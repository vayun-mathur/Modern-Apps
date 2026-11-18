//! Edge-case tests for `lib.rs` primitives (malformed DER, KDF lengths, sizes).
use super::*;

#[test]
fn malformed_kem_inputs_are_rejected() {
    assert!(mlkem_encaps_der(&[]).is_none());
    assert!(mlkem_encaps_der(&[0u8; 100]).is_none());
    assert!(mlkem_encaps_der(&[0u8; KEM_EK - 1]).is_none());
    let (pub_der, priv_der) = mlkem_keygen_der();
    let (ct, _) = mlkem_encaps_der(&pub_der).unwrap();
    assert!(mlkem_decaps_der(&[], &ct).is_none());
    assert!(mlkem_decaps_der(&[0u8; 100], &ct).is_none());
    assert!(mlkem_decaps_der(&priv_der, &[]).is_none());
    assert!(mlkem_decaps_der(&priv_der, &[0u8; 100]).is_none());
    assert!(mlkem_decaps_der(&priv_der, &ct[..KEM_CT - 1]).is_none());
    let mut long_ct = ct.clone();
    long_ct.push(0);
    assert!(mlkem_decaps_der(&priv_der, &long_ct).is_none());
    // truncated public key (shorter than the raw key) cannot encapsulate
    assert!(mlkem_encaps_der(&pub_der[..100]).is_none());
}

#[test]
fn malformed_dsa_inputs_are_rejected() {
    assert!(mldsa_sign_der(&[], b"m").is_none());
    assert!(mldsa_sign_der(&[0u8; 100], b"m").is_none());
    let (pub_der, priv_der) = mldsa_keygen_der();
    let sig = mldsa_sign_der(&priv_der, b"m").unwrap();
    assert_eq!(sig.len(), DSA_SIG);
    // verify: malformed pub / wrong sig sizes are false via bool API, Err via checked API
    for bad_pub in [&b""[..], &b"short"[..], &[0u8; 100][..]] {
        assert!(!mldsa_verify_der(bad_pub, b"m", &sig));
        assert_eq!(mldsa_verify_checked(bad_pub, b"m", &sig), Err(()));
    }
    for bad_sig_len in [0usize, 1, 100, DSA_SIG - 1, DSA_SIG + 1] {
        let s = vec![0u8; bad_sig_len];
        assert!(!mldsa_verify_der(&pub_der, b"m", &s), "sig len {bad_sig_len}");
        assert_eq!(mldsa_verify_checked(&pub_der, b"m", &s), Err(()), "sig len {bad_sig_len}");
    }
    assert_eq!(mldsa_verify_checked(&pub_der, b"m", &sig), Ok(true));
    assert_eq!(mldsa_verify_checked(&pub_der, b"other", &sig), Ok(false));
    let mut tampered = sig.clone();
    tampered[42] ^= 0xff;
    assert_eq!(mldsa_verify_checked(&pub_der, b"m", &tampered), Ok(false));
    assert!(mldsa_sign_der(&priv_der[..100], b"m").is_none());
}

#[test]
fn concat_kdf_lengths_and_determinism() {
    let z = [9u8; 32];
    assert!(concat_kdf_sha256(&z, 0).is_empty());
    assert_eq!(concat_kdf_sha256(&z, 1).len(), 1);
    assert_eq!(concat_kdf_sha256(&z, 32).len(), 32);
    assert_eq!(concat_kdf_sha256(&z, 33).len(), 33);
    assert_eq!(concat_kdf_sha256(&z, 64).len(), 64);
    assert_eq!(concat_kdf_sha256(&z, 100).len(), 100);
    // prefix property: longer outputs extend shorter ones (counter blocks)
    let k32 = concat_kdf_sha256(&z, 32);
    let k64 = concat_kdf_sha256(&z, 64);
    assert_eq!(&k64[..32], &k32[..]);
    assert_ne!(&k64[32..], &k32[..]);
    assert_eq!(concat_kdf_sha256(&z, 32), k32);
    assert_ne!(concat_kdf_sha256(&[8u8; 32], 32), k32);
    assert_ne!(concat_kdf_sha256(&[], 32), k32);
    assert_eq!(concat_kdf_sha256(&[], 32).len(), 32);
}

#[test]
fn der_trailing_slice_helpers_edges() {
    assert_eq!(spki_raw(&[1, 2, 3], 4), None);
    assert_eq!(spki_raw(&[1, 2, 3], 3), Some(&[1, 2, 3][..]));
    assert_eq!(spki_raw(&[9, 1, 2, 3], 3), Some(&[1, 2, 3][..]));
    assert_eq!(spki_raw(&[], 1), None);
    assert_eq!(pkcs8_expanded(&[1, 2], 3), None);
    assert_eq!(pkcs8_expanded(&[9, 1, 2], 2), Some(&[1, 2][..]));
    assert_eq!(to_arr::<4>(&[1, 2, 3]), None);
    assert_eq!(to_arr::<4>(&[1, 2, 3, 4]), Some([1, 2, 3, 4]));
    assert_eq!(to_arr::<4>(&[1, 2, 3, 4, 5]), None);
    assert_eq!(spki_wrap(&[0xaa], &[1, 2]), vec![0xaa, 1, 2]);
}

#[test]
fn link_seed_expand_is_deterministic() {
    let a = link_seed_expand(&[1u8; 32]);
    assert_eq!(a, link_seed_expand(&[1u8; 32]));
    assert_ne!(a, link_seed_expand(&[2u8; 32]));
    assert_eq!(a.len(), KEM_SEED);
    let seed = mlkem_link_seed_new();
    assert_eq!(seed.len(), LINK_SEED);
}

#[test]
fn generated_key_sizes_and_prefixes() {
    let (kp, ks) = mlkem_keygen_der();
    assert!(kp.starts_with(&KEM_PUB_PREFIX));
    assert!(ks.starts_with(&KEM_PRIV_PREFIX));
    let (dp, ds) = mldsa_keygen_der();
    assert!(dp.starts_with(&DSA_PUB_PREFIX));
    assert!(ds.starts_with(&DSA_PRIV_PREFIX));
    // fresh keypairs differ
    let (kp2, _) = mlkem_keygen_der();
    assert_ne!(kp, kp2);
    let (dp2, _) = mldsa_keygen_der();
    assert_ne!(dp, dp2);
}
