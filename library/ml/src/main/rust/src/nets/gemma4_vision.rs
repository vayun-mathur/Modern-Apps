//! Gemma 4's vision tower: 16 layers over image patches, out as soft tokens for the decoder.
//!
//! Restores the image input that `com.google.ai.edge.litertlm` used to provide. The decoder in
//! [`super::gemma4`] is text-only; this produces the `[n, 1536]` block that stands in for an
//! image in its prompt.
//!
//! # The layer is the decoder's, with three differences
//!
//! Same pre-norm, same q/k/v norms, same gated feed-forward. What differs:
//!
//! * **Multi-head, not multi-query.** Twelve heads and twelve KV heads, so no grouping.
//! * **Two-dimensional RoPE.** A patch has a row and a column, so a 64-wide head is two 32-wide
//!   blocks rotated by each - see [`super::Builder::rotary_axes`].
//! * **Clipped linears.** `use_clipped_linears` in the config, and 11 `Clip`s a layer in the
//!   export, each with its own calibrated bounds. They bound activations that would otherwise
//!   leave fp16's range; dropping them changes every number downstream.
//!
//! # What the host does
//!
//! The export takes `pixel_values [patches, 768]` - already patchified, 16x16x3 - and
//! `pixel_position_ids [patches, 2]`. So the patch extraction, the position ids and the two
//! learned position-embedding gathers (a `[10240, 768]` table) are host work, as the decoder's
//! embedding is. That is not a simplification made here: it is what the export's own inputs are.
//! [`prepare`] does all of it.
//!
//! # The grid follows the image, and the padding does not come with it
//!
//! `Gemma4ImageProcessor` resizes an image to at most `max_patches(budget)` patches preserving
//! its aspect ratio, rounding each side down to a multiple of `POOL * PATCH` pixels, and then
//! **pads** the patch sequence to that maximum so a batch stacks. The padding is why the export
//! carries a sentinel of `-1` in the position ids, an additive `-65504` attention mask, and a
//! pooling written as a masked matrix multiply with a `GatherND` after it.
//!
//! None of that is needed here. A plan is recorded per shape anyway, so this records one per
//! [`Grid`] and passes the real patches with nothing appended. Every pooled cell is then exactly
//! full, the mask is all ones, and the pooling is an ordinary average - see [`pool`]. The price
//! is re-recording when the aspect ratio changes, which against sixteen layers over a couple of
//! thousand patches does not register.
//!
//! # The tensor order is the contract
//!
//! As everywhere else in this tree, the `.maml` is an ordered table with no names, and
//! `maml_convert.collect_gemma4_vision` writes it in exactly the order [`declare_layer`] reads
//! it. The export's initializers are anonymised (`_to_copy_104`), so unlike the decoder the
//! converter cannot match by name and walks the graph in topological order instead - which makes
//! agreeing on this order more load-bearing here, not less.
use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// Channels through the tower. `hidden_size` in `vision_config`.
pub const D_MODEL: u32 = 768;

/// Attention heads. Ordinary multi-head: `num_key_value_heads` is the same.
pub const HEADS: u32 = 12;

/// Channels per head, which the 2-D rotary splits into two blocks of 32.
pub const HEAD_DIM: u32 = 64;

/// Blocks a head's rotary is split into: one for the patch row, one for its column.
pub const ROPE_AXES: u32 = 2;

/// Feed-forward width. `intermediate_size`.
pub const FFN: u32 = 3072;

/// Transformer layers. `num_hidden_layers`.
pub const LAYERS: usize = 16;

/// Patch side, in pixels. A patch is `16 * 16 * 3 = 768` values, which is why [`D_MODEL`] and the
/// patch projection's input width coincide - a coincidence, not a constraint.
pub const PATCH: u32 = 16;

/// The epsilon in every RMS norm. `rms_norm_eps`.
pub const EPSILON: f32 = 1e-6;

/// Whether `q_norm` and `k_norm`'s gammas already carry the attention scale.
///
/// They do, and this is a contract note rather than a switch: [`layer`] realises it by calling
/// [`Builder::attn_scores_prescaled`]. The export has no `Mul` between `q_proj` and the score
/// matmul - the path is `q_proj -> Clip -> q_norm -> rotary -> MatMul` - so there is no
/// `1 / sqrt(64)` to reproduce. The gammas are uniform scalars that differ per layer
/// (0.406, 0.355, 0.381 ...) and whose `q * k` product is 0.500 in every one of the sixteen, so
/// the scaling is trained rather than derived.
///
/// The same trap as the decoder's `gemma4::Q_NORM_CARRIES_SCALE`, and it fails the same silent
/// way: eight times too small, no shape error, every layout test green.
pub const SCALE_IS_IN_THE_NORMS: bool = true;

