// The Gemma-4 device gather: embedding + per-layer combine on the device.
//
// Part of `nets::gemma4`. The host `gather` (`gemma4_part4.rs`) costs
// ~45-50 ms a token: 36 int4-row reads (working table + 35 per-layer mmap
// tables) plus the `SHARED_PROJ` host SGEMV (~13.8M MACs: 35 groups of
// 256x1536) plus grouped norms. This pass runs the same computation on the
// device over the EMBED file: one token-id input in, `(hidden, combined)`
// out, upload-once and submit-per-token.
//
// # What it computes (exactly what `gather`+`combine` compute on the host)
//
// - `hidden` = int4 row `token` of the working table (TOKENS triple).
// - `embedded` = concat of int4 row `token` (row 0 for PLACEHOLDERS) from
//   each of the 35 per-layer tables.
// - `projected = W @ hidden`, one 256-wide group per layer row of the int8
//   SHARED_PROJ `[8960, 1536]` table.
// - `combined = (16 * embedded + rms_norm(projected, SHARED_NORM)) / sqrt(2)`
//   per 256-wide group (S10 t307 `16.0`, t304 `1/sqrt(2)`).
//
// # How (one file per plan)
//
// The TEXT pass cannot read EMBED (one file per plan — see `Builder::new`),
// so this is a second EMBED-file plan like `gemma4_head`. The token id
// arrives as a `[1, 1, 1]` fp16 input (ids < 2048 stay exact — and the only
// ids that need distinguishing here are the two PLACEHOLDERS at 258880+,
// which take a different masking path below, not the `embed` op).
//
// The working + per-layer lookups use `conv_int4`-style row reads... no:
// they use `Builder::embed` (single-row fp16 `Embed` over rank-2 int4
// tables is NOT supported — `embed` reads fp16 tables only). Instead each
// lookup is a `conv_int4` 1x1 with a one-hot input? No — simplest correct:
// the lookups stay host-side (`int4_row` = 36 dequants, the cheap half) and
// only the SGEMV+norm+combine moves on-device? That saves nothing — the
// SGEMV needs the rows on-device anyway.
//
// Decision: full-device gather via `Embed` over fp16 views is impossible
// (tables are int4); via one-hot `conv_int4` is absurd (262144-wide input).
// The economical device gather is a dedicated `GatherRows` op... which does
// not exist. SO: this pass does the projection half on-device and keeps the
// 36 row lookups on host — NO. Stop. Reconsider below.
//
// # Actual decision (recorded, not implemented)
//
// The 36 `int4_row` host reads are ~36 * (row dequant) — measured against
// the 13.8M-MAC SGEMV, the SGEMV dominates (~45 of ~50 ms). Moving ONLY the
// SGEMV+norm+combine on-device still needs hidden+embedded uploaded per
// token (1536 + 8960 floats = ~21 KB — trivial) and returns 8960 floats.
// That kills the SGEMV but keeps 36 host row reads (the cheap, cached,
// mmap-friendly half).
//
// This pass therefore takes TWO inputs — `hidden [1536, 1, 1]` and
// `embedded [8960, 1, 1]` (both gathered on host, as today) — and computes
// `combined` on-device: one `conv_int8` 8960x1536 projection + grouped
// norm + scaled add. Per-token traffic: ~21 KB up, ~18 KB back. The win is
// the 13.8M MACs moving from host serial to GPU parallel (~45 ms -> ~2 ms).
//
// A full on-device row gather (dedicated `GatherRows` Kind over int4 tables)
// is the follow-up, not this pass: it needs a new Kind + shader + reference
// oracle, and the remaining host half is small and page-cache friendly.
use super::{Builder, Plan, Shape, WeightSource};

/// Per-layer input width. Mirrors `gemma4::PER_LAYER`.
pub const PER_LAYER: u32 = 256;

/// Layers. Mirrors `gemma4::LAYERS`.
pub const LAYERS: usize = 35;

/// Gather sideband: S10 t307. See `gemma4_part4::combine`.
pub const GATHER_SCALE: f32 = 16.0;

/// Build the combine pass over the EMBED file's offsets.
///
/// Inputs: `hidden [1536, 1, 1]` (working-table row) and `embedded
/// [8960, 1, 1]` (concat of the 35 per-layer rows), both gathered on host.
/// Output: `combined [8960, 1, 1]` — the per-layer block the TEXT pass takes
/// as its second input. Every other EMBED tensor is named via `host_tensor`.
pub fn build_plan(weights: &dyn WeightSource) -> Result<Plan, String> {
    build_plan_at(weights, 1)
}

