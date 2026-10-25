package com.example.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {
    
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var closeButton: Button
    private lateinit var previewImage: ImageView
    private lateinit var retakeButton: Button
    private lateinit var confirmButton: Button
    
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var capturedFile: File? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        
        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.btnCapture)
        closeButton = findViewById(R.id.btnClose)
        previewImage = findViewById(R.id.previewImage)
        retakeButton = findViewById(R.id.btnRetake)
        confirmButton = findViewById(R.id.btnConfirm)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CODE_PERMISSIONS
            )
        }
        
        captureButton.setOnClickListener { takePhoto() }
        closeButton.setOnClickListener { finish() }
        retakeButton.setOnClickListener { retakePhoto() }
        confirmButton.setOnClickListener { confirmPhoto() }
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
            
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        
        val photoFile = File(
            cacheDir,
            "camera_${System.currentTimeMillis()}.jpg"
        )
        
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        this@CameraActivity,
                        "Ошибка съёмки: ${exc.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturedFile = photoFile
                    showPreview(photoFile)
                }
            }
        )
    }
    
    private fun showPreview(file: File) {
        runOnUiThread {
            previewView.visibility = View.GONE
            captureButton.visibility = View.GONE
            
            previewImage.visibility = View.VISIBLE
            retakeButton.visibility = View.VISIBLE
            confirmButton.visibility = View.VISIBLE
            
            val bitmap = loadAndRotateBitmap(file)
            previewImage.setImageBitmap(bitmap)
        }
    }
    
    private fun loadAndRotateBitmap(file: File): Bitmap {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        return try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
            
            if (matrix.isIdentity) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
        } catch (e: Exception) {
            bitmap
        }
    }
    
    private fun retakePhoto() {
        capturedFile?.delete()
        capturedFile = null
        
        previewView.visibility = View.VISIBLE
        captureButton.visibility = View.VISIBLE
        
        previewImage.visibility = View.GONE
        retakeButton.visibility = View.GONE
        confirmButton.visibility = View.GONE
    }
    
    private fun confirmPhoto() {
        val file = capturedFile
        if (file != null && file.exists()) {
            // Сохраняем повернутое изображение с правильной ориентацией в правильную директорию
            val correctedFile = saveCorrectedImage(file)
            setResult(RESULT_OK, intent.apply {
                putExtra("photo_path", correctedFile.absolutePath)
            })
            finish()
        }
    }
    
    private fun saveCorrectedImage(originalFile: File): File {
        val correctedFile = File(
            cacheDir,
            "corrected_${System.currentTimeMillis()}.jpg"
        )
        
        try {
            val bitmap = loadAndRotateBitmap(originalFile)
            
            // Сжимаем изображение для экономии памяти и более быстрой передачи
            val maxSide = 2048
            val scaledBitmap = if (bitmap.width > maxSide || bitmap.height > maxSide) {
                val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
                val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                if (scaled !== bitmap) bitmap.recycle()
                scaled
            } else {
                bitmap
            }
            
            // Сохраняем с качеством 90% для баланса размера и качества
            correctedFile.outputStream().use { out ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            scaledBitmap.recycle()
            
            // Устанавливаем EXIF с нормальной ориентацией
            try {
                val exif = ExifInterface(correctedFile.absolutePath)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                exif.saveAttributes()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set EXIF orientation", e)
            }
            
            originalFile.delete()
            return correctedFile
        } catch (e: Exception) {
            Log.e(TAG, "Error saving corrected image", e)
            Toast.makeText(this, "Ошибка сохранения фото: ${e.message}", Toast.LENGTH_SHORT).show()
            return originalFile
        }
    }
    
    private fun allPermissionsGranted() = arrayOf(Manifest.permission.CAMERA).all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Разрешения не предоставлены", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
    
    companion object {
        private const val TAG = "CameraActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}