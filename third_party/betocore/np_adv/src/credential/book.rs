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

//! Traits defining credential books. These are used in the deserialization path to provide
//! the credentials to try for either advertisement version.
//! See [`CredentialBookBuilder`] for batteries-included implementations.

use crate::credential::{
    source::{CredentialSource, SliceCredentialSource},
    v0::{V0DiscoveryCryptoMaterial, V0},
    v1::{
        MicExtendedSaltSectionIdentityResolutionMaterial,
        MicExtendedSaltSectionVerificationMaterial, MicShortSaltSectionIdentityResolutionMaterial,
        MicShortSaltSectionVerificationMaterial, V1DiscoveryCryptoMaterial, V1,
    },
    DiscoveryMetadataCryptoMaterial, MatchableCredential, MatchedCredential, ProtocolVersion,
};
use core::{borrow::Borrow, marker::PhantomData};
use crypto_provider::CryptoProvider;

#[cfg(any(feature = "alloc", test))]
extern crate alloc;

#[cfg(any(feature = "alloc", test))]
use crate::credential::ReferencedMatchedCredential;
#[cfg(any(feature = "alloc", test))]
use alloc::vec::Vec;

/// A collection of credentials to try when attempting to deserialize
/// advertisements of either advertisement version which is
/// valid for the given lifetime.
pub trait CredentialBook<'a>
where
    Self: 'a,
{
    /// The type of the matched credentials for the credentials
    /// held within this credential-book. May lend from the credential-book.
    type Matched: MatchedCredential;

    /// The type of V0 crypto-materials yielded by this credential-book.
    type V0Crypto: V0DiscoveryCryptoMaterial;

    /// The type of V1 crypto-materials yielded by this credential-book.
    type V1Crypto: V1DiscoveryCryptoMaterial;

    /// The iterator type returned from [`CredentialBook#v0_iter()`],
    /// which yields `(crypto-material, match_data)` pairs.
    /// This is a lending iterator which may borrow things from `self`.
    type V0Iterator: Iterator<Item = (Self::V0Crypto, Self::Matched)>;

    /// The iterator type returned from [`CredentialBook#v1_iter()`],
    /// which yields `(crypto-material, match_data)` pairs.
    /// This is a lending iterator which may borrow things from `self`.
    type V1Iterator: Iterator<Item = (Self::V1Crypto, Self::Matched)>;

    /// Returns an iterator over V0 credentials in this credential book.
    fn v0_iter(&'a self) -> Self::V0Iterator;

    /// Returns an iterator over V1 credentials in this credential book.
    fn v1_iter(&'a self) -> Self::V1Iterator;
}

/// Convenient marker struct for building credential-books with
/// some given match-data type.
pub struct CredentialBookBuilder<M: MatchedCredential> {
    _marker: PhantomData<M>,
}

impl<M: MatchedCredential> CredentialBookBuilder<M> {
    /// Constructs a [`CachedSliceCredentialBook`] from the given slices of discovery credentials,
    /// populating its internal buffers of cached credential crypto-materials for up to the
    /// given number of credentials in each version (up to `N0` credentials cached in V0,
    /// and up to `N1` credentials cached in `V1`.)
    pub fn build_cached_slice_book<'a, const N0: usize, const N1: usize, P: CryptoProvider>(
        v0_creds: &'a [MatchableCredential<V0, M>],
        v1_creds: &'a [MatchableCredential<V1, M>],
    ) -> CachedSliceCredentialBook<'a, M, N0, N1> {
        let v0_source = SliceCredentialSource::new(v0_creds);
        let v0_cache = init_cache_from_source::<V0, _, N0, P>(&v0_source);
        let v0_source = CachedCredentialSource::<_, _, N0>::new(v0_source, v0_cache);

        let v1_source = SliceCredentialSource::new(v1_creds);
        let v1_cache = init_cache_from_source::<V1, _, N1, P>(&v1_source);
        let v1_source = CachedCredentialSource::<_, _, N1>::new(v1_source, v1_cache);

        CredentialBookFromSources::new(v0_source, v1_source)
    }

    #[cfg(feature = "alloc")]
    /// Constructs a new credential-book which owns all of the given credentials,
    /// and maintains pre-calculated cryptographic information about them
    /// for speedy advertisement deserialization.
    pub fn build_precalculated_owned_book<P: CryptoProvider>(
        v0_iter: impl IntoIterator<Item = MatchableCredential<V0, M>>,
        v1_iter: impl IntoIterator<Item = MatchableCredential<V1, M>>,
    ) -> PrecalculatedOwnedCredentialBook<M> {
        let v0_source = PrecalculatedOwnedCredentialSource::<V0, M>::new::<P>(v0_iter);
        let v1_source = PrecalculatedOwnedCredentialSource::<V1, M>::new::<P>(v1_iter);
        CredentialBookFromSources::new(v0_source, v1_source)
    }

    #[cfg(feature = "alloc")]
    /// Constructs a new credential-book which owns all of the given credentials,
    /// and maintains pre-calculated cryptographic information about them
    /// for speedy advertisement deserialization.
    pub fn build_precalculated_owned_book_with_id<P: CryptoProvider>(
        v0_iter: impl IntoIterator<Item = MatchableCredential<V0, M>>,
        v1_iter: impl IntoIterator<Item = MatchableCredential<V1, M>>,
    ) -> PrecalculatedOwnedCredentialBookWithId<M>
    where
        M: MatchedCredential + Clone,
    {
        let v0_source = PrecalculatedOwnedV0CredentialSourceWithId::<M>::new::<P>(v0_iter);
        let v1_source = PrecalculatedOwnedV1CredentialSourceWithId::<M>::new::<P>(v1_iter);
        CredentialBookFromSources::new(v0_source, v1_source)
    }
}

