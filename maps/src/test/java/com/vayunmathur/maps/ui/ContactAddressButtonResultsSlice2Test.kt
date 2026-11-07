package com.vayunmathur.maps.ui

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.maps.util.ContactLookupProvidersSlice2Test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * The contact-address button with picker results actually delivered: a fake
 * activity-result registry answers each launch with a canned result, and the
 * fake contacts provider serves the rows behind the returned URIs.
 * Slice-2 coverage for maps (basename C, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class ContactAddressButtonResultsSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    /** Answers every launch immediately with [responder]'s result. */
    private class FakeRegistry(
        var responder: (contract: ActivityResultContract<*, *>, input: Any?) -> Pair<Int, Intent?>,
    ) : ActivityResultRegistry() {
        val launches = mutableListOf<String>()
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launches += contract.javaClass.simpleName
            val (code, data) = responder(contract, input)
            dispatchResult(requestCode, code, data)
        }
    }

    private class ThrowingContactsProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): Cursor = throw SecurityException("no READ_CONTACTS")
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    }

    private val rowUri = Uri.parse("content://com.android.contacts/data/7")
    private val contactUri = Uri.parse("content://com.android.contacts/contacts/9")

    private fun providerWith(rows: Map<String, List<Array<Any?>>>) {
        val provider = Robolectric.setupContentProvider(
            ContactLookupProvidersSlice2Test.FakeContactsProvider::class.java,
            "com.android.contacts",
        )
        provider.tables = rows
    }

    private fun postalRow(formatted: String?): Array<Any?> =
        arrayOf(
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
            formatted, null, null, null, null, null, "9",
        )

    private fun setUpButton(registry: FakeRegistry, picked: MutableList<String>) {
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ContactAddressButton(onAddress = { picked += it })
            }
        }
    }

    private fun dataIntent(uri: Uri?) = Intent().apply { data = uri }

    @Test fun `a picked postal row delivers its address`() {
        providerWith(mapOf(rowUri.toString() to listOf(postalRow("1 Ferry Building\nSan Francisco, CA 94111"))))
        val registry = FakeRegistry { _, _ -> Activity.RESULT_OK to dataIntent(rowUri) }
        val picked = mutableListOf<String>()
        setUpButton(registry, picked)
        rule.onNodeWithContentDescription("Contacts").performClick()
        rule.waitForIdle()
        assertEquals(listOf("1 Ferry Building, San Francisco, CA 94111"), picked)
    }

    @Test fun `a cancelled pick delivers nothing`() {
        providerWith(emptyMap())
        val registry = FakeRegistry { _, _ -> Activity.RESULT_CANCELED to null }
        val picked = mutableListOf<String>()
        setUpButton(registry, picked)
        rule.onNodeWithContentDescription("Contacts").performClick()
        rule.waitForIdle()
        assertEquals(emptyList(), picked)
        rule.onNodeWithText("Choose an address").assertDoesNotExist()
    }

    @Test fun `an empty postal row falls back to the contact picker and a single address delivers`() {
        providerWith(
            mapOf(
                rowUri.toString() to listOf(postalRow(null)),
                contactUri.toString() to listOf(arrayOf<Any?>("9", null, null, null, null, null, null, "9")),
                ContactsContract.Data.CONTENT_URI.toString() to listOf(postalRow("2 Mission St, San Francisco")),
            ),
        )
        val registry = FakeRegistry { contract, _ ->
            if (contract is ActivityResultContracts.PickContact) {
                Activity.RESULT_OK to dataIntent(contactUri)
            } else {
                Activity.RESULT_OK to dataIntent(rowUri)
            }
        }
        val picked = mutableListOf<String>()
        setUpButton(registry, picked)
        rule.onNodeWithContentDescription("Contacts").performClick()
        rule.waitForIdle()
        assertEquals(listOf("2 Mission St, San Francisco"), picked)
        assertTrue(registry.launches.size >= 2, "postal then contact: ${registry.launches}")
    }

    @Test fun `several contact addresses open the chooser and tapping one delivers it`() {
        providerWith(
            mapOf(
                rowUri.toString() to listOf(postalRow(null)),
                contactUri.toString() to listOf(arrayOf<Any?>("9", null, null, null, null, null, null, "9")),
                ContactsContract.Data.CONTENT_URI.toString() to listOf(
                    postalRow("Home: 1 First St"),
                    postalRow("Work: 2 Second Ave"),
                ),
            ),
        )
        val registry = FakeRegistry { contract, _ ->
            if (contract is ActivityResultContracts.PickContact) {
                Activity.RESULT_OK to dataIntent(contactUri)
            } else {
                Activity.RESULT_OK to dataIntent(rowUri)
            }
        }
        val picked = mutableListOf<String>()
        setUpButton(registry, picked)
        rule.onNodeWithContentDescription("Contacts").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Choose an address").assertIsDisplayed()
        assertEquals(emptyList(), picked)
        rule.onNodeWithText("Work: 2 Second Ave").performClick()
        rule.waitForIdle()
        assertEquals(listOf("Work: 2 Second Ave"), picked)
        rule.onNodeWithText("Choose an address").assertDoesNotExist()
    }

    @Test fun `dismissing the chooser delivers nothing`() {
        providerWith(
            mapOf(
                rowUri.toString() to listOf(postalRow(null)),
                contactUri.toString() to listOf(arrayOf<Any?>("9", null, null, null, null, null, null, "9")),
                ContactsContract.Data.CONTENT_URI.toString() to listOf(
                    postalRow("Home: 1 First St"),
                    postalRow("Work: 2 Second Ave"),
                ),
            ),
        )
        val registry = FakeRegistry { contract, _ ->
            if (contract is ActivityResultContracts.PickContact) {
                Activity.RESULT_OK to dataIntent(contactUri)
            } else {
                Activity.RESULT_OK to dataIntent(rowUri)
            }
        }
        val picked = mutableListOf<String>()
        setUpButton(registry, picked)
        rule.onNodeWithContentDescription("Contacts").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Choose an address").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(emptyList(), picked)
        rule.onNodeWithText("Choose an address").assertDoesNotExist()
    }

    @Test fun `an ok pick with no data delivers nothing`() {
        providerWith(emptyMap())
        val registry = FakeRegistry { _, _ -> Activity.RESULT_OK to Intent() }
        val picked = mutableListOf<String>()
        setUpButton(registry, picked)
        rule.onNodeWithContentDescription("Contacts").performClick()
        rule.waitForIdle()
        assertEquals(emptyList(), picked)
    }

    @Test fun `a contact with no postal addresses delivers nothing and shows no chooser`() {
        providerWith(
            mapOf(
                rowUri.toString() to listOf(postalRow(null)),
                contactUri.toString() to listOf(arrayOf<Any?>("9", null, null, null, null, null, null, "9")),
                ContactsContract.Data.CONTENT_URI.toString() to emptyList(),
            ),
        )
        val registry = FakeRegistry { contract, _ ->
            if (contract is ActivityResultContracts.PickContact) {
                Activity.RESULT_OK to dataIntent(contactUri)
            } else {
                Activity.RESULT_OK to dataIntent(rowUri)
            }
        }
        val picked = mutableListOf<String>()
        setUpButton(registry, picked)
        rule.onNodeWithContentDescription("Contacts").performClick()
        rule.waitForIdle()
        assertEquals(emptyList(), picked)
        rule.onNodeWithText("Choose an address").assertDoesNotExist()
    }

    /** Refuses every query until [refuse] flips, then serves one postal row. */
    private class FlakyContactsProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): Cursor {
            if (refuse) throw SecurityException("no READ_CONTACTS yet")
            val cursor = android.database.MatrixCursor(
                arrayOf(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                    ContactsContract.CommonDataKinds.StructuredPostal.STREET,
                    ContactsContract.CommonDataKinds.StructuredPostal.CITY,
                    ContactsContract.CommonDataKinds.StructuredPostal.REGION,
                    ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
                    ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY,
                    ContactsContract.Contacts._ID,
                ),
            )
            cursor.addRow(
                arrayOf<Any?>(
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
                    "3 Third St, Springfield", null, null, null, null, null, "9",
                ),
            )
            return cursor
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

        companion object {
            var refuse = true
        }
    }

    @Test fun `a granted permission retries the refused contact read`() {
        FlakyContactsProvider.refuse = true
        Robolectric.setupContentProvider(FlakyContactsProvider::class.java, "com.android.contacts")
        val registry = FakeRegistry { contract, _ ->
            when (contract) {
                is ActivityResultContracts.PickContact -> Activity.RESULT_OK to dataIntent(contactUri)
                is ActivityResultContracts.RequestPermission -> {
                    FlakyContactsProvider.refuse = false
                    Activity.RESULT_OK to Intent().putExtra(
                        "androidx.activity.result.contract.extra.PERMISSION_GRANT_RESULTS",
                        intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED),
                    )
                }
                else -> Activity.RESULT_OK to dataIntent(rowUri)
            }
        }
        val picked = mutableListOf<String>()
        setUpButton(registry, picked)
        rule.onNodeWithContentDescription("Contacts").performClick()
        rule.waitForIdle()
        assertEquals(listOf("3 Third St, Springfield"), picked)
    }

    @Test fun `a cancelled contact pick after an empty postal row delivers nothing`() {
        providerWith(mapOf(rowUri.toString() to listOf(postalRow(null))))
        val registry = FakeRegistry { contract, _ ->
            if (contract is ActivityResultContracts.PickContact) {
                Activity.RESULT_CANCELED to null
            } else {
                Activity.RESULT_OK to dataIntent(rowUri)
            }
        }
        val picked = mutableListOf<String>()
        setUpButton(registry, picked)
        rule.onNodeWithContentDescription("Contacts").performClick()
        rule.waitForIdle()
        assertEquals(emptyList(), picked)
        rule.onNodeWithText("Choose an address").assertDoesNotExist()
    }

    @Test fun `a refused contact read asks for permission and a denial delivers nothing`() {
        Robolectric.setupContentProvider(ThrowingContactsProvider::class.java, "com.android.contacts")
        val registry = FakeRegistry { contract, _ ->
            when (contract) {
                is ActivityResultContracts.PickContact -> Activity.RESULT_OK to dataIntent(contactUri)
                is ActivityResultContracts.RequestPermission -> Activity.RESULT_OK to Intent()
                else -> Activity.RESULT_OK to dataIntent(rowUri)
            }
        }
        val picked = mutableListOf<String>()
        setUpButton(registry, picked)
        rule.onNodeWithContentDescription("Contacts").performClick()
        rule.waitForIdle()
        // Postal read was refused too, so the flow fell back to the contact
        // picker, whose read was refused again: permission was requested and
        // the (empty-intent) answer counts as denied.
        assertEquals(emptyList(), picked)
        assertTrue(
            registry.launches.any { it.contains("RequestPermission") },
            "permission requested: ${registry.launches}",
        )
    }
}
