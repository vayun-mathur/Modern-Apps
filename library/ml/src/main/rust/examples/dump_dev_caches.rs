//! Export the KV caches after feeding the golden tokens, one layer at a time.
//!
//! Drives the decode net for TOKENS (prefill-style, position by position) and
//! writes each owning layer's K and V cache rows for the fed positions as raw
//! fp16 LE: `dev_caches/layer{L}_{k,v}.bin`, each `[positions, head_dim]`.
//! Compare against analysis/prove_sign/ref_caches/layer{L}_{k,v}.npy (int8,
//! [1,1,32003,head_dim] K / [1,1,head_dim,32003] V) after dequantising.
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
    let mut args = std::env::args().skip(1);
    let (Some(text), Some(embed), Some(outdir)) =
        (args.next().map(PathBuf::from), args.next().map(PathBuf::from), args.next())
    else {
        println!("usage: dump_dev_caches <text.maml> <embed.maml> <outdir>");
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
        at.infer_raw_many(&[&hidden_in, &per_layer, &angles_local, &angles_global]).unwrap();
    }
    // Pinned layout: CACHE_TENSORS K/V pairs per owning layer. Read back via
    // export_pinned and split per the cache shapes in gemma4.rs.
    let at = net.at(gemma4::Mode::DecodeStep.at(TIER)).unwrap();
    let bytes = at
        .export_pinned(gemma4::CACHE_TENSORS, TOKENS.len() as u32)
        .unwrap();
    std::fs::create_dir_all(&outdir).unwrap();
    // Walk the pinned list in order; each cache contributes positions*width fp16.
    // Widths: layer i owns dim(i) head-dim (256 sliding, 512 full), K then V.
    let mut at_byte = 0usize;
    let mut manifest = String::new();
    for layer in 0..gemma4::OWNS_CACHE_LAYERS {
        let dim = gemma4::head_dim(layer) as usize;
        for kind in ["k", "v"] {
            let count = TOKENS.len() * dim;
            let span = count * 2;
            let chunk = &bytes[at_byte..at_byte + span];
            let path = format!("{outdir}/layer{layer}_{kind}.bin");
            std::fs::write(&path, chunk).unwrap();
            manifest.push_str(&format!("layer{layer}_{kind} dim={dim}\n"));
            at_byte += span;
        }
    }
    std::fs::write(format!("{outdir}/manifest.txt"), manifest).unwrap();
    println!("exported {at_byte} bytes of {len} total", len = bytes.len());
}
