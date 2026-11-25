package com.jay.phonemic

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Invisible screen opened by the widget. Android only lets an app switch the
 * microphone on while it is on screen, so the widget opens this for a moment,
 * starts (or stops) the mic, and closes again.
 */
class ToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (MicState.running) {
            startService(Intent(this, MicService::class.java).setAction(MicService.ACTION_STOP))
            finish()
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // first time: open the app so it can ask for the microphone permission
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val saved = getSharedPreferences("phonemic", Context.MODE_PRIVATE).getString("addr", "")
        startForegroundService(
            Intent(this, MicService::class.java)
                .putExtra(MicService.EXTRA_HOST, Addr.normalize(saved) ?: "")
        )
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 800)
    }
}
