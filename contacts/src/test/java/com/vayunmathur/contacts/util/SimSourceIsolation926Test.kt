package com.vayunmathur.contacts.util

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.DefaultAccountSource
import com.vayunmathur.contacts.data.DefaultAccountSnapshot
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.ProviderAccount
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
