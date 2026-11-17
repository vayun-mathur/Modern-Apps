// The Gemma-4 GPU tied head: logits on the device, LiteRT-style.
//
// The text pass (`Mode::DecodeStep`) returns the normed hidden state and the
// host computes `hidden @ HEAD_TABLE^T` in `bridge_part6::tied_head_logits` —
// 4 chunked `fp16_rows` reads that dequant 805 MB fp16->fp32 per token
// (~2 s). This pass runs the same projection on the device: one
// `[1536, 1, 1]` hidden input in, one `[16384, 1, 1]` logits split per chunk
// out, upload-once and submit-per-token.
//
// Two precisions, selected at build time by which chunks the file carries:
// int8 chunks (3 tensors each: kernel, per-channel scale, bias) bind via
// `conv_int8` and auto-route to `ConvVecInt8` at 1 position — ~402 MB total,
// the production path. fp16 chunks (2 tensors: kernel, bias) bind via `conv`
// and route to `ConvPoint` — the fallback that files without int8 chunks
// still satisfy. The runtime prefers int8 when present (see `build_plan`).
//
// The head table lives in the EMBED file, so this plan builds over the
// EMBED offsets (one file per plan — see `Builder::new`), with every other
// EMBED tensor named via `host_tensor` (same complement pattern as
// `nllb::name_host_tensors`). The converter emits int8 chunks after the fp16
// ones (`s10_to_maml.collect_embed`); indices 0..144 are unchanged so older
// files still parse — this plan then fails its `shaped` checks and the
// caller falls back (first to the fp16-chunk plan, then the host head).
//
// One 262144-wide fp16 tensor spans ~768 MiB, far over the ~96 MiB
// segment-window reach at guaranteed 128 MiB `maxStorageBufferRange`
// (`vulkan::segment::for_op` refuses it: "Split the tensor in the
// converter"). 16x16384 int8 chunks are ~25 MB each — inside the window with
// margin on host and P8.
use super::{Act, Builder, Id, Plan, Shape, WeightSource};
use super::gemma4::{D_MODEL, LAYERS, PER_LAYER, VOCAB};
use super::gemma4::embed;

/// Vocab classes per chunk: 262144 / 16.
pub const CLASSES_PER_CHUNK: u32 = 16384;

/// Head chunks the converter emits. Must match `GEMMA4_HEAD_SPLITS`.
pub const HEAD_CHUNKS: usize = 16;

/// First tensor of the fp16 chunked head: kernel of chunk 0.
///
/// Layout after the legacy rank-2 table (tensor 112): chunk `s` owns
/// `HEAD_BASE + s * 2` (fp16 kernel `[CLASSES_PER_CHUNK, 1536, 1, 1]`) and
/// `HEAD_BASE + s * 2 + 1` (fp16 zero bias `[CLASSES_PER_CHUNK]`).
pub const HEAD_BASE: usize = embed::HEAD_CHUNKS;

/// Tensors the fp16 chunked head adds: kernel + bias per chunk.
pub const HEAD_CHUNK_TENSORS: usize = HEAD_CHUNKS * 2;

/// Tensors an EMBED file with fp16 GPU-head chunks holds.
pub const TENSORS_WITH_HEAD: usize = embed::TENSORS_WITH_HEAD;

/// First tensor of the int8 chunked head: kernel of chunk 0.
///
/// Layout after the fp16 chunks: chunk `s` owns `HEAD8_BASE + s * 3` (int8
/// kernel `[CLASSES_PER_CHUNK, 1536, 1, 1]`), `+ 1` (fp16 per-channel scale
/// `[CLASSES_PER_CHUNK]`), `+ 2` (fp16 zero bias `[CLASSES_PER_CHUNK]`).
///
/// In the standalone head file (`graph::GEMMA4_HEAD`, 48 tensors) the chunks
/// start at 0: chunk `s` owns `s * 3 .. s * 3 + 2`. See `build_plan_standalone`.
pub const HEAD8_BASE: usize = embed::HEAD8_CHUNKS;

