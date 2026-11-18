//! Edge-case tests for pgp.rs primitives + JNI dispatch helpers.
//! Reuses ONE keypair per identity (OnceLock) — keygen is seconds-slow.
use super::pgp::*;
use std::sync::OnceLock;
static ALICE: OnceLock<(String, String, String)> = OnceLock::new();
static BOB: OnceLock<(String, String, String)> = OnceLock::new();
fn alice() -> &'static (String, String, String) {
    ALICE.get_or_init(|| pgp_generate("alice <alice@maemail.org>", "pw12345678").expect("alice"))
}
fn bob() -> &'static (String, String, String) {
    BOB.get_or_init(|| pgp_generate("bob <bob@maemail.org>", "pw87654321").expect("bob"))
}
fn ref_crc24(data: &[u8]) -> [u8; 3] {
    // Independent table-driven CRC-24 (table built from poly 0x1864CFB).
    let mut table = [0u32; 256];
    for (n, e) in table.iter_mut().enumerate() {
        let mut c = (n as u32) << 16;
        for _ in 0..8 { c = if c & 0x800000 != 0 { (c << 1) ^ 0x1864CFB } else { c << 1 }; }
        *e = c & 0xFFFFFF;
    }
    let mut crc: u32 = 0xB704CE;
    for &b in data {
        crc = ((crc << 8) & 0xFFFFFF) ^ table[(((crc >> 16) ^ b as u32) & 0xFF) as usize];
    }
    [(crc >> 16) as u8, (crc >> 8) as u8, crc as u8]
}
#[test]
fn crc24_known_and_reference() {
    assert_eq!(crc24(b""), [0xB7, 0x04, 0xCE], "empty = init bytes");
    assert_eq!(crc24(b"123456789"), [0x21, 0xCF, 0x02], "CRC-24/OPENPGP check");
    for data in [&b""[..], b"a", b"123456789", &[0u8; 64][..], &[0xFFu8; 33][..]] {
        assert_eq!(crc24(data), ref_crc24(data), "table reference mismatch");
    }
    let varied: Vec<u8> = (0..255u32).map(|i| (i * 37 % 256) as u8).collect();
    assert_eq!(crc24(&varied), ref_crc24(&varied));
}
#[test]
fn hex_upper_cases() {
    assert_eq!(hex_upper(&[]), "");
    assert_eq!(hex_upper(&[0x00, 0xFF, 0xAB, 0x0F, 0x10]), "00FFAB0F10");
    assert_eq!(hex_upper(&[0xDE, 0xAD, 0xBE, 0xEF]), "DEADBEEF");
}
#[test]
fn is_probably_armor_cases() {
    assert!(is_probably_armor(b"-----BEGIN PGP PUBLIC KEY BLOCK-----"));
    assert!(is_probably_armor(b"-----BEGIN PGP MESSAGE-----"));
    assert!(!is_probably_armor(b""));
    assert!(!is_probably_armor(b"-----BEGIN"));
    assert!(!is_probably_armor(&[0x99, 0x01, 0xFF]));
}
#[test]
fn armored_message_wrapping_and_empty() {
    let empty = to_armored_message(b"").expect("armor empty");
    assert_eq!(empty, "-----BEGIN PGP MESSAGE-----\n\n=twTO\n-----END PGP MESSAGE-----\n");
    assert_eq!(dearmor_any(&empty).expect("dearmor empty armor"), Vec::<u8>::new());
    let raw: Vec<u8> = (0..200u32).map(|i| (i % 256) as u8).collect();
    let armored = to_armored_message(&raw).expect("armor");
    let lines: Vec<&str> = armored.lines().collect();
    assert_eq!(lines[0], "-----BEGIN PGP MESSAGE-----");
    assert_eq!(lines[1], "");
    assert_eq!(lines[lines.len() - 1], "-----END PGP MESSAGE-----");
    assert!(lines[lines.len() - 2].starts_with('='), "crc footer");
    for line in &lines[2..lines.len() - 2] { assert!(line.len() <= 64, "wrap 64: {}", line.len()); }
    assert!(lines[2..lines.len() - 2].iter().take(lines.len() - 5).all(|l| l.len() == 64));
    assert_eq!(dearmor_any(&armored).expect("roundtrip"), raw);
}
#[test]
fn dearmor_any_garbage_and_empty() {
    assert!(dearmor_any("").is_none());
    assert!(dearmor_any("not armor at all").is_none());
    assert!(dearmor_any("hello world").is_none());
    assert!(dearmor_any("-----BEGIN PGP MESSAGE-----\n\n!!!\n-----END PGP MESSAGE-----\n").is_none());
    let (_, public, _) = alice();
    assert!(dearmor_any(public).expect("valid armor dearmors").len() > 100);
}
#[test]
fn fingerprint_and_normalize_reject_secret_armor() {
    let (secret, _, _) = alice();
    assert!(pgp_fingerprint(secret).is_none(), "secret armor is not a public key");
    assert!(pgp_normalize_key(secret.as_bytes()).is_none());
    assert!(pgp_fingerprint("").is_none());
    assert!(pgp_fingerprint("-----BEGIN PGP PRIVATE KEY BLOCK-----").is_none());
}
#[test]
fn normalize_empty_garbage_binary_garbage() {
    assert!(pgp_normalize_key(b"").is_none());
    assert!(pgp_normalize_key(b"not a key").is_none());
    assert!(pgp_normalize_key(&[0xFF, 0x00, 0xFE, 1, 2, 3, 0x99]).is_none());
    assert!(pgp_normalize_key(&[0u8; 64]).is_none());
    assert!(pgp_normalize_key("-----BEGIN PGP".as_bytes()).is_none());
}
#[test]
fn decrypt_verify_signed_with_no_verifier() {
    let (a_secret, _, _) = alice();
    let (b_secret, b_public, _) = bob();
    let raw = pgp_encrypt_signed(b_public, Some((a_secret.as_str(), "pw12345678")), b"hi bob")
        .expect("signed encrypt");
    let (payload, fp) = pgp_decrypt_verify(b_secret, "pw87654321", &raw, None).expect("no verifier");
    assert_eq!(payload, b"hi bob");
    assert_eq!(fp, "", "unverified signed message yields empty fingerprint");
    // Plain pgp_decrypt on signed message also returns payload.
    assert_eq!(pgp_decrypt(b_secret, "pw87654321", &raw).expect("decrypt"), b"hi bob");
}
#[test]
fn decrypt_rejects_armored_message_instead_of_raw() {
    let (secret, public, _) = alice();
    let raw = pgp_encrypt(public, b"federated").expect("encrypt");
    let armored = to_armored_message(&raw).expect("armor");
    assert!(pgp_decrypt(secret, "pw12345678", armored.as_bytes()).is_none());
    assert!(pgp_decrypt(secret, "pw12345678", b"").is_none());
    assert!(pgp_decrypt(secret, "pw12345678", b"not pgp bytes").is_none());
    assert!(pgp_decrypt("garbage", "pw12345678", &raw).is_none());
}
#[test]
fn sign_detached_empty_data() {
    use pgp::composed::{Deserializable, DetachedSignature, SignedPublicKey};
    let (secret, public, _) = alice();
    let sig_bytes = pgp_sign_detached(secret, "pw12345678", b"").expect("sign empty");
    assert!(!sig_bytes.is_empty());
    let sig = DetachedSignature::from_bytes(&sig_bytes[..]).expect("parse");
    let (key, _) = SignedPublicKey::from_string(public).expect("key");
    assert!(sig.verify(&key.primary_key, b"").is_ok());
    assert!(sig.verify(&key.primary_key, b"x").is_err());
    assert!(pgp_sign_detached("garbage", "pw", b"data").is_none());
    assert!(pgp_sign_detached(secret, "pw12345678", b"").is_some());
}
#[test]
fn generate_empty_user_id_and_passphrase() {
    // CORRECT rPGP behavior (probed): empty uid + empty passphrase succeed.
    let (secret, public, fp) = pgp_generate("", "").expect("empty uid/pw generates");
    assert!(secret.contains("-----BEGIN PGP PRIVATE KEY BLOCK-----"));
    assert!(public.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----"));
    assert_eq!(fp.len(), 40);
    assert_eq!(pgp_fingerprint(&public), Some(fp));
    let raw = pgp_encrypt(&public, b"empty-pw payload").expect("encrypt");
    assert_eq!(pgp_decrypt(&secret, "", &raw).expect("decrypt empty pw"), b"empty-pw payload");
    assert!(pgp_decrypt(&secret, "nonempty", &raw).is_none());
}
#[test]
fn encrypt_to_key_without_encryption_subkey_fails() {
    use pgp::composed::{KeyType, SecretKeyParamsBuilder};
    use pgp::crypto::hash::HashAlgorithm;
    use pgp::crypto::sym::SymmetricKeyAlgorithm;
    use rand08::rngs::OsRng;
    // Constructible: signing-only primary, no subkeys at all.
    let mut rng = OsRng;
    let mut params = SecretKeyParamsBuilder::default();
    params.key_type(KeyType::Ed25519).can_sign(true).can_certify(false)
        .primary_user_id("signonly <s@maemail.org>".to_string())
        .passphrase(Some("pw12345678".to_string()))
        .preferred_symmetric_algorithms(smallvec::smallvec![SymmetricKeyAlgorithm::AES256])
        .preferred_hash_algorithms(smallvec::smallvec![HashAlgorithm::Sha512])
        .preferred_compression_algorithms(smallvec::smallvec![]).subkeys(vec![]);
    let secret = params.build().expect("params").generate(rng).expect("sign-only keygen");
    let public = secret.to_public_key();
    assert!(public.public_subkeys.is_empty(), "constructed key has no subkey");
    use pgp::composed::ArmorOptions;
    let armored = public.to_armored_string(ArmorOptions::default()).expect("armor");
    assert!(pgp_encrypt(&armored, b"nowhere to encrypt").is_none(), "no encryption subkey");
}
#[test]
fn signer_verifier_and_utf8_rules() {
    assert_eq!(signer_option("", "pw"), None);
    assert_eq!(signer_option("SECRET", "pw"), Some(("SECRET", "pw")));
    assert_eq!(signer_option("SECRET", ""), Some(("SECRET", "")));
    assert_eq!(verifier_option(""), None);
    assert_eq!(verifier_option("PUB"), Some("PUB"));
    assert_eq!(utf8_string(b"abc"), Some("abc".to_string()));
    assert_eq!(utf8_string(&[0xFF, 0xFE]), None);
    assert_eq!(utf8_string(b""), Some(String::new()));
}
#[test]
fn from_bytes_helpers_dispatch() {
    let (a_secret, a_public, a_fp) = alice();
    let (b_secret, b_public, _) = bob();
    assert_eq!(fingerprint_from_bytes(a_public.as_bytes()), Some(a_fp.clone()));
    assert!(fingerprint_from_bytes(&[0xFF]).is_none());
    assert!(generate_from_bytes(&[0xFF], b"pw").is_none());
    assert!(encrypt_from_bytes(&[0xFF], b"x").is_none());
    assert!(decrypt_from_bytes(b"bad", b"pw", b"x").is_none());
    assert!(armor_from_bytes(b"hi").expect("armor").contains("PGP MESSAGE"));
    // Empty secret via helper = unsigned encrypt; empty verifier = unverified.
    let raw = encrypt_signed_from_bytes(b_public.as_bytes(), b"", b"", b"unsigned").expect("unsigned");
    let (payload, fp) = decrypt_verify_from_bytes(
        b_secret.as_bytes(), b"pw87654321", &raw, b"").expect("decrypt no verifier");
    assert_eq!(payload, b"unsigned"); assert_eq!(fp, "");
    // Signed via helper + verified via helper returns signer fingerprint.
    let raw2 = encrypt_signed_from_bytes(
        b_public.as_bytes(), a_secret.as_bytes(), b"pw12345678", b"signed").expect("signed");
    let (p2, f2) = decrypt_verify_from_bytes(
        b_secret.as_bytes(), b"pw87654321", &raw2, a_public.as_bytes()).expect("verified");
    assert_eq!(p2, b"signed"); assert_eq!(f2, *a_fp);
    assert!(sign_detached_from_bytes(a_secret.as_bytes(), b"pw12345678", b"d").is_some());
    assert!(sign_detached_from_bytes(&[0xFF], b"pw", b"d").is_none());
    assert!(decrypt_from_bytes(b_secret.as_bytes(), b"pw87654321", &raw).is_some());
    assert!(encrypt_signed_from_bytes(b_public.as_bytes(), b"garbage", b"pw", b"x").is_none());
}
#[test]
fn encrypt_empty_plaintext_roundtrip() {
    let (secret, public, _) = alice();
    let raw = pgp_encrypt(public, b"").expect("encrypt empty");
    assert_eq!(pgp_decrypt(secret, "pw12345678", &raw).expect("decrypt empty"), b"");
    assert!(pgp_encrypt("garbage", b"x").is_none());
    assert!(pgp_encrypt_signed(public, Some(("garbage", "pw")), b"x").is_none());
}
