use crate::camera::{mat_to_rodrigues, rodrigues_to_mat, CameraParams};
use crate::linalg::{Matrix3, Vector3};
fn close(a: f64, b: f64, t: f64) { assert!((a-b).abs() <= t, "{a} vs {b}"); }
fn mat_close(m: &Matrix3<f64>, w: &[f64;9], t: f64) { for r in 0..3 { for c in 0..3 { close(m[(r,c)], w[r*3+c], t); } } }
#[test] fn intrinsics_k() {
    let cam = CameraParams { focal: 100.0, aspect: 2.0, ppx: 10.0, ppy: 20.0, r: Matrix3::identity() };
    mat_close(&cam.k(), &[100.0,0.0,10.0, 0.0,200.0,20.0, 0.0,0.0,1.0], 0.0);
    let cam = CameraParams { focal: 50.0, aspect: 1.0, ppx: 0.0, ppy: 0.0, r: Matrix3::identity() };
    mat_close(&cam.k(), &[50.0,0.0,0.0, 0.0,50.0,0.0, 0.0,0.0,1.0], 0.0);
}
#[test] fn rodrigues_zero_and_tiny_are_identity() {
    mat_close(&rodrigues_to_mat(Vector3::zeros()), &[1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0], 0.0);
    mat_close(&rodrigues_to_mat(Vector3::new(1e-13, 0.0, 0.0)), &[1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0], 0.0);
}
#[test] fn rodrigues_quarter_turns() {
    let h = std::f64::consts::FRAC_PI_2;
    // Rz(90°): x->y, y->-x
    mat_close(&rodrigues_to_mat(Vector3::new(0.0,0.0,h)), &[0.0,-1.0,0.0, 1.0,0.0,0.0, 0.0,0.0,1.0], 1e-12);
    // Rx(90°): y->z, z->-y
    mat_close(&rodrigues_to_mat(Vector3::new(h,0.0,0.0)), &[1.0,0.0,0.0, 0.0,0.0,-1.0, 0.0,1.0,0.0], 1e-12);
    // Ry(90°): z->x, x->-z
    mat_close(&rodrigues_to_mat(Vector3::new(0.0,h,0.0)), &[0.0,0.0,1.0, 0.0,1.0,0.0, -1.0,0.0,0.0], 1e-12);
}
#[test] fn rodrigues_half_turn_and_orthonormality() {
    let m = rodrigues_to_mat(Vector3::new(std::f64::consts::PI, 0.0, 0.0));
    mat_close(&m, &[1.0,0.0,0.0, 0.0,-1.0,0.0, 0.0,0.0,-1.0], 1e-12);
    close(m.determinant(), 1.0, 1e-12);
    let m = rodrigues_to_mat(Vector3::new(0.3, -0.5, 0.8));
    mat_close(&(m * m.transpose()), &[1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0], 1e-12);
    close(m.determinant(), 1.0, 1e-12);
}
#[test] fn rodrigues_roundtrip() {
    for v in [Vector3::new(0.1,0.2,0.3), Vector3::new(-0.5,0.4,0.1), Vector3::new(0.0,0.0,1.0), Vector3::new(1.0,-1.0,0.5)] {
        let back = mat_to_rodrigues(&rodrigues_to_mat(v));
        close(back.x, v.x, 1e-9); close(back.y, v.y, 1e-9); close(back.z, v.z, 1e-9);
    }
}
#[test] fn mat_to_rodrigues_identity_and_quarter() {
    let z = mat_to_rodrigues(&Matrix3::identity()); close(z.norm(), 0.0, 1e-12);
    let v = mat_to_rodrigues(&Matrix3::new(0.0,-1.0,0.0, 1.0,0.0,0.0, 0.0,0.0,1.0));
    close(v.x, 0.0, 1e-9); close(v.y, 0.0, 1e-9); close(v.z, std::f64::consts::FRAC_PI_2, 1e-9);
}
#[test] fn mat_to_rodrigues_pi_rotations_reconstruct() {
    for m in [Matrix3::new(1.0,0.0,0.0, 0.0,-1.0,0.0, 0.0,0.0,-1.0), Matrix3::new(-1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,-1.0), Matrix3::new(-1.0,0.0,0.0, 0.0,-1.0,0.0, 0.0,0.0,1.0)] {
        let v = mat_to_rodrigues(&m);
        close(v.norm(), std::f64::consts::PI, 1e-9);
        mat_close(&rodrigues_to_mat(v), &[m[(0,0)],m[(0,1)],m[(0,2)],m[(1,0)],m[(1,1)],m[(1,2)],m[(2,0)],m[(2,1)],m[(2,2)]], 1e-9);
    }
}
#[test] fn mat_to_rodrigues_scaled_input_is_orthonormalized() {
    // 2*Rz(90°) should still yield the Rz(90°) axis/angle via SVD polar factor
    let m = Matrix3::new(0.0,-2.0,0.0, 2.0,0.0,0.0, 0.0,0.0,2.0);
    let v = mat_to_rodrigues(&m);
    close(v.z, std::f64::consts::FRAC_PI_2, 1e-9);
}
