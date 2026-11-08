//! Distance fog for tilted views: haze far tiles into the land.
//!
//! Split out of `mod.rs` (which owns the colour helpers this builds on) to keep every
//! renderer file under the 500-line lint. Pure functions over the camera + tile id —
//! no GPU state, trivially testable off-device.

use crate::camera::Camera;
use crate::style::{Layer, Palette};

/// The tilt distances (in viewport heights from the camera centre) the distance fog ramps over.
///
/// At pitch 0 every drawn tile is within ~1 viewport-height, so nothing ever fogs. Tilted at
/// 65° the far trapezoid reaches the 6-viewport coverage cap in `select::coverage`; the ramp
/// ends there, so the farthest drawn tiles arrive already land-coloured and the ground
/// dissolves into haze instead of ending at a hard edge. Starts past the mid-field so the
/// readable map never hazes — only the far strip does.
const FOG_NEAR_VIEWPORTS: f64 = 2.5;
const FOG_FAR_VIEWPORTS: f64 = 6.0;

/// Distance-fog mix for one tile's draws: 0 near, rising to 1 at the far coverage cap.
///
/// Measured tile-centre to camera-centre in viewport heights, so it is zoom-independent: a
/// z14 tile at 65° and a z10 tile at 65° fog by where they sit on screen, not by their span.
/// Zero at pitch 0 (the `pitch_deg == 0.0` short-circuit keeps the flat map byte-identical)
/// and zero for anything inside the near ramp.
pub(crate) fn fog_factor(camera: &Camera, z: u8, x: u32, y: u32) -> f32 {
    if camera.pitch_deg == 0.0 {
        return 0.0;
    }
    let h = camera.height_dp.max(1.0) as f64;
    let span = camera.tile_span_dp(z);
    let centre = crate::camera::project(camera.center_lon, camera.center_lat, camera.zoom);
    let dx = (f64::from(x) + 0.5) * span - centre.x;
    let dy = (f64::from(y) + 0.5) * span - centre.y;
    ((dx.hypot(dy) / h - FOG_NEAR_VIEWPORTS) / (FOG_FAR_VIEWPORTS - FOG_NEAR_VIEWPORTS))
        .clamp(0.0, 1.0) as f32
}

/// Mix an ARGB colour toward the fog colour by `f`: 0 is unchanged, 1 is the fog colour.
///
/// Applied in packed space like `scale_alpha`, consistent with the renderer's existing
/// non-colour-managed math. Folds into the pushed colour, so fills *and* lines fog together
/// (unlike `morph.x`, which the line path ignores) — roads haze with the land instead of
/// staying crisp over it. Alpha mixes too: full fog is opaque haze, which is what makes the
/// far edge read as haze rather than as translucent ground.
pub(crate) fn apply_fog(argb: u32, fog: u32, f: f32) -> u32 {
    if f <= 0.0 {
        return argb;
    }
    let m = |a: u32, b: u32| {
        (a as f32 + (b as f32 - a as f32) * f)
            .round()
            .clamp(0.0, 255.0) as u32
    };
    (m(argb >> 24, fog >> 24) << 24)
        | (m((argb >> 16) & 0xFF, (fog >> 16) & 0xFF) << 16)
        | (m((argb >> 8) & 0xFF, (fog >> 8) & 0xFF) << 8)
        | m(argb & 0xFF, fog & 0xFF)
}

/// The colour distance fog mixes toward: the `earth` land colour, not the water-blue clear.
///
/// The clear colour is the sea by design (`style::background` is water: an unloaded map reads
/// as ocean with land appearing on top of it). Fogging toward it pulled distant tilted land
/// into ocean-blue, dissolving the far ground into sea. The far ground under tilt is land, so
/// the haze it dissolves into is the land's own colour — `earth.color(palette)` at the frame
/// zoom, opacity-scaled the way the terrain pass paints it, so the farthest drawn tiles arrive
/// already land-coloured.
///
/// Falls back to the background when the style names no `earth` layer, which keeps the old
/// behaviour rather than inventing a colour. That never happens with the bundled style; the
/// fallback is for a future style that drops the layer.
///
/// Since v8 the `earth` fill arm reads source `landtype` (matched by style id, because the
/// source alone no longer names it).
pub(crate) fn fog_color(layers: &[Layer], palette: Palette, zoom: f64) -> u32 {
    layers
        .iter()
        .find(|l| l.id == "earth")
        .map(|earth| super::scale_alpha(earth.color(palette), earth.opacity_at(zoom)))
        .unwrap_or_else(|| crate::style::background(palette.variant))
}

