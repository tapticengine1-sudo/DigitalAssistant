package com.example.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat


class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val key = findViewById<EditText>(R.id.apiKey)
        val swSpeak = findViewById<SwitchCompat>(R.id.switchSpeak)
        val swMulti = findViewById<SwitchCompat>(R.id.switchMultimodal)
        val visionModel = findViewById<EditText>(R.id.visionModel)

        key.setText(Prefs.apiKey(this))
        swSpeak.isChecked = Prefs.speak(this)
        swMulti.isChecked = Prefs.multimodal(this)
        visionModel.setText(Prefs.visionModel(this))

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            Prefs.setApiKey(this, key.text.toString().trim())
            Prefs.setSpeak(this, swSpeak.isChecked)
            Prefs.setMultimodal(this, swMulti.isChecked)
            Prefs.setVisionModel(this, visionModel.text.toString().trim())
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            finish()
        }
        findViewById<Button>(R.id.btnDefault).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        }
        findViewById<Button>(R.id.btnPerms).setOnClickListener {
            val perms = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CONTACTS
            )
            if (Build.VERSION.SDK_INT >= 33) {
                perms += Manifest.permission.POST_NOTIFICATIONS
                perms += Manifest.permission.READ_MEDIA_IMAGES
            } else {
                perms += Manifest.permission.READ_EXTERNAL_STORAGE
            }
            requestPermissions(perms.toTypedArray(), 1)
        }
        findViewById<Button>(R.id.btnWrite).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnDnd).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            requestIgnoreBattery()
        }
        findViewById<Button>(R.id.btnFullScreen).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(Uri.parse("package:$packageName"))
                )
            } else {
                Toast.makeText(this, "Нужно Android 14+", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestIgnoreBattery() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "Уже без ограничений батареи", Toast.LENGTH_SHORT).show()
                return
            }
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
