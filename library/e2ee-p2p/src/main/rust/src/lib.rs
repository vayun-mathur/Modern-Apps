//! ML-KEM-768 + ML-DSA-65 for e2ee-p2p, replacing Bouncy Castle.
//!
//! Crypto: `fips203` (FIPS 203 ML-KEM) and `fips204` (FIPS 204 ML-DSA).
//! Keys cross the JNI/storage boundary as **DER**, byte-compatible with the
//! previously-deployed Bouncy Castle encoding so existing Office E2EE bundles and
//! stored identities keep working:
//!   - public keys: standard X.509 SubjectPublicKeyInfo (fixed 22-byte prefix + raw key)
//!   - private keys: PKCS#8 whose privateKey is BC's `SEQUENCE { seed, expandedKey }`
//!     ("both" form). We parse the expanded key (always the trailing bytes) and, when
//!     generating, re-emit the exact seed+expanded structure.

use fips203::ml_kem_768;
use fips203::traits::{Decaps, Encaps, KeyGen as KemKeyGen, SerDes as KemSerDes};
use fips204::ml_dsa_65;
use fips204::traits::{KeyGen as DsaKeyGen, SerDes as DsaSerDes, Signer, Verifier};
use rand::rngs::SysRng;
/// Fill with OS entropy. `SysRng` is infallible (`TryRng<Error = Infallible>`),
/// so this cannot fail; the `match` satisfies the `panic`-deny lint without
/// an `expect`/`unwrap`.
fn sys_fill(dst: &mut [u8]) {
    use rand_core::TryRng;
    if SysRng.try_fill_bytes(dst).is_err() {
        debug_assert!(false, "SysRng is infallible");
    }
}

// ---- ML-KEM-768 sizes ----
const KEM_EK: usize = 1184; // encapsulation (public) key
const KEM_DK: usize = 2400; // decapsulation (private, expanded) key
const KEM_CT: usize = 1088; // ciphertext
const KEM_SEED: usize = 64; // d || z
/// FindFamily share-link seed: the 32 bytes that travel in the URL fragment.
pub const LINK_SEED: usize = 32;

// ---- ML-DSA-65 sizes ----
const DSA_PK: usize = 1952;
const DSA_SK: usize = 4032; // expanded secret key
const DSA_SEED: usize = 32; // xi
const DSA_SIG: usize = 3309;

// Fixed DER prefixes captured from Bouncy Castle 1.85 (see testvectors/).
// SubjectPublicKeyInfo prefix = SEQ, AlgId(OID), BIT STRING header (+unused=00).
const KEM_PUB_PREFIX: [u8; 22] = [
    0x30, 0x82, 0x04, 0xb2, 0x30, 0x0b, 0x06, 0x09, 0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x04,
    0x02, 0x03, 0x82, 0x04, 0xa1, 0x00,
];
const DSA_PUB_PREFIX: [u8; 22] = [
    0x30, 0x82, 0x07, 0xb2, 0x30, 0x0b, 0x06, 0x09, 0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x03,
    0x12, 0x03, 0x82, 0x07, 0xa1, 0x00,
];
// PKCS#8 prefix up to (and including) the seed OCTET STRING header.
const KEM_PRIV_PREFIX: [u8; 30] = [
    0x30, 0x82, 0x09, 0xbe, 0x02, 0x01, 0x00, 0x30, 0x0b, 0x06, 0x09, 0x60, 0x86, 0x48, 0x01, 0x65,
    0x03, 0x04, 0x04, 0x02, 0x04, 0x82, 0x09, 0xaa, 0x30, 0x82, 0x09, 0xa6, 0x04, 0x40,
];
const KEM_PRIV_MID: [u8; 4] = [0x04, 0x82, 0x09, 0x60]; // expandedKey OCTET STRING header
const DSA_PRIV_PREFIX: [u8; 30] = [
    0x30, 0x82, 0x0f, 0xfe, 0x02, 0x01, 0x00, 0x30, 0x0b, 0x06, 0x09, 0x60, 0x86, 0x48, 0x01, 0x65,
    0x03, 0x04, 0x03, 0x12, 0x04, 0x82, 0x0f, 0xea, 0x30, 0x82, 0x0f, 0xe6, 0x04, 0x20,
];
const DSA_PRIV_MID: [u8; 4] = [0x04, 0x82, 0x0f, 0xc0];

