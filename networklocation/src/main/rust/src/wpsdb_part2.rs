#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use std::io::Write;

    // --- fixture builder ---------------------------------------------------
    //
    // A transcription of `wps_build`'s writer, kept here so `cargo test` is meaningful
    // without a multi-gigabyte store. The reader is checked against it two ways: `index()`
    // resolves the keys it was given, and `reconstruct()` walks the upper bitvector by a
    // different code path and must recover the same key list.

    struct BitWriter {
        buf: Vec<u8>,
        bits: u64,
    }
    impl BitWriter {
        fn new() -> BitWriter {
            BitWriter { buf: Vec::new(), bits: 0 }
        }
        fn push(&mut self, v: u64, nbits: u32) {
            for k in 0..nbits {
                if (self.bits & 7) == 0 {
                    self.buf.push(0);
                }
                if (v >> k) & 1 == 1 {
                    let p = self.bits;
                    self.buf[(p >> 3) as usize] |= 1 << (p & 7);
                }
                self.bits += 1;
            }
        }
        fn push_bit(&mut self, set: bool) {
            self.push(u64::from(set), 1);
        }
    }

    fn choose_l(universe_bits: u8, n: u64) -> u8 {
        if n == 0 {
            return universe_bits.min(MAX_L);
        }
        let u = 1u128 << universe_bits;
        let mut l = 0u8;
        while l < MAX_L && (u >> (l + 1)) >= n as u128 {
            l += 1;
        }
        l
    }

    /// Write a valid `WPSDB2` to `path`. `records` need not be sorted or unique. The altitude
    /// slot is written only for kind 2: `Some(metres)` lands in the record, `None` writes the
    /// unknown sentinel.
    fn write_fixture(
        path: &std::path::Path,
        universe_bits: u8,
        payload_kind: u8,
        records: &[(u128, i64, i64, u64, Option<i64>)],
        sample_log2: u8,
    ) {
        let mut recs = records.to_vec();
        recs.sort_by_key(|r| r.0);
        recs.dedup_by_key(|r| r.0);
        let n = recs.len() as u64;
        let l = choose_l(universe_bits, n);

        // Elias-Fano upper bitvector: for each key in order, a run of zeros advancing the
        // bucket, then a one.
        let mut high = BitWriter::new();
        let mut prev_bucket = 0u64;
        for &(key, ..) in &recs {
            let bucket = (key >> l) as u64;
            for _ in prev_bucket..bucket {
                high.push_bit(false);
            }
            high.push_bit(true);
            prev_bucket = bucket;
        }
        // Pad out the bucket space so `select0(hi - 1)` resolves for every reachable bucket.
        let buckets = 1u64 << (universe_bits as u32 - l as u32);
        for _ in prev_bucket..buckets {
            high.push_bit(false);
        }
        let high_len_bits = high.bits;

        let mut zeros: Vec<u64> = vec![0];
        let mut count = 0u64;
        for p in 0..high_len_bits {
            if (high.buf[(p >> 3) as usize] >> (p & 7)) & 1 == 0 {
                count += 1;
            }
            if ((p + 1) & ((1u64 << sample_log2) - 1)) == 0 {
                zeros.push(count);
            }
        }
        assert_eq!(zeros.len() as u64, (high_len_bits >> sample_log2) + 1);

        let mut low = BitWriter::new();
        for &(key, ..) in &recs {
            let lo = if l == 0 { 0 } else { (key & ((1u128 << l) - 1)) as u64 };
            low.push(lo, l as u32);
        }

        let mut payload = BitWriter::new();
        for &(_, lat_e8, lon_e8, acc, alt) in &recs {
            payload.push((lat_e8 + LAT_BIAS) as u64, LAT_BITS);
            payload.push((lon_e8 + LON_BIAS) as u64, LON_BITS);
            payload.push(acc, ACC_BITS);
            if payload_kind == PAYLOAD_LATLON_E8_ACC16_ALT16 {
                payload.push((alt.unwrap_or(-ALT_BIAS) + ALT_BIAS) as u64, ALT_BITS);
            }
        }

        let mut out: Vec<u8> = Vec::new();
        out.extend_from_slice(MAGIC);
        out.push(1); // key_kind
        out.push(universe_bits);
        out.push(payload_kind);
        out.push(l);
        out.push(sample_log2);
        out.extend_from_slice(&[0, 0, 0]);
        out.extend_from_slice(&n.to_le_bytes());
        out.extend_from_slice(&high_len_bits.to_le_bytes());
        out.extend_from_slice(&(high.buf.len() as u64).to_le_bytes());
        assert_eq!(out.len() as u64, HIGH_OFF);
        out.extend_from_slice(&high.buf);
        out.extend_from_slice(&((zeros.len() * 8) as u64).to_le_bytes());
        for z in &zeros {
            out.extend_from_slice(&z.to_le_bytes());
        }
        out.extend_from_slice(&(low.buf.len() as u64).to_le_bytes());
        out.extend_from_slice(&low.buf);
        out.extend_from_slice(&(payload.buf.len() as u64).to_le_bytes());
        out.extend_from_slice(&payload.buf);

        let mut f = File::create(path).unwrap();
        f.write_all(&out).unwrap();
    }

    struct Fixture {
        dir: std::path::PathBuf,
    }
    impl Fixture {
        fn new(name: &str) -> Fixture {
            let dir = std::env::temp_dir().join(format!("wpsdb2-{name}-{}", std::process::id()));
            std::fs::create_dir_all(&dir).unwrap();
            Fixture { dir }
        }
        fn path(&self) -> std::path::PathBuf {
            self.dir.join("store.wpsdb")
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.dir);
        }
    }

    /// Reconstruct every (index, key) pair by walking the Elias–Fano upper bitvector — the
    /// inverse of `index()` — so tests cross-check membership against a second code path.
    fn reconstruct(r: &Reader) -> Vec<u128> {
        let l = r.l as u32;
        let mut keys = Vec::with_capacity(r.n as usize);
        let mut idx = 0u64;
        let mut p = 0u64;
        while p < r.high_len_bits && idx < r.n {
            if r.high_bit(p) {
                let upper = (p - idx) as u128;
                let lo = r.low(idx).unwrap() as u128;
                keys.push((upper << l) | lo);
                idx += 1;
            }
            p += 1;
        }
        keys
    }

    /// Deterministic LCG so the tests need no rand dep.
    fn lcg(state: &mut u64) -> u64 {
        *state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        *state
    }

    fn wifi_records(count: usize) -> Vec<(u128, i64, i64, u64, Option<i64>)> {
        let mut state = 0x1234_5678_9abc_def0u64;
        (0..count)
            .map(|i| {
                let key = (lcg(&mut state) & ((1u64 << 48) - 1)) as u128;
                let lat = (lcg(&mut state) % 18_000_000_000) as i64 - LAT_BIAS;
                let lon = (lcg(&mut state) % 36_000_000_000) as i64 - LON_BIAS;
                let acc = if i % 17 == 0 { ACC_UNKNOWN } else { (i % 500) as u64 };
                let alt = if i % 13 == 0 { None } else { Some((i % 9000) as i64 - 500) };
                (key, lat, lon, acc, alt)
            })
            .collect()
    }

    // --- tests -------------------------------------------------------------

    #[test]
    fn header_is_parsed() {
        let f = Fixture::new("header");
        let recs = wifi_records(500);
        write_fixture(&f.path(), 48, PAYLOAD_LATLON_E8_ACC16_ALT16, &recs, 12);
        let r = Reader::open_path(f.path()).expect("fixture must open");
        assert_eq!(r.universe_bits, 48);
        assert!(r.n > 0 && r.n <= 500);
        assert!(r.high_len_bits >= r.n, "high bitvector shorter than key count");
        assert!(r.l <= MAX_L);
    }

    /// Every key round-trips through `index()`, and `lookup` decodes exactly the coordinate,
    /// accuracy and altitude the builder wrote — including both "unknown" sentinels.
    #[test]
    fn known_keys_resolve_exactly() {
        let f = Fixture::new("known");
        let mut recs = wifi_records(2000);
        recs.sort_by_key(|r| r.0);
        recs.dedup_by_key(|r| r.0);
        write_fixture(&f.path(), 48, PAYLOAD_LATLON_E8_ACC16_ALT16, &recs, 12);
        let r = Reader::open_path(f.path()).unwrap();

        assert_eq!(reconstruct(&r), recs.iter().map(|x| x.0).collect::<Vec<_>>());

        for (i, &(key, lat_e8, lon_e8, acc, alt)) in recs.iter().enumerate() {
            assert_eq!(r.index(key), Some(i as u64), "index mismatch for {key:#x}");
            let (lat, lon, got_acc, got_alt) = r.lookup(key).expect("known key resolves");
            // e8 is exact in f64 (well under 2^53), so this is an equality check, not epsilon.
            assert_eq!(lat, lat_e8 as f64 / COORD_SCALE, "lat mismatch for {key:#x}");
            assert_eq!(lon, lon_e8 as f64 / COORD_SCALE, "lon mismatch for {key:#x}");
            if acc == ACC_UNKNOWN {
                assert!(got_acc < 0.0, "unknown accuracy must be negative");
            } else {
                assert_eq!(got_acc, acc as f64, "accuracy mismatch for {key:#x}");
            }
            match alt {
                Some(m) => assert_eq!(got_alt, m as f64, "altitude mismatch for {key:#x}"),
                None => assert!(got_alt.is_nan(), "unknown altitude must be NaN for {key:#x}"),
            }
            assert!((-90.0..=90.0).contains(&lat));
            assert!((-180.0..=180.0).contains(&lon));
        }
    }

    /// Kind-1 stores (no altitude slot) stay readable; their altitude reports as NaN.
    #[test]
    fn kind1_stores_read_with_nan_altitude() {
        let f = Fixture::new("kind1");
        let recs = wifi_records(500);
        write_fixture(&f.path(), 48, PAYLOAD_LATLON_E8_ACC16, &recs, 12);
        let r = Reader::open_path(f.path()).unwrap();
        for &(key, lat_e8, lon_e8, acc, _) in &recs {
            let (lat, lon, got_acc, got_alt) = r.lookup(key).expect("known key resolves");
            assert_eq!(lat, lat_e8 as f64 / COORD_SCALE);
            assert_eq!(lon, lon_e8 as f64 / COORD_SCALE);
            if acc != ACC_UNKNOWN {
                    assert_eq!(got_acc, acc as f64);
            }
            assert!(got_alt.is_nan(), "kind 1 has no altitude slot");
        }
    }

    /// Random unknown keys are all rejected — zero false positives.
    #[test]
    fn unknown_keys_rejected() {
        let f = Fixture::new("unknown");
        let recs = wifi_records(2000);
        write_fixture(&f.path(), 48, PAYLOAD_LATLON_E8_ACC16_ALT16, &recs, 12);
        let r = Reader::open_path(f.path()).unwrap();

        let known: HashMap<u128, ()> = reconstruct(&r).into_iter().map(|k| (k, ())).collect();
        let mut state = 0xdead_beef_cafe_babeu64;
        let mut fp = 0;
        for _ in 0..50_000 {
            let key = (lcg(&mut state) & ((1u64 << 48) - 1)) as u128;
            if !known.contains_key(&key) && r.lookup(key).is_some() {
                fp += 1;
            }
        }
        assert_eq!(fp, 0, "expected zero false positives, got {fp}");
    }

    /// The 84-bit cell keyspace exercises the u128 path and the `MAX_L` cap, which a 48-bit
    /// universe never reaches.
    #[test]
    fn cell_scale_keys_resolve() {
        let f = Fixture::new("cell");
        let mut state = 0x0f0f_0f0f_0f0f_0f0fu64;
        let mut recs: Vec<(u128, i64, i64, u64, Option<i64>)> = (0..1000)
            .map(|_| {
                let hi = (lcg(&mut state) & ((1u64 << 20) - 1)) as u128;
                let key = (hi << 64) | lcg(&mut state) as u128;
                (key, 37_77_000_000i64, -122_41_000_000i64, 250, None)
            })
            .collect();
        recs.sort_by_key(|r| r.0);
        recs.dedup_by_key(|r| r.0);
        write_fixture(&f.path(), 84, PAYLOAD_LATLON_E8_ACC16_ALT16, &recs, 12);

        let r = Reader::open_path(f.path()).unwrap();
        assert_eq!(r.l, MAX_L, "an 84-bit universe at this n must hit the l cap");
        for (i, &(key, ..)) in recs.iter().enumerate() {
            assert_eq!(r.index(key), Some(i as u64), "cell key {key:#x} rejected");
        }
        let (lat, lon, acc, alt) = r.lookup(recs[0].0).unwrap();
        assert_eq!((lat, lon, acc), (37.77, -122.41, 250.0));
        assert!(alt.is_nan(), "cell records carry no altitude");
    }

    /// Extremes must survive the bias, and a store whose payload straddles byte boundaries
    /// (87 and 103 bits are both coprime with 8) must decode every record, not just aligned ones.
    #[test]
    fn coordinate_extremes_and_bit_alignment() {
        let f = Fixture::new("extremes");
        let recs: Vec<(u128, i64, i64, u64, Option<i64>)> = vec![
            (1, -LAT_BIAS, -LON_BIAS, 0, Some(-ALT_BIAS + 1)),
            (2, LAT_BIAS, LON_BIAS, 65534, Some(ALT_BIAS - 1)),
            (3, 0, 0, ACC_UNKNOWN, None),
            (4, 1, -1, 1, Some(0)),
            (5, -LAT_BIAS + 1, LON_BIAS - 1, 12345, Some(-430)),
            (6, 45_00_000_001, -93_26_543_210, 7, Some(8848)),
        ];
        write_fixture(&f.path(), 48, PAYLOAD_LATLON_E8_ACC16_ALT16, &recs, 6);
        let r = Reader::open_path(f.path()).unwrap();
        for &(key, lat_e8, lon_e8, acc, alt) in &recs {
            let (lat, lon, got, got_alt) = r.lookup(key).expect("key resolves");
            assert_eq!(lat, lat_e8 as f64 / COORD_SCALE);
            assert_eq!(lon, lon_e8 as f64 / COORD_SCALE);
            if acc == ACC_UNKNOWN {
                assert!(got < 0.0);
            } else {
                assert_eq!(got, acc as f64);
            }
            match alt {
                Some(m) => assert_eq!(got_alt, m as f64, "altitude mismatch for key {key}"),
                None => assert!(got_alt.is_nan()),
            }
        }
        assert_eq!(r.lookup(1).unwrap().0, -90.0);
        assert_eq!(r.lookup(2).unwrap().1, 180.0);
    }

    /// Cross-check against a store built by `wps_harvest` rather than by the fixture writer
    /// above.
    ///
    /// The fixture writer is a transcription of `wps_build`'s, which means the round-trip
    /// tests would still pass if both sides shared a misunderstanding of the format. Pointing
    /// `WPSDB2_TEST` at a real store closes that gap, and is also how a planet build gets
    /// smoke-tested before it is published:
    ///
    /// ```sh
    /// WPSDB2_TEST=/path/to/wifi-v2.wpsdb cargo test -p ... wpsdb -- --nocapture
    /// ```
    ///
    /// Walking the bitvector is cheap (it is mmap'd), but every key's low bits and payload
    /// cost a positional read, so a store with hundreds of millions of records is sampled
    /// rather than reconstructed whole.
    #[test]
    fn a_real_store_opens_and_resolves() {
        let Ok(path) = std::env::var("WPSDB2_TEST") else {
            eprintln!("skip: set WPSDB2_TEST to a .wpsdb built by wps_build");
            return;
        };
        let r = Reader::open_path(&path)
            .unwrap_or_else(|| panic!("{path} exists but failed the header check"));
        assert!(r.n > 0, "a published store must not be empty");
        assert!(r.high_len_bits >= r.n);

        const SAMPLES: u64 = 3000;
        let stride = (r.n / SAMPLES).max(1);

        let l = r.l as u32;
        let mut checked = 0u64;
        let mut prev_key: Option<u128> = None;
        let mut idx = 0u64;
        let mut p = 0u64;
        while p < r.high_len_bits && idx < r.n {
            if r.high_bit(p) {
                if idx % stride == 0 {
                    // Recover this key the long way round — from the bitvector — then check
                    // `index()` finds its way back to the same slot.
                    let upper = (p - idx) as u128;
                    let lo = r.low(idx).expect("low bits readable") as u128;
                    let key = (upper << l) | lo;

                    assert!(key < (1u128 << r.universe_bits), "key {key:#x} escapes the universe");
                    if let Some(prev) = prev_key {
                        assert!(prev < key, "keys are not strictly ascending");
                    }
                    prev_key = Some(key);

                    assert_eq!(
                        r.index(key),
                        Some(idx),
                        "known key {key:#x} did not resolve to its own index"
                    );
                    let (lat, lon, acc, alt) = r.lookup(key).expect("known key resolves");
                    assert!((-90.0..=90.0).contains(&lat), "lat out of range: {lat}");
                    assert!((-180.0..=180.0).contains(&lon), "lon out of range: {lon}");
                    assert!(acc < 0.0 || (0.0..=65534.0).contains(&acc), "implausible acc: {acc}");
                    assert!(alt.is_nan() || (-32767.0..=32767.0).contains(&alt), "implausible alt: {alt}");
                    checked += 1;
                }
                idx += 1;
            }
            p += 1;
        }
        assert_eq!(idx, r.n, "bitvector holds {idx} keys, header says {}", r.n);
        assert!(checked > 0, "nothing was sampled");
        eprintln!(
            "{path}: {} records, universe {} bits, l={}, {checked} sampled",
            r.n, r.universe_bits, r.l
        );
    }

    /// A store from the previous format must be refused outright rather than mis-decoded:
    /// its records are 41-bit grid codes, so reading them as 87-bit e8 records would return
    /// plausible-looking but wrong coordinates.
    #[test]
    fn wpsdb1_is_rejected() {
        let f = Fixture::new("v1");
        let mut bytes = b"WPSDB1\x00\x00".to_vec();
        bytes.extend_from_slice(&41u32.to_le_bytes());
        bytes.extend_from_slice(&[0u8; 64]);
        std::fs::write(f.path(), &bytes).unwrap();
        assert!(Reader::open_path(f.path()).is_none(), "WPSDB1 must not open as WPSDB2");
    }

    #[test]
    fn truncated_store_is_rejected() {
        let f = Fixture::new("trunc");
        let recs = wifi_records(300);
        write_fixture(&f.path(), 48, PAYLOAD_LATLON_E8_ACC16_ALT16, &recs, 12);
        let full = std::fs::read(f.path()).unwrap();
        for cut in [8usize, 24, 40, full.len() / 2, full.len() - 1] {
            std::fs::write(f.path(), &full[..cut]).unwrap();
            assert!(
                Reader::open_path(f.path()).is_none(),
                "a store truncated to {cut} bytes must not open"
            );
        }
    }
}
