//! C ABI exports for iOS (and cross-platform consumers) so FindFamilyiOS can
//! reuse the exact same ML-KEM-768 + ML-DSA-65 implementation as Office's
//! Android PqcIdentity, with identical bundle format and KDF.
//!
//! Bundle formats (identical to `Pqc.kt`):
//!   - public bundle:  [4B kemPubLen BE][kemPub SPKI DER][dsaPub SPKI DER]
//!   - private bundle: [4B kemPrivLen BE][kemPriv PKCS#8 DER][dsaPriv PKCS#8 DER]
//!   - sealed blob:    [4B encapLen BE][encap(1088)][aes] where aes = [12B iv][ct||tag]
//!
//! Memory ownership: every `Vec<u8>` returned as `*mut u8 + len` is allocated via `Box::into_raw`
//! and must be freed by `pqc_free`. Output pointer/len pairs are written into caller-provided
//! out-params; null on failure returns 0. This keeps the surface minimal and avoids cbindgen.
//!
//! All functions return 1 on success, 0 on failure (except `pqc_free`/`pqc_secure_zero` which are void).

use std::ptr;
use std::slice;

// ---------------------------------------------------------------------------
// Alloc helpers — callers must free via pqc_free(ptr, len).
// ---------------------------------------------------------------------------

fn alloc_bytes(v: Vec<u8>) -> (*mut u8, usize) {
    let len = v.len();
    let ptr = Box::into_raw(v.into_boxed_slice()) as *mut u8;
    (ptr, len)
}

/// Frees a buffer previously returned by alloc_bytes / encap/encaps etc.
#[no_mangle]
pub unsafe extern "C" fn pqc_free(ptr: *mut u8, len: usize) {
    if ptr.is_null() {
        return;
    }
    let _ = Box::from_raw(std::ptr::slice_from_raw_parts_mut(ptr, len));
}

/// Best-effort secure zero of a buffer (for private keys / shared secrets).
#[no_mangle]
pub unsafe extern "C" fn pqc_secure_zero(ptr: *mut u8, len: usize) {
    if ptr.is_null() || len == 0 {
        return;
    }
    ptr::write_bytes(ptr, 0, len);
}

// ---------------------------------------------------------------------------
// Length-prefix framing (same as Kotlin Pqc.lenPrefix / unLenPrefix)
// ---------------------------------------------------------------------------

fn len_prefix(a: &[u8], b: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(4 + a.len() + b.len());
    out.extend_from_slice(&(a.len() as u32).to_be_bytes());
    out.extend_from_slice(a);
    out.extend_from_slice(b);
    out
}

fn un_len_prefix(x: &[u8]) -> Option<(&[u8], &[u8])> {
    if x.len() < 4 {
        return None;
    }
    let len = u32::from_be_bytes([x[0], x[1], x[2], x[3]]) as usize;
    if 4 + len > x.len() {
        return None;
    }
    Some((&x[4..4 + len], &x[4 + len..]))
}

// ---------------------------------------------------------------------------
// Public helpers exported for iOS tests / diagnostics.
// ---------------------------------------------------------------------------

/// Returns 1 if `bundle` looks like a valid public PQC bundle (kemLen + at least that many bytes).
#[no_mangle]
pub unsafe extern "C" fn pqc_bundle_is_valid(bundle: *const u8, bundle_len: usize) -> i32 {
    if bundle.is_null() || bundle_len < 4 {
        return 0;
    }
    let slice = slice::from_raw_parts(bundle, bundle_len);
    i32::from(un_len_prefix(slice).is_some())
}

/// Returns 1 and fills out_* with DER lengths if parseable. On fail returns 0.
#[no_mangle]
pub unsafe extern "C" fn pqc_bundle_inspect(
    bundle: *const u8,
    bundle_len: usize,
    kem_pub_len_out: *mut usize,
    dsa_pub_len_out: *mut usize,
) -> i32 {
    if bundle.is_null() || bundle_len < 4 || kem_pub_len_out.is_null() || dsa_pub_len_out.is_null() {
        return 0;
    }
    let s = slice::from_raw_parts(bundle, bundle_len);
    match un_len_prefix(s) {
        Some((kem, dsa)) => {
            ptr::write(kem_pub_len_out, kem.len());
            ptr::write(dsa_pub_len_out, dsa.len());
            1
        }
        None => 0,
    }
}