// ---------- DER helpers (fixed layouts) ----------

fn spki_wrap(prefix: &[u8], raw: &[u8]) -> Vec<u8> {
    let mut v = Vec::with_capacity(prefix.len() + raw.len());
    v.extend_from_slice(prefix);
    v.extend_from_slice(raw);
    v
}

/// Raw public key = the trailing `raw_len` bytes of the SPKI DER.
fn spki_raw(der: &[u8], raw_len: usize) -> Option<&[u8]> {
    if der.len() < raw_len {
        return None;
    }
    Some(&der[der.len() - raw_len..])
}

/// Expanded private key = the trailing `exp_len` bytes of the PKCS#8 DER
/// (true for BC's seed+expanded "both" form, where expandedKey is last).
fn pkcs8_expanded(der: &[u8], exp_len: usize) -> Option<&[u8]> {
    if der.len() < exp_len {
        return None;
    }
    Some(&der[der.len() - exp_len..])
}

fn kem_priv_der(seed: &[u8], expanded: &[u8]) -> Vec<u8> {
    let mut v = Vec::with_capacity(KEM_PRIV_PREFIX.len() + KEM_SEED + KEM_PRIV_MID.len() + KEM_DK);
    v.extend_from_slice(&KEM_PRIV_PREFIX);
    v.extend_from_slice(seed);
    v.extend_from_slice(&KEM_PRIV_MID);
    v.extend_from_slice(expanded);
    v
}

fn dsa_priv_der(seed: &[u8], expanded: &[u8]) -> Vec<u8> {
    let mut v = Vec::with_capacity(DSA_PRIV_PREFIX.len() + DSA_SEED + DSA_PRIV_MID.len() + DSA_SK);
    v.extend_from_slice(&DSA_PRIV_PREFIX);
    v.extend_from_slice(seed);
    v.extend_from_slice(&DSA_PRIV_MID);
    v.extend_from_slice(expanded);
    v
}

fn to_arr<const N: usize>(s: &[u8]) -> Option<[u8; N]> {
    s.try_into().ok()
}

/// SP 800-56A single-step concatenation KDF with SHA-256 and empty OtherInfo —
/// exactly what Bouncy Castle's `KEMGenerateSpec(pub, "AES")` applies by default:
/// key = SHA-256(BE32(1) || Z) for the first 32 bytes. Reproduced so the derived
/// AES key matches already-deployed ciphertexts.
fn concat_kdf_sha256(z: &[u8], out_len: usize) -> Vec<u8> {
    use sha2::{Digest, Sha256};
    let mut out = Vec::with_capacity(out_len);
    let mut counter: u32 = 1;
    while out.len() < out_len {
        let mut h = Sha256::new();
        h.update(counter.to_be_bytes());
        h.update(z);
        out.extend_from_slice(&h.finalize());
        counter += 1;
    }
    out.truncate(out_len);
    out
}

// ---------- ML-KEM-768 ----------

/// Generate an ML-KEM keypair. Returns (spkiPubDer, pkcs8PrivDer).
pub fn mlkem_keygen_der() -> (Vec<u8>, Vec<u8>) {
    let mut d = [0u8; 32];
    let mut z = [0u8; 32];
    sys_fill(&mut d);
    sys_fill(&mut z);
    let (ek, dk) = ml_kem_768::KG::keygen_from_seed(d, z);
    let mut seed = [0u8; KEM_SEED];
    seed[..32].copy_from_slice(&d);
    seed[32..].copy_from_slice(&z);
    let pub_der = spki_wrap(&KEM_PUB_PREFIX, &ek.into_bytes());
    let priv_der = kem_priv_der(&seed, &dk.into_bytes());
    (pub_der, priv_der)
}

/// Encapsulate to a recipient SPKI public key. Returns (ciphertext, sharedSecret[32]).
pub fn mlkem_encaps_der(pub_der: &[u8]) -> Option<(Vec<u8>, Vec<u8>)> {
    let raw = spki_raw(pub_der, KEM_EK)?;
    let ek = ml_kem_768::EncapsKey::try_from_bytes(to_arr::<KEM_EK>(raw)?).ok()?;
    let (ssk, ct) = ek.try_encaps().ok()?;
    let key = concat_kdf_sha256(&ssk.into_bytes(), 32);
    Some((ct.into_bytes().to_vec(), key))
}

