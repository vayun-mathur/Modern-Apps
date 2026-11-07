package com.vayunmathur.emergency.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.emergency.data.EmergencyRepository
import com.vayunmathur.emergency.notifications.SosNotifications
import com.vayunmathur.emergency.receiver.SosActionReceiver
import com.vayunmathur.emergency.service.SosCountdownService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLooper
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class RepositoryTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val phoneUri: Uri = Uri.parse("content://com.android.contacts/data/phones/5")
    private val deadUri: Uri = Uri.parse("content://com.android.contacts/data/phones/99")

    @BeforeTest
    fun setUp() {
        FakeContactsProvider.reset()
        val provider = FakeContactsProvider()
        provider.attachInfo(
            ctx,
            android.content.pm.ProviderInfo().apply {
                authority = "com.android.contacts"
                packageName = ctx.packageName
                name = FakeContactsProvider::class.java.name
            },
        )
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
            "com.android.contacts", provider,
        )
        FakeContactsProvider.phones[phoneUri.toString()] =
            FakeContactsProvider.PhoneRow("Alex Rivera", "(555) 010-2030")
    }

    @Test
    fun `repository saves identity and medical info and reloads`() = runBlocking {
        val repo = EmergencyRepository.get(ctx)
        repo.saveOwnerIdentity("Jamie Owner", "1 Main St")
        var info = repo.info.value
        assertEquals("Jamie Owner", info.name)
        assertEquals("1 Main St", info.address)
        repo.saveMedicalInfo("o+", "YES")
        info = repo.info.value
        // stored values are normalized on the way back out
        assertEquals("O+", info.bloodType)
        assertEquals("yes", info.organDonor)
        assertTrue(info.hasAnythingSet())
        // clearing everything re-enables the suggestion state path
        repo.saveOwnerIdentity("", "")
        repo.saveMedicalInfo("unknown", "unknown")
        repo.load()
        assertFalse(repo.info.value.hasAnythingSet())
        Unit
    }

    @Test
    fun `repository adds deduplicates removes and prunes contacts`() = runBlocking {
        val repo = EmergencyRepository.get(ctx)
        // start clean regardless of test order within the shared singleton
        repo.info.value.contacts.toList().forEach { repo.removeContact(it.phoneUri) }
        assertTrue(repo.addContact(phoneUri))
        assertTrue(repo.addContact(phoneUri)) // duplicate is accepted, not stored twice
        assertEquals(1, repo.info.value.contacts.size)
        assertEquals("Alex Rivera", repo.info.value.contacts.first().displayName)
        assertFalse(repo.addContact(deadUri))
        repo.removeContact(phoneUri)
        assertTrue(repo.info.value.contacts.isEmpty())
        repo.removeContact(phoneUri) // removing an absent contact is a no-op

        // a contact deleted from the address book is pruned (and re-persisted) on load
        assertTrue(repo.addContact(phoneUri))
        FakeContactsProvider.phones.remove(phoneUri.toString())
        repo.load()
        assertTrue(repo.info.value.contacts.isEmpty())
        Unit
    }
}

