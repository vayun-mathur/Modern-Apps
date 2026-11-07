package com.vayunmathur.keyboard.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * One thing the user copied. [id] is unique and monotonic (see [nextId]), which is also
 * what orders the history and names the cached image file.
 *
 * A clip is either text or an image, never both: [imagePath] points at the keyboard's own
 * copy of the image bytes (see [ClipboardStore.capture]) and is null for text clips.
 */
@Serializable
data class ClipItem(
    val id: Long,
    val text: String = "",
    val imagePath: String? = null,
    val mimeType: String? = null,
    /** Blur this everywhere and never write it to disk. */
    val sensitive: Boolean = false,
) {
    val isImage: Boolean get() = imagePath != null

    val imageFile: File? get() = imagePath?.let(::File)

    /** Single-line form for the chip and the list rows. */
    val preview: String get() = text.replace(Regex("\\s+"), " ").trim()

    /** Same content, ignoring when it was captured — what de-duplication compares. */
    fun sameContentAs(other: ClipItem): Boolean = when {
        isImage || other.isImage -> false
        else -> text == other.text
    }
}

/**
 * The keyboard's clipboard history: the last [MAX_ITEMS] things copied, newest first.
 *
 * Images are copied into [imageDir] the moment they are captured rather than kept as a
 * `content://` reference. The URI grant an app hands the clipboard does not outlive that
 * app's clip, so a reference would paste fine for a minute and then silently fail — which
 * is exactly when a clipboard history is worth having.
 *
 * Sensitive clips (see [looksSensitive]) live in memory for the session only; [persistState]
 * writes through [encode], which leaves them out, so a password never reaches disk.
 * Sensitive images are refused at capture instead, so no bytes are written either.
 */
class ClipboardStore(private val imageDir: File) {

    var items: List<ClipItem> = emptyList()
        private set

    fun restoreState() {
        val file = stateFile()
        if (!file.exists()) {
            items = emptyList()
            return
        }
        items = decode(runCatching { file.readText() }.getOrNull())
            .filter { it.imageFile?.exists() != false }
    }

    /**
     * Write the history to [stateFile]; sensitive clips are excluded by [encode].
     * Must be called off the main thread.
     */
    fun persistState() {
        runCatching {
            imageDir.mkdirs()
            stateFile().writeText(encode(items))
        }
    }

    /** Replace the history wholesale (startup migration); trims to the caps like [add] does. */
    fun seed(history: List<ClipItem>) {
        items = cap(history.filter { it.imageFile?.exists() != false }, ::discardImage)
    }

    /** The history file inside [imageDir]. Cache is excluded from backup rules, so clips never leave the device. */
    fun stateFile(): File = stateFile(imageDir)

    /**
     * Record [item]. Returns it when it is genuinely new; re-copying something already in
     * the history just moves it back to the front and returns null, so the strip does not
     * pop up a chip for a clip the user is only re-using.
     */
    fun add(item: ClipItem): ClipItem? {
        val existing = items.firstOrNull { it.sameContentAs(item) }
        if (existing != null) {
            // Sensitivity only ever upgrades: the same text re-copied from a
            // password field is a credential now, whatever it was before, and
            // keeping the old entry would persist and display it as plain.
            val kept = if (item.sensitive && !existing.sensitive) {
                existing.copy(sensitive = true)
            } else {
                existing
            }
            items = listOf(kept) + (items - existing)
            return null
        }
        items = cap(listOf(item) + items, ::discardImage)
        return item
    }

    fun delete(item: ClipItem) {
        if (items.none { it.id == item.id }) return
        items = items.filterNot { it.id == item.id }
        discardImage(item)
    }

    fun clear() {
        items.forEach(::discardImage)
        items = emptyList()
    }

    /**
     * Turn the system's primary clip into a [ClipItem], copying image bytes into the cache
     * on the way. Returns null for a clip with nothing usable in it.
     */
    suspend fun capture(context: Context, clip: ClipData, inPasswordField: Boolean): ClipItem? {
        val entry = (if (clip.itemCount > 0) clip.getItemAt(0) else null) ?: return null
        val flagged = inPasswordField || clip.description.isMarkedSensitive()

        val uri = entry.uri
        val mime = uri?.let { context.contentResolver.getType(it) }
        if (uri != null && mime != null && mime.startsWith("image/")) {
            // Sensitive images are not recorded at all: bytes on disk would outlive
            // the session while the history entry (excluded from serialization)
            // does not, leaving orphaned credential-adjacent files.
            if (flagged) return null
            val id = nextId()
            val file = withContext(Dispatchers.IO) { copyImage(context, uri, id, mime) }
                ?: return null
            return ClipItem(
                id = id,
                imagePath = file.path,
                mimeType = mime,
                sensitive = false,
            )
        }

        val text = entry.coerceToText(context).toString().trim()
        // Beyond this a copy is an accident (select-all of a document), not a clip;
        // a multi-megabyte string does not belong in DataStore-shaped persistence.
        if (text.isEmpty() || text.length > MAX_TEXT_CHARS) return null
        return ClipItem(
            id = nextId(),
            text = text,
            mimeType = ClipDescription.MIMETYPE_TEXT_PLAIN,
            sensitive = flagged || looksSensitive(text),
        )
    }

