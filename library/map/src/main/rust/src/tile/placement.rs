//! Screen-space label placement: greedy, rank-ordered, per-frame.
//!
//! M1 places _point_ labels only (country/region/locality/subplace). Each tile
//! shapes its candidates once ([`crate::tile::symbol`]); this module decides, per
//! frame, which candidates draw: sort by rank (country first), greedily accept
//! while the screen box collides with nothing accepted yet.
//!
//! # Coordinates
//!
//! Collision runs in _screen px_: the caller passes each candidate's screen box
//! (computed from the anchor's clip position + the frame's text size). This keeps
//! the module free of camera math and testable with literal boxes.
//!
//! Projection is pitch-aware ([`project_to_screen`]): at pitch 0 it is the linear
//! map, and under tilt it carries the perspective divide the billboard shader
//! applies — so a box sits where its label draws, and distant POIs compress onto
//! overlapping boxes the rank-ordered placer thins by importance.
//!
//! # Rank
//!
//! Lower is more important: country (0) > region (1) > locality (2) > subplace
//! (3) > POI (4), 255 for an id the renderer did not recognise (sinks last). Ties break
//! by population weight (higher first — a big city beats a town), then by
//! larger box (more informative), then by id order — deterministic, so frames
//! don't shimmer.
//!
//! # Variable anchors
//!
//! A POI label may be drawn to the left or the right of its icon
//! (`text-variable-anchor: ["left", "right"]`), so a candidate carries two boxes and
//! [`place`] reports which one it accepted. The renderer then emits at that anchor, which
//! is what makes a label near the edge of a crowd flip to its other side instead of
//! disappearing.

use crate::style::Anchor;

/// A label candidate's screen box plus its rank.
pub struct Candidate {
    /// Stable id for the accept-set.
    pub id: u64,
    /// Rank: 0 country … 4 POI, 255 unknown (sorts last).
    pub rank: u8,
    /// Population weight within the rank, higher first.
    pub pop: u16,
    /// Screen box in device px, with collision padding baked in.
    pub rect: (f32, f32, f32, f32),
    /// The box to try when [`rect`](Self::rect) collides — the label drawn at its second
    /// variable anchor. `None` for a label that cannot move, which is every place label.
    pub alternate: Option<(f32, f32, f32, f32)>,
}

/// An accepted candidate: its id, and whether it took its
/// [`alternate`](Candidate::alternate) box.
pub type Placed = (u64, bool);

/// Minimum population rank for a locality to draw at a camera zoom.
///
/// Country/region always draw (few, important); subplace is layer-gated. Rank gating
/// happens before collision because collision alone cannot thin hundreds of towns down
/// to the major-city set - the small ones simply arrive first in some tiles.
///
/// The ranks are the reference basemap's `population_rank` (see the tiler's
/// `schema::places::rank_of`): 12 is 500k, 1 is any counted population at all. So z6 and
/// below hold the half-million-plus cities, z7..z9 add anywhere with a population, and
/// z10 draws everything the tile shaped.
pub fn locality_min_pop(zoom: f64) -> u16 {
    if zoom < 7.0 {
        12
    } else if zoom < 10.0 {
        1
    } else {
        0
    }
}

