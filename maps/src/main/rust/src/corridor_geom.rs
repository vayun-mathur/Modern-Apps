//! Polyline ground geometry for corridor bundling, ported from
//! `scripts/maps/gtfs_ingest/src/shapes.rs` (the `project` / `distance_m` /
//! `resample` primitives) and `bundle_part2.rs` (cumulative measurement,
//! slicing, side tests and probe walks).
//!
//! Points are `(lat_e7, lon_e7)` integer pairs and distances are ground
//! metres in a local equirectangular frame, exactly as the tool measures
//! them — the corridor thresholds (`CORRIDOR_M` etc. in [`super::corridor`])
//! are calibrated in those units. Only the measurement subset is ported:
//! fitting (`fit`), simplification and wire encoding stay build-side.

/// Nearest point on segment `a`-`b` to `p`, with the parameter forced to at
/// least `t_min`. Returns `(t, distance in metres)`. Local equirectangular
/// metres about the segment's own mean latitude, exact enough at
/// shape-segment scale.
pub fn project(p: (i32, i32), a: (i32, i32), b: (i32, i32), t_min: f64) -> (f64, f64) {
    let cos_lat = f64::from(a.0 + b.0) * 0.5 * 1e-7;
    let cos_lat = cos_lat.to_radians().cos();
    let m = |q: (i32, i32)| {
        (
            f64::from(q.1) * 1e-7 * 111_320.0 * cos_lat,
            f64::from(q.0) * 1e-7 * 111_320.0,
        )
    };
    let (ax, ay) = m(a);
    let (bx, by) = m(b);
    let (px, py) = m(p);
    let (dx, dy) = (bx - ax, by - ay);
    let len2 = dx * dx + dy * dy;
    let t = if len2 <= 0.0 {
        t_min
    } else {
        (((px - ax) * dx + (py - ay) * dy) / len2).clamp(t_min, 1.0)
    };
    let (cx, cy) = (ax + t * dx, ay + t * dy);
    (t, ((px - cx).powi(2) + (py - cy).powi(2)).sqrt())
}

/// Ground distance between two e7 points in metres, in the same local
/// equirectangular frame [`project`] measures in.
pub fn distance_m(a: (i32, i32), b: (i32, i32)) -> f64 {
    let cos_lat = f64::from(a.0 + b.0) * 0.5 * 1e-7;
    let cos_lat = cos_lat.to_radians().cos();
    let dy = (f64::from(b.0) - f64::from(a.0)) * 1e-7 * 111_320.0;
    let dx = (f64::from(b.1) - f64::from(a.1)) * 1e-7 * 111_320.0 * cos_lat;
    (dx * dx + dy * dy).sqrt()
}

/// Walk `points` emitting a point every `step_m` metres along it.
///
/// The first and last points are always kept, so the walk covers the whole
/// polyline and a short one still yields both ends.
pub fn resample(points: &[(i32, i32)], step_m: f64) -> Vec<(i32, i32)> {
    if points.len() < 2 || step_m.is_nan() || step_m <= 0.0 {
        return points.to_vec();
    }
    let lerp = |a: i32, b: i32, t: f64| (f64::from(a) + (f64::from(b) - f64::from(a)) * t).round() as i32;
    let mut out = vec![points[0]];
    // Distance already walked since the last emitted sample, carried across
    // the segment boundary so the spacing is along the polyline, not per
    // segment.
    let mut carry = 0.0f64;
    for pair in points.windows(2) {
        let (a, b) = (pair[0], pair[1]);
        let length = distance_m(a, b);
        if length <= 0.0 {
            continue;
        }
        let mut at = step_m - carry;
        while at < length {
            let t = at / length;
            out.push((lerp(a.0, b.0, t), lerp(a.1, b.1, t)));
            at += step_m;
        }
        carry = length - (at - step_m);
    }
    if let Some(&last) = points.last() {
        if out[out.len() - 1] != last {
            out.push(last);
        }
    }
    out
}

