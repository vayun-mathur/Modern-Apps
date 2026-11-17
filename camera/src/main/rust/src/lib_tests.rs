//! Tests for lib.rs private helpers: the GPU estimate blob layout and the
//! JPEG encode round trip.

use super::*;
use crate::imgbuf::Rgba;
use crate::linalg::Matrix3;

fn est(cams: Vec<stitch::FrameCam>) -> stitch::Estimate {
    stitch::Estimate { canvas_w: 1200, canvas_h: 700, u0: -12.5, v0: 3.25, scale: 640.0, cams }
}

fn frame_cam(idx: usize, focal: f64, gain: f32) -> stitch::FrameCam {
    stitch::FrameCam {
        original_index: idx,
        focal,
        ppx: 400.0,
        ppy: 300.0,
        r: [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0],
        gain,
    }
}

fn u32_at(b: &[u8], o: usize) -> u32 {
    u32::from_le_bytes(b[o..o + 4].try_into().unwrap())
}
fn f64_at(b: &[u8], o: usize) -> f64 {
    f64::from_le_bytes(b[o..o + 8].try_into().unwrap())
}

#[test]
fn serialize_estimate_empty_cameras_is_header_only() {
    let blob = serialize_estimate(&est(vec![]));
    assert_eq!(blob.len(), 36);
    assert_eq!(u32_at(&blob, 0), 1200);
    assert_eq!(u32_at(&blob, 4), 700);
    assert_eq!(f64_at(&blob, 8), -12.5);
    assert_eq!(f64_at(&blob, 16), 3.25);
    assert_eq!(f64_at(&blob, 24), 640.0);
    assert_eq!(u32_at(&blob, 32), 0);
}

#[test]
fn serialize_estimate_per_camera_record_layout() {
    let blob = serialize_estimate(&est(vec![frame_cam(7, 555.5, 1.25), frame_cam(2, 600.0, 0.75)]));
    let rec = 4 + 8 * 12 + 4;
    assert_eq!(blob.len(), 36 + 2 * rec);
    assert_eq!(u32_at(&blob, 32), 2);
    // First record: index, focal, ppx, ppy, R row-major, gain.
    assert_eq!(u32_at(&blob, 36), 7);
    assert_eq!(f64_at(&blob, 40), 555.5);
    assert_eq!(f64_at(&blob, 48), 400.0);
    assert_eq!(f64_at(&blob, 56), 300.0);
    assert_eq!(f64_at(&blob, 64), 1.0, "R[0]");
    assert_eq!(f64_at(&blob, 72), 0.0, "R[1]");
    assert_eq!(f64_at(&blob, 128), 1.0, "R[8]");
    let gain = f32::from_le_bytes(blob[136..140].try_into().unwrap());
    assert_eq!(gain, 1.25);
    // Second record starts immediately after.
    assert_eq!(u32_at(&blob, 36 + rec), 2);
    assert_eq!(f64_at(&blob, 36 + rec + 4), 600.0);
}

#[test]
fn serialize_estimate_preserves_rotation_entry_order() {
    let mut c = frame_cam(0, 500.0, 1.0);
    c.r = [0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0];
    let blob = serialize_estimate(&est(vec![c]));
    for j in 0..9 {
        assert_eq!(f64_at(&blob, 64 + 8 * j), j as f64, "R[{j}] order");
    }
    let _ = Matrix3::<f64>::identity(); // keep import used if layout changes
}

#[test]
fn encode_jpeg_round_trips_dimensions_and_rough_colour() {
    let mut img = Rgba::new(16, 12);
    for y in 0..12 {
        for x in 0..16 {
            img.set(x, y, [200, 40, 30, 255]);
        }
    }
    let bytes = encode_jpeg(&img, 95).expect("encode");
    assert!(bytes.len() > 20 && bytes[0] == 0xFF && bytes[1] == 0xD8, "JPEG SOI marker");
    let back = Rgba::from_jpeg(&bytes).expect("decode own JPEG");
    assert_eq!((back.w, back.h), (16, 12));
    let centre = back.get(8, 6);
    assert!((centre[0] as i32 - 200).abs() < 8, "red drifted: {centre:?}");
    assert!((centre[1] as i32 - 40).abs() < 8);
    assert!((centre[2] as i32 - 30).abs() < 8);
}

#[test]
fn encode_jpeg_drops_alpha_but_keeps_rgb() {
    let mut img = Rgba::new(4, 4);
    for y in 0..4 {
        for x in 0..4 {
            img.set(x, y, [10, 220, 90, 0]); // alpha 0, RGB still encoded
        }
    }
    let bytes = encode_jpeg(&img, 100).expect("encode");
    let back = Rgba::from_jpeg(&bytes).expect("decode");
    let p = back.get(2, 2);
    assert!((p[1] as i32 - 220).abs() < 10, "green kept despite alpha: {p:?}");
}

#[test]
fn next_handle_is_strictly_increasing() {
    let a = next_handle();
    let b = next_handle();
    assert!(b > a, "handles must increase: {a} then {b}");
}
