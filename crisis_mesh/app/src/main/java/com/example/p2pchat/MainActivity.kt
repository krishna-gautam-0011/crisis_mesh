package com.example.p2pchat

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.p2pchat.access.BrandingConfig
import com.example.p2pchat.databinding.ActivityMainBinding
import kotlin.random.Random

/**
 * The homepage. By design this shows nothing but the branding image and the
 * two entry buttons (see activity_main.xml) - the user id is still generated
 * and persisted here, but it's only displayed later, inside ChatActivity's
 * header. LauncherActivity runs before this and gates access by device hash.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        const val PREFS = "p2pchat_prefs"
        const val KEY_USER_ID = "user_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        getOrCreateUserId(prefs)

        binding.homepageImage.setImageResource(BrandingConfig.HOMEPAGE_IMAGE)

        binding.enterMeshButton.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        binding.enterMapsButton.setOnClickListener {
            startActivity(Intent(this, MapsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        lockOutIfRevoked()
    }

    private fun getOrCreateUserId(prefs: SharedPreferences): String {
        val existing = prefs.getString(KEY_USER_ID, null)
        if (existing != null) return existing
        val generated = "User" + Random.nextInt(1000, 9999)
        prefs.edit().putString(KEY_USER_ID, generated).apply()
        return generated
    }
}
