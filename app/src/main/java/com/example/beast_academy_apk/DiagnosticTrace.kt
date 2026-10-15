package com.example.beast_academy_apk

import android.util.Log
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.MotionEvent
import java.io.File

/** Event-only diagnostics for reproducing pinned WebView input failures. */
internal object DiagnosticTrace {
    private const val TAG = "BeastAcademyDiag"
    private const val MAX_BYTES = 262144L
    private val moveTimes = mutableMapOf<String, Long>()
    private var writer: Handler? = null
    private var directory: File? = null

    fun initialize(context: Context) {
        if (!BuildConfig.DEBUG || writer != null) return
        directory = File(context.filesDir, "input-trace").apply { mkdirs() }
        writer = Handler(HandlerThread("input-trace").apply { start() }.looper)
        record("session version=${BuildConfig.VERSION_NAME} wall=${System.currentTimeMillis()} uptime=${SystemClock.uptimeMillis()}")
    }

    fun record(event: String) {
        if (!BuildConfig.DEBUG) return
        val line = "wall=${System.currentTimeMillis()} uptime=${SystemClock.uptimeMillis()} $event"
        Log.i(TAG, line)
        writer?.post {
            runCatching {
                val dir = directory ?: return@runCatching
                val file = File(dir, "current.log")
                if (file.length() >= MAX_BYTES) {
                    File(dir, "previous.log").delete()
                    file.renameTo(File(dir, "previous.log"))
                }
                file.appendText(line + "\n")
            }.onFailure { Log.w(TAG, "Unable to persist input trace", it) }
        }
    }

    fun touch(stage: String, event: MotionEvent, result: Boolean? = null) {
        if (!BuildConfig.DEBUG) return
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val previous = moveTimes[stage] ?: Long.MIN_VALUE
            if (previous != Long.MIN_VALUE && event.eventTime - previous < 80) return
            moveTimes[stage] = event.eventTime
        }
        val pointers = (0 until event.pointerCount).joinToString(";") {
            "${event.getPointerId(it)}:${event.getX(it)},${event.getY(it)}"
        }
        record("native stage=$stage action=${MotionEvent.actionToString(event.action)} " +
            "down=${event.downTime} time=${event.eventTime} count=${event.pointerCount} " +
            "pointers=[$pointers] source=${event.source} device=${event.deviceId} result=$result")
    }

    fun preserveFailure(reason: String) {
        if (!BuildConfig.DEBUG) return
        record("POSSIBLE STALE TOUCH $reason; preserving trace without resetting WebView")
        writer?.post {
            runCatching {
                val dir = directory ?: return@runCatching
                val snapshot = File(dir, "suspected-stale-touch.log")
                // Keep the first transition; continuing input must not overwrite the evidence.
                if (!snapshot.exists()) {
                    snapshot.writeText(listOf("previous.log", "current.log").joinToString("") {
                        File(dir, it).takeIf(File::exists)?.readText() ?: ""
                    })
                }
            }.onFailure { Log.w(TAG, "Unable to preserve failure trace", it) }
        }
    }
}
