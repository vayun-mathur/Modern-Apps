use crate::linalg::{DMatrix, DVector};
fn close(a: f64, b: f64) { assert!((a-b).abs() < 1e-12, "{a} vs {b}"); }
fn dm(vals: &[f64], r: usize, c: usize) -> DMatrix<f64> { let mut m = DMatrix::zeros(r,c); for i in 0..r { for j in 0..c { m[(i,j)] = vals[i*c+j]; } } m }
fn dv(vals: &[f64]) -> DVector<f64> { let mut v = DVector::zeros(vals.len()); for (i,&x) in vals.iter().enumerate() { v[i]=x; } v }
#[test] fn zeros_dims_index_row() {
    let mut m = DMatrix::<f64>::zeros(2, 3);
    assert_eq!(m.nrows(), 2);
    assert_eq!(m.row(0), vec![0.0,0.0,0.0]);
    m[(1,2)] = 7.0; assert_eq!(m[(1,2)], 7.0); assert_eq!(m.row(1), vec![0.0,0.0,7.0]);
}
#[test] fn dvector_index_and_ops() {
    let mut v = dv(&[1.0,2.0,3.0]); v[1] = -2.0; assert_eq!(v[1], -2.0);
    let mut a = dv(&[1.0,2.0]); a += dv(&[10.0,20.0]); assert_eq!((a[0],a[1]), (11.0,22.0));
    let n = -&dv(&[1.0,-2.0,3.0]); assert_eq!((n[0],n[1],n[2]), (-1.0,2.0,-3.0));
}
#[test] fn dmatrix_add_assign() { let mut m = dm(&[1.0,2.0,3.0,4.0],2,2); m += dm(&[10.0,20.0,30.0,40.0],2,2); assert_eq!((m[(0,0)],m[(1,1)]), (11.0,44.0)); }
#[test] fn lu_identity_and_diagonal() {
    let x = dm(&[1.0,0.0, 0.0,1.0],2,2).lu().solve(&dv(&[5.0,-3.0])).unwrap(); close(x[0],5.0); close(x[1],-3.0);
    let x = dm(&[2.0,0.0, 0.0,4.0],2,2).lu().solve(&dv(&[6.0,8.0])).unwrap(); close(x[0],3.0); close(x[1],2.0);
}
#[test] fn lu_requires_pivot_swap() {
    // [[0,1],[1,0]] x = [2,3] => x = [3,2]
    let x = dm(&[0.0,1.0, 1.0,0.0],2,2).lu().solve(&dv(&[2.0,3.0])).unwrap(); close(x[0],3.0); close(x[1],2.0);
    // [[0,2,0],[1,0,0],[0,0,4]] x=[?] with x=[1,2,3] => b=[4,1,12]
    let x = dm(&[0.0,2.0,0.0, 1.0,0.0,0.0, 0.0,0.0,4.0],3,3).lu().solve(&dv(&[4.0,1.0,12.0])).unwrap();
    close(x[0],1.0); close(x[1],2.0); close(x[2],3.0);
}
#[test] fn lu_1x1_and_upper_triangular() {
    let x = dm(&[4.0],1,1).lu().solve(&dv(&[9.0])).unwrap(); close(x[0],2.25);
    assert!(dm(&[0.0],1,1).lu().solve(&dv(&[1.0])).is_none());
    // [[1,2],[0,3]] x, x=[1,1] => b=[3,3]
    let x = dm(&[1.0,2.0, 0.0,3.0],2,2).lu().solve(&dv(&[3.0,3.0])).unwrap(); close(x[0],1.0); close(x[1],1.0);
}
#[test] fn dmatrix_svd_nullspace_is_last_row() {
    // A = [[1,0],[0,0]] (2x2): A^T A = diag(1,0); smallest singular vector = (0,+-1)
    let s = dm(&[1.0,0.0, 0.0,0.0],2,2).svd(true, true);
    let vt = s.v_t.unwrap(); assert!(s.u.is_none());
    let last = vt.row(vt.nrows()-1);
    assert!(last[0].abs() < 1e-9 && (last[1].abs()-1.0).abs() < 1e-9, "{last:?}");
    let first = vt.row(0); assert!((first[0].abs()-1.0).abs() < 1e-9 && first[1].abs() < 1e-9);
}
#[test] fn dmatrix_svd_rows_orthonormal_and_ordered() {
    // A^T A for A=diag(1,3) is diag(1,9): descending order puts (0,+-1) first
    let s = dm(&[1.0,0.0, 0.0,3.0],2,2).svd(true, true);
    let vt = s.v_t.unwrap();
    let r0 = vt.row(0); let r1 = vt.row(1);
    assert!((r0[1].abs()-1.0).abs() < 1e-9, "{r0:?}");
    assert!((r0[0]*r1[0]+r0[1]*r1[1]).abs() < 1e-9);
    assert!(((r0[0]*r0[0]+r0[1]*r0[1])-1.0).abs() < 1e-9);
}
#[test] fn dmatrix_svd_rectangular() {
    // 3x2 A with columns (1,0,0),(0,1,0): A^T A = I, rows of Vt orthonormal
    let s = dm(&[1.0,0.0, 0.0,1.0, 0.0,0.0],3,2).svd(true, true);
    let vt = s.v_t.unwrap(); assert_eq!(vt.nrows(), 2);
    let r0 = vt.row(0); let r1 = vt.row(1);
    assert!(((r0[0]*r0[0]+r0[1]*r0[1])-1.0).abs() < 1e-9);
    assert!((r0[0]*r1[0]+r0[1]*r1[1]).abs() < 1e-9);
}
