//! Which of three hypotheses explains the int4 gemv running at 3-17% of streaming bandwidth.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example report_gemv_bottleneck
//! ```
//!
//! # The question
//!
//! `probe_ssbo.comp` sweeps 256 MB at ~20 GB/s on a Mali-G715 doing **one XOR per 16 bytes**
//! with one load in flight. `conv_vec_int4.comp` reads the same width from the same kind of
//! buffer with the same workgroup size and a comparable load depth, and reaches 0.64-3.37
//! GB/s. Three explanations have been offered and they imply opposite fixes:
//!
//! * **H1, memory-level parallelism.** ml_drift bulk-prefetches into subgroup registers
//!   (`__wave Type weights_cache[16]`, `ucl::WaveLoad(..., 16)`); we keep 1-2 loads in
//!   flight. Fix: restructure the loads.
//! * **H2, dequant ALU.** ~160 ops per 16 bytes against the control's one. Fix: cut the
//!   arithmetic, which would revive `VK_KHR_shader_integer_dot_product` (reported
//!   accelerated on both test devices, currently unused) and possibly W4A8 activations.
//! * **H3, access pattern.** Something about the indexing rather than the width.
//!
//! # The design
//!
//! Five shaders over one 256 MB allocation, differing in one axis at a time:
//!
//! | | shallow loads | 16 loads in flight |
//! | :--- | :--- | :--- |
//! | 1 XOR / 16 B | **A** (control) | **D** |
//! | + int4 unpack | **B** | |
//! | + unpack, activation fetch, FMA | **C** | **E** |
//!
//! `A-B` is the unpack, `B-C` the activation stream, `A-D` prefetch depth without
//! arithmetic, `C-E` prefetch depth with it. A being anything other than ~20 GB/s means the
//! harness is not comparable to `imageprobe::compare` and nothing else should be read.
//!
//! All five sweep the same bytes with the same grid, so the reported GB/s are directly
//! comparable to each other and to the SSBO figure from `report_read_path`.
//!
//! # Deliberately not in `shaders/`
//!
//! These are probes, not runtime kernels, so they live in `analysis/gemv_probe/` and are
//! compiled by hand rather than by `build.rs`, which would pull them into every build of
//! the shipped runtime. See the README beside them for the `glslc` line.

use std::sync::Arc;
use std::time::Instant;

use ash::vk;

use modelrunner::vulkan::buffers::Buffer;
use modelrunner::vulkan::context::{self, Context};

// Compiled by `build.rs` from `shaders/gemv_*.comp` into OUT_DIR, the same path
// `imageprobe.rs` uses for its two probe shaders. The sources of truth are in
// `analysis/gemv_probe/`; `shaders/` holds build-visible copies because `build.rs` is the
// only glslc access available in this environment.
const A: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/gemv_a_control.comp.spv"));
const B: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/gemv_b_unpack.comp.spv"));
const C: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/gemv_c_full.comp.spv"));
const D: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/gemv_d_deep.comp.spv"));
const E: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/gemv_e_deep_full.comp.spv"));
const F: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/gemv_f_shape.comp.spv"));
const G: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/gemv_g_shape_full.comp.spv"));
const H: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/gemv_h_rows8.comp.spv"));
const I: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/gemv_i_kloop16.comp.spv"));
const J: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/gemv_j_split8x8.comp.spv"));
const K: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/gemv_k_rwarena.comp.spv"));

/// Output channels for the gemv-shaped variants F and G.
///
/// 65,536 so their bytes match the `[65536, 1536]` row of `report_gemv_scaling`, which is the
/// figure G has to reproduce. `count` is half of it because `ROWS` is 2, giving 32,768
/// workgroups - inside the 65,535 per-dimension floor, so no 2D split is needed.
///
/// `GEMV_OUT` overrides it, so the byte total can be held roughly constant while `GEMV_IN`
/// sweeps. See [`gemv_out`].
fn gemv_out() -> u32 {
    env_u32("GEMV_OUT").unwrap_or(65_536)
}

