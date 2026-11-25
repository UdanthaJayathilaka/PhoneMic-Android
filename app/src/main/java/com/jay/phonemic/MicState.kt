package com.jay.phonemic

/** Tiny shared state: the service writes it, the screen reads it. */
object MicState {
    @Volatile var running: Boolean = false
    @Volatile var status: String = "Stopped"
    @Volatile var level: Float = 0f
}
