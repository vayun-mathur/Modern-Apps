//! Tests for the pure signify framing helpers in `lib.rs`.
//!
//! The framing semantics asserted here are the Kotlin `Signify.verify`
//! semantics (`data/security/Signify.kt`), which these helpers mirror:
//! blobs are sliced at `[10, 42)` / `[10, 74)` once they are long enough,
//! bytes past the slice are ignored, and the 2-byte algorithm id and
//! 8-byte key number are *never compared* — not against an expected value
//! and not between the public key and the signature. Tests below pin that
//! down rather than assume signify-style header matching.

use super::*;
use base64::engine::general_purpose::STANDARD;
use base64::Engine as _;
use ed25519_dalek::{Signer, SigningKey};

/// The algorithm id real signify writes for ed25519.
const ALGO: [u8; ALGO_LEN] = *b"Ed";

/// A key number used by the fixtures; distinct values prove (non-)matching.
const KEY_NUM: [u8; KEY_NUM_LEN] = *b"12345678";

/// The exact bytes signed by the checked-in test vector.
const REPODATA: &[u8] = include_bytes!("../testvectors/repodata.1.json");

/// The full checked-in `.sig` file: comment line plus base64 blob.
const SIG_FILE: &str = include_str!("../testvectors/repodata.1.json.sig");

/// The checked-in Accrescent public key blob, base64.
const PUBKEY_B64: &str = "RWT8aZ/NdUmXCPqQ0we7UyCe34q1xRfncBFVK5dI3ok9BkL1bFF3mgh3";

/// A freshly generated key pair, deterministic from `seed` so failures
/// reproduce byte-for-byte.
fn fresh_key(seed: u8) -> (SigningKey, [u8; PUBKEY_LEN]) {
    let signing = SigningKey::from_bytes(&[seed; PUBKEY_LEN]);
    let verifying = signing.verifying_key().to_bytes();
    (signing, verifying)
}

/// Frame raw key/signature material as a signify blob.
fn blob(algo: [u8; ALGO_LEN], key_num: [u8; KEY_NUM_LEN], material: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(HEADER_LEN + material.len());
    out.extend_from_slice(&algo);
    out.extend_from_slice(&key_num);
    out.extend_from_slice(material);
    out
}

/// A complete, valid fixture: base64 public key, `.sig` file text, message.
fn fixture(seed: u8, msg: &[u8]) -> (String, String, Vec<u8>) {
    let (signing, verifying) = fresh_key(seed);
    let signature = signing.sign(msg).to_bytes();
    let pubkey_b64 = STANDARD.encode(blob(ALGO, KEY_NUM, &verifying));
    let sig_b64 = STANDARD.encode(blob(ALGO, KEY_NUM, &signature));
    let sig_file = format!("untrusted comment: verify with fixture.pub\n{sig_b64}\n");
    (pubkey_b64, sig_file, msg.to_vec())
}

#[test]
fn round_trip_fresh_key_verifies_through_every_api() {
    let msg = b"round-trip message";
    let (pubkey_b64, sig_file, msg) = fixture(7, msg);
    let (signing, verifying) = fresh_key(7);
    let signature = signing.sign(&msg).to_bytes();
    let pubkey_blob = STANDARD.decode(&pubkey_b64).unwrap();
    let sig_blob = blob(ALGO, KEY_NUM, &signature);

    assert!(verify_signify(&pubkey_b64, &sig_file, &msg));
    assert!(verify_signify(&pubkey_b64, &STANDARD.encode(&sig_blob), &msg));
    assert!(verify_signify_blobs(&pubkey_blob, &sig_blob, &msg));
    assert!(verify(&verifying, &msg, &signature));
    assert!(verify_inputs(
        Some(verifying.to_vec()),
        Some(msg.clone()),
        Some(signature.to_vec()),
    ));
    assert_eq!(decode_pubkey(&pubkey_b64), Some(verifying));
    assert_eq!(sig_file_signature(&sig_file), Some(signature));
    assert_eq!(extract_pubkey_key(&pubkey_blob), Some(verifying));
    assert_eq!(extract_signature(&sig_blob), Some(signature));
}

#[test]
fn real_vectors_verify_through_the_new_api() {
    assert!(verify_signify(PUBKEY_B64, SIG_FILE, REPODATA));
    let pubkey_blob = STANDARD.decode(PUBKEY_B64).unwrap();
    assert_eq!(decode_pubkey(PUBKEY_B64), extract_pubkey_key(&pubkey_blob));
    let mut tampered = REPODATA.to_vec();
    tampered[3] ^= 1;
    assert!(!verify_signify(PUBKEY_B64, SIG_FILE, &tampered));
}

