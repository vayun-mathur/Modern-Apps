//! Extra coverage: rate limiter under-load/cookie flow, Tunn error paths,
//! queued packets, timer-driven handshake/keepalive/expiry branches.
#![cfg(test)]
use super::handshake::{b2s_hash, b2s_keyed_mac_16, b2s_keyed_mac_16_2, LABEL_COOKIE, LABEL_MAC1};
use super::index_table::IndexTable;
use super::rate_limiter::RateLimiter;
use super::timers::TimerName;
use super::{TimerParams, Tunn, TunnResult, WireGuardError};
use crate::packet::{Packet, WgCookieReply, WgHandshakeBase, WgHandshakeInit, WgKind};
use crate::tun::MtuWatcher;
use crate::x25519;
use aead::{Aead, Payload};
use bytes::BytesMut;
use chacha20poly1305::XChaCha20Poly1305;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;
use zerocopy::IntoBytes;

fn tunn_pair() -> (Tunn, Tunn) {
    let a_priv = x25519::StaticSecret::from([11u8; 32]);
    let b_priv = x25519::StaticSecret::from([12u8; 32]);
    let a_pub = x25519::PublicKey::from(&a_priv);
    let b_pub = x25519::PublicKey::from(&b_priv);
    let mk = |p: x25519::StaticSecret, peer: x25519::PublicKey| {
        let rl = Arc::new(RateLimiter::new(&x25519::PublicKey::from(&p), 100));
        Tunn::new(p, peer, None, None, IndexTable::from_os_rng(), rl)
    };
    (mk(a_priv, b_pub), mk(b_priv, a_pub))
}
fn establish(a: &mut Tunn, b: &mut Tunn) {
    let init = a.format_handshake_initiation(false).unwrap();
    let wg = Packet::from_bytes(BytesMut::from(init.as_bytes())).try_into_wg().unwrap();
    let resp = match b.handle_incoming_packet(wg) {
        TunnResult::WriteToNetwork(k) => k,
        o => panic!("resp {o:?}"),
    };
    let resp_pkt: Packet = resp.into();
    let wg = resp_pkt.try_into_wg().unwrap();
    let ka = match a.handle_incoming_packet(wg) {
        TunnResult::WriteToNetwork(k) => k,
        o => panic!("ka {o:?}"),
    };
    let ka_pkt: Packet = ka.into();
    let wg = ka_pkt.try_into_wg().unwrap();
    match b.handle_incoming_packet(wg) {
        TunnResult::WriteToTunnel(p) => assert!(p.is_empty()),
        o => panic!("ka tunnel {o:?}"),
    }
}
fn valid_init(pubkey: &x25519::PublicKey) -> WgHandshakeInit {
    let mut init = WgHandshakeInit::new();
    init.sender_idx.set(77);
    let mac1_key = b2s_hash(LABEL_MAC1, pubkey.as_bytes());
    *init.mac1_mut() = b2s_keyed_mac_16(&mac1_key, init.until_mac1());
    init
}
fn decrypt_cookie(pubkey: &x25519::PublicKey, reply: &WgCookieReply, mac1: &[u8; 16]) -> [u8; 16] {
    let key = b2s_hash(LABEL_COOKIE, pubkey.as_bytes());
    let cipher = <XChaCha20Poly1305 as aead::KeyInit>::new_from_slice(&key).unwrap();
    let pt = cipher
        .decrypt(&reply.nonce.into(), Payload { aad: mac1, msg: reply.encrypted_cookie.as_bytes() })
        .unwrap();
    pt.try_into().unwrap()
}

#[test]
fn rate_limiter_valid_mac_not_under_load_and_v6() {
    let priv_ = x25519::StaticSecret::from([21u8; 32]);
    let pub_ = x25519::PublicKey::from(&priv_);
    let rl = RateLimiter::new(&pub_, 100);
    rl.try_reset_count();
    let init = valid_init(&pub_);
    let addr6 = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 51820);
    let res = rl.verify_packet(addr6, Packet::from_bytes(BytesMut::from(init.as_bytes())));
    assert!(matches!(res, Ok(WgKind::HandshakeInit(_))));
    // HandshakeResp with valid mac1 also passes.
    let mut resp = crate::packet::WgHandshakeResp::new(5, 6, [1u8; 32]);
    let mac1_key = b2s_hash(LABEL_MAC1, pub_.as_bytes());
    *resp.mac1_mut() = b2s_keyed_mac_16(&mac1_key, resp.until_mac1());
    let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 1);
    let res = rl.verify_packet(addr, Packet::from_bytes(BytesMut::from(resp.as_bytes())));
    assert!(matches!(res, Ok(WgKind::HandshakeResp(_))));
}

