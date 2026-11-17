use super::ICON_FLOATS_PER_VERTEX;
use crate::style::Anchor;
use crate::tess::text;
use crate::tile::geometry::ShapedLabel;
use crate::tile::sprite::Sprite;

/// Emit one shaped label's quads at the size and anchor the caller resolved for it.
///
/// `text_px` is DEVICE px (the layer's size arm at the camera zoom x camera density -
/// the ramp is authored in Dp, the shader and the tile span are device px). The wrapper
/// exists so the unit contract lives in one place instead of at every call.
///
/// `anchor` and `offset_em` are the resolved variable anchor and the layer's
/// `text-offset`: [`Anchor::Center`] with a zero offset is the place-label path and is
/// exactly what this drew before POI existed.
///
/// Rank emphasis used to live here as a fixed multiplier (1.25x for big cities, 0.85x
/// for hamlets) standing in for the authored data-driven size arms. The style carries
/// those arms properly now - see `Layer::text_size_for` - so the caller resolves the
/// size and this just draws it.
///
/// `rotation` is the camera's `(cos, sin)` (see [`crate::camera::Camera::rotation`]): the
/// emitted quads are counter-rotated about the anchor so the label stays **upright**
/// under a heading-up camera. `(1.0, 0.0)` is north-up and costs nothing.
///
/// `ground` samples the tile-normalised ground height under the anchor, carried as the
/// trailing float so the billboard hangs the quad off the terrain there.
///
/// A **curved** label — one carrying a [`centreline`](ShapedLabel::centreline) — takes a
/// different path: its single line is laid along the polyline by
/// [`text::emit_curved`](crate::tess::text::emit_curved), one glyph per vertex rotated to the
/// local tangent, and it is **not** counter-rotated. A curved label is map-aligned, so its
/// orientation is the road's, not the camera's; `anchor`, `offset_em` and `rotation` are unused
/// on that path.
#[allow(clippy::too_many_arguments)]
pub fn emit_label(
    label: &ShapedLabel,
    anchor: Anchor,
    offset_em: (f32, f32),
    text_px: f32,
    tile_span_px: f32,
    rotation: (f32, f32),
    ground: &dyn Fn(f32, f32) -> f32,
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
) {
    let atlas = crate::tile::glyph::atlas();
    // A curved (line) label lays its single shaped run along the centreline; the tangent gives
    // each glyph its rotation, so no `upright` counter-rotation and no anchor/offset apply.
    if let Some(centreline) = &label.centreline {
        let Some(line) = label.lines.first() else {
            return;
        };
        text::emit_curved(
            atlas,
            label.weight,
            line,
            centreline,
            &text::CurvedScale {
                text_px,
                tile_span_px,
                ground,
            },
            vertices,
            indices,
        );
        return;
    }
    let start = vertices.len();
    text::emit(
        atlas,
        label.weight,
        &label.lines,
        label.anchor,
        anchor,
        offset_em,
        text_px,
        tile_span_px,
        ground,
        vertices,
        indices,
    );
    text::upright(&mut vertices[start..], label.anchor, rotation);
}

/// Emit one POI icon's quad, centred on the label's anchor point.
///
/// The reference sets neither `icon-size` nor `icon-offset`, so an icon is its sheet size
/// in Dp, centred on the point, at a constant screen size whatever the zoom — unlike the
/// label beside it, which follows a `text-size` ramp. The label's `text-offset` is what
/// keeps the two from overlapping.
///
/// Goes into a **separate** buffer from the text: the two sample different atlases through
/// different fragment shaders (a picture against a distance field), so they cannot share a
/// draw even though they share a pipeline layout and a vertex format.
///
/// `rotation` counter-rotates the quad exactly as it does for the text beside it, so a POI
/// pictogram stays the right way up under a heading-up camera. Tilt is handled separately and
/// on the GPU: every corner carries the label's ground anchor, and the billboard vertex shader
/// projects that anchor and hangs the corner off it at a constant screen offset, so the icon
/// faces the camera at any pitch. The half-extents below stay a tile-local offset *from* the
/// anchor rather than a resolved screen position, which is what lets the shader do that.
///
/// `ground` samples the tile-normalised height under the anchor — the icon hangs off the same
/// terrain the label does.
#[allow(clippy::too_many_arguments)]
pub fn emit_icon(
    label: &ShapedLabel,
    sprite: Sprite,
    dark: bool,
    density: f32,
    tile_span_px: f32,
    rotation: (f32, f32),
    ground: &dyn Fn(f32, f32) -> f32,
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
) {
    if tile_span_px <= 0.0 {
        return;
    }
    let half_w = sprite.width_dp * density * 0.5 / tile_span_px;
    let half_h = sprite.height_dp * density * 0.5 / tile_span_px;
    let (cx, cy) = label.anchor;
    let (x0, y0) = (cx - half_w, cy - half_h);
    let (x1, y1) = (cx + half_w, cy + half_h);
    let uv = sprite.uv;
    // The sheet is the light half over the dark one, so the theme is one addition here
    // rather than a second atlas or a second resolved `Sprite`. Applied at emit — which
    // runs every frame — so switching palette stays free of re-tessellation.
    let dv = if dark {
        crate::tile::sprite::atlas().dark_v_offset()
    } else {
        0.0
    };
    let (v0, v1) = (uv.v0 + dv, uv.v1 + dv);
    let base = (vertices.len() / ICON_FLOATS_PER_VERTEX) as u32;
    let start = vertices.len();
    // Trailing `cx, cy` is the ground anchor, repeated on all four corners, plus its terrain
    // height. The corner positions are the anchor plus a tile-local half-extent, so the shader
    // recovers the screen offset by subtracting the two — the same contract `tess::text::emit`
    // writes for a glyph.
    let anchor_h = ground(cx, cy);
    vertices.extend_from_slice(&[x0, y0, uv.u0, v0, cx, cy, anchor_h]);
    vertices.extend_from_slice(&[x1, y0, uv.u1, v0, cx, cy, anchor_h]);
    vertices.extend_from_slice(&[x1, y1, uv.u1, v1, cx, cy, anchor_h]);
    vertices.extend_from_slice(&[x0, y1, uv.u0, v1, cx, cy, anchor_h]);
    indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
    // Rotates the corner positions about the anchor and leaves the anchor itself alone, so the
    // offset the shader reconstructs is the counter-rotated one.
    text::upright_stride(
        &mut vertices[start..],
        label.anchor,
        rotation,
        ICON_FLOATS_PER_VERTEX,
    );
}
