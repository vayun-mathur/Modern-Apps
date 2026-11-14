//! `junction`: one line per **lane connector** through an intersection.
//!
//! A lane connector is the path a single lane follows from an approach into an exit — the left-turn
//! lane's arc, the through lane's stripe, the slip road peeling off a motorway. The renderer draws
//! each as a one-lane ribbon and does no curve maths, so every feature here is an already-sampled
//! polyline rather than a control-point set.
//!
//! Geometry comes from the same v6 routing graph [`crate::schema::traffic`] reads —
//! `metadata.bin`, `nodes.bin`, `edges.bin`, `intermediate.bin`, plus `lanes.bin` — and that module
//! is where the on-disk format, the escape tables and the polyline decode are documented. This one
//! only adds what a connector needs on top: which nodes are junctions, which way each incident edge
//! points, and which lane goes where.
//!
//! # What is data and what is inference
//!
//! **Be clear about this, because most of it is inference.** OSM has essentially no lane
//! connectivity data. `connectivity` relations exist but are vanishingly rare and nothing in this
//! tree reads them. So:
//!
//! | Thing | Where it comes from |
//! |---|---|
//! | Which nodes are junctions | **Data.** The generator collapses degree-2 chains, so a surviving node of degree ≥ 3 is a real fork. |
//! | Where each approach and exit runs | **Data.** Node coordinates and edge polylines. |
//! | Approach and exit headings | **Data**, derived: `atan2` over the first geometry step, because the graph stores no headings. |
//! | Which movements are legal | **Inference.** Every exit but a reversal is assumed legal — excluded by *angle*, not just by whether it returns to the same neighbour, because a divided highway's median slot reverses onto a different node. The graph carries no turn restrictions. |
//! | A lane's turn indication, where `turn:lanes` is tagged | **Data.** `lanes.bin`, keyed by directed edge, ordered left→right. |
//! | A lane's turn indication everywhere else | **Inference**, and this is the overwhelming majority of roads. |
//! | How many lanes an arm has, absent `turn:lanes` | **Neither, and the important row.** One lane in the direction of travel — not a guess but the carriageway renderer's own default, so a connector lands on the painted asphalt. [`effective_lanes`]. |
//! | Which lane of the exit a movement lands in | **Inference.** The convention in [`exit_lane`], stated below. |
//! | Lane width |
//! | Lane width | **Neither, and worth knowing which.** [`LANE_WIDTH_MERCATOR_M`] is not a road measurement — it is tuned to the width the renderer paints a lane at. |
//! | The setback the arc starts at | **Neither.** [`SETBACK_M`] is a nominal guess at how big an intersection is. |
//!
//! Motorway exits are the case this should look good on: they are comparatively well tagged, they
//! are geometrically simple, and a slip road's heading separates cleanly from the mainline's.
//! Ordinary urban intersections are a heuristic and will need tuning against screenshots. Nothing
//! here should be read as knowing where the paint is.
//!
//! # Thinning (size)
//!
//! The junction layer is the archive's largest single line item, so three
//! principled rules thin it — predicates, never count caps, so no junction is
//! thinned arbitrarily:
//!
//! * **Arterial gate.** A junction emits nothing unless at least one arm is
//!   [`ARTERIAL_MAX_CLASS`] or better. Residential-only crossings keep their
//!   carriageways; they lose only the lane markings.
//! * **Slight turns at degree 3.** A 20–45° movement at a three-way node is a
//!   road bend, not a turn, and the carriageway already shows the path. Kept
//!   at degree ≥ 4, where a slight exit is a real choice.
//! * **Halved sampling.** Connectors sample at roughly half the old density;
//!   the curve shape is unchanged to the eye at display scale.
//!
//! # Both ends of a connector are placed by the same rule, and a lane count is never invented
//!
//! A connector runs from a lane to a lane, so both ends need a lane index and a lane count. Those
//! come from [`effective_lanes`] and [`lane_offset_m`], called once per end. Two defects lived
//! here and they had to be fixed together, because fixing either alone makes the other worse.
//!
//! **The ends disagreed.** The exit end kept whatever `lanes.bin` left on its [`Arm`], while the
//! approach end was widened to the junction's exit count, so [`exit_lane`] returned lane 0 for
//! every movement and every connector entering an arm terminated on one identical point. A
//! degree-`d` junction emitted the correct `d * (d - 1)` connectors and only `d` distinct
//! endpoints: a wide fan at one end collapsing to a pencil point at the other, which is the tangle
//! of hairlines that reached a device.
//!
//! **The width was fabricated.** `legal.len()` is a count of *exits*, borrowed as a lane count
//! because it was in scope. It is not a measurement of anything. At a 4-way it claims three lanes
//! per direction where the renderer paints one, so the outermost connector starts 2.5 lane widths
//! off the centreline on a carriageway whose kerb is at 1.0 — off the road entirely.
//!
//! Mirroring the fabricated count to the exit end would have made both ends agree, and put *both*
//! of them off the asphalt instead of one. So the count is not mirrored; it is removed.
//! [`effective_lanes`] takes an [`Arm`] and nothing else, which makes the two ends agree by
//! construction and makes an observer-dependent count unrepresentable rather than merely absent.
//!
//! The honest consequence: at an untagged junction every movement out of an arm shares that arm's
//! one lane, so connectors meet at the arm's mouth instead of fanning, and `d` distinct endpoints
//! at a degree-`d` junction is now the *correct* answer rather than the symptom. Separating them
//! would mean drawing lanes that do not exist.
//!
//! ## Which lane of the exit a movement lands in
//!
//! Only ever more than one lane where `turn:lanes` says so. With `N` approach lanes and `M` exit
//! lanes, for the group of approach lanes making the *same* movement into the *same* exit, ranked
//! `0..r` left→right:
//!
//! - **Through and reverse** hold position: `floor(in_lane * M / N)`, clamped. Identity when
//!   `N == M`, which is what a straight-through wants and is what lets it draw as a straight line
//!   rather than a slight S.
//! - **Left turns** fill the exit from its left: `min(rank, M - 1)`.
//! - **Right turns** fill the exit from its right: `M - 1 - min(r - 1 - rank, M - 1)`.
//!
//! The rank is what makes a tagged dual left turn two ribbons in two lanes instead of two ribbons
//! on one point. A single-lane movement has `r == 1` and reduces to "the leftmost" or "the
//! rightmost".
//!
//! **This is convention, not data, and it is written down so it can be argued with.** Nothing in
//! the graph says which lane feeds which.
//!
//! ## The setback does not scale with the junction's degree
//!
//! Worth recording, because the measurement that suggested it did was reading the fabricated width.
//! [`SETBACK_M`] is constant and additionally clamped to a fraction of each arm's own length. The
//! furthest a connector reached from the node used to grow with degree — about 14.5 m at degree 3
//! against 20.9 m at degree 8 — because the *lateral* fan grew: its outermost lane sat at
//! `(count - 0.5)` widths with `count = degree - 1`. With the fabricated count gone the lateral
//! term is half a lane at every degree and the reach is the setback, flat. No change to
//! [`SETBACK_M`] was needed and none was made.
//!
//! ## Reading a screenshot of this layer
//!
//! Counter-intuitive, and worth knowing before judging a geometry change here by eye. A connector
//! draws as a one-lane carriageway ribbon, so by width it is mostly asphalt — but the
//! `junction-connector` style layer declares the *same* colours as `roads-carriageway`, on purpose,
//! so that a connector reads as the carriageway continued rather than as a stripe laid over it.
//! Across the junction box, which the crossing road ribbons already cover, that makes the ribbon's
//! asphalt an exact identity against what is beneath it: same style entry, same colour, depth off.
//!
//! **So where a connector overlies road surface, its only visible signature is the fraction of its
//! width that is lane marking** — which is why the geometry here says ribbons while the screenshot
//! that prompted this work said thin white strokes. Where a connector leaves the road the asphalt
//! does show, as grey against bare earth; the tangle this layer first shipped with had both, white
//! over the carriageway and grey beyond it. Figures measured on the renderer side.
//!
//! The practical consequence: do not judge a change to the geometry above by how much asphalt
//! appears. Judge it by where the markings fall.
//!
//! **First, though, check that connectors are drawn at all.** `style::LANE_RENDERING` is a release
//! kill switch that defaults to `false`, and it works by dropping every `carriageway: true` layer
//! from `style::layers()` — which is `roads-carriageway` *and* `junction-connector`. It is a
//! *drawing* gate and it is not this module's. The tiler does not link the renderer, so connectors
//! are written into the archive exactly as before and only the painting stops: the screen goes
//! empty rather than going wrong.
//!
//! **So a blank connector screenshot is not evidence about [`MIN_ZOOM`], and must not be read as
//! any.** The two gates are independent and are currently in opposite states — [`MIN_ZOOM`] decides
//! what reaches the archive and is satisfied; the style layer decides what is painted and is
//! switched off. Debugging an empty screen inward lands on the tiler, and "fixing" a tiling gate
//! that is already right is what silently shipped an archive with zero junction features once
//! already. That failure is documented at [`MIN_ZOOM`]; this is the same confusion running the
//! other way. Nothing in this module can cause a blank screen and nothing in this module can fix
//! one.
//!
//! The renderer's own tests read `style::layers_with_lane_rendering()` so they keep asserting
//! against the real style, which is right — but it does mean a green test run says nothing about
//! whether the switch is on.
//!
//! # Lateral offset is baked in projected units, not ground metres
//!
//! Each connector's polyline already carries its lane's offset across the carriageway. That offset
//! is expressed as a fixed number of **Web Mercator** units — spelled here as the ground metres a
//! lane occupies *at the equator*, which is the same thing — and **not** as ground metres at the
//! junction's own latitude.
//!
//! This is structural rather than cosmetic. The carriageway ribbon's lane width is a screen-space
//! quantity: a dp ramp turned into a half-width in pixels and pushed per draw. Web Mercator's scale
//! carries a `1 / cos φ` term, so a fixed ground distance is a *different* number of pixels at
//! every latitude. Measured against the style's painted lane, 3.5 m of ground comes out at 0.97 of
//! a lane at the equator, 0.76 at 38°N and 0.48 at 60°N — so a naively baked ground offset lands
//! 2.3× too far out in Scandinavia, which is more than two lane widths and puts the connector off
//! the road it is meant to join.
//!
//! No zoom gate can rescue that, because `cos φ` is not a function of zoom. Scaling the offset by
//! `cos φ` before it is converted to degrees cancels the projection's stretch exactly, costs one
//! multiply, and needs no wire field and no shader change. The along-road setback is deliberately
//! *not* scaled: [`SETBACK_M`] is a real distance on the ground, because the intersection it spans
//! and the road geometry it must meet are both real distances on the ground.
//!
//! ## The projection correction is exact; the lane width is not
//!
//! "Exactly" above is a claim about the projection and nothing else: `cos φ` cancels Mercator's
//! `1 / cos φ`, which is pure geometry and leaves nothing over at any latitude. It is *not* a
//! claim that a connector lands on the paint. Read as one it is actively dangerous, because a
//! reader who believes the whole thing is exact has no reason to expect a residual, and on meeting
//! one will look for it in the projection — where it is not — and close it by putting a `cos φ`
//! back somewhere. That is the bug this section exists to prevent.
//!
//! A second and smaller discrepancy does survive, and it is not projection error.
//! [`LANE_WIDTH_MERCATOR_M`] is a single number, while the width the renderer actually paints a
//! lane at comes from the style's dp ramp, which is not linear in ground metres — so the two agree
//! at one zoom and drift either side of it. The ramp is `roads-carriageway`'s `width` in
//! `basemap.flat.json` — exponential, base 2.0, over stops
//! `[[16, 3.0], [18, 11.0], [20, 40.0], [22, 160.0]]`, one lane's width in dp. Read at the
//! equator, where the `cos φ` term is already 1 and the ramp is therefore read cleanly on its
//! own, and against a 512 dp tile, a painted lane is worth about 3.40 m of ground at z17 falling
//! to 2.98 m by z20, against a fixed 3.0 m of connector pitch: roughly 12% narrow at z17,
//! crossing over to about 1% wide by z20. Those figures are reproducible from those stops rather
//! than asserted. The change of sign is the part that matters: no single percentage describes the
//! residual, and anyone who reads "the connector is N% wider" will reach for a correction that is
//! wrong at one end of the zoom range. That the fit crosses zero at all is the point — it is
//! centred across the zoom band rather than biased to one end, which is what fitting on absolute
//! dp instead of relative error buys. The residual is also latitude-independent, which together
//! with the drift in zoom is the signature of a ramp mismatch rather than of a projection.
//!
//! **It cannot be closed by a projection term, because it is not a projection error.** The only
//! two levers on it are [`LANE_WIDTH_MERCATOR_M`] and the style's width ramp; the constant is
//! already fitted against that ramp, and where the residual is deliberately put — in percent
//! rather than in pixels — is argued at the constant. A `cos φ` introduced to chase it would be 1
//! at the equator, which is exactly where the residual was measured, and wrong everywhere else.

