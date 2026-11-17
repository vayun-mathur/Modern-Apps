use super::*;

#[test]
fn empty_command_sends_one_empty_last_block() {
    assert_eq!(
        build_store_data_apdus(&[], STORE_DATA_CHUNK),
        vec![vec![0x80, 0xE2, 0x91, 0x00, 0x00]]
    );
}

#[test]
fn single_block_is_last_with_p1_91() {
    assert_eq!(
        build_store_data_apdus(&[0xBF, 0x2E, 0x00], STORE_DATA_CHUNK),
        vec![vec![0x80, 0xE2, 0x91, 0x00, 0x03, 0xBF, 0x2E, 0x00]]
    );
}

#[test]
fn multi_block_chunking_marks_only_last() {
    let cmd: Vec<u8> = (0..130u8).collect();
    let apdus = build_store_data_apdus(&cmd, 63);
    assert_eq!(apdus.len(), 3);
    assert_eq!(&apdus[0][..5], &[0x80, 0xE2, 0x11, 0x00, 63]);
    assert_eq!(&apdus[1][..5], &[0x80, 0xE2, 0x11, 0x01, 63]);
    assert_eq!(&apdus[2][..5], &[0x80, 0xE2, 0x91, 0x02, 4]);
    // Payloads reassemble to the original command.
    let joined: Vec<u8> = apdus.iter().flat_map(|a| a[5..].iter().copied()).collect();
    assert_eq!(joined, cmd);
}

#[test]
fn exact_chunk_multiple_has_no_trailing_empty_block() {
    let apdus = build_store_data_apdus(&[0xAA; 126], 63);
    assert_eq!(apdus.len(), 2);
    assert_eq!(apdus[1][2], 0x91);
    assert_eq!(apdus[1][4], 63);
}

#[test]
fn chunk_is_clamped_to_apdu_limits() {
    // 0 clamps to 1: one APDU per byte.
    let apdus = build_store_data_apdus(&[1, 2, 3], 0);
    assert_eq!(apdus.len(), 3);
    assert!(apdus.iter().all(|a| a[4] == 1));
    // >255 clamps to 255.
    let big: Vec<u8> = vec![0x55; 300];
    let apdus = build_store_data_apdus(&big, 1000);
    assert_eq!(apdus.len(), 2);
    assert_eq!(apdus[0][4], 255);
    assert_eq!(apdus[1][4], 45);
}
