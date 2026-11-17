//! Host tests for the `.maml` reader's format arithmetic (`weights.rs`):
//! dtype byte counts and the header parser's refusals, with hand-built
//! files. A parser that accepts a malformed asset fails later as a driver
//! reset, so every refusal branch gets a case.

use super::*;
use crate::preprocess::f32_to_f16;

/// A minimal valid v1 file: one fp16 tensor `[2, 2]` holding 1..4, for
/// `graph::SELFIE`.
fn valid_file() -> Vec<u8> {
    let mut out = vec![0u8; HEADER_BYTES];
    out[0..4].copy_from_slice(&MAGIC);
    out[4..8].copy_from_slice(&1u32.to_le_bytes());
    out[8..12].copy_from_slice(&graph::SELFIE.to_le_bytes());
    out[12..16].copy_from_slice(&1u32.to_le_bytes());
    // data_offset = 64 + 32, data_len = 4 elements * 2 bytes.
    out[48..52].copy_from_slice(&96u32.to_le_bytes());
    out[52..56].copy_from_slice(&8u32.to_le_bytes());
    // Tensor entry at 64: rank 2, dims [2, 2], dtype f16, offset 0, len 4.
    out.extend_from_slice(&2u32.to_le_bytes());
    out.extend_from_slice(&2u32.to_le_bytes());
    out.extend_from_slice(&2u32.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes()); // dtype
    out.extend_from_slice(&0u32.to_le_bytes()); // offset
    out.extend_from_slice(&4u32.to_le_bytes()); // len
    for value in [1.0f32, 2.0, 3.0, 4.0] {
        out.extend_from_slice(&f32_to_f16(value).to_le_bytes());
    }
    out
}

#[test]
fn dtype_byte_counts_match_the_format() {
    assert_eq!(Dtype::F16.bytes(4), 8);
    assert_eq!(Dtype::I8.bytes(4), 4);
    assert_eq!(Dtype::I4.bytes(4), 2);
    assert_eq!(Dtype::I4.bytes(5), 3, "an odd tap count pads the last nibble");
    assert_eq!(Dtype::Q2K.bytes(256), 84);
    assert_eq!(Dtype::Q2K.bytes(257), 168, "a short block still costs a superblock");
    assert_eq!(Dtype::Q2K.bytes(0), 0);
    assert!(!Dtype::F16.is_quantised());
    assert!(Dtype::I8.is_quantised() && Dtype::I4.is_quantised() && Dtype::Q2K.is_quantised());
}

#[test]
fn a_minimal_file_parses_and_reads_back() {
    let bytes = valid_file();
    let weights = Weights::parse(&bytes, graph::SELFIE).expect("the file parses");
    assert_eq!(weights.graph_id, graph::SELFIE);
    assert_eq!(weights.data().len(), 8);
    let reader = weights.reader();
    assert_eq!(reader.fp16(0, &[2, 2]).expect("the tensor"), vec![1.0, 2.0, 3.0, 4.0]);
    assert!(reader.fp16(0, &[4]).is_err(), "wrong shape is refused");
    assert!(reader.fp16(1, &[1]).is_err(), "past the table is refused");
}

#[test]
fn malformed_files_are_refused_at_the_header() {
    let good = valid_file();
    assert!(Weights::parse(&good[..32], graph::SELFIE).is_err(), "short header");
    let mut bad = good.clone();
    bad[0] = b'X';
    assert!(Weights::parse(&bad, graph::SELFIE).is_err(), "bad magic");
    assert!(Weights::parse(&good, graph::U2NETP).is_err(), "wrong graph");
    let mut bad = good.clone();
    bad[4..8].copy_from_slice(&99u32.to_le_bytes());
    assert!(Weights::parse(&bad, graph::SELFIE).is_err(), "future version");
    let mut bad = good.clone();
    bad[4..8].copy_from_slice(&0u32.to_le_bytes());
    assert!(Weights::parse(&bad, graph::SELFIE).is_err(), "version zero");
    // Trailing bytes: the whole slice must end where the sections end.
    let mut padded = good.clone();
    padded.push(0);
    assert!(Weights::parse(&padded, graph::SELFIE).is_err(), "trailing bytes");
    // Truncated data section.
    assert!(Weights::parse(&good[..good.len() - 2], graph::SELFIE).is_err());
}

#[test]
fn malformed_tensor_entries_are_refused() {
    let good = valid_file();
    let entry = HEADER_BYTES;
    // Rank 0 and rank 5 are both outside 1..=4.
    for rank in [0u32, 5] {
        let mut bad = good.clone();
        bad[entry..entry + 4].copy_from_slice(&rank.to_le_bytes());
        assert!(Weights::parse(&bad, graph::SELFIE).is_err(), "rank {rank}");
    }
    // An unknown dtype.
    let mut bad = good.clone();
    bad[entry + 20..entry + 24].copy_from_slice(&7u32.to_le_bytes());
    assert!(Weights::parse(&bad, graph::SELFIE).is_err(), "dtype 7");
    // Length that disagrees with the dims ([2,2] is 4, not 5).
    let mut bad = good.clone();
    bad[entry + 28..entry + 32].copy_from_slice(&5u32.to_le_bytes());
    assert!(Weights::parse(&bad, graph::SELFIE).is_err(), "len vs dims");
    // An offset off the 16-byte alignment.
    let mut bad = good.clone();
    bad[entry + 24..entry + 28].copy_from_slice(&2u32.to_le_bytes());
    assert!(Weights::parse(&bad, graph::SELFIE).is_err(), "unaligned offset");
}

