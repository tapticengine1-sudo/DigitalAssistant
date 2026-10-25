package com.example.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val msgs = mutableListOf<Msg>()
    private lateinit var adapter: ChatAdapter
    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private var attachPreview: TextView? = null
    private lateinit var client: DeepSeekClient
    private lateinit var store: ChatStore
    private var tts: TextToSpeech? = null
    private var conversationId: Long = -1L
    private var pendingAttachment: PendingAttachment? = null

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val photoPath = result.data?.getStringExtra("photo_path") ?: return@registerForActivityResult
        val file = java.io.File(photoPath)
        if (file.exists()) {
            val uri = Uri.fromFile(file)
            val att = AttachmentReader.fromUri(this, uri)
            if (att != null) {
                pendingAttachment = att
                updateAttachPreview()
            } else {
                Toast.makeText(this, "Не удалось обработать фото с камеры", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Файл фото не найден: $photoPath", Toast.LENGTH_SHORT).show()
        }
    }

    // Упрощенный системный выбор изображения через GetContent
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        val att = AttachmentReader.fromUri(this, uri)
        if (att == null) {
            Toast.makeText(this, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        pendingAttachment = att
        updateAttachPreview()
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        val att = AttachmentReader.fromUri(this, uri)
        if (att == null) {
            Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        pendingAttachment = att
        updateAttachPreview()
    }

    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        when {
            data.getBooleanExtra(EXTRA_NEW_CHAT, false) -> startNewConversation()
            data.hasExtra(EXTRA_CONVERSATION_ID) ->
                loadConversation(data.getLongExtra(EXTRA_CONVERSATION_ID, -1L))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Assist / hotword → отдельная шторка, не этот экран
        if (isAssistHandoff(intent)) {
            OverlayLauncher.open(
                this,
                fromHotword = intent.getBooleanExtra(OverlayActivity.EXTRA_FROM_HOTWORD, false)
            )
            finish()
            return
        }

        store = ChatStore.get(this)
        client = DeepSeekClient(this)
        list = findViewById(R.id.list)
        input = findViewById(R.id.input)
        sendBtn = findViewById(R.id.btnSend)
        attachPreview = findViewById(R.id.attachPreview)
        
        adapter = ChatAdapter(msgs)
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter

        // Инициализация TTS с правильной обработкой
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("ru", "RU"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    android.util.Log.e("MainActivity", "Russian TTS not supported")
                    runOnUiThread {
                        Toast.makeText(this, "Русский язык не поддерживается TTS", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    android.util.Log.d("MainActivity", "TTS initialized successfully")
                }
            } else {
                android.util.Log.e("MainActivity", "TTS initialization failed")
            }
        }

        findViewById<Button>(R.id.btnAttach).setOnClickListener {
            showAttachmentMenu()
        }
        attachPreview?.setOnClickListener {
            pendingAttachment = null
            updateAttachPreview()
        }
        sendBtn.setOnClickListener { 
            submitInput()
        }
        
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitInput()
                true
            } else false
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnHistory).setOnClickListener {
            historyLauncher.launch(Intent(this, HistoryActivity::class.java))
        }

        findViewById<Button>(R.id.btnNewChat).setOnClickListener {
            startNewConversation()
        }

        findViewById<Button>(R.id.btnSearch).setOnClickListener {
            Toast.makeText(this, "Поиск пока не реализован", Toast.LENGTH_SHORT).show()
        }

        val restoreId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L)
        if (restoreId > 0) loadConversation(restoreId)
        else startNewConversation()

        requestAppPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isAssistHandoff(intent)) {
            OverlayLauncher.open(
                this,
                fromHotword = intent.getBooleanExtra(OverlayActivity.EXTRA_FROM_HOTWORD, false)
            )
        }
    }

    private fun isAssistHandoff(i: Intent?): Boolean {
        if (i == null) return false
        return i.action == Intent.ACTION_ASSIST ||
            i.getBooleanExtra(EXTRA_OVERLAY, false) ||
            i.getBooleanExtra("listen", false)
    }

    private fun requestAppPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.READ_MEDIA_IMAGES
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        requestPermissions(perms.toTypedArray(), REQUEST_CODE_PERMISSIONS)
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        android.util.Log.d("MainActivity", "Permissions result received")
    }



    private fun updateAttachPreview() {
        val tv = attachPreview ?: return
        val att = pendingAttachment
        if (att == null) {
            tv.visibility = View.GONE
            tv.text = ""
        } else {
            tv.visibility = View.VISIBLE
            val kind = if (att.isImage) "изображение" else "файл"
            tv.text = "📎 $kind: ${att.name}  (нажмите, чтобы убрать)"
        }
    }

    private fun startNewConversation() {
        msgs.clear()
        adapter.notifyDataSetChanged()
        client.clearSessionHistory()
        pendingAttachment = null
        updateAttachPreview()
        conversationId = -1L
        
        thread {
            val id = try {
                store.createConversation().id
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to create conversation", e)
                -1L
            }
            runOnUiThread {
                conversationId = id
            }
        }
    }

    private fun loadConversation(id: Long) {
        if (id <= 0) return
        
        thread {
            val conv = store.getConversation(id)
            val stored = store.messagesFor(id)
            
            runOnUiThread {
                if (conv == null) {
                    Toast.makeText(this, "Диалог не найден", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                
                conversationId = id
                client.seedFromMessages(stored)
                msgs.clear()
                
                for (m in stored) {
                    val uri = m.attachmentUri?.let { Uri.parse(it) }
                    msgs += Msg(m.isUser, m.text, m.attachmentName, uri, m.id)
                }
                
                adapter.notifyDataSetChanged()
                if (msgs.isNotEmpty()) list.scrollToPosition(msgs.size - 1)
            }
        }
    }

    private fun submitInput() {
        val t = input.text.toString().trim()
        val att = pendingAttachment
        
        if (t.isEmpty() && att == null) return
        
        val text = t.ifBlank {
            if (att?.isImage == true) "Что на изображении?" else "Проанализируй файл."
        }
        
        send(text, att)
    }

    private fun send(text: String, attachment: PendingAttachment?) {
        input.setText("")
        val attachLabel = attachment?.name
        pendingAttachment = null
        updateAttachPreview()

        val userMsg = Msg(true, text, attachmentLabel = attachLabel, attachmentUri = attachment?.uri)
        msgs.add(userMsg)
        adapter.notifyItemInserted(msgs.size - 1)

        val pending = Msg(false, "…")
        msgs.add(pending)
        adapter.notifyItemInserted(msgs.size - 1)
        list.scrollToPosition(msgs.size - 1)
        val pendingIndex = msgs.lastIndex
        val convId = conversationId

        thread {
            if (convId > 0) {
                userMsg.dbId = store.addMessage(
                    StoredMessage(
                        conversationId = convId,
                        isUser = true,
                        text = text,
                        attachmentUri = attachment?.uri?.toString(),
                        attachmentMime = attachment?.mime,
                        attachmentName = attachLabel
                    )
                )
                pending.dbId = store.addMessage(
                    StoredMessage(conversationId = convId, isUser = false, text = "…")
                )
            }
            val reply = try {
                client.ask(text, attachment)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error getting response", e)
                "Ошибка: ${e.message}"
            }
            
            if (pending.dbId > 0) store.updateMessageText(pending.dbId, reply)
            
            runOnUiThread {
                pending.text = reply
                adapter.notifyItemChanged(pendingIndex)
                list.scrollToPosition(msgs.size - 1)

                // Озвучиваем ответ, если TTS включен в настройках
                speakResponseIfAllowed(reply)
            }
        }
    }

    private fun speakResponseIfAllowed(text: String) {
        if (!Prefs.speak(this)) {
            android.util.Log.d("MainActivity", "TTS disabled in settings")
            return
        }
        
        if (isDoNotDisturbActive()) {
            android.util.Log.d("MainActivity", "Do Not Disturb active, skipping TTS")
            return
        }
        
        val ttsInstance = tts
        if (ttsInstance == null) {
            android.util.Log.w("MainActivity", "TTS not initialized")
            return
        }
        
        // Очищаем markdown и специальные символы
        val cleanText = text
            .replace("*", "")
            .replace("#", "")
            .replace("_", "")
            .replace("`", "")
            .trim()
        
        if (cleanText.isNotEmpty()) {
            val result = ttsInstance.speak(
                cleanText,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "reply_${System.currentTimeMillis()}"
            )
            
            if (result == TextToSpeech.ERROR) {
                android.util.Log.e("MainActivity", "TTS speak failed")
            } else {
                android.util.Log.d("MainActivity", "TTS speaking: ${cleanText.take(50)}...")
            }
        }
    }

    private fun isDoNotDisturbActive(): Boolean {
        return try {
            val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
            val ringerMode = audioManager?.ringerMode
            ringerMode == AudioManager.RINGER_MODE_SILENT || ringerMode == AudioManager.RINGER_MODE_VIBRATE
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error checking DND status", e)
            false
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun showAttachmentMenu() {
        val sheet = AttachmentBottomSheet { choice ->
            when (choice) {
                AttachmentBottomSheet.Choice.CAMERA -> cameraLauncher.launch(Intent(this, CameraActivity::class.java))
                AttachmentBottomSheet.Choice.GALLERY -> pickImage.launch("image/*")
                AttachmentBottomSheet.Choice.FILE -> pickFile.launch(arrayOf("image/*", "text/*", "application/pdf", "*/*"))
            }
        }
        sheet.show(supportFragmentManager, "attachment_menu")
    }

    companion object {
        const val EXTRA_OVERLAY = "overlay"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_NEW_CHAT = "new_chat"
        private const val REQUEST_CODE_PERMISSIONS = 1000
        private const val TAG = "MainActivity"
    }
}
