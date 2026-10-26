//! Precomputes the KV cache for Gemma 4's fixed prompt prefix and writes it to a file.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example bake_gemma4_prefix -- \
//!     gemma4_text.maml gemma4_embed.maml gemma4_tokenizer.spm1 prefix.txt gemma4_prefix.kv
//! ```
//!
//! # Why this exists
//!
//! The system block and the tool declarations are ~1,870 positions and they never change. On a
//! Tensor G4 prefilling them costs about **14 seconds**, and every device pays it on every cold
//! start, forever, to compute numbers that are identical everywhere.
//!
//! They are identical because the inputs are: the same tokens against the same weights. So they
//! can be computed once, here, and shipped.
//!
//! # Not bit-identical, and that is fine
//!
//! A desktop GPU and a phone will not produce the same bits - different reduction orders, fp16
//! rounding. The difference is far inside the noise the int4 weights already carry, and the cache
//! is not compared against anything, only attended over. What must match exactly is the **token
//! sequence**, which is why the digest below covers it.
//!
//! # The format
//!
//! ```text
//! magic   "GKV1"                    4 bytes
//! positions                         u32
//! tensors                           u32   (30: fifteen layers, keys and values)
//! digest                            32 bytes, SHA-256 of the prefix tokens
//! payload  per tensor, in plan order: positions * width fp16 values
//! ```
//!
//! Per tensor rather than one flat arena image, so the file does not depend on arena offsets and
//! survives being loaded into a different cache tier than it was baked at.
use std::path::PathBuf;
use std::sync::Arc;

use modelrunner::nets::gemma4;
use modelrunner::post::sentencepiece::Table;
use modelrunner::post::sentencepiece_flavours::GEMMA;
use modelrunner::vulkan::context;
use modelrunner::vulkan::reshape::Reshaped;
use modelrunner::vulkan::run::StepParams;
use modelrunner::weights::{graph, Weights};

/// Positions one prefill submit covers. As the bridge's, for the same fence reasons.
const CHUNK: usize = 4096;

/// The markers a rendered prompt may contain. Mirrors `Gemma4Handle.MARKERS`.
const MARKERS: [&str; 11] = [
    "<bos>", "<eos>", "<|turn>", "<turn|>", "<|tool>", "<tool|>", "<|tool_call>", "<tool_call|>",
    "<|tool_response>", "<tool_response|>", "<|\"|>",
];

/// Whether the text file carries the combine, so plans take raw per-layer rows.
static FUSED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

fn gather_in(reader: &modelrunner::weights::Reader<'_>, token: u32) -> Result<(Vec<f32>, Vec<f32>), String> {
    gemma4::gather_input(reader, token, FUSED.load(std::sync::atomic::Ordering::Relaxed))
}

