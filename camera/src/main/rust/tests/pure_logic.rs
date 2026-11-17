//! Synthetic-input tests for the crate's pure, deterministic logic.
//!
//! Everything here builds tiny in-memory images / matrices — no JPEG fixtures —
//! and exercises the public surface that `src/lib.rs` re-exports or declares.

use camera_stitch::camera::{rodrigues_to_mat, CameraParams};
use camera_stitch::features::{Features, KeyPoint};
use camera_stitch::imgbuf::Rgba;
use camera_stitch::linalg::{Matrix3, Vector3};

// The stitch pipeline modules are crate-private, so integration tests reach
// the pure helpers through the crate root's internal paths only where they
// are `pub`. That means most pipeline coverage lives in sibling
// `#[cfg(test)]` modules next to the code; this file covers what the crate
// publicly exposes: `camera`, `imgbuf`, `linalg`, `geometry`, `features`.

fn approx(a: f64, b: f64, tol: f64) -> bool {
    (a - b).abs() <= tol
}

fn solid(w: usize, h: usize, c: [u8; 4]) -> Rgba {
    let mut img = Rgba::new(w, h);
    for y in 0..h {
        for x in 0..w {
            img.set(x, y, c);
        }
    }
    img
}

// ---------------------------------------------------------------- camera ---

#[test]
fn camera_k_uses_focal_times_aspect_for_y() {
    let p = CameraParams {
        focal: 600.0,
        aspect: 0.5,
        ppx: 10.0,
        ppy: 20.0,
        r: Matrix3::identity(),
    };
    let k = p.k();
    assert!(approx(k[(0, 0)], 600.0, 1e-12));
    assert!(approx(k[(1, 1)], 300.0, 1e-12), "fy = focal*aspect");
    assert!(approx(k[(0, 2)], 10.0, 1e-12));
    assert!(approx(k[(1, 2)], 20.0, 1e-12));
}

#[test]
fn rodrigues_quarter_turn_about_z_maps_x_to_y() {
    let r = rodrigues_to_mat(Vector3::new(0.0, 0.0, std::f64::consts::FRAC_PI_2));
    let v = r * Vector3::new(1.0, 0.0, 0.0);
    assert!(approx(v.x, 0.0, 1e-12), "x {}", v.x);
    assert!(approx(v.y, 1.0, 1e-12), "y {}", v.y);
    assert!(approx(v.z, 0.0, 1e-12));
    // A rotation matrix is orthonormal with determinant +1.
    assert!(approx(r.determinant(), 1.0, 1e-12));
    let should_be_i = r * r.transpose();
    for i in 0..3 {
        for j in 0..3 {
            let want = if i == j { 1.0 } else { 0.0 };
            assert!(approx(should_be_i[(i, j)], want, 1e-12), "[{i}{j}]");
        }
    }
}

#[test]
fn rodrigues_large_angle_round_trips_through_matrix() {
    // 180° about X: mat_to_rodrigues takes the trace<=0 branch.
    let rvec = Vector3::new(std::f64::consts::PI, 0.0, 0.0);
    let m = rodrigues_to_mat(rvec);
    assert!(approx(m[(1, 1)], -1.0, 1e-12));
    assert!(approx(m[(2, 2)], -1.0, 1e-12));
    let back = camera_stitch::camera::mat_to_rodrigues(&m);
    assert!(approx(back.norm(), std::f64::consts::PI, 1e-9), "norm {}", back.norm());
}

// ---------------------------------------------------------------- imgbuf ---

#[test]
fn rgba_from_bytes_with_exact_size_round_trips() {
    // from_bytes requires exactly w*h*4 bytes (enforced by debug_assert).
    let img = Rgba::from_bytes(2, 1, vec![1, 2, 3, 4, 5, 6, 7, 8]);
    assert_eq!(img.get(0, 0), [1, 2, 3, 4]);
    assert_eq!(img.get(1, 0), [5, 6, 7, 8]);
    let zero = Rgba::from_bytes(0, 0, vec![]);
    assert_eq!((zero.w, zero.h, zero.px.len()), (0, 0, 0));
}

#[test]
fn rgba_get_reads_written_pixels() {
    let img = solid(2, 2, [7, 8, 9, 255]);
    assert_eq!(img.get(0, 0), [7, 8, 9, 255]);
    assert_eq!(img.get(1, 1), [7, 8, 9, 255]);
}

#[test]
#[should_panic]
fn rgba_get_out_of_bounds_panics() {
    // Documented current behaviour: get() indexes unchecked, so callers
    // must guard coordinates (all in-crate callers do).
    let img = solid(2, 2, [7, 8, 9, 255]);
    let _ = img.get(5, 0);
}

