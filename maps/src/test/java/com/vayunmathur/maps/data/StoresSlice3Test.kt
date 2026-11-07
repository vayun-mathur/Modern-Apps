package com.vayunmathur.maps.data

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 3/3 (root package files M-Z): the DataStore-backed persistence for
 * recents ([RecentSearchStore]), parking memory ([ParkingStore]) and starred
 * places ([SavedPlaceStore]).
 *
 * Each store is exercised through its real DataStore (fresh per test class —
 * the build forks one JVM per class): ordering, de-duplication, caps,
 * history archiving, and corrupt-payload tolerance. Flow reads use `first()`
 * on the store's cold flow so they observe the committed write rather than
 * the asynchronously-mirrored snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoresSlice3Test {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun recents() = RecentSearchStore.get(context)
    private fun parking() = ParkingStore(DataStoreUtils.getInstance(context))
    private fun saved() = SavedPlaceStore(DataStoreUtils.getInstance(context))

    @BeforeTest
    fun clearAll() {
        val ds = DataStoreUtils.getInstance(context)
        runBlocking {
            ds.setString("maps_recent_searches", "")
            ds.setString("parking_active_spot", "")
            ds.setString("parking_history", "[]")
            ds.setString("saved_places_list", "[]")
            ds.setString("saved_place_lists", "{}")
        }
    }

    // --- RecentSearchStore ---

    @Test
    fun `added queries read back most-recent first`() {
        val store = recents()
        runBlocking {
            store.add("pizza")
            store.add("park")
            assertEquals(listOf("park", "pizza"), store.recents.first())
        }
    }

    @Test
    fun `re-adding moves to front without duplicating`() {
        val store = recents()
        runBlocking {
            store.add("Pizza")
            store.add("park")
            store.add("pizza")
            assertEquals(listOf("pizza", "park"), store.recents.first())
        }
    }

    @Test
    fun `blank queries are ignored`() {
        val store = recents()
        runBlocking {
            store.add("   ")
            store.add("")
            assertTrue(store.recents.first().isEmpty())
        }
    }

    @Test
    fun `recents are capped at ten`() {
        val store = recents()
        runBlocking {
            repeat(12) { store.add("q$it") }
            val got = store.recents.first()
            assertEquals(10, got.size)
            assertEquals("q11", got.first())
            assertTrue(got.none { it == "q0" || it == "q1" })
        }
    }

    @Test
    fun `clear empties recents`() {
        val store = recents()
        runBlocking {
            store.add("pizza")
            store.clear()
            assertTrue(store.recents.first().isEmpty())
        }
    }

    @Test
    fun `corrupt payload decodes to empty`() {
        val store = recents()
        runBlocking {
            DataStoreUtils.getInstance(context).setString("maps_recent_searches", "not json{{{")
            assertTrue(store.recents.first().isEmpty())
            assertTrue(store.current().isEmpty())
        }
    }

    // --- ParkingStore ---

    @Test
    fun `active spot round-trips with its note`() {
        val store = parking()
        val spot = ParkingSpot(lat = 37.8, lon = -122.4, timestamp = 123L, note = "P2")
        runBlocking {
            store.setActive(spot)
            assertEquals(spot, store.activeFlow().first())
        }
        assertEquals(spot, store.activeInitial())
    }

    @Test
    fun `clearing the active spot reads back null`() {
        val store = parking()
        runBlocking {
            store.setActive(ParkingSpot(1.0, 2.0, 3L))
            store.setActive(null)
            assertNull(store.activeFlow().first())
        }
    }

    @Test
    fun `history round-trips as a list`() {
        val store = parking()
        val spots = listOf(
            ParkingSpot(1.0, 2.0, 3L, note = "a"),
            ParkingSpot(4.0, 5.0, 6L),
        )
        runBlocking {
            store.setHistory(spots)
            assertEquals(spots, store.historyFlow().first())
        }
        assertEquals(spots, store.historyInitial())
    }

    @Test
    fun `corrupt parking payloads decode to empty`() {
        val store = parking()
        val ds = DataStoreUtils.getInstance(context)
        runBlocking {
            ds.setString("parking_active_spot", "garbage")
            ds.setString("parking_history", "garbage")
            assertNull(store.activeFlow().first())
            assertTrue(store.historyFlow().first().isEmpty())
        }
    }

    @Test
    fun `parking spot converts to a routable feature`() {
        val feature = ParkingSpot(37.8, -122.4, 9L).toFeature("My car")
        assertEquals("My car", feature.name)
        assertEquals(37.8, feature.position.latitude)
        assertEquals(-122.4, feature.position.longitude)
    }

    // --- SavedPlaceStore ---

    @Test
    fun `saved list round-trips`() {
        val store = saved()
        val places = listOf(SavedPlace("Home", 1.0, 2.0), SavedPlace("Work", 3.0, 4.0))
        runBlocking {
            store.setSaved(places)
            assertEquals(places, store.savedFlow().first())
        }
        assertEquals(places, store.savedInitial())
    }

    @Test
    fun `named lists round-trip`() {
        val store = saved()
        val lists = mapOf("Trip" to listOf(SavedPlace("Hotel", 5.0, 6.0)))
        runBlocking {
            store.setLists(lists)
            assertEquals(lists, store.listsFlow().first())
        }
        assertEquals(lists, store.listsInitial())
    }

    @Test
    fun `corrupt saved payloads decode to empty`() {
        val store = saved()
        val ds = DataStoreUtils.getInstance(context)
        runBlocking {
            ds.setString("saved_places_list", "garbage")
            ds.setString("saved_place_lists", "garbage")
            assertTrue(store.savedFlow().first().isEmpty())
            assertTrue(store.listsFlow().first().isEmpty())
        }
    }

    @Test
    fun `saved place matching is by name and coordinates`() {
        val place = SavedPlace("Cafe", 1.0, 2.0)
        val same = SpecificFeature.GenericPlace(
            name = "Cafe", phone = null, website = null, openingHours = null,
            position = com.vayunmathur.library.map.GeoPoint(2.0, 1.0),
        )
        val renamed = same.copy(name = "Cafe 2")
        assertTrue(place.matches(same))
        assertTrue(!place.matches(renamed))
        assertEquals(place, SavedPlace.from(same))
        assertEquals(same.name, place.toFeature().name)
    }
}
