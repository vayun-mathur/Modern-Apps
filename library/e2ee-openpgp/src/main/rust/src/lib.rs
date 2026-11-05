//! OpenPGP (RFC 9580) for SecureMail, via rPGP.
//!
//! Suite: Ed25519 signing primary + X25519 encryption subkey, AES-256 session
//! keys, SHA-512 digests. Keys cross the JNI boundary as ASCII armor; ciphertext
//! crosses as raw PGP/MIME bytes (armored only for SMTP federation, which the
//! app does with [`pgp::to_armored_message`]).
//!
//! The relay server never sees plaintext or private keys — it routes opaque
//! blobs by recipient address, exactly like the FindFamily relay routes opaque
//! ciphertext by userid.
//!
//! Lives in its own `e2ee_pgp` cdylib (see :library:e2ee-openpgp) so apps that
//! don't do mail never link the rPGP cipher suite (#873).

pub mod pgp;
mod pgp_jni;

#[cfg(test)]
mod pgp_tests;
