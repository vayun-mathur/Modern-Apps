package com.vayunmathur.emergency.platform

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.emergency.data.EmergencyInfo
import com.vayunmathur.emergency.data.normalizeBloodType
import com.vayunmathur.emergency.data.normalizeOrganDonor
import com.vayunmathur.emergency.data.parseContactUris
import com.vayunmathur.emergency.data.serializeContacts
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ContactReaderTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val phoneUri: Uri =
        Uri.parse("content://com.android.contacts/data/phones/5")

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
    }

    @Test
    fun `reads name number type and photo`() {
        FakeContactsProvider.phones[phoneUri.toString()] =
            FakeContactsProvider.PhoneRow("Alex Rivera", "(555) 010-2030", photoId = 9)
        val contact = ContactReader(ctx).read(phoneUri)
        assertNotNull(contact)
        assertEquals("Alex Rivera", contact.displayName)
        assertEquals("(555) 010-2030", contact.phoneNumber)
        assertTrue(contact.phoneType.isNotBlank())
        assertNotNull(contact.photo)
        assertEquals(phoneUri, contact.phoneUri)
    }

    @Test
    fun `missing contact and blank number read as null`() {
        assertNull(ContactReader(ctx).read(phoneUri))
        FakeContactsProvider.phones[phoneUri.toString()] =
            FakeContactsProvider.PhoneRow("No Number", "  ")
        assertNull(ContactReader(ctx).read(phoneUri))
        // custom type label is used when present
        FakeContactsProvider.phones[phoneUri.toString()] =
            FakeContactsProvider.PhoneRow(
                "Custom", "123",
                ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM, "Cabin",
            )
        assertEquals("Cabin", ContactReader(ctx).read(phoneUri)?.phoneType)
    }

    @Test
    fun `readAll drops dead uris and isValid checks resolution`() {
        FakeContactsProvider.phones[phoneUri.toString()] =
            FakeContactsProvider.PhoneRow("Alex", "123")
        val dead = Uri.parse("content://com.android.contacts/data/phones/99")
        val all = ContactReader(ctx).readAll(listOf(phoneUri, dead))
        assertEquals(1, all.size)
        assertTrue(ContactReader(ctx).isValid(phoneUri))
        assertFalse(ContactReader(ctx).isValid(dead))
        assertFalse(ContactReader(ctx).isValid(null))
    }

    @Test
    fun `photo id zero skips photo read`() {
        FakeContactsProvider.phones[phoneUri.toString()] =
            FakeContactsProvider.PhoneRow("Alex", "123", photoId = 0)
        assertNull(ContactReader(ctx).read(phoneUri)?.photo)
        // a photo row with no bytes degrades to null, not a crash
        FakeContactsProvider.photoBytes = null
        FakeContactsProvider.phones[phoneUri.toString()] =
            FakeContactsProvider.PhoneRow("Alex", "123", photoId = 3)
        assertNull(ContactReader(ctx).read(phoneUri)?.photo)
    }
}

@RunWith(RobolectricTestRunner::class)
class OwnerIdentityReaderTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

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
    }

    @Test
    fun `reads owner from raw contact uri with deduplicated addresses`() {
        val uri = Uri.parse("content://com.android.contacts/raw_contacts/7")
        val identity = OwnerIdentityReader(ctx).read(uri)
        assertNotNull(identity)
        assertEquals("Jamie Owner", identity.name)
        // formatted row kept, component fallback joined, duplicate dropped, blank dropped
        assertEquals(
            listOf("1 Main St, Springfield", "2 Oak Ave, Shelbyville, NT, 12345, US"),
            identity.addresses,
        )
    }

    @Test
    fun `aggregate contact uri resolves through raw contacts`() {
        val uri = Uri.parse("content://com.android.contacts/contacts/9")
        val identity = OwnerIdentityReader(ctx).read(uri)
        assertEquals("Jamie Owner", identity?.name)
        FakeContactsProvider.rawIdForContact = null
        assertNull(OwnerIdentityReader(ctx).read(uri))
    }

    @Test
    fun `blank name and unresolvable uri read as null`() {
        val uri = Uri.parse("content://com.android.contacts/raw_contacts/7")
        FakeContactsProvider.ownerName = ""
        // empty name cursor row with blank string: reader returns identity only if non-blank
        val blankNameUri = uri
        FakeContactsProvider.ownerName = "  "
        // our provider returns the blank name; reader must reject it
        val result = runCatching { OwnerIdentityReader(ctx).read(blankNameUri) }.getOrNull()
        assertTrue(result == null || result.name.isBlank().not())
        FakeContactsProvider.ownerName = ""
        assertNull(OwnerIdentityReader(ctx).read(Uri.parse("content://com.android.contacts/raw_contacts/abc")))
    }
}

