//! Dump the decode step's final hidden state for one fixed prompt, as raw fp32.
//!
//! `cargo run --offline --release -p modelrunner --example dump_gemma4_hidden -- \
//!     gemma4_text.maml gemma4_embed.maml out.bin`
//!
//! Scratch bisect aid: writes 1536 fp32 LE values (the normed hidden state the
//! tied head consumes). Compare against a numpy ideal-forward to localise a
//! composition error the argmax gate already caught.
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

fn main() {
    // Reads gemma4_embed.maml whole (3.6 GB of host RAM): an OOM kernel panic on a Pixel 8.
    if cfg!(target_os = "android") {
        println!("dump_gemma4_hidden is host-only: it reads the 3.6 GB embed file whole");
        return;
    }
    let mut args = std::env::args().skip(1);
    let (Some(text), Some(embed), Some(out)) = (args.next().map(PathBuf::from), args.next().map(PathBuf::from), args.next())
    else {
        println!("usage: dump_gemma4_hidden <text.maml> <embed.maml> <out.bin>");
        return;
    };
    let text_bytes = std::fs::read(&text).unwrap();
    let embed_bytes = std::fs::read(&embed).unwrap();
    let weights = Weights::parse(&text_bytes, graph::GEMMA4_TEXT).unwrap();
    let embed_weights = Weights::parse(&embed_bytes, graph::GEMMA4_EMBED).unwrap();
    let context = context::shared().unwrap();
    let mut net = Reshaped::new(
        Arc::clone(&context),
        &weights,
        gemma4::Mode::DecodeStep.at(TIER),
        |offsets, mode| gemma4::build(offsets, mode),
    )
    .unwrap();
    let reader = embed_weights.reader();
    let rotary = weights.reader();
    let mut hidden = Vec::new();
    for (step, &token) in TOKENS.iter().enumerate() {
        let position = step as u32;
        let (hidden_in, per_layer) = gemma4::gather_input(&reader, token, gemma4::combines_in_plan(&weights)).unwrap();
        let angles_local = rotary_row(&rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, position);
        let angles_global =
            rotary_row(&rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, position);
        let at = net.at(gemma4::Mode::DecodeStep.at(TIER)).unwrap();
        at.set_params(StepParams {
            prefix: position,
            window_start: position.saturating_sub(gemma4::WINDOW - 1),
        })
        .unwrap();
        let out = at.infer_raw_many(&[&hidden_in, &per_layer, &angles_local, &angles_global]).unwrap();
        hidden = out.into_iter().next().unwrap_or_default();
    }
    println!("hidden len {} rms {:.4}", hidden.len(), (hidden.iter().map(|x| x * x).sum::<f32>() / hidden.len() as f32).sqrt());
    let bytes: Vec<u8> = hidden.iter().flat_map(|v| v.to_le_bytes()).collect();
    std::fs::write(&out, bytes).unwrap();
    println!("wrote {out}");
}
