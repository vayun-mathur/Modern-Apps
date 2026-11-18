//! Host-reader tests: the `Reader` dequantising row/bulk reads over a
//! synthetic `.maml`, their dtype/range refusals, and the streamed
//! (file-backed) reader. Expectations are hand-computed from the
//! quantisation definitions (int8: `value * per-row scale`; int4:
//! signed nibble * per-block scale; Q2_K: `d^2 * q * lo - dmin * d * hi`).

use super::{graph, write_mixed, Blob, Dtype, Fixture, Offsets, Reader, Streamed, Tensor, Weights};
use std::io::Write as _;

fn parsed(tensors: &[Fixture]) -> Weights {
    let blob = write_mixed(graph::SUPERTONIC_VE, tensors);
    Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses")
}

fn close(got: &[f32], want: &[f32]) {
    assert_eq!(got.len(), want.len(), "length: got {got:?}, want {want:?}");
    for (g, w) in got.iter().zip(want) {
        assert!((g - w).abs() < 1e-3, "got {got:?}, want {want:?}");
    }
}

#[test]
fn fp16_reads_decode_and_describe_reports_the_tensor() {
    let weights = parsed(&[Fixture::F16(vec![2, 2], vec![1.0, -2.0, 0.5, 4.0])]);
    let reader = weights.reader();
    close(&reader.fp16(0, &[2, 2]).expect("fp16 read"), &[1.0, -2.0, 0.5, 4.0]);
    assert_eq!(weights.tensors().len(), 1, "the in-memory blob serves its table");
    let tensor = reader.describe(0, &[2, 2]).expect("describe");
    assert_eq!(tensor.len, 4);
    assert_eq!(reader.file_id().1, weights.data().len() as u64);
    assert!(reader.describe(0, &[4]).is_err(), "a wrong shape is refused");
    assert!(!weights.is_empty());
    assert_eq!(weights.len(), 1);
}

#[test]
fn fp16_reads_refuse_quantised_tensors() {
    let weights = parsed(&[Fixture::I8(vec![1, 2], vec![1, 2])]);
    let err = weights.reader().fp16(0, &[1, 2]).expect_err("int8 is not fp16");
    assert!(err.contains("host reads fp16"), "{err}");
}

#[test]
fn int8_row_dequantises_with_its_own_rows_scale() {
    let weights = parsed(&[
        Fixture::I8(vec![2, 3], vec![1, -2, 3, 4, 5, -6]),
        Fixture::F16(vec![2], vec![0.5, 2.0]),
    ]);
    let reader = weights.reader();
    close(&reader.int8_row(0, 1, &[2, 3], 0).expect("row 0"), &[0.5, -1.0, 1.5]);
    close(&reader.int8_row(0, 1, &[2, 3], 1).expect("row 1"), &[8.0, 10.0, -12.0]);
    assert!(reader.int8_row(0, 1, &[2, 3], 2).is_err(), "row past the end");
}

#[test]
fn int8_row_refuses_wrong_dtypes_on_both_tensors() {
    let weights = parsed(&[
        Fixture::F16(vec![2, 3], vec![0.0; 6]),
        Fixture::I8(vec![2], vec![1, 2]),
    ]);
    let reader = weights.reader();
    assert!(reader.int8_row(0, 1, &[2, 3], 0).is_err(), "fp16 kernel is not int8");
    // Scale tensor (index 1) has the right shape but a quantised dtype.
    let weights = parsed(&[
        Fixture::I8(vec![2, 3], vec![1, 2, 3, 4, 5, 6]),
        Fixture::I8(vec![2], vec![1, 2]),
    ]);
    let err = weights.reader().int8_row(0, 1, &[2, 3], 0).expect_err("quantised scale");
    assert!(err.contains("a scale is fp16"), "{err}");
}

#[test]
fn int8_all_dequantises_every_row_in_one_read() {
    let weights = parsed(&[
        Fixture::I8(vec![2, 3], vec![1, -2, 3, 4, 5, -6]),
        Fixture::F16(vec![2], vec![0.5, 2.0]),
    ]);
    close(
        &weights.reader().int8_all(0, 1, &[2, 3]).expect("bulk read"),
        &[0.5, -1.0, 1.5, 8.0, 10.0, -12.0],
    );
    assert!(weights.reader().int8_all(1, 1, &[2]).is_err(), "fp16 is not int8");
}

