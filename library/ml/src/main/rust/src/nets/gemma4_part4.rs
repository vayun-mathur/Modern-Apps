// Host-side embedding gathers for Gemma 4.
//
// Part of the gemma4 module: the `embed` table indices plus
// `gather`/`gather_soft`/`combine`, which read the EMBED file on the host
// because a pass reads one weights file, not two. Split out of `gemma4.rs`
// so each file stays under the 500-line Rust limit
// (`scripts/lint_rust_length.sh`).

/// The embedding tables, which live in their own `.maml`. See [`crate::weights::graph`].
///
/// Two tensors, each a `(kernel, scale, bias)` triple as everything quantised here is. Both are
/// read a row at a time by [`crate::weights::Reader::int4_row`] and neither is bound to a shader:
/// a decode step needs 1536 values from a 1.2 GB table.
pub mod embed {
    /// Working token table, int4 `[VOCAB, D_MODEL]` (converted from litertlm's
    /// 2-bit rows). The gather source only - the tied head reads HEAD_TABLE.
    pub const TOKENS: usize = 0;

    /// Raw-scale head table: S10's `embedder.decode` composite (t2698),
    /// stored fp16 (the 2-bit source only resolves 0.97 under int4 requant).
    /// The table the reference scores its final hidden against; the tied head
    /// reads this, not the working table (see [`super::EMBED_GAIN`]). LAST
    /// tensor in the file, so the per-layer tables keep their entries.
    pub const HEAD_TABLE: usize = 7 + super::LAYERS * 3;

    /// Shared per-layer projection, int8 `[LAYERS * PER_LAYER, D_MODEL]`.
    pub const SHARED_PROJ: usize = 3;

    /// Norm over one layer's slice of the shared projection.
    pub const SHARED_NORM: usize = 6;

    /// First per-layer mmap table. Table `i` lives at `TABLES + i * 3`
    /// (int4 triple), each int4 `[VOCAB, PER_LAYER]` in U8-typed storage.
    /// NOTE: the per-layer tables live at file entries 7+i*3 in BOTH the old
    /// (112-tensor) and new (113-tensor) embed files: the head table was
    /// appended at the END of the file, not after the working table, so all
    /// existing indices keep working and old files stay readable.
    pub const TABLES: usize = 7;

    /// Tensors the embedding `.maml` holds: working triple + projection
    /// triple + norm + 35 triples + head fp16. The head table is LAST so the
    /// per-layer tables keep their file entries (see TABLES).
    pub const TENSORS: usize = 7 + super::LAYERS * 3 + 1;

    /// Raw-scale head table (see HEAD_TABLE): the last tensor in the file.
    pub const HEAD_TENSOR: usize = 7 + super::LAYERS * 3;

    /// First tensor of the GPU-head chunks (kernel of chunk 0).
    ///
    /// The converter appends 16 rank-4 fp16 kernels + zero biases
    /// after the legacy rank-2 table (`s10_to_maml.collect_embed`); see
    /// `crate::nets::gemma4_head` for the pass that reads them.
    pub const HEAD_CHUNKS: usize = HEAD_TABLE + 1;

    /// Tensors the chunked GPU head adds: kernel + bias per chunk.
    pub const HEAD_CHUNK_TENSORS: usize = super::super::gemma4_head::HEAD_CHUNKS * 2;

    /// Tensors an EMBED file with GPU-head chunks holds.
    pub const TENSORS_WITH_HEAD: usize = HEAD_TABLE + 1 + HEAD_CHUNK_TENSORS;

    /// First tensor of the int8 GPU-head chunks (kernel of chunk 0).
    ///
    /// The converter appends `HEAD_CHUNKS` int8 triples (kernel, per-channel
    /// scale, bias) after the fp16 chunks (`s10_to_maml.collect_embed`); see
    /// `crate::nets::gemma4_head` for the pass that reads them.
    pub const HEAD8_CHUNKS: usize = TENSORS_WITH_HEAD;

    /// Tensors the int8 chunked GPU head adds: kernel + scale + bias per chunk.
    pub const HEAD8_CHUNK_TENSORS: usize = super::super::gemma4_head::HEAD_CHUNKS * 3;

    /// Tensors an EMBED file with int8 GPU-head chunks holds.
    pub const TENSORS_WITH_HEAD8: usize = HEAD8_CHUNKS + HEAD8_CHUNK_TENSORS;

    /// Table triple for layer `i`.
    pub const fn table(i: usize) -> usize {
        TABLES + i * 3
    }

