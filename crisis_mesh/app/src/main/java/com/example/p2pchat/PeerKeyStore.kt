package com.example.p2pchat

import android.content.Context

/**
 * Persists the public keys we've learned from peers we've synced with, keyed by
 * their userId, so we can still encrypt a private message to someone the moment
 * they're back in range without waiting for a fresh handshake every time.
 */
class PeerKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences("p2pchat_peer_keys", Context.MODE_PRIVATE)

    fun save(userId: String, publicKeyBase64: String) {
        prefs.edit().putString(userId, publicKeyBase64).apply()
    }

    fun get(userId: String): String? = prefs.getString(userId, null)

    fun knownPeerIds(): Set<String> = prefs.all.keys
}