#[test]
fn q2k_row_unpacks_superblocks_with_the_table_pair() {
    let mut row1 = vec![0u8; 84];
    row1[0] = 0x21; // lo = 1, hi = 2 for lane 0
    row1[16] = 3; // q = 3 for the first tap
    let mut payload = vec![0u8; 84];
    payload.extend_from_slice(&row1);
    let weights = parsed(&[
        Fixture::Q2K(vec![2, 256], payload),
        Fixture::F16(vec![4], vec![9.0, 9.0, 1.0, 0.5]), // row 1 pair is (d=1, dmin=0.5)
    ]);
    let reader = weights.reader();
    let out = reader.q2k_row(0, 1, &[2, 256], 1).expect("row 1");
    assert_eq!(out.len(), 256);
    // Lane 0's (lo=1, hi=2) applies to all 16 of its taps:
    // d^2 * q * lo - (dmin * d) * hi is 2 at q=3 and -1 at q=0.
    assert!((out[0] - 2.0).abs() < 1e-3, "first tap {}", out[0]);
    assert!(out[1..16].iter().all(|&v| (v + 1.0).abs() < 1e-3), "lane 0 at q=0: {:?}", &out[1..16]);
    assert!(out[16..].iter().all(|&v| v == 0.0), "the rest of the row is zero");
    assert!(reader.q2k_row(0, 1, &[2, 256], 2).is_err(), "row past the end");
    assert!(reader.q2k_row(1, 1, &[4], 0).is_err(), "fp16 is not Q2_K");
}

#[test]
fn int4_rows_dequantise_signed_nibbles_per_block_scale() {
    let weights = parsed(&[
        Fixture::I4(vec![2, 32], vec![3; 64]),
        Fixture::F16(vec![2, 1], vec![0.5, 2.0]),
    ]);
    let reader = weights.reader();
    close(&reader.int4_row(0, 1, &[2, 32], 0).expect("row 0"), &[1.5; 32]);
    close(&reader.int4_row(0, 1, &[2, 32], 1).expect("row 1"), &[6.0; 32]);
    // Negative nibbles sign-extend: -1 * 0.5 = -0.5.
    let weights = parsed(&[
        Fixture::I4(vec![1, 32], vec![-1; 32]),
        Fixture::F16(vec![1, 1], vec![0.5]),
    ]);
    close(&weights.reader().int4_row(0, 1, &[1, 32], 0).expect("row"), &[-0.5; 32]);
}

#[test]
fn int4_rows_refuse_odd_strides_and_wrong_dtypes() {
    let weights = parsed(&[
        Fixture::I4(vec![1, 3], vec![1, 2, 3]),
        Fixture::F16(vec![1, 1], vec![1.0]),
    ]);
    let err = weights.reader().int4_row(0, 1, &[1, 3], 0).expect_err("odd stride");
    assert!(err.contains("does not start on a byte"), "{err}");
    assert!(weights.reader().int4_row(1, 1, &[1, 1], 0).is_err(), "fp16 is not int4");
    assert!(weights.reader().int4_rows(0, 1, &[1, 3], 1, 1).is_err(), "range past the end");
}

#[test]
fn fp16_rows_reads_a_contiguous_block() {
    let weights = parsed(&[Fixture::F16(vec![3, 2], vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0])]);
    let reader = weights.reader();
    close(&reader.fp16_rows(0, &[3, 2], 1, 2).expect("rows 1..3"), &[3.0, 4.0, 5.0, 6.0]);
    assert!(reader.fp16_rows(0, &[3, 2], 2, 2).is_err(), "block past the end");
}

#[test]
fn from_data_weights_hold_one_f16_tensor() {
    let weights = Weights::from_data(vec![0x00, 0x3c, 0x00, 0xc0]); // 1.0, -2.0 in fp16
    assert_eq!(weights.len(), 1);
    assert!(!weights.is_empty());
    close(&weights.reader().fp16(0, &[2]).expect("read"), &[1.0, -2.0]);
    assert_eq!(weights.offsets().len(), 1);
}

