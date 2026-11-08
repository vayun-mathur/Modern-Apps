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
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.accrescent.AccrescentRepository
import com.vayunmathur.appstore.data.play.PlayRepository
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The whole direct-download install, end to end over loopback: bytes
 * download, verify against the shadow-served archive info, and commit
 * to a (shadow) PackageInstaller session. The row must sit at
 * Installing afterwards, and clear when the receiver's success event
 * arrives — the same hand-off the real OS performs.
 */
@RunWith(RobolectricTestRunner::class)
class InstallCoordinatorHappyPathTest {

    private lateinit var server: HttpServer
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: InstallCoordinator
    private lateinit var context: Context

    private val bytes = "e2e-apk-bytes-served-over-loopback".toByteArray()

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/app.apk") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
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

    @Test
    fun `download verify commit leaves the row installing until the os reports success`() = runBlocking {
        val pkg = "com.example.e2e"
        // The verifier reads archive info for the staged file's path; the
        // shadow PM serves this registration for exactly that path.
        val staged = File(context.cacheDir, DownloadCache.apkName(pkg))
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

        val app = UnifiedApp(
            packageName = pkg,
            source = AppSource.FDROID,
            name = "E2E App",
            apkUrl = "http://127.0.0.1:${server.address.port}/app.apk",
            sizeBytes = bytes.size.toLong(),
        )
        val outcome = coordinator.install(app)
        assertTrue(outcome.started, "install did not start: ${outcome.verification}")
        assertEquals(InstallStage.Installing, coordinator.stages.value[pkg])

        // The OS reports back through the receiver flow; the row clears.
        InstallEvents.publish(InstallResult(pkg, success = true))
        assertEquals(null, coordinator.stages.value[pkg])
    }

    private companion object {
        const val CERT_A = "MIICvTCCAaWgAwIBAgIED3lFmDANBgkqhkiG9w0BAQsFADAPMQ0wCwYDVQQDEwRUZXN0MB4XDTI2MTEwNzAyNTAyNFoXDTM2MTEwNDAyNTAyNFowDzENMAsGA1UEAxMEVGVzdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALS7Wkz9gUndEJscrbEZgQHPQh7aVHnWFIXpw7yMZpLlI7QUBg8x8jZPJeHbWJXws/IW66dod0/QmqrG4y21i1KIQW5xwIPo3s3Igrs9ZnO05kd9Um9sgrcagAd4CjnUI0Um10AIogP6Bz6rUFL8WGDNvACq22bJDRaKVVIno7FuwgGQY+7Jrn73B4rQjyOM+PJtAnxNGBcDUVXa6CCGhU9DT/eYCtnjKlOVF8Pmflwe4LlrfYRqq7wJm2d+I6cZHwMX2coH9BwymuRCT4s6NnYkQZVYAvy8BFVI3jvK+Q259oDKDDZBLp+fHs9cUHJQ2TwnKH5rFH7tYXIwvkHgG5ECAwEAAaMhMB8wHQYDVR0OBBYEFM8ej83GSYbEXAs4JDW/shyQY5x1MA0GCSqGSIb3DQEBCwUAA4IBAQCboZxi2u+lovXniEQCy31XZhU+vKXr+u1lyHKREgy88rcDrXquapNJX0XpaJBSLFEc180j3Fr6vWst0JynJxjq7dFHqf7wDSLJQDysXEDEUr2+H6Edkjbw8Lju88uXrD7v7BgHSxsn1ffZ02FfzFKF6zC4M7pp2FkJj/1CdtW4JbfcH2RWqht8k7YSAvWgPm2hzroQzMHe3BxGsSj0t9+9F5GdBtZdnG+/i8iBcOvmk+r7p0xkWuH7qaZmcGqNrX9m31FnZMp9OWkwkCqc8yncKDJ89P810JAcVL6Fp0cBrUMACkNAH5nIpDxAfV1mDp0FW8mDYuW1qnNxg0zdPfWX"
    }
}
