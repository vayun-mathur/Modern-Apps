package com.vayunmathur.securemail.platform

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.securemail.data.CachedKey
import com.vayunmathur.securemail.domain.mail.KeyLookupPolicy
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Trust resolution and address claiming in [MailViewModel], which the
 * report named and earlier suites only reached up to their early
 * returns: the cached-key trust path, the no-key outcome, and a
 * well-formed claim that runs until the (absent) relay refuses it.
 */
@RunWith(RobolectricTestRunner::class)
class MailViewModelTrustClaimTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun vm(store: FakeMailStore = FakeMailStore()) = MailViewModel(app, store)

    private fun await(description: String, timeoutMs: Long = 40_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue(condition(), "timed out waiting for: $description")
    }

    @Test fun `resolveTrust answers from the vault cache without any lookup`() = runBlocking {
        val store = FakeMailStore()
        store.keys.upsert(
            CachedKey(
                address = "ada@example.org", armoredPublic = "armor",
                fingerprintHex = "AABBCCDD", source = "Cached", updatedMillis = 1L,
            ),
        )
        val trust = vm(store).resolveTrust(listOf("  ADA@Example.ORG ", "ada@example.org"))
        assertEquals(2, trust.size)
        for (entry in trust) {
            assertEquals("ada@example.org", entry.address, "addresses are trimmed and lowercased")
            assertTrue(entry.hasKey)
            assertEquals(KeyLookupPolicy.KeySource.Cached, entry.source)
            assertEquals("AABBCCDD", entry.fingerprintHex)
        }
    }

    @Test fun `resolveTrust reports no key for an address that cannot be looked up`() = runBlocking {
        // No '@' at all: no directory or WKD lookup is even possible, and no
        // network is touched - the answer is a plain no-key.
        val trust = vm().resolveTrust(listOf("not-an-address"))
        assertEquals(1, trust.size)
        assertFalse(trust.single().hasKey)
        assertEquals(KeyLookupPolicy.KeySource.None, trust.single().source)
        assertEquals("not-an-address", trust.single().address)
    }

    @Test fun `resolveTrust handles an empty recipient list`() = runBlocking {
        assertTrue(vm().resolveTrust(emptyList()).isEmpty())
    }

    @Test fun `claimAddress rejects malformed addresses and short passphrases synchronously`() {
        val viewModel = vm()
        viewModel.claimAddress("ab", "long-enough-passphrase")
        assertEquals(
            "Use 3–64 lowercase letters, digits, dots or dashes.",
            viewModel.state.error,
        )
        viewModel.claimAddress("good.name", "short")
        assertEquals("Use a passphrase of at least 8 characters.", viewModel.state.error)
        assertFalse(viewModel.state.busy, "a rejected claim must not start work")
    }

    @Test fun `claimAddress with a well-formed request fails at the offline relay`() {
        val viewModel = vm()
        viewModel.claimAddress("new.user", "long-enough-passphrase")
        assertTrue(viewModel.state.busy, "a well-formed claim starts work")
        // The relay never connects in a host test: after the relay wait the
        // claim surfaces the offline error instead of claiming anything.
        await("relay offline claim error") {
            viewModel.state.error?.contains("Relay offline") == true
        }
        assertFalse(viewModel.state.busy)
    }
}
