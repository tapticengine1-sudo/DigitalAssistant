package com.example.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Conversation(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class StoredMessage(
    val id: Long = 0,
    val conversationId: Long,
    val isUser: Boolean,
    val text: String,
    val attachmentUri: String? = null,
    val attachmentMime: String? = null,
    val attachmentName: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class SearchHit(
    val message: StoredMessage,
    val conversationTitle: String
)

/** Локальная SQLite-база диалогов и сообщений. */
class ChatStore private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "assistant_chats.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id INTEGER NOT NULL,
                is_user INTEGER NOT NULL,
                text TEXT NOT NULL,
                attachment_uri TEXT,
                attachment_mime TEXT,
                attachment_name TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_msg_conv ON messages(conversation_id)")
        db.execSQL("CREATE INDEX idx_msg_text ON messages(text)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    fun createConversation(title: String = "Новый диалог"): Conversation {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insert(
            "conversations", null,
            ContentValues().apply {
                put("title", title)
                put("created_at", now)
                put("updated_at", now)
            }
        )
        return Conversation(id, title, now, now)
    }

    fun getConversation(id: Long): Conversation? {
        readableDatabase.query(
            "conversations", null, "id=?", arrayOf(id.toString()), null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return Conversation(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("title")),
                c.getLong(c.getColumnIndexOrThrow("created_at")),
                c.getLong(c.getColumnIndexOrThrow("updated_at"))
            )
        }
    }

    fun listConversations(): List<Conversation> {
        val out = mutableListOf<Conversation>()
        readableDatabase.query(
            "conversations", null, null, null, null, null, "updated_at DESC"
        ).use { c ->
            while (c.moveToNext()) {
                out += Conversation(
                    c.getLong(c.getColumnIndexOrThrow("id")),
                    c.getString(c.getColumnIndexOrThrow("title")),
                    c.getLong(c.getColumnIndexOrThrow("created_at")),
                    c.getLong(c.getColumnIndexOrThrow("updated_at"))
                )
            }
        }
        return out
    }

    fun deleteConversation(id: Long) {
        writableDatabase.delete("conversations", "id=?", arrayOf(id.toString()))
    }

    fun renameConversation(id: Long, title: String) {
        writableDatabase.update(
            "conversations",
            ContentValues().apply {
                put("title", title.take(80))
                put("updated_at", System.currentTimeMillis())
            },
            "id=?", arrayOf(id.toString())
        )
    }

    fun addMessage(msg: StoredMessage): Long {
        val db = writableDatabase
        val id = db.insert(
            "messages", null,
            ContentValues().apply {
                put("conversation_id", msg.conversationId)
                put("is_user", if (msg.isUser) 1 else 0)
                put("text", msg.text)
                put("attachment_uri", msg.attachmentUri)
                put("attachment_mime", msg.attachmentMime)
                put("attachment_name", msg.attachmentName)
                put("created_at", msg.createdAt)
            }
        )
        db.update(
            "conversations",
            ContentValues().apply { put("updated_at", msg.createdAt) },
            "id=?", arrayOf(msg.conversationId.toString())
        )
        // Заголовок из первого пользовательского сообщения
        if (msg.isUser) {
            val conv = getConversation(msg.conversationId)
            if (conv != null && (conv.title == "Новый диалог" || conv.title.isBlank())) {
                renameConversation(msg.conversationId, msg.text.trim().take(60).ifBlank { "Диалог" })
            }
        }
        return id
    }

    fun updateMessageText(id: Long, text: String) {
        writableDatabase.update(
            "messages",
            ContentValues().apply { put("text", text) },
            "id=?", arrayOf(id.toString())
        )
    }

    fun messagesFor(conversationId: Long): List<StoredMessage> {
        val out = mutableListOf<StoredMessage>()
        readableDatabase.query(
            "messages", null, "conversation_id=?", arrayOf(conversationId.toString()),
            null, null, "created_at ASC"
        ).use { c ->
            while (c.moveToNext()) out += readMessage(c)
        }
        return out
    }

    fun search(query: String): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<SearchHit>()
        val sql = """
            SELECT m.*, c.title AS conv_title FROM messages m
            JOIN conversations c ON c.id = m.conversation_id
            WHERE m.text LIKE ? COLLATE NOCASE
            ORDER BY m.created_at DESC
            LIMIT 200
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf("%$q%")).use { c ->
            val titleIdx = c.getColumnIndexOrThrow("conv_title")
            while (c.moveToNext()) {
                out += SearchHit(readMessage(c), c.getString(titleIdx))
            }
        }
        return out
    }

    private fun readMessage(c: android.database.Cursor): StoredMessage {
        fun str(col: String): String? {
            val i = c.getColumnIndexOrThrow(col)
            return if (c.isNull(i)) null else c.getString(i)
        }
        return StoredMessage(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            conversationId = c.getLong(c.getColumnIndexOrThrow("conversation_id")),
            isUser = c.getInt(c.getColumnIndexOrThrow("is_user")) == 1,
            text = c.getString(c.getColumnIndexOrThrow("text")),
            attachmentUri = str("attachment_uri"),
            attachmentMime = str("attachment_mime"),
            attachmentName = str("attachment_name"),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
        )
    }

    companion object {
        @Volatile private var instance: ChatStore? = null
        fun get(ctx: Context): ChatStore =
            instance ?: synchronized(this) {
                instance ?: ChatStore(ctx).also { instance = it }
            }
    }
}