/// Greedily place candidates: rank order, first-come keeps its box.
/// Rank 0 (country) always draws: it never collides, so capitals and country
/// names survive any crowd. All other ranks collide normally.
///
/// A candidate whose primary box collides gets one more try at its
/// [`alternate`](Candidate::alternate), and is reported as having taken it. That is
/// MapLibre's variable-anchor behaviour reduced to the two anchors the reference
/// declares.
///
/// Returns the accepted candidates in acceptance order.
pub fn place(candidates: &[Candidate]) -> Vec<Placed> {
    let mut ordered: Vec<&Candidate> = candidates.iter().collect();
    ordered.sort_by(|a, b| {
        a.rank
            .cmp(&b.rank)
            .then_with(|| b.pop.cmp(&a.pop))
            .then_with(|| {
                let (aw, ah) = (a.rect.2 - a.rect.0, a.rect.3 - a.rect.1);
                let (bw, bh) = (b.rect.2 - b.rect.0, b.rect.3 - b.rect.1);
                (bw * bh)
                    .partial_cmp(&(aw * ah))
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
            .then_with(|| a.id.cmp(&b.id))
    });
    let mut accepted: Vec<(f32, f32, f32, f32)> = Vec::new();
    let mut out = Vec::new();
    for c in ordered {
        // Rank 0 never collides: country labels are few and must always draw.
        let free = |rect: (f32, f32, f32, f32), accepted: &[(f32, f32, f32, f32)]| {
            c.rank == 0 || !accepted.iter().any(|a| overlaps(*a, rect))
        };
        let taken = if free(c.rect, &accepted) {
            (c.rect, false)
        } else if let Some(alternate) = c.alternate.filter(|a| free(*a, &accepted)) {
            (alternate, true)
        } else {
            continue;
        };
        accepted.push(taken.0);
        out.push((c.id, taken.1));
    }
    out
}

fn overlaps(a: (f32, f32, f32, f32), b: (f32, f32, f32, f32)) -> bool {
    a.0 < b.2 && b.0 < a.2 && a.1 < b.3 && b.1 < a.3
}

/// Hasher for maps keyed by [`candidate_id`]: the id passes straight through.
///
/// A candidate id is already a splitmix64-avalanched hash of its tile, layer and label, so
/// running it through std's SipHash again buys nothing and costs a SipHash per lookup — and the
/// frame path looks up every symbol-layer label of every resident tile in the accept-set on
/// every frame (thousands of lookups a frame at city zooms).
#[derive(Default, Clone, Copy)]
pub struct CandidateIdHasher(u64);

impl std::hash::Hasher for CandidateIdHasher {
    fn finish(&self) -> u64 {
        self.0
    }

    fn write_u64(&mut self, id: u64) {
        self.0 = id;
    }

    /// Only `u64` keys are expected; anything else still hashes (FNV-1a), just not for free.
    fn write(&mut self, bytes: &[u8]) {
        for &b in bytes {
            self.0 = (self.0 ^ u64::from(b)).wrapping_mul(0x0100_0000_01b3);
        }
    }
}

/// A map keyed by [`candidate_id`], hashed with [`CandidateIdHasher`].
pub type CandidateMap<V> =
    std::collections::HashMap<u64, V, std::hash::BuildHasherDefault<CandidateIdHasher>>;

/// Stable id for one label of one tile in one frame, so the accept-set the
/// renderer computes in its pre-pass names the same labels `record_symbol`
/// later filters by.
///
/// A cheap deterministic integer mix over (tile z/x/y, layer index, position in
/// the tile's shaped label list) — the label list is shaped once in feature
/// order, so the inputs are frame-stable and collisions across tiles are
/// impossible in practice. Pure integer arithmetic, so ids are stable across
/// frames and runs (no hasher keys, no per-process randomness).
pub fn candidate_id(z: u8, x: u32, y: u32, layer_index: usize, label_idx: usize) -> u64 {
    // FxHash-style multiply mix + splitmix64 final avalanche.
    const K: u64 = 0xbf58476d1ce4e5b9;
    let mut h: u64 = 0x9e3779b97f4a7c15;
    h ^= z as u64;
    h = h.wrapping_mul(K);
    h ^= x as u64;
    h = h.wrapping_mul(K);
    h ^= y as u64;
    h = h.wrapping_mul(K);
    h ^= layer_index as u64;
    h = h.wrapping_mul(K);
    h ^= label_idx as u64;
    h = h.wrapping_mul(K);
    // Final avalanche so neighbouring inputs land far apart.
    h ^= h >> 30;
    h = h.wrapping_mul(K);
    h ^= h >> 27;
    h = h.wrapping_mul(0x94d049bb133111eb);
    h ^= h >> 31;
    h
}

/// One label's screen collision box in device px, from the same inputs the
/// tessellator uses — so the box the placer sees is the box the GPU draws,
/// plus MapLibre-style padding.
///
/// `anchor` is tile-local 0..1, `tile_clip` the camera's column-major matrix
/// for the tile, `extent_wh` the viewport in device px. Width is the label's
/// advance at the frame's `text_px` (`total_advance` is in font units over
/// [`UP_EM`](crate::tile::glyph::UP_EM)); height is one `text_px`, centred on
/// the anchor like the emitted em box. `pad_px` inflates the box on every
/// side, standing in for the icon + text padding MapLibre applies around
/// every label: without it tight advance boxes let hundreds of villages
/// survive at z6 where MapLibre shows ~10 cities.
pub fn screen_rect(
    anchor: (f32, f32),
    tile_clip: [f32; 16],
    extent_wh: (u32, u32),
    text_px: f32,
    total_advance: f32,
    pad_px: f32,
) -> Option<(f32, f32, f32, f32)> {
    anchored_rect(
        anchor,
        tile_clip,
        extent_wh,
        &BoxInputs {
            text_px,
            advance: total_advance,
            line_count: 1,
            offset_em: (0.0, 0.0),
            icon_px: None,
            pad_px,
        },
        Anchor::Center,
    )
}

/// Everything a label's collision box depends on besides where it is anchored.
///
/// A struct rather than eight parameters because the renderer builds one of these per
/// label and then asks for a box at each candidate anchor — the inputs are shared and only
/// the anchor varies.
pub struct BoxInputs {
    /// The frame's text size in device px.
    pub text_px: f32,
    /// The widest line's advance, in font units.
    pub advance: f32,
    /// How many lines the label wrapped to, at least 1.
    pub line_count: usize,
    /// `text-offset` in ems.
    pub offset_em: (f32, f32),
    /// The icon's drawn size in device px, when the label has one.
    pub icon_px: Option<(f32, f32)>,
    /// Collision padding added on every side.
    pub pad_px: f32,
}

/// Project a tile-local 0..1 point to screen px through the tile's clip matrix, **with**
/// the perspective divide — the one derivation collision shares with the GPU billboard path.
///
/// [`anchored_rect`] and [`curved_boxes`] used to apply only the matrix's linear part, which
/// is the whole transform at pitch 0 (`w == 1`) but drops the foreshortening under tilt: boxes
/// were built around a pre-divide position while `symbol_billboard.vert` draws each anchor
/// post-divide, so collision tested boxes that sat nowhere near the drawn labels. A tilted view
/// additionally compresses distant ground toward the horizon, which is exactly the declutter
/// case — with the divide, far POIs land close together on screen, their screen-constant boxes
/// overlap, and the rank-ordered placer culls them, so declutter follows the projected density
/// rather than the zoom alone.
///
/// Returns `None` when the point is on or behind the eye (`w <= 0`): there is no screen position
/// to collide at, so the label cannot be a candidate. Callers skip the label (point path) or the
/// glyph (curved path) — a box for an unprojectable label is unrepresentable rather than garbage.
/// The `!(w > 0.0)` form also catches NaN, which would otherwise build a box that collides with
/// nothing and always draws.
pub fn project_to_screen(
    tile_clip: [f32; 16],
    point: (f32, f32),
    extent_wh: (u32, u32),
) -> Option<(f32, f32)> {
    let cx = tile_clip[0] * point.0 + tile_clip[4] * point.1 + tile_clip[12];
    let cy = tile_clip[1] * point.0 + tile_clip[5] * point.1 + tile_clip[13];
    let w = tile_clip[3] * point.0 + tile_clip[7] * point.1 + tile_clip[15];
    if !(w > 0.0) {
        return None;
    }
    Some((
        (cx / w * 0.5 + 0.5) * extent_wh.0 as f32,
        (cy / w * 0.5 + 0.5) * extent_wh.1 as f32,
    ))
}

/// The screen box a label would occupy if drawn at `anchor`.
///
/// The union of the text block and the icon, which is where this departs from MapLibre:
/// it keeps two linked boxes and tests both. The two are equivalent while `icon-optional`
/// and `text-optional` are both false — as they are here, since neither is set — because
/// then a collision on either box rejects the pair anyway. A union is looser only in the
/// gap the `text-offset` opens between icon and text, which is 1.1 em of empty space that
/// nothing would have been placed in.
///
/// The box is centred on [`project_to_screen`]'s pitch-aware projection of `point`, so under
/// tilt it sits where the billboarded label draws; its width and height are screen-constant
/// (text advance at `text_px`, icon size in device px), matching the screen-constant size the
/// billboard path draws at. Returns `None` when the anchor is on or behind the eye — such a
/// label cannot be a candidate, so no box exists for it.
pub fn anchored_rect(
    point: (f32, f32),
    tile_clip: [f32; 16],
    extent_wh: (u32, u32),
    inputs: &BoxInputs,
    anchor: Anchor,
) -> Option<(f32, f32, f32, f32)> {
    let (sx, sy) = project_to_screen(tile_clip, point, extent_wh)?;

    let w = inputs.text_px * inputs.advance / crate::tile::glyph::UP_EM as f32;
    // Lines stack at 1.2 em, so a two-line block is 2.2 em tall, not 2.4: the first line
    // contributes its own height and each further one a line's worth of leading.
    let lines = inputs.line_count.max(1) as f32;
    let h = inputs.text_px * (1.0 + (lines - 1.0) * crate::tess::text::LINE_HEIGHT_EM);
    // The same placement `tess::text::emit` uses: the offset's magnitude, its direction
    // taken from the anchor, and no vertical component on a horizontal anchor.
    let offset = inputs.offset_em.0.abs() * inputs.text_px;
    let (mut x0, mut x1) = match anchor {
        Anchor::Center => (sx - w * 0.5, sx + w * 0.5),
        Anchor::Left => (sx + offset, sx + offset + w),
        Anchor::Right => (sx - offset - w, sx - offset),
    };
    let (mut y0, mut y1) = (sy - h * 0.5, sy + h * 0.5);
    if let Some((icon_w, icon_h)) = inputs.icon_px {
        x0 = x0.min(sx - icon_w * 0.5);
        x1 = x1.max(sx + icon_w * 0.5);
        y0 = y0.min(sy - icon_h * 0.5);
        y1 = y1.max(sy + icon_h * 0.5);
    }
    Some((
        x0 - inputs.pad_px,
        y0 - inputs.pad_px,
        x1 + inputs.pad_px,
        y1 + inputs.pad_px,
    ))
}

// --- oriented / segmented collision (curved labels) -------------------------

/// An oriented bounding box in screen px: a centre, half-extents along its own axes, and the
/// unit orientation `(cos, sin)` of its local x-axis.
///
/// A point label's box is one of these with `sin == 0` (axis-aligned), so a single collision
/// primitive serves both the straight point labels and the per-glyph boxes of a curved one — a
/// curved street name is a *row* of these, each rotated to its glyph's tangent, which is what
/// lets it collide tightly instead of as one loose AABB across the whole bend.
#[derive(Clone, Copy, Debug)]
pub struct Obb {
    pub cx: f32,
    pub cy: f32,
    pub hx: f32,
    pub hy: f32,
    pub cos: f32,
    pub sin: f32,
}

impl Obb {
    /// An axis-aligned box from a screen rect — how a point label enters the segmented placer.
    pub fn from_rect(rect: (f32, f32, f32, f32)) -> Obb {
        Obb {
            cx: (rect.0 + rect.2) * 0.5,
            cy: (rect.1 + rect.3) * 0.5,
            hx: (rect.2 - rect.0).abs() * 0.5,
            hy: (rect.3 - rect.1).abs() * 0.5,
            cos: 1.0,
            sin: 0.0,
        }
    }

    /// The box's two unit axes.
    fn axes(&self) -> [(f32, f32); 2] {
        [(self.cos, self.sin), (-self.sin, self.cos)]
    }
}

/// Whether two oriented boxes overlap, by the separating-axis theorem.
///
/// Four candidate axes (each box's two), which is all a 2D OBB pair needs: if the boxes' shadows
/// are disjoint on any one axis they cannot intersect. Touching exactly (a gap of zero) counts as
/// separated, matching the half-open [`overlaps`] the axis-aligned path uses so a label may sit
/// flush against its neighbour.
///
/// Fast path: when both boxes are axis-aligned (`sin == 0`, which is how every point label enters
/// via [`Obb::from_rect`]), this is exactly the 4-float AABB test — same half-open rule, no SAT.
pub fn obb_overlap(a: &Obb, b: &Obb) -> bool {
    if a.sin == 0.0 && b.sin == 0.0 {
        // |dx| < hx sum AND |dy| < hy sum; touching exactly (>=) is separated.
        return (a.cx - b.cx).abs() < a.hx + b.hx && (a.cy - b.cy).abs() < a.hy + b.hy;
    }
    let dx = b.cx - a.cx;
    let dy = b.cy - a.cy;
    for axis in a.axes().iter().chain(b.axes().iter()) {
        let (ax, ay) = *axis;
        let centre_gap = (dx * ax + dy * ay).abs();
        let ra = project_radius(a, ax, ay);
        let rb = project_radius(b, ax, ay);
        if centre_gap >= ra + rb {
            return false;
        }
    }
    true
}

/// The half-width of `box`'s shadow on unit axis `(ax, ay)`.
fn project_radius(b: &Obb, ax: f32, ay: f32) -> f32 {
    let [u, v] = b.axes();
    b.hx * (u.0 * ax + u.1 * ay).abs() + b.hy * (v.0 * ax + v.1 * ay).abs()
}

/// A label candidate whose collision footprint is one or more oriented boxes.
///
/// The superset of [`Candidate`]: a point label is a single [`Obb`], a curved label is the row of
/// per-glyph boxes [`curved_boxes`] builds. [`alternate`](Self::alternate) is the second
/// variable-anchor footprint (POI only), tried when the primary collides — the same fallback
/// [`Candidate`] carries, generalised to a set of boxes.
pub struct SegmentedCandidate {
    pub id: u64,
    pub rank: u8,
    pub pop: u16,
    pub boxes: Vec<Obb>,
    pub alternate: Option<Vec<Obb>>,
    /// Stable feature identity for cross-tile dedup (`ID_NONE` when the label
    /// carries no identity — such candidates are never deduped).
    pub feature_id: u64,
    /// Style layer index, the second half of the dedup identity.
    pub layer_index: usize,
}

impl SegmentedCandidate {
    /// The bounding half-area of the primary footprint, for the size tie-break — a curved label's
    /// summed glyph areas, a point label's box area.
    fn area(&self) -> f32 {
        self.boxes.iter().map(|b| b.hx * b.hy).sum::<f32>() * 4.0
    }
}

include!("placement_grid.rs");
include!("placement_part1.rs");
include!("placement_part2.rs");
include!("placement_part3.rs");
include!("placement_globe.rs");

#[cfg(test)]
mod candidate_map_tests {
    use super::{candidate_id, CandidateMap};

    #[test]
    fn an_id_keyed_map_finds_exactly_the_ids_put_in() {
        let mut map: CandidateMap<u32> = CandidateMap::default();
        for label in 0..5_000usize {
            map.insert(candidate_id(14, 2620, 6332, label % 14, label), label as u32);
        }
        assert_eq!(map.len(), 5_000, "candidate ids must stay distinct under the identity hash");
        for label in 0..5_000usize {
            assert_eq!(map.get(&candidate_id(14, 2620, 6332, label % 14, label)), Some(&(label as u32)));
        }
        assert!(!map.contains_key(&candidate_id(14, 2620, 6333, 0, 0)));
    }
}
