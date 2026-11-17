package com.vayunmathur.library.image.decoders

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import com.vayunmathur.library.image.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MICROSECONDS_PER_MILLISECOND = 1000L

/** Extracts video frames as bitmaps for thumbnail requests. */
object VideoFrameDecoder {

    suspend fun decode(
        request: ImageRequest,
        context: Context?,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val millis = request.videoFrameMillis ?: return@withContext null
        val ctx = context ?: request.context ?: return@withContext null
        val data = request.data ?: return@withContext null

        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                bindDataSource(retriever, data, ctx) ?: return@runCatching null
                extractFrame(retriever, millis)
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()
    }

    private fun bindDataSource(
        retriever: MediaMetadataRetriever,
        data: Any,
        ctx: Context,
    ): Unit? =
        when (data) {
            is Uri -> bindUriSource(retriever, data, ctx)
            is String -> bindStringSource(retriever, data, ctx)
            is File -> {
                retriever.setDataSource(data.absolutePath)
            }
            else -> null
        }

    private fun bindUriSource(
        retriever: MediaMetadataRetriever,
        data: Uri,
        ctx: Context,
    ) {
        runCatching {
            retriever.setDataSource(ctx, data)
        }.getOrElse {
            val path = data.path ?: return
            retriever.setDataSource(path)
        }
    }

    private fun bindStringSource(
        retriever: MediaMetadataRetriever,
        data: String,
        ctx: Context,
    ): Unit? {
        val uri = runCatching { data.toUri() }.getOrNull()
        if (uri != null && uri.scheme == CONTENT_SCHEME) {
            retriever.setDataSource(ctx, uri)
            return Unit
        }
        if (data.startsWith(ABSOLUTE_PATH_PREFIX) || data.startsWith(FILE_URI_PREFIX)) {
            val path = data.removePrefix(FILE_URI_PREFIX)
            retriever.setDataSource(path)
            return Unit
        }
        if (data.startsWith(HTTP_PREFIX) || data.startsWith(HTTPS_PREFIX)) {
            // MediaMetadataRetriever supports remote URL – supply empty headers.
            retriever.setDataSource(data, HashMap())
            return Unit
        }
        val file = File(data)
        if (!file.exists()) return null
        retriever.setDataSource(file.absolutePath)
        return Unit
    }

    private fun extractFrame(retriever: MediaMetadataRetriever, millis: Long): Bitmap? {
        val timeUs = millis * MICROSECONDS_PER_MILLISECOND
        return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
    }
}

private const val CONTENT_SCHEME = "content"
private const val ABSOLUTE_PATH_PREFIX = "/"
private const val FILE_URI_PREFIX = "file://"
private const val HTTP_PREFIX = "http://"
private const val HTTPS_PREFIX = "https://"
