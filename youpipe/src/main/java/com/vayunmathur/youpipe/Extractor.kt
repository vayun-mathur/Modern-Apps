package com.vayunmathur.youpipe

import androidx.core.net.toUri
import com.vayunmathur.youpipe.ui.ChannelInfo
import com.vayunmathur.youpipe.ui.VideoInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import kotlin.io.encoding.Base64

fun videoURLtoID(url: String): Long {
    return ByteBuffer.wrap(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(url.toUri().getQueryParameter("v")!!)).long
}

fun encodeVideoID(id: String): Long {
    return ByteBuffer.wrap(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(id)).long
}

fun decodeVideoID(id: Long): String {
    val buffer = ByteBuffer.allocate(java.lang.Long.BYTES)
    buffer.putLong(id)
    return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(buffer.array())
}

fun videoIDtoURL(id: Long): String {
    val buffer = ByteBuffer.allocate(java.lang.Long.BYTES)
    buffer.putLong(id)
    return "https://www.youtube.com/watch?v="+Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(buffer.array())
}

fun channelIDtoURL(id: String): String {
    return "https://www.youtube.com/channel/$id"
}

fun channelURLtoID(url: String): String {
    return url.toUri().lastPathSegment!!
}

private val client = HttpClient {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

suspend fun getChannelVideos(channelInfo: List<ChannelInfo>): List<String> = coroutineScope {
    channelInfo.map { channelInfo ->
        async(Dispatchers.IO) {
            val xmlString =
                client.get("https://www.youtube.com/feeds/videos.xml?channel_id=${channelInfo.channelID}")
                    .bodyAsText()

            val ids = xmlString.split('\n').filter {
                it.contains("<yt:videoId>")
            }.map { it.substringBefore("</yt:videoId>").substringAfter("<yt:videoId>") }
            ids
        }
    }.awaitAll().flatten()
}

suspend fun getChannelInfo(channelId: List<String>): List<ChannelInfo> {
    return client.get("https://api.vayunmathur.com/youpipe/channels") {
        parameter("ids", channelId.joinToString(","))
    }.body<List<ChannelInfo>>()
}

suspend fun getChannelDataAndIds(channelId: List<String>): Pair<List<ChannelInfo>, List<String>> {

    // --- STEP 1: Fetch Channel Metadata ---
    val channelInfo = getChannelInfo(channelId)

    val channelIDs= getChannelVideos(channelInfo)

    return Pair(channelInfo, channelIDs)
}

suspend fun getVideoDetails(videoIds: List<String>): List<VideoInfo> {
    return client.get("https://api.vayunmathur.com/youpipe/videos") {
        parameter("ids", videoIds.joinToString(","))
    }.body<List<VideoInfo>>()
}