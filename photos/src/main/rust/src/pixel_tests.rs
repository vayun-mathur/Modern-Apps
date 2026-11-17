//! Unit tests for `pixel` ARGB helpers.
use crate::pixel::*;

#[test]
fn pack_bit_layout_is_argb() {
    assert_eq!(pack(0xAB, 0xCD, 0xEF, 0x12) as u32, 0xABCDEF12);
    assert_eq!(pack(0, 0, 0, 0), 0);
    assert_eq!(pack(255, 255, 255, 255), -1);
    assert_eq!(pack(255, 0, 0, 0) as u32, 0xFF00_0000);
    assert_eq!(pack(0, 255, 0, 0) as u32, 0x00FF_0000);
    assert_eq!(pack(0, 0, 255, 0) as u32, 0x0000_FF00);
    assert_eq!(pack(0, 0, 0, 255) as u32, 0x0000_00FF);
}

#[test]
fn unpack_extracts_each_channel() {
    let p = pack(0x12, 0x34, 0x56, 0x78);
    assert_eq!((a(p), r(p), g(p), b(p)), (0x12, 0x34, 0x56, 0x78));
    // High-alpha pixels are negative as i32; extraction must use unsigned shifts.
    let q = pack(255, 200, 100, 50);
    assert!(q < 0);
    assert_eq!((a(q), r(q), g(q), b(q)), (255, 200, 100, 50));
    assert_eq!((a(-1), r(-1), g(-1), b(-1)), (255, 255, 255, 255));
    assert_eq!((a(0), r(0), g(0), b(0)), (0, 0, 0, 0));
}

#[test]
fn pack_unpack_roundtrips() {
    for (av, rv, gv, bv) in [
        (0, 0, 0, 0),
        (255, 255, 255, 255),
        (128, 1, 254, 77),
        (255, 255, 0, 128),
        (1, 2, 3, 4),
        (90, 180, 45, 225),
    ] {
        let p = pack(av, rv, gv, bv);
        assert_eq!((a(p), r(p), g(p), b(p)), (av, rv, gv, bv));
    }
}

#[test]
fn channels_do_not_bleed_into_neighbours() {
    // Each channel occupies exactly its own byte for in-range inputs.
    let p = pack(0xFF, 0x00, 0xFF, 0x00);
    assert_eq!(p as u32, 0xFF00_FF00);
    let p = pack(0x01, 0x02, 0x04, 0x08);
    assert_eq!(p as u32, 0x0102_0408);
}

#[test]
fn clamp_i_bounds() {
    assert_eq!(clamp_i(5, 0, 10), 5);
    assert_eq!(clamp_i(0, 0, 10), 0);
    assert_eq!(clamp_i(10, 0, 10), 10);
    assert_eq!(clamp_i(-3, 0, 10), 0);
    assert_eq!(clamp_i(99, 0, 10), 10);
    assert_eq!(clamp_i(-5, -10, -1), -5);
    assert_eq!(clamp_i(-50, -10, -1), -10);
    assert_eq!(clamp_i(0, -10, -1), -1);
    assert_eq!(clamp_i(7, 7, 7), 7);
    assert_eq!(clamp_i(i32::MIN, 0, 255), 0);
    assert_eq!(clamp_i(i32::MAX, 0, 255), 255);
}

#[test]
fn clamp_f_bounds() {
    assert_eq!(clamp_f(0.5, 0.0, 1.0), 0.5);
    assert_eq!(clamp_f(0.0, 0.0, 1.0), 0.0);
    assert_eq!(clamp_f(1.0, 0.0, 1.0), 1.0);
    assert_eq!(clamp_f(-0.25, 0.0, 1.0), 0.0);
    assert_eq!(clamp_f(1.25, 0.0, 1.0), 1.0);
    assert_eq!(clamp_f(-3.5, -2.0, 2.0), -2.0);
    assert_eq!(clamp_f(3.5, -2.0, 2.0), 2.0);
}
