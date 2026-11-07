package com.vayunmathur.appstore.data.installer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.accrescent.appstore.v1.AppDownloadInfo
import app.accrescent.appstore.v1.AppServiceGrpcKt
import app.accrescent.appstore.v1.GetAppDownloadInfoRequest
import app.accrescent.appstore.v1.GetAppDownloadInfoResponse
import app.accrescent.appstore.v1.SplitDownloadInfo
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.appstore.data.AccrescentTrustEntity
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.DownloadCache
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.accrescent.AccrescentApi
import com.vayunmathur.appstore.data.accrescent.AccrescentRepository
import com.vayunmathur.appstore.data.play.PlayRepository
import com.vayunmathur.appstore.data.security.VerificationResult
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
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
 * Accrescent installs through InstallCoordinator against a fake gRPC
 * service and a loopback split server: no trust anchor refuses before
 * any download, an incompatible device and an empty split list refuse
 * with their own reasons, a 404 split fails the row, and a split whose
 * archive signer matches the signed allowlist verifies and commits.
 */
@RunWith(RobolectricTestRunner::class)
class InstallCoordinatorAccrescentTest {

    private class FakeService(var splitUrl: String) :
        AppServiceGrpcKt.AppServiceCoroutineImplBase() {
        override suspend fun getAppDownloadInfo(
            request: GetAppDownloadInfoRequest,
        ): GetAppDownloadInfoResponse {
            if (request.appId == "app.incompatible") {
                throw StatusException(Status.FAILED_PRECONDITION.withDescription("no build"))
            }
            if (request.appId == "app.empty") {
                return GetAppDownloadInfoResponse.newBuilder()
                    .setAppDownloadInfo(AppDownloadInfo.getDefaultInstance())
                    .build()
            }
            return GetAppDownloadInfoResponse.newBuilder()
                .setAppDownloadInfo(
                    AppDownloadInfo.newBuilder().addSplitDownloadInfo(
                        SplitDownloadInfo.newBuilder().setUrl(splitUrl).setDownloadSize(0).build(),
                    ),
                ).build()
        }
    }

    private lateinit var http: HttpServer
    private lateinit var grpcServer: Server
    private lateinit var channel: ManagedChannel
    private lateinit var service: FakeService
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: InstallCoordinator
    private lateinit var context: Context

    private val bytes = "accrescent-split-bytes".toByteArray()

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/split.apk") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()

