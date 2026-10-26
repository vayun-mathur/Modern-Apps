//! Per-model wall time, per-kind GPU time and output checksums for the vision nets.
//!
//! ```text
//! cargo run --release -p modelrunner --example profile_vision -- all path/to/assets
//! adb shell MODELRUNNER_TIMESTAMPS=1 /data/local/tmp/maml/vision/profile_vision all /data/local/tmp/maml/vision
//! ```
//!
//! `which` is one of `all`, `selfie`, `modnet`, `u2netp`, `scrfd`, `mbf`, `det`, `rec`,
//! `clip_image`, `clip_text`, `maia`. `dir` holds the `.maml` files under their asset
//! names (`selfie_segmentation.maml`, `modnet.maml`, `u2netp.maml`, `scrfd_500m.maml`,
//! `w600k_mbf.maml`, `ppocr_det.maml`, `ppocr_rec.maml`, `clip/tinyclip.maml`,
//! `maia3-5m.maml`); it defaults to `MODELRUNNER_VISION` or the current directory.
//!
//! Timestamps need `MODELRUNNER_TIMESTAMPS=1`; without it only wall times and checksums
//! print. Measure the two in separate runs. The checksum is the whole-output f64 sum,
//! printed so two builds can be compared for identical output.
use std::collections::BTreeMap;
use std::time::Instant;

use modelrunner::nets::{
    maia, mobilefacenet, modnet, ppocr_det, ppocr_rec, scrfd, selfie, tinyclip, u2netp, Plan,
};
use modelrunner::post::ocr::REC_WIDTH;
use modelrunner::preprocess::{f16_to_f32, to_planar_f16, PPOCR_REC, RESCALE_ONLY};
use modelrunner::vulkan::context;
use modelrunner::vulkan::reshape::Reshaped;
use modelrunner::vulkan::run::Net;
use modelrunner::weights::{graph, Offsets, Weights};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let which = args.first().map(String::as_str).unwrap_or("all");
    let dir = args
        .get(1)
        .map(String::as_str)
        .map(str::to_owned)
        .or_else(|| std::env::var("MODELRUNNER_VISION").ok())
        .unwrap_or_else(|| ".".into());
    let models: &[(&str, &str, u32)] = &[
        ("selfie", "selfie_segmentation.maml", 5),
        ("modnet", "modnet.maml", 3),
        ("u2netp", "u2netp.maml", 2),
        ("scrfd", "scrfd_500m.maml", 3),
        ("mbf", "w600k_mbf.maml", 5),
        ("det", "ppocr_det.maml", 2),
        ("rec", "ppocr_rec.maml", 5),
        ("rec_batch", "ppocr_rec.maml", 5),
        ("clip_image", "clip/tinyclip.maml", 3),
        ("clip_text", "clip/tinyclip.maml", 5),
        ("maia", "maia3-5m.maml", 3),
    ];
    let mut failed = false;
    for (name, file, passes) in models {
        if which != "all" && which != *name {
            continue;
        }
        let path = format!("{dir}/{file}");
        println!("=== {name} ({path}) ===");
        let result = match *name {
            "selfie" => profile_selfie(&path, *passes),
            "modnet" => profile_modnet(&path, *passes),
            "u2netp" => profile_u2netp(&path, *passes),
            "scrfd" => profile_scrfd(&path, *passes),
            "mbf" => profile_mbf(&path, *passes),
            "det" => profile_det(&path, *passes),
            "rec" => profile_rec(&path, *passes),
            "rec_batch" => profile_rec_batch(&path, *passes),
            "clip_image" => profile_clip_image(&path, *passes),
            "clip_text" => profile_clip_text(&path, *passes),
            "maia" => profile_maia(&path, *passes),
            other => Err(format!("unknown model {other:?}")),
        };
        if let Err(why) = result {
            println!("failed: {why}");
            failed = true;
        }
        println!();
    }
    if failed {
        std::process::exit(1);
    }
}

/// Deterministic photo-range input: the same ramp `scripts/ml/onnx_parity.py` feeds.
///
/// A full-range sawtooth in [-0.5, 0.5), exercising every activation's range rather
/// than saturating the segmenters' sigmoids to one end. Free of transcendentals so
/// both sides of a parity comparison read the same bytes.
fn pattern(len: usize) -> Vec<f32> {
    (0..len).map(|i| (i as u64 * 37 % 255) as f32 / 255.0 - 0.5).collect()
}

