package com.vayunmathur.library.ml

import android.os.ParcelFileDescriptor
import com.vayunmathur.library.log.Log
import java.io.File

/**
 * The baked prefix-cache half of [Gemma4Handle] (plus the one-shot benchmark),
 * as `internal` extensions so Gemma4Handle.kt stays under the FileLength limit.
 * The public entry points stay on the handle and delegate here.
 */

/**
 * Time the model once, so the log says where its time goes.
 *
 * Runs three passes each way and keeps the best, which costs a couple of seconds the first
 * time a conversation starts and answers a question that guesswork has repeatedly got wrong.
 */
internal fun Gemma4Handle.runBenchmark() {
    if (handle != 0L) {
        backend.capabilities()
        backend.imageProbe()
        backend.benchmark(handle)
    }
}

/**
 * Load the baked cache for [prefix], if it is present and matches. Returns whether it did.
 *
 * # The check is the point
 *
 * The asset encodes keys and values for one exact token sequence. If the system prompt or
 * the tool set has changed since it was baked, those numbers describe a prompt the model is
 * not being given - and nothing downstream could tell, because a KV cache is only attended
 * over, never compared. So this re-encodes [prefix] here and refuses unless the digest of
 * the result matches the one in the file.
 *
 * A mismatch is not an error: it falls back to prefilling, which is slow and correct.
 */
internal fun Gemma4Handle.loadBakedPrefix(prefix: String): Boolean {
    val tokens = readPrefixTokens(prefix)
    val blob = readPrefixBlob()
    if (tokens == null || blob == null) return false
    return installValidatedPrefix(blob, tokens)
}

/** Validates the cached blob against [tokens] and installs it, or false (with logs) on mismatch. */
private fun Gemma4Handle.installValidatedPrefix(blob: ByteArray, tokens: IntArray): Boolean {
    if (!isBakedCache(blob)) return false
    val positions = Gemma4Handle.readInt(blob, PREFIX_POSITIONS_OFFSET)
    if (!positionsMatch(tokens, positions)) return false
    if (!digestMatches(tokens, blob)) return false
    if (!ensurePrefixCapacity(positions)) return false
    return installPrefix(blob, tokens, positions)
}

private fun Gemma4Handle.readPrefixTokens(prefix: String): IntArray? {
    if (handle == 0L) return null
    val tokens = encodePrompt(prefix)
    if (tokens.isEmpty()) return null
    return tokens
}

private fun Gemma4Handle.readPrefixBlob(): ByteArray? {
    val file = java.io.File(directory, Gemma4Handle.PREFIX_CACHE)
    if (!file.isFile) return null
    return runCatching { file.readBytes() }.getOrNull()
}

private fun isBakedCache(blob: ByteArray): Boolean {
    if (blob.size < Gemma4Handle.HEADER ||
        String(blob, 0, PREFIX_MAGIC_BYTES, Charsets.US_ASCII) != PREFIX_MAGIC
    ) {
        Log.status(Gemma4Handle.TAG, "${Gemma4Handle.PREFIX_CACHE} is not a baked cache")
        return false
    }
    return true
}

private fun Gemma4Handle.positionsMatch(tokens: IntArray, positions: Int): Boolean {
    // The size check stays enforced even though the digest does not: `positions` decides
    // where the next token goes and how many ids are recorded as cached, so a wrong count
    // corrupts the bookkeeping rather than merely the contents.
    if (positions != tokens.size) {
        Log.status(
            Gemma4Handle.TAG,
            "${Gemma4Handle.PREFIX_CACHE} is $positions positions, this prefix is ${tokens.size}"
        )
        return false
    }
    return true
}

private fun Gemma4Handle.digestMatches(tokens: IntArray, blob: ByteArray): Boolean {
    // The digest IS enforced: it says whether these keys and values were computed from
    // *these* tokens, and a mismatch means the model is about to attend over a prompt it was
    // not given. That failure is silent - a fluent reply to a conversation that never happened -
    // so a stale cache falls back to prefilling: slow and correct. The loud log stays either
    // way, because a mismatch means the baked asset and the declared prompt have drifted apart
    // and someone needs to re-run `bake_gemma4_prefix`.
    val digest = blob.copyOfRange(PREFIX_DIGEST_OFFSET, PREFIX_DIGEST_OFFSET + PREFIX_DIGEST_BYTES)
    if (!Gemma4Handle.digest(tokens).contentEquals(digest)) {
        Log.status(Gemma4Handle.TAG, "${Gemma4Handle.PREFIX_CACHE} DIGEST MISMATCH - prefilling instead")
        return false
    }
    return true
}

