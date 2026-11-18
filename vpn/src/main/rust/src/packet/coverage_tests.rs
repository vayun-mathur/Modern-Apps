//! Extra branch coverage: pool, Packet conversions, decode_mut, IPv4 options,
//! payload-decoder checksum branches, IPv6 payload decoders, TCP/UDP Debug.
#![cfg(test)]
use super::*;
use bytes::BytesMut;
use std::net::{Ipv4Addr, Ipv6Addr};
use zerocopy::{FromBytes, IntoBytes};

fn v4_bytes(proto: IpNextProtocol, payload: &[u8]) -> Vec<u8> {
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
fn udp_seg(payload: &[u8], checksum: u16) -> Vec<u8> {
    let h = UdpHeader::new(1000, 2000, (UdpHeader::LEN + payload.len()) as u16, checksum);
    let mut v = h.as_bytes().to_vec();
    v.extend_from_slice(payload);
    v
}
fn tcp_seg(payload: &[u8]) -> Vec<u8> {
    let mut b = vec![0u8; TcpHeader::LEN];
    b[0..2].copy_from_slice(&1234u16.to_be_bytes());
    b[2..4].copy_from_slice(&80u16.to_be_bytes());
    b[12] = 0x50;
    b[13] = 0x02;
    b.extend_from_slice(payload);
    b
}
fn v6_bytes(next: IpNextProtocol, payload: &[u8]) -> Vec<u8> {
    let h = Ipv6Header {
        version_traffic_flow: Ipv6VersionTrafficFlow::new().with_version(6),
        payload_length: (payload.len() as u16).into(),
        next_header: next,
        hop_limit: 64,
        source_address: u128::from(Ipv6Addr::LOCALHOST).into(),
        destination_address: u128::from(Ipv6Addr::LOCALHOST).into(),
    };
    let mut v = h.as_bytes().to_vec();
    v.extend_from_slice(payload);
    v
}
fn owned_v4_udp() -> Packet<Ipv4<Udp>> {
    let raw = v4_bytes(IpNextProtocol::Udp, &udp_seg(b"hi", 0));
    let p4: Packet<Ipv4<[u8]>> = Ipv4Decoder::UNCHECKED
        .decode_owned(Packet::from_bytes(BytesMut::from(&raw[..])))
        .unwrap();
    Ipv4PayloadDecoder::<UdpDecoder>::UNCHECKED.decode_owned(p4).unwrap()
}
fn owned_v4_tcp() -> Packet<Ipv4<Tcp>> {
    let raw = v4_bytes(IpNextProtocol::Tcp, &tcp_seg(b"hi"));
    let p4: Packet<Ipv4<[u8]>> = Ipv4Decoder::UNCHECKED
        .decode_owned(Packet::from_bytes(BytesMut::from(&raw[..])))
        .unwrap();
    Ipv4PayloadDecoder::<TcpDecoder>::UNCHECKED.decode_owned(p4).unwrap()
}
fn owned_v6_udp() -> Packet<Ipv6<Udp>> {
    let raw = v6_bytes(IpNextProtocol::Udp, &udp_seg(b"hi", 0));
    let p6: Packet<Ipv6<[u8]>> = Ipv6Decoder::UNCHECKED
        .decode_owned(Packet::from_bytes(BytesMut::from(&raw[..])))
        .unwrap();
    Ipv6PayloadDecoder::<UdpDecoder>::UNCHECKED.decode_owned(p6).unwrap()
}
fn owned_v6_tcp() -> Packet<Ipv6<Tcp>> {
    let raw = v6_bytes(IpNextProtocol::Tcp, &tcp_seg(b"hi"));
    let p6: Packet<Ipv6<[u8]>> = Ipv6Decoder::UNCHECKED
        .decode_owned(Packet::from_bytes(BytesMut::from(&raw[..])))
        .unwrap();
    Ipv6PayloadDecoder::<TcpDecoder>::UNCHECKED.decode_owned(p6).unwrap()
}

#[test]
fn pool_get_returns_zeroed_sized_packet() {
    let pool = PacketBufPool::<4096>::new(4);
    let p = pool.get();
    assert_eq!(p.len(), 4096);
    assert!(p.as_bytes().iter().all(|&b| b == 0));
    let p2 = PacketBufPool::<64>::new_lazy(1).get();
    assert_eq!(p2.len(), 64);
    // new_from_pool keeps bytes and length.
    let pooled = Packet::new_from_pool(ReturnToPool, BytesMut::from(&[1u8, 2, 3][..]));
    assert_eq!(pooled.as_bytes(), &[1, 2, 3]);
}

#[test]
fn packet_basics_default_truncate_buf_and_debug() {
    let mut p = Packet::default();
    assert!(p.is_empty());
    assert_eq!(p.len(), 0);
    p.buf_mut().extend_from_slice(&[9, 8, 7, 6]);
    assert_eq!(p.buf(), &[9, 8, 7, 6]);
    assert_eq!(p.as_ptr(), p.as_bytes().as_ptr());
    p.truncate(2);
    assert_eq!(p.as_bytes(), &[9, 8]);
    assert_eq!(format!("{:?}", Packet::from_bytes(BytesMut::from(&[1u8][..]))), "Packet([1])");
    let cloned_bytes: Packet = Packet::copy_from(&[5u8, 6]);
    assert_eq!(cloned_bytes.as_bytes(), &[5, 6]);
}

#[test]
fn packet_overwrite_with_and_deref_mut() {
    let base = Packet::from_bytes(BytesMut::from(&[0u8; 4][..]));
    let resp = WgHandshakeResp::new(7, 8, [3u8; 32]);
    let mut p: Packet<WgHandshakeResp> = base.overwrite_with(&resp);
    assert_eq!(p.sender_idx(), 7);
    p.mac1 = [0xAA; 16];
    assert_eq!(p.mac1(), &[0xAA; 16]);
    *p.mac2_mut() = [0xBB; 16];
    assert_eq!(p.mac2(), &[0xBB; 16]);
    assert_eq!(p.until_mac1().len(), WgHandshakeResp::MAC1_OFF);
    // Generic Packet<P> -> Packet<[u8]> conversion.
    let raw: Packet = p.into_bytes();
    assert_eq!(raw.len(), WgHandshakeResp::LEN);
}

#[test]
fn packet_typed_conversions_to_ip_and_bytes() {
    let raw4u = owned_v4_udp().as_bytes().to_vec();
    let a: Packet<Ipv4<[u8]>> = owned_v4_udp().into();
    assert_eq!(a.as_bytes(), raw4u.as_slice());
    let b: Packet<Ip> = owned_v4_udp().into();
    assert_eq!(b.as_bytes(), raw4u.as_slice());
    let c: Packet = owned_v4_udp().into();
    assert_eq!(c.as_bytes(), raw4u.as_slice());
    let d: Packet<Ip> = owned_v6_udp().into();
    assert_eq!(d.len(), owned_v6_udp().len());
    let _: Packet = owned_v6_udp().into();
    let _: Packet<Ipv4<[u8]>> = owned_v4_tcp().into();
    let _: Packet<Ip> = owned_v4_tcp().into();
    let _: Packet = owned_v4_tcp().into();
    let _: Packet<Ipv6<[u8]>> = owned_v6_tcp().into();
    let _: Packet<Ip> = owned_v6_tcp().into();
    let _: Packet = owned_v6_tcp().into();
    // Ipv4<[u8]> / Ipv6<[u8]> -> Ip / [u8]
    let p4: Packet<Ipv4<[u8]>> = Ipv4Decoder::UNCHECKED
        .decode_owned(Packet::from_bytes(BytesMut::from(&v4_bytes(IpNextProtocol::Udp, b"x")[..])))
        .unwrap();
    let _: Packet<Ip> = p4.into();
    let p6: Packet<Ipv6<[u8]>> = Ipv6Decoder::UNCHECKED
        .decode_owned(Packet::from_bytes(BytesMut::from(&v6_bytes(IpNextProtocol::Udp, b"x")[..])))
        .unwrap();
    let ip6: Packet<Ip> = p6.into();
    let _: Packet = ip6.into();
    // Ip -> [u8] via Packet<Ip> conversion already; WgData handled in wg tests.
}

#[test]
fn into_payload_chains_v6_and_udp() {
    let p6 = owned_v6_udp();
    let payload: Packet<Udp> = p6.into_payload();
    assert_eq!(payload.header.source_port.get(), 1000);
    let inner: Packet<[u8]> = payload.into_payload();
    assert_eq!(inner.as_bytes(), b"hi");
    let p4 = owned_v4_udp();
    let udp: Packet<Udp> = p4.into_payload();
    assert_eq!(udp.header.destination_port.get(), 2000);
}

#[test]
fn decode_mut_and_error_display() {
    let mut bytes = udp_seg(b"hello", 0);
    bytes[6..8].copy_from_slice(&((UdpHeader::LEN + 5) as u16).to_be_bytes());
    let udp: &mut Udp = UdpDecoder::CHECK_ALL.decode_mut(&mut bytes[..]).unwrap();
    udp.header.source_port.set(4321);
    assert_eq!(&bytes[0..2], &4321u16.to_be_bytes());
    assert_eq!(format!("{}", DecodeError::InvalidValue("x")), "Invalid value for x");
    assert_eq!(format!("{}", DecodeError::BadAlignment), "Bad alignment");
    assert_eq!(format!("{}", DecodeError::InvalidSourceSize), "Invalid source size");
    assert_eq!(format!("{}", DecodeError::InvalidSourceData), "Invalid source data");
    // Short buffer maps zerocopy size error to InvalidSourceSize.
    assert!(matches!(
        UdpDecoder::CHECK_ALL.decode_ref(&[0u8; 3]),
        Err(DecodeError::InvalidSourceSize)
    ));
}

#[test]
fn ipv4_options_payload_and_assume_no_options() {
    // IHL=6: 4 option bytes (1,2,3,4) then payload "data".
    let mut h = Ipv4Header::new_for_length(
        Ipv4Addr::new(10, 0, 0, 1),
        Ipv4Addr::new(10, 0, 0, 2),
        IpNextProtocol::Udp,
        8,
    );
    h.version_and_ihl = h.version_and_ihl.with_ihl(6);
    h.total_len.set(24 + 4);
    h.header_checksum = h.compute_checksum().into();
    let mut raw = h.as_bytes().to_vec();
    raw.extend_from_slice(&[1, 2, 3, 4]);
    raw.extend_from_slice(b"data");
    let opts: &Ipv4<Ipv4Options<[u8]>> = Ipv4Decoder::CHECK_ALL.decode_ref(&raw[..]).unwrap();
    assert_eq!(opts.payload().unwrap(), b"data");
    assert!(matches!(
        Decoder::<Ipv4<Ipv4Options<[u8]>>, Ipv4<[u8]>>::decode_ref(&Ipv4AssumeNoOptions, opts),
        Err(DecodeError::InvalidValue("IHL"))
    ));
    // IHL=5 passes AssumeNoOptions.
    let raw5 = v4_bytes(IpNextProtocol::Udp, b"xy");
    let opts5: &Ipv4<Ipv4Options<[u8]>> = Ipv4Decoder::CHECK_ALL.decode_ref(&raw5[..]).unwrap();
    assert!(Decoder::<Ipv4<Ipv4Options<[u8]>>, Ipv4<[u8]>>::decode_ref(&Ipv4AssumeNoOptions, opts5).is_ok());
    // Decoder<Ip, Ipv4Options> / Ipv4 / Ipv6 entry points.
    let ip_pkt = Packet::from_bytes(BytesMut::from(&raw5[..])).try_into_ip().unwrap();
    let via_ip: &Ip = IpDecoder::UNCHECKED.decode_ref(&raw5[..]).unwrap();
    let _ = via_ip;
    assert!(Decoder::<Ip, Ipv4<Ipv4Options>>::decode_ref(&Ipv4Decoder::CHECK_ALL, {
        let ip: &Ip = Ip::ref_from_bytes(&raw5).unwrap();
        ip
    }).is_ok());
    let _ = ip_pkt;
    // Invalid IHL (<5) for payload(): first byte 0x44.
    let mut bad = raw5.clone();
    bad[0] = 0x44;
    let mut hh = Ipv4Header::ref_from_bytes(&bad[..20]).unwrap().to_owned();
    hh.header_checksum.set(0);
    hh.header_checksum = hh.compute_checksum().into();
    bad[..20].copy_from_slice(hh.as_bytes());
    let bad_opts: &Ipv4<Ipv4Options<[u8]>> = Ipv4Decoder::UNCHECKED.decode_ref(&bad[..]).unwrap();
    assert!(bad_opts.payload().is_err());
    // IHL larger than buffer for payload().
    let mut big = raw5.clone();
    big[0] = 0x4F;
    let mut hh = Ipv4Header::ref_from_bytes(&big[..20]).unwrap().to_owned();
    hh.header_checksum.set(0);
    hh.header_checksum = hh.compute_checksum().into();
    big[..20].copy_from_slice(hh.as_bytes());
    let big_opts: &Ipv4<Ipv4Options<[u8]>> = Ipv4Decoder::UNCHECKED.decode_ref(&big[..]).unwrap();
    assert!(big_opts.payload().is_err());
}

#[test]
fn ipv4_header_new_and_debug_and_oversize_len() {
    let h = Ipv4Header::new(Ipv4Addr::new(1, 1, 1, 1), Ipv4Addr::new(2, 2, 2, 2), IpNextProtocol::Tcp, &[0u8; 10]);
    assert_eq!(h.total_len.get(), 30);
    let dbg = format!("{:?}", h);
    assert!(dbg.contains("version"));
    assert!(dbg.contains("source_address"));
    // try_update_ip_len errors above u16::MAX total size.
    let mut buf = vec![0u8; 70_000];
    buf[0] = 0x45;
    let ip = Ipv4::<[u8]>::mut_from_bytes(&mut buf).unwrap();
    assert!(ip.try_update_ip_len().is_err());
}

#[test]
fn ipv4_payload_checksum_branches() {
    // UDP with correct checksum passes; corrupted payload fails.
    let payload = b"hello";
    let mut seg = udp_seg(payload, 0);
    let udp = Udp::<[u8]>::ref_from_bytes(&seg).unwrap();
    let ph = PseudoHeaderV4::from_udp(0xC0A80001.into(), 0xC0A80002.into(), udp);
    let csum = crate::packet::util::checksum_udp_with_skip(ph, udp);
    seg[6..8].copy_from_slice(&csum.to_be_bytes());
    let raw = v4_bytes(IpNextProtocol::Udp, &seg);
    let ip: &Ipv4 = Ipv4Decoder::CHECK_ALL.decode_ref(&raw[..]).unwrap();
    assert!(Decoder::<Ipv4<[u8]>, Ipv4<Udp>>::decode_ref(&Ipv4PayloadDecoder::<UdpDecoder>::CHECK_ALL, ip).is_ok());
    let mut corrupt = seg.clone();
    corrupt[8] ^= 0xFF;
    let raw_bad = v4_bytes(IpNextProtocol::Udp, &corrupt);
    let ip_bad: &Ipv4 = Ipv4Decoder::CHECK_ALL.decode_ref(&raw_bad[..]).unwrap();
    assert!(matches!(
        Decoder::<Ipv4<[u8]>, Ipv4<Udp>>::decode_ref(&Ipv4PayloadDecoder::<UdpDecoder>::CHECK_ALL, ip_bad),
        Err(DecodeError::InvalidValue("UDP checksum"))
    ));
    // UDP checksum 0 means "no checksum" in IPv4: accepted.
    let raw_zero = v4_bytes(IpNextProtocol::Udp, &udp_seg(b"zz", 0));
    let ip_zero: &Ipv4 = Ipv4Decoder::CHECK_ALL.decode_ref(&raw_zero[..]).unwrap();
    assert!(Decoder::<Ipv4<[u8]>, Ipv4<Udp>>::decode_ref(&Ipv4PayloadDecoder::<UdpDecoder>::CHECK_ALL, ip_zero).is_ok());
    // TCP bad checksum fails; wrong protocol for TCP decoder fails.
    let raw_tcp_bad = v4_bytes(IpNextProtocol::Tcp, &tcp_seg(b"hi"));
    let ip_tcp: &Ipv4 = Ipv4Decoder::CHECK_ALL.decode_ref(&raw_tcp_bad[..]).unwrap();
    assert!(Decoder::<Ipv4<[u8]>, Ipv4<Tcp>>::decode_ref(&Ipv4PayloadDecoder::<TcpDecoder>::CHECK_ALL, ip_tcp).is_err());
    assert!(matches!(
        Decoder::<Ipv4<[u8]>, Ipv4<Tcp>>::decode_ref(&Ipv4PayloadDecoder::<TcpDecoder>::CHECK_ALL, ip_zero),
        Err(DecodeError::InvalidValue("protocol"))
    ));
    // Fragmented TCP also rejected.
    let mut frag = v4_bytes(IpNextProtocol::Tcp, &tcp_seg(b"hi"));
    frag[6] |= 0x20;
    let mut hh = Ipv4Header::ref_from_bytes(&frag[..20]).unwrap().to_owned();
    hh.header_checksum.set(0);
    hh.header_checksum = hh.compute_checksum().into();
    frag[..20].copy_from_slice(hh.as_bytes());
    let ip_frag: &Ipv4 = Ipv4Decoder::CHECK_ALL.decode_ref(&frag[..]).unwrap();
    assert!(matches!(
        Decoder::<Ipv4<[u8]>, Ipv4<Tcp>>::decode_ref(&Ipv4PayloadDecoder::<TcpDecoder>::CHECK_ALL, ip_frag),
        Err(DecodeError::InvalidValue("fragment_offset / more_fragments"))
    ));
}

#[test]
fn ipv6_payload_and_len_branches() {
    // try_update_ip_len success + Debug.
    let raw = v6_bytes(IpNextProtocol::Udp, &udp_seg(b"abc", 0));
    let mut buf = raw.clone();
    let buf_len = buf.len();
    let ip6 = Ipv6::<[u8]>::mut_from_bytes(&mut buf).unwrap();
    ip6.try_update_ip_len().unwrap();
    assert_eq!(ip6.header.payload_length.get() as usize, buf_len - Ipv6Header::LEN);
    let dbg = format!("{:?}", ip6.header);
    assert!(dbg.contains("traffic_class"));
    // Oversize payload length update errors.
    let mut big = vec![0u8; 70_000];
    big[0] = 0x60;
    let ip_big = Ipv6::<[u8]>::mut_from_bytes(&mut big).unwrap();
    assert!(ip_big.try_update_ip_len().is_err());
    // Wrong protocol.
    let ip_ref: &Ipv6 = Ipv6Decoder::CHECK_ALL.decode_ref(&raw[..]).unwrap();
    assert!(matches!(
        Decoder::<Ipv6<[u8]>, Ipv6<Tcp>>::decode_ref(&Ipv6PayloadDecoder::<TcpDecoder>::CHECK_ALL, ip_ref),
        Err(DecodeError::InvalidValue("protocol"))
    ));
    // UDP checksum: correct passes, corrupt fails.
    let mut seg = udp_seg(b"hello", 0);
    let udp = Udp::<[u8]>::ref_from_bytes(&seg).unwrap();
    let ph = PseudoHeaderV6::from_udp(u128::from(Ipv6Addr::LOCALHOST).into(), u128::from(Ipv6Addr::LOCALHOST).into(), udp);
    let csum = crate::packet::util::checksum_udp_with_skip(ph, udp);
    seg[6..8].copy_from_slice(&csum.to_be_bytes());
    let raw_ok = v6_bytes(IpNextProtocol::Udp, &seg);
    let ip_ok: &Ipv6 = Ipv6Decoder::CHECK_ALL.decode_ref(&raw_ok[..]).unwrap();
    assert!(Decoder::<Ipv6<[u8]>, Ipv6<Udp>>::decode_ref(&Ipv6PayloadDecoder::<UdpDecoder>::CHECK_ALL, ip_ok).is_ok());
    let mut corrupt = seg.clone();
    corrupt[8] ^= 1;
    let raw_bad = v6_bytes(IpNextProtocol::Udp, &corrupt);
    let ip_bad: &Ipv6 = Ipv6Decoder::CHECK_ALL.decode_ref(&raw_bad[..]).unwrap();
    assert!(Decoder::<Ipv6<[u8]>, Ipv6<Udp>>::decode_ref(&Ipv6PayloadDecoder::<UdpDecoder>::CHECK_ALL, ip_bad).is_err());
    // Decoder<Ip, Ipv6> entry point + Tcp v6 bad checksum.
    let ip_any: &Ip = Ip::ref_from_bytes(&raw_ok).unwrap();
    assert!(Decoder::<Ip, Ipv6>::decode_ref(&Ipv6Decoder::CHECK_ALL, ip_any).is_ok());
    let raw_tcp = v6_bytes(IpNextProtocol::Tcp, &tcp_seg(b"hi"));
    let ip_tcp: &Ipv6 = Ipv6Decoder::CHECK_ALL.decode_ref(&raw_tcp[..]).unwrap();
    assert!(Decoder::<Ipv6<[u8]>, Ipv6<Tcp>>::decode_ref(&Ipv6PayloadDecoder::<TcpDecoder>::CHECK_ALL, ip_tcp).is_err());
}

#[test]
fn tcp_flags_debug_and_checksum_update() {
    let mut hdr_bytes = [0u8; 20];
    hdr_bytes[12] = 0x50;
    let mut hdr = TcpHeader::ref_from_bytes(&hdr_bytes).unwrap().to_owned();
    hdr.set_psh(true);
    hdr.set_rst(true);
    hdr.set_urg(true);
    hdr.set_ece(true);
    hdr.set_cwr(true);
    assert!(hdr.psh() && hdr.rst() && hdr.urg() && hdr.ece() && hdr.cwr());
    assert!(!hdr.syn());
    hdr.set_syn(true);
    assert!(hdr.syn());
    let dbg = format!("{:?}", hdr);
    assert!(dbg.contains("source_port"));
    assert!(dbg.contains("data_offset"));
    // Tcp Debug (header + options + payload).
    let tcp_bytes = tcp_seg(b"payload");
    let tcp = Tcp::<[u8]>::ref_from_bytes(&tcp_bytes).unwrap();
    let tdbg = format!("{:?}", tcp);
    assert!(tdbg.contains("Tcp"));
    // update_tcp_checksum v4 and v6 agree with calculate_*.
    let mut p4 = owned_v4_tcp();
    let before = p4.calculate_tcp_checksum();
    p4.payload.header.checksum.set(0);
    p4.update_tcp_checksum();
    assert_eq!(p4.calculate_tcp_checksum(), before);
    let mut p6 = owned_v6_tcp();
    let before6 = p6.calculate_tcp_checksum();
    p6.payload.header.checksum.set(0);
    p6.update_tcp_checksum();
    assert_eq!(p6.calculate_tcp_checksum(), before6);
    // TcpDecoder direct: valid offset ok, UNCHECKED ok on malformed.
    assert!(TcpDecoder::CHECK_ALL.decode_ref(&tcp_bytes).is_ok());
}

#[test]
fn udp_and_wg_debug_formats() {
    let h = UdpHeader::new(1, 2, 8, 3);
    let dbg = format!("{:?}", h);
    assert!(dbg.contains("source_port"));
    assert!(dbg.contains("checksum"));
    let udp_bytes = udp_seg(b"x", 0);
    let udp = Udp::<[u8]>::ref_from_bytes(&udp_bytes).unwrap();
    assert!(format!("{:?}", udp).contains("Udp"));
    // Wg Debug + WgKind Debug for every variant.
    let init = WgHandshakeInit::new();
    let raw = Packet::from_bytes(BytesMut::from(init.as_bytes()));
    let kind = raw.try_into_wg().unwrap();
    assert!(format!("{:?}", kind).contains("HandshakeInit"));
    let resp = WgHandshakeResp::new(1, 2, [0u8; 32]);
    let kind = Packet::from_bytes(BytesMut::from(resp.as_bytes())).try_into_wg().unwrap();
    assert!(format!("{:?}", kind).contains("HandshakeResp"));
    let cookie = WgCookieReply::default();
    assert_eq!(cookie.as_bytes().len(), WgCookieReply::LEN);
    let kind = Packet::from_bytes(BytesMut::from(cookie.as_bytes())).try_into_wg().unwrap();
    assert!(format!("{:?}", kind).contains("CookieReply"));
    let mut data = vec![0u8; WgData::OVERHEAD];
    data[0] = 4;
    let kind = Packet::from_bytes(BytesMut::from(&data[..])).try_into_wg().unwrap();
    assert!(format!("{:?}", kind).contains("Data"));
    // Wg struct Debug via a WgHandshakeBase mac accessors on Resp.
    let mut resp2 = WgHandshakeResp::new(9, 10, [1u8; 32]);
    *resp2.mac1_mut() = [4u8; 16];
    *resp2.mac2_mut() = [5u8; 16];
    assert_eq!(resp2.mac1(), &[4u8; 16]);
    assert_eq!(resp2.mac2(), &[5u8; 16]);
    assert_eq!(resp2.sender_idx(), 9);
    assert_eq!(format!("{:?}", WgPacketType::CookieReply), "CookieReply");
    assert_eq!(format!("{:?}", WgPacketType::HandshakeResp), "HandshakeResp");
}

#[test]
fn util_pseudo_from_tcp_and_checksum_tcp_branch() {
    let tcp_bytes = tcp_seg(b"abc");
    let tcp = Tcp::<[u8]>::ref_from_bytes(&tcp_bytes).unwrap();
    let ph4 = PseudoHeaderV4::from_tcp(0x0A000001.into(), 0x0A000002.into(), tcp);
    assert_eq!(ph4.protocol, IpNextProtocol::Tcp);
    let ph6 = PseudoHeaderV6::from_tcp(u128::from(Ipv6Addr::LOCALHOST).into(), u128::from(Ipv6Addr::LOCALHOST).into(), tcp);
    assert_eq!(ph6.protocol, IpNextProtocol::Tcp);
    let ph6u = PseudoHeaderV6::from_udp(u128::from(Ipv6Addr::LOCALHOST).into(), u128::from(Ipv6Addr::LOCALHOST).into(), Udp::<[u8]>::ref_from_bytes(&udp_seg(b"a", 0)).unwrap());
    assert_eq!(ph6u.protocol, IpNextProtocol::Udp);
    // checksum with odd-length payload via skip path is deterministic.
    let c1 = crate::packet::util::checksum_tcp_with_skip(ph4, tcp);
    let c2 = crate::packet::util::checksum_tcp_with_skip(PseudoHeaderV4::from_tcp(0x0A000001.into(), 0x0A000002.into(), tcp), tcp);
    assert_eq!(c1, c2);
}
