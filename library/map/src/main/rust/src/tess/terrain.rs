//! Tessellates a tile's ground into a DEM-displaced relief grid (WS-G, 3D terrain).
//!
//! Render-time only, like [`super::roof`]: the archive carries a per-tile `u16` heightmap in a
//! side table (metres above sea level, biased by 32768 — see
//! [`Heightmap`](tilecodec::mamaps::body::Heightmap)), never triangles. This module turns one
//! tile's heightmap into a regular grid of triangles whose per-vertex `z` rises and falls with the
//! terrain, so under camera tilt the ground shows real relief and occludes what sits behind a hill.
//!
//! # The terrain vertex
//!
//! Six floats — position with a height, plus a surface normal. Unlike a building it needs no
//! per-vertex colour: the whole ground is one style colour (the `earth` arm's, on the
//! `landtype` source since v8), pushed per draw,
//! and the relief is conveyed by shading that colour against the normal.
//!
//! | offset | floats | meaning                                                        |
//! |--------|--------|----------------------------------------------------------------|
//! | 0..12  | 3      | tile-local `(u, v, height)`; `u,v` in 0..1, `height` tile-norm  |
//! | 12..24 | 3      | surface normal in the same tile-local space, unit length       |
//!
//! `height` is **tile-normalised** — metres divided by the tile's own ground width, the same unit
//! `u`/`v` are — so the grid is zoom-independent like every other tile mesh, exactly as
//! [`super::roof`] normalises building heights. The terrain vertex shader multiplies it by the
//! tile's world-px span (a per-frame push scalar) to recover the world-px height the WS0
//! perspective matrix expects in its `z` input. At pitch 0 that matrix ignores `z` for `x`/`y`, so
//! the grid collapses to the flat footprint of the tile — the flat map the app has always shown.
//!
//! # The grid
//!
//! One vertex per heightmap sample (`dim * dim`), so the DEM is used at its own resolution with no
//! resampling, and `(dim - 1)^2` quads between them. Normals come from central differences of the
//! neighbouring heights (a standard heightfield normal), so a slope reads darker than a flat and
//! the relief is legible without any shadow pass. The differences read a 3×3-smoothed copy of the
//! height field — interior texels only, the border ring verbatim — so facet noise softens while
//! the two tiles sharing an edge smooth from the same edge samples and agree exactly. Positions
//! always carry the raw heights untouched. A tile whose samples are all equal (flat DEM, or
//! filled sea level) produces a flat sheet with every normal pointing straight up — indistinguishable
//! from the old flat ground once shading is disabled at pitch 0.
//!
//! # The skirt
//!
//! One vertical wall quad per edge segment, hung from the edge vertices straight down by
//! [`SKIRT_DEPTH`]. Neighbouring tiles sample the shared edge at
//! different resolutions (different LODs, different DEM dims), so under tilt the two displaced
//! edges part and the gap would show the clear colour through it; the skirts overlap instead,
//! and the seam reads as shaded earth. Each skirt vertex copies its edge vertex's `(u, v)` and
//! normal and differs only in `z`. Skirt vertices are appended after the grid vertices (and
//! skirt indices after the grid indices), so the grid prefix of both buffers is exactly the
//! triangulation described above, bit for bit.

use tilecodec::mamaps::body::Heightmap;

/// Floats per terrain vertex: `x, y, z, nx, ny, nz`.
pub const FLOATS_PER_VERTEX: usize = 6;

/// How far below its edge vertex each skirt vertex hangs, in tile-normalised units — the same
/// unit the grid heights carry, so the drop is zoom-independent like the grid itself.
///
/// A skirt only has to outlast the *mismatch* between two neighbours' displaced edges, not the
/// relief itself: both tiles store their own copy of the shared edge samples, so matched
/// resolutions agree exactly and part only by the resampling error where resolutions differ — a
/// fraction of one cell's relief. Five percent of the tile width buries that with room to spare,
/// while staying shallow enough to cost no depth precision worth naming.
pub const SKIRT_DEPTH: f32 = 0.05;

/// Smooth the height field for normals only: a 3×3 box blur over interior texels, with the
/// border ring copied verbatim.
///
/// The neighbour tile across a shared edge smooths from the same edge samples (it stores its own
/// copy of them, and neither side's blur kernel reaches past its own border), so the two shared
/// edge slopes — and hence the two edge normals — agree bit-for-bit: zero seam risk by
/// construction. Kept on `f32` tile-normalised heights so the test can compare pre/post blur
/// exactly, and so positions — which must stay exact — never pass through it.
fn smooth_heights(raw: &[f32], dim: usize) -> Vec<f32> {
    let mut out = raw.to_vec();
    if dim < 3 {
        // No interior texel exists (a dim-2 grid is all border), so there is nothing to blur.
        return out;
    }
    for row in 1..dim - 1 {
        for col in 1..dim - 1 {
            let mut sum = 0.0f32;
            for dr in -1..=1 {
                for dc in -1..=1 {
                    sum += raw[((row as isize + dr) as usize) * dim + (col as isize + dc) as usize];
                }
            }
            out[row * dim + col] = sum / 9.0;
        }
    }
    out
}

