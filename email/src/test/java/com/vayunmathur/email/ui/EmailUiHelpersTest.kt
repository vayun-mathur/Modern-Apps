package com.vayunmathur.email.ui

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * Content-resolver helpers behind the composer pickers: a picked
 * attachment must show the provider's display name and size, a picked
 * contact must yield its email address, and anything unreadable must
 * fall back (last path segment / zero / null) instead of crashing the
 * composer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailUiHelpersTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    /** Serves one canned row whose values are keyed by column name. */
    class CannedProvider(private val values: Map<String, Any?>) : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val cols = projection ?: values.keys.toTypedArray()
            val cursor = MatrixCursor(cols)
            cursor.addRow(cols.map { values[it] })
            return cursor
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    class EmptyProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor = MatrixCursor(projection ?: arrayOf("_display_name"))
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    @BeforeTest
    fun registerProviders() {
        ShadowContentResolver.registerProviderInternal(
            "canned.test",
            CannedProvider(
                mapOf(
                    "_display_name" to "report.pdf",
                    "_size" to 4321L,
                    "data1" to "person@x.com",
                )
            ),
        )
        ShadowContentResolver.registerProviderInternal("empty.test", EmptyProvider())
    }

    @Test
    fun `uriName reads the provider display name`() {
        assertEquals("report.pdf", uriName(context, Uri.parse("content://canned.test/items/1")))
    }

    @Test
    fun `uriName falls back to the last path segment and then attachment`() {
        assertEquals("photo.jpg", uriName(context, Uri.parse("file:///storage/photo.jpg")))
        assertEquals("attachment", uriName(context, Uri.parse("content://empty.test")))
    }

    @Test
    fun `uriSize reads the provider size and defaults to zero`() {
        assertEquals(4321L, uriSize(context, Uri.parse("content://canned.test/items/1")))
        assertEquals(0L, uriSize(context, Uri.parse("file:///storage/photo.jpg")))
        assertEquals(0L, uriSize(context, Uri.parse("content://empty.test/items/1")))
    }

    @Test
    fun `contactEmail reads the address column and is null when absent`() {
        assertEquals("person@x.com", contactEmail(context, Uri.parse("content://canned.test/contacts/1")))
        assertNull(contactEmail(context, Uri.parse("content://empty.test/contacts/1")))
    }
}
