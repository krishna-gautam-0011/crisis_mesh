package com.example.p2pchat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.locks.ReentrantLock

/**
 * Stores every message this device knows about in a single JSON file
 * (messages.json, in the app's private files directory).
 *
 * When this device connects to a peer, the peer's full message list is
 * merged into this store as a UNION keyed by message id: every message either
 * device has ever seen ends up on both devices. This also means private
 * messages can "hop" from device to device until they eventually reach the
 * intended recipient, even without a direct connection.
 *
 * Every message (public or private) is only kept for [MessageType.TTL_MS] (24h)
 * from when it was created. Expired messages are pruned from disk on every read,
 * so once a device has been out of range for 24h+, anything it missed is simply
 * gone everywhere - it "vanishes" as designed rather than piling up forever.
 */
class MessageStore(context: Context) {

    private val gson = Gson()
    private val file = File(context.filesDir, "messages.json")
    private val lock = ReentrantLock()

    private val listType = object : TypeToken<MutableList<ChatMessage>>() {}.type

    /** Loads the store, dropping (and persisting the drop of) anything past its 24h TTL. */
    fun loadAll(): MutableList<ChatMessage> {
        lock.lock()
        try {
            val raw = readRaw()
            val now = System.currentTimeMillis()
            val fresh = raw.filter { now - it.timestamp < MessageType.TTL_MS }.toMutableList()
            if (fresh.size != raw.size) {
                writeRaw(fresh)
            }
            return fresh
        } finally {
            lock.unlock()
        }
    }

    private fun readRaw(): MutableList<ChatMessage> {
        if (!file.exists()) return mutableListOf()
        val text = file.readText()
        if (text.isBlank()) return mutableListOf()
        return try {
            gson.fromJson(text, listType) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun writeRaw(messages: List<ChatMessage>) {
        file.writeText(gson.toJson(messages))
    }

    /**
     * Forces an expiry sweep right now (used by the periodic cleanup timer so
     * messages visibly disappear from the UI the moment they turn 24h old,
     * even if nothing else triggers a store read/write in that instant).
     * Returns true if anything was actually removed.
     */
    fun pruneExpired(): Boolean {
        lock.lock()
        try {
            val raw = readRaw()
            val now = System.currentTimeMillis()
            val fresh = raw.filter { now - it.timestamp < MessageType.TTL_MS }
            if (fresh.size != raw.size) {
                writeRaw(fresh)
                return true
            }
            return false
        } finally {
            lock.unlock()
        }
    }

    /** Add one locally-composed message and persist it. */
    fun addMessage(message: ChatMessage) {
        lock.lock()
        try {
            val current = readRaw()
            if (current.none { it.id == message.id }) {
                current.add(message)
                writeRaw(current)
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Union-merge an incoming list of messages (from a peer) into our store.
     * Already-expired incoming messages are dropped rather than resurrected.
     * Returns true if anything new was added.
     */
    fun mergeIncoming(incoming: List<ChatMessage>): Boolean {
        lock.lock()
        try {
            val current = readRaw()
            val existingIds = current.map { it.id }.toHashSet()
            val now = System.currentTimeMillis()
            var changed = false
            for (m in incoming) {
                if (now - m.timestamp >= MessageType.TTL_MS) continue
                if (existingIds.add(m.id)) {
                    current.add(m)
                    changed = true
                }
            }
            if (changed) {
                current.sortBy { it.timestamp }
                writeRaw(current)
            }
            return changed
        } finally {
            lock.unlock()
        }
    }

    fun getPublicMessages(): List<ChatMessage> =
        loadAll().filter { it.type == MessageType.PUBLIC }.sortedBy { it.timestamp }

    /**
     * The full two-way private conversation between [myUserId] and [peerId]:
     * everything I sent them plus everything they sent me, oldest first.
     *
     * My own outgoing messages are only ever kept locally as their plain-text (unencrypted)
     * copy - that's my own permanent record of what I typed (see NearbyManager.sendPrivate).
     * Anything from [peerId] to me can only ever legitimately exist encrypted (their content
     * gossips through the mesh as ciphertext only I can open - see CryptoManager). Matching on
     * both direction AND [ChatMessage.encrypted] like this means we only ever show exactly one,
     * correct representation of each message, and never try to "decrypt" our own outgoing
     * ciphertext (which is wrapped for the recipient's key, not ours, and would just show as a
     * bogus "couldn't decrypt" bubble if it ever looped back to us through the mesh).
     */
    fun getPrivateThread(myUserId: String, peerId: String): List<ChatMessage> =
        loadAll().filter {
            it.type == MessageType.PRIVATE &&
                ((it.senderId == myUserId && it.targetId == peerId && !it.encrypted) ||
                    (it.senderId == peerId && it.targetId == myUserId && it.encrypted))
        }.sortedBy { it.timestamp }

    /**
     * Every user id that shows up as either side of a still-live (non-expired)
     * private message involving [myUserId] - lets the UI keep showing a peer's
     * conversation for up to 24h even after they've stepped out of range.
     */
    fun getKnownPrivatePeers(myUserId: String): Set<String> {
        val all = loadAll().filter { it.type == MessageType.PRIVATE }
        val peers = mutableSetOf<String>()
        for (m in all) {
            if (m.senderId == myUserId && m.targetId != null) peers.add(m.targetId)
            if (m.targetId == myUserId) peers.add(m.senderId)
        }
        return peers
    }

    /**
     * Everything we're willing to hand a peer during a SYNC handshake - i.e. everything
     * *except* our own plain-text local copies of PRIVATE messages we authored for someone
     * else. Those are kept in [loadAll] purely so the sender can see their own outbox in the
     * UI (see ChatActivity/getPrivateThread); they must NEVER be included here, because SYNC
     * fires automatically for literally any peer we connect to (see NearbyManager,
     * onConnectionResult -> sendSync), not just the message's intended recipient. Sending them
     * out as-is would hand every private message's plain text to every device we ever pair
     * with, completely defeating the point of encrypting private messages in the first place.
     * PUBLIC messages (always plain text by design) and PRIVATE messages we received or are
     * relaying (always still [ChatMessage.encrypted] ciphertext - see CryptoManager) are both
     * safe to include: a relay can store-and-forward the ciphertext without ever reading it.
     */
    fun loadAllForSync(): List<ChatMessage> =
        loadAll().filter { it.type == MessageType.PUBLIC || it.encrypted }
}
