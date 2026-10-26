/// `TILE` in `shaders/conv_point.comp` and `shaders/conv_point_int8.comp`.
///
/// Both tiled shaders are dispatched one workgroup per tile, so [`Builder::emit`] has to know
/// this to compute [`Push::count`]. Shared by the two so the fp16 and int8 lowerings cannot
/// drift apart.
const CONV_POINT_TILE: u32 = 32;

/// `TILE` in `shaders/conv_point_int8.comp`: output channels and positions per workgroup.
///
/// Separate from [`CONV_POINT_TILE`] because the int8 shader dropped shared-memory tiling for
/// register blocking and covers a larger tile - see its header. **Must equal `TILE` there.**
const CONV_POINT_INT8_TILE: u32 = 32;

/// Int8 GEMMs with fewer 32 x 32 tiles than this take `conv_point_int8.comp`'s split path.
const CONV_POINT_INT8_SPLIT_BELOW: u32 = 256;

/// `TILE` in the tiled modes of `shaders/attn_scores.comp` and `shaders/attn_apply.comp`.
///
/// **Must equal `TILE` in both.** Held by `the_attention_tiles_match_their_shaders`.
const ATTN_TILE: u32 = 32;

/// `BLOCK_OC` in `shaders/conv_tiled.glsl`: output channels per invocation.
///
/// **Must equal `BLOCK_OC` there.** Held by `the_spatial_tiles_match_their_shader`.
const SPATIAL_BLOCK_OC: u32 = 4;

/// `BLOCK_X` in `shaders/conv_tiled.glsl`: adjacent output columns per invocation.
///
/// **Must equal `BLOCK_X` there.** Held by the same test.
const SPATIAL_BLOCK_X: u32 = 8;

/// `ROWS` in `shaders/conv_vec_int8.comp`: output channels per workgroup.
///
/// That shader is dispatched one workgroup per group of this many channels, so [`Builder::emit`]
/// has to know it to compute [`Push::count`], exactly as it does for [`CONV_POINT_TILE`].
/// **Must equal `ROWS` in `conv_vec_int8.comp`.** The two are separate declarations in
/// separate languages and nothing checks them against each other; a mismatch leaves most
/// output channels never dispatched, which parity catches as zeros. Held by
/// `the_gemv_row_count_matches_both_shaders`.
const CONV_VEC_ROWS: u32 = 2;

/// `ROWS` in `shaders/conv_vec_int4.comp`: output channels per workgroup.
///
/// The int4 gemv runs eight rows per workgroup for the stream reason that shader's header
/// gives, while the int8 gemv stays at [`CONV_VEC_ROWS`]. Separate constants because they
/// are separate decisions now: the two shaders diverged, and one shared name would let an
/// edit to either silently dispatch the other wrong. [`Builder::emit`] uses this for the
/// int4 vector kinds; `the_int4_gemv_row_count_matches_its_shader` holds it against the shader.
const CONV_VEC_INT4_ROWS: u32 = 8;

/// `ROWS` in `shaders/conv_vec_q2k.comp`: output channels per workgroup.
///
/// Eight like the int4 gemv, for the same stream reason: a Q2_K row's superblocks are
/// wider than int4's blocks, so fewer rows per workgroup would leave the same
/// load-ALU overlap on the table. Separate constant for the same reason as
/// [`CONV_VEC_INT4_ROWS`]: an edit to either shader must not silently dispatch the other
/// wrong. [`Builder::emit`] uses this for the Q2_K vector kinds;
/// `the_q2k_gemv_row_count_matches_its_shader` holds it against the shader.
pub(crate) const CONV_VEC_Q2K_ROWS: u32 = 8;

/// Most rows a reducing kind (layer norm, RMS norm, softmax) runs one workgroup per row for.
///
/// Below it a whole workgroup splits each row - see `shaders/reduce.glsl` - because there are
/// too few rows to occupy the device one invocation each: a decode step normalises a single
/// position. Above it the original one-invocation-per-row mode stays, because enough rows fill
/// the device on their own and adjacent invocations then read adjacent positions.
const ROW_REDUCE_MAX_ROWS: u32 = 256;

/// Shortest row worth splitting across a workgroup. Shorter rows leave most lanes idle.
const ROW_REDUCE_MIN_LEN: u32 = 64;

/// Invocations per workgroup: `local_size_x` in `shaders/common.glsl`.
const ROW_REDUCE_LANES: u32 = 64;

/// Logits each lane of [`Builder::argmax`]'s first stage scans per pass.
///
/// 64 lanes × 64 workgroups × this many cover 262,144 logits at one element per
/// lane per iteration. Larger keeps the fold's partial count down; smaller wastes
/// less on ragged tails. **Must equal `PER_LANE` in `shaders/argmax.comp`.**
const ARGMAX_PER_LANE: u32 = 64;

/// Workgroups [`Builder::argmax`]'s first stage reduces a row of `count` logits with.
///
/// Enough that every lane has work (`PER_LANE` each), capped so the fold's partials
/// fit one workgroup's 64 lanes. The device and the host interpreter share this, so
/// the group boundaries — and therefore the tie-breaks — agree by construction.
pub(crate) fn argmax_groups(count: u32) -> u32 {
    count.div_ceil(ROW_REDUCE_LANES * ARGMAX_PER_LANE).clamp(1, ROW_REDUCE_LANES)
}

