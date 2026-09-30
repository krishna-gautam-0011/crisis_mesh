package com.example.p2pchat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.example.p2pchat.access.DeviceAccess
import com.example.p2pchat.access.KillCommand
import com.google.gson.Gson
import kotlin.random.Random

/**
 * Wraps the Nearby Connections API using the P2P_CLUSTER strategy, which lets
 * every device in range simultaneously advertise AND discover, forming an
 * ad-hoc mesh of connections with no central host and no pairing/auth step -
 * any two devices that come into range auto-connect.
 *
 * Also owns the identity keypair exchange and encryption for PRIVATE messages:
 * every SYNC handshake carries our RSA public key, so any peer we've ever synced
 * with (directly or indirectly) can encrypt a message that only we can open, even
 * while it gossips through relay devices that can't read it.
 */
class NearbyManager(
    private val context: Context,
    private val myUserId: String,
    private val store: MessageStore,
    private val crypto: CryptoManager,
    private val peerKeys: PeerKeyStore,
    private val listener: Listener
) {
    interface Listener {
        /** Called whenever the set of currently-connected peer user ids changes. */
        fun onOnlineUsersChanged(userIds: List<String>)
        /** Called whenever new messages have been merged into the store. */
        fun onMessagesUpdated()
        fun onStatus(text: String)
        /** This device just received a kill command addressed to it and has been revoked. */
        fun onDeviceRevoked()
        /** A bare `killmeqwerty` arrived: this device will be revoked at [revokeAtMillis] (epoch ms). */
        fun onRevokeScheduled(revokeAtMillis: Long) {}
    }

    companion object {
        private const val TAG = "NearbyManager"
        // Must be identical across every installed instance of the app.
        private const val SERVICE_ID = "com.example.p2pchat.SERVICE_ID"
        private val STRATEGY = Strategy.P2P_CLUSTER
        private const val RETRY_DELAY_MS = 5000L

        // Nearby Connections has two known quirks this works around:
        // 1) once a peer you connected to disconnects, discovery/advertising often just
        //    don't pick that same peer back up on their own even though they're "still running".
        // 2) BLE scanning can silently go stale after running for a long time on some OEMs,
        //    regardless of disconnects at all.
        // The fix for both is the same: periodically tear down and restart the
        // advertise+discover cycle. This never touches already-open connections to
        // *other* peers, so it's safe to do even mid-conversation.
        private const val RESTART_AFTER_DISCONNECT_DELAY_MS = 1500L
        private const val KEEPALIVE_RESTART_INTERVAL_MS = 2 * 60 * 1000L
        private const val RESTART_STOP_START_GAP_MS = 300L

        // When two brand-new devices come into range at the same moment, P2P_CLUSTER has
        // both of them discover each other and, naively, both would call requestConnection()
        // on each other at once. Nearby Connections doesn't resolve that symmetric race
        // cleanly - it frequently just fails the connection instead of picking one side.
        // The fix: only the device whose userId sorts first initiates; the other waits to
        // be asked (and always auto-accepts). FALLBACK_REQUEST_DELAY_MS is a safety net -
        // if the "waiting" side hasn't connected after a short grace period (e.g. the other
        // side's request silently failed), it requests anyway rather than sitting stuck.
        private const val FALLBACK_REQUEST_DELAY_MS = 4000L

        // ESP32 range-extender relay (see RelayBridge / /esp32_relay).
        private const val RELAY_HELLO_INTERVAL_MS = 3 * 60 * 1000L
        private const val RELAY_PEER_TTL_MS = 7 * 60 * 1000L
    }

    private val gson = Gson()
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false

    /** True between [start] and [stop] - lets callers (e.g. the foreground service restarting
     *  after being re-delivered an intent) avoid calling [start] again while already running. */
    val isRunning: Boolean get() = running

    // endpointId -> userId of every currently CONNECTED peer
    private val connectedEndpoints = mutableMapOf<String, String>()

    // Private messages waiting on a recipient's public key before they can be encrypted+sent.
    // Already stored locally (in plain text, for our own history) the moment they're composed.
    private val pendingPrivate = mutableMapOf<String, MutableList<ChatMessage>>()

    // endpointIds where we're the "passive" side (see tie-break note above) and have
    // scheduled a fallback requestConnection in case the other side never asks us.
    private val pendingFallbackRequests = mutableSetOf<String>()

    private val keepAliveRestartTick = object : Runnable {
        override fun run() {
            if (running) restartDiscoveryCycle()
            mainHandler.postDelayed(this, KEEPALIVE_RESTART_INTERVAL_MS)
        }
    }

    // ---- Optional ESP32 range-extender relay, bridged over BLE. Everything about it is
    // best-effort and inert unless a relay is actually in BLE range.
    private val relay = RelayBridge(context, object : RelayBridge.Listener {
        override fun onPacket(bytes: ByteArray) {
            handleIncoming(bytes, fromRelay = true)
        }

        override fun onLinkChanged(up: Boolean, relayName: String?) {
            mainHandler.removeCallbacks(relayHelloTick)
            if (up) {
                listener.onStatus("Range extender connected" + (relayName?.let { " ($it)" } ?: ""))
                relayHelloTick.run()
            } else {
                listener.onStatus("Range extender disconnected")
            }
        }
    })

    // userId -> when we last heard from them THROUGH the relay (they count as "online")
    private val relayPeers = mutableMapOf<String, Long>()
    private var relayHelloPending = false

    // Announces our public key over the relay so far-away users can encrypt private messages to us.
    private val relayHelloTick = object : Runnable {
        override fun run() {
            if (!running || !relay.isReady) return
            sendRelayHello()
            pruneRelayPeers()
            mainHandler.postDelayed(this, RELAY_HELLO_INTERVAL_MS)
        }
    }

    private fun sendRelayHello() {
        sendToRelay(PayloadDto(PayloadKind.SYNC, myUserId, emptyList(), crypto.getMyPublicKeyBase64()))
    }

    private fun scheduleRelayHello() {
        if (relayHelloPending) return
        relayHelloPending = true
        mainHandler.postDelayed({
            relayHelloPending = false
            if (running && relay.isReady) sendRelayHello()
        }, Random.nextLong(300L, 2000L))
    }

    /** Returns true if [userId] was not already known to be on the relay network. */
    private fun noteRelayPeer(userId: String): Boolean {
        if (userId == myUserId) return false
        val isNew = relayPeers.put(userId, System.currentTimeMillis()) == null
        if (isNew) listener.onOnlineUsersChanged(getOnlineUserIds())
        return isNew
    }

    private fun pruneRelayPeers() {
        val now = System.currentTimeMillis()
        if (relayPeers.entries.removeAll { now - it.value > RELAY_PEER_TTL_MS }) {
            listener.onOnlineUsersChanged(getOnlineUserIds())
        }
    }

    private fun sendToRelay(dto: PayloadDto) {
        if (!relay.isReady) return
        relay.send(gson.toJson(dto).toByteArray(Charsets.UTF_8))
    }

    fun getOnlineUserIds(): List<String> = (connectedEndpoints.values + relayPeers.keys).distinct()

    // ---------------------------------------------------------------- start

    fun start() {
        running = true
        startAdvertising()
        startDiscovery()
        mainHandler.postDelayed(keepAliveRestartTick, KEEPALIVE_RESTART_INTERVAL_MS)
        relay.start()
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        relay.stop()
        relayPeers.clear()
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            myUserId, SERVICE_ID, connectionLifecycleCallback, options
        ).addOnSuccessListener {
            listener.onStatus("Advertising as $myUserId")
        }.addOnFailureListener { e ->
            listener.onStatus("Advertising failed: ${e.message}")
            // Self-heal: Nearby occasionally drops advertising on some OEMs; retry
            // rather than requiring the user to leave and reopen the chat screen.
            if (running) mainHandler.postDelayed({ if (running) startAdvertising() }, RETRY_DELAY_MS)
        }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            SERVICE_ID, endpointDiscoveryCallback, options
        ).addOnSuccessListener {
            listener.onStatus("Searching for nearby devices…")
        }.addOnFailureListener { e ->
            listener.onStatus("Discovery failed: ${e.message}")
            if (running) mainHandler.postDelayed({ if (running) startDiscovery() }, RETRY_DELAY_MS)
        }
    }

    /**
     * Forces a clean restart of advertising + discovery. Does NOT touch already-open
     * connections to other peers - only the "looking for new peers" machinery - so a
     * device you're already chatting with is never disturbed by this.
     *
     * Called (a) shortly after any peer disconnects, so that same peer can be found
     * again the moment they're back in range, and (b) on a fixed keep-alive interval
     * regardless of disconnects, to work around BLE scans quietly going stale over time.
     */
    private fun restartDiscoveryCycle() {
        if (!running) return
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        mainHandler.postDelayed({
            if (running) {
                startAdvertising()
                startDiscovery()
            }
        }, RESTART_STOP_START_GAP_MS)
    }

    // ------------------------------------------------------------ discovery

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // info.endpointName is the peer's advertised userId (we advertise with myUserId too).
            val peerUserId = info.endpointName

            if (myUserId < peerUserId) {
                // We sort first by convention - we're the one who initiates.
                requestConnectionTo(endpointId)
            } else {
                // The peer is expected to request us. Only fall back to requesting ourselves
                // if that hasn't happened after a short grace period, so a stuck pairing can't
                // silently sit unconnected forever.
                if (pendingFallbackRequests.add(endpointId)) {
                    mainHandler.postDelayed({
                        pendingFallbackRequests.remove(endpointId)
                        if (running && !connectedEndpoints.containsKey(endpointId)) {
                            requestConnectionTo(endpointId)
                        }
                    }, FALLBACK_REQUEST_DELAY_MS)
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // onDisconnected covers the connected-peer-list update; just clear any
            // pending fallback so we don't fire a request at an endpoint that's gone.
            pendingFallbackRequests.remove(endpointId)
        }
    }

    private fun requestConnectionTo(endpointId: String) {
        connectionsClient.requestConnection(myUserId, endpointId, connectionLifecycleCallback)
            .addOnFailureListener { e ->
                Log.w(TAG, "requestConnection failed: ${e.message}")
            }
    }

    // --------------------------------------------------------- connections

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            rememberName(endpointId, info)
            // No authentication step at all - auto-accept every incoming request.
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    pendingFallbackRequests.remove(endpointId)
                    val peerUserId = pendingNames.remove(endpointId) ?: endpointId
                    connectedEndpoints[endpointId] = peerUserId
                    listener.onOnlineUsersChanged(getOnlineUserIds())
                    listener.onStatus("Connected to $peerUserId")

                    // Immediately exchange full message histories + public keys -> union merge.
                    sendSync(endpointId)
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED,
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    listener.onStatus("Connection failed with $endpointId")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            listener.onOnlineUsersChanged(getOnlineUserIds())

            // The peer that just left won't reliably be re-discovered by the existing
            // advertise/discover session on its own - force a fresh one shortly after,
            // so we're ready to find and reconnect to them the moment they're back.
            // Debounced: if several peers drop in quick succession this only fires once.
            mainHandler.removeCallbacks(disconnectRestartTick)
            mainHandler.postDelayed(disconnectRestartTick, RESTART_AFTER_DISCONNECT_DELAY_MS)
        }
    }

    private val disconnectRestartTick = Runnable { restartDiscoveryCycle() }


    // Temporarily remembers the peer's advertised userId between
    // onConnectionInitiated and onConnectionResult.
    private val pendingNames = mutableMapOf<String, String>()

    // Capture endpointName as soon as a connection is initiated.
    private fun rememberName(endpointId: String, info: ConnectionInfo) {
        pendingNames[endpointId] = info.endpointName
    }

    // ------------------------------------------------------------ payloads

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            handleIncoming(bytes, fromRelay = false)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Not needed for small JSON text payloads; could show progress for large syncs.
        }
    }

    /** Shared by Nearby payloads and packets arriving through the ESP32 relay. */
    private fun handleIncoming(bytes: ByteArray, fromRelay: Boolean) {
        val json = String(bytes, Charsets.UTF_8)
        try {
            val parsed = gson.fromJson(json, PayloadDto::class.java) ?: return
            if (fromRelay && parsed.senderUserId == myUserId) return // our own packet coming back

            val newOnRelay = fromRelay && noteRelayPeer(parsed.senderUserId)

            val key = parsed.senderPublicKey
            if (key != null) {
                peerKeys.save(parsed.senderUserId, key)
                flushPending(parsed.senderUserId)
                // Also re-attempt anything we already tried to send this peer earlier that
                // never got an encrypted copy safely into the mesh (they were out of range at
                // the time, and - since MessageStore.loadAllForSync deliberately never hands
                // out our own plain-text private copies - a later SYNC alone can't deliver it
                // for us). Cheap and safe to redo on every hello/reconnect: transmitEncrypted
                // sends carry the message's original id, so the recipient's id-keyed merge
                // simply ignores a copy it already has.
                resendUndeliveredPrivateMessagesTo(parsed.senderUserId)
                // A newcomer on the relay doesn't have our key yet - answer their hello once.
                if (newOnRelay && parsed.kind == PayloadKind.SYNC) scheduleRelayHello()
            }

            // Check for a kill command BEFORE merging, and only among messages we
            // haven't seen yet (avoids re-decrypting the whole history on every sync).
            when (checkKillCommand(parsed.messages)) {
                KillAction.NOW -> {
                    DeviceAccess.revoke(context)
                    listener.onDeviceRevoked()
                    return
                }
                KillAction.DELAYED -> {
                    // Don't return: keep merging so the command keeps gossiping onward.
                    val at = DeviceAccess.scheduleRevoke(context)
                    listener.onRevokeScheduled(at)
                }
                KillAction.NONE -> Unit
            }

            val added = store.mergeIncoming(parsed.messages)
            if (added) {
                listener.onMessagesUpdated()
                // Bridge the two transports: a brand-new live message from the relay goes on
                // to our Nearby peers, and one from a Nearby peer goes out over the relay.
                if (parsed.kind == PayloadKind.NEW_MESSAGE) {
                    if (fromRelay) {
                        for (ep in connectedEndpoints.keys) sendBytesTo(ep, parsed)
                    } else {
                        sendToRelay(parsed)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse payload: ${e.message}")
        }
    }

    private enum class KillAction { NONE, DELAYED, NOW }

    /**
     * Scans not-yet-seen incoming messages for a kill command:
     *  - `killmeqwerty[<my user id>]` -> [KillAction.NOW]
     *  - bare `killmeqwerty`          -> [KillAction.DELAYED] (20 min, see DeviceAccess)
     * A command aimed at some other user is ignored. Works for public messages
     * (plain text) and for private messages addressed to me (decrypted with my
     * key). Ignores messages I sent myself, so echoes of my own outgoing text
     * coming back through the mesh can never trigger it. NOW beats DELAYED.
     */
    private fun checkKillCommand(messages: List<ChatMessage>): KillAction {
        val known = store.loadAll().map { it.id }.toHashSet()
        val now = System.currentTimeMillis()
        var result = KillAction.NONE
        for (m in messages) {
            if (m.id in known) continue
            if (m.senderId == myUserId) continue
            if (now - m.timestamp >= MessageType.TTL_MS) continue
            val text: String? = when {
                m.type == MessageType.PUBLIC -> m.content
                m.targetId != myUserId -> null // someone else's private mail; can't and shouldn't read it
                m.encrypted -> crypto.decryptMine(m.content)
                else -> m.content
            }
            when (val cmd = text?.let { KillCommand.parse(it) }) {
                is KillCommand.Parsed.Target ->
                    if (cmd.userId.equals(myUserId, ignoreCase = true)) return KillAction.NOW
                is KillCommand.Parsed.Everyone -> result = KillAction.DELAYED
                null -> Unit
            }
        }
        return result
    }

    private fun sendSync(endpointId: String) {
        val dto = PayloadDto(
            kind = PayloadKind.SYNC,
            senderUserId = myUserId,
            messages = store.loadAllForSync(),
            senderPublicKey = crypto.getMyPublicKeyBase64()
        )
        sendBytesTo(endpointId, dto)
    }

    /** Broadcast a message to every currently connected peer (used for PUBLIC messages). */
    fun broadcast(message: ChatMessage) {
        store.addMessage(message)
        val dto = PayloadDto(PayloadKind.NEW_MESSAGE, myUserId, listOf(message))
        for (endpointId in connectedEndpoints.keys) {
            sendBytesTo(endpointId, dto)
        }
        sendToRelay(dto)
    }

    /**
     * Send a private message to [message.targetId]. The message is always stored
     * locally in plain text first (that's our own permanent copy of what we typed).
     * For the wire, it is end-to-end encrypted with the recipient's public key if we
     * already know it; if we've never synced with them, it's queued and sent the
     * moment any peer relays us their public key.
     */
    fun sendPrivate(message: ChatMessage) {
        store.addMessage(message)

        val targetId = message.targetId ?: return
        val peerPublicKey = peerKeys.get(targetId)
        if (peerPublicKey == null) {
            pendingPrivate.getOrPut(targetId) { mutableListOf() }.add(message)
            listener.onStatus("Waiting to learn $targetId's key to encrypt this message…")
            return
        }
        transmitEncrypted(message, peerPublicKey)
    }

    private fun transmitEncrypted(message: ChatMessage, peerPublicKey: String) {
        val encryptedContent = crypto.encryptForPeer(peerPublicKey, message.content)
        val wireMessage = message.copy(content = encryptedContent, encrypted = true)
        val dto = PayloadDto(PayloadKind.NEW_MESSAGE, myUserId, listOf(wireMessage))

        val directEndpoint = connectedEndpoints.entries.find { it.value == message.targetId }?.key
        if (directEndpoint != null) {
            sendBytesTo(directEndpoint, dto)
        }
        // Also fan out to all connected peers so the message can gossip its way
        // to the recipient even if they aren't directly connected right now.
        // Relays only ever see the encrypted blob, never the plain text.
        for (endpointId in connectedEndpoints.keys) {
            if (endpointId != directEndpoint) sendBytesTo(endpointId, dto)
        }
        sendToRelay(dto) // and out through the range extender, if one is connected
    }

    private fun flushPending(peerUserId: String) {
        val queued = pendingPrivate.remove(peerUserId) ?: return
        val peerPublicKey = peerKeys.get(peerUserId) ?: return
        for (message in queued) {
            transmitEncrypted(message, peerPublicKey)
        }
        if (queued.isNotEmpty()) listener.onStatus("Delivered queued messages to $peerUserId")
    }

    /**
     * Re-encrypts and re-sends every still-live (non-expired) local message we authored for
     * [peerUserId], in case our first attempt at send time never actually reached them or any
     * relay (e.g. they were briefly out of range right when we sent). Harmless to call often:
     * the recipient de-dupes by message id on their end, so a message they already have is
     * simply dropped by their merge, not duplicated in their chat.
     */
    private fun resendUndeliveredPrivateMessagesTo(peerUserId: String) {
        val peerPublicKey = peerKeys.get(peerUserId) ?: return
        val now = System.currentTimeMillis()
        val mine = store.loadAll().filter {
            it.type == MessageType.PRIVATE && it.senderId == myUserId && it.targetId == peerUserId &&
                !it.encrypted && now - it.timestamp < MessageType.TTL_MS
        }
        for (message in mine) transmitEncrypted(message, peerPublicKey)
    }

    private fun sendBytesTo(endpointId: String, dto: PayloadDto) {
        val json = gson.toJson(dto)
        val payload = Payload.fromBytes(json.toByteArray(Charsets.UTF_8))
        connectionsClient.sendPayload(endpointId, payload)
    }

    private data class PayloadDto(
        val kind: String,
        val senderUserId: String,
        val messages: List<ChatMessage>,
        val senderPublicKey: String? = null
    )
}