private fun Gemma4Handle.ensurePrefixCapacity(positions: Int): Boolean {
    // The cache starts at the smallest tier and this prefix is larger than it. Growing first
    // is not optional: `loadPrefixGemma4` refuses a prefix bigger than the cache rather than
    // truncating it, because half a prefix is keys for a prompt nobody sent.
    if (backend.capacity(handle) < positions) {
        val grown = backend.grow(handle, positions + PREFIX_SLACK_POSITIONS)
        if (grown < positions) {
            Log.status(
                Gemma4Handle.TAG,
                "a $positions-position prefix does not fit this device; prefilling"
            )
            return false
        }
    }
    return true
}

private fun Gemma4Handle.installPrefix(
    blob: ByteArray,
    tokens: IntArray,
    positions: Int
): Boolean {
    val payload = blob.copyOfRange(Gemma4Handle.HEADER, blob.size)
    val loaded = backend.loadPrefix(handle, positions, payload)
    if (loaded != positions) return false
    // The cache now holds exactly these tokens, so the next turn matches against them and
    // feeds only what follows.
    cachedIds = tokens
    Log.status(Gemma4Handle.TAG, "loaded a $positions-position prefix cache, skipping its prefill")
    return true
}

private const val PREFIX_MAGIC = "GKV1"
private const val PREFIX_MAGIC_BYTES = 4
private const val PREFIX_POSITIONS_OFFSET = 4
private const val PREFIX_DIGEST_OFFSET = 12
private const val PREFIX_DIGEST_BYTES = 32
private const val PREFIX_SLACK_POSITIONS = 2

/**
 * Open both graphs, read the tokenizer, and hand the descriptors over.
 *
 * Two descriptors rather than one, so the `finally` dance is doubled. Native adopts both
 * and closes both on every path including failure, so [handed] guards the window between
 * detaching and native taking ownership - and both must be closed here if the call is
 * never made.
 */
internal fun createGemma4Handle(directory: File): Long {
    val text = File(directory, Gemma4Handle.TEXT)
    val embed = File(directory, Gemma4Handle.EMBED)
    val tokenizer = File(directory, Gemma4Handle.TOKENIZER)
    for (file in listOf(text, embed, tokenizer)) {
        if (!file.isFile) {
            Log.status(Gemma4Handle.TAG, "${file.name} is missing from $directory")
            return 0L
        }
    }
    val short = shortFiles(directory)
    if (short.isNotEmpty()) {
        // Fail before native opens anything: a truncated file maps fine and dies
        // pages-deep in the upload, which used to surface as the same bare
        // "failed to load" as a missing GPU feature (#901).
        Log.status(Gemma4Handle.TAG, "short files in $directory: ${short.joinToString()}")
        return 0L
    }
    // Optional GPU head (third file). Absent on old downloads — native falls
    // back to the host head, so this stays warning-free by design.
    val head = File(directory, Gemma4Handle.HEAD)
    val headLease = openHeadLease(head) ?: return 0L
    val table = runCatching { tokenizer.readBytes() }.getOrElse {
        headLease.close()
        Log.status(Gemma4Handle.TAG, "cannot read ${Gemma4Handle.TOKENIZER}: $it")
        return 0L
    }
    val textFd = openModelFd(text, Gemma4Handle.TEXT, onFailure = { headLease.close() })
        ?: return 0L
    val embedFd = openModelFd(embed, Gemma4Handle.EMBED, onFailure = {
        closeGemma4Fd(textFd)
        headLease.close()
    }) ?: return 0L
    var handed = false
    try {
        val live = MlNative.createGemma4(
            textFd, 0L, text.length(),
            embedFd, 0L, embed.length(),
            headLease.fd, 0L, headLease.length,
            table,
            gemma4CacheBudget(),
        )
        handed = true
        return live
    } finally {
        if (!handed) {
            closeGemma4Fd(textFd)
            closeGemma4Fd(embedFd)
            headLease.close()
        }
    }
}

/** Open file descriptor lease for the optional GPU head, or null (with a log) on failure. */
private class HeadLease(val fd: Int, val length: Long) : AutoCloseable {
    override fun close() {
        if (fd >= 0) closeGemma4Fd(fd)
    }
}

