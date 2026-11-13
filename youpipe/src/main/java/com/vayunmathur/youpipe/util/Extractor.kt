package com.vayunmathur.youpipe.util
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.StaticMirror
import com.vayunmathur.youpipe.ui.ChannelInfo
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.time.toKotlinInstant

@Serializable
data class SponsorSegment(
    val category: String,
    val segment: List<Float>,
    @SerialName("UUID") val uuid: String,
) {
    val start: Long get() = (segment[0] * 1000).toLong()
    val end: Long get() = (segment[1] * 1000).toLong()
}

/** Decodes HTML entities/markup to plain text. */
fun String.decodeHtml(): String =
    HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()

/** Maps a NewPipe [StreamInfoItem] to a [VideoInfo], or null if it has no upload date. */
fun StreamInfoItem.toVideoInfo(): VideoInfo? {
    val date = getUploadDate() ?: return null
    return VideoInfo(
        name.decodeHtml(),
        videoURLtoID(url),
        getDuration(),
        getViewCount(),
        date.instant.toKotlinInstant(),
        thumbnails.firstOrNull()?.url ?: "",
        getUploaderName().orEmpty().decodeHtml(),
        getContentAvailability() == ContentAvailability.PAID,
    )
}

fun videoURLtoID(url: String): Long {
    val encoded = url.toUri().getQueryParameter("v")!!
    return ByteBuffer.wrap(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(encoded)).long
}

fun channelURLtoID(url: String): String {
    return url.substringAfterLast("/")
}

fun encodeVideoID(id: String): Long {
    return ByteBuffer.wrap(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(id)).long
}

fun decodeVideoID(id: Long): String {
    val buffer = ByteBuffer.allocate(java.lang.Long.BYTES)
    buffer.putLong(id)
    return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(buffer.array())
}

fun videoIDtoURL(id: Long): String = "https://www.youtube.com/watch?v=${decodeVideoID(id)}"

/**
 * Extracts a YouTube video id from arbitrary shared text (a pasted link, a `watch?v=`,
 * `youtu.be/`, `/shorts/`, `/live/`, or `/embed/` URL, possibly surrounded by other text) and encodes
 * it to the app's [Long] id. Returns null when no 11-char id can be found.
 */
fun parseSharedVideoId(text: String): Long? {
    val match = Regex("""(?:v=|/shorts/|/live/|/embed/|youtu\.be/)([A-Za-z0-9_-]{11})""").find(text)
        ?: return null
    return runCatching { encodeVideoID(match.groupValues[1]) }.getOrNull()
}

fun channelIDtoURL(id: String): String {
    return if (id.startsWith("@")) {
        "https://www.youtube.com/$id"
    } else {
        "https://www.youtube.com/channel/$id"
    }
}

suspend fun getVideoInfo(videoId: Long): VideoInfo = coroutineScope {
    val idString = decodeVideoID(videoId)
    val ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$idString")
    ex.fetchPage()
    VideoInfo(
        ex.getName().decodeHtml(),
        videoId,
        ex.getLength(),
        ex.getViewCount(),
        ex.getUploadDate()!!.instant.toKotlinInstant(),
        ex.getThumbnails().first().url,
        ex.getUploaderName().decodeHtml()
    )
}

fun getChannelVideos(channelId: String): Sequence<VideoInfo> = sequence {
    val ex = ServiceList.YouTube.getChannelTabExtractorFromId(channelId, "videos")
    ex.fetchPage()
    var page = ex.getInitialPage()
    while (true) {
        page.getItems().filterIsInstance<StreamInfoItem>().forEach { item ->
            item.toVideoInfo()?.let { yield(it) }
        }
        @Suppress("UNCHECKED_CAST")
        val nextPage = nextChannelPage(ex, page) as org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage<org.schabi.newpipe.extractor.InfoItem>?
        page = nextPage ?: break
    }
}

private fun nextChannelPage(
    ex: org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor,
    page: org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage<*>,
): org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage<*>? {
    if (!page.hasNextPage()) return null
    val next = page.nextPage ?: return null
    return ex.getPage(next)
}

/** First page of the YouTube Trending kiosk, mapped to [VideoInfo]. */
suspend fun getTrendingVideos(): List<VideoInfo> = coroutineScope {
    val ex = ServiceList.YouTube.getKioskList().getDefaultKioskExtractor()
        ?: return@coroutineScope emptyList()
    ex.fetchPage()
    ex.getInitialPage().getItems().filterIsInstance<StreamInfoItem>().mapNotNull { it.toVideoInfo() }
}

/** First page of video results for [query], mapped to [VideoInfo]. */
suspend fun searchVideos(query: String): List<VideoInfo> = coroutineScope {
    val ex = ServiceList.YouTube.getSearchExtractor(query)
    ex.fetchPage()
    ex.getInitialPage().getItems().filterIsInstance<StreamInfoItem>().mapNotNull { it.toVideoInfo() }
}

suspend fun getChannelInfo(channelId: String): ChannelInfo = getChannelInfoFromURL(channelIDtoURL(channelId))

suspend fun getChannelInfoFromURL(url: String): ChannelInfo = coroutineScope {
    val ex = ServiceList.YouTube.getChannelExtractor(url)
    ex.fetchPage()
    ChannelInfo(
        ex.getName().decodeHtml(),
        ex.getId(),
        ex.getSubscriberCount(),
        0,
        ex.getAvatars().firstOrNull()?.url ?: "",
    )
}

@Serializable
data class DeArrowTitle(
    val title: String,
    val original: Boolean,
    val votes: Int,
    val locked: Boolean,
    @SerialName("UUID") val uuid: String,
)

