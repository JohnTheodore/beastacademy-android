package com.example.beast_academy_apk

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import android.webkit.JavascriptInterface
import java.util.Locale

private const val TAG = "BeastAcademyApp"

class AndroidSpeechSynthesis(
    context: Context,
    private val onTtsUnavailable: () -> Unit
) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var isReady = false

    init {
        val available = tts.engines
        Log.d(TAG, "Available TTS engines: ${available.map { it.name }}")
        if (available.isEmpty()) {
            onTtsUnavailable()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            isReady = true
            Log.d(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "TTS Initialization failed!")
            onTtsUnavailable()
        }
    }

    @JavascriptInterface
    fun speak(text: String?) {
        if (isReady && !text.isNullOrEmpty()) {
            Log.d(TAG, "Native speaking: $text")
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utteranceId")
        } else {
            Log.e(TAG, "TTS not ready or text empty. isReady=$isReady text=$text")
        }
    }

    @JavascriptInterface
    fun cancel() {
        if (isReady) tts.stop()
    }

    @JavascriptInterface
    fun isReady(): Boolean = isReady

    fun shutdown() {
        tts.shutdown()
    }
}
