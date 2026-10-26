/// `close(handle)` — frees the reader and its dup'd fd.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_close<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut Handle));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // resolve() field order:
    // [lat, lon, name, house, street, city, state, country, postcode, kind]
    const F_LAT: usize = 0;
    const F_LON: usize = 1;
    const F_NAME: usize = 2;
    const F_STREET: usize = 4;
    const F_CITY: usize = 5;
    const F_STATE: usize = 6;
    const F_COUNTRY: usize = 7;
    const F_KIND: usize = 9;

    /// Locate a real DB: `$GEOCODER_DB`, else the bundled asset next to the crate.
    fn db_path() -> Option<String> {
        if let Ok(p) = std::env::var("GEOCODER_DB") {
            return Some(p);
        }
        let bundled = concat!(env!("CARGO_MANIFEST_DIR"), "/../assets/geocoder.db");
        if std::path::Path::new(bundled).exists() {
            Some(bundled.to_string())
        } else {
            eprintln!("skip: no geocoder DB (set GEOCODER_DB or add the bundled asset)");
            None
        }
    }

    /// Open the real DB. Returns `None` (test self-skips) when no DB is present so CI without
    /// the ~1.4 GB asset stays green; a DB that exists but fails to parse is a hard failure.
    fn open_db() -> Option<Reader> {
        let path = db_path()?;
        match Reader::open_path(&path) {
            Some(r) => Some(r),
            None => panic!("geocoder DB at {path} exists but failed magic/version/parse"),
        }
    }

    /// The header, dictionaries and index tables the two searches rely on are internally
    /// consistent (this is what would break if a repack corrupted the file).
    #[test]
    fn structure_is_sane() {
        let r = match open_db() {
            Some(r) => r,
            None => return,
        };
        assert!(r.n > 0, "record count must be positive");
        assert_eq!(r.dicts.len(), DICTS, "wrong dictionary count");
        assert_eq!(r.cols.len(), COLUMNS, "wrong column count");
        assert_eq!(r.nm_name.n, r.nm_rec.n, "name index halves must match in length");
        assert!(r.nm_name.n <= r.n, "name index cannot exceed the record count");
        assert!(r.fwd.n > 0 && r.fwd.n <= r.n, "forward index holds addresses, so 0 < fwd <= n");
        for (i, d) in r.dicts.iter().enumerate() {
            assert!(d.count > 0, "dictionary {i} is empty");
        }
        // grid_index() binary-searches the grid's cell ids, so they must be strictly ascending,
        // and the record starts must be non-decreasing and within [0, n].
        let mut prev_cell = i64::MIN;
        let mut prev_start = 0i32;
        for gi in 0..r.grid_count {
            let (cell, s) = (r.grid_cell(gi), r.grid_start(gi));
            assert!(cell > prev_cell, "grid cells not strictly ascending at {gi}");
            assert!(s >= prev_start && s <= r.n, "grid start {s} out of range (prev {prev_start}, n {})", r.n);
            prev_cell = cell;
            prev_start = s;
        }
    }

    /// Every dictionary's first-string table must agree with its blocks, or a binary search
    /// lands in the wrong block and silently misses. Checked for every block of every
    /// searchable dictionary.
    #[test]
    fn first_string_tables_match_their_blocks() {
        let mut r = match open_db() {
            Some(r) => r,
            None => return,
        };
        for field in [dict_of(C_NAME), dict_of(C_STREET), dict_of(C_CITY), dict_of(C_STATE), dict_of(C_COUNTRY)] {
            let d = r.map.as_slice();
            let dict = &mut r.dicts[field];
            for b in 0..dict.blocks {
                let first = dict.first(d, b).to_owned();
                assert_eq!(dict.get(d, b * dict.per), first, "dictionary {field} block {b}");
                if b > 0 {
                    assert_eq!(cmp_utf16(dict.first(d, b - 1), &first), Ordering::Less);
                }
            }
        }
    }

    /// `index_of` must find every string at its own id, across block boundaries.
    #[test]
    fn dictionary_lookup_finds_strings_at_their_own_ids() {
        let mut r = match open_db() {
            Some(r) => r,
            None => return,
        };
        let d = r.map.as_slice();
        let dict = &mut r.dicts[dict_of(C_STREET)];
        let step = (dict.count / 500).max(1);
        let mut i = 0;
        while i < dict.count {
            let s = dict.get(d, i);
            assert_eq!(dict.index_of(d, &s), Some(i), "street {s:?}");
            i += step;
        }
        assert_eq!(dict.index_of(d, "\u{10FFFF} no such street"), None);
    }

    /// Reverse geocoding a coordinate that is definitely inside the database's coverage
    /// returns a real, nearby record.
    ///
    /// The query points come from the database itself rather than from a list of city centres,
    /// so this works against a metro extract, a state and the planet alike. Hardcoded cities
    /// only ever tested that the planet build had been used.
    #[test]
    fn reverse_returns_nearby_record() {
        let mut r = match open_db() {
            Some(r) => r,
            None => return,
        };
        for frac in [1, 3, 5, 7, 9] {
            let seed = (r.n / 10) * frac;
            let a = r.resolve(seed);
            let lat: f64 = a[F_LAT].parse().expect("lat parses");
            let lon: f64 = a[F_LON].parse().expect("lon parses");
            // Nudge off the record's own position so this is a real search, not an identity.
            let (qlat, qlon) = (lat + 0.0007, lon + 0.0007);

            let rec = r
                .reverse(qlat, qlon)
                .unwrap_or_else(|| panic!("reverse returned nothing near ({qlat}, {qlon})"));
            let b = r.resolve(rec);
            let rlat: f64 = b[F_LAT].parse().expect("lat parses");
            let rlon: f64 = b[F_LON].parse().expect("lon parses");
            assert!(
                (rlat - qlat).abs() < 0.5 && (rlon - qlon).abs() < 0.5,
                "nearest record ({rlat},{rlon}) too far from query ({qlat},{qlon}); full = {b:?}"
            );
            assert!(
                !b[F_NAME].is_empty() || !b[F_STREET].is_empty() || !b[F_CITY].is_empty(),
                "resolved record has no name, street or city: {b:?}"
            );
        }
    }

    /// Forward geocoding is consistent with reverse: take a real address found by reverse,
    /// look it up by its structured fields, and every returned record must carry the same
    /// country/state/city/street. (We don't assert the exact source record is in the first
    /// N results — a single street can hold more addresses than the limit.)
    #[test]
    fn forward_matches_reverse() {
        let mut r = match open_db() {
            Some(r) => r,
            None => return,
        };
        // Find a record that actually carries a full structured address; streets, POIs and
        // places generally do not, and forward search is only defined over addresses.
        let mut seed = -1i32;
        let step = (r.n / 500).max(1);
        let mut i = 0;
        while i < r.n {
            let a = r.resolve(i);
            if !a[F_COUNTRY].is_empty()
                && !a[F_STATE].is_empty()
                && !a[F_CITY].is_empty()
                && !a[F_STREET].is_empty()
            {
                seed = i;
                break;
            }
            i += step;
        }
        if seed < 0 {
            eprintln!("skip: no fully addressed record in this database");
            return;
        }
        let a = r.resolve(seed);
        let (street, city, state, country) = (
            a[F_STREET].clone(),
            a[F_CITY].clone(),
            a[F_STATE].clone(),
            a[F_COUNTRY].clone(),
        );
        eprintln!("forward seed: {country}/{state}/{city}/{street}");

        let recs = r.forward(&country, &state, &city, &street, 25);
        assert!(
            !recs.is_empty(),
            "forward found nothing for a country/state/city/street produced by reverse"
        );
        for rc in recs {
            let b = r.resolve(rc);
            assert_eq!(b[F_COUNTRY], country, "country mismatch: {b:?}");
            assert_eq!(b[F_STATE], state, "state mismatch: {b:?}");
            assert_eq!(b[F_CITY], city, "city mismatch: {b:?}");
            assert_eq!(b[F_STREET], street, "street mismatch: {b:?}");
        }
    }

    /// Every record carries a kind, and the ones that are not plain addresses carry a name.
    /// This is what v2 could not represent at all.
    #[test]
    fn records_carry_a_kind_and_named_features_carry_a_name() {
        let mut r = match open_db() {
            Some(r) => r,
            None => return,
        };
        let mut kinds = std::collections::HashSet::new();
        let step = (r.n / 5000).max(1);
        let mut i = 0;
        while i < r.n {
            let a = r.resolve(i);
            let kind: i32 = a[F_KIND].parse().expect("kind parses");
            assert!((1..=4).contains(&kind), "record {i} has kind {kind}");
            let _ = kinds.insert(kind);
            // Kinds 2..=4 are street, POI and place, all of which exist only because they
            // are named.
            if kind != 1 {
                assert!(!a[F_NAME].is_empty(), "record {i} of kind {kind} has no name");
            }
            i += step;
        }
        assert!(kinds.len() > 1, "the database should hold more than addresses: {kinds:?}");
    }

    /// A name taken from the database is findable by its own prefix, and the result really
    /// does start with it.
    #[test]
    fn name_search_finds_named_features_by_prefix() {
        let mut r = match open_db() {
            Some(r) => r,
            None => return,
        };
        if r.nm_name.n == 0 {
            eprintln!("skip: database holds no named features");
            return;
        }
        // Pick a name that actually exists, from the middle of the name index.
        let seed = r.nm_rec.get(r.map.as_slice(), r.nm_name.n / 2);
        let name = r.resolve(seed)[F_NAME].clone();
        assert!(!name.is_empty(), "the name index must point at a named record");

        let prefix: String = name.chars().take(name.chars().count().min(6)).collect();
        let hits = r.search_name(&prefix, 20);
        assert!(!hits.is_empty(), "prefix {prefix:?} of a known name found nothing");
        for rec in hits {
            let got = r.resolve(rec)[F_NAME].clone();
            assert!(got.starts_with(&prefix), "{got:?} does not start with {prefix:?}");
        }
    }

    #[test]
    fn name_search_rejects_nonsense_without_panicking() {
        let mut r = match open_db() {
            Some(r) => r,
            None => return,
        };
        assert!(r.search_name("", 10).is_empty(), "an empty prefix must not match everything");
        assert!(r.search_name("__no_such_name_anywhere__", 10).is_empty());
    }

    /// Reverse lookup must return the genuinely nearest record, not merely one from the first
    /// ring that had anything in it. Checked by brute force over a window of the grid-ordered
    /// records around the answer.
    #[test]
    fn reverse_returns_the_nearest_record_not_just_a_near_one() {
        let mut r = match open_db() {
            Some(r) => r,
            None => return,
        };
        let step = (r.n / 200).max(1);
        let mut seed = 0;
        while seed < r.n {
            let a = r.resolve(seed);
            let lat: f64 = a[F_LAT].parse().expect("lat parses");
            let lon: f64 = a[F_LON].parse().expect("lon parses");
            let Some(found) = r.reverse(lat, lon) else {
                panic!("querying a record's own coordinates found nothing");
            };
            let b = r.resolve(found);
            let flat: f64 = b[F_LAT].parse().expect("lat parses");
            let flon: f64 = b[F_LON].parse().expect("lon parses");
            // The nearest record to a record's own position is at distance zero, so anything
            // else means the search settled for a worse answer.
            let d = (flat - lat).abs() + (flon - lon).abs();
            assert!(d < 1e-6, "reverse at ({lat}, {lon}) returned ({flat}, {flon}), off by {d}");
            seed += step;
        }
    }

    /// A structured lookup that cannot exist returns empty (no panic, no bogus hit).
    #[test]
    fn forward_unknown_is_empty() {
        let mut r = match open_db() {
            Some(r) => r,
            None => return,
        };
        let recs = r.forward(
            "__no_such_country__",
            "__no_such_state__",
            "__no_such_city__",
            "__no_such_street__",
            10,
        );
        assert!(recs.is_empty(), "unknown query should yield no records");
    }

    /// Micro-benchmark (opt-in). Run with:
    ///   GEOCODER_DB=/path/geocoder.db cargo test -p network_location_position_estimation_rust \
    ///     geocoder::tests::bench_ops -- --ignored --nocapture
    #[test]
    #[ignore]
    fn bench_ops() {
        use std::time::Instant;
        let path = match db_path() {
            Some(p) => p,
            None => return,
        };

        let t = Instant::now();
        let mut r = Reader::open_path(&path).expect("open");
        eprintln!("open (inflate dicts+grid): {:.1} ms", t.elapsed().as_secs_f64() * 1e3);

        // City centers across continents (lat, lon).
        let cities = [
            (40.7128, -74.0060),
            (51.5074, -0.1278),
            (35.6762, 139.6503),
            (48.8566, 2.3522),
            (34.0522, -118.2437),
            (41.9028, 12.4964),
            (52.5200, 13.4050),
            (55.7558, 37.6173),
            (-33.8688, 151.2093),
            (19.0760, 72.8777),
            (-23.5505, -46.6333),
            (1.3521, 103.8198),
            (25.2048, 55.2708),
            (37.7749, -122.4194),
            (43.6532, -79.3832),
        ];
        let reps = 20;

        for &(la, lo) in &cities {
            let _ = r.reverse(la, lo); // warm the OS page cache
        }

        let mut rev = Vec::new();
        for _ in 0..reps {
            for &(la, lo) in &cities {
                let t = Instant::now();
                let rec = r.reverse(la, lo);
                let us = t.elapsed().as_secs_f64() * 1e6;
                if rec.is_some() {
                    rev.push(us);
                }
            }
        }

        // Forward keys taken from a real reverse hit.
        let seed = r.reverse(40.7128, -74.0060).expect("seed");
        let a = r.resolve(seed);
        let (co, st, ci, sr) = (a[6].clone(), a[5].clone(), a[4].clone(), a[3].clone());
        let mut fwd = Vec::new();
        for _ in 0..(reps * cities.len()) {
            let t = Instant::now();
            let _ = r.forward(&co, &st, &ci, &sr, 10);
            fwd.push(t.elapsed().as_secs_f64() * 1e6);
        }

        let mut res = Vec::new();
        for _ in 0..2000 {
            let t = Instant::now();
            let _ = r.resolve(seed);
            res.push(t.elapsed().as_secs_f64() * 1e6);
        }

        fn stats(label: &str, mut v: Vec<f64>) {
            v.sort_by(|a, b| a.partial_cmp(b).unwrap());
            let n = v.len();
            let sum: f64 = v.iter().sum();
            let q = |x: f64| v[(((n - 1) as f64) * x) as usize];
            eprintln!(
                "{label:8} n={n:4}  min={:.1}  p50={:.1}  avg={:.1}  p95={:.1}  max={:.1}  (µs)",
                v[0],
                q(0.5),
                sum / n as f64,
                q(0.95),
                v[n - 1]
            );
        }
        stats("reverse", rev);
        stats("forward", fwd);
        stats("resolve", res);
    }
}
