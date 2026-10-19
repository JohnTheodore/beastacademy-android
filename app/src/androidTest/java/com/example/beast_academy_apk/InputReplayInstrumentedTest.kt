package com.example.beast_academy_apk

import android.app.KeyguardManager
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Replays recorded physical MotionEvents only in the separate, account-free lab. */
@RunWith(AndroidJUnit4::class)
class InputReplayInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun evaluate(scenario: ActivityScenario<InputLabActivity>, expression: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        scenario.onActivity { it.webView.evaluateJavascript(expression) { value -> result = value; latch.countDown() } }
        check(latch.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun copyContacts(event: MotionEvent, action: Int, exclude: Int = -1): MotionEvent {
        val indices = (0 until event.pointerCount).filter { it != exclude }
        val properties = indices.map { index -> MotionEvent.PointerProperties().also { event.getPointerProperties(index, it) } }.toTypedArray()
        val coords = indices.map { index -> MotionEvent.PointerCoords().also { event.getPointerCoords(index, it) } }.toTypedArray()
        return MotionEvent.obtain(event.downTime, maxOf(event.eventTime, SystemClock.uptimeMillis()), action,
            indices.size, properties, coords, event.metaState, event.buttonState, event.xPrecision,
            event.yPrecision, event.deviceId, event.edgeFlags, event.source, event.flags)
    }

    @Test fun replayNaturalFiveContactGesture() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".inputlab")) { "Never instrument the child's process" }
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val args = InstrumentationRegistry.getArguments()
        val scrollable = args.getString("scrollable") == "true"
        val nativeHost = args.getString("nativeHost") == "true"
        val records = JSONArray(context.assets.open(if (args.getString("dense") == "true") "incident-20261019-dense.json" else "incident-20261019.json").bufferedReader().use { it.readText() })
        val intent = Intent(context, InputLabActivity::class.java)
            .putExtra("claimCanvas", args.getString("claimCanvas") == "true")
            .putExtra("nativeHost", nativeHost).putExtra("scrollable", scrollable)
            .putExtra("chromeTrace", args.getString("chromeTrace") == "true")
            .putExtra("lessonLike", args.getString("lessonLike") == "true")
        ActivityScenario.launch<InputLabActivity>(intent).use { scenario ->
            val deadline = SystemClock.uptimeMillis() + 10000
            while (evaluate(scenario, "!!window.probe") != "true") {
                check(SystemClock.uptimeMillis() < deadline)
                SystemClock.sleep(50)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(250)
            SystemClock.sleep(args.getString("startDelay")?.toLong() ?: 0L)
            val origin = evaluate(scenario, "performance.timeOrigin")
            try { repeat(args.getString("repeats")?.toInt() ?: 20) { attempt ->
                evaluate(scenario, "window.scrollTo(0,0); probe.events=[]")
                val start = SystemClock.uptimeMillis() + 50
                var active: MotionEvent? = null
                try {
                    for (i in 0 until records.length()) {
                        val row = records.getJSONObject(i)
                        val target = start + row.getLong("offset")
                        val remaining = target - SystemClock.uptimeMillis()
                        if (remaining > 0) SystemClock.sleep(remaining)
                        val points = row.getJSONArray("pointers")
                        val props = Array(points.length()) { index ->
                            MotionEvent.PointerProperties().apply {
                                id = points.getJSONArray(index).getInt(0)
                                toolType = MotionEvent.TOOL_TYPE_FINGER
                            }
                        }
                        val coords = Array(points.length()) { index ->
                            MotionEvent.PointerCoords().apply {
                                x = points.getJSONArray(index).getDouble(1).toFloat()
                                y = points.getJSONArray(index).getDouble(2).toFloat()
                                pressure = 1f; size = .05f
                            }
                        }
                        val event = MotionEvent.obtain(start, maxOf(target, SystemClock.uptimeMillis()),
                            row.getInt("action"), points.length(), props, coords,
                            0, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                        try {
                            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, row.getInt("action") == MotionEvent.ACTION_UP))
                            active?.recycle()
                            active = when (event.actionMasked) {
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> null
                                MotionEvent.ACTION_POINTER_UP -> copyContacts(event, MotionEvent.ACTION_MOVE, event.actionIndex)
                                else -> MotionEvent.obtain(event)
                            }
                        } finally { event.recycle() }
                    }
                } finally {
                    // Finish any stream interrupted by a failed injection or assertion.
                    active?.let { remaining ->
                        val cancel = copyContacts(remaining, MotionEvent.ACTION_CANCEL)
                        try { instrumentation.uiAutomation.injectInputEvent(cancel, true) }
                        finally { cancel.recycle(); remaining.recycle() }
                    }
                }
                SystemClock.sleep(300)
                val state = evaluate(scenario, "JSON.stringify({probe,scrollY,scale:visualViewport.scale,viewport:[innerWidth,innerHeight],origin:performance.timeOrigin})")
                DiagnosticTrace.record("replay attempt=$attempt scrollable=$scrollable native=$nativeHost result=$state")
                val probe = JSONObject(JSONObject("{\"v\":$state}").getString("v")).getJSONObject("probe")
                assertEquals("Replay $attempt stranded contacts: $state", 0, probe.getJSONArray("active").length())
                assertEquals(origin, evaluate(scenario, "performance.timeOrigin"))
            } } finally {
                SystemClock.sleep(args.getString("endDelay")?.toLong() ?: 0L)
                if (args.getString("chromeTrace") == "true") {
                    val stopped = CountDownLatch(1)
                    scenario.onActivity { it.stopChromeTrace { stopped.countDown() } }
                    check(stopped.await(15, TimeUnit.SECONDS)) { "Browser input trace did not finish writing" }
                }
            }
        }
    }
}
