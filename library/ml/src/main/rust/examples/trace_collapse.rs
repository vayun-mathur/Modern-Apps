//! Find where the residual stream collapses to a constant: trace hidden after
//! each layer count and print mean/rms. A healthy transformer keeps rms ~O(1-10)
//! with structure; the collapse point is the layer whose output goes flat.
use std::path::PathBuf;
use std::sync::Arc;

use modelrunner::nets::gemma4;
use modelrunner::vulkan::context;
use modelrunner::vulkan::reshape::Reshaped;
use modelrunner::vulkan::run::StepParams;
use modelrunner::weights::{graph, Weights};

const TIER: u32 = gemma4::MAX_CONTEXT;
const TOKENS: [u32; 6] = [2, 818, 5279, 529, 7001, 563];

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

fn stats(tag: &str, v: &[f32]) {
    if v.is_empty() {
        println!("{tag}: EMPTY");
        return;
    }
    let n = v.len() as f64;
    let mean: f64 = v.iter().map(|x| f64::from(*x)).sum::<f64>() / n;
    let rms: f64 = (v.iter().map(|x| f64::from(*x) * f64::from(*x)).sum::<f64>() / n).sqrt();
    let finite = v.iter().all(|x| x.is_finite());
    // Flatness: fraction of channels within 1% of the mean abs.
    println!("{tag}: n={} mean={:.4} rms={:.4} finite={}", v.len(), mean, rms, finite);
}

fn main() {
    let mut args = std::env::args().skip(1);
    let (Some(text), Some(embed)) = (args.next().map(PathBuf::from), args.next().map(PathBuf::from))
    else {
        println!("usage: trace_collapse <text.maml> <embed.maml> [max_layers]");
        return;
    };
    let max_layers: usize = args.next().and_then(|s| s.parse().ok()).unwrap_or(gemma4::LAYERS as usize);
    let text_bytes = std::fs::read(&text).unwrap();
    let embed_bytes = std::fs::read(&embed).unwrap();
    let weights = Weights::parse(&text_bytes, graph::GEMMA4_TEXT).unwrap();
    let embed_weights = Weights::parse(&embed_bytes, graph::GEMMA4_EMBED).unwrap();
    let context = context::shared().unwrap();
    let reader = embed_weights.reader();
    let rotary = weights.reader();
    for layers in 0..=max_layers.min(gemma4::LAYERS as usize) {
        let mode = gemma4::Mode::Trace { layers }.at(TIER);
        let mut net = match Reshaped::new(
            Arc::clone(&context),
            &weights,
            mode,
            |offsets, mode| gemma4::build(offsets, mode),
        ) {
            Ok(net) => net,
            Err(why) => {
                println!("layers={layers}: build failed: {why}");
                continue;
            }
        };
        let mut last = (Vec::new(), Vec::new());
        let mut ok = true;
        for (step, &token) in TOKENS.iter().enumerate() {
            let position = step as u32;
            let gathered = gemma4::gather_input(&reader, token, gemma4::combines_in_plan(&weights));
            let (hidden_in, per_layer) = match gathered {
                Ok(g) => g,
                Err(why) => {
                    println!("layers={layers}: gather failed: {why}");
                    ok = false;
                    break;
                }
            };
            let angles_local = rotary_row(&rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, position);
            let angles_global =
                rotary_row(&rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, position);
            let at = match net.at(mode) {
                Ok(at) => at,
                Err(why) => {
                    println!("layers={layers}: plan failed: {why}");
                    ok = false;
                    break;
                }
            };
            if at
                .set_params(StepParams {
                    prefix: position,
                    window_start: position.saturating_sub(gemma4::WINDOW - 1),
                })
                .is_err()
            {
                println!("layers={layers}: params failed");
                ok = false;
                break;
            }
            match at.infer_raw_many(&[&hidden_in, &per_layer, &angles_local, &angles_global]) {
                Ok(out) => {
                    let mut it = out.into_iter();
                    last = (it.next().unwrap_or_default(), it.next().unwrap_or_default());
                }
                Err(why) => {
                    println!("layers={layers}: infer failed at step {step}: {why}");
                    ok = false;
                    break;
                }
            }
        }
        if ok {
            stats(&format!("layers={layers} hidden"), &last.0);
        }
    }
}
