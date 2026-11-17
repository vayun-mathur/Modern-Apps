//! Coverage tests: crypto primitives + Signal wire codec.
//! Pure/deterministic: known-answer vectors, boundary lengths, malformed input.

use communicate_signal::crypto;
use communicate_signal::wire::{
    PbReader, PbValue, PbWriter, PreKeySignalMessage, SenderKeyDistributionMessage,
    SenderKeyMessage, SignalMessage,
};

fn kp() -> ([u8; 32], [u8; 32]) {
    crypto::generate_key_pair(&mut communicate_signal::OsRng::default())
}
fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

// ---------------- crypto: HKDF / HMAC known answers ----------------

#[test]
fn hkdf_matches_rfc5869_case1() {
    let ikm = [0x0bu8; 22];
    let salt: Vec<u8> = (0u8..13).collect();
    let info: Vec<u8> = (0xf0u8..0xfa).collect();
    let okm = crypto::hkdf(&ikm, &salt, &info, 42);
    assert_eq!(
        hex(&okm),
        "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
    );
}

#[test]
fn hkdf_lengths_and_multiblock() {
    assert_eq!(crypto::hkdf(b"k", &[], b"i", 0).len(), 0);
    assert_eq!(crypto::hkdf(b"k", &[], b"i", 1).len(), 1);
    assert_eq!(crypto::hkdf(b"k", &[], b"i", 32).len(), 32);
    let long = crypto::hkdf(b"k", &[], b"i", 100); // >3 blocks
    assert_eq!(long.len(), 100);
    // Prefix property: first 32 bytes of a longer output equal the 32-byte output.
    assert_eq!(&long[..32], &crypto::hkdf(b"k", &[], b"i", 32)[..]);
    assert_ne!(crypto::hkdf(b"k", &[], b"a", 32), crypto::hkdf(b"k", &[], b"b", 32));
    assert_ne!(crypto::hkdf(b"k1", &[], b"i", 32), crypto::hkdf(b"k2", &[], b"i", 32));
    assert_ne!(
        crypto::hkdf(b"k", &[1u8; 32], b"i", 32),
        crypto::hkdf(b"k", &[2u8; 32], b"i", 32)
    );
}

#[test]
fn hmac_matches_rfc4231_and_key_lengths() {
    let mac = crypto::hmac_sha256(&[0x0bu8; 20], b"Hi There");
    assert_eq!(
        hex(&mac),
        "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
    );
    // Any key length is accepted (empty, block-size, over block-size).
    assert_eq!(crypto::hmac_sha256(&[], b"d").len(), 32);
    assert_eq!(crypto::hmac_sha256(&[7u8; 64], b"d").len(), 32);
    assert_eq!(crypto::hmac_sha256(&[7u8; 200], b"d").len(), 32);
    assert_ne!(crypto::hmac_sha256(&[7u8; 200], b"d"), crypto::hmac_sha256(&[7u8; 64], b"d"));
    assert_eq!(crypto::hmac_sha256(b"k", b""), crypto::hmac_sha256(b"k", b""));
}

#[test]
fn message_keys_layout_is_stable() {
    let a = crypto::MessageKeys::derive(&[9u8; 32]);
    let b = crypto::MessageKeys::derive(&[9u8; 32]);
    assert_eq!(a.cipher_key, b.cipher_key);
    assert_eq!(a.mac_key, b.mac_key);
    assert_eq!(a.iv, b.iv);
    // cipher/mac/iv are distinct slices of the derived stream.
    assert_ne!(&a.cipher_key[..16], &a.iv[..]);
    assert_ne!(a.cipher_key, a.mac_key);
    let c = crypto::MessageKeys::derive(&[8u8; 32]);
    assert_ne!(a.cipher_key, c.cipher_key);
}

// ---------------- crypto: AES-CBC ----------------

