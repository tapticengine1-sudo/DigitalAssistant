
package com.example.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import kotlin.concurrent.thread

data class GalleryImage(val uri: Uri, val path: String, var selected: Boolean = false)

class GalleryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: GalleryAdapter
    private lateinit var closeButton: Button
    private lateinit var attachButton: Button
    private val images = mutableListOf<GalleryImage>()
    private val maxSelection = 30

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        recyclerView = findViewById(R.id.recyclerView)
        closeButton = findViewById(R.id.btnClose)
        attachButton = findViewById(R.id.btnAttach)

        recyclerView.layoutManager = GridLayoutManager(this, 3)
        adapter = GalleryAdapter(images) { image, position ->
            toggleSelection(image, position)
        }
        recyclerView.adapter = adapter

        closeButton.setOnClickListener { finish() }
        attachButton.setOnClickListener { confirmSelection() }
        attachButton.visibility = View.GONE

        if (checkPermissions()) {
            loadImagesAsync()
        } else {
            requestPermissions()
        }
    }

    private fun checkPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        ActivityCompat.requestPermissions(this, arrayOf(permission), 101)
    }

    private fun toggleSelection(image: GalleryImage, position: Int) {
        val selectedCount = images.count { it.selected }
        if (!image.selected && selectedCount >= maxSelection) {
            Toast.makeText(this, "Максимум $maxSelection фото", Toast.LENGTH_SHORT).show()
            return
        }
        image.selected = !image.selected
        adapter.notifyItemChanged(position)
        updateAttachButton()
    }

    private fun updateAttachButton() {
        val count = images.count { it.selected }
        if (count > 0) {
            attachButton.visibility = View.VISIBLE
            attachButton.text = "Прикрепить ($count)"
        } else {
            attachButton.visibility = View.GONE
        }
    }

    private fun confirmSelection() {
        val selected = images.filter { it.selected }
        if (selected.isEmpty()) {
            finish()
            return
        }
        // Отправляем выбранные фото (пока только первое, но можно расширить)
        val uris = selected.map { it.uri.toString() }
        val paths = selected.map { it.path }
        
        setResult(RESULT_OK, intent.apply {
            putExtra("photo_uri", uris.first())
            putExtra("photo_path", paths.first())
            // Для будущего: можно добавить массивы
            putStringArrayListExtra("photo_uris", ArrayList(uris))
            putStringArrayListExtra("photo_paths", ArrayList(paths))
        })
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadImagesAsync()
            } else {
                Toast.makeText(this, "Разрешение не предоставлено", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun loadImagesAsync() {
        thread {
            try {
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DATE_ADDED
                )

                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
                val limit = 500 // Ограничиваем количество для быстрой загрузки

                val cursor: Cursor? = try {
                    contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        null,
                        null,
                        "$sortOrder LIMIT $limit"
                    )
                } catch (e: Exception) {
                    android.util.Log.e("GalleryActivity", "Query failed", e)
                    runOnUiThread {
                        Toast.makeText(this, "Ошибка загрузки галереи: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    null
                }

                if (cursor == null) {
                    runOnUiThread {
                        Toast.makeText(this, "Не удалось получить доступ к галерее", Toast.LENGTH_SHORT).show()
                    }
                    return@thread
                }

                val temp = mutableListOf<GalleryImage>()
                cursor.use {
                    try {
                        val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val dataColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

                        while (it.moveToNext()) {
                            try {
                                val id = it.getLong(idColumn)
                                val path = it.getString(dataColumn) ?: ""
                                val uri = Uri.withAppendedPath(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    id.toString()
                                )
                                temp.add(GalleryImage(uri, path))
                            } catch (e: Exception) {
                                // Пропускаем проблемные изображения
                                android.util.Log.w("GalleryActivity", "Skipping image", e)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("GalleryActivity", "Error reading cursor", e)
                    }
                }

                runOnUiThread {
                    if (temp.isEmpty()) {
                        Toast.makeText(this, "Изображения не найдены", Toast.LENGTH_SHORT).show()
                    }
                    images.clear()
                    images.addAll(temp)
                    adapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                android.util.Log.e("GalleryActivity", "Fatal error in loadImagesAsync", e)
                runOnUiThread {
                    Toast.makeText(this, "Критическая ошибка загрузки галереи", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }
}

class GalleryAdapter(
    private val images: List<GalleryImage>,
    private val onClick: (GalleryImage, Int) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.imageView)
        val checkBox: CheckBox = view.findViewById(R.id.checkBox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_image, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val image = images[position]
        
        // Используем Coil для быстрой асинхронной загрузки миниатюр с кэшированием
        holder.imageView.load(image.uri) {
            crossfade(false) // Отключаем анимацию для плавности скролла
            scale(Scale.FILL)
            size(200, 200) // Еще меньше для экономии памяти
            memoryCacheKey(image.uri.toString())
            diskCacheKey(image.uri.toString())
            placeholder(android.R.drawable.ic_menu_gallery)
            error(android.R.drawable.ic_menu_report_image)
        }

        holder.checkBox.isChecked = image.selected
        holder.checkBox.visibility = if (image.selected) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            onClick(image, position)
        }
    }

    override fun getItemCount() = images.size
}