/// What the decoder reads: `text_config.hidden_size`.
pub const OUT_DIM: u32 = 1536;

/// Rows in the learned position table, gathered on the host. `position_embedding_size`.
pub const POSITIONS: u32 = 10240;

/// Patches averaged together per axis by the pooling. `pooling_kernel_size`.
pub const POOL: u32 = 3;

/// Soft-token budgets `Gemma4ImageProcessor` accepts, smallest first.
///
/// The budget decides everything downstream: an image is resized to at most `budget * POOL *
/// POOL` patches, and the tower's cost is quadratic in that. It is a genuine choice rather than a
/// constant because the top of this range does not fit on a phone - see [`Grid::for_image`].
pub const SOFT_TOKEN_BUDGETS: [u32; 5] = [70, 140, 280, 560, 1120];

/// The budget the reference processor defaults to, and what parity is measured at.
///
/// `default_output_length` in `vision_config`, and the top-level `vision_soft_tokens_per_image`.
/// At this budget a square image is 48x48 patches, whose two `[12, 2304, 2304]` score maps alone
/// are 254 MB of arena. [`SOFT_TOKEN_BUDGETS`]`[0]` is 64 soft tokens and 16 MB.
pub const DEFAULT_SOFT_TOKENS: u32 = 280;

/// The most patches an image is resized down to at `soft_tokens`.
pub const fn max_patches(soft_tokens: u32) -> u32 {
    soft_tokens * POOL * POOL
}

/// The base of the two-dimensional rotary. `vision_config.rope_parameters.rope_theta`.
pub const ROPE_THETA: f32 = 100.0;

/// The side of the block an image is resized to a multiple of, in pixels.
///
/// `POOL * PATCH`. Both axes are rounded down to a multiple of this so that the patch grid
/// divides by [`POOL`] exactly - which is what lets the pooling be an ordinary average over a
/// grid rather than the export's masked matrix. See [`Grid::for_image`].
pub const SIDE_MULTIPLE: u32 = POOL * PATCH;

/// Tensors one clip contributes: a `[2]` fp16 pair, minimum first.
const CLIP_TENSORS: usize = 1;

/// Clips in one layer. Counted off the export, not guessed - see the module docs.
const CLIPS_PER_LAYER: usize = 11;

/// Quantised projections in one layer: q, k, v, o, gate, up, down.
const PROJECTIONS_PER_LAYER: usize = 7;

/// Tensors one quantised projection contributes: kernel, per-block scale, bias.
const PROJECTION_TENSORS: usize = 3;

/// Tensors one **unquantised** projection contributes: kernel and bias, as [`Builder::conv`] reads
/// them. See [`PATCH_PROJECTION`].
const DENSE_TENSORS: usize = 2;

/// Unquantised tensors in one layer: four `d_model` norms and three `head_dim` ones.
const PLAIN_PER_LAYER: usize = 7;

/// Tensors one layer contributes, in file order.
const LAYER_TENSORS: usize = PLAIN_PER_LAYER
    + CLIPS_PER_LAYER * CLIP_TENSORS
    + PROJECTIONS_PER_LAYER * PROJECTION_TENSORS;

/// The patch projection, `[768, 768]` in the export, held at **fp16**.
///
/// # Why the two ends are not quantised
///
/// Everything between them is int4, which costs the tower far more than it costs the decoder:
/// sixteen layers turn a per-layer cosine of 0.9999 into 0.946 at the output. Measuring where
/// that comes from puts a disproportionate share at the two ends rather than spread evenly -
/// the patch projection is already at 0.9952 before layer 0 has run, and the output projection
/// alone takes the pooled state from 0.9747 to 0.9481.
///
/// Both are small: `768 x 768` and `1536 x 768` against sixteen layers of `4 x 768 x 768` plus
/// `3 x 768 x 3072`. Holding the pair at fp16 costs about 2.7 MB on a 95 MB file and removes the
/// error at the point where it has the whole tower left to be amplified through, and at the point
/// where nothing downstream can average it away.
pub const PATCH_PROJECTION: usize = 0;

