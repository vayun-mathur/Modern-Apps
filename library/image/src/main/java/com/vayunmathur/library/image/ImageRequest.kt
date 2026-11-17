package com.vayunmathur.library.image

import android.content.Context

/**
 * Mirrors `coil.request.ImageRequest` Builder API actually used in repo.
 *
 * Fields used in codebase:
 * - data: Uri/String/File/ByteArray/Bitmap?
 * - memoryCacheKey / diskCacheKey
 * - crossfade(Boolean)
 * - size(Int) / size(Size)
 * - transformations(...)
 * - videoFrameMillis(Long)
 * - allowHardware(Boolean)
 */
data class ImageRequest(
    val context: Context? = null,
    val data: Any? = null,
    val memoryCacheKey: String? = null,
    val diskCacheKey: String? = null,
    val crossfade: Boolean = false,
    val size: Size? = null,
    val transformations: List<Transformation> = emptyList(),
    val videoFrameMillis: Long? = null,
    val allowHardware: Boolean = true,
) {
    /** Builder mirroring the Coil-style request API for an ImageRequest. */
    class Builder(contextOrAny: Any? = null) {
        private var context: Context? = contextOrAny as? Context
        private var data: Any? = null
        private var memoryCacheKey: String? = null
        private var diskCacheKey: String? = null
        private var crossfade: Boolean = false
        private var size: Size? = null
        private var transformations: MutableList<Transformation> = mutableListOf()
        private var videoFrameMillis: Long? = null
        private var allowHardware: Boolean = true

        init {
            if (contextOrAny is Context) {
                // already set
            } else if (contextOrAny != null) {
                // allow Builder without context – some call sites pass non-context builder then data later
                // If object isn't Context, treat as possibly nothing.
            }
        }

        fun data(data: Any?): Builder {
            this.data = data
            return this
        }

        fun memoryCacheKey(key: String?): Builder {
            this.memoryCacheKey = key
            return this
        }

        fun diskCacheKey(key: String?): Builder {
            this.diskCacheKey = key
            return this
        }

        fun crossfade(enable: Boolean): Builder {
            this.crossfade = enable
            return this
        }

        /** Coil overload – keep for compat */
        fun crossfade(enable: Boolean, @Suppress("UNUSED_PARAMETER") durationMillis: Int): Builder {
            this.crossfade = enable
            return this
        }

        fun size(size: Int): Builder {
            this.size = Size(size, size)
            return this
        }

        fun size(size: Size): Builder {
            this.size = size
            return this
        }

        fun size(width: Int, height: Int): Builder {
            this.size = Size(width, height)
            return this
        }

        fun transformations(vararg t: Transformation): Builder {
            transformations = t.toMutableList()
            return this
        }

        fun transformations(list: List<Transformation>): Builder {
            transformations = list.toMutableList()
            return this
        }

        fun videoFrameMillis(millis: Long): Builder {
            this.videoFrameMillis = millis
            return this
        }

        fun allowHardware(allow: Boolean): Builder {
            this.allowHardware = allow
            return this
        }

        fun build(): ImageRequest = ImageRequest(
            context = context,
            data = data,
            memoryCacheKey = memoryCacheKey,
            diskCacheKey = diskCacheKey,
            crossfade = crossfade,
            size = size,
            transformations = transformations.toList(),
            videoFrameMillis = videoFrameMillis,
            allowHardware = allowHardware,
        )
    }
}

/** Top-level extensions to mirror Coil DSL `videoFrameMillis` in builder lambda – not strictly needed */
fun ImageRequest.Builder.videoFrameMillis(millis: Long?): ImageRequest.Builder {
    if (millis != null) videoFrameMillis(millis)
    return this
}
