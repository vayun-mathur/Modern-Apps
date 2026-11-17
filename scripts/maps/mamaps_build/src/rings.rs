//! Stage C: normalise ring winding and hole containment, once, at build time.
//!
//! # Why this is worth a module
//!
//! The published z0 ocean tile is one polygon with **105 holes**, of which 40 overlap each other, 6
//! straddle the exterior, 4 are entirely outside it and 13 are lakes nested inside other holes.
//! `tess::fill` on device repairs all of that every time the tile is tessellated, under a frame
//! budget, in `i32`. Doing it here instead costs nothing anyone waits for, runs in `f64`, and turns
//! a per-frame repair into a one-time fact — which is what
//! [`FLAG_RINGS_VALIDATED`](tilecodec::mamaps::header::FLAG_RINGS_VALIDATED) tells the renderer.
//!
//! # What "valid" means here
//!
//! Per part group, after this runs:
//!
//! 1. exactly one exterior, wound counter-clockwise;
//! 2. every hole wound clockwise;
//! 3. every hole strictly inside its exterior;
//! 4. no two holes overlapping;
//! 5. no zero-area ring.
//!
//! Those five are what `tess::fill` assumes and what the batch check in this module's tests asserts
//! over every tile of a real build.
//!
//! # What it does not do
//!
//! It does not clip a straddling hole to its exterior or subtract one overlapping hole from another.
//! Both need a real boolean operation, and getting one subtly wrong is worse than dropping the hole:
//! a dropped hole paints a lake as land, which is visible and wrong; a botched intersection paints a
//! wedge across a continent, which is visible and inexplicable. So a hole that cannot be placed
//! cleanly is **dropped and counted**, and the count is in the build report.

use tilecodec::mamaps::body::{BuildingAttrs, LaneTurns, Layer, Part, WINDING_HOLE, WINDING_OUTER};

/// A kept ring with its bounding box `(min_x, min_y, max_x, max_y)`.
type RingWithBox = (Vec<(i16, i16)>, (i16, i16, i16, i16));

/// What normalising a build cost, for the report.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Stats {
    /// Rings whose winding disagreed with their stated role and was corrected.
    pub rewound: u64,
    /// Rings that enclosed no area at all.
    pub zero_area: u64,
    /// Holes dropped for being outside their exterior, straddling it, or overlapping another hole.
    pub holes_dropped: u64,
    pub groups: u64,
}

impl Stats {
    pub fn add(&mut self, other: Stats) {
        self.rewound += other.rewound;
        self.zero_area += other.zero_area;
        self.holes_dropped += other.holes_dropped;
        self.groups += other.groups;
    }

    /// Did anything have to be corrected?
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn clean(&self) -> bool {
        self.rewound == 0 && self.zero_area == 0 && self.holes_dropped == 0
    }
}

/// Normalise every polygon feature of a layer in place.
///
/// Rebuilds the parts table and the coordinate arena, because dropping a ring changes both and the
/// encoder requires the parts to tile the arena exactly.
#[cfg(test)]
pub fn normalise(layer: &mut Layer) -> Stats {
    normalise_with_ids(layer, None, None, None)
}

