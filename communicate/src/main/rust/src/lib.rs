//! Classic Signal protocol v3 for the WhatsApp bridge.
//!
//! WhatsApp companion sessions speak Signal protocol v3 (X3DH + Double Ratchet +
//! Sender Keys). `org.signal:libsignal-android` dropped non-PQ X3DH, which is why this
//! bridge previously depended on the pure-Java `org.whispersystems:signal-protocol-java`
//! shipped through a Shadow-relocated module. This crate replaces it.
//!
//! Design: every entry point is **pure**. Kotlin owns persistence (Room) and passes
//! session/sender-key records in as opaque bytes, getting updated records back. That
//! avoids JNI callbacks into Kotlin stores entirely, and keeps all crypto state
//! transitions in one auditable place.
//!
//! Wire formats are byte-exact with signal-protocol-java (constants transcribed from its
//! bytecode — see `wire.rs`). The *persisted record* format is our own, which is safe
//! because the migration accepts a one-time WhatsApp re-link.

pub mod wa_binary;
pub mod wa_tokens;
pub mod crypto;
pub mod group;
pub mod session;
pub mod wire;
pub mod signal;

#[cfg(target_os = "android")]
mod jni_bridge;

#[cfg(test)]
mod tests;

/// The thread-local CSPRNG, seeded from the OS. `ThreadRng` satisfies the
/// infallible `Rng + CryptoRng` bounds on the crypto fns; `SysRng` cannot,
/// since it is a fallible `TryRng` in rand_core 0.10.
pub use rand::rngs::ThreadRng as OsRng;
