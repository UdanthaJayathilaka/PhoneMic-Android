package com.jay.phonemic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Records the phone microphone and streams it to the PC app over a
 * secure WebSocket (wss://<pc>:8443/ws). Runs as a foreground service so it
 * keeps working when the screen is off.
 *
 * The microphone is only switched on while the PC is connected AND not muted.
 */
class MicService : Service() {

    companion object {
        const val ACTION_STOP = "com.jay.phonemic.STOP"
        const val EXTRA_HOST = "host"
        private const val CHANNEL = "phonemic_status"
        private const val OLD_CHANNEL = "phonemic"
        private const val NOTIF_ID = 1
        private const val RATE = 48000
    }

    @Volatile private var running = false
    @Volatile private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var shownMode = ""
    private var shownStatus = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopWork()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val host = intent?.getStringExtra(EXTRA_HOST).orEmpty()
        running = true
        MicState.running = true
        MicState.connected = false
        MicState.muted = true
        MicState.status = if (host.isEmpty()) "Searching for PC..." else "Connecting..."

        startForegroundCompat()

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonemic:mic").apply { acquire() }

        worker = Thread { runLoop(host) }.also { it.start() }
        MicWidget.updateAll(this)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        try { nm.deleteNotificationChannel(OLD_CHANNEL) } catch (_: Exception) {}
        // DEFAULT importance (but silent) so it also shows on the lock screen / Always On Display
        val ch = NotificationChannel(CHANNEL, "Phone Mic status", NotificationManager.IMPORTANCE_DEFAULT)
        ch.setSound(null, null)
        ch.enableVibration(false)
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)

        val n = buildNotification()
        shownMode = MicState.mode()
        shownStatus = MicState.status
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /** Green = live, gray = muted from the PC, red = not connected. */
    private fun buildNotification(): Notification {
        val mode = MicState.mode()
        val icon: Int
        val title: String
        when (mode) {
            "live" -> { icon = R.drawable.ic_stat_mic; title = "LIVE - the PC can hear you" }
            "muted" -> { icon = R.drawable.ic_stat_mic_off; title = "Muted (press the shortcut on the PC)" }
            else -> { icon = R.drawable.ic_stat_mic_none; title = "Not connected" }
        }
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, MicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(icon)
            .setColor(MicState.color(mode))
            .setColorized(true)
            .setContentTitle(title)
            .setContentText(MicState.status)
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    /** Update the notification + widgets when the state changes. */
    private fun refreshUi() {
        val mode = MicState.mode()
        val status = MicState.status
        if (mode == shownMode && status == shownStatus) return
        shownMode = mode
        shownStatus = status
        if (running) {
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
            } catch (_: Exception) {
            }
        }
        MicWidget.updateAll(this)
    }

    private fun stopWork() {
        running = false
        MicState.running = false
        MicState.connected = false
        MicState.status = "Stopped"
        MicState.level = 0f
        try { record?.stop() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        MicWidget.updateAll(this)
    }

    override fun onDestroy() {
        running = false
        MicState.running = false
        MicState.connected = false
        try { record?.stop() } catch (_: Exception) {}
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        MicWidget.updateAll(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Worker thread: find PC -> connect -> record while live; reconnect
    // ------------------------------------------------------------------
    private fun runLoop(initialHost: String) {
        val prefs = getSharedPreferences("phonemic", MODE_PRIVATE)
        var hostPort = initialHost
        val client = buildClient(prefs)

        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec: AudioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 8192) * 2
            )
        } catch (e: Exception) {
            fail("Microphone error: ${e.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            fail("Could not open the microphone")
            return
        }
        record = rec

        val wsRef = AtomicReference<WebSocket?>(null)
        val open = AtomicBoolean(false)
        val failures = AtomicInteger(0)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                MicState.muted = true // stay quiet until the PC says "unmute"
                webSocket.send("{\"rate\":$RATE}")
                open.set(true)
                failures.set(0)
                MicState.connected = true
                MicState.status = "Connected to ${Addr.short(MicState.address)}"
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // the PC app tells us to mute / unmute (its shortcut key)
                try {
                    when (JSONObject(text).optString("cmd")) {
                        "mute" -> {
                            MicState.muted = true
                            MicState.status = "Connected to ${Addr.short(MicState.address)}"
                        }
                        "unmute" -> {
                            MicState.muted = false
                            MicState.status = "Sending your voice to ${Addr.short(MicState.address)}"
                        }
                    }
                } catch (_: Exception) {
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open.set(false)
                wsRef.compareAndSet(webSocket, null)
                MicState.connected = false
                failures.incrementAndGet()
                if (running) MicState.status = "Disconnected - retrying..."
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open.set(false)
                wsRef.compareAndSet(webSocket, null)
                MicState.connected = false
                failures.incrementAndGet()
                if (running) {
                    val why = t.message ?: t.javaClass.simpleName
                    MicState.status = if (why.contains("Reset trust")) why
                    else "Can't reach the PC - retrying..."
                }
            }
        }

        var recording = false
        try {
            val buf = ByteArray(4096)
            var lastAttempt = 0L
            var lastSearch = 0L

            while (running) {
                refreshUi()
                val now = System.currentTimeMillis()

                // find the PC by itself: when we don't know it, or it keeps failing
                if ((hostPort.isEmpty() || failures.get() >= 3) &&
                    wsRef.get() == null && now - lastSearch > 8000
                ) {
                    lastSearch = now
                    MicState.status = "Searching for PC..."
                    refreshUi()
                    val found = Discovery.find()
                    if (!running) break
                    if (found != null) {
                        hostPort = found
                        prefs.edit().putString("addr", Addr.short(found)).apply()
                        failures.set(0)
                        lastAttempt = 0L
                    } else {
                        MicState.status = "PC not found - open Phone Mic on the PC (same Wi-Fi)"
                    }
                    lastSearch = System.currentTimeMillis()
                }

                if (hostPort.isNotEmpty() && wsRef.get() == null &&
                    System.currentTimeMillis() - lastAttempt > 2000
                ) {
                    lastAttempt = System.currentTimeMillis()
                    MicState.address = hostPort
                    if (failures.get() == 0) MicState.status = "Connecting to ${Addr.short(hostPort)}..."
                    val req = Request.Builder().url("wss://$hostPort/ws").build()
                    wsRef.set(client.newWebSocket(req, listener))
                }

                // microphone ON only while connected and not muted
                val live = open.get() && !MicState.muted
                if (!live) {
                    if (recording) {
                        try { rec.stop() } catch (_: Exception) {}
                        recording = false
                        MicState.level = 0f
                    }
                    Thread.sleep(100)
                    continue
                }
                if (!recording) {
                    rec.startRecording()
                    recording = true
                }

                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    Thread.sleep(20)
                    continue
                }

                // loudness for the meter (16-bit little-endian samples)
                var peak = 0
                var i = 0
                while (i + 1 < n) {
                    val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                    val a = if (s < 0) -s else s
                    if (a > peak) peak = a
                    i += 2
                }
                val lvl = peak / 32768f
                if (lvl > MicState.level) MicState.level = lvl

                val ws = wsRef.get()
                if (ws != null && ws.queueSize() < 300_000L) {
                    ws.send(buf.toByteString(0, n))
                }
            }
        } catch (e: Exception) {
            if (running) MicState.status = "Error: ${e.message}"
        } finally {
            try { wsRef.get()?.close(1000, null) } catch (_: Exception) {}
            if (recording) try { rec.stop() } catch (_: Exception) {}
            rec.release()
            record = null
            client.dispatcher.executorService.shutdown()
            MicState.connected = false
            MicState.level = 0f
            if (running) {
                // ended by itself (not by the Stop button)
                running = false
                MicState.running = false
                stopSelf()
            }
            MicWidget.updateAll(this)
        }
    }

    private fun fail(message: String) {
        MicState.status = message
        MicState.running = false
        running = false
        stopSelf()
        MicWidget.updateAll(this)
    }

    /**
     * The PC app uses a self-signed certificate. We "trust on first use":
     * remember the PC's certificate the first time and refuse if it ever
     * changes (so nobody else on the Wi-Fi can pretend to be your PC).
     * "Reset trust" in the app forgets it.
     */
    private fun buildClient(prefs: SharedPreferences): OkHttpClient {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val fp = MessageDigest.getInstance("SHA-256")
                    .digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it) }
                val saved = prefs.getString("fp_pc", null)
                if (saved == null) {
                    prefs.edit().putString("fp_pc", fp).apply()
                } else if (saved != fp) {
                    throw CertificateException("This is not your PC (certificate changed) - tap 'Reset trust' if you changed PC")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(4, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
    }
}
