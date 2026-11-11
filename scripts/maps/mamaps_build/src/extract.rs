//! Stage A: `.osm.pbf` in, classified geometry in lon/lat out.
//!
//! Three passes, because a PBF is ordered nodes-then-ways-then-relations and a way's geometry needs
//! coordinates that arrived before its tags did:
//!
//! 1. **ways + relations.** Classify tags. Spill the node refs of every classified way to a
//!    sequential file and keep the member way ids of every classified relation. Node blobs are
//!    skipped outright.
//! 2. **ways again**, keeping the refs of the ways some relation lists as a member. A second pass
//!    rather than keeping every untagged way's refs from the first, because "every untagged way in
//!    California" is 40 M node ids and the members are a few hundred thousand.
//! 3. **nodes.** Fill coordinates for exactly the ids the first two passes asked for, at 16 bytes
//!    per needed node.
//!
//! That last point is the whole memory argument. [`osm_ingest::nodeloc::NodeLocations`] costs
//! `O(needed)`; `graph_build`'s bitset costs 2.5 GB keyed by raw OSM node id whatever the extract,
//! and peaks near 10 GB on California. A water-and-buildings build needs a few million nodes, so it
//! pays tens of megabytes.
//!
//! # Nothing large is resident across the passes
//!
//! Neither of the two big tables this stage produces is held in memory. Features go to
//! [`Store`]; classified ways go to [`WaySink`], exploiting the fact that pass 1 already sees them
//! in ascending id order so no sort is needed to read them back in the order the archive wants.
//! What stays resident is the set a sequential file cannot serve: the refs of the ways that some
//! relation lists as a member, because a relation reaches those by id in its own order.
//!
//! # Collecting the needed ids: two shapes, one switch
//!
//! Pass 3 needs the ids sorted and unique, and there are two ways to get there. The obvious one --
//! one `Vec<i64>` of every ref **with duplicates**, sorted and deduped in place -- is what
//! [`collect_needed_in_memory`] does, and it is right up to a point: it is exact, it needs no
//! assumption about the ids, and on anything smaller than a continent it is smaller than the
//! alternative. Past that point it is the largest thing in the whole build. North-america is ~19 GB
//! of it; planet projects to ~96 GB and a sort of 12 G elements.
//!
//! [`collect_needed_by_bitset`] is the other shape, taken above [`REFS_IN_MEMORY`]: a bit per node
//! id, which is at most ~1.7 GB *for any extract, forever*, because OSM's node ids are monotonic and
//! near 13 G. The bitset's words move into the rank index directly ([`NodeLocations::from_bitset`])
//! rather than being walked out as ids and re-set -- walking them out would hold two ~1.5 GB words
//! arrays at once. Both paths must produce the same sequence and
//! `the_two_ref_collectors_agree_on_the_same_input` holds them to it.
//!
//! # Nothing large outlives its phase
//!
//! The tables above are freed as soon as their last reader is done, not at the end of the stage:
//! the blob-kinds mask after the node pass, the ways spill (plus corridor overrides and inherited
//! lane counts) after `materialise_ways`, and relations, member refs and the coordinate table
//! after `materialise_relations` -- before the coastline, transit and routing-graph externals.
//! The planet build died at the junction tail with the OSM tables still resident beside a freshly
//! `Graph::load`ed graph; the frees plus the graph's own file mappings (no heap `Vec`s, no commit
//! charge) are what keep those two peaks apart. `plan_ways_spill` / `plan_feature_spill` decide
//! the anon-vs-file backend from the counts before the bytes are asked for.

use std::collections::HashMap;
use std::path::Path;

use osm_ingest::nodeloc::{resolve_nodes_with, NodeLocations};
use osm_ingest::osm::{visit_block, Element, NodeView, MEMBER_NODE, MEMBER_WAY};
use osm_ingest::pbf::{self, KIND_RELATIONS, KIND_WAYS};
use osm_ingest::proto::{err, Result};
use osm_ingest::rings::{self, MemberWay, RingStats};
use osm_ingest::select::Select;
use tile_build::geom::Geometry;
use tile_build::par;
use tile_build::progress::Progress;
use rayon::prelude::*;

use crate::schema::{self, Class, Layers};
use crate::store::{
    PreRecord, RecordRun, Sink, Store, WayBlock, WayBlockLoc, WayBlocks, WayCounts, WayReader, WaySink,
    WAY_BLOCK_WAYS,
};
use tilecodec::mamaps::body::{BuildingAttrs, Carriageway};
use tilecodec::mamaps::dict::LAYER_BUILDINGS;

