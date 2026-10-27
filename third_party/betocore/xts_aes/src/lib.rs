// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Implementation of the XTS-AES tweakable block cipher.
//!
//! See NIST docs [here](https://luca-giuzzi.unibs.it/corsi/Support/papers-cryptography/1619-2007-NIST-Submission.pdf)
//! and [here](https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-38e.pdf).

#![no_std]

#[cfg(feature = "std")]
extern crate std;

use array_ref::{array_mut_ref, array_ref};
use core::fmt;
use core::marker::PhantomData;

use crypto_provider::aes::{Aes, AesBlock, AesCipher, AesDecryptCipher, AesEncryptCipher};
use crypto_provider::{
    aes::{AesKey, BLOCK_SIZE},
    CryptoProvider,
};

use ldt_tbc::{
    TweakableBlockCipher, TweakableBlockCipherDecrypter, TweakableBlockCipherEncrypter,
    TweakableBlockCipherKey,
};

#[cfg(test)]
mod tweak_tests;

/// XTS-AES as per NIST docs
/// [here](https://luca-giuzzi.unibs.it/corsi/Support/papers-cryptography/1619-2007-NIST-Submission.pdf)
/// and
/// [here](https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-38e.pdf).
///
/// Data encrypted with XTS is divided into data units, each of which corresponds to one tweak
/// (assigned consecutively). If using a numeric tweak (`u128`), the tweak must be converted to
/// little endian bytes (e.g. with `u128::to_le_bytes()`).
///
/// Inside each data unit, there are one or more 16-byte blocks. The length of a data unit SHOULD
/// not to exceed 2^20 blocks (16MB) in order to maintain security properties; see
/// [appendix D](https://luca-giuzzi.unibs.it/corsi/Support/papers-cryptography/1619-2007-NIST-Submission.pdf).
/// The last block (if there is more than one block) may have fewer than 16 bytes, in which case
/// ciphertext stealing will be applied.
///
/// Each block in a data unit has its own block number, generated consecutively, incorporated into
/// the tweak used to encrypt that block.
///
/// There is no support for partial bytes (bit lengths that aren't a multiple of 8).
pub struct XtsAes<A: Aes<Key = K::BlockCipherKey>, K: XtsKey + TweakableBlockCipherKey> {
    _marker: PhantomData<A>,
    _marker2: PhantomData<K>,
}

impl<A: Aes<Key = K::BlockCipherKey>, K: XtsKey + TweakableBlockCipherKey>
    TweakableBlockCipher<BLOCK_SIZE> for XtsAes<A, K>
{
    type EncryptionCipher = XtsEncrypter<A, K>;
    type DecryptionCipher = XtsDecrypter<A, K>;
    type Tweak = Tweak;
    type Key = K;
}

/// The XtsAes128 implementation
pub type XtsAes128<C> = XtsAes<<C as CryptoProvider>::Aes128, XtsAes128Key>;

/// The XtsAes256 implementation
pub type XtsAes256<C> = XtsAes<<C as CryptoProvider>::Aes256, XtsAes256Key>;

/// Struct which provides Xts Aes Encrypt operations
#[repr(C)]
pub struct XtsEncrypter<A: Aes<Key = K::BlockCipherKey>, K: XtsKey> {
    main_encryption_cipher: A::EncryptCipher,
    tweak_encryption_cipher: A::EncryptCipher,
    _marker: PhantomData<K>,
}

/// Struct which provides Xts Aes Decrypt operations
#[repr(C)]
pub struct XtsDecrypter<A: Aes<Key = K::BlockCipherKey>, K: XtsKey> {
    main_decryption_cipher: A::DecryptCipher,
    tweak_encryption_cipher: A::EncryptCipher,
    _marker: PhantomData<K>,
}

