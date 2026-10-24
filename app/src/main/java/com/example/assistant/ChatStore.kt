package com.example.assistant

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
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

/** Локальная SQLite-база диалогов и сообщений. Все публичные методы синхронизированы. */
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

    @Synchronized
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

    @Synchronized
    fun getConversation(id: Long): Conversation? {
        return try {
            readableDatabase.query(
                "conversations", null, "id=?", arrayOf(id.toString()), null, null, null
            ).use { c ->
                if (!c.moveToFirst()) return null
                readConversation(c)
            }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun listConversations(): List<Conversation> {
        val out = mutableListOf<Conversation>()
        try {
            readableDatabase.query(
                "conversations", null, null, null, null, null, "updated_at DESC"
            ).use { c ->
                while (c.moveToNext()) {
                    readConversation(c)?.let { out += it }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    @Synchronized
    fun deleteConversation(id: Long) {
        try {
            writableDatabase.delete("conversations", "id=?", arrayOf(id.toString()))
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun renameConversation(id: Long, title: String) {
        try {
            writableDatabase.update(
                "conversations",
                ContentValues().apply {
                    put("title", title.take(80))
                    put("updated_at", System.currentTimeMillis())
                },
                "id=?", arrayOf(id.toString())
            )
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun addMessage(msg: StoredMessage): Long {
        return try {
            val db = writableDatabase
            val id = db.insert(
                "messages", null,
                ContentValues().apply {
                    put("conversation_id", msg.conversationId)
                    put("is_user", if (msg.isUser) 1 else 0)
                    put("text", msg.text ?: "")
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
            if (msg.isUser) {
                val conv = getConversationUnlocked(msg.conversationId)
                if (conv != null && (conv.title == "Новый диалог" || conv.title.isBlank())) {
                    renameUnlocked(
                        msg.conversationId,
                        msg.text.trim().take(60).ifBlank { "Диалог" }
                    )
                }
            }
            id
        } catch (_: Exception) {
            -1L
        }
    }

    @Synchronized
    fun updateMessageText(id: Long, text: String) {
        try {
            writableDatabase.update(
                "messages",
                ContentValues().apply { put("text", text) },
                "id=?", arrayOf(id.toString())
            )
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun messagesFor(conversationId: Long): List<StoredMessage> {
        val out = mutableListOf<StoredMessage>()
        try {
            readableDatabase.query(
                "messages", null, "conversation_id=?", arrayOf(conversationId.toString()),
                null, null, "created_at ASC"
            ).use { c ->
                while (c.moveToNext()) readMessage(c)?.let { out += it }
            }
        } catch (_: Exception) {
        }
        return out
    }

    @Synchronized
    fun search(query: String): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<SearchHit>()
        try {
            val sql = """
                SELECT m.*, c.title AS conv_title FROM messages m
                JOIN conversations c ON c.id = m.conversation_id
                WHERE m.text LIKE ? COLLATE NOCASE
                ORDER BY m.created_at DESC
                LIMIT 200
            """.trimIndent()
            readableDatabase.rawQuery(sql, arrayOf("%$q%")).use { c ->
                val titleIdx = c.getColumnIndex("conv_title")
                while (c.moveToNext()) {
                    val msg = readMessage(c) ?: continue
                    val title = if (titleIdx >= 0) c.getString(titleIdx) ?: "Диалог" else "Диалог"
                    out += SearchHit(msg, title)
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Уже под @Synchronized addMessage. */
    private fun getConversationUnlocked(id: Long): Conversation? {
        return try {
            readableDatabase.query(
                "conversations", null, "id=?", arrayOf(id.toString()), null, null, null
            ).use { c ->
                if (!c.moveToFirst()) return null
                readConversation(c)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun renameUnlocked(id: Long, title: String) {
        try {
            writableDatabase.update(
                "conversations",
                ContentValues().apply {
                    put("title", title.take(80))
                    put("updated_at", System.currentTimeMillis())
                },
                "id=?", arrayOf(id.toString())
            )
        } catch (_: Exception) {
        }
    }

    private fun readConversation(c: Cursor): Conversation? {
        return try {
            val idIdx = c.getColumnIndex("id")
            val titleIdx = c.getColumnIndex("title")
            val createdIdx = c.getColumnIndex("created_at")
            val updatedIdx = c.getColumnIndex("updated_at")
            if (idIdx < 0 || titleIdx < 0 || createdIdx < 0 || updatedIdx < 0) return null
            Conversation(
                id = c.getLong(idIdx),
                title = c.getString(titleIdx) ?: "Диалог",
                createdAt = c.getLong(createdIdx),
                updatedAt = c.getLong(updatedIdx)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun readMessage(c: Cursor): StoredMessage? {
        return try {
            fun str(col: String): String? {
                val i = c.getColumnIndex(col)
                if (i < 0 || c.isNull(i)) return null
                return c.getString(i)
            }
            fun longCol(col: String, default: Long = 0L): Long {
                val i = c.getColumnIndex(col)
                if (i < 0 || c.isNull(i)) return default
                return c.getLong(i)
            }
            fun intCol(col: String, default: Int = 0): Int {
                val i = c.getColumnIndex(col)
                if (i < 0 || c.isNull(i)) return default
                return c.getInt(i)
            }
            StoredMessage(
                id = longCol("id"),
                conversationId = longCol("conversation_id"),
                isUser = intCol("is_user") == 1,
                text = str("text") ?: "",
                attachmentUri = str("attachment_uri"),
                attachmentMime = str("attachment_mime"),
                attachmentName = str("attachment_name"),
                createdAt = longCol("created_at", System.currentTimeMillis())
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        @Volatile private var instance: ChatStore? = null
        fun get(ctx: Context): ChatStore =
            instance ?: synchronized(this) {
                instance ?: ChatStore(ctx).also { instance = it }
            }
    }
}
