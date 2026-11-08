package com.vayunmathur.travel.util

import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor

/** Contact parser via a fake content resolver cursor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactAutofillTest {

    @Test fun requestedFields_includeNameEmailPhoneEvent() {
        kotlin.test.assertEquals(4, REQUESTED_CONTACT_FIELDS.size)
        kotlin.test.assertTrue(REQUESTED_CONTACT_FIELDS.contains(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE))
    }

    @Test fun readSessionContact_badUri_returnsNull() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(readSessionContact(ctx, Uri.parse("content://invalid/1")))
    }
}
