//! Marker quad emission: one billboarded sprite's clip-space corners.
//!
//! Split from `record_extra3.rs` (file-length limit). Pure function over the
//! resolved quad centre/axes — shared by the flat billboard path and the globe
//! anchor path (axis-aligned clip axes with w == 1, same add).
use crate::camera::Camera;
use crate::marker::Marker;
use crate::tile::sprite::Sprite;

/// One marker's resolved draw geometry: the sprite to sample and the clip-space
/// quad centre and local Dp axes to hang it off.
///
/// The single derivation the icon draw, the label draw and the POI-placement
/// blockers all read, so a pin's collision box is the box it draws — a second
/// implementation of this projection would drift out of step and pins would
/// cull POIs they do not cover (or fail to cull ones they do).
///
/// `None` when the marker draws nothing: an icon the sheet does not carry, the
/// tilt putting it behind the eye, or (globe) the far side. Callers skip such
/// a marker entirely — icon, label and blocker alike.
pub(super) fn marker_screen_geom(
    camera: &Camera,
    marker: &Marker,
    globe: bool,
) -> Option<(Sprite, f64, f64, f64, f64, f64, f64, f64)> {
    let sprite = crate::marker::icon_sprite_name(marker.icon)
        .and_then(|n| crate::tile::sprite::atlas().get(n))?;
    // Globe: far-side markers are on the planet's far side — culled, not
    // drawn. Near-side markers resolve their clip quad through the globe
    // anchor (screen position of the lon/lat on the ball) with the same
    // screen-constant size as the flat path.
    if globe {
        let (sx, sy) = camera.globe_anchor_to_screen(marker.lon, marker.lat)?;
        // Clip-space centre from Dp: clip = 2*dp/dim - 1 (y down, matching
        // the flat matrices' sign convention).
        let cx = sx / camera.width_dp as f64 * 2.0 - 1.0;
        let cy = sy / camera.height_dp as f64 * 2.0 - 1.0;
        // Dp axes in clip space: one Dp is 2/dim clip units. Axis-aligned, so
        // the icon is already screen-upright on this path.
        let xu = 2.0 / camera.width_dp as f64;
        let yv = 2.0 / camera.height_dp as f64;
        return Some((sprite, cx, cy, xu, 0.0, 0.0, yv, 1.0));
    }
    // The billboard matrix for this marker (upright + screen-constant under tilt). Its
    // translation column is the quad centre in clip space; columns 0 and 1 are the local
    // Dp axes. All four corners share the same `w` (the matrix's `w` columns for the two
    // in-plane axes are zero on both the ortho and the tilted path), so the perspective
    // divide is one number per marker.
    let m = camera.screen_quad_to_clip(marker.lon, marker.lat, 1.0);
    let w = m[15] as f64;
    if !(w > 0.0) {
        return None; // behind the eye / above the horizon under tilt: nothing to draw.
    }
    let cx = m[12] as f64 / w;
    let cy = m[13] as f64 / w;
    let (xu, yu) = (m[0] as f64 / w, m[1] as f64 / w);
    let (xv, yv) = (m[4] as f64 / w, m[5] as f64 / w);
    Some((sprite, cx, cy, xu, yu, xv, yv, w))
}

/// Counter-rotate a quad-local `(u, v)` offset by the inverse of the camera bearing.
///
/// The clip matrices turn a local offset `d` into a screen offset `R·d`; pre-multiplying by
/// `Rᵀ` here makes it come out screen-aligned again — the same trick as
/// [`crate::tess::text::upright`], applied to the offset rather than the position because a
/// marker's centre must stay glued to its lon/lat while only its orientation straightens.
/// The per-axis clip scales factor out of the rotation (they multiply through), so this holds
/// on the tilted path as well as the ortho one; at bearing zero it is the identity. The globe
/// path never calls this: its axes are already axis-aligned.
pub(super) fn upright_offset(u: f64, v: f64, rotation: (f64, f64)) -> (f64, f64) {
    let (cos, sin) = rotation;
    (cos * u - sin * v, sin * u + cos * v)
}

#[cfg(test)]
mod tests {
    use super::upright_offset;

    /// North-up is the identity: every phone frame takes this path, so it must be exact.
    #[test]
    fn north_up_counter_rotation_is_the_identity() {
        for (u, v) in [(0.0, 0.0), (14.0, -14.0), (-3.25, 7.5)] {
            let (ru, rv) = upright_offset(u, v, (1.0, 0.0));
            assert!((ru - u).abs() < 1e-12, "{ru} != {u}");
            assert!((rv - v).abs() < 1e-12, "{rv} != {v}");
        }
    }

    /// A 90-degree bearing turns the map a quarter turn, so the counter-rotation turns the
    /// offset back: what was right of the pin draws right of it, not below it.
    #[test]
    fn a_quarter_turn_bearing_counter_rotates_the_offset() {
        let (ru, rv) = upright_offset(1.0, 0.0, (0.0, 1.0));
        assert!(ru.abs() < 1e-12, "{ru}");
        assert!((rv - 1.0).abs() < 1e-12, "{rv}");
    }
}

