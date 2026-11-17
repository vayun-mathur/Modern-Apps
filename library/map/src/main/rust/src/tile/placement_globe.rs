// Globe-aware screen projection for label placement: lon/lat through the
// orthographic sphere instead of the flat tile matrix.
//
// Split from `placement.rs` (file-length limit). The flat `project_to_screen`
// feeds a tile-local point through the tile's clip matrix; on the globe the
// matrix bends (a 4x4 cannot bend a sphere), so labels placed through it land
// in flat positions while the basemap under them draws bent — the scattered
// text of the broken globe. These helpers project through the same
// `globe_point` basis the vertex shaders and the CPU anchor use, with the
// same screen convention (`halfH - y`, north up) and the same far-side cull
// (z < 0 draws nothing).
//
// No imports: this file is `include!`d into `placement.rs`, so `Anchor`,
// `BoxInputs` and `Obb` (and the whole parent scope) are already in scope.

/// Screen px of a lon/lat on the globe, or `None` far-side.
///
/// `radius_dp` is the globe radius in Dp (`globe_radius`), `extent_wh` the
/// viewport in device px, `density` device px per Dp. Matches
/// `Camera::globe_anchor_to_screen` + the Dp-to-device mapping the flat
/// `project_to_screen` ends with.
pub fn globe_to_screen(
    camera: &crate::camera::Camera,
    lon: f64,
    lat: f64,
    extent_wh: (u32, u32),
) -> Option<(f32, f32)> {
    let (sx_dp, sy_dp) = camera.globe_anchor_to_screen(lon, lat)?;
    let (w, h) = (extent_wh.0 as f64, extent_wh.1 as f64);
    let px = sx_dp / camera.width_dp as f64 * w;
    let py = sy_dp / camera.height_dp as f64 * h;
    Some((px as f32, py as f32))
}

/// Tile-local anchor to lon/lat at the camera zoom: the inverse of the tile
/// grid the flat path folds into its matrix.
pub fn tile_anchor_lonlat(tz: u8, tx: u32, ty: u32, anchor: (f32, f32)) -> (f64, f64) {
    let n = 2f64.powi(tz as i32);
    let wx = (tx as f64 + anchor.0 as f64) / n;
    let wy = (ty as f64 + anchor.1 as f64) / n;
    let lon = wx * 360.0 - 180.0;
    let n_pi = std::f64::consts::PI - 2.0 * std::f64::consts::PI * wy;
    let lat = n_pi.sinh().atan() * 180.0 / std::f64::consts::PI;
    (lon, lat)
}

/// Which tile a globe-projected point lives in.
///
/// Bundled because the globe helpers always need the whole tile address to
/// turn a tile-local point into lon/lat; the coordinates are never passed
/// independently.
#[derive(Clone, Copy)]
pub struct TileRef {
    /// Zoom level.
    pub z: u8,
    /// Tile column.
    pub x: u32,
    /// Tile row.
    pub y: u32,
}

/// The screen box a label would occupy on the globe if drawn at `anchor`.
///
/// Same box construction as [`super::anchored_rect`] (text block + icon union,
/// anchor-directed, padded) but centred on the globe-projected anchor rather
/// than the flat matrix projection. Returns `None` far-side.
pub fn globe_anchored_rect(
    camera: &crate::camera::Camera,
    tile: TileRef,
    point: (f32, f32),
    extent_wh: (u32, u32),
    inputs: &BoxInputs,
    anchor: Anchor,
) -> Option<(f32, f32, f32, f32)> {
    let (lon, lat) = tile_anchor_lonlat(tile.z, tile.x, tile.y, point);
    let (sx, sy) = globe_to_screen(camera, lon, lat, extent_wh)?;

    let w = inputs.text_px * inputs.advance / crate::tile::glyph::UP_EM as f32;
    let lines = inputs.line_count.max(1) as f32;
    let h = inputs.text_px * (1.0 + (lines - 1.0) * crate::tess::text::LINE_HEIGHT_EM);
    let offset = inputs.offset_em.0.abs() * inputs.text_px;
    let (mut x0, mut x1) = match anchor {
        Anchor::Center => (sx - w * 0.5, sx + w * 0.5),
        Anchor::Left => (sx + offset, sx + offset + w),
        Anchor::Right => (sx - offset - w, sx - offset),
    };
    let (mut y0, mut y1) = (sy - h * 0.5, sy + h * 0.5);
    if let Some((icon_w, icon_h)) = inputs.icon_px {
        x0 = x0.min(sx - icon_w * 0.5);
        x1 = x1.max(sx + icon_w * 0.5);
        y0 = y0.min(sy - icon_h * 0.5);
        y1 = y1.max(sy + icon_h * 0.5);
    }
    Some((
        x0 - inputs.pad_px,
        y0 - inputs.pad_px,
        x1 + inputs.pad_px,
        y1 + inputs.pad_px,
    ))
}

/// Build a curved label's per-glyph screen collision boxes on the globe.
///
/// The segmented analogue of [`globe_anchored_rect`]: each pen point goes
/// tile-local -> lon/lat -> globe screen, and the glyph becomes an [`Obb`]
/// oriented to the screen-space tangent. The tangent is the tile-local tangent
/// rotated by the local sphere bearing (east/north basis at the pen), which at
/// globe zoom is axis-aligned up to the meridian convergence — approximated by
/// the flat linear part, exactly as the flat path does under tilt.
pub fn globe_curved_boxes(
    camera: &crate::camera::Camera,
    tile: TileRef,
    placements: &[crate::tess::text::CurvedGlyph],
    tile_clip: [f32; 16],
    extent_wh: (u32, u32),
    text_px: f32,
    pad_px: f32,
) -> Vec<Obb> {
    let (w, h) = (extent_wh.0 as f32, extent_wh.1 as f32);
    let em = crate::tile::glyph::UP_EM as f32;
    let cap_px = crate::tess::text::CAP_HEIGHT_EM * text_px;
    let mut out = Vec::with_capacity(placements.len());
    for cg in placements {
        let advance_px = cg.glyph.advance / em * text_px;
        if advance_px <= 0.0 {
            continue;
        }
        let (lon, lat) = tile_anchor_lonlat(tile.z, tile.x, tile.y, cg.pen);
        let Some((sx, sy)) = globe_to_screen(camera, lon, lat, extent_wh) else { continue };
        // Screen-space tangent through the flat linear part (glyph-scale: the
        // sphere bend varies negligibly across one glyph).
        let dcx = tile_clip[0] * cg.tangent.0 + tile_clip[4] * cg.tangent.1;
        let dcy = tile_clip[1] * cg.tangent.0 + tile_clip[5] * cg.tangent.1;
        let (mut tx, mut ty) = (dcx * w, dcy * h);
        let len = (tx * tx + ty * ty).sqrt();
        if len > 1e-6 {
            tx /= len;
            ty /= len;
        } else {
            tx = 1.0;
            ty = 0.0;
        }
        out.push(Obb {
            cx: sx + tx * advance_px * 0.5,
            cy: sy + ty * advance_px * 0.5,
            hx: advance_px * 0.5 + pad_px,
            hy: cap_px * 0.5 + pad_px,
            cos: tx,
            sin: ty,
        });
    }
    out
}
