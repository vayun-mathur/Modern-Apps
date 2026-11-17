//! Coverage tests: X3DH + Double Ratchet sessions and Sender-Key groups.
//! Two in-process parties, synthetic keys, no network/filesystem.

use communicate_signal::crypto;
use communicate_signal::group;
use communicate_signal::session;
use communicate_signal::wire::{
    PreKeySignalMessage, SenderKeyDistributionMessage, SenderKeyMessage, SignalMessage,
};

fn rng() -> communicate_signal::OsRng {
    communicate_signal::OsRng::default()
}
fn kp() -> ([u8; 32], [u8; 32]) {
    crypto::generate_key_pair(&mut rng())
}

struct Parties {
    alice: session::SessionState,
    bob: session::SessionState,
    bob_ik_priv: [u8; 32],
}

/// Full X3DH establishment; Bob has already consumed the pre-key message.
fn established(with_opk: bool) -> Parties {
    let mut r = rng();
    let (alice_ik_priv, alice_ik_pub) = kp();
    let (bob_ik_priv, bob_ik_pub) = kp();
    let (bob_spk_priv, bob_spk_pub) = kp();
    let (bob_opk_priv, bob_opk_pub) = kp();
    let sig = crypto::sign(&bob_ik_priv, &crypto::serialize_public(&bob_spk_pub), &[0x33u8; 64]);
    let bundle = session::PreKeyBundle {
        registration_id: 7,
        pre_key_id: if with_opk { Some(11) } else { None },
        pre_key_public: if with_opk { Some(bob_opk_pub) } else { None },
        signed_pre_key_id: 3,
        signed_pre_key_public: bob_spk_pub,
        signed_pre_key_signature: sig.to_vec(),
        identity_key: bob_ik_pub,
    };
    let mut alice =
        session::process_pre_key_bundle(&mut r, &bundle, &alice_ik_priv, &alice_ik_pub, 9).unwrap();
    let enc = session::encrypt(&mut alice, b"setup").unwrap();
    let pk = PreKeySignalMessage::parse(&enc.body).unwrap();
    let mut bob = session::process_pre_key_message(
        &pk,
        &bob_ik_priv,
        &bob_ik_pub,
        &bob_spk_priv,
        if with_opk { Some(&bob_opk_priv) } else { None },
    )
    .unwrap();
    let inner = SignalMessage::parse(&pk.message).unwrap();
    assert_eq!(session::decrypt(&mut r, &mut bob, &inner).unwrap(), b"setup");
    Parties { alice, bob, bob_ik_priv }
}

fn inner_msg(enc: &session::Encrypted) -> SignalMessage {
    let inner = if enc.is_pre_key {
        PreKeySignalMessage::parse(&enc.body).unwrap().message
    } else {
        enc.body.clone()
    };
    SignalMessage::parse(&inner).unwrap()
}

fn deliver(
    r: &mut communicate_signal::OsRng,
    enc: &session::Encrypted,
    to: &mut session::SessionState,
) -> Vec<u8> {
    // Alice keeps wrapping in pkmsg until she has received Bob's first reply
    // (her pending_pre_key clears on her first inbound ratchet step).
    let inner = if enc.is_pre_key {
        PreKeySignalMessage::parse(&enc.body).unwrap().message
    } else {
        enc.body.clone()
    };
    let msg = SignalMessage::parse(&inner).unwrap();
    session::decrypt(r, to, &msg).unwrap()
}

fn send(
    r: &mut communicate_signal::OsRng,
    from: &mut session::SessionState,
    to: &mut session::SessionState,
    text: &[u8],
) -> Vec<u8> {
    let enc = session::encrypt(from, text).unwrap();
    deliver(r, &enc, to)
}

#[test]
fn x3dh_without_one_time_prekey_still_establishes() {
    let mut p = established(false);
    let mut r = rng();
    assert_eq!(send(&mut r, &mut p.alice, &mut p.bob, b"a2b"), b"a2b");
    assert_eq!(send(&mut r, &mut p.bob, &mut p.alice, b"b2a"), b"b2a");
    let _ = p.bob_ik_priv;
}

