#!/usr/bin/env python3
"""ONNX to `.maml`, the weights container `:library:ml` reads.

`:library:ml` is not a graph interpreter: each network's forward pass is hardcoded
Rust in `library/ml/src/main/rust/src/nets/`. So a `.maml` file carries **only
ordered tensors** — no operators, no topology, no names. The Rust net module
indexes the tensor table positionally.

That makes tensor *order* the entire contract between this script and the Rust,
and a reordering would be silently wrong rather than a load failure. So the order
is defined mechanically and pinned:

* Order is the weighted nodes in ONNX **topological order** — `Conv`/`ConvTranspose`
  weight then bias, `PRelu` slope, `Gemm` weight then bias. Nothing else is emitted:
  every other initializer in these graphs is shape-computation scaffolding that folds
  away at a fixed input size.

  Topological rather than initializer order matters for the nets with `PRelu`: the Rust
  reads a layer as `weight = i, bias = i + 1, slope = i + 2`, which only lines up if a
  slope is emitted immediately after the convolution it follows.
* `--check` hashes the whole ordered layer table (shapes *and* the attributes the
  Rust hardcodes: dilation, pads, strides, group) into one SHA-256 pinned below.
  Any upstream re-export that renames, reorders, retunes or re-pads a single layer
  changes that hash and fails, instead of producing a file that loads and infers
  nonsense.

Two nodes are rewritten rather than emitted verbatim, because doing it here means the
runtime needs no shader for either:

* A **`Gemm`** over a flattened NCHW map is a convolution whose kernel covers the whole
  spatial extent. MobileFaceNet's `[512, 3136]` becomes `[512, 64, 7, 7]` and the
  existing `conv.comp` computes it, so there is no `InnerProduct` pipeline.
* A trailing **`BatchNormalization`** is folded into the `Gemm` before it. At inference a
  batch norm is a per-channel affine, so `W' = W * gamma / sqrt(var + eps)` and
  `b' = (b - mean) * gamma / sqrt(var + eps) + beta` is exact, not an approximation.

Weights are stored fp16 with fp32 accumulation in-shader (see `SPEC` below for the
byte layout). ONNX tensor layout is preserved verbatim — `[M, C/group, kH, kW]` for
`Conv`, `[C, M/group, kH, kW]` for `ConvTranspose` — so nothing here has to agree
with the shaders about a transpose.

One graph does not come from an ONNX export at all. SMaLL-100 is read from its PyTorch
`.safetensors` and quantised to int8 here, because the only ONNX exports that exist for it are
already int8 and quantised per *tensor*; see [`CHECKPOINTS`] for why that is not usable.

One graph is an ONNX export whose tensor table is nevertheless **derived from architecture
constants and read by name**, rather than walked topologically. TinyCLIP is two towers in one
graph, interleaved, and its projections are anonymous `onnx::MatMul_NNNN` initializers, so a
topological walk would emit an order no one could read. See [`ARCHITECTURES`].

    ./scripts/ml/maml_convert.py --check model.onnx --graph u2netp
    ./scripts/ml/maml_convert.py model.onnx --graph u2netp -o out.maml
    ./scripts/ml/maml_convert.py model.onnx --graph u2netp --print-layers
    ./scripts/ml/maml_convert.py model.safetensors --graph small100 -o out.maml
    ./scripts/ml/maml_convert.py music_detector.sound_model_2 --graph nnfp -o nnfp.maml
"""

import argparse
import hashlib
import math
import os
import struct
import sys

import numpy as np
import onnx
from onnx import numpy_helper, shape_inference

# --- .maml ---------------------------------------------------------------------
#
# One contiguous blob, so the whole file becomes one VkBuffer and one staging
# upload. Little-endian throughout; every device we target is.
#
#   header, 64 bytes
#     0   4   magic b"MAML"
#     4   4   u32 format version
#     8   4   u32 graph id — the runtime rejects a file built for another net
#    12   4   u32 tensor count
#    16  32   source SHA-256 of the ONNX or checkpoint, so a shipped asset traces upstream
#    48   4   u32 offset of the data section
#    52   4   u32 length of the data section
#    56   8   reserved, zero
#
#   tensor table, 32 bytes per entry, immediately after the header
#     0   4   u32 rank, 1..4
#     4  16   u32[4] dims, unused trailing entries zero
#    20   4   u32 dtype, 0 = fp16
#    24   4   u32 byte offset within the data section
#    28   4   u32 element count
#
#   data, fp16, each tensor padded to ALIGNMENT
MAGIC = b"MAML"
FORMAT_VERSION = 1
HEADER_BYTES = 64
TENSOR_ENTRY_BYTES = 32
# Tensor payload types. The container carries the type per tensor, so fp16 and int8 can sit in
# one file and a reader knows the stride without being told.
DTYPE_F16 = 0

# Signed 8-bit, symmetric, with a per-output-channel scale in the fp16 tensor that follows it.
#
# Only for a network where the weights dominate the download: SMaLL-100 is 330 million
# parameters, 660 MB at fp16 against 330 MB here, and the ncnn build of the same model shipped
# 1.14 GB. Everything else stays fp16, where the saving would not pay for the loss.
DTYPE_I8 = 1
# Signed 4-bit, two per byte along the contraction axis, with a **rank-2** per-block scale in the
# fp16 tensor that follows it: `(out_channels, ceil(taps / I4_BLOCK))`.
#
# Where int8 halves a download, this quarters it, and it is the only way a model whose weights
# dominate at several gigabytes becomes shippable at all. It costs a scale table 32 times larger
# than int8's, which at these widths is well under a percent of the tensor it scales.
#
# A tensor's byte length is `ceil(len / 2)`, not `len * stride` - the one dtype whose stride is
# fractional. Reader and writer must agree on that rounding or an odd-length tensor's last
# element reads as whatever follows it.
DTYPE_I4 = 2
# GGUF Q2_K superblocks, carried verbatim: 256 taps per 84-byte block (16 scale bytes +
# 64 quant bytes + d + dmin as fp16). The per-superblock `(d, dmin)` pair lives in the
# fp16 tensor that follows it, one row per block, in the slot a per-channel scale would
# occupy — so a Q2_K layer is the same kernel + scale + bias triple as int8, with a
# rank-2 scale. A tensor's byte length is `ceil(len / 256) * 84`.
DTYPE_Q2K = 3
# Taps one Q2_K superblock covers. Fixed by the GGUF format, not a tuning choice.
Q2K_BLOCK = 256
# Bytes one Q2_K superblock occupies: 16 scales + 64 quants + d + dmin.
Q2K_BYTES = 84
# Taps one Q2_K lane covers: a superblock is 16 lanes of 16 taps sharing one `(d, dmin)`.
Q2K_LANE = 16
# 16 bytes is the largest scalar/vector alignment the shaders index with, and a
# multiple of 2, so an aligned tensor offset is also an aligned fp16 index.
ALIGNMENT = 16

SPEC = __doc__

# The floor for `cosine(fp32 weight, int8 * scale)` per tensor. Supertonic's text encoder was
# reverted to fp16 at 0.99212 while its sampler shipped at 0.99997, so this is set where a layer
# that quantises this badly is worth looking at rather than shipping.
MIN_INT8_COSINE = 0.999

# The same floor for int4, which cannot meet the int8 one and should not be asked to.
#
# Four bits give seven positive codes against 127, so the quantisation error is roughly eighteen
# times larger even with a scale per block. Measured on random gaussian weights at
# `I4_BLOCK` 32, `quantise_per_block` reconstructs at about 0.9956 - comfortably below
# `MIN_INT8_COSINE` and not a defect. Holding int4 to the int8 gate would either reject every
# tensor or, worse, invite someone to lower the int8 gate to match.
#
# Set where a tensor that quantises *unusually* badly for four bits stands out: real weights are
# not gaussian and the projections this is meant for measure better than the synthetic case.
MIN_INT4_COSINE = 0.99

# Graphs the generic ONNX collector reads as int8. See [`int8_eligible`] for what that
# excludes within a graph, and `scripts/ml/maml_survey.py` for how the list was chosen.
#
# Roughly halves each of these: 6.5 -> 3.3 MiB for mobilefacenet, 3.8 -> 1.9 for ppocr_rec,
# 2.2 -> 1.2 for ppocr_det and u2netp, 1.2 -> 0.7 for scrfd.
#
# `selfie` is deliberately absent. It is a 211 KB depthwise net: a `[C, 1, 3, 3]` kernel saves
# nine bytes per channel and spends two on the scale, so there is nothing to win and it would
# be one more graph to re-verify.
INT8_GRAPHS = set()

# Graph ids. Shared with `library/ml/src/main/rust/src/weights.rs`; changing one
# without the other is what the id exists to catch.
GRAPHS = {
    "selfie": 1,
    "u2netp": 2,
    "scrfd": 3,
    "mobilefacenet": 4,
    # Folded ahead of time by `scripts/ml/ppocr_fold.py`, not by this script.
    "ppocr_det": 5,
    "ppocr_rec": 6,
    # 7..10 were Piper's four graphs — vocoder, encoder, flow and duration predictor —
    # deleted when Supertonic replaced them. The numbers are **not** reused: an id identifies
    # a forward pass, and a `.maml` built for the old vocoder must be rejected rather than
    # loaded as whatever took its slot.
    "supertonic_voc": 11,
    "supertonic_dp": 12,
    "supertonic_ttl": 13,
    "supertonic_ve": 14,
    # Read from a PyTorch checkpoint rather than an ONNX graph; see [`CHECKPOINTS`].
    "small100": 15,
    # An ONNX graph read by name rather than walked; see [`ARCHITECTURES`].
    "tinyclip": 16,
    # Read from a PyTorch checkpoint rather than an ONNX graph; see [`CHECKPOINTS`].
    "whisper": 17,
    # NLLB-200-distilled-600M, replacing small100; see [`CHECKPOINTS`].
    # Agreed with rust-eng/app-eng in team chat (nllb-translate): next free id,
    # 7..10 stay retired.
    "nllb600": 18,
    # Maia3-5M human-move prediction, replacing Stockfish in `:games:chess`; see
    # [`CHECKPOINTS`]. Next free id after NLLB's 18, with 7..10 and 15 staying retired.
    "maia": 19,
    # Gemma 4 E2B instruction-tuned, the text decoder, from `onnx-community/gemma-4-E2B-it-ONNX`.
    # Next free id after Maia's 19. **Not 19**: the port plan originally claimed that number,
    # which Maia already holds, and a collision would make a file parse as the wrong net.
    "gemma4_text": 20,
    # Gemma 4's two embedding tables, read on the host rather than by any shader. Its own file
    # because a decode step needs one row of each and nothing binds them.
    "gemma4_embed": 21,
    # The vision tower, which restored image input after litertlm was removed.
    "gemma4_vision": 22,
    # The Now Playing audio fingerprinter, 415 ms of log-mel in and a 64-d embedding out.
    # Next free id after Gemma 4's vision tower at 22, with 7..10 and 15 staying retired.
    # `weights.rs` has `graph::NNFP` at the same number.
    #
    # The first graph read from a **TFLite** flatbuffer rather than ONNX or a checkpoint;
    # see [`TFLITE`] for why not via ONNX.
    "nnfp": 23,
    # Gemma 4's audio tower, 12 conformer layers over log-mel frames. Next free id after the
    # fingerprinter at 23, with 7..10 and 15 staying retired. `weights.rs` has
    # `graph::GEMMA4_AUDIO` at the same number. Its own file at ~165 MB: bigger than the vision
    # tower, and a device that never sends audio should not download it.
    "gemma4_audio": 24,
    # MADLAD400-3b-mt translation, encoder and decoder in one file. See
    # [`CHECKPOINTS`].
    #
    # Two graphs rather than one file would upload the untied head twice over: the
    # input table and the logits kernel are separate 256,000-row tables. Next free
    # id after Gemma 4's audio tower at 24, with 7..10 and 15 staying retired.
    # `weights.rs` has `graph::MADLAD` at the same number.
    "madlad400": 25,
    # Gemma 4's GPU logits head: the 16 int8 head chunks (kernel, scale, bias
    # each) ALONE in their own file. Next free id after MADLAD's 25, with 7..10
    # and 15 staying retired. `weights.rs` has `graph::GEMMA4_HEAD` at the same
    # number.
    #
    # Why a third file: the head `Net` uploads its whole `Blob` verbatim, and
    # the EMBED file grew to 3.6 GB with the chunks inside (TEXT 1.1 GB + EMBED
    # 3.6 GB = 4.7 GB uploaded vs ~2.6 GB free on a Pixel 8 = OOM-reboot,
    # twice, 2026-09-26). The head file is ~402 MB: TEXT + HEAD = ~1.5 GB
    # uploaded, back under the 3.5 GB the old harness proved safe. The EMBED
    # file itself is never uploaded — host gathers only, as before.
    "gemma4_head": 26,
    # The Q2_K port of the same net, from `model-q2k.gguf`. SAME graph id: the tensor
    # order is identical (684 layers, 1724 tensors, same names) and only the payloads
    # differ (Q2_K verbatim vs int8 requantised), so the Rust forward pass reads either
    # file. A separate id would fork the runtime for no reason; the digest tells them apart.
    "madlad400-q2k": 25,
    # MODNet photographic-portrait matting (MobileNetV2 backbone), for `:camera`'s
    # still-capture portrait matte. Converted via `modnet_fold.py`, not this script
    # directly: the export's Slice-fed batch norms and SE MatMuls are folded first.
    # Next free id after MADLAD's 25, with 7..10 and 15 staying retired.
    "modnet": 27,
}

# Graphs read from a GGUF file rather than safetensors/ONNX/TFLite. The Q2_K port reads
# the quantised export directly (see `open_gguf`); the safetensors path stays for the
# int8 fallback.
GGUF_GRAPHS = {
    "madlad400-q2k",
}

# SHA-256 over the ordered layer table (see `layer_table_digest`). Regenerate with
# --print-digest after deliberately re-pointing at a new upstream export.
EXPECTED_DIGEST = {
    "selfie": "6db422ecfe0af8e37591eb14c195166b2c416649bf9063100bd5bb14eafaad3a",
    "u2netp": "f5199e05e4d4e93a6af601d04e3e0516bb68b9f292bb81d474704ea0b4e591d0",
    "scrfd": "d2ce2880a432af03b0ef594029d3ab03d86de54bd0a9f10ea2c8e412309b0f46",
    "mobilefacenet": "40987839aec03378958581deba29ce9fd9dd1ae9aa88d0c7ad04a885a2e2122c",
    "ppocr_det": "51b0c9ed871526fa270b4bfa53b393c056db21c58193849fb83d44768f69ef9b",
    "ppocr_rec": "8c16a8702aa7a1e7b4d94787d089616ca4bc3d8aaeebb0b0159eabc56af3ba08",
    "supertonic_voc": "5282083898554b1fae28de946bfc1a82a11e2acddf3911877f27b1b115c583aa",
    "supertonic_dp": "d9a548e1f1ed908a3b0ad27ab543dd7521858f9110b426edb1ed64ba1f1a13e4",
    "supertonic_ttl": "92d0fa8ae73d4a8a223c77fb4230e390df580744014a3fcf3211ab72a988c020",
    "supertonic_ve": "48ab5789e8839507ff93e099dc0f61940f25da9ba6201651784f6e68598199e2",
    "small100": "c9f05f2fb686aa53137ca06b521baf6d7a00f32cfb4ea42bd14a5c3e7cf6c086",
    "tinyclip": "7557b2725b52ea04bd18b12e089320b9dd419cd3f7344c0e3d32888d61c24fa7",
    "whisper": "9e2830c5ea5d443fc31eb5c4eb80fa9a3b9bbcccd3c07963256ade50963c72a2",
    # Filled in by running --print-digest after the first successful conversion;
    # see fetch_nllb600.py. Placeholder until then (check fails loudly, not silently).
    "nllb600": "8b5dd248896b11bf1bb0feae6818d4b00b8ad176549df48bdc07f7b970d3480a",
    # Pinned with --print-digest after the first successful conversion; see fetch_maia.py.
    "maia": "50da3ca5ebb9a1b54338c43b2a4b798df25279ac6f74fa19b64146b3964b61aa",
    # Gemma 4's vision tower. Mixed precision: int4 through the sixteen layers, fp16 for the
    # patch and output projections at the ends. See `collect_gemma4_vision.dense`.
    "gemma4_vision": "a942080007f7ca9d8fa38a0812a4f858ff1c31afb7c4e94d105428da5fa56f19",
    # Gemma 4 E2B text decoder converted DIRECTLY from the litertlm GPU bundle
    # (scripts/ml/litertlm_to_maml.py; the ONNX export is a different model).
    # 540 layers, 1090 tensors (rotary thetas read off the base bundle's own
    # Section 10 `maybe_rope` constants: 1e4 sliding / 1e6 full;
    # --rope-theta overrides both; per-layer `skip` whole-residual scalars;
    # plus the two shared ones-vectors), fidelity worst 0.9988.
    # Pinned 2026-09-25 (full-scale O/PL; the 2026-09-17 pin was 538/1088,
    # pre-ones-vectors and pre-fullfix — see
    # build/gemma4-s10-fullfix/BLESSED.txt).
    "gemma4_text": "20262c74605b0c121220cdb25f4c92d8f26607b4d548781035dcdc91209a2c26",
    # Gemma 4 E2B embedder from the same bundle: 2-bit working table + shared
    # [8960,1536] INT8 + 35xINT4 mmap tables. 38 layers, 112 tensors,
    # fidelity worst 0.9985. Pinned 2026-09-17.
    "gemma4_embed": "a1c7d7f31c2041e2b1faedb3d2792a0c7048f6f59a8cc3657bfa95c79482dff2",
    # `music_detector.sound_model_2`, pinned with --print-digest against the file recovered
    # from the APK (SHA-256 7A86281A6DEED410..., 216,320 bytes). The key records the strides,
    # padding and depth multiplier the export stores, so a re-export that resolved the
    # VALID-versus-SAME contradiction `nets::nnfp` works around would change this and be
    # looked at rather than silently disagreeing with the hardcoded pass.
    "nnfp": "c374708b973b63dad8fd37e4a25ddf584217752a747655e4fbd87899823b3f1c",
    # Gemma 4's audio tower, pinned with --print-digest after the .maml was checked against
    # `nets::gemma4_audio`'s `declare_shared` and `declare_layer` shape for shape over all 747
    # tensors. Mixed precision like the vision tower: int4 through the twelve layers, fp16 for
    # the two subsampling convolutions, the relative tables, the norms, the clips and the three
    # end projections. See `collect_gemma4_audio.dense`.
    "gemma4_audio": "f3c0be0ca09c5ed6882766eabaa6ebb147dc09cd45ab6d881dec12da41b9783d",
    # MADLAD400-3B-MT, pinned with --print-digest after the .maml was checked against
    # `nets::madlad`'s layout constants over all 1724 tensors (684 layers). int8
    # linears throughout (488, all with synthesised zero bias — T5 is bias-free),
    # fp16 RMSNorm gains, fp16 host-read relative tables. See `collect_madlad`.
    "madlad400": "36c2c8845d36b33a37dc12ad8255e38deb7664b72aa1e2a2f7b2467a9f05ea2a",
    # The Q2_K port of the same net, from `model-q2k.gguf`. Same order (684 layers,
    # 1724 tensors, same names — the Rust walks positionally, so both collectors must
    # agree); only the payloads differ (Q2_K verbatim vs int8 requantised), hence a
    # separate digest on the same graph id. See `collect_madlad_q2k`.
    "madlad400-q2k": "f17cbb4fb9f44eb8a4d94d93f5a00e7ec868d11628a12ea1b2078fbc700124b7",
    # MODNet photographic portrait matting, folded by `modnet_fold.py`. Pinned
    # after `nets::modnet` was checked against this table layer for layer.
    "modnet": "f6ad3e1cdcf38679f89ed15891576a46a2b8c9d3cc110fbd26b746df4194da2a",
}

# Graphs that are one **module** of a larger export, keyed by the node-name prefix that
# selects it.
#
# Empty at present: every graph here is exported as its own file. The mechanism stays because
# an export that packs several forward passes into one graph is not convertible as one plan,
# and a Torch export keeps its module paths - so it partitions along the lines the model was
# written in, and each static module converts independently.
#
# A module-scoped graph has no GEOMETRY entry: its input is another module's output rather
# than a graph input, so there is nothing at the boundary to check a shape against. What is
# checked instead is the op inventory within the module, which is the same guard by another
# route.
MODULES = {}

# `outputs` are the graph outputs the Rust forward pass corresponds to; the export may
# declare more (U^2-Netp declares all seven of its side outputs and we read one).
GEOMETRY = {
    "selfie": {
        "input": "pixel_values",
        "outputs": ["alphas"],
        "shape": [3, 256, 256],
    },
    "u2netp": {
        "input": "input.1",
        "outputs": ["1959"],
        "shape": [3, 320, 320],
    },
    # Nine: score, box and keypoint maps at strides 8, 16 and 32. The Rust reads the
    # convolution outputs feeding these rather than the reshaped tensors themselves,
    # because a Transpose/Reshape is a relabelling that NCHW indexing does for free.
    "scrfd": {
        "input": "input.1",
        "outputs": [
            "443", "468", "493",
            "446", "471", "496",
            "449", "474", "499",
        ],
        "shape": [3, None, None],
    },
    "mobilefacenet": {
        "input": "input.1",
        "outputs": ["516"],
        "shape": [3, 112, 112],
    },
    # MODNet photographic portrait matting. Dynamic HW like scrfd: the export is
    # dynamic-batch/dynamic-shape and the branch Resizes are scale-factor based,
    # so the fixed 512x512 run resolves structurally. Transcribed at SIZE=512.
    "modnet": {
        "input": "input",
        "outputs": ["output"],
        "shape": [3, None, None],
    },
}

# Op-type counts, as a coarse first check that the graph is the one we lowered by
# hand. Ops absent from the list must be absent from the graph.
EXPECTED_OPS = {
    "selfie": {
        "Conv": 54, "ConvTranspose": 1, "Add": 14, "Mul": 10, "Concat": 3,
        "ReduceMean": 10, "Relu": 22, "HardSwish": 11, "Sigmoid": 11,
        "Resize": 3, "Shape": 3, "Slice": 3,
    },
    "u2netp": {
        "Conv": 119, "MaxPool": 33, "Resize": 38, "Concat": 127, "Add": 11,
        "Relu": 112, "Sigmoid": 7, "Shape": 114, "Gather": 76, "Unsqueeze": 76,
        "Slice": 38, "Cast": 38, "Constant": 266,
    },
    "scrfd": {
        "Conv": 60, "Relu": 41, "Sigmoid": 3, "Add": 4, "Resize": 2, "Concat": 2,
        "Reshape": 9, "Transpose": 9, "Shape": 6, "Gather": 4, "Unsqueeze": 4,
        "Slice": 2,
    },
    "mobilefacenet": {
        "Conv": 49, "PRelu": 34, "Add": 12, "BatchNormalization": 1, "Flatten": 1,
        "Gemm": 1,
    },
    # MODNet photographic portrait matting, AFTER `modnet_fold.py`: the 16
    # IBNorm batch norms are folded into their Convs and the 2 SE MatMuls are
    # rewritten as 1x1 Convs, so this counts the folded graph (Conv 69+2, no
    # BatchNormalization, no MatMul). The 16 InstanceNormalizations and 35
    # Clip(0,6)/17 Relu/2 Sigmoid are structural, transcribed in
    # `nets::modnet` rather than emitted.
    "modnet": {
        "Conv": 71, "Add": 10, "Mul": 1, "Concat": 24,
        "InstanceNormalization": 16, "GlobalAveragePool": 1,
        "Relu": 17, "Clip": 35, "Sigmoid": 2,
        "Resize": 9, "Slice": 32, "Shape": 3, "Gather": 2, "Unsqueeze": 4,
        "Reshape": 2, "Expand": 1, "Constant": 241,
    },
    # Two towers in one graph. `MatMul` 107 is the 80 weighted projections plus the 26 attention
    # products and the one `logit_scale` product; `Erf` 13 is the 13 layers' GELU; `Softmax` 13 is
    # their attention, and the causal mask the text tower's three need is a `-3.4e38` constant
    # added to the score map, not an op. `ArgMax` 1 is the end-of-text search this runtime does on
    # the host. Counted rather than transcribed: `python -c` over the graph produced these.
    "tinyclip": {
        "Add": 186, "ArgMax": 1, "Cast": 5, "Concat": 27, "ConstantOfShape": 1, "Conv": 1,
        "Div": 44, "Equal": 4, "Erf": 13, "Expand": 3, "Flatten": 1, "Gather": 20, "Less": 1,
        "MatMul": 107, "Mul": 73, "Pow": 33, "Range": 2, "ReduceMean": 58, "ReduceSum": 2,
        "Reshape": 113, "Shape": 12, "Slice": 3, "Softmax": 13, "Sqrt": 29, "Squeeze": 1,
        "Sub": 31, "Transpose": 68, "Unsqueeze": 20, "Where": 5,
    },
}

# ONNX graphs whose tensor table is **derived from these constants and read by name**, rather than
# produced by the topological walk in [`collect_layers`].
#
# One entry, TinyCLIP. Three things make the walk unusable for it:
#
# * It is **two towers in one graph**, and the export interleaves them - text layer 0, vision layer
#   0, text layer 1, and so on. A topological order is therefore an order no reader could check
#   against `nets::tinyclip`, and `Mode::Image` and `Mode::Text` would each read a stride through
#   the table.
# * Every projection is a bare `MatMul` against an **anonymous** `onnx::MatMul_NNNN` initializer.
#   There is nothing in the initializer's name to say which layer or which projection it is. The
#   *node* names are fully structured (`/vision_model/encoder/layers.3/mlp/fc1/MatMul`), so this
#   reads the weight through the node, which is also what makes the inventory check below possible.
# * `MatMul` is `x @ W` with a **`[in, out]`** weight, and this runtime indexes a kernel as
#   `[out, in, kh, kw]`. Every attention projection here is square 256 x 256, so reading one
#   convention as the other yields a tensor of exactly the right shape holding exactly the wrong
#   numbers. `fc1` is `[256, 1024]` and `fc2` is `[1024, 256]`, so the shape assertions in
#   [`check_architecture`] are what make that loud.
#
# The `Conv` is the one exception and needs **no** transpose: ONNX `Conv` is already
# `[M, C, kH, kW]`, which is this runtime's own layout.
#
# What is checked is the **exact named-parameter inventory** derived from these constants, as
# [`CHECKPOINTS`] gets, *plus* the op counts in [`EXPECTED_OPS`] that only an ONNX graph can give.
# There is no [`GEOMETRY`] entry: this export has three inputs and four outputs and is dynamically
# shaped throughout, so there is no single boundary shape to compare against.
ARCHITECTURES = {
    "tinyclip": {
        # `hidden_size`, shared by both towers, and `num_attention_heads` 4, so `head_dim` is 64
        # and `Builder::attn_scores`'s own `1 / sqrt(64)` is already the model's 0.125 scale.
        "width": 256,
        "heads": 4,
        "ffn": 1024,
        "vision_layers": 10,
        "text_layers": 3,
        # 16 x 16 patches over 224 x 224 is a 14 x 14 grid, so 196 patches plus the class token.
        "patch": 16,
        "grid": 14,
        "vocab": 49_408,
        "context": 77,
        "projection": 512,
    },
    "gemma4_text": {
        # Read off `config.json`'s `text_config`, then checked against the export's own
        # initializer shapes by `check_architecture`. Transcribed rather than derived: a
        # constant that disagrees with the file must fail loudly, not be quietly adopted.
        "d_model": 1536,
        "heads": 8,
        "kv_heads": 1,
        "head_dim": 256,
        "global_head_dim": 512,
        "layers": 35,
        # Layers 0..15 own a KV cache; 15..35 have no k/v projections and a double-wide MLP.
        "owns_cache_layers": 15,
        "ffn": 6144,
        "ffn_wide": 12288,
        "per_layer": 256,
        "vocab": 262_144,
        "head_splits": 4,
        # Positions the runtime offers, and so the rows the rotary tables are cut to.
        # `max_position_embeddings` is 131072, which no arena could hold. Mirrors
        # `nets::gemma4::MAX_CONTEXT`.
        "max_context": 16384,
    },
    "gemma4_embed": {
        "d_model": 1536,
        "per_layer": 256,
        "layers": 35,
        "vocab": 262_144,
        # The two constants `embed_tokens.onnx` multiplies its gathers by, folded into the
        # weights here. 39.25 is `sqrt(1536)` as fp16 rounds it; 16.0 is `sqrt(256)` exactly.
        "embed_scale": 39.25,
        "per_layer_scale": 16.0,
    },
    "gemma4_vision": {
        # Read off config.json's vision_config, then checked against the export by
        # check_architecture. Mirrors nets::gemma4_vision's constants.
        "d_model": 768,
        "heads": 12,
        "head_dim": 64,
        "ffn": 3072,
        "layers": 16,
        "patch": 16,
        "out_dim": 1536,
        "positions": 10240,
    },
    "gemma4_audio": {
        # Read off config.json's audio_config and then measured against the export by
        # collect_gemma4_audio, which asserts every count this walk depends on. Mirrors
        # nets::gemma4_audio's constants.
        "d_model": 1024,
        "heads": 8,
        "head_dim": 128,
        "ffn": 4096,
        "layers": 12,
        "out_dim": 1536,
        # Output channels of the two subsampling convolutions, and the mel bins surviving their
        # two stride-2 halvings. `mel_subsampled * sscp_channels[1]` is d_model, which is why
        # the input projection is square - arithmetic, not identity.
        "sscp_channels": [128, 32],
        "mel_subsampled": 32,
        # Columns in a layer's relative-position table. NOT the attended span, which is 12: the
        # thirteenth column exists because the export's _rel_shift skew consumes one, and the
        # mask's strict `dist < 12` means it is computed and thrown away.
        "rel_offsets": 13,
        # Constants nets::gemma4_audio hardcodes, asserted against the graph's folded scalars so
        # that a changed one fails here rather than becoming a wrong forward pass. The query
        # scale is `(128 ** -0.5) / ln 2` and the key scale `ln(1 + e) / ln 2`; the `/ ln 2` in
        # both is the part nobody would guess.
        "q_scale": 0.127517431974411,
        "k_scale": 1.8946361541748047,
        "logit_cap": 50.0,
        "residual_weight": 0.5,
        # nets::gemma4_audio::TENSORS. 15 shared plus 12 layers of 61.
        "tensors": 747,
    },
}

