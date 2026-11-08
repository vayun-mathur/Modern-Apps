//! Tilt-pivot elevation sampling for pitched frames.
//!
//! File-length split from the frame loop (see [`render`](super::Renderer::render)): the
//! per-frame pivot resolution that walks the resident tile pyramid for the terrain height
//! under the camera target — and the shared sampler behind it, exposed for the host-side
//! overlay projection so markers and gestures pivot on what the basemap drew with.
use super::Renderer;
use crate::camera::Camera;
use crate::tile::select;

impl Renderer {
    /// Tilt-pivot elevation under the camera target, in world px at this zoom.
    ///
    /// Walks the tile pyramid from the finest level that can hold the target
    /// (`floor(zoom)`, clamped to 0..=22) down to z0 and samples the first
    /// resident tile carrying a heightmap, at the target's tile-local (u, v).
    /// Metres become world px through the tile's own span over its ground
    /// width — the same normalisation the label ground sampler uses, inverted.
    ///
    /// Returns 0.0 — the legacy sea-level pivot — where no resident tile has
    /// DEM (oceans, unloaded tiles), so those frames render exactly as before.
    pub(super) fn ground_pivot_dp(&self, camera: &Camera) -> f64 {
        self.pivot_dp_at(camera.center_lon, camera.center_lat, camera.zoom)
    }

    /// Tilt-pivot elevation under an arbitrary geo point, in world px at `zoom`:
    /// the shared sampler behind [`ground_pivot_dp`](Self::ground_pivot_dp), exposed
    /// for the host-side overlay projection through
    /// [`tiltPivotDp`](Java_com_vayunmathur_library_map_MapNative_tiltPivotDp), so
    /// markers and gestures use the same pivot the renderer drew with. Same 0.0
    /// fallback where no resident tile has DEM.
    pub(crate) fn pivot_dp_at(&self, lon: f64, lat: f64, zoom: f64) -> f64 {
        use crate::tile::geometry::{sample_ground_metres, tile_ground_width_m};
        let z_top = zoom.floor().clamp(0.0, 22.0) as u8;
        let c = crate::camera::project(lon, lat, zoom);
        let size = crate::camera::world_size(zoom);
        for z in (0..=z_top).rev() {
            let n = 2u32.pow(z as u32); // tiles per side; z <= 22 fits u32 and TileId's 22-bit x/y.
            let span = size / f64::from(n); // == camera.tile_span_dp(z).
            let tx = ((c.x / span).floor() as u32).min(n - 1);
            let ty = ((c.y / span).floor() as u32).min(n - 1);
            let key = select::TileId { z, x: tx, y: ty }.key();
            let Some(tile) = self.tiles.get(&key) else {
                continue;
            };
            if tile.heightmap.is_none() {
                continue;
            }
            let u = ((c.x - f64::from(tx) * span) / span) as f32;
            let v = ((c.y - f64::from(ty) * span) / span) as f32;
            let Some(m) = sample_ground_metres(&tile.heightmap, u, v) else {
                continue;
            };
            let width = tile_ground_width_m(z, ty).max(f64::MIN_POSITIVE);
            return f64::from(m) * span / width;
        }
        0.0
    }
}
