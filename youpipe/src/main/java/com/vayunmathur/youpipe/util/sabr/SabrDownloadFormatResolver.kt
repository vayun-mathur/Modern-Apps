package com.vayunmathur.youpipe.util.sabr

import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import java.io.IOException

internal object SabrDownloadFormatResolver {
    /**
     * Resolves the audio [YoutubeSabrFormat] to download. When [itag] is positive the exact
     * matching audio format is required; otherwise the best available audio format is used.
     */
    @Throws(IOException::class)
    fun resolveAudioFormat(info: YoutubeSabrInfo, itag: Int): YoutubeSabrFormat {
        val byItag = if (itag > 0) {
            info.formats.firstOrNull { it.isAudio && it.itag == itag }
        } else {
            null
        }
        return byItag
            ?: info.findBestAudioFormat()
            ?: throw SabrDownloadException(
                SabrDownloadException.Reason.FORMAT,
                "SABR download failed: could not resolve audio itag $itag",
            )
    }

    /**
     * Resolves the video [YoutubeSabrFormat] to download. When [itag] is positive the exact
     * matching video format is required; otherwise the lowest available video format is used.
     */
    @Throws(IOException::class)
    fun resolveVideoFormat(info: YoutubeSabrInfo, itag: Int): YoutubeSabrFormat {
        val byItag = if (itag > 0) {
            info.formats.firstOrNull { it.isVideo && it.itag == itag }
        } else {
            null
        }
        return byItag
            ?: info.findLowestVideoFormat()
            ?: throw SabrDownloadException(
                SabrDownloadException.Reason.FORMAT,
                "SABR download failed: could not resolve video itag $itag",
            )
    }
}
