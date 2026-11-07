package com.vayunmathur.appstore.data.accrescent

import app.accrescent.appstore.v1.AppListing
import app.accrescent.appstore.v1.DeviceAttributes
import app.accrescent.appstore.v1.GetAppListingRequest
import app.accrescent.appstore.v1.GetAppPackageInfoRequest
import app.accrescent.appstore.v1.GetAppUpdateInfoRequest
import app.accrescent.appstore.v1.Image
import app.accrescent.appstore.v1.ListAppListingsRequest
import app.accrescent.appstore.v1.ListAppListingsResponse
import com.android.bundle.DeviceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Builder mutation semantics for the vendored messages: clearing a
 * proto3 `optional` field removes its presence, repeated fields can be
 * replaced wholesale, and merging a parsed message into a builder
 * reproduces it — the operations any future client code (pagination
 * reuse, request rewriting) would rely on.
 */
class ProtoBuilderMutationTest {

    @Test
    fun `clearing optional fields removes presence`() {
        val listing = AppListing.newBuilder()
            .setAppId("app.x").setName("X").setLanguage("en")
            .setIcon(Image.newBuilder().setUrl("https://example.org/x.png"))
            .clearAppId().clearIcon()
            .build()
        assertFalse(listing.hasAppId())
        assertFalse(listing.hasIcon())
        assertEquals("X", listing.name)
        assertTrue(listing.hasName())

        val request = ListAppListingsRequest.newBuilder()
            .setPageToken("tok").setPageSize(10).clearPageToken()
            .build()
        assertFalse(request.hasPageToken())
        assertTrue(request.hasPageSize())
    }

    @Test
    fun `repeated fields replace and extend`() {
        val spec = DeviceSpec.newBuilder()
            .addAllSupportedAbis(listOf("arm64-v8a"))
            .addSupportedAbis("armeabi-v7a")
            .clearSupportedAbis()
            .addAllSupportedAbis(listOf("x86_64"))
            .addAllDeviceFeatures(listOf("android.hardware.touchscreen"))
            .build()
        assertEquals(listOf("x86_64"), spec.supportedAbisList)
        assertEquals(listOf("android.hardware.touchscreen"), spec.deviceFeaturesList)

        val request = GetAppListingRequest.newBuilder()
            .addAllPreferredLanguages(listOf("en", "de"))
            .addPreferredLanguages("fr")
            .build()
        assertEquals(listOf("en", "de", "fr"), request.preferredLanguagesList)
    }

    @Test
    fun `mergeFrom reproduces a parsed message`() {
        val original = ListAppListingsResponse.newBuilder()
            .addListings(AppListing.newBuilder().setAppId("app.one").setName("One"))
            .setNextPageToken("more")
            .build()
        val rebuilt = ListAppListingsResponse.newBuilder()
            .mergeFrom(original.toByteArray())
            .build()
        assertEquals(original, rebuilt)

        val spec = DeviceSpec.newBuilder()
            .setSdkVersion(34).setScreenDensity(440).setBuildBrand("google")
            .build()
        val merged = DeviceSpec.newBuilder().mergeFrom(spec).build()
        assertEquals(spec, merged)
        assertEquals(440, merged.screenDensity)
    }

    @Test
    fun `builders start from the default instance`() {
        val request = GetAppUpdateInfoRequest.newBuilder()
            .setAppId("app.x")
            .build()
        val fromDefault = GetAppUpdateInfoRequest.newBuilder(request)
            .setBaseVersionCode(9L)
            .build()
        assertEquals("app.x", fromDefault.appId)
        assertEquals(9L, fromDefault.baseVersionCode)

        val pkgRequest = GetAppPackageInfoRequest.newBuilder()
            .setAppId("app.y").clearAppId()
            .build()
        assertFalse(pkgRequest.hasAppId())

        val attrs = DeviceAttributes.newBuilder()
            .setSpec(DeviceSpec.newBuilder().setSdkVersion(31))
            .clearSpec()
            .build()
        assertFalse(attrs.hasSpec())
    }
}
