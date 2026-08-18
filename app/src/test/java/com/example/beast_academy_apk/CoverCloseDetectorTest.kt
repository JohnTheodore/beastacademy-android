package com.example.beast_academy_apk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverCloseDetectorTest {
    @Test
    fun `requires simultaneous sustained light and magnetic changes`() {
        val detector = warmedDetector()

        assertFalse(detector.onLight(0.2f, 2_000L))
        assertFalse(detector.onMagneticField(90f, 2_050L))
        assertFalse(detector.onMagneticField(91f, 2_200L))
        assertTrue(detector.onMagneticField(92f, 2_310L))
    }

    @Test
    fun `darkness alone does not trigger`() {
        val detector = warmedDetector()

        assertFalse(detector.onLight(0.1f, 2_000L))
        assertFalse(detector.onLight(0.1f, 2_400L))
        assertFalse(detector.onMagneticField(51f, 2_450L))
    }

    @Test
    fun `magnetic change alone does not trigger`() {
        val detector = warmedDetector()

        assertFalse(detector.onMagneticField(95f, 2_000L))
        assertFalse(detector.onMagneticField(96f, 2_400L))
    }

    @Test
    fun `widely separated sensor changes do not trigger`() {
        val detector = warmedDetector()

        assertFalse(detector.onLight(0.1f, 2_000L))
        assertFalse(detector.onMagneticField(95f, 2_900L))
        assertFalse(detector.onMagneticField(96f, 3_300L))
    }

    @Test
    fun `does not trigger during warmup`() {
        val detector = CoverCloseDetector()

        detector.onLight(100f, 0L)
        detector.onMagneticField(50f, 0L)
        detector.onLight(100f, 500L)
        detector.onMagneticField(50f, 500L)
        assertFalse(detector.onLight(0.1f, 1_000L))
        assertFalse(detector.onMagneticField(95f, 1_000L))
    }

    @Test
    fun `triggers only once until reset`() {
        val detector = warmedDetector()

        detector.onLight(0.1f, 2_000L)
        detector.onMagneticField(95f, 2_000L)
        assertTrue(detector.onMagneticField(95f, 2_300L))
        assertFalse(detector.onMagneticField(95f, 2_700L))

        detector.reset()
        warm(detector)
        detector.onLight(0.1f, 2_000L)
        detector.onMagneticField(95f, 2_000L)
        assertTrue(detector.onMagneticField(95f, 2_300L))
    }

    private fun warmedDetector(): CoverCloseDetector = CoverCloseDetector().also(::warm)

    private fun warm(detector: CoverCloseDetector) {
        listOf(0L, 500L, 1_000L, 1_500L).forEach { time ->
            detector.onLight(100f, time)
            detector.onMagneticField(50f, time)
        }
    }
}
