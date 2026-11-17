package com.vayunmathur.findfamily.network

import com.vayunmathur.e2ee.Pqc
import com.vayunmathur.library.log.Log
import kotlin.io.encoding.Base64

/**
 * Generates a fresh share-link key. The link is ML-KEM only and derived from a
 * 32-byte seed, which is the whole secret handed to the recipient in the URL
 * fragment — short enough to fit in an SMS. The private key is never stored:
 * the sender only ever encrypts, and the recipient re-derives it from the seed.
 */
fun Networking.generatePqcLinkKey(): Networking.PqcLinkKey {
    val key = Pqc.generateLinkKey()
    return Networking.PqcLinkKey(
        seedB64Url = Base64.UrlSafe.encode(key.seed).trimEnd('='),
        publicBundleB64 = Base64.encode(key.publicBundle)
    )
}

/**
 * This device's invite fingerprint, as the unpadded base64url `k=` value of a share link.
 * Null when PQC is unavailable — the link then goes out without it and the recipient simply
 * has nothing to pre-verify against, exactly as before this parameter existed.
 */
fun Networking.myInviteFingerprint(): String? {
    if (!pqcReady) return null
    return runCatching {
        Base64.UrlSafe.encode(Pqc.inviteFingerprint(userid, pqcIdentity.publicBundle)).trimEnd('=')
    }.onFailure { Log.status(TAG, "invite fingerprint failed", it) }.getOrNull()
}

/**
 * Generates a full PQC identity (ML-KEM + ML-DSA) for a device we own but that cannot
 * run keygen itself — currently only UWB trackers, whose private bundle we hold on
 * their behalf. Returns (publicBundleBase64, privateBundleBase64) where the private
 * bundle is [4B kemPrivLen][kemPrivDer][dsaPrivDer] — DERs, BC-compatible, same KDF
 * as Office. Share links use [generatePqcLinkKey] instead.
 */
fun Networking.generatePqcIdentityKeyPair(): Networking.PqcIdentityKeyPair {
    val (kemPub, kemPriv) = Pqc.generateKem()
    val (dsaPub, dsaPriv) = Pqc.generateDsa()
    val pubBundle = Pqc.bundle(kemPub, dsaPub)
    val privBundle = buildPrivBundle(kemPriv, dsaPriv)
    return Networking.PqcIdentityKeyPair(
        publicBundleB64 = Base64.encode(pubBundle),
        privateBundleB64 = Base64.encode(privBundle)
    )
}

/** Private bundle layout: [u32 kemPrivLen BE][kemPriv][dsaPriv] — mirrors public bundle. */
private fun buildPrivBundle(kemPriv: ByteArray, dsaPriv: ByteArray): ByteArray {
    val out = ByteArray(Networking.U32_LEN + kemPriv.size + dsaPriv.size)
    out[0] = (kemPriv.size ushr Networking.U32_BYTE0_SHIFT).toByte()
    out[1] = (kemPriv.size ushr Networking.U32_BYTE1_SHIFT).toByte()
    out[2] = (kemPriv.size ushr Networking.BITS_PER_BYTE).toByte()
    out[Networking.U32_LEN - 1] = kemPriv.size.toByte()
    kemPriv.copyInto(out, Networking.U32_LEN)
    dsaPriv.copyInto(out, Networking.U32_LEN + kemPriv.size)
    return out
}
