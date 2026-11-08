package com.vayunmathur.appstore.data.accrescent

import app.accrescent.appstore.v1.AppDownloadInfo
import app.accrescent.appstore.v1.AppListing
import app.accrescent.appstore.v1.AppUpdateInfo
import app.accrescent.appstore.v1.GetAppDownloadInfoRequest
import app.accrescent.appstore.v1.GetAppPackageInfoResponse
import app.accrescent.appstore.v1.GetAppUpdateInfoRequest
import app.accrescent.appstore.v1.GetAppUpdateInfoResponse
import app.accrescent.appstore.v1.Image
import app.accrescent.appstore.v1.ListAppListingsRequest
import app.accrescent.appstore.v1.ListAppListingsResponse
import app.accrescent.appstore.v1.PackageInfo
import app.accrescent.appstore.v1.SplitDownloadInfo
import app.accrescent.appstore.v1.SplitUpdateInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Accrescent wire contract the client code is written against:
 * proto3 `optional` presence (an empty-but-present page token is a
 * different request — see AccrescentApi), wrapper-response presence
 * signalling "update available", and lossless round trips of the
 * messages the repository maps into listings and split downloads.
 */
class AccrescentProtoTest {

    @Test
    fun `page token presence distinguishes a first page from a continuation`() {
        val first = ListAppListingsRequest.newBuilder().setPageSize(50).build()
        assertFalse(first.hasPageToken())
        assertFalse(first.hasSkip())

        val next = ListAppListingsRequest.newBuilder()
            .setPageSize(50)
            .setPageToken("token-2")
            .addPreferredLanguages("en-US")
            .build()
        assertTrue(next.hasPageToken())
        assertEquals("token-2", next.pageToken)
        assertEquals(listOf("en-US"), next.preferredLanguagesList)

        // Even an empty token is *present* once set — which is why the
        // API layer only sets it for continuation requests.
        val emptyToken = ListAppListingsRequest.newBuilder().setPageToken("").build()
        assertTrue(emptyToken.hasPageToken())
    }

    @Test
    fun `listing round trips with its icon and localized fields`() {
        val listing = AppListing.newBuilder()
            .setAppId("app.example")
            .setLanguage("en-US")
            .setName("Example")
            .setShortDescription("An example app")
            .setIcon(Image.newBuilder().setUrl("https://example.org/icon.png"))
            .build()
        val parsed = AppListing.parseFrom(listing.toByteArray())
        assertEquals(listing, parsed)
        assertEquals("app.example", parsed.appId)
        assertTrue(parsed.hasIcon())
        assertEquals("https://example.org/icon.png", parsed.icon.url)

        val bare = AppListing.getDefaultInstance()
        assertFalse(bare.hasAppId())
        assertFalse(bare.hasIcon())
    }

    @Test
    fun `update presence on the wrapper is the up-to-date signal`() {
        assertFalse(GetAppUpdateInfoResponse.getDefaultInstance().hasAppUpdateInfo())

        val response = GetAppUpdateInfoResponse.newBuilder()
            .setAppUpdateInfo(
                AppUpdateInfo.newBuilder().addSplitUpdateInfo(
                    SplitUpdateInfo.newBuilder()
                        .setApkUrl("https://example.org/base.apk")
                        .setApkDownloadSize(1234)
                        .build(),
                ),
            )
            .build()
        assertTrue(response.hasAppUpdateInfo())
        val split = response.appUpdateInfo.splitUpdateInfoList.single()
        assertEquals("https://example.org/base.apk", split.apkUrl)
        assertEquals(1234, split.apkDownloadSize)
    }

    @Test
    fun `download and package info messages round trip`() {
        val download = AppDownloadInfo.newBuilder()
            .addSplitDownloadInfo(
                SplitDownloadInfo.newBuilder()
                    .setUrl("https://example.org/split.apk")
                    .setDownloadSize(77)
                    .build(),
            )
            .build()
        assertEquals(download, AppDownloadInfo.parseFrom(download.toByteArray()))
        assertEquals(77, download.splitDownloadInfoList.single().downloadSize)

        val pkg = PackageInfo.newBuilder().setVersionCode(42L).setVersionName("1.2").build()
        val wrapped = GetAppPackageInfoResponse.newBuilder().setPackageInfo(pkg).build()
        assertTrue(wrapped.hasPackageInfo())
        assertEquals(42L, wrapped.packageInfo.versionCode)
        assertEquals("1.2", wrapped.packageInfo.versionName)
        assertFalse(GetAppPackageInfoResponse.getDefaultInstance().hasPackageInfo())
    }

    @Test
    fun `listings response carries the next page token presence`() {
        val page = ListAppListingsResponse.newBuilder()
            .addListings(AppListing.newBuilder().setAppId("app.one"))
            .setNextPageToken("more")
            .build()
        assertTrue(page.hasNextPageToken())
        assertEquals(listOf("app.one"), page.listingsList.map { it.appId })

        val last = ListAppListingsResponse.newBuilder()
            .addListings(AppListing.newBuilder().setAppId("app.one"))
            .build()
        assertFalse(last.hasNextPageToken())
        assertEquals("", last.nextPageToken)
    }

    @Test
    fun `device attributed requests carry their fields`() {
        val downloadReq = GetAppDownloadInfoRequest.newBuilder()
            .setAppId("app.example")
            .build()
        assertTrue(downloadReq.hasAppId())
        assertFalse(downloadReq.hasDeviceAttributes())

        val updateReq = GetAppUpdateInfoRequest.newBuilder()
            .setAppId("app.example")
            .setBaseVersionCode(9L)
            .build()
        assertTrue(updateReq.hasBaseVersionCode())
        assertEquals(9L, updateReq.baseVersionCode)
    }
}
