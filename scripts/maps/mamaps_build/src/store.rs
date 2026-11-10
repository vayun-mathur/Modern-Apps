//! The feature store: classified features staged **outside the heap** rather than in memory.
//!
//! # Why this exists
//!
//! Measured on `california-latest.osm.pbf`, the generator peaked at 10.03 GB — so essentially
//! none of it was the tiler. It was all stage A, where three
//! large things were alive at once:
//!
//! | | California |
//! |---|
//! | materialised features, lon/lat `f64` | ~4.9 GB |
//! | classified ways and their node refs | ~2.7 GB |
//! | node id -> location table | ~2.5 GB |
//!
//! The features were the largest and the only one that did not have to be resident: nothing reads
//! them until the tiler does, and the tiler reads them **once per zoom, in order**. So they are
//! staged outside the heap -- anonymous pagefile memory when the commit budget allows (no `.tmp`
//! files, nothing stranded on kill), a scratch file beside the output when it does not -- and the
//! tiler streams them fifteen times instead of holding them fifteen times over. Which backend is
//! the budget gate's decision ([`osm_ingest::mem::SpillPlan`]), made from the counts pass 1
//! measured; same records either way, so the backend never changes a hash.
//!
//! # The format is `tile_build`'s, not a new one
//!
//! [`spill::NormalizedWriter`] already writes exactly this: a length-prefixed record per feature
//! holding a lon/lat geometry and a property list. Reusing it means the encoding is already tested,
//! already round-tripped, and already handles every geometry kind — and it costs one small
//! indirection: the [`Class`] is packed into a single integer property. That is the plan's own
//! suggestion, and it is why this module needed no format of its own.
//!
//! The one change since made to that format was made *there*, for both its consumers: the geometry
//! is stored as `i32` e7 rather than `f64` degrees, halving the spill. Every coordinate `osm_ingest`
//! produces is already an e7 integer, so this archive is byte-identical across that change; see
//! [`spill::encode_geometry`].
//!
//! # The second table: classified ways
//!
//! [`WaySink`] and [`WayReader`] do the same for the ~2.7 GB row of that table, for the same reason
//! and by a different route: a way's record is a handful of integers rather than a geometry, and it
//! is read back in the order it was written, so it gets a format of its own rather than
//! `NormalizedWriter`'s. Same backend choice as the features (anon or file, budget-gated); the
//! spill is freed right after materialise, before the routing graph is loaded, so the two never
//! peak together. See [`WaySink`] for why no sort is needed on the way back.
//!
//! # The third table: relation member ways
//!
//! Neither spill serves the one random access in stage A: a relation reaches its member ways by
//! id, in the order it lists them. So the members -- and only the members -- stay resident, in a
//! sorted vector ([`MemberWays`](crate::extract::MemberWays)) rather than a hash map: at planet's
//! 8.4 M member ways the map's per-entry overhead is hundreds of MB beside the ref bytes. Freed
//! with the coordinate table after materialise, before the externals.

use std::fs::File;
use std::io::{BufRead, BufReader, BufWriter, Write};
use std::path::{Path, PathBuf};

use osm_ingest::proto::{err, zigzag, Error, Result};
use tile_build::geom::{bounds as geom_bounds, Geometry, Rect};
use tile_build::mvt::Value;
use tile_build::spill::{
    encode_geometry, GeomKind, NormalizedChunks, NormalizedReader, NormalizedWriter,
    NORM_CHUNK_FEATURES,
};

use crate::extract::Feature;
use crate::schema::Class;
use tilecodec::mamaps::body::{BuildingAttrs, Carriageway};

/// The property key the packed [`Class`] travels under.
///
/// One byte, because it is written once per feature and 15.5 M allocations of a longer name is a
/// cost with nothing to show for it.
const CLASS_KEY: &str = "c";

