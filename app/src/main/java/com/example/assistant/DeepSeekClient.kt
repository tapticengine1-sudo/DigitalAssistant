package com.example.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeepSeekClient(private val ctx: Context) {
    private val tools = Tools(ctx.applicationContext)
    private val history = JSONArray().apply { put(system()) }

    private fun system(): JSONObject {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("ru")).format(Date())
        return JSONObject().put("role", "system").put(
            "content",
            "Ты голосовой помощник на телефоне Android. Отвечай по-русски, коротко и без markdown " +
                "(ответы озвучиваются). Для действий на телефоне и поиска информации используй инструменты. " +
                "Если для актуальных данных нужен интернет — сначала ищи через web_search. " +
                "Сейчас: $now."
        )
    }

    private fun trim() {
        while (history.length() > 40) {
            history.remove(1)
            while (history.length() > 1 && history.getJSONObject(1).getString("role") != "user") {
                history.remove(1)
            }
        }
    }

    private fun post(key: String, body: JSONObject): JSONObject {
        val c = URL("https://api.deepseek.com/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        c.connectTimeout = 20000
        c.readTimeout = 90000
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IOException("HTTP $code: ${text.take(300)}")
        return JSONObject(text)
    }

    /**
     * @param userText текст пользователя
     * @param attachment опциональное вложение (изображение / документ)
     */
    fun ask(userText: String, attachment: PendingAttachment? = null): String {
        val key = Prefs.apiKey(ctx)
        if (key.isBlank()) return "Сначала введите DeepSeek API-ключ в настройках (⚙)."

        history.put(0, system())
        history.put(buildUserMessage(userText, attachment))
        trim()

        val useVision = attachment?.isImage == true && Prefs.multimodal(ctx)
        val model = if (useVision) Prefs.visionModel(ctx) else "deepseek-chat"
        // Инструменты обычно несовместимы с частью vision-моделей — отключаем при картинке
        val withTools = !useVision

        repeat(6) {
            val body = JSONObject()
                .put("model", model)
                .put("messages", history)
                .put("temperature", 0.7)
            if (withTools) body.put("tools", Tools.definitions())

            val msg = post(key, body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val content = if (msg.isNull("content")) "" else msg.optString("content", "")
            val calls = msg.optJSONArray("tool_calls")
            val assistant = JSONObject().put("role", "assistant").put("content", content)
            if (calls == null || calls.length() == 0 || !withTools) {
                history.put(assistant)
                return content.ifBlank { "Готово." }
            }
            assistant.put("tool_calls", calls)
            history.put(assistant)
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.getJSONObject("function")
                val args = try {
                    JSONObject(fn.optString("arguments", "{}").ifBlank { "{}" })
                } catch (_: Exception) {
                    JSONObject()
                }
                val result = tools.run(fn.getString("name"), args)
                history.put(
                    JSONObject().put("role", "tool")
                        .put("tool_call_id", call.getString("id"))
                        .put("content", result)
                )
            }
        }
        return "Не удалось завершить действие."
    }

    private fun buildUserMessage(userText: String, attachment: PendingAttachment?): JSONObject {
        if (attachment == null) {
            return JSONObject().put("role", "user").put("content", userText)
        }

        if (attachment.isImage && Prefs.multimodal(ctx)) {
            android.util.Log.d("DeepSeekClient", "Processing image attachment: ${attachment.name}, URI: ${attachment.uri}")
            val b64 = AttachmentReader.imageBase64Jpeg(ctx, attachment.uri)
            if (b64 != null && b64.isNotEmpty()) {
                android.util.Log.d("DeepSeekClient", "Successfully encoded image to base64, length: ${b64.length}")
                val parts = JSONArray()
                    .put(
                        JSONObject().put("type", "text").put(
                            "text",
                            userText.ifBlank { "Опиши изображение и ответь по сути." }
                        )
                    )
                    .put(
                        JSONObject().put("type", "image_url").put(
                            "image_url",
                            JSONObject().put("url", "data:image/jpeg;base64,$b64")
                        )
                    )
                return JSONObject().put("role", "user").put("content", parts)
            } else {
                android.util.Log.e("DeepSeekClient", "Failed to encode image to base64")
            }
        }

        // Текст / документ или multimodal выключен: встраиваем превью в текст
        val extra = when {
            attachment.isImage ->
                "\n\n[Прикреплено изображение: ${attachment.name}. " +
                    "Мультимодальный режим выключен или не удалось прочитать файл — опиши, что нужно сделать.]"
            attachment.mime.startsWith("text/") ||
                attachment.name.endsWith(".txt", true) ||
                attachment.name.endsWith(".md", true) ||
                attachment.name.endsWith(".json", true) ||
                attachment.name.endsWith(".csv", true) -> {
                val preview = AttachmentReader.readTextPreview(ctx, attachment.uri)
                if (preview != null)
                    "\n\n[Файл «${attachment.name}»]:\n$preview"
                else
                    "\n\n[Прикреплён файл «${attachment.name}», не удалось прочитать.]"
            }
            else ->
                "\n\n[Прикреплён файл «${attachment.name}» (${attachment.mime}). " +
                    "Содержимое бинарное — учитывай только имя/тип, если пользователь не описал суть.]"
        }
        return JSONObject().put("role", "user").put("content", userText + extra)
    }

    fun clearSessionHistory() {
        while (history.length() > 0) history.remove(0)
        history.put(system())
    }

    /** Восстановить контекст модели из сохранённых сообщений (без tool-calls). */
    fun seedFromMessages(messages: List<StoredMessage>) {
        clearSessionHistory()
        for (m in messages) {
            if (m.text.isBlank()) continue
            history.put(
                JSONObject()
                    .put("role", if (m.isUser) "user" else "assistant")
                    .put("content", m.text)
            )
        }
        trim()
    }
}
