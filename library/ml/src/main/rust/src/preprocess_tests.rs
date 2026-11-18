//! Preprocess tests for the validation branches: letterbox geometry
//! refusals and the bitmap shape checks in both resize paths.

use super::{to_letterboxed_f16, to_planar_f16, Letterbox, IMAGENET};
use crate::nets::Shape;

#[test]
fn letterbox_refuses_degenerate_geometry() {
    assert!(Letterbox::new(0, 10, 100, 32).is_err(), "zero width");
    assert!(Letterbox::new(10, 0, 100, 32).is_err(), "zero height");
    assert!(Letterbox::new(10, 10, 0, 32).is_err(), "zero long side");
    assert!(Letterbox::new(10, 10, 100, 0).is_err(), "zero multiple");
    assert!(Letterbox::square(0, 10, 100).is_err(), "zero width, square");
    assert!(Letterbox::square(10, 10, 0).is_err(), "zero side");
}

#[test]
fn letterbox_scales_the_long_side_and_pads_to_the_multiple() {
    // 200x100 at long side 100: scale 0.5, resized 100x50, padded to
    // the next multiple of 32 on both axes (128x64), centred.
    let fit = Letterbox::new(200, 100, 100, 32).expect("fits");
    assert_eq!(fit.shape(), Shape::new(3, 64, 128));
    let square = Letterbox::square(200, 100, 64).expect("fits");
    assert_eq!(square.shape(), Shape::new(3, 64, 64));
}

#[test]
fn the_resize_paths_refuse_mismatched_bitmaps() {
    let fit = Letterbox::new(4, 4, 4, 1).expect("fits");
    let norm = IMAGENET;
    let mut dst = vec![0u16; 3 * 16];
    assert!(to_letterboxed_f16(&[], 0, 4, &fit, &norm, &mut dst).is_err(), "zero bitmap");
    assert!(to_letterboxed_f16(&[0; 3], 4, 4, &fit, &norm, &mut dst).is_err(), "short pixels");
    assert!(to_letterboxed_f16(&[0; 16], 4, 4, &fit, &norm, &mut [0u16; 4]).is_err(), "short dst");

    let mut dst = vec![0u16; 3 * 16];
    assert!(to_planar_f16(&[0; 16], 4, 4, Shape::new(1, 4, 4), &norm, &mut dst).is_err(), "one channel");
    assert!(to_planar_f16(&[], 0, 4, Shape::new(3, 4, 4), &norm, &mut dst).is_err(), "zero bitmap");
    assert!(to_planar_f16(&[0; 3], 4, 4, Shape::new(3, 4, 4), &norm, &mut dst).is_err(), "short pixels");
    assert!(to_planar_f16(&[0; 16], 4, 4, Shape::new(3, 4, 4), &norm, &mut [0u16; 4]).is_err(), "short dst");
}
