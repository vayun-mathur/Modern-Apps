//! Per-kind GPU time and per-pass wall time for one model, on whatever device runs it.
//!
//! ```text
//! cargo run --release -p modelrunner --example profile_net -- nllb [path/to/nllb600.maml]
//! adb shell MODELRUNNER_TIMESTAMPS=1 /data/local/tmp/maml/profile_net nllb /data/local/tmp/maml/nllb/nllb600.maml
//! ```
//!
//! Timestamps need `MODELRUNNER_TIMESTAMPS=1`; without it only wall times print. The wall times
//! are with timestamps off unless it is set, so measure the two in separate runs.
use std::collections::BTreeMap;
use std::time::Instant;

use modelrunner::nets::{madlad, madlad_part1, nllb, whisper, Op, Plan};
use modelrunner::vulkan::context;
use modelrunner::vulkan::reshape::Reshaped;
use modelrunner::vulkan::run::{Net, StepParams};
use modelrunner::weights::{graph, Streamed, Weights};

/// Source tokens. A longish sentence, so cross-attention costs show.
const SRC_LEN: u32 = 32;

/// Decode steps timed after one warm-up.
const STEPS: u32 = 24;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let model = args.first().map(String::as_str).unwrap_or("nllb");
    let result = match model {
        "nllb" => nllb_profile(args.get(1).map(String::as_str)),
        "whisper" => whisper_profile(args.get(1).map(String::as_str)),
        "madlad" => madlad_profile(args.get(1).map(String::as_str)),
        other => Err(format!("unknown model {other:?}; known: nllb, whisper, madlad")),
    };
    if let Err(why) = result {
        println!("failed: {why}");
    }
}

fn nllb_profile(path: Option<&str>) -> Result<(), String> {
    let path = path
        .map(str::to_owned)
        .or_else(|| std::env::var("MODELRUNNER_NLLB").ok())
        .ok_or("pass the nllb600.maml path or set MODELRUNNER_NLLB")?;
    let bytes = std::fs::read(&path).map_err(|e| format!("cannot read {path}: {e}"))?;
    let weights = Weights::parse(&bytes, graph::NLLB)?;
    let context = context::shared()?;

    let started = Instant::now();
    // Decode-first construction when MODELRUNNER_DECODE_FIRST=1: the cold record (one-time
    // driver cost, ~130-166 ms for the 280-op decode plan) and the max-size arena allocation
    // happen in load, and later switches only re-record (~10-15 ms) with no arena growth.
    // Any other number is the src_len to record the decode plan at, to check the cold cost is per
    // kernel rather than per shape.
    let first = match std::env::var("MODELRUNNER_DECODE_FIRST").ok().and_then(|v| v.parse().ok()) {
        Some(1) => nllb::Mode::DecodeStep { src_len: SRC_LEN },
        Some(src_len) => nllb::Mode::DecodeStep { src_len },
        None => nllb::Mode::Encode { len: SRC_LEN },
    };
    let mut net = Reshaped::new(
        context,
        &weights,
        first,
        |offsets, mode| nllb::build(offsets, mode),
    )?;
    println!("load      {:>8.1} ms (upload + first record)", ms(started));

    // A plausible source

    // A plausible source: the source-language token, then ordinary pieces, then `</s>`.
    let mut source: Vec<u32> = vec![256_047];
    source.extend((0..SRC_LEN - 2).map(|i| 1_000 + i * 37));
    source.push(2);
    let embedded = nllb::embed_positions(weights.reader(), &source, 0)?;

    let mut encode_ms = Vec::new();
    let mut switch_ms = Vec::new();
    let mut step_ms = Vec::new();
    // Three sentences, so the per-call re-records show up as well as steady-state steps.
    for _ in 0..3 {
        let t = Instant::now();
        net.at(nllb::Mode::Encode { len: SRC_LEN })?.infer_raw(&embedded)?;
        encode_ms.push(ms(t));

        let mut token = 2u32;
        for step in 0..=STEPS {
            let row = nllb::embed_positions(weights.reader(), &[token], step)?;
            let t = Instant::now();
            let at = net.at(nllb::Mode::DecodeStep { src_len: SRC_LEN })?;
            if step == 0 {
                switch_ms.push(ms(t));
            }
            let t = Instant::now();
            at.set_params(StepParams { prefix: step, window_start: 0 })?;
            let out = at.infer_raw(&row)?;
            if step > 0 {
                step_ms.push(ms(t));
            }
            token = greedy(&out).unwrap_or(2).min(nllb::VOCAB - 1);
        }
    }
    println!("encode    {} (includes the re-record into Encode after the first)", stats(&encode_ms));
    println!("switch    {} (re-record into DecodeStep)", stats(&switch_ms));
    println!("step      {}", stats(&step_ms));

    let decode = net.at(nllb::Mode::DecodeStep { src_len: SRC_LEN })?;
    print_kinds("decode step", decode);
    let encode = net.at(nllb::Mode::Encode { len: SRC_LEN })?;
    encode.infer_raw(&embedded)?;
    print_kinds("encode", encode);
    Ok(())
}

