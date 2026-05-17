package com.example.beast_academy_apk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.util.Locale

private const val TAG = "BeastAcademyApp"

class AndroidSpeechSynthesis(
    context: Context,
    private val onTtsUnavailable: () -> Unit
) : TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, this)

    @Volatile private var isReady = false
    @Volatile private var webViewRef: WebView? = null

    fun attachWebView(wv: WebView) {
        webViewRef = wv
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
                dispatchToJs("window.__androidTtsStart", utteranceId)
            }

            override fun onDone(utteranceId: String?) {
                dispatchToJs("window.__androidTtsDone", utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                dispatchToJs("window.__androidTtsError", utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                dispatchToJs("window.__androidTtsError", utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                dispatchToJs("window.__androidTtsDone", utteranceId)
            }
        })
        isReady = true
        Log.d(TAG, "TTS initialized successfully")
    }

    private fun dispatchToJs(fnName: String, utteranceId: String?) {
        val id = utteranceId ?: return
        val safeId = id.replace("\\", "\\\\").replace("'", "\\'")
        val wv = webViewRef ?: return
        mainHandler.post {
            wv.evaluateJavascript("if(typeof $fnName === 'function') $fnName('$safeId');", null)
        }
    }

    @JavascriptInterface
    fun speak(utteranceId: String?, text: String?): Boolean {
        if (!isReady || text.isNullOrEmpty() || utteranceId.isNullOrEmpty()) {
            Log.e(TAG, "TTS speak rejected (ready=$isReady id=$utteranceId len=${text?.length})")
            return false
        }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        return result == TextToSpeech.SUCCESS
    }

    @JavascriptInterface
    fun cancel() {
        if (isReady) tts.stop()
    }

    @JavascriptInterface
    fun isReady(): Boolean = isReady

    fun shutdown() {
        isReady = false
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        webViewRef = null
    }
}