/// Tensors the int8 chunked head adds: kernel + scale + bias per chunk.
pub const HEAD8_CHUNK_TENSORS: usize = HEAD_CHUNKS * 3;

/// Tensors an EMBED file with int8 GPU-head chunks holds.
pub const TENSORS_WITH_HEAD8: usize = embed::TENSORS_WITH_HEAD8;

/// A `1 x 1` fp16 convolution: the device-side dot of one vocab chunk.
fn point(b: &mut Builder, at: usize, x: Id, out: u32) -> Id {
    b.conv(
        x,
        at,
        out,
        (1, 1),
        (1, 1),
        (1, 1),
        (0, 0, 0, 0),
        1,
        Act::None,
    )
}

/// A `1 x 1` **int8** convolution: the device-side dot of one vocab chunk.
///
/// Same geometry as [`point`], over the int8 triple (kernel, per-channel
/// scale, bias). Auto-routes to `ConvVecInt8` at 1 position — the fix for
/// the fp16 `ConvPoint` 93.75% idle-lane waste, with no code change.
fn point8(b: &mut Builder, at: usize, x: Id, out: u32) -> Id {
    b.conv_int8(x, at, out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None)
}

/// Build the head pass over the EMBED file's offsets.
///
/// One `[1536, 1, 1]` hidden-state input, one `[CLASSES_PER_CHUNK, 1, 1]`
/// fp16 output per chunk, in vocab order. Prefers the int8 chunks when the
/// file carries them (fewer bytes, vector routing); falls back to fp16
/// chunks; fails the `shaped` checks (and the caller falls back to the host
/// head) when the file carries neither. The host concatenates the 16 splits
/// and runs the existing softcap + argmax, exactly as it does for the
/// host-computed logits today.
///
/// `with_argmax` appends a device-side greedy sampler: the 16 splits are
/// concatenated on-device (channel Concat lowers to copies within the arena)
/// and one `Argmax` op reduces to the winning index as two fp16 lanes
/// (`lo = id % 2048`, `hi = id / 2048` — see `Builder::argmax`). The caller
/// reads back 2 values instead of 262144. The full-logits outputs stay too
/// (the Kotlin sampling path still needs them); the argmax output is last.
pub fn build_plan(weights: &dyn WeightSource) -> Result<Plan, String> {
    build_plan_inner(weights, false)
}

/// [`build_plan`] with the device argmax appended. See [`build_plan`].
pub fn build_plan_greedy(weights: &dyn WeightSource) -> Result<Plan, String> {
    build_plan_inner(weights, true)
}

fn build_plan_inner(weights: &dyn WeightSource, greedy: bool) -> Result<Plan, String> {
    // Probe for int8 chunks without marking anything read: `count()` is the
    // file's tensor total, which is exact (145 fp16-only, 193 with int8).
    if weights.count() >= TENSORS_WITH_HEAD8 {
        if let Ok(plan) = build_plan8(weights, greedy) {
            return Ok(plan);
        }
        // Wrong shapes: fall through to the fp16 chunks rather than failing.
    }
    build_plan_fp16(weights, greedy)
}

/// Tensors the standalone head file holds: 16 int8 triples, nothing else.
pub const HEAD_FILE_TENSORS: usize = HEAD_CHUNKS * 3;

