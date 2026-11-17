//! Unit tests for `inpaint` (exemplar + diffusion branches).
use crate::inpaint::inpaint;
use crate::pixel::{a, pack, r};

fn solid(w: usize, h: usize, p: i32) -> Vec<i32> {
    vec![p; w * h]
}
fn pattern(w: usize, h: usize) -> Vec<i32> {
    let mut v = Vec::with_capacity(w * h);
    for y in 0..h {
        for x in 0..w {
            let c = ((x * 37 + y * 91) % 256) as i32;
            v.push(pack(255, c, 255 - c, ((x * 3 + y * 5) % 256) as i32));
        }
    }
    v
}

#[test]
fn mask_threshold_is_half() {
    // 0.49 is not a hole; 0.5 is. Only the second call may change the pixel.
    let (w, h) = (9, 9);
    let colour = pack(255, 40, 80, 120);
    let mut src = solid(w, h, colour);
    src[4 * w + 4] = pack(255, 0, 0, 0);
    let mut mask = vec![0f32; w * h];
    mask[4 * w + 4] = 0.49;
    assert_eq!(inpaint(&src, w, h, &mask, w, h, 10), src);
    mask[4 * w + 4] = 0.5;
    let out = inpaint(&src, w, h, &mask, w, h, 10);
    assert_eq!(out[4 * w + 4], colour, "hole in flat field fills with the field colour");
}

#[test]
fn single_hole_in_flat_field_fills_exactly_and_touches_nothing_else() {
    let (w, h) = (11, 11);
    let colour = pack(255, 10, 20, 30);
    let mut src = solid(w, h, colour);
    src[5 * w + 5] = pack(255, 250, 250, 250);
    let mut mask = vec![0f32; w * h];
    mask[5 * w + 5] = 1.0;
    let out = inpaint(&src, w, h, &mask, w, h, 10);
    assert_eq!(out, solid(w, h, colour));
}

#[test]
fn non_hole_pixels_are_never_modified_exemplar() {
    let (w, h) = (20, 20);
    let src = pattern(w, h);
    let mut mask = vec![0f32; w * h];
    for y in 8..12 {
        for x in 8..12 {
            mask[y * w + x] = 1.0;
        }
    }
    let out = inpaint(&src, w, h, &mask, w, h, 30);
    for y in 0..h {
        for x in 0..w {
            if mask[y * w + x] < 0.5 {
                assert_eq!(out[y * w + x], src[y * w + x], "non-hole ({x},{y}) changed");
            }
            assert_eq!(a(out[y * w + x]), 255);
        }
    }
}

#[test]
fn corner_hole_in_flat_field_fills() {
    let (w, h) = (8, 8);
    let colour = pack(255, 66, 99, 33);
    let mut src = solid(w, h, colour);
    let mut mask = vec![0f32; w * h];
    for y in 0..2 {
        for x in 0..2 {
            mask[y * w + x] = 1.0;
            src[y * w + x] = pack(255, 0, 0, 0);
        }
    }
    let out = inpaint(&src, w, h, &mask, w, h, 10);
    assert_eq!(out, solid(w, h, colour));
}

#[test]
fn all_hole_image_becomes_neutral_gray_keeping_alpha() {
    // No known pixel exists, so the safety fill paints 0x808080 and
    // preserves each pixel's alpha byte.
    let (w, h) = (4, 4);
    let mut src = Vec::new();
    for i in 0..w * h {
        src.push(pack(100 + i as i32, 10, 200, 30));
    }
    let mask = vec![1f32; w * h];
    let out = inpaint(&src, w, h, &mask, w, h, 10);
    for i in 0..w * h {
        assert_eq!(out[i] as u32 & 0x00FF_FFFF, 0x0080_8080, "gray fill at {i}");
        assert_eq!(a(out[i]), a(src[i]), "alpha kept at {i}");
    }
}

#[test]
fn scaled_mask_maps_by_floor() {
    // 2x2 mask over a 4x4 image: each mask cell covers a 2x2 quadrant.
    // Marking the top-left mask cell holes the top-left quadrant only.
    let (w, h) = (4, 4);
    let colour = pack(255, 50, 100, 150);
    let mut src = solid(w, h, colour);
    for y in 0..2 {
        for x in 0..2 {
            src[y * w + x] = pack(255, 0, 0, 0);
        }
    }
    let mask = vec![1.0f32, 0.0, 0.0, 0.0];
    let out = inpaint(&src, w, h, &mask, 2, 2, 10);
    assert_eq!(out, solid(w, h, colour), "quadrant hole filled, rest untouched");
}

#[test]
fn degenerate_mask_returns_input() {
    let src = pattern(6, 6);
    // Zero-size mask dimensions must not panic (division / clamp on empty range).
    assert_eq!(inpaint(&src, 6, 6, &[], 0, 0, 10), src);
    assert_eq!(inpaint(&src, 6, 6, &[], 0, 6, 10), src);
    // Mask buffer shorter than mask_w*mask_h likewise.
    assert_eq!(inpaint(&src, 6, 6, &[1.0, 0.0], 8, 8, 10), src);
}

#[test]
fn diffusion_branch_smooths_large_hole_and_keeps_known_pixels() {
    // 150x150 hole (>20000 px) forces the Jacobi diffusion branch.
    let (w, h) = (160, 160);
    let colour = pack(255, 200, 200, 200);
    let mut src = solid(w, h, colour);
    let mut mask = vec![0f32; w * h];
    for y in 5..155 {
        for x in 5..155 {
            mask[y * w + x] = 1.0;
            src[y * w + x] = pack(255, 0, 0, 0);
        }
    }
    let out = inpaint(&src, w, h, &mask, w, h, 80);
    assert_eq!(out.len(), w * h);
    // Known border pixels untouched.
    assert_eq!(out[0], colour);
    assert_eq!(out[159 * w + 159], colour);
    // A hole pixel next to the known border diffuses toward the border
    // colour: strictly between the black seed and 200 after 80 passes.
    let edge = r(out[5 * w + 5]);
    assert!(edge > 50 && edge <= 200, "diffused edge value {edge}");
    assert!(out.iter().all(|&p| a(p) == 255));
}

#[test]
fn diffusion_zero_passes_leaves_hole_seed_unchanged() {
    let (w, h) = (160, 160);
    let mut src = solid(w, h, pack(255, 200, 200, 200));
    let mut mask = vec![0f32; w * h];
    for y in 5..155 {
        for x in 5..155 {
            mask[y * w + x] = 1.0;
            src[y * w + x] = pack(255, 0, 0, 0);
        }
    }
    // Zero (or negative) Jacobi passes copy the seed values back verbatim.
    assert_eq!(inpaint(&src, w, h, &mask, w, h, 0), src);
    assert_eq!(inpaint(&src, w, h, &mask, w, h, -5), src);
}
