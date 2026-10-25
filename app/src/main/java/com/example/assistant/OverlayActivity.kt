package com.example.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
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
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import kotlin.concurrent.thread

/**
 * Компактная полупрозрачная шторка поверх текущего приложения / lock screen.
 * Не использует SpeechRecognizer (error 10 в оболочке Assist).
 */
class OverlayActivity : AppCompatActivity() {

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
    private var conversationId: Long = -1L
    private var pendingAttachment: PendingAttachment? = null
    private var holdToTalk: HoldToTalk? = null
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
            }
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
        val att = AttachmentReader.fromUri(this, uri) ?: run {
            Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        pendingAttachment = att
        updateAttachPreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Assistant_Overlay)
        super.onCreate(savedInstanceState)
        applyLockScreenFlags()
        setupOverlayWindow()
        setContentView(R.layout.activity_assist)

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
                // Callback не используется для автоотправки
            },
            onRmsChanged = { rms ->
                runOnUiThread { waveformView.setAmplitude(rms) }
            }
        )
        holdToTalk?.attach()

        thread {
            val id = try {
                store.createConversation("Оверлей").id
            } catch (_: Exception) {
                -1L
            }
            runOnUiThread { conversationId = id }
        }

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
                    submitWithVoiceFlag(true)
                }
                // Пересоздаем HoldToTalk после отправки
                recreateHoldToTalk()
            } else {
                submit() 
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit(); true
            } else false
        }

        // Автоматический запуск микрофона при вызове оверлея
        if (intent.getBooleanExtra(EXTRA_FROM_HOTWORD, false) || intent.getBooleanExtra(EXTRA_START_VOICE, false)) {
            input.postDelayed({
                holdToTalk?.startListeningAuto()
            }, 300)
        } else {
            input.post { focusInputAndShowKeyboard() }
        }
    }

    override fun onDestroy() {
        holdToTalk?.destroy()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLockScreenFlags()
        input.post { focusInputAndShowKeyboard() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            finish()
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun applyLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setupOverlayWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        val wlp = window.attributes
        wlp.gravity = Gravity.BOTTOM
        wlp.width = WindowManager.LayoutParams.MATCH_PARENT
        wlp.height = WindowManager.LayoutParams.WRAP_CONTENT
        wlp.dimAmount = 0.4f
        wlp.flags = wlp.flags or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (Build.VERSION.SDK_INT < 27) {
            @Suppress("DEPRECATION")
            wlp.flags = wlp.flags or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        }
        window.attributes = wlp
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        window.setGravity(Gravity.BOTTOM)
    }

    private fun focusInputAndShowKeyboard() {
        input.isFocusable = true
        input.isFocusableInTouchMode = true
        input.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        input.postDelayed({
            if (!isFinishing) {
                input.requestFocus()
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 150)
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

    private fun updateVoiceUI(listening: Boolean) {
        isVoiceInput = listening
        if (listening) {
            micBtn.text = "⏸"
            sendBtn.text = "➤"
            sendBtn.visibility = View.VISIBLE
            waveformView.visibility = View.VISIBLE
        } else {
            micBtn.text = "🎤"
            sendBtn.text = "➤"
            waveformView.visibility = View.GONE
            waveformView.reset()
        }
    }
    
    private fun recreateHoldToTalk() {
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
                // Не используется
            },
            onRmsChanged = { rms ->
                runOnUiThread { waveformView.setAmplitude(rms) }
            }
        )
        holdToTalk?.attach()
    }
    
    private fun submitWithVoiceFlag(fromVoice: Boolean) {
        val t = input.text.toString().trim()
        val att = pendingAttachment
        if (t.isEmpty() && att == null) return
        val text = t.ifBlank {
            if (att?.isImage == true) "Что на изображении?" else "Проанализируй файл."
        }
        send(text, att)
        // В оверлее пока не озвучиваем, но флаг можно использовать позже
    }

    private fun submit() {
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
        val label = attachment?.name
        val wasVoice = isVoiceInput
        isVoiceInput = false
        pendingAttachment = null
        updateAttachPreview()
        updateVoiceUI(false)

        val userMsg = Msg(true, text, attachmentLabel = label, attachmentUri = attachment?.uri)
        msgs.add(userMsg)
        list.visibility = View.VISIBLE
        adapter.notifyItemInserted(msgs.lastIndex)

        val pending = Msg(false, "…")
        msgs.add(pending)
        adapter.notifyItemInserted(msgs.lastIndex)
        list.scrollToPosition(msgs.lastIndex)
        val pendingIndex = msgs.lastIndex
        val convId = conversationId

        thread {
            if (convId > 0) {
                store.addMessage(
                    StoredMessage(
                        conversationId = convId,
                        isUser = true,
                        text = text,
                        attachmentUri = attachment?.uri?.toString(),
                        attachmentMime = attachment?.mime,
                        attachmentName = label
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
                list.scrollToPosition(msgs.lastIndex)
                focusInputAndShowKeyboard()
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
        const val EXTRA_FROM_HOTWORD = "from_hotword"
        const val EXTRA_START_VOICE = "start_voice"
    }
}
