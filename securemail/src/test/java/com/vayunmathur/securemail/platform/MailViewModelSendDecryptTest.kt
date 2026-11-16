package com.vayunmathur.securemail.platform

import android.app.Application
import android.os.Looper
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.securemail.data.CachedKey
import com.vayunmathur.securemail.data.MailConfig
import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.data.MailIdentity
import com.vayunmathur.securemail.data.StoredMessage
import com.vayunmathur.securemail.domain.mail.MimeCodec
import com.vayunmathur.securemail.domain.pgp.PgpCrypto
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [MailViewModel] send/decrypt happy paths through [FakeMailStore] with a
 * real generated identity and an injected passphrase. The socket is offline
 * in tests, so encrypted/plain sends queue to the outbox — the deterministic
 * offline outcome of the real publish call.
 */
@RunWith(RobolectricTestRunner::class)
class MailViewModelSendDecryptTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val me = PgpCrypto.generateIdentity("Me <me@maemail.org>", "my-pass")
    private val ada = PgpCrypto.generateIdentity("Ada <ada@example.org>", "ada-pass")

    private fun await(description: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue(condition(), "timed out waiting for: $description")
    }

    private suspend fun seededStore(): FakeMailStore {
        val store = FakeMailStore()
        store.identities.upsert(
            MailIdentity("me@maemail.org", "", me.armoredPublic, me.fingerprintHex, me.armoredSecret, isPrimary = true, createdMillis = 1),
        )
        return store
    }

    private fun vm(store: FakeMailStore) = MailViewModel(app, store).also { it.passphraseLoader = { "my-pass" } }

    @Test fun `send to a keyed recipient queues offline and stores an encrypted sent copy`() {
        val store = runBlocking { seededStore() }
        runBlocking { store.keys.upsert(CachedKey("ada@example.org", ada.armoredPublic, ada.fingerprintHex, "Wkd", 1)) }
        val viewModel = vm(store)
        var done: Boolean? = null
        viewModel.send(to = listOf("ada@example.org"), subject = "Hi Ada", body = "Secret body") { done = it }
        await("encrypted send settles") { done != null }
        assertEquals(true, done)
        assertFalse(viewModel.state.busy)
        assertTrue(viewModel.state.message!!.contains("Queued 1"), viewModel.state.message)
        val queued = runBlocking { store.outbox.all() }.single()
        assertEquals("ada@example.org", queued.toJson)
        assertEquals("me@maemail.org", queued.fromAddress)
        val sent = store.messages.snapshot().single { it.folder == MailFolder.SENT }
        assertEquals("Secret body", sent.decryptedBody)
        assertEquals("Hi Ada", sent.decryptedSubject)
        assertFalse(sent.unencrypted)
    }

    @Test fun `send merges cc and bcc, keyless recipients queue plain and sent copy is flagged`() {
        val store = runBlocking { seededStore() }
        val viewModel = vm(store)
        var done: Boolean? = null
        viewModel.send(
            to = listOf("bob@example.org"), cc = listOf("bob@example.org"), bcc = listOf("carol@example.org"),
            subject = "Plain", body = "For everyone",
        ) { done = it }
        await("plain send settles") { done != null }
        assertEquals(true, done)
        val queued = runBlocking { store.outbox.all() }
        assertEquals(2, queued.size)
        assertEquals(setOf("bob@example.org", "carol@example.org"), queued.map { it.toJson }.toSet())
        val sent = store.messages.snapshot().single { it.folder == MailFolder.SENT }
        assertTrue(sent.unencrypted)
        assertEquals("bob@example.org,carol@example.org", sent.toJson)
    }

    @Test fun `send with no recipients stores a sent copy and reports sent`() {
        val store = runBlocking { seededStore() }
        val viewModel = vm(store)
        var done: Boolean? = null
        viewModel.send(to = emptyList(), subject = "Note to self", body = "Draft-ish") { done = it }
        await("empty send settles") { done != null }
        assertEquals(true, done)
        assertEquals("Sent.", viewModel.state.message)
        assertTrue(runBlocking { store.outbox.all() }.isEmpty())
        assertEquals(1, store.messages.snapshot().count { it.folder == MailFolder.SENT })
    }

    @Test fun `send from a disabled alias fails and unknown from falls back to primary`() {
        val store = runBlocking {
            seededStore().also {
                it.identities.upsert(
                    MailIdentity("alias@maemail.org", "", ada.armoredPublic, ada.fingerprintHex, ada.armoredSecret, disabled = true, createdMillis = 2),
                )
            }
        }
        val viewModel = vm(store)
        var done: Boolean? = null
        viewModel.send(to = listOf("ada@example.org"), subject = "S", body = "B", from = "alias@maemail.org") { done = it }
        await("disabled sender fails") { done != null }
        assertEquals(false, done)
        assertTrue(viewModel.state.error!!.contains("disabled"), viewModel.state.error)
        // Unknown from addresses fall back to the primary identity
        // (Identities.forDelivery semantics), so this send queues as me.
        assertTrue(store.messages.snapshot().isEmpty())
        done = null
        viewModel.send(to = listOf("ada@example.org"), subject = "S", body = "B", from = "ghost@maemail.org") { done = it }
        await("unknown sender falls back") { done != null }
        assertEquals(true, done)
        assertEquals("me@maemail.org", runBlocking { store.outbox.all() }.single().fromAddress)
    }

    @Test fun `decryptStored decrypts and verifies a locked blob in place`() {
        val store = runBlocking { seededStore() }
        runBlocking { store.keys.upsert(CachedKey("ada@example.org", ada.armoredPublic, ada.fingerprintHex, "Wkd", 1)) }
        val mime = MimeCodec.buildPlainBody("ada@example.org", "Locked subj", "Locked body", "m@x")
        val raw = PgpCrypto.encryptSigned(mime, me.armoredPublic, ada.armoredSecret, "ada-pass")
        runBlocking {
            store.messages.upsert(
                StoredMessage(
                    id = "lock1", from = "", toJson = "me@maemail.org", dateMillis = 5, messageId = "lock1",
                    blobBase64 = Base64.encodeToString(raw, Base64.NO_WRAP), deliveredTo = "me@maemail.org",
                ),
            )
        }
        val viewModel = vm(store)
        viewModel.decryptStored("lock1")
        await("decrypt stores plaintext") { store.messages.storeDecryptedCalls.isNotEmpty() }
        val call = store.messages.storeDecryptedCalls.single()
        assertEquals(listOf("lock1", "Locked body", "Locked subj", ada.fingerprintHex, null), call)
        assertEquals("ada@example.org", runBlocking { store.messages.get("lock1") }?.from)
        assertNull(viewModel.state.error)
    }

    @Test fun `decryptStored accepts an armored envelope from an uncached sender`() {
        val store = runBlocking { seededStore() }
        val mime = MimeCodec.buildPlainBody("own@maemail.org", "Env subj", "Env body", "m@x")
        val inner = PgpCrypto.encrypt(mime, me.armoredPublic)
        val envelope = MimeCodec.buildEncryptedEnvelope(PgpCrypto.armorMessage(inner))
        runBlocking {
            store.messages.upsert(
                StoredMessage(
                    id = "lock2", from = "", toJson = "me@maemail.org", dateMillis = 5, messageId = "lock2",
                    blobBase64 = Base64.encodeToString(envelope, Base64.NO_WRAP), deliveredTo = "me@maemail.org",
                ),
            )
        }
        val viewModel = vm(store)
        viewModel.decryptStored("lock2")
        await("envelope decrypt settles") {
            store.messages.storeDecryptedCalls.isNotEmpty() || viewModel.state.error != null
        }
        // Sender is own-domain: WKD/socket lookups miss offline, so the
        // provisional (unverified) plaintext is what gets stored.
        assertEquals("Env body", store.messages.storeDecryptedCalls.singleOrNull()?.get(1))
        assertEquals(null, store.messages.storeDecryptedCalls.singleOrNull()?.get(3))
    }

    @Test fun `exportPublicKey surfaces armor only after an identity is configured`() {
        val config = MailConfig(app)
        runBlocking { config.clear() }
        val viewModel = vm(runBlocking { seededStore() })
        viewModel.exportPublicKey()
        await("export without identity errors") { viewModel.state.error == "Unlock first." }
        assertNull(viewModel.state.exportArmor)
        runBlocking { config.setIdentity("me@maemail.org", "PUBLIC-ARMOR", "AABB") }
        viewModel.dismissMessages()
        viewModel.exportPublicKey()
        await("export armor appears") { viewModel.state.exportArmor == "PUBLIC-ARMOR" }
        assertNull(viewModel.state.error)
        runBlocking { config.clear() }
    }

    @Test fun `forgetIdentity clears identities and resets state`() {
        val store = runBlocking { seededStore() }
        val viewModel = vm(store)
        viewModel.forgetIdentity()
        await("identities forgotten") { runBlocking { store.identities.count() } == 0 }
        assertEquals("", viewModel.state.address)
        assertFalse(viewModel.state.onboarded)
    }
}
