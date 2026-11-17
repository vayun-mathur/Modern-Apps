package com.vayunmathur.contacts.data

import com.vayunmathur.contacts.platform.ContactPlatforms

/** Which header button is being resolved: call, message, or video. */
enum class CommAction { CALL, MESSAGE, VIDEO }

/**
 * Pure decision logic for the single shared [CommPref].
 * No Android dependencies so it stays unit-testable on the JVM.
 */
object CommPrefResolver {
    /**
     * Platforms the picker may offer for [action], in Signal > WhatsApp > SIM
     * priority order. SIM/system is always available for call and message;
     * video has no system option, so it lists only installed platforms.
     * Meet is video-only and only when installed.
     */
    fun availablePlatformsFor(
        action: CommAction,
        platforms: ContactPlatforms,
        isGoogleMeetInstalled: Boolean,
    ): List<CommPlatform> {
        val out = mutableListOf<CommPlatform>()
        when (action) {
            CommAction.CALL -> {
                if (platforms.signalCallId != null) out += CommPlatform.SIGNAL
                if (platforms.whatsAppCallId != null) out += CommPlatform.WHATSAPP
                if (platforms.telegramCallId != null) out += CommPlatform.TELEGRAM
                out += CommPlatform.SIM
            }
            CommAction.MESSAGE -> {
                if (platforms.signalMessageId != null) out += CommPlatform.SIGNAL
                if (platforms.whatsAppMessageId != null) out += CommPlatform.WHATSAPP
                if (platforms.telegramMessageId != null) out += CommPlatform.TELEGRAM
                out += CommPlatform.SIM
            }
            CommAction.VIDEO -> {
                if (isGoogleMeetInstalled) out += CommPlatform.MEET
                if (platforms.whatsAppVideoId != null) out += CommPlatform.WHATSAPP
                if (platforms.signalVideoId != null) out += CommPlatform.SIGNAL
                if (platforms.telegramVideoId != null) out += CommPlatform.TELEGRAM
            }
        }
        return out
    }

    /**
     * True when [pref] can be launched directly for [action]: its number still
     * exists on the contact (normalized compare) and its platform is offered
     * for [action] right now.
     */
    fun isPrefValidFor(
        pref: CommPref?,
        action: CommAction,
        phoneNumbers: List<PhoneNumber>,
        platforms: ContactPlatforms,
        isGoogleMeetInstalled: Boolean,
    ): Boolean {
        if (pref == null || pref.number.isBlank()) return false
        if (phoneNumbers.none { normalize(it.number) == normalize(pref.number) }) return false
        return availablePlatformsFor(action, platforms, isGoogleMeetInstalled)
            .contains(pref.platform)
    }

    /**
     * True when the platform picker can be skipped entirely: only SIM is
     * available, so there is nothing to choose (per request: if neither
     * WhatsApp nor Signal is available, just pick SIM).
     */
    fun isSimOnly(
        action: CommAction,
        platforms: ContactPlatforms,
        isGoogleMeetInstalled: Boolean,
    ): Boolean = availablePlatformsFor(action, platforms, isGoogleMeetInstalled) ==
        listOf(CommPlatform.SIM)

    internal fun normalize(raw: String): String =
        raw.filter { it.isDigit() || it == '+' }.trim()
}
