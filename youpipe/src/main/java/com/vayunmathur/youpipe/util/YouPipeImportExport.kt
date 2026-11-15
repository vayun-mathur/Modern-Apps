package com.vayunmathur.youpipe.util

import android.app.Application
import android.net.Uri
import com.vayunmathur.library.log.Log
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.zip.ZipInputStream
import kotlin.time.Clock
import kotlin.time.Instant

private const val TAG = "YouPipeViewModel"

fun YouPipeViewModel.importYouTubeTakeout(uri: Uri) {
    val ctx = getApplication<Application>()
    viewModelScope.launch(Dispatchers.IO) {
        isImportingMutable.value = true
        importProgressMutable.value = 0f
        try {
            importTakeoutZip(ctx, uri)
        } catch (e: java.io.IOException) {
            Log.error(TAG, "Error importing YouTube Takeout", e)
        } catch (e: IllegalStateException) {
            Log.error(TAG, "Error importing YouTube Takeout", e)
        }
        isImportingMutable.value = false
        setupHourlyTask(ctx)
    }
}

private suspend fun YouPipeViewModel.importTakeoutZip(ctx: Application, uri: Uri) {
    ZipInputStream(ctx.contentResolver.openInputStream(uri)).use { zipInputStream ->
        var entry = zipInputStream.nextEntry
        val subs = mutableListOf<Subscription>()
        val history = mutableListOf<HistoryVideo>()

        while (entry != null) {
            when {
                entry.name.endsWith("subscriptions/subscriptions.csv") ->
                    importTakeoutSubscriptions(zipInputStream, subs)
                entry.name.endsWith("history/watch-history.json") ->
                    importTakeoutJsonHistory(zipInputStream, history)
                entry.name.endsWith("history/watch-history.html") ->
                    importTakeoutHtmlHistory(zipInputStream, history)
            }
            entry = zipInputStream.nextEntry
        }

        if (subs.isNotEmpty()) repository.subscriptionStore.upsertSubscriptions(subs)
        if (history.isNotEmpty()) repository.historyStore.upsertHistoryVideos(history)
    }
}

private suspend fun YouPipeViewModel.importTakeoutSubscriptions(
    zipInputStream: ZipInputStream,
    subs: MutableList<Subscription>,
) {
    val content = zipInputStream.readBytes().decodeToString()
    val lines = content.lines().drop(1)
    val total = lines.size
    lines.forEachIndexed { index, line ->
        if (line.isNotBlank()) {
            val parts = line.split(",")
            if (parts.size >= 2) {
                val url = parts[1]
                try {
                    val channelInfo = getChannelInfoFromURL(url)
                    subs.add(channelInfo.toSubscription())
                } catch (e: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
                    Log.error(TAG, "Error fetching channel info for $url", e)
                } catch (e: java.io.IOException) {
                    Log.error(TAG, "Error fetching channel info for $url", e)
                }
            }
        }
        importProgressMutable.value = (index + 1).toFloat() / total
    }
}

private fun importTakeoutJsonHistory(
    zipInputStream: ZipInputStream,
    history: MutableList<HistoryVideo>,
) {
    val jsonString = zipInputStream.readBytes().decodeToString()
    val jsonArray = Json.parseToJsonElement(jsonString).jsonArray
    jsonArray.forEach { element ->
        try {
            parseTakeoutJsonEntry(element)?.let { history.add(it) }
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "Error parsing history item", e)
        }
    }
}

private fun parseTakeoutJsonEntry(element: kotlinx.serialization.json.JsonElement): HistoryVideo? {
    val title = element.jsonObject["title"]
        ?.jsonPrimitive?.content?.removePrefix("Watched ") ?: ""
    val url = element.jsonObject["titleUrl"]?.jsonPrimitive?.content ?: ""
    val time = element.jsonObject["time"]
        ?.jsonPrimitive?.content?.let { Instant.parse(it) }
        ?: Clock.System.now()
    val author = element.jsonObject["subtitles"]
        ?.jsonArray?.firstOrNull()
        ?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""

    if (!url.contains("watch?v=")) return null
    val videoID = videoURLtoID(url)
    return HistoryVideo(
        id = videoID,
        progress = 0,
        videoItem = VideoInfo(
            title.decodeHtml(),
            videoID,
            0,
            0,
            time,
            "",
            author.decodeHtml()
        ),
        timestamp = time
    )
}

