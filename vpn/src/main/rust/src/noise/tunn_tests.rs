//! Tests for `Tunn` state machine, timers surface, session encap/decap, and `pad_to_x16`.
//! Deterministic apart from ephemeral keys: the Noise handshake between two
//! in-process tunnels must succeed regardless of key values.
#![cfg(test)]
use super::*;
use crate::noise::index_table::IndexTable;
use crate::packet::{Packet, WgKind};
use crate::tun::MtuWatcher;
use crate::x25519;
use bytes::BytesMut;
use std::sync::Arc;
use std::time::Duration;

fn make_tunn_pair() -> (Tunn, Tunn) {
    let a_priv = x25519::StaticSecret::from([1u8; 32]);
    let b_priv = x25519::StaticSecret::from([2u8; 32]);
    let a_pub = x25519::PublicKey::from(&a_priv);
    let b_pub = x25519::PublicKey::from(&b_priv);
    let mk = |priv_: x25519::StaticSecret, peer: x25519::PublicKey| {
        let table = IndexTable::from_os_rng();
        let rl = Arc::new(RateLimiter::new(&x25519::PublicKey::from(&priv_), 100));
        Tunn::new(priv_, peer, None, None, table, rl)
    };
    (mk(a_priv, b_pub), mk(b_priv, a_pub))
}

fn wg_packet_of(kind: WgKind) -> Packet {
    kind.into()
}

#[test]
fn tunn_fresh_state() {
    let (a, _b) = make_tunn_pair();
    assert!(!a.is_expired());
    let (t, tx, rx, loss, rtt) = a.stats();
    assert!(t.is_none());
    assert_eq!((tx, rx), (0, 0));
    assert_eq!(loss, 0.0);
    assert!(rtt.is_none());
    assert_eq!(a.persistent_keepalive(), None);
    assert_eq!(a.preshared_key(), None);
}

#[test]
fn tunn_update_timers_fresh_is_none() {
    let (mut a, _b) = make_tunn_pair();
    assert!(matches!(a.update_timers(), Ok(None)));
}

#[test]
fn tunn_persistent_keepalive_setter() {
    let a_priv = x25519::StaticSecret::from([3u8; 32]);
    let b_pub = x25519::PublicKey::from(&x25519::StaticSecret::from([4u8; 32]));
    let table = IndexTable::from_os_rng();
    let rl = Arc::new(RateLimiter::new(&x25519::PublicKey::from(&a_priv), 100));
    let mut t = Tunn::new(a_priv, b_pub, None, Some(25), table, rl);
    assert_eq!(t.persistent_keepalive(), Some(25));
    t.set_persistent_keepalive(Some(0));
    assert_eq!(t.persistent_keepalive(), None);
    t.set_persistent_keepalive(Some(10));
    assert_eq!(t.persistent_keepalive(), Some(10));
    t.set_persistent_keepalive(None);
    assert_eq!(t.persistent_keepalive(), None);
}

#[test]
fn tunn_preshared_key_roundtrip() {
    let (mut a, _b) = make_tunn_pair();
    a.set_preshared_key(Some([7u8; 32]));
    assert_eq!(a.preshared_key(), Some([7u8; 32]));
    a.set_preshared_key(None);
    assert_eq!(a.preshared_key(), None);
}

#[test]
fn tunn_timer_params_default_ranges() {
    let p = TimerParams::default();
    assert_eq!(*p.keepalive_timeout.start(), Duration::from_secs(10));
    assert_eq!(*p.rekey_timeout.start(), Duration::from_secs(5));
    assert_eq!(*p.rekey_after_time.start(), Duration::from_secs(120));
    let (a, _b) = make_tunn_pair();
    assert_eq!(a.timer_params(), &p);
}

#[test]
fn pad_to_x16_behaviour() {
    let mut mtu = MtuWatcher::new(1280);
    let p = Packet::from_bytes(BytesMut::zeroed(10));
    assert_eq!(pad_to_x16(p, &mut mtu).len(), 16);
    let p = Packet::from_bytes(BytesMut::zeroed(16));
    assert_eq!(pad_to_x16(p, &mut mtu).len(), 16);
    let p = Packet::from_bytes(BytesMut::zeroed(17));
    assert_eq!(pad_to_x16(p, &mut mtu).len(), 32);
    // Content preserved, padding zeroed.
    let mut b = BytesMut::zeroed(10);
    b[0] = 0xAB;
    let out = pad_to_x16(Packet::from_bytes(b), &mut mtu);
    assert_eq!(out.as_bytes()[0], 0xAB);
    assert!(out.as_bytes()[10..].iter().all(|&x| x == 0));
}

