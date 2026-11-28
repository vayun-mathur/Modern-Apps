package com.vayunmathur.contacts.util
import com.vayunmathur.contacts.platform.ContactViewModel

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.DefaultAccountKind
import com.vayunmathur.contacts.data.DefaultAccountSource
import com.vayunmathur.contacts.data.DefaultAccountSnapshot
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.ProviderAccount
import com.vayunmathur.contacts.platform.ContactAccount
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SimProviderPass2Test
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

/**
 * Issue 926 (v2.6.11, LineageOS/Galaxy S10): the contacts app spun
 * forever on both tabs when the physical SIM appeared as a source.
 * The SIM ADN query and the device query were merged sequentially
 * before anything was published, so a throwing or never-answering
 * ICC provider blocked the device contacts and the loaded flag.
 *
 * These tests put a throwing, then a hanging, fake ICC provider
 * behind the real SIM source seam and prove the device contacts
 * still render and loading completes; a healthy SIM still merges.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SimSourceIsolation926Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private class FakeSource : DefaultAccountSource {
        override fun defaultAccountForNewContacts(): DefaultAccountSnapshot? = null
        override fun customLocalAccount(): ProviderAccount? = null
        override fun providerSimAccounts(): List<ProviderAccount> = emptyList()
    }

    /** Cloud default whose SIM-accounts query never answers (stuck Binder call). */
    private class HangingSimAccountsSource : DefaultAccountSource {
        override fun defaultAccountForNewContacts(): DefaultAccountSnapshot =
            DefaultAccountSnapshot(DefaultAccountKind.CLOUD, ProviderAccount("cloud@x", "com.cloud"))
        override fun customLocalAccount(): ProviderAccount? = null
        override fun providerSimAccounts(): List<ProviderAccount> {
            Thread.sleep(5_000)
            return emptyList()
        }
    }

    /** Cloud default whose SIM-accounts query throws an undocumented RuntimeException. */
    private class ThrowingSimAccountsSource : DefaultAccountSource {
        override fun defaultAccountForNewContacts(): DefaultAccountSnapshot =
            DefaultAccountSnapshot(DefaultAccountKind.CLOUD, ProviderAccount("cloud@x", "com.cloud"))
        override fun customLocalAccount(): ProviderAccount? = null
        override fun providerSimAccounts(): List<ProviderAccount> =
            throw IllegalStateException("sim accounts wedged")
    }

    /**
     * Contacts provider that delegates to the healthy fake except for the
     * two-column RawContacts accounts query, which never answers.
     */
    class HangingAccountsProvider : ContentProvider() {
        private val delegate = FakeProviderPass2Test.FakeContactsProvider()
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            if (uri.toString().contains("raw_contacts") && projection?.size == 2) {
                Thread.sleep(5_000)
            }
            return delegate.query(uri, projection, selection, selectionArgs, sortOrder)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri =
            delegate.insert(uri, values)
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
        ): Int = delegate.update(uri, values, selection, selectionArgs)
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            delegate.delete(uri, selection, selectionArgs)
        override fun getType(uri: Uri): String? = null
    }

    /** ICC provider whose every query throws, as on a wedged SIM stack. */
    class ThrowingIccProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor = throw IllegalStateException("icc wedged")
        override fun insert(uri: Uri, values: ContentValues?): Uri =
            throw IllegalStateException("icc wedged")
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
        ): Int = throw IllegalStateException("icc wedged")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            throw IllegalStateException("icc wedged")
        override fun getType(uri: Uri): String? = null
    }

    /** ICC provider whose query never answers within the test window. */
    class HangingIccProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            // Non-cancellable block, like a Binder call waiting on the
            // radio to load EF_ADN; coroutine cancellation cannot stop it.
            Thread.sleep(3_000)
            return MatrixCursor(arrayOf("name", "number", "emails", "_id"))
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri =
            throw IllegalStateException("icc wedged")
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
        ): Int = throw IllegalStateException("icc wedged")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            throw IllegalStateException("icc wedged")
        override fun getType(uri: Uri): String? = null
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY,
            FakeProviderPass2Test.FakeContactsProvider(),
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(timeoutMillis: Long = 500): ContactViewModel =
        ContactViewModel(app, FakeSource(), timeoutMillis)

    @Test
    fun throwingSimSourceStillRendersDeviceContactsAndCompletesLoading() {
        ShadowContentResolver.registerProviderInternal("icc", ThrowingIccProvider())
        val vm = vm()
        runBlocking { withTimeout(15_000) { vm.syncFromSystem() } }
        assertTrue(vm.hasLoadedContacts.value, "loading must terminate when the SIM source throws")
        // The fake device provider's two contacts survive a dead SIM source.
        assertEquals(setOf(7L, 8L), vm.allContactsState.value.map { it.id }.toSet())
        assertTrue(vm.allContactsState.value.none { it.accountType == SIM_ACCOUNT_TYPE })
    }

    @Test
    fun hangingSimSourceStillRendersDeviceContactsAndCompletesLoading() {
        ShadowContentResolver.registerProviderInternal("icc", HangingIccProvider())
        val vm = vm(timeoutMillis = 200)
        val started = System.nanoTime()
        runBlocking { withTimeout(15_000) { vm.syncFromSystem() } }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(vm.hasLoadedContacts.value, "loading must terminate when the SIM source hangs")
        assertEquals(setOf(7L, 8L), vm.allContactsState.value.map { it.id }.toSet())
        assertTrue(elapsedMillis < 10_000, "sync took ${elapsedMillis}ms despite the source timeout")
    }

    @Test
    fun hangingDefaultAccountQueryStillCompletesLoadingAndKeepsAccounts() {
        ShadowContentResolver.registerProviderInternal("icc", ThrowingIccProvider())
        val vm = ContactViewModel(app, HangingSimAccountsSource(), 200)
        val started = System.nanoTime()
        runBlocking { withTimeout(15_000) { vm.syncFromSystem() } }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(vm.hasLoadedContacts.value, "loading must terminate when the default-account query hangs")
        assertEquals(setOf(7L, 8L), vm.allContactsState.value.map { it.id }.toSet())
        assertTrue(
            ContactAccount("a@gmail.com", "com.google") in vm.accountsState.value,
            "provider accounts must survive a wedged default-account query: ${vm.accountsState.value}",
        )
        assertTrue(elapsedMillis < 4_000, "sync took ${elapsedMillis}ms despite the source timeout")
    }

    @Test
    fun throwingDefaultAccountQueryDoesNotAbortAccountLoad() {
        ShadowContentResolver.registerProviderInternal("icc", ThrowingIccProvider())
        val vm = ContactViewModel(app, ThrowingSimAccountsSource(), 500)
        runBlocking { withTimeout(15_000) { vm.syncFromSystem() } }
        assertTrue(vm.hasLoadedContacts.value)
        assertEquals(setOf(7L, 8L), vm.allContactsState.value.map { it.id }.toSet())
        assertTrue(
            ContactAccount("a@gmail.com", "com.google") in vm.accountsState.value,
            "accounts must still load when the default-account query throws: ${vm.accountsState.value}",
        )
    }

    @Test
    fun hangingProviderAccountsQueryStillCompletesLoading() {
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, HangingAccountsProvider(),
        )
        ShadowContentResolver.registerProviderInternal("icc", ThrowingIccProvider())
        val vm = vm(timeoutMillis = 200)
        val started = System.nanoTime()
        runBlocking { withTimeout(15_000) { vm.syncFromSystem() } }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(vm.hasLoadedContacts.value, "loading must terminate when the accounts query hangs")
        assertEquals(setOf(7L, 8L), vm.allContactsState.value.map { it.id }.toSet())
        assertTrue(elapsedMillis < 4_000, "sync took ${elapsedMillis}ms despite the source timeout")
    }

    @Test
    fun healthySimSourceStillMergesIntoTheList() {
        ShadowContentResolver.registerProviderInternal("icc", SimProviderPass2Test.FakeIccProvider())
        val vm = vm()
        runBlocking { withTimeout(15_000) { vm.syncFromSystem() } }
        assertTrue(vm.hasLoadedContacts.value)
        val ids = vm.allContactsState.value.map { it.id }.toSet()
        assertTrue(7L in ids && 8L in ids, "device contacts missing: $ids")
        assertTrue(
            vm.allContactsState.value.any { it.accountType == SIM_ACCOUNT_TYPE },
            "healthy SIM contacts must still load",
        )
    }
}