/// Grid vertex indices around the tile edge in wall order: the top row west-to-east, the right
/// column skipping the top corner, the bottom row east-to-west, the left column skipping both
/// corners — a closed loop of `4 * (dim - 1)` samples the skirt walls hang from. Shared with
/// the tests so they can walk the same ring the walls do.
fn edge_ring(dim: usize) -> Vec<usize> {
    let mut ring = Vec::with_capacity(4 * (dim - 1));
    for col in 0..dim {
        ring.push(col);
    }
    for row in 1..dim {
        ring.push(row * dim + dim - 1);
    }
    for col in (0..dim - 1).rev() {
        ring.push((dim - 1) * dim + col);
    }
    for row in (1..dim - 1).rev() {
        ring.push(row * dim);
    }
    ring
}

/// Tessellate one tile's heightmap into a relief grid, appending to `out_v`/`out_i`.
///
/// `ground_width_m` is the tile's own east–west ground width in metres (see
/// [`crate::tile::geometry`]), the horizontal unit the heights are normalised against so a metre up
/// reads the same on screen as a metre across. A `dim` below 2 (or a zero ground width) makes no
/// surface and emits nothing.
///
/// The output is indexed and shares vertices between adjacent quads: unlike the flat-shaded
/// building mesh, terrain normals are per-vertex (Gouraud across the grid), so sharing is both
/// correct and cheaper.
///
/// A vertical skirt then walls the grid edge (see [`SKIRT_DEPTH`]): one skirt vertex per edge
/// sample, appended after the grid vertices, and one wall quad per edge segment, appended after
/// the grid indices — so the grid prefix of both buffers is the untouched triangulation above.
pub fn tessellate(hm: &Heightmap, ground_width_m: f64, out_v: &mut Vec<f32>, out_i: &mut Vec<u32>) {
    tessellate_masked(hm, ground_width_m, None, out_v, out_i);
}

