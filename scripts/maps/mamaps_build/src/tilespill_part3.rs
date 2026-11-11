#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::body::{GEOM_LINE, GEOM_POLYGON, WINDING_HOLE, WINDING_OUTER};

    pub(super) fn tmp(name: &str) -> PathBuf {
        use std::sync::atomic::AtomicU64;
        static NEXT: AtomicU64 = AtomicU64::new(0);
        std::env::temp_dir().join(format!(
            "mamaps_tilespill_{}_{}_{name}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed),
        ))
    }

    /// The tile-chunk scratch is unlinked at creation on Unix: nothing named is left in its
    /// directory while chunks are written and read back, and the books still balance.
    #[test]
    fn the_chunk_scratch_leaves_no_name_behind() {
        let dir = tmp("unlinkdir");
        std::fs::create_dir_all(&dir).unwrap();
        let spill = ChunkSpill::create(dir.join("tiles.chunks.tmp")).unwrap();
        let chunk = spill.write_chunk(std::collections::BTreeMap::new()).unwrap();
        #[cfg(unix)]
        assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 0, "a chunk scratch name was left");
        let mut reader = spill.reader(&chunk, 4096);
        assert!(reader.next().unwrap().is_none());
        spill.check_books().unwrap();
        drop(spill);
        assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 0);
        std::fs::remove_dir(&dir).unwrap();
    }

    pub(super) fn feature(kind: u16, detail: u16, geom: u8, flags: u8, at: u32, n: u32) -> BodyFeature {
        BodyFeature {
            kind,
            kind_detail: detail,
            geom_type: geom,
            flags,
            name_idx: tilecodec::mamaps::body::NAME_NONE,
            parts_offset: at,
            part_count: n,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        }
    }

    fn named(kind: u16, name_idx: u16) -> BodyFeature {
        BodyFeature {
            kind,
            kind_detail: 0,
            geom_type: tilecodec::mamaps::body::GEOM_POINT,
            flags: 0,
            name_idx,
            parts_offset: 0,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        }
    }

    fn entry(layer_id: u8, features: Vec<BodyFeature>, names: &[&str]) -> ChunkEntry {
        let mut layer = BodyLayer::new(layer_id);
        layer.features = features;
        for (i, _) in layer.features.iter().enumerate() {
            layer.parts.push(Part {
                coord_start: i as u32,
                point_count: 1,
                winding: WINDING_OUTER,
            });
            layer.coords.push((i as i16, 0));
        }
        // Fix up parts_offset to match the pushed parts (all features above use offset 0).
        for (i, feature) in layer.features.iter_mut().enumerate() {
            feature.parts_offset = i as u32;
        }
        ChunkEntry { layer, names: names.iter().map(|s| s.to_string()).collect(), ids: Vec::new(), turn_lanes: Vec::new(), buildings: Vec::new(), carriageways: Vec::new() }
    }

    /// Every shape an entry can take, including the empty ones that a naive length check would let
    /// through and a naive decode would trip on — plus a named entry, because names are the one
    /// variable-length arena in the spill.
    pub(super) fn every_layer() -> Vec<((u64, u8), ChunkEntry)> {
        let plain = |layer_id: u8, features: Vec<BodyFeature>| ChunkEntry {
            layer: BodyLayer { layer_id, features, parts: Vec::new(), coords: Vec::new() },
            names: Vec::new(),
            ids: Vec::new(),
            turn_lanes: Vec::new(),
            buildings: Vec::new(),
            carriageways: Vec::new(),
        };
        vec![
            // Empty layer: no features, no parts, no coords.
            ((1, 0), ChunkEntry::new(0)),
            // Features but no parts and no coords, which `push` cannot make and a corrupt file can.
            ((2, 1), plain(1, vec![feature(1, 2, GEOM_LINE, 0, 0, 0)])),
            // A part with no coordinates.
            (
                (3, 2),
                ChunkEntry {
                    layer: BodyLayer {
                        layer_id: 2,
                        features: vec![feature(3, 4, GEOM_LINE, 1, 0, 1)],
                        parts: vec![Part {
                            coord_start: 0,
                            point_count: 0,
                            winding: WINDING_OUTER,
                        }],
                        coords: Vec::new(),
                    },
                    names: Vec::new(),
                    ids: Vec::new(),
                    turn_lanes: Vec::new(),
                    buildings: Vec::new(),
                    carriageways: Vec::new(),
                },
            ),
            // The extremes of every field: `u16::MAX` kinds, `i16` at both ends, a hole.
            (
                (4, 3),
                ChunkEntry {
                    layer: BodyLayer {
                        layer_id: 3,
                        features: vec![
                            feature(u16::MAX, u16::MAX, GEOM_POLYGON, 0x0f, 0, 2),
                            feature(0, 0, GEOM_POLYGON, 0, 2, 1),
                        ],
                        parts: vec![
                            Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER },
                            Part { coord_start: 2, point_count: 2, winding: WINDING_HOLE },
                            Part { coord_start: 4, point_count: 1, winding: WINDING_OUTER },
                        ],
                        coords: vec![
                            (i16::MIN, i16::MAX),
                            (i16::MAX, i16::MIN),
                            (0, 0),
                            (-1, 1),
                            (32_767, -32_768),
                        ],
                    },
                    names: Vec::new(),
                    ids: Vec::new(),
                    turn_lanes: Vec::new(),
                    buildings: Vec::new(),
                    carriageways: Vec::new(),
                },
            ),
            // Several layers on one tile, which is what the merge collapses.
            ((5, 1), ChunkEntry::new(1)),
            (
                (5, 7),
                ChunkEntry {
                    layer: BodyLayer {
                        layer_id: 7,
                        features: vec![feature(9, 9, GEOM_LINE, 0, 0, 1)],
                        parts: vec![Part {
                            coord_start: 0,
                            point_count: 3,
                            winding: WINDING_OUTER,
                        }],
                        coords: vec![(1, 2), (3, 4), (5, 6)],
                    },
                    names: Vec::new(),
                    ids: Vec::new(),
                    turn_lanes: Vec::new(),
                    buildings: Vec::new(),
                    carriageways: Vec::new(),
                },
            ),
            ((5, 10), ChunkEntry::new(10)),
            // A named entry: two point labels sharing one name plus an unnamed one.
            ((6, 8), entry(8, vec![named(3, 1), named(5, 1), named(7, 0)], &["Café"])),
            // The id arena, which only `places` and `poi` carry: a real tagged OSM id, ID_NONE
            // for a feature with no upstream element, and a value with its top bit set so a
            // sign-extension bug in the u64 round trip cannot hide.
            (
                (7, 8),
                ChunkEntry {
                    ids: vec![
                        crate::extract::tagged_id(240_109_189, crate::extract::ELEMENT_NODE),
                        tilecodec::mamaps::body::ID_NONE,
                        u64::MAX,
                    ],
                    ..entry(8, vec![named(3, 1), named(5, 0), named(7, 0)], &["Bar"])
                },
            ),
            // The carriageway arena, which only `roads` carries: a divided road, a one-way with
            // every lane forward, and a road whose split was never surveyed — all three in one
            // entry, because the section is dense and the unsurveyed one still needs its slot.
            (
                (8, 4),
                ChunkEntry {
                    carriageways: vec![
                        Carriageway { forward: 3, backward: 1, solid_dividers: 0b101 },
                        Carriageway { forward: 2, backward: 0, solid_dividers: u32::MAX },
                        Carriageway::default(),
                    ],
                    ..entry(4, vec![named(1, 0), named(2, 0), named(3, 0)], &[])
                },
            ),
        ]
    }

    pub(super) fn drain(spill: &ChunkSpill, at: &ChunkRef, window: usize) -> Vec<((u64, u8), ChunkEntry)> {
        let mut reader = spill.reader(at, window);
        let mut out = Vec::new();
        while let Some(entry) = reader.next().expect("read an entry back") {
            out.push(entry);
        }
        out
    }

    #[test]
    fn a_chunk_round_trips_through_the_scratch_file() {
        let spill = ChunkSpill::create(tmp("roundtrip")).expect("create");
        let want = every_layer();
        let map: BTreeMap<(u64, u8), ChunkEntry> = want.iter().cloned().collect();
        let at = spill.write_chunk(map).expect("write");
        assert_eq!(at.entries, want.len() as u64);
        assert_eq!(drain(&spill, &at, 1 << 16), want);
        spill.check_books().expect("the books balance");
    }

    /// **The silent one.** A `ChunkEntry.carriageways` that never reaches the spill is dropped on
    /// every build that spills chunks — which is every real build — and the archive comes out with
    /// no carriageway table, no lane markings and no error anywhere. Nothing else in this module
    /// would notice: the books balance, the features are all there, and the map merely looks like
    /// the renderer is at fault.
    ///
    /// So the section is asserted on its own rather than only inside `every_layer`: a road with a
    /// divided carriageway, a one-way with every lane forward, and one whose split was never
    /// surveyed — the last because the section is dense and its slot has to survive too.
    #[test]
    fn a_carriageway_section_survives_the_scratch_file() {
        let spill = ChunkSpill::create(tmp("carriageways")).expect("create");
        let want = ChunkEntry {
            carriageways: vec![
                Carriageway { forward: 3, backward: 1, solid_dividers: 0b101 },
                Carriageway { forward: 2, backward: 0, solid_dividers: u32::MAX },
                Carriageway::default(),
            ],
            ..entry(4, vec![named(1, 0), named(2, 0), named(3, 0)], &[])
        };
        let mut map: BTreeMap<(u64, u8), ChunkEntry> = BTreeMap::new();
        map.insert((1, 4), want.clone());
        let at = spill.write_chunk(map).expect("write");
        let got = drain(&spill, &at, 1 << 16);
        assert_eq!(got, vec![((1u64, 4u8), want)]);
        // And the books, which are what would catch a section written but not accounted for.
        spill.check_books().expect("the books balance");
    }

    /// A layer with no surveyed split writes no section at all, so a `boundaries` or `water` tile
    /// pays nothing for a table that only roads ever have.
    #[test]
    fn an_entry_with_no_carriageways_writes_no_section() {
        let spill = ChunkSpill::create(tmp("nocarriageways")).expect("create");
        let bare = entry(4, vec![named(1, 0), named(2, 0)], &[]);
        let mut map: BTreeMap<(u64, u8), ChunkEntry> = BTreeMap::new();
        map.insert((1, 4), bare.clone());
        let at = spill.write_chunk(map).expect("write");
        let with = ChunkEntry {
            carriageways: vec![Carriageway::default(); 2],
            ..entry(4, vec![named(1, 0), named(2, 0)], &[])
        };
        let mut map: BTreeMap<(u64, u8), ChunkEntry> = BTreeMap::new();
        map.insert((1, 4), with);
        let at_with = spill.write_chunk(map).expect("write");
        assert_eq!(
            at_with.len - at.len,
            2 * CARRIAGEWAY_RECORD_LEN as u64,
            "the section costs exactly one record per feature and nothing when absent",
        );
        assert_eq!(drain(&spill, &at, 1 << 16), vec![((1u64, 4u8), bare)]);
    }

    /// The read accounting survives a buffer compaction mid-entry. Names are pulled one inline
    /// length at a time, so a narrow window tops the buffer up (compacting it) halfway through
    /// an entry's names — and the bytes-read counter used to difference two offsets across that
    /// compaction, undercounting by the compacted prefix. California z12 caught it: a megabyte
    /// short on `check_books`. Narrow window + many long names forces the compaction here.
    #[test]
    fn a_narrow_window_compacting_mid_entry_still_balances_the_books() {
        let spill = ChunkSpill::create(tmp("compactnames")).expect("create");
        let names: Vec<String> = (0..40).map(|i| format!("name-{i}-with-padding-to-lengthen")).collect();
        let features: Vec<BodyFeature> = names
            .iter()
            .enumerate()
            .map(|(i, _)| BodyFeature {
                kind: 1,
                kind_detail: 0,
                geom_type: tilecodec::mamaps::body::GEOM_POINT,
                flags: 0,
                name_idx: i as u16 + 1,
                parts_offset: i as u32,
                part_count: 1,
                transit_color: 0,
                transit_ordinal: 0,
                transit_lanes: 0,
                transit_taper: 0,
                lane_count: 0,
            })
            .collect();
        let mut layer = BodyLayer::new(8);
        for (i, feature) in features.into_iter().enumerate() {
            layer.features.push(feature);
            layer.parts.push(Part {
                coord_start: i as u32,
                point_count: 1,
                winding: WINDING_OUTER,
            });
            layer.coords.push((i as i16, 0));
        }
        let mut map: BTreeMap<(u64, u8), ChunkEntry> = BTreeMap::new();
        map.insert((7, 8), ChunkEntry { layer, names: names.clone(), ids: Vec::new() , turn_lanes: Vec::new(), buildings: Vec::new(), carriageways: Vec::new() });
        let at = spill.write_chunk(map).expect("write");
        // Window of one header: every name tops up (and compacts) mid-entry.
        let read = drain(&spill, &at, ENTRY_HEADER_BYTES);
        assert_eq!(read.len(), 1);
        assert_eq!(read[0].1.names, names);
        spill.check_books().expect("the books balance after mid-entry compaction");
    }

    /// The claim the whole merge rests on: the file's order is `map.keys()`, so a reader hands
    /// `Merged` the identical key sequence a `BTreeMap` did.
    #[test]
    fn entry_order_is_the_maps_key_order() {
        let spill = ChunkSpill::create(tmp("order")).expect("create");
        // Inserted in a deliberately scrambled order; a `BTreeMap` sorts them and so must the file.
        let mut map: BTreeMap<(u64, u8), ChunkEntry> = BTreeMap::new();
        for (tile, layer) in [(30u64, 2u8), (10, 5), (10, 1), (20, 9), (10, 3), (30, 0)] {
            map.insert((tile, layer), ChunkEntry::new(layer));
        }
        let keys: Vec<(u64, u8)> = map.keys().copied().collect();
        let at = spill.write_chunk(map).expect("write");
        let read: Vec<(u64, u8)> = drain(&spill, &at, 1 << 16).into_iter().map(|(k, _)| k).collect();
        assert_eq!(read, keys);
        assert_eq!(read, vec![(10, 1), (10, 3), (10, 5), (20, 9), (30, 0), (30, 2)]);
    }

    /// Chunks written concurrently must not overlap, and every one must read back as itself.
    #[test]
    fn concurrent_chunks_get_disjoint_ranges_and_the_books_balance() {
        let spill = ChunkSpill::create(tmp("concurrent")).expect("create");
        let refs: Vec<ChunkRef> = std::thread::scope(|scope| {
            let handles: Vec<_> = (0..8u64)
                .map(|i| {
                    scope.spawn({
                        let spill = &spill;
                        move || {
                            let mut map: BTreeMap<(u64, u8), ChunkEntry> = BTreeMap::new();
                            for tile in 0..64u64 {
                                let mut entry = ChunkEntry::new(1);
                                entry.layer.coords =
                                    vec![(i as i16, tile as i16); 1 + tile as usize];
                                map.insert((tile, 1), entry);
                            }
                            (i, spill.write_chunk(map).expect("write"))
                        }
                    })
                })
                .collect();
            let mut refs: Vec<(u64, ChunkRef)> =
                handles.into_iter().map(|h| h.join().expect("join")).collect();
            refs.sort_by_key(|(i, _)| *i);
            refs.into_iter().map(|(_, at)| at).collect()
        });

        let mut ranges: Vec<(u64, u64)> = refs.iter().map(|r| (r.at, r.at + r.len)).collect();
        ranges.sort();
        for pair in ranges.windows(2) {
            assert!(pair[0].1 <= pair[1].0, "two chunks share bytes: {pair:?}");
        }
        for (i, at) in refs.iter().enumerate() {
            let entries = drain(&spill, at, 1 << 15);
            assert_eq!(entries.len(), 64);
            assert_eq!(entries[3].1.layer.coords[0], (i as i16, 3));
        }
        spill.check_books().expect("the books balance");
    }

    #[test]
    fn a_truncated_chunk_errors_rather_than_decoding() {
        let spill = ChunkSpill::create(tmp("truncated")).expect("create");
        let map: BTreeMap<(u64, u8), ChunkEntry> = every_layer().into_iter().collect();
        let at = spill.write_chunk(map).expect("write");

        // Half a chunk: the reader is told the entry count but the bytes run out first.
        let short = ChunkRef { at: at.at, len: at.len / 2, entries: at.entries };
        let mut reader = spill.reader(&short, 1 << 16);
        let mut failed = false;
        loop {
            match reader.next() {
                Ok(Some(_)) => {}
                Ok(None) => break,
                Err(_) => {
                    failed = true;
                    break;
                }
            }
        }
        assert!(failed, "a truncated chunk decoded instead of erroring");

        // And the other way round: the bytes are there but fewer entries were claimed, so the
        // cursor would leave a tail behind.
        let fewer = ChunkRef { at: at.at, len: at.len, entries: at.entries - 1 };
        let mut reader = spill.reader(&fewer, 1 << 16);
        for _ in 0..fewer.entries {
            reader.next().expect("read").expect("an entry");
        }
        assert!(reader.next().is_err(), "a chunk with bytes past its last entry read as complete");
    }

    /// Byte 23 was the reserved tail and is now the carriageway flag, so the three section flags
    /// are the whole of bytes 21..24. Each is 0 or 1: a spare value there means a newer writer put
    /// something in it, and guessing would decode a section that moved.
    #[test]
    fn an_unknown_section_flag_errors() {
        let head = [0u8; ENTRY_HEADER_BYTES];
        entry_header(&head).expect("a zero header is legal");
        for flag in 21..24 {
            let mut set = head;
            set[flag] = 1;
            entry_header(&set).unwrap_or_else(|e| panic!("byte {flag} set is legal: {e:?}"));
            let mut spare = head;
            spare[flag] = 2;
            assert!(entry_header(&spare).is_err(), "byte {flag} decoded a spare value");
        }
    }

    /// The word that was the second reserved tail is now the id count, and an id count that is
    /// neither zero nor one-per-feature is a desynced side table — the one corruption that would
    /// otherwise attribute every POI's id to its neighbour.
    #[test]
    fn an_id_count_that_does_not_match_the_features_errors() {
        let mut head = [0u8; ENTRY_HEADER_BYTES];
        head[8..12].copy_from_slice(&2u32.to_le_bytes());
        head[28..32].copy_from_slice(&2u32.to_le_bytes());
        entry_header(&head).expect("one id per feature is legal");
        head[28..32].copy_from_slice(&0u32.to_le_bytes());
        entry_header(&head).expect("no ids at all is legal");
        head[28..32].copy_from_slice(&1u32.to_le_bytes());
        assert!(entry_header(&head).is_err(), "one id for two features decoded");
    }

    #[test]
    fn an_entry_claiming_more_than_a_gigabyte_errors() {
        let mut head = [0u8; ENTRY_HEADER_BYTES];
        head[16..20].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(entry_header(&head).is_err(), "a gigabyte-plus entry decoded");
    }
}