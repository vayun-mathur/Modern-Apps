//! Per-label resolve for the pick snapshot: one label's screen box + lon/lat.
//!
//! File-length split from [`placement`](super::placement) (see `placement.rs`):
//! `refresh_placed` walks the tiles there; this resolves one label through
//! either the flat matrix projection or the globe sphere projection (far side
//! culls), so pick boxes match drawn boxes on both paths.
use super::placement_reuse::box_inputs;
use super::Renderer;
use crate::camera::Camera;
use crate::style::Layer;
use ash::vk;

impl Renderer {
    /// One label's pick box + lon/lat for `refresh_placed`, or `None` when the
    /// anchor has no screen position (behind the eye, or globe far side).
    ///
    /// Same projection the pre-pass placed through — pitch-aware flat matrix,
    /// sphere bend on the globe — so the pick box is the drawn box on both
    /// paths. `tile_wx`/`tile_wy`/`span_dp` are the flat tile origin + span
    /// (Dp) the lon/lat unprojection needs; unused on the globe, where the
    /// sphere basis inverts through the centre instead.
    #[allow(clippy::too_many_arguments)]
    pub(super) fn refresh_placed_label(
        &self,
        camera: &Camera,
        layer: &Layer,
        label: &crate::tile::geometry::ShapedLabel,
        tile: &super::ResidentTile,
        text_px: f32,
        primary: crate::style::Anchor,
        alternate: Option<crate::style::Anchor>,
        anchor: crate::style::Anchor,
        extent: vk::Extent2D,
        globe: bool,
        tile_wx: f64,
        tile_wy: f64,
        span_dp: f64,
    ) -> Option<((f32, f32, f32, f32), f64, f64)> {
        use crate::tile::placement;
        let tile_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
        let extent_wh = (extent.width, extent.height);
        // Same projection as the pre-pass (pitch-aware flat, sphere on the
        // globe); an unprojectable anchor has no box and no hit.
        let rect = if globe {
            placement::globe_anchored_rect(
                camera,
                placement::TileRef {
                    z: tile.z,
                    x: tile.x,
                    y: tile.y,
                },
                label.anchor,
                extent_wh, &box_inputs(layer, label, camera), anchor,
            )
        } else {
            placement::anchored_rect(
                label.anchor, tile_clip, extent_wh,
                &box_inputs(layer, label, camera), anchor,
            )
        }?;
        // Anchor tile-local → lon/lat. Globe: the sphere basis inverts through
        // the centre (same basis the draw path bends through); flat: world px
        // at the camera zoom.
        let (lon, lat) = if globe {
            placement::tile_anchor_lonlat(tile.z, tile.x, tile.y, label.anchor)
        } else {
            let wx = tile_wx + label.anchor.0 as f64 * span_dp;
            let wy = tile_wy + label.anchor.1 as f64 * span_dp;
            crate::camera::unproject(wx, wy, camera.zoom)
        };
        let _ = (primary, alternate, text_px);
        Some((rect, lon, lat))
    }
}
