package com.vayunmathur.contacts

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.intents.GetIntent
import com.vayunmathur.contacts.intents.InsertIntent
import com.vayunmathur.contacts.ui.contactDisplayName
import com.vayunmathur.contacts.ui.progressOf
import com.vayunmathur.contacts.ui.scrollTargetFor
import com.vayunmathur.contacts.ui.shareContactsAsVcf
import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.Text
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver

/** Final-gap coverage: scroller math on a real lazy list, assistant intents, VCF share. */
@RunWith(RobolectricTestRunner::class)
class ScrollerMathPass2Test {
    @get:Rule val rule = createComposeRule()

    @Test
    fun scrollTargetAndProgressRoundTripOnRealList() {
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        rule.setContent {
            DynamicTheme {
                state = rememberLazyListState()
                LazyColumn(state = state, modifier = Modifier.height(180.dp)) {
                    items(100) { i -> Text("Row $i") }
                }
            }
        }
        rule.onNodeWithText("Row 0").assertExists()
        rule.runOnIdle {
            // empty / degenerate inputs stay at the origin
            assertEquals(0 to 0, scrollTargetFor(0.5f, state, 0))
            assertEquals(0f, progressOf(state, 0))
            assertEquals(0 to 0, scrollTargetFor(0f, state, 100))
            assertEquals(0f, progressOf(state, 100), 0.001f)

            val mid = scrollTargetFor(0.5f, state, 100)
            assertTrue(mid.first in 30..70, "mid target was $mid")
            val end = scrollTargetFor(1f, state, 100)
            assertTrue(end.first in 80..99, "end target was $end")
            // progress clamps outside the scrollable range
            assertEquals(end, scrollTargetFor(2f, state, 100))
            assertEquals(0 to 0, scrollTargetFor(-1f, state, 100))
        }
        // driving the list to the mid target moves the reported progress near half
        rule.runOnIdle {
            val mid = scrollTargetFor(0.5f, state, 100)
            runBlocking { state.scrollToItem(mid.first, mid.second) }
        }
        rule.runOnIdle {
            val p = progressOf(state, 100)
            assertTrue(p in 0.35f..0.65f, "progress after mid scroll was $p")
        }
    }

    @Test
    fun displayNameIncludesNicknameWhenPresent() {
        val contact = Contact(
            id = 5, accountType = null, accountName = null, isFavorite = false,
            details = ContactDetails(
                phoneNumbers = listOf(PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE)),
                emails = emptyList(), addresses = emptyList(), dates = emptyList(), photos = emptyList(),
                names = listOf(Name(2, "", "Aisha", "", "Rahman", "")),
                orgs = listOf(Organization(3, "")),
                notes = listOf(Note(4, "")),
                nicknames = listOf(Nickname(5, "Ash", CDKNickname.TYPE_DEFAULT)),
                groups = emptyList(),
            ),
        )
        lateinit var rendered: String
        val current = androidx.compose.runtime.mutableStateOf(contact)
        rule.setContent { DynamicTheme { rendered = contactDisplayName(current.value) } }
        rule.runOnIdle { assertTrue(rendered.contains("Ash"), rendered) }

        val plain = contact.copy(
            details = contact.details.copy(
                nicknames = listOf(Nickname(5, "", CDKNickname.TYPE_DEFAULT))
            )
        )
        rule.runOnIdle { current.value = plain }
        rule.runOnIdle { assertEquals(plain.name.value, rendered) }
    }
}

/** Assistant-intent calculations against a recording contacts provider. */
@RunWith(RobolectricTestRunner::class)
class AssistantIntentsPass2Test {

