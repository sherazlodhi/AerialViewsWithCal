package com.neilturner.aerialviews.services

import android.content.Context
import android.speech.tts.TextToSpeech
import timber.log.Timber
import java.util.Locale

/** Service to handle Text-to-Speech announcements via FlowBus */
class TtsService(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            tts?.language = Locale.getDefault()
            Timber.i("TtsService: initialized")
        } else {
            Timber.e("TtsService: initialization failed")
        }
    }

    fun speak(text: String) {
        if (!isReady) {
            Timber.w("TtsService: not ready yet")
            return
        }
        Timber.i("TtsService: Speaking: $text")
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "msg_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
        tts?.shutdown()
    }
}

/** Event to trigger speech from any component */
data class SpeechEvent(val text: String)
