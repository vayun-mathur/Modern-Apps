//! Raw ed25519 verification for `:appstore`, replacing Bouncy Castle.
//!
//! The raw check ([`verify`]) receives an already-extracted 32-byte public
//! key, the exact signed bytes, and a 64-byte signature. The pure signify
//! framing helpers in this file mirror the parsing the Kotlin
//! `Signify.verify` does on Android (see `data/security/Signify.kt`): a
//! public key is a base64 blob of `2-byte algo id || 8-byte key id ||
//! 32-byte ed25519 public key`, a signature is the same framing around a
//! 64-byte signature, and a `.sig` file precedes its blob with an
//! `untrusted comment:` line. They exist so that framing logic is testable
//! without a JVM; the Kotlin caller keeps its own copy.
//!
//! Everything fails closed by returning `false`/`None`: this is the whole
//! trust anchor for the Accrescent app source, and a panic across the JNI
//! boundary would abort the process rather than reject the signature.

use base64::engine::general_purpose::STANDARD;
use base64::Engine as _;
use ed25519_dalek::{Signature, VerifyingKey};

/// Length of a raw ed25519 public key.
const PUBKEY_LEN: usize = 32;

/// Length of a raw ed25519 signature.
const SIG_LEN: usize = 64;

/// Length of the signify algorithm id at the start of every blob.
pub const ALGO_LEN: usize = 2;

/// Length of the signify key number that follows the algorithm id.
pub const KEY_NUM_LEN: usize = 8;

/// Length of the signify blob header: algorithm id plus key number.
pub const HEADER_LEN: usize = ALGO_LEN + KEY_NUM_LEN;

/// Decoded length of a signify public-key blob: header plus public key.
pub const PUBKEY_BLOB_LEN: usize = HEADER_LEN + PUBKEY_LEN;

/// Decoded length of a signify signature blob: header plus signature.
pub const SIG_BLOB_LEN: usize = HEADER_LEN + SIG_LEN;

/// The comment prefix that precedes the blob in a signify `.sig` file.
pub const UNTRUSTED_COMMENT: &str = "untrusted comment:";

/// True only if `sig` is a valid ed25519 signature over `msg` by `pubkey`.
///
/// Returns `false` — never panics — for a wrong-length key or signature, a
/// public key that is not a canonically-encoded curve point, or a bad
/// signature.
///
/// Uses `verify_strict`, which additionally rejects small-order public keys and
/// non-canonically-encoded `R`/`A` values. Bouncy Castle's `Ed25519Signer`
/// accepted those, so this is strictly narrower than what it replaced: a real
/// signify key cannot be small-order, and for a trust anchor the stricter check
/// is the safer direction to differ in.
pub fn verify(pubkey: &[u8], msg: &[u8], sig: &[u8]) -> bool {
    let pubkey: [u8; PUBKEY_LEN] = match pubkey.try_into() {
        Ok(k) => k,
        Err(_) => return false,
    };
    let sig: [u8; SIG_LEN] = match sig.try_into() {
        Ok(s) => s,
        Err(_) => return false,
    };
    let key = match VerifyingKey::from_bytes(&pubkey) {
        Ok(k) => k,
        Err(_) => return false,
    };
    key.verify_strict(msg, &Signature::from_bytes(&sig)).is_ok()
}

/// Copy `N` bytes out of `blob` starting at `offset`, or `None` if the blob
/// does not reach that far.
fn field<const N: usize>(blob: &[u8], offset: usize) -> Option<[u8; N]> {
    let mut out = [0u8; N];
    out.copy_from_slice(blob.get(offset..offset.checked_add(N)?)?);
    Some(out)
}

/// The 2-byte algorithm id of a signify blob, or `None` if `blob` is shorter
/// than the header. Purely descriptive: like the Kotlin parser, the
/// verification path never compares algorithm ids.
pub fn algo_id(blob: &[u8]) -> Option<[u8; ALGO_LEN]> {
    field(blob, 0)
}

/// The 8-byte key number of a signify blob, or `None` if `blob` is shorter
/// than the header. Purely descriptive: like the Kotlin parser, the
/// verification path never compares key numbers between key and signature.
pub fn key_num(blob: &[u8]) -> Option<[u8; KEY_NUM_LEN]> {
    field(blob, ALGO_LEN)
}

