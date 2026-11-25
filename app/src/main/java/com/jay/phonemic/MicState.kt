package com.jay.phonemic

/** Tiny shared state: the service writes it, the screen and widget read it. */
object MicState {
    @Volatile var running: Boolean = false
    @Volatile var connected: Boolean = false
    @Volatile var muted: Boolean = true
    @Volatile var status: String = "Stopped"
    @Volatile var level: Float = 0f
    @Volatile var address: String = ""

    /** "live" = green, "muted" = gray, "off" = red (same as the PC app) */
    fun mode(): String = when {
        !running || !connected -> "off"
        muted -> "muted"
        else -> "live"
    }

    const val GREEN = 0xFF2E7D32.toInt()
    const val GRAY = 0xFF8A8A8A.toInt()
    const val RED = 0xFFC62828.toInt()

    fun color(mode: String): Int = when (mode) {
        "live" -> GREEN
        "muted" -> GRAY
        else -> RED
    }
}

object Addr {
    const val PORT = 8443

    /** "192.168.1.15" or "https://192.168.1.15:8443/" -> "192.168.1.15:8443" */
    fun normalize(raw: String?): String? {
        var s = (raw ?: "").trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("wss://")
        s = s.substringBefore("/")
        if (s.isEmpty()) return null
        return if (s.contains(":")) s else "$s:$PORT"
    }

    /** "192.168.1.15:8443" -> "192.168.1.15" (what we show / save) */
    fun short(hostPort: String): String =
        if (hostPort.endsWith(":$PORT")) hostPort.removeSuffix(":$PORT") else hostPort
}
