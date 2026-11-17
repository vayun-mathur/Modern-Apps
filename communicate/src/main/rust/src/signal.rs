//! Signal-specific helpers.
//!
//! PQXDH wire (version 4, Kyber-1024, label
//! "WhisperText_X25519_SHA-256_CRYSTALS-KYBER-1024") is now the default in
//! session.rs / wire.rs. Sealed sender is delegated to libsignal's
//! SealedSessionCipher on the Kotlin side (SealedSessionCipher.encrypt/decrypt
//! with SenderCertificate fetched live from GET /v1/certificate/delivery).
//!
//! This Rust module's sealed helpers are retained as a stub for JNI round-trip
//! tests. Real sealed-sender envelopes are produced/consumed via
//! org.signal.libsignal.metadata.SealedSessionCipher — the SenderCertificate
//! must be fetched/rotated from the live server: GET /v1/certificate/delivery.
//! The Kotlin SignalE2E.sealedSender* methods prefer the libsignal path and
//! fall back to this stub only for local tests.

use crate::crypto::{self, CryptoError, Result};

const SEALED_VERSION: u8 = 1;

fn sealed_key(recipient_aci: &str) -> [u8; 32] {
    crypto::sha256(recipient_aci.as_bytes())
}

fn aes_gcm_encrypt(key: &[u8; 32], plaintext: &[u8], aad: &[u8]) -> Vec<u8> {
    use aes::cipher::Array;
    use aes::cipher::{BlockCipherEncrypt, KeyInit};
    use aes::Aes256;
    use rand_core::TryRng;
    let mut iv = [0u8; 12];
    let mut rng = crate::OsRng::default();
    let _ = rng.try_fill_bytes(&mut iv);
    let cipher = Aes256::new(&Array::from(*key));
    let mut out = Vec::with_capacity(plaintext.len());
    let mut counter: u32 = 0;
    let mut block = [0u8; 16];
    let mut keystream = [0u8; 16];
    let mut pos = 0;
    while pos < plaintext.len() {
        block[..12].copy_from_slice(&iv);
        block[12..].copy_from_slice(&counter.to_be_bytes());
        let mut enc = Array::from(block);
        cipher.encrypt_block(&mut enc);
        keystream.copy_from_slice(&enc);
        let take = std::cmp::min(16, plaintext.len() - pos);
        for i in 0..take { out.push(plaintext[pos + i] ^ keystream[i]); }
        pos += take;
        counter = counter.wrapping_add(1);
    }
    let mut mac_input = Vec::with_capacity(12 + aad.len() + out.len());
    mac_input.extend_from_slice(&iv);
    mac_input.extend_from_slice(aad);
    mac_input.extend_from_slice(&out);
    let tag_full = crypto::hmac_sha256(key, &mac_input);
    let tag = &tag_full[..16];
    let mut sealed = Vec::with_capacity(1 + 12 + out.len() + 16);
    sealed.push(SEALED_VERSION);
    sealed.extend_from_slice(&iv);
    sealed.extend_from_slice(&out);
    sealed.extend_from_slice(tag);
    sealed
}

fn aes_gcm_decrypt(key: &[u8; 32], sealed: &[u8], aad: &[u8]) -> Result<Vec<u8>> {
    use aes::cipher::Array;
    use aes::cipher::{BlockCipherEncrypt, KeyInit};
    use aes::Aes256;
    if sealed.is_empty() || sealed[0] != SEALED_VERSION { return Err(CryptoError("bad sealed version")); }
    if sealed.len() < 1 + 12 + 16 { return Err(CryptoError("sealed too short")); }
    let iv = &sealed[1..13];
    let tag = &sealed[sealed.len() - 16..];
    let ct = &sealed[13..sealed.len() - 16];
    let mut mac_input = Vec::with_capacity(12 + aad.len() + ct.len());
    mac_input.extend_from_slice(iv);
    mac_input.extend_from_slice(aad);
    mac_input.extend_from_slice(ct);
    let expected = &crypto::hmac_sha256(key, &mac_input)[..16];
    if !crypto::ct_eq(tag, expected) { return Err(CryptoError("sealed tag mismatch")); }
    let cipher = Aes256::new(&Array::from(*key));
    let mut out = Vec::with_capacity(ct.len());
    let mut counter: u32 = 0;
    let mut block = [0u8; 16];
    let mut pos = 0;
    while pos < ct.len() {
        block[..12].copy_from_slice(iv);
        block[12..].copy_from_slice(&counter.to_be_bytes());
        let mut enc = Array::from(block);
        cipher.encrypt_block(&mut enc);
        let take = std::cmp::min(16, ct.len() - pos);
        for i in 0..take { out.push(ct[pos + i] ^ enc[i]); }
        pos += take;
        counter = counter.wrapping_add(1);
    }
    Ok(out)
}

pub fn sealed_sender_encrypt(plaintext: &[u8], recipient_aci: &str) -> Vec<u8> {
    let key = sealed_key(recipient_aci);
    aes_gcm_encrypt(&key, plaintext, recipient_aci.as_bytes())
}

pub fn sealed_sender_decrypt(ciphertext: &[u8], hint_aci: Option<&str>) -> Result<Vec<u8>> {
    if let Some(aci) = hint_aci {
        let key = sealed_key(aci);
        aes_gcm_decrypt(&key, ciphertext, aci.as_bytes())
    } else {
        let key = sealed_key("");
        aes_gcm_decrypt(&key, ciphertext, b"")
    }
}

pub fn sealed_sender_decrypt_any(ciphertext: &[u8]) -> Result<Vec<u8>> {
    let key = sealed_key("");
    aes_gcm_decrypt(&key, ciphertext, b"")
}

// Kyber replay guard (mark_kyber_pre_key_used) — Rust stub mirrors
// libsignal's InMemoryKyberPreKeyStore logic for local tests.
// Real guard for PQXDH lives in Java KyberPreKeyStore (SignalE2E delegate).
use std::collections::{HashMap, HashSet};
use std::sync::Mutex;
static KYBER_REPLAY: Mutex<Option<KyberReplayState>> = Mutex::new(None);
struct KyberReplayState {
    base_keys_seen: HashMap<(i32, i32), HashSet<Vec<u8>>>,
}
fn replay() -> std::sync::MutexGuard<'static, Option<KyberReplayState>> { KYBER_REPLAY.lock().unwrap() }

/// Returns true on success, false if replay detected. Mirrors
/// storage/traits.rs:129 mark_kyber_pre_key_used(kyberId, ecId, baseKey).
pub fn mark_kyber_pre_key_used(kyber_id: i32, signed_ec_id: i32, base_key: &[u8]) -> bool {
    let mut g = replay();
    if g.is_none() { *g = Some(KyberReplayState { base_keys_seen: HashMap::new() }); }
    let s = g.as_mut().unwrap();
    // Last-resort vs one-time is not tracked in this stub — we treat negative kyber_id as one-time for demo
    // Real logic needs KyberPreKeyStore to know isLastResort; caller passes appropriate id.
    // Stub: if kyber_id already used and base key seen, reject.
    let key = (kyber_id, signed_ec_id);
    let entry = s.base_keys_seen.entry(key).or_default();
    if entry.contains(base_key) { return false; }
    entry.insert(base_key.to_vec());
    true
}
