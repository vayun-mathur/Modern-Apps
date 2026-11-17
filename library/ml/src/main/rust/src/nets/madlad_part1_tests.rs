//! Host tests for MADLAD's host helpers (`madlad_part1.rs`): the T5
//! relative-position bucket, the class-split arithmetic and the relative
//! bias gather. Bucket expectations are hand-computed from transformers'
//! `modeling_t5.py` bucketing, not from this implementation.

use super::*;
use crate::preprocess::f32_to_f16;
use crate::weights::{Blob, Dtype, Offsets, Reader, Tensor};

#[test]
fn tokens_split_evenly_across_the_four_class_shards() {
    assert_eq!(split_of(0), (0, 0));
    assert_eq!(split_of(63_999), (0, 63_999));
    assert_eq!(split_of(64_000), (1, 0));
    assert_eq!(split_of(255_999), (3, 63_999));
}

#[test]
fn bidirectional_buckets_spend_half_the_range_on_keys_ahead() {
    // Same position, and keys behind the query: exact low buckets.
    assert_eq!(relative_bucket(0, true), 0);
    assert_eq!(relative_bucket(-1, true), 1);
    assert_eq!(relative_bucket(-7, true), 7);
    // Keys ahead get the upper half (16..32 with 32 buckets).
    assert_eq!(relative_bucket(1, true), 17);
    assert_eq!(relative_bucket(7, true), 23);
    // The logarithmic region: 8 is the first inexact bucket, and anything at
    // or past max_distance saturates the half-range.
    assert_eq!(relative_bucket(-8, true), 8);
    assert_eq!(relative_bucket(-128, true), 15);
    assert_eq!(relative_bucket(-10_000, true), 15);
    assert_eq!(relative_bucket(128, true), 31);
    assert_eq!(relative_bucket(10_000, true), 31);
}

#[test]
fn causal_buckets_clamp_keys_ahead_to_zero() {
    assert_eq!(relative_bucket(5, false), 0, "a key ahead, clamped");
    assert_eq!(relative_bucket(0, false), 0);
    assert_eq!(relative_bucket(-1, false), 1);
    assert_eq!(relative_bucket(-15, false), 15);
    // 32 buckets, 16 exact: the rest are logarithmic up to 128.
    assert_eq!(relative_bucket(-128, false), 31);
    assert_eq!(relative_bucket(-10_000, false), 31);
}

/// A `Blob` over owned bytes, for building a `Reader` in tests.
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
        let end = start + into.len();
        let source = self
            .bytes
            .get(start..end)
            .ok_or_else(|| format!("read of {start}..{end} past {}", self.bytes.len()))?;
        into.copy_from_slice(source);
        Ok(())
    }
}

/// A table whose only real tensor is the encoder relative-bias table, with
/// value `bucket * 16 + head` in every cell (exact in fp16).
fn bias_reader() -> (Offsets, Bytes) {
    let mut tensors = vec![
        Tensor { rank: 1, dims: [1, 0, 0, 0], offset: 0, len: 1, dtype: Dtype::F16 };
        TABLE_ENC + 1
    ];
    tensors[TABLE_ENC] = Tensor {
        rank: 2,
        dims: [BUCKETS, HEADS, 0, 0],
        offset: 0,
        len: BUCKETS * HEADS,
        dtype: Dtype::F16,
    };
    let mut bytes = Vec::new();
    for bucket in 0..BUCKETS {
        for head in 0..HEADS {
            bytes.extend_from_slice(&f32_to_f16((bucket * HEADS + head) as f32).to_le_bytes());
        }
    }
    let blob = Bytes { bytes, tensors: tensors.clone() };
    (Offsets::from_test(tensors), blob)
}

#[test]
fn the_bias_gather_lays_heads_out_as_channels() {
    let (offsets, blob) = bias_reader();
    let reader = Reader::new(&offsets, &blob);
    let out = relative_bias(reader, TABLE_ENC, &[0, 1], &[0, 1, 2], true).expect("bias");
    assert_eq!(out.len(), HEADS as usize * 2 * 3);
    // Buckets by hand: (q0,k0)=0, (q0,k1)=17, (q0,k2)=18, (q1,k0)=1.
    let at = |head: usize, qi: usize, ki: usize| out[head * 6 + qi * 3 + ki];
    assert_eq!(at(0, 0, 0), 0.0);
    assert_eq!(at(0, 0, 1), (17 * HEADS) as f32);
    assert_eq!(at(3, 0, 2), (18 * HEADS + 3) as f32);
    assert_eq!(at(2, 1, 0), (HEADS + 2) as f32);
    assert!(relative_bias(reader, TABLE_ENC + 5, &[0], &[0], true).is_err(), "not a table");
}

#[test]
fn embedding_refuses_a_token_past_the_vocabulary_before_reading() {
    let offsets = Offsets::empty();
    let blob = Bytes { bytes: Vec::new(), tensors: Vec::new() };
    let reader = Reader::new(&offsets, &blob);
    assert!(embed_positions(reader, &[256_000], 0).is_err(), "past the vocabulary");
    assert!(embed_positions(reader, &[u32::MAX], 0).is_err());
}
