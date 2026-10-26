//! Runs Gemma 4's audio tower on the GPU and compares it with onnxruntime's.
//!
//! ```text
//! python scripts/ml/gemma4_audio_encoder_parity.py audio_encoder_fp16.onnx -o audio_golden.json
//! cargo run --offline --release -p modelrunner --example check_gemma4_audio_parity -- \
//!     gemma4_audio.maml audio_golden.json
//! ```
//!
//! # What only this catches
//!
//! Until this existed the audio tower had never executed on a GPU in any form. `nets::gemma4_audio`
//! has twenty unit tests and `vulkan::parity` has ten for the banded ops, and all of them pass —
//! but the unit tests check that 747 tensors are declared in the order the converter writes them,
//! and the parity tests check one op against its own oracle. Both are satisfied by a tower whose
//! twelve layers are composed wrongly.
//!
//! # The int4 control is the bar, and it is not optional
//!
//! The device runs int4 weights and the export runs fp16 ones, so the two cannot agree closely.
//! Measured by the generator, four bits alone cost this much cosine at the output:
//!
//! ```text
//! T = 1   0.009    T = 25  0.017    T = 48  0.041
//! T = 4   0.002    T = 56  0.037
//! ```
//!
//! So against the exact reference a *correct* tower reads anywhere from 0.998 to 0.958 depending
//! only on how long the clip is, and no fixed floor exists that anyone could set. The control's
//! value is not that it widens the gap — it is that it makes the correct answer a CONSTANT. Every
//! comparison below is against `_int4` when the golden carries it.
//!
//! # The per-probe bar, which I expected not to exist
//!
//! The plan was to print the profile unthresholded, on the reasoning that fp16 Vulkan against
//! fp32 onnxruntime must drift with depth and no floor could be both tight enough to localise and
//! loose enough to pass. The first device run disproved that: the drift across twelve layers is
//! two parts in a million, against negative controls three orders of magnitude further out. So
//! [`PROBE_FLOOR`] is real and measured, not assumed.
use std::path::PathBuf;
use std::sync::Arc;

use modelrunner::logmel::{self, LogMel};
use modelrunner::nets::gemma4_audio as audio;
use modelrunner::vulkan::context;
use modelrunner::vulkan::reshape::Reshaped;
#[path = "common/gemma4_tower_time.rs"]
mod tower_time;
use modelrunner::weights::{graph, Weights};

/// The bar every probe must clear against the int4 control.
///
/// I expected this to be impossible to set. The reasoning was that Vulkan fp16 against fp32
/// onnxruntime must drift with depth, so any threshold tight enough to localise a fault would
/// fire on a correct run. The first device run disproved it. Measured on `multi`, T = 56:
///
/// ```text
/// layer0_in_projected 1.000000    layer_in_4..9  0.999999    tower_out    0.999998
/// layer_in_0..3       1.000000    layer_in_10,11 0.999998    encoder_out  0.999998
/// ```
///
/// Two parts in one millionth across twelve layers. The drift is real and monotonic and it is
/// four orders of magnitude smaller than I assumed. Against that, the generator's three negative
/// controls read 0.9677, 0.9874 and 0.9111 on the same clip, so this floor sits with roughly
/// three orders of margin on both sides.
///
/// Tighten it if the drift ever comes in lower; do NOT loosen it to accommodate a failure.
const PROBE_FLOOR: f64 = 0.9995;

/// The bar the output must clear against the int4 control.
///
/// Measured rather than chosen. The generator's three negative controls — all twelve relative
/// tables reversed, layer 6's alone, and q/k swapped — read 0.9677, 0.9874 and 0.9111 against the
/// same control. A correct device should reach the control almost exactly, so 0.99 sits between
/// the two populations. Note how little room that leaves: the single-layer fault is at 0.9874, so
/// a subtler one will sit above this floor and only the profile will show it.
const OUTPUT_FLOOR: f64 = 0.99;

/// A reproducible sample, bit-identical to `synthetic_pcm` in the generator.
///
/// Integer hash to i16 then a divide by 32768, which is exact in binary, so both sides agree to
/// the bit and the golden carries no waveform.
fn synthetic_pcm(count: usize) -> Vec<f32> {
    (0..count)
        .map(|i| {
            let mut h = (i as u32).wrapping_mul(73_856_093);
            h ^= h >> 13;
            h = h.wrapping_mul(1_274_126_177);
            h ^= h >> 16;
            ((h & 0xFFFF) as i32 - 32768) as f32 / 32768.0
        })
        .collect()
}

