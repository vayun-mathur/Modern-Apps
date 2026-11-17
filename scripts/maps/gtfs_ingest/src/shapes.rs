//! Fitting GTFS `shapes.txt` geometry to a RAPTOR route's stop pattern, for the
//! v4 `SHAPE_COORDS` / `ROUTE_SHAPE_IDX` / `ROUTE_STOP_SHAPE` sections.
//!
//! A ride leg has to draw the path the vehicle actually takes, and the device
//! slices that path by *vertex index* for the boarded span. So a fitted shape is
//! a polyline plus one vertex index per pattern stop, and those indices must be
//! non-decreasing or the slice is meaningless.
//!
//! Two things make that work:
//!   * each stop's projection onto the shape is **inserted as a vertex**, so a
//!     boarded span begins and ends exactly at its stop rather than at whichever
//!     shape point happened to be nearest;
//!   * the shape is trimmed to `[first stop, last stop]`, dropping the deadhead
//!     prologue/epilogue no ride leg can reach.
//!
//! Every `shapes.txt` point in between is kept: the pack is the build's source of
//! truth for transit geometry, so it is stored at full resolution.
//!
//! `shape_dist_traveled` is used only to *order* — to pick which segment a stop
//! belongs to, which is what disambiguates a loop route that passes a stop
//! twice. Its units are feed-defined (km, mi, ft, ...) and are never read as
//! metres.

use crate::gtfs::Shape;

/// How far a stop may sit
/// How far a stop may sit from the shape before the fit is rejected. A genuine
/// mismatch (shape belonging to another pattern, swapped lat/lon) blows past
/// this; ordinary stop-to-kerb offsets do not.
const MAX_STOP_OFFSET_M: f64 = 150.0;

/// A shape fitted to one route's stop pattern.
pub struct FittedShape {
    /// The polyline to store, as `(lat_e7, lon_e7)`.
    pub points: Vec<(i32, i32)>,
    /// Vertex index in `points` for each pattern stop; non-decreasing.
    pub stop_vertices: Vec<u32>,
}