#[allow(clippy::expect_used)]
impl<A: Aes<Key = K::BlockCipherKey>, K: XtsKey> XtsEncrypter<A, K> {
    /// Encrypt a data unit in place, using sequential block numbers for each block.
    /// `data_unit` must be at least [BLOCK_SIZE] bytes, and fewer than
    /// `BLOCK_SIZE * 2^20` bytes.
    pub fn encrypt_data_unit(&self, tweak: Tweak, data_unit: &mut [u8]) -> Result<(), XtsError> {
        let (standalone_blocks, last_complete_block, partial_last_block) =
            data_unit_parts(data_unit)?;

        let mut tweaked_xts = self.tweaked(tweak);

        standalone_blocks.chunks_mut(BLOCK_SIZE).for_each(|block| {
            // Until array_chunks is stabilized, using a macro to get array refs out of slice chunks
            // Won't panic because we are only processing complete blocks
            #[allow(clippy::indexing_slicing)]
            tweaked_xts.encrypt_block(array_mut_ref!(block, 0, BLOCK_SIZE));
            tweaked_xts.advance_to_next_block_num();
        });

        if partial_last_block.is_empty() {
            #[allow(clippy::indexing_slicing)]
            tweaked_xts.encrypt_block(array_mut_ref!(last_complete_block, 0, BLOCK_SIZE));
            // nothing to do for the last block since it's empty
        } else {
            // This implements ciphertext stealing for the last two blocks which allows processing
            // of a message length not divisible by block size without any expansion or padding.
            // b is in bytes not bits; we do not consider partial bytes
            let b = partial_last_block.len();

            // Per spec: CC = encrypt P_{m-1} (the last complete block)
            let cc = {
                let mut last_complete_block_plaintext: AesBlock =
                    last_complete_block.try_into().expect("complete block");
                tweaked_xts.encrypt_block(&mut last_complete_block_plaintext);
                tweaked_xts.advance_to_next_block_num();
                last_complete_block_plaintext
            };

            // Copy b bytes of P_m before we overwrite them with C_m
            let mut partial_last_block_plaintext: AesBlock = [0; BLOCK_SIZE];
            partial_last_block_plaintext
                .get_mut(0..b)
                .expect("this should never be hit, since a partial block is always smaller than a complete block")
                .copy_from_slice(partial_last_block);

            // C_m = first b bytes of CC
            partial_last_block.copy_from_slice(
                cc.get(0..b)
                    .expect("b is the len of partial_last_block so it will always be in bounds"),
            );

            // PP = P_m || last (16 - b) bytes of CC
            let mut pp = {
                // the first b bytes have already been written as C_m, so it's safe to overwrite
                let mut cc = cc;
                cc.get_mut(0..b)
                    .expect("partial block len should always be less than a complete block")
                    .copy_from_slice(
                        partial_last_block_plaintext
                            .get(0..b)
                            .expect("b is in range of block length"),
                    );
                cc
            };

            // C_{m-1} = encrypt PP
            tweaked_xts.encrypt_block(&mut pp);
            last_complete_block.copy_from_slice(&pp[..]);
        }

        Ok(())
    }

    /// Returns an [`XtsEncrypterTweaked`] configured with the specified tweak and a block number of 0.
    fn tweaked(&self, tweak: Tweak) -> XtsEncrypterTweaked<'_, A> {
        let mut bytes = tweak.bytes;
        self.tweak_encryption_cipher.encrypt(&mut bytes);

        XtsEncrypterTweaked {
            tweak_state: TweakState::new(bytes),
            enc_cipher: &self.main_encryption_cipher,
        }
    }
}