    private fun copyImage(context: Context, uri: Uri, id: Long, mime: String): File? {
        val file = File(imageDir, "$id.${mime.substringAfterLast('/', "png")}")
        val ok = runCatching {
            imageDir.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        // Refuse rather than half-keep: a truncated image pastes as
                        // a corrupt file, which is worse than no history entry.
                        if (total > MAX_IMAGE_BYTES) return@runCatching false
                        out.write(buf, 0, n)
                    }
                    true
                }
            } == true
        }.getOrDefault(false)
        if (!ok) runCatching { file.delete() }
        return if (ok) file else null
    }

    private fun discardImage(item: ClipItem) {
        runCatching { item.imageFile?.delete() }
    }

    companion object {
        /** Roughly a day of copying; beyond this the history is an archive, not a clipboard. */
        const val MAX_ITEMS = 30

        /** Images are cached as real files, so far fewer of them are kept. */
        const val MAX_IMAGES = 10

        /** Longest single text clip kept; beyond this a copy is an accident, not a clip. */
        const val MAX_TEXT_CHARS = 50_000

        /** Largest single image cached; bigger copies are refused rather than half-kept. */
        const val MAX_IMAGE_BYTES = 10_000_000L

        /** Shortest digit run treated as a sensitive code (PIN/2FA length). */
        const val MIN_SENSITIVE_DIGITS = 4

        /** Longest digit run treated as a card number. */
        const val MAX_SENSITIVE_DIGITS = 24

        /** Shortest clip examined for mixed sensitive content. */
        const val MIN_CLIP_LENGTH = 6

        /** Longest clip examined for mixed sensitive content. */
        const val MAX_CLIP_LENGTH = 64

        /** History file inside a clips dir; cache is excluded from backup rules. */
        fun stateFile(clipsDir: File): File = File(clipsDir, "clips.json")

        /**
         * Unique, monotonic ids. Wall-clock millis plus a sequence, so two copies in
         * the same millisecond — or a backwards clock step — never share an id.
         * Ids are LazyColumn keys and image filenames, so a collision is a crash
         * plus one image overwriting the other.
         */
        private val lastClipId = AtomicLong(0)

        fun nextId(): Long {
            val now = System.currentTimeMillis() * 1_000L
            while (true) {
                val last = lastClipId.get()
                val next = maxOf(now, last + 1)
                if (lastClipId.compareAndSet(last, next)) return next
            }
        }

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Trim to the caps, newest first, handing every dropped clip to [onDropped] so its
         * cached image file goes with it.
         */
        fun cap(items: List<ClipItem>, onDropped: (ClipItem) -> Unit = {}): List<ClipItem> {
            val kept = ArrayList<ClipItem>(MAX_ITEMS)
            var images = 0
            for (item in items) {
                val room = kept.size < MAX_ITEMS && (!item.isImage || images < MAX_IMAGES)
                if (!room) {
                    onDropped(item)
                    continue
                }
                if (item.isImage) images++
                kept.add(item)
            }
            return kept
        }

        /**
         * A guess at whether [text] is a credential, used to blur clips that arrive without
         * the [ClipDescription.EXTRA_IS_SENSITIVE] flag the platform only added in API 33
         * (and that most apps still do not set).
         *
         * Password-shaped means: one unbroken token of 6–64 characters mixing letters with
         * digits or punctuation. URLs and email addresses fit that description too and are
         * copied far more often than passwords, so they are excluded by name. What is left
         * over-blurs occasionally, which costs one tap on the reveal toggle — the opposite
         * mistake shows someone's password on screen.
         */
        fun looksSensitive(text: String): Boolean {
            // PINs, 2FA codes and card numbers: short digit runs, often grouped with
            // spaces or dashes ("1234", "4111 1111 1111 1111"). Over-blurs an
            // occasional year or score, which costs one eye-tap; the opposite mistake
            // shows a credential in the clear.
            val digits = text.count { it.isDigit() }
            val rest = text.filterNot { it.isDigit() || it.isWhitespace() || it == '-' || it == '+' }
            if (rest.isEmpty() && digits in MIN_SENSITIVE_DIGITS..MAX_SENSITIVE_DIGITS) return true
            if (text.length !in MIN_CLIP_LENGTH..MAX_CLIP_LENGTH) return false
            if (text.any { it.isWhitespace() }) return false
            if (text.contains("://") || EMAIL.matches(text)) return false
            if (text.none { it.isLetter() }) return false
            return text.any { it.isDigit() } || text.any { !it.isLetterOrDigit() }
        }

        /** Sensitive clips are dropped here rather than at read time: they never hit disk. */
        fun encode(items: List<ClipItem>): String =
            json.encodeToString(items.filterNot { it.sensitive })

        fun decode(stored: String?): List<ClipItem> {
            if (stored.isNullOrBlank()) return emptyList()
            return runCatching { json.decodeFromString<List<ClipItem>>(stored) }
                .getOrDefault(emptyList())
        }

        private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

        /**
         * Decode [file] down to roughly [maxPx] on its longest side. There is no image
         * loader in this repo, so this is [BitmapFactory]'s own two-pass bounds-then-sample
         * decode — enough for a thumbnail, and it never holds the full-size bitmap.
         */
        fun decodeThumbnail(file: File, maxPx: Int): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null
            var sample = 1
            while (longest / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()

        private fun ClipDescription.isMarkedSensitive(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
    }
}
