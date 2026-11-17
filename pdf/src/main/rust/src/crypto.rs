//! Standard security handler crypto primitives for the safe PDF stack.
//!
//! Implements the classic PDF password algorithms (PDF 1.7 §7.6.3): key
//! derivation, user/owner password entries, per-object keys, RC4, and the
//! AES-128 (AESV2/V4) and AES-256 (AESV3/V5, algorithm 2.B) handlers, plus the
//! SHA-2 hashes they need. Enough to open, remove a password from, and set a
//! password on RC4- and AES-encrypted PDFs. Public-key / certificate security
//! handlers are not implemented (they require the recipient's private key).
//!
//! `cbc` crate removed – it was a single-function wrapper around `aes` (per user rule).
//! Now own CBC impl in `pdf_cbc.rs` using `aes` crate only.

use md5::{Digest, Md5};

/// The 32-byte password padding string (PDF §7.6.3.3).
const PAD: [u8; 32] = [
    0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
    0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A,
];

/// RC4 stream cipher (symmetric: same call encrypts and decrypts).
pub fn rc4(key: &[u8], data: &[u8]) -> Vec<u8> {
    if key.is_empty() {
        return data.to_vec();
    }
    let mut s: [u8; 256] = [0; 256];
    for (i, v) in s.iter_mut().enumerate() {
        *v = i as u8;
    }
    let mut j = 0usize;
    for i in 0..256 {
        j = (j + s[i] as usize + key[i % key.len()] as usize) & 0xff;
        s.swap(i, j);
    }
    let mut out = Vec::with_capacity(data.len());
    let (mut a, mut b) = (0usize, 0usize);
    for &byte in data {
        a = (a + 1) & 0xff;
        b = (b + s[a] as usize) & 0xff;
        s.swap(a, b);
        let k = s[(s[a] as usize + s[b] as usize) & 0xff];
        out.push(byte ^ k);
    }
    out
}

fn md5(data: &[u8]) -> [u8; 16] {
    let mut h = Md5::new();
    h.update(data);
    h.finalize().into()
}

fn pad_pw(pw: &[u8]) -> [u8; 32] {
    let mut out = [0u8; 32];
    let n = pw.len().min(32);
    out[..n].copy_from_slice(&pw[..n]);
    out[n..].copy_from_slice(&PAD[..32 - n]);
    out
}

/// Algorithm 2: the file encryption key from the user password.
///
/// `encrypt_metadata` is the `/EncryptMetadata` flag (default true). When it is false and
/// `rev >= 4`, step (f) requires four 0xFF bytes to be added to the hash; omitting them
/// derives the wrong key, so a CORRECT password is rejected as wrong.
pub fn compute_key(pw: &[u8], o: &[u8], p: i32, id0: &[u8], n: usize, rev: u8, encrypt_metadata: bool) -> Vec<u8> {
    let mut input = Vec::new();
    input.extend_from_slice(&pad_pw(pw));
    let mut o32 = [0u8; 32];
    let m = o.len().min(32);
    o32[..m].copy_from_slice(&o[..m]);
    input.extend_from_slice(&o32);
    input.extend_from_slice(&(p as u32).to_le_bytes());
    input.extend_from_slice(id0);
    if rev >= 4 && !encrypt_metadata {
        input.extend_from_slice(&[0xFF; 4]);
    }
    let mut hash = md5(&input);
    if rev >= 3 {
        for _ in 0..50 {
            hash = md5(&hash[..n]);
        }
    }
    hash[..n].to_vec()
}

/// Algorithm 4/5: the `/U` entry (first 16 bytes are the validation salt).
pub fn compute_u(key: &[u8], id0: &[u8], rev: u8) -> Vec<u8> {
    if rev == 2 {
        rc4(key, &PAD)
    } else {
        let mut input = Vec::new();
        input.extend_from_slice(&PAD);
        input.extend_from_slice(id0);
        let hash = md5(&input);
        let mut data = rc4(key, &hash);
        for i in 1u8..=19 {
            let k: Vec<u8> = key.iter().map(|b| b ^ i).collect();
            data = rc4(&k, &data);
        }
        data.resize(32, 0);
        data
    }
}

/// Algorithm 3: the `/O` (owner) entry.
pub fn compute_o(owner_pw: &[u8], user_pw: &[u8], n: usize, rev: u8) -> Vec<u8> {
    let mut hash = md5(&pad_pw(owner_pw));
    if rev >= 3 {
        for _ in 0..50 {
            hash = md5(&hash[..n]);
        }
    }
    let okey = hash[..n].to_vec();
    let mut data = rc4(&okey, &pad_pw(user_pw)).to_vec();
    if rev >= 3 {
        for i in 1u8..=19 {
            let k: Vec<u8> = okey.iter().map(|b| b ^ i).collect();
            data = rc4(&k, &data);
        }
    }
    data
}