// Now, for the implementation details. External implementors still need
// to be able to reference these types (since the returned types from
// [`CredentialBookBuilder`]'s convenience methods are just type-aliases),
// and if they want, they can use them as building-blocks to construct
// their own [`CredentialBook`]s, but they're largely just scaffolding.

/// A structure bundling both V0 and V1 credential-sources to define
/// a credential-book which owns both of these independent sources.
pub struct CredentialBookFromSources<S0, S1> {
    /// The source for V0 credentials.
    v0_source: S0,
    /// The source for V1 credentials.
    v1_source: S1,
}

impl<'a, S0, S1> CredentialBookFromSources<S0, S1>
where
    Self: 'a,
    S0: CredentialSource<'a, V0>,
    S1: CredentialSource<'a, V1, Matched = <S0 as CredentialSource<'a, V0>>::Matched>,
    <S0 as CredentialSource<'a, V0>>::Crypto: V0DiscoveryCryptoMaterial,
    <S1 as CredentialSource<'a, V1>>::Crypto: V1DiscoveryCryptoMaterial,
{
    /// Constructs a new [`CredentialBook`] out of the two given
    /// credential-sources, one for v0 and one for v1. The match-data
    /// of both credential sources must be the same.
    pub fn new(v0_source: S0, v1_source: S1) -> Self {
        Self { v0_source, v1_source }
    }
}

impl<'a, S0, S1> CredentialBook<'a> for CredentialBookFromSources<S0, S1>
where
    Self: 'a,
    S0: CredentialSource<'a, V0>,
    S1: CredentialSource<'a, V1, Matched = <S0 as CredentialSource<'a, V0>>::Matched>,
    <S0 as CredentialSource<'a, V0>>::Crypto: V0DiscoveryCryptoMaterial,
    <S1 as CredentialSource<'a, V1>>::Crypto: V1DiscoveryCryptoMaterial,
{
    type Matched = <S0 as CredentialSource<'a, V0>>::Matched;
    type V0Crypto = <S0 as CredentialSource<'a, V0>>::Crypto;
    type V1Crypto = <S1 as CredentialSource<'a, V1>>::Crypto;
    type V0Iterator = <S0 as CredentialSource<'a, V0>>::Iterator;
    type V1Iterator = <S1 as CredentialSource<'a, V1>>::Iterator;

    fn v0_iter(&'a self) -> Self::V0Iterator {
        self.v0_source.iter()
    }
    fn v1_iter(&'a self) -> Self::V1Iterator {
        self.v1_source.iter()
    }
}