/// Taps per output channel for F and G. 1,536 is the shape ~70% of the model's bytes have,
/// and the shape whose loop runs exactly once with 16 lanes idle.
///
/// # Why this is overridable
///
/// It is the single variable that decides how many times `conv_vec_int4.comp`'s loop runs per
/// lane: the stride is `64 * I4_BLOCK` = 2048 taps, so a lane runs `ceil(in_c / 2048)` times
/// and lanes with `lane * 32 >= in_c` do not run at all. Sweeping it with everything else held
/// fixed isolates iterations-per-lane from occupancy, ALU mix and dispatch count - which is the
/// question left open after the A-G run, and it needs no new shader because both F and G
/// already take `in_c` as a push constant.
///
///   1536 -> 1 iteration, 48 of 64 lanes  (q, k, v, gate_up, head splits: 71% of decode bytes)
///   2048 -> 1 iteration, 64 of 64 lanes  (o_proj on sliding layers)
///   4096 -> 2 iterations, 64 lanes
///  12288 -> 6 iterations, 64 lanes       (down, shared-cache layers)
fn gemv_in() -> u32 {
    env_u32("GEMV_IN").unwrap_or(1_536)
}

/// A positive `u32` from the environment, or `None` if unset or unparseable.
fn env_u32(key: &str) -> Option<u32> {
    std::env::var(key).ok()?.parse::<u32>().ok().filter(|v| *v > 0)
}

/// Bytes swept per pass. Matches `imageprobe::BYTES` so A is comparable to the published
/// SSBO figure.
const BYTES: u64 = 256 * 1024 * 1024;

/// Workgroups. Matches `imageprobe::GROUPS`.
const GROUPS: u32 = 2048;

/// `float16_t` in the activation buffer.
///
/// 4,096 (8 KB) is the floor — cache-resident, as the real kernel's 3 KB activation vector is,
/// and it is all variants A–E ever index (C's `abase` tops out at 4,095).
///
/// The gemv-shaped variants need `in_c` elements, because their activation index runs to
/// `base + 31` where `base` reaches `in_c`. Sizing to that rather than masking is deliberate:
/// masking every access cost **32 extra ALU ops per `uvec4`** in the loop these variants exist
/// to measure, which the real kernel does not pay (`conv_vec_int4.comp:114`), and which made
/// this probe 1.6–1.8x slower than the production kernel it models. Sizing to `in_c` also
/// reproduces the real cache footprint (3 KB at 1536, 24 KB at 12288) instead of a fixed 8 KB.
fn act_elems() -> u64 {
    4096.max(u64::from(gemv_in()) + 64)
}

/// Timed passes per variant, after a discarded warm pass. Odd, so the median is a sample.
const RUNS: usize = 7;

/// The per-call host round trip, in seconds, as measured by `report_gemv_overhead`.
///
/// Directly measured rather than fitted: a `[64, 1536]` gemv moves 49 kB, which even a slow
/// kernel streams in tens of microseconds, so its wall time is the round trip. Three runs on a
/// Mali-G715 gave 0.312 / 0.655 / 0.638 ms; the larger figure is used so the guard errs toward
/// warning. An earlier two-parameter curve fit put this at 1.5-2.9 ms and was wrong.
///
/// Used only to judge whether a variant's measurement is overhead-dominated. It is **not**
/// subtracted from the reported GB/s - those stay raw, so they remain comparable with
/// `report_gemv_scaling`, which does not subtract either.
const CALL_FLOOR_S: f64 = 0.000_64;

/// Passes of variant A run before any timing starts, to bring the clocks up.
///
/// bench-builder found cold-clock artifacts large enough to halve a reading, so the first
/// variant measured must not also be the thing that warms the device.
const WARMUP: usize = 3;

fn main() {
    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => return println!("no usable Vulkan device: {why}"),
    };
    if let Err(why) = run(&context) {
        println!("probe failed: {why}");
    }
}

