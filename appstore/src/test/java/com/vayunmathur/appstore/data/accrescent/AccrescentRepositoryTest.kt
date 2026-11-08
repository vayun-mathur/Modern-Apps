package com.vayunmathur.appstore.data.accrescent

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AccrescentTrustEntity
import com.vayunmathur.appstore.data.AppDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Accrescent trust anchor: the signed allowlist cached in Room is what
 * signer/min-version lookups answer from, and replacing it (as a verified
 * repodata refresh does) drops apps the new document no longer vouches for.
 * Network paths fail soft offline and are not exercised here.
 */
@RunWith(RobolectricTestRunner::class)
class AccrescentRepositoryTest {

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
    fun `trust lookups answer from the cached allowlist`() = runTest {
        db.accrescentTrustDao().upsertAll(
            listOf(AccrescentTrustEntity("app.accrescent.example", "AA".repeat(32), 42L)),
        )
        assertEquals("aa".repeat(32), repo.signerFor("app.accrescent.example")?.lowercase())
        assertEquals(42L, repo.minVersionFor("app.accrescent.example"))
        assertEquals(42L, repo.entryFor("app.accrescent.example")?.minVersionCode)
        assertEquals(setOf("app.accrescent.example"), repo.appIds())
    }

    @Test
    fun `unknown apps have no trust anchor`() = runTest {
        assertNull(repo.signerFor("app.unknown"))
        assertNull(repo.minVersionFor("app.unknown"))
        assertNull(repo.entryFor("app.unknown"))
        assertTrue(repo.appIds().isEmpty())
    }

    @Test
    fun `replacing the allowlist drops apps it no longer lists`() = runTest {
        val store = AccrescentTrustStore(db.accrescentTrustDao())
        store.replaceFrom(
            RepoData(
                timestamp = 10L,
                apps = mapOf(
                    "app.one" to RepoApp(minVersionCode = 1L, signingCertHash = "AB".repeat(32)),
                    "app.two" to RepoApp(minVersionCode = 2L, signingCertHash = "CD".repeat(32)),
                ),
            ),
        )
        assertEquals(setOf("app.one", "app.two"), repo.appIds())
        // Signing hashes are normalised to lowercase on the way in.
        assertEquals("ab".repeat(32), repo.signerFor("app.one"))

        store.replaceFrom(
            RepoData(
                timestamp = 11L,
                apps = mapOf("app.two" to RepoApp(minVersionCode = 3L, signingCertHash = "CD".repeat(32))),
            ),
        )
        assertEquals(setOf("app.two"), repo.appIds())
        assertNull(repo.signerFor("app.one"))
        assertEquals(3L, repo.minVersionFor("app.two"))
    }

    @Test
    fun `blank search returns nothing without touching the network`() = runTest {
        assertTrue(repo.search("   ").isEmpty())
        assertTrue(repo.search("").isEmpty())
    }
}
