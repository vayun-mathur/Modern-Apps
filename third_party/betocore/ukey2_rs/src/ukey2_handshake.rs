#![allow(missing_docs)]
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
#![allow(clippy::expect_used)]
// TODO: remove this and convert all unwraps to expects
#![allow(clippy::unwrap_used)]

use std::{
    collections::HashSet,
    fmt::{self, Formatter},
    marker::PhantomData,
};

use crypto_provider::elliptic_curve::EphemeralSecret;
use crypto_provider::p256::{P256EcdhProvider, P256PublicKey, P256};
use crypto_provider::x25519::X25519;
use crypto_provider::CryptoProvider;
use crypto_provider::{
    elliptic_curve::{EcdhProvider, PublicKey},
    hkdf::Hkdf,
    sha2::{Sha256, Sha512},
    CryptoRng,
};
use ukey2_proto::protobuf::Message;
use ukey2_proto::ukey2_all_proto::{securemessage, ukey};

use crate::proto_adapter::NextProtocol;
pub(crate) use crate::proto_adapter::{
    CipherCommitment, ClientFinished, ClientInit, GenericPublicKey, HandshakeCipher,
    IntoAdapter as _, ServerInit, ToWrappedMessage as _,
};

pub trait WireCompatibilityLayer {
    fn encode_public_key<C: CryptoProvider>(
        &self,
        key: Vec<u8>,
        cipher: HandshakeCipher,
    ) -> Option<Vec<u8>>;
    fn decode_public_key<C: CryptoProvider>(
        &self,
        key: Vec<u8>,
        cipher: HandshakeCipher,
    ) -> Option<Vec<u8>>;
}

#[derive(Clone)]
pub enum HandshakeImplementation {
    /// Implementation of ukey2 exchange handshake according to the specs in
    /// <https://github.com/google/ukey2/blob/master/README.md>.
    ///
    /// In particular, when encoding for the P256 public key, this uses the standardized encoding
    /// described in [SEC 1](https://www.secg.org/sec1-v2.pdf).
    ///
    /// For X25519, the public key is the x-coordinate in little endian per RFC 7748.
    Spec,
    /// Implementation of ukey2 exchange handshake that matches
    /// [the Java implementation](https://github.com/google/ukey2/blob/master/src/main/java/com/google/security/cryptauth/lib/securegcm/Ukey2Handshake.java),
    /// but different from what the specs says.
    ///
    /// In particular, when encoding for the P256 curve, the public key is represented as serialized
    /// bytes of the following proto:
    /// ```text
    /// message EcP256PublicKey {
    ///     // x and y are encoded in big-endian two's complement (slightly wasteful)
    ///     // Client MUST verify (x,y) is a valid point on NIST P256
    ///     required bytes x = 1;
    ///     required bytes y = 2;
    /// }
    /// ```
    ///
    /// Encoding for X25519 is not supported in this mode.
    PublicKeyInProtobuf,
}

impl WireCompatibilityLayer for HandshakeImplementation {
    fn encode_public_key<C: CryptoProvider>(
        &self,
        key: Vec<u8>,
        cipher: HandshakeCipher,
    ) -> Option<Vec<u8>> {
        match self {
            HandshakeImplementation::Spec => Some(key),
            HandshakeImplementation::PublicKeyInProtobuf => match cipher {
                HandshakeCipher::P256Sha512 => {
                    let p256_key =
                        <C::P256 as P256EcdhProvider>::PublicKey::from_bytes(key.as_slice())
                            .expect("");
                    let (x, y) = p256_key.to_affine_coordinates().unwrap();
                    let bigboi_x = num_bigint::BigInt::from_biguint(
                        num_bigint::Sign::Plus,
                        num_bigint::BigUint::from_bytes_be(x.to_vec().as_slice()),
                    );
                    let bigboi_y = num_bigint::BigInt::from_biguint(
                        num_bigint::Sign::Plus,
                        num_bigint::BigUint::from_bytes_be(y.to_vec().as_slice()),
                    );
                    let proto_key = securemessage::EcP256PublicKey {
                        x: Some(bigboi_x.to_signed_bytes_be()),
                        y: Some(bigboi_y.to_signed_bytes_be()),
                        ..Default::default()
                    };
                    let key = securemessage::GenericPublicKey {
                        type_: Some(securemessage::PublicKeyType::EC_P256.into()),
                        ec_p256_public_key: Some(proto_key).into(),
                        ..Default::default()
                    };
                    key.write_to_bytes().ok()
                }
                HandshakeCipher::Curve25519Sha512 => None,
            },
        }
    }

