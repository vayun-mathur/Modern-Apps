package com.vayunmathur.share.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareNativeDiscoveryTest {

    @Test
    fun `mdns service type and ble hash match NearbySharing derivation`() {
        assertEquals("_FC9F5ED42C8A._tcp", ShareNativeDiscovery.nativeMdnsServiceType())
        val hash = ShareNativeDiscovery.nativeBleServiceIdHash()!!
        assertEquals(3, hash.size)
        assertEquals(0xFC.toByte(), hash[0]); assertEquals(0x9F.toByte(), hash[1]); assertEquals(0x5E.toByte(), hash[2])
    }

    @Test
    fun `endpoint info round trips`() {
        val blob = ShareNativeDiscovery.nativeBuildEndpointInfo("Pixel 9", ShareNative.DEVICE_TYPE_PHONE)!!
        val parsed = ShareNativeDiscovery.parseEndpointInfo(blob)!!
        assertEquals("Pixel 9", parsed.deviceName)
        assertEquals(ShareNative.DEVICE_TYPE_PHONE, parsed.deviceType)
        assertNull(ShareNativeDiscovery.nativeBuildEndpointInfo("", 1))
        assertNull(ShareNativeDiscovery.parseEndpointInfo(byteArrayOf(1, 2, 3)))
        assertNull(ShareNativeDiscovery.parseEndpointInfo(ByteArray(0)))
    }

    @Test
    fun `wifi lan service info requires 4 ascii chars`() {
        val raw = ShareNativeDiscovery.nativeBuildWifiLanServiceInfo("ABCD")!!
        assertEquals(8, raw.size)
        val parsed = ShareNativeDiscovery.parseWifiLanServiceInfo(raw)!!
        assertEquals("ABCD", parsed.endpointId)
        assertNull(ShareNativeDiscovery.nativeBuildWifiLanServiceInfo("ABC"))
        assertNull(ShareNativeDiscovery.nativeBuildWifiLanServiceInfo("ABCDE"))
        assertNull(ShareNativeDiscovery.nativeBuildWifiLanServiceInfo("ABéD"))
        assertNull(ShareNativeDiscovery.parseWifiLanServiceInfo(byteArrayOf(1, 2)))
    }

    @Test
    fun `ble advertisement round trips and rejects foreign data`() {
        val payload = byteArrayOf(9, 8, 7, 6, 5)
        val adv = ShareNativeDiscovery.nativeBuildBleAdvertisement(payload, ByteArray(0), false)!!
        assertTrue(adv.isNotEmpty())
        val back = ShareNativeDiscovery.nativeParseBleAdvertisement(adv)!!
        assertTrue(payload.contentEquals(back))
        assertNull(ShareNativeDiscovery.nativeParseBleAdvertisement(byteArrayOf(0, 1, 2, 3)))
        // oversized fast payload does not fit
        val big = ByteArray(200) { 1 }
        assertNull(ShareNativeDiscovery.nativeBuildBleAdvertisement(big, ByteArray(0), true))
        // bad device token length
        assertNull(ShareNativeDiscovery.nativeBuildBleAdvertisement(payload, ByteArray(3), false))
    }

    @Test
    fun `ble endpoint payload carries endpoint id and info`() {
        val info = ShareNativeDiscovery.nativeBuildEndpointInfo("My Phone", 1)!!
        val payload = ShareNativeDiscovery.nativeBuildBleEndpointPayload("WXYZ", info)!!
        assertEquals("WXYZ", ShareNativeDiscovery.nativeParseBleEndpointId(payload))
        val inner = ShareNativeDiscovery.nativeParseBleEndpointInfo(payload)!!
        assertTrue(info.contentEquals(inner))
        assertNull(ShareNativeDiscovery.nativeBuildBleEndpointPayload("XYZ", info))
        assertNull(ShareNativeDiscovery.nativeParseBleEndpointInfo(byteArrayOf(1, 2, 3)))
        assertNull(ShareNativeDiscovery.nativeParseBleEndpointId(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `fast initiation service data prefixes FC128E`() {
        val data = ShareNativeDiscovery.nativeFastInitiationServiceData(byteArrayOf(1, 2))!!
        assertEquals(0xFC.toByte(), data[0]); assertEquals(0x12.toByte(), data[1]); assertEquals(0x8E.toByte(), data[2])
        assertNull(ShareNativeDiscovery.nativeFastInitiationServiceData(byteArrayOf(1)))
        assertNull(ShareNativeDiscovery.nativeFastInitiationServiceData(ByteArray(0)))
    }
}
