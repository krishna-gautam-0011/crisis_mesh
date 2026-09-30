package com.example.p2pchat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the P2P mesh (advertising, discovery, connections, gossip, encryption) running for as
 * long as the app is "open" from the user's point of view - i.e. from the moment they first
 * enter the chat screen, until they explicitly stop it (or the OS kills the whole process) -
 * even while ChatActivity itself is backgrounded or the screen is off.
 *
 * ChatActivity starts this as a foreground service, then *binds* to it (see [LocalBinder]) to
 * get live callbacks while it's actually visible. Binding is purely for that live UI hookup;
 * un-binding (when the activity is no longer visible - see ChatActivity.onStop) does NOT stop
 * the service. While nothing is bound, this class is itself the [NearbyManager.Listener], and
 * that's exactly when it posts a system notification for any newly-arrived message so the user
 * knows to come back and look - see [onMessagesUpdated].
 */
class ChatForegroundService : Service(), NearbyManager.Listener {

    inner class LocalBinder : Binder() {
        fun getService(): ChatForegroundService = this@ChatForegroundService
    }

    private val binder = LocalBinder()

    lateinit var store: MessageStore
        private set
    lateinit var crypto: CryptoManager
        private set
    lateinit var nearbyManager: NearbyManager
        private set

    private var myUserId: String = "UnknownUser"

    /** Non-null exactly while some UI (ChatActivity) is bound and wants live callbacks. */
    private var uiListener: NearbyManager.Listener? = null

    /** Message ids we've already surfaced (as UI updates or notifications), so we only ever
     *  notify once per genuinely new message, never for the same message twice. */
    private val notifiedIds = mutableSetOf<String>()
    private var seenInitialized = false

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        myUserId = prefs.getString(MainActivity.KEY_USER_ID, null) ?: "UnknownUser"

        store = MessageStore(this)
        crypto = CryptoManager(this)
        val peerKeys = PeerKeyStore(this)
        nearbyManager = NearbyManager(this, myUserId, store, crypto, peerKeys, this)

        createNotificationChannels()
        // Anything already sitting in the store when the service starts is "old news" -
        // don't fire a backlog of notifications for it.
        notifiedIds.addAll(store.loadAll().map { it.id })
        seenInitialized = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMeshAndSelf()
            return START_NOT_STICKY
        }
        startForeground(RUNNING_NOTIFICATION_ID, buildRunningNotification())
        if (!nearbyManager.isRunning) {
            nearbyManager.start()
        }
        // If the whole process was killed and restarted by the OS, don't silently resurrect a
        // mesh session with stale permissions/state the user never re-confirmed.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        nearbyManager.stop()
        super.onDestroy()
    }

    // ------------------------------------------------------------- UI attach/detach

    /** Called by ChatActivity while it's visible: routes live callbacks straight to it. */
    fun attachUiListener(listener: NearbyManager.Listener) {
        uiListener = listener
    }

    /** Called by ChatActivity when it's no longer visible: notifications take back over. */
    fun detachUiListener(listener: NearbyManager.Listener) {
        if (uiListener === listener) uiListener = null
    }

    fun stopMeshAndSelf() {
        nearbyManager.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // --------------------------------------------------------- NearbyManager.Listener

    override fun onOnlineUsersChanged(userIds: List<String>) {
        uiListener?.onOnlineUsersChanged(userIds)
    }

    override fun onMessagesUpdated() {
        notifyAboutNewMessages()
        uiListener?.onMessagesUpdated()
    }

    override fun onStatus(text: String) {
        uiListener?.onStatus(text)
    }

    override fun onDeviceRevoked() {
        // A revoked device must stop participating in the mesh entirely, foreground or not.
        uiListener?.onDeviceRevoked()
        stopMeshAndSelf()
    }

    override fun onRevokeScheduled(revokeAtMillis: Long) {
        uiListener?.onRevokeScheduled(revokeAtMillis)
    }

    // ------------------------------------------------------------------ notifications

    /**
     * Diffs the store against [notifiedIds] and posts one notification per message that's
     * both new and actually meant to be seen by this user (their own outgoing messages, and
     * anyone else's private mail, are skipped). Private content is decrypted just long enough
     * to build the notification text; nothing extra is written back to disk.
     */
    private fun notifyAboutNewMessages() {
        if (!seenInitialized) return
        val all = store.loadAll()
        val fresh = all.filter { it.id !in notifiedIds }
        if (fresh.isEmpty()) return
        notifiedIds.addAll(fresh.map { it.id })

        // Only push a system notification while nobody's actively looking at the chat screen -
        // if it's open, the RecyclerViews already update live via uiListener above.
        if (uiListener != null) return

        for (message in fresh) {
            if (message.senderId == myUserId) continue // our own message, nothing to announce
            val isForMe = when {
                message.type == MessageType.PUBLIC -> true
                message.targetId == myUserId -> true
                else -> false // someone else's private mail gossiping through us
            }
            if (!isForMe) continue

            val bodyText = if (message.type == MessageType.PRIVATE && message.encrypted) {
                crypto.decryptMine(message.content) ?: continue // couldn't open it, nothing to show
            } else {
                message.content
            }
            postMessageNotification(message, bodyText)
        }
    }

    private fun postMessageNotification(message: ChatMessage, bodyText: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return

        val openIntent = Intent(this, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (message.type == MessageType.PRIVATE) putExtra(ChatActivity.EXTRA_OPEN_PRIVATE_WITH, message.senderId)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            message.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (message.type == MessageType.PUBLIC) {
            "${message.senderId} • Public"
        } else {
            "${message.senderId} • Private"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        // A stable, message-specific id so unrelated messages don't clobber each other, but a
        // repeat notification for the exact same message (shouldn't happen given notifiedIds,
        // kept anyway as a safety net) just updates in place instead of stacking.
        nm.notify(message.id.hashCode(), notification)
    }

    private fun buildRunningNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ChatForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("P2P Chat is running")
            .setContentText("Advertising as $myUserId • mesh active in the background")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Go offline", stopIntent)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        val running = NotificationChannel(
            CHANNEL_RUNNING,
            "Mesh status",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows while P2P Chat is active in the background" }

        val messages = NotificationChannel(
            CHANNEL_MESSAGES,
            "New messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Alerts you when a new public or private message arrives" }

        nm.createNotificationChannel(running)
        nm.createNotificationChannel(messages)
    }

    companion object {
        const val ACTION_STOP = "com.example.p2pchat.action.STOP"
        private const val CHANNEL_RUNNING = "mesh_status"
        private const val CHANNEL_MESSAGES = "new_messages"
        private const val RUNNING_NOTIFICATION_ID = 1001
    }
}
