package com.vayunmathur.appstore.data.accrescent

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.accrescent.appstore.v1.AppDownloadInfo
import app.accrescent.appstore.v1.AppListing
import app.accrescent.appstore.v1.AppServiceGrpcKt
import app.accrescent.appstore.v1.AppUpdateInfo
import app.accrescent.appstore.v1.GetAppDownloadInfoRequest
import app.accrescent.appstore.v1.GetAppDownloadInfoResponse
import app.accrescent.appstore.v1.GetAppListingRequest
import app.accrescent.appstore.v1.GetAppListingResponse
import app.accrescent.appstore.v1.GetAppPackageInfoRequest
import app.accrescent.appstore.v1.GetAppPackageInfoResponse
import app.accrescent.appstore.v1.GetAppUpdateInfoRequest
import app.accrescent.appstore.v1.GetAppUpdateInfoResponse
import app.accrescent.appstore.v1.Image
import app.accrescent.appstore.v1.ListAppListingsRequest
import app.accrescent.appstore.v1.ListAppListingsResponse
import app.accrescent.appstore.v1.PackageInfo
import app.accrescent.appstore.v1.SplitDownloadInfo
import app.accrescent.appstore.v1.SplitUpdateInfo
import com.vayunmathur.appstore.data.AccrescentTrustEntity
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AccrescentRepository against an in-process fake of the real gRPC
 * service: listings paginate, details merge package info and the signed
 * allowlist's signer, update/download info map their splits, and gRPC
 * statuses become the repository's typed failures / soft nulls.
 */
@RunWith(RobolectricTestRunner::class)
class AccrescentFakeServerTest {

    private class FakeService : AppServiceGrpcKt.AppServiceCoroutineImplBase() {
        var packageVersionCode = 10L
        var updateAvailable = true

        private fun listing(id: String, name: String) = AppListing.newBuilder()
            .setAppId(id)
            .setName(name)
            .setShortDescription("$name summary")
            .setIcon(Image.newBuilder().setUrl("https://example.org/$id.png"))
            .build()

        override suspend fun listAppListings(
            request: ListAppListingsRequest,
        ): ListAppListingsResponse {
            val builder = ListAppListingsResponse.newBuilder()
            return if (request.pageToken == "p2") {
                builder.addListings(listing("app.gamma", "Gamma")).build()
            } else {
                builder.addListings(listing("app.beta", "Beta"))
                    .addListings(listing("app.alpha", "Alpha"))
                    .setNextPageToken("p2")
                    .build()
            }
        }

        override suspend fun getAppListing(
            request: GetAppListingRequest,
        ): GetAppListingResponse {
            if (request.appId == "app.missing") {
                throw StatusException(Status.NOT_FOUND.withDescription("no such app"))
            }
            return GetAppListingResponse.newBuilder()
                .setListing(listing(request.appId, "Alpha"))
                .build()
        }

        override suspend fun getAppPackageInfo(
            request: GetAppPackageInfoRequest,
        ): GetAppPackageInfoResponse = GetAppPackageInfoResponse.newBuilder()
            .setPackageInfo(
                PackageInfo.newBuilder()
                    .setVersionCode(packageVersionCode)
                    .setVersionName("1.$packageVersionCode")
                    .build(),
            ).build()

        override suspend fun getAppDownloadInfo(
            request: GetAppDownloadInfoRequest,
        ): GetAppDownloadInfoResponse {
            if (request.appId == "app.incompatible") {
                throw StatusException(
                    Status.FAILED_PRECONDITION.withDescription("no build for device"),
                )
            }
            return GetAppDownloadInfoResponse.newBuilder()
                .setAppDownloadInfo(
                    AppDownloadInfo.newBuilder().addSplitDownloadInfo(
                        SplitDownloadInfo.newBuilder()
                            .setUrl("https://example.org/base.apk")
                            .setDownloadSize(123)
                            .build(),
                    ),
                ).build()
        }

        override suspend fun getAppUpdateInfo(
            request: GetAppUpdateInfoRequest,
        ): GetAppUpdateInfoResponse {
            if (!updateAvailable) return GetAppUpdateInfoResponse.getDefaultInstance()
            return GetAppUpdateInfoResponse.newBuilder()
                .setAppUpdateInfo(
                    AppUpdateInfo.newBuilder().addSplitUpdateInfo(
                        SplitUpdateInfo.newBuilder()
                            .setApkUrl("https://example.org/update.apk")
                            .setApkDownloadSize(55)
                            .build(),
                    ),
                ).build()
        }
    }

