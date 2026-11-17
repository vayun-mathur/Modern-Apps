//! Beacon identity → 128-bit store key.
//!
//! Every function here has a counterpart in
//! `networklocation/src/main/java/com/vayunmathur/networklocation/BeaconKeys.kt`, and the two
//! must agree exactly. A mismatch is silent: the store is built with one packing, the device
//! probes with another, every lookup misses, and that is indistinguishable from a database
//! that simply does not know the beacon. The test vectors at the bottom of this file are
//! transcribed verbatim from `BeaconKeysTest.kt` for exactly that reason.

/// A BSSID is 48 bits, so the high half of a WiFi key is always zero.
pub const WIFI_UNIVERSE_BITS: u8 = 48;

/// Cell keys are 84 bits, laid out MSB-first as:
///
/// ```text
/// mcc(10) | mnc(10) | radio(4) | area(24) | cid(36)
/// ```
///
/// 36 bits of cell id covers 5G's NCI; 24 bits of area code covers 5G's TAC. The radio type is
/// part of the identity because an LTE ECI and a GSM CID are otherwise indistinguishable
/// numbers within the same network and area code.
pub const CELL_UNIVERSE_BITS: u8 = 84;

const CID_BITS: u32 = 36;
const AREA_BITS: u32 = 24;
const CID_MASK: u64 = (1u64 << CID_BITS) - 1;
const AREA_MASK: u64 = (1u64 << AREA_BITS) - 1;

/// Cellular access technology. Wire values are baked into every cell key, so this is
/// append-only and must match `RadioType` in `PositioningData.kt`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum RadioType {
    /// 2G.
    Gsm = 1,
    /// 3G.
    Umts = 2,
    /// 4G.
    Lte = 3,
    /// 5G.
    Nr = 4,
}

impl RadioType {
    /// Parse the spellings the open datasets use in their `radio` column.
    pub fn parse(s: &str) -> Option<RadioType> {
        match s.trim().to_ascii_uppercase().as_str() {
            "GSM" | "2G" | "CDMA" => (!s.trim().eq_ignore_ascii_case("CDMA")).then_some(RadioType::Gsm),
            "UMTS" | "WCDMA" | "3G" | "HSPA" => Some(RadioType::Umts),
            "LTE" | "4G" => Some(RadioType::Lte),
            "NR" | "5G" | "NRNSA" => Some(RadioType::Nr),
            _ => None,
        }
    }
}

/// A cell tower's identity, before packing.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CellId {
    /// Mobile country code, 0..=999.
    pub mcc: u16,
    /// Mobile network code, 0..=999.
    pub mnc: u16,
    /// Access technology.
    pub radio: RadioType,
    /// Cell id: NR NCI (36 bits), LTE ECI (28), UMTS CID (28) or GSM CID (16).
    pub cell_id: u64,
    /// LTE/NR TAC or GSM/UMTS LAC.
    pub area: u32,
}

/// Parse `"aa:bb:cc:dd:ee:ff"` to a 48-bit key, big-endian (OUI in the high bits).
pub fn parse_mac(bssid: &str) -> Option<u64> {
    let mut v = 0u64;
    let mut groups = 0;
    for part in bssid.split(':') {
        if part.len() != 2 {
            return None;
        }
        let b = u8::from_str_radix(part, 16).ok()?;
        v = (v << 8) | b as u64;
        groups += 1;
    }
    (groups == 6).then_some(v)
}

/// Whether `mac` is locally administered or multicast.
///
/// Randomized client addresses, virtual interfaces and multicast addresses are not stable
/// identifiers for a place: the same address turns up somewhere else tomorrow. An entry for
/// one is worse than no entry, so the builder drops them and the client skips probing them.
pub fn is_randomized_mac(mac: u64) -> bool {
    ((mac >> 40) & 0x03) != 0
}

/// Full 128-bit store key for a WiFi AP.
pub fn wifi_key(mac: u64) -> u128 {
    mac as u128
}

/// Full 128-bit store key for a cell tower.
pub fn cell_key(id: &CellId) -> u128 {
    let hi = ((id.mcc as u64 & 0x3FF) << 10) | (id.mnc as u64 & 0x3FF);
    let lo = ((id.radio as u64 & 0xF) << (CID_BITS + AREA_BITS))
        | ((id.area as u64 & AREA_MASK) << CID_BITS)
        | (id.cell_id & CID_MASK);
    ((hi as u128) << 64) | lo as u128
}

/// Whether `id` survives packing unchanged. A tower whose identity does not fit the key
/// layout would be stored under a truncated key that a different tower also maps to, so it is
/// dropped rather than aliased.
pub fn cell_fits(id: &CellId) -> bool {
    id.mcc <= 999
        && id.mnc <= 999
        && (id.area as u64) <= AREA_MASK
        && id.cell_id <= CID_MASK
        && id.cell_id > 0
}

#[cfg(test)]
mod tests {
    use super::*;

    // These expectations are transcribed from `BeaconKeysTest.kt`. Change them here only
    // together with that file.

