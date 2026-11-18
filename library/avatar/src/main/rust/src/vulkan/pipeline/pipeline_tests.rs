//! Tests for the SPIR-V word conversion and bundled shader modules.
use super::*;

#[test]
fn spv_words_are_little_endian_aligned_and_drop_trailing_bytes() {
    assert!(spv_words(&[]).is_empty());
    assert_eq!(spv_words(&[0x03, 0x02, 0x23, 0x07]), vec![0x0723_0203]);
    assert_eq!(
        spv_words(&[1, 0, 0, 0, 2, 0, 0, 0]),
        vec![1, 2]
    );
    // Non-multiple-of-4: trailing partial word is dropped (SPIR-V is whole words).
    assert_eq!(spv_words(&[1, 0, 0, 0, 9]), vec![1]);
    assert_eq!(spv_words(&[1, 2, 3]), Vec::<u32>::new());
}

#[test]
fn bundled_spv_modules_start_with_spirv_magic() {
    for spv in [YUU_VERT_SPV, YUU_FRAG_SPV] {
        assert!(!spv.is_empty());
        assert_eq!(spv.len() % 4, 0, "SPIR-V must be whole words");
        let words = spv_words(spv);
        assert_eq!(words[0], 0x0723_0203, "SPIR-V magic number");
        assert_eq!(words.len(), spv.len() / 4);
    }
    assert_ne!(YUU_VERT_SPV, YUU_FRAG_SPV, "vertex and fragment shaders differ");
}
