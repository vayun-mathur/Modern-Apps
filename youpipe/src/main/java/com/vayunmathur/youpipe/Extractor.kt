package com.vayunmathur.youpipe

import android.util.Log
import androidx.core.net.toUri
import com.vayunmathur.youpipe.ui.ChannelInfo
import com.vayunmathur.youpipe.ui.VideoInfo
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDateTime
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.time.Duration
import kotlin.time.Instant

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

private const val apiKey = "AIzaSyBJ2gUeEQ36jbBGLRJUjK1541StDfpBWHI"

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
    val chanRaw: ChannelResponse = client.get("https://www.googleapis.com/youtube/v3/channels") {
        parameter("part", "snippet,statistics,contentDetails")
        parameter("id", channelId.joinToString(","))
        parameter("maxResults", 50)
        parameter("key", apiKey)
    }.body()

    return chanRaw.items.map { item -> ChannelInfo(
        name = item.snippet.title,
        channelID = item.id,
        subscribers = item.statistics.subscriberCount.toLong(),
        videos = item.statistics.videoCount.toInt(),
        avatar = item.snippet.thumbnails.high.url,
        uploadsPlaylistID = item.contentDetails.relatedPlaylists.uploads
    ) }
}

suspend fun getChannelDataAndIds(channelId: List<String>): Pair<List<ChannelInfo>, List<String>> {

    // --- STEP 1: Fetch Channel Metadata ---
    val channelInfo = getChannelInfo(channelId)

    val channelIDs= getChannelVideos(channelInfo)

    return Pair(channelInfo, channelIDs)
}

@Serializable
data class ChannelResponse(val items: List<ChannelItem>)

@Serializable
data class ChannelItem(
    val id: String,
    val snippet: ChannelSnippet,
    val statistics: ChannelStats,
    val contentDetails: ChannelContentDetails
)

@Serializable
data class ChannelSnippet(val title: String, val thumbnails: Thumbnails)

@Serializable
data class Thumbnails(val high: ThumbnailUrl)

@Serializable
data class ThumbnailUrl(val url: String)

@Serializable
data class ChannelStats(val subscriberCount: String, val videoCount: String)

@Serializable
data class ChannelContentDetails(val relatedPlaylists: RelatedPlaylists)

@Serializable
data class RelatedPlaylists(val uploads: String)

@Serializable
data class PlaylistResponse(val items: List<PlaylistItem>, val nextPageToken: String? = null)

@Serializable
data class PlaylistItem(val contentDetails: VideoIdHolder)

@Serializable
data class VideoIdHolder(val videoId: String)

suspend fun getVideoDetails(videoIds: List<String>): List<VideoInfo> {
    val results = mutableListOf<VideoInfo>()

    // YouTube API allows max 50 IDs per request
    val chunks = videoIds.chunked(50)

    for (chunk in chunks) {
        val idString = chunk.joinToString(",")

        val response: VideoDetailResponse = client.get("https://www.googleapis.com/youtube/v3/videos") {
            parameter("part", "snippet,statistics,contentDetails")
            parameter("id", idString)
            parameter("key", apiKey)
        }.body()

        response.items.forEach { item ->
            results.add(
                VideoInfo(
                    name = item.snippet.title,
                    videoID = encodeVideoID(item.id),
                    // Converts ISO 8601 (PT5M20S) to total seconds
                    duration = Duration.parse(item.contentDetails.duration).inWholeMilliseconds,
                    views = item.statistics.viewCount?.toLong() ?: 0L,
                    uploadDate = Instant.parse(item.snippet.publishedAt),
                    thumbnailURL = item.snippet.thumbnails.high.url,
                    author = item.snippet.channelTitle
                )
            )
        }
    }
    return results
}

@Serializable
data class VideoDetailResponse(val items: List<VideoItemDetail>)

@Serializable
data class VideoItemDetail(
    val id: String,
    val snippet: VideoSnippet,
    val contentDetails: VideoContentDetails,
    val statistics: VideoStats
)

@Serializable
data class VideoSnippet(
    val title: String,
    val channelTitle: String,
    val publishedAt: String,
    val thumbnails: Thumbnails
)

@Serializable
data class VideoContentDetails(
    val duration: String // e.g., "PT1H2M10S"
)

@Serializable
data class VideoStats(
    val viewCount: String?
)