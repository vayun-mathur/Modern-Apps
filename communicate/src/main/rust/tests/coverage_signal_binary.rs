//! Coverage tests: sealed-sender stub, Kyber replay guard, WA binary nodes.
//! Pure/deterministic, synthetic inputs only.

use communicate_signal::signal::{
    mark_kyber_pre_key_used, sealed_sender_decrypt, sealed_sender_decrypt_any,
    sealed_sender_encrypt,
};
use communicate_signal::wa_binary::{decode_node, encode_node, Node};

fn round_trip(node: &Node) -> Node {
    let encoded = encode_node(node);
    assert_eq!(encoded[0], 0);
    decode_node(&encoded[1..]).expect("decode")
}

// ---------------- sealed sender stub ----------------

#[test]
fn sealed_sender_round_trip_with_hint() {
    for aci in ["alice", "bob@example.org", ""] {
        for plain in [b"".as_slice(), b"x".as_slice(), b"a longer sealed payload 123".as_slice()] {
            let ct = sealed_sender_encrypt(plain, aci);
            assert_eq!(ct[0], 1, "version byte");
            assert_eq!(sealed_sender_decrypt(&ct, Some(aci)).unwrap(), plain);
        }
    }
    // Empty-ACI ciphertext is what decrypt_any handles.
    let ct = sealed_sender_encrypt(b"anon", "");
    assert_eq!(sealed_sender_decrypt_any(&ct).unwrap(), b"anon");
    assert_eq!(sealed_sender_decrypt(&ct, Some("")).unwrap(), b"anon");
}

#[test]
fn sealed_sender_wrong_recipient_and_tamper() {
    let ct = sealed_sender_encrypt(b"for alice only", "alice");
    assert!(sealed_sender_decrypt(&ct, Some("bob")).is_err(), "wrong key/AAD");
    assert!(sealed_sender_decrypt_any(&ct).is_err(), "anon key cannot open alice mail");
    // Two encryptions differ (random IV) yet both decrypt.
    let ct2 = sealed_sender_encrypt(b"for alice only", "alice");
    assert_ne!(ct, ct2);
    assert_eq!(sealed_sender_decrypt(&ct2, Some("alice")).unwrap(), b"for alice only");
    // Tamper: version, IV, ciphertext and tag flips all fail.
    for idx in [0usize, 3, 15, 20] {
        let mut bad = ct.clone();
        if idx < bad.len() {
            bad[idx] ^= 1;
            assert!(sealed_sender_decrypt(&bad, Some("alice")).is_err(), "flip at {idx}");
        }
    }
    let n = ct.len();
    let mut bad_tag = ct.clone();
    bad_tag[n - 1] ^= 1;
    assert!(sealed_sender_decrypt(&bad_tag, Some("alice")).is_err());
    // Every strict prefix is an error, never a panic.
    for cut in 0..ct.len() {
        assert!(sealed_sender_decrypt(&ct[..cut], Some("alice")).is_err(), "cut {cut}");
    }
    let mut bad_version = ct.clone();
    bad_version[0] = 2;
    assert!(sealed_sender_decrypt(&bad_version, Some("alice")).is_err());
}

// ---------------- Kyber replay guard ----------------

#[test]
fn kyber_replay_guard_keys_and_pairs() {
    // Unique ids per assertion group: the guard is process-global state.
    assert!(mark_kyber_pre_key_used(91_001, 7, b"base-a"));
    assert!(mark_kyber_pre_key_used(91_001, 7, b"base-b"), "new base key ok");
    assert!(!mark_kyber_pre_key_used(91_001, 7, b"base-a"), "replay rejected");
    // Same base key under a different (kyber, signed-ec) pair is independent.
    assert!(mark_kyber_pre_key_used(91_002, 7, b"base-a"));
    assert!(mark_kyber_pre_key_used(91_001, 8, b"base-a"));
    // Empty base key is tracked like any other.
    assert!(mark_kyber_pre_key_used(91_003, 1, b""));
    assert!(!mark_kyber_pre_key_used(91_003, 1, b""));
}

// ---------------- WA binary node codec ----------------

#[test]
fn node_many_children_and_deep_nesting() {
    // >255 children forces the LIST_16 list header.
    let mut parent = Node::new("message");
    for i in 0..300 {
        parent.content.push(Node::new("enc").attr("v", &i.to_string()));
    }
    assert_eq!(round_trip(&parent), parent);
    // Deep nesting round-trips.
    let mut deep = Node::new("leaf").attr("depth", "end");
    for i in 0..40 {
        let mut wrap = Node::new("iq").attr("level", &i.to_string());
        wrap.content.push(deep);
        deep = wrap;
    }
    assert_eq!(round_trip(&deep), deep);
    // Many attributes (>127 pairs stays LIST_8-sized list; order deterministic).
    let mut wide = Node::new("iq");
    for i in 0..60 {
        wide.attrs.insert(format!("k{i:02}"), format!("value-{i}"));
    }
    assert_eq!(round_trip(&wide), wide);
    assert_eq!(encode_node(&wide), encode_node(&wide.clone()), "encoding is deterministic");
}

