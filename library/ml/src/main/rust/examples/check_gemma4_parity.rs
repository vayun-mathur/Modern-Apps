//! Runs Gemma 4's decode step on the GPU and compares its logits with onnxruntime's.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example check_gemma4_parity -- \
//!     gemma4_text.maml gemma4_embed.maml logits_golden.json
//! ```
//!
//! # This is the check nothing else substitutes for
//!
//! Every other test in this port checks *layout* - that 999 tensors are declared in the order the
//! converter writes them, that a scale has the rank its precision implies, that one op agrees
//! with a CPU oracle. All of that can pass while the model is wrong, because a transposed read
//! agrees with itself and a doubled scale is still a number.
//!
//! Thirty-five layers in sequence against real weights is the only thing that catches an error of
//! *composition*: a residual added before its norm instead of after, a rotary applied to the key
//! but not the query, a cache written at the wrong position. Those produce plausible logits.
//!
//! # What agreement to expect
//!
//! The reference is fp16 weights in fp32 arithmetic; this is int4 weights with a per-block scale.
//! The logits will not match to the bit and should not be expected to. What must match is the
//! **argmax** and the broad ordering of the top few - a model that is right differs from the
//! reference by quantisation noise, and a model that is wrong differs by rearranging the ranking.
use std::path::PathBuf;
use std::sync::Arc;

use modelrunner::nets::gemma4;
use modelrunner::nets::gemma4_head::HeadOut;
use modelrunner::vulkan::context;
use modelrunner::vulkan::reshape::Reshaped;
use modelrunner::vulkan::run::StepParams;
use modelrunner::weights::{Streamed, graph};

/// The app's starting cache tier (1024 positions, 19 MB): the 16K tier adds 280 MB of KV, which
/// the Pixel 8 cannot spare beside TEXT and the head.
const TIER: u32 = gemma4::CONTEXT_TIERS[0];

/// Open both `.maml` files as [`Streamed`] instead of `std::fs::read` + [`Weights::parse`].
///
/// The old path held 1.09 GB + 2.39 GB resident and then copied each data section again
/// inside `parse`, which is ~7 GB of peak host RSS against the ~2.4 GB a 7 GB Pixel 8 has
/// free — the OOM-reboot this harness caused twice. `Streamed::open` reads only the
/// ~64 KB header/table prefix; the data section stays in the file and is pulled through
/// `Net`'s 8 MB chunked staging upload plus row-at-a-time host gathers, so peak host RSS
/// is tens of MB. Identical bytes reach the device (`read_at` over the same section).
fn open_streamed(path: &PathBuf, expect_graph: u32) -> Result<Streamed, String> {
    let file = std::fs::File::open(path)
        .map_err(|e| format!("cannot open {}: {e}", path.display()))?;
    let len = file
        .metadata()
        .map_err(|e| format!("cannot size {}: {e}", path.display()))?
        .len();
    Streamed::open(file, 0, len, expect_graph)
}

/// Every GPU net this example builds goes through here.
///
/// `Reshaped::streamed` uploads the whole file it is given, so a net over
/// gemma4_embed.maml is a 3.6 GB upload on top of TEXT: the Pixel 8 OOM
/// kernel panic. EMBED is read on the host only, a row at a time.
fn streamed_net<S: Copy + PartialEq>(
    context: &Arc<context::Context>,
    weights: &Streamed,
    shape: S,
    plan: fn(&modelrunner::weights::Offsets, S) -> Result<modelrunner::nets::Plan, String>,
) -> Result<Reshaped<S>, String> {
    if weights.graph_id == graph::GEMMA4_EMBED {
        return Err("refusing to upload gemma4_embed.maml to the GPU (3.6 GB); pass --head".into());
    }
    Reshaped::streamed(Arc::clone(context), weights.offsets(), weights, shape, plan)
}

fn gemma4_plan(offsets: &modelrunner::weights::Offsets, pass: gemma4::Pass) -> Result<modelrunner::nets::Plan, String> {
    gemma4::build(offsets, pass)
}

/// Whether the text file carries the combine, so plans take raw per-layer rows (set in `main`).
static FUSED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

fn fused() -> bool {
    FUSED.load(std::sync::atomic::Ordering::Relaxed)
}

/// One token's text-plan inputs 0 and 1 for this run's text file.
fn gather_in(embed: &modelrunner::weights::Reader<'_>, token: u32) -> Result<(Vec<f32>, Vec<f32>), String> {
    gemma4::gather_input(embed, token, fused())
}

fn head_plan(offsets: &modelrunner::weights::Offsets, out: HeadOut) -> Result<modelrunner::nets::Plan, String> {
    modelrunner::nets::gemma4_head::build_plan_standalone(offsets, out)
}

struct Args {
    positional: Vec<String>,
    head: Option<PathBuf>,
    time: Option<usize>,
    prefill: Option<Vec<u32>>,
    sample: bool,
    topk: bool,
    split: Option<u32>,
    len: Option<u32>,
    gather: bool,
    soft: bool,
    spread: bool,
}

/// `--head <path>`, `--time <repeats>`, `--prefill <tokens>`, `--sample`; the
/// rest are positional: text embed golden [trace] [tail] [head].
fn parse_args() -> Args {
    let mut args =
        Args { positional: Vec::new(), head: None, time: None, prefill: None, sample: false, topk: false, split: None, len: None, gather: false, soft: false, spread: false };
    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        match arg.as_str() {
            "--head" => args.head = it.next().map(PathBuf::from),
            "--time" => args.time = it.next().and_then(|n| n.parse().ok()),
            "--prefill" => args.prefill = it.next().map(|n| n.split(',').filter_map(|w| w.parse().ok()).collect()),
            "--sample" => args.sample = true,
            "--topk" => args.topk = true,
            "--split" => args.split = it.next().and_then(|n| n.parse().ok()),
            "--len" => args.len = it.next().and_then(|n| n.parse().ok()),
            "--gather" => args.gather = true,
            "--soft" => args.soft = true,
            "--spread" => args.spread = true,
            _ => args.positional.push(arg),
        }
    }
    if args.head.is_none() {
        args.head = args.positional.get(5).map(PathBuf::from);
    }
    args
}

