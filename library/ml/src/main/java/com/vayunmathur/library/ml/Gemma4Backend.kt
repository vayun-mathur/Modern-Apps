package com.vayunmathur.library.ml

import java.io.File

/**
 * The native boundary [Gemma4Handle] talks to: every Gemma 4 JNI entry point
 * it uses, behind an interface so the prompt/cache/generation pipeline (which
 * is ordinary Kotlin) can be exercised on the host JVM with a fake.
 *
 * Production always uses [NativeGemma4Backend], which forwards to [MlNative]
 * one-for-one — this seam changes no behaviour, it only names the boundary
 * that was previously a direct `MlNative.` call at each site. Tests inject a
 * fake through [Gemma4Handle]'s internal constructor.
 */
internal interface Gemma4Backend {
    /** Whether the native library loaded at all (says nothing about Vulkan). */
    val isAvailable: Boolean

    /** Open the model in [directory]; 0 on any failure. */
    fun create(directory: File): Long

    /** Native's own message for the last failed [create], or null. */
    fun lastError(): String?

    fun encode(handle: Long, text: String, specials: Array<String>): IntArray?
    fun decode(handle: Long, tokens: IntArray): String?
    fun push(handle: Long, tokens: IntArray): Int
    fun pushSoft(handle: Long, embeddings: FloatArray): Int
    fun step(handle: Long, token: Int): Int
    fun logits(handle: Long, token: Int): FloatArray?
    fun topK(handle: Long, token: Int, k: Int): FloatArray?
    fun position(handle: Long): Int
    fun reset(handle: Long)
    fun seek(handle: Long, position: Int): Int
    fun capacity(handle: Long): Int
    fun grow(handle: Long, needed: Int): Int
    fun loadPrefix(handle: Long, positions: Int, cache: ByteArray): Int
    fun destroy(handle: Long)
    fun capabilities()
    fun imageProbe()
    fun benchmark(handle: Long)
}

/** The production backend: a straight delegation to [MlNative]. */
internal object NativeGemma4Backend : Gemma4Backend {
    override val isAvailable: Boolean get() = MlNative.isAvailable
    override fun create(directory: File): Long = createGemma4Handle(directory)
    override fun lastError(): String? = MlNative.lastGemma4Error()
    override fun encode(handle: Long, text: String, specials: Array<String>): IntArray? =
        MlNative.encodeGemma4(handle, text, specials)
    override fun decode(handle: Long, tokens: IntArray): String? =
        MlNative.decodeGemma4(handle, tokens)
    override fun push(handle: Long, tokens: IntArray): Int = MlNative.pushGemma4(handle, tokens)
    override fun pushSoft(handle: Long, embeddings: FloatArray): Int =
        MlNative.pushSoftGemma4(handle, embeddings)
    override fun step(handle: Long, token: Int): Int = MlNative.stepGemma4(handle, token)
    override fun logits(handle: Long, token: Int): FloatArray? = MlNative.logitsGemma4(handle, token)
    override fun topK(handle: Long, token: Int, k: Int): FloatArray? =
        MlNative.topKGemma4(handle, token, k)
    override fun position(handle: Long): Int = MlNative.positionGemma4(handle)
    override fun reset(handle: Long) = MlNative.resetGemma4(handle)
    override fun seek(handle: Long, position: Int): Int = MlNative.seekGemma4(handle, position)
    override fun capacity(handle: Long): Int = MlNative.capacityGemma4(handle)
    override fun grow(handle: Long, needed: Int): Int = MlNative.growGemma4(handle, needed)
    override fun loadPrefix(handle: Long, positions: Int, cache: ByteArray): Int =
        MlNative.loadPrefixGemma4(handle, positions, cache)
    override fun destroy(handle: Long) = MlNative.destroyGemma4(handle)
    override fun capabilities() {
        MlNative.capabilitiesGemma4()
    }
    override fun imageProbe() {
        MlNative.imageProbeGemma4()
    }
    override fun benchmark(handle: Long) {
        MlNative.benchmarkGemma4(handle)
    }
}