#[allow(clippy::expect_used)]
impl<A: Aes<Key = K::BlockCipherKey>, K: XtsKey> XtsDecrypter<A, K> {
    /// Decrypt a data unit in place, using sequential block numbers for each block.
    /// `data_unit` must be at least [BLOCK_SIZE] bytes, and fewer than
    /// `BLOCK_SIZE * 2^20` bytes.
    pub fn decrypt_data_unit(&self, tweak: Tweak, data_unit: &mut [u8]) -> Result<(), XtsError> {
        let (standalone_blocks, last_complete_block, partial_last_block) =
            data_unit_parts(data_unit)?;

        let mut tweaked_xts = self.tweaked(tweak);

        standalone_blocks.chunks_mut(BLOCK_SIZE).for_each(|block| {
            #[allow(clippy::indexing_slicing)]
            tweaked_xts.decrypt_block(array_mut_ref!(block, 0, BLOCK_SIZE));
            tweaked_xts.advance_to_next_block_num();
        });

        if partial_last_block.is_empty() {
            #[allow(clippy::indexing_slicing)]
            tweaked_xts.decrypt_block(array_mut_ref!(last_complete_block, 0, BLOCK_SIZE));
        } else {
            let b = partial_last_block.len();

            // tweak state is currently at m-1 block, so capture m-1 state for later use
            let tweak_state_m_1 = tweaked_xts.current_tweak();

            // Per spec: PP = encrypt C_{m-1} (the last complete block) at block num m
            let pp = {
                tweaked_xts.advance_to_next_block_num();
                // Block num is now at m
                // We need C_m later, so make a copy to avoid overwriting
                let mut last_complete_block_ciphertext: AesBlock =
                    last_complete_block.try_into().expect("complete block");
                tweaked_xts.decrypt_block(&mut last_complete_block_ciphertext);
                tweaked_xts.set_tweak(tweak_state_m_1);
                last_complete_block_ciphertext
            };

            // Copy b bytes of C_m before we overwrite them with P_m
            let mut partial_last_block_ciphertext: AesBlock = [0; BLOCK_SIZE];
            partial_last_block_ciphertext
                .get_mut(0..b)
                .expect("this should never be hit, since a partial block is always smaller than a complete block")
                .copy_from_slice(partial_last_block);

            // P_m = first b bytes of PP
            partial_last_block.copy_from_slice(
                pp.get(0..b)
                    .expect("b is the len of partial_last_block so it will always be in bounds"),
            );

            // CC = C_m | CP (last 16-b bytes of PP)
            let cc = {
                let cp = pp.get(b..).expect(
                    "b is in bounds since a partial block is always smaller than a complete block",
                );
                last_complete_block
                    .get_mut(0..b)
                    .expect("partial block length within bounds of complete block")
                    .copy_from_slice(
                        partial_last_block_ciphertext
                            .get(0..b)
                            .expect("partial block length within bounds of complete block"),
                    );
                last_complete_block
                    .get_mut(b..)
                    .expect("partial block length within bounds of complete block")
                    .copy_from_slice(cp);
                last_complete_block
            };

            // decrypting at block num = m -1
            #[allow(clippy::indexing_slicing)]
            tweaked_xts.decrypt_block(array_mut_ref!(cc, 0, BLOCK_SIZE));
        }

        Ok(())
    }

    /// Returns an [`XtsDecrypterTweaked`] configured with the specified tweak and a block number of 0.
    fn tweaked(&self, tweak: Tweak) -> XtsDecrypterTweaked<'_, A> {
        let mut bytes = tweak.bytes;
        self.tweak_encryption_cipher.encrypt(&mut bytes);

        XtsDecrypterTweaked {
            tweak_state: TweakState::new(bytes),
            dec_cipher: &self.main_decryption_cipher,
        }
    }
}

type DataUnitPartsResult<'a> = Result<(&'a mut [u8], &'a mut [u8], &'a mut [u8]), XtsError>;

/// Returns `(standalone blocks, last complete block, partial last block)`.
fn data_unit_parts(data_unit: &mut [u8]) -> DataUnitPartsResult<'_> {
    if data_unit.len() < BLOCK_SIZE {
        return Err(XtsError::DataTooShort);
    } else if data_unit.len() > MAX_XTS_SIZE {
        return Err(XtsError::DataTooLong);
    }
    // complete_blocks >= 1
    let complete_blocks = data_unit.len() / BLOCK_SIZE;
    // standalone_units >= 0 blocks, suffix = last complete block + possible partial block.
    let (standalone_blocks, suffix) = data_unit.split_at_mut((complete_blocks - 1) * BLOCK_SIZE);
    let (last_complete_block, partial_last_block) = suffix.split_at_mut(BLOCK_SIZE);
    Ok((standalone_blocks, last_complete_block, partial_last_block))
}

impl<A: Aes<Key = K::BlockCipherKey>, K: XtsKey + TweakableBlockCipherKey>
    TweakableBlockCipherEncrypter<BLOCK_SIZE> for XtsEncrypter<A, K>
{
    type Key = K;
    type Tweak = Tweak;

    /// Build an [XtsEncrypter] with the provided [Aes] and the provided key.
    fn new(key: &Self::Key) -> Self {
        XtsEncrypter {
            main_encryption_cipher: A::EncryptCipher::new(key.key_1()),
            tweak_encryption_cipher: A::EncryptCipher::new(key.key_2()),
            _marker: Default::default(),
        }
    }

    #[allow(clippy::expect_used)]
    fn encrypt(&self, tweak: Self::Tweak, block: &mut [u8; BLOCK_SIZE]) {
        // We're encrypting precisely one block, so the block number won't advance, and there is no
        // need to apply ciphertext stealing
        self.tweaked(tweak).encrypt_block(block)
    }
}

