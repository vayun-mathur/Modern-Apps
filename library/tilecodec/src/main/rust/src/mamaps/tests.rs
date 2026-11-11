use super::*;
use crate::mamaps::body::{Feature, Layer, Part, DEFAULT_EXTENT, GEOM_LINE, WINDING_OUTER};
use crate::mamaps::write::{Options, StreamWriter};
use crate::pmtiles::{tile_id, zoom_base};
use crate::proto::Result;
use crate::stream::{RangeReader, OPEN_PREFIX_BYTES};
use std::cell::RefCell;
use std::path::PathBuf;

/// A [`RangeReader`] over bytes in memory that logs every range asked for, so a test can assert
/// on the **number of round trips** as well as on the bytes. The same shape `stream.rs` uses.
struct Counting {
    bytes: Vec<u8>,
    requests: RefCell<Vec<(u64, u32)>>,
}

impl RangeReader for Counting {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        self.requests.borrow_mut().push((offset, length));
        if offset >= self.bytes.len() as u64 {
            return Ok(Vec::new());
        }
        // Short rather than failing, as the trait specifies.
        let end = (offset + length as u64).min(self.bytes.len() as u64);
        Ok(self.bytes[offset as usize..end as usize].to_vec())
    }
}

/// A one-line body whose contents depend on `seed`, so two tiles differ unless asked not to.
fn body_for(seed: i16) -> Body {
    let mut roads = Layer::new(dict::LAYER_ROADS);
    roads.features.push(Feature {
        kind: 45,
        kind_detail: dict::NONE,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: crate::mamaps::body::NAME_NONE,
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
    Body { extent: DEFAULT_EXTENT, layers: vec![roads], names: Vec::new(), ids: Vec::new() , turn_lanes: Vec::new(), buildings: Vec::new(), heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new() }
}

/// An archive of `(z, x, y, seed)` tiles, fed in ascending id order as the writer requires.
fn archive(tiles: &[(u8, u64, u64, i16)], options: Options) -> Vec<u8> {
    let mut rows: Vec<(u64, i16)> =
        tiles.iter().map(|(z, x, y, seed)| (tile_id(*z, *x, *y), *seed)).collect();
    rows.sort_by_key(|(id, _)| *id);
    let mut writer = StreamWriter::new(options).expect("options");
    for (id, seed) in rows {
        writer.append(id, &body_for(seed)).expect("append");
    }
    writer.finish().expect("finish")
}

fn open(bytes: Vec<u8>) -> MamapsArchive<Counting> {
    MamapsArchive::open(Counting { bytes, requests: RefCell::new(Vec::new()) }).expect("open")
}

/// A directory of its own, removed on drop, so a test can point a writer's body scratch file at
/// it and then assert on what is — or is not — left behind.
struct Scratch {
    dir: PathBuf,
}

impl Scratch {
    fn new(name: &str) -> Scratch {
        let dir =
            std::env::temp_dir().join(format!("mamaps_write_{name}_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).expect("temp dir");
        Scratch { dir }
    }

    fn path(&self, name: &str) -> PathBuf {
        self.dir.join(name)
    }

    fn options(&self) -> Options {
        Options { spill_dir: Some(self.dir.clone()), ..Options::default() }
    }

    /// What the directory holds, so a test can say "nothing".
    fn files(&self) -> Vec<String> {
        std::fs::read_dir(&self.dir)
            .expect("read_dir")
            .map(|e| e.expect("entry").file_name().to_string_lossy().into_owned())
            .collect()
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

/// A body big enough, and random enough, that a few hundred of them cannot fit in the writer's
/// scratch-file buffer.
///
/// The coordinates come from a seeded LCG rather than from a pattern on purpose: a body that
/// deflated down to a few hundred bytes would let a whole test archive sit in the buffer, and
/// then a test about reading bodies back off disk would pass without ever reading one.
fn wide_body_for(seed: i16) -> Body {
    const POINTS: u32 = 2048;
    let mut roads = Layer::new(dict::LAYER_ROADS);
    roads.features.push(Feature {
        kind: 45,
        kind_detail: dict::NONE,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: crate::mamaps::body::NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    roads.parts.push(Part { coord_start: 0, point_count: POINTS, winding: WINDING_OUTER });
    let mut state = seed as u64 ^ 0xA5A5_A5A5_A5A5_A5A5;
    roads.coords = (0..POINTS)
        .map(|_| {
            state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            ((state >> 33) as i16, (state >> 17) as i16)
        })
        .collect();
    Body { extent: DEFAULT_EXTENT, layers: vec![roads], names: Vec::new(), ids: Vec::new() , turn_lanes: Vec::new(), buildings: Vec::new(), heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new() }
}

#[test]
fn a_tile_written_reads_back_exactly() {
    let bytes = archive(&[(0, 0, 0, 100), (1, 0, 0, 200), (1, 1, 1, 300)], Options::default());
    let mut a = open(bytes);
    for (z, x, y, seed) in [(0u8, 0u32, 0u32, 100i16), (1, 0, 0, 200), (1, 1, 1, 300)] {
        let tile = a.tile(z, x, y).expect("read").expect("present");
        assert_eq!(tile, body_for(seed), "z{z}/{x}/{y}");
    }
    // A tile the archive does not hold is `None`, which is the ordinary answer off the edge of
    // coverage rather than a fault.
    assert!(a.tile(1, 0, 1).expect("read").is_none());
    assert!(a.tile(9, 0, 0).expect("read").is_none(), "past max_zoom");
    assert!(a.tile(1, 9, 9).expect("read").is_none(), "off the world");
}

#[test]
fn a_tile_reads_back_uncompressed_too() {
    let options = Options { compress: false, ..Options::default() };
    let mut a = open(archive(&[(0, 0, 0, 7)], options));
    assert!(!a.header.compressed());
    assert_eq!(a.tile(0, 0, 0).expect("read").expect("present"), body_for(7));
}

/// **Invariant 2 of the plan's verification list.** The whole container shape exists to hold
/// this: open is one request, a warm tile is one, a cold tile is two, and nothing is ever
/// three.
#[test]
fn open_is_one_request_a_warm_tile_one_a_cold_tile_two_and_never_three() {
    // Enough tiles to need more than one leaf, so a cold tile really does have a leaf to
    // fetch. Sixteen entries per leaf: z0 plus all of z1..z3 is 85 tiles, so six leaves.
    let mut tiles: Vec<(u8, u64, u64, i16)> = Vec::new();
    for z in 0..=3u8 {
        for x in 0..(1u64 << z) {
            for y in 0..(1u64 << z) {
                tiles.push((z, x, y, (tile_id(z, x, y) + 1) as i16));
            }
        }
    }
    let options = Options { leaf_entry_capacity: 16, ..Options::default() };
    let mut a = open(archive(&tiles, options));
    assert!(a.header.leaf_count > 1, "this test needs several leaves");
    assert_eq!(
        a.reader.requests.borrow().as_slice(),
        &[(0, OPEN_PREFIX_BYTES)],
        "a cold open is one request: header, dictionary and root all live in the prefix",
    );

    let count = |a: &MamapsArchive<Counting>| a.reader.requests.borrow().len();
    a.reader.requests.borrow_mut().clear();
    assert!(a.tile(3, 0, 0).expect("read").is_some());
    assert_eq!(count(&a), 2, "a cold tile is a leaf plus a body");

    a.reader.requests.borrow_mut().clear();
    assert!(a.tile(3, 1, 1).expect("read").is_some());
    assert_eq!(count(&a), 1, "its leaf is now cached, so only the body");

    // And nothing anywhere costs three, at any zoom, warm or cold.
    for (z, x, y, _) in &tiles {
        a.reader.requests.borrow_mut().clear();
        let _ = a.tile(*z, *x as u32, *y as u32).expect("read");
        let n = count(&a);
        assert!(n <= 2, "z{z}/{x}/{y} cost {n} requests");
    }
}

/// **Invariant 3.** Every read's length comes from a header or entry field, so the request log
/// can be checked against the fields rather than against a sentinel or a guess. A read of the
/// wrong length does not merely fail: `tile::cache` stores a `206` whose body length equals the
/// request, so it poisons that range for every later read.
#[test]
fn every_read_takes_its_length_from_a_declared_field() {
    let mut a = open(archive(&[(0, 0, 0, 1), (1, 0, 0, 2)], Options::default()));
    let (leaf_len, leaf_offset) = (a.header.leaf_len, a.header.leaf_offset);
    a.reader.requests.borrow_mut().clear();
    assert!(a.tile(1, 0, 0).expect("read").is_some());
    let requests = a.reader.requests.borrow().clone();
    assert_eq!(requests.len(), 2);
    // The leaf: offset and length both from the root entry and the header.
    assert_eq!(requests[0].0, leaf_offset, "the leaf is where the header says");
    assert!(requests[0].1 as u64 <= leaf_len as u64, "and no longer than the section it is in");
    assert_eq!(
        requests[0].1 as usize % index::LEAF_ENTRY_LEN,
        0,
        "a leaf read is a whole number of entries",
    );
    // The body: inside the data section, at a length from its own leaf entry.
    assert!(requests[1].0 >= a.header.data_offset);
    assert!(requests[1].0 + requests[1].1 as u64 <= a.header.data_offset + a.header.data_len);
}

/// A short reply must fail loudly rather than be decoded or cached.
#[test]
fn a_range_that_comes_back_short_is_refused() {
    let full = archive(&[(0, 0, 0, 1), (1, 0, 0, 2)], Options::default());
    let truncated = full[..full.len() - 8].to_vec();
    let mut a =
        MamapsArchive::open(Counting { bytes: truncated, requests: RefCell::new(Vec::new()) })
            .expect("the prefix is intact, so open still works");
    let failure = a.tile(1, 0, 0).expect_err("the last body is cut off");
    assert!(failure.0.contains("got"), "{}", failure.0);
}

/// **What makes ocean and empty tiles nearly free.** Consecutive identical bodies collapse to
/// one entry *and* one body; non-consecutive ones share the body through the content bucket.
#[test]
fn identical_tiles_share_one_body_whether_or_not_they_are_consecutive() {
    // Every z1 tile the same, and z0 different, so the run cannot swallow everything.
    let bytes = archive(&[(0, 0, 0, 9), (1, 0, 0, 5), (1, 1, 0, 5), (1, 1, 1, 5), (1, 0, 1, 5)], Options::default());
    let header = Header::parse(&bytes).expect("header");
    assert_eq!(header.tiles_addressed, 5);
    assert_eq!(header.bodies_written, 2, "one distinct z1 body plus z0's");
    assert!(header.flags & header::FLAG_RUN_LENGTH_PRESENT != 0, "a run was used");
    // And all four still read back.
    let mut a = open(bytes);
    for (x, y) in [(0u32, 0u32), (1, 0), (1, 1), (0, 1)] {
        assert_eq!(a.tile(1, x, y).expect("read").expect("present"), body_for(5));
    }
    assert_eq!(a.tile(0, 0, 0).expect("read").expect("present"), body_for(9));
}

/// Content dedup across a leaf boundary, which is the case that made a leaf's
/// `base_data_offset` the *minimum* of its chunk rather than its first entry: a tile in a later
/// leaf can point at a body written for an earlier one.
#[test]
fn a_tile_can_share_a_body_written_for_an_earlier_leaf() {
    // Ids ascend, seeds alternate, so no run ever forms and the repeats are all non-adjacent.
    let mut tiles: Vec<(u8, u64, u64, i16)> = Vec::new();
    for x in 0..8u64 {
        for y in 0..8u64 {
            tiles.push((3, x, y, if (x + y) % 2 == 0 { 11 } else { 22 }));
        }
    }
    let options = Options { leaf_entry_capacity: 4, ..Options::default() };
    let bytes = archive(&tiles, options);
    let header = Header::parse(&bytes).expect("header");
    assert_eq!(header.tiles_addressed, 64);
    assert!(header.bodies_written <= 8, "{} bodies for two shapes", header.bodies_written);
    assert!(header.leaf_count > 4, "this test needs the repeats to cross leaves");
    let mut a = open(bytes);
    for (z, x, y, seed) in &tiles {
        let tile = a.tile(*z, *x as u32, *y as u32).expect("read").expect("present");
        assert_eq!(tile, body_for(*seed), "z{z}/{x}/{y}");
    }
}

/// **The one thing spilling the data section could have broken.** A content-dedup hit is a fact
/// because every byte of the candidate is compared, and once the bodies are in a file that
/// compare has to read them back. This feeds enough distinct bodies to push the earliest ones
/// well past the writer's in-memory buffer and then repeats every one of them, so the
/// confirmations really do come off disk rather than out of the buffer.
#[test]
fn content_dedup_confirms_a_candidate_that_has_already_reached_the_scratch_file() {
    let scratch = Scratch::new("dedup_off_disk");
    // 200 incompressible 8 KiB bodies is a data section of about 1.6 MB, against a buffer of 1.
    const DISTINCT: i16 = 200;
    let base = tile_id(9, 0, 0);
    let mut rows: Vec<(u64, i16)> = (0..DISTINCT).map(|i| (base + i as u64, i)).collect();
    // Then the same bodies again at later ids, in the same order. Every repeat is non-adjacent,
    // so no run can form and each one has to go through the content bucket.
    rows.extend((0..DISTINCT).map(|i| (base + DISTINCT as u64 + i as u64, i)));

    let options = Options { max_zoom: 9, ..scratch.options() };
    let mut writer = StreamWriter::new(options).expect("options");
    for (id, seed) in &rows {
        writer.append(*id, &wide_body_for(*seed)).expect("append");
    }
    let bytes = writer.finish().expect("finish");

    let header = Header::parse(&bytes).expect("header");
    assert!(
        header.data_len > 1 << 20,
        "the data section must outgrow the writer's buffer or this test proves nothing, it is \
         {} bytes",
        header.data_len,
    );
    assert_eq!(header.tiles_addressed, rows.len() as u64);
    assert_eq!(header.bodies_written, DISTINCT as u64, "the second pass stored nothing new");
    // And every tile still reads back as itself, which is what confirming against the wrong
    // bytes would silently break.
    let mut a = open(bytes);
    for (id, seed) in &rows {
        let (z, x, y) = crate::pmtiles::tile_zxy(*id);
        let tile = a.tile(z, x as u32, y as u32).expect("read").expect("present");
        assert_eq!(tile, wide_body_for(*seed), "tile {id}");
    }
}

/// `finish_to_path` and `finish` are two ways of emitting one archive, not two archives.
///
/// Worth asserting because they are not the same code path — one copies the data section out of
/// a file and the other into a `Vec` — and because the streaming one is what a real build uses.
/// If it could differ by a byte then every test above would be testing something nothing ships.
#[test]
fn a_streamed_archive_is_byte_identical_to_one_finished_in_memory() {
    let scratch = Scratch::new("streamed");
    // Repeats and runs in the same archive, so dedup and the run-length flag are both live on
    // the way to both outputs.
    let tiles: Vec<(u8, u64, u64, i16)> = (0..8u64)
        .flat_map(|x| {
            (0..8u64).map(move |y| (3u8, x, y, if (x + y) % 3 == 0 { 4 } else { (x * 8 + y) as i16 }))
        })
        .collect();
    let options = || Options { leaf_entry_capacity: 8, ..scratch.options() };
    let in_memory = archive(&tiles, options());

    let mut rows: Vec<(u64, i16)> =
        tiles.iter().map(|(z, x, y, seed)| (tile_id(*z, *x, *y), *seed)).collect();
    rows.sort_by_key(|(id, _)| *id);
    let mut writer = StreamWriter::new(options()).expect("options");
    for (id, seed) in rows {
        writer.append(id, &body_for(seed)).expect("append");
    }
    let out = scratch.path("streamed.mamaps");
    writer.finish_to_path(&out).expect("finish_to_path");

    let streamed = std::fs::read(&out).expect("read back");
    assert_eq!(streamed.len(), in_memory.len(), "the same length");
    assert_eq!(streamed, in_memory, "the same bytes");
    // And it opens and reads, which is all a reader will ever ask of it.
    assert_eq!(open(streamed).tile(3, 7, 7).expect("read").expect("present"), body_for(63));
}

/// A build that dies must not strand a copy of its data section — 652 MB for California, tens of
/// gigabytes for a planet. However the writer ends, its scratch file goes with it.
#[test]
fn the_body_scratch_file_is_removed_however_the_build_ends() {
    let scratch = Scratch::new("spill_cleanup");
    // A separate directory for the archives, so `scratch.files()` can assert on emptiness.
    let output = Scratch::new("spill_cleanup_out");

    // Dropped without ever being finished, which is what a failure upstream of the writer looks
    // like.
    {
        let mut writer = StreamWriter::new(scratch.options()).expect("options");
        writer.append(tile_id(0, 0, 0), &body_for(1)).expect("append");
        // Unlinked as soon as it is created on Unix, so even a killed build leaves nothing; named
        // until drop elsewhere.
        let named = if cfg!(unix) { 0 } else { 1 };
        assert_eq!(scratch.files().len(), named, "the body scratch's name while the writer lives");
    }
    assert!(scratch.files().is_empty(), "dropping the writer took its scratch with it");

    let mut writer = StreamWriter::new(scratch.options()).expect("options");
    writer.append(tile_id(0, 0, 0), &body_for(2)).expect("append");
    writer.finish_to_path(&output.path("a.mamaps")).expect("finish_to_path");
    assert!(scratch.files().is_empty(), "and so did finishing");

    // And a build refused for having no tiles, which fails only after the scratch file exists.
    let writer = StreamWriter::new(scratch.options()).expect("options");
    let refused = output.path("b.mamaps");
    assert!(writer.finish_to_path(&refused).is_err(), "an archive with no tiles");
    assert!(scratch.files().is_empty());
    assert!(!refused.exists(), "and the destination was never touched");
}

/// A reader that counts `read_batch` waves as well as single reads, so a test can assert on
/// the batch structure — not just the bytes — of a multi-tile fetch.
struct Batched {
    inner: Counting,
    single_reads: std::cell::Cell<usize>,
    batch_waves: RefCell<Vec<usize>>,
}

impl Batched {
    fn new(bytes: Vec<u8>) -> Batched {
        Batched {
            inner: Counting { bytes, requests: RefCell::new(Vec::new()) },
            single_reads: std::cell::Cell::new(0),
            batch_waves: RefCell::new(Vec::new()),
        }
    }
}

impl RangeReader for Batched {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        self.single_reads.set(self.single_reads.get() + 1);
        self.inner.read(offset, length)
    }

    fn read_batch(&self, requests: &[(u64, u32)]) -> Vec<Result<Vec<u8>>> {
        self.batch_waves.borrow_mut().push(requests.len());
        requests.iter().map(|&(o, l)| self.inner.read(o, l)).collect()
    }
}

fn open_batched(bytes: Vec<u8>) -> MamapsArchive<Batched> {
    MamapsArchive::open(Batched::new(bytes)).expect("open")
}

/// **The batch contract.** A cold screenful of tiles costs two waves — one per distinct cold
/// leaf, then one for the bodies — not two serial round trips per tile.
///
/// The `Batched` reader serves each range out of memory, so this measures the *structure* of
/// the fetch (how many waves, how many ranges per wave), not the network. The production
/// reader overlaps each wave in one multiplexed fetch; the per-tile path would cost
/// `2 * tiles.len()` serial reads here instead.
#[test]
fn a_batch_of_cold_tiles_costs_two_waves_not_two_round_trips_per_tile() {
    // Eight tiles across several leaves, so the leaf wave is real.
    let mut tiles: Vec<(u8, u64, u64, i16)> = Vec::new();
    for x in 0..4u64 {
        for y in 0..2u64 {
            tiles.push((3, x, y, (x * 2 + y) as i16));
        }
    }
    let options = Options { leaf_entry_capacity: 2, ..Options::default() };
    let a = open_batched(archive(&tiles, options));
    assert!(a.header.leaf_count > 1, "this test needs several leaves");
    // The open itself is a single read, before any batch runs.
    a.reader.inner.requests.borrow_mut().clear();
    a.reader.single_reads.set(0);
    a.reader.batch_waves.borrow_mut().clear();

    let want: Vec<(u8, u32, u32)> =
        tiles.iter().map(|(z, x, y, _)| (*z, *x as u32, *y as u32)).collect();
    let found = a.tiles(&want);
    assert_eq!(found.len(), want.len());
    for (slot, result) in found.iter().enumerate() {
        let (_, _, _, seed) = tiles[slot];
        assert_eq!(
            result.as_ref().expect("read").as_ref().expect("present"),
            &body_for(seed),
            "slot {slot}",
        );
    }
    // Two waves: the distinct cold leaves, then the bodies. No single reads at all —
    // the whole batch rides `read_batch`.
    assert_eq!(a.reader.single_reads.get(), 0, "no serial reads on the batch path");
    let waves = a.reader.batch_waves.borrow().clone();
    assert_eq!(waves.len(), 2, "one leaf wave and one body wave, got {waves:?}");
    assert_eq!(
        waves[1], want.len(),
        "every tile's body in the second wave, got {waves:?}",
    );
    // And the leaf wave fetches each distinct leaf exactly once, however many tiles share it.
    let distinct_leaves: std::collections::HashSet<u64> = a
        .reader
        .inner
        .requests
        .borrow()
        .iter()
        .take(waves[0])
        .map(|(o, _)| *o)
        .collect();
    assert_eq!(distinct_leaves.len(), waves[0], "one request per distinct leaf");
}

/// A warm batch — every leaf already cached — costs one wave: the bodies only.
#[test]
fn a_batch_of_warm_tiles_costs_one_body_wave() {
    let mut tiles: Vec<(u8, u64, u64, i16)> = Vec::new();
    for x in 0..4u64 {
        for y in 0..2u64 {
            tiles.push((3, x, y, (x * 2 + y) as i16));
        }
    }
    let options = Options { leaf_entry_capacity: 2, ..Options::default() };
    let a = open_batched(archive(&tiles, options));
    let want: Vec<(u8, u32, u32)> =
        tiles.iter().map(|(z, x, y, _)| (*z, *x as u32, *y as u32)).collect();
    // Warm the leaves with one tile each (single-tile path), then clear the counters.
    for (z, x, y) in &want {
        let _ = a.tile(*z, *x, *y).expect("warm");
    }
    a.reader.single_reads.set(0);
    a.reader.batch_waves.borrow_mut().clear();

    let found = a.tiles(&want);
    assert!(found.iter().all(|r| r.as_ref().expect("read").is_some()));
    let waves = a.reader.batch_waves.borrow().clone();
    assert_eq!(waves, vec![want.len()], "one body wave and nothing else, got {waves:?}");
    assert_eq!(a.reader.single_reads.get(), 0, "no serial reads on the batch path");
}

/// Absences and failures stay per-slot: a hole in the middle of a batch is `None` in its
/// slot, and the tiles around it still land.
#[test]
fn a_batch_keeps_absences_per_slot() {
    // z3 x0..4 y0..2, but only the even seeds are written — the odd tiles are holes.
    let mut tiles: Vec<(u8, u64, u64, i16)> = Vec::new();
    for x in 0..4u64 {
        for y in 0..2u64 {
            if (x + y) % 2 == 0 {
                tiles.push((3, x, y, (x * 2 + y) as i16));
            }
        }
    }
    let options = Options { leaf_entry_capacity: 8, ..Options::default() };
    let a = open_batched(archive(&tiles, options));
    a.reader.inner.requests.borrow_mut().clear();

    let want: Vec<(u8, u32, u32)> = (0..4u32)
        .flat_map(|x| (0..2u32).map(move |y| (3u8, x, y)))
        .collect();
    let found = a.tiles(&want);
    assert_eq!(found.len(), want.len());
    for (slot, result) in found.iter().enumerate() {
        let (x, y) = (want[slot].1, want[slot].2);
        if (x as u64 + y as u64) % 2 == 0 {
            let seed = (x * 2 + y) as i16;
            assert_eq!(
                result.as_ref().expect("read").as_ref().expect("present"),
                &body_for(seed),
                "slot {slot}",
            );
        } else {
            assert!(
                result.as_ref().expect("read").is_none(),
                "slot {slot} is a hole, not an error",
            );
        }
    }
}

/// A one-tile batch is the single-tile path with identical bytes and identical cost.
#[test]
fn a_single_tile_batch_reads_like_a_plain_read() {
    let a = open_batched(archive(&[(3, 1, 2, 42)], Options::default()));
    a.reader.inner.requests.borrow_mut().clear();
    let found = a.tiles(&[(3, 1, 2)]);
    assert_eq!(found.len(), 1);
    assert_eq!(
        found[0].as_ref().expect("read").as_ref().expect("present"),
        &body_for(42),
    );
    assert!(a.reader.batch_waves.borrow().is_empty(), "no wave for one tile");
}
