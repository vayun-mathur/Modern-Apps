package com.vayunmathur.communicate.data.rcs.e2e

import com.vayunmathur.library.log.NativeLoader


/**
 * JNI bridge to the `communicate_mls` Rust crate (OpenMLS, RFC 9420).
 *
 * Flat byte protocol: every call takes opaque blobs in and returns
 * `Array<ByteArray>` (`[storage_out, result…]`) or null on error. Kotlin
 * persists `storage_out` (provider snapshot) in Room alongside conversation
 * rows; identity bytes live in the MLS identity table. The crate holds no
 * state across calls.
 *
 * MLS follows GSMA Universal Profile 3.0 (standard MLS — the same profile
 * Google Messages uses). Key discovery stays closed-loop in v1 (our own RCS
 * content-types instead of a federated directory); the crypto itself is
 * interop-shaped.
 */
object RustMlsCrypto {

    private const val TAG = "RustMlsCrypto"

    val isAvailable: Boolean = NativeLoader.load("communicate_mls", TAG)

    /** Generate an identity for `e164` UTF-8 bytes. Returns `[empty, identity]`. */
    @JvmStatic external fun generateIdentity(e164: ByteArray): Array<ByteArray>?

    /** Build a key package. `(storage, identity)` → `[storage_out, key_package_tls]`. */
    @JvmStatic external fun buildKeyPackage(storage: ByteArray, identity: ByteArray): Array<ByteArray>?

    /** Create a group. `(storage, identity)` → `[storage_out, group_id]`. */
    @JvmStatic external fun createGroup(storage: ByteArray, identity: ByteArray): Array<ByteArray>?

    /**
     * Add members. `(storage, identity, group_id, key_packages)` →
     * `[storage_out, commit_tls, welcome_framed]`. Send commit to the group,
     * welcome to the joiners (both over RCS).
     */
    @JvmStatic external fun addMembers(
        storage: ByteArray,
        identity: ByteArray,
        groupId: ByteArray,
        keyPackages: Array<ByteArray>,
    ): Array<ByteArray>?

    /**
     * Join from a Welcome envelope. `(storage, welcome_framed)` →
     * `[storage_out, group_id]`.
     */
    @JvmStatic external fun joinGroup(storage: ByteArray, welcome: ByteArray): Array<ByteArray>?

    /**
     * Encrypt. `(storage, identity, group_id, plaintext)` →
     * `[storage_out, framed_payload]`. Send the payload over RCS.
     */
    @JvmStatic external fun encrypt(
        storage: ByteArray,
        identity: ByteArray,
        groupId: ByteArray,
        plaintext: ByteArray,
    ): Array<ByteArray>?

    /**
     * Decrypt. `(storage, group_id, framed_payload)` →
     * `[storage_out, is_application(1B), plaintext]`.
     */
    @JvmStatic external fun decrypt(
        storage: ByteArray,
        groupId: ByteArray,
        payload: ByteArray,
    ): Array<ByteArray>?

    /**
     * Remove members by leaf index. `(storage, identity, group_id, indices_csv)` →
     * `[storage_out, commit_tls]`. Indices are ASCII decimal, comma-separated.
     */
    @JvmStatic external fun removeMembers(
        storage: ByteArray,
        identity: ByteArray,
        groupId: ByteArray,
        indicesCsv: ByteArray,
    ): Array<ByteArray>?

    /** Framing probe: 0=welcome, 1=message, 2=unknown. */
    @JvmStatic external fun payloadKind(payload: ByteArray): Int

    /** Magic prefixes for Kotlin-side routing without a JNI round trip. */
    @JvmStatic external fun welcomeMagic(): ByteArray?
    @JvmStatic external fun messageMagic(): ByteArray?
}
