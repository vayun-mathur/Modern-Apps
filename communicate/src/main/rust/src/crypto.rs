//! Primitives for classic Signal protocol v3.
//!
//! Every constant here was read out of `signal-protocol-java` 2.8.1 bytecode rather than
//! from memory, since these are wire-visible and a mismatch is a silent interop break:
//!   * HKDFv3 counter starts at 1 (`HKDFv3.getIterationStartOffset`)
//!   * `DerivedMessageSecrets` = 80 bytes -> cipherKey(32) || macKey(32) || iv(16)
//!   * `DerivedRootSecrets`    = 64 bytes -> rootKey(32) || chainKey(32)
//!   * info strings: "WhisperText", "WhisperRatchet", "WhisperMessageKeys", "WhisperGroup"

use aes::cipher::Array;
use aes::cipher::{BlockCipherDecrypt, BlockCipherEncrypt, KeyInit};
use aes::Aes256;
use curve25519_dalek::constants::ED25519_BASEPOINT_TABLE;
use curve25519_dalek::edwards::{CompressedEdwardsY, EdwardsPoint};
use curve25519_dalek::montgomery::MontgomeryPoint;
use curve25519_dalek::scalar::Scalar;
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256, Sha512};
use subtle::ConstantTimeEq;

pub const KEY_LEN: usize = 32;
/// `DerivedMessageSecrets.SIZE`
pub const MESSAGE_SECRETS_LEN: usize = 80;
/// `DerivedRootSecrets.SIZE`
pub const ROOT_SECRETS_LEN: usize = 64;
/// `SenderMessageKey` derives 48 bytes: iv(16) || cipherKey(32)
pub const SENDER_SECRETS_LEN: usize = 48;

pub const INFO_TEXT: &[u8] = b"WhisperText";
pub const INFO_RATCHET: &[u8] = b"WhisperRatchet";
pub const INFO_MESSAGE_KEYS: &[u8] = b"WhisperMessageKeys";
pub const INFO_GROUP: &[u8] = b"WhisperGroup";

/// `ChainKey.MESSAGE_KEY_SEED` / `CHAIN_KEY_SEED`
pub const MESSAGE_KEY_SEED: u8 = 0x01;
pub const CHAIN_KEY_SEED: u8 = 0x02;

/// DJB (Curve25519) key type prefix on serialized public keys.
pub const DJB_TYPE: u8 = 0x05;

