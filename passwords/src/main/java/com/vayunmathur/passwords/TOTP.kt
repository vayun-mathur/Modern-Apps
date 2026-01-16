package com.vayunmathur.passwords

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TOTP {
    private fun base32Decode(data: String): ByteArray {
        val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = data.trim().replace("=", "").replace(" ", "").uppercase()
        val output = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0
        for (c in clean) {
            val valC = base32Chars.indexOf(c)
            if (valC == -1) continue
            buffer = (buffer shl 5) or valC
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return output.toByteArray()
    }

    private fun hotp(key: ByteArray, counter: Long, digits: Int = 6): String {
        val counterBytes = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            counterBytes[i] = (c and 0xFF).toByte()
            c = c ushr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        val keySpec = SecretKeySpec(key, "RAW")
        mac.init(keySpec)
        val hash = mac.doFinal(counterBytes)
        val offset = (hash[hash.size - 1].toInt() and 0x0f)
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        val otp = binary % Math.pow(10.0, digits.toDouble()).toInt()
        return otp.toString().padStart(digits, '0')
    }

    fun generate(secret: String, epochSecond: Long): String {
        val key = base32Decode(secret)
        val timeStep = 30L
        val counter = epochSecond / timeStep
        return hotp(key, counter, 6)
    }
}