#[test]
fn header_fields_are_extracted_and_short_blobs_rejected() {
    let (_, verifying) = fresh_key(3);
    let key_blob = blob(ALGO, KEY_NUM, &verifying);
    assert_eq!(algo_id(&key_blob), Some(ALGO));
    assert_eq!(key_num(&key_blob), Some(KEY_NUM));
    assert_eq!(algo_id(&[]), None);
    assert_eq!(algo_id(&[b'E']), None);
    assert_eq!(key_num(&key_blob[..HEADER_LEN - 1]), None);
    assert_eq!(key_num(&[]), None);
    let real = STANDARD.decode(PUBKEY_B64).unwrap();
    assert_eq!(algo_id(&real), Some([real[0], real[1]]));
    assert_eq!(key_num(&real).map(|n| n.to_vec()), Some(real[2..10].to_vec()));
}

// Kotlin never checks the algorithm id or key number: framing bytes are
// sliced off and ignored. These tests pin that (perhaps surprising)
// behaviour so a future "helpful" header check cannot silently change
// what the trust anchor accepts relative to the Kotlin parser.
#[test]
fn mismatched_algo_and_key_num_still_verify_like_kotlin() {
    let msg = b"header semantics";
    let (signing, verifying) = fresh_key(11);
    let signature = signing.sign(msg).to_bytes();
    let other_num = *b"87654321";
    for algo in [*b"Ed", *b"XX", [0, 0]] {
        for key_num in [KEY_NUM, other_num] {
            let key_blob = blob(algo, key_num, &verifying);
            let sig_blob = blob(algo, key_num, &signature);
            assert!(verify_signify_blobs(&key_blob, &sig_blob, msg), "algo {algo:?} num {key_num:?}");
        }
    }
    // Key and signature framing need not agree with each other either.
    let key_blob = blob(*b"ZZ", other_num, &verifying);
    let sig_blob = blob(ALGO, KEY_NUM, &signature);
    assert!(verify_signify_blobs(&key_blob, &sig_blob, msg));
    let pubkey_b64 = STANDARD.encode(&key_blob);
    let sig_file = format!("untrusted comment: x\n{}\n", STANDARD.encode(&sig_blob));
    assert!(verify_signify(&pubkey_b64, &sig_file, msg));
}

#[test]
fn wrong_key_rejected_even_with_copied_framing() {
    let msg = b"who signed this";
    let (signing_a, _) = fresh_key(21);
    let (_, verifying_b) = fresh_key(22);
    let signature = signing_a.sign(msg).to_bytes();
    let key_blob = blob(ALGO, KEY_NUM, &verifying_b);
    let sig_blob = blob(ALGO, KEY_NUM, &signature);
    assert!(!verify_signify_blobs(&key_blob, &sig_blob, msg));
}

#[test]
fn extraction_at_every_length_boundary() {
    let (_, verifying) = fresh_key(5);
    let (signing, _) = fresh_key(5);
    let signature = signing.sign(b"m").to_bytes();
    let key_blob = blob(ALGO, KEY_NUM, &verifying);
    let sig_blob = blob(ALGO, KEY_NUM, &signature);
    for len in 0..=key_blob.len() + 2 {
        let prefix = &key_blob[..len.min(key_blob.len())];
        let expect = (prefix.len() >= PUBKEY_BLOB_LEN).then_some(verifying);
        assert_eq!(extract_pubkey_key(prefix), expect, "key len {len}");
        assert_eq!(verify_signify_blobs(prefix, &sig_blob, b"m"), prefix.len() >= PUBKEY_BLOB_LEN);
    }
    for len in 0..=sig_blob.len() {
        let prefix = &sig_blob[..len];
        let expect = (len >= SIG_BLOB_LEN).then_some(signature);
        assert_eq!(extract_signature(prefix), expect, "sig len {len}");
    }
    // Bytes past the extracted slice are ignored, as in Kotlin.
    let mut long_key = key_blob.clone();
    long_key.extend_from_slice(b"trailing junk");
    assert_eq!(extract_pubkey_key(&long_key), Some(verifying));
    let mut long_sig = sig_blob.clone();
    long_sig.extend_from_slice(b"trailing junk");
    assert_eq!(extract_signature(&long_sig), Some(signature));
    assert!(verify_signify_blobs(&long_key, &long_sig, b"m"));
}

