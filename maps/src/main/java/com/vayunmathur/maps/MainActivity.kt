package com.vayunmathur.maps

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.downloadservice.InitialDownloadChecker
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.IntentLauncher
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.maps.data.MapLink
import com.vayunmathur.maps.data.MapLinkParser
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ui.MapPage
import com.vayunmathur.maps.ui.SavedPlacesPage
import com.vayunmathur.maps.ui.settings.MapSettingsPage
import com.vayunmathur.maps.data.MapPreferences
import com.vayunmathur.maps.data.ThemeMode
import com.vayunmathur.maps.util.MapTileCache
import com.vayunmathur.maps.util.MapOverlayWorker
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.NavigationService
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.OfflineRouterRoadRoutes
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.MapSettingsViewModel
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import com.vayunmathur.library.map.GeoPoint
import java.io.File

class MainActivity : MaActivity() {

    companion object {
        private const val TAG = "MapsIntent"

        /** Fallback search-bias centre (downtown Los Angeles) when no GPS fix exists. */
        private const val FALLBACK_LON = -118.243683
        private const val FALLBACK_LAT = 34.052235

        // Hosts the cross-app AssistantIntent client for maps (mirrors taxi/openassistant
        // MainActivity.intentLauncher). WS-C's RideEstimate/OrderLookup clients launch taxi's
        // and fooddelivery's intents through this.
        lateinit var intentLauncher: IntentLauncher
    }

    // Same instances the Compose tree gets via viewModel() (both resolve to this
    // Activity's ViewModelStore), so an external geo:/maps/navigation intent handled
    // here shows up in the map UI: selecting a place opens its bottom pane, and a
    // navigation link drives the shared NavigationSessionManager.
    private val selectedVm: SelectedFeatureViewModel by viewModels()
    private val searchVm: MapsSearchViewModel by viewModels()

    override fun onCreateBeforeContent() {
        // FIRST_PARTY: data.maproj.org tiles + amenities + api.maproj.org -> ISRG+GTS
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        intentLauncher = IntentLauncher(this)
        // Daily overlay check chain (UNMETERED): KEEP leaves an existing chain
        // alone; the toggle gates inside syncAutoUpdateSchedule.
        syncOverlaySchedule()
        // Reclaim the ~44 MB bundled basemap copied into filesDir by older
        // builds; the basemap now streams live and is cached on demand.
        File(filesDir, "world_z0-6.pmtiles").delete()

        // Deep link that launched us cold (geo:/google.navigation:/maps URL).
        handleIntent(intent)
    }

