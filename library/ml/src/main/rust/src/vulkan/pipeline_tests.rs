//! Tests for the pipeline table: every `Kind` maps to the pipeline
//! field its shader was compiled into, the int4-dot kind falls back
//! to the fp32 int4 pipeline when the device offered no dot pipeline,
//! and every SPIR-V blob in the table is a whole number of words.

use super::{Shaders, SPIRV};
use crate::nets::Kind;
use ash::vk;
use ash::vk::Handle as _;

fn handle(raw: u64) -> vk::Pipeline {
    vk::Pipeline::from_raw(raw)
}

/// A `Shaders` with a distinct fake pipeline in every field, numbered
/// in declaration order starting at 1. No device is involved: `for_kind`
/// is a pure table lookup, and the handles are never submitted.
fn numbered() -> Shaders {
    let mut shaders: Shaders = unsafe { std::mem::zeroed() };
    let mut next = 0u64;
    let mut assign = |slot: &mut vk::Pipeline| {
        next += 1;
        *slot = handle(next);
    };
    assign(&mut shaders.conv);
    assign(&mut shaders.conv_transpose);
    assign(&mut shaders.maxpool);
    assign(&mut shaders.avgpool);
    assign(&mut shaders.resize);
    assign(&mut shaders.resize_nearest);
    assign(&mut shaders.gap);
    assign(&mut shaders.add);
    assign(&mut shaders.mul_bcast);
    assign(&mut shaders.affine);
    assign(&mut shaders.layernorm);
    assign(&mut shaders.instancenorm);
    assign(&mut shaders.attn_scores);
    assign(&mut shaders.softmax);
    assign(&mut shaders.attn_apply);
    assign(&mut shaders.attn_scores_relative);
    assign(&mut shaders.attn_apply_relative);
    assign(&mut shaders.attn_scores_banded);
    assign(&mut shaders.attn_apply_banded);
    assign(&mut shaders.embed);
    assign(&mut shaders.conv_int8);
    assign(&mut shaders.conv_point);
    assign(&mut shaders.conv_point_int8);
    assign(&mut shaders.mul);
    assign(&mut shaders.constant);
    assign(&mut shaders.add_bcast);
    assign(&mut shaders.rotary);
    assign(&mut shaders.attn_scores_cached);
    assign(&mut shaders.attn_apply_cached);
    assign(&mut shaders.conv_vec_int8);
    assign(&mut shaders.softmax_causal);
    assign(&mut shaders.softmax_prefix);
    assign(&mut shaders.cache_write);
    assign(&mut shaders.softcap);
    assign(&mut shaders.activate);
    assign(&mut shaders.gated_activate);
    assign(&mut shaders.conv_vec_int4);
    assign(&mut shaders.conv_point_int4);
    assign(&mut shaders.conv_vec_q2k);
    assign(&mut shaders.conv_point_q2k);
    assign(&mut shaders.conv_q2k);
    assign(&mut shaders.mul_scalar);
    assign(&mut shaders.clamp);
    assign(&mut shaders.rmsnorm);
    assign(&mut shaders.quantize);
    assign(&mut shaders.argmax);
    assign(&mut shaders.argmax_fold);
    assign(&mut shaders.topk);
    assign(&mut shaders.quantize_blocks);
    assign(&mut shaders.conv_point_int4_dot);
    shaders
}

#[test]
fn every_kind_maps_to_its_own_pipeline() {
    let shaders = numbered();
    // Numbered in the order assigned above.
    let cases: &[(Kind, u64)] = &[
        (Kind::Conv, 1),
        (Kind::ConvTranspose, 2),
        (Kind::MaxPool, 3),
        (Kind::AvgPool, 4),
        (Kind::Resize, 5),
        (Kind::ResizeNearest, 6),
        (Kind::GlobalAvgPool, 7),
        (Kind::Add, 8),
        (Kind::MulBroadcast, 9),
        (Kind::Affine, 10),
        (Kind::LayerNorm, 11),
        (Kind::InstanceNorm, 12),
        (Kind::AttnScores, 13),
        (Kind::Softmax, 14),
        (Kind::AttnApply, 15),
        (Kind::AttnScoresRelative, 16),
        (Kind::AttnApplyRelative, 17),
        (Kind::AttnScoresBanded, 18),
        (Kind::AttnApplyBanded, 19),
        (Kind::Embed, 20),
        (Kind::ConvInt8, 21),
        (Kind::ConvPoint, 22),
        (Kind::ConvPointInt8, 23),
        (Kind::Mul, 24),
        (Kind::Constant, 25),
        (Kind::AddBroadcast, 26),
        (Kind::Rotary, 27),
        (Kind::AttnScoresCached, 28),
        (Kind::AttnApplyCached, 29),
        (Kind::ConvVecInt8, 30),
        (Kind::SoftmaxCausal, 31),
        (Kind::SoftmaxPrefix, 32),
        (Kind::CacheWrite, 33),
        (Kind::Softcap, 34),
        (Kind::Activate, 35),
        (Kind::GatedActivate, 36),
        (Kind::ConvVecInt4, 37),
        (Kind::ConvPointInt4, 38),
        (Kind::ConvVecQ2K, 39),
        (Kind::ConvPointQ2K, 40),
        (Kind::ConvQ2K, 41),
        (Kind::MulScalar, 42),
        (Kind::Clamp, 43),
        (Kind::RmsNorm, 44),
        (Kind::Quantize, 45),
        (Kind::Argmax, 46),
        (Kind::ArgmaxFold, 47),
        (Kind::TopK, 48),
        (Kind::QuantizeBlocks, 49),
        (Kind::ConvPointInt4Dot, 50),
    ];
    for &(kind, want) in cases {
        assert!(shaders.for_kind(kind) == handle(want), "{kind:?}");
    }
}

#[test]
fn the_int4_dot_kind_falls_back_when_no_dot_pipeline_was_built() {
    let mut shaders = numbered();
    shaders.conv_point_int4_dot = vk::Pipeline::null();
    assert!(shaders.for_kind(Kind::ConvPointInt4Dot) == shaders.conv_point_int4);
    shaders.conv_point_int4_dot = handle(999);
    assert!(shaders.for_kind(Kind::ConvPointInt4Dot) == handle(999));
}

#[test]
fn every_spirv_blob_is_word_aligned_and_nonempty() {
    assert_eq!(SPIRV.len(), 49);
    for (i, blob) in SPIRV.iter().enumerate() {
        assert!(!blob.is_empty(), "shader {i} is empty");
        assert_eq!(blob.len() % 4, 0, "shader {i} is not a whole number of words");
        // The SPIR-V magic number, little-endian.
        assert_eq!(&blob[0..4], &[0x03, 0x02, 0x23, 0x07], "shader {i} has no SPIR-V magic");
    }
}