        service = FakeService("http://127.0.0.1:${http.address.port}/split.apk")
        val name = InProcessServerBuilder.generateName()
        grpcServer = InProcessServerBuilder.forName(name)
            .directExecutor().addService(service).build().start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(Dispatchers.Unconfined)
        coordinator = InstallCoordinator(
            context = context,
            db = db,
            play = PlayRepository(context),
            accrescent = AccrescentRepository(context, db, AccrescentApi { channel }),
            scope = scope,
            ownSigningCertificates = { emptySet() },
        )
    }

    @AfterTest
    fun tearDown() {
        channel.shutdownNow()
        grpcServer.shutdownNow()
        http.stop(0)
        scope.cancel()
        db.close()
    }

    private fun app(id: String) = UnifiedApp(
        packageName = id,
        source = AppSource.ACCRESCENT,
        name = id,
    )

    private fun certHash(): String {
        val der = android.util.Base64.decode(CERT_A, android.util.Base64.DEFAULT)
        return MessageDigest.getInstance("SHA-256").digest(der)
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun trust(id: String) {
        db.accrescentTrustDao().upsertAll(
            listOf(AccrescentTrustEntity(id, certHash(), 1L)),
        )
    }

    @Test
    fun `no trust anchor refuses before downloading`() = runBlocking {
        val outcome = coordinator.install(app("app.untrusted"))
        assertFalse(outcome.started)
        val reason = (outcome.verification as VerificationResult.Rejected).reason
        assertTrue(reason.contains("does not vouch"), reason)
        assertTrue(coordinator.stages.value["app.untrusted"] is InstallStage.Failed)
    }

    @Test
    fun `incompatible device refuses with its reason`() = runBlocking {
        trust("app.incompatible")
        val outcome = coordinator.install(app("app.incompatible"))
        assertFalse(outcome.started)
        val reason = (outcome.verification as VerificationResult.Rejected).reason
        assertTrue(reason.contains("no build for your device"), reason)
    }

    @Test
    fun `empty split list refuses`() = runBlocking {
        trust("app.empty")
        val outcome = coordinator.install(app("app.empty"))
        assertFalse(outcome.started)
        val reason = (outcome.verification as VerificationResult.Rejected).reason
        assertTrue(reason.contains("no files to download"), reason)
    }

    @Test
    fun `a split that 404s fails the row`() = runBlocking {
        trust("app.missing-file")
        service.splitUrl = "http://127.0.0.1:${http.address.port}/nope.apk"
        val outcome = coordinator.install(app("app.missing-file"))
        assertFalse(outcome.started)
        assertTrue(coordinator.stages.value["app.missing-file"] is InstallStage.Failed)
    }

    @Test
    fun `a split matching the allowlist signer verifies and commits`() = runBlocking {
        val pkg = "app.verified"
        trust(pkg)
        service.splitUrl = "http://127.0.0.1:${http.address.port}/split.apk"
        val staged = File(context.cacheDir, DownloadCache.splitApkName(pkg, 0))
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

        val outcome = coordinator.install(app(pkg))
        assertTrue(outcome.started, "install did not start: ${outcome.verification}")
        assertEquals(InstallStage.Installing, coordinator.stages.value[pkg])
    }

    private companion object {
        const val CERT_A = "MIICvTCCAaWgAwIBAgIED3lFmDANBgkqhkiG9w0BAQsFADAPMQ0wCwYDVQQDEwRUZXN0MB4XDTI2MTEwNzAyNTAyNFoXDTM2MTEwNDAyNTAyNFowDzENMAsGA1UEAxMEVGVzdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALS7Wkz9gUndEJscrbEZgQHPQh7aVHnWFIXpw7yMZpLlI7QUBg8x8jZPJeHbWJXws/IW66dod0/QmqrG4y21i1KIQW5xwIPo3s3Igrs9ZnO05kd9Um9sgrcagAd4CjnUI0Um10AIogP6Bz6rUFL8WGDNvACq22bJDRaKVVIno7FuwgGQY+7Jrn73B4rQjyOM+PJtAnxNGBcDUVXa6CCGhU9DT/eYCtnjKlOVF8Pmflwe4LlrfYRqq7wJm2d+I6cZHwMX2coH9BwymuRCT4s6NnYkQZVYAvy8BFVI3jvK+Q259oDKDDZBLp+fHs9cUHJQ2TwnKH5rFH7tYXIwvkHgG5ECAwEAAaMhMB8wHQYDVR0OBBYEFM8ej83GSYbEXAs4JDW/shyQY5x1MA0GCSqGSIb3DQEBCwUAA4IBAQCboZxi2u+lovXniEQCy31XZhU+vKXr+u1lyHKREgy88rcDrXquapNJX0XpaJBSLFEc180j3Fr6vWst0JynJxjq7dFHqf7wDSLJQDysXEDEUr2+H6Edkjbw8Lju88uXrD7v7BgHSxsn1ffZ02FfzFKF6zC4M7pp2FkJj/1CdtW4JbfcH2RWqht8k7YSAvWgPm2hzroQzMHe3BxGsSj0t9+9F5GdBtZdnG+/i8iBcOvmk+r7p0xkWuH7qaZmcGqNrX9m31FnZMp9OWkwkCqc8yncKDJ89P810JAcVL6Fp0cBrUMACkNAH5nIpDxAfV1mDp0FW8mDYuW1qnNxg0zdPfWX"
    }
}
