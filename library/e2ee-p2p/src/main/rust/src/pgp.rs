//! OpenPGP (RFC 9580) for SecureMail, via rPGP.
//!
//! Suite: Ed25519 signing primary + X25519 encryption subkey (the same suite
//! the app previously generated with BouncyCastle), AES-256 session keys,
//! SHA-512 digests. Keys cross the JNI boundary as ASCII armor; ciphertext
//! crosses as raw PGP/MIME bytes (armored only for SMTP federation, which the
//! app does with [`to_armored_message`]).
//!
//! The relay server never sees plaintext or private keys — it routes opaque
//! blobs by recipient address, exactly like the FindFamily relay routes opaque
//! ciphertext by userid.

use pgp::armor::Dearmor;
use pgp::composed::{
    ArmorOptions, Deserializable, EncryptionCaps, KeyType, Message, MessageBuilder,
    SecretKeyParamsBuilder, SignedPublicKey, SignedSecretKey, SubkeyParamsBuilder,
};
use pgp::crypto::hash::HashAlgorithm;
use pgp::crypto::sym::SymmetricKeyAlgorithm;
use pgp::types::{KeyDetails, Password};
use rand08::rngs::OsRng;

/// Generate an Ed25519 primary + X25519 encryption subkey for `user_id`.
/// Returns (armoredSecret, armoredPublic, fingerprintHex).
pub fn pgp_generate(user_id: &str, passphrase: &str) -> Option<(String, String, String)> {
    let mut rng = OsRng;
    let mut params = SecretKeyParamsBuilder::default();
    params
        .key_type(KeyType::Ed25519)
        .can_certify(false)
        .can_sign(true)
        .primary_user_id(user_id.to_string())
        .passphrase(Some(passphrase.to_string()))
        .preferred_symmetric_algorithms(smallvec::smallvec![
            SymmetricKeyAlgorithm::AES256,
        ])
        .preferred_hash_algorithms(smallvec::smallvec![HashAlgorithm::Sha512])
        .preferred_compression_algorithms(smallvec::smallvec![])
        .subkeys(vec![SubkeyParamsBuilder::default()
            .key_type(KeyType::X25519)
            .can_encrypt(EncryptionCaps::All)
            .passphrase(Some(passphrase.to_string()))
            .build()
            .ok()?]);
    let secret = params.build().ok()?.generate(&mut rng).ok()?;
    let public = secret.to_public_key();
    let fingerprint = hex_upper(&secret.fingerprint().as_bytes());
    Some((
        secret.to_armored_string(ArmorOptions::default()).ok()?,
        public.to_armored_string(ArmorOptions::default()).ok()?,
        fingerprint,
    ))
}

/// Fingerprint (uppercase hex, no spaces) of an armored public key.
pub fn pgp_fingerprint(armored_public: &str) -> Option<String> {
    let (key, _) = SignedPublicKey::from_string(armored_public).ok()?;
    Some(hex_upper(&key.fingerprint().as_bytes()))
}

/// Normalize a public key (binary WKD record or armor) to canonical armor.
/// Returns None when the input is not a parseable OpenPGP public key.
pub fn pgp_normalize_key(input: &[u8]) -> Option<String> {
    use pgp::composed::Deserializable as _;
    let key = if is_probably_armor(input) {
        let text = std::str::from_utf8(input).ok()?;
        let (key, _) = SignedPublicKey::from_string(text).ok()?;
        key
    } else {
        SignedPublicKey::from_bytes(input).ok()?
    };
    key.to_armored_string(ArmorOptions::default()).ok()
}

fn is_probably_armor(input: &[u8]) -> bool {
    input.starts_with(b"-----BEGIN PGP")
}

/// Encrypt `plaintext` to one recipient's armored public key. Returns raw
/// (unarmored) PGP bytes for the relay wire.
pub fn pgp_encrypt(armored_public: &str, plaintext: &[u8]) -> Option<Vec<u8>> {
    pgp_encrypt_signed(armored_public, None, plaintext)
}

/// Encrypt + sign: as [`pgp_encrypt`], additionally signing with the sender's
/// secret key (`(armored_secret, passphrase)`). Returns raw PGP bytes.
pub fn pgp_encrypt_signed(
    armored_public: &str,
    signer: Option<(&str, &str)>,
    plaintext: &[u8],
) -> Option<Vec<u8>> {
    let mut rng = OsRng;
    let (public, _) = SignedPublicKey::from_string(armored_public).ok()?;
    // Encrypt to the first subkey (our keys always carry the X25519
    // encryption subkey; a foreign key without one cannot be encrypted to).
    // Matches the documented `encrypt_to_key(&rng, &public_subkeys[0])` shape.
    let subkey = public.public_subkeys.first()?;
    let mut builder = MessageBuilder::from_bytes("message.txt", plaintext.to_vec())
        .seipd_v1(&mut rng, SymmetricKeyAlgorithm::AES256);
    // Parse the signer first: builder.sign() borrows the secret key for the
    // builder's lifetime, so it must outlive the if-let scope.
    let signer_key: Option<SignedSecretKey> = match signer {
        Some((armored_secret, _)) => {
            Some(SignedSecretKey::from_string(armored_secret).ok()?.0)
        }
        None => None,
    };
    if let (Some(key), Some((_, passphrase))) = (&signer_key, signer) {
        // packet::SecretKey implements SigningKey; the primary is Ed25519.
        builder.sign(
            &key.primary_key,
            Password::from(passphrase),
            HashAlgorithm::Sha512,
        );
    }
    builder.encrypt_to_key(&mut rng, subkey).ok()?;
    builder.to_vec(&mut rng).ok()
}

