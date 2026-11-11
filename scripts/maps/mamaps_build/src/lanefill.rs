//! Fill in the lane count of a junction stub from the road it interrupts.
//!
//! # The defect this exists for
//!
//! [`schema::roads::lane_count`](crate::schema::roads::lane_count) reads the OSM `lanes` tag and
//! nothing else, and a way that does not carry one gets zero — which the renderer turns into
//! `oneway ? 1 : 2`. That default is right for the untagged residential street it was written for.
//! It is wrong for the twenty metres of asphalt between the two carriageways of a divided
//! arterial, because a mapper who tagged `lanes=6` on the road either side very often left the
//! short piece in the middle untagged.
//!
//! The result reaches a screen as an intersection that is *narrower than the roads entering it*.
//! Measured at Broadway × Rollins Road in Burlingame, the case this was written for: Rollins is
//! 6.1 painted lanes west of the junction and 4.1 east of it, and **2.0 lanes through it** — the
//! narrowest pavement in the scene is the middle of the intersection. Way `417329335` is that
//! stub: nineteen metres, no `lanes`, no `lanes:forward`, no `lanes:backward`, sitting between
//! neighbours tagged 6 and 4.
//!
//! **This is not a defect in the extract.** [`crate::extract`] reads one OSM way to one feature
//! and never splits a way, so there is no `lanes` tag being dropped anywhere. The data genuinely
//! does not have one.
//!
//! # What this is, and what it is not
//!
//! **It is inference, and it must be read as inference.** Nothing here is tagged. What makes it
//! legitimate is not that the guess is good but that *the thing it replaces is also a guess* —
//! `oneway ? 1 : 2` is an invention with no more support in the data than this has, and it is a
//! worse one, because it ignores the two tagged measurements sitting at either end of the way it
//! is guessing about. Replacing a context-free invention with a context-sensitive one is the whole
//! of the claim. It is not a claim that the answer is right.
//!
//! Nothing here ever overrides a tag. A way that carries `lanes` keeps it.
//!
//! # What counts as the same road
//!
//! A way inherits only when **every** one of these holds. Each was measured against the whole San
//! Francisco peninsula extract — 27,287 drivable ways, of which 13,674 carry no `lanes` tag.
//!
//! * **It carries no `lanes` tag at all.** Never override data.
//! * **It has a name**, and the donor has the same one.
//! * **It is at most [`MAX_STUB_M`] long.**
//! * **A donor at *both* ends**, each collinear within [`MAX_TURN_DEGREES`] and agreeing on
//!   one-wayness.
//! * **The donor is itself tagged.** Not inherited — see "one hop" below.
//!
//! ## Why name *and* collinearity, rather than either
//!
//! | rule | candidates | of which the donor disagrees on one-wayness |
//! |---|---|---|
//! | same name only | 343 | 77 |
//! | collinear only | 393 | 77 |
//! | **both** | **290** | **40** |
//!
//! Neither alone is safe. Name alone joins a street to itself around a hard corner, which is a
//! different carriageway. Collinearity alone joins a street to whatever runs straight on through
//! the junction, and a road changing its name at a crossroads is ordinary. Requiring both is the
//! intersection of the two, and the last column is the evidence that it is the *right*
//! intersection rather than merely the smaller one: the disagreement rate on an independent
//! attribute nobody selected for — one-wayness — halves. Pairs that were never the same
//! carriageway are what that column counts, and requiring both conditions removes half of them.
//!
//! Matching on `ref` as well as `name` was measured and rejected: over the same extract it admits
//! exactly one further way, a 257 m motorway spur, which [`MAX_STUB_M`] excludes anyway.
//!
//! ## Why one-wayness has to agree
//!
//! `lanes` counts **both** directions. A two-way `lanes=6` inherited onto a one-way stub paints
//! six lanes running one way where the truth is about three, so the error is not a small
//! misjudgement but a doubling, in the direction that reads as a broken map. This is a
//! correctness condition and not a tuning knob; it costs 290 candidates down to 252.
//!
//! ## Why the minimum of the donors
//!
//! Over the same extract, at [`MAX_STUB_M`]: taking the minimum changes 113 ways by an average of
//! +1.33 lanes; taking the maximum changes 135 by +1.82. Both fix the reported defect. The
//! minimum is preferred because the two errors are not symmetric — too narrow reads as a cautious
//! map and too wide reads as a wrong one.
//!
//! Note the gap between 156 ways that qualify and 113 that change: where the minimum lands on the
//! value the default would have produced anyway, nothing is emitted. A rule that declines to speak
//! unless both of its neighbours jointly justify it is behaving correctly, not under-reaching.
//!
//! ## Why a length bound, and why in metres
//!
//! Candidate lengths run to a long tail — median 33.5 m but p90 202.7 m and a maximum of 804 m.
//! Unbounded, half a kilometre of El Camino Real quietly goes from the default 1 to 2, and 519 m
//! of San Bruno Avenue West from 1 to 3. That is no longer a junction stub; it is a road, and a
//! road acquiring a width from a neighbour it merely touches is the failure mode this bound
//! exists to prevent.
//!
//! [`MAX_STUB_M`] is 50 because of what is being modelled rather than where a percentile falls.
//! The subject is the pavement inside a junction, or the link between the two carriageways of a
//! divided road: [`schema::junction`](crate::schema::junction) already takes an intersection to be
//! about 14 m from its centre to its edge, so a box some 28 m across, and a divided-carriageway
//! link runs 15–30 m. Fifty clears that comfortably and sits well below the p75 of 99 m, so it
//! excludes the tail by construction. Relaxing 50 → 100 m buys 17 more changed ways; removing the
//! bound entirely buys 49 and admits the two above.
//!
//! **Metres, not node count.** A node count looks like a proxy for a short way and is not one: way
//! `23925587` in the same extract is a two-node way 497 m long.
//!
//! ## One hop, never a chain
//!
//! A donor must carry a real tag. An inherited count never becomes a donor, so a tagged way's
//! width can travel exactly one way and no further — a property of the rule's shape rather than a
//! limit someone has to remember to enforce. Chaining would also need iteration to a fixpoint
//! inside a pass whose output must be byte-identical at every thread count.
//!
//! # What is deliberately *not* touched
//!
//! Only the lane **count** — the width the carriageway is painted. Not
//! [`turn_masks`](crate::schema::roads::turn_masks) and not
//! [`carriageway`](crate::schema::roads::carriageway).
//!
//! A count is a physical property of the road that plainly continues across a junction. A turn
//! arrow and a directional split are surveys of a *particular* piece of asphalt, and they do not.
//! There is also a concrete trap: `osm_ingest::tags::build_dir_lanes` pads its mask list to the
//! lane count, so feeding an inferred count into the masks would grow the list and **draw turn
//! arrows that no one tagged**. Ways with `turn:lanes` and no `lanes` do exist — way `417976725`
//! on Rollins Road is one. Arrows stay strictly data-driven.
//!
//! # How it is computed
//!
//! A hash-partitioned external group-by, because the in-memory form does not survive contact with
//! a planet build. Every named road way contributes one record per endpoint; on the peninsula
//! extract that is tens of thousands, but scaled to a planet's ~70 M drivable ways it is of the
//! order of 4 GB against a build already peaking near 7 GB. Pre-filtering by node count was
//! measured and does not rescue it — it still holds ~1 GB while losing real candidates.
//!
//! So records go to [`PARTITIONS`] files, partitioned on the endpoint node id. Every way meeting
//! at a node lands in the same file, which is the only grouping the rule needs, and each file is
//! then sorted and grouped on its own. Peak memory is one partition rather than the whole planet.
//!
//! Recipients longer than [`MAX_STUB_M`] are dropped before they are ever written — an untagged
//! way is never a donor, so a long untagged way cannot affect anything and need not be stored.
//! That alone removes three quarters of the recipient records.
//!
//! Names are hashed to a `u64` rather than carried. A collision costs one wrong inheritance and
//! cannot cost more than that: the collinearity and one-wayness conditions still have to pass, and
//! the result is still bounded by [`MAX_STUB_M`] and by the minimum of the donors.

