package com.vayunmathur.contacts.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** W5: SIM data source failure branches against providers that throw. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimThrowingW5Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    class SecurityThrowingProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor = throw SecurityException("no sim permission")
        override fun insert(uri: Uri, values: ContentValues?): Uri =
            throw SecurityException("no sim permission")
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
        ): Int = throw SecurityException("no sim permission")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            throw SecurityException("no sim permission")
        override fun getType(uri: Uri): String? = null
    }

    class RuntimeThrowingProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor = throw IllegalStateException("icc wedged")
        override fun insert(uri: Uri, values: ContentValues?): Uri =
            throw IllegalStateException("icc wedged")
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
        ): Int = throw IllegalStateException("icc wedged")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            throw IllegalStateException("icc wedged")
        override fun getType(uri: Uri): String? = null
    }

    @BeforeTest
    fun register() {
        ShadowContentResolver.registerProviderInternal("icc", SecurityThrowingProvider())
    }

    @Test
    fun securityFailuresReadAsEmptyAndWritesFail() {
        assertEquals(emptyList(), SimContactsDataSource.listSimContacts(ctx))
        assertFalse(SimContactsDataSource.insertSimContact(ctx, "A", "1", "a@x.com", 3))
        assertFalse(
            SimContactsDataSource.updateSimContact(
                ctx, SimContact(name = "A", number = "1"), "B", "2", "b@x.com", 3,
            )
        )
        assertFalse(
            SimContactsDataSource.deleteSimContact(
                ctx, SimContact(name = "A", number = "1", subscriptionId = 3),
            )
        )
        assertEquals(emptyList(), SimContactsDataSource.simContactsAsContacts(ctx))
    }

    @Test
    fun blankWritesAreRejectedBeforeProviderCall() {
        assertFalse(SimContactsDataSource.insertSimContact(ctx, "", "", null, null))
        assertFalse(
            SimContactsDataSource.updateSimContact(ctx, SimContact(name = "A", number = "1"), "", "")
        )
    }

    @Test
    fun runtimeFailuresAreAlsoContained() {
        ShadowContentResolver.registerProviderInternal("icc", RuntimeThrowingProvider())
        assertEquals(emptyList(), SimContactsDataSource.listSimContacts(ctx))
        assertFalse(SimContactsDataSource.insertSimContact(ctx, "A", "1"))
        assertFalse(SimContactsDataSource.deleteSimContact(ctx, SimContact(name = "", number = "")))
    }
}
