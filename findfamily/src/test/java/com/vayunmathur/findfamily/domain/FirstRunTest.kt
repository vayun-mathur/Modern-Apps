package com.vayunmathur.findfamily.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Staged first-run decisions: stage order and who counts as first-run. */
class FirstRunTest {

    @Test fun newUserWithNoFamilyAndNoPermissionsIsFirstRun() {
        assertTrue(FirstRun.isFirstRun(hasFamily = false, firstRunCompleted = false, allGatePermissionsGranted = false))
    }

    @Test fun alreadyGrantedUserIsNotFirstRun() {
        // Already-granted fast path: identical to before, no sheet.
        assertFalse(FirstRun.isFirstRun(hasFamily = false, firstRunCompleted = false, allGatePermissionsGranted = true))
    }

    @Test fun returningUserWithFamilyIsNotFirstRun() {
        assertFalse(FirstRun.isFirstRun(hasFamily = true, firstRunCompleted = false, allGatePermissionsGranted = false))
    }

    @Test fun finishedFlowIsNotFirstRun() {
        assertFalse(FirstRun.isFirstRun(hasFamily = false, firstRunCompleted = true, allGatePermissionsGranted = false))
    }

    @Test fun pairStageComesBeforeLocation() {
        assertEquals(FirstRunStage.PairFamily, FirstRun.stage(hasFamily = false, locationSharingGranted = false))
        // Even with location already granted, pairing still comes first.
        assertEquals(FirstRunStage.PairFamily, FirstRun.stage(hasFamily = false, locationSharingGranted = true))
    }

    @Test fun locationStageOnlyAfterFamilyExists() {
        assertEquals(FirstRunStage.EnableLocationSharing, FirstRun.stage(hasFamily = true, locationSharingGranted = false))
    }

    @Test fun doneWhenFamilyAndLocationPresent() {
        assertEquals(FirstRunStage.Done, FirstRun.stage(hasFamily = true, locationSharingGranted = true))
    }
}
