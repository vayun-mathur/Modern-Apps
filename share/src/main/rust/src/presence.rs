//! Nearby Presence advertisement build/parse for public/Everyone mode (unencrypted V0).
//!
//! # Not on the Quick Share path
//!
//! Presence is a **separate subsystem** from the Nearby Connections BLE bootstrap
//! Quick Share actually uses. It advertises under `0xFCF1` with `np_adv` framing,
//! whereas Quick Share advertises a Nearby Connections `BleAdvertisement` under
//! `0000FEF3-0000-1000-8000-00805F9B34FB` (`p000\dses.java:21`) — see
//! [`crate::ble_adv`].
//!
//! This module is retained and unit-tested but is **not called from discovery**.
//! BetoCore's credential / D2D / payload FFI has no Java callers anywhere in GMS
//! 26.24.34 (writeup §11.1), and whether betocore is live for Quick Share at all
//! could not be determined (writeup §10.1). Treating `np_adv` as the Quick Share
//! advertisement format was the original mistake this module documents; see
//! `share/QUICK_SHARE_VERIFICATION.md`.
//!
//! Wraps `np_adv`'s `AdvBuilder<UnencryptedEncoder>` for building service-data and
//! `deserialize_advertisement` for parsing scanned bytes back into a display name
//! or JSON view. Fully-encrypted identities (LDT etc.) are out of scope.

use np_adv::{
    credential::book::CredentialBookBuilder,
    credential::matched::EmptyMatchedCredential,
    deserialization_arena, deserialize_advertisement,
    legacy::{
        data_elements::device_info::DeviceInfoDataElement,
        data_elements::tx_power::TxPowerDataElement,
        serialize::{AdvBuilder, UnencryptedEncoder},
    },
    shared_data::{DeviceInfo, DeviceType, TxPower},
};

/// Build a V0 unencrypted Nearby Presence advert for `device_name`.
///
/// Returns the serialized advert bytes (including the NP version header) suitable
/// for `AdvertiseData.addServiceData(0xFCF1, bytes)`. Clamps name to 5..9 bytes per
/// DeviceInfo spec: short names are padded with spaces, long names are truncated
/// with the truncation bit set, matching the Kotlin fallback semantics.
pub fn build_presence_advert(device_name: &str) -> Option<Vec<u8>> {
    let raw = device_name.as_bytes();
    if raw.len() <= 9 {
        let padded: Vec<u8> = if raw.len() < 5 {
            let mut v = raw.to_vec();
            v.extend(std::iter::repeat(b' ').take(5 - raw.len()));
            v
        } else {
            raw.to_vec()
        };
        return build_with_name_bytes(&padded, false);
    }
    build_with_name_bytes(&raw[..9], true)
}

fn build_with_name_bytes(name_bytes: &[u8], truncated: bool) -> Option<Vec<u8>> {
    if name_bytes.len() < 5 || name_bytes.len() > 9 {
        return None;
    }
    let device_info = DeviceInfo::try_from((DeviceType::Phone, truncated, name_bytes)).ok()?;
    let mut builder = AdvBuilder::new(UnencryptedEncoder);
    let tx = TxPower::try_from(0i8).ok()?;
    builder
        .add_data_element(TxPowerDataElement::from(tx))
        .ok()?;
    builder
        .add_data_element(DeviceInfoDataElement::from(device_info))
        .ok()?;
    let adv = builder.into_advertisement().ok()?;
    Some(adv.as_slice().to_vec())
}

/// Parse advert bytes (service-data for 0xFCF1) and extract the display name.
///
/// Returns `None` if bytes are not a valid unencrypted V0 presence advert or no DeviceInfo
/// element is present (e.g. encrypted LDT advert — not Everyone mode).
pub fn parse_presence_advert_name(advert_bytes: &[u8]) -> Option<String> {
    let cred_book = CredentialBookBuilder::<EmptyMatchedCredential>::build_cached_slice_book::<
        0,
        0,
        crypto_provider_default::CryptoProviderImpl,
    >(&[], &[]);
    let arena = deserialization_arena!();
    let adv = deserialize_advertisement::<_, crypto_provider_default::CryptoProviderImpl>(
        arena, advert_bytes, &cred_book,
    )
    .ok()?;
    let v0 = adv.into_v0()?;
    match v0 {
        np_adv::legacy::V0AdvertisementContents::Plaintext(p) => {
            for de in p.data_elements() {
                // Stop at the first undecodable element: the iterator
                // re-yields it without consuming input, so skipping an
                // error here spun forever on a truncated advert.
                let de = de.ok()?;
                if let np_adv::legacy::deserialize::DeserializedDataElement::DeviceInfo(di) = de {
                    let name = String::from_utf8_lossy(di.device_info.device_name())
                        .trim_end()
                        .to_string();
                    if !name.is_empty() {
                        return Some(name);
                    }
                }
            }
            None
        }
        _ => None,
    }
}

