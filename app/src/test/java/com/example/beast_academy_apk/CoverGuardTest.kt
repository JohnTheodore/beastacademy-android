package com.example.beast_academy_apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverGuardTest {
    private val changes = mutableListOf<Pair<Boolean, String>>()
    private val guard = CoverGuard(null) { covered, reason -> changes += covered to reason }

    private fun warm() = listOf(0L, 500L, 1_000L, 1_500L).forEach {
        guard.onLight(100f, it)
        guard.onField(50f, it)
    }

    private fun close() {
        warm()
        guard.onLight(0.1f, 2_000L)
        guard.onField(95f, 2_000L)
        guard.onField(95f, 3_000L)
        assertTrue(guard.covered)
    }

    @Test
    fun `sustained light drop and field shift close after one second`() {
        warm()
        guard.onLight(0.2f, 2_000L) // on-change light sensor then stays silent
        guard.onField(90f, 2_050L)
        guard.onField(91f, 3_000L)
        assertFalse(guard.covered)
        guard.onField(92f, 3_060L)
        assertEquals(listOf(true to "sensors"), changes)
    }

    @Test
    fun `darkness alone or field shift alone does not close`() {
        warm()
        guard.onLight(0.1f, 2_000L)
        guard.onField(51f, 3_500L)
        guard.onLight(100f, 3_600L)
        guard.onField(95f, 4_000L)
        guard.onField(95f, 5_500L)
        assertFalse(guard.covered)
    }

    @Test
    fun `field shift below 20 uT does not close`() {
        warm()
        guard.onLight(0.1f, 2_000L)
        guard.onField(69f, 2_000L)
        guard.onField(69f, 3_500L)
        assertFalse(guard.covered)
    }

    @Test
    fun `field shift of 20 uT closes`() {
        warm()
        guard.onLight(0.1f, 2_000L)
        guard.onField(70f, 2_000L)
        guard.onField(70f, 3_000L)
        assertTrue(guard.covered)
    }

    @Test
    fun `onsets more than 750 ms apart do not close`() {
        warm()
        guard.onLight(0.1f, 2_000L)
        guard.onField(95f, 2_900L)
        guard.onField(96f, 4_200L)
        assertFalse(guard.covered)
    }

    @Test
    fun `light returning restarts the second`() {
        warm()
        guard.onLight(0.1f, 2_000L)
        guard.onField(95f, 2_000L)
        guard.onLight(100f, 2_500L)
        guard.onLight(0.1f, 2_600L)
        guard.onField(95f, 3_100L)
        assertFalse(guard.covered)
        guard.onField(95f, 3_600L)
        assertTrue(guard.covered)
    }

    @Test
    fun `does not close during warm-up`() {
        guard.onLight(100f, 0L)
        guard.onField(50f, 0L)
        guard.onLight(0.1f, 500L)
        guard.onField(95f, 500L)
        guard.onField(95f, 1_400L)
        assertFalse(guard.covered)
    }

    @Test
    fun `screen wake inside a closed case does not open`() {
        close()
        guard.setActive(true) // charger wakes the screen; sensors restart in the dark
        guard.setActive(false)
        guard.onLight(0f, 10_000L)
        guard.onLight(1.9f, 10_100L)
        guard.onField(50f, 10_200L)
        assertTrue(guard.covered)
    }

    @Test
    fun `room light opens and close detection re-arms`() {
        close()
        guard.onLight(2f, 5_000L)
        assertEquals(listOf(true to "sensors", false to "light"), changes)
        guard.onField(95f, 5_100L) // stale pre-open state must not re-close
        assertFalse(guard.covered)
    }

    @Test
    fun `three taps within two seconds open`() {
        guard.onTap(0L) // ignored while uncovered
        close()
        guard.onTap(0L)
        guard.onTap(1_500L)
        guard.onTap(3_600L) // first tap aged out
        assertTrue(guard.covered)
        guard.onTap(3_700L)
        guard.onTap(3_800L)
        assertEquals(false to "taps", changes.last())
    }

    @Test
    fun `field strength removes hard-iron offset`() {
        assertEquals(50f, fieldStrength(floatArrayOf(130f, 40f, 0f, 100f, 0f, 0f), true), 0.001f)
        assertEquals(50f, fieldStrength(floatArrayOf(70f, -40f, 0f, 100f, 0f, 0f), true), 0.001f)
        assertEquals(50f, fieldStrength(floatArrayOf(30f, 40f, 0f), false), 0.001f)
    }
}
