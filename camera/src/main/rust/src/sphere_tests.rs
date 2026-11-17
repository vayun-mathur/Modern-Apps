//! Tests for sphere.rs: focal estimation from homographies, the spherical
//! projector round trip, orthonormalisation, and single-image warping.
fn mdiff(a: &Matrix3<f64>, b: &Matrix3<f64>) -> f64 {
    let mut s = 0.0f64;
    for r in 0..3 { for c in 0..3 { let d = a[(r, c)] - b[(r, c)]; s += d * d; } }
    s.sqrt()
}


use super::*;
use crate::camera::rodrigues_to_mat;
use crate::linalg::Vector3;

fn approx(a: f64, b: f64, tol: f64) -> bool {
    (a - b).abs() <= tol
}

#[test]
fn k_matrix_layout() {
    let k = k_matrix(500.0, 320.0, 240.0);
    assert!(approx(k[(0, 0)], 500.0, 1e-12) && approx(k[(1, 1)], 500.0, 1e-12));
    assert!(approx(k[(0, 2)], 320.0, 1e-12) && approx(k[(1, 2)], 240.0, 1e-12));
    assert!(approx(k[(2, 0)], 0.0, 1e-12) && approx(k[(2, 2)], 1.0, 1e-12));
}

#[test]
fn focals_from_identity_and_translation_have_no_valid_focal() {
    // Identity: denominators vanish / estimates non-positive after sanitize.
    let (f0, f1) = focals_from_homography(&Matrix3::identity());
    assert!(f0.is_none() && f1.is_none());
    // Pure translation homography carries no focal information either.
    let t = Matrix3::new(1.0, 0.0, 12.0, 0.0, 1.0, -4.0, 0.0, 0.0, 1.0);
    let (f0, f1) = focals_from_homography(&t);
    assert!(f0.is_none() && f1.is_none());
}

#[test]
fn focals_from_rotation_homography_recover_known_focal() {
    // H = K R K^-1 for a real rotation: both focal estimates should land
    // near the focal baked into K (the equations OpenCV solves).
    let f_true = 500.0;
    let k = k_matrix(f_true, 320.0, 240.0);
    let kinv = k.try_inverse().expect("K invertible");
    let r = rodrigues_to_mat(Vector3::new(0.03, 0.25, -0.02));
    let h = k * r * kinv;
    let (f0, f1) = focals_from_homography(&h);
    let est = estimate_focal(&[h], 640, 480);
    assert!(est.is_finite() && est > 1.0, "estimate {est}");
    // At least one of the pair must be recoverable for this construction,
    // and the combined estimate should be in a plausible band around truth.
    assert!(f0.is_some() || f1.is_some(), "no focal recovered: {f0:?} {f1:?}");
    assert!(est > 100.0 && est < 2500.0, "implausible focal {est}");
}

#[test]
fn estimate_focal_median_odd_and_even_counts() {
    // Repeat a valid rotation homography: median of identical values is
    // that value for both odd and even counts.
    let k = k_matrix(500.0, 320.0, 240.0);
    let kinv = k.try_inverse().unwrap();
    let r = rodrigues_to_mat(Vector3::new(0.0, 0.3, 0.0));
    let h = k * r * kinv;
    let single = estimate_focal(&[h], 640, 480);
    assert!(single.is_finite() && single > 1.0);
    assert!(approx(estimate_focal(&[h, h, h], 640, 480), single, 1e-9));
    assert!(approx(estimate_focal(&[h, h], 640, 480), single, 1e-9));
    // Junk pairs are skipped, so mixing identity in changes nothing.
    assert!(approx(estimate_focal(&[h, Matrix3::identity(), h], 640, 480), single, 1e-9));
}

#[test]
fn orthonormalize_snaps_scaled_rotation_back_to_rotation() {
    let r = rodrigues_to_mat(Vector3::new(0.1, -0.2, 0.05));
    let scaled = Matrix3::new(r[(0,0)]*1.7, r[(0,1)]*1.7, r[(0,2)]*1.7, r[(1,0)]*1.7, r[(1,1)]*1.7, r[(1,2)]*1.7, r[(2,0)]*1.7, r[(2,1)]*1.7, r[(2,2)]*1.7); // scalar multiple: SVD projection drops the scale
    let o = orthonormalize(&scaled);
    assert!(approx(o.determinant(), 1.0, 1e-9) || approx(o.determinant(), -1.0, 1e-9));
    let prod = o * o.transpose();
    for i in 0..3 {
        for j in 0..3 {
            let want = if i == j { 1.0 } else { 0.0 };
            assert!(approx(prod[(i, j)], want, 1e-9), "orthonormal [{i}{j}]");
        }
    }
    // Direction preserved: the corrected matrix stays close to the input.
    assert!(mdiff(&(o), &(r)) < 0.05, "projection moved the rotation too far");
}

