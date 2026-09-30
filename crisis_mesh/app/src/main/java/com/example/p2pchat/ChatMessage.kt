package com.example.p2pchat

import java.util.UUID

/**
 * A single chat message.
 *
 * type = "PUBLIC"  -> broadcast to everyone, targetId is null, content is always plain text
 * type = "PRIVATE" -> meant only for the user whose id == targetId
 *
 * Every message has a globally unique [id] so that when two devices meet and
 * exchange their message lists, the merge can be a simple union keyed on [id]
 * (no duplicates, no data loss).
 *
 * Messages are only ever kept for [MessageType.TTL_MS] (24h) from [timestamp];
 * anything older is pruned locally on every device - see MessageStore.
 *
 * [encrypted] distinguishes the two representations a PRIVATE message can have:
 *  - false: this is the sender's own local copy, [content] is the plain text they typed.
 *  - true:  this came in off the wire (directly or relayed through a third device),
 *           [content] is a hybrid RSA/AES ciphertext blob only the true recipient's
 *           private key can open (see CryptoManager). Relay devices happily store and
 *           forward this blob without ever being able to read it.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val targetId: String? = null,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String, // MessageType.PUBLIC or MessageType.PRIVATE
    val encrypted: Boolean = false
)

object MessageType {
    const val PUBLIC = "PUBLIC"
    const val PRIVATE = "PRIVATE"

    /** How long any message (public or private) is retained before it vanishes. */
    const val TTL_MS = 24L * 60L * 60L * 1000L
}

object PayloadKind {
    const val SYNC = "SYNC"               // full message list, sent right after connecting
    const val NEW_MESSAGE = "NEW_MESSAGE" // a single freshly-composed message
}