/// As [`normalise`], but also drops the id and turn-lane records of any feature it drops.
///
/// The side tables are indexed by feature position, so the retain at the end has to happen to all
/// of them or every entry after the first casualty describes the wrong feature. This only started
/// mattering when `boundaries` gained a table: `places` and `poi` are points, and a point never
/// loses its single part, so the retain was a no-op for every layer that carried ids. A road's
/// turn table rides along for the same discipline, though a road is a line and a line is never
/// dropped here.
pub fn normalise_with_ids(
    layer: &mut Layer,
    ids: Option<&mut Vec<u64>>,
    turns: Option<&mut Vec<LaneTurns>>,
    buildings: Option<&mut Vec<BuildingAttrs>>,
) -> Stats {
    let mut stats = Stats::default();
    let mut parts: Vec<Part> = Vec::with_capacity(layer.parts.len());
    let mut coords: Vec<(i16, i16)> = Vec::with_capacity(layer.coords.len());

    for feature in &mut layer.features {
        if feature.geom_type != tilecodec::mamaps::body::GEOM_POLYGON {
            // A line's parts pass through untouched: winding means nothing on an open path.
            let start = parts.len() as u32;
            for part in layer.parts[feature.parts_offset as usize..]
                .iter()
                .take(feature.part_count as usize)
            {
                let points = ring_points(&layer.coords, part);
                parts.push(Part {
                    coord_start: coords.len() as u32,
                    point_count: points.len() as u32,
                    winding: part.winding,
                });
                coords.extend_from_slice(points);
            }
            feature.parts_offset = start;
            continue;
        }
        stats.groups += 1;
        let group: Vec<(&Part, &[(i16, i16)])> = layer.parts
            [feature.parts_offset as usize..]
            .iter()
            .take(feature.part_count as usize)
            .map(|part| (part, ring_points(&layer.coords, part)))
            .collect();

        // The exterior is the first part, by the format's own convention.
        let Some((_, exterior)) = group.first().copied() else {
            feature.part_count = 0;
            continue;
        };
        let exterior_area = signed_area(exterior);
        if exterior_area == 0.0 {
            // A zero-area exterior takes its holes with it: there is nothing for them to be in.
            stats.zero_area += 1;
            feature.part_count = 0;
            continue;
        }

        let start = parts.len() as u32;
        let mut count = 0u32;
        // An exterior is counter-clockwise. Reversed rather than re-labelled, because the winding
        // field states a fact about the coordinates and the two must agree.
        // `(ring, box)`, because every containment test below needs the box and computing it per
        // comparison would put back the quadratic cost the boxes exist to remove.
        let mut kept: Vec<RingWithBox> =
            Vec::with_capacity(group.len());
        let mut exterior_ring = exterior.to_vec();
        if exterior_area < 0.0 {
            exterior_ring.reverse();
            stats.rewound += 1;
        }
        push_ring(&mut parts, &mut coords, &exterior_ring, WINDING_OUTER);
        count += 1;
        let exterior_box = bounds(&exterior_ring);
        kept.push((exterior_ring, exterior_box));

        for (_, hole) in group.iter().skip(1) {
            let area = signed_area(hole);
            if area == 0.0 {
                stats.zero_area += 1;
                continue;
            }
            let hole_box = bounds(hole);
            // Strictly inside its exterior. A straddling or outside hole is dropped rather than
            // clipped: a dropped hole paints a lake as land, which is visible and explicable, and a
            // botched clip paints a wedge across a continent, which is neither.
            if !strictly_inside(hole, hole_box, &kept[0].0, exterior_box) {
                stats.holes_dropped += 1;
                continue;
            }
            // And not overlapping a hole already kept. Same reasoning: no boolean operations.
            if kept
                .iter()
                .skip(1)
                .any(|(other, other_box)| rings_overlap(hole, hole_box, other, *other_box))
            {
                stats.holes_dropped += 1;
                continue;
            }
            let mut ring = hole.to_vec();
            // A hole is clockwise, which is the opposite sign to its exterior.
            if area > 0.0 {
                ring.reverse();
                stats.rewound += 1;
            }
            push_ring(&mut parts, &mut coords, &ring, WINDING_HOLE);
            count += 1;
            // Reversing a ring does not move it, so the box is still the one just computed.
            kept.push((ring, hole_box));
        }
        feature.parts_offset = start;
        feature.part_count = count;
    }

    // A feature whose exterior went takes no parts, so it draws nothing. Removed outright rather
    // than left as an empty group, which the encoder refuses. The id table is indexed by feature
    // position, so it is filtered by the same predicate, in the same order, first.
    if let Some(ids) = ids {
        if ids.len() == layer.features.len() {
            let mut at = 0;
            ids.retain(|_| {
                let keep = layer.features[at].part_count > 0;
                at += 1;
                keep
            });
        }
    }
    if let Some(turns) = turns {
        if turns.len() == layer.features.len() {
            let mut at = 0;
            turns.retain(|_| {
                let keep = layer.features[at].part_count > 0;
                at += 1;
                keep
            });
        }
    }
    // The building side table is indexed by feature position too, so a dropped degenerate building
    // must drop its attrs with it, in the same order and by the same predicate.
    if let Some(buildings) = buildings {
        if buildings.len() == layer.features.len() {
            let mut at = 0;
            buildings.retain(|_| {
                let keep = layer.features[at].part_count > 0;
                at += 1;
                keep
            });
        }
    }
    layer.features.retain(|feature| feature.part_count > 0);
    layer.parts = parts;
    layer.coords = coords;
    stats
}

fn ring_points<'a>(coords: &'a [(i16, i16)], part: &Part) -> &'a [(i16, i16)] {
    let start = part.coord_start as usize;
    &coords[start..start + part.point_count as usize]
}

fn push_ring(
    parts: &mut Vec<Part>,
    coords: &mut Vec<(i16, i16)>,
    ring: &[(i16, i16)],
    winding: u16,
) {
    parts.push(Part {
        coord_start: coords.len() as u32,
        point_count: ring.len() as u32,
        winding,
    });
    coords.extend_from_slice(ring);
}

