//! Host-side math edges for the panorama stitcher.
//!
//! The crate's inline suite in `src/lib.rs` covers the full stitch and estimate paths against
//! `testdata/*.jpg`. These tests cover the pure math reachable through the public re-exports
//! (`camera`, `geometry`, `linalg`, `imgbuf`) with no JPEGs and no Android: Rodrigues round
//! trips, the intrinsic matrix, a translation homography, and image sampling behaviour.

use camera_stitch::camera::{mat_to_rodrigues, rodrigues_to_mat, CameraParams};
use camera_stitch::geometry::{find_homography_ransac, transfer_inliers};
use camera_stitch::imgbuf::{to_gray, Rgba};
use camera_stitch::linalg::{Matrix3, Vector3};

fn approx(a: f64, b: f64, tol: f64) -> bool {
    (a - b).abs() <= tol
}

#[test]
fn rodrigues_round_trips_small_rotation() {
    let rvec = Vector3::new(0.1, 0.2, 0.3);
    let mat = rodrigues_to_mat(rvec);
    let back = mat_to_rodrigues(&mat);
    assert!(approx(back.x, rvec.x, 1e-9), "x: {}", back.x);
    assert!(approx(back.y, rvec.y, 1e-9), "y: {}", back.y);
    assert!(approx(back.z, rvec.z, 1e-9), "z: {}", back.z);
}

#[test]
fn rodrigues_identity_stays_identity() {
    let mat = rodrigues_to_mat(Vector3::zeros());
    for r in 0..3 {
        for c in 0..3 {
            let want = if r == c { 1.0 } else { 0.0 };
            assert!(approx(mat[(r, c)], want, 1e-12), "m[{r}{c}]={}", mat[(r, c)]);
        }
    }
    let back = mat_to_rodrigues(&Matrix3::identity());
    assert!(back.norm() < 1e-9, "norm {}", back.norm());
}

#[test]
fn camera_k_matrix_has_expected_shape() {
    let params = CameraParams {
        focal: 800.0,
        aspect: 1.0,
        ppx: 320.0,
        ppy: 240.0,
        r: Matrix3::identity(),
    };
    let k = params.k();
    assert!(approx(k[(0, 0)], 800.0, 1e-9));
    assert!(approx(k[(1, 1)], 800.0, 1e-9));
    assert!(approx(k[(0, 2)], 320.0, 1e-9));
    assert!(approx(k[(1, 2)], 240.0, 1e-9));
    assert!(approx(k[(2, 2)], 1.0, 1e-12));
    assert!(approx(k[(0, 1)], 0.0, 1e-12));
}

#[test]
fn homography_recovers_translation() {
    let a = vec![(0.0f32, 0.0f32), (10.0, 0.0), (10.0, 10.0), (0.0, 10.0), (5.0, 5.0), (15.0, 5.0)];
    let b: Vec<(f32, f32)> = a.iter().map(|(x, y)| (x + 3.0, y + 4.0)).collect();
    let (h, inliers) = find_homography_ransac(&a, &b, 200, 2.0).expect("homography");
    assert!(inliers >= 4, "inliers {inliers}");
    assert!(approx(h[(0, 2)], 3.0, 0.5), "tx {}", h[(0, 2)]);
    assert!(approx(h[(1, 2)], 4.0, 0.5), "ty {}", h[(1, 2)]);
    assert_eq!(transfer_inliers(&h, &a, &b, 2.0).len(), inliers);
}

#[test]
fn rgba_sample_resize_and_gray_behave() {
    let mut img = Rgba::new(4, 4);
    img.set(1, 1, [255, 0, 0, 255]);
    assert_eq!(img.get(1, 1), [255, 0, 0, 255]);
    assert!(img.sample(-1.0, 0.0).is_none());
    assert!(img.sample(99.0, 99.0).is_none());
    let clear = Rgba::new(2, 2);
    assert!(clear.sample(0.5, 0.5).is_none(), "fully transparent has no data");
    let small = img.resized(2, 2);
    assert_eq!((small.w, small.h), (2, 2));
    let gray = to_gray(&img);
    assert_eq!((gray.w, gray.h), (4, 4));
    assert_eq!(gray.px[4 + 1], ((255u32 * 77) >> 8) as u8);
}
