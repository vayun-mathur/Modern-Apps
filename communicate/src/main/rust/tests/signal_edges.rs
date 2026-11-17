//! Framing, state-logic and boundary tests for the communicate Signal crate.
//!
//! `src/tests.rs` is a placeholder with no coverage, so this file seeds it: pure round trips and
//! malformed-input handling for the wire codec, node codec, crypto primitives and sender-key
//! state machine. Host-only, no JNI involved.

use communicate_signal::crypto;
use communicate_signal::group;
use communicate_signal::group::SenderKeyState;
use communicate_signal::signal::{mark_kyber_pre_key_used, sealed_sender_encrypt};
use communicate_signal::signal::{sealed_sender_decrypt, sealed_sender_decrypt_any};
use communicate_signal::wa_binary::{decode_node, encode_node, Node};
use communicate_signal::wire::{
    PbReader, PbValue, PbWriter, PreKeySignalMessage, SenderKeyDistributionMessage, SenderKeyMessage,
    SignalMessage,
};

fn key_pair() -> ([u8; 32], [u8; 32]) {
    crypto::generate_key_pair(&mut communicate_signal::OsRng::default())
}

#[test]
fn signal_message_mac_round_trip_and_tamper() {
    let (_, pub_a) = key_pair();
    let (_, pub_b) = key_pair();
    let (_, ratchet_pub) = key_pair();
    let cipher = [7u8; 24];
    let mac_key = [0x11u8; 32];
    let msg = SignalMessage::new(&mac_key, &pub_a, &pub_b, &ratchet_pub, 3, 1, &cipher);
    assert!(msg.verify_mac(&mac_key, &pub_a, &pub_b));
    let parsed = SignalMessage::parse(&msg.serialized).expect("parse");
    assert!(parsed.verify_mac(&mac_key, &pub_a, &pub_b));
    assert_eq!(parsed.counter, 3);
    assert_eq!(parsed.previous_counter, 1);
    assert_eq!(parsed.ciphertext, cipher);
    let mut bad_mac = msg.serialized.clone();
    let last = bad_mac.len() - 1;
    bad_mac[last] ^= 1;
    let tampered = SignalMessage::parse(&bad_mac).expect("parse tampered");
    assert!(!tampered.verify_mac(&mac_key, &pub_a, &pub_b));
    assert!(SignalMessage::parse(&[]).is_err());
    assert!(SignalMessage::parse(&msg.serialized[..msg.serialized.len() - 1]).is_err());
}

#[test]
fn prekey_message_round_trip_and_truncated_rejected() {
    let (_, base) = key_pair();
    let (_, ident) = key_pair();
    let original = PreKeySignalMessage {
        registration_id: 4242,
        pre_key_id: Some(7),
        signed_pre_key_id: 9,
        base_key: base,
        identity_key: ident,
        message: vec![1, 2, 3, 4],
    };
    let back = PreKeySignalMessage::parse(&original.serialize()).expect("parse");
    assert_eq!(back.registration_id, 4242);
    assert_eq!(back.pre_key_id, Some(7));
    assert_eq!(back.base_key, base);
    assert_eq!(back.message, vec![1, 2, 3, 4]);
    assert!(PreKeySignalMessage::parse(&[]).is_err());
    let mut truncated = original.serialize();
    truncated.truncate(truncated.len() / 2);
    assert!(PreKeySignalMessage::parse(&truncated).is_err());
}

#[test]
fn sender_key_message_signature_verifies_and_breaks_on_tamper() {
    let (sign_priv, sign_pub) = key_pair();
    let random = [0x22u8; 64];
    let msg = SenderKeyMessage::new(5, 12, b"hello group", &sign_priv, &random);
    assert!(msg.verify_signature(&sign_pub));
    let parsed = SenderKeyMessage::parse(&msg.serialized).expect("parse");
    assert!(parsed.verify_signature(&sign_pub));
    let mut bad = msg.serialized.clone();
    bad[10] ^= 1;
    assert!(!SenderKeyMessage::parse(&bad).map(|m| m.verify_signature(&sign_pub)).unwrap_or(false));
    assert!(SenderKeyMessage::parse(&[]).is_err());
}

#[test]
fn protobuf_reader_round_trips_and_rejects_bad_input() {
    let mut writer = PbWriter::new();
    writer.uint32(1, 300);
    writer.bytes(2, b"payload");
    let mut reader = PbReader::new(&writer.buf);
    let mut seen_varint = false;
    let mut seen_bytes = false;
    while let Some((field, value)) = reader.next_field().expect("field") {
        match (field, value) {
            (1, PbValue::Varint(v)) => {
                assert_eq!(v, 300);
                seen_varint = true;
            }
            (2, PbValue::Bytes(b)) => {
                assert_eq!(b, b"payload");
                seen_bytes = true;
            }
            _ => panic!("unexpected field"),
        }
    }
    assert!(seen_varint && seen_bytes);
    // Truncating the trailing bytes field leaves the leading varint intact:
    // the first read succeeds and the second reports the truncation.
    let mut truncated = PbReader::new(&writer.buf[..writer.buf.len() - 2]);
    assert!(truncated.next_field().is_ok());
    assert!(truncated.next_field().is_err());
    assert!(PbReader::new(&[0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01])
        .next_field()
        .is_err());
    assert!(PbReader::new(&[0x1B]).next_field().is_err(), "wire type 3 is unsupported");
}

