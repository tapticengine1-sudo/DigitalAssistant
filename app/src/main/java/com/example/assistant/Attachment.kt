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
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        var name = "file"
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        val isImage = mime.startsWith("image/")
        return PendingAttachment(uri, mime, name, isImage)
    }

    /** Сжимает изображение и кодирует в base64 JPEG (для multimodal API). */
    fun imageBase64Jpeg(ctx: Context, uri: Uri, maxSide: Int = 1280, quality: Int = 82): String? {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val original = BitmapFactory.decodeStream(input) ?: return null
                val scaled = scale(original, maxSide)
                try {
                    val bos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                    Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                } finally {
                    if (scaled !== original) scaled.recycle()
                    original.recycle()
                }
            }
        } catch (_: Exception) {
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