/// One classified feature, in lon/lat, ready to tile.
///
/// `name` is the coalesced display label for `places` and `poi` points, `None` for every other
/// layer. Carried here (not in [`Class`]) because it is a string and `Class` is `Copy`.
/// `transit_color` is the aggregated route colour for `transit` lines (`0xRRGGBB`), zero for
/// every other layer, and `transit_ordinal`/`transit_lanes`/`transit_taper` are the inputs the
/// renderer needs to place such a line in a lane of the corridor it shares.
pub struct Feature {
    pub class: Class,
    pub geometry: Geometry,
    pub name: Option<String>,
    /// The OSM element this came from, tagged by id space — see [`tagged_id`].
    ///
    /// [`ID_NONE`](tilecodec::mamaps::body::ID_NONE) for every layer but `places` and `poi`.
    /// Only those two reach the archive's id table, and only those two are pure points that
    /// `coalesce` never merges, so only those two have an id that means anything.
    pub id: u64,
    pub transit_color: u32,
    pub transit_ordinal: u8,
    pub transit_lanes: u8,
    pub transit_taper: u8,
    /// A `roads` feature's carriageway lane count, zero for every other layer and for a road with
    /// no `lanes` tag. Baked into the body feature so the renderer can draw the carriageway as its
    /// individual lanes at high zoom. See [`crate::schema::roads::lane_count`].
    pub lane_count: u8,
    /// A road's per-lane turn-indication masks, `(forward, backward)`, left to right — the
    /// `LANE_*` bits of OSM `turn:lanes[:forward|:backward]`. Empty for every other layer and for
    /// a road with no `turn:lanes`. Baked into the body's turn-lane side table so the renderer can
    /// draw per-lane arrows at junctions. See [`crate::schema::roads::turn_masks`].
    pub turn_fwd: Vec<u16>,
    pub turn_bwd: Vec<u16>,
    /// How a road's lanes divide between the two directions, and which dividers between them may
    /// not be crossed. Default for every other layer and for a road whose split was never
    /// surveyed. Baked into the body's carriageway side table so the renderer can put the centre
    /// line where it belongs rather than down the middle. See [`crate::schema::roads::carriageway`].
    pub carriageway: Carriageway,
    /// A building's OSM Simple 3D Buildings attributes (height, roof, colours), `None` for every
    /// non-building feature. Baked into the body's building side table so the renderer can extrude
    /// the footprint in 3D. See [`crate::schema::buildings::attrs`].
    pub building: Option<BuildingAttrs>,
}

/// The id space an [`Feature::id`] came from, in the low two bits.
///
/// OSM numbers nodes, ways and relations independently and the three sequences overlap freely,
/// so a bare id does not identify an element. Tags start at one rather than zero so that a fully
/// zero id stays reserved for [`ID_NONE`](tilecodec::mamaps::body::ID_NONE).
pub const ELEMENT_NODE: u64 = 1;
pub const ELEMENT_WAY: u64 = 2;
pub const ELEMENT_RELATION: u64 = 3;

/// An OSM id tagged with its id space, or `ID_NONE` for an id OSM would never issue.
pub fn tagged_id(id: i64, element: u64) -> u64 {
    // OSM element ids start at 1. A zero or negative one is a synthetic element (a coastline
    // polygon, a GTFS shape) with no upstream identity to carry.
    if id <= 0 {
        return tilecodec::mamaps::body::ID_NONE;
    }
    ((id as u64) << 2) | element
}

/// What a run of stage A did, for the build report.
#[derive(Debug, Default, Clone)]
pub struct Stats {
    pub ways_classified: u64,
    pub relations_classified: u64,
    pub features: u64,
    /// Classified elements whose geometry could not be built — an extract cut through them, or a
    /// relation's rings would not close.
    pub geometry_failed: u64,
    pub nodes_needed: u64,
    /// Land polygons read from a prepared coastline product, if one was given.
    pub land_polygons: u64,
    /// Nodes classified as `places`/`poi` labels.
    pub nodes_classified: u64,
    /// Coloured rail lines read from a prepared GTFS transit-routes export, if one was given.
    pub transit_routes: u64,
    /// Drivable component segments emitted into the `traffic` layer from the v6 routing graph.
    pub traffic_segments: u64,
    /// Lane connectors emitted into the `junction` layer from the same graph.
    pub junction_connectors: u64,
    /// Road ways whose `min_zoom` was pulled shallower to match their corridor.
    pub corridor_promotions: u64,
    /// Untagged road ways that took a lane count from a tagged neighbour. See [`crate::lanefill`].
    pub lanes_inherited: u64,
    pub rings: RingStats,
}

/// A classified relation, held between passes.
struct Relation {
    class: Class,
    /// `(way id, role is inner)`, in the order the relation lists them, which is what
    /// [`rings::assemble`] stitches.
    members: Vec<(i64, bool)>,
    /// A relation carrying a shape is stitched into rings; one carrying a border is emitted as its
    /// member lines. Which it is comes from [`Class::area`] rather than from the relation's `type`
    /// tag, because both an administrative border and a protected area are `type=boundary`.
    area: bool,
    /// The display label for a `places` relation (a country, a region), `None` otherwise.
    name: Option<String>,
    /// The relation's own OSM id, tagged — see [`tagged_id`]. Only read for label relations.
    id: i64,
    /// A building relation's S3DB attributes (a multipolygon `building=*`), `None` otherwise.
    building: Option<BuildingAttrs>,
    /// A `place` relation's headcount (`population` tag), for country label zoom scoring
    /// (see `extract_fallback::country_zoom`). Read in pass 1 while the tags are in hand;
    /// `None` for every non-place relation and for places nobody counted (Antarctica).
    population: Option<u64>,
    /// The ISO 3166-1 code of an `admin_level=2` relation, `None` for every other relation. What
    /// the tile's marking convention is resolved from — see [`crate::schema::boundaries::Conventions`].
    iso: Option<String>,
}

include!("extract_part1.rs");
include!("extract_part2.rs");
include!("extract_part3.rs");
include!("extract_part4.rs");