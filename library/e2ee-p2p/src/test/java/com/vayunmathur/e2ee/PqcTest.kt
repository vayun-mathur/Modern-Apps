package com.vayunmathur.e2ee

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These exercise the real native PQC (libe2ee_pqc.so), so they only run on-device
 * / when the native lib is loadable. Byte-level interop with the previously-
 * deployed Bouncy Castle encoding is covered by the Rust crate's own tests
 * (library/e2ee-p2p/src/main/rust/src/tests.rs) against captured BC vectors.
 */
class PqcTest {
    private var nativeAvailable = false

    @BeforeTest
    fun checkNative() {
        nativeAvailable = runCatching { Pqc.generateKem() }.isSuccess
        // The host lib is built for JVM tests (cargoBuildHost, like every other
        // Rust module): unavailable here is a build failure, not a skip.
        assertTrue(nativeAvailable, "host e2ee_pqc lib unavailable: cargoBuildHost must produce it for JVM tests")
    }

    @Test
    fun ml_kem_encrypt_decrypt_roundtrip() {
        assertTrue(nativeAvailable)
        val (kemPub, kemPriv) = Pqc.generateKem()
        val (dsaPub, _) = Pqc.generateDsa()
        val bundle = Pqc.bundle(kemPub, dsaPub)
        val msg = "post-quantum hello — a payload comfortably longer than one KEM ciphertext".encodeToByteArray()
        val ct = Pqc.encryptTo(bundle, msg)
        assertContentEquals(msg, Pqc.decrypt(kemPriv, ct))
    }

    @Test
    fun ml_dsa_sign_verify() {
        assertTrue(nativeAvailable)
        val (kemPub, _) = Pqc.generateKem()
        val (dsaPub, dsaPriv) = Pqc.generateDsa()
        val bundle = Pqc.bundle(kemPub, dsaPub)
        val data = "authenticate me".encodeToByteArray()
        val sig = Pqc.signWith(dsaPriv, data)
        assertTrue(Pqc.verify(bundle, data, sig))
        assertFalse(Pqc.verify(bundle, "tampered".encodeToByteArray(), sig))

        val (ok, od) = Pqc.generateKem().first to Pqc.generateDsa().first
        val other = Pqc.bundle(ok, od)
        assertFalse(Pqc.verify(other, data, sig))
    }

    @Test
    fun security_code_matches_both_sides() {
        assertTrue(nativeAvailable)
        val a = Pqc.bundle(Pqc.generateKem().first, Pqc.generateDsa().first)
        val b = Pqc.bundle(Pqc.generateKem().first, Pqc.generateDsa().first)
        assertTrue(Pqc.securityCode(a, b) == Pqc.securityCode(b, a)) // order-independent
    }
}