    /// Tensors a SLIM embedding `.maml` holds (`s10_to_maml.py --slim-embed`): the working triple,
    /// four one-element fp16 placeholders where SHARED_PROJ and SHARED_NORM were, and the 35
    /// tables, so every index above holds. No head table: the head file carries the head, and
    /// the per-layer combine reads the text file's copy (`super::COMBINE_PROJ`).
    pub const SLIM_TENSORS: usize = TABLES + super::LAYERS * 3;

    /// Whether this embedding file carries the per-layer combine's projection and norm.
    pub fn carries_combine(embed: &crate::weights::Reader<'_>) -> bool {
        let rows = super::LAYERS as u32 * super::PER_LAYER;
        embed.describe(SHARED_PROJ, &[rows, super::D_MODEL, 1, 1]).is_ok()
            && embed.describe(SHARED_NORM, &[super::PER_LAYER]).is_ok()
    }

    /// Whether an embedding file of `tensors` entries carries the host head's table.
    pub const fn carries_head_table(tensors: usize) -> bool {
        tensors > HEAD_TABLE
    }

    /// Ids the export maps to row 0 of the per-layer table before gathering.
    ///
    /// The image and audio placeholders. They have no per-layer input of their own - their
    /// embedding comes from the vision or audio tower - and the export masks them with a
    /// `Where` rather than letting them index the table. A gather that skipped this would read a
    /// real row for a placeholder and quietly perturb every layer.
    pub const PLACEHOLDERS: [u32; 2] = [258_880, 258_881];
}

/// One token's embedding and per-layer inputs, gathered on the host.
///
/// Returns `(inputs_embeds, per_layer_inputs)`, ready for [`build`]'s first two inputs.
/// `inputs_embeds` is the working-table row; `per_layer_inputs` is the COMBINED
/// block the reference's `maybe_preprocess_per_layer_embeddings` produces:
///
///     projected = hidden @ SHARED_PROJ
///     combined  = (16 * embedded + rms_norm(projected)) * 1/sqrt(2)
///
/// S10 graph walk t319 -> t30: t307 `[16.0]` scales the gather side, t304
/// `[1/sqrt(2)]` the sum. No `1/sqrt(d_model)` exists anywhere in S10 (and a
/// grouped RMS-norm would erase a uniform pre-scale anyway).
///
/// `embedded` concatenates one 256-wide row from each layer's own mmap table
/// (litertlm gathers the same way: 35 parallel lookups + concat).
/// The combination lives here rather than in [`build`] because the projection
/// lives in the EMBED file and a pass reads one weights file, not two.
///
/// The process-constant projection comes from the combine cache (see
/// [`combine_cached`]); use [`gather_timed`] when the per-phase attribution
/// matters.
pub fn gather(
    embed: &crate::weights::Reader<'_>,
    token: u32,
) -> Result<(Vec<f32>, Vec<f32>), String> {
    gather_timed(embed, token).map(|(hidden, per_layer, _)| (hidden, per_layer))
}

/// Host-side milliseconds inside one [`gather`], by phase.
///
/// `rows` covers the 36 int4 row reads (working table + 35 per-layer tables);
/// `bulk` the process-constant `int8_all` + gamma reads (`~0` on a combine-cache
/// hit — the row going quiet is the cache's gate signal); `matmul` the 13.7M-MAC
/// projection + grouped norm + scaled add. Rotary stays harness-side: the
/// `--time` loop times its own `rotary_row` calls.
pub struct GatherTimes {
    /// ms in the 36 int4 row reads.
    pub rows_ms: f64,
    /// ms in the `int8_all` + gamma bulk reads (`~0` on a cache hit).
    pub bulk_ms: f64,
    /// ms in the projection + grouped norm + scaled add.
    pub matmul_ms: f64,
}

/// [`gather`] with its host split attached (Phase 3a′ step 1).
///
/// Harness-only attribution: the bytes computed are identical to [`gather`]'s,
/// only timed. The `--time` loop accumulates these alongside its own rotary
/// timing to decide how steps 2–3 divide the ~240 ms host slice.
pub fn gather_timed(
    embed: &crate::weights::Reader<'_>,
    token: u32,
) -> Result<(Vec<f32>, Vec<f32>, GatherTimes), String> {
    use std::time::Instant;
    if token >= VOCAB {
        return Err(format!("token {token} is past the {}-entry vocabulary", VOCAB));
    }
    let rows_tick = Instant::now();
    let hidden = embed.int4_row(embed::TOKENS, embed::TOKENS + 1, &[VOCAB, D_MODEL], token)?;
    // The placeholders have no per-layer row of their own; the export masks them to 0.
    let per_layer_row = if embed::PLACEHOLDERS.contains(&token) { 0 } else { token };
    let mut embedded = Vec::with_capacity((PER_LAYER as usize) * LAYERS);
    for i in 0..LAYERS {
        let at = embed::table(i);
        embedded.extend(embed.int4_row(at, at + 1, &[VOCAB, PER_LAYER], per_layer_row)?);
    }
    let rows_ms = rows_tick.elapsed().as_secs_f64() * 1000.0;
    let (per_layer, bulk_ms, matmul_ms) =
        combine_timed(embed, &hidden, &embedded, combine_cache_enabled())?;
    Ok((hidden.clone(), per_layer, GatherTimes { rows_ms, bulk_ms, matmul_ms }))
}

