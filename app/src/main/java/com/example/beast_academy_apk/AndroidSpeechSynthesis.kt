package com.example.beast_academy_apk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

private const val TAG = "BeastAcademyApp"

class AndroidSpeechSynthesis(
    context: Context,
    private val onTtsUnavailable: () -> Unit
) : TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, this)

    @Volatile private var isReady = false
    
    private data class WebViewBinding(
        val generation: Long,
        val webView: WeakReference<WebView>
    )

    private val generationCounter = AtomicLong(0L)
    @Volatile private var binding: WebViewBinding? = null
    private val utteranceGenerations = ConcurrentHashMap<String, Long>()

    fun attachWebView(wv: WebView, gen: Long) {
        binding = WebViewBinding(gen, WeakReference(wv))
        Log.d(TAG, "TTS attached to WebView generation $gen")
    }

    fun detachWebView(wv: WebView) {
        if (binding?.webView?.get() === wv) {
            binding = null
            utteranceGenerations.clear()
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS Initialization failed (status=$status)")
            mainHandler.post { onTtsUnavailable() }
            return
        }
        val langResult = tts.setLanguage(Locale.US)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "TTS language not available (result=$langResult)")
            mainHandler.post { onTtsUnavailable() }
            return
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                dispatchToJs("window.__androidTtsStart", utteranceId, false)
            }

            override fun onDone(utteranceId: String?) {
                dispatchToJs("window.__androidTtsDone", utteranceId, true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                dispatchToJs("window.__androidTtsError", utteranceId, true)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                dispatchToJs("window.__androidTtsError", utteranceId, true)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                dispatchToJs("window.__androidTtsDone", utteranceId, true)
            }
        })
        isReady = true
        Log.d(TAG, "TTS initialized successfully")
    }

    private fun dispatchToJs(functionName: String, utteranceId: String?, terminal: Boolean) {
        val id = utteranceId ?: return
        val expectedGeneration = utteranceGenerations[id] ?: return

        if (terminal) {
            utteranceGenerations.remove(id)
        }

        val quotedId = JSONObject.quote(id)

        mainHandler.post {
            val current = binding ?: return@post
            if (current.generation != expectedGeneration) {
                return@post
            }

            val webView = current.webView.get() ?: return@post

            runCatching {
                webView.evaluateJavascript(
                    "if (typeof $functionName === 'function') { $functionName($quotedId); }",
                    null
                )
            }.onFailure {
                Log.w(TAG, "Dropping TTS callback", it)
            }
        }
    }

    @JavascriptInterface
    fun speak(utteranceId: String?, text: String?): Boolean {
        if (!isReady || text.isNullOrEmpty() || utteranceId.isNullOrEmpty()) {
            Log.e(TAG, "TTS speak rejected (ready=$isReady id=$utteranceId len=${text?.length})")
            return false
        }
        
        val currentBinding = binding ?: return false
        utteranceGenerations[utteranceId] = currentBinding.generation
        
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            utteranceGenerations.remove(utteranceId)
        }
        return result == TextToSpeech.SUCCESS
    }

    @JavascriptInterface
    fun cancel() {
        utteranceGenerations.clear()
        if (isReady) tts.stop()
    }

    @JavascriptInterface
    fun isReady(): Boolean = isReady

    fun shutdown() {
        isReady = false
        binding = null
        utteranceGenerations.clear()
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }
}
