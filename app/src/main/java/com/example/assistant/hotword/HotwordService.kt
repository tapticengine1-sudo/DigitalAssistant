package com.example.assistant.hotword

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.assistant.MainActivity
import com.example.assistant.OverlayLauncher
import com.example.assistant.Prefs
import com.example.assistant.R

/**
 * Foreground Service: глобальный hotword «Джарвис»
 * (приложение / рабочий стол / lock screen).
 */
class HotwordService : Service(), HotwordEngine.Listener {

    private var engine: HotwordEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var paused = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ensureChannel()
        val notification = buildListeningNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        acquireWakeLock()
        startEngine()
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
            ACTION_PAUSE -> pauseEngine()
            ACTION_RESUME -> resumeEngine()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        engine?.release()
        engine = null
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onHotword(matched: String) {
        playCueBeep()
        maybeWakeScreen()
        // Full-screen intent надёжнее с lock screen / background start limits
        postFullScreenAssist()
        try {
            OverlayLauncher.open(this, fromHotword = true, startVoice = false)
        } catch (_: Exception) {
        }
        updateNotification("Слышу «$matched» — открываю помощника")
        main.postDelayed({
            if (isRunning && !paused) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildListeningNotification())
            }
        }, 2500)
    }

    override fun onEngineError(message: String) {
        updateNotification("Hotword: $message")
    }

    override fun onListeningChanged(active: Boolean) {
        if (active && !paused) updateNotification("Слушаю «${Prefs.hotwordPhrase(this)}»…")
    }

    private fun startEngine() {
        engine?.release()
        val eng = SpeechRecognizerHotwordEngine(applicationContext)
        eng.phrases = HotwordPhrases.normalize(Prefs.hotwordPhrase(this))
        engine = eng
        paused = false
        eng.start(this)
    }

    private fun pauseEngine() {
        paused = true
        engine?.stop()
        updateNotification("Пауза (микрофон занят чатом)")
    }

    private fun resumeEngine() {
        if (!Prefs.hotwordEnabled(this)) return
        paused = false
        startEngine()
        updateNotification("Слушаю «${Prefs.hotwordPhrase(this)}»…")
    }

    private fun playCueBeep() {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val am = getSystemService(AudioManager::class.java)
            val stream = if (Build.VERSION.SDK_INT >= 26) {
                am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    .let { if (it > 0) AudioManager.STREAM_MUSIC else AudioManager.STREAM_NOTIFICATION }
            } else {
                AudioManager.STREAM_NOTIFICATION
            }
            val tone = ToneGenerator(stream, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 160)
            main.postDelayed({
                try {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 140)
                } catch (_: Exception) {
                }
            }, 180)
            main.postDelayed({
                try {
                    tone.release()
                } catch (_: Exception) {
                }
            }, 450)
            @Suppress("UNUSED_VARIABLE")
            val unused = attrs
        } catch (_: Exception) {
            try {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                tone.startTone(ToneGenerator.TONE_CDMA_PIP, 200)
                main.postDelayed({
                    try {
                        tone.release()
                    } catch (_: Exception) {
                    }
                }, 300)
            } catch (_: Exception) {
            }
        }
    }

    private fun maybeWakeScreen() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "assistant:hotword"
            )
            wl.acquire(3000)
            wl.release()
        } catch (_: Exception) {
        }
    }

    private fun postFullScreenAssist() {
        val pi = PendingIntent.getActivity(
            this, 3,
            OverlayLauncher.intent(this, fromHotword = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setContentTitle("Джарвис")
            .setContentText("Нажмите, чтобы открыть помощника")
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT_ID, n)
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "assistant:hotword_cpu").also {
                it.acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Кодовое слово", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Вызов Джарвиса", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun buildListeningNotification(): Notification {
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
        val assist = PendingIntent.getActivity(
            this, 2,
            OverlayLauncher.intent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Помощник · Джарвис")
            .setContentText("Слушаю «${Prefs.hotwordPhrase(this)}»…")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .addAction(0, "Открыть", assist)
            .addAction(0, "Стоп", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Помощник · Джарвис")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    companion object {
        const val CHANNEL_ID = "hotword"
        const val CHANNEL_ALERT = "hotword_alert"
        const val NOTIF_ID = 42
        const val NOTIF_ALERT_ID = 43
        const val ACTION_STOP = "com.example.assistant.hotword.STOP"
        const val ACTION_UPDATE_PHRASE = "com.example.assistant.hotword.UPDATE_PHRASE"
        const val ACTION_PAUSE = "com.example.assistant.hotword.PAUSE"
        const val ACTION_RESUME = "com.example.assistant.hotword.RESUME"

        @Volatile var isRunning: Boolean = false
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, HotwordService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HotwordService::class.java))
        }

        fun syncWithPrefs(ctx: Context) {
            if (Prefs.hotwordEnabled(ctx)) start(ctx) else stop(ctx)
        }

        fun pause(ctx: Context) {
            if (!isRunning) return
            try {
                ctx.startService(Intent(ctx, HotwordService::class.java).setAction(ACTION_PAUSE))
            } catch (_: Exception) {
            }
        }

        fun resume(ctx: Context) {
            if (!isRunning || !Prefs.hotwordEnabled(ctx)) return
            try {
                ctx.startService(Intent(ctx, HotwordService::class.java).setAction(ACTION_RESUME))
            } catch (_: Exception) {
            }
        }
    }
}
