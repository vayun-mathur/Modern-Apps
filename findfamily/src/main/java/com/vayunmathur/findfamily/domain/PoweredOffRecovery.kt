package com.vayunmathur.findfamily.domain

import com.vayunmathur.e2ee.E2eeKeyStore
import com.vayunmathur.e2ee.Pqc

/**
 * An ML-KEM keypair used for nothing but powered-off sightings.
 *
 * [publicBundle] is an ordinary `Pqc` public bundle with an **empty ML-DSA half**, the same shape
 * `Pqc.generateLinkKey` produces. That is not a shortcut: a key we hand to other people should be
 * able to receive a sighting and nothing else, and a bundle with no signing half cannot be used to
 * sign anything in this user's name even by a peer who holds the private side.
 *
 * [kemPrivate] is the **raw ML-KEM private DER**, matching what [PoweredOffKeyStore] stores and
 * what `Pqc.decrypt` expects — deliberately not the composite `[4B kemPrivLen][kemPriv][dsaPriv]`
 * that the word "bundle" means elsewhere in this package. See the [PoweredOffKeyStore] KDoc for
 * the bug that naming cost last time.
 */
class RecoveryKeypair(val publicBundle: ByteArray, val kemPrivate: ByteArray)

/**
 * The work a [PoweredOffRecovery] roster change leaves for the caller to actually send.
 *
 * Returned rather than sent here so the roster is decidable without a socket, a `Context` or the
 * native crypto library — which is what makes grant/revoke unit-testable, and this is the code
 * where being able to test the exact set of recipients matters most.
 */
class RecoveryDistribution(
    /** Rotation counter of [kemPrivate]. Monotonic; a peer must ignore a lower one. */
    val epoch: Long,
    /** The private half to seal individually to each peer in [to]. Never leaves the device in clear. */
    val kemPrivate: ByteArray,
    /** The public half to re-register with the relay so finders seal to it. */
    val publicBundle: ByteArray,
    /** Every peer that should hold the current key, not only the one that just changed. */
    val to: Set<Long>,
    /** Peer to ask to drop its copy. Advisory — see [PoweredOffRecovery]. */
    val revoked: Long? = null,
)

/**
 * Who, besides this device, can decrypt this device's powered-off sightings — and the dedicated
 * keypair that makes answering "somebody" possible without handing out the identity key.
 *
 * ## Why a separate keypair exists at all
 * A powered-off phone cannot decrypt its own sightings; it is off, and by the time it is back on
 * the finding is over. So somebody else has to hold a key. The obvious key is the one the beacon
 * used to use — `ff_pqcKemPriv`, the device's ML-KEM identity private key — and handing that to a
 * family member would let them decrypt **every** live location this user ever publishes, to
 * anyone, forever. That is not a trade worth making for a recovery convenience, so sightings are
 * sealed to a key that can open sightings and nothing else. Compromising it costs the user their
 * powered-off location history; it does not cost them their live location channel.
 *
 * ## What this class will not do
 * The roster starts empty and only ever grows by an explicit [grant]. There is deliberately no
 * "share with everyone I already share location with": the existing roster is a list of people
 * allowed to see where the user is *now*, which is revocable in practice by turning sharing off.
 * A recovery key is not revocable in that sense, so consent for one is not consent for the other.
 *
 * ## Two things rotation cannot undo, which the UI must say out loud
 *  - A grantee also needs the beacon secret, because [PoweredOffProtocol.recentHandles] is what
 *    tells them which handles to fetch. Holding it lets them derive this device's EIDs and track
 *    the powered-off phone themselves for as long as the keypair lives. For a person the user
 *    picked by name that is inside the trust model, but it is a real capability and not a
 *    side effect worth hiding in a comment.
 *  - [revoke] rotates, which stops the revoked peer opening anything sealed **after** it. It
 *    cannot claw back ciphertexts they already fetched, and it cannot stop them having written
 *    the old key down. Revocation is forward-only. Saying otherwise in the UI would be a lie.
 *
 * Rotation also retires the previous private key outright, so sightings still sitting on the relay
 * sealed to the old public half become unreadable to the owner too. That is the honest cost of
 * revoking mid-window and is preferred to keeping a retired key alive to satisfy it.
 */