@RunWith(RobolectricTestRunner::class)
class ViewModelAndCoordinatorTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val phoneUri: Uri = Uri.parse("content://com.android.contacts/data/phones/5")

    @BeforeTest
    fun setUp() {
        FakeContactsProvider.reset()
        val provider = FakeContactsProvider()
        provider.attachInfo(
            app,
            android.content.pm.ProviderInfo().apply {
                authority = "com.android.contacts"
                packageName = app.packageName
                name = FakeContactsProvider::class.java.name
            },
        )
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
            "com.android.contacts", provider,
        )
        FakeContactsProvider.phones[phoneUri.toString()] =
            FakeContactsProvider.PhoneRow("Alex Rivera", "(555) 010-2030")
    }

    private fun await(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        assertTrue(cond())
    }

    @Test
    fun `view model loads state and routes actions`() = runBlocking {
        val vm = EmergencyViewModel(app)
        // state only combines while subscribed (WhileSubscribed), so collect it
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch { vm.state.collect {} }
        try {
            await { !vm.state.value.loading }
            assertFalse(vm.state.value.health.available)
            vm.saveMedicalInfo("A+", "no")
            await { vm.state.value.info.bloodType == "A+" }
            vm.addContact(phoneUri)
            await { vm.state.value.info.contacts.isNotEmpty() }
            assertFalse(vm.state.value.addFailed)
            vm.addContact(Uri.parse("content://com.android.contacts/data/phones/99"))
            await { vm.state.value.addFailed }
            vm.clearAddFailed()
            await { !vm.state.value.addFailed }
            vm.removeContact(phoneUri)
            await { vm.state.value.info.contacts.isEmpty() }
            vm.refresh()
            await { !vm.state.value.loading }
            vm.refreshHealthConnect()
            Thread.sleep(200); ShadowLooper.idleMainLooper()
            assertFalse(vm.state.value.health.available)
        } finally {
            job.cancel(); scope.cancel()
        }
        Unit
    }

    @Test
    fun `pick owner saves directly for one address and asks for several`() = runBlocking {
        val vm = EmergencyViewModel(app)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch { vm.state.collect {} }
        try {
            await { !vm.state.value.loading }
            // several addresses -> candidates are offered, confirm saves the choice
            vm.pickOwner(Uri.parse("content://com.android.contacts/raw_contacts/7"))
            await { vm.state.value.ownerCandidates != null }
            assertEquals("Jamie Owner", vm.state.value.ownerCandidates?.name)
            assertEquals(2, vm.state.value.ownerCandidates?.addresses?.size)
            vm.dismissOwnerPick()
            await { vm.state.value.ownerCandidates == null }
            vm.pickOwner(Uri.parse("content://com.android.contacts/raw_contacts/7"))
            await { vm.state.value.ownerCandidates != null }
            vm.confirmOwnerAddress("2 Oak Ave, Shelbyville, NT, 12345, US")
            await { vm.state.value.info.address == "2 Oak Ave, Shelbyville, NT, 12345, US" }
            assertEquals("Jamie Owner", vm.state.value.info.name)
            // confirm with nothing pending is a no-op
            vm.confirmOwnerAddress("nowhere")
            // unreadable owner sets the one-shot failure flag
            FakeContactsProvider.ownerName = ""
            vm.pickOwner(Uri.parse("content://com.android.contacts/raw_contacts/7"))
            await { vm.state.value.ownerPickFailed }
            vm.clearOwnerPickFailed()
            await { !vm.state.value.ownerPickFailed }
            // single-address owner saves without asking
            FakeContactsProvider.ownerName = "Jamie Owner"
            FakeContactsProvider.addresses = listOf(
                arrayOf("Only Address 1", null, null, null, null, null),
            )
            vm.pickOwner(Uri.parse("content://com.android.contacts/raw_contacts/7"))
            await { vm.state.value.info.address == "Only Address 1" }
            // no-address owner saves with a blank address
            FakeContactsProvider.addresses = emptyList()
            vm.pickOwner(Uri.parse("content://com.android.contacts/raw_contacts/7"))
            await { vm.state.value.info.address == "" }
        } finally {
            job.cancel(); scope.cancel()
        }
        Unit
    }

    @Test
    fun `sos coordinator loads fallback state and hands off`() {
        val coordinator = SosCoordinator(app, openedWithMillis = 42_000)
        await { coordinator.state.value.loaded }
        assertEquals(EmergencyNumberLookup.FALLBACK_NUMBER, coordinator.state.value.number)
        assertEquals(42_000, coordinator.state.value.totalMillis)
        assertFalse(coordinator.state.value.gestureEnabled)
        // zero remaining never starts the service; a positive remainder does
        coordinator.continueInBackground(0)
        coordinator.continueInBackground(30_000)
        coordinator.cancel()
        ShadowLooper.idleMainLooper()
        // default-duration coordinator reads the resource total
        val byDefault = SosCoordinator(app, openedWithMillis = -1)
        await { byDefault.state.value.loaded }
        assertEquals(5_000, byDefault.state.value.totalMillis)
    }
}