/// A bright centred blob on a dark field — structurally nothing like [`pattern`].
///
/// Matches `nets::reference`'s `blob` so the response check compares the same pair.
/// Layout is channel-major `[c, h, w]`, the order every plan input uses.
fn blob(channels: usize, height: usize, width: usize) -> Vec<f32> {
    let mut values = Vec::with_capacity(channels * height * width);
    let (cy, cx) = (height as f32 / 2.0, width as f32 / 2.0);
    let radius = height.min(width) as f32 / 3.0;
    for c in 0..channels {
        for y in 0..height {
            for x in 0..width {
                let (dy, dx) = (y as f32 - cy, x as f32 - cx);
                let inside = (dy * dy + dx * dx).sqrt() < radius;
                values.push(if inside { 0.9 - c as f32 * 0.2 } else { 0.05 });
            }
        }
    }
    values
}

fn load(path: &str, graph_id: u32) -> Result<Weights, String> {
    let bytes = std::fs::read(path).map_err(|e| format!("cannot read {path}: {e}"))?;
    Weights::parse(&bytes, graph_id)
}

/// Time `passes` inferences after one warm-up, then print the kinds table and checksums.
///
/// Runs `input_b` once after the timed passes and reports the peak per-element
/// response between the two inputs. Segmentation masks are near-zero on synthetic
/// input even when correct — see `nets::reference::assert_usable_mask` — so the
/// response, not the checksum, is what distinguishes a net that answers from one
/// that cannot.
fn report(
    label: &str,
    net: &mut Net,
    input: &[f32],
    input_b: &[f32],
    passes: u32,
) -> Result<(), String> {
    let mut ms = Vec::new();
    let mut last: Vec<Vec<f32>> = Vec::new();
    net.infer_raw(input)?;
    for _ in 0..passes {
        let t = Instant::now();
        last = net.infer_raw(input)?;
        ms.push(since(t));
    }
    println!("infer     {}", stats(&ms));
    print_checksums(&last);
    let other = net.infer_raw(input_b)?;
    for (a, b) in last.iter().zip(other.iter()) {
        let response = a.iter().zip(b.iter()).map(|(x, y)| (x - y).abs()).fold(0.0f32, f32::max);
        println!("response  peak {response:.6}");
    }
    net.infer_raw(input)?;
    print_kinds(label, net);
    Ok(())
}

fn report_many(
    label: &str,
    net: &mut Net,
    inputs: &[&[f32]],
    passes: u32,
) -> Result<(), String> {
    let mut ms = Vec::new();
    net.infer_raw_many(inputs)?;
    for _ in 0..passes {
        let t = Instant::now();
        let out = net.infer_raw_many(inputs)?;
        ms.push(since(t));
        if ms.len() == passes as usize {
            print_checksums(&out);
        }
    }
    println!("infer     {}", stats(&ms));
    net.infer_raw_many(inputs)?;
    print_kinds(label, net);
    Ok(())
}

fn print_checksums(out: &[Vec<f32>]) {
    for (i, binding) in out.iter().enumerate() {
        let sum: f64 = binding.iter().map(|&v| f64::from(v)).sum();
        let max: f32 = binding.iter().fold(0.0f32, |a, &v| a.max(v.abs()));
        println!("output[{i}] len {:>8} checksum {sum:.6} maxabs {max:.6}", binding.len());
    }
}

fn profile_selfie(path: &str, passes: u32) -> Result<(), String> {
    let weights = load(path, graph::SELFIE)?;
    let context = context::shared()?;
    let started = Instant::now();
    let plan = selfie::build(&weights)?;
    let mut net = Net::new(context, plan, &weights, RESCALE_ONLY)?;
    println!("load      {:>8.1} ms (upload + first record)", since(started));
    let input = pattern(3 * selfie::SIZE as usize * selfie::SIZE as usize);
    let input_b = blob(3, selfie::SIZE as usize, selfie::SIZE as usize);
    report("selfie", &mut net, &input, &input_b, passes)
}

fn profile_modnet(path: &str, passes: u32) -> Result<(), String> {
    let weights = load(path, graph::MODNET)?;
    let context = context::shared()?;
    let started = Instant::now();
    let plan = modnet::build(&weights)?;
    let mut net = Net::new(context, plan, &weights, RESCALE_ONLY)?;
    println!("load      {:>8.1} ms (upload + first record)", since(started));
    let input = pattern(3 * modnet::SIZE as usize * modnet::SIZE as usize);
    let input_b = blob(3, modnet::SIZE as usize, modnet::SIZE as usize);
    report("modnet", &mut net, &input, &input_b, passes)
}