use std::fs::File;
use std::io::{BufWriter, Read, Write};
use std::path::{Path, PathBuf};

use osm_ingest::proto::{err, Error, Result};
use rayon::prelude::*;
use tile_build::par;

/// The longest way that may inherit, in metres. See the module docs for why 50 and why metres.
pub const MAX_STUB_M: f64 = 50.0;

/// How far from straight a donor may leave the shared node and still be the same road, in degrees.
///
/// Zero is a perfect continuation. 45° was measured and rejected: it admits 17 more candidates and
/// brings 17 more one-wayness disagreements with them, which is the whole of what it buys.
pub const MAX_TURN_DEGREES: f64 = 30.0;

/// How many files the endpoint records are partitioned across.
///
/// Only peak memory depends on this — one partition is held at a time — so it wants to be large
/// enough that a planet's records divide into pieces of tens of megabytes, and small enough that
/// the open file handles and the write buffers behind them stay cheap.
pub const PARTITIONS: usize = 256;

/// Metres per degree of latitude. Spherical, matching [`crate::schema::junction`], and worth
/// centimetres over the tens of metres this module measures.
const METRES_PER_DEGREE: f64 = 111_320.0;

/// One endpoint record, fixed width so a partition is read back without a varint decoder.
const RECORD_LEN: usize = 30;

