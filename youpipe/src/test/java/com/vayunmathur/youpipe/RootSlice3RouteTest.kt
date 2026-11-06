package com.vayunmathur.youpipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

/**
 * Slice-3 tests for the youpipe root navigation model ([Route] variants and
 * [defaultBackStack] mapping). Pure Kotlin — no Android needed.
 *
 * MainActivity's intent-routing (`resolveInitialBackStack`) reads Intent extras
 * and DataStore and is covered on the emulator; the backstack mapping it
 * delegates to is fully exercised here.
 */
class RootSlice3RouteTest {

    @Test
    fun defaultBackStackHomeVariants() {
        assertEquals(listOf(Route.Main(0)), defaultBackStack(null))
        assertEquals(listOf(Route.Main(0)), defaultBackStack("home"))
        assertEquals(listOf(Route.Main(0)), defaultBackStack("unknown-key"))
        assertEquals(listOf(Route.Main(0)), defaultBackStack(""))
    }

    @Test
    fun defaultBackStackEachDestination() {
        assertEquals(listOf(Route.Main(1)), defaultBackStack("subscriptions"))
        assertEquals(
            listOf(Route.Main(1), Route.SubscriptionVideosPage(null)),
            defaultBackStack("all_subscriptions"),
        )
        assertEquals(listOf(Route.Main(2)), defaultBackStack("history"))
        // Legacy downloads key and saved key both open the Saved hub.
        assertEquals(listOf(Route.Main(3)), defaultBackStack("downloads"))
        assertEquals(listOf(Route.Main(3)), defaultBackStack("saved"))
        assertEquals(listOf(Route.Main(4)), defaultBackStack("settings"))
    }

    @Test
    fun defaultPageOptionsAreDistinctAndCoverHubs() {
        val keys = DEFAULT_PAGE_OPTIONS.map { it.first }
        assertEquals(keys.size, keys.distinct().size)
        assertTrue(keys.containsAll(listOf("home", "subscriptions", "history", "saved", "settings")))
        // Every option key maps to a non-empty backstack.
        for ((key, _) in DEFAULT_PAGE_OPTIONS) {
            assertTrue(defaultBackStack(key).isNotEmpty(), "backstack for $key")
        }
        // Labels are resource ids.
        for ((_, res) in DEFAULT_PAGE_OPTIONS) {
            assertTrue(res != 0)
        }
    }

    @Test
    fun routeVariantsCarryPayloads() {
        assertEquals(42L, Route.VideoPage(42L).videoID)
        assertEquals("UC123", Route.ChannelPage("UC123").channelID)
        assertEquals("music", Route.SubscriptionVideosPage("music").category)
        assertEquals(null, Route.SubscriptionVideosPage(null).category)
        assertEquals(7L, Route.PlaylistDetail(7L).playlistId)
        assertEquals(9L, Route.AddToPlaylist(9L).videoID)
        assertEquals(true, Route.AddToPlaylist(9L).includeWatchLater)
        assertEquals(11L, Route.AddToWatchLater(11L).videoID)
        assertEquals(0, Route.Main().initialTab)
        assertEquals(3, Route.Main(3).initialTab)
    }

    @Test
    fun defaultPageKeyConstants() {
        assertEquals("default_page", DEFAULT_PAGE_KEY)
        assertEquals("home", DEFAULT_PAGE_HOME)
        assertEquals("subscriptions", DEFAULT_PAGE_SUBSCRIPTIONS)
        assertEquals("all_subscriptions", DEFAULT_PAGE_ALL_SUBSCRIPTIONS)
        assertEquals("history", DEFAULT_PAGE_HISTORY)
        assertEquals("downloads", DEFAULT_PAGE_DOWNLOADS)
        assertEquals("saved", DEFAULT_PAGE_SAVED)
        assertEquals("settings", DEFAULT_PAGE_SETTINGS)
    }

    @Test
    fun allRouteVariantsAreSerializable() {
        // The back stack is persisted; every Route subtype must stay
        // @Serializable or restores silently drop entries.
        val variants: List<Route> = listOf(
            Route.Main(1),
            Route.VideoPage(9L),
            Route.ChannelPage("UC1"),
            Route.SubscriptionVideosPage("music"),
            Route.SubscriptionVideosPage(null),
            Route.CreateSubscriptionCategory(null),
            Route.CreateSubscriptionCategory("music"),
            Route.Downloads,
            Route.PlaylistDetail(3L),
            Route.CreatePlaylist,
            Route.AddToPlaylist(9L),
            Route.AddToPlaylist(9L, includeWatchLater = false),
            Route.AddToWatchLater(11L),
            Route.SettingsGeneral,
            Route.SettingsSponsorBlock,
            Route.SettingsRecommendations,
            Route.SettingsData,
            Route.ManageInterests,
        )
        assertEquals(18, variants.size)
        for (route in variants) {
            val annotation = route.javaClass.getAnnotation(Serializable::class.java)
            assertTrue(annotation != null, "${route.javaClass.simpleName} lost @Serializable")
        }
    }

    @Test
    fun routeEqualityCoversEveryField() {
        assertEquals(Route.Main(1), Route.Main(1))
        assertNotEquals(Route.Main(1), Route.Main(2))
        assertEquals(Route.VideoPage(9L), Route.VideoPage(9L))
        assertNotEquals(Route.VideoPage(9L), Route.VideoPage(10L))
        assertEquals(Route.ChannelPage("A"), Route.ChannelPage("A"))
        assertNotEquals(Route.ChannelPage("A"), Route.ChannelPage("B"))
        assertEquals(Route.SubscriptionVideosPage("m"), Route.SubscriptionVideosPage("m"))
        assertNotEquals(Route.SubscriptionVideosPage("m"), Route.SubscriptionVideosPage(null))
        assertEquals(Route.CreateSubscriptionCategory("m"), Route.CreateSubscriptionCategory("m"))
        assertNotEquals(
            Route.CreateSubscriptionCategory("m"),
            Route.CreateSubscriptionCategory(null),
        )
        assertEquals(Route.PlaylistDetail(3L), Route.PlaylistDetail(3L))
        assertEquals(Route.AddToPlaylist(9L), Route.AddToPlaylist(9L))
        assertNotEquals(Route.AddToPlaylist(9L), Route.AddToPlaylist(9L, includeWatchLater = false))
        assertEquals(Route.AddToWatchLater(11L), Route.AddToWatchLater(11L))
        // Singletons are identical by definition; distinct types never equal.
        assertTrue(Route.Downloads === Route.Downloads)
        assertTrue(Route.CreatePlaylist === Route.CreatePlaylist)
        assertNotEquals<Route>(Route.Downloads, Route.CreatePlaylist)
    }
}
