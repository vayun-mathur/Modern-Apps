use crate::features::{detect_and_describe, detect_and_describe_orb, match_features, Features, ORB_DEFAULT_NFEATURES, ORB_NLEVELS, ORB_PATCH_SIZE, ORB_SCALE_FACTOR};
use crate::imgbuf::Gray;
fn feats(desc: Vec<[u8;32]>) -> Features { Features { kps: Vec::new(), desc, w: 0, h: 0 } }
#[test] fn orb_constants_match_opencv_defaults() {
    assert_eq!(ORB_DEFAULT_NFEATURES, 500); assert_eq!(ORB_NLEVELS, 8); assert_eq!(ORB_PATCH_SIZE, 31);
    assert!((ORB_SCALE_FACTOR - 1.2).abs() < 1e-6);
}
#[test] fn flat_and_tiny_images_yield_no_features() {
    // border = max(16, 32) = 32 (edgeThreshold 31 + ...) so images <=64 wide yield nothing at level 0
    let mut g = Gray::new(100, 100); g.px.iter_mut().for_each(|p| *p = 128);
    let f = detect_and_describe(&g, 500, 20);
    assert!(f.kps.is_empty() && f.desc.is_empty()); assert_eq!((f.w, f.h), (100, 100));
    let tiny = Gray::new(10, 10);
    assert!(detect_and_describe(&tiny, 500, 20).kps.is_empty());
    assert!(detect_and_describe_orb(&Gray::new(1,1), 500, 20).kps.is_empty());
}
#[test] fn white_square_produces_features_with_valid_descriptors() {
    // Isolated bright dots on black: centre 255, all 16 FAST-circle pixels 0 -> FAST-9 corner
    let mut g = Gray::new(100, 100);
    for y in [40usize, 55, 70] { for x in [40usize, 55, 70] { g.px[y*100+x] = 255; } }
    let f = detect_and_describe(&g, 500, 20);
    assert!(!f.kps.is_empty(), "expected corners on dots");
    assert_eq!(f.kps.len(), f.desc.len());
    assert!(f.kps.len() <= 500);
    for kp in &f.kps { assert!(kp.x >= 0.0 && kp.x < 100.0 && kp.y >= 0.0 && kp.y < 100.0, "kp {},{}", kp.x, kp.y); assert!(kp.angle.is_finite()); }
}
#[test] fn detection_is_deterministic() {
    let mut g = Gray::new(100, 100);
    for y in [40usize, 55, 70] { for x in [40usize, 55, 70] { g.px[y*100+x] = 255; } }
    let a = detect_and_describe(&g, 100, 20); let b = detect_and_describe(&g, 100, 20);
    assert_eq!(a.kps.len(), b.kps.len()); assert_eq!(a.desc, b.desc);
    for (x,y) in a.kps.iter().zip(b.kps.iter()) { assert_eq!((x.x,x.y), (y.x,y.y)); }
}
#[test] fn max_features_caps_output() {
    let mut g = Gray::new(120, 120);
    // Checkerboard-ish texture: many corners
    for y in 0..120 { for x in 0..120 { if (x/10 + y/10) % 2 == 0 { g.px[y*120+x] = 255; } } }
    let f = detect_and_describe(&g, 5, 20);
    assert!(f.kps.len() <= 5, "got {}", f.kps.len());
}
#[test] fn match_identical_descriptors() {
    let d = [0xABu8; 32];
    let a = feats(vec![d]); let b = feats(vec![d, [0xFFu8;32]]);
    // best=0, second=256 => 0 < ratio*second for any positive ratio
    assert_eq!(match_features(&a, &b, 0.8), vec![(0,0)]);
}
#[test] fn match_ratio_rejects_ambiguous() {
    // Two identical candidates: best == second, so best < ratio*second fails
    let d = [7u8; 32];
    assert!(match_features(&feats(vec![d]), &feats(vec![d, d]), 0.8).is_empty());
    assert!(match_features(&feats(vec![d]), &feats(vec![d, d]), 1.0).is_empty());
    // best=8 bits away, second=256: 8 < 0.8*256 passes; reversed ratio small fails? 8 < 0.01*256=2.56 fails
    let mut near = [0u8;32]; near[0] = 0xFF; // 8 bits from zero
    let a = feats(vec![[0u8;32]]); let b = feats(vec![near, [0xFFu8;32]]);
    assert_eq!(match_features(&a, &b, 0.8), vec![(0,0)]);
    assert!(match_features(&a, &b, 0.01).is_empty());
}
#[test] fn match_empty_inputs() {
    assert!(match_features(&feats(vec![]), &feats(vec![[0u8;32]]), 0.8).is_empty());
    assert!(match_features(&feats(vec![[0u8;32]]), &feats(vec![]), 0.8).is_empty());
    assert!(match_features(&feats(vec![]), &feats(vec![]), 0.8).is_empty());
}
#[test] fn self_match_detected_features() {
    let mut g = Gray::new(100, 100);
    for y in [40usize, 55, 70] { for x in [40usize, 55, 70] { g.px[y*100+x] = 255; } }
    let f = detect_and_describe(&g, 500, 20);
    if !f.desc.is_empty() {
        // Each descriptor's nearest in itself is itself at distance 0; ratio test needs a distinct second, so just check no panic and matches index validly
        let m = match_features(&f, &f, 0.99);
        for (i,j) in m { assert!(i < f.desc.len() && j < f.desc.len()); }
    }
}