private fun importTakeoutHtmlHistory(
    zipInputStream: ZipInputStream,
    history: MutableList<HistoryVideo>,
) {
    val html = zipInputStream.readBytes().decodeToString()
    val regex = Regex(
        "<div class=\"content-cell mdl-cell mdl-cell--6-col " +
            "mdl-typography--body-1\">Watched&nbsp;<a href=\"(.*?)\">" +
            "(.*?)</a><br><a href=\"(.*?)\">(.*?)</a><br>(.*?)</div>",
        RegexOption.DOT_MATCHES_ALL
    )
    regex.findAll(html).forEach { match ->
        try {
            parseTakeoutHtmlEntry(match)?.let { history.add(it) }
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "Error parsing HTML history item", e)
        }
    }
}

private fun parseTakeoutHtmlEntry(match: MatchResult): HistoryVideo? {
    val url = match.groupValues[1]
    val title = match.groupValues[2]
    val author = match.groupValues[4]

    if (!url.contains("watch?v=")) return null
    val videoID = videoURLtoID(url)
    return HistoryVideo(
        id = videoID,
        progress = 0,
        videoItem = VideoInfo(
            title.decodeHtml(),
            videoID,
            0,
            0,
            Clock.System.now(),
            "",
            author.decodeHtml()
        ),
        timestamp = Clock.System.now()
    )
}

fun YouPipeViewModel.exportSubscriptions(uri: Uri) {
    val ctx = getApplication<Application>()
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val subs = repository.subscriptionStore.getAllSubscriptions()
            val json = Json.encodeToString(subs)
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
        } catch (e: java.io.IOException) {
            Log.error(TAG, "Error exporting subscriptions", e)
        } catch (e: IllegalStateException) {
            Log.error(TAG, "Error exporting subscriptions", e)
        }
    }
}

fun YouPipeViewModel.restoreSubscriptions(uri: Uri) {
    val ctx = getApplication<Application>()
    viewModelScope.launch(Dispatchers.IO) {
        isImportingMutable.value = true
        try {
            val json = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
            val subs = decodeSubscriptionBackup(json)
            if (subs == null) {
                // #565: files shaped like {"version":1,"salt":"..."} are an encrypted/salted
                // envelope written by another client, not a plain subscription list. There is no
                // password UI to decrypt with, so report it instead of crashing on the decode.
                Log.error(TAG, "Unsupported subscription backup format")
                AppMessages.show(ctx.getString(R.string.restore_unsupported_format))
            } else {
                repository.subscriptionStore.clearAllSubscriptions()
                repository.subscriptionStore.upsertSubscriptions(subs)
            }
        } catch (e: java.io.IOException) {
            Log.error(TAG, "Error restoring subscriptions", e)
            AppMessages.show(ctx.getString(R.string.restore_unsupported_format))
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "Error restoring subscriptions", e)
            AppMessages.show(ctx.getString(R.string.restore_unsupported_format))
        }
        isImportingMutable.value = false
        setupHourlyTask(ctx)
    }
}

/**
 * Decodes a YouPipe subscription backup: a plain JSON array of [Subscription].
 * Returns null when the file is a non-array envelope (e.g. an encrypted
 * `{"version":..,"salt":..}` backup from another client) rather than throwing, so callers can
 * report an unsupported-format message.
 */
internal fun decodeSubscriptionBackup(json: String): List<Subscription>? {
    val trimmed = json.trimStart()
    if (!trimmed.startsWith("[")) return null
    return Json.decodeFromString<List<Subscription>>(json)
}

fun YouPipeViewModel.importNewPipe(uri: Uri) {
    val ctx = getApplication<Application>()
    viewModelScope.launch(Dispatchers.IO) {
        isImportingMutable.value = true
        importProgressMutable.value = 0f
        try {
            val jsonString = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
            val json = Json.parseToJsonElement(jsonString).jsonObject
            val subsArray = json["subscriptions"]?.jsonArray
            if (subsArray != null) {
                val total = subsArray.size
                val subs = mutableListOf<Subscription>()
                subsArray.forEachIndexed { index, element ->
                    try {
                        var url = element.jsonObject["url"]?.jsonPrimitive?.content ?: ""
                        if (!url.startsWith("http")) url = "https://$url"
                        val channelInfo = getChannelInfoFromURL(url)
                        subs.add(channelInfo.toSubscription())
                    } catch (e: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
                        Log.error(TAG, "Error importing channel", e)
                    } catch (e: java.io.IOException) {
                        Log.error(TAG, "Error importing channel", e)
                    }
                    importProgressMutable.value = (index + 1).toFloat() / total
                }
                repository.subscriptionStore.clearAllSubscriptions()
                repository.subscriptionStore.upsertSubscriptions(subs)
            }
        } catch (e: java.io.IOException) {
            Log.error(TAG, "Error importing NewPipe subscriptions", e)
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "Error importing NewPipe subscriptions", e)
        }
        isImportingMutable.value = false
        setupHourlyTask(ctx)
    }
}