fn main() {
    let args = parse_args();
    let (Some(text), Some(embed), Some(golden)) = (
        args.positional.first().map(PathBuf::from),
        args.positional.get(1).map(PathBuf::from),
        args.positional.get(2),
    ) else {
        println!(
            "usage: check_gemma4_parity <text.maml> <embed.maml> <golden.json> [trace] [tail] \
             [--head head.maml] [--time N [--sample] [--topk]] [--prefill N] [--split K [--len N]]"
        );
        return;
    };
    let weights = match open_streamed(&text, graph::GEMMA4_TEXT) {
        Ok(w) => w,
        Err(why) => return println!("the text model does not stream: {why}"),
    };
    FUSED.store(gemma4::combines_in_plan(&weights.offsets()), std::sync::atomic::Ordering::Relaxed);
    println!("text plans combine the per-layer rows: {}", fused());
    let mut embed_weights = match open_streamed(&embed, graph::GEMMA4_EMBED) {
        Ok(w) => w,
        Err(why) => return println!("the embedding does not stream: {why}"),
    };
    println!("embed rows mapped: {}", embed_weights.map());
    let head_weights: Option<Streamed> = match &args.head {
        Some(path) => match open_streamed(path, graph::GEMMA4_HEAD) {
            Ok(w) => Some(w),
            Err(why) => return println!("the head file does not stream: {why}"),
        },
        None => None,
    };
    let golden = match std::fs::read_to_string(&golden) {
        Ok(text) => text,
        Err(why) => return println!("cannot read the golden: {why}"),
    };
    let (tokens, want_ids, want_logits) = parse_golden(&golden);
    if tokens.is_empty() {
        return println!("the golden holds no tokens");
    }
    println!("tokens {tokens:?}");

    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => return println!("no Vulkan device: {why}"),
    };
    if let Some(repeats) = args.time {
        let mode = if args.topk { HeadOut::TopK } else if args.sample { HeadOut::Logits } else { HeadOut::Greedy };
        time_decode(&context, &weights, &embed_weights, head_weights.as_ref(), &tokens, repeats, mode, args.gather);
        return print_memory();
    }
    if args.soft {
        if let Err(why) = soft_check(&context, &weights, &embed_weights, head_weights.as_ref(), &tokens) {
            println!("the soft check failed to run: {why}");
        }
        return print_memory();
    }
    if args.gather {
        if let Err(why) = gather_check(&context, &embed_weights, &tokens) {
            println!("the gather check failed: {why}");
        }
        return print_memory();
    }
    if let Some(k) = args.split {
        let n = args.len.unwrap_or(tokens.len() as u32) as usize;
        let prompt: Vec<u32> = tokens.iter().copied().cycle().take(n).collect();
        if let Err(why) = split_check(&context, &weights, &embed_weights, head_weights.as_ref(), &prompt, k) {
            println!("the split check failed to run: {why}");
        }
        return print_memory();
    }
    if let Some(widths) = args.prefill {
        // `--spread`: a prompt of distinct ids, where no row read repeats (the golden cycles 6).
        let spread: Vec<u32> = (0..TIER).map(|i| (i * 7919 + 1000) % 250_000).collect();
        let prompt = if args.spread { &spread } else { &tokens };
        time_prefill(&context, &weights, &embed_weights, head_weights.as_ref(), prompt, &widths);
        return print_memory();
    }
    match run(&context, &weights, &embed_weights, head_weights.as_ref(), &tokens) {
        Ok(logits) => report(&logits, &want_ids, &want_logits),
        Err(why) => println!("the decode failed: {why}"),
    }
    print_memory();

    // Bisect: where do the two first disagree? Only useful when the logits already failed, but
    // cheap enough to always print.
    let Some(trace_path) = args.positional.get(3) else { return };
    let Ok(trace) = std::fs::read_to_string(trace_path) else {
        return println!("cannot read {trace_path}");
    };
    println!();
    println!("bisect against the reference's intermediates");
    for (label, layers) in [("ple_combined", 0usize), ("l0_out", 1), ("l1_out", 2)] {
        let Some(want) = field(&trace, label) else {
            println!("  {label:<14} not in the trace file");
            continue;
        };
        match trace_run(&context, &weights, &embed_weights, &tokens, layers) {
            Ok((hidden, per_layer)) => {
                let got: &[f32] = if label == "ple_combined" { &per_layer } else { &hidden };
                println!("  {label:<14} {}", compare(got, &want));
            }
            Err(why) => println!("  {label:<14} failed: {why}"),
        }
    }

    // L0 tail bisect: the four branch vectors against the numpy-fp oracle, in
    // tail order. The first label where the device drops below 0.99 while numpy
    // holds is the divergence point (see `Mode::TraceTail`).
    let Some(tail_path) = args.positional.get(4) else { return };
    let Ok(tail) = std::fs::read_to_string(tail_path) else {
        return println!("cannot read {tail_path}");
    };
    println!();
    println!("L0 tail against the numpy oracle");
    match tail_run(&context, &weights, &embed_weights, &tokens) {
        Ok(branches) => {
            for (label, got) in
                ["attn_branch", "mlp_branch", "ple_branch", "l0_out"].into_iter().zip(branches.iter())
            {
                match field(&tail, label) {
                    Some(want) => println!("  {label:<14} {}", compare(got, &want)),
                    None => println!("  {label:<14} not in the tail file"),
                }
            }
        }
        Err(why) => println!("  the tail trace failed: {why}"),
    }
}

/// Cosine and relative RMS between a device tensor and the reference's.
fn compare(got: &[f32], want: &[f32]) -> String {
    if got.len() != want.len() {
        return format!("length {} against {}", got.len(), want.len());
    }
    let dot: f64 = got.iter().zip(want).map(|(a, b)| f64::from(*a) * f64::from(*b)).sum();
    let na: f64 = got.iter().map(|a| f64::from(*a) * f64::from(*a)).sum::<f64>().sqrt();
    let nb: f64 = want.iter().map(|b| f64::from(*b) * f64::from(*b)).sum::<f64>().sqrt();
    let cosine = dot / (na * nb).max(1e-30);
    let verdict = if cosine > 0.99 { "ok" } else { "WRONG" };
    format!("cosine {cosine:.6}  rms {:.4} against {:.4}  {verdict}", na / got.len() as f64, nb / want.len() as f64)
}

/// One named array from the trace file.
fn field(text: &str, key: &str) -> Option<Vec<f32>> {
    let at = text.find(&format!("\"{key}\""))?;
    let rest = &text[at..];
    let open = rest.find('[')?;
    let close = rest[open..].find(']')?;
    Some(rest[open + 1..open + close].split(',').filter_map(|p| p.trim().parse().ok()).collect())
}

/// [`run`] stopping after `layers` layers, returning `(hidden, per_layer)`.
fn trace_run(
    context: &Arc<context::Context>,
    weights: &Streamed,
    embed: &Streamed,
    tokens: &[u32],
    layers: usize,
) -> Result<(Vec<f32>, Vec<f32>), String> {
    let mode = gemma4::Mode::Trace { layers }.at(TIER);
    let mut net = streamed_net(context, weights, mode, gemma4_plan)?;
    let reader = embed.reader();
    let rotary = weights.reader();
    let mut last = (Vec::new(), Vec::new());
    for (step, &token) in tokens.iter().enumerate() {
        let position = u32::try_from(step).map_err(|_| "a step past u32")?;
        let (hidden, per_layer) = gather_in(&reader, token)?;
        let angles_local = rotary_row(&rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, position)?;
        let angles_global =
            rotary_row(&rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, position)?;
        let at = net.at(mode)?;
        at.set_params(StepParams {
            prefix: position,
            window_start: position.saturating_sub(gemma4::WINDOW - 1),
        })?;
        let out = at.infer_raw_many(&[&hidden, &per_layer, &angles_local, &angles_global])?;
        let mut it = out.into_iter();
        last = (it.next().unwrap_or_default(), it.next().unwrap_or_default());
    }
    Ok(last)
}

/// Layer 0's four tail branches at the last step, in tail order
/// (`attn_branch`, `mlp_branch`, `ple_branch`, `l0_out`).
///
/// Same OOM-safe shape as [`trace_run`]: the KV cache fills one step at a time and only
/// the last step's four 1536-float branches are kept.
fn tail_run(
    context: &Arc<context::Context>,
    weights: &Streamed,
    embed: &Streamed,
    tokens: &[u32],
) -> Result<[Vec<f32>; 4], String> {
    let mode = gemma4::Mode::TraceTail.at(TIER);
    let mut net = streamed_net(context, weights, mode, gemma4_plan)?;
    let reader = embed.reader();
    let rotary = weights.reader();
    let mut last = [Vec::new(), Vec::new(), Vec::new(), Vec::new()];
    for (step, &token) in tokens.iter().enumerate() {
        let position = u32::try_from(step).map_err(|_| "a step past u32")?;
        let (hidden, per_layer) = gather_in(&reader, token)?;
        let angles_local = rotary_row(&rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, position)?;
        let angles_global =
            rotary_row(&rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, position)?;
        let at = net.at(mode)?;
        at.set_params(StepParams {
            prefix: position,
            window_start: position.saturating_sub(gemma4::WINDOW - 1),
        })?;
        let out = at.infer_raw_many(&[&hidden, &per_layer, &angles_local, &angles_global])?;
        if out.len() != 4 {
            return Err(format!("a tail step returned {} tensors, not the four branches", out.len()));
        }
        let mut it = out.into_iter();
        last = [
            it.next().unwrap_or_default(),
            it.next().unwrap_or_default(),
            it.next().unwrap_or_default(),
            it.next().unwrap_or_default(),
        ];
    }
    Ok(last)
}

/// One decode step's host and device split, in ms.
#[derive(Default)]
struct StepTimes {
    rows: f64,
    combine: f64,
    infer: f64,
    head: f64,
}

