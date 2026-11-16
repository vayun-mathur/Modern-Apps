package com.vayunmathur.web.domain.shields

import com.vayunmathur.web.domain.EffectiveShields
import com.vayunmathur.web.domain.ShieldLevel
import com.vayunmathur.web.domain.ShieldsSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Pins the hot-path policy: what may skip the shields engine, and what must not. */
class RequestGateTest {

    @Test fun webSchemesAreOnlyHttpAndHttps() {
        assertTrue(RequestGate.isWebScheme("http"))
        assertTrue(RequestGate.isWebScheme("https"))
        assertFalse(RequestGate.isWebScheme("data"))
        assertFalse(RequestGate.isWebScheme("blob"))
        assertFalse(RequestGate.isWebScheme("file"))
        assertFalse(RequestGate.isWebScheme(null))
    }

    @Test fun shieldCheckRunsForSubresourceWhenEngineReadyAndBlockingOn() {
        assertTrue(RequestGate.shouldShieldCheck("https", isMainFrame = false, engineReady = true, blockTrackers = true))
        assertTrue(RequestGate.shouldShieldCheck("http", isMainFrame = false, engineReady = true, blockTrackers = true))
    }

    @Test fun shieldCheckSkippedWhenItCannotChangeTheOutcome() {
        // Main-frame documents are rewritten, never shield-blocked.
        assertFalse(RequestGate.shouldShieldCheck("https", isMainFrame = true, engineReady = true, blockTrackers = true))
        // Engine not loaded yet: check() can only answer null ("allow").
        assertFalse(RequestGate.shouldShieldCheck("https", isMainFrame = false, engineReady = false, blockTrackers = true))
        // Tracker blocking off for the site: nothing to ask.
        assertFalse(RequestGate.shouldShieldCheck("https", isMainFrame = false, engineReady = true, blockTrackers = false))
        // Non-web schemes never reach the engine.
        assertFalse(RequestGate.shouldShieldCheck("data", isMainFrame = false, engineReady = true, blockTrackers = true))
        assertFalse(RequestGate.shouldShieldCheck(null, isMainFrame = false, engineReady = true, blockTrackers = true))
    }

    @Test fun hostShieldsCacheResolvesOncePerHost() {
        var calls = 0
        val cache = HostShieldsCache { host ->
            calls++
            EffectiveShields.resolve(ShieldsSettings.AGGRESSIVE_DEFAULTS, null)
                .also { assertTrue(host.isNotEmpty()) }
        }
        val first = cache.forHost("example.com")
        val second = cache.forHost("example.com")
        assertSame(first, second)
        assertEquals(1, calls)

        cache.forHost("other.example")
        assertEquals(2, calls)
        // Single-entry: returning to the first host re-resolves (still correct, just not memoised).
        cache.forHost("example.com")
        assertEquals(3, calls)
    }

    @Test fun hostShieldsCacheInvalidateForcesReResolve() {
        var calls = 0
        val cache = HostShieldsCache {
            calls++
            EffectiveShields.resolve(ShieldsSettings(level = ShieldLevel.OFF), null)
        }
        cache.forHost("example.com")
        cache.invalidate()
        cache.forHost("example.com")
        assertEquals(2, calls)
    }

    @Test fun originProbeGateProbesOncePerOriginChange() {
        val gate = OriginProbeGate()
        assertTrue(gate.shouldProbe("https://example.com"))
        // Reloads / redirect finishes of the same origin are not re-probed.
        assertFalse(gate.shouldProbe("https://example.com"))
        assertFalse(gate.shouldProbe("https://example.com"))
        // A different origin earns a fresh probe...
        assertTrue(gate.shouldProbe("https://other.example"))
        assertFalse(gate.shouldProbe("https://other.example"))
        // ...and coming back re-probes, since storage may have changed meanwhile.
        assertTrue(gate.shouldProbe("https://example.com"))
    }

    @Test fun originProbeGateIgnoresBlankOrigins() {
        val gate = OriginProbeGate()
        assertFalse(gate.shouldProbe(""))
        assertFalse(gate.shouldProbe("   "))
        assertTrue(gate.shouldProbe("https://example.com"))
    }
}
