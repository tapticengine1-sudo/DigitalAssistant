package com.example.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * VoiceInput в стиле Gemini: нажать один раз для записи, отображение волн,
 * выбор - остановить и отредактировать или отправить сразу.
 */
class HoldToTalk(
    private val activity: Activity,
    private val button: View,
    private val onListening: (Boolean) -> Unit,
    private val onPartialText: (String) -> Unit = {},
    private val onTranscript: (String) -> Unit,
    private val onRmsChanged: (Float) -> Unit = {}
) {
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var lastPartial = ""
    private var releasing = false

    fun attach() {
        button.isClickable = true
        button.isFocusable = true
        button.setOnClickListener {
            if (isListening) {
                stopListening()
            } else {
                startListening()
            }
        }
    }
    
    fun startListeningAuto() {
        if (!isListening) startListening()
    }

    fun destroy() {
        isListening = false
        destroyRecognizer()
        onListening(false)
    }
    
    fun stopAndSend() {
        if (isListening) {
            stopListening()
            val text = lastPartial.trim()
            if (text.isNotEmpty()) {
                onTranscript(text)
            }
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(activity, "Разрешите микрофон", Toast.LENGTH_SHORT).show()
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        isListening = true
        lastPartial = ""
        destroyRecognizer()
        startRecognition()
    }

    private fun stopListening() {
        if (!isListening) return
        isListening = false
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
            destroyRecognizer()
            onListening(false)
        }
    }

    private fun startRecognition() {
        if (releasing) return
        val r = ensureRecognizer() ?: return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 15000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 15000L)
        try {
            r.startListening(i)
            onListening(true)
        } catch (_: Exception) {
            destroyRecognizer()
            onListening(false)
            if (isListening) {
                button.postDelayed({ if (isListening) startRecognition() }, 400)
            }
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Toast.makeText(activity, "Нет службы распознавания речи (нужен Google)", Toast.LENGTH_LONG).show()
            return null
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity).also {
                it.setRecognitionListener(listener)
            }
        }
        return recognizer
    }

    private fun destroyRecognizer() {
        releasing = true
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        releasing = false
    }



    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {
            activity.runOnUiThread { onRmsChanged(v) }
        }
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, p: Bundle?) {}

        override fun onPartialResults(p: Bundle?) {
            val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) {
                lastPartial = t
                activity.runOnUiThread { onPartialText(t) }
            }
        }

        override fun onResults(r: Bundle?) {
            val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) {
                lastPartial = t
                activity.runOnUiThread { onPartialText(t) }
            }
            if (isListening) {
                destroyRecognizer()
                startRecognition()
                return
            }
            destroyRecognizer()
            onListening(false)
        }

        override fun onError(error: Int) {
            if (releasing) return
            if (isListening &&
                (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            ) {
                destroyRecognizer()
                button.postDelayed({ if (isListening) startRecognition() }, 350)
                return
            }
            destroyRecognizer()
            onListening(false)
        }
    }
}