fn run(context: &Arc<Context>) -> Result<(), String> {
    let quads = (BYTES / 16) as u32;
    // The source allocation's USAGE FLAGS are a variable, not a detail.
    //
    // `imageprobe` allocates its 256 MB with `STORAGE_BUFFER | UNIFORM_TEXEL_BUFFER |
    // TRANSFER_DST`, because it needs a texel view over the same memory. This runner allocates
    // with `Buffer::device_local`, i.e. `STORAGE_BUFFER | TRANSFER_DST | TRANSFER_SRC` - no
    // texel usage.
    //
    // That difference is suspicious: variant A is byte-for-byte `probe_ssbo.comp`, reads the
    // same 256 MB with the same 2,048 groups, and measures **28.6 GB/s on the G710** where
    // `report_read_path`'s SSBO leg measures **17.4** on the same device. Same shader, 1.64x
    // apart, and the allocation flags are the only difference.
    //
    // `GEMV_TEXEL_USAGE=1` adds the texel flag so the two can be compared directly. If A drops
    // to ~17.4, then declaring `UNIFORM_TEXEL_BUFFER` depresses SSBO throughput - which would
    // mean `report_read_path`'s SSBO leg is handicapped, its ceilings are understated, and its
    // texel-vs-SSBO ratio is measuring the handicap rather than a texel advantage.
    let source = if std::env::var("GEMV_TEXEL_USAGE").is_ok() {
        Buffer::device_local_usage(
            context,
            BYTES,
            vk::BufferUsageFlags::STORAGE_BUFFER
                | vk::BufferUsageFlags::UNIFORM_TEXEL_BUFFER
                | vk::BufferUsageFlags::TRANSFER_DST,
        )?
    } else {
        Buffer::device_local(context, BYTES)?
    };
    let sink = Buffer::device_local(context, 256)?;
    let acts = Buffer::device_local(context, act_elems() * 2)?;

    // The activation buffer is initialised, unlike the source.
    //
    // The source feeds `bitfieldExtract`, so whatever bits it holds become a finite integer
    // and uninitialised memory cannot change the arithmetic's cost. fp16 is different: an
    // uninitialised pattern can be NaN or a denormal, and a device that handles those on a
    // slow path would make variants C and E look expensive for a reason that has nothing to
    // do with the hypothesis under test.
    upload_activations(context, &acts)?;

    let bindings = [
        binding(0),
        binding(1),
        binding(2),
    ];
    let layout = unsafe {
        context
            .device
            .create_descriptor_set_layout(
                &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                None,
            )
            .map_err(|e| format!("descriptor layout: {e:?}"))?
    };
    let sizes = [vk::DescriptorPoolSize {
        ty: vk::DescriptorType::STORAGE_BUFFER,
        descriptor_count: 3,
    }];
    let pool = unsafe {
        context
            .device
            .create_descriptor_pool(
                &vk::DescriptorPoolCreateInfo::default().max_sets(1).pool_sizes(&sizes),
                None,
            )
            .map_err(|e| format!("descriptor pool: {e:?}"))?
    };
    let layouts = [layout];
    let set = unsafe {
        context
            .device
            .allocate_descriptor_sets(
                &vk::DescriptorSetAllocateInfo::default()
                    .descriptor_pool(pool)
                    .set_layouts(&layouts),
            )
            .map_err(|e| format!("descriptor set: {e:?}"))?
            .first()
            .copied()
            .ok_or("allocate_descriptor_sets returned nothing")?
    };
    write_set(context, set, &source, &sink, &acts);

    let push = [vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::COMPUTE)
        .offset(0)
        .size(12)];
    let pipeline_layout = unsafe {
        context
            .device
            .create_pipeline_layout(
                &vk::PipelineLayoutCreateInfo::default()
                    .set_layouts(&layouts)
                    .push_constant_ranges(&push),
                None,
            )
            .map_err(|e| format!("pipeline layout: {e:?}"))?
    };

    let command_pool = unsafe {
        context
            .device
            .create_command_pool(
                &vk::CommandPoolCreateInfo::default()
                    .queue_family_index(context.queue_family_index)
                    .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER),
                None,
            )
            .map_err(|e| format!("command pool: {e:?}"))?
    };
    let command_buffer = unsafe {
        context
            .device
            .allocate_command_buffers(
                &vk::CommandBufferAllocateInfo::default()
                    .command_pool(command_pool)
                    .level(vk::CommandBufferLevel::PRIMARY)
                    .command_buffer_count(1),
            )
            .map_err(|e| format!("command buffer: {e:?}"))?
            .first()
            .copied()
            .ok_or("allocate_command_buffers returned nothing")?
    };
    let fence = unsafe {
        context
            .device
            .create_fence(&vk::FenceCreateInfo::default(), None)
            .map_err(|e| format!("fence: {e:?}"))?
    };

    // name, spirv, note, workgroups, bytes swept.
    //
    // A-E sweep the whole 256 MB with a grid-stride loop, so a lane runs ~128 iterations.
    // F and G are dispatched as a real gemv - one workgroup per two output channels - so a
    // lane runs the loop **once** at `in_c` 1536, which is what `conv_vec_int4.comp` does.
    // That difference is the point of the pair; see `gemv_f_shape.comp`.
    let gemv_out = gemv_out();
    let gemv_in = gemv_in();
    // Weight bytes F and G sweep: `out * in / 2` at four bits.
    let gemv_bytes: u64 = u64::from(gemv_out) * u64::from(gemv_in) / 2;

    // HARD BOUND. The gemv-shaped variants index `weights128[(channel * in_c + base) / 32]`
    // into the same 256 MiB source allocation the streaming variants sweep. If
    // `out * in_c / 2` exceeds it they read past the end — and under `robustBufferAccess`
    // that does not fault, it returns zeros. The kernel still runs, still finishes, and
    // reports a plausible GB/s computed from bytes it never actually read.
    //
    // Refusing is the only safe behaviour: a silently wrong number here would be
    // indistinguishable from a real result and would be acted on.
    if gemv_bytes > BYTES {
        println!("REFUSING TO RUN: GEMV_OUT={gemv_out} x GEMV_IN={gemv_in} needs {:.1} MB", gemv_bytes as f64 / 1e6);
        println!("  but the source allocation is {:.1} MB.", BYTES as f64 / 1e6);
        println!();
        println!("  The gemv variants would read past the buffer. Under robustBufferAccess that");
        println!("  returns zeros rather than faulting, so you would get a plausible number");
        println!("  computed from bytes that were never read.");
        println!();
        println!("  Keep GEMV_OUT * GEMV_IN <= {}. Max GEMV_OUT for this GEMV_IN is {}.", BYTES * 2, (BYTES * 2) / u64::from(gemv_in));
        return Ok(());
    }

    // A lane starts at `lane * 32` and strides 2048, so this is how many times its loop runs
    // and how many of the 64 lanes enter it at all. Printed because it is the variable under
    // test, and reading it off `in_c` in your head is exactly the step everyone got wrong.
    let iterations = gemv_in.div_ceil(2048);
    let active_lanes = gemv_in.div_ceil(32).min(64);

    let variants: [(&str, &[u8], &str, u32, u64); 11] = [
        ("A control      ", A, "stream, 1 XOR / 16 B", GROUPS, BYTES),
        ("B unpack       ", B, "stream, + int4 unpack", GROUPS, BYTES),
        ("C full dequant ", C, "stream, + activations + FMA", GROUPS, BYTES),
        ("D deep         ", D, "stream, 16 loads in flight", GROUPS, BYTES),
        ("E deep + full  ", E, "stream, deep + full dequant", GROUPS, BYTES),
        ("F real shape   ", F, "gemv shape, trivial ALU", gemv_out / 2, gemv_bytes),
        ("G real shape+  ", G, "K=1 ROWS 2 64 lanes, READONLY acts", gemv_out / 2, gemv_bytes),
        ("H ROWS 8       ", H, "K=1  ROWS 8  64 lanes (row-ILP only)", gemv_out / 8, gemv_bytes),
        ("I K-loop x16   ", I, "K=3  ROWS 2  16 lanes", gemv_out / 2, gemv_bytes),
        ("J split 8x8    ", J, "K=6  ROWS 8  64 lanes", gemv_out / 8, gemv_bytes),
        // G with a read-write activation buffer and a store into it, as production's `arena`
        // is (`common.glsl:38`, `conv_vec_int4.comp:179`). K vs G is the aliasing cost.
        ("K rw-arena     ", K, "= G but READ-WRITE acts + store", gemv_out / 2, gemv_bytes),
    ];

    // Warm the clocks on the control before anything is recorded as a measurement.
    {
        let warm = Pipeline::new(context, pipeline_layout, A)?;
        for _ in 0..WARMUP {
            let _ = time_pass(Pass { context, cb: command_buffer, fence, layout: pipeline_layout, set, pipeline: &warm }, quads, GROUPS)?;
        }
        warm.destroy(context);
    }

    println!("  A-E: 256 MB per pass, {GROUPS} workgroups, grid-stride (~128 iterations a lane)");
    println!(
        "  F-G: {:.1} MB per pass, {} workgroups, gemv shape [{gemv_out}, {gemv_in}]",
        gemv_bytes as f64 / 1e6,
        gemv_out / 2,
    );
    println!(
        "       -> {iterations} loop iteration(s) a lane, {active_lanes} of 64 lanes active"
    );
    println!("  {RUNS} timed passes each, INTERLEAVED round-robin");
    println!();
    println!("  variant             raw GB/s  corr GB/s      min      max   what changed");

    // Every variant is built up front and then timed ROUND-ROBIN: one pass of A, one of B, ...
    // one of J, and repeat. Not seven passes of A, then seven of B.
    //
    // This is not fastidiousness. lead measured `ROWS=4` at 3.63 vs 3.19 - an apparent 14% win -
    // running baseline-then-candidate sequentially, and it evaporated to 0.88x (a LOSS) when the
    // same two were interleaved in one session. GPU DVFS drifts over the tens of seconds a full
    // sweep takes, so a sequential schedule confounds "which variant" with "when in the session
    // it ran". With ten variants the first and last are minutes apart, and F/G/I - the
    // comparisons this whole document rests on - sit at the far end of that drift.
    //
    // Round-robin makes every variant share the same clock trajectory: whatever the clocks are
    // doing in round k, all ten see it.
    let mut built = Vec::with_capacity(variants.len());
    for (name, spirv, note, groups, bytes) in variants {
        built.push((name, Pipeline::new(context, pipeline_layout, spirv)?, note, groups, bytes));
    }

    // One discarded pass each, so no variant's first timed pass pays for driver setup.
    for (_, pipeline, _, groups, _) in &built {
        let _ = time_pass(Pass { context, cb: command_buffer, fence, layout: pipeline_layout, set, pipeline }, quads, *groups)?;
    }

    let mut rates: Vec<Vec<f64>> = vec![Vec::with_capacity(RUNS); built.len()];
    let mut times: Vec<Vec<f64>> = vec![Vec::with_capacity(RUNS); built.len()];
    for _ in 0..RUNS {
        for (index, (_, pipeline, _, groups, bytes)) in built.iter().enumerate() {
            let seconds =
                time_pass(Pass { context, cb: command_buffer, fence, layout: pipeline_layout, set, pipeline }, quads, *groups)?;
            if let Some(slot) = rates.get_mut(index) {
                slot.push(*bytes as f64 / seconds / 1e9);
            }
            if let Some(slot) = times.get_mut(index) {
                slot.push(seconds);
            }
        }
    }

    let mut medians = Vec::new();
    let mut overhead_flagged = false;
    for (index, (name, pipeline, note, _, bytes)) in built.iter().enumerate() {
        pipeline.destroy(context);
        let (Some(rate_samples), Some(time_samples)) = (rates.get_mut(index), times.get_mut(index))
        else {
            continue;
        };
        rate_samples.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        time_samples.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        let median = rate_samples.get(rate_samples.len() / 2).copied().unwrap_or(0.0);
        let low = rate_samples.first().copied().unwrap_or(0.0);
        let high = rate_samples.last().copied().unwrap_or(0.0);

        // Overhead share is judged on measured WALL TIME, not on bytes.
        //
        // A byte threshold is calibrated against one rate and goes quiet at every other. 20 MB
        // at 2.77 GB/s is 7.2 ms and only 9% overhead, but the same 20 MB at 20 GB/s is 1.0 ms
        // and 39% overhead - so a byte guard stays silent exactly when a successful fix would
        // be under-reported by 40%. Wall time is rate-independent and self-correcting.
        let median_time = time_samples.get(time_samples.len() / 2).copied().unwrap_or(0.0);
        let share = if median_time > 0.0 { CALL_FLOOR_S / median_time } else { 1.0 };
        let mark = if share > 0.10 {
            overhead_flagged = true;
            "  <-- OVERHEAD"
        } else {
            ""
        };

        // The corrected rate, so the subtraction is not left as a manual step for whoever reads
        // the table. Every cross-shape comparison wants this column, because two shapes with
        // different wall times carry different overhead shares and the raw figures are not
        // directly comparable. The raw column stays because it is what `report_gemv_scaling`
        // prints, and dropping it would break comparability with every number already recorded.
        let corrected = if median_time > CALL_FLOOR_S * 1.05 {
            *bytes as f64 / (median_time - CALL_FLOOR_S) / 1e9
        } else {
            // Below ~1.05x the floor the subtraction is meaningless and would print a wild
            // number. Say so rather than emit one.
            f64::NAN
        };
        if corrected.is_nan() {
            println!("  {name}   {median:10.2}         --   {low:6.2}   {high:6.2}   {note}{mark}");
        } else {
            println!("  {name}   {median:10.2}   {corrected:8.2}   {low:6.2}   {high:6.2}   {note}{mark}");
        }
        // The analysis below is entirely cross-shape (F against A, G against C, and so on), so
        // it reads the corrected column. Where correction is impossible the raw value is used
        // and the row will already be flagged.
        medians.push((*name, if corrected.is_nan() { median } else { corrected }));
    }

    if overhead_flagged {
        println!();
        println!(
            "  OVERHEAD: rows marked above ran in under {:.1} ms, so the measured {:.2} ms",
            CALL_FLOOR_S * 10.0 * 1000.0,
            CALL_FLOOR_S * 1000.0,
        );
        println!("  per-call host round trip is >10% of their time. Prefer the corrected column,");
        println!("  and treat differences between flagged rows as weak. Raising GEMV_OUT is better.");
    }
    println!();
    println!("  raw  = bytes / t          comparable with report_gemv_scaling, which also does not correct");
    println!("  corr = bytes / (t - {:.2} ms)   host round trip removed; the analysis below uses this", CALL_FLOOR_S * 1000.0);

    report(&medians);


    unsafe {
        let device = &context.device;
        device.destroy_fence(fence, None);
        device.destroy_command_pool(command_pool, None);
        device.destroy_pipeline_layout(pipeline_layout, None);
        device.destroy_descriptor_pool(pool, None);
        device.destroy_descriptor_set_layout(layout, None);
    }
    Ok(())
}

