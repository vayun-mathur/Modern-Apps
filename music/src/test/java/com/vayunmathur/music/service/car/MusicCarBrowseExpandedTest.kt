package com.vayunmathur.music.service.car

import android.content.Context
import androidx.car.app.CarContext
import androidx.car.app.model.Template
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.music.FakeAndroidKeyStore
import com.vayunmathur.music.service.MusicLibraryTree
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MusicCarBrowseScreen / MusicCarPlaybackScreen template selection across the
 * full host-API matrix over an empty tree, complementing MusicCarScreensTest:
 * every (node, apiLevel) pair must yield a host-legal template — root tabs at
 * 6/7 use list content, at 8+ sectioned; non-root nodes never use TabTemplate.
 * Populated rows (song rows, album grid tiles, play-all) need a populated
 * tree, which needs Room, so they remain device-only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicCarBrowseExpandedTest {

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun carContext(apiLevel: Int): TestCarContext {
        val carContext = TestCarContext.createCarContext(context)
        val field = CarContext::class.java.getDeclaredField("mCarAppApiLevel")
        field.isAccessible = true
        field.setInt(carContext, apiLevel)
        return carContext
    }

    private fun state(): MusicCarState = MusicCarState(MusicLibraryTree(context))

    private fun templateName(template: Template): String = template.javaClass.simpleName

    @Test
    fun rootTemplateFollowsTheHostApiLadder() {
        val carState = state()
        // API 1-5: plain list. 6-7: tabs over list content (tab builder may
        // decline on an empty tree, falling back to the screen's own list).
        for (level in listOf(1, 3, 5)) {
            assertEquals(
                "ListTemplate",
                templateName(MusicCarBrowseScreen(carContext(level), MusicLibraryTree.ROOT, carState).onGetTemplate()),
                "api $level",
            )
        }
        for (level in listOf(6, 7)) {
            assertTrue(
                templateName(MusicCarBrowseScreen(carContext(level), MusicLibraryTree.ROOT, carState).onGetTemplate())
                    in listOf("TabTemplate", "ListTemplate", "SectionedItemTemplate"),
                "api $level",
            )
        }
        for (level in listOf(8, 9, 10)) {
            assertTrue(
                templateName(MusicCarBrowseScreen(carContext(level), MusicLibraryTree.ROOT, carState).onGetTemplate())
                    in listOf("TabTemplate", "SectionedItemTemplate"),
                "api $level",
            )
        }
    }

    @Test
    fun everyBrowseNodeProducesALegalTemplateAtLowAndHighApi() {
        val carState = state()
        val nodes = listOf(
            MusicLibraryTree.TAB_PLAYLISTS,
            MusicLibraryTree.TAB_ALBUMS,
            MusicLibraryTree.TAB_ARTISTS,
            MusicLibraryTree.TAB_SONGS,
            MusicLibraryTree.TAB_RECENT,
            "album_1", "artist_1", "playlist_1", "album_x", "unknown_node",
        )
        for (node in nodes) {
            val legacy = MusicCarBrowseScreen(carContext(1), node, carState).onGetTemplate()
            assertEquals("ListTemplate", templateName(legacy), "$node @ api1")
            val modern = MusicCarBrowseScreen(carContext(9), node, carState).onGetTemplate()
            assertTrue(
                templateName(modern) in listOf("SectionedItemTemplate", "ListTemplate"),
                "$node @ api9 was ${templateName(modern)}",
            )
            val mid = MusicCarBrowseScreen(carContext(7), node, carState).onGetTemplate()
            assertEquals("ListTemplate", templateName(mid), "$node @ api7")
        }
    }

    @Test
    fun playbackTemplateFollowsTheHostApiLadder() {
        val carState = state()
        for (level in listOf(1, 5, 7)) {
            assertEquals(
                "PaneTemplate",
                templateName(MusicCarPlaybackScreen(carContext(level), carState).onGetTemplate()),
                "api $level",
            )
        }
        for (level in listOf(8, 9, 10)) {
            assertEquals(
                "MediaPlaybackTemplate",
                templateName(MusicCarPlaybackScreen(carContext(level), carState).onGetTemplate()),
                "api $level",
            )
        }
    }

    @Test
    fun carStateNotifiesEveryObserverOncePerChange() {
        val carState = state()
        var first = 0
        var second = 0
        carState.observe { first++ }
        carState.observe { second++ }
        carState.notifyChanged()
        carState.notifyChanged()
        assertEquals(2, first)
        assertEquals(2, second)
        assertEquals(null, carState.controller)
    }
}
