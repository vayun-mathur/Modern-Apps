package com.vayunmathur.passwords.domain
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Singleton holding totp state and operations. */
object TOTP {
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    // RFC 4648 base32 decode. Input is expected already trimmed/uppercased with
    // padding stripped; any character outside the alphabet is ignored.
    private fun base32Decode(input: String): ByteArray {
        val out = ArrayList<Byte>(input.length * 5 / 8 + 1)
        var buffer = 0
        var bitsLeft = 0
        for (c in input) {
            val value = BASE32_ALPHABET.indexOf(c)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    private fun hotp(key: ByteArray, counter: Long): String {
        val counterBytes = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "RAW"))
        val hash = mac.doFinal(counterBytes)
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    fun generate(secret: String, epochSecond: Long): String {
        val cleaned = secret.trim().replace("=", "").replace(" ", "").uppercase()
        val key = base32Decode(cleaned)
        val timeStep = 30L
        val counter = epochSecond / timeStep
        return hotp(key, counter)
    }
}
