package com.vayunmathur.vpn.platform

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.vpn.data.AppUsageSummary
import com.vayunmathur.vpn.data.ConnectionLogDao
import com.vayunmathur.vpn.data.ConnectionLogEntity
import com.vayunmathur.vpn.data.DomainBytesSummary
import com.vayunmathur.vpn.data.DomainCountSummary
import com.vayunmathur.vpn.data.VpnConfig
import com.vayunmathur.vpn.data.VpnConfigDao
import com.vayunmathur.vpn.data.VpnConfigEntity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VpnViewModelTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    class FakeConfigDao : VpnConfigDao {
        val rows = MutableStateFlow<List<VpnConfigEntity>>(emptyList())
        var nextId = 1L
        val touched = mutableListOf<Pair<Long, Long>>()
        val deleted = mutableListOf<VpnConfigEntity>()
        override fun flowAll(): Flow<List<VpnConfigEntity>> = rows
        override suspend fun getById(id: Long): VpnConfigEntity? = rows.value.firstOrNull { it.id == id }
        override suspend fun getAll(): List<VpnConfigEntity> = rows.value
        override suspend fun upsert(entity: VpnConfigEntity): Long {
            val id = if (entity.id == 0L) nextId++ else entity.id
            rows.value = rows.value.filter { it.id != id } + entity.copy(id = id)
            return id
        }
        override suspend fun delete(entity: VpnConfigEntity): Int {
            deleted.add(entity); rows.value = rows.value.filter { it.id != entity.id }; return 1
        }
        override suspend fun touch(id: Long, ts: Long) { touched.add(id to ts) }
    }

    class FakeLogDao : ConnectionLogDao {
        val topApps = MutableStateFlow<List<AppUsageSummary>>(emptyList())
        val byCount = MutableStateFlow<List<DomainCountSummary>>(emptyList())
        val byBytes = MutableStateFlow<List<DomainBytesSummary>>(emptyList())
        var unnamed = emptyList<Int>()
        val named = mutableListOf<Triple<Int, String, String>>()
        var deleteAllCalls = 0
        override fun flowTopApps(): Flow<List<AppUsageSummary>> = topApps
        override fun flowDomainsByCount(): Flow<List<DomainCountSummary>> = byCount
        override fun flowDomainsByBytes(): Flow<List<DomainBytesSummary>> = byBytes
        override suspend fun unnamedUids(): List<Int> = unnamed
        override suspend fun nameUid(uid: Int, packageName: String, appLabel: String) { named.add(Triple(uid, packageName, appLabel)) }
        override suspend fun upsertAll(entities: List<ConnectionLogEntity>): List<Long> = entities.map { it.id }
        override suspend fun deleteAll() { deleteAllCalls++ }
        override suspend fun findIdentical(remoteIp: String, remotePort: Int, protocol: String, localPort: Int): ConnectionLogEntity? = null
    }

    private fun vm(dao: FakeConfigDao = FakeConfigDao(), logDao: FakeLogDao = FakeLogDao()) =
        VpnViewModel(app, dao, logDao)

    private suspend fun awaitIdle() { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }

    @Test fun configsFlowMapsEntitiesToModels() { runBlocking {
        val dao = FakeConfigDao()
        dao.rows.value = listOf(VpnConfigEntity(id = 3, name = "x", peerEndpoint = "h:1"))
        val vm = vm(dao)
        val list = withTimeout(5000) { vm.configs.first { it.isNotEmpty() } }
        assertEquals("x", list.single().name); assertEquals(3L, list.single().id)
        assertNull(vm.connectingId.value); assertNull(vm.status.value)
    } }

    @Test fun leaderboardsFlowThrough() { runBlocking {
        val logDao = FakeLogDao().apply {
            topApps.value = listOf(AppUsageSummary("com.a", "A", 100, 2))
            byCount.value = listOf(DomainCountSummary("a.com", 5))
            byBytes.value = listOf(DomainBytesSummary("a.com", 999))
        }
        val vm = vm(logDao = logDao)
        assertEquals(100L, withTimeout(5000) { vm.topAppsFlow.first { it.isNotEmpty() } }.single().totalBytes)
        assertEquals(5L, withTimeout(5000) { vm.domainsByCountFlow.first { it.isNotEmpty() } }.single().totalCount)
        assertEquals(999L, withTimeout(5000) { vm.domainsByBytesFlow.first { it.isNotEmpty() } }.single().totalBytes)
    } }

    @Test fun upsertDeleteAndClearStatus() { runBlocking {
        val dao = FakeConfigDao(); val vm = vm(dao)
        var savedId = -1L
        vm.upsert(VpnConfig(name = "new")) { savedId = it }
        withTimeout(5000) { while (dao.rows.value.isEmpty()) delay(20) }
        assertEquals("new", dao.rows.value.single().name)
        val cfg = dao.rows.value.single().let { VpnConfig(id = it.id, name = it.name) }
        vm.delete(cfg)
        withTimeout(5000) { while (dao.rows.value.isNotEmpty()) delay(20) }
        assertEquals(1, dao.deleted.size)
        vm.clearStatus(); assertNull(vm.status.value)
    } }

    @Test fun deletingLastBundleConfigRemovesBundleFiles() { runBlocking {
        val dao = FakeConfigDao(); val vm = vm(dao)
        val ctx = app as Context
        val bundleDir = File(File(ctx.filesDir, "mavpn-bundles"), "bundle-1").apply { mkdirs() }
        File(bundleDir, "manifest.json").writeText("{}")
        dao.rows.value = listOf(
            VpnConfigEntity(id = 1, name = "a", bundleId = "bundle-1", bundleServerId = "de-1"),
            VpnConfigEntity(id = 2, name = "b", bundleId = "bundle-1", bundleServerId = "us-1"),
            VpnConfigEntity(id = 3, name = "plain"),
        )
        // Deleting one of two bundle configs keeps the bundle files.
        vm.delete(VpnConfig(id = 1, name = "a", bundleId = "bundle-1", bundleServerId = "de-1"))
        withTimeout(5000) { while (dao.rows.value.any { it.id == 1L }) delay(20) }
        delay(100)
        assertTrue(bundleDir.exists())
        // Deleting the last bundle config removes them; the plain config is untouched.
        vm.delete(VpnConfig(id = 2, name = "b", bundleId = "bundle-1", bundleServerId = "us-1"))
        withTimeout(5000) { while (bundleDir.exists()) delay(20) }
        assertEquals(listOf(3L), dao.rows.value.map { it.id })
        // Deleting a plain (non-bundle) config never touches bundle storage.
        vm.delete(VpnConfig(id = 3, name = "plain"))
        withTimeout(5000) { while (dao.rows.value.isNotEmpty()) delay(20) }
    } }

    @Test fun deletingBundleConfigToleratesMissingBundleFiles() { runBlocking {
        val dao = FakeConfigDao(); val vm = vm(dao)
        dao.rows.value = listOf(
            VpnConfigEntity(id = 1, name = "a", bundleId = "never-stored", bundleServerId = "de-1"),
        )
        vm.delete(VpnConfig(id = 1, name = "a", bundleId = "never-stored", bundleServerId = "de-1"))
        withTimeout(5000) { while (dao.rows.value.isNotEmpty()) delay(20) }
        assertEquals(1, dao.deleted.size)
    } }

    @Test fun stopVpnSetsDisconnectedStatus() {
        val vm = vm()
        vm.stopVpn()
        assertEquals("Disconnected", vm.status.value); assertNull(vm.connectingId.value)
        // Shadow of started service: the disconnect intent was fired at the service
        val started = Shadows.shadowOf(app).nextStartedService
        assertNotNull(started); assertEquals(com.vayunmathur.vpn.service.VpnTunnelService.ACTION_DISCONNECT, started.action)
    }

    @Test fun deleteAllLogsClearsDaoAndStatus() { runBlocking {
        val logDao = FakeLogDao(); val vm = vm(logDao = logDao)
        vm.deleteAllLogs()
        assertEquals("Logs cleared", vm.status.value)
        withTimeout(5000) { while (logDao.deleteAllCalls == 0) delay(20) }
    } }

    @Test fun importFromUriSuccessAndFailures() { runBlocking {
        val dao = FakeConfigDao(); val vm = vm(dao)
        val ctx = app as Context
        val conf = File(ctx.cacheDir, "tunnel1.conf").apply {
            writeText("[Interface]\nPrivateKey = abc=\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = def=\nEndpoint = vpn.example.com:51820\n")
        }
        vm.importFromUri(ctx, Uri.fromFile(conf))
        withTimeout(8000) { while (dao.rows.value.isEmpty()) delay(25) }
        assertEquals("tunnel1", dao.rows.value.single().name)
        assertEquals("abc=", dao.rows.value.single().privateKey)
        withTimeout(5000) { while (vm.status.value?.startsWith("Imported") != true) delay(25) }
        // Bad file
        val bad = File(ctx.cacheDir, "bad.conf").apply { writeText("not a config") }
        vm.importFromUri(ctx, Uri.fromFile(bad))
        withTimeout(5000) { while (vm.status.value?.startsWith("Import failed") != true) delay(25) }
        // Missing file
        vm.importFromUri(ctx, Uri.parse("content://missing/x.conf"))
        delay(200)
    } }

    @Test fun importFromUriMavpnBundle() { runBlocking {
        val dao = FakeConfigDao(); val vm = vm(dao)
        val ctx = app as Context
        val manifest = """{"format":"mavpn/1","provider":"Example VPN","servers":[""" +
            """{"id":"de-1","country":"DE","city":"Frankfurt","file":"servers/de-1.conf", """ +
            """"endpoint":"manifest.example:9999"}]}"""
        val conf = "[Interface]\nPrivateKey = abc=\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = def=\nEndpoint = conf.example:51820\n"
        val bundleFile = File(ctx.cacheDir, "demo.mavpn")
        java.util.zip.ZipOutputStream(bundleFile.outputStream()).use { zip ->
            for ((name, text) in listOf("manifest.json" to manifest, "servers/de-1.conf" to conf)) {
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(text.encodeToByteArray()); zip.closeEntry()
            }
        }
        vm.importFromUri(ctx, Uri.fromFile(bundleFile))
        withTimeout(8000) { while (dao.rows.value.isEmpty()) delay(25) }
        val row = dao.rows.value.single()
        assertEquals("Example VPN", row.provider); assertEquals("DE", row.countryCode)
        assertEquals("Frankfurt", row.city); assertEquals("de-1", row.bundleServerId)
        assertEquals("manifest.example:9999", row.peerEndpoint)
        withTimeout(5000) { while (vm.status.value?.startsWith("Imported 1 servers") != true) delay(25) }
        assertEquals(listOf("Example VPN"), withTimeout(5000) { vm.importedProviders.first { it.isNotEmpty() } })
        assertEquals("Frankfurt", withTimeout(5000) { vm.geoCountries.first { it.isNotEmpty() } }.single().cities.single().city)
        // The bundle was stored in app-private storage under its bundle id.
        assertTrue(java.io.File(ctx.filesDir, "mavpn-bundles").listFiles()?.isNotEmpty() == true)
        // A corrupt bundle fails cleanly with no new rows.
        val badFile = File(ctx.cacheDir, "bad.mavpn").apply { writeBytes("not a zip".encodeToByteArray()) }
        vm.importFromUri(ctx, Uri.fromFile(badFile))
        withTimeout(5000) { while (vm.status.value?.startsWith("Import failed") != true) delay(25) }
        assertEquals(1, dao.rows.value.size)
    } }

    @Test fun importNameFromEndpointWhenNoFileName() { runBlocking {
        val dao = FakeConfigDao(); val vm = vm(dao)
        val ctx = app as Context
        // content uri with no meaningful last segment goes through contentResolver; use a file named ".conf"
        val conf = File(ctx.cacheDir, ".conf").apply {
            writeText("[Interface]\nPrivateKey = abc=\n[Peer]\nPublicKey = def=\nEndpoint = host.example:1234\n")
        }
        vm.importFromUri(ctx, Uri.fromFile(conf))
        withTimeout(8000) { while (dao.rows.value.isEmpty()) delay(25) }
        val name = dao.rows.value.single().name
        assertTrue(name.isNotBlank(), "name=$name")
    } }

    @Test fun initPollClearsConnectingWhenServiceStopped() {
        val vm = vm()
        // The init loop polls VpnTunnelService.isRunning every 500ms and clears
        // a stale connecting id; advancing the Robolectric clock runs iterations.
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertNull(vm.connectingId.value)
    }

    @Test fun factoryCreatesAndRejects() {
        val factory = VpnViewModelFactory(app, FakeConfigDao(), FakeLogDao())
        assertNotNull(factory.create(VpnViewModel::class.java))
        try {
            factory.create(String::class.java as Class<androidx.lifecycle.ViewModel>)
            kotlin.test.fail("should reject other VM classes")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun bypassListRoundTrip() { runBlocking {
        val ctx = app as Context
        BypassList.setBypassed(ctx, "com.example.a", true)
        withTimeout(5000) { while (!BypassList.load(ctx).contains("com.example.a")) delay(25) }
        assertTrue(BypassList.flow(ctx).first().contains("com.example.a"))
        BypassList.setBypassed(ctx, "com.example.a", false)
        withTimeout(5000) { while (BypassList.load(ctx).contains("com.example.a")) delay(25) }
        assertEquals("bypass_packages", BypassList.KEY)
    } }
}
