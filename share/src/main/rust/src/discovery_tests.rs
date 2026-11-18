//! Discovery-codec tests for the branches the golden tests did not
//! take: device-type decoding, name truncation on a UTF-8 boundary,
//! BLE payload header refusals, advertisement size/version refusals,
//! and presence-advert parsing of malformed input.

use super::{ble_adv, endpoint_info, presence};
use endpoint_info::DeviceType;

#[test]
fn device_type_decodes_every_known_value_and_defaults_unknown() {
    assert_eq!(DeviceType::from_raw(0), DeviceType::Unknown);
    assert_eq!(DeviceType::from_raw(1), DeviceType::Phone);
    assert_eq!(DeviceType::from_raw(2), DeviceType::Tablet);
    assert_eq!(DeviceType::from_raw(3), DeviceType::Laptop);
    assert_eq!(DeviceType::from_raw(4), DeviceType::Car);
    assert_eq!(DeviceType::from_raw(5), DeviceType::Foldable);
    assert_eq!(DeviceType::from_raw(6), DeviceType::Xr);
    assert_eq!(DeviceType::from_raw(7), DeviceType::Unknown);
    assert_eq!(DeviceType::from_raw(255), DeviceType::Unknown);
}

#[test]
fn a_long_multibyte_name_truncates_on_a_char_boundary() {
    // 20 copies of a 2-byte character = 40 bytes against a 32-byte cap:
    // the truncated name must still decode (no replacement char).
    let name = "€".repeat(11);
    let blob = endpoint_info::build(&name, DeviceType::Phone, |b| b.fill(3)).expect("builds");
    let parsed = endpoint_info::parse(&blob).expect("parses");
    let got = parsed.device_name.expect("a name is present");
    assert!(got.len() <= 32, "name is {} bytes", got.len());
    assert!(got.chars().all(|c| c == '€'), "boundary preserved: {got}");
    // A blank name builds nothing.
    assert!(endpoint_info::build("", DeviceType::Phone, |b| b.fill(3)).is_none());
}

#[test]
fn ble_endpoint_payload_refuses_bad_headers() {
    assert!(endpoint_info::parse_ble_endpoint_payload(&[]).is_none(), "empty");
    assert!(endpoint_info::parse_ble_endpoint_payload(&[0u8; 4]).is_none(), "short");
    // Right length, wrong version bits in the header.
    let mut raw = vec![0u8; 16];
    raw[0] = 0xFF;
    assert!(endpoint_info::parse_ble_endpoint_payload(&raw).is_none(), "bad version");
    // Declared info length past the end of the payload.
    let good = endpoint_info::build_ble_endpoint_payload("Ab3d", &[1, 2, 3]).expect("builds");
    let mut truncated = good.clone();
    truncated.truncate(good.len() - 1);
    assert!(endpoint_info::parse_ble_endpoint_payload(&truncated).is_none(), "truncated info");
    // A non-UTF8 endpoint id is refused.
    let mut bad_id = good.clone();
    bad_id[4] = 0xFF;
    assert!(endpoint_info::parse_ble_endpoint_payload(&bad_id).is_none(), "bad id");
    // The right version with the wrong service-id hash is not ours.
    let mut bad_hash = good.clone();
    bad_hash[1] ^= 0xFF;
    assert!(endpoint_info::parse_ble_endpoint_payload(&bad_hash).is_none(), "bad hash");
    // An endpoint-info blob whose name is the replacement character
    // itself is rejected, as GMS rejects U+FFFD names.
    let fffd = endpoint_info::build("a\u{fffd}b", DeviceType::Phone, |b| b.fill(3)).expect("builds");
    assert!(endpoint_info::parse(&fffd).is_none(), "U+FFFD name rejected");
    // An endpoint id that is not 4 characters cannot build.
    assert!(endpoint_info::build_ble_endpoint_payload("toolong!", &[1]).is_none());
    assert!(endpoint_info::build_ble_endpoint_payload("abc", &[1]).is_none());
}

#[test]
fn ble_advertisement_refuses_oversize_and_wrong_versions() {
    // Fast mode has a 27-byte budget; 64 bytes of data cannot fit.
    let adv = ble_adv::BleAdvertisement::fast(vec![7u8; 64], None);
    assert!(adv.serialize().is_none(), "oversize fast advertisement");
    // A header with the wrong version is not one of ours.
    assert!(ble_adv::BleAdvertisement::parse(&[0x00, 0x01]).is_none(), "bad version");
    assert!(ble_adv::BleAdvertisement::parse(&[]).is_none(), "empty");
    // Extended header claiming a negative data length is refused.
    let mut raw = ble_adv::BleAdvertisement::extended(vec![1, 2, 3], None)
        .serialize()
        .expect("serializes");
    // Corrupt the 4-byte big-endian length (after header + 3-byte hash).
    let len_at = 1 + 3;
    raw[len_at] = 0xFF;
    assert!(ble_adv::BleAdvertisement::parse(&raw).is_none(), "negative length");
    // A single trailing byte is not a device token (2 are required),
    // and the advertisement still parses.
    let fast = ble_adv::BleAdvertisement::fast(vec![9, 8, 7], None)
        .serialize()
        .expect("fast serializes");
    let mut trailed = fast.clone();
    trailed.push(0xAB);
    let parsed = ble_adv::BleAdvertisement::parse(&trailed).expect("trailing byte tolerated");
    assert_eq!(parsed.device_token, None);
    assert_eq!(parsed.data, vec![9, 8, 7]);
    // The mDNS service type and hash are stable strings/bytes.
    assert_eq!(ble_adv::mdns_service_type(), "_FC9F5ED42C8A._tcp");
    assert_eq!(ble_adv::ble_service_id_hash().len(), 3);
    assert_eq!(ble_adv::fast_initiation_service_data([0xAB, 0xCD]).len(), 5);
}

#[test]
fn presence_parsing_refuses_malformed_adverts() {
    assert!(presence::parse_presence_advert_name(&[]).is_none());
    assert!(presence::parse_presence_advert_name(b"not json at all").is_none());
    assert!(presence::parse_presence_advert_json(&[]).is_none());
    assert!(presence::parse_presence_advert_json(b"{\"device_name\":").is_none());
    // A built advert round-trips, and tampering with it does not panic.
    let built = presence::build_presence_advert("Pixel").expect("builds");
    assert_eq!(presence::parse_presence_advert_name(&built).as_deref(), Some("Pixel"));
    assert!(presence::parse_presence_advert_json(&built).is_some());
    let mut tampered = built.clone();
    tampered.truncate(built.len() / 2);
    // A truncated advert must be rejected, not spin the parser.
    assert!(presence::parse_presence_advert_name(&tampered).is_none());
    assert!(presence::parse_presence_advert_json(&tampered).is_none());
    // A blank name is space-padded per the DeviceInfo fallback, so it
    // builds but parses back to no usable name.
    let blank = presence::build_presence_advert("").expect("padded name builds");
    assert!(presence::parse_presence_advert_name(&blank).is_none());
    assert!(presence::parse_presence_advert_json(&blank).is_none(), "no name, no JSON view");
}