use std::collections::BTreeSet;
use std::path::Path;

use osm_ingest::proto::{err, Result};
use rayon::prelude::*;
use tile_build::geom::Geometry;
use tile_build::par;
use tile_build::progress::Progress;
use tilecodec::mamaps::body::{
    LANE_LEFT, LANE_NONE, LANE_REVERSE, LANE_RIGHT, LANE_SHARP_LEFT, LANE_SHARP_RIGHT,
    LANE_SLIGHT_LEFT, LANE_SLIGHT_RIGHT, LANE_THROUGH,
};
use tilecodec::mamaps::dict::{self, LAYER_JUNCTION};

use super::boundaries::Conventions;
use super::traffic::{Graph, ROAD_TYPE_MASK};
use super::Class;
use crate::store::Sink;

/// The shallowest zoom lane connectors are **tiled** into.
///
/// This is a tiling gate and nothing else, so it is bounded above by the deepest zoom an archive
/// is built to — z14, [`crate::DEFAULT_MAX_ZOOM`]. Set past that there
/// is no tile deep enough to hold a connector, so every one of them is computed and then dropped,
/// and the layer is missing from the archive entirely while the build still reports success.
///
/// 14 is that bound, which puts connectors in the deepest tiles only and is the least size they
/// can cost. The zoom they are *drawn* from is a separate gate living in the style — the
/// `junction-connector` layer's `minzoom`, which is 16 — and the renderer overzooms the z14 tile
/// above it. That split is how `roads-lanes` already carries lane detail.
pub const MIN_ZOOM: u8 = 14;