/// The int8 head pass. See [`build_plan`].
fn build_plan8(weights: &dyn WeightSource, greedy: bool) -> Result<Plan, String> {
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // Every EMBED tensor this pass does not read, named so `finish` accepts
    // the plan. The int8 chunks are the only tensors read.
    for index in 0..TENSORS_WITH_HEAD8 {
        if !(HEAD8_BASE..HEAD8_BASE + HEAD8_CHUNK_TENSORS).contains(&index) {
            b.host_tensor(index, &dims_of(index));
        }
    }
    let x = b.input(Shape::new(D_MODEL, 1, 1));
    let mut outs = Vec::with_capacity(HEAD_CHUNKS);
    let mut next = HEAD8_BASE;
    for _ in 0..HEAD_CHUNKS {
        let at = next;
        next += 3;
        outs.push(point8(b, at, x, CLASSES_PER_CHUNK));
    }
    if next != HEAD8_BASE + HEAD8_CHUNK_TENSORS {
        return Err(format!("the int8 head claims {} tensors, not {HEAD8_CHUNK_TENSORS}", next - HEAD8_BASE));
    }
    if greedy {
        finish_greedy(b, &mut outs);
    }
    builder.finish(&outs)
}

/// Append the on-device concat + argmax to `outs`. Shared by the int8,
/// fp16 and standalone passes (the tail is identical; only the chunk base
/// differs).
fn finish_greedy(b: &mut Builder, outs: &mut Vec<Id>) {
    // Concatenate the 16 splits on-device (channel Concat lowers to
    // copies within the arena — no extra allocation) and reduce to one
    // greedy id. The split outputs stay too, so the Kotlin sampling path
    // keeps working from the same recording.
    let row = b.concat(outs);
    let id = b.argmax(row);
    outs.push(id);
}

/// The fp16 head pass. See [`build_plan`].
fn build_plan_fp16(weights: &dyn WeightSource, greedy: bool) -> Result<Plan, String> {
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // Every EMBED tensor this pass does not read, named so `finish` accepts
    // the plan. The fp16 chunks are the only tensors read; the legacy rank-2
    // table (tensor 112) stays host-side for gather-time fallback.
    for index in 0..TENSORS_WITH_HEAD {
        if !(HEAD_BASE..HEAD_BASE + HEAD_CHUNK_TENSORS).contains(&index) {
            b.host_tensor(index, &dims_of(index));
        }
    }
    let x = b.input(Shape::new(D_MODEL, 1, 1));
    let mut outs = Vec::with_capacity(HEAD_CHUNKS);
    let mut next = HEAD_BASE;
    for _ in 0..HEAD_CHUNKS {
        let at = next;
        next += 2;
        outs.push(point(b, at, x, CLASSES_PER_CHUNK));
    }
    if next != HEAD_BASE + HEAD_CHUNK_TENSORS {
        return Err(format!("the head claims {} tensors, not {HEAD_CHUNK_TENSORS}", next - HEAD_BASE));
    }
    if greedy {
        finish_greedy(b, &mut outs);
    }
    builder.finish(&outs)
}

/// What the standalone head hands back.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HeadOut {
    /// The 16 logit splits.
    Logits,
    /// The device argmax id as `[lo, hi]`.
    Greedy,
    /// The 64 largest logits as `[value, lo, hi] x 64` (see `Builder::top_k`).
    TopK,
}

/// The standalone head pass over the head file (`graph::GEMMA4_HEAD`).
///
/// Same 16 int8 triples as `build_plan8`, but the file holds NOTHING else:
/// chunk `s` owns tensors `s * 3 .. s * 3 + 2` (no `host_tensor` companions
/// needed — `finish` sees every tensor read). `Greedy` and `TopK` reduce the
/// splits on the device, so a step reads back 2 or 192 values instead of 512 KB.
pub fn build_plan_standalone(weights: &dyn WeightSource, out: HeadOut) -> Result<Plan, String> {
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    let x = b.input(Shape::new(D_MODEL, 1, 1));
    let mut outs = Vec::with_capacity(HEAD_CHUNKS);
    for s in 0..HEAD_CHUNKS {
        outs.push(point8(b, s * 3, x, CLASSES_PER_CHUNK));
    }
    match out {
        HeadOut::Logits => builder.finish(&outs),
        HeadOut::Greedy => {
            let row = b.concat(&outs);
            let id = b.argmax(row);
            builder.finish(&[id])
        }
        HeadOut::TopK => {
            let row = b.concat(&outs);
            let top = b.top_k(row);
            builder.finish(&[top])
        }
    }
}

