//! Self-checks for [`crate::store`], extracted wholesale so `store.rs` stays small.
//!
//! Kept as a sibling module so production callers keep using `crate::store::*`
//! with no path changes; only the test imports point at `crate::store`.

#[cfg(test)]
mod tests {
    use crate::keys;
    use crate::record::{ALT_UNKNOWN, Record, Source, ACC_UNKNOWN, LAT_E8_MIN, LON_E8_MIN};
    use crate::store::{
        KeyKind, DEFAULT_SAMPLE_LOG2, MAX_L, choose_l, verify_store, write_store,
    };

    use std::path::PathBuf;

    struct TempDir(PathBuf);
    impl TempDir {
        fn new(tag: &str) -> TempDir {
            let p = std::env::temp_dir().join(format!("wpsbuild-{tag}-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&p);
            std::fs::create_dir_all(&p).unwrap();
            TempDir(p)
        }
    }
    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn lcg(state: &mut u64) -> u64 {
        *state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        *state
    }

    /// Adapt a plain record list to the `io::Result` stream the writer and verifier take.
    fn ok(v: Vec<Record>) -> impl IntoIterator<Item = std::io::Result<Record>> {
        v.into_iter().map(Ok)
    }

    fn wifi_records(count: usize, seed: u64) -> Vec<Record> {
        let mut state = seed;
        let mut v: Vec<Record> = (0..count)
            .map(|i| Record {
                key: (lcg(&mut state) & 0xFFFF_FFFF_FFFF) as u128,
                lat_e8: (lcg(&mut state) % 18_000_000_000) as i64 + LAT_E8_MIN,
                lon_e8: (lcg(&mut state) % 36_000_000_000) as i64 + LON_E8_MIN,
                accuracy_m: if i % 13 == 0 { ACC_UNKNOWN } else { (i % 900) as u16 },
                altitude_m: (i % 9000) as i16 - 500,
                source: Source::Gsloc,
            })
            .collect();
        v.sort_by_key(|r| r.key);
        v.dedup_by_key(|r| r.key);
        v
    }

    #[test]
    fn store_round_trips_through_its_own_verifier() {
        let d = TempDir::new("roundtrip");
        let recs = wifi_records(3000, 0x1234_5678_9abc_def0);
        let out = d.0.join("wifi.wpsdb");
        let size = write_store(
            &out,
            &d.0,
            KeyKind::Wifi,
            keys::WIFI_UNIVERSE_BITS,
            recs.len() as u64,
            DEFAULT_SAMPLE_LOG2,
            ok(recs.clone()),
        )
        .unwrap();
        assert!(size > 0);
        verify_store(&out, ok(recs.clone())).unwrap();
    }

    #[test]
    fn cell_universe_round_trips() {
        let d = TempDir::new("cell");
        let mut state = 0x0f0f_0f0f_0f0f_0f0fu64;
        let mut recs: Vec<Record> = (0..800)
            .map(|_| Record {
                key: (((lcg(&mut state) & 0xFFFFF) as u128) << 64) | lcg(&mut state) as u128,
                lat_e8: 37_77_493_000,
                lon_e8: -122_41_942_000,
                accuracy_m: 900,
                altitude_m: ALT_UNKNOWN,
                source: Source::OpenCellId,
            })
            .collect();
        recs.sort_by_key(|r| r.key);
        recs.dedup_by_key(|r| r.key);
        let out = d.0.join("cells.wpsdb");
        write_store(
            &out,
            &d.0,
            KeyKind::Cell,
            keys::CELL_UNIVERSE_BITS,
            recs.len() as u64,
            DEFAULT_SAMPLE_LOG2,
            ok(recs.clone()),
        )
        .unwrap();
        verify_store(&out, ok(recs.clone())).unwrap();
    }

    #[test]
    fn unsorted_or_duplicate_input_is_refused() {
        let d = TempDir::new("unsorted");
        let base = wifi_records(50, 7);
        let out = d.0.join("bad.wpsdb");

        let mut swapped = base.clone();
        swapped.swap(10, 11);
        let err = write_store(
            &out,
            &d.0,
            KeyKind::Wifi,
            keys::WIFI_UNIVERSE_BITS,
            swapped.len() as u64,
            DEFAULT_SAMPLE_LOG2,
            ok(swapped),
        );
        assert!(err.is_err(), "out-of-order keys must be refused");

        let mut duped = base.clone();
        duped[11] = duped[10];
        assert!(write_store(
            &out,
            &d.0,
            KeyKind::Wifi,
            keys::WIFI_UNIVERSE_BITS,
            duped.len() as u64,
            DEFAULT_SAMPLE_LOG2,
            ok(duped),
        )
        .is_err());
    }

    #[test]
    fn a_wrong_record_count_is_refused() {
        let d = TempDir::new("count");
        let recs = wifi_records(50, 11);
        let out = d.0.join("bad.wpsdb");
        assert!(write_store(
            &out,
            &d.0,
            KeyKind::Wifi,
            keys::WIFI_UNIVERSE_BITS,
            recs.len() as u64 + 1,
            DEFAULT_SAMPLE_LOG2,
            ok(recs.clone()),
        )
        .is_err());
        assert!(write_store(
            &out,
            &d.0,
            KeyKind::Wifi,
            keys::WIFI_UNIVERSE_BITS,
            recs.len() as u64 - 1,
            DEFAULT_SAMPLE_LOG2,
            ok(recs),
        )
        .is_err());
    }

    #[test]
    fn an_empty_store_is_writable_and_verifiable() {
        let d = TempDir::new("empty");
        let out = d.0.join("empty.wpsdb");
        write_store(
            &out,
            &d.0,
            KeyKind::Wifi,
            keys::WIFI_UNIVERSE_BITS,
            0,
            DEFAULT_SAMPLE_LOG2,
            ok(Vec::new()),
        )
        .unwrap();
        verify_store(&out, ok(Vec::new())).unwrap();
    }

    #[test]
    fn scratch_files_do_not_survive() {
        let d = TempDir::new("scratch");
        let recs = wifi_records(100, 3);
        let out = d.0.join("s.wpsdb");
        write_store(
            &out,
            &d.0,
            KeyKind::Wifi,
            keys::WIFI_UNIVERSE_BITS,
            recs.len() as u64,
            DEFAULT_SAMPLE_LOG2,
            ok(recs),
        )
        .unwrap();
        for name in ["wpsdb.high", "wpsdb.low", "wpsdb.payload"] {
            assert!(!d.0.join(name).exists(), "{name} was left behind");
        }
    }

    #[test]
    fn l_is_capped_so_low_always_fits_a_u64() {
        // A wide universe with few keys is where the cap bites.
        assert_eq!(choose_l(keys::CELL_UNIVERSE_BITS, 1000), MAX_L);
        // A realistic cell store stays under it.
        assert!(choose_l(keys::CELL_UNIVERSE_BITS, 50_000_000) < MAX_L);
        // And a WiFi store is nowhere near.
        assert!(choose_l(keys::WIFI_UNIVERSE_BITS, 1_000_000_000) < 32);
    }
}
