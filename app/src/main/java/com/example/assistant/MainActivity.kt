package com.example.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.assistant.hotword.HotwordService
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val msgs = mutableListOf<Msg>()
    private lateinit var adapter: ChatAdapter
    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private var micBtn: Button? = null
    private var attachPreview: TextView? = null
    private var continuousSwitch: SwitchCompat? = null
    private lateinit var client: DeepSeekClient
    private lateinit var store: ChatStore
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var voiceDialog = false
    private var overlayMode = false
    private var conversationId: Long = -1L
    private var pendingAttachment: PendingAttachment? = null

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
        overlayMode = isOverlayIntent(intent)
        if (overlayMode) setTheme(R.style.Theme_Assistant_Overlay)
        super.onCreate(savedInstanceState)

        if (overlayMode) {
            setContentView(R.layout.activity_assist)
            setupOverlayWindow()
        } else {
            setContentView(R.layout.activity_main)
        }

        store = ChatStore.get(this)
        client = DeepSeekClient(this)
        list = findViewById(R.id.list)
        input = findViewById(R.id.input)
        micBtn = findViewById(R.id.btnMic)
        attachPreview = findViewById(R.id.attachPreview)
        continuousSwitch = findViewById(R.id.switchContinuous)
        adapter = ChatAdapter(msgs)
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ru", "RU")
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) {}
            override fun onDone(id: String?) {
                if (overlayMode) return
                val cont = voiceDialog || Prefs.continuousVoice(this@MainActivity)
                if (cont) runOnUiThread {
                    voiceDialog = true
                    startListening()
                }
            }
        })

        findViewById<Button>(R.id.btnAttach)?.setOnClickListener {
            pickFile.launch(
                arrayOf(
                    "image/*",
                    "text/*",
                    "application/pdf",
                    "application/json",
                    "*/*"
                )
            )
        }
        attachPreview?.setOnClickListener {
            pendingAttachment = null
            updateAttachPreview()
        }

        findViewById<Button>(R.id.btnSend).setOnClickListener { submitInput(fromVoice = false) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitInput(fromVoice = false)
                true
            } else false
        }

        if (!overlayMode) {
            findViewById<Button>(R.id.btnSettings).setOnClickListener {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            findViewById<Button>(R.id.btnHistory).setOnClickListener {
                historyLauncher.launch(Intent(this, HistoryActivity::class.java))
            }
            continuousSwitch?.isChecked = Prefs.continuousVoice(this)
            continuousSwitch?.setOnCheckedChangeListener { _, checked ->
                Prefs.setContinuousVoice(this, checked)
                if (checked) {
                    voiceDialog = true
                    Toast.makeText(this, "После ответа микрофон включится сам", Toast.LENGTH_SHORT).show()
                } else {
                    voiceDialog = false
                    stopListening()
                }
            }
            micBtn?.setOnClickListener {
                if (listening) stopListening() else {
                    voiceDialog = true
                    startListening()
                }
            }

            val restoreId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L)
            if (restoreId > 0) loadConversation(restoreId)
            else startNewConversation(showGreeting = true)

            requestAppPermissions()
            if (Prefs.hotwordEnabled(this)) HotwordService.syncWithPrefs(this)
        } else {
            startNewConversation(showGreeting = false)
            findViewById<View>(R.id.dim).setOnClickListener { finish() }
            input.post { focusInputAndShowKeyboard() }
            if (intent.getBooleanExtra(EXTRA_FROM_HOTWORD, false)) {
                Toast.makeText(this, "Слушаю, Джарвис на связи", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!overlayMode) continuousSwitch?.isChecked = Prefs.continuousVoice(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when {
            isOverlayIntent(intent) -> {
                if (!overlayMode) {
                    finish()
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .setAction(Intent.ACTION_ASSIST)
                            .putExtra(EXTRA_OVERLAY, true)
                            .putExtra(EXTRA_FROM_HOTWORD, intent.getBooleanExtra(EXTRA_FROM_HOTWORD, false))
                    )
                } else {
                    input.post { focusInputAndShowKeyboard() }
                }
            }
            overlayMode && intent.action == Intent.ACTION_MAIN -> {
                finish()
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                )
            }
        }
    }

    private fun requestAppPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun setupOverlayWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }

    private fun focusInputAndShowKeyboard() {
        input.isFocusable = true
        input.isFocusableInTouchMode = true
        input.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        input.postDelayed({
            if (!isFinishing && overlayMode) {
                input.requestFocus()
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 120)
    }

    private fun updateAttachPreview() {
        val att = pendingAttachment
        val tv = attachPreview ?: return
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
        conversationId = store.createConversation().id
        if (showGreeting && !overlayMode) {
            add(
                Msg(
                    false,
                    "Привет! Я ваш помощник. API-ключ — в настройках (⚙). " +
                        "История — ☰, файлы — 📎, непрерывный голос — тумблер."
                ),
                attachment = null
            )
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
                    msgs += Msg(
                        user = m.isUser,
                        text = m.text,
                        attachmentLabel = m.attachmentName,
                        dbId = m.id
                    )
                }
                adapter.notifyDataSetChanged()
                if (msgs.isNotEmpty()) list.scrollToPosition(msgs.size - 1)
            }
        }
    }

    private fun submitInput(fromVoice: Boolean) {
        val t = input.text.toString().trim()
        val att = pendingAttachment
        if (t.isEmpty() && att == null) return
        if (!fromVoice && !Prefs.continuousVoice(this)) voiceDialog = false
        if (fromVoice || Prefs.continuousVoice(this)) voiceDialog = true
        val text = t.ifBlank {
            if (att?.isImage == true) "Что на изображении?" else "Проанализируй файл."
        }
        send(text, att)
    }

    private fun add(m: Msg, attachment: PendingAttachment?) {
        msgs.add(m)
        if (overlayMode && list.visibility != View.VISIBLE) list.visibility = View.VISIBLE
        adapter.notifyItemInserted(msgs.size - 1)
        list.scrollToPosition(msgs.size - 1)
        if (conversationId > 0) {
            val convId = conversationId
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

    private fun send(text: String, attachment: PendingAttachment?) {
        input.setText("")
        val attachLabel = attachment?.name
        pendingAttachment = null
        updateAttachPreview()

        val userMsg = Msg(true, text, attachmentLabel = attachLabel)
        msgs.add(userMsg)
        if (overlayMode && list.visibility != View.VISIBLE) list.visibility = View.VISIBLE
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
                val cont = !overlayMode && (voiceDialog || Prefs.continuousVoice(this))
                if (Prefs.speak(this)) {
                    tts?.speak(
                        reply.replace("*", "").replace("#", ""),
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "reply"
                    )
                } else if (cont) {
                    voiceDialog = true
                    startListening()
                }
                if (overlayMode) focusInputAndShowKeyboard()
            }
        }
    }

    private fun setListening(v: Boolean) {
        listening = v
        micBtn?.text = if (v) "⏹" else "🎤"
    }

    private fun startListening() {
        if (overlayMode) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Разрешите микрофон", Toast.LENGTH_SHORT).show()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Нет службы распознавания речи (нужен Google)", Toast.LENGTH_LONG).show()
            return
        }
        tts?.stop()
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(voiceListener)
            }
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        recognizer?.startListening(i)
        setListening(true)
    }

    private val voiceListener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, p: Bundle?) {}
        override fun onPartialResults(p: Bundle?) {}
        override fun onError(error: Int) {
            setListening(false)
            if (!overlayMode && Prefs.continuousVoice(this@MainActivity) &&
                (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_NO_MATCH)
            ) {
                input.postDelayed({
                    if (Prefs.continuousVoice(this@MainActivity)) startListening()
                }, 400)
            } else if (!Prefs.continuousVoice(this@MainActivity)) {
                voiceDialog = false
            }
        }

        override fun onResults(r: Bundle?) {
            setListening(false)
            val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) {
                input.setText(t)
                submitInput(fromVoice = true)
            } else if (Prefs.continuousVoice(this@MainActivity)) {
                startListening()
            } else {
                voiceDialog = false
            }
        }
    }

    private fun stopListening() {
        if (!Prefs.continuousVoice(this)) voiceDialog = false
        recognizer?.stopListening()
        recognizer?.cancel()
        tts?.stop()
        setListening(false)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OVERLAY = "overlay"
        const val EXTRA_FROM_HOTWORD = "from_hotword"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_NEW_CHAT = "new_chat"

        fun isOverlayIntent(i: Intent?): Boolean {
            if (i == null) return false
            return i.action == Intent.ACTION_ASSIST ||
                i.getBooleanExtra(EXTRA_OVERLAY, false) ||
                i.getBooleanExtra("listen", false)
        }
    }
}