/// Cumulative ground distance to each vertex of a polyline.
pub fn cumulative(points: &[(i32, i32)]) -> Vec<f64> {
    let mut out = Vec::with_capacity(points.len());
    let mut total = 0.0;
    for (at, point) in points.iter().enumerate() {
        if at > 0 {
            total += distance_m(points[at - 1], *point);
        }
        out.push(total);
    }
    out
}

/// The point `at` metres along a polyline, interpolated within its segment.
pub fn point_at(points: &[(i32, i32)], cum: &[f64], at: f64) -> (i32, i32) {
    if points.len() < 2 {
        return points.first().copied().unwrap_or((0, 0));
    }
    let last = points.len() - 1;
    if at <= 0.0 {
        return points[0];
    }
    if at >= cum[last] {
        return points[last];
    }
    let seg = cum.partition_point(|d| *d <= at).max(1) - 1;
    let span = cum[seg + 1] - cum[seg];
    let t = if span > 0.0 { (at - cum[seg]) / span } else { 0.0 };
    let (a, b) = (points[seg], points[seg + 1]);
    (
        (f64::from(a.0) + (f64::from(b.0) - f64::from(a.0)) * t).round() as i32,
        (f64::from(a.1) + (f64::from(b.1) - f64::from(a.1)) * t).round() as i32,
    )
}

/// The part of a polyline between two distances along it, with both ends
/// interpolated.
///
/// Both ends land exactly on the polyline, so two adjacent slices share
/// their boundary vertex and the pieces of one route meet. Empty when the
/// two distances leave nothing between them.
pub fn slice_between(points: &[(i32, i32)], cum: &[f64], from: f64, to: f64) -> Vec<(i32, i32)> {
    if points.len() < 2 {
        return Vec::new();
    }
    let total = cum[cum.len() - 1];
    let (from, to) = (from.clamp(0.0, total), to.clamp(0.0, total));
    let (from, to) = if from <= to { (from, to) } else { (to, from) };
    let mut out = vec![point_at(points, cum, from)];
    for (at, along) in cum.iter().enumerate() {
        if *along > from && *along < to && out[out.len() - 1] != points[at] {
            out.push(points[at]);
        }
    }
    let end = point_at(points, cum, to);
    if out[out.len() - 1] != end {
        out.push(end);
    }
    if out.len() < 2 {
        Vec::new()
    } else {
        out
    }
}

/// How far to the side of a reference polyline `p` lies, in metres, signed
/// positive to the right of its direction of travel. Only segments within
/// `extent` (cumulative metres) are searched: past a corridor mouth the
/// reference heads wherever it goes next, which is no axis to measure a side
/// against. Ported from the tool's `signed_offset`, minus the corridor
/// wrapper: callers pass the reference's own points, cum and extent.
pub fn signed_offset_side(
    points: &[(i32, i32)],
    cum: &[f64],
    extent: (f64, f64),
    p: (i32, i32),
) -> f64 {
    let mut best = (f64::INFINITY, 0.0f64);
    for at in 0..points.len().saturating_sub(1) {
        let (Some(&ca), Some(&cb)) = (cum.get(at), cum.get(at + 1)) else {
            continue;
        };
        if cb < extent.0 || ca > extent.1 {
            continue;
        }
        let (a, b) = (points[at], points[at + 1]);
        let (_, distance) = project(p, a, b, 0.0);
        if distance >= best.0 {
            continue;
        }
        let cos_lat = f64::from(a.0 + b.0) * 0.5 * 1e-7;
        let cos_lat = cos_lat.to_radians().cos();
        let (east, north) = (f64::from(b.1 - a.1) * cos_lat, f64::from(b.0 - a.0));
        let length = (east * east + north * north).sqrt();
        if length <= 0.0 {
            continue;
        }
        let (ux, uy) = (east / length, north / length);
        let d_east = f64::from(p.1 - a.1) * 1e-7 * 111_320.0 * cos_lat;
        let d_north = f64::from(p.0 - a.0) * 1e-7 * 111_320.0;
        best = (distance, d_east * uy - d_north * ux);
    }
    best.1
}

