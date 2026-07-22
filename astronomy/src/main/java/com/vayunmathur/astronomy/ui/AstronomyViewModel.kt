package com.vayunmathur.astronomy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.astronomy.data.CatalogRepository
import com.vayunmathur.astronomy.domain.engine.*
import com.vayunmathur.astronomy.domain.sensor.DeviceOrientation
import com.vayunmathur.astronomy.domain.sensor.LocationProvider
import com.vayunmathur.astronomy.domain.sensor.OrientationManager
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.math.*

data class ObserverLocation(val latRad: Double, val lonRad: Double, val latDeg: Double, val lonDeg: Double, val altM: Double = 0.0)

sealed interface CenterOption {
    data object DevicePointing : CenterOption
    data class Manual(val azRad: Double, val altRad: Double) : CenterOption
    data class TargetObject(val objectId: String, val azRad: Double, val altRad: Double) : CenterOption
}

data class VisibleStar(val star: com.vayunmathur.astronomy.data.model.Star, val altAz: AltAz, val raDec: RaDec)
data class VisiblePlanet(val id: String, val name: String, val altAz: AltAz, val raDec: RaDec, val mag: Double?, val distanceAu: Double)
data class VisibleSun(val altAz: AltAz, val raDec: RaDec, val distanceAu: Double)
data class VisibleMoon(val altAz: AltAz, val raDec: RaDec, val phase: Double, val illumination: Double, val ageDays: Double)
data class VisibleDeepSky(val obj: com.vayunmathur.astronomy.data.model.DeepSkyObject, val altAz: AltAz, val raDec: RaDec)

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
    val constellations: List<ConstellationLine> = emptyList()
)

data class ConstellationLine(val abbr: String, val name: String, val segments: List<List<Int>>)
data class TrajectoryPoint(val jd: Double, val altAz: AltAz, val raDec: RaDec)
data class SearchResult(val id: String, val title: String, val subtitle: String, val raDec: Pair<Double, Double>?)

@OptIn(ExperimentalTime::class)
class AstronomyViewModel(app: Application) : AndroidViewModel(app) {
    private val context = getApplication<Application>()
    private val catalog = CatalogRepository(context)
    private val ds = DataStoreUtils.getInstance(context)
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

    private val _showConstellations = MutableStateFlow(true)
    val showConstellations: StateFlow<Boolean> = _showConstellations
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

    val visibleSky: StateFlow<VisibleSky> = combine(_simTime, _observer, _catalogReady) { args ->
        val time = args[0] as Instant
        val obs = args[1] as ObserverLocation?
        val ready = args[2] as Boolean
        if (!ready || obs == null) VisibleSky(observer = obs, time = time, jd = TimeEngine.instantToJulianDate(time))
        else computeVisibleSky(time, obs)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, VisibleSky())

    private var tickerJob: Job? = null
    private var locationJob: Job? = null

    private fun computeVisibleSky(time: Instant, obs: ObserverLocation): VisibleSky {
        val jd = TimeEngine.instantToJulianDate(time)
        val lst = TimeEngine.lstRad(jd, obs.lonRad)
        val magLim = _magLimit.value.toDouble()
        val stars = catalog.stars.asSequence()
            .filter { it.mag <= magLim }
            .map { s ->
                val rd = RaDec(s.ra, s.dec)
                val aa = CoordinateTransforms.raDecToAltAz(rd, lst, obs.latRad)
                VisibleStar(s, aa, rd)
            }.toList()

        val constLines = catalog.constellations.map { c ->
            ConstellationLine(c.abbr, c.name, c.lines)
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
            constellations = constLines
        )
    }

