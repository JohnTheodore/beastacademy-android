package com.example.beast_academy_apk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
private const val RECOVERY_GRACE_PERIOD_MS = 2000L
private const val RECOVERY_FALLBACK_MS = 2000L
private const val POPUP_TIMEOUT_MS = 10000L
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
    
    private var pendingRecovery: Runnable? = null
    private var isDisposed = false
    
    var filePathCallback: ValueCallback<Array<Uri>>? = null
    var filePickerLauncher: ActivityResultLauncher<Array<String>>? = null

    private val speechPolyfill = """
        (function() {
          var _pending = {};
          var _seq = 0;

          function waitForBridge(callback, retries) {
            if (window.AndroidSpeech && window.AndroidSpeech.isReady()) {
              callback(true);
            } else if (retries <= 0) {
              callback(false);
            } else {
              setTimeout(function() { waitForBridge(callback, retries - 1); }, 300);
            }
          }

          window.__androidTtsStart = function(id) {
            var u = _pending[id];
            if (u && u.onstart) { try { u.onstart({}); } catch(e) {} }
          };
          window.__androidTtsDone = function(id) {
            var u = _pending[id];
            delete _pending[id];
            window.speechSynthesis.speaking = Object.keys(_pending).length > 0;
            if (u && u.onend) { try { u.onend({}); } catch(e) {} }
          };
          window.__androidTtsError = function(id) {
            var u = _pending[id];
            delete _pending[id];
            window.speechSynthesis.speaking = Object.keys(_pending).length > 0;
            if (u && u.onerror) { try { u.onerror({ error: 'synthesis-failed' }); } catch(e) {} }
          };

          window.SpeechSynthesisUtterance = function(text) {
            this.text = text || '';
            this.lang = 'en-US';
            this.rate = 1; this.pitch = 1; this.volume = 1;
            this.onend = null; this.onstart = null; this.onerror = null;
          };

          var _listeners = {};

          window.speechSynthesis = {
            speaking: false,
            pending: false,
            paused: false,

            addEventListener: function(type, fn) {
              if (!_listeners[type]) _listeners[type] = [];
              _listeners[type].push(fn);
            },
            removeEventListener: function(type, fn) {
              if (!_listeners[type]) return;
              _listeners[type] = _listeners[type].filter(function(f) { return f !== fn; });
            },
            dispatchEvent: function(event) {
              var fns = _listeners[event.type] || [];
              fns.forEach(function(fn) { try { fn(event); } catch(e) {} });
              return true;
            },

            getVoices: function() {
              return [{ name: 'Android TTS', lang: 'en-US', default: true, localService: true, voiceURI: 'Android TTS' }];
            },
            speak: function(utterance) {
              var self = this;
              var id = 'utt_' + (++_seq);
              _pending[id] = utterance;
              waitForBridge(function(ok) {
                if (!ok) {
                  delete _pending[id];
                  self.speaking = Object.keys(_pending).length > 0;
                  if (utterance.onerror) { try { utterance.onerror({ error: 'synthesis-unavailable' }); } catch(e) {} }
                  return;
                }
                var queued = window.AndroidSpeech.speak(id, utterance.text || '');
                if (queued) {
                  self.speaking = true;
                } else {
                  delete _pending[id];
                  self.speaking = Object.keys(_pending).length > 0;
                  if (utterance.onerror) { try { utterance.onerror({ error: 'synthesis-unavailable' }); } catch(e) {} }
                }
              }, 20);
            },
            cancel: function() {
              _pending = {};
              this.speaking = false;
              if (window.AndroidSpeech) window.AndroidSpeech.cancel();
            },
            pause: function() {},
            resume: function() {}
          };

          setTimeout(function() {
            var event = new Event('voiceschanged');
            window.speechSynthesis.dispatchEvent(event);
          }, 500);
        })();
    """.trimIndent()

    fun attachHost(newHost: FrameLayout) {
        host = newHost
        if (currentWebView == null) {
            requestRecovery(RecoveryReason.FALLBACK)
        } else {
            val wv = currentWebView!!
            (wv.parent as? ViewGroup)?.removeView(wv)
            newHost.addView(wv)
        }
    }

    fun detachHost(oldHost: FrameLayout) {
        if (host === oldHost) {
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
        val wv = WebView(activity)
        val gen = generation.incrementAndGet()
        
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        wv.keepEdgeTouchesInApp()

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
            WebViewCompat.addDocumentStartJavaScript(wv, speechPolyfill, setOf("*"))
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                showCustomView(view, callback)
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (view === currentWebView) {
                    onProgressChanged(newProgress)
                }
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popup = createPopupWebView(activity)
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@BeastAcademyWebViewController.filePathCallback?.onReceiveValue(null)
                this@BeastAcademyWebViewController.filePathCallback = filePathCallback
                val acceptTypes = fileChooserParams?.acceptTypes
                    ?.filter { it.isNotEmpty() }
                    ?.toTypedArray()
                    ?.takeIf { it.isNotEmpty() }
                    ?: arrayOf("*/*")
                return runCatching {
                    filePickerLauncher?.launch(acceptTypes) ?: false
                    true
                }.getOrElse {
                    this@BeastAcademyWebViewController.filePathCallback = null
                    filePathCallback?.onReceiveValue(null)
                    false
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
                if (url != null && isSafeUrl(url)) {
                    lastSafeUrl = url
                }
            }
            
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                onRecoveryStateChanged(RecoveryState.Healthy)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
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
                        document.addEventListener('visibilitychange', function() {
                          if (!document.hidden) resume();
                        });
                      }
                      resume();
                    })();
                """.trimIndent(), null)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (isSafeUrl(url)) return false
                
                runCatching {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                return true
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
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
                    onRendererUnresponsive(view, renderer, gen)
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

    private fun createPopupWebView(context: Context): WebView {
        val popup = WebView(context)
        popupWebViews.add(popup)
        var handled = false

        fun handleExternalUri(uri: Uri): Boolean {
            if (handled) return true
            handled = true
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            destroyPopupWebView(popup)
            return true
        }

        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return handleExternalUri(uri)
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
        if (wv == null) return
        popupWebViews.remove(wv)
        runCatching {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.removeAllViews()
            wv.destroy()
        }
    }

    fun requestRecovery(reason: RecoveryReason, failedView: WebView? = null) {
        if (isDisposed) return
        if (failedView != null && failedView !== currentWebView) {
            destroyMainWebView(failedView)
            return
        }
        
        if (pendingRecovery != null && reason != RecoveryReason.RENDERER_CRASHED && reason != RecoveryReason.RENDERER_KILLED) return
        
        if (reason != RecoveryReason.MANUAL_RELOAD && !recoveryLimiter.recordAndCheck()) {
            onRecoveryStateChanged(RecoveryState.Failed("Beast Academy keeps stopping."))
            destroyMainWebView(currentWebView)
            return
        }

        onRecoveryStateChanged(RecoveryState.Recovering)
        
        val oldView = currentWebView
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
        
        cancelRendererWatchdog()
        hideCustomView()
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
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

    private fun onRendererUnresponsive(view: WebView, renderer: WebViewRenderProcess?, gen: Long) {
        if (view !== currentWebView) return
        logRendererIncident("onRendererUnresponsive", view, gen, RecoveryReason.RENDERER_HUNG, null)
        
        if (pendingRecovery != null) return
        
        onRecoveryStateChanged(RecoveryState.HungWaiting)
        
        val watchdog = object : Runnable {
            override fun run() {
                if (pendingRecovery !== this) return
                pendingRecovery = null
                
                Log.w(TAG, "Renderer hung grace period expired, terminating...")
                val terminated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    renderer?.terminate() == true
                } else false
                
                if (!terminated) {
                    requestRecovery(RecoveryReason.RENDERER_HUNG, view)
                } else {
                    mainHandler.postDelayed({
                        if (currentWebView === view) {
                            Log.w(TAG, "Renderer termination fallback triggered")
                            requestRecovery(RecoveryReason.FALLBACK, view)
                        }
                    }, RECOVERY_FALLBACK_MS)
                }
            }
        }
        pendingRecovery = watchdog
        mainHandler.postDelayed(watchdog, RECOVERY_GRACE_PERIOD_MS)
    }

    private fun onRendererResponsive(view: WebView, gen: Long) {
        if (view !== currentWebView) return
        Log.i(TAG, "Renderer became responsive again (generation $gen)")
        cancelRendererWatchdog()
        onRecoveryStateChanged(RecoveryState.Healthy)
    }

    private fun cancelRendererWatchdog() {
        pendingRecovery?.let { mainHandler.removeCallbacks(it) }
        pendingRecovery = null
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
    }

    fun hideCustomView() {
        val view = customView ?: return
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
        hideCustomView()
        speechBridge.cancel()
        
        val wv = currentWebView ?: return
        wv.evaluateJavascript("""
            try {
              if (window.speechSynthesis) window.speechSynthesis.cancel();
              document.querySelectorAll('audio, video').forEach(function(el) { el.pause(); });
              if (window.Howler) {
                Howler.stop();
                if (Howler.ctx && Howler.ctx.state === 'running') Howler.ctx.suspend();
              }
            } catch(e) {}
        """.trimIndent(), null)
        
        wv.onPause()
        wv.pauseTimers()
    }

    fun onResume() {
        activity.window.enterImmersiveMode()
        val wv = currentWebView ?: return
        wv.resumeTimers()
        wv.onResume()
        
        wv.evaluateJavascript("""
            try {
              if (window.Howler && Howler.ctx && Howler.ctx.state === 'suspended') Howler.ctx.resume();
            } catch(e) {}
        """.trimIndent(), null)
    }

    fun shutdown() {
        isDisposed = true
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
