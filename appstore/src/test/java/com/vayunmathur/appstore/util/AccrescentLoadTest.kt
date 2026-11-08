package com.vayunmathur.appstore.util

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The phone ViewModel's Accrescent helpers offline: an update lookup
 * for a package answers null (the API cannot be reached, which the
 * caller reads as "no update"), and loadAccrescent is a no-op when
 * the source is disabled and fails soft when it is enabled.
 */
@RunWith(RobolectricTestRunner::class)
class AccrescentLoadTest {

    private lateinit var vm: AppStoreViewModel

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        // Not closed: the ViewModel scope keeps collecting DB flows; the
        // in-memory DB dies with the Robolectric sandbox.
        vm = AppStoreViewModel(app, db)
    }

    @Test
    fun `accrescentUpdate is null when the api cannot answer`() = runBlocking {
        assertNull(vm.accrescentUpdate("app.does.not.exist", currentVersionCode = 1L))
    }

    @Test
    fun `loadAccrescent with the source disabled touches nothing`() = runBlocking {
        vm.loadAccrescent(enabled = setOf(AppSource.MODERN_APPS))
        assertTrue(vm.accrescentAppsFlow.value.isEmpty())
        assertTrue(vm.accrescentPackagesFlow.value.isEmpty())
    }

    @Test
    fun `loadAccrescent enabled fails soft offline`() = runBlocking {
        vm.loadAccrescent(enabled = setOf(AppSource.ACCRESCENT))
        // No allowlist, no listings: both flows keep their previous (empty) state.
        assertTrue(vm.accrescentAppsFlow.value.isEmpty())
        assertEquals(emptySet(), vm.accrescentPackagesFlow.value)
    }
}