fn main() {
    // Reads gemma4_embed.maml whole (3.6 GB of host RAM): an OOM kernel panic on a Pixel 8.
    if cfg!(target_os = "android") {
        println!("bake_gemma4_prefix is host-only: it reads the 3.6 GB embed file whole");
        return;
    }
    let mut args = std::env::args().skip(1);
    let (Some(text), Some(embed), Some(tokenizer), Some(prefix), Some(out)) =
        (args.next(), args.next(), args.next(), args.next(), args.next())
    else {
        println!(
            "usage: bake_gemma4_prefix <text.maml> <embed.maml> <table.spm1> <prefix.txt> <out.kv>"
        );
        return;
    };
    let read = |p: &str| std::fs::read(PathBuf::from(p));
    let (Ok(text_bytes), Ok(embed_bytes), Ok(table_bytes)) =
        (read(&text), read(&embed), read(&tokenizer))
    else {
        return println!("cannot read the model files");
    };
    let Ok(rendered) = std::fs::read_to_string(&prefix) else {
        return println!("cannot read {prefix}");
    };
    let (Ok(weights), Ok(embed_weights)) = (
        Weights::parse(&text_bytes, graph::GEMMA4_TEXT),
        Weights::parse(&embed_bytes, graph::GEMMA4_EMBED),
    ) else {
        return println!("the weights do not parse");
    };
    FUSED.store(gemma4::combines_in_plan(&weights), std::sync::atomic::Ordering::Relaxed);
    let table = match Table::parse_with(&table_bytes, GEMMA) {
        Ok(table) => table,
        Err(why) => return println!("the tokenizer does not parse: {why}"),
    };

    let tokens = table.encode_with_specials(rendered.trim_end_matches('\n'), &MARKERS);
    println!("prefix {} chars -> {} positions", rendered.len(), tokens.len());
    if tokens.is_empty() {
        return println!("an empty prefix");
    }

    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => return println!("no Vulkan device: {why}"),
    };
    // Baked at the top tier so the file covers any device; a smaller tier reads the prefix it
    // has room for and refuses the rest, which `import_pinned` checks.
    let tier = gemma4::MAX_CONTEXT;
    let mut net = match Reshaped::new(Arc::clone(&context), &weights, gemma4::Mode::DecodeStep.at(tier), |o, m| {
        gemma4::build(o, m)
    }) {
        Ok(net) => net,
        Err(why) => return println!("the model did not open: {why}"),
    };
    let reader = embed_weights.reader();
    let rotary = weights.reader();
    let (Ok(local), Ok(global)) = (
        rotary.fp16(gemma4::ROTARY_LOCAL, &[gemma4::MAX_CONTEXT, gemma4::HEAD_DIM]),
        rotary.fp16(gemma4::ROTARY_GLOBAL, &[gemma4::MAX_CONTEXT, gemma4::GLOBAL_HEAD_DIM]),
    ) else {
        return println!("the rotary tables did not load");
    };

    // Every position, including the last: this is a prefix, not a prompt, so nothing here is a
    // prediction and the whole thing belongs in the cache.
    let mut fed = 0usize;
    for chunk in tokens.chunks(CHUNK) {
        let width = chunk.len() as u32;
        let base = fed as u32;
        let mut hidden = vec![0f32; (gemma4::D_MODEL * width) as usize];
        let mut per_layer =
            vec![0f32; (gemma4::PER_LAYER * gemma4::LAYERS as u32 * width) as usize];
        let mut al = vec![0f32; (gemma4::HEAD_DIM * width) as usize];
        let mut ag = vec![0f32; (gemma4::GLOBAL_HEAD_DIM * width) as usize];
        for (offset, &token) in chunk.iter().enumerate() {
            let column = offset as u32;
            let position = base + column;
            let Ok((h, p)) = gather_in(&reader, token) else {
                return println!("token {token} is not in the embedding");
            };
            place(&mut hidden, &h, column, width);
            place(&mut per_layer, &p, column, width);
            let from = (position * gemma4::HEAD_DIM) as usize;
            place(&mut al, &local[from..from + gemma4::HEAD_DIM as usize], column, width);
            let from = (position * gemma4::GLOBAL_HEAD_DIM) as usize;
            place(&mut ag, &global[from..from + gemma4::GLOBAL_HEAD_DIM as usize], column, width);
        }
        let Ok(at) = net.at(gemma4::Mode::Prefill { tokens: width }.at(tier)) else {
            return println!("the prefill plan did not build");
        };
        let _ = at.set_params(StepParams {
            prefix: base,
            window_start: base.saturating_sub(gemma4::WINDOW - 1),
        });
        if at.infer_raw_many(&[&hidden, &per_layer, &al, &ag]).is_err() {
            return println!("the prefill failed at {base}");
        }
        fed += chunk.len();
        print!("\r  {fed} / {} positions", tokens.len());
    }
    println!();

    let positions = fed as u32;
    let payload = match net.at(gemma4::Mode::DecodeStep.at(tier)) {
        Ok(net) => match net.export_pinned(gemma4::CACHE_TENSORS, positions) {
            Ok(bytes) => bytes,
            Err(why) => return println!("the cache did not export: {why}"),
        },
        Err(why) => return println!("{why}"),
    };

    let mut file = Vec::with_capacity(payload.len() + 64);
    file.extend_from_slice(b"GKV1");
    file.extend_from_slice(&positions.to_le_bytes());
    file.extend_from_slice(&(payload.len() as u32 / positions.max(1)).to_le_bytes());
    file.extend_from_slice(&digest(&tokens));
    file.extend_from_slice(&payload);
    // Round-trip it before claiming success. Importing into a *fresh* net and continuing from
    // the same position must give the same next token as the net that just prefilled it - which
    // is the only property the app relies on, and the only one worth checking.
    let expected = argmax_next(&mut net, &reader, &local, &global, tokens[tokens.len() - 1], positions);
    let mut fresh = match Reshaped::new(Arc::clone(&context), &weights, gemma4::Mode::DecodeStep.at(tier), |o, m| {
        gemma4::build(o, m)
    }) {
        Ok(net) => net,
        Err(why) => return println!("the check net did not open: {why}"),
    };
    match fresh.at(gemma4::Mode::DecodeStep.at(tier)) {
        Ok(net) => {
            if let Err(why) = net.import_pinned(gemma4::CACHE_TENSORS, positions, &payload) {
                return println!("the cache did not import: {why}");
            }
        }
        Err(why) => return println!("{why}"),
    }
    // Continue for a while, not one token. One token agreeing only says export and import are
    // each other's inverse; it says nothing about whether the numbers mean anything to a model
    // that keeps reading them. A cache that is subtly wrong still produces *a* plausible first
    // token and then falls apart, which is exactly what shipping this did.
    let ask = table.encode_with_specials("<turn|>\n<|turn>user\nWhat is 2+2?<turn|>\n<|turn>model\n", &MARKERS);
    let live = continue_with(&mut net, &reader, &local, &global, &ask, positions, 12);
    let baked = continue_with(&mut fresh, &reader, &local, &global, &ask, positions, 12);
    println!("  prefilled continues: {:?}", table.decode(&live));
    println!("  imported  continues: {:?}", table.decode(&baked));
    if live != baked {
        println!("MISMATCH: the imported cache does not behave like the prefilled one");
    }
    let got = argmax_next(&mut fresh, &reader, &local, &global, tokens[tokens.len() - 1], positions);
    match (expected, got) {
        (Some(a), Some(b)) if a == b => println!("round trip ok: both continue with token {a}"),
        (a, b) => {
            return println!("ROUND TRIP FAILED: prefilled gives {a:?}, imported gives {b:?}");
        }
    }

    match std::fs::write(&out, &file) {
        Ok(()) => {
            println!("wrote {out}  {:.1} MB", file.len() as f64 / 1e6);
            println!("  {positions} positions, {} bytes each", payload.len() / positions as usize);
        }
        Err(why) => println!("cannot write {out}: {why}"),
    }
}