/// The per-layer combination for an EXPLICIT hidden state (see [`gather`]).
///
/// For soft tokens: the hidden state is an encoder's output, not a table row,
/// while the table rows still come from `token`. The reference rewrites a
/// placeholder id to `pad_token_id` (0) before gathering and then overwrites
/// only the hidden state - so both halves come from row 0, and that is what
/// the caller passes here.
pub fn gather_soft(
    embed: &crate::weights::Reader<'_>,
    hidden: &[f32],
    token: u32,
) -> Result<Vec<f32>, String> {
    if hidden.len() != D_MODEL as usize {
        return Err(format!(
            "a soft hidden state of {} values, not {}",
            hidden.len(),
            D_MODEL
        ));
    }
    let mut embedded = Vec::with_capacity((PER_LAYER as usize) * LAYERS);
    for i in 0..LAYERS {
        let at = embed::table(i);
        embedded.extend(embed.int4_row(at, at + 1, &[VOCAB, PER_LAYER], token)?);
    }
    combine_cached(embed, hidden, &embedded)
}

/// The per-layer combination over already-gathered rows, cached (Phase 3a′ step 2).
///
/// The projection (`SHARED_PROJ` int8 `[8960, 1536]`, 13.7 MB) and the gamma
/// (`SHARED_NORM`) are process-constant: only the rows vary per token. The
/// first call reads and dequantises them once (~55 MB fp32 resident) and every
/// later step is served from memory; the bytes are identical by construction —
/// same file, same decode, once not per step.
///
/// On by default; `MODELRUNNER_COMBINE_CACHE=0` (or `off`/`false`, or
/// `debug.modelrunner.combine_cache` on Android) re-reads per step — today's
/// path, the per-phase fallback. The flag shares the on-by-default opt-out
/// convention of the quantize fold (see `quant_fold_on`).
///
/// This is the single shared implementation: the bridge's `gather_combine_host`
/// and the `--gather-parity` gate's both delegate here, so the cache covers the
/// shipped step path as well as the harnesses.
pub fn combine_cached(
    embed: &crate::weights::Reader<'_>,
    hidden: &[f32],
    embedded: &[f32],
) -> Result<Vec<f32>, String> {
    combine_timed(embed, hidden, embedded, combine_cache_enabled()).map(|(out, _, _)| out)
}

/// Whether the combine cache serves, read once.
///
/// Cached because this is consulted per token, where a property lookup per call
/// would be a (small but pointless) share of what Phase 3a′ is trying to remove.
/// Same rationale as the timestamp knob in `vulkan::run`.
fn combine_cache_enabled() -> bool {
    use std::sync::OnceLock;
    static ON: OnceLock<bool> = OnceLock::new();
    *ON.get_or_init(|| crate::nets::quant_fold_on(crate::knobs::get("combine_cache")))
}

/// The cached process-constant half of the combine: dequantised projection + gamma.
///
/// `key` pins the file identity AND the three tensors' descriptors, so a cache
/// hit means the same bytes by construction. Fills under the lock: decode is
/// single-threaded and the fill happens once per file, so no step ever waits on
/// another's I/O.
struct CombineBulk {
    /// File identity plus projection/scale/gamma descriptors (see `Reader::file_id`).
    key: (usize, u64, crate::weights::Tensor, crate::weights::Tensor, crate::weights::Tensor),
    /// Dequantised `SHARED_PROJ`, `[LAYERS * PER_LAYER, D_MODEL]` fp32 (~55 MB).
    proj: std::sync::Arc<Vec<f32>>,
    /// Dequantised `SHARED_NORM`, `[PER_LAYER]` fp32.
    gamma: std::sync::Arc<Vec<f32>>,
}