/// A ring's signed area by the shoelace formula: positive counter-clockwise.
///
/// `f64` from `i64` terms. This is the whole reason the work belongs at build time: on device the
/// same computation is done in `i32` under a frame budget, where a long ring's cross products
/// overflow and a near-degenerate one rounds to the wrong sign.
pub fn signed_area(ring: &[(i16, i16)]) -> f64 {
    let mut twice = 0i64;
    for pair in ring.windows(2) {
        let ((x0, y0), (x1, y1)) = (pair[0], pair[1]);
        twice += x0 as i64 * y1 as i64 - x1 as i64 * y0 as i64;
    }
    // An unclosed ring: close it implicitly rather than reporting a wrong area.
    if let (Some(first), Some(last)) = (ring.first(), ring.last()) {
        if first != last {
            twice += last.0 as i64 * first.1 as i64 - first.0 as i64 * last.1 as i64;
        }
    }
    twice as f64 / 2.0
}

/// Is every vertex of `inner` inside `outer`?
///
/// Vertex containment rather than a full geometric test. It is what distinguishes the three cases
/// that matter — wholly inside, wholly outside, straddling — and it cannot be fooled by anything a
/// clipped tile actually contains, because a hole that crosses its exterior has vertices on both
/// sides by construction.
/// A ring's bounding box as `(min_x, min_y, max_x, max_y)`.
///
/// Computed once per ring and threaded into the containment tests, because those are the whole cost
/// of stage C. Measured on a California build with a coastline, `encode` — which is stage C plus
/// serialise plus DEFLATE — was 432.7 s against 94.7 s without one. `earth` lands in ~529 k
/// tile-layers and its polygons carry many holes, and both tests below are quadratic without a cheap
/// way to say "these two are nowhere near each other".
///
/// Every use is **conservative**: a box test only ever rejects a case the exact test would also have
/// rejected, so the answers are unchanged and the archive stays byte-identical.
fn bounds(ring: &[(i16, i16)]) -> (i16, i16, i16, i16) {
    let mut b = (i16::MAX, i16::MAX, i16::MIN, i16::MIN);
    for &(x, y) in ring {
        b.0 = b.0.min(x);
        b.1 = b.1.min(y);
        b.2 = b.2.max(x);
        b.3 = b.3.max(y);
    }
    b
}

/// Is `inner`'s box entirely within `outer`'s?
fn box_within(inner: (i16, i16, i16, i16), outer: (i16, i16, i16, i16)) -> bool {
    inner.0 >= outer.0 && inner.1 >= outer.1 && inner.2 <= outer.2 && inner.3 <= outer.3
}

/// Do two boxes share any area?
fn boxes_meet(a: (i16, i16, i16, i16), b: (i16, i16, i16, i16)) -> bool {
    !(a.2 < b.0 || a.0 > b.2 || a.3 < b.1 || a.1 > b.3)
}

/// Is every vertex of `inner` inside `outer`?
///
/// Vertex containment rather than a full geometric test. It is what distinguishes the three cases
/// that matter — wholly inside, wholly outside, straddling — and it cannot be fooled by anything a
/// clipped tile actually contains, because a hole that crosses its exterior has vertices on both
/// sides by construction.
fn strictly_inside(
    inner: &[(i16, i16)],
    inner_box: (i16, i16, i16, i16),
    outer: &[(i16, i16)],
    outer_box: (i16, i16, i16, i16),
) -> bool {
    // If any of `inner` lies outside `outer`'s box then that vertex is outside `outer` itself, so the
    // exact test would say no too. This is what makes a hole belonging to a different part of the
    // world cost four comparisons instead of a scan of the exterior.
    if !box_within(inner_box, outer_box) {
        return false;
    }
    inner.iter().all(|&point| point_in_ring(point, outer, outer_box))
}

/// Do two rings overlap? Approximated by mutual vertex containment.
///
/// One ring having a vertex inside the other is the signature of every overlap a clipped tile
/// produces. Two rings that merely touch at a vertex are not overlapping, and are left alone —
/// `tess::fill` already handles a hole that touches its exterior.
fn rings_overlap(
    a: &[(i16, i16)],
    a_box: (i16, i16, i16, i16),
    b: &[(i16, i16)],
    b_box: (i16, i16, i16, i16),
) -> bool {
    // Disjoint boxes cannot overlap, and most pairs of holes in a real polygon are disjoint. This is
    // the test that takes the pairwise scan from quadratic-in-vertices to quadratic-in-holes.
    if !boxes_meet(a_box, b_box) {
        return false;
    }
    a.iter().filter(|&&p| point_in_ring(p, b, b_box)).count() > 1
        || b.iter().filter(|&&p| point_in_ring(p, a, a_box)).count() > 1
}