/// The trailing norm before the output projection. Its gamma is all ones in the export, which is
/// not a no-op: an RMS norm still divides by the RMS.
pub const FINAL_NORM: usize = PATCH_PROJECTION + DENSE_TENSORS;

/// The projection to the decoder's width, `[1536, 768]`, held at **fp16**. See
/// [`PATCH_PROJECTION`].
pub const OUT_PROJECTION: usize = FINAL_NORM + 1;

/// The learned position table for a patch's **column**, `[10240, 768]`, read a row at a time.
///
/// Host-read for the same reason the decoder's embedding is: a pass needs a handful of rows out
/// of ten thousand, and binding the whole table to gather them would be absurd. Stored as an int4
/// triple like every other large tensor here.
///
/// Column and not row: `pixel_position_ids` is built by the reference preprocessor as
/// `meshgrid(arange(width), arange(height), indexing="xy")`, so component **0 is the column**,
/// and this is the table the export gathers with component 0. Getting the pair the wrong way
/// round transposes every image without changing a single shape.
pub const COLUMN_POSITIONS: usize = OUT_PROJECTION + DENSE_TENSORS;

/// The learned position table for a patch's **row**, `[10240, 768]`. See [`COLUMN_POSITIONS`].
pub const ROW_POSITIONS: usize = COLUMN_POSITIONS + PROJECTION_TENSORS;

/// Tensors before any layer.
const SHARED_TENSORS: usize = ROW_POSITIONS + PROJECTION_TENSORS;

/// Where the layers start.
const LAYER0: usize = SHARED_TENSORS;

/// Total tensors the `.maml` holds, and the count `maml_convert.py` must write.
pub const TENSORS: usize = SHARED_TENSORS + LAYERS * LAYER_TENSORS;

/// The first tensor of layer `index`.
pub fn layer_at(index: usize) -> usize {
    LAYER0 + index * LAYER_TENSORS
}

/// Declare every tensor of layer `index` against `weights`, in file order.
///
/// The order is the export's own topological order, which is what the converter walks. Reading
/// the clips as part of the sequence rather than collecting them separately is deliberate: it is
/// the only thing that keeps the two sides in step when the names carry no information.
pub fn declare_layer(weights: &dyn WeightSource, index: usize) -> Result<(), String> {
    let at = layer_at(index);
    let mut next = at;
    let plain = |dims: &[u32], n: &mut usize| -> Result<(), String> {
        let here = *n;
        *n += 1;
        weights.shaped(here, dims).map(|_| ())
    };
    let clip = |n: &mut usize| -> Result<(), String> {
        let here = *n;
        *n += CLIP_TENSORS;
        weights.shaped(here, &[2]).map(|_| ())
    };

    plain(&[D_MODEL], &mut next)?; // pre-attention norm
    clip(&mut next)?;
    projection(weights, &mut next, HEADS * HEAD_DIM, D_MODEL)?; // q_proj
    projection(weights, &mut next, HEADS * HEAD_DIM, D_MODEL)?; // k_proj
    projection(weights, &mut next, HEADS * HEAD_DIM, D_MODEL)?; // v_proj
    clip(&mut next)?;
    clip(&mut next)?;
    clip(&mut next)?;
    plain(&[HEAD_DIM], &mut next)?; // q_norm
    plain(&[HEAD_DIM], &mut next)?; // k_norm
    plain(&[HEAD_DIM], &mut next)?; // v_norm
    clip(&mut next)?;
    projection(weights, &mut next, D_MODEL, HEADS * HEAD_DIM)?; // o_proj
    clip(&mut next)?;
    plain(&[D_MODEL], &mut next)?; // post-attention norm
    plain(&[D_MODEL], &mut next)?; // pre-feed-forward norm
    clip(&mut next)?;
    projection(weights, &mut next, FFN, D_MODEL)?; // gate
    projection(weights, &mut next, FFN, D_MODEL)?; // up
    clip(&mut next)?;
    clip(&mut next)?;
    clip(&mut next)?;
    projection(weights, &mut next, D_MODEL, FFN)?; // down
    clip(&mut next)?;
    plain(&[D_MODEL], &mut next)?; // post-feed-forward norm
    if next != layer_at(index + 1) {
        return Err(format!(
            "vision layer {index} declared {} tensors, not the {} its span allows",
            next - at,
            layer_at(index + 1) - at
        ));
    }
    Ok(())
}

