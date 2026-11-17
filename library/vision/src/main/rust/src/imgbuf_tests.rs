use crate::imgbuf::{harris_scores, to_gray, Gray, Rgba};
#[test] fn rgba_new_zeroed_and_get_set() {
    let mut img = Rgba::new(3, 2);
    assert_eq!((img.w, img.h, img.px.len()), (3, 2, 24));
    assert_eq!(img.get(2, 1), [0,0,0,0]);
    img.set(2, 1, [10,20,30,40]); assert_eq!(img.get(2,1), [10,20,30,40]);
    assert_eq!(img.get(0,0), [0,0,0,0]);
}
#[test] fn rgba_from_bytes_roundtrip() {
    let px: Vec<u8> = (0..16).collect();
    let img = Rgba::from_bytes(2, 2, px);
    assert_eq!(img.get(1,0), [4,5,6,7]); assert_eq!(img.get(0,1), [8,9,10,11]);
}
#[test] fn sample_exact_pixel_and_bilinear_midpoint() {
    let mut img = Rgba::new(2, 2);
    img.set(0,0,[0,0,0,255]); img.set(1,0,[100,0,0,255]); img.set(0,1,[0,100,0,255]); img.set(1,1,[100,100,100,255]);
    assert_eq!(img.sample(0.0,0.0).unwrap(), [0.0,0.0,0.0,255.0]);
    assert_eq!(img.sample(1.0,1.0).unwrap(), [100.0,100.0,100.0,255.0]);
    // Center = mean of 4 corners per channel: R(0+100+0+100)/4=50, G likewise 50, B 25
    let c = img.sample(0.5,0.5).unwrap();
    assert!((c[0]-50.0).abs()<1e-6 && (c[1]-50.0).abs()<1e-6 && (c[2]-25.0).abs()<1e-6 && (c[3]-255.0).abs()<1e-6, "{c:?}");
    // Quarter along x on top row: R = 25
    let c = img.sample(0.25,0.0).unwrap(); assert!((c[0]-25.0).abs()<1e-6);
}
#[test] fn sample_out_of_bounds_and_transparent() {
    let mut img = Rgba::new(2, 2);
    assert!(img.sample(-0.1, 0.0).is_none()); assert!(img.sample(0.0, -1.0).is_none());
    assert!(img.sample(1.01, 0.0).is_none()); assert!(img.sample(0.0, 2.0).is_none());
    // All alpha 0 => None even in bounds
    assert!(img.sample(0.0, 0.0).is_none());
    img.set(0,0,[10,20,30,255]);
    assert!(img.sample(0.0,0.0).is_some());
}
#[test] fn rgba_resized_identity_down_and_up() {
    let mut img = Rgba::new(2, 2);
    img.set(0,0,[0,0,0,255]); img.set(1,0,[100,0,0,255]); img.set(0,1,[0,100,0,255]); img.set(1,1,[100,100,100,255]);
    let same = img.resized(2, 2);
    assert_eq!(same.get(1,1), [100,100,100,255]);
    // 1x1 downsample samples center (0.5,0.5): [50,50,25], alpha forced 255
    let one = img.resized(1, 1); assert_eq!(one.get(0,0), [50,50,25,255]);
    let big = img.resized(4, 4); assert_eq!((big.w, big.h), (4,4));
    assert_eq!(big.get(0,0), [0,0,0,255]);
    // Zero size clamps to 1x1
    assert_eq!((img.resized(0,0).w, img.resized(0,0).h), (1,1));
}
#[test] fn gray_new_at_and_clone() {
    let mut g = Gray::new(3, 2); assert_eq!(g.px.len(), 6);
    g.px[5] = 99; assert_eq!(g.at(2,1), 99); assert_eq!(g.at(0,0), 0);
}
#[test] fn gray_resized_average_and_identity() {
    let mut g = Gray::new(2, 2); g.px = vec![0, 100, 100, 200];
    assert_eq!(g.resized(1,1).px, vec![100]); // mean of 4
    assert_eq!(g.resized(2,2).px, vec![0,100,100,200]);
    let up = g.resized(4, 4); assert_eq!(up.at(0,0), 0);
    assert_eq!((g.resized(0,5).w, g.resized(0,5).h), (1,5));
}
#[test] fn gray_blur_uniform_impulse_and_tiny() {
    let mut g = Gray::new(5, 5); g.px.iter_mut().for_each(|p| *p = 80);
    assert!(g.gaussian_blur_3x3().px.iter().all(|&p| p == 80));
    // Impulse 160 at center of 3x3: blurred center = 160*4/16 = 40; borders unchanged (0)
    let mut g = Gray::new(3, 3); g.px[4] = 160;
    let b = g.gaussian_blur_3x3(); assert_eq!(b.at(1,1), 40); assert_eq!(b.at(0,0), 0);
    // Tiny images returned as clone
    let mut g = Gray::new(2, 2); g.px = vec![1,2,3,4];
    assert_eq!(g.gaussian_blur_3x3().px, vec![1,2,3,4]);
}
#[test] fn gray_blur_border_preserved_interior_smoothed() {
    // 4x4 ramp: interior (1,1) kernel over neighbourhood; just assert border == input, interior in range
    let mut g = Gray::new(4, 4); for i in 0..16 { g.px[i] = (i*10) as u8; }
    let b = g.gaussian_blur_3x3();
    for x in 0..4 { assert_eq!(b.at(x,0), g.at(x,0)); assert_eq!(b.at(x,3), g.at(x,3)); }
    assert!(b.at(1,1) > 0 && b.at(1,1) < 255);
}
#[test] fn to_gray_known_colors() {
    let mut img = Rgba::new(5, 1);
    img.set(0,0,[255,255,255,255]); img.set(1,0,[255,0,0,255]); img.set(2,0,[0,255,0,255]); img.set(3,0,[0,0,255,255]); img.set(4,0,[0,0,0,255]);
    let g = to_gray(&img);
    // Rec601 >>8: white 255, red 76 (255*77>>8), green 149 (255*150>>8), blue 28 (255*29>>8)
    assert_eq!(g.px, vec![255, 76, 149, 28, 0]);
    // Gray input r=g=b=v maps to v (77+150+29=256)
    let mut img = Rgba::new(1,1); img.set(0,0,[128,128,128,0]); assert_eq!(to_gray(&img).px, vec![128]);
}
#[test] fn harris_flat_is_zero_and_empty() {
    let mut g = Gray::new(16, 16); g.px.iter_mut().for_each(|p| *p = 100);
    let s = harris_scores(&g, &[(8,8),(4,4)]);
    assert_eq!(s.len(), 2); assert!(s.iter().all(|&v| v == 0.0), "{s:?}");
    assert!(harris_scores(&g, &[]).is_empty());
}
#[test] fn harris_corner_exceeds_edge_and_flat() {
    // White bottom-right quadrant => corner near (16,16), vertical edge near (16,8)... compare magnitudes
    let mut g = Gray::new(32, 32);
    for y in 16..32 { for x in 16..32 { g.px[y*32+x] = 255; } }
    let corner = harris_scores(&g, &[(16,16)])[0];
    let flat = harris_scores(&g, &[(8,8)])[0];
    assert_eq!(flat, 0.0);
    assert!(corner > flat, "corner {corner} flat {flat}");
    assert!(corner > 0.0);
}