    fn decode_public_key<C: CryptoProvider>(
        &self,
        key: Vec<u8>,
        cipher: HandshakeCipher,
    ) -> Option<Vec<u8>> {
        match self {
            HandshakeImplementation::Spec => Some(key),
            HandshakeImplementation::PublicKeyInProtobuf => {
                // key will be wrapped in a genericpublickey
                let public_key: GenericPublicKey<C> =
                    securemessage::GenericPublicKey::parse_from_bytes(key.as_slice())
                        .ok()?
                        .into_adapter()
                        .ok()?;
                match public_key {
                    GenericPublicKey::Ec256(key) => {
                        debug_assert_eq!(cipher, HandshakeCipher::P256Sha512);
                        Some(key.to_bytes().to_vec())
                    }
                }
            }
        }
    }
}

pub struct Ukey2ServerStage1<C: CryptoProvider> {
    pub(crate) next_protocols: HashSet<NextProtocol>,
    pub(crate) handshake_impl: HandshakeImplementation,
    _marker: PhantomData<C>,
}

impl<C: CryptoProvider> fmt::Debug for Ukey2ServerStage1<C> {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        write!(f, "Ukey2ServerS1")
    }
}

impl<C: CryptoProvider> Ukey2ServerStage1<C> {
    pub fn from(next_protocols: HashSet<String>, handshake_impl: HandshakeImplementation) -> Self {
        Self {
            next_protocols: next_protocols.iter().filter_map(|p| p.try_into().ok()).collect(),
            handshake_impl,
            _marker: PhantomData,
        }
    }

    pub(crate) fn handle_client_init<R: rand::Rng + rand::CryptoRng>(
        self,
        rng: &mut R,
        client_init: ClientInit,
        client_init_msg_bytes: Vec<u8>,
    ) -> Result<Ukey2ServerStage2<C>, ClientInitError> {
        if client_init.version() != 1 {
            return Err(ClientInitError::BadVersion);
        }

        let next_protocols = client_init.next_protocols();
        let Some(selected_protocol) = next_protocols.intersection(&self.next_protocols).min()
        else {
            return Err(ClientInitError::BadNextProtocol);
        };

        // nothing to check here about client_init.random -- already been validated as 32 bytes

        // all cipher types are supported, so no BAD_HANDSHAKE_CIPHER case
        let commitment = client_init
            .commitments()
            .iter()
            // we want to get the first matching cipher, but max_by_key returns the last max,
            // so iterate in reverse direction
            .rev()
            // proto enum uses the priority as the numeric value
            .max_by_key(|c| c.cipher().as_proto() as i32)
            .ok_or(ClientInitError::BadHandshakeCipher)?;
        match commitment.cipher() {
            // pick in priority order
            HandshakeCipher::Curve25519Sha512 => {
                let secret = ServerKeyPair::Curve25519(
                    <C::X25519 as EcdhProvider<X25519>>::EphemeralSecret::generate_random(&mut
                        <<<C::X25519 as EcdhProvider<X25519>>::EphemeralSecret as EphemeralSecret<
                            X25519,
                        >>::Rng as CryptoRng>::new(),
                    ),
                );
                Ok(Ukey2ServerStage2::from(
                    &mut *rng,
                    client_init_msg_bytes,
                    commitment.clone(),
                    secret,
                    self.handshake_impl,
                    *selected_protocol,
                ))
            }
            HandshakeCipher::P256Sha512 => {
                let secret = ServerKeyPair::P256(
                    <C::P256 as EcdhProvider<P256>>::EphemeralSecret::generate_random(
                        &mut<<<C::P256 as EcdhProvider<P256>>::EphemeralSecret as EphemeralSecret<
                            P256,
                        >>::Rng as CryptoRng>::new(),
                    ),
                );
                Ok(Ukey2ServerStage2::from(
                    &mut *rng,
                    client_init_msg_bytes,
                    commitment.clone(),
                    secret,
                    self.handshake_impl,
                    *selected_protocol,
                ))
            }
        }
    }
}