impl<A: Aes<Key = K::BlockCipherKey>, K: XtsKey + TweakableBlockCipherKey>
    TweakableBlockCipherDecrypter<BLOCK_SIZE> for XtsDecrypter<A, K>
{
    type Key = K;
    type Tweak = Tweak;

    fn new(key: &K) -> Self {
        XtsDecrypter {
            main_decryption_cipher: A::DecryptCipher::new(key.key_1()),
            tweak_encryption_cipher: A::EncryptCipher::new(key.key_2()),
            _marker: Default::default(),
        }
    }

    #[allow(clippy::expect_used)]
    fn decrypt(&self, tweak: Self::Tweak, block: &mut [u8; BLOCK_SIZE]) {
        // We don't need the ciphertext stealing here since the input size is always exactly one block
        self.tweaked(tweak).decrypt_block(block)
    }
}

/// Errors that can occur during XTS encryption/decryption.
#[derive(Debug, PartialEq, Eq)]
pub enum XtsError {
    /// The data is less than one AES block
    DataTooShort,
    /// The data is longer than 2^20 blocks, at which point XTS security degrades
    DataTooLong,
}

/// XTS spec recommends to not go beyond 2^20 blocks.
const MAX_XTS_SIZE: usize = (1 << 20) * BLOCK_SIZE;

/// An XTS key comprised of two keys for the underlying block cipher.
pub trait XtsKey: for<'a> TryFrom<&'a [u8], Error = Self::TryFromError> {
    /// The key used by the block cipher underlying XTS
    type BlockCipherKey;
    /// The error returned when `TryFrom<&[u8]>` fails.
    type TryFromError: fmt::Debug;

    /// Returns the first of the two block cipher keys.
    fn key_1(&self) -> &Self::BlockCipherKey;
    /// Returns the second of the two block cipher keys.
    fn key_2(&self) -> &Self::BlockCipherKey;
}

const AES_128_KEY_SIZE: usize = 16;

/// An XTS-AES-128 key.
pub struct XtsAes128Key {
    key_1: <Self as XtsKey>::BlockCipherKey,
    key_2: <Self as XtsKey>::BlockCipherKey,
}

impl XtsKey for XtsAes128Key {
    type BlockCipherKey = crypto_provider::aes::Aes128Key;
    type TryFromError = XtsKeyTryFromSliceError;

    fn key_1(&self) -> &Self::BlockCipherKey {
        &self.key_1
    }

    fn key_2(&self) -> &Self::BlockCipherKey {
        &self.key_2
    }
}

impl TryFrom<&[u8]> for XtsAes128Key {
    type Error = XtsKeyTryFromSliceError;

    fn try_from(slice: &[u8]) -> Result<Self, Self::Error> {
        try_split_concat_key::<AES_128_KEY_SIZE>(slice)
            .map(|(key_1, key_2)| Self { key_1: key_1.into(), key_2: key_2.into() })
            .ok_or_else(XtsKeyTryFromSliceError::new)
    }
}

#[allow(clippy::expect_used)]
impl From<&[u8; 32]> for XtsAes128Key {
    fn from(array: &[u8; 32]) -> Self {
        let arr1: [u8; AES_128_KEY_SIZE] =
            array[..AES_128_KEY_SIZE].try_into().expect("array is correctly sized");
        let arr2: [u8; AES_128_KEY_SIZE] =
            array[AES_128_KEY_SIZE..].try_into().expect("array is correctly sized");
        Self {
            key_1: crypto_provider::aes::Aes128Key::from(arr1),
            key_2: crypto_provider::aes::Aes128Key::from(arr2),
        }
    }
}

impl TweakableBlockCipherKey for XtsAes128Key {
    type ConcatenatedKeyArray = [u8; 64];

    // Allow index slicing, since a panic will be impossible to hit
    #[allow(clippy::indexing_slicing)]
    fn split_from_concatenated(key: &Self::ConcatenatedKeyArray) -> (Self, Self) {
        ((array_ref!(key, 0, 32)).into(), (array_ref!(key, 32, 32)).into())
    }

    fn concatenate_with(&self, other: &Self) -> Self::ConcatenatedKeyArray {
        let mut out = [0; 64];
        out[..16].copy_from_slice(self.key_1().as_slice());
        out[16..32].copy_from_slice(self.key_2().as_slice());
        out[32..48].copy_from_slice(other.key_1().as_slice());
        out[48..].copy_from_slice(other.key_2().as_slice());

        out
    }
}