    fun getRaDecProvider(objectId: String, refJd: Double): (Double) -> RaDec {
        return when {
            objectId == "SUN" -> { jd -> SolarCalculator.calc(jd).raDec }
            objectId == "MOON" -> { jd -> LunarCalculator.calc(jd).raDec }
            objectId.startsWith("PLANET_") -> {
                val pid = objectId.removePrefix("PLANET_")
                val planetElem = catalog.planets.firstOrNull { it.id == pid }
                val earthElem = catalog.earth
                if (planetElem != null && earthElem != null) {
                    { jd -> PlanetaryCalculator.geocentricRaDec(planetElem, earthElem, jd).raDec }
                } else {
                    { _ -> RaDec(0.0, 0.0) }
                }
            }
            objectId.startsWith("STAR_") -> {
                val sid = objectId.removePrefix("STAR_").toIntOrNull()
                val star = sid?.let { id -> catalog.stars.firstOrNull { it.id == id } }
                if (star != null) {
                    { _ -> RaDec(star.ra, star.dec) }
                } else {
                    { _ -> RaDec(0.0, 0.0) }
                }
            }
            else -> {
                val dso = catalog.deepSky.firstOrNull { it.id == objectId }
                if (dso != null) {
                    { _ -> RaDec(dso.ra, dso.dec) }
                } else {
                    { _ -> RaDec(0.0, 0.0) }
                }
            }
        }
    }

    fun resolveCenter(): Pair<Double, Double> {
        return when (val vc = _viewCenter.value) {
            is CenterOption.DevicePointing -> {
                val orient = _deviceOrientation.value
                if (orient != null) Pair(orient.pointingAzRad, orient.pointingAltRad)
                else Pair(0.0, 45.0.toRad())
            }
            is CenterOption.Manual -> Pair(vc.azRad, vc.altRad)
            is CenterOption.TargetObject -> Pair(vc.azRad, vc.altRad)
        }
    }

    fun resolveRotation(): Double {
        return when (_viewCenter.value) {
            is CenterOption.DevicePointing -> _deviceOrientation.value?.viewRotationRad ?: 0.0
            else -> 0.0
        }
    }

    fun selectObject(id: String) {
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
        viewModelScope.launch { catalog.loadAll(); _catalogReady.value = true }
        viewModelScope.launch { orientationMgr.orientation.collect { _deviceOrientation.value = it } }
        orientationMgr.start()
        _viewCenter.value = CenterOption.DevicePointing
        _devicePointingEnabled.value = true
        startTicker()
        refreshLocation()
    }

    private fun loadPrefs() {
        _showConstellations.value = ds.getBoolean("astro_show_const", true)
        _showGrid.value = ds.getBoolean("astro_show_grid", true) // full-sphere grid per request
        _showDeepSky.value = ds.getBoolean("astro_show_deep", true)
        _showPlanets.value = ds.getBoolean("astro_show_planets", true)
        _magLimit.value = (ds.getDouble("astro_mag_limit")?.toFloat() ?: 6.0f)
        _nightMode.value = ds.getBoolean("astro_night_mode", false)
        _showBelowHorizon.value = ds.getBoolean("astro_show_below", true)
        _fovDeg.value = (ds.getDouble("astro_fov")?.toFloat() ?: 70f)
        // No still mode: always device pointing
        _devicePointingEnabled.value = true
        _viewCenter.value = CenterOption.DevicePointing
        ds.getDouble("astro_lat")?.let { latDeg -> ds.getDouble("astro_lon")?.let { lonDeg ->
            _observer.value = ObserverLocation(latDeg.toRad(), lonDeg.toRad(), latDeg, lonDeg, 0.0)
        } }
    }

    private fun saveBool(key: String, v: Boolean) { viewModelScope.launch { ds.setBoolean(key, v) } }
    private fun saveDouble(key: String, v: Double) { viewModelScope.launch { ds.setDouble(key, v) } }

    fun setShowConstellations(v: Boolean) { _showConstellations.value = v; saveBool("astro_show_const", v) }
    fun setShowGrid(v: Boolean) { _showGrid.value = v; saveBool("astro_show_grid", v) }
    fun setShowDeepSky(v: Boolean) { _showDeepSky.value = v; saveBool("astro_show_deep", v) }
    fun setShowPlanets(v: Boolean) { _showPlanets.value = v; saveBool("astro_show_planets", v) }
    fun setNightMode(v: Boolean) { _nightMode.value = v; saveBool("astro_night_mode", v) }
    fun setDevicePointing(v: Boolean) {
        _devicePointingEnabled.value = v; saveBool("astro_device_pointing", v)
        if (v) { orientationMgr.start(); _viewCenter.value = CenterOption.DevicePointing } else { orientationMgr.stop(); _viewCenter.value = CenterOption.Manual(0.0, 45.0.toRad()) }
    }
    fun setShowBelowHorizon(v: Boolean) { _showBelowHorizon.value = v; saveBool("astro_show_below", v) }

