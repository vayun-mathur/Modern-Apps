package com.vayunmathur.appstore.data.installer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.DownloadCache
import com.vayunmathur.appstore.data.SplitFile
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.accrescent.AccrescentRepository
import com.vayunmathur.appstore.data.play.PlayRepository
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Split-set direct installs: base plus sibling splits download (with a
 * truncated first attempt retried once), verify against the published
 * hash and signer, and commit as one session; a wrong published hash
 * rejects the set.
 */
@RunWith(RobolectricTestRunner::class)
class InstallCoordinatorSplitInstallTest {

    private val baseBytes = "split-set-base-bytes".toByteArray()
    private val splitBytes = "split-set-config-bytes!".toByteArray()

    private lateinit var server: HttpServer
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: InstallCoordinator
    private lateinit var context: Context
    private val flakyHits = AtomicInteger(0)

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    private fun certHash(): String {
        val der = android.util.Base64.decode(CERT_A, android.util.Base64.DEFAULT)
        return sha256Hex(der)
    }

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/base.apk") { exchange ->
            exchange.sendResponseHeaders(200, baseBytes.size.toLong())
            exchange.responseBody.use { it.write(baseBytes) }
        }
        server.createContext("/split.apk") { exchange ->
            exchange.sendResponseHeaders(200, splitBytes.size.toLong())
            exchange.responseBody.use { it.write(splitBytes) }
        }
        server.createContext("/flaky.apk") { exchange ->
            // First attempt is short (but well-formed); the retry is whole.
            val served = if (flakyHits.incrementAndGet() == 1) {
                baseBytes.copyOf(5)
            } else {
                baseBytes
            }
            exchange.sendResponseHeaders(200, served.size.toLong())
            exchange.responseBody.use { it.write(served) }
        }
        server.start()

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(Dispatchers.Unconfined)
        coordinator = InstallCoordinator(
            context = context,
            db = db,
            play = PlayRepository(context),
            accrescent = AccrescentRepository(context, db),
            scope = scope,
            ownSigningCertificates = { emptySet() },
        )
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        scope.cancel()
        db.close()
    }

    private fun signingInfo(): SigningInfo {
        val der = android.util.Base64.decode(CERT_A, android.util.Base64.DEFAULT)
        val info = SigningInfo()
        val setSignatures = Shadows.shadowOf(info).javaClass
            .getDeclaredMethod("setSignatures", Array<Signature>::class.java)
        setSignatures.isAccessible = true
        setSignatures.invoke(Shadows.shadowOf(info), arrayOf(Signature(der)))
        return info
    }

    private fun registerArchive(pkg: String, staged: File) {
        val info = PackageInfo().apply {
            packageName = pkg
            @Suppress("DEPRECATION")
            versionCode = 7
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                sourceDir = staged.absolutePath
                targetSdkVersion = 35
            }
            signingInfo = signingInfo()
        }
        Shadows.shadowOf(context.packageManager).addPackage(info)
    }

    private fun splitApp(pkg: String, apkUrl: String, apkSha256: String?) = UnifiedApp(
        packageName = pkg,
        source = AppSource.FDROID,
        name = "Split App",
        apkUrl = apkUrl,
        sizeBytes = baseBytes.size.toLong(),
        apkSha256 = apkSha256,
        expectedSigners = listOf(certHash()),
        splitFiles = listOf(
            SplitFile(
                fileName = "${pkg}.split0.apk",
                url = "http://127.0.0.1:${server.address.port}/split.apk",
                sha256 = sha256Hex(splitBytes),
                size = splitBytes.size.toLong(),
            ),
        ),
    )

    @Test
    fun `a split set downloads verifies and commits`() = runBlocking {
        val pkg = "com.example.splits"
        registerArchive(pkg, File(context.cacheDir, DownloadCache.apkName(pkg)))
        registerArchive(pkg, File(context.cacheDir, DownloadCache.splitApkName(pkg, 0)))
        val app = splitApp(
            pkg,
            "http://127.0.0.1:${server.address.port}/base.apk",
            sha256Hex(baseBytes),
        )
        val outcome = coordinator.install(app)
        assertTrue(outcome.started, "install did not start: ${outcome.verification}")
        assertEquals(InstallStage.Installing, coordinator.stages.value[pkg])
    }

    @Test
    fun `a truncated base download is retried once`() = runBlocking {
        val pkg = "com.example.flaky"
        registerArchive(pkg, File(context.cacheDir, DownloadCache.apkName(pkg)))
        registerArchive(pkg, File(context.cacheDir, DownloadCache.splitApkName(pkg, 0)))
        val app = splitApp(
            pkg,
            "http://127.0.0.1:${server.address.port}/flaky.apk",
            sha256Hex(baseBytes),
        )
        val outcome = coordinator.install(app)
        assertTrue(outcome.started, "install did not start: ${outcome.verification}")
        assertEquals(2, flakyHits.get())
    }

    @Test
    fun `a wrong published base hash rejects the set`() = runBlocking {
        val pkg = "com.example.badhash"
        registerArchive(pkg, File(context.cacheDir, DownloadCache.apkName(pkg)))
        registerArchive(pkg, File(context.cacheDir, DownloadCache.splitApkName(pkg, 0)))
        val app = splitApp(
            pkg,
            "http://127.0.0.1:${server.address.port}/base.apk",
            sha256Hex("other-bytes".toByteArray()),
        )
        val outcome = coordinator.install(app)
        assertFalse(outcome.started)
        assertTrue(coordinator.stages.value[pkg] is InstallStage.Failed)
    }

    private companion object {
        const val CERT_A = "MIICvTCCAaWgAwIBAgIED3lFmDANBgkqhkiG9w0BAQsFADAPMQ0wCwYDVQQDEwRUZXN0MB4XDTI2MTEwNzAyNTAyNFoXDTM2MTEwNDAyNTAyNFowDzENMAsGA1UEAxMEVGVzdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALS7Wkz9gUndEJscrbEZgQHPQh7aVHnWFIXpw7yMZpLlI7QUBg8x8jZPJeHbWJXws/IW66dod0/QmqrG4y21i1KIQW5xwIPo3s3Igrs9ZnO05kd9Um9sgrcagAd4CjnUI0Um10AIogP6Bz6rUFL8WGDNvACq22bJDRaKVVIno7FuwgGQY+7Jrn73B4rQjyOM+PJtAnxNGBcDUVXa6CCGhU9DT/eYCtnjKlOVF8Pmflwe4LlrfYRqq7wJm2d+I6cZHwMX2coH9BwymuRCT4s6NnYkQZVYAvy8BFVI3jvK+Q259oDKDDZBLp+fHs9cUHJQ2TwnKH5rFH7tYXIwvkHgG5ECAwEAAaMhMB8wHQYDVR0OBBYEFM8ej83GSYbEXAs4JDW/shyQY5x1MA0GCSqGSIb3DQEBCwUAA4IBAQCboZxi2u+lovXniEQCy31XZhU+vKXr+u1lyHKREgy88rcDrXquapNJX0XpaJBSLFEc180j3Fr6vWst0JynJxjq7dFHqf7wDSLJQDysXEDEUr2+H6Edkjbw8Lju88uXrD7v7BgHSxsn1ffZ02FfzFKF6zC4M7pp2FkJj/1CdtW4JbfcH2RWqht8k7YSAvWgPm2hzroQzMHe3BxGsSj0t9+9F5GdBtZdnG+/i8iBcOvmk+r7p0xkWuH7qaZmcGqNrX9m31FnZMp9OWkwkCqc8yncKDJ89P810JAcVL6Fp0cBrUMACkNAH5nIpDxAfV1mDp0FW8mDYuW1qnNxg0zdPfWX"
    }
}