/// Whisper over a synthetic window: the real prompt, then a fixed number of greedy steps.
///
/// Prints the ids and a logits checksum so two builds can be compared for identical output, which
/// is the regression check here - there is no recorded reference transcript to match.
fn whisper_profile(path: Option<&str>) -> Result<(), String> {
    const STEPS: u32 = 32;
    // `<|startoftranscript|> <|en|> <|transcribe|> <|notimestamps|>`.
    const PROMPT: [u32; 4] = [50_258, 50_259, 50_359, 50_363];
    let path = path
        .map(str::to_owned)
        .or_else(|| std::env::var("MODELRUNNER_WHISPER").ok())
        .ok_or("pass the whisper_base.maml path or set MODELRUNNER_WHISPER")?;
    let bytes = std::fs::read(&path).map_err(|e| format!("cannot read {path}: {e}"))?;
    let weights = Weights::parse(&bytes, graph::WHISPER)?;
    let context = context::shared()?;

    let started = Instant::now();
    // MODELRUNNER_DECODE_FIRST=1 mirrors the app's create: decode step recorded, then the encoder.
    let decode_first = std::env::var("MODELRUNNER_DECODE_FIRST").is_ok();
    let first = if decode_first { whisper::Mode::DecodeStep } else { whisper::Mode::Encode };
    let mut net =
        Reshaped::new(context, &weights, first, |offsets, mode| whisper::build(offsets, mode))?;
    net.at(whisper::Mode::Encode)?;
    println!("load      {:>8.1} ms (upload + first record)", ms(started));

    // A deterministic log-mel-like window: smooth in time, varied across bins.
    let mel: Vec<f32> = (0..whisper::MELS * whisper::MEL_FRAMES)
        .map(|i| {
            let (bin, frame) = (i / whisper::MEL_FRAMES, i % whisper::MEL_FRAMES);
            ((frame as f32 * 0.013 + bin as f32 * 0.37).sin() * 0.6 - 0.2).clamp(-1.0, 1.0)
        })
        .collect();

    let mut encode_ms = Vec::new();
    let mut step_ms = Vec::new();
    let mut switch_ms = Vec::new();
    let mut ids = Vec::new();
    let mut checksum = 0.0f64;
    for round in 0..2 {
        let t = Instant::now();
        let encoder = net.at(whisper::Mode::Encode)?;
        let encoded = encoder.infer_raw_many(&[&mel])?;
        encode_ms.push(ms(t));
        // HEAD's encoder hands its twelve cross caches back; the decode step there takes them in.
        let cross: Vec<Vec<f32>> = encoded.into_iter().skip(1).collect();

        let mut token = PROMPT[0];
        for step in 0..PROMPT.len() as u32 + STEPS {
            let row = whisper::embed_positions(weights.reader(), &[token], step)?;
            let t = Instant::now();
            let at = net.at(whisper::Mode::DecodeStep)?;
            if step == 0 {
                switch_ms.push(ms(t));
            }
            at.set_params(StepParams { prefix: step, window_start: 0 })?;
            let mut inputs: Vec<&[f32]> = vec![&row];
            inputs.extend(cross.iter().map(Vec::as_slice));
            let t = Instant::now();
            let out = at.infer_raw_many(&inputs)?;
            step_ms.push(ms(t));
            let logits = out.first().ok_or("no logits")?;
            token = if let Some(&next) = PROMPT.get(step as usize + 1) {
                next
            } else {
                let next = greedy(&out).unwrap_or(50_257);
                if round == 0 {
                    ids.push(next);
                    checksum += logits.iter().map(|&v| f64::from(v)).sum::<f64>();
                }
                next
            };
        }
    }
    println!("encode    {}", stats(&encode_ms));
    println!("switch    {} (into DecodeStep)", stats(&switch_ms));
    println!("step      {}", stats(&step_ms));
    println!("ids       {ids:?}");
    println!("checksum  {checksum:.3}");

    let decode = net.at(whisper::Mode::DecodeStep)?;
    print_kinds("decode step", decode);
    let encode = net.at(whisper::Mode::Encode)?;
    encode.infer_raw_many(&[&mel])?;
    print_kinds("encode", encode);
    Ok(())
}