enum ServerKeyPair<C: CryptoProvider> {
    Curve25519(<C::X25519 as EcdhProvider<X25519>>::EphemeralSecret),
    P256(<C::P256 as EcdhProvider<P256>>::EphemeralSecret),
}

pub struct Ukey2ServerStage2<C: CryptoProvider> {
    client_init_msg: Vec<u8>,
    server_init_msg: Vec<u8>,
    commitment: CipherCommitment,
    key_pair: ServerKeyPair<C>,
    pub(crate) handshake_impl: HandshakeImplementation,
    next_protocol: NextProtocol,
    _marker: PhantomData<C>,
}

impl<C: CryptoProvider> fmt::Debug for Ukey2ServerStage2<C> {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        write!(f, "Ukey2ServerS2")
    }
}

const HKDF_SALT_AUTH: &[u8] = b"UKEY2 v1 auth";
const HKDF_SALT_NEXT: &[u8] = b"UKEY2 v1 next";

impl<C: CryptoProvider> Ukey2ServerStage2<C> {
    fn from<R: rand::Rng + rand::CryptoRng>(
        rng: &mut R,
        client_init_msg: Vec<u8>,
        commitment: CipherCommitment,
        key_pair: ServerKeyPair<C>,
        handshake_impl: HandshakeImplementation,
        next_protocol: NextProtocol,
    ) -> Self {
        let mut random = [0u8; 32]; rng.fill_bytes(&mut random);
        let mut server_init = ukey::Ukey2ServerInit::default();
        server_init.set_version(1);
        server_init.set_random(random.to_vec());
        server_init.set_handshake_cipher(commitment.cipher().as_proto());
        server_init.set_public_key(match &key_pair {
            ServerKeyPair::Curve25519(es) => es.public_key_bytes().as_ref().to_vec(),
            ServerKeyPair::P256(es) => handshake_impl
                .encode_public_key::<C>(
                    es.public_key_bytes().as_ref().to_vec(),
                    HandshakeCipher::P256Sha512,
                )
                .unwrap(),
        });
        server_init.set_selected_next_protocol(next_protocol.to_string());

        Self {
            client_init_msg,
            server_init_msg: server_init.to_wrapped_msg().write_to_bytes().unwrap(),
            commitment,
            key_pair,
            handshake_impl,
            next_protocol,
            _marker: PhantomData,
        }
    }

    pub fn server_init_msg(&self) -> &[u8] {
        &self.server_init_msg
    }

    pub(crate) fn handle_client_finished_msg(
        self,
        msg: ClientFinished,
        client_finished_msg_bytes: &[u8],
    ) -> Result<Ukey2Server, ClientFinishedError> {
        let hash_bytes = C::Sha512::sha512(client_finished_msg_bytes);
        // must be constant time to avoid timing attack on hash equality
        if C::constant_time_eq(hash_bytes.as_slice(), self.commitment.commitment()) {
            // handshake is complete
            // independently derive shared DH key
            let shared_secret_bytes = match self.key_pair {
                ServerKeyPair::Curve25519(es) => {
                    let buf = msg.public_key.into_iter().collect::<Vec<u8>>();
                    let public_key: [u8; 32] =
                        (&buf[..]).try_into().map_err(|_| ClientFinishedError::BadEd25519Key)?;
                    es.diffie_hellman(
                        &<C::X25519 as EcdhProvider<X25519>>::PublicKey::from_bytes(&public_key)
                            .map_err(|_| ClientFinishedError::BadEd25519Key)?,
                    )
                    .map_err(|_| ClientFinishedError::BadKeyExchange)?
                    .into()
                }
                ServerKeyPair::P256(es) => {
                    let other_public_key =
                        &<C::P256 as P256EcdhProvider>::PublicKey::from_sec1_bytes(
                            self.handshake_impl
                                .decode_public_key::<C>(msg.public_key, HandshakeCipher::P256Sha512)
                                .ok_or(ClientFinishedError::BadP256Key)?
                                .as_slice(),
                        )
                        .map_err(|_| ClientFinishedError::BadP256Key)?;
                    es.diffie_hellman(other_public_key)
                        .map_err(|_| ClientFinishedError::BadKeyExchange)?
                        .into()
                }
            };
            let shared_secret_sha256 = C::Sha256::sha256(&shared_secret_bytes).to_vec();
            Ok(Ukey2Server {
                completed_handshake: CompletedHandshake::new(
                    self.client_init_msg,
                    self.server_init_msg,
                    shared_secret_sha256,
                    self.next_protocol,
                ),
            })
        } else {
            Err(ClientFinishedError::UnknownCommitment)
        }
    }
}