/// The property key a transit line's colour travels under, as a `Uint` of `0xRRGGBB`.
///
/// Only `transit` features set it. Absent for everything else, so the spill pays nothing for the
/// 200 M features that have no colour.
const COLOR_KEY: &str = "t";

/// The property key a transit line's lane inputs travel under, as a `Uint` packing the colour's
/// ordinal, the corridor's colour count and the taper fraction one byte each.
///
/// A second key rather than packed alongside the colour: the two are decided in different places
/// (the colour is the agency's, the lane inputs are the exporter's). Three bytes in one `Uint`
/// rather than three keys, because a spilled key is an owned `String` per feature.
const LANE_KEY: &str = "s";

/// The property key a label's display name travels under, when it has one.
///
/// `places` and `poi` are the only layers that set it. Absent (not empty) for everything else,
/// so the spill pays nothing for the 190 M features that have no name.
const NAME_KEY: &str = "n";

/// The property key a label's tagged OSM element id travels under, as a `Uint`.
///
/// Only `places` and `poi` features set it, and only when the element had an id to carry. Absent
/// (not zero) otherwise, so the spill pays nothing for the 190 M features that have none — the
/// same bargain [`NAME_KEY`] makes.
const ID_KEY: &str = "i";

/// The property key a road's carriageway lane count travels under, as a `Uint`.
///
/// Only `roads` features with a `lanes` tag set it. Absent (not zero) otherwise, so the spill
/// pays nothing for the roads with no lane data and for every non-road — the same bargain
/// [`NAME_KEY`] and [`ID_KEY`] make.
const LANE_COUNT_KEY: &str = "l";

/// The property keys a road's per-lane turn masks travel under, forward and backward, each a
/// `|`-separated decimal string of `LANE_*` masks (the same [`osm_ingest::roads::pack_lanes`] form
/// the graph and pmtiles use). Only roads with `turn:lanes` set them; absent otherwise, so the
/// spill pays nothing for the roads and every non-road that have none.
const TURN_FWD_KEY: &str = "tf";
const TURN_BWD_KEY: &str = "tb";

/// The property key a road's carriageway travels under, as a `Uint` packing the forward and
/// backward lane counts a byte each and the solid-divider bits above them (see
/// [`pack_carriageway`]).
///
/// Only a road whose split was actually surveyed sets it. Absent (not zero) otherwise, so the
/// spill pays nothing for the roads whose division is unknown — which is most of them, and the
/// same bargain [`NAME_KEY`] and [`LANE_COUNT_KEY`] make.
const CARRIAGEWAY_KEY: &str = "cw";

/// A [`Carriageway`] as the single `Uint` the spill carries it as: forward in bits 0..8, backward
/// in 8..16, the divider bits in 16..48. Lossless, and the inverse is [`unpack_carriageway`].
fn pack_carriageway(c: Carriageway) -> u64 {
    (c.forward as u64) | ((c.backward as u64) << 8) | ((c.solid_dividers as u64) << 16)
}

fn unpack_carriageway(bits: u64) -> Carriageway {
    Carriageway {
        forward: bits as u8,
        backward: (bits >> 8) as u8,
        solid_dividers: (bits >> 16) as u32,
    }
}

/// The property keys a building's S3DB attributes travel under, three `Uint`s packing the eight
/// [`BuildingAttrs`] fields (see [`pack_building`]). Only `buildings` features set them, and every
/// building sets all three — even one with no S3DB tags, whose attributes are all zero — so a
/// building stays distinguishable from a non-building on the way back and the side table the tiler
/// builds stays dense-parallel to the layer's features.
const BLD_A_KEY: &str = "ba";
const BLD_B_KEY: &str = "bb";
const BLD_C_KEY: &str = "bc";

