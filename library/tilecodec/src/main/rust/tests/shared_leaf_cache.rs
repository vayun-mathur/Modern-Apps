//! Shared leaf cache: the issue-815 MapLibre-parity gate.
//!
//! The lib test target is broken at HEAD by in-progress header work (`shared_*`
//! fields referenced from `mamaps::archive::tests` before they land on
//! `Header`), so like `leaf_compression.rs` this exercises the public API only:
//! it builds against the lib, never against `#[cfg(test)]` code.

use std::sync::Arc;
use tilecodec::mamaps::index::LeafEntry;
use tilecodec::mamaps::read::{LeafCache, DEFAULT_LEAF_CACHE_CAPACITY};

fn leaf(first: u32) -> Vec<LeafEntry> {
    vec![LeafEntry {
        tile_id_lo: first,
        run_length: 1,
        offset_delta: 0,
        length: 8,
    }]
}

#[test]
fn an_inserted_leaf_reads_back() {
    let cache = LeafCache::new();
    assert_eq!(cache.capacity(), DEFAULT_LEAF_CACHE_CAPACITY);
    assert!(cache.is_empty());
    cache.insert(7, leaf(10));
    assert_eq!(cache.len(), 1);
    let hit = cache.get(7).expect("cached");
    assert_eq!(hit.len(), 1);
    assert_eq!(hit[0].tile_id_lo, 10);
    assert!(cache.get(8).is_none(), "a miss is None");
}

#[test]
fn the_cache_evicts_least_recently_used() {
    let cache = LeafCache::with_capacity(2);
    cache.insert(1, leaf(1));
    cache.insert(2, leaf(2));
    // Touch 1 so 2 is the eviction candidate.
    let _ = cache.get(1);
    cache.insert(3, leaf(3));
    assert!(cache.get(1).is_some(), "recently used survives");
    assert!(cache.get(2).is_none(), "least-recently-used is evicted");
    assert!(cache.get(3).is_some());
    assert_eq!(cache.len(), 2);
}

#[test]
fn a_zero_capacity_still_holds_one_leaf() {
    let cache = LeafCache::with_capacity(0);
    assert_eq!(cache.capacity(), 1);
    cache.insert(1, leaf(1));
    assert!(cache.get(1).is_some());
}

#[test]
fn a_racing_insert_keeps_one_copy() {
    let cache = LeafCache::new();
    let first = cache.insert(9, leaf(1));
    let second = cache.insert(9, leaf(2));
    assert!(Arc::ptr_eq(&first, &second), "the loser uses the winner's copy");
    assert_eq!(cache.len(), 1);
    assert_eq!(cache.get(9).expect("cached")[0].tile_id_lo, 1);
}

#[test]
fn the_cache_shares_across_threads() {
    let cache = Arc::new(LeafCache::new());
    let mut handles = Vec::new();
    for i in 0..8u64 {
        let cache = Arc::clone(&cache);
        handles.push(std::thread::spawn(move || {
            cache.insert(i, leaf(i as u32));
            // Every thread reads every leaf; concurrent gets are safe.
            for j in 0..8u64 {
                let _ = cache.get(j);
            }
        }));
    }
    for h in handles {
        h.join().expect("worker");
    }
    assert_eq!(cache.len(), 8);
    for i in 0..8u64 {
        assert!(cache.get(i).is_some(), "leaf {i} survived the race");
    }
}