/// Whether a MADLAD decode step takes the encoder output as an input. True at HEAD; false once the
/// encode pass leaves the cross-attention K and V on the device.
const MADLAD_STEP_TAKES_ENCODED: bool = false;

/// MADLAD over a fixed 32-token source: three sentences of 24 greedy steps, ids and checksum
/// printed so two builds can be compared. Streamed, because the file is a gigabyte.
fn madlad_profile(path: Option<&str>) -> Result<(), String> {
    const SRC_LEN: u32 = 32;
    const STEPS: u32 = 24;
    let path = path
        .map(str::to_owned)
        .or_else(|| std::env::var("MODELRUNNER_MADLAD").ok())
        .ok_or("pass the madlad400.maml path or set MODELRUNNER_MADLAD")?;
    let file = std::fs::File::open(&path).map_err(|e| format!("cannot open {path}: {e}"))?;
    let len = file.metadata().map_err(|e| format!("cannot size {path}: {e}"))?.len();
    let weights = Streamed::open(file, 0, len, graph::MADLAD)?;
    let context = context::shared()?;

    let started = Instant::now();
    let mut net = Reshaped::streamed(
        context,
        weights.offsets(),
        &weights,
        madlad::Mode::Encode { len: SRC_LEN },
        |offsets, mode| madlad::build(offsets, mode),
    )?;
    println!("load      {:>8.1} ms (upload + first record)", ms(started));

    // A target tag then ordinary pieces then `</s>`, ids well inside the vocabulary.
    let mut source: Vec<u32> = vec![256_000 - 1_000];
    source.extend((0..SRC_LEN - 2).map(|i| 500 + i * 41));
    source.push(1);
    let embedded = madlad_part1::embed_positions(weights.reader(), &source, 0)?;
    let queries: Vec<u32> = (0..SRC_LEN).collect();
    let enc_bias =
        madlad_part1::relative_bias(weights.reader(), madlad::TABLE_ENC, &queries, &queries, true)?;

    let mut encode_ms = Vec::new();
    let mut step_ms = Vec::new();
    let mut ids = Vec::new();
    for round in 0..3 {
        let t = Instant::now();
        let out = net.at(madlad::Mode::Encode { len: SRC_LEN })?.infer_raw_many(&[&embedded, &enc_bias])?;
        encode_ms.push(ms(t));
        let encoded = out.into_iter().next().ok_or("the encoder returned nothing")?;
        if round == 0 {
            let sum: f64 = encoded.iter().map(|&v| f64::from(v)).sum();
            println!("encoded   checksum {sum:.6}");
        }

        let mut token = 0u32;
        for step in 0..STEPS {
            let row = madlad_part1::embed_positions(weights.reader(), &[token], step)?;
            let keys: Vec<u32> = (0..=step).collect();
            let live = madlad_part1::relative_bias(weights.reader(), madlad::TABLE_DEC, &[step], &keys, false)?;
            let width = madlad::MAX_DECODE_POSITIONS as usize;
            let mut bias = vec![0.0f32; madlad::HEADS as usize * width];
            for head in 0..madlad::HEADS as usize {
                bias[head * width..head * width + keys.len()]
                    .copy_from_slice(&live[head * keys.len()..(head + 1) * keys.len()]);
            }
            let at = net.at(madlad::Mode::DecodeStep { src_len: SRC_LEN })?;
            at.set_params(StepParams { prefix: step, window_start: 0 })?;
            let mut inputs: Vec<&[f32]> = vec![&row];
            if MADLAD_STEP_TAKES_ENCODED {
                inputs.push(&encoded);
            }
            inputs.push(&bias);
            let t = Instant::now();
            let out = at.infer_raw_many(&inputs)?;
            if step > 0 {
                step_ms.push(ms(t));
            }
            token = greedy(&out).unwrap_or(1).min(madlad::VOCAB - 1);
            if round == 0 {
                ids.push(token);
            }
        }
    }
    println!("encode    {}", stats(&encode_ms));
    println!("step      {}", stats(&step_ms));
    println!("ids       {ids:?}");

    let (planner, offsets) = net.planner();
    let decode = net.at(madlad::Mode::DecodeStep { src_len: SRC_LEN })?;
    print_kinds("decode step", decode);
    print_shapes("decode step", decode, &planner(&offsets, madlad::Mode::DecodeStep { src_len: SRC_LEN })?);
    let encode = net.at(madlad::Mode::Encode { len: SRC_LEN })?;
    encode.infer_raw_many(&[&embedded, &enc_bias])?;
    print_kinds("encode", encode);
    print_shapes("encode", encode, &planner(&offsets, madlad::Mode::Encode { len: SRC_LEN })?);
    Ok(())
}