#[test]
fn aes_cbc_boundaries() {
    let key = [0x42u8; 32];
    let iv = [0x24u8; 16];
    for len in [0usize, 1, 15, 16, 17, 31, 32, 33, 255, 256, 1024] {
        let plain: Vec<u8> = (0..len).map(|i| (i % 251) as u8).collect();
        let ct = crypto::aes_cbc_encrypt(&key, &iv, &plain);
        assert_eq!(ct.len(), (len / 16 + 1) * 16, "padded len for {len}");
        assert_eq!(crypto::aes_cbc_decrypt(&key, &iv, &ct).unwrap(), plain, "len {len}");
    }
    // Block-aligned input gains a full padding block; changing key/iv changes ct.
    let p = [5u8; 16];
    assert_ne!(crypto::aes_cbc_encrypt(&key, &iv, &p), crypto::aes_cbc_encrypt(&[1u8; 32], &iv, &p));
    assert_ne!(crypto::aes_cbc_encrypt(&key, &iv, &p), crypto::aes_cbc_encrypt(&key, &[2u8; 16], &p));
}

#[test]
fn aes_cbc_rejects_bad_padding_and_shape() {
    let key = [0x42u8; 32];
    let iv = [0x24u8; 16];
    assert!(crypto::aes_cbc_decrypt(&key, &iv, &[]).is_err());
    assert!(crypto::aes_cbc_decrypt(&key, &iv, &[0u8; 15]).is_err());
    assert!(crypto::aes_cbc_decrypt(&key, &iv, &[0u8; 17]).is_err());
    let ct = crypto::aes_cbc_encrypt(&key, &iv, b"hello world hello!");
    for flip in [0x01u8, 0x80, 0xff] {
        let mut bad = ct.clone();
        let n = bad.len();
        bad[n - 1] ^= flip;
        assert!(crypto::aes_cbc_decrypt(&key, &iv, &bad).is_err(), "flip {flip:#x}");
    }
    // Wrong key cannot produce the original plaintext (padding or content).
    match crypto::aes_cbc_decrypt(&[0u8; 32], &iv, &ct) {
        Ok(v) => assert_ne!(v, b"hello world hello!"),
        Err(_) => {}
    }
}

// ---------------- crypto: Curve25519 ----------------

#[test]
fn agreement_is_symmetric_for_real_pairs() {
    let (a_priv, a_pub) = kp();
    let (b_priv, b_pub) = kp();
    assert_eq!(crypto::agreement(&a_priv, &b_pub), crypto::agreement(&b_priv, &a_pub));
    let (c_priv, _c_pub) = kp();
    assert_ne!(crypto::agreement(&a_priv, &b_pub), crypto::agreement(&c_priv, &b_pub));
    assert_eq!(crypto::public_from_private(&a_priv), a_pub);
    assert_eq!(crypto::public_from_private(&a_priv), crypto::public_from_private(&a_priv));
}

#[test]
fn sign_verify_variants() {
    let (priv_a, pub_a) = kp();
    let (_priv_b, pub_b) = kp();
    let sig = crypto::sign(&priv_a, b"payload", &[1u8; 64]);
    assert!(crypto::verify(&pub_a, b"payload", &sig));
    assert!(!crypto::verify(&pub_b, b"payload", &sig), "wrong public key");
    assert!(!crypto::verify(&pub_a, b"payload!", &sig), "wrong message");
    assert!(!crypto::verify(&pub_a, b"", &sig));
    let sig2 = crypto::sign(&priv_a, b"payload", &[2u8; 64]);
    assert_ne!(sig, sig2);
    assert!(crypto::verify(&pub_a, b"payload", &sig2));
    let sig_empty = crypto::sign(&priv_a, b"", &[3u8; 64]);
    assert!(crypto::verify(&pub_a, b"", &sig_empty));
    let mut bad_r = sig;
    bad_r[0] ^= 1;
    assert!(!crypto::verify(&pub_a, b"payload", &bad_r));
    let mut bad_s = sig;
    bad_s[40] ^= 1;
    assert!(!crypto::verify(&pub_a, b"payload", &bad_s));
    assert!(!crypto::verify(&pub_a, b"payload", &[0u8; 64]));
}

