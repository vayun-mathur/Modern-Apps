//! Runs Gemma 4's vision tower on the GPU and compares it with onnxruntime's.
//!
//! ```text
//! python scripts/ml/gemma4_vision_parity.py vision_encoder_fp16.onnx -o vision_golden.json
//! cargo run --offline --release -p modelrunner --example check_gemma4_vision_parity -- \
//!     gemma4_vision.maml vision_golden.json
//! ```
//!
//! # What only this catches
//!
//! `nets::gemma4_vision`'s unit tests check that 637 tensors are declared in the order the
//! converter writes them, that a grid divides by the pooling kernel, that a pixel lands in the
//! channel the export's patchify would put it. All of that passes on a tower that is wrong,
//! because a transposed read agrees with itself.
//!
//! Sixteen layers against real weights catch errors of *composition*, and this tower has four
//! that no shape would notice:
//!
//! * the two learned position tables swapped, so every image is transposed;
//! * the two rotary blocks swapped, likewise;
//! * `1 / sqrt(head_dim)` applied when the export applies none, flattening all sixteen layers;
//! * a clip dropped, which moves values fp16 was calibrated to hold.
//!
//! The golden is generated on a **33 x 18** patch grid precisely so the first two are visible. On
//! a square grid they are not.
//!
//! # What agreement to expect
//!
//! The reference is fp16 weights in fp32 arithmetic; this is int4 weights with a per-block scale,
//! and the worst tensor's conversion fidelity was 0.9927. So the numbers will not match, and the
//! bar is a cosine that stays near one *and does not fall as the layers accumulate*. A wrong
//! composition does not drift, it steps.
use std::path::PathBuf;
use std::sync::Arc;

use modelrunner::nets::gemma4_vision as vision;
use modelrunner::vulkan::context;
use modelrunner::vulkan::reshape::Reshaped;
#[path = "common/gemma4_tower_time.rs"]
mod tower_time;
use modelrunner::weights::{graph, Weights};

/// A reproducible byte for pixel `(y, x)`, channel `(0, 1, 2)` = `(r, g, b)`.
///
/// Must stay bit-identical to `synthetic_pixel` in `scripts/ml/gemma4_vision_parity.py`, which is
/// how the two sides agree on an image without the golden carrying one. A hash rather than a
/// gradient because a linear ramp is nearly rank-one, and a rank-one input hides exactly the
/// errors above.
fn synthetic_pixel(y: u32, x: u32, channel: u32) -> u32 {
    let mut h = y
        .wrapping_mul(73_856_093)
        ^ x.wrapping_mul(19_349_663)
        ^ channel.wrapping_mul(83_492_791);
    h ^= h >> 13;
    h = h.wrapping_mul(1_274_126_177);
    h ^= h >> 16;
    h & 0xff
}

/// The whole image as ARGB_8888, which is what [`vision::patchify`] takes.
fn synthetic_image(grid: vision::Grid) -> Vec<i32> {
    let (width, height) = grid.pixels();
    let mut pixels = Vec::with_capacity((width * height) as usize);
    for y in 0..height {
        for x in 0..width {
            let argb = 0xff00_0000
                | (synthetic_pixel(y, x, 0) << 16)
                | (synthetic_pixel(y, x, 1) << 8)
                | synthetic_pixel(y, x, 2);
            pixels.push(argb as i32);
        }
    }
    pixels
}

