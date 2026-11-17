package com.vayunmathur.findfamily.tracker
import com.vayunmathur.findfamily.domain.PoweredOffRecovery
import com.vayunmathur.findfamily.domain.RecoveryKeypair
import com.vayunmathur.findfamily.domain.poweredOffGrantSigningBytes

import com.vayunmathur.e2ee.E2eeKeyStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for who can decrypt a powered-off phone's sightings.
 *
 * This is the security boundary of the whole feature, and every property it has is a property of
 * the roster arithmetic rather than of the crypto: the ML-KEM library is assumed to work, but
 * "default nobody", "grant does not widen past the named peer", "revoke actually rotates" and
 * "the identity key is never touched" are all decisions made here and are all silent when wrong.
 * A grant that leaked to the whole roster would look identical in the UI to a correct one.
 *
 * [PoweredOffRecovery] takes its keypair source as a parameter precisely so this file can run
 * without the native ML-KEM library, which a JVM unit test cannot load.
 */
class PoweredOffRecoveryTest {

    /**
     * In-memory [E2eeKeyStore] that also records every key it is asked for, so a test can assert
     * on what was *not* touched.
     */
    private class FakeKeyStore : E2eeKeyStore {
        val values = HashMap<String, ByteArray>()
        val namesTouched = LinkedHashSet<String>()

        override suspend fun getBytes(name: String): ByteArray? {
            namesTouched += name
            return values[name]
        }