/// Parse advert bytes and produce a JSON view for Kotlin/UI/filtering.
///
/// On success: `{"deviceName":"Pixel 7","deviceType":1,"txPower":0,"isTruncated":false}` (utf8 bytes).
/// Returns `None` on invalid advert.
pub fn parse_presence_advert_json(advert_bytes: &[u8]) -> Option<Vec<u8>> {
    let cred_book = CredentialBookBuilder::<EmptyMatchedCredential>::build_cached_slice_book::<
        0,
        0,
        crypto_provider_default::CryptoProviderImpl,
    >(&[], &[]);
    let arena = deserialization_arena!();
    let adv = deserialize_advertisement::<_, crypto_provider_default::CryptoProviderImpl>(
        arena, advert_bytes, &cred_book,
    )
    .ok()?;
    let v0 = adv.into_v0()?;
    match v0 {
        np_adv::legacy::V0AdvertisementContents::Plaintext(p) => {
            let mut device_name: Option<String> = None;
            let mut device_type: i32 = DeviceType::Unknown as i32;
            let mut is_truncated = false;
            let mut tx_power: Option<i32> = None;
            for de in p.data_elements() {
                match de {
                    Ok(np_adv::legacy::deserialize::DeserializedDataElement::DeviceInfo(di)) => {
                        let di_ref = &di.device_info;
                        device_name = Some(
                            String::from_utf8_lossy(di_ref.device_name())
                                .trim_end()
                                .to_string(),
                        );
                        device_type = di_ref.device_type() as i32;
                        is_truncated = di_ref.name_truncated();
                    }
                    Ok(np_adv::legacy::deserialize::DeserializedDataElement::TxPower(tp)) => {
                        tx_power = Some(tp.tx_power.as_i8() as i32);
                    }
                    // As in `parse_presence_advert_name`: an undecodable
                    // element is re-yielded forever if skipped, so the
                    // advert is simply unusable.
                    Err(_) => return None,
                    _ => {}
                }
            }
            let name = device_name?;
            if name.is_empty() {
                return None;
            }
            let tx = tx_power.unwrap_or(0);
            // Minimal JSON escaping for deviceName (escape backslash and quote)
            let esc = name.replace('\\', "\\\\").replace('"', "\\\"");
            let json = format!(
                "{{\"deviceName\":\"{esc}\",\"deviceType\":{device_type},\"txPower\":{tx},\"isTruncated\":{is_truncated}}}",
            );
            Some(json.into_bytes())
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_parse_round_trip() {
        let cases = ["MyPhone", "Pixel 6", "A", "VeryLongDeviceNameThatGetsTruncated"];
        for name in cases {
            let adv = build_presence_advert(name).expect("build should succeed");
            assert!(!adv.is_empty(), "adv empty for {name}");
            assert_eq!(adv[0], 0x00, "version header should be 0x00 for {name}");
            let parsed = parse_presence_advert_name(&adv);
            assert!(parsed.is_some(), "parse should succeed for {name}");
            let got = parsed.unwrap();
            if name.as_bytes().len() <= 9 {
                let expected = String::from_utf8_lossy(name.as_bytes()).to_string();
                assert!(
                    got.starts_with(expected.trim()),
                    "got {got} expected prefix of {name}"
                );
            } else {
                assert_eq!(got.as_bytes().len(), 9, "truncated name should be 9 bytes");
            }
        }
    }

    #[test]
    fn parse_json_round_trip() {
        let adv = build_presence_advert("Pixel 7").unwrap();
        let json = parse_presence_advert_json(&adv).unwrap();
        let s = String::from_utf8(json).unwrap();
        assert!(s.contains("\"deviceName\":\"Pixel 7\""));
        assert!(s.contains("\"deviceType\":1"));
        assert!(s.contains("\"txPower\""));
        assert!(s.contains("\"isTruncated\":false"));
        // Short name padded path
        let adv2 = build_presence_advert("Mi").unwrap();
        let json2 = parse_presence_advert_json(&adv2).unwrap();
        let s2 = String::from_utf8(json2).unwrap();
        assert!(s2.contains("\"deviceName\":\"Mi\""));
    }

    #[test]
    fn encrypted_adv_not_parsed() {
        let adv = vec![0x04, 0x22, 0x22, 0xAA, 0xBB, 0xCC];
        assert!(parse_presence_advert_name(&adv).is_none());
        assert!(parse_presence_advert_json(&adv).is_none());
    }
}
