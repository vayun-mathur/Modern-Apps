package com.vayunmathur.securemail.platform

import com.vayunmathur.securemail.data.CachedKey
import com.vayunmathur.securemail.data.MailIdentity
import com.vayunmathur.securemail.data.OutboxMessage
import com.vayunmathur.securemail.domain.pgp.PgpCrypto
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [OutboxRetry] against a fake [com.vayunmathur.securemail.data.MailStore].
 * The socket has no live session in tests, so publishes fail with the real
 * "no session" reason — which is exactly the retry-stays-queued behaviour.
 */
@RunWith(RobolectricTestRunner::class)
class OutboxRetryTest {
    private val sender = MailIdentity(
        address = "me@maemail.org", label = "", publicArmor = "pub", fingerprintHex = "AA",
        secretArmor = "secret", isPrimary = true, createdMillis = 1,
    )

    private fun retry(
        store: FakeMailStore,
        passphrase: () -> String = { "passphrase-one" },
        identity: suspend (String?) -> MailIdentity = { sender },
    ) = OutboxRetry(store, passphrase, identity)

    private fun entry(toJson: String, from: String? = "me@maemail.org", created: Long = 1) =
        OutboxMessage(toJson = toJson, subject = "Hi", body = "Body", createdMillis = created, fromAddress = from)

    @Test fun `empty outbox sends and fails nothing`() = runBlocking {
        val store = FakeMailStore()
        assertEquals(0 to 0, retry(store).retryAll())
        assertTrue(store.outbox.noteAttemptCalls.isEmpty())
    }

    @Test fun `plain recipient stays queued with publish reason when offline`() = runBlocking {
        val store = FakeMailStore()
        val id = store.outbox.insert(entry("ada@example.org"))
        assertEquals(0 to 1, retry(store).retryAll())
        assertEquals(1, store.outbox.all().size)
        val attempted = store.outbox.all().single()
        assertEquals(1, attempted.attemptCount)
        assertTrue(attempted.lastError!!.contains("publish failed"), attempted.lastError)
        assertTrue(attempted.lastError!!.contains("no session"), attempted.lastError)
        assertEquals(listOf(id to attempted.lastError), store.outbox.noteAttemptCalls.map { it.first to it.second })
    }

    @Test fun `one entry with two recipients fails once and records one attempt`() = runBlocking {
        val store = FakeMailStore()
        store.outbox.insert(entry("a@x.org, b@x.org"))
        assertEquals(0 to 1, retry(store).retryAll())
        assertEquals(1, store.outbox.noteAttemptCalls.size)
        assertEquals(1, store.outbox.all().single().attemptCount)
    }

    @Test fun `blank recipient tokens are ignored but entry still attempts publish path`() = runBlocking {
        val store = FakeMailStore()
        store.outbox.insert(entry("  , ada@example.org , "))
        assertEquals(0 to 1, retry(store).retryAll())
        assertEquals(1, store.outbox.noteAttemptCalls.size)
    }

    @Test fun `cached key with malformed armor records encrypt failure without publishing`() = runBlocking {
        val store = FakeMailStore()
        store.keys.upsert(CachedKey("ada@example.org", "not a key", "FF", "Cached", 1))
        store.outbox.insert(entry("ada@example.org"))
        assertEquals(0 to 1, retry(store).retryAll())
        val error = store.outbox.all().single().lastError!!
        assertTrue(error.contains("encrypt failed"), error)
    }

    @Test fun `cached key encrypts with real keys then fails at offline publish`() = runBlocking {
        val store = FakeMailStore()
        val recipient = PgpCrypto.generateIdentity("Ada <ada@example.org>", "recipient-pass")
        val mine = PgpCrypto.generateIdentity("Me <me@maemail.org>", "passphrase-one")
        store.keys.upsert(CachedKey("ada@example.org", recipient.armoredPublic, recipient.fingerprintHex, "Cached", 1))
        store.outbox.insert(entry("ada@example.org"))
        val realSender = sender.copy(secretArmor = mine.armoredSecret, publicArmor = mine.armoredPublic)
        assertEquals(0 to 1, retry(store, identity = { realSender }).retryAll())
        val error = store.outbox.all().single().lastError!!
        assertTrue(error.contains("publish failed"), error)
    }

    @Test fun `sender identity failure is recorded as outbox error`() = runBlocking {
        val store = FakeMailStore()
        store.outbox.insert(entry("ada@example.org"))
        val failing = retry(store, identity = { throw IllegalStateException("no identity yet") })
        assertEquals(0 to 1, failing.retryAll())
        assertEquals("outbox error: no identity yet", store.outbox.all().single().lastError)
    }

    @Test fun `multiple entries are attempted in order and counted separately`() = runBlocking {
        val store = FakeMailStore()
        store.outbox.insert(entry("a@x.org", created = 2))
        store.outbox.insert(entry("b@x.org", created = 1))
        assertEquals(0 to 2, retry(store).retryAll())
        assertEquals(2, store.outbox.noteAttemptCalls.size)
        assertTrue(store.outbox.all().all { it.attemptCount == 1 })
    }

    @Test fun `missing passphrase aborts the whole retry before any attempt`() = runBlocking {
        val store = FakeMailStore()
        store.outbox.insert(entry("ada@example.org"))
        assertFailsWith<IllegalStateException> {
            retry(store, passphrase = { throw IllegalStateException("locked") }).retryAll()
        }
        assertTrue(store.outbox.noteAttemptCalls.isEmpty())
        assertEquals(0, store.outbox.all().single().attemptCount)
    }

    @Test fun `buildMimeBody and buildEncryptedBody wrap content`() {
        val mime = buildMimeBody("me@maemail.org", "Hello", "Body text")
        val text = mime.toString(Charsets.UTF_8)
        assertTrue(text.contains("Hello"), text)
        assertTrue(text.contains("Body text"), text)
        val envelope = buildEncryptedBody(byteArrayOf(1, 2, 3))
        assertTrue(envelope.isNotEmpty())
        assertTrue(envelope.toString(Charsets.UTF_8).contains("PGP MESSAGE"))
    }
}