/// Read the 2x2 out loud, so the log says which hypothesis it supports rather than leaving
/// it to whoever pastes the table into a message.
fn report(medians: &[(&str, f64)]) {
    let get = |i: usize| medians.get(i).map(|(_, v)| *v).unwrap_or(0.0);
    let (a, b, c, d, e, f, g) = (get(0), get(1), get(2), get(3), get(4), get(5), get(6));
    println!();
    println!("  A -> B  unpack costs         {:.2}x   (inside a stream)", ratio(a, b));
    println!("  B -> C  activations cost     {:.2}x   (inside a stream)", ratio(b, c));
    println!("  A -> D  depth is worth       {:.2}x   (light ALU)", ratio(a, d));
    println!("  C -> E  depth is worth       {:.2}x   (full ALU)", ratio(c, e));
    println!("  A -> F  the SHAPE costs      {:.2}x   (same trivial ALU, one-shot vs stream)", ratio(a, f));
    println!("  F -> G  the ALU costs        {:.2}x   (inside the real shape)", ratio(f, g));
    println!();

    // Deliberately coarse thresholds. This is a discriminating experiment, not a measurement
    // of an effect size, and a 1.3x is not something to build a plan on.
    let shape_costs = a > 0.0 && f < a * 0.5;
    let alu_costs_in_shape = f > 0.0 && g < f * 0.6;
    let alu_costs_in_stream = a > 0.0 && c < a * 0.6;
    let depth_helps = d > a * 1.3;

    match (shape_costs, alu_costs_in_shape) {
        (true, false) => println!(
            "  READS AS: THE SHAPE. A one-iteration loop with 16 idle lanes accounts for it, and\n           \
             the dequant arithmetic is nearly free. Fix = give each lane a stream of work:\n           \
             more taps per lane, no idle lanes, a loop that actually iterates. Integer dot\n           \
             product and W4A8 would buy little."
        ),
        (true, true) => println!(
            "  READS AS: BOTH. The shape costs {:.1}x and the arithmetic a further {:.1}x on top\n           \
             of it. Fix the shape first - it is the larger term and it is a loop-bounds change,\n           \
             not a numerics change.",
            ratio(a, f),
            ratio(f, g),
        ),
        (false, true) => println!(
            "  READS AS: THE ALU. The shape is nearly free and the dequant is the limiter.\n           \
             This revives VK_KHR_shader_integer_dot_product and possibly W4A8."
        ),
        (false, false) => println!(
            "  READS AS: NEITHER, which means G did not reproduce the real kernel. Check G against\n           \
             report_gemv_scaling's [65536, 1536] row before reading anything else here."
        ),
    }
    if alu_costs_in_stream && !alu_costs_in_shape {
        println!(
            "  NOTE: the dequant is expensive inside a stream (A->C) but not inside the real\n        \
             shape (F->G). That is consistent with the shape already being the bottleneck -\n        \
             there is no throughput left for the arithmetic to take away."
        );
    }
    if depth_helps {
        println!("  NOTE: deep prefetch beat the control by {:.2}x with light ALU.", ratio(d, a));
    }
    println!();
    println!("  TWO ANCHORS, check both before trusting anything above:");
    println!("    A should match report_read_path's SSBO figure (~20 G715, ~28 G4).");
    println!("    G should match report_gemv_scaling's [65536, 1536] row (~3.4-4.0).");
    println!("  If either is off, the harness is not reproducing what it claims to.");
}