/// The largest (least significant) road class that still counts as an arterial.
///
/// Graph edge types share OSM's highway vocabulary as integers
/// (`osm_ingest::tags`: motorway=1 … tertiary=5, unclassified=6,
/// residential=7, …). A junction emits connectors only when at least one arm
/// is this class or better: quiet residential crossings lose lane markings
/// they barely show, while every junction an arterial touches keeps its own.
pub const ARTERIAL_MAX_CLASS: u8 = 5;

/// Width of one lane, in **Web Mercator metres** — ground metres at the equator, which at any
/// other latitude is a smaller ground distance and the same projected one. That is what makes it a
/// fixed number of pixels everywhere; see the module docs, where expressing it as ground metres at
/// the junction's own latitude is what puts a connector two lanes off the road at 60°N.
/// [`lane_width_ground_m`] converts it for a given latitude.
///
/// **3.0, not the 3.5 a real lane is designed to, and that is deliberate — do not "correct" it.**
/// This is not a measurement of a road. It is fitted to the width the renderer actually paints a
/// lane at, which is a dp ramp and is not linear in ground metres. Measured against that ramp, a
/// lane is worth about 3.40 m of ground at z17 falling to 2.98 m by z20, so no single constant is
/// right at every zoom and the only question is where to put the residual.
///
/// It is fitted on **absolute misalignment in dp, not relative error**, and the two disagree: a dp
/// is 1.7 per ground metre at z17 and 13.4 at z20, so the same percentage costs eight times the
/// pixels at the deep end. 3.2 minimises worst-case percentage but leaves ~5 dp of visible gap at
/// z20; 3.0 is a worse percentage fit and keeps the gap near a pixel at every zoom, which is the
/// one that cannot be seen. Percentage error is not what a viewer looks at.
pub const LANE_WIDTH_MERCATOR_M: f64 = 3.0;

