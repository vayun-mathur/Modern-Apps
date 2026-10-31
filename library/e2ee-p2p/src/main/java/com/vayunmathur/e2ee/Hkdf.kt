package com.vayunmathur.e2ee

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF (RFC 5869) over HMAC-SHA256: derive several fixed-purpose sub-keys from one secret so
 * that compromising a key derived for one use says nothing about the others.
 *
 * Lives in the E2EE module (not `:library`) so SDKs and apps can use key
 * derivation without depending on the whole base library (#884).
 *
 * Shared rather than per-app because several features need exactly this:
 * `:backup` expands the BIP-0039 seed into the AES-256 master key,
 * `:cast:protocol` expands the paired session secret into the control-channel
 * and per-stream media keys, and `:sdk:controller` expands its session secret.
 */
object Hkdf {
    private const val HMAC = "HmacSHA256"
    private const val HASH_LEN = 32
    private const val MAX_BLOCKS = 255

    fun derive(ikm: ByteArray, info: ByteArray, length: Int, salt: ByteArray = ByteArray(HASH_LEN)): ByteArray {
        val prk = hmac(salt, ikm)
        val blocks = (length + HASH_LEN - 1) / HASH_LEN
        require(blocks <= MAX_BLOCKS) { "HKDF cannot derive more than 255 blocks" }
        val output = ByteArray(blocks * HASH_LEN)
        var previous = ByteArray(0)
        for (i in 1..blocks) {
            val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(prk, HMAC)) }
            mac.update(previous)
            mac.update(info)
            mac.update(i.toByte())
            previous = mac.doFinal()
            previous.copyInto(output, (i - 1) * HASH_LEN)
        }
        return output.copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(data)
}
