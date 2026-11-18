//! A CPU implementation of every op, and an interpreter that runs a [`Plan`] on the host.
//!
//! # Why this exists
//!
//! Until now nothing checked the *numbers* this runtime produces. Both shipping nets
//! emit a segmentation mask, and a mask is confirmed by looking at it — so a
//! transposed kernel, a half-pixel shift or a misread group count would show up as a
//! slightly worse matte on one driver and nothing at all in CI.
//!
//! That was defensible for two nets whose output is an image. It stops being
//! defensible for the models this runtime is growing into: a face embedding that is
//! quietly wrong corrupts clustering with no visible symptom, and a CTC decode that is
//! one logit out returns confident nonsense. So every op gets a reference here, and
//! every op's semantics get pinned by a fixture that was computed by hand rather than
//! by running this code.
//!
//! # What it is a reference *for*
//!
//! Not for the shaders — they cannot run on the host, so this cannot diff against
//! them. It is a reference for the **plan**: [`super::Builder`] resolves shapes, group
//! splits, pads and arena offsets, and running the resolved [`Push`] blocks through an
//! independent implementation is what shows that arithmetic is right end to end.
//!
//! The op bodies below are written from ONNX's definitions. Where they agree with the
//! corresponding `.comp` file that is the result being asserted, not a shortcut: the
//! shader is the thing under test, and a fixture that was derived from it would test
//! nothing.
//!
//! # fp16 storage, fp32 arithmetic — reproduced exactly
//!
//! The shaders keep activations and weights in fp16 and accumulate in fp32.
//! [`Reference`] holds its arena as `f32`, but **every store is round-tripped through
//! fp16 first**, so each value is exactly the one the device would hold. Accumulation
//! order matches the shaders' loop nesting too, which is what makes a bit-for-bit
//! comparison against a device run meaningful rather than approximate.
//!
//! # Test-only
//!
//! `#[cfg(test)]`, so none of it reaches the shipped `.so`.

use std::cell::RefCell;

use super::{Id, Kind, Op, Plan, Push, Shape, SoftmaxMode, WeightSource};
use crate::preprocess::{f16_to_f32, f32_to_f16};

/// The `act` codes from `common.glsl`, which [`super::Act::code`] produces.
mod act {
    /// Store the accumulator unchanged.
    pub const NONE: u32 = 0;
    /// `max(x, 0)`.
    pub const RELU: u32 = 1;
    /// ONNX `HardSwish` at its default alpha and beta.
    pub const HARDSWISH: u32 = 2;
    /// The logistic function.
    pub const SIGMOID: u32 = 3;
    /// `x < 0 ? slope[c] * x : x`.
    pub const PRELU: u32 = 4;
    /// `clamp(x, 0, 1)`, a normalised `HardSigmoid`.
    pub const CLIP01: u32 = 5;
    /// `x * sigmoid(x)`.
    pub const SWISH: u32 = 6;
    /// `clamp(x, 0, 6)`, MobileNetV2's ReLU6.
    pub const RELU6: u32 = 7;
    /// The exact GELU. See `super::super::Act::Gelu`.
    pub const GELU: u32 = 8;
}

/// The fused activation, mirroring `activate` in `common.glsl`.
///
/// `slope` is the PReLU coefficient for this output channel, already fetched; the
/// others ignore it.
fn activate(x: f32, kind: u32, slope: f32) -> f32 {
    match kind {
        act::RELU => x.max(0.0),
        act::HARDSWISH => x * (x * (1.0 / 6.0) + 0.5).clamp(0.0, 1.0),
        act::SIGMOID => 1.0 / (1.0 + (-x).exp()),
        act::PRELU => {
            if x < 0.0 {
                x * slope
            } else {
                x
            }
        }
        act::CLIP01 => x.clamp(0.0, 1.0),
        act::SWISH => x / (1.0 + (-x).exp()),
        act::RELU6 => x.clamp(0.0, 6.0),
        // The exact GELU, not the tanh approximation: [`super::erf`] is the same A&S 7.1.26
        // series the shader uses, so the two agree well inside fp16.
        act::GELU => 0.5 * x * (1.0 + super::erf(x * std::f32::consts::FRAC_1_SQRT_2)),
        _ => x,
    }
}

/// Round `value` to fp16 and back, which is what storing it in the arena does.
fn through_f16(value: f32) -> f32 {
    f16_to_f32(f32_to_f16(value))
}

