use super::metrics::{ATLAS_COLS, ATLAS_PX, CELL_PX, SDF_SPREAD_PX};
use super::placement::Placement;

/// 8SSEDT-lite: exact brute-force distance transform over a small cell.
///
/// Inside/outside comes from the 50% coverage threshold; distance is normalised by
/// `SDF_SPREAD_PX`. `placement` is where the caller has decided the bitmap sits in the
/// cell, so the same rect `ink_uv` addresses is the rect sampled here.
///
/// Still an exact nearest-opposite-texel search, but organised so it is cheap enough to run
/// at renderer start (the atlas used to cost 1.35 s on a desktop core, all of it on the thread
/// that created the map):
/// - the inside/outside test is sampled once per cell texel into a mask instead of once per
///   (texel, offset) pair, each of which paid a float divide and four bounds checks;
/// - the search walks offsets nearest-first and stops at the first opposite texel, which is
///   by construction the nearest one, and never visits offsets at or past the search limit;
/// - a summed-area table skips the walk outright for texels whose whole search square is one
///   colour (no opposite texel can be in range), which is most of every cell.
///
/// The result is bit-identical to the original square scan
/// ([`sdf_from_coverage_reference`] in the tests pins it over every glyph of both weights):
/// the original kept the minimum `d_cell` among opposite texels with `d_cell < spread*scale`,
/// which is exactly the first opposite texel in ascending-distance order.
pub(super) fn sdf_from_coverage(coverage: &[u8], w: u32, h: u32, placement: Placement) -> Vec<u8> {
    let inside = |x: i32, y: i32| -> bool {
        x >= 0
            && y >= 0
            && (x as u32) < w
            && (y as u32) < h
            && coverage[(y as u32 * w + x as u32) as usize] >= 128
    };
    let spread = SDF_SPREAD_PX as f32;
    let Placement {
        scale,
        ox,
        oy,
        dw,
        dh,
    } = placement;
    let sample = |cx: u32, cy: u32| -> bool {
        if cx < ox || cy < oy || cx >= ox + dw || cy >= oy + dh {
            return false;
        }
        let gx = ((cx - ox) as f32 / scale) as i32;
        let gy = ((cy - oy) as f32 / scale) as i32;
        inside(gx, gy)
    };
    // Search radius in *cell* px: the spread in glyph px scaled up. Distances are
    // divided back down by the same scale so the SDF is in glyph px everywhere.
    let r_cell = ((SDF_SPREAD_PX as f32 * scale).ceil() as u32).max(2);
    // The mask covers every coordinate a neighbour lookup can reach: 0..CELL_PX + r_cell.
    // Negative neighbours saturate to 0 (the original used `saturating_add_signed`).
    let ext = (CELL_PX + r_cell + 1) as usize;
    let mut mask = vec![false; ext * ext];
    for y in 0..ext {
        for x in 0..ext {
            mask[y * ext + x] = sample(x as u32, y as u32);
        }
    }
    // Every offset the original could accept (`d_cell < best * scale` with `best` starting at
    // `spread`), nearest first. Ties keep any order: equal distances give equal answers.
    let limit = spread * scale;
    let r = r_cell as i32;
    let mut offsets: Vec<(f32, i32, i32)> = Vec::with_capacity(((2 * r + 1) * (2 * r + 1)) as usize);
    for dy in -r..=r {
        for dx in -r..=r {
            let d_cell = ((dx * dx + dy * dy) as f32).sqrt();
            if d_cell < limit {
                offsets.push((d_cell, dx, dy));
            }
        }
    }
    offsets.sort_by(|a, b| a.0.total_cmp(&b.0));
    // Summed-area table of the mask, so a texel whose whole search square is one colour —
    // most of the cell: the empty margin around the ink and the middle of thick stems — can
    // be answered "no opposite texel in range" in O(1) instead of walking every offset.
    let mut sat = vec![0u32; (ext + 1) * (ext + 1)];
    for y in 0..ext {
        let mut row = 0u32;
        for x in 0..ext {
            row += u32::from(mask[y * ext + x]);
            sat[(y + 1) * (ext + 1) + x + 1] = sat[y * (ext + 1) + x + 1] + row;
        }
    }
    let inside_in = |x0: usize, y0: usize, x1: usize, y1: usize| -> u32 {
        // Inclusive rect; `+ - -` order keeps the u32 arithmetic from underflowing.
        sat[(y1 + 1) * (ext + 1) + x1 + 1] + sat[y0 * (ext + 1) + x0]
            - sat[y0 * (ext + 1) + x1 + 1]
            - sat[(y1 + 1) * (ext + 1) + x0]
    };
    let mut out = vec![0u8; (CELL_PX * CELL_PX) as usize];
    for cy in 0..CELL_PX as i32 {
        for cx in 0..CELL_PX as i32 {
            let me = mask[cy as usize * ext + cx as usize];
            // Inside the stroke the distance is capped by the search radius, so a
            // thick stem never reaches full bright — but the shader only needs the
            // 0.5 crossing plus a smoothing band either side. Scale the inside
            // distance so the stem centre hits 1.0: full dynamic range at the edge.
            let mut best = spread;
            // The reachable square, clamped at 0 exactly as the neighbour lookup clamps.
            let (x0, y0) = ((cx - r).max(0) as usize, (cy - r).max(0) as usize);
            let (x1, y1) = ((cx + r) as usize, (cy + r) as usize);
            let inside = inside_in(x0, y0, x1, y1);
            let uniform = if me {
                inside as usize == (x1 - x0 + 1) * (y1 - y0 + 1)
            } else {
                inside == 0
            };
            if !uniform {
                for &(d_cell, dx, dy) in &offsets {
                    let nx = (cx + dx).max(0) as usize;
                    let ny = (cy + dy).max(0) as usize;
                    if mask[ny * ext + nx] != me {
                        best = d_cell / scale;
                        break;
                    }
                }
            }
            let value = if me {
                // Inside: 0.5 at the edge → 1.0 one px in. Stems thicker than 2px
                // saturate, which is correct for an SDF edge function.
                (0.5 + (best / 2.0).min(0.5)).clamp(0.0, 1.0)
            } else {
                (0.5 - best / (2.0 * spread)).clamp(0.0, 1.0)
            };
            out[(cy as u32 * CELL_PX + cx as u32) as usize] = (value * 255.0) as u8;
        }
    }
    out
}

