package com.example.beast_academy_apk

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Sole owner of the case cover: its sensors, close/open decisions, manual unlock and covered state.
 *
 * Closes when light drops to <= 15% of its recent level (from >= 1 lux) and field strength shifts by
 * >= max(20 uT, 20%), both starting within 750 ms of each other and holding for 1 s, after a 1.5 s
 * warm-up. Opens on >= 2 lux or three taps within 2 s. A screen wake never opens it: plugging a
 * charger into a closed case wakes the screen. Onsets, not latest samples, are compared because
 * the light sensor is on-change and stays silent while the case is closed.
 */
internal class CoverGuard(
    private val sensors: SensorManager?,
    private val onChange: (covered: Boolean, reason: String) -> Unit,
) : SensorEventListener {
    var covered = false
        private set
    private var listening = false
    private var firstMillis: Long? = null
    private val taps = ArrayDeque<Long>()
    private val light = Signal { base, lux -> base >= 1f && lux <= max(0.5f, base * 0.15f) && base - lux >= 0.5f }
    private val field = Signal { base, uT -> abs(uT - base) >= max(20f, base * 0.2f) }

    /** Listen only while the activity is resumed with the screen on. */
    fun setActive(active: Boolean) {
        if (active == listening) return
        listening = active
        resetSignals()
        val manager = sensors ?: return
        if (!active) return manager.unregisterListener(this)
        val wanted = listOfNotNull(
            manager.getDefaultSensor(Sensor.TYPE_LIGHT),
            manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
                ?: manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
        )
        val registered = wanted.count { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        if (registered < 2) Log.w(TAG, "Cover detection limited: $registered of 2 sensors registered")
    }

    fun onLight(lux: Float, timeMillis: Long) {
        if (!covered) sample(light, lux, timeMillis) else if (lux >= OPEN_LUX) setCovered(false, "light")
    }

    fun onField(microTesla: Float, timeMillis: Long) {
        if (!covered) sample(field, microTesla, timeMillis)
    }

    fun onTap(timeMillis: Long) {
        if (!covered) return
        taps.addLast(timeMillis)
        while (timeMillis - taps.first() > 2_000L) taps.removeFirst()
        if (taps.size >= 3) setCovered(false, "taps")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!listening || event == null) return
        val timeMillis = event.timestamp / 1_000_000L
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> onLight(event.values[0], timeMillis)
            Sensor.TYPE_MAGNETIC_FIELD -> onField(fieldStrength(event.values, false), timeMillis)
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> onField(fieldStrength(event.values, true), timeMillis)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun sample(signal: Signal, value: Float, timeMillis: Long) {
        if (!value.isFinite() || value < 0f) return
        val first = firstMillis ?: timeMillis.also { firstMillis = it }
        signal.count++
        val warm = timeMillis - first >= 1_500L && light.count >= 3 && field.count >= 3
        signal.update(value, timeMillis, warm)
        val lightSince = light.closedSince ?: return
        val fieldSince = field.closedSince ?: return
        if (abs(lightSince - fieldSince) <= 750L && timeMillis - max(lightSince, fieldSince) >= 1_000L) {
            setCovered(true, "sensors")
        }
    }

    private fun setCovered(value: Boolean, reason: String) {
        covered = value
        taps.clear()
        resetSignals()
        onChange(value, reason)
    }

    private fun resetSignals() {
        firstMillis = null
        light.reset()
        field.reset()
    }

    /** One sensor's slow-moving baseline and when its reading began to look closed. */
    private class Signal(private val looksClosed: (baseline: Float, value: Float) -> Boolean) {
        var count = 0
        var closedSince: Long? = null
        private var baseline: Float? = null

        fun update(value: Float, timeMillis: Long, warm: Boolean) {
            val base = baseline
            val closed = warm && base != null && looksClosed(base, value)
            closedSince = if (closed) closedSince ?: timeMillis else null
            if (!closed) baseline = base?.plus((if (warm) 0.015f else 0.25f) * (value - base)) ?: value
        }

        fun reset() {
            count = 0
            closedSince = null
            baseline = null
        }
    }

    private companion object {
        const val TAG = "BeastAcademyCover"
        const val OPEN_LUX = 2f
    }
}

/** Field strength without the device's hard-iron offset, so rotation alone barely changes it. */
internal fun fieldStrength(values: FloatArray, uncalibrated: Boolean): Float {
    val offset = if (uncalibrated && values.size >= 6) 3 else -1
    return sqrt((0..2).sumOf { i ->
        val v = values[i] - if (offset < 0) 0f else values[offset + i]
        (v * v).toDouble()
    }).toFloat()
}