/// Bit 0 of a record's flag byte: the way is `oneway=yes`.
const FLAG_ONEWAY: u8 = 1 << 0;
/// Bit 1: this record is the way's *last* node rather than its first.
const FLAG_LAST_END: u8 = 1 << 1;

/// One named road way offered to the join.
///
/// `nodes` and `line` must be the same length: a way whose extract cut some of its nodes away has
/// coordinates that no longer line up with its refs, and its neighbours are as likely to be
/// missing as its geometry. Such a way is skipped rather than guessed at.
pub struct Segment<'a> {
    pub id: i64,
    /// The OSM `name`. A way without one cannot take part.
    pub name: &'a str,
    /// The way's tagged lane count. Zero — no `lanes` tag — is what makes it a recipient; anything
    /// else makes it a donor.
    pub lanes: u8,
    pub oneway: bool,
    pub nodes: &'a [i64],
    /// The way's coordinates in lon/lat, parallel to `nodes`.
    pub line: &'a [(f64, f64)],
}

#[derive(Clone, Copy)]
struct Record {
    node: i64,
    way: i64,
    name_hash: u64,
    /// Degrees clockwise from north, pointing *away* from `node` along the way.
    bearing: f32,
    lanes: u8,
    flags: u8,
}

impl Record {
    fn oneway(self) -> bool {
        self.flags & FLAG_ONEWAY != 0
    }

    fn end(self) -> u8 {
        u8::from(self.flags & FLAG_LAST_END != 0)
    }

    fn write(self, out: &mut impl Write) -> Result<()> {
        let mut buf = [0u8; RECORD_LEN];
        buf[0..8].copy_from_slice(&self.node.to_le_bytes());
        buf[8..16].copy_from_slice(&self.way.to_le_bytes());
        buf[16..24].copy_from_slice(&self.name_hash.to_le_bytes());
        buf[24..28].copy_from_slice(&self.bearing.to_le_bytes());
        buf[28] = self.lanes;
        buf[29] = self.flags;
        out.write_all(&buf).map_err(|e| Error(format!("cannot write a lanefill partition: {e}")))
    }

    fn read(buf: &[u8]) -> Record {
        let at = |a: usize, b: usize| -> [u8; 8] { buf[a..b].try_into().expect("8 bytes") };
        Record {
            node: i64::from_le_bytes(at(0, 8)),
            way: i64::from_le_bytes(at(8, 16)),
            name_hash: u64::from_le_bytes(at(16, 24)),
            bearing: f32::from_le_bytes(buf[24..28].try_into().expect("4 bytes")),
            lanes: buf[28],
            flags: buf[29],
        }
    }
}

/// A way's two endpoint records, built off the serial path. See [`Collector::push_prepared`].
pub struct Prepared([Record; 2]);

/// What [`Collector::push`] writes for `segment`, or `None` where it writes nothing.
///
/// Pure over the segment: the same checks, in the same order, with the same arithmetic as the
/// inline body this was lifted out of, so a pool-built `Prepared` is bit-for-bit the record pair
/// the serial path produced.
pub fn prepare(segment: &Segment) -> Option<Prepared> {
    if segment.name.is_empty() || segment.nodes.len() < 2 {
        return None;
    }
    if segment.nodes.len() != segment.line.len() {
        return None;
    }
    let first = segment.nodes[0];
    let last = segment.nodes[segment.nodes.len() - 1];
    // A closed way has one endpoint, not two, so it can never have a donor at "both" ends and
    // is not a junction stub in any case.
    if first == last {
        return None;
    }
    // An untagged way is never a donor, so one too long to be a recipient cannot affect
    // anything. Dropping it here rather than at resolve time is what keeps the spill small.
    if segment.lanes == 0 && polyline_length_m(segment.line) > MAX_STUB_M {
        return None;
    }
    // The bearing leaving each end, skipping repeated coordinates: a duplicated vertex has no
    // direction, and the graph does contain them where a way was split at a coincident point.
    let head = leaving(segment.line, true)?;
    let tail = leaving(segment.line, false)?;

    let name_hash = hash_name(segment.name);
    let base = if segment.oneway { FLAG_ONEWAY } else { 0 };
    let record = |node: i64, bearing: f64, end: u8| Record {
        node,
        way: segment.id,
        name_hash,
        bearing: bearing as f32,
        lanes: segment.lanes,
        flags: base | end,
    };
    Some(Prepared([record(first, head, 0u8), record(last, tail, FLAG_LAST_END)]))
}

