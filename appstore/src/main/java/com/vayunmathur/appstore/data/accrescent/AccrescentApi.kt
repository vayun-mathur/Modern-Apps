package com.vayunmathur.appstore.data.accrescent

import app.accrescent.appstore.v1.AppDownloadInfo
import app.accrescent.appstore.v1.AppListing
import app.accrescent.appstore.v1.AppServiceGrpcKt
import app.accrescent.appstore.v1.AppUpdateInfo
import app.accrescent.appstore.v1.GetAppDownloadInfoRequest
import app.accrescent.appstore.v1.GetAppListingRequest
import app.accrescent.appstore.v1.GetAppPackageInfoRequest
import app.accrescent.appstore.v1.GetAppUpdateInfoRequest
import app.accrescent.appstore.v1.ListAppListingsRequest
import app.accrescent.appstore.v1.ListAppListingsResponse
import app.accrescent.appstore.v1.PackageInfo
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.okhttp.OkHttpChannelBuilder
import java.util.concurrent.TimeUnit

/** The device this app runs on cannot install the requested Accrescent app (FAILED_PRECONDITION). */
class IncompatibleDeviceException(message: String) : Exception(message)

/** Accrescent's API does not list the requested app id (NOT_FOUND). */
class AccrescentNotFoundException(message: String) : Exception(message)

/**
 * Thin coroutine wrapper over Accrescent's gRPC `AppService`.
 *
 * The channel talks to [AccrescentRepo.APP_STORE_API_DOMAIN] over TLS on 443. TLS here is not
 * the security boundary — the ed25519-signed repodata and the on-device APK signature +
 * min-version checks are — so it uses system trust via grpc-okhttp rather than a pinned factory.
 * This is the first okhttp/grpc surface in the store; everything else uses HttpURLConnection.
 *
 * gRPC status codes are mapped to intent-revealing exceptions so callers can distinguish
 * "incompatible device" and "no such app" from a generic failure.
 */
class AccrescentApi(
    private val channelFactory: () -> ManagedChannel = {
        OkHttpChannelBuilder
            .forAddress(AccrescentRepo.APP_STORE_API_DOMAIN, HTTPS_PORT)
            .useTransportSecurity()
            .build()
    },
) {

    @Volatile
    private var channelRef: ManagedChannel? = null

    private companion object {
        private const val HTTPS_PORT = 443
    }

    private fun stub(): AppServiceGrpcKt.AppServiceCoroutineStub =
        AppServiceGrpcKt.AppServiceCoroutineStub(channel())

    private fun channel(): ManagedChannel {
        channelRef?.let { if (!it.isShutdown && !it.isTerminated) return it }
        return synchronized(this) {
            channelRef?.let { if (!it.isShutdown && !it.isTerminated) return it }
            channelFactory().also { channelRef = it }
        }
    }

    suspend fun listAppListings(
        pageSize: Int,
        pageToken: String,
        preferredLanguages: List<String>,
    ): ListAppListingsResponse = call {
        val request = ListAppListingsRequest.newBuilder()
            .setPageSize(pageSize)
            .addAllPreferredLanguages(preferredLanguages)
        // page_token is a proto3 `optional` field: setting it to "" marks it present, and the
        // server rejects an empty-but-present token ("provided page token is invalid"). Only set
        // it for continuation requests so the initial request omits it entirely.
        if (pageToken.isNotEmpty()) request.pageToken = pageToken
        stub().listAppListings(request.build())
    }

    suspend fun getAppListing(appId: String, preferredLanguages: List<String>): AppListing = call {
        stub().getAppListing(
            GetAppListingRequest.newBuilder()
                .setAppId(appId)
                .addAllPreferredLanguages(preferredLanguages)
                .build()
        ).listing
    }

    suspend fun getAppPackageInfo(appId: String): PackageInfo = call {
        stub().getAppPackageInfo(
            GetAppPackageInfoRequest.newBuilder().setAppId(appId).build()
        ).packageInfo
    }

    suspend fun getAppDownloadInfo(
        appId: String,
        deviceAttributes: app.accrescent.appstore.v1.DeviceAttributes,
    ): AppDownloadInfo = call {
        stub().getAppDownloadInfo(
            GetAppDownloadInfoRequest.newBuilder()
                .setAppId(appId)
                .setDeviceAttributes(deviceAttributes)
                .build()
        ).appDownloadInfo
    }

    /** Null when the app is up to date (the response's update-info field is absent). */
    suspend fun getAppUpdateInfo(
        appId: String,
        deviceAttributes: app.accrescent.appstore.v1.DeviceAttributes,
        baseVersionCode: Long,
    ): AppUpdateInfo? = call {
        val response = stub().getAppUpdateInfo(
            GetAppUpdateInfoRequest.newBuilder()
                .setAppId(appId)
                .setDeviceAttributes(deviceAttributes)
                .setBaseVersionCode(baseVersionCode)
                .build()
        )
        if (response.hasAppUpdateInfo()) response.appUpdateInfo else null
    }

    fun shutdown() {
        runCatching { channelRef?.shutdown()?.awaitTermination(2, TimeUnit.SECONDS) }
        channelRef = null
    }

    private suspend inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: StatusException) {
        throw mapStatus(e.status, e)
    } catch (e: StatusRuntimeException) {
        throw mapStatus(e.status, e)
    }

    private fun mapStatus(status: Status, cause: Exception): Exception = when (status.code) {
        Status.Code.FAILED_PRECONDITION ->
            IncompatibleDeviceException(status.description ?: "device is not compatible")
        Status.Code.NOT_FOUND ->
            AccrescentNotFoundException(status.description ?: "app not found")
        else -> cause
    }
}
