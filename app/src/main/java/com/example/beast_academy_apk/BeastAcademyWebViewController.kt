package com.example.beast_academy_apk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.MotionEvent
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "BeastAcademyApp"
private const val POPUP_TIMEOUT_MS = 10000L
private const val AUDIO_STATE_CLEANUP_TIMEOUT_MS = 250L
private const val DEFAULT_START_URL = "https://beastacademy.com/school"


enum class RecoveryReason {
    RENDERER_CRASHED,
    RENDERER_KILLED,
    RENDERER_HUNG,
    MANUAL_RELOAD,
    FALLBACK
}

class BeastAcademyWebViewController(
    private val activity: ComponentActivity,
    private val speechBridge: AndroidSpeechSynthesis,
    private val onProgressChanged: (Int) -> Unit,
    private val onRecoveryStateChanged: (RecoveryState) -> Unit
) {
    sealed class RecoveryState {
        object Healthy : RecoveryState()
        object HungWaiting : RecoveryState()
        object Recovering : RecoveryState()
        data class Failed(val message: String) : RecoveryState()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var host: FrameLayout? = null
    private var currentWebView: WebView? = null
    private val generation = AtomicLong(0)
    
    private val popupWebViews = Collections.newSetFromMap(java.util.IdentityHashMap<WebView, Boolean>())
    
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    
    private var lastSafeUrl: String = DEFAULT_START_URL
    private val recoveryLimiter = RecoveryLimiter()
    
    private var pendingWebViewPause: Runnable? = null
    private var isDisposed = false
    private var isPaused = false
    private var recreateOnResume = false
    private val pauseCompletion = PauseCompletion()
    private var pickerInFlight = false
    private var rendererUnresponsive = false
    
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    var filePickerLauncher: ActivityResultLauncher<Array<String>>? = null

    fun diagnosticState(): String =
        "controller paused=$isPaused pendingPause=${pendingWebViewPause != null} " +
            "recreateOnResume=$recreateOnResume webView=${currentWebView != null} " +
            "customView=${customView != null} generation=${generation.get()}"


    fun attachHost(newHost: FrameLayout) {
        check(!isDisposed) { "Cannot attach a closed browser session" }
        check(host == null || host === newHost) { "A browser session has one stable host" }
        if (host === newHost) return
        host = newHost
        if (currentWebView == null) {
            requestRecovery(RecoveryReason.FALLBACK)
        } else {
            val wv = currentWebView!!
            check(wv.parent == null) { "Detach the previous host explicitly" }
            newHost.addView(wv)
        }
    }

    fun detachHost(oldHost: FrameLayout) {
        if (host === oldHost) {
            (currentWebView as? BeastAcademyWebView)?.cancelActiveTouches("host detached")
            currentWebView?.let(oldHost::removeView)
            host = null
        }
    }

    private fun getDesktopUserAgent(context: Context): String {
        val defaultUA = WebSettings.getDefaultUserAgent(context)
        val chromeToken = Regex("Chrome/([\\d.]+)").find(defaultUA)?.groupValues?.get(1)
            ?: "122.0.0.0"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeToken Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createMainWebView(): WebView {
        val wv = BeastAcademyWebView(activity)
        wv.installInputDiagnostics()
        val gen = generation.incrementAndGet()
        rendererUnresponsive = false
        
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        wv.keepEdgeTouchesInApp()
        if (BuildConfig.DEBUG) {
            wv.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    DiagnosticTrace.record("webview touch down ${diagnosticState()}")
                }
                false
            }
        }
        DiagnosticTrace.record("webview created generation=$gen ${diagnosticState()}")

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.addJavascriptInterface(speechBridge, "AndroidSpeech")
        speechBridge.attachWebView(wv, gen)
        
        if (BuildConfig.DEBUG) {
            wv.addJavascriptInterface(object {
                @JavascriptInterface
                @Suppress("unused")
                fun hangRenderer() {
                    Log.d(TAG, "DEBUG: Hanging renderer for 30s")
                    val end = SystemClock.uptimeMillis() + 30000
                    while (SystemClock.uptimeMillis() < end) {
                        // Blocking renderer thread for testing.
                    }
                }
            }, "AndroidDebug")
        }

        wv.settings.apply {
            userAgentString = getDesktopUserAgent(activity)
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, WebViewScripts.speechPolyfill, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(wv, WebViewScripts.audioLifecycle, setOf("*"))
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (owns(wv, gen)) showCustomView(view, callback) else callback?.onCustomViewHidden()
            }

            override fun onHideCustomView() {
                if (owns(wv, gen)) hideCustomView()
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (owns(view, gen)) {
                    onProgressChanged(newProgress)
                }
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                if (!owns(view, gen) || isPaused) return false
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popup = createPopupWebView(activity, gen)
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val launcher = filePickerLauncher
                if (!owns(webView, gen) || launcher == null || isPaused || pickerInFlight) return false
                completeFilePicker(null)
                this@BeastAcademyWebViewController.filePathCallback = filePathCallback
                val acceptTypes = fileChooserParams?.acceptTypes
                    ?.filter { it.isNotEmpty() }
                    ?.toTypedArray()
                    ?.takeIf { it.isNotEmpty() }
                    ?: arrayOf("*/*")
                return runCatching {
                    pickerInFlight = true
                    launcher.launch(acceptTypes)
                    true
                }.getOrElse {
                    pickerInFlight = false
                    this@BeastAcademyWebViewController.filePathCallback = null
                    filePathCallback?.onReceiveValue(null)
                    true // Cancellation was delivered; the request has been handled.
                }
            }
            
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val m = consoleMessage ?: return true
                if (BuildConfig.DEBUG || m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.d(TAG, "JS [${m.messageLevel()}]: ${m.message()}")
                }
                return true
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (owns(view, gen) && url != null && isSafeUrl(url)) {
                    lastSafeUrl = url
                    DiagnosticTrace.record("navigation generation=$gen url=${sanitizeUrl(url)}")
                }
            }
            
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (owns(view, gen)) onRecoveryStateChanged(RecoveryState.Healthy)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!owns(view, gen)) return
                view?.evaluateJavascript("""
                    (function() {
                      function resume() {
                        if (window.Howler && Howler.ctx && Howler.ctx.state === 'suspended') {
                          try { Howler.ctx.resume(); } catch(e) {}
                        }
                      }
                      if (!window.__baResumeHooked) {
                        window.__baResumeHooked = true;
                        document.addEventListener('touchstart', resume, true);
                        document.addEventListener('mousedown', resume, true);
                      }
                    })();
                """.trimIndent(), null)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                if (!owns(view, gen)) return true
                val didCrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    detail?.didCrash() == true
                } else {
                    false
                }
                val reason = if (didCrash) RecoveryReason.RENDERER_CRASHED else RecoveryReason.RENDERER_KILLED
                logRendererIncident("onRenderProcessGone", view ?: wv, gen, reason, detail)
                requestRecovery(reason, view)
                return true
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val executor = ContextCompat.getMainExecutor(activity)
            wv.setWebViewRenderProcessClient(executor, object : WebViewRenderProcessClient() {
                override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) {
                    onRendererUnresponsive(view, gen)
                }

                override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) {
                    onRendererResponsive(view, gen)
                }
            })
        }

        return wv
    }

    private fun isSafeUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme == "https" && (uri.host == "beastacademy.com" || uri.host?.endsWith(".beastacademy.com") == true)
    }

    private fun createPopupWebView(context: Context, ownerGeneration: Long): WebView {
        val popup = WebView(context)
        popupWebViews.add(popup)
        var handled = false

        fun handleInMainWebView(uri: Uri): Boolean {
            if (handled) return true
            handled = true
            if (!isDisposed && ownerGeneration == generation.get() && !isPaused) {
                currentWebView?.loadUrl(uri.toString())
            }
            destroyPopupWebView(popup)
            return true
        }

        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return handleInMainWebView(uri)
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Log.w(TAG, "Popup renderer gone")
                destroyPopupWebView(view ?: popup)
                return true
            }
        }

        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) {
                destroyPopupWebView(window ?: popup)
            }
        }

        mainHandler.postDelayed({
            if (!handled) destroyPopupWebView(popup)
        }, POPUP_TIMEOUT_MS)

        return popup
    }

    private fun destroyPopupWebView(wv: WebView?) {
        if (wv == null || !popupWebViews.remove(wv)) return
        runCatching {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.removeAllViews()
            wv.destroy()
        }
    }

    fun requestRecovery(reason: RecoveryReason, failedView: WebView? = null) {
        DiagnosticTrace.record("recovery requested reason=$reason ${diagnosticState()}")
        if (isDisposed) return
        if (failedView != null && failedView !== currentWebView) {
            // Its teardown already ran when the generation was replaced. A late callback
            // must not touch the new session's picker, full-screen view, or speech binding.
            return
        }

        // Never spin up a fresh renderer while the app is backgrounded. Besides wasting
        // resources, a newly loaded page could start audio before it receives a pause event.
        if (isPaused) {
            destroyMainWebView(failedView ?: currentWebView)
            recreateOnResume = true
            return
        }
        
        if (reason != RecoveryReason.MANUAL_RELOAD && !recoveryLimiter.recordAndCheck()) {
            onRecoveryStateChanged(RecoveryState.Failed("Beast Academy keeps stopping."))
            destroyMainWebView(currentWebView)
            return
        }

        cancelPendingWebViewPause()
        pauseCompletion.invalidate()
        onRecoveryStateChanged(RecoveryState.Recovering)
        
        val oldView = currentWebView
        popupWebViews.toList().forEach(::destroyPopupWebView)
        destroyMainWebView(oldView)
        
        val newView = createMainWebView()
        currentWebView = newView
        host?.addView(newView)
        newView.loadUrl(lastSafeUrl)
    }

    private fun destroyMainWebView(wv: WebView?) {
        if (wv == null) return
        
        if (wv === currentWebView) {
            currentWebView = null
        }
        
        (wv as? BeastAcademyWebView)?.cancelActiveTouches("session destroyed")
        hideCustomView()
        completeFilePicker(null)
        speechBridge.detachWebView(wv)
        
        runCatching {
            wv.stopLoading()
            wv.removeJavascriptInterface("AndroidSpeech")
            if (BuildConfig.DEBUG) wv.removeJavascriptInterface("AndroidDebug")
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.removeAllViews()
            wv.destroy()
        }.onFailure {
            Log.e(TAG, "Failed to destroy main WebView", it)
        }
    }

    private fun owns(view: WebView?, gen: Long): Boolean =
        !isDisposed && view != null && view === currentWebView && gen == generation.get()

    fun onFilePickerResult(uris: Array<Uri>?) {
        pickerInFlight = false
        completeFilePicker(uris)
    }

    private fun completeFilePicker(uris: Array<Uri>?) {
        val callback = filePathCallback
        filePathCallback = null
        callback?.onReceiveValue(uris)
    }

    private fun onRendererUnresponsive(view: WebView, gen: Long) {
        if (!owns(view, gen)) return
        logRendererIncident("onRendererUnresponsive", view, gen, RecoveryReason.RENDERER_HUNG, null)
        // A delayed renderer can recover. Preserve the lesson and evidence until the user
        // explicitly chooses Reload; only an actual renderer exit warrants auto recovery.
        if (!rendererUnresponsive) DiagnosticTrace.preserveFailure("renderer unresponsive generation=$gen")
        rendererUnresponsive = true
        onRecoveryStateChanged(RecoveryState.HungWaiting)
    }

    private fun onRendererResponsive(view: WebView, gen: Long) {
        if (!owns(view, gen)) return
        rendererUnresponsive = false
        Log.i(TAG, "Renderer became responsive again (generation $gen)")
        onRecoveryStateChanged(RecoveryState.Healthy)
    }

    private fun logRendererIncident(
        event: String,
        view: WebView,
        gen: Long,
        reason: RecoveryReason,
        detail: RenderProcessGoneDetail?
    ) {
        val pkg = WebViewCompat.getCurrentWebViewPackage(activity)
        val appVersion = activity.packageManager.getPackageInfo(activity.packageName, 0)
        
        Log.e(TAG, "Renderer Incident: $event")
        Log.e(TAG, "  App: ${activity.packageName} ${appVersion.versionName} (${PackageInfoCompat.getLongVersionCode(appVersion)})")
        Log.e(TAG, "  Android: SDK ${Build.VERSION.SDK_INT}, Model ${Build.MODEL}")
        Log.e(TAG, "  Reason: $reason")
        Log.e(TAG, "  Generation: $gen")
        Log.e(TAG, "  URL: ${sanitizeUrl(view.url)}")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && detail != null) {
            Log.e(TAG, "  Did Crash: ${detail.didCrash()}")
            Log.e(TAG, "  Exit Priority: ${detail.rendererPriorityAtExit()}")
        }
        
        Log.e(TAG, "  WebView Provider: ${pkg?.packageName} (${pkg?.versionName})")
        Log.e(TAG, "  Activity State: ${activity.lifecycle.currentState}")
        Log.e(TAG, "  Popups: ${popupWebViews.size}")
        Log.e(TAG, "  Custom View: ${customView != null}")
    }

    private fun sanitizeUrl(url: String?): String {
        if (url == null) return "null"
        return runCatching {
            val uri = Uri.parse(url)
            "${uri.scheme}://${uri.host}${uri.path}"
        }.getOrDefault("invalid-url")
    }

    private fun showCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        DiagnosticTrace.record("custom view show requested ${diagnosticState()}")
        if (customView != null) {
            hideCustomView()
        }
        if (view == null) {
            callback?.onCustomViewHidden()
            return
        }
        
        if (activity.isFinishing || activity.isDestroyed) {
            callback?.onCustomViewHidden()
            return
        }

        customView = view
        customViewCallback = callback

        val decor = activity.window.decorView as ViewGroup
        (view.parent as? ViewGroup)?.removeView(view)
        decor.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        view.keepEdgeTouchesInApp()
        activity.window.enterImmersiveMode()
        DiagnosticTrace.record("custom view shown ${diagnosticState()}")
    }

    fun hideCustomView() {
        val view = customView ?: return
        DiagnosticTrace.record("custom view hide ${diagnosticState()}")
        val decor = activity.window.decorView as ViewGroup
        decor.removeView(view)
        customView = null
        
        val callback = customViewCallback
        customViewCallback = null
        runCatching { callback?.onCustomViewHidden() }

        activity.window.enterImmersiveMode()
    }

    fun handleBack(): Boolean {
        if (customView != null) {
            hideCustomView()
            return true
        }
        if (currentWebView?.canGoBack() == true) {
            currentWebView?.goBack()
            return true
        }
        return false
    }

    fun onPause() {
        DiagnosticTrace.record("controller onPause ${diagnosticState()}")
        pauseAudio(forScreenOff = false)
    }

    fun onScreenOff() {
        DiagnosticTrace.record("controller onScreenOff ${diagnosticState()}")
        pauseAudio(forScreenOff = true)
    }

    fun onCoverClosed() {
        DiagnosticTrace.record("controller onCoverClosed ${diagnosticState()}")
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.window.decorView.keepScreenOn = false
        currentWebView?.keepScreenOn = false
        popupWebViews.forEach { it.keepScreenOn = false }
        customView?.keepScreenOn = false
        pauseAudio(forScreenOff = true)
    }

    private fun pauseAudio(forScreenOff: Boolean) {
        if (isDisposed) return

        (currentWebView as? BeastAcademyWebView)?.cancelActiveTouches("pause screenOff=$forScreenOff")

        hideCustomView()
        speechBridge.cancel()

        val wv = currentWebView
        val audioMuted = setAllWebViewAudioMuted(muted = true)
        DiagnosticTrace.record("pauseAudio screenOff=$forScreenOff audioMuted=$audioMuted ${diagnosticState()}")

        // MUTE_AUDIO is the only WebView API that synchronously silences every audio source.
        // If an old provider lacks it, destroying the renderer is the only reliable fallback
        // for the explicit screen-off requirement.
        if (forScreenOff && !audioMuted) {
            Log.w(TAG, "WebView audio muting is unsupported; destroying WebViews on screen-off")
            cancelPendingWebViewPause()
            recreateOnResume = wv != null
            destroyMainWebView(wv)
            popupWebViews.toList().forEach { destroyPopupWebView(it) }
            isPaused = true
            pauseCompletion.invalidate()
            return
        }

        if (isPaused) return
        isPaused = true
        val generation = pauseCompletion.begin()
        if (wv == null) return

        val timeout = Runnable {
            pendingWebViewPause = null
            finishWebViewPause(wv, generation)
        }
        pendingWebViewPause = timeout
        mainHandler.postDelayed(timeout, AUDIO_STATE_CLEANUP_TIMEOUT_MS)

        runCatching {
            wv.evaluateJavascript(WebViewScripts.pauseAudio) {
                finishWebViewPause(wv, generation)
            }
        }.onFailure {
            Log.w(TAG, "Unable to clean up WebView playback state", it)
            finishWebViewPause(wv, generation)
        }
    }

    private fun finishWebViewPause(wv: WebView, generation: Long) {
        if (!isPaused || currentWebView !== wv || !pauseCompletion.complete(generation)) return
        cancelPendingWebViewPause()
        DiagnosticTrace.record("webview pausing generation=$generation ${diagnosticState()}")
        runCatching { wv.onPause() }
        DiagnosticTrace.record("webview paused generation=$generation")
        popupWebViews.toList().forEach { popup -> runCatching { popup.onPause() } }
    }

    private fun cancelPendingWebViewPause() {
        pendingWebViewPause?.let(mainHandler::removeCallbacks)
        pendingWebViewPause = null
    }

    private fun setAllWebViewAudioMuted(muted: Boolean): Boolean {
        val webViews = buildList {
            currentWebView?.let(::add)
            addAll(popupWebViews.toList())
        }
        val results = webViews.map { setWebViewAudioMuted(it, muted) }
        return results.all { it }
    }

    private fun setWebViewAudioMuted(wv: WebView, muted: Boolean): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) return false
        return runCatching {
            WebViewCompat.setAudioMuted(wv, muted)
            true
        }.onFailure {
            Log.w(TAG, "Unable to ${if (muted) "mute" else "unmute"} WebView audio", it)
        }.getOrDefault(false)
    }

    fun onResume() {
        DiagnosticTrace.record("controller onResume before ${diagnosticState()}")
        if (isDisposed) return
        isPaused = false
        pauseCompletion.invalidate()
        cancelPendingWebViewPause()

        activity.window.enterImmersiveMode()

        if (recreateOnResume) {
            recreateOnResume = false
            requestRecovery(RecoveryReason.MANUAL_RELOAD)
            return
        }

        val wv = currentWebView ?: return
        wv.onResume()
        DiagnosticTrace.record("webview resumed ${diagnosticState()}")
        popupWebViews.toList().forEach { popup ->
            runCatching { popup.onResume() }
        }
        setAllWebViewAudioMuted(muted = false)
    }

    fun shutdown() {
        if (isDisposed) return
        isDisposed = true
        pauseCompletion.invalidate()
        cancelPendingWebViewPause()
        destroyMainWebView(currentWebView)
        val popups = popupWebViews.toList()
        popups.forEach { destroyPopupWebView(it) }
        speechBridge.shutdown()
    }
}

private fun Window.enterImmersiveMode() {
    WindowCompat.setDecorFitsSystemWindows(this, false)
    WindowCompat.getInsetsController(this, decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

private fun View.keepEdgeTouchesInApp() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

    fun updateExclusionRects() {
        if (width > 0 && height > 0) {
            systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
        }
    }

    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        updateExclusionRects()
    }
    post { updateExclusionRects() }
}