/// [`prepare`] for a way whose coordinates are read on demand: `coord(i)` is node `i`'s
/// coordinate, valid for every `i < nodes.len()` (the caller has checked every node resolves --
/// `prepare`'s `nodes.len() == line.len()` test).
///
/// The same decisions on the same arithmetic, reading only the coordinates they need: a donor
/// (`lanes > 0`) needs only the bearings leaving its two ends, and a recipient's length test can
/// stop as soon as the running sum passes [`MAX_STUB_M`] -- the sum of non-negative segments only
/// grows, so a prefix over the limit means the whole way is. On the planet every coordinate read
/// is a random read of an ~80 GB table, and lane inheritance read every coordinate of every named
/// road (134 s); most of them are long and decided after a few nodes.
pub fn prepare_lazy(
    id: i64,
    name: &str,
    lanes: u8,
    oneway: bool,
    nodes: &[i64],
    coord: impl Fn(usize) -> (f64, f64),
) -> Option<Prepared> {
    if name.is_empty() || nodes.len() < 2 {
        return None;
    }
    let first = nodes[0];
    let last = nodes[nodes.len() - 1];
    if first == last {
        return None;
    }
    if lanes == 0 {
        // `polyline_length_m`, summed in the same order, stopping once it is decided.
        let mut length = -0.0f64;
        let mut previous = coord(0);
        for i in 1..nodes.len() {
            let p = coord(i);
            let north = (p.1 - previous.1) * METRES_PER_DEGREE;
            let east = (p.0 - previous.0) * METRES_PER_DEGREE * previous.1.to_radians().cos();
            length += (north * north + east * east).sqrt();
            if length > MAX_STUB_M {
                return None;
            }
            previous = p;
        }
    }
    // `leaving`, scanning from each end for the first coordinate that differs.
    let origin = coord(0);
    let head = bearing(origin, (1..nodes.len()).map(&coord).find(|p| *p != origin)?);
    let origin = coord(nodes.len() - 1);
    let tail = bearing(origin, (0..nodes.len() - 1).rev().map(&coord).find(|p| *p != origin)?);

    let name_hash = hash_name(name);
    let base = if oneway { FLAG_ONEWAY } else { 0 };
    let record = |node: i64, bearing: f64, end: u8| Record {
        node,
        way: id,
        name_hash,
        bearing: bearing as f32,
        lanes,
        flags: base | end,
    };
    Some(Prepared([record(first, head, 0u8), record(last, tail, FLAG_LAST_END)]))
}

/// Accumulates endpoint records, then resolves them into the ways whose lane count changes.
pub struct Collector {
    files: Vec<BufWriter<File>>,
    paths: Vec<PathBuf>,
}

impl Collector {
    /// Open the partition files. `stem` is a scratch path; each partition appends to it.
    pub fn create(stem: &Path) -> Result<Collector> {
        let mut files = Vec::with_capacity(PARTITIONS);
        let mut paths = Vec::with_capacity(PARTITIONS);
        for i in 0..PARTITIONS {
            let path = stem.with_extension(format!("lanefill{i:03}.tmp"));
            let file = File::create(&path)
                .map_err(|e| Error(format!("cannot create {}: {e}", path.display())))?;
            // Small buffers: there are `PARTITIONS` of them held open at once, and each takes a
            // steady trickle rather than a burst.
            files.push(BufWriter::with_capacity(1 << 14, file));
            paths.push(path);
        }
        Ok(Collector { files, paths })
    }

    /// Offer one way to the join. Ways that cannot take part are dropped here.
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn push(&mut self, segment: &Segment) -> Result<()> {
        match prepare(segment) {
            Some(prepared) => self.push_prepared(&prepared),
            None => Ok(()),
        }
    }