/// Type-level function used internally
/// by [`CachedCredentialSource`] in order to uniformly
/// refer to the "precalculated" crypto-material variants
/// for each protocol version.
pub(crate) mod precalculated_for_version {
    use crate::credential::{
        v0::{PrecalculatedV0DiscoveryCryptoMaterial, V0DiscoveryCredential, V0},
        v1::{PrecalculatedV1DiscoveryCryptoMaterial, V1DiscoveryCredential, V1},
        DiscoveryMetadataCryptoMaterial, ProtocolVersion,
    };
    use crypto_provider::CryptoProvider;

    pub trait MappingTrait<V: ProtocolVersion> {
        type Output: DiscoveryMetadataCryptoMaterial<V>;
        /// Provides pre-calculated crypto-material for the given
        /// discovery credential.
        fn precalculate<C: CryptoProvider>(
            discovery_credential: &V::DiscoveryCredential,
        ) -> Self::Output;
    }

    pub enum Marker {}

    impl MappingTrait<V0> for Marker {
        type Output = PrecalculatedV0DiscoveryCryptoMaterial;
        fn precalculate<C: CryptoProvider>(
            discovery_credential: &V0DiscoveryCredential,
        ) -> PrecalculatedV0DiscoveryCryptoMaterial {
            PrecalculatedV0DiscoveryCryptoMaterial::new::<C>(discovery_credential)
        }
    }

    impl MappingTrait<V1> for Marker {
        type Output = PrecalculatedV1DiscoveryCryptoMaterial;
        fn precalculate<C: CryptoProvider>(
            discovery_credential: &V1DiscoveryCredential,
        ) -> PrecalculatedV1DiscoveryCryptoMaterial {
            discovery_credential.to_precalculated::<C>()
        }
    }
}

type PrecalculatedCryptoForProtocolVersion<V> =
    <precalculated_for_version::Marker as precalculated_for_version::MappingTrait<V>>::Output;

fn precalculate_crypto_material<V: ProtocolVersion, C: CryptoProvider>(
    discovery_credential: &V::DiscoveryCredential,
) -> PrecalculatedCryptoForProtocolVersion<V>
where
    precalculated_for_version::Marker: precalculated_for_version::MappingTrait<V>,
{
    <precalculated_for_version::Marker as precalculated_for_version::MappingTrait<V>>::precalculate::<
        C,
    >(discovery_credential)
}

/// Iterator type for [`CachedCredentialSource`].
pub struct CachedCredentialSourceIterator<'a, V, S, const N: usize>
where
    precalculated_for_version::Marker: precalculated_for_version::MappingTrait<V>,
    V: ProtocolVersion + 'a,
    S: CredentialSource<'a, V> + 'a,
    S::Crypto: Borrow<V::DiscoveryCredential>,
{
    /// The current index of the (enumerated) elements that we're iterating over.
    /// Always counts up at every iteration, and may lie outside of the range
    /// of the `cache` slice for uncached elements.
    current_index: usize,
    /// The cache of pre-calculated crypto-materials from the original source.
    ///
    /// The [`self.source_iterator`]'s (up-to) first `N` elements
    /// must match (up-to) the first `N` elements in the cache,
    /// and they must both appear in the same order.
    cache: &'a [Option<PrecalculatedCryptoForProtocolVersion<V>>; N],
    /// The iterator over the credentials in the original source
    source_iterator: S::Iterator,
}