# Graphs read from a **PyTorch checkpoint** rather than an ONNX graph, keyed by graph id name.
#
# Every other net here converts from a frozen ONNX export, and for good reason: the graph records
# the shapes and the op inventory, so `check_graph` can refuse an export whose structure moved
# without anyone transcribing anything. A checkpoint has neither. It is used anyway for SMaLL-100,
# because the only ONNX exports that exist for it are already int8:
#
# * `casawolice/small100-onnx` quantises **per tensor**, not per output channel. Its embedding's
#   row absmax spans 0.258 to 1.740, so one scale for the whole table costs a factor of four in
#   resolution against the per-row scale `quantise_per_channel` produces, and up to 6.7x on the
#   quietest rows. That resolution cannot be recovered by requantising: it is already gone.
# * It carries the 131M-parameter embedding **three times** - `embed_tokens.weight` in the
#   encoder, `model.shared.weight` in the decoder and a separately quantised `[1024, 128112]`
#   `lm_head` - which is why that deployment set is 609 MB rather than 333 MB. In the checkpoint
#   the weight is tied and appears exactly once, so the tied head costs nothing.
# * Its two embedding copies are **uint8 with a zero point of 159**. `conv_point_int8.comp`
#   computes `scale[o] * sum(int8_w * in)` and has nowhere to put a zero point.
#
# What is checked instead is the **exact parameter inventory** - every name and every shape,
# derived from the constants below rather than listed - which is a stricter guard than the op
# counts an ONNX graph gets, because it fails on a renamed or resized parameter as well as on a
# missing one. The layer table digest still covers the order.
CHECKPOINTS = {
    "small100": {
        "d_model": 1024,
        "encoder_layers": 12,
        "decoder_layers": 3,
        "ffn": 4096,
        "vocab": 128_112,
        # The tied embedding is 125.1 MiB of int8, and `maxStorageBufferRange`'s guaranteed
        # minimum is 128 MiB. Emitting it as two tensors over disjoint class ranges keeps every
        # binding comfortably under that floor and gives `nets::small100` its two logits ops for
        # free; 128,112 is 2 x 64,056, so the halves are equal and no range is padded.
        "head_splits": 2,
    },
    # whisper-base, from `openai/whisper-base`. A checkpoint for the same reason SMaLL-100 is one:
    # the ONNX exports that exist are already int8 and quantised per **tensor**, which is the
    # coarseness that cost SMaLL-100's embedding a factor of four.
    #
    # Two things about this architecture are unlike anything else here, and both are asserted rather
    # than assumed by [`whisper_inventory`]:
    #
    # * **No `k_proj.bias`.** Only `q_proj`, `v_proj` and `out_proj` carry one, in all 18 attentions.
    #   `Builder::conv_int8` always reads a bias after the scale, so the converter synthesises a
    #   zero and records it in the digest key — a checkpoint that grows one must not be read as this
    #   table.
    # * **A convolution stem.** `conv1` is `[512, 80, 3]` stride 1 and `conv2` is `[512, 512, 3]`
    #   **stride 2**, which is what turns 3000 mel frames into 1500 encoder positions. Both are
    #   rank-3, so they are lifted to the `1 x k` rank-4 kernel this runtime indexes.
    #
    # The head is **tied** to `embed_tokens`, as SMaLL-100's is, but at 51,865 x 512 it is 26.6 MB
    # and fits one binding — so unlike SMaLL-100 it needs no class split.
    "whisper": {
        "d_model": 512,
        "encoder_layers": 6,
        "decoder_layers": 6,
        "ffn": 2048,
        "vocab": 51_865,
        "mels": 80,
        # `max_source_positions` and `max_target_positions`. The first is 3000 mel frames after
        # `conv2`'s stride 2; the second is the decoder's learned position table.
        "source_positions": 1500,
        "target_positions": 448,
        "conv_kernel": 3,
    },
    # NLLB-200-distilled-600M, from `facebook/nllb-200-distilled-600M` (gated, MIT-adjacent
    # CC-BY-NC but fetched fine unauthenticated). A checkpoint for the same reason SMaLL-100
    # is one: the repo ships only `pytorch_model.bin` (fp32) plus `sentencepiece.bpe.model`
    # and `tokenizer.json` — there is no ONNX export at all.
    #
    # The architecture is `M2M100ForConditionalGeneration` (config.json says so verbatim:
    # `model_type: m2m_100`, `activation_function: relu`, `scale_embedding: true`,
    # `d_model: 1024`, `vocab_size: 256206`). So the forward pass is SMaLL-100's with two
    # differences, both asserted by [`nllb_inventory`] and [`collect_nllb`]:
    #
    # * **12 decoder layers, not 3.** The distilled-600M keeps the full decoder.
    # * **202 language tokens, not 100**, at ids `256001..256202` with `<mask>` at 256203.
    #   Tokenizer detail only — the converter never reads them — but the vocab count pins it.
    #
    # Everything else carries over: fairseq sinusoid positions with the +2 offset (same
    # `M2M100SinusoidalPositionalEmbedding`, offset=2, verified in transformers'
    # `modeling_m2m_100.py`), pre-norm layers, ReLU FFN, `1/sqrt(64)` query scale already in
    # `attn_scores`, all four projections biased (unlike whisper's missing k bias), tied
    # embedding emitted once.
    #
    # The checkpoint holds the tied embedding **four times** (`model.shared`,
    # `model.encoder.embed_tokens`, `model.decoder.embed_tokens`, `lm_head` — all
    # byte-identical, verified exact-equal). Only `model.shared.weight` is emitted.
    #
    # The tied embedding is 262.4 MiB of int8 (256,206 x 1024), so it needs **four** class
    # splits to stay under `maxStorageBufferRange`'s 128 MiB floor: 256,206 = 2 x 128,103
    # is not divisible by 4, so the four ranges are 64,052 / 64,052 / 64,051 / 64,051
    # (first two get the extra row each). `nets::nllb600` reads four logits ops.
    "nllb600": {
        "d_model": 1024,
        "encoder_layers": 12,
        "decoder_layers": 12,
        "ffn": 4096,
        "vocab": 256_206,
        # Four head splits, uneven (see above): first two ranges hold 64,052 classes,
        # last two 64,051.
        "head_splits": 4,
        "head_rows": [64_052, 64_052, 64_051, 64_051],
    },
    # MADLAD400-3B-MT, from `google/madlad400-3b-mt`. A checkpoint for the same reason
    # NLLB is one: the repo ships `model.safetensors` (fp32) plus `spiece.model` and
    # `tokenizer.json` — there is no ONNX export at all.
    #
    # The architecture is `T5ForConditionalGeneration` (`model_type: t5`,
    # `feed_forward_proj: gated-gelu`, `tie_word_embeddings: false`), so the forward
    # pass is an encoder-decoder with 32 pre-norm RMSNorm layers a side, GEGLU
    # feed-forwards, untied embeddings, and T5 relative position biases — all asserted
    # by [`madlad_inventory`] and [`collect_madlad`]:
    #
    # * **32 encoder + 32 decoder layers**, `d_model` 1024, 16 heads of `d_kv` 128
    #   (`inner_dim` 2048), `d_ff` 8192.
    # * **Untied embeddings.** `decoder.embed_tokens.weight` is the encoder/decoder
    #   input table and `lm_head.weight` the logits kernel, and they differ —
    #   refuses a checkpoint where they match. Both are emitted, each as four
    #   64,000-class splits (256,000 divides evenly: no uneven tail as in NLLB).
    # * **No projection biases anywhere.** Every `q/k/v/o`, `wi_0/wi_1/wo` is
    #   `bias=False`, so each gets a synthesised zero recorded in the digest key —
    #   a checkpoint that grows one must not be read as this table.
    # * **No learned positions.** T5's position signal is the relative bias, computed
    #   on the host by `nets::madlad::relative_bias` and added as a plan input —
    #   so the bias tables are emitted fp16 for the host to read, not for a shader.
    # * **Bias tables on block 0 only.** `has_relative_attention_bias=bool(i == 0)`,
    #   so only the two block-0 self-attentions hold a `[32, 16]` table; deeper
    #   layers reuse the computed bias. Both tables are emitted once, at the end.
    "madlad400": {
        "d_model": 1024,
        "heads": 16,
        "head_dim": 128,
        "encoder_layers": 32,
        "decoder_layers": 32,
        "d_ff": 8192,
        "vocab": 256_000,
        # Four even head splits: 256,000 = 4 x 64,000.
        "head_splits": 4,
        "head_rows": [64_000, 64_000, 64_000, 64_000],
        "buckets": 32,
    },
    # Maia3-5M, from `UofTCSSLab/Maia3-5M`. A checkpoint because the repo ships a single
    # `maia3-5m.pt` pickle and nothing else — no ONNX, no safetensors. `fetch_maia.py`
    # repacks it, as `fetch_nllb600.py` does for NLLB's `.bin`.
    #
    # The architecture is `MAIA3Model` from CSSLab/maia3, with `BASE_SIZE_CONFIG` plus the
    # 5M row of `MODEL_SPECS`: 8 encoder-only blocks over 64 square tokens, `dim_vit` 256,
    # 8 heads, `mlp_ratio` 2.0. Four things about it are unlike anything else here, and all
    # four are asserted rather than assumed by [`maia_inventory`] and [`collect_maia`]:
    #
    # * **`omit_qkv_biases=True`.** `nn.MultiheadAttention(bias=False)`, so there is no
    #   `in_proj_bias` and no `out_proj.bias`. `Builder::conv` always reads a bias after
    #   its kernel, so four zeros per block are synthesised and recorded in the digest key.
    # * **A fused `in_proj_weight` `[768, 256]`.** Torch packs q, k and v into one tensor;
    #   the collector splits it into three in that order, which is
    #   `_in_projection_packed`'s.
    # * **`use_rms_norm=True`, post-norm.** `norm1` and `norm2` are `torch.nn.RMSNorm`, so
    #   they carry a weight and **no bias**. A checkpoint that grew `norm1.bias` would be
    #   an architecture change, not a detail, and fails here.
    # * **A shared attention-bias weight.** `smolgen_shared_weight` `[4096, 64]` is one
    #   parameter registered under nine names — itself plus
    #   `transformer.layers.N.self_attn.smolgen_weight` for all eight blocks, all aliasing
    #   the same storage. `fetch_maia.py` asserts that and drops the eight extras, so this
    #   inventory names only the shared one. See [`collect_maia`] for why it is emitted
    #   replicated eight times anyway.
    #
    # The value and ponder heads (`last_ln`, `fc_value*`, `fc_ponder*`) are in the
    # checkpoint and so are in the inventory, but are deliberately **not emitted**: nothing
    # picks a move with them. Asserting them keeps a re-trained head from passing silently.
    #
    # Every convolution is int8, quantised per output channel. That was chosen on measured
    # move agreement rather than on the cosine gate — see [`collect_maia`] and
    # `scripts/ml/maia_quant_eval.py`, which also rules out int4.
    "maia": {
        "d_model": 256,
        "blocks": 8,
        "heads": 8,
        "ffn": 512,
        "head_hidden": 256,
        # `gab_intermediate_dim` and `gab_gen_size`.
        "bias_hidden": 64,
        "bias_gen": 64,
        # `dim_emb`, each of the two elo embeddings' width.
        "elo_dim": 128,
        # `12 * history + 2 * dim_emb`, the token projection's input width.
        "input": 12 * 8 + 2 * 128,
        "squares": 64,
        "promotions": 4,
    },
}


class Layer:
    """One weighted node, as the Rust forward pass needs to see it."""

    def __init__(self, index, op, name, key, first_tensor, tensor_count):
        self.index = index
        self.op = op
        self.name = name
        self._key = key
        # Where this layer's tensors start in the `.maml` table, and how many it has.
        # The Rust indexes by tensor, and with `PRelu` in the mix that is no longer
        # `2 * layer` — so --print-layers reports it rather than leaving a transcriber
        # to count.
        self.first_tensor = first_tensor
        self.tensor_count = tensor_count

    def key(self):
        """Everything the Rust hardcodes about this layer, as a stable string."""
        return self._key


def lift_to_2d(weight, node):
    """A 1-D convolution's rank-3 weight as the rank-4 one this runtime indexes.

    A tensor here is `[c, h, w]` with no rank-3 case, so a 1-D convolution is a 2-D one
    whose kernel is `1 x k` — which is what every convolution in `nets::supertonic_vocoder`
    reads. Inserting the height axis is a reshape of the same bytes in the same order, and
    doing it here means the Rust reads `[out, in, 1, k]` like every other kernel rather than
    carrying a special case for audio.

    Returns `(weight, spatial)`, where `spatial` is the kernel/dilation/pad/stride form the
    digest key should record: also lifted to two dimensions, so a reader comparing the key
    against the Rust sees the same `(1, k)` the Rust wrote.
    """
    attrs = {a.name: a for a in node.attribute}

    def ints(name, default):
        return list(attrs[name].ints) if name in attrs else list(default)

    if weight.ndim != 3:
        return weight, None
    lifted = weight.reshape(weight.shape[0], weight.shape[1], 1, weight.shape[2])
    # ONNX orders pads as all the begins then all the ends, so a 1-D `[before, after]`
    # becomes `[0, before, 0, after]` rather than being padded on the right.
    pads = ints("pads", (0, 0))
    return lifted, {
        "kernel_shape": [1] + ints("kernel_shape", [weight.shape[2]]),
        "dilations": [1] + ints("dilations", [1]),
        "strides": [1] + ints("strides", [1]),
        "pads": [0, pads[0], 0, pads[1]],
    }


def convolution_key(node, weight, bias, spatial=None):
    """The digest key for a `Conv`/`ConvTranspose`.

    Byte-for-byte the string the first version of this script produced, so the pinned
    selfie and u2netp digests survive every later addition here.

    `spatial` overrides the node's own attributes, for a 1-D convolution that
    [`lift_to_2d`] has turned into a `1 x k` one.
    """
    attrs = {a.name: a for a in node.attribute}

    def ints(name, default):
        if spatial is not None and name in spatial:
            return list(spatial[name])
        return list(attrs[name].ints) if name in attrs else list(default)

    return (
        f"{node.op_type} w={list(weight.shape)} "
        f"b={list(bias.shape) if bias is not None else None} "
        f"k={ints('kernel_shape', weight.shape[2:])} "
        f"d={ints('dilations', (1, 1))} p={ints('pads', (0, 0, 0, 0))} "
        f"s={ints('strides', (1, 1))} "
        f"g={attrs['group'].i if 'group' in attrs else 1}"
    )


def inferred_shapes(model):
    """Every tensor's shape, for the fixed-shape graphs. Empty where inference fails."""
    try:
        inferred = shape_inference.infer_shapes(model)
    except Exception:
        return {}
    everything = list(inferred.graph.value_info) + list(inferred.graph.input)
    return {
        vi.name: [d.dim_value for d in vi.type.tensor_type.shape.dim]
        for vi in everything
    }


def flatten_source_shape(node, graph, shapes):
    """`[c, h, w]` of the map a `Gemm`'s flattened input came from.

    This is what lets a `Gemm` be emitted as a convolution kernel. It is read from the
    graph rather than assumed, because getting it wrong reshapes the fully-connected
    weight into the wrong kernel and produces a plausible-looking embedding.
    """
    producer = next((n for n in graph.node if node.input[0] in n.output), None)
    if producer is None or producer.op_type != "Flatten":
        raise SystemExit(
            f"{node.output[0]}: a Gemm whose input is not a Flatten. Only a Gemm over a "
            "flattened feature map can be emitted as a convolution."
        )
    shape = shapes.get(producer.input[0])
    if not shape or len(shape) != 4 or 0 in shape[1:]:
        raise SystemExit(
            f"{node.output[0]}: cannot infer the shape feeding {producer.output[0]}, "
            f"got {shape}. A Gemm can only be reshaped where the map's size is static."
        )
    return list(shape[1:])


def fold_batch_norm(node, inits, weight, bias):
    """Fold a `BatchNormalization` into the weight and bias of the layer before it.

    At inference a batch norm is `gamma * (x - mean) / sqrt(var + eps) + beta`, a
    per-output-channel affine, so pushing it into the preceding layer is exact. Doing it
    here rather than in a shader is why the runtime has no batch-norm pipeline.
    """
    attrs = {a.name: a for a in node.attribute}
    epsilon = attrs["epsilon"].f if "epsilon" in attrs else 1e-5
    missing = [n for n in node.input[1:] if n not in inits]
    if missing:
        raise SystemExit(f"{node.output[0]}: batch norm parameter {missing} is computed")
    gamma = numpy_helper.to_array(inits[node.input[1]]).astype(np.float64)
    beta = numpy_helper.to_array(inits[node.input[2]]).astype(np.float64)
    mean = numpy_helper.to_array(inits[node.input[3]]).astype(np.float64)
    var = numpy_helper.to_array(inits[node.input[4]]).astype(np.float64)
    scale = gamma / np.sqrt(var + epsilon)
    # fp64 for the fold itself: the division by sqrt(var) can be large, and this is the
    # one place the file's values are computed rather than copied.
    folded_weight = weight.astype(np.float64) * scale.reshape([-1] + [1] * (weight.ndim - 1))
    folded_bias = (bias.astype(np.float64) - mean) * scale + beta
    return folded_weight, folded_bias


def int8_eligible(node, consumers):
    """Whether `node`, a convolution, can be read as int8 by this runtime.

    Three exclusions, all forced by the runtime rather than chosen:

    * **`ConvTranspose`.** There is no int8 transposed convolution — `Builder` has `conv_int8`
      and no `conv_transpose_int8`, and `conv_transpose.comp` has no int8 twin.
    * **A convolution feeding a `PRelu`.** The Rust fuses that into the producing convolution
      as `Act::PRelu`, whose per-channel slope wants the push-block offset the dequantisation
      scale occupies. `Builder::conv_int8` refuses it outright.
    * **Edge padding.** `conv_int8.comp` reads out-of-bounds taps as zero and has no border
      replication, so a net that called `Builder::edge_padding` cannot quantise a *padded*
      convolution. Only the four Supertonic graphs do, and they are not converted through
      here — so this is documented rather than tested for.

    Everything else is fair game. `conv_int8.comp` handles groups, arbitrary kernel sizes,
    strides, dilations and zero padding, which is worth stating because
    `nets::supertonic_vocoder::INT8_CONVS` reads as though grouping were the blocker; there,
    the depthwise convolutions are excluded because they are *edge-padded*, not because they
    are grouped.
    """
    if node.op_type != "Conv":
        return False
    return not any(c.op_type == "PRelu" for c in consumers.get(node.output[0], ()))


def collect_layers(model, prefix=None, quantise=False):
    """The weighted nodes in topological order, with the tensors each contributes.

    `prefix` restricts the walk to one Torch module; see [`MODULES`].

    `quantise` reads every eligible convolution as int8 rather than fp16 — three tensors
    (kernel, per-output-channel scale, bias) instead of two. See [`INT8_GRAPHS`] for which
    graphs ask for it and [`int8_eligible`] for what "eligible" excludes and why.
    """
    graph = model.graph
    inits = {i.name: i for i in graph.initializer}
    # A module-scoped export keeps its weights in `Constant` nodes rather than the
    # initializer list, the way PP-OCRv5's recognition export does.
    constants = {}
    for node in graph.node:
        if node.op_type == "Constant":
            for attribute in node.attribute:
                if attribute.name == "value":
                    constants[node.output[0]] = attribute.t
    shapes = inferred_shapes(model)

    def held(name):
        return name in inits or name in constants

    def array(name):
        return numpy_helper.to_array(inits[name] if name in inits else constants[name])

    # Which node's output each BatchNormalization consumes, so the layer producing it
    # can absorb it.
    batch_norms = {n.input[0]: n for n in graph.node if n.op_type == "BatchNormalization"}
    consumers = {}
    for node in graph.node:
        for name in node.input:
            consumers.setdefault(name, []).append(node)
    folded = set()

    layers = []
    tensors = []
    fidelity = Fidelity() if quantise else None
    for node in graph.node:
        if prefix is not None and not node.name.startswith(prefix):
            continue
        first_tensor = len(tensors)
        if node.op_type in ("Conv", "ConvTranspose"):
            missing = [n for n in node.input[1:] if not held(n)]
            if missing:
                raise SystemExit(
                    f"{node.output[0]}: weight/bias {missing} is not a constant. "
                    "A computed weight means this is not the frozen export we lower."
                )
            weight = array(node.input[1])
            weight, spatial = lift_to_2d(weight, node)
            bias = array(node.input[2]) if len(node.input) > 2 else None
            synthesised = bias is None
            if synthesised:
                # The shaders always add a bias, so a layer without one gets a zero. Exact,
                # and recorded in the key so the digest notices if a layer that used to have
                # a bias stops having one — which would otherwise look like the same table.
                channels = weight.shape[1] if node.op_type == "ConvTranspose" else weight.shape[0]
                bias = np.zeros(channels, dtype=np.float32)
            key = convolution_key(node, weight, bias, spatial)
            if synthesised:
                key += " b0=synthesised"
            if quantise and int8_eligible(node, consumers):
                kernel, scale = fidelity.quantise(node.name or node.output[0], weight)
                tensors.append(kernel)
                tensors.append(scale)
                tensors.append(bias)
                key = key.replace("Conv ", "ConvInt8 ", 1) + " zp=0 dtype=int8"
            else:
                tensors.append(weight)
                tensors.append(bias)
        elif node.op_type == "PRelu":
            if not held(node.input[1]):
                raise SystemExit(f"{node.output[0]}: a computed PRelu slope")
            slope = array(node.input[1])
            key = f"PRelu s={list(slope.shape)}"
            tensors.append(slope)
        elif node.op_type == "Gemm":
            attrs = {a.name: a for a in node.attribute}
            alpha = attrs["alpha"].f if "alpha" in attrs else 1.0
            beta = attrs["beta"].f if "beta" in attrs else 1.0
            trans_a = attrs["transA"].i if "transA" in attrs else 0
            trans_b = attrs["transB"].i if "transB" in attrs else 0
            if (alpha, beta, trans_a, trans_b) != (1.0, 1.0, 0, 1):
                raise SystemExit(
                    f"{node.output[0]}: Gemm alpha={alpha} beta={beta} transA={trans_a} "
                    f"transB={trans_b}; only the plain `x @ W.T + b` form is lowered."
                )
            missing = [n for n in node.input[1:] if not held(n)]
            if missing:
                raise SystemExit(f"{node.output[0]}: Gemm operand {missing} is computed")
            weight = array(node.input[1])
            bias = array(node.input[2])
            batch_norm = batch_norms.get(node.output[0])
            if batch_norm is not None:
                weight, bias = fold_batch_norm(batch_norm, inits, weight, bias)
                folded.add(batch_norm.output[0])
            spatial = flatten_source_shape(node, graph, shapes)
            expected = int(np.prod(spatial))
            if weight.shape[1] != expected:
                raise SystemExit(
                    f"{node.output[0]}: Gemm weight is {list(weight.shape)} but its input "
                    f"flattens {spatial} = {expected} values"
                )
            weight = weight.reshape([weight.shape[0]] + spatial)
            key = (
                f"Gemm w={list(weight.shape)} b={list(bias.shape)} "
                f"bn={batch_norm is not None}"
            )
            tensors.append(weight)
            tensors.append(bias)
        elif node.op_type == "Gather" and held(node.input[0]) and array(node.input[0]).ndim == 2:
            # An embedding table. An export that multiplies the looked-up row by
            # `sqrt(d_model)` has that scale folded in here rather than left to a shader: it
            # is one op fewer and one rounding fewer, since `round(table * sqrt(d))` is
            # closer than `round(table) * sqrt(d)`.
            table = array(node.input[0]).astype(np.float64)
            table = table * math.sqrt(table.shape[1])
            key = f"Embed t={list(table.shape)} scaled=sqrt({table.shape[1]})"
            tensors.append(table)
        elif node.op_type == "Pad" and held(node.input[0]):
            # A relative position table, which the export pads out to `2T-1` entries and
            # then skews. `nets::supertonic_text` and `nets::supertonic_duration` read the
            # nine unpadded entries directly, so the leading batch axis is dropped and the
            # padding never happens.
            table = np.squeeze(array(node.input[0]))
            if table.ndim != 2 or table.shape[0] % 2 == 0:
                raise SystemExit(
                    f"{node.output[0]}: a padded constant of {list(np.shape(table))}; a "
                    "relative table is [2 * window + 1, head_dim]"
                )
            key = f"Relative t={list(table.shape)}"
            tensors.append(table)
        elif node.op_type == "Mul" and layer_norm_scale(node, consumers, held, array):
            # A layer norm's gamma and the beta on the `Add` after it. The export decomposes
            # the rest of the norm into ReduceMean/Sub/Pow/Sqrt/Div, none of which carries a
            # weight, so this pair is all there is to collect.
            gamma = next(array(n) for n in node.input if held(n))
            follow = consumers[node.output[0]][0]
            beta = next(array(n) for n in follow.input if held(n))
            key = f"LayerNorm g={list(gamma.shape)} b={list(beta.shape)}"
            tensors.append(gamma)
            tensors.append(beta)
        elif node.op_type == "BatchNormalization":
            # Folded above, when the layer it follows was emitted. One that was not is a
            # batch norm somewhere in the middle of the graph, which nothing here can
            # absorb and no shader implements.
            if node.output[0] not in folded:
                raise SystemExit(
                    f"{node.output[0]}: a BatchNormalization that does not follow a Gemm. "
                    "Only a trailing batch norm can be folded away."
                )
            continue
        else:
            continue
        layers.append(
            Layer(
                len(layers),
                node.op_type,
                node.output[0],
                key,
                first_tensor,
                len(tensors) - first_tensor,
            )
        )
    if fidelity is not None:
        fidelity.report()
    return layers, tensors


def layer_norm_scale(node, consumers, held, array):
    """Whether `node` is the `Mul(x, gamma)` of a decomposed layer norm.

    Recognised by structure rather than by name: a `Mul` against a rank-1 constant whose one
    consumer is an `Add` against a rank-1 constant of the same length. In the exports this reads
    that matches the layer norms and nothing else - mask multiplies and embedding scales are
    against scalars or computed tensors.
    """
    scales = [n for n in node.input if held(n) and array(n).ndim == 1]
    if len(scales) != 1:
        return False
    follow = consumers.get(node.output[0], [])
    if len(follow) != 1 or follow[0].op_type != "Add":
        return False
    shifts = [
        n
        for n in follow[0].input
        if held(n) and array(n).ndim == 1 and array(n).size == array(scales[0]).size
    ]
    return len(shifts) == 1


def layer_table_digest(layers):
    h = hashlib.sha256()
    for layer in layers:
        h.update(layer.key().encode())
        h.update(b"\n")
    return h.hexdigest()


def check_graph(model, graph_id_name):
    graph = model.graph
    prefix = MODULES.get(graph_id_name)
    nodes = [n for n in graph.node if prefix is None or n.name.startswith(prefix)]

    if prefix is None and graph_id_name in GEOMETRY:
        want = GEOMETRY[graph_id_name]
        names = [i.name for i in graph.input]
        if names != [want["input"]]:
            raise SystemExit(f"inputs are {names}, expected [{want['input']!r}]")
        dims = [
            d.dim_value or d.dim_param for d in graph.input[0].type.tensor_type.shape.dim
        ]
        # Batch may be symbolic. A spatial dim may be too, but only where the expected
        # shape says so: for the fixed-size nets every folded Resize target and pad below
        # was resolved at exactly this size, so a dynamic one would silently mislower.
        got = list(dims[1:])
        expect = want["shape"]
        mismatched = len(got) != len(expect) or any(
            e is not None and g != e for g, e in zip(got, expect)
        )
        if mismatched:
            raise SystemExit(f"input shape {dims}, expected [batch] + {expect}")

        outputs = [o.name for o in graph.output]
        absent = [o for o in want["outputs"] if o not in outputs]
        if absent:
            raise SystemExit(f"outputs are {outputs}, expected {absent} among them")
    elif not nodes:
        raise SystemExit(
            f"no nodes are named {prefix!r}. A module-scoped graph relies on the export "
            "keeping its Torch module paths, which this one has not."
        )

    counts = {}
    for node in nodes:
        counts[node.op_type] = counts.get(node.op_type, 0) + 1
    expected = EXPECTED_OPS.get(graph_id_name)
    if expected is None:
        # A graph checked by structure rather than by op census. The Gemma 4 exports are the
        # ones: a decoder whose op counts move with `num_hidden_layers` and two encoders whose
        # initializers are anonymised, so `check_architecture` and the collectors' own node
        # numbering carry the check instead. See `ARCHITECTURES`.
        if graph_id_name not in ARCHITECTURES:
            raise SystemExit(f"{graph_id_name} has neither an op census nor an architecture")
        return
    if counts != expected:
        only_got = {k: v for k, v in counts.items() if expected.get(k) != v}
        only_want = {k: v for k, v in expected.items() if counts.get(k) != v}
        raise SystemExit(f"op counts differ: got {only_got}, expected {only_want}")