/// Whether `rows` rows of `len` elements reduce one workgroup per row, as
/// `(Push::kw, invocations)`.
fn row_reduce(rows: u32, len: u32) -> (u32, u32) {
    if rows <= ROW_REDUCE_MAX_ROWS && len >= ROW_REDUCE_MIN_LEN {
        (1, rows * ROW_REDUCE_LANES)
    } else {
        (0, rows)
    }
}

/// [`row_reduce`] for an RMS norm over `rows` rows strided `stride` apart: tile mode
/// (`Push::kw` 2, 16 rows a workgroup, see `shaders/rmsnorm.comp`) once 16 adjacent positions
/// exist to share each load.
fn norm_reduce(rows: u32, len: u32, stride: u32) -> (u32, u32) {
    if stride >= NORM_TILE_ROWS && len >= ROW_REDUCE_MIN_LEN {
        (2, rows.div_ceil(NORM_TILE_ROWS) * ROW_REDUCE_LANES)
    } else {
        row_reduce(rows, len)
    }
}

/// Rows one tile-mode workgroup owns. `tile_mode` in `shaders/rmsnorm.comp` hard-codes it.
const NORM_TILE_ROWS: u32 = 16;

/// [`row_reduce`] for a per-channel plane reduction (instance norm, global average
/// pool), whose rows are contiguous planes rather than strided channel gathers.
///
/// The threshold differs from [`row_reduce`]: a plane reduction's serial cost grows
/// with the plane, not the row count, so planes of at least [`ROW_REDUCE_MIN_LEN`]
/// elements go row mode however many channels there are — MODNet's 16-channel
/// 128x128 planes (16 ms each serially) are the case that motivated this. `count`
/// stays the channel count either way, so the reference interpreter and
/// `Kind::arena_reads` need no change.
fn row_reduce_plane(channels: u32, plane: u32) -> (u32, u32) {
    if plane >= ROW_REDUCE_MIN_LEN {
        (1, channels * ROW_REDUCE_LANES)
    } else {
        (0, channels)
    }
}

/// Rows at least this long reduce one workgroup per row however many there are - for a softmax.
///
/// A softmax row is contiguous, so lanes splitting it read consecutive elements and the row mode
/// costs nothing in access pattern; a norm's row is strided by the sequence length, which is why
/// [`row_reduce`] keeps many-position norms on the per-position shader. Whisper's encoder has
/// 12,000 score rows of 1,500 keys: one thread each made its six softmaxes 420 ms on a Pixel 8.
const ROW_REDUCE_LONG_ROW: u32 = 512;

/// [`row_reduce`] for a softmax, whose rows are contiguous. See [`ROW_REDUCE_LONG_ROW`].
fn row_reduce_contiguous(rows: u32, len: u32) -> (u32, u32) {
    if len >= ROW_REDUCE_LONG_ROW {
        (1, rows * ROW_REDUCE_LANES)
    } else {
        row_reduce(rows, len)
    }
}

#[cfg(test)]
fn gemv_rows_of(shader: &str) -> u32 {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("shaders").join(shader);
    let source = std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));
    let line = source
        .lines()
        .find(|line| line.trim_start().starts_with("#define ROWS"))
        .unwrap_or_else(|| panic!("{shader} declares no ROWS"));
    line.split_whitespace()
        .nth(2)
        .and_then(|word| word.trim_end_matches('u').parse().ok())
        .unwrap_or_else(|| panic!("{shader} has an unreadable ROWS: {line}"))
}

/// `erf`, to about 1.5e-7 — Abramowitz and Stegun 7.1.26.
///
/// Rust has no `erf`, and [`Act::Gelu`] is the exact form rather than the tanh approximation, so
/// approximating the *activation* would be a different function. This approximates `erf` itself
/// instead, well below fp16's resolution.
///
/// Test-only now. It began in `post::duration` for VITS's separable stacks and was moved here so
/// that deleting Piper would not take it with it; with Piper gone its only remaining caller is
/// `nets::reference`, which is `#[cfg(test)]`. It stays beside [`Act::Gelu`] rather than moving
/// into that module because the two have to agree with `activate` in `common.glsl`, which is where
/// the *shipped* GELU is computed — the series here and the one there are the same coefficients,
/// and keeping them one scroll apart is what makes that checkable.
#[cfg(test)]
pub(crate) fn erf(x: f32) -> f32 {
    const A: [f32; 5] = [0.254_829_6, -0.284_496_74, 1.421_413_7, -1.453_152, 1.061_405_4];
    const P: f32 = 0.327_591_1;
    let sign = if x < 0.0 { -1.0 } else { 1.0 };
    let x = x.abs();
    let t = 1.0 / (1.0 + P * x);
    let mut poly = 0.0;
    for coefficient in A.iter().rev() {
        poly = (poly + coefficient) * t;
    }
    sign * (1.0 - poly * (-x * x).exp())
}

/// The largest integer fp16 holds exactly, and so the width of one embedding id lane.
///
/// Every integer below this has an exact fp16 representation; at 2049 the gaps open to 2 and
/// keep doubling. A table with more rows than this takes its ids as two lanes — see
/// [`Kind::Embed`] and [`embed_lanes`].
pub const EMBED_LANE: u32 = 2048;

/// Ids for [`Builder::embed`] over a table of more than [`EMBED_LANE`] rows, laid out as the
/// `[2, 1, T]` tensor it wants: all the low lanes, then all the high ones.
pub fn embed_lanes(ids: &[u32]) -> Vec<f32> {
    let mut out = Vec::with_capacity(ids.len() * 2);
    out.extend(ids.iter().map(|&id| (id % EMBED_LANE) as f32));
    out.extend(ids.iter().map(|&id| (id / EMBED_LANE) as f32));
    out
}
