package com.vayunmathur.youpipe.util.sabr

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.IOException

/**
 * Muxes the separately-downloaded SABR audio and video temporary files into a single mp4 using
 * ffmpeg (stream copy, no re-encode). Adapted from PipePipe's SabrFfmpegMuxer, decoupled from the
 * giga DownloadMission storage/state machine: it writes straight to [output].
 */
internal object SabrFfmpegMuxer {
    private const val TAG = "SabrFfmpegMuxer"

    @Throws(IOException::class)
    fun mux(videoInput: File, audioInput: File, output: File) {
        val command = buildList {
            add("-hide_banner")
            add("-nostats")
            add("-loglevel")
            add("fatal")
            add("-y")
            add("-i")
            add(videoInput.absolutePath)
            add("-i")
            add(audioInput.absolutePath)
            add("-map")
            add("0:v?")
            add("-map")
            add("1:a?")
            add("-c")
            add("copy")
            if (supportsFastStart(output)) {
                add("-movflags")
                add("+faststart")
            }
            add(output.absolutePath)
        }
        Log.d(TAG, "remux video=${videoInput.length()} audio=${audioInput.length()} -> ${output.name}")
        val session = FFmpegKit.executeWithArguments(command.toTypedArray())
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: ffmpeg remux failed (${session.returnCode})"
                    + session.output.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty(),
            )
        }
    }

    private fun supportsFastStart(output: File): Boolean {
        return when (output.extension.lowercase()) {
            "m4a", "m4v", "mov", "mp4" -> true
            else -> false
        }
    }
}
