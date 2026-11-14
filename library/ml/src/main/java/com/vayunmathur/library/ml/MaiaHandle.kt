package com.vayunmathur.library.ml

import android.content.res.AssetManager
import android.os.ParcelFileDescriptor
import com.vayunmathur.library.log.Log

/**
 * On-device human-move prediction for chess: Maia3-5M on the Vulkan compute runtime.
 *
 * An encoder-only transformer over 64 square tokens — width 256, 8 blocks, 8 heads — at 5.23
 * million parameters. See `library/ml/src/main/rust/src/nets/maia.rs`.
 *
 * # It predicts, it does not search
 *
 * One forward pass per move and no tree at all, which is the point rather than a compromise.
 * Maia3 is trained on human games to predict what a player *of a given rating* would play, so
 * a weak setting blunders the way a beginner does. It replaces Stockfish at `Skill Level 0`,
 * which searched eight ply and then threw a move away in ways no human ever does.
 *
 * # Strength is an input
 *
 * [logits] takes `selfElo` and `oppoElo` in 0..5000 — clamped, not rejected — and they enter
 * the model as a blend of two learned embeddings. One weights file therefore covers every
 * difficulty; there is nothing to reload when the player changes it.
 *
 * # One bundled asset, 6.8 MiB
 *
 * `maia3-5m.maml` ships inside the APK, so this has an [inAssets] and no download. Every
 * convolution is int8, quantised per output channel from the fp32 checkpoint; the norms, the
 * biases and the two elo embeddings stay fp16 and are 1% of the parameters. It replaces an
 * 86 MB NNUE, so the APK is about 79 MB smaller.
 *
 * int8 rather than fp16 because it was measured: `scripts/ml/maia_quant_eval.py` puts move
 * agreement against fp32 at 99.0% to 99.5% across the four difficulties, and every
 * disagreement is a position where the top two legal moves are closer together than fp16
 * rounding alone would separate them. The same harness rejects int4 at 87% to 91%.
 *
 * An asset must be stored **uncompressed** for this to work at all: `AssetManager.openFd`
 * throws for a deflated entry. That is what `noCompress += "maml"` in
 * `games/chess/build.gradle.kts` is for.
 *
 * # The caller owns the chess
 *
 * [logits] returns the raw 4352-entry move vector. Legal masking, temperature and sampling all
 * need a legal move list, which this library has no idea how to produce — `MaiaEngine` in
 * `:games:chess` does all three, and also does the mirror-and-swap that [logits] requires for
 * black.
 *
 * # Availability
 *
 * Construction never throws. [isAvailable] is false when `libmodelrunner.so` is missing for
 * this ABI, when the asset is absent, compressed or malformed, or when the device cannot give
 * us a Vulkan device with fp16 compute — and then [logits] returns null. The chess app gates
 * its "play against the AI" option on it rather than offering a mode that cannot move.
 *
 * # Threading
 *
 * Not thread-safe. A caller must hold a lock across [logits] and [close].
 */
class MaiaHandle private constructor(private val source: String) : AutoCloseable {
    private var handle: Long = 0L

    /** True if the graph came up and is the file this runtime was built against. */
    val isAvailable: Boolean get() = handle != 0L

    /**
     * The [MOVES] move logits for a board, or null on failure.
     *
     * [planes] is `PLANE_COUNT * SQUARES` floats, plane-major, with square `rank * 8 + file`
     * so a1 is 0 and h8 is 63. The planes are white P, N, B, R, Q, K then black P, N, B, R, Q,
     * K.
     *
     * **The board must already be from the mover's side.** When black is to move the caller
     * mirrors it vertically and swaps the colours, and un-mirrors the move it picks. Passing
     * an unmirrored black position produces plausible nonsense rather than an error.
     *
     * Indices 0..4095 are `from * 64 + to`; 4096..4351 are
     * `4096 + fromFile * 32 + toFile * 4 + piece` for queen, rook, bishop, knight. Promotions
     * are always rank 7 to rank 8, because the board is mirrored for black.
     */
    fun logits(planes: FloatArray, selfElo: Int, oppoElo: Int): FloatArray? {
        if (handle == 0L) return null
        if (planes.size != PLANE_COUNT * SQUARES) return null
        return MlNative.maiaLogits(handle, planes, selfElo, oppoElo)
    }

    /** Free the network and close the weights file. Idempotent. */
    override fun close() {
        val live = handle
        handle = 0L
        if (live != 0L) MlNative.destroyMaia(live)
    }

    override fun toString(): String = "Maia3-5M from $source"

    /** Board/move dimensions, model constants and the asset factory for [MaiaHandle]. */
    companion object {
        private const val TAG = "MaiaHandle"

        /** Board squares, and so the model's sequence length. One token per square. */
        const val SQUARES = 64

        /** Board planes: six piece types for each colour. */
        const val PLANE_COUNT = 12

        /** Entries in the move vocabulary: 64x64 from-to pairs plus 8x8x4 promotions. */
        const val MOVES = 4352

        /** The highest rating the model interpolates to. Higher inputs clamp to it. */
        const val MAX_ELO = 5000

        /** The one graph. Native checks its graph id, so a wrong file fails at load. */
        const val GRAPH = "maia3-5m.maml"

        /**
         * The model from the APK's assets, which is the only place it lives.
         *
         * No `inDirectory` counterpart: at 6.8 MiB this is bundled, so there is no download
         * directory to look in.
         */
        fun inAssets(assets: AssetManager, path: String = GRAPH): MaiaHandle {
            val instance = MaiaHandle("the APK's $path")
            instance.handle = if (!MlNative.isAvailable) {
                0L
            } else {
                try {
                    create(assets, path)
                } catch (expected: Exception) {
                    Log.error(TAG, "cannot open $path", expected)
                    0L
                }
            }
            return instance
        }

        /**
         * Open the asset and hand the descriptor over.
         *
         * `use` rather than a bare `close`, and it is load-bearing in both directions — the
         * same argument [ClipHandle]'s asset path makes. On the happy path the descriptor has
         * already been detached and `AssetFileDescriptor.close` only releases the wrapper; if
         * `detachFd` throws instead, the close is the real one, and a leaked descriptor onto
         * the APK would last the life of the process.
         *
         * The inner `finally` covers the remaining window: after the descriptor has been given
         * up but before native has adopted it.
         */
        private fun create(assets: AssetManager, path: String): Long =
            assets.openFd(path).use { afd ->
                val fd = afd.parcelFileDescriptor.detachFd()
                var handed = false
                try {
                    val handle = MlNative.createMaia(fd, afd.startOffset, afd.length)
                    handed = true
                    handle
                } finally {
                    if (!handed) closeFd(fd)
                }
            }

        /**
         * Close a bare descriptor.
         *
         * Adopting it into a [ParcelFileDescriptor] is the only way to reach `close(2)` from
         * Kotlin. Failures are swallowed because the caller is already on an error path.
         */
        private fun closeFd(fd: Int) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
        }
    }
}
