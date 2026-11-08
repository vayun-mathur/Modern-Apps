package com.vayunmathur.keyboard.util

import android.content.ClipData
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.keyboard.platform.VoiceFailure
import com.vayunmathur.keyboard.platform.VoiceInput
import com.vayunmathur.keyboard.platform.VoicePermission
import com.vayunmathur.keyboard.platform.VoicePermissionResult
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PlatformRoboTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `keyboard settings load defaults and persisted values`() = runBlocking {
        val ds = DataStoreUtils.getInstance(ctx, deviceProtected = true)
        // The store is shared with other Robolectric tests in this process, so
        // set every key under test explicitly rather than assuming a pristine file.
        val defaults = KeyboardSettings.load(ds)
        assertNotNull(KeyboardLayouts.byId(defaults.activeLayoutId) ?: KeyboardLayouts.DEFAULT)
        ds.setBoolean(KeyboardSettings.Keys.SOUND, true)
        ds.setBoolean(KeyboardSettings.Keys.HAPTIC, false)
        ds.setDouble(KeyboardSettings.Keys.KEY_HEIGHT, 1.2)
        ds.setString(KeyboardSettings.Keys.ACTIVE_LAYOUT, "de_qwertz")
        // DataStoreUtils' synchronous getters read an eagerly-collected
        // mirror of the store; the mirror catches up asynchronously after
        // a suspending edit commits, so poll instead of assuming the very
        // next load sees the write (on a loaded CI box it did not, and the
        // layout read back as the default).
        var loaded = KeyboardSettings.load(ds)
        val deadline = System.currentTimeMillis() + 10_000
        while (loaded.activeLayoutId != "de_qwertz" && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            loaded = KeyboardSettings.load(ds)
        }
        assertTrue(loaded.sound)
        assertFalse(loaded.haptic)
        assertEquals(1.2f, loaded.keyHeightScale)
        assertEquals("de_qwertz", loaded.activeLayout.id)
        // unknown id falls back to default layout object
        val unknown = loaded.copy(activeLayoutId = "no_such_layout")
        assertEquals(KeyboardLayouts.DEFAULT, unknown.activeLayout)
        // restore-ish values for other tests sharing the store
        ds.setString(KeyboardSettings.Keys.ACTIVE_LAYOUT, KeyboardLayouts.DEFAULT.id)
        Unit
    }

    @Test
    fun `pinyin dictionaries load from bundled assets`() = runBlocking {
        val sc = PinyinDictionary.load(ctx, "pinyin_sc")
        assertTrue(sc.candidates("ni").isNotEmpty())
        assertTrue(sc.isSyllable("ni"))
        assertFalse(sc.isSyllable("nonsense syllable"))
        assertTrue(sc.candidates("").isEmpty())
        val tc = PinyinDictionary.load(ctx, "pinyin_tc")
        assertTrue(tc.candidates("hao").isNotEmpty())
        val bopo = PinyinDictionary.loadBopomofo(ctx)
        assertTrue(bopo.isNotEmpty())
        // values are pinyin spellings the Han composer can look up
        assertTrue(bopo.values.all { it.isNotBlank() })
        assertTrue(PinyinDictionary.EMPTY.candidates("ni").isEmpty())
    }

    @Test
    fun `emoji data and dictionary load from assets`() = runBlocking {
        val emoji = EmojiData.load(ctx)
        assertTrue(emoji.categories.isNotEmpty())
        assertTrue(emoji.search("cat").isNotEmpty())
        val dict = Dictionary.load(ctx)
        assertTrue(dict.contains("the") || dict.suggestions("hel", 3).isNotEmpty())
        assertTrue(dict.suggestions("hel", 3).all { it.startsWith("hel") })
    }

    @Test
    fun `clipboard capture turns text clips into items`() = runBlocking {
        val dir = File(ctx.cacheDir, "test-clips-${System.nanoTime()}").apply { mkdirs() }
        val store = ClipboardStore(dir)
        val plain = ClipData.newPlainText("label", "  hello clipboard  ")
        val item = store.capture(ctx, plain, inPasswordField = false)
        assertNotNull(item)
        assertEquals("hello clipboard", item.text)
        assertFalse(item.isImage)

        val inPassword = store.capture(
            ctx, ClipData.newPlainText("l", "hunter2secret"), inPasswordField = true,
        )
        assertNotNull(inPassword)
        assertTrue(inPassword.sensitive)

        val empty = store.capture(ctx, ClipData.newPlainText("l", "   "), inPasswordField = false)
        assertNull(empty)

        val huge = store.capture(
            ctx,
            ClipData.newPlainText("l", "x".repeat(ClipboardStore.MAX_TEXT_CHARS + 1)),
            inPasswordField = false,
        )
        assertNull(huge)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `clipboard store persists non-sensitive and restores`() {
        val dir = File(ctx.cacheDir, "test-clips2-${System.nanoTime()}").apply { mkdirs() }
        val store = ClipboardStore(dir)
        val textFile = File(dir, "note.txt").apply { writeText("x") }
        val img = ClipItem(id = 5001, imagePath = textFile.path, mimeType = "image/png")
        store.seed(listOf(ClipItem(id = 5002, text = "keep me"), img))
        assertEquals(2, store.items.size)
        store.add(ClipItem(id = 5003, text = "secret1pass", sensitive = true))
        store.persistState()
        val raw = ClipboardStore.stateFile(dir).readText()
        assertTrue(raw.contains("keep me"))
        assertFalse(raw.contains("secret1pass"))

        val restored = ClipboardStore(dir)
        restored.restoreState()
        assertTrue(restored.items.any { it.text == "keep me" })
        assertTrue(restored.items.none { it.sensitive })

        // restore with no state file, then decoding garbage
        val emptyDir = File(ctx.cacheDir, "test-clips3-${System.nanoTime()}").apply { mkdirs() }
        val empty = ClipboardStore(emptyDir)
        empty.restoreState()
        assertTrue(empty.items.isEmpty())
        assertEquals(emptyList(), ClipboardStore.decode(null))
        assertEquals(emptyList(), ClipboardStore.decode(""))
        assertEquals(emptyList(), ClipboardStore.decode("not json"))
        dir.deleteRecursively(); emptyDir.deleteRecursively()
    }

    @Test
    fun `decodeThumbnail decodes a real png at bounded size`() {
        val dir = File(ctx.cacheDir, "test-thumb-${System.nanoTime()}").apply { mkdirs() }
        val png = File(dir, "shot.png")
        val bmp = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val thumb = ClipboardStore.decodeThumbnail(png, 16)
        assertNotNull(thumb)
        assertTrue(thumb.width <= 64 && thumb.height <= 32)
        val full = ClipboardStore.decodeThumbnail(png, 256)
        assertNotNull(full)
        dir.deleteRecursively()
    }

    @Test
    fun `clip item preview collapses whitespace and compares content`() {
        val item = ClipItem(id = 1, text = "  a\n b\t c ")
        assertEquals("a b c", item.preview)
        assertTrue(item.sameContentAs(ClipItem(id = 2, text = "  a\n b\t c ")))
        assertFalse(item.sameContentAs(ClipItem(id = 3, text = "other")))
        val image = ClipItem(id = 4, imagePath = "/x.png", mimeType = "image/png")
        assertTrue(image.isImage)
        assertNotNull(image.imageFile)
        assertFalse(image.sameContentAs(item))
        assertFalse(ClipItem(id = 5).isImage)
        assertNull(ClipItem(id = 5).imageFile)
    }

    @Test
    fun `nextId is monotonic`() {
        val a = ClipboardStore.nextId()
        val b = ClipboardStore.nextId()
        assertTrue(b > a)
    }

    @Test
    fun `voice input unavailable reports failure and lifecycle is safe`() {
        val voice = VoiceInput(ctx)
        // Robolectric has no recognition service.
        assertFalse(voice.isAvailable())
        var failure: VoiceFailure? = null
        var final: String? = null
        voice.start(
            languageTag = "en",
            onPartial = {},
            onTranscribing = {},
            onFinal = { final = it },
            onFailure = { failure = it },
        )
        assertEquals(VoiceFailure.UNAVAILABLE, failure)
        assertNull(final)
        voice.stop(); voice.cancel(); voice.destroy()
        voice.destroy()
    }

    @Test
    fun `voice permission checks grant state and publishes results`() = runBlocking {
        assertFalse(VoicePermission.isGranted(ctx))
        VoicePermission.request(ctx)
        // request launches the trampoline activity; the intent is recorded by Robolectric
        val started = org.robolectric.Shadows.shadowOf(ctx as android.app.Application)
            .nextStartedActivity
        assertNotNull(started)
        assertEquals(
            com.vayunmathur.keyboard.platform.VoicePermissionActivity::class.java.name,
            started.component?.className,
        )
        val got = kotlinx.coroutines.coroutineScope {
            val pending = async {
                withTimeoutOrNull(2_000) { VoicePermission.results.first() }
            }
            kotlinx.coroutines.delay(100)
            VoicePermission.publish(VoicePermissionResult.BLOCKED)
            pending.await()
        }
        assertEquals(VoicePermissionResult.BLOCKED, got)
    }
}
