package com.vayunmathur.photos.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.HealMode
import com.vayunmathur.photos.data.HealingStroke
import com.vayunmathur.photos.data.HealingStrokes
import com.vayunmathur.photos.data.OcrBox
import com.vayunmathur.photos.data.OcrLayout
import com.vayunmathur.photos.data.SmudgeStroke
import com.vayunmathur.photos.data.SmudgeStrokes
import com.vayunmathur.photos.data.applyHealingToBitmap
import com.vayunmathur.photos.data.applyToBitmap
import com.vayunmathur.photos.data.parseOcrLayout
import com.vayunmathur.photos.data.toJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cov-c2: tokenizer, face maths, OCR layout JSON and bitmap heal/smudge. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosCovC2LogicTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun clipTokenizerEncodesWordsNumbersAndContractions() {
        val tokenizer = ClipTokenizer.load(context)
        assertNotNull(tokenizer, "bundled CLIP merges asset must load in unit tests")
        val tokens = tokenizer.tokenize("A red bicycle, 2 wheels! It's fast.")
        assertEquals(ClipTokenizer.CONTEXT_LENGTH, tokens.size)
        assertEquals(ClipTokenizer.START_TOKEN, tokens[0])
        assertTrue(tokens[1] != 0, "first word must encode to a vocab id")
        // End token follows the encoded pieces, then zero padding (CLIP convention).
        val end = tokens.indexOf(ClipTokenizer.END_TOKEN)
        assertTrue(end > 1)
        assertTrue(tokens.drop(end + 1).all { it == 0 })
    }

    @Test fun clipTokenizerHandlesEmptyLongAndUnicodeText() {
        val tokenizer = assertNotNull(ClipTokenizer.load(context))
        val empty = tokenizer.tokenize("")
        assertEquals(ClipTokenizer.START_TOKEN, empty[0])
        assertEquals(ClipTokenizer.END_TOKEN, empty[1])
        val long = tokenizer.tokenize("word ".repeat(200))
        assertEquals(ClipTokenizer.CONTEXT_LENGTH, long.size)
        assertEquals(ClipTokenizer.END_TOKEN, long[ClipTokenizer.CONTEXT_LENGTH - 1])
        val unicode = tokenizer.tokenize("café naïve 123")
        assertEquals(ClipTokenizer.START_TOKEN, unicode[0])
        // Tokenizing is deterministic and cached pieces give identical results.
        assertTrue(tokenizer.tokenize("café naïve 123").contentEquals(unicode))
    }

    @Test fun faceRecognizerSimilarityAndEncodingRoundTrip() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(1f, FaceRecognizer.similarity(a, a), 0.001f)
        assertEquals(0f, FaceRecognizer.similarity(a, b), 0.001f)
        assertEquals(-1f, FaceRecognizer.similarity(a, floatArrayOf(1f)))
        assertEquals(-1f, FaceRecognizer.similarity(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f)))
        val normalized = FaceRecognizer.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, normalized[0], 0.001f)
        assertEquals(0.8f, normalized[1], 0.001f)
        assertTrue(FaceRecognizer.l2Normalize(floatArrayOf(0f, 0f)).contentEquals(floatArrayOf(0f, 0f)))
        val values = floatArrayOf(0.25f, -1.5f, 42f, 0f)
        assertTrue(FaceRecognizer.bytesToFloats(FaceRecognizer.floatsToBytes(values)).contentEquals(values))
        assertFalse(FaceRecognizer.modelsAvailable(context))
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        assertTrue(FaceRecognizer.detectAndEmbed(context, bmp).isEmpty())
    }

    @Test fun ocrLayoutRoundTripsAndRejectsBadJson() {
        val layout = OcrLayout(
            w = 200, h = 100,
            boxes = listOf(
                OcrBox(text = "hello", left = 1, top = 2, right = 50, bottom = 20),
                OcrBox(text = "world", left = 1, top = 30, right = 60, bottom = 50, vertical = true),
            ),
        )
        assertEquals("hello\nworld", layout.text)
        assertEquals(layout, parseOcrLayout(layout.toJson()))
        assertNull(parseOcrLayout(null))
        assertNull(parseOcrLayout("  "))
        assertNull(parseOcrLayout("{not json"))
        assertNull(parseOcrLayout(OcrLayout(0, 100, emptyList()).toJson()))
    }

    @Test fun healingAndSmudgeModifyBitmapPixels() {
        val bmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        // Paint a blue patch the heal stroke samples from.
        for (x in 0 until 5) for (y in 0 until 5) bmp.setPixel(x, y, Color.BLUE)
        val healed = HealingStrokes(
            listOf(
                HealingStroke(
                    sourceX = 0.1f, sourceY = 0.1f,
                    points = listOf(0.8f to 0.8f, 0.85f to 0.85f),
                    brushSize = 0.2f, mode = HealMode.Clone,
                ),
                HealingStroke(sourceX = 0f, sourceY = 0f, points = emptyList()),
            ),
        ).applyHealingToBitmap(bmp)
        assertEquals(20, healed.width)
        assertTrue(HealingStrokes().isIdentity())
        assertFalse(healed.sameAs(bmp))

        val smudged = SmudgeStrokes(
            listOf(
                SmudgeStroke(points = listOf(0.1f to 0.5f, 0.9f to 0.5f), strength = 0.9f, brushSize = 0.2f),
                SmudgeStroke(points = listOf(0.5f to 0.5f)),
            ),
        ).applyToBitmap(bmp)
        assertEquals(20, smudged.width)
        assertTrue(SmudgeStrokes().isIdentity())
    }
}
