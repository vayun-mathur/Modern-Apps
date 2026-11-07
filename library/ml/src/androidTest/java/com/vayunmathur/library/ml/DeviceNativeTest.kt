package com.vayunmathur.library.ml

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Pass 3 (library/ml): the JNI bridge on a real (emulator) device, where
 * `libmodelrunner.so` actually loads and the android-only symbols exist.
 *
 * No real model weights are used: the `.maml` files in androidTest/assets
 * are garbage, so every Vulkan-backed create must refuse gracefully (handle
 * 0, feature off) — except [MusicGate], which is CPU-only with no weights
 * and genuinely runs.
 */
@RunWith(AndroidJUnit4::class)
class DeviceNativeTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /**
     * The instrumentation (test APK) context: the garbage `.maml` assets live
     * in the test APK, not in the library's own empty app APK, so asset-based
     * handles must be built from here to reach their create paths at all.
     */
    private val testContext: android.content.Context
        get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context

    @Test
    fun nativeLibraryLoadsOnDevice() {
        assertTrue(MlNative.isAvailable)
    }

    @Test
    fun musicGateActuallyRunsOnDevice() {
        val gate = MusicGate.inProcess()
        assertTrue(gate.isAvailable)
        // two seconds of silence: warm-up completes and hops report
        val scores = gate.push(ShortArray(16_000 * 2))
        assertNotNull(scores)
        assertTrue(scores!!.isNotEmpty())
        assertTrue(scores.all { it in 0f..1f })
        // count must fit the buffer
        assertNull(gate.push(ShortArray(4), count = 99))
        gate.reset()
        val afterReset = gate.push(ShortArray(16_000 * 2))
        assertNotNull(afterReset)
        gate.close()
        gate.close()
        assertEquals("Now Playing music gate (CPU)", gate.toString())
    }

    @Test
    fun gemmaHandleWithMissingAndShortFilesIsUnavailableOnDevice() {
        val empty = File(context.cacheDir, "gemma-device-empty").apply { mkdirs() }
        val handle = Gemma4Handle.inDirectory(empty)
        assertFalse(handle.isAvailable)
        // on device the native error read works (no UnsatisfiedLinkError):
        // loadError is the native/short-file message or null, never a throw
        handle.loadError
        assertEquals(0, handle.position)
        assertNull(handle.generate(listOf(Gemma4Handle.Turn(Gemma4Handle.Role.USER, "hi")), null))
        assertEquals("", handle.decode(intArrayOf(1)))
        handle.close()

        val short = File(context.cacheDir, "gemma-device-short").apply { mkdirs() }
        File(short, Gemma4Handle.TEXT).writeBytes(byteArrayOf(1))
        File(short, Gemma4Handle.EMBED).writeBytes(byteArrayOf(1))
        File(short, Gemma4Handle.TOKENIZER).writeBytes(byteArrayOf(1))
        val shortHandle = Gemma4Handle.inDirectory(short)
        assertFalse(shortHandle.isAvailable)
        val error = shortHandle.loadError
        assertTrue(error == null || error.isNotBlank())
        shortHandle.close()
    }

    @Test
    fun gemmaCreateWithFullSizeSparseFilesRefusesGracefullyOnDevice() {
        // Sparse files at the manifest sizes pass the Kotlin file checks and
        // reach the native create, which must refuse zero-filled weights
        // with a handle of 0 rather than crashing.
        val dir = File(context.cacheDir, "gemma-device-sparse").apply { mkdirs() }
        for (name in listOf(Gemma4Handle.TEXT, Gemma4Handle.EMBED, Gemma4Handle.TOKENIZER)) {
            val f = File(dir, name)
            java.io.RandomAccessFile(f, "rw").use {
                it.setLength(GEMMA4_EXPECTED_BYTES.getValue(name))
            }
        }
        val handle = Gemma4Handle.inDirectory(dir)
        assertFalse(handle.isAvailable)
        handle.close()
        dir.deleteRecursively()
    }

    @Test
    fun gemmaCreateWithHeadLeaseAlsoRefusesGracefullyOnDevice() {
        // As the sparse test, plus the vision HEAD span: the head lease is
        // opened and handed to native, which still refuses zero weights.
        val dir = File(context.cacheDir, "gemma-device-head").apply { mkdirs() }
        for (name in Gemma4Handle.FILES_WITH_HEAD) {
            val f = File(dir, name)
            java.io.RandomAccessFile(f, "rw").use {
                it.setLength(GEMMA4_EXPECTED_BYTES.getValue(name))
            }
        }
        val handle = Gemma4Handle.inDirectory(dir)
        assertFalse(handle.isAvailable)
        handle.close()
        dir.deleteRecursively()
    }

    @Test
    fun supertonicFromGarbageAssetsIsUnavailableOnDevice() {
        val synth = SupertonicSynthesizer.inAssets(testContext.assets, "supertonic")
        assertFalse(synth.isAvailable)
        synth.close()
    }

    @Test
    fun translationHandlesWithGarbageFilesAreUnavailableOnDevice() {
        val dir = File(context.cacheDir, "translate-device").apply { mkdirs() }
        File(dir, MadladHandle.GRAPH).writeBytes(byteArrayOf(1, 2, 3))
        File(dir, MadladHandle.TOKENIZER).writeBytes("not json".toByteArray())
        val madlad = MadladHandle.inDirectory(dir)
        assertFalse(madlad.isAvailable)
        madlad.close()

        File(dir, NllbHandle.GRAPH).writeBytes(byteArrayOf(1, 2, 3))
        File(dir, NllbHandle.TOKENIZER).writeBytes("not json".toByteArray())
        val nllb = NllbHandle.inDirectory(dir)
        assertFalse(nllb.isAvailable)
        nllb.close()
        dir.deleteRecursively()
    }

    @Test
    fun nativeBackendToleratesAZeroHandleOnDevice() {
        // Every bridge method documents handle 0 as safe; prove it for the
        // ones that do not need a model behind them.
        val backend = NativeGemma4Backend
        assertTrue(backend.isAvailable)
        assertEquals(-1, backend.position(0L)) // native no-handle answer
        backend.reset(0L)
        assertEquals(0, backend.decode(0L, intArrayOf(1, 2))?.length ?: 0)
        backend.destroy(0L)
        backend.loadPrefix(0L, 0, ByteArray(0))
    }

    @Test
    fun visionAndAudioTowersRefuseSparseZeroWeightsOnDevice() {
        val dir = File(context.cacheDir, "towers-device").apply { mkdirs() }
        val visionFile = File(dir, Gemma4VisionHandle.VISION)
        java.io.RandomAccessFile(visionFile, "rw").use {
            it.setLength(GEMMA4_EXPECTED_BYTES.getValue(Gemma4VisionHandle.VISION))
        }
        val vision = Gemma4VisionHandle.inDirectory(dir)
        assertFalse(vision.isAvailable)
        vision.close()

        val audioFile = File(dir, Gemma4AudioHandle.AUDIO)
        java.io.RandomAccessFile(audioFile, "rw").use {
            it.setLength(GEMMA4_EXPECTED_BYTES.getValue(Gemma4AudioHandle.AUDIO))
        }
        val audio = Gemma4AudioHandle.inDirectory(dir)
        assertFalse(audio.isAvailable)
        audio.close()
        dir.deleteRecursively()
    }

    @Test
    fun faceAndSegmenterCreatesRefuseGarbageWeightsOnDevice() {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

        val detector = FaceDetector(testContext)
        assertFalse(detector.isAvailable)
        assertTrue(detector.detect(bmp).isEmpty())
        detector.close()

        val embedder = FaceEmbedder(testContext)
        assertFalse(embedder.isAvailable)
        assertNull(embedder.embed(bmp))
        embedder.close()

        val selfie = SelfieSegmenter(testContext)
        assertFalse(selfie.isAvailable)
        assertNull(selfie.segment(bmp))
        selfie.close()

        val subject = SubjectSegmenter(testContext)
        assertFalse(subject.isAvailable)
        assertNull(subject.segment(bmp))
        subject.close()

        val modnet = ModnetSegmenter(testContext)
        assertFalse(modnet.isAvailable)
        assertNull(modnet.segment(bmp))
        modnet.close()
    }

    @Test
    fun textRecognizerRefusesGarbageAssetsOnDevice() {
        val recognizer = TextRecognizer(testContext)
        assertFalse(recognizer.isAvailable)
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        assertTrue(recognizer.recognize(bmp).isEmpty())
        recognizer.close()
        recognizer.close()
    }

    @Test
    fun supertonicWithAnEmptyDirectoryIsUnavailableOnDevice() {
        val dir = File(context.cacheDir, "supertonic-device-empty").apply { mkdirs() }
        val synth = SupertonicSynthesizer.inDirectory(dir)
        assertFalse(synth.isAvailable)
        assertEquals(0, synth.synthesize("hello", "en").size)
        synth.close()
    }

    @Test
    fun assetHandlesWithoutModelsAreUnavailableOnDevice() {
        val whisper = WhisperHandle.inAssets(
            testContext.assets,
            special = intArrayOf(1, 2, 3, 4, 5),
            languages = intArrayOf(10),
            suppress = intArrayOf(),
            suppressAtBegin = intArrayOf(),
        )
        assertFalse(whisper.isAvailable)
        whisper.close()

        val maia = MaiaHandle.inAssets(testContext.assets)
        assertFalse(maia.isAvailable)
        maia.close()

        val clip = ClipHandle.inAssets(testContext.assets)
        assertFalse(clip.isAvailable)
        clip.close()

        val nnfp = NnfpHandle.inAssets(testContext.assets)
        assertFalse(nnfp.isAvailable)
        nnfp.close()

        val dir = File(context.cacheDir, "handles-device-empty").apply { mkdirs() }
        val nllb = NllbHandle.inDirectory(dir)
        assertFalse(nllb.isAvailable)
        nllb.close()

        val madlad = MadladHandle.inDirectory(dir)
        assertFalse(madlad.isAvailable)
        madlad.close()

        val vision = Gemma4VisionHandle.inDirectory(dir)
        assertFalse(vision.isAvailable)
        vision.close()

        val audio = Gemma4AudioHandle.inDirectory(dir)
        assertFalse(audio.isAvailable)
        audio.close()
    }
}