/// [`build_plan`] over `width` columns laid out `[C, 1, width]`, the prefill's input layout, so
/// a whole prompt chunk combines in one submit and the output feeds the prefill as it is.
pub fn build_plan_at(weights: &dyn WeightSource, width: u32) -> Result<Plan, String> {
    use super::gemma4::embed;
    use super::gemma4::{D_MODEL, EPSILON};

    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // The combine reads only SHARED_PROJ triple + SHARED_NORM; everything
    // else is named host-side. (The int4 gather tables stay host-read —
    // see the module docs for why the row lookups did not move.)
    for index in 0..embed::TABLES {
        match index {
            3 | 4 | 5 | 6 => {}
            _ => b.host_tensor(index, &super::gemma4_head::dims_of(index)),
        }
    }
    for index in embed::TABLES..embed::TENSORS_WITH_HEAD8 {
        b.host_tensor(index, &super::gemma4_head::dims_of(index));
    }

    let hidden = b.input(Shape::new(D_MODEL, 1, width));
    let embedded = b.input(Shape::new(PER_LAYER * LAYERS as u32, 1, width));

    // projected = W @ hidden, 8960-wide over the int8 triple. conv_int8 at
    // 1 position auto-routes to ConvVecInt8 (same as the per-layer
    // projections in the transformer).
    let projected = b.conv_int8(
        hidden,
        embed::SHARED_PROJ,
        PER_LAYER * LAYERS as u32,
        (1, 1),
        (1, 1),
        (1, 1),
        (0, 0, 0, 0),
        1,
        super::Act::None,
    );
    // Grouped RMS norm with the shared 256-wide gamma: 35 groups.
    let normed = b.rms_norm_grouped(projected, embed::SHARED_NORM, EPSILON, LAYERS as u32);
    // combined = (16 * embedded + normed) / sqrt(2). The 16x and 1/sqrt(2)
    // are build-time constants (S10 t307/t304) — fold via affine pairs:
    // scaled = affine(embedded, 16, 0), then add, then affine(1/sqrt2, 0).
    // NOTE: `mul_scalar` reads the scale from the FILE, not the build —
    // constants here must use `affine` (push-constant scale), not
    // `mul_scalar`. Two dispatches + one add = 3 ops; the SGEMV they
    // bracket is the expensive one this pass exists to move.
    let scaled = b.affine(embedded, GATHER_SCALE, 0.0);
    let summed = b.add(scaled, normed);
    let combined = b.affine(summed, 1.0 / std::f32::consts::SQRT_2, 0.0);
    builder.finish(&[combined])
}

/// Prefill chunks are padded up to a multiple of this many positions, where the int4 GEMM takes
/// its 16-byte activation path: on a Pixel 8 a 163-position chunk cost 4.7 s against 2.5 s at
/// 200. Coarser buckets (64) cost ~0.1 s of GPU on widths that were already aligned.
pub const PAD_TO: u32 = 8;

/// The width to run a `width`-position chunk at when `room` cache positions are free from its
/// start. Exact: queries attend causally, so no real position reads a pad, and the pads' cache
/// slots lie past the prompt, where the next position writes before anything reads.
pub fn padded(width: u32, room: u32) -> u32 {
    let up = width.div_ceil(PAD_TO) * PAD_TO;
    if up <= room { up } else { width }
}

/// Write one position's `values` into column `column` of a channel-major `[C, 1, width]` block.
pub fn place(into: &mut [f32], values: &[f32], column: usize, width: usize) {
    for (slot, &value) in into.iter_mut().skip(column).step_by(width.max(1)).zip(values) {
        *slot = value;
    }
}

/// Column `column` of a channel-major `[C, 1, width]` block.
pub fn column(block: &[f32], column: usize, width: usize) -> Vec<f32> {
    block.iter().skip(column).step_by(width.max(1)).copied().collect()
}

/// The working-table rows of `tokens`, channel-major `[D_MODEL, 1, tokens.len()]`. Each distinct
/// token is read once and placed in every column it fills.
pub fn token_rows(reader: &crate::weights::Reader<'_>, tokens: &[u32]) -> Result<Vec<f32>, String> {
    use super::gemma4::{embed, D_MODEL, VOCAB};
    let width = tokens.len();
    let mut block = vec![0f32; D_MODEL as usize * width];
    for (token, columns) in distinct(tokens.iter().copied()) {
        if token >= VOCAB {
            return Err(format!("token {token} is past the {VOCAB}-entry vocabulary"));
        }
        let row = reader.int4_row(embed::TOKENS, embed::TOKENS + 1, &[VOCAB, D_MODEL], token)?;
        for at in columns {
            place(&mut block, &row, at, width);
        }
    }
    Ok(block)
}

/// The 35 per-layer table rows of each token, concatenated per token and laid out channel-major
/// `[PER_LAYER * LAYERS, 1, tokens.len()]`. Placeholders read row 0, as the export masks them.
/// Each distinct row is read once per table.
pub fn layer_rows(reader: &crate::weights::Reader<'_>, tokens: &[u32]) -> Result<Vec<f32>, String> {
    leading_layer_rows(reader, tokens, LAYERS)
}

