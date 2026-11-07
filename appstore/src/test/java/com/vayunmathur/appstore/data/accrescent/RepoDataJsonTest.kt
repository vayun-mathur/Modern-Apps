package com.vayunmathur.appstore.data.accrescent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The repodata wire contract the trust anchor is built from: the
 * signed document's snake_case fields decode into the allowlist
 * entries, unknown fields are tolerated (the document evolves), and
 * a round trip preserves every app.
 */
class RepoDataJsonTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `a repodata document decodes into allowlist entries`() {
        val document = """
            {
              "timestamp": 1735689600,
              "future_field": {"nested": true},
              "apps": {
                "app.example.one": {"min_version_code": 42, "signing_cert_hash": "AB12"},
                "app.example.two": {"min_version_code": 7, "signing_cert_hash": "CD34"}
              }
            }
        """.trimIndent()

        val data = json.decodeFromString(RepoData.serializer(), document)
        assertEquals(1735689600L, data.timestamp)
        assertEquals(2, data.apps.size)
        assertEquals(42L, data.apps.getValue("app.example.one").minVersionCode)
        assertEquals("AB12", data.apps.getValue("app.example.one").signingCertHash)
        assertEquals(7L, data.apps.getValue("app.example.two").minVersionCode)
    }

    @Test
    fun `an empty document has no apps and a missing apps field defaults empty`() {
        val empty = json.decodeFromString(RepoData.serializer(), """{"timestamp": 5, "apps": {}}""")
        assertTrue(empty.apps.isEmpty())
        val noApps = json.decodeFromString(RepoData.serializer(), """{"timestamp": 5}""")
        assertTrue(noApps.apps.isEmpty())
        assertEquals(5L, noApps.timestamp)
    }

    @Test
    fun `a round trip preserves the document`() {
        val data = RepoData(
            timestamp = 99L,
            apps = mapOf("app.example" to RepoApp(minVersionCode = 3L, signingCertHash = "EF56")),
        )
        val encoded = json.encodeToString(RepoData.serializer(), data)
        assertTrue(encoded.contains("min_version_code"), encoded)
        assertTrue(encoded.contains("signing_cert_hash"), encoded)
        assertEquals(data, json.decodeFromString(RepoData.serializer(), encoded))
    }
}
