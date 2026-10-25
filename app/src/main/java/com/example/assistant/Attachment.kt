package com.example.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream

data class PendingAttachment(
    val uri: Uri,
    val mime: String,
    val name: String,
    val isImage: Boolean
)

object AttachmentReader {

    fun fromUri(ctx: Context, uri: Uri): PendingAttachment? {
        return try {
            val cr = ctx.contentResolver
            var mime = cr.getType(uri)
            
            // Для file:// URI пытаемся определить MIME по расширению
            if (mime == null && uri.scheme == "file") {
                val path = uri.path
                mime = when {
                    path?.endsWith(".jpg", true) == true || path?.endsWith(".jpeg", true) == true -> "image/jpeg"
                    path?.endsWith(".png", true) == true -> "image/png"
                    path?.endsWith(".gif", true) == true -> "image/gif"
                    path?.endsWith(".webp", true) == true -> "image/webp"
                    path?.endsWith(".txt", true) == true -> "text/plain"
                    path?.endsWith(".pdf", true) == true -> "application/pdf"
                    else -> "application/octet-stream"
                }
            }
            
            mime = mime ?: "application/octet-stream"
            
            var name = "file"
            try {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            name = c.getString(nameIndex) ?: name
                        }
                    }
                }
            } catch (e: Exception) {
                // Для file:// URI query может не работать, берем имя из пути
                android.util.Log.w("AttachmentReader", "Failed to query display name, using path", e)
                uri.path?.let { path ->
                    name = path.substringAfterLast('/')
                }
            }
            
            val isImage = mime.startsWith("image/")
            android.util.Log.d("AttachmentReader", "Created attachment: uri=$uri, mime=$mime, name=$name, isImage=$isImage")
            PendingAttachment(uri, mime, name, isImage)
        } catch (e: Exception) {
            android.util.Log.e("AttachmentReader", "Failed to create PendingAttachment from URI: $uri", e)
            null
        }
    }

    /** Сжимает изображение и кодирует в base64 JPEG (для multimodal API). */
    fun imageBase64Jpeg(ctx: Context, uri: Uri, maxSide: Int = 1280, quality: Int = 85): String? {
        return try {
            // Пробуем сначала через contentResolver (для content:// URI)
            var bitmap: Bitmap? = null
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    bitmap = BitmapFactory.decodeStream(input)
                }
            } catch (e: Exception) {
                android.util.Log.w("AttachmentReader", "Failed to load via contentResolver, trying file path", e)
            }
            
            // Если не получилось, пробуем прямой путь (для file:// URI)
            if (bitmap == null) {
                try {
                    val path = uri.path
                    if (path != null) {
                        bitmap = BitmapFactory.decodeFile(path)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AttachmentReader", "Failed to load via file path", e)
                }
            }
            
            val original = bitmap ?: return null
            
            val scaled = scale(original, maxSide)
            try {
                val bos = ByteArrayOutputStream()
                val compressed = scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                if (!compressed) {
                    android.util.Log.e("AttachmentReader", "Failed to compress bitmap")
                    return null
                }
                val bytes = bos.toByteArray()
                if (bytes.isEmpty()) {
                    android.util.Log.e("AttachmentReader", "Compressed bytes are empty")
                    return null
                }
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } finally {
                if (scaled !== original) scaled.recycle()
                original.recycle()
            }
        } catch (e: Exception) {
            android.util.Log.e("AttachmentReader", "Error encoding image to base64", e)
            null
        }
    }

    fun readTextPreview(ctx: Context, uri: Uri, maxChars: Int = 12000): String? {
        return try {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?.take(maxChars)
        } catch (_: Exception) {
            null
        }
    }

    private fun scale(src: Bitmap, maxSide: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
    }
}
