package com.vayunmathur.securemail.platform

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.securemail.data.CachedKey
import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.data.StoredMessage
import com.vayunmathur.securemail.domain.mail.KeyLookupPolicy
import kotlinx.coroutines.flow.first
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
 * [MailViewModel] through its public API with a [FakeMailStore].
 * Anything needing the AndroidKeyStore, the live socket session, or the
 * process-wide SQLCipher [com.vayunmathur.securemail.data.MailRepository]
 * singleton (sender identity, real decryption) is exercised only up to its
 * deterministic failure/early-return branches — see the report notes.
 */
@RunWith(RobolectricTestRunner::class)
class MailViewModelTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun vm(store: FakeMailStore = FakeMailStore()) = MailViewModel(app, store)

    private fun stored(id: String, folder: MailFolder = MailFolder.INBOX, body: String? = "body", toJson: String = "ada@example.org") =
        StoredMessage(
            id = id, from = "ada@example.org", toJson = toJson, dateMillis = 100, messageId = id,
            blobBase64 = "", decryptedBody = body, decryptedSubject = "Subject $id", folder = folder,
        )

    /** Pumps the (paused) main looper and waits for an IO-side effect. */
    private fun await(description: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue(condition(), "timed out waiting for: $description")
    }

    @Test fun `seedDraft filters blanks dedupes and blanks from becomes null`() {
        val viewModel = vm()
        viewModel.seedDraft(
            to = listOf("a@x.org", "", "  ", "a@x.org", "b@x.org"),
            cc = listOf("c@x.org", "c@x.org"),
            bcc = listOf("", "d@x.org"),
            from = "  ",
        )
        assertEquals(listOf("a@x.org", "b@x.org"), viewModel.draftTo)
        assertEquals(listOf("c@x.org"), viewModel.draftCc)
        assertEquals(listOf("d@x.org"), viewModel.draftBcc)
        assertNull(viewModel.draftFrom)
        viewModel.seedDraft(from = "alias@maemail.org")
        assertEquals("alias@maemail.org", viewModel.draftFrom)
        assertEquals(emptyList(), viewModel.draftTo)
    }

    @Test fun `clearDraft empties every recipient list and sender`() {
        val viewModel = vm()
        viewModel.seedDraft(listOf("a@x.org"), listOf("c@x.org"), listOf("d@x.org"), "me@maemail.org")
        viewModel.clearDraft()
        assertEquals(emptyList(), viewModel.draftTo)
        assertEquals(emptyList(), viewModel.draftCc)
        assertEquals(emptyList(), viewModel.draftBcc)
        assertNull(viewModel.draftFrom)
    }

    @Test fun `addDraftRecipient validates lowercases and rejects duplicates per list`() {
        val viewModel = vm()
        assertTrue(viewModel.addDraftRecipient(0, "  Ada@Example.ORG "))
        assertEquals(listOf("ada@example.org"), viewModel.draftTo)
        assertFalse(viewModel.addDraftRecipient(0, "ADA@example.org"))
        assertFalse(viewModel.addDraftRecipient(0, "not an address"))
        assertFalse(viewModel.addDraftRecipient(0, ""))
        assertTrue(viewModel.addDraftRecipient(1, "cc@example.org"))
        assertTrue(viewModel.addDraftRecipient(2, "bcc@example.org"))
        // Same address may live in a different list.
        assertTrue(viewModel.addDraftRecipient(1, "ada@example.org"))
        assertEquals(listOf("cc@example.org", "ada@example.org"), viewModel.draftCc)
        // Unknown targets fall back to To.
        assertTrue(viewModel.addDraftRecipient(99, "other@example.org"))
        assertEquals(listOf("ada@example.org", "other@example.org"), viewModel.draftTo)
    }

    @Test fun `removeDraftRecipient only touches its own list`() {
        val viewModel = vm()
        viewModel.seedDraft(listOf("a@x.org"), listOf("a@x.org"), listOf("a@x.org"))
        viewModel.removeDraftRecipient(1, "a@x.org")
        assertEquals(listOf("a@x.org"), viewModel.draftTo)
        assertEquals(emptyList(), viewModel.draftCc)
        viewModel.removeDraftRecipient(2, "a@x.org")
        viewModel.removeDraftRecipient(0, "a@x.org")
        assertEquals(emptyList(), viewModel.draftTo)
        assertEquals(emptyList(), viewModel.draftBcc)
        viewModel.removeDraftRecipient(0, "ghost@x.org") // no-op, no crash
    }

    @Test fun `selectFolder drives folderMessages from the store`() = runBlocking {
        val store = FakeMailStore()
        store.messages.upsert(stored("in1", MailFolder.INBOX))
        store.messages.upsert(stored("sp1", MailFolder.SPAM))
        val viewModel = vm(store)
        assertEquals(MailFolder.INBOX, viewModel.selectedFolder)
        assertEquals(listOf("in1"), viewModel.folderMessages.first().map { it.id })
        viewModel.selectFolder(MailFolder.SPAM)
        assertEquals(MailFolder.SPAM, viewModel.selectedFolder)
        assertEquals(listOf("sp1"), viewModel.folderMessages.first().map { it.id })
        viewModel.selectFolder(MailFolder.TRASH)
        assertEquals(emptyList(), viewModel.folderMessages.first())
    }

    @Test fun `claimAddress rejects invalid local part and short passphrase synchronously`() {
        val viewModel = vm()
        viewModel.claimAddress("ab", "long-enough-passphrase")
        assertEquals("Use 3–64 lowercase letters, digits, dots or dashes.", viewModel.state.error)
        assertFalse(viewModel.state.busy)
        viewModel.claimAddress("bad part!", "long-enough-passphrase")
        assertEquals("Use 3–64 lowercase letters, digits, dots or dashes.", viewModel.state.error)
        viewModel.claimAddress("alice", "short")
        assertEquals("Use a passphrase of at least 8 characters.", viewModel.state.error)
        assertFalse(viewModel.state.busy)
    }

    @Test fun `dismissMessages clears message error and export armor`() {
        val viewModel = vm()
        viewModel.claimAddress("ab", "long-enough-passphrase")
        assertTrue(viewModel.state.error != null)
        viewModel.dismissMessages()
        assertNull(viewModel.state.error)
        assertNull(viewModel.state.message)
        assertNull(viewModel.state.exportArmor)
    }

    @Test fun `folder extensions move mark and delete through the store`() {
        val store = FakeMailStore()
        val viewModel = vm(store)
        runBlocking {
            store.messages.upsert(stored("1"))
            store.messages.upsert(stored("2"))
            store.messages.upsert(stored("3", MailFolder.TRASH))
        }
        viewModel.moveToTrash(listOf("1"))
        await("move to trash") { runBlocking { store.messages.get("1")?.folder == MailFolder.TRASH } }
        viewModel.restoreFromTrash(listOf("1"))
        await("restore") { runBlocking { store.messages.get("1")?.folder == MailFolder.INBOX } }
        viewModel.moveTo(listOf("2"), MailFolder.SPAM)
        await("move to spam") { runBlocking { store.messages.get("2")?.folder == MailFolder.SPAM } }
        viewModel.setRead(listOf("1", "2"), true)
        await("set read") { runBlocking { store.messages.get("2")?.isRead == true } }
        viewModel.markRead("1")
        await("mark read") { runBlocking { store.messages.get("1")?.isRead == true } }
        viewModel.emptyTrash()
        await("empty trash") { runBlocking { store.messages.get("3") == null } }
        viewModel.deleteForever(listOf("1"))
        await("delete forever") { runBlocking { store.messages.get("1") == null } }
        assertEquals(listOf("2"), store.messages.snapshot().map { it.id })
    }

    @Test fun `envelopeOf parses recipients and carries envelope fields`() {
        val viewModel = vm()
        val env = viewModel.envelopeOf(stored("9", toJson = "a@x.org, b@x.org"))
        assertEquals("9", env.id)
        assertEquals("ada@example.org", env.from)
        assertEquals(listOf("a@x.org", "b@x.org"), env.to)
        assertEquals(100, env.dateMillis)
        assertEquals("9", env.messageId)
        assertFalse(env.unencrypted)
        val blank = viewModel.envelopeOf(stored("10", toJson = ""))
        assertEquals(emptyList(), blank.to)
    }

    @Test fun `resolveTrust returns cached keys and none for unparseable addresses`() = runBlocking {
        val store = FakeMailStore()
        store.keys.upsert(CachedKey("ada@example.org", "ARMOR", "FF00", "Wkd", 7))
        val viewModel = vm(store)
        val trust = viewModel.resolveTrust(listOf(" Ada@Example.ORG ", "not-an-address", ""))
        assertEquals(3, trust.size)
        assertEquals(
            KeyLookupPolicy.RecipientTrust("ada@example.org", true, KeyLookupPolicy.KeySource.Cached, "FF00"),
            trust[0],
        )
        assertEquals(KeyLookupPolicy.RecipientTrust("not-an-address", false, KeyLookupPolicy.KeySource.None), trust[1])
        assertEquals("", trust[2].address)
        assertFalse(trust[2].hasKey)
        assertEquals(emptyList(), viewModel.resolveTrust(emptyList()))
    }

    @Test fun `decryptStored ignores missing already-decrypted and unencrypted messages`() {
        val store = FakeMailStore()
        val viewModel = vm(store)
        runBlocking {
            store.messages.upsert(stored("done", body = "already plain"))
            store.messages.upsert(stored("plain", body = null).copy(unencrypted = true, decryptedBody = null))
        }
        viewModel.decryptStored("missing")
        viewModel.decryptStored("done")
        viewModel.decryptStored("plain")
        await("decrypt no-ops settle") {
            shadowOf(Looper.getMainLooper()).idle()
            viewModel.state.error == null && store.messages.storeDecryptedCalls.isEmpty()
        }
        assertNull(viewModel.state.error)
        assertTrue(store.messages.storeDecryptedCalls.isEmpty())
    }

    @Test fun `decryptStored on a locked blob without a stored passphrase surfaces an error`() {
        val store = FakeMailStore()
        val viewModel = vm(store)
        runBlocking {
            store.messages.upsert(
                stored("locked", body = null).copy(blobBase64 = "AA==", decryptedBody = null, decryptedSubject = null),
            )
        }
        viewModel.decryptStored("locked")
        await("decrypt failure error") { viewModel.state.error == "Could not decrypt this message." }
        assertTrue(store.messages.storeDecryptedCalls.isEmpty())
    }

    @Test fun `send without a set-up identity fails through onDone and records the error`() {
        val viewModel = vm()
        var done: Boolean? = null
        viewModel.send(to = listOf("ada@example.org"), subject = "Hi", body = "Body") { done = it }
        assertTrue(viewModel.state.busy)
        await("send failure") { done == false }
        assertEquals(false, done)
        assertFalse(viewModel.state.busy)
        assertTrue(viewModel.state.error != null)
    }

    @Test fun `retryOutbox while offline keeps entries and reports relay offline`() {
        val viewModel = vm()
        viewModel.retryOutbox()
        await("offline retry message") { viewModel.state.error == "Relay offline — kept in outbox." }
    }

    @Test fun `refresh mirrors the offline socket state`() {
        val viewModel = vm()
        viewModel.refresh()
        await("refresh settles") { !viewModel.state.socketConnected }
        assertFalse(viewModel.state.socketConnected)
    }

    @Test fun `setAliasDisabled and createAlias without a passphrase surface real state`() {
        val store = FakeMailStore()
        val viewModel = vm(store)
        runBlocking {
            store.identities.upsert(
                com.vayunmathur.securemail.data.MailIdentity(
                    "me@maemail.org", "", "pub", "AA", "sec", isPrimary = true, createdMillis = 1,
                ),
            )
            store.identities.upsert(
                com.vayunmathur.securemail.data.MailIdentity(
                    "alias@maemail.org", "", "pub", "BB", "sec", isPrimary = false, createdMillis = 2,
                ),
            )
        }
        viewModel.setAliasDisabled("alias@maemail.org", true)
        await("alias disabled") {
            runBlocking { store.identities.get("alias@maemail.org")?.disabled == true }
        }
        // createAlias needs the sealed S2K passphrase; none is stored in tests,
        // so it must fail with the recovery message, not silently succeed.
        viewModel.createAlias("Shopping")
        await("create alias passphrase error") {
            viewModel.state.error?.contains("No key passphrase stored") == true
        }
        assertFalse(viewModel.state.busy)
    }

    @Test fun `resolveTrust own domain without a socket or WKD key reports no key`() = runBlocking {
        // Uncached own-domain address: socket directory misses offline, then
        // the WKD fall-through finds nothing in the test environment.
        val viewModel = vm()
        val trust = viewModel.resolveTrust(listOf("someone@maemail.org"))
        assertEquals(1, trust.size)
        assertEquals("someone@maemail.org", trust.single().address)
        assertFalse(trust.single().hasKey)
        assertEquals(KeyLookupPolicy.KeySource.None, trust.single().source)
    }
}