/// Two archive views over one cache fetch a shared leaf once.
///
/// The cost contract the container exists to hold — open is one request, a
/// warm tile one, a cold tile two — now holds *across* readers: the second
/// view's tile costs one request (its body) because the first view's leaf
/// fetch already populated the shared cache. Before the shared cache this
/// second view paid two.
#[test]
fn two_archives_over_one_cache_fetch_a_shared_leaf_once() {
    use tilecodec::mamaps::body::{Body, Feature, Layer, Part, DEFAULT_EXTENT, GEOM_LINE, WINDING_OUTER};
    use tilecodec::mamaps::dict;
    use tilecodec::mamaps::read::MamapsArchive;
    use tilecodec::mamaps::write::{Options, StreamWriter};
    use tilecodec::pmtiles::tile_id;
    use tilecodec::proto::Result;
    use tilecodec::stream::RangeReader;

    struct Counting {
        bytes: Vec<u8>,
        requests: std::cell::RefCell<Vec<(u64, u32)>>,
    }
    impl RangeReader for Counting {
        fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
            self.requests.borrow_mut().push((offset, length));
            if offset >= self.bytes.len() as u64 {
                return Ok(Vec::new());
            }
            let end = (offset + length as u64).min(self.bytes.len() as u64);
            Ok(self.bytes[offset as usize..end as usize].to_vec())
        }
    }

    fn body_for(seed: i16) -> Body {
        let mut roads = Layer::new(dict::LAYER_ROADS);
        roads.features.push(Feature {
            kind: 45,
            kind_detail: dict::NONE,
            geom_type: GEOM_LINE,
            flags: 0,
            name_idx: tilecodec::mamaps::body::NAME_NONE,
            parts_offset: 0,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        });
        roads.parts.push(Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER });
        roads.coords = vec![(0, 0), (seed, seed)];
        Body {
            extent: DEFAULT_EXTENT,
            layers: vec![roads],
            names: Vec::new(),
            ids: Vec::new(),
            turn_lanes: Vec::new(),
            buildings: Vec::new(),
            heightmap: None,
            carriageways: Vec::new(),
            convention: None,
            region_links: Vec::new(),
        }
    }

    // Several leaves so a tile really has a leaf to share.
    let mut tiles: Vec<(u8, u64, u64, i16)> = Vec::new();
    for z in 0..=3u8 {
        for x in 0..(1u64 << z) {
            for y in 0..(1u64 << z) {
                tiles.push((z, x, y, (tile_id(z, x, y) + 1) as i16));
            }
        }
    }
    let options = Options { leaf_entry_capacity: 16, ..Options::default() };
    let mut rows: Vec<(u64, i16)> =
        tiles.iter().map(|(z, x, y, seed)| (tile_id(*z, *x, *y), *seed)).collect();
    rows.sort_by_key(|(id, _)| *id);
    let mut writer = StreamWriter::new(options).expect("options");
    for (id, seed) in rows {
        writer.append(id, &body_for(seed)).expect("append");
    }
    let bytes = writer.finish().expect("finish");

    let leaves = Arc::new(LeafCache::new());
    let count = |a: &MamapsArchive<Counting>| a.reader().requests.borrow().len();

    // First view: cold tile costs two (leaf + body).
    let first = MamapsArchive::open_with_cache(
        Counting { bytes: bytes.clone(), requests: std::cell::RefCell::new(Vec::new()) },
        Arc::clone(&leaves),
    )
    .expect("open");
    assert!(first.tile(3, 0, 0).expect("read").is_some());
    assert_eq!(count(&first), 3, "open plus a cold tile: prefix, leaf, body");

    // Second view over the same bytes and the same cache: the same tile costs
    // one request (its body) — the leaf came from the shared cache, and the
    // prefix open is its own one request.
    let second = MamapsArchive::open_with_cache(
        Counting { bytes, requests: std::cell::RefCell::new(Vec::new()) },
        Arc::clone(&leaves),
    )
    .expect("open");
    assert!(second.tile(3, 0, 0).expect("read").is_some());
    assert_eq!(
        count(&second),
        2,
        "open plus a shared-leaf tile: prefix and body, no second leaf fetch"
    );
}

#[test]
fn planet_sized_leaves_are_bounded_by_bytes_not_count() {
    use tilecodec::mamaps::read::DEFAULT_LEAF_CACHE_BYTES;
    // A planet leaf: 262 144 entries x 16 B = 4 MiB parsed (planet3/planet4 capacity).
    let big = |lo: u32| {
        vec![LeafEntry { tile_id_lo: lo, run_length: 1, offset_delta: 0, length: 1 }; 262_144]
    };
    let cache = LeafCache::new();
    for i in 0..40u64 {
        cache.insert(i, big(i as u32));
    }
    assert!(cache.bytes() <= DEFAULT_LEAF_CACHE_BYTES, "held {} bytes", cache.bytes());
    assert_eq!(cache.len(), DEFAULT_LEAF_CACHE_BYTES / (262_144 * 16));
    assert!(cache.get(39).is_some(), "the newest leaf survives");
    assert!(cache.get(0).is_none(), "the oldest planet leaves are evicted by bytes");
}

#[test]
fn one_oversize_leaf_is_still_kept() {
    let cache = LeafCache::with_limits(64, 1024);
    cache.insert(1, vec![LeafEntry { tile_id_lo: 1, run_length: 1, offset_delta: 0, length: 1 }; 1000]);
    assert!(cache.get(1).is_some());
    assert_eq!(cache.len(), 1);
}
