package com.jay.phonemic

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var addrField: EditText
    private lateinit var mainBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var meter: ProgressBar
    private var shown = 0f

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("phonemic", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(56), dp(24), dp(24))
        }

        val title = TextView(this).apply {
            text = "Phone Mic"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(title, lp(0))

        val help = TextView(this).apply {
            text = "Type the PC address shown in the Phone Mic app on your PC " +
                "(same Wi-Fi), for example 192.168.1.15"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 14f
        }
        root.addView(help, lp(24))

        addrField = EditText(this).apply {
            setText(prefs.getString("addr", ""))
            hint = "192.168.1.15"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#666666"))
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(addrField, lp(8))

        mainBtn = Button(this).apply {
            text = "START"
            textSize = 24f
            setTextColor(Color.WHITE)
            setOnClickListener { onToggle() }
        }
        root.addView(
            mainBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80))
                .apply { topMargin = dp(24) }
        )

        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        root.addView(meter, lp(24))

        statusTv = TextView(this).apply {
            text = MicState.status
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 16f
            gravity = Gravity.CENTER
        }
        root.addView(statusTv, lp(12))

        val resetBtn = Button(this).apply {
            text = "Reset trust (use if the PC changed)"
            isAllCaps = false
            setOnClickListener { resetTrust() }
        }
        root.addView(resetBtn, lp(32))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private val tick = object : Runnable {
        override fun run() {
            val on = MicState.running
            mainBtn.text = if (on) "STOP" else "START"
            mainBtn.setBackgroundColor(Color.parseColor(if (on) "#C62828" else "#2E7D32"))
            statusTv.text = MicState.status

            val l = MicState.level
            MicState.level = 0f
            shown = maxOf(l, shown * 0.85f)
            meter.progress = minOf(100, (shown * 150f).toInt())

            ui.postDelayed(this, 80)
        }
    }

    private fun onToggle() {
        if (MicState.running) {
            startService(Intent(this, MicService::class.java).setAction(MicService.ACTION_STOP))
            return
        }
        val hostPort = normalize(addrField.text.toString())
        if (hostPort == null) {
            Toast.makeText(this, "Type the PC address first", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("phonemic", Context.MODE_PRIVATE)
            .edit().putString("addr", addrField.text.toString().trim()).apply()

        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 1)
        } else {
            startMic(hostPort)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            normalize(addrField.text.toString())?.let { startMic(it) }
        } else {
            Toast.makeText(this, "Microphone permission is needed", Toast.LENGTH_LONG).show()
        }
    }

    private fun startMic(hostPort: String) {
        startForegroundService(
            Intent(this, MicService::class.java).putExtra(MicService.EXTRA_HOST, hostPort)
        )
    }

    /** "192.168.1.15" or "https://192.168.1.15:8443/" -> "192.168.1.15:8443" */
    private fun normalize(raw: String): String? {
        var s = raw.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("wss://")
        s = s.substringBefore("/")
        if (s.isEmpty()) return null
        return if (s.contains(":")) s else "$s:8443"
    }

    private fun resetTrust() {
        val prefs = getSharedPreferences("phonemic", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("fp_") }.forEach { edit.remove(it) }
        edit.apply()
        Toast.makeText(this, "Trust reset", Toast.LENGTH_SHORT).show()
    }
}
