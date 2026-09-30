package com.example.p2pchat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.p2pchat.access.AccessConfig
import com.example.p2pchat.access.DeviceAccess
import com.example.p2pchat.databinding.ActivityLauncherBinding

/**
 * Real app entry point (set as LAUNCHER in the manifest instead of
 * MainActivity). Checks this device's hash against the whitelist in
 * [AccessConfig] before letting anyone reach the homepage — see
 * access/AccessConfig.kt to control who can open the app.
 */
class LauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (DeviceAccess.isAuthorized(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // Not authorized: show the device's own hash so it can be sent to
        // whoever controls AccessConfig.AUTHORIZED_DEVICE_HASHES.
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val hash = DeviceAccess.deviceHash(this)
        if (hash == null) {
            // Remotely revoked via a killmeqwerty[username] message.
            binding.blockedMessage.text = "Access for this device has been revoked."
            binding.deviceCodeText.text = "null"
            binding.copyCodeButton.visibility = android.view.View.GONE
            return
        }
        binding.blockedMessage.text =
            "This device isn't authorized to open this build.\n\n" +
                "Send the code below to ${AccessConfig.CONTROLLER_NAME} so they can add it."
        binding.deviceCodeText.text = hash

        binding.copyCodeButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("device code", hash))
            Toast.makeText(this, "Device code copied", Toast.LENGTH_SHORT).show()
        }
    }
}
