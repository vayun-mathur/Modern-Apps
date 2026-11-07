package com.vayunmathur.auto.protocol

import com.google.protobuf.ByteString
import com.vayunmathur.auto.protocol.gal.AudioConfiguration
import com.vayunmathur.auto.protocol.gal.BluetoothService
import com.vayunmathur.auto.protocol.gal.InputSourceService
import com.vayunmathur.auto.protocol.gal.MediaCodecType
import com.vayunmathur.auto.protocol.gal.MediaSinkService
import com.vayunmathur.auto.protocol.gal.MediaSourceService
import com.vayunmathur.auto.protocol.gal.NavigationStatusService
import com.vayunmathur.auto.protocol.gal.Sensor
import com.vayunmathur.auto.protocol.gal.SensorSourceService
import com.vayunmathur.auto.protocol.gal.Service
import com.vayunmathur.auto.protocol.gal.ServiceDiscoveryRequest
import com.vayunmathur.auto.protocol.gal.ServiceDiscoveryResponse
import com.vayunmathur.auto.protocol.gal.TouchConfig
import com.vayunmathur.auto.protocol.gal.VendorExtensionService
import com.vayunmathur.auto.protocol.gal.VideoConfiguration
import com.vayunmathur.auto.protocol.gal.VideoResolution
import com.vayunmathur.auto.protocol.gal.WifiProjectionService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Builds one fully populated [Service] per service payload type inside a
 * discovery response, and pins that the whole structure survives a
 * serialize/parse round-trip field-for-field.
 */
class GalServicesRoundTripTest {

    private fun bytes(vararg values: Int): ByteString = ByteString.copyFrom(values.map { it.toByte() }.toByteArray())

