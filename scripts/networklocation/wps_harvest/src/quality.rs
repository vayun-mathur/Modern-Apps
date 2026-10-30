//! Turning many observations of a beacon into at most one store record.
//!
//! This is where "accuracy first" is actually earned. Coverage comes from the crawl; what
//! decides whether a fix is any good is which observations are kept and which are thrown away.
//!
//! Three rules, in order of how much they matter:
//!
//! 1. **Movers are dropped entirely.** A BSSID seen in two places kilometres apart is a phone
//!    hotspot, a travel router, or an AP on a bus or train. Recording either position is worse
//!    than recording none: a device that sees it gets confidently placed somewhere it is not,
//!    and the solver has no way to tell. This is also the standard privacy mitigation for a
//!    dataset of this shape, since a moving AP's history is a person's history.
//! 2. **Unusable identifiers are dropped**: randomized or multicast MACs, cell identities that
//!    do not fit the key layout, coordinates outside the representable range.
//! 3. **Among what is left, the most precise observation wins**, and its accuracy is carried
//!    into the store rather than being replaced by a constant.

use crate::keys;
use crate::record::{Record, ACC_UNKNOWN};

/// Metres per degree of latitude, near enough for a threshold comparison.
const M_PER_DEG_LAT: f64 = 111_320.0;

/// How the merge should treat a group of observations.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Policy {
    /// Observations of one beacon further apart than this mean it moves, so it is dropped.
    pub mover_threshold_m: u32,
    /// Drop observations whose reported accuracy is worse than this. `None` keeps everything,
    /// which is the default: a coarse fix still helps the solver, because the solver is told
    /// how coarse it is.
    pub max_accuracy_m: Option<u16>,
    /// Whether keys are WiFi MACs, which enables the randomized-address filter.
    pub wifi: bool,
}

impl Policy {
    /// Defaults for a WiFi store.
    pub fn wifi() -> Policy {
        Policy { mover_threshold_m: 1_000, max_accuracy_m: None, wifi: true }
    }

    /// Defaults for a cell store.
    ///
    /// The mover threshold is far looser than WiFi's: a cell's reported position is the centre
    /// of a sector that can be kilometres across, and two datasets can put the same tower a
    /// long way apart without either being wrong about which tower it is.
    pub fn cell() -> Policy {
        Policy { mover_threshold_m: 20_000, max_accuracy_m: None, wifi: false }
    }
}

/// Why a group produced no record. Counted so a build reports what it discarded rather than
/// silently shrinking.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Rejects {
    /// Randomized, locally-administered or multicast MACs.
    pub randomized_mac: u64,
    /// Coordinates outside the store's representable range.
    pub out_of_range: u64,
    /// Observations too far apart to be one fixed beacon.
    pub mover: u64,
    /// Every observation was less precise than the policy allows.
    pub too_coarse: u64,
}

impl Rejects {
    /// Total groups discarded.
    pub fn total(&self) -> u64 {
        self.randomized_mac + self.out_of_range + self.mover + self.too_coarse
    }
}

/// Approximate distance in metres. Equirectangular, which is accurate to well under a percent
/// at the scales the thresholds sit at and does not need trigonometry per pair beyond one
/// cosine.
pub fn distance_m(lat_a_e8: i64, lon_a_e8: i64, lat_b_e8: i64, lon_b_e8: i64) -> f64 {
    let lat_a = lat_a_e8 as f64 / 1e8;
    let lat_b = lat_b_e8 as f64 / 1e8;
    let dlat = (lat_a - lat_b) * M_PER_DEG_LAT;
    let mid = ((lat_a + lat_b) / 2.0).to_radians();
    let dlon = (lon_a_e8 - lon_b_e8) as f64 / 1e8 * M_PER_DEG_LAT * mid.cos();
    (dlat * dlat + dlon * dlon).sqrt()
}

