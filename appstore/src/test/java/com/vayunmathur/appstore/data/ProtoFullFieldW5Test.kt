package com.vayunmathur.appstore.data

import app.accrescent.appstore.v1.AppListing
import app.accrescent.appstore.v1.AppUpdateInfo
import app.accrescent.appstore.v1.GetAppUpdateInfoRequest
import app.accrescent.appstore.v1.Image
import app.accrescent.appstore.v1.ListAppListingsResponse
import app.accrescent.appstore.v1.SplitUpdateInfo
import com.android.bundle.DeviceSpec
import com.google.protobuf.MessageLite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * W5: generated proto types used on the Accrescent/Play wire — every field
 * set, serialized, parsed back and compared, so a field dropped from a
 * request would fail here rather than silently under-specifying a device.
 */
@RunWith(RobolectricTestRunner::class)
class ProtoFullFieldW5Test {

    private fun roundTrip(message: MessageLite): MessageLite {
        val klass = message.javaClass
        val parser = klass.getMethod("parseFrom", ByteArray::class.java)
        return parser.invoke(null, message.toByteArray()) as MessageLite
    }

    @Test
    fun deviceSpecWithEveryKnownFieldRoundTrips() {
        val spec = DeviceSpec.newBuilder()
            .addAllSupportedAbis(listOf("arm64-v8a", "armeabi-v7a"))
            .addSupportedLocales("en-US")
            .addSupportedLocales("fr-FR")
            .addAllDeviceFeatures(listOf("android.hardware.touchscreen", "android.hardware.leanback"))
            .setScreenDensity(420)
            .setSdkVersion(35)
            .setBuildBrand("google")
            .setBuildDevice("panther")
            .setCodename("REL")
            .setSocManufacturer("Google")
            .setSocModel("Tensor")
            .setRamBytes(8_000_000_000L)
            .build()
        val parsed = roundTrip(spec) as DeviceSpec
        assertEquals(spec, parsed)
        assertEquals(spec.hashCode(), parsed.hashCode())
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), parsed.supportedAbisList)
        assertEquals(420, parsed.screenDensity)
        assertNotEquals(spec, DeviceSpec.getDefaultInstance())
        assertTrue(spec.toString().contains("arm64-v8a"))
        // Builder mutation after build must not mutate the built message.
        val rebuilt = spec.toBuilder().setSdkVersion(36).build()
        assertEquals(35, spec.sdkVersion)
        assertEquals(36, rebuilt.sdkVersion)
    }

    @Test
    fun accrescentListingAndUpdateInfoRoundTrip() {
        val listing = AppListing.newBuilder()
            .setAppId("app.example")
            .setName("Example")
            .setShortDescription("Short summary")
            .setIcon(Image.newBuilder().setUrl("https://example.org/icon.png"))
            .build()
        assertEquals(listing, roundTrip(listing))

        val update = AppUpdateInfo.newBuilder()
            .addSplitUpdateInfo(
                SplitUpdateInfo.newBuilder()
                    .setApkUrl("https://example.org/base.apk")
                    .setApkDownloadSize(1234)
            )
            .addSplitUpdateInfo(
                SplitUpdateInfo.newBuilder()
                    .setApkUrl("https://example.org/lang.apk")
                    .setApkDownloadSize(55)
            )
            .build()
        val parsedUpdate = roundTrip(update) as AppUpdateInfo
        assertEquals(2, parsedUpdate.splitUpdateInfoCount)
        assertEquals("https://example.org/lang.apk", parsedUpdate.getSplitUpdateInfo(1).apkUrl)
        assertEquals(1234, parsedUpdate.getSplitUpdateInfo(0).apkDownloadSize)

        val response = ListAppListingsResponse.newBuilder()
            .addListings(listing)
            .setNextPageToken("token-2")
            .build()
        val parsedResponse = roundTrip(response) as ListAppListingsResponse
        assertEquals("token-2", parsedResponse.nextPageToken)
        assertEquals("app.example", parsedResponse.getListings(0).appId)

        val request = GetAppUpdateInfoRequest.newBuilder()
            .setAppId("app.example")
            .build()
        assertEquals("app.example", (roundTrip(request) as GetAppUpdateInfoRequest).appId)
    }
}
