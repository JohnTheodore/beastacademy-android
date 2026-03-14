package com.example.beast_academy_apk

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import android.webkit.JavascriptInterface
import java.util.Locale

private const val TAG = "BeastAcademyApp"

class AndroidSpeechSynthesis(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var isReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            isReady = true
            Log.d(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "TTS Initialization failed!")
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