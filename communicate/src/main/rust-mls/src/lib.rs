//! MLS (RFC 9420, GSMA Universal Profile 3.0) for the RCS line, backed by OpenMLS.
//!
//! Design mirrors the Signal crate in `../rust`: every entry point is
//! **pure**. Kotlin owns persistence (Room) and passes group state, key
//! packages and proposals in as opaque TLS-serialized bytes, getting updated
//! state back. The crate never calls back into Kotlin and holds no globals.
//!
//! The crypto is interop-shaped (standard ciphersuite, BasicCredential
//! identities, TLS framing — the same UP 3.0 MLS profile Google Messages
//! uses). Key discovery stays closed-loop in v1: key packages ride our own
//! RCS content-types rather than a federated directory.

pub mod group;
pub mod keys;
pub mod message;
pub mod store;

#[cfg(target_os = "android")]
#[allow(unsafe_code)]
mod jni_bridge;

#[cfg(test)]
mod tests;
