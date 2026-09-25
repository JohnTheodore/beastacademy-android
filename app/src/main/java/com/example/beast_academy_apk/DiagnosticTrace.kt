package com.example.beast_academy_apk

import android.util.Log

/** Event-only diagnostics for reproducing pinned WebView input failures. */
internal object DiagnosticTrace {
    private const val TAG = "BeastAcademyDiag"

    fun record(event: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, event)
    }
}
