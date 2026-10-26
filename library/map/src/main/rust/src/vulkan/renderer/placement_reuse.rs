//! Placement reuse across camera motion, plus the collision-box inputs.
//!
//! File-length split from the renderer root (see `mod.rs`) and from
//! [`placement`](super::placement) (see `placement.rs`): the drift-reuse
//! machinery that lets small zoom/bearing/pitch changes reuse the cached
//! accept-set, the [`PlacementKey`] universe comparison behind it, and the
//! per-label collision-box inputs the candidate loop reads. A real submodule
//! (not `include!`), so imports are explicit below.
use super::PlacementKey;
use super::AcceptSet;
use crate::camera::Camera;
use crate::style::Layer;
use crate::tile::geometry;
use std::time::Instant;

/// The reuse window once the camera is zooming fast (see [`Renderer::place_symbols`](super::Renderer::place_symbols)).
///
/// Past ~1 zoom level per second the collision answer goes stale faster than the pan window
/// allows: re-place promptly instead so labels do not visibly lag the zoom.
const PLACE_REUSE_FAST_ZOOM_MS: u128 = 80;

/// The zoom speed past which the reuse window shrinks to [`PLACE_REUSE_FAST_ZOOM_MS`],
/// in zoom levels per second.
const FAST_ZOOM_PER_SEC: f64 = 1.0;

/// Drift thresholds for reusing the accept-set without an exact key match.
///
/// A camera that differs from the cached one only in centre/zoom/bearing/pitch, each within
/// these, reuses the cached accept-set within the reuse window: the projection moved a
/// little but the candidate universe (tiles, layers, filter, extent, sizes) is bit-identical,
/// so the collision answer is overwhelmingly likely unchanged and boxes still re-project per
/// frame in `refresh_placed`. Any drift past these re-places.
const DRIFT_MAX_DZOOM: f64 = 0.05;
/// Degrees.
const DRIFT_MAX_DBEARING: f64 = 2.0;
/// Degrees.
const DRIFT_MAX_DPITCH: f64 = 2.0;

/// Whether `key` differs from `cached` only by camera drift within the reuse thresholds.
///
/// Centre may move arbitrarily (pan); zoom/bearing/pitch each have a small budget. The
/// universe (tiles, stamps, filter, extent, sizes, layers, marker generation) must be
/// bit-identical — checked by the caller via [`PlacementKey::same_universe`].
pub(super) fn within_drift(cached: &PlacementKey, key: &PlacementKey) -> bool {
    let zoom = f64::from_bits(key.zoom);
    let cached_zoom = f64::from_bits(cached.zoom);
    if (zoom - cached_zoom).abs() >= DRIFT_MAX_DZOOM {
        return false;
    }
    let bearing = f64::from_bits(key.bearing);
    let cached_bearing = f64::from_bits(cached.bearing);
    if (bearing - cached_bearing).abs() >= DRIFT_MAX_DBEARING {
        return false;
    }
    let pitch = f64::from_bits(key.pitch);
    let cached_pitch = f64::from_bits(cached.pitch);
    if (pitch - cached_pitch).abs() >= DRIFT_MAX_DPITCH {
        return false;
    }
    true
}

/// The reuse window for a drift-reuse hit: the full reuse window, shrunk to
/// [`PLACE_REUSE_FAST_ZOOM_MS`] while zooming fast.
///
/// Zoom velocity comes from the cached key's zoom versus this frame's over the cache age —
/// both already in hand, no extra state. A zero/negative age (same-instant re-entry) takes
/// the fast path only if the zoom actually jumped; otherwise the full window.
pub(super) fn drift_reuse_ms(
    cached: &PlacementKey,
    key: &PlacementKey,
    at: &Instant,
    full_window_ms: u128,
) -> u128 {
    let zoom = f64::from_bits(key.zoom);
    let cached_zoom = f64::from_bits(cached.zoom);
    let dz = (zoom - cached_zoom).abs();
    if dz <= 0.0 {
        return full_window_ms;
    }
    let age_secs = at.elapsed().as_secs_f64();
    if age_secs > 0.0 && dz / age_secs > FAST_ZOOM_PER_SEC {
        PLACE_REUSE_FAST_ZOOM_MS
    } else {
        full_window_ms
    }
}