fn ratio(from: f64, to: f64) -> f64 {
    if to <= 0.0 {
        return 0.0;
    }
    from / to
}

fn binding(at: u32) -> vk::DescriptorSetLayoutBinding<'static> {
    vk::DescriptorSetLayoutBinding::default()
        .binding(at)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .descriptor_count(1)
        .stage_flags(vk::ShaderStageFlags::COMPUTE)
}

fn write_set(context: &Arc<Context>, set: vk::DescriptorSet, source: &Buffer, sink: &Buffer, acts: &Buffer) {
    let infos = [
        vk::DescriptorBufferInfo { buffer: source.buffer, offset: 0, range: vk::WHOLE_SIZE },
        vk::DescriptorBufferInfo { buffer: sink.buffer, offset: 0, range: vk::WHOLE_SIZE },
        vk::DescriptorBufferInfo { buffer: acts.buffer, offset: 0, range: vk::WHOLE_SIZE },
    ];
    let writes: Vec<_> = infos
        .iter()
        .enumerate()
        .map(|(at, info)| {
            vk::WriteDescriptorSet::default()
                .dst_set(set)
                .dst_binding(at as u32)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(std::slice::from_ref(info))
        })
        .collect();
    // SAFETY: the set is live and nothing is in flight against it yet.
    unsafe { context.device.update_descriptor_sets(&writes, &[]) };
}