/// Algorithm 1: the per-object RC4 key for object (`num`,`gen`).
pub fn object_key(key: &[u8], num: u32, gen: u16, n: usize) -> Vec<u8> {
    let mut input = key.to_vec();
    input.extend_from_slice(&num.to_le_bytes()[..3]);
    input.extend_from_slice(&gen.to_le_bytes()[..2]);
    let hash = md5(&input);
    let len = (n + 5).min(16);
    hash[..len].to_vec()
}

/// The `/Encrypt` dictionary values needed to authenticate a password:
/// the candidate password, the `/O` and `/U` entries, `/P`, the first
/// trailer `/ID` element, the key length in bytes, the handler revision
/// and the `/EncryptMetadata` flag.
pub struct AuthParams<'a> {
    pub pw: &'a [u8],
    pub o: &'a [u8],
    pub u: &'a [u8],
    pub p: i32,
    pub id0: &'a [u8],
    pub n: usize,
    pub rev: u8,
    pub encrypt_metadata: bool,
}

/// Verify a password: returns the file key if it matches the `/U` entry.
pub fn authenticate(params: &AuthParams<'_>) -> Option<Vec<u8>> {
    let (pw, o, u, p, id0, n, rev, encrypt_metadata) =
        (params.pw, params.o, params.u, params.p, params.id0, params.n, params.rev, params.encrypt_metadata);
    let key = compute_key(pw, o, p, id0, n, rev, encrypt_metadata);
    let computed = compute_u(&key, id0, rev);
    let cmp_len = if rev == 2 { 32 } else { 16 };
    if computed.len() >= cmp_len && u.len() >= cmp_len && computed[..cmp_len] == u[..cmp_len] {
        Some(key)
    } else {
        None
    }
}

/// Owner-password fallback for R2-R4: per PDF spec algorithm 5 reverse,
/// try to decrypt O with owner password to recover user password, then
/// authenticate. Returns file key if owner password valid.
pub fn authenticate_owner_fallback(params: &AuthParams<'_>) -> Option<Vec<u8>> {
    let (pw, o, u, p, id0, n, rev, encrypt_metadata) =
        (params.pw, params.o, params.u, params.p, params.id0, params.n, params.rev, params.encrypt_metadata);
    // Derive owner key from owner pw
    let mut hash = md5(&pad_pw(pw));
    if rev >= 3 {
        for _ in 0..50 {
            hash = md5(&hash[..n]);
        }
    }
    let okey = hash[..n].to_vec();
    // Decrypt O to get user pad (reverse of compute_o)
    let mut user_pad = if o.len() >= 32 { o[..32].to_vec() } else { o.to_vec() };
    if rev >= 3 {
        for i in (0u8..=19).rev() {
            let k: Vec<u8> = okey.iter().map(|b| b ^ i).collect();
            user_pad = rc4(&k, &user_pad);
        }
    } else {
        user_pad = rc4(&okey, &user_pad);
    }
    // user_pad is the padded user password; strip PKCS-ish Pad for key derivation? Use raw 32 bytes as password input per spec algorithm 2 using user_pad as password (with unpad semantics).
    // The spec says O = encrypted user_pad. We try using unpad? Actually user_pad may contain PAD suffix.
    // Compute key using the recovered user pad truncated to valid length: try first n bytes? Safer to try the 32-byte user_pad directly.
    // Remove trailing PAD bytes heuristics: if last bytes match PAD pattern, trim.
    let candidate_pw = user_pad.clone();
    // Trim PAD suffix: find where PAD pattern ends
    // PDF pad is fixed 32-byte constant; if user_pad tail matches PAD tail, trim.
    // Simple heuristic: if candidate_pw length 32 and ends with PAD char not printable, still use it as-is for compute_key.
    // authenticate will compute key and check U.
    let key_candidate = compute_key(&candidate_pw, o, p, id0, n, rev, encrypt_metadata);
    let computed = compute_u(&key_candidate, id0, rev);
    let cmp_len = if rev == 2 { 32 } else { 16 };
    if computed.len() >= cmp_len && u.len() >= cmp_len && computed[..cmp_len] == u[..cmp_len] {
        return Some(key_candidate);
    }
    // Also try using owner pw directly as user pw (owner may equal user)
    authenticate(params)
}

/// AES-256-CBC encrypt without padding, IV = zeros (used to build /UE and /OE).
/// Inlines `cbc` crate (tiny wrapper around `aes::Aes128/192/256`)
pub fn aes256_cbc_encrypt_nopad_zeroiv(key: &[u8], data: &[u8]) -> Result<Vec<u8>, CbcError> {
    crate::pdf_cbc::enc_aes256_nopad_zeroiv(key, data)
}

