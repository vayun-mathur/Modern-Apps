package com.vayunmathur.e2ee

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PqcTest {
    @Test
    fun ml_kem_encrypt_decrypt_roundtrip() {
        val kem = Pqc.generateKem()
        val dsa = Pqc.generateDsa()
        val bundle = Pqc.bundle(kem.public.encoded, dsa.public.encoded)
        val msg = "post-quantum hello — a longer payload than RSA-OAEP could ever hold in one shot".encodeToByteArray()
        val ct = Pqc.encryptTo(bundle, msg)
        assertArrayEquals(msg, Pqc.decrypt(kem.private, ct))
    }

    @Test
    fun ml_dsa_sign_verify() {
        val kem = Pqc.generateKem()
        val dsa = Pqc.generateDsa()
        val bundle = Pqc.bundle(kem.public.encoded, dsa.public.encoded)
        val data = "authenticate me".encodeToByteArray()
        val sig = Pqc.signWith(dsa.private, data)
        assertTrue(Pqc.verify(bundle, data, sig))
        assertFalse(Pqc.verify(bundle, "tampered".encodeToByteArray(), sig))

        val other = Pqc.bundle(Pqc.generateKem().public.encoded, Pqc.generateDsa().public.encoded)
        assertFalse(Pqc.verify(other, data, sig))
    }

    @Test
    fun security_code_matches_both_sides() {
        val a = Pqc.bundle(Pqc.generateKem().public.encoded, Pqc.generateDsa().public.encoded)
        val b = Pqc.bundle(Pqc.generateKem().public.encoded, Pqc.generateDsa().public.encoded)
        assertTrue(Pqc.securityCode(a, b) == Pqc.securityCode(b, a)) // order-independent
    }
}
