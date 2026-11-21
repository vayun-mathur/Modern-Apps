package com.vayunmathur.findfamily.domain

/** Stage of the staged first-run flow, derived from family + permission state. */
enum class FirstRunStage {
    /** Explain the app and pair/add a family member. No permission is needed. */
    PairFamily,

    /** Family exists; ask for location only now that sharing is being enabled. */
    EnableLocationSharing,

    /** Nothing left to stage: family exists and location sharing can run. */
    Done,
}

/**
 * Pure first-run staging decisions (no Android / Compose), so the order the
 * permissions move to is unit-testable without launching the activity.
 *
 * A user is on the first run only while they have no family member yet, have
 * not finished this flow before, and are not already fully granted. Anyone
 * else — a returning user with family, a finished flow, or the
 * already-granted fast path — keeps the pre-existing gate behaviour exactly.
 */
object FirstRun {

    /** DataStore key marking that the staged first-run flow was finished. */
    const val COMPLETED_KEY = "first_run_completed"

    /** True only for a brand-new user who still needs the staged flow. */
    fun isFirstRun(
        hasFamily: Boolean,
        firstRunCompleted: Boolean,
        allGatePermissionsGranted: Boolean,
    ): Boolean = !hasFamily && !firstRunCompleted && !allGatePermissionsGranted

    /** The stage to show for the given family / location state. */
    fun stage(hasFamily: Boolean, locationSharingGranted: Boolean): FirstRunStage = when {
        !hasFamily -> FirstRunStage.PairFamily
        !locationSharingGranted -> FirstRunStage.EnableLocationSharing
        else -> FirstRunStage.Done
    }
}
