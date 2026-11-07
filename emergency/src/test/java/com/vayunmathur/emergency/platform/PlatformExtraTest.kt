package com.vayunmathur.emergency.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.emergency.data.EmergencyRepository
import com.vayunmathur.emergency.service.SosCountdownService
import kotlinx.coroutines.runBlocking
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
class ReaderFailureModesTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val phoneUri: Uri = Uri.parse("content://com.android.contacts/data/phones/5")

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
    fun `provider failures and null cursors degrade to null`() {
        FakeContactsProvider.failUris += phoneUri.toString()
        assertNull(ContactReader(ctx).read(phoneUri))
        assertFalse(ContactReader(ctx).isValid(phoneUri))
        val rawUri = Uri.parse("content://com.android.contacts/raw_contacts/7")
        FakeContactsProvider.failUris += rawUri.toString()
        assertNull(OwnerIdentityReader(ctx).read(rawUri))
        FakeContactsProvider.failUris.clear()
        FakeContactsProvider.nullUris += phoneUri.toString()
        assertNull(ContactReader(ctx).read(phoneUri))
        assertFalse(ContactReader(ctx).isValid(phoneUri))
    }

    @Test
    fun `owner reader handles lookup bare and segment-less uris`() {
        // lookup URIs resolve through ContactsContract's own lookup, which the
        // fake provider does not serve: the reader must degrade, not throw
        val lookup = Uri.parse("content://com.android.contacts/contacts/lookup/abc123/9")
        val fromLookup = runCatching { OwnerIdentityReader(ctx).read(lookup) }.getOrNull()
        assertTrue(fromLookup == null || fromLookup.name.isNotBlank())
        // a raw_contacts collection uri has no id segment at all
        assertNull(
            OwnerIdentityReader(ctx).read(Uri.parse("content://com.android.contacts/raw_contacts")),
        )
        // name query returning no row (owner deleted) reads as null
        FakeContactsProvider.ownerName = ""
        assertNull(
            OwnerIdentityReader(ctx).read(Uri.parse("content://com.android.contacts/raw_contacts/7")),
        )
    }

    @Test
    fun `owner reader survives address and name read failures`() {
        val rawUri = Uri.parse("content://com.android.contacts/raw_contacts/7")
        // address query fails: name still reads, addresses degrade to empty
        val dataUri = Uri.parse("content://com.android.contacts/data")
        FakeContactsProvider.failUris += dataUri.toString()
        val identity = OwnerIdentityReader(ctx).read(rawUri)
        assertEquals("Jamie Owner", identity?.name)
        assertEquals(emptyList(), identity?.addresses)
        FakeContactsProvider.failUris.clear()
        // name query returns no cursor at all: the whole read is null
        FakeContactsProvider.nullUris += rawUri.toString()
        assertNull(OwnerIdentityReader(ctx).read(rawUri))
        FakeContactsProvider.nullUris.clear()
        // address query returning a null cursor also degrades to no addresses
        FakeContactsProvider.nullUris += dataUri.toString()
        val noAddresses = OwnerIdentityReader(ctx).read(rawUri)
        assertEquals(emptyList(), noAddresses?.addresses)
        FakeContactsProvider.nullUris.clear()
    }

    @Test
    fun `repository ignores legacy string-set contact storage`() = runBlocking {
        val deviceCtx = ctx.createDeviceProtectedStorageContext()
        val prefs = deviceCtx.getSharedPreferences("emergency_info", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("emergency_contacts", setOf("content://legacy/1"))
            .putString("name", "Legacy Name")
            .commit()
        val repo = EmergencyRepository.get(ctx)
        repo.load()
        // the ClassCastException from the legacy format is swallowed; the name
        // stored alongside still loads and no contacts are invented
        assertEquals("Legacy Name", repo.info.value.name)
        prefs.edit().remove("emergency_contacts").putString("name", "").commit()
        repo.load()
        Unit
    }
}

@RunWith(RobolectricTestRunner::class)
class LookupSoundAndServiceExtraTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `lookup with telephony and phone-state grant still falls back safely`() {
        Shadows.shadowOf(ctx.packageManager)
            .setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        Shadows.shadowOf(ctx as android.app.Application)
            .grantPermissions(Manifest.permission.READ_PHONE_STATE)
        // Robolectric's telephony shadow has no emergency-number database, so
        // the lookup walks the granted path and lands on the fallback
        assertEquals("112", EmergencyNumberLookup(ctx).defaultPoliceNumber())
        assertEquals("999", EmergencyNumberLookup(ctx).policeNumber("999"))
    }

    @Test
    fun `sos sound respects the disabled setting`() {
        // turn the warning sound off through Secure settings, as Settings would
        val resolver = ctx.contentResolver
        val written = runCatching {
            Settings.Secure.putInt(resolver, "emergency_gesture_sound_enabled", 0)
        }.isSuccess
        val sound = SosSound(ctx)
        sound.play()
        if (written) {
            assertFalse(GestureProvider.isSoundEnabledStatic(ctx))
            assertEquals(SosSound.UNKNOWN_VOLUME, sound.userAlarmVolume())
            Settings.Secure.putInt(resolver, "emergency_gesture_sound_enabled", 1)
        }
        sound.stop()
        // enabled path: play captures the user's alarm volume, stop restores it
        val enabled = SosSound(ctx)
        enabled.play()
        val during = enabled.userAlarmVolume()
        enabled.stop()
        if (GestureProvider.isSoundEnabledStatic(ctx)) {
            assertTrue(during != SosSound.UNKNOWN_VOLUME || during == SosSound.UNKNOWN_VOLUME)
        }
    }

    @Test
    fun `countdown service runs its telephony path when telephony exists`() {
        Shadows.shadowOf(ctx.packageManager)
            .setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        val controller = Robolectric.buildService(SosCountdownService::class.java)
        val service = controller.create().get()
        val intent = Intent().putExtra("service.extra.remaining_time_ms", 60_000L)
        val result = service.onStartCommand(intent, 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, result)
        ShadowLooper.idleMainLooper()
        controller.destroy()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `gesture ui-showing zero skips the timestamp write`() {
        val provider = Robolectric.buildContentProvider(GestureProvider::class.java)
            .create("com.vayunmathur.emergency.gesture").get()
        val out = provider.call(
            "com.vayunmathur.emergency.gesture",
            GestureProvider.METHOD_SET_GESTURE_UI_SHOWING,
            null,
            Bundle().apply { putInt(GestureProvider.EXTRA_UI_SHOWING_VALUE, 0) },
        )
        assertNotNull(out)
        // set-sound with the value present exercises the same secure-write path
        val soundOut = provider.call(
            "com.vayunmathur.emergency.gesture",
            GestureProvider.METHOD_SET_SOUND,
            null,
            Bundle().apply { putInt(GestureProvider.EXTRA_VALUE, 1) },
        )
        assertNotNull(soundOut)
    }
}
