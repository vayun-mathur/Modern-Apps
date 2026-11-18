//! PP-OCR detection builder tests: the backbone-exposing variant
//! and the input-extent refusals.

use super::{build, build_with_backbone, TENSORS};
use crate::nets::tests::Shapes;

#[test]
fn the_backbone_variant_reports_both_outputs() {
    let plan = build_with_backbone(&Shapes::new(TENSORS), 64, 64).expect("builds");
    assert_eq!(plan.outputs.len(), 2, "probability map and backbone output");
    let plain = build(&Shapes::new(TENSORS), 64, 64).expect("builds");
    assert_eq!(plain.outputs.len(), 1);
}

#[test]
fn bad_extents_are_refused() {
    let err = build(&Shapes::new(TENSORS), 0, 64).expect_err("zero height");
    assert!(err.contains("64x0"), "{err}");
    let err = build(&Shapes::new(TENSORS), 64, 0).expect_err("zero width");
    assert!(err.contains("0x64"), "{err}");
    let err = build(&Shapes::new(TENSORS), 63, 64).expect_err("not a multiple");
    assert!(err.contains("multiple of 32"), "{err}");
}