    #[test]
    fn mac_parses_big_endian_with_the_oui_in_the_high_bits() {
        assert_eq!(parse_mac("00:11:22:33:44:55"), Some(0x001122334455));
        assert_eq!(parse_mac("aa:bb:cc:dd:ee:ff"), Some(0xAABBCCDDEEFF));
        assert_eq!(parse_mac("AA:BB:CC:DD:EE:FF"), Some(0xAABBCCDDEEFF));
        assert_eq!(parse_mac("00:00:00:00:00:00"), Some(0));
        assert_eq!(parse_mac("ff:ff:ff:ff:ff:ff"), Some(0xFFFFFFFFFFFF));
    }

    #[test]
    fn malformed_macs_are_rejected() {
        for bad in [
            "",
            "00:11:22:33:44",
            "00:11:22:33:44:55:66",
            "00-11-22-33-44-55",
            "zz:11:22:33:44:55",
            "0:11:22:33:44:55",
            "-1:11:22:33:44:55",
        ] {
            assert_eq!(parse_mac(bad), None, "{bad:?} must not parse");
        }
    }

    #[test]
    fn locally_administered_and_multicast_macs_are_excluded() {
        assert!(is_randomized_mac(0xAABBCCDDEEFF));
        assert!(is_randomized_mac(0x020000000000));
        assert!(is_randomized_mac(0x010000000000));
        assert!(!is_randomized_mac(0x001122334455));
        assert!(!is_randomized_mac(0xFCFBFB000000));
        assert!(!is_randomized_mac(0x00FFFFFFFFFF));
    }

    #[test]
    fn cell_key_layout_is_mcc10_mnc10_radio4_area24_cid36() {
        let id = CellId {
            mcc: 310,
            mnc: 260,
            radio: RadioType::Lte,
            cell_id: 0xABCDEF123,
            area: 12345,
        };
        let key = cell_key(&id);
        assert_eq!((key >> 64) as u64, 0x4D904);
        assert_eq!(key as u64, 0x3003039ABCDEF123);
    }

    #[test]
    fn radio_type_discriminates_otherwise_identical_cells() {
        let base = CellId {
            mcc: 234,
            mnc: 15,
            radio: RadioType::Gsm,
            cell_id: 4242,
            area: 999,
        };
        let mut keys: Vec<u128> = [RadioType::Gsm, RadioType::Umts, RadioType::Lte, RadioType::Nr]
            .iter()
            .map(|&r| cell_key(&CellId { radio: r, ..base }))
            .collect();
        keys.sort_unstable();
        keys.dedup();
        assert_eq!(keys.len(), 4, "each radio type must produce its own key");
    }

    #[test]
    fn full_36_bit_nci_survives_packing() {
        let id = CellId {
            mcc: 1,
            mnc: 1,
            radio: RadioType::Nr,
            cell_id: 0xFFFFFFFFF,
            area: 0,
        };
        assert_eq!(cell_key(&id) as u64 & 0xFFFFFFFFF, 0xFFFFFFFFF);
        let truncated = CellId { cell_id: 0x0FFFFFFF, ..id };
        assert_ne!(cell_key(&id), cell_key(&truncated));
    }

    #[test]
    fn wide_5g_tac_survives_packing() {
        let id = CellId {
            mcc: 505,
            mnc: 1,
            radio: RadioType::Nr,
            cell_id: 1,
            area: 0xFFFFFF,
        };
        assert_eq!((cell_key(&id) as u64 >> 36) & 0xFFFFFF, 0xFFFFFF);
    }

    #[test]
    fn keys_stay_inside_their_declared_universe() {
        let widest = CellId {
            mcc: 999,
            mnc: 999,
            radio: RadioType::Nr,
            cell_id: 0xFFFFFFFFF,
            area: 0xFFFFFF,
        };
        assert!(cell_key(&widest) < (1u128 << CELL_UNIVERSE_BITS));
        assert!(wifi_key(0xFFFFFFFFFFFF) < (1u128 << WIFI_UNIVERSE_BITS));
    }

    #[test]
    fn identities_that_do_not_fit_are_rejected_not_truncated() {
        let ok = CellId {
            mcc: 310,
            mnc: 260,
            radio: RadioType::Lte,
            cell_id: 42,
            area: 7,
        };
        assert!(cell_fits(&ok));
        assert!(!cell_fits(&CellId { mcc: 1000, ..ok }));
        assert!(!cell_fits(&CellId { mnc: 1000, ..ok }));
        assert!(!cell_fits(&CellId { area: 0x1000000, ..ok }));
        assert!(!cell_fits(&CellId { cell_id: 1 << 36, ..ok }));
        assert!(!cell_fits(&CellId { cell_id: 0, ..ok }));
    }

    #[test]
    fn radio_parse_covers_the_dataset_spellings() {
        assert_eq!(RadioType::parse("GSM"), Some(RadioType::Gsm));
        assert_eq!(RadioType::parse("umts"), Some(RadioType::Umts));
        assert_eq!(RadioType::parse("LTE"), Some(RadioType::Lte));
        assert_eq!(RadioType::parse("NR"), Some(RadioType::Nr));
        // CDMA has no MCC/MNC/LAC/CID identity of this shape and is not carried.
        assert_eq!(RadioType::parse("CDMA"), None);
        assert_eq!(RadioType::parse("banana"), None);
    }
}