/// Reduce every observation of one beacon to at most one record.
///
/// `group` must be non-empty and all entries must share a key. Returns `None` when the beacon
/// is rejected, recording why in `rejects`.
pub fn reduce(group: &[Record], policy: &Policy, rejects: &mut Rejects) -> Option<Record> {
    let first = *group.first()?;

    if policy.wifi && keys::is_randomized_mac(first.key as u64) {
        rejects.randomized_mac += 1;
        return None;
    }

    // Anything unrepresentable is dropped rather than clamped: a clamped coordinate is a
    // confident claim about the wrong place.
    let mut usable: Vec<&Record> = group.iter().filter(|r| r.in_range()).collect();
    if usable.is_empty() {
        rejects.out_of_range += 1;
        return None;
    }

    // Movers are judged before the accuracy filter, so a beacon is not rescued from the mover
    // test by having its outlying observations filtered out first.
    let threshold = policy.mover_threshold_m as f64;
    for (i, a) in usable.iter().enumerate() {
        for b in usable.iter().skip(i + 1) {
            if distance_m(a.lat_e8, a.lon_e8, b.lat_e8, b.lon_e8) > threshold {
                rejects.mover += 1;
                return None;
            }
        }
    }

    if let Some(max) = policy.max_accuracy_m {
        usable.retain(|r| r.accuracy_m != ACC_UNKNOWN && r.accuracy_m <= max);
        if usable.is_empty() {
            rejects.too_coarse += 1;
            return None;
        }
    }

    // Most precise wins; an unreported accuracy loses to any reported one. `sort` in `sort.rs`
    // already orders a group this way, but reduce must not depend on that to stay correct.
    let best = usable
        .into_iter()
        .min_by_key(|r| (r.accuracy_m == ACC_UNKNOWN, r.accuracy_m, r.source, r.lat_e8, r.lon_e8))?;
    Some(*best)
}

