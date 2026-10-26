//! Parses a converted Gemma 4 `.maml` and builds its decode plan against the real tensor table.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example probe_gemma4 -- <file.maml>
//! ```
//!
//! # What this checks that the layout tests cannot
//!
//! `nets::gemma4`'s tests assert the ordered table against `nets::tests::Shapes`, a stub that
//! hands back whatever index it is asked for. That catches a net walking its own span wrongly,
//! and cannot catch the net and the **converter** disagreeing - both sides would have to be wrong
//! in the same way, but nothing forces them to be right in the same way either.
//!
//! This resolves every tensor against a file `maml_convert.py` actually wrote: each `shaped` call
//! checks the requested dims against the table, so a transposed kernel, a scale of the wrong
//! rank, or a layer at the wrong offset is refused here rather than producing a plan that runs
//! and is wrong.
//!
//! It does **not** check the numbers. That needs a reference run, which needs the tokenizer and
//! the embedding export; see `check_nllb_parity.rs` for the shape that takes.
use std::path::PathBuf;
use modelrunner::nets::{gemma4, gemma4_head, gemma4_vision};
use modelrunner::weights::{graph, Blob, Weights};
/// The cache these examples record against: the top tier, so a long prompt fits.
const TIER: u32 = gemma4::MAX_CONTEXT;
fn main() {
    // Reads gemma4_embed.maml whole (3.6 GB of host RAM): an OOM kernel panic on a Pixel 8.
    if cfg!(target_os = "android") {
        println!("probe_gemma4 is host-only: it reads the 3.6 GB embed file whole");
        return;
    }
    let Some(path) = std::env::args().nth(1).map(PathBuf::from) else {
        println!("usage: probe_gemma4 <gemma4_text.maml> [gemma4_embed.maml]");
        return;
    };
    let bytes = match std::fs::read(&path) {
        Ok(bytes) => bytes,
        Err(why) => {
            println!("cannot read {}: {why}", path.display());
            return;
        }
    };
    println!("file      {}  {:.2} GB", path.display(), bytes.len() as f64 / 1e9);

    let weights = match Weights::parse(&bytes, graph::GEMMA4_TEXT) {
        Ok(weights) => weights,
        Err(why) => {
            println!("parse failed: {why}");
            return;
        }
    };
    println!("tensors   {} (the module expects {})", weights.tensors().len(), gemma4::TENSORS);
    println!("data      {:.2} GB", weights.data().len() as f64 / 1e9);
    if weights.tensors().len() != gemma4::TENSORS {
        println!("MISMATCH: the converter and nets::gemma4 disagree about the table's length");
        return;
    }

    match gemma4::build(&weights, gemma4::Mode::DecodeStep.at(TIER)) {
        Ok(plan) => {
            println!();
            println!("the decode plan builds against the real table");
            println!("  {} ops", plan.ops.len());
            println!("  {} inputs, {} outputs", plan.inputs.len(), plan.outputs.len());
            println!("  {:.1} MB of arena", f64::from(plan.arena_elems) * 2.0 / 1e6);
            let classes: u32 = plan.outputs.iter().map(|b| b.shape.c).sum();
            println!("  {classes} logits over {} splits", plan.outputs.len());
            {
                use std::collections::BTreeMap;
                let mut tally: BTreeMap<String, usize> = BTreeMap::new();
                for op in &plan.ops {
                    let name = match op {
                        modelrunner::nets::Op::Dispatch { kind, .. } => format!("{kind:?}"),
                        modelrunner::nets::Op::Copy { .. } => "Copy".to_string(),
                    };
                    *tally.entry(name).or_default() += 1;
                }
                let mut rows: Vec<_> = tally.into_iter().collect();
                rows.sort_by_key(|(_, n)| std::cmp::Reverse(*n));
                println!("  ops by kind, {} total:", plan.ops.len());
                for (name, n) in rows {
                    println!("    {n:5}  {:6.2} per layer  {name}", n as f64 / 35.0);
                }
            }
            println!("  {} pinned", plan.pinned.len());
            for (i, b) in plan.pinned.iter().take(3).enumerate() {
                println!("    pinned {i}: c={} h={} w={}", b.shape.c, b.shape.h, b.shape.w);
            }
        }
        Err(why) => println!("the decode plan does NOT build: {why}"),
    }

    // The embedding, if it is beside the text model. Its own file and its own graph id.
    let Some(embed_path) = std::env::args().nth(2).map(PathBuf::from) else {
        println!();
        println!("pass the embedding .maml as a second argument to exercise the host gather");
        return;
    };
    let embed_bytes = match std::fs::read(&embed_path) {
        Ok(bytes) => bytes,
        Err(why) => {
            println!("cannot read {}: {why}", embed_path.display());
            return;
        }
    };
    let embed = match Weights::parse(&embed_bytes, graph::GEMMA4_EMBED) {
        Ok(weights) => weights,
        Err(why) => {
            println!("the embedding does not parse: {why}");
            return;
        }
    };
    println!();
    println!("embedding {}  {:.2} GB", embed_path.display(), embed_bytes.len() as f64 / 1e9);
    println!(
        "tensors   {} (fp16 chunks: {} / int8 chunks: {})",
        embed.tensors().len(),
        gemma4_head::TENSORS_WITH_HEAD,
        gemma4_head::TENSORS_WITH_HEAD8,
    );
    match gemma4_head::build_plan(&embed) {
        Ok(plan) => {
            println!("the GPU head plan builds against the real table");
            println!("  {} ops, {} inputs, {} outputs", plan.ops.len(), plan.inputs.len(), plan.outputs.len());
            let classes: u32 = plan.outputs.iter().map(|b| b.shape.c).sum();
            println!("  {classes} logits over {} splits", plan.outputs.len());
            println!("  {:.1} MB of arena", f64::from(plan.arena_elems) * 2.0 / 1e6);
        }
        Err(why) => println!("the GPU head plan does NOT build: {why}"),
    }
    match gemma4_head::build_plan_greedy(&embed) {
        Ok(plan) => {
            println!("the greedy head plan builds against the real table");
            println!("  {} ops, {} inputs, {} outputs", plan.ops.len(), plan.inputs.len(), plan.outputs.len());
            for (i, o) in plan.outputs.iter().enumerate() {
                println!("  out[{i}]: c={} h={} w={}", o.shape.c, o.shape.h, o.shape.w);
            }
        }
        Err(why) => println!("the greedy head plan does NOT build: {why}"),
    }

    // The vision tower, if it is beside the other two.
    if let Some(path) = std::env::args().nth(3).map(PathBuf::from) {
        // A square image, which is the grid the reference resize picks for a 1:1 aspect ratio.
        let grid = match gemma4_vision::Grid::for_image(
            1024,
            1024,
            gemma4_vision::DEFAULT_SOFT_TOKENS,
        ) {
            Ok(grid) => grid,
            Err(why) => {
                println!("no grid for a square image: {why}");
                return;
            }
        };
        match std::fs::read(&path).ok().and_then(|bytes| {
            Weights::parse(&bytes, graph::GEMMA4_VISION).ok().map(|w| {
                let tensors = w.tensors().len();
                let plan = gemma4_vision::build(&w, gemma4_vision::Mode::Image(grid));
                (tensors, plan.map(|p| (p.ops.len(), p.inputs.len(), p.outputs.len(), p.arena_elems)))
            })
        }) {
            Some((tensors, plan)) => {
                println!();
                println!("vision    {}", path.display());
                println!("tensors   {tensors} (the module expects {})", gemma4_vision::TENSORS);
                println!(
                    "grid      {}x{} patches -> {} soft tokens",
                    grid.rows,
                    grid.cols,
                    grid.soft_tokens()
                );
                match plan {
                    Ok((ops, ins, outs, arena)) => {
                        println!("  the image plan builds against the real table");
                        println!("  {ops} ops, {ins} inputs, {outs} outputs");
                        println!("  {:.1} MB of arena", f64::from(arena) * 2.0 / 1e6);
                    }
                    Err(why) => println!("  the image plan does NOT build: {why}"),
                }
            }
            None => println!("the vision file does not parse"),
        }
    }

    let reader = embed.reader();
    // `<bos>` is 2, and 105 is an ordinary piece. A placeholder is included because it takes the
    // masked path, which is the one a gather written from the shapes alone would get wrong.
    for token in [2u32, 105, gemma4::embed::PLACEHOLDERS[0]] {
        match gemma4::gather_input(&reader, token, gemma4::combines_in_plan(&weights)) {
            Ok((hidden, per_layer)) => {
                let rms = |v: &[f32]| (v.iter().map(|x| x * x).sum::<f32>() / v.len() as f32).sqrt();
                println!(
                    "  token {token:>6}: embedding {} values rms {:.4}, per-layer {} values \
                     rms {:.4}",
                    hidden.len(),
                    rms(&hidden),
                    per_layer.len(),
                    rms(&per_layer)
                );
            }
            Err(why) => println!("  token {token}: {why}"),
        }
    }
}
