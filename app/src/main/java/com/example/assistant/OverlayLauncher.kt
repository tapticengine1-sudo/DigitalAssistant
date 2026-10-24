package com.example.assistant

import android.content.Context
import android.content.Intent
import android.os.Build

/** Единая точка запуска компактной шторки поверх текущего экрана / lock screen. */
object OverlayLauncher {

    fun intent(
        ctx: Context,
        fromHotword: Boolean = false,
        startVoice: Boolean = false
    ): Intent {
        return Intent(ctx, OverlayActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
            if (Build.VERSION.SDK_INT >= 24) {
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            putExtra(OverlayActivity.EXTRA_FROM_HOTWORD, fromHotword)
            putExtra(OverlayActivity.EXTRA_START_VOICE, startVoice)
        }
    }

    fun open(ctx: Context, fromHotword: Boolean = false, startVoice: Boolean = false) {
        ctx.startActivity(intent(ctx, fromHotword, startVoice))
    }
}