#[test]
fn rate_limiter_under_load_cookie_challenge_then_accept() {
    let priv_ = x25519::StaticSecret::from([22u8; 32]);
    let pub_ = x25519::PublicKey::from(&priv_);
    let rl = RateLimiter::new(&pub_, 0); // every packet is "under load"
    let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4)), 9999);
    let init = valid_init(&pub_);
    let mac1 = *init.mac1();
    // No valid mac2 yet -> cookie reply challenge.
    let err = rl.verify_packet(addr, Packet::from_bytes(BytesMut::from(init.as_bytes()))).unwrap_err();
    let pkt = match err {
        TunnResult::WriteToNetwork(k) => {
            let p: Packet = k.into();
            p
        }
        o => panic!("expected cookie challenge, got {o:?}"),
    };
    assert_eq!(pkt.len(), WgCookieReply::LEN);
    let reply = match pkt.try_into_wg().unwrap() {
        WgKind::CookieReply(r) => r,
        o => panic!("expected CookieReply, got {o:?}"),
    };
    let cookie = decrypt_cookie(&pub_, &reply, &mac1);
    // Retry with correct mac2 -> accepted even under load.
    let mut init2 = valid_init(&pub_);
    assert_eq!(*init2.mac1(), mac1);
    *init2.mac2_mut() = b2s_keyed_mac_16_2(&cookie, init2.until_mac1(), init2.mac1());
    let res = rl.verify_packet(addr, Packet::from_bytes(BytesMut::from(init2.as_bytes())));
    assert!(matches!(res, Ok(WgKind::HandshakeInit(_))));
    // A second challenge has a different nonce (nonce counter advances).
    let err2 = rl.verify_packet(addr, Packet::from_bytes(BytesMut::from(valid_init(&pub_).as_bytes()))).unwrap_err();
    let pkt2: Packet = match err2 { TunnResult::WriteToNetwork(k) => k.into(), o => panic!("{o:?}") };
    let reply2 = match pkt2.try_into_wg().unwrap() { WgKind::CookieReply(r) => r, o => panic!("{o:?}") };
    assert_ne!(reply.nonce, reply2.nonce);
}

#[test]
fn tunn_error_paths_and_queued_packets() {
    let (mut a, mut b) = tunn_pair();
    // Data for unknown session index -> NoCurrentSession.
    let mut data = vec![0u8; 32];
    data[0] = 4;
    data[4..8].copy_from_slice(&12345u32.to_le_bytes());
    let wg = Packet::from_bytes(BytesMut::from(&data[..])).try_into_wg().unwrap();
    assert!(matches!(a.handle_incoming_packet(wg), TunnResult::Err(WireGuardError::NoCurrentSession)));
    // Cookie reply with no outstanding handshake -> UnexpectedPacket.
    let cookie = WgCookieReply::new();
    let wg = Packet::from_bytes(BytesMut::from(cookie.as_bytes())).try_into_wg().unwrap();
    assert!(matches!(a.handle_incoming_packet(wg), TunnResult::Err(WireGuardError::UnexpectedPacket)));
    // Queue a packet before the session exists, then drain after handshake.
    let payload = Packet::from_bytes(BytesMut::from(&b"queued-data!"[..]));
    let init = a.handle_outgoing_packet(payload, None).expect("initiation");
    // Drive the handshake manually from that initiation.
    let init_pkt: Packet = init.into();
    assert_eq!(init_pkt.as_bytes()[0], 1);
    let wg = init_pkt.try_into_wg().unwrap();
    let resp = match b.handle_incoming_packet(wg) { TunnResult::WriteToNetwork(k) => k, o => panic!("{o:?}") };
    let resp_pkt: Packet = resp.into();
    let wg = resp_pkt.try_into_wg().unwrap();
    let _ka = match a.handle_incoming_packet(wg) { TunnResult::WriteToNetwork(k) => k, o => panic!("{o:?}") };
    let mut mtu = MtuWatcher::new(1280);
    let drained = a.get_queued_packets(&mut mtu);
    assert_eq!(drained.len(), 1);
    assert!(matches!(drained[0], WgKind::Data(_)));
    // set_static_private clears sessions; encapsulate queues + initiates again.
    let new_priv = x25519::StaticSecret::from([13u8; 32]);
    let new_pub = x25519::PublicKey::from(&new_priv);
    a.set_static_private(new_priv, new_pub, Arc::new(RateLimiter::new(&new_pub, 100)));
    let (t, _tx, _rx, _loss, _rtt) = a.stats();
    assert!(t.is_none());
}

