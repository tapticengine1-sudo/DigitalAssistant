package com.example.assistant

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class Msg(
    val user: Boolean,
    var text: String,
    var attachmentLabel: String? = null,
    var attachmentUri: Uri? = null,
    var dbId: Long = 0
)

class ChatAdapter(private val items: List<Msg>) : RecyclerView.Adapter<ChatAdapter.VH>() {
    class VH(val row: LinearLayout, val tv: TextView, val attachThumb: ImageView) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.VERTICAL
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
        }
        
        val attachThumb = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                (120 * d).toInt(), 
                (120 * d).toInt()
            ).apply {
                setMargins((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            val bg = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(0xFFE0E0E0.toInt())
            }
            background = bg
        }
        
        val tv = TextView(ctx).apply {
            textSize = 16f
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.82).toInt()
            setTextIsSelectable(true)
        }
        
        row.addView(attachThumb)
        row.addView(tv)
        return VH(row, tv, attachThumb)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val ctx = holder.itemView.context
        holder.tv.text = m.text
        
        val userBubbleColor = ctx.getColor(R.color.ptako_bubble_user)
        val aiBubbleColor = ctx.getColor(R.color.ptako_bubble_ai)
        val textColor = ctx.getColor(R.color.ptako_text)
        
        holder.tv.setTextColor(textColor)
        val bg = GradientDrawable().apply {
            cornerRadius = 40f
            setColor(if (m.user) userBubbleColor else aiBubbleColor)
        }
        holder.tv.background = bg
        holder.row.gravity = if (m.user) Gravity.END else Gravity.START

        val label = m.attachmentLabel
        val uri = m.attachmentUri
        
        if (!label.isNullOrBlank() && uri != null) {
            holder.attachThumb.visibility = View.VISIBLE
            // Загружаем миниатюру с помощью Coil
            holder.attachThumb.load(uri) {
                crossfade(true)
                placeholder(android.R.drawable.ic_menu_gallery)
                error(android.R.drawable.ic_menu_report_image)
            }
            
            // Клик для полноэкранного просмотра
            holder.attachThumb.setOnClickListener {
                val ctx = holder.itemView.context
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    // Fallback: открыть через chooser
                    try {
                        ctx.startActivity(Intent.createChooser(intent, "Просмотр"))
                    } catch (_: Exception) {}
                }
            }
        } else {
            holder.attachThumb.visibility = View.GONE
        }
    }

    override fun getItemCount() = items.size
}