    @Test
    fun `discovery response with every service type round-trips`() {
        val audio = AudioConfiguration.newBuilder().setSamplingRate(48_000).setNumberOfBits(16).setNumberOfChannels(2)
        val video = VideoConfiguration.newBuilder()
            .setCodecResolution(VideoResolution.VIDEO_1920x1080).setFrameRate(60)
            .setWidthMargin(4).setHeightMargin(2).setDensity(320)
            .setField6(6).setField8(8).setField9(9).setField10(-10).setField11(bytes(1, 2))
        val response = ServiceDiscoveryResponse.newBuilder()
            .setHeadUnitName("Pioneer").setCarModel("Model X").setCarYear("2026").setCarSerial("SN1")
            .setDriverPosition(1).setHeadunitMake("Make").setHeadunitModel("HU-1")
            .setHeadunitSoftwareBuild("b42").setHeadunitSoftwareVersion("1.2.3")
            .setCanPlayNativeMediaDuringVr(true).setField13(13).setField14("f14")
            .setHideClock(true).setField17(bytes(17))
            .addServices(
                Service.newBuilder().setId(7).setSensorSource(
                    SensorSourceService.newBuilder()
                        .addSensors(Sensor.newBuilder().setType(1))
                        .addSensors(Sensor.newBuilder().setType(3))
                        .setLocationCharacterization(2)
                        .addAllFuelTypes(listOf(1, 2)).addAllEvConnectorTypes(listOf(3)),
                ),
            )
            .addServices(
                Service.newBuilder().setId(3).setMediaSink(
                    MediaSinkService.newBuilder()
                        .setAvailableType(MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                        .setAudioType(1).addAudioConfigs(audio).addVideoConfigs(video)
                        .setAvailableWhileInCall(1).setField7(7).setField8(8).setField9(9),
                ),
            )
            .addServices(
                Service.newBuilder().setId(4).setInputSource(
                    InputSourceService.newBuilder()
                        .addAllKeycodesSupported(listOf(24, 25))
                        .addTouchscreen(TouchConfig.newBuilder().setWidth(1920).setHeight(720).setType(1))
                        .addTouchpad(TouchConfig.newBuilder().setWidth(200).setHeight(100))
                        .addAllFeedbackEventsSupported(listOf(1)).setField5(5),
                ),
            )
            .addServices(
                Service.newBuilder().setId(5).setMediaSource(
                    MediaSourceService.newBuilder().setAvailableType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM).setAudioConfig(audio),
                ),
            )
            .addServices(
                Service.newBuilder().setId(6).setBluetooth(
                    BluetoothService.newBuilder().setCarAddress("AA:BB:CC:DD:EE:FF").addAllSupportedPairingMethods(listOf(1, 2)),
                ),
            )
            .addServices(
                Service.newBuilder().setId(8)
                    .setRadio(bytes(1))
                    .setNavigationStatus(
                        NavigationStatusService.newBuilder().setMinimumIntervalMs(500).setType(2).setImageOptions(bytes(3, 4)),
                    ),
            )
            .addServices(
                Service.newBuilder().setId(12)
                    .setMediaPlaybackStatus(bytes(9)).setPhoneStatus(bytes(10)).setMediaBrowser(bytes(11))
                    .setVendorExtension(
                        VendorExtensionService.newBuilder().setName("vendor")
                            .addPackageAllowlist("com.example.a").addPackageAllowlist("com.example.b")
                            .setData(bytes(5)),
                    )
                    .setNotification(bytes(13)),
            )
            .addServices(
                Service.newBuilder().setId(14)
                    .setWifiProjection(WifiProjectionService.newBuilder().setCarWifiBssid("00:11:22:33:44:55"))
                    .setField15(bytes(15)),
            )
            .build()

        val parsed = ServiceDiscoveryResponse.parseFrom(response.toByteArray())
        assertEquals(response, parsed)
        assertEquals(8, parsed.servicesCount)
        assertEquals("Pioneer", parsed.headUnitName)
        assertTrue(parsed.hideClock)
        val sensor = parsed.getServices(0)
        assertEquals(listOf(1, 3), sensor.sensorSource.sensorsList.map { it.type })
        val sink = parsed.getServices(1).mediaSink
        assertEquals(48_000, sink.getAudioConfigs(0).samplingRate)
        assertEquals(VideoResolution.VIDEO_1920x1080, sink.getVideoConfigs(0).codecResolution)
        assertEquals(-10, sink.getVideoConfigs(0).field10)
        val input = parsed.getServices(2).inputSource
        assertEquals(listOf(24, 25), input.keycodesSupportedList)
        assertEquals(1920, input.getTouchscreen(0).width)
        assertEquals("AA:BB:CC:DD:EE:FF", parsed.getServices(4).bluetooth.carAddress)
        assertEquals(500, parsed.getServices(5).navigationStatus.minimumIntervalMs)
        assertEquals(listOf("com.example.a", "com.example.b"), parsed.getServices(6).vendorExtension.packageAllowlistList)
        assertEquals("00:11:22:33:44:55", parsed.getServices(7).wifiProjection.carWifiBssid)
        // Rebuilding from the parsed message yields identical bytes.
        val rebuilt = ServiceDiscoveryResponse.newBuilder().mergeFrom(parsed).build()
        assertTrue(response.toByteArray().contentEquals(rebuilt.toByteArray()))
    }

    @Test
    fun `discovery request with all fields round-trips`() {
        val request = ServiceDiscoveryRequest.newBuilder()
            .setField1(bytes(1)).setField2(bytes(2)).setField3(bytes(3))
            .setDeviceName("Pixel").setDeviceBrand("Google").setField6(bytes(6))
            .build()
        val parsed = ServiceDiscoveryRequest.parseFrom(request.toByteArray())
        assertEquals(request, parsed)
        assertEquals("Pixel", parsed.deviceName)
        assertEquals("Google", parsed.deviceBrand)
        assertTrue(parsed.hasField1() && parsed.hasField6())
    }

    @Test
    fun `empty discovery response and defaults`() {
        val empty = ServiceDiscoveryResponse.getDefaultInstance()
        assertEquals(0, empty.servicesCount)
        assertEquals("", empty.headUnitName)
        val parsed = ServiceDiscoveryResponse.parseFrom(empty.toByteArray())
        assertEquals(empty, parsed)
        // A service carrying only its required id still builds and parses.
        val bare = Service.newBuilder().setId(99).build()
        assertEquals(99, Service.parseFrom(bare.toByteArray()).id)
    }
}
