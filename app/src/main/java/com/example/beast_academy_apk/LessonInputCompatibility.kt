package com.example.beast_academy_apk

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Register once on each WebView, before its first navigation. WebView executes
 * the bootstrap again for every matching document, including reload/history. */
internal object LessonInputCompatibility {
    fun install(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            DiagnosticTrace.record("input compatibility unavailable: document-start unsupported")
            return
        }
        try {
            val source = webView.context.assets.open("lesson-input-compat.js")
                .bufferedReader().use { it.readText() }
            WebViewCompat.addDocumentStartJavaScript(
                webView, "($source)();", setOf("https://beastacademy.com")
            )
            DiagnosticTrace.record("input compatibility v1 registered for each lesson document")
        } catch (failure: Exception) {
            // Provider implementation failures must not prevent opening the site.
            DiagnosticTrace.record("input compatibility registration failed: ${failure.javaClass.simpleName}")
        }
    }
}
