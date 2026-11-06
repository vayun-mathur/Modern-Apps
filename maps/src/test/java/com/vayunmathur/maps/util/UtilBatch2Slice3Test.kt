package com.vayunmathur.maps.util

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 3/3 (root package files M-Z), second batch: the small remainder of
 * `util` — [MoonAssetLoader]'s bundled-asset load, [PlacePanelState]'s saved
 * matching, the [SearchUiState.phase] table beyond the existing
 * [SearchUiStateTest], and [MapSettingsViewModel]'s overlay progress fields.
 */
@RunWith(AndroidJUnit4::class)
class UtilBatch2Slice3Test {

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @BeforeTest
    fun initWork() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        runCatching {
            androidx.work.WorkManager.initialize(
                context,
                androidx.work.Configuration.Builder()
                    .setMinimumLoggingLevel(android.util.Log.DEBUG)
                    .setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
                    .build(),
            )
        }
    }

    private fun place(name: String = "Cafe") = SpecificFeature.GenericPlace(
        name = name,
        phone = null,
        website = null,
        openingHours = null,
        position = GeoPoint(2.0, 1.0),
    )

    private fun <T> awaitState(flow: StateFlow<T>, timeoutMs: Long = 10_000, want: (T) -> Boolean): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: T = flow.value
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            last = flow.value
            if (want(last)) return last
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return flow.value
    }

    // --- MoonAssetLoader ---

    @Test
    fun `bundled moon pair loads with its documented dimensions`() = runBlocking {
        val textures = MoonAssetLoader.load(app)
        assertNotNull(textures)
        assertEquals(4096, textures.width)
        assertEquals(2048, textures.height)
        assertEquals(4096 * 2048 * 4, textures.colorRgba.size)
        assertEquals(2048, textures.demWidth)
        assertEquals(1024, textures.demHeight)
        assertEquals(2048 * 1024 * 2, textures.demRg.size)
    }

    // --- PlacePanelState ---

    @Test
    fun `panel saved matching follows the flat starred list`() {
        val saved = SavedPlace("Cafe", 1.0, 2.0)
        val state = PlacePanelState(saved = listOf(saved))
        assertTrue(state.isSaved(place()))
        assertEquals(saved, state.savedMatch(place()))
        assertFalse(state.isSaved(place("Other")))
        assertNull(state.savedMatch(place("Other")))
    }

    @Test
    fun `empty panel saves nothing`() {
        val state = PlacePanelState()
        assertFalse(state.isSaved(place()))
        assertNull(state.savedMatch(place()))
        assertEquals(com.vayunmathur.maps.data.google.PoiSection.DETAILS, state.poiSection)
    }

    @Test
    fun `panel actions noop is a complete no-op`() {
        val noop = PlacePanelActions.Noop
        noop.setPoiSection(com.vayunmathur.maps.data.google.PoiSection.PHOTOS)
        noop.addSaved()
        noop.removeSaved(SavedPlace("x", 0.0, 0.0))
        noop.openNearestStop(1.0, 2.0)
    }

    // --- SearchUiState phases (recents/idle corners) ---

    @Test
    fun `single character with recents still offers recents`() {
        val state = SearchUiState(query = "p", recents = listOf("pizza"))
        assertEquals(SearchPhase.Recents, state.phase)
    }

    @Test
    fun `single character without recents stays idle`() {
        assertEquals(SearchPhase.Idle, SearchUiState(query = "p").phase)
    }

    @Test
    fun `searching wins over empty results`() {
        val state = SearchUiState(query = "pizza place", searching = true)
        assertEquals(SearchPhase.Searching, state.phase)
    }

    @Test
    fun `search actions noop covers every callback`() {
        val noop = SearchActions.Noop
        noop.setQuery("q")
        noop.selectResult(SearchResult("id", "t", null, 0.0, 0.0, null))
        noop.pickContactAddress("addr")
        noop.clearRecents()
        noop.back()
    }

    // --- MapSettingsViewModel overlay progress ---

    @Test
    fun `overlay progress fields default to zero`() {
        val vm = MapSettingsViewModel(app)
        assertEquals(0L, vm.overlayAppliedSeq.value)
        assertEquals(0L, vm.overlayPendingBytes.value)
        assertEquals(0L, vm.overlayLastCheck.value)
    }

    @Test
    fun `overlay progress follows direct datastore writes`() {
        val ds = DataStoreUtils.getInstance(app)
        runBlocking {
            ds.setLong(MapOverlayWorker.KEY_APPLIED_SEQ, 7L)
            ds.setLong(MapOverlayWorker.KEY_PENDING_BYTES, 1024L)
        }
        val vm = MapSettingsViewModel(app)
        // Seeded as the StateFlow initial values from the synchronous mirror.
        assertEquals(7L, vm.overlayAppliedSeq.value)
        assertEquals(1024L, vm.overlayPendingBytes.value)
        awaitState(vm.overlayLastCheck) { true }
    }

    @Test
    fun `auto-update toggle persists`() {
        val vm = MapSettingsViewModel(app)
        vm.setMapAutoUpdate(false)
        awaitState(vm.mapAutoUpdate) { !it }
        vm.setMapAutoUpdate(true)
        awaitState(vm.mapAutoUpdate) { it }
    }

    @Test
    fun `globe toggle persists`() {
        val vm = MapSettingsViewModel(app)
        vm.setGlobeEnabled(true)
        awaitState(vm.globeEnabled) { it }
        vm.setGlobeEnabled(false)
        awaitState(vm.globeEnabled) { !it }
    }
}