#[test]
fn bundle_signature_validation() {
    let mut r = rng();
    let (alice_ik_priv, alice_ik_pub) = kp();
    let (bob_ik_priv, bob_ik_pub) = kp();
    let (_spk_priv, spk_pub) = kp();
    let good = crypto::sign(&bob_ik_priv, &crypto::serialize_public(&spk_pub), &[1u8; 64]);
    let mk_bundle = |sig: Vec<u8>| session::PreKeyBundle {
        registration_id: 1,
        pre_key_id: None,
        pre_key_public: None,
        signed_pre_key_id: 1,
        signed_pre_key_public: spk_pub,
        signed_pre_key_signature: sig,
        identity_key: bob_ik_pub,
    };
    assert!(session::process_pre_key_bundle(&mut r, &mk_bundle(good.to_vec()), &alice_ik_priv, &alice_ik_pub, 1).is_ok());
    // Wrong length, garbage, and a signature over the wrong key all fail.
    assert!(session::process_pre_key_bundle(&mut r, &mk_bundle(vec![0u8; 63]), &alice_ik_priv, &alice_ik_pub, 1).is_err());
    assert!(session::process_pre_key_bundle(&mut r, &mk_bundle(vec![0u8; 65]), &alice_ik_priv, &alice_ik_pub, 1).is_err());
    assert!(session::process_pre_key_bundle(&mut r, &mk_bundle([0u8; 64].to_vec()), &alice_ik_priv, &alice_ik_pub, 1).is_err());
    let wrong_msg = crypto::sign(&bob_ik_priv, b"not the spk", &[1u8; 64]);
    assert!(session::process_pre_key_bundle(&mut r, &mk_bundle(wrong_msg.to_vec()), &alice_ik_priv, &alice_ik_pub, 1).is_err());
}

#[test]
fn double_ratchet_ping_pong_many_turns() {
    let mut p = established(true);
    let mut r = rng();
    for i in 0..6 {
        let a_text = format!("alice turn {i}");
        assert_eq!(send(&mut r, &mut p.alice, &mut p.bob, a_text.as_bytes()), a_text.as_bytes());
        let b_text = format!("bob turn {i}");
        assert_eq!(send(&mut r, &mut p.bob, &mut p.alice, b_text.as_bytes()), b_text.as_bytes());
    }
    // Same-direction burst: several messages on one chain before replying.
    let mut held = Vec::new();
    for i in 0..4 {
        let enc = session::encrypt(&mut p.alice, format!("burst {i}").as_bytes()).unwrap();
        held.push(inner_msg(&enc));
    }
    for (i, m) in held.iter().enumerate() {
        assert_eq!(
            session::decrypt(&mut r, &mut p.bob, m).unwrap(),
            format!("burst {i}").as_bytes()
        );
    }
    // Empty and large plaintexts.
    assert_eq!(send(&mut r, &mut p.bob, &mut p.alice, b""), b"");
    let big = vec![0x5au8; 5000];
    assert_eq!(send(&mut r, &mut p.alice, &mut p.bob, &big), big);
}

#[test]
fn out_of_order_duplicate_and_tamper_rejected() {
    let mut p = established(true);
    let mut r = rng();
    // Alice sends 0,1,2; Bob receives 2 first (skips 0,1), then 0, then 1.
    let mut msgs = Vec::new();
    for t in [b"m0".as_slice(), b"m1".as_slice(), b"m2".as_slice()] {
        let enc = session::encrypt(&mut p.alice, t).unwrap();
        msgs.push(inner_msg(&enc));
    }
    assert_eq!(session::decrypt(&mut r, &mut p.bob, &msgs[2]).unwrap(), b"m2");
    assert_eq!(session::decrypt(&mut r, &mut p.bob, &msgs[0]).unwrap(), b"m0");
    assert_eq!(session::decrypt(&mut r, &mut p.bob, &msgs[1]).unwrap(), b"m1");
    // Replays fail: skipped keys are consumed, old counters are duplicates.
    assert!(session::decrypt(&mut r, &mut p.bob, &msgs[0]).is_err());
    assert!(session::decrypt(&mut r, &mut p.bob, &msgs[2]).is_err());
    // Tampered message fails MAC without consuming its counter: the
    // genuine copy with the same counter must still decrypt afterwards.
    let enc = session::encrypt(&mut p.alice, b"genuine").unwrap();
    let genuine = inner_msg(&enc);
    let mut raw = genuine.serialized.clone();
    let n = raw.len();
    raw[n - 1] ^= 1; // trailing MAC byte: parse succeeds, MAC must fail
    let tampered = SignalMessage::parse(&raw).unwrap();
    assert!(session::decrypt(&mut r, &mut p.bob, &tampered).is_err());
    assert_eq!(session::decrypt(&mut r, &mut p.bob, &genuine).unwrap(), b"genuine");
    let enc2 = session::encrypt(&mut p.alice, b"still alive").unwrap();
    assert_eq!(session::decrypt(&mut r, &mut p.bob, &inner_msg(&enc2)).unwrap(), b"still alive");
}