@Serializable
data class DeArrowThumbnail(
    val timestamp: Double? = null,
    val original: Boolean,
    val votes: Int,
    val locked: Boolean,
    @SerialName("UUID") val uuid: String,
)

@Serializable
data class DeArrowBranding(
    val titles: List<DeArrowTitle>,
    val thumbnails: List<DeArrowThumbnail>,
    val randomTime: Double,
    val videoDuration: Double? = null,
)

/**
 * SponsorBlock + DeArrow data APIs are mirrored on the self-hosted proxy (and
 * published as static shards, see [SPONSORBLOCK_STATIC])
 * (see location_share_server `sb_build.sh` + handlers/sponsorblock.rs), served
 * from a local copy of the SponsorBlock database. DeArrow thumbnail *frames*
 * are now self-hosted via the same origin (yt-dlp + ffmpeg renderer implemented
 * in `location_share_server/src/handlers/dearrow.rs`, inspired by
 * ajayyy/DeArrowThumbnailCache but written from scratch), with fallback proxy
 * to dearrow-thumb.ajay.app.
 *
 * `internal var` (not const) so tests can point it at a closed port to
 * exercise the best-effort fallbacks; production keeps the default below.
 */
internal var SPONSORBLOCK_MIRROR = "https://api.maproj.org"
internal var DEARROW_THUMB_MIRROR = "https://api.maproj.org/api/dearrow/thumbnail"

/**
 * The same SponsorBlock/DeArrow data as static shards on R2
 * (location_share_server `src/static_r2/sb_shards.rs`): one file per first
 * [SB_SHARD_PREFIX_HEX] hex digits of sha256(videoID), holding every video in
 * that range. Read first; the endpoints above are the fallback. A shard that
 * loads is authoritative (a video missing from it has no data), and since only
 * the hash prefix is requested, nobody learns which video is being watched.
 */
internal var SPONSORBLOCK_STATIC = "${StaticMirror.BASE}/sb/v1"

/** Must match the server's `PREFIX_HEX`. */
private const val SB_SHARD_PREFIX_HEX = 4

private val sbShardJson = Json { ignoreUnknownKeys = true }

/** First [SB_SHARD_PREFIX_HEX] lowercase hex digits of sha256([videoId]). */
internal fun sbShardPrefix(videoId: String): String =
    MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(SB_SHARD_PREFIX_HEX)

@Serializable
internal data class SbShardVideo(
    val videoID: String,
    val segments: List<SbShardSegment> = emptyList(),
)

@Serializable
internal data class SbShardSegment(
    val category: String,
    val segment: List<Float>,
    @SerialName("UUID") val uuid: String,
    val actionType: String = "skip",
)

/**
 * [videoId]'s segments out of a segments shard, limited to the action types the
 * endpoint returns by default (skip, mute); the shard carries every type.
 */
internal fun segmentsFromShard(body: String, videoId: String): List<SponsorSegment> =
    sbShardJson.decodeFromString<List<SbShardVideo>>(body)
        .firstOrNull { it.videoID == videoId }
        ?.segments.orEmpty()
        .filter { it.actionType == "skip" || it.actionType == "mute" }
        .map { SponsorSegment(it.category, it.segment, it.uuid) }

/** [videoId]'s branding out of a branding shard; absent = no branding (empty, as the endpoint answers). */
internal fun brandingFromShard(body: String, videoId: String): DeArrowBranding =
    sbShardJson.decodeFromString<Map<String, DeArrowBranding>>(body)[videoId]
        ?: DeArrowBranding(titles = emptyList(), thumbnails = emptyList(), randomTime = 0.0)

/** The shard body for [videoId] under [kind] (`segments` / `branding`), or null when not published. */
private suspend fun sbShard(kind: String, videoId: String): String? =
    StaticMirror.getStatic("$SPONSORBLOCK_STATIC/$kind/${sbShardPrefix(videoId)}.json")?.text

suspend fun getDeArrowBranding(videoId: Long): DeArrowBranding? {
    val idString = decodeVideoID(videoId)
    return try {
        sbShard("branding", idString)?.let { runCatching { brandingFromShard(it, idString) }.getOrNull() }
            ?: NetworkClient.getJson<DeArrowBranding>("$SPONSORBLOCK_MIRROR/api/branding?videoID=$idString")
    } catch (_: Exception) {
        // Best-effort enrichment: branding is optional, feed plays fine without it.
        null
    }
}

fun DeArrowBranding.trustedTitle(): String? {
    val title = titles.firstOrNull() ?: return null
    if (title.original) return null
    if (!title.locked && title.votes < 0) return null
    return title.title.replace(">", "").trim()
}

fun DeArrowBranding.trustedThumbnailUrl(videoId: Long): String? {
    val thumb = thumbnails.firstOrNull() ?: return null
    if (thumb.original) return null
    if (!thumb.locked && thumb.votes < 0) return null
    val timestamp = thumb.timestamp ?: return null
    // Self-hosted renderer on api.maproj.org (yt-dlp + ffmpeg), fallback proxy to ajay.app server-side
    return "$DEARROW_THUMB_MIRROR?videoID=${decodeVideoID(videoId)}&time=$timestamp"
}

suspend fun getSponsorSegments(videoId: Long): List<SponsorSegment> {
    val idString = decodeVideoID(videoId)
    return try {
        sbShard("segments", idString)?.let { runCatching { segmentsFromShard(it, idString) }.getOrNull() }
            ?: NetworkClient.getJson("$SPONSORBLOCK_MIRROR/api/skipSegments?videoID=$idString")
    } catch (_: Exception) {
        // Best-effort enrichment: no segments simply means no auto-skip for this video.
        emptyList()
    }
}
