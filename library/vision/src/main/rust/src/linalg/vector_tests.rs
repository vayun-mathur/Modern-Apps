use crate::linalg::{Matrix3, Vector3};
fn eq(v: Vector3<f64>, x: f64, y: f64, z: f64) { assert_eq!((v.x, v.y, v.z), (x, y, z)); }
#[test] fn zeros_and_new() { eq(Vector3::zeros(), 0.0, 0.0, 0.0); eq(Vector3::new(1.0, -2.0, 3.5), 1.0, -2.0, 3.5); }
#[test] fn norm_pythagorean() {
    assert_eq!(Vector3::new(3.0, 4.0, 0.0).norm(), 5.0);
    assert_eq!(Vector3::new(0.0, 0.0, 0.0).norm(), 0.0);
    assert!((Vector3::new(1.0, 2.0, 2.0).norm() - 3.0).abs() < 1e-15);
    assert!((Vector3::new(-2.0, 3.0, 6.0).norm() - 7.0).abs() < 1e-15);
}
#[test] fn dot_hand_computed() {
    assert_eq!(Vector3::new(1.0, 2.0, 3.0).dot(&Vector3::new(4.0, 5.0, 6.0)), 32.0);
    assert_eq!(Vector3::new(1.0, 0.0, 0.0).dot(&Vector3::new(0.0, 1.0, 0.0)), 0.0);
    assert_eq!(Vector3::new(2.0, -1.0, 3.0).dot(&Vector3::new(2.0, -1.0, 3.0)), 14.0);
}
#[test] fn cross_basis_and_general() {
    eq(Vector3::new(1.0,0.0,0.0).cross(&Vector3::new(0.0,1.0,0.0)), 0.0, 0.0, 1.0);
    eq(Vector3::new(0.0,1.0,0.0).cross(&Vector3::new(1.0,0.0,0.0)), 0.0, 0.0, -1.0);
    // (3*7-4*6, 4*5-2*7, 2*6-3*5) = (-3, 6, -3)
    eq(Vector3::new(2.0,3.0,4.0).cross(&Vector3::new(5.0,6.0,7.0)), -3.0, 6.0, -3.0);
    let a = Vector3::new(1.0, 2.0, 3.0); let b = Vector3::new(-2.0, 0.0, 1.0);
    assert_eq!(a.cross(&b).dot(&a), 0.0); assert_eq!(a.cross(&b).dot(&b), 0.0);
}
#[test] fn scalar_ops() {
    eq(Vector3::new(1.0, -2.0, 4.0) * 2.0, 2.0, -4.0, 8.0);
    eq(Vector3::new(2.0, -4.0, 8.0) / 2.0, 1.0, -2.0, 4.0);
    let mut v = Vector3::new(8.0, 4.0, 2.0); v /= 2.0; eq(v, 4.0, 2.0, 1.0);
}
#[test] fn add_sub_neg_assign() {
    eq(Vector3::new(1.0,2.0,3.0) + Vector3::new(4.0,5.0,6.0), 5.0, 7.0, 9.0);
    eq(Vector3::new(4.0,5.0,6.0) - Vector3::new(1.0,2.0,3.0), 3.0, 3.0, 3.0);
    eq(-Vector3::new(1.0,-2.0,3.0), -1.0, 2.0, -3.0);
    let mut v = Vector3::new(1.0,1.0,1.0); v += Vector3::new(1.0,2.0,3.0); eq(v, 2.0, 3.0, 4.0);
}
#[test] fn indexing_read_write() {
    let mut v = Vector3::new(7.0, 8.0, 9.0);
    assert_eq!((v[0], v[1], v[2]), (7.0, 8.0, 9.0));
    v[1] = -8.0; assert_eq!(v.y, -8.0);
}
#[test] #[should_panic] fn indexing_out_of_range_panics() { let _ = Vector3::zeros()[3]; }
#[test] fn transpose_and_outer_product() {
    let v = Vector3::new(1.0, 2.0, 3.0);
    let m: Matrix3<f64> = v * v.transpose();
    // outer(v,v) = [[1,2,3],[2,4,6],[3,6,9]]
    let want = [1.0,2.0,3.0, 2.0,4.0,6.0, 3.0,6.0,9.0];
    for r in 0..3 { for c in 0..3 { assert_eq!(m[(r,c)], want[r*3+c]); } }
    let w: Matrix3<f64> = Vector3::new(1.0,2.0,3.0) * Vector3::new(4.0,5.0,6.0).transpose();
    assert_eq!((w[(0,0)], w[(0,2)], w[(2,0)], w[(2,2)]), (4.0, 6.0, 12.0, 18.0));
}
#[test] fn into_owned_is_identity() { eq(Vector3::new(1.0,2.0,3.0).into_owned(), 1.0, 2.0, 3.0); }