fn tampered(msg: &SignalMessage) -> SignalMessage {
    let mut raw = msg.serialized.clone();
    let n = raw.len();
    raw[n - 1] ^= 1;
    SignalMessage::parse(&raw).unwrap()
}

#[test]
fn failed_decrypt_preserves_skipped_and_ratchet_messages() {
    let mut p = established(true);
    let mut r = rng();
    // Skipped-key path: deliver the second message first, then a tampered
    // copy of the skipped first message must not burn its skipped key.
    let m0 = inner_msg(&session::encrypt(&mut p.alice, b"s0").unwrap());
    let m1 = inner_msg(&session::encrypt(&mut p.alice, b"s1").unwrap());
    assert_eq!(session::decrypt(&mut r, &mut p.bob, &m1).unwrap(), b"s1");
    assert!(session::decrypt(&mut r, &mut p.bob, &tampered(&m0)).is_err());
    assert_eq!(session::decrypt(&mut r, &mut p.bob, &m0).unwrap(), b"s0");
    // Ratchet path: Bob replies (Alice ratchets), then Alice's next message
    // carries a new ratchet key to Bob. Tampering it must roll back Bob's
    // ratchet step as well as the chain advance.
    assert_eq!(send(&mut r, &mut p.bob, &mut p.alice, b"reply"), b"reply");
    let fresh = inner_msg(&session::encrypt(&mut p.alice, b"new chain").unwrap());
    assert!(session::decrypt(&mut r, &mut p.bob, &tampered(&fresh)).is_err());
    assert_eq!(session::decrypt(&mut r, &mut p.bob, &fresh).unwrap(), b"new chain");
}

#[test]
fn group_failed_decrypt_preserves_iteration() {
    let mut r = rng();
    let (own, skdm) = group::create(&mut r);
    let mut sender = own.clone();
    let genuine = group::encrypt(&mut r, &mut sender, b"hello group").unwrap();
    let mut peer = group::process(&skdm).unwrap();
    // Validly signed (so signature passes) but undecryptable: ciphertext
    // is not a whole number of AES blocks. This must not consume iteration 0.
    let signing_private = own.signing_private.unwrap();
    let bad = SenderKeyMessage::new(own.key_id, 0, &[0u8; 15], &signing_private, &[0x44u8; 64]);
    assert!(group::decrypt(&mut peer, &bad.serialized).is_err());
    assert_eq!(group::decrypt(&mut peer, &genuine).unwrap(), b"hello group");
    // Same guarantee on the skipped-key path.
    let g1 = group::encrypt(&mut r, &mut sender, b"g1").unwrap();
    let g2 = group::encrypt(&mut r, &mut sender, b"g2").unwrap();
    assert_eq!(group::decrypt(&mut peer, &g2).unwrap(), b"g2");
    let bad_skipped =
        SenderKeyMessage::new(own.key_id, 1, &[0u8; 15], &signing_private, &[0x45u8; 64]);
    assert!(group::decrypt(&mut peer, &bad_skipped.serialized).is_err());
    assert_eq!(group::decrypt(&mut peer, &g1).unwrap(), b"g1");
}