#[test]
fn offsets_answer_shape_checks_without_a_file() {
    let empty = Offsets::empty();
    assert!(empty.is_empty());
    assert_eq!(empty.len(), 0);
    assert!(empty.tensor(0).is_err());
    let table = Offsets::from_test(vec![Tensor {
        rank: 2,
        dims: [2, 2, 0, 0],
        offset: 0,
        len: 4,
        dtype: Dtype::F16,
    }]);
    assert_eq!(table.shaped(0, &[2, 2]).expect("the tensor").len, 4);
    assert!(table.shaped(0, &[4]).is_err(), "shape mismatch names the tensor");
    assert!(table.shaped(1, &[1]).is_err(), "out of range");
}

/// A hand-built file with four tensors: an int8 `[2, 4]` at offset 0, its
/// fp16 per-row scale `[2]` at 16, an int4 `[1, 32]` at 32 (16 packed bytes)
/// and its fp16 block scale `[1, 1]` at 48. The data section is 50 bytes.
fn quantised_file() -> Vec<u8> {
    let mut out = vec![0u8; HEADER_BYTES];
    out[0..4].copy_from_slice(&MAGIC);
    out[4..8].copy_from_slice(&1u32.to_le_bytes());
    out[8..12].copy_from_slice(&graph::SELFIE.to_le_bytes());
    out[12..16].copy_from_slice(&4u32.to_le_bytes());
    out[48..52].copy_from_slice(&((HEADER_BYTES + 4 * 32) as u32).to_le_bytes());
    out[52..56].copy_from_slice(&50u32.to_le_bytes());
    let mut entry = |rank: u32, dims: [u32; 4], dtype: u32, offset: u32, len: u32, out: &mut Vec<u8>| {
        out.extend_from_slice(&rank.to_le_bytes());
        for d in dims { out.extend_from_slice(&d.to_le_bytes()); }
        out.extend_from_slice(&dtype.to_le_bytes());
        out.extend_from_slice(&offset.to_le_bytes());
        out.extend_from_slice(&len.to_le_bytes());
    };
    entry(2, [2, 4, 0, 0], 1, 0, 8, &mut out);
    entry(1, [2, 0, 0, 0], 0, 16, 2, &mut out);
    entry(2, [1, 32, 0, 0], 2, 32, 32, &mut out);
    entry(2, [1, 1, 0, 0], 0, 48, 1, &mut out);
    let mut data = vec![0u8; 50];
    data[0..8].copy_from_slice(&[1, -2, 3, -4, 10, 20, -30, 40].map(|v| v as u8));
    for (at, v) in [(16usize, 0.5f32), (18, 2.0), (48, 0.25)] {
        data[at..at + 2].copy_from_slice(&f32_to_f16(v).to_le_bytes());
    }
    // Nibbles low-first: codes [1, -2, 7, -8] as bytes 0x? — low=1, high=-2 (14).
    data[32] = 1 | (14 << 4);
    data[33] = 7 | (8 << 4);
    out.extend_from_slice(&data);
    out
}

#[test]
fn reader_dequantises_int8_and_int4_rows() {
    let bytes = quantised_file();
    let weights = Weights::parse(&bytes, graph::SELFIE).expect("the file parses");
    let reader = weights.reader();
    assert_eq!(
        reader.int8_row(0, 1, &[2, 4], 1).expect("row 1"),
        vec![20.0, 40.0, -60.0, 80.0],
        "int8 values times the row scale of 2"
    );
    assert_eq!(
        reader.int8_row(0, 1, &[2, 4], 0).expect("row 0"),
        vec![0.5, -1.0, 1.5, -2.0]
    );
    assert!(reader.int8_row(0, 1, &[2, 4], 2).is_err(), "row past the table");
    assert!(reader.int8_row(1, 1, &[2], 0).is_err(), "an fp16 tensor is not int8");
    // int4: first four codes are 1, -2, 7, -8, scaled by 0.25; the rest are 0.
    let row = reader.int4_row(2, 3, &[1, 32], 0).expect("the int4 row");
    assert_eq!(&row[..4], &[0.25, -0.5, 1.75, -2.0]);
    assert!(row[4..].iter().all(|&v| v == 0.0));
    assert!(reader.int4_row(0, 1, &[2, 4], 0).is_err(), "an int8 tensor is not int4");
}

#[test]
fn reader_reads_blocks_of_fp16_rows() {
    let bytes = valid_file();
    let weights = Weights::parse(&bytes, graph::SELFIE).expect("the file parses");
    let reader = weights.reader();
    assert_eq!(reader.fp16_rows(0, &[2, 2], 1, 1).expect("row 1"), vec![3.0, 4.0]);
    assert!(reader.fp16_rows(0, &[2, 2], 1, 2).is_err(), "past the last row");
}