const AES_256_KEY_SIZE: usize = 32;

/// An XTS-AES-256 key.
pub struct XtsAes256Key {
    key_1: <Self as XtsKey>::BlockCipherKey,
    key_2: <Self as XtsKey>::BlockCipherKey,
}

impl XtsKey for XtsAes256Key {
    type BlockCipherKey = crypto_provider::aes::Aes256Key;
    type TryFromError = XtsKeyTryFromSliceError;

    fn key_1(&self) -> &Self::BlockCipherKey {
        &self.key_1
    }

    fn key_2(&self) -> &Self::BlockCipherKey {
        &self.key_2
    }
}

impl TryFrom<&[u8]> for XtsAes256Key {
    type Error = XtsKeyTryFromSliceError;

    fn try_from(slice: &[u8]) -> Result<Self, Self::Error> {
        try_split_concat_key::<AES_256_KEY_SIZE>(slice)
            .map(|(key_1, key_2)| Self { key_1: key_1.into(), key_2: key_2.into() })
            .ok_or_else(XtsKeyTryFromSliceError::new)
    }
}

#[allow(clippy::expect_used)]
impl From<&[u8; 64]> for XtsAes256Key {
    fn from(array: &[u8; 64]) -> Self {
        let arr1: [u8; AES_256_KEY_SIZE] =
            array[..AES_256_KEY_SIZE].try_into().expect("array is correctly sized");
        let arr2: [u8; AES_256_KEY_SIZE] =
            array[AES_256_KEY_SIZE..].try_into().expect("array is correctly sized");
        Self {
            key_1: crypto_provider::aes::Aes256Key::from(arr1),
            key_2: crypto_provider::aes::Aes256Key::from(arr2),
        }
    }
}

impl TweakableBlockCipherKey for XtsAes256Key {
    type ConcatenatedKeyArray = [u8; 128];

    // Allow index slicing, since a panic will be impossible to hit
    #[allow(clippy::indexing_slicing)]
    fn split_from_concatenated(key: &Self::ConcatenatedKeyArray) -> (Self, Self) {
        ((array_ref!(key, 0, 64)).into(), (array_ref!(key, 64, 64)).into())
    }

    fn concatenate_with(&self, other: &Self) -> Self::ConcatenatedKeyArray {
        let mut out = [0; 128];
        out[..32].copy_from_slice(self.key_1().as_slice());
        out[32..64].copy_from_slice(self.key_2().as_slice());
        out[64..96].copy_from_slice(other.key_1().as_slice());
        out[96..].copy_from_slice(other.key_2().as_slice());

        out
    }
}

/// The error returned when converting from a slice fails.
#[derive(Debug)]
pub struct XtsKeyTryFromSliceError {
    _private: (),
}

impl XtsKeyTryFromSliceError {
    fn new() -> Self {
        Self { _private: () }
    }
}

/// The tweak for an XTS-AES cipher.
#[derive(Clone)]
pub struct Tweak {
    bytes: AesBlock,
}

impl Tweak {
    /// Little-endian content of the tweak.
    pub fn le_bytes(&self) -> AesBlock {
        self.bytes
    }
}

impl From<AesBlock> for Tweak {
    fn from(bytes: AesBlock) -> Self {
        Self { bytes }
    }
}

impl From<u128> for Tweak {
    fn from(n: u128) -> Self {
        Self { bytes: n.to_le_bytes() }
    }
}

/// An XTS tweak advanced to a particular block num.
#[derive(Clone)]
pub(crate) struct TweakState {
    /// The block number inside the data unit. Should not exceed 2^20.
    block_num: u32,
    /// Original tweak multiplied by the primitive polynomial `block_num` times as per section 5.2
    tweak: AesBlock,
}

impl TweakState {
    /// Create a TweakState from the provided state with block_num = 0.
    fn new(tweak: [u8; BLOCK_SIZE]) -> TweakState {
        TweakState { block_num: 0, tweak }
    }

