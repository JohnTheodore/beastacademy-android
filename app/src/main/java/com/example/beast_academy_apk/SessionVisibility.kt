package com.example.beast_academy_apk

/** Independent reasons to suspend a session. A lifecycle resume cannot reopen a cover. */
internal class SessionVisibility(private var screenInteractive: Boolean = true) {
    private var resumed = false
    private var covered = false
    private var destroyed = false
    val canRun: Boolean get() = resumed && screenInteractive && !covered && !destroyed

    fun resume() { if (!destroyed) resumed = true }
    fun pause() { resumed = false }
    fun screenOff() { screenInteractive = false }
    fun screenOn() {
        if (destroyed) return
        screenInteractive = true
        covered = false
    }
    fun closeCover(): Boolean {
        if (!canRun) return false
        covered = true
        return true
    }
    fun destroy() { destroyed = true; resumed = false }
    override fun toString() = "resumed=$resumed screen=$screenInteractive covered=$covered destroyed=$destroyed"
}
