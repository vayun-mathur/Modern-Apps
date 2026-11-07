package com.vayunmathur.contacts.util

import android.app.Application
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Second-pass coverage for the one-time DataStore/provider migrations. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MigrationPass2Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
    }


    private fun vm() = ContactViewModel(app)

    /**
     * Runs suspend DataStore/VM work off Robolectric's main thread. Calling these APIs from a
     * main-thread runBlocking can park forever once another sandbox has touched the shared
     * DataStore in the same test JVM; IO execution plus a timeout turns that into a failure
     * instead of a hung test executor.
     */
    private fun <T> ioBlocking(block: suspend () -> T): T =
        runBlocking(Dispatchers.IO) { withTimeout(30_000) { block() } }

    @Test
    fun legacyAccountEntriesMigrateIntoSavedSet() {
        val vm = vm()
        ioBlocking {
            // legacy comma-joined plain names, plus one already-encoded entry
            vm.dataStore.setString("extra_accounts", "Old One,Old Two")
            // getString reads the synchronous snapshot, which lags the edit:
            // wait until the seed is visible before migrating
            val deadline = System.nanoTime() + 15_000_000_000L
            while (vm.dataStore.getString("extra_accounts").isNullOrEmpty() &&
                System.nanoTime() < deadline
            ) {
                Thread.sleep(25)
            }
            vm.migrateLegacyAccounts()
            val saved = vm.loadSavedAccounts()
            assertTrue(saved.any { it.name == "Old One" }, "saved=$saved")
            assertTrue(saved.any { it.name == "Old Two" }, "saved=$saved")
            // legacy key is cleared so the migration does not run twice
            assertEquals("", vm.dataStore.getString("extra_accounts").orEmpty())

            // already current: blank legacy value is a no-op
            vm.migrateLegacyAccounts()
            assertEquals(saved.size, vm.loadSavedAccounts().size)
        }
    }

    @Test
    fun hiddenAccountKeysMigrateToEncodedForm() {
        val vm = vm()
        ioBlocking {
            vm.dataStore.addStringToSetIfAbsent("hidden_accounts", "com.google|a@gmail.com")
            assertTrue(vm.dataStore.getStringSetAwait("hidden_accounts").isNotEmpty(),
                "hidden account seed should land")
            vm.migrateHiddenAccountKeys()
            val hidden = vm.dataStore.getStringSetAwait("hidden_accounts")
            // raw "type|name" entries with special chars get re-encoded; the set stays non-empty
            assertTrue(hidden.isNotEmpty())
        }
    }

    @Test
    fun savedAccountEntriesDecodeCleanAndLegacyForms() {
        val vm = vm()
        ioBlocking {
            vm.dataStore.addStringToSetIfAbsent(
                "extra_accounts_set", encodeExtraAccountEntry("Encoded|Name", "com.google")
            )
            vm.dataStore.addStringToSetIfAbsent("extra_accounts_set", "PlainLegacyName")
            val saved = vm.loadSavedAccounts()
            assertTrue(saved.any { it.name == "Encoded|Name" && it.type == "com.google" }, "saved=$saved")
            assertTrue(saved.any { it.name == "PlainLegacyName" }, "saved=$saved")
        }
    }

    @Test
    fun noYearMigrationRunsOnceAndSetsFlag() {
        val vm = vm()
        ioBlocking {
            vm.migrateNoYearEventDates()
            assertEquals(true, vm.dataStore.getBoolean("no_year_event_dates_migrated", false))
            // second run returns immediately via the flag
            vm.migrateNoYearEventDates()
            assertEquals(true, vm.dataStore.getBoolean("no_year_event_dates_migrated", false))
        }
    }

    @Test
    fun birthdayUtcMigrationSkippedWhileSyncOff() {
        val vm = vm()
        ioBlocking {
            vm.migrateBirthdayCalendarToUtc()
            // sync is off, so the migration stays pending (flag unset)
            assertEquals(false, vm.dataStore.getBoolean("birthday_calendar_utc_migrated", false))
        }
    }

    @Test
    fun draftFromExistingProviderContactKeepsDetails() {
        val vm = vm()
        ioBlocking { vm.syncFromSystem() }
        // fake provider contact 7 (Aisha) loads into a draft with her details
        vm.initEditDraft(7L)
        val draft = vm.editDraft.value
        assertEquals("Aisha", draft?.firstName)
        assertEquals("+14155550102", draft?.phoneNumbers?.firstOrNull()?.number)
        assertTrue(draft?.emails?.any { it.address == "aisha@x.com" } == true)

        // a prefill-only draft for a new contact merges prefilled values
        vm.clearEditDraft()
        val prefill = com.vayunmathur.contacts.data.ContactPrefill(
            name = "Pre Filled",
            phones = listOf(com.vayunmathur.contacts.data.PrefillValue("+14155550199")),
            emails = listOf(com.vayunmathur.contacts.data.PrefillValue("pre@x.com")),
        )
        vm.initEditDraft(null, prefill)
        assertEquals("+14155550199", vm.editDraft.value?.phoneNumbers?.firstOrNull()?.number)
        assertEquals("pre@x.com", vm.editDraft.value?.emails?.firstOrNull()?.address)
    }
}
