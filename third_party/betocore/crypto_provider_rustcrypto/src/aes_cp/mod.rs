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

//! Implementation of `crypto_provider::aes` types using RustCrypto's `aes`.
#![forbid(unsafe_code)]

use aes::cipher::{Array, BlockCipherDecrypt as _, BlockCipherEncrypt as _, KeyInit as _};

use crypto_provider::aes::{
    Aes, Aes128Key, Aes256Key, AesBlock, AesCipher, AesDecryptCipher, AesEncryptCipher, AesKey,
};

/// Module implementing AES-CBC.
pub(crate) mod cbc;

pub(crate) mod ctr;

/// Rust crypto implementation of AES-128
pub struct Aes128;
impl Aes for Aes128 {
    type Key = Aes128Key;
    type EncryptCipher = Aes128Cipher;
    type DecryptCipher = Aes128Cipher;
}

/// Rust crypto implementation of AES-256
pub struct Aes256;
impl Aes for Aes256 {
    type Key = Aes256Key;
    type EncryptCipher = Aes256Cipher;
    type DecryptCipher = Aes256Cipher;
}

/// A Rust Crypto AES-128 cipher used for encryption and decryption
pub struct Aes128Cipher(aes::Aes128);

impl AesCipher for Aes128Cipher {
    type Key = Aes128Key;

    fn new(key: &Self::Key) -> Self {
        Self(aes::Aes128::new(key.as_array().into()))
    }
}

impl AesEncryptCipher for Aes128Cipher {
    fn encrypt(&self, block: &mut AesBlock) {
        self.0.encrypt_block(<&mut Array<_, _>>::from(&mut *block));
    }
}

impl AesDecryptCipher for Aes128Cipher {
    fn decrypt(&self, block: &mut AesBlock) {
        self.0.decrypt_block(<&mut Array<_, _>>::from(&mut *block))
    }
}

/// A Rust Crypto AES-256 cipher used for encryption and decryption
pub struct Aes256Cipher(aes::Aes256);

impl AesCipher for Aes256Cipher {
    type Key = Aes256Key;

    fn new(key: &Self::Key) -> Self {
        Self(aes::Aes256::new(key.as_array().into()))
    }
}

impl AesEncryptCipher for Aes256Cipher {
    fn encrypt(&self, block: &mut AesBlock) {
        self.0.encrypt_block(<&mut Array<_, _>>::from(&mut *block));
    }
}

impl AesDecryptCipher for Aes256Cipher {
    fn decrypt(&self, block: &mut AesBlock) {
        self.0.decrypt_block(<&mut Array<_, _>>::from(&mut *block))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use core::marker::PhantomData;
    use crypto_provider_test::aes::*;

    #[apply(aes_128_encrypt_test_cases)]
    fn aes_128_encrypt_test(testcase: CryptoProviderTestCase<Aes128Cipher>) {
        testcase(PhantomData);
    }

    #[apply(aes_128_decrypt_test_cases)]
    fn aes_128_decrypt_test(testcase: CryptoProviderTestCase<Aes128Cipher>) {
        testcase(PhantomData);
    }

    #[apply(aes_256_encrypt_test_cases)]
    fn aes_256_encrypt_test(testcase: CryptoProviderTestCase<Aes256Cipher>) {
        testcase(PhantomData);
    }

    #[apply(aes_256_decrypt_test_cases)]
    fn aes_256_decrypt_test(testcase: CryptoProviderTestCase<Aes256Cipher>) {
        testcase(PhantomData);
    }
}
