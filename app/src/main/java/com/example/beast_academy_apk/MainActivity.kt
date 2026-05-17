package com.example.beast_academy_apk

import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.beast_academy_apk.ui.theme.BeastacademyapkTheme
import kotlinx.coroutines.delay

private const val TAG = "BeastAcademyApp"
private const val START_URL = "https://beastacademy.com/school"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        (getSystemService(AUDIO_SERVICE) as? AudioManager)?.let { audioManager ->
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2,
                    0
                )
            }
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        enableEdgeToEdge()
        setContent {
            BeastacademyapkTheme {
                BeastAcademyWebView(START_URL)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.navigationBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BeastAcademyWebView(url: String) {
    val activity = LocalContext.current as ComponentActivity
    val window = activity.window
    val lifecycle = activity.lifecycle

    var showTtsWarning by remember { mutableStateOf(false) }
    var customView: View? by remember { mutableStateOf(null) }
    var customViewCallback: WebChromeClient.CustomViewCallback? by remember { mutableStateOf(null) }
    var webView: WebView? by remember { mutableStateOf(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var loadProgress by remember { mutableIntStateOf(0) }

    val filePathCallbackHolder = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        filePathCallbackHolder.value?.onReceiveValue(uris.toTypedArray())
        filePathCallbackHolder.value = null
    }

    val speechBridge = remember {
        AndroidSpeechSynthesis(context = activity) {
            showTtsWarning = true
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            val wv = webView ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    wv.evaluateJavascript(
                        """
                        try {
                          Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
                          Object.defineProperty(document, 'hidden', { value: true, configurable: true });
                          document.dispatchEvent(new Event('visibilitychange'));
                        } catch(e) {}
                        """.trimIndent(),
                        null
                    )
                    wv.onPause()
                    wv.pauseTimers()
                }
                Lifecycle.Event.ON_RESUME -> {
                    wv.resumeTimers()
                    wv.onResume()
                    wv.evaluateJavascript(
                        """
                        try {
                          Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
                          Object.defineProperty(document, 'hidden', { value: false, configurable: true });
                          document.dispatchEvent(new Event('visibilitychange'));
                          if (window.Howler && Howler.ctx && Howler.ctx.state === 'suspended') Howler.ctx.resume();
                        } catch(e) {}
                        """.trimIndent(),
                        null
                    )
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            speechBridge.shutdown()
            webView?.destroy()
        }
    }

    if (showTtsWarning) {
        LaunchedEffect(Unit) {
            delay(8000L)
            showTtsWarning = false
        }
    }

    val speechPolyfill = """
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

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { factoryContext ->
                WebView(factoryContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    val webViewInstance = this
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(webViewInstance, true)
                    }

                    addJavascriptInterface(speechBridge, "AndroidSpeech")
                    speechBridge.attachWebView(this)

                    settings.apply {
                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
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
                        WebViewCompat.addDocumentStartJavaScript(this, speechPolyfill, setOf("*"))
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                            if (customView != null) {
                                onHideCustomView()
                            }
                            if (view == null) {
                                callback?.onCustomViewHidden()
                                return
                            }
                            customView = view
                            customViewCallback = callback

                            val decor = window.decorView as ViewGroup
                            decor.addView(
                                view,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                                )
                            )

                            WindowCompat.getInsetsController(window, window.decorView).apply {
                                hide(WindowInsetsCompat.Type.systemBars())
                                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            }
                        }

                        override fun onHideCustomView() {
                            val view = customView ?: return
                            val decor = window.decorView as ViewGroup
                            decor.removeView(view)
                            customView = null
                            customViewCallback?.onCustomViewHidden()
                            customViewCallback = null

                            WindowCompat.getInsetsController(window, window.decorView).apply {
                                hide(WindowInsetsCompat.Type.navigationBars())
                                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            }
                        }

                        override fun onPermissionRequest(request: PermissionRequest?) {
                            request?.grant(request.resources)
                        }

                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            loadProgress = newProgress
                        }

                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            val m = consoleMessage ?: return true
                            if (BuildConfig.DEBUG || m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                Log.d(TAG, "JS [${m.messageLevel()}]: ${m.message()}")
                            }
                            return true
                        }

                        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                            Log.d(TAG, "JS alert: $message")
                            result?.confirm()
                            return true
                        }

                        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                            Log.d(TAG, "JS confirm (auto-cancel): $message")
                            result?.cancel()
                            return true
                        }

                        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                            Log.d(TAG, "JS prompt (auto-cancel): $message")
                            result?.cancel()
                            return true
                        }

                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                            val ctx = view?.context ?: return false
                            val stub = WebView(ctx).apply { settings.javaScriptEnabled = true }
                            stub.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                                    req?.url?.let { uri ->
                                        runCatching {
                                            activity.startActivity(
                                                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                    }
                                    v?.destroy()
                                    return true
                                }
                            }
                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                            transport?.webView = stub
                            resultMsg?.sendToTarget()
                            return true
                        }

                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            filePathCallbackHolder.value?.onReceiveValue(null)
                            filePathCallbackHolder.value = filePathCallback
                            val acceptTypes = fileChooserParams?.acceptTypes
                                ?.filter { it.isNotEmpty() }
                                ?.toTypedArray()
                                ?.takeIf { it.isNotEmpty() }
                                ?: arrayOf("*/*")
                            return runCatching {
                                filePickerLauncher.launch(acceptTypes)
                                true
                            }.getOrElse {
                                filePathCallbackHolder.value = null
                                filePathCallback?.onReceiveValue(null)
                                false
                            }
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            canGoBack = view?.canGoBack() ?: false
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

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

                        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                            Log.e(TAG, "Renderer gone (crashed=${detail?.didCrash()}, priority=${detail?.rendererPriorityAtExit()})")
                            view?.let { wv ->
                                (wv.parent as? ViewGroup)?.removeView(wv)
                                wv.destroy()
                            }
                            if (webView === view) webView = null
                            activity.recreate()
                            return true
                        }
                    }

                    loadUrl(url)
                    webView = this
                }
            }
        )

        if (loadProgress in 1..99) {
            LinearProgressIndicator(
                progress = { loadProgress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (showTtsWarning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.7f)
                    ),
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Text(
                        text = "No TTS engine found. Go to Settings → Accessibility → Text-to-speech to install one.",
                        color = Color.White,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    BackHandler(enabled = customView != null || canGoBack) {
        if (customView != null) {
            webView?.webChromeClient?.onHideCustomView()
        } else {
            webView?.goBack()
        }
    }
}
