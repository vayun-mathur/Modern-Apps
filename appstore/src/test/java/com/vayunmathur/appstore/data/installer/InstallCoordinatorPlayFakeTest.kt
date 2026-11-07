package com.vayunmathur.appstore.data.installer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.aurora.gplayapi.data.models.PlayFile
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.DownloadCache
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.accrescent.AccrescentRepository
import com.vayunmathur.appstore.data.play.PlayRepository
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
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
 * Play installs through InstallCoordinator with a fake PlayRepository
 * (details + purchase) and loopback delivery: the purchased file
 * downloads, verifies against the delivery hash and the listing's
 * signer, and commits; an expired first delivery URL is re-purchased
 * once; an empty purchase fails the row.
 */
@RunWith(RobolectricTestRunner::class)
class InstallCoordinatorPlayFakeTest {

    private val bytes = "play-delivery-bytes".toByteArray()

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    private fun certHash(): String {
        val der = android.util.Base64.decode(CERT_A, android.util.Base64.DEFAULT)
        return sha256Hex(der)
    }

    private inner class FakePlay(context: Context) : PlayRepository(context) {
        var purchaseCalls = 0
        var expireFirstPurchase = false
        var returnEmpty = false
        var goodUrl = ""
        var goneUrl = ""

        override suspend fun details(packageName: String): UnifiedApp = UnifiedApp(
            packageName = packageName,
            source = AppSource.PLAYSTORE,
            name = "Play App",
            versionCode = 7L,
            expectedSigners = listOf(certHash()),
        )

        override suspend fun purchase(
            packageName: String,
            versionCode: Long,
            offerType: Int,
            certHash: String?,
        ): List<PlayFile> {
            purchaseCalls++
            if (returnEmpty) return emptyList()
            val url = if (expireFirstPurchase && purchaseCalls == 1) goneUrl else goodUrl
            return listOf(
                PlayFile(
                    name = "base.apk",
                    url = url,
                    size = bytes.size.toLong(),
                    sha256 = sha256Hex(bytes),
                ),
            )
        }
    }

    private lateinit var http: HttpServer
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var fake: FakePlay
    private lateinit var coordinator: InstallCoordinator
    private lateinit var context: Context

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/apk") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.createContext("/gone") { exchange ->
            exchange.sendResponseHeaders(410, -1)
            exchange.close()
        }
        http.start()

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(Dispatchers.Unconfined)
        fake = FakePlay(context).apply {
            goodUrl = "http://127.0.0.1:${http.address.port}/apk"
            goneUrl = "http://127.0.0.1:${http.address.port}/gone"
        }
        coordinator = InstallCoordinator(
            context = context,
            db = db,
            play = fake,
            accrescent = AccrescentRepository(context, db),
            scope = scope,
            ownSigningCertificates = { emptySet() },
        )
    }

    @AfterTest
    fun tearDown() {
        http.stop(0)
        scope.cancel()
        db.close()
    }

    private fun registerArchive(pkg: String) {
        val staged = File(DownloadCache.playDir(context.cacheDir, pkg, 7L), "base.apk")
        val der = android.util.Base64.decode(CERT_A, android.util.Base64.DEFAULT)
        val signingInfo = SigningInfo()
        val setSignatures = Shadows.shadowOf(signingInfo).javaClass
            .getDeclaredMethod("setSignatures", Array<Signature>::class.java)
        setSignatures.isAccessible = true
        setSignatures.invoke(Shadows.shadowOf(signingInfo), arrayOf(Signature(der)))
        val info = PackageInfo().apply {
            packageName = pkg
            @Suppress("DEPRECATION")
            versionCode = 7
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                sourceDir = staged.absolutePath
                targetSdkVersion = 35
            }
            this.signingInfo = signingInfo
        }
        Shadows.shadowOf(context.packageManager).addPackage(info)
    }

    private fun playApp(pkg: String) = UnifiedApp(
        packageName = pkg,
        source = AppSource.PLAYSTORE,
        name = "Play App",
        versionCode = 7L,
    )

    @Test
    fun `a purchased delivery downloads verifies and commits`() = runBlocking {
        val pkg = "com.example.playok"
        registerArchive(pkg)
        val outcome = coordinator.install(playApp(pkg))
        assertTrue(outcome.started, "install did not start: ${outcome.verification}")
        assertEquals(1, fake.purchaseCalls)
        assertEquals(InstallStage.Installing, coordinator.stages.value[pkg])
    }

    @Test
    fun `an expired delivery url is re-purchased once`() = runBlocking {
        val pkg = "com.example.playretry"
        registerArchive(pkg)
        fake.expireFirstPurchase = true
        val outcome = coordinator.install(playApp(pkg))
        assertTrue(outcome.started, "install did not start: ${outcome.verification}")
        assertEquals(2, fake.purchaseCalls)
    }

    @Test
    fun `an empty purchase fails the row`() = runBlocking {
        val pkg = "com.example.playempty"
        fake.returnEmpty = true
        val outcome = coordinator.install(playApp(pkg))
        assertFalse(outcome.started)
        assertTrue(coordinator.stages.value[pkg] is InstallStage.Failed)
    }

    private companion object {
        const val CERT_A = "MIICvTCCAaWgAwIBAgIED3lFmDANBgkqhkiG9w0BAQsFADAPMQ0wCwYDVQQDEwRUZXN0MB4XDTI2MTEwNzAyNTAyNFoXDTM2MTEwNDAyNTAyNFowDzENMAsGA1UEAxMEVGVzdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALS7Wkz9gUndEJscrbEZgQHPQh7aVHnWFIXpw7yMZpLlI7QUBg8x8jZPJeHbWJXws/IW66dod0/QmqrG4y21i1KIQW5xwIPo3s3Igrs9ZnO05kd9Um9sgrcagAd4CjnUI0Um10AIogP6Bz6rUFL8WGDNvACq22bJDRaKVVIno7FuwgGQY+7Jrn73B4rQjyOM+PJtAnxNGBcDUVXa6CCGhU9DT/eYCtnjKlOVF8Pmflwe4LlrfYRqq7wJm2d+I6cZHwMX2coH9BwymuRCT4s6NnYkQZVYAvy8BFVI3jvK+Q259oDKDDZBLp+fHs9cUHJQ2TwnKH5rFH7tYXIwvkHgG5ECAwEAAaMhMB8wHQYDVR0OBBYEFM8ej83GSYbEXAs4JDW/shyQY5x1MA0GCSqGSIb3DQEBCwUAA4IBAQCboZxi2u+lovXniEQCy31XZhU+vKXr+u1lyHKREgy88rcDrXquapNJX0XpaJBSLFEc180j3Fr6vWst0JynJxjq7dFHqf7wDSLJQDysXEDEUr2+H6Edkjbw8Lju88uXrD7v7BgHSxsn1ffZ02FfzFKF6zC4M7pp2FkJj/1CdtW4JbfcH2RWqht8k7YSAvWgPm2hzroQzMHe3BxGsSj0t9+9F5GdBtZdnG+/i8iBcOvmk+r7p0xkWuH7qaZmcGqNrX9m31FnZMp9OWkwkCqc8yncKDJ89P810JAcVL6Fp0cBrUMACkNAH5nIpDxAfV1mDp0FW8mDYuW1qnNxg0zdPfWX"
    }
}
