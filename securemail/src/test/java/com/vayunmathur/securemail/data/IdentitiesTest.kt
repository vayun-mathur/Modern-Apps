package com.vayunmathur.securemail.data

import com.vayunmathur.securemail.domain.pgp.PgpCrypto
import com.vayunmathur.securemail.platform.FakeIdentityDao
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [Identities] DAO-level cores: migration backfill, delivery lookup, SUB signing. */
class IdentitiesTest {
    private fun identity(address: String, primary: Boolean = false, created: Long = 1, secret: String = "sec") =
        MailIdentity(address, "", "pub-$address", "FP-$address", secret, isPrimary = primary, createdMillis = created)

    @Test fun `ensureMigrated backfills primary only when configured with a secret`() = runBlocking {
        val dao = FakeIdentityDao()
        val settings = MailSettings("me@maemail.org", "pub-armor", "AABB")
        Identities.ensureMigrated(dao, MailSettings()) { "secret-armor" }
        assertEquals(0, dao.count())
        Identities.ensureMigrated(dao, settings) { null }
        assertEquals(0, dao.count())
        Identities.ensureMigrated(dao, settings) { throw IllegalStateException("keystore gone") }
        assertEquals(0, dao.count())
        Identities.ensureMigrated(dao, settings) { "secret-armor" }
        assertEquals(1, dao.count())
        val primary = dao.primary()!!
        assertEquals("me@maemail.org", primary.address)
        assertEquals("pub-armor", primary.publicArmor)
        assertEquals("secret-armor", primary.secretArmor)
        assertTrue(primary.isPrimary)
        assertEquals(0L, primary.createdMillis)
        // Idempotent: a second run with different settings changes nothing.
        Identities.ensureMigrated(dao, MailSettings("other@maemail.org", "x", "y")) { "z" }
        assertEquals("me@maemail.org", dao.primary()?.address)
    }

    @Test fun `forDelivery matches lowercased address and falls back to primary on a miss`() = runBlocking {
        val dao = FakeIdentityDao()
        dao.upsert(identity("me@maemail.org", primary = true))
        dao.upsert(identity("alias@maemail.org", created = 2))
        assertEquals("alias@maemail.org", Identities.forDelivery(dao, "ALIAS@maemail.org")?.address)
        assertEquals("me@maemail.org", Identities.forDelivery(dao, null)?.address)
        // A named-but-unknown delivery address falls back to the primary.
        assertEquals("me@maemail.org", Identities.forDelivery(dao, "ghost@maemail.org")?.address)
        assertEquals("me@maemail.org", Identities.forDelivery(dao, "")?.address)
        val empty = FakeIdentityDao()
        assertNull(Identities.forDelivery(empty, null))
    }

    @Test fun `sign returns a detached signature and null on every missing input`() {
        val key = PgpCrypto.generateIdentity("Me <me@maemail.org>", "my-pass")
        val me = identity("me@maemail.org", primary = true, secret = key.armoredSecret)
        val payload = "challenge-bytes".toByteArray()
        val sig = Identities.sign(me, "my-pass", payload)
        assertTrue(sig != null && sig.isNotEmpty())
        assertContentEquals(sig, Identities.sign(me, "my-pass", payload))
        assertNull(Identities.sign(null, "my-pass", payload))
        assertNull(Identities.sign(me, null, payload))
        assertNull(Identities.sign(me, "wrong-pass", payload))
        assertNull(Identities.sign(me.copy(secretArmor = "not a key"), "my-pass", payload))
    }
}
