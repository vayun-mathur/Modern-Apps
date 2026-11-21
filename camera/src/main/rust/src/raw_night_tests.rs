//! Synthetic RAW tests: Bayer gradients, black-level handling, and a
//! measurable noise-variance reduction from the linear merge.

use super::*;

/// Deterministic pseudo-noise in [-amp, amp] from a cheap LCG.
fn noise(i: usize, seed: u32, amp: i32) -> i32 {
    let mut s = seed.wrapping_add((i as u32).wrapping_mul(2654435761));
    s ^= s << 13;
    s ^= s >> 17;
    s ^= s << 5;
    (s % (2 * amp as u32 + 1)) as i32 - amp
}

/// Builds an RGGB frame whose underlying scene is a smooth gradient in
/// linear light, rendered per-photosite, plus per-sample noise.
fn synthetic(w: usize, h: usize, seed: u32, noise_amp: i32, black: u16, white: u16) -> RawFrame {
    let meta = RawMeta::scalar(black as f32, white as f32, Cfa::Rggb);
    let mut samples = vec![0u16; w * h];
    for y in 0..h {
        for x in 0..w {
            let ch = meta.cfa.channel_at(x, y);
            // Scene: R rises with x, G with (x+y)/2, B falls with x.
            let scene = match ch {
                0 => 0.15 + 0.5 * x as f32 / w as f32,
                2 => 0.65 - 0.5 * x as f32 / w as f32,
                _ => 0.15 + 0.5 * (x + y) as f32 / (w + h) as f32,
            };
            // Block texture so green-channel ORB registration has corners
            // to lock onto (a pure gradient is featureless by design).
            let block = ((x / 16) * 31 + (y / 16) * 17 + ((x / 16) * (y / 16)) * 7) % 5;
            // Fine scene texture, identical across the burst (only the
            // per-frame `noise` differs), so registration has signal.
            let cell = ((x / 3) * 73856093) ^ ((y / 3) * 19349663);
            let fine = ((cell % 13) as f32 - 6.0) * 0.012;
            let texture = (block as f32 - 2.0) * 0.05 + fine;
            let v = black as i32
                + ((scene + texture).clamp(0.02, 0.95) * (white - black) as f32).round() as i32
                + noise(y * w + x, seed, noise_amp);
            samples[y * w + x] = v.clamp(0, 65535) as u16;
        }
    }
    RawFrame { w, h, samples, meta }
}

/// Noise power: mean squared deviation of the green channel from the
/// noiseless ground-truth frame (scene gradient/texture cancels out).
fn green_noise_power(img: &LinearRgb, clean: &LinearRgb, x0: usize, y0: usize, side: usize) -> f64 {
    let mut acc = 0.0f64;
    let mut n = 0usize;
    for y in y0..y0 + side {
        for x in x0..x0 + side {
            let d = img.px[y * img.w + x][1] as f64 - clean.px[y * clean.w + x][1] as f64;
            acc += d * d;
            n += 1;
        }
    }
    acc / n as f64
}

#[test]
fn raw10_unpack_roundtrip() {
    // Samples 0, 341, 682, 1023 packed as RAW10.
    let samples = [0u16, 341, 682, 1023];
    let mut bytes = vec![0u8; 5];
    for k in 0..4 {
        bytes[k] = (samples[k] >> 2) as u8;
        bytes[4] |= ((samples[k] & 3) as u8) << (k * 2);
    }
    let out = unpack_raw10(&bytes.repeat(4), 4, 1).expect("unpack");
    assert_eq!(out[0..4], samples[..]);
    assert!(unpack_raw10(&bytes, 3, 1).is_none(), "width must be multiple of 4");
}

#[test]
fn raw16_unpack_little_endian() {
    let out = unpack_raw16(&[0x34, 0x12, 0x00, 0x01], 2, 1).expect("unpack");
    assert_eq!(out, vec![0x1234, 0x0100]);
}