/// Fill the activation buffer with ordinary finite fp16, through a staging copy.
fn upload_activations(context: &Arc<Context>, acts: &Buffer) -> Result<(), String> {
    // 0.5, 1.0, 1.5 ... as fp16 bit patterns, cycling. Any finite spread does; what matters
    // is that none of them is NaN, Inf or denormal.
    let values: Vec<u16> = (0..act_elems())
        .map(|i| f32_to_f16(0.5 + (i % 8) as f32 * 0.25))
        .collect();
    let mut bytes = Vec::with_capacity(values.len() * 2);
    for value in &values {
        bytes.extend_from_slice(&value.to_le_bytes());
    }
    let staging = Buffer::staging(context, bytes.len() as vk::DeviceSize)?;
    staging.write(&bytes)?;
    one_shot(context, |device, cb| {
        let region = vk::BufferCopy::default().size(bytes.len() as vk::DeviceSize);
        // SAFETY: both buffers are live and the region is exactly the staging size.
        unsafe { device.cmd_copy_buffer(cb, staging.buffer, acts.buffer, std::slice::from_ref(&region)) };
    })
}

/// Round to fp16, for the handful of constants above. Round-to-nearest is not needed here.
fn f32_to_f16(value: f32) -> u16 {
    let bits = value.to_bits();
    let sign = ((bits >> 16) & 0x8000) as u16;
    let exponent = ((bits >> 23) & 0xFF) as i32 - 127 + 15;
    let mantissa = ((bits >> 13) & 0x3FF) as u16;
    if exponent <= 0 || exponent >= 31 {
        return sign;
    }
    sign | ((exponent as u16) << 10) | mantissa
}

