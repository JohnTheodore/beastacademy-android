package com.example.beast_academy_apk

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.SensorManager
import android.os.Bundle
import android.os.PowerManager
import android.view.MotionEvent
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Activity owns one browser session. Updating chrome never reparents the WebView. */
class MainActivity : ComponentActivity() {
    private lateinit var controller: BeastAcademyWebViewController
    private lateinit var chrome: BrowserChrome
    private lateinit var cover: CoverGuard
    private lateinit var visibility: SessionVisibility
    private var receiverRegistered = false
    private var originalBrightness = -1f
    private val running get() = visibility.canRun && !cover.covered

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (::controller.isInitialized) controller.onFilePickerResult(uris.toTypedArray())
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val wasRunning = running
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    visibility.screenOff()
                    cover.setActive(false)
                    controller.onScreenOff()
                }
                Intent.ACTION_SCREEN_ON -> {
                    visibility.screenOn()
                    syncSession(wasRunning)
                }
            }
            DiagnosticTrace.record("screen action=${intent?.action} visibility=$visibility covered=${cover.covered} ${controller.diagnosticState()}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticTrace.initialize(this)
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        enableEdgeToEdge()
        originalBrightness = window.attributes.screenBrightness
        val screenInteractive = getSystemService(PowerManager::class.java).isInteractive
        visibility = SessionVisibility(screenInteractive)
        chrome = BrowserChrome(
            this,
            onReload = { controller.requestRecovery(RecoveryReason.MANUAL_RELOAD) },
            onCoverTap = { cover.onTap(it) },
        )
        val speech = AndroidSpeechSynthesis(this) { chrome.showTtsWarning() }
        controller = BeastAcademyWebViewController(
            activity = this,
            speechBridge = speech,
            onProgressChanged = chrome::setProgress,
            onRecoveryStateChanged = chrome::setRecoveryState
        )
        controller.filePickerLauncher = filePicker
        cover = CoverGuard(getSystemService(SensorManager::class.java)) { covered, reason ->
            DiagnosticTrace.record("cover covered=$covered reason=$reason visibility=$visibility ${controller.diagnosticState()}")
            // End the complete browser gesture before the native overlay can take input.
            if (covered) controller.onCoverClosed()
            chrome.setCovered(covered)
            window.attributes = window.attributes.apply {
                screenBrightness = if (covered) 0f else originalBrightness
            }
            if (running) controller.onResume()
        }
        setContentView(chrome)
        controller.attachHost(chrome.browserHost)
        // Creation can happen while the screen is off; do not leave newly created audio active.
        if (screenInteractive) controller.onPause() else controller.onScreenOff()
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!controller.handleBack()) finish()
            }
        })
        DiagnosticTrace.record("activity created lockTask=${lockTaskMode()}")
    }

    override fun onResume() {
        super.onResume()
        val wasRunning = running
        visibility.resume()
        syncSession(wasRunning)
    }

    /** Sensors listen whenever visible; only an uncovered visible session runs the lesson. */
    private fun syncSession(wasRunning: Boolean) {
        cover.setActive(visibility.canRun)
        if (!wasRunning && running) controller.onResume()
    }

    override fun onPause() {
        visibility.pause()
        cover.setActive(false)
        controller.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        visibility.destroy()
        cover.setActive(false)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        chrome.dispose()
        controller.shutdown()
        window.attributes = window.attributes.apply { screenBrightness = originalBrightness }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        DiagnosticTrace.record("window focus=$hasFocus lifecycle=${lifecycle.currentState} lockTask=${lockTaskMode()}")
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        DiagnosticTrace.touch("activity-dispatch", event)
        val handled = super.dispatchTouchEvent(event)
        DiagnosticTrace.touch("activity-return", event, handled)
        return handled
    }

    private fun lockTaskMode(): Int = getSystemService(ActivityManager::class.java)?.lockTaskModeState ?: -1
}
