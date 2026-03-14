package com.example.beast_academy_apk

import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.beast_academy_apk.ui.theme.BeastacademyapkTheme

private const val TAG = "BeastAcademyApp"
private const val START_URL = "https://beastacademy.com/school"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        (getSystemService(AUDIO_SERVICE) as? AudioManager)?.let { audioManager ->
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2,
                    0
                )
            }
        }

        enableEdgeToEdge()
        setContent {
            BeastacademyapkTheme {
                BeastAcademyWebView(START_URL)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BeastAcademyWebView(url: String) {
    val context = LocalContext.current
    val speechBridge = remember { AndroidSpeechSynthesis(context) }

    DisposableEffect(Unit) {
        onDispose { speechBridge.shutdown() }
    }

    var webView: WebView? by remember { mutableStateOf(null) }
    var canGoBack by remember { mutableStateOf(false) }

    val speechPolyfill = """
        (function() {
            window.SpeechSynthesisUtterance = function(text) {
                this.text = text || '';
                this.lang = 'en-US';
                this.rate = 1.0;
                this.pitch = 1.0;
                this.volume = 1.0;
                this.onend = null;
                this.onstart = null;
                this.onerror = null;
            };
            
            window.speechSynthesis = {
                speaking: false,
                speak: function(utterance) {
                    this.speaking = true;
                    if (utterance.onstart) utterance.onstart();
                    if (window.AndroidSpeech && window.AndroidSpeech.isReady()) {
                        window.AndroidSpeech.speak(utterance.text);
                    }
                    if (utterance.onend) setTimeout(() => utterance.onend(), 100);
                    this.speaking = false;
                },
                cancel: function() {
                    if (window.AndroidSpeech) window.AndroidSpeech.cancel();
                    this.speaking = false;
                },
                getVoices: () => [{ name: 'Android Voice', lang: 'en-US', default: true }],
                onvoiceschanged: null,
                dispatchEvent: function(event) {
                    if (event.type === 'voiceschanged' && this.onvoiceschanged) this.onvoiceschanged(event);
                }
            };
            
            setTimeout(() => {
                window.speechSynthesis.dispatchEvent(new Event('voiceschanged'));
            }, 100);
        })();
    """.trimIndent()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            factory = { factoryContext ->
                WebView(factoryContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)

                    val currentWebView = this
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(currentWebView, true)
                    }

                    addJavascriptInterface(speechBridge, "AndroidSpeech")

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
                    }

                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        WebViewCompat.addDocumentStartJavaScript(this, speechPolyfill, setOf("*"))
                    }
                    
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest?) {
                            request?.grant(request.resources)
                        }
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            Log.d(TAG, "JS Console: ${consoleMessage?.message()}")
                            return true
                        }
                    }
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            canGoBack = view?.canGoBack() ?: false
                            view?.evaluateJavascript("""
                                (function() {
                                    if (window.Howler && Howler.ctx && Howler.ctx.state === 'suspended') {
                                        const resume = () => Howler.ctx.resume();
                                        document.addEventListener('touchstart', resume, { once: true });
                                        document.addEventListener('mousedown', resume, { once: true });
                                        resume();
                                    }
                                })();
                            """.trimIndent(), null)
                        }
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
                    }
                    
                    loadUrl(url)
                    webView = this
                }
            }
        )
    }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }
}