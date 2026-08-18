package com.example.beast_academy_apk

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/** Foreground-only bridge from Android sensors to [CoverCloseDetector]. */
internal class CoverSensorMonitor(
    context: Context,
    private val onCoverClosed: () -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val magneticSensor =
        sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val detector = CoverCloseDetector()
    private var started = false

    fun start() {
        if (started) return
        if (sensorManager == null || lightSensor == null || magneticSensor == null) {
            Log.w(TAG, "Automatic cover detection unavailable: required sensors are missing")
            return
        }

        detector.reset()
        val lightRegistered = sensorManager.registerListener(
            this,
            lightSensor,
            SensorManager.SENSOR_DELAY_GAME,
        )
        val magneticRegistered = sensorManager.registerListener(
            this,
            magneticSensor,
            SensorManager.SENSOR_DELAY_GAME,
        )
        started = lightRegistered && magneticRegistered
        if (!started) {
            sensorManager.unregisterListener(this)
            Log.w(TAG, "Automatic cover detection unavailable: sensor registration failed")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!started || event == null) return
        // Sensor timestamps share Android's monotonic time base, so their relative timing remains
        // accurate even if callbacks are briefly delayed on the main thread.
        val timeMillis = event.timestamp / 1_000_000L
        val closed = when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> detector.onLight(event.values.firstOrNull() ?: return, timeMillis)
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                if (event.values.size < 3) return
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                detector.onMagneticField(sqrt(x * x + y * y + z * z), timeMillis)
            }
            else -> false
        }

        if (closed) {
            stop()
            Log.i(TAG, "Case closure detected; entering covered mode")
            onCoverClosed()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val TAG = "BeastAcademyCover"
    }
}
