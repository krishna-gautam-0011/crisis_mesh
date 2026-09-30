package com.example.p2pchat

import android.app.Activity
import android.content.Intent
import com.example.p2pchat.access.DeviceAccess

/**
 * If this device has been revoked (immediately or because a delayed-revoke
 * deadline has passed), stops the background mesh service (it must not keep
 * advertising/relaying for a revoked device just because the user happens to be
 * looking at a screen, like MapsActivity, that never otherwise touches it), sends
 * the user to the launcher's "revoked" screen, clears the back stack, and finishes
 * this activity. Returns true if it did so.
 * Call from onResume so a lockout that came due while the app was in the
 * background is still enforced the moment the user comes back.
 */
fun Activity.lockOutIfRevoked(): Boolean {
    if (!DeviceAccess.isRevoked(this)) return false
    startService(Intent(this, ChatForegroundService::class.java).setAction(ChatForegroundService.ACTION_STOP))
    startActivity(
        Intent(this, LauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    )
    finish()
    return true
}

