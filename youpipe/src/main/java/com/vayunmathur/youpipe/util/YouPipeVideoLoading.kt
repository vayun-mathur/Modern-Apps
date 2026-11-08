package com.vayunmathur.youpipe.util

import com.vayunmathur.library.log.Log
import androidx.lifecycle.viewModelScope
import com.vayunmathur.youpipe.data.CachedRelatedVideo
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.Comment
import com.vayunmathur.youpipe.ui.SubtitleTrack
import com.vayunmathur.youpipe.ui.VideoChapter
import com.vayunmathur.youpipe.ui.VideoData
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.ui.fromHTML
import com.vayunmathur.youpipe.ui.getAudioCodecName
import com.vayunmathur.youpipe.ui.getVideoCodecName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import kotlin.time.Clock
import kotlin.time.toKotlinInstant

private const val TAG = "YouPipeViewModel"
private const val MILLIS_PER_SECOND = 1000L
private const val FALLBACK_DOWNLOAD_WIDTH = 1920
private const val FALLBACK_DOWNLOAD_HEIGHT = 1080

fun YouPipeViewModel.loadVideo(videoID: Long, downloadedVideo: DownloadedVideo?) {
    videoJob?.cancel()
    sponsorJob?.cancel()
    videoStateMutable.value = YouPipeViewModel.VideoState()

    launchSponsorSegments(videoID)
    fetchDeArrowForVideos(listOf(videoID))

    videoJob = viewModelScope.launch {
        val url = videoIDtoURL(videoID)
        val youtubeService = ServiceList.YouTube
        // Broad catch is deliberate: the extractor throws across IO, parsing and
        // runtime failures, and every mode must land in error state, never crash.
        @Suppress("TooGenericExceptionCaught")
        suspend fun loadSafely() {
            try {
                loadVideoContent(videoID, downloadedVideo, url)
                loadComments(youtubeService, url)
            } catch (error: Exception) {
                handleVideoLoadFailure(downloadedVideo, error)
            }
        }
        loadSafely()
    }
}

private fun YouPipeViewModel.launchSponsorSegments(videoID: Long) {
    // Sponsor segments load in parallel.
    sponsorJob = viewModelScope.launch(Dispatchers.IO) {
        val segs = getSponsorSegments(videoID)
        videoStateMutable.update { it.copy(sponsorSegments = segs) }
    }
}

private suspend fun YouPipeViewModel.loadVideoContent(
    videoID: Long,
    downloadedVideo: DownloadedVideo?,
    url: String,
) {
    withContext(Dispatchers.IO) {
        val ex = ServiceList.YouTube.getStreamExtractor(url)
        ex.fetchPage()

        val streams = loadContentStreams(ex, downloadedVideo)
        val data = buildVideoData(ex)
        // Related videos are optional; upstream fetches them via
        // ExtractorHelper.getRelatedItemsOrLogError precisely so a failure here
        // cannot take down playback. Calling getRelatedItems() bare meant one bad
        // renderer surfaced as "Video load error" on a video that had extracted fine.
        val related = loadRelatedVideos(ex)

        videoStateMutable.update {
            it.copy(
                data = data,
                videoStreams = streams.videoStreams,
                audioStreams = streams.audioStreams,
                segments = streams.segments,
                subtitles = streams.subtitles,
                relatedVideos = related,
            )
        }

        fetchDeArrowForVideos(related.map { it.videoID })
        cacheRelatedVideos(videoID, related)
    }
}

private suspend fun YouPipeViewModel.loadContentStreams(
    ex: org.schabi.newpipe.extractor.stream.StreamExtractor,
    downloadedVideo: DownloadedVideo?,
): LoadedNetworkStreams {
    if (downloadedVideo != null) {
        val (videoList, audioList) = downloadedStreams(downloadedVideo)
        return LoadedNetworkStreams(videoList, audioList, emptyList(), emptyList())
    }
    return loadNetworkStreams(ex)
}

private fun buildVideoData(
    ex: org.schabi.newpipe.extractor.stream.StreamExtractor,
) = VideoData(
    ex.getName().decodeHtml(),
    ex.getViewCount(),
    ex.getLength(),
    // Live/upcoming videos may carry no upload date (extractor returns null);
    // fall back to now rather than failing a load that extracted fine.
    ex.getUploadDate()?.instant?.toKotlinInstant() ?: Clock.System.now(),
    ex.getThumbnails().first().url,
    ex.getUploaderName().decodeHtml(),
    channelURLtoID(ex.getUploaderUrl()),
    ex.getUploaderAvatars().first().url,
    ex.getDescription().getContent().fromHTML()
)

