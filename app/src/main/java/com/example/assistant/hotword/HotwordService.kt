package com.example.assistant.hotword

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.assistant.MainActivity
import com.example.assistant.Prefs
import com.example.assistant.R

/**
 * Foreground Service: держит [HotwordEngine] и при «Джарвис» открывает шторку ассистента.
 */
class HotwordService : Service(), HotwordEngine.Listener {

    private var engine: HotwordEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notification = buildNotification("Слушаю «${Prefs.hotwordPhrase(this)}»…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        val eng = SpeechRecognizerHotwordEngine(applicationContext)
        eng.phrases = HotwordPhrases.normalize(Prefs.hotwordPhrase(this))
        engine = eng
        eng.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setHotwordEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_PHRASE -> {
                engine?.phrases = HotwordPhrases.normalize(Prefs.hotwordPhrase(this))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.release()
        engine = null
        super.onDestroy()
    }

    override fun onHotword(matched: String) {
        val i = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_ASSIST)
            .putExtra(MainActivity.EXTRA_OVERLAY, true)
            .putExtra(MainActivity.EXTRA_FROM_HOTWORD, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(i)
        updateNotification("Слышу «$matched» — открываю помощника")
    }

    override fun onEngineError(message: String) {
        updateNotification("Hotword: $message")
    }

    override fun onListeningChanged(active: Boolean) {
        if (active) updateNotification("Слушаю «${Prefs.hotwordPhrase(this)}»…")
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Кодовое слово", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, HotwordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Помощник · Джарвис")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .addAction(0, "Стоп", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val CHANNEL_ID = "hotword"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "com.example.assistant.hotword.STOP"
        const val ACTION_UPDATE_PHRASE = "com.example.assistant.hotword.UPDATE_PHRASE"

        fun start(ctx: Context) {
            val i = Intent(ctx, HotwordService::class.java)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HotwordService::class.java))
        }

        fun syncWithPrefs(ctx: Context) {
            if (Prefs.hotwordEnabled(ctx)) start(ctx) else stop(ctx)
        }
    }
}
