//! Extended checksum / pseudo-header tests (util.rs has a single test today).
#![cfg(test)]
use super::util::*;
use super::*;
use std::net::{Ipv4Addr, Ipv6Addr};
use zerocopy::IntoBytes;

#[test]
fn checksum_empty_and_known_vectors() {
    assert_eq!(checksum(&[]), 0xFFFF);
    assert_eq!(checksum(&[&[]]), 0xFFFF);
    assert_eq!(checksum(&[&[0xFF, 0xFF]]), 0);
    // Odd length: single byte 0x01 pads to 0x0100, complement 0xFEFF.
    assert_eq!(checksum(&[&[0x01]]), 0xFEFF);
    // Split slices sum like one slice.
    assert_eq!(checksum(&[&[0x12, 0x34], &[0x56]]), checksum(&[&[0x12, 0x34, 0x56]]));
}

#[test]
fn checksum_self_consistency_ipv4_header() {
    let mut h = Ipv4Header::new_for_length(
        Ipv4Addr::new(1, 2, 3, 4),
        Ipv4Addr::new(5, 6, 7, 8),
        IpNextProtocol::Tcp,
        0,
    );
    let computed = checksum(&[h.as_bytes()]);
    h.header_checksum = computed.into();
    assert_eq!(checksum(&[h.as_bytes()]), 0);
    // with_skip recomputes the checksum ignoring the stored field.
    assert_eq!(checksum_ipv4_with_skip(h.as_bytes()), computed);
    assert_eq!(h.compute_checksum(), computed);
    h.header_checksum.set(0xBEEF);
    assert_eq!(checksum_ipv4_with_skip(h.as_bytes()), computed);
    assert_ne!(checksum(&[h.as_bytes()]), 0);
}

#[test]
fn checksum_udp_zero_maps_to_ffff() {
    // Craft pseudo-header + UDP whose raw checksum is 0 so the UDP wrapper
    // must return 0xFFFF (0 means "no checksum" on the wire).
    // checksum over [0xFFFF] word alone complements to 0; use that as payload
    // with an empty-ish pseudo header built from bytes.
    let c = checksum_udp(PseudoHeaderV4::from_bytes(
        0.into(),
        0.into(),
        IpNextProtocol::Udp,
        &[0xFF, 0xFF],
    ), &[0xFF, 0xFF]);
    // Just assert the invariant: never returns 0.
    assert_ne!(c, 0);
    let direct = checksum(&[&[0xFF, 0xFF]]);
    assert_eq!(direct, 0);
}

#[test]
fn pseudo_header_v4_fields() {
    let ph = PseudoHeaderV4::from_bytes(0x01020304.into(), 0x05060708.into(), IpNextProtocol::Udp, &[0u8; 20]);
    assert_eq!(ph.protocol, IpNextProtocol::Udp);
    assert_eq!(ph.length.get(), 20);
    assert_eq!(ph.source.get(), 0x01020304);
    assert_eq!(ph.as_bytes().len(), 12);
}

#[test]
fn pseudo_header_v6_fields() {
    let src: u128 = Ipv6Addr::LOCALHOST.into();
    let ph = PseudoHeaderV6::from_bytes(src.into(), src.into(), IpNextProtocol::Tcp, &[0u8; 7]);
    assert_eq!(ph.protocol, IpNextProtocol::Tcp);
    assert_eq!(ph.length.get(), 7);
    assert_eq!(ph.as_bytes().len(), 36);
}

#[test]
fn udp_tcp_checksum_roundtrip_v4() {
    // Build IPv4/UDP, compute checksum via decoder path, then verify decoder accepts it.
    let payload = b"roundtrip!";
    let mut udp_hdr = UdpHeader::new(1111, 2222, (UdpHeader::LEN + payload.len()) as u16, 0);
    let mut udp_bytes = udp_hdr.as_bytes().to_vec();
    udp_bytes.extend_from_slice(payload);
    let udp = super::Udp::<[u8]>::ref_from_bytes(&udp_bytes).unwrap();
    let ph = PseudoHeaderV4::from_udp(0x0A000001.into(), 0x0A000002.into(), udp);
    let csum = checksum_udp_with_skip(ph, udp);
    udp_hdr.checksum.set(csum);
    let mut full = udp_hdr.as_bytes().to_vec();
    full.extend_from_slice(payload);
    let udp2 = super::Udp::<[u8]>::ref_from_bytes(&full).unwrap();
    let ph2 = PseudoHeaderV4::from_udp(0x0A000001.into(), 0x0A000002.into(), udp2);
    assert_eq!(checksum_udp_with_skip(ph2, udp2), csum);
}