    /// Write the two endpoint records [`prepare`] built, in the order `push` always wrote them
    /// (first end, then last end). Split from `push` so the geometry -- length, bearings, name
    /// hash -- runs on the pool and only these two fixed-width writes stay serial.
    pub fn push_prepared(&mut self, prepared: &Prepared) -> Result<()> {
        for record in prepared.0 {
            record.write(&mut self.files[partition_of(record.node)])?;
        }
        Ok(())
    }

    /// Resolve every partition and return `(way id, inherited lane count)` for the ways that
    /// actually change, ascending by way id so the materialise pass can binary-search it.
    ///
    /// Ways whose inherited count equals the default they would have had anyway are left out, the
    /// same way [`crate::corridor::promote`] leaves out ways its corridor does not move.
    pub fn finish(mut self) -> Result<Vec<(i64, u8)>> {
        for file in &mut self.files {
            file.flush().map_err(|e| Error(format!("cannot flush a lanefill partition: {e}")))?;
        }
        drop(self.files);

        // `(way, end, donor lanes, one-way)`. The only state that outlives a partition, because a
        // way's two endpoints hash to two different files and the "donor at both ends" condition
        // cannot be decided until both have been seen.
        //
        // The 256 partitions are independent — every record for a node lives in exactly the one
        // partition `partition_of` sends it to — so each is read, sorted and paired on its own
        // thread and the per-partition pairs concatenated. `found` is globally sorted below, so the
        // concatenation order never reaches the output: the resolution stays independent of both
        // the write order and the partition order.
        let per_partition: Vec<Vec<(i64, u8, u8, bool)>> = par::install(|| {
            self.paths
                .par_iter()
                .map(|path| -> Result<Vec<(i64, u8, u8, bool)>> {
                    let mut records: Vec<Record> = Vec::new();
                    read_partition(path, &mut records)?;
                    let mut local: Vec<(i64, u8, u8, bool)> = Vec::new();
                    if !records.is_empty() {
                        // Sorted rather than hashed, for the reason `corridor::promote` gives: a
                        // hash map over the record count is the largest thing this pass would
                        // otherwise allocate. The full key makes the order total, so the output
                        // does not depend on the write order.
                        records.sort_unstable_by_key(|r| (r.node, r.way, r.flags));
                        for run in records.chunk_by(|a, b| a.node == b.node) {
                            pair_up(run, &mut local);
                        }
                    }
                    Ok(local)
                })
                .collect::<Result<Vec<_>>>()
        })?;
        let mut found: Vec<(i64, u8, u8, bool)> = per_partition.into_iter().flatten().collect();
        for path in &self.paths {
            let _ = std::fs::remove_file(path);
        }

        found.sort_unstable();
        let mut out: Vec<(i64, u8)> = Vec::new();
        for run in found.chunk_by(|a, b| a.0 == b.0) {
            if !run.iter().any(|e| e.1 == 0) || !run.iter().any(|e| e.1 == 1) {
                continue;
            }
            let inherited = run.iter().map(|e| e.2).min().unwrap_or(0);
            // What the renderer would have drawn without us: `tile/geometry.rs` turns a zero lane
            // count into one lane for a one-way and two for anything else.
            let default = if run[0].3 { 1 } else { 2 };
            if inherited != default {
                out.push((run[0].0, inherited));
            }
        }
        Ok(out)
    }
}

/// Every recipient in `run` against every donor, appending the pairs that survive the conditions.
fn pair_up(run: &[Record], found: &mut Vec<(i64, u8, u8, bool)>) {
    for recipient in run.iter().filter(|r| r.lanes == 0) {
        for donor in run.iter().filter(|d| d.lanes > 0) {
            if donor.way == recipient.way
                || donor.name_hash != recipient.name_hash
                || donor.oneway() != recipient.oneway()
            {
                continue;
            }
            if deviation(f64::from(recipient.bearing), f64::from(donor.bearing)) > MAX_TURN_DEGREES
            {
                continue;
            }
            found.push((recipient.way, recipient.end(), donor.lanes, recipient.oneway()));
        }
    }
}

fn read_partition(path: &Path, out: &mut Vec<Record>) -> Result<()> {
    out.clear();
    let mut file =
        File::open(path).map_err(|e| Error(format!("cannot open {}: {e}", path.display())))?;
    let mut bytes = Vec::new();
    file.read_to_end(&mut bytes)
        .map_err(|e| Error(format!("cannot read {}: {e}", path.display())))?;
    if bytes.len() % RECORD_LEN != 0 {
        return err(format!(
            "{} holds {} bytes, which is not a whole number of {RECORD_LEN}-byte records",
            path.display(),
            bytes.len(),
        ));
    }
    out.reserve(bytes.len() / RECORD_LEN);
    for chunk in bytes.chunks_exact(RECORD_LEN) {
        out.push(Record::read(chunk));
    }
    Ok(())
}

