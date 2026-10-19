package com.example.beast_academy_apk

import android.util.Log
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.MotionEvent
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import androidx.webkit.WebViewCompat

/** Event-only diagnostics for reproducing pinned WebView input failures. */
internal object DiagnosticTrace {
    private const val TAG = "BeastAcademyDiag"
    private const val MAX_BYTES = 262144L
    private const val MAX_PENDING_BYTES = 524288
    private val pendingBytes = AtomicInteger()
    private val droppedRecords = AtomicLong()
    private var persistedDropCount = 0L
    private val moveTimes = mutableMapOf<String, Long>()
    private var writer: Handler? = null
    private var directory: File? = null
    private var sessionHeader = ""

    fun initialize(context: Context) {
        if (writer != null) return
        directory = File(context.filesDir, "input-trace").apply { mkdirs() }
        writer = Handler(HandlerThread("input-trace").apply { start() }.looper)
        val provider = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        sessionHeader = "session package=${context.packageName} version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} " +
            "debug=${BuildConfig.DEBUG} pid=${android.os.Process.myPid()} model=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} " +
            "provider=${provider?.packageName} providerVersion=${provider?.versionName} " +
            "startWall=${System.currentTimeMillis()} startUptime=${SystemClock.uptimeMillis()}"
        record(sessionHeader)
    }

    fun record(event: String) {
        val line = "wall=${System.currentTimeMillis()} uptime=${SystemClock.uptimeMillis()} $event"
        if (BuildConfig.DEBUG) Log.i(TAG, line)
        val handler = writer ?: return
        // Bound the producer queue as well as disk storage. Slow storage must not make
        // an input storm retain unlimited strings/Runnables on the UI process heap.
        val bytes = line.length * 2
        if (pendingBytes.addAndGet(bytes) > MAX_PENDING_BYTES) {
            pendingBytes.addAndGet(-bytes)
            droppedRecords.incrementAndGet()
            return
        }
        handler.post {
            runCatching {
                val dir = directory ?: return@runCatching
                val file = File(dir, "current.log")
                if (file.length() >= MAX_BYTES) {
                    File(dir, "previous.log").delete()
                    file.renameTo(File(dir, "previous.log"))
                }
                val dropped = droppedRecords.get()
                if (dropped != persistedDropCount) {
                    file.appendText("wall=${System.currentTimeMillis()} TRACE_GAP droppedRecords=$dropped\n")
                    persistedDropCount = dropped
                }
                file.appendText(line + "\n")
            }.onFailure { Log.w(TAG, "Unable to persist input trace", it) }
            pendingBytes.addAndGet(-bytes)
        }
    }

    fun touch(stage: String, event: MotionEvent, result: Boolean? = null) {
        if (!BuildConfig.DEBUG && !stage.startsWith("webview-") && stage !in setOf("activity-dispatch", "host-dispatch")) return
        // Keep every move once at the browser boundary for faithful native replays.
        if (event.actionMasked == MotionEvent.ACTION_MOVE && !(stage.startsWith("webview-") && stage.endsWith("-dispatch"))) {
            val previous = moveTimes[stage] ?: Long.MIN_VALUE
            if (previous != Long.MIN_VALUE && event.eventTime - previous < 80) return
            moveTimes[stage] = event.eventTime
        }
        val pointers = (0 until event.pointerCount).joinToString(";") {
            "${event.getPointerId(it)}:${event.getX(it)},${event.getY(it)}"
        }
        val detailed = stage.startsWith("webview-") && stage.endsWith("-dispatch")
        val details = if (detailed) {
            val history = (0 until event.historySize).joinToString("|") { sample ->
                "${event.getHistoricalEventTime(sample)}:" + (0 until event.pointerCount).joinToString(";") { pointer ->
                    "${event.getPointerId(pointer)},${event.getHistoricalX(pointer, sample)},${event.getHistoricalY(pointer, sample)},${event.getHistoricalPressure(pointer, sample)}"
                }
            }
            val properties = (0 until event.pointerCount).joinToString(";") {
                "${event.getPointerId(it)}:tool=${event.getToolType(it)},pressure=${event.getPressure(it)},size=${event.getSize(it)},major=${event.getTouchMajor(it)},minor=${event.getTouchMinor(it)},orientation=${event.getOrientation(it)}"
            }
            " meta=${event.metaState} buttons=${event.buttonState} flags=${event.flags} edge=${event.edgeFlags}" +
                " precision=${event.xPrecision},${event.yPrecision} properties=[$properties] history=[$history]"
        } else ""
        record("native stage=$stage action=${MotionEvent.actionToString(event.action)} " +
            "down=${event.downTime} time=${event.eventTime} count=${event.pointerCount} " +
            "pointers=[$pointers] source=${event.source} device=${event.deviceId} result=$result$details")
    }

    fun preserveFailure(reason: String) {
        record("POSSIBLE STALE TOUCH $reason; preserving trace without resetting WebView")
        writer?.post {
            runCatching {
                val dir = directory ?: return@runCatching
                val snapshot = File(dir, "suspected-stale-touch.log")
                // Bound storage while retaining separate failures across documents/sessions.
                // A stale snapshot from a prior day must not suppress today's transition.
                File(dir, "suspected-stale-touch-3.log").delete()
                for (index in 2 downTo 1) {
                    File(dir, "suspected-stale-touch-$index.log").renameTo(File(dir, "suspected-stale-touch-${index + 1}.log"))
                }
                snapshot.renameTo(File(dir, "suspected-stale-touch-1.log"))
                snapshot.writeText(sessionHeader + "\nfailure=$reason\n" + listOf("previous.log", "current.log").joinToString("") {
                    File(dir, it).takeIf(File::exists)?.readText() ?: ""
                })
            }.onFailure { Log.w(TAG, "Unable to preserve failure trace", it) }
        }
    }

    /** Called from the permission-protected provider's Binder thread, never the UI thread. */
    fun snapshotArchive(): ByteArray {
        val handler = checkNotNull(writer) { "Diagnostics have not initialized" }
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val ready = CountDownLatch(1)
        var archive: ByteArray? = null
        var failure: Throwable? = null
        handler.post {
            try {
                val output = ByteArrayOutputStream()
                ZipOutputStream(output).use { zip ->
                    val metadata = "version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} debug=${BuildConfig.DEBUG} " +
                        "pid=${android.os.Process.myPid()} wall=${System.currentTimeMillis()} uptime=${SystemClock.uptimeMillis()} " +
                        "droppedRecords=${droppedRecords.get()} pendingBytes=${pendingBytes.get()}\n$sessionHeader\n"
                    zip.putNextEntry(ZipEntry("diagnostic-state.txt"))
                    zip.write(metadata.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    for (name in listOf("previous.log", "current.log", "suspected-stale-touch.log",
                            "suspected-stale-touch-1.log", "suspected-stale-touch-2.log", "suspected-stale-touch-3.log")) {
                        val file = File(checkNotNull(directory), name)
                        if (!file.exists()) continue
                        zip.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                archive = output.toByteArray()
            } catch (error: Throwable) { failure = error }
            finally { ready.countDown() }
        }
        check(ready.await(5, TimeUnit.SECONDS)) { "Diagnostic writer did not finish snapshot" }
        failure?.let { throw IllegalStateException("Cannot export diagnostic snapshot", it) }
        return checkNotNull(archive)
    }

}