#[test]
fn base64_padding_whitespace_and_wrapping_variants() {
    let msg = b"flexible base64";
    let (pubkey_b64, sig_file, msg) = fixture(9, msg);
    let unpadded_key = pubkey_b64.trim_end_matches('=');
    assert!(verify_signify(unpadded_key, &sig_file, &msg));
    let sig_line = sig_file.lines().nth(1).unwrap();
    let unpadded_sig = format!("untrusted comment: x\n{}\n", sig_line.trim_end_matches('='));
    assert!(verify_signify(&pubkey_b64, &unpadded_sig, &msg));
    // Whitespace around and inside the base64, as Android's decoder allows.
    let spaced_key = format!("  \n{}\n ", pubkey_b64);
    assert_eq!(decode_pubkey(&spaced_key), decode_pubkey(&pubkey_b64));
    let mut spaced_blob = String::new();
    for (i, ch) in sig_line.chars().enumerate() {
        if i > 0 && i % 16 == 0 {
            spaced_blob.push(' ');
        }
        spaced_blob.push(ch);
    }
    let spaced_file = format!("untrusted comment: x\n{spaced_blob}\n");
    assert!(verify_signify(&pubkey_b64, &spaced_file, &msg));
    let wrapped_key = format!("{}\n{}", &pubkey_b64[..20], &pubkey_b64[20..]);
    assert_eq!(decode_pubkey(&wrapped_key), decode_pubkey(&pubkey_b64));
}

#[test]
fn malformed_base64_is_rejected() {
    for bad in ["", " ", "====", "=", "A", "AB=C", "!!!", "a b\t!", "éééé", "####"] {
        assert_eq!(decode_pubkey(bad), None, "decode_pubkey {bad:?}");
        assert_eq!(decode_signature(bad), None, "decode_signature {bad:?}");
        assert!(!verify_signify(bad, bad, b"msg"), "verify_signify {bad:?}");
    }
    // Valid base64 that decodes too short is rejected at extraction.
    let short = STANDARD.encode(blob(ALGO, KEY_NUM, &[0u8; 8]));
    assert_eq!(decode_pubkey(&short), None);
    assert_eq!(decode_signature(&short), None);
    let almost_key = STANDARD.encode(&blob(ALGO, KEY_NUM, &[0u8; 31]));
    assert_eq!(decode_pubkey(&almost_key), None);
}

#[test]
fn sig_file_comment_and_line_variants() {
    let msg = b"sig file shapes";
    let (pubkey_b64, sig_file, msg) = fixture(13, msg);
    let blob_line = sig_file.lines().nth(1).unwrap().to_string();
    let variants = [
        sig_file.clone(),
        format!("{blob_line}\n"),
        format!("{blob_line}"),
        format!("untrusted comment: verify with fixture.pub\n{blob_line}"),
        format!("  untrusted comment: indented comment  \n  {blob_line}  \n\n"),
        format!("\n\nuntrusted comment: x\n\n{blob_line}\n\n\n"),
        format!("untrusted comment: x\r\n{blob_line}\r\n"),
        format!("untrusted comment:\n{blob_line}\n"),
        format!("untrusted comment: one\nuntrusted comment: two\n{blob_line}\n"),
    ];
    for text in &variants {
        assert!(verify_signify(&pubkey_b64, text, &msg), "variant {text:?}");
        assert_eq!(sig_file_signature(text), sig_file_signature(&sig_file));
    }
}

#[test]
fn sig_file_rejects_missing_or_unusable_blob_line() {
    let msg = b"nothing to verify with";
    let (pubkey_b64, sig_file, msg) = fixture(15, msg);
    for text in [
        "",
        "\n\n",
        "untrusted comment: only a comment\n",
        "untrusted comment: only a comment",
        "   \n\t\n",
        "not base64 at all!!!\n",
    ] {
        assert_eq!(sig_file_signature(text), None, "sig_file_signature {text:?}");
        assert!(!verify_signify(&pubkey_b64, text, &msg), "verify_signify {text:?}");
    }
    // A non-comment final line wins over an earlier valid blob (Kotlin
    // takes the last qualifying line), so trailing garbage fails closed.
    let trailed = format!("{sig_file}garbage line\n");
    assert!(!verify_signify(&pubkey_b64, &trailed, &msg));
    // "untrusted comment" without the colon is not a comment line; as the
    // last line it is taken as the blob and fails to decode far enough.
    let no_colon = format!("{sig_file}untrusted comment\n");
    assert!(!verify_signify(&pubkey_b64, &no_colon, &msg));
}