class PoweredOffRecovery(
    private val ks: E2eeKeyStore,
    /**
     * Seam for tests. The real implementation reaches into the native ML-KEM library, which a JVM
     * unit test has no business loading — and the roster arithmetic this class exists for is worth
     * testing without it.
     */
    private val mint: () -> RecoveryKeypair = ::mintRecoveryKeypair,
) {

    /** The current keypair, minting and filing one on first use. */
    suspend fun ensure(): RecoveryKeypair {
        val priv = ks.present(KEY_PRIV)
        val pub = ks.present(KEY_PUB)
        if (priv != null && pub != null) return RecoveryKeypair(pub, priv)
        return rotate()
    }

    /** The peers currently entitled to decrypt this device's sightings. Empty until a [grant]. */
    suspend fun grantees(): Set<Long> = decodeIds(ks.present(KEY_GRANTS))

    suspend fun isGranted(peerId: Long): Boolean = peerId in grantees()

    /** Rotation counter of the current keypair. 0 before one exists. */
    suspend fun epoch(): Long = ks.present(KEY_EPOCH)?.decodeToString()?.toLongOrNull() ?: 0L

    /**
     * Entitle [peerId] to decrypt this device's sightings.
     *
     * Does **not** rotate: the peers that already hold this key are supposed to hold it, so
     * churning it would only cost every one of them a redelivery. The returned distribution is
     * therefore addressed to [peerId] alone.
     *
     * Re-granting a peer that is already on the roster still returns a distribution, because the
     * ordinary reason to do it is that the first delivery did not arrive.
     */
    suspend fun grant(peerId: Long): RecoveryDistribution {
        require(peerId != 0L) { "peerId 0 is not a real peer" }
        val keys = ensure()
        val updated = grantees() + peerId
        ks.setBytes(KEY_GRANTS, encodeIds(updated))
        return RecoveryDistribution(
            epoch = epoch(),
            kemPrivate = keys.kemPrivate,
            publicBundle = keys.publicBundle,
            to = setOf(peerId),
        )
    }

    /**
     * Withdraw [peerId]'s entitlement and rotate, so nothing sealed from now on is readable with
     * the key they were given.
     *
     * The distribution is addressed to **every remaining grantee**, not to nobody: rotating
     * without redelivering would silently revoke the innocent peers too, and they would go on
     * polling with a dead key and finding nothing, which is the failure mode this whole feature
     * is riddled with and the one hardest to notice.
     *
     * Revoking a peer that was never granted still rotates. The caller reached for revoke, and a
     * new key costs one registration; guessing that they were mistaken does not.
     */
    suspend fun revoke(peerId: Long): RecoveryDistribution {
        val remaining = grantees() - peerId
        ks.setBytes(KEY_GRANTS, encodeIds(remaining))
        val keys = rotate()
        return RecoveryDistribution(
            epoch = epoch(),
            kemPrivate = keys.kemPrivate,
            publicBundle = keys.publicBundle,
            to = remaining,
            revoked = peerId,
        )
    }

    /**
     * Drop the keypair and the whole roster, for when the user turns powered-off finding off.
     *
     * Leaving the roster behind would silently re-entitle everyone on it the next time the switch
     * went back on, which is not what "off" looked like it meant.
     */
    suspend fun clear() {
        ks.setBytes(KEY_GRANTS, ByteArray(0))
        ks.setBytes(KEY_PRIV, ByteArray(0))
        ks.setBytes(KEY_PUB, ByteArray(0))
    }

    private suspend fun rotate(): RecoveryKeypair {
        val fresh = mint()
        ks.setBytes(KEY_PRIV, fresh.kemPrivate)
        ks.setBytes(KEY_PUB, fresh.publicBundle)
        ks.setBytes(KEY_EPOCH, (epoch() + 1).toString().encodeToByteArray())
        return fresh
    }

    // E2eeKeyStore can write but not delete, so `clear` blanks rather than removes. Every read
    // therefore has to treat an empty value as absent, or a cleared keypair would read back as a
    // zero-length private key and fail inside the native decrypt instead of being re-minted.
    private suspend fun E2eeKeyStore.present(name: String): ByteArray? =
        getBytes(name)?.takeIf { it.isNotEmpty() }

    private fun encodeIds(ids: Set<Long>): ByteArray =
        ids.sorted().joinToString(",").encodeToByteArray()

    private fun decodeIds(raw: ByteArray?): Set<Long> {
        if (raw == null || raw.isEmpty()) return emptySet()
        return raw.decodeToString().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
    }

    private companion object {
        const val KEY_PRIV = "ff_pofrec_priv"
        const val KEY_PUB = "ff_pofrec_pub"
        const val KEY_GRANTS = "ff_pofrec_grants"
        const val KEY_EPOCH = "ff_pofrec_epoch"
    }
}

