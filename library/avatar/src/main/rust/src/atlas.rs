//! Procedural face atlas: skin-tone base + drawn face features.
//!
//! Split along module lines (repo `rustFileLength` gate). Generates a 256×256
//! RGBA atlas at runtime from traits — no `.glb` needed for faces. Layout:
//! - Full-atlas base fill = skin tone (so every skin UV keeps working).
//! - Face block [0, 128, 256, 256] (bottom half): 12 eye styles × rows,
//!   8 brow styles, 8 nose styles, 12 mouth styles, each drawn per style
//!   index with trait colors. Mole/wrinkle/blush overlaid by index.
//! - Shirt block keeps procedural UV split (mesh UVs unchanged elsewhere).
//!
//! The head's face quad (mesh.rs) UVs point into the active eye/brow/nose/
//! mouth cells; sliders that move features shift the quad, styles select the
//! cell. Drawn-on but positioned by traits.

use crate::traits::YuuTraits;

/// Atlas edge in pixels.
pub const ATLAS_SIZE: usize = 256;

/// Face block origin (bottom half of the atlas).
pub const FACE_X: usize = 0;
pub const FACE_Y: usize = 128;
pub const FACE_W: usize = 256;
pub const FACE_H: usize = 128;

/// Cell grid inside the face block: 12 columns (max style count) × 4 rows
/// (eyes / brows / nose / mouth).
pub const FACE_COLS: usize = 12;
pub const FACE_ROWS: usize = 4;

/// Pixel width/height of one face cell.
pub fn face_cell() -> (usize, usize) {
    (FACE_W / FACE_COLS, FACE_H / FACE_ROWS)
}

/// UV rect (u0, v0, u1, v1) for a face cell.
/// Row 0 = eyes, 1 = brows, 2 = nose, 3 = mouth; col = style index clamped.
/// V matches sampler space (no flip — see [`cell_uv_by_col`]).
pub fn face_cell_uv(row: usize, style: i32) -> [f32; 4] {
    cell_uv_by_col(
        row.min(FACE_ROWS - 1),
        (style.max(0) as usize) % FACE_COLS,
    )
}

/// UV rect for a mole/wrinkle/blush overlay cell (sampler space, no flip).
///
/// Overlays share the spare brow-row cells (row 1, cols 8..11): column
/// `8 + (style mod 4)` — exactly the cells [`generate_atlas`] paints, so the
/// mesh decal UVs and the painted pixels always agree.
pub fn overlay_cell_uv(style: i32) -> [f32; 4] {
    cell_uv_by_col(1, 8 + (style.max(0) as usize % 4))
}

/// UV rect (u0, v0, u1, v1) for one face-block cell by explicit row/column.
///
/// No V flip: both backends sample memory row 0 at v=0 (Vulkan samples the
/// first texel at (0,0); GL unpacks the first byte to the bottom-left texel),
/// so v = memory_y / ATLAS_SIZE directly. (An earlier revision flipped V
/// here; every decal then sampled skin and faces rendered blank.)
fn cell_uv_by_col(row: usize, col: usize) -> [f32; 4] {
    let (cw, ch) = face_cell();
    let x0 = FACE_X + col * cw;
    let y0 = FACE_Y + row * ch;
    let s = ATLAS_SIZE as f32;
    [
        x0 as f32 / s,
        y0 as f32 / s,
        (x0 + cw) as f32 / s,
        (y0 + ch) as f32 / s,
    ]
}

/// Unpack an ARGB long to RGBA bytes.
fn argb_to_rgba(argb: u64, fallback: [u8; 3]) -> [u8; 4] {
    if argb == 0 {
        return [fallback[0], fallback[1], fallback[2], 0xFF];
    }
    [
        ((argb >> 16) & 0xFF) as u8,
        ((argb >> 8) & 0xFF) as u8,
        (argb & 0xFF) as u8,
        0xFF,
    ]
}

/// Fill a rect in the pixel buffer (clipped).
fn fill_rect(
    px: &mut [u8],
    x: usize,
    y: usize,
    w: usize,
    h: usize,
    color: [u8; 4],
) {
    for j in y..(y + h).min(ATLAS_SIZE) {
        for i in x..(x + w).min(ATLAS_SIZE) {
            let o = (j * ATLAS_SIZE + i) * 4;
            px[o..o + 4].copy_from_slice(&color);
        }
    }
}

