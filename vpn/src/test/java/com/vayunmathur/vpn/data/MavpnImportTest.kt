package com.vayunmathur.vpn.data

import com.vayunmathur.vpn.domain.MavpnParser
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MavpnImportTest {
    private val conf = """
        [Interface]
        PrivateKey = cGFzc3dvcmQxMjM0NTY3ODkwYWJjZGVmZ2hpamtsbW4=
        Address = 10.0.0.2/32
        DNS = 1.1.1.1
        [Peer]
        PublicKey = cGVlcnB1YmxpY2tleTEyMzQ1Njc4OTBhYmNkZWZnaGk=
        Endpoint = conf.example:51820
    """.trimIndent()

    private fun bundleBytes(): ByteArray {
        val manifest = """{"format":"mavpn/1","provider":"Example VPN","servers":[""" +
            """{"id":"de-1","country":"DE","city":"Frankfurt","file":"servers/de-1.conf",""" +
            """"endpoint":"manifest.example:9999"},""" +
            """{"id":"us-1","country":"US","city":"New York","file":"servers/us-1.conf"}]}"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in listOf(
                "manifest.json" to manifest,
                "servers/de-1.conf" to conf,
                "servers/us-1.conf" to conf.replace("conf.example", "us.example"),
            )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun importMapsServersToSelectableConfigs() {
        val bundle = MavpnParser.parse(bundleBytes(), WgConfigParser::parse).getOrThrow()
        val bundleId = bundleIdFor(bundle.manifest.provider, bundle.servers.map { it.entry.id })
        val configs = bundle.toVpnConfigs(bundleId) { "derived:" + it.take(4) }
        assertEquals(2, configs.size)
        val frankfurt = configs.first { it.bundleServerId == "de-1" }
        // Manifest endpoint wins over the .conf endpoint for activation/display.
        assertEquals("manifest.example:9999", frankfurt.peerEndpoint)
        assertEquals("Example VPN", frankfurt.provider)
        assertEquals("DE", frankfurt.countryCode)
        assertEquals("Frankfurt", frankfurt.city)
        assertEquals(bundleId, frankfurt.bundleId)
        assertEquals("derived:cGFz", frankfurt.publicKey)
        assertEquals("10.0.0.2/32", frankfurt.address)
        val newYork = configs.first { it.bundleServerId == "us-1" }
        assertEquals("us.example:51820", newYork.peerEndpoint)
    }

    @Test fun activationUsesSameConfigModelAndWgQuickRoundTrip() {
        val bundle = MavpnParser.parse(bundleBytes(), WgConfigParser::parse).getOrThrow()
        val config = bundle.toVpnConfigs("bundle-1").first { it.bundleServerId == "de-1" }
        // The existing tunnel activation serialises a VpnConfig; the imported
        // server must survive that path with its effective endpoint intact.
        val reparsed = WgConfigParser.parse(WgConfigParser.toWgQuick(config)).getOrThrow()
        assertEquals(config.privateKey, reparsed.privateKey)
        assertEquals("manifest.example:9999", reparsed.peerEndpoint)
        assertEquals(config.peerPublicKey, reparsed.peerPublicKey)
    }

    @Test fun reimportReusesExistingConfigIds() {
        val bundle = MavpnParser.parse(bundleBytes(), WgConfigParser::parse).getOrThrow()
        val bundleId = bundleIdFor(bundle.manifest.provider, bundle.servers.map { it.entry.id })
        val first = bundle.toVpnConfigs(bundleId)
        val saved = first.mapIndexed { index, config -> config.copy(id = 10L + index, lastUsed = 42) }
        val second = bundle.toVpnConfigs(bundleId, saved)
        assertEquals(saved.map { it.id }, second.map { it.id })
        assertEquals(listOf(42L, 42L), second.map { it.lastUsed })
        assertEquals(bundleId, bundleIdFor(bundle.manifest.provider, bundle.servers.map { it.entry.id }))
        assertNotEquals(bundleId, bundleIdFor("Other", bundle.servers.map { it.entry.id }))
    }

    @Test fun geoServerMappingOnlyForBundleConfigs() {
        val plain = VpnConfig(id = 1, name = "plain")
        assertNull(plain.toGeoServerOrNull())
        val imported = VpnConfig(
            id = 2, name = "Example VPN — Frankfurt", peerEndpoint = "h:1",
            provider = "Example VPN", countryCode = "DE", city = "Frankfurt", bundleId = "b",
            bundleServerId = "de-1",
        )
        val geo = imported.toGeoServerOrNull()
        assertEquals(2L, geo?.configId)
        assertEquals("h:1", geo?.endpoint)
        assertTrue(geo?.name?.contains("Frankfurt") == true)
    }

    @Test fun entityRoundTripKeepsBundleMetadata() {
        val config = VpnConfig(
            id = 7, name = "n", provider = "P", countryCode = "DE", city = "Berlin",
            bundleId = "p-abc", bundleServerId = "de-1",
        )
        assertEquals(config, config.toEntity().toModel())
    }
}