/// Even-odd ray cast, with the boundary counting as inside.
///
/// That last clause is what keeps clipped islands: the tile clipper emits exterior and hole
/// vertices onto the same tile-edge lines, so a hole clipped onto the tile edge touches its
/// exterior there. Reading those vertices as outside dropped the hole — an island filled in
/// solid on the fine tile while the coarse tile (hole fully inside) stayed pixel-perfect.
/// The renderer's `tess::fill::geom::point_within_ring` already counts the boundary as inside
/// for exactly this reason; stage C runs first and bakes its drops into the archive, so it
/// has to agree with it — a stricter rule here deletes islands the renderer would otherwise
/// have kept.
///
/// The leniency admits exactly coincident edges — a vertex ON the exterior — never a vertex
/// genuinely outside it: those still fail every edge test and the hole still goes. And it is
/// safe in this direction only: a hole the tiler keeps but the renderer drops costs one
/// island, while a hole the tiler drops is gone for every reader.
fn point_in_ring((x, y): (i16, i16), ring: &[(i16, i16)], ring_box: (i16, i16, i16, i16)) -> bool {
    // A point outside the box is outside the ring, for the cost of four comparisons rather than a
    // walk of every edge.
    if x < ring_box.0 || x > ring_box.2 || y < ring_box.1 || y > ring_box.3 {
        return false;
    }
    let (x, y) = (x as f64, y as f64);
    let mut inside = false;
    for pair in ring.windows(2) {
        let ((x0, y0), (x1, y1)) = (pair[0], pair[1]);
        let (x0, y0, x1, y1) = (x0 as f64, y0 as f64, x1 as f64, y1 as f64);
        if (y0 > y) != (y1 > y) {
            let t = (y - y0) / (y1 - y0);
            if x < x0 + t * (x1 - x0) {
                inside = !inside;
            }
        }
    }
    inside || on_boundary((x, y), ring)
}

/// Is the point exactly on one of the ring's edges?
///
/// The tile clipper emits exterior and hole vertices onto the same tile-edge lines, quantised
/// to the same `i16` grid — a hole clipped onto the tile edge shares exact coordinates with
/// the exterior's clipped edge there. An exact integer cross product (no division, so no
/// truncation putting the two sides of one edge in disagreement), mirroring the renderer's
/// `tess::fill::geom::on_boundary`.
fn on_boundary((x, y): (f64, f64), ring: &[(i16, i16)]) -> bool {
    for pair in ring.windows(2) {
        let ((x0, y0), (x1, y1)) = (pair[0], pair[1]);
        let (x0, y0, x1, y1) = (x0 as f64, y0 as f64, x1 as f64, y1 as f64);
        let cross = (x - x0) * (y1 - y0) - (y - y0) * (x1 - x0);
        if cross == 0.0
            && x >= x0.min(x1)
            && x <= x0.max(x1)
            && y >= y0.min(y1)
            && y <= y0.max(y1)
        {
            return true;
        }
    }
    false
}

/// **Verification item 6 of the plan's list.** Every ring of a layer must satisfy all five
/// invariants.
///
/// Returned as a list of complaints rather than a bool, so a failing build says which polygon and
/// why. Used by the generator's own tests over every tile of a real build.
#[cfg_attr(not(test), allow(dead_code))]
pub fn check(layer: &Layer) -> Vec<String> {
    let mut problems = Vec::new();
    for (index, feature) in layer.features.iter().enumerate() {
        if feature.geom_type != tilecodec::mamaps::body::GEOM_POLYGON {
            continue;
        }
        let parts = layer.parts_of(feature);
        let Some(exterior) = parts.first() else {
            problems.push(format!("feature {index} has no parts"));
            continue;
        };
        if exterior.winding != WINDING_OUTER {
            problems.push(format!("feature {index}'s first part is a hole"));
        }
        let exterior_points = layer.points(exterior);
        let area = signed_area(exterior_points);
        if area <= 0.0 {
            problems.push(format!("feature {index}'s exterior is not counter-clockwise ({area})"));
        }
        let holes: Vec<&Part> = parts[1..].iter().collect();
        if holes.iter().any(|part| part.winding != WINDING_HOLE) {
            problems.push(format!("feature {index} has a second exterior"));
        }
        for (i, hole) in holes.iter().enumerate() {
            let points = layer.points(hole);
            let area = signed_area(points);
            if area >= 0.0 {
                problems.push(format!("feature {index}'s hole {i} is not clockwise ({area})"));
            }
            if !strictly_inside(points, bounds(points), exterior_points, bounds(exterior_points))
            {
                problems.push(format!("feature {index}'s hole {i} is not inside its exterior"));
            }
            for (j, other) in holes.iter().enumerate().skip(i + 1) {
                let other_points = layer.points(other);
                if rings_overlap(points, bounds(points), other_points, bounds(other_points)) {
                    problems.push(format!("feature {index}'s holes {i} and {j} overlap"));
                }
            }
        }
    }
    problems
}

include!("rings_part1.rs");