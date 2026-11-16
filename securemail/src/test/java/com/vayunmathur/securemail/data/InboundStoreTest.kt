package com.vayunmathur.securemail.data

import android.util.Base64
import com.vayunmathur.securemail.domain.mail.MimeCodec
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

/** [InboundStore] core over a [FakeMailStore]: plain, spam, locked and decrypted arrivals. */
@RunWith(RobolectricTestRunner::class)
class InboundStoreTest {
    private val me = PgpCrypto.generateIdentity("Me <me@maemail.org>", "my-pass")
    private val ada = PgpCrypto.generateIdentity("Ada <ada@example.org>", "ada-pass")
    private val identity = MailIdentity(
        address = "me@maemail.org", publicArmor = me.armoredPublic, fingerprintHex = me.fingerprintHex,
        secretArmor = me.armoredSecret, isPrimary = true, createdMillis = 1,
    )

    private fun msg(id: String, raw: ByteArray, plain: Boolean = false, spam: Boolean = false, to: String = "me@maemail.org") =
        MailSocket.InboundMsg(id, MailSocket.MsgFlags(unencrypted = plain, spam = spam), to, raw)

    private fun store(
        store: FakeMailStore,
        m: MailSocket.InboundMsg,
        id: MailIdentity? = identity,
        pass: () -> String? = { "my-pass" },
        resolver: suspend (String) -> String? = { null },
    ) = runBlocking { InboundStore.store(m, store, id, pass, resolver) }

    @Test fun `unencrypted frame parses mime and lands in inbox`() {
        val store = FakeMailStore()
        val raw = MimeCodec.buildPlainBody("ada@example.org", "Hello there", "Plain body", "m1@maemail.org")
        store(store, msg("u1", raw, plain = true))
        val saved = store.messages.snapshot().single()
        assertEquals("ada@example.org", saved.from)
        assertEquals("Hello there", saved.decryptedSubject)
        assertEquals("Plain body", saved.decryptedBody?.trim())
        assertEquals("", saved.blobBase64)
        assertTrue(saved.unencrypted)
        assertEquals(MailFolder.INBOX, saved.folder)
        assertEquals("me@maemail.org", saved.deliveredTo)
        assertEquals("me@maemail.org", saved.toJson)
    }

    @Test fun `unencrypted spam flag routes to spam and html is kept`() {
        val store = FakeMailStore()
        val raw = "From: a@x.org\r\nSubject: Buy\r\nContent-Type: text/html; charset=utf-8\r\n\r\n<p>Deal</p>".toByteArray()
        store(store, msg("u2", raw, plain = true, spam = true))
        val saved = store.messages.snapshot().single()
        assertEquals(MailFolder.SPAM, saved.folder)
        assertEquals("<p>Deal</p>", saved.decryptedHtml)
        assertEquals("Deal", saved.decryptedBody)
    }

    @Test fun `blank deliveredTo falls back to identity address or empty`() {
        val withId = FakeMailStore()
        val raw = MimeCodec.buildPlainBody("a@x.org", "S", "B", "m@x")
        store(withId, msg("u3", raw, plain = true, to = ""))
        assertEquals("me@maemail.org", withId.messages.snapshot().single().deliveredTo)
        val noId = FakeMailStore()
        store(noId, msg("u4", raw, plain = true, to = ""), id = null)
        assertEquals("", noId.messages.snapshot().single().deliveredTo)
    }

    @Test fun `encrypted frame without passphrase or identity stays locked`() {
        val raw = PgpCrypto.encryptSigned(
            MimeCodec.buildPlainBody("ada@example.org", "Secret", "Hidden", "m@x"),
            me.armoredPublic, ada.armoredSecret, "ada-pass",
        )
        val noPass = FakeMailStore()
        store(noPass, msg("e1", raw, spam = true), pass = { null })
        val locked = noPass.messages.snapshot().single()
        assertEquals("", locked.from)
        assertNull(locked.decryptedBody)
        assertEquals(MailFolder.SPAM, locked.folder)
        assertEquals(Base64.encodeToString(raw, Base64.NO_WRAP), locked.blobBase64)
        val noIdentity = FakeMailStore()
        store(noIdentity, msg("e2", raw), id = null)
        assertNull(noIdentity.messages.snapshot().single().decryptedBody)
        val throwing = FakeMailStore()
        store(throwing, msg("e3", raw), pass = { throw IllegalStateException("keystore gone") })
        assertNull(throwing.messages.snapshot().single().decryptedBody)
    }

    @Test fun `encrypted frame decrypts on arrival when sender key is unknown`() {
        val store = FakeMailStore()
        val raw = PgpCrypto.encryptSigned(
            MimeCodec.buildPlainBody("ada@example.org", "Arrival", "Decrypted at ingest", "m@x"),
            me.armoredPublic, ada.armoredSecret, "ada-pass",
        )
        store(store, msg("e4", raw))
        val saved = store.messages.snapshot().single()
        assertEquals("Arrival", saved.decryptedSubject)
        assertEquals("Decrypted at ingest", saved.decryptedBody?.trim())
        assertEquals("ada@example.org", saved.from)
        assertNull(saved.verifiedSigner)
        assertTrue(!saved.unencrypted)
    }

    @Test fun `encrypted frame verifies when the resolver returns the sender key`() {
        val store = FakeMailStore()
        val raw = PgpCrypto.encryptSigned(
            MimeCodec.buildPlainBody("ada@example.org", "Signed", "Verified body", "m@x"),
            me.armoredPublic, ada.armoredSecret, "ada-pass",
        )
        val seen = mutableListOf<String>()
        store(store, msg("e5", raw), resolver = { seen += it; ada.armoredPublic })
        assertEquals(listOf("ada@example.org"), seen)
        val saved = store.messages.snapshot().single()
        assertEquals("Verified body", saved.decryptedBody?.trim())
        assertEquals(ada.fingerprintHex, saved.verifiedSigner)
    }

    @Test fun `armored envelope decrypts and undecryptable blob stays locked`() {
        val store = FakeMailStore()
        val inner = PgpCrypto.encrypt(
            MimeCodec.buildPlainBody("ada@example.org", "Armored", "Envelope body", "m@x"), me.armoredPublic,
        )
        val envelope = MimeCodec.buildEncryptedEnvelope(PgpCrypto.armorMessage(inner))
        store(store, msg("e6", envelope))
        assertEquals("Envelope body", store.messages.snapshot().single().decryptedBody?.trim())
        val bad = FakeMailStore()
        store(bad, msg("e7", byteArrayOf(1, 2, 3, 4)))
        val locked = bad.messages.snapshot().single()
        assertNull(locked.decryptedBody)
        assertEquals(Base64.encodeToString(byteArrayOf(1, 2, 3, 4), Base64.NO_WRAP), locked.blobBase64)
    }
}
