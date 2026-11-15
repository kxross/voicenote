package com.voicenote.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * On-device dictation via Android's offline [SpeechRecognizer] — the privacy /
 * offline alternative to uploading audio to the transcription endpoint. Nothing
 * leaves the phone.
 *
 * The platform recognizer finalizes a segment after each pause, so to capture a
 * whole voice note we accumulate finalized segments and restart listening until
 * [stop] is called. SpeechRecognizer must be driven from the main thread, and
 * its callbacks arrive there, so construct and call this from the UI thread.
 *
 * Only usable on API 31+ with an installed on-device model — gate every use with
 * [isAvailable] and fall back to the endpoint when it's false.
 */
class OnDeviceTranscriber(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val finalized = StringBuilder()
    private var lastPartial = ""
    private var active = false

    private var onAmplitude: ((Float) -> Unit)? = null
    private var onText: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    /** Begin continuous dictation. Callbacks fire on the main thread. Returns
     *  false (and starts nothing) when on-device recognition isn't available. */
    fun start(
        onAmplitude: ((Float) -> Unit)? = null,
        onText: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ): Boolean {
        if (!isAvailable(context)) return false
        this.onAmplitude = onAmplitude
        this.onText = onText
        this.onError = onError
        finalized.setLength(0)
        lastPartial = ""
        active = true
        recognizer = createRecognizer() ?: return false
        recognizer!!.setRecognitionListener(listener)
        listen()
        return true
    }

    /** Stop dictation and return the full accumulated transcript. */
    fun stop(): String {
        active = false
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        return combined().trim()
    }

    /** Abort without producing a transcript (e.g. the user cancelled). */
    fun cancel() {
        active = false
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun combined(): String {
        val base = finalized.toString()
        return if (lastPartial.isBlank()) base else "$base $lastPartial".trim()
    }

    private fun createRecognizer(): SpeechRecognizer? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "createOnDeviceSpeechRecognizer failed", e)
        null
    }

    private fun recognizeIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    private fun listen() {
        if (!active) return
        try {
            recognizer?.startListening(recognizeIntent())
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            onError?.invoke("recognizer error")
        }
    }

    /** Restart after a finalized segment / silence. A short delay sidesteps the
     *  RECOGNIZER_BUSY some OEMs throw on an immediate restart. */
    private fun restart() {
        if (!active) return
        handler.postDelayed({ listen() }, RESTART_DELAY_MS)
    }

    private fun appendFinal(text: String) {
        if (text.isBlank()) return
        if (finalized.isNotEmpty()) finalized.append(" ")
        finalized.append(text.trim())
        lastPartial = ""
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            // rmsdB roughly -2..10 dB → normalize to 0..1 for the waveform.
            onAmplitude?.invoke(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (!active) return
            // Silence / no-match / busy just mean a pause in a longer note —
            // keep the session going by restarting.
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restart()
                else -> {
                    Log.w(TAG, "recognizer error $error")
                    onError?.invoke("recognizer error $error")
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            appendFinal(text)
            onText?.invoke(combined())
            if (active) restart()   // keep dictating until the user stops
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                lastPartial = text.trim()
                onText?.invoke(combined())
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        private const val TAG = "OnDeviceTranscriber"
        private const val RESTART_DELAY_MS = 80L

        /** True when the platform can dictate offline. Gated on API 33, where
         *  both isOnDeviceRecognitionAvailable and the on-device recognizer
         *  factory exist; on a model-less device it returns false. */
        fun isAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                try {
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                } catch (e: Exception) {
                    false
                }
    }
}
