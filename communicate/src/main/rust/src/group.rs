//! Sender Key (group) ratchet.
//!
//! Chain key advance uses the same 0x01/0x02 seeds as the 1:1 chain, but message keys
//! derive 48 bytes under info "WhisperGroup" -> iv(16) || cipherKey(32) (verified against
//! `SenderMessageKey` bytecode). Messages are signed with a Curve25519 key pair rather
//! than MAC'd, since a group message has no pairwise secret.

use crate::crypto::{self, CryptoError, Result};
use crate::wire::{SenderKeyDistributionMessage, SenderKeyMessage};

const MAX_MESSAGE_KEYS: usize = 2000;
const RECORD_VERSION: u8 = 1;

pub struct SenderMessageKey {
    pub iteration: u32,
    pub iv: [u8; 16],
    pub cipher_key: [u8; 32],
}

impl SenderMessageKey {
    fn derive(iteration: u32, seed: &[u8]) -> SenderMessageKey {
        let d = crypto::hkdf(seed, &[], crypto::INFO_GROUP, crypto::SENDER_SECRETS_LEN);
        let mut iv = [0u8; 16];
        let mut cipher_key = [0u8; 32];
        iv.copy_from_slice(&d[0..16]);
        cipher_key.copy_from_slice(&d[16..48]);
        SenderMessageKey { iteration, iv, cipher_key }
    }
}

#[derive(Clone)]
pub struct SenderChainKey {
    pub iteration: u32,
    pub key: [u8; 32],
}

impl SenderChainKey {
    pub fn next(&self) -> SenderChainKey {
        SenderChainKey {
            iteration: self.iteration + 1,
            key: crypto::hmac_sha256(&self.key, &[crypto::CHAIN_KEY_SEED]),
        }
    }

    pub fn message_key(&self) -> SenderMessageKey {
        SenderMessageKey::derive(
            self.iteration,
            &crypto::hmac_sha256(&self.key, &[crypto::MESSAGE_KEY_SEED]),
        )
    }
}

/// One sender's state within a group. For our own key both halves of the signing
/// pair are present; for a peer only the public half is.
#[derive(Clone)]
pub struct SenderKeyState {
    pub key_id: u32,
    pub chain: SenderChainKey,
    pub signing_public: [u8; 32],
    pub signing_private: Option<[u8; 32]>,
    pub skipped: Vec<(u32, [u8; 16], [u8; 32])>,
}

impl SenderKeyState {
    pub fn serialize(&self) -> Vec<u8> {
        let mut v = Vec::new();
        v.push(RECORD_VERSION);
        v.extend_from_slice(&self.key_id.to_be_bytes());
        v.extend_from_slice(&self.chain.iteration.to_be_bytes());
        v.extend_from_slice(&self.chain.key);
        v.extend_from_slice(&self.signing_public);
        match &self.signing_private {
            Some(p) => {
                v.push(1);
                v.extend_from_slice(p);
            }
            None => v.push(0),
        }
        v.extend_from_slice(&(self.skipped.len() as u32).to_be_bytes());
        for (it, iv, key) in &self.skipped {
            v.extend_from_slice(&it.to_be_bytes());
            v.extend_from_slice(iv);
            v.extend_from_slice(key);
        }
        v
    }

    pub fn deserialize(data: &[u8]) -> Result<SenderKeyState> {
        let need = |p: usize, n: usize| -> Result<()> {
            if p + n > data.len() {
                Err(CryptoError("truncated sender key record"))
            } else {
                Ok(())
            }
        };
        need(0, 1)?;
        if data[0] != RECORD_VERSION {
            return Err(CryptoError("unsupported sender key record version"));
        }
        let mut p = 1;
        need(p, 4)?;
        let key_id = u32::from_be_bytes(data[p..p + 4].try_into().unwrap());
        p += 4;
        need(p, 4)?;
        let iteration = u32::from_be_bytes(data[p..p + 4].try_into().unwrap());
        p += 4;
        need(p, 32)?;
        let mut key = [0u8; 32];
        key.copy_from_slice(&data[p..p + 32]);
        p += 32;
        need(p, 32)?;
        let mut signing_public = [0u8; 32];
        signing_public.copy_from_slice(&data[p..p + 32]);
        p += 32;
        need(p, 1)?;
        let has_private = data[p] == 1;
        p += 1;
        let signing_private = if has_private {
            need(p, 32)?;
            let mut sp = [0u8; 32];
            sp.copy_from_slice(&data[p..p + 32]);
            p += 32;
            Some(sp)
        } else {
            None
        };
        need(p, 4)?;
        let n = u32::from_be_bytes(data[p..p + 4].try_into().unwrap()) as usize;
        p += 4;
        if n > MAX_MESSAGE_KEYS {
            return Err(CryptoError("too many skipped sender keys"));
        }
        let mut skipped = Vec::with_capacity(n);
        for _ in 0..n {
            need(p, 4 + 16 + 32)?;
            let it = u32::from_be_bytes(data[p..p + 4].try_into().unwrap());
            p += 4;
            let mut iv = [0u8; 16];
            iv.copy_from_slice(&data[p..p + 16]);
            p += 16;
            let mut ck = [0u8; 32];
            ck.copy_from_slice(&data[p..p + 32]);
            p += 32;
            skipped.push((it, iv, ck));
        }
        Ok(SenderKeyState {
            key_id,
            chain: SenderChainKey { iteration, key },
            signing_public,
            signing_private,
            skipped,
        })
    }
}

