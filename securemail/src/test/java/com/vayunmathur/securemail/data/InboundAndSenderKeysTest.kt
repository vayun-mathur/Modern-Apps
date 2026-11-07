package com.vayunmathur.securemail.data

import com.vayunmathur.securemail.domain.pgp.PgpCrypto
import com.vayunmathur.securemail.network.MailSocket
import com.vayunmathur.securemail.platform.FakeMailStore
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class InboundAndSenderKeysTest {

    private fun mime(from: String, subject: String, body: String): String =
        "From: $from\r\nSubject: $subject\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n$body"

    private fun msg(
        id: String,
        raw: ByteArray,
        unencrypted: Boolean,
        spam: Boolean = false,
        deliveredTo: String = "",
    ) = MailSocket.InboundMsg(id, MailSocket.MsgFlags(unencrypted, spam), deliveredTo, raw)

    @Test fun unencryptedFrameParsesAndStores() = runBlocking {
        val store = FakeMailStore()
        InboundStore.store(
            store = store, identity = null, passphrase = null,
            senderKeyResolver = { null },
            msg = msg("m1", mime("alice@x.org", "Hi", "Body text").toByteArray(), unencrypted = true),
        )
        val stored = store.messageList.value.single()
        assertEquals("alice@x.org", stored.from)
        assertEquals("Hi", stored.decryptedSubject)
        assertEquals("Body text", stored.decryptedBody)
        assertTrue(stored.unencrypted)
        assertEquals(MailFolder.INBOX, stored.folder)
    }

    @Test fun spamVerdictRoutesToSpamFolder() = runBlocking {
        val store = FakeMailStore()
        InboundStore.store(
            store = store, identity = null, passphrase = null,
            senderKeyResolver = { null },
            msg = msg("m2", mime("s@x.org", "Junk", "spam").toByteArray(), unencrypted = true, spam = true),
        )
        assertEquals(MailFolder.SPAM, store.messageList.value.single().folder)
    }

    @Test fun encryptedFrameWithoutPassphrasePersistsAsLockedBlob() = runBlocking {
        val store = FakeMailStore()
        InboundStore.store(
            store = store, identity = null, passphrase = null,
            senderKeyResolver = { null },
            msg = msg("m3", byteArrayOf(1, 2, 3), unencrypted = false, deliveredTo = "me@maemail.org"),
        )
        val stored = store.messageList.value.single()
        assertEquals("", stored.from)
        assertNull(stored.decryptedBody)
        assertFalse(stored.unencrypted)
        assertTrue(stored.blobBase64.isNotEmpty())
        assertEquals("me@maemail.org", stored.deliveredTo)
    }

    @Test fun encryptedFrameDecryptsOnArrivalWithIdentityKey() = runBlocking {
        // Generate a real identity + encrypt a MIME message to it, then run
        // the full inbound path against the in-memory store.
        val identityKey = PgpCrypto.generateIdentity("me@maemail.org <me@maemail.org>", "passphrase-1")
        val identity = MailIdentity(
            address = "me@maemail.org", publicArmor = identityKey.armoredPublic,
            fingerprintHex = "F", secretArmor = identityKey.armoredSecret,
            isPrimary = true, createdMillis = 1,
        )
        val cipher = PgpCrypto.encrypt(mime("bob@x.org", "Secret", "hidden body").toByteArray(), identityKey.armoredPublic)
        val store = FakeMailStore()
        InboundStore.store(
            store = store, identity = identity, passphrase = "passphrase-1",
            senderKeyResolver = { null },
            msg = msg("m4", cipher, unencrypted = false, deliveredTo = "me@maemail.org"),
        )
        val stored = store.messageList.value.single()
        assertEquals("Secret", stored.decryptedSubject)
        assertEquals("hidden body", stored.decryptedBody)
    }

    @Test fun senderKeysCacheHitSkipsNetwork() = runBlocking {
        val store = FakeMailStore()
        store.keyList.value = listOf(CachedKey("bob@x.org", "ARMORED", "FP", "Wkd", 1))
        var networkCalls = 0
        val resolved = SenderKeys.resolve(
            store = store, address = " Bob@X.org ",
            socketKeyFetcher = { networkCalls++; null },
            wkdKeyFetcher = { networkCalls++; null },
        )
        assertEquals("ARMORED", resolved)
        assertEquals(0, networkCalls)
    }

    @Test fun senderKeysOwnDomainUsesSocketBeforeWkd() = runBlocking {
        val store = FakeMailStore()
        val key = PgpCrypto.generateIdentity("bob@maemail.org <bob@maemail.org>", "passphrase-1")
        val resolved = SenderKeys.resolve(
            store = store, address = "bob@maemail.org",
            socketKeyFetcher = { key.armoredPublic },
            wkdKeyFetcher = { error("WKD must not be consulted after a directory hit") },
        )
        assertEquals(key.armoredPublic, resolved)
        // The resolved key is cached for next time.
        assertEquals(key.armoredPublic, store.keyList.value.single().armoredPublic)
        assertEquals("OwnDirectory", store.keyList.value.single().source)
    }

    @Test fun senderKeysFallsBackToWkdAndHandlesEmpty() = runBlocking {
        val store = FakeMailStore()
        val key = PgpCrypto.generateIdentity("eve@x.org <eve@x.org>", "passphrase-1")
        val resolved = SenderKeys.resolve(
            store = store, address = "eve@x.org",
            socketKeyFetcher = { error("socket must not run for external domains") },
            wkdKeyFetcher = { url -> if (url.contains("openpgpkey")) key.armoredPublic else null },
        )
        assertEquals(key.armoredPublic, resolved)
        assertEquals("Wkd", store.keyList.value.single().source)
        assertNull(
            SenderKeys.resolve(store, "  ", socketKeyFetcher = { null }, wkdKeyFetcher = { null }),
        )
        assertNull(
            SenderKeys.resolve(store, "nobody@x.org", socketKeyFetcher = { null }, wkdKeyFetcher = { null }),
        )
    }
}
