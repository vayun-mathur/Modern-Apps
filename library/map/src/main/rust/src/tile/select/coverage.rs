use crate::camera::{Camera, TILE_SIZE};

use super::tile_id::TileId;

/// How far past the camera centre the tilt trapezoid may reach, in viewport heights.
///
/// Under tilt the top of the screen recedes toward the horizon — at 65° the far edge is many
/// viewport-heights away — and an uncapped trapezoid enumerates an unbounded tile strip that is
/// fogged to the background colour anyway (see `FAR_FADE`). Capping the far reach keeps the
/// visible set proportional to the viewport instead of the pitch: ground past the cap is still
/// drawn (the far tiles cover it, faded) but no *extra* tiles are fetched for it. In tile units
/// the cap bites only at high pitch + low zoom, exactly where the strip would explode.
const FAR_REACH_VIEWPORTS: f64 = 6.0;

/// The world-px axis-aligned box the viewport covers on the ground, accounting for **both**
/// bearing and tilt.
///
/// At pitch 0 this is exactly [`Camera::viewport_bounds`] — the bounding box of the rotated
/// viewport. Under tilt the top of the screen recedes toward the horizon, so the ground the
/// viewport actually covers is a trapezoid reaching far past that box; a selection derived from
/// the untilted box leaves the top of the display blank exactly where the distance the driver is
/// looking toward is. So the four screen corners are unprojected through the tilt-aware ray/plane
/// ([`Camera::screen_to_world`]) and the box is grown to hold them. The [`PITCH_MAX_DEG`] cap keeps
/// every corner below the horizon, so each resolves and the trapezoid stays finite.
fn coverage(camera: &Camera) -> (crate::camera::WorldPx, crate::camera::WorldPx) {
    let (mut min, mut max) = camera.viewport_bounds();
    if camera.pitch_deg == 0.0 {
        return (min, max);
    }
    let w = camera.width_dp as f64;
    let h = camera.height_dp as f64;
    // The top edge recedes furthest, so its two corners are the far edge of the trapezoid; the
    // bottom corners are the near edge. Their AABB bounds the whole trapezoid because a screen line
    // maps to a straight line on the ground plane.
    for &(sx, sy) in &[(0.0, 0.0), (w, 0.0), (w, h), (0.0, h)] {
        if let Some(p) = camera.screen_to_world(sx, sy) {
            min.x = min.x.min(p.x);
            min.y = min.y.min(p.y);
            max.x = max.x.max(p.x);
            max.y = max.y.max(p.y);
        }
    }
    // Far-distance cap: clamp the box to a radius around the camera centre so a near-horizon
    // view at high pitch enumerates a bounded strip instead of tiles to the horizon. The
    // `PITCH_MAX_DEG` cap keeps every corner finite; this keeps the *count* bounded.
    let centre = crate::camera::project(camera.center_lon, camera.center_lat, camera.zoom);
    let reach = FAR_REACH_VIEWPORTS * h.max(w);
    min.x = min.x.max(centre.x - reach);
    min.y = min.y.max(centre.y - reach);
    max.x = max.x.min(centre.x + reach);
    max.y = max.y.min(centre.y + reach);
    (min, max)
}

/// The tiles covering `camera`'s viewport, clamped to the archive's zoom range.
///
/// Coverage is the bounding box of the viewport **as the camera actually orients it** — bearing
/// *and* tilt — so a rotation pulls in the extra ring of tiles the rotated corners reach and a tilt
/// pulls in the trapezoid of ground the receding top of the screen covers. At bearing zero and
/// pitch zero the box is the viewport and this is what it always was.
///
/// On the globe ([`crate::camera::globe_active`]) coverage is the disc the viewport sees:
/// every tile whose Mercator rect intersects the visible hemisphere cap. Tiles fully
/// behind the limb are skipped — they would bend to the far side and be depth-culled
/// anyway, so fetching them is pure waste.
pub fn visible(camera: &Camera, min_zoom: u8, max_zoom: u8) -> Vec<TileId> {
    if camera.width_dp <= 0.0 || camera.height_dp <= 0.0 {
        return Vec::new();
    }
    if crate::camera::globe_active(camera) {
        return globe_visible(camera, min_zoom, max_zoom);
    }
    let z = (camera.zoom.floor().max(0.0) as u32).clamp(min_zoom as u32, max_zoom as u32) as u8;
    let n = 1i64 << z;
    let span = camera.tile_span_dp(z);
    let (min, max) = coverage(camera);

    let min_tx = (min.x / span).floor() as i64;
    let max_tx = (max.x / span).floor() as i64;
    let min_ty = (min.y / span).floor() as i64;
    let max_ty = (max.y / span).floor() as i64;

    let mut out = Vec::new();
    for ty in min_ty..=max_ty {
        if ty < 0 || ty >= n {
            continue;
        }
        for tx in min_tx..=max_tx {
            if tx < 0 || tx >= n {
                continue;
            }
            out.push(TileId {
                z,
                x: tx as u32,
                y: ty as u32,
            });
        }
    }
    out
}

