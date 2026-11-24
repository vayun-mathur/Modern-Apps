package com.vayunmathur.launcher.wear.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WearLauncherModelTest {

    private fun app(pkg: String, label: String, cls: String = "$pkg.Main") =
        WatchApp(pkg, cls, label)

    private val installed = listOf(
        app("com.vayunmathur.calendar", "Calendar"),
        app("com.vayunmathur.weather", "Weather"),
        app("com.example.zebra", "Zebra"),
        app("com.example.alpha", "alpha"),
        app("com.vayunmathur.launcher", "Launcher"),
        app("com.example.alpha", "alpha duplicate", "com.example.alpha.Other"),
        app("", "blank"),
        app("com.example.noclass", "NoClass", ""),
    )

    private val snapshot = WearFavoritesSnapshot(
        generatedAtMillis = 10L,
        favorites = listOf(
            WearFavorite("com.example.zebra", "com.example.zebra.Main", "Zebra", 1),
            WearFavorite("com.vayunmathur.calendar", "", "Calendar", 0),
            WearFavorite("com.example.absent", "", "Absent", 2),
        ),
    )

    @Test
    fun sectionsMapFavoritesRecentsCompanionsAndAll() {
        val sections = buildWearLauncherSections(
            installed = installed,
            snapshot = snapshot,
            recents = listOf(
                RecentLaunch("com.example.alpha", 100L),
                RecentLaunch("com.vayunmathur.calendar", 200L), // favorite: excluded from recents
                RecentLaunch("com.example.gone", 300L), // not installed: dropped
            ),
            ownPackageName = "com.vayunmathur.launcher",
        )
        assertEquals(
            listOf("com.vayunmathur.calendar", "com.example.zebra"),
            sections.favorites.map { it.packageName },
        )
        assertEquals(listOf("com.example.absent"), sections.missingFavorites.map { it.packageName })
        assertEquals(listOf("com.example.alpha"), sections.recents.map { it.packageName })
        // Weather is a companion; Calendar is already a favorite so excluded there.
        assertEquals(listOf("com.vayunmathur.weather"), sections.companions.map { it.packageName })
        // All: self, blanks, no-class and duplicate packages removed; label-sorted.
        assertEquals(
            listOf("com.example.alpha", "com.vayunmathur.calendar", "com.vayunmathur.weather", "com.example.zebra"),
            sections.all.map { it.packageName },
        )
    }

    @Test
    fun favoritePrefersExactComponent() {
        val two = listOf(
            WatchApp("com.example.multi", "com.example.multi.Second", "Multi"),
            WatchApp("com.example.multi", "com.example.multi.First", "Multi"),
        )
        val sections = buildWearLauncherSections(
            two,
            WearFavoritesSnapshot(1L, listOf(WearFavorite("com.example.multi", "com.example.multi.Second", "Multi", 0))),
            emptyList(),
            ownPackageName = "com.vayunmathur.launcher",
        )
        assertEquals("com.example.multi.Second", sections.favorites.single().className)
    }

    @Test
    fun capabilityAbsentYieldsEmptySectionsExceptMissing() {
        val sections = buildWearLauncherSections(
            installed = emptyList(),
            snapshot = snapshot,
            recents = listOf(RecentLaunch("com.example.alpha", 1L)),
            ownPackageName = "com.vayunmathur.launcher",
        )
        assertTrue(sections.favorites.isEmpty())
        assertTrue(sections.recents.isEmpty())
        assertTrue(sections.companions.isEmpty())
        assertTrue(sections.all.isEmpty())
        assertEquals(snapshot.favorites, sections.missingFavorites)
    }

    @Test
    fun noSnapshotStillGivesDrawerAndCompanions() {
        val sections = buildWearLauncherSections(
            installed, WearFavoritesSnapshot(), emptyList(), "com.vayunmathur.launcher",
        )
        assertTrue(sections.favorites.isEmpty())
        assertTrue(sections.missingFavorites.isEmpty())
        assertEquals(4, sections.all.size)
        assertEquals(
            listOf("com.vayunmathur.calendar", "com.vayunmathur.weather"),
            sections.companions.map { it.packageName },
        )
    }

    @Test
    fun filterPrefersPrefixThenContains() {
        val apps = listOf(app("a", "Calendar"), app("b", "Vacation Calendar"), app("c", "Camera"))
        assertEquals(listOf("Calendar", "Vacation Calendar"), filterWatchApps(apps, "cal").map { it.label })
        assertEquals(apps, filterWatchApps(apps, "  "))
        assertTrue(filterWatchApps(apps, "zzz").isEmpty())
    }

    @Test
    fun recordLaunchDedupesCapsAndIgnoresBlank() {
        var recents = emptyList<RecentLaunch>()
        recents = recordRecentLaunch(recents, "a", 1L)
        recents = recordRecentLaunch(recents, "b", 2L)
        recents = recordRecentLaunch(recents, "a", 3L)
        assertEquals(listOf("a", "b"), recents.map { it.packageName })
        assertEquals(recents, recordRecentLaunch(recents, "", 4L))
        (0 until 10).forEach { recents = recordRecentLaunch(recents, "p$it", 10L + it) }
        assertEquals(MAX_RECENTS, recents.size)
    }

    @Test
    fun settingsTargetsOnlyResolvableNoFakeToggles() {
        assertTrue(availableWearSettingsTargets(emptySet()).isEmpty())
        assertEquals(
            listOf(WearSettingsTarget.DISPLAY, WearSettingsTarget.SYSTEM),
            availableWearSettingsTargets(
                setOf("android.settings.DISPLAY_SETTINGS", "android.settings.SETTINGS"),
            ),
        )
    }
}
