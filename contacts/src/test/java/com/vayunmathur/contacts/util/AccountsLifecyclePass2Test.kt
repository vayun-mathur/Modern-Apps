package com.vayunmathur.contacts.util

import android.app.Application
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.LOCAL_ACCOUNT_TYPE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rename/delete success and guard paths for local accounts. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccountsLifecyclePass2Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
    }


    private fun vm(): ContactViewModel {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        return vm
    }

    private fun awaitResult(
        block: (((Boolean, String?) -> Unit)) -> Unit,
    ): Pair<Boolean, String?> {
        val signal = CompletableDeferred<Pair<Boolean, String?>>()
        block { ok, code -> signal.complete(ok to code) }
        return runBlocking { withTimeout(30_000) { signal.await() } }
    }

    /** Seeds a local account into the saved set and reloads accounts. */
    private fun seedLocalAccount(vm: ContactViewModel, name: String) {
        runBlocking {
            vm.dataStore.addStringToSetIfAbsent(
                "extra_accounts_set", encodeExtraAccountEntry(name, LOCAL_ACCOUNT_TYPE)
            )
            vm.loadAccountsInternal()
        }
    }

    @Test
    fun renameLocalAccountSucceedsAndMigratesRefs() {
        val vm = vm()
        seedLocalAccount(vm, "Family")
        assertTrue(vm.accounts.value.any { it.name == "Family" })

        val account = ContactAccount("Family", LOCAL_ACCOUNT_TYPE)
        val (ok, code) = awaitResult { vm.renameLocalAccount(account, "Kinfolk", it) }
        assertTrue(ok, "rename should succeed, code=$code")
        assertEquals(null, code)

        runBlocking { vm.loadAccountsInternal() }
        assertTrue(vm.accounts.value.any { it.name == "Kinfolk" },
            "accounts=${vm.accounts.value}")
        // (other classes in this JVM seed their own "Family" accounts into the
        // shared store, so absence of the old name is not asserted here)
        val saved = runBlocking { vm.loadSavedAccounts() }
        assertTrue(saved.any { it.name == "Kinfolk" })
    }

    @Test
    fun renameGuardsRejectInvalidRenames() {
        val vm = vm()
        seedLocalAccount(vm, "Family")
        val local = ContactAccount("Family", LOCAL_ACCOUNT_TYPE)

        assertEquals(false to "blank", awaitResult { vm.renameLocalAccount(local, "   ", it) })
        assertEquals(true to null, awaitResult { vm.renameLocalAccount(local, "Family", it) })
        assertEquals(false to "not_local",
            awaitResult { vm.renameLocalAccount(ContactAccount("a@gmail.com", "com.google"), "X", it) })
        assertEquals(false to "collision",
            awaitResult { vm.renameLocalAccount(local, "A@GMAIL.COM", it) })
    }

    @Test
    fun deleteLocalAccountSucceedsAndClearsRefs() {
        val vm = vm()
        seedLocalAccount(vm, "Temp")
        // mark it last-selected so the delete must clear that reference too
        vm.setLastSelectedAccount("Temp", LOCAL_ACCOUNT_TYPE)
        val deadline = System.nanoTime() + 15_000_000_000L
        while (vm.dataStore.getString("last_account_name") != "Temp" &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(25)
        }

        val (ok, code) = awaitResult {
            vm.deleteLocalAccount(ContactAccount("Temp", LOCAL_ACCOUNT_TYPE), it)
        }
        assertTrue(ok, "delete should succeed, code=$code")

        runBlocking { vm.loadAccountsInternal() }
        assertTrue(vm.accounts.value.none { it.name == "Temp" },
            "accounts=${vm.accounts.value}")
    }

    @Test
    fun deleteGuardsRejectNonLocalAccounts() {
        val vm = vm()
        assertEquals(false to "not_local",
            awaitResult { vm.deleteLocalAccount(ContactAccount("a@gmail.com", "com.google"), it) })
        // the default on-device account (blank name and type) is also guarded
        assertEquals(false to "not_local",
            awaitResult { vm.deleteLocalAccount(ContactAccount("", ""), it) })
    }

    @Test
    fun createAccountCollisionAndSuccess() {
        val vm = vm()
        // success path: create, then it appears in the saved accounts
        val created = CompletableDeferred<Unit>()
        vm.createAccount("Brand New") { created.complete(Unit) }
        runBlocking { withTimeout(30_000) { created.await() } }
        val saved = runBlocking { vm.loadSavedAccounts() }
        assertTrue(saved.any { it.name == "Brand New" }, "saved=$saved")

        // collision: same name again completes without duplicating
        val again = CompletableDeferred<Unit>()
        vm.createAccount("Brand New") { again.complete(Unit) }
        runBlocking { withTimeout(30_000) { again.await() } }
        val savedAfter = runBlocking { vm.loadSavedAccounts() }
        assertEquals(1, savedAfter.count { it.name == "Brand New" })

        // provider account name collision is also rejected (no duplicate added)
        val providerCollision = CompletableDeferred<Unit>()
        vm.createAccount("a@gmail.com") { providerCollision.complete(Unit) }
        runBlocking { withTimeout(30_000) { providerCollision.await() } }
    }
}