/// A [`BuildingAttrs`] packed into the three `Uint`s the spill carries it as.
///
/// `a` holds the three heights and the roof shape; `b` the two roof bytes and the wall colour;
/// `c` the roof colour. Lossless — every field is copied bit for bit — and the inverse is
/// [`unpack_building`].
fn pack_building(a: &BuildingAttrs) -> (u64, u64, u64) {
    let word_a = ((a.height as u64) << 40)
        | ((a.min_height as u64) << 24)
        | ((a.roof_height as u64) << 8)
        | (a.roof_shape as u64);
    let word_b = ((a.roof_direction as u64) << 40)
        | ((a.roof_orientation as u64) << 32)
        | (a.building_colour as u64);
    (word_a, word_b, a.roof_colour as u64)
}

fn unpack_building(a: u64, b: u64, c: u64) -> BuildingAttrs {
    BuildingAttrs {
        height: (a >> 40) as u16,
        min_height: (a >> 24) as u16,
        roof_height: (a >> 8) as u16,
        roof_shape: a as u8,
        roof_direction: (b >> 40) as u8,
        roof_orientation: (b >> 32) as u8,
        building_colour: b as u32,
        roof_colour: c as u32,
    }
}

/// Parse a `|`-separated decimal mask string ([`osm_ingest::roads::pack_lanes`]'s output) back into
/// per-lane `LANE_*` masks. An empty string is no lanes.
fn unpack_lanes(packed: &str) -> Result<Vec<u16>> {
    if packed.is_empty() {
        return Ok(Vec::new());
    }
    packed
        .split('|')
        .map(|t| {
            t.parse::<u16>()
                .map_err(|_| Error(format!("a spilled turn mask `{t}` is not a u16")))
        })
        .collect()
}

/// A [`Class`] as one integer, so a feature's classification costs one property rather than seven.
///
/// | field | bits | width |
/// |---|---|---|
/// | `layer` | 0..4 | 10 layers (v2: `places`, `poi`, `transit`) |
/// | `kind` | 4..20 | `u16` |
/// | `kind_detail` | 20..36 | `u16` |
/// | `flags` | 36..44 | `u8` |
/// | `area` | 44..45 | one bit |
/// | `min_zoom` | 45..50 | 0..31 |
/// | `min_area_px` | 50..58 | 0..255, integral |
///
/// `min_area_px` is an `f64` in [`Class`] and a byte here. Every value the schema uses is a small
/// whole number of square pixels, and [`pack`] refuses anything else rather than rounding a
/// threshold silently.
fn pack(class: &Class) -> Result<u64> {
    if class.layer > 0b1111 {
        return err(format!("layer {} does not fit the packed class", class.layer));
    }
    if class.min_zoom > 0b11111 {
        return err(format!("min_zoom {} does not fit the packed class", class.min_zoom));
    }
    // Refused rather than rounded: a threshold quietly changed is a layer quietly wrong.
    if class.min_area_px < 0.0
        || class.min_area_px > 255.0
        || class.min_area_px.fract() != 0.0
    {
        return err(format!(
            "min_area_px {} is not a whole number of square pixels in 0..=255",
            class.min_area_px,
        ));
    }
    Ok((class.layer as u64)
        | ((class.kind as u64) << 4)
        | ((class.kind_detail as u64) << 20)
        | ((class.flags as u64) << 36)
        | ((class.area as u64) << 44)
        | ((class.min_zoom as u64) << 45)
        | ((class.min_area_px as u64) << 50))
}

fn unpack(bits: u64) -> Class {
    Class {
        layer: (bits & 0b1111) as u8,
        kind: ((bits >> 4) & 0xffff) as u16,
        kind_detail: ((bits >> 20) & 0xffff) as u16,
        flags: ((bits >> 36) & 0xff) as u8,
        area: (bits >> 44) & 1 == 1,
        min_zoom: ((bits >> 45) & 0b11111) as u8,
        min_area_px: ((bits >> 50) & 0xff) as f64,
    }
}