/// [`layer_rows`] with only the first `tables` tables read; the rest of the block stays zero.
pub fn leading_layer_rows(reader: &crate::weights::Reader<'_>, tokens: &[u32], tables: usize) -> Result<Vec<f32>, String> {
    use super::gemma4::{embed, VOCAB};
    let width = tokens.len();
    let wide = PER_LAYER as usize;
    let mut block = vec![0f32; wide * LAYERS * width];
    let rows = distinct(tokens.iter().map(|&t| if embed::PLACEHOLDERS.contains(&t) { 0 } else { t }));
    for (row, columns) in rows {
        for i in 0..tables.min(LAYERS) {
            let table = embed::table(i);
            let values = reader.int4_row(table, table + 1, &[VOCAB, PER_LAYER], row)?;
            let from = i * wide * width;
            let Some(slab) = block.get_mut(from..from + wide * width) else {
                return Err(format!("layer {i} past a {width}-column block"));
            };
            for &at in &columns {
                place(slab, &values, at, width);
            }
        }
    }
    Ok(block)
}

/// Each distinct id in first-seen order, with the columns it occupies.
fn distinct(ids: impl Iterator<Item = u32>) -> Vec<(u32, Vec<usize>)> {
    let mut groups: Vec<(u32, Vec<usize>)> = Vec::new();
    let mut seen = std::collections::HashMap::new();
    for (at, id) in ids.enumerate() {
        let slot = *seen.entry(id).or_insert_with(|| {
            groups.push((id, Vec::new()));
            groups.len() - 1
        });
        if let Some((_, columns)) = groups.get_mut(slot) {
            columns.push(at);
        }
    }
    groups
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::nets::tests::Shapes;

    #[test]
    fn the_gather_layout_matches_the_converter() {
        // SHARED_PROJ triple (3-5) + SHARED_NORM (6) are the only tensors
        // this pass reads; the constants below mirror `collect_embed`.
        assert_eq!(super::super::gemma4::embed::SHARED_PROJ, 3);
        assert_eq!(super::super::gemma4::embed::SHARED_NORM, 6);
        assert_eq!(PER_LAYER, 256);
        assert_eq!(LAYERS, 35);
        assert_eq!(GATHER_SCALE, 16.0);
    }

    #[test]
    fn the_combine_pass_builds() {
        // Two host-gathered inputs in, one combined block out. `finish`'s
        // unread-tensor invariant polices the EMBED span via host_tensor.
        let source = Shapes::new(super::super::gemma4::embed::TENSORS_WITH_HEAD8);
        let plan = build_plan(&source).expect("the combine pass builds");
        assert_eq!(plan.inputs.len(), 2);
        assert_eq!(plan.inputs[0].shape, Shape::new(1536, 1, 1));
        assert_eq!(plan.inputs[1].shape, Shape::new(8960, 1, 1));
        assert_eq!(plan.outputs.len(), 1);
        assert_eq!(plan.outputs[0].shape, Shape::new(8960, 1, 1));
        // 1 int8 projection + 1 grouped norm + 2 affine + 1 add = 5 dispatches.
        assert_eq!(plan.ops.len(), 5);
        crate::nets::tests::assert_no_aliasing(&plan);
        let sched = crate::nets::schedule::schedule(&plan);
        crate::nets::schedule::is_sound(&plan, &sched).expect("the combine schedule is sound");
    }

    #[test]
    fn the_wide_combine_pass_keeps_the_prefill_layout() {
        let source = Shapes::new(super::super::gemma4::embed::TENSORS_WITH_HEAD8);
        let plan = build_plan_at(&source, 200).expect("the wide combine pass builds");
        assert_eq!(plan.inputs[0].shape, Shape::new(1536, 1, 200));
        assert_eq!(plan.inputs[1].shape, Shape::new(8960, 1, 200));
        assert_eq!(plan.outputs[0].shape, Shape::new(8960, 1, 200));
        assert_eq!(plan.ops.len(), 5);
    }

    #[test]
    fn padding_rounds_up_to_the_tile_when_the_cache_has_room() {
        assert_eq!(padded(163, 1024), 168);
        assert_eq!(padded(64, 1024), 64);
        assert_eq!(padded(1, 1024), 8);
        assert_eq!(padded(163, 165), 163);
    }

    #[test]
    fn distinct_groups_columns_by_first_sight() {
        assert_eq!(distinct([5, 2, 5, 0, 2].into_iter()), vec![(5, vec![0, 2]), (2, vec![1, 4]), (0, vec![3])]);
    }

    #[test]
    fn place_and_column_round_trip() {
        let mut block = vec![0f32; 3 * 4];
        place(&mut block, &[1.0, 2.0, 3.0], 2, 4);
        assert_eq!(block, vec![0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 3.0, 0.0]);
        assert_eq!(column(&block, 2, 4), vec![1.0, 2.0, 3.0]);
    }
}