/// Clamp a source-reported accuracy into the store's 16-bit field.
///
/// Saturates rather than dropping: 65 km is already useless for positioning, but the solver
/// weights by accuracy, so a saturated value is correctly ignored rather than absent.
pub fn clamp_accuracy(meters: i32) -> u16 {
    if meters < 0 {
        ACC_UNKNOWN
    } else {
        meters.min(ACC_UNKNOWN as i32 - 1) as u16
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::record::{ALT_UNKNOWN, Source};

    const SF_LAT: i64 = 37_77493000;
    const SF_LON: i64 = -122_41942000;

    fn rec(key: u128, lat: i64, lon: i64, acc: u16, source: Source) -> Record {
        Record { key, lat_e8: lat, lon_e8: lon, accuracy_m: acc, altitude_m: ALT_UNKNOWN, source }
    }

    fn wifi(lat: i64, lon: i64, acc: u16) -> Record {
        rec(0x001122334455, lat, lon, acc, Source::Gsloc)
    }

    #[test]
    fn distance_is_about_right() {
        // One degree of latitude.
        let d = distance_m(0, 0, 1_00000000, 0);
        assert!((d - M_PER_DEG_LAT).abs() < 1.0, "got {d}");
        // A degree of longitude shrinks with latitude.
        let equator = distance_m(0, 0, 0, 1_00000000);
        let high = distance_m(60_00000000, 0, 60_00000000, 1_00000000);
        assert!(high < equator * 0.55, "{high} vs {equator}");
        assert_eq!(distance_m(SF_LAT, SF_LON, SF_LAT, SF_LON), 0.0);
    }

    #[test]
    fn the_most_precise_observation_wins_and_keeps_its_accuracy() {
        let mut rj = Rejects::default();
        let group = vec![
            wifi(SF_LAT, SF_LON, 200),
            wifi(SF_LAT + 100, SF_LON, 15),
            wifi(SF_LAT - 50, SF_LON, 90),
        ];
        let got = reduce(&group, &Policy::wifi(), &mut rj).unwrap();
        assert_eq!(got.accuracy_m, 15);
        assert_eq!(got.lat_e8, SF_LAT + 100);
        assert_eq!(rj.total(), 0);
    }

    #[test]
    fn a_reported_accuracy_beats_an_unreported_one() {
        let mut rj = Rejects::default();
        let group = vec![wifi(SF_LAT, SF_LON, ACC_UNKNOWN), wifi(SF_LAT, SF_LON, 900)];
        assert_eq!(reduce(&group, &Policy::wifi(), &mut rj).unwrap().accuracy_m, 900);
    }

    #[test]
    fn an_only_unreported_accuracy_still_produces_a_record() {
        let mut rj = Rejects::default();
        let group = vec![wifi(SF_LAT, SF_LON, ACC_UNKNOWN)];
        assert_eq!(reduce(&group, &Policy::wifi(), &mut rj).unwrap().accuracy_m, ACC_UNKNOWN);
    }

    #[test]
    fn a_beacon_seen_in_two_places_is_dropped_not_averaged() {
        let mut rj = Rejects::default();
        // San Francisco and New York.
        let group = vec![wifi(SF_LAT, SF_LON, 20), wifi(40_71278000, -74_00594000, 20)];
        assert_eq!(reduce(&group, &Policy::wifi(), &mut rj), None);
        assert_eq!(rj.mover, 1);
    }

    #[test]
    fn small_disagreements_between_sources_are_tolerated() {
        let mut rj = Rejects::default();
        // ~55 m apart: two datasets describing the same AP.
        let group = vec![
            rec(0x001122334455, SF_LAT, SF_LON, 40, Source::Beacondb),
            rec(0x001122334455, SF_LAT + 50000, SF_LON, 25, Source::Gsloc),
        ];
        assert!(reduce(&group, &Policy::wifi(), &mut rj).is_some());
        assert_eq!(rj.mover, 0);
    }

    #[test]
    fn the_accuracy_filter_cannot_rescue_a_mover() {
        let mut rj = Rejects::default();
        let policy = Policy { max_accuracy_m: Some(50), ..Policy::wifi() };
        // The far-away observation would be filtered out by max_accuracy, but the beacon still
        // moves and must not be kept on the strength of the surviving one.
        let group = vec![wifi(SF_LAT, SF_LON, 20), wifi(40_71278000, -74_00594000, 5000)];
        assert_eq!(reduce(&group, &policy, &mut rj), None);
        assert_eq!(rj.mover, 1);
        assert_eq!(rj.too_coarse, 0);
    }

    #[test]
    fn randomized_macs_are_dropped_for_wifi_but_the_filter_is_off_for_cells() {
        let mut rj = Rejects::default();
        // 0xAA first octet: locally administered.
        let group = vec![rec(0xAABBCCDDEEFF, SF_LAT, SF_LON, 20, Source::Gsloc)];
        assert_eq!(reduce(&group, &Policy::wifi(), &mut rj), None);
        assert_eq!(rj.randomized_mac, 1);

        // The same bit pattern as a cell key means nothing; the filter must not fire.
        let mut rj2 = Rejects::default();
        assert!(reduce(&group, &Policy::cell(), &mut rj2).is_some());
        assert_eq!(rj2.randomized_mac, 0);
    }

    #[test]
    fn out_of_range_coordinates_are_dropped() {
        let mut rj = Rejects::default();
        let group = vec![wifi(91_00000000, SF_LON, 20)];
        assert_eq!(reduce(&group, &Policy::wifi(), &mut rj), None);
        assert_eq!(rj.out_of_range, 1);
    }

    #[test]
    fn a_partly_out_of_range_group_keeps_the_usable_observation() {
        let mut rj = Rejects::default();
        let group = vec![wifi(91_00000000, SF_LON, 5), wifi(SF_LAT, SF_LON, 30)];
        let got = reduce(&group, &Policy::wifi(), &mut rj).unwrap();
        assert_eq!(got.lat_e8, SF_LAT);
        assert_eq!(rj.total(), 0);
    }

    #[test]
    fn max_accuracy_drops_a_group_with_nothing_precise_enough() {
        let mut rj = Rejects::default();
        let policy = Policy { max_accuracy_m: Some(50), ..Policy::wifi() };
        let group = vec![wifi(SF_LAT, SF_LON, 300), wifi(SF_LAT, SF_LON, ACC_UNKNOWN)];
        assert_eq!(reduce(&group, &policy, &mut rj), None);
        assert_eq!(rj.too_coarse, 1);
    }

    #[test]
    fn accuracy_clamps_into_the_field_rather_than_wrapping() {
        assert_eq!(clamp_accuracy(0), 0);
        assert_eq!(clamp_accuracy(35), 35);
        assert_eq!(clamp_accuracy(-1), ACC_UNKNOWN);
        assert_eq!(clamp_accuracy(i32::MIN), ACC_UNKNOWN);
        assert_eq!(clamp_accuracy(999_999), 65534);
        assert_eq!(clamp_accuracy(65534), 65534);
        // Never produces the sentinel from a real measurement.
        assert_ne!(clamp_accuracy(65535), ACC_UNKNOWN);
    }
}
