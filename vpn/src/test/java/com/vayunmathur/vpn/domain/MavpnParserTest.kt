package com.vayunmathur.vpn.domain

import com.vayunmathur.vpn.data.WgConfigParser
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class MavpnParserTest {
    private val conf = """
        [Interface]
        PrivateKey = cGFzc3dvcmQxMjM0NTY3ODkwYWJjZGVmZ2hpamtsbW4=
        Address = 10.0.0.2/32
        [Peer]
        PublicKey = cGVlcnB1YmxpY2tleTEyMzQ1Njc4OTBhYmNkZWZnaGk=
        Endpoint = vpn.example.com:51820
    """.trimIndent()

    private fun manifest(
        format: String = "mavpn/1",
        servers: String = """[{"id":"de-1","country":"DE","city":"Frankfurt","file":"servers/de-1.conf"}]""",
    ) = """{"format":"$format","provider":"Example","servers":$servers}"""

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun bundle(
        manifestText: String = manifest(),
        confText: String = conf,
        extra: Array<Pair<String, String>> = emptyArray(),
    ) = zipOf("manifest.json" to manifestText, "servers/de-1.conf" to confText, *extra)

    private fun parse(bytes: ByteArray) = MavpnParser.parse(bytes, WgConfigParser::parse)

    @Test fun parsesValidBundleThroughExistingWireGuardParser() {
        val parsed = parse(bundle()).getOrThrow()
        assertEquals("Example", parsed.manifest.provider)
        assertEquals(1, parsed.servers.size)
        val server = parsed.servers.single()
        assertEquals("DE", server.entry.country)
        assertEquals("Frankfurt", server.entry.city)
        assertEquals("cGFzc3dvcmQxMjM0NTY3ODkwYWJjZGVmZ2hpamtsbW4=", server.config.privateKey)
        assertEquals("vpn.example.com:51820", server.config.peerEndpoint)
    }

    @Test fun parsesFreshnessFieldsAndLegacyBundles() {
        val text = "{\"format\":\"mavpn/1\",\"provider\":\"Example\",\"providerId\":\"example\"," +
            "\"revision\":7,\"issuedAt\":\"2026-01-01T00:00:00Z\",\"expiresAt\":\"2027-01-01T00:00:00Z\"," +
            "\"updateUrl\":\"https://example.invalid/u.json\",\"servers\":" +
            "[{\"id\":\"de-1\",\"country\":\"DE\",\"city\":\"Frankfurt\",\"file\":\"servers/de-1.conf\"}]}"
        val parsed = parse(bundle(manifestText = text)).getOrThrow()
        assertEquals("example", parsed.manifest.freshness.providerId)
        assertEquals(7L, parsed.manifest.freshness.revision)
        assertEquals("https://example.invalid/u.json", parsed.manifest.freshness.updateUrl)
        assertTrue(parsed.manifest.freshness.issuedAtEpochMs!! < parsed.manifest.freshness.expiresAtEpochMs!!)
        // Legacy bundle: no freshness fields still parses, with empty freshness.
        assertTrue(parse(bundle()).getOrThrow().manifest.freshness.isEmpty)
    }

    @Test fun rejectsNonsensicalFreshness() {
        fun withFresh(extra: String): String =
            "{\"format\":\"mavpn/1\",\"provider\":\"Example\",$extra,\"servers\":" +
                "[{\"id\":\"de-1\",\"country\":\"DE\",\"city\":\"Frankfurt\",\"file\":\"servers/de-1.conf\"}]}"
        for (bad in listOf(
            "\"revision\":-1",
            "\"revision\":1.5",
            "\"providerId\":\"bad id!\"",
            "\"issuedAt\":\"not-a-date\"",
            "\"issuedAt\":\"2027-01-01T00:00:00Z\",\"expiresAt\":\"2026-01-01T00:00:00Z\"",
            "\"updateUrl\":\"http://example.invalid/u.json\"",
        )) {
            assertTrue(parse(bundle(manifestText = withFresh(bad))).isFailure, bad)
        }
    }

    @Test fun loadsCommittedDemoFixture() {
        val bytes = javaClass.getResourceAsStream("/mavpn/demo.mavpn")?.readBytes()
            ?: fail("missing test fixture /mavpn/demo.mavpn (regenerate with vpn/tools/generate_sample_mavpn.py)")
        val parsed = parse(bytes).getOrThrow()
        assertEquals("EXAMPLE Demo VPN (fictional)", parsed.manifest.provider)
        assertEquals("example-demo-vpn", parsed.manifest.freshness.providerId)
        assertEquals(1L, parsed.manifest.freshness.revision)
        assertEquals(listOf("de-fra-1", "de-ber-1", "us-nyc-1"), parsed.servers.map { it.entry.id })
        assertTrue(parsed.servers.all { it.config.peerEndpoint.endsWith(".example.invalid:51820") })
    }

    @Test fun manifestEndpointOverridesConfEndpoint() {
        val text = manifest(
            servers = """[{"id":"de-1","country":"DE","city":"Frankfurt","file":"servers/de-1.conf",""" +
                """"endpoint":"override.example:9999"}]""",
        )
        val server = parse(bundle(manifestText = text)).getOrThrow().servers.single()
        assertEquals("override.example:9999", server.effectiveEndpoint { it.peerEndpoint })
    }

    @Test fun rejectsUnknownFormatMajorAndBadFormat() {
        assertTrue(parse(bundle(manifestText = manifest(format = "mavpn/2"))).isFailure)
        assertTrue(parse(bundle(manifestText = manifest(format = "other/1"))).isFailure)
        assertTrue(parse(bundle(manifestText = manifest(format = "mavpn/x"))).isFailure)
        assertTrue(parse(bundle(manifestText = "{}")).isFailure)
        assertTrue(parse(bundle(manifestText = "not json")).isFailure)
    }

    @Test fun rejectsMissingManifestAndMissingServerFile() {
        assertTrue(parse(zipOf("servers/de-1.conf" to conf)).isFailure)
        val missing = manifest(
            servers = """[{"id":"de-1","country":"DE","city":"Frankfurt","file":"servers/nope.conf"}]""",
        )
        assertTrue(parse(bundle(manifestText = missing)).isFailure)
    }

    @Test fun rejectsTraversalAndAbsoluteEntries() {
        for (evil in listOf("../evil.conf", "servers/../../evil.conf", "/abs.conf", "servers\\evil.conf")) {
            val bytes = zipOf("manifest.json" to manifest(), evil to conf)
            val result = parse(bytes)
            assertTrue(result.isFailure, "expected rejection for $evil")
        }
        assertTrue(MavpnParser.runCatching { checkedEntryName("a/../b") }.isFailure)
        assertEquals("servers/de-1.conf", MavpnParser.checkedEntryName("servers/de-1.conf"))
    }

    @Test fun rejectsOversizeEntryAndTooManyEntries() {
        val bigConf = conf + "\n# " + "x".repeat(MavpnParser.MAX_ENTRY_BYTES)
        assertTrue(parse(bundle(confText = bigConf)).isFailure)
        val many = Array(MavpnParser.MAX_ENTRIES + 1) { "extra/$it.txt" to "x" }
        assertTrue(parse(bundle(extra = many)).isFailure)
    }

    @Test fun rejectsBadManifestFields() {
        val cases = listOf(
            """[{"id":"bad id!","country":"DE","city":"X","file":"servers/de-1.conf"}]""",
            """[{"id":"de-1","country":"XX","city":"X","file":"servers/de-1.conf"}]""",
            """[{"id":"de-1","country":"DE","city":"","file":"servers/de-1.conf"}]""",
            """[{"id":"de-1","country":"DE","city":"X","file":"elsewhere/de-1.conf"}]""",
            """[{"id":"de-1","country":"DE","city":"X","file":"servers/de-1.conf"},""" +
                """{"id":"de-1","country":"DE","city":"Y","file":"servers/de-1.conf"}]""",
            """[]""",
        )
        for (servers in cases) {
            assertTrue(parse(bundle(manifestText = manifest(servers = servers))).isFailure, servers)
        }
    }

    @Test fun rejectsInvalidWireGuardConfWithoutLeakingKeyMaterial() {
        val result = parse(bundle(confText = "[Interface]\nPrivateKey = SECRETKEY\n"))
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("SECRETKEY" !in message, "parser error leaked config contents: $message")
    }

    @Test fun rejectsNonZipAndEmptyInput() {
        assertTrue(parse(ByteArray(0)).isFailure)
        assertTrue(parse("not a zip".encodeToByteArray()).isFailure)
    }
}
