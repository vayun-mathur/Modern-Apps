package com.vayunmathur.library.image

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.drawable.toDrawable

/**
 * Result of [ImageLoader.execute]. Mirrors Coil's sealed result hierarchy minimally.
 */
sealed class ImageResult {
    data class Success(
        val bitmap: Bitmap,
        /** true if served from [MemoryCache] */
        val isFromMemory: Boolean = false,
        val dataSource: DataSource = DataSource.MEMORY,
    ) : ImageResult() {
        /** For TileCache compat – coil returned drawable, we re-expose as drawable. */
        val drawable: BitmapDrawable get() = bitmap.toDrawable(android.content.res.Resources.getSystem())

        fun asBitmapDrawable(resources: android.content.res.Resources? = null): BitmapDrawable =
            bitmap.toDrawable(resources ?: android.content.res.Resources.getSystem())
    }

    data class Error(
        val throwable: Throwable,
    ) : ImageResult()

    enum class DataSource { MEMORY, DISK, NETWORK, UNKNOWN }
}

// Backwards compat alias used in TileCache if needed – old: SuccessResult
typealias SuccessResult = ImageResult.Success
