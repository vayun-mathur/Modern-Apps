package com.vayunmathur.astronomy.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.astronomy.data.CatalogRepository
import com.vayunmathur.astronomy.data.model.PlanetId
import com.vayunmathur.astronomy.R
import com.vayunmathur.astronomy.domain.engine.AltAz
import com.vayunmathur.astronomy.domain.engine.CoordinateTransforms
import com.vayunmathur.astronomy.domain.engine.LunarCalculator
import com.vayunmathur.astronomy.domain.engine.PlanetaryCalculator
import com.vayunmathur.astronomy.domain.engine.RaDec
import com.vayunmathur.astronomy.domain.engine.RiseSetCalculator
import com.vayunmathur.astronomy.domain.engine.SolarCalculator
import com.vayunmathur.astronomy.domain.engine.TimeEngine
import com.vayunmathur.astronomy.domain.engine.toRad
import com.vayunmathur.library.sensor.DeviceOrientation
import com.vayunmathur.astronomy.platform.sensor.LocationProvider
import com.vayunmathur.library.sensor.OrientationManager
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

data class ObserverLocation(
    val latRad: Double,
    val lonRad: Double,
    val latDeg: Double,
    val lonDeg: Double,
    val altM: Double = 0.0,
)

sealed interface CenterOption {
    data object DevicePointing : CenterOption
    data class Manual(val azRad: Double, val altRad: Double) : CenterOption
    data class TargetObject(val objectId: String, val azRad: Double, val altRad: Double) : CenterOption
}

data class VisibleStar(
    val star: com.vayunmathur.astronomy.data.model.Star,
    val altAz: AltAz,
    val raDec: RaDec,
)
data class VisiblePlanet(
    val id: String,
    val name: String,
    val altAz: AltAz,
    val raDec: RaDec,
    val mag: Double?,
    val distanceAu: Double,
)
data class VisibleSun(val altAz: AltAz, val raDec: RaDec, val distanceAu: Double)
data class VisibleMoon(
    val altAz: AltAz,
    val raDec: RaDec,
    val phase: Double,
    val illumination: Double,
    val ageDays: Double,
)
data class VisibleDeepSky(
    val obj: com.vayunmathur.astronomy.data.model.DeepSkyObject,
    val altAz: AltAz,
    val raDec: RaDec,
)

enum class ConstellationMode { OFF, LINES, LINES_AND_ART }

/** One anchor of a constellation figure: the image pixel and where it currently sits on the sky. */
data class VisibleArtAnchor(val srcX: Float, val srcY: Float, val altAz: AltAz)
data class VisibleArt(val abbr: String, val image: String, val anchors: List<VisibleArtAnchor>)

@OptIn(ExperimentalTime::class)
data class VisibleSky(
    val stars: List<VisibleStar> = emptyList(),
    val planets: List<VisiblePlanet> = emptyList(),
    val sun: VisibleSun? = null,
    val moon: VisibleMoon? = null,
    val deepSky: List<VisibleDeepSky> = emptyList(),
    val lstRad: Double = 0.0,
    val jd: Double = 0.0,
    val observer: ObserverLocation? = null,
    val time: Instant = Clock.System.now(),
    val constellations: List<ConstellationLine> = emptyList(),
    val art: List<VisibleArt> = emptyList()
)

data class ConstellationLine(val abbr: String, val name: String, val segments: List<List<Int>>)
data class TrajectoryPoint(val jd: Double, val altAz: AltAz, val raDec: RaDec)
data class SearchResult(val id: String, val title: String, val subtitle: String, val raDec: Pair<Double, Double>?)

@OptIn(ExperimentalTime::class)
class AstronomyViewModel(app: Application) : AndroidViewModel(app), SkyMapActions, SearchActions, SettingsActions {
    private companion object {
        const val MIN_FOV_DEG = 10f
        const val MAX_FOV_DEG = 120f
        const val TICKER_DELAY_MS = 1000L
        const val LOCATION_TIMEOUT_MS = 8000L
        const val TRAJECTORY_HALF_STEPS = 48
        const val TRAJECTORY_STEP_DAYS = 0.25 / 24.0
        const val SEARCH_RESULT_LIMIT = 20
    }
    private val context = getApplication<Application>()
    val catalog = CatalogRepository(context)
    private var starIndex: Map<Int, com.vayunmathur.astronomy.data.model.Star> = emptyMap()
    private val ds = DataStoreUtils.getInstance(context)
    private val prefs = AstronomyPrefs(ds, viewModelScope)
    private val orientationMgr = OrientationManager(context)

