package com.vayunmathur.youpipe.nativeext

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Kotlin-facing API of the Rust extractor.
 *
 * Unwraps the JSON envelope from [YouPipeNative] and turns a failure into an exception, so callers
 * see ordinary Kotlin types. Calls block on the network, so each hops to [Dispatchers.IO].
 *
 * Throughout, a count of `-1` means YouTube did not report one — it is never a real value.
 */
object NativeExtractor {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * @param filter `videos`, `channels`, `playlists`, or null for everything.
     * @throws NativeExtractorException if the request or parse failed outright. Per-item problems
     *   arrive in the result's `errors` instead, leaving the good results usable.
     */
    suspend fun search(
        query: String,
        filter: String? = null,
        hl: String = DEFAULT_HL,
        gl: String = DEFAULT_GL,
    ): SearchResult = io { unwrap(YouPipeNative.search(query, filter, hl, gl)) }

    suspend fun searchPage(token: String, hl: String = DEFAULT_HL, gl: String = DEFAULT_GL): SearchResult =
        io { unwrap(YouPipeNative.searchPage(token, hl, gl)) }

    suspend fun suggestions(query: String, hl: String = DEFAULT_HL, gl: String = DEFAULT_GL): List<String> =
        io { unwrap(YouPipeNative.suggestions(query, hl, gl)) }

    /**
     * Metadata and playable streams for a video.
     *
     * When [StreamInfo.sabrOnly] is true YouTube returned no direct stream URLs and playback must
     * go through the app's SABR path.
     */
    suspend fun streamInfo(videoId: String, hl: String = DEFAULT_HL, gl: String = DEFAULT_GL): StreamInfo =
        io { unwrap(YouPipeNative.streamInfo(videoId, hl, gl)) }

    /** @param id a `UC…` channel id or an `@handle`. */
    suspend fun channelInfo(id: String, hl: String = DEFAULT_HL, gl: String = DEFAULT_GL): ChannelInfo =
        io { unwrap(YouPipeNative.channelInfo(id, hl, gl)) }

    suspend fun playlistInfo(id: String, hl: String = DEFAULT_HL, gl: String = DEFAULT_GL): PlaylistInfo =
        io { unwrap(YouPipeNative.playlistInfo(id, hl, gl)) }

    suspend fun trending(hl: String = DEFAULT_HL, gl: String = DEFAULT_GL): ItemsPage =
        io { unwrap(YouPipeNative.trending(hl, gl)) }

    /** Next page for a channel, playlist or trending list. */
    suspend fun browseContinuation(
        token: String,
        hl: String = DEFAULT_HL,
        gl: String = DEFAULT_GL,
    ): ItemsPage = io { unwrap(YouPipeNative.browseContinuation(token, hl, gl)) }

    /** First page of comments; empty when comments are disabled. */
    suspend fun comments(videoId: String, hl: String = DEFAULT_HL, gl: String = DEFAULT_GL): CommentsPage =
        io { unwrap(YouPipeNative.comments(videoId, hl, gl)) }

    suspend fun commentsPage(token: String, hl: String = DEFAULT_HL, gl: String = DEFAULT_GL): CommentsPage =
        io { unwrap(YouPipeNative.commentsPage(token, hl, gl)) }

    private suspend inline fun <T> io(crossinline block: () -> T): T =
        withContext(Dispatchers.IO) { block() }

    // internal (was private) so unit tests can exercise envelope handling without the native library.
    internal inline fun <reified T> unwrap(payload: String): T {
        val envelope = json.decodeFromString<Envelope<T>>(payload)
        if (!envelope.ok || envelope.data == null) {
            throw NativeExtractorException(envelope.error ?: "unknown extractor error")
        }
        return envelope.data
    }

    @Serializable
    @PublishedApi
    internal data class Envelope<T>(val ok: Boolean, val data: T? = null, val error: String? = null)

    private const val DEFAULT_HL = "en-GB"
    private const val DEFAULT_GL = "GB"
}

class NativeExtractorException(message: String) : Exception(message)

/** Value used wherever YouTube reported no count. */
const val UNKNOWN_COUNT: Long = -1

