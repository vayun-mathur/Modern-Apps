// The segmented placer's broadphase: a uniform-grid index over accepted boxes.
//
// File-length split from `tile::placement` (see `placement.rs`): the greedy
// `place_segmented` pass plus the grid helpers it reads. Included textually via
// `include!`, so everything here shares `placement.rs`'s scope — `Obb`,
// `obb_overlap`, `SegmentedCandidate` and `Placed` resolve as if this were one
// file.
use std::collections::HashMap;

/// Greedily place candidates whose footprints are oriented-box sets: the segmented counterpart of
/// the `place` function in `placement.rs`, and the one the curved labels use so a street name
/// collides with a point label box-for-box rather than as one loose rectangle.
///
/// Ordering, rank-0-never-collides and the variable-anchor fallback are identical to `place`;
/// only the geometry test changes (any box against any accepted box, by `obb_overlap`). A point
/// label enters as a one-box candidate, so point and curved labels place in the **same** pass and
/// therefore collide with each other, which is the whole point of the exercise.
///
/// Performance shape: footprint areas are hoisted into a side table (the sort
/// comparator never recomputes the sum), the accepted-box buffer is reserved up
/// front, and collision runs through a uniform-grid broadphase — cell size from
/// the max AABB half-extent — with the exact narrowphase. Bit-identical accept
/// order, accept set and `flipped` flags versus the brute-force loop.
pub fn place_segmented(candidates: &[SegmentedCandidate]) -> Vec<Placed> {
    // Hoisted footprint areas: `SegmentedCandidate::area` computed once per
    // candidate into a side table. The comparator below reads this table, so
    // sorting never recomputes the sum.
    let mut areas: Vec<f32> = Vec::with_capacity(candidates.len());
    for c in candidates.iter() {
        areas.push(c.area());
    }
    // Sort indices (stable) by the same key as before: rank, pop desc, area
    // desc, id. Starting from 0..n with a stable sort and identical keys yields
    // the identical order as sorting `Vec<&Candidate>` from iteration order.
    let mut ordered: Vec<usize> = (0..candidates.len()).collect();
    ordered.sort_by(|&ai, &bi| {
        let (a, b) = (&candidates[ai], &candidates[bi]);
        a.rank
            .cmp(&b.rank)
            .then_with(|| b.pop.cmp(&a.pop))
            .then_with(|| {
                areas[bi]
                    .partial_cmp(&areas[ai])
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
            .then_with(|| a.id.cmp(&b.id))
    });
    // Reserve the accepted-box buffer from the candidate count (primary boxes;
    // alternates substitute 1:1 so this bounds the accepted set size).
    let total_primary: usize = candidates.iter().map(|c| c.boxes.len()).sum();
    let mut accepted: Vec<Obb> = Vec::with_capacity(total_primary);
    // Uniform-grid broadphase over accepted boxes. Cell size derives from the
    // max AABB half-extent over all candidate boxes (padding is already baked
    // into the boxes); any cell size keeps results exact, this one keeps each
    // box in few cells.
    let mut max_e: f32 = 0.0;
    for c in candidates.iter() {
        for b in c.boxes.iter().chain(c.alternate.iter().flatten()) {
            let (ex, ey) = obb_aabb_half(b);
            max_e = max_e.max(ex).max(ey);
        }
    }
    let mut cell: f32 = max_e * 2.0;
    if !matches!(
        cell.partial_cmp(&1.0),
        Some(std::cmp::Ordering::Greater | std::cmp::Ordering::Equal)
    ) {
        cell = 1.0;
    }
    let mut grid: HashMap<(i32, i32), Vec<usize>> = HashMap::new();
    let mut out = Vec::new();
    for &ci in ordered.iter() {
        let c = &candidates[ci];
        // Rank 0 short-circuits before any geometry, as before.
        let free = |boxes: &[Obb]| {
            if c.rank == 0 {
                return true;
            }
            for b in boxes.iter() {
                let (x0, y0, x1, y1) = obb_aabb(b);
                let (cx0, cy0, cx1, cy1) = cell_range(x0, y0, x1, y1, cell);
                for cy in cy0..=cy1 {
                    for cx in cx0..=cx1 {
                        if let Some(bucket) = grid.get(&(cx, cy)) {
                            for &ai in bucket.iter() {
                                // Narrowphase stays exact `obb_overlap`, same
                                // argument order as the brute-force loop.
                                if obb_overlap(&accepted[ai], b) {
                                    return false;
                                }
                            }
                        }
                    }
                }
            }
            true
        };
        let taken = if free(&c.boxes) {
            (&c.boxes, false)
        } else if let Some(alternate) = c.alternate.as_ref().filter(|a| free(a)) {
            (alternate, true)
        } else {
            continue;
        };
        for b in taken.0.iter() {
            let idx = accepted.len();
            accepted.push(*b);
            let (x0, y0, x1, y1) = obb_aabb(b);
            let (cx0, cy0, cx1, cy1) = cell_range(x0, y0, x1, y1, cell);
            for cy in cy0..=cy1 {
                for cx in cx0..=cx1 {
                    grid.entry((cx, cy)).or_default().push(idx);
                }
            }
        }
        out.push((c.id, taken.1));
    }
    out
}

/// Half-extents of an OBB's axis-aligned bounding box.
///
/// `pub(crate)` for the renderer's viewport cull: the AABB conservatively bounds the OBB,
/// so a candidate whose every box AABB misses the viewport can draw no visible pixel.
pub(crate) fn obb_aabb_half(b: &Obb) -> (f32, f32) {
    let (c, s) = (b.cos.abs(), b.sin.abs());
    (b.hx * c + b.hy * s, b.hx * s + b.hy * c)
}

/// Full AABB of an OBB in screen px.
fn obb_aabb(b: &Obb) -> (f32, f32, f32, f32) {
    let (ex, ey) = obb_aabb_half(b);
    (b.cx - ex, b.cy - ey, b.cx + ex, b.cy + ey)
}

/// Grid cells overlapped by an AABB (inclusive range via floor).
fn cell_range(x0: f32, y0: f32, x1: f32, y1: f32, cell: f32) -> (i32, i32, i32, i32) {
    let (lo_x, hi_x) = if x0 <= x1 { (x0, x1) } else { (x1, x0) };
    let (lo_y, hi_y) = if y0 <= y1 { (y0, y1) } else { (y1, y0) };
    (
        (lo_x / cell).floor() as i32,
        (lo_y / cell).floor() as i32,
        (hi_x / cell).floor() as i32,
        (hi_y / cell).floor() as i32,
    )
}
