package com.vayunmathur.youpipe.network.sabr

import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrFormatTimeline
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo

/**
 * Immutable spec describing a SABR audio+video selection for the session-based ([SabrNgSession])
 * media3 bridge. Segment timelines are parsed eagerly from each format's initialization data so a
 * DASH manifest can be published with exact segment counts.
 */
class SabrNgSourceSpec(
    val videoId: String,
    val info: YoutubeSabrInfo,
    val audioFormat: YoutubeSabrInfo.Format,
    val videoFormat: YoutubeSabrInfo.Format,
    val localization: Localization,
    audioInitializationData: ByteArray,
    videoInitializationData: ByteArray,
    poToken: ByteArray?
) {
    private val audioInitializationData: ByteArray = audioInitializationData.clone()
    private val videoInitializationData: ByteArray = videoInitializationData.clone()
    val poToken: ByteArray? = poToken?.clone()

    /** Exact audio timeline parsed from the audio init segment (throws if it cannot be parsed). */
    val audioTimeline: YoutubeSabrFormatTimeline =
        YoutubeSabrFormatTimeline.parse(audioFormat, audioInitializationData)

    /** Exact video timeline parsed from the video init segment (throws if it cannot be parsed). */
    val videoTimeline: YoutubeSabrFormatTimeline =
        YoutubeSabrFormatTimeline.parse(videoFormat, videoInitializationData)

    fun getDurationMs(): Long =
        maxOf(audioFormat.getApproxDurationMs(), videoFormat.getApproxDurationMs())

    fun getInitializationData(itag: Int): ByteArray? = when (itag) {
        audioFormat.getItag() -> audioInitializationData.clone()
        videoFormat.getItag() -> videoInitializationData.clone()
        else -> null
    }

    fun timelineFor(itag: Int): YoutubeSabrFormatTimeline? = when (itag) {
        audioFormat.getItag() -> audioTimeline
        videoFormat.getItag() -> videoTimeline
        else -> null
    }
}