    private val _simTime = MutableStateFlow(Clock.System.now())
    val simTime: StateFlow<Instant> = _simTime
    private val _isLive = MutableStateFlow(true)
    val isLive: StateFlow<Boolean> = _isLive
    private val _observer = MutableStateFlow<ObserverLocation?>(null)
    val observer: StateFlow<ObserverLocation?> = _observer
    private val _deviceOrientation = MutableStateFlow<DeviceOrientation?>(null)
    val deviceOrientation: StateFlow<DeviceOrientation?> = _deviceOrientation
    private val _viewCenter = MutableStateFlow<CenterOption>(CenterOption.Manual(0.0, 45.0.toRad()))
    val viewCenter: StateFlow<CenterOption> = _viewCenter
    private val _fovDeg = MutableStateFlow(70f)
    val fovDeg: StateFlow<Float> = _fovDeg

    private val _constellationMode = MutableStateFlow(ConstellationMode.LINES)
    val constellationMode: StateFlow<ConstellationMode> = _constellationMode
    private val _showGrid = MutableStateFlow(false)
    val showGrid: StateFlow<Boolean> = _showGrid
    private val _showDeepSky = MutableStateFlow(true)
    val showDeepSky: StateFlow<Boolean> = _showDeepSky
    private val _showPlanets = MutableStateFlow(true)
    val showPlanets: StateFlow<Boolean> = _showPlanets
    private val _magLimit = MutableStateFlow(6.0f)
    val magLimit: StateFlow<Float> = _magLimit
    private val _nightMode = MutableStateFlow(false)
    val nightMode: StateFlow<Boolean> = _nightMode
    private val _devicePointingEnabled = MutableStateFlow(false)
    val devicePointingEnabled: StateFlow<Boolean> = _devicePointingEnabled

    // Per request: always show below horizon (full sphere), no still mode
    private val _showBelowHorizon = MutableStateFlow(true)
    val showBelowHorizon: StateFlow<Boolean> = _showBelowHorizon

    private val _selectedObjectId = MutableStateFlow<String?>(null)
    val selectedObjectId: StateFlow<String?> = _selectedObjectId
    private val _isArMode = MutableStateFlow(false)
    val isArMode: StateFlow<Boolean> = _isArMode
    private val _trajectory = MutableStateFlow<List<TrajectoryPoint>>(emptyList())
    val trajectory: StateFlow<List<TrajectoryPoint>> = _trajectory
    private val _catalogReady = MutableStateFlow(false)
    val catalogReady: StateFlow<Boolean> = _catalogReady
    private val _riseSet = MutableStateFlow<RiseSetCalculator.RiseTransitSet?>(null)
    val riseSet: StateFlow<RiseSetCalculator.RiseTransitSet?> = _riseSet

    // _magLimit is an input to computeVisibleSky (it filters the star
    // list), so it must be one of the combined flows: without it, changing
    // the magnitude limit in Settings only took effect on the next
    // one-second live tick, and never while time was scrubbed/paused.
    val visibleSky: StateFlow<VisibleSky> = combine(_simTime, _observer, _catalogReady, _magLimit) { time, obs, ready, _ ->
        if (!ready || obs == null) VisibleSky(observer = obs, time = time, jd = TimeEngine.instantToJulianDate(time))
        else computeVisibleSky(time, obs)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, VisibleSky())

    private var tickerJob: Job? = null
    private var locationJob: Job? = null

