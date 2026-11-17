package com.vayunmathur.securemail.data

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.databases
import com.vayunmathur.securemail.FakeAndroidKeyStore
import com.vayunmathur.securemail.platform.crypto.KeyManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The context-level [Identities.ensureMigrated] / [Identities.active],
 * which earlier suites only exercised through the DAO-level core: the
 * production overloads read MailConfig, the KeyManager secret and the
 * process-wide MailRepository. Seeding the shared database cache with
 * an in-memory database and resetting the repository singleton (both
 * restored afterwards) plus the in-memory keystore fake makes the full
 * migration run on the host.
 */
@RunWith(RobolectricTestRunner::class)
class IdentitiesMigrationContextTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo: MailRepository get() = MailRepository.get(app)

    @BeforeTest fun setUp() {
        FakeAndroidKeyStore.install()
        resetRepositorySingleton()
        synchronized(databases) {
            runCatching { databases[MailDatabase::class]?.close() }
            databases[MailDatabase::class] =
                Room.inMemoryDatabaseBuilder(app, MailDatabase::class.java)
                    .allowMainThreadQueries().build()
        }
        runBlocking {
            repo.identityDao().deleteAll()
            MailConfig(app).setIdentity("", "", "")
        }
        KeyManager(app).clear()
    }

    @AfterTest fun tearDown() {
        resetRepositorySingleton()
        synchronized(databases) {
            runCatching { databases[MailDatabase::class]?.close() }
            databases.remove(MailDatabase::class)
        }
    }

    private fun resetRepositorySingleton() {
        runCatching {
            val field = MailRepository::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    @Test fun `migration is a no-op until configured with a stored secret`() = runBlocking {
        // Nothing configured at all.
        Identities.ensureMigrated(app)
        assertEquals(0, repo.identityDao().count())

        // Configured identity, but the keystore holds no secret yet.
        MailConfig(app).setIdentity("me@maemail.org", "pub-armor", "AABBCC")
        Identities.ensureMigrated(app)
        assertEquals(0, repo.identityDao().count())
        assertTrue(Identities.active(app).isEmpty())
    }

    @Test fun `migration backfills the primary identity once and stays idempotent`() = runBlocking {
        MailConfig(app).setIdentity("me@maemail.org", "pub-armor", "AABBCC")
        KeyManager(app).storeSecretArmor("secret-armor")

        Identities.ensureMigrated(app)
        assertEquals(1, repo.identityDao().count())
        val active = Identities.active(app)
        assertEquals(1, active.size)
        val primary = active.single()
        assertEquals("me@maemail.org", primary.address)
        assertEquals("pub-armor", primary.publicArmor)
        assertEquals("AABBCC", primary.fingerprintHex)
        assertEquals("secret-armor", primary.secretArmor)
        assertTrue(primary.isPrimary)

        // A second migration with changed config must not duplicate or
        // overwrite the backfilled row.
        MailConfig(app).setIdentity("other@maemail.org", "x", "y")
        Identities.ensureMigrated(app)
        assertEquals(1, repo.identityDao().count())
        assertEquals("me@maemail.org", Identities.active(app).single().address)
        assertEquals("other@maemail.org", MailConfig(app).settings.first().address)
    }
}
