package com.vayunmathur.library.image.fetchers

import android.content.Context
import android.graphics.Bitmap

/** Fetcher returning an in-memory bitmap unchanged. */
class BitmapFetcher : Fetcher {
    override suspend fun fetch(data: Any?, context: Context): FetchResult? {
        if (data is Bitmap) return FetchResult.BitmapResult(data)
        return null
    }
}
