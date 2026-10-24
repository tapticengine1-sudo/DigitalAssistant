package com.example.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
    private var attachPreview: TextView? = null
    private lateinit var client: DeepSeekClient
    private lateinit var store: ChatStore
    private var tts: TextToSpeech? = null
    private var holdToTalk: HoldToTalk? = null
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
                micBtn.alpha = if (listening) 0.5f else 1f
            },
            onTranscript = { text ->
                input.setText(text)
                submitInput(fromVoice = true)
            }
        )
        holdToTalk?.attach()

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
        requestPermissions(perms.toTypedArray(), 1)
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

                if (Prefs.speak(this)) {
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



    override fun onDestroy() {
        holdToTalk?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OVERLAY = "overlay"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_NEW_CHAT = "new_chat"
    }
}
