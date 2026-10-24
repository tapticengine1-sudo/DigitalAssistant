package com.example.assistant

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class HistoryActivity : AppCompatActivity() {

    private lateinit var store: ChatStore
    private lateinit var list: RecyclerView
    private lateinit var hint: TextView
    private lateinit var search: EditText
    private lateinit var adapter: HistoryAdapter
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var refreshGen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        store = ChatStore.get(this)
        list = findViewById(R.id.list)
        hint = findViewById(R.id.hint)
        search = findViewById(R.id.search)

        adapter = HistoryAdapter(
            onClick = { row ->
                when (row) {
                    is HistoryRow.ConversationItem -> openConversation(row.conversation.id)
                    is HistoryRow.SearchItem -> openConversation(row.hit.message.conversationId)
                }
            },
            onLongClick = { row ->
                if (row is HistoryRow.ConversationItem) confirmDelete(row.conversation)
            }
        )
        list.itemAnimator = null
        list.layoutManager = LinearLayoutManager(this)
        list.setHasFixedSize(false)
        list.adapter = adapter

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnNewChat).setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra(MainActivity.EXTRA_NEW_CHAT, true))
            finish()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refresh(s?.toString().orEmpty())
            }
        })
    }

    override fun onResume() {
        super.onResume()
        refresh(search.text?.toString().orEmpty())
    }

    override fun onDestroy() {
        refreshGen++
        io.shutdownNow()
        super.onDestroy()
    }

    private fun refresh(query: String) {
        val gen = ++refreshGen
        val q = query.trim()
        io.execute {
            val data = try {
                if (q.isEmpty()) {
                    store.listConversations().map { HistoryRow.ConversationItem(it) }
                } else {
                    store.search(q).map { HistoryRow.SearchItem(it) }
                }
            } catch (_: Exception) {
                emptyList()
            }
            main.post {
                if (isFinishing || isDestroyed || gen != refreshGen) return@post
                if (!::adapter.isInitialized || !::list.isInitialized) return@post
                hint.text = if (q.isEmpty()) "Диалоги" else "Найдено: ${data.size}"
                adapter.replace(data)
            }
        }
    }

    private fun openConversation(id: Long) {
        setResult(RESULT_OK, Intent().putExtra(MainActivity.EXTRA_CONVERSATION_ID, id))
        finish()
    }

    private fun confirmDelete(c: Conversation) {
        AlertDialog.Builder(this)
            .setTitle("Удалить диалог?")
            .setMessage(c.title.ifBlank { "Диалог" })
            .setPositiveButton("Удалить") { _, _ ->
                val genHint = search.text?.toString().orEmpty()
                io.execute {
                    try {
                        store.deleteConversation(c.id)
                    } catch (_: Exception) {
                    }
                    main.post {
                        if (!isFinishing && !isDestroyed) refresh(genHint)
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private sealed class HistoryRow {
        data class ConversationItem(val conversation: Conversation) : HistoryRow()
        data class SearchItem(val hit: SearchHit) : HistoryRow()
    }

    private class HistoryAdapter(
        private val onClick: (HistoryRow) -> Unit,
        private val onLongClick: (HistoryRow) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        private val items = mutableListOf<HistoryRow>()
        private val fmt = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))

        fun replace(data: List<HistoryRow>) {
            items.clear()
            items.addAll(data)
            notifyDataSetChanged()
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.title)
            val subtitle: TextView = view.findViewById(R.id.subtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            if (position < 0 || position >= items.size) return
            val row = items[position] ?: return
            when (row) {
                is HistoryRow.ConversationItem -> {
                    val title = row.conversation.title
                    holder.title.text = if (title.isNullOrBlank()) "Диалог" else title
                    holder.subtitle.text = try {
                        fmt.format(Date(row.conversation.updatedAt))
                    } catch (_: Exception) {
                        ""
                    }
                }
                is HistoryRow.SearchItem -> {
                    val m = row.hit.message
                    val who = if (m.isUser) "Вы" else "Ассистент"
                    val body = m.text.orEmpty().take(100)
                    holder.title.text = "$who: $body"
                    val whenStr = try {
                        fmt.format(Date(m.createdAt))
                    } catch (_: Exception) {
                        ""
                    }
                    val convTitle = row.hit.conversationTitle.ifBlank { "Диалог" }
                    holder.subtitle.text = "$convTitle · $whenStr"
                }
            }
            holder.itemView.setOnClickListener { onClick(row) }
            holder.itemView.setOnLongClickListener {
                onLongClick(row)
                true
            }
        }

        override fun getItemCount() = items.size
    }
}