    private fun computeVisibleSky(time: Instant, obs: ObserverLocation): VisibleSky {
        val jd = TimeEngine.instantToJulianDate(time)
        val lst = TimeEngine.lstRad(jd, obs.lonRad)
        val magLim = _magLimit.value.toDouble()
        val visibleStars = catalog.stars.filter { it.mag <= magLim }
        val starRaDecs = visibleStars.map { RaDec(it.ra, it.dec) }
        val starAltAz = CoordinateTransforms.batchRaDecToAltAz(starRaDecs, lst, obs.latRad)
        val stars = visibleStars.mapIndexed { i, s ->
            VisibleStar(s, starAltAz[i], starRaDecs[i])
        }

        val constLines = catalog.constellations.map { c ->
            ConstellationLine(c.abbr, c.name, c.lines)
        }

        val art = catalog.constellationArt.mapNotNull { a ->
            val resolved = a.anchors.map { an -> an to starIndex[an.hip] }
            if (resolved.any { it.second == null }) return@mapNotNull null
            VisibleArt(a.abbr, a.image, resolved.map { (an, st) ->
                val raDec = RaDec(st!!.ra, st.dec)
                val altAz = CoordinateTransforms.raDecToAltAz(raDec, lst, obs.latRad)
                VisibleArtAnchor(an.x.toFloat(), an.y.toFloat(), altAz)
            })
        }

        val deep = catalog.deepSky.map { obj ->
            val rd = RaDec(obj.ra, obj.dec)
            val aa = CoordinateTransforms.raDecToAltAz(rd, lst, obs.latRad)
            VisibleDeepSky(obj, aa, rd)
        }

        val sun = SolarCalculator.calc(jd).let { solar ->
            val aa = CoordinateTransforms.raDecToAltAz(solar.raDec, lst, obs.latRad)
            VisibleSun(aa, solar.raDec, solar.distanceAu)
        }

        val moon = LunarCalculator.calc(jd).let { lunar ->
            val aa = CoordinateTransforms.raDecToAltAz(lunar.raDec, lst, obs.latRad)
            VisibleMoon(aa, lunar.raDec, lunar.phase, lunar.illumination, lunar.ageDays)
        }

        val earthElem = catalog.earth
        val planets = if (earthElem != null) {
            PlanetaryCalculator.calcAll(catalog.planets, earthElem, jd).map { pr ->
                val aa = CoordinateTransforms.raDecToAltAz(pr.raDec, lst, obs.latRad)
                VisiblePlanet(pr.id, pr.name, aa, pr.raDec, pr.magnitude, pr.distanceAu)
            }
        } else emptyList()

        return VisibleSky(
            stars = stars,
            planets = planets,
            sun = sun,
            moon = moon,
            deepSky = deep,
            lstRad = lst,
            jd = jd,
            observer = obs,
            time = time,
            constellations = constLines,
            art = art
        )
    }

    fun getRaDecProvider(objectId: String): (Double) -> RaDec {
        return when {
            objectId == "SUN" -> { jd -> SolarCalculator.calc(jd).raDec }
            objectId == "MOON" -> { jd -> LunarCalculator.calc(jd).raDec }
            objectId.startsWith("PLANET_") -> {
                val pid = objectId.removePrefix("PLANET_")
                // Resolve inside the lambda, not at provider creation: a
                // provider minted before the catalog finished loading used
                // to capture the empty catalog and return RaDec(0, 0)
                // forever, freezing the trajectory/rise-set at the pole.
                val provider: (Double) -> RaDec = { jd ->
                    val planetElem = catalog.planets.firstOrNull { it.id == pid }
                    val earthElem = catalog.earth
                    if (planetElem != null && earthElem != null) {
                        PlanetaryCalculator.geocentricRaDec(planetElem, earthElem, jd).raDec
                    } else {
                        RaDec(0.0, 0.0)
                    }
                }
                provider
            }
            objectId.startsWith("STAR_") -> {
                val sid = objectId.removePrefix("STAR_").toIntOrNull()
                val provider: (Double) -> RaDec = { _ ->
                    val star = sid?.let { id -> catalog.stars.firstOrNull { it.id == id } }
                    if (star != null) RaDec(star.ra, star.dec) else RaDec(0.0, 0.0)
                }
                provider
            }
            else -> {
                val provider: (Double) -> RaDec = { _ ->
                    val dso = catalog.deepSky.firstOrNull { it.id == objectId }
                    if (dso != null) RaDec(dso.ra, dso.dec) else RaDec(0.0, 0.0)
                }
                provider
            }
        }
    }

    override fun selectObject(id: String) {
        _selectedObjectId.value = id
        updateTrajectoryForSelected()
        updateRiseSetForSelected()
    }

    fun clearSelection() {
        _selectedObjectId.value = null
        _trajectory.value = emptyList()
        _riseSet.value = null
    }

    fun enableAr(enabled: Boolean) {
        _isArMode.value = enabled
        if (enabled) {
            _viewCenter.value = CenterOption.DevicePointing
            _devicePointingEnabled.value = true
        }
    }

    init {
        loadPrefs()
        viewModelScope.launch {
            catalog.loadAll()
            starIndex = catalog.stars.associateBy { it.id }
            _catalogReady.value = true
            // An object can be selected (e.g. from Search) before the
            // catalog lands; refresh its derived data now that providers
            // can resolve it.
            updateTrajectoryForSelected()
            updateRiseSetForSelected()
        }
        viewModelScope.launch {
            orientationMgr.orientation.collect { _deviceOrientation.value = it }
        }
        orientationMgr.start()
        _viewCenter.value = CenterOption.DevicePointing
        _devicePointingEnabled.value = true
        startTicker()
        refreshLocation()
    }