/// `GroupSessionBuilder.create` — mint our sender key for a group and return both the
/// state to persist and the distribution message to send.
pub fn create<R: rand_core::Rng + rand_core::CryptoRng>(
    rng: &mut R,
) -> (SenderKeyState, SenderKeyDistributionMessage) {
    let mut id_bytes = [0u8; 4];
    rng.fill_bytes(&mut id_bytes);
    // libsignal keeps the id in the low 31 bits.
    let key_id = u32::from_be_bytes(id_bytes) & 0x7fff_ffff;

    let mut chain_key = [0u8; 32];
    rng.fill_bytes(&mut chain_key);
    let (signing_private, signing_public) = crypto::generate_key_pair(rng);

    let state = SenderKeyState {
        key_id,
        chain: SenderChainKey { iteration: 0, key: chain_key },
        signing_public,
        signing_private: Some(signing_private),
        skipped: Vec::new(),
    };
    let skdm = SenderKeyDistributionMessage {
        key_id,
        iteration: 0,
        chain_key: chain_key.to_vec(),
        signing_key: signing_public,
    };
    (state, skdm)
}

/// `GroupSessionBuilder.process` — adopt a peer's sender key.
pub fn process(skdm: &SenderKeyDistributionMessage) -> Result<SenderKeyState> {
    if skdm.chain_key.len() != 32 {
        return Err(CryptoError("SKDM chain key must be 32 bytes"));
    }
    let mut key = [0u8; 32];
    key.copy_from_slice(&skdm.chain_key);
    Ok(SenderKeyState {
        key_id: skdm.key_id,
        chain: SenderChainKey { iteration: skdm.iteration, key },
        signing_public: skdm.signing_key,
        signing_private: None,
        skipped: Vec::new(),
    })
}

pub fn encrypt<R: rand_core::Rng + rand_core::CryptoRng>(
    rng: &mut R,
    state: &mut SenderKeyState,
    plaintext: &[u8],
) -> Result<Vec<u8>> {
    let signing_private = state
        .signing_private
        .ok_or(CryptoError("no signing key for this sender key"))?;
    let mk = state.chain.message_key();
    state.chain = state.chain.next();

    let ciphertext = crypto::aes_cbc_encrypt(&mk.cipher_key, &mk.iv, plaintext);
    let mut random = [0u8; 64];
    rng.fill_bytes(&mut random);
    let msg = SenderKeyMessage::new(
        state.key_id,
        mk.iteration,
        &ciphertext,
        &signing_private,
        &random,
    );
    Ok(msg.serialized)
}

pub fn decrypt(state: &mut SenderKeyState, ciphertext: &[u8]) -> Result<Vec<u8>> {
    // Verify-then-apply: advance the chain / consume skipped keys only
    // on a clone, committing after signature and padding checks pass.
    // A failed decrypt must not consume its iteration.
    let mut working = state.clone();
    let plaintext = decrypt_inner(&mut working, ciphertext)?;
    *state = working;
    Ok(plaintext)
}

fn decrypt_inner(state: &mut SenderKeyState, ciphertext: &[u8]) -> Result<Vec<u8>> {
    let msg = SenderKeyMessage::parse(ciphertext)?;
    if msg.key_id != state.key_id {
        return Err(CryptoError("sender key id mismatch"));
    }
    if !msg.verify_signature(&state.signing_public) {
        return Err(CryptoError("sender key signature failed"));
    }

    // Replay of a key we already stepped over.
    if let Some(pos) = state.skipped.iter().position(|(it, _, _)| *it == msg.iteration) {
        let (_, iv, cipher_key) = state.skipped.remove(pos);
        return crypto::aes_cbc_decrypt(&cipher_key, &iv, &msg.ciphertext);
    }

    let current = state.chain.iteration;
    if msg.iteration < current {
        return Err(CryptoError("duplicate or out-of-order group message"));
    }
    if (msg.iteration - current) as usize > MAX_MESSAGE_KEYS {
        return Err(CryptoError("group message iteration jumps too far ahead"));
    }

    while state.chain.iteration < msg.iteration {
        let mk = state.chain.message_key();
        state.skipped.push((mk.iteration, mk.iv, mk.cipher_key));
        state.chain = state.chain.next();
    }
    let mk = state.chain.message_key();
    state.chain = state.chain.next();

    if state.skipped.len() > MAX_MESSAGE_KEYS {
        let excess = state.skipped.len() - MAX_MESSAGE_KEYS;
        state.skipped.drain(0..excess);
    }

    crypto::aes_cbc_decrypt(&mk.cipher_key, &mk.iv, &msg.ciphertext)
}