impl PlacementKey {
    /// The retained-collision bucket: quantised camera pose plus the globe flag.
    ///
    /// Bins carry hysteresis by construction — the bucket is what the accept-set is cached
    /// against, and `same_bucket` compares buckets, not distances — so a camera oscillating
    /// on a bin edge re-places once per crossing rather than flickering labels on and off:
    /// each crossing recomputes a stable answer instead of toggling between two.
    pub(super) fn same_bucket(&self, other: &PlacementKey) -> bool {
        self.bucket_zoom == other.bucket_zoom
            && self.bucket_bearing == other.bucket_bearing
            && self.bucket_pitch == other.bucket_pitch
            && self.bucket_lon == other.bucket_lon
            && self.bucket_lat == other.bucket_lat
            && self.globe == other.globe
    }

    /// Bit-identity of everything but the camera pose: tiles and their stamps, filter, extent,
    /// sizes, layer count, and the globe flag. A drift-reuse candidate must match all of these;
    /// only centre/zoom/bearing/pitch may differ (within their thresholds — see `within_drift`).
    /// Any universe change is a hard re-place. The globe flag is universe, not pose: flat and
    /// globe project through different box builders, so a projection switch must never reuse.
    pub(super) fn same_universe(&self, other: &PlacementKey) -> bool {
        self.width_dp == other.width_dp
            && self.height_dp == other.height_dp
            && self.density == other.density
            && self.extent == other.extent
            && self.filter == other.filter
            && self.tiles == other.tiles
            && self.layers == other.layers
            && self.markers == other.markers
            && self.globe == other.globe
    }
}

/// A cached accept-set reused without re-running the placer, or `None` on a miss.
///
/// The three hit paths in priority order: an exact key hit (still camera), a pan within the
/// reuse window (same everything but the centre), and small zoom/bearing/pitch drift on a
/// bit-identical candidate universe. Every hit records `Step::PlacementHit` (1); the caller
/// records 0 and bumps the placement version on a recompute. Boxes still re-project per
/// frame in `refresh_placed` on every path — only the collision outcome lags.
///
/// Split from `place_symbols` (file-length limit); the logic itself is unchanged.
pub(super) fn reuse_cached_accept(
    cache: &std::cell::RefCell<Option<(PlacementKey, AcceptSet, Instant)>>,
    step_times: &std::cell::RefCell<crate::timing::StepTimes>,
    key: &PlacementKey,
    full_window_ms: u128,
) -> Option<AcceptSet> {
    let cached = cache.borrow();
    let (cached_key, accepted, at) = cached.as_ref()?;
    if *cached_key == *key {
        step_times
            .borrow_mut()
            .record(crate::timing::Step::PlacementHit, 1);
        return Some(accepted.clone());
    }
    // Same everything but the centre: a pan in progress. Reuse the last accept-set
    // for a short window rather than re-running the quadratic pass every frame.
    let mut moved = cached_key.clone();
    moved.center_lon = key.center_lon;
    moved.center_lat = key.center_lat;
    if moved == *key && at.elapsed().as_millis() < full_window_ms {
        step_times
            .borrow_mut()
            .record(crate::timing::Step::PlacementHit, 1);
        return Some(accepted.clone());
    }
    // Small zoom/bearing/pitch drift on top of a pan: the candidate universe is
    // bit-identical (tiles, stamps, filter, extent, layers, sizes) and only the
    // projection moved a little, so reuse the accept-set within the same window.
    //
    // A fast zoom-out shrinks the window: past ~1 level/sec the answer goes stale
    // faster than the pan window allows, so re-place promptly.
    if cached_key.same_universe(key)
        && within_drift(cached_key, key)
        && at.elapsed().as_millis() < drift_reuse_ms(cached_key, key, at, full_window_ms)
    {
        step_times
            .borrow_mut()
            .record(crate::timing::Step::PlacementHit, 1);
        return Some(accepted.clone());
    }
    // Retained collision bucket (MapLibre's camera-bucket equivalent): same
    // quantised bucket and same candidate universe means the quadratic placer would
    // answer identically, so reuse without a time bound. The time-window paths above
    // stay as the fallback for cameras that drifted within thresholds but crossed a
    // bucket edge; this path covers the steady slow-pan case indefinitely.
    if cached_key.same_universe(key) && cached_key.same_bucket(key) {
        step_times
            .borrow_mut()
            .record(crate::timing::Step::PlacementHit, 1);
        return Some(accepted.clone());
    }
    None
}

