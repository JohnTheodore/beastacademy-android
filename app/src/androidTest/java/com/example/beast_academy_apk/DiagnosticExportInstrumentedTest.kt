package com.example.beast_academy_apk

import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Run against both debug and a signed, non-debuggable .inputlab release build. */
@RunWith(AndroidJUnit4::class)
class DiagnosticExportInstrumentedTest {
    @Test fun shellExportRetainsNativeAndDomEvidenceWithoutReplacingDocument() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".inputlab")) { "Never instrument the child's app" }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun browser(activity: MainActivity): WebView {
                val chrome = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as BrowserChrome
                return chrome.browserHost.getChildAt(0) as WebView
            }
            fun evaluate(expression: String): String {
                val done = CountDownLatch(1)
                var result = "null"
                scenario.onActivity { browser(it).evaluateJavascript(expression) { value -> result = value; done.countDown() } }
                check(done.await(5, TimeUnit.SECONDS))
                return result
            }
            scenario.onActivity {
                browser(it).loadDataWithBaseURL("https://inputlab.invalid/", """
                    <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                    <style>html,body{margin:0;height:100%;touch-action:none}canvas{width:100%;height:100%;background:#d8e8ef}</style>
                    <canvas></canvas><script>window.fixtureReady=true</script>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
            val deadline = SystemClock.uptimeMillis() + 10000
            while (evaluate("!!window.fixtureReady") != "true") {
                check(SystemClock.uptimeMillis() < deadline)
                SystemClock.sleep(25)
            }
            val origin = evaluate("performance.timeOrigin")
            var x = 0f
            var y = 0f
            var ready = false
            while (!ready) {
                check(SystemClock.uptimeMillis() < deadline) { "WebView never became ready for native input" }
                scenario.onActivity {
                    val view = browser(it)
                    val chrome = it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as BrowserChrome
                    ready = chrome.recoveryState == BeastAcademyWebViewController.RecoveryState.Healthy &&
                        view.isAttachedToWindow && view.hasWindowFocus() && view.width > 200 && view.height > 200 && !view.isLayoutRequested
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    x = location[0] + view.width * .4f
                    y = location[1] + view.height * .4f
                }
                if (!ready) SystemClock.sleep(25)
            }
            // A laid-out native view can precede the new document's compositor
            // surface. Wait for that surface and focus before testing DOM export.
            val painted = CountDownLatch(1)
            scenario.onActivity {
                browser(it).requestFocus()
                browser(it).postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { painted.countDown() }
                })
            }
            check(painted.await(5, TimeUnit.SECONDS)) { "Fixture was not presented" }
            val focusDeadline = SystemClock.uptimeMillis() + 5000
            while (evaluate("document.hasFocus()") != "true") {
                check(SystemClock.uptimeMillis() < focusDeadline) { "Fixture document did not gain focus" }
                SystemClock.sleep(25)
            }
            val down = SystemClock.uptimeMillis()
            for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
                val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = .05f })
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, properties, coords,
                    0, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                SystemClock.sleep(40)
            }
            val terminalDeadline = SystemClock.uptimeMillis() + 5000
            while (evaluate("window.__beastInputTraceLastTouch?.type==='touchend'") != "true") {
                check(SystemClock.uptimeMillis() < terminalDeadline) { "Document-start DOM recorder did not capture the touch stream" }
                SystemClock.sleep(25)
            }
            val descriptor = instrumentation.uiAutomation.executeShellCommand(
                "content read --uri content://${context.packageName}.input-diagnostics/snapshot")
            val entries = mutableMapOf<String, String>()
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    }
                }
            }
            val metadata = checkNotNull(entries["diagnostic-state.txt"]) { "Shell export was not a valid diagnostic ZIP" }
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            assertTrue(metadata, metadata.contains("debug=$debuggable"))
            assertTrue(metadata, metadata.contains("pid=${android.os.Process.myPid()}"))
            assertTrue(metadata, metadata.contains("droppedRecords=0"))
            assertTrue(metadata, metadata.contains("providerVersion="))
            val log = entries["current.log"] ?: error("Missing current trace")
            assertTrue(log.takeLast(5000), log.contains("native stage=webview-"))
            assertTrue(log.takeLast(5000), log.contains("ACTION_UP"))
            assertTrue(log.takeLast(5000), log.contains("dom webview=") && log.contains("touchend"))
            val terminal = log.lineSequence().last { it.contains("dom webview=") && it.contains("touchend") }
            val event = org.json.JSONObject(terminal.substring(terminal.indexOf('{')))
            assertEquals(origin.toDouble(), event.getDouble("document"), .001)
            assertEquals("Export must not navigate/reload", origin, evaluate("performance.timeOrigin"))
            fun debugControl(enabled: Boolean): String {
                val method = if (enabled) "enable-webview-debugging" else "disable-webview-debugging"
                val result = instrumentation.uiAutomation.executeShellCommand(
                    "content call --uri content://${context.packageName}.input-diagnostics --method $method")
                return ParcelFileDescriptor.AutoCloseInputStream(result).bufferedReader().use { it.readText() }
            }
            try {
                assertTrue(debugControl(true).contains("enabled=true"))
                assertEquals("Enabling ADB inspection must preserve the document", origin, evaluate("performance.timeOrigin"))
            } finally {
                assertTrue(debugControl(false).contains("enabled=false"))
            }
            assertEquals(origin, evaluate("performance.timeOrigin"))
        }
    }
}