fn main() {
    let mut args = std::env::args().skip(1);
    let (Some(model), Some(golden)) = (args.next().map(PathBuf::from), args.next()) else {
        println!("usage: check_gemma4_vision_parity <vision.maml> <vision_golden.json> [--time N]");
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
    let weights = match Weights::parse(&bytes, graph::GEMMA4_VISION) {
        Ok(w) => w,
        Err(why) => return println!("the vision model does not parse: {why}"),
    };
    if weights.len() != vision::TENSORS {
        return println!("{} tensors, not {}", weights.len(), vision::TENSORS);
    }
    let golden = match std::fs::read_to_string(&golden) {
        Ok(text) => text,
        Err(why) => return println!("cannot read the golden: {why}"),
    };
    let (Some(rows), Some(cols)) = (scalar(&golden, "rows"), scalar(&golden, "cols")) else {
        return println!("the golden has no grid");
    };
    let grid = match vision::Grid::new(rows, cols) {
        Ok(grid) => grid,
        Err(why) => return println!("the golden's grid is not one this builds: {why}"),
    };
    println!(
        "grid {}x{} patches = {} patches, {} soft tokens",
        grid.rows,
        grid.cols,
        grid.patches(),
        grid.soft_tokens()
    );
    if grid.rows == grid.cols {
        println!("WARNING: a square grid cannot tell a row from a column");
    }

    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => return println!("no Vulkan device: {why}"),
    };
    let pixels = synthetic_image(grid);
    let inputs = match vision::prepare(&weights.reader(), grid, &pixels) {
        Ok(inputs) => inputs,
        Err(why) => return println!("the host preprocessing failed: {why}"),
    };
    if let Some(repeats) = repeats {
        let mode = vision::Mode::Image(grid);
        let tick = std::time::Instant::now();
        let built = Reshaped::new(Arc::clone(&context), &weights, mode, |offsets, mode| vision::build(offsets, mode));
        let mut net = match built {
            Ok(net) => net,
            Err(why) => return println!("the image net does not build: {why}"),
        };
        println!("image net built in {:.0} ms", tick.elapsed().as_secs_f64() * 1000.0);
        if let Err(why) = tower_time::time_runs(&mut net, mode, &[&inputs[0], &inputs[1], &inputs[2]], repeats) {
            println!("timing failed: {why}");
        }
        return;
    }

    // Bisect first: a whole-tower disagreement is much easier to read once the layers below it
    // have been placed.
    println!();
    println!("bisect against the reference's intermediates");
    println!("  exact = the export as published, int4 = the same weights through four bits");
    let mut worst = 1.0_f64;
    let mut labels: Vec<(String, usize)> =
        (0..vision::LAYERS).map(|i| (format!("layer_in_{i}"), i)).collect();
    labels.push(("tower_out".to_string(), vision::LAYERS));
    for (label, layers) in labels {
        let (Some(exact), Some(probes)) =
            (field(&golden, &label), field_usize(&golden, &format!("{label}_rows")))
        else {
            continue;
        };
        match trace(&context, &weights, grid, layers, &inputs) {
            Ok((hidden, _)) => {
                let got =
                    gather_rows(&hidden, grid.patches() as usize, vision::D_MODEL as usize, &probes);
                let control = field(&golden, &format!("{label}_int4"));
                let (line, cosine) = against(&got, &exact, control.as_deref(), TRACE_FLOOR);
                worst = worst.min(cosine);
                println!("  {label:<12} {line}");
            }
            Err(why) => println!("  {label:<12} failed: {why}"),
        }
    }
    if let (Some(exact), Some(probes)) =
        (field(&golden, "pooled_in"), field_usize(&golden, "pooled_in_rows"))
    {
        match trace(&context, &weights, grid, vision::LAYERS, &inputs) {
            Ok((_, pooled)) => {
                let got = gather_rows(
                    &pooled,
                    grid.soft_tokens() as usize,
                    vision::D_MODEL as usize,
                    &probes,
                );
                let control = field(&golden, "pooled_in_int4");
                let (line, cosine) = against(&got, &exact, control.as_deref(), TRACE_FLOOR);
                worst = worst.min(cosine);
                println!("  {:<12} {line}", "pooled_in");
            }
            Err(why) => println!("  {:<12} failed: {why}", "pooled_in"),
        }
    }

    println!();
    let Some(exact) = field(&golden, "image_features") else {
        return println!("the golden has no image_features");
    };
    let control = field(&golden, "image_features_int4");
    match run(&context, &weights, grid, &inputs) {
        Ok(features) => report(&features, &exact, control.as_deref(), grid, worst),
        Err(why) => println!("the image pass failed: {why}"),
    }
}