/// The mel frames for a clip, through this crate's own front end.
///
/// Deliberately `logmel` rather than a dump from the golden: it makes the harness end to end, and
/// `logmel` is already pinned against the same reference extractor to 2.24e-7 of the frame peak,
/// so it cannot be the source of a disagreement seen here.
fn mel_for(real: usize) -> Result<(Vec<f32>, u32), String> {
    let waveform = synthetic_pcm(real);
    let mut frames = Vec::new();
    let count = LogMel::new().spectrogram(&waveform, &mut frames);
    if count != logmel::frame_count(real) {
        return Err(format!("{count} frames, not {}", logmel::frame_count(real)));
    }
    Ok((frames, count as u32))
}

/// One plan, run once. `outputs` is however many the mode produces.
fn run(
    context: &Arc<context::Context>,
    weights: &Weights,
    mode: audio::Mode,
    input: &[f32],
) -> Result<Vec<Vec<f32>>, String> {
    let mut net = Reshaped::new(Arc::clone(context), weights, mode, |offsets, mode| {
        audio::build(offsets, mode)
    })?;
    net.at(mode)?.infer_raw_many(&[input])
}

/// Rows `probes` of a `[channels, 1, positions]` tensor, laid out row-major as the golden is.
fn gather_rows(values: &[f32], positions: usize, channels: usize, probes: &[usize]) -> Vec<f32> {
    let mut out = Vec::with_capacity(probes.len() * channels);
    for &position in probes {
        for channel in 0..channels {
            out.push(values.get(channel * positions + position).copied().unwrap_or(f32::NAN));
        }
    }
    out
}

/// Cosine between a device tensor and the reference's.
fn cosine(got: &[f32], want: &[f32]) -> f64 {
    if got.len() != want.len() || got.is_empty() {
        return f64::NAN;
    }
    let dot: f64 = got.iter().zip(want).map(|(a, b)| f64::from(*a) * f64::from(*b)).sum();
    let na: f64 = got.iter().map(|a| f64::from(*a) * f64::from(*a)).sum::<f64>().sqrt();
    let nb: f64 = want.iter().map(|b| f64::from(*b) * f64::from(*b)).sum::<f64>().sqrt();
    dot / (na * nb).max(1e-30)
}

/// The worst single probe token, and which one.
///
/// A pooled cosine over six probe rows hides a fault confined to one of them, and the ramp is
/// exactly such a fault: an unguarded band read corrupts only tokens 0..10, which is 44 % of a
/// T = 25 clip but 1.5 % of a T = 750 one. The clips that would make it unmissable - T = 1 and
/// T = 4 - cannot execute, because `MIN_TOKENS` is 12 and the band is the model's rather than
/// the clip's. So the ramp has to be caught per token instead of per clip, and `probe_tokens`
/// carries 0, 1, 11 and 12 for that reason.
fn worst_token(got: &[f32], want: &[f32], probes: &[usize], width: usize) -> (usize, f64) {
    let mut lowest = (usize::MAX, 1.0_f64);
    for (row, token) in probes.iter().enumerate() {
        let (from, to) = (row * width, (row + 1) * width);
        let (Some(a), Some(b)) = (got.get(from..to), want.get(from..to)) else { continue };
        let each = cosine(a, b);
        if each < lowest.1 {
            lowest = (*token, each);
        }
    }
    lowest
}

/// Compare one probe point against the control when there is one, else the exact reference.
///
/// Returns `(line, cosine, measured_against_control)`. The third matters: a cosine against the
/// exact reference is not comparable to [`OUTPUT_FLOOR`], because four bits alone move it by up
/// to 0.04 and the amount depends on the clip's length.
fn against(got: &[f32], clip: &str, label: &str, golden: &str) -> (String, f64, bool) {
    let Some(exact) = field(golden, clip, label) else {
        return (format!("{label}: absent from the golden"), f64::NAN, false);
    };
    let exact_cosine = cosine(got, &exact);
    match field(golden, clip, &format!("{label}_int4")) {
        Some(control) => {
            let c = cosine(got, &control);
            (format!("int4 {c:.6}   (exact {exact_cosine:.6})"), c, true)
        }
        None => (format!("exact {exact_cosine:.6}   NO CONTROL"), exact_cosine, false),
    }
}