#[test]
fn chain_key_stepping_is_deterministic() {
    let c0 = session::ChainKey { key: [7u8; 32], index: 0 };
    let c1 = c0.next();
    assert_eq!(c1.index, 1);
    assert_ne!(c1.key, c0.key);
    assert_eq!(c1.key, session::ChainKey { key: [7u8; 32], index: 0 }.next().key);
    let mk0 = c0.message_keys();
    let mk1 = c1.message_keys();
    assert_ne!(mk0.cipher_key, mk1.cipher_key);
    assert_eq!(mk0.cipher_key, c0.message_keys().cipher_key);
    assert_ne!(mk0.cipher_key, mk0.mac_key);
}

#[test]
fn session_record_minimal_and_caps() {
    // No sender chain, no receiver chains, no pending pre-key.
    let minimal = session::SessionState {
        root_key: [1u8; 32],
        local_identity: [2u8; 32],
        remote_identity: [3u8; 32],
        remote_registration_id: 0,
        sender_ratchet_private: [4u8; 32],
        sender_ratchet_public: [5u8; 32],
        sender_chain: None,
        previous_counter: 0,
        receiver_chains: vec![],
        pending_pre_key: None,
    };
    let back = session::SessionState::deserialize(&minimal.serialize()).unwrap();
    assert!(back.sender_chain.is_none());
    assert!(back.pending_pre_key.is_none());
    assert!(back.receiver_chains.is_empty());
    // Encrypting without a sending chain is an error, not a panic.
    let mut m2 = session::SessionState::deserialize(&minimal.serialize()).unwrap();
    assert!(session::encrypt(&mut m2, b"x").is_err());
    // Pending pre-key with no one-time id round-trips as None.
    let mut with_ppk = minimal.clone();
    with_ppk.pending_pre_key = Some(session::PendingPreKey {
        pre_key_id: None,
        signed_pre_key_id: 2,
        base_key: [9u8; 32],
        local_registration_id: 5,
    });
    let back = session::SessionState::deserialize(&with_ppk.serialize()).unwrap();
    assert_eq!(back.pending_pre_key.unwrap().pre_key_id, None);
    // Deserializer caps: >5 receiver chains rejected.
    let mut many = minimal.clone();
    many.receiver_chains = (0..6)
        .map(|i| session::ReceiverChain {
            ratchet_key: [i as u8; 32],
            chain_key: session::ChainKey { key: [1u8; 32], index: 0 },
            skipped: vec![],
        })
        .collect();
    assert!(session::SessionState::deserialize(&many.serialize()).is_err());
    // Exactly 5 accepted.
    many.receiver_chains.truncate(5);
    assert!(session::SessionState::deserialize(&many.serialize()).is_ok());
    // Truncation at any point is an error (never a panic).
    let blob = minimal.serialize();
    for cut in 0..blob.len() {
        assert!(session::SessionState::deserialize(&blob[..cut]).is_err(), "cut {cut}");
    }
}

// ---------------- Sender Keys (groups) ----------------

#[test]
fn sender_chain_key_stepping() {
    let c0 = group::SenderChainKey { iteration: 0, key: [4u8; 32] };
    let c1 = c0.next();
    assert_eq!(c1.iteration, 1);
    assert_ne!(c1.key, c0.key);
    let mk = c0.message_key();
    assert_eq!(mk.iteration, 0);
    // Deterministic: same chain key derives the same message key, next differs.
    assert_eq!(mk.cipher_key, c0.message_key().cipher_key);
    assert_eq!(mk.iv, c0.message_key().iv);
    assert_eq!(c1.message_key().iteration, 1);
    assert_ne!(mk.cipher_key, c1.message_key().cipher_key);
}

#[test]
fn group_create_process_and_key_id_mask() {
    let mut r = rng();
    for _ in 0..8 {
        let (state, skdm) = group::create(&mut r);
        assert_eq!(state.key_id, skdm.key_id);
        assert!(state.key_id < 0x8000_0000, "key id kept in low 31 bits");
        assert_eq!(skdm.iteration, 0);
        assert_eq!(skdm.chain_key.len(), 32);
        assert!(state.signing_private.is_some());
        let peer = group::process(&skdm).unwrap();
        assert!(peer.signing_private.is_none());
        assert_eq!(peer.signing_public, state.signing_public);
    }
    // Distribution messages with wrong chain-key lengths are rejected.
    let (_s, skdm) = group::create(&mut r);
    for len in [0usize, 1, 31, 33, 64] {
        let bad = SenderKeyDistributionMessage {
            key_id: skdm.key_id,
            iteration: 0,
            chain_key: vec![1u8; len],
            signing_key: skdm.signing_key,
        };
        assert!(group::process(&bad).is_err(), "chain len {len}");
    }
}

