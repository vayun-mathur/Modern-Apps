package com.vayunmathur.messages.telegram.mtproto.crypto

import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SrpAnswer(val a: ByteArray, val m1: ByteArray)

object Srp {
    fun computeAnswer(
        password: ByteArray,
        srpB: ByteArray,
        randomA: ByteArray,
        salt1: ByteArray,
        salt2: ByteArray,
        g: Int,
        p: ByteArray,
    ): SrpAnswer {
        val pBig = BigInteger(1, p)
        val gBig = BigInteger.valueOf(g.toLong())
        val gBytes = ByteArray(256)
        val gBigBytes = gBig.toByteArray()
        System.arraycopy(gBigBytes, 0, gBytes, 256 - gBigBytes.size, gBigBytes.size)

        val aBig = BigInteger(1, randomA)
        val ga = pad256(gBig.modPow(aBig, pBig))
        val gb = pad256(BigInteger(1, srpB))

        val u = BigInteger(1, hash(ga, gb))
        val x = BigInteger(1, secondary(password, salt1, salt2))
        val v = gBig.modPow(x, pBig)
        val k = BigInteger(1, hash(p, gBytes))
        val kv = k.multiply(v).mod(pBig)

        var t = BigInteger(1, srpB).subtract(kv)
        if (t.signum() < 0) t = t.add(pBig)

        val sa = pad256(t.modPow(u.multiply(x).add(aBig), pBig))
        val ka = MessageDigest.getInstance("SHA-256").digest(sa)

        val hP = MessageDigest.getInstance("SHA-256").digest(p)
        val hG = MessageDigest.getInstance("SHA-256").digest(gBytes)
        val xorHpHg = ByteArray(32)
        for (i in 0 until 32) xorHpHg[i] = (hP[i].toInt() xor hG[i].toInt()).toByte()

        val m1 = hash(
            xorHpHg,
            hash(salt1),
            hash(salt2),
            ga, gb, ka
        )

        return SrpAnswer(ga, m1)
    }

    private fun hash(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (part in parts) md.update(part)
        return md.digest()
    }

    private fun saltHash(data: ByteArray, salt: ByteArray): ByteArray = hash(salt, data, salt)

    private fun primary(password: ByteArray, salt1: ByteArray, salt2: ByteArray): ByteArray =
        saltHash(saltHash(password, salt1), salt2)

    private fun secondary(password: ByteArray, salt1: ByteArray, salt2: ByteArray): ByteArray {
        val ph1 = primary(password, salt1, salt2)
        val pbkdf2 = pbkdf2Sha512(ph1, salt1, 100000, 64)
        return saltHash(pbkdf2, salt2)
    }

    private fun pbkdf2Sha512(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        val hashLen = 64
        val blocks = (keyLength + hashLen - 1) / hashLen
        val result = ByteArray(blocks * hashLen)
        for (i in 1..blocks) {
            val blockIndex = byteArrayOf(
                (i shr 24).toByte(), (i shr 16).toByte(), (i shr 8).toByte(), i.toByte()
            )
            mac.reset()
            mac.update(salt)
            var u = mac.doFinal(blockIndex)
            val block = u.copyOf()
            for (j in 2..iterations) {
                mac.reset()
                u = mac.doFinal(u)
                for (k in block.indices) block[k] = (block[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(block, 0, result, (i - 1) * hashLen, hashLen)
        }
        return result.copyOfRange(0, keyLength)
    }

    private fun pad256(v: BigInteger): ByteArray {
        val bytes = v.toByteArray()
        return when {
            bytes.size == 256 -> bytes
            bytes.size > 256 -> bytes.copyOfRange(bytes.size - 256, bytes.size)
            else -> ByteArray(256 - bytes.size) + bytes
        }
    }
}