    /// Advance the tweak state in the data unit to the next block without encrypting
    /// or decrypting the intermediate blocks.
    #[allow(clippy::indexing_slicing)]
    fn advance_to_next_block(&mut self) {
        // Conceptual left shift across the bytes.
        // Most significant byte: if shift would carry, XOR in the coefficients of primitive
        // polynomial in F_2^128 (x^128 = x^7 + x^2 + x + 1) =>  0b1000_0111 in binary.
        let mut target = [0_u8; BLOCK_SIZE];
        target[0] = (self.tweak[0] << 1) ^ ((self.tweak[BLOCK_SIZE - 1] >> 7) * 0b1000_0111);
        for (j, byte) in target.iter_mut().enumerate().skip(1) {
            *byte = (self.tweak[j] << 1) ^ (self.tweak[j - 1] >> 7);
        }
        self.tweak = target;
        self.block_num += 1;
    }

    /// Advance the tweak state in the data unit to the `block_num`'th block without encrypting
    /// or decrypting the intermediate blocks.
    ///
    /// `block_num` should not exceed 2^20.
    ///
    /// # Panics
    /// - If `block_num` is less than the current block num
    #[cfg(test)]
    fn advance_to_block(&mut self, block_num: u32) {
        // It's a programmer error; nothing to recover from
        assert!(self.block_num <= block_num);

        // Multiply by the primitive polynomial as many times as needed, as per section 5.2
        // of IEEE spec
        #[allow(clippy::indexing_slicing)]
        for _ in 0..(block_num - self.block_num) {
            self.advance_to_next_block()
        }
    }
}

/// An XTS-AES cipher configured with an initial tweak that can be advanced through the block
/// numbers for that tweak's data unit.
///
/// Encryption or decryption is per-block only; ciphertext stealing is not implemented at this
/// level.
struct XtsEncrypterTweaked<'a, A: Aes> {
    tweak_state: TweakState,
    enc_cipher: &'a A::EncryptCipher,
}

impl<A: Aes> XtsEncrypterTweaked<'_, A> {
    fn advance_to_next_block_num(&mut self) {
        self.tweak_state.advance_to_next_block()
    }

    /// Encrypt a block in place using the configured tweak and current block number.
    fn encrypt_block(&self, block: &mut AesBlock) {
        array_xor(block, &self.tweak_state.tweak);
        self.enc_cipher.encrypt(block);
        array_xor(block, &self.tweak_state.tweak);
    }
}

/// An XTS-AES cipher configured with an initial tweak that can be advanced through the block
/// numbers for that tweak's data unit.
///
/// Encryption or decryption is per-block only; ciphertext stealing is not implemented at this
/// level.
struct XtsDecrypterTweaked<'a, A: Aes> {
    tweak_state: TweakState,
    dec_cipher: &'a A::DecryptCipher,
}

impl<A: Aes> XtsDecrypterTweaked<'_, A> {
    fn advance_to_next_block_num(&mut self) {
        self.tweak_state.advance_to_next_block()
    }

    /// Get the current tweak state -- useful if needed to reset to an earlier block num.
    fn current_tweak(&self) -> TweakState {
        self.tweak_state.clone()
    }

    /// Set the tweak to a state captured via [`current_tweak`](XtsDecrypterTweaked::current_tweak).
    fn set_tweak(&mut self, tweak_state: TweakState) {
        self.tweak_state = tweak_state;
    }
    fn decrypt_block(&self, block: &mut AesBlock) {
        // CC = C ^ T
        array_xor(block, &self.tweak_state.tweak);
        // PP = decrypt CC
        self.dec_cipher.decrypt(block);
        // P = PP ^ T
        array_xor(block, &self.tweak_state.tweak);
    }
}

/// Calculate `base = base ^ rhs` for each byte.
#[allow(clippy::expect_used)]
fn array_xor(base: &mut AesBlock, rhs: &AesBlock) {
    // hopefully this gets done smartly by the compiler (intel pxor, arm veorq, or equivalent).
    // This seems to happen in practice at opt level 3: https://gcc.godbolt.org/z/qvjE8joMv
    for i in 0..BLOCK_SIZE {
        *base.get_mut(i).expect("i is always a valid index for an AesBlock") ^=
            rhs.get(i).expect("i is always a valid index for an AesBlock");
    }
}

fn try_split_concat_key<const N: usize>(slice: &[u8]) -> Option<([u8; N], [u8; N])> {
    slice.get(0..N).and_then(|slice| slice.try_into().ok()).and_then(|k1: [u8; N]| {
        slice.get(N..).and_then(|slice| slice.try_into().ok()).map(|k2: [u8; N]| (k1, k2))
    })
}