/// Decrypt raw PGP bytes with an armored secret key + passphrase. Returns the
/// literal payload. Signature verification is a separate pass (see below).
pub fn pgp_decrypt(
    armored_secret: &str,
    passphrase: &str,
    ciphertext: &[u8],
) -> Option<Vec<u8>> {
    pgp_decrypt_verify(armored_secret, passphrase, ciphertext, None).map(|(p, _)| p)
}

/// Decrypt + verify: as [`pgp_decrypt`], additionally verifying an embedded
/// signature against `verifier` (armored sender public key). Returns
/// (payload, signer_fingerprint_hex_or_empty). Unsigned messages verify
/// cleanly with an empty fingerprint (accepted, not rejected); tampered
/// signatures fail the whole call (None).
pub fn pgp_decrypt_verify(
    armored_secret: &str,
    passphrase: &str,
    ciphertext: &[u8],
    verifier: Option<&str>,
) -> Option<(Vec<u8>, String)> {
    let (secret, _) = SignedSecretKey::from_string(armored_secret).ok()?;
    let msg = Message::from_bytes(ciphertext).ok()?;
    let mut decrypted = msg
        .decrypt(&Password::from(passphrase), &secret)
        .ok()?;
    // Signedness is structural: an unsigned message decrypts to Literal and
    // must NOT attempt verify() (it bails "message was not signed"). Only
    // Signed messages go through verification; a failed check there means
    // tamper and fails the whole call.
    let is_signed = matches!(decrypted, Message::Signed { .. });
    // Drain the literal first: verify() requires the message read to the end.
    let payload = decrypted.as_data_vec().ok()?;
    if !is_signed {
        return Some((payload, String::new()));
    }
    let fingerprint = match verifier {
        Some(armored_public) => {
            let (public, _) = SignedPublicKey::from_string(armored_public).ok()?;
            // Try the primary first, then each subkey (sender may sign with
            // either; ours always signs with the Ed25519 primary).
            let mut candidates: Vec<&dyn pgp::types::VerifyingKey> = vec![&public.primary_key];
            for sub in &public.public_subkeys {
                candidates.push(&sub.key);
            }
            let mut matched: Option<String> = None;
            for key in candidates {
                if decrypted.verify(key).is_ok() {
                    matched = Some(hex_upper(&key.fingerprint().as_bytes()));
                    break;
                }
            }
            matched?
        }
        // No verifier key supplied: is the message signed at all? A signed
        // message without a key to check against stays unverified (empty),
        // which callers render as "signature not verified" — accepted.
        None => String::new(),
    };
    Some((payload, fingerprint))
}

/// Binary detached signature over `data` with the Ed25519 primary (SHA-512).
/// Returns the raw signature packet (used for relay SUB authentication).
pub fn pgp_sign_detached(armored_secret: &str, passphrase: &str, data: &[u8]) -> Option<Vec<u8>> {
    use pgp::composed::DetachedSignature;
    use pgp::ser::Serialize;
    let (secret, _) = SignedSecretKey::from_string(armored_secret).ok()?;
    DetachedSignature::sign_binary_data(
        OsRng,
        &secret.primary_key,
        &Password::from(passphrase),
        HashAlgorithm::Sha512,
        data,
    )
    .ok()?
    .to_bytes()
    .ok()
}

/// Armor raw PGP bytes as a `PGP MESSAGE` block (for SMTP federation).
pub fn to_armored_message(raw: &[u8]) -> Option<String> {
    use base64::Engine;
    // RFC 9580 §6 armor framing: header, base64 body at 64-col, CRC24 footer.
    let mut out = String::from("-----BEGIN PGP MESSAGE-----\n\n");
    let b64 = base64::engine::general_purpose::STANDARD.encode(raw);
    for chunk in b64.as_bytes().chunks(64) {
        out.push_str(std::str::from_utf8(chunk).ok()?);
        out.push('\n');
    }
    out.push('=');
    out.push_str(&base64::engine::general_purpose::STANDARD.encode(crc24(raw)));
    out.push_str("\n-----END PGP MESSAGE-----\n");
    Some(out)
}

/// OpenPGP CRC24 (RFC 9580 §6.1): polynomial 0x1864CFB, init 0xB704CE.
fn crc24(data: &[u8]) -> [u8; 3] {
    let mut crc: u32 = 0xB704CE;
    for &b in data {
        crc ^= (b as u32) << 16;
        for _ in 0..8 {
            crc <<= 1;
            if crc & 0x1000000 != 0 {
                crc ^= 0x1864CFB;
            }
        }
    }
    [(crc >> 16) as u8, (crc >> 8) as u8, crc as u8]
}

/// Dearmor any ASCII-armored input to raw bytes (key or message).
pub fn dearmor_any(armored: &str) -> Option<Vec<u8>> {
    use std::io::Read;
    let mut dearmor = Dearmor::new(armored.as_bytes());
    let mut bytes = Vec::new();
    dearmor.read_to_end(&mut bytes).ok()?;
    Some(bytes)
}

fn hex_upper(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push(HEX[(b >> 4) as usize] as char);
        out.push(HEX[(b & 0x0f) as usize] as char);
    }
    out
}