impl<'a, V, S, const N: usize> Iterator for CachedCredentialSourceIterator<'a, V, S, N>
where
    precalculated_for_version::Marker: precalculated_for_version::MappingTrait<V>,
    V: ProtocolVersion + 'a,
    S: CredentialSource<'a, V> + 'a,
    S::Crypto: Borrow<V::DiscoveryCredential>,
{
    type Item = (PossiblyCachedDiscoveryCryptoMaterial<'a, V>, S::Matched);
    fn next(&mut self) -> Option<Self::Item> {
        // Regardless of what we're going to do with the crypto-materials, we still need
        // to advance the underlying iterator, and bail if it runs out.
        let (discovery_credential, match_data) = self.source_iterator.next()?;
        // Check whether/not we have cached crypto-material for the current index
        let crypto = match self.cache.get(self.current_index) {
            Some(precalculated_crypto_entry) => {
                let precalculated_crypto = precalculated_crypto_entry
                    .as_ref()
                    .expect("iterator still going, but cache is not full?");
                PossiblyCachedDiscoveryCryptoMaterial::from_precalculated(precalculated_crypto)
            }
            None => PossiblyCachedDiscoveryCryptoMaterial::from_discovery_credential(
                discovery_credential.borrow().clone(),
            ),
        };
        // Advance the index forward to point to the next item in the cache.
        self.current_index += 1;
        Some((crypto, match_data))
    }
}

/// A [`CredentialSource`] which augments the externally-provided [`CredentialSource`] with
/// a cache containing up to the specified number of pre-calculated credentials.
pub struct CachedCredentialSource<V: ProtocolVersion, S, const N: usize>
where
    precalculated_for_version::Marker: precalculated_for_version::MappingTrait<V>,
{
    wrapped: S,
    cache: [Option<PrecalculatedCryptoForProtocolVersion<V>>; N],
}

/// Helper function to construct a cache for a [`CachedCredentialSource`] from
/// a reference to some source of discovery-credentials.
///
/// This function needs to be kept separate from [`CachedCredentialSource#new`]
/// to get around otherwise-tricky lifetime issues around ephemerally-borrowing
/// from a moved object within the body of a function.
pub(crate) fn init_cache_from_source<'a, V: ProtocolVersion, S, const N: usize, P: CryptoProvider>(
    original: &'a S,
) -> [Option<PrecalculatedCryptoForProtocolVersion<V>>; N]
where
    S: CredentialSource<'a, V> + 'a,
    S::Crypto: Borrow<V::DiscoveryCredential>,
    precalculated_for_version::Marker: precalculated_for_version::MappingTrait<V>,
{
    let mut cache = [0u8; N].map(|_| None);
    for (cache_entry, source_credential) in cache.iter_mut().zip(original.iter()) {
        let (discovery_credential, _) = source_credential;
        let precalculated_crypto_material =
            precalculate_crypto_material::<V, P>(discovery_credential.borrow());
        let _ = cache_entry.insert(precalculated_crypto_material);
    }
    cache
}

impl<'a, V: ProtocolVersion, S, const N: usize> CachedCredentialSource<V, S, N>
where
    S: CredentialSource<'a, V> + 'a,
    precalculated_for_version::Marker: precalculated_for_version::MappingTrait<V>,
{
    /// Constructs a [`CachedCredentialSource`] from the given [`CredentialSource`]
    /// and the given (initial) cache contents, as constructed via the
    /// [`init_cache_from_source`] helper function.
    pub(crate) fn new(
        wrapped: S,
        cache: [Option<PrecalculatedCryptoForProtocolVersion<V>>; N],
    ) -> Self {
        Self { wrapped, cache }
    }
}