// ---------------------------------------------------------------------------
// Lists
// ---------------------------------------------------------------------------

@Serializable
data class SearchResult(
    val items: List<SearchItem> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null,
    @SerialName("search_suggestion") val searchSuggestion: String? = null,
    @SerialName("is_corrected_search") val isCorrectedSearch: Boolean = false,
    /** Non-fatal per-item parse failures; the surrounding results are still valid. */
    val errors: List<String> = emptyList(),
)

@Serializable
data class ItemsPage(
    val items: List<SearchItem> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null,
    val errors: List<String> = emptyList(),
)

@Serializable
sealed interface SearchItem {
    val url: String
    val name: String

    @Serializable
    @SerialName("STREAM")
    data class Stream(
        override val url: String,
        override val name: String,
        @SerialName("duration_seconds") val durationSeconds: Long? = null,
        @SerialName("uploader_name") val uploaderName: String? = null,
        @SerialName("uploader_url") val uploaderUrl: String? = null,
        @SerialName("uploader_verified") val uploaderVerified: Boolean = false,
        @SerialName("textual_upload_date") val textualUploadDate: String? = null,
        @SerialName("view_count") val viewCount: Long = UNKNOWN_COUNT,
        val thumbnails: List<Thumbnail> = emptyList(),
        @SerialName("is_live") val isLive: Boolean = false,
        @SerialName("is_short") val isShort: Boolean = false,
    ) : SearchItem

    @Serializable
    @SerialName("CHANNEL")
    data class Channel(
        override val url: String,
        override val name: String,
        val description: String? = null,
        @SerialName("subscriber_count") val subscriberCount: Long = UNKNOWN_COUNT,
        @SerialName("stream_count") val streamCount: Long = UNKNOWN_COUNT,
        val verified: Boolean = false,
        val thumbnails: List<Thumbnail> = emptyList(),
    ) : SearchItem

    @Serializable
    @SerialName("PLAYLIST")
    data class Playlist(
        override val url: String,
        override val name: String,
        @SerialName("uploader_name") val uploaderName: String? = null,
        @SerialName("stream_count") val streamCount: Long = UNKNOWN_COUNT,
        val thumbnails: List<Thumbnail> = emptyList(),
    ) : SearchItem
}

@Serializable
data class Thumbnail(val url: String, val width: Long = 0, val height: Long = 0)

// ---------------------------------------------------------------------------
// Video playback
// ---------------------------------------------------------------------------

@Serializable
enum class StreamKind {
    @SerialName("VIDEO") VIDEO,
    @SerialName("LIVE") LIVE,
    /** A finished livestream, still served from its DVR window. */
    @SerialName("POST_LIVE") POST_LIVE,
}

@Serializable
data class StreamInfo(
    val id: String,
    val url: String,
    val name: String,
    val kind: StreamKind = StreamKind.VIDEO,
    @SerialName("duration_seconds") val durationSeconds: Long = 0,
    @SerialName("view_count") val viewCount: Long = UNKNOWN_COUNT,
    @SerialName("like_count") val likeCount: Long = UNKNOWN_COUNT,
    @SerialName("uploader_name") val uploaderName: String? = null,
    @SerialName("uploader_url") val uploaderUrl: String? = null,
    @SerialName("uploader_verified") val uploaderVerified: Boolean = false,
    @SerialName("uploader_subscriber_count") val uploaderSubscriberCount: Long = UNKNOWN_COUNT,
    @SerialName("uploader_avatars") val uploaderAvatars: List<Thumbnail> = emptyList(),
    val description: String? = null,
    @SerialName("textual_upload_date") val textualUploadDate: String? = null,
    val thumbnails: List<Thumbnail> = emptyList(),
    val category: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("age_limit") val ageLimit: Long = 0,
    /** Progressive (muxed) streams. */
    @SerialName("video_streams") val videoStreams: List<VideoStream> = emptyList(),
    @SerialName("audio_streams") val audioStreams: List<AudioStream> = emptyList(),
    /** Adaptive video tracks, to be paired with an [AudioStream]. */
    @SerialName("video_only_streams") val videoOnlyStreams: List<VideoStream> = emptyList(),
    @SerialName("dash_manifest_url") val dashManifestUrl: String? = null,
    @SerialName("hls_manifest_url") val hlsManifestUrl: String? = null,
    /** True when YouTube served only SABR formats, so the app's SABR path must take over. */
    @SerialName("sabr_only") val sabrOnly: Boolean = false,
    @SerialName("server_abr_streaming_url") val serverAbrStreamingUrl: String? = null,
    val errors: List<String> = emptyList(),
)