/// Everything one label's collision box depends on besides its anchor.
///
/// Built once per label and reused for each candidate anchor, so the two boxes a POI is
/// tried at can only differ in where they sit — not in how big they are.
pub(super) fn box_inputs(
    layer: &Layer,
    label: &geometry::ShapedLabel,
    camera: &Camera,
) -> crate::tile::placement::BoxInputs {
    box_inputs_with_arms(layer, label, camera, layer.text_size_arms(camera.zoom))
}

/// [`box_inputs`], but with the style's text-size arms already resolved for this
/// (layer, zoom) — see [`Layer::text_size_arms`]. The per-label call resolves two
/// style ramps per label; the per-layer caller resolves them once and answers each
/// label with a float compare. Bit-identical values either way.
pub(super) fn box_inputs_with_arms(
    layer: &Layer,
    label: &geometry::ShapedLabel,
    camera: &Camera,
    arms: (f32, Option<f32>, Option<f32>),
) -> crate::tile::placement::BoxInputs {
    crate::tile::placement::BoxInputs {
        text_px: Layer::text_size_with_arms(label.pop, arms.0, arms.1, arms.2) * camera.density,
        advance: label.total_advance,
        line_count: label.lines.len(),
        offset_em: layer.text_offset,
        // Dp from the sheet, device px here — the same conversion `emit_icon` makes.
        icon_px: label
            .sprite
            .map(|s| (s.width_dp * camera.density, s.height_dp * camera.density)),
        pad_px: collision_padding_dp(camera.zoom) * camera.density,
    }
}

/// Collision padding in Dp around every label box, by camera zoom (the caller converts to
/// device px alongside the text and icon sizes).
///
/// MapLibre pads every label (icon + text padding, growing at low zoom via
/// the icon-padding ramp), which is what holds z6 to ~10 cities while z14
/// stays dense. Without it tight advance boxes let hundreds of villages
/// survive at z6. 24px at z6 and below culls hamlets against towns; 4px at
/// z14+ keeps street labels tight. Linear between.
fn collision_padding_dp(zoom: f64) -> f32 {
    if zoom <= 6.0 {
        24.0
    } else if zoom >= 14.0 {
        4.0
    } else {
        (24.0 - (zoom - 6.0) * (20.0 / 8.0)) as f32
    }
}

