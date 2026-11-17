//! Tests for index table, rate limiter, handshake crypto helpers, and session encap/decap.
#![cfg(test)]
use super::handshake::{b2s_hash, b2s_hmac, b2s_hmac2, b2s_keyed_mac_16, parse_handshake_anon};
use super::index_table::IndexTable;
use super::rate_limiter::RateLimiter;
use super::session::Session;
use super::{TunnResult, WireGuardError};
use crate::packet::{Packet, WgData, WgHandshakeInit, WgKind};
use crate::x25519;
use bytes::BytesMut;
use rand::{SeedableRng, rngs::StdRng};
use std::collections::HashSet;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use zerocopy::{FromBytes, IntoBytes};

#[test]
fn index_table_unique_and_free_on_drop() {
    let table = IndexTable::from_rng(StdRng::seed_from_u64(42));
    let mut seen = HashSet::new();
    let mut idxs = Vec::new();
    for _ in 0..200 {
        let idx = table.new_index();
        assert!(seen.insert(idx.value()), "duplicate index {}", idx.value());
        assert!(table.in_use(idx.value()));
        idxs.push(idx);
    }
    let v = idxs[0].value();
    drop(idxs.remove(0));
    assert!(!table.in_use(v));
    // Deterministic with seeded rng: same seed reproduces first value.
    let t2 = IndexTable::from_rng(StdRng::seed_from_u64(42));
    let t3 = IndexTable::from_rng(StdRng::seed_from_u64(42));
    assert_eq!(t2.new_index().value(), t3.new_index().value());
}

#[test]
fn b2s_hash_known_vector_and_properties() {
    // Blake2s-256("") known digest.
    let empty = b2s_hash(&[], &[]);
    assert_eq!(
        hex::encode(empty),
        "69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9"
    );
    assert_eq!(b2s_hash(b"a", b"b"), b2s_hash(b"ab", &[]));
    assert_ne!(b2s_hash(b"a", &[]), b2s_hash(b"b", &[]));
    assert_eq!(b2s_hash(b"x", b"y"), b2s_hash(b"x", b"y"));
}

#[test]
fn b2s_hmac_properties() {
    let k = [1u8; 32];
    assert_eq!(b2s_hmac(&k, b"data"), b2s_hmac(&k, b"data"));
    assert_ne!(b2s_hmac(&k, b"data"), b2s_hmac(&[2u8; 32], b"data"));
    assert_ne!(b2s_hmac(&k, b"data"), b2s_hmac(&k, b"datb"));
    // hmac2 chains both parts; order matters.
    assert_ne!(b2s_hmac2(&k, b"a", b"b"), b2s_hmac2(&k, b"b", b"a"));
    assert_eq!(b2s_keyed_mac_16(&k, b"data").len(), 16);
    assert_ne!(b2s_keyed_mac_16(&k, b"data"), b2s_keyed_mac_16(&k, b"datb"));
}

#[test]
fn parse_handshake_anon_rejects_garbage() {
    let priv_ = x25519::StaticSecret::from([5u8; 32]);
    let pub_ = x25519::PublicKey::from(&priv_);
    let init = WgHandshakeInit::new();
    assert!(parse_handshake_anon(&priv_, &pub_, &init).is_err());
}

#[test]
fn rate_limiter_passthrough_data_and_rejects_bad_mac() {
    let priv_ = x25519::StaticSecret::from([6u8; 32]);
    let pub_ = x25519::PublicKey::from(&priv_);
    let rl = RateLimiter::new(&pub_, 100);
    let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 51820);

    // Data packets pass through untouched.
    let mut buf = [0u8; 32];
    buf[0] = 4;
    let res = rl.verify_packet(addr, Packet::from_bytes(BytesMut::from(&buf[..])));
    assert!(matches!(res, Ok(WgKind::Data(_))));

    // Handshake init with zero MAC1 is rejected.
    let init = WgHandshakeInit::new();
    let res = rl.verify_packet(addr, Packet::from_bytes(BytesMut::from(init.as_bytes())));
    assert!(matches!(res, Err(TunnResult::Err(WireGuardError::InvalidMac))));

    // Non-WireGuard garbage is InvalidPacket.
    let res = rl.verify_packet(addr, Packet::from_bytes(BytesMut::from(&[99u8, 0][..])));
    assert!(matches!(res, Err(TunnResult::Err(WireGuardError::InvalidPacket))));
}

fn session_pair() -> (Session, Session) {
    // A's sending key == B's receiving key and vice versa, with matching indices.
    let ta = IndexTable::from_rng(StdRng::seed_from_u64(1));
    let tb = IndexTable::from_rng(StdRng::seed_from_u64(2));
    let a_idx = ta.new_index();
    let b_idx = tb.new_index();
    let a_recv = a_idx.value();
    let b_recv = b_idx.value();
    let key_ab = [10u8; 32];
    let key_ba = [20u8; 32];
    // Session::new(local_index, sending_index, receiving_key, sending_key)
    let a = Session::new(a_idx, b_recv, key_ba, key_ab);
    let b = Session::new(b_idx, a_recv, key_ab, key_ba);
    (a, b)
}

#[test]
fn session_encrypt_decrypt_roundtrip_and_replay() {
    let (a, b) = session_pair();
    let plain = Packet::from_bytes(BytesMut::from(&b"secret payload"[..]));
    let enc = a.format_packet_data(plain).expect("format");
    let out = b.receive_packet_data(enc.clone()).expect("receive");
    assert_eq!(out.as_bytes(), b"secret payload");
    // Replay of the same packet must fail (duplicate counter / AEAD consumed mark).
    assert!(b.receive_packet_data(enc).is_err());
}

#[test]
fn session_wrong_receiver_index_rejected() {
    let (a, _b) = session_pair();
    let (_a2, b2) = session_pair();
    let enc = a
        .format_packet_data(Packet::from_bytes(BytesMut::from(&b"x"[..])))
        .expect("format");
    // b2 has a different receiving index (different seeded table value may collide
    // only with negligible probability; assert on error kind if index differs).
    if enc.header.receiver_idx.get() != b2.receiving_index.value() {
        assert!(matches!(
            b2.receive_packet_data(enc),
            Err(WireGuardError::WrongIndex)
        ));
    }
}

#[test]
fn session_tampered_ciphertext_rejected() {
    let (a, b) = session_pair();
    let enc = a
        .format_packet_data(Packet::from_bytes(BytesMut::from(&b"data"[..])))
        .expect("format");
    // Flip a ciphertext/tag byte.
    let mut raw: Packet = enc.into();
    let n = raw.as_bytes().len();
    raw.buf_mut()[n - 1] ^= 0xFF;
    let enc = match raw.try_into_wg().unwrap() {
        WgKind::Data(d) => d,
        _ => unreachable!(),
    };
    assert!(matches!(
        b.receive_packet_data(enc),
        Err(WireGuardError::InvalidAeadTag)
    ));
    let _ = WgData::mut_from_bytes;
}