#[test]
fn parse_public_edge_lengths() {
    let (_priv, pubk) = kp();
    assert_eq!(crypto::parse_public(&pubk).unwrap(), pubk);
    assert_eq!(crypto::parse_public(&crypto::serialize_public(&pubk)).unwrap(), pubk);
    assert!(crypto::parse_public(&[]).is_err());
    assert!(crypto::parse_public(&[0u8; 31]).is_err());
    assert!(crypto::parse_public(&[0u8; 34]).is_err());
    let mut wrong_prefix = vec![0x04u8];
    wrong_prefix.extend_from_slice(&pubk);
    assert!(crypto::parse_public(&wrong_prefix).is_err());
    assert!(crypto::ct_eq(b"abc", b"abc"));
    assert!(!crypto::ct_eq(b"abc", b"abd"));
    assert!(!crypto::ct_eq(b"abc", b"ab"));
    assert!(crypto::ct_eq(b"", b""));
}

// ---------------- wire: protobuf codec ----------------

#[test]
fn protobuf_varint_boundaries_and_unknown_fields() {
    for v in [0u32, 1, 127, 128, 300, 16_383, 16_384, u32::MAX] {
        let mut w = PbWriter::new();
        w.uint32(7, v);
        let mut r = PbReader::new(&w.buf);
        match r.next_field().unwrap() {
            Some((7, PbValue::Varint(got))) => assert_eq!(got, v as u64),
            other => panic!("unexpected field for {v}: {:?}", matches!(other, Some(_))),
        }
        assert!(r.next_field().unwrap().is_none());
    }
    let mut w = PbWriter::new();
    w.uint32(99, 5);
    w.bytes(1, b"x");
    let mut r = PbReader::new(&w.buf);
    assert!(matches!(r.next_field().unwrap(), Some((99, PbValue::Varint(5)))));
    assert!(matches!(r.next_field().unwrap(), Some((1, PbValue::Bytes(b"x")))));
}

#[test]
fn protobuf_rejects_malformed() {
    assert!(PbReader::new(&[0x80]).next_field().is_err(), "truncated varint");
    assert!(PbReader::new(&[0x80, 0x80]).next_field().is_err());
    for key in [0x0bu8, 0x0c, 0x0e, 0x0f] {
        assert!(PbReader::new(&[key, 0, 0, 0, 0, 0, 0, 0, 0]).next_field().is_err(), "key {key:#x}");
    }
    assert!(PbReader::new(&[0x12, 0x7f, 1, 2]).next_field().is_err());
    let mut r = PbReader::new(&[0x09, 1, 2, 3, 4, 5, 6, 7, 8]);
    assert!(matches!(r.next_field().unwrap(), Some((1, PbValue::Varint(0)))));
    let mut r = PbReader::new(&[0x0d, 1, 2, 3, 4]);
    assert!(matches!(r.next_field().unwrap(), Some((1, PbValue::Varint(0)))));
    assert!(PbReader::new(&[0x09, 1, 2]).next_field().is_err(), "truncated 64-bit");
    assert!(PbReader::new(&[0x0d, 1]).next_field().is_err(), "truncated 32-bit");
}

// ---------------- wire: SignalMessage ----------------

#[test]
fn signal_message_counters_and_empty_ciphertext() {
    let (_pa, pub_a) = kp();
    let (_pb, pub_b) = kp();
    let (_pr, ratchet) = kp();
    let mac = [3u8; 32];
    for (counter, prev) in [(0u32, 0u32), (1, 0), (u32::MAX, u32::MAX - 1)] {
        let m = SignalMessage::new(&mac, &pub_a, &pub_b, &ratchet, counter, prev, b"");
        let back = SignalMessage::parse(&m.serialized).unwrap();
        assert_eq!(back.counter, counter);
        assert_eq!(back.previous_counter, prev);
        assert_eq!(back.ciphertext, Vec::<u8>::new());
        assert!(back.verify_mac(&mac, &pub_a, &pub_b));
        assert!(!back.verify_mac(&[4u8; 32], &pub_a, &pub_b), "wrong mac key");
        assert!(!back.verify_mac(&mac, &pub_b, &pub_a), "swapped identities");
    }
    let big = vec![0xabu8; 10_000];
    let m = SignalMessage::new(&mac, &pub_a, &pub_b, &ratchet, 9, 8, &big);
    assert_eq!(SignalMessage::parse(&m.serialized).unwrap().ciphertext, big);
    let mut bad = m.serialized.clone();
    bad[5] ^= 1;
    let parsed = SignalMessage::parse(&bad).unwrap();
    assert!(!parsed.verify_mac(&mac, &pub_a, &pub_b));
    let mut alt = m.serialized.clone();
    alt[0] = 0x30;
    assert!(SignalMessage::parse(&alt).is_ok());
}

