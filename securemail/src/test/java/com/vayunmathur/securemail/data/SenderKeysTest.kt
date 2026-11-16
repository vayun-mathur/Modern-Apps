package com.vayunmathur.securemail.data

import com.vayunmathur.securemail.domain.pgp.PgpCrypto
import com.vayunmathur.securemail.network.MailSocket
import com.vayunmathur.securemail.platform.FakeMailStore
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [SenderKeys] resolution order: vault cache, own directory, then WKD. */
@RunWith(RobolectricTestRunner::class)
class SenderKeysTest {
    private val ada = PgpCrypto.generateIdentity("Ada <ada@example.org>", "ada-pass")
    private val own = PgpCrypto.generateIdentity("Own <own@maemail.org>", "own-pass")

    @Test fun `blank address and cache hit short circuit without lookups`() = runBlocking {
        val store = FakeMailStore()
        store.keys.upsert(CachedKey("ada@example.org", ada.armoredPublic, ada.fingerprintHex, "Wkd", 1))
        var lookups = 0
        assertNull(SenderKeys.resolve("   ", store, { lookups++; null }, { lookups++; null }))
        assertEquals(
            ada.armoredPublic,
            SenderKeys.resolve(" Ada@Example.ORG ", store, { lookups++; null }, { lookups++; null }),
        )
        assertEquals(0, lookups)
    }

    @Test fun `own domain directory hit is cached as OwnDirectory`() = runBlocking {
        val store = FakeMailStore()
        val wkdUrls = mutableListOf<String>()
        val hit = SenderKeys.resolve(
            "own@maemail.org", store,
            ownDirectory = { MailSocket.KeyResult(it, own.armoredPublic) },
            wkdFetch = { wkdUrls += it; null },
        )
        assertEquals(own.armoredPublic, hit)
        assertTrue(wkdUrls.isEmpty())
        val cached = store.keys.byAddress["own@maemail.org"]!!
        assertEquals("OwnDirectory", cached.source)
        assertEquals(own.fingerprintHex, cached.fingerprintHex)
    }

    @Test fun `own directory miss and null armor fall through to first WKD success`() = runBlocking {
        val store = FakeMailStore()
        val urls = mutableListOf<String>()
        val hit = SenderKeys.resolve(
            "own@maemail.org", store,
            ownDirectory = { MailSocket.KeyResult(it, null) },
            wkdFetch = { url -> urls += url; if (urls.size == 1) null else ada.armoredPublic },
        )
        assertEquals(ada.armoredPublic, hit)
        assertEquals(2, urls.size)
        assertTrue(urls[0].contains("openpgpkey.maemail.org"))
        assertEquals("Wkd", store.keys.byAddress["own@maemail.org"]?.source)
    }

    @Test fun `external address skips the directory and reports null when WKD misses`() = runBlocking {
        val store = FakeMailStore()
        var directoryCalls = 0
        val hit = SenderKeys.resolve(
            "ada@example.org", store,
            ownDirectory = { directoryCalls++; error("must not be called for external") },
            wkdFetch = { null },
        )
        assertNull(hit)
        assertEquals(0, directoryCalls)
        assertTrue(store.keys.byAddress.isEmpty())
    }

    @Test fun `directory exception falls through and uncacheable armor still returns`() = runBlocking {
        val store = FakeMailStore()
        val hit = SenderKeys.resolve(
            "ada@example.org", store,
            ownDirectory = { null },
            wkdFetch = { "not a real key" },
        )
        // fingerprintHex fails on malformed armor, so nothing is cached,
        // but the fetched armor is still handed to the caller.
        assertEquals("not a real key", hit)
        assertTrue(store.keys.byAddress.isEmpty())
        val ownStore = FakeMailStore()
        val ownHit = SenderKeys.resolve(
            "own@maemail.org", ownStore,
            ownDirectory = { throw IllegalStateException("socket down") },
            wkdFetch = { own.armoredPublic },
        )
        assertEquals(own.armoredPublic, ownHit)
        assertEquals("Wkd", ownStore.keys.byAddress["own@maemail.org"]?.source)
    }
}