private suspend fun YouPipeViewModel.loadRelatedVideos(
    ex: org.schabi.newpipe.extractor.stream.StreamExtractor,
): List<VideoInfo> = runCatching {
    ex.getRelatedItems()?.getItems()?.filterIsInstance<StreamInfoItem>()
        ?.mapNotNull { it.toVideoInfo() } ?: emptyList()
}.getOrElse { error: Throwable ->
    Log.status("YouPipeViewModel", "Could not get related videos", error)
    emptyList()
}

private suspend fun YouPipeViewModel.cacheRelatedVideos(videoID: Long, related: List<VideoInfo>) {
    if (related.isEmpty()) return
    repository.upsertCachedRelatedVideos(related.map {
        CachedRelatedVideo(
            sourceVideoID = videoID,
            videoItem = it,
            cachedAt = Clock.System.now()
        )
    })
}

private suspend fun YouPipeViewModel.loadComments(
    youtubeService: org.schabi.newpipe.extractor.StreamingService,
    url: String,
) {
    withContext(Dispatchers.IO) {
        val cex = youtubeService.getCommentsExtractor(url)
            ?: return@withContext
        cex.fetchPage()
        val comments = cex.getInitialPage().getItems().map { comment ->
            mapExtractorComment(comment)
        }
        videoStateMutable.update { it.copy(comments = comments) }
    }
}

private fun mapExtractorComment(
    c: org.schabi.newpipe.extractor.comments.CommentsInfoItem,
): Comment {
    val content = if (c.getCommentText().getType() == Description.HTML) {
        c.getCommentText().getContent().fromHTML()
    } else {
        c.getCommentText().getContent().decodeHtml()
    }
    return Comment(
        content,
        c.getUploaderName().orEmpty().decodeHtml(),
        c.getLikeCount(),
        0
    )
}

private fun YouPipeViewModel.handleVideoLoadFailure(
    downloadedVideo: DownloadedVideo?,
    error: Throwable,
) {
    if (downloadedVideo != null) {
        val video = downloadedVideo
        val data = VideoData(
            video.videoItem.name,
            video.videoItem.views,
            video.videoItem.duration,
            video.videoItem.uploadDate,
            video.videoItem.thumbnailURL,
            video.videoItem.author,
            "",
            "",
            ""
        )
        val (videoList, audioList) = downloadedStreams(video)
        videoStateMutable.update { it.copy(data = data, videoStreams = videoList, audioStreams = audioList) }
    } else {
        videoStateMutable.update { it.copy(error = true) }
        Log.error(TAG, "Video load error", error)
    }
}

private class LoadedNetworkStreams(
    val videoStreams: List<VideoStream>,
    val audioStreams: List<AudioStream>,
    val segments: List<VideoChapter>,
    val subtitles: List<SubtitleTrack>,
)

private fun loadNetworkStreams(
    ex: org.schabi.newpipe.extractor.stream.StreamExtractor,
): LoadedNetworkStreams {
    val segments = ex.getStreamSegments().map {
        VideoChapter(
            (it.getStartTimeSeconds() * MILLIS_PER_SECOND).toInt(),
            it.getTitle(),
            it.getPreviewUrl(),
        )
    }
    val subtitles = ex.getSubtitles(
        org.schabi.newpipe.extractor.MediaFormat.VTT,
    ).map { it.toSubtitleTrack() }
    val partition = partitionStreams(ex)
    val videoId = ex.getId()
    logStreamPartition(videoId, partition)
    registerSabrInfo(videoId, partition.sabrVideoOnly)

    val videoStreams = selectVideoStreams(ex, partition, videoId)
    val audioStreams = selectAudioStreams(partition, videoId)
    return LoadedNetworkStreams(videoStreams, audioStreams, segments, subtitles)
}

private class StreamPartition(
    val progVideoOnly: List<org.schabi.newpipe.extractor.stream.VideoStream>,
    val progAudio: List<org.schabi.newpipe.extractor.stream.AudioStream>,
    val sabrVideoOnly: List<org.schabi.newpipe.extractor.stream.VideoStream>,
    val sabrAudio: List<org.schabi.newpipe.extractor.stream.AudioStream>,
)

