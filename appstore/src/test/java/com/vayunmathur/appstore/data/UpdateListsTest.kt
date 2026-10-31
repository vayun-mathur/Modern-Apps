package com.vayunmathur.appstore.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateListsTest {

    private val self = "com.vayunmathur.appstore"

    private fun app(
        pkg: String,
        source: AppSource = AppSource.MODERN_APPS,
        versionCode: Long = 2L,
    ) = UnifiedApp(packageName = pkg, source = source, name = pkg, versionCode = versionCode)

    private fun installed(
        pkg: String,
        versionCode: Long = 1L,
        installer: String? = self,
    ) = InstalledInfo(
        packageName = pkg,
        name = pkg,
        versionName = null,
        versionCode = versionCode,
        installerPackage = installer,
    )

    private val enabled = setOf(AppSource.MODERN_APPS, AppSource.PLAYSTORE)

    @Test
    fun `own installs are eligible`() {
        val result = partitionUpdates(
            pending = listOf(app("a")),
            installed = mapOf("a" to installed("a")),
            enabled = enabled,
            ignoredPackages = emptySet(),
            ownPackage = self,
        )
        assertEquals(listOf("a"), result.eligible.map { it.packageName })
        assertTrue(result.foreign.isEmpty())
        assertTrue(result.ignored.isEmpty())
    }

    @Test
    fun `play-installed apps are foreign not eligible`() {
        val result = partitionUpdates(
            pending = listOf(
                app("a", AppSource.PLAYSTORE),
                app("b", AppSource.PLAYSTORE),
            ),
            installed = mapOf(
                "a" to installed("a", installer = "com.android.vending"),
                "b" to installed("b", installer = self),
            ),
            enabled = enabled,
            ignoredPackages = emptySet(),
            ownPackage = self,
        )
        assertEquals(listOf("b"), result.eligible.map { it.packageName })
        assertEquals(listOf("a"), result.foreign.map { it.packageName })
    }

    @Test
    fun `ignored apps are excluded everywhere and surfaced separately`() {
        val result = partitionUpdates(
            pending = listOf(app("a"), app("b")),
            installed = mapOf("a" to installed("a"), "b" to installed("b")),
            enabled = enabled,
            ignoredPackages = setOf("a"),
            ownPackage = self,
        )
        assertEquals(listOf("b"), result.eligible.map { it.packageName })
        assertEquals(listOf("a"), result.ignored.map { it.packageName })
    }

    @Test
    fun `ignored foreign app is ignored not foreign`() {
        val result = partitionUpdates(
            pending = listOf(app("a", AppSource.PLAYSTORE)),
            installed = mapOf("a" to installed("a", installer = "com.android.vending")),
            enabled = enabled,
            ignoredPackages = setOf("a"),
            ownPackage = self,
        )
        assertTrue(result.eligible.isEmpty())
        assertTrue(result.foreign.isEmpty())
        assertEquals(listOf("a"), result.ignored.map { it.packageName })
    }

    @Test
    fun `stale and disabled-source rows are dropped before partitioning`() {
        val result = partitionUpdates(
            pending = listOf(
                app("stale", versionCode = 1L),
                app("disabled", source = AppSource.FDROID),
            ),
            installed = mapOf("stale" to installed("stale"), "disabled" to installed("disabled")),
            enabled = setOf(AppSource.MODERN_APPS),
            ignoredPackages = emptySet(),
            ownPackage = self,
        )
        assertTrue(result.eligible.isEmpty())
        assertTrue(result.foreign.isEmpty())
        assertTrue(result.ignored.isEmpty())
    }

    @Test
    fun `update owner counts when installer does not`() {
        val info = installed("a", installer = "com.android.vending").copy(
            updateOwnerPackage = self,
        )
        val result = partitionUpdates(
            pending = listOf(app("a")),
            installed = mapOf("a" to info),
            enabled = enabled,
            ignoredPackages = emptySet(),
            ownPackage = self,
        )
        assertEquals(listOf("a"), result.eligible.map { it.packageName })
    }
}