/// One token through the app's decode path: host rows + combine, the
/// transformer submit, then the head submit. Returns the next token (device
/// argmax, or the host argmax over softcapped logits with `sample`).
#[allow(clippy::too_many_arguments)]
fn decode_one(
    net: &mut Reshaped<gemma4::Pass>,
    head: Option<&mut Reshaped<HeadOut>>,
    gather: Option<&mut Reshaped<u32>>,
    embed: &modelrunner::weights::Reader<'_>,
    rotary: &modelrunner::weights::Reader<'_>,
    token: u32,
    position: u32,
    mode: HeadOut,
    times: &mut StepTimes,
) -> Result<Option<u32>, String> {
    use std::time::Instant;
    let (hidden_in, per_layer, gtimes) = match gather {
        _ if fused() => {
            let tick = Instant::now();
            let (hidden, embedded) = gemma4::gather_input(embed, token, true)?;
            let rows_ms = tick.elapsed().as_secs_f64() * 1000.0;
            (hidden, embedded, gemma4::GatherTimes { rows_ms, bulk_ms: 0.0, matmul_ms: 0.0 })
        }
        Some(gather) => {
            let tick = Instant::now();
            let hidden = embed.int4_row(
                gemma4::embed::TOKENS,
                gemma4::embed::TOKENS + 1,
                &[gemma4::VOCAB, gemma4::D_MODEL],
                token,
            )?;
            let row = if gemma4::embed::PLACEHOLDERS.contains(&token) { 0 } else { token };
            let mut embedded = Vec::with_capacity((gemma4::PER_LAYER as usize) * gemma4::LAYERS);
            for i in 0..gemma4::LAYERS {
                let at = gemma4::embed::table(i);
                embedded.extend(embed.int4_row(at, at + 1, &[gemma4::VOCAB, gemma4::PER_LAYER], row)?);
            }
            let rows_ms = tick.elapsed().as_secs_f64() * 1000.0;
            let tick = Instant::now();
            let out = gather.at(1)?.infer_raw_many(&[&hidden, &embedded])?;
            let per_layer = out.into_iter().next().ok_or("the gather net returned nothing")?;
            let matmul_ms = tick.elapsed().as_secs_f64() * 1000.0;
            (hidden, per_layer, gemma4::GatherTimes { rows_ms, bulk_ms: 0.0, matmul_ms })
        }
        None => gemma4::gather_timed(embed, token)?,
    };
    let rows_tick = Instant::now();
    let angles_local = rotary_row(rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, position)?;
    let angles_global = rotary_row(rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, position)?;
    times.rows += gtimes.rows_ms + gtimes.bulk_ms + rows_tick.elapsed().as_secs_f64() * 1000.0;
    times.combine += gtimes.matmul_ms;
    let tick = Instant::now();
    let at = net.at(gemma4::Mode::DecodeStep.at(TIER))?;
    at.set_params(StepParams {
        prefix: position,
        window_start: position.saturating_sub(gemma4::WINDOW - 1),
    })?;
    let out = at.infer_raw_many(&[&hidden_in, &per_layer, &angles_local, &angles_global])?;
    times.infer += tick.elapsed().as_secs_f64() * 1000.0;
    let Some(head) = head else { return Ok(None) };
    let hidden = out.into_iter().next().ok_or("the step returned nothing")?;
    let tick = Instant::now();
    let out = head.at(mode)?.infer_raw_many(&[&hidden])?;
    let id = if mode == HeadOut::TopK {
        let top = out.first().ok_or("the top-k head returned nothing")?;
        top[2] as u32 * 2048 + top[1] as u32
    } else if mode == HeadOut::Logits {
        let logits = softcapped(&out)?;
        let mut best = (f32::NEG_INFINITY, 0u32);
        for (i, &v) in logits.iter().enumerate() {
            if v > best.0 {
                best = (v, i as u32);
            }
        }
        best.1
    } else {
        let id = out.last().ok_or("the greedy head returned nothing")?;
        if id.len() != 2 {
            return Err(format!("greedy id holds {} values, not 2", id.len()));
        }
        id[1] as u32 * 2048 + id[0] as u32
    };
    times.head += tick.elapsed().as_secs_f64() * 1000.0;
    Ok(Some(id))
}

fn build_head(context: &Arc<context::Context>, head: Option<&Streamed>, mode: HeadOut) -> Result<Option<Reshaped<HeadOut>>, String> {
    match head {
        Some(file) => streamed_net(context, file, mode, head_plan).map(Some),
        None => {
            println!("no --head: timings exclude the head");
            Ok(None)
        }
    }
}

/// Decode-speed probe: the app's per-token loop repeated `repeats` times over
/// the golden's tokens. Pass 0 is warmup; the rest are steady state. With a
/// head file each step includes the head: the device-greedy plan, or with
/// `--sample` the full-logits readback + softcap the sampling path pays.
#[allow(clippy::too_many_arguments)]
fn time_decode(
    context: &Arc<context::Context>,
    weights: &Streamed,
    embed: &Streamed,
    head: Option<&Streamed>,
    tokens: &[u32],
    repeats: usize,
    mode: HeadOut,
    device_gather: bool,
) {
    use std::time::Instant;
    let mut net = match streamed_net(context, weights, gemma4::Mode::DecodeStep.at(TIER), gemma4_plan) {
        Ok(net) => net,
        Err(why) => return println!("the net does not build: {why}"),
    };
    let mut gather_net = if device_gather {
        match gather_plan(context, embed) {
            Ok(net) => Some(net),
            Err(why) => return println!("the gather net does not build: {why}"),
        }
    } else {
        None
    };
    let mut head_net = match build_head(context, head, mode) {
        Ok(net) => net,
        Err(why) => return println!("the head does not build: {why}"),
    };
    let reader = embed.reader();
    let rotary = weights.reader();
    let stamps = modelrunner::knobs::is_set("timestamps");
    let repeats = repeats.max(1);
    let mut steady: Vec<f64> = Vec::new();
    let mut split = StepTimes::default();
    let mut ids = Vec::new();
    for pass in 0..repeats {
        let mut step_ms: Vec<f64> = Vec::with_capacity(tokens.len());
        ids.clear();
        for (step, &token) in tokens.iter().enumerate() {
            let tick = Instant::now();
            let mut times = StepTimes::default();
            match decode_one(&mut net, head_net.as_mut(), gather_net.as_mut(), &reader, &rotary, token, step as u32, mode, &mut times) {
                Ok(id) => ids.extend(id),
                Err(why) => return println!("step {step} failed: {why}"),
            }
            step_ms.push(tick.elapsed().as_secs_f64() * 1000.0);
            if pass > 0 {
                split.rows += times.rows;
                split.combine += times.combine;
                split.infer += times.infer;
                split.head += times.head;
            }
        }
        let mean = step_ms.iter().sum::<f64>() / step_ms.len().max(1) as f64;
        println!("pass {pass}: {} steps, mean {mean:.1} ms/step", step_ms.len());
        if pass > 0 {
            steady.extend(step_ms);
        }
    }
    println!("next-token ids {ids:?}");
    if !steady.is_empty() {
        let n = steady.len() as f64;
        let mean = steady.iter().sum::<f64>() / n;
        println!(
            "steady-state ({}): {mean:.1} ms/token = {:.2} tok/s",
            if head_net.is_none() { "no head" } else { match mode { HeadOut::Logits => "sampled head", HeadOut::TopK => "top-k head", HeadOut::Greedy => "greedy head" } },
            1000.0 / mean.max(1e-9)
        );
        println!(
            "per step: rows {:.1} ms, combine {:.1} ms, infer {:.1} ms, head {:.1} ms ({} steps)",
            split.rows / n,
            split.combine / n,
            split.infer / n,
            split.head / n,
            steady.len()
        );
    }
    if stamps {
        match net.at(gemma4::Mode::DecodeStep.at(TIER)).and_then(|at| at.op_times()) {
            Ok(report) => {
                print_kinds("decode step", &report);
                if let Ok(plan) = gemma4::build(&weights.offsets(), gemma4::Mode::DecodeStep.at(TIER)) {
                    print_shapes(&report, &plan);
                }
            }
            Err(why) => println!("timestamps unreadable: {why}"),
        }
        if let Some(head) = head_net.as_mut() {
            match head.at(mode).and_then(|at| at.op_times()) {
                Ok(report) => print_kinds("head", &report),
                Err(why) => println!("head timestamps unreadable: {why}"),
            }
        }
    }
}