    /** Enqueue the daily overlay check when the auto-update toggle is on. */
    private fun syncOverlaySchedule() {
        val ds = DataStoreUtils.getInstance(this)
        if (ds.getBoolean(MapPreferences.KEY_MAP_AUTO_UPDATE, MapPreferences.DEFAULT_MAP_AUTO_UPDATE)) {
            MapOverlayWorker.scheduleAuto(this)
        }
    }

    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        val ds = DataStoreUtils.getInstance(this)
        val themeMode by ds.stringFlow(MapPreferences.KEY_THEME_MODE)
            .collectAsState(initial = ds.getString(MapPreferences.KEY_THEME_MODE))
        DynamicTheme(darkTheme = ThemeMode.from(themeMode).darkOverride, content = content)
    }

    @Composable
    override fun Content() {
        val ds = DataStoreUtils.getInstance(this)
        // One download: the single-archive `.mamaps` carries the tiles
        // plus every former side file as sections (graph metadata /
        // nodes / edges / intermediate / names / lanes / elevation,
        // POI index / names / attrs / spatial / words, transit),
        // appended 8-aligned with a section directory + MAMA8 footer
        // by `mamaps_pack`. Each loader maps the one file once and
        // falls back to its legacy side files when the archive is
        // absent or lacks its sections, so a tiles-only archive
        // degrades exactly as missing side files do today — but the
        // published archive packs all thirteen, and this gate lists
        // only it.
        //
        // Hosting hazard (unchanged): this entry gates app start, so
        // the URL must be live before the version ships. An `adb push`
        // to the same filename satisfies the gate, which is how a
        // locally built archive is sideloaded.
        InitialDownloadChecker(ds, listOf(
            Triple(
                MapTileCache.BASEMAP_ARCHIVE_URL,
                MapTileCache.BASEMAP_ARCHIVE_FILE,
                getString(R.string.downloading_basemap),
            )
        )) {
            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // POST_NOTIFICATIONS is runtime-grantable on API 33+
                // and required for the navigation foreground-service
                // notification to actually display (otherwise the
                // service runs invisibly and the user can't End Trip
                // from outside the app).
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            AppPermissionsGate(
                spec = AppPermissionsSpec(
                    title = getString(R.string.grant_location_permission),
                    requirements = listOf(PermissionRequirement.Runtime(perms))
                )
            ) {
                OfflineAware {
                    Navigation()
                }
            }
        }
    }

    // Warm start: the app was already running when another app fired a geo:/maps/
    // navigation intent at us. singleTop (see AndroidManifest) routes it here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Turn an external ACTION_VIEW `geo:` / `google.navigation:` / Google-Maps web
     * link into either an opened place (select + bottom pane) or a started
     * navigation session. Malformed/empty links are ignored (never crash).
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.dataString ?: return
        val link = MapLinkParser.parse(data)
        if (link == null) {
            Log.status(TAG, "ACTION_VIEW ignored (unparseable): $data")
            return
        }
        Log.status(TAG, "ACTION_VIEW routed data=$data -> $link")
        if (link.navigate) navigateTo(link) else openPlace(link)
    }

    /** Select a place from the link and open its bottom pane. */
    private fun openPlace(link: MapLink) {
        val lat = link.lat
        val lng = link.lng
        val query = link.query
        when {
            // A point with a name/label → drop it straight in (no network).
            lat != null && lng != null && !query.isNullOrBlank() -> {
                selectedVm.selectAndFocus(genericPlace(query, lat, lng), link.zoom)
            }
            // A bare point → reverse-geocode for a name; fall back to a plain pin.
            lat != null && lng != null -> {
                searchVm.reverseGeocode(lat, lng) { place ->
                    selectedVm.selectAndFocus(
                        place ?: genericPlace(getString(R.string.dropped_pin), lat, lng),
                        link.zoom,
                    )
                }
            }
            // A query only → run the Google search and auto-select the first hit
            // (same path as the contact-address shortcut).
            !query.isNullOrBlank() -> {
                val near = biasPosition()
                searchVm.searchAndSelectFirst(query, near.latitude, near.longitude) { first ->
                    if (first != null) selectedVm.selectAndFocus(searchVm.toFeature(first), link.zoom)
                    else Log.status(TAG, "openPlace: no search result for \"$query\"")
                }
            }
        }
    }

    /** Resolve the link's destination, route from the user's location, and start guidance. */
    private fun navigateTo(link: MapLink) {
        lifecycleScope.launch {
            val mode = link.mode ?: RouteService.TravelMode.DRIVE
            val (destPos, destName) = resolveDestination(link)
            if (destPos == null) {
                Log.status(TAG, "navigateTo: could not resolve destination for $link")
                return@launch
            }

            val destFeature = SpecificFeature.GenericPlace(
                name = destName,
                phone = null,
                website = null,
                openingHours = null,
                position = destPos,
            )
            // A null first waypoint means "from current location".
            val route = SpecificFeature.Route(listOf(null, destFeature))

            // Wait briefly for a real GPS fix so the first leg starts from the user.
            val origin = awaitUserPosition()
            if (origin == null) {
                // No fix yet: fall back to opening the destination place + pane so the
                // user can hit Start Navigation manually (which waits for location).
                Log.status(TAG, "navigateTo: no location fix; opening destination place instead")
                selectedVm.selectAndFocus(destFeature)
                return@launch
            }

            val computed = try {
                OfflineRouterRoadRoutes.getRouteForMode(applicationContext, route, origin, mode)
            } catch (_: Exception) {
                Log.status(TAG, "navigateTo: routing failed"); null
            }
            if (computed == null) {
                Log.status(TAG, "navigateTo: no route ($mode) to $destName; opening place instead")
                selectedVm.selectAndFocus(destFeature)
                return@launch
            }

            startForegroundService(Intent(applicationContext, NavigationService::class.java))
            NavigationSessionManager.init(applicationContext)
            NavigationSessionManager.start(
                route = computed,
                mode = mode,
                destination = destPos,
                destinationLabel = destName,
            )
            Log.status(TAG, "navigateTo: started $mode navigation to $destName")
        }
    }

    /** The link's destination: an explicit coord, else an offline POI lookup of the query. */
    private suspend fun resolveDestination(link: MapLink): Pair<GeoPoint?, String> {
        if (link.lat != null && link.lng != null) {
            return GeoPoint(link.lng, link.lat) to (link.query ?: getString(R.string.dropped_pin))
        }
        val query = link.query
        if (query.isNullOrBlank()) return null to getString(R.string.dropped_pin)
        val near = biasPosition()
        // Offline OSM POI search (no query-bar side effects: this path
        // must not prefill the search text like searchAndSelectFirst
        // does). Free-text addresses have no OSM path and stay
        // unresolvable, handled by the caller.
        val first = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.vayunmathur.maps.util.PoiIndex.initialize(applicationContext)
                com.vayunmathur.maps.util.PoiIndex.searchByName(
                    query, near.latitude, near.longitude, limit = 1,
                ).firstOrNull()
            }.getOrNull()
        }
        return first?.let { GeoPoint(it.lon, it.lat) } to (first?.name ?: query)
    }

    /** First valid GPS fix within [timeoutMs], or null. (0,0) means "no fix yet". */
    private suspend fun awaitUserPosition(timeoutMs: Long = 8_000): GeoPoint? =
        withTimeoutOrNull(timeoutMs) {
            selectedVm.userPosition.first { it.latitude != 0.0 || it.longitude != 0.0 }
        }

    /** Search bias: the user's live position when known, else the persisted last fix, else the map's default centre. */
    private fun biasPosition(): GeoPoint {
        val p = selectedVm.userPosition.value
        if (p.latitude != 0.0 || p.longitude != 0.0) return p
        return persistedLastFix() ?: GeoPoint(FALLBACK_LON, FALLBACK_LAT)
    }

    /** The last GPS fix persisted across restarts, or null when none was ever stored. */
    private fun persistedLastFix(): GeoPoint? {
        val ds = DataStoreUtils.getInstance(this)
        val lon = ds.getDouble(MapPreferences.KEY_LAST_LON) ?: return null
        val lat = ds.getDouble(MapPreferences.KEY_LAST_LAT) ?: return null
        if (lat == 0.0 && lon == 0.0) return null
        return GeoPoint(lon, lat)
    }

    private fun genericPlace(name: String, lat: Double, lng: Double) =
        SpecificFeature.GenericPlace(
            name = name, phone = null, website = null, openingHours = null,
            position = GeoPoint(lng, lat),
        )
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object MapPage: Route
    @Serializable
    data object SettingsPage: Route
    @Serializable
    data object SavedPlacesPage: Route
}