    private fun loadPrefs() {
        val snapshot = prefs.load()
        _constellationMode.value = snapshot.constellationMode
        _showGrid.value = snapshot.showGrid
        _showDeepSky.value = snapshot.showDeepSky
        _showPlanets.value = snapshot.showPlanets
        _magLimit.value = snapshot.magLimit
        _nightMode.value = snapshot.nightMode
        _showBelowHorizon.value = snapshot.showBelowHorizon
        _fovDeg.value = snapshot.fovDeg
        // No still mode: always device pointing
        _devicePointingEnabled.value = true
        _viewCenter.value = CenterOption.DevicePointing
        val latDeg = snapshot.latDeg
        val lonDeg = snapshot.lonDeg
        if (latDeg != null && lonDeg != null) {
            _observer.value = ObserverLocation(latDeg.toRad(), lonDeg.toRad(), latDeg, lonDeg, 0.0)
        }
    }

    override fun setShowConstellations(mode: ConstellationMode) {
        _constellationMode.value = mode
        prefs.saveString("astro_const_mode", mode.name)
    }
    override fun setShowGrid(v: Boolean) {
        _showGrid.value = v
        prefs.saveBool("astro_show_grid", v)
    }
    override fun setShowDeepSky(v: Boolean) {
        _showDeepSky.value = v
        prefs.saveBool("astro_show_deep", v)
    }
    override fun setShowPlanets(v: Boolean) {
        _showPlanets.value = v
        prefs.saveBool("astro_show_planets", v)
    }
    override fun setNightMode(v: Boolean) {
        _nightMode.value = v
        prefs.saveBool("astro_night_mode", v)
    }
    fun setDevicePointing(v: Boolean) {
        _devicePointingEnabled.value = v
        prefs.saveBool("astro_device_pointing", v)
        if (v) {
            orientationMgr.start()
            _viewCenter.value = CenterOption.DevicePointing
        } else {
            orientationMgr.stop()
            _viewCenter.value = CenterOption.Manual(0.0, 45.0.toRad())
        }
    }
    override fun setShowBelowHorizon(v: Boolean) {
        _showBelowHorizon.value = v
        prefs.saveBool("astro_show_below", v)
    }

