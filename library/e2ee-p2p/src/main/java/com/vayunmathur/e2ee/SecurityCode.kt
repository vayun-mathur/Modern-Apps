package com.vayunmathur.e2ee

import java.security.MessageDigest

/**
 * Derives a human-comparable "security code" (safety number) from the two participants' public
 * keys. Both devices compute the **same** code because the two key fingerprints are combined in a
 * canonical (sorted) order before hashing. If the codes match on both devices, the two peers hold
 * each other's genuine keys — i.e. no one (not the server) substituted a key to intercept the
 * end-to-end-encrypted channel.
 *
 * Inputs are the **canonical DER** encodings of the public keys (see [E2ee.canonicalDer]) so the
 * result is independent of incidental PEM formatting differences.
 */
object SecurityCode {

    /** Iterated hashing slows brute-force search for a colliding short code. */
    private const val ITERATIONS = 4000

    /** @return a 6-group, 30-digit code like "12345 67890 ...", identical on both devices. */
    fun compute(myKeyDer: ByteArray, theirKeyDer: ByteArray): String {
        val a = sha256(myKeyDer)
        val b = sha256(theirKeyDer)
        // Canonical order so both sides (which hold the two keys in opposite roles) agree.
        var h = if (compareLex(a, b) <= 0) a + b else b + a
        repeat(ITERATIONS) { h = sha256(h) }
        return format(h)
    }

    private fun sha256(x: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(x)

    private fun compareLex(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    private fun format(h: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        var group = 0
        while (group < 6 && i + 5 <= h.size) {
            var v = 0L
            for (j in 0 until 5) v = (v shl 8) or (h[i + j].toLong() and 0xFF)
            if (group > 0) sb.append(' ')
            sb.append((v % 100000L).toString().padStart(5, '0'))
            i += 5; group++
        }
        return sb.toString()
    }
}