#[test]
fn rgba_sample_bilinear_midpoint_averages_neighbours() {
    let mut img = Rgba::new(2, 1);
    img.set(0, 0, [0, 0, 0, 255]);
    img.set(1, 0, [100, 200, 40, 255]);
    let mid = img.sample(0.5, 0.0).expect("opaque sample");
    assert!((mid[0] - 50.0).abs() < 1e-4, "r {}", mid[0]);
    assert!((mid[1] - 100.0).abs() < 1e-4, "g {}", mid[1]);
    assert!((mid[2] - 20.0).abs() < 1e-4, "b {}", mid[2]);
    // Exact pixel centre returns that pixel.
    let c = img.sample(1.0, 0.0).expect("centre");
    assert!((c[0] - 100.0).abs() < 1e-4);
}

#[test]
fn rgba_sample_transparency_rules() {
    let mut img = Rgba::new(3, 3);
    img.set(1, 1, [255, 255, 255, 255]);
    assert!(img.sample(1.0, 1.0).is_some());
    // At the corner the bilinear neighbourhood *includes* the opaque centre
    // as one corner, so sample returns Some even though the weighted value
    // there is fully transparent black.
    let corner = img.sample(0.0, 0.0).expect("neighbourhood has one opaque corner");
    assert_eq!(corner, [0.0, 0.0, 0.0, 0.0]);
    // A fully transparent image samples to None everywhere in bounds.
    let clear = Rgba::new(3, 3);
    assert!(clear.sample(1.0, 1.0).is_none());
    assert!(clear.sample(0.0, 0.0).is_none());
}

#[test]
fn rgba_resized_to_same_size_is_identity_and_downscale_averages() {
    let img = solid(4, 4, [80, 40, 20, 255]);
    let same = img.resized(4, 4);
    assert_eq!(same.px, img.px);
    let small = img.resized(2, 2);
    assert!(small.px.chunks_exact(4).all(|p| p == [80, 40, 20, 255]));
    let one = img.resized(1, 1);
    assert_eq!(one.get(0, 0), [80, 40, 20, 255]);
}

#[test]
fn to_gray_uses_integer_luma_weights() {
    let mut img = Rgba::new(3, 1);
    img.set(0, 0, [255, 255, 255, 255]);
    img.set(1, 0, [255, 0, 0, 255]);
    img.set(2, 0, [0, 0, 255, 255]);
    let g = camera_stitch::imgbuf::to_gray(&img);
    assert_eq!(g.px[0], 255);
    assert_eq!(g.px[1], ((255u32 * 77) >> 8) as u8);
    assert_eq!(g.px[2], ((255u32 * 29) >> 8) as u8);
}

#[test]
fn gray_resize_and_blur_keep_constant_images_constant() {
    let mut g = camera_stitch::imgbuf::Gray::new(6, 6);
    for v in g.px.iter_mut() {
        *v = 90;
    }
    let blurred = g.gaussian_blur_3x3();
    assert!(blurred.px.iter().all(|&v| v == 90));
    let small = g.resized(3, 3);
    assert_eq!((small.w, small.h), (3, 3));
    assert!(small.px.iter().all(|&v| v == 90));
}

#[test]
#[should_panic]
fn gray_at_out_of_bounds_panics() {
    // Like Rgba::get, Gray::at indexes unchecked.
    let g = camera_stitch::imgbuf::Gray::new(4, 4);
    let _ = g.at(100, 100);
}

// -------------------------------------------------------------- features ---

fn feats_with_desc(descs: Vec<[u8; 32]>) -> Features {
    let n = descs.len();
    Features {
        kps: (0..n)
            .map(|i| KeyPoint { x: i as f32 * 10.0, y: 5.0, angle: 0.0, score: 1.0 })
            .collect(),
        desc: descs,
        w: 100,
        h: 50,
    }
}

#[test]
fn match_features_empty_inputs_match_nothing() {
    let empty = feats_with_desc(vec![]);
    let one = feats_with_desc(vec![[0u8; 32]]);
    assert!(camera_stitch::features::match_features(&empty, &one, 0.8).is_empty());
    assert!(camera_stitch::features::match_features(&one, &empty, 0.8).is_empty());
}

#[test]
fn match_features_identical_descriptor_wins_over_distant_ones() {
    let target = [0xAAu8; 32];
    let mut far = [0u8; 32];
    far[0] = 0xFF; // some bits differ from both
    let a = feats_with_desc(vec![target]);
    // best = identical (dist 0), second = far (dist > 0): ratio test passes.
    let b = feats_with_desc(vec![far, target, [0x55u8; 32]]);
    let m = camera_stitch::features::match_features(&a, &b, 0.8);
    assert_eq!(m, vec![(0, 1)]);
}

