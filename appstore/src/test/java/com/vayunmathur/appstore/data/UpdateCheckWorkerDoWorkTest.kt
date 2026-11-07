package com.vayunmathur.appstore.data

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * UpdateCheckWorker end to end under Robolectric: with every source off
 * the check is a quiet no-op success; with a newer catalogue row for an
 * installed package it posts exactly one "updates available" notification
 * (permission permitting) and de-dupes an unchanged re-run; with
 * auto-install on but no silent-install eligibility the same update is
 * re-reported as needing confirmation.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateCheckWorkerDoWorkTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var settings: SettingsRepository

    @BeforeTest
    fun setUp() {
        settings = SettingsRepository(ctx, CoroutineScope(Dispatchers.IO))
        ctx.getSharedPreferences("appstore-update-check", Context.MODE_PRIVATE)
            .edit().clear().commit()
        runBlocking {
            settings.setAutoInstallUpdates(false)
            AppSource.TOGGLEABLE.forEach { settings.setSourceEnabled(it, false) }
            // Drop any catalogue rows a previous test in this class seeded.
            AppStoreDatabaseRepository.get(ctx).database.cachedAppDao().clearAll()
        }
    }

    private fun worker() = TestListenableWorkerBuilder<UpdateCheckWorker>(ctx).build()

    private fun postedNotifications() = Shadows.shadowOf(
        ctx.getSystemService(NotificationManager::class.java),
    ).allNotifications

    private suspend fun seedCatalogueUpdate() {
        val db = AppStoreDatabaseRepository.get(ctx).database
        db.cachedAppDao().upsert(
            UnifiedApp(
                packageName = ctx.packageName,
                source = AppSource.MODERN_APPS,
                name = "This Store",
                versionCode = 999_999L,
                repoUrl = DefaultRepos.MODERN_APPS.url,
            ).toEntity(),
        )
        settings.setSourceEnabled(AppSource.MODERN_APPS, true)
    }

    @Test
    fun `all sources off succeeds and posts nothing`() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertTrue(postedNotifications().isEmpty())
    }

    @Test
    fun `catalogue update notifies once then de-dupes`() = runBlocking {
        seedCatalogueUpdate()
        // No POST_NOTIFICATIONS yet: signature is recorded but nothing posts.
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertTrue(postedNotifications().isEmpty())

        Shadows.shadowOf(ctx as Application)
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // An unchanged set is de-duped even now that posting is allowed.
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertTrue(postedNotifications().isEmpty(), "unchanged update set must not re-notify")

        // Clear the recorded signature: the same update now posts.
        ctx.getSharedPreferences("appstore-update-check", Context.MODE_PRIVATE)
            .edit().clear().commit()
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(1, postedNotifications().size)
    }

    @Test
    fun `auto-install on still reports updates it cannot apply silently`() = runBlocking {
        seedCatalogueUpdate()
        settings.setAutoInstallUpdates(true)
        Shadows.shadowOf(ctx as Application)
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        // This store is not the installer of record for itself in the
        // Robolectric package manager, so the update stays outstanding and
        // is reported as needing confirmation (one notification).
        assertEquals(1, postedNotifications().size)
    }

    @Test
    fun `no updates after clearing the catalogue cancels quietly`() = runBlocking {
        seedCatalogueUpdate()
        Shadows.shadowOf(ctx as Application)
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(1, postedNotifications().size)

        AppStoreDatabaseRepository.get(ctx).database.cachedAppDao().clearAll()
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertTrue(postedNotifications().isEmpty(), "empty update set must cancel the notification")
    }
}