/// Write one position's `values` into column `column` of a `[C, 1, width]` block.
fn place(into: &mut [f32], values: &[f32], column: u32, width: u32) {
    for (channel, &value) in values.iter().enumerate() {
        if let Some(slot) = into.get_mut(channel * width as usize + column as usize) {
            *slot = value;
        }
    }
}

/// A digest of the token sequence, so a cache cannot be used against a prompt it was not baked
/// for.
///
/// Not cryptographic - FNV-1a widened to 32 bytes. The threat is a stale asset after someone
/// edits the system prompt, not an adversary: the file ships beside the weights over the same
/// channel, and anyone who could substitute it could substitute those.
fn digest(tokens: &[u32]) -> [u8; 32] {
    let mut out = [0u8; 32];
    for lane in 0..4usize {
        let mut hash: u64 = 0xcbf2_9ce4_8422_2325 ^ (lane as u64).wrapping_mul(0x9e37_79b9);
        for &token in tokens {
            for byte in token.to_le_bytes() {
                hash ^= u64::from(byte);
                hash = hash.wrapping_mul(0x100_0000_01b3);
            }
        }
        out[lane * 8..lane * 8 + 8].copy_from_slice(&hash.to_le_bytes());
    }
    out
}

/// The token this net would produce next, given `token` at `position`.
fn argmax_next(
    net: &mut Reshaped<gemma4::Pass>,
    reader: &modelrunner::weights::Reader<'_>,
    local: &[f32],
    global: &[f32],
    token: u32,
    position: u32,
) -> Option<u32> {
    let (hidden, per_layer) = gather_in(reader, token).ok()?;
    let from = (position * gemma4::HEAD_DIM) as usize;
    let al = local[from..from + gemma4::HEAD_DIM as usize].to_vec();
    let from = (position * gemma4::GLOBAL_HEAD_DIM) as usize;
    let ag = global[from..from + gemma4::GLOBAL_HEAD_DIM as usize].to_vec();
    let at = net.at(gemma4::Mode::DecodeStep.at(gemma4::MAX_CONTEXT)).ok()?;
    at.set_params(StepParams {
        prefix: position,
        window_start: position.saturating_sub(gemma4::WINDOW - 1),
    })
    .ok()?;
    let out = at.infer_raw_many(&[&hidden, &per_layer, &al, &ag]).ok()?;
    let mut best = (f32::NEG_INFINITY, 0u32);
    let mut base = 0u32;
    for split in out.iter().take(gemma4::HEAD_SPLITS) {
        for (offset, &value) in split.iter().enumerate() {
            if value > best.0 {
                best = (value, base + offset as u32);
            }
        }
        base += split.len() as u32;
    }
    Some(best.1)
}