#[test]
fn match_features_ratio_rejects_ambiguous_equidistant_candidates() {
    // Two identical candidates: best == second, so best < ratio*second fails.
    let d = [3u8; 32];
    let a = feats_with_desc(vec![d]);
    let b = feats_with_desc(vec![d, d]);
    assert!(camera_stitch::features::match_features(&a, &b, 0.8).is_empty());
}

#[test]
fn detect_and_describe_on_blank_and_tiny_images_is_empty_but_safe() {
    let blank = camera_stitch::imgbuf::Gray::new(64, 64);
    let f = camera_stitch::features::detect_and_describe(&blank, 100, 20);
    assert!(f.kps.is_empty() && f.desc.is_empty());
    assert_eq!((f.w, f.h), (64, 64));
    let tiny = camera_stitch::imgbuf::Gray::new(2, 2);
    let f2 = camera_stitch::features::detect_and_describe(&tiny, 100, 20);
    assert!(f2.kps.len() <= 100);
    assert_eq!(f2.kps.len(), f2.desc.len(), "one descriptor per keypoint");
}

// -------------------------------------------------------------- geometry ---

#[test]
fn transfer_inliers_with_identity_maps_points_to_themselves() {
    let pts: Vec<(f32, f32)> = vec![(0.0, 0.0), (10.0, 3.0), (-5.0, 7.0)];
    let inl = camera_stitch::geometry::transfer_inliers(&Matrix3::identity(), &pts, &pts, 1.0);
    assert_eq!(inl, vec![0, 1, 2]);
    // A destination 100px away is not an inlier of the identity.
    let far: Vec<(f32, f32)> = pts.iter().map(|(x, y)| (x + 100.0, *y)).collect();
    assert!(camera_stitch::geometry::transfer_inliers(&Matrix3::identity(), &pts, &far, 1.0).is_empty());
}

#[test]
fn homography_needs_at_least_four_points() {
    let a = vec![(0.0f32, 0.0f32), (1.0, 0.0), (0.0, 1.0)];
    assert!(camera_stitch::geometry::find_homography_ransac(&a, &a, 50, 1.0).is_none());
    assert!(camera_stitch::geometry::find_homography_ransac(&[], &[], 50, 1.0).is_none());
}

#[test]
fn homography_recovers_pure_scale_about_origin() {
    let a: Vec<(f32, f32)> = vec![
        (1.0, 1.0), (4.0, 1.0), (4.0, 4.0), (1.0, 4.0), (2.0, 3.0), (7.0, 2.0), (3.0, 8.0),
    ];
    let b: Vec<(f32, f32)> = a.iter().map(|(x, y)| (2.0 * x, 2.0 * y)).collect();
    let (h, inl) = camera_stitch::geometry::find_homography_ransac(&a, &b, 200, 1.0).expect("h");
    assert!(inl >= 4, "inliers {inl}");
    assert!(approx(h[(0, 0)], 2.0, 0.05), "h00 {}", h[(0, 0)]);
    assert!(approx(h[(1, 1)], 2.0, 0.05), "h11 {}", h[(1, 1)]);
    assert!(approx(h[(0, 2)], 0.0, 0.05), "h02 {}", h[(0, 2)]);
}

// ---------------------------------------------------------------- linalg ---

#[test]
fn matrix3_inverse_undoes_multiplication() {
    let m = Matrix3::new(2.0, 0.5, 1.0, 0.0, 3.0, 0.25, 1.0, 0.0, 2.0);
    let inv = m.try_inverse().expect("invertible");
    let prod = m * inv;
    for i in 0..3 {
        for j in 0..3 {
            let want = if i == j { 1.0 } else { 0.0 };
            assert!(approx(prod[(i, j)], want, 1e-9), "[{i}{j}] {}", prod[(i, j)]);
        }
    }
    assert!(approx(m.determinant() * inv.determinant(), 1.0, 1e-9));
}

#[test]
fn singular_matrix_has_no_inverse_and_zero_determinant() {
    let m = Matrix3::new(1.0, 2.0, 3.0, 2.0, 4.0, 6.0, 0.0, 1.0, 0.0); // row1 = 2*row0
    assert!(approx(m.determinant(), 0.0, 1e-12));
    assert!(m.try_inverse().is_none());
}

#[test]
fn vector3_cross_dot_and_norm_are_consistent() {
    let x = Vector3::new(1.0, 0.0, 0.0);
    let y = Vector3::new(0.0, 1.0, 0.0);
    let z = x.cross(&y);
    assert!(approx(z.z, 1.0, 1e-12) && approx(z.x, 0.0, 1e-12));
    assert!(approx(x.dot(&y), 0.0, 1e-12));
    assert!(approx(Vector3::new(3.0, 4.0, 0.0).norm(), 5.0, 1e-12));
    assert!(approx(x.cross(&y).norm(), 1.0, 1e-12), "unit axes give unit cross");
}