/// One quantised `1x1` projection: kernel, per-block scale, bias.
///
/// int4 like the decoder's, and for the same reason - the tower is 337 MB at fp16 and 99 MB at
/// four bits, on top of a download that is already large. The two projections at the ends are the
/// exception; see [`PATCH_PROJECTION`].
fn projection(
    weights: &dyn WeightSource,
    next: &mut usize,
    out: u32,
    inp: u32,
) -> Result<(), String> {
    weights.shaped_words(*next, &[out, inp, 1, 1])?;
    let blocks = inp.div_ceil(crate::weights::I4_BLOCK);
    weights.shaped(*next + 1, &[out, blocks])?;
    weights.shaped(*next + 2, &[out])?;
    *next += PROJECTION_TENSORS;
    Ok(())
}

/// One **unquantised** `1x1` projection: kernel and bias, the pair [`Builder::conv`] reads.
fn dense(
    weights: &dyn WeightSource,
    next: &mut usize,
    out: u32,
    inp: u32,
) -> Result<(), String> {
    weights.shaped(*next, &[out, inp, 1, 1])?;
    weights.shaped(*next + 1, &[out])?;
    *next += DENSE_TENSORS;
    Ok(())
}

/// Declare the tensors that sit outside any layer, in file order.
pub fn declare_shared(weights: &dyn WeightSource) -> Result<(), String> {
    let mut next = PATCH_PROJECTION;
    dense(weights, &mut next, D_MODEL, D_MODEL)?;
    weights.shaped(next, &[D_MODEL])?;
    next += 1;
    dense(weights, &mut next, OUT_DIM, D_MODEL)?;
    for _ in 0..2 {
        weights.shaped_words(next, &[POSITIONS, D_MODEL, 1, 1])?;
        weights.shaped(next + 1, &[POSITIONS, D_MODEL.div_ceil(crate::weights::I4_BLOCK)])?;
        weights.shaped(next + 2, &[POSITIONS])?;
        next += PROJECTION_TENSORS;
    }
    if next != SHARED_TENSORS {
        return Err(format!("{next} shared tensors, not {SHARED_TENSORS}"));
    }
    Ok(())
}

/// The `[in, out]` shape the export holds for a kernel declared here as `dims`.
pub fn as_exported(dims: &[u32]) -> Vec<u32> {
    match dims {
        [out, inp, 1, 1] => vec![*inp, *out],
        other => other.to_vec(),
    }
}

/// The patch grid one image is resized to, in patches.
///
/// Both extents are multiples of [`POOL`], which is what the reference preprocessor guarantees by
/// rounding the pixel dimensions down to a multiple of [`SIDE_MULTIPLE`]. That guarantee is the
/// whole reason the pooling below is an ordinary average: with it, every pooled cell holds
/// exactly `POOL * POOL` patches and there is nothing to mask.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Grid {
    /// Patch rows, a multiple of [`POOL`].
    pub rows: u32,
    /// Patch columns, a multiple of [`POOL`].
    pub cols: u32,
}

