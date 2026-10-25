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
    private lateinit var micBtn: Button
    private lateinit var sendBtn: Button
    private lateinit var waveformView: WaveformView
    private var attachPreview: TextView? = null
    private lateinit var client: DeepSeekClient
    private lateinit var store: ChatStore
    private var tts: TextToSpeech? = null
    private var holdToTalk: HoldToTalk? = null
    private var conversationId: Long = -1L
    private var pendingAttachment: PendingAttachment? = null
    private var isVoiceInput = false

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

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uriStr = result.data?.getStringExtra("photo_uri") ?: return@registerForActivityResult
        val uri = Uri.parse(uriStr)
        val att = AttachmentReader.fromUri(this, uri)
        if (att != null) {
            pendingAttachment = att
            updateAttachPreview()
        }
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
            data.getBooleanExtra(EXTRA_NEW_CHAT, false) -> startNewConversation(showGreeting = true)
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
        micBtn = findViewById(R.id.btnMic)
        sendBtn = findViewById(R.id.btnSend)
        waveformView = findViewById(R.id.waveform)
        attachPreview = findViewById(R.id.attachPreview)
        adapter = ChatAdapter(msgs)
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ru", "RU")
        }

        holdToTalk = HoldToTalk(
            activity = this,
            button = micBtn,
            onListening = { listening ->
                runOnUiThread {
                    updateVoiceUI(listening)
                }
            },
            onPartialText = { text ->
                runOnUiThread { input.setText(text) }
            },
            onTranscript = { text ->
                // Этот callback больше не используется для автоотправки
            },
            onRmsChanged = { rms ->
                runOnUiThread { waveformView.setAmplitude(rms) }
            }
        )
        holdToTalk?.attach()

        findViewById<Button>(R.id.btnAttach).setOnClickListener {
            showAttachmentMenu()
        }
        attachPreview?.setOnClickListener {
            pendingAttachment = null
            updateAttachPreview()
        }
        sendBtn.setOnClickListener { 
            if (isVoiceInput) {
                // Мгновенная отправка голосового ввода
                val text = input.text.toString().trim()
                isVoiceInput = false
                holdToTalk?.destroy()
                updateVoiceUI(false)
                if (text.isNotEmpty()) {
                    submitInput(fromVoice = true)
                }
                // Пересоздаем HoldToTalk после отправки
                recreateHoldToTalk()
            } else {
                submitInput(fromVoice = false) 
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitInput(fromVoice = false); true
            } else false
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnHistory).setOnClickListener {
            historyLauncher.launch(Intent(this, HistoryActivity::class.java))
        }

        findViewById<Button>(R.id.btnNewChat).setOnClickListener {
            startNewConversation(showGreeting = false)
        }

        findViewById<Button>(R.id.btnSearch).setOnClickListener {
            Toast.makeText(this, "Поиск пока не реализован", Toast.LENGTH_SHORT).show()
        }

        val restoreId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L)
        if (restoreId > 0) loadConversation(restoreId)
        else startNewConversation(showGreeting = false)

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
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), REQUEST_CODE_PERMISSIONS)
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS || requestCode == 1001) {
            val audioPermGranted = grantResults.isNotEmpty() && 
                permissions.indices.any { 
                    permissions[it] == Manifest.permission.RECORD_AUDIO && 
                    grantResults[it] == PackageManager.PERMISSION_GRANTED 
                }
            if (audioPermGranted) {
                Toast.makeText(this, "Разрешение на микрофон получено", Toast.LENGTH_SHORT).show()
            } else if (permissions.contains(Manifest.permission.RECORD_AUDIO)) {
                Toast.makeText(this, "Без доступа к микрофону голосовой ввод не работает", Toast.LENGTH_LONG).show()
            }
        }
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

    private fun startNewConversation(showGreeting: Boolean) {
        msgs.clear()
        adapter.notifyDataSetChanged()
        client.clearSessionHistory()
        pendingAttachment = null
        updateAttachPreview()
        conversationId = -1L
        thread {
            val id = try {
                store.createConversation().id
            } catch (_: Exception) {
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

    private fun updateVoiceUI(listening: Boolean) {
        runOnUiThread {
            isVoiceInput = listening
            if (listening) {
                micBtn.text = "⏸"
                micBtn.alpha = 1.0f
                micBtn.isEnabled = true
                micBtn.setBackgroundColor(ContextCompat.getColor(this, R.color.ptako_orange))
                sendBtn.text = "➤"
                sendBtn.visibility = View.VISIBLE
                waveformView.visibility = View.VISIBLE
            } else {
                micBtn.text = "🎤"
                micBtn.alpha = 1.0f
                micBtn.isEnabled = true
                micBtn.setBackgroundColor(ContextCompat.getColor(this, R.color.ptako_blue))
                sendBtn.text = "➤"
                waveformView.visibility = View.GONE
                waveformView.reset()
            }
        }
    }
    
    private fun recreateHoldToTalk() {
        // Сначала уничтожаем старый экземпляр
        holdToTalk?.destroy()
        
        holdToTalk = HoldToTalk(
            activity = this,
            button = micBtn,
            onListening = { listening ->
                updateVoiceUI(listening)
            },
            onPartialText = { text ->
                runOnUiThread { input.setText(text) }
            },
            onTranscript = { text ->
                // Не используется
            },
            onRmsChanged = { rms ->
                runOnUiThread { waveformView.setAmplitude(rms) }
            }
        )
        holdToTalk?.attach()
    }

    private fun submitInput(fromVoice: Boolean) {
        val t = input.text.toString().trim()
        val att = pendingAttachment
        if (t.isEmpty() && att == null) return
        val text = t.ifBlank {
            if (att?.isImage == true) "Что на изображении?" else "Проанализируй файл."
        }
        send(text, att, fromVoice)
    }

    private fun addUiAndPersist(m: Msg, attachment: PendingAttachment?) {
        msgs.add(m)
        adapter.notifyItemInserted(msgs.size - 1)
        list.scrollToPosition(msgs.size - 1)
        val convId = conversationId
        if (convId > 0) {
            thread {
                val id = store.addMessage(
                    StoredMessage(
                        conversationId = convId,
                        isUser = m.user,
                        text = m.text,
                        attachmentUri = attachment?.uri?.toString(),
                        attachmentMime = attachment?.mime,
                        attachmentName = attachment?.name ?: m.attachmentLabel
                    )
                )
                runOnUiThread { m.dbId = id }
            }
        }
    }

    private fun send(text: String, attachment: PendingAttachment?, fromVoice: Boolean = false) {
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
                "Ошибка: ${e.message}"
            }
            if (pending.dbId > 0) store.updateMessageText(pending.dbId, reply)
            runOnUiThread {
                pending.text = reply
                adapter.notifyItemChanged(pendingIndex)
                list.scrollToPosition(msgs.size - 1)

                // Умная озвучка: голос → текст+TTS, текст → только текст
                // Проверка режима "Не беспокоить"
                if (fromVoice && Prefs.speak(this) && !isDoNotDisturbActive()) {
                    tts?.speak(
                        reply.replace("*", "").replace("#", ""),
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "reply"
                    )
                }
            }
        }
    }

    private fun isDoNotDisturbActive(): Boolean {
        return try {
            val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
            audioManager?.ringerMode == AudioManager.RINGER_MODE_SILENT ||
                    audioManager?.ringerMode == AudioManager.RINGER_MODE_VIBRATE
        } catch (e: Exception) {
            false
        }
    }



    override fun onDestroy() {
        holdToTalk?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun showAttachmentMenu() {
        val sheet = AttachmentBottomSheet { choice ->
            when (choice) {
                AttachmentBottomSheet.Choice.CAMERA -> cameraLauncher.launch(Intent(this, CameraActivity::class.java))
                AttachmentBottomSheet.Choice.GALLERY -> galleryLauncher.launch(Intent(this, GalleryActivity::class.java))
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
    }
}
