//! Self-checks for [`crate::write`], extracted wholesale so `write.rs` stays small.
//!
//! Kept as a sibling module so production callers keep using `crate::write::*`
//! with no path changes; only the test imports point at `crate::write`.

#[cfg(test)]
mod tests {
    use crate::extract::{Row, Strings};
    use crate::format::*;
    use crate::write::{decode_column, decode_dict, verify, write};

    struct TempDir(std::path::PathBuf);
    impl TempDir {
        fn new(tag: &str) -> TempDir {
            let p = std::env::temp_dir().join(format!("geodb-{tag}-{}", std::process::id()));
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

    /// Build rows the way extraction does, so the test exercises the same interning path the
    /// real build uses rather than a shortcut around it.
    fn sample_rows(count: usize) -> (Vec<Row>, Strings) {
        let mut strings = Strings::default();
        let mut state = 0x1234_5678u64;
        let mut next = || {
            state = state.wrapping_mul(6364136223846793005).wrapping_add(1);
            state
        };
        let rows = (0..count)
            .map(|i| {
                let kind = match i % 4 {
                    0 => K_ADDRESS,
                    1 => K_STREET,
                    2 => K_POI,
                    _ => K_PLACE,
                };
                let name =
                    if kind == K_ADDRESS { String::new() } else { format!("Feature {}", i % 977) };
                let house = if kind == K_ADDRESS { format!("{}", i % 200) } else { String::new() };
                let street =
                    if kind == K_ADDRESS { format!("Street {}", i % 313) } else { String::new() };
                let ids = [
                    strings.dicts[D_NAME].intern(&name),
                    strings.dicts[D_HOUSE].intern(&house),
                    strings.dicts[D_STREET].intern(&street),
                    strings.dicts[D_CITY].intern(&format!("City {}", i % 47)),
                    strings.dicts[D_STATE].intern(&format!("State {}", i % 11)),
                    strings.dicts[D_COUNTRY].intern(["US", "GB", "DE", "JP"][i % 4]),
                    strings.dicts[D_POSTCODE].intern(&format!("{:05}", i % 9000)),
                ];
                Row {
                    lat: (next() % 18_000_000) as i64 as i32 - 9_000_000,
                    lon: ((next() % 36_000_000) as i64 - 18_000_000) as i32,
                    kind,
                    ids,
                }
            })
            .collect();
        (rows, strings)
    }

    /// Split a finished database into its sections.
    fn sections(buf: &[u8]) -> Vec<&[u8]> {
        let mut cursor = 12usize;
        let mut out = Vec::new();
        for _ in 0..SECTIONS {
            let size = u32::from_be_bytes(buf[cursor..cursor + 4].try_into().unwrap()) as usize;
            cursor += 4;
            out.push(&buf[cursor..cursor + size]);
            cursor += size;
        }
        out
    }

    /// A street sample named `name` at an e5 position.
    fn street(strings: &mut Strings, name: &str, lat: i32, lon: i32) -> Row {
        Row {
            lat,
            lon,
            kind: K_STREET,
            ids: [
                strings.dicts[D_NAME].intern(name),
                strings.dicts[D_HOUSE].intern(""),
                strings.dicts[D_STREET].intern(""),
                strings.dicts[D_CITY].intern(""),
                strings.dicts[D_STATE].intern(""),
                strings.dicts[D_COUNTRY].intern(""),
                strings.dicts[D_POSTCODE].intern(""),
            ],
        }
    }

    #[test]
    fn a_database_round_trips_through_the_verifier() {
        let d = TempDir::new("roundtrip");
        let (mut rows, mut strings) = sample_rows(BLOCK * 2 + 123);
        let out = d.0.join("geocoder.db");
        let report = write(&out, &mut rows, &mut strings).unwrap();
        assert_eq!(report.n, rows.len());
        assert!(report.total > 0);
        // `write` sorts in place and rewrites ids, so `rows` is now exactly what the file
        // should contain.
        verify(&out, &rows).unwrap();
    }

    #[test]
    fn records_come_out_in_grid_then_z_order() {
        let d = TempDir::new("order");
        let (mut rows, mut strings) = sample_rows(2000);
        let out = d.0.join("g.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();
        for w in rows.windows(2) {
            let (a, b) = (&w[0], &w[1]);
            let ca = cell_id(a.lat, a.lon);
            let cb = cell_id(b.lat, b.lon);
            assert!(ca <= cb, "cells out of order");
            if ca == cb {
                assert!(
                    morton_in_cell(a.lat, a.lon) <= morton_in_cell(b.lat, b.lon),
                    "z-order broken inside a cell"
                );
            }
        }
    }

    #[test]
    fn two_runs_produce_identical_bytes() {
        let d = TempDir::new("determinism");
        let (mut a, mut sa) = sample_rows(1500);
        let (mut b, mut sb) = sample_rows(1500);
        // Feed the second run in a different order; the sort must erase the difference.
        b.reverse();
        let pa = d.0.join("a.geodb");
        let pb = d.0.join("b.geodb");
        let _ = write(&pa, &mut a, &mut sa).unwrap();
        let _ = write(&pb, &mut b, &mut sb).unwrap();
        assert_eq!(std::fs::read(&pa).unwrap(), std::fs::read(&pb).unwrap());
    }

    #[test]
    fn unicode_survives_the_dictionaries() {
        let d = TempDir::new("unicode");
        let mut strings = Strings::default();
        let mut rows: Vec<Row> = ["Caf\u{e9} de Flore", "\u{6771}\u{4eac}\u{99c5}", "\u{395}\u{3bb}\u{3bb}", "\u{1f3d4} Peak", "\u{cd}safj\u{f6}r\u{f0}ur"]
            .iter()
            .enumerate()
            .map(|(i, name)| Row {
                lat: 3_700_000 + i as i32 * 10,
                lon: -12_200_000,
                kind: K_POI,
                ids: [
                    strings.dicts[D_NAME].intern(name),
                    strings.dicts[D_HOUSE].intern(""),
                    strings.dicts[D_STREET].intern(""),
                    strings.dicts[D_CITY].intern("Somewhere"),
                    strings.dicts[D_STATE].intern(""),
                    strings.dicts[D_COUNTRY].intern("US"),
                    strings.dicts[D_POSTCODE].intern(""),
                ],
            })
            .collect();
        let out = d.0.join("u.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();
        verify(&out, &rows).unwrap();

        // The names must survive as bytes, not merely as ids.
        let buf = std::fs::read(&out).unwrap();
        let size = u32::from_be_bytes(buf[12..16].try_into().unwrap()) as usize;
        let names = decode_dict(&buf[16..16 + size]).unwrap();
        assert!(names.iter().any(|s| s == "\u{6771}\u{4eac}\u{99c5}"), "{names:?}");
        assert!(names.iter().any(|s| s == "\u{1f3d4} Peak"), "{names:?}");
    }

    #[test]
    fn the_name_index_covers_named_records_and_excludes_the_rest() {
        let d = TempDir::new("nameidx");
        let (mut rows, mut strings) = sample_rows(500);
        let out = d.0.join("n.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();
        verify(&out, &rows).unwrap();

        let mut street_keys = std::collections::HashSet::new();
        let named = rows
            .iter()
            .filter(|r| match r.kind {
                K_ADDRESS => false,
                K_STREET => street_keys.insert((cell_id(r.lat, r.lon), r.ids[D_NAME])),
                _ => true,
            })
            .count();
        let buf = std::fs::read(&out).unwrap();
        let nm_rec = decode_column(sections(&buf)[DICTS + COLUMNS + 3], false).unwrap();
        assert_eq!(nm_rec.len(), named, "every named record must be indexed, and only those");
    }

    #[test]
    fn a_street_is_name_indexed_once_per_cell() {
        let d = TempDir::new("streetidx");
        let mut strings = Strings::default();
        // Three samples of one street inside one cell, and a fourth a cell to the north.
        let mut rows = vec![
            street(&mut strings, "Elm St", 3_700_010, -12_200_010),
            street(&mut strings, "Elm St", 3_700_200, -12_200_010),
            street(&mut strings, "Elm St", 3_700_400, -12_200_010),
            street(&mut strings, "Elm St", 3_700_010 + CELL as i32, -12_200_010),
        ];
        let out = d.0.join("s.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();
        verify(&out, &rows).unwrap();
        let buf = std::fs::read(&out).unwrap();
        let nm_rec = decode_column(sections(&buf)[DICTS + COLUMNS + 3], false).unwrap();
        assert_eq!(rows.len(), 4, "every sample stays a record for reverse lookup");
        assert_eq!(nm_rec.len(), 2, "one name entry per cell: {nm_rec:?}");
    }

    #[test]
    fn the_forward_index_holds_exactly_the_records_with_a_street() {
        let d = TempDir::new("fwd");
        let (mut rows, mut strings) = sample_rows(800);
        let out = d.0.join("f.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();
        verify(&out, &rows).unwrap();
        let buf = std::fs::read(&out).unwrap();
        let fwd = decode_column(sections(&buf)[DICTS + COLUMNS + 1], true).unwrap();
        let streets = decode_dict(sections(&buf)[D_STREET]).unwrap();
        let has_street = |i: usize| !streets[rows[i].ids[D_STREET] as usize].is_empty();
        let expected = (0..rows.len()).filter(|&i| has_street(i)).count();
        assert!(expected > 0 && expected < rows.len(), "premise: some records lack a street");
        assert_eq!(fwd.len(), expected);
        assert!(fwd.iter().all(|&i| has_street(i as usize)));
    }

    #[test]
    fn a_place_with_a_full_address_but_no_house_number_is_forward_indexed() {
        // Amundsen-Scott South Pole Station is a place tagged with street/city/state/country
        // and no house number. An address-only forward index made it unfindable.
        let d = TempDir::new("fwdplace");
        let mut strings = Strings::default();
        let mut place = street(&mut strings, "South Pole Station", -9_000_000, 0);
        place.kind = K_PLACE;
        place.ids[D_STREET] = strings.dicts[D_STREET].intern("PSC 798");
        place.ids[D_CITY] = strings.dicts[D_CITY].intern("APO");
        place.ids[D_STATE] = strings.dicts[D_STATE].intern("AP");
        place.ids[D_COUNTRY] = strings.dicts[D_COUNTRY].intern("US");
        let lone = street(&mut strings, "Elm St", 3_700_010, -12_200_010);
        let mut rows = vec![place, lone];
        let out = d.0.join("p.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();
        verify(&out, &rows).unwrap();
        let buf = std::fs::read(&out).unwrap();
        let fwd = decode_column(sections(&buf)[DICTS + COLUMNS + 1], true).unwrap();
        let place_at = rows.iter().position(|r| r.kind == K_PLACE).unwrap() as i32;
        assert_eq!(fwd, vec![place_at], "the place is indexed; the bare street sample is not");
    }

    #[test]
    fn identical_rows_are_written_once() {
        let d = TempDir::new("dedup");
        let mut strings = Strings::default();
        let a = street(&mut strings, "Elm St", 3_700_010, -12_200_010);
        let b = street(&mut strings, "Oak St", 3_700_010, -12_200_010);
        // Two ways of Elm St meeting at a shared node, plus a different street at that node.
        let mut rows = vec![a, b, a];
        let out = d.0.join("d.geodb");
        let report = write(&out, &mut rows, &mut strings).unwrap();
        assert_eq!(report.n, 2, "the shared end point must be stored once");
        assert_eq!(rows.len(), 2);
        verify(&out, &rows).unwrap();
    }

    #[test]
    fn a_single_record_database_is_valid() {
        let d = TempDir::new("one");
        let (mut rows, mut strings) = sample_rows(1);
        let out = d.0.join("one.geodb");
        let report = write(&out, &mut rows, &mut strings).unwrap();
        assert_eq!(report.n, 1);
        verify(&out, &rows).unwrap();
    }

    #[test]
    fn the_verifier_rejects_a_database_that_does_not_match() {
        let d = TempDir::new("mismatch");
        let (mut rows, mut strings) = sample_rows(100);
        let out = d.0.join("m.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();

        let mut wrong = rows.clone();
        wrong[7].ids[D_NAME] = wrong[7].ids[D_NAME].wrapping_add(1);
        assert!(verify(&out, &wrong).is_err(), "a changed name id must be caught");

        let mut short = rows.clone();
        let _ = short.pop();
        assert!(verify(&out, &short).is_err(), "a record count change must be caught");
    }

    #[test]
    fn trailing_bytes_are_rejected() {
        let d = TempDir::new("trailing");
        let (mut rows, mut strings) = sample_rows(50);
        let out = d.0.join("t.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();
        let mut buf = std::fs::read(&out).unwrap();
        buf.push(0);
        std::fs::write(&out, &buf).unwrap();
        assert!(verify(&out, &rows).is_err());
    }
}
