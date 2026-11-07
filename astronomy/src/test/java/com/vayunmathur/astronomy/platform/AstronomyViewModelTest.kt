package com.vayunmathur.astronomy.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.astronomy.domain.engine.RaDec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
class AstronomyViewModelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) { if (cond()) return; Thread.sleep(50) }
        assertTrue(cond(), "timed out: $desc")
    }

    @Test fun `catalog loads and visible sky computes for a manual location`() {
        val vm = AstronomyViewModel(app)
        await("catalog ready") { vm.catalogReady.value }
        assertTrue(vm.catalog.stars.isNotEmpty())
        vm.setManualLocation(37.0, -122.0)
        assertEquals(37.0, vm.observer.value?.latDeg)
        await("visible sky") { vm.visibleSky.value.stars.isNotEmpty() }
        val sky = vm.visibleSky.value
        assertNotNull(sky.sun); assertNotNull(sky.moon)
        assertTrue(sky.planets.isNotEmpty())
        assertTrue(sky.deepSky.isNotEmpty())
    }

    @Test fun `settings setters clamp and persist state`() {
        val vm = AstronomyViewModel(app)
        vm.setFov(500f); assertEquals(120f, vm.fovDeg.value)
        vm.setFov(1f); assertEquals(10f, vm.fovDeg.value)
        vm.setFov(70f); assertEquals(70f, vm.fovDeg.value)
        vm.setShowGrid(true); assertTrue(vm.showGrid.value)
        vm.setShowDeepSky(false); assertEquals(false, vm.showDeepSky.value)
        vm.setShowPlanets(false); assertEquals(false, vm.showPlanets.value)
        vm.setNightMode(true); assertTrue(vm.nightMode.value)
        vm.setShowBelowHorizon(false); assertEquals(false, vm.showBelowHorizon.value)
        vm.setMagLimit(4.5f); assertEquals(4.5f, vm.magLimit.value)
        vm.setShowConstellations(ConstellationMode.OFF)
        assertEquals(ConstellationMode.OFF, vm.constellationMode.value)
        vm.setDevicePointing(false)
        assertTrue(vm.viewCenter.value is CenterOption.Manual)
        vm.setDevicePointing(true)
        assertTrue(vm.viewCenter.value is CenterOption.DevicePointing)
        vm.enableAr(true)
        assertTrue(vm.isArMode.value)
    }

    @Test fun `selection computes trajectory and rise-set, clear resets`() {
        val vm = AstronomyViewModel(app)
        await("catalog") { vm.catalogReady.value }
        vm.setManualLocation(37.0, -122.0)
        vm.selectObject("SUN")
        assertEquals("SUN", vm.selectedObjectId.value)
        await("trajectory") { vm.trajectory.value.isNotEmpty() }
        assertEquals(97, vm.trajectory.value.size)
        await("riseSet") { vm.riseSet.value != null }
        vm.clearSelection()
        assertNull(vm.selectedObjectId.value)
        assertTrue(vm.trajectory.value.isEmpty())
        assertNull(vm.riseSet.value)
    }

    @Test fun `search finds planets deep sky and stars, blank is empty`() {
        val vm = AstronomyViewModel(app)
        await("catalog") { vm.catalogReady.value }
        assertTrue(vm.search("").isEmpty())
        assertTrue(vm.search("   ").isEmpty())
        val mars = vm.search("Mars")
        assertTrue(mars.any { it.id == "PLANET_MARS" }, "got $mars")
        val m31 = vm.search("M31")
        assertTrue(m31.isNotEmpty())
        assertTrue(vm.search("zzzz-no-match").none { it.id.startsWith("STAR_") && it.title.contains("zzzz") })
        // RaDec providers resolve known and unknown ids without crashing
        assertNotNull(vm.getRaDecProvider("SUN")(2460000.5))
        assertEquals(RaDec(0.0, 0.0), vm.getRaDecProvider("STAR_999999")(2460000.5))
        assertEquals(RaDec(0.0, 0.0), vm.getRaDecProvider("nope")(2460000.5))
    }

    @Test fun `magnitude limit change recomputes the sky while time is paused`() {
        val vm = AstronomyViewModel(app)
        await("catalog ready") { vm.catalogReady.value }
        vm.setManualLocation(37.0, -122.0)
        // Pause time so the one-second live ticker cannot mask a missing
        // recompute: only a magLimit-driven emission can change the sky now.
        vm.setTime(Instant.fromEpochMilliseconds(1_700_000_000_000), live = false)
        await("visible sky") { vm.visibleSky.value.stars.isNotEmpty() }
        val before = vm.visibleSky.value.stars.size
        vm.setMagLimit(1.0f)
        await("sky filtered by new mag limit") {
            val stars = vm.visibleSky.value.stars
            stars.size < before && stars.all { it.star.mag <= 1.0 }
        }
    }

    @Test fun `rise-set recomputes when the observer moves`() {
        val vm = AstronomyViewModel(app)
        await("catalog ready") { vm.catalogReady.value }
        vm.setManualLocation(37.0, -122.0)
        vm.selectObject("SUN")
        await("riseSet") { vm.riseSet.value?.riseJd != null }
        val firstRise = vm.riseSet.value!!.riseJd
        // Sydney sunrise is many hours away from San Francisco's; a stale
        // rise-set would keep returning the first value forever.
        vm.setManualLocation(-33.86, 151.21)
        await("riseSet for new location") {
            val rise = vm.riseSet.value?.riseJd
            rise != null && rise != firstRise
        }
    }

    @Test fun `time scrubbing leaves live mode and live-now restores it`() {
        val vm = AstronomyViewModel(app)
        val fixed = Instant.fromEpochMilliseconds(1_700_000_000_000)
        vm.setTime(fixed, live = false)
        assertEquals(fixed, vm.simTime.value)
        assertEquals(false, vm.isLive.value)
        vm.setLiveNow()
        assertTrue(vm.isLive.value)
    }

    @Test fun `catalog repository loads built-in fallback and is idempotent`() = runBlocking {
        val repo = com.vayunmathur.astronomy.data.CatalogRepository(app)
        repo.loadAll()
        val stars = repo.stars.size
        assertTrue(stars > 0)
        assertTrue(repo.constellations.isNotEmpty())
        assertTrue(repo.deepSky.isNotEmpty())
        assertNotNull(repo.earth)
        repo.loadAll()
        assertEquals(stars, repo.stars.size)
    }

    @Test fun `view center and rotation resolution`() {
        assertEquals(0.0 to 45.0 * Math.PI / 180.0, resolveViewCenter(CenterOption.DevicePointing, null).let { it.first to it.second })
        val manual = resolveViewCenter(CenterOption.Manual(1.0, 2.0), null)
        assertEquals(1.0 to 2.0, manual.first to manual.second)
        assertEquals(0.0, resolveViewRotation(CenterOption.Manual(1.0, 2.0), null))
        assertEquals(0.0, resolveViewRotation(CenterOption.DevicePointing, null))
    }
}