#[test]
fn a_streamed_file_reads_the_same_tensors_from_disk() {
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[Fixture::F16(vec![2], vec![3.0, -1.0])],
    );
    let path = std::env::temp_dir().join(format!("modelrunner-reader-test-{}.maml", std::process::id()));
    std::fs::File::create(&path).expect("create").write_all(&blob).expect("write");
    let file = std::fs::File::open(&path).expect("open");
    let streamed = Streamed::open(file, 0, blob.len() as u64, graph::SUPERTONIC_VE).expect("open streamed");
    assert_eq!(streamed.len(), 1);
    assert!(!streamed.is_empty());
    assert_eq!(streamed.tensors().len(), 1, "the streamed blob serves its table");
    close(&streamed.reader().fp16(0, &[2]).expect("streamed read"), &[3.0, -1.0]);
    assert_eq!(streamed.offsets().len(), 1);
    let _ = std::fs::remove_file(&path);
    // A length that cuts the data section short is refused at open.
    let file = std::fs::File::open("/dev/null").expect("open null");
    assert!(Streamed::open(file, 0, 4, graph::SUPERTONIC_VE).is_err());
}

#[test]
fn parse_refuses_a_header_that_disagrees_with_its_table() {
    let blob = write_mixed(graph::SUPERTONIC_VE, &[Fixture::F16(vec![1], vec![1.0])]);
    // The data offset lives at byte 48 of the 64-byte header: point it
    // somewhere the tensor count does not put it.
    let mut bad = blob.clone();
    bad[48..52].copy_from_slice(&9999u32.to_le_bytes());
    let err = Weights::parse(&bad, graph::SUPERTONIC_VE).expect_err("offset disagrees");
    assert!(err.contains("data starts at 9999"), "{err}");
    // A file cut short inside its own table is refused too.
    let short = &blob[..blob.len() - 20];
    assert!(Weights::parse(short, graph::SUPERTONIC_VE).is_err(), "truncated table");
    // And one whose header is not even whole.
    assert!(Weights::parse(&blob[..32], graph::SUPERTONIC_VE).is_err(), "short header");
}

#[test]
fn shaped_words_refuses_fp16_and_the_fixtures_refuse_int8() {
    use crate::nets::WeightSource as _;
    let weights = parsed(&[Fixture::F16(vec![2], vec![1.0, 2.0])]);
    // The Weights table itself: a quantised-kernel lookup on an fp16
    // tensor is a type error, not a silent reinterpretation.
    assert!(weights.shaped_words(0, &[2]).is_err());
    assert!(weights.offsets().shaped_words(0, &[2]).is_err());
    // The two host fixtures hold fp32 only, so both refuse int8 reads
    // in the same words.
    let given = crate::nets::reference::Given::new(&[(vec![2], vec![1.0, 2.0])]).expect("given");
    assert!(given.shaped_words(0, &[2]).is_err());
    let invented = crate::nets::reference::Invented::new(1);
    assert!(invented.shaped_words(0, &[2]).is_err());
    // A repeated shape query against Invented reuses the first layout.
    let invented = crate::nets::reference::Invented::new(1);
    let first = invented.shaped(0, &[2, 2]).expect("first layout");
    let second = invented.shaped(0, &[2, 2]).expect("cached layout");
    assert_eq!(first, second);
}

/// A `Blob` over owned bytes, for tables `write_mixed` cannot spell
/// (mismatched dtypes and tap counts the parser would refuse).
struct Bytes {
    bytes: Vec<u8>,
    tensors: Vec<Tensor>,
}

impl Blob for Bytes {
    fn data_len(&self) -> u64 {
        self.bytes.len() as u64
    }
    fn tensors(&self) -> &[Tensor] {
        &self.tensors
    }
    fn read_at(&self, offset: u64, into: &mut [u8]) -> Result<(), String> {
        let start = offset as usize;
        let source = self
            .bytes
            .get(start..start + into.len())
            .ok_or_else(|| format!("read at {start} past {}", self.bytes.len()))?;
        into.copy_from_slice(source);
        Ok(())
    }
}

fn tensor(rank: u32, dims: [u32; 4], offset: u32, len: u32, dtype: Dtype) -> Tensor {
    Tensor { rank, dims, offset, len, dtype }
}

