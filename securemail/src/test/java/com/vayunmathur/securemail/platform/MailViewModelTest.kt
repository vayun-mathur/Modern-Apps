package com.vayunmathur.securemail.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.securemail.FakeAndroidKeyStore
import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.data.MailIdentity
import com.vayunmathur.securemail.data.StoredMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MailViewModelTest {

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun msg(id: String, folder: MailFolder = MailFolder.INBOX, read: Boolean = false) =
        StoredMessage(id = id, from = "a@x.org", toJson = "[]", dateMillis = 1000, messageId = id, blobBase64 = "", isRead = read, folder = folder)

    private fun vm(store: FakeMailStore = FakeMailStore()) = MailViewModel(app, store)

    /** IO work lands on Dispatchers.IO; wait for the observable effect. */
    private fun awaitTrue(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("timed out waiting for: $description")
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test fun draftRecipientsLifecycle() {
        val v = vm()
        v.seedDraft(to = listOf(" A@x.org ", "", "a@x.org"), cc = listOf("c@x.org"), from = "me@maemail.org")
        // Seed keeps blanks out but does not lowercase (chips canonicalize on add).
        assertEquals(listOf(" A@x.org ", "a@x.org"), v.draftTo)
        assertEquals("me@maemail.org", v.draftFrom)

        assertTrue(v.addDraftRecipient(0, "New@X.org"))
        assertEquals("new@x.org", v.draftTo.last())
        assertFalse(v.addDraftRecipient(0, "new@x.org")) // duplicate
        assertFalse(v.addDraftRecipient(0, "not-an-address"))
        assertTrue(v.addDraftRecipient(1, "cc@x.org"))
        assertTrue(v.addDraftRecipient(2, "bcc@x.org"))
        v.removeDraftRecipient(1, "c@x.org")
        assertEquals(listOf("cc@x.org"), v.draftCc)
        v.removeDraftRecipient(1, "cc@x.org")
        assertEquals(emptyList(), v.draftCc)
        v.clearDraft()
        assertEquals(emptyList(), v.draftTo)
        assertNull(v.draftFrom)
    }

    @Test fun folderSelectionAndMessageActions() {
        val store = FakeMailStore(messages = listOf(msg("1"), msg("2"), msg("3", MailFolder.TRASH)))
        val v = vm(store)
        assertEquals(MailFolder.INBOX, v.selectedFolder)
        v.selectFolder(MailFolder.SPAM)
        assertEquals(MailFolder.SPAM, v.selectedFolder)

        v.moveToTrash(listOf("1"))
        awaitTrue("move to trash") { store.messageList.value.first { it.id == "1" }.folder == MailFolder.TRASH }
        v.restoreFromTrash(listOf("1"))
        awaitTrue("restore") { store.messageList.value.first { it.id == "1" }.folder == MailFolder.INBOX }
        v.setRead(listOf("2"), true)
        awaitTrue("set read") { store.messageList.value.first { it.id == "2" }.isRead }
        v.markRead("1")
        awaitTrue("mark read") { store.messageList.value.first { it.id == "1" }.isRead }
        v.emptyTrash()
        awaitTrue("empty trash") { store.messageList.value.none { it.folder == MailFolder.TRASH } }
        awaitTrue("trash message") { v.state.message == "Trash emptied." }
        v.deleteForever(listOf("2"))
        awaitTrue("delete forever") { store.messageList.value.none { it.id == "2" } }
    }

    @Test fun flowsExposeStoreContents() = runBlocking {
        val identity = MailIdentity(
            address = "me@maemail.org", publicArmor = "pub", fingerprintHex = "ABCD",
            secretArmor = "sec", isPrimary = true, createdMillis = 1,
        )
        val store = FakeMailStore(messages = listOf(msg("1")), identities = listOf(identity))
        val v = vm(store)
        assertEquals(listOf("1"), v.messages.first().map { it.id })
        assertEquals(listOf("me@maemail.org"), v.identities.first().map { it.address })
        assertEquals(0, v.outboxCount.first())
        v.selectFolder(MailFolder.INBOX)
        assertEquals(listOf("1"), v.folderMessages.first().map { it.id })
    }

    @Test fun claimAddressValidationErrors() {
        val v = vm()
        v.claimAddress("ab", "long-passphrase")
        assertTrue(v.state.error!!.contains("3–64"))
        v.claimAddress("valid.name", "short")
        assertTrue(v.state.error!!.contains("at least 8"))
        assertFalse(v.state.busy)
    }

    @Test fun unlockIfReadyWithoutIdentityUnlocks() {
        val v = vm()
        var unlocked = false
        var locked = false
        // No FragmentActivity needed on the no-identity path.
        v.unlockIfReady(
            activity = org.robolectric.Robolectric.buildActivity(androidx.fragment.app.FragmentActivity::class.java).create().get(),
            onUnlocked = { unlocked = true },
            onLocked = { locked = true },
        )
        assertTrue(unlocked)
        assertFalse(locked)
        assertFalse(v.state.locked)
    }

    @Test fun aliasDisableWritesThrough() {
        val alias = MailIdentity(
            address = "alias@maemail.org", publicArmor = "p", fingerprintHex = "F",
            secretArmor = "s", isPrimary = false, createdMillis = 2,
        )
        val store = FakeMailStore(identities = listOf(alias))
        val v = vm(store)
        v.setAliasDisabled("alias@maemail.org", true)
        awaitTrue("alias disabled") { store.identityList.value.single().disabled }
    }

    @Test fun sendToCachedKeyRecipientQueuesWhileOffline() {
        // Sender identity + sealed passphrase, recipient key cached in the
        // vault: trust resolves from cache (no network), encryption runs
        // for real, publish fails offline, so the message must queue.
        val senderKey = com.vayunmathur.securemail.domain.pgp.PgpCrypto
            .generateIdentity("me@maemail.org <me@maemail.org>", "passphrase-1")
        val recipientKey = com.vayunmathur.securemail.domain.pgp.PgpCrypto
            .generateIdentity("bob@x.org <bob@x.org>", "passphrase-2")
        val store = FakeMailStore(
            identities = listOf(
                MailIdentity(
                    address = "me@maemail.org", publicArmor = senderKey.armoredPublic,
                    fingerprintHex = senderKey.fingerprintHex, secretArmor = senderKey.armoredSecret,
                    isPrimary = true, createdMillis = 1,
                ),
            ),
        )
        store.keyList.value = listOf(
            com.vayunmathur.securemail.data.CachedKey(
                "bob@x.org", recipientKey.armoredPublic, recipientKey.fingerprintHex, "Wkd", 1,
            ),
        )
        com.vayunmathur.securemail.platform.crypto.KeyManager(app).storeKeyPassphrase("passphrase-1")

        val v = vm(store)
        var done: Boolean? = null
        v.send(to = listOf("bob@x.org"), subject = "Hi Bob", body = "secret body") { done = it }
        awaitTrue("send completes") { done != null }
        assertEquals(true, done)
        awaitTrue("queued") { store.outboxList.value.size == 1 }
        assertTrue(v.state.message!!.contains("Queued"))
        // Sent-folder copy is stored as our own composition.
        awaitTrue("sent copy") { store.messageList.value.any { it.folder == MailFolder.SENT } }
    }

    @Test fun sendWithoutIdentityFails() {
        val v = vm()
        var done: Boolean? = null
        v.send(to = listOf("bob@x.org"), subject = "Hi", body = "body") { done = it }
        awaitTrue("send fails") { done != null }
        assertEquals(false, done)
        assertTrue(v.state.error!!.contains("Set up your address first"))
    }

    @Test fun decryptStoredDecryptsWithStoredIdentity() {
        // Encrypt a MIME message to the stored identity, save it as a
        // locked blob, and let the ViewModel decrypt it in memory. The
        // MIME has no From, so no sender-key network resolution happens.
        val key = com.vayunmathur.securemail.domain.pgp.PgpCrypto
            .generateIdentity("me@maemail.org <me@maemail.org>", "passphrase-1")
        val mime = "Subject: Locked\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nopen body"
        val cipher = com.vayunmathur.securemail.domain.pgp.PgpCrypto
            .encrypt(mime.toByteArray(), key.armoredPublic)
        val blob = android.util.Base64.encodeToString(cipher, android.util.Base64.NO_WRAP)
        val store = FakeMailStore(
            messages = listOf(msg("lock1").copy(blobBase64 = blob, deliveredTo = "me@maemail.org")),
            identities = listOf(
                MailIdentity(
                    address = "me@maemail.org", publicArmor = key.armoredPublic,
                    fingerprintHex = key.fingerprintHex, secretArmor = key.armoredSecret,
                    isPrimary = true, createdMillis = 1,
                ),
            ),
        )
        com.vayunmathur.securemail.platform.crypto.KeyManager(app).storeKeyPassphrase("passphrase-1")
        val v = vm(store)
        v.decryptStored("lock1")
        awaitTrue("decrypted") { store.messageList.value.single().decryptedBody != null }
        assertEquals("open body", store.messageList.value.single().decryptedBody)
        assertEquals("Locked", store.messageList.value.single().decryptedSubject)
    }

    @Test fun decryptStoredSkipsAlreadyDecryptedAndMissing() {
        val store = FakeMailStore(messages = listOf(msg("1").copy(decryptedBody = "hi", unencrypted = true)))
        val v = vm(store)
        // Must complete without throwing (no passphrase configured).
        v.decryptStored("1")
        v.decryptStored("missing")
        Thread.sleep(200)
        assertEquals("hi", store.messageList.value.single().decryptedBody)
    }
}
