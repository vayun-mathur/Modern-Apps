//! PP-OCR recogniser batch tests: several crops in one plan, and
//! the width/line-count refusals.

use super::build_batch;
use crate::nets::ppocr_rec::TENSORS;
use crate::nets::tests::Shapes;

#[test]
fn a_batch_builds_one_pass_per_line() {
    let plan = build_batch(&Shapes::new(TENSORS), 64, 3).expect("builds");
    assert_eq!(plan.inputs.len(), 3);
    assert_eq!(plan.outputs.len(), 3);
    let one = build_batch(&Shapes::new(TENSORS), 64, 1).expect("builds");
    assert_eq!(one.inputs.len(), 1);
}

#[test]
fn a_batch_refuses_bad_widths_and_empty_batches() {
    assert!(build_batch(&Shapes::new(TENSORS), 0, 1).is_err(), "zero width");
    assert!(build_batch(&Shapes::new(TENSORS), 63, 1).is_err(), "not a multiple of 8");
    assert!(build_batch(&Shapes::new(TENSORS), 64, 0).is_err(), "no lines");
}
