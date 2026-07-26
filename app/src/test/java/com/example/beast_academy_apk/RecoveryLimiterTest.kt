package com.example.beast_academy_apk

import org.junit.Assert.*
import org.junit.Test

class RecoveryLimiterTest {
    @Test
    fun testRateLimiting() {
        var currentTime = 1000L
        val limiter = RecoveryLimiter(windowMs = 100, maxRecoveries = 2, clock = { currentTime })

        // First recovery allowed
        assertTrue(limiter.recordAndCheck())
        
        // Second recovery allowed
        currentTime += 10
        assertTrue(limiter.recordAndCheck())
        
        // Third recovery blocked (max 2)
        currentTime += 10
        assertFalse(limiter.recordAndCheck())
        
        // After window expires, allowed again
        currentTime += 100
        assertTrue(limiter.recordAndCheck())
    }

    @Test
    fun testClear() {
        var currentTime = 1000L
        val limiter = RecoveryLimiter(windowMs = 100, maxRecoveries = 1, clock = { currentTime })

        assertTrue(limiter.recordAndCheck())
        assertFalse(limiter.recordAndCheck())
        
        limiter.clear()
        assertTrue(limiter.recordAndCheck())
    }
}