/// The original square-scan distance transform, kept verbatim as the reference the
/// optimised [`sdf_from_coverage`] is pinned against.
#[cfg(test)]
pub(super) fn sdf_from_coverage_reference(coverage: &[u8], w: u32, h: u32, placement: Placement) -> Vec<u8> {
    let inside = |x: i32, y: i32| -> bool {
        x >= 0
            && y >= 0
            && (x as u32) < w
            && (y as u32) < h
            && coverage[(y as u32 * w + x as u32) as usize] >= 128
    };
    let spread = SDF_SPREAD_PX as f32;
    let mut out = vec![0u8; (CELL_PX * CELL_PX) as usize];
    let Placement {
        scale,
        ox,
        oy,
        dw,
        dh,
    } = placement;
    let sample = |cx: u32, cy: u32| -> bool {
        if cx < ox || cy < oy || cx >= ox + dw || cy >= oy + dh {
            return false;
        }
        let gx = ((cx - ox) as f32 / scale) as i32;
        let gy = ((cy - oy) as f32 / scale) as i32;
        inside(gx, gy)
    };
    let r_cell = ((SDF_SPREAD_PX as f32 * scale).ceil() as u32).max(2);
    for cy in 0..CELL_PX {
        for cx in 0..CELL_PX {
            let me = sample(cx, cy);
            let mut best = spread;
            for dy in -(r_cell as i32)..=(r_cell as i32) {
                for dx in -(r_cell as i32)..=(r_cell as i32) {
                    let d_cell = ((dx * dx + dy * dy) as f32).sqrt();
                    if d_cell < best * scale
                        && sample(cx.saturating_add_signed(dx), cy.saturating_add_signed(dy)) != me
                    {
                        best = d_cell / scale;
                    }
                }
            }
            let value = if me {
                (0.5 + (best / 2.0).min(0.5)).clamp(0.0, 1.0)
            } else {
                (0.5 - best / (2.0 * spread)).clamp(0.0, 1.0)
            };
            out[(cy * CELL_PX + cx) as usize] = (value * 255.0) as u8;
        }
    }
    out
}

/// Expand one R8 SDF row into RGBA8 for upload: `[v, v, v, v]` per texel.
///
/// THE upload contract the fragment shader depends on (`shaders/symbol.frag`
/// samples `.r`). The SDF value goes in ALL FOUR channels deliberately: the
/// Stage-B device verdict proved a running binary sampling `.r == 0` with the
/// SDF in `.a` only — a channel divorce no in-tree step can produce (the view
/// uses identity swizzle, the staging copy cannot reorder UNORM channels), so
/// it came from a stale binary predating the RGBA8 expansion. Writing `v`
/// everywhere makes `.r` correct under ANY single-channel placement the bytes
/// ever had, and any future swap that breaks it fails
/// `the_rgba8_expansion_carries_sdf_in_every_channel` instead of the capture.
///
/// Lives here (host-compiled) rather than in `vulkan::images` (Android-only) so
/// the contract is testable without a device.
pub fn expand_sdf_r8_to_rgba8(r8: &[u8]) -> Vec<u8> {
    r8.iter().flat_map(|&v| [v, v, v, v]).collect()
}

/// Copy one cell's SDF into the atlas image.
pub(super) fn blit_cell(atlas: &mut [u8], cell: u32, sdf: &[u8]) {
    let col = cell % ATLAS_COLS;
    let row = cell / ATLAS_COLS;
    for cy in 0..CELL_PX {
        let dst = ((row * CELL_PX + cy) * ATLAS_PX + col * CELL_PX) as usize;
        let src = (cy * CELL_PX) as usize;
        atlas[dst..dst + CELL_PX as usize].copy_from_slice(&sdf[src..src + CELL_PX as usize]);
    }
}