@Composable
fun Navigation(
    viewModel: SelectedFeatureViewModel = viewModel(),
    searchViewModel: MapsSearchViewModel = viewModel(),
    savedPlacesViewModel: SavedPlacesViewModel = viewModel(),
    settingsViewModel: MapSettingsViewModel = viewModel(),
    parkingViewModel: com.vayunmathur.maps.util.ParkingViewModel = viewModel(),
    transitViewModel: com.vayunmathur.maps.util.TransitStopsViewModel = viewModel(),
) {
    val backStack = rememberNavBackStack<Route>(Route.MapPage)
    MainNavigation(backStack) {
        // The map is the list pane and settings/saved-places are detail panes,
        // so medium widths show them side by side automatically. The map itself
        // never splits: its own expanded side panel is separate (see MapPage),
        // and these entries carry no shared-element keys — there is nothing to
        // morph between a map surface and a settings form.
        entry<Route.MapPage>(metadata = ListPage()) {
            MapPage(
                backStack,
                viewModel,
                savedPlacesViewModel,
                searchViewModel,
                settingsViewModel,
                parkingViewModel,
                transitViewModel,
            )
        }
        entry<Route.SettingsPage>(metadata = ListDetailPage()) {
            MapSettingsPage(backStack, settingsViewModel)
        }
        entry<Route.SavedPlacesPage>(metadata = ListDetailPage()) {
            SavedPlacesPage(backStack, savedPlacesViewModel)
        }
    }
}
