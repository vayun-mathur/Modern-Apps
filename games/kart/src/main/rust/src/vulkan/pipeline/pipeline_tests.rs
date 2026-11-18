//! Tests for the aligned SPIR-V word conversion (the kart bug fix) and
//! the bundled shader modules.
use super::*;

#[test]
fn spv_words_are_little_endian_aligned_and_drop_trailing_bytes() {
    assert!(spv_words(&[]).is_empty());
    assert_eq!(spv_words(&[0x03, 0x02, 0x23, 0x07]), vec![0x0723_0203]);
    assert_eq!(spv_words(&[1, 0, 0, 0, 2, 0, 0, 0]), vec![1, 2]);
    assert_eq!(spv_words(&[1, 0, 0, 0, 9]), vec![1]);
    assert_eq!(spv_words(&[1, 2, 3]), Vec::<u32>::new());
    // Regression: the old code reinterpreted align-1 include_bytes! as
    // *const u32 (UB). The conversion must produce identical words from a
    // deliberately unaligned slice.
    let mut buf = [0u8; 9];
    buf[1..9].copy_from_slice(&[0x03, 0x02, 0x23, 0x07, 5, 0, 0, 0]);
    assert_eq!(spv_words(&buf[1..]), vec![0x0723_0203, 5]);
}

#[test]
fn bundled_spv_modules_start_with_spirv_magic() {
    for spv in [KART_VERT_SPV, KART_FRAG_SPV] {
        assert!(!spv.is_empty());
        assert_eq!(spv.len() % 4, 0);
        let words = spv_words(spv);
        assert_eq!(words[0], 0x0723_0203, "SPIR-V magic number");
    }
    assert_ne!(KART_VERT_SPV, KART_FRAG_SPV);
}