/// Record, submit and wait for one throwaway command buffer.
fn one_shot(
    context: &Arc<Context>,
    record: impl FnOnce(&ash::Device, vk::CommandBuffer),
) -> Result<(), String> {
    let device = &context.device;
    // SAFETY: every handle is created, used and destroyed inside this call, and the fence is
    // waited on before anything is torn down.
    unsafe {
        let pool = device
            .create_command_pool(
                &vk::CommandPoolCreateInfo::default().queue_family_index(context.queue_family_index),
                None,
            )
            .map_err(|e| format!("one-shot pool: {e:?}"))?;
        let cb = device
            .allocate_command_buffers(
                &vk::CommandBufferAllocateInfo::default()
                    .command_pool(pool)
                    .level(vk::CommandBufferLevel::PRIMARY)
                    .command_buffer_count(1),
            )
            .map_err(|e| format!("one-shot buffer: {e:?}"))?
            .first()
            .copied()
            .ok_or("allocate_command_buffers returned nothing")?;
        device
            .begin_command_buffer(
                cb,
                &vk::CommandBufferBeginInfo::default()
                    .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT),
            )
            .map_err(|e| format!("one-shot begin: {e:?}"))?;
        record(device, cb);
        device.end_command_buffer(cb).map_err(|e| format!("one-shot end: {e:?}"))?;
        let fence = device
            .create_fence(&vk::FenceCreateInfo::default(), None)
            .map_err(|e| format!("one-shot fence: {e:?}"))?;
        let buffers = [cb];
        let submit = vk::SubmitInfo::default().command_buffers(&buffers);
        let guard = context.lock_queue();
        let sent = device.queue_submit(context.queue, std::slice::from_ref(&submit), fence);
        drop(guard);
        sent.map_err(|e| format!("one-shot submit: {e:?}"))?;
        device
            .wait_for_fences(&[fence], true, 20_000_000_000)
            .map_err(|e| format!("one-shot wait: {e:?}"))?;
        device.destroy_fence(fence, None);
        device.destroy_command_pool(pool, None);
    }
    Ok(())
}