/// Fill an ellipse in the pixel buffer (clipped).
fn fill_ellipse(
    px: &mut [u8],
    cx: usize,
    cy: usize,
    rx: usize,
    ry: usize,
    color: [u8; 4],
) {
    let rx = rx.max(1);
    let ry = ry.max(1);
    for j in cy.saturating_sub(ry)..(cy + ry).min(ATLAS_SIZE) {
        for i in cx.saturating_sub(rx)..(cx + rx).min(ATLAS_SIZE) {
            let dx = (i as f32 - cx as f32) / rx as f32;
            let dy = (j as f32 - cy as f32) / ry as f32;
            if dx * dx + dy * dy <= 1.0 {
                let o = (j * ATLAS_SIZE + i) * 4;
                px[o..o + 4].copy_from_slice(&color);
            }
        }
    }
}

/// Generate the 256×256 RGBA face atlas for the given traits.
///
/// Base = skin tone everywhere (existing skin UVs keep working). Only the
/// SELECTED style cell per row is painted — other columns stay skin so style
/// selection visibly changes the atlas (and the mesh UVs, which point at the
/// selected cell, always hit painted pixels):
/// - Eyes (row 0): white sclera ellipse + dark pupil ellipse; size varies
///   with column, pupil offset zigzags so adjacent styles differ.
/// - Brows (row 1): horizontal bar; thickness/angle vary by column.
/// - Nose (row 2): vertical shaded bar; width/height vary by column.
/// - Mouth (row 3): horizontal smile bar; width/curve vary by column.
///
/// Mole/wrinkle/blush are overlaid on the brow-row spare cells when
/// nonzero.
pub fn generate_atlas(traits: &YuuTraits) -> Vec<u8> {
    let skin = {
        let rgb = traits.skin_rgb();
        [
            (rgb[0].clamp(0.0, 1.0) * 255.0) as u8,
            (rgb[1].clamp(0.0, 1.0) * 255.0) as u8,
            (rgb[2].clamp(0.0, 1.0) * 255.0) as u8,
            0xFF,
        ]
    };
    let mut px = vec![0u8; ATLAS_SIZE * ATLAS_SIZE * 4];
    // Base fill: skin everywhere.
    for o in px.chunks_exact_mut(4) {
        o.copy_from_slice(&skin);
    }

    let (cw, ch) = face_cell();
    let dark = [0x22, 0x1A, 0x14, 0xFF];
    let white = [0xFF, 0xFF, 0xFF, 0xFF];
    let eye_rgb = traits.eye_color;
    let brow_rgb = traits.brow_color;
    let mouth_rgb = traits.mouth_color;
    let eye_tint = argb_to_rgba(eye_rgb, [0x22, 0x1A, 0x14]);
    let brow_tint = argb_to_rgba(brow_rgb, [0x2A, 0x1E, 0x12]);
    let mouth_tint = argb_to_rgba(mouth_rgb, [0x8A, 0x3B, 0x2E]);

    // Row 0 — eyes: paint ONLY the selected style column. Other columns stay
    // skin so style selection visibly changes the atlas (and the mesh UVs,
    // which point at the selected cell, always hit painted pixels).
    let eye_col = (traits.eye_style.max(0) as usize) % FACE_COLS;
    {
        let x0 = FACE_X + eye_col * cw;
        let y0 = FACE_Y;
        let w = cw - 4;
        let ew = (w / 3).max(3) + eye_col % 3;
        let eh = (ch / 4).max(3);
        let cy = y0 + ch / 2;
        for side in [0, 1] {
            let cx = x0 + 2 + side * (w / 2) + (w / 4) % 3;
            fill_ellipse(&mut px, cx, cy, ew, eh, white);
            let pox = (cx as i32 + ((eye_col % 3) as i32 - 1)).max(x0 as i32 + 1) as usize;
            fill_ellipse(
                &mut px,
                pox,
                cy,
                (ew / 2).max(1),
                (eh / 2).max(1),
                eye_tint,
            );
        }
    }
    // Row 1 — brows: selected column only. Bar thickness/tilt vary.
    let brow_col = (traits.brow_style.max(0) as usize) % FACE_COLS;
    {
        let x0 = FACE_X + brow_col * cw;
        let y0 = FACE_Y + ch;
        let thick = 2 + brow_col % 3;
        let tilt = (brow_col % 3) as i32 - 1;
        for side in [0, 1] {
            let bx = x0 + 2 + side * ((cw - 4) / 2);
            for k in 0..thick {
                let yy = (y0 + ch / 2 + k) as i32 + tilt * (side as i32 * 2 - 1);
                if yy >= FACE_Y as i32 && (yy as usize) < FACE_Y + FACE_H {
                    fill_rect(&mut px, bx, yy as usize, (cw - 4) / 2, 1, brow_tint);
                }
            }
        }
    }
    // Row 2 — nose: selected column only. Vertical shaded bar.
    let nose_col = (traits.nose_style.max(0) as usize) % FACE_COLS;
    {
        let x0 = FACE_X + nose_col * cw;
        let y0 = FACE_Y + 2 * ch;
        let nw = 2 + nose_col % 3;
        let nh = ch - 6 - (nose_col % 2) * 2;
        let shade = [
            (skin[0] as f32 * 0.85) as u8,
            (skin[1] as f32 * 0.82) as u8,
            (skin[2] as f32 * 0.80) as u8,
            0xFF,
        ];
        fill_rect(&mut px, x0 + cw / 2 - nw / 2, y0 + 3, nw, nh, shade);
        fill_rect(&mut px, x0 + cw / 2 - nw / 2, y0 + 3 + nh, nw + 2, 2, dark);
    }
    // Row 3 — mouth: selected column only. Width/curve vary.
    let mouth_col = (traits.mouth_style.max(0) as usize) % FACE_COLS;
    {
        let x0 = FACE_X + mouth_col * cw;
        let y0 = FACE_Y + 3 * ch;
        let mw = (cw - 8).max(6) - (mouth_col % 4);
        let my = y0 + ch / 2 + (mouth_col % 3) - 1;
        fill_rect(&mut px, x0 + (cw - mw) / 2, my, mw, 3, mouth_tint);
        // Smile corners for even columns.
        if mouth_col % 2 == 0 {
            fill_rect(&mut px, x0 + (cw - mw) / 2, my.saturating_sub(2), 2, 2, mouth_tint);
            fill_rect(&mut px, x0 + (cw + mw) / 2 - 2, my.saturating_sub(2), 2, 2, mouth_tint);
        }
    }
    // Overlays: mole / wrinkle / blush on spare brow-row cells (cols 8..11).
    if traits.mole_style != 0 {
        let col = 8 + (traits.mole_style.max(0) as usize % 4);
        fill_ellipse(
            &mut px,
            FACE_X + col * cw + cw / 2,
            FACE_Y + ch + ch / 2,
            2,
            2,
            dark,
        );
    }
    if traits.wrinkle_style != 0 {
        let col = 8 + (traits.wrinkle_style.max(0) as usize % 4);
        for k in 0..3 {
            fill_rect(
                &mut px,
                FACE_X + col * cw + 3,
                FACE_Y + ch + 4 + k * 4,
                cw - 6,
                1,
                dark,
            );
        }
    }
    if traits.blush_style != 0 {
        let col = 8 + (traits.blush_style.max(0) as usize % 4);
        let pink = [0xE8, 0x8A, 0x7A, 0xFF];
        fill_ellipse(&mut px, FACE_X + col * cw + 4, FACE_Y + ch + ch / 2, 3, 2, pink);
        fill_ellipse(
            &mut px,
            FACE_X + col * cw + cw - 4,
            FACE_Y + ch + ch / 2,
            3,
            2,
            pink,
        );
    }
    let _ = white;
    px
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::traits::YuuTraits;

    #[test]
    fn atlas_size_and_skin_base() {
        let t = YuuTraits::default();
        let px = generate_atlas(&t);
        assert_eq!(px.len(), ATLAS_SIZE * ATLAS_SIZE * 4);
        // Top-left pixel = skin fallback (default skin_tone 0 → [0.91,0.71,0.54]).
        assert_eq!(&px[0..4], &[232, 181, 137, 255]);
    }

    #[test]
    fn face_cell_uvs_in_face_block() {
        let uv = face_cell_uv(0, 3);
        // u inside [0,1]; eye row lives at memory rows 128..160 → v 0.5..0.625
        // (no V flip — samplers read memory row 0 at v=0 on both backends).
        assert!(uv[0] >= 0.0 && uv[2] <= 1.0);
        assert!(uv[1] >= 0.5 && uv[1] <= 0.625);
        assert!(uv[3] >= 0.5 && uv[3] <= 0.625);
        assert!(uv[2] > uv[0] && uv[3] > uv[1]);
    }

    #[test]
    fn styles_change_pixels() {
        let a = generate_atlas(&YuuTraits::default());
        let varied = YuuTraits { eye_style: 7, mouth_style: 9, ..Default::default() };
        let b = generate_atlas(&varied);
        assert_ne!(a, b, "style indices must change atlas pixels");
    }
}