        override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) {
            namesTouched += name
            if (onlyIfAbsent && values.containsKey(name)) return
            values[name] = value
        }
    }

    /** Distinct, deterministic, obviously-fake keypairs. Counts how many times it was called. */
    private class CountingMint {
        var calls = 0
            private set

        operator fun invoke(): RecoveryKeypair {
            calls++
            return RecoveryKeypair(
                publicBundle = "pub-$calls".encodeToByteArray(),
                kemPrivate = "priv-$calls".encodeToByteArray(),
            )
        }
    }

    private val ks = FakeKeyStore()
    private val mint = CountingMint()
    private val recovery = PoweredOffRecovery(ks, mint::invoke)

    // --- default state -------------------------------------------------------------------

    @Test
    fun nobodyIsGrantedByDefault() = runBlocking {
        // The single most important assertion in this file. The feature is useless without a
        // grant and dangerous if one appears on its own, so "off" has to be the state you get
        // for free rather than the state you have to arrange.
        assertEquals(emptySet<Long>(), recovery.grantees())
        assertFalse(recovery.isGranted(1234L))
        assertEquals(0, mint.calls, "no keypair is minted just by asking who can read")
    }

    @Test
    fun enablingTheBeaconDoesNotEntitleAnyone() = runBlocking {
        recovery.ensure()
        assertEquals(emptySet<Long>(), recovery.grantees())
    }

    // --- the identity key is out of bounds -----------------------------------------------

    @Test
    fun neverReadsOrWritesTheIdentityPrivateKey() = runBlocking {
        // The whole point of a separate recovery keypair: `ff_pqcKemPriv` decrypts every live
        // location this user publishes to anyone, forever, so it must never be what gets shared.
        // Asserting on the key *names* catches a future edit that quietly reaches for it again,
        // which reviewing a diff for `Pqc.decrypt` call sites would not.
        recovery.ensure()
        recovery.grant(11L)
        recovery.revoke(11L)
        recovery.clear()

        assertFalse("ff_pqcKemPriv" in ks.namesTouched, "identity private key must be untouchable")
        assertTrue(
            ks.namesTouched.all { it.startsWith("ff_pofrec_") },
            "recovery state must stay in its own namespace, saw ${ks.namesTouched}",
        )
        // And nothing it stores may be the identity key by value either.
        assertFalse(ks.values.values.any { it.decodeToString() == "ff_pqcKemPriv" })
    }

    @Test
    fun recoveryKeysDoNotCollideWithThePerPeerSightingStore() {
        // PoweredOffKeyStore owns `ff_pof_secret_$id` / `ff_pof_priv_$id` in the same DataStore.
        // A prefix clash would have one silently overwrite the other, and the symptom would be
        // zero sightings with no error anywhere — the failure this feature keeps producing.
        val peerId = 42L
        assertFalse("ff_pofrec_priv".startsWith("ff_pof_priv_$peerId"))
        assertFalse("ff_pof_priv_$peerId".startsWith("ff_pofrec_"))
        assertFalse("ff_pof_secret_$peerId".startsWith("ff_pofrec_"))
    }

    // --- grant ---------------------------------------------------------------------------

    @Test
    fun grantAddsOnlyTheNamedPeer() = runBlocking {
        val d = recovery.grant(7L)
        assertEquals(setOf(7L), recovery.grantees())
        // Addressed to the new grantee alone. If this ever returned the whole roster it would
        // still "work", so only a test says otherwise.
        assertEquals(setOf(7L), d.to)
        assertEquals(null, d.revoked)
    }

    @Test
    fun grantingASecondPeerLeavesTheFirstAloneAndDoesNotRotate() = runBlocking {
        val first = recovery.grant(7L)
        val second = recovery.grant(8L)

        assertEquals(setOf(7L, 8L), recovery.grantees())
        // Same key: the existing grantee is entitled to it, so churning it would cost them a
        // redelivery for nothing.
        assertTrue(first.kemPrivate.contentEquals(second.kemPrivate))
        assertTrue(first.publicBundle.contentEquals(second.publicBundle))
        assertEquals(first.epoch, second.epoch)
        assertEquals(1, mint.calls)
        // The second grant is not addressed to the first peer.
        assertEquals(setOf(8L), second.to)
    }

    @Test
    fun regrantingTheSamePeerIsIdempotentButStillRedelivers() = runBlocking {
        recovery.grant(7L)
        val again = recovery.grant(7L)
        assertEquals(setOf(7L), recovery.grantees())
        // The ordinary reason to re-grant is that the first delivery never arrived, so returning
        // nothing to send would make the retry a no-op.
        assertEquals(setOf(7L), again.to)
        assertEquals(1, mint.calls, "re-granting must not rotate")
    }

    @Test
    fun grantDistributesThePrivateHalfAndTheMatchingPublicHalf() = runBlocking {
        val d = recovery.grant(7L)
        val keys = recovery.ensure()
        assertTrue(d.kemPrivate.contentEquals(keys.kemPrivate))
        assertTrue(d.publicBundle.contentEquals(keys.publicBundle))
        assertEquals(1L, d.epoch, "first keypair is epoch 1")
    }

    @Test
    fun peerZeroIsRejected() = runBlocking {
        // 0 is the "no identity yet" sentinel used throughout Networking; granting to it would
        // file the key under a userid no peer can ever be.
        assertFailsWith<IllegalArgumentException> { runBlocking { recovery.grant(0L) } }
        assertEquals(emptySet<Long>(), recovery.grantees())
    }

    // --- revoke and rotation -------------------------------------------------------------

    @Test
    fun revokeRemovesThePeerAndRotatesTheKeypair() = runBlocking {
        val granted = recovery.grant(7L)
        val revoked = recovery.revoke(7L)

        assertEquals(emptySet<Long>(), recovery.grantees())
        assertFalse(recovery.isGranted(7L))
        assertEquals(2, mint.calls, "revoke must rotate")
        assertFalse(
            granted.kemPrivate.contentEquals(revoked.kemPrivate),
            "the revoked peer's key must not still be the live one",
        )
    }

    @Test
    fun afterRevokeFindersSealToAKeyTheRevokedPeerDoesNotHave() = runBlocking {
        // The property revocation actually buys, stated exactly: the public half the relay will
        // hand to finders from now on is not the one whose private half was handed out.
        val granted = recovery.grant(7L)
        val afterRevoke = recovery.revoke(7L)
        assertFalse(granted.publicBundle.contentEquals(afterRevoke.publicBundle))
        assertTrue(afterRevoke.publicBundle.contentEquals(recovery.ensure().publicBundle))
    }

    @Test
    fun revokeRedistributesToTheRemainingGranteesAndNotToTheRevokedPeer() = runBlocking {
        recovery.grant(7L)
        recovery.grant(8L)
        recovery.grant(9L)

        val d = recovery.revoke(8L)

        assertEquals(setOf(7L, 9L), recovery.grantees())
        // Rotating without redelivering would revoke the innocent peers too, and they would keep
        // polling with a dead key and finding nothing — silently, like everything else here.
        assertEquals(setOf(7L, 9L), d.to)
        assertFalse(8L in d.to)
        assertEquals(8L, d.revoked)
    }

    @Test
    fun revokingTheLastGranteeStillRotatesAndAddressesNobody() = runBlocking {
        recovery.grant(7L)
        val d = recovery.revoke(7L)
        assertEquals(emptySet<Long>(), d.to)
        assertEquals(7L, d.revoked)
        assertEquals(2, mint.calls)
    }

    @Test
    fun revokingSomeoneWhoWasNeverGrantedStillRotates() = runBlocking {
        recovery.grant(7L)
        val d = recovery.revoke(999L)
        // Documented behaviour: the caller reached for revoke. A spare rotation costs one
        // registration; second-guessing them could leave a key live that they believe is dead.
        assertEquals(2, mint.calls)
        assertEquals(setOf(7L), recovery.grantees())
        assertEquals(setOf(7L), d.to)
    }

    @Test
    fun epochIsMonotonicAcrossRotations() = runBlocking {
        assertEquals(0L, recovery.epoch(), "no keypair yet")
        recovery.ensure()
        assertEquals(1L, recovery.epoch())
        recovery.grant(7L)
        assertEquals(1L, recovery.epoch(), "granting does not rotate")
        assertEquals(2L, recovery.revoke(7L).epoch)
        assertEquals(3L, recovery.revoke(8L).epoch)
        // A peer must be able to reject a stale grant that overtakes a fresh one on the wire.
        assertEquals(3L, recovery.epoch())
    }

    // --- clear / persistence ---------------------------------------------------------------

    @Test
    fun clearForgetsTheRosterSoTurningTheBeaconBackOnDoesNotReEntitleAnyone() = runBlocking {
        recovery.grant(7L)
        recovery.grant(8L)
        recovery.clear()

        assertEquals(emptySet<Long>(), recovery.grantees())
        // A blanked keypair must be re-minted, not read back as a zero-length private key.
        val fresh = recovery.ensure()
        assertEquals(2, mint.calls)
        assertTrue(fresh.kemPrivate.isNotEmpty())
        assertEquals(emptySet<Long>(), recovery.grantees())
    }

    @Test
    fun rosterAndKeypairSurviveANewInstanceOverTheSameStore() = runBlocking {
        recovery.grant(7L)
        val original = recovery.ensure()

        val reopened = PoweredOffRecovery(ks, mint::invoke)
        assertEquals(setOf(7L), reopened.grantees())
        assertTrue(reopened.ensure().kemPrivate.contentEquals(original.kemPrivate))
        assertEquals(1, mint.calls, "reopening must not mint a second keypair")
    }

    @Test
    fun rosterRoundTripsManyPeers() = runBlocking {
        val peers = setOf(1L, -5L, Long.MAX_VALUE, 987654321L)
        for (p in peers) recovery.grant(p)
        assertEquals(peers, PoweredOffRecovery(ks, mint::invoke).grantees())
        // Negative ids are real: userids are signed longs on this side of the wire.
        assertTrue(recovery.grantees().isNotEmpty())
    }

    // --- what a grant is signed over -------------------------------------------------------

    @Test
    fun signingBytesBindTheOwnerTheRecipientAndTheEpoch() {
        val secret = ByteArray(32) { 1 }
        val priv = ByteArray(8) { 2 }
        val base = poweredOffGrantSigningBytes(10L, 20L, 3L, secret, priv)

        // Owner: without this, a peer could deliver keys claiming to be a different family
        // member, and every sighting decrypted from them would be drawn as that person's phone.
        assertFalse(base.contentEquals(poweredOffGrantSigningBytes(11L, 20L, 3L, secret, priv)))
        // Recipient: without this, a grant lifted off one peer replays at another.
        assertFalse(base.contentEquals(poweredOffGrantSigningBytes(10L, 21L, 3L, secret, priv)))
        // Epoch: without this, a signature from an old grant re-authenticates a rolled-back one.
        assertFalse(base.contentEquals(poweredOffGrantSigningBytes(10L, 20L, 4L, secret, priv)))
        // And the key material itself, obviously.
        assertFalse(base.contentEquals(poweredOffGrantSigningBytes(10L, 20L, 3L, secret, ByteArray(8) { 9 })))
        assertFalse(base.contentEquals(poweredOffGrantSigningBytes(10L, 20L, 3L, ByteArray(32) { 9 }, priv)))
    }

    @Test
    fun signingBytesAreDeterministicAndDomainSeparated() {
        val secret = ByteArray(4) { 1 }
        val priv = ByteArray(4) { 2 }
        val a = poweredOffGrantSigningBytes(10L, 20L, 3L, secret, priv)
        assertTrue(a.contentEquals(poweredOffGrantSigningBytes(10L, 20L, 3L, secret, priv)))
        // A signature over these bytes must not be reusable as a signature over anything else
        // this identity signs.
        assertTrue(a.decodeToString().startsWith("ffpofgrant1"))
    }

    @Test
    fun signingBytesCannotBeConfusedByShiftingTheFieldBoundary() {
        // The two variable-length fields are separated by an explicit length, so the same bytes
        // split differently between secret and key cannot produce the same signing input. Without
        // that prefix these two would be byte-identical and one signature would cover both.
        val a = poweredOffGrantSigningBytes(1L, 2L, 3L, ByteArray(4) { 7 }, ByteArray(8) { 7 })
        val b = poweredOffGrantSigningBytes(1L, 2L, 3L, ByteArray(8) { 7 }, ByteArray(4) { 7 })
        assertEquals(a.size, b.size)
        assertFalse(a.contentEquals(b))
    }
}
