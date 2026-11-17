//! Tests for UDP header + decoder and IP/UDP checksum helpers.
#![cfg(test)]
use super::*;
use zerocopy::{FromBytes, IntoBytes};

fn udp_bytes(payload: &[u8], len_field: u16) -> Vec<u8> {
    let h = UdpHeader::new(1234, 53, len_field, 0);
    let mut v = h.as_bytes().to_vec();
    v.extend_from_slice(payload);
    v
}

#[test]
fn udp_header_new_layout() {
    let h = UdpHeader::new(0x1234, 0xABCD, 100, 0x5555);
    assert_eq!(h.source_port.get(), 0x1234);
    assert_eq!(h.destination_port.get(), 0xABCD);
    assert_eq!(h.length.get(), 100);
    assert_eq!(h.checksum.get(), 0x5555);
    assert_eq!(h.as_bytes(), &[0x12, 0x34, 0xAB, 0xCD, 0, 100, 0x55, 0x55]);
    assert_eq!(UdpHeader::LEN, 8);
}

#[test]
fn udp_decoder_accepts_matching_length() {
    let bytes = udp_bytes(b"hello", (UdpHeader::LEN + 5) as u16);
    let udp: &Udp = UdpDecoder::CHECK_ALL.decode_ref(&bytes).unwrap();
    assert_eq!(&udp.payload, b"hello");
    // UNCHECKED also accepts.
    assert!(UdpDecoder::UNCHECKED.decode_ref(&bytes).is_ok());
}

#[test]
fn udp_decoder_rejects_length_mismatch_when_checked() {
    let bytes = udp_bytes(b"hello", 999);
    assert!(matches!(
        Decoder::<[u8], Udp>::decode_ref(&UdpDecoder::CHECK_ALL, &bytes),
        Err(DecodeError::InvalidValue("UDP Length"))
    ));
    // With length validation off the same bytes pass.
    assert!(UdpDecoder { length: false, checksum: false }.decode_ref(&bytes).is_ok());
}

#[test]
fn udp_decoder_rejects_short_buffer() {
    assert!(UdpDecoder::CHECK_ALL.decode_ref(&[0u8; 7]).is_err());
    assert!(UdpDecoder::CHECK_ALL.decode_ref(&[]).is_err());
}

#[test]
fn udp_ref_from_bytes_fields() {
    let bytes = udp_bytes(&[10, 20], 10);
    let udp = Udp::<[u8]>::ref_from_bytes(&bytes).unwrap();
    assert_eq!(udp.header.source_port.get(), 1234);
    assert_eq!(udp.header.destination_port.get(), 53);
    assert_eq!(&udp.payload, &[10, 20]);
}
