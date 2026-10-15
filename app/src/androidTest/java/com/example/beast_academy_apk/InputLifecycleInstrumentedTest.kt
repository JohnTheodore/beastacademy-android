package com.example.beast_academy_apk

import android.content.Intent
import android.app.KeyguardManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class InputLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var downTime = 0L
    private var x = 0f
    private var y = 0f

    private fun launch(
        nativeHost: Boolean = false,
        repairCancellation: Boolean = InstrumentationRegistry.getArguments().getString("baseline") != "true"
    ): ActivityScenario<InputLabActivity> {
        check(instrumentation.targetContext.packageName.endsWith(".inputlab")) {
            "Run with -PinputLab=true; never instrument the child's app process."
        }
        check(!instrumentation.targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            "Unlock the tablet before running native gesture tests; tests never change the lock state."
        }
        val intent = Intent(instrumentation.targetContext, InputLabActivity::class.java)
            .putExtra("nativeHost", nativeHost)
            .putExtra("repairCancel", repairCancellation)
        val scenario = ActivityScenario.launch<InputLabActivity>(intent)
        awaitProbe(scenario)
        val deadline = SystemClock.uptimeMillis() + 10000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { ready = it.webView.width > 200 && it.webView.height > 200 && it.webView.hasWindowFocus() }
            if (!ready) SystemClock.sleep(50)
        }
        check(ready) { "Fixture has no laid-out focused WebView" }
        scenario.onActivity {
            val location = IntArray(2)
            it.webView.getLocationOnScreen(location)
            x = location[0] + it.webView.width * .4f
            y = location[1] + it.webView.height * .4f
            DiagnosticTrace.record("test bounds=${it.webView.width}x${it.webView.height} location=${location.toList()} injection=$x,$y")
        }
        return scenario
    }

    private fun evaluate(scenario: ActivityScenario<InputLabActivity>, expression: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        scenario.onActivity { it.webView.evaluateJavascript(expression) { value -> result = value; latch.countDown() } }
        check(latch.await(5, TimeUnit.SECONDS)) { "Renderer failed to answer; preserve inputlab process" }
        return result
    }

    private fun awaitProbe(scenario: ActivityScenario<InputLabActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (evaluate(scenario, "!!window.probe") == "true") return
            SystemClock.sleep(50)
        }
        error("Fixture did not load")
    }

    private fun snapshot(scenario: ActivityScenario<InputLabActivity>): JSONObject =
        JSONObject(evaluate(scenario, "window.probe"))

    private fun send(action: Int, count: Int, delta: Float = 0f, ids: IntArray = IntArray(count) { it }, requireDelivery: Boolean = true) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val properties = Array(count) { MotionEvent.PointerProperties().apply { id = ids[it]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(count) { MotionEvent.PointerCoords().apply {
            x = this@InputLifecycleInstrumentedTest.x + it * 80 + delta
            y = this@InputLifecycleInstrumentedTest.y
            pressure = 1f
            size = .05f
        } }
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count, properties, coords,
            0, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            val delivered = instrumentation.uiAutomation.injectInputEvent(event, true)
            if (requireDelivery) assertTrue("Input injection rejected", delivered)
        }
        finally { event.recycle() }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(40)
    }

    private fun pressThree() {
        send(MotionEvent.ACTION_DOWN, 1)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 2)
        send(MotionEvent.ACTION_POINTER_DOWN or (2 shl 8), 3)
    }

    private fun releaseThree(requireDelivery: Boolean = true) {
        send(MotionEvent.ACTION_POINTER_UP or (2 shl 8), 3, requireDelivery = requireDelivery)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl 8), 2, requireDelivery = requireDelivery)
        send(MotionEvent.ACTION_UP, 1, requireDelivery = requireDelivery)
    }

    private fun assertDrags(scenario: ActivityScenario<InputLabActivity>) {
        val previous = snapshot(scenario).getInt("dragMoves")
        send(MotionEvent.ACTION_DOWN, 1)
        send(MotionEvent.ACTION_MOVE, 1, 60f)
        send(MotionEvent.ACTION_UP, 1, 60f)
        val deadline = SystemClock.uptimeMillis() + 3000
        var state = snapshot(scenario)
        while (state.getInt("dragMoves") <= previous && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            state = snapshot(scenario)
        }
        assertEquals("Stale DOM contacts: $state", 0, state.getJSONArray("active").length())
        assertTrue("Single-finger drag was not delivered: $state", state.getInt("dragMoves") > previous)
    }

    @Test fun composeRepeatedMultiTouchClearsAllContacts() {
        launch().use { scenario ->
            repeat(20) {
                pressThree()
                assertEquals(3, snapshot(scenario).getJSONArray("active").length())
                releaseThree()
                assertDrags(scenario)
            }
        }
    }

    @Test fun nativeRepeatedMultiTouchClearsAllContacts() {
        launch(nativeHost = true).use { scenario ->
            repeat(20) { pressThree(); releaseThree(); assertDrags(scenario) }
        }
    }

    @Test fun composeHostRemovalDuringMultiTouchClearsAllContacts() {
        launch().use { scenario ->
            pressThree()
            assertEquals(3, snapshot(scenario).getJSONArray("active").length())
            scenario.onActivity { it.showHost = false }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(150)
            val removed = snapshot(scenario)
            DiagnosticTrace.record("test host-removed probe=$removed")
            // Clear Android's injected fingers, but keep the WebView's state available for inspection.
            releaseThree()
            scenario.onActivity { it.showHost = true }
            instrumentation.waitForIdleSync()
            assertEquals("Compose removal left stale contacts: $removed", 0, removed.getJSONArray("active").length())
            assertDrags(scenario)
        }
    }

    @Test fun backgroundDuringMultiTouchClearsAllContacts() {
        launch().use { scenario ->
            pressThree()
            scenario.moveToState(Lifecycle.State.CREATED)
            releaseThree(requireDelivery = false)
            scenario.moveToState(Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()
            assertDrags(scenario)
        }
    }

    @Test fun composeRemovalWithOnlyNonzeroPointerClearsContact() {
        launch().use { scenario ->
            send(MotionEvent.ACTION_DOWN, 1)
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 2, ids = intArrayOf(0, 2))
            send(MotionEvent.ACTION_POINTER_UP, 2, ids = intArrayOf(0, 2))
            assertEquals("[2]", snapshot(scenario).getJSONArray("active").toString())
            scenario.onActivity { it.showHost = false }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
            val removed = snapshot(scenario)
            send(MotionEvent.ACTION_UP, 1, ids = intArrayOf(2), requireDelivery = false)
            scenario.onActivity { it.showHost = true }
            instrumentation.waitForIdleSync()
            assertEquals("Dummy cancel missed nonzero pointer: $removed", 0, removed.getJSONArray("active").length())
            assertDrags(scenario)
        }
    }

    @Test fun unrepairedCancellationReproducesFrozenSingleFingerDrag() {
        launch(repairCancellation = false).use { scenario ->
            val originalDocument = evaluate(scenario, "performance.timeOrigin")
            send(MotionEvent.ACTION_DOWN, 1)
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 2, ids = intArrayOf(0, 2))
            send(MotionEvent.ACTION_POINTER_UP, 2, ids = intArrayOf(0, 2))
            scenario.onActivity { it.showHost = false }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
            val interrupted = snapshot(scenario)
            send(MotionEvent.ACTION_UP, 1, ids = intArrayOf(2), requireDelivery = false)
            scenario.onActivity { it.showHost = true }
            instrumentation.waitForIdleSync()
            assertEquals("Baseline must reproduce the phantom finger", "[2]", interrupted.getJSONArray("active").toString())

            val before = snapshot(scenario).getInt("dragMoves")
            send(MotionEvent.ACTION_DOWN, 1)
            send(MotionEvent.ACTION_MOVE, 1, 60f)
            send(MotionEvent.ACTION_UP, 1, 60f)
            SystemClock.sleep(150)
            val frozen = snapshot(scenario)
            assertEquals("Phantom finger must still be present", "[2]", frozen.getJSONArray("active").toString())
            assertEquals("Two-contact input must block the single-finger drag", before, frozen.getInt("dragMoves"))
            assertEquals("Reproduction must not reload the document", originalDocument, evaluate(scenario, "performance.timeOrigin"))
        }
    }

    @Test fun validFullCancellationClearsAllContacts() {
        launch().use { scenario ->
            pressThree()
            send(MotionEvent.ACTION_CANCEL, 3)
            assertDrags(scenario)
        }
    }

    @Test fun coverCancellationClearsAllContactsWithoutReload() {
        launch().use { scenario ->
            pressThree()
            scenario.onActivity { it.webView.cancelActiveTouches("cover closed test") }
            val cancelled = snapshot(scenario)
            assertEquals("Cover cancellation left contacts: $cancelled", 0, cancelled.getJSONArray("active").length())
            releaseThree()
            assertDrags(scenario)
        }
    }

    @Test fun repeatedInterruptionsKeepSamePageUsable() {
        launch().use { scenario ->
            val originalDocument = evaluate(scenario, "performance.timeOrigin")
            repeat(50) { attempt ->
                send(MotionEvent.ACTION_DOWN, 1)
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 2, ids = intArrayOf(0, 2))
                send(MotionEvent.ACTION_POINTER_UP, 2, ids = intArrayOf(0, 2))
                scenario.onActivity { it.showHost = false }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(50)
                val interrupted = snapshot(scenario)
                send(MotionEvent.ACTION_UP, 1, ids = intArrayOf(2), requireDelivery = false)
                scenario.onActivity { it.showHost = true }
                instrumentation.waitForIdleSync()
                assertEquals("Attempt $attempt stranded contact: $interrupted", 0, interrupted.getJSONArray("active").length())
                assertDrags(scenario)
                assertEquals("Test unexpectedly reloaded the document", originalDocument, evaluate(scenario, "performance.timeOrigin"))
            }
            DiagnosticTrace.record("test 50 interruptions passed with unchanged document timeOrigin=$originalDocument")
        }
    }
}