def open_checkpoint(path):
    """`(name -> ndarray, {name: shape})` for a `.safetensors` file, read one tensor at a time.

    Lazily, because the checkpoint is 1.33 GB of fp32 and every tensor is quantised to int8 or
    rounded to fp16 the moment it is read - so holding the whole thing costs 1.3 GB of resident
    host memory for nothing.
    """
    try:
        from safetensors import safe_open
    except ImportError:
        raise SystemExit("needs safetensors: python -m pip install safetensors")
    handle = safe_open(path, framework="np")
    shapes = {name: list(handle.get_slice(name).get_shape()) for name in handle.keys()}
    return handle.get_tensor, shapes


# GGUF tensor types this reads. Only the two MADLAD's file holds: Q2_K linears and F32
# norms/tables. Anything else is a file this has not been read against, and should fail
# rather than be silently mis-carried.
GGUF_F32 = 0
GGUF_Q2_K = 10


def open_gguf(path):
    """`(get, shapes)` for a GGUF file, mirroring [`open_checkpoint`].

    `get(name)` returns `(dtype_code, raw_bytes, gguf_shape)` — the payload untouched,
    because Q2_K blocks are carried verbatim (transposed at the block level by the
    collector, never dequantised). `shapes` maps names to the GGUF `[cols, rows]` shape
    as stored; the collector compares against the transposed expectation.

    The reader is hand-rolled (magic, version, counts, KV skip, tensor infos) rather than
    via the `gguf` package, because the converter needs file offsets for lazy reads and
    the package materialises every tensor. Only versions and tensor layouts verified
    against `model-q2k.gguf` are accepted.
    """
    import struct as struct_

    with open(path, "rb") as handle:
        blob = handle.read()

    if blob[:4] != b"GGUF":
        raise SystemExit(f"{path} holds no GGUF flatbuffer")
    version = struct_.unpack("<I", blob[4:8])[0]
    if version != 2:
        raise SystemExit(f"{path} is GGUF version {version}, not the version 2 read here")
    n_tensors = struct_.unpack("<Q", blob[8:16])[0]
    n_kv = struct_.unpack("<Q", blob[16:24])[0]

    at = 24

    def read_string():
        nonlocal at
        (length,) = struct_.unpack("<Q", blob[at : at + 8])
        at += 8
        value = blob[at : at + length].decode("utf-8")
        at += length
        return value

    def skip_value(type_):
        nonlocal at
        # GGUF metadata types: u8/i8/u16/i16/u32/i32/f32/bool/string/array/u64/i64/f64.
        if type_ == 8:  # string
            (length,) = struct_.unpack("<Q", blob[at : at + 8])
            at += 8 + length
        elif type_ == 9:  # array
            (inner, count) = struct_.unpack("<IQ", blob[at : at + 12])
            at += 12
            for _ in range(count):
                skip_value(inner)
        elif type_ in (0, 1, 6):
            at += 1
        elif type_ in (2, 3, 4, 5):
            at += 2 if type_ in (2, 3) else 4
        elif type_ in (10, 11, 12):
            at += 8
        else:
            raise SystemExit(f"{path}: unhandled GGUF metadata type {type_}")

    for _ in range(n_kv):
        read_string()
        (type_,) = struct_.unpack("<I", blob[at : at + 4])
        at += 4
        skip_value(type_)

    infos = {}
    for _ in range(n_tensors):
        name = read_string()
        (ndims,) = struct_.unpack("<I", blob[at : at + 4])
        at += 4
        dims = [struct_.unpack("<Q", blob[at + 8 * i : at + 8 * (i + 1)])[0] for i in range(ndims)]
        at += 8 * ndims
        (dtype,) = struct_.unpack("<I", blob[at : at + 4])
        at += 4
        (offset,) = struct_.unpack("<Q", blob[at : at + 8])
        at += 8
        infos[name] = (dtype, dims, offset)

    # Tensor data starts at the first 32-byte-aligned offset past the header.
    data_start = (at + 31) & ~31

    def get(name):
        try:
            dtype, dims, offset = infos[name]
        except KeyError:
            raise SystemExit(f"{path} holds no tensor named {name}")
        if dtype == GGUF_Q2_K:
            # Q2_K payload size is exact: rows are 256-tap superblocks at 84 bytes.
            elems = 1
            for d in dims:
                elems *= d
            size = elems // Q2K_BLOCK * Q2K_BYTES
        elif dtype == GGUF_F32:
            size = 1
            for d in dims:
                size *= d
            size *= 4
        else:
            raise SystemExit(f"{name} is GGUF type {dtype}, which is not read")
        payload = blob[data_start + offset : data_start + offset + size]
        if len(payload) != size:
            raise SystemExit(f"{name} runs past the file ({len(payload)} of {size} bytes)")
        return dtype, payload, dims

    shapes = {name: list(dims) for name, (_, dims, _) in infos.items()}
    return get, shapes


def small100_inventory(spec):
    """`{name: shape}` the checkpoint must hold exactly, derived from [`CHECKPOINTS`].

    Derived rather than transcribed: a list of 275 names would be checked against itself.
    """
    d, ffn = spec["d_model"], spec["ffn"]
    want = {"model.shared.weight": [spec["vocab"], d]}
    for side, count in (("encoder", spec["encoder_layers"]), ("decoder", spec["decoder_layers"])):
        want[f"model.{side}.layer_norm.weight"] = [d]
        want[f"model.{side}.layer_norm.bias"] = [d]
        for index in range(count):
            at = f"model.{side}.layers.{index}"
            attentions = ["self_attn"] + (["encoder_attn"] if side == "decoder" else [])
            for attention in attentions:
                want[f"{at}.{attention}_layer_norm.weight"] = [d]
                want[f"{at}.{attention}_layer_norm.bias"] = [d]
                for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                    want[f"{at}.{attention}.{projection}.weight"] = [d, d]
                    want[f"{at}.{attention}.{projection}.bias"] = [d]
            want[f"{at}.final_layer_norm.weight"] = [d]
            want[f"{at}.final_layer_norm.bias"] = [d]
            want[f"{at}.fc1.weight"] = [ffn, d]
            want[f"{at}.fc1.bias"] = [ffn]
            want[f"{at}.fc2.weight"] = [d, ffn]
            want[f"{at}.fc2.bias"] = [d]
    return want


def whisper_inventory(spec):
    """`{name: shape}` whisper-base's checkpoint must hold exactly, derived from [`CHECKPOINTS`].

    Derived rather than transcribed: a list of 245 names would be checked against itself.

    Two absences are as load-bearing as the presences, so both are stated by *not* appearing:

    * **`k_proj.bias` is not here**, in any of the 18 attentions. A checkpoint that grows one would
      have 18 unexpected names and fail, rather than being read as this table with the bias ignored.
    * **There is no `proj_out`.** The head is tied to `model.decoder.embed_tokens.weight`, which
      therefore appears once and is emitted once.

    The shapes are `torch.nn.Linear`'s **`[out, in]`**, which is a `[out, in, 1, 1]` kernel and so a
    reshape rather than a transpose. `fc1` at `[2048, 512]` and `fc2` at `[512, 2048]` are the
    asymmetric pair that makes a transposed checkpoint fail here rather than on the device.
    """
    d, ffn = spec["d_model"], spec["ffn"]
    kernel = spec["conv_kernel"]
    want = {
        "model.encoder.conv1.weight": [d, spec["mels"], kernel],
        "model.encoder.conv1.bias": [d],
        "model.encoder.conv2.weight": [d, d, kernel],
        "model.encoder.conv2.bias": [d],
        # A real tensor, not computed sinusoids: whisper freezes them into the checkpoint.
        "model.encoder.embed_positions.weight": [spec["source_positions"], d],
        "model.decoder.embed_tokens.weight": [spec["vocab"], d],
        # Learned, unlike the encoder's.
        "model.decoder.embed_positions.weight": [spec["target_positions"], d],
    }
    for side, count in (("encoder", spec["encoder_layers"]), ("decoder", spec["decoder_layers"])):
        want[f"model.{side}.layer_norm.weight"] = [d]
        want[f"model.{side}.layer_norm.bias"] = [d]
        for index in range(count):
            at = f"model.{side}.layers.{index}"
            attentions = ["self_attn"] + (["encoder_attn"] if side == "decoder" else [])
            for attention in attentions:
                want[f"{at}.{attention}_layer_norm.weight"] = [d]
                want[f"{at}.{attention}_layer_norm.bias"] = [d]
                for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                    want[f"{at}.{attention}.{projection}.weight"] = [d, d]
                    # Every projection but the key has a bias. See the docstring.
                    if projection != "k_proj":
                        want[f"{at}.{attention}.{projection}.bias"] = [d]
            want[f"{at}.final_layer_norm.weight"] = [d]
            want[f"{at}.final_layer_norm.bias"] = [d]
            want[f"{at}.fc1.weight"] = [ffn, d]
            want[f"{at}.fc1.bias"] = [ffn]
            want[f"{at}.fc2.weight"] = [d, ffn]
            want[f"{at}.fc2.bias"] = [d]
    return want


# The inventory each checkpoint-sourced graph is checked against, and the collector that emits it.
#
# Two dicts rather than a key inside [`CHECKPOINTS`] because both hold functions defined below it.
INVENTORIES = {}
COLLECTORS = {}


def check_checkpoint(shapes, graph_id_name):
    """Refuse a checkpoint that is not the architecture the Rust forward pass hardcodes.

    # The transpose that would otherwise be silent

    A `torch.nn.Linear`'s weight is **`[out, in]`**, and this runtime indexes a kernel as
    `[out, in, kh, kw]`, so a projection needs a reshape and no transpose. ONNX `MatMul` is the
    other way round - `x @ W` with a `[in, out]` weight - and every attention projection here is
    square 1024 x 1024, so reading one convention as the other yields a tensor of exactly the
    right shape holding exactly the wrong numbers, and a parameter count that still adds up.

    `fc1` is `[4096, 1024]` and `fc2` is `[1024, 4096]`, so the shape check below is what makes
    that mistake loud: a transposed checkpoint fails on those four numbers before anything is
    written. That is the whole reason this asserts shapes rather than just names.
    """
    want = INVENTORIES[graph_id_name](CHECKPOINTS[graph_id_name])
    missing = sorted(set(want) - set(shapes))
    extra = sorted(set(shapes) - set(want))
    shared = sorted(set(want) & set(shapes))
    wrong = {n: (shapes[n], want[n]) for n in shared if shapes[n] != want[n]}
    if missing or extra or wrong:
        lines = []
        if missing:
            lines.append(f"  absent: {missing[:6]}{' ...' if len(missing) > 6 else ''}")
        if extra:
            lines.append(f"  unexpected: {extra[:6]}{' ...' if len(extra) > 6 else ''}")
        for name, (got, expect) in list(wrong.items())[:6]:
            lines.append(f"  {name}: {got} against {expect}")
        raise SystemExit(
            f"{graph_id_name}: the checkpoint is not the architecture nets/{graph_id_name}.rs\n"
            "hardcodes. A wrong shape here is a wrong forward pass, not a load failure:\n"
            + "\n".join(lines)
        )


class Fidelity:
    """The worst per-tensor cosine between an fp32 weight and the `int8 * scale` a device reads.

    The only check that looks at the *numbers* a quantisation produces. It is not a check that the
    forward pass is right — a transposed read agrees with itself perfectly, which is why
    [`check_checkpoint`] and [`check_architecture`] assert shapes — and it is not a check that the
    model is accurate, which `scripts/ml/onnx_parity.py` is for. It is the one thing that catches a
    layer whose distribution int8 cannot represent, before it ships.
    """

    def __init__(self):
        self.seen = []

    def quantise(self, name, weight):
        """`(int8, scale)` for `weight`, recording how well the pair reproduces it."""
        kernel, scale = quantise_per_channel(weight)
        back = kernel.astype(np.float32).reshape(weight.shape[0], -1) * scale[:, None]
        flat = np.ascontiguousarray(weight, dtype=np.float32).reshape(weight.shape[0], -1)
        cosine = float(
            np.dot(flat.ravel(), back.ravel())
            / max(np.linalg.norm(flat) * np.linalg.norm(back), 1e-30)
        )
        self.seen.append((cosine, name))
        return kernel, scale

    def quantise4(self, name, weight):
        """`(Int4, scale)` for `weight`, recording how well the pair reproduces it.

        The int4 counterpart of [`Fidelity.quantise`]. The reconstruction differs: a block's
        scale covers `I4_BLOCK` taps rather than a whole row, so the cosine is measured against
        the block-wise product a shader actually computes and not against a row-wise one, which
        would flatter it.
        """
        codes, scale = quantise_per_block(weight)
        flat = np.ascontiguousarray(weight, dtype=np.float32).reshape(weight.shape[0], -1)
        rows, taps = flat.shape
        blocks = scale.shape[1]
        # Expand the block scale back over the taps it covers, dropping the padding of the last
        # short block, so the comparison is element for element.
        wide = np.repeat(scale, I4_BLOCK, axis=1)[:, :taps]
        back = codes.astype(np.float32).reshape(rows, taps) * wide
        cosine = float(
            np.dot(flat.ravel(), back.ravel())
            / max(np.linalg.norm(flat) * np.linalg.norm(back), 1e-30)
        )
        self.seen.append((cosine, name))
        del blocks
        return Int4(codes), scale

    def report(self, floor=None):
        """Print the worst three, and refuse the file if any is under the floor.

        `floor` defaults to [`MIN_INT8_COSINE`]; an int4 collector passes
        [`MIN_INT4_COSINE`], which is looser because four bits cannot meet the int8 bar and
        should not be asked to. See that constant for the measured numbers.
        """
        floor = MIN_INT8_COSINE if floor is None else floor
        self.seen.sort()
        for cosine, name in self.seen[:3]:
            if cosine < floor:
                raise SystemExit(
                    f"{name}: the quantised weight correlates {cosine:.6f} with the fp32 one,"
                    f" under {floor}.\nQuantising this layer costs too much; exclude it and"
                    " store it fp16 instead."
                )
        worst = ", ".join(f"{name} {cosine:.6f}" for cosine, name in self.seen[:3])
        print(f"fidelity over {len(self.seen)} tensors, worst three: {worst}")


def collect_small100(get, spec):
    """SMaLL-100's checkpoint as the ordered tensor table `nets::small100` indexes.

    Order is the contract, and it is:

        the tied embedding, as `head_splits` disjoint class ranges
        each encoder layer, in forward order
        the encoder's final layer norm
        each decoder layer, in forward order
        the decoder's final layer norm

    The **tied embedding comes first and appears once**, because it has two roles and neither
    wants a second copy. `nets::small100` gathers row `t` of it on the host to build the encoder
    and decoder inputs - the rows are contiguous, and each carries its own scale, which is exactly
    what a per-output-channel quantiser produces - and binds the same tensors as the kernel of the
    logits projection. Splitting it by class range is what keeps that binding under
    `maxStorageBufferRange`; see [`CHECKPOINTS`].

    # What is folded, and what is not

    * `sqrt(d_model)` is **not** folded into the embedding here, unlike the fp16 `Gather` arm in
      [`collect_layers`]. The host gather applies it while it is still fp32, which is one rounding
      fewer than scaling an int8 code by its scale and then by `sqrt(1024)`.
    * The attention query scale is **not** folded into `q_proj`. M2M-100 scales the query by
      `head_dim ** -0.5` and `head_dim` is 64, which is the `1 / sqrt(head_dim)` `attn_scores`
      already applies. Folding it would apply it twice.
    * Sinusoidal positions are not in the checkpoint and are not emitted. They are static, so
      `nets::small100` computes them and adds them to the gathered rows in fp32, which also
      removes the `pos_weights.f32.bin` the ncnn build had to download.
    """
    layers = []
    tensors = []
    # Worst per-tensor cosine between the fp32 weight and `int8 * scale`, which is the numbers
    # the device will read. This is the only check that the quantisation itself is usable; the
    # shape assertions in [`check_checkpoint`] are what catch a transpose, since a cosine is
    # computed against whatever was read and a transposed read agrees with itself perfectly.
    fidelity = Fidelity()

    def emit(op, name, key, added):
        layers.append(Layer(len(layers), op, name, key, len(tensors) - added, added))

    def linear(name):
        """An int8 kernel as `[out, in, 1, 1]`, its per-output-channel scale, and its bias."""
        weight = get(f"{name}.weight")
        bias = get(f"{name}.bias")
        outputs, inputs = weight.shape
        kernel, scale = fidelity.quantise(name, weight.reshape(outputs, inputs, 1, 1))
        tensors.extend([kernel, scale, bias])
        emit(
            "Linear8",
            name,
            f"Linear8 w={list(kernel.shape)} scale={[outputs]} b={list(bias.shape)} "
            "zp=0 dtype=int8",
            3,
        )

    def layer_norm(name):
        gamma = get(f"{name}.weight")
        beta = get(f"{name}.bias")
        tensors.extend([gamma, beta])
        emit("LayerNorm", name, f"LayerNorm g={list(gamma.shape)} b={list(beta.shape)}", 2)

    embedding = get("model.shared.weight")
    splits = spec["head_splits"]
    classes, width = embedding.shape
    if classes % splits:
        raise SystemExit(f"{classes} classes do not divide into {splits} equal ranges")
    step = classes // splits
    for part in range(splits):
        lo = part * step
        rows = embedding[lo : lo + step]
        name = f"model.shared.weight[{lo}:{lo + step}]"
        kernel, scale = fidelity.quantise(name, rows.reshape(step, width, 1, 1))
        # `Builder::conv_int8` reads a bias after the scale, and a tied head has none. Zeros are
        # exact, and recording it in the key means a checkpoint that grows one is not mistaken for
        # this table.
        tensors.extend([kernel, scale, np.zeros(step, dtype=np.float32)])
        emit(
            "Head",
            name,
            f"Head w={list(kernel.shape)} scale={[step]} b={[step]} "
            f"classes={lo}..{lo + step} zp=0 dtype=int8 b0=synthesised",
            3,
        )

    for side, count in (("encoder", spec["encoder_layers"]), ("decoder", spec["decoder_layers"])):
        for index in range(count):
            at = f"model.{side}.layers.{index}"
            # Pre-norm, as `M2M100EncoderLayer` and `M2M100DecoderLayer` are: the norm comes
            # before the sublayer it feeds and the residual skips both. Emitting in forward order
            # is what lets the Rust walk the table without an index table.
            layer_norm(f"{at}.self_attn_layer_norm")
            for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                linear(f"{at}.self_attn.{projection}")
            if side == "decoder":
                layer_norm(f"{at}.encoder_attn_layer_norm")
                for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                    linear(f"{at}.encoder_attn.{projection}")
            layer_norm(f"{at}.final_layer_norm")
            linear(f"{at}.fc1")
            linear(f"{at}.fc2")
        layer_norm(f"model.{side}.layer_norm")

    fidelity.report()
    return layers, tensors


def check_gguf(shapes, graph_id_name):
    """Refuse a GGUF that is not the architecture the Rust forward pass hardcodes.

    The mirror of [`check_checkpoint`] for GGUF sources: same parameter NAMES as
    [`madlad_inventory`] (so the two registries cannot drift — the names are derived
    from one spec), but GGUF `[in, out]` shapes and Q2_K/F32 dtype assertions instead
    of torch shapes. A transposed or resized file fails here rather than on the device.
    """
    want_names = madlad_inventory(CHECKPOINTS[graph_id_name])
    # GGUF shape is the torch shape reversed: q/k/v `[2048, 1024]` read as `[1024, 2048]`.
    want = {name: list(reversed(shape)) for name, shape in want_names.items()}
    missing = sorted(set(want) - set(shapes))
    extra = sorted(set(shapes) - set(want))
    shared = sorted(set(want) & set(shapes))
    wrong = {n: (shapes[n], want[n]) for n in shared if shapes[n] != want[n]}
    if missing or extra or wrong:
        lines = []
        if missing:
            lines.append(f"  absent: {missing[:6]}{' ...' if len(missing) > 6 else ''}")
        if extra:
            lines.append(f"  unexpected: {extra[:6]}{' ...' if len(extra) > 6 else ''}")
        for name, (got, expect) in list(wrong.items())[:6]:
            lines.append(f"  {name}: {got} against {expect}")
        raise SystemExit(
            f"{graph_id_name}: the GGUF is not the architecture nets/{graph_id_name}.rs\n"
            "hardcodes. A wrong shape here is a wrong forward pass, not a load failure:\n"
            + "\n".join(lines)
        )


class Q2K:
    """A tensor to serialise as [`DTYPE_Q2K`].

    Carries the raw Q2_K superblocks (84 bytes per 256 taps, in flat order) plus the
    `(rows, blocks)` count the reader needs. Like [`Int4`]: numpy has no two-bit dtype,
    so `build` cannot infer this from values — wrapping says so explicitly.

    Quacks like an array for the attributes `build` needs (`shape`, `ndim`, `size`).
    """

    def __init__(self, blocks, rows, taps):
        self.blocks = blocks
        self.shape = (rows, taps)
        self.ndim = 2
        self.size = rows * taps


def _q2k_checked(get, name, torch_shape):
    """The GGUF payload for `name`, checked against `torch_shape` ([outputs, inputs]).

    Verifies dtype, block-divisibility and byte size. GGUF flat order == torch flat order
    (candle's dim reversal), so no transpose — the payload goes into the file unchanged.
    """
    dtype, payload, _ = get(f"{name}.weight")
    if dtype != GGUF_Q2_K:
        raise SystemExit(f"{name}.weight is GGUF type {dtype}, not Q2_K")
    outputs, inputs = torch_shape
    if inputs % Q2K_BLOCK:
        raise SystemExit(f"{name}: {inputs} taps is not block-divisible by {Q2K_BLOCK}")
    blocks = inputs // Q2K_BLOCK
    if len(payload) != outputs * blocks * Q2K_BYTES:
        raise SystemExit(f"{name}: {len(payload)} bytes, not {outputs * blocks * Q2K_BYTES}")
    return payload


def _emit_q2k(layers, tensors, emit, name, payload, outputs, inputs, op="LinearQ2K",
              extra=""):
    """A Q2_K kernel + its `(d, dmin)` fp16 table + a synthesised zero bias (3 tensors).

    The `(d, dmin)` table is re-derived from the superblocks rather than sliced, so the
    file stays self-describing; the shader reads this table, not the superblocks'
    embedded fp16 (same values, one fetch instead of two).
    """
    import numpy as _np

    blocks = inputs // Q2K_BLOCK
    tensors.append(Q2K(payload, outputs, inputs))
    raw = _np.frombuffer(payload, dtype=_np.uint8).reshape(outputs * blocks, Q2K_BYTES)
    table = _np.empty((outputs * blocks, 2), dtype=_np.float32)
    for b in range(outputs * blocks):
        table[b, 0] = _np.frombuffer(raw[b, 80:82].tobytes(), dtype=_np.float16)[0]
        table[b, 1] = _np.frombuffer(raw[b, 82:84].tobytes(), dtype=_np.float16)[0]
    tensors.append(_np.ascontiguousarray(table.reshape(outputs * blocks * 2)))
    tensors.append(_np.zeros(outputs, dtype=_np.float32))
    emit(
        op,
        name,
        f"{op} w={[outputs, inputs]} blocks={blocks} "
        f"scale={[outputs, blocks, 2]} b={[outputs]} zp=0 dtype=q2k b0=synthesised"
        f"{extra}",
        3,
    )


def collect_madlad_q2k(get, spec):
    """MADLAD400-3B-MT's GGUF as the ordered tensor table `nets::madlad` indexes.

    Same emission ORDER as [`collect_madlad`] (8 Head + 256 enc + 1 + 416 dec + 1 +
    2 Relative = 684 layers, 1724 tensors, same names) — the Rust walks positionally,
    so the two collectors must agree exactly. What differs is the source and the
    payload: Q2_K superblocks carried verbatim from `model-q2k.gguf` (dequantised only
    to verify orientation at development time, never in the pipeline), fp16 norms and
    tables read straight from the GGUF's F32 tensors.

    Per linear: the GGUF payload reshaped to torch rows via the flat rule
    (`torch_flat == gguf_flat`), requantised NOT AT ALL — the 84-byte superblocks go
    into the file unchanged, with the per-superblock `(d, dmin)` fp16 table beside them
    (in the slot a per-channel scale would occupy) and a synthesised zero bias after
    that. No fidelity gate on these tensors: their error is the file's own, already
    validated (cosine ~0.96 vs fp32, the Q2_K band).
    """
    layers = []
    tensors = []

    def emit(op, name, key, added):
        layers.append(Layer(len(layers), op, name, key, len(tensors) - added, added))

    def linear_q2k(name, torch_shape):
        """A Q2_K kernel verbatim, its `(d, dmin)` table, and a synthesised zero bias."""
        payload = _q2k_checked(get, name, torch_shape)
        _emit_q2k(layers, tensors, emit, name, payload, *torch_shape)

    def rms_norm(name):
        """An RMS norm's gain, fp16 from the GGUF's F32. One tensor: no beta."""
        dtype, payload, gguf_shape = get(f"{name}.weight")
        if dtype != GGUF_F32:
            raise SystemExit(f"{name}.weight is GGUF type {dtype}, not F32")
        import numpy as _np

        gamma = _np.frombuffer(payload, dtype=_np.float32)
        tensors.append(gamma)
        emit("RmsNorm", name, f"RmsNorm g={list(gamma.shape)}", 1)

    def head_q2k(table, rows_spec, torch_taps):
        dtype, payload, _ = get(f"{table}.weight")
        if dtype != GGUF_Q2_K:
            raise SystemExit(f"{table}.weight is GGUF type {dtype}, not Q2_K")
        # Whole-table payload, split by class range into per-split kernels. A split
        # boundary falls on a superblock boundary (every split row count divides by 256).
        # Torch rows: flat payload reshaped. GGUF flat == torch flat.
        for rows in rows_spec:
            if rows % Q2K_BLOCK:
                raise SystemExit(f"{table} split of {rows} classes straddles superblocks")
        lo = 0
        blocks = torch_taps // Q2K_BLOCK
        for rows in rows_spec:
            name = f"{table}.weight[{lo}:{lo + rows}]"
            chunk = payload[lo * blocks * Q2K_BYTES : (lo + rows) * blocks * Q2K_BYTES]
            _emit_q2k(layers, tensors, emit, name, chunk, rows, torch_taps, op="Head",
                      extra=f" classes={lo}..{lo + rows}")
            lo += rows

    d, ffn = spec["d_model"], spec["d_ff"]
    inner = spec["heads"] * spec["head_dim"]
    rows_spec = spec["head_rows"]
    head_q2k("decoder.embed_tokens", rows_spec, d)
    head_q2k("lm_head", rows_spec, d)

    for side, count in (("encoder", spec["encoder_layers"]), ("decoder", spec["decoder_layers"])):
        for index in range(count):
            at = f"{side}.block.{index}"
            attentions = ["SelfAttention"] + (["EncDecAttention"] if side == "decoder" else [])
            for layer, attention in enumerate(attentions):
                rms_norm(f"{at}.layer.{layer}.layer_norm")
                linear_q2k(f"{at}.layer.{layer}.{attention}.q", [inner, d])
                linear_q2k(f"{at}.layer.{layer}.{attention}.k", [inner, d])
                linear_q2k(f"{at}.layer.{layer}.{attention}.v", [inner, d])
                linear_q2k(f"{at}.layer.{layer}.{attention}.o", [d, inner])
            ff = len(attentions)
            rms_norm(f"{at}.layer.{ff}.layer_norm")
            # Fused `[gate | up]`, the layout `GatedActivate` halves: row-concatenation of
            # the two payloads (each row keeps its own blocks, so no block ever splits and
            # the concat is exact). Same fused table the int8 port builds by concatenating
            # fp32 rows, but with zero requantisation.
            wi_0 = _q2k_checked(get, f"{at}.layer.{ff}.DenseReluDense.wi_0", [ffn, d])
            wi_1 = _q2k_checked(get, f"{at}.layer.{ff}.DenseReluDense.wi_1", [ffn, d])
            _emit_q2k(layers, tensors, emit,
                      f"{at}.layer.{ff}.DenseReluDense.wi_01", wi_0 + wi_1, 2 * ffn, d)
            linear_q2k(f"{at}.layer.{ff}.DenseReluDense.wo", [d, ffn])
        rms_norm(f"{side}.final_layer_norm")

    for side in ("encoder", "decoder"):
        dtype, payload, gguf_shape = get(
            f"{side}.block.0.layer.0.SelfAttention.relative_attention_bias.weight"
        )
        if dtype != GGUF_F32:
            raise SystemExit(f"{side} relative table is GGUF type {dtype}, not F32")
        import numpy as _np

        # GGUF [16, 32] is torch [32, 16] transposed (reversed dims, same flat bytes).
        table = _np.frombuffer(payload, dtype=_np.float32).reshape(
            spec["heads"], spec["buckets"]
        ).T.copy()
        if list(table.shape) != [spec["buckets"], spec["heads"]]:
            raise SystemExit(f"{side} block-0 relative table is {list(table.shape)}")
        tensors.append(_np.ascontiguousarray(table, dtype=_np.float32))
        emit(
            "Relative",
            f"{side}.block.0.relative_attention_bias",
            f"Relative t={list(table.shape)} host-read fp16",
            1,
        )

    return layers, tensors