private fun partitionStreams(
    ex: org.schabi.newpipe.extractor.stream.StreamExtractor,
): StreamPartition {
    val sabrMethod = org.schabi.newpipe.extractor.stream.DeliveryMethod.SABR
    val rawVideoOnly = ex.getVideoOnlyStreams()
    val rawAudio = ex.getAudioStreams()
    return StreamPartition(
        progVideoOnly = rawVideoOnly.filter { it.getDeliveryMethod() != sabrMethod },
        progAudio = rawAudio.filter { it.getDeliveryMethod() != sabrMethod },
        sabrVideoOnly = rawVideoOnly.filter { it.getDeliveryMethod() == sabrMethod },
        sabrAudio = rawAudio.filter { it.getDeliveryMethod() == sabrMethod },
    )
}

private fun logStreamPartition(videoId: String, partition: StreamPartition) {
    Log.dev(
        "YouPipeSabr",
        "video $videoId streams prog(v=${partition.progVideoOnly.size}," +
            "a=${partition.progAudio.size}) sabr(v=${partition.sabrVideoOnly.size}," +
            "a=${partition.sabrAudio.size})"
    )
}

private fun registerSabrInfo(
    videoId: String,
    sabrVideoOnly: List<org.schabi.newpipe.extractor.stream.VideoStream>,
) {
    // Register SABR session metadata for the playback service if present.
    // NOTE: do NOT prewarm the content PoToken here — doing so regressed SABR
    // bootstrap (403 on init range / "no exact indexes"). The token is minted
    // at play time by createSourceSpec, which is the known-working ordering.
    if (sabrVideoOnly.isEmpty()) return
    sabrVideoOnly.firstOrNull {
        it.getDeliveryMethodInfo() is
            org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
    }?.let {
        val sabrInfo = it.getDeliveryMethodInfo() as
            org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
        com.vayunmathur.youpipe.util.sabr.SabrNgSessionStore.putExtractorInfo(
            videoId, sabrInfo
        )
    }
}

private fun selectVideoStreams(
    ex: org.schabi.newpipe.extractor.stream.StreamExtractor,
    partition: StreamPartition,
    videoId: String,
): List<VideoStream> {
    if (partition.progVideoOnly.isEmpty() && partition.sabrVideoOnly.isEmpty()) {
        return ex.getVideoStreams().map { it.toDomain() }
            .groupBy { it.height }
            .map { (_, streamsAtRes) -> streamsAtRes.maxWith(videoStreamPreference) }
            .sortedByDescending { it.height }
    }
    // Combine progressive + SABR video-only, then keep exactly one stream
    // per resolution: highest fps wins, then best codec (av1 > vp9 > avc).
    // Do NOT filter >1080p.
    return (
        partition.progVideoOnly.map { it.toDomain() } +
            partition.sabrVideoOnly.map { it.toSabrDomain(videoId) }
        )
        .groupBy { it.height }
        .map { (_, streamsAtRes) -> streamsAtRes.maxWith(videoStreamPreference) }
        .sortedByDescending { it.height }
}

private fun selectAudioStreams(partition: StreamPartition, videoId: String): List<AudioStream> {
    if (partition.progVideoOnly.isEmpty() && partition.sabrVideoOnly.isEmpty()) {
        return emptyList()
    }
    // Audio: opus only (F-Droid friendly, higher quality). Filter before sort.
    val rawAudioCandidates = if (partition.progAudio.isNotEmpty()) {
        partition.progAudio.map { it.toDomain() }
    } else {
        partition.sabrAudio.map { it.toSabrDomain(videoId) }
    }
    return rawAudioCandidates
        .filter { it.codec == AUDIO_CODEC_OPUS }
        .let { opusList ->
            // Fallback to all if no opus (rare, but avoid empty track list)
            if (opusList.isEmpty()) rawAudioCandidates else opusList
        }
        .sortedWith(
            compareByDescending<AudioStream> { it.bitrate }
        )
}

private const val AUDIO_CODEC_OPUS = "opus"

/**
 * When a download completes while the user is on the video page, swap the
 * playable streams over to the on-disk copy (mirrors the original
 * `LaunchedEffect(downloadedVideo)` in VideoPage).
 */
