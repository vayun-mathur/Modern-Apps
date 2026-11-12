package com.vayunmathur.communicate.domain

/** The device ringer switch, as `AudioManager.getRingerMode()` reports it. */
enum class RingerMode { Silent, Vibrate, Normal }

/**
 * Everything that decides how one incoming call alerts, already read from the system.
 *
 * @property ringVolumeAudible whether the ring stream volume is above zero.
 * @property vibrateWhenRinging the "vibrate for calls" system setting; only consulted in [RingerMode.Normal],
 *   since [RingerMode.Vibrate] always vibrates and [RingerMode.Silent] never does.
 * @property callerAllowedByDnd whether Do Not Disturb lets this caller through (true when DND is off).
 * @property otherCallLive whether another call is already up, which makes this one a call-waiting call.
 */
data class RingConditions(
    val ringerMode: RingerMode,
    val ringVolumeAudible: Boolean,
    val vibrateWhenRinging: Boolean,
    val callerAllowedByDnd: Boolean,
    val otherCallLive: Boolean,
)

/** What the ringer should do for one incoming call. All false means the call alerts visually only. */
data class RingPlan(
    val ringtone: Boolean,
    val vibrate: Boolean,
    val callWaitingTone: Boolean,
) {
    /** Whether nothing audible or haptic plays. */
    val isSilent: Boolean get() = !ringtone && !vibrate && !callWaitingTone

    /** Named plans, so call sites and tests read as the behaviour rather than three booleans. */
    companion object {
        val Silent = RingPlan(ringtone = false, vibrate = false, callWaitingTone = false)
        val VibrateOnly = RingPlan(ringtone = false, vibrate = true, callWaitingTone = false)
        val CallWaiting = RingPlan(ringtone = false, vibrate = false, callWaitingTone = true)
    }
}

/**
 * Decide how an incoming call alerts, following AOSP Telecom's `Ringer`:
 *
 * - A caller Do Not Disturb filters out gets nothing at all (the notification still shows).
 * - A second call while another is live gets only the call-waiting tone, which plays in the earpiece
 *   of the call already up — never the full ringtone, and no vibration. The ringer switch does not
 *   silence it, because it is in-call audio rather than a ring.
 * - Otherwise the ringer switch rules: silent plays nothing, vibrate only vibrates, and normal plays
 *   the ringtone (when the ring volume is above zero) and vibrates when "vibrate for calls" is on.
 */
fun ringPlan(conditions: RingConditions): RingPlan = when {
    !conditions.callerAllowedByDnd -> RingPlan.Silent
    conditions.otherCallLive -> RingPlan.CallWaiting
    conditions.ringerMode == RingerMode.Silent -> RingPlan.Silent
    conditions.ringerMode == RingerMode.Vibrate -> RingPlan.VibrateOnly
    else -> RingPlan(
        ringtone = conditions.ringVolumeAudible,
        vibrate = conditions.vibrateWhenRinging,
        callWaitingTone = false,
    )
}

/** The Do Not Disturb interruption filter, as `NotificationManager.getCurrentInterruptionFilter()` reports it. */
enum class DndMode { Off, PriorityOnly, AlarmsOnly, TotalSilence }

/** Which callers a priority-only Do Not Disturb policy lets through. */
enum class DndCallers { None, Anyone, Contacts, Starred }

/**
 * A Do Not Disturb policy, reduced to what matters for calls.
 *
 * @property callsAllowed whether the policy's priority categories include calls at all.
 */
data class DndPolicy(
    val mode: DndMode,
    val callsAllowed: Boolean,
    val callers: DndCallers,
)

/**
 * Whether [policy] lets a call through, for when the platform's own caller check
 * (`NotificationManager.matchesCallFilter`) cannot answer — no dialable number, or the check is
 * unavailable. Repeat callers cannot be judged from here, so they only break through via that check.
 */
fun dndAllowsCall(policy: DndPolicy, isContact: Boolean, isStarred: Boolean): Boolean = when (policy.mode) {
    DndMode.Off -> true
    DndMode.AlarmsOnly, DndMode.TotalSilence -> false
    DndMode.PriorityOnly -> policy.callsAllowed && when (policy.callers) {
        DndCallers.Anyone -> true
        DndCallers.Contacts -> isContact || isStarred
        DndCallers.Starred -> isStarred
        DndCallers.None -> false
    }
}

private val DIALABLE_NUMBER = Regex("""\+?\d{3,15}""")

/** Formatting a stored or typed number may carry; stripped before the digits are checked. */
private const val NUMBER_PUNCTUATION = " -()."

/**
 * The phone number inside a line-specific peer id, or null when the peer has none.
 *
 * Google Voice and SIM peers are plain numbers; WhatsApp peers are JIDs (`15551234567@s.whatsapp.net`,
 * with an optional `:device` suffix); Signal peers are an ACI (a UUID) or an E.164 number. WhatsApp
 * `@lid` ids are opaque digits, not numbers, so they are rejected. The number feeds the contact lookup
 * (custom ringtone, starred) and the Do Not Disturb caller check.
 */
fun dialableNumberOrNull(peerId: String): String? {
    val trimmed = peerId.trim()
    val domain = trimmed.substringAfter('@', missingDelimiterValue = "")
    if (domain == "lid") return null
    val user = trimmed.substringBefore('@').substringBefore(':')
    val compact = user.filterNot { it in NUMBER_PUNCTUATION }
    return compact.takeIf { DIALABLE_NUMBER.matches(it) }
}