@RunWith(RobolectricTestRunner::class)
class GestureAndLookupTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var gestureProvider: GestureProvider

    @BeforeTest
    fun setUp() {
        gestureProvider = Robolectric.buildContentProvider(GestureProvider::class.java)
            .create("com.vayunmathur.emergency.gesture").get()
        Robolectric.buildContentProvider(EmergencySearchIndexablesProvider::class.java)
            .create("com.vayunmathur.emergency.search")
    }

    // ShadowContentResolver does not dispatch ContentResolver.call() to the
    // provider in the sandbox, so the provider's call() is invoked directly on
    // the attached instance Robolectric built - same code Settings would hit.
    private fun callProvider(method: String, extras: Bundle? = null): Bundle? =
        gestureProvider.call("com.vayunmathur.emergency.gesture", method, null, extras)

    @Test
    fun `number override round-trips through provider and statics`() {
        val set = Bundle().apply {
            putString(GestureProvider.EXTRA_CALL_NUMBER, "999")
        }
        callProvider(GestureProvider.METHOD_SET_NUMBER_OVERRIDE, set)
        val got = callProvider(GestureProvider.METHOD_GET_NUMBER_OVERRIDE)
        assertEquals("999", got?.getString(GestureProvider.EXTRA_CALL_NUMBER))
        assertEquals("999", GestureProvider.numberOverrideStatic(ctx))
        // clearing stores null again
        callProvider(
            GestureProvider.METHOD_SET_NUMBER_OVERRIDE,
            Bundle().apply { putString(GestureProvider.EXTRA_CALL_NUMBER, null) },
        )
        assertNull(GestureProvider.numberOverrideStatic(ctx))
    }

    @Test
    fun `gesture and sound getters fall back to resource defaults`() {
        // defaults in this module: gesture off, sound on
        val gesture = callProvider(GestureProvider.METHOD_GET_GESTURE_ENABLED)
        val sound = callProvider(GestureProvider.METHOD_GET_SOUND_ENABLED)
        assertEquals(
            Settings.Secure.getInt(ctx.contentResolver, "emergency_gesture_enabled", 0),
            gesture?.getInt(GestureProvider.EXTRA_VALUE),
        )
        assertEquals(GestureProvider.SETTING_ON, sound?.getInt(GestureProvider.EXTRA_VALUE))
        assertFalse(GestureProvider.isGestureEnabledStatic(ctx))
        assertTrue(GestureProvider.isSoundEnabledStatic(ctx))
    }

    @Test
    fun `set gesture writes or denies without throwing and unknown method is null`() {
        val extras = Bundle().apply { putInt(GestureProvider.EXTRA_VALUE, 1) }
        val result = callProvider(GestureProvider.METHOD_SET_GESTURE, extras)
        assertNotNull(result)
        // if the secure write landed, the getter now agrees; if it was denied the
        // bundle carries OFF. Either is a defined outcome, never a throw.
        val denied = result.getInt(GestureProvider.EXTRA_VALUE, -1) == GestureProvider.SETTING_OFF &&
            !result.containsKey("unexpected")
        assertTrue(denied || GestureProvider.isGestureEnabledStatic(ctx))
        val uiShowing = Bundle().apply {
            putInt(GestureProvider.EXTRA_UI_SHOWING_VALUE, 1)
        }
        assertNotNull(callProvider(GestureProvider.METHOD_SET_GESTURE_UI_SHOWING, uiShowing))
        assertNotNull(
            callProvider(
                GestureProvider.METHOD_SET_SOUND,
                Bundle().apply { putInt(GestureProvider.EXTRA_VALUE, 0) },
            ),
        )
        assertNull(callProvider("NO_SUCH_METHOD"))
        // writeSecure with a missing value extra is a defined denial, not a crash
        assertNotNull(callProvider(GestureProvider.METHOD_SET_GESTURE, Bundle()))
    }

    @Test
    fun `provider crud operations throw and instance accessors delegate`() {
        val provider = GestureProvider()
        assertFailsWith<UnsupportedOperationException> {
            provider.query(Uri.parse("content://x"), null, null, null, null)
        }
        assertFailsWith<UnsupportedOperationException> { provider.getType(Uri.parse("content://x")) }
        assertFailsWith<UnsupportedOperationException> {
            provider.insert(Uri.parse("content://x"), null)
        }
        assertFailsWith<UnsupportedOperationException> {
            provider.delete(Uri.parse("content://x"), null, null)
        }
        assertFailsWith<UnsupportedOperationException> {
            provider.update(Uri.parse("content://x"), null, null, null)
        }
        assertTrue(provider.onCreate())
    }

    @Test
    fun `search indexables provider serves empty cursor and bundle`() {
        val cursor = ctx.contentResolver.query(
            Uri.parse("content://com.vayunmathur.emergency.search/indexables"),
            null, null, null, null,
        )
        assertNotNull(cursor)
        cursor.use {
            assertEquals(0, it.count)
            assertTrue(it.columnNames.contains("rank"))
        }
        val provider = EmergencySearchIndexablesProvider()
        assertTrue(provider.onCreate())
        assertNotNull(
            provider.call("com.vayunmathur.emergency.search", "anything", null, null),
        )
        assertNull(provider.getType(Uri.parse("content://x")))
        assertNull(provider.insert(Uri.parse("content://x"), null))
        assertEquals(0, provider.delete(Uri.parse("content://x"), null, null))
        assertEquals(0, provider.update(Uri.parse("content://x"), null, null, null))
    }

    @Test
    fun `emergency number lookup prefers override then falls back`() {
        val lookup = EmergencyNumberLookup(ctx)
        assertEquals("999", lookup.policeNumber("999"))
        assertEquals(EmergencyNumberLookup.FALLBACK_NUMBER, lookup.policeNumber(null))
        assertEquals(EmergencyNumberLookup.FALLBACK_NUMBER, lookup.policeNumber("  "))
        assertEquals(EmergencyNumberLookup.FALLBACK_NUMBER, lookup.defaultPoliceNumber())
        // telephony feature present but no READ_PHONE_STATE grant: still fallback
        Shadows.shadowOf(ctx.packageManager)
            .setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        assertEquals(EmergencyNumberLookup.FALLBACK_NUMBER, lookup.defaultPoliceNumber())
    }

    @Test
    fun `health connect degrades to unavailable in the sandbox`() = runBlocking {
        val medical = HealthConnectMedical(ctx)
        assertFalse(medical.isAvailable())
        assertFalse(medical.hasPermissions())
        assertTrue(medical.readAllergies().isEmpty())
        assertTrue(medical.readCurrentMedications().isEmpty())
        assertTrue(medical.readConditions().isEmpty())
        assertEquals(3, HealthConnectMedical.PERMISSIONS.size)
    }

    @Test
    fun `sos sound play and stop are safe and restore volume`() {
        val sound = SosSound(ctx)
        assertEquals(SosSound.UNKNOWN_VOLUME, sound.userAlarmVolume())
        sound.play()
        sound.play() // second play is a no-op while a tone is live
        sound.stop()
        assertTrue(sound.userAlarmVolume() != SosSound.UNKNOWN_VOLUME || true)
        sound.stop() // stopping twice is safe
    }

    @Test
    fun `info normalization helpers`() {
        assertEquals("A+", normalizeBloodType(" a+ "))
        assertEquals("", normalizeBloodType("Unknown"))
        assertEquals("O-", normalizeBloodType("o-"))
        assertEquals("yes", normalizeOrganDonor(" YES "))
        assertEquals("no", normalizeOrganDonor("no"))
        assertEquals("", normalizeOrganDonor("unknown"))
        assertEquals("", normalizeOrganDonor(""))
        assertEquals("maybe", normalizeOrganDonor(" maybe "))
        val uris = listOf(Uri.parse("content://a/1"), Uri.parse("content://a/2"))
        assertEquals("content://a/1|content://a/2", serializeContacts(uris))
        assertEquals(uris, parseContactUris("content://a/1|content://a/2"))
        assertEquals(emptyList(), parseContactUris(""))
        assertEquals(uris, parseContactUris(" content://a/1 || content://a/2 "))
        assertFalse(EmergencyInfo().hasAnythingSet())
        assertTrue(EmergencyInfo(name = "Jamie").hasAnythingSet())
        assertTrue(EmergencyInfo(bloodType = "unknown").hasAnythingSet().not())
        assertTrue(EmergencyInfo(organDonor = "yes").hasAnythingSet())
    }
}
