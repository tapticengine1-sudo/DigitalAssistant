package com.example.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Hold-to-Talk: пока кнопка зажата — STT, по отпусканию — финальный текст в колбэк.
 */
class HoldToTalk(
    private val activity: Activity,
    private val button: View,
    private val onListening: (Boolean) -> Unit,
    private val onTranscript: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var holding = false
    private var lastPartial = ""
    private var releasing = false
    private var sendOnStop = false

    fun attach() {
        button.isClickable = true
        button.isFocusable = true
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    startHold()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    v.performClick()
                    stopHold(send = true)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    stopHold(send = false)
                    true
                }
                else -> false
            }
        }
    }

    fun destroy() {
        holding = false
        sendOnStop = false
        destroyRecognizer()
        onListening(false)
    }

    private fun startHold() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(activity, "Разрешите микрофон", Toast.LENGTH_SHORT).show()
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        holding = true
        sendOnStop = false
        lastPartial = ""
        destroyRecognizer()
        startListening()
    }

    private fun stopHold(send: Boolean) {
        if (!holding && !sendOnStop) {
            onListening(false)
            return
        }
        holding = false
        sendOnStop = send
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
            finishFromPartial(send)
        }
        // Если onResults не придёт — отправим partial чуть позже
        button.postDelayed({
            if (sendOnStop) finishFromPartial(true)
        }, 700)
    }

    private fun startListening() {
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
            if (holding) {
                button.postDelayed({ if (holding) startListening() }, 400)
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

    private fun finishFromPartial(send: Boolean) {
        if (!sendOnStop && !send) return
        sendOnStop = false
        val text = lastPartial.trim()
        destroyRecognizer()
        onListening(false)
        if (send && text.isNotEmpty()) onTranscript(text)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, p: Bundle?) {}

        override fun onPartialResults(p: Bundle?) {
            val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) lastPartial = t
        }

        override fun onResults(r: Bundle?) {
            val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) lastPartial = t
            if (holding) {
                destroyRecognizer()
                startListening()
                return
            }
            if (sendOnStop) finishFromPartial(true)
            else {
                destroyRecognizer()
                onListening(false)
            }
        }

        override fun onError(error: Int) {
            if (releasing) return
            if (holding &&
                (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            ) {
                destroyRecognizer()
                button.postDelayed({ if (holding) startListening() }, 350)
                return
            }
            if (sendOnStop) {
                finishFromPartial(true)
            } else {
                destroyRecognizer()
                onListening(false)
            }
        }
    }
}
