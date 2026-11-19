package com.vayunmathur.e2ee

import com.vayunmathur.library.log.NativeLoader

/**
 * JNI surface for the native Rust PQC library (libe2ee_pqc.so): ML-KEM-768 +
 * ML-DSA-65. All keys are DER (X.509 SPKI / PKCS#8), byte-compatible with the
 * previous Bouncy Castle encoding. Keygen/encaps return a two-element
 * `byte[][]` = [publicOrCiphertext, privateOrSharedSecret]; null on failure.
 */
internal object PqcNative {
    external fun nativeMlkemKeygen(): Array<ByteArray>?
    external fun nativeMlkemLinkKeygen(): Array<ByteArray>?
    external fun nativeMlkemLinkPubFromSeed(seed: ByteArray): ByteArray?
    external fun nativeMldsaKeygen(): Array<ByteArray>?
    external fun nativeMlkemEncaps(pubDer: ByteArray): Array<ByteArray>?
    external fun nativeMlkemDecaps(privDer: ByteArray, ct: ByteArray): ByteArray?
    external fun nativeMldsaSign(privDer: ByteArray, msg: ByteArray): ByteArray?
    external fun nativeMldsaVerify(pubDer: ByteArray, msg: ByteArray, sig: ByteArray): Boolean

    val available: Boolean = NativeLoader.load("e2ee_pqc", "PqcNative")
}
