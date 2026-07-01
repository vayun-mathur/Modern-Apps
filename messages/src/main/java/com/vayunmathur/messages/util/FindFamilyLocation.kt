package com.vayunmathur.messages.util

import android.content.Context
import android.content.Intent
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Caller-side integration with the FindFamily app's link-creation intent.
 *
 * The Location composer action mints a temporary location-sharing link
 * *at send time* and sends the returned URL as a normal message. Link
 * creation is delegated to FindFamily via its assistant-intent contract
 * (JSON `DATA` in, JSON `RESPONSE_DATA` out) so the private key never
 * leaves that app.
 *
 * Note: the target [CLASS] is an external dependency owned by the
 * FindFamily app, not this module. If it isn't present, [isAvailable]
 * returns false and the UI degrades gracefully.
 */
object FindFamilyLocation {

    const val PACKAGE = "com.vayunmathur.findfamily"
    const val CLASS = "com.vayunmathur.findfamily.intents.CreateLinkIntent"

    /** Input payload — mirrors FindFamily's expected `DATA` shape. */
    @Serializable
    data class CreateLinkData(val name: String, val expiryMillis: Long)

    /** How long a freshly-minted sharing link stays active. */
    data class DurationOption(val label: String, val millis: Long)

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    /** Mirrors FindFamily's in-app expiry dropdown. */
    val DURATION_OPTIONS: List<DurationOption> = listOf(
        DurationOption("15 minutes", 15 * MIN),
        DurationOption("30 minutes", 30 * MIN),
        DurationOption("1 hour", HOUR),
        DurationOption("2 hours", 2 * HOUR),
        DurationOption("4 hours", 4 * HOUR),
        DurationOption("6 hours", 6 * HOUR),
        DurationOption("12 hours", 12 * HOUR),
        DurationOption("1 day", DAY),
        DurationOption("2 days", 2 * DAY),
        DurationOption("1 week", 7 * DAY),
    )

    /** True iff the FindFamily link-creation activity is installed/resolvable. */
    fun isAvailable(context: Context): Boolean =
        buildIntent("", 0L).resolveActivity(context.packageManager) != null

    /** Build the explicit intent that asks FindFamily to mint a link. */
    fun buildIntent(name: String, expiryMillis: Long): Intent =
        Intent().apply {
            setClassName(PACKAGE, CLASS)
            putExtra(
                "DATA",
                Json.encodeToString(
                    CreateLinkData.serializer(),
                    CreateLinkData(name, expiryMillis),
                ),
            )
        }

    /**
     * Extract the share URL from a CreateLinkIntent result, or null if the
     * intent returned nothing usable. The activity JSON-encodes its String
     * output, so the raw extra is a quoted JSON string.
     */
    fun parseResult(data: Intent?): String? {
        val raw = data?.getStringExtra("RESPONSE_DATA") ?: return null
        return runCatching { Json.decodeFromString(String.serializer(), raw) }
            .getOrNull()
            ?.takeIf { it.startsWith("http") }
    }
}
