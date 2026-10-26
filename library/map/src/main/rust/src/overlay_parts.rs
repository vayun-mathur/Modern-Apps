//! Route tessellation, moved wholesale out of [`crate::overlay`].
//!
//! No logic changes: [`tessellate`] is re-exported from `overlay` so every
//! existing `crate::overlay::tessellate` path keeps working.
use crate::camera::{project, WorldPx};
use crate::overlay::{
    EXTENT, RouteMesh, RoutePlacement, RouteSegment, RouteSegmentRange, RouteStyle,
};
use crate::tess::stroke;

/// Tessellate a list of coloured runs into one [`RouteMesh`], or `None` when there is
/// nothing to draw.
///
/// Every run's points share one bounding square, so the whole route is one vertex/index
/// pair — the casing is then a single draw over the lot and each run's fill is a draw of
/// its own index slice (see [`RouteMesh::segments`]). A single-colour route (the car) is
/// just a one-run list and takes the same path.
///
/// `None` covers an empty list and a list every run of which is degenerate — empty, a
/// single point, or all-coincident points, none of which has a direction to stroke. A run
/// that is degenerate on its own is skipped rather than failing the whole route, so one
/// glitchy zero-length step does not blank a route being followed. A caller clearing the
/// route passes an empty slice and gets `None`, which is the same thing.
pub fn tessellate(segments: &[RouteSegment], style: RouteStyle) -> Option<RouteMesh> {
    // Longitudes unwrapped along the route (each point within 180 degrees of the one before,
    // runs continuing from the previous run's end), so a route crossing the antimeridian is
    // one short hop past +-180 rather than a streak back across the whole world. `project` is
    // linear in longitude, so an unwrapped point simply lands past the world edge; the draw
    // picks the world copy nearest the camera.
    let mut previous: Option<f64> = None;
    let unwrapped: Vec<Vec<(f64, f64)>> = segments
        .iter()
        .map(|segment| {
            segment
                .points
                .iter()
                .map(|&(lon, lat)| {
                    let lon = match previous {
                        Some(p) => p + (lon - p + 540.0).rem_euclid(360.0) - 180.0,
                        None => lon,
                    };
                    previous = Some(lon);
                    (lon, lat)
                })
                .collect()
        })
        .collect();
    // The bounding box spans every run's points, so all runs normalise into the same
    // square and their strokes line up. Zoom 0 is the reference the mesh is stored at:
    // any zoom would do, since the normalisation divides the scale out, and zero needs
    // no argument.
    let mut min = WorldPx {
        x: f64::MAX,
        y: f64::MAX,
    };
    let mut max = WorldPx {
        x: f64::MIN,
        y: f64::MIN,
    };
    let mut any = false;
    for points in &unwrapped {
        for &(lon, lat) in points {
            let p = project(lon, lat, 0.0);
            min.x = min.x.min(p.x);
            min.y = min.y.min(p.y);
            max.x = max.x.max(p.x);
            max.y = max.y.max(p.y);
            any = true;
        }
    }
    if !any {
        return None;
    }
    // Squared off, so one span serves both axes and the local coordinates stay in 0..1
    // the way a tile's do. A route running due north has zero width and would otherwise
    // divide by zero on x.
    let span = (max.x - min.x).max(max.y - min.y);
    if !span.is_finite() || span <= 0.0 {
        return None;
    }

    let mut vertices = Vec::new();
    let mut indices = Vec::new();
    let mut ranges = Vec::new();
    let mut coords: Vec<i32> = Vec::new();
    for (segment, points) in segments.iter().zip(&unwrapped) {
        if points.len() < 2 {
            continue;
        }
        coords.clear();
        coords.reserve(points.len() * 2);
        for &(lon, lat) in points {
            let p = project(lon, lat, 0.0);
            coords.push((((p.x - min.x) / span) * EXTENT as f64).round() as i32);
            coords.push((((p.y - min.y) / span) * EXTENT as f64).round() as i32);
        }
        let index_offset = indices.len() as u32;
        // Not `gapped`: the casing is a second draw of this same geometry at a wider push
        // constant, so one plain band centred on the run is all the geometry needed.
        // `stroke` bases its indices on the vertices already present, so appending run
        // after run to the shared buffers just works.
        stroke::stroke(&coords, EXTENT, false, &mut vertices, &mut indices);
        let index_count = indices.len() as u32 - index_offset;
        if index_count == 0 {
            continue;
        }
        ranges.push(RouteSegmentRange {
            color: segment.color,
            index_offset,
            index_count,
        });
    }
    if ranges.is_empty() {
        return None;
    }
    Some(RouteMesh {
        placement: RoutePlacement {
            origin: min,
            span,
            style,
        },
        vertices,
        indices,
        segments: ranges,
    })
}
