package com.vayunmathur.appstore.data.accrescent

import app.accrescent.appstore.v1.AppDownloadInfo
import app.accrescent.appstore.v1.AppListing
import app.accrescent.appstore.v1.AppUpdateInfo
import app.accrescent.appstore.v1.DeviceAttributes
import app.accrescent.appstore.v1.GetAppDownloadInfoResponse
import app.accrescent.appstore.v1.GetAppListingRequest
import app.accrescent.appstore.v1.GetAppListingResponse
import app.accrescent.appstore.v1.GetAppPackageInfoRequest
import app.accrescent.appstore.v1.GetAppUpdateInfoResponse
import app.accrescent.appstore.v1.Image
import app.accrescent.appstore.v1.ListAppListingsRequest
import app.accrescent.appstore.v1.PackageInfo
import app.accrescent.appstore.v1.SplitDownloadInfo
import app.accrescent.appstore.v1.SplitUpdateInfo
import com.android.bundle.DeviceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wire round trips for the remaining Accrescent and bundletool
 * messages: every field the client sets survives serialization, and
 * proto3 `optional` presence (present-but-empty vs absent) is preserved
 * because the API layer distinguishes the two.
 */
class ProtoRoundTripTest {

    @Test
    fun `device spec carries every split-selection field`() {
        val spec = DeviceSpec.newBuilder()
            .addSupportedAbis("arm64-v8a")
            .addSupportedAbis("armeabi-v7a")
            .addSupportedLocales("en-US")
            .addSupportedLocales("de-DE")
            .addDeviceFeatures("android.hardware.touchscreen")
            .addGlExtensions("GL_OES_test")
            .setScreenDensity(420)
            .setSdkVersion(34)
            .setCodename("REL")
            .setRamBytes(8_000_000_000L)
            .setBuildBrand("google")
            .setBuildDevice("pixel")
            .setSocManufacturer("Google")
            .setSocModel("Tensor")
            .build()
        val parsed = DeviceSpec.parseFrom(spec.toByteArray())
        assertEquals(spec, parsed)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), parsed.supportedAbisList)
        assertEquals(listOf("en-US", "de-DE"), parsed.supportedLocalesList)
        assertEquals(420, parsed.screenDensity)
        assertEquals(34, parsed.sdkVersion)
        assertEquals(8_000_000_000L, parsed.ramBytes)
        assertEquals("Tensor", parsed.socModel)

        val empty = DeviceSpec.getDefaultInstance()
        assertEquals(0, empty.screenDensity)
        assertTrue(empty.supportedAbisList.isEmpty())
        assertEquals("", empty.buildBrand)
    }

    @Test
    fun `device attributes wraps the spec with presence`() {
        assertFalse(DeviceAttributes.getDefaultInstance().hasSpec())
        val attrs = DeviceAttributes.newBuilder()
            .setSpec(DeviceSpec.newBuilder().setSdkVersion(35))
            .build()
        val parsed = DeviceAttributes.parseFrom(attrs.toByteArray())
        assertTrue(parsed.hasSpec())
        assertEquals(35, parsed.spec.sdkVersion)
    }

    @Test
    fun `listing request and response wrappers round trip`() {
        val request = GetAppListingRequest.newBuilder()
            .setAppId("app.example")
            .addPreferredLanguages("en-US")
            .addPreferredLanguages("fr-FR")
            .build()
        val parsedRequest = GetAppListingRequest.parseFrom(request.toByteArray())
        assertEquals(request, parsedRequest)
        assertTrue(parsedRequest.hasAppId())
        assertFalse(GetAppListingRequest.getDefaultInstance().hasAppId())

        val listing = AppListing.newBuilder()
            .setAppId("app.example")
            .setLanguage("en-US")
            .setName("Example")
            .setShortDescription("Short")
            .setIcon(Image.newBuilder().setUrl("https://example.org/i.png"))
            .build()
        val response = GetAppListingResponse.newBuilder().setListing(listing).build()
        val parsed = GetAppListingResponse.parseFrom(response.toByteArray())
        assertTrue(parsed.hasListing())
        assertEquals("Example", parsed.listing.name)
        assertEquals("Short", parsed.listing.shortDescription)
        assertFalse(GetAppListingResponse.getDefaultInstance().hasListing())
        assertFalse(AppListing.getDefaultInstance().hasLanguage())
    }

    @Test
    fun `package info request and download wrappers round trip`() {
        val request = GetAppPackageInfoRequest.newBuilder().setAppId("app.example").build()
        assertEquals(request, GetAppPackageInfoRequest.parseFrom(request.toByteArray()))
        assertTrue(request.hasAppId())

        val download = GetAppDownloadInfoResponse.newBuilder()
            .setAppDownloadInfo(
                AppDownloadInfo.newBuilder()
                    .addSplitDownloadInfo(
                        SplitDownloadInfo.newBuilder()
                            .setUrl("https://example.org/a.apk").setDownloadSize(10),
                    )
                    .addSplitDownloadInfo(
                        SplitDownloadInfo.newBuilder()
                            .setUrl("https://example.org/b.apk").setDownloadSize(20),
                    ),
            ).build()
        val parsed = GetAppDownloadInfoResponse.parseFrom(download.toByteArray())
        assertTrue(parsed.hasAppDownloadInfo())
        assertEquals(
            listOf(10, 20),
            parsed.appDownloadInfo.splitDownloadInfoList.map { it.downloadSize },
        )
        assertFalse(GetAppDownloadInfoResponse.getDefaultInstance().hasAppDownloadInfo())
        assertFalse(SplitDownloadInfo.getDefaultInstance().hasUrl())
    }

    @Test
    fun `update wrapper and list request skip field round trip`() {
        val update = GetAppUpdateInfoResponse.newBuilder()
            .setAppUpdateInfo(
                AppUpdateInfo.newBuilder().addSplitUpdateInfo(
                    SplitUpdateInfo.newBuilder()
                        .setApkUrl("https://example.org/u.apk").setApkDownloadSize(99),
                ),
            ).build()
        val parsed = GetAppUpdateInfoResponse.parseFrom(update.toByteArray())
        assertEquals(99, parsed.appUpdateInfo.splitUpdateInfoList.single().apkDownloadSize)
        assertTrue(SplitUpdateInfo.getDefaultInstance().apkUrl.isEmpty())

        val list = ListAppListingsRequest.newBuilder()
            .setPageSize(25)
            .setSkip(50)
            .setPageToken("tok")
            .build()
        val parsedList = ListAppListingsRequest.parseFrom(list.toByteArray())
        assertEquals(25, parsedList.pageSize)
        assertTrue(parsedList.hasSkip())
        assertEquals(50, parsedList.skip)
        assertFalse(ListAppListingsRequest.getDefaultInstance().hasPageSize())

        val pkg = PackageInfo.newBuilder().setVersionCode(123L).setVersionName("2.0").build()
        assertEquals(pkg, PackageInfo.parseFrom(pkg.toByteArray()))
        assertTrue(PackageInfo.getDefaultInstance().versionName.isEmpty())
        assertFalse(PackageInfo.getDefaultInstance().hasVersionCode())
    }
}