/// Internal implementation of [`PossiblyCachedDiscoveryCryptoMaterial`] to hide
/// what crypto-material variants we're actually storing in cached
/// credential-books.
pub(crate) enum PossiblyCachedDiscoveryCryptoMaterialKind<'a, V: ProtocolVersion>
where
    precalculated_for_version::Marker: precalculated_for_version::MappingTrait<V>,
{
    Discovery(V::DiscoveryCredential),
    Precalculated(&'a PrecalculatedCryptoForProtocolVersion<V>),
}

/// Crypto-materials that are potentially references to
/// already-cached precomputed variants, or raw discovery
/// credentials.
pub struct PossiblyCachedDiscoveryCryptoMaterial<'a, V: ProtocolVersion>
where
    precalculated_for_version::Marker: precalculated_for_version::MappingTrait<V>,
{
    pub(crate) wrapped: PossiblyCachedDiscoveryCryptoMaterialKind<'a, V>,
}

impl<'a, V: ProtocolVersion> PossiblyCachedDiscoveryCryptoMaterial<'a, V>
where
    precalculated_for_version::Marker: precalculated_for_version::MappingTrait<V>,
{
    fn from_discovery_credential(discovery_credential: V::DiscoveryCredential) -> Self {
        Self { wrapped: PossiblyCachedDiscoveryCryptoMaterialKind::Discovery(discovery_credential) }
    }
    fn from_precalculated(precalculated: &'a PrecalculatedCryptoForProtocolVersion<V>) -> Self {
        Self { wrapped: PossiblyCachedDiscoveryCryptoMaterialKind::Precalculated(precalculated) }
    }
}

impl<V: ProtocolVersion> DiscoveryMetadataCryptoMaterial<V>
    for PossiblyCachedDiscoveryCryptoMaterial<'_, V>
where
    precalculated_for_version::Marker: precalculated_for_version::MappingTrait<V>,
{
    fn metadata_nonce<C: CryptoProvider>(&self) -> [u8; 12] {
        match &self.wrapped {
            PossiblyCachedDiscoveryCryptoMaterialKind::Discovery(x) => x.metadata_nonce::<C>(),
            PossiblyCachedDiscoveryCryptoMaterialKind::Precalculated(x) => x.metadata_nonce::<C>(),
        }
    }
}

impl V0DiscoveryCryptoMaterial for PossiblyCachedDiscoveryCryptoMaterial<'_, V0> {
    fn ldt_adv_cipher<C: CryptoProvider>(&self) -> ldt_np_adv::AuthenticatedNpLdtDecryptCipher<C> {
        match &self.wrapped {
            PossiblyCachedDiscoveryCryptoMaterialKind::Discovery(x) => x.ldt_adv_cipher::<C>(),
            PossiblyCachedDiscoveryCryptoMaterialKind::Precalculated(x) => x.ldt_adv_cipher::<C>(),
        }
    }
}

