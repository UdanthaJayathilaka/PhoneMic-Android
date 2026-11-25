package com.jay.phonemic

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var addrField: EditText
    private lateinit var findBtn: Button
    private lateinit var mainBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var stateTv: TextView
    private lateinit var circle: ImageView
    private lateinit var circleBg: GradientDrawable
    private lateinit var meter: ProgressBar
    private var shown = 0f
    private var searching = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private val prefs get() = getSharedPreferences("phonemic", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Phone Mic"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, lp(0))

        // big coloured mic circle: green = live, gray = muted, red = not connected
        circleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(MicState.RED)
        }
        circle = ImageView(this).apply {
            background = circleBg
            setImageResource(R.drawable.ic_stat_mic_none)
            setPadding(dp(26), dp(26), dp(26), dp(26))
            setOnClickListener { onToggle() }
        }
        root.addView(circle, LinearLayout.LayoutParams(dp(120), dp(120)).apply {
            topMargin = dp(20)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        stateTv = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(stateTv, lp(12))

        mainBtn = Button(this).apply {
            text = "START"
            textSize = 22f
            setTextColor(Color.WHITE)
            setOnClickListener { onToggle() }
        }
        root.addView(
            mainBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72))
                .apply { topMargin = dp(16) }
        )

        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(meter, lp(16))

        statusTv = TextView(this).apply {
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 15f
            gravity = Gravity.CENTER
        }
        root.addView(statusTv, lp(8))

        root.addView(TextView(this).apply {
            text = "PC address (found automatically - leave empty)"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 13f
        }, lp(28))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addrField = EditText(this).apply {
            setText(prefs.getString("addr", ""))
            hint = "Auto"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#666666"))
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        row.addView(addrField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        findBtn = Button(this).apply {
            text = "Find PC"
            isAllCaps = false
            setOnClickListener { findPc() }
        }
        row.addView(findBtn)
        root.addView(row, lp(4))

        root.addView(TextView(this).apply {
            text = "Tip: long-press the home screen → Widgets → Phone Mic to add a one-tap button."
            setTextColor(Color.parseColor("#888888"))
            textSize = 13f
        }, lp(20))

        root.addView(Button(this).apply {
            text = "Reset trust (only if you changed PC)"
            isAllCaps = false
            setOnClickListener { resetTrust() }
        }, lp(16))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (addrField.text.isNullOrBlank()) addrField.setText(prefs.getString("addr", ""))
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private val tick = object : Runnable {
        override fun run() {
            val on = MicState.running
            val mode = MicState.mode()
            val color = MicState.color(mode)

            mainBtn.text = if (on) "STOP" else "START"
            mainBtn.setBackgroundColor(if (on) Color.parseColor("#424242") else MicState.GREEN)

            circleBg.setColor(if (on) color else MicState.RED)
            circle.setImageResource(
                when (mode) {
                    "live" -> R.drawable.ic_stat_mic
                    "muted" -> R.drawable.ic_stat_mic_off
                    else -> R.drawable.ic_stat_mic_none
                }
            )
            stateTv.text = when {
                !on -> "OFF"
                mode == "live" -> "LIVE"
                mode == "muted" -> "MUTED (from PC)"
                else -> "NOT CONNECTED"
            }
            stateTv.setTextColor(if (on) color else MicState.RED)
            statusTv.text = if (searching) "Searching for PC..." else MicState.status

            // show the address the service found
            val saved = prefs.getString("addr", "") ?: ""
            if (!addrField.hasFocus() && saved.isNotEmpty() && addrField.text.toString() != saved && on) {
                addrField.setText(saved)
            }

            val l = MicState.level
            MicState.level = 0f
            shown = maxOf(l, shown * 0.85f)
            meter.progress = minOf(100, (shown * 150f).toInt())

            ui.postDelayed(this, 80)
        }
    }

    private fun findPc() {
        if (searching) return
        searching = true
        findBtn.isEnabled = false
        Thread {
            val found = Discovery.find()
            ui.post {
                searching = false
                findBtn.isEnabled = true
                if (found != null) {
                    val short = Addr.short(found)
                    addrField.setText(short)
                    prefs.edit().putString("addr", short).apply()
                    Toast.makeText(this, "Found PC: $short", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        "PC not found. Open Phone Mic on the PC (same Wi-Fi).",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun onToggle() {
        if (MicState.running) {
            startService(Intent(this, MicService::class.java).setAction(MicService.ACTION_STOP))
            return
        }
        prefs.edit().putString("addr", addrField.text.toString().trim()).apply()

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
            startMic()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startMic()
        } else {
            Toast.makeText(this, "Microphone permission is needed", Toast.LENGTH_LONG).show()
        }
    }

    /** Empty address = find the PC automatically. */
    private fun startMic() {
        val hostPort = Addr.normalize(addrField.text.toString()) ?: ""
        startForegroundService(
            Intent(this, MicService::class.java).putExtra(MicService.EXTRA_HOST, hostPort)
        )
    }

    private fun resetTrust() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("fp") }.forEach { edit.remove(it) }
        edit.apply()
        Toast.makeText(this, "Trust reset", Toast.LENGTH_SHORT).show()
    }
}
