//! Tests for WireGuard packet framing (`Packet::try_into_wg`, header builders).
#![cfg(test)]
use super::*;
use bytes::BytesMut;
use zerocopy::IntoBytes;

fn pkt(bytes: &[u8]) -> Packet {
    Packet::from_bytes(BytesMut::from(bytes))
}

#[test]
fn wg_rejects_empty_and_tiny() {
    assert!(pkt(&[]).try_into_wg().is_err());
    assert!(pkt(&[1, 0, 0]).try_into_wg().is_err());
}

#[test]
fn wg_rejects_unknown_type() {
    let mut b = vec![0u8; 148];
    b[0] = 99;
    assert!(pkt(&b).try_into_wg().is_err());
    b[0] = 0;
    assert!(pkt(&b).try_into_wg().is_err());
}

#[test]
fn wg_rejects_wrong_size_for_type() {
    // HandshakeInit type but truncated / overlong.
    let mut b = vec![0u8; 147];
    b[0] = 1;
    assert!(pkt(&b).try_into_wg().is_err());
    let mut b = vec![0u8; 149];
    b[0] = 1;
    assert!(pkt(&b).try_into_wg().is_err());
    // HandshakeResp / CookieReply wrong sizes.
    let mut b = vec![0u8; 91];
    b[0] = 2;
    assert!(pkt(&b).try_into_wg().is_err());
    let mut b = vec![0u8; 63];
    b[0] = 3;
    assert!(pkt(&b).try_into_wg().is_err());
    // Data below overhead (header + tag = 32).
    let mut b = vec![0u8; 31];
    b[0] = 4;
    assert!(pkt(&b).try_into_wg().is_err());
}

#[test]
fn wg_handshake_init_roundtrip() {
    let mut init = WgHandshakeInit::new();
    init.sender_idx.set(0xAABB_CCDD);
    init.unencrypted_ephemeral = [7u8; 32];
    let p = pkt(init.as_bytes()).try_into_wg().unwrap();
    assert!(matches!(p, WgKind::HandshakeInit(_)));
    if let WgKind::HandshakeInit(h) = p {
        assert_eq!(h.sender_idx(), 0xAABB_CCDD);
        assert_eq!(h.unencrypted_ephemeral, [7u8; 32]);
        assert_eq!(h.as_bytes().len(), WgHandshakeInit::LEN);
        assert_eq!(h.until_mac1().len(), WgHandshakeInit::MAC1_OFF);
        assert_eq!(h.until_mac2().len(), WgHandshakeInit::MAC2_OFF);
        assert!(h.until_mac1().len() < h.until_mac2().len());
    }
}

#[test]
fn wg_handshake_resp_roundtrip() {
    let resp = WgHandshakeResp::new(11, 22, [3u8; 32]);
    let p = pkt(resp.as_bytes()).try_into_wg().unwrap();
    assert!(matches!(p, WgKind::HandshakeResp(_)));
    if let WgKind::HandshakeResp(h) = p {
        assert_eq!(h.sender_idx(), 11);
        assert_eq!(h.receiver_idx.get(), 22);
        assert_eq!(h.unencrypted_ephemeral, [3u8; 32]);
        assert_eq!(h.as_bytes().len(), WgHandshakeResp::LEN);
    }
}

#[test]
fn wg_cookie_reply_roundtrip() {
    let mut c = WgCookieReply::new();
    c.receiver_idx.set(42);
    c.nonce = [9u8; 24];
    let p = pkt(c.as_bytes()).try_into_wg().unwrap();
    assert!(matches!(p, WgKind::CookieReply(_)));
    if let WgKind::CookieReply(r) = p {
        assert_eq!(r.receiver_idx.get(), 42);
        assert_eq!(r.nonce, [9u8; 24]);
        assert_eq!(r.as_bytes().len(), WgCookieReply::LEN);
    }
}

#[test]
fn wg_data_keepalive_and_payload() {
    // Exactly overhead => empty payload => keepalive.
    let hdr = WgDataHeader::new().with_receiver_idx(5).with_counter(9);
    let mut buf = vec![0u8; WgData::OVERHEAD];
    buf[..WgDataHeader::LEN].copy_from_slice(hdr.as_bytes());
    let p = pkt(&buf).try_into_wg().unwrap();
    assert!(matches!(p, WgKind::Data(_)));
    if let WgKind::Data(d) = p {
        assert!(WgData::is_empty(&d));
        assert!(d.is_keepalive());
        assert_eq!(d.header.receiver_idx.get(), 5);
        assert_eq!(d.header.counter.get(), 9);
        assert_eq!(d.encrypted_encapsulated_packet().len(), 0);
    }
    // Overhead + 4 payload bytes.
    let mut buf = vec![0u8; WgData::OVERHEAD + 4];
    buf[..WgDataHeader::LEN].copy_from_slice(hdr.as_bytes());
    buf[WgDataHeader::LEN..WgDataHeader::LEN + 4].copy_from_slice(&[1, 2, 3, 4]);
    let p = pkt(&buf).try_into_wg().unwrap();
    if let WgKind::Data(mut d) = p {
        assert!(!WgData::is_empty(&d));
        assert!(!d.is_keepalive());
        assert_eq!(d.encrypted_encapsulated_packet(), &[1, 2, 3, 4]);
        assert_eq!(d.tag().len(), WgData::TAG_LEN);
        d.encrypted_encapsulated_packet_mut()[0] = 99;
        assert_eq!(d.encrypted_encapsulated_packet()[0], 99);
    } else {
        panic!("expected Data");
    }
}

#[test]
fn wg_packet_type_debug_names() {
    assert_eq!(format!("{:?}", WgPacketType::Data), "Data");
    assert_eq!(format!("{:?}", WgPacketType::HandshakeInit), "HandshakeInit");
    assert_eq!(format!("{:?}", WgPacketType(77)), "77");
}

#[test]
fn wg_kind_into_packet_preserves_bytes() {
    let resp = WgHandshakeResp::new(1, 2, [0u8; 32]);
    let raw = resp.as_bytes().to_vec();
    let kind: WgKind = pkt(&raw).try_into_wg().unwrap();
    let back: Packet = kind.into();
    assert_eq!(back.as_bytes(), raw.as_slice());
}

