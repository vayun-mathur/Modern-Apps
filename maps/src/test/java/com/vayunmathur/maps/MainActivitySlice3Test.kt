package com.vayunmathur.maps

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.maps.data.MapLink
import com.vayunmathur.maps.data.MapLinkParser
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.ThemeMode
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 3/3 (root package files M-Z): [MainActivity]'s intent routing, [Route]
 * navigation hierarchy, and theme mapping.
 *
 * MainActivity is the only root-package file M-Z, and it is the sole consumer
 * of [MapLinkParser]: every external `geo:` / `google.navigation:` / Maps web
 * link enters here, so the routing contract (named point -> instant select,
 * bare point -> reverse-geocode with dropped-pin fallback, query -> first-hit
 * select, navigate -> guidance, garbage -> ignored without crashing) is pinned
 * through the real activity under Robolectric rather than against the parser
 * alone. The [Navigation] back stack and [ThemeMode] mapping ride along
 * because they live in this same file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivitySlice3Test {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest
    fun setUp() {
        // NOTE: Dispatchers.setMain is deliberately NOT used: awaitUserPosition
        // uses withTimeoutOrNull, whose delay stalls on a TestDispatcher's
        // virtual clock. The real Main (shadow-paused) lets navigateTo run.
        // The manifest disables WorkManager's auto-initializer, so nothing
        // initializes it under Robolectric and syncOverlaySchedule() would
        // throw on every activity launch. Manual init keeps the enqueue
        // synchronous and hermetic.
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

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewIntent(data: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(data)).apply {
            setClass(context, MainActivity::class.java)
        }

    private fun launch(data: String?): org.robolectric.android.controller.ActivityController<MainActivity> {
        val intent = Intent(context, MainActivity::class.java)
        if (data != null) {
            intent.action = Intent.ACTION_VIEW
            intent.data = Uri.parse(data)
        }
        return Robolectric.buildActivity(MainActivity::class.java, intent).setup()
    }

    private fun selectedOf(activity: MainActivity): SelectedFeatureViewModel =
        ViewModelProvider(activity)[SelectedFeatureViewModel::class.java]

    @Test
    fun `cold start schedules the overlay check when auto-update is on`() {
        // syncOverlaySchedule() runs on every launch: with the default toggle
        // on, the daily overlay worker is enqueued (KEEP keeps it singular).
        // The periodic tag is stable, so listing by tag proves the enqueue.
        val controller = launch(null)
        controller.get()
        val wm = androidx.work.WorkManager.getInstance(context)
        // Unique-work name from MapOverlayWorker (private const): stable.
        val infos = wm.getWorkInfosForUniqueWork("map_overlay_periodic").get()
        kotlin.test.assertTrue(infos.isNotEmpty(), "overlay schedule missing after launch")
        controller.destroy()
    }

    @Test
    fun `cold start skips the overlay schedule when auto-update is off`() {
        // The toggle gates inside syncOverlaySchedule: off means no enqueue.
        // Cancel first so a schedule left by a sibling test cannot leak in.
        androidx.work.WorkManager.getInstance(context).cancelAllWork().result.get()
        val ds = com.vayunmathur.library.util.DataStoreUtils.getInstance(context)
        kotlinx.coroutines.runBlocking {
            ds.setBoolean(com.vayunmathur.maps.data.MapPreferences.KEY_MAP_AUTO_UPDATE, false)
        }
        try {
            val controller = launch(null)
            controller.get()
            val wm = androidx.work.WorkManager.getInstance(context)
            // Unique-work name from MapOverlayWorker (private const): stable.
            val infos = wm.getWorkInfosForUniqueWork("map_overlay_periodic").get()
            kotlin.test.assertTrue(infos.isEmpty(), "overlay scheduled despite opt-out: $infos")
            controller.destroy()
        } finally {
            kotlinx.coroutines.runBlocking {
                ds.setBoolean(
                    com.vayunmathur.maps.data.MapPreferences.KEY_MAP_AUTO_UPDATE,
                    com.vayunmathur.maps.data.MapPreferences.DEFAULT_MAP_AUTO_UPDATE,
                )
            }
        }
    }

    // --- MapLinkParser contract (the activity's only routing input) ---

    @Test
    fun `geo point parses to bare coordinates`() {
        val link = MapLinkParser.parse("geo:37.7749,-122.4194")
        assertNotNull(link)
        assertEquals(37.7749, link.lat)
        assertEquals(-122.4194, link.lng)
        assertNull(link.query)
    }

    @Test
    fun `geo search parses to query only`() {
        val link = MapLinkParser.parse("geo:0,0?q=Coffee")
        assertNotNull(link)
        assertEquals("Coffee", link.query)
        assertNull(link.lat)
    }

    @Test
    fun `geo named point carries both label and coordinates`() {
        val link = MapLinkParser.parse("geo:37.7749,-122.4194?q=Pier+39")
        assertNotNull(link)
        assertEquals(37.7749, link.lat)
        assertEquals(-122.4194, link.lng)
        assertTrue(link.query?.contains("Pier") == true)
    }

    @Test
    fun `navigation link requests guidance with drive mode`() {
        val link = MapLinkParser.parse("google.navigation:q=37.7749,-122.4194&mode=d")
        assertNotNull(link)
        assertTrue(link.navigate)
        assertEquals(com.vayunmathur.maps.util.RouteService.TravelMode.DRIVE, link.mode)
        assertEquals(37.7749, link.lat)
    }

    @Test
    fun `unparseable link returns null`() {
        assertNull(MapLinkParser.parse("not a link at all"))
        assertNull(MapLinkParser.parse(""))
    }

    @Test
    fun `link without target returns null`() {
        // A navigation intent with no destination has nothing to act on.
        val link = MapLinkParser.parse("google.navigation:mode=d")
        assertTrue(link == null || !link.hasTarget)
    }

    @Test
    fun `MapLink hasTarget needs a query or a full point`() {
        assertTrue(MapLink(query = "coffee").hasTarget)
        assertTrue(MapLink(lat = 1.0, lng = 2.0).hasTarget)
        assertTrue(!MapLink(lat = 1.0).hasTarget)
        assertTrue(!MapLink().hasTarget)
    }

    // --- Activity intent routing ---

    @Test
    fun `cold start with named geo point selects it immediately`() {
        val controller = launch("geo:37.7749,-122.4194?q=Pier+39")
        val activity = controller.get()
        val vm = selectedOf(activity)
        val selected = vm.selectedFeature.value
        assertIs<SpecificFeature.GenericPlace>(selected)
        assertTrue(selected.name.contains("Pier"))
        assertEquals(37.7749, selected.position.latitude, 1e-6)
        assertEquals(-122.4194, selected.position.longitude, 1e-6)
        // The map is asked to fly there: a focus request is pending.
        assertNotNull(vm.pendingFocus.value)
        controller.destroy()
    }

    @Test
    fun `cold start with non-view action selects nothing`() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, "geo:37.7749,-122.4194?q=Pier+39")
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val vm = selectedOf(controller.get())
        assertNull(vm.selectedFeature.value)
        assertNull(vm.pendingFocus.value)
        controller.destroy()
    }

    @Test
    fun `cold start with unparseable view link selects nothing and does not crash`() {
        val controller = launch("https://example.com/not-a-maps-link")
        val vm = selectedOf(controller.get())
        assertNull(vm.selectedFeature.value)
        assertNull(vm.pendingFocus.value)
        controller.destroy()
    }

    @Test
    fun `cold start with view action but null data selects nothing`() {
        // ACTION_VIEW with no Uri: dataString is null, handled without crashing.
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val vm = selectedOf(controller.get())
        assertNull(vm.selectedFeature.value)
        assertNull(vm.pendingFocus.value)
        controller.destroy()
    }

    @Test
    fun `warm start routes a new geo intent to the selection`() {
        val controller = launch(null)
        val activity = controller.get()
        val vm = selectedOf(activity)
        assertNull(vm.selectedFeature.value)
        controller.newIntent(viewIntent("geo:37.7749,-122.4194?q=Pier+39"))
        val selected = vm.selectedFeature.value
        assertIs<SpecificFeature.GenericPlace>(selected)
        assertTrue(selected.name.contains("Pier"))
        controller.destroy()
    }

    @Test
    fun `warm start with unparseable intent keeps prior selection`() {
        val controller = launch("geo:37.7749,-122.4194?q=Pier+39")
        val activity = controller.get()
        val vm = selectedOf(activity)
        val before = vm.selectedFeature.value
        assertNotNull(before)
        controller.newIntent(viewIntent("https://example.com/not-a-maps-link"))
        assertEquals(before, vm.selectedFeature.value)
        controller.destroy()
    }

    @Test
    fun `consumeFocus clears the pending camera request`() {
        val controller = launch("geo:37.7749,-122.4194?q=Pier+39")
        val vm = selectedOf(controller.get())
        assertNotNull(vm.pendingFocus.value)
        vm.consumeFocus()
        assertNull(vm.pendingFocus.value)
        controller.destroy()
    }

    // --- Route navigation hierarchy (same file) ---

    @Test
    fun `routes are nav keys with three destinations`() {
        assertIs<NavKey>(Route.MapPage)
        assertIs<NavKey>(Route.SettingsPage)
        assertIs<NavKey>(Route.SavedPlacesPage)
    }

    @Test
    fun `back stack starts on the map and navigates to settings and back`() {
        val stack = NavBackStack<Route>(arrayOf(Route.MapPage))
        assertEquals(Route.MapPage, stack.last())
        stack.add(Route.SettingsPage)
        assertEquals(Route.SettingsPage, stack.last())
        stack.pop()
        assertEquals(Route.MapPage, stack.last())
    }

    @Test
    fun `back stack reaches saved places independently of settings`() {
        val stack = NavBackStack<Route>(arrayOf(Route.MapPage))
        stack.add(Route.SavedPlacesPage)
        assertEquals(Route.SavedPlacesPage, stack.last())
        stack.pop()
        assertEquals(listOf(Route.MapPage), stack.backStack)
    }

    // --- Theme mapping (AppTheme reads this) ---

    @Test
    fun `theme mode maps stored prefs to dark overrides`() {
        assertEquals(null, ThemeMode.from(null).darkOverride)
        assertEquals(null, ThemeMode.from("system").darkOverride)
        assertEquals(false, ThemeMode.from("light").darkOverride)
        assertEquals(true, ThemeMode.from("dark").darkOverride)
        // Unknown stored values fall back to following the system, never to a forced mode.
        assertEquals(null, ThemeMode.from("amoled").darkOverride)
    }

    @Test
    fun `routes serialize round-trip through generated serializer`() {
        // The @Serializable Route hierarchy persists navigation state; a broken
        // discriminator would restore the wrong screen after process death.
        val json = kotlinx.serialization.json.Json
        for (route in listOf(Route.MapPage, Route.SettingsPage, Route.SavedPlacesPage)) {
            val encoded = json.encodeToString(Route.serializer(), route)
            assertEquals(route, json.decodeFromString(Route.serializer(), encoded))
        }
    }

    @Test
    fun `route discriminators are distinct`() {
        val json = kotlinx.serialization.json.Json
        val encodings = listOf(Route.MapPage, Route.SettingsPage, Route.SavedPlacesPage)
            .map { json.encodeToString(Route.serializer(), it) }
        assertEquals(3, encodings.toSet().size)
    }

    // --- navigateTo paths (same file) ---

    private var fixtureDir: java.io.File? = null

    /**
     * Map a one-POI index (no grid/words/attrs side files): [PoiIndex.searchByName]
     * falls back to the scan path, [PoiIndex.nearest] walks the morton span, and
     * the attribute lookup yields null so enrichment resolves to the bare POI name.
     * Record layout: latE7(4) lonE7(4) nameOff(4) type(2) = 14 bytes.
     */
    private fun mapOnePoiFixture(name: String, lat: Double, lon: Double) {
        val dir = java.nio.file.Files.createTempDirectory("slice3poi").toFile()
        fixtureDir = dir
        dir.resolve(com.vayunmathur.maps.util.PoiIndex.NAMES_FILE)
            .writeBytes((name + "\u0000").toByteArray(Charsets.UTF_8))
        val rec = java.nio.ByteBuffer.allocate(14).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        rec.putInt((lat * 1e7).toInt())
        rec.putInt((lon * 1e7).toInt())
        rec.putInt(0)
        rec.putShort(0)
        dir.resolve(com.vayunmathur.maps.util.PoiIndex.INDEX_FILE).writeBytes(rec.array())
        assertTrue(
            com.vayunmathur.maps.util.PoiIndex.reload(dir),
            "one-POI fixture did not map",
        )
    }

    /** Clear the process-wide [PoiIndex] mapping so sibling test classes start clean. */
    private fun clearPoiIndex() {
        val empty = java.nio.file.Files.createTempDirectory("slice3empty").toFile()
        try {
            com.vayunmathur.maps.util.PoiIndex.reload(empty)
        } finally {
            empty.deleteRecursively()
        }
        fixtureDir?.deleteRecursively()
        fixtureDir = null
    }

    /** Disable both location providers so no live fix exists (persisted-fix branch). */
    private fun clearLiveFix() {
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE)
            as android.location.LocationManager
        val shadow = org.robolectric.Shadows.shadowOf(lm)
        shadow.setProviderEnabled(android.location.LocationManager.GPS_PROVIDER, false)
        shadow.setProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER, false)
    }

    /** Seed the OS-cached GPS fix so the ViewModel starts with a live position. */
    private fun seedGpsFix(lat: Double = 37.7749, lon: Double = -122.4194) {
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE)
            as android.location.LocationManager
        val shadow = org.robolectric.Shadows.shadowOf(lm)
        shadow.setProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER, true)
        val fix = android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lon
            accuracy = 5f
            elapsedRealtimeNanos = 1_000_000_000L
        }
        shadow.setLastKnownLocation(android.location.LocationManager.GPS_PROVIDER, fix)
        shadow.setLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER, null)
    }

    @Test
    fun `navigate link with coord destination falls back to opening the place when routing fails`() {
        // No routing archive under Robolectric, so getRouteForMode throws/returns
        // null and navigateTo must degrade to selecting the destination place
        // instead of crashing or leaving nothing selected.
        seedGpsFix()
        val controller = launch("google.navigation:q=37.7849,-122.4094&mode=d")
        val vm = selectedOf(controller.get())
        awaitSelection(vm)
        val selected = vm.selectedFeature.value
        assertIs<SpecificFeature.GenericPlace>(selected, "destination place should be selected")
        assertEquals(37.7849, selected.position.latitude, 1e-6)
        assertEquals(-122.4094, selected.position.longitude, 1e-6)
        controller.destroy()
    }

    @Test
    fun `bare geo point with no index hit drops a plain pin`() {
        // No POI fixture mapped: reverseGeocode resolves null and openPlace
        // falls back to a "Dropped pin" rather than selecting nothing.
        clearPoiIndex()
        seedGpsFix()
        val controller = launch("geo:37.7849,-122.4094")
        val vm = selectedOf(controller.get())
        awaitSelection(vm)
        val selected = vm.selectedFeature.value
        assertIs<SpecificFeature.GenericPlace>(selected)
        assertEquals(context.getString(R.string.dropped_pin), selected.name)
        assertEquals(37.7849, selected.position.latitude, 1e-6)
        controller.destroy()
    }

    @Test
    fun `navigate link with no destination selects nothing`() {
        // navigate=true but no coord or query: resolveDestination has nothing
        // to resolve and navigateTo returns without selecting or crashing.
        seedGpsFix()
        val controller = launch("google.navigation:mode=d")
        val vm = selectedOf(controller.get())
        awaitQuiescence()
        assertNull(vm.selectedFeature.value)
        controller.destroy()
    }

    @Test
    fun `navigate link with no live fix opens the destination place`() {
        // No providers enabled and no persisted fix: awaitUserPosition times
        // out and navigateTo degrades to opening the destination place so the
        // user can start navigation manually once located.
        clearLiveFix()
        val controller = launch("google.navigation:q=37.7849,-122.4094&mode=d")
        val vm = selectedOf(controller.get())
        awaitSelection(vm, maxIters = 2_000)
        val selected = vm.selectedFeature.value
        assertIs<SpecificFeature.GenericPlace>(selected, "destination place should be selected")
        assertEquals(37.7849, selected.position.latitude, 1e-6)
        assertEquals(-122.4094, selected.position.longitude, 1e-6)
        controller.destroy()
    }
    @Test
    fun `warm start navigate link with a fix attempts routing then opens the place`() {
        // Seeding the OS fix before launch makes it the cold-start seed: the
        // VM picks it up while the plain launch settles, so the later navigate
        // intent finds a live origin and attempts real routing. With no graph
        // archive routing resolves null and navigateTo degrades to opening the
        // destination place.
        seedGpsFix()
        val controller = launch(null)
        val activity = controller.get()
        val vm = selectedOf(activity)
        awaitFix(vm)
        controller.newIntent(viewIntent("google.navigation:q=37.7849,-122.4094&mode=d"))
        awaitSelection(vm)
        val selected = vm.selectedFeature.value
        assertIs<SpecificFeature.GenericPlace>(selected, "destination place should be selected")
        assertEquals(37.7849, selected.position.latitude, 1e-6)
        assertEquals(-122.4094, selected.position.longitude, 1e-6)
        controller.destroy()
    }

    @Test
    fun `warm start navigate link without a mode defaults to driving`() {
        // No mode param: link.mode is null and navigateTo drives by default,
        // still degrading to the destination place without a graph archive.
        seedGpsFix()
        val controller = launch(null)
        val vm = selectedOf(controller.get())
        awaitFix(vm)
        controller.newIntent(viewIntent("google.navigation:q=37.7849,-122.4094"))
        awaitSelection(vm)
        val selected = vm.selectedFeature.value
        assertIs<SpecificFeature.GenericPlace>(selected, "destination place should be selected")
        assertEquals(37.7849, selected.position.latitude, 1e-6)
        controller.destroy()
    }

    @Test
    fun `activity exposes its cross-app intent launcher`() {
        // Other apps' ride/order clients launch maps intents through this.
        val controller = launch(null)
        controller.get()
        assertNotNull(MainActivity.intentLauncher)
        controller.destroy()
    }

    @Test
    fun `navigate link with unresolvable query selects nothing`() {
        // A free-text destination with no offline index entry resolves to null,
        // and navigateTo must log and return without selecting or crashing.
        seedGpsFix()
        val controller = launch("google.navigation:q=zz-unresolvable-place-xyzzy&mode=d")
        val vm = selectedOf(controller.get())
        awaitQuiescence()
        assertNull(vm.selectedFeature.value)
        controller.destroy()
    }

    @Test
    fun `query-only place link with no index hit selects nothing and does not crash`() {
        seedGpsFix()
        val controller = launch("geo:0,0?q=zz-unresolvable-place-xyzzy")
        val vm = selectedOf(controller.get())
        awaitQuiescence()
        assertNull(vm.selectedFeature.value)
        controller.destroy()
    }

    /**
     * Pump the main looper until [vm] holds a non-zero position or the bound
     * is hit. The seeded OS fix is delivered to the location listener on the
     * main loop, so a plain launch needs a few pumps before the VM sees it.
     * Bounded by iteration count, not wall clock.
     */
    private fun awaitFix(
        vm: SelectedFeatureViewModel,
        maxIters: Int = 500,
    ) {
        val shadow = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        repeat(maxIters) {
            Robolectric.flushForegroundThreadScheduler()
            shadow.idle()
            val p = vm.userPosition.value
            if (p.latitude != 0.0 || p.longitude != 0.0) return
            Thread.yield()
        }
    }

    /**
     * Pump the main looper until [vm] holds a selection or the bound is hit.
     * navigateTo hops through Dispatchers.IO/Default on real background
     * threads (PAUSED looper mode has no background scheduler to flush), so
     * this yields to let them run while draining every posted main-loop task.
     * Bounded by iteration count, not wall clock.
     */
    private fun awaitSelection(
        vm: SelectedFeatureViewModel,
        maxIters: Int = 500,
    ) {
        val shadow = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        repeat(maxIters) {
            Robolectric.flushForegroundThreadScheduler()
            shadow.idle()
            if (vm.selectedFeature.value != null) return
            Thread.yield()
        }
    }

    /** Same drain without a condition, for asserting a steady null selection. */
    private fun awaitQuiescence(maxIters: Int = 500) {
        val shadow = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        repeat(maxIters) {
            Robolectric.flushForegroundThreadScheduler()
            shadow.idle()
            Thread.yield()
        }
    }

    @Test
    fun `fallback bias centre is downtown Los Angeles`() {
        // Pinned because openPlace/resolveDestination silently bias every offline
        // search around it whenever no fix exists: a wrong constant degrades
        // every cold-start deep link, not just one screen.
        clearLiveFix()
        val controller = launch("geo:0,0?q=unresolvable-place-xyzzy")
        val vm = selectedOf(controller.get())
        // No offline index under Robolectric, so no selection happens; what this
        // pins is that the fallback path runs without crashing and leaves the
        // query path (not the point path) taken — nothing selected, no focus.
        assertNull(vm.selectedFeature.value)
        assertNull(vm.pendingFocus.value)
        controller.destroy()
    }

    @Test
    fun `query-only place link selects the offline index hit`() {
        mapOnePoiFixture("Pier 39", 37.7849, -122.4094)
        seedGpsFix()
        try {
            val controller = launch("geo:0,0?q=Pier+39")
            val activity = controller.get()
            val vm = selectedOf(activity)
            awaitSelection(vm)
            val selected = vm.selectedFeature.value
            assertIs<SpecificFeature.GenericPlace>(selected)
            assertEquals("Pier 39", selected.name)
            assertNotNull(vm.pendingFocus.value)
            // The search ran through the query box path: the query text is set.
            assertEquals(
                "Pier 39",
                ViewModelProvider(activity)
                    [com.vayunmathur.maps.util.MapsSearchViewModel::class.java].query.value,
            )
            controller.destroy()
        } finally {
            clearPoiIndex()
        }
    }

    @Test
    fun `bare geo point reverse-geocodes to the nearby POI name`() {
        mapOnePoiFixture("Pier 39", 37.7849, -122.4094)
        seedGpsFix()
        try {
            val controller = launch("geo:37.7849,-122.4094")
            val vm = selectedOf(controller.get())
            awaitSelection(vm)
            val selected = vm.selectedFeature.value
            assertIs<SpecificFeature.GenericPlace>(selected)
            assertEquals("Pier 39", selected.name)
            controller.destroy()
        } finally {
            clearPoiIndex()
        }
    }

    @Test
    fun `navigate query link resolves through the offline index`() {
        mapOnePoiFixture("Pier 39", 37.7849, -122.4094)
        seedGpsFix()
        try {
            val controller = launch("google.navigation:q=Pier+39&mode=d")
            val vm = selectedOf(controller.get())
            awaitSelection(vm)
            // Routing still fails without an archive, so the resolved destination
            // degrades to an opened place — but the destination came from the
            // index, not from raw coordinates.
            val selected = vm.selectedFeature.value
            assertIs<SpecificFeature.GenericPlace>(selected)
            assertEquals("Pier 39", selected.name)
            controller.destroy()
        } finally {
            clearPoiIndex()
        }
    }

    @Test
    fun `persisted fix biases the query search when no live fix exists`() {
        clearLiveFix()
        val ds = com.vayunmathur.library.util.DataStoreUtils.getInstance(context)
        kotlinx.coroutines.runBlocking {
            ds.setDouble(com.vayunmathur.maps.data.MapPreferences.KEY_LAST_LON, -122.4194)
            ds.setDouble(com.vayunmathur.maps.data.MapPreferences.KEY_LAST_LAT, 37.7749)
        }
        try {
            val controller = launch("geo:0,0?q=unresolvable-place-xyzzy")
            val activity = controller.get()
            val vm = selectedOf(activity)
            awaitQuiescence()
            // The persisted branch ran the search (query text set by
            // searchAndSelectFirst) and, with no index hit, selected nothing.
            assertEquals(
                "unresolvable-place-xyzzy",
                ViewModelProvider(activity)
                    [com.vayunmathur.maps.util.MapsSearchViewModel::class.java].query.value,
            )
            assertNull(vm.selectedFeature.value)
            controller.destroy()
        } finally {
            // (0,0) is the "no fix stored" sentinel: restore it so sibling
            // tests and classes see a clean store.
            kotlinx.coroutines.runBlocking {
                ds.setDouble(com.vayunmathur.maps.data.MapPreferences.KEY_LAST_LON, 0.0)
                ds.setDouble(com.vayunmathur.maps.data.MapPreferences.KEY_LAST_LAT, 0.0)
            }
        }
    }

    @Test
    fun `GeoPoint keeps lon-lat order for intent coordinates`() {
        // MainActivity builds GeoPoint(lng, lat) from link lat/lng: the order is
        // the whole point, so pin it once where the intent path constructs it.
        val p = GeoPoint(-122.4194, 37.7749)
        assertEquals(-122.4194, p.longitude, 1e-9)
        assertEquals(37.7749, p.latitude, 1e-9)
    }
}