/// One probe point along a candidate, with the unit direction of travel there.
pub struct DirectedPoint {
    /// The e7 point.
    pub point: (i32, i32),
    /// Unit tangent in an (east, north) frame with cosine latitude scaling.
    pub ux: f64,
    /// Unit tangent in an (east, north) frame with cosine latitude scaling.
    pub uy: f64,
}

/// Each point of `line` with the unit direction of travel there. One entry
/// per input point: a degenerate segment carries the previous direction
/// forward rather than dropping the point, so the result stays
/// index-aligned with its input.
pub fn directed(line: &[(i32, i32)]) -> Vec<DirectedPoint> {
    let mut out = Vec::with_capacity(line.len());
    let (mut ux, mut uy) = (1.0f64, 0.0f64);
    for i in 0..line.len() {
        let (a, b) = if i + 1 < line.len() {
            (line[i], line[i + 1])
        } else if i > 0 {
            (line[i - 1], line[i])
        } else {
            // A single point has no direction; the initial east stands in.
            out.push(DirectedPoint { point: line[i], ux, uy });
            continue;
        };
        let cos_lat = (f64::from(a.0 + b.0) * 0.5 * 1e-7).to_radians().cos();
        let dy = f64::from(b.0 - a.0);
        let dx = f64::from(b.1 - a.1) * cos_lat;
        let length = (dx * dx + dy * dy).sqrt();
        if length > 0.0 {
            (ux, uy) = (dx / length, dy / length);
        }
        out.push(DirectedPoint { point: line[i], ux, uy });
    }
    out
}

/// Walk a polyline at `step_m`, returning each sample with its unit
/// direction of travel.
pub fn walk_directed(points: &[(i32, i32)], step_m: f64) -> Vec<DirectedPoint> {
    let walked = resample(points, step_m);
    if walked.len() < 2 {
        return Vec::new();
    }
    directed(&walked)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_metre_is_a_metre_in_both_axes() {
        // 0.001 degrees of latitude is 111.3 m anywhere.
        let north = distance_m((377_000_000, -1_224_000_000), (377_010_000, -1_224_000_000));
        assert!((north - 111.32).abs() < 0.1, "{north}");
        // The same longitude span is shorter, by cos(37.7).
        let east = distance_m((377_000_000, -1_224_000_000), (377_000_000, -1_223_990_000));
        assert!((east - 111.32 * 37.7f64.to_radians().cos()).abs() < 0.1, "{east}");
    }

    #[test]
    fn resampling_walks_the_polyline_at_a_fixed_spacing() {
        // 0.1 degrees of latitude: 11,132 m due north.
        let line = [(37_700_000, -122_400_000), (38_700_000, -122_400_000)];
        let walked = resample(&line, 1000.0);
        // Eleven interior samples plus both ends.
        assert_eq!(walked.len(), 13, "{walked:?}");
        assert_eq!(walked[0], line[0], "the first point is always kept");
        assert_eq!(walked[walked.len() - 1], line[1], "and so is the last");
        for pair in walked[..walked.len() - 1].windows(2) {
            let step = distance_m(pair[0], pair[1]);
            assert!((step - 1000.0).abs() < 1.0, "uneven step {step}");
        }
    }

    #[test]
    fn slicing_shares_its_boundary_vertex() {
        let line = [(0, 0), (0, 1_113_200), (0, 2_226_400)];
        let cum = cumulative(&line);
        let a = slice_between(&line, &cum, 0.0, 1000.0);
        let b = slice_between(&line, &cum, 1000.0, 2000.0);
        assert!(a.len() >= 2 && b.len() >= 2);
        assert_eq!(a[a.len() - 1], b[0], "adjacent slices meet exactly");
    }
}