struct Pipeline {
    module: vk::ShaderModule,
    pipeline: vk::Pipeline,
}

impl Pipeline {
    fn new(context: &Arc<Context>, layout: vk::PipelineLayout, spirv: &[u8]) -> Result<Pipeline, String> {
        if spirv.len() % (4) != 0 || spirv.len() < 20 {
            return Err(format!(
                "{} bytes is not a SPIR-V module - did the glslc step run? See analysis/gemv_probe/README.md",
                spirv.len()
            ));
        }
        let words: Vec<u32> = spirv
            .chunks_exact(4)
            .map(|w| u32::from_le_bytes([w[0], w[1], w[2], w[3]]))
            .collect();
        // SAFETY: both handles are destroyed by `destroy`, and the module outlives the
        // pipeline creation that reads it.
        unsafe {
            let module = context
                .device
                .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&words), None)
                .map_err(|e| format!("shader module: {e:?}"))?;
            let name = std::ffi::CString::new("main").map_err(|e| format!("{e}"))?;
            let stage = vk::PipelineShaderStageCreateInfo::default()
                .stage(vk::ShaderStageFlags::COMPUTE)
                .module(module)
                .name(&name);
            let pipeline = context
                .device
                .create_compute_pipelines(
                    vk::PipelineCache::null(),
                    &[vk::ComputePipelineCreateInfo::default().stage(stage).layout(layout)],
                    None,
                )
                .map_err(|(_, e)| format!("compute pipeline: {e:?}"))?
                .first()
                .copied()
                .ok_or("create_compute_pipelines returned nothing")?;
            Ok(Pipeline { module, pipeline })
        }
    }

    fn destroy(&self, context: &Arc<Context>) {
        // SAFETY: called after the last fence wait, so nothing references either handle.
        unsafe {
            context.device.destroy_pipeline(self.pipeline, None);
            context.device.destroy_shader_module(self.module, None);
        }
    }
}

/// The Vulkan handles one timed pass binds, bundled so `time_pass` takes
/// the pass plus its two sizes.
struct Pass<'a> {
    context: &'a Arc<Context>,
    cb: vk::CommandBuffer,
    fence: vk::Fence,
    layout: vk::PipelineLayout,
    set: vk::DescriptorSet,
    pipeline: &'a Pipeline,
}

/// Seconds for one pass.
fn time_pass(pass: Pass<'_>, quads: u32, groups: u32) -> Result<f64, String> {
    let Pass { context, cb, fence, layout, set, pipeline } = pass;
    let device = &context.device;
    // `quads` for the streaming variants, `in_c` and `count` for the gemv-shaped ones. All
    // three are pushed for every variant; a shader that does not declare a field ignores it.
    // For F and G, `groups` **is** `count` — one workgroup per `ROWS` output channels.
    let mut push = [0u8; 12];
    for (slot, value) in [quads, gemv_in(), groups].iter().enumerate() {
        if let Some(dst) = push.get_mut(slot * 4..slot * 4 + 4) {
            dst.copy_from_slice(&value.to_le_bytes());
        }
    }
    // SAFETY: the buffer is reset and re-recorded here and the fence is waited on before
    // this returns, so nothing is ever in flight across two calls.
    unsafe {
        device
            .begin_command_buffer(cb, &vk::CommandBufferBeginInfo::default())
            .map_err(|e| format!("begin: {e:?}"))?;
        device.cmd_bind_pipeline(cb, vk::PipelineBindPoint::COMPUTE, pipeline.pipeline);
        device.cmd_bind_descriptor_sets(cb, vk::PipelineBindPoint::COMPUTE, layout, 0, &[set], &[]);
        device.cmd_push_constants(
            cb,
            layout,
            vk::ShaderStageFlags::COMPUTE,
            0,
            &push,
        );
        device.cmd_dispatch(cb, groups, 1, 1);
        device.end_command_buffer(cb).map_err(|e| format!("end: {e:?}"))?;

        let started = Instant::now();
        device.reset_fences(&[fence]).map_err(|e| format!("reset: {e:?}"))?;
        let buffers = [cb];
        let submit = vk::SubmitInfo::default().command_buffers(&buffers);
        let guard = context.lock_queue();
        let sent = device.queue_submit(context.queue, std::slice::from_ref(&submit), fence);
        drop(guard);
        sent.map_err(|e| format!("submit: {e:?}"))?;
        device
            .wait_for_fences(&[fence], true, 20_000_000_000)
            .map_err(|e| format!("wait: {e:?}"))?;
        Ok(started.elapsed().as_secs_f64())
    }
}
