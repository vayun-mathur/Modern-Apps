//! Dump device block outputs after each of the first N layers (Trace mode).
//!
//! Drives Trace{layers} for layers=1..N on the golden tokens and writes the
//! last step's hidden state after each layer as raw fp32 LE:
//! `dev_layers/layer{L}.bin` (1536 floats). Compare vs the numpy replicate.
use std::path::PathBuf;
use std::sync::Arc;

use modelrunner::nets::gemma4;
use modelrunner::vulkan::context;
use modelrunner::vulkan::reshape::Reshaped;
use modelrunner::vulkan::run::StepParams;
use modelrunner::weights::{graph, Weights};

const TIER: u32 = gemma4::MAX_CONTEXT;
const TOKENS: [u32; 6] = [2, 818, 5279, 529, 7001, 563];
const LAYERS: usize = 15;

/// Dump one layer's last-step hidden state, or every position's when `DUMP_ALL`.
fn dump_all() -> bool {
    std::env::var("DUMP_ALL_POS").map(|v| v == "1").unwrap_or(false)
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
    let mut args = std::env::args().skip(1);
    let (Some(text), Some(embed), Some(outdir)) =
        (args.next().map(PathBuf::from), args.next().map(PathBuf::from), args.next())
    else {
        println!("usage: dump_dev_layers <text.maml> <embed.maml> <outdir>");
        return;
    };
    let text_bytes = std::fs::read(&text).unwrap();
    let embed_bytes = std::fs::read(&embed).unwrap();
    let weights = Weights::parse(&text_bytes, graph::GEMMA4_TEXT).unwrap();
    let embed_weights = Weights::parse(&embed_bytes, graph::GEMMA4_EMBED).unwrap();
    let context = context::shared().unwrap();
    std::fs::create_dir_all(&outdir).unwrap();
    for layers in 1..=LAYERS {
        let mode = gemma4::Mode::Trace { layers }.at(TIER);
        let mut net = Reshaped::new(
            Arc::clone(&context),
            &weights,
            mode,
            |offsets, mode| gemma4::build(offsets, mode),
        )
        .unwrap();
        let reader = embed_weights.reader();
        let rotary = weights.reader();
        let mut last = Vec::new();
        let mut all = Vec::new();
        let want_all = dump_all();
        for (step, &token) in TOKENS.iter().enumerate() {
            let position = step as u32;
            let (hidden, per_layer) = gemma4::gather_input(&reader, token, gemma4::combines_in_plan(&weights)).unwrap();
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
            let out = at
                .infer_raw_many(&[&hidden, &per_layer, &angles_local, &angles_global])
                .unwrap();
            let mut it = out.into_iter();
            last = it.next().unwrap_or_default();
            if want_all {
                all.push(last.clone());
            }
        }
        if want_all {
            let flat: Vec<f32> = all.into_iter().flatten().collect();
            let bytes: Vec<u8> = flat.iter().flat_map(|v| v.to_le_bytes()).collect();
            std::fs::write(format!("{outdir}/layer{}_all.bin", layers - 1), bytes).unwrap();
        }
        let bytes: Vec<u8> = last.iter().flat_map(|v| v.to_le_bytes()).collect();
        std::fs::write(format!("{outdir}/layer{}.bin", layers - 1), bytes).unwrap();
        println!("layer {} hidden len {}", layers - 1, last.len());
    }
}
