package com.example.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
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
    private var isActuallyListening = false // Реальное состояние от SpeechRecognizer
    private var lastPartial = ""
    private var releasing = false
    private val mainHandler = Handler(Looper.getMainLooper())
    
    companion object {
        private const val TAG = "HoldToTalk"
        private const val RETRY_DELAY_MS = 400L
    }

    fun attach() {
        mainHandler.post {
            button.isClickable = true
            button.isFocusable = true
            button.isEnabled = true
            button.setOnClickListener {
                Log.d(TAG, "Button clicked, isListening=$isListening")
                if (isListening) {
                    stopListening()
                } else {
                    startListening()
                }
            }
        }
    }
    
    fun startListeningAuto() {
        Log.d(TAG, "startListeningAuto called")
        mainHandler.post {
            if (!isListening) startListening()
        }
    }

    fun destroy() {
        Log.d(TAG, "destroy called")
        isListening = false
        isActuallyListening = false
        mainHandler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        mainHandler.post { onListening(false) }
    }
    
    fun stopAndSend() {
        Log.d(TAG, "stopAndSend called")
        if (isListening) {
            stopListening()
            val text = lastPartial.trim()
            if (text.isNotEmpty()) {
                onTranscript(text)
            }
        }
    }

    private fun startListening() {
        Log.d(TAG, "startListening called")
        
        // Проверка разрешений
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            mainHandler.post {
                Toast.makeText(activity, "Разрешите доступ к микрофону в настройках", Toast.LENGTH_LONG).show()
                onListening(false)
            }
            try {
                activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request permissions", e)
            }
            return
        }
        
        // Проверка доступности сервиса распознавания
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Log.e(TAG, "Speech recognition not available")
            mainHandler.post {
                Toast.makeText(activity, "Служба распознавания речи недоступна. Установите Google App.", Toast.LENGTH_LONG).show()
                onListening(false)
            }
            return
        }
        
        isListening = true
        isActuallyListening = false
        lastPartial = ""
        destroyRecognizer()
        
        // Небольшая задержка для корректной инициализации
        mainHandler.postDelayed({
            if (isListening) {
                startRecognition()
            }
        }, 100)
    }

    private fun stopListening() {
        Log.d(TAG, "stopListening called")
        if (!isListening) {
            Log.d(TAG, "Already not listening, ignoring")
            return
        }
        isListening = false
        isActuallyListening = false
        mainHandler.removeCallbacksAndMessages(null)
        
        try {
            recognizer?.stopListening()
            Log.d(TAG, "stopListening called on recognizer")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recognizer", e)
            destroyRecognizer()
            mainHandler.post { onListening(false) }
        }
    }

    private fun startRecognition() {
        if (releasing) {
            Log.d(TAG, "Currently releasing, skipping startRecognition")
            return
        }
        
        if (!isListening) {
            Log.d(TAG, "Not in listening mode, skipping startRecognition")
            return
        }
        
        Log.d(TAG, "startRecognition called")
        val r = ensureRecognizer()
        if (r == null) {
            Log.e(TAG, "Failed to create recognizer")
            mainHandler.post { onListening(false) }
            return
        }
        
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 15000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 15000L)
        
        try {
            r.startListening(i)
            Log.d(TAG, "startListening called successfully")
            // Обновим UI только когда получим onReadyForSpeech/onBeginningOfSpeech
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting recognition", e)
            destroyRecognizer()
            mainHandler.post { onListening(false) }
            
            // Retry если все еще в режиме прослушивания
            if (isListening) {
                Log.d(TAG, "Scheduling retry")
                mainHandler.postDelayed({ 
                    if (isListening) startRecognition() 
                }, RETRY_DELAY_MS)
            }
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (recognizer != null) {
            return recognizer
        }
        
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Log.e(TAG, "Recognition not available")
            mainHandler.post {
                Toast.makeText(activity, "Нет службы распознавания речи (нужен Google App)", Toast.LENGTH_LONG).show()
            }
            return null
        }
        
        return try {
            // ВАЖНО: создаем на UI потоке
            mainHandler.post {
                if (recognizer == null) {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(activity).also {
                        it.setRecognitionListener(listener)
                        Log.d(TAG, "SpeechRecognizer created successfully")
                    }
                }
            }
            recognizer
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer", e)
            null
        }
    }

    private fun destroyRecognizer() {
        if (recognizer == null) return
        
        Log.d(TAG, "destroyRecognizer called")
        releasing = true
        isActuallyListening = false
        
        mainHandler.post {
            try {
                recognizer?.cancel()
                Log.d(TAG, "Recognizer cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling recognizer", e)
            }
            
            try {
                recognizer?.destroy()
                Log.d(TAG, "Recognizer destroyed")
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying recognizer", e)
            }
            
            recognizer = null
            releasing = false
        }
    }



    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
            isActuallyListening = true
            mainHandler.post { onListening(true) }
        }
        
        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
            isActuallyListening = true
            mainHandler.post { onListening(true) }
        }
        
        override fun onRmsChanged(v: Float) {
            mainHandler.post { onRmsChanged(v) }
        }
        
        override fun onBufferReceived(b: ByteArray?) {}
        
        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
            isActuallyListening = false
        }
        
        override fun onEvent(t: Int, p: Bundle?) {}

        override fun onPartialResults(p: Bundle?) {
            val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) {
                Log.d(TAG, "onPartialResults: $t")
                lastPartial = t
                mainHandler.post { onPartialText(t) }
            }
        }

        override fun onResults(r: Bundle?) {
            val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            Log.d(TAG, "onResults: $t, isListening=$isListening")
            
            if (!t.isNullOrBlank()) {
                lastPartial = t
                mainHandler.post { onPartialText(t) }
            }
            
            isActuallyListening = false
            
            // Если пользователь все еще держит кнопку - перезапускаем
            if (isListening) {
                Log.d(TAG, "Still listening, restarting recognition")
                destroyRecognizer()
                mainHandler.postDelayed({ 
                    if (isListening) startRecognition() 
                }, 200)
                return
            }
            
            // Иначе останавливаемся
            Log.d(TAG, "Stopping recognition")
            destroyRecognizer()
            mainHandler.post { onListening(false) }
        }

        override fun onError(error: Int) {
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                else -> "UNKNOWN_ERROR_$error"
            }
            Log.w(TAG, "onError: $errorMsg, isListening=$isListening, releasing=$releasing")
            
            if (releasing) {
                Log.d(TAG, "Ignoring error during release")
                return
            }
            
            isActuallyListening = false
            
            // Ошибки, при которых можно попробовать перезапустить
            val canRetry = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            
            // Критическая ошибка - нет разрешений
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                Log.e(TAG, "Insufficient permissions")
                isListening = false
                destroyRecognizer()
                mainHandler.post { 
                    onListening(false)
                    Toast.makeText(activity, "Нет доступа к микрофону. Разрешите в настройках.", Toast.LENGTH_LONG).show()
                }
                return
            }
            
            // Если еще слушаем и ошибка некритичная - пробуем перезапустить
            if (isListening && canRetry) {
                Log.d(TAG, "Retrying after error")
                destroyRecognizer()
                mainHandler.postDelayed({ 
                    if (isListening) startRecognition() 
                }, RETRY_DELAY_MS)
                return
            }
            
            // Иначе останавливаемся и уведомляем пользователя
            isListening = false
            destroyRecognizer()
            mainHandler.post { 
                onListening(false)
                if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                    Toast.makeText(activity, "Ошибка сети. Проверьте подключение.", Toast.LENGTH_SHORT).show()
                } else if (error == SpeechRecognizer.ERROR_SERVER) {
                    Toast.makeText(activity, "Ошибка сервера распознавания.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
