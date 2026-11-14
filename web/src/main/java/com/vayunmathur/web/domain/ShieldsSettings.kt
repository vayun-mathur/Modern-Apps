package com.vayunmathur.web.domain

/**
 * How hard shields work on a site. Mirrors Brave's block-mode selector.
 *
 * [AGGRESSIVE] is the default: it additionally farbles timezone and disables WebRTC,
 * both of which are effective against fingerprinting but break a minority of sites —
 * hence the per-site override in the shields panel.
 */
enum class ShieldLevel { OFF, STANDARD, AGGRESSIVE }

/**
 * A shields configuration.
 *
 * The global instance held by the view model has every field set. Per-site records leave
 * fields null to inherit the global value, so turning one shield off for a site does not
 * freeze the rest at their current values.
 */
data class ShieldsSettings(
    val level: ShieldLevel? = null,
    val blockTrackers: Boolean? = null,
    val cosmeticFiltering: Boolean? = null,
    val fingerprintProtection: Boolean? = null,
    val httpsUpgrade: Boolean? = null,
) {
    /** True when this per-site record says nothing and can be dropped from the database. */
    val isEmpty: Boolean
        get() = level == null && blockTrackers == null && cosmeticFiltering == null &&
            fingerprintProtection == null && httpsUpgrade == null

    /** Shared constants and helpers for ShieldsSettings. */
    companion object {
        /** Brave's "Aggressive" preset, applied on first run. */
        val AGGRESSIVE_DEFAULTS = ShieldsSettings(
            level = ShieldLevel.AGGRESSIVE,
            blockTrackers = true,
            cosmeticFiltering = true,
            fingerprintProtection = true,
            httpsUpgrade = true,
        )
    }
}

/** The resolved shields for one site — what the WebView client actually acts on. */
data class EffectiveShields(
    val level: ShieldLevel,
    val blockTrackers: Boolean,
    val cosmeticFiltering: Boolean,
    val fingerprintProtection: Boolean,
    /** Also farble timezone and remove `RTCPeerConnection`. */
    val aggressiveFingerprinting: Boolean,
    val httpsUpgrade: Boolean,
    /** Block plaintext navigations outright instead of silently downgrading. */
    val httpsOnly: Boolean,
) {
    val anyEnabled: Boolean
        get() = blockTrackers || cosmeticFiltering || fingerprintProtection || httpsUpgrade

    /** Shared constants and helpers for EffectiveShields. */
    companion object {
        val OFF = EffectiveShields(
            level = ShieldLevel.OFF,
            blockTrackers = false,
            cosmeticFiltering = false,
            fingerprintProtection = false,
            aggressiveFingerprinting = false,
            httpsUpgrade = false,
            httpsOnly = false,
        )

        /**
         * Combines the global defaults with a site's overrides. A site setting always wins;
         * anything it leaves null falls back to the global value, then to the level's preset.
         */
        fun resolve(global: ShieldsSettings, site: ShieldsSettings? = null): EffectiveShields {
            val level = site?.level ?: global.level ?: ShieldLevel.AGGRESSIVE
            if (level == ShieldLevel.OFF) return OFF

            fun pick(
                selector: (ShieldsSettings) -> Boolean?,
            ): Boolean = site?.let(selector) ?: global.let(selector) ?: true

            val fingerprint = pick { it.fingerprintProtection }
            val https = pick { it.httpsUpgrade }
            val aggressive = level == ShieldLevel.AGGRESSIVE
            return EffectiveShields(
                level = level,
                blockTrackers = pick { it.blockTrackers },
                cosmeticFiltering = pick { it.cosmeticFiltering },
                fingerprintProtection = fingerprint,
                aggressiveFingerprinting = fingerprint && aggressive,
                httpsUpgrade = https,
                httpsOnly = https && aggressive,
            )
        }
    }
}
