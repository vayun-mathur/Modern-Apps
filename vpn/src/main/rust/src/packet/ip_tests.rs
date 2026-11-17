//! Tests for generic IP decoding (`Packet::try_into_ip` / `try_into_ipvx`, `Ip::source/destination`).
#![cfg(test)]
use super::*;
use bytes::BytesMut;
use either::Either;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use zerocopy::IntoBytes;

fn v4_packet() -> Vec<u8> {
    let h = Ipv4Header::new_for_length(Ipv4Addr::new(10, 0, 0, 1), Ipv4Addr::new(10, 0, 0, 2), IpNextProtocol::Udp, 3);
    let mut v = h.as_bytes().to_vec();
    v.extend_from_slice(&[1, 2, 3]);
    v
}

fn v6_packet() -> Vec<u8> {
    let mut h = Ipv6Header {
        version_traffic_flow: Ipv6VersionTrafficFlow::new().with_version(6),
        payload_length: 3.into(),
        next_header: IpNextProtocol::Tcp,
        hop_limit: 64,
        source_address: u128::from(Ipv6Addr::LOCALHOST).into(),
        destination_address: u128::from(Ipv6Addr::LOCALHOST).into(),
    };
    h.set_version(6);
    let mut v = h.as_bytes().to_vec();
    v.extend_from_slice(&[4, 5, 6]);
    v
}

#[test]
fn try_into_ipvx_routes_v4_and_v6() {
    let p = Packet::from_bytes(BytesMut::from(&v4_packet()[..]));
    assert!(matches!(p.try_into_ipvx().unwrap(), Either::Left(_)));
    let p = Packet::from_bytes(BytesMut::from(&v6_packet()[..]));
    assert!(matches!(p.try_into_ipvx().unwrap(), Either::Right(_)));
}

#[test]
fn try_into_ip_rejects_tiny() {
    let p = Packet::from_bytes(BytesMut::from(&[0x45u8][..]));
    assert!(p.try_into_ip().is_err());
    let p = Packet::from_bytes(BytesMut::new());
    assert!(p.try_into_ip().is_err());
}

#[test]
fn try_into_ipvx_rejects_bad_version() {
    let mut b = v4_packet();
    b[0] = 0x75; // version 7
    let p = Packet::from_bytes(BytesMut::from(&b[..]));
    assert!(p.try_into_ipvx().is_err());
}

#[test]
fn ip_source_destination_v4_v6_and_bad() {
    let bytes = v4_packet();
    let ip: &Ip = IpDecoder::UNCHECKED.decode_ref(&bytes).unwrap();
    assert_eq!(ip.source(), Some(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1))));
    assert_eq!(ip.destination(), Some(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2))));

    let bytes = v6_packet();
    let ip: &Ip = IpDecoder::UNCHECKED.decode_ref(&bytes).unwrap();
    assert_eq!(ip.source(), Some(IpAddr::V6(Ipv6Addr::LOCALHOST)));

    let mut bad = v4_packet();
    bad[0] = 0x95;
    let ip: &Ip = IpDecoder::UNCHECKED.decode_ref(&bad).unwrap();
    assert_eq!(ip.source(), None);
    assert_eq!(ip.destination(), None);
}

#[test]
fn ip_decoder_check_all_rejects_short_v6() {
    // CHECK_ALL with version=true demands 40 bytes for v6.
    let bytes = v6_packet()[..20].to_vec();
    assert!(matches!(
        Decoder::<[u8], Ip>::decode_ref(&IpDecoder::CHECK_ALL, &bytes),
        Err(DecodeError::InvalidValue("length"))
    ));
}

#[test]
fn packet_into_payload_strips_headers() {
    // IPv4 -> payload
    let p = Packet::from_bytes(BytesMut::from(&v4_packet()[..]));
    let ip = p.try_into_ipvx().unwrap();
    let Either::Left(v4) = ip else { panic!("v4") };
    let payload: Packet<[u8]> = v4.into_payload();
    assert_eq!(payload.as_bytes(), &[1, 2, 3]);
}

#[test]
fn ip_next_protocol_constants() {
    assert_eq!(IpNextProtocol::Tcp.as_bytes(), &[6]);
    assert_eq!(IpNextProtocol::Udp.as_bytes(), &[17]);
    assert_ne!(IpNextProtocol::Tcp, IpNextProtocol::Udp);
    assert_eq!(format!("{:?}", IpNextProtocol::Udp), "Udp");
}
