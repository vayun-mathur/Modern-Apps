package com.vayunmathur.translate.platform

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ml.NllbHandle
import com.vayunmathur.translate.domain.Languages
import com.vayunmathur.translate.domain.TranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [TranslationEngine] backed by [NllbHandle] - NLLB-200-distilled-600M on `:library:ml`'s
 * Vulkan runtime.
 *
 * The model is loaded lazily from [NllbModel.modelDir] the first time it is needed, and only
 * if the two files have been downloaded; until then [isAvailable] is false and the UI shows
 * a download prompt. Language detection is unnecessary - the UI's source picker names the
 * source - so [detectLanguage] returns null.
 *
 * NLLB needs BOTH the source and the target language: the source token leads the encoder
 * input and the target token forced-BOSes the decoder. A null [from] (the UI's Auto
 * sentinel) falls back to the [AUTO_FALLBACK] source rather than running in the wrong
 * language: native cannot detect, and guessing any other language would mistranslate
 * instead of failing.
 *
 * # The lock is not only for the handle
 *
 * A translation keeps its KV caches on the device and re-records the network on the way in,
 * so two concurrent calls would clobber each other's state. The mutex therefore covers the
 * whole of [translate] rather than just the load, and [close] too.
 */
class NllbTranslator(private val context: Context) : TranslationEngine {

    private val lock = Mutex()
    private var model: NllbHandle? = null
    private var attempts = 0
    private var closed = false

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.Default) {
        lock.withLock { ensure() != null }
    }

    override suspend fun detectLanguage(text: String): String? = null

    // Broad catch is deliberate: the native translator throws undocumented
    // RuntimeExceptions on bad input, which read as "untranslatable".
    @Suppress("TooGenericExceptionCaught")
    override suspend fun translate(text: String, from: String?, to: String): String? =
        withContext(Dispatchers.Default) {
            if (text.isBlank()) return@withContext ""
            val source = NllbModel.tokenId(Languages.byCode(from ?: AUTO_FALLBACK).flores)
                ?: return@withContext null
            val target = NllbModel.tokenId(Languages.byCode(to).flores)
                ?: return@withContext null
            lock.withLock {
                val handle = ensure() ?: return@withContext null
                try {
                    handle.translate(text, source, target)?.ifBlank { null }
                } catch (e: Exception) {
                    Log.error(TAG, "translate failed", e)
                    null
                }
            }
        }

    /** Call after a successful model download so the next use loads it. */
    suspend fun reset() = lock.withLock { attempts = 0 }

    /**
     * Free the model. Terminal: nothing loads again afterwards, and it is idempotent.
     *
     * Deliberately **not** `suspend`, because the only caller is `ViewModel.onCleared`,
     * which is not. That means it does not take [lock], so it must not run while a
     * translation is in flight - which `onCleared` guarantees: `viewModelScope` is
     * cancelled before it runs and the UI cannot ask for another translation once the
     * view model is cleared.
     */
    fun close() {
        closed = true
        model?.close()
        model = null
    }

    /**
     * The loaded model, loading it if the files are present.
     *
     * Retries are capped at [MAX_ATTEMPTS] rather than latched on the first failure,
     * because the one recoverable cause - a download that finished between two calls -
     * is exactly the case a latch would make permanent. Anything else fails twice and
     * stays failed.
     */
    private fun ensure(): NllbHandle? {
        model?.let { return it }
        if (closed || attempts >= MAX_ATTEMPTS) return null
        if (!NllbModel.isDownloaded(context)) return null
        attempts++
        val reclaimed = NllbModel.deleteRetired(context)
        if (reclaimed > 0) {
            Log.status(TAG, "reclaimed $reclaimed bytes of retired SMaLL-100 weights")
        }
        val handle = NllbHandle.inDirectory(NllbModel.modelDir(context))
        if (!handle.isAvailable) {
            handle.close()
            return null
        }
        model = handle
        return handle
    }

    companion object {
        private const val TAG = "NllbTranslator"

        /**
         * The source used when the UI's source is Auto. English, because it is the
         * highest-resource training pair and the least surprising mistranslation - and
         * because [com.vayunmathur.translate.domain.Languages.AUTO] mirrors it, so the
         * two cannot drift apart without the compiler noticing only one changed.
         */
        private const val AUTO_FALLBACK = "en"

        /** One retry, for a download that completed between two calls. See `ensure`. */
        private const val MAX_ATTEMPTS = 2
    }
}

