package com.example.beast_academy_apk

import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the real Activity/session owner only in the separate, account-free package. */
@RunWith(AndroidJUnit4::class)
class BrowserSessionInstrumentedTest {
    private fun chrome(activity: MainActivity): BrowserChrome =
        (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as BrowserChrome

    private fun browser(activity: MainActivity) = chrome(activity).browserHost.getChildAt(0) as WebView

    private fun loadFixture(scenario: ActivityScenario<MainActivity>): String {
        scenario.onActivity {
            browser(it).loadDataWithBaseURL("https://inputlab.invalid/", "<!doctype html><canvas></canvas><script>window.fixtureReady=true</script>", "text/html", "UTF-8", null)
        }
        val deadline = SystemClock.uptimeMillis() + 10000
        while (evaluate(scenario, "!!window.fixtureReady") != "true") {
            check(SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(50)
        }
        return evaluate(scenario, "performance.timeOrigin")
    }

    private fun evaluate(scenario: ActivityScenario<MainActivity>, expression: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        scenario.onActivity { browser(it).evaluateJavascript(expression) { value -> result = value; done.countDown() } }
        check(done.await(5, TimeUnit.SECONDS))
        return result
    }

    @Test fun activityPauseResumeAndLateCallbacksPreserveCurrentSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".inputlab")) { "Never launch or instrument the child's app" }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val firstOrigin = loadFixture(scenario)
            var first: WebView? = null
            lateinit var oldClient: WebViewClient
            lateinit var oldChrome: WebChromeClient
            scenario.onActivity {
                first = browser(it)
                oldClient = browser(it).webViewClient
                oldChrome = checkNotNull(browser(it).webChromeClient)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(firstOrigin, evaluate(scenario, "performance.timeOrigin"))
            scenario.onActivity { assertSame(first, browser(it)) }

            // Exercise the real recovery button rather than recreating the Activity.
            scenario.onActivity {
                val chrome = chrome(it)
                chrome.setRecoveryState(BeastAcademyWebViewController.RecoveryState.HungWaiting)
                fun reload(view: View): Boolean {
                    if (view is android.widget.Button && view.text == it.getString(R.string.reload_now)) return view.performClick()
                    if (view is ViewGroup) for (i in 0 until view.childCount) if (reload(view.getChildAt(i))) return true
                    return false
                }
                assertTrue(reload(chrome))
                assertNotSame(first, browser(it))
            }
            val replacementOrigin = loadFixture(scenario)
            assertNotEquals(firstOrigin, replacementOrigin)
            scenario.onActivity {
                val current = browser(it)
                val chrome = chrome(it)
                chrome.setRecoveryState(BeastAcademyWebViewController.RecoveryState.HungWaiting)
                oldClient.onPageCommitVisible(first, "https://beastacademy.com/old")
                oldClient.onRenderProcessGone(first, null)
                oldChrome.onProgressChanged(first, 99)
                oldChrome.onHideCustomView()
                assertEquals(BeastAcademyWebViewController.RecoveryState.HungWaiting, chrome.recoveryState)
                assertSame(current, browser(it))
                assertTrue(current.isAttachedToWindow)
                var rejected = false
                oldChrome.onShowCustomView(View(it), WebChromeClient.CustomViewCallback { rejected = true })
                assertTrue("Stale full-screen request must be rejected", rejected)
            }
            assertEquals(replacementOrigin, evaluate(scenario, "performance.timeOrigin"))
        }
    }
}
