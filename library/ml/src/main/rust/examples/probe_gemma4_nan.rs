//! Stage-by-stage finiteness probe for the Gemma 4 text tower.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example probe_gemma4_nan -- \
//!     gemma4_text.maml gemma4_embed.maml
//! ```
//!
//! Runs the 6-token parity prompt, printing min/max/NaN-count of the hidden
//! state after 0, 1, 2, 4, 8, 16, 35 layers plus the gather outputs. The
//! first stage that goes non-finite localises the fault.
use std::path::PathBuf;
use std::sync::Arc;

use modelrunner::nets::gemma4;
use modelrunner::vulkan::context;
use modelrunner::vulkan::reshape::Reshaped;
use modelrunner::vulkan::run::StepParams;
use modelrunner::weights::{graph, Weights};

const TIER: u32 = gemma4::MAX_CONTEXT;
const TOKENS: [u32; 6] = [2, 818, 5279, 529, 7001, 563];

fn stats(label: &str, v: &[f32]) {
    let nans = v.iter().filter(|x| !x.is_finite()).count();
    let finite: Vec<f32> = v.iter().copied().filter(|x| x.is_finite()).collect();
    let (lo, hi) = if finite.is_empty() {
        (f32::NAN, f32::NAN)
    } else {
        (
            finite.iter().copied().fold(f32::INFINITY, f32::min),
            finite.iter().copied().fold(f32::NEG_INFINITY, f32::max),
        )
    };
    println!(
        "  {label:<14} len {:>6}  min {lo:>12.4}  max {hi:>12.4}  nonfinite {nans}",
        v.len()
    );
}

fn rotary_row(
    reader: &modelrunner::weights::Reader<'_>,
    index: usize,
    width: u32,
    position: u32,
) -> Vec<f32> {
    let all = reader.fp16(index, &[gemma4::MAX_CONTEXT, width]).unwrap();
    let from = (position * width) as usize;
    all[from..from + width as usize].to_vec()
}

fn main() {
    // Reads gemma4_embed.maml whole (3.6 GB of host RAM): an OOM kernel panic on a Pixel 8.
    if cfg!(target_os = "android") {
        println!("probe_gemma4_nan is host-only: it reads the 3.6 GB embed file whole");
        return;
    }
    let mut args = std::env::args().skip(1);
    let (Some(text), Some(embed)) = (args.next().map(PathBuf::from), args.next().map(PathBuf::from))
    else {
        println!("usage: probe_gemma4_nan <text.maml> <embed.maml>");
        return;
    };
    let text_bytes = std::fs::read(&text).unwrap();
    let embed_bytes = std::fs::read(&embed).unwrap();
    let weights = Weights::parse(&text_bytes, graph::GEMMA4_TEXT).unwrap();
    let embed_weights = Weights::parse(&embed_bytes, graph::GEMMA4_EMBED).unwrap();
    let context = context::shared().unwrap();

    let reader = embed_weights.reader();
    let rotary = weights.reader();

    // Gather outputs for the last prompt token, as the parity harness feeds them.
    let (hidden_in, per_layer) = gemma4::gather_input(&reader, *TOKENS.last().unwrap(), gemma4::combines_in_plan(&weights)).unwrap();
    stats("gather hidden", &hidden_in);
    stats("gather per_layer", &per_layer);

    for layers in [0usize, 1, 2, 4, 8, 16, 35] {
        let mode = if layers == 35 {
            gemma4::Mode::DecodeStep.at(TIER)
        } else {
            gemma4::Mode::Trace { layers }.at(TIER)
        };
        let mut net = match Reshaped::new(Arc::clone(&context), &weights, mode, |offsets, mode| {
            gemma4::build(offsets, mode)
        }) {
            Ok(net) => net,
            Err(why) => {
                println!("  layers={layers:<3} build failed: {why}");
                continue;
            }
        };
        let mut last = (Vec::new(), Vec::new());
        let mut failed = None;
        for (step, &token) in TOKENS.iter().enumerate() {
            let position = step as u32;
            let (hidden_in, per_layer) = gemma4::gather_input(&reader, token, gemma4::combines_in_plan(&weights)).unwrap();
            let angles_local =
                rotary_row(&rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, position);
            let angles_global =
                rotary_row(&rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, position);
            let at = net.at(mode).unwrap();
            at.set_params(StepParams {
                prefix: position,
                window_start: position.saturating_sub(gemma4::WINDOW - 1),
            })
            .unwrap();
            match at.infer_raw_many(&[&hidden_in, &per_layer, &angles_local, &angles_global]) {
                Ok(out) => {
                    let mut it = out.into_iter();
                    last = (it.next().unwrap_or_default(), it.next().unwrap_or_default());
                }
                Err(why) => {
                    failed = Some(why);
                    break;
                }
            }
        }
        if let Some(why) = failed {
            println!("  layers={layers:<3} infer failed: {why}");
            continue;
        }
        let label = format!("layers={layers}");
        if layers == 35 {
            stats(&label, &last.0);
        } else {
            stats(&label, &last.0);
            if last.1.len() > 1 {
                stats("  +per_layer", &last.1);
            }
        }
    }
}
