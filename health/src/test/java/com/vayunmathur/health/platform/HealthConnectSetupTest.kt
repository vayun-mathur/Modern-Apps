package com.vayunmathur.health.platform

import androidx.health.connect.client.HealthConnectClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for #891: the app crashed on launch on Android 12/13 because nothing checked
 * the Health Connect SDK status before calling `getOrCreate`. [HealthConnectSetup] now gates
 * every entry point; this pins the status mapping (including unknown future codes degrading to
 * not-installed rather than crashing) and the installer URLs.
 */
class HealthConnectSetupTest {

    @Test
    fun availableMapsToAvailable() {
        assertEquals(
            HealthConnectAvailability.AVAILABLE,
            HealthConnectSetup.toAvailability(HealthConnectClient.SDK_AVAILABLE),
        )
    }

    @Test
    fun updateRequiredMapsToUpdateRequired() {
        assertEquals(
            HealthConnectAvailability.UPDATE_REQUIRED,
            HealthConnectSetup.toAvailability(
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
            ),
        )
    }

    @Test
    fun unavailableMapsToNotInstalled() {
        assertEquals(
            HealthConnectAvailability.NOT_INSTALLED,
            HealthConnectSetup.toAvailability(HealthConnectClient.SDK_UNAVAILABLE),
        )
    }

    @Test
    fun unknownFutureStatusMapsToNotInstalledRatherThanCrashing() {
        assertEquals(
            HealthConnectAvailability.NOT_INSTALLED,
            HealthConnectSetup.toAvailability(Int.MAX_VALUE),
        )
    }

    @Test
    fun marketUrlTargetsTheProviderPackageWithOnboarding() {
        assertTrue(
            HealthConnectSetup.MARKET_URL.startsWith(
                "market://details?id=${HealthConnectSetup.PROVIDER_PACKAGE}"
            )
        )
        assertTrue(HealthConnectSetup.MARKET_URL.contains("healthconnect"))
    }

    @Test
    fun webFallbackTargetsTheSameProviderPackage() {
        assertTrue(HealthConnectSetup.WEB_URL.startsWith("https://"))
        assertTrue(HealthConnectSetup.WEB_URL.contains("id=${HealthConnectSetup.PROVIDER_PACKAGE}"))
    }
}