/// The shape of EMBED tensor `index`, derived from the layout constants.
///
/// `host_tensor` checks it against the file, so this is a second statement
/// of the table `s10_to_maml.collect_embed` writes — a converter and a
/// runtime that disagree about a shape fail here rather than on the device.
pub fn dims_of(index: usize) -> Vec<u32> {
    if index < embed::TABLES {
        // Working triple (0-2, int4 kernel + per-block scale + bias),
        // shared projection triple (3-5, int8 kernel + scale + bias),
        // shared norm (6). File kernels are rank-2 [rows, taps] (host
        // readers take rank-2 dims); only Builder::conv demands rank-4.
        if index < 3 {
            return match index {
                0 => vec![VOCAB, D_MODEL],
                1 => vec![VOCAB, D_MODEL.div_ceil(crate::weights::I4_BLOCK)],
                _ => vec![VOCAB],
            };
        }
        if index < 6 {
            return match index {
                3 => vec![
                    LAYERS as u32 * PER_LAYER,
                    D_MODEL,
                    1,
                    1,
                ],
                4 => vec![LAYERS as u32 * PER_LAYER],
                _ => vec![LAYERS as u32 * PER_LAYER],
            };
        }
        return vec![PER_LAYER];
    }
    if index < embed::HEAD_TABLE {
        // Per-layer mmap tables: int4 triple each (kernel rank-2
        // [VOCAB, 256], per-block scale, bias).
        return match (index - embed::TABLES) % 3 {
            0 => vec![VOCAB, PER_LAYER],
            1 => vec![VOCAB, PER_LAYER.div_ceil(crate::weights::I4_BLOCK)],
            _ => vec![VOCAB],
        };
    }
    if index == embed::HEAD_TABLE {
        // Legacy rank-2 fp16 head table (host path).
        return vec![VOCAB, D_MODEL];
    }
    if index < HEAD8_BASE {
        // fp16 chunked head: kernel, bias alternating.
        return if (index - HEAD_BASE) % 2 == 0 {
            vec![CLASSES_PER_CHUNK, D_MODEL, 1, 1]
        } else {
            vec![CLASSES_PER_CHUNK]
        };
    }
    // int8 chunked head: kernel, per-channel scale, bias.
    match (index - HEAD8_BASE) % 3 {
        0 => vec![CLASSES_PER_CHUNK, D_MODEL, 1, 1],
        1 => vec![CLASSES_PER_CHUNK],
        _ => vec![CLASSES_PER_CHUNK],
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::nets::tests::Shapes;

    #[test]
    fn the_head_layout_matches_the_converter() {
        // `s10_to_maml.collect_embed` head section: legacy rank-2 fp16 table
        // at 112, then 16 rank-4 fp16 kernels + zero biases, then 16 int8
        // triples (kernel, scale, bias). Indices 0..111 unchanged so old
        // files still parse for gather.
        assert_eq!(embed::HEAD_TABLE, 112);
        assert_eq!(HEAD_BASE, 113);
        assert_eq!(HEAD_CHUNKS, 16);
        assert_eq!(CLASSES_PER_CHUNK, 16384);
        assert_eq!(CLASSES_PER_CHUNK as usize * HEAD_CHUNKS, VOCAB as usize);
        assert_eq!(HEAD_CHUNK_TENSORS, 32);
        assert_eq!(TENSORS_WITH_HEAD, 145);
        assert_eq!(embed::TENSORS_WITH_HEAD, 145);
        assert_eq!(HEAD8_BASE, 145);
        assert_eq!(HEAD8_CHUNK_TENSORS, 48);
        assert_eq!(TENSORS_WITH_HEAD8, 193);
        assert_eq!(embed::TENSORS_WITH_HEAD8, 193);
    }

    #[test]
    fn the_head_pass_builds_and_reads_every_chunk() {
        // One hidden input in, 16 vocab splits out, against the stub source
        // so `Builder::finish`'s unread-tensor invariant does the work: every
        // EMBED tensor is either a chunk this pass reads or named host-side.
        // The int8 pass is the production one (fp16 covered below).
        let source = Shapes::new(TENSORS_WITH_HEAD8);
        let plan = build_plan(&source).expect("the int8 head pass builds");
        assert_eq!(plan.inputs.len(), 1);
        assert_eq!(plan.inputs[0].shape, Shape::new(D_MODEL, 1, 1));
        assert_eq!(plan.outputs.len(), HEAD_CHUNKS);
        for output in &plan.outputs {
            assert_eq!(output.shape, Shape::new(CLASSES_PER_CHUNK, 1, 1));
        }
        // 16 int8 1x1 convolutions, one per chunk (vector-routed at 1 pos).
        let mut convs = 0;
        for op in &plan.ops {
            if let crate::nets::Op::Dispatch { kind, .. } = op {
                if matches!(
                    kind,
                    crate::nets::Kind::ConvInt8
                        | crate::nets::Kind::ConvPointInt8
                        | crate::nets::Kind::ConvVecInt8
                ) {
                    convs += 1;
                }
            }
        }
        assert_eq!(convs, HEAD_CHUNKS, "one int8 conv per chunk");
        crate::nets::tests::assert_no_aliasing(&plan);
        let sched = crate::nets::schedule::schedule(&plan);
        crate::nets::schedule::is_sound(&plan, &sched).expect("the head schedule is sound");
    }

    #[test]
    fn the_fp16_head_pass_builds_and_reads_every_chunk() {
        // Same coverage for the fp16 fallback path.
        let source = Shapes::new(TENSORS_WITH_HEAD);
        let plan = build_plan(&source).expect("the fp16 head pass builds");
        assert_eq!(plan.inputs.len(), 1);
        assert_eq!(plan.inputs[0].shape, Shape::new(D_MODEL, 1, 1));
        assert_eq!(plan.outputs.len(), HEAD_CHUNKS);
        for output in &plan.outputs {
            assert_eq!(output.shape, Shape::new(CLASSES_PER_CHUNK, 1, 1));
        }
        let mut convs = 0;
        for op in &plan.ops {
            if let crate::nets::Op::Dispatch { kind, .. } = op {
                if matches!(kind, crate::nets::Kind::Conv | crate::nets::Kind::ConvPoint) {
                    convs += 1;
                }
            }
        }
        assert_eq!(convs, HEAD_CHUNKS, "one fp16 conv per chunk");
        crate::nets::tests::assert_no_aliasing(&plan);
        let sched = crate::nets::schedule::schedule(&plan);
        crate::nets::schedule::is_sound(&plan, &sched).expect("the fp16 head schedule is sound");
    }

    #[test]
    fn the_standalone_greedy_head_returns_only_the_id() {
        let source = Shapes::new(HEAD_FILE_TENSORS);
        let plan = build_plan_standalone(&source, HeadOut::Greedy).expect("the greedy head builds");
        assert_eq!(plan.outputs.len(), 1);
        assert_eq!(plan.outputs[0].shape, Shape::new(2, 1, 1));
        let plan = build_plan_standalone(&source, HeadOut::Logits).expect("the logits head builds");
        assert_eq!(plan.outputs.len(), HEAD_CHUNKS);
        let plan = build_plan_standalone(&source, HeadOut::TopK).expect("the top-k head builds");
        assert_eq!(plan.outputs[0].shape, Shape::new(192, 1, 1));
    }

    #[test]
    fn the_chunk_shapes_cover_the_vocabulary() {
        // Chunk s owns classes [s * 16384, (s + 1) * 16384): contiguous and
        // gap-free, so concatenating the 16 outputs is the full logits row.
        let mut covered = 0u32;
        for s in 0..HEAD_CHUNKS {
            let _ = s;
            covered += CLASSES_PER_CHUNK;
        }
        assert_eq!(covered, VOCAB);
    }
}