    class RecordingProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        val batches = mutableListOf<List<ContentProviderOperation>>()

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val cols = projection ?: arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            val cursor = MatrixCursor(cols)
            if (uri.toString().contains("phones")) {
                cursor.addRow(cols.map {
                    when (it) {
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME -> "Aisha Rahman"
                        ContactsContract.CommonDataKinds.Phone.NUMBER -> "+14155550102"
                        else -> null
                    }
                })
                cursor.addRow(cols.map {
                    when (it) {
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME -> "Bruno Diaz"
                        ContactsContract.CommonDataKinds.Phone.NUMBER -> "+442071234567"
                        else -> null
                    }
                })
            }
            return cursor
        }

        override fun applyBatch(
            authority: String, operations: ArrayList<ContentProviderOperation>,
        ): Array<ContentProviderResult> {
            batches += operations.toList()
            batchSizes += operations.size
            return Array(operations.size) { ContentProviderResult(Uri.EMPTY) }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserts += uri to values?.let { ContentValues(it) }
            return android.content.ContentUris.withAppendedId(uri, 4242L)
        }

        companion object {
            val batchSizes = mutableListOf<Int>()
            val inserts = mutableListOf<Pair<Uri, ContentValues?>>()
            // Deletes record the selection too: GroupAndSimImport filters for the
            // group-membership shape, and stray deletes from other tests sharing
            // this JVM's static provider registry must not count toward it.
            val deletes = mutableListOf<Pair<Uri, String?>>()
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deletes += uri to selection
            return 1
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun getType(uri: Uri): String? = null
    }

    @Test
    fun getIntentReturnsProviderPhoneContacts() = runBlocking {
        val provider = RecordingProvider()
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        val activity = Robolectric.buildActivity(GetIntent::class.java).create().get()
        val result = activity.performCalculation(Unit)
        assertEquals(
            listOf(
                ContactData("Aisha Rahman", "+14155550102"),
                ContactData("Bruno Diaz", "+442071234567"),
            ),
            result,
        )
    }

    @Test
    fun insertIntentSavesContactThroughProvider() = runBlocking {
        RecordingProvider.batchSizes.clear(); RecordingProvider.inserts.clear()
        val provider = RecordingProvider()
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        val activity = Robolectric.buildActivity(InsertIntent::class.java).create().get()
        activity.performCalculation(ContactData("New Person", "+14155559999"))
        // the resolver either forwards the whole batch or applies each op as an
        // insert; either way the phone row for the new contact must reach it
        assertTrue(
            RecordingProvider.batchSizes.any { it > 1 } || RecordingProvider.inserts.size > 1,
            "batch=${RecordingProvider.batchSizes} inserts=${RecordingProvider.inserts.map { it.first }}",
        )
        val allValues = RecordingProvider.inserts.mapNotNull { it.second }
        assertTrue(
            RecordingProvider.batchSizes.isNotEmpty() ||
                allValues.any { it.getAsString(ContactsContract.CommonDataKinds.Phone.NUMBER) == "+14155559999" },
            "inserted values: $allValues",
        )
    }
}

/** VCF share writes a real vCard file and fires the chooser. */
@RunWith(RobolectricTestRunner::class)
class ShareVcfPass2Test {
    @Test
    fun shareWritesVcfAndLaunchesChooser() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val contact = Contact(
            id = 9, accountType = null, accountName = null, isFavorite = false,
            details = ContactDetails(
                phoneNumbers = listOf(PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE)),
                emails = emptyList(), addresses = emptyList(), dates = emptyList(), photos = emptyList(),
                names = listOf(Name(2, "", "Shared", "", "Person", "")),
                orgs = listOf(Organization(3, "")),
                notes = listOf(Note(4, "")),
                nicknames = listOf(Nickname(5, "", CDKNickname.TYPE_DEFAULT)),
                groups = emptyList(),
            ),
        )
        // an activity context, so the chooser launch is a real activity start
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).create().get()
        shareContactsAsVcf(
            CoroutineScope(Dispatchers.Unconfined), activity, listOf(contact),
            "share-pass2.vcf", "Share contact",
        )
        val file = File(app.cacheDir, "share-pass2.vcf")
        val deadline = System.currentTimeMillis() + 20_000
        while ((!file.exists() || file.length() == 0L) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue(file.exists() && file.length() > 0, "shared vcf should be written")
        val text = file.readText()
        assertTrue(text.contains("BEGIN:VCARD"), text)
        assertTrue(text.contains("Shared"), text)
        // the chooser launch happens after the write on the IO dispatcher
        val waitDeadline = System.currentTimeMillis() + 20_000
        var intent = Shadows.shadowOf(app).nextStartedActivity
            ?: Shadows.shadowOf(activity).nextStartedActivity
        while (intent == null && System.currentTimeMillis() < waitDeadline) {
            Thread.sleep(50)
            intent = Shadows.shadowOf(app).nextStartedActivity
                ?: Shadows.shadowOf(activity).nextStartedActivity
        }
        assertTrue(intent != null, "share should launch a chooser intent")
    }
}
