package com.jay.phonemic

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Finds the PC running Phone Mic on the same Wi-Fi, so you never have to type
 * its address. Blocking: call it from a background thread.
 *
 * 1. Shout "PHONEMIC_DISCOVER" on UDP 8444; the PC app answers "PHONEMIC_HERE 8443".
 * 2. If nothing answers (firewall), try every address 1..254 on port 8443
 *    and ask https://ip:8443/hello which must answer "PHONEMIC".
 */
object Discovery {
    private const val UDP_PORT = 8444

    fun find(): String? = udp(2500) ?: scan()

    private fun udp(timeoutMs: Long): String? {
        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 300
                val msg = "PHONEMIC_DISCOVER".toByteArray()
                val targets = broadcastTargets()
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(256)
                var nextSend = 0L
                while (System.currentTimeMillis() < end) {
                    val now = System.currentTimeMillis()
                    if (now >= nextSend) {
                        for (t in targets) {
                            try {
                                sock.send(DatagramPacket(msg, msg.size, t, UDP_PORT))
                            } catch (_: Exception) {
                            }
                        }
                        nextSend = now + 700
                    }
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val text = String(p.data, 0, p.length)
                        if (text.startsWith("PHONEMIC_HERE")) {
                            val port = text.substringAfter(" ", "").trim().toIntOrNull() ?: Addr.PORT
                            val ip = p.address.hostAddress
                            if (ip != null) return "$ip:$port"
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** Wi-Fi addresses of this phone (falls back to any private address). */
    private fun wifiAddresses(): List<InterfaceAddress> {
        val all = mutableListOf<Pair<String, InterfaceAddress>>()
        try {
            val list = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (ni in Collections.list(list)) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && a.isSiteLocalAddress) all.add(ni.name to ia)
                }
            }
        } catch (_: Exception) {
        }
        val wifi = all.filter { it.first.startsWith("wlan") || it.first.startsWith("swlan") }
        return (if (wifi.isNotEmpty()) wifi else all).map { it.second }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        for (ia in wifiAddresses()) ia.broadcast?.let { out.add(it) }
        try {
            out.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        return out.distinct()
    }

    private fun scan(): String? {
        val mine = wifiAddresses().firstOrNull()?.address as? Inet4Address ?: return null
        val b = mine.address
        val prefix = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}."
        val self = b[3].toInt() and 255
        val pool = Executors.newFixedThreadPool(48)
        try {
            val cs = ExecutorCompletionService<String?>(pool)
            var jobs = 0
            for (i in 1..254) {
                if (i == self) continue
                val ip = prefix + i
                cs.submit(Callable<String?> {
                    try {
                        Socket().use { s -> s.connect(InetSocketAddress(ip, Addr.PORT), 400) }
                        if (isPhoneMic(ip)) "$ip:${Addr.PORT}" else null
                    } catch (_: Exception) {
                        null
                    }
                })
                jobs++
            }
            repeat(jobs) {
                val f = cs.poll(6, TimeUnit.SECONDS) ?: return null
                val r = try { f.get() } catch (_: Exception) { null }
                if (r != null) return r
            }
        } finally {
            pool.shutdownNow()
        }
        return null
    }

    // Only used to ask "/hello" (no audio, no secrets), so any certificate is fine here.
    private val checkClient: OkHttpClient by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(800, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun isPhoneMic(ip: String): Boolean = try {
        checkClient.newCall(Request.Builder().url("https://$ip:${Addr.PORT}/hello").build())
            .execute().use { it.isSuccessful && it.body?.string()?.trim() == "PHONEMIC" }
    } catch (_: Exception) {
        false
    }
}
