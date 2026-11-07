package com.vayunmathur.pdf.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.pdf.util.BlendMode
import com.vayunmathur.pdf.util.ComposePdfDocument
import com.vayunmathur.pdf.util.PdfPrimitive
import com.vayunmathur.pdf.util.SafePdfPage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinalBatchTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun blendModeMappings() {
        assertEquals(androidx.compose.ui.graphics.BlendMode.SrcOver, BlendMode.Normal.toCompose())
        assertEquals(androidx.compose.ui.graphics.BlendMode.Multiply, BlendMode.Multiply.toCompose())
        assertEquals(androidx.compose.ui.graphics.BlendMode.Luminosity, BlendMode.Luminosity.toCompose())
        BlendMode.entries.forEach { assertNotNull(it.toCompose()); assertNotNull(it.toAndroid()) }
        assertEquals(android.graphics.BlendMode.MULTIPLY, BlendMode.Multiply.toAndroid())
        val paint = android.graphics.Paint()
        paint.setBlend(BlendMode.Normal); assertNull(paint.blendMode)
        paint.setBlend(BlendMode.Screen); assertEquals(android.graphics.BlendMode.SCREEN, paint.blendMode)
        paint.setBlend(BlendMode.Normal); assertNull(paint.blendMode)
    }

    @Test fun listingsHappyPaths() {
        // Form fields: count, id, type, rect, name, value, checked
        fun str(b: ByteBuffer, s: String) { val bytes = s.toByteArray(); b.putShort(bytes.size.toShort()); b.put(bytes) }
        val ff = ByteBuffer.allocate(200).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putLong(9L).put(1)
            .putFloat(1f).putFloat(2f).putFloat(3f).putFloat(4f)
        str(ff, "field"); str(ff, "value"); ff.put(1)
        val fields = com.vayunmathur.pdf.util.SafePdfListings.parseFormFields(ff.array().copyOf(ff.position()))
        assertEquals(1, fields.size); assertEquals("field", fields[0].name); assertTrue(fields[0].checked)

        val out = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putShort(2).putInt(7)
        str(out, "Title")
        val outline = com.vayunmathur.pdf.util.SafePdfListings.parseOutline(out.array().copyOf(out.position()))
        assertEquals(listOf(com.vayunmathur.pdf.util.SafeOutlineItem(2, 7, "Title")), outline)

        val sm = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(3)
            .putFloat(1f).putFloat(2f).putFloat(3f).putFloat(4f)
        assertEquals(3, com.vayunmathur.pdf.util.SafePdfListings.parseSearchMatches(sm.array())[0].page)

        val empty = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()
        assertTrue(com.vayunmathur.pdf.util.SafePdfListings.parseAnnotations(empty).isEmpty())
        assertTrue(com.vayunmathur.pdf.util.SafePdfListings.parseLinks(empty).isEmpty())
        assertTrue(com.vayunmathur.pdf.util.SafePdfListings.parseOutline(empty).isEmpty())
    }

    @Test fun composeAppendImageFromJpeg() { runBlocking {
        val png = File(ctx.cacheDir, "final.png")
        png.outputStream().use { Bitmap.createBitmap(24, 18, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val jpeg = readAsJpeg(ctx, Uri.fromFile(png)); assertNotNull(jpeg)
        val doc = ComposePdfDocument.create()
        try {
            assertEquals(1, doc.appendImage(jpeg.bytes, jpeg.width, jpeg.height))
            assertEquals(1, doc.pageCount())
            assertNotNull(doc.renderPage(0))
            assertEquals(1, doc.appendImage(byteArrayOf(1, 2, 3), 24, 18)) // native embeds bytes without validating JPEG
            assertNotNull(doc.save())
        } finally { doc.close() }
    } }

    @Test fun textSelectionLongPress() {
        val page = SafePdfPage(612f, 792f, listOf(
            PdfPrimitive.Text(Offset(72f, 700f), 16f, 0xFF000000.toInt(), "Hello selectable world"),
        ))
        rule.setContent {
            DynamicTheme {
                Box(Modifier.size(400.dp)) { TextSelectionLayer(page = page, ch = 400f, scale = 0.6f, ocr = null) }
            }
        }
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            down(Offset(80f, 60f))
            advanceEventTime(700)
            moveBy(Offset(160f, 60f))
            up()
        }
        rule.waitForIdle()
        // Tap dismisses any selection
        rule.onRoot().performTouchInput { down(Offset(80f, 60f)); up() }
        rule.waitForIdle()
    }
}