#[test]
fn sig_file_takes_last_blob_line() {
    let (pubkey_b64, sig_file_a, msg_a) = fixture(17, b"first message");
    let (_, sig_file_b, msg_b) = {
        let (signing, _) = fresh_key(17);
        let sig_b64 = STANDARD.encode(blob(ALGO, KEY_NUM, &signing.sign(b"second message").to_bytes()));
        ((), format!("untrusted comment: x\n{sig_b64}\n"), b"second message".to_vec())
    };
    let two_blobs = format!("{}{}", sig_file_a, sig_file_b.lines().nth(1).unwrap());
    let two_blobs = format!("{two_blobs}\n");
    assert!(verify_signify(&pubkey_b64, &two_blobs, &msg_b));
    assert!(!verify_signify(&pubkey_b64, &two_blobs, &msg_a));
}

#[test]
fn tampered_key_signature_and_message_all_rejected() {
    let msg = b"tamper-evident";
    let (pubkey_b64, sig_file, msg) = fixture(19, msg);
    let (signing, verifying) = fresh_key(19);
    let signature = signing.sign(&msg).to_bytes();
    for pos in [0, 1, 10, 31] {
        let mut key = verifying;
        key[pos] ^= 1;
        assert!(!verify(&key, &msg, &signature), "key byte {pos}");
    }
    for pos in [0, 32, 63] {
        let mut sig = signature;
        sig[pos] ^= 1;
        assert!(!verify(&verifying, &msg, &sig), "sig byte {pos}");
    }
    for pos in [0, msg.len() / 2, msg.len() - 1] {
        let mut forged = msg.clone();
        forged[pos] ^= 1;
        assert!(!verify_signify(&pubkey_b64, &sig_file, &forged), "msg byte {pos}");
    }
    assert!(!verify_signify(&pubkey_b64, &sig_file, b""));
    assert!(!verify_signify(&pubkey_b64, &sig_file, b"tamper-evident!"));
    // Flipping a character inside the base64 blob (not the padding) must
    // change the decoded material and fail.
    let mut chars: Vec<char> = sig_file.chars().collect();
    // Well past the 10 framing bytes, inside the signature material.
    let idx = sig_file.find('\n').unwrap() + 21;
    chars[idx] = if chars[idx] == 'A' { 'B' } else { 'A' };
    let forged_file: String = chars.into_iter().collect();
    assert!(!verify_signify(&pubkey_b64, &forged_file, &msg));
}

#[test]
fn verify_inputs_requires_all_three_present_and_valid() {
    let msg = b"jni core";
    let (signing, verifying) = fresh_key(23);
    let signature = signing.sign(msg).to_bytes();
    let (pk, m, s) = (Some(verifying.to_vec()), Some(msg.to_vec()), Some(signature.to_vec()));
    assert!(verify_inputs(pk.clone(), m.clone(), s.clone()));
    assert!(!verify_inputs(None, m.clone(), s.clone()));
    assert!(!verify_inputs(pk.clone(), None, s.clone()));
    assert!(!verify_inputs(pk.clone(), m.clone(), None));
    assert!(!verify_inputs(None, None, None));
    assert!(!verify_inputs(Some(vec![0u8; 31]), m.clone(), s.clone()));
    assert!(!verify_inputs(pk.clone(), m.clone(), Some(vec![0u8; 65])));
    assert!(!verify_inputs(pk, Some(b"other".to_vec()), s));
}

#[test]
fn truncated_and_garbage_inputs_fail_closed_without_panicking() {
    let msg = b"boundary sweep";
    let (pubkey_b64, sig_file, msg) = fixture(29, msg);
    let key_blob = STANDARD.decode(&pubkey_b64).unwrap();
    let sig_blob = STANDARD.decode(sig_file.lines().nth(1).unwrap()).unwrap();
    for len in 0..key_blob.len() {
        assert!(!verify_signify_blobs(&key_blob[..len], &sig_blob, &msg), "key blob len {len}");
    }
    for len in 0..sig_blob.len() {
        assert!(!verify_signify_blobs(&key_blob, &sig_blob[..len], &msg), "sig blob len {len}");
    }
    for len in 0..pubkey_b64.len() {
        assert!(!verify_signify(&pubkey_b64[..len], &sig_file, &msg), "key b64 len {len}");
    }
    for garbage in [vec![0u8; 42], vec![0xFFu8; 74], vec![0u8; 74]] {
        assert!(!verify_signify_blobs(&garbage, &garbage, &msg));
        assert!(!verify_inputs(Some(garbage.clone()), Some(msg.clone()), Some(garbage)));
    }
}
