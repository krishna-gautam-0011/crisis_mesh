package com.example.p2pchat

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.p2pchat.access.DeviceAccess
import com.example.p2pchat.adapters.MessageVariant
import com.example.p2pchat.adapters.MessagesAdapter
import com.example.p2pchat.adapters.PeerItem
import com.example.p2pchat.adapters.UsersAdapter
import com.example.p2pchat.databinding.ActivityChatBinding
import com.example.p2pchat.maps.GeoCodec
import com.example.p2pchat.maps.GeoPoint

class ChatActivity : AppCompatActivity(), NearbyManager.Listener {

    private lateinit var binding: ActivityChatBinding
    private lateinit var store: MessageStore
    private lateinit var crypto: CryptoManager
    private lateinit var myUserId: String

    // The actual mesh networking lives in ChatForegroundService so it keeps running while this
    // activity isn't visible (see that class's kdoc). This activity starts it, binds to it for
    // live callbacks while visible, and unbinds (but does NOT stop it) once backgrounded.
    private var chatService: ChatForegroundService? = null
    private var boundToService = false
    private var pendingOpenPrivateWith: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as ChatForegroundService.LocalBinder).getService()
            chatService = service
            service.attachUiListener(this@ChatActivity)
            // Catch up on anything that changed (new messages, peers) while we weren't bound.
            lastOnlineUserIds = service.nearbyManager.getOnlineUserIds()
            pendingOpenPrivateWith?.let { selectPeer(it) }
            pendingOpenPrivateWith = null
            refreshMessageLists()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            chatService = null
        }
    }

    /** Who the private card is currently showing a conversation with, if anyone. */
    private var selectedPeerId: String? = null

    private var lastOnlineUserIds: List<String> = emptyList()

    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val cleanupIntervalMs = 30_000L
    private val cleanupTick = object : Runnable {
        override fun run() {
            // Sweeps anything that just crossed the 24h TTL so it visibly vanishes
            // from the UI even if no new message/connection event triggers a refresh.
            if (store.pruneExpired()) refreshMessageLists()
            cleanupHandler.postDelayed(this, cleanupIntervalMs)
        }
    }

    // Fires the lockout at the deadline of a delayed (bare "killmeqwerty") revoke.
    private val revokeHandler = Handler(Looper.getMainLooper())

    private var lastKnownLocation: GeoPoint? = null
    private var locationManager: LocationManager? = null
    private val locationListener = LocationListener { location: Location ->
        lastKnownLocation = GeoPoint(location.latitude, location.longitude)
    }

    private val usersAdapter = UsersAdapter(onPeerClick = { userId -> selectPeer(userId) })
    private val publicAdapter = MessagesAdapter(
        variant = MessageVariant.PUBLIC,
        showTarget = false,
        onMessageClick = { message -> openLocationIfAny(message) }
    )
    private val privateAdapter = MessagesAdapter(
        variant = MessageVariant.PRIVATE,
        showTarget = false,
        onMessageClick = { message -> openLocationIfAny(message) }
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // POST_NOTIFICATIONS (Android 13+) is nice-to-have, not required: someone who denies
            // it should still get the full mesh chat, just without new-message notifications.
            val coreGranted = results
                .filterKeys { it != POST_NOTIFICATIONS_PERMISSION }
                .values
                .all { it }
            if (coreGranted) {
                startChatService()
                requestLocationUpdatesIfPermitted()
            } else {
                Toast.makeText(
                    this,
                    "All permissions are required to discover and connect to nearby devices",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        myUserId = prefs.getString(MainActivity.KEY_USER_ID, null) ?: "UnknownUser"
        binding.myIdText.text = "Me: $myUserId"
        binding.myAvatarInitial.text = myUserId.take(1).uppercase()

        store = MessageStore(this)
        crypto = CryptoManager(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        intent?.getStringExtra(EXTRA_OPEN_PRIVATE_WITH)?.let { pendingOpenPrivateWith = it }

        setupRecyclerViews()
        setupButtons()
        refreshMessageLists()
        updatePrivateHeader()

        requestNeededPermissions()
        cleanupHandler.postDelayed(cleanupTick, cleanupIntervalMs)

        // A delayed revoke may already be counting down from an earlier session.
        DeviceAccess.pendingRevokeAt(this)?.let { scheduleLockoutTimer(it) }
    }

    override fun onStart() {
        super.onStart()
        // Re-attach for live callbacks (and implicitly resume notification suppression) any
        // time this screen becomes visible again, including after being backgrounded.
        if (!boundToService) {
            val bound = bindService(
                Intent(this, ChatForegroundService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            boundToService = bound
        }
    }

    override fun onStop() {
        super.onStop()
        // Un-bind, but deliberately do NOT stop the service - it (and the mesh it runs) keeps
        // going in the background, per ChatForegroundService's kdoc, and starts posting
        // notifications for new messages again now that nothing is bound to show them live.
        if (boundToService) {
            chatService?.detachUiListener(this)
            unbindService(serviceConnection)
            boundToService = false
        }
        chatService = null
    }

    override fun onResume() {
        super.onResume()
        // Catches a deadline that passed while the app was in the background. Stops the
        // background mesh service too - see AccessGuard.lockOutIfRevoked.
        lockOutIfRevoked()
    }

    private fun startChatService() {
        val intent = Intent(this, ChatForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
        if (!boundToService) {
            boundToService = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    /** Fully stops the mesh + background service, e.g. because this device was just revoked. */
    private fun stopChatService() {
        if (boundToService) {
            chatService?.detachUiListener(this)
            unbindService(serviceConnection)
            boundToService = false
        }
        chatService = null
        startService(Intent(this, ChatForegroundService::class.java).setAction(ChatForegroundService.ACTION_STOP))
    }

    private fun scheduleLockoutTimer(revokeAtMillis: Long) {
        revokeHandler.removeCallbacksAndMessages(null)
        val wait = (revokeAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        revokeHandler.postDelayed({
            if (DeviceAccess.isRevoked(this)) onDeviceRevoked()
        }, wait)
    }

    private fun setupRecyclerViews() {
        binding.usersRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.usersRecyclerView.adapter = usersAdapter

        binding.publicRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.publicRecyclerView.adapter = publicAdapter

        binding.privateRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.privateRecyclerView.adapter = privateAdapter
    }

    /** The live NearbyManager, if the background service is bound yet (near-instant after
     *  startChatService(), but not literally synchronous). */
    private fun nearby(): NearbyManager? {
        val manager = chatService?.nearbyManager
        if (manager == null) {
            Toast.makeText(this, "Still starting the mesh — try again in a second", Toast.LENGTH_SHORT).show()
        }
        return manager
    }

    private fun setupButtons() {
        binding.sendPublicButton.setOnClickListener {
            val text = binding.messageInput.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Type a message first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val message = ChatMessage(
                senderId = myUserId,
                targetId = null,
                content = text,
                type = MessageType.PUBLIC
            )
            nearby()?.broadcast(message)
            binding.messageInput.setText("")
            refreshMessageLists()
        }

        binding.sendPrivateButton.setOnClickListener {
            val text = binding.messageInput.text.toString().trim()
            // Typed recipient id (works even for a peer that's out of range but reachable
            // via mesh gossip-relay) takes priority; otherwise fall back to whoever is
            // currently tap-selected in the Online list.
            val typedTarget = binding.recipientIdInput.text.toString().trim()
            val target = typedTarget.ifEmpty { selectedPeerId }
            if (text.isEmpty()) {
                Toast.makeText(this, "Type a message first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (target == null) {
                Toast.makeText(this, "Type a recipient user id above, or tap a nearby user, first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val message = ChatMessage(
                senderId = myUserId,
                targetId = target,
                content = text,
                type = MessageType.PRIVATE
            )
            nearby()?.sendPrivate(message)
            binding.messageInput.setText("")
            refreshMessageLists()
        }

        binding.sendCoordinateButton.setOnClickListener {
            val point = lastKnownLocation
            if (point == null) {
                Toast.makeText(this, "Still finding your GPS location — try again in a moment", Toast.LENGTH_SHORT).show()
                requestLocationUpdatesIfPermitted()
                return@setOnClickListener
            }
            val content = GeoCodec.encode(point.copy(label = myUserId))
            val typedTarget = binding.recipientIdInput.text.toString().trim()
            val target = typedTarget.ifEmpty { selectedPeerId }
            if (target != null) {
                val message = ChatMessage(
                    senderId = myUserId,
                    targetId = target,
                    content = content,
                    type = MessageType.PRIVATE
                )
                nearby()?.sendPrivate(message)
                Toast.makeText(this, "Location sent privately to $target", Toast.LENGTH_SHORT).show()
            } else {
                val message = ChatMessage(
                    senderId = myUserId,
                    targetId = null,
                    content = content,
                    type = MessageType.PUBLIC
                )
                nearby()?.broadcast(message)
                Toast.makeText(this, "Location broadcast publicly", Toast.LENGTH_SHORT).show()
            }
            refreshMessageLists()
        }
    }

    /** Opens the offline India map centred on a message's coordinate, if it carries one. */
    private fun openLocationIfAny(message: ChatMessage) {
        val point = GeoCodec.decode(message.content) ?: return
        val intent = Intent(this, MapsActivity::class.java).apply {
            putExtra(MapsActivity.EXTRA_LAT, point.lat)
            putExtra(MapsActivity.EXTRA_LNG, point.lng)
            putExtra(MapsActivity.EXTRA_FROM, message.senderId)
        }
        startActivity(intent)
    }

    private fun requestLocationUpdatesIfPermitted() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return
        val lm = locationManager ?: return
        try {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(provider)) {
                    lm.getLastKnownLocation(provider)?.let { lastKnownLocation = GeoPoint(it.latitude, it.longitude) }
                    lm.requestLocationUpdates(provider, 3000L, 5f, locationListener)
                }
            }
        } catch (e: SecurityException) {
            // permission revoked between the check and the call; nothing to do
        }
    }

    private fun selectPeer(userId: String) {
        selectedPeerId = if (selectedPeerId == userId) null else userId
        usersAdapter.setSelected(selectedPeerId)
        // Mirror the tap into the typed field too, so both ways of picking a
        // recipient stay in sync and visible.
        binding.recipientIdInput.setText(selectedPeerId ?: "")
        updatePrivateHeader()
        refreshMessageLists()
    }

    private fun updatePrivateHeader() {
        val peer = selectedPeerId
        if (peer == null) {
            binding.privateTargetText.text = "Tap a user below for a private chat"
            binding.privateHeaderText.text = "Private inbox"
        } else {
            binding.privateTargetText.text = "Private chat with $peer — tap again to close"
            binding.privateHeaderText.text = "Private • $peer"
        }
    }

    private fun refreshMessageLists() {
        val publicMessages = store.getPublicMessages()
        publicAdapter.submit(publicMessages)
        binding.emptyPublicText.visibility = if (publicMessages.isEmpty()) View.VISIBLE else View.GONE

        val peer = selectedPeerId
        val privateMessages = if (peer != null) {
            store.getPrivateThread(myUserId, peer).map { decryptForDisplay(it) }
        } else {
            emptyList()
        }
        privateAdapter.submit(privateMessages)
        binding.emptyPrivateText.visibility = if (privateMessages.isEmpty()) View.VISIBLE else View.GONE

        refreshUsersList()
    }

    /** Our own authored copies are already plain text; anything that arrived off the wire needs unwrapping. */
    private fun decryptForDisplay(message: ChatMessage): ChatMessage {
        if (!message.encrypted) return message
        if (message.targetId != myUserId) return message // shouldn't happen, but never try to open others' mail
        val plain = crypto.decryptMine(message.content) ?: "🔒 (couldn't decrypt this message)"
        return message.copy(content = plain, encrypted = false)
    }

    /** Merges currently-connected peers with recent (still within 24h) private-chat partners who stepped out of range. */
    private fun refreshUsersList() {
        val online = lastOnlineUserIds.toSet()
        val known = store.getKnownPrivatePeers(myUserId)
        val allPeerIds = (online + known).toList().sortedWith(compareByDescending<String> { it in online }.thenBy { it })

        val items = allPeerIds.map { PeerItem(userId = it, online = it in online) }
        usersAdapter.submit(items)
        binding.onlineCountText.text = online.size.toString()
        binding.emptyUsersText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    // --------------------------------------------------------- permissions

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_ADVERTISE
            needed += Manifest.permission.BLUETOOTH_CONNECT
            needed += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        needed += Manifest.permission.ACCESS_FINE_LOCATION
        needed += Manifest.permission.ACCESS_WIFI_STATE
        needed += Manifest.permission.CHANGE_WIFI_STATE
        // Optional (see permissionLauncher above): lets the background service notify about
        // new messages once this screen isn't visible. Requested alongside the required ones
        // so the person only sees one permission round-trip, not two.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        val toRequest = needed.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isEmpty()) {
            startChatService()
            requestLocationUpdatesIfPermitted()
        } else {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    // ------------------------------------------------------- Nearby.Listener

    override fun onOnlineUsersChanged(userIds: List<String>) {
        runOnUiThread {
            lastOnlineUserIds = userIds
            refreshUsersList()
        }
    }

    override fun onMessagesUpdated() {
        runOnUiThread { refreshMessageLists() }
    }

    override fun onRevokeScheduled(revokeAtMillis: Long) {
        runOnUiThread { scheduleLockoutTimer(revokeAtMillis) }
    }

    override fun onDeviceRevoked() {
        runOnUiThread {
            stopChatService()
            // Back to the launcher, which now shows the "revoked" screen; wipe the back stack.
            startActivity(
                Intent(this, LauncherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
        }
    }

    override fun onStatus(text: String) {
        runOnUiThread { binding.statusText.text = text }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupHandler.removeCallbacksAndMessages(null)
        revokeHandler.removeCallbacksAndMessages(null)
        // Deliberately NOT stopping the service here - see onStop(). The mesh (and the
        // notifications it can now post) is meant to keep running in the background for as
        // long as the app is "open" from the user's point of view, i.e. until they explicitly
        // go offline from the notification, or the device is revoked (see onDeviceRevoked).
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (e: SecurityException) {
            // nothing to clean up if we never had permission
        }
    }

    companion object {
        /** Extra carrying a peer's user id: set when a private-message notification is tapped,
         *  so the private card opens straight to that conversation. */
        const val EXTRA_OPEN_PRIVATE_WITH = "extra_open_private_with"
        private val POST_NOTIFICATIONS_PERMISSION: String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.POST_NOTIFICATIONS
            } else {
                "android.permission.POST_NOTIFICATIONS"
            }
    }
}