#[test]
fn full_handshake_and_data_roundtrip() {
    let (mut a, mut b) = make_tunn_pair();

    // A initiates.
    let init = a.format_handshake_initiation(false).expect("initiation");
    assert_eq!(init.as_bytes().len(), 148);
    // Second call while in progress returns None.
    assert!(a.format_handshake_initiation(false).is_none());

    // B consumes init -> handshake response to network.
    let wg = Packet::from_bytes(BytesMut::from(init.as_bytes()))
        .try_into_wg()
        .unwrap();
    let resp = match b.handle_incoming_packet(wg) {
        TunnResult::WriteToNetwork(k) => k,
        other => panic!("expected WriteToNetwork, got {other:?}"),
    };
    let resp_pkt: Packet = wg_packet_of(resp);
    assert_eq!(resp_pkt.as_bytes().len(), 92);

    // A consumes response -> keepalive data packet to network.
    let wg = resp_pkt.try_into_wg().unwrap();
    let keepalive = match a.handle_incoming_packet(wg) {
        TunnResult::WriteToNetwork(k) => k,
        other => panic!("expected WriteToNetwork keepalive, got {other:?}"),
    };
    // B consumes keepalive data -> empty tunnel write.
    let wg = wg_packet_of(keepalive).try_into_wg().unwrap();
    match b.handle_incoming_packet(wg) {
        TunnResult::WriteToTunnel(p) => assert!(p.is_empty()),
        other => panic!("expected empty WriteToTunnel, got {other:?}"),
    }

    // Now A encapsulates an IP payload; B decapsulates it identically.
    let payload = Packet::from_bytes(BytesMut::from(&b"hello wireguard"[..]));
    let mut mtu = MtuWatcher::new(1280);
    let enc = a.handle_outgoing_packet(payload, Some(&mut mtu)).expect("encapsulated");
    let wg = wg_packet_of(enc).try_into_wg().unwrap();
    match b.handle_incoming_packet(wg) {
        TunnResult::WriteToTunnel(p) => {
            // pad_to_x16 padded 15 -> 16 bytes.
            assert_eq!(&p.as_bytes()[..15], b"hello wireguard");
            assert_eq!(p.as_bytes().len(), 16);
        }
        other => panic!("expected WriteToTunnel, got {other:?}"),
    }

    // Stats now report traffic + a handshake time on A.
    let (t, tx, rx, _loss, rtt) = a.stats();
    assert!(t.is_some());
    assert!(tx > 0 && rx > 0);
    assert!(rtt.is_some());
}

#[test]
fn tunn_encapsulate_without_session_queues_and_initiates() {
    let (mut a, _b) = make_tunn_pair();
    let payload = Packet::from_bytes(BytesMut::from(&[1u8, 2, 3][..]));
    let out = a.handle_outgoing_packet(payload, None).expect("handshake initiation");
    // No session yet, so we get a handshake initiation (148 bytes).
    let p: Packet = wg_packet_of(out);
    assert_eq!(p.as_bytes().len(), 148);
    assert_eq!(p.as_bytes()[0], 1);
}

#[test]
fn tunn_reset_clears_sessions() {
    let (mut a, mut b) = make_tunn_pair();
    let init = a.format_handshake_initiation(false).unwrap();
    let wg = Packet::from_bytes(BytesMut::from(init.as_bytes())).try_into_wg().unwrap();
    let _ = b.handle_incoming_packet(wg);
    a.reset();
    assert!(!a.is_expired());
    // After reset, encapsulate falls back to handshake initiation again.
    let out = a
        .handle_outgoing_packet(Packet::from_bytes(BytesMut::from(&[9u8][..])), None)
        .expect("initiation after reset");
    let p: Packet = wg_packet_of(out);
    assert_eq!(p.as_bytes()[0], 1);
}
