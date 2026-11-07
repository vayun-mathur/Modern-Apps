package com.vayunmathur.networklocation.provider

import android.content.Context
import android.net.Uri
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.networklocation.OfflineDatabases
import com.vayunmathur.networklocation.writeGeocoderDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
@RunWith(RobolectricTestRunner::class)
class DatabaseStatusProviderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun clearAll() {
        for (n in listOf(OfflineDatabases.GEOCODER, OfflineDatabases.WIFI, OfflineDatabases.CELL)) {
            OfflineDatabases.file(context, n).delete()
        }
    }

    private fun installAll() {
        writeGeocoderDb(OfflineDatabases.file(context, OfflineDatabases.GEOCODER))
        OfflineDatabases.file(context, OfflineDatabases.WIFI).writeBytes(byteArrayOf(1))
        OfflineDatabases.file(context, OfflineDatabases.CELL).writeBytes(byteArrayOf(1))
    }

    @Test
    fun `contract values are the published ones`() {
        assertEquals("com.vayunmathur.networklocation.status", DatabaseStatusContract.AUTHORITY)
        assertEquals(
            "content://com.vayunmathur.networklocation.status/databases",
            DatabaseStatusContract.DATABASES.toString(),
        )
        assertEquals("all_present", DatabaseStatusContract.COLUMN_ALL_PRESENT)
    }

    @Test
    fun `query reports zero until every database is present then one`() {
        clearAll()
        val provider = Robolectric.buildContentProvider(DatabaseStatusProvider::class.java).create().get()
        provider.query(
            DatabaseStatusContract.DATABASES, null, null, null, null,
        )!!.use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            assertEquals(DatabaseStatusContract.COLUMN_ALL_PRESENT, c.getColumnName(0))
        }
        installAll()
        provider.query(
            DatabaseStatusContract.DATABASES, null, null, null, null,
        )!!.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }

    @Test
    fun `query for another uri returns null and writes are no-ops`() {
        clearAll()
        val provider = Robolectric.buildContentProvider(DatabaseStatusProvider::class.java).create().get()
        val other = Uri.parse("content://com.vayunmathur.networklocation.status/other")
        assertNull(provider.query(other, null, null, null, null))
        assertNull(provider.getType(DatabaseStatusContract.DATABASES))
        assertNull(provider.insert(DatabaseStatusContract.DATABASES, null))
        assertEquals(0, provider.update(DatabaseStatusContract.DATABASES, null, null, null))
        assertEquals(0, provider.delete(DatabaseStatusContract.DATABASES, null, null))
    }

    @Test
    fun `a non-system caller is rejected`() {
        clearAll()
        val provider = Robolectric.buildContentProvider(DatabaseStatusProvider::class.java).create().get()
        // Robolectric's Binder shadow defaults the calling uid to our own; move it elsewhere.
        org.robolectric.shadows.ShadowBinder.setCallingUid(Process.SYSTEM_UID + 4242)
        try {
            assertFailsWith<SecurityException> {
                provider.query(DatabaseStatusContract.DATABASES, null, null, null, null)
            }
        } finally {
            org.robolectric.shadows.ShadowBinder.reset()
        }
        // As the system uid the same query is admitted.
        org.robolectric.shadows.ShadowBinder.setCallingUid(Process.SYSTEM_UID)
        try {
            provider.query(DatabaseStatusContract.DATABASES, null, null, null, null)!!.close()
        } finally {
            org.robolectric.shadows.ShadowBinder.reset()
        }
    }
}