/// Nearest point on segment `a`-`b` to `p`, with the parameter forced to at
/// least `t_min`. Returns `(t, distance in metres)`. Local equirectangular
/// metres about the segment's own mean latitude, which is exact enough at
/// shape-segment scale.
pub fn project(p: (i32, i32), a: (i32, i32), b: (i32, i32), t_min: f64) -> (f64, f64) {
    let cos_lat = ((a.0 as f64 + b.0 as f64) * 0.5 * 1e-7).to_radians().cos();
    let m = |q: (i32, i32)| {
        (q.1 as f64 * 1e-7 * 111_320.0 * cos_lat, q.0 as f64 * 1e-7 * 111_320.0)
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
    let cos_lat = ((a.0 as f64 + b.0 as f64) * 0.5 * 1e-7).to_radians().cos();
    let dy = (b.0 as f64 - a.0 as f64) * 1e-7 * 111_320.0;
    let dx = (b.1 as f64 - a.1 as f64) * 1e-7 * 111_320.0 * cos_lat;
    (dx * dx + dy * dy).sqrt()
}

/// Walk `points` emitting a point every `step_m` metres along it.
///
/// The first and last points are always kept, so the walk covers the whole
/// polyline and a short one still yields both ends. The other half of
/// [`project`]: that measures across a polyline, this measures along one, and
/// together they are enough to ask whether two polylines are the same line.
pub fn resample(points: &[(i32, i32)], step_m: f64) -> Vec<(i32, i32)> {
    if points.len() < 2 || step_m.partial_cmp(&0.0) != Some(std::cmp::Ordering::Greater) {
        return points.to_vec();
    }
    let lerp = |a: i32, b: i32, t: f64| (a as f64 + (b as f64 - a as f64) * t).round() as i32;
    let mut out = vec![points[0]];
    // Distance already walked since the last emitted sample, carried across the
    // segment boundary so the spacing is along the polyline and not per segment.
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
    let last = points[points.len() - 1];
    if out[out.len() - 1] != last {
        out.push(last);
    }
    out
}

fn non_decreasing(v: &[f64]) -> bool {
    v.windows(2).all(|w| w[1] >= w[0])
}

/// Segment index whose `shape_dist_traveled` span contains `d`, searched from
/// `from` onwards so the result can never step backwards along the shape.
fn bracket(shape_dist: &[f64], d: f64, from: usize) -> usize {
    let last = shape_dist.len() - 2;
    let mut seg = from.min(last);
    while seg < last && shape_dist[seg + 1] < d {
        seg += 1;
    }
    seg
}

/// Fit `shape` to `stops` (a route's stop pattern, in order). `stop_dists` is
/// `shape_dist_traveled` from `stop_times.txt`, used only when the shape carries
/// it too. Returns `None` when the shape cannot be trusted for this pattern, in
/// which case the caller stores no shape and the device draws stop-to-stop.
pub fn fit(shape: &Shape, stops: &[(i32, i32)], stop_dists: Option<&[f64]>) -> Option<FittedShape> {
    let n = shape.lat_e7.len();
    if n < 2 || stops.len() < 2 {
        return None;
    }
    let orig: Vec<(i32, i32)> =
        (0..n).map(|i| (shape.lat_e7[i], shape.lon_e7[i])).collect();

    // Ordering key, only when both sides supply it and both are monotone.
    let ordering = match (shape.dist.as_ref(), stop_dists) {
        (Some(sd), Some(td))
            if sd.len() == n
                && td.len() == stops.len()
                && non_decreasing(sd)
                && non_decreasing(td) =>
        {
            Some((sd, td))
        }
        _ => None,
    };

    // Project each stop, never stepping backwards along the shape.
    let mut proj: Vec<(usize, f64)> = Vec::with_capacity(stops.len());
    let mut cur_seg = 0usize;
    let mut cur_t = 0.0f64;
    for (k, &stop) in stops.iter().enumerate() {
        let (seg, t, offset) = match ordering {
            Some((sd, td)) => {
                let seg = bracket(sd, td[k], cur_seg);
                let t_min = if seg == cur_seg { cur_t } else { 0.0 };
                let (t, d) = project(stop, orig[seg], orig[seg + 1], t_min);
                (seg, t, d)
            }
            None => {
                let mut best: Option<(usize, f64, f64)> = None;
                for seg in cur_seg..(n - 1) {
                    let t_min = if seg == cur_seg { cur_t } else { 0.0 };
                    let (t, d) = project(stop, orig[seg], orig[seg + 1], t_min);
                    if best.is_none_or(|(_, _, bd)| d < bd) {
                        best = Some((seg, t, d));
                    }
                }
                best?
            }
        };
        if offset > MAX_STOP_OFFSET_M {
            return None;
        }
        cur_seg = seg;
        cur_t = t;
        proj.push((seg, t));
    }

    // Insert each projection as its own vertex, so the pinned indices below are
    // exactly the stops rather than the nearest shape point to them.
    let lerp = |a: i32, b: i32, t: f64| (a as f64 + (b as f64 - a as f64) * t).round() as i32;
    let mut pts: Vec<(i32, i32)> = Vec::with_capacity(n + stops.len());
    let mut pinned: Vec<usize> = Vec::with_capacity(stops.len());
    let mut k = 0usize;
    for i in 0..n {
        pts.push(orig[i]);
        while k < proj.len() && proj[k].0 == i {
            let (seg, t) = proj[k];
            let (a, b) = (orig[seg], orig[seg + 1]);
            pts.push((lerp(a.0, b.0, t), lerp(a.1, b.1, t)));
            pinned.push(pts.len() - 1);
            k += 1;
        }
    }
    if pinned.len() != stops.len() {
        return None;
    }

    // Trim to the boarded extent, keeping every point between pinned vertices.
    let mut keep: Vec<usize> = vec![pinned[0]];
    let mut stop_vertices: Vec<u32> = vec![0];
    for w in pinned.windows(2) {
        let (a, b) = (w[0], w[1]);
        if b > a {
            keep.extend(a + 1..=b);
        }
        stop_vertices.push((keep.len() - 1) as u32);
    }
    if keep.len() < 2 {
        return None;
    }

    Some(FittedShape {
        points: keep.into_iter().map(|i| pts[i]).collect(),
        stop_vertices,
    })
}

/// Encode a fitted polyline for SHAPE_COORDS: `u32 point_count`, then per point
/// a zigzag varint latitude delta and longitude delta (1e-7 degrees, cumulative
/// from zero). Zigzag varints rather than the `i16` deltas `graph.rs` uses for
/// road geometry: consecutive `shapes.txt` points are routinely far enough apart
/// to overflow an `i16` at 1e-7 degrees.
pub fn encode(points: &[(i32, i32)]) -> Vec<u8> {
    let mut out = Vec::with_capacity(4 + points.len() * 6);
    out.extend_from_slice(&(points.len() as u32).to_le_bytes());
    let (mut plat, mut plon) = (0i64, 0i64);
    for &(lat, lon) in points {
        write_zigzag(&mut out, lat as i64 - plat);
        write_zigzag(&mut out, lon as i64 - plon);
        plat = lat as i64;
        plon = lon as i64;
    }
    out
}

fn write_zigzag(v: &mut Vec<u8>, x: i64) {
    let mut u = ((x << 1) ^ (x >> 63)) as u64;
    loop {
        let b = (u & 0x7f) as u8;
        u >>= 7;
        if u != 0 {
            v.push(b | 0x80);
        } else {
            v.push(b);
            break;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn shape(points: &[(f64, f64)], dist: Option<&[f64]>) -> Shape {
        Shape {
            lat_e7: points.iter().map(|&(la, _)| (la * 1e7) as i32).collect(),
            lon_e7: points.iter().map(|&(_, lo)| (lo * 1e7) as i32).collect(),
            dist: dist.map(|d| d.to_vec()),
        }
    }
    fn e7(lat: f64, lon: f64) -> (i32, i32) {
        ((lat * 1e7) as i32, (lon * 1e7) as i32)
    }

    fn decode(bytes: &[u8]) -> Vec<(i32, i32)> {
        let n = u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]) as usize;
        let mut pos = 4usize;
        let mut read = || -> i64 {
            let mut result = 0u64;
            let mut shift = 0u32;
            loop {
                let b = bytes[pos];
                pos += 1;
                result |= ((b & 0x7f) as u64) << shift;
                if b & 0x80 == 0 {
                    break;
                }
                shift += 7;
            }
            ((result >> 1) as i64) ^ -((result & 1) as i64)
        };
        let (mut lat, mut lon) = (0i64, 0i64);
        (0..n)
            .map(|_| {
                lat += read();
                lon += read();
                (lat as i32, lon as i32)
            })
            .collect()
    }

    #[test]
    fn fits_stops_by_nearest_point_when_no_dist_is_given() {
        // A shape detouring east between three collinear stops.
        let s = shape(
            &[
                (37.700, -122.400),
                (37.705, -122.390),
                (37.710, -122.400),
                (37.715, -122.390),
                (37.720, -122.400),
            ],
            None,
        );
        let stops = [e7(37.700, -122.400), e7(37.710, -122.400), e7(37.720, -122.400)];
        let f = fit(&s, &stops, None).expect("fits");
        assert_eq!(f.stop_vertices.len(), 3);
        assert!(
            f.stop_vertices.windows(2).all(|w| w[1] >= w[0]),
            "vertex indices must be non-decreasing, got {:?}",
            f.stop_vertices
        );
        assert_eq!(f.stop_vertices[0], 0, "trimmed to the first stop");
        assert_eq!(
            *f.stop_vertices.last().unwrap() as usize,
            f.points.len() - 1,
            "trimmed to the last stop"
        );
        // Each stop's vertex is the stop itself, so the drawn ride starts on it.
        for (k, &v) in f.stop_vertices.iter().enumerate() {
            assert_eq!(f.points[v as usize], stops[k]);
        }
        // The detour is kept.
        assert!(f.points.iter().any(|&(_, lon)| lon > -1_223_950_000));
    }

    #[test]
    fn shape_dist_traveled_disambiguates_a_stop_visited_twice() {
        // An out-and-back: the middle stop's coordinates match two passes, and
        // only the distance key can say which one the pattern means.
        let s = shape(
            &[
                (37.700, -122.400),
                (37.710, -122.400),
                (37.720, -122.400),
                (37.710, -122.400),
                (37.700, -122.400),
            ],
            // Kilometres, deliberately not metres.
            Some(&[0.0, 1.1, 2.2, 3.3, 4.4]),
        );
        let stops = [e7(37.700, -122.400), e7(37.710, -122.400), e7(37.700, -122.400)];
        let f = fit(&s, &stops, Some(&[0.0, 3.3, 4.4])).expect("fits");
        // The second stop is the *return* pass, so its vertex must sit past the
        // northern turn rather than on the outbound leg.
        let turn = f
            .points
            .iter()
            .position(|&(lat, _)| lat >= 377_195_000)
            .expect("the turn is kept");
        assert!(
            f.stop_vertices[1] as usize > turn,
            "stop 1 landed on the outbound pass: {:?} vs turn {turn}",
            f.stop_vertices
        );
    }

    #[test]
    fn rejects_a_shape_belonging_to_another_pattern() {
        let s = shape(&[(40.700, -74.000), (40.710, -74.000)], None);
        let stops = [e7(37.700, -122.400), e7(37.710, -122.400)];
        assert!(fit(&s, &stops, None).is_none(), "a shape 4000 km away must be dropped");
    }

    #[test]
    fn rejects_a_degenerate_shape() {
        let s = shape(&[(37.700, -122.400)], None);
        let stops = [e7(37.700, -122.400), e7(37.710, -122.400)];
        assert!(fit(&s, &stops, None).is_none());
    }

    #[test]
    fn every_shape_point_between_the_stops_is_kept() {
        let mut pts = Vec::new();
        for i in 0..101 {
            pts.push((37.700 + i as f64 * 0.0001, -122.400));
        }
        let s = shape(&pts, None);
        let stops = [e7(37.700, -122.400), e7(37.7100, -122.400)];
        let f = fit(&s, &stops, None).expect("fits");
        assert_eq!(f.points.len(), 101, "full resolution: even a straight run keeps its points");
        assert_eq!(f.stop_vertices, vec![0, 100]);
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

    /// The spacing is along the whole polyline, not restarted per segment: a
    /// chain of short segments must not emit a sample at every joint.
    #[test]
    fn resampling_carries_the_spacing_across_segments() {
        let mut pts = Vec::new();
        for i in 0..101 {
            pts.push((37_700_000 + i * 1_000, -122_400_000));
        }
        // 100 segments of ~11 m each, so ~1113 m in total.
        let walked = resample(&pts, 100.0);
        assert!(walked.len() >= 12 && walked.len() <= 13, "{}", walked.len());
    }

    #[test]
    fn resampling_leaves_something_it_cannot_walk_alone() {
        assert_eq!(resample(&[], 10.0), Vec::new());
        let one = [(37_700_000, -122_400_000)];
        assert_eq!(resample(&one, 10.0), one.to_vec());
        // A step of zero would loop forever rather than divide by it.
        let two = [(37_700_000, -122_400_000), (37_700_100, -122_400_000)];
        assert_eq!(resample(&two, 0.0), two.to_vec());
        // A duplicated point is a zero-length segment, not a division by zero.
        let doubled = [(37_700_000, -122_400_000), (37_700_000, -122_400_000)];
        assert_eq!(resample(&doubled, 10.0), vec![doubled[0]]);
    }

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
    fn encoding_survives_deltas_that_would_overflow_an_i16() {
        // ~40 km apart: 3.6e6 in 1e-7 degrees, far past i16::MAX.
        let points =
            vec![(377_000_000, -1_224_000_000), (380_600_000, -1_224_000_000), (377_000_000, -1_219_000_000)];
        assert_eq!(decode(&encode(&points)), points);
    }

    #[test]
    fn encoding_roundtrips_a_fitted_shape() {
        let s = shape(
            &[(37.700, -122.400), (37.705, -122.390), (37.710, -122.400)],
            None,
        );
        let stops = [e7(37.700, -122.400), e7(37.710, -122.400)];
        let f = fit(&s, &stops, None).expect("fits");
        assert_eq!(decode(&encode(&f.points)), f.points);
    }
}
