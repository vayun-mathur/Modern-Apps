package com.vayunmathur.library.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.*
import okhttp3.*
import okio.BufferedSink
import okio.buffer
import okio.appendingSink
import okio.sink
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class DownloadService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "high_speed_download_channel"
        private const val MAX_RETRIES = 5
        private const val BUFFER_SIZE = 262144L
    }

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        // Handle deprecated WIFI_MODE_FULL_HIGH_PERF
        val wifiMode =
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY

        wifiLock = wm.createWifiLock(wifiMode, "AiChat:HighPerf")

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiChat:DownloadWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val urls = intent?.getStringArrayExtra("urls") ?: emptyArray()
        val fileNames = intent?.getStringArrayExtra("fileNames") ?: emptyArray()

        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Preparing high-speed transfer..."),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        wifiLock?.acquire()
        wakeLock?.acquire()

        serviceScope.launch {
            val ds = DataStoreUtils.getInstance(applicationContext)

            urls.forEachIndexed { index, url ->
                val fileName = fileNames[index]
                val destFile = File(getExternalFilesDir(null), fileName)

                if(ds.getBoolean("done_$fileName", false)) return@forEachIndexed

                var retryCount = 0
                var success = false

                while (retryCount < MAX_RETRIES && !success && isActive) {
                    try {
                        performDownload(url, destFile, ds, fileName)
                        success = true
                    } catch (e: Exception) {
                        retryCount++
                        val errorMsg = "Retry $retryCount/$MAX_RETRIES: ${e.message}"
                        updateNotification(errorMsg)
                        ds.setString("error_$fileName", errorMsg)

                        if (retryCount in 1..MAX_RETRIES) {
                            delay(TimeUnit.SECONDS.toMillis(Math.pow(2.0, retryCount.toDouble()).toLong()))
                        }
                    }
                }
            }
            ds.setBoolean("dbSetupComplete", true)
            cleanupAndStop()
        }

        return START_REDELIVER_INTENT
    }

    private suspend fun performDownload(
        url: String,
        file: File,
        ds: DataStoreUtils,
        fileName: String
    ) = withContext(Dispatchers.IO) {
        val existingSize = if (file.exists()) file.length() else 0L
        var lastBytes = existingSize
        var lastTime = System.currentTimeMillis()

        val request = Request.Builder()
            .url(url)
            .apply {
                if (existingSize > 0) addHeader("Range", "bytes=$existingSize-")
            }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                if (response.code == 416) {
                    markAsDone(ds, fileName)
                    return@withContext
                }
                throw IOException("Server returned ${response.code}")
            }

            // Elvis operator removed as body is expected to be present if call succeeded
            val body = response.body
            val isResuming = response.code == 206
            val totalSize = if (isResuming) existingSize + body.contentLength() else body.contentLength()

            val source = body.source()
            val sink: BufferedSink = if (isResuming) file.appendingSink().buffer() else file.sink().buffer()

            var downloaded = if (isResuming) existingSize else 0L

            try {
                sink.use { output ->
                    source.use { input ->
                        while (isActive && !input.exhausted()) {
                            val read = input.read(output.buffer, BUFFER_SIZE)
                            if (read == -1L) break

                            output.emit()
                            downloaded += read

                            val currentTime = System.currentTimeMillis()
                            val timeDiffMs = currentTime - lastTime

                            if (timeDiffMs >= 1000L || downloaded == totalSize) {
                                val progress = if (totalSize > 0) downloaded.toDouble() / totalSize else 0.0
                                val speedMbps = ((downloaded - lastBytes) * 8.0) / 1_000_000.0 / (timeDiffMs / 1000.0)

                                ds.setDouble("progress_$fileName", progress)
                                ds.setDouble("speed_$fileName", if (downloaded == totalSize) 0.0 else speedMbps)

                                val speedText = if (speedMbps > 0) "${speedMbps.toInt()} Mbps" else "Finishing..."
                                updateNotification("Downloading $fileName: ${(progress * 100).toInt()}% ($speedText)")

                                lastBytes = downloaded
                                lastTime = currentTime
                            }
                        }
                        output.flush()
                    }
                }

                if (totalSize > 0 && downloaded >= totalSize) {
                    markAsDone(ds, fileName)
                } else if (isActive && totalSize > 0) {
                    throw IOException("Connection lost: $downloaded/$totalSize bytes received")
                }

            } catch (e: Exception) {
                throw e
            }
        }
    }

    private suspend fun markAsDone(ds: DataStoreUtils, fileName: String) {
        ds.setBoolean("done_$fileName", true)
        ds.setDouble("progress_$fileName", 1.0)
        ds.setDouble("speed_$fileName", 0.0)
    }

    private fun cleanupAndStop() {
        if (wifiLock?.isHeld == true) wifiLock?.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Data Transfer Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, createNotification(content))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Background Transfers", NotificationManager.IMPORTANCE_LOW)
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