package com.vayunmathur.appstore.data

import android.content.Context
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
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The full F-Droid trust chain over loopback: a jarsigner-signed
 * `entry.jar` (generated with the JDK's own keytool/jarsigner at test
 * setup) names and hashes `index-v2.json`, which is then streamed into
 * listings. A wrong pin or a tampered index must fail the fetch.
 */
@RunWith(RobolectricTestRunner::class)
class FDroidFetchRepoIndexTest {

    private lateinit var server: HttpServer
    private lateinit var repoUrl: String
    private lateinit var work: File
    private lateinit var fingerprint: String
    private val servedIndex = AtomicReference<ByteArray>()

    private val signerSha = "bb".repeat(32)
    private val apkSha = "aa".repeat(32)

    private fun indexJson(): String = """
        {
          "packages": {
            "com.example.alpha": {
              "metadata": {
                "name": {"en-US": "Alpha"},
                "summary": {"en-US": "Alpha summary"},
                "description": {"en-US": "Alpha description"},
                "authorName": "Alpha Author",
                "categories": ["Tools"],
                "webSite": "https://example.org",
                "license": "GPL-3.0",
                "added": 1000,
                "lastUpdated": 2000,
                "icon": {"en-US": {"name": "/icons/alpha.png"}},
                "featureGraphic": {"en-US": {"name": "/feature.png"}},
                "screenshots": {
                  "phone": {"en-US": [{"name": "/shots/phone1.png"}]},
                  "tv": {"en-US": [{"name": "/shots/tv1.png"}]}
                },
                "antiFeatures": {"Tracking": {"en-US": "tracks"}}
              },
              "versions": {
                "v1": {
                  "added": 1000,
                  "file": {"name": "/alpha_1.apk", "sha256": "$apkSha", "size": 1234},
                  "manifest": {
                    "versionName": "1.0",
                    "versionCode": 1,
                    "usesSdk": {"targetSdkVersion": 35},
                    "signer": {"sha256": ["$signerSha"]},
                    "features": [{"name": "android.hardware.camera"}]
                  },
                  "whatsNew": {"en-US": "First release"}
                }
              }
            },
            "com.example.lowtarget": {
              "metadata": {"name": {"en-US": "Low Target"}},
              "versions": {
                "v1": {
                  "added": 1000,
                  "file": {"name": "/low_1.apk", "sha256": "$apkSha", "size": 10},
                  "manifest": {"versionName": "1.0", "versionCode": 1,
                               "usesSdk": {"targetSdkVersion": 20}}
                }
              }
            },
            "com.example.splitset": {
              "metadata": {"name": {"en-US": "Split Set"}},
              "versions": {
                "base": {
                  "added": 1000,
                  "file": {"name": "/split_base.apk", "sha256": "$apkSha", "size": 100},
                  "manifest": {"versionName": "1.0", "versionCode": 5,
                               "usesSdk": {"targetSdkVersion": 35},
                               "requiredSplitTypes": ["config"]}
                },
                "cfg": {
                  "added": 1000,
                  "file": {"name": "/split_cfg.apk", "sha256": "$apkSha", "size": 50},
                  "manifest": {"versionName": "1.0", "versionCode": 5,
                               "usesSdk": {"targetSdkVersion": 35}}
                }
              }
            },
            "com.example.dynamite": {
              "metadata": {"name": {"en-US": "Dynamite Only"}},
              "versions": {
                "v1": {
                  "added": 1000,
                  "file": {"name": "/gms_1_installtime.apk", "sha256": "$apkSha", "size": 10},
                  "manifest": {"versionName": "1.0", "versionCode": 1,
                               "usesSdk": {"targetSdkVersion": 35}}
                }
              }
            },
            "com.example.noversion": {
              "metadata": {"name": {"en-US": "No Version"}},
              "versions": {}
            },
            "com.example.broken": "not-a-package-object"
          }
        }
    """.trimIndent()

    private fun runTool(tool: String, vararg args: String) {
        val exe = File(System.getProperty("java.home"), "bin/$tool")
        val process = ProcessBuilder(listOf(exe.absolutePath) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "$tool failed: $output")
    }