@Serializable
data class VideoStream(
    val url: String,
    val itag: Long = 0,
    @SerialName("mime_type") val mimeType: String = "",
    val codec: String? = null,
    val resolution: String? = null,
    val width: Long = 0,
    val height: Long = 0,
    val fps: Long = 0,
    val bitrate: Long = 0,
    @SerialName("content_length") val contentLength: Long = 0,
    @SerialName("init_start") val initStart: Long = 0,
    @SerialName("init_end") val initEnd: Long = 0,
    @SerialName("index_start") val indexStart: Long = 0,
    @SerialName("index_end") val indexEnd: Long = 0,
    @SerialName("video_only") val videoOnly: Boolean = false,
)

@Serializable
data class AudioStream(
    val url: String,
    val itag: Long = 0,
    @SerialName("mime_type") val mimeType: String = "",
    val codec: String? = null,
    val bitrate: Long = 0,
    @SerialName("average_bitrate") val averageBitrate: Long = 0,
    @SerialName("sample_rate") val sampleRate: Long = 0,
    val channels: Long = 2,
    @SerialName("content_length") val contentLength: Long = 0,
    @SerialName("init_start") val initStart: Long = 0,
    @SerialName("init_end") val initEnd: Long = 0,
    @SerialName("index_start") val indexStart: Long = 0,
    @SerialName("index_end") val indexEnd: Long = 0,
    /** Language track id such as `en.4`, when the video has dubs. */
    @SerialName("track_id") val trackId: String? = null,
    @SerialName("track_name") val trackName: String? = null,
    @SerialName("is_drc") val isDrc: Boolean = false,
)

// ---------------------------------------------------------------------------
// Channel / playlist / comments
// ---------------------------------------------------------------------------

@Serializable
data class ChannelInfo(
    val id: String,
    val url: String,
    val name: String,
    val description: String? = null,
    val avatars: List<Thumbnail> = emptyList(),
    val banners: List<Thumbnail> = emptyList(),
    @SerialName("subscriber_count") val subscriberCount: Long = UNKNOWN_COUNT,
    val verified: Boolean = false,
    val items: List<SearchItem> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null,
    val errors: List<String> = emptyList(),
)

@Serializable
data class PlaylistInfo(
    val id: String,
    val url: String,
    val name: String,
    val description: String? = null,
    @SerialName("uploader_name") val uploaderName: String? = null,
    @SerialName("uploader_url") val uploaderUrl: String? = null,
    val thumbnails: List<Thumbnail> = emptyList(),
    @SerialName("stream_count") val streamCount: Long = UNKNOWN_COUNT,
    val items: List<SearchItem> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null,
    val errors: List<String> = emptyList(),
)

@Serializable
data class Comment(
    val id: String = "",
    val text: String = "",
    @SerialName("author_name") val authorName: String? = null,
    @SerialName("author_url") val authorUrl: String? = null,
    @SerialName("author_thumbnails") val authorThumbnails: List<Thumbnail> = emptyList(),
    @SerialName("author_verified") val authorVerified: Boolean = false,
    @SerialName("like_count") val likeCount: Long = UNKNOWN_COUNT,
    @SerialName("reply_count") val replyCount: Long = 0,
    @SerialName("published_time") val publishedTime: String? = null,
    @SerialName("is_pinned") val isPinned: Boolean = false,
    @SerialName("is_hearted") val isHearted: Boolean = false,
)

@Serializable
data class CommentsPage(
    val comments: List<Comment> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null,
    val errors: List<String> = emptyList(),
)