/// `--time N`: the clip pass at `seconds` of synthetic audio, built once and run `N` times.
fn time_clip(context: &Arc<context::Context>, weights: &Weights, seconds: f64, repeats: usize) -> Result<(), String> {
    let real = ((seconds * 16_000.0) as usize).min(audio::MAX_SAMPLES);
    let (mel, frames) = mel_for(real)?;
    let input = audio::prepare(&mel, frames)?;
    let mode = audio::Mode::Clip { frames };
    println!();
    println!("=== {seconds} s: {frames} frames, T {}", audio::tokens(frames));
    let tick = std::time::Instant::now();
    let mut net = Reshaped::new(Arc::clone(context), weights, mode, |offsets, mode| audio::build(offsets, mode))?;
    println!("clip net built in {:.0} ms", tick.elapsed().as_secs_f64() * 1000.0);
    tower_time::time_runs(&mut net, mode, &[&input], repeats)
}

fn main() {
    let mut args = std::env::args().skip(1);
    let (Some(model), Some(path)) = (args.next().map(PathBuf::from), args.next()) else {
        println!("usage: check_gemma4_audio_parity <gemma4_audio.maml> <audio_golden.json> [--time N]");
        return;
    };
    let repeats = match (args.next().as_deref(), args.next()) {
        (Some("--time"), n) => Some(n.and_then(|n| n.parse().ok()).unwrap_or(3)),
        _ => None,
    };
    let bytes = match std::fs::read(&model) {
        Ok(bytes) => bytes,
        Err(why) => return println!("cannot read {}: {why}", model.display()),
    };
    let weights = match Weights::parse(&bytes, graph::GEMMA4_AUDIO) {
        Ok(w) => w,
        Err(why) => return println!("the audio model does not parse: {why}"),
    };
    if weights.len() != audio::TENSORS {
        return println!("{} tensors, not {}", weights.len(), audio::TENSORS);
    }
    let golden = match std::fs::read_to_string(&path) {
        Ok(text) => text,
        Err(why) => return println!("cannot read the golden: {why}"),
    };
    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => return println!("no Vulkan device: {why}"),
    };
    if let Some(repeats) = repeats {
        for seconds in [5.0, 30.0] {
            if let Err(why) = time_clip(&context, &weights, seconds, repeats) {
                println!("timing {seconds} s failed: {why}");
            }
        }
        return;
    }

    let mut worst = (1.0_f64, String::new());
    let mut any_control = false;
    for clip in clips(&golden) {
        let (Some(real), Some(want_frames), Some(want_tokens)) = (
            scalar(&golden, &clip, "real"),
            scalar(&golden, &clip, "frames"),
            scalar(&golden, &clip, "tokens"),
        ) else {
            println!("{clip}: incomplete entry, skipped");
            continue;
        };
        let probes = field_usize(&golden, &clip, "probe_tokens").unwrap_or_default();
        println!();
        println!("=== {clip}: {real} samples, {want_frames} frames, T {want_tokens}");

        let (mel, frames) = match mel_for(real) {
            Ok(pair) => pair,
            Err(why) => {
                println!("  the front end failed: {why}");
                continue;
            }
        };
        if frames != want_frames as u32 {
            println!("  FRAME COUNT DISAGREES: {frames} here, {want_frames} in the golden");
            continue;
        }
        let input = match audio::prepare(&mel, frames) {
            Ok(input) => input,
            Err(why) => {
                println!("  prepare failed: {why}");
                continue;
            }
        };
        let tokens = audio::tokens(frames) as usize;
        if tokens != want_tokens {
            println!("  TOKEN COUNT DISAGREES: {tokens} here, {want_tokens} in the golden");
            continue;
        }
        // The module refuses a clip shorter than the attention band, and it is right to: the band
        // is a property of the model, not of the clip. So the two short cases in the golden - the
        // T = 1 and T = 4 clips chosen to sit entirely inside the start-of-sequence ramp - cannot
        // execute here at all, and this says so once instead of failing fifteen times.
        //
        // The ramp is still covered. Queries 0..10 of EVERY clip are on it, and `probe_tokens`
        // includes 0, 1, 11 and 12 precisely to straddle its boundary.
        if tokens < audio::MIN_TOKENS as usize {
            println!(
                "  skipped: T {tokens} is under MIN_TOKENS {}, which the 12-wide band requires.",
                audio::MIN_TOKENS
            );
            println!("  the ramp it was chosen for is still probed at tokens 0, 1, 11, 12 elsewhere");
            continue;
        }

        // The front end. Only `projected` is compared: the device's `sscp_out` is in a PERMUTED
        // channel order relative to the export's, because the converter permutes the input
        // projection's rows (`input_rows` in `collect_gemma4_audio`) and the projection undoes it.
        // Measured: sscp_out reads 0.29 against the golden while projected reads 1.000000 on the
        // same run, which is the permutation and not a fault. Comparing it would need the golden
        // to carry the permutation, which it does not.
        match run(&context, &weights, audio::Mode::Sscp { frames }, &input) {
            Ok(out) => {
                let Some(values) = out.get(1) else {
                    println!("  Sscp produced no projected output");
                    continue;
                };
                let got = gather_rows(values, tokens, audio::D_MODEL as usize, &probes);
                let (line, c, controlled) = against(&got, &clip, "layer0_in_projected", &golden);
                any_control |= controlled;
                println!("  {:<22} {line}", "layer0_in_projected");
                println!("  {:<22} not compared: permuted channel order, see the source", "sscp_out");
                if c < worst.0 {
                    worst = (c, format!("{clip}/layer0_in_projected"));
                }
            }
            Err(why) => println!("  Sscp failed: {why}"),
        }

        // The layer sweep, on the two extremes only. Every stop point re-records the plan, so
        // thirteen of them per clip is the expensive part of this harness; `tiny` is the
        // start-of-sequence ramp where every query is on the edge, and `multi` is the longest and
        // the one where int4 costs the most, which is where a fault hides best.
        if clip == "multi" || clip == "tiny" {
            for layer in 0..=audio::LAYERS {
                let mode = audio::Mode::Trace { frames, layers: layer };
                let label = if layer == audio::LAYERS {
                    "tower_out".to_string()
                } else {
                    format!("layer_in_{layer}")
                };
                match run(&context, &weights, mode, &input) {
                    Ok(out) => {
                        let Some(hidden) = out.first() else { continue };
                        let got = gather_rows(hidden, tokens, audio::D_MODEL as usize, &probes);
                        let (line, c, controlled) = against(&got, &clip, &label, &golden);
                        any_control |= controlled;
                        println!("  {label:<22} {line}");
                        if c < worst.0 {
                            worst = (c, format!("{clip}/{label}"));
                        }
                        if layer == audio::LAYERS {
                            if let Some(tail) = out.get(1) {
                                let got =
                                    gather_rows(tail, tokens, audio::OUT_DIM as usize, &probes);
                                let (line, c, _) = against(&got, &clip, "encoder_out", &golden);
                                println!("  {:<22} {line}", "encoder_out");
                                if c < worst.0 {
                                    worst = (c, format!("{clip}/encoder_out"));
                                }
                            }
                        }
                    }
                    Err(why) => println!("  {label:<22} failed: {why}"),
                }
            }
        }

        // The output, which is the only figure with a floor under it.
        match run(&context, &weights, audio::Mode::Clip { frames }, &input) {
            Ok(out) => {
                let Some(features) = out.first() else {
                    println!("  the clip pass produced no output");
                    continue;
                };
                let all: Vec<usize> = (0..tokens).collect();
                let whole = gather_rows(features, tokens, audio::OUT_DIM as usize, &all);
                let got = gather_rows(features, tokens, audio::OUT_DIM as usize, &probes);
                let (line, c, controlled) = against(&got, &clip, "audio_features", &golden);
                any_control |= controlled;
                let verdict = if !controlled {
                    "NO CONTROL, unreadable"
                } else if c > OUTPUT_FLOOR {
                    "ok"
                } else {
                    "WRONG"
                };
                println!("  {:<22} {line}   {verdict}", "audio_features");
                // Per token, because the ramp is a fault in tokens 0..10 only and the pooled
                // figure above averages it away. This is the check that stands in for the T = 1
                // clip the module will not run.
                let bar = field(&golden, &clip, "audio_features_int4")
                    .or_else(|| field(&golden, &clip, "audio_features"));
                if let Some(bar) = bar {
                    let (token, each) =
                        worst_token(&got, &bar, &probes, audio::OUT_DIM as usize);
                    let ramp = if token < audio::ATTEND_SPAN as usize { "  ON THE RAMP" } else { "" };
                    let mark = if each > OUTPUT_FLOOR { "ok" } else { "WRONG" };
                    println!(
                        "  {:<22} token {token} at {each:.6}   {mark}{ramp}",
                        "worst probe token"
                    );
                    if each < worst.0 {
                        worst = (each, format!("{clip}/token {token}"));
                    }
                }
                let _ = whole;
            }
            Err(why) => println!("  the clip pass failed: {why}"),
        }
    }

    println!();
    if !any_control {
        println!("NOTE: the golden carries no int4 control, so nothing above can tell a bug from");
        println!("      four bits. Regenerate the golden without --no-control.");
        return;
    }
    let verdict = if worst.0 > PROBE_FLOOR { "PASS" } else { "FAIL" };
    println!("worst probe {:.6} at {}   floor {PROBE_FLOOR}   {verdict}", worst.0, worst.1);
    println!();
    if worst.0 > PROBE_FLOOR {
        println!("The tower is as close to the reference as four bits allow, at every probe.");
        println!("For scale: the generator's negative controls read 0.9677 (all twelve relative");
        println!("tables reversed), 0.9874 (layer 6's alone) and 0.9111 (q/k swapped) against the");
        println!("same control. A composition error is three orders of magnitude away from here.");
    } else {
        println!("This is a composition error, not quantisation. Read the profile above: the");
        println!("first probe to fall names the layer AFTER the fault. Smooth and monotonic is");
        println!("quantisation; a step at one layer is not.");
    }
}

