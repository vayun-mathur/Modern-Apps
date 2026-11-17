//! MADLAD's host-side helpers: embedding gather and relative-bias computation.
//!
//! Split from `madlad.rs` along the host/device line: everything here runs on the CPU
//! against [`crate::weights::Reader`], while `madlad.rs` keeps the plan builders. See
//! that module's docs for why the embedding has no scale and no positions, and why the
//! bias arrives as a plan input rather than a weight.

use super::madlad::{
    BUCKETS, CLASSES_PER_SPLIT, D_MODEL, HEAD_SHARED, HEADS, MAX_DISTANCE, TABLE_DEC,
    TABLE_ENC, VOCAB,
};
use crate::weights::Reader;
use super::madlad::split_classes;

/// Which head split holds token `id`, and its row within that split.
///
/// Even splits, so one division: 256,000 = 4 x 64,000.
pub(crate) fn split_of(id: u32) -> (usize, u32) {
    ((id / CLASSES_PER_SPLIT) as usize, id % CLASSES_PER_SPLIT)
}

/// The embedded source for `ids`, in the channel-major layout the plan wants.
///
/// The `[1024, 1, len]` fp16 input to [`Mode::Encode`], as f32 for the caller to upload. No
/// scale and no positions — T5 has neither; see the module docs.
///
/// `past` is accepted and ignored: it keeps the call sites reading the same as NLLB's, where
/// it offsets the sinusoid. Here there is nothing to offset.
///
/// [`Mode::Encode`]: super::madlad::Mode::Encode
pub fn embed_positions(
    weights: Reader<'_>,
    ids: &[u32],
    past: u32,
) -> Result<Vec<f32>, String> {
    let _ = past;
    let width = D_MODEL as usize;
    let mut out = vec![0.0f32; width * ids.len()];
    for (at, &id) in ids.iter().enumerate() {
        if id >= VOCAB {
            return Err(format!("token {id} is past the {VOCAB}-entry vocabulary"));
        }
        let (split, row) = split_of(id);
        let kernel = HEAD_SHARED + split * 3;
        let embedding = weights.q2k_row(
            kernel,
            kernel + 1,
            &[split_classes(split), D_MODEL],
            row,
        )?;
        for (channel, value) in embedding.iter().enumerate() {
            // Channel-major: this runtime indexes `[c, h, w]`, and the export is `[w, c]`.
            let slot = out
                .get_mut(channel * ids.len() + at)
                .ok_or("an embedding row is wider than d_model")?;
            *slot = *value;
        }
    }
    Ok(out)
}

/// T5's relative-position bucket, verbatim from transformers' `modeling_t5.py`.
///
/// `relative_position` is `memory_position - query_position`: negative when the key is
/// behind the query. Bidirectional (the encoder) spends half the buckets on keys ahead;
/// causal (decoder self-attention) clamps those to bucket 0 and spends every bucket behind.
pub fn relative_bucket(relative_position: i32, bidirectional: bool) -> u32 {
    let (mut buckets, mut position) = (0u32, relative_position);
    let num = BUCKETS as i32;
    let (num, max_distance) = if bidirectional {
        let half = num / 2;
        buckets += (u32::from(position > 0)) * half as u32;
        position = position.abs();
        (half, MAX_DISTANCE as i32)
    } else {
        position = (-position).max(0);
        (num, MAX_DISTANCE as i32)
    };
    // Half the buckets are exact increments; the rest are logarithmic up to max_distance.
    let max_exact = num / 2;
    let bucket = if position < max_exact {
        position
    } else {
        let ratio = (position as f32 / max_exact as f32).ln() / (max_distance as f32 / max_exact as f32).ln();
        (max_exact as f32 + ratio * (num - max_exact) as f32) as i32
    };
    buckets + bucket.min(num - 1) as u32
}

/// The `[HEADS, queries, keys]` relative bias for one attention, in channel-major plan layout.
///
/// `table` is the block-0 `[BUCKETS, HEADS]` fp16 table ([`TABLE_ENC`] or [`TABLE_DEC`]);
/// `queries` are the attending positions and `keys` the attended ones, both absolute.
/// The encoder attends bidirectionally over `0..len`; a decode step's single query is `step`
/// over keys `0..=step`; cross-attention never calls this.
///
/// Laid out `[heads, queries, keys]` channel-major — heads as channels — so the net adds it
/// to the score map with one `Add`. Computed in f32 and rounded once, on upload.
pub fn relative_bias(
    weights: Reader<'_>,
    table: usize,
    queries: &[u32],
    keys: &[u32],
    bidirectional: bool,
) -> Result<Vec<f32>, String> {
    let expect = if table == TABLE_ENC {
        "encoder block-0"
    } else if table == TABLE_DEC {
        "decoder block-0"
    } else {
        return Err(format!("tensor {table} is not a relative table"));
    };
    let gathered = weights.fp16(table, &[BUCKETS, HEADS])?;
    let mut out = vec![0.0f32; HEADS as usize * queries.len() * keys.len()];
    for (qi, &q) in queries.iter().enumerate() {
        for (ki, &k) in keys.iter().enumerate() {
            let bucket = relative_bucket(k as i32 - q as i32, bidirectional) as usize;
            for head in 0..HEADS as usize {
                // Table row-major `[bucket, head]`; output channel-major `[head, q, k]`.
                let value = gathered
                    .get(bucket * HEADS as usize + head)
                    .ok_or(format!("{expect} table has no bucket {bucket}"))?;
                let slot = head * queries.len() * keys.len() + qi * keys.len() + ki;
                out[slot] = *value;
            }
        }
    }
    Ok(out)
}

#[cfg(test)]
#[path = "madlad_part1_tests.rs"]
mod madlad_part1_tests;