/// Representation of the UKEY2 server information after the handshake has been completed. An
/// instance of this can be created by going through the handshake state machine (starting from
/// [`Ukey2ServerStage1`]).
pub struct Ukey2Server {
    completed_handshake: CompletedHandshake,
}

impl fmt::Debug for Ukey2Server {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        write!(f, "Ukey2Server")
    }
}

impl Ukey2Server {
    pub fn completed_handshake(&self) -> &CompletedHandshake {
        &self.completed_handshake
    }

    pub fn into_completed_handshake(self) -> CompletedHandshake {
        self.completed_handshake
    }
}

pub struct Ukey2ClientStage1<C: CryptoProvider> {
    curve25519_secret: <C::X25519 as EcdhProvider<X25519>>::EphemeralSecret,
    p256_secret: <C::P256 as EcdhProvider<P256>>::EphemeralSecret,
    curve25519_client_finished_bytes: Vec<u8>,
    p256_client_finished_bytes: Vec<u8>,
    client_init_bytes: Vec<u8>,
    commitment_ciphers: Vec<HandshakeCipher>,
    handshake_impl: HandshakeImplementation,
    next_protocols: Vec<NextProtocol>,
    _marker: PhantomData<C>,
}

impl<C: CryptoProvider> fmt::Debug for Ukey2ClientStage1<C> {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        write!(f, "Ukey2Client1")
    }
}

impl<C: CryptoProvider> Ukey2ClientStage1<C> {
    // Clippy: we assert that there must be at least one element in `next_protocols`, so indexing
    // [0] is safe.
    #[allow(clippy::indexing_slicing)]
    pub fn from<R: rand::Rng + rand::SeedableRng + rand::CryptoRng>(
        rng: &mut R,
        next_protocols: Vec<NextProtocol>,
        handshake_impl: HandshakeImplementation,
    ) -> Self {
        assert!(!next_protocols.is_empty());
        let mut random = [0u8; 32]; rng.fill_bytes(&mut random); let random = random.to_vec();
        // Curve25519 ClientFinished Message
        let curve25519_secret =
            <C::X25519 as EcdhProvider<X25519>>::EphemeralSecret::generate_random(
                &mut <<<C::X25519 as EcdhProvider<X25519>>::EphemeralSecret as EphemeralSecret<
                    X25519,
                >>::Rng as CryptoRng>::new(),
            );
        let curve25519_client_finished_bytes = {
            let client_finished = ukey::Ukey2ClientFinished {
                public_key: Some(curve25519_secret.public_key_bytes().as_ref().to_vec()),
                ..Default::default()
            };
            client_finished.to_wrapped_msg().write_to_bytes().unwrap()
        };
        let curve25519_client_finished_hash =
            C::Sha512::sha512(&curve25519_client_finished_bytes).to_vec();

        // P256 ClientFinished Message
        let p256_secret = <C::P256 as EcdhProvider<P256>>::EphemeralSecret::generate_random(
            &mut <<<C::P256 as EcdhProvider<P256>>::EphemeralSecret as EphemeralSecret<
                P256,
            >>::Rng as CryptoRng>::new(),
        );
        let p256_client_finished_bytes = {
            let client_finished = ukey::Ukey2ClientFinished {
                public_key: Some(
                    handshake_impl
                        .encode_public_key::<C>(
                            p256_secret.public_key_bytes().as_ref().to_vec(),
                            HandshakeCipher::P256Sha512,
                        )
                        .expect("Output of p256_secret.public_key_bytes should always be valid input for encode_public_key"),
                ),
                ..Default::default()
            };
            client_finished.to_wrapped_msg().write_to_bytes().unwrap()
        };
        let p256_client_finished_hash = C::Sha512::sha512(&p256_client_finished_bytes).to_vec();

        // ClientInit Message
        let client_init_bytes = {
            let curve25519_commitment = ukey::ukey2client_init::CipherCommitment {
                handshake_cipher: Some(HandshakeCipher::Curve25519Sha512.as_proto().into()),
                commitment: Some(curve25519_client_finished_hash),
                ..Default::default()
            };

            let p256_commitment = ukey::ukey2client_init::CipherCommitment {
                handshake_cipher: Some(HandshakeCipher::P256Sha512.as_proto().into()),
                commitment: Some(p256_client_finished_hash),
                ..Default::default()
            };

            let client_init = ukey::Ukey2ClientInit {
                version: Some(1),
                random: Some(random),
                cipher_commitments: vec![curve25519_commitment, p256_commitment],
                next_protocol: Some(next_protocols[0].to_string()),
                next_protocols: next_protocols.iter().map(|x| x.to_string()).collect(),
                ..Default::default()
            };
            client_init.to_wrapped_msg().write_to_bytes().unwrap()
        };

        Self {
            curve25519_secret,
            p256_secret,
            curve25519_client_finished_bytes,
            p256_client_finished_bytes,
            client_init_bytes,
            commitment_ciphers: vec![
                HandshakeCipher::Curve25519Sha512,
                HandshakeCipher::P256Sha512,
            ],
            handshake_impl,
            next_protocols,
            _marker: PhantomData,
        }
    }

