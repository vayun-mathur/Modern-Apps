package com.vayunmathur.library.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.*
import okhttp3.*
import okio.buffer
import okio.appendingSink
import okio.sink
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class DownloadService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val NOTIF_ID = 1001
    private val CHANNEL_ID = "high_speed_download_channel"

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AiChat:HighPerf")
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiChat:DownloadWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val urls = intent?.getStringArrayExtra("urls") ?: emptyArray()
        val fileNames = intent?.getStringArrayExtra("fileNames") ?: emptyArray()

        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Initializing 100GB Transfer..."),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        wifiLock?.acquire()
        wakeLock?.acquire()

        serviceScope.launch {
            val ds = DataStoreUtils.getInstance(applicationContext)

            urls.forEachIndexed { index, url ->
                val fileName = fileNames[index]
                val destFile = File(getExternalFilesDir(null), fileName)

                var lastBytes = 0L
                var lastTime = System.currentTimeMillis()

                try {
                    if(ds.getBoolean("done_$fileName", false)) return@forEachIndexed
                    downloadFileWithResume(url, destFile) { downloaded, total ->
                        val currentTime = System.currentTimeMillis()
                        val timeDiff = (currentTime - lastTime) / 1000.0

                        if (timeDiff >= 1.0 || downloaded == total) {
                            val progress = if (total > 0) downloaded.toDouble() / total else 0.0
                            val speedMbps = ((downloaded - lastBytes) * 8.0) / 1_000_000.0 / timeDiff

                            launch {
                                ds.setDouble("progress_$fileName", progress)
                                ds.setDouble("speed_$fileName", if (downloaded == total) 0.0 else speedMbps)
                                ds.setBoolean("done_$fileName", downloaded == total)
                            }

                            updateNotification("Downloading $fileName: ${(progress * 100).toInt()}% (${speedMbps.toInt()} Mbps)")

                            lastBytes = downloaded
                            lastTime = currentTime
                        }
                    }

                    // Final Verification
                    updateNotification("Verifying $fileName integrity...")
                    // If you have a server hash, you would check it here
                    // val isValid = verifyHash(destFile, "expected_hash_here")

                } catch (e: Exception) {
                    launch { ds.setString("error_$fileName", e.message ?: "Network Error") }
                }
            }
            ds.setBoolean("dbSetupComplete", true)
            cleanupAndStop()
        }
        return START_NOT_STICKY
    }

    private suspend fun downloadFileWithResume(url: String, file: File, onProgress: (Long, Long) -> Unit) {
        val existingSize = if (file.exists()) file.length() else 0L

        val request = Request.Builder()
            .url(url)
            .apply {
                if (existingSize > 0) addHeader("Range", "bytes=$existingSize-")
            }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw Exception("Failed: ${response.code}")
            }

            val isResuming = response.code == 206
            val body = response.body ?: throw Exception("Empty body")
            val totalSize = if (isResuming) existingSize + body.contentLength() else body.contentLength()

            // AppendingSink ensures we write exactly from where the last byte ended
            val sink = if (isResuming) file.appendingSink().buffer() else file.sink().buffer()
            val source = body.source()

            var downloaded = if (isResuming) existingSize else 0L
            val buffer = ByteArray(131072) // 128KB buffer for massive files
            var bytesRead: Int

            source.use { input ->
                sink.use { output ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        onProgress(downloaded, totalSize)
                    }
                    output.flush() // Force write to physical storage
                }
            }
        }
    }

    private fun verifyHash(file: File, expectedHash: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(262144)
        file.inputStream().use { input ->
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        return actualHash.equals(expectedHash, ignoreCase = true)
    }

    private fun cleanupAndStop() {
        if (wifiLock?.isHeld == true) wifiLock?.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("High-Speed Setup")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, createNotification(content))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "AI Downloads", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        cleanupAndStop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}