// ---------------------------------------------------------------------------
// KEM keygen (returns public + private DER via out-params)
// ---------------------------------------------------------------------------

/// Generates an ML-KEM-768 keypair. Out params filled on success (caller must free via pqc_free).
#[no_mangle]
pub unsafe extern "C" fn pqc_kem_keygen(
    pub_out: *mut *mut u8,
    pub_len_out: *mut usize,
    priv_out: *mut *mut u8,
    priv_len_out: *mut usize,
) -> i32 {
    if pub_out.is_null() || pub_len_out.is_null() || priv_out.is_null() || priv_len_out.is_null() {
        return 0;
    }
    let (pub_der, priv_der) = crate::mlkem_keygen_der();
    let (pub_ptr, pub_len) = alloc_bytes(pub_der);
    let (priv_ptr, priv_len) = alloc_bytes(priv_der);
    ptr::write(pub_out, pub_ptr);
    ptr::write(pub_len_out, pub_len);
    ptr::write(priv_out, priv_ptr);
    ptr::write(priv_len_out, priv_len);
    1
}

// ---------------------------------------------------------------------------
// DSA keygen
// ---------------------------------------------------------------------------

/// Generates an ML-DSA-65 keypair. Out params filled on success (caller must free).
#[no_mangle]
pub unsafe extern "C" fn pqc_dsa_keygen(
    pub_out: *mut *mut u8,
    pub_len_out: *mut usize,
    priv_out: *mut *mut u8,
    priv_len_out: *mut usize,
) -> i32 {
    if pub_out.is_null() || pub_len_out.is_null() || priv_out.is_null() || priv_len_out.is_null() {
        return 0;
    }
    let (pub_der, priv_der) = crate::mldsa_keygen_der();
    let (pub_ptr, pub_len) = alloc_bytes(pub_der);
    let (priv_ptr, priv_len) = alloc_bytes(priv_der);
    ptr::write(pub_out, pub_ptr);
    ptr::write(pub_len_out, pub_len);
    ptr::write(priv_out, priv_ptr);
    ptr::write(priv_len_out, priv_len);
    1
}

// ---------------------------------------------------------------------------
// Full identity keygen — public bundle + private bundle
// ---------------------------------------------------------------------------

/// Generates both KEM+DSA and returns the public bundle and private bundle.
/// Public bundle:  [4B kemPubLen][kemPub][dsaPub]
/// Private bundle: [4B kemPrivLen][kemPriv][dsaPriv]
#[no_mangle]
pub unsafe extern "C" fn pqc_identity_keygen(
    pub_bundle_out: *mut *mut u8,
    pub_bundle_len_out: *mut usize,
    priv_bundle_out: *mut *mut u8,
    priv_bundle_len_out: *mut usize,
) -> i32 {
    if pub_bundle_out.is_null()
        || pub_bundle_len_out.is_null()
        || priv_bundle_out.is_null()
        || priv_bundle_len_out.is_null()
    {
        return 0;
    }
    let (kem_pub, kem_priv) = crate::mlkem_keygen_der();
    let (dsa_pub, dsa_priv) = crate::mldsa_keygen_der();
    let pub_b = len_prefix(&kem_pub, &dsa_pub);
    let priv_b = len_prefix(&kem_priv, &dsa_priv);
    let (pub_ptr, pub_len) = alloc_bytes(pub_b);
    let (priv_ptr, priv_len) = alloc_bytes(priv_b);
    ptr::write(pub_bundle_out, pub_ptr);
    ptr::write(pub_bundle_len_out, pub_len);
    ptr::write(priv_bundle_out, priv_ptr);
    ptr::write(priv_bundle_len_out, priv_len);
    1
}

// ---------------------------------------------------------------------------
// FindFamily link keys — ML-KEM only, derived from a 32-byte seed.
// The public bundle keeps the [4B kemLen][kem][dsa] layout with an empty DSA
// half, so pqc_bundle_split / encryptTo need no special casing.
// ---------------------------------------------------------------------------