/// Which partition an endpoint node belongs to.
///
/// Mixed rather than taken modulo directly: OSM node ids are allocated in runs, so the low bits of
/// neighbouring ids are correlated and a plain modulo would fill a few partitions and starve the
/// rest. This is the SplitMix64 finaliser, which is cheap and spreads the low bits.
fn partition_of(node: i64) -> usize {
    let mut z = (node as u64) ^ 0x9e37_79b9_7f4a_7c15;
    z = (z ^ (z >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    ((z ^ (z >> 31)) % PARTITIONS as u64) as usize
}

/// FNV-1a over the name's bytes. See the module docs on why a collision is survivable.
fn hash_name(name: &str) -> u64 {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in name.as_bytes() {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

/// The bearing leaving the line's first (or last) point, walking past repeated coordinates.
///
/// `None` when every point is the same place, which has no direction to report.
fn leaving(line: &[(f64, f64)], from_start: bool) -> Option<f64> {
    if from_start {
        let origin = *line.first()?;
        Some(bearing(origin, *line.iter().skip(1).find(|p| **p != origin)?))
    } else {
        let origin = *line.last()?;
        Some(bearing(origin, *line.iter().rev().skip(1).find(|p| **p != origin)?))
    }
}

/// Bearing from `a` to `b` in degrees clockwise from north. Both are lon/lat.
fn bearing(a: (f64, f64), b: (f64, f64)) -> f64 {
    let north = b.1 - a.1;
    let east = (b.0 - a.0) * a.1.to_radians().cos();
    east.atan2(north).to_degrees()
}

/// How far two bearings are from being a straight continuation of one another, in degrees.
///
/// Both point *away* from the node they share, so a road running straight through it leaves on two
/// bearings 180° apart and scores zero.
fn deviation(a: f64, b: f64) -> f64 {
    let mut delta = (a - b) % 360.0;
    if delta > 180.0 {
        delta -= 360.0;
    } else if delta < -180.0 {
        delta += 360.0;
    }
    (180.0 - delta.abs()).abs()
}

include!("lanefill_part1.rs");

#[cfg(test)]
mod lazy_tests {
    use super::*;

    /// `prepare_lazy` decides every way exactly as `prepare` on its full line does: same `None`s,
    /// same records, for donors and recipients, short and long, with repeated vertices at either
    /// end and degenerate ways.
    #[test]
    fn lazy_preparation_matches_preparing_the_whole_line() {
        let mut state = 0x2545_F491_4F6C_DD1Du64;
        let mut next = || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state
        };
        for case in 0..20_000i64 {
            let n = 2 + (next() % 12) as usize;
            let step = [1e-6, 1e-5, 1e-4, 3e-4][(next() % 4) as usize];
            let mut line = Vec::with_capacity(n);
            let (mut x, mut y) = (-122.3 + (next() % 1000) as f64 * 1e-3, 37.5);
            for _ in 0..n {
                if next() % 5 != 0 {
                    x += ((next() % 200) as f64 - 100.0) * step;
                    y += ((next() % 200) as f64 - 100.0) * step;
                }
                line.push((x, y));
            }
            let mut nodes: Vec<i64> = (0..n as i64).map(|k| 1000 + k).collect();
            if case % 9 == 0 {
                let last = nodes.len() - 1;
                nodes[last] = nodes[0];
            }
            let lanes = (next() % 3) as u8;
            let oneway = next() % 2 == 0;
            let name = if case % 13 == 0 { "" } else { "Main St" };
            let whole = prepare(&Segment { id: case, name, lanes, oneway, nodes: &nodes, line: &line });
            let lazy = prepare_lazy(case, name, lanes, oneway, &nodes, |i| line[i]);
            match (whole, lazy) {
                (None, None) => {}
                (Some(a), Some(b)) => {
                    for (ra, rb) in a.0.iter().zip(b.0.iter()) {
                        let (mut ba, mut bb) = (Vec::new(), Vec::new());
                        ra.write(&mut ba).unwrap();
                        rb.write(&mut bb).unwrap();
                        assert_eq!(ba, bb, "case {case}");
                    }
                }
                (a, b) => panic!("case {case}: whole {} lazy {}", a.is_some(), b.is_some()),
            }
        }
    }
}
