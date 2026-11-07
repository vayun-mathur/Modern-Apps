package com.vayunmathur.appstore.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Persisted store preferences. DataStore is file-backed and shared across
 * tests in this class, so each test owns one key end-to-end: the
 * enabled-sources sequence runs in a single method (defaults first), and
 * the auto-install test is the only one touching its key.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private fun repo() = SettingsRepository(
        ApplicationProvider.getApplicationContext<Context>(),
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
    )

    @Test
    fun `enabled sources default then follow edits`() = runTest {
        val settings = repo()
        // Fresh-install defaults: only first-party sources on.
        assertEquals(AppSource.DEFAULT_ENABLED, settings.readEnabledSources())

        settings.setSourceEnabled(AppSource.FDROID, true)
        assertTrue(AppSource.FDROID in settings.readEnabledSources())
        settings.setSourceEnabled(AppSource.FDROID, false)
        assertFalse(AppSource.FDROID in settings.readEnabledSources())
        assertTrue(AppSource.MODERN_APPS in settings.readEnabledSources())

        settings.setSourceEnabled(AppSource.MODERN_APPS, false)
        assertFalse(AppSource.MODERN_APPS in settings.readEnabledSources())
        assertEquals(
            AppSource.DEFAULT_ENABLED - AppSource.MODERN_APPS,
            settings.enabledSources.first { AppSource.MODERN_APPS !in it },
        )
        settings.setSourceEnabled(AppSource.MODERN_APPS, true)
        assertTrue(AppSource.MODERN_APPS in settings.readEnabledSources())

        for (source in AppSource.TOGGLEABLE) settings.setSourceEnabled(source, true)
        assertEquals(AppSource.TOGGLEABLE.toSet(), settings.readEnabledSources())
    }

    @Test
    fun `auto install defaults off and persists edits`() = runTest {
        val settings = repo()
        assertFalse(SettingsRepository.DEFAULT_AUTO_INSTALL_UPDATES)
        assertFalse(settings.readAutoInstallUpdates())

        settings.setAutoInstallUpdates(true)
        assertTrue(settings.readAutoInstallUpdates())
        assertTrue(settings.autoInstallUpdates.first { it })

        settings.setAutoInstallUpdates(false)
        assertFalse(settings.readAutoInstallUpdates())
    }
}