impl V1DiscoveryCryptoMaterial for PossiblyCachedDiscoveryCryptoMaterial<'_, V1> {
    fn mic_short_salt_identity_resolution_material<C: CryptoProvider>(
        &self,
    ) -> MicShortSaltSectionIdentityResolutionMaterial {
        match &self.wrapped {
            PossiblyCachedDiscoveryCryptoMaterialKind::Discovery(x) => {
                x.mic_short_salt_identity_resolution_material::<C>()
            }
            PossiblyCachedDiscoveryCryptoMaterialKind::Precalculated(x) => {
                x.mic_short_salt_identity_resolution_material::<C>()
            }
        }
    }

    fn mic_extended_salt_identity_resolution_material<C: CryptoProvider>(
        &self,
    ) -> MicExtendedSaltSectionIdentityResolutionMaterial {
        match &self.wrapped {
            PossiblyCachedDiscoveryCryptoMaterialKind::Discovery(x) => {
                x.mic_extended_salt_identity_resolution_material::<C>()
            }
            PossiblyCachedDiscoveryCryptoMaterialKind::Precalculated(x) => {
                x.mic_extended_salt_identity_resolution_material::<C>()
            }
        }
    }

    fn mic_short_salt_verification_material<C: CryptoProvider>(
        &self,
    ) -> MicShortSaltSectionVerificationMaterial {
        match &self.wrapped {
            PossiblyCachedDiscoveryCryptoMaterialKind::Discovery(x) => {
                x.mic_short_salt_verification_material::<C>()
            }
            PossiblyCachedDiscoveryCryptoMaterialKind::Precalculated(x) => {
                x.mic_short_salt_verification_material::<C>()
            }
        }
    }

    fn mic_extended_salt_verification_material<C: CryptoProvider>(
        &self,
    ) -> MicExtendedSaltSectionVerificationMaterial {
        match &self.wrapped {
            PossiblyCachedDiscoveryCryptoMaterialKind::Discovery(x) => {
                x.mic_extended_salt_verification_material::<C>()
            }
            PossiblyCachedDiscoveryCryptoMaterialKind::Precalculated(x) => {
                x.mic_extended_salt_verification_material::<C>()
            }
        }
    }
}

impl<'a, V: ProtocolVersion, S, const N: usize> CredentialSource<'a, V>
    for CachedCredentialSource<V, S, N>
where
    Self: 'a,
    S: CredentialSource<'a, V> + 'a,
    S::Crypto: Borrow<V::DiscoveryCredential>,
    precalculated_for_version::Marker: precalculated_for_version::MappingTrait<V>,
    PossiblyCachedDiscoveryCryptoMaterial<'a, V>: DiscoveryMetadataCryptoMaterial<V>,
{
    type Matched = <S as CredentialSource<'a, V>>::Matched;
    type Crypto = PossiblyCachedDiscoveryCryptoMaterial<'a, V>;
    type Iterator = CachedCredentialSourceIterator<'a, V, S, N>;

    fn iter(&'a self) -> Self::Iterator {
        CachedCredentialSourceIterator {
            current_index: 0,
            cache: &self.cache,
            source_iterator: self.wrapped.iter(),
        }
    }
}

/// A simple credentials source for environments which are,
/// for all practical purposes, not space-constrained, and hence
/// can store an arbitrary amount of pre-calculated crypto-materials.
///
/// Requires `alloc` as a result of internally leveraging a `Vec`.
#[cfg(any(feature = "alloc", test))]
pub struct PrecalculatedOwnedCredentialSource<V: ProtocolVersion, M: MatchedCredential>
where
    precalculated_for_version::Marker: precalculated_for_version::MappingTrait<V>,
{
    credentials: Vec<(PrecalculatedCryptoForProtocolVersion<V>, M)>,
}

#[cfg(any(feature = "alloc", test))]
impl<V: ProtocolVersion, M: MatchedCredential> PrecalculatedOwnedCredentialSource<V, M>
where
    precalculated_for_version::Marker: precalculated_for_version::MappingTrait<V>,
{
    /// Pre-calculates crypto material for the given credentials, and constructs a
    /// credentials source which holds owned copies of this crypto-material.
    pub fn new<P: CryptoProvider>(
        credential_iter: impl IntoIterator<Item = MatchableCredential<V, M>>,
    ) -> Self {
        let credentials = credential_iter
            .into_iter()
            .map(|credential| {
                (
                    precalculate_crypto_material::<V, P>(&credential.discovery_credential),
                    credential.match_data,
                )
            })
            .collect();
        Self { credentials }
    }
}

#[cfg(any(feature = "alloc", test))]
fn reference_crypto_and_match_data<C, M: MatchedCredential>(
    pair_ref: &(C, M),
) -> (&C, ReferencedMatchedCredential<'_, M>) {
    let (c, m) = pair_ref;
    (c, m.into())
}

