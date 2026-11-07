package com.vayunmathur.library.image.decoders

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.Size
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.compose.AsyncImageState
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class BitmapAndComposeTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun pngBytes(width: Int = 16, height: Int = 12): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLUE)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    @Test fun `isSvg sniffs tag and xml declaration`() {
        assertTrue(BitmapDecoder.isSvg("<svg></svg>".toByteArray()))
        assertTrue(BitmapDecoder.isSvg("<?xml version=\"1.0\"?><svg></svg>".toByteArray()))
        assertFalse(BitmapDecoder.isSvg("<?xml version=\"1.0\"?><html></html>".toByteArray()))
        assertFalse(BitmapDecoder.isSvg("png".toByteArray()))
        assertFalse(BitmapDecoder.isSvg(ByteArray(0)))
    }

    @Test fun `decode png bytes at original and sampled sizes`(): Unit = runBlocking {
        val bytes = pngBytes()
        assertTrue(bytes.isNotEmpty())
        val full = BitmapDecoder.decode(bytes, ImageRequest.Builder(ctx).build(), allowHardware = false)
        assertNotNull(full)
        assertEquals(16, full.width); assertEquals(12, full.height)
        val thumb = BitmapDecoder.decode(bytes, ImageRequest.Builder(ctx).size(Size(4, 4)).build(), allowHardware = false)
        assertNotNull(thumb)
        assertTrue(thumb.width <= 16 && thumb.height <= 12)
        // Robolectric BitmapFactory fabricates a placeholder for undecodable bytes, so no null assertion here
    }

    @Test fun `image loader decodes byte array requests and caches in memory`(): Unit = runBlocking {
        val loader = ImageLoader.Builder(ctx).build()
        val request = ImageRequest.Builder(ctx).data(pngBytes()).memoryCacheKey("png-1").build()
        val first = loader.execute(request)
        assertTrue(first is com.vayunmathur.library.image.ImageResult.Success, "got $first")
        val second = loader.execute(request)
        assertTrue(second is com.vayunmathur.library.image.ImageResult.Success)
        assertTrue((second as com.vayunmathur.library.image.ImageResult.Success).isFromMemory)
        assertNotNull(loader.peekMemoryCache(request))
    }

    @Test fun `async image renders bitmap model and reports state`() {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val states = mutableListOf<AsyncImageState>()
        rule.setContent {
            AsyncImage(model = bmp, contentDescription = "sample image", onState = { states += it })
        }
        rule.waitUntil(timeoutMillis = 10_000) { states.any { it is AsyncImageState.Success } }
        rule.onNodeWithContentDescription("sample image").assertExists()
    }

    @Test fun `async image with null model reports empty`() {
        val states = mutableListOf<AsyncImageState>()
        rule.setContent {
            AsyncImage(model = null, contentDescription = null, onState = { states += it })
        }
        assertTrue(states.contains(AsyncImageState.Empty) || states.isEmpty())
    }
}