/**
 * `name to "actual/expected bytes"` for every gemma4 file on disk that is
 * shorter than the mirror serves.
 *
 * Sizes come from the download manifest ([ModelUrls.GemmaMaml.FILES]) rather
 * than native's header because the check runs before any descriptor is
 * opened — and because a maml header describes the data section, not the file,
 * so it cannot catch a tail truncation on its own. [ModelUrls] lives in
 * `:library:downloadservice`, which `:library:ml` must not depend on (consumer
 * direction only), hence the literal table: keep it in sync with the manifest.
 *
 * Public so the hosting app can name the broken file in its load error (#901)
 * instead of surfacing one undifferentiated "failed to load".
 */
fun shortFiles(directory: File): List<String> =
    GEMMA4_EXPECTED_BYTES.mapNotNull { (name, expected) ->
        val file = File(directory, name)
        if (file.isFile && file.length() < expected) "$name ${file.length()}/$expected" else null
    }

/** Expected on-disk bytes per gemma4 file; see [shortFiles] for why it is literal. */
val GEMMA4_EXPECTED_BYTES: Map<String, Long> = mapOf(
    Gemma4Handle.TEXT to 1_101_593_392L,
    Gemma4Handle.EMBED to 1_566_576_256L,
    Gemma4Handle.HEAD to 403_703_360L,
    Gemma4VisionHandle.VISION to 97_815_200L,
    Gemma4AudioHandle.AUDIO to 173_999_072L,
    Gemma4Handle.TOKENIZER to 3_889_167L,
)

private fun openHeadLease(head: File): HeadLease? {
    if (!head.isFile) return HeadLease(NO_FD, 0L)
    val fd = runCatching {
        ParcelFileDescriptor.open(head, ParcelFileDescriptor.MODE_READ_ONLY)
            .use { it.detachFd() }
    }.getOrElse {
        Log.status(Gemma4Handle.TAG, "cannot open ${Gemma4Handle.HEAD}: $it")
        return null
    }
    return HeadLease(fd, head.length())
}

private const val NO_FD = -1

/** Opens a model file descriptor, running [onFailure] and returning null (with a log) on error. */
private fun openModelFd(file: File, name: String, onFailure: () -> Unit): Int? {
    return runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            .use { it.detachFd() }
    }.getOrElse {
        onFailure()
        Log.status(Gemma4Handle.TAG, "cannot open $name: $it")
        null
    }
}

/**
 * Close a bare descriptor.
 *
 * Adopting it into a [ParcelFileDescriptor] is the only way to reach `close(2)` from
 * Kotlin. Failures are swallowed because the caller is already on an error path.
 */
private fun closeGemma4Fd(fd: Int) {
    runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
}

/**
 * Bytes this device will spend on the KV cache.
 *
 * # Why it is a fraction of *total* rather than available memory
 *
 * Available memory is whatever the rest of the system happens to be doing when the
 * assistant starts, so sizing against it makes the conversation length depend on what
 * else was open - and shrinks it exactly when the user has been busy. Total memory is a
 * property of the device, which is what the tier should track.
 *
 * A twentieth is deliberately conservative. The weights are already ~2.9 GB of mapped
 * file and the tower allocations sit on top, so the cache is not the only claim on the
 * budget - and an allocation failure here is a model that will not start at all.
 *
 *   4 GB  ->  200 MB  ->  8,192 positions
 *   8 GB  ->  400 MB  -> 16,384 positions
 *  16 GB  ->  800 MB  -> 16,384 positions, the top tier
 */
private fun gemma4CacheBudget(): Long {
    // `/proc/meminfo` rather than `ActivityManager`, because this class is constructed
    // from a directory and has no `Context` - and adding one to the signature to read a
    // single number would push Android into an API that is otherwise platform-free.
    val total = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemTotal:") }
                ?.filter(Char::isDigit)
                ?.toLongOrNull()
                ?.times(BYTES_PER_KB) ?: 0L
        }
    }.getOrDefault(0L)
    // A device that will not say gets the smallest tier, which always fits, rather than
    // an optimistic guess that fails to allocate and leaves the assistant dead.
    if (total <= 0L) return 0L
    return total / CACHE_FRACTION
}

private const val CACHE_FRACTION = 20
private const val BYTES_PER_KB = 1024