/// The clip names in the golden, in order.
fn clips(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut at = 0usize;
    while let Some(found) = text[at..].find("\"name\":") {
        let start = at + found + "\"name\":".len();
        let rest = text[start..].trim_start();
        if let Some(stripped) = rest.strip_prefix('"') {
            if let Some(end) = stripped.find('"') {
                out.push(stripped[..end].to_string());
            }
        }
        at = start;
    }
    out
}

/// The slice of the golden belonging to `clip`, so a key lookup cannot cross into another one.
fn slice<'a>(text: &'a str, clip: &str) -> Option<&'a str> {
    let start = text.find(&format!("\"name\": \"{clip}\""))?;
    let rest = &text[start + 1..];
    let end = rest.find("\"name\":").map_or(text.len(), |n| start + 1 + n);
    Some(&text[start..end])
}

/// One `"key": number` from a clip's slice.
fn scalar(text: &str, clip: &str, key: &str) -> Option<usize> {
    let body = slice(text, clip)?;
    let at = body.find(&format!("\"{key}\""))?;
    let rest = &body[at..];
    let colon = rest.find(':')?;
    rest[colon + 1..]
        .trim_start()
        .split(|c: char| !c.is_ascii_digit())
        .next()
        .and_then(|n| n.parse().ok())
}

/// One named array of floats from a clip's slice.
fn field(text: &str, clip: &str, key: &str) -> Option<Vec<f32>> {
    Some(array(slice(text, clip)?, key)?.split(',').filter_map(|p| p.trim().parse().ok()).collect())
}

/// One named array of indices from a clip's slice.
fn field_usize(text: &str, clip: &str, key: &str) -> Option<Vec<usize>> {
    Some(array(slice(text, clip)?, key)?.split(',').filter_map(|p| p.trim().parse().ok()).collect())
}

/// The text between the brackets of `"key": [...]`, matching the key exactly.
fn array<'a>(text: &'a str, key: &str) -> Option<&'a str> {
    let at = text.find(&format!("\"{key}\": ["))?;
    let rest = &text[at..];
    let open = rest.find('[')?;
    let close = rest[open..].find(']')?;
    Some(&rest[open + 1..open + close])
}
