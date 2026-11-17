//! Own CBC impl to drop `cbc` crate (tiny wrapper pulling cipher 0.4.4 duplicate)
//! Single-function usage per user rule – write it ourselves. Removes cbc 0.1.2 + cipher 0.4.4 + inout + crypto-common duplicate.
//!
//! Every entry point is total: key length, IV length and block alignment are
//! validated before any fixed-size conversion, so untrusted PDF bytes can only
//! produce a [`CbcError`], never a panic.

use aes::cipher::{
    consts::U16, Array, BlockCipherDecrypt, BlockCipherEncrypt, BlockSizeUser, KeyInit,
};
use aes::{Aes128, Aes192, Aes256};

const BLOCK: usize = 16;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CbcError {
    KeyLen(usize),
    IvLen(usize),
    NotBlockAligned(usize),
    TooShort(usize),
    BadPadding,
}

impl std::fmt::Display for CbcError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CbcError::KeyLen(n) => write!(f, "invalid AES key length: {n} bytes"),
            CbcError::IvLen(n) => write!(f, "invalid AES-CBC IV length: {n} bytes"),
            CbcError::NotBlockAligned(n) => write!(f, "{n} bytes is not a whole number of 16-byte blocks"),
            CbcError::TooShort(n) => write!(f, "{n} bytes is shorter than the 16-byte IV"),
            CbcError::BadPadding => write!(f, "PKCS#7 padding did not verify"),
        }
    }
}

fn block(bytes: &[u8]) -> Result<[u8; BLOCK], CbcError> {
    bytes
        .try_into()
        .map_err(|_| CbcError::NotBlockAligned(bytes.len()))
}

fn iv_block(iv: &[u8]) -> Result<[u8; BLOCK], CbcError> {
    iv.try_into().map_err(|_| CbcError::IvLen(iv.len()))
}

fn cipher_for<C: KeyInit>(key: &[u8]) -> Result<C, CbcError> {
    C::new_from_slice(key).map_err(|_| CbcError::KeyLen(key.len()))
}

fn encrypt_blocks<C>(cipher: &C, iv: [u8; BLOCK], data: &[u8]) -> Result<Vec<u8>, CbcError>
where
    C: BlockCipherEncrypt + BlockSizeUser<BlockSize = U16>,
{
    if data.len() % BLOCK != 0 {
        return Err(CbcError::NotBlockAligned(data.len()));
    }
    let mut out = Vec::with_capacity(data.len());
    let mut prev = iv;
    for chunk in data.chunks(BLOCK) {
        let mut b = block(chunk)?;
        for i in 0..BLOCK {
            b[i] ^= prev[i];
        }
        let mut ga = Array::from(b);
        cipher.encrypt_block(&mut ga);
        out.extend_from_slice(&ga);
        prev = block(&ga)?;
    }
    Ok(out)
}

fn decrypt_blocks<C>(cipher: &C, iv: [u8; BLOCK], ct: &[u8]) -> Result<Vec<u8>, CbcError>
where
    C: BlockCipherDecrypt + BlockSizeUser<BlockSize = U16>,
{
    if ct.len() % BLOCK != 0 {
        return Err(CbcError::NotBlockAligned(ct.len()));
    }
    let mut out = Vec::with_capacity(ct.len());
    let mut prev = iv;
    for chunk in ct.chunks(BLOCK) {
        let cur = block(chunk)?;
        let mut dec = Array::from(cur);
        cipher.decrypt_block(&mut dec);
        let mut plain = [0u8; BLOCK];
        for i in 0..BLOCK {
            plain[i] = dec[i] ^ prev[i];
        }
        out.extend_from_slice(&plain);
        prev = cur;
    }
    Ok(out)
}

/// Dispatch on the AES key size (16/24/32 bytes) and CBC-encrypt `data`.
fn encrypt_any(key: &[u8], iv: [u8; BLOCK], data: &[u8]) -> Result<Vec<u8>, CbcError> {
    match key.len() {
        16 => encrypt_blocks(&cipher_for::<Aes128>(key)?, iv, data),
        24 => encrypt_blocks(&cipher_for::<Aes192>(key)?, iv, data),
        32 => encrypt_blocks(&cipher_for::<Aes256>(key)?, iv, data),
        n => Err(CbcError::KeyLen(n)),
    }
}

/// Dispatch on the AES key size (16/24/32 bytes) and CBC-decrypt `ct`.
fn decrypt_any(key: &[u8], iv: [u8; BLOCK], ct: &[u8]) -> Result<Vec<u8>, CbcError> {
    match key.len() {
        16 => decrypt_blocks(&cipher_for::<Aes128>(key)?, iv, ct),
        24 => decrypt_blocks(&cipher_for::<Aes192>(key)?, iv, ct),
        32 => decrypt_blocks(&cipher_for::<Aes256>(key)?, iv, ct),
        n => Err(CbcError::KeyLen(n)),
    }
}

pub fn enc_aes256_nopad_zeroiv(key: &[u8], data: &[u8]) -> Result<Vec<u8>, CbcError> {
    if key.len() != 32 {
        return Err(CbcError::KeyLen(key.len()));
    }
    encrypt_blocks(&cipher_for::<Aes256>(key)?, [0u8; BLOCK], data)
}

pub fn dec_aes256_nopad_zeroiv(key: &[u8], ct: &[u8]) -> Result<Vec<u8>, CbcError> {
    if key.len() != 32 {
        return Err(CbcError::KeyLen(key.len()));
    }
    decrypt_blocks(&cipher_for::<Aes256>(key)?, [0u8; BLOCK], ct)
}

pub fn cbc_enc(key: &[u8], iv: &[u8; BLOCK], data: &[u8]) -> Result<Vec<u8>, CbcError> {
    let pad = BLOCK - (data.len() % BLOCK);
    let mut padded = Vec::with_capacity(data.len() + pad);
    padded.extend_from_slice(data);
    padded.extend(std::iter::repeat_n(pad as u8, pad));
    let ct = encrypt_any(key, *iv, &padded)?;
    let mut res = Vec::with_capacity(BLOCK + ct.len());
    res.extend_from_slice(iv);
    res.extend_from_slice(&ct);
    Ok(res)
}

pub fn cbc_dec(key: &[u8], data: &[u8]) -> Result<Vec<u8>, CbcError> {
    if data.len() < BLOCK {
        return Err(CbcError::TooShort(data.len()));
    }
    let (iv, ct) = data.split_at(BLOCK);
    let mut out = decrypt_any(key, iv_block(iv)?, ct)?;
    if out.is_empty() {
        return Err(CbcError::BadPadding);
    }
    let pad = out[out.len() - 1] as usize;
    if pad == 0 || pad > BLOCK || pad > out.len() {
        return Err(CbcError::BadPadding);
    }
    for &b in &out[out.len() - pad..] {
        if b as usize != pad {
            return Err(CbcError::BadPadding);
        }
    }
    out.truncate(out.len() - pad);
    Ok(out)
}

pub fn aes128_cbc_enc_nopad(key: &[u8], iv: &[u8], data: &[u8]) -> Result<Vec<u8>, CbcError> {
    if key.len() != 16 {
        return Err(CbcError::KeyLen(key.len()));
    }
    encrypt_blocks(&cipher_for::<Aes128>(key)?, iv_block(iv)?, data)
}