/// A rough bound on how many tiles a viewport can want, for capacity hints.
///
/// Measured off the same tilt-and-rotation coverage box [`visible`] selects from, so it stays a
/// bound rather than becoming a lie the moment the camera turns or tilts.
pub fn bound(camera: &Camera) -> usize {
    let (min, max) = coverage(camera);
    let across = (max.x - min.x) / TILE_SIZE + 2.0;
    let down = (max.y - min.y) / TILE_SIZE + 2.0;
    (across * down).ceil() as usize
}

/// Globe coverage: every tile at the (clamped) zoom whose Mercator rect touches
/// the visible hemisphere.
///
/// The hemisphere is the set of lon/lat within 90° (great-circle) of the camera
/// centre; a tile is kept when any of its rect — corners plus edge midpoints
/// (a tile can straddle the limb with all four corners behind it) — is on the
/// near side. The zoom is the same floor-then-clamp the flat path uses, so the
/// globe and the flat map agree on tile size at the detail threshold.
fn globe_visible(camera: &Camera, min_zoom: u8, max_zoom: u8) -> Vec<TileId> {
    let z = (camera.zoom.floor().max(0.0) as u32).clamp(min_zoom as u32, max_zoom as u32) as u8;
    let n = 1i64 << z;
    // Hemisphere cap in lon/lat: sample the tile rect's corners + edge midpoints
    // and keep the tile when any sample is within 90° of the centre. Lon/lat come
    // from the tile grid directly (not via a world-size round trip), so this is
    // exact at any camera zoom.
    let lon_at = |tx: i64| tx as f64 / n as f64 * 360.0 - 180.0;
    let lat_at = |ty: i64| {
        let n_pi = std::f64::consts::PI - 2.0 * std::f64::consts::PI * ty as f64 / n as f64;
        n_pi.sinh().atan() * 180.0 / std::f64::consts::PI
    };
    let mut out = Vec::new();
    for ty in 0..n {
        let lat_n = lat_at(ty);
        let lat_s = lat_at(ty + 1);
        let mid_lat = (lat_n + lat_s) / 2.0;
        for tx in 0..n {
            let lon_w = lon_at(tx);
            let lon_e = lon_at(tx + 1);
            let mid_lon = (lon_w + lon_e) / 2.0;
            let near = [
                (lon_w, lat_n),
                (mid_lon, lat_n),
                (lon_e, lat_n),
                (lon_e, mid_lat),
                (lon_e, lat_s),
                (mid_lon, lat_s),
                (lon_w, lat_s),
                (lon_w, mid_lat),
                (mid_lon, mid_lat),
            ]
            .iter()
            .any(|&(lon, lat)| {
                let (_, _, z) = crate::camera::globe_point(camera.center_lon, camera.center_lat, lon, lat);
                z >= 0.0
            });
            if near {
                out.push(TileId {
                    z,
                    x: tx as u32,
                    y: ty as u32,
                });
            }
        }
    }
    out
}

/// Sort `tiles` centre-first, in place: nearest the set's own mean (the
/// viewport centre for a coverage box) first, so a cold pan fills its middle
/// before its edges. Same ordering the fetch loop always used; sorting the
/// caller's Vec instead of a copy saves one allocation per frame.
pub fn sort_center_first(tiles: &mut [TileId]) {
    if tiles.len() < 2 {
        return;
    }
    let (sx, sy) = tiles.iter().fold((0i64, 0i64), |(sx, sy), t| (sx + t.x as i64, sy + t.y as i64));
    let n = tiles.len() as i64;
    let (cx, cy) = (sx / n, sy / n);
    tiles.sort_by_cached_key(|t| {
        let dx = t.x as i64 - cx;
        let dy = t.y as i64 - cy;
        dx * dx + dy * dy
    });
}

#[cfg(test)]
mod center_first_tests {
    use super::sort_center_first;
    use super::TileId;

    #[test]
    fn sorts_centre_outward_without_changing_the_set() {
        let mut tiles: Vec<TileId> = (0..3)
            .flat_map(|dy| (0..3).map(move |dx| TileId { z: 4, x: 9 + dx, y: 9 + dy }))
            .collect();
        let mut before = tiles.clone();
        sort_center_first(&mut tiles);
        assert_eq!((tiles[0].x, tiles[0].y), (10, 10));
        before.sort_by_key(|t| t.key());
        let mut after = tiles.clone();
        after.sort_by_key(|t| t.key());
        assert_eq!(before, after);
    }
}