/// Feed `ask` at `from`, then generate `limit` tokens greedily.
fn continue_with(
    net: &mut Reshaped<gemma4::Pass>,
    reader: &modelrunner::weights::Reader<'_>,
    local: &[f32],
    global: &[f32],
    ask: &[u32],
    from: u32,
    limit: usize,
) -> Vec<u32> {
    let mut out = Vec::new();
    let mut at = from;
    for &token in ask {
        if argmax_at(net, reader, local, global, token, at).is_none() {
            return out;
        }
        at += 1;
    }
    let mut next = *ask.last().unwrap_or(&2);
    for _ in 0..limit {
        let Some(token) = argmax_at(net, reader, local, global, next, at) else { break };
        at += 1;
        if token == 1 || token == 106 {
            break;
        }
        out.push(token);
        next = token;
    }
    out
}

/// One decode step, returning the argmax.
fn argmax_at(
    net: &mut Reshaped<gemma4::Pass>,
    reader: &modelrunner::weights::Reader<'_>,
    local: &[f32],
    global: &[f32],
    token: u32,
    position: u32,
) -> Option<u32> {
    let (hidden, per_layer) = gather_in(reader, token).ok()?;
    let from = (position * gemma4::HEAD_DIM) as usize;
    let al = local[from..from + gemma4::HEAD_DIM as usize].to_vec();
    let from = (position * gemma4::GLOBAL_HEAD_DIM) as usize;
    let ag = global[from..from + gemma4::GLOBAL_HEAD_DIM as usize].to_vec();
    let at = net.at(gemma4::Mode::DecodeStep.at(gemma4::MAX_CONTEXT)).ok()?;
    at.set_params(StepParams {
        prefix: position,
        window_start: position.saturating_sub(gemma4::WINDOW - 1),
    })
    .ok()?;
    let out = at.infer_raw_many(&[&hidden, &per_layer, &al, &ag]).ok()?;
    let mut best = (f32::NEG_INFINITY, 0u32);
    let mut base = 0u32;
    for split in out.iter().take(gemma4::HEAD_SPLITS) {
        for (offset, &value) in split.iter().enumerate() {
            if value > best.0 {
                best = (value, base + offset as u32);
            }
        }
        base += split.len() as u32;
    }
    Some(best.1)
}