    private lateinit var db: AppDatabase
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var service: FakeService
    private lateinit var api: AccrescentApi
    private lateinit var repo: AccrescentRepository

    @BeforeTest
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        service = FakeService()
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(service)
            .build()
            .start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        api = AccrescentApi { channel }
        repo = AccrescentRepository(ctx, db, api)
    }

    @AfterTest
    fun tearDown() {
        repo.shutdown()
        channel.shutdownNow()
        server.shutdownNow()
        db.close()
    }

    @Test
    fun `listApps maps the first page and keeps its token`() = runTest {
        val page = repo.listApps()
        assertEquals(listOf("app.beta", "app.alpha"), page.apps.map { it.packageName })
        assertEquals("p2", page.nextPageToken)
        assertEquals(AppSource.ACCRESCENT, page.apps.first().source)
        assertEquals("Beta summary", page.apps.first().summary)
        assertEquals("https://example.org/app.beta.png", page.apps.first().iconUrl)
    }

    @Test
    fun `allListings paginates to the end and sorts by name`() = runTest {
        val all = repo.allListings()
        assertEquals(listOf("Alpha", "Beta", "Gamma"), all.map { it.name })
        // Second call serves the cache: same result without another page load.
        assertEquals(all, repo.allListings())
    }

    @Test
    fun `search matches name package and summary over the full catalogue`() = runTest {
        assertEquals(listOf("app.gamma"), repo.search("amm").map { it.packageName })
        assertEquals(listOf("app.beta"), repo.search("APP.BETA").map { it.packageName })
        assertEquals(listOf("app.alpha"), repo.search("alpha summary").map { it.packageName })
        assertTrue(repo.search("nonexistent").isEmpty())
    }

    @Test
    fun `details merges listing package info and trust signer`() = runTest {
        db.accrescentTrustDao().upsertAll(
            listOf(AccrescentTrustEntity("app.alpha", "AB".repeat(32), 3L)),
        )
        val details = repo.details("app.alpha")
        assertEquals("Alpha", details?.name)
        assertEquals(10L, details?.versionCode)
        assertEquals("1.10", details?.versionName)
        assertEquals(listOf("ab".repeat(32)), details?.expectedSigners?.map { it.lowercase() })
    }

    @Test
    fun `details for a missing app fails soft to null`() = runTest {
        assertNull(repo.details("app.missing"))
    }

    @Test
    fun `api maps NOT_FOUND and FAILED_PRECONDITION to typed exceptions`() = runTest {
        assertFailsWith<AccrescentNotFoundException> { api.getAppListing("app.missing", listOf("en")) }
        val attrs = DeviceAttributesProvider(
            ApplicationProvider.getApplicationContext<Context>(),
        ).deviceAttributes()
        assertFailsWith<IncompatibleDeviceException> { api.getAppDownloadInfo("app.incompatible", attrs) }
    }

    @Test
    fun `downloadInfo maps split urls and sizes`() = runTest {
        val splits = repo.downloadInfo("app.alpha")
        assertEquals(listOf(AccrescentSplit("https://example.org/base.apk", 123L)), splits)
    }

    @Test
    fun `updateInfo uses the package info version when it is newer`() = runTest {
        val update = repo.updateInfo("app.alpha", currentVersionCode = 5L)
        assertEquals(10L, update?.versionCode)
        assertEquals("1.10", update?.versionName)
        assertEquals(listOf(AccrescentSplit("https://example.org/update.apk", 55L)), update?.splits)
    }

    @Test
    fun `updateInfo falls back to current plus one when package info is not newer`() = runTest {
        service.packageVersionCode = 5L
        val update = repo.updateInfo("app.alpha", currentVersionCode = 5L)
        assertEquals(6L, update?.versionCode)
    }

    @Test
    fun `updateInfo is null when the service reports no update`() = runTest {
        service.updateAvailable = false
        assertNull(repo.updateInfo("app.alpha", currentVersionCode = 5L))
    }
}
