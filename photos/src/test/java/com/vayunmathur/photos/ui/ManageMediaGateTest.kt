package com.vayunmathur.photos.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ManageMediaGate]: MANAGE_MEDIA is deferred from app start, so a
 * trash/delete/move action runs immediately when granted, is held (and the
 * settings ask launched) when not, then runs on a granted return or is
 * dropped with a denial notice on a refused one.
 */
class ManageMediaGateTest {
    private class Probe {
        var granted = false
        var launches = 0
        var denials = 0
        val ran = mutableListOf<String>()
    }

    private fun gate(probe: Probe) = ManageMediaGate(
        isGranted = { probe.granted },
        launchRequest = { probe.launches++ },
        onDenied = { probe.denials++ },
    )

    @Test
    fun grantedRunsImmediatelyWithoutAsking() {
        val probe = Probe().apply { granted = true }
        gate(probe).run { probe.ran += "trash" }
        assertEquals(listOf("trash"), probe.ran)
        assertEquals(0, probe.launches)
        assertEquals(0, probe.denials)
    }

    @Test
    fun ungrantedDefersThenRunsOnceWhenGrantLands() {
        val probe = Probe()
        val gate = gate(probe)
        gate.run { probe.ran += "move" }
        assertEquals(emptyList(), probe.ran)
        assertEquals(1, probe.launches)
        probe.granted = true
        gate.onRequestReturned()
        assertEquals(listOf("move"), probe.ran)
        assertEquals(0, probe.denials)
        // The held action is consumed: a second resume must not replay it.
        gate.onRequestReturned()
        assertEquals(listOf("move"), probe.ran)
    }

    @Test
    fun ungrantedStillDeniedDropsActionAndExplains() {
        val probe = Probe()
        val gate = gate(probe)
        gate.run { probe.ran += "delete" }
        gate.onRequestReturned()
        assertEquals(emptyList(), probe.ran)
        assertEquals(1, probe.denials)
        // Nothing remains pending after a denial.
        probe.granted = true
        gate.onRequestReturned()
        assertEquals(emptyList(), probe.ran)
        assertEquals(1, probe.denials)
    }

    @Test
    fun returnWithNothingPendingIsNoOp() {
        val probe = Probe().apply { granted = true }
        gate(probe).onRequestReturned()
        assertEquals(0, probe.denials)
        assertEquals(emptyList(), probe.ran)
    }

    @Test
    fun latestActionReplacesOneStillWaiting() {
        val probe = Probe()
        val gate = gate(probe)
        gate.run { probe.ran += "stale" }
        gate.run { probe.ran += "fresh" }
        probe.granted = true
        gate.onRequestReturned()
        assertEquals(listOf("fresh"), probe.ran)
    }
}