fun YouPipeViewModel.applyDownloadedStreams(downloadedVideo: DownloadedVideo) {
    val (videoList, audioList) = downloadedStreams(downloadedVideo)
    videoStateMutable.update { it.copy(videoStreams = videoList, audioStreams = audioList, segments = emptyList()) }
}

fun YouPipeViewModel.clearVideoError() {
    videoStateMutable.update { it.copy(error = false) }
}

internal fun downloadedStreams(dv: DownloadedVideo): Pair<List<VideoStream>, List<AudioStream>> {
    val video = listOf(
        VideoStream(
            dv.filePath, FALLBACK_DOWNLOAD_WIDTH, FALLBACK_DOWNLOAD_HEIGHT, 0, 30, "Downloaded", "avc", 0L
        )
    )
    // Keep as opus for consistency with opus-only filter in UI
    val audio = if (dv.audioPath != null) listOf(AudioStream(dv.audioPath, 0, "Default", "opus", 0L)) else emptyList()
    return video to audio
}

internal fun org.schabi.newpipe.extractor.stream.VideoStream.toDomain() = VideoStream(
    getContent(), getWidth(), getHeight(), getBitrate(), getFps(), "${getHeight()}p",
    getVideoCodecName(getCodec() ?: ""), getItagItem()?.contentLength ?: 0L
)

internal fun org.schabi.newpipe.extractor.stream.VideoStream.toSabrDomain(videoId: String): VideoStream {
    val itag = getItagItem()?.id ?: 0
    val url = if (getDeliveryMethod() == org.schabi.newpipe.extractor.stream.DeliveryMethod.SABR) {
        "sabr://$videoId?v=$itag"
    } else {
        getContent()
    }
    return VideoStream(
        url, getWidth(), getHeight(), getBitrate(), getFps(), "${getHeight()}p",
        getVideoCodecName(getCodec() ?: ""), getItagItem()?.contentLength ?: 0L
    )
}

internal fun org.schabi.newpipe.extractor.stream.AudioStream.toDomain() = AudioStream(
    getContent(), getBitrate(), getAudioLocale()?.language ?: "Default",
    getAudioCodecName(getCodec() ?: ""), getItagItem()?.contentLength ?: 0L,
    audioTrackId = getAudioTrackId(),
    displayName = getAudioTrackName() ?: getAudioLocale()?.displayLanguage,
)

internal fun org.schabi.newpipe.extractor.stream.AudioStream.toSabrDomain(videoId: String): AudioStream {
    val itag = getItagItem()?.id ?: 0
    val url = if (getDeliveryMethod() == org.schabi.newpipe.extractor.stream.DeliveryMethod.SABR) {
        "sabr://$videoId?a=$itag"
    } else {
        getContent()
    }
    return AudioStream(
        url, getBitrate(),
        getAudioLocale()?.language ?: getAudioLocale()?.toLanguageTag() ?: "Default",
        getAudioCodecName(getCodec() ?: ""), getItagItem()?.contentLength ?: 0L,
        audioTrackId = getAudioTrackId(),
        displayName = getAudioTrackName() ?: getAudioLocale()?.displayLanguage,
    )
}

internal fun SubtitlesStream.toSubtitleTrack() = SubtitleTrack(
    url = getContent(),
    languageTag = getLanguageTag(),
    displayName = getDisplayLanguageName() + if (isAutoGenerated()) " (auto)" else "",
    autoGenerated = isAutoGenerated(),
    mimeType = getFormat()?.mimeType ?: "application/ttml+xml",
)

internal fun codecPriority(codec: String): Int = when (codec) {
    CODEC_AV1 -> CODEC_PRIORITY_AV1
    CODEC_VP9 -> CODEC_PRIORITY_VP9
    CODEC_AVC -> CODEC_PRIORITY_AVC
    else -> CODEC_PRIORITY_OTHER
}

private const val CODEC_AV1 = "av1"
private const val CODEC_VP9 = "vp9"
private const val CODEC_AVC = "avc"
private const val CODEC_PRIORITY_AV1 = 3
private const val CODEC_PRIORITY_VP9 = 2
private const val CODEC_PRIORITY_AVC = 1
private const val CODEC_PRIORITY_OTHER = 0

internal val videoStreamPreference: Comparator<VideoStream> =
    compareBy<VideoStream> { it.fps }.thenBy { codecPriority(it.codec) }
