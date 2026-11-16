package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-domain checks for WiFi QR parsing: no Android needed. */
class WifiQrTest {

    @Test
    fun wpaNetworkParses() {
        val config = parseWifiQr("WIFI:T:WPA;S:HomeNet;P:s3cret;;")
        assertEquals(
            WifiQrConfig("HomeNet", "s3cret", WifiSecurity.WPA, hidden = false),
            config,
        )
        assertEquals(WifiSuggestionPlan.WPA_PASSPHRASE, config?.suggestionPlan())
    }

    @Test
    fun wpa3AndWpa2LabelsParseAsWpa() {
        assertEquals(WifiSecurity.WPA, parseWifiQr("WIFI:T:WPA3;S:N;P:p;;")?.security)
        assertEquals(WifiSecurity.WPA, parseWifiQr("WIFI:T:WPA2;S:N;P:p;;")?.security)
        assertEquals(WifiSecurity.WPA, parseWifiQr("WIFI:T:SAE;S:N;P:p;;")?.security)
    }

    @Test
    fun openNetworkParsesWithoutPassword() {
        val config = parseWifiQr("WIFI:T:nopass;S:CoffeeShop;;")
        assertEquals(WifiQrConfig("CoffeeShop", null, WifiSecurity.OPEN, hidden = false), config)
        assertEquals(WifiSuggestionPlan.OPEN, config?.suggestionPlan())
    }

    @Test
    fun missingSecurityFieldMeansOpen() {
        assertEquals(WifiSecurity.OPEN, parseWifiQr("WIFI:S:OpenNet;;")?.security)
        assertEquals(WifiSecurity.OPEN, parseWifiQr("WIFI:T:;S:OpenNet;;")?.security)
    }

    @Test
    fun wepNetworkParsesButHasNoSuggestionPlan() {
        val config = parseWifiQr("WIFI:T:WEP;S:OldNet;P:abcde;;")
        assertEquals(WifiQrConfig("OldNet", "abcde", WifiSecurity.WEP, hidden = false), config)
        assertEquals(WifiSuggestionPlan.UNSUPPORTED, config?.suggestionPlan())
    }

    @Test
    fun hiddenNetworkParses() {
        assertTrue(parseWifiQr("WIFI:T:WPA;S:H;P:p;H:true;;")?.hidden == true)
        assertTrue(parseWifiQr("WIFI:T:WPA;S:H;P:p;H:1;;")?.hidden == true)
        assertFalse(parseWifiQr("WIFI:T:WPA;S:H;P:p;H:false;;")?.hidden == true)
        assertFalse(parseWifiQr("WIFI:T:WPA;S:H;P:p;;")?.hidden == true)
    }

    @Test
    fun escapedCharactersInSsidAndPassword() {
        val config = parseWifiQr("""WIFI:T:WPA;S:My\;Net\,1\:X;P:p\\w\;d;;""")
        assertEquals("My;Net,1:X", config?.ssid)
        assertEquals("""p\w;d""", config?.password)
    }

    @Test
    fun fieldsMayAppearInAnyOrderAndExtrasAreIgnored() {
        val config = parseWifiQr("WIFI:P:pw;H:true;S:Net;T:WPA;A:anon;;")
        assertEquals(WifiQrConfig("Net", "pw", WifiSecurity.WPA, hidden = true), config)
    }

    @Test
    fun trailingSemicolonsAreOptional() {
        assertEquals("Net", parseWifiQr("WIFI:T:WPA;S:Net;P:pw")?.ssid)
        assertEquals("Net", parseWifiQr("WIFI:T:WPA;S:Net;P:pw;")?.ssid)
    }

    @Test
    fun lowercasePrefixParses() {
        assertEquals("Net", parseWifiQr("wifi:T:WPA;S:Net;P:pw;;")?.ssid)
    }

    @Test
    fun isWifiQrMatchesOnlyParseablePayloads() {
        assertTrue(isWifiQr("WIFI:T:WPA;S:Net;P:pw;;"))
        assertFalse(isWifiQr("WIFI:T:WPA;P:pw;;"))
        assertFalse(isWifiQr("https://example.com"))
        assertFalse(isWifiQr("FIDO:/12345"))
    }

    @Test
    fun missingOrEmptySsidIsInvalid() {
        assertNull(parseWifiQr("WIFI:T:WPA;P:pw;;"))
        assertNull(parseWifiQr("WIFI:T:WPA;S:;P:pw;;"))
        assertNull(parseWifiQr("WIFI:;;"))
        assertNull(parseWifiQr("WIFI:"))
    }

    @Test
    fun securedNetworkWithoutPasswordIsInvalid() {
        assertNull(parseWifiQr("WIFI:T:WPA;S:Net;;"))
        assertNull(parseWifiQr("WIFI:T:WPA;S:Net;P:;;"))
        assertNull(parseWifiQr("WIFI:T:WEP;S:Net;;"))
    }

    @Test
    fun enterpriseNetworkIsUnsupported() {
        assertNull(parseWifiQr("WIFI:T:WPA2-EAP;S:Corp;P:pw;E:PEAP;;"))
    }

    @Test
    fun nonWifiPayloadsAreInvalid() {
        assertNull(parseWifiQr(""))
        assertNull(parseWifiQr("hello world"))
        assertNull(parseWifiQr("WIFI"))
        assertNull(parseWifiQr(" WIFI:T:WPA;S:Net;P:pw;;".replaceFirst(" ", "X")))
    }

    @Test
    fun danglingEscapeIsInvalid() {
        assertNull(parseWifiQr("""WIFI:T:WPA;S:Net;P:pw\"""))
    }

    @Test
    fun openNetworkIgnoresStrayPasswordField() {
        val config = parseWifiQr("WIFI:T:nopass;S:Net;P:ignored;;")
        assertEquals(null, config?.password)
    }
}
