package com.example.beast_academy_apk

import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises production registration, with every fixture request served locally. */
@RunWith(AndroidJUnit4::class)
class LessonInputCompatibilityInstrumentedTest {
    private fun browser(activity: MainActivity): WebView =
        ((activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as BrowserChrome)
            .browserHost.getChildAt(0) as WebView

    private fun evaluate(scenario: ActivityScenario<MainActivity>, script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        scenario.onActivity { browser(it).evaluateJavascript(script) { value -> result = value; latch.countDown() } }
        check(latch.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun awaitDocument(scenario: ActivityScenario<MainActivity>, previous: String? = null): String {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (evaluate(scenario, "window.fixtureReady===true") == "true") {
                val identity = evaluate(scenario, "performance.timeOrigin")
                if (identity != previous) return identity
            }
            SystemClock.sleep(25)
        }
        error("Fixture document did not become ready")
    }

    @Test fun productionBootstrapReappliesBeforeSiteScriptsOnReloadAndNavigation() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".inputlab"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                browser(it).webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                        val html = """<!doctype html><script>
                          window.beforeSite = !!window.__beastInputCompat;
                          window.initialStatus = window.__beastInputCompat?.summary();
                          const modules = {};
                          for (const chunk of window.webpackChunkba || []) chunk[2]({m: modules});
                          window.fixtureReady = true;
                        </script>""".trimIndent()
                        return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                    }
                }
                browser(it).loadUrl("https://beastacademy.com/__inputlab/first")
            }
            var identity = awaitDocument(scenario)
            assertEquals("true", evaluate(scenario, "window.beforeSite"))
            assertEquals("1", evaluate(scenario, "window.initialStatus.version"))
            assertEquals("\"awaiting-module\"", evaluate(scenario, "window.__beastInputCompat.summary().engines[54145]"))
            // A persistent registration must execute in each new document, without
            // adding another registration from onPageFinished or relying on timing.
            repeat(100) {
                val previous = identity
                scenario.onActivity { activity -> browser(activity).reload() }
                identity = awaitDocument(scenario, previous)
                assertEquals("true", evaluate(scenario, "window.beforeSite"))
                assertEquals("0", evaluate(scenario, "window.initialStatus.active"))
                assertEquals("0", evaluate(scenario, "window.initialStatus.adapted"))
            }
            evaluate(scenario, "history.pushState({},'', '/__inputlab/route')")
            assertEquals(identity, evaluate(scenario, "performance.timeOrigin"))
            assertEquals("1", evaluate(scenario, "window.__beastInputCompat.summary().version"))
            scenario.onActivity { browser(it).loadUrl("https://example.invalid/unsupported") }
            awaitDocument(scenario, identity)
            assertEquals("false", evaluate(scenario, "window.beforeSite"))
            assertEquals("\"undefined\"", evaluate(scenario, "typeof window.__beastInputCompat"))
            val unsupported = evaluate(scenario, "performance.timeOrigin")
            scenario.onActivity { browser(it).loadUrl("https://beastacademy.com/__inputlab/return") }
            awaitDocument(scenario, unsupported)
            assertEquals("true", evaluate(scenario, "window.beforeSite"))
        }
    }
}
