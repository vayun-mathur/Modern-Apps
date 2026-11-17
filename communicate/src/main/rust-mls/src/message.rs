//! Framing helpers shared by the JNI layer.
//!
//! The RCS transport carries two framed payload kinds (see [`crate::group`]):
//! [`crate::group::WELCOME_MAGIC`] envelopes (Welcome + ratchet tree) and
//! [`crate::group::MESSAGE_MAGIC`] payloads (TLS `MlsMessageOut`). This module
//! only hosts the kind probe so Kotlin can route inbound bytes before calling
//! into [`crate::group`].

use crate::group::{MESSAGE_MAGIC, WELCOME_MAGIC};

/// Kind of an inbound framed payload.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PayloadKind {
    /// `WELCOME_MAGIC` envelope → join flow.
    Welcome,
    /// `MESSAGE_MAGIC` payload → decrypt flow.
    Message,
    /// Unrecognized framing.
    Unknown,
}

/// Probe the framing magic of [bytes].
pub fn payload_kind(bytes: &[u8]) -> PayloadKind {
    if bytes.get(..8) == Some(WELCOME_MAGIC.as_slice()) {
        PayloadKind::Welcome
    } else if bytes.get(..8) == Some(MESSAGE_MAGIC.as_slice()) {
        PayloadKind::Message
    } else {
        PayloadKind::Unknown
    }
}
