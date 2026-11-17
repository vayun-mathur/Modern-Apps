package com.vayunmathur.vpn.data

import com.vayunmathur.vpn.domain.MavpnUpdateMetadata
import com.vayunmathur.vpn.domain.sha256Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MavpnUpdateTest {
    @Test fun verifiedBundleDownloadsOnlyWithGoodHash() { runBlocking {
        val bundle = "zip-bytes".encodeToByteArray()
        val client = MavpnUpdateClient { bundle }
        val good = MavpnUpdateMetadata(
            "mavpn-update/1", "p", 2,
            downloadUrl = "https://example.invalid/b.mavpn", sha256 = sha256Hex(bundle),
        )
        assertEquals(bundle.toList(), client.fetchVerifiedBundle(good).toList())
        val bad = good.copy(sha256 = sha256Hex("other".encodeToByteArray()))
        assertTrue(runCatching { client.fetchVerifiedBundle(bad) }.isFailure)
        assertTrue(runCatching { client.fetchVerifiedBundle(good.copy(sha256 = null)) }.isFailure)
        assertTrue(runCatching { client.fetchVerifiedBundle(good.copy(downloadUrl = null)) }.isFailure)
    } }

    @Test fun metadataFetchedThroughInjectedFetcher() { runBlocking {
        val client = MavpnUpdateClient {
            """{"format":"mavpn-update/1","providerId":"p","revision":5}""".encodeToByteArray()
        }
        val meta = client.fetchMetadata("https://example.invalid/u.json")
        assertEquals(5L, meta.revision)
    } }
}
