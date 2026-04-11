package com.vayunmathur.maps

import android.app.DownloadManager
import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

class ZoneDownloadManager(private val context: Context) {
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    enum class ZoneStatus { NOT_STARTED, DOWNLOADING, FINISHED }

    /**
     * A Flow that emits a Map of all zones currently being downloaded.
     * Key: Zone ID, Value: Progress (0.0 to 1.0).
     * Statuses included: PENDING, RUNNING, and PAUSED.
     */
    fun getDownloadingZonesFlow(): Flow<Map<Int, Float>> = flow {
        while (true) {
            val progressSumMap = mutableMapOf<Int, Float>()
            val countMap = mutableMapOf<Int, Int>()

            // Query for any task that isn't finished or failed
            val query = DownloadManager.Query().setFilterByStatus(
                DownloadManager.STATUS_RUNNING or
                        DownloadManager.STATUS_PENDING or
                        DownloadManager.STATUS_PAUSED
            )

            val cursor = downloadManager.query(query)
            while (cursor.moveToNext()) {
                val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                // Extract Zone ID from title: "Map Zone 52" -> 52
                if (title.startsWith("Map Zone ")) {
                    val zoneId = title.removePrefix("Map Zone ").toIntOrNull()
                    if (zoneId != null) {
                        val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                        val progress = if (total > 0) downloaded.toFloat() / total.toFloat() else 0f
                        progressSumMap[zoneId] = progressSumMap.getOrDefault(zoneId, 0f) + progress
                        countMap[zoneId] = countMap.getOrDefault(zoneId, 0) + 1
                    }
                }
            }
            cursor.close()

            val finalProgressMap = progressSumMap.mapValues { (zoneId, sum) ->
                sum / (countMap[zoneId] ?: 1).toFloat()
            }

            emit(finalProgressMap)
            delay(1000) // Poll faster (1s) for progress updates
        }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    /**
     * Deletes a zone file and cancels any active downloads for that zone.
     */
    fun deleteZone(zoneId: Int) {
        // 1. Cancel active or pending downloads in the system
        val query = DownloadManager.Query()
        val cursor = downloadManager.query(query)
        while (cursor.moveToNext()) {
            val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
            if (title == "Map Zone $zoneId") {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                downloadManager.remove(id) // This stops the download and removes the system entry
            }
        }
        cursor.close()

        // 2. Remove the files from disk
        val files = listOf(
            "zone_$zoneId.pmtiles",
            "nodes_zone_$zoneId.bin",
            "edges_zone_$zoneId.bin"
        )
        files.forEach { fileName ->
            val file = File(context.getExternalFilesDir(null), fileName)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    fun getDownloadedZonesFlow(): Flow<List<Int>> = flow {
        while (true) {
            emit(getDownloadedZones())
            delay(2000) // Poll every 2 seconds
        }
    }
        .distinctUntilChanged() // Only emit if the list actually changes
        .conflate()             // Drop intermediate updates if the UI is slow
        .flowOn(Dispatchers.IO) // Run the disk/DB checks on a background thread

    fun getDownloadedZones(): List<Int> {
        val filesDir = context.getExternalFilesDir(null) ?: return emptyList()

        // We scan the grid 0-63. This is faster than a full regex file list scan
        // and ensures we only return valid zone IDs.
        return (0..63).filter { zoneId ->
            getZoneStatus(zoneId) == ZoneStatus.FINISHED
        }
    }

    fun getZoneStatus(zoneId: Int): ZoneStatus {
        val pmtilesFile = File(context.getExternalFilesDir(null), "zone_$zoneId.pmtiles")
        val nodesFile = File(context.getExternalFilesDir(null), "nodes_zone_$zoneId.bin")
        val edgesFile = File(context.getExternalFilesDir(null), "edges_zone_$zoneId.bin")

        val query = DownloadManager.Query()
        val cursor = downloadManager.query(query)
        var isDownloading = false

        while (cursor.moveToNext()) {
            val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
            if (title == "Map Zone $zoneId") {
                val systemStatus = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (systemStatus == DownloadManager.STATUS_RUNNING ||
                    systemStatus == DownloadManager.STATUS_PAUSED ||
                    systemStatus == DownloadManager.STATUS_PENDING) {
                    isDownloading = true
                }
            }
        }
        cursor.close()

        if (isDownloading) return ZoneStatus.DOWNLOADING

        val allFilesExist = pmtilesFile.exists() && nodesFile.exists() && edgesFile.exists()
        return if (allFilesExist) ZoneStatus.FINISHED else ZoneStatus.NOT_STARTED
    }

    fun startDownload(zoneId: Int) {
        deleteZone(zoneId)
        val files = listOf(
            "zone_$zoneId.pmtiles" to "https://data.vayunmathur.com/zone_$zoneId.pmtiles",
            "nodes_zone_$zoneId.bin" to "https://data.vayunmathur.com/nodes_zone_$zoneId.bin",
            "edges_zone_$zoneId.bin" to "https://data.vayunmathur.com/edges_zone_$zoneId.bin"
        )

        files.forEach { (fileName, url) ->
            val request = DownloadManager.Request(url.toUri())
                .setTitle("Map Zone $zoneId")
                .setDescription("Downloading high-detail offline map data")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, null, fileName)
                .setAllowedOverMetered(true)

            downloadManager.enqueue(request)
        }
    }
}