#[test]
fn tunn_replay_init_and_wrong_peer_rejected() {
    let (mut a, mut b) = tunn_pair();
    let init = a.format_handshake_initiation(false).unwrap();
    let raw = init.as_bytes().to_vec();
    let wg = Packet::from_bytes(BytesMut::from(&raw[..])).try_into_wg().unwrap();
    assert!(matches!(b.handle_incoming_packet(wg), TunnResult::WriteToNetwork(_)));
    // Replay of the identical initiation: timestamp not after last -> rejected.
    let wg = Packet::from_bytes(BytesMut::from(&raw[..])).try_into_wg().unwrap();
    assert!(matches!(
        b.handle_incoming_packet(wg),
        TunnResult::Err(WireGuardError::WrongTai64nTimestamp)
    ));
    // Force-resend while in progress returns a packet again.
    assert!(a.format_handshake_initiation(true).is_some());
}

#[test]
fn tunn_timers_keepalive_and_expiry_branches() {
    // Persistent keepalive is due immediately on a fresh tunnel.
    let priv_ = x25519::StaticSecret::from([31u8; 32]);
    let peer = x25519::PublicKey::from(&x25519::StaticSecret::from([32u8; 32]));
    let mk = |ka: Option<u16>| {
        Tunn::new(
            x25519::StaticSecret::from([31u8; 32]),
            peer,
            None,
            ka,
            IndexTable::from_os_rng(),
            Arc::new(RateLimiter::new(&x25519::PublicKey::from(&priv_), 100)),
        )
    };
    let mut t = mk(Some(25));
    match t.update_timers() {
        Ok(Some(WgKind::HandshakeInit(_))) => {}
        o => panic!("expected handshake initiation from keepalive-due, got {o:?}"),
    }
    // Long-idle session expiry: advance the tunnel clock far past REJECT_AFTER*3.
    let (mut a, mut b) = tunn_pair();
    establish(&mut a, &mut b);
    a.timers[TimerName::TimeCurrent] = Duration::from_secs(10_000);
    assert!(matches!(a.update_timers(), Err(WireGuardError::ConnectionExpired)));
    assert!(a.is_expired());
    // Handshake attempt expiry: initiation in flight, clock far ahead.
    let (mut c, _d) = tunn_pair();
    let _ = c.format_handshake_initiation(false).unwrap();
    c.timers[TimerName::TimeCurrent] = Duration::from_secs(10_000);
    assert!(matches!(c.update_timers(), Err(WireGuardError::ConnectionExpired)));
}

#[test]
fn tunn_timers_session_prune_and_rekey_paths() {
    let (mut a, mut b) = tunn_pair();
    establish(&mut a, &mut b);
    // Custom params: zero rekey timeout so an in-flight rekey fires immediately.
    a.dangerously_set_timer_params(TimerParams {
        keepalive_timeout: Duration::ZERO..=Duration::ZERO,
        new_handshake_timeout: Duration::ZERO..=Duration::ZERO,
        rekey_timeout: Duration::ZERO..=Duration::ZERO,
        rekey_after_time: Duration::from_secs(120)..=Duration::from_secs(120),
    });
    // Prune: session established long ago relative to advanced clock, but keep
    // TimeSessionEstablished current so the connection itself is not expired.
    let now = Duration::from_secs(500);
    a.timers[TimerName::TimeCurrent] = now;
    a.timers[TimerName::TimeSessionEstablished] = now;
    for t in a.timers.session_timers.iter_mut() {
        *t = Duration::ZERO;
    }
    let _ = a.update_timers();
    let (t_since, _tx, _rx, _loss, _rtt) = a.stats();
    assert!(t_since.is_none(), "sessions older than REJECT_AFTER_TIME are pruned");
}

#[test]
fn tunn_stats_loss_and_timer_params_roundtrip() {
    let (mut a, mut b) = tunn_pair();
    establish(&mut a, &mut b);
    let custom = TimerParams {
        keepalive_timeout: Duration::from_secs(3)..=Duration::from_secs(4),
        new_handshake_timeout: Duration::from_secs(5)..=Duration::from_secs(6),
        rekey_timeout: Duration::from_secs(7)..=Duration::from_secs(8),
        rekey_after_time: Duration::from_secs(9)..=Duration::from_secs(10),
    };
    a.dangerously_set_timer_params(custom.clone());
    assert_eq!(a.timer_params(), &custom);
    let (_t, tx, rx, loss, _rtt) = a.stats();
    assert!(tx > 0 && rx > 0);
    assert!((0.0..=1.0).contains(&loss));
    // Debug formats for results.
    assert!(format!("{:?}", TunnResult::Done).contains("Done"));
    assert!(format!("{:?}", TunnResult::Err(WireGuardError::InvalidMac)).contains("InvalidMac"));
}
