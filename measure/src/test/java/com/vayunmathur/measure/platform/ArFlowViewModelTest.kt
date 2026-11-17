package com.vayunmathur.measure.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.measure.data.model.TrackingQuality
import com.vayunmathur.measure.domain.ArFlowState
import com.vayunmathur.measure.domain.HitCandidate
import com.vayunmathur.measure.domain.HitKind
import com.vayunmathur.measure.domain.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArFlowViewModelTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun readyVm(): MeasureViewModel {
        val vm = MeasureViewModel(app)
        vm.setCameraPermission(true)
        vm.onSessionChanged(42L)
        vm.onTrackingUpdate(TrackingQuality.Good, hasPlane = true)
        return vm
    }

    @Test fun reticleHitMakesFlowReadyAndPlacementUsesIt() {
        val vm = readyVm()
        assertEquals(ArFlowState.Searching, vm.ar.value.flowState)
        vm.hitProvider = FakeArHitProvider(
            HitCandidate(HitKind.Plane, Vec3(0.0, 0.0, 0.0), 1.5)
        )
        vm.refreshReticle()
        assertIs<ArFlowState.Ready>(vm.ar.value.flowState)
        vm.placeAtReticle()
        assertEquals(1, vm.ar.value.anchors.size)
        assertTrue(vm.ar.value.anchors.single().onPlane)

        // A second reticle point completes a segment + polyline total.
        vm.onReticleHit(HitCandidate(HitKind.Plane, Vec3(3.0, 4.0, 0.0), 5.0))
        vm.placeAtReticle()
        assertEquals(5.0, vm.ar.value.distanceM)
        assertEquals(5.0, vm.ar.value.totalDistanceM)
    }

    @Test fun placementIsRefusedWithoutASolidHit() {
        val vm = readyVm()
        vm.placeAtReticle() // Searching: no hit, no anchor, no crash.
        assertTrue(vm.ar.value.anchors.isEmpty())

        // Limited tracking must never place, even with a hit in hand.
        vm.onTrackingUpdate(TrackingQuality.Limited, hasPlane = true)
        vm.onReticleHit(HitCandidate(HitKind.FeaturePoint, Vec3(1.0, 0.0, 0.0), 1.0))
        assertIs<ArFlowState.Scanning>(vm.ar.value.flowState)
        vm.placeAtReticle()
        assertTrue(vm.ar.value.anchors.isEmpty())
    }

    @Test fun anchorsAreRemovableIndividually() {
        val vm = readyVm()
        vm.addResolvedAnchor(0.0, 0.0, 0.0, onPlane = true)
        vm.addResolvedAnchor(1.0, 0.0, 0.0, onPlane = true)
        vm.addResolvedAnchor(2.0, 0.0, 0.0, onPlane = true)
        val middle = vm.ar.value.anchors[1]
        vm.removeAnchor(middle.id)
        assertEquals(2, vm.ar.value.anchors.size)
        assertEquals(2.0, vm.ar.value.distanceM) // remaining points are 2 m apart
        assertNull(vm.ar.value.areaM2)
    }
}