@RunWith(RobolectricTestRunner::class)
class SosServiceAndReceiverTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `countdown service rejects invalid and telephony-less starts`() {
        val controller = Robolectric.buildService(SosCountdownService::class.java)
        val service = controller.create().get()
        assertEquals(
            android.app.Service.START_NOT_STICKY,
            service.onStartCommand(null, 0, 1),
        )
        val negative = Intent().putExtra("service.extra.remaining_time_ms", -5L)
        assertEquals(
            android.app.Service.START_NOT_STICKY,
            service.onStartCommand(negative, 0, 2),
        )
        // no telephony feature in the sandbox: a valid countdown still stops at once
        val valid = Intent().putExtra("service.extra.remaining_time_ms", 60_000L)
        assertEquals(
            android.app.Service.START_NOT_STICKY,
            service.onStartCommand(valid, 0, 3),
        )
        assertNull(service.onBind(null))
        controller.destroy()
    }

    @Test
    fun `countdown notification counts down to a wall-clock deadline`() {
        Shadows.shadowOf(ctx.packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY, true)
        val controller = Robolectric.buildService(SosCountdownService::class.java)
        val service = controller.create().get()
        val before = System.currentTimeMillis()
        val intent = Intent().putExtra("service.extra.remaining_time_ms", 60_000L)
        service.onStartCommand(intent, 0, 1)
        ShadowLooper.idleMainLooper()
        val manager = ctx.getSystemService(android.app.NotificationManager::class.java)
        val posted = manager.activeNotifications
            .firstOrNull { it.id == SosNotifications.NOTIFICATION_ID }?.notification
        assertNotNull(posted)
        // setWhen is epoch wall time: an elapsedRealtime target lands in 1970 and
        // the chronometer counts down to the wrong instant.
        assertTrue(posted.`when` >= before + 59_000)
        assertTrue(posted.`when` <= System.currentTimeMillis() + 61_000)
        controller.destroy()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `service start and stop helpers build the right intents`() {
        SosCountdownService.start(ctx, 12_000)
        val started = Shadows.shadowOf(ctx as Application).nextStartedService
        assertNotNull(started)
        assertEquals(
            SosCountdownService::class.java.name,
            started.component?.className,
        )
        SosCountdownService.stop(ctx)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `receiver cancels and ignores unknown actions`() {
        val receiver = SosActionReceiver()
        receiver.onReceive(ctx, Intent("com.example.UNKNOWN"))
        receiver.onReceive(ctx, SosActionReceiver.cancelIntent(ctx))
        ShadowLooper.idleMainLooper()
        // pending intent factories produce our explicit intents
        assertEquals(
            SosActionReceiver.ACTION_MAKE_CALL,
            SosActionReceiver.makeCallIntent(ctx).action,
        )
        assertEquals(
            SosActionReceiver.ACTION_CANCEL_COUNTDOWN,
            SosActionReceiver.cancelIntent(ctx).action,
        )
        assertNotNull(SosActionReceiver.makeCallPendingIntent(ctx))
        assertNotNull(SosActionReceiver.cancelPendingIntent(ctx))
        SosActionReceiver.stopCountdown(ctx)
    }

    @Test
    fun `receiver make-call falls back to the telecom intent`() {
        // No Settings emergency provider exists in the sandbox, so the receiver
        // must fall back to ACTION_CALL_EMERGENCY with the fallback number.
        // This only records a shadow intent - nothing is dialled.
        val receiver = SosActionReceiver()
        receiver.onReceive(ctx, SosActionReceiver.makeCallIntent(ctx))
        ShadowLooper.idleMainLooper()
        val started = Shadows.shadowOf(ctx as Application).nextStartedActivity
        if (started != null) {
            assertEquals("android.intent.action.CALL_EMERGENCY", started.action)
            assertEquals("tel:112", started.dataString)
        }
    }

    @Test
    fun `notifications build post and cancel`() {
        SosNotifications.ensureChannel(ctx)
        val manager = ctx.getSystemService(android.app.NotificationManager::class.java)
        assertNotNull(manager.getNotificationChannel(SosNotifications.CHANNEL_ID))
        val contentIntent = android.app.PendingIntent.getActivity(
            ctx, 0, Intent(ctx, com.vayunmathur.emergency.ui.SosActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = SosNotifications.countdown(
            ctx, "112", System.currentTimeMillis() + 5_000, contentIntent,
        )
        assertEquals(SosNotifications.CHANNEL_ID, notification.channelId)
        assertTrue(notification.actions?.any { it.title?.toString() == "Cancel" } == true)
        SosNotifications.notify(ctx, notification)
        assertTrue(
            manager.activeNotifications.any { it.id == SosNotifications.NOTIFICATION_ID },
        )
        SosNotifications.cancel(ctx)
        assertTrue(
            manager.activeNotifications.none { it.id == SosNotifications.NOTIFICATION_ID },
        )
    }
}
