package com.example.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService

class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

class AssistSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreate() {
        super.onCreate()
        // Без UI сессии — иначе система рисует полноэкранную заглушку
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        setUiEnabled(false)
        OverlayLauncher.open(context.applicationContext)
        hide()
    }
}

/** Заглушка: Android требует указать recognitionService для ассистента. */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, callback: Callback?) {}
    override fun onCancel(callback: Callback?) {}
    override fun onStopListening(callback: Callback?) {}
}