/// A geometry encoded for the feature spill, plus the two facts the spill
/// derives from it.
///
/// Built on the pool by the materialise drains (`EncodedGeom::live` is pure:
/// [`encode_geometry`](tile_build::spill::encode_geometry),
/// [`GeomKind::of`](tile_build::spill::GeomKind::of), [`bounds`](tile_build::geom::bounds)
/// and the e7 box below touch nothing shared), appended in order by the serial
/// drain. Byte-identical to encoding in the sink: all four are pure functions
/// of the same geometry.
pub struct EncodedGeom {
    kind: GeomKind,
    bytes: Vec<u8>,
    bounds: Option<Rect>,
    ebbx: Option<(i32, i32, i32, i32)>,
}

impl EncodedGeom {
    /// Encode a live geometry: what the sink always did inline, exposed so
    /// callers with no pool pass the same shape the pool-built path does.
    /// One path, two speeds.
    pub fn live(geometry: &Geometry) -> Result<EncodedGeom> {
        let mut bytes = Vec::new();
        encode_geometry(geometry, &mut bytes)
            .map_err(|e| osm_ingest::proto::Error(e.to_string()))?;
        Ok(EncodedGeom {
            kind: GeomKind::of(geometry),
            bytes,
            bounds: geom_bounds(geometry),
            ebbx: ebbx_of(geometry),
        })
    }
}

/// The sink bbox's e7 box for one geometry: min/max over ALL vertices with
/// [`Sink::grow`]'s exact conversion.
///
/// `bounds` above skips non-finite vertices (matching
/// [`tile_build::geom::bounds`]); this must not: the old per-vertex walk
/// folded `(NaN * 1e7) as i32` — which saturates to 0 — so a NaN vertex
/// contributed 0 to the box. Reproducing the walk exactly (rather than
/// converting `bounds`' corners) keeps the box identical even where a
/// shapefile-derived coordinate misbehaves. `None` only when the geometry
/// holds no vertices at all, in which case the old walk visited nothing and
/// the box was unchanged.
fn ebbx_of(geometry: &Geometry) -> Option<(i32, i32, i32, i32)> {
    let mut acc: Option<(i32, i32, i32, i32)> = None;
    let mut visit = |&(lon, lat): &(f64, f64)| {
        let (x, y) = ((lon * 1e7) as i32, (lat * 1e7) as i32);
        acc = Some(match acc {
            None => (x, y, x, y),
            Some(b) => (b.0.min(x), b.1.min(y), b.2.max(x), b.3.max(y)),
        });
    };
    match geometry {
        Geometry::Points(points) => points.iter().for_each(&mut visit),
        Geometry::Lines(lines) => lines.iter().flatten().for_each(&mut visit),
        Geometry::Polygons(polygons) => {
            polygons.iter().flatten().flatten().for_each(&mut visit)
        }
    }
    acc
}

/// Accumulates features into a file, tracking the bounding box as it goes.
///
/// The bbox is computed here rather than in a pass of its own, because a pass of its own would be a
/// sixteenth read of a four-gigabyte file to learn four numbers.
pub struct Sink {
    writer: NormalizedWriter,
    /// The shallowest `min_zoom` in each spill chunk, and the running one for the chunk being filled.
    ///
    /// One byte per 64 features — 243 KB on California — and it is what lets the tiler **skip** a
    /// chunk rather than deserialise it. Every feature carries a `min_zoom` and most are deep: z14
    /// alone holds 16.9 M against ~5 M across z0..z12. Without this the reader parses the whole
    /// 2.0 GB spill once per zoom, which measured 55.2 s of a 180 s build back when the spill was
    /// 3.3 GB, all of it on one thread.
    chunk_mins: Vec<u8>,
    filling: u8,
    props: Vec<(String, Value)>,
    count: u64,
    bbox: Option<(i32, i32, i32, i32)>,
}

include!("store_part1.rs");
include!("store_part2.rs");
include!("store_part3.rs");
include!("store_part4.rs");
include!("store_part5.rs");
include!("store_part6.rs");