#[test]
fn node_codec_round_trips_children_payload_and_malformed_input() {
    let mut parent = Node::new("message");
    parent.attrs.insert("to".into(), "user@s.whatsapp.net".into());
    let mut child = Node::new("body");
    child.data = Some(b"hello".to_vec());
    parent.content.push(child);
    let encoded = encode_node(&parent);
    assert_eq!(encoded[0], 0, "frame flag byte leads");
    assert_eq!(decode_node(&encoded[1..]).expect("decode"), parent);
    assert!(decode_node(&[]).is_err());
    assert!(decode_node(&[0xFF, 0xFF]).is_err());
    let mut cut = encode_node(&Node::new("iq").attr("to", "a@s.whatsapp.net"));
    cut.truncate(cut.len() / 2);
    assert!(decode_node(&cut[1..]).is_err());
}

#[test]
fn crypto_primitives_round_trip_and_reject_tampering() {
    let key = [0x42u8; 32];
    let iv = [0x24u8; 16];
    let plain = b"round trip through cbc";
    let cipher = crypto::aes_cbc_encrypt(&key, &iv, plain);
    assert_eq!(crypto::aes_cbc_decrypt(&key, &iv, &cipher).expect("decrypt"), plain);
    assert!(crypto::aes_cbc_decrypt(&key, &iv, &[]).is_err());
    assert!(crypto::aes_cbc_decrypt(&key, &iv, &[1, 2, 3]).is_err());
    let mut bad_pad = cipher.clone();
    let last = bad_pad.len() - 1;
    bad_pad[last] ^= 1;
    assert!(crypto::aes_cbc_decrypt(&key, &iv, &bad_pad).is_err());
    let (priv_a, pub_a) = key_pair();
    let (_, pub_b) = key_pair();
    let (priv_b, _) = key_pair();
    let shared_ab = crypto::agreement(&priv_a, &pub_b);
    let shared_ba = crypto::agreement(&priv_b, &pub_a);
    assert_ne!(shared_ab, shared_ba, "different private keys give different secrets");
    assert_eq!(shared_ab.len(), 32);
    let mac = crypto::hmac_sha256(&key, plain);
    assert!(crypto::ct_eq(&mac, &crypto::hmac_sha256(&key, plain)));
    assert!(!crypto::ct_eq(&mac, &[0u8; 32]));
    assert!(!crypto::ct_eq(&mac, &mac[..16]));
    assert_eq!(crypto::serialize_public(&pub_a).len(), 33);
    assert_eq!(crypto::parse_public(&crypto::serialize_public(&pub_a)).expect("parse"), pub_a);
    assert!(crypto::parse_public(&[0u8; 5]).is_err());
}

#[test]
fn sender_key_state_encrypt_decrypt_and_rejects_wrong_key_id() {
    let mut rng = communicate_signal::OsRng::default();
    let (own_state, skdm) = group::create(&mut rng);
    let mut sender = own_state.clone();
    let cipher = group::encrypt(&mut rng, &mut sender, b"group hello").expect("encrypt");
    let mut peer = group::process(&skdm).expect("process");
    let plain = group::decrypt(&mut peer, &cipher).expect("decrypt");
    assert_eq!(plain, b"group hello");
    let blob = own_state.serialize();
    let restored = SenderKeyState::deserialize(&blob).expect("record");
    assert_eq!(restored.key_id, own_state.key_id);
    assert!(SenderKeyState::deserialize(&[]).is_err());
    assert!(SenderKeyState::deserialize(&blob[..blob.len() / 2]).is_err());
    let wrong_id = SenderKeyDistributionMessage {
        key_id: skdm.key_id ^ 1,
        iteration: skdm.iteration,
        chain_key: skdm.chain_key.clone(),
        signing_key: skdm.signing_key,
    };
    let mut wrong_peer = group::process(&wrong_id).expect("process wrong id");
    assert!(group::decrypt(&mut wrong_peer, &cipher).is_err());
}

#[test]
fn sealed_sender_stub_encrypts_and_rejects_garbage() {
    let cipher = sealed_sender_encrypt(b"sealed hello", "alice");
    assert!(!cipher.is_empty());
    assert_eq!(cipher[0], 1, "sealed stub version byte");
    assert!(sealed_sender_decrypt(&[], Some("alice")).is_err());
    assert!(sealed_sender_decrypt(b"junk", Some("alice")).is_err());
    assert!(sealed_sender_decrypt_any(b"junk").is_err());
    assert!(mark_kyber_pre_key_used(11, 22, b"base-key"));
    assert!(!mark_kyber_pre_key_used(11, 22, b"base-key"), "replay of same base key is rejected");
}
