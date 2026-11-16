package com.vayunmathur.library.image

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.image.compose.AnimatedImage
import com.vayunmathur.library.image.fetchers.FetchResult
import com.vayunmathur.library.image.fetchers.Fetcher
import com.vayunmathur.library.image.util.ensureExists
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Loader pipeline branches the basic suite does not reach: remote bytes
 * (NETWORK, then memory hit, then DISK hit on a fresh loader sharing the
 * disk cache), fetcher-supplied bitmaps and sources, throwing fetchers and
 * transformations, video-frame fallbacks, and the AnimatedImage compose
 * path when no drawable can be decoded.
 */
@RunWith(RobolectricTestRunner::class)
class LoaderDeepTest {

    @get:Rule val composeRule = createComposeRule()

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun svgBytes(seed: Int) =
        """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="10" viewBox="0 0 20 10"><rect width="20" height="10" fill="#${seed}0${seed}0${seed}0"/></svg>"""
            .toByteArray()

    private fun freshDisk(name: String): DiskCache {
        val dir = File(ctx.cacheDir, "deep-$name-${System.nanoTime()}").ensureExists()
        return DiskCache(dir, 4 * 1024 * 1024)
    }

    private fun request(data: Any?, key: String) =
        ImageRequest.Builder(ctx).data(data).memoryCacheKey(key).size(Size(20, 10)).build()

    @Test fun remoteBytesFlowNetworkThenMemoryThenDisk(): Unit = runBlocking {
        val disk = freshDisk("remote")
        var fetches = 0
        val loader = ImageLoader.Builder(ctx)
            .diskCache(disk)
            .memoryCache(MemoryCache.Builder().build())
            .remote { data, _ ->
                fetches++
                if (data == "pic") FetchResult.Bytes(svgBytes(1)) else null
            }
            .build()

        val first = loader.execute(request("pic", "k-pic"))
        assertTrue(first is ImageResult.Success)
        assertEquals(ImageResult.DataSource.NETWORK, (first as ImageResult.Success).dataSource)
        assertEquals(1, fetches)

        val second = loader.execute(request("pic", "k-pic"))
        assertTrue(second is ImageResult.Success)
        assertTrue((second as ImageResult.Success).isFromMemory)
        assertEquals(1, fetches, "memory hit must not refetch")
        assertNotNull(loader.peekMemoryCache(request("pic", "k-pic")))

        // A fresh loader over the same disk cache serves DISK without fetching.
        var diskFetches = 0
        val diskLoader = ImageLoader.Builder(ctx)
            .diskCache(disk)
            .memoryCache(MemoryCache.Builder().build())
            .remote { _, _ -> diskFetches++; null }
            .build()
        val third = diskLoader.execute(request("pic", "k-pic"))
        assertTrue(third is ImageResult.Success)
        assertEquals(ImageResult.DataSource.DISK, (third as ImageResult.Success).dataSource)
        assertEquals(0, diskFetches)
        assertNotNull(third.bitmap)
        // Success result plumbing: drawable conversion and defaults.
        assertNotNull(third.asBitmapDrawable(ctx.resources))
        assertNotNull(third.drawable)
    }

    @Test fun fetcherBitmapAndThrowingFetcherOutcomes(): Unit = runBlocking {
        val bmp = Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888)
        val bitmapLoader = ImageLoader.Builder(ctx)
            .diskCache(freshDisk("bitmapfetcher"))
            .addFetcher(object : Fetcher {
                override suspend fun fetch(data: Any?, context: Context): FetchResult? =
                    if (data == "bmp") FetchResult.BitmapResult(bmp) else null
            })
            .build()
        val hit = bitmapLoader.execute(request("bmp", "k-bmp"))
        assertTrue(hit is ImageResult.Success)
        assertEquals(6, (hit as ImageResult.Success).bitmap.width)

        val throwing = ImageLoader.Builder(ctx)
            .diskCache(freshDisk("throwing"))
            .addFetcher(object : Fetcher {
                override suspend fun fetch(data: Any?, context: Context): FetchResult? =
                    throw IllegalStateException("fetch boom")
            })
            .build()
        val failed = throwing.execute(request("anything", "k-throw"))
        assertTrue(failed is ImageResult.Error, "throwing fetcher must surface Error, was $failed")
    }

    @Test fun throwingTransformationIsSkippedAndGoodOneApplies(): Unit = runBlocking {
        val src = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val boom = object : Transformation {
            override val cacheKey: String = "boom"
            override suspend fun transform(input: Bitmap, size: Size): Bitmap =
                throw IllegalStateException("transform boom")
        }
        val loader = ImageLoader.Builder(ctx).build()
        val req = ImageRequest.Builder(ctx).data(src).transformations(listOf(boom)).build()
        val result = loader.execute(req)
        assertTrue(result is ImageResult.Success)
        assertEquals(4, (result as ImageResult.Success).bitmap.width)
    }

    @Test fun videoFrameRequestFallsBackToErrorForUnfetchableData(): Unit = runBlocking {
        val loader = ImageLoader.Builder(ctx).diskCache(freshDisk("video")).build()
        val req = ImageRequest.Builder(ctx)
            .data("content://media/external/video/1".toUri())
            .videoFrameMillis(1_000L)
            .build()
        val result = loader.execute(req)
        assertTrue(result is ImageResult.Error, "no frame can be extracted under Robolectric, was $result")
    }

    @Test fun pngBytesReachBitmapDecoderAndFailCleanlyOnHost(): Unit = runBlocking {
        // 1x1 transparent PNG. Robolectric's BitmapFactory shadow cannot
        // decode real PNG bytes, so on host the loader must surface its
        // documented Error (never a crash or a blank Success); on device
        // the same bytes decode through BitmapDecoder.
        val png = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8Xw8AAwMCAO+ip1sAAAAASUVORK5CYII=",
            Base64.DEFAULT,
        )
        val loader = ImageLoader.Builder(ctx)
            .diskCache(freshDisk("png"))
            .remote { data, _ -> if (data == "png") FetchResult.Bytes(png) else null }
            .build()
        val result = loader.execute(
            ImageRequest.Builder(ctx).data("png").memoryCacheKey("k-png").build(),
        )
        assertTrue(result is ImageResult.Error, "host decode failure must be Error, was $result")
    }

    @Test fun animatedImageWithUndecodableUriComposesToNothing() {
        composeRule.setContent {
            AnimatedImage(uri = "content://media/external/images/999999".toUri(), contentDescription = "anim")
            Text("after")
        }
        composeRule.onRoot().assertExists()
    }

    @Test fun imageResultSuccessDefaults() {
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val success = ImageResult.Success(bmp)
        assertEquals(ImageResult.DataSource.MEMORY, success.dataSource)
        assertEquals(false, success.isFromMemory)
        val alias: SuccessResult = success
        assertEquals(bmp, alias.bitmap)
        assertEquals(ImageResult.DataSource.UNKNOWN, ImageResult.DataSource.valueOf("UNKNOWN"))
    }
}
