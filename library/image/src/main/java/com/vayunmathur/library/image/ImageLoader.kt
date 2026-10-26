package com.vayunmathur.library.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.vayunmathur.library.image.decoders.BitmapDecoder
import com.vayunmathur.library.image.decoders.SvgDecoder
import com.vayunmathur.library.image.decoders.VideoFrameDecoder
import com.vayunmathur.library.image.fetchers.AssetFetcher
import com.vayunmathur.library.image.fetchers.BitmapFetcher
import com.vayunmathur.library.image.fetchers.ByteArrayFetcher
import com.vayunmathur.library.image.fetchers.ContentResolverFetcher
import com.vayunmathur.library.image.fetchers.FetchResult
import com.vayunmathur.library.image.fetchers.FileFetcher
import com.vayunmathur.library.image.fetchers.Fetcher
import com.vayunmathur.library.image.fetchers.HttpFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File

private const val DATA_KEY_SAMPLE_BYTES = 16

/**
 * Replacement for `coil.ImageLoader`. Uses:
 * - [MemoryCache] (LruCache<String, Bitmap>)
 * - [DiskCache] (file LRU raw bytes)
 * - Fetcher registry (http/content/file/asset/bytearray/bitmap)
 * - Decoders: SVG via internal Android stdlib renderer (Canvas/Path), Video via
 *   MediaMetadataRetriever, Bitmap via BitmapFactory/ImageDecoder
 */
