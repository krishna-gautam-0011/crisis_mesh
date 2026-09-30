package com.example.p2pchat.access

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * Reads this device's ANDROID_ID, hashes it, and checks the hash against the
 * whitelist in [AccessConfig]. Logic lives here so [AccessConfig] can stay a
 * plain data file that's safe to hand-edit without touching any behavior.
 *
 * A device can also be remotely revoked (see [KillCommand]):
 *  - immediately via [revoke] (targeted `killmeqwerty[username]`), or
 *  - after a delay via [scheduleRevoke] (bare `killmeqwerty`, 20 minutes).
 * Once revoked, [deviceHash] returns null and [isAuthorized] is always false -
 * even if the debug bypass in [AccessConfig] is on. Both the flag and any
 * pending deadline are persisted, so they survive app restarts and the app
 * being closed while the countdown runs.
 */
object DeviceAccess {

    private const val PREFS = "p2pchat_access"
    private const val KEY_REVOKED = "revoked"
    private const val KEY_REVOKE_AT = "revoke_at"

    /** Delay between receiving a bare `killmeqwerty` and the device being revoked. */
    const val DELAYED_REVOKE_MS = 20L * 60L * 1000L

    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""

    private fun sha256(input: String): String {
        val digestBytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digestBytes.joinToString("") { "%02x".format(it) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * True once this device is revoked. If a scheduled deadline has passed, this
     * converts it into a permanent revoke on the spot, so winding the clock back
     * afterwards can't undo it.
     */
    fun isRevoked(context: Context): Boolean {
        val p = prefs(context)
        if (p.getBoolean(KEY_REVOKED, false)) return true
        val at = p.getLong(KEY_REVOKE_AT, 0L)
        if (at != 0L && System.currentTimeMillis() >= at) {
            revoke(context)
            return true
        }
        return false
    }

    /** Epoch millis at which a pending delayed revoke fires, or null if none is pending. */
    fun pendingRevokeAt(context: Context): Long? {
        if (isRevoked(context)) return null
        return prefs(context).getLong(KEY_REVOKE_AT, 0L).takeIf { it != 0L }
    }

    /**
     * Starts the delayed-revoke countdown ([delayMs] from now). If one is already
     * running it is left alone (repeat messages never extend it). Returns the
     * deadline in epoch millis.
     */
    fun scheduleRevoke(context: Context, delayMs: Long = DELAYED_REVOKE_MS): Long {
        pendingRevokeAt(context)?.let { return it }
        val at = System.currentTimeMillis() + delayMs
        prefs(context).edit().putLong(KEY_REVOKE_AT, at).commit()
        return at
    }

    /** Sets this device's hash to null, permanently (until app data is cleared). */
    fun revoke(context: Context) {
        // commit() (not apply()) so the flag is on disk before anything else happens.
        prefs(context).edit().putBoolean(KEY_REVOKED, true).remove(KEY_REVOKE_AT).commit()
    }

    /** The SHA-256 hex hash that identifies this device to [AccessConfig], or null if revoked. */
    fun deviceHash(context: Context): String? =
        if (isRevoked(context)) null else sha256(androidId(context))

    /** True if this device's hash is in the whitelist (or the debug bypass is on). */
    fun isAuthorized(context: Context): Boolean {
        val hash = deviceHash(context) ?: return false // revoked always wins
        if (AccessConfig.ALLOW_ALL_DEVICES_DEBUG_ONLY) return true
        return hash.isNotBlank() && hash in AccessConfig.AUTHORIZED_DEVICE_HASHES
    }
}