#[test]
fn group_multi_member_and_long_conversation() {
    let mut r = rng();
    let (own, skdm) = group::create(&mut r);
    let mut sender = own.clone();
    let mut peer_a = group::process(&skdm).unwrap();
    let mut peer_b = group::process(&skdm).unwrap();
    for i in 0..10 {
        let text = format!("announcement {i}");
        let ct = group::encrypt(&mut r, &mut sender, text.as_bytes()).unwrap();
        assert_eq!(group::decrypt(&mut peer_a, &ct).unwrap(), text.as_bytes());
        assert_eq!(group::decrypt(&mut peer_b, &ct).unwrap(), text.as_bytes());
    }
    let big = vec![7u8; 4096];
    let ct = group::encrypt(&mut r, &mut sender, &big).unwrap();
    assert_eq!(group::decrypt(&mut peer_a, &ct).unwrap(), big);
    let ct = group::encrypt(&mut r, &mut sender, b"").unwrap();
    assert_eq!(group::decrypt(&mut peer_a, &ct).unwrap(), b"");
}

#[test]
fn group_tamper_wrong_signer_and_far_jump() {
    let mut r = rng();
    let (own, skdm) = group::create(&mut r);
    let mut sender = own.clone();
    let ct = group::encrypt(&mut r, &mut sender, b"hello").unwrap();
    // Byte-flip inside the signed body breaks signature or decryption.
    let mut bad = ct.clone();
    bad[4] ^= 1;
    let mut peer = group::process(&skdm).unwrap();
    assert!(group::decrypt(&mut peer, &bad).is_err());
    // A peer state built from a different distribution cannot decrypt.
    let (_own2, skdm2) = group::create(&mut r);
    let mut stranger = group::process(&skdm2).unwrap();
    assert!(group::decrypt(&mut stranger, &ct).is_err());
    // Fresh peer still decrypts the genuine message.
    let mut peer2 = group::process(&skdm).unwrap();
    assert_eq!(group::decrypt(&mut peer2, &ct).unwrap(), b"hello");
    // Garbage inputs are errors, not panics.
    assert!(group::decrypt(&mut peer2, &[]).is_err());
    assert!(group::decrypt(&mut peer2, &[0xff; 80]).is_err());
    assert!(group::decrypt(&mut peer2, &ct[..ct.len() / 2]).is_err());
}

#[test]
fn sender_key_record_edges() {
    let mut r = rng();
    let (own, _skdm) = group::create(&mut r);
    // Without private key (peer-shaped state) round-trips.
    let mut peer_shaped = own.clone();
    peer_shaped.signing_private = None;
    let back = group::SenderKeyState::deserialize(&peer_shaped.serialize()).unwrap();
    assert!(back.signing_private.is_none());
    assert_eq!(back.signing_public, own.signing_public);
    // Bad version and every truncation rejected.
    let blob = own.serialize();
    let mut badv = blob.clone();
    badv[0] = 2;
    assert!(group::SenderKeyState::deserialize(&badv).is_err());
    for cut in 0..blob.len() {
        assert!(group::SenderKeyState::deserialize(&blob[..cut]).is_err(), "cut {cut}");
    }
    // Skipped entries round-trip.
    let mut with_skipped = own.clone();
    with_skipped.skipped = vec![(3, [1u8; 16], [2u8; 32]), (4, [3u8; 16], [4u8; 32])];
    let back = group::SenderKeyState::deserialize(&with_skipped.serialize()).unwrap();
    assert_eq!(back.skipped.len(), 2);
    assert_eq!(back.skipped[1].0, 4);
    // Over-cap skipped count rejected by the deserializer.
    let mut over = own.clone();
    over.skipped = (0..2001).map(|i| (i, [0u8; 16], [0u8; 32])).collect();
    assert!(group::SenderKeyState::deserialize(&over.serialize()).is_err());
}
