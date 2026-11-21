package com.vayunmathur.contacts.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.contacts.platform.persistSimContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issues #934/#935/#936 against a fake ICC provider that reproduces the two
 * AOSP behaviours the old fake glossed over:
 *
 *  - query returns each email followed by a comma and each ANR followed by a
 *    colon (`IccProvider.loadRecord`), so one address arrives as `"a@x.com,"`;
 *  - update/delete-by-search match the *whole* old record
 *    (`AdnRecord.isEqual` over tag, number, emails and ANRs), so an
 *    incomplete old record matches nothing.
 *
 * The old fake accepted every write, so these bugs were invisible host-side.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SimAdnSemanticsTest {

    data class Row(
        val name: String,
        val number: String,
        val emails: List<String> = emptyList(),
        val anrs: List<String> = emptyList(),
    )

    class AospIccProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val cursor = MatrixCursor(arrayOf("name", "number", "emails", "anrs", "_id"))
            rows.forEachIndexed { index, row ->
                cursor.addRow(
                    arrayOf<Any?>(
                        row.name,
                        row.number,
                        row.emails.takeIf { it.isNotEmpty() }
                            ?.joinToString("") { "$it," },
                        row.anrs.takeIf { it.isNotEmpty() }
                            ?.joinToString("") { "$it:" },
                        index.toLong(),
                    )
                )
            }
            return cursor
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            insertCount++
            rows += Row(
                name = values?.getAsString("tag").orEmpty(),
                number = values?.getAsString("number").orEmpty(),
                emails = split(values?.getAsString("emails"), ','),
                anrs = split(values?.getAsString("anrs"), ':'),
            )
            return "content://icc/adn/0".toUri()
        }

        override fun update(
            uri: Uri, values: ContentValues?, selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            if (refuseWrites) return 0
            val old = Row(
                name = values?.getAsString("tag").orEmpty(),
                number = values?.getAsString("number").orEmpty(),
                emails = split(values?.getAsString("emails"), ','),
                anrs = split(values?.getAsString("anrs"), ':'),
            )
            val index = rows.indexOf(old)
            if (index == -1) return 0
            rows[index] = Row(
                name = values?.getAsString("newTag").orEmpty(),
                number = values?.getAsString("newNumber").orEmpty(),
                emails = split(values?.getAsString("newEmails"), ','),
                anrs = split(values?.getAsString("newAnrs"), ':'),
            )
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            if (refuseWrites) return 0
            var name = ""
            var number = ""
            var emails = emptyList<String>()
            var anrs = emptyList<String>()
            selection.orEmpty().split(" AND ").forEach { param ->
                val pair = param.split("=", limit = 2)
                if (pair.size != 2) return@forEach
                val value = pair[1].trim().removeSurrounding("'")
                when (pair[0].trim()) {
                    "tag", "name" -> name = value
                    "number" -> number = value
                    "emails" -> emails = split(value, ',')
                    "anrs" -> anrs = split(value, ':')
                }
            }
            return if (rows.remove(Row(name, number, emails, anrs))) 1 else 0
        }

        override fun getType(uri: Uri): String? = null

        companion object {
            val rows = mutableListOf<Row>()
            var insertCount = 0
            var refuseWrites = false

            fun split(raw: String?, separator: Char): List<String> =
                raw?.split(separator)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

            fun reset(seed: List<Row>) {
                rows.clear()
                rows += seed
                insertCount = 0
                refuseWrites = false
            }
        }
    }

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal("icc", AospIccProvider())
        AospIccProvider.reset(
            listOf(Row("Aisha Rahman", "+14155550102", listOf("aisha@x.com"), listOf("+999")))
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun providerTrailingCommaIsNotPartOfTheEmail() {
        val listed = SimContactsDataSource.listSimContacts(ctx)
        assertEquals(1, listed.size)
        assertEquals("aisha@x.com", listed.single().emails)
        assertEquals("+999", listed.single().anrs)
        val projected = SimContactsDataSource.simContactToContact(listed.single())
        assertEquals(listOf("aisha@x.com"), projected.details.emails.map { it.address })
    }

    @Test
    fun editingAnEmailedSimContactReplacesItInsteadOfDuplicating() {
        val old = SimContactsDataSource.listSimContacts(ctx).single()
        assertTrue(
            SimContactsDataSource.updateSimContact(
                ctx, old, "Aisha Khan", "+222", "khan@x.com", old.subscriptionId
            )
        )
        assertEquals(
            listOf(Row("Aisha Khan", "+222", listOf("khan@x.com"), listOf("+999"))),
            AospIccProvider.rows.toList(),
        )
        assertEquals(1, SimContactsDataSource.listSimContacts(ctx).size)
    }

    @Test
    fun addingAnEmailToASimContactStoresItWithoutATrailingComma() {
        AospIccProvider.reset(listOf(Row("Bob", "+111")))
        val old = SimContactsDataSource.listSimContacts(ctx).single()
        assertTrue(SimContactsDataSource.updateSimContact(ctx, old, "Bob", "+111", "bob@x.com"))
        assertEquals(listOf("bob@x.com"), AospIccProvider.rows.single().emails)
        // and the re-read that feeds the edit screen is clean, not "bob@x.com,"
        assertEquals("bob@x.com", SimContactsDataSource.listSimContacts(ctx).single().emails)
    }

    @Test
    fun deletingAnEmailedSimContactRemovesIt() {
        val old = SimContactsDataSource.listSimContacts(ctx).single()
        assertTrue(SimContactsDataSource.deleteSimContact(ctx, old))
        assertTrue(AospIccProvider.rows.isEmpty())
        assertTrue(SimContactsDataSource.listSimContacts(ctx).isEmpty())
    }

    @Test
    fun insertingAnEmailedSimContactReadsBackCleanAndDeletable() {
        assertTrue(SimContactsDataSource.insertSimContact(ctx, "New Person", "+333", "new@x.com,"))
        val listed = SimContactsDataSource.listSimContacts(ctx)
        assertEquals("new@x.com", listed.first { it.name == "New Person" }.emails)
        assertTrue(SimContactsDataSource.deleteSimContact(ctx, listed.first { it.name == "New Person" }))
    }

    @Test
    fun anEditWhoseOldRowCannotBeRemovedDoesNotInsertADuplicate() {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        val projected = SimContactsDataSource.simContactsAsContacts(ctx).single()
        val renamed = projected.copy(
            details = projected.details.copy(
                names = listOf(Name(0, "", "Aisha", "", "Khan", ""))
            )
        )
        // Provider now refuses update and delete (wedged radio/OEM variant):
        // the save must fail, never fall through to insert-and-leave-the-old.
        AospIccProvider.refuseWrites = true
        assertFalse(runBlocking { vm.persistSimContact(renamed) })
        assertEquals(0, AospIccProvider.insertCount)
        assertEquals(1, AospIccProvider.rows.size)
    }
}
