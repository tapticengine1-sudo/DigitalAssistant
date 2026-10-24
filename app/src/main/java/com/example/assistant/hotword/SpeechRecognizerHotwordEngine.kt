package com.example.assistant.hotword

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Практичная реализация на системном SpeechRecognizer:
 * непрерывные сессии + поиск фразы в partial/final результатах.
 *
 * Для продакшена с низким энергопотреблением лучше подключить
 * выделенный wake-word SDK (Porcupine и т.п.) через тот же [HotwordEngine].
 */
class SpeechRecognizerHotwordEngine(
    private val context: Context
) : HotwordEngine {

    override var phrases: List<String> = HotwordPhrases.normalize("джарвис")

    private var recognizer: SpeechRecognizer? = null
    private var listener: HotwordEngine.Listener? = null
    private var running = false
    private val main = Handler(Looper.getMainLooper())
    private var restartPosted = false

    override fun start(listener: HotwordEngine.Listener) {
        this.listener = listener
        running = true
        main.post { ensureListening() }
    }

    override fun stop() {
        running = false
        restartPosted = false
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        listener?.onListeningChanged(false)
    }

    override fun release() {
        stop()
        listener = null
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private fun ensureListening() {
        if (!running) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener?.onEngineError("Распознавание речи недоступно")
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        listener?.onListeningChanged(true)
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onPartialResults(partialResults: Bundle?) {
                        checkResults(partialResults)
                    }

                    override fun onResults(results: Bundle?) {
                        checkResults(results)
                        scheduleRestart(350)
                    }

                    override fun onError(error: Int) {
                        listener?.onListeningChanged(false)
                        val delay = when (error) {
                            SpeechRecognizer.ERROR_CLIENT,
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                                destroyRecognizer()
                                800L
                            }
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                listener?.onEngineError("Нет разрешения RECORD_AUDIO")
                                return
                            }
                            else -> 450L
                        }
                        scheduleRestart(delay)
                    }
                })
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            listener?.onEngineError(e.message ?: "startListening failed")
            destroyRecognizer()
            scheduleRestart(1000)
        }
    }

    private fun checkResults(bundle: Bundle?) {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        for (utterance in list) {
            val hit = HotwordPhrases.containsHotword(utterance, phrases) ?: continue
            running = false
            restartPosted = false
            main.removeCallbacksAndMessages(null)
            destroyRecognizer()
            listener?.onHotword(hit)
            main.postDelayed({
                running = true
                ensureListening()
            }, 2500)
            return
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!running || restartPosted) return
        restartPosted = true
        main.postDelayed({
            restartPosted = false
            if (running) ensureListening()
        }, delayMs)
    }
}
