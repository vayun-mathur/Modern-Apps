package com.vayunmathur.library.image.fetchers

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.net.toUri

/** Fetcher resolving content URIs for decoding. */
class ContentResolverFetcher : Fetcher {
    override suspend fun fetch(data: Any?, context: Context): FetchResult? {
        val uri: Uri = when (data) {
            is Uri -> data
            is String -> try { data.toUri() } catch (_: Exception) { return null }
            else -> return null
        }
        if (uri.scheme != "content") return null

        // Handed to the decoder as a source rather than read into a ByteArray:
        // MediaStore images are multi-MB and the caller usually wants a thumbnail,
        // so the whole encoded file never needs to exist in memory at once.
        // Creating the source does no IO, so there is nothing to dispatch here.
        val resolver = context.contentResolver
        return FetchResult.Source(
            decoderSource = ImageDecoder.createSource(resolver, uri),
            openStream = { try { resolver.openInputStream(uri) } catch (_: Exception) { null } },
        )
    }
}