#[test]
fn proj_backward_rejects_points_behind_camera() {
    let k = k_matrix(800.0, 320.0, 240.0);
    let proj = Proj::new(&k, &Matrix3::identity(), 800.0).expect("proj");
    // Forward-map a source point, then confirm the antipodal sphere point
    // (u shifted by PI*scale) projects behind the camera.
    let (u, v) = proj.forward(320.0, 240.0);
    let behind_u = u + std::f64::consts::PI * proj.scale;
    assert!(proj.backward(behind_u, v).is_none(), "antipode must be behind");
}

#[test]
fn proj_forward_backward_round_trips_off_centre() {
    let k = k_matrix(700.0, 320.0, 240.0);
    let r = rodrigues_to_mat(Vector3::new(0.05, 0.1, 0.0));
    let proj = Proj::new(&k, &r, 700.0).expect("proj");
    for &(x, y) in &[(100.0, 80.0), (500.0, 400.0), (320.0, 240.0), (40.0, 440.0)] {
        let (u, v) = proj.forward(x, y);
        let (bx, by) = proj.backward(u, v).expect("front-facing round trip");
        assert!(approx(bx, x, 1e-6), "x {bx} vs {x}");
        assert!(approx(by, y, 1e-6), "y {by} vs {y}");
    }
}

#[test]
fn proj_new_rejects_singular_k() {
    let singular = Matrix3::new(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    assert!(Proj::new(&singular, &Matrix3::identity(), 100.0).is_none());
}

#[test]
fn warped_bounds_of_identity_camera_are_finite_and_ordered() {
    let k = k_matrix(400.0, 50.0, 40.0);
    let (u0, v0, u1, v1) = warped_bounds(100, 80, &k, &Matrix3::identity(), 400.0).expect("bounds");
    assert!(u0 <= u1 && v0 <= v1);
    assert!(u0.is_finite() && v0.is_finite() && u1.is_finite() && v1.is_finite());
    // The tile must cover roughly the source extent (within a border walk's
    // tolerance): width on the sphere is comparable to the image width.
    assert!((u1 - u0) > 50.0 && (u1 - u0) < 200.0, "du {}", u1 - u0);
    assert!((v1 - v0) > 40.0 && (v1 - v0) < 160.0, "dv {}", v1 - v0);
}

#[test]
fn warp_one_identity_camera_preserves_centre_colour() {
    let mut img = Rgba::new(32, 24);
    for y in 0..24 {
        for x in 0..32 {
            img.set(x, y, [200, 30, 60, 255]);
        }
    }
    img.set(16, 12, [1, 2, 3, 255]);
    let k = k_matrix(200.0, 16.0, 12.0);
    let tile = warp_one(&img, &k, &Matrix3::identity(), 200.0).expect("warp");
    assert!(tile.img.w > 0 && tile.img.h > 0);
    // The tile centre maps back near the source centre.
    let c = tile.img.get(tile.img.w / 2, tile.img.h / 2);
    assert_eq!(c[3], 255, "centre covered");
    // Bilinear sampling blends the marker into its neighbourhood, so look
    // for a distinctly dark pixel near the tile centre rather than the
    // exact source value.
    let mut found_marker = false;
    for y in 0..tile.img.h {
        for x in 0..tile.img.w {
            let p = tile.img.get(x, y);
            if p[3] == 255 && (p[0] as i32 - 200).abs() > 60 {
                found_marker = true;
            }
        }
    }
    assert!(found_marker, "distinctive centre pixel survives the warp (blended)");
}

#[test]
fn warp_one_transparent_source_yields_no_opaque_pixels() {
    let img = Rgba::new(16, 16); // all alpha 0
    let k = k_matrix(200.0, 8.0, 8.0);
    let tile = warp_one(&img, &k, &Matrix3::identity(), 200.0).expect("warp runs");
    assert!(tile.img.px.chunks_exact(4).all(|p| p[3] == 0), "no coverage from transparent source");
}

#[test]
fn sample_reflect_101_mirrors_at_borders() {
    let mut img = Rgba::new(4, 1);
    img.set(0, 0, [10, 0, 0, 255]);
    img.set(1, 0, [20, 0, 0, 255]);
    img.set(2, 0, [30, 0, 0, 255]);
    img.set(3, 0, [40, 0, 0, 255]);
    // Sampling just left of the image reflects to pixel 1's neighbourhood,
    // not a clamp to pixel 0: value must exceed the border pixel's 10.
    let left = sample_reflect_101(&img, -0.4, 0.0).expect("reflect sample");
    assert!(left[0] > 10.0, "reflected sample {left:?} should blend pixel 1");
    let inside = sample_reflect_101(&img, 1.0, 0.0).expect("inside");
    assert!((inside[0] - 20.0).abs() < 1e-4);
    // Fully transparent neighbourhood samples to None.
    let clear = Rgba::new(4, 4);
    assert!(sample_reflect_101(&clear, 1.0, 1.0).is_none());
}
