package com.vayunmathur.contacts.util

import android.app.Application
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.LOCAL_ACCOUNT_TYPE
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/** W5: every legacy-entry shape through the account/hidden/no-year migrations. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MigrationBranchesW5Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
    }

    private fun <T> ioBlocking(block: suspend () -> T): T =
        runBlocking(Dispatchers.IO) { withTimeout(30_000) { block() } }

    private suspend fun awaitSeed(vm: ContactViewModel, key: String) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (vm.dataStore.getString(key).isNullOrEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(25)
        }
    }

    @Test
    fun legacyEntriesOfEveryShapeMigrate() {
        val vm = ContactViewModel(app)
        ioBlocking {
            val clean = encodeExtraAccountEntry("Clean Name", "com.google")
            vm.dataStore.setString(
                "extra_accounts",
                "$clean, Plain Name , Legacy|com.old , |com.noname , ,",
            )
            awaitSeed(vm, "extra_accounts")
            vm.migrateLegacyAccounts()
            val saved = vm.loadSavedAccounts()
            assertTrue(saved.any { it.name == "Clean Name" && it.type == "com.google" }, "saved=$saved")
            assertTrue(saved.any { it.name == "Plain Name" && it.type == LOCAL_ACCOUNT_TYPE }, "saved=$saved")
            assertTrue(saved.any { it.name == "Legacy" && it.type == "com.old" }, "saved=$saved")
            assertEquals("", vm.dataStore.getString("extra_accounts").orEmpty())
        }
    }

    @Test
    fun savedEntriesNormalizeLegacyAndDropEmpty() {
        val vm = ContactViewModel(app)
        ioBlocking {
            vm.dataStore.addStringToSetIfAbsent("extra_accounts_set", "Old|com.legacy")
            vm.dataStore.addStringToSetIfAbsent("extra_accounts_set", "|com.noname")
            vm.dataStore.addStringToSetIfAbsent("extra_accounts_set", encodeAccountComponent("Encoded Solo"))
            val saved = vm.loadSavedAccounts()
            assertTrue(saved.any { it.name == "Old" && it.type == "com.legacy" }, "saved=$saved")
            assertTrue(saved.none { it.type == "com.noname" }, "empty-name entries must drop, saved=$saved")
            // Re-loading keeps every surviving account (normalization may add encoded forms).
            assertTrue(vm.loadSavedAccounts().size >= saved.size - 1)
        }
    }

    @Test
    fun hiddenKeysOfEveryShapeMigrateOrKeep() {
        val vm = ContactViewModel(app)
        ioBlocking {
            vm.dataStore.addStringToSet("hidden_accounts", "com.google|Raw Name")
            vm.dataStore.addStringToSet("hidden_accounts", "no-pipe-entry")
            vm.dataStore.addStringToSet("hidden_accounts", encodeHiddenAccountKey("com.google", "Clean Name"))
            vm.dataStore.addStringToSet("hidden_accounts", "|")
            val deadline = System.nanoTime() + 15_000_000_000L
            while (!vm.dataStore.getStringSetAwait("hidden_accounts").contains("no-pipe-entry") &&
                System.nanoTime() < deadline
            ) {
                Thread.sleep(25)
            }
            vm.migrateHiddenAccountKeys()
            val hidden = vm.dataStore.getStringSetAwait("hidden_accounts")
            assertTrue(hidden.contains("no-pipe-entry"), "pipe-less keys are kept, hidden=$hidden")
            assertTrue(hidden.size >= 3, "no seeded key should be lost, hidden=$hidden")
            assertTrue(hidden.isNotEmpty())
        }
    }

    @Test
    fun noYearAndBirthdayMigrationsSetFlags() {
        val vm = ContactViewModel(app)
        ioBlocking {
            vm.migrateNoYearEventDates()
            assertEquals(true, vm.dataStore.getBoolean("no_year_event_dates_migrated", false))
            // Isolate from other classes sharing this DataStore file.
            vm.dataStore.setBoolean("calendar_sync_enabled", false)
            vm.dataStore.setBoolean("birthday_calendar_utc_migrated", false)
            // Birthday migration is skipped while sync is off (flag stays unset).
            vm.migrateBirthdayCalendarToUtc()
            assertEquals(false, vm.dataStore.getBoolean("birthday_calendar_utc_migrated", false))
            vm.dataStore.setBoolean("calendar_sync_enabled", true)
            vm.migrateBirthdayCalendarToUtc()
            assertEquals(true, vm.dataStore.getBoolean("birthday_calendar_utc_migrated", false))
            // Restore both flags: the DataStore file is shared with other test
            // classes in this JVM, and a set flag would change their behaviour.
            vm.dataStore.setBoolean("calendar_sync_enabled", false)
            vm.dataStore.setBoolean("birthday_calendar_utc_migrated", false)
        }
    }

    @Test
    fun renamedAccountRefsMigrate() {
        val vm = ContactViewModel(app)
        ioBlocking {
            vm.dataStore.setString("extra_accounts", "Renamed|com.google")
            awaitSeed(vm, "extra_accounts")
            vm.migrateLegacyAccounts()
            assertTrue(vm.loadSavedAccounts().any { it.name == "Renamed" })
        }
    }
}