/// The combine cache itself: one slot, replaced when the file changes.
///
/// One slot is enough because every flow opens its EMBED file once and holds it:
/// the bridge handle for the conversation, each harness for its run. A second
/// file evicts the first rather than growing without bound (the OOM guard: the
/// streaming harness exists because this device reboots).
fn combine_cache() -> &'static std::sync::Mutex<Option<CombineBulk>> {
    use std::sync::OnceLock;
    static CACHE: OnceLock<std::sync::Mutex<Option<CombineBulk>>> = OnceLock::new();
    CACHE.get_or_init(|| std::sync::Mutex::new(None))
}

/// `combine` proper with its bulk/matmul split attached: projection, grouped-norm, add.
///
/// `use_cache = true` serves the process-constant half from the combine cache;
/// `false` re-reads per step (today's path, and the identity test's oracle).
/// The arithmetic below is byte-for-byte the old `combine` — only where the
/// projection comes from changed.
fn combine_timed(
    embed: &crate::weights::Reader<'_>,
    hidden: &[f32],
    embedded: &[f32],
    use_cache: bool,
) -> Result<(Vec<f32>, f64, f64), String> {
    use std::time::Instant;
    let bulk_tick = Instant::now();
    let (proj, gamma) = combine_projection(embed, use_cache)?;
    let bulk_ms = bulk_tick.elapsed().as_secs_f64() * 1000.0;
    let matmul_tick = Instant::now();
    let out = apply_combine(&proj, &gamma, hidden, embedded);
    let matmul_ms = matmul_tick.elapsed().as_secs_f64() * 1000.0;
    Ok((out, bulk_ms, matmul_ms))
}

/// The process-constant half of the combine, cached or freshly read.
fn combine_projection(
    embed: &crate::weights::Reader<'_>,
    use_cache: bool,
) -> Result<(std::sync::Arc<Vec<f32>>, std::sync::Arc<Vec<f32>>), String> {
    let proj_dims = [LAYERS as u32 * PER_LAYER, D_MODEL, 1, 1];
    if !use_cache {
        let proj = embed.int8_all(embed::SHARED_PROJ, embed::SHARED_PROJ + 1, &proj_dims)?;
        let gamma = embed.fp16(embed::SHARED_NORM, &[PER_LAYER])?;
        return Ok((std::sync::Arc::new(proj), std::sync::Arc::new(gamma)));
    }
    let (table, data_len) = embed.file_id();
    let proj_tensor = embed.describe(embed::SHARED_PROJ, &proj_dims)?;
    let scale_tensor = embed.describe(embed::SHARED_PROJ + 1, &[LAYERS as u32 * PER_LAYER])?;
    let gamma_tensor = embed.describe(embed::SHARED_NORM, &[PER_LAYER])?;
    let key = (table, data_len, proj_tensor, scale_tensor, gamma_tensor);
    let mut guard =
        combine_cache().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if let Some(cached) = guard.as_ref() {
        if cached.key == key {
            return Ok((std::sync::Arc::clone(&cached.proj), std::sync::Arc::clone(&cached.gamma)));
        }
    }
    let proj = embed.int8_all(embed::SHARED_PROJ, embed::SHARED_PROJ + 1, &proj_dims)?;
    let gamma = embed.fp16(embed::SHARED_NORM, &[PER_LAYER])?;
    let bulk = CombineBulk {
        key,
        proj: std::sync::Arc::new(proj),
        gamma: std::sync::Arc::new(gamma),
    };
    let out = (std::sync::Arc::clone(&bulk.proj), std::sync::Arc::clone(&bulk.gamma));
    *guard = Some(bulk);
    Ok(out)
}

/// The per-token half of the combine over an already-held projection.
fn apply_combine(
    proj: &[f32],
    gamma: &[f32],
    hidden: &[f32],
    embedded: &[f32],
) -> Vec<f32> {
    let rows = LAYERS;
    let wide = PER_LAYER as usize;
    // S10 t307: the gather side carries x16 (a real term, not a neutral
    // rescale - the sum mixes two different-magnitude halves).
    const GATHER_SCALE: f32 = 16.0;
    let inv_sqrt_2 = 1.0 / std::f32::consts::SQRT_2;
    let mut out = Vec::with_capacity(rows * wide);
    for r in 0..rows {
        let row = &proj[r * wide * D_MODEL as usize..(r + 1) * wide * D_MODEL as usize];
        let emb = &embedded[r * wide..(r + 1) * wide];
        // projected = W @ hidden, one 256-wide group. No 1/sqrt(d_model):
        // S10 has no such constant, and the grouped RMS-norm below would
        // erase a uniform pre-scale anyway.
        let mut group = vec![0f32; wide];
        for (o, wrow) in group.iter_mut().zip(row.chunks_exact(D_MODEL as usize)) {
            *o = wrow.iter().zip(hidden.iter()).map(|(a, b)| a * b).sum::<f32>();
        }
        // Grouped RMS norm with the shared 256-wide gamma.
        let mean_sq = group.iter().map(|v| v * v).sum::<f32>() / wide as f32;
        let norm = 1.0 / (mean_sq + EPSILON).sqrt();
        for (v, &g) in group.iter_mut().zip(gamma.iter()) {
            *v = *v * norm * g;
        }
        // combined = (16 * embedded + normed) / sqrt(2) (t307, t304).
        for (v, &e) in group.iter_mut().zip(emb.iter()) {
            *v = (*v + GATHER_SCALE * e) * inv_sqrt_2;
        }
        out.extend_from_slice(&group);
    }
    out
}