#[cfg(any(feature = "alloc", test))]
impl<'a, V: ProtocolVersion, M: MatchedCredential> CredentialSource<'a, V>
    for PrecalculatedOwnedCredentialSource<V, M>
where
    Self: 'a,
    precalculated_for_version::Marker: precalculated_for_version::MappingTrait<V>,
    &'a PrecalculatedCryptoForProtocolVersion<V>: DiscoveryMetadataCryptoMaterial<V>,
{
    type Matched = ReferencedMatchedCredential<'a, M>;
    type Crypto = &'a PrecalculatedCryptoForProtocolVersion<V>;
    type Iterator = core::iter::Map<
        core::slice::Iter<'a, (PrecalculatedCryptoForProtocolVersion<V>, M)>,
        fn(
            &'a (PrecalculatedCryptoForProtocolVersion<V>, M),
        )
            -> (&'a PrecalculatedCryptoForProtocolVersion<V>, ReferencedMatchedCredential<'a, M>),
    >;

    fn iter(&'a self) -> Self::Iterator {
        self.credentials.iter().map(reference_crypto_and_match_data)
    }
}

/// Type-alias for credential sources which are provided via slice credential-sources
/// with a pre-calculated credential cache layered on top.
pub type CachedSliceCredentialSource<'a, V, M, const N: usize> =
    CachedCredentialSource<V, SliceCredentialSource<'a, V, M>, N>;

/// A [`CredentialBook`] whose sources for V0 and V1 credentials come from the given slices
/// of discovery credentials, with crypto-materials for up to the given number of credentials
/// from the beginning of each slice kept in an in-memory cache.
pub type CachedSliceCredentialBook<'a, M, const N0: usize, const N1: usize> =
    CredentialBookFromSources<
        CachedSliceCredentialSource<'a, V0, M, N0>,
        CachedSliceCredentialSource<'a, V1, M, N1>,
    >;

#[cfg(any(feature = "alloc", test))]
/// A credential-book which owns all of its (non-matched) credential data,
/// and maintains pre-calculated cryptographic information about all
/// stored credentials for speedy advertisement deserialization.
///
/// Use this credential book if memory usage is not terribly tight,
/// and you're operating in an environment with an allocator.
pub type PrecalculatedOwnedCredentialBook<M> = CredentialBookFromSources<
    PrecalculatedOwnedCredentialSource<V0, M>,
    PrecalculatedOwnedCredentialSource<V1, M>,
>;

#[cfg(any(feature = "alloc", test))]
/// A credential-book which owns all of its (non-matched) credential data,
/// and maintains pre-calculated cryptographic information about all
/// stored credentials for speedy advertisement deserialization.
pub type PrecalculatedOwnedCredentialBookWithId<M> = CredentialBookFromSources<
    PrecalculatedOwnedV0CredentialSourceWithId<M>,
    PrecalculatedOwnedV1CredentialSourceWithId<M>,
>;

#[cfg(any(feature = "alloc", test))]
/// A simple credentials source that holds owned, pre-calculated V0 crypto-materials.
pub struct PrecalculatedOwnedV0CredentialSourceWithId<M: MatchedCredential> {
    credentials:
        Vec<
            (
                <precalculated_for_version::Marker as precalculated_for_version::MappingTrait<
                    V0,
                >>::Output,
                M,
            ),
        >,
}

#[cfg(any(feature = "alloc", test))]
impl<M: MatchedCredential + Clone> PrecalculatedOwnedV0CredentialSourceWithId<M> {
    /// Pre-calculates crypto material for the given credentials and constructs a
    /// new source which owns this crypto-material.
    pub fn new<P: CryptoProvider>(
        credential_iter: impl IntoIterator<Item = MatchableCredential<V0, M>>,
    ) -> Self {
        let credentials = credential_iter
            .into_iter()
            .map(|credential| {
                (
                    <precalculated_for_version::Marker as precalculated_for_version::MappingTrait<
                        V0,
                    >>::precalculate::<P>(&credential.discovery_credential),
                    credential.match_data,
                )
            })
            .collect();
        Self { credentials }
    }
}

