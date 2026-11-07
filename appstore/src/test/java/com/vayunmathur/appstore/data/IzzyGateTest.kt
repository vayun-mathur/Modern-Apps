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
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The IzzyOnDroid admission gate in a real sync: Izzy ships developers'
 * own binaries, so only versions an independent builder reproduced may
 * be imported. With the rbtlog feed unreachable (offline), nothing is
 * admitted and the sync imports zero rows — never everything unfiltered.
 * The repo itself is the same loopback signed repo as the other sync tests.
 */
@RunWith(RobolectricTestRunner::class)
class IzzyGateTest {

    private lateinit var server: HttpServer
    private lateinit var work: File
    private lateinit var db: AppDatabase
    private lateinit var provider: FDroidAppProvider

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
        work = File.createTempFile("izzy-gate", "dir").let { it.delete(); it.mkdirs(); it }
        val index = """
            {"packages": {"com.example.izzyapp": {
              "metadata": {"name": {"en-US": "Izzy App"}},
              "versions": {"v1": {
                "added": 1000,
                "file": {"name": "/izzy_1.apk", "sha256": "${"aa".repeat(32)}", "size": 10},
                "manifest": {"versionName": "1.0", "versionCode": 1,
                             "usesSdk": {"targetSdkVersion": 35}}
              }}
            }}}
        """.trimIndent().toByteArray()
        val entryJson =
            """{"index": {"name": "index-v2.json", "sha256": "${ApkCertificates.sha256(index)}"}}"""
        val entryJar = File(work, "entry-unsigned.jar")
        JarOutputStream(entryJar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("entry.json"))
            out.write(entryJson.toByteArray())
            out.closeEntry()
        }
        val keystore = File(work, "ks.jks")
        runTool(
            "keytool", "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "3650", "-keystore", keystore.absolutePath,
            "-storepass", "changeit", "-keypass", "changeit", "-dname", "CN=Izzy Test",
        )
        val signedJar = File(work, "entry.jar")
        runTool(
            "jarsigner", "-keystore", keystore.absolutePath, "-storepass", "changeit",
            "-signedjar", signedJar.absolutePath, entryJar.absolutePath, "test",
        )
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        keystore.inputStream().use { ks.load(it, "changeit".toCharArray()) }
        val fingerprint = ApkCertificates.sha256(ks.getCertificate("test").encoded)
        val signedBytes = signedJar.readBytes()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/repo/entry.jar") { exchange ->
            exchange.sendResponseHeaders(200, signedBytes.size.toLong())
            exchange.responseBody.use { it.write(signedBytes) }
        }
        server.createContext("/repo/index-v2.json") { exchange ->
            exchange.sendResponseHeaders(200, index.size.toLong())
            exchange.responseBody.use { it.write(index) }
        }
        server.start()
        val repoUrl = "http://127.0.0.1:${server.address.port}/repo"

        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        provider = FDroidAppProvider(
            db,
            ctx,
            RepoDescriptor(
                url = repoUrl,
                displayName = "Izzy Test",
                pinnedFingerprint = fingerprint,
                source = AppSource.IZZY,
                supportsReproducibilityFeed = false,
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        db.close()
        work.deleteRecursively()
    }

    @Test
    fun `with no reachable verdict feed an izzy sync imports nothing`() = runTest {
        assertEquals(0, provider.syncIntoDb())
        assertNull(db.cachedAppDao().byPackage("com.example.izzyapp"))
        assertEquals(0, provider.lastReproducibleCount)
    }
}