fn profile_u2netp(path: &str, passes: u32) -> Result<(), String> {
    let weights = load(path, graph::U2NETP)?;
    let context = context::shared()?;
    let started = Instant::now();
    let plan = u2netp::build(&weights)?;
    let mut net = Net::new(context, plan, &weights, RESCALE_ONLY)?;
    println!("load      {:>8.1} ms (upload + first record)", since(started));
    let input = pattern(3 * u2netp::SIZE as usize * u2netp::SIZE as usize);
    let input_b = blob(3, u2netp::SIZE as usize, u2netp::SIZE as usize);
    report("u2netp", &mut net, &input, &input_b, passes)
}

fn profile_scrfd(path: &str, passes: u32) -> Result<(), String> {
    let weights = load(path, graph::SCRFD)?;
    let context = context::shared()?;
    let started = Instant::now();
    // The bridge builds at the fixed LONG_SIDE square; profile that.
    let plan = scrfd::build(&weights, scrfd::LONG_SIDE, scrfd::LONG_SIDE)?;
    let mut net = Net::new(context, plan, &weights, RESCALE_ONLY)?;
    println!("load      {:>8.1} ms (upload + first record)", since(started));
    let side = scrfd::LONG_SIDE as usize;
    let input = pattern(3 * side * side);
    let input_b = blob(3, side, side);
    report("scrfd", &mut net, &input, &input_b, passes)
}

fn profile_mbf(path: &str, passes: u32) -> Result<(), String> {
    let weights = load(path, graph::MOBILEFACENET)?;
    let context = context::shared()?;
    let started = Instant::now();
    let plan = mobilefacenet::build(&weights)?;
    let mut net = Net::new(context, plan, &weights, RESCALE_ONLY)?;
    println!("load      {:>8.1} ms (upload + first record)", since(started));
    let input = pattern(3 * mobilefacenet::SIZE as usize * mobilefacenet::SIZE as usize);
    let input_b = blob(3, mobilefacenet::SIZE as usize, mobilefacenet::SIZE as usize);
    report("mbf", &mut net, &input, &input_b, passes)
}

fn profile_det(path: &str, passes: u32) -> Result<(), String> {
    let weights = load(path, graph::PPOCR_DET)?;
    let context = context::shared()?;
    let started = Instant::now();
    // The bridge builds at the fixed LONG_SIDE square; profile that.
    let plan = ppocr_det::build(&weights, ppocr_det::LONG_SIDE, ppocr_det::LONG_SIDE)?;
    let mut net = Net::new(context, plan, &weights, RESCALE_ONLY)?;
    println!("load      {:>8.1} ms (upload + first record)", since(started));
    let side = ppocr_det::LONG_SIDE as usize;
    let input = pattern(3 * side * side);
    let input_b = blob(3, side, side);
    report("det", &mut net, &input, &input_b, passes)
}

fn profile_rec(path: &str, passes: u32) -> Result<(), String> {
    let weights = load(path, graph::PPOCR_REC)?;
    let context = context::shared()?;
    let started = Instant::now();
    // The bridge builds at the fixed REC_WIDTH; profile that.
    let plan = ppocr_rec::build(&weights, REC_WIDTH)?;
    let mut net = Net::new(context, plan, &weights, PPOCR_REC)?;
    println!("load      {:>8.1} ms (upload + first record)", since(started));
    let input = pattern(3 * ppocr_rec::HEIGHT as usize * REC_WIDTH as usize);
    let input_b = blob(3, ppocr_rec::HEIGHT as usize, REC_WIDTH as usize);
    report("rec", &mut net, &input, &input_b, passes)
}