class ImageLoader private constructor(
    private val appContext: Context,
    val memoryCache: MemoryCache?,
    val diskCache: DiskCache?,
    private val fetchers: List<Fetcher>,
    private val respectCacheHeaders: Boolean = false,
) {

    /** Owns in-flight work so it outlives any one caller's cancellation. */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Requests currently being fetched/decoded, keyed by cache key.
     *
     * A grid scrolling past several tiles of the same image used to fetch and decode
     * it once per tile; now the first request does the work and the rest await it.
     */
    private val inFlight = mutableMapOf<String, Deferred<ImageResult>>()

    companion object {
        @Volatile
        private var singleton: ImageLoader? = null

        fun get(context: Context): ImageLoader {
            return singleton ?: synchronized(this) {
                singleton ?: Builder(context.applicationContext).build().also { singleton = it }
            }
        }

        internal fun setDefault(loader: ImageLoader) {
            singleton = loader
        }
    }

    private fun dataKey(request: ImageRequest): String = when (val d = request.data) {
        null -> "null"
        is String -> d
        is ByteArray -> "bytes_${d.size}_${d.take(DATA_KEY_SAMPLE_BYTES).hashCode()}"
        is Bitmap -> "bitmap_${d.width}x${d.height}_${d.hashCode()}"
        else -> d.toString()
    }

    private fun computeCacheKey(request: ImageRequest): String {
        val sizeKey = request.size?.let { "${it.width}x${it.height}" } ?: "orig"
        val transKey = request.transformations.joinToString("|") { it.cacheKey }
        val videoKey = request.videoFrameMillis?.let { "vf_$it" } ?: ""
        // An explicit key names the *data*; size and transformations still have to be
        // folded in. Returning a caller's key verbatim meant every caller was
        // responsible for encoding its own size, and a caller that didn't (a 256 px
        // thumbnail key that says nothing about 256) collided by name with a request
        // for the same image at a different size and was served the wrong bitmap.
        val base = request.memoryCacheKey ?: request.diskCacheKey ?: dataKey(request)
        return "$base|$sizeKey|$transKey|$videoKey"
    }

    /**
     * True for data already sitting on this device's storage.
     *
     * The disk cache exists to avoid re-downloading; for a local file it only
     * duplicated the user's own photo library into `cacheDir` (at up to 5% of device
     * storage) to avoid reopening a file that is already there and cheap to reopen.
     */
    private fun isLocalData(data: Any?): Boolean = when (data) {
        is File -> true
        is Uri -> data.scheme.let { it == "content" || it == "file" || it == null }
        is String -> data.startsWith("content://") || data.startsWith("file://") || data.startsWith("/")
        else -> false
    }

    /**
     * Memory-cache lookup with no dispatch and no IO.
     *
     * [execute] is a suspend function that hops to [Dispatchers.IO] before it can
     * even check the memory cache, so a cached bitmap was never available in time
     * for the first frame. Callers on the main thread can use this to paint
     * immediately on a hit (see `AsyncImage`).
     */
    fun peekMemoryCache(request: ImageRequest): Bitmap? {
        if (request.data is Bitmap) return null
        return try {
            memoryCache?.get(computeCacheKey(request))?.takeIf { !it.isRecycled }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun execute(request: ImageRequest): ImageResult {
        // A Bitmap request has nothing to fetch or cache; transform and return it.
        // Still dispatched, because a transformation allocates and draws a bitmap and
        // the caller is usually on the main thread.
        if (request.data is Bitmap) {
            val bmp = request.data
            return withContext(Dispatchers.IO) {
                val transformed = applyTransformations(bmp, request)
                ImageResult.Success(
                    transformed,
                    isFromMemory = false,
                    dataSource = ImageResult.DataSource.MEMORY,
                )
            }
        }

        val cacheKey = computeCacheKey(request)
        peekMemoryCache(request)?.let {
            return ImageResult.Success(
                it,
                isFromMemory = true,
                dataSource = ImageResult.DataSource.MEMORY,
            )
        }

        val deferred = synchronized(inFlight) {
            // `isCompleted`, not `isActive`: a CoroutineStart.LAZY Deferred sits in the
            // NEW state where isActive is false, so testing isActive would reject the
            // entry during exactly the window between registration and start() and let
            // a second caller duplicate the work.
            val existing = inFlight[cacheKey]?.takeIf { !it.isCompleted }
            if (existing != null) {
                existing
            } else {
                // LAZY so the entry is registered (and its cleanup hooked up) before
                // the work can start and try to remove itself.
                val started = scope.async(start = CoroutineStart.LAZY) { load(request, cacheKey) }
                inFlight[cacheKey] = started
                started.invokeOnCompletion {
                    synchronized(inFlight) {
                        // Only if still ours; a later request may have replaced it.
                        if (inFlight[cacheKey] === started) inFlight.remove(cacheKey)
                    }
                }
                started
            }
        }
        deferred.start()
        return deferred.await()
    }

    private suspend fun load(request: ImageRequest, cacheKey: String): ImageResult =
        withContext(Dispatchers.IO) {
            val scope = LoadScope(
                request = request,
                cacheKey = cacheKey,
                context = request.context ?: appContext,
                diskKey = request.diskCacheKey ?: cacheKey,
                isLocal = isLocalData(request.data),
            )
            // Another coalesced request may have filled the cache while this one waited.
            memoryHit(scope)
                ?: diskHit(scope)
                ?: videoHit(scope)
                ?: resolveFromFetchers(scope)
                ?: decodeFresh(scope)
        }

    private fun memoryHit(scope: LoadScope): ImageResult? {
        val cached = runCatching { memoryCache?.get(scope.cacheKey) }.getOrNull() ?: return null
        return ImageResult.Success(
            cached,
            isFromMemory = true,
            dataSource = ImageResult.DataSource.MEMORY,
        )
    }

    private suspend fun diskHit(scope: LoadScope): ImageResult? {
        val cache = diskCache
        if (cache == null || scope.isLocal) return null
        val diskBytes = runCatching { cache.get(scope.diskKey) }.getOrNull() ?: return null
        scope.diskBytes = diskBytes
        val decoded = decodeBytes(diskBytes, scope.request) ?: return null
        return bitmapResult(decoded, scope, ImageResult.DataSource.DISK)
    }

    private suspend fun videoHit(scope: LoadScope): ImageResult? {
        if (scope.request.videoFrameMillis == null) return null
        val videoBmp = runCatching {
            VideoFrameDecoder.decode(scope.request, scope.context)
        }.getOrNull() ?: return null
        return bitmapResult(videoBmp, scope, ImageResult.DataSource.MEMORY)
    }

    private suspend fun resolveFromFetchers(scope: LoadScope): ImageResult? =
        runCatching { resolveFromFetchersOrNull(scope) }.getOrElse { ImageResult.Error(it) }

    private suspend fun resolveFromFetchersOrNull(scope: LoadScope): ImageResult? =
        when (val outcome = collectFetchOutcome(scope)) {
            is FetchOutcome.Resolved -> outcome.result
            FetchOutcome.PendingBytes -> null
        }

    private suspend fun collectFetchOutcome(scope: LoadScope): FetchOutcome {
        for (fetcher in fetchers) {
            val result = runCatching {
                fetcher.fetch(scope.request.data, scope.context)
            }.getOrNull() ?: continue
            return classifyFetchResult(result, scope)
        }
        return FetchOutcome.PendingBytes
    }

    private suspend fun classifyFetchResult(result: FetchResult, scope: LoadScope): FetchOutcome =
        when (result) {
            is FetchResult.BitmapResult -> FetchOutcome.Resolved(
                bitmapResult(result.bitmap, scope, ImageResult.DataSource.MEMORY),
            )
            is FetchResult.Bytes -> {
                scope.fetchedBytes = result.bytes
                FetchOutcome.PendingBytes
            }
            is FetchResult.Source -> sourceOutcome(result, scope)
        }

    private suspend fun sourceOutcome(result: FetchResult.Source, scope: LoadScope): FetchOutcome {
        // Fast path: decode (and downsample) straight from the file.
        val decodedFromSource = BitmapDecoder.decode(
            result.decoderSource,
            scope.request,
            scope.request.allowHardware,
        )
        if (decodedFromSource != null) {
            return FetchOutcome.Resolved(
                bitmapResult(decodedFromSource, scope, ImageResult.DataSource.DISK),
            )
        }
        // ImageDecoder can't read it (an SVG, or a video whose frame
        // decoder needs the bytes): fall back to the byte path.
        scope.fetchedBytes = runCatching {
            result.openStream()?.use { it.readBytes() }
        }.getOrNull()
        return FetchOutcome.PendingBytes
    }

    private suspend fun decodeFresh(scope: LoadScope): ImageResult {
        val bytes = scope.fetchedBytes ?: scope.diskBytes
            ?: return ImageResult.Error(
                IllegalArgumentException("Unable to fetch data: ${scope.request.data}"),
            )
        val decoded = runCatching {
            decodeBytes(bytes, scope.request)
                ?: scope.request.videoFrameMillis?.let {
                    VideoFrameDecoder.decode(scope.request, scope.context)
                }
        }.getOrElse { return ImageResult.Error(it) }
            ?: return ImageResult.Error(
                IllegalArgumentException("Failed to decode image bytes (${bytes.size} bytes)"),
            )

        val transformed = applyTransformations(decoded, scope.request)
        putMemory(scope.cacheKey, transformed)
        putDisk(scope, bytes)
        return ImageResult.Success(
            transformed,
            isFromMemory = false,
            dataSource = ImageResult.DataSource.NETWORK,
        )
    }

    private suspend fun decodeBytes(bytes: ByteArray, request: ImageRequest): Bitmap? {
        if (SvgDecoder.canDecode(bytes, request.data)) {
            val svgBmp = SvgDecoder.decode(bytes, request)
            if (svgBmp != null) return svgBmp
        }
        return BitmapDecoder.decode(bytes, request, request.allowHardware)
    }

    private suspend fun bitmapResult(
        bitmap: Bitmap,
        scope: LoadScope,
        source: ImageResult.DataSource,
    ): ImageResult {
        val transformed = applyTransformations(bitmap, scope.request)
        putMemory(scope.cacheKey, transformed)
        return ImageResult.Success(
            transformed,
            isFromMemory = false,
            dataSource = source,
        )
    }

    private fun putMemory(cacheKey: String, bitmap: Bitmap) {
        try {
            memoryCache?.put(cacheKey, bitmap)
        } catch (_: Exception) {
        }
    }

    private fun putDisk(scope: LoadScope, bytes: ByteArray) {
        if (diskCache == null || scope.isLocal) return
        try {
            diskCache.put(scope.diskKey, bytes)
        } catch (_: Exception) {
        }
    }

    private suspend fun applyTransformations(bitmap: Bitmap, request: ImageRequest): Bitmap {
        var current = bitmap
        for (t in request.transformations) {
            try {
                current = t.transform(current, request.size ?: Size.Original)
            } catch (_: Exception) {}
        }
        return current
    }

    private class LoadScope(
        val request: ImageRequest,
        val cacheKey: String,
        val context: Context,
        val diskKey: String,
        val isLocal: Boolean,
        var fetchedBytes: ByteArray? = null,
        var diskBytes: ByteArray? = null,
    )

    private sealed interface FetchOutcome {
        data class Resolved(val result: ImageResult) : FetchOutcome
        data object PendingBytes : FetchOutcome
    }

    class Builder(private val context: Context) {
        private var memoryCacheInstance: MemoryCache? = null
        private var diskCacheInstance: DiskCache? = null
        private var respectCacheHeaders: Boolean = true
        private val extraFetchers: MutableList<Fetcher> = mutableListOf()

        inner class ComponentsBuilder {
            fun add(factory: Any): ComponentsBuilder {
                if (factory is Fetcher) extraFetchers += factory
                return this
            }
        }

        fun components(block: ComponentsBuilder.() -> Unit): Builder {
            val cb = ComponentsBuilder()
            cb.block()
            return this
        }

        fun memoryCache(cache: MemoryCache): Builder {
            memoryCacheInstance = cache
            return this
        }

        fun memoryCache(block: () -> MemoryCache): Builder {
            memoryCacheInstance = block()
            return this
        }

        fun diskCache(cache: DiskCache): Builder {
            diskCacheInstance = cache
            return this
        }

        fun diskCache(block: () -> DiskCache): Builder {
            diskCacheInstance = block()
            return this
        }

        fun respectCacheHeaders(respect: Boolean): Builder {
            respectCacheHeaders = respect
            return this
        }

        fun addFetcher(fetcher: Fetcher): Builder {
            extraFetchers += fetcher
            return this
        }

        fun build(): ImageLoader {
            val mem = memoryCacheInstance ?: MemoryCache.Builder().build()
            val disk = diskCacheInstance ?: try {
                val dir = context.applicationContext.cacheDir.resolve("image_cache")
                DiskCache.Builder().directory(dir).build()
            } catch (_: Exception) { null }

            val defaultFetchers = listOf(
                BitmapFetcher(),
                ByteArrayFetcher(),
                FileFetcher(),
                AssetFetcher(),
                ContentResolverFetcher(),
                HttpFetcher(),
            )
            val allFetchers = extraFetchers + defaultFetchers

            return ImageLoader(
                appContext = context.applicationContext,
                memoryCache = mem,
                diskCache = disk,
                fetchers = allFetchers,
                respectCacheHeaders = respectCacheHeaders,
            )
        }
    }
}
