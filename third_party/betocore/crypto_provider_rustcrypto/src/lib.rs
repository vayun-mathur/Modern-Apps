// Copyright 2023 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#![no_std]

//! Crate which provides impls for CryptoProvider backed by RustCrypto crates

use core::{fmt::Debug, marker::PhantomData};

pub use aes;
use cfg_if::cfg_if;
pub use hkdf;
pub use hmac;
use rand::{Rng, SeedableRng};
use rand_core::CryptoRng;
use subtle::ConstantTimeEq;

/// Contains the RustCrypto backed impls for AES-GCM-SIV operations
mod aead;
/// Contains the RustCrypto backed AES impl for CryptoProvider
pub mod aes_cp;
/// Contains the RustCrypto backed ECDSA P256 impl for CryptoProvider
mod ecdsa;
/// Contains the RustCrypto backed impl for ed25519 key generation, signing, and verification
mod ed25519;
/// Contains the RustCrypto backed hkdf impl for CryptoProvider
mod hkdf_cp;
/// Contains the RustCrypto backed hmac impl for CryptoProvider
mod hmac_cp;
/// Contains the RustCrypto backed P256 impl for CryptoProvider
mod p256;
/// Contains the RustCrypto backed SHA2 impl for CryptoProvider
mod sha2_cp;
/// Contains the RustCrypto backed X25519 impl for CryptoProvider
mod x25519;

cfg_if! {
    if #[cfg(feature = "std")] {
        /// Providing a type alias for compatibility with existing usage of RustCrypto
        /// by default we use ChaCha20Rng for the underlying csprng (StdRng is no
        /// longer Clone in rand 0.9+, and CryptoProvider requires Clone).
        pub type RustCrypto = RustCryptoImpl<rand_chacha::ChaCha20Rng>;
    } else if #[cfg(feature = "rand_chacha")] {
        /// A no_std compatible implementation of CryptoProvider backed by RustCrypto crates
        pub type RustCrypto = RustCryptoImpl<rand_chacha::ChaCha20Rng>;
    } else {
        compile_error!("Must specify either --features std or --features rand_chacha");
    }
}

/// The RustCrypto backed struct which implements CryptoProvider
#[derive(Default, Clone, Debug, PartialEq, Eq)]
pub struct RustCryptoImpl<R: CryptoRng + SeedableRng + Rng> {
    _marker: PhantomData<R>,
}

impl<R: CryptoRng + SeedableRng + Rng> RustCryptoImpl<R> {
    /// Create a new instance of RustCrypto
    pub fn new() -> Self {
        Self { _marker: Default::default() }
    }
}

impl<R: CryptoRng + SeedableRng + Rng + Eq + PartialEq + Debug + Clone + Send>
    crypto_provider::CryptoProvider for RustCryptoImpl<R>
{
    type HkdfSha256 = hkdf_cp::Hkdf<sha2::Sha256>;
    type HmacSha256 = hmac_cp::Hmac<sha2::Sha256>;
    type HkdfSha512 = hkdf_cp::Hkdf<sha2::Sha512>;
    type HmacSha512 = hmac_cp::Hmac<sha2::Sha512>;
    type AesCbcPkcs7Padded = aes_cp::cbc::AesCbcPkcs7Padded;
    type X25519 = x25519::X25519Ecdh<R>;
    type P256 = p256::P256Ecdh<R>;
    type Sha256 = sha2_cp::RustCryptoSha256;
    type Sha512 = sha2_cp::RustCryptoSha512;
    type Aes128 = aes_cp::Aes128;
    type Aes256 = aes_cp::Aes256;
    type AesCtr128 = aes_cp::ctr::AesCtr128;
    type AesCtr256 = aes_cp::ctr::AesCtr256;
    type Ed25519 = ed25519::Ed25519;
    type Aes128GcmSiv = aead::aes_gcm_siv::AesGcmSiv<aes::Aes128>;
    type Aes256GcmSiv = aead::aes_gcm_siv::AesGcmSiv<aes::Aes256>;
    type Aes128Gcm = aead::aes_gcm::AesGcm<aes::Aes128>;
    type Aes256Gcm = aead::aes_gcm::AesGcm<aes::Aes256>;
    type EcdsaP256 = ecdsa::P256Ecdsa;
    type CryptoRng = RcRng<R>;

    fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
        a.ct_eq(b).into()
    }
}

/// A RustCrypto wrapper for RNG
pub struct RcRng<R>(R);

impl<R: rand_core::CryptoRng + Rng + SeedableRng> crypto_provider::CryptoRng for RcRng<R> {
    fn new() -> Self {
        let mut sys = rand::rngs::SysRng;
        Self(R::try_from_rng(&mut sys).unwrap_or_else(|_| R::from_seed(Default::default())))
    }

    fn next_u64(&mut self) -> u64 {
        self.0.next_u64()
    }

    fn fill(&mut self, dest: &mut [u8]) {
        self.0.fill_bytes(dest)
    }
}

#[cfg(test)]
mod testing;

#[cfg(test)]
mod tests {
    use core::marker::PhantomData;

    use crypto_provider_test::prelude::*;
    use crypto_provider_test::sha2::*;

    use crate::RustCrypto;

    #[apply(sha2_test_cases)]
    fn sha2_tests(testcase: CryptoProviderTestCase<RustCrypto>) {
        testcase(PhantomData::<RustCrypto>);
    }
}