/// [`LANE_WIDTH_MERCATOR_M`] as ground metres at `lat_degrees`, which is a lane's width shrinking
/// toward the poles exactly as fast as Mercator stretches it. Exact as a statement about the
/// projection — `cos φ` against `1 / cos φ`, with no approximation in it — and only that: it says
/// nothing about whether the result matches the painted lane, which is the ramp residual the
/// module docs describe and is not fixable here.
fn lane_width_ground_m(lat_degrees: f64) -> f64 {
    LANE_WIDTH_MERCATOR_M * lat_degrees.to_radians().cos().abs().max(1e-6)
}

/// How far back from the junction node a connector starts, and how far past it the connector ends.
///
/// The node is a point; a real intersection is a box some tens of metres across. This is the
/// half-width that box is assumed to have, so a connector spans roughly `2 * SETBACK_M` of ground.
/// Clamped per edge to a fraction of that edge's own length, so a short block does not produce a
/// connector running past the next junction.
const SETBACK_M: f64 = 14.0;

/// The most of an incident edge's length a connector may consume at either end.
const SETBACK_EDGE_FRACTION: f64 = 0.4;

/// Below this the connector is shorter than a lane is wide and draws as a blob.
const MIN_SETBACK_M: f64 = 2.0;

/// Bezier handle length as a fraction of the setback.
///
/// 0.55 is the usual circular-arc approximation constant; it makes the curve leave the approach and
/// meet the exit tangentially without bulging past the intersection box.
const HANDLE: f64 = 0.55;