/// Write one position's `values` into column `column` of a `[C, 1, width]` block.
fn place(into: &mut [f32], values: &[f32], column: u32, width: u32) {
    for (channel, &value) in values.iter().enumerate() {
        into[channel * width as usize + column as usize] = value;
    }
}

/// The device combine over the EMBED file, uploading only the tensors it reads, keyed by width.
fn gather_plan(context: &Arc<context::Context>, embed: &Streamed) -> Result<Reshaped<u32>, String> {
    Reshaped::windowed(Arc::clone(context), embed.offsets(), embed, &[1], |offsets, width| {
        modelrunner::nets::gemma4_gather::build_plan_at(offsets, width)
    })
}

/// Positions one prefill submit covers. Mirrors the bridge's `CHUNK`.
const CHUNK: usize = 256;

/// Milliseconds per stage of one prompt, summed over its chunks.
#[derive(Default)]
struct PrefillTimes {
    rows: f64,
    combine: f64,
    rotary: f64,
    plan_wait: f64,
    record: f64,
    gpu: f64,
    /// The last token's `Mode::Tail` submit.
    tail: f64,
    /// Per-kind GPU tables of the last chunk's prefill and tail, under `MODELRUNNER_TIMESTAMPS`.
    reports: Vec<(&'static str, Vec<(String, f64, f64)>)>,
    /// The last token's final-normed state, which the head reads.
    state: Vec<f32>,
}

/// `prompt` at position 0 exactly as `Gemma4Handle::prefill` feeds it: per chunk (padded to
/// `gemma4_gather::padded` when `pad`), the host rows straight into `[C, 1, T]` while an evicted
/// plan builds on another thread, one device combine, the rotary rows, then the Prefill submit.
fn prefill_app(
    net: &mut Reshaped<gemma4::Pass>,
    gather: Option<&mut Reshaped<u32>>,
    embed: &modelrunner::weights::Reader<'_>,
    rotary: &modelrunner::weights::Reader<'_>,
    prompt: &[u32],
    pad: bool,
) -> Result<PrefillTimes, String> {
    use modelrunner::nets::gemma4_gather as g;
    use std::time::Instant;
    let mut times = PrefillTimes::default();
    let mut gather = gather;
    for (index, chunk) in prompt.chunks(CHUNK).enumerate() {
        let start = (index * CHUNK) as u32;
        let width = if pad { g::padded(chunk.len() as u32, TIER - start) } else { chunk.len() as u32 };
        let mut fed = chunk.to_vec();
        fed.resize(width as usize, 0);
        let mode = if start == 0 { gemma4::Mode::PrefillFresh { tokens: width } } else { gemma4::Mode::Prefill { tokens: width } }.at(TIER);
        let mut pending = (!net.holds(mode)).then(|| net.planner());
        let tick = Instant::now();
        if fused() {
            // As `Gemma4Handle::prefill_chunk`: built on this thread, which keeps its core awake.
            if let Some((plan, offsets)) = pending.take() {
                net.adopt(mode, plan(&offsets, mode)?);
            }
        }
        times.plan_wait += tick.elapsed().as_secs_f64() * 1000.0;
        let tick = Instant::now();
        let building = pending.map(|(plan, offsets)| std::thread::spawn(move || plan(&offsets, mode)));
        let tables = if fused() { gemma4::OWNS_CACHE_LAYERS } else { gemma4::LAYERS };
        let (hidden, embedded) = g::token_rows(embed, &fed).and_then(|h| Ok((h, g::leading_layer_rows(embed, &fed, tables)?)))?;
        let tail_rows = if fused() { Some(g::layer_rows(embed, &chunk[chunk.len() - 1..])?) } else { None };
        times.rows += tick.elapsed().as_secs_f64() * 1000.0;
        let tick = Instant::now();
        let per_layer = if fused() {
            embedded
        } else {
            let gather = gather.as_deref_mut().ok_or("no gather net for an unfused text file")?;
            gather.at(width)?.infer_raw_many(&[&hidden, &embedded])?.into_iter().next().ok_or("no combine")?
        };
        times.combine += tick.elapsed().as_secs_f64() * 1000.0;
        let tick = Instant::now();
        if let Some(thread) = building {
            let plan = thread.join().map_err(|_| "the plan thread panicked".to_string())?;
            net.adopt(mode, plan?);
        }
        times.plan_wait += tick.elapsed().as_secs_f64() * 1000.0;
        let tick = Instant::now();
        let mut local = vec![0f32; (gemma4::HEAD_DIM * width) as usize];
        let mut global = vec![0f32; (gemma4::GLOBAL_HEAD_DIM * width) as usize];
        for column in 0..width {
            let position = start + column;
            place(&mut local, &rotary_row(rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, position)?, column, width);
            place(&mut global, &rotary_row(rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, position)?, column, width);
        }
        times.rotary += tick.elapsed().as_secs_f64() * 1000.0;
        let tick = Instant::now();
        let at = net.at(mode)?;
        times.record += tick.elapsed().as_secs_f64() * 1000.0;
        let tick = Instant::now();
        at.set_params(StepParams { prefix: start, window_start: start.saturating_sub(gemma4::WINDOW - 1) })?;
        let out = at.infer_raw_many(&[&hidden, &per_layer, &local, &global])?;
        times.gpu += tick.elapsed().as_secs_f64() * 1000.0;
        let stamps = modelrunner::knobs::is_set("timestamps");
        times.reports = if stamps { at.op_times().map(|r| vec![("prefill", r)]).unwrap_or_default() } else { Vec::new() };
        if (index + 1) * CHUNK < prompt.len() {
            continue;
        }
        // As `Gemma4Handle::tail`: the last real column through the layers that own no cache.
        let tick = Instant::now();
        let last = chunk.len() - 1;
        let residual = out.first().ok_or("a prefill returned nothing")?;
        let mut inputs = gemma4::tail_inputs(residual, [&hidden, &per_layer, &local, &global], last, width as usize, fused());
        if let Some(rows) = tail_rows {
            inputs[1] = rows;
        }
        let at = net.at(gemma4::Mode::Tail.at(TIER))?;
        let position = start + last as u32;
        at.set_params(StepParams { prefix: position, window_start: position.saturating_sub(gemma4::WINDOW - 1) })?;
        let refs: Vec<&[f32]> = inputs.iter().map(Vec::as_slice).collect();
        times.state = at.infer_raw_many(&refs)?.into_iter().next().ok_or("the tail returned nothing")?;
        times.tail += tick.elapsed().as_secs_f64() * 1000.0;
        if let Some(report) = stamps.then(|| at.op_times().ok()).flatten() {
            times.reports.push(("tail", report));
        }
    }
    Ok(times)
}

/// TTFT as the app sees it: `prefill_app` for a prompt of each width (the golden's tokens cycled),
/// then the first greedy step (device combine, DecodeStep record, step, head). Each width runs
/// cold then warm, and the widths run back to back as consecutive prompts would.
fn time_prefill(
    context: &Arc<context::Context>,
    weights: &Streamed,
    embed: &Streamed,
    head: Option<&Streamed>,
    tokens: &[u32],
    widths: &[u32],
) {
    use std::time::Instant;
    let mut net = match streamed_net(context, weights, gemma4::Mode::DecodeStep.at(TIER), gemma4_plan) {
        Ok(net) => net,
        Err(why) => return println!("the net does not build: {why}"),
    };
    let mut head_net = match build_head(context, head, HeadOut::Greedy) {
        Ok(net) => net,
        Err(why) => return println!("the head does not build: {why}"),
    };
    let mut gather = match (!fused()).then(|| gather_plan(context, embed)).transpose() {
        Ok(net) => net,
        Err(why) => return println!("the gather net does not build: {why}"),
    };
    // As `build_gemma4` warms at load: the arena at the widest chunk, then the decode step.
    let tick = Instant::now();
    let warmed = (|| -> Result<(), String> {
        net.at(gemma4::Mode::Prefill { tokens: 256 }.at(TIER))?;
        net.at(gemma4::Mode::PrefillFresh { tokens: 256 }.at(TIER))?;
        net.at(gemma4::Mode::Tail.at(TIER))?;
        net.at(gemma4::Mode::DecodeStep.at(TIER))?;
        for width in [256, 1] {
            if let Some(gather) = gather.as_mut() {
                gather.at(width)?;
            }
        }
        if let Some(head) = head_net.as_mut() {
            for out in [HeadOut::Logits, HeadOut::TopK, HeadOut::Greedy] {
                head.at(out)?;
            }
        }
        Ok(())
    })();
    if let Err(why) = warmed {
        return println!("the warm-up failed: {why}");
    }
    println!("load-time warm-up {:.0} ms", tick.elapsed().as_secs_f64() * 1000.0);
    let reader = embed.reader();
    let rotary = weights.reader();
    let stamps = modelrunner::knobs::is_set("timestamps");
    let mut prefill_report = None;
    for &width in widths {
        if width == 0 || width >= TIER {
            println!("a {width}-token prompt does not fit the {TIER}-position cache");
            continue;
        }
        let prompt: Vec<u32> = tokens.iter().copied().cycle().take(width as usize).collect();
        let next = tokens[width as usize % tokens.len()];
        // The app's flow: Kotlin pushes the prompt, then steps its last token; native runs them as
        // one prefill and the head reads the last column (`Gemma4Handle::fold_pending`).
        let folded: Vec<u32> = prompt.iter().copied().chain([next]).collect();
        for run in 0..2 {
            let start = Instant::now();
            let mut p = match prefill_app(&mut net, gather.as_mut(), &reader, &rotary, &folded, true) {
                Ok(p) => p,
                Err(why) => return println!("the prefill failed: {why}"),
            };
            let prefill_ms = start.elapsed().as_secs_f64() * 1000.0;
            if stamps && run == 1 {
                prefill_report = Some(std::mem::take(&mut p.reports));
            }
            let tick = Instant::now();
            let id = match head_net.as_mut().map(|h| h.at(HeadOut::Greedy).and_then(|at| at.infer_raw_many(&[&p.state]))) {
                Some(Ok(out)) => out.last().filter(|id| id.len() == 2).map(|id| id[1] as u32 * 2048 + id[0] as u32),
                Some(Err(why)) => return println!("the head failed: {why}"),
                None => None,
            };
            let head_ms = tick.elapsed().as_secs_f64() * 1000.0;
            println!(
                "{} prefill {width}+1 tokens: rows {:.0} + plan wait {:.0} + combine {:.0} + rotary {:.0} + record {:.0} + gpu {:.0} + tail {:.0} = {prefill_ms:.0} ms; \
                 head {head_ms:.0} ms (id {id:?}); TTFT {:.0} ms",
                if run == 0 { "cold" } else { "warm" },
                p.rows, p.plan_wait, p.combine, p.rotary, p.record, p.gpu, p.tail,
                prefill_ms + head_ms,
            );
        }
        // The old flow once (prefill the prompt, then a decode step), for the token to match.
        let tick = Instant::now();
        let unfolded = prefill_app(&mut net, gather.as_mut(), &reader, &rotary, &prompt, true).and_then(|_| {
            let mut s = StepTimes::default();
            decode_one(&mut net, head_net.as_mut(), gather.as_mut(), &reader, &rotary, next, width, HeadOut::Greedy, &mut s)
        });
        println!("  decode-step flow: id {unfolded:?}, {:.0} ms", tick.elapsed().as_secs_f64() * 1000.0);
        let margins = (|| -> Result<String, String> {
            let head = head_net.as_mut().ok_or("no head")?;
            let p = prefill_app(&mut net, gather.as_mut(), &reader, &rotary, &folded, true)?;
            let a = softcapped(&head.at(HeadOut::Logits)?.infer_raw_many(&[&p.state])?)?;
            prefill_app(&mut net, gather.as_mut(), &reader, &rotary, &prompt, true)?;
            let b = last_logits(&mut net, head, &reader, &rotary, next, width)?;
            let top3 = |l: &[f32]| top10(l).into_iter().take(3).map(|i| (i, l[i as usize])).collect::<Vec<_>>();
            Ok(format!("folded {:?} / decode-step {:?}", top3(&a), top3(&b)))
        })();
        println!("  top3 {margins:?}");
    }
    for (title, report) in prefill_report.unwrap_or_default() {
        print_kinds(title, &report);
    }
}

/// Feed `tokens` at positions `start..` without the head: one batched `Prefill` submit, or one
/// single-position pass each (the decode layer, which attends the cache).
fn feed(
    net: &mut Reshaped<gemma4::Pass>,
    embed: &modelrunner::weights::Reader<'_>,
    rotary: &modelrunner::weights::Reader<'_>,
    tokens: &[u32],
    start: u32,
    batched: bool,
) -> Result<(), String> {
    if tokens.is_empty() {
        return Ok(());
    }
    let params = |position: u32| StepParams {
        prefix: position,
        window_start: position.saturating_sub(gemma4::WINDOW - 1),
    };
    if !batched {
        for (offset, &token) in tokens.iter().enumerate() {
            let position = start + offset as u32;
            let (hidden, per_layer) = gather_in(embed, token)?;
            let local = rotary_row(rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, position)?;
            let global = rotary_row(rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, position)?;
            let at = net.at(gemma4::Mode::Prefill { tokens: 1 }.at(TIER))?;
            at.set_params(params(position))?;
            at.infer_raw_many(&[&hidden, &per_layer, &local, &global])?;
        }
        return Ok(());
    }
    let width = tokens.len() as u32;
    let mut hidden = vec![0f32; (gemma4::D_MODEL * width) as usize];
    let mut per_layer = vec![0f32; (gemma4::PER_LAYER * gemma4::LAYERS as u32 * width) as usize];
    let mut local = vec![0f32; (gemma4::HEAD_DIM * width) as usize];
    let mut global = vec![0f32; (gemma4::GLOBAL_HEAD_DIM * width) as usize];
    for (column, &token) in tokens.iter().enumerate() {
        let column = column as u32;
        let (h, p) = gather_in(embed, token)?;
        place(&mut hidden, &h, column, width);
        place(&mut per_layer, &p, column, width);
        place(&mut local, &rotary_row(rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, start + column)?, column, width);
        place(
            &mut global,
            &rotary_row(rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, start + column)?,
            column,
            width,
        );
    }
    // As the bridge does: a chunk at position 0 attends itself, any later one the cache.
    let mode = if start == 0 {
        gemma4::Mode::PrefillFresh { tokens: width }
    } else {
        gemma4::Mode::Prefill { tokens: width }
    };
    let at = net.at(mode.at(TIER))?;
    at.set_params(params(start))?;
    at.infer_raw_many(&[&hidden, &per_layer, &local, &global])?;
    Ok(())
}

/// The last token as a decode step at `position`, then the logits head.
#[allow(clippy::too_many_arguments)]
fn last_logits(
    net: &mut Reshaped<gemma4::Pass>,
    head: &mut Reshaped<HeadOut>,
    embed: &modelrunner::weights::Reader<'_>,
    rotary: &modelrunner::weights::Reader<'_>,
    token: u32,
    position: u32,
) -> Result<Vec<f32>, String> {
    let (hidden, per_layer) = gather_in(embed, token)?;
    let local = rotary_row(rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, position)?;
    let global = rotary_row(rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, position)?;
    let at = net.at(gemma4::Mode::DecodeStep.at(TIER))?;
    at.set_params(StepParams { prefix: position, window_start: position.saturating_sub(gemma4::WINDOW - 1) })?;
    let out = at.infer_raw_many(&[&hidden, &per_layer, &local, &global])?;
    let state = out.into_iter().next().ok_or("the step returned nothing")?;
    softcapped(&head.at(HeadOut::Logits)?.infer_raw_many(&[&state])?)
}

fn top10(logits: &[f32]) -> Vec<u32> {
    let mut order: Vec<u32> = (0..logits.len() as u32).collect();
    order.sort_by(|&a, &b| logits[b as usize].total_cmp(&logits[a as usize]));
    order.truncate(10);
    order
}

/// A prompt split `[0..k at 0][k..n-1 at k]` must give the next-token ranking the whole prompt
/// gives at 0. Variants: whole batched (reference), all sequential, the app's stopgap (batched
/// head + sequential tail), and a batched tail (correct only once prefill attends the cache).
fn split_check(
    context: &Arc<context::Context>,
    weights: &Streamed,
    embed: &Streamed,
    head: Option<&Streamed>,
    prompt: &[u32],
    k: u32,
) -> Result<(), String> {
    let n = prompt.len();
    let k = k as usize;
    if n < 3 || k == 0 || k + 1 >= n || n as u32 >= TIER {
        return Err(format!("need 0 < k < n - 1 and n < {TIER}; got k {k}, n {n}"));
    }
    let head = head.ok_or("the split check needs --head")?;
    let mut net = streamed_net(context, weights, gemma4::Mode::DecodeStep.at(TIER), gemma4_plan)?;
    let mut head_net = streamed_net(context, head, HeadOut::Logits, head_plan)?;
    let reader = embed.reader();
    let rotary = weights.reader();
    let last = prompt[n - 1];
    let body = &prompt[..n - 1];
    let (front, tail) = body.split_at(k);
    let mut run = |label: &str, front_batched: bool, tail_batched: bool, whole: bool| -> Result<Vec<f32>, String> {
        let tick = std::time::Instant::now();
        let mut tail_ms = 0.0;
        if whole {
            feed(&mut net, &reader, &rotary, body, 0, front_batched)?;
        } else {
            feed(&mut net, &reader, &rotary, front, 0, front_batched)?;
            let tail_tick = std::time::Instant::now();
            feed(&mut net, &reader, &rotary, tail, k as u32, tail_batched)?;
            tail_ms = tail_tick.elapsed().as_secs_f64() * 1000.0;
        }
        let logits = last_logits(&mut net, &mut head_net, &reader, &rotary, last, (n - 1) as u32)?;
        let caches = net.at(gemma4::Mode::DecodeStep.at(TIER))?.export_pinned(gemma4::CACHE_TENSORS, n as u32)?;
        let hash = caches.iter().fold(0xcbf2_9ce4_8422_2325u64, |h, &b| (h ^ u64::from(b)).wrapping_mul(0x100_0000_01b3));
        println!(
            "  {label:<32} {:>7.0} ms (tail {tail_ms:.0} ms = {:.1} ms/token)  caches {hash:016x}  top10 {:?}",
            tick.elapsed().as_secs_f64() * 1000.0,
            tail_ms / tail.len().max(1) as f64,
            top10(&logits)
        );
        Ok(logits)
    };
    println!("split check: {n} tokens, split at {k}");
    // Widest batch first so the arena grows before anything it holds matters.
    let reference = run("whole batched at 0", true, true, true)?;
    let variants = [
        ("all sequential", run("all sequential", false, false, true)?),
        ("batched head + sequential tail", run("batched head + sequential tail", true, false, false)?),
        ("batched head + batched tail", run("batched head + batched tail", true, true, false)?),
    ];
    let want = top10(&reference);
    let mut gather = if fused() { None } else { Some(gather_plan(context, embed)?) };
    prefill_app(&mut net, gather.as_mut(), &reader, &rotary, body, false)?;
    let exact = last_logits(&mut net, &mut head_net, &reader, &rotary, last, (n - 1) as u32)?;
    println!("  {:<32} top10 {:?}", "app path, unpadded", top10(&exact));
    prefill_app(&mut net, gather.as_mut(), &reader, &rotary, body, true)?;
    let app = last_logits(&mut net, &mut head_net, &reader, &rotary, last, (n - 1) as u32)?;
    println!("  {:<32} top10 {:?}", "app path (rows + device combine)", top10(&app));
    let pad_diff = app.iter().zip(&exact).map(|(a, b)| (a - b).abs()).fold(0f32, f32::max);
    println!("  padding {} -> {}: max |logit diff| vs unpadded {pad_diff:.4}", body.len(), body.len().div_ceil(8) * 8);
    let folded = prefill_app(&mut net, gather.as_mut(), &reader, &rotary, prompt, true)?;
    let folded = softcapped(&head_net.at(HeadOut::Logits)?.infer_raw_many(&[&folded.state])?)?;
    println!("  {:<32} top10 {:?}", "app path, last token folded", top10(&folded));
    let variants = [
        variants[0].clone(),
        variants[1].clone(),
        variants[2].clone(),
        ("app path (rows + device combine)", app),
        ("app path, last token folded", folded),
    ];
    for (label, logits) in &variants {
        let got = top10(logits);
        let max_diff = logits.iter().zip(&reference).map(|(a, b)| (a - b).abs()).fold(0f32, f32::max);
        let verdict = if got == want {
            "SAME top10"
        } else if got[0] == want[0] {
            "same argmax, top10 reordered"
        } else {
            "ARGMAX DIFFERS"
        };
        println!("  {label:<32} {verdict}, max |logit diff| {max_diff:.4}");
    }
    Ok(())
}

/// The device combine (`gemma4_gather`, uploaded through `Reshaped::windowed` so only the
/// tensors it reads leave the 3.6 GB file) against the host `combine_cached`, per token.
fn gather_check(context: &Arc<context::Context>, embed: &Streamed, tokens: &[u32]) -> Result<(), String> {
    use std::time::Instant;
    let tick = Instant::now();
    let mut net = gather_plan(context, embed)?;
    println!("gather net built in {:.0} ms", tick.elapsed().as_secs_f64() * 1000.0);
    let reader = embed.reader();
    let (mut host_ms, mut device_ms, mut worst) = (0.0, 0.0, 0f32);
    let mut device_cols = Vec::new();
    let repeats = 4;
    for pass in 0..repeats {
        for &token in tokens {
            let hidden = reader.int4_row(
                gemma4::embed::TOKENS,
                gemma4::embed::TOKENS + 1,
                &[gemma4::VOCAB, gemma4::D_MODEL],
                token,
            )?;
            let row = if gemma4::embed::PLACEHOLDERS.contains(&token) { 0 } else { token };
            let mut embedded = Vec::with_capacity((gemma4::PER_LAYER as usize) * gemma4::LAYERS);
            for i in 0..gemma4::LAYERS {
                let at = gemma4::embed::table(i);
                embedded.extend(reader.int4_row(at, at + 1, &[gemma4::VOCAB, gemma4::PER_LAYER], row)?);
            }
            let tick = Instant::now();
            let host = gemma4::combine_cached(&reader, &hidden, &embedded)?;
            let host_t = tick.elapsed().as_secs_f64() * 1000.0;
            let tick = Instant::now();
            let out = net.at(1)?.infer_raw_many(&[&hidden, &embedded])?;
            let device_t = tick.elapsed().as_secs_f64() * 1000.0;
            let device = out.into_iter().next().ok_or("the gather net returned nothing")?;
            if pass == 0 {
                device_cols.push(device.clone());
            }
            if device.len() != host.len() {
                return Err(format!("device {} values, host {}", device.len(), host.len()));
            }
            let scale = host.iter().fold(0f32, |m, v| m.max(v.abs())).max(1e-6);
            let diff = device.iter().zip(&host).map(|(a, b)| (a - b).abs()).fold(0f32, f32::max) / scale;
            worst = worst.max(diff);
            if pass > 0 {
                host_ms += host_t;
                device_ms += device_t;
            }
        }
    }
    let n = (tokens.len() * (repeats - 1)) as f64;
    println!(
        "combine per token: host {:.2} ms, device {:.2} ms; worst max|diff|/max|host| {worst:.5}",
        host_ms / n,
        device_ms / n
    );
    // The prefill's batched combine: every column at once, against the per-token device combine.
    use modelrunner::nets::gemma4_gather as g;
    let width = tokens.len();
    let tick = Instant::now();
    let hidden = g::token_rows(&reader, tokens)?;
    let embedded = g::layer_rows(&reader, tokens)?;
    let rows_ms = tick.elapsed().as_secs_f64() * 1000.0;
    let mut wide = Vec::new();
    let mut wide_ms = 0.0;
    for _ in 0..3 {
        let tick = Instant::now();
        wide = net.at(width as u32)?.infer_raw_many(&[&hidden, &embedded])?.into_iter().next().ok_or("no wide combine")?;
        wide_ms = tick.elapsed().as_secs_f64() * 1000.0;
    }
    let (mut identical, mut worst) = (0, 0f32);
    for (at, single) in device_cols.iter().enumerate() {
        let col = g::column(&wide, at, width);
        identical += usize::from(col == *single);
        let scale = single.iter().fold(0f32, |m, v| m.max(v.abs())).max(1e-6);
        worst = worst.max(col.iter().zip(single).map(|(a, b)| (a - b).abs()).fold(0f32, f32::max) / scale);
    }
    println!(
        "wide combine over {width} columns: rows {rows_ms:.1} ms, combine {wide_ms:.1} ms; \
         {identical}/{width} columns bit-identical to per-token, worst {worst:.5}"
    );
    Ok(())
}

/// Soft tokens after text: `prompt[..2]` batched at 0, then `prompt[2..n-1]` as soft rows
/// (embedding row + pad-token per-layer inputs, as `pushSoftGemma4` builds them) batched at 2
/// versus one pass each, then the last token. The two next-token rankings must agree.
fn soft_check(
    context: &Arc<context::Context>,
    weights: &Streamed,
    embed: &Streamed,
    head: Option<&Streamed>,
    prompt: &[u32],
) -> Result<(), String> {
    let n = prompt.len();
    if n < 4 {
        return Err("the soft check needs at least 4 tokens".into());
    }
    let head = head.ok_or("the soft check needs --head")?;
    let mut net = streamed_net(context, weights, gemma4::Mode::DecodeStep.at(TIER), gemma4_plan)?;
    let mut head_net = streamed_net(context, head, HeadOut::Logits, head_plan)?;
    let reader = embed.reader();
    let rotary = weights.reader();
    let mut rows = Vec::new();
    for &token in &prompt[2..n - 1] {
        let hidden = reader.int4_row(
            gemma4::embed::TOKENS,
            gemma4::embed::TOKENS + 1,
            &[gemma4::VOCAB, gemma4::D_MODEL],
            token,
        )?;
        let per_layer = gemma4::gather_soft_input(&reader, &hidden, 0, fused())?;
        rows.push((hidden, per_layer));
    }
    let mut run = |batched: bool| -> Result<Vec<f32>, String> {
        feed(&mut net, &reader, &rotary, &prompt[..2], 0, true)?;
        if batched {
            let width = rows.len() as u32;
            let mut hidden = vec![0f32; (gemma4::D_MODEL * width) as usize];
            let mut per_layer = vec![0f32; (gemma4::PER_LAYER * gemma4::LAYERS as u32 * width) as usize];
            let mut local = vec![0f32; (gemma4::HEAD_DIM * width) as usize];
            let mut global = vec![0f32; (gemma4::GLOBAL_HEAD_DIM * width) as usize];
            for (column, (h, p)) in rows.iter().enumerate() {
                let column = column as u32;
                place(&mut hidden, h, column, width);
                place(&mut per_layer, p, column, width);
                place(&mut local, &rotary_row(&rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, 2 + column)?, column, width);
                place(&mut global, &rotary_row(&rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, 2 + column)?, column, width);
            }
            let at = net.at(gemma4::Mode::Prefill { tokens: width }.at(TIER))?;
            at.set_params(StepParams { prefix: 2, window_start: 0 })?;
            at.infer_raw_many(&[&hidden, &per_layer, &local, &global])?;
        } else {
            for (offset, (h, p)) in rows.iter().enumerate() {
                let position = 2 + offset as u32;
                let local = rotary_row(&rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, position)?;
                let global = rotary_row(&rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, position)?;
                let at = net.at(gemma4::Mode::Prefill { tokens: 1 }.at(TIER))?;
                at.set_params(StepParams { prefix: position, window_start: position.saturating_sub(gemma4::WINDOW - 1) })?;
                at.infer_raw_many(&[h, p, &local, &global])?;
            }
        }
        last_logits(&mut net, &mut head_net, &reader, &rotary, prompt[n - 1], (n - 1) as u32)
    };
    let batched = run(true)?;
    let sequential = run(false)?;
    let max_diff = batched.iter().zip(&sequential).map(|(a, b)| (a - b).abs()).fold(0f32, f32::max);
    println!("soft rows batched    top10 {:?}", top10(&batched));
    println!("soft rows sequential top10 {:?}", top10(&sequential));
    println!(
        "soft check: {}, max |logit diff| {max_diff:.4}",
        if top10(&batched) == top10(&sequential) { "SAME top10" } else if top10(&batched)[0] == top10(&sequential)[0] { "same argmax, top10 reordered" } else { "ARGMAX DIFFERS" }
    );
    Ok(())
}

/// Per-kind GPU table for one submit, plus its ten hottest dispatches.
fn print_kinds(title: &str, report: &[(String, f64, f64)]) {
    let mut per_kind: Vec<(String, usize, f64)> = Vec::new();
    let (mut total, mut gaps) = (0.0, 0.0);
    for (label, us, gap) in report {
        total += us;
        gaps += gap;
        let kind = label.split(':').nth(1).unwrap_or("?").trim().to_string();
        match per_kind.iter_mut().find(|(k, _, _)| *k == kind) {
            Some((_, n, t)) => {
                *n += 1;
                *t += us;
            }
            None => per_kind.push((kind, 1, *us)),
        }
    }
    per_kind.sort_by(|a, b| b.2.total_cmp(&a.2));
    println!();
    println!("{title}: {} dispatches, {:.1} ms timestamped, {:.1} ms gaps", report.len(), total / 1000.0, gaps / 1000.0);
    println!("{:>6} {:>10} {:>10}  kind", "count", "total-ms", "mean-us");
    for (kind, n, us) in &per_kind {
        println!("{n:>6} {:>10.1} {:>10.1}  {kind}", us / 1000.0, us / *n as f64);
    }
    let mut hottest: Vec<&(String, f64, f64)> = report.iter().collect();
    hottest.sort_by(|a, b| b.1.total_cmp(&a.1));
    for (label, us, _) in hottest.into_iter().take(10) {
        println!("  {us:>9.1} us  {label}");
    }
}

/// Per-(kind, in_c, out_c) GPU time for the weight-streaming kinds, with the weight bandwidth.
fn print_shapes(report: &[(String, f64, f64)], plan: &modelrunner::nets::Plan) {
    use modelrunner::nets::{Kind, Op};
    let mut rows: Vec<(String, u32, u32, usize, f64, f64)> = Vec::new();
    let dispatches = plan.ops.iter().filter_map(|op| match op { Op::Dispatch { kind, push, .. } => Some((*kind, *push)), _ => None });
    for ((kind, push), (_, us, _)) in dispatches.zip(report) {
        let bits = match kind { Kind::ConvVecInt4 => 4.0, Kind::ConvVecInt8 => 8.0, Kind::RmsNorm => 16.0, _ => continue };
        let (in_c, out_c) = if kind == Kind::RmsNorm { (push.in_c, push.group.max(1)) } else { (push.in_c, push.out_c) };
        let bytes = if kind == Kind::RmsNorm { f64::from(in_c) * 2.0 } else { f64::from(in_c) * f64::from(out_c) * bits / 8.0 };
        let name = format!("{kind:?}");
        let push = modelrunner::nets::Push { in_c, out_c, ..push };
        match rows.iter_mut().find(|r| r.0 == name && r.1 == push.in_c && r.2 == push.out_c) {
            Some(r) => { r.3 += 1; r.4 += us; r.5 += bytes; }
            None => rows.push((name, push.in_c, push.out_c, 1, *us, bytes)),
        }
    }
    rows.sort_by(|a, b| b.4.total_cmp(&a.4));
    println!("{:>6} {:>10} {:>10} {:>8}  kind in_c -> out_c", "count", "total-ms", "mean-us", "GB/s");
    for (kind, in_c, out_c, n, us, bytes) in rows {
        println!("{n:>6} {:>10.2} {:>10.1} {:>8.1}  {kind} {in_c} -> {out_c}", us / 1000.0, us / n as f64, bytes / us / 1000.0);
    }
}

/// Host RSS high-water mark and current RSS (Linux/Android only).
fn print_memory() {
    let Ok(status) = std::fs::read_to_string("/proc/self/status") else { return };
    for line in status.lines().filter(|l| l.starts_with("VmHWM") || l.starts_with("VmRSS")) {
        println!("{line}");
    }
}

/// Feed every token in order and return the last step's logits.
///
/// The device returns the normed hidden state; the logits are
/// `hidden @ H^T` over the raw-scale head table (see
/// `gemma4::embed::HEAD_TABLE`), softcapped, computed on the host exactly as
/// the bridge does it. So a parity pass here covers the gather, the whole
/// transformer, AND the tied-head path the app actually uses.
fn run(
    context: &Arc<context::Context>,
    weights: &Streamed,
    embed: &Streamed,
    head: Option<&Streamed>,
    tokens: &[u32],
) -> Result<Vec<f32>, String> {
    let mut net = streamed_net(context, weights, gemma4::Mode::DecodeStep.at(TIER), gemma4_plan)?;
    let reader = embed.reader();
    let rotary = weights.reader();
    let mut hidden = Vec::new();
    for (step, &token) in tokens.iter().enumerate() {
        let position = u32::try_from(step).map_err(|_| "a step past u32")?;
        if position >= gemma4::MAX_CONTEXT {
            return Err(format!("position {position} is past MAX_CONTEXT"));
        }
        let (hidden_in, per_layer) = gather_in(&reader, token)?;
        let angles_local = rotary_row(&rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, position)?;
        let angles_global =
            rotary_row(&rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, position)?;

        let at = net.at(gemma4::Mode::DecodeStep.at(TIER))?;
        // The sliding layers attend `[position - WINDOW + 1, position]`; the full ones want the
        // whole prefix. One `window_start` serves both only while the prefix is inside the
        // window, which it is for a golden this short - a longer one needs the per-layer window
        // this runtime does not carry yet.
        at.set_params(StepParams {
            prefix: position,
            window_start: position.saturating_sub(gemma4::WINDOW - 1),
        })?;
        let out = at.infer_raw_many(&[&hidden_in, &per_layer, &angles_local, &angles_global])?;
        if out.len() != 1 {
            return Err(format!("a step returned {} tensors, not the hidden state", out.len()));
        }
        hidden = out.into_iter().next().unwrap_or_default();
    }
    if hidden.len() != gemma4::D_MODEL as usize {
        return Err(format!("hidden state of {} values, not {}", hidden.len(), gemma4::D_MODEL));
    }
    gpu_logits(context, head, &hidden)
}

/// Logits for `hidden` from the standalone head file (~402 MB upload).
///
/// There is no fallback: the EMBED-resident chunks would mean a net over the
/// 3.6 GB EMBED file, and the host tied head holds a ~400 MB fp32 quarter.
fn gpu_logits(
    context: &Arc<context::Context>,
    head: Option<&Streamed>,
    hidden: &[f32],
) -> Result<Vec<f32>, String> {
    let head = head.ok_or("no head file: pass --head gemma4_head.maml")?;
    let mut net = streamed_net(context, head, HeadOut::Logits, head_plan)?;
    softcapped(&net.at(HeadOut::Logits)?.infer_raw_many(&[hidden])?)
}

/// The 16 head splits concatenated and softcapped, as the bridge does it.
fn softcapped(out: &[Vec<f32>]) -> Result<Vec<f32>, String> {
    use modelrunner::nets::gemma4_head;
    let splits = out.get(..gemma4_head::HEAD_CHUNKS).ok_or("the head returned too few splits")?;
    let mut logits = Vec::with_capacity(gemma4::VOCAB as usize);
    for split in splits {
        if split.len() != gemma4_head::CLASSES_PER_CHUNK as usize {
            return Err(format!("a head split of {} values", split.len()));
        }
        logits.extend(split.iter().map(|&v| gemma4::LOGIT_CAP * (v / gemma4::LOGIT_CAP).tanh()));
    }
    Ok(logits)
}

/// One position's angles from a rotary table, which is plain fp16.
///
/// A single-row `fp16_rows` read (512 B local, 1 KB global) rather than the
/// whole `[MAX_CONTEXT, width]` table: the old `fp16` call held the full
/// 8/16 MB blob as ~16/33 MB of fp32 transient per step, twice a step.
fn rotary_row(
    reader: &modelrunner::weights::Reader<'_>,
    index: usize,
    width: u32,
    position: u32,
) -> Result<Vec<f32>, String> {
    if position >= gemma4::MAX_CONTEXT {
        return Err(format!("position {position} is past the rotary table"));
    }
    reader.fp16_rows(index, &[gemma4::MAX_CONTEXT, width], position, 1)
}

/// Print the comparison, and say plainly whether it passed.
fn report(got: &[f32], want_ids: &[u32], want_logits: &[f32]) {
    let mut order: Vec<u32> = (0..got.len() as u32).collect();
    order.sort_by(|&a, &b| got[b as usize].total_cmp(&got[a as usize]));
    let top: Vec<u32> = order.iter().copied().take(10).collect();

    println!();
    println!("  reference top10 {want_ids:?}");
    println!("  device    top10 {top:?}");
    println!();
    println!("  {:>8}  {:>12}  {:>12}", "id", "reference", "device");
    for (rank, &id) in want_ids.iter().enumerate().take(10) {
        println!(
            "  {id:>8}  {:>12.4}  {:>12.4}",
            want_logits.get(rank).copied().unwrap_or(f32::NAN),
            got.get(id as usize).copied().unwrap_or(f32::NAN),
        );
    }
    println!();
    let argmax_ok = top.first() == want_ids.first();
    let overlap = top.iter().filter(|id| want_ids.contains(id)).count();
    println!("  argmax {}", if argmax_ok { "MATCHES" } else { "DIFFERS" });
    println!("  {overlap} of the reference's top 10 are in the device's top 10");
    if argmax_ok && overlap >= 8 {
        println!();
        println!("PASS: the forward pass agrees with onnxruntime within quantisation noise");
    } else {
        println!();
        println!("FAIL: this is a composition error, not quantisation - the ranking moved");
    }
}

/// `{"tokens": [...], "top_ids": [...], "top_logits": [...]}`.
fn parse_golden(text: &str) -> (Vec<u32>, Vec<u32>, Vec<f32>) {
    let numbers = |key: &str| -> String {
        let Some(at) = text.find(key) else { return String::new() };
        let rest = &text[at + key.len()..];
        let Some(open) = rest.find('[') else { return String::new() };
        let Some(close) = rest[open..].find(']') else { return String::new() };
        rest[open + 1..open + close].to_string()
    };
    let ints = |s: String| -> Vec<u32> {
        s.split(',').filter_map(|p| p.trim().parse().ok()).collect()
    };
    let floats = |s: String| -> Vec<f32> {
        s.split(',').filter_map(|p| p.trim().parse().ok()).collect()
    };
    (
        ints(numbers("\"tokens\"")),
        ints(numbers("\"top_ids\"")),
        floats(numbers("\"top_logits\"")),
    )
}