/// Generates a fresh link seed and its ML-KEM-only public bundle.
/// Out params filled on success (caller must free via pqc_free; zero the seed first).
#[no_mangle]
pub unsafe extern "C" fn pqc_link_keygen(
    seed_out: *mut *mut u8,
    seed_len_out: *mut usize,
    pub_bundle_out: *mut *mut u8,
    pub_bundle_len_out: *mut usize,
) -> i32 {
    if seed_out.is_null()
        || seed_len_out.is_null()
        || pub_bundle_out.is_null()
        || pub_bundle_len_out.is_null()
    {
        return 0;
    }
    let seed = crate::mlkem_link_seed_new();
    let (kem_pub, _) = match crate::mlkem_link_keygen_from_seed(&seed) {
        Some(p) => p,
        None => return 0,
    };
    let (seed_ptr, seed_len) = alloc_bytes(seed.to_vec());
    let (bundle_ptr, bundle_len) = alloc_bytes(len_prefix(&kem_pub, &[]));
    ptr::write(seed_out, seed_ptr);
    ptr::write(seed_len_out, seed_len);
    ptr::write(pub_bundle_out, bundle_ptr);
    ptr::write(pub_bundle_len_out, bundle_len);
    1
}

/// Re-derives the ML-KEM-only public bundle from a stored link seed.
#[no_mangle]
pub unsafe extern "C" fn pqc_link_pub_from_seed(
    seed: *const u8,
    seed_len: usize,
    pub_bundle_out: *mut *mut u8,
    pub_bundle_len_out: *mut usize,
) -> i32 {
    if seed.is_null() || pub_bundle_out.is_null() || pub_bundle_len_out.is_null() {
        return 0;
    }
    let s = slice::from_raw_parts(seed, seed_len);
    let (kem_pub, _) = match crate::mlkem_link_keygen_from_seed(s) {
        Some(p) => p,
        None => return 0,
    };
    let (bundle_ptr, bundle_len) = alloc_bytes(len_prefix(&kem_pub, &[]));
    ptr::write(pub_bundle_out, bundle_ptr);
    ptr::write(pub_bundle_len_out, bundle_len);
    1
}

// ---------------------------------------------------------------------------
// Bundle helper (iOS builds bundle same way as Pqc.kt)
// ---------------------------------------------------------------------------

/// Builds a public PQC bundle from individual KEM and DSA public DERs.
#[no_mangle]
pub unsafe extern "C" fn pqc_bundle_build(
    kem_pub: *const u8,
    kem_pub_len: usize,
    dsa_pub: *const u8,
    dsa_pub_len: usize,
    out: *mut *mut u8,
    out_len: *mut usize,
) -> i32 {
    if kem_pub.is_null() || dsa_pub.is_null() || out.is_null() || out_len.is_null() {
        return 0;
    }
    let kem = slice::from_raw_parts(kem_pub, kem_pub_len);
    let dsa = slice::from_raw_parts(dsa_pub, dsa_pub_len);
    let b = len_prefix(kem, dsa);
    let (ptr, len) = alloc_bytes(b);
    ptr::write(out, ptr);
    ptr::write(out_len, len);
    1
}

/// Splits a public bundle into KEM and DSA components (caller frees each via pqc_free).
#[no_mangle]
pub unsafe extern "C" fn pqc_bundle_split(
    bundle: *const u8,
    bundle_len: usize,
    kem_pub_out: *mut *mut u8,
    kem_pub_len_out: *mut usize,
    dsa_pub_out: *mut *mut u8,
    dsa_pub_len_out: *mut usize,
) -> i32 {
    if bundle.is_null()
        || kem_pub_out.is_null()
        || kem_pub_len_out.is_null()
        || dsa_pub_out.is_null()
        || dsa_pub_len_out.is_null()
    {
        return 0;
    }
    let s = slice::from_raw_parts(bundle, bundle_len);
    let (kem, dsa) = match un_len_prefix(s) {
        Some(p) => p,
        None => return 0,
    };
    let (k_ptr, k_len) = alloc_bytes(kem.to_vec());
    let (d_ptr, d_len) = alloc_bytes(dsa.to_vec());
    ptr::write(kem_pub_out, k_ptr);
    ptr::write(kem_pub_len_out, k_len);
    ptr::write(dsa_pub_out, d_ptr);
    ptr::write(dsa_pub_len_out, d_len);
    1
}