/// Emit one marker's name label into the shared glyph vertex/index buffers.
///
/// The POI `emit_label` counterpart for screen-anchored pins: the label is shaped with the
/// glyph atlas (single line — pin names are short, and wrapping would need the placer's
/// line-box machinery), laid out in Dp to the icon's right at [`MARKER_LABEL_DP`], and hung
/// off the marker's clip centre through its (counter-rotated) Dp axes, so each glyph lands
/// beside the upright icon. Glyphs the atlas does not carry are skipped, exactly as
/// [`crate::tess::text::emit`] skips them.
///
/// Vertices are the 7-float billboard layout (`x, y, u, v, ax, ay, ah`): the anchor is the
/// marker centre with zero height, ignored while the draw's billboard flag is clear (the flat
/// reduction `symbol_billboard.vert` documents) but truthful if that ever changes.
/// `density` is the camera's device-px-per-Dp, turning the Dp size into the device-px size the
/// SDF shader reads from the push.
#[allow(clippy::too_many_arguments)]
pub(super) fn emit_marker_label(
    glyph_atlas: &crate::tile::glyph::GlyphAtlas,
    label: &str,
    icon_hw_dp: f64,
    cx: f64,
    cy: f64,
    xu: f64,
    yu: f64,
    xv: f64,
    yv: f64,
    rotation: (f64, f64),
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
) {
    use crate::marker::{MARKER_LABEL_DP, MARKER_LABEL_GAP_DP};
    use crate::tess::text::{shape, CAP_HEIGHT_EM};
    use crate::tile::glyph::{Weight, UP_EM};
    const FLOATS_PER_VERTEX: usize = 7;
    let (glyphs, _) = shape(
        glyph_atlas,
        Weight::Regular,
        label,
        false,
    );
    if glyphs.is_empty() {
        return;
    }
    // Dp per font unit at the marker label size — the clip-space counterpart of `emit`'s
    // `px_per_font_unit`, uniform on both axes so glyphs keep their proportions.
    let dp_per_unit = MARKER_LABEL_DP / UP_EM as f32;
    // The block starts past the icon's edge plus daylight, and its baseline centres the cap
    // height on the marker centre — the single-line `Anchor::Left` placement `emit` does.
    let origin_x = icon_hw_dp as f32 + MARKER_LABEL_GAP_DP;
    let baseline_y = 0.5 * CAP_HEIGHT_EM * MARKER_LABEL_DP;
    for g in &glyphs {
        let Some(uv) = glyph_atlas.uv(Weight::Regular, g.ch) else {
            continue;
        };
        let x0 = origin_x + (g.pen_x + g.bearing_x) * dp_per_unit;
        let x1 = x0 + g.w * dp_per_unit;
        // `top` is y-up above the baseline; clip space (like tile space) is y-down.
        let y0 = baseline_y - g.top * dp_per_unit;
        let y1 = y0 + g.h * dp_per_unit;
        let base = (vertices.len() / FLOATS_PER_VERTEX) as u32;
        // Each Dp corner counter-rotated (screen-upright) and hung off the clip centre
        // through the Dp axes — the same add `emit_marker_corners` does for icons.
        for (dx, dy, tu, tv) in [(x0, y0, uv.u0, uv.v0), (x1, y0, uv.u1, uv.v0), (x1, y1, uv.u1, uv.v1), (x0, y1, uv.u0, uv.v1)] {
            let (du, dv) = upright_offset(dx as f64, dy as f64, rotation);
            vertices.push((cx + du * xu + dv * xv) as f32);
            vertices.push((cy + du * yu + dv * yv) as f32);
            vertices.push(tu);
            vertices.push(tv);
            vertices.push(cx as f32);
            vertices.push(cy as f32);
            vertices.push(0.0);
        }
        indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
    }
}
/// Emit one marker's four corners into the shared sprite vertex/index buffers.
///
/// `corners` are (local_u, local_v, tex_u, tex_v); (cx, cy) the NDC centre and
/// (xu, yu)/(xv, yv) the local Dp axes in NDC, both already divided by the
/// marker's `w` in [`marker_screen_geom`], so the corners add straight on.
pub(super) fn emit_marker_corners(
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
    base: u32,
    corners: [(f64, f64, f32, f32); 4],
    cx: f64,
    cy: f64,
    xu: f64,
    yu: f64,
    xv: f64,
    yv: f64,
) {
    for (lu, lv, tu, tv) in corners {
        let ox = lu * xu + lv * xv;
        let oy = lu * yu + lv * yv;
        vertices.push((cx + ox) as f32);
        vertices.push((cy + oy) as f32);
        vertices.push(tu);
        vertices.push(tv);
    }
    indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
}
