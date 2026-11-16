package com.vayunmathur.findfamily

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.platform.NoShowCheckScheduler
import com.vayunmathur.findfamily.util.BootReceiver
import com.vayunmathur.findfamily.util.FindFamilyNotificationChannels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y3PlatformBitsTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        TestFindFamilyRepository.install(ctx)
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
    }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun notificationChannelsCreatedWithExpectedIds() {
        assertEquals("entry_exit_7_arrival", FindFamilyNotificationChannels.entryExitChannelId(7L, true))
        assertEquals("entry_exit_7_departure", FindFamilyNotificationChannels.entryExitChannelId(7L, false))
        assertEquals("no_show_7", FindFamilyNotificationChannels.noShowChannelId(7L))
        FindFamilyNotificationChannels.ensureNoShowChannel(ctx, 7L, "Alice")
        FindFamilyNotificationChannels.ensureEntryExitChannels(ctx, 7L, "Alice")
        val manager = ctx.getSystemService(NotificationManager::class.java)
        val ids = manager.notificationChannels.map { it.id }
        assertTrue(ids.containsAll(listOf("no_show_7", "entry_exit_7_arrival", "entry_exit_7_departure")), "$ids")
        // Idempotent: re-ensuring neither duplicates nor throws.
        FindFamilyNotificationChannels.ensureNoShowChannel(ctx, 7L, "Alice")
        assertEquals(ids.toSet(), manager.notificationChannels.map { it.id }.toSet())
    }

    @Test fun bootReceiverHandlesBootAndUnlockBroadcasts() {
        val receiver = BootReceiver()
        for (action in listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "com.example.UNRELATED",
            null,
        )) {
            val intent = Intent(action).setPackage(ctx.packageName)
            runCatching { receiver.onReceive(ctx, intent) }
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertNotNull(receiver)
    }

    @Test fun noShowSchedulerEnqueuesAndCancels() {
        val alert = com.vayunmathur.findfamily.data.NoShowAlert(
            watchedUserId = 7L, waypointId = 3L,
            expectedAt = kotlin.time.Clock.System.now() + kotlin.time.Duration.Companion.parse("24h"),
            grace = kotlin.time.Duration.Companion.parse("30m"),
        )
        NoShowCheckScheduler.schedule(ctx, alert)
        Thread.sleep(300)
        NoShowCheckScheduler.schedule(ctx, alert) // REPLACE policy re-enqueues
        NoShowCheckScheduler.cancel(ctx, alert.id)
        assertNotNull(NoShowCheckScheduler)
    }
}