/// The token from a greedy plan's `[lo, hi]` output, or the argmax of a logits plan's splits.
fn greedy(out: &[Vec<f32>]) -> Option<u32> {
    if let [pair] = out {
        if let [lo, hi] = pair[..] {
            return Some(hi as u32 * 2048 + lo as u32);
        }
    }
    let mut best: Option<(u32, f32)> = None;
    let mut index = 0u32;
    for split in out {
        for &value in split {
            if best.is_none_or(|(_, top)| value > top) {
                best = Some((index, value));
            }
            index += 1;
        }
    }
    best.map(|(id, _)| id)
}

fn print_kinds(label: &str, net: &Net) {
    let times = match net.op_times() {
        Ok(times) => times,
        Err(why) => {
            println!("{label}: {why}");
            return;
        }
    };
    let mut kinds: BTreeMap<String, (usize, f64)> = BTreeMap::new();
    let (mut total, mut gaps) = (0.0, 0.0);
    for (name, us, gap) in &times {
        let kind = name.split(": ").nth(1).unwrap_or(name).to_string();
        let entry = kinds.entry(kind).or_default();
        entry.0 += 1;
        entry.1 += us;
        total += us;
        gaps += gap;
    }
    let mut rows: Vec<_> = kinds.into_iter().collect();
    rows.sort_by(|a, b| b.1 .1.total_cmp(&a.1 .1));
    println!();
    println!("{label}: {} dispatches, {:.2} ms in shaders, {:.2} ms in gaps", times.len(), total / 1e3, gaps / 1e3);
    println!("  {:<22} {:>6} {:>10} {:>10}", "kind", "count", "total ms", "mean us");
    for (kind, (count, us)) in rows {
        println!("  {kind:<22} {count:>6} {:>10.3} {:>10.1}", us / 1e3, us / count as f64);
    }
}

/// GPU time per (kind, in_c, out_c, positions) when `MODELRUNNER_SHAPES` is set.
fn print_shapes(label: &str, net: &Net, plan: &Plan) {
    if std::env::var("MODELRUNNER_SHAPES").is_err() {
        return;
    }
    let Ok(times) = net.op_times() else { return };
    let pushes: Vec<_> = plan
        .ops
        .iter()
        .filter_map(|op| match op {
            Op::Dispatch { kind, push, .. } => Some((*kind, *push)),
            _ => None,
        })
        .collect();
    let mut shapes: BTreeMap<String, (usize, f64)> = BTreeMap::new();
    for ((_, us, _), (kind, push)) in times.iter().zip(&pushes) {
        let key = format!("{kind:?} {}->{} x{} in1={}", push.in_c, push.out_c, push.out_h * push.out_w, push.in1);
        let entry = shapes.entry(key).or_default();
        entry.0 += 1;
        entry.1 += us;
    }
    let mut rows: Vec<_> = shapes.into_iter().collect();
    rows.sort_by(|a, b| b.1 .1.total_cmp(&a.1 .1));
    println!("{label} by shape:");
    for (key, (count, us)) in rows.into_iter().take(16) {
        println!("  {key:<44} {count:>5} {:>10.3} ms {:>9.1} us", us / 1e3, us / count as f64);
    }
}

fn ms(since: Instant) -> f64 {
    since.elapsed().as_secs_f64() * 1e3
}

fn stats(values: &[f64]) -> String {
    if values.is_empty() {
        return "n/a".into();
    }
    let mut sorted = values.to_vec();
    sorted.sort_by(f64::total_cmp);
    let mean = sorted.iter().sum::<f64>() / sorted.len() as f64;
    format!(
        "mean {mean:>8.2} ms  median {:>8.2}  min {:>8.2}  max {:>8.2}  (n={})",
        sorted[sorted.len() / 2],
        sorted[0],
        sorted[sorted.len() - 1],
        sorted.len()
    )
}
