package com.example.beast_academy_apk

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Passive native siblings of the browser; only visible controls and the cover take input. */
internal class BrowserChrome(context: Context, onReload: () -> Unit) : FrameLayout(context) {
    val browserHost = InputTraceHost(context)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val recovery = FrameLayout(context)
    private val recoveryText = TextView(context)
    private val spinner = ProgressBar(context)
    private val warning = TextView(context)
    private val cover = View(context)
    var recoveryState: BeastAcademyWebViewController.RecoveryState = BeastAcademyWebViewController.RecoveryState.Healthy
        private set
    private var loadProgress = 0
    private val hideWarning = Runnable { warning.visibility = GONE }

    init {
        addView(browserHost, LayoutParams(-1, -1))
        progress.max = 100
        progress.visibility = GONE
        addView(progress, LayoutParams(-1, dp(4), Gravity.TOP))

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(238, 238, 238))
            // Keep card touches from falling through to the lesson.
            isClickable = true
        }
        recoveryText.apply { textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.BLACK) }
        card.addView(recoveryText, LinearLayout.LayoutParams(-1, -2))
        card.addView(spinner, LinearLayout.LayoutParams(dp(32), dp(32)).apply { topMargin = dp(16) })
        card.addView(Button(context).apply {
            setText(R.string.reload_now)
            setOnClickListener { onReload() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24) })
        recovery.addView(card, LayoutParams(dp(320), -2, Gravity.CENTER))
        recovery.visibility = GONE
        addView(recovery, LayoutParams(-1, -1))

        warning.apply {
            setText(R.string.tts_unavailable)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xB3000000.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
            gravity = Gravity.CENTER
            visibility = GONE
        }
        addView(warning, LayoutParams(-1, -2, Gravity.TOP).apply {
            topMargin = dp(64); marginStart = dp(24); marginEnd = dp(24)
        })
        cover.apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            visibility = GONE
        }
        addView(cover, LayoutParams(-1, -1))
    }

    fun setProgress(value: Int) {
        loadProgress = value
        progress.progress = value
        updateProgress()
    }

    fun setRecoveryState(state: BeastAcademyWebViewController.RecoveryState) {
        recoveryState = state
        recovery.visibility = if (state == BeastAcademyWebViewController.RecoveryState.Healthy) GONE else VISIBLE
        spinner.visibility = if (state is BeastAcademyWebViewController.RecoveryState.Failed) GONE else VISIBLE
        recoveryText.text = when (state) {
            BeastAcademyWebViewController.RecoveryState.Healthy -> ""
            BeastAcademyWebViewController.RecoveryState.HungWaiting -> context.getString(R.string.renderer_waiting)
            BeastAcademyWebViewController.RecoveryState.Recovering -> context.getString(R.string.recovering)
            is BeastAcademyWebViewController.RecoveryState.Failed -> state.message
        }
        updateProgress()
    }

    private fun updateProgress() {
        progress.visibility = if (loadProgress in 1..99 &&
            recoveryState == BeastAcademyWebViewController.RecoveryState.Healthy) VISIBLE else GONE
    }

    fun setCovered(covered: Boolean) {
        cover.visibility = if (covered) VISIBLE else GONE
        // A covered lesson must also be absent from the accessibility focus tree.
        browserHost.importantForAccessibility = if (covered) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    fun showTtsWarning() {
        removeCallbacks(hideWarning)
        warning.visibility = VISIBLE
        postDelayed(hideWarning, 8000L)
    }

    fun dispose() { removeCallbacks(hideWarning) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