/// Metres per degree of latitude. Spherical, which is the same approximation
/// [`tile_build::geom::project`] makes and is worth centimetres over a connector's length.
const METRES_PER_DEGREE: f64 = 111_320.0;

/// The class every lane connector carries: layer `junction`, no kind and no detail.
///
/// No `kind`/`kind_detail` for the same reason [`crate::schema::traffic::traffic_class`] has none:
/// the basemap style does not draw this layer, the carriageway renderer does, and it selects the
/// whole layer rather than filtering within it. Keeping the name out of [`dict::KINDS`] avoids
/// disturbing the frozen kind table for a value nothing matches by name.
pub fn junction_class() -> Class {
    Class {
        layer: LAYER_JUNCTION,
        kind: dict::NONE,
        kind_detail: dict::NONE,
        flags: 0,
        area: false,
        min_zoom: MIN_ZOOM,
        min_area_px: 0.0,
    }
}

// --- lanes.bin ------------------------------------------------------------------------------

/// `lanes.bin`: `[u32 n][(u32 edge_idx, u32 blob_off) x (n + 1)][u16 mask blob]`.
///
/// **Sparse**: only edges that carry a `turn:lanes` tag appear, ascending by `edge_idx`, so absence
/// from the index is how "this edge has no lane data" is spelled. Mirrors
/// `maps/src/main/rust/src/graph.rs::edge_lane_masks`; the trailing sentinel entry exists only to
/// give the last real edge a length.
///
/// Mapped, not read: a planet `lanes.bin` is looked up by binary search, so only its index pages
/// stay resident. Matches [`Graph`].
struct LaneTable {
    raw: osm_ingest::mem::Mapped,
    count: u32,
    blob_off: usize,
}

impl LaneTable {
    /// Read `lanes.bin`, or `None` when the graph was built without lane data. A missing file is
    /// not an error — every connector then falls back to the inferred path, which is what happens
    /// for most edges even when the file is present.
    fn load(dir: &Path) -> Result<Option<LaneTable>> {
        let path = dir.join("lanes.bin");
        // Absence is not an error (a graph built without lane data); any other failure to open
        // or map is. Checked by existence first so a mapping failure on a present file is not
        // misread as "no lane data".
        if std::fs::metadata(&path).is_err() {
            return Ok(None);
        }
        let raw = osm_ingest::mem::Mapped::open(&path).map_err(osm_ingest::proto::Error)?;
        if raw.len() < 4 {
            return err("lanes.bin is too short to hold its entry count".to_string());
        }
        let count = u32::from_le_bytes([raw[0], raw[1], raw[2], raw[3]]);
        // n + 1 entries: the trailing sentinel is what gives the last edge an end offset.
        let index_bytes = 4usize
            .checked_add((count as usize + 1).saturating_mul(8))
            .ok_or_else(|| osm_ingest::proto::Error("lanes.bin index size overflows".to_string()))?;
        if raw.len() < index_bytes {
            return err(format!(
                "lanes.bin is {} bytes, too short for its {count}-entry index",
                raw.len(),
            ));
        }
        Ok(Some(LaneTable { raw, count, blob_off: index_bytes }))
    }

