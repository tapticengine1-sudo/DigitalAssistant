package com.example.assistant.hotword

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.assistant.Prefs

/** Перезапуск слушателя «Джарвис» после перезагрузки. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        if (Prefs.hotwordEnabled(context)) {
            HotwordService.syncWithPrefs(context)
        }
    }
}