    @BeforeTest
    fun setUp() {
        work = File.createTempFile("fdroid-fetch", "dir").let { it.delete(); it.mkdirs(); it }
        val indexBytes = indexJson().toByteArray()
        servedIndex.set(indexBytes)

        // entry.json chains the signed root to the index by hash.
        val entryJson = """{"index": {"name": "index-v2.json", "sha256": "${ApkCertificates.sha256(indexBytes)}"}}"""
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
            "-storepass", "changeit", "-keypass", "changeit", "-dname", "CN=FDroid Test",
        )
        val signedJar = File(work, "entry.jar")
        runTool(
            "jarsigner", "-keystore", keystore.absolutePath, "-storepass", "changeit",
            "-signedjar", signedJar.absolutePath, entryJar.absolutePath, "test",
        )
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        keystore.inputStream().use { ks.load(it, "changeit".toCharArray()) }
        fingerprint = ApkCertificates.sha256(ks.getCertificate("test").encoded)

        val signedBytes = signedJar.readBytes()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/repo/entry.jar") { exchange ->
            exchange.sendResponseHeaders(200, signedBytes.size.toLong())
            exchange.responseBody.use { it.write(signedBytes) }
        }
        server.createContext("/repo/index-v2.json") { exchange ->
            val bytes = servedIndex.get()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        repoUrl = "http://127.0.0.1:${server.address.port}/repo"
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        work.deleteRecursively()
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `signed index is parsed into listings and low quality entries dropped`() = runTest {
        val result = FDroidRepository.fetchRepoIndex(
            context(), repoUrl, fingerprint, AppSource.FDROID,
        ) { pkg, _ -> pkg == "com.example.alpha" }

        assertEquals(fingerprint, result.signerSha256)
        val alpha = result.apps.singleOrNull { it.packageName == "com.example.alpha" }
        assertTrue(alpha != null, "alpha missing from ${result.apps.map { it.packageName }}")
        assertEquals("Alpha", alpha.name)
        assertEquals("Alpha summary", alpha.summary)
        assertEquals("Alpha Author", alpha.author)
        assertEquals(listOf("Tools"), alpha.categories)
        assertEquals("1.0", alpha.versionName)
        assertEquals(1L, alpha.versionCode)
        assertEquals(1234L, alpha.sizeBytes)
        assertEquals(35, alpha.targetSdk)
        assertEquals(listOf(signerSha), alpha.expectedSigners)
        assertEquals(apkSha, alpha.apkSha256)
        assertEquals("$repoUrl/alpha_1.apk", alpha.apkUrl)
        assertEquals("$repoUrl/icons/alpha.png", alpha.iconUrl)
        assertEquals(listOf("Tracking"), alpha.antiFeatures)
        assertTrue(alpha.reproducible)
        assertEquals("$repoUrl/feature.png", alpha.featureGraphic)
        assertEquals(listOf("$repoUrl/shots/phone1.png"), alpha.screenshots)
        assertEquals(listOf("$repoUrl/shots/tv1.png"), alpha.tvScreenshots)

        // A split-set release advertises its base and gathers the sibling
        // sharing its versionCode; a Dynamite-only package is dropped.
        val splitset = result.apps.singleOrNull { it.packageName == "com.example.splitset" }
        assertTrue(splitset != null, "splitset missing from ${result.apps.map { it.packageName }}")
        assertEquals("$repoUrl/split_base.apk", splitset.apkUrl)
        assertTrue(splitset.containsSplit)
        assertEquals(listOf("/split_cfg.apk"), splitset.splitFiles.map { it.fileName })
        assertEquals("$repoUrl/split_cfg.apk", splitset.splitFiles.single().url)
        assertTrue(result.apps.none { it.packageName == "com.example.dynamite" })

        // targetSdk below the floor is filtered; versionless and malformed
        // packages never reach the catalogue.
        assertTrue(result.apps.none { it.packageName == "com.example.lowtarget" })
        assertTrue(result.apps.none { it.packageName == "com.example.noversion" })
        assertTrue(result.apps.none { it.packageName == "com.example.broken" })
    }

    @Test
    fun `a wrong pinned fingerprint fails the fetch`() = runTest {
        assertFailsWith<Exception> {
            FDroidRepository.fetchRepoIndex(
                context(), repoUrl, "0".repeat(64), AppSource.FDROID,
            ) { _, _ -> false }
        }
    }

    @Test
    fun `a tampered index fails its signed hash check`() = runTest {
        servedIndex.set("{}".toByteArray())
        val e = assertFailsWith<Exception> {
            FDroidRepository.fetchRepoIndex(
                context(), repoUrl, fingerprint, AppSource.FDROID,
            ) { _, _ -> false }
        }
        assertTrue(e.message!!.contains("hash"), e.message!!)
    }
}
