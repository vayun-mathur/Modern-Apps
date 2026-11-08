package com.vayunmathur.music.service.car

import android.content.Context
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
import kotlin.test.assertTrue

/**
 * Car browse/playback screens over the empty library against a
 * TestCarContext: every browse level must produce a valid template (an
 * Android Auto host rejects a screen that throws), across the template
 * fallbacks for older host API levels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicCarScreensTest {

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun carContext(apiLevel: Int): TestCarContext {
        val carContext = TestCarContext.createCarContext(context)
        // TestCarContext never establishes a host API level; set the field
        // the host binder would normally fill so screens can pick a template
        // (same pattern as the weather/maps car tests).
        val field = androidx.car.app.CarContext::class.java
            .getDeclaredField("mCarAppApiLevel")
        field.isAccessible = true
        field.setInt(carContext, apiLevel)
        return carContext
    }

    private fun state(): MusicCarState {
        val tree = MusicLibraryTree(context)
        return MusicCarState(tree)
    }

    private fun assertTemplate(template: Template, vararg allowed: String) {
        val name = template.javaClass.simpleName
        assertTrue(
            allowed.isEmpty() || name in allowed,
            "template $name not in ${allowed.toList()}",
        )
    }

    @Test
    fun `browse root produces a template at every host api level`() {
        val carState = state()
        // Level 9 takes the tab template, 6-8 the sectioned/list fallbacks,
        // and below 6 the root must still render as a plain list.
        // With an empty tree the tab template builder may decline, in which
        // case the screen's own fallback to sectioned content is the contract.
        assertTemplate(
            MusicCarBrowseScreen(carContext(9), MusicLibraryTree.ROOT, carState).onGetTemplate(),
            "TabTemplate", "SectionedItemTemplate",
        )
        assertTemplate(
            MusicCarBrowseScreen(carContext(7), MusicLibraryTree.ROOT, carState).onGetTemplate(),
            "SectionedItemTemplate", "ListTemplate",
        )
        assertTemplate(
            MusicCarBrowseScreen(carContext(1), MusicLibraryTree.ROOT, carState).onGetTemplate(),
            "ListTemplate", "SectionedItemTemplate",
        )
    }

    @Test
    fun `browse tabs and unknown nodes produce templates`() {
        val carContext = carContext(8)
        val carState = state()
        for (node in listOf(
            MusicLibraryTree.TAB_SONGS,
            MusicLibraryTree.TAB_ALBUMS,
            MusicLibraryTree.TAB_ARTISTS,
            MusicLibraryTree.TAB_PLAYLISTS,
            MusicLibraryTree.TAB_RECENT,
            "album_99",
            "playlist_99",
            "totally-unknown",
        )) {
            val screen = MusicCarBrowseScreen(carContext, node, carState)
            assertTemplate(
                screen.onGetTemplate(),
                "SectionedItemTemplate", "ListTemplate", "TabTemplate",
            )
        }
    }

    @Test
    fun `playback screen produces a template without a controller`() {
        val carState = state()
        assertTemplate(
            MusicCarPlaybackScreen(carContext(9), carState).onGetTemplate(),
            "MediaPlaybackTemplate",
        )
        // Pre-API-8 hosts get the pane fallback with transport actions.
        assertTemplate(
            MusicCarPlaybackScreen(carContext(1), carState).onGetTemplate(),
            "PaneTemplate",
        )
    }

    @Test
    fun `car state notifies observers and tolerates throwing ones`() {
        val carState = state()
        var calls = 0
        carState.observe { calls++ }
        carState.observe { throw RuntimeException("observer boom") }
        carState.notifyChanged()
        assertTrue(calls == 1, "observer must be called despite a throwing sibling")
        assertTrue(carState.controller == null)
    }
}
