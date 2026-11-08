package com.vayunmathur.files.data.saf

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class StorageUrisTreeTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `volume split keeps colons in folder names`() {
        // Split at the first colon only: an SD-card folder named "A:B" keeps its colon.
        assertEquals("primary" to "A:B/c", StorageUris.volumeAndRelative("primary:A:B/c"))
        assertEquals("1234-ABCD" to "Music", StorageUris.volumeAndRelative("1234-ABCD:Music"))
        assertEquals("primary" to "", StorageUris.volumeAndRelative("primary:"))
    }

    @Test
    fun `volume split rejects malformed ids`() {
        assertNull(StorageUris.volumeAndRelative("nocolon"))
        assertNull(StorageUris.volumeAndRelative(":relative"))
    }

    @Test
    fun `tree uri without a known volume resolves to null, not primary-rooted garbage`() {
        // The old call site split the URI on ':' and re-rooted at the primary
        // volume, so an SD-card pick extracted into the same-named internal
        // folder. Unknown volumes must resolve to null so the caller falls
        // back to the current directory instead.
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/1234-ABCD%3AMusic")
        assertNull(StorageUris.fileForTreeUri(ctx, uri))
    }

    @Test
    fun `non-tree uri resolves to null`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3Aa.txt")
        assertNull(StorageUris.fileForTreeUri(ctx, uri))
    }
}
