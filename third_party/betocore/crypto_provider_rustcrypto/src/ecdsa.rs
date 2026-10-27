// Copyright 2025 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
//
// you may not use this file except in compliance with the License.
//
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
//
// distributed under the License is distributed on an "AS IS" BASIS,
//
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//
// See the License for the specific language governing permissions and
//
// limitations under the License.
//
//

use crypto_provider::ecdsa::{
    InvalidSignatureBytes, P256EcdsaProvider, VerifyError, PUBLIC_KEY_LENGTH,
};
use p256::ecdsa::signature::{Signer, Verifier};
#[cfg(feature = "std")]
use p256::elliptic_curve::Generate;

pub struct P256Ecdsa;

impl P256EcdsaProvider for P256Ecdsa {
    type P256KeyPair = P256SigningKey;

    type P256PublicKey = P256VerifyingKey;

    type P256Signature = P256Signature;
}

pub struct P256SigningKey(p256::ecdsa::SigningKey);

impl crypto_provider::ecdsa::KeyPair for P256SigningKey {
    type P256PublicKey = P256VerifyingKey;

    type P256Signature = P256Signature;

    fn raw_private_key(
        &self,
        _permit: &crypto_provider::ecdsa::RawPrivateKeyPermit,
    ) -> crypto_provider::ecdsa::RawP256PrivateKey {
        self.0.to_bytes().into()
    }

    #[allow(clippy::unwrap_used)]
    fn from_raw_private_key(
        bytes: &crypto_provider::ecdsa::RawP256PrivateKey,
        _permit: &crypto_provider::ecdsa::RawPrivateKeyPermit,
    ) -> Self
    where
        Self: Sized,
    {
        // Unwrap is safe here, as the bytes for a private key can be anything.
        // We size check the raw bytes beforehand, so there is no failure.
        let signing_key = p256::ecdsa::SigningKey::from_slice(bytes).unwrap();
        Self(signing_key)
    }

    fn sign(&self, msg: &[u8]) -> Self::P256Signature {
        P256Signature(self.0.sign(msg))
    }

    #[cfg(feature = "std")]
    fn generate() -> Self {
        let mut csprng = rand::rngs::ThreadRng::default();
        Self(p256::ecdsa::SigningKey::generate_from_rng(&mut csprng))
    }

    #[allow(clippy::clone_on_copy)]
    fn public_key(&self) -> Self::P256PublicKey {
        P256VerifyingKey(*self.0.verifying_key())
    }
}

pub struct P256VerifyingKey(p256::ecdsa::VerifyingKey);

impl crypto_provider::ecdsa::PublicKey for P256VerifyingKey {
    type Signature = P256Signature;

    #[allow(clippy::unwrap_used)]
    fn from_bytes(
        bytes: &crypto_provider::ecdsa::RawP256PublicKey,
    ) -> Result<Self, crypto_provider::ecdsa::InvalidPublicKeyBytes>
    where
        Self: Sized,
    {
        let _encoded_point = p256::Sec1Point::from_bytes(bytes)
            .map_err(|_| crypto_provider::ecdsa::InvalidPublicKeyBytes)?;
        // Unwrap is safe here, as we've verified during construction that the public key is
        // of the correct size and that it is a point on the curve, so this cannot fail.
        let verifying_key = p256::ecdsa::VerifyingKey::from_sec1_bytes(bytes.as_ref()).unwrap();
        Ok(Self(verifying_key))
    }

    fn to_bytes(&self) -> crypto_provider::ecdsa::RawP256PublicKey {
        let mut public_key = [0u8; PUBLIC_KEY_LENGTH];
        let point = self.0.to_sec1_point(false);
        public_key.copy_from_slice(point.as_bytes());
        public_key
    }

    fn verify_strict(
        &self,
        message: &[u8],
        signature: &Self::Signature,
    ) -> Result<(), VerifyError> {
        self.0.verify(message, &signature.0).map_err(|_| VerifyError)
    }
}

pub struct P256Signature(p256::ecdsa::Signature);

impl crypto_provider::ecdsa::Signature for P256Signature {
    fn from_bytes(
        bytes: &crypto_provider::ecdsa::RawP256Signature,
    ) -> Result<Self, InvalidSignatureBytes> {
        p256::ecdsa::Signature::from_der(bytes).map(Self).map_err(|_| InvalidSignatureBytes)
    }

    #[allow(clippy::unwrap_used)]
    fn to_bytes(&self) -> crypto_provider::ecdsa::RawP256Signature {
        // Unwrap is safe here as the maximum size of a DER-serialized key is 72 bytes.
        // Since we check the size of the signature on initialization from bytes, we don't
        // need a check here and this cannot fail.
        self.0.to_der().as_bytes().try_into().unwrap()
    }
}

#[cfg(test)]
mod test {
    use super::P256Ecdsa;
    use crypto_provider_test::ecdsa::*;

    #[test]
    fn p256_ecdsa_tests() {
        run_wycheproof_test_vectors::<P256Ecdsa>(EcdsaTestName::EcdsaSecp256r1Sha256);
    }
}
