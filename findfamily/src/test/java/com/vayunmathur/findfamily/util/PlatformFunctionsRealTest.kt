package com.vayunmathur.findfamily.util
import com.vayunmathur.findfamily.platform.Platform

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Platform] contact-picker plumbing: the legacy launcher composes, and the
 * session/legacy cursor readers aggregate rows into one picked contact.
 */
@RunWith(AndroidJUnit4::class)
class PlatformFunctionsRealTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    private class CursorProvider(private val cursor: Cursor) : ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor = cursor
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
        ) = 0
    }

    @Test fun legacyPickerLauncherComposesAndRequests() {
        val platform = Platform(ctx)
        var launcher: (() -> Unit)? = null
        rule.setContent { launcher = platform.requestPickContact { _, _ -> } }
        rule.waitForIdle()
        assertNotNull(launcher)
        // Invoking asks for the contacts permission (Robolectric records the request).
        launcher!!.invoke()
        rule.waitForIdle()
    }

    @Test fun readLegacyContactFromProviderCursor() {
        val cursor = MatrixCursor(arrayOf(
            ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.PHOTO_URI,
        )).apply { addRow(arrayOf("Grace Hopper", "content://photos/1")) }
        ShadowContentResolver.registerProviderInternal(
            "com.android.contacts", CursorProvider(cursor),
        )
        val platform = Platform(ctx)
        val method = Platform::class.java.getDeclaredMethod(
            "readLegacyContact", Uri::class.java,
        ).apply { isAccessible = true }
        val result = method.invoke(platform, Uri.parse("content://com.android.contacts/contacts/1"))
        assertEquals("Grace Hopper" to "content://photos/1", result)
    }

    @Test fun readLegacyContactEmptyCursorIsNull() {
        val cursor = MatrixCursor(arrayOf(
            ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.PHOTO_URI,
        ))
        ShadowContentResolver.registerProviderInternal(
            "com.android.contacts", CursorProvider(cursor),
        )
        val platform = Platform(ctx)
        val method = Platform::class.java.getDeclaredMethod(
            "readLegacyContact", Uri::class.java,
        ).apply { isAccessible = true }
        assertNull(method.invoke(platform, Uri.parse("content://com.android.contacts/contacts/2")))
    }

    @Test fun readSessionContactAggregatesRowsAndPhoto() {
        val photoBytes = byteArrayOf(1, 2, 3, 4)
        val cursor = MatrixCursor(arrayOf(
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Contacts.Photo.PHOTO,
        )).apply {
            addRow(arrayOf("lk-1", "Ada Lovelace",
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, null))
            addRow(arrayOf("lk-1", "Ada Lovelace",
                ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE, photoBytes))
            addRow(arrayOf("lk-2", "Other Person",
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, null))
        }
        ShadowContentResolver.registerProviderInternal(
            "com.android.contacts", CursorProvider(cursor),
        )
        val platform = Platform(ctx)
        val method = Platform::class.java.getDeclaredMethod(
            "readSessionContact", Uri::class.java,
        ).apply { isAccessible = true }
        val picked = method.invoke(platform, Uri.parse("content://com.android.contacts/session/1"))
        assertNotNull(picked)
        val name = picked.javaClass.getDeclaredMethod("getName").apply { isAccessible = true }.invoke(picked)
        val photo = picked.javaClass.getDeclaredMethod("getPhoto").apply { isAccessible = true }.invoke(picked) as String?
        assertEquals("Ada Lovelace", name)
        assertNotNull(photo)
        assertTrue(photo.startsWith("data:image/jpeg;base64,"))
    }
}