def collect_whisper(get, spec):
    """whisper-base's checkpoint as the ordered tensor table `nets::whisper` indexes.

    Order is the contract, and it is forward order per stack, encoder first:

        `conv1`, `conv2`, the encoder position table, each encoder layer, the encoder's norm
        the tied head, the decoder position table, each decoder layer, the decoder's norm

    # The two convolutions

    Both are rank-3 in the checkpoint and are emitted as the `1 x k` rank-4 kernel this runtime
    indexes, which is a reshape of the same bytes. Their geometry is in the digest key rather than in
    the Rust alone, because `conv2`'s **stride 2** is what turns 3000 mel frames into 1500 encoder
    positions and a wrong stride gives the right rank and the wrong length.

    # The absent key bias

    No `k_proj` in any of the 18 attentions has one. `Builder::conv_int8` reads a bias after the
    scale, so each gets zeros — exact, and recorded in the digest key as `b0=synthesised` so a
    checkpoint that grows one is not silently read as this table.

    # The tied head

    `model.decoder.embed_tokens.weight` is emitted **once** and has two roles, as SMaLL-100's does:
    `nets::whisper` gathers a row of it on the host to build a decode step's input, and binds the
    same tensor as the kernel of the logits projection. At 51,865 x 512 it is 26.6 MB of int8, well
    inside `maxStorageBufferRange`'s guaranteed 128 MiB, so unlike SMaLL-100 it needs no class split.

    # What is not folded

    * The attention query scale. Whisper scales the query by `head_dim ** -0.5` and `head_dim` is 64,
      which is the `1 / sqrt(head_dim)` `attn_scores` already applies. Folding it would apply it
      twice.
    * `sqrt(d_model)` on the embedding. `config.json` has `scale_embedding: false`, so there is none.
    * Both **position tables** are real tensors, and they go opposite ways. The **encoder's** is
      added to the conv stem's output, which is a device tensor, so it is transposed to
      `[width, 1, positions]` and emitted as a `Kind::Constant`. The **decoder's** is added to a
      gathered embedding row on the *host*, in f32, exactly as `nets::small100::embed_positions`
      does, so it stays `[positions, width]` and never reaches a shader.
    """
    layers = []
    tensors = []
    fidelity = Fidelity()

    def emit(op, name, key, added):
        layers.append(Layer(len(layers), op, name, key, len(tensors) - added, added))

    def convolution(name, stride):
        """A rank-3 `[out, in, k]` kernel as the `1 x k` int8 one, its scale and its bias."""
        weight = get(f"{name}.weight")
        bias = get(f"{name}.bias")
        outputs, inputs, width = weight.shape
        lifted = weight.reshape(outputs, inputs, 1, width)
        kernel, scale = fidelity.quantise(name, lifted)
        tensors.extend([kernel, scale, bias])
        # ONNX orders pads as all the begins then all the ends, so a 1-D `[1, 1]` is `[0, 1, 0, 1]`.
        # `same` padding on a kernel of 3, which is what holds the length at stride 1 and halves it
        # at stride 2.
        emit(
            "Conv8",
            name,
            f"Conv8 w={list(kernel.shape)} scale={[outputs]} b={list(bias.shape)} "
            f"k={[1, width]} s={[1, stride]} p={[0, 1, 0, 1]} zp=0 dtype=int8",
            3,
        )

    def linear(name, biased=True):
        """An int8 kernel as `[out, in, 1, 1]`, its per-output-channel scale, and its bias."""
        weight = get(f"{name}.weight")
        outputs, inputs = weight.shape
        kernel, scale = fidelity.quantise(name, weight.reshape(outputs, inputs, 1, 1))
        if biased:
            bias = get(f"{name}.bias")
            suffix = ""
        else:
            bias = np.zeros(outputs, dtype=np.float32)
            suffix = " b0=synthesised"
        tensors.extend([kernel, scale, bias])
        emit(
            "Linear8",
            name,
            f"Linear8 w={list(kernel.shape)} scale={[outputs]} b={list(bias.shape)} "
            f"zp=0 dtype=int8{suffix}",
            3,
        )

    def layer_norm(name):
        gamma = get(f"{name}.weight")
        beta = get(f"{name}.bias")
        tensors.extend([gamma, beta])
        emit("LayerNorm", name, f"LayerNorm g={list(gamma.shape)} b={list(beta.shape)}", 2)

    def table(name):
        values = get(f"{name}.weight")
        tensors.append(values)
        emit("Table", name, f"Table t={list(values.shape)}", 1)

    def constant(name):
        """A `[positions, width]` table as the `[width, 1, positions]` a `Kind::Constant` wants."""
        values = get(f"{name}.weight")
        positions, width = values.shape
        transposed = values.T.reshape(width, 1, positions).copy()
        tensors.append(transposed)
        emit("Constant", name, f"Constant t={list(transposed.shape)}", 1)

    def stack(side, count):
        for index in range(count):
            at = f"model.{side}.layers.{index}"
            # Pre-norm, as `WhisperEncoderLayer` and `WhisperDecoderLayer` are: the norm comes before
            # the sublayer it feeds and the residual skips both.
            attentions = ["self_attn"] + (["encoder_attn"] if side == "decoder" else [])
            for attention in attentions:
                layer_norm(f"{at}.{attention}_layer_norm")
                for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                    linear(f"{at}.{attention}.{projection}", biased=projection != "k_proj")
            layer_norm(f"{at}.final_layer_norm")
            linear(f"{at}.fc1")
            linear(f"{at}.fc2")
        layer_norm(f"model.{side}.layer_norm")

    # --- the encoder ---
    convolution("model.encoder.conv1", stride=1)
    # Stride 2: 3000 mel frames become the 1500 positions the decoder cross-attends over.
    convolution("model.encoder.conv2", stride=2)
    # Transposed and device-resident: it is added to the conv stem's output. The decoder's table
    # below is not, because the host adds that one.
    constant("model.encoder.embed_positions")
    stack("encoder", spec["encoder_layers"])

    # --- the decoder ---
    head = "model.decoder.embed_tokens"
    rows = get(f"{head}.weight")
    classes, width = rows.shape
    kernel, scale = fidelity.quantise(head, rows.reshape(classes, width, 1, 1))
    # `Builder::conv_int8` reads a bias after the scale, and a tied head has none.
    tensors.extend([kernel, scale, np.zeros(classes, dtype=np.float32)])
    emit(
        "Head",
        head,
        f"Head w={list(kernel.shape)} scale={[classes]} b={[classes]} tied=embed_tokens "
        "zp=0 dtype=int8 b0=synthesised",
        3,
    )
    table("model.decoder.embed_positions")
    stack("decoder", spec["decoder_layers"])

    fidelity.report()
    return layers, tensors


def nllb_inventory(spec):
    """`{name: shape}` NLLB-200-distilled-600M's checkpoint must hold exactly.

    Derived rather than transcribed: a list of 513 names would be checked against itself.

    The checkpoint holds the tied embedding **four times** (`model.shared`,
    `model.encoder.embed_tokens`, `model.decoder.embed_tokens`, `lm_head` — verified
    byte-identical). Only `model.shared.weight` is wanted; the other three are stated by
    *not* appearing, so a checkpoint that unties them fails loudly instead of being read
    as this table with three copies ignored.

    Unlike whisper, **every** projection (all 96 attentions' q/k/v/out) carries a bias —
    `k_proj.bias` is present. And unlike whisper there is no convolution stem and no
    learned position table: positions are fairseq sinusoids computed by the Rust, exactly
    as for small100.
    """
    d, ffn = spec["d_model"], spec["ffn"]
    want = {"model.shared.weight": [spec["vocab"], d]}
    for side, count in (("encoder", spec["encoder_layers"]), ("decoder", spec["decoder_layers"])):
        want[f"model.{side}.layer_norm.weight"] = [d]
        want[f"model.{side}.layer_norm.bias"] = [d]
        for index in range(count):
            at = f"model.{side}.layers.{index}"
            attentions = ["self_attn"] + (["encoder_attn"] if side == "decoder" else [])
            for attention in attentions:
                want[f"{at}.{attention}_layer_norm.weight"] = [d]
                want[f"{at}.{attention}_layer_norm.bias"] = [d]
                for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                    want[f"{at}.{attention}.{projection}.weight"] = [d, d]
                    want[f"{at}.{attention}.{projection}.bias"] = [d]
            want[f"{at}.final_layer_norm.weight"] = [d]
            want[f"{at}.final_layer_norm.bias"] = [d]
            want[f"{at}.fc1.weight"] = [ffn, d]
            want[f"{at}.fc1.bias"] = [ffn]
            want[f"{at}.fc2.weight"] = [d, ffn]
            want[f"{at}.fc2.bias"] = [d]
    return want