    override fun setMagLimit(v: Float) {
        _magLimit.value = v
        prefs.saveDouble("astro_mag_limit", v.toDouble())
    }
    override fun setFov(v: Float) {
        _fovDeg.value = v.coerceIn(MIN_FOV_DEG, MAX_FOV_DEG)
        prefs.saveDouble("astro_fov", v.toDouble())
    }
    override fun setManualLocation(latDeg: Double, lonDeg: Double) {
        _observer.value = ObserverLocation(latDeg.toRad(), lonDeg.toRad(), latDeg, lonDeg)
        prefs.saveDouble("astro_lat", latDeg)
        prefs.saveDouble("astro_lon", lonDeg)
        // Rise/set and trajectories are computed for the observer; without
        // this they kept showing the previous location's times until the
        // object was re-selected.
        updateTrajectoryForSelected()
        updateRiseSetForSelected()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (true) {
                if (_isLive.value) { _simTime.value = Clock.System.now() }
                delay(TICKER_DELAY_MS)
            }
        }
    }

    override fun refreshLocation() {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            if (!LocationProvider.hasPermission(context)) return@launch
            val loc = withContext(Dispatchers.IO) {
                try {
                    kotlinx.coroutines.withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                        LocationProvider.currentLocation(context)
                    }
                } catch (_: Exception) {
                    null
                }
            }
            loc?.let {
                val latDeg = it.latitude; val lonDeg = it.longitude
                _observer.value = ObserverLocation(latDeg.toRad(), lonDeg.toRad(), latDeg, lonDeg, it.altitude)
                prefs.saveDouble("astro_lat", latDeg); prefs.saveDouble("astro_lon", lonDeg)
                orientationMgr.updateLocation(latDeg, lonDeg, it.altitude.toFloat())
                updateTrajectoryForSelected()
                updateRiseSetForSelected()
            } ?: run { _observer.value?.let { obs -> orientationMgr.updateLocation(obs.latDeg, obs.lonDeg) } }
        }
    }

    override fun setTime(instant: Instant, live: Boolean) {
        _simTime.value = instant
        _isLive.value = live
        updateTrajectoryForSelected()
        updateRiseSetForSelected()
    }
    fun setLiveNow() { _simTime.value = Clock.System.now(); _isLive.value = true }

    // No still mode per request: always tracks phone. Pan disabled in device mode (only zoom allowed)

    private fun updateTrajectoryForSelected() {
        val id = _selectedObjectId.value ?: return
        val obs = _observer.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val jdNow = TimeEngine.instantToJulianDate(_simTime.value)
            val getRaDec = getRaDecProvider(id)
            val pts = mutableListOf<TrajectoryPoint>()
            for (i in -TRAJECTORY_HALF_STEPS..TRAJECTORY_HALF_STEPS) {
                val jd = jdNow + i * TRAJECTORY_STEP_DAYS
                val rd = getRaDec(jd)
                val lst = TimeEngine.lstRad(jd, obs.lonRad)
                pts.add(TrajectoryPoint(jd, CoordinateTransforms.raDecToAltAz(rd, lst, obs.latRad), rd))
            }
            _trajectory.value = pts
        }
    }

    private fun updateRiseSetForSelected() {
        val id = _selectedObjectId.value ?: return
        val obs = _observer.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val jd = TimeEngine.instantToJulianDate(_simTime.value)
            val jd0 = floor(jd - 0.5) + 0.5
            val getRaDec = getRaDecProvider(id)
            _riseSet.value = RiseSetCalculator.calc(jd0, obs.latRad, obs.lonRad, getRaDec)
        }
    }

    override fun search(query: String): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        val results = mutableListOf<SearchResult>()
        catalog.stars.filter { matchesQuery(it.name, q) || matchesQuery(it.properName, q) }
            .take(SEARCH_RESULT_LIMIT)
            .forEach {
                results.add(
                    SearchResult(
                        "STAR_${it.id}",
                        it.properName ?: it.name
                            ?: context.getString(
                                R.string.search_result_star_fallback,
                                it.id.toString(),
                            ),
                        context.getString(
                            R.string.search_result_star_mag,
                            it.mag.toString(),
                        ),
                        it.ra to it.dec,
                    ),
                )
            }
        PlanetId.entries.map { it to context.getString(it.displayNameRes) }
            .filter { (_, name) -> name.lowercase().contains(q) }
            .forEach { (planet, name) ->
                val pid = when (planet) {
                    PlanetId.SUN, PlanetId.MOON -> planet.name
                    else -> "PLANET_${planet.name}"
                }
                results.add(SearchResult(pid, name, context.getString(R.string.search_result_planet), null))
            }
        catalog.deepSky.filter { it.id.lowercase().contains(q) || it.name.lowercase().contains(q) }
            .take(SEARCH_RESULT_LIMIT)
            .forEach {
                results.add(SearchResult(it.id, "${it.id} ${it.name}", it.type, it.ra to it.dec))
            }
        catalog.constellations.filter { it.abbr.lowercase().contains(q) || it.name.lowercase().contains(q) }
            .forEach {
                results.add(
                    SearchResult(
                        "CONST_${it.abbr}",
                        it.name,
                        context.getString(R.string.search_result_constellation, it.abbr),
                        null,
                    ),
                )
            }
        return results
    }

    private fun matchesQuery(value: String?, q: String): Boolean =
        value?.lowercase()?.contains(q) == true

    override fun onCleared() { orientationMgr.stop(); tickerJob?.cancel(); locationJob?.cancel() }
}

/** Resolves the sky-map view center from explicit state (extracted for TooManyFunctions). */
internal fun resolveViewCenter(
    viewCenter: CenterOption,
    orient: com.vayunmathur.library.sensor.DeviceOrientation?,
): Pair<Double, Double> =
    when (viewCenter) {
        is CenterOption.DevicePointing -> {
            if (orient != null) Pair(orient.pointingAzRad, orient.pointingAltRad)
            else Pair(0.0, 45.0.toRad())
        }
        is CenterOption.Manual -> Pair(viewCenter.azRad, viewCenter.altRad)
        is CenterOption.TargetObject -> Pair(viewCenter.azRad, viewCenter.altRad)
    }

/** Resolves the sky-map view rotation from explicit state (extracted for TooManyFunctions). */
internal fun resolveViewRotation(
    viewCenter: CenterOption,
    orient: com.vayunmathur.library.sensor.DeviceOrientation?,
): Double =
    when (viewCenter) {
        is CenterOption.DevicePointing -> orient?.viewRotationRad ?: 0.0
        else -> 0.0
    }