/// The R5/R6 password hash (algorithm 2.B for R6, plain SHA-256 for R5).
pub fn hash_v5(pw: &[u8], salt: &[u8], udata: &[u8], rev: u8) -> Option<Vec<u8>> {
    if rev >= 6 {
        hash_2b(pw, salt, udata)
    } else {
        Some(sha256(&[pw, salt, udata].concat()))
    }
}

/// The V5 (AESV3) `/U`, `/UE`, `/O`, `/OE` entries, in that order.
pub type V5Entries = (Vec<u8>, Vec<u8>, Vec<u8>, Vec<u8>);

/// Build the V5 (AESV3) `/U`,`/UE`,`/O`,`/OE` entries for `file_key` (32 bytes)
/// given user/owner passwords and four 8-byte random salts
/// `[u_val, u_key, o_val, o_key]`. `rev` is 5 or 6.
pub fn compute_v5(
    user_pw: &[u8],
    owner_pw: &[u8],
    file_key: &[u8],
    salts: &[[u8; 8]; 4],
    rev: u8,
) -> Option<V5Entries> {
    // /U = hash(user_pw + uValSalt) || uValSalt || uKeySalt   (48 bytes)
    let mut u = hash_v5(user_pw, &salts[0], &[], rev)?;
    u.truncate(32);
    u.extend_from_slice(&salts[0]);
    u.extend_from_slice(&salts[1]);
    // /UE = AES-256(no pad, IV=0) of file_key with hash(user_pw + uKeySalt)
    let ik_u = hash_v5(user_pw, &salts[1], &[], rev)?;
    let ue = aes256_cbc_encrypt_nopad_zeroiv(ik_u.get(..32)?, file_key).ok()?;
    // /O = hash(owner_pw + oValSalt + U) || oValSalt || oKeySalt
    let mut o = hash_v5(owner_pw, &salts[2], &u, rev)?;
    o.truncate(32);
    o.extend_from_slice(&salts[2]);
    o.extend_from_slice(&salts[3]);
    // /OE = AES-256(no pad, IV=0) of file_key with hash(owner_pw + oKeySalt + U)
    let ik_o = hash_v5(owner_pw, &salts[3], &u, rev)?;
    let oe = aes256_cbc_encrypt_nopad_zeroiv(ik_o.get(..32)?, file_key).ok()?;
    Some((u, ue, o, oe))
}

/// The 16-byte `/Perms` block for V5, encrypted with the file key (AES-256-ECB,
/// i.e. CBC with a zero IV, no padding).
pub fn compute_perms_v5(file_key: &[u8], p: i32) -> Option<Vec<u8>> {
    let mut block = [0u8; 16];
    block[..4].copy_from_slice(&p.to_le_bytes());
    block[4..8].copy_from_slice(&[0xFF, 0xFF, 0xFF, 0xFF]);
    block[8] = b'T'; // EncryptMetadata = true
    block[9] = b'a';
    block[10] = b'd';
    block[11] = b'b';
    block[12] = 0;
    block[13] = 0;
    block[14] = 0;
    block[15] = 0;
    aes256_cbc_encrypt_nopad_zeroiv(file_key.get(..32)?, &block).ok()
}

// ---------------------------------------------------------------------------
// AES support (V4 AESV2 / V5 AESV3) – own CBC impl, cbc crate dropped
// ---------------------------------------------------------------------------

use crate::pdf_cbc::CbcError;
use sha2::{Sha256, Sha384, Sha512};

fn sha256(data: &[u8]) -> Vec<u8> {
    let mut h = Sha256::new();
    h.update(data);
    h.finalize().to_vec()
}
fn sha384(data: &[u8]) -> Vec<u8> {
    let mut h = Sha384::new();
    h.update(data);
    h.finalize().to_vec()
}
fn sha512(data: &[u8]) -> Vec<u8> {
    let mut h = Sha512::new();
    h.update(data);
    h.finalize().to_vec()
}

/// AES-CBC decrypt with PKCS#7 padding; the 16-byte IV is prepended to `data`.
/// Inlines cbc crate using aes crate only (single function wrapper)
pub fn aes_cbc_decrypt(key: &[u8], data: &[u8]) -> Result<Vec<u8>, CbcError> {
    crate::pdf_cbc::cbc_dec(key, data)
}

/// AES-CBC encrypt with PKCS#7 padding; a random 16-byte IV is prepended.
/// Inlines cbc crate using aes crate only
pub fn aes_cbc_encrypt(key: &[u8], iv: &[u8; 16], data: &[u8]) -> Result<Vec<u8>, CbcError> {
    crate::pdf_cbc::cbc_enc(key, iv, data)
}

/// AES-256-CBC decrypt without padding, IV = zeros (used for /UE, /OE).
fn aes256_cbc_decrypt_nopad_zeroiv(key: &[u8], ct: &[u8]) -> Result<Vec<u8>, CbcError> {
    crate::pdf_cbc::dec_aes256_nopad_zeroiv(key, ct)
}

