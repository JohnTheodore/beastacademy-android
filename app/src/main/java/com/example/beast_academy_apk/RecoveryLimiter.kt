package com.example.beast_academy_apk

import android.os.SystemClock

class RecoveryLimiter(
    private val windowMs: Long = 60000L,
    private val maxRecoveries: Int = 3,
    private val clock: () -> Long = { SystemClock.uptimeMillis() }
) {
    private val timestamps = mutableListOf<Long>()

    fun recordAndCheck(): Boolean {
        val now = clock()
        timestamps.removeAll { it < now - windowMs }
        if (timestamps.size >= maxRecoveries) return false
        timestamps.add(now)
        return true
    }
    
    fun clear() {
        timestamps.clear()
    }
}