    pub fn client_init_msg(&self) -> &[u8] {
        &self.client_init_bytes
    }

    pub(crate) fn handle_server_init(
        self,
        server_init: ServerInit,
        server_init_bytes: Vec<u8>,
    ) -> Result<Ukey2Client, ServerInitError> {
        if server_init.version() != 1 {
            return Err(ServerInitError::BadVersion);
        }

        if !self.next_protocols.contains(&server_init.selected_next_protocol()) {
            return Err(ServerInitError::BadNextProtocol);
        }
        let next_protocol = server_init.selected_next_protocol();

        // loop over all commitments every time for a semblance of constant time-ness
        let server_cipher = self
            .commitment_ciphers
            .iter()
            .fold(None, |accum, c| {
                if server_init.handshake_cipher() == *c {
                    match accum {
                        None => Some(c),
                        Some(_) => accum,
                    }
                } else {
                    accum
                }
            })
            .ok_or(ServerInitError::BadHandshakeCipher)?;
        let (server_shared_secret, client_finished_bytes) = match server_cipher {
            HandshakeCipher::P256Sha512 => {
                let other_public_key = &<C::P256 as P256EcdhProvider>::PublicKey::from_sec1_bytes(
                    self.handshake_impl
                        .decode_public_key::<C>(
                            server_init.public_key.to_vec(),
                            HandshakeCipher::P256Sha512,
                        )
                        .ok_or(ServerInitError::BadPublicKey)?
                        .as_slice(),
                )
                .map_err(|_| ServerInitError::BadPublicKey)?;
                let shared_secret = self
                    .p256_secret
                    .diffie_hellman(other_public_key)
                    .map_err(|_| ServerInitError::BadKeyExchange)?;
                let shared_secret_bytes: [u8; 32] = shared_secret.into();
                (shared_secret_bytes, self.p256_client_finished_bytes)
            }
            HandshakeCipher::Curve25519Sha512 => {
                let pub_key: [u8; 32] =
                    server_init.public_key.try_into().map_err(|_| ServerInitError::BadPublicKey)?;
                (
                    self.curve25519_secret
                        .diffie_hellman(
                            &<C::X25519 as EcdhProvider<X25519>>::PublicKey::from_bytes(&pub_key)
                                .map_err(|_| ServerInitError::BadPublicKey)?,
                        )
                        .map_err(|_| ServerInitError::BadKeyExchange)?
                        .into(),
                    self.curve25519_client_finished_bytes,
                )
            }
        };
        let shared_secret_bytes = C::Sha256::sha256(&server_shared_secret).to_vec();
        Ok(Ukey2Client {
            client_finished_bytes,
            completed_handshake: CompletedHandshake::new(
                self.client_init_bytes,
                server_init_bytes.to_vec(),
                shared_secret_bytes,
                next_protocol,
            ),
        })
    }
}

