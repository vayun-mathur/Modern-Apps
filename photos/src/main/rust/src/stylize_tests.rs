//! Unit tests for `stylize` (FindEdges / Emboss).
use crate::pixel::{a, b, g, pack, r};
use crate::stylize::stylize;

fn gray(w: usize, h: usize, v: i32) -> Vec<i32> {
    vec![pack(255, v, v, v); w * h]
}
fn assert_gray(p: i32) {
    assert_eq!(r(p), g(p));
    assert_eq!(g(p), b(p));
}

#[test]
fn none_and_unknown_modes_are_identity() {
    let mut src = Vec::new();
    for i in 0..(6 * 5) {
        src.push(pack(200, (i * 37) % 256, (i * 91) % 256, (i * 53) % 256));
    }
    for mode in [0, 3, 99, -1] {
        assert_eq!(stylize(&src, 6, 5, mode), src, "mode {mode}");
    }
}

#[test]
fn edges_flat_is_white_and_emboss_flat_is_bias_plus_luminance() {
    // Zero gradient => Sobel magnitude 0 => inverted to 255.
    let out = stylize(&gray(6, 6, 77), 6, 6, 1);
    assert!(out.iter().all(|&p| p == pack(255, 255, 255, 255)));
    // Emboss kernel sums to 1, so a flat field L maps to clamp(L + 128).
    assert!(stylize(&gray(5, 5, 0), 5, 5, 2).iter().all(|&p| r(p) == 128));
    assert!(stylize(&gray(5, 5, 100), 5, 5, 2).iter().all(|&p| r(p) == 228));
    assert!(stylize(&gray(5, 5, 255), 5, 5, 2).iter().all(|&p| r(p) == 255));
}

#[test]
fn edges_vertical_step_has_dark_seam_and_white_flats() {
    let (w, h) = (10, 6);
    let mut src = Vec::new();
    for _y in 0..h {
        for x in 0..w {
            let v = if x < 5 { 0 } else { 255 };
            src.push(pack(255, v, v, v));
        }
    }
    let out = stylize(&src, w, h, 1);
    for y in 0..h {
        assert_eq!(r(out[y * w + 1]), 255, "flat interior stays white");
        assert_eq!(r(out[y * w + 8]), 255);
        // Both columns straddling the step see the full Sobel response.
        assert_eq!(r(out[y * w + 4]), 0, "seam (dark side)");
        assert_eq!(r(out[y * w + 5]), 0, "seam (bright side)");
    }
}

#[test]
fn edges_uses_bt601_luminance_weights() {
    // Black -> pure colour step of value 100. At the seam gx = 4*luminance,
    // gy = 0, so v = 255 - 4*lum: red lum 29 -> 139, green 58 -> 23,
    // blue 11 -> 211. Green must read as the strongest edge.
    let (w, h) = (8, 5);
    let make = |c: i32| -> Vec<i32> {
        let mut v = Vec::new();
        for _y in 0..h {
            for x in 0..w {
                v.push(if x < 4 { pack(255, 0, 0, 0) } else { c });
            }
        }
        v
    };
    let seam = |c: i32| r(stylize(&make(c), w, h, 1)[2 * w + 3]);
    assert_eq!(seam(pack(255, 100, 0, 0)), 139);
    assert_eq!(seam(pack(255, 0, 100, 0)), 23);
    assert_eq!(seam(pack(255, 0, 0, 100)), 211);
}

#[test]
fn edges_and_emboss_are_grayscale_and_preserve_alpha() {
    let (w, h) = (9, 7);
    let mut src = Vec::new();
    for y in 0..h {
        for x in 0..w {
            src.push(pack(
                100 + ((x + y) * 10) as i32,
                (x * 28) as i32 % 256,
                (y * 36) as i32 % 256,
                ((x * y) * 9) as i32 % 256,
            ));
        }
    }
    for mode in [1, 2] {
        let out = stylize(&src, w, h, mode);
        assert_eq!(out.len(), w * h);
        for i in 0..w * h {
            assert_gray(out[i]);
            assert_eq!(a(out[i]), a(src[i]), "mode {mode} alpha at {i}");
        }
    }
}

#[test]
fn emboss_linear_ramp_matches_kernel_algebra() {
    // Luminance ramp L = 10*x (constant in y). Kernel column sums are
    // (-3, 1, 3) over dx, so sum = -3(L-10) + L + 3(L+10) = L + 60 and
    // v = clamp(L + 60 + 128). Centre pixel x=4: L=40 => 228.
    let (w, h) = (9, 9);
    let mut src = Vec::new();
    for _y in 0..h {
        for x in 0..w {
            let v = (x * 10) as i32;
            src.push(pack(255, v, v, v));
        }
    }
    let out = stylize(&src, w, h, 2);
    assert_eq!(r(out[4 * w + 4]), 228);
    assert_eq!(r(out[4 * w + 2]), 208);
}

#[test]
fn single_pixel_images() {
    // All neighbours clamp to the single pixel: no gradient.
    let src = vec![pack(180, 90, 90, 90)];
    assert_eq!(stylize(&src, 1, 1, 1), vec![pack(180, 255, 255, 255)]);
    // Emboss: sum = luminance (kernel sums to 1) => 90 + 128 = 218.
    assert_eq!(stylize(&src, 1, 1, 2), vec![pack(180, 218, 218, 218)]);
    assert_eq!(stylize(&src, 1, 1, 0), src);
}