#[test]
fn black_level_is_subtracted_not_crushed() {
    // A frame sitting exactly at black level linearises to 0, and a
    // frame 10% above black (in sensor counts) stays ~10% of range:
    // black subtraction must not push shadow signal negative or clip it.
    let black = 64u16;
    let white = 1023u16;
    let mut frame = synthetic(16, 16, 1, 0, black, white);
    for s in frame.samples.iter_mut() {
        *s = black;
    }
    let lin = demosaic(&frame);
    assert!(lin.px.iter().all(|p| p.iter().all(|&v| v == 0.0)), "at black -> 0");

    for s in frame.samples.iter_mut() {
        *s = black + (white - black) / 10;
    }
    let lin = demosaic(&frame);
    let v = lin.px[8 * 16 + 8][1];
    assert!((v - 0.1).abs() < 0.02, "10% above black stays ~0.1, got {v}");
}

#[test]
fn demosaic_recovers_bayer_gradient_channels() {
    let frame = synthetic(32, 24, 7, 0, 64, 1023);
    let lin = demosaic(&frame);
    // Left edge is R-dark/B-bright, right edge the reverse.
    let left = lin.px[12 * 32 + 2];
    let right = lin.px[12 * 32 + 29];
    assert!(left[2] > left[0] + 0.2, "left should be blue-dominant: {left:?}");
    assert!(right[0] > right[2] + 0.2, "right should be red-dominant: {right:?}");
    assert!(lin.px.iter().all(|p| p.iter().all(|&v| (0.0..=1.05).contains(&v))));
}

#[test]
fn merge_reduces_noise_variance_measurably() {
    // Large enough for the ORB pyramid's 31px edge threshold to leave an
    // interior (registration needs >~72px per side to see any keypoints).
    let (w, h) = (160, 120);
    let frames: Vec<RawFrame> = (0..6).map(|i| synthetic(w, h, 100 + i * 17, 28, 64, 1023)).collect();
    let single = demosaic(&frames[0]);
    let clean = demosaic(&synthetic(w, h, 999, 0, 64, 1023));
    let merged = merge_raw_linear(&frames).expect("merge");
    assert_eq!((merged.w, merged.h), (w, h));
    // Interior patch (avoid demosaic borders): merging 6 frames should
    // cut noise power by well over 2x even after demosaic correlation.
    let v_single = green_noise_power(&single, &clean, 60, 45, 32);
    let v_merged = green_noise_power(&merged, &clean, 60, 45, 32);
    assert!(v_single > 0.0, "synthetic noise must be present");
    assert!(
        v_merged < v_single * 0.45,
        "merge should at least halve variance: single {v_single:.6} merged {v_merged:.6}"
    );
    // And the merged mean stays on the scene, not dragged toward black.
    let mean: f64 = merged.px.iter().map(|p| p[1] as f64).sum::<f64>() / merged.px.len() as f64;
    assert!((0.25..0.55).contains(&mean), "merged green mean on scene: {mean}");
}

#[test]
fn merge_rejects_mismatched_and_empty_bursts() {
    assert!(merge_raw_linear(&[]).is_none());
    let a = synthetic(16, 16, 1, 0, 64, 1023);
    let b = synthetic(8, 16, 1, 0, 64, 1023);
    assert!(merge_raw_linear(&[a, b]).is_none(), "size mismatch refuses, never guesses");
}

#[test]
fn tone_map_is_monotonic_and_opaque() {
    let frame = synthetic(16, 16, 3, 0, 64, 1023);
    let rgba = merge_raw_burst(&[frame], 1.0).expect("burst");
    assert!(rgba.px.chunks_exact(4).all(|p| p[3] == 255));
    let dark = tone_map(&LinearRgb { w: 1, h: 1, px: vec![[0.05, 0.05, 0.05]] }, 1.0);
    let bright = tone_map(&LinearRgb { w: 1, h: 1, px: vec![[0.5, 0.5, 0.5]] }, 1.0);
    assert!(bright.px[0] > dark.px[0], "tone map preserves order");
}

#[test]
fn cfa_codes_match_android_constants() {
    // CameraMetadata: RGGB=0, GRBG=1, GBRG=2, BGGR=3, MONO=4 (API 31+ values
    // for MONO differ by platform level; the first four are stable).
    assert_eq!(Cfa::from_code(0), Some(Cfa::Rggb));
    assert_eq!(Cfa::from_code(3), Some(Cfa::Bggr));
    assert_eq!(Cfa::from_code(99), None);
}
