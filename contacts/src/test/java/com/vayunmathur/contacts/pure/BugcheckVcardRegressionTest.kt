package com.vayunmathur.contacts.pure

import android.provider.ContactsContract
import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.util.VcfUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Regression tests for the bugcheck-contacts fixes, exercising the real
 * VcfUtils exporter/parser pair (not mirrored copies).
 */
class BugcheckVcardRegressionTest {

    private fun contactWithAddress(type: Int, label: String = ""): Contact {
        val details = ContactDetails.empty().copy(
            names = listOf(Name(0, "", "Ada", "", "Lovelace", "")),
            addresses = listOf(Address(0, "1 Work Way", type, label)),
        )
        return Contact(0L, null, null, false, details)
    }

    private fun exportAndReparse(contact: Contact): Contact {
        val out = ByteArrayOutputStream()
        runBlocking { VcfUtils.exportContacts(listOf(contact), out) }
        return VcfUtils.parseContacts(
            ByteArrayInputStream(out.toByteArray(Charsets.UTF_8))
        ).single()
    }

    @Test
    fun workAddressSurvivesExportImportRoundTrip() {
        val parsed = exportAndReparse(
            contactWithAddress(ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK)
        )
        assertEquals(
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK,
            parsed.details.addresses.single().type,
        )
    }

    @Test
    fun homeAddressStillRoundTripsAsHome() {
        val parsed = exportAndReparse(
            contactWithAddress(ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME)
        )
        assertEquals(
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME,
            parsed.details.addresses.single().type,
        )
    }
}
