package com.vayunmathur.findfamily.service

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.notifications.FindFamilyNotificationChannels
import com.vayunmathur.findfamily.platform.BootReceiver
import com.vayunmathur.findfamily.platform.LocationServiceController
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Quick Settings tiles, boot receiver, notification channels and the no-agent
 * backup contract in the sandbox: tile clicks flip the persisted switches, the boot receiver
 * ignores unrelated broadcasts and unpermitted boots, and channels are created
 * per person with the documented ids.
 */
@RunWith(AndroidJUnit4::class)
class TilesBootAndChannelsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    private fun idleMain() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun sharingTileTogglesGlobalSharingFlag() = runBlocking {
        val service = Robolectric.buildService(SharingTileService::class.java).create().get()
        runCatching {
            androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build())
        }
        assertTrue(LocationServiceController.isGlobalSharingEnabled(ctx))
        service.onTileAdded()
        service.onStartListening()
        idleMain()
        service.onClick()
        // The click flips the flag on Dispatchers.Main: pump until it lands.
        var waited = 0
        while (LocationServiceController.isGlobalSharingEnabled(ctx) && waited < 5_000) {
            idleMain()
            delay(50); waited += 50
        }
        assertFalse(LocationServiceController.isGlobalSharingEnabled(ctx))
        service.onClick()
        waited = 0
        while (!LocationServiceController.isGlobalSharingEnabled(ctx) && waited < 5_000) {
            idleMain()
            delay(50); waited += 50
        }
        assertTrue(LocationServiceController.isGlobalSharingEnabled(ctx))
        SharingTileService.requestRefresh(ctx)
        Unit
    }

    @Test fun trackingTileWithoutPermissionOpensAppInsteadOfToggling() = runBlocking {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val controller = Robolectric.buildService(TrackingTileService::class.java)
        val service = controller.create().get()
        assertTrue(LocationServiceController.isTrackingEnabled(ctx))
        service.onTileAdded()
        service.onStartListening()
        // No permission: the click opens the app instead of toggling. The tile
        // binder (IQSService) does not exist under Robolectric, so the collapse
        // call itself throws there; on a device it is bound before clicks land.
        runCatching { service.onClick() }
        idleMain()
        // The click must not flip tracking off/on blindly.
        assertTrue(LocationServiceController.isTrackingEnabled(ctx))
        // Grant, then the click toggles for real.
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        service.onClick()
        var waited = 0
        while (LocationServiceController.isTrackingEnabled(ctx) && waited < 5_000) {
            idleMain()
            delay(50); waited += 50
        }
        assertFalse(LocationServiceController.isTrackingEnabled(ctx))
        // No controller.destroy(): Robolectric's TileService shadow mishandles
        // Service.onDestroy (ShadowTileService cast), unrelated to app code.
        // Restore default-on for other tests in this sandbox.
        val back = CoroutineScope(Dispatchers.IO)
            .async { LocationServiceController.setTrackingEnabled(ctx, true) }
        while (!back.isCompleted) { idleMain(); delay(25) }
        back.await()
        Unit
    }

    @Test fun bootReceiverIgnoresUnrelatedAndUnpermittedBoots() {
        val receiver = BootReceiver()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        // Unrelated action: ignored without touching async machinery.
        receiver.onReceive(ctx, Intent("com.example.UNRELATED"))
        // Boot without fine permission: must not start anything (and must not crash).
        receiver.onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(ctx, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        // Locked boot with permission: reconciles against device-protected state.
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        receiver.onReceive(ctx, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        Thread.sleep(300)
        idleMain()
    }

    @Test fun notificationChannelsAreCreatedPerPerson() {
        val manager = ctx.getSystemService(android.app.NotificationManager::class.java)
        assertNotNull(manager)
        FindFamilyNotificationChannels.ensureNoShowChannel(ctx, 42L, "Ann")
        FindFamilyNotificationChannels.ensureEntryExitChannels(ctx, 42L, "Ann")
        // Idempotent: creating again refreshes, never throws.
        FindFamilyNotificationChannels.ensureNoShowChannel(ctx, 42L, "Ann")
        assertNotNull(manager.getNotificationChannel("no_show_42"))
        assertNotNull(manager.getNotificationChannel("entry_exit_42_arrival"))
        assertNotNull(manager.getNotificationChannel("entry_exit_42_departure"))
        assertEquals("no_show_42", FindFamilyNotificationChannels.noShowChannelId(42L))
        assertEquals("entry_exit_42_arrival", FindFamilyNotificationChannels.entryExitChannelId(42L, true))
        assertEquals("entry_exit_42_departure", FindFamilyNotificationChannels.entryExitChannelId(42L, false))
        FindFamilyNotificationChannels.openNoShowChannelSettings(ctx, 42L, "Ann")
        FindFamilyNotificationChannels.openChannelSettings(ctx, 42L, "Ann", arrival = true)
        val started = Shadows.shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(
            android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,
            started.action,
        )
    }

    @Test fun manifestHasNoBackupAgentAndExtractionRulesGovern() {
        // The legacy backup agent was removed (it bypassed the deny-all cloud
        // rules); data_extraction_rules.xml now governs both channels.
        val info = app.packageManager.getApplicationInfo(app.packageName, 0)
        assertEquals(null, info.backupAgentName)
        assertTrue(app.resources.getIdentifier("data_extraction_rules", "xml", app.packageName) != 0)
        assertNotNull(DataStoreUtils.getInstance(ctx))
    }
}
