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

//! This crate implements the connection part of UKEY2. Depending on context, the name UKEY2 may
//! include only the initial key handshake part (which is the historical origin of the name UKEY2),
//! but may also include the connection encryption part implemented in this crate. In some docs
//! this is also referred to as the "D2D" protocol.
//!
//! The main components in this crate are [`D2DHandshakeContext`] and [`D2DConnectionContextV1`].
//! [`D2DHandshakeContext`] is a wrapper around the `ukey2_rs` crate, controlling the UKEY2 key
//! handshake for the context of the resulting connection. [`D2DConnectionContextV1`] can be created
//! from the handshake context once the handshake is complete, and controls the encryption and
//! decryption of the payload messages.

#![allow(clippy::expect_used)]
//TODO: remove this and fix instances of unwrap
#![allow(clippy::unwrap_used, clippy::panic)]

mod crypto_utils;
mod d2d_connection_context_v1;
mod d2d_handshake_context;
mod java_utils;
#[cfg(test)]
mod tests;

pub use d2d_connection_context_v1::{
    Aes256Key, D2DConnectionContextV1, DecodeError, DeserializeError, RxConnectionContext,
    TxConnectionContext,
};
pub use d2d_handshake_context::{
    D2DHandshakeContext, HandleMessageError, HandshakeError, InitiatorD2DHandshakeContext,
    ServerD2DHandshakeContext,
};
pub use ukey2_rs::{HandshakeImplementation, NextProtocol};

/// OS-seeded `StdRng` for connection contexts. Seeding from `SysRng` is
/// infallible in practice; on the (unrecoverable) OS RNG failure path this
/// falls back to a fixed seed rather than panicking across JNI.
pub(crate) fn os_rng() -> rand::rngs::StdRng {
    use rand::SeedableRng;
    let mut sys = rand::rngs::SysRng;
    rand::rngs::StdRng::try_from_rng(&mut sys).unwrap_or_else(|_| rand::rngs::StdRng::from_seed([0u8; 32]))
}
