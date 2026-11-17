//! Extra decoder-branch tests for IPv4/IPv6/TCP beyond the happy paths in-module.
#![cfg(test)]
use super::*;
use std::net::{Ipv4Addr, Ipv6Addr};
use zerocopy::{FromBytes, IntoBytes};

fn v4_udp_bytes(proto: IpNextProtocol, payload: &[u8]) -> Vec<u8> {
    let mut h = Ipv4Header::new_for_length(
        Ipv4Addr::new(192, 168, 0, 1),
        Ipv4Addr::new(192, 168, 0, 2),
        proto,
        payload.len() as u16,
    );
    h.header_checksum = h.compute_checksum().into();
    let mut v = h.as_bytes().to_vec();
    v.extend_from_slice(payload);
    v
}

fn udp_seg(payload: &[u8]) -> Vec<u8> {
    let h = UdpHeader::new(1000, 2000, (UdpHeader::LEN + payload.len()) as u16, 0);
    let mut v = h.as_bytes().to_vec();
    v.extend_from_slice(payload);
    v
}

#[test]
fn ipv4_decoder_rejects_bad_version() {
    let mut b = v4_udp_bytes(IpNextProtocol::Udp, &udp_seg(b"x"));
    b[0] = 0x65; // version 6, ihl 5
    // fix checksum so only version can fail
    let mut h = Ipv4Header::ref_from_bytes(&b[..20]).unwrap().to_owned();
    h.header_checksum.set(0);
    h.header_checksum = h.compute_checksum().into();
    b[..20].copy_from_slice(h.as_bytes());
    assert!(matches!(
        Decoder::<[u8], Ipv4<[u8]>>::decode_ref(&Ipv4Decoder::CHECK_ALL, &b),
        Err(DecodeError::InvalidValue("version"))
    ));
}

#[test]
fn ipv4_decoder_rejects_bad_total_len() {
    let mut b = v4_udp_bytes(IpNextProtocol::Udp, &udp_seg(b"x"));
    b.truncate(10); // shorter than header/total_len
    assert!(Decoder::<[u8], Ipv4<[u8]>>::decode_ref(&Ipv4Decoder::CHECK_ALL, &b).is_err());
    // total_len smaller than header
    let mut b2 = v4_udp_bytes(IpNextProtocol::Udp, &udp_seg(b"x"));
    b2[2..4].copy_from_slice(&10u16.to_be_bytes());
    let mut h = Ipv4Header::ref_from_bytes(&b2[..20]).unwrap().to_owned();
    h.header_checksum.set(0);
    h.header_checksum = h.compute_checksum().into();
    b2[..20].copy_from_slice(h.as_bytes());
    assert!(matches!(
        Decoder::<[u8], Ipv4<[u8]>>::decode_ref(&Ipv4Decoder::CHECK_ALL, &b2),
        Err(DecodeError::InvalidValue("total_len"))
    ));
}

#[test]
fn ipv4_decoder_rejects_non5_ihl_for_no_options() {
    let mut b = v4_udp_bytes(IpNextProtocol::Udp, &udp_seg(b"x"));
    b[0] = 0x46; // ihl 6
    let mut h = Ipv4Header::ref_from_bytes(&b[..20]).unwrap().to_owned();
    h.header_checksum.set(0);
    h.header_checksum = h.compute_checksum().into();
    b[..20].copy_from_slice(h.as_bytes());
    assert!(matches!(
        Decoder::<[u8], Ipv4<[u8]>>::decode_ref(&Ipv4Decoder::CHECK_ALL, &b),
        Err(DecodeError::InvalidValue("IHL"))
    ));
}

#[test]
fn ipv4_payload_decoder_rejects_wrong_protocol_and_fragment() {
    let b = v4_udp_bytes(IpNextProtocol::Tcp, &udp_seg(b"x"));
    let ip: &Ipv4 = Ipv4Decoder::CHECK_ALL.decode_ref(&b[..]).unwrap();
    assert!(matches!(
        Decoder::<Ipv4<[u8]>, Ipv4<Udp>>::decode_ref(&Ipv4PayloadDecoder::<UdpDecoder>::CHECK_ALL, ip),
        Err(DecodeError::InvalidValue("protocol"))
    ));
    // Fragmented: set MF flag (byte 6: 0x20)
    let mut b2 = v4_udp_bytes(IpNextProtocol::Udp, &udp_seg(b"x"));
    b2[6] |= 0x20;
    let mut h = Ipv4Header::ref_from_bytes(&b2[..20]).unwrap().to_owned();
    h.header_checksum.set(0);
    h.header_checksum = h.compute_checksum().into();
    b2[..20].copy_from_slice(h.as_bytes());
    let ip2: &Ipv4 = Ipv4Decoder::CHECK_ALL.decode_ref(&b2[..]).unwrap();
    assert!(matches!(
        Decoder::<Ipv4<[u8]>, Ipv4<Udp>>::decode_ref(&Ipv4PayloadDecoder::<UdpDecoder>::CHECK_ALL, ip2),
        Err(DecodeError::InvalidValue("fragment_offset / more_fragments"))
    ));
}

