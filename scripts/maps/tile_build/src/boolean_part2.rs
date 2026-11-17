/// Group rings into `[exterior, hole, ...]` polygons by nesting depth.
fn assemble(rings: Vec<Vec<Pt>>) -> Vec<Polygon> {
    // Depth by containment rather than by the sweep's `prev_in_result` chain: the chain is cheaper
    // but only correct when every ring closes cleanly, and a hard input can leave it inconsistent.
    // Containment is O(rings^2) point-in-polygon tests, and a tile has few rings.
    let mut depth = vec![0usize; rings.len()];
    for i in 0..rings.len() {
        let probe = rings[i][0];
        for (j, other) in rings.iter().enumerate() {
            if i == j {
                continue;
            }
            if point_in_ring(probe, other) {
                depth[i] += 1;
            }
        }
    }

    let mut out: Vec<Polygon> = Vec::new();
    let mut index_of: Vec<Option<usize>> = vec![None; rings.len()];
    // Even depth is an exterior, odd is a hole in the nearest enclosing exterior.
    for i in 0..rings.len() {
        if depth[i] % 2 == 0 {
            index_of[i] = Some(out.len());
            out.push(vec![orient(rings[i].clone(), true)]);
        }
    }
    for i in 0..rings.len() {
        if depth[i] % 2 != 0 {
            // The enclosing exterior is the deepest even-depth ring that contains it.
            let mut best: Option<(usize, usize)> = None;
            let probe = rings[i][0];
            for j in 0..rings.len() {
                if i == j || depth[j] % 2 != 0 {
                    continue;
                }
                if point_in_ring(probe, &rings[j]) && best.is_none_or(|(d, _)| depth[j] >= d) {
                    best = Some((depth[j], j));
                }
            }
            if let Some((_, j)) = best {
                if let Some(at) = index_of[j] {
                    out[at].push(orient(rings[i].clone(), false));
                }
            }
        }
    }
    out
}

/// Force a ring counter-clockwise for an exterior, clockwise for a hole.
fn orient(mut ring: Vec<Pt>, ccw: bool) -> Vec<Pt> {
    let mut area = 0.0;
    for i in 0..ring.len() {
        let a = ring[i];
        let b = ring[(i + 1) % ring.len()];
        area += a.0 * b.1 - b.0 * a.1;
    }
    if (area > 0.0) != ccw {
        ring.reverse();
    }
    ring
}

/// Even-odd ray cast. Matches `rings.rs`'s convention that a point on the boundary is outside.
fn point_in_ring(p: Pt, ring: &[Pt]) -> bool {
    let mut inside = false;
    let n = ring.len();
    for i in 0..n {
        let a = ring[i];
        let b = ring[(i + 1) % n];
        if (a.1 > p.1) != (b.1 > p.1) {
            let t = (p.1 - a.1) / (b.1 - a.1);
            if p.0 < a.0 + t * (b.0 - a.0) {
                inside = !inside;
            }
        }
    }
    inside
}

