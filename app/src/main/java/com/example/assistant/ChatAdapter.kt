package com.example.assistant

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class Msg(
    val user: Boolean,
    var text: String,
    var attachmentLabel: String? = null,
    var dbId: Long = 0
)

class ChatAdapter(private val items: List<Msg>) : RecyclerView.Adapter<ChatAdapter.VH>() {
    class VH(val row: LinearLayout, val tv: TextView, val attachIcon: ImageView) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.VERTICAL
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
        }
        val tv = TextView(ctx).apply {
            textSize = 16f
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.82).toInt()
            setTextIsSelectable(true)
        }
        val attachIcon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()).apply {
                setMargins((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), 0)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = android.view.View.GONE
        }
        row.addView(attachIcon)
        row.addView(tv)
        return VH(row, tv, attachIcon)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.tv.text = m.text
        holder.tv.setTextColor(if (m.user) Color.WHITE else 0xFF1A1A1A.toInt())
        val bg = GradientDrawable().apply {
            cornerRadius = 40f
            setColor(if (m.user) 0xFF3F51B5.toInt() else 0xFFE8EAF6.toInt())
        }
        holder.tv.background = bg
        holder.row.gravity = if (m.user) Gravity.END else Gravity.START

        val label = m.attachmentLabel
        if (!label.isNullOrBlank()) {
            holder.attachIcon.visibility = android.view.View.VISIBLE
            // Простая иконка - цветной квадратик с первой буквой типа файла
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f
                setColor(if (m.user) 0x80FFFFFF.toInt() else 0xFF90CAF9.toInt())
            }
            holder.attachIcon.background = bg
            holder.attachIcon.setImageResource(android.R.drawable.ic_menu_gallery)
            holder.attachIcon.alpha = 0.7f
        } else {
            holder.attachIcon.visibility = android.view.View.GONE
        }
    }

    override fun getItemCount() = items.size
}