#[test]
fn node_payload_length_classes() {
    // 256..=1MiB-1 payloads use BINARY_20; spot-check around the boundary.
    for size in [255usize, 256, 257, 65_535, 65_536] {
        let mut node = Node::new("enc");
        node.data = Some((0..size).map(|i| (i % 253) as u8).collect());
        let back = round_trip(&node);
        assert_eq!(back.data.as_ref().map(Vec::len), Some(size), "size {size}");
        assert_eq!(back.data, node.data);
    }
    // Empty payload is preserved as Some(empty), distinct from no payload.
    let mut empty_data = Node::new("enc");
    empty_data.data = Some(vec![]);
    let back = round_trip(&empty_data);
    assert_eq!(back.data, Some(vec![]));
    // Text content in the payload slot decodes as bytes.
    let mut texty = Node::new("body");
    texty.data = Some(b"plain text body".to_vec());
    assert_eq!(round_trip(&texty), texty);
}

#[test]
fn node_packed_string_boundaries() {
    // 127 chars is the longest packable value; it must survive packed.
    let at_max = "7".repeat(127);
    let node = Node::new("iq").attr("zzz_raw_key", &at_max);
    assert_eq!(round_trip(&node).attrs["zzz_raw_key"], at_max);
    let over = "7".repeat(128);
    let node = Node::new("iq").attr("zzz_raw_key", &over);
    assert_eq!(round_trip(&node).attrs["zzz_raw_key"], over);
    // Mixed nibble alphabet, even/odd lengths, and hex boundaries.
    for v in ["0", "00", "1-2", "12.34", "-", ".", "ABCDEF0", "F", "FF", "0123456789ABCDEF"] {
        let node = Node::new("iq").attr("zzz_raw_key", v);
        assert_eq!(round_trip(&node).attrs["zzz_raw_key"], v, "value {v}");
    }
    // Strings that merely look numeric with other chars fall back to raw.
    for v in ["12a", "1 2", "+123", "12,3", "abc-def"] {
        let node = Node::new("iq").attr("zzz_raw_key", v);
        assert_eq!(round_trip(&node).attrs["zzz_raw_key"], v, "value {v}");
    }
    // Non-ASCII text round-trips as raw bytes.
    let node = Node::new("iq").attr("zzz_raw_key", "héllo wörld ✓");
    assert_eq!(round_trip(&node).attrs["zzz_raw_key"], "héllo wörld ✓");
}

#[test]
fn node_jid_variants() {
    for jid in [
        "15551234567@s.whatsapp.net",
        "user@g.us",
        "123-456@g.us",
        "@s.whatsapp.net",
        "user@lid",
        "plainstring-no-at",
    ] {
        let node = Node::new("iq").attr("to", jid);
        assert_eq!(round_trip(&node).attrs["to"], jid, "jid {jid}");
    }
    // A dotted non-numeric user part must round-trip losslessly: only the
    // canonical numeric `<user>.<agent>:<device>` form uses AD_JID.
    for jid in [
        "a.b.c@s.whatsapp.net",
        "a.b@s.whatsapp.net",
        "a.b.c@g.us",
        "user.1@s.whatsapp.net",
        "user:2@s.whatsapp.net",
        "user.0:0@s.whatsapp.net",
        "user.256:1@s.whatsapp.net",
        "user:x@s.whatsapp.net",
        "15551234567.1:2@s.whatsapp.net",
        "15551234567.0:3@s.whatsapp.net",
    ] {
        let node = Node::new("iq").attr("to", jid);
        assert_eq!(round_trip(&node).attrs["to"], jid, "jid {jid}");
    }
    // Attribute keys are never JID-encoded even when the value is.
    let node = Node::new("iq").attr("jid", "x@s.whatsapp.net");
    assert_eq!(round_trip(&node), node);
    // Tag "0" is the wire empty-node marker: LIST_8 followed by LIST_EMPTY,
    // and it must decode back to the same node.
    let encoded = encode_node(&Node::new("0"));
    assert_eq!(encoded, vec![0u8, 248, 0]);
    assert_eq!(round_trip(&Node::new("0")), Node::new("0"));
    // An empty node nested as a child also round-trips.
    let mut parent = Node::new("iq");
    parent.content.push(Node::new("0"));
    parent.content.push(Node::new("enc"));
    assert_eq!(round_trip(&parent), parent);
}

#[test]
fn node_decoder_rejects_garbage_robustly() {
    assert!(decode_node(&[]).is_err());
    assert!(decode_node(&[0]).is_err(), "list tag 0 unknown at node level");
    assert!(decode_node(&[248]).is_err(), "LIST_8 without size");
    assert!(decode_node(&[248, 1]).is_err(), "LIST_8 size without tag");
    assert!(decode_node(&[249, 0]).is_err(), "truncated LIST_16");
    // Every prefix of a valid encoding: Ok or Err, never panic/hang.
    let node = Node::new("message")
        .attr("to", "15551234567.1:2@s.whatsapp.net")
        .attr("id", "ABC123");
    let mut with_child = node.clone();
    let mut child = Node::new("enc");
    child.data = Some(vec![1, 2, 3, 4, 5]);
    with_child.content.push(child);
    let encoded = encode_node(&with_child);
    for cut in 1..encoded.len() {
        let _ = decode_node(&encoded[1..cut]);
    }
    // Random-ish garbage sweeps: never panic.
    let mut state = 0x1234_5678u32;
    for _ in 0..200 {
        let mut buf = Vec::new();
        for _ in 0..24 {
            state = state.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            buf.push((state >> 24) as u8);
        }
        let _ = decode_node(&buf);
    }
}
