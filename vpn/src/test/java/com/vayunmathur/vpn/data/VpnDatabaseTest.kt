package com.vayunmathur.vpn.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VpnDatabaseTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun db(): VpnDatabase =
        androidx.room3.Room.inMemoryDatabaseBuilder(ctx, VpnDatabase::class.java)
            .allowMainThreadQueries().build()

    @Test fun configDaoCrudAndOrdering() { runBlocking {
        val db = db()
        try {
            val dao = db.vpnConfigDao()
            assertTrue(dao.getAll().isEmpty())
            val id1 = dao.upsert(VpnConfigEntity(name = "one", peerEndpoint = "a:1", lastUsed = 100))
            val id2 = dao.upsert(VpnConfigEntity(name = "two", peerEndpoint = "b:2", lastUsed = 200))
            assertTrue(id1 > 0 && id2 > id1)
            assertEquals("one", dao.getById(id1)?.name)
            assertNull(dao.getById(9999))
            // flow orders by lastUsed DESC
            assertEquals(listOf("two", "one"), dao.flowAll().first().map { it.name })
            dao.touch(id1, 300)
            assertEquals(listOf("one", "two"), dao.flowAll().first().map { it.name })
            // upsert with existing id updates
            dao.upsert(VpnConfigEntity(id = id1, name = "one-renamed", lastUsed = 300))
            assertEquals("one-renamed", dao.getById(id1)?.name)
            assertEquals(1, dao.delete(VpnConfigEntity(id = id2)))
            assertEquals(1, dao.getAll().size)
        } finally { db.close() }
    } }

    @Test fun connectionLogDaoAggregatesAndFindIdentical() { runBlocking {
        val db = db()
        try {
            val dao = db.connectionLogDao()
            dao.upsertAll(listOf(
                ConnectionLogEntity(timestampStart = 1, timestampLast = 2, uid = 100, packageName = "com.a", appLabel = "A", protocol = "TCP", localIp = "10.0.0.2", localPort = 1111, remoteIp = "1.2.3.4", remotePort = 443, domain = "a.com", txBytes = 100, rxBytes = 50, requestCount = 3),
                ConnectionLogEntity(timestampStart = 1, timestampLast = 3, uid = 101, packageName = "com.a", appLabel = "A", protocol = "UDP", localIp = "10.0.0.2", localPort = 2222, remoteIp = "5.6.7.8", remotePort = 53, domain = "b.com", txBytes = 10, rxBytes = 20, requestCount = 2),
                ConnectionLogEntity(timestampStart = 1, timestampLast = 4, uid = -1, packageName = null, appLabel = "Unknown", protocol = "TCP", localIp = "10.0.0.2", localPort = 3333, remoteIp = "9.9.9.9", remotePort = 443, domain = null, txBytes = 5, rxBytes = 5, requestCount = 1),
            ))
            val topApps = dao.flowTopApps().first()
            assertEquals("com.a", topApps.first().packageName); assertEquals(180L, topApps.first().totalBytes)
            assertEquals(listOf("a.com" to 3L, "b.com" to 2L), dao.flowDomainsByCount().first().map { it.domain to it.totalCount })
            assertEquals(150L, dao.flowDomainsByBytes().first().first { it.domain == "a.com" }.totalBytes)
            assertEquals(listOf(-1).intersect(dao.unnamedUids().toSet()).isEmpty(), true) // uid -1 excluded (uid > 0)
            val found = dao.findIdentical("1.2.3.4", 443, "TCP", 1111)
            assertNotNull(found); assertEquals("a.com", found.domain)
            assertNull(dao.findIdentical("1.2.3.4", 443, "TCP", 9999))
            dao.deleteAll()
            assertTrue(dao.flowTopApps().first().isEmpty())
        } finally { db.close() }
    } }

    @Test fun connectionLogNameUid() { runBlocking {
        val db = db()
        try {
            val dao = db.connectionLogDao()
            dao.upsertAll(listOf(
                ConnectionLogEntity(uid = 555, packageName = null, appLabel = "Unknown", protocol = "UDP", remoteIp = "1.1.1.1", remotePort = 53, localPort = 1),
            ))
            assertEquals(listOf(555), dao.unnamedUids())
            dao.nameUid(555, "com.named", "Named")
            assertTrue(dao.unnamedUids().isEmpty())
            assertEquals("Named", dao.flowTopApps().first().single().appLabel)
        } finally { db.close() }
    } }

    @Test fun repositorySingletonAndMigrations() {
        // Note: actually opening the repository database needs AndroidKeyStore-backed
        // SQLCipher, which Robolectric cannot provide (KeyStoreException), so the DAO
        // getters are exercised through the in-memory database above instead.
        val repo = VpnRepository.get(ctx)
        assertTrue(VpnRepository.get(ctx) === repo)
        assertEquals(listOf(MIGRATION_1_2), VpnDatabase.migrations)
        assertEquals(1, VpnDatabase.migrations.first().startVersion)
        assertEquals(2, VpnDatabase.migrations.first().endVersion)
    }
}