#[derive(Debug)]
pub struct CryptoError(pub &'static str);

pub type Result<T> = core::result::Result<T, CryptoError>;

// ---------------------------------------------------------------------------
// HMAC / HKDF
// ---------------------------------------------------------------------------

pub fn hmac_sha256(key: &[u8], data: &[u8]) -> [u8; 32] {
    // Fully qualified: hmac 0.13 re-exports digest 0.11's KeyInit (matching the
    // workspace sha2 0.11 pin). The bare `KeyInit` name resolves to aes's
    // cipher-0.4 copy — a different trait version that Hmac<Sha256> cannot satisfy.
    let mut mac =
        <Hmac<Sha256> as hmac::KeyInit>::new_from_slice(key).expect("hmac accepts any key length");
    mac.update(data);
    mac.finalize().into_bytes().into()
}

/// HKDF-SHA256 (RFC 5869) with the v3 counter start of 1. An empty salt means the
/// RFC's all-zero salt, matching `HKDF.deriveSecrets(input, info, len)`.
pub fn hkdf(ikm: &[u8], salt: &[u8], info: &[u8], out_len: usize) -> Vec<u8> {
    let zero = [0u8; 32];
    let salt = if salt.is_empty() { &zero[..] } else { salt };
    let prk = hmac_sha256(salt, ikm);

    let mut out = Vec::with_capacity(out_len);
    let mut t: Vec<u8> = Vec::new();
    let mut counter: u8 = 1;
    while out.len() < out_len {
        let mut mac =
            <Hmac<Sha256> as hmac::KeyInit>::new_from_slice(&prk).expect("hmac accepts any key length");
        mac.update(&t);
        mac.update(info);
        mac.update(&[counter]);
        t = mac.finalize().into_bytes().to_vec();
        out.extend_from_slice(&t);
        counter = counter.wrapping_add(1);
    }
    out.truncate(out_len);
    out
}

/// cipherKey(32) || macKey(32) || iv(16)
pub struct MessageKeys {
    pub cipher_key: [u8; 32],
    pub mac_key: [u8; 32],
    pub iv: [u8; 16],
}

impl MessageKeys {
    pub fn derive(seed: &[u8]) -> MessageKeys {
        let d = hkdf(seed, &[], INFO_MESSAGE_KEYS, MESSAGE_SECRETS_LEN);
        let mut cipher_key = [0u8; 32];
        let mut mac_key = [0u8; 32];
        let mut iv = [0u8; 16];
        cipher_key.copy_from_slice(&d[0..32]);
        mac_key.copy_from_slice(&d[32..64]);
        iv.copy_from_slice(&d[64..80]);
        MessageKeys { cipher_key, mac_key, iv }
    }
}

// ---------------------------------------------------------------------------
// AES-256-CBC with PKCS#7
// ---------------------------------------------------------------------------
// Hand-rolled CBC over the `aes` block cipher, matching the repo's existing
// preference for a single explicit function over pulling in a block-mode crate
// (see the `cbc removed - own CBC in pdf_cbc.rs` note in the workspace manifest).

pub fn aes_cbc_encrypt(key: &[u8; 32], iv: &[u8; 16], plaintext: &[u8]) -> Vec<u8> {
    let cipher = Aes256::new(&Array::from(*key));
    let pad = 16 - (plaintext.len() % 16);
    let mut buf = Vec::with_capacity(plaintext.len() + pad);
    buf.extend_from_slice(plaintext);
    buf.extend(std::iter::repeat_n(pad as u8, pad));

    let mut prev = *iv;
    for chunk in buf.chunks_mut(16) {
        for i in 0..16 {
            chunk[i] ^= prev[i];
        }
        let mut block = Array::from(<[u8; 16]>::try_from(&chunk[..]).expect("chunk is 16 bytes"));
        cipher.encrypt_block(&mut block);
        chunk.copy_from_slice(&block);
        prev.copy_from_slice(chunk);
    }
    buf
}

pub fn aes_cbc_decrypt(key: &[u8; 32], iv: &[u8; 16], ciphertext: &[u8]) -> Result<Vec<u8>> {
    if ciphertext.is_empty() || ciphertext.len() % 16 != 0 {
        return Err(CryptoError("ciphertext is not a whole number of AES blocks"));
    }
    let cipher = Aes256::new(&Array::from(*key));
    let mut out = ciphertext.to_vec();
    let mut prev = *iv;
    for chunk in out.chunks_mut(16) {
        let cipher_block: [u8; 16] = chunk.try_into().expect("chunk is 16 bytes");
        let mut block = Array::from(cipher_block);
        cipher.decrypt_block(&mut block);
        chunk.copy_from_slice(&block);
        for i in 0..16 {
            chunk[i] ^= prev[i];
        }
        prev = cipher_block;
    }
    let pad = *out.last().expect("non-empty") as usize;
    if pad == 0 || pad > 16 || pad > out.len() {
        return Err(CryptoError("bad PKCS#7 padding"));
    }
    // Constant-time-ish check of every pad byte before truncating.
    let start = out.len() - pad;
    let mut ok = 1u8;
    for &b in &out[start..] {
        ok &= b.ct_eq(&(pad as u8)).unwrap_u8();
    }
    if ok != 1 {
        return Err(CryptoError("bad PKCS#7 padding"));
    }
    out.truncate(start);
    Ok(out)
}

// ---------------------------------------------------------------------------
// Curve25519: X25519 agreement + XEdDSA signatures
// ---------------------------------------------------------------------------

/// Clamp per RFC 7748 so the scalar is a valid X25519 private key.
fn clamp(mut k: [u8; 32]) -> [u8; 32] {
    k[0] &= 248;
    k[31] &= 127;
    k[31] |= 64;
    k
}

pub fn generate_key_pair<R: rand_core::Rng + rand_core::CryptoRng>(
    rng: &mut R,
) -> ([u8; 32], [u8; 32]) {
    let mut secret = [0u8; 32];
    rng.fill_bytes(&mut secret);
    let secret = clamp(secret);
    (secret, public_from_private(&secret))
}

pub fn public_from_private(private: &[u8; 32]) -> [u8; 32] {
    let scalar = Scalar::from_bytes_mod_order(clamp(*private));
    (ED25519_BASEPOINT_TABLE * &scalar).to_montgomery().to_bytes()
}

pub fn agreement(private: &[u8; 32], public: &[u8; 32]) -> [u8; 32] {
    let secret = x25519_dalek::StaticSecret::from(clamp(*private));
    let peer = x25519_dalek::PublicKey::from(*public);
    secret.diffie_hellman(&peer).to_bytes()
}

/// XEdDSA `hash_i(X) = SHA512((2^256 - 1 - i) || X)` for i = 1, little-endian.
fn hash1(parts: &[&[u8]]) -> Scalar {
    let mut prefix = [0xffu8; 32];
    prefix[0] = 0xfe;
    let mut h = Sha512::new();
    h.update(prefix);
    for p in parts {
        h.update(p);
    }
    Scalar::from_bytes_mod_order_wide(&h.finalize().into())
}

fn hash(parts: &[&[u8]]) -> Scalar {
    let mut h = Sha512::new();
    for p in parts {
        h.update(p);
    }
    Scalar::from_bytes_mod_order_wide(&h.finalize().into())
}

/// XEdDSA key pair: the Edwards public key with sign bit forced to 0, plus the
/// scalar negated when required so it still matches.
fn xeddsa_key_pair(private: &[u8; 32]) -> (Scalar, [u8; 32]) {
    let k = Scalar::from_bytes_mod_order(clamp(*private));
    let ed = ED25519_BASEPOINT_TABLE * &k;
    let mut a_bytes = ed.compress().to_bytes();
    let sign = a_bytes[31] >> 7;
    a_bytes[31] &= 0x7f;
    let a = if sign == 1 { -k } else { k };
    (a, a_bytes)
}

/// `Curve.calculateSignature` — XEdDSA over Curve25519. `random` must be 64 fresh bytes.
pub fn sign(private: &[u8; 32], message: &[u8], random: &[u8; 64]) -> [u8; 64] {
    let (a, a_bytes) = xeddsa_key_pair(private);
    let r = hash1(&[a.as_bytes(), message, random]);
    let cap_r = (ED25519_BASEPOINT_TABLE * &r).compress().to_bytes();
    let h = hash(&[&cap_r, &a_bytes, message]);
    let s = r + h * a;

    let mut sig = [0u8; 64];
    sig[..32].copy_from_slice(&cap_r);
    sig[32..].copy_from_slice(s.as_bytes());
    sig
}

/// `Curve.verifySignature` — XEdDSA verification against a Montgomery public key.
pub fn verify(public: &[u8; 32], message: &[u8], signature: &[u8; 64]) -> bool {
    // Reject a non-canonical s (high bit set) up front, as the spec requires.
    if signature[63] & 0xe0 != 0 {
        return false;
    }
    let mont = MontgomeryPoint(*public);
    // sign bit 0 -> the "positive" Edwards representative.
    let ed: EdwardsPoint = match mont.to_edwards(0) {
        Some(p) => p,
        None => return false,
    };
    let a_bytes = ed.compress().to_bytes();

    let mut r_bytes = [0u8; 32];
    r_bytes.copy_from_slice(&signature[..32]);
    let mut s_bytes = [0u8; 32];
    s_bytes.copy_from_slice(&signature[32..]);

    let s = match Option::<Scalar>::from(Scalar::from_canonical_bytes(s_bytes)) {
        Some(s) => s,
        None => return false,
    };
    let h = hash(&[&r_bytes, &a_bytes, message]);
    // R_check = sB - hA
    let r_check = EdwardsPoint::vartime_double_scalar_mul_basepoint(&(-h), &ed, &s);
    let expected = match CompressedEdwardsY(r_bytes).decompress() {
        Some(p) => p,
        None => return false,
    };
    r_check.compress().to_bytes().ct_eq(&expected.compress().to_bytes()).unwrap_u8() == 1
}

/// Serialized public key: 0x05 || 32 raw bytes.
pub fn serialize_public(public: &[u8; 32]) -> Vec<u8> {
    let mut v = Vec::with_capacity(33);
    v.push(DJB_TYPE);
    v.extend_from_slice(public);
    v
}

/// Accepts either the 33-byte 0x05-prefixed form or a bare 32-byte key.
pub fn parse_public(bytes: &[u8]) -> Result<[u8; 32]> {
    let raw = match bytes.len() {
        33 if bytes[0] == DJB_TYPE => &bytes[1..],
        32 => bytes,
        _ => return Err(CryptoError("bad public key encoding")),
    };
    let mut out = [0u8; 32];
    out.copy_from_slice(raw);
    Ok(out)
}

pub fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.ct_eq(b).unwrap_u8() == 1
}

/// SHA-256, used only for non-secret fingerprints.
pub fn sha256(data: &[u8]) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(data);
    h.finalize().into()
}
