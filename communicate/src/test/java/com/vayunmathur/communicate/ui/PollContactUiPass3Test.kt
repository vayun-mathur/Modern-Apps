package com.vayunmathur.communicate.ui

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UiPass3 coverage for the poll composer (`PollComposerDialog.kt`) and the
 * contact hand-off helpers (`ContactIntents.kt`, `SharedContactCard.kt`).
 */
@RunWith(RobolectricTestRunner::class)
class PollContactUiPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun str(id: Int): String = context.getString(id)

    @Test
    fun `poll needs a question and two options before it can be created`() {
        rule.setContent { PollComposerDialog(onDismiss = {}, onCreate = { _, _ -> }) }
        rule.onNodeWithText(str(R.string.poll_new)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.poll_create)).assertIsNotEnabled()
        rule.onNodeWithText(str(R.string.poll_question)).performTextInput("Lunch?")
        rule.onNodeWithText(context.getString(R.string.poll_option, 1)).performTextInput("Pizza")
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.poll_create)).assertIsNotEnabled()
        rule.onNodeWithText(context.getString(R.string.poll_option, 2)).performTextInput("Sushi")
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.poll_create)).assertIsEnabled()
    }

    @Test
    fun `poll create trims and drops blank options`() {
        var created: Pair<String, List<String>>? = null
        var dismissed = 0
        rule.setContent {
            PollComposerDialog(onDismiss = { dismissed++ }, onCreate = { q, o -> created = q to o })
        }
        rule.onNodeWithText(str(R.string.poll_question)).performTextInput("  Lunch? ")
        rule.onNodeWithText(context.getString(R.string.poll_option, 1)).performTextInput(" Pizza ")
        rule.onNodeWithText(context.getString(R.string.poll_option, 2)).performTextInput("Sushi")
        rule.onNodeWithText(str(R.string.poll_add_option)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.poll_create)).performClick()
        rule.waitForIdle()
        assertEquals("Lunch?" to listOf("Pizza", "Sushi"), created)
        assertEquals(1, dismissed)
    }

    @Test
    fun `poll options can be added and removed but never below two`() {
        rule.setContent { PollComposerDialog(onDismiss = {}, onCreate = { _, _ -> }) }
        rule.onNodeWithText(str(R.string.poll_add_option)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(R.string.poll_option, 3)).assertIsDisplayed()
        // Removing the third row returns to the two-row floor.
        rule.onAllNodes(
            androidx.compose.ui.test.hasContentDescriptionExactly("Close"),
        )[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(R.string.poll_option, 3)).assertDoesNotExist()
    }

    @Test
    fun `poll cancel dismisses without creating`() {
        var created = 0
        var dismissed = 0
        rule.setContent {
            PollComposerDialog(onDismiss = { dismissed++ }, onCreate = { _, _ -> created++ })
        }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        rule.waitForIdle()
        assertEquals(0, created)
        assertEquals(1, dismissed)
    }

    // -- contact intents / cards ----------------------------------------------

    @Test
    fun `contact intents ignore blank numbers`() {
        // No activity is started and nothing throws for a blank number.
        ContactIntents.createNew(context, "  ")
        ContactIntents.addToExisting(context, "")
    }

    @Test
    fun `reading an unresolvable contact returns null`() {
        assertNull(readSharedContact(context, Uri.parse("content://com.android.contacts/contacts/999")))
    }

    /** Minimal contacts provider: one contact row, two numbers, one email. */
    private class FakeContactsProvider : android.content.ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = 0
        override fun update(
            uri: Uri, values: android.content.ContentValues?, selection: String?, args: Array<String>?,
        ): Int = 0

        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): android.database.Cursor? {
            val path = uri.toString()
            return when {
                path.contains("phones") -> android.database.MatrixCursor(
                    arrayOf(
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                        android.provider.ContactsContract.CommonDataKinds.Phone.LABEL,
                    ),
                ).apply {
                    addRow(arrayOf("+155501", "Mobile"))
                    addRow(arrayOf(" +155502 ", null))
                    addRow(arrayOf("  ", "Home"))
                }
                path.contains("emails") -> android.database.MatrixCursor(
                    arrayOf(android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS),
                ).apply {
                    addRow(arrayOf(" pat@example.com "))
                    addRow(arrayOf(" "))
                }
                else -> android.database.MatrixCursor(
                    arrayOf(
                        android.provider.ContactsContract.Contacts._ID,
                        android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ),
                ).apply { addRow(arrayOf("5", "Pat Lee")) }
            }
        }
    }

    @Test
    fun `reading a picked contact splits the name and trims rows`() {
        org.robolectric.Shadows.shadowOf(context.contentResolver)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
            android.provider.ContactsContract.AUTHORITY, FakeContactsProvider(),
        )
        val card = readSharedContact(
            context, Uri.parse("content://com.android.contacts/contacts/5"),
        )
        assertEquals("Pat", card?.givenName)
        assertEquals("Lee", card?.familyName)
        assertEquals(
            listOf("+155501" to "Mobile", "+155502" to null),
            card?.phoneNumbers,
        )
        assertEquals(listOf("pat@example.com"), card?.emails)
    }

    @Test
    fun `shared contact card keeps its fields`() {
        val card = SharedContactCard(
            givenName = "Pat",
            familyName = "Lee",
            phoneNumbers = listOf("+1555" to "Mobile"),
            emails = listOf("pat@example.com"),
        )
        assertEquals("Pat", card.givenName)
        assertEquals("Lee", card.familyName)
        assertEquals(listOf("+1555" to "Mobile"), card.phoneNumbers)
        assertEquals(listOf("pat@example.com"), card.emails)
    }
}
