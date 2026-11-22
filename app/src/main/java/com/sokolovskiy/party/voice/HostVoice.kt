package com.sokolovskiy.party.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Speech recognition for the host page («Три слова» marks the secret words the speaker said): a WebView has no
 * Web Speech API, so the page asks the app through `PGApp.listen()`. Android's recogniser ends after every phrase,
 * so this keeps restarting it until [stop]. Main thread only.
 */
class HostVoice(
    private val context: Context,
    private val onText: (text: String, final: Boolean) -> Unit,
    /** "on" once it hears, "off" when it can't run, "denied" without the mic permission. */
    private val onState: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var wanted = false
    private var announced = false
    private var lang = "uk-UA"

    fun start(language: String) {
        lang = language
        wanted = true
        announced = false
        main.removeCallbacksAndMessages(null)
        val r = rec ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
            ?.also { it.setRecognitionListener(listener); rec = it }
        if (r == null) { wanted = false; onState("off"); return }
        listen()
    }

    /** Stops after the current phrase: its final result still arrives. */
    fun stop() {
        wanted = false
        main.removeCallbacksAndMessages(null)
        runCatching { rec?.stopListening() }
    }

    fun destroy() {
        wanted = false
        main.removeCallbacksAndMessages(null)
        runCatching { rec?.destroy() }
        rec = null
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        runCatching { rec?.startListening(i) }.onFailure { wanted = false; onState("off") }
    }

    private fun again(delayMs: Long) {
        if (wanted) main.postDelayed({ if (wanted) listen() }, delayMs)
    }

    private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!announced && wanted) { announced = true; onState("on") }
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) { best(partialResults)?.let { onText(it, false) } }
        override fun onResults(results: Bundle?) {
            best(results)?.let { onText(it, true) }
            again(50)
        }
        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { wanted = false; onState("denied") }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> again(500)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> again(1000)
                // silence (no match / speech timeout) or a client hiccup: just listen again
                else -> again(100)
            }
        }
    }
}