/// How far past the viewport a tile may sit and still be drawn, in Dp.
///
/// The cull below drops resident tiles the viewport cannot see — stale tiles from the last
/// pan awaiting eviction, cap overflow — but a label anchored near a tile's edge can legitimately
/// extend past it, and the symbol path draws winners from whatever tiles placement accepted.
/// So the flat draws cull with this margin while symbols always draw: a tile further than this
/// from the viewport contributes no visible fill/line pixel, and any label it carried is either
/// duplicated on a visible tile or itself off-screen. Larger than the largest label box
/// (city names at ~30px plus halo); far smaller than a tile span at any zoom that matters.
pub(crate) const CULL_MARGIN_DP: f64 = 64.0;

/// Whether tile (`z`, `x`, `y`) is provably off-screen, so its flat draws can be skipped.
///
/// Conservative by construction: the globe path never culls (tiles bend onto the sphere, so a
/// flat rect test is meaningless), tilted views never cull (the ground trapezoid reaches past
/// the untilted bounds — see `select::coverage`), and the flat path culls only tiles fully
/// outside the rotated viewport bounds plus [`CULL_MARGIN_DP`]. Symbol draws are NOT gated on
/// this (see `record_inner`): placement dedup can crown a stale tile's copy of a border label,
/// and dropping that tile's symbols would lose the label while its on-screen twin was
/// deduped away. Fills, lines, carriageways, buildings, terrain, traffic and region pieces
/// are per-tile ground cover — skipping them where nothing shows is pure win.
pub(crate) fn tile_offscreen(camera: &Camera, z: u8, x: u32, y: u32) -> bool {
    if crate::camera::globe_active(camera) || camera.pitch_deg != 0.0 {
        return false;
    }
    let span = camera.tile_span_dp(z);
    if !(span > 0.0) {
        return false;
    }
    let (min, max) = camera.viewport_bounds();
    let m = CULL_MARGIN_DP;
    let x0 = x as f64 * span;
    let y0 = y as f64 * span;
    x0 + span < min.x - m || x0 > max.x + m || y0 + span < min.y - m || y0 > max.y + m
}