/**
 * Mint a recovery keypair with the real ML-KEM implementation.
 *
 * ML-KEM only, with an empty ML-DSA half — see [RecoveryKeypair]. Top-level rather than a method
 * so [PoweredOffRecovery]'s default argument does not drag the native library into a unit test
 * that never calls it.
 */
fun mintRecoveryKeypair(): RecoveryKeypair {
    val (kemPub, kemPriv) = Pqc.generateKem()
    return RecoveryKeypair(publicBundle = Pqc.bundle(kemPub, ByteArray(0)), kemPrivate = kemPriv)
}

/** Length of one big-endian u64 field in [poweredOffGrantSigningBytes]. */
private const val U64_LEN = 8

/**
 * The exact bytes a grant is signed over, so the recipient can tell who really sent it.
 *
 * The peer channel encrypts to the recipient but does not authenticate the sender: `sender` on a
 * [com.vayunmathur.findfamily.domain.UwbEnvelope] is self-declared, and anyone who knows a userid can
 * seal an envelope to it. Without a signature a connected peer could deliver keys claiming to be
 * somebody else, and every sighting decrypted with them would be stamped with that somebody's
 * userid by `PoweredOffReporting.fetchSightings` and drawn on the map as their phone. Forged
 * locations attributed to a family member is a worse outcome than the feature not working.
 *
 * [owner] and [recipient] are both inside the signature. Owner binds the keys to the identity
 * being claimed; recipient stops a grant being replayed to a third party who was never chosen.
 *
 * The secret's length is written before it so the two variable-length fields cannot be traded off
 * against each other: without it, a 4-byte secret followed by an 8-byte key hashes identically to
 * an 8-byte secret followed by a 4-byte key of the same bytes. Both are fixed-length in practice,
 * which is exactly the kind of reasoning that stops being true later.
 */
fun poweredOffGrantSigningBytes(
    owner: Long,
    recipient: Long,
    epoch: Long,
    secret: ByteArray,
    recoveryPrivate: ByteArray,
): ByteArray {
    val domain = "ffpofgrant1".toByteArray(Charsets.US_ASCII)
    val out = ByteArray(domain.size + 32 + secret.size + recoveryPrivate.size)
    var off = 0
    domain.copyInto(out, off); off += domain.size
    TrackerProtocol.u64be(owner).copyInto(out, off); off += U64_LEN
    TrackerProtocol.u64be(recipient).copyInto(out, off); off += U64_LEN
    TrackerProtocol.u64be(epoch).copyInto(out, off); off += U64_LEN
    TrackerProtocol.u64be(secret.size.toLong()).copyInto(out, off); off += U64_LEN
    secret.copyInto(out, off); off += secret.size
    recoveryPrivate.copyInto(out, off)
    return out
}
