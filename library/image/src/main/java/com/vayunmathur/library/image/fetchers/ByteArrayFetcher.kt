package com.vayunmathur.library.image.fetchers

import android.content.Context

/** Fetcher returning in-memory encoded image bytes unchanged. */
class ByteArrayFetcher : Fetcher {
    override suspend fun fetch(data: Any?, context: Context): FetchResult? {
        if (data is ByteArray) return FetchResult.Bytes(data)
        return null
    }
}