#[test]
fn signal_message_missing_fields_rejected() {
    let (_pr, ratchet) = kp();
    let mut w = PbWriter::new();
    w.bytes(1, &crypto::serialize_public(&ratchet));
    let mut data = vec![0x33u8];
    data.extend_from_slice(&w.buf);
    data.extend_from_slice(&[0u8; 8]);
    assert!(SignalMessage::parse(&data).is_err());
    let mut w = PbWriter::new();
    w.uint32(2, 1);
    let mut data = vec![0x33u8];
    data.extend_from_slice(&w.buf);
    data.extend_from_slice(&[0u8; 8]);
    assert!(SignalMessage::parse(&data).is_err());
    assert!(SignalMessage::parse(&[0x33]).is_err(), "version only, no MAC room");
}

// ---------------- wire: PreKey / SenderKey messages ----------------

#[test]
fn prekey_signal_message_field_edges() {
    let (_p1, base) = kp();
    let (_p2, ident) = kp();
    let full = PreKeySignalMessage {
        registration_id: 0,
        pre_key_id: Some(0),
        signed_pre_key_id: 0,
        base_key: base,
        identity_key: ident,
        message: vec![],
    };
    let back = PreKeySignalMessage::parse(&full.serialize()).unwrap();
    assert_eq!(back.registration_id, 0);
    assert_eq!(back.pre_key_id, Some(0));
    assert_eq!(back.message, Vec::<u8>::new());
    let mut w = PbWriter::new();
    w.bytes(2, &crypto::serialize_public(&base));
    w.bytes(3, &crypto::serialize_public(&ident));
    w.bytes(4, b"inner");
    let mut data = vec![0x33u8];
    data.extend_from_slice(&w.buf);
    assert!(PreKeySignalMessage::parse(&data).is_err());
    let mut w = PbWriter::new();
    w.uint32(6, 1);
    w.bytes(3, &crypto::serialize_public(&ident));
    w.bytes(4, b"inner");
    let mut data = vec![0x33u8];
    data.extend_from_slice(&w.buf);
    assert!(PreKeySignalMessage::parse(&data).is_err());
    let mut bad = full.serialize();
    bad[0] = 0x23;
    assert!(PreKeySignalMessage::parse(&bad).is_err());
}

#[test]
fn senderkey_and_distribution_missing_fields() {
    let (sign_priv, sign_pub) = kp();
    let msg = SenderKeyMessage::new(0, 0, b"", &sign_priv, &[5u8; 64]);
    let back = SenderKeyMessage::parse(&msg.serialized).unwrap();
    assert_eq!(back.key_id, 0);
    assert_eq!(back.iteration, 0);
    assert_eq!(back.ciphertext, Vec::<u8>::new());
    assert!(back.verify_signature(&sign_pub));
    let (_o_priv, other_pub) = kp();
    assert!(!back.verify_signature(&other_pub), "wrong signing key");
    assert!(SenderKeyMessage::parse(&msg.serialized[..40]).is_err());
    let mut badv = msg.serialized.clone();
    badv[0] = 0x43;
    assert!(SenderKeyMessage::parse(&badv).is_err());

    let skdm = SenderKeyDistributionMessage {
        key_id: 7,
        iteration: 3,
        chain_key: vec![],
        signing_key: sign_pub,
    };
    let back = SenderKeyDistributionMessage::parse(&skdm.serialize()).unwrap();
    assert_eq!(back.chain_key, Vec::<u8>::new());
    let mut w = PbWriter::new();
    w.uint32(1, 7);
    let mut data = vec![0x33u8];
    data.extend_from_slice(&w.buf);
    assert!(SenderKeyDistributionMessage::parse(&data).is_err());
    let mut badv = skdm.serialize();
    badv[0] = 0x13;
    assert!(SenderKeyDistributionMessage::parse(&badv).is_err());
}
