package com.vayunmathur.updater.domain

/**
 * Whether a background run may go ahead and pull an update down.
 *
 * The metered guard is about the user's data bill, not about safety — a full package is one to
 * two gigabytes and downloading that over a phone plan without being asked is a real cost. It
 * has nothing to do with whether the update is trustworthy; that question is settled by the
 * signature check, much later and unconditionally.
 *
 * A manual "check now" from the UI does not come through here. The user asking for it is the
 * consent this is standing in for.
 */
object AutoInstallPolicy {

    /** Inputs to the background auto-install decision: user settings and network state. */
    data class Conditions(
        /** The user's setting. Default on. */
        val autoInstall: Boolean,
        /** The user's setting. Default off. */
        val meteredAllowed: Boolean,
        /** Whether the active network bills by the byte. */
        val networkMetered: Boolean,
        /** Whether there is an active network at all. */
        val connected: Boolean,
    )

    /** Outcome of the background auto-install policy check. */
    sealed interface Decision {
        /** Background download and install may proceed. */
        data object Proceed : Decision

        /** Not now. [reason] is for the log, not the UI — nothing was asked, so nothing is told. */
        data class Hold(val reason: String) : Decision
    }

    fun decide(conditions: Conditions): Decision = when {
        !conditions.connected -> Decision.Hold("no network")
        !conditions.autoInstall -> Decision.Hold("automatic installation is off")
        conditions.networkMetered && !conditions.meteredAllowed ->
            Decision.Hold("the connection is metered and mobile data is not allowed")

        else -> Decision.Proceed
    }
}