    fun setMagLimit(v: Float) { _magLimit.value = v; saveDouble("astro_mag_limit", v.toDouble()) }
    fun setFov(v: Float) { _fovDeg.value = v.coerceIn(10f, 120f); saveDouble("astro_fov", v.toDouble()) }
    fun setManualLocation(latDeg: Double, lonDeg: Double) {
        _observer.value = ObserverLocation(latDeg.toRad(), lonDeg.toRad(), latDeg, lonDeg)
        saveDouble("astro_lat", latDeg); saveDouble("astro_lon", lonDeg)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch { while (true) { if (_isLive.value) _simTime.value = Clock.System.now(); delay(1000) } }
    }

    fun refreshLocation() {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            if (!LocationProvider.hasPermission(context)) return@launch
            val loc = withContext(Dispatchers.IO) { try { kotlinx.coroutines.withTimeoutOrNull(8000) { LocationProvider.currentLocation(context) } } catch (_: Exception) { null } }
            loc?.let {
                val latDeg = it.latitude; val lonDeg = it.longitude
                _observer.value = ObserverLocation(latDeg.toRad(), lonDeg.toRad(), latDeg, lonDeg, it.altitude)
                saveDouble("astro_lat", latDeg); saveDouble("astro_lon", lonDeg)
                orientationMgr.updateLocation(latDeg, lonDeg, it.altitude.toFloat())
            } ?: run { _observer.value?.let { obs -> orientationMgr.updateLocation(obs.latDeg, obs.lonDeg) } }
        }
    }

    fun setTime(instant: Instant, live: Boolean = false) { _simTime.value = instant; _isLive.value = live; updateTrajectoryForSelected(); updateRiseSetForSelected() }
    fun setLiveNow() { _simTime.value = Clock.System.now(); _isLive.value = true }

    // No still mode per request: always tracks phone. Pan disabled in device mode (only zoom allowed)
    fun onPan(deltaAzDeg: Double, deltaAltDeg: Double) { /* no still mode – always tracks phone */ }

    private fun updateTrajectoryForSelected() {
        val id = _selectedObjectId.value ?: return
        val obs = _observer.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val jdNow = TimeEngine.instantToJulianDate(_simTime.value)
            val getRaDec = getRaDecProvider(id, jdNow)
            val pts = mutableListOf<TrajectoryPoint>()
            for (i in -48..48) {
                val jd = jdNow + i * 0.25 / 24.0
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
            val getRaDec = getRaDecProvider(id, jd)
            _riseSet.value = RiseSetCalculator.calc(jd0, obs.latRad, obs.lonRad, getRaDec)
        }
    }

    fun getCatalog() = catalog

    fun search(query: String): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        val results = mutableListOf<SearchResult>()
        catalog.stars.filter { (it.name?.lowercase()?.contains(q) == true) || (it.properName?.lowercase()?.contains(q) == true) }.take(20).forEach {
            results.add(SearchResult("STAR_${it.id}", it.properName ?: it.name ?: "Star ${it.id}", "Star mag ${it.mag}", it.ra to it.dec))
        }
        listOf("Sun","Moon","Mercury","Venus","Mars","Jupiter","Saturn","Uranus","Neptune").filter { it.lowercase().contains(q) }.forEach {
            val pid = if (it=="Sun") "SUN" else if (it=="Moon") "MOON" else "PLANET_${it.uppercase()}"
            results.add(SearchResult(pid, it, "Planet", null))
        }
        catalog.deepSky.filter { it.id.lowercase().contains(q) || it.name.lowercase().contains(q) }.take(20).forEach {
            results.add(SearchResult(it.id, "${it.id} ${it.name}", it.type, it.ra to it.dec))
        }
        catalog.constellations.filter { it.abbr.lowercase().contains(q) || it.name.lowercase().contains(q) }.forEach {
            results.add(SearchResult("CONST_${it.abbr}", it.name, "Constellation ${it.abbr}", null))
        }
        return results
    }

    override fun onCleared() { super.onCleared(); orientationMgr.stop(); tickerJob?.cancel(); locationJob?.cancel() }
}