/// How many draws of style layer `layer_index` a culled `tile` would have issued.
///
/// The layer loop's share of the cull (file-length split): counts this tile's meshes for the
/// layer so the loop can skip them in one call. Zero when the tile is on-screen — the common
/// case is one function call and out.
pub(crate) fn culled_flat_draws(
    tile: &super::ResidentTile,
    layer_index: usize,
    camera: &Camera,
) -> usize {
    if !tile_offscreen(camera, tile.z, tile.x, tile.y) {
        return 0;
    }
    tile.layers
        .iter()
        .filter(|l| l.layer_index == layer_index)
        .count()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn camera(pitch: f64) -> Camera {
        Camera {
            center_lon: -122.4194,
            center_lat: 37.7749,
            zoom: 14.0,
            width_dp: 411.0,
            height_dp: 891.0,
            density: 2.0,
            bearing_deg: 0.0,
            pitch_deg: pitch,
            pivot_dp: 0.0,
            time_seconds: 0.0,
            globe: false,
            moon: false,
        }
    }

    #[test]
    fn the_flat_map_never_fogs() {
        let c = camera(0.0);
        // Even a tile at the edge of the world: pitch 0 short-circuits to zero.
        assert_eq!(fog_factor(&c, 14, 0, 0), 0.0);
        assert_eq!(fog_factor(&c, 14, 16383, 16383), 0.0);
    }

    #[test]
    fn the_centre_tile_never_fogs_when_tilted() {
        // The tile under the camera centre sits at distance ~0 viewports: inside the near
        // ramp at any pitch, so the readable map never hazes.
        let c = camera(65.0);
        let centre = crate::camera::project(c.center_lon, c.center_lat, c.zoom);
        let span = c.tile_span_dp(14);
        let x = (centre.x / span).floor() as u32;
        let y = (centre.y / span).floor() as u32;
        assert_eq!(fog_factor(&c, 14, x, y), 0.0);
    }

    #[test]
    fn a_far_tile_fogs_fully_at_max_tilt() {
        // Six viewport-heights north of the centre: past the far ramp end, so full fog.
        let c = camera(65.0);
        let centre = crate::camera::project(c.center_lon, c.center_lat, c.zoom);
        let span = c.tile_span_dp(14);
        let far = centre.y - 6.0 * c.height_dp as f64;
        let ty = (far / span).floor().max(0.0) as u32;
        let tx = (centre.x / span).floor().max(0.0) as u32;
        assert_eq!(fog_factor(&c, 14, tx, ty), 1.0);
    }

    #[test]
    fn fog_mixes_toward_the_fog_colour() {
        assert_eq!(apply_fog(0xFF00_0000, 0xFFFF_FFFF, 0.0), 0xFF00_0000);
        assert_eq!(apply_fog(0xFF00_0000, 0xFFFF_FFFF, 1.0), 0xFFFF_FFFF);
        // Halfway red-to-white is half-grey, opaque throughout.
        assert_eq!(apply_fog(0xFFFF_0000, 0xFFFF_FFFF, 0.5), 0xFFFF_8080);
    }

    #[test]
    fn fog_aims_at_the_land_not_the_water() {
        use crate::style::Variant;
        let layers = crate::style::layers();
        let land = fog_color(layers, Palette::new(false, false), 14.0);
        assert_eq!(land, 0xFFE2DFDA, "light earth is the authored #e2dfda");
        assert_eq!(
            fog_color(layers, Palette::new(true, false), 14.0),
            0xFF24262C,
            "dark earth is the authored #24262c"
        );
        // The water-blue clear the fog used to aim at: full fog must no longer land on it.
        let water = crate::style::background(Variant::Light);
        assert_eq!(
            water, 0xFF80DEEA,
            "the clear is still the authored water-blue"
        );
        assert_ne!(land, water, "fog must not aim at the water-blue clear");
        // Full fog of a water draw is the land colour: a far tile hazes into the shore
        // haze rather than dissolving the land into sea.
        assert_eq!(apply_fog(water, land, 1.0), land);
    }

    /// The tile under the camera centre at the camera zoom, for cull tests.
    fn centre_tile(c: &Camera, z: u8) -> (u32, u32) {
        let centre = crate::camera::project(c.center_lon, c.center_lat, c.zoom);
        let span = c.tile_span_dp(z);
        (
            (centre.x / span).floor().max(0.0) as u32,
            (centre.y / span).floor().max(0.0) as u32,
        )
    }

    #[test]
    fn the_centre_tile_is_never_culled() {
        let c = camera(0.0);
        let (x, y) = centre_tile(&c, 14);
        assert!(!tile_offscreen(&c, 14, x, y));
        // Its neighbours are inside the viewport too (411x891 Dp against 512 Dp tiles).
        assert!(!tile_offscreen(&c, 14, x + 1, y));
        assert!(!tile_offscreen(&c, 14, x, y + 1));
    }

    #[test]
    fn a_tile_across_the_world_is_culled() {
        let c = camera(0.0);
        let (x, y) = centre_tile(&c, 14);
        // More than a viewport plus the 64 Dp margin away in tile units.
        let far_x = (x as i64 + 10).min(16383) as u32;
        assert!(tile_offscreen(&c, 14, far_x, y));
        let far_y = (y as i64 + 10).min(16383) as u32;
        assert!(tile_offscreen(&c, 14, x, far_y));
    }

    #[test]
    fn the_cull_never_fires_tilted_or_on_the_globe() {
        // Tilted: the ground trapezoid reaches past the untilted bounds, so a rect test
        // would cull visible ground. The cull stays flat-only by construction.
        let tilted = camera(65.0);
        let (x, y) = centre_tile(&tilted, 14);
        let far_x = (x as i64 + 10).min(16383) as u32;
        assert!(!tile_offscreen(&tilted, 14, far_x, y));
        // Globe: tiles bend onto the sphere, so a flat rect test is meaningless.
        let mut globe = camera(0.0);
        globe.globe = true;
        assert!(!tile_offscreen(&globe, 2, 0, 0));
    }

    #[test]
    fn the_cull_never_fires_rotated_off_the_wrong_edge() {
        // A 45° bearing widens the covered box by sqrt(2): a tile at the viewport corner
        // must survive, because the rotated viewport reaches it.
        let mut c = camera(0.0);
        c.bearing_deg = 45.0;
        let (x, y) = centre_tile(&c, 14);
        assert!(!tile_offscreen(&c, 14, x, y));
        // But the far side of the world still culls — rotation widens, it does not cover.
        let far_x = (x as i64 + 10).min(16383) as u32;
        assert!(tile_offscreen(&c, 14, far_x, y));
    }
}
