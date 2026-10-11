package com.ze.iptvplayer

import com.ze.iptvplayer.util.ThemeManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ze.iptvplayer.data.Prefs
import com.ze.iptvplayer.databinding.ActivityStartBinding

/**
 * The app's launcher activity. Returning users with a saved playlist are forwarded straight to
 * MainActivity; the welcome screen only shows for a fresh install (or after the last playlist
 * was deleted).
 */
class StartActivity : AppCompatActivity() {

    private var forwarded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        if (Prefs.activePlaylist(this) != null) {
            goToMain()
            return
        }

        val binding = ActivityStartBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.startAddPlaylistButton.setOnClickListener {
            startActivity(Intent(this, AddSourceActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have added a playlist from the welcome screen and come back. Guarded by
        // `forwarded` because onCreate() may already have forwarded us (onResume still runs).
        if (!forwarded && Prefs.activePlaylist(this) != null) {
            goToMain()
        }
    }

    private fun goToMain() {
        forwarded = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
