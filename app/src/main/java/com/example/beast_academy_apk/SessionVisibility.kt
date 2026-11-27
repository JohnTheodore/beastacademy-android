package com.example.beast_academy_apk

/** Lifecycle and screen reasons to suspend a session. The case cover is owned by [CoverGuard]. */
internal class SessionVisibility(private var screenInteractive: Boolean = true) {
    private var resumed = false
    private var destroyed = false
    val canRun: Boolean get() = resumed && screenInteractive && !destroyed

    fun resume() { if (!destroyed) resumed = true }
    fun pause() { resumed = false }
    fun screenOff() { screenInteractive = false }
    fun screenOn() { if (!destroyed) screenInteractive = true }
    fun destroy() { destroyed = true; resumed = false }
    override fun toString() = "resumed=$resumed screen=$screenInteractive destroyed=$destroyed"
}