#[cfg(any(feature = "alloc", test))]
/// A simple credentials source that holds owned, pre-calculated V1 crypto-materials.
pub struct PrecalculatedOwnedV1CredentialSourceWithId<M: MatchedCredential> {
    credentials:
        Vec<
            (
                <precalculated_for_version::Marker as precalculated_for_version::MappingTrait<
                    V1,
                >>::Output,
                M,
            ),
        >,
}

#[cfg(any(feature = "alloc", test))]
impl<M: MatchedCredential + Clone> PrecalculatedOwnedV1CredentialSourceWithId<M> {
    /// Pre-calculates crypto material for the given credentials and constructs a
    /// new source which owns this crypto-material.
    pub fn new<P: CryptoProvider>(
        credential_iter: impl IntoIterator<Item = MatchableCredential<V1, M>>,
    ) -> Self {
        let credentials = credential_iter
            .into_iter()
            .map(|credential| {
                (
                    <precalculated_for_version::Marker as precalculated_for_version::MappingTrait<
                        V1,
                    >>::precalculate::<P>(&credential.discovery_credential),
                    credential.match_data,
                )
            })
            .collect();
        Self { credentials }
    }
}

#[cfg(any(feature = "alloc", test))]
/// Mapping function for the iterator to return a reference to the crypto material
/// and a clone of the match data.
fn crypto_and_cloned_match_data<C, M: MatchedCredential + Clone>(pair_ref: &(C, M)) -> (&C, M) {
    let (c, m) = pair_ref;
    (c, m.clone())
}

#[cfg(any(feature = "alloc", test))]
impl<'a, M: MatchedCredential + Clone> CredentialSource<'a, V0>
    for PrecalculatedOwnedV0CredentialSourceWithId<M>
where
    Self: 'a,
{
    type Matched = M;
    type Crypto = &'a <precalculated_for_version::Marker as precalculated_for_version::MappingTrait<
        V0,
    >>::Output;
    type Iterator = core::iter::Map<
        core::slice::Iter<
            'a,
            (
                <precalculated_for_version::Marker as precalculated_for_version::MappingTrait<
                    V0,
                >>::Output,
                M,
            ),
        >,
        fn(
            &'a (
                <precalculated_for_version::Marker as precalculated_for_version::MappingTrait<
                    V0,
                >>::Output,
                M,
            ),
        ) -> (
            &'a <precalculated_for_version::Marker as precalculated_for_version::MappingTrait<
                V0,
            >>::Output,
            M,
        ),
    >;

    fn iter(&'a self) -> Self::Iterator {
        self.credentials.iter().map(crypto_and_cloned_match_data)
    }
}

#[cfg(any(feature = "alloc", test))]
impl<'a, M: MatchedCredential + Clone> CredentialSource<'a, V1>
    for PrecalculatedOwnedV1CredentialSourceWithId<M>
where
    Self: 'a,
{
    type Matched = M;
    type Crypto = &'a <precalculated_for_version::Marker as precalculated_for_version::MappingTrait<
        V1,
    >>::Output;
    type Iterator = core::iter::Map<
        core::slice::Iter<
            'a,
            (
                <precalculated_for_version::Marker as precalculated_for_version::MappingTrait<
                    V1,
                >>::Output,
                M,
            ),
        >,
        fn(
            &'a (
                <precalculated_for_version::Marker as precalculated_for_version::MappingTrait<
                    V1,
                >>::Output,
                M,
            ),
        ) -> (
            &'a <precalculated_for_version::Marker as precalculated_for_version::MappingTrait<
                V1,
            >>::Output,
            M,
        ),
    >;

    fn iter(&'a self) -> Self::Iterator {
        self.credentials.iter().map(crypto_and_cloned_match_data)
    }
}
