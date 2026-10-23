package com.example.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.example.assistant.hotword.HotwordService

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val key = findViewById<EditText>(R.id.apiKey)
        val swSpeak = findViewById<SwitchCompat>(R.id.switchSpeak)
        val swCont = findViewById<SwitchCompat>(R.id.switchContinuous)
        val swMulti = findViewById<SwitchCompat>(R.id.switchMultimodal)
        val visionModel = findViewById<EditText>(R.id.visionModel)
        val swHotword = findViewById<SwitchCompat>(R.id.switchHotword)
        val hotwordPhrase = findViewById<EditText>(R.id.hotwordPhrase)

        key.setText(Prefs.apiKey(this))
        swSpeak.isChecked = Prefs.speak(this)
        swCont.isChecked = Prefs.continuousVoice(this)
        swMulti.isChecked = Prefs.multimodal(this)
        visionModel.setText(Prefs.visionModel(this))
        swHotword.isChecked = Prefs.hotwordEnabled(this)
        hotwordPhrase.setText(Prefs.hotwordPhrase(this))

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            Prefs.setApiKey(this, key.text.toString().trim())
            Prefs.setSpeak(this, swSpeak.isChecked)
            Prefs.setContinuousVoice(this, swCont.isChecked)
            Prefs.setMultimodal(this, swMulti.isChecked)
            Prefs.setVisionModel(this, visionModel.text.toString().trim())
            Prefs.setHotwordPhrase(this, hotwordPhrase.text.toString().trim())
            Prefs.setHotwordEnabled(this, swHotword.isChecked)
            HotwordService.syncWithPrefs(this)
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
    }
}
