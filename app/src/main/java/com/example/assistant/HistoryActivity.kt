package com.example.assistant

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class HistoryActivity : AppCompatActivity() {

    private lateinit var store: ChatStore
    private lateinit var list: RecyclerView
    private lateinit var hint: TextView
    private lateinit var search: EditText
    private val rows = mutableListOf<HistoryRow>()
    private lateinit var adapter: HistoryAdapter
    private val fmt = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        store = ChatStore.get(this)
        list = findViewById(R.id.list)
        hint = findViewById(R.id.hint)
        search = findViewById(R.id.search)
        adapter = HistoryAdapter(rows,
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
        list.layoutManager = LinearLayoutManager(this)
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

    private fun refresh(query: String) {
        thread {
            val q = query.trim()
            val data = if (q.isEmpty()) {
                store.listConversations().map { HistoryRow.ConversationItem(it) }
            } else {
                store.search(q).map { HistoryRow.SearchItem(it) }
            }
            runOnUiThread {
                hint.text = if (q.isEmpty()) "Диалоги" else "Найдено: ${data.size}"
                rows.clear()
                rows.addAll(data)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun openConversation(id: Long) {
        setResult(
            RESULT_OK,
            Intent().putExtra(MainActivity.EXTRA_CONVERSATION_ID, id)
        )
        finish()
    }

    private fun confirmDelete(c: Conversation) {
        AlertDialog.Builder(this)
            .setTitle("Удалить диалог?")
            .setMessage(c.title)
            .setPositiveButton("Удалить") { _, _ ->
                thread {
                    store.deleteConversation(c.id)
                    runOnUiThread { refresh(search.text?.toString().orEmpty()) }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private sealed class HistoryRow {
        data class ConversationItem(val conversation: Conversation) : HistoryRow()
        data class SearchItem(val hit: SearchHit) : HistoryRow()
    }

    private inner class HistoryAdapter(
        private val items: List<HistoryRow>,
        private val onClick: (HistoryRow) -> Unit,
        private val onLongClick: (HistoryRow) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        inner class VH(val root: LinearLayout, val title: TextView, val sub: TextView) :
            RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false) as LinearLayout
            return VH(
                root,
                root.findViewById(android.R.id.text1),
                root.findViewById(android.R.id.text2)
            )
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            when (row) {
                is HistoryRow.ConversationItem -> {
                    holder.title.text = row.conversation.title
                    holder.sub.text = fmt.format(Date(row.conversation.updatedAt))
                }
                is HistoryRow.SearchItem -> {
                    val m = row.hit.message
                    val who = if (m.isUser) "Вы" else "Ассистент"
                    holder.title.text = "$who: ${m.text.take(100)}"
                    holder.sub.text = "${row.hit.conversationTitle} · ${fmt.format(Date(m.createdAt))}"
                }
            }
            holder.root.setOnClickListener { onClick(row) }
            holder.root.setOnLongClickListener {
                onLongClick(row)
                true
            }
        }

        override fun getItemCount() = items.size
    }
}
