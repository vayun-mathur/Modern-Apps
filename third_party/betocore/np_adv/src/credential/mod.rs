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

//! Credential types used in deserialization.
//!
//! While simple implementations are provided to get started with, there is likely opportunity for
//! efficiency gains with implementations tailored to suit (e.g. caching a few hot credentials
//! rather than reading from disk every time, etc).

use crate::credential::matched::{MatchedCredential, ReferencedMatchedCredential};
use core::fmt::Debug;
use crypto_provider::{aead::Aead, aes, CryptoProvider, FromCryptoRng};

pub mod book;
pub mod matched;
pub mod source;
pub mod v0;
pub mod v1;

#[cfg(any(test, feature = "alloc"))]
pub mod metadata;

#[cfg(test)]
mod tests;

/// Information about a credential as supplied by the caller.
pub struct MatchableCredential<V: ProtocolVersion, M: MatchedCredential> {
    /// The cryptographic information associated
    /// with this particular credential which is used for discovering
    /// advertisements/advertisement sections generated via the
    /// paired sender credential.
    pub discovery_credential: V::DiscoveryCredential,
    /// The data which will be yielded back to the caller upon a successful
    /// identity-match against this credential.
    pub match_data: M,
}

impl<V: ProtocolVersion, M: MatchedCredential> MatchableCredential<V, M> {
    /// Views this credential as a (borrowed) discovery-credential
    /// combined with some matched credential data
    /// (which is copied - see documentation on [`MatchedCredential`])
    pub fn as_pair(&self) -> (&V::DiscoveryCredential, ReferencedMatchedCredential<'_, M>) {
        (&self.discovery_credential, ReferencedMatchedCredential::from(&self.match_data))
    }
}

/// Error returned when metadata decryption fails.
#[derive(Debug, Eq, PartialEq)]
pub struct MetadataDecryptionError;

/// Seal for protocol versions to enforce totality.
pub(crate) mod protocol_version_seal {
    /// Internal-only supertrait of protocol versions
    /// for the purpose of sealing the trait.
    pub trait ProtocolVersionSeal: Clone {}
}

/// Marker trait for protocol versions (V0/V1)
/// and associated data about them.
pub trait ProtocolVersion: protocol_version_seal::ProtocolVersionSeal {
    /// The discovery credential type for this protocol version, which
    /// is the minimal amount of cryptographic materials that we need
    /// in order to discover advertisements/sections which make
    /// use of the sender-paired version of the credential.
    type DiscoveryCredential: DiscoveryMetadataCryptoMaterial<Self> + Clone;

    /// The native-length identity token for this protocol version
    /// [i.e: if V0, a 14-byte identity token, or if V1, a 16-byte
    /// identity token.]
    type IdentityToken: Clone + AsRef<[u8]> + FromCryptoRng;

    /// Computes the metadata nonce for this version from the given key-seed.
    fn metadata_nonce_from_key_seed<C: CryptoProvider>(
        hkdf: &np_hkdf::NpKeySeedHkdf<C>,
    ) -> <C::Aes128Gcm as Aead>::Nonce;

    // TODO this should be done by the identity provider that owns the corresponding credential
    /// Transforms the passed metadata key (if needed) to a 16-byte metadata key
    /// which may be used for metadata encryption/decryption
    fn extract_metadata_key<C: CryptoProvider>(
        identity_token: Self::IdentityToken,
    ) -> aes::Aes128Key;
}

/// Trait for structures which provide cryptographic
/// materials for discovery in a particular protocol version.
/// See [`crate::credential::v0::V0DiscoveryCryptoMaterial`]
/// and [`crate::credential::v1::V1DiscoveryCryptoMaterial`]
/// for V0 and V1 specializations.
pub trait DiscoveryMetadataCryptoMaterial<V: ProtocolVersion> {
    /// Constructs or copies the metadata nonce used for decryption of associated credential
    /// metadata for the identity represented via this crypto material.
    fn metadata_nonce<C: CryptoProvider>(&self) -> [u8; 12];
}
