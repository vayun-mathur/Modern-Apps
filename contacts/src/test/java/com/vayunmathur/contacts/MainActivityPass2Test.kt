package com.vayunmathur.contacts

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Second-pass coverage for MainActivity intent handling and launch. */
@RunWith(RobolectricTestRunner::class)
class MainActivityPass2Test {

    private fun grantContacts() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
        )
    }

    @Test
    fun activityLaunchesToContactList() {
        grantContacts()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(activity.isFinishing)
        assertEquals("com.vayunmathur.contacts", activity.packageName)
        controller.pause().stop().destroy()
    }

    @Test
    fun activityHandlesViewContactIntent() {
        grantContacts()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.contacts/contacts/7"))
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun activityHandlesGroupsIntent() {
        grantContacts()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.contacts/groups"))
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun routeSerializationRoundTrips() {
        val json = kotlinx.serialization.json.Json
        fun <T> roundTrip(value: T, serializer: kotlinx.serialization.KSerializer<T>) {
            assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
        }
        roundTrip(Route.Main, Route.Main.serializer())
        roundTrip(Route.ContactsList, Route.ContactsList.serializer())
        roundTrip(Route.GroupsList(3L), Route.GroupsList.serializer())
        roundTrip(Route.GroupsList(), Route.GroupsList.serializer())
        roundTrip(Route.AddToGroupDialog(listOf(1L, 2L)), Route.AddToGroupDialog.serializer())
        roundTrip(Route.ContactDetail(7L), Route.ContactDetail.serializer())
        roundTrip(Route.EditContact(7L), Route.EditContact.serializer())
        roundTrip(Route.EditContact(null), Route.EditContact.serializer())
        roundTrip(Route.InsertOrEditContact(), Route.InsertOrEditContact.serializer())
        roundTrip(Route.EventDatePickerDialog("birthday", null), Route.EventDatePickerDialog.serializer())
        roundTrip(
            Route.EventDatePickerDialog("anniversary", kotlinx.datetime.LocalDate(2020, 1, 2)),
            Route.EventDatePickerDialog.serializer(),
        )
        roundTrip(Route.EventDeleteConfirmDialog(7L, "Aisha"), Route.EventDeleteConfirmDialog.serializer())
        roundTrip(Route.EventDeleteConfirmDialog(7L, null), Route.EventDeleteConfirmDialog.serializer())
        roundTrip(Route.Settings, Route.Settings.serializer())
        roundTrip(Route.AddAccountDialog, Route.AddAccountDialog.serializer())
        roundTrip(Route.CropPhoto("content://x"), Route.CropPhoto.serializer())
        roundTrip(Route.ImportVcf(listOf("content://a", "content://b")), Route.ImportVcf.serializer())
        roundTrip(Route.ImportVcf(emptyList()), Route.ImportVcf.serializer())
        assertEquals(7L, Route.ContactDetail(7L).contactId)
        assertEquals(null, Route.GroupsList().expandGroupId)
        assertEquals(listOf("content://a", "content://b"), Route.ImportVcf(listOf("content://a", "content://b")).uris)
        assertTrue(Route.Main is Route)
    }

    @Test
    fun activityHandlesPickIntent() {
        grantContacts()
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "w800dp-h400dp")
    fun activityLaunchesInLandscape() {
        grantContacts()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun contactsContentUriIsTheExpectedAuthority() {
        // guards the pick-URI assertions used elsewhere: ids append to the contacts URI
        val uri = android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 7L)
        assertEquals("content://com.android.contacts/contacts/7", uri.toString())
    }
}