/// Eight lines one submit each against one batched submit; the logits must match bit for bit.
fn profile_rec_batch(path: &str, passes: u32) -> Result<(), String> {
    const LINES: u32 = 8;
    let weights = load(path, graph::PPOCR_REC)?;
    let context = context::shared()?;
    let mut single = Net::new(context.clone(), ppocr_rec::build(&weights, REC_WIDTH)?, &weights, PPOCR_REC)?;
    let mut batch =
        Net::new(context, ppocr_rec::build_batch(&weights, REC_WIDTH, LINES)?, &weights, PPOCR_REC)?;
    let len = ppocr_rec::HEIGHT as usize * REC_WIDTH as usize;
    let strips: Vec<Vec<i32>> = (0..LINES as usize)
        .map(|i| (0..len).map(|p| (0xff00_0000u32 | ((p * 37 + i * 101) as u32 * 2_654_435_761 >> 8)) as i32).collect())
        .collect();
    let shape = modelrunner::nets::Shape::new(3, ppocr_rec::HEIGHT, REC_WIDTH);
    let mut half = vec![0u16; shape.len() as usize];
    let mut inputs = Vec::new();
    for strip in &strips {
        to_planar_f16(strip, REC_WIDTH, ppocr_rec::HEIGHT, shape, &PPOCR_REC, &mut half)?;
        inputs.push(half.iter().map(|&h| f16_to_f32(h)).collect::<Vec<f32>>());
    }
    let refs: Vec<&[f32]> = inputs.iter().map(Vec::as_slice).collect();
    let (mut one, mut many) = (Vec::new(), Vec::new());
    let mut expected = Vec::new();
    for _ in 0..=passes {
        let t = Instant::now();
        expected = strips
            .iter()
            .map(|s| single.infer(s, REC_WIDTH, ppocr_rec::HEIGHT))
            .collect::<Result<Vec<_>, _>>()?;
        one.push(since(t));
        let t = Instant::now();
        let got = batch.infer_raw_many(&refs)?;
        many.push(since(t));
        if expected != got {
            return Err("a batched line's logits differ from its single run".into());
        }
    }
    println!("single    {} for {LINES} lines", stats(&one[1..]));
    println!("batched   {} for {LINES} lines (identical logits)", stats(&many[1..]));
    print_checksums(&expected);
    Ok(())
}

fn tinyclip_plan(offsets: &Offsets, mode: tinyclip::Mode) -> Result<Plan, String> {
    tinyclip::build(offsets, mode)
}

fn profile_clip_image(path: &str, passes: u32) -> Result<(), String> {
    let weights = load(path, graph::TINYCLIP)?;
    let context = context::shared()?;
    let started = Instant::now();
    let mut nets = Reshaped::new(context, &weights, tinyclip::Mode::Image, tinyclip_plan)?;
    println!("load      {:>8.1} ms (upload + first record)", since(started));
    let side = tinyclip::IMAGE_SIZE as usize;
    let input = pattern(3 * side * side);
    let input_b = blob(3, side, side);
    let net = nets.at(tinyclip::Mode::Image)?;
    report("clip_image", net, &input, &input_b, passes)
}

fn profile_clip_text(path: &str, passes: u32) -> Result<(), String> {
    let weights = load(path, graph::TINYCLIP)?;
    let context = context::shared()?;
    let started = Instant::now();
    // `<|startoftext|>`, three pieces, `<|endoftext|>`: a typical short query.
    let ids: Vec<u32> = vec![49406, 1234, 5678, 9012, 49407];
    let embedded = tinyclip::embed_positions(weights.reader(), &ids)?;
    let len = ids.len() as u32;
    let mut nets = Reshaped::new(context, &weights, tinyclip::Mode::Text { len }, tinyclip_plan)?;
    println!("load      {:>8.1} ms (upload + first record)", since(started));
    let net = nets.at(tinyclip::Mode::Text { len })?;
    report_many("clip_text", net, &[&embedded], passes)
}

fn profile_maia(path: &str, passes: u32) -> Result<(), String> {
    let weights = load(path, graph::MAIA)?;
    let context = context::shared()?;
    let started = Instant::now();
    let plan = maia::build(&weights)?;
    let mut net = Net::new(context, plan, &weights, RESCALE_ONLY)?;
    println!("load      {:>8.1} ms (upload + first record)", since(started));
    // A sparse opening-like board: a few pieces on their squares, both sides at 1500.
    let mut planes = vec![0.0f32; maia::PLANES as usize * maia::SQUARES as usize];
    for (plane, square) in [(5, 12), (5, 52), (4, 1), (4, 57), (11, 4), (11, 60)] {
        planes[plane * maia::SQUARES as usize + square] = 1.0;
    }
    let elo = maia::elo_embedding(weights.reader(), 1500.0)?;
    let tokens = maia::tokens(&planes, &elo, &elo)?;
    report_many("maia", &mut net, &[&tokens], passes)
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
    let top: usize = std::env::var("MODELRUNNER_TOP").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let mut slowest: Vec<_> = times.iter().collect();
    slowest.sort_by(|a, b| b.1.total_cmp(&a.1));
    for (name, us, _) in slowest.into_iter().take(top) {
        println!("  top {name:<30} {:>10.3} ms", us / 1e3);
    }
}

fn since(since: Instant) -> f64 {
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