    fn entry(&self, i: u32) -> (u32, u32) {
        let at = 4 + (i as usize) * 8;
        let edge = u32::from_le_bytes([self.raw[at], self.raw[at + 1], self.raw[at + 2], self.raw[at + 3]]);
        let off = u32::from_le_bytes([
            self.raw[at + 4],
            self.raw[at + 5],
            self.raw[at + 6],
            self.raw[at + 7],
        ]);
        (edge, off)
    }

    /// The per-lane `LANE_*` masks for directed edge `idx`, ordered left→right, or `None` when it
    /// carries none. Binary search, because the index is sparse.
    fn masks(&self, idx: u32) -> Option<Vec<u16>> {
        let (mut lo, mut hi) = (0u32, self.count);
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            if self.entry(mid).0 < idx {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if lo >= self.count || self.entry(lo).0 != idx {
            return None;
        }
        let start = self.blob_off + self.entry(lo).1 as usize;
        let end = self.blob_off + self.entry(lo + 1).1 as usize;
        if end <= start || end > self.raw.len() {
            return None;
        }
        Some(
            self.raw[start..end]
                .chunks_exact(2)
                .map(|c| u16::from_le_bytes([c[0], c[1]]))
                .collect(),
        )
    }
}

// --- turns ----------------------------------------------------------------------------------

/// A movement's turn class, the same vocabulary `turn:lanes` uses.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Turn {
    SharpLeft,
    Left,
    SlightLeft,
    Through,
    SlightRight,
    Right,
    SharpRight,
    /// A reversal back the way the driver came — the `LANE_REVERSE` indication.
    Reverse,
}

impl Turn {
    /// The `LANE_*` bit a lane tagged for this movement sets.
    fn bit(self) -> u16 {
        match self {
            Turn::SharpLeft => LANE_SHARP_LEFT,
            Turn::Left => LANE_LEFT,
            Turn::SlightLeft => LANE_SLIGHT_LEFT,
            Turn::Through => LANE_THROUGH,
            Turn::SlightRight => LANE_SLIGHT_RIGHT,
            Turn::Right => LANE_RIGHT,
            Turn::SharpRight => LANE_SHARP_RIGHT,
            Turn::Reverse => LANE_REVERSE,
        }
    }

    /// The angle, in degrees clockwise, this movement nominally turns through. Used to pick the
    /// closest real exit for a tagged indication that no exit's own bucket matches — a
    /// `turn:lanes=right` onto a road the graph classes as a sharp right, say.
    fn nominal_degrees(self) -> f64 {
        match self {
            Turn::SharpLeft => -155.0,
            Turn::Left => -90.0,
            Turn::SlightLeft => -32.0,
            Turn::Through => 0.0,
            Turn::SlightRight => 32.0,
            Turn::Right => 90.0,
            Turn::SharpRight => 155.0,
            Turn::Reverse => 180.0,
        }
    }

    /// Does this movement go left? Decides which lane of the exit a connector lands in.
    fn is_left(self) -> bool {
        matches!(self, Turn::SharpLeft | Turn::Left | Turn::SlightLeft)
    }

    fn is_right(self) -> bool {
        matches!(self, Turn::SharpRight | Turn::Right | Turn::SlightRight)
    }

    /// Every turn a `LANE_*` mask can name, so a lane's bits can be walked.
    const ALL: [Turn; 8] = [
        Turn::SharpLeft,
        Turn::Left,
        Turn::SlightLeft,
        Turn::Through,
        Turn::SlightRight,
        Turn::Right,
        Turn::SharpRight,
        Turn::Reverse,
    ];
}

include!("junction_part1.rs");
include!("junction_part2.rs");
include!("junction_part3.rs");
include!("junction_part4.rs");
include!("junction_part5.rs");