#[test]
fn fp16_decodes_the_non_finite_values() {
    // Through the fixture writer first: an f32 infinity is written
    // as the fp16 infinity pattern and reads back as infinity.
    let weights = parsed(&[Fixture::F16(vec![2], vec![f32::INFINITY, f32::NEG_INFINITY])]);
    let out = weights.reader().fp16(0, &[2]).expect("reads");
    assert!(out[0].is_infinite() && out[0] > 0.0, "+inf");
    assert!(out[1].is_infinite() && out[1] < 0.0, "-inf");
    // Raw patterns for the rest, since the fixture writer's
    // f32->f16 turns a NaN into an infinity: 0x7C00, 0xFC00, 0x7E00
    // little-endian, plus the smallest subnormal 0x0001.
    let mut bytes = Vec::new();
    for pattern in [0x7C00u16, 0xFC00, 0x7E00, 0x0001] {
        bytes.extend_from_slice(&pattern.to_le_bytes());
    }
    let blob = Bytes { bytes, tensors: vec![tensor(1, [4, 0, 0, 0], 0, 4, Dtype::F16)] };
    let offsets = Offsets::from_test(blob.tensors.clone());
    let out = Reader::new(&offsets, &blob).fp16(0, &[4]).expect("reads");
    assert!(out[0].is_infinite() && out[0] > 0.0, "+inf");
    assert!(out[1].is_infinite() && out[1] < 0.0, "-inf");
    assert!(out[2].is_nan(), "nan");
    assert!((out[3] - 5.960_464_5e-8).abs() < 1e-12, "the smallest subnormal: {}", out[3]);
}

#[test]
fn quantised_reads_refuse_quantised_scales_and_bad_tap_counts() {
    // Q2_K: the scale tensor must be fp16, and a row's taps must be
    // a whole number of 256-value superblocks.
    let blob = Bytes {
        bytes: vec![0u8; 168],
        tensors: vec![
            tensor(2, [2, 256, 0, 0], 0, 512, Dtype::Q2K),
            tensor(1, [4, 0, 0, 0], 512, 4, Dtype::Q2K),
        ],
    };
    let offsets = Offsets::from_test(blob.tensors.clone());
    let reader = Reader::new(&offsets, &blob);
    let err = reader.q2k_row(0, 1, &[2, 256], 0).expect_err("quantised scale");
    assert!(err.contains("a scale is fp16"), "{err}");

    let blob = Bytes {
        bytes: Vec::new(),
        tensors: vec![tensor(2, [2, 100, 0, 0], 0, 200, Dtype::Q2K)],
    };
    let offsets = Offsets::from_test(blob.tensors.clone());
    let reader = Reader::new(&offsets, &blob);
    let err = reader.q2k_row(0, 0, &[2, 100], 0).expect_err("tap count");
    assert!(err.contains("not block-divisible"), "{err}");

    // int8 and int4 rows refuse quantised scales the same way.
    let blob = Bytes {
        bytes: vec![0u8; 64],
        tensors: vec![
            tensor(2, [2, 4, 0, 0], 0, 8, Dtype::I8),
            tensor(1, [2, 0, 0, 0], 8, 2, Dtype::I8),
        ],
    };
    let offsets = Offsets::from_test(blob.tensors.clone());
    let reader = Reader::new(&offsets, &blob);
    let err = reader.int8_row(0, 1, &[2, 4], 0).expect_err("int8 scale");
    assert!(err.contains("a scale is fp16"), "{err}");

    let blob = Bytes {
        bytes: vec![0u8; 64],
        tensors: vec![
            tensor(2, [2, 32, 0, 0], 0, 64, Dtype::I4),
            tensor(2, [2, 1, 0, 0], 32, 2, Dtype::I4),
        ],
    };
    let offsets = Offsets::from_test(blob.tensors.clone());
    let reader = Reader::new(&offsets, &blob);
    let err = reader.int4_row(0, 1, &[2, 32], 0).expect_err("int4 scale");
    assert!(err.contains("a scale is fp16"), "{err}");

    // The bulk int8 read refuses a quantised scale, and the fp16
    // block read refuses a quantised tensor, in the same words.
    let blob = Bytes {
        bytes: vec![0u8; 64],
        tensors: vec![
            tensor(2, [2, 4, 0, 0], 0, 8, Dtype::I8),
            tensor(1, [2, 0, 0, 0], 8, 2, Dtype::I8),
        ],
    };
    let offsets = Offsets::from_test(blob.tensors.clone());
    let reader = Reader::new(&offsets, &blob);
    let err = reader.int8_all(0, 1, &[2, 4]).expect_err("bulk scale");
    assert!(err.contains("a scale is fp16"), "{err}");
    let err = reader.fp16_rows(0, &[2, 4], 0, 1).expect_err("fp16 rows of int8");
    assert!(err.contains("host reads fp16"), "{err}");
}
