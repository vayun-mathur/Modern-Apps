package com.vayunmathur.passwords.data

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasswordDatabaseTest {

    private lateinit var db: PasswordDatabase

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PasswordDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun passwordCrudAndQueries() = runBlocking {
        val dao = db.passwordDao()
        val id = dao.upsertRaw(Password(name = "GitHub", username = "octo", syncId = "s1", updatedAt = 100))
        assertTrue(id > 0)
        assertEquals("GitHub", dao.getById(id)?.name)
        assertEquals("GitHub", dao.getBySyncId("s1")?.name)
        assertEquals(1, dao.getAll().size)
        assertEquals(1, dao.getUpdatedSince(50).size)
        assertEquals(0, dao.getUpdatedSince(100).size)
        assertEquals("GitHub", dao.getByIdFlow(id).first()?.name)
        assertEquals(1, dao.getAllFlow().first().size)

        // upsert() bumps updatedAt instead of writing it verbatim.
        dao.upsert(Password(id = id, name = "Renamed", syncId = "s1", updatedAt = 1))
        assertEquals("Renamed", dao.getById(id)?.name)
        assertTrue((dao.getById(id)?.updatedAt ?: 0) > 1)

        assertEquals(1, dao.deleteBySyncId("s1"))
        assertNull(dao.getById(id))
    }

    @Test fun websitesPersistThroughConverter() = runBlocking {
        val dao = db.passwordDao()
        val id = dao.upsertRaw(Password(name = "A", websites = listOf("a.com", "b.com"), syncId = "w1"))
        assertEquals(listOf("a.com", "b.com"), dao.getById(id)?.websites)
    }

    @Test fun passkeyCrudAndQueries() = runBlocking {
        val dao = db.passkeyDao()
        val pk = Passkey(rpId = "example.com", credentialId = "cred", syncId = "pk1", updatedAt = 10, privateKeyBytes = byteArrayOf(1, 2))
        val id = dao.upsertRaw(pk)
        assertEquals("example.com", dao.getByCredentialId("cred")?.rpId)
        assertEquals(1, dao.getByRpId("example.com").size)
        assertEquals(1, dao.getUpdatedSince(5).size)
        assertEquals(byteArrayOf(1, 2).toList(), dao.getBySyncId("pk1")?.privateKeyBytes?.toList())
        dao.upsert(pk.copy(id = id, signCount = 3, updatedAt = 1))
        assertEquals(3, dao.getBySyncId("pk1")?.signCount)
        assertEquals(1, dao.deleteBySyncId("pk1"))
        assertTrue(dao.getAll().isEmpty())
    }

    @Test fun snapshotCrud() = runBlocking {
        val dao = db.syncSnapshotDao()
        dao.upsert(SyncSnapshot("s1", SyncSnapshot.KIND_PASSWORD, "h1", 1, 2, 3))
        dao.upsert(SyncSnapshot("s2", SyncSnapshot.KIND_PASSKEY, "h2", 1, 2, 3))
        assertEquals(2, dao.getAll().size)
        dao.deleteByKind(SyncSnapshot.KIND_PASSWORD)
        assertEquals(listOf("s2"), dao.getAll().map { it.syncId })
        dao.deleteBySyncId("s2")
        assertTrue(dao.getAll().isEmpty())
        dao.upsert(SyncSnapshot("s3", "password", "h", 0, 0, 0))
        dao.deleteAll()
        assertTrue(dao.getAll().isEmpty())
    }

    @Test fun deleteAllClearsPasswords() = runBlocking {
        val dao = db.passwordDao()
        dao.upsertRaw(Password(name = "A", syncId = "x"))
        dao.deleteAll()
        assertTrue(dao.getAll().isEmpty())
    }

    @Test fun migrationsAreRegistered() {
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5),
            PasswordDatabase.migrations.map { it.startVersion to it.endVersion })
    }
}
