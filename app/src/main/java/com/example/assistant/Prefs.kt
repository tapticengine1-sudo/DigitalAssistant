package com.example.assistant

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = sp(c).getString("key", "") ?: ""
    fun setApiKey(c: Context, v: String) = sp(c).edit().putString("key", v).apply()

    fun speak(c: Context): Boolean = sp(c).getBoolean("speak", true)
    fun setSpeak(c: Context, v: Boolean) = sp(c).edit().putBoolean("speak", v).apply()

    /** Непрерывный голосовой диалог: после TTS снова слушать. */
    fun continuousVoice(c: Context): Boolean = sp(c).getBoolean("continuous_voice", false)
    fun setContinuousVoice(c: Context, v: Boolean) =
        sp(c).edit().putBoolean("continuous_voice", v).apply()

    /** OpenAI-совместимый multimodal (изображения в content[]). */
    fun multimodal(c: Context): Boolean = sp(c).getBoolean("multimodal", false)
    fun setMultimodal(c: Context, v: Boolean) = sp(c).edit().putBoolean("multimodal", v).apply()

    fun visionModel(c: Context): String =
        sp(c).getString("vision_model", "deepseek-chat") ?: "deepseek-chat"
    fun setVisionModel(c: Context, v: String) =
        sp(c).edit().putString("vision_model", v.ifBlank { "deepseek-chat" }).apply()

    fun hotwordEnabled(c: Context): Boolean = sp(c).getBoolean("hotword", false)
    fun setHotwordEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("hotword", v).apply()

    fun hotwordPhrase(c: Context): String =
        sp(c).getString("hotword_phrase", "джарвис") ?: "джарвис"
    fun setHotwordPhrase(c: Context, v: String) =
        sp(c).edit().putString("hotword_phrase", v.ifBlank { "джарвис" }).apply()
}