/// Run `plan` on the host and return its outputs, in [`Plan::outputs`] order.
///
/// `weights` is the `.maml` data section verbatim — the same bytes the device gets —
/// so this accepts [`crate::weights::Weights::data`] and a shipped asset directly.
/// `inputs` is one fp32 slice per [`Plan::inputs`] entry; each is rounded to fp16 on
/// the way in, exactly as an upload would.
pub fn run_multi(
    plan: &Plan,
    weights: &[u8],
    inputs: &[&[f32]],
) -> Result<Vec<Vec<f32>>, String> {
    run_multi_at(plan, weights, inputs, 0, 0)
}

/// [`run_multi`] with the step parameters a decode plan reads.
///
/// `prefix` is [`crate::vulkan::run::StepParams::prefix`], the cache positions already written,
/// and `window_start` the first position a sliding-window layer may attend to. Both are ignored
/// by every op whose `Push::dyn_keys` is zero, which is every op in every net that does not
/// decode.
pub fn run_multi_at(
    plan: &Plan,
    weights: &[u8],
    inputs: &[&[f32]],
    prefix: u32,
    window_start: u32,
) -> Result<Vec<Vec<f32>>, String> {
    let mut reference = Reference::new(plan, weights, inputs)?;
    reference.prefix = prefix;
    reference.window_start = window_start;
    reference.execute(plan)?;
    plan.outputs
        .iter()
        .map(|out| reference.read(out.at, out.shape.len()))
        .collect()
}

/// [`run_multi`] for the single-input, single-output nets.
pub fn run(plan: &Plan, weights: &[u8], input: &[f32]) -> Result<Vec<f32>, String> {
    let outputs = run_multi(plan, weights, &[input])?;
    match <[Vec<f32>; 1]>::try_from(outputs) {
        Ok([only]) => Ok(only),
        Err(other) => Err(format!("this net has {} outputs, not one", other.len())),
    }
}

/// A host execution of a [`Plan`]: the activation arena, and the weights it reads.
pub struct Reference {
    /// One entry per fp16 element of the arena. Always an exactly-representable fp16
    /// value; see the note on precision in the module docs.
    arena: Vec<f32>,
    /// The weights blob, decoded once. Decoding per multiply-accumulate would be
    /// exact too, and slow enough to make a whole-net run untestable.
    weights: Vec<f32>,
    /// The same blob undecoded, for the one op whose weights are not fp16.
    ///
    /// [`Kind::ConvInt8`] reads its kernel a byte at a time through what the shader sees as a
    /// `uint` view of the weights buffer. Keeping the bytes as well as the decoded floats costs a
    /// third more memory in a test-only interpreter and is what lets an int8 net be checked on the
    /// host at all — without it the only oracle for 605 MB of quantised weights would be a phone.
    bytes: Vec<u8>,
    /// The device's [`crate::vulkan::run::StepParams`], for the ops that read them.
    ///
    /// The interpreter has no descriptor set, so the values the shaders would fetch from binding 3
    /// are held here instead. Both zero unless [`run_multi_at`] set them, which keeps every
    /// existing caller and every fixture on the fixed-length path.
    prefix: u32,
    window_start: u32,
}

include!("reference_part1.rs");
include!("reference_part2.rs");
include!("reference_part13.rs");
include!("reference_part11.rs");
include!("reference_part3.rs");
include!("reference_part4.rs");
include!("reference_part12.rs");
#[cfg(test)]
mod tests {
    use super::super::{
        embed_lanes, maia, mobilefacenet, modnet, ppocr_det, ppocr_rec, scrfd, selfie, supertonic_duration, supertonic_sampler,
        supertonic_text, supertonic_vocoder, tinyclip, u2netp, whisper, Act, Builder, EMBED_LANE,
    };
    use super::*;
include!("reference_part5.rs");
include!("reference_part6.rs");
include!("reference_part7.rs");
include!("reference_part8.rs");
include!("reference_part9.rs");
include!("reference_part10.rs");
}

#[cfg(test)]
#[path = "reference_tests.rs"]
mod reference_tests;

#[cfg(test)]
#[path = "reference_ops_tests.rs"]
mod reference_ops_tests;

#[cfg(test)]
#[path = "reference_ops_mutated_tests.rs"]
mod reference_ops_mutated_tests;
