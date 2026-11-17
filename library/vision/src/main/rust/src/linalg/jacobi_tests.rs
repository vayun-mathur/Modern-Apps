use super::jacobi_sym;
fn sorted(mut v: Vec<f64>) -> Vec<f64> { v.sort_by(|a,b| a.total_cmp(b)); v }
#[test] fn one_by_one() { let (e, v) = jacobi_sym(&[42.0], 1); assert_eq!(e, vec![42.0]); assert_eq!(v, vec![1.0]); }
#[test] fn diagonal_unchanged() {
    let (e, v) = jacobi_sym(&[3.0,0.0,0.0, 0.0,-1.0,0.0, 0.0,0.0,5.0], 3);
    assert_eq!(sorted(e.clone()), vec![-1.0, 3.0, 5.0]);
    // eigenvectors are signed basis vectors: V^T V = I
    for i in 0..3 { for j in 0..3 { let d: f64 = (0..3).map(|k| v[k*3+i]*v[k*3+j]).sum(); assert!((d - if i==j {1.0} else {0.0}).abs() < 1e-12); } }
    let _ = e;
}
#[test] fn two_by_two_known_eigen() {
    // [[2,1],[1,2]] eigenvalues 1,3
    let a = [2.0,1.0, 1.0,2.0];
    let (e, v) = jacobi_sym(&a, 2);
    let s = sorted(e.clone()); assert!((s[0]-1.0).abs()<1e-12 && (s[1]-3.0).abs()<1e-12);
    // Reconstruct V diag(e) V^T == A
    for r in 0..2 { for c in 0..2 { let rec: f64 = (0..2).map(|k| v[r*2+k]*e[k]*v[c*2+k]).sum(); assert!((rec-a[r*2+c]).abs()<1e-12, "{r}{c} {rec}"); } }
}
#[test] fn identity_all_ones() { let (e, _) = jacobi_sym(&[1.0,0.0, 0.0,1.0], 2); assert!(e.iter().all(|&x| (x-1.0).abs()<1e-12)); }
#[test] fn zero_matrix() { let (e, v) = jacobi_sym(&[0.0;4], 2); assert_eq!(e, vec![0.0,0.0]); assert_eq!(v, vec![1.0,0.0,0.0,1.0]); }
#[test] fn three_by_three_block() {
    // [[2,1,0],[1,2,0],[0,0,5]] eigenvalues 1,3,5
    let a = [2.0,1.0,0.0, 1.0,2.0,0.0, 0.0,0.0,5.0];
    let (e, v) = jacobi_sym(&a, 3);
    let s = sorted(e.clone()); assert!((s[0]-1.0).abs()<1e-9 && (s[1]-3.0).abs()<1e-9 && (s[2]-5.0).abs()<1e-9, "{s:?}");
    for r in 0..3 { for c in 0..3 { let rec: f64 = (0..3).map(|k| v[r*3+k]*e[k]*v[c*3+k]).sum(); assert!((rec-a[r*3+c]).abs()<1e-9); } }
}
