use crate::geometry::{find_homography_ransac, transfer_inliers, Lcg, Pt};
use crate::linalg::Matrix3;
fn close(a: f64, b: f64, t: f64) { assert!((a-b).abs() < t, "{a} vs {b}"); }
fn square() -> Vec<Pt> { vec![(0.0,0.0),(10.0,0.0),(10.0,10.0),(0.0,10.0)] }
#[test] fn lcg_deterministic_bounded() {
    let mut a = Lcg::new(42); let mut b = Lcg::new(42);
    for _ in 0..20 { let (x,y) = (a.next_usize(7), b.next_usize(7)); assert_eq!(x,y); assert!(x < 7); }
    let mut c = Lcg::new(1); assert_eq!(c.next_usize(1), 0);
    let mut d = Lcg::new(0); for _ in 0..10 { assert!(d.next_usize(100) < 100); }
    // Different seeds diverge quickly
    let mut e = Lcg::new(1); let mut f = Lcg::new(2);
    let se: Vec<usize> = (0..8).map(|_| e.next_usize(1000)).collect();
    let sf: Vec<usize> = (0..8).map(|_| f.next_usize(1000)).collect();
    assert_ne!(se, sf);
}
#[test] fn homography_identity_four_points() {
    let a = square();
    let (h, n) = find_homography_ransac(&a, &a.clone(), 20, 1.0).unwrap();
    assert_eq!(n, 4);
    for r in 0..3 { for c in 0..3 { close(h[(r,c)], if r==c {1.0} else {0.0}, 1e-6); } }
}
#[test] fn homography_translation() {
    let a = square(); let b: Vec<Pt> = a.iter().map(|&(x,y)| (x+5.0, y+3.0)).collect();
    let (h, _) = find_homography_ransac(&a, &b, 20, 1.0).unwrap();
    close(h[(0,0)],1.0,1e-6); close(h[(0,2)],5.0,1e-5); close(h[(1,1)],1.0,1e-6); close(h[(1,2)],3.0,1e-5); close(h[(2,2)],1.0,1e-9);
}
#[test] fn homography_uniform_scale() {
    let a = square(); let b: Vec<Pt> = a.iter().map(|&(x,y)| (x*2.0, y*2.0)).collect();
    let (h, _) = find_homography_ransac(&a, &b, 20, 1.0).unwrap();
    close(h[(0,0)],2.0,1e-6); close(h[(1,1)],2.0,1e-6); close(h[(0,2)],0.0,1e-6);
}
#[test] fn homography_too_few_or_mismatched() {
    assert!(find_homography_ransac(&square()[..3], &square()[..3], 10, 1.0).is_none());
    assert!(find_homography_ransac(&square(), &square()[..3], 10, 1.0).is_none());
    assert!(find_homography_ransac(&[], &[], 10, 1.0).is_none());
}
#[test] fn transfer_inliers_threshold() {
    let id = Matrix3::identity();
    let a: Vec<Pt> = vec![(0.0,0.0),(10.0,0.0),(10.0,10.0)];
    let b: Vec<Pt> = vec![(0.0,0.0),(10.5,0.0),(20.0,10.0)];
    assert_eq!(transfer_inliers(&id, &a, &b, 1.0), vec![0,1]);
    assert_eq!(transfer_inliers(&id, &a, &b, 0.4), vec![0]);
    // Shorter b truncates via min len
    assert_eq!(transfer_inliers(&id, &a, &b[..1], 10.0), vec![0]);
    assert!(transfer_inliers(&id, &[], &[], 1.0).is_empty());
}
#[test] fn transfer_inliers_under_translation() {
    let h = Matrix3::new(1.0,0.0,5.0, 0.0,1.0,3.0, 0.0,0.0,1.0);
    let a = square(); let b: Vec<Pt> = a.iter().map(|&(x,y)| (x+5.0, y+3.0)).collect();
    assert_eq!(transfer_inliers(&h, &a, &b, 0.01).len(), 4);
    assert!(transfer_inliers(&h, &a, &a, 0.01).is_empty());
}
#[test] fn ransac_recovers_translation_with_many_points() {
    let mut a = Vec::new(); let mut b = Vec::new();
    for i in 0..6 { for j in 0..4 { let p = (i as f32*10.0, j as f32*10.0); a.push(p); b.push((p.0+4.0, p.1-2.0)); } }
    let (h, n) = find_homography_ransac(&a, &b, 200, 1.0).unwrap();
    assert!(n >= 20, "inliers {n}");
    close(h[(0,2)], 4.0, 0.05); close(h[(1,2)], -2.0, 0.05);
    assert_eq!(transfer_inliers(&h, &a, &b, 1.0).len(), a.len());
}
#[test] fn ransac_deterministic() {
    let a = square(); let b: Vec<Pt> = a.iter().map(|&(x,y)| (x+1.0, y+1.0)).collect();
    let (h1, n1) = find_homography_ransac(&a, &b, 50, 1.0).unwrap();
    let (h2, n2) = find_homography_ransac(&a, &b, 50, 1.0).unwrap();
    assert_eq!(n1, n2); for r in 0..3 { for c in 0..3 { assert_eq!(h1[(r,c)], h2[(r,c)]); } }
}