impl Grid {
    /// The grid `Gemma4ImageProcessor` resizes a `width x height` image to at `soft_tokens`.
    ///
    /// Ported from `get_aspect_ratio_preserving_size`: scale so the area is at most
    /// `max_patches(soft_tokens)` patches, then round each side **down** to a multiple of
    /// [`SIDE_MULTIPLE`]. Rounding down rather than to nearest is what keeps the result inside
    /// the budget; the two fallbacks cover a sliver so extreme that one side rounds away.
    ///
    /// `soft_tokens` must be one of [`SOFT_TOKEN_BUDGETS`]. It is an argument rather than a
    /// constant because the cost is quadratic in the patch count and the reference default does
    /// not fit in a phone's budget - see [`DEFAULT_SOFT_TOKENS`].
    pub fn for_image(width: u32, height: u32, soft_tokens: u32) -> Result<Grid, String> {
        if !SOFT_TOKEN_BUDGETS.contains(&soft_tokens) {
            return Err(format!(
                "a budget of {soft_tokens} soft tokens, not one of {SOFT_TOKEN_BUDGETS:?}"
            ));
        }
        if width == 0 || height == 0 {
            return Err(format!("an image of {width}x{height}"));
        }
        let (w, h) = (f64::from(width), f64::from(height));
        let budget = f64::from(max_patches(soft_tokens)) * f64::from(PATCH) * f64::from(PATCH);
        let factor = (budget / (w * h)).sqrt();
        let side = f64::from(SIDE_MULTIPLE);
        let mut target_w = (factor * w / side).floor() as u32 * SIDE_MULTIPLE;
        let mut target_h = (factor * h / side).floor() as u32 * SIDE_MULTIPLE;
        // A sliver: one side is so much longer that the other rounds to nothing. Give the short
        // side one block and cap the long one, as the reference does.
        let longest = soft_tokens * SIDE_MULTIPLE;
        if target_h == 0 && target_w == 0 {
            return Err(format!("{width}x{height} resizes to nothing"));
        } else if target_h == 0 {
            target_h = SIDE_MULTIPLE;
            target_w = ((w / h).floor() as u32 * SIDE_MULTIPLE).max(SIDE_MULTIPLE).min(longest);
        } else if target_w == 0 {
            target_w = SIDE_MULTIPLE;
            target_h = ((h / w).floor() as u32 * SIDE_MULTIPLE).max(SIDE_MULTIPLE).min(longest);
        }
        let grid = Grid::new(target_h / PATCH, target_w / PATCH)?;
        if grid.soft_tokens() > soft_tokens {
            return Err(format!(
                "{width}x{height} resolved to {} soft tokens, past the {soft_tokens} asked for",
                grid.soft_tokens()
            ));
        }
        Ok(grid)
    }

    /// A grid of `rows x cols` patches, refusing one the pooling could not tile.
    pub fn new(rows: u32, cols: u32) -> Result<Grid, String> {
        let grid = Grid { rows, cols };
        if rows == 0 || cols == 0 {
            return Err(format!("a patch grid of {rows}x{cols}"));
        }
        if rows % (POOL) != 0 || cols % (POOL) != 0 {
            return Err(format!(
                "a {rows}x{cols} patch grid does not divide by the pooling kernel {POOL}, so a \
                 pooled cell would straddle the edge"
            ));
        }
        let ceiling = max_patches(SOFT_TOKEN_BUDGETS[SOFT_TOKEN_BUDGETS.len() - 1]);
        if grid.patches() > ceiling {
            return Err(format!(
                "{rows}x{cols} is {} patches, past the {ceiling} the largest budget allows",
                grid.patches()
            ));
        }
        Ok(grid)
    }

    /// Patches in the grid, and so the tower's sequence length.
    pub const fn patches(&self) -> u32 {
        self.rows * self.cols
    }

    /// Soft tokens the tower emits for this grid, one per pooled cell.
    pub const fn soft_tokens(&self) -> u32 {
        (self.rows / POOL) * (self.cols / POOL)
    }

    /// The `(width, height)` in pixels an image must be resized to to produce this grid.
    pub const fn pixels(&self) -> (u32, u32) {
        (self.cols * PATCH, self.rows * PATCH)
    }
}

/// Which pass [`build`] emits.
///
/// The grid is part of the key because a plan is recorded at one shape and the grid follows the
/// image's aspect ratio. [`crate::vulkan::Reshaped`] re-records when the key changes, which for a
/// tower this size is far cheaper than the pass itself.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// One image: a grid of patches in, [`Grid::soft_tokens`] soft tokens out.
    Image(Grid),
    /// Stop after `layers` layers and hand back the hidden state, for parity bisection.
    ///
    /// The whole-tower comparison in `examples/check_gemma4_vision_parity.rs` can only say that
    /// the answer moved; this says where. `layers: 0` stops before layer 0, so it reports the
    /// patch projection and the position embedding on their own.
    ///
    /// Outputs are `[hidden, pooled]`: the state after `layers` layers, and [`pool`] applied to
    /// it. Pooling a partial state is meaningless as a value but exact as a check, and having
    /// both means a disagreement can be placed either side of the pooling.
    Trace { grid: Grid, layers: usize },
}

impl Mode {
    /// The patch grid this pass is recorded for.
    pub const fn grid(self) -> Grid {
        match self {
            Mode::Image(grid) | Mode::Trace { grid, .. } => grid,
        }
    }
}

include!("gemma4_vision_part1.rs");
include!("gemma4_vision_part2.rs");

#[cfg(test)]
#[path = "gemma4_vision_tests.rs"]
mod gemma4_vision_tests;