/// [`tessellate`], but drawing the grid only where `land` says the sample is over land.
///
/// `land` is a `dim * dim` row-major mask (the same order the grid vertices are emitted in): a cell
/// contributes its two triangles only when all four of its corners are land, and a skirt wall only
/// when both of its edge samples are. `None` means "all land" — the exact output [`tessellate`]
/// gives, so the no-mask callers and their goldens are unchanged.
///
/// This is what keeps the earth-coloured relief off the sea: the archive attaches a flat sea-level
/// heightmap to coastal tiles that straddle the shore, and without this the whole tile's grid drew,
/// painting shaded earth over open water that has no fill to cover it back (the sea is the clear
/// colour, not a polygon). The mask is built from the tile's coastline land polygons — see
/// [`crate::tile::geometry::terrain`]. Every grid vertex is still emitted whatever the mask says, so
/// the row-major vertex order the skirts and callers index into is unchanged; only triangles are
/// withheld.
pub fn tessellate_masked(
    hm: &Heightmap,
    ground_width_m: f64,
    land: Option<&[bool]>,
    out_v: &mut Vec<f32>,
    out_i: &mut Vec<u32>,
) {
    let dim = hm.dim as usize;
    if dim < 2 {
        return;
    }
    // A cell (its four corners) or a skirt segment (its two ends) is drawn only when every sample
    // it touches is land. `None` admits everything, so the un-masked path is bit-identical.
    let is_land = |i: usize| land.is_none_or(|m| m.get(i).copied().unwrap_or(false));
    // Tile-normalised height per metre; a degenerate (polar) tile with zero width flattens rather
    // than dividing by zero, matching how buildings handle the same edge.
    let factor = if ground_width_m > 0.0 {
        1.0 / ground_width_m
    } else {
        0.0
    };
    // A sample's height in tile-normalised units. Out-of-range reads back sea level (the bias),
    // which the grid never asks for but keeps the closure total.
    let height = |col: usize, row: usize| -> f32 {
        let stored = hm.sample(col as u16, row as u16).unwrap_or(32768);
        (Heightmap::metres(stored) as f64 * factor) as f32
    };

    let step = 1.0 / (dim as f32 - 1.0);
    let base = (out_v.len() / FLOATS_PER_VERTEX) as u32;
    // The blurred field behind the normals: central differences below read from this, so
    // per-sample DEM noise softens, while positions keep the raw `height(col, row)`. The
    // border ring is verbatim by construction, so the shared-edge slopes — and the edge
    // normals — agree bit-for-bit with the neighbour tile's.
    let smooth = {
        let mut raw = Vec::with_capacity(dim * dim);
        for row in 0..dim {
            for col in 0..dim {
                raw.push(height(col, row));
            }
        }
        smooth_heights(&raw, dim)
    };
    let shed = |col: usize, row: usize| -> f32 { smooth[row * dim + col] };
    for row in 0..dim {
        for col in 0..dim {
            let u = col as f32 * step;
            let v = row as f32 * step;
            let z = height(col, row);

            // Central differences over the *smoothed* field, clamped at the tile edge. The spacing
            // is the tile-normalised grid step, so the slope is in the same units as `z`, which
            // keeps the normal honest against the height the vertex actually carries.
            let cl = col.saturating_sub(1);
            let cr = (col + 1).min(dim - 1);
            let ru = row.saturating_sub(1);
            let rd = (row + 1).min(dim - 1);
            let dzdu = (shed(cr, row) - shed(cl, row)) / ((cr - cl) as f32 * step);
            let dzdv = (shed(col, rd) - shed(col, ru)) / ((rd - ru) as f32 * step);
            // The upward normal of a heightfield z = f(u, v) is (-dz/du, -dz/dv, 1), normalised.
            let mut n = [-dzdu, -dzdv, 1.0];
            let len = (n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).sqrt();
            if len > f32::EPSILON {
                n = [n[0] / len, n[1] / len, n[2] / len];
            } else {
                n = [0.0, 0.0, 1.0];
            }
            out_v.extend_from_slice(&[u, v, z, n[0], n[1], n[2]]);
        }
    }

    let dim32 = dim as u32;
    for row in 0..dim - 1 {
        for col in 0..dim - 1 {
            let a = base + row as u32 * dim32 + col as u32;
            let b = a + 1;
            let c = a + dim32;
            let d = c + 1;
            // Withhold a cell whose footprint reaches the sea: all four corners must be land, so a
            // coastal cell drops out and the open water below shows the clear colour instead of
            // shaded earth. A land-only tile keeps every cell (the mask is all-true or absent).
            let cell = row * dim + col;
            if !(is_land(cell) && is_land(cell + 1) && is_land(cell + dim) && is_land(cell + dim + 1))
            {
                continue;
            }
            // Two triangles per cell, consistently wound; culling is off in the pipeline so the
            // winding only ever decides nothing visible, but it is kept regular for readability.
            out_i.extend_from_slice(&[a, b, d, a, d, c]);
        }
    }

    // Vertical skirts around the grid edge: one wall quad per edge segment, hung from the edge
    // vertices straight down by SKIRT_DEPTH. Neighbouring tiles sample the shared edge at
    // different resolutions (different LODs, different DEM dims), so under tilt the two displaced
    // edges part and the gap would show the clear colour; the skirts overlap instead, and the
    // seam reads as shaded earth. Each skirt vertex copies its edge vertex's `(u, v)` and normal
    // bit-for-bit and differs only in `z`, so the top edge stays exact and shading continues
    // uninterrupted. Skirt vertices are appended after the grid vertices (and skirt indices after
    // the grid indices), so the grid prefix of both buffers is the untouched triangulation
    // above — including the row-major vertex order callers index into.
    let ring = edge_ring(dim);
    let skirt_base = base + (dim * dim) as u32;
    for &edge in &ring {
        let at = (base as usize + edge) * FLOATS_PER_VERTEX;
        let dropped = [
            out_v[at],
            out_v[at + 1],
            out_v[at + 2] - SKIRT_DEPTH,
            out_v[at + 3],
            out_v[at + 4],
            out_v[at + 5],
        ];
        out_v.extend_from_slice(&dropped);
    }
    let n = ring.len() as u32;
    for k in 0..n {
        let e0 = ring[k as usize];
        let e1 = ring[((k + 1) % n) as usize];
        // A skirt only walls a real land edge: over the sea the grid is gone, so its skirt would
        // be a lone earth-coloured wall hanging in open water under tilt. Drop it when either end
        // is not land.
        if !(is_land(e0) && is_land(e1)) {
            continue;
        }
        let t0 = base + e0 as u32;
        let t1 = base + e1 as u32;
        let s0 = skirt_base + k;
        let s1 = skirt_base + (k + 1) % n;
        // One wall quad per edge segment, wound like the grid cells; culling is off so the
        // winding only ever decides nothing visible, but it is kept regular for readability.
        out_i.extend_from_slice(&[t0, t1, s1, t0, s1, s0]);
    }
}

#[cfg(test)]
mod tests {
    include!("terrain_tests.rs");
}
