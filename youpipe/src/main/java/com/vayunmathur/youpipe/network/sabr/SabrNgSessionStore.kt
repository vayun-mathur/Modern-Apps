package com.vayunmathur.youpipe.network.sabr

import com.vayunmathur.library.log.Log
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrRequest
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabrng.protocol.SabrStreamingResponseReader
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Extractor→player handoff and source-spec factory for the session-based ([SabrNgSession]) SABR
 * bridge. The extractor publishes a [YoutubeSabrInfo] per videoId; the player later builds a
 * [SabrNgSourceSpec] by driving a short SABR "preparation" transaction to fetch each selected
 * format's initialization segment (which carries the segment index used to publish the DASH
 * timeline).
 *
 * Note: SABR-only formats carry NO per-format media URL, so the init segment CANNOT be fetched with
 * a direct HTTP range GET (that path — `YoutubeSabrRequestHelper.fetchInitializationData` — needs a
 * `initializationUrl` that these formats do not have). The init segment is delivered by the SABR
 * server itself in response to a preparation request, exactly like the follow-up media segments.
 */
object SabrNgSessionStore {
    private const val TAG = "SabrNgSessionStore"

    private val extractorInfo = ConcurrentHashMap<String, YoutubeSabrInfo>()

    fun putExtractorInfo(videoId: String, info: YoutubeSabrInfo) {
        extractorInfo[videoId] = info
    }

    fun getExtractorInfo(videoId: String): YoutubeSabrInfo? = extractorInfo[videoId]

    fun evict(videoId: String) {
        extractorInfo.remove(videoId)
    }

    @Throws(IOException::class)
    @JvmOverloads
    fun createSourceSpec(
        videoId: String,
        preferredVideoItag: Int,
        preferredAudioItag: Int,
        preferredAudioTrackId: String?,
        info: YoutubeSabrInfo,
        poToken: ByteArray?,
        localization: Localization,
        tokenMinter: ((Boolean) -> ByteArray?)? = null
    ): SabrNgSourceSpec {
        val videoFormat = requireVideoFormat(info, videoId, preferredVideoItag)
        val audioFormat = requireAudioFormat(info, videoId, preferredAudioItag, preferredAudioTrackId)
        val token = poToken ?: info.getPoToken() ?: ByteArray(0)
        val initByItag = fetchInitializationSegments(
            videoId, info, listOf(audioFormat, videoFormat), token, tokenMinter
        )
        val audioInit = initByItag[audioFormat.getItag()]
            ?: throw missingInitIOException(videoId, "audio", audioFormat.getItag())
        val videoInit = initByItag[videoFormat.getItag()]
            ?: throw missingInitIOException(videoId, "video", videoFormat.getItag())
        return SabrNgSourceSpec(
            videoId, info, audioFormat, videoFormat, localization, audioInit, videoInit,
            poToken ?: info.getPoToken()
        )
    }

    /**
     * Drives a short SABR preparation transaction and returns the raw init-segment bytes per itag.
     * The init segment includes the fragment index (mp4 sidx / webm cues) that
     * [SabrNgSourceSpec] parses into a [YoutubeSabrFormatTimeline].
     */
    @Throws(IOException::class)
    private fun fetchInitializationSegments(
        videoId: String,
        info: YoutubeSabrInfo,
        formats: List<YoutubeSabrInfo.Format>,
        token: ByteArray,
        tokenMinter: ((Boolean) -> ByteArray?)?
    ): Map<Int, ByteArray> {
        val session = YoutubeSabrSession(info)
        if (token.isNotEmpty()) {
            session.setPoToken(token)
        }
        val initByItag = HashMap<Int, ByteArray>()
        val neededItags = formats.mapTo(HashSet()) { it.getItag() }
        val consumer = SabrStreamingResponseReader.SegmentConsumer { segment ->
            val header = segment.getHeader()
            if (header.isInitSegment() && neededItags.contains(header.getItag()) &&
                !initByItag.containsKey(header.getItag())
            ) {
                initByItag[header.getItag()] = segment.getData()
            }
        }
        // A preparation transaction fetches the init segment (with its fragment index) for the
        // preferred formats. The coordinator loops requestOnce through the attestation handshake
        // (re-minting the PO token on rejection) and server backoff until both inits arrive.
        val coordinator = SabrRequestCoordinator(
            session, SabrAttestationRetryHandler(videoId, tokenMinter), null
        )
        val request = YoutubeSabrRequest.preparation(0, formats)
        try {
            coordinator.request(request, consumer) { initByItag.size >= formats.size }
        } catch (e: IOException) {
            throw e
        } catch (e: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
            throw IOException("SABR initialization request failed for $videoId", e)
        }
        if (initByItag.size < formats.size) {
            Log.status(
                TAG,
                "SABR init incomplete for $videoId: got=${initByItag.keys} needed=$neededItags"
            )
        }
        return initByItag
    }

    @Throws(IOException::class)
    private fun requireVideoFormat(
        info: YoutubeSabrInfo,
        videoId: String,
        preferredItag: Int,
    ): YoutubeSabrInfo.Format = selectVideoFormat(info, preferredItag)
        ?: throw IOException("No SABR video format for $videoId (itag=$preferredItag)")

    @Throws(IOException::class)
    private fun requireAudioFormat(
        info: YoutubeSabrInfo,
        videoId: String,
        preferredItag: Int,
        preferredAudioTrackId: String?,
    ): YoutubeSabrInfo.Format = selectAudioFormat(info, preferredItag, preferredAudioTrackId)
        ?: throw IOException("No SABR audio format for $videoId (itag=$preferredItag)")

    private fun missingInitIOException(videoId: String, kind: String, itag: Int): IOException =
        IOException("SABR did not return a $kind init segment for $videoId (itag=$itag)")

    private fun selectVideoFormat(
        info: YoutubeSabrInfo,
        preferredItag: Int
    ): YoutubeSabrInfo.Format? {
        val videos = info.getFormats().filter { it.isVideo() }
        return videos.firstOrNull { it.getItag() == preferredItag }
            ?: videos.minByOrNull { if (it.getHeight() > 0) it.getHeight() else Int.MAX_VALUE }
    }

    private fun selectAudioFormat(
        info: YoutubeSabrInfo,
        preferredItag: Int,
        preferredAudioTrackId: String?
    ): YoutubeSabrInfo.Format? {
        val audios = info.getFormats().filter { it.isAudio() }
        if (audios.isEmpty()) {
            return null
        }
        if (preferredItag > 0) {
            audios.firstOrNull {
                it.getItag() == preferredItag &&
                    (preferredAudioTrackId.isNullOrEmpty() ||
                        preferredAudioTrackId == it.getAudioTrackId())
            }?.let { return it }
            audios.firstOrNull { it.getItag() == preferredItag }?.let { return it }
        }
        return audios.firstOrNull { it.isOriginalAudio() }
            ?: audios.maxByOrNull { it.getBitrate() }
    }
}