/// Compare against the exact reference and, when it is in the golden, the int4 control.
///
/// The returned cosine is the one that decides: against the control if there is one, since that
/// is the only bar the device can actually meet. See `gemma4_vision_parity.py`.
fn against(got: &[f32], exact: &[f32], control: Option<&[f32]>, floor: f64) -> (String, f64) {
    let (exact_line, exact_cosine) = compare(got, exact);
    match control {
        None => (format!("exact {exact_line}"), exact_cosine),
        Some(control) => {
            let (line, cosine) = compare(got, control);
            let verdict = if cosine > floor { "ok" } else { "WRONG" };
            (
                format!(
                    "int4 cosine {cosine:.6} {verdict}   (exact {exact_cosine:.6}, \
                     which four bits alone cost)"
                ),
                cosine,
            )
        }
    }
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

/// The tower stopped after `layers` layers: `(hidden, pooled)`.
fn trace(
    context: &Arc<context::Context>,
    weights: &Weights,
    grid: vision::Grid,
    layers: usize,
    inputs: &[Vec<f32>; vision::INPUTS],
) -> Result<(Vec<f32>, Vec<f32>), String> {
    let mode = vision::Mode::Trace { grid, layers };
    let mut net = Reshaped::new(Arc::clone(context), weights, mode, |offsets, mode| {
        vision::build(offsets, mode)
    })?;
    let out = net
        .at(mode)?
        .infer_raw_many(&[&inputs[0], &inputs[1], &inputs[2]])?;
    let mut it = out.into_iter();
    Ok((it.next().unwrap_or_default(), it.next().unwrap_or_default()))
}

/// The whole pass: `[soft_tokens, 1536]`, laid out row-major as the golden is.
fn run(
    context: &Arc<context::Context>,
    weights: &Weights,
    grid: vision::Grid,
    inputs: &[Vec<f32>; vision::INPUTS],
) -> Result<Vec<f32>, String> {
    let mode = vision::Mode::Image(grid);
    let mut net = Reshaped::new(Arc::clone(context), weights, mode, |offsets, mode| {
        vision::build(offsets, mode)
    })?;
    let out = net
        .at(mode)?
        .infer_raw_many(&[&inputs[0], &inputs[1], &inputs[2]])?;
    let features = out.into_iter().next().ok_or("the plan produced no output")?;
    let tokens = grid.soft_tokens() as usize;
    let width = vision::OUT_DIM as usize;
    if features.len() != tokens * width {
        return Err(format!("{} values, not {}", features.len(), tokens * width));
    }
    // The plan writes `[1536, 1, tokens]`; the golden is `[tokens, 1536]`.
    Ok(gather_rows(&features, tokens, width, &(0..tokens).collect::<Vec<_>>()))
}

/// Cosine and RMS between a device tensor and the reference's.
fn compare(got: &[f32], want: &[f32]) -> (String, f64) {
    if got.len() != want.len() {
        return (format!("length {} against {}", got.len(), want.len()), 0.0);
    }
    let dot: f64 = got.iter().zip(want).map(|(a, b)| f64::from(*a) * f64::from(*b)).sum();
    let na: f64 = got.iter().map(|a| f64::from(*a) * f64::from(*a)).sum::<f64>().sqrt();
    let nb: f64 = want.iter().map(|b| f64::from(*b) * f64::from(*b)).sum::<f64>().sqrt();
    let cosine = dot / (na * nb).max(1e-30);
    let n = got.len() as f64;
    let verdict = if cosine > 0.99 { "ok" } else { "WRONG" };
    (
        format!(
            "cosine {cosine:.6}  rms {:.4} against {:.4}  {verdict}",
            na / n.sqrt(),
            nb / n.sqrt()
        ),
        cosine,
    )
}

/// The bar an intermediate must clear against the int4 control.
///
/// The measured values sit at 0.99999 through the tower, so this is loose by two orders of
/// magnitude and still nowhere near what a composition error produces. Measured, by building the
/// same tower with `attn_scores` instead of `attn_scores_prescaled`: `tower_out` falls to 0.444
/// and the output to 0.635. There is no overlap between the two populations.
const TRACE_FLOOR: f64 = 0.9995;

/// The bar the output must clear, which is looser than [`TRACE_FLOOR`] on purpose.
///
/// The arena is fp16 and the pooled state has an RMS near 1100, so the final norm and the
/// `[1536, 768]` projection accumulate 768 terms at a magnitude where fp16's step is about half a
/// unit. That is real rounding the control does not model, since onnxruntime accumulates in fp32.
const OUTPUT_FLOOR: f64 = 0.998;

/// The bar the worst single soft token must clear. One bad token hides inside a whole-tensor
/// cosine, and a soft token is what the decoder actually reads.
const TOKEN_FLOOR: f64 = 0.99;

/// Print the comparison, and say plainly whether it passed.
fn report(
    got: &[f32],
    exact: &[f32],
    control: Option<&[f32]>,
    grid: vision::Grid,
    worst_trace: f64,
) {
    let (line, cosine) = against(got, exact, control, OUTPUT_FLOOR);
    println!("image_features  {line}");
    if got.len() != exact.len() {
        return println!("FAIL: the shapes do not even agree");
    }
    let bar = control.unwrap_or(exact);

    // Per-soft-token cosine, because one bad token hides inside a whole-tensor cosine.
    let width = vision::OUT_DIM as usize;
    let mut lowest = (0usize, 1.0_f64);
    for token in 0..grid.soft_tokens() as usize {
        let a = &got[token * width..(token + 1) * width];
        let b = &bar[token * width..(token + 1) * width];
        let dot: f64 = a.iter().zip(b).map(|(x, y)| f64::from(*x) * f64::from(*y)).sum();
        let na: f64 = a.iter().map(|x| f64::from(*x) * f64::from(*x)).sum::<f64>().sqrt();
        let nb: f64 = b.iter().map(|y| f64::from(*y) * f64::from(*y)).sum::<f64>().sqrt();
        let each = dot / (na * nb).max(1e-30);
        if each < lowest.1 {
            lowest = (token, each);
        }
    }
    println!("worst soft token {} at cosine {:.6}", lowest.0, lowest.1);

    println!();
    if control.is_none() {
        println!("NOTE: the golden has no int4 control, so this is measured against fp16 weights");
        println!("      and cannot tell a bug from four bits. Regenerate without --no-control.");
        return;
    }
    if cosine > OUTPUT_FLOOR && lowest.1 > TOKEN_FLOOR && worst_trace > TRACE_FLOOR {
        println!("PASS: the vision tower is as close to the reference as four bits allow");
    } else {
        println!("FAIL: this is a composition error, not quantisation");
        println!("      the bisect above says which layer it enters at");
    }
}

/// One `"key": number` from the golden.
fn scalar(text: &str, key: &str) -> Option<u32> {
    let at = text.find(&format!("\"{key}\""))?;
    let rest = &text[at..];
    let colon = rest.find(':')?;
    rest[colon + 1..]
        .trim_start()
        .split(|c: char| !c.is_ascii_digit())
        .next()
        .and_then(|n| n.parse().ok())
}

/// One named array from the golden.
fn field(text: &str, key: &str) -> Option<Vec<f32>> {
    Some(array(text, key)?.split(',').filter_map(|p| p.trim().parse().ok()).collect())
}

/// One named array of indices from the golden.
fn field_usize(text: &str, key: &str) -> Option<Vec<usize>> {
    Some(array(text, key)?.split(',').filter_map(|p| p.trim().parse().ok()).collect())
}

/// The text between the brackets of `"key": [...]`.
fn array<'a>(text: &'a str, key: &str) -> Option<&'a str> {
    let at = text.find(&format!("\"{key}\""))?;
    let rest = &text[at..];
    let open = rest.find('[')?;
    let close = rest[open..].find(']')?;
    Some(&rest[open + 1..open + close])
}
