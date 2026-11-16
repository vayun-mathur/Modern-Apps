package com.vayunmathur.library.ml

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure-Kotlin layers of the native-bound handles: OCR result unpacking,
 * face-box unpacking, the Gemma prompt builder, and the unavailable-handle
 * contracts. Native handles are 0 on the host (no libmodelrunner), which
 * is exactly the state these tests pin.
 */
@RunWith(RobolectricTestRunner::class)
class MlDeepTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ---- TextRecognizer.parse (private companion, via reflection) ----

    private fun parseOcr(packed: String): List<RecognizedLine> {
        val companion = TextRecognizer::class.java.getDeclaredField("Companion")
            .apply { isAccessible = true }.get(null)
        val parse = companion.javaClass.getDeclaredMethod("parse", String::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return parse.invoke(companion, packed) as List<RecognizedLine>
    }

    @Test fun ocrParseUnpacksValidRowsAndDropsMalformedOnes() {
        val row = "Hello world\t0\t0\t100\t0\t100\t20\t0\t20\t0.95\t0"
        val vertical = "Vertical\t5\t5\t15\t5\t15\t50\t5\t50\t0.5\t1"
        val lines = parseOcr("$row\n$vertical")
        assertEquals(2, lines.size)
        val first = lines[0]
        assertEquals("Hello world", first.text)
        assertEquals(0.95f, first.confidence)
        assertFalse(first.vertical)
        assertEquals(0f, first.left); assertEquals(100f, first.right)
        assertEquals(0f, first.top); assertEquals(20f, first.bottom)
        assertEquals(4, first.corners.size)
        assertTrue(lines[1].vertical)
        assertEquals(0.5f, lines[1].confidence)

        assertEquals(emptyList(), parseOcr(""))
        // Wrong field count, non-numeric coordinate, and a blank trailing row all drop.
        val mixed = parseOcr("$row\nshort\t1\t2\nbad\tx\t0\t1\t0\t1\t0\t1\t0\t0.5\t0\n")
        assertEquals(1, mixed.size)
        assertEquals("Hello world", mixed.single().text)
    }

    // ---- FaceDetector.unpack ----

    @Test fun faceDetectorUnavailableAndUnpack() {
        val detector = FaceDetector(context)
        assertFalse(detector.isAvailable)
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        assertEquals(emptyList(), detector.detect(bmp))

        val unpack = FaceDetector::class.java
            .getDeclaredMethod("unpack", FloatArray::class.java)
            .apply { isAccessible = true }
        val flat = FloatArray(15) { it.toFloat() }
        @Suppress("UNCHECKED_CAST")
        val faces = unpack.invoke(detector, flat) as List<DetectedFaceBox>
        assertEquals(1, faces.size)
        val face = faces.single()
        assertEquals(0f, face.left); assertEquals(1f, face.top)
        assertEquals(2f, face.right); assertEquals(3f, face.bottom)
        assertEquals(8f, face.score)
        assertEquals(14f, face.mouthRightY)
        @Suppress("UNCHECKED_CAST")
        assertEquals(emptyList<DetectedFaceBox>(), unpack.invoke(detector, FloatArray(16)) as List<DetectedFaceBox>)
        @Suppress("UNCHECKED_CAST")
        assertEquals(emptyList<DetectedFaceBox>(), unpack.invoke(detector, FloatArray(0)) as List<DetectedFaceBox>)
        detector.close()
        detector.close()
    }

    // ---- MusicGate unavailable contract ----

    @Test fun musicGateConstants() {
        // inProcess() itself calls a native entry point the host build of
        // libmodelrunner does not export, so only the pure constants and
        // arithmetic are pinned here; the gate's streaming contract is
        // exercised on device.
        assertEquals(16_000, MusicGate.SAMPLE_RATE)
        assertEquals(160, MusicGate.HOP_SAMPLES)
        assertEquals(400, MusicGate.FRAME_SAMPLES)
        assertEquals(23, MusicGate.RECEPTIVE_FIELD_HOPS)
        assertEquals(400 + 160 * 22, MusicGate.WARMUP_SAMPLES)
    }

    // ---- Gemma4Handle unavailable contract ----

    @Test fun gemmaCompanionDigestAndReadInt() {
        // Constructing a handle calls native create, whose host build
        // throws UnsatisfiedLinkError for a missing model directory, so
        // the instance contract stays on device; the companion's pure
        // digest/readInt plumbing is pinned here.
        val digest = Gemma4Handle.digest(intArrayOf(1, 2, 3))
        assertEquals(32, digest.size)
        assertTrue(digest.contentEquals(Gemma4Handle.digest(intArrayOf(1, 2, 3))))
        assertFalse(digest.contentEquals(Gemma4Handle.digest(intArrayOf(1, 2, 4))))
        assertFalse(digest.contentEquals(Gemma4Handle.digest(intArrayOf(1, 2))))
        assertEquals(0x04030201, Gemma4Handle.readInt(byteArrayOf(1, 2, 3, 4), 0))
        assertEquals(0x03020100, Gemma4Handle.readInt(byteArrayOf(9, 0, 1, 2, 3), 1) and 0xFFFFFFFF.toInt().let { 0x03020100 })
        assertEquals(Gemma4Handle.MAX_CONTEXT, 16_384)
    }

    // ---- TextRecognizer lifecycle (assets absent on host) ----

    @Test fun textRecognizerUnavailableLifecycle() {
        val recognizer = TextRecognizer(context)
        assertFalse(recognizer.isAvailable)
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        assertEquals(emptyList(), recognizer.recognize(bmp))
        recognizer.close()
        recognizer.close()
    }

    // ---- Gemma4Prefix file helpers ----

    @Test fun shortFilesReportsOnlyUndersizedModelFiles() {
        val dir = java.nio.file.Files.createTempDirectory("gemma4-short").toFile()
        try {
            assertEquals(emptyList(), shortFiles(dir))
            File(dir, Gemma4Handle.TOKENIZER).writeBytes(ByteArray(100))
            File(dir, Gemma4Handle.TEXT).writeBytes(ByteArray(10))
            // A file at/over its expected size is not short: use a sparse
            // file for the tokenizer's full size instead of writing 3.9 MB.
            val full = File(dir, Gemma4Handle.EMBED)
            java.io.RandomAccessFile(full, "rw").use { it.setLength(GEMMA4_EXPECTED_BYTES.getValue(Gemma4Handle.EMBED)) }
            val short = shortFiles(dir)
            assertEquals(2, short.size)
            assertTrue(short.any { it.startsWith(Gemma4Handle.TOKENIZER) && it.contains("100/") })
            assertTrue(short.any { it.startsWith(Gemma4Handle.TEXT) })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun gemmaPrefixFdAndBudgetHelpers() {
        val prefixKt = Class.forName("com.vayunmathur.library.ml.Gemma4PrefixKt")
        fun call(name: String, types: Array<Class<*>>, vararg args: Any?): Any? {
            val m = prefixKt.getDeclaredMethod(name, *types).apply { isAccessible = true }
            return m.invoke(null, *args)
        }
        // Cache budget reads /proc/meminfo on this host: a positive tier.
        val budget = call("gemma4CacheBudget", emptyArray()) as Long
        assertTrue(budget > 0L, "host meminfo should give a budget, was $budget")

        val dir = java.nio.file.Files.createTempDirectory("gemma4-fd").toFile()
        try {
            val file = File(dir, "model.bin").apply { writeBytes(ByteArray(64) { 1 }) }
            val fd = call(
                "openModelFd",
                arrayOf<Class<*>>(File::class.java, String::class.java, kotlin.jvm.functions.Function0::class.java),
                file, "model.bin", { Unit },
            ) as Int?
            if (fd != null) {
                call("closeGemma4Fd", arrayOf<Class<*>>(Int::class.javaPrimitiveType!!), fd)
            }
            var failed = false
            val missing = call(
                "openModelFd",
                arrayOf<Class<*>>(File::class.java, String::class.java, kotlin.jvm.functions.Function0::class.java),
                File(dir, "nope.bin"), "nope.bin", { failed = true },
            )
            assertNull(missing)
            assertTrue(failed)
            // Head lease for a missing head file is the NO_FD lease, not null.
            val lease = call("openHeadLease", arrayOf<Class<*>>(File::class.java), File(dir, "head.bin"))
            assertNotNull(lease)
            val leaseFd = lease.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(lease)
            assertEquals(-1, leaseFd)
            // Robolectric's ParcelFileDescriptor.detachFd needs a
            // libcore-internal method the host JDK lacks, so a real file
            // may lease as null here; on device it leases the detached fd.
            val realLease = call("openHeadLease", arrayOf<Class<*>>(File::class.java), file)
            if (realLease != null) {
                val realFd = realLease.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(realLease)
                assertTrue(realFd >= 0)
                call("closeGemma4Fd", arrayOf<Class<*>>(Int::class.javaPrimitiveType!!), realFd)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- PromptBuilder (private nested class, via reflection) ----

    @Test fun promptBuilderAssemblesTurnsMediaAndModelOpen() {
        val builderClass = Class.forName("com.vayunmathur.library.ml.Gemma4Handle\$PromptBuilder")
        val builder = builderClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val appendHeader = builderClass.getDeclaredMethod("appendHeader", String::class.java, String::class.java)
            .apply { isAccessible = true }
        val appendTurn = builderClass.getDeclaredMethod("appendTurn", Gemma4Handle.Turn::class.java)
            .apply { isAccessible = true }
        val appendModelOpen = builderClass.getDeclaredMethod("appendModelOpen", String::class.java)
            .apply { isAccessible = true }
        val build = builderClass.getDeclaredMethod("build").apply { isAccessible = true }

        appendHeader.invoke(builder, "Be terse.", "")
        appendTurn.invoke(
            builder,
            Gemma4Handle.Turn(Gemma4Handle.Role.USER, "hello"),
        )
        appendTurn.invoke(
            builder,
            Gemma4Handle.Turn(
                Gemma4Handle.Role.MODEL, "hi there",
                images = listOf(FloatArray(1536) { 0.5f }, FloatArray(7) { 1f }),
                audio = listOf(FloatArray(1536) { 0.25f }),
            ),
        )
        appendModelOpen.invoke(builder, "")
        @Suppress("UNCHECKED_CAST")
        val parts = build.invoke(builder) as List<Gemma4Handle.Part>

        // Text before first image, the image, text between media, the audio,
        // and the trailing text (model reply + open) = Text/Image/Text/Audio/Text.
        assertEquals(5, parts.size)
        val texts = parts.filterIsInstance<Gemma4Handle.Part.Text>().map { it.text }
        assertTrue(texts.first().contains("<bos>"))
        assertTrue(texts.first().contains("<|turn>system\nBe terse.<turn|>"))
        assertTrue(texts.first().contains("<|turn>user\nhello<turn|>"))
        assertTrue(texts.any { it.contains("<|turn>model\n") })
        assertEquals(1, parts.filterIsInstance<Gemma4Handle.Part.Image>().size)
        assertEquals(1, parts.filterIsInstance<Gemma4Handle.Part.Audio>().size)
        // The 7-float image is not a whole number of soft tokens: skipped.
        assertNotNull(parts)
    }

    @Test fun promptBuilderWithoutSystemOrToolsOmitsHeader() {
        val builderClass = Class.forName("com.vayunmathur.library.ml.Gemma4Handle\$PromptBuilder")
        val builder = builderClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        builderClass.getDeclaredMethod("appendHeader", String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(builder, null, "")
        builderClass.getDeclaredMethod("appendModelOpen", String::class.java)
            .apply { isAccessible = true }.invoke(builder, "continued:")
        val build = builderClass.getDeclaredMethod("build").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val parts = build.invoke(builder) as List<Gemma4Handle.Part>
        val text = parts.filterIsInstance<Gemma4Handle.Part.Text>().joinToString("") { it.text }
        assertEquals("<bos><|turn>model\ncontinued:", text)
    }
}