// ---------------------------------------------------------------------------
// Encapsulate / Decapsulate raw (for direct use)
// ---------------------------------------------------------------------------

/// Encapsulates to a KEM public DER: returns (ciphertext(1088), sharedSecret(32)).
#[no_mangle]
pub unsafe extern "C" fn pqc_kem_encaps(
    kem_pub: *const u8,
    kem_pub_len: usize,
    ct_out: *mut *mut u8,
    ct_len_out: *mut usize,
    ss_out: *mut *mut u8,
    ss_len_out: *mut usize,
) -> i32 {
    if kem_pub.is_null() || ct_out.is_null() || ct_len_out.is_null() || ss_out.is_null() || ss_len_out.is_null() {
        return 0;
    }
    let pub_slice = slice::from_raw_parts(kem_pub, kem_pub_len);
    let (ct, ss) = match crate::mlkem_encaps_der(pub_slice) {
        Some(p) => p,
        None => return 0,
    };
    let (ct_ptr, ct_len) = alloc_bytes(ct);
    let (ss_ptr, ss_len) = alloc_bytes(ss);
    ptr::write(ct_out, ct_ptr);
    ptr::write(ct_len_out, ct_len);
    ptr::write(ss_out, ss_ptr);
    ptr::write(ss_len_out, ss_len);
    1
}

/// Decapsulates with a KEM private DER + ciphertext -> sharedSecret(32).
#[no_mangle]
pub unsafe extern "C" fn pqc_kem_decaps(
    kem_priv: *const u8,
    kem_priv_len: usize,
    ct: *const u8,
    ct_len: usize,
    ss_out: *mut *mut u8,
    ss_len_out: *mut usize,
) -> i32 {
    if kem_priv.is_null() || ct.is_null() || ss_out.is_null() || ss_len_out.is_null() {
        return 0;
    }
    let priv_slice = slice::from_raw_parts(kem_priv, kem_priv_len);
    let ct_slice = slice::from_raw_parts(ct, ct_len);
    let ss = match crate::mlkem_decaps_der(priv_slice, ct_slice) {
        Some(s) => s,
        None => return 0,
    };
    let (ss_ptr, len) = alloc_bytes(ss);
    ptr::write(ss_out, ss_ptr);
    ptr::write(ss_len_out, len);
    1
}

// ---------------------------------------------------------------------------
// High-level encrypt/decrypt to a public bundle (same framing as Pqc.encryptTo)
// Layout sealed: [4B encapLen BE][encap(1088)][aes] where aes = [12B iv][ciphertext||tag]
// AES key is sharedSecret = SHA256(BE32(1)||rawSS) identical to Kotlin's concat_kdf.
// ---------------------------------------------------------------------------

// NOTE: The high-level encrypt/decrypt that does AES is implemented on the Kotlin/Swift side
// because it needs AES-GCM (CryptoKit / javax.crypto). This crate exposes only KEM primitives
// via C. Swift's PQCCrypto will reproduce E2ee.aesEncrypt layout for full compatibility.
//
// For iOS convenience we also expose these direct-derivation helpers that perform the full
// PQC seal/unseal if the caller provides an AES implementation, or we do AES here using
// a pure-Rust software AES if `aes-gcm` were added. To keep deps minimal, we use the same
// AES-GCM as in Kotlin: iv prepended. This requires adding `aes-gcm` + `aead`. To avoid
// new deps in this PR, we leave AES to the Swift layer — so we expose no aes-containing
// extern here, only KEM.

