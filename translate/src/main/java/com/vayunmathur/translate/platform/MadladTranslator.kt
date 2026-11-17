package com.vayunmathur.translate.platform

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ml.MadladHandle
import com.vayunmathur.translate.domain.TranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [TranslationEngine] backed by [MadladHandle] — MADLAD400-3B-MT on `:library:ml`'s
 * Vulkan runtime.
 *
 * The model is loaded lazily from [MadladModel.modelDir] the first time it is needed, and only
 * if the two files have been downloaded; until then [isAvailable] is false and the UI shows
 * a download prompt. Language detection is unnecessary — the UI's source picker names the
 * source — so [detectLanguage] returns null.
 *
 * MADLAD needs only the TARGET language: its tag (`<2en>`) leads the encoder source and
 * the decoder starts unforced from `<unk>`. The source language selects nothing — the
 * encoder reads whatever script arrives — so a null [from] (the UI's Auto sentinel)
 * needs no fallback: the tag is built from [to] alone. An unknown target tag fails
 * closed (null) rather than mistranslating.
 *
 * # The lock is not only for the handle
 *
 * A translation re-records the network once per decoded token, so two concurrent calls
 * would interleave recordings of the same command buffer. The mutex therefore covers the
 * whole of [translate] rather than just the load, and [close] too.
 */
class MadladTranslator(private val context: Context) : TranslationEngine {

    private val lock = Mutex()
    private var model: MadladHandle? = null
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
            val tag = MadladModel.targetTag(to)
                ?: return@withContext null
            lock.withLock {
                val handle = ensure() ?: return@withContext null
                try {
                    handle.translate(text, tag)?.ifBlank { null }
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
     * translation is in flight — which `onCleared` guarantees: `viewModelScope` is
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
     * because the one recoverable cause — a download that finished between two calls —
     * is exactly the case a latch would make permanent. Anything else fails twice and
     * stays failed.
     */
    private fun ensure(): MadladHandle? {
        model?.let { return it }
        if (closed || attempts >= MAX_ATTEMPTS) return null
        if (!MadladModel.isDownloaded(context)) return null
        attempts++
        val handle = MadladHandle.inDirectory(MadladModel.modelDir(context))
        if (!handle.isAvailable) {
            handle.close()
            return null
        }
        model = handle
        return handle
    }

    /** Logging and load-retry constants for [MadladTranslator]. */
    companion object {
        private const val TAG = "MadladTranslator"

        /** One retry, for a download that completed between two calls. See `ensure`. */
        private const val MAX_ATTEMPTS = 2
    }
}