#[derive(Debug)]
#[allow(clippy::enum_variant_names)]
pub(crate) enum ServerInitError {
    BadVersion,
    BadHandshakeCipher,
    BadPublicKey,
    /// The diffie-hellman key exchange failed to generate a shared secret
    BadKeyExchange,
    /// The server sent an invalid next protocol that is not available to the client.
    BadNextProtocol,
}

#[derive(Clone)]
pub struct Ukey2Client {
    completed_handshake: CompletedHandshake,
    client_finished_bytes: Vec<u8>,
}

impl fmt::Debug for Ukey2Client {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        write!(f, "Ukey2Client")
    }
}

impl Ukey2Client {
    pub fn client_finished_msg(&self) -> &[u8] {
        &self.client_finished_bytes
    }

    pub fn completed_handshake(&self) -> &CompletedHandshake {
        &self.completed_handshake
    }

    pub fn into_completed_handshake(self) -> CompletedHandshake {
        self.completed_handshake
    }
}

#[allow(clippy::enum_variant_names)]
pub enum ClientInitError {
    BadVersion,
    BadHandshakeCipher,
    BadNextProtocol,
}

pub enum ClientFinishedError {
    BadEd25519Key,
    BadP256Key,
    UnknownCommitment,
    /// The diffie-hellman key exchange failed to generate a shared secret
    BadKeyExchange,
}

/// The result of completing the UKEY2 handshake.
#[derive(Clone)]
pub struct CompletedHandshake {
    client_init_bytes: Vec<u8>,
    server_init_bytes: Vec<u8>,
    shared_secret: Vec<u8>,
    pub next_protocol: NextProtocol,
}

impl CompletedHandshake {
    fn new(
        client_init_bytes: Vec<u8>,
        server_init_bytes: Vec<u8>,
        shared_secret: Vec<u8>,
        next_protocol: NextProtocol,
    ) -> Self {
        Self { client_init_bytes, server_init_bytes, shared_secret, next_protocol }
    }

    /// Returns an HKDF for the UKEY2 `AUTH_STRING`.
    pub fn auth_string<C: CryptoProvider>(&self) -> HandshakeHkdf<'_, C> {
        HandshakeHkdf::new(
            &self.client_init_bytes,
            &self.server_init_bytes,
            C::HkdfSha256::new(Some(HKDF_SALT_AUTH), &self.shared_secret),
        )
    }

    /// Returns an HKDF for the UKEY2 `NEXT_SECRET`.
    pub fn next_protocol_secret<C: CryptoProvider>(&self) -> HandshakeHkdf<'_, C> {
        HandshakeHkdf::new(
            &self.client_init_bytes,
            &self.server_init_bytes,
            C::HkdfSha256::new(Some(HKDF_SALT_NEXT), &self.shared_secret),
        )
    }
}

/// A UKEY2 handshake secret that can derive output at the caller's preferred length.
pub struct HandshakeHkdf<'a, C: CryptoProvider> {
    client_init_bytes: &'a [u8],
    server_init_bytes: &'a [u8],
    hkdf: C::HkdfSha256,
}

impl<'a, C: CryptoProvider> HandshakeHkdf<'a, C> {
    /// Returns `None` if the requested size > 255 * 512 bytes.
    pub fn derive_array<const N: usize>(&self) -> Option<[u8; N]> {
        let mut buf = [0; N];
        self.derive_slice(&mut buf).map(|_| buf)
    }

    /// Returns `None` if the requested `length` > 255 * 512 bytes.
    pub fn derive_vec(&self, length: usize) -> Option<Vec<u8>> {
        let mut buf = vec![0; length];
        self.derive_slice(&mut buf).map(|_| buf)
    }

    /// Returns `None` if the provided `buf` has size > 255 * 512 bytes.
    pub fn derive_slice(&self, buf: &mut [u8]) -> Option<()> {
        self.hkdf.expand_multi_info(&[self.client_init_bytes, self.server_init_bytes], buf).ok()
    }

    fn new(client_init_bytes: &'a [u8], server_init_bytes: &'a [u8], hkdf: C::HkdfSha256) -> Self {
        Self { client_init_bytes, server_init_bytes, hkdf }
    }
}