/// Decapsulate with a PKCS#8 private key. Returns sharedSecret[32].
pub fn mlkem_decaps_der(priv_der: &[u8], ct: &[u8]) -> Option<Vec<u8>> {
    let dk_raw = pkcs8_expanded(priv_der, KEM_DK)?;
    let dk = ml_kem_768::DecapsKey::try_from_bytes(to_arr::<KEM_DK>(dk_raw)?).ok()?;
    let ct = ml_kem_768::CipherText::try_from_bytes(to_arr::<KEM_CT>(ct)?).ok()?;
    let ssk = dk.try_decaps(&ct).ok()?;
    Some(concat_kdf_sha256(&ssk.into_bytes(), 32))
}

// ---------- FindFamily link keys (ML-KEM only, seed-derived) ----------

/// Domain-separation string for link-seed expansion. **Wire format**: the browser
/// share page derives the same key from the same seed, so this string cannot change
/// without breaking every link already handed out.
const LINK_SEED_DOMAIN: &[u8] = b"ff-link-v1";

/// SHAKE256(LINK_SEED_DOMAIN || seed) -> 64 bytes, used as ML-KEM's `d || z`.
fn link_seed_expand(seed: &[u8]) -> [u8; KEM_SEED] {
    use shake::digest::{ExtendableOutput, Update, XofReader};
    let mut h = shake::Shake256::default();
    h.update(LINK_SEED_DOMAIN);
    h.update(seed);
    let mut out = [0u8; KEM_SEED];
    h.finalize_xof().read(&mut out);
    out
}

/// Fresh random link seed.
pub fn mlkem_link_seed_new() -> [u8; LINK_SEED] {
    let mut seed = [0u8; LINK_SEED];
    sys_fill(&mut seed);
    seed
}

/// Deterministically derive an ML-KEM keypair from a 32-byte link seed.
/// Returns (spkiPubDer, pkcs8PrivDer) in the same encoding as `mlkem_keygen_der`.
pub fn mlkem_link_keygen_from_seed(seed: &[u8]) -> Option<(Vec<u8>, Vec<u8>)> {
    if seed.len() != LINK_SEED {
        return None;
    }
    let dz = link_seed_expand(seed);
    let d: [u8; 32] = to_arr(&dz[..32])?;
    let z: [u8; 32] = to_arr(&dz[32..])?;
    let (ek, dk) = ml_kem_768::KG::keygen_from_seed(d, z);
    let pub_der = spki_wrap(&KEM_PUB_PREFIX, &ek.into_bytes());
    let priv_der = kem_priv_der(&dz, &dk.into_bytes());
    Some((pub_der, priv_der))
}

// ---------- ML-DSA-65 ----------

pub fn mldsa_keygen_der() -> (Vec<u8>, Vec<u8>) {
    let mut xi = [0u8; DSA_SEED];
    sys_fill(&mut xi);
    let (pk, sk) = ml_dsa_65::KG::keygen_from_seed(&xi);
    let pub_der = spki_wrap(&DSA_PUB_PREFIX, &pk.into_bytes());
    let priv_der = dsa_priv_der(&xi, &sk.into_bytes());
    (pub_der, priv_der)
}

/// Pure ML-DSA signature, empty context (matches BC's `Signature("ML-DSA")`).
pub fn mldsa_sign_der(priv_der: &[u8], msg: &[u8]) -> Option<Vec<u8>> {
    let sk_raw = pkcs8_expanded(priv_der, DSA_SK)?;
    let sk = ml_dsa_65::PrivateKey::try_from_bytes(to_arr::<DSA_SK>(sk_raw)?).ok()?;
    let sig = sk.try_sign(msg, &[]).ok()?;
    Some(sig.to_vec())
}

pub fn mldsa_verify_der(pub_der: &[u8], msg: &[u8], sig: &[u8]) -> bool {
    let raw = match spki_raw(pub_der, DSA_PK) {
        Some(r) => r,
        None => return false,
    };
    let pk = match to_arr::<DSA_PK>(raw).and_then(|a| ml_dsa_65::PublicKey::try_from_bytes(a).ok()) {
        Some(p) => p,
        None => return false,
    };
    let sig_arr: [u8; DSA_SIG] = match sig.try_into() {
        Ok(a) => a,
        Err(_) => return false,
    };
    pk.verify(msg, &sig_arr, &[])
}

mod jni_bridge;
mod c_bridge;

#[cfg(test)]
mod tests;