/// Signs with DSA private DER.
#[no_mangle]
pub unsafe extern "C" fn pqc_dsa_sign(
    dsa_priv: *const u8,
    dsa_priv_len: usize,
    msg: *const u8,
    msg_len: usize,
    sig_out: *mut *mut u8,
    sig_len_out: *mut usize,
) -> i32 {
    if dsa_priv.is_null() || msg.is_null() || sig_out.is_null() || sig_len_out.is_null() {
        return 0;
    }
    let priv_slice = slice::from_raw_parts(dsa_priv, dsa_priv_len);
    let msg_slice = slice::from_raw_parts(msg, msg_len);
    let sig = match crate::mldsa_sign_der(priv_slice, msg_slice) {
        Some(s) => s,
        None => return 0,
    };
    let (ptr, len) = alloc_bytes(sig);
    ptr::write(sig_out, ptr);
    ptr::write(sig_len_out, len);
    1
}

/// Verifies with DSA public DER. Returns 1 if valid, 0 if invalid, -1 on parse error.
#[no_mangle]
pub unsafe extern "C" fn pqc_dsa_verify(
    dsa_pub: *const u8,
    dsa_pub_len: usize,
    msg: *const u8,
    msg_len: usize,
    sig: *const u8,
    sig_len: usize,
) -> i32 {
    if dsa_pub.is_null() || msg.is_null() || sig.is_null() {
        return -1;
    }
    let pub_slice = slice::from_raw_parts(dsa_pub, dsa_pub_len);
    let msg_slice = slice::from_raw_parts(msg, msg_len);
    let sig_slice = slice::from_raw_parts(sig, sig_len);
    match crate::mldsa_verify_checked(pub_slice, msg_slice, sig_slice) {
        Ok(true) => 1,
        Ok(false) => 0,
        Err(()) => -1,
    }
}

// ---------------------------------------------------------------------------
// Combined security code — identical to SecurityCode.compute in Kotlin
// Computes same 6-group 5-digit code from two public bundles.
// ---------------------------------------------------------------------------

/// Computes security code length prefix: fills out buffer with code string (30 digits + 5 spaces + NUL).
/// out must be at least 36 bytes (35 chars + NUL). Returns 1 on success, 0 on fail.
#[no_mangle]
pub unsafe extern "C" fn pqc_security_code(
    my_bundle: *const u8,
    my_bundle_len: usize,
    their_bundle: *const u8,
    their_bundle_len: usize,
    out: *mut u8,
    out_len: usize,
) -> i32 {
    if my_bundle.is_null() || their_bundle.is_null() || out.is_null() || out_len < 36 {
        return 0;
    }
    // compute SHA256 of each bundle, sort, 4000 iter.
    use sha2::{Digest, Sha256};
    const ITER: usize = 4000;
    let a_slice = slice::from_raw_parts(my_bundle, my_bundle_len);
    let b_slice = slice::from_raw_parts(their_bundle, their_bundle_len);
    let mut sha = Sha256::new();
    sha.update(a_slice);
    let ha = sha.finalize_reset();
    sha.update(b_slice);
    let hb = sha.finalize();
    // canonical concat sorted
    let mut h: Vec<u8> = if ha.as_slice() <= hb.as_slice() {
        [ha.as_slice(), hb.as_slice()].concat()
    } else {
        [hb.as_slice(), ha.as_slice()].concat()
    };
    for _ in 0..ITER {
        let mut s2 = Sha256::new();
        s2.update(&h);
        h = s2.finalize().to_vec();
    }
    // format 6 groups 5 digits
    let mut code_bytes = Vec::with_capacity(35);
    let mut idx = 0usize;
    for group in 0..6 {
        if idx + 5 > h.len() {
            return 0;
        }
        let mut v: u64 = 0;
        for j in 0..5 {
            v = (v << 8) | (h[idx + j] as u64);
        }
        if group > 0 {
            code_bytes.push(b' ');
        }
        let grp = format!("{:05}", v % 100000);
        code_bytes.extend_from_slice(grp.as_bytes());
        idx += 5;
    }
    // copy + NUL
    let copy_len = code_bytes.len().min(out_len - 1);
    ptr::copy_nonoverlapping(code_bytes.as_ptr(), out, copy_len);
    ptr::write(out.add(copy_len), 0u8);
    1
}