def collect_gemma4(model, spec):
    """Gemma 4's decoder export as the ordered tensor table `nets::gemma4` indexes.

    THE TENSOR ORDER (the contract with the Rust net - positional indexing, no names):

        1. the logits head, as `head_splits` disjoint class ranges of `vocab / head_splits`.
           Each range emits 3 tensors: int4 kernel `[rows, 1536, 1, 1]`, fp16 per-block scale
           `[rows, blocks]`, synthesised zero bias `[rows]`. Tensors 0..11.
        2. the shared per-layer projection (3 tensors) and its norm (1). Tensors 12..15.
        3. the final norm (1). Tensor 16.
        4. each layer 0..34, in the order `declare_layer` states. A layer that owns a cache
           contributes 32 tensors; one that does not contributes 25.

    # Two things the converter does that the export does not

    * **Transposes** every projection. The export holds `MatMul` weights as `[in, out]`; every
      convolution in this runtime takes an ONNX `Conv` kernel, `[out, in, kh, kw]`.
    * **Synthesises a zero bias** for each. Gemma 4 has none - `attention_bias` is false - but a
      convolution here always takes one, and a zero bias is `out * 2` bytes against a kernel of
      `out * 1536`.

    # And two it deliberately does not

    * `layer_scalar` is emitted **unchanged**, not folded. Folding it into
      `per_layer_projection`'s weights would be exact and would save an op, but the per-layer
      branch is the one part of the forward pass with no reference run behind it; folding a
      constant into a path whose shape is still unconfirmed makes the eventual comparison harder,
      not easier. `nets::gemma4` names it as a host tensor meanwhile.
    * The **scale folded into `q_norm`** stays folded. The export applies no `1/sqrt(head_dim)`
      between the query projection and the score matmul, and `nets::gemma4` passes a scale of one
      to match - see `Q_NORM_CARRIES_SCALE`. Dividing it out here would need the Rust to stop
      doing that in the same commit.
    """
    graph = model.graph
    inits = {i.name: i for i in graph.initializer}

    def get(name):
        found = inits.get(name)
        if found is None:
            raise SystemExit(f"the export has no tensor named {name}")
        return numpy_helper.to_array(found).astype(np.float32)

    layers = []
    tensors = []
    fidelity = Fidelity()

    def emit(op, name, key, added):
        layers.append(Layer(len(layers), op, name, key, len(tensors) - added, added))

    def projection(name, weight=None):
        """A `[in, out]` MatMul as an int4 `[out, in, 1, 1]` kernel, its scale, and a zero bias."""
        matrix = get(f"{name}.weight") if weight is None else weight
        inputs, outputs = matrix.shape
        kernel = np.ascontiguousarray(matrix.T).reshape(outputs, inputs, 1, 1)
        codes, scale = fidelity.quantise4(name, kernel)
        bias = np.zeros(outputs, dtype=np.float32)
        tensors.extend([codes, scale, bias])
        emit(
            "Linear4",
            name,
            f"Linear4 w={[outputs, inputs, 1, 1]} scale={list(scale.shape)} "
            f"b={[outputs]} zp=0 dtype=int4 block={I4_BLOCK}",
            3,
        )

    def projection8(name):
        """The same at **eight** bits, whose scale is rank 1.

        For the two per-layer-input projections only. They are the smallest weights in a layer
        and the only ones that miss `MIN_INT4_COSINE`: `per_layer_input_gate` on layer 21
        reconstructs at 0.9870 at four bits, while every large projection clears the floor.
        Quantising them further would save about a thousandth of the file for the worst error in
        it. `nets::gemma4::projection8` states the same split on the reader's side.
        """
        matrix = get(f"{name}.weight")
        inputs, outputs = matrix.shape
        kernel = np.ascontiguousarray(matrix.T).reshape(outputs, inputs, 1, 1)
        codes, scale = fidelity.quantise(name, kernel)
        bias = np.zeros(outputs, dtype=np.float32)
        tensors.extend([codes, scale, bias])
        emit(
            "Linear8",
            name,
            f"Linear8 w={[outputs, inputs, 1, 1]} scale={[outputs]} b={[outputs]} "
            "zp=0 dtype=int8",
            3,
        )

    def vector(name, expect):
        values = get(name)
        if list(values.shape) != [expect]:
            raise SystemExit(f"{name} is {list(values.shape)}, not [{expect}]")
        tensors.append(values)
        emit("RmsNorm", name, f"RmsNorm g={[expect]}", 1)

    d = spec["d_model"]
    per_layer, layer_count = spec["per_layer"], spec["layers"]
    splits, vocab = spec["head_splits"], spec["vocab"]

    # 1. The logits head, split so no one tensor exceeds a descriptor's guaranteed reach.
    head = get("lm_head.MatMul.weight")
    rows = vocab // splits
    if rows * splits != vocab:
        raise SystemExit(f"{vocab} classes do not split into {splits}")
    for part in range(splits):
        lo = part * rows
        projection(f"lm_head[{lo}:{lo + rows}]", weight=head[:, lo : lo + rows])

    # 2. The shared per-layer projection and its norm.
    projection("model.per_layer_projection.MatMul")
    vector("model.per_layer_projection_norm.weight", per_layer)

    # 3. The final norm. The export files it under a phantom 36th layer rather than beside the
    # other shared tensors, so it is read by that name and emitted here, where the net wants it.
    vector("model.layers.35.final_norm_layernorm.weight", d)

    # 4. The two rotary tables, truncated and interleaved.
    #
    # The export ships `cos_cache_*` and `sin_cache_*` at 131072 rows, which is
    # `max_position_embeddings` and two orders of magnitude past any context this runtime offers:
    # the global pair alone would be 134 MB. They are cut to `max_context` and interleaved into
    # one table per layer type, so a position's angles are one contiguous row in the layout
    # `Builder::rotary` reads - cosines first, then sines.
    context = spec["max_context"]
    for kind, dim in (("local", spec["head_dim"]), ("global", spec["global_head_dim"])):
        cos = get(f"cos_cache_{kind}")[:context]
        sin = get(f"sin_cache_{kind}")[:context]
        if cos.shape != (context, dim // 2) or sin.shape != cos.shape:
            raise SystemExit(
                f"the {kind} rotary tables are {cos.shape} and {sin.shape}, not "
                f"({context}, {dim // 2}) - half a head is the rotary width"
            )
        table = np.concatenate([cos, sin], axis=1).astype(np.float32)
        tensors.append(table)
        emit("Rotary", f"{kind}_rotary_table", f"Rotary t={list(table.shape)}", 1)

    # 4. The layers.
    for index in range(layer_count):
        at = f"model.layers.{index}"
        full = index % 5 == 4
        owns = index < spec["owns_cache_layers"]
        dim = spec["global_head_dim"] if full else spec["head_dim"]
        vector(f"{at}.input_layernorm.weight", d)
        vector(f"{at}.attn.q_norm.layernorm.weight", dim)
        projection(f"{at}.attn.q_proj.MatMul")
        if owns:
            vector(f"{at}.attn.k_norm.layernorm.weight", dim)
            projection(f"{at}.attn.k_proj.MatMul")
            projection(f"{at}.attn.v_proj.MatMul")
            # `v_norm`'s gamma is all ones and the export names it with slashes rather than dots,
            # so it is read by its node path. Emitted like any other norm rather than synthesised
            # here, so the file stays self-describing and a future export that trains it needs no
            # converter change.
            vector(f"/model/layers.{index}/attn/v_norm/ones_weight", dim)
        projection(f"{at}.attn.o_proj.MatMul")
        vector(f"{at}.post_attention_layernorm.weight", d)
        vector(f"{at}.pre_feedforward_layernorm.weight", d)
        projection(f"{at}.mlp.gate_up_proj.MatMul")
        projection(f"{at}.mlp.down_proj.MatMul")
        vector(f"{at}.post_feedforward_layernorm.weight", d)
        projection8(f"{at}.per_layer.per_layer_input_gate.MatMul")
        projection8(f"{at}.per_layer.per_layer_projection.MatMul")
        vector(f"{at}.post_per_layer_input_norm.weight", d)
        scalar = get(f"{at}.layer_scalar")
        tensors.append(scalar.reshape(1))
        emit("Scalar", f"{at}.layer_scalar", "Scalar g=[1]", 1)

    fidelity.report(MIN_INT4_COSINE)
    return layers, tensors


def collect_gemma4_vision(model, spec):
    """Gemma 4's vision tower as the ordered table `nets::gemma4_vision` indexes.

    THE TENSOR ORDER (the contract with the Rust net - positional indexing, no names):

        0..1    the patch projection, `[768, 768]` in the export, as an fp16 kernel and bias
        2       the trailing norm
        3..4    the output projection to the decoder's 1536, likewise fp16
        5..10   the two `[10240, 768]` position tables, as int4 triples
        then each layer 0..15, in the order `declare_layer` reads:
            pre-attention norm, clip,
            q/k/v (three triples), clip x3,
            q/k/v norms,
            clip, o_proj, clip,
            post-attention norm, pre-feed-forward norm, clip,
            gate, up, clip x3, down, clip,
            post-feed-forward norm

    # The export's initializers are anonymised, so this walks the graph

    Unlike the decoder, whose tensors are named `model.layers.7.attn.q_proj.MatMul.weight`, the
    vision export calls them `_to_copy_104`. Matching by name is therefore impossible and the
    weights are taken from the **nodes** instead.

    The anchor is that the nodes themselves are numbered in topological order:
    `node_linear`, `node_linear_1` .. `node_linear_113`, and
    `_fused_rms_norm/node_mean` .. `node_mean_112`. Seven linears and seven norms a layer, over
    sixteen layers, plus a patch projection and an output projection at the ends - which is
    checked below rather than assumed, because a renumbering upstream would otherwise shift every
    tensor silently.

    Clips are taken by graph position between one layer's first norm and the next's: eleven a
    layer, and one before the first layer for the patch projection. Also checked.
    """
    g = model.graph
    inits = {i.name: i for i in g.initializer}
    position = {n.name: at for at, n in enumerate(g.node)}

    def numbered(node, prefix):
        """The index in `prefix`, `prefix_1`, `prefix_2`, ... or None.

        The q4f16 exports append `_Quant` to every quantised linear
        (`node_linear_7_Quant`); the suffix is stripped before matching so both
        exports walk the same numbering.
        """
        name = (node.name or "").replace("_fused_rms_norm/node_mean", "node_mean")
        if name.endswith("_Quant"):
            name = name[: -len("_Quant")]
        if not name.startswith(prefix):
            return None
        tail = name[len(prefix):]
        if tail == "":
            return 0
        return int(tail[1:]) if tail.startswith("_") and tail[1:].isdigit() else None

    def weight(index):
        """Linear `index` as fp32 `[in, out]`, from either export.

        The fp16 walk stores protos in `linears`; the q4f16 walk stores `None`
        there and the dequantised matrix in `decoded`. Both flow into the same
        `dense`/`projection` calls below, which is what keeps the file format
        identical across sources.
        """
        if index in decoded:
            return decoded[index].astype(np.float32)
        return array(linears[index])

    linears, norms, clips = {}, {}, []
    decoded = {}
    # A `Clip`'s bounds can be an initializer or a folded `Constant` node, and the vision export
    # uses both - the patch projection's is a Constant while every layer's is an initializer.
    constants = {}
    for node in g.node:
        if node.op_type == "Constant":
            for attribute in node.attribute:
                if attribute.name == "value":
                    constants[node.output[0]] = attribute.t

    def scalar(name):
        held = inits.get(name) or constants.get(name)
        if held is None:
            return None
        flat = numpy_helper.to_array(held).reshape(-1)
        return float(flat[0]) if flat.size else None

    for node in g.node:
        if node.op_type in ("MatMul", "Gemm"):
            index = numbered(node, "node_linear")
            if index is not None:
                held = [inits[i] for i in node.input if i in inits]
                if held:
                    linears[index] = held[0]
        elif node.op_type == "MatMulNBits":
            # The q4f16 exports: every linear quantised in place. Decoded to
            # fp32 [K, N] here so the shared dense/projection path - and its
            # fidelity gate - treats both exports identically. Verified against
            # onnxruntime's own MatMulNBits to 3.7e-3 (analysis/prove_sign/).
            index = numbered(node, "node_linear")
            if index is not None:
                linears[index] = None
                decoded[index] = dequant_matmul_nbits(node, inits)
        elif node.op_type == "SimplifiedLayerNormalization":
            index = numbered(node, "node_mean")
            if index is not None:
                held = [inits[i] for i in node.input if i in inits]
                if held:
                    norms[index] = held[0]
        elif node.op_type == "Clip":
            # `Clip(x, min, max)` with either bound optional. The patch projection's is
            # one-sided; every layer's has both. An absent bound becomes a value fp16 cannot
            # reach, so the pair is always two numbers and the shader needs no special case.
            low = scalar(node.input[1]) if len(node.input) > 1 else None
            high = scalar(node.input[2]) if len(node.input) > 2 else None
            if low is None and high is None:
                continue
            clips.append((
                position[node.name],
                [-FP16_LIMIT if low is None else low, FP16_LIMIT if high is None else high],
            ))

    layers_count = spec["layers"]
    want_linears = layers_count * 7 + 2
    want_norms = layers_count * 7 + 1
    if len(linears) != want_linears or len(norms) != want_norms:
        raise SystemExit(
            f"the vision export has {len(linears)} linears and {len(norms)} norms, not "
            f"{want_linears} and {want_norms} - the node numbering this relies on has changed"
        )

    clips.sort()
    starts = [position_of_norm(g, position, 7 * i) for i in range(layers_count)]
    starts.append(position_of_norm(g, position, layers_count * 7))
    head_clips = [c for c in clips if c[0] < starts[0]]
    if len(head_clips) != 1:
        raise SystemExit(f"{len(head_clips)} clips before the first layer, not 1")
    per_layer_clips = []
    for i in range(layers_count):
        got = [c for c in clips if starts[i] < c[0] < starts[i + 1]]
        if len(got) != 11:
            raise SystemExit(f"vision layer {i} has {len(got)} clips, not 11")
        per_layer_clips.append([bounds for _, bounds in got])

    layers, tensors = [], []
    fidelity = Fidelity()

    def emit(op, name, key, added):
        layers.append(Layer(len(layers), op, name, key, len(tensors) - added, added))

    def array(tensor):
        # A raw numpy array passes through: the q4f16 walk decodes MatMulNBits
        # to fp32 itself (see `weight`), so both exports meet here as matrices.
        if isinstance(tensor, np.ndarray):
            return tensor.astype(np.float32)
        return numpy_helper.to_array(tensor).astype(np.float32)

    def weight(index):
        """Linear `index` as fp32 `[in, out]`, from either export.

        The fp16 walk stores protos in `linears`; the q4f16 walk stores `None`
        there and the dequantised matrix in `decoded`. Both flow into the same
        `dense`/`projection` calls below, which is what keeps the file format
        identical across sources.
        """
        if index in decoded:
            return decoded[index].astype(np.float32)
        return array(linears[index])

    def dense(name, tensor, transposed=False):
        """A linear's weight as an **fp16** `[out, in, 1, 1]` kernel and a zero bias.

        `transposed` says the weight is **already** `[out, in]`, which a `Gemm` with `transB` is
        and a `MatMul` is not. The tower's output projection is the one `Gemm` here, so the two
        conventions meet exactly once - and transposing it anyway would be a `[768, 1536]` kernel
        that is the right size and the wrong matrix.

        # Why these two are not quantised

        Sixteen layers of int4 turn a per-layer cosine of 0.9999 into 0.946 at the output, and a
        disproportionate share of that enters at the patch projection - 0.9952 before layer 0 has
        run - and leaves at the output projection, which takes the pooled state from 0.9747 to
        0.9481. Neither has anything downstream to average it away. Together they are 1.7 M
        parameters against the tower's 300 M, so fp16 here costs about 2.7 MB.

        See `nets::gemma4_vision::PATCH_PROJECTION`.
        """
        matrix = array(tensor)
        if transposed:
            outputs, inputs = matrix.shape
            kernel = np.ascontiguousarray(matrix).reshape(outputs, inputs, 1, 1)
        else:
            inputs, outputs = matrix.shape
            kernel = np.ascontiguousarray(matrix.T).reshape(outputs, inputs, 1, 1)
        tensors.extend([kernel, np.zeros(outputs, dtype=np.float32)])
        emit("Linear", name, f"Linear w={[outputs, inputs, 1, 1]} dtype=fp16", 2)

    def projection(name, tensor, transposed=False):
        """A linear's weight as an int4 `[out, in, 1, 1]` kernel, its scale, and a zero bias.

        Every projection inside a layer. The two at the ends go through `dense` instead.
        """
        matrix = array(tensor)
        if transposed:
            outputs, inputs = matrix.shape
            kernel = np.ascontiguousarray(matrix).reshape(outputs, inputs, 1, 1)
        else:
            inputs, outputs = matrix.shape
            kernel = np.ascontiguousarray(matrix.T).reshape(outputs, inputs, 1, 1)
        codes, scale = fidelity.quantise4(name, kernel)
        tensors.extend([codes, scale, np.zeros(outputs, dtype=np.float32)])
        emit("Linear4", name, f"Linear4 w={[outputs, inputs, 1, 1]} dtype=int4", 3)

    def table(name, tensor):
        """A `[rows, width]` gather table, **not** transposed.

        Unlike a projection, nothing multiplies by this - `Reader::int4_row` reads row `position`
        - so transposing it would make every gather stride across the file instead of reading one
        contiguous span. The same reason `collect_gemma4_embed` leaves the embedding alone.
        """
        values = array(tensor)
        rows, width = values.shape
        codes, scale = fidelity.quantise4(name, values.reshape(rows, width, 1, 1))
        tensors.extend([codes, scale, np.zeros(rows, dtype=np.float32)])
        emit("Table4", name, f"Table4 w={[rows, width, 1, 1]} dtype=int4", 3)

    def vector(name, tensor, expect):
        values = array(tensor)
        if list(values.shape) != [expect]:
            raise SystemExit(f"{name} is {list(values.shape)}, not [{expect}]")
        tensors.append(values)
        emit("RmsNorm", name, f"RmsNorm g={[expect]}", 1)

    def clip(name, bounds):
        low, high = bounds
        if not low < high:
            raise SystemExit(f"{name} clips to [{low}, {high}], which is empty")
        tensors.append(np.array([low, high], dtype=np.float32))
        emit("Clip", name, f"Clip [{low}, {high}]", 1)

    d_model = spec["d_model"]
    heads, head_dim, ffn = spec["heads"], spec["head_dim"], spec["ffn"]

    dense("patch_projection", weight(0))
    vector("final_norm", norms[layers_count * 7], d_model)
    dense("output_projection", weight(layers_count * 7 + 1), transposed=True)
    # The two learned position tables, `[10240, 768]` each, which the host gathers a row at a
    # time. Emitted in the same `[out, in, 1, 1]` triple form as everything else so that
    # `Reader::int4_row` can read them, even though nothing binds them to a shader.
    #
    # The first is gathered by position component 0 and the second by component 1, and the
    # reference preprocessor builds those with `indexing="xy"` - so component 0 is the COLUMN.
    # Naming them the other way round would transpose every image without changing a shape.
    gathers = [
        inits[i]
        for node in g.node
        if node.op_type == "Gather" and (node.name or "").startswith("node_embedding")
        for i in node.input
        if i in inits and list(inits[i].dims) == [spec["positions"], d_model]
    ]
    # The q4f16 exports quantise the tables in place (`GatherBlockQuantized`,
    # same block packing as MatMulNBits): decode both to fp32 here so the
    # shared `table` path - and its fidelity gate - treats both exports alike.
    if not gathers:
        for node in g.node:
            if node.op_type != "GatherBlockQuantized" or not (
                node.name or ""
            ).startswith("node_embedding"):
                continue
            attrs = {a.name: a for a in node.attribute}
            bits = attrs["bits"].i if "bits" in attrs else 4
            if bits != 4:
                raise SystemExit(node.name + ": block-quantised gather with " +
                                 str(bits) + " bits, not 4")
            gathers.append(dequant_block_quantized(
                inits[node.input[0]], inits[node.input[2]], inits[node.input[3]]))
    if len(gathers) != 2:
        raise SystemExit(f"{len(gathers)} position tables, not 2 - the head has changed")
    table("column_positions", gathers[0])
    table("row_positions", gathers[1])

    for i in range(layers_count):
        base = 1 + 7 * i
        norm_base = 7 * i
        marks = per_layer_clips[i]
        at = f"vision.layers.{i}"
        vector(f"{at}.pre_attention_norm", norms[norm_base], d_model)
        clip(f"{at}.clip_in", marks[0])
        projection(f"{at}.q_proj", weight(base))
        projection(f"{at}.k_proj", weight(base + 1))
        projection(f"{at}.v_proj", weight(base + 2))
        clip(f"{at}.clip_q", marks[1])
        clip(f"{at}.clip_k", marks[2])
        clip(f"{at}.clip_v", marks[3])
        vector(f"{at}.q_norm", norms[norm_base + 1], head_dim)
        vector(f"{at}.k_norm", norms[norm_base + 2], head_dim)
        vector(f"{at}.v_norm", norms[norm_base + 3], head_dim)
        clip(f"{at}.clip_mixed", marks[4])
        projection(f"{at}.o_proj", weight(base + 3))
        clip(f"{at}.clip_o", marks[5])
        vector(f"{at}.post_attention_norm", norms[norm_base + 4], d_model)
        vector(f"{at}.pre_feedforward_norm", norms[norm_base + 5], d_model)
        clip(f"{at}.clip_ff_in", marks[6])
        projection(f"{at}.gate_proj", weight(base + 4))
        projection(f"{at}.up_proj", weight(base + 5))
        clip(f"{at}.clip_gate", marks[7])
        clip(f"{at}.clip_up", marks[8])
        clip(f"{at}.clip_gated", marks[9])
        projection(f"{at}.down_proj", weight(base + 6))
        clip(f"{at}.clip_down", marks[10])
        vector(f"{at}.post_feedforward_norm", norms[norm_base + 6], d_model)
        del heads, ffn
        heads, ffn = spec["heads"], spec["ffn"]

    fidelity.report(MIN_INT4_COSINE)
    return layers, tensors


def position_of_norm(graph, position, index):
    """Where `node_mean_{index}` sits in topological order."""
    want = "node_mean" if index == 0 else f"node_mean_{index}"
    for node in graph.node:
        name = (node.name or "").replace("_fused_rms_norm/node_mean", "node_mean")
        if name == want:
            return position[node.name]
    raise SystemExit(f"the export has no {want}")


def collect_gemma4_audio(model, spec):
    """Gemma 4's audio tower as the ordered table `nets::gemma4_audio` indexes.

    THE TENSOR ORDER (the contract with the Rust net - positional indexing, no names):

        0..1    subsampling conv 0, `[128, 1, 3, 3]` fp16 with its spatial axes SWAPPED, and a
                synthesised zero bias
        2..3    its layer norm: the export's gain and a synthesised zero beta
        4..5    subsampling conv 1, `[32, 128, 3, 3]`, spatial axes swapped, zero bias
        6..7    its layer norm, zero beta
        8..9    the input projection, `[1024, 1024]` fp16, ROWS PERMUTED - see `input_rows`
        10..11  the output projection, `[1536, 1024]` fp16, with its REAL bias
        12      the embedder's pre-projection norm, `[1536]`
        13..14  the embedder's projection, `[1536, 1536]` fp16, zero bias
        then each layer 0..11, in the order `declare_layer` reads:
            ffw1 pre-norm, clip, up (triple), clip, clip, down (triple), clip, ffw1 post-norm,
            pre-attention norm, clip, q, k, v (three triples), clip x3,
            the folded per-channel query scale, the `[8, 128, 13]` relative table,
            clip, post (triple), clip, post-attention norm,
            conv pre-norm, clip, gate (triple), clip,
            the depthwise kernel and its zero bias, conv norm, clip, exit (triple), clip,
            ffw2 pre-norm, clip, up, clip, clip, down, clip, ffw2 post-norm,
            the terminal norm

    # The export's initializers are anonymised, so this walks the graph

    Every weight is called `val_2567` or `permute_9` or `_to_copy_16`, so matching by name is
    impossible for most of them. HOW EACH FAMILY IS FOUND, weakest tier last:

        depthwise convs, SSCP convs   ATTRIBUTE: group == 1024 / group == 1, plus asserted
                                      pads and strides. Export-independent.
        relative tables               SHAPE [8, 128, 13] and consumed by a MatMul.
        folded query scales           SHAPE [128] and consumed by a Mul. The first SSCP layer
                                      norm's gain is also [128] but is consumed by a
                                      LayerNormalization, so the op type separates them.
        SSCP norm gains               op type LayerNormalization - the only two in the tower.
        linears, RMS norms, clips     NODE NUMBERING, the fragile tier.

    NAMES ARE THE LAST RESORT AND THE LAST TIER IS GUARDED ACCORDINGLY. Neither namespace is
    stable across exports: the fp16 cast renumbers every `node_<Op>_<N>` and renames 44% of the
    initializers (618 in each export, only 345 in common), so a name-based collector calibrated
    on `audio_encoder.onnx` finds different tensors in `audio_encoder_fp16.onnx`. Counts alone
    do not catch a reordering that preserves the count, so the numbered families additionally
    assert the SHAPE of all ten linear slots in all twelve layers, and that every layer's
    eighteen clips are consumed by the same sequence of op types as layer 0's. A shift that
    survived all of that would have to preserve every shape at every slot, which the layer's
    [1024, 4096] / [4096, 1024] / [1024, 2048] mix does not allow.

    # The hole in the linear numbering

    `node_linear` runs 0..134 but **thirteen indices are absent**. Twelve of them are
    `relative_k_proj`, one per layer, which the exporter constant-folded into the `[8, 128, 13]`
    table this collects separately: the sinusoid it projects depends on nothing but dtype, so
    there is nothing left to compute at runtime and no `MatMul` is emitted. The thirteenth is
    133, because `output_proj` is the one linear with a bias and the exporter split it - the
    `MatMul` is named something else entirely and `node_linear_133` is the `Add`.

    Index 0 is PRESENT and is the input projection. It is the bare `node_linear` with no
    numeric suffix, which `numbered` maps to 0; a reader that only matches `node_linear_<N>`
    misses it, concludes the range starts at 1, and is then off by one for the whole graph.

    So the stride is **11 with a hole at +5**, not 10. A naive `1 + 10 * i` is off by one from
    layer 0 onward and lands on plausible-looking shapes all the way down; both the hole and the
    count are asserted below rather than assumed.
    """
    g = model.graph
    inits = {i.name: i for i in g.initializer}
    position = {n.name: at for at, n in enumerate(g.node)}
    produced = {out: n for n in g.node for out in n.output}

    def numbered(node, prefix):
        """The index in `prefix`, `prefix_1`, `prefix_2`, ... or None.

        Strips the q4f16 `_Quant` suffix, as the vision walk does.
        """
        name = (node.name or "").replace("_fused_rms_norm/node_mean", "node_mean")
        if name.endswith("_Quant"):
            name = name[: -len("_Quant")]
        if not name.startswith(prefix):
            return None
        tail = name[len(prefix):]
        if tail == "":
            return 0
        return int(tail[1:]) if tail.startswith("_") and tail[1:].isdigit() else None

    constants = {}
    for node in g.node:
        if node.op_type == "Constant":
            for attribute in node.attribute:
                if attribute.name == "value":
                    constants[node.output[0]] = attribute.t

    def held(node, dims=None):
        """The initializers or folded constants `node` reads, optionally of one shape."""
        out = []
        for name in node.input:
            tensor = inits.get(name) or constants.get(name)
            if tensor is not None and (dims is None or list(tensor.dims) == list(dims)):
                out.append(tensor)
        return out

    def scalar(name):
        tensor = inits.get(name) or constants.get(name)
        if tensor is None:
            return None
        flat = numpy_helper.to_array(tensor).reshape(-1)
        return float(flat[0]) if flat.size else None

    layers_count = spec["layers"]
    d_model, heads, head_dim = spec["d_model"], spec["heads"], spec["head_dim"]
    ffn, out_dim = spec["ffn"], spec["out_dim"]
    offsets, mel_sub = spec["rel_offsets"], spec["mel_subsampled"]
    channels = spec["sscp_channels"]

    linears, norms, clips = {}, {}, []
    relative, per_dim, depthwise, subsample, gains = [], [], [], [], []
    reads = {}
    for node in g.node:
        for i in node.input:
            reads.setdefault(i, []).append(node.op_type)
    clip_consumers = {}
    for node in g.node:
        if node.op_type in ("MatMul", "Gemm"):
            index = numbered(node, "node_linear")
            if index is not None:
                for tensor in held(node)[:1]:
                    linears[index] = tensor
            for tensor in held(node, [heads, head_dim, offsets]):
                relative.append((position[node.name], tensor))
        elif node.op_type == "MatMulNBits":
            # The q4f16 exports: decode via the shared block helper (verified
            # against onnxruntime to 3.7e-3) and store the fp32 matrix the same
            # way the fp16 walk stores its proto.
            index = numbered(node, "node_linear")
            if index is not None:
                linears[index] = numpy_helper.from_array(
                    dequant_matmul_nbits(node, inits).astype(np.float32),
                    name=node.name + "_dequant")
        elif node.op_type == "SimplifiedLayerNormalization":
            index = numbered(node, "node_mean")
            if index is not None:
                for tensor in held(node)[:1]:
                    norms[index] = tensor
        elif node.op_type == "Mul":
            # The folded `softplus(per_dim_scale)`. A `[128]` initializer, which the first
            # subsampling layer norm's gain also is - but that one is read by a
            # `LayerNormalization`, never by a `Mul`, so the op type separates them.
            for tensor in held(node, [head_dim]):
                per_dim.append((position[node.name], tensor))
        elif node.op_type == "LayerNormalization":
            gains.append((position[node.name], held(node)[0]))
        elif node.op_type == "Conv":
            weights = held(node)
            if not weights:
                raise SystemExit(f"{node.name} is a Conv with no kernel")
            # ATTRIBUTE ANCHORS, not names and not weight rank. `group` separates the two conv
            # families exactly and survives a re-export, where node names do not: the fp16 cast
            # renumbers every node_<Op>_<N> and renames 44% of the initializers.
            attrs = {a.name: list(a.ints) if a.ints else a.i for a in node.attribute}
            group, pads = attrs.get("group", 1), attrs.get("pads", [])
            strides = attrs.get("strides", [])
            if group == d_model:
                # CAUSAL: four taps left, none right. Symmetric padding here would not fail a
                # shape check - it would shift the whole encoder two positions against the
                # reference, in all twelve layers, cumulatively. Asserted rather than trusted.
                if pads != [4, 0] or strides != [1]:
                    raise SystemExit(
                        f"{node.name} is the depthwise conv with pads={pads} strides={strides}, "
                        "not the causal pads=[4, 0] strides=[1] nets::gemma4_audio builds. "
                        "Symmetric padding shifts every frame with no shape error."
                    )
                depthwise.append((position[node.name], weights[0]))
            elif group == 1:
                if pads != [1, 1, 1, 1] or strides != [2, 2]:
                    raise SystemExit(
                        f"{node.name} is a subsampling conv with pads={pads} strides={strides}, "
                        "not the symmetric pads=[1,1,1,1] strides=[2,2] the transposed layout "
                        "in nets::gemma4_audio::input_shape relies on being square."
                    )
                subsample.append((position[node.name], weights[0]))
            else:
                raise SystemExit(f"{node.name} is a Conv with group={group}, neither 1 nor {d_model}")
        elif node.op_type == "Clip":
            low = scalar(node.input[1]) if len(node.input) > 1 else None
            high = scalar(node.input[2]) if len(node.input) > 2 else None
            if low is None and high is None:
                continue
            clip_consumers[position[node.name]] = tuple(sorted(set(reads.get(node.output[0], []))))
            clips.append((
                position[node.name],
                [-FP16_LIMIT if low is None else low, FP16_LIMIT if high is None else high],
            ))

    # Every count the positional table depends on, asserted so that a re-export fails here
    # rather than by writing a well-formed file that decodes to the wrong matrices.
    want = {
        "linears": (len(linears), layers_count * 10 + 2),
        "norms": (len(norms), layers_count * 9 + 1),
        "clips": (len(clips), layers_count * 18),
        "relative tables": (len(relative), layers_count),
        "per-dim scales": (len(per_dim), layers_count),
        "depthwise kernels": (len(depthwise), layers_count),
        "subsampling kernels": (len(subsample), 2),
        "subsampling norm gains": (len(gains), 2),
    }
    wrong = [f"  {k}: {got} against {expect}" for k, (got, expect) in want.items() if got != expect]
    if wrong:
        raise SystemExit(
            "the audio export's node census has changed, so the numbering this collector "
            "relies on no longer holds:\n" + "\n".join(wrong)
        )
    absent = [i for i in range(max(linears) + 1) if i not in linears]
    predicted = [6 + 11 * i for i in range(layers_count)] + [11 * layers_count + 1]
    if absent != predicted:
        raise SystemExit(
            f"node_linear is missing {absent}, not {predicted}. The first twelve are the folded "
            "relative_k_proj and the last is output_proj's bias Add; a different hole means the "
            "stride of 11 with a gap at +5 is no longer the right walk."
        )
    # The eleven slots of layer 0, checked by shape before the walk is trusted for the other
    # eleven layers. `base = 1 + 11 * i` with a hole at +5 is the whole contract, and every one
    # of these is a plausible-looking matrix if the stride is wrong by one.
    slots = [
        ("ffw1.up", 0, [d_model, ffn]),
        ("ffw1.down", 1, [ffn, d_model]),
        ("q_proj", 2, [d_model, d_model]),
        ("k_proj", 3, [d_model, d_model]),
        ("v_proj", 4, [d_model, d_model]),
        ("post", 6, [d_model, d_model]),
        ("lconv.gate", 7, [d_model, 2 * d_model]),
        ("lconv.exit", 8, [d_model, d_model]),
        ("ffw2.up", 9, [d_model, ffn]),
        ("ffw2.down", 10, [ffn, d_model]),
    ]
    for i in range(layers_count):
        for label, slot, shape in slots:
            got = list(linears[1 + 11 * i + slot].dims)
            if got != shape:
                raise SystemExit(
                    f"layer {i}'s {label} is node_linear_{1 + 11 * i + slot} with shape {got}, "
                    f"not {shape}. The stride of 11 with a hole at +5 no longer walks this graph."
                )

    for name, value, uses in (
        ("q_scale", spec["q_scale"], layers_count),
        ("k_scale", spec["k_scale"], layers_count),
        ("logit_cap", spec["logit_cap"], 2 * layers_count),
        ("residual_weight", spec["residual_weight"], 2 * layers_count),
    ):
        found = sum(
            1
            for node in g.node
            for i in node.input
            if scalar(i) is not None and abs(scalar(i) - value) < 1e-9
        )
        if found != uses:
            raise SystemExit(
                f"{name} = {value} appears {found} times in the graph, not {uses}. "
                f"nets::gemma4_audio hardcodes it, so a changed constant is a wrong forward pass."
            )

    for collection in (relative, per_dim, depthwise, subsample, gains):
        collection.sort()
    clips.sort()
    starts = [position_of_norm(g, position, 9 * i) for i in range(layers_count + 1)]
    if [c for c in clips if c[0] < starts[0]] or [c for c in clips if c[0] > starts[-1]]:
        raise SystemExit(
            "the audio export has clips outside a layer. The input and output projections are "
            "plain nn.Linear, unlike the vision tower's head, so there should be none."
        )
    per_layer_clips = []
    signatures = []
    for i in range(layers_count):
        span = [(at, bounds) for at, bounds in clips if starts[i] < at < starts[i + 1]]
        if len(span) != 18:
            raise SystemExit(f"audio layer {i} has {len(span)} clips, not 18")
        per_layer_clips.append([bounds for _, bounds in span])
        signatures.append(tuple(clip_consumers[at] for at, _ in span))
    # The eighteen bounds are assigned to ops purely by position, and they are calibrated per
    # layer - layer 0's first is [-12.875, 12.8125] and layer 11's is [-9.25, 9.1875] - so a
    # layer whose graph order differed would put every bound on the wrong op with no shape
    # error and no fidelity warning. Checking that each layer's clips are consumed by the same
    # sequence of op types as layer 0's is what turns "positional" into something asserted.
    odd = [i for i, s in enumerate(signatures) if s != signatures[0]]
    if odd:
        raise SystemExit(
            f"audio layers {odd} order their 18 clips differently from layer 0, so assigning "
            f"the bounds by position would silently mis-apply them. Layer 0 is "
            f"{signatures[0]}, layer {odd[0]} is {signatures[odd[0]]}."
        )

    tensors, table = [], []
    fidelity = Fidelity()

    def emit(op, name, key, added):
        table.append(Layer(len(table), op, name, key, len(tensors) - added, added))

    def array(tensor):
        return numpy_helper.to_array(tensor).astype(np.float32)

    def dense(name, tensor, bias=None, transposed=False):
        """A linear's weight as an **fp16** `[out, in, 1, 1]` kernel and a bias.

        The three end projections. They go through here rather than `projection` for the reason
        the vision tower's two do: an error made before layer 0 has run has twelve layers left
        to be amplified through, and one made after the last has nothing downstream to average
        it away. Together they are 5.0 M parameters against the tower's 294 M, so fp16 here
        costs about 8 MB on a 165 MB file.
        """
        matrix = array(tensor)
        if transposed:
            outputs, inputs = matrix.shape
            kernel = np.ascontiguousarray(matrix).reshape(outputs, inputs, 1, 1)
        else:
            inputs, outputs = matrix.shape
            kernel = np.ascontiguousarray(matrix.T).reshape(outputs, inputs, 1, 1)
        zeros = np.zeros(outputs, dtype=np.float32)
        tensors.extend([kernel, zeros if bias is None else array(bias)])
        emit("Linear", name, f"Linear w={[outputs, inputs, 1, 1]} dtype=fp16", 2)

    def projection(name, tensor):
        """A linear's weight as an int4 `[out, in, 1, 1]` kernel, its scale, and a zero bias."""
        matrix = array(tensor)
        inputs, outputs = matrix.shape
        kernel = np.ascontiguousarray(matrix.T).reshape(outputs, inputs, 1, 1)
        codes, scale = fidelity.quantise4(name, kernel)
        tensors.extend([codes, scale, np.zeros(outputs, dtype=np.float32)])
        emit("Linear4", name, f"Linear4 w={[outputs, inputs, 1, 1]} dtype=int4", 3)

    def vector(name, tensor, expect):
        values = array(tensor)
        if list(values.shape) != [expect]:
            raise SystemExit(f"{name} is {list(values.shape)}, not [{expect}]")
        tensors.append(values)
        emit("RmsNorm", name, f"RmsNorm g={[expect]}", 1)

    def clip(name, bounds):
        low, high = bounds
        if not low < high:
            raise SystemExit(f"{name} clips to [{low}, {high}], which is empty")
        tensors.append(np.array([low, high], dtype=np.float32))
        emit("Clip", name, f"Clip [{low}, {high}]", 1)

    def kernel(name, tensor, shape, act="", swap_spatial=False):
        """A convolution kernel at fp16 and a synthesised zero bias.

        None of the three convolutions in this export has a bias, and `Builder::conv` reads one
        after every kernel, so the pair is uniform and the shader needs no special case.

        `swap_spatial` transposes the last two axes, which the two subsampling kernels need and
        the depthwise one does not. `nets::gemma4_audio` carries the mel map as
        `[channels, mel, time]` where the export has `[channels, time, mel]` - see `input_rows`
        for why - and swapping a convolution's input axes is only exact if its kernel's are
        swapped with them. A 3x3 kernel is not symmetric, so feeding a transposed map through an
        untransposed kernel is the right shape and a different convolution.

        Measured rather than reasoned: running the export up to `input_proj`'s output under
        onnxruntime against this layout in numpy gives cosine 0.99999994 with the swap and
        0.906 without it, on the same input. See probe/audio_permute.py.
        """
        values = array(tensor)
        if swap_spatial:
            values = np.ascontiguousarray(values.transpose(0, 1, 3, 2))
        values = values.reshape(shape)
        tensors.extend([values, np.zeros(shape[0], dtype=np.float32)])
        emit("Conv", name, f"Conv w={list(shape)}{act} dtype=fp16", 2)

    def layer_norm(name, tensor, expect):
        """A layer norm's gain and a synthesised zero beta.

        The two subsampling norms are the only `LayerNormalization` in the tower - everything
        else is `SimplifiedLayerNormalization`, i.e. RMS - and the export carries a gain with no
        bias (`elementwise_affine=True, bias=False`). `Builder::layer_norm` reads the pair.
        """
        values = array(tensor)
        if list(values.shape) != [expect]:
            raise SystemExit(f"{name} is {list(values.shape)}, not [{expect}]")
        tensors.extend([values, np.zeros(expect, dtype=np.float32)])
        emit("LayerNorm", name, f"LayerNorm g={[expect]} b={[expect]}", 2)

    def input_rows(tensor):
        """`input_proj_linear`'s weight with its 1024 input rows reordered.

        THE FLATTEN ORDER IS THE TRAP, and it is silent: the matrix is square, so both orderings
        are the right size and only one is the right matrix.

        The export flattens the second subsampling convolution's output **mel-major**. Its
        `Transpose` is `perm=[0, 2, 3, 1]` from `[B, ch, time, mel]`, giving `[B, time, mel, ch]`
        and an input index of `m * 32 + c`. `nets::gemma4_audio` carries the map as
        `[ch, mel, time]` so that the flatten into a `[1024, 1, T]` sequence is a relabelling of
        the same bytes rather than a copy of a 23 MB activation - and that ordering is
        `c * 32 + m`.

        Permuting a `[1024, 1024]` weight once here is free. Transposing the map at runtime, per
        clip, is not. Both sides say so: see `nets::gemma4_audio::INPUT_PROJECTION`.
        """
        matrix = array(tensor)
        if list(matrix.shape) != [d_model, d_model]:
            raise SystemExit(f"the input projection is {list(matrix.shape)}, not square")
        return matrix.reshape(mel_sub, channels[1], d_model).transpose(1, 0, 2).reshape(
            d_model, d_model
        )

    kernel("sscp.conv0", subsample[0][1], (channels[0], 1, 3, 3), swap_spatial=True)
    layer_norm("sscp.norm0", gains[0][1], channels[0])
    kernel("sscp.conv1", subsample[1][1], (channels[1], channels[0], 3, 3), swap_spatial=True)
    layer_norm("sscp.norm1", gains[1][1], channels[1])
    dense("input_projection", numpy_helper.from_array(input_rows(linears[0])))

    # `output_proj` is the one biased linear, so the exporter split it into a `MatMul` and an
    # `Add`. Found by walking BACK from the embedder's pre-projection norm — the last
    # SimplifiedLayerNormalization, which this collector already locates as norms[9 * layers] —
    # rather than by naming any node. The earlier version of this started from a literal
    # `node_linear_133`, in the very namespace this docstring calls unstable; it would have failed
    # loudly rather than mislabelled, but the comment claimed edge-following it was not doing.
    #
    #   norms[108]  <- Add (output_proj.bias)  <- MatMul (output_proj.weight)  <- norm_out
    tail_norm = [n for n in g.node
                 if numbered(n, "node_mean") == layers_count * 9
                 and n.op_type == "SimplifiedLayerNormalization"]
    if len(tail_norm) != 1:
        raise SystemExit(f"{len(tail_norm)} embedder pre-projection norms, not 1")
    adds = [produced[i] for i in tail_norm[0].input if i in produced and produced[i].op_type == "Add"]
    if len(adds) != 1:
        raise SystemExit(
            "the embedder's pre-projection norm is not fed by an Add, so output_proj is no "
            "longer a biased linear the exporter split. Check the tail before trusting this."
        )
    bias = held(adds[0], [out_dim])
    matmuls = [produced[i] for i in adds[0].input if i in produced and produced[i].op_type == "MatMul"]
    weight = [t for n in matmuls for t in held(n, [d_model, out_dim])]
    if not weight:
        # The q4f16 exports: output_proj's MatMul is a MatMulNBits. Decoded to
        # the fp32 [d_model, out_dim] the fp16 walk reads off its MatMul.
        qnodes = [produced[i] for i in adds[0].input
                  if i in produced and produced[i].op_type == "MatMulNBits"]
        weight = [numpy_helper.from_array(dequant_matmul_nbits(n, inits).astype(np.float32),
                                          name=n.name + "_dequant")
                  for n in qnodes
                  if dequant_matmul_nbits(n, inits).shape == (d_model, out_dim)]
    if len(bias) != 1 or len(weight) != 1:
        raise SystemExit(
            f"output_proj resolved to {len(weight)} weights and {len(bias)} biases, not one each"
        )
    dense("output_projection", weight[0], bias=bias[0])
    # `with_scale=False` upstream, so the exporter materialised an all-ones gain. Emitted rather
    # than dropped: it is 3 KB and it saves the runtime a gainless RMS norm. An RMS norm still
    # divides by the RMS, so this is not a no-op whatever the gain is.
    vector("embedder.pre_projection_norm", norms[layers_count * 9], out_dim)
    dense("embedder.projection", linears[11 * layers_count + 2])

    for i in range(layers_count):
        base = 1 + 11 * i
        norm_base = 9 * i
        marks = per_layer_clips[i]
        at = f"audio.layers.{i}"

        vector(f"{at}.ffw1.pre_norm", norms[norm_base], d_model)
        clip(f"{at}.ffw1.clip_in", marks[0])
        projection(f"{at}.ffw1.up", linears[base])
        clip(f"{at}.ffw1.clip_up_out", marks[1])
        clip(f"{at}.ffw1.clip_act", marks[2])
        projection(f"{at}.ffw1.down", linears[base + 1])
        clip(f"{at}.ffw1.clip_out", marks[3])
        vector(f"{at}.ffw1.post_norm", norms[norm_base + 1], d_model)

        vector(f"{at}.pre_attention_norm", norms[norm_base + 2], d_model)
        clip(f"{at}.clip_qkv_in", marks[4])
        projection(f"{at}.q_proj", linears[base + 2])
        projection(f"{at}.k_proj", linears[base + 3])
        projection(f"{at}.v_proj", linears[base + 4])
        clip(f"{at}.clip_q", marks[5])
        clip(f"{at}.clip_k", marks[6])
        clip(f"{at}.clip_v", marks[7])
        # `softplus(per_dim_scale)`, folded by the exporter to a `[128]` fp32 vector, times the
        # scalar `q_scale` and tiled over the eight heads. Two multiplies in the export
        # (`node_mul_502` then `node_mul_506`) become one per-channel multiply here, and the
        # widening from [128] to [1024] is what makes it an ordinary one rather than a per-head
        # broadcast the runtime has no op for.
        scale = np.tile(array(per_dim[i][1]) * spec["q_scale"], heads)
        if scale.shape != (d_model,):
            raise SystemExit(f"{at}'s query scale tiled to {scale.shape}, not ({d_model},)")
        # Rank three, because `Builder::constant` copies it into the arena as a [C, 1, 1]
        # operand for an ordinary per-channel multiply.
        tensors.append(scale.reshape(d_model, 1, 1))
        emit("QueryScale", f"{at}.query_scale", f"QueryScale s={[d_model, 1, 1]} folded=q_scale", 1)
        # `relative_k_proj(sinusoid)`, folded by the exporter and already permuted to
        # [heads, head_dim, offsets]. TRANSPOSED HERE to [heads, offsets, head_dim] so that
        # head_dim - the axis the shader's dot product contracts over - is the contiguous one.
        # Leaving the export's order would be the right size with transposed strides and would
        # read wrong taps with no shape error.
        #
        # Column `offsets - 1` is displacement zero and column 0 is displacement 12, which the
        # mask never admits - see `nets::gemma4_audio::rel_column`.
        relk = array(relative[i][1])
        if list(relk.shape) != [heads, head_dim, offsets]:
            raise SystemExit(f"{at}'s relative table is {list(relk.shape)}")
        relk = np.ascontiguousarray(relk.transpose(0, 2, 1))
        tensors.append(relk)
        emit("Relative", f"{at}.relative_k", f"Relative w={list(relk.shape)} dtype=fp16", 1)
        clip(f"{at}.clip_attn_out", marks[8])
        projection(f"{at}.post_proj", linears[base + 6])
        clip(f"{at}.clip_post_out", marks[9])
        vector(f"{at}.post_attention_norm", norms[norm_base + 3], d_model)

        vector(f"{at}.lconv.pre_norm", norms[norm_base + 4], d_model)
        clip(f"{at}.lconv.clip_in", marks[10])
        projection(f"{at}.lconv.gate", linears[base + 7])
        clip(f"{at}.lconv.clip_gate_out", marks[11])
        # `[1024, 1, 5]` in the export, read here as a `1 x 5` grouped convolution over a
        # `[1024, 1, T]` sequence, so the kernel gains a unit height. Causal: `pads=[4, 0]`.
        kernel(f"{at}.lconv.depthwise", depthwise[i][1], (d_model, 1, 1, 5), act=" group=1024")
        vector(f"{at}.lconv.conv_norm", norms[norm_base + 5], d_model)
        clip(f"{at}.lconv.clip_act", marks[12])
        projection(f"{at}.lconv.exit", linears[base + 8])
        clip(f"{at}.lconv.clip_out", marks[13])

        vector(f"{at}.ffw2.pre_norm", norms[norm_base + 6], d_model)
        clip(f"{at}.ffw2.clip_in", marks[14])
        projection(f"{at}.ffw2.up", linears[base + 9])
        clip(f"{at}.ffw2.clip_up_out", marks[15])
        clip(f"{at}.ffw2.clip_act", marks[16])
        projection(f"{at}.ffw2.down", linears[base + 10])
        clip(f"{at}.ffw2.clip_out", marks[17])
        vector(f"{at}.ffw2.post_norm", norms[norm_base + 7], d_model)
        vector(f"{at}.norm_out", norms[norm_base + 8], d_model)

    if len(tensors) != spec["tensors"]:
        raise SystemExit(
            f"{len(tensors)} tensors, not the {spec['tensors']} nets::gemma4_audio::TENSORS "
            "declares. The two sides index the same table positionally, so they agree or "
            "nothing works."
        )
    fidelity.report(MIN_INT4_COSINE)
    return table, tensors


def collect_gemma4_embed(model, spec):
    """Gemma 4's two embedding tables as the ordered table `nets::gemma4`'s host side indexes.

    THE TENSOR ORDER:

        0..2  `model.embed_tokens.weight`            int4 `[262144, 1536]`, scale, bias
        3..5  `model.embed_tokens_per_layer.weight`  int4 `[262144, 8960]`, scale, bias

    # The scales the export applies are folded in

    `embed_tokens.onnx` is two `Gather`s and two `Mul`s: `inputs_embeds` is the row times 39.25
    (`sqrt(1536)` rounded through fp16) and `per_layer_inputs` is its row times 16.0
    (`sqrt(256)`). Both are constants over the whole table, so they are folded into the weights
    here and the host gathers a row that is already scaled. Applying them at runtime would be one
    more multiply per element per token and one more constant to keep in step.

    # Not transposed

    Unlike every projection in `collect_gemma4`, these stay `[rows, columns]`. Nothing indexes
    them as a convolution kernel - `Reader::int4_row` reads row `id` - so transposing would make
    a gather stride across the file instead of reading one contiguous span.

    # The bias is still there

    A zero bias per row, as for the projections, so the triple is the same shape everywhere and
    `Reader::int4_row`'s `(kernel, scale)` pair sits at a predictable offset. 262144 rows of fp16
    is 512 KB against a 1.2 GB table.
    """
    inits = {i.name: i for i in model.graph.initializer}

    def get(name):
        found = inits.get(name)
        if found is None:
            raise SystemExit(f"the embedding export has no tensor named {name}")
        return numpy_helper.to_array(found).astype(np.float32)

    layers = []
    tensors = []
    fidelity = Fidelity()
    for name, columns, scale_by in (
        ("model.embed_tokens.weight", spec["d_model"], spec["embed_scale"]),
        (
            "model.embed_tokens_per_layer.weight",
            spec["per_layer"] * spec["layers"],
            spec["per_layer_scale"],
        ),
    ):
        table = get(name)
        if list(table.shape) != [spec["vocab"], columns]:
            raise SystemExit(f"{name} is {list(table.shape)}, not {[spec['vocab'], columns]}")
        codes, scale = fidelity.quantise4(name, table * scale_by)
        bias = np.zeros(spec["vocab"], dtype=np.float32)
        tensors.extend([codes, scale, bias])
        layers.append(
            Layer(
                len(layers),
                "Embedding4",
                name,
                f"Embedding4 w={list(table.shape)} scale={list(scale.shape)} "
                f"folded={scale_by} dtype=int4 block={I4_BLOCK}",
                len(tensors) - 3,
                3,
            )
        )
    fidelity.report(MIN_INT4_COSINE)
    return layers, tensors


def collect_nllb(get, spec):
    """NLLB-200-distilled-600M's checkpoint as the ordered tensor table `nets::nllb600` indexes.

    THE TENSOR ORDER (the contract with the Rust net — positional indexing, no names):

        1. the tied embedding (`model.shared.weight` ONLY), as `head_splits` disjoint
           class ranges: [0:64052], [64052:128104], [128104:192155], [192155:256206].
           Each range emits 3 tensors: int8 kernel [rows, 1024, 1, 1], fp16 scale [rows],
           synthesised zero bias [rows]. Op "Head". Tensors 0..11.
        2. each encoder layer 0..11 in forward order. Per layer, in this exact order:
           self_attn_layer_norm (LayerNorm, 2 tensors: gamma, beta),
           self_attn q/k/v/out_proj (Linear8, 3 tensors each: kernel, scale, bias),
           final_layer_norm (LayerNorm, 2),
           fc1 (Linear8, 3), fc2 (Linear8, 3).
           22 tensors per encoder layer.
        3. the encoder's final layer norm (LayerNorm, 2 tensors).
        4. each decoder layer 0..11 in forward order. Per layer: self_attn block as in
           the encoder (2 + 4*3 = 14 tensors), then encoder_attn_layer_norm (2) +
           encoder_attn q/k/v/out_proj (4*3 = 12), then final_layer_norm (2) + fc1/fc2
           (2*3 = 6). 36 tensors per decoder layer.
        5. the decoder's final layer norm (LayerNorm, 2 tensors). Last.

    Totals: 12 head tensors + 12*22 encoder + 2 + 12*36 decoder + 2 = 712 tensors in
    258 layers (4 Head + 96 enc + 1 + 156 dec + 1).
    196 int8 convolutions (4 head + 12*6 enc + 12*10 dec); everything else fp16 norms
    and biases.

    # What is folded, and what is not (same as small100 — same M2M-100 forward pass)

    * `sqrt(d_model)` is **not** folded into the embedding. The host gather applies it in
      fp32 (`embed_positions`), one rounding fewer than scaling int8 codes.
    * The attention query scale is **not** folded into `q_proj`. M2M-100 scales the query
      by `head_dim ** -0.5` (head_dim 64) = the `1 / sqrt(head_dim)` `attn_scores` applies.
    * Sinusoidal positions are not in the checkpoint and are not emitted. `nets::nllb600`
      computes fairseq's concatenated-halves sinusoid at position `past + at + 1 + 1`
      (padding_idx 1, so first token sits at 2) — byte-identical math to small100.
    * FFN activation is **ReLU** (`activation_function: relu` in config.json), so the Rust
      uses `Act::Relu` on fc1, not GELU.
    * The four checkpoint copies of the tied embedding are asserted byte-identical in
      `fetch_nllb600.py` before conversion; only `model.shared.weight` is read here.
    """
    layers = []
    tensors = []
    fidelity = Fidelity()

    def emit(op, name, key, added):
        layers.append(Layer(len(layers), op, name, key, len(tensors) - added, added))

    def linear(name):
        """An int8 kernel as `[out, in, 1, 1]`, its per-output-channel scale, and its bias."""
        weight = get(f"{name}.weight")
        bias = get(f"{name}.bias")
        outputs, inputs = weight.shape
        kernel, scale = fidelity.quantise(name, weight.reshape(outputs, inputs, 1, 1))
        tensors.extend([kernel, scale, bias])
        emit(
            "Linear8",
            name,
            f"Linear8 w={list(kernel.shape)} scale={[outputs]} b={list(bias.shape)} "
            "zp=0 dtype=int8",
            3,
        )

    def layer_norm(name):
        gamma = get(f"{name}.weight")
        beta = get(f"{name}.bias")
        tensors.extend([gamma, beta])
        emit("LayerNorm", name, f"LayerNorm g={list(gamma.shape)} b={list(beta.shape)}", 2)

    embedding = get("model.shared.weight")
    rows_spec = spec["head_rows"]
    if sum(rows_spec) != embedding.shape[0]:
        raise SystemExit(
            f"{embedding.shape[0]} classes do not split as {rows_spec}"
        )
    lo = 0
    for part, rows in enumerate(rows_spec):
        chunk = embedding[lo : lo + rows]
        name = f"model.shared.weight[{lo}:{lo + rows}]"
        kernel, scale = fidelity.quantise(name, chunk.reshape(rows, embedding.shape[1], 1, 1))
        # `Builder::conv_int8` reads a bias after the scale, and a tied head has none. Zeros are
        # exact, and recording it in the key means a checkpoint that grows one is not mistaken for
        # this table.
        tensors.extend([kernel, scale, np.zeros(rows, dtype=np.float32)])
        emit(
            "Head",
            name,
            f"Head w={list(kernel.shape)} scale={[rows]} b={[rows]} "
            f"classes={lo}..{lo + rows} zp=0 dtype=int8 b0=synthesised",
            3,
        )
        lo += rows

    for side, count in (("encoder", spec["encoder_layers"]), ("decoder", spec["decoder_layers"])):
        for index in range(count):
            at = f"model.{side}.layers.{index}"
            # Pre-norm, as `M2M100EncoderLayer` and `M2M100DecoderLayer` are: the norm comes
            # before the sublayer it feeds and the residual skips both. Emitting in forward order
            # is what lets the Rust walk the table without an index table.
            layer_norm(f"{at}.self_attn_layer_norm")
            for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                linear(f"{at}.self_attn.{projection}")
            if side == "decoder":
                layer_norm(f"{at}.encoder_attn_layer_norm")
                for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                    linear(f"{at}.encoder_attn.{projection}")
            layer_norm(f"{at}.final_layer_norm")
            linear(f"{at}.fc1")
            linear(f"{at}.fc2")
        layer_norm(f"model.{side}.layer_norm")

    fidelity.report()
    return layers, tensors


def maia_inventory(spec):
    """`{name: shape}` Maia3-5M's checkpoint must hold exactly, after `fetch_maia.py`'s repack.

    Derived rather than transcribed: a list of 148 names would be checked against itself.

    Three absences are as load-bearing as the presences, so all three are stated by *not*
    appearing:

    * **No attention biases.** `omit_qkv_biases=True`, so there is no `mha.in_proj_bias`
      and no `mha.out_proj.bias` in any of the eight blocks.
    * **No `norm1.bias` or `norm2.bias`.** They are `torch.nn.RMSNorm`, which has a gain
      and nothing else. A checkpoint that grew either would be a layer norm, and the Rust
      would silently ignore the beta.
    * **No `transformer.layers.N.self_attn.smolgen_weight`.** Those eight names alias
      `smolgen_shared_weight`; `fetch_maia.py` asserts that and drops them, so a checkpoint
      whose blocks stopped sharing one weight fails here with eight unexpected names rather
      than being converted as if they still did.

    `proj_sq_from`, `proj_sq_to` and `promo_bias_proj` are `bias=False` too. The shapes are
    `torch.nn.Linear`'s **`[out, in]`**, so a kernel is a reshape and not a transpose;
    `linear1 [512, 256]` against `linear2 [256, 512]` is the asymmetric pair that makes a
    transposed checkpoint fail here rather than on the device.
    """
    d, ffn = spec["d_model"], spec["ffn"]
    hidden, gen, heads = spec["bias_hidden"], spec["bias_gen"], spec["heads"]
    squares, hid = spec["squares"], spec["head_hidden"]
    want = {
        "smolgen_shared_weight": [squares * squares, gen],
        "elo_embedding_low.weight": [1, spec["elo_dim"]],
        "elo_embedding_high.weight": [1, spec["elo_dim"]],
        "token_projection.weight": [d, spec["input"]],
        "token_projection.bias": [d],
    }
    for index in range(spec["blocks"]):
        at = f"transformer.layers.{index}"
        want[f"{at}.self_attn.mha.in_proj_weight"] = [3 * d, d]
        want[f"{at}.self_attn.mha.out_proj.weight"] = [d, d]
        want[f"{at}.self_attn.sm2.weight"] = [hidden, d]
        want[f"{at}.self_attn.sm2.bias"] = [hidden]
        want[f"{at}.self_attn.ln1.weight"] = [hidden]
        want[f"{at}.self_attn.ln1.bias"] = [hidden]
        want[f"{at}.self_attn.sm3.weight"] = [heads * gen, hidden]
        want[f"{at}.self_attn.sm3.bias"] = [heads * gen]
        want[f"{at}.self_attn.ln2.weight"] = [heads * gen]
        want[f"{at}.self_attn.ln2.bias"] = [heads * gen]
        want[f"{at}.linear1.weight"] = [ffn, d]
        want[f"{at}.linear1.bias"] = [ffn]
        want[f"{at}.linear2.weight"] = [d, ffn]
        want[f"{at}.linear2.bias"] = [d]
        want[f"{at}.norm1.weight"] = [d]
        want[f"{at}.norm2.weight"] = [d]
    want["transformer.norm.weight"] = [d]
    want["transformer.norm.bias"] = [d]
    want["proj_sq_from.weight"] = [hid, d]
    want["proj_sq_to.weight"] = [hid, d]
    want["promo_bias_proj.weight"] = [spec["promotions"], hid]
    # The value and ponder heads. Present, asserted, and deliberately not emitted — see
    # [`CHECKPOINTS`] and [`collect_maia`].
    want["last_ln.weight"] = [d]
    want["last_ln.bias"] = [d]
    want["fc_value_hid.weight"] = [hid, d]
    want["fc_value_hid.bias"] = [hid]
    want["fc_value.weight"] = [3, hid]
    want["fc_value.bias"] = [3]
    want["fc_ponder_hid.weight"] = [hid, d]
    want["fc_ponder_hid.bias"] = [hid]
    want["fc_ponder.weight"] = [1, hid]
    want["fc_ponder.bias"] = [1]
    return want


def collect_maia(get, spec):
    """Maia3-5M's checkpoint as the ordered tensor table `nets::maia` indexes.

    THE TENSOR ORDER (the contract with the Rust net — positional indexing, no names):

        0.  elo_embedding_low.weight   [1, 128]           op "Host"
        1.  elo_embedding_high.weight  [1, 128]           op "Host"
        2.  token_projection           int8 [256, 352, 1, 1] + scale [256] + bias [256]
        5.  smolgen kernel replicated  int8 [32768, 64, 1, 1] + scale [32768] + zero bias
        8.  each block 0..7 in forward order, 30 tensors each, in this exact order:
              sm2 (Linear8, 3), ln1 (LayerNorm, 2), sm3 (Linear8, 3), ln2 (LayerNorm, 2),
              q / k / v / out_proj (Linear8, 3 each — bias synthesised),
              norm1 (RmsNorm, 1), linear1 (Linear8, 3), linear2 (Linear8, 3),
              norm2 (RmsNorm, 1).
        248. transformer.norm (LayerNorm, 2).
        250. proj_sq_from (Linear8, 3 — bias synthesised).
        253. proj_sq_to (Linear8, 3 — bias synthesised).
        256. promo_bias_proj (Linear8, 3 — bias synthesised). Last.

    Totals: 259 tensors in 104 layers, 69 of them int8 convolutions. Everything else — the
    norms, the biases and the two elo tables — stays fp16, and is 1% of the parameters.

    # The two structural rewrites

    * **The fused `in_proj_weight` is split.** Torch packs q, k and v into one `[768, 256]`
      tensor, in that order (`_in_projection_packed` chunks it three ways). Three separate
      `[256, 256, 1, 1]` kernels come out, each with a synthesised zero bias because
      `omit_qkv_biases=True` and `Builder::conv` always reads one.

    * **`smolgen_shared_weight` is replicated eight times.** Upstream expands the bias
      generator's `[8, 64]` output with `einsum("bhi,oi->bho")` against a `[4096, 64]`
      weight. This runtime has no transpose op, so `nets::maia` expresses that as a
      **grouped 1x1 convolution** with `group = 8` — and a grouped convolution's kernel is
      `[out, in/group, 1, 1]`, which here is `[32768, 64, 1, 1]`: group `g`'s 4096 output
      rows must each hold the same `[4096, 64]` weight. `np.tile` along axis 0 puts them in
      exactly the order group `g` reads.

      It costs +1.84M elements, and at int8 that is +1.8 MB and 30% of the file. It is
      replicated **once, not per block**: the parameter is shared, so all eight blocks pass
      tensor 5 as their `weight_index` and the file holds one copy of the replication.

      The quantisation is done on the `[4096, 64]` original and then tiled, not the other way
      round: the two give identical codes because the replicated rows are identical, but
      quantising 262,144 elements instead of 2,097,152 also makes the fidelity line report the
      tensor the checkpoint actually holds.

    # int8, and why not int4

    Every convolution here is int8, quantised per output channel; the norms, the biases and the
    two elo tables stay fp16. 13.3 MiB to 6.8 MiB, with the worst per-tensor cosine at
    0.999941 against a 0.999 gate.

    `scripts/ml/maia_quant_eval.py` is what justifies that, on move agreement rather than on
    cosine: over 400 positions at each shipping rating the int8 model picks a different move
    0.5% to 1.0% of the time, always where the top two legal moves are within 0.017 — inside
    what fp16 rounding does on its own. The same harness rejects int4 at 87% to 91% agreement
    with 14 to 29 genuine changes of mind, at any group size.

    # What is folded, and what is not

    * The attention query scale is **not** folded into the q kernel. Torch scales the query
      by `head_dim ** -0.5` and `head_dim` is 32, which is the `1 / sqrt(head_dim)`
      `attn_scores` already applies. Folding it would apply it twice.
    * The policy head's `1 / sqrt(head_hid_dim)` is not folded either, for the same reason:
      `head_hid_dim` is 256 and `attn_scores(from, to, 1)` derives `1 / sqrt(256)` itself.
    * `promo_bias_proj`'s `* sqrt(head_hid_dim)` is **not** folded into the kernel. It is
      applied by `post::maia` on the host, where the score it is added to has already been
      divided by the same factor — folding one of the pair and not the other is the easy
      mistake, so neither is folded.
    * The elo interpolation is not folded: the two embeddings are emitted raw and blended
      on the host by `nets::maia::elo_embedding`, because the blend weight is an input.
    """
    layers = []
    tensors = []
    fidelity = Fidelity()

    def emit(op, name, key, added):
        layers.append(Layer(len(layers), op, name, key, len(tensors) - added, added))

    def linear(name, weight=None, bias=None):
        """An int8 kernel as `[out, in, 1, 1]`, its per-output-channel scale, and its bias."""
        weight = get(f"{name}.weight") if weight is None else weight
        outputs, inputs = weight.shape
        synthesised = bias is None
        if synthesised:
            bias = np.zeros(outputs, dtype=np.float32)
        kernel, scale = fidelity.quantise(name, weight.reshape(outputs, inputs, 1, 1))
        tensors.extend([kernel, scale, bias])
        note = " b0=synthesised" if synthesised else ""
        emit(
            "Linear8",
            name,
            f"Linear8 w={list(kernel.shape)} scale={[outputs]} b={[outputs]} "
            f"zp=0 dtype=int8{note}",
            3,
        )

    def layer_norm(name):
        gamma = get(f"{name}.weight")
        beta = get(f"{name}.bias")
        tensors.extend([gamma, beta])
        emit("LayerNorm", name, f"LayerNorm g={list(gamma.shape)} b={list(beta.shape)}", 2)

    def rms_norm(name):
        """An RMS norm's gain. One tensor: there is no beta to follow it."""
        gamma = get(f"{name}.weight")
        tensors.append(gamma)
        emit("RmsNorm", name, f"RmsNorm g={list(gamma.shape)}", 1)

    d, heads = spec["d_model"], spec["heads"]
    gen, squares = spec["bias_gen"], spec["squares"]

    for name in ("elo_embedding_low.weight", "elo_embedding_high.weight"):
        table = get(name)
        tensors.append(table)
        emit("Host", name, f"Host t={list(table.shape)}", 1)

    linear("token_projection", bias=get("token_projection.bias"))

    shared = get("smolgen_shared_weight")
    if list(shared.shape) != [squares * squares, gen]:
        raise SystemExit(f"smolgen_shared_weight is {list(shared.shape)}, not {[squares*squares, gen]}")
    # Quantise the original, then tile the codes and the scale. Tiling first would quantise
    # 2,097,152 identical-by-construction rows to reach the same answer, and would report the
    # fidelity of a tensor the checkpoint does not hold.
    codes, scale = fidelity.quantise("smolgen_shared_weight", shared.reshape(*shared.shape, 1, 1))
    replicated = np.tile(codes.reshape(squares * squares, gen), (heads, 1)).reshape(
        heads * squares * squares, gen, 1, 1
    )
    scales = np.tile(scale, heads)
    rows = replicated.shape[0]
    tensors.extend([replicated, scales, np.zeros(rows, dtype=np.float32)])
    emit(
        "Smolgen8",
        "smolgen_shared_weight",
        f"Smolgen8 w={list(replicated.shape)} scale={[rows]} b={[rows]} group={heads} "
        f"from={list(shared.shape)} zp=0 dtype=int8 b0=synthesised",
        3,
    )

    for index in range(spec["blocks"]):
        at = f"transformer.layers.{index}"
        # The bias generator, then attention, then the feed-forward — the order
        # `nets::maia::encoder_block` walks, so the Rust needs no index table.
        linear(f"{at}.self_attn.sm2", bias=get(f"{at}.self_attn.sm2.bias"))
        layer_norm(f"{at}.self_attn.ln1")
        linear(f"{at}.self_attn.sm3", bias=get(f"{at}.self_attn.sm3.bias"))
        layer_norm(f"{at}.self_attn.ln2")

        fused = get(f"{at}.self_attn.mha.in_proj_weight")
        if list(fused.shape) != [3 * d, d]:
            raise SystemExit(f"{at} in_proj_weight is {list(fused.shape)}, not {[3 * d, d]}")
        for part, projection in enumerate(("q_proj", "k_proj", "v_proj")):
            linear(f"{at}.self_attn.{projection}", weight=fused[part * d : (part + 1) * d])
        linear(f"{at}.self_attn.mha.out_proj")

        # Post-norm: `norm1` closes the attention residual and `norm2` the feed-forward's,
        # so both come after the sublayer they normalise rather than before it.
        rms_norm(f"{at}.norm1")
        linear(f"{at}.linear1", bias=get(f"{at}.linear1.bias"))
        linear(f"{at}.linear2", bias=get(f"{at}.linear2.bias"))
        rms_norm(f"{at}.norm2")

    layer_norm("transformer.norm")
    linear("proj_sq_from")
    linear("proj_sq_to")
    linear("promo_bias_proj")

    fidelity.report()
    return layers, tensors


def madlad_inventory(spec):
    """`{name: shape}` MADLAD400-3B-MT's checkpoint must hold exactly.

    Derived rather than transcribed: a list of ~580 names would be checked against itself.

    The names are transformers' `T5ForConditionalGeneration` state-dict names
    (`T5LayerSelfAttention.SelfAttention.q`, `T5LayerFF.DenseReluDense.wi_0`, ...).
    Three absences are as load-bearing as the presences, so all three are stated by
    *not* appearing:

    * **No projection biases.** Every `q/k/v/o` and `wi_0/wi_1/wo` is `bias=False`
      (all 192 attentions, all 64 feed-forwards). A checkpoint that grew one would
      have unexpected names and fail, rather than being read as this table with the
      bias ignored.
    * **No `layer_norm.bias`.** They are `T5LayerNorm` (RMSNorm), which has a gain
      and nothing else. A checkpoint that grew either would be a layer norm, and the
      Rust would silently ignore the beta.
    * **No relative tables past block 0.** Only the two block-0 self-attentions hold
      `relative_attention_bias.weight` (`has_relative_attention_bias=bool(i == 0)`);
      deeper layers reuse the computed bias. A checkpoint that grew more would be a
      different forward pass.

    The input table is `decoder.embed_tokens.weight`, not `shared.weight`: T5's
    `_tie_or_clone_weights` clones shared into the decoder embeddings at load, and
    this export materialised only that clone. `lm_head.weight` is the separate
    logits kernel, and the two differ (checked by `fetch_madlad400.check_untied`).

    The shapes are `torch.nn.Linear`'s **`[out, in]`**, so a kernel is a reshape and
    not a transpose; `wi_0 [8192, 1024]` against `wo [1024, 8192]` is the asymmetric
    set that makes a transposed checkpoint fail here rather than on the device.
    """
    d, ffn = spec["d_model"], spec["d_ff"]
    inner = spec["heads"] * spec["head_dim"]
    want = {
        "decoder.embed_tokens.weight": [spec["vocab"], d],
        "lm_head.weight": [spec["vocab"], d],
    }
    for side, count in (("encoder", spec["encoder_layers"]), ("decoder", spec["decoder_layers"])):
        for index in range(count):
            at = f"{side}.block.{index}"
            attentions = ["SelfAttention"] + (["EncDecAttention"] if side == "decoder" else [])
            for layer, attention in enumerate(attentions):
                want[f"{at}.layer.{layer}.{attention}.q.weight"] = [inner, d]
                want[f"{at}.layer.{layer}.{attention}.k.weight"] = [inner, d]
                want[f"{at}.layer.{layer}.{attention}.v.weight"] = [inner, d]
                want[f"{at}.layer.{layer}.{attention}.o.weight"] = [d, inner]
                want[f"{at}.layer.{layer}.layer_norm.weight"] = [d]
            if index == 0:
                want[f"{at}.layer.0.SelfAttention.relative_attention_bias.weight"] = [
                    spec["buckets"],
                    spec["heads"],
                ]
            ff = len(attentions)
            want[f"{at}.layer.{ff}.DenseReluDense.wi_0.weight"] = [ffn, d]
            want[f"{at}.layer.{ff}.DenseReluDense.wi_1.weight"] = [ffn, d]
            want[f"{at}.layer.{ff}.DenseReluDense.wo.weight"] = [d, ffn]
            want[f"{at}.layer.{ff}.layer_norm.weight"] = [d]
        want[f"{side}.final_layer_norm.weight"] = [d]
    return want


def collect_madlad(get, spec):
    """MADLAD400-3B-MT's checkpoint as the ordered tensor table `nets::madlad` indexes.

    THE TENSOR ORDER (the contract with the Rust net — positional indexing, no names):

        1. the input table (`decoder.embed_tokens.weight` ONLY), as `head_splits`
           disjoint class ranges: [0:64000], [64000:128000], [128000:192000],
           Each range emits 3 tensors: int8 kernel [rows, 1024, 1, 1], fp16 scale
           [rows], synthesised zero bias [rows]. Op "Head". Tensors 0..11.
        2. the logits kernel (`lm_head.weight` ONLY), same four ranges. Op "Head".
           Tensors 12..23.
        3. each encoder layer 0..31 in forward order. Per layer, in this exact order:
           self norm (RmsNorm, 1 tensor: gamma),
           self q/k/v/o (Linear8, 3 tensors each: kernel, scale, synthesised bias),
           ff norm (RmsNorm, 1),
           fused wi_01 (Linear8, 3: `[2 * d_ff, d_model, 1, 1]` kernel over
           `[wi_0 rows | wi_1 rows]`, scale, synthesised bias), wo (Linear8, 3).
           20 tensors per encoder layer.
        4. the encoder's final norm (RmsNorm, 1 tensor).
        5. each decoder layer 0..31 in forward order. Per layer: self block as in
           the encoder (1 + 4*3 = 13 tensors), then cross norm (1) +
           cross q/k/v/o (4*3 = 12), then ff norm (1) + fused wi_01/wo (2*3 = 6).
           33 tensors per decoder layer.
        6. the decoder's final norm (RmsNorm, 1 tensor).
        7. the two block-0 relative tables, fp16 verbatim for the host to read:
           encoder self `[32, 16]`, decoder self `[32, 16]`. Op "Relative". Last.

    Totals: 24 head tensors + 32*20 encoder + 1 + 32*33 decoder + 1 + 2 tables =
    1724 tensors in 684 layers (8 Head + 256 enc + 1 + 416 dec + 1 + 2 Relative).
    488 int8 convolutions (8 head + 32*6 enc + 32*9 dec); everything else fp16
    norms, biases and the two tables.

    # What is folded, and what is not

    * `sqrt(d_model)` is **not** folded into the embedding — T5 has no embedding
      scale at all (no `scale_embedding`). The host gather dequantises the row.
    * The attention query scale is **not** folded into `q`. T5 applies none (Mesh
      TensorFlow init), and `attn_scores_prescaled` applies exactly 1.0.
    * The relative bias is **not** folded into any projection. It is position-
      dependent, so it cannot be; the host computes it per shape and hands it in
      as a plan input the net adds with a plain `Add`.
    * `wi_0` and `wi_1` **are** fused into one `[16384, 1024]` projection. GEGLU
      needs `GELU(wi_0(x)) * wi_1(x)`, and `GatedActivate` reads a fused
      `[gate | up]` tensor — so the converter concatenates the rows and the Rust
      emits one convolution instead of two plus a slice pair.
    * FFN activation is **gated-gelu** (`feed_forward_proj: gated-gelu`), so the
      Rust uses `Act::Gelu` on the fused projection. T5's is the sigmoid
      approximation and the shader is exact-erf; the difference is below fp16
      resolution (see `nets::madlad`).
    * The two untied tables are each emitted whole-range: `fetch_madlad400.py`
      asserts they differ before conversion, so emitting both is exact.
    """
    layers = []
    tensors = []
    fidelity = Fidelity()

    def emit(op, name, key, added):
        layers.append(Layer(len(layers), op, name, key, len(tensors) - added, added))

    def linear(name, weight=None):
        """An int8 kernel as `[out, in, 1, 1]`, its per-output-channel scale, and a bias.

        T5 carries no projection biases, so the bias is always synthesised zeros —
        recorded in the key so a checkpoint that grows one is not mistaken for
        this table.
        """
        weight = get(f"{name}.weight") if weight is None else weight
        outputs, inputs = weight.shape
        kernel, scale = fidelity.quantise(name, weight.reshape(outputs, inputs, 1, 1))
        tensors.extend([kernel, scale, np.zeros(outputs, dtype=np.float32)])
        emit(
            "Linear8",
            name,
            f"Linear8 w={list(kernel.shape)} scale={[outputs]} b={[outputs]} "
            "zp=0 dtype=int8 b0=synthesised",
            3,
        )

    def rms_norm(name):
        """An RMS norm's gain. One tensor: there is no beta to follow it."""
        gamma = get(f"{name}.weight")
        tensors.append(gamma)
        emit("RmsNorm", name, f"RmsNorm g={list(gamma.shape)}", 1)

    def head(table, rows_spec):
        embedding = get(f"{table}.weight")
        if sum(rows_spec) != embedding.shape[0]:
            raise SystemExit(f"{embedding.shape[0]} classes do not split as {rows_spec}")
        lo = 0
        for rows in rows_spec:
            chunk = embedding[lo : lo + rows]
            name = f"{table}.weight[{lo}:{lo + rows}]"
            kernel, scale = fidelity.quantise(
                name, chunk.reshape(rows, embedding.shape[1], 1, 1)
            )
            # `Builder::conv_int8` reads a bias after the scale, and an embedding
            # table has none. Zeros are exact, and recording it in the key means a
            # checkpoint that grows one is not mistaken for this table.
            tensors.extend([kernel, scale, np.zeros(rows, dtype=np.float32)])
            emit(
                "Head",
                name,
                f"Head w={list(kernel.shape)} scale={[rows]} b={[rows]} "
                f"classes={lo}..{lo + rows} zp=0 dtype=int8 b0=synthesised",
                3,
            )
            lo += rows

    d, ffn = spec["d_model"], spec["d_ff"]
    rows_spec = spec["head_rows"]
    head("decoder.embed_tokens", rows_spec)
    head("lm_head", rows_spec)

    for side, count in (("encoder", spec["encoder_layers"]), ("decoder", spec["decoder_layers"])):
        for index in range(count):
            at = f"{side}.block.{index}"
            # Pre-norm, as `T5Block` is: the norm comes before the sublayer it feeds
            # and the residual skips both. Emitting in forward order is what lets the
            # Rust walk the table without an index table.
            attentions = ["SelfAttention"] + (["EncDecAttention"] if side == "decoder" else [])
            for layer, attention in enumerate(attentions):
                rms_norm(f"{at}.layer.{layer}.layer_norm")
                for projection in ("q", "k", "v", "o"):
                    linear(f"{at}.layer.{layer}.{attention}.{projection}")
            ff = len(attentions)
            rms_norm(f"{at}.layer.{ff}.layer_norm")
            wi_0 = get(f"{at}.layer.{ff}.DenseReluDense.wi_0.weight")
            wi_1 = get(f"{at}.layer.{ff}.DenseReluDense.wi_1.weight")
            if list(wi_0.shape) != [ffn, d] or list(wi_1.shape) != [ffn, d]:
                raise SystemExit(
                    f"{at} wi_0 {list(wi_0.shape)} wi_1 {list(wi_1.shape)}, not "
                    f"[{ffn}, {d}] each"
                )
            # Fused `[gate | up]`, the layout `GatedActivate` halves. Quantise the
            # concatenation (identical rows quantise identically either way) so the
            # fidelity line reports the tensor the file actually holds.
            fused = np.concatenate([wi_0, wi_1], axis=0)
            linear(f"{at}.layer.{ff}.DenseReluDense.wi_01", weight=fused)
            linear(f"{at}.layer.{ff}.DenseReluDense.wo")
        rms_norm(f"{side}.final_layer_norm")

    for side in ("encoder", "decoder"):
        table = get(f"{side}.block.0.layer.0.SelfAttention.relative_attention_bias.weight")
        if list(table.shape) != [spec["buckets"], spec["heads"]]:
            raise SystemExit(
                f"{side} block-0 relative table is {list(table.shape)}, not "
                f"[{spec['buckets']}, {spec['heads']}]"
            )
        tensors.append(np.ascontiguousarray(table, dtype=np.float32))
        emit(
            "Relative",
            f"{side}.block.0.relative_attention_bias",
            f"Relative t={list(table.shape)} host-read fp16",
            1,
        )

    fidelity.report()
    return layers, tensors


def q2k_to_float(payload, gguf_shape, torch_shape):
    """GGUF Q2_K payload dequantised to fp32 in torch `torch_shape` row order.

    Exact transcription of candle's `BlockQ2K::to_float` (k_quants.rs:1016), composed
    with candle's GGUF dim reversal. The physical bytes are the torch matrix in
    row-major FLAT order: `torch_flat = gguf_flat`, reshaped to `torch_shape`. The
    per-block loop below dequantises physical row by row into the flat order; the
    reshape to torch rows happens once at the end.

    (Two earlier revisions failed: (1) scattering each block's 256 taps across 256 torch
    rows — passed every byte-level check, cosine ~0.0 vs fp32; (2) physical-row-is-torch-row
    — row counts differ (1024 vs 2048), impossible. The flat-reshape rule was derived from
    candle's shape reversal + `matmul_t` layout and verified at cosine 0.96 on q row 0.)

    Per 16-elem lane: `y = dl*(q) - ml`, `dl = d*(sc&0xF)`, `ml = dmin*(sc>>4)`
    (superblock `d`/`dmin` straight, NO extra product — `to_float` has no activation
    scale to fold, unlike `vec_dot`'s `dall = y.d*x.d`). `q` 2 bits at shift 0/2/4/6
    cycling per 32-elem half; halves read separate 32-byte `qs` chunks (`qs[0:32]` then
    `qs[32:64]`), `is` stepping by 2 per lane-pair across the whole block. `d`/`dmin`
    are fp16.

    All GGUF shapes here divide evenly by 256 — a remainder fails loudly in `open_gguf`'s
    size computation rather than silently misaligning here.
    """
    import numpy as _np

    gguf_rows, gguf_cols = gguf_shape
    n_blocks = gguf_cols // Q2K_BLOCK
    # Physical row i holds gguf_cols taps = n_blocks superblocks of 84 bytes.
    raw = _np.frombuffer(payload, dtype=_np.uint8).reshape(gguf_rows, n_blocks * Q2K_BYTES)
    flat = _np.empty((gguf_rows * gguf_cols,), dtype=_np.float32)
    for i in range(gguf_rows):
        at = 0
        for k in range(n_blocks):
            block = raw[i, at : at + Q2K_BYTES]
            at += Q2K_BYTES
            scales = block[:16]
            qs = block[16:80]
            d = _np.frombuffer(block[80:82].tobytes(), dtype=_np.float16)[0].astype(_np.float32)
            dmin = _np.frombuffer(block[82:84].tobytes(), dtype=_np.float16)[0].astype(_np.float32)
            is_ = 0
            # 256 taps = 2 halves of 128 (`y_chunks` zipped with 32-byte `qs_chunks`); each
            # half consumes 8 scale bytes in 4 lane-pairs. Lane-pair `j` at shift 2*j: the
            # even scale covers 16 taps from the half-chunk's `qs[..16]`, the odd scale 16
            # taps from `qs[16..]` — both at the SAME shift. Half 1 reads qs[32:64], NOT
            # qs[0:32]: the halves are separate 32-byte chunks.
            base = (i * n_blocks + k) * Q2K_BLOCK
            for half in range(2):
                hqs = qs[half * 32 : half * 32 + 32]
                shift = 0
                for lane in range(4):
                    sc = scales[is_]
                    is_ += 1
                    lo, hi = sc & 0xF, sc >> 4
                    for l in range(16):
                        q = (int(hqs[l]) >> shift) & 3
                        flat[base + half * 128 + lane * 32 + l] = d * (q * lo) - dmin * hi
                    sc = scales[is_]
                    is_ += 1
                    lo, hi = sc & 0xF, sc >> 4
                    for l in range(16):
                        q = (int(hqs[16 + l]) >> shift) & 3
                        flat[base + half * 128 + lane * 32 + 16 + l] = (
                            d * (q * lo) - dmin * hi
                        )
                    shift += 2
    rows, taps = torch_shape
    if rows * taps != gguf_rows * gguf_cols:
        raise SystemExit(f"torch shape {torch_shape} does not tile GGUF shape {gguf_shape}")
    return flat.reshape(rows, taps)


INVENTORIES.update({"small100": small100_inventory, "whisper": whisper_inventory})
COLLECTORS.update({"small100": collect_small100, "whisper": collect_whisper})
INVENTORIES.update({"nllb600": nllb_inventory})
COLLECTORS.update({"nllb600": collect_nllb})
INVENTORIES.update({"maia": maia_inventory})
COLLECTORS.update({"maia": collect_maia})
INVENTORIES.update({"madlad400": madlad_inventory})
COLLECTORS.update({"madlad400": collect_madlad})


# The vision position table, after TinyCLIP's export constant-folds it: `position_ids` is a
# constant, so the `Gather` that reads the table folds away and its `[1, 197, 256]` result is the
# initializer. There is no `vision_model.embeddings.position_embedding.weight` to read.
#
# Named here because it is the one parameter whose key is an internal tensor name rather than a
# module path, and both [`tinyclip_inventory`] and [`collect_tinyclip`] have to agree about it.
VISION_POSITIONS_FOLDED = "/vision_model/embeddings/position_embedding/Gather_output_0"


def tinyclip_inventory(spec):
    """What TinyCLIP's export must hold, as two dicts keyed the two ways the graph names things.

    Returned rather than transcribed: a list of 250 names would be checked against itself.

    * `parameters` is `{initializer name: dims}` for everything the export names — the norms, the
      biases, the patch kernel, both embedding tables and the class token.
    * `projections` is `{MatMul node name: dims}` for the 80 anonymous weights. Keyed by node
      because the initializer names carry no information; see [`ARCHITECTURES`].

    `dims` for a projection is ONNX's own **`[in, out]`**, so this dict is also the statement that
    catches a transposed read: `fc1` at `[256, 1024]` and `fc2` at `[1024, 256]` cannot both be
    satisfied by a checkpoint whose linears are stored the other way round.
    """
    width, ffn, projection = spec["width"], spec["ffn"], spec["projection"]
    parameters = {
        "vision_model.embeddings.patch_embedding.weight": [
            width, 3, spec["patch"], spec["patch"],
        ],
        "vision_model.embeddings.class_embedding": [width],
        VISION_POSITIONS_FOLDED: [1, spec["grid"] ** 2 + 1, width],
        "vision_model.pre_layrnorm.weight": [width],
        "vision_model.pre_layrnorm.bias": [width],
        "vision_model.post_layernorm.weight": [width],
        "vision_model.post_layernorm.bias": [width],
        "text_model.embeddings.token_embedding.weight": [spec["vocab"], width],
        "text_model.embeddings.position_embedding.weight": [spec["context"], width],
        "text_model.final_layer_norm.weight": [width],
        "text_model.final_layer_norm.bias": [width],
    }
    projections = {
        "/visual_projection/MatMul": [width, projection],
        "/text_projection/MatMul": [width, projection],
    }
    for tower, count in (("vision", spec["vision_layers"]), ("text", spec["text_layers"])):
        for index in range(count):
            at = f"{tower}_model.encoder.layers.{index}"
            node = f"/{tower}_model/encoder/layers.{index}"
            for norm in ("layer_norm1", "layer_norm2"):
                parameters[f"{at}.{norm}.weight"] = [width]
                parameters[f"{at}.{norm}.bias"] = [width]
            for name in ("q_proj", "k_proj", "v_proj", "out_proj"):
                parameters[f"{at}.self_attn.{name}.bias"] = [width]
                projections[f"{node}/self_attn/{name}/MatMul"] = [width, width]
            parameters[f"{at}.mlp.fc1.bias"] = [ffn]
            parameters[f"{at}.mlp.fc2.bias"] = [width]
            projections[f"{node}/mlp/fc1/MatMul"] = [width, ffn]
            projections[f"{node}/mlp/fc2/MatMul"] = [ffn, width]
    return parameters, projections


def gemma4_inventory(spec):
    """Every named parameter Gemma 4's decoder export must hold, derived from `spec`.

    Derived, never transcribed as a list: a hand-written inventory of 900-odd tensors would be
    checked against the same file it was copied from. Building it from the constants means a
    disagreement between `config.json` and the export is what fails, which is the thing worth
    catching.

    Returns `(parameters, projections)` to match [`tinyclip_inventory`]'s shape; Gemma's
    projections are all named initializers, so the second is empty.
    """
    d = spec["d_model"]
    heads, kv_heads = spec["heads"], spec["kv_heads"]
    per_layer = spec["per_layer"]
    want = {
        "lm_head.MatMul.weight": [d, spec["vocab"]],
        "model.per_layer_projection.MatMul.weight": [d, per_layer * spec["layers"]],
        "model.per_layer_projection_norm.weight": [per_layer],
        # The export files the final norm under a phantom 36th layer.
        "model.layers.35.final_norm_layernorm.weight": [d],
        # Cut to `max_context` by the collector; asserted here at their shipped length.
        "cos_cache_local": [131072, spec["head_dim"] // 2],
        "sin_cache_local": [131072, spec["head_dim"] // 2],
        "cos_cache_global": [131072, spec["global_head_dim"] // 2],
        "sin_cache_global": [131072, spec["global_head_dim"] // 2],
    }
    for index in range(spec["layers"]):
        at = f"model.layers.{index}"
        full = index % 5 == 4
        owns = index < spec["owns_cache_layers"]
        dim = spec["global_head_dim"] if full else spec["head_dim"]
        inner = spec["ffn"] if owns else spec["ffn_wide"]
        want[f"{at}.input_layernorm.weight"] = [d]
        want[f"{at}.attn.q_norm.layernorm.weight"] = [dim]
        want[f"{at}.attn.q_proj.MatMul.weight"] = [d, heads * dim]
        if owns:
            want[f"{at}.attn.k_norm.layernorm.weight"] = [dim]
            want[f"{at}.attn.k_proj.MatMul.weight"] = [d, kv_heads * dim]
            want[f"{at}.attn.v_proj.MatMul.weight"] = [d, kv_heads * dim]
            want[f"/model/layers.{index}/attn/v_norm/ones_weight"] = [dim]
        want[f"{at}.attn.o_proj.MatMul.weight"] = [heads * dim, d]
        want[f"{at}.post_attention_layernorm.weight"] = [d]
        want[f"{at}.pre_feedforward_layernorm.weight"] = [d]
        want[f"{at}.mlp.gate_up_proj.MatMul.weight"] = [d, inner * 2]
        want[f"{at}.mlp.down_proj.MatMul.weight"] = [inner, d]
        want[f"{at}.post_feedforward_layernorm.weight"] = [d]
        want[f"{at}.per_layer.per_layer_input_gate.MatMul.weight"] = [d, per_layer]
        want[f"{at}.per_layer.per_layer_projection.MatMul.weight"] = [per_layer, d]
        want[f"{at}.post_per_layer_input_norm.weight"] = [d]
        want[f"{at}.layer_scalar"] = [1]
    return want, {}


def gemma4_embed_inventory(spec):
    """The two tables `embed_tokens.onnx` holds, and nothing else that matters."""
    return {
        "model.embed_tokens.weight": [spec["vocab"], spec["d_model"]],
        "model.embed_tokens_per_layer.weight": [
            spec["vocab"],
            spec["per_layer"] * spec["layers"],
        ],
    }, {}


# Which inventory `check_architecture` asserts against, for the ONNX-by-name graphs. The
# checkpoint graphs register theirs beside their collectors above; this is the same registry.
INVENTORIES.update(
    {
        "tinyclip": tinyclip_inventory,
        "gemma4_text": gemma4_inventory,
        "gemma4_embed": gemma4_embed_inventory,
        # The vision export names every weight `_to_copy_104`, so there is no inventory to check
        # against. `collect_gemma4_vision` asserts the node *numbering* instead, which is the
        # only structure that export actually carries.
        "gemma4_vision": lambda spec: ({}, {}),
        # The audio export is anonymised the same way, with two named survivors that are worth
        # pinning because both mark a place the structure could move: the bias proves output_proj
        # is still the one biased linear the exporter splits, and the depthwise kernel proves the
        # conformer convolution is still full-width and five taps.
        "gemma4_audio": lambda spec: (
            {
                "audio_tower.output_proj.bias": [spec["out_dim"]],
                "audio_tower.layers.0.lconv1d.depthwise_conv1d.weight": [spec["d_model"], 1, 5],
            },
            {},
        ),
    }
)


def check_architecture(model, graph_id_name, spec):
    """Refuse an ONNX export that is not the architecture `nets/<graph>.rs` hardcodes.

    The counterpart of [`check_checkpoint`], and the same argument: a wrong shape here is a wrong
    forward pass rather than a load failure, so it is asserted before anything is written. The op
    counts in [`EXPECTED_OPS`] are checked as well, by [`check_graph`] — this is the stricter of
    the two, because it fails on a renamed or resized parameter as well as on a missing one.

    `spec` is passed rather than looked up, as [`collect_tinyclip`]'s is, so the two cannot be
    given different ones.
    """
    parameters, projections = INVENTORIES[graph_id_name](spec)
    inits = {i.name: list(i.dims) for i in model.graph.initializer}
    nodes = {n.name: n for n in model.graph.node}

    lines = []
    for name, want in sorted(parameters.items()):
        got = inits.get(name)
        if got != want:
            lines.append(f"  {name}: {got} against {want}")
    for node_name, want in sorted(projections.items()):
        node = nodes.get(node_name)
        if node is None:
            lines.append(f"  {node_name}: no such node")
            continue
        held = [x for x in node.input if x in inits]
        if len(held) != 1 or inits[held[0]] != want:
            lines.append(f"  {node_name}: {[inits.get(h) for h in held]} against {want}")
    if lines:
        raise SystemExit(
            f"{graph_id_name}: the export is not the architecture "
            f"nets/{graph_id_name}.rs\nhardcodes. A wrong shape here is a wrong forward pass, "
            "not a load failure:\n" + "\n".join(lines[:12])
        )
    print(
        f"{len(parameters)} named parameters and {len(projections)} projections match the "
        f"{graph_id_name} architecture"
    )


def collect_tinyclip(model, spec):
    """TinyCLIP's export as the ordered tensor table `nets::tinyclip` indexes.

    Order is the contract, and it is forward order **per tower**, vision first:

        the patch kernel, the class token, the vision positions, `pre_layrnorm`
        each vision layer, then `post_layernorm`, then `visual_projection`
        the token embedding, the text positions
        each text layer, then `final_layer_norm`, then `text_projection`

    The export interleaves the two towers; this does not. See [`ARCHITECTURES`].

    # What is transposed, and what is not

    * A **`MatMul`** weight is `[in, out]` and becomes `[out, in, 1, 1]`, so every projection is
      the `1 x 1` convolution `Builder::conv_int8` already computes.
    * The **`Conv`** patch kernel is `[M, C, kH, kW]` already, which is this runtime's own layout,
      so it is emitted **verbatim**. The `[256, 3, 16, 16]` assertion in [`check_architecture`] is
      what says so; a transpose here would be a plausible-looking embedding.
    * The two **position tables** are `[positions, width]` and the vision one becomes
      `[width, 1, positions]`, because it is added to a sequence the device holds channel-major.
      The text one is **not** transposed: the host reads it, gathers the token rows and adds the
      two in f32, exactly as `nets::small100::embed_positions` does, so it never reaches a shader.

    # What has no bias, and what gets a synthesised one

    The patch embedding and both projections have none — `bias=False` on all three in
    `CLIPModel`. The shaders always add one, so each gets zeros, which is exact and is recorded in
    the digest key so an export that grows a bias is not read as this table.
    """
    width = spec["width"]
    inits = {i.name: i for i in model.graph.initializer}
    nodes = {n.name: n for n in model.graph.node}
    layers = []
    tensors = []
    fidelity = Fidelity()

    def array(name):
        return numpy_helper.to_array(inits[name]).astype(np.float32)

    def emit(op, name, key, added):
        layers.append(Layer(len(layers), op, name, key, len(tensors) - added, added))

    def matmul_weight(node_name):
        """The `[in, out]` initializer a projection's `MatMul` reads, by **node** name."""
        held = [x for x in nodes[node_name].input if x in inits]
        return numpy_helper.to_array(inits[held[0]]).astype(np.float32)

    def projection(node_name, name):
        """A `MatMul` as an int8 `[out, in, 1, 1]` kernel, its per-output scale and a zero bias."""
        weight = matmul_weight(node_name)
        inputs, outputs = weight.shape
        kernel, scale = fidelity.quantise(name, weight.T.reshape(outputs, inputs, 1, 1))
        tensors.extend([kernel, scale, np.zeros(outputs, dtype=np.float32)])
        emit(
            "Linear8",
            name,
            f"Linear8 w={list(kernel.shape)} scale={[outputs]} b={[outputs]} "
            "zp=0 dtype=int8 b0=synthesised",
            3,
        )

    def linear(node_name, bias_name, name):
        """[`projection`] with the export's own bias, which every layer projection has."""
        weight = matmul_weight(node_name)
        bias = array(bias_name)
        inputs, outputs = weight.shape
        kernel, scale = fidelity.quantise(name, weight.T.reshape(outputs, inputs, 1, 1))
        tensors.extend([kernel, scale, bias])
        emit(
            "Linear8",
            name,
            f"Linear8 w={list(kernel.shape)} scale={[outputs]} b={list(bias.shape)} "
            "zp=0 dtype=int8",
            3,
        )

    def layer_norm(name):
        gamma = array(f"{name}.weight")
        beta = array(f"{name}.bias")
        tensors.extend([gamma, beta])
        emit("LayerNorm", name, f"LayerNorm g={list(gamma.shape)} b={list(beta.shape)}", 2)

    def constant(name, values):
        tensors.append(values)
        emit("Constant", name, f"Constant t={list(values.shape)}", 1)

    def encoder_layer(tower, index):
        at = f"{tower}_model.encoder.layers.{index}"
        node = f"/{tower}_model/encoder/layers.{index}"
        # Pre-norm, as `CLIPEncoderLayer` is: the norm comes before the sublayer it feeds and the
        # residual skips both. Emitting in forward order is what lets the Rust walk the table.
        layer_norm(f"{at}.layer_norm1")
        for name in ("q_proj", "k_proj", "v_proj", "out_proj"):
            linear(
                f"{node}/self_attn/{name}/MatMul",
                f"{at}.self_attn.{name}.bias",
                f"{at}.self_attn.{name}",
            )
        layer_norm(f"{at}.layer_norm2")
        linear(f"{node}/mlp/fc1/MatMul", f"{at}.mlp.fc1.bias", f"{at}.mlp.fc1")
        linear(f"{node}/mlp/fc2/MatMul", f"{at}.mlp.fc2.bias", f"{at}.mlp.fc2")

    # --- the vision tower ---
    patch = "vision_model.embeddings.patch_embedding.weight"
    kernel, scale = fidelity.quantise(patch, array(patch))
    tensors.extend([kernel, scale, np.zeros(width, dtype=np.float32)])
    emit(
        "Conv8",
        patch,
        f"Conv8 w={list(kernel.shape)} scale={[width]} b={[width]} "
        f"k={[spec['patch'], spec['patch']]} s={[spec['patch'], spec['patch']]} "
        "p=[0, 0, 0, 0] zp=0 dtype=int8 b0=synthesised",
        3,
    )
    # `[width]` as the `[width, 1, 1]` a `Kind::Constant` wants: one position, every channel.
    token = array("vision_model.embeddings.class_embedding").reshape(width, 1, 1)
    constant("vision_model.embeddings.class_embedding", token)
    positions = np.squeeze(array(VISION_POSITIONS_FOLDED))
    constant(VISION_POSITIONS_FOLDED, positions.T.reshape(width, 1, positions.shape[0]).copy())
    layer_norm("vision_model.pre_layrnorm")
    for index in range(spec["vision_layers"]):
        encoder_layer("vision", index)
    layer_norm("vision_model.post_layernorm")
    projection("/visual_projection/MatMul", "visual_projection")

    # --- the text tower ---
    table = "text_model.embeddings.token_embedding.weight"
    rows = array(table)
    # Per **row**, which is per token: the host gathers one row and dequantises it by that row's
    # own scale, so this is the same shape of table `collect_small100`'s tied embedding is. The
    # export's own int8 file quantises it per *tensor* with a zero point of 226, which is neither
    # representable by `conv_int8.comp` nor recoverable by requantising.
    kernel, scale = fidelity.quantise(table, rows.reshape(rows.shape[0], rows.shape[1], 1, 1))
    tensors.extend([kernel, scale])
    emit(
        "Embed8",
        table,
        f"Embed8 t={list(kernel.shape)} scale={[rows.shape[0]]} zp=0 dtype=int8",
        2,
    )
    # Not transposed: the host reads this one. See the docstring.
    text_positions = array("text_model.embeddings.position_embedding.weight")
    constant("text_model.embeddings.position_embedding.weight", text_positions)
    for index in range(spec["text_layers"]):
        encoder_layer("text", index)
    layer_norm("text_model.final_layer_norm")
    projection("/text_projection/MatMul", "text_projection")

    fidelity.report()
    return layers, tensors


def dequant_block_quantized(quant_t, scales_t, zp_t):
    """4-bit block-quantised `[rows, cols]` as fp32, low nibble first.

    Shared by `MatMulNBits` linears (stored transposed `[N, K/block, 16]`,
    decoded to `[N, K]`) and `GatherBlockQuantized` position tables (stored
    flat `[rows, cols/2]`, decoded to `[rows, cols]`). The block count always
    comes from the scales table (`[rows, blocks]`), so both layouts meet here.
    Packing verified against onnxruntime's own MatMulNBits to 3.7e-3
    (analysis/prove_sign/).
    """
    dims = [int(d) for d in quant_t.dims]
    scales_dims = [int(d) for d in scales_t.dims]
    if len(scales_dims) != 2:
        raise SystemExit("block scales of rank " + str(len(scales_dims)))
    out_dim, per_col_blocks = scales_dims
    quant = numpy_helper.to_array(quant_t).reshape(-1)
    if len(dims) == 3:
        rows, _, bpb = dims
        assert rows == out_dim, (dims, scales_dims)
        block = bpb * 2
        raw = quant.reshape(out_dim, per_col_blocks, bpb)
    elif len(dims) == 2:
        rows, packed = dims
        assert rows == out_dim, (dims, scales_dims)
        raw = quant.reshape(out_dim, per_col_blocks, packed // per_col_blocks)
        block = (packed // per_col_blocks) * 2
    else:
        raise SystemExit("block-quantised tensor of rank " + str(len(dims)))
    in_dim = per_col_blocks * block
    scales = numpy_helper.to_array(scales_t).astype(np.float32)
    zp = numpy_helper.to_array(zp_t).reshape(-1).astype(np.uint8)
    vals = np.empty((out_dim, per_col_blocks, block), dtype=np.float32)
    vals[:, :, 0::2] = (raw & 0xF).astype(np.float32)
    vals[:, :, 1::2] = ((raw >> 4) & 0xF).astype(np.float32)
    zb = zp.reshape(out_dim, (per_col_blocks + 1) // 2)
    zf = np.empty((out_dim, per_col_blocks), dtype=np.float32)
    zf[:, 0::2] = (zb & 0xF).astype(np.float32)[:, : (per_col_blocks + 1) // 2]
    if per_col_blocks > 1:
        zf[:, 1::2] = ((zb >> 4) & 0xF).astype(np.float32)[:, : per_col_blocks // 2]
    if scales.shape != (out_dim, per_col_blocks):
        raise SystemExit(
            "scales " + str(list(scales.shape)) + ", not [" + str(out_dim) + ", " +
            str(per_col_blocks) + "]")
    return ((vals.reshape(out_dim, in_dim) - zf.repeat(block, axis=1)) *
            scales.repeat(block, axis=1))


def dequant_matmul_nbits(node, inits):
    """An ONNX `MatMulNBits` weight as fp32 `[K, N]`, matching a MatMul's `[in, out]`.

    The quant tensor is stored TRANSPOSED as `[N, K/block, 16]` (verified: a
    K=768 N=3072 node holds `[3072, 24, 16]`), decoded to `[N, K]` natively and
    transposed back.
    """
    attrs = {a.name: a for a in node.attribute}
    bits = attrs["bits"].i if "bits" in attrs else 4
    if bits != 4:
        raise SystemExit(node.name + ": MatMulNBits with " + str(bits) + " bits, not 4")
    native = dequant_block_quantized(inits[node.input[1]], inits[node.input[2]],
                                     inits[node.input[3]])
    k = attrs["K"].i if "K" in attrs else native.shape[1]
    n = attrs["N"].i if "N" in attrs else native.shape[0]
    if native.shape != (n, k):
        raise SystemExit(node.name + ": decoded " + str(list(native.shape)) + ", not [" +
                         str(n) + ", " + str(k) + "]")
    return native.T


def quantise_per_channel(kernel):
    """An fp32 kernel as `(int8, scale)`, symmetric and absmax, one scale per output channel.

    Every int8 tensor in this tree goes through here. Nothing reads a pre-quantised export:
    Supertonic's are fp32, and SMaLL-100's only int8 export quantises per tensor, which throws
    away the resolution this recovers - see [`CHECKPOINTS`].

    # Symmetric, with no zero point

    `conv_int8.comp` and `conv_point_int8.comp` compute `scale[o] * sum(int8_w * in)`: there is
    nowhere for a zero point to go, and adding one would cost a second pass over the taps to
    subtract `zp * sum(in)`. So the scale is `absmax / 127` and 0 maps to 0, which is also what
    onnxruntime's dynamic quantiser was measured to emit for SMaLL-100 — every entry of every
    weight zero point in that export is 0.

    127 rather than 128: the range is made symmetric by giving up the one extra negative code, so
    that `-absmax` and `+absmax` both round to a representable value. Using 128 would let a
    weight at exactly `+absmax` quantise to 128, which does not exist in int8.

    # Per output channel, not per tensor

    A row of the kernel is one output channel, and the scale multiplies that row's finished
    accumulator, so a scale per row costs nothing over a scalar — see `Builder::conv_int8`. It
    buys a great deal: a layer whose channels differ in range by 100x loses most of its
    resolution on the quiet ones under a single tensor-wide scale.

    # An all-zero channel

    Its absmax is 0, and `0 / 127` is a scale of 0, which is *correct* — every weight in the row
    is 0, so any scale reproduces it — but it is also a division by zero when computing the
    codes. Those rows are given a scale of 1 instead, which is exact for the same reason and is
    finite.
    """
    kernel = np.ascontiguousarray(kernel, dtype=np.float32)
    if kernel.ndim < 1:
        raise SystemExit(f"rank {kernel.ndim} kernel; quantisation needs an output axis")
    if not np.isfinite(kernel).all():
        raise SystemExit("a kernel holding a non-finite value cannot be quantised")
    rows = kernel.reshape(kernel.shape[0], -1)
    absmax = np.abs(rows).max(axis=1)
    scale = np.where(absmax > 0, absmax / 127.0, 1.0).astype(np.float32)
    # Round half away from zero, as `np.round` does not: it rounds half to even, which biases a
    # symmetric weight distribution towards the even codes.
    codes = np.floor(np.abs(rows) / scale[:, None] + 0.5) * np.sign(rows)
    quantised = np.clip(codes, -127, 127).astype(np.int8).reshape(kernel.shape)
    # The scale is stored as fp16 beside the weights, so the reconstruction the shader performs
    # uses the *rounded* scale. Rounding it here means the error reported below is the error the
    # device will have, not an optimistic one.
    scale = scale.astype(np.float16).astype(np.float32)
    # And the rounding itself can produce a zero, for a row whose absmax is small enough that
    # `absmax / 127` underflows fp16. A zero scale reconstructs the whole row as zero however
    # large its codes are, so the guard belongs **after** the round-trip, not before it. As in
    # `quantise_per_block`: such a row cannot be represented and is stored as zeros.
    scale = np.where(scale > 0, scale, 1.0)
    return quantised, scale


# Taps one int4 scale covers. Mirrors `weights::I4_BLOCK` and `I4_BLOCK` in `common.glsl`.
#
# 32 rather than 64: at 4.5 bits per weight against 4.25 the difference is 3% of a download, and
# `MIN_INT8_COSINE` is a strict gate that the wider block is the more likely to miss.
I4_BLOCK = 32

# Stands in for an absent `Clip` bound: past fp16's largest finite value, so clamping to it is a
# no-op for any number the tower can hold, and it still round-trips through the fp16 table the
# bounds are stored in.
FP16_LIMIT = 65504.0


def quantise_per_block(kernel, block=I4_BLOCK):
    """An fp32 kernel as `(int4, scale)`, symmetric and absmax, one scale per block of taps.

    # Why four bits need a finer scale than eight

    `quantise_per_channel` gives a whole output row one scale, which works at eight bits because
    127 codes still resolve a row whose taps vary by an order of magnitude. Four bits have seven
    positive codes. Under one row-wide scale a row containing a single large tap quantises almost
    everything else to zero, so the scale is per block of `block` consecutive taps instead.

    # The blocks run along the contraction axis

    A row is flattened to `in * kh * kw` and cut into blocks of `block`, which is the axis the
    shader's inner loop walks - so a block is contiguous in memory and the shader reads one scale
    per block rather than gathering. The last block of a row is short whenever `taps` is not a
    multiple of `block`, and is scaled on what it actually holds.

    # 7, not 8

    As `quantise_per_channel` uses 127 rather than 128: the range is made symmetric by giving up
    the extra negative code, so `-absmax` and `+absmax` both land on a representable value.
    `int4_at` sign-extends, so the stored range is -8..7 and only -8 is unused.

    Returns the codes shaped like `kernel` and a `(rows, blocks)` fp32 scale table, already
    rounded through fp16 so the error reported by the caller is the error the device will have.
    """
    kernel = np.ascontiguousarray(kernel, dtype=np.float32)
    if kernel.ndim < 1:
        raise SystemExit(f"rank {kernel.ndim} kernel; quantisation needs an output axis")
    if not np.isfinite(kernel).all():
        raise SystemExit("a kernel holding a non-finite value cannot be quantised")
    rows = kernel.reshape(kernel.shape[0], -1)
    count, taps = rows.shape
    blocks = (taps + block - 1) // block
    # Pad the tap axis so it divides into whole blocks. The padding is zero, which cannot raise a
    # block's absmax, so the last block's scale is decided by the real taps alone.
    padded = np.zeros((count, blocks * block), dtype=np.float32)
    padded[:, :taps] = rows
    grouped = padded.reshape(count, blocks, block)
    absmax = np.abs(grouped).max(axis=2)
    scale = np.where(absmax > 0, absmax / 7.0, 1.0).astype(np.float32)
    scale = scale.astype(np.float16).astype(np.float32)
    # A block whose absmax is small enough that `absmax / 7` underflows fp16 rounds to a scale of
    # **zero**, and then `0 / 0` is NaN and `NaN.astype(int8)` is undefined - a block of garbage
    # codes with nothing to show it. Such a block cannot be represented at all, so it is given a
    # scale of 1 and quantises to zeros, which is what the file would reconstruct anyway.
    scale = np.where(scale > 0, scale, 1.0)
    codes = np.floor(np.abs(grouped) / scale[:, :, None] + 0.5) * np.sign(grouped)
    codes = np.clip(codes, -7, 7).astype(np.int8)
    quantised = codes.reshape(count, blocks * block)[:, :taps].reshape(kernel.shape)
    return quantised, scale


def pack_int4(values):
    """Signed 4-bit codes as bytes, two per byte, **low nibble first**.

    An odd length leaves the final high nibble as padding, which is what `Dtype::bytes`'s
    `div_ceil` accounts for on the reader's side. Writing `len // 2` bytes instead would produce
    a file that parses - the bounds check would pass - and whose last element read as whatever
    followed it.
    """
    flat = np.ascontiguousarray(values, dtype=np.int8).reshape(-1)
    if flat.size and (flat.min() < -8 or flat.max() > 7):
        raise SystemExit(f"int4 codes out of range: {flat.min()}..{flat.max()}")
    nibbles = (flat.astype(np.uint8) & 0x0F).astype(np.uint8)
    if nibbles.size % 2:
        nibbles = np.concatenate([nibbles, np.zeros(1, dtype=np.uint8)])
    pairs = nibbles.reshape(-1, 2)
    return (pairs[:, 0] | (pairs[:, 1] << 4)).astype(np.uint8).tobytes()


class Int4:
    """A tensor to serialise as [`DTYPE_I4`].

    numpy has no four-bit dtype, so `build` cannot tell an int4 kernel from an int8 one by
    `tensor.dtype` the way it tells int8 from fp32. Wrapping it says so explicitly rather than
    inferring from a value range, which would misread an int8 kernel that happened to hold only
    small codes.

    Quacks like an array for the three attributes `build` needs.
    """

    def __init__(self, codes):
        self.codes = np.ascontiguousarray(codes, dtype=np.int8)
        self.shape = self.codes.shape
        self.ndim = self.codes.ndim
        self.size = self.codes.size


class Raw:
    """A tensor copied verbatim from another `.maml`: its table entry's dtype, shape and element
    count plus its payload bytes, so a re-packed file is bit-identical to its source."""

    def __init__(self, dtype, shape, size, payload):
        self.dtype_code = dtype
        self.shape = tuple(shape)
        self.ndim = len(self.shape)
        self.size = size
        self.payload = payload


def payload_bytes(dtype, size):
    """Payload length of a tensor of `size` elements stored as `dtype` (mirrors `Dtype::bytes`)."""
    if dtype == DTYPE_F16:
        return size * 2
    if dtype == DTYPE_I8:
        return size
    if dtype == DTYPE_I4:
        return (size + 1) // 2
    if dtype == DTYPE_Q2K:
        return (size + Q2K_BLOCK - 1) // Q2K_BLOCK * Q2K_BYTES
    raise SystemExit(f"unknown dtype {dtype}")


def build(layers, tensors, graph_id, onnx_sha256):
    """Serialise the tensor table and the data section.

    A tensor is fp16 unless it arrives as `int8` (bytes go through unchanged as
    [`DTYPE_I8`]) or as [`Int4`]/[`Q2K`] (packed/verbatim payloads as [`DTYPE_I4`]/
    [`DTYPE_Q2K`]). A quantised kernel's scale is a separate fp16 tensor that the
    caller has already placed after it.
    """
    table = bytearray()
    data = bytearray()
    for tensor in tensors:
        if tensor.ndim < 1 or tensor.ndim > 4:
            raise SystemExit(f"rank {tensor.ndim} tensor; the table holds 1..4")
        while len(data) % ALIGNMENT:
            data.append(0)
        offset = len(data)
        if isinstance(tensor, Raw):
            dtype = tensor.dtype_code
            if len(tensor.payload) != payload_bytes(dtype, tensor.size):
                raise SystemExit(f"a raw payload of {len(tensor.payload)} bytes for {tensor.size} taps")
            data.extend(tensor.payload)
        elif isinstance(tensor, Int4):
            dtype = DTYPE_I4
            data.extend(pack_int4(tensor.codes))
        elif isinstance(tensor, Q2K):
            # Verbatim superblocks: 84 bytes per 256 taps. The reader's `Dtype::bytes`
            # must agree on `len.div_ceil(256) * 84` or the last block reads neighbour
            # bytes at the right shape.
            dtype = DTYPE_Q2K
            payload = bytes(tensor.blocks)
            expect = tensor.size // Q2K_BLOCK * Q2K_BYTES
            if len(payload) != expect:
                raise SystemExit(
                    f"a Q2_K payload of {len(payload)} bytes for {tensor.size} taps, "
                    f"not {expect}"
                )
            data.extend(payload)
        elif tensor.dtype == np.int8:
            # Already quantised, so nothing to round: the bytes are the payload.
            dtype = DTYPE_I8
            data.extend(tensor.tobytes())
        else:
            dtype = DTYPE_F16
            half = np.ascontiguousarray(tensor, dtype=np.float32).astype(np.float16)
            if not np.isfinite(half).all():
                raise SystemExit(
                    f"a weight of shape {list(tensor.shape)} overflows fp16. "
                    "Storing this net at half precision needs per-tensor scaling."
                )
            data.extend(half.tobytes())
        dims = list(tensor.shape) + [0] * (4 - tensor.ndim)
        table.extend(struct.pack("<I", tensor.ndim))
        table.extend(struct.pack("<4I", *dims))
        table.extend(struct.pack("<III", dtype, offset, tensor.size))
    assert len(table) == len(tensors) * TENSOR_ENTRY_BYTES

    data_offset = HEADER_BYTES + len(table)
    header = bytearray()
    header.extend(MAGIC)
    header.extend(struct.pack("<III", FORMAT_VERSION, graph_id, len(tensors)))
    header.extend(onnx_sha256)
    header.extend(struct.pack("<II", data_offset, len(data)))
    header.extend(b"\0" * 8)
    assert len(header) == HEADER_BYTES

    return bytes(header) + bytes(table) + bytes(data), len(layers)


# --- TFLite --------------------------------------------------------------------
#
# A third input kind, after ONNX graphs and the PyTorch checkpoints in [`CHECKPOINTS`].
#
# The Now Playing models ship as TFLite flatbuffers and there is no ONNX export of them to
# convert through: they were recovered from an APK, and the only route to ONNX would be a
# TFLite -> ONNX converter whose op coverage would have to be trusted for the custom
# `CIRCULAR_BUFFER` and depthwise-with-multiplier layers this graph is mostly made of. Reading
# the flatbuffer here is both shorter and the version whose tensor order can be read off the
# file, which is the only thing the Rust actually depends on.
#
# Two differences from the ONNX path worth knowing:
#
# * A TFLite kernel is `[out, kh, kw, in]` and this runtime wants `[out, in / group, kh, kw]`,
#   so unlike the ONNX path there **is** a transpose here. It is per layer kind, in
#   [`collect_tflite`], and `nets::nnfp`'s `the_layer_table_matches_the_tflite_export` is the
#   assertion on the other side of it.
# * The weights arrive int8 with a scale per tensor and are **dequantised to fp16**, not
#   carried through as [`DTYPE_I8`]. At 172,576 parameters int8 would save nine kilobytes
#   against fp16 - 337 KB versus 346 KB - which does not pay for per-tensor int8 arithmetic
#   or for a second thing to verify. See [`INT8_GRAPHS`] for when it would.
TFLITE = {
    "nnfp": {
        # `music_detector.sound_model_2`, one subgraph. Note the file has 432 bytes of
        # container in front of the flatbuffer; [`open_tflite`] finds the magic rather than
        # assuming offset zero.
        "subgraph": 0,
        "input": [1, 1, 32, 1],
        "output": [1, 64],
        # The whole op census. `CIRCULAR_BUFFER` and the reshapes carry no weights and are
        # unrolled or relabelled by the Rust, so they appear here and nowhere else.
        "ops": {
            "CONV_2D": 10,
            "DEPTHWISE_CONV_2D": 2,
            "CIRCULAR_BUFFER": 6,
            "RESHAPE": 3,
        },
        # The weighted ops in graph order, which is the `.maml` tensor order and so the entire
        # contract with `nets::nnfp`. Each entry is the layout to emit, the kernel shape
        # **after** the transpose, and the bias length. Checked against the file, not assumed:
        # a re-export that changed a channel count fails here rather than at inference.
        "layers": [
            ("conv", [8, 1, 1, 4], 8),
            ("conv", [16, 8, 4, 1], 16),
            ("conv", [24, 16, 1, 4], 24),
            ("conv", [32, 24, 4, 1], 32),
            ("conv", [48, 32, 1, 4], 48),
            ("conv", [64, 48, 4, 1], 64),
            ("conv", [96, 64, 1, 4], 96),
            ("conv", [128, 96, 4, 1], 128),
            ("conv", [96, 128, 1, 2], 96),
            ("conv", [64, 96, 4, 1], 64),
            # The full-width depthwise with `depth_multiplier = 8`, read as a grouped
            # convolution: 512 outputs over 64 groups of one input channel each.
            ("depthwise", [512, 1, 1, 50], 512),
            # The final projection, which the Rust reads as two constants rather than as a
            # convolution's parameters because the export's preceding reshape is a channel
            # shuffle it cannot express. See `nets::nnfp::build`.
            ("shuffled", [1, 8, 64], 64),
        ],
    },
}

# Numeric TFLite tensor types this reads. Anything else is a graph we have not seen.
TFLITE_DTYPES = {0: "float32", 2: "int32", 7: "int16", 9: "int8"}


def open_tflite(path):
    """The flatbuffer at `path`, and a `{opcode index: name}` for its operator table.

    The magic is located rather than assumed at offset 0: `music_detector.sound_model_2` has
    432 bytes of container in front of it, and a reader that starts at zero sees garbage.
    """
    try:
        from tflite.BuiltinOperator import BuiltinOperator
        from tflite.Model import Model
    except ImportError:
        raise SystemExit("needs the TFLite schema bindings: python -m pip install tflite")

    with open(path, "rb") as handle:
        raw = handle.read()
    offset = 0
    if raw[4:8] != b"TFL3":
        found = raw.find(b"TFL3")
        if found < 4:
            raise SystemExit(f"{path} holds no TFL3 flatbuffer")
        offset = found - 4
    model = Model.GetRootAsModel(raw[offset:], 0)

    builtins = {v: k for k, v in vars(BuiltinOperator).items() if isinstance(v, int)}
    names = {}
    for index in range(model.OperatorCodesLength()):
        code = model.OperatorCodes(index)
        custom = code.CustomCode()
        # A `custom_code` string wins over the numeric code, rather than the documented
        # "builtin 32 means CUSTOM" test. Both Now Playing files get that number wrong:
        # `sound_model_2` stores builtin 0 - which reads as `ADD` - on all six
        # `CIRCULAR_BUFFER` entries while still carrying the name beside them, and
        # `sound_model` does the same to an `IF`. A builtin never has a `custom_code`, so
        # trusting the string is both safe and the only reading that survives these files.
        if custom:
            names[index] = custom.decode()
            continue
        # `builtin_code` is a later, wider field than `deprecated_builtin_code`, and an older
        # converter leaves it zero. Fall back rather than reading every old op as `ADD`.
        builtin = code.BuiltinCode() or code.DeprecatedBuiltinCode()
        names[index] = builtins.get(builtin, f"op{builtin}")
    return model, names


def tflite_tensor(model, graph, index):
    """Tensor `index` of `graph`, dequantised to fp32.

    Refuses per-axis quantisation rather than guessing which axis survives the transpose in
    [`collect_tflite`]. Nothing in the graphs read here uses it - every tensor in
    `sound_model_2` is per-tensor - so a file that does is a file this has not been read
    against, and should fail rather than be silently wrong along one axis.
    """
    tensor = graph.Tensors(index)
    shape = [tensor.Shape(axis) for axis in range(tensor.ShapeLength())]
    buffer = model.Buffers(tensor.Buffer())
    if buffer.DataLength() == 0:
        raise SystemExit(f"tensor {index} {shape} has no buffer, so it is not a constant")
    dtype = TFLITE_DTYPES.get(tensor.Type())
    if dtype is None:
        raise SystemExit(f"tensor {index} has TFLite type {tensor.Type()}, which is not read")
    values = np.frombuffer(buffer.DataAsNumpy().tobytes(), dtype=dtype).reshape(shape)

    quant = tensor.Quantization()
    if quant is None or quant.ScaleLength() == 0:
        return np.ascontiguousarray(values, dtype=np.float32)
    if quant.ScaleLength() != 1:
        raise SystemExit(
            f"tensor {index} is quantised per axis over {quant.ScaleLength()} scales. "
            "This reader only does per-tensor; see its docstring."
        )
    scale = float(quant.ScaleAsNumpy()[0])
    zero = float(quant.ZeroPointAsNumpy()[0]) if quant.ZeroPointLength() else 0.0
    # In f64 and back, so a large int32 bias does not lose its low bits to f32 before scaling.
    return ((values.astype(np.float64) - zero) * scale).astype(np.float32)


def tflite_options(operator, depthwise):
    """`(stride_h, stride_w, padding, depth_multiplier, activation)`, for the digest key.

    Recorded rather than acted on. The Rust hardcodes its own padding, because `sound_model_2`
    stores `Padding = VALID` on every convolution while recording output shapes that only
    `SAME` produces on the frequency axis - see `nets::nnfp`. Putting the stored value in the
    key means a re-export that *fixed* that inconsistency changes the digest and gets looked
    at, instead of quietly disagreeing with the hardcoded pass.
    """
    from tflite.Conv2DOptions import Conv2DOptions
    from tflite.DepthwiseConv2DOptions import DepthwiseConv2DOptions

    table = operator.BuiltinOptions()
    if table is None:
        return (0, 0, 0, 0, 0)
    options = DepthwiseConv2DOptions() if depthwise else Conv2DOptions()
    options.Init(table.Bytes, table.Pos)
    multiplier = options.DepthMultiplier() if depthwise else 1
    return (
        options.StrideH(),
        options.StrideW(),
        options.Padding(),
        multiplier,
        options.FusedActivationFunction(),
    )


def check_tflite(model, names, graph_id_name):
    """The graph's boundary shapes and op census against [`TFLITE`]."""
    spec = TFLITE[graph_id_name]
    graph = model.Subgraphs(spec["subgraph"])

    def shape_of(index):
        tensor = graph.Tensors(index)
        return [tensor.Shape(axis) for axis in range(tensor.ShapeLength())]

    got_in = [shape_of(graph.Inputs(i)) for i in range(graph.InputsLength())]
    if got_in != [spec["input"]]:
        raise SystemExit(f"graph inputs are {got_in}, expected [{spec['input']}]")
    got_out = [shape_of(graph.Outputs(i)) for i in range(graph.OutputsLength())]
    if got_out != [spec["output"]]:
        raise SystemExit(f"graph outputs are {got_out}, expected [{spec['output']}]")

    counts = {}
    for index in range(graph.OperatorsLength()):
        name = names[graph.Operators(index).OpcodeIndex()]
        counts[name] = counts.get(name, 0) + 1
    if counts != spec["ops"]:
        only_got = {k: v for k, v in counts.items() if spec["ops"].get(k) != v}
        only_want = {k: v for k, v in spec["ops"].items() if counts.get(k) != v}
        raise SystemExit(f"op counts differ: got {only_got}, expected {only_want}")


def collect_tflite(model, names, spec):
    """`(layers, tensors)` for a TFLite graph, in the order the Rust forward pass reads them.

    Graph order, weighted ops only: kernel then bias, exactly as the ONNX collector emits a
    `Conv`. The transpose from TFLite's `[out, kh, kw, in]` into this runtime's
    `[out, in / group, kh, kw]` happens here, per layer kind.
    """
    graph = model.Subgraphs(spec["subgraph"])
    weighted = []
    for index in range(graph.OperatorsLength()):
        operator = graph.Operators(index)
        name = names[operator.OpcodeIndex()]
        if name in ("CONV_2D", "DEPTHWISE_CONV_2D"):
            weighted.append((index, operator, name))
    if len(weighted) != len(spec["layers"]):
        raise SystemExit(
            f"{len(weighted)} weighted ops, expected {len(spec['layers'])}. The export's "
            "layer count changed, so the hardcoded forward pass no longer describes it."
        )

    layers, tensors = [], []
    for position, ((at, operator, op_name), want) in enumerate(zip(weighted, spec["layers"])):
        kind, want_kernel, want_bias = want
        kernel = tflite_tensor(model, graph, operator.Inputs(1))
        bias = tflite_tensor(model, graph, operator.Inputs(2))
        stored = list(kernel.shape)
        options = tflite_options(operator, op_name == "DEPTHWISE_CONV_2D")

        if kind == "conv":
            # `[out, kh, kw, in]` to `[out, in, kh, kw]`.
            kernel = kernel.transpose(0, 3, 1, 2)
        elif kind == "depthwise":
            # A depthwise kernel is `[1, kh, kw, out]`, where `out` is `in * depth_multiplier`
            # indexed as `channel * multiplier + m`. Moving that axis to the front gives
            # `[out, 1, kh, kw]`, which is what a grouped convolution with one input channel
            # per group reads - and the `channel * multiplier + m` ordering is exactly the
            # group ordering the shader uses, so nothing has to be reordered within it.
            kernel = kernel.transpose(3, 0, 1, 2)
        elif kind == "shuffled":
            # Read as constants, not as a convolution: `[1, 1, 8, 64]` to `[1, 8, 64]` and the
            # bias to `[64, 1, 1]`, which is the rank a constant has. Both are relabellings of
            # the same bytes in the same order.
            kernel = kernel.reshape(kernel.shape[1:])
            bias = bias.reshape(bias.shape[0], 1, 1)
        else:
            raise SystemExit(f"layer {position} has unknown kind {kind!r}")
        kernel = np.ascontiguousarray(kernel, dtype=np.float32)

        if list(kernel.shape) != want_kernel:
            raise SystemExit(
                f"layer {position} ({op_name} at op {at}) is {list(kernel.shape)} after the "
                f"transpose, expected {want_kernel}"
            )
        if bias.size != want_bias:
            raise SystemExit(f"layer {position} bias is {bias.size} long, expected {want_bias}")

        key = (
            f"TFLite{op_name} w={stored} b={[want_bias]} "
            f"s={list(options[:2])} p={options[2]} m={options[3]} a={options[4]}"
        )
        layers.append(Layer(position, op_name, f"op{at}", key, len(tensors), 2))
        tensors.append(kernel)
        tensors.append(np.ascontiguousarray(bias, dtype=np.float32))
    return layers, tensors


def main():
    parser = argparse.ArgumentParser(description=SPEC.splitlines()[0])
    parser.add_argument(
        "model", help="an ONNX export, a .safetensors for a CHECKPOINTS graph, or a .tflite"
    )
    parser.add_argument("--graph", required=True, choices=sorted(GRAPHS))
    parser.add_argument("-o", "--out", help="where to write the .maml")
    parser.add_argument("--check", action="store_true", help="validate only")
    parser.add_argument("--print-layers", action="store_true")
    parser.add_argument("--print-digest", action="store_true")
    args = parser.parse_args()

    source_sha256 = hashlib.sha256()
    with open(args.model, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            source_sha256.update(block)
    source_size = os.path.getsize(args.model)

    if args.graph in TFLITE:
        model, names = open_tflite(args.model)
        check_tflite(model, names, args.graph)
        layers, tensors = collect_tflite(model, names, TFLITE[args.graph])
    elif args.graph in GGUF_GRAPHS:
        get, shapes = open_gguf(args.model)
        check_gguf(shapes, "madlad400")
        layers, tensors = collect_madlad_q2k(get, CHECKPOINTS["madlad400"])
    elif args.graph in CHECKPOINTS:
        get, shapes = open_checkpoint(args.model)
        check_checkpoint(shapes, args.graph)
        layers, tensors = COLLECTORS[args.graph](get, CHECKPOINTS[args.graph])
    elif args.graph in ARCHITECTURES:
        model = onnx.load(args.model)
        check_graph(model, args.graph)
        check_architecture(model, args.graph, ARCHITECTURES[args.graph])
        if args.graph == "gemma4_text":
            layers, tensors = collect_gemma4(model, ARCHITECTURES[args.graph])
        elif args.graph == "gemma4_embed":
            layers, tensors = collect_gemma4_embed(model, ARCHITECTURES[args.graph])
        elif args.graph == "gemma4_vision":
            layers, tensors = collect_gemma4_vision(model, ARCHITECTURES[args.graph])
        elif args.graph == "gemma4_audio":
            layers, tensors = collect_gemma4_audio(model, ARCHITECTURES[args.graph])
        else:
            layers, tensors = collect_tinyclip(model, ARCHITECTURES[args.graph])
    else:
        model = onnx.load(args.model)
        check_graph(model, args.graph)
        layers, tensors = collect_layers(
            model, MODULES.get(args.graph), quantise=args.graph in INT8_GRAPHS
        )

    digest = layer_table_digest(layers)
    if args.print_digest:
        print(f'    "{args.graph}": "{digest}",')
    pinned = EXPECTED_DIGEST.get(args.graph)
    if pinned is None:
        message = (
            f"{args.graph} has no pinned layer table digest. Add\n"
            f'    "{args.graph}": "{digest}",\n'
            "to EXPECTED_DIGEST once the Rust net agrees with this table, so that a later\n"
            "change to either side fails loudly instead of shifting every tensor."
        )
        if not args.print_digest:
            raise SystemExit(message)
        print(message, file=sys.stderr)
    elif digest != pinned:
        message = (
            f"layer table digest {digest}\n"
            f"           pinned  {pinned}\n"
            "The ordered Conv/ConvTranspose table changed, so the hardcoded forward\n"
            f"pass in library/ml/src/main/rust/src/nets/{args.graph}.rs no longer\n"
            "matches this export. Re-read it with --print-layers, update the Rust,\n"
            "then re-pin with --print-digest."
        )
        if not args.print_digest:
            raise SystemExit(message)
        print(message, file=sys.stderr)

    if args.print_layers:
        for layer in layers:
            print(f"{layer.index:4d} t{layer.first_tensor:<4d} {layer.key()}  # {layer.name}")

    blob, count = build(layers, tensors, GRAPHS[args.graph], source_sha256.digest())
    if args.out:
        with open(args.out, "wb") as f:
            f.write(blob)
    print(
        f"{args.graph}: {count} layers, {len(tensors)} tensors, "
        f"{len(blob)} bytes ({source_size} fp32 in)"
    )


if __name__ == "__main__":
    main()