#[cfg(test)]
mod combine_cache_tests {
    use super::*;

    /// Cached vs re-read combine is bit-identical (Phase 3a′ step 2 gate).
    ///
    /// The gate the plan requires before the cache lands: the bytes are
    /// identical by construction (same file, same decode, once not per step),
    /// and this pins it on synthetic rows. Forced through `combine_timed`
    /// directly rather than the flag so no environment is touched — the flag's
    /// `OnceLock` is process-global and other tests share the process.
    #[test]
    fn cached_combine_is_bit_identical_to_reread() {
        // The fixture carries only what the combine reads: dummies at 0-2,
        // the int8 projection triple at 3-5, the gamma at 6. The row tables
        // the gather reads are irrelevant — this tests the combine, not the rows.
        const PROJ: usize = LAYERS * PER_LAYER as usize * D_MODEL as usize;
        let blob = crate::weights::write_mixed(
            crate::weights::graph::GEMMA4_EMBED,
            &[
                crate::weights::Fixture::F16(vec![1], vec![0.0]),
                crate::weights::Fixture::F16(vec![1], vec![0.0]),
                crate::weights::Fixture::F16(vec![1], vec![0.0]),
                crate::weights::Fixture::I8(
                    vec![LAYERS as u32 * PER_LAYER, D_MODEL, 1, 1],
                    (0..PROJ).map(|i| (i % 15) as i8 - 7).collect(),
                ),
                crate::weights::Fixture::F16(
                    vec![LAYERS as u32 * PER_LAYER],
                    vec![1.0; LAYERS * PER_LAYER as usize],
                ),
                crate::weights::Fixture::F16(vec![1], vec![0.0]),
                crate::weights::Fixture::F16(vec![PER_LAYER], vec![1.0; PER_LAYER as usize]),
            ],
        );
        let weights = crate::weights::Weights::parse(&blob, crate::weights::graph::GEMMA4_EMBED)
            .expect("the combine fixture parses");
        let reader = weights.reader();
        let hidden: Vec<f32> =
            (0..D_MODEL as usize).map(|i| (i % 7) as f32 * 0.125).collect();
        let embedded: Vec<f32> =
            (0..LAYERS * PER_LAYER as usize).map(|i| (i % 11) as f32 * 0.0625).collect();
        // Re-read first (today's path, no cache interaction), then cached twice:
        // the second cached call is the hit the gate cares about.
        let reread = combine_timed(&reader, &hidden, &embedded, false)
            .expect("the uncached combine runs")
            .0;
        let miss = combine_timed(&reader, &hidden, &embedded, true)
            .expect("the cache fill runs")
            .0;
        let hit = combine_timed(&reader, &hidden, &embedded, true)
            .expect("the cache hit runs")
            .0;
        assert_eq!(reread.len(), LAYERS * PER_LAYER as usize);
        assert_eq!(miss, reread);
        assert_eq!(hit, reread);
        assert!(embed::carries_combine(&reader));
    }

    #[test]
    fn a_slim_embed_keeps_every_index_and_carries_neither_combine_nor_head() {
        assert_eq!(embed::SLIM_TENSORS, 112);
        assert_eq!(embed::table(LAYERS - 1) + 3, embed::SLIM_TENSORS);
        assert!(!embed::carries_head_table(embed::SLIM_TENSORS));
        assert!(embed::carries_head_table(embed::TENSORS));
        let placeholders: Vec<_> =
            (0..embed::TABLES).map(|_| crate::weights::Fixture::F16(vec![1], vec![0.0])).collect();
        let blob = crate::weights::write_mixed(crate::weights::graph::GEMMA4_EMBED, &placeholders);
        let weights = crate::weights::Weights::parse(&blob, crate::weights::graph::GEMMA4_EMBED)
            .expect("the slim fixture parses");
        assert!(!embed::carries_combine(&weights.reader()));
    }
}