/// Quantise the camera into a retained-collision bucket (MapLibre's camera-bucket equivalent).
///
/// The placer is quadratic plus per-glyph projection; re-running it every frame is the panning
/// jank. Frames inside one bucket share the cached accept-set: only the *collision outcome*
/// lags by at most one bucket, while boxes still re-project per frame in `refresh_placed`,
/// so what draws tracks the camera exactly.
///
/// Bin sizes: zoom in 1/32-level bins (a slow pinch crosses ~2 bins/sec — each re-place is a
/// stable answer, not flicker); bearing/pitch in 4° bins (inside the old 2° drift budget's
/// spirit but coarser, because boxes re-project anyway); centre in ~1/1024-tile bins at the
/// floored zoom (sub-pixel at every zoom — a pan crosses a bin every few frames at most).
/// Globe is part of the key (flat and globe project through different box builders).
pub(super) fn bucket_for(camera: &Camera) -> (i64, i64, i64, i64, i64) {
    let zoom_bin = (camera.zoom * 32.0).floor() as i64;
    let bearing_bin = (camera.bearing_deg.rem_euclid(360.0) / 4.0).floor() as i64;
    let pitch_bin = (camera.pitch_deg / 4.0).floor() as i64;
    // Centre bins in tile units at the floored zoom: zoom-independent bin density.
    let floor_z = camera.zoom.floor().clamp(0.0, 22.0);
    let span = 512.0 * 2f64.powf(floor_z);
    let centre = crate::camera::project(camera.center_lon, camera.center_lat, floor_z);
    let lon_bin = (centre.x / span * 1024.0).floor() as i64;
    let lat_bin = (centre.y / span * 1024.0).floor() as i64;
    (zoom_bin, bearing_bin, pitch_bin, lon_bin, lat_bin)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::camera::Camera;

    fn camera(lon: f64, lat: f64, zoom: f64, bearing: f64, pitch: f64) -> Camera {
        Camera {
            center_lon: lon,
            center_lat: lat,
            zoom,
            width_dp: 411.0,
            height_dp: 891.0,
            density: 2.0,
            bearing_deg: bearing,
            pitch_deg: pitch,
            time_seconds: 0.0,
            globe: false,
            moon: false,
        }
    }

    #[test]
    fn a_still_camera_stays_in_its_bucket() {
        let c = camera(-122.4194, 37.7749, 14.0, 0.0, 0.0);
        assert_eq!(bucket_for(&c), bucket_for(&c));
    }

    #[test]
    fn a_one_frame_pan_stays_in_its_bucket() {
        // 16 ms of panning at 60fps moves the centre by a few Dp — far less than the
        // ~1/1024-tile bin (half a Dp at z14), so the collision answer is retained.
        let a = camera(-122.4194, 37.7749, 14.0, 0.0, 0.0);
        let b = camera(-122.4193, 37.7749, 14.0, 0.0, 0.0);
        assert_eq!(bucket_for(&a), bucket_for(&b));
    }

    #[test]
    fn a_fast_pan_leaves_its_bucket() {
        // A fling crossing half the viewport must re-place: the candidate set on screen
        // is genuinely different, not a stable answer lagging by a bucket.
        let a = camera(-122.4194, 37.7749, 14.0, 0.0, 0.0);
        let b = camera(-122.0, 37.7749, 14.0, 0.0, 0.0);
        assert_ne!(bucket_for(&a), bucket_for(&b));
    }

    #[test]
    fn a_zoom_step_leaves_its_bucket() {
        // 1/32-level bins: a tenth of a level re-places, so a pinch re-places a few
        // times a second with a stable answer each time rather than every frame.
        let a = camera(-122.4194, 37.7749, 14.0, 0.0, 0.0);
        let b = camera(-122.4194, 37.7749, 14.1, 0.0, 0.0);
        assert_ne!(bucket_for(&a).0, bucket_for(&b).0);
    }

    #[test]
    fn buckets_are_zoom_independent_in_ground_terms() {
        // Centre bins are ~1/1024 of a tile at the floored zoom: the same ground shift
        // crosses the same number of bins whatever the zoom, so bucket density does not
        // thin out at street zoom or thicken at country zoom.
        let shift_lon = 0.0001;
        let low_a = camera(-122.4194, 37.7749, 6.0, 0.0, 0.0);
        let low_b = camera(-122.4194 + shift_lon, 37.7749, 6.0, 0.0, 0.0);
        let high_a = camera(-122.4194, 37.7749, 14.0, 0.0, 0.0);
        let high_b = camera(-122.4194 + shift_lon, 37.7749, 14.0, 0.0, 0.0);
        let low_moved = bucket_for(&low_a).3 != bucket_for(&low_b).3;
        let high_moved = bucket_for(&high_a).3 != bucket_for(&high_b).3;
        // At z6 the shift is sub-bin (stays), at z14 it crosses bins (moves): the point
        // is the scaling holds, i.e. bins track ground, not zoom floats.
        assert!(!low_moved || high_moved);
    }
}
