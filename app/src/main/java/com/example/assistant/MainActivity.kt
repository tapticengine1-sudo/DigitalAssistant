package com.example.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
// Bundle used by RecognitionListener callbacks
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import com.example.assistant.hotword.HotwordService
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val msgs = mutableListOf<Msg>()
    private lateinit var adapter: ChatAdapter
    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var micBtn: Button
    private lateinit var voiceModeBtn: Button
    private lateinit var voiceModeBar: LinearLayout
    private lateinit var voiceModeStatus: TextView
    private var attachPreview: TextView? = null
    private lateinit var client: DeepSeekClient
    private lateinit var store: ChatStore
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null

    private var listening = false
    /** Непрерывный Voice Mode (цикл STT → API → TTS → STT). */
    private var voiceMode = false
    /** Одиночный захват с кнопки 🎤 (не Voice Mode). */
    private var oneShotVoice = false
    private var conversationId: Long = -1L
    private var pendingAttachment: PendingAttachment? = null
    private var releasingRecognizer = false

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
        voiceModeBtn = findViewById(R.id.btnVoiceMode)
        voiceModeBar = findViewById(R.id.voiceModeBar)
        voiceModeStatus = findViewById(R.id.voiceModeStatus)
        attachPreview = findViewById(R.id.attachPreview)
        adapter = ChatAdapter(msgs)
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ru", "RU")
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                if (voiceMode) runOnUiThread { setVoiceStatus("Voice Mode · говорю…") }
            }
            override fun onError(id: String?) {
                if (voiceMode) runOnUiThread {
                    setVoiceStatus("Voice Mode · слушаю…")
                    startListeningInternal()
                }
            }
            override fun onDone(id: String?) {
                if (!voiceMode) return
                runOnUiThread {
                    setVoiceStatus("Voice Mode · слушаю…")
                    startListeningInternal()
                }
            }
        })

        findViewById<Button>(R.id.btnAttach).setOnClickListener {
            pickFile.launch(arrayOf("image/*", "text/*", "application/pdf", "application/json", "*/*"))
        }
        attachPreview?.setOnClickListener {
            pendingAttachment = null
            updateAttachPreview()
        }
        findViewById<Button>(R.id.btnSend).setOnClickListener { submitInput(fromVoice = false) }
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

        voiceModeBtn.setOnClickListener {
            if (voiceMode) stopVoiceMode() else startVoiceMode()
        }
        findViewById<Button>(R.id.btnStopVoice).setOnClickListener { stopVoiceMode() }

        micBtn.setOnClickListener {
            if (voiceMode) {
                Toast.makeText(this, "Сначала остановите Voice Mode", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (listening) {
                cancelListening(destroy = true)
                oneShotVoice = false
                HotwordService.resume(this)
            } else {
                oneShotVoice = true
                HotwordService.pause(this)
                startListeningInternal()
            }
        }

        val restoreId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L)
        if (restoreId > 0) loadConversation(restoreId)
        else startNewConversation(showGreeting = true)

        requestAppPermissions()
        if (Prefs.hotwordEnabled(this)) HotwordService.syncWithPrefs(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isAssistHandoff(intent)) {
            if (voiceMode) stopVoiceMode()
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
        requestPermissions(perms.toTypedArray(), 1)
    }

    // --- Voice Mode ---

    private fun startVoiceMode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Разрешите микрофон", Toast.LENGTH_SHORT).show()
            return
        }
        voiceMode = true
        oneShotVoice = false
        HotwordService.pause(this)
        voiceModeBar.visibility = View.VISIBLE
        voiceModeBtn.alpha = 0.45f
        setVoiceStatus("Voice Mode · слушаю…")
        destroyRecognizer()
        startListeningInternal()
        Toast.makeText(this, "Voice Mode: говорите, Стоп — выход", Toast.LENGTH_SHORT).show()
    }

    private fun stopVoiceMode() {
        voiceMode = false
        oneShotVoice = false
        voiceModeBar.visibility = View.GONE
        voiceModeBtn.alpha = 1f
        setVoiceStatus("Voice Mode")
        tts?.stop()
        destroyRecognizer()
        setListeningUi(false)
        HotwordService.resume(this)
    }

    private fun setVoiceStatus(text: String) {
        voiceModeStatus.text = text
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
                if (showGreeting) {
                    addUiAndPersist(
                        Msg(
                            false,
                            "Привет! История — ☰, файлы — 📎, 🎙 — Voice Mode, 🎤 — одна фраза."
                        ),
                        null
                    )
                }
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
                    msgs += Msg(m.isUser, m.text, m.attachmentName, m.id)
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
        if (!fromVoice && !voiceMode) oneShotVoice = false
        val text = t.ifBlank {
            if (att?.isImage == true) "Что на изображении?" else "Проанализируй файл."
        }
        send(text, att)
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

    private fun send(text: String, attachment: PendingAttachment?) {
        input.setText("")
        val attachLabel = attachment?.name
        pendingAttachment = null
        updateAttachPreview()

        val userMsg = Msg(true, text, attachmentLabel = attachLabel)
        msgs.add(userMsg)
        adapter.notifyItemInserted(msgs.size - 1)

        val pending = Msg(false, "…")
        msgs.add(pending)
        adapter.notifyItemInserted(msgs.size - 1)
        list.scrollToPosition(msgs.size - 1)
        val pendingIndex = msgs.lastIndex
        val convId = conversationId

        if (voiceMode) setVoiceStatus("Voice Mode · думаю…")

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

                val speak = Prefs.speak(this) || voiceMode
                if (speak) {
                    if (voiceMode) setVoiceStatus("Voice Mode · говорю…")
                    tts?.speak(
                        reply.replace("*", "").replace("#", ""),
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "reply"
                    )
                    // Voice Mode: следующий STT в onDone TTS
                    // one-shot: не продолжаем
                    if (!voiceMode) oneShotVoice = false
                } else if (voiceMode) {
                    setVoiceStatus("Voice Mode · слушаю…")
                    startListeningInternal()
                } else {
                    oneShotVoice = false
                }
            }
        }
    }

    private fun setListeningUi(v: Boolean) {
        listening = v
        micBtn.text = if (v) "⏹" else "🎤"
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (releasingRecognizer) return null
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Нет службы распознавания речи (нужен Google)", Toast.LENGTH_LONG).show()
            return null
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(voiceListener)
            }
        }
        return recognizer
    }

    private fun destroyRecognizer() {
        releasingRecognizer = true
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        listening = false
        releasingRecognizer = false
    }

    private fun cancelListening(destroy: Boolean) {
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        if (destroy) destroyRecognizer()
        setListeningUi(false)
    }

    private fun startListeningInternal() {
        if (releasingRecognizer) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Разрешите микрофон", Toast.LENGTH_SHORT).show()
            if (voiceMode) stopVoiceMode()
            return
        }
        tts?.stop()
        if (voiceMode || oneShotVoice) HotwordService.pause(this)
        val r = ensureRecognizer() ?: run {
            if (voiceMode) {
                input.postDelayed({ if (voiceMode) startListeningInternal() }, 600)
            }
            return
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        try {
            r.startListening(i)
            setListeningUi(true)
            if (voiceMode) setVoiceStatus("Voice Mode · слушаю…")
        } catch (_: Exception) {
            destroyRecognizer()
            setListeningUi(false)
            if (voiceMode) {
                input.postDelayed({ if (voiceMode) startListeningInternal() }, 700)
            }
        }
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
            setListeningUi(false)
            if (releasingRecognizer) return
            when {
                voiceMode &&
                    (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_CLIENT) -> {
                    // В Voice Mode тихо перезапускаем слух
                    destroyRecognizer()
                    input.postDelayed({ if (voiceMode) startListeningInternal() }, 450)
                }
                voiceMode && error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    destroyRecognizer()
                    input.postDelayed({ if (voiceMode) startListeningInternal() }, 800)
                }
                voiceMode && error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Toast.makeText(this@MainActivity, "Нет доступа к микрофону", Toast.LENGTH_SHORT).show()
                    stopVoiceMode()
                }
                else -> {
                    oneShotVoice = false
                    destroyRecognizer()
                    if (!voiceMode) HotwordService.resume(this@MainActivity)
                }
            }
        }

        override fun onResults(r: Bundle?) {
            setListeningUi(false)
            val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) {
                input.setText(t)
                if (voiceMode) {
                    // Цикл: сразу в API; STT освободим перед следующим кругом после TTS
                    destroyRecognizer()
                    submitInput(fromVoice = true)
                } else {
                    oneShotVoice = false
                    destroyRecognizer()
                    HotwordService.resume(this@MainActivity)
                    submitInput(fromVoice = true)
                }
            } else if (voiceMode) {
                destroyRecognizer()
                input.postDelayed({ if (voiceMode) startListeningInternal() }, 350)
            } else {
                oneShotVoice = false
                destroyRecognizer()
                HotwordService.resume(this@MainActivity)
            }
        }
    }

    override fun onDestroy() {
        voiceMode = false
        destroyRecognizer()
        tts?.shutdown()
        HotwordService.resume(this)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OVERLAY = "overlay"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_NEW_CHAT = "new_chat"
    }
}