/// Where two segments meet: 0 not at all, 1 at a point, 2 along a shared stretch.
fn intersect(a1: Pt, a2: Pt, b1: Pt, b2: Pt) -> (u8, Pt, Pt) {
    let va = (a2.0 - a1.0, a2.1 - a1.1);
    let vb = (b2.0 - b1.0, b2.1 - b1.1);
    let cross = va.0 * vb.1 - va.1 * vb.0;
    let d = (b1.0 - a1.0, b1.1 - a1.1);

    if cross != 0.0 {
        let t = (d.0 * vb.1 - d.1 * vb.0) / cross;
        let u = (d.0 * va.1 - d.1 * va.0) / cross;
        // Endpoints count: touching is an intersection here, which is the point of using a method
        // that tolerates degeneracy.
        if (-EPS..=1.0 + EPS).contains(&t) && (-EPS..=1.0 + EPS).contains(&u) {
            let p = (a1.0 + t * va.0, a1.1 + t * va.1);
            return (1, p, p);
        }
        return (0, a1, a1);
    }

    // Parallel. Collinear only if b1 lies on a's line.
    if (d.0 * va.1 - d.1 * va.0) != 0.0 {
        return (0, a1, a1);
    }
    // Project both onto a's direction and overlap the intervals.
    let len2 = va.0 * va.0 + va.1 * va.1;
    if len2 == 0.0 {
        return (0, a1, a1);
    }
    let proj = |p: Pt| ((p.0 - a1.0) * va.0 + (p.1 - a1.1) * va.1) / len2;
    let (mut s0, mut s1) = (proj(b1), proj(b2));
    if s0 > s1 {
        std::mem::swap(&mut s0, &mut s1);
    }
    let lo = s0.max(0.0);
    let hi = s1.min(1.0);
    if lo > hi + EPS {
        return (0, a1, a1);
    }
    let at = |s: f64| (a1.0 + s * va.0, a1.1 + s * va.1);
    if (hi - lo).abs() < EPS {
        let p = at(lo);
        return (1, p, p);
    }
    (2, at(lo), at(hi))
}

/// Evaluate `op` on two sets of polygons.
///
/// Each polygon is `[exterior, hole, ...]`; ring orientation of the input is not significant, and
/// the output follows the convention in `rings.rs` — counter-clockwise exteriors, clockwise holes.
pub fn boolean(subject: &[Polygon], clip: &[Polygon], op: Op) -> Vec<Polygon> {
    // Trivial cases, which are also the common ones: a tile is usually all land or all sea.
    if subject.is_empty() {
        return match op {
            Op::Intersection | Op::Difference => Vec::new(),
            Op::Union | Op::Xor => clip.to_vec(),
        };
    }
    if clip.is_empty() {
        return match op {
            Op::Intersection => Vec::new(),
            Op::Union | Op::Difference | Op::Xor => subject.to_vec(),
        };
    }

    // Xor by composition rather than as its own sweep. `A^B` is `(A-B) | (B-A)` by definition, and
    // the two halves touch along the boundary of the overlap — a single sweep has to assemble
    // rings that meet without crossing, and got it wrong where the other three operations were
    // right. Built this way it is correct exactly as far as difference and union are, which is
    // what the randomised identities check.
    if op == Op::Xor {
        let left = boolean(subject, clip, Op::Difference);
        let right = boolean(clip, subject, Op::Difference);
        if left.is_empty() {
            return right;
        }
        if right.is_empty() {
            return left;
        }
        return boolean(&left, &right, Op::Union);
    }

    let mut sweep = Sweep::new();
    sweep.add_polygons(subject, Side::Subject);
    sweep.add_polygons(clip, Side::Clip);
    sweep.run(op)
}

/// `subject − clip`. The ocean is `tile rectangle − land`.
pub fn difference(subject: &[Polygon], clip: &[Polygon]) -> Vec<Polygon> {
    boolean(subject, clip, Op::Difference)
}

/// An axis-aligned rectangle as a single polygon, wound counter-clockwise.
pub fn rect(min_x: f64, min_y: f64, max_x: f64, max_y: f64) -> Polygon {
    vec![vec![
        (min_x, min_y),
        (max_x, min_y),
        (max_x, max_y),
        (min_x, max_y),
    ]]
}

/// Total signed area of a polygon set, positive for counter-clockwise exteriors.
///
/// Exposed because it is how the tests assert a result without depending on vertex order.
pub fn area(polys: &[Polygon]) -> f64 {
    let mut total = 0.0;
    for poly in polys {
        for (at, ring) in poly.iter().enumerate() {
            let mut a = 0.0;
            for i in 0..ring.len() {
                let p = ring[i];
                let q = ring[(i + 1) % ring.len()];
                a += p.0 * q.1 - q.0 * p.1;
            }
            a /= 2.0;
            // A hole subtracts whichever way it happens to be wound.
            total += if at == 0 { a.abs() } else { -a.abs() };
        }
    }
    total
}
