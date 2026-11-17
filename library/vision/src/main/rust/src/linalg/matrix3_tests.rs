use crate::linalg::{Matrix3, Vector3};
fn close(a: f64, b: f64) { assert!((a-b).abs() < 1e-12, "{a} vs {b}"); }
fn mat_close(m: &Matrix3<f64>, want: &[f64;9]) { for r in 0..3 { for c in 0..3 { close(m[(r,c)], want[r*3+c]); } } }
#[test] fn zeros_identity_new_index() {
    mat_close(&Matrix3::zeros(), &[0.0;9]);
    mat_close(&Matrix3::identity(), &[1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0]);
    let mut m = Matrix3::new(1.0,2.0,3.0, 4.0,5.0,6.0, 7.0,8.0,9.0);
    assert_eq!((m[(0,0)], m[(1,2)], m[(2,1)]), (1.0, 6.0, 8.0));
    m[(1,1)] = -5.0; assert_eq!(m[(1,1)], -5.0);
}
#[test] fn transpose_swaps_and_is_involution() {
    let m = Matrix3::new(1.0,2.0,3.0, 4.0,5.0,6.0, 7.0,8.0,9.0);
    mat_close(&m.transpose(), &[1.0,4.0,7.0, 2.0,5.0,8.0, 3.0,6.0,9.0]);
    mat_close(&m.transpose().transpose(), &[1.0,2.0,3.0, 4.0,5.0,6.0, 7.0,8.0,9.0]);
}
#[test] fn column_extracts() {
    let m = Matrix3::new(1.0,2.0,3.0, 4.0,5.0,6.0, 7.0,8.0,9.0);
    let c = m.column(1); assert_eq!((c.x, c.y, c.z), (2.0, 5.0, 8.0));
    let c = m.column(2); assert_eq!((c.x, c.y, c.z), (3.0, 6.0, 9.0));
}
#[test] fn from_rows_builds() {
    let rows = [Vector3::new(1.0,2.0,3.0).transpose(), Vector3::new(4.0,5.0,6.0).transpose(), Vector3::new(7.0,8.0,9.0).transpose()];
    mat_close(&Matrix3::from_rows(&rows), &[1.0,2.0,3.0, 4.0,5.0,6.0, 7.0,8.0,9.0]);
}
#[test] fn determinant_hand_computed() {
    close(Matrix3::identity().determinant(), 1.0);
    close(Matrix3::zeros().determinant(), 0.0);
    // 2*(3*4-2*0) - 0 + 1*(1*0-3*1) = 24 - 3 = 21
    close(Matrix3::new(2.0,0.0,1.0, 1.0,3.0,2.0, 1.0,0.0,4.0).determinant(), 21.0);
    close(Matrix3::new(2.0,0.0,0.0, 0.0,3.0,0.0, 0.0,0.0,4.0).determinant(), 24.0);
    // Row swap flips sign: det of [[0,1,0],[1,0,0],[0,0,1]] = -1
    close(Matrix3::new(0.0,1.0,0.0, 1.0,0.0,0.0, 0.0,0.0,1.0).determinant(), -1.0);
    // Singular (row2 = 2*row1)
    close(Matrix3::new(1.0,2.0,3.0, 2.0,4.0,6.0, 1.0,1.0,1.0).determinant(), 0.0);
}
#[test] fn multiply_hand_computed() {
    let a = Matrix3::new(1.0,2.0,3.0, 4.0,5.0,6.0, 7.0,8.0,9.0);
    let b = Matrix3::new(9.0,8.0,7.0, 6.0,5.0,4.0, 3.0,2.0,1.0);
    // Row0: 9+12+9=30, 8+10+6=24, 7+8+3=18; Row1: 36+30+18=84, 32+25+12=69, 28+20+6=54; Row2: 63+48+27=138, 56+40+18=114, 49+32+9=90
    mat_close(&(a*b), &[30.0,24.0,18.0, 84.0,69.0,54.0, 138.0,114.0,90.0]);
    mat_close(&(a * Matrix3::identity()), &[1.0,2.0,3.0, 4.0,5.0,6.0, 7.0,8.0,9.0]);
    mat_close(&(Matrix3::zeros() * a), &[0.0;9]);
}
#[test] fn multiply_ref_variants_match_owned() {
    let a = Matrix3::new(1.0,0.0,2.0, 0.0,1.0,0.0, 3.0,0.0,1.0);
    let b = Matrix3::new(2.0,1.0,0.0, 0.0,2.0,1.0, 1.0,0.0,2.0);
    let want = a * b;
    mat_close(&(a * &b), &[want[(0,0)],want[(0,1)],want[(0,2)],want[(1,0)],want[(1,1)],want[(1,2)],want[(2,0)],want[(2,1)],want[(2,2)]]);
    mat_close(&(&a * b), &[want[(0,0)],want[(0,1)],want[(0,2)],want[(1,0)],want[(1,1)],want[(1,2)],want[(2,0)],want[(2,1)],want[(2,2)]]);
    mat_close(&(&a * &b), &[want[(0,0)],want[(0,1)],want[(0,2)],want[(1,0)],want[(1,1)],want[(1,2)],want[(2,0)],want[(2,1)],want[(2,2)]]);
}
#[test] fn matrix_times_vector() {
    let m = Matrix3::new(1.0,2.0,3.0, 4.0,5.0,6.0, 7.0,8.0,9.0);
    let v = m * Vector3::new(1.0, 1.0, 1.0); assert_eq!((v.x, v.y, v.z), (6.0, 15.0, 24.0));
    let v = &m * Vector3::new(1.0, 0.0, 0.0); assert_eq!((v.x, v.y, v.z), (1.0, 4.0, 7.0));
}
#[test] fn inverse_known_matrices() {
    mat_close(&Matrix3::new(2.0,0.0,0.0, 0.0,4.0,0.0, 0.0,0.0,5.0).try_inverse().unwrap(), &[0.5,0.0,0.0, 0.0,0.25,0.0, 0.0,0.0,0.2]);
    // Upper triangular [[1,2,0],[0,1,3],[0,0,1]]^-1 = [[1,-2,6],[0,1,-3],[0,0,1]]
    mat_close(&Matrix3::new(1.0,2.0,0.0, 0.0,1.0,3.0, 0.0,0.0,1.0).try_inverse().unwrap(), &[1.0,-2.0,6.0, 0.0,1.0,-3.0, 0.0,0.0,1.0]);
    assert!(Matrix3::zeros().try_inverse().is_none());
    assert!(Matrix3::new(f64::NAN,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0).try_inverse().is_none());
}
#[test] fn add_assign_and_div_assign() {
    let mut m = Matrix3::identity(); m += Matrix3::identity();
    mat_close(&m, &[2.0,0.0,0.0, 0.0,2.0,0.0, 0.0,0.0,2.0]);
    m /= 2.0; mat_close(&m, &[1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0]);
}
#[test] fn svd_sorted_descending_and_reconstructs() {
    let m = Matrix3::new(1.0,0.0,0.0, 0.0,3.0,0.0, 0.0,0.0,2.0);
    let s = m.svd_sorted();
    close(s.singular_values[0], 3.0); close(s.singular_values[1], 2.0); close(s.singular_values[2], 1.0);
    let u = s.u.unwrap(); let vt = s.v_t.unwrap();
    let sig = Matrix3::new(s.singular_values[0],0.0,0.0, 0.0,s.singular_values[1],0.0, 0.0,0.0,s.singular_values[2]);
    mat_close(&(u*sig*vt), &[1.0,0.0,0.0, 0.0,3.0,0.0, 0.0,0.0,2.0]);
    // U and Vt orthonormal
    mat_close(&(u*u.transpose()), &[1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0]);
    mat_close(&(vt*vt.transpose()), &[1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0]);
}
#[test] fn svd_zero_matrix_is_orthonormal_with_zero_singulars() {
    let s = Matrix3::zeros().svd(true, true);
    assert_eq!((s.singular_values[0], s.singular_values[1], s.singular_values[2]), (0.0, 0.0, 0.0));
    let u = s.u.unwrap();
    mat_close(&(u*u.transpose()), &[1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0]);
}
#[test] fn svd_rank_one() {
    // diag(5,0,0): singular values {5,0,0}, U Vt still orthonormal, reconstructs
    let m = Matrix3::new(5.0,0.0,0.0, 0.0,0.0,0.0, 0.0,0.0,0.0);
    let s = m.svd_sorted();
    close(s.singular_values[0], 5.0); close(s.singular_values[1], 0.0); close(s.singular_values[2], 0.0);
    let u = s.u.unwrap(); let vt = s.v_t.unwrap();
    let sig = Matrix3::new(s.singular_values[0],0.0,0.0, 0.0,s.singular_values[1],0.0, 0.0,0.0,s.singular_values[2]);
    mat_close(&(u*sig*vt), &[5.0,0.0,0.0, 0.0,0.0,0.0, 0.0,0.0,0.0]);
}
#[test] fn symmetric_eigen_identity_and_scaled() {
    let se = Matrix3::identity().symmetric_eigen();
    for i in 0..3 { close(se.eigenvalues[i], 1.0); }
    mat_close(&(se.eigenvectors * se.eigenvectors.transpose()), &[1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0]);
    let se = Matrix3::new(4.0,0.0,0.0, 0.0,-2.0,0.0, 0.0,0.0,7.0).symmetric_eigen();
    let mut ev = [se.eigenvalues[0], se.eigenvalues[1], se.eigenvalues[2]]; ev.sort_by(|a,b| a.total_cmp(b));
    close(ev[0], -2.0); close(ev[1], 4.0); close(ev[2], 7.0);
}
