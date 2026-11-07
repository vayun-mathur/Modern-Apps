package com.vayunmathur.cast.platform.mirror

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.view.Surface
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowTileService

/**
 * The shadow codec has no surface producer; an encoder that cannot hand out a
 * surface is exactly the failure the pipeline already handles, so give it one
 * backed by a throwaway texture. The texture is retained: a collected
 * SurfaceTexture would detach the surface mid-test.
 */
@Implements(MediaCodec::class)
open class ShadowMediaCodecWithSurface : ShadowMediaCodec() {

    @Implementation
    fun createInputSurface(): Surface {
        val texture = SurfaceTexture(nextTextureId++)
        textures += texture
        return Surface(texture)
    }

    private companion object {
        var nextTextureId = 100
        val textures = mutableListOf<SurfaceTexture>()
    }
}

/**
 * A codec that actually emits: one codec-config buffer, one key frame, one
 * delta frame, then nothing. Per-codec-instance state (Robolectric keeps one
 * shadow per codec), so every encoder an engine starts replays the sequence.
 * Output buffers are ours and generously sized - the shadow codec's own are
 * smaller than one 20 ms PCM frame, which real encoder buffers never are.
 */
@Implements(MediaCodec::class)
class ShadowMediaCodecSynthesizing : ShadowMediaCodecWithSurface() {

    private var outputStep = 0

    @Implementation
    fun getInputBuffer(index: Int): java.nio.ByteBuffer =
        java.nio.ByteBuffer.allocate(INPUT_CAPACITY)

    @Implementation
    fun getOutputBuffer(index: Int): java.nio.ByteBuffer =
        java.nio.ByteBuffer.wrap(ByteArray(OUTPUT_CAPACITY) { (it * 7 + 1).toByte() })

    @Implementation
    fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int {
        // Queued input (the audio paths) produces real shadow output: adopt
        // its index so its buffer-ownership bookkeeping stays consistent.
        // The first call after configure only reports the output format;
        // consume that signal here so callers see frames, not a format event.
        var shadowIndex = runCatching {
            native_dequeueOutputBuffer(info, timeoutUs)
        }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
        if (shadowIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            shadowIndex = runCatching {
                native_dequeueOutputBuffer(info, timeoutUs)
            }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
        }
        if (shadowIndex >= 0) return shadowIndex
        // Nothing queued (surface-fed video): replay the synthetic sequence.
        return syntheticOutput(info)
    }

    private fun syntheticOutput(info: MediaCodec.BufferInfo): Int =
        when (outputStep++) {
            0 -> {
                info.set(0, CONFIG_BYTES, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                0
            }
            1 -> {
                info.set(0, KEY_BYTES, 33_333L, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                0
            }
            2 -> {
                info.set(0, DELTA_BYTES, 66_666L, 0)
                0
            }
            else -> MediaCodec.INFO_TRY_AGAIN_LATER
        }

    private companion object {
        const val INPUT_CAPACITY = 16_384
        const val OUTPUT_CAPACITY = 16_384
        const val CONFIG_BYTES = 8
        const val KEY_BYTES = 48
        const val DELTA_BYTES = 24
    }
}

/** Allocates a projection without a framework impl: every call is a no-op. */
@org.robolectric.annotation.Implements(android.media.projection.MediaProjection::class)
class ShadowMediaProjectionFake {

    @org.robolectric.annotation.Implementation
    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        flags: Int,
        surface: android.view.Surface?,
        callback: android.hardware.display.VirtualDisplay.Callback?,
        handler: android.os.Handler?,
    ): android.hardware.display.VirtualDisplay? = null

    @org.robolectric.annotation.Implementation
    fun registerCallback(
        callback: android.media.projection.MediaProjection.Callback,
        handler: android.os.Handler?,
    ) = Unit

    @org.robolectric.annotation.Implementation
    fun unregisterCallback(callback: android.media.projection.MediaProjection.Callback) = Unit

    @org.robolectric.annotation.Implementation
    fun stop() = Unit
}

/** Hands out the fake projection the platform service cannot build here. */
@org.robolectric.annotation.Implements(android.media.projection.MediaProjectionManager::class)
class ShadowMediaProjectionManagerFake {

    @org.robolectric.annotation.Implementation
    fun getMediaProjection(
        resultCode: Int,
        data: android.content.Intent,
    ): android.media.projection.MediaProjection {
        // Built through the real constructor: Robolectric weaves shadow
        // dispatch into instances at construction, so an Unsafe-allocated
        // projection would run the real (impl-less) methods instead.
        val iface = Class.forName("android.media.projection.IMediaProjection")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            iface.classLoader,
            arrayOf(iface),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        return android.media.projection.MediaProjection::class.java
            .getConstructor(android.content.Context::class.java, iface)
            .newInstance(context, proxy)
    }
}

/**
 * A normally-constructed projection (Robolectric weaves shadow dispatch in at
 * construction, so Unsafe-allocated instances run impl-less real methods).
 */
fun newFakeProjection(context: android.content.Context): android.media.projection.MediaProjection {
    val iface = Class.forName("android.media.projection.IMediaProjection")
    val proxy = java.lang.reflect.Proxy.newProxyInstance(
        iface.classLoader,
        arrayOf(iface),
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            else -> null
        }
    }
    return android.media.projection.MediaProjection::class.java
        .getConstructor(android.content.Context::class.java, iface)
        .newInstance(context, proxy)
}

/** A projection whose virtual display creation succeeds (with a shell display). */
@org.robolectric.annotation.Implements(android.media.projection.MediaProjection::class)
class ShadowMediaProjectionGivingDisplay {

    @org.robolectric.annotation.Implementation
    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        flags: Int,
        surface: android.view.Surface?,
        callback: android.hardware.display.VirtualDisplay.Callback?,
        handler: android.os.Handler?,
    ): android.hardware.display.VirtualDisplay {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, android.hardware.display.VirtualDisplay::class.java)
            as android.hardware.display.VirtualDisplay
    }

    @org.robolectric.annotation.Implementation
    fun registerCallback(
        callback: android.media.projection.MediaProjection.Callback,
        handler: android.os.Handler?,
    ) = Unit

    @org.robolectric.annotation.Implementation
    fun unregisterCallback(callback: android.media.projection.MediaProjection.Callback) = Unit

    @org.robolectric.annotation.Implementation
    fun stop() = Unit
}