/// Extract the raw 32-byte public key from a decoded signify public-key
/// blob, or `None` if the blob is shorter than [`PUBKEY_BLOB_LEN`]. Bytes
/// past the key are ignored, mirroring the Kotlin `copyOfRange(10, 42)`.
pub fn extract_pubkey_key(blob: &[u8]) -> Option<[u8; PUBKEY_LEN]> {
    field(blob, HEADER_LEN)
}

/// Extract the raw 64-byte signature from a decoded signify signature blob,
/// or `None` if the blob is shorter than [`SIG_BLOB_LEN`]. Bytes past the
/// signature are ignored, mirroring the Kotlin `copyOfRange(10, 74)`.
pub fn extract_signature(blob: &[u8]) -> Option<[u8; SIG_LEN]> {
    field(blob, HEADER_LEN)
}

/// Decode base64 the way Android's `Base64.decode(..., Base64.DEFAULT)`
/// does for the Kotlin parser: ASCII whitespace anywhere in the input is
/// ignored, and the trailing padding may be present or missing. Returns
/// `None` for any other malformed input.
fn decode_base64_flexible(value: &str) -> Option<Vec<u8>> {
    let cleaned: String = value.chars().filter(|c| !c.is_ascii_whitespace()).collect();
    let core = cleaned.trim_end_matches('=');
    if core.is_empty() {
        return if cleaned.is_empty() { Some(Vec::new()) } else { None };
    }
    let mut padded = String::from(core);
    while padded.len() % 4 != 0 {
        padded.push('=');
    }
    STANDARD.decode(padded).ok()
}

/// Decode a base64 signify public-key blob and extract the raw public key,
/// or `None` if the input is not valid base64 or decodes to fewer than
/// [`PUBKEY_BLOB_LEN`] bytes.
pub fn decode_pubkey(base64: &str) -> Option<[u8; PUBKEY_LEN]> {
    extract_pubkey_key(&decode_base64_flexible(base64)?)
}

/// Decode a base64 signify signature blob and extract the raw signature,
/// or `None` if the input is not valid base64 or decodes to fewer than
/// [`SIG_BLOB_LEN`] bytes.
pub fn decode_signature(base64: &str) -> Option<[u8; SIG_LEN]> {
    extract_signature(&decode_base64_flexible(base64)?)
}

/// Extract the raw signature from the text of a signify `.sig` file.
///
/// Mirrors the Kotlin parser: every line is trimmed, and the last non-blank
/// line that does not start with [`UNTRUSTED_COMMENT`] is taken as the
/// base64 signature blob. Returns `None` when no such line exists or the
/// blob does not decode to a full signature.
pub fn sig_file_signature(text: &str) -> Option<[u8; SIG_LEN]> {
    let blob = text
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with(UNTRUSTED_COMMENT))
        .last()?;
    decode_signature(blob)
}

/// Verify `msg` against decoded signify key and signature blobs.
///
/// Both blobs are reduced with [`extract_pubkey_key`] and
/// [`extract_signature`]; as in the Kotlin parser, the algorithm id and key
/// number framing is sliced off but never compared, so a signature made by
/// the key verifies whatever framing bytes the two blobs carry.
pub fn verify_signify_blobs(pubkey_blob: &[u8], sig_blob: &[u8], msg: &[u8]) -> bool {
    let Some(pubkey) = extract_pubkey_key(pubkey_blob) else {
        return false;
    };
    let Some(sig) = extract_signature(sig_blob) else {
        return false;
    };
    verify(&pubkey, msg, &sig)
}

/// Verify `msg` exactly as the Kotlin `Signify.verify` does: `pubkey_b64`
/// is a bare base64 public-key blob, and `signature` is either a bare
/// base64 signature blob or the text of a `.sig` file.
pub fn verify_signify(pubkey_b64: &str, signature: &str, msg: &[u8]) -> bool {
    let (Some(pubkey), Some(sig)) = (decode_pubkey(pubkey_b64), sig_file_signature(signature))
    else {
        return false;
    };
    verify(&pubkey, msg, &sig)
}

/// The testable core of the JNI bridge: `false` unless all three inputs
/// are present and [`verify`] accepts them.
pub fn verify_inputs(
    pubkey: Option<Vec<u8>>,
    msg: Option<Vec<u8>>,
    sig: Option<Vec<u8>>,
) -> bool {
    match (pubkey, msg, sig) {
        (Some(pubkey), Some(msg), Some(sig)) => verify(&pubkey, &msg, &sig),
        _ => false,
    }
}

mod jni_bridge;

#[cfg(test)]
mod signify_tests;

#[cfg(test)]
mod tests;