/// AES-128-CBC encrypt without padding (algorithm 2.B inner step).
fn aes128_cbc_encrypt_nopad(key: &[u8], iv: &[u8], data: &[u8]) -> Result<Vec<u8>, CbcError> {
    crate::pdf_cbc::aes128_cbc_enc_nopad(key, iv, data)
}

/// Per-object AES key: md5(fileKey + obj + gen + "sAlT") (AESV2 only).
pub fn object_key_aes(key: &[u8], num: u32, gen: u16, n: usize) -> Vec<u8> {
    let mut input = key.to_vec();
    input.extend_from_slice(&num.to_le_bytes()[..3]);
    input.extend_from_slice(&gen.to_le_bytes()[..2]);
    input.extend_from_slice(b"sAlT");
    let hash = md5(&input);
    let len = (n + 5).min(16);
    hash[..len].to_vec()
}

/// Algorithm 2.B: the R6 hardened password hash.
fn hash_2b(pw: &[u8], salt: &[u8], udata: &[u8]) -> Option<Vec<u8>> {
    let mut k = sha256(&[pw, salt, udata].concat());
    let mut round = 0usize;
    loop {
        let mut block = Vec::with_capacity(pw.len() + k.len() + udata.len());
        block.extend_from_slice(pw);
        block.extend_from_slice(&k);
        block.extend_from_slice(udata);
        let mut k1 = Vec::with_capacity(block.len() * 64);
        for _ in 0..64 {
            k1.extend_from_slice(&block);
        }
        let e = aes128_cbc_encrypt_nopad(k.get(..16)?, k.get(16..32)?, &k1).ok()?;
        let m = e.get(..16)?.iter().map(|b| *b as u32).sum::<u32>() % 3;
        k = match m {
            0 => sha256(&e),
            1 => sha384(&e),
            _ => sha512(&e),
        };
        round += 1;
        if round >= 64 && (*e.last().unwrap_or(&0) as usize) <= round.saturating_sub(32) {
            break;
        }
    }
    k.get(..32).map(|s| s.to_vec())
}

/// User-password path for AESV3 (R5/R6).
pub fn authenticate_v5_user(pw: &[u8], u: &[u8], ue: &[u8], rev: u8) -> Option<Vec<u8>> {
    if u.len() < 48 || ue.len() < 32 {
        return None;
    }
    let val_salt = &u[32..40];
    let key_salt = &u[40..48];
    let check = if rev >= 6 {
        hash_2b(pw, val_salt, &[])?
    } else {
        sha256(&[pw, val_salt].concat())
    };
    if check.len() < 32 || check[..32] != u[..32] {
        return None;
    }
    let ikey = if rev >= 6 {
        hash_2b(pw, key_salt, &[])?
    } else {
        sha256(&[pw, key_salt].concat())
    };
    let file_key = aes256_cbc_decrypt_nopad_zeroiv(&ikey, &ue[..32]).ok()?;
    if file_key.len() == 32 {
        Some(file_key)
    } else {
        None
    }
}

/// Owner-password path for AESV3 (R5/R6): checks /O /OE with U as extra input per ISO 32000-2 alg 2.B.
/// `o` is 48-byte /O, `oe` is 32-byte /OE, `u` is 48-byte /U (for owner, /O hashes owner_pw+oVal+U).
/// Returns file key on success.
pub fn authenticate_v5_owner(pw: &[u8], o: &[u8], oe: &[u8], u: &[u8], rev: u8) -> Option<Vec<u8>> {
    if o.len() < 48 || oe.len() < 32 || u.len() < 48 {
        return None;
    }
    let o_val_salt = &o[32..40];
    let o_key_salt = &o[40..48];
    // ISO 32000-2 algorithm 12/13 concatenate the 48-BYTE /U string, not however many
    // bytes the file happened to put there. `u` is untrusted: algorithm 2.B repeats
    // (password || K || udata) 64 times per round for at least 64 rounds, so a 10 MB
    // /U turns one password check into tens of gigabytes of AES.
    let u = &u[..48];
    // Owner validation hash: hash(owner_pw + oValSalt + U)
    let check = if rev >= 6 {
        hash_2b(pw, o_val_salt, u)?
    } else {
        sha256(&[pw, o_val_salt, u].concat())
    };
    if check.len() < 32 || check[..32] != o[..32] {
        return None;
    }
    let ikey = if rev >= 6 {
        hash_2b(pw, o_key_salt, u)?
    } else {
        sha256(&[pw, o_key_salt, u].concat())
    };
    let file_key = aes256_cbc_decrypt_nopad_zeroiv(&ikey, &oe[..32]).ok()?;
    if file_key.len() == 32 {
        Some(file_key)
    } else {
        None
    }
}

include!("crypto_part1.rs");