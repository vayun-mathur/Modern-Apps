package com.vayunmathur.uniflix.data

import com.vayunmathur.uniflix.Route
import com.vayunmathur.uniflix.domain.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Offline behaviour of the URL-addon provider plus navigation route shapes. */
class AddonStreamProviderOfflineTest {

    @Test fun providerIdentity() {
        val p = AddonStreamProvider("https://addons.example.com", "My Addon")
        assertEquals("My Addon", p.name)
        assertFalse(p.supports4k)
        assertEquals("https://addons.example.com", p.baseUrl)
    }

    @Test fun resolveFailsWithAddonNameOffline() = runTest {
        val p = AddonStreamProvider("https://addons.invalid", "Offline Addon")
        val err = assertFailsWith<IllegalStateException> {
            p.resolve(42, MediaType.MOVIE)
        }
        assertTrue(err.message!!.contains("Offline Addon"), err.message)
    }

    @Test fun alternatesFailsWithAddonNameOffline() = runTest {
        val p = AddonStreamProvider("https://addons.invalid", "Offline Addon")
        val err = assertFailsWith<IllegalStateException> {
            p.alternates(7, MediaType.TV, season = 1, episode = 2)
        }
        assertTrue(err.message!!.contains("Offline Addon"), err.message)
    }

    @Test fun fetchManifestFailsAsNetworkErrorOffline() = runTest {
        val err = assertFailsWith<com.vayunmathur.uniflix.domain.ManifestError.Network> {
            AddonStreamProvider.fetchManifest("https://addons.invalid/base")
        }
        assertTrue(err.message!!.contains("addons.invalid"), err.message)
    }

    @Test fun routesCarryTheirArguments() {
        assertEquals(Route.Detail("movie", 42), Route.Detail("movie", 42))
        assertTrue(Route.Detail("movie", 42) != Route.Detail("tv", 42))
        val watch = Route.Watch("tv", 7)
        assertEquals(0, watch.season)
        assertEquals(0, watch.episode)
        assertEquals(Route.Watch("tv", 7, 2, 5), Route.Watch("tv", 7, season = 2, episode = 5))
        assertEquals(Route.AddonDetail(9L), Route.AddonDetail(9L))
        // Singleton routes are distinct destinations.
        val routes: List<Route> = listOf(
            Route.Home, Route.Movies, Route.Series, Route.Search,
            Route.Lists, Route.ContinueWatching, Route.PlayerSettings, Route.Addons)
        assertEquals(routes.size, routes.distinct().size)
    }
}
