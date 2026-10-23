package com.example.assistant.hotword

/**
 * Движок детекции кодового слова.
 * Реализации могут быть на SpeechRecognizer, Porcupine, Vosk и т.д.
 */
interface HotwordEngine {
    /** Нормализованные варианты фразы (например «джарвис», «jarvis»). */
    var phrases: List<String>

    fun start(listener: Listener)
    fun stop()
    fun release()

    interface Listener {
        fun onHotword(matched: String)
        fun onEngineError(message: String)
        fun onListeningChanged(active: Boolean)
    }
}

object HotwordPhrases {
    fun normalize(raw: String): List<String> {
        val base = raw.trim().lowercase()
            .replace('ё', 'е')
            .ifBlank { "джарвис" }
        val set = linkedSetOf(base)
        when {
            base.contains("джарвис") || base == "jarvis" -> {
                set += "джарвис"
                set += "jarvis"
                set += "джервис"
            }
        }
        return set.toList()
    }

    fun containsHotword(utterance: String, phrases: List<String>): String? {
        val text = utterance.lowercase().replace('ё', 'е')
        return phrases.firstOrNull { text.contains(it) }
    }
}
