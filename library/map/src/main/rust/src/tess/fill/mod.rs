//! Turns MVT polygon geometry into fill triangles.
//!
//! The bridge between `tilecodec::mvt::decode_polygons`'s `[polygon][ring]` output
//! and [`super::earcut`]'s single flat array plus hole offsets. Fill vertices carry
//! position only — colour is a per-draw push constant, since a style layer is one
//! colour by definition and per-vertex colour would quadruple the vertex buffer for
//! nothing.
//!
//! # Why the rings are regrouped before they reach earcut
//!
//! Earcut's model is one exterior ring plus holes that are inside it and disjoint from
//! each other. The published archive does not honour that. Its z0 ocean polygon carries
//! 105 holes of which 40 overlap another hole, 6 straddle the exterior boundary, 4 lie
//! wholly outside it, and 13 are lakes nested *inside* a continent hole. Handing that
//! straight to earcut is what produced the fan of slivers over the Arctic: a bridged ring
//! that self-intersects has no ears, so clipping stalls, the split-and-retry fallback
//! fires dozens of times, and it emits triangles that span the polygon. The geometry is
//! only 0.0016 of the tile wrong; the tessellation came out 0.089 wrong — 12% of the tile
//! painted blue over land.
//!
//! So [`grouping::polygons`] rebuilds the ring nesting the way the fill rules define it: a ring at
//! even nesting depth is an exterior, a ring at odd depth is a hole of the ring that
//! encloses it most closely, and each exterior is tessellated separately. That alone fixes
//! the nested lakes, which earcut has no way to express. The remaining conflicts — a hole
//! that crosses its own exterior, or two holes that overlap — cannot be expressed either,
//! and those holes are dropped, largest first so the biggest island survives. Filling in a
//! small island costs far less area than the slivers do.
//!
//! Earcut itself now matches upstream v2.2.4 exactly and is deliberately kept that way, so
//! it can still be diffed against the reference; nothing here works around a defect in it.
//!
//! The invalid geometry very likely originates in our own tile build, which clips each
//! ring independently — see `scripts/maps`. The renderer has to cope with the archive that
//! exists regardless.

use super::earcut;

mod geom;
mod grouping;
#[cfg(test)]
mod tests;
#[cfg(test)]
mod tests_part2;

use self::geom::open_length;
use self::grouping::polygons;

/// Floats per vertex: `x, y, z`.
///
/// `z` is the draped ground height in the same tile-local unit as `x`/`y` (metres
/// over the tile's ground width, plus a sub-metre lift) — `0.0` straight out of the
/// tessellator, filled in by the drape pass at build time where the tile carries a
/// heightmap. The tilted clip matrix reads it; at pitch 0 it is ignored for x/y.
pub const FLOATS_PER_VERTEX: usize = 3;

/// Coordinates below this many make a ring that encloses nothing.
const MIN_RING_COORDS: usize = 6;

/// Groups with each group's holes sorted, so two groupings compare equal when they hold the
/// same exteriors with the same holes in any order. Debug-assertion support only (still
/// compiled in release: `debug_assert_eq!` type-checks its operands there).
#[cfg_attr(not(debug_assertions), allow(dead_code))]
fn canonical_groups(mut groups: Vec<Vec<usize>>) -> Vec<Vec<usize>> {
    for group in &mut groups {
        if group.len() > 2 {
            group[1..].sort_unstable();
        }
    }
    groups
}

/// Tessellate one polygon: the exterior ring first, then holes, each flat and
/// **closed** as the decoder returns them.
///
/// `validated` says the producer already normalised the rings — exactly one exterior, wound
/// consistently, holes strictly inside it and not overlapping each other. A `.mamaps` archive says
/// so in its header; anything else does not.
///
/// **The repair pass is kept either way.** Deleting it would turn a generator bug into an on-device
/// artifact, or an earcut hang, with no diagnostic — and in a debug build it runs regardless and
/// asserts it found nothing to fix, so the claim is continuously verified on the device rather than
/// taken on trust.
pub fn tessellate(
    rings: &[Vec<(i32, i32)>],
    extent: u32,
    validated: bool,
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
) {
    // Earcut wants rings with no repeated closing vertex, in one flat array.
    let mut open: Vec<&[(i32, i32)]> = Vec::with_capacity(rings.len());
    for (r, ring) in rings.iter().enumerate() {
        let length = open_length(ring);
        if length * 2 < MIN_RING_COORDS {
            // A dropped exterior takes its holes with it — a hole with nothing around
            // it renders as solid fill, which is worse than the feature being absent.
            // A dropped hole is only a missing hole.
            if r == 0 {
                return;
            }
            continue;
        }
        open.push(&ring[..length]);
    }
    if open.is_empty() {
        return;
    }

    // Only the rings that made it into a group are emitted, so a dropped hole leaves no
    // unreferenced vertices behind — the z0 ocean drops enough of them to matter. `offsets`
    // maps a ring to where its vertices land, so a group's triangles can be mapped back
    // after being renumbered for its own earcut call.
    let groups = if validated {
        // One group: the exterior and every hole, in the order the producer stated them. Which is
        // what `polygons` would work out anyway, at the cost of an O(holes x outers) containment
        // scan per feature per frame.
        let trusted = vec![(0..open.len()).collect::<Vec<usize>>()];
        // Compared up to hole order: the repair pass sorts holes largest-first, the producer
        // keeps its own order (planet-scale v10 bodies routinely differ), and earcut draws the
        // same area either way. A dropped, extra or regrouped ring still fails.
        debug_assert_eq!(
            canonical_groups(polygons(&open)),
            canonical_groups(trusted.clone()),
            "an archive claimed validated rings and the repair pass disagreed",
        );
        trusted
    } else {
        polygons(&open)
    };
    let mut offsets = vec![usize::MAX; open.len()];
    let mut emitted: Vec<usize> = Vec::with_capacity(open.len());
    let mut vertex_count = 0usize;
    for group in &groups {
        for &r in group {
            if offsets[r] == usize::MAX {
                offsets[r] = vertex_count;
                vertex_count += open[r].len();
                emitted.push(r);
            }
        }
    }

    let mut triangles: Vec<u32> = Vec::new();
    let mut coords: Vec<i32> = Vec::new();
    let mut hole_starts: Vec<usize> = Vec::new();
    let mut global: Vec<u32> = Vec::new();
    for group in &groups {
        coords.clear();
        hole_starts.clear();
        global.clear();
        for (position, &r) in group.iter().enumerate() {
            if position > 0 {
                hole_starts.push(coords.len() / 2);
            }
            for (v, &(x, y)) in open[r].iter().enumerate() {
                coords.push(x);
                coords.push(y);
                global.push((offsets[r] + v) as u32);
            }
        }
        triangles.extend(
            earcut::triangulate(&coords, &hole_starts)
                .iter()
                .map(|&t| global[t as usize]),
        );
    }
    if triangles.is_empty() {
        return;
    }

    let scale = 1.0 / extent as f32;
    let base = (vertices.len() / FLOATS_PER_VERTEX) as u32;
    for &r in &emitted {
        for &(x, y) in open[r].iter() {
            vertices.push(x as f32 * scale);
            vertices.push(y as f32 * scale);
            vertices.push(0.0);
        }
    }
    indices.extend(triangles.iter().map(|&t| base + t));
}
