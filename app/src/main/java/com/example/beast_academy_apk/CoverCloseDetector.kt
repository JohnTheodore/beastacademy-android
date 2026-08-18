package com.example.beast_academy_apk

import kotlin.math.abs
import kotlin.math.max

/**
 * Detects a cover closing from a sudden light drop accompanied by a magnetic-field change.
 *
 * The detector intentionally has no "open" transition. Once triggered, callers must stop
 * feeding it samples and create/reset it only after a real Android screen wake.
 */
internal class CoverCloseDetector(
    private val warmUpMillis: Long = 1_500L,
    private val confirmationMillis: Long = 250L,
    private val maxSensorSkewMillis: Long = 750L,
) {
    private data class Sample(val value: Float, val timeMillis: Long)

    private var firstSampleTimeMillis: Long? = null
    private var lightBaseline: Float? = null
    private var magneticBaseline: Float? = null
    private var lightSampleCount = 0
    private var magneticSampleCount = 0
    private var latestLight: Sample? = null
    private var latestMagnetic: Sample? = null
    private var candidateSinceMillis: Long? = null
    private var triggered = false

    fun reset() {
        firstSampleTimeMillis = null
        lightBaseline = null
        magneticBaseline = null
        lightSampleCount = 0
        magneticSampleCount = 0
        latestLight = null
        latestMagnetic = null
        candidateSinceMillis = null
        triggered = false
    }

    fun onLight(lux: Float, timeMillis: Long): Boolean {
        if (triggered || !lux.isFinite() || lux < 0f) return false
        noteFirstSample(timeMillis)
        latestLight = Sample(lux, timeMillis)
        lightSampleCount++

        if (!isWarmedUp(timeMillis)) {
            lightBaseline = updateBaseline(lightBaseline, lux, WARM_UP_ALPHA)
            return false
        }

        val result = evaluate(timeMillis)
        if (!isLightCloseCandidate()) {
            lightBaseline = updateBaseline(lightBaseline, lux, TRACKING_ALPHA)
        }
        return result
    }

    fun onMagneticField(microTesla: Float, timeMillis: Long): Boolean {
        if (triggered || !microTesla.isFinite() || microTesla < 0f) return false
        noteFirstSample(timeMillis)
        latestMagnetic = Sample(microTesla, timeMillis)
        magneticSampleCount++

        if (!isWarmedUp(timeMillis)) {
            magneticBaseline = updateBaseline(magneticBaseline, microTesla, WARM_UP_ALPHA)
            return false
        }

        val result = evaluate(timeMillis)
        if (!isMagneticCloseCandidate()) {
            magneticBaseline = updateBaseline(magneticBaseline, microTesla, TRACKING_ALPHA)
        }
        return result
    }

    private fun noteFirstSample(timeMillis: Long) {
        if (firstSampleTimeMillis == null) firstSampleTimeMillis = timeMillis
    }

    private fun isWarmedUp(timeMillis: Long): Boolean {
        val first = firstSampleTimeMillis ?: return false
        return timeMillis - first >= warmUpMillis &&
            lightSampleCount >= MIN_SAMPLES_PER_SENSOR &&
            magneticSampleCount >= MIN_SAMPLES_PER_SENSOR
    }

    private fun evaluate(timeMillis: Long): Boolean {
        val light = latestLight ?: return false
        val magnetic = latestMagnetic ?: return false
        val signalsAgree = isLightCloseCandidate() &&
            isMagneticCloseCandidate() &&
            abs(light.timeMillis - magnetic.timeMillis) <= maxSensorSkewMillis

        if (!signalsAgree) {
            candidateSinceMillis = null
            return false
        }

        val since = candidateSinceMillis
        if (since == null) {
            candidateSinceMillis = timeMillis
            return false
        }
        if (timeMillis - since < confirmationMillis) return false

        triggered = true
        return true
    }

    private fun isLightCloseCandidate(): Boolean {
        val baseline = lightBaseline ?: return false
        val current = latestLight?.value ?: return false
        if (baseline < MIN_OPEN_LIGHT_LUX) return false
        return current <= max(MAX_CLOSED_LIGHT_LUX, baseline * MAX_CLOSED_LIGHT_RATIO) &&
            baseline - current >= MIN_LIGHT_DROP_LUX
    }

    private fun isMagneticCloseCandidate(): Boolean {
        val baseline = magneticBaseline ?: return false
        val current = latestMagnetic?.value ?: return false
        val requiredChange = max(MIN_MAGNETIC_CHANGE_UT, baseline * MIN_MAGNETIC_CHANGE_RATIO)
        return abs(current - baseline) >= requiredChange
    }

    private fun updateBaseline(current: Float?, sample: Float, alpha: Float): Float =
        current?.let { it + alpha * (sample - it) } ?: sample

    private companion object {
        const val MIN_SAMPLES_PER_SENSOR = 3
        const val WARM_UP_ALPHA = 0.25f
        const val TRACKING_ALPHA = 0.015f

        const val MIN_OPEN_LIGHT_LUX = 1f
        const val MAX_CLOSED_LIGHT_LUX = 0.5f
        const val MAX_CLOSED_LIGHT_RATIO = 0.15f
        const val MIN_LIGHT_DROP_LUX = 0.5f

        const val MIN_MAGNETIC_CHANGE_UT = 12f
        const val MIN_MAGNETIC_CHANGE_RATIO = 0.20f
    }
}
