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
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Records the phone microphone and streams it to the PC app over a
 * secure WebSocket (wss://<pc>:8443/ws). Runs as a foreground service so it
 * keeps working when the screen is off.
 */
class MicService : Service() {

    companion object {
        const val ACTION_STOP = "com.jay.phonemic.STOP"
        const val EXTRA_HOST = "host"
        private const val CHANNEL = "phonemic"
        private const val NOTIF_ID = 1
        private const val RATE = 48000
    }

    @Volatile private var running = false
    @Volatile private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopWork()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val host = intent?.getStringExtra(EXTRA_HOST).orEmpty()
        startForegroundCompat()

        running = true
        MicState.running = true
        MicState.status = "Connecting..."

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonemic:mic").apply { acquire() }

        worker = Thread { runLoop(host) }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone Mic", NotificationManager.IMPORTANCE_LOW)
        )
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
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Phone Mic is on")
            .setContentText("Sending your microphone to the PC")
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopWork() {
        running = false
        MicState.running = false
        MicState.status = "Stopped"
        MicState.level = 0f
        try { record?.stop() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        MicState.running = false
        try { record?.stop() } catch (_: Exception) {}
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Worker thread: record -> send, reconnect when the link drops
    // ------------------------------------------------------------------
    private fun runLoop(hostPort: String) {
        val prefs = getSharedPreferences("phonemic", MODE_PRIVATE)
        val client = buildClient(hostPort, prefs)
        val request = Request.Builder().url("wss://$hostPort/ws").build()

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

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("{\"rate\":$RATE}")
                open.set(true)
                MicState.status = "Live - sending to PC"
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open.set(false)
                wsRef.compareAndSet(webSocket, null)
                if (running) MicState.status = "Disconnected - retrying..."
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open.set(false)
                wsRef.compareAndSet(webSocket, null)
                if (running) {
                    MicState.status = "Can't reach PC (${t.message ?: t.javaClass.simpleName}) - retrying..."
                }
            }
        }

        try {
            rec.startRecording()
            val buf = ByteArray(4096)
            var lastAttempt = 0L

            while (running) {
                val now = System.currentTimeMillis()
                if (wsRef.get() == null && now - lastAttempt > 2000) {
                    lastAttempt = now
                    MicState.status = "Connecting..."
                    wsRef.set(client.newWebSocket(request, listener))
                }

                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (!running) break
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

                if (open.get()) {
                    val ws = wsRef.get()
                    if (ws != null && ws.queueSize() < 300_000L) {
                        ws.send(buf.toByteString(0, n))
                    }
                }
            }
        } catch (e: Exception) {
            if (running) MicState.status = "Error: ${e.message}"
        } finally {
            try { wsRef.get()?.close(1000, null) } catch (_: Exception) {}
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            record = null
            client.dispatcher.executorService.shutdown()
            MicState.running = false
            MicState.level = 0f
            if (running) {
                // ended by itself (not by the Stop button)
                running = false
                stopSelf()
            }
        }
    }

    private fun fail(message: String) {
        MicState.status = message
        MicState.running = false
        running = false
        stopSelf()
    }

    /**
     * The PC app uses a self-signed certificate. We "trust on first use":
     * remember the certificate fingerprint the first time we connect to a PC
     * and refuse if it ever changes (so nobody else on the Wi-Fi can pretend
     * to be your PC). "Reset trust" in the app forgets it.
     */
    private fun buildClient(hostPort: String, prefs: SharedPreferences): OkHttpClient {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val fp = MessageDigest.getInstance("SHA-256")
                    .digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it) }
                val key = "fp_$hostPort"
                val saved = prefs.getString(key, null)
                if (saved == null) {
                    prefs.edit().putString(key, fp).apply()
                } else if (saved != fp) {
                    throw CertificateException("PC certificate changed - tap 'Reset trust'")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