#[test]
fn ipv4_try_update_ip_len() {
    let mut b = v4_udp_bytes(IpNextProtocol::Udp, &udp_seg(b"abc"));
    let ip = Ipv4::<[u8]>::mut_from_bytes(&mut b).unwrap();
    ip.try_update_ip_len().unwrap();
    assert_eq!(usize::from(ip.header.total_len.get()), b.len());
}

#[test]
fn ipv6_decoder_rejects_bad_version_and_short() {
    let mut h = Ipv6Header {
        version_traffic_flow: Ipv6VersionTrafficFlow::new().with_version(4),
        payload_length: 0.into(),
        next_header: IpNextProtocol::Tcp,
        hop_limit: 1,
        source_address: u128::from(Ipv6Addr::LOCALHOST).into(),
        destination_address: u128::from(Ipv6Addr::LOCALHOST).into(),
    };
    h.set_version(4);
    let b = h.as_bytes().to_vec();
    assert!(matches!(
        Decoder::<[u8], Ipv6<[u8]>>::decode_ref(&Ipv6Decoder::CHECK_ALL, &b),
        Err(DecodeError::InvalidValue("version"))
    ));
    assert!(Ipv6Decoder::UNCHECKED.decode_ref(&b[..]).is_ok());
    // payload_length beyond buffer
    h.set_version(6);
    h.payload_length.set(100);
    let b = h.as_bytes().to_vec();
    assert!(matches!(
        Decoder::<[u8], Ipv6<[u8]>>::decode_ref(&Ipv6Decoder::CHECK_ALL, &b),
        Err(DecodeError::InvalidValue("total length"))
    ));
}

#[test]
fn ipv6_header_accessors_and_total_length() {
    let mut h = Ipv6Header {
        version_traffic_flow: Ipv6VersionTrafficFlow::new().with_version(6),
        payload_length: 5.into(),
        next_header: IpNextProtocol::Udp,
        hop_limit: 33,
        source_address: u128::from(Ipv6Addr::LOCALHOST).into(),
        destination_address: u128::from(Ipv6Addr::LOCALHOST).into(),
    };
    h.set_traffic_class(0xAB);
    h.set_flow_label(0x12345);
    assert_eq!(h.version(), 6);
    assert_eq!(h.traffic_class(), 0xAB);
    assert_eq!(h.flow_label(), 0x12345);
    assert_eq!(h.total_length(), Ipv6Header::LEN + 5);
    assert_eq!(h.next_protocol(), IpNextProtocol::Udp);
    assert_eq!(h.source(), Ipv6Addr::LOCALHOST);
}

#[test]
fn tcp_options_payload_slicing() {
    // data_offset 6 => 4 bytes options, then payload
    let mut b = vec![0u8; 20];
    b[12] = 0x60;
    b.extend_from_slice(&[1, 1, 1, 1]); // options
    b.extend_from_slice(b"data");
    let tcp = Tcp::<[u8]>::ref_from_bytes(&b).unwrap();
    assert_eq!(tcp.options_len(), Some(4));
    assert_eq!(tcp.options(), Some(&[1, 1, 1, 1][..]));
    assert_eq!(tcp.payload(), Some(&b"data"[..]));
    // data_offset 5 => no options
    b[12] = 0x50;
    let tcp = Tcp::<[u8]>::ref_from_bytes(&b).unwrap();
    assert_eq!(tcp.options(), Some(&[][..]));
    assert_eq!(tcp.payload(), Some(&[1, 1, 1, 1, b'd', b'a', b't', b'a'][..]));
    // data_offset 4 => malformed
    b[12] = 0x40;
    let tcp = Tcp::<[u8]>::ref_from_bytes(&b).unwrap();
    assert_eq!(tcp.options_len(), None);
    assert_eq!(tcp.payload(), None);
    assert!(matches!(
        Decoder::<[u8], Tcp>::decode_ref(&TcpDecoder::CHECK_ALL, &b),
        Err(DecodeError::InvalidValue("TCP data_offset"))
    ));
    assert!(TcpDecoder::UNCHECKED.decode_ref(&b).is_ok());
}

#[test]
fn tcp_flags_setters_roundtrip() {
    let mut b = vec![0u8; 20];
    b[12] = 0x50;
    let mut hdr = TcpHeader::ref_from_bytes(&b).unwrap().to_owned();
    hdr.set_syn(true);
    hdr.set_ack(true);
    assert!(hdr.syn() && hdr.ack() && !hdr.fin() && !hdr.rst());
    hdr.set_fin(true);
    assert!(hdr.fin());
    assert_eq!(hdr.data_offset(), 5);
    assert_eq!(TcpDataOffset::no_options().data_offset(), 5);
}
