package com.vayunmathur.appstore.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.appstore.data.security.ApkCertificates
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One repo's import into the shared catalogue, against the same loopback
 * signed repo as the fetch test: rows are replaced per repo, the repo's
 * fingerprint/last-sync are recorded, a verified-but-empty index keeps
 * the previous rows, and a failed sync leaves the cache untouched.
 */
@RunWith(RobolectricTestRunner::class)
class FDroidAppProviderSyncTest {

    private lateinit var server: HttpServer
    private lateinit var work: File
    private lateinit var db: AppDatabase
    private lateinit var provider: FDroidAppProvider
    private val servedIndex = AtomicReference<ByteArray>()
    private lateinit var repoUrl: String

    private fun indexWith(vararg packages: String): String =
        """{"packages": {${packages.joinToString(",")}}}"""

    private fun pkg(name: String, file: String): String = """
        "com.example.$name": {
          "metadata": {"name": {"en-US": "$name"}},
          "versions": {"v1": {
            "added": 1000,
            "file": {"name": "/$file", "sha256": "${"aa".repeat(32)}", "size": 10},
            "manifest": {"versionName": "1.0", "versionCode": 1,
                         "usesSdk": {"targetSdkVersion": 35}}
          }}
        }
    """.trimIndent()

    private fun runTool(tool: String, vararg args: String) {
        val exe = File(System.getProperty("java.home"), "bin/$tool")
        val process = ProcessBuilder(listOf(exe.absolutePath) + args)
            .redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "$tool failed: $output")
    }

    @BeforeTest
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        work = File.createTempFile("fdroid-provider", "dir").let { it.delete(); it.mkdirs(); it }
        servedIndex.set(indexWith(pkg("alpha", "alpha_1.apk")).toByteArray())

        val entryJson = """{"index": {"name": "index-v2.json", "sha256": "${ApkCertificates.sha256(servedIndex.get())}"}}"""
        // The entry hash must track the served index, so the jar is rebuilt
        // per test in setUpIndex() below; start with the initial index.
        rebuildEntry(entryJson)
    }

    private fun rebuildEntry(entryJson: String) {
        val entryJar = File(work, "entry-unsigned.jar")
        JarOutputStream(entryJar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("entry.json"))
            out.write(entryJson.toByteArray())
            out.closeEntry()
        }
        val keystore = File(work, "ks.jks")
        if (!keystore.exists()) {
            runTool(
                "keytool", "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "3650", "-keystore", keystore.absolutePath,
                "-storepass", "changeit", "-keypass", "changeit", "-dname", "CN=FDroid Test",
            )
        }
        val signedJar = File(work, "entry.jar")
        signedJar.delete()
        runTool(
            "jarsigner", "-keystore", keystore.absolutePath, "-storepass", "changeit",
            "-signedjar", signedJar.absolutePath, entryJar.absolutePath, "test",
        )
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        keystore.inputStream().use { ks.load(it, "changeit".toCharArray()) }
        val fingerprint = ApkCertificates.sha256(ks.getCertificate("test").encoded)

        if (!::server.isInitialized) {
            val signedBytes = AtomicReference(signedJar.readBytes())
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/repo/entry.jar") { exchange ->
                val bytes = File(work, "entry.jar").readBytes()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.createContext("/repo/index-v2.json") { exchange ->
                val bytes = servedIndex.get()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            repoUrl = "http://127.0.0.1:${server.address.port}/repo"
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            provider = FDroidAppProvider(
                db,
                ctx,
                RepoDescriptor(
                    url = repoUrl,
                    displayName = "Test Repo",
                    pinnedFingerprint = fingerprint,
                    source = AppSource.FDROID,
                    supportsReproducibilityFeed = false,
                ),
            )
        }
    }

    /** Point the signed entry at a new index, as a repo publish would. */
    private fun serveIndex(json: String) {
        servedIndex.set(json.toByteArray())
        val entryJson = """{"index": {"name": "index-v2.json", "sha256": "${ApkCertificates.sha256(servedIndex.get())}"}}"""
        rebuildEntry(entryJson)
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        db.close()
        work.deleteRecursively()
    }

    @Test
    fun `sync imports rows and records the repo fingerprint`() = runTest {
        assertEquals(1, provider.syncIntoDb())
        val row = db.cachedAppDao().byPackage("com.example.alpha")
        assertNotNull(row)
        assertEquals("alpha", row.name)
        assertEquals(repoUrl, row.repoUrl)
        val repo = db.repoDao().all().single { it.url == repoUrl }
        assertTrue(repo.lastSync > 0L)
        assertEquals(64, repo.fingerprint?.length)
    }

    @Test
    fun `a later sync replaces only this repos rows`() = runTest {
        provider.syncIntoDb()
        db.cachedAppDao().upsert(
            UnifiedApp("com.example.other", AppSource.MODERN_APPS, "Other").toEntity(),
        )
        serveIndex(indexWith(pkg("beta", "beta_1.apk")))
        assertEquals(1, provider.syncIntoDb())
        assertEquals(null, db.cachedAppDao().byPackage("com.example.alpha"))
        assertNotNull(db.cachedAppDao().byPackage("com.example.beta"))
        assertNotNull(db.cachedAppDao().byPackage("com.example.other"))
    }

    @Test
    fun `a verified but empty index keeps the cached rows`() = runTest {
        provider.syncIntoDb()
        serveIndex("""{"packages": {}}""")
        assertEquals(0, provider.syncIntoDb())
        assertNotNull(db.cachedAppDao().byPackage("com.example.alpha"))
    }

    @Test
    fun `a failed sync throws and leaves the cache untouched`() = runTest {
        provider.syncIntoDb()
        // Serve index bytes the signed entry does not hash to.
        servedIndex.set("{}".toByteArray())
        assertFailsWith<Exception> { provider.syncIntoDb() }
        assertNotNull(db.cachedAppDao().byPackage("com.example.alpha"))
    }
}
