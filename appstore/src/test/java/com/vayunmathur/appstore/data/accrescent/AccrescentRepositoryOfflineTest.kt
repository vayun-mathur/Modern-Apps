package com.vayunmathur.appstore.data.accrescent

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Accrescent with no reachable API: browse/search/details fail soft
 * (empty/null, never an exception to the UI), the repodata refresh
 * fails closed leaving the trust cache untouched, and the install-time
 * lookups that must fail closed do throw.
 */
@RunWith(RobolectricTestRunner::class)
class AccrescentRepositoryOfflineTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AccrescentRepository

    @BeforeTest
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        repo = AccrescentRepository(ctx, db)
    }

    @AfterTest
    fun tearDown() {
        repo.shutdown()
        db.close()
    }

    @Test
    fun `listApps fails soft to an empty page`() = runBlocking {
        val page = repo.listApps()
        assertTrue(page.apps.isEmpty())
        assertEquals("", page.nextPageToken)
    }

    @Test
    fun `details for an unknown app is null offline`() = runBlocking {
        assertNull(repo.details("app.does.not.exist"))
    }

    @Test
    fun `search and allListings are empty when the catalogue cannot load`() = runBlocking {
        assertTrue(repo.search("anything").isEmpty())
        assertTrue(repo.allListings().isEmpty())
    }

    @Test
    fun `refreshRepoData fails closed offline`() = runBlocking {
        val result = repo.refreshRepoData()
        assertTrue(result.isFailure, "repodata refresh must fail with no network")
        assertTrue(repo.appIds().isEmpty())
    }

    @Test
    fun `downloadInfo throws when the api cannot answer`() = runBlocking {
        assertFailsWith<Exception> { repo.downloadInfo("app.does.not.exist") }
        Unit
    }
}
