package com.vayunmathur.contacts

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email as EmailKind
import android.provider.ContactsContract.CommonDataKinds.Phone as PhoneKind
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.Intents.Insert
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import kotlin.test.BeforeTest
import org.junit.rules.ExternalResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver

private fun grantAndRegister() {
    val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    Shadows.shadowOf(app).grantPermissions(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
    )
    ShadowContentResolver.registerProviderInternal(
        ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
    )
}

private typealias MainRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

private fun intentRule(intent: Intent): MainRule =
    AndroidComposeTestRule(ActivityScenarioRule<MainActivity>(intent)) { rule ->
        lateinit var act: MainActivity
        rule.scenario.onActivity { act = it }
        act
    }

private fun MainRule.awaitText(text: String) {
    waitUntil(20_000) {
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }
}

/** ACTION_INSERT with scalar extras and Insert.DATA rows lands in a prefilled editor. */
@RunWith(RobolectricTestRunner::class)
class InsertIntentActivityPass2Test {
    companion object {
        private val intent = Intent(Intent.ACTION_INSERT).apply {
            setDataAndType(ContactsContract.Contacts.CONTENT_URI, ContactsContract.Contacts.CONTENT_TYPE)
            putExtra(Insert.NAME, "Zoe Inserted")
            putExtra(Insert.PHONE, "+14155550111")
            putExtra(Insert.PHONE_TYPE, PhoneKind.TYPE_WORK)
            putExtra(Insert.EMAIL, "zoe@x.com")
            putExtra(Insert.COMPANY, "InsertCo")
            val rows = arrayListOf(
                ContentValues().apply {
                    put(ContactsContract.Data.MIMETYPE, EmailKind.CONTENT_ITEM_TYPE)
                    put(EmailKind.ADDRESS, "row@x.com")
                    put(EmailKind.TYPE, EmailKind.TYPE_HOME)
                },
                ContentValues().apply {
                    put(ContactsContract.Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
                    put(StructuredPostal.FORMATTED_ADDRESS, "9 Row Street")
                    put(StructuredPostal.TYPE, StructuredPostal.TYPE_HOME)
                },
                ContentValues().apply {
                    put(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                    put(StructuredName.DISPLAY_NAME, "Should Not Win")
                },
            )
            putParcelableArrayListExtra(Insert.DATA, rows)
        }
    }

    val registerRule = object : ExternalResource() {
        override fun before() = grantAndRegister()
    }

    val rule: MainRule = intentRule(intent)

    // registration must happen before the activity's onCreate routing
    // queries the provider, so it explicitly wraps the compose rule
    @get:Rule
    val chain: org.junit.rules.TestRule =
        org.junit.rules.RuleChain.outerRule(registerRule).around(rule)

    @BeforeTest fun setUp() = grantAndRegister()

    @Test
    fun insertIntentOpensPrefilledEditor() {
        rule.awaitText("Zoe Inserted")
        assertTrue(
            rule.onAllNodesWithText("+14155550111", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("zoe@x.com", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
    }
}

/** ACTION_EDIT on a contacts URI opens that contact's editor. */
@RunWith(RobolectricTestRunner::class)
class EditIntentActivityPass2Test {
    companion object {
        private val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(
                Uri.parse("content://com.android.contacts/contacts/7"),
                ContactsContract.Contacts.CONTENT_ITEM_TYPE,
            )
        }
    }

    val registerRule = object : ExternalResource() {
        override fun before() = grantAndRegister()
    }

    val rule: MainRule = intentRule(intent)

    // registration must happen before the activity's onCreate routing
    // queries the provider, so it explicitly wraps the compose rule
    @get:Rule
    val chain: org.junit.rules.TestRule =
        org.junit.rules.RuleChain.outerRule(registerRule).around(rule)

    @BeforeTest fun setUp() = grantAndRegister()

    @Test
    fun editIntentOpensExistingEditor() {
        // the editor title proves routing; the prefilled name proves the draft loaded
        rule.awaitText("Edit contact")
        rule.awaitText("Aisha")
    }

}

/** SHOW_OR_CREATE_CONTACT with a tel: URI opens the add-to-contact flow for that number. */
@RunWith(RobolectricTestRunner::class)
class InsertOrEditIntentActivityPass2Test {
    companion object {
        private val intent = Intent(ContactsContract.Intents.SHOW_OR_CREATE_CONTACT).apply {
            data = Uri.parse("tel:+14155550199")
        }
    }

    val registerRule = object : ExternalResource() {
        override fun before() = grantAndRegister()
    }

    val rule: MainRule = intentRule(intent)

    // registration must happen before the activity's onCreate routing
    // queries the provider, so it explicitly wraps the compose rule
    @get:Rule
    val chain: org.junit.rules.TestRule =
        org.junit.rules.RuleChain.outerRule(registerRule).around(rule)

    @BeforeTest fun setUp() = grantAndRegister()

    @Test
    fun insertOrEditShowsNumber() {
        rule.awaitText("+14155550199")
    }
}

/** Quick-contact on a contacts URI opens the details page. */
@RunWith(RobolectricTestRunner::class)
class QuickContactActivityPass2Test {
    companion object {
        private val intent = Intent(ContactsContract.QuickContact.ACTION_QUICK_CONTACT).apply {
            setDataAndType(
                Uri.parse("content://com.android.contacts/contacts/7"),
                ContactsContract.Contacts.CONTENT_ITEM_TYPE,
            )
        }
    }

    val registerRule = object : ExternalResource() {
        override fun before() = grantAndRegister()
    }

    val rule: MainRule = intentRule(intent)

    // registration must happen before the activity's onCreate routing
    // queries the provider, so it explicitly wraps the compose rule
    @get:Rule
    val chain: org.junit.rules.TestRule =
        org.junit.rules.RuleChain.outerRule(registerRule).around(rule)

    @BeforeTest fun setUp() = grantAndRegister()

    @Test
    fun quickContactOpensDetails() {
        rule.awaitText("Aisha")
        rule.awaitText("Rahman")
    }
}

/** VIEW on a groups URI opens the groups tab. */
@RunWith(RobolectricTestRunner::class)
class ViewGroupActivityPass2Test {
    companion object {
        private val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.contacts/groups/11")
        }
    }

    val registerRule = object : ExternalResource() {
        override fun before() = grantAndRegister()
    }

    val rule: MainRule = intentRule(intent)

    // registration must happen before the activity's onCreate routing
    // queries the provider, so it explicitly wraps the compose rule
    @get:Rule
    val chain: org.junit.rules.TestRule =
        org.junit.rules.RuleChain.outerRule(registerRule).around(rule)

    @BeforeTest fun setUp() = grantAndRegister()

    @Test
    fun viewGroupOpensGroups() {
        rule.awaitText("Family")
    }
}

/** VIEW of a vCard opens the import flow. */
@RunWith(RobolectricTestRunner::class)
class VcfViewActivityPass2Test {
    companion object {
        private val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://downloads/card.vcf"), "text/x-vcard")
        }
    }

    val registerRule = object : ExternalResource() {
        override fun before() {
            grantAndRegister()
            // the import screen streams the URI at parse time (activity create),
            // so the bytes must be registered before launch, not in @BeforeTest
            val vcf = "BEGIN:VCARD\nVERSION:3.0\nFN:Imported Person\nN:Person;Imported;;;\nTEL;TYPE=CELL:+14155550123\nEND:VCARD\n"
            Shadows.shadowOf(
                ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver
            ).registerInputStream(
                Uri.parse("content://downloads/card.vcf"),
                java.io.ByteArrayInputStream(vcf.toByteArray()),
            )
        }
    }

    val rule: MainRule = intentRule(intent)

    // registration must happen before the activity's onCreate routing
    // queries the provider, so it explicitly wraps the compose rule
    @get:Rule
    val chain: org.junit.rules.TestRule =
        org.junit.rules.RuleChain.outerRule(registerRule).around(rule)

    @BeforeTest fun setUp() = grantAndRegister()

    @Test
    fun vcfViewOpensImport() {
        rule.waitUntil(60_000) {
            rule.onAllNodesWithText("Imported Person", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    }

/** ACTION_PICK on contacts opens the picker; tapping a contact finishes with its URI. */
@RunWith(RobolectricTestRunner::class)
class PickIntentActivityPass2Test {
    companion object {
        private val intent = Intent(Intent.ACTION_PICK).apply {
            setDataAndType(ContactsContract.Contacts.CONTENT_URI, ContactsContract.Contacts.CONTENT_TYPE)
        }
    }

    val registerRule = object : ExternalResource() {
        override fun before() = grantAndRegister()
    }

    val rule: MainRule = intentRule(intent)

    @get:Rule
    val chain: org.junit.rules.TestRule =
        org.junit.rules.RuleChain.outerRule(registerRule).around(rule)

    @BeforeTest fun setUp() = grantAndRegister()

    @Test
    fun pickingContactFinishesWithResult() {
        rule.awaitText("Aisha")
        rule.onNodeWithText("Aisha", substring = true).performClick()
        // the activity finishes with RESULT_OK carrying the contact URI
        rule.waitUntil(20_000) { rule.activity.isFinishing }
        val shadow = org.robolectric.Shadows.shadowOf(rule.activity)
        assertEquals(android.app.Activity.RESULT_OK, shadow.resultCode)
        assertEquals(
            "content://com.android.contacts/contacts/7",
            shadow.resultIntent?.data.toString(),
        )
    }
}

/** ACTION_VIEW on a phone_lookup URI resolves the contact and opens details. */
@RunWith(RobolectricTestRunner::class)
class LookupIntentActivityPass2Test {
    companion object {
        private val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                Uri.parse("content://com.android.contacts/phone_lookup/+442071234567"),
                ContactsContract.Contacts.CONTENT_ITEM_TYPE,
            )
        }
    }

    val registerRule = object : ExternalResource() {
        override fun before() = grantAndRegister()
    }

    val rule: MainRule = intentRule(intent)

    @get:Rule
    val chain: org.junit.rules.TestRule =
        org.junit.rules.RuleChain.outerRule(registerRule).around(rule)

    @BeforeTest fun setUp() = grantAndRegister()

    @Test
    fun lookupOpensDetails() {
        // Directly exercise the platform lookup contract first: this distinguishes
        // a fake-provider lookup mismatch from a navigation failure.
        val resolved = ContactsContract.Contacts.lookupContact(
            rule.activity.contentResolver,
            Uri.parse("content://com.android.contacts/contacts/lookup/k7/7"),
        )
        assertEquals("content://com.android.contacts/contacts/7", resolved.toString())
        rule.awaitText("Aisha")
        rule.awaitText("Rahman")
    }
}

/** ACTION_SEND with a vCard stream opens the import flow. */
@RunWith(RobolectricTestRunner::class)
class SendVcfActivityPass2Test {
    companion object {
        private val intent = Intent(Intent.ACTION_SEND).apply {
            val sharedUri = Uri.parse("content://downloads/shared.vcf")
            setDataAndType(sharedUri, "text/x-vcard")
            putExtra(Intent.EXTRA_STREAM, sharedUri)
        }
    }

    val registerRule = object : ExternalResource() {
        override fun before() {
            grantAndRegister()
            val vcf = """
                BEGIN:VCARD
                VERSION:3.0
                FN:Shared Person
                N:Person;Shared;;;
                TEL;TYPE=CELL:+14155550125
                END:VCARD
            """.trimIndent()
            Shadows.shadowOf(
                ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver
            ).registerInputStream(
                Uri.parse("content://downloads/shared.vcf"),
                java.io.ByteArrayInputStream(vcf.toByteArray()),
            )
        }
    }

    val rule: MainRule = intentRule(intent)

    @get:Rule
    val chain: org.junit.rules.TestRule =
        org.junit.rules.RuleChain.outerRule(registerRule).around(rule)

    @BeforeTest fun setUp() = grantAndRegister()

    @Test
    fun sendVcfOpensImport() {
        rule.waitUntil(60_000) {
            rule.onAllNodesWithText("Shared Person", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
