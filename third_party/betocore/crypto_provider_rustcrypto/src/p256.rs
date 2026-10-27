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

use crate::RcRng;
use core::marker::PhantomData;
use crypto_provider::{
    elliptic_curve::{EcdhProvider, EphemeralSecret},
    p256::{PointCompression, P256},
    tinyvec::ArrayVec,
};
use p256::{
    elliptic_curve,
    elliptic_curve::sec1::{FromSec1Point, Sec1Point, ToSec1Point},
    elliptic_curve::Generate,
    NistP256,
};
use rand::{Rng, SeedableRng};
use rand_core::CryptoRng;

/// Implementation of NIST-P256 using RustCrypto crates.
pub struct P256Ecdh<R> {
    _marker: PhantomData<R>,
}

impl<R: CryptoRng + SeedableRng + Rng + Send> EcdhProvider<P256> for P256Ecdh<R> {
    type PublicKey = P256PublicKey;
    type EphemeralSecret = P256EphemeralSecret<R>;
    type SharedSecret = [u8; 32];
}

/// A NIST-P256 public key.
#[derive(Debug, PartialEq, Eq)]
pub struct P256PublicKey(p256::PublicKey);
impl crypto_provider::p256::P256PublicKey for P256PublicKey {
    type Error = elliptic_curve::Error;

    fn from_sec1_bytes(bytes: &[u8]) -> Result<Self, Self::Error> {
        p256::PublicKey::from_sec1_bytes(bytes).map(Self)
    }

    fn to_sec1_bytes(&self, point_compression: PointCompression) -> ArrayVec<[u8; 65]> {
        let mut bytes = ArrayVec::<[u8; 65]>::new();
        bytes.extend_from_slice(
            self.0.to_sec1_point(point_compression == PointCompression::Compressed).as_bytes(),
        );
        bytes
    }

    #[allow(clippy::expect_used)]
    fn to_affine_coordinates(&self) -> Result<([u8; 32], [u8; 32]), Self::Error> {
        let p256_key = self.0.to_sec1_point(false);
        let x: &[u8; 32] =
            p256_key.x().expect("Generated key should not be on identity point").as_ref();
        let y: &[u8; 32] =
            p256_key.y().expect("Generated key should not be on identity point").as_ref();
        Ok((*x, *y))
    }
    fn from_affine_coordinates(x: &[u8; 32], y: &[u8; 32]) -> Result<Self, Self::Error> {
        let key_option: Option<p256::PublicKey> =
            p256::PublicKey::from_sec1_point(&Sec1Point::<NistP256>::from_affine_coordinates(
                x.into(),
                y.into(),
                false,
            ))
            .into();
        key_option.map(Self).ok_or(elliptic_curve::Error)
    }
}

/// Ephemeral secrect for use in a P256 Diffie-Hellman
pub struct P256EphemeralSecret<R: CryptoRng + SeedableRng + Rng> {
    secret: p256::ecdh::EphemeralSecret,
    _marker: PhantomData<R>,
}

impl<R: CryptoRng + SeedableRng + Rng + Send> EphemeralSecret<P256> for P256EphemeralSecret<R> {
    type Impl = P256Ecdh<R>;
    type Error = sec1::Error;
    type Rng = RcRng<R>;
    type EncodedPublicKey = ArrayVec<[u8; 65]>;

    fn generate_random(rng: &mut Self::Rng) -> Self {
        Self {
            secret: p256::ecdh::EphemeralSecret::generate_from_rng(&mut rng.0),
            _marker: Default::default(),
        }
    }

    fn public_key_bytes(&self) -> Self::EncodedPublicKey {
        let mut bytes = Self::EncodedPublicKey::new();
        bytes.extend_from_slice(self.secret.public_key().to_sec1_point(false).as_bytes());
        bytes
    }

    fn diffie_hellman(
        self,
        other_pub: &P256PublicKey,
    ) -> Result<<Self::Impl as EcdhProvider<P256>>::SharedSecret, Self::Error> {
        let shared_secret = p256::ecdh::EphemeralSecret::diffie_hellman(&self.secret, &other_pub.0);
        let bytes: <Self::Impl as EcdhProvider<P256>>::SharedSecret =
            (*shared_secret.raw_secret_bytes()).into();
        Ok(bytes)
    }
}

#[cfg(test)]
impl<R: CryptoRng + SeedableRng + Rng + Send>
    crypto_provider_test::elliptic_curve::EphemeralSecretForTesting<P256>
    for P256EphemeralSecret<R>
{
    fn from_private_components(
        private_bytes: &[u8; 32],
        _public_key: &P256PublicKey,
    ) -> Result<Self, Self::Error> {
        Ok(Self {
            secret: p256::ecdh::EphemeralSecret::generate_from_rng(
                &mut crate::testing::MockCryptoRng { values: private_bytes.iter() },
            ),
            _marker: Default::default(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::P256Ecdh;
    use core::marker::PhantomData;
    use crypto_provider_test::p256::*;
    use rand::rngs::StdRng;

    #[apply(p256_test_cases)]
    fn p256_tests(testcase: CryptoProviderTestCase<P256Ecdh<StdRng>>, _name: &str) {
        testcase(PhantomData::<P256Ecdh<StdRng>>)
    }
}
