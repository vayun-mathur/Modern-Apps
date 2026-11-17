//! SCRATCH — task 42 acoustic spike. Delete after the run; not intended to land.
//!
//! Embeds 16 kHz mono 16-bit WAVs with the NNFP embedder and retrieves a query against a
//! reference set by cosine similarity over the 64-d embeddings, searching all alignments.
//!
//! ```text
//! cargo run --release -p modelrunner --example spike_nnfp_match -- \
//!     <nnfp.maml> <query.wav> <ref.wav>...
//! ```

use std::collections::BTreeMap;
use std::path::Path;

use modelrunner::microfrontend::{Frontend, NOW_PLAYING};
use modelrunner::nets::nnfp;
use modelrunner::preprocess::RESCALE_ONLY;
use modelrunner::vulkan::context;
use modelrunner::vulkan::run::Net;
use modelrunner::weights::{graph, Weights};

/// 10 ms frames per embedding window, and how far the window walks between embeddings.
const REF_HOP_FRAMES: usize = 100;
const QUERY_HOP_FRAMES: usize = 25;

/// The log-mel front end has no per-utterance normalisation, so absolute level walks straight
/// into the features. A mic capture and a decoded file are never at the same level, so both
/// sides are brought to a common RMS first; otherwise the retrieval would be measuring gain.
const TARGET_RMS: f32 = 3000.0;

fn read_wav_mono16(path: &Path) -> Result<Vec<i16>, String> {
    let bytes = std::fs::read(path).map_err(|e| format!("{}: {e}", path.display()))?;
    if bytes.len() < 12 || &bytes[0..4] != b"RIFF" || &bytes[8..12] != b"WAVE" {
        return Err(format!("{}: not a RIFF/WAVE file", path.display()));
    }
    let u16at = |o: usize| u16::from_le_bytes([bytes[o], bytes[o + 1]]);
    let u32at = |o: usize| u32::from_le_bytes([bytes[o], bytes[o + 1], bytes[o + 2], bytes[o + 3]]);

    let (mut channels, mut rate, mut bits) = (0u16, 0u32, 0u16);
    let mut at = 12;
    let mut pcm: Option<(usize, usize)> = None;
    while at + 8 <= bytes.len() {
        let id = &bytes[at..at + 4];
        let size = u32at(at + 4) as usize;
        let body = at + 8;
        if id == b"fmt " && body + 16 <= bytes.len() {
            channels = u16at(body + 2);
            rate = u32at(body + 4);
            bits = u16at(body + 14);
        } else if id == b"data" {
            pcm = Some((body, size.min(bytes.len().saturating_sub(body))));
        }
        at = body + size + (size & 1);
    }
    let (start, len) = pcm.ok_or_else(|| format!("{}: no data chunk", path.display()))?;
    if bits != 16 {
        return Err(format!("{}: {bits}-bit, expected 16", path.display()));
    }
    if rate != 16000 {
        return Err(format!("{}: {rate} Hz, expected 16000", path.display()));
    }

    let frames = len / 2;
    let mut mono = Vec::with_capacity(frames / channels.max(1) as usize);
    let chans = channels.max(1) as usize;
    let mut i = 0;
    while (i + chans) * 2 <= len {
        let mut sum = 0i32;
        for c in 0..chans {
            let o = start + (i + c) * 2;
            sum += i16::from_le_bytes([bytes[o], bytes[o + 1]]) as i32;
        }
        mono.push((sum / chans as i32) as i16);
        i += chans;
    }
    Ok(mono)
}

fn rms_normalise(samples: &mut [i16]) -> f32 {
    let sum: f64 = samples.iter().map(|&s| (s as f64) * (s as f64)).sum();
    let rms = (sum / samples.len().max(1) as f64).sqrt() as f32;
    if rms > 1.0 {
        let gain = TARGET_RMS / rms;
        for s in samples.iter_mut() {
            *s = (*s as f32 * gain).clamp(-32768.0, 32767.0) as i16;
        }
    }
    rms
}

/// Every 10 ms log-mel frame of `samples`, laid out frame-major exactly as the plan's input
/// expects.
fn log_mel(samples: &[i16]) -> Result<Vec<f32>, String> {
    let mut frontend = Frontend::new(NOW_PLAYING)?;
    let mut out = Vec::new();
    frontend.process(samples, &mut out);
    Ok(out)
}

fn l2(v: &mut [f32]) {
    let n = v.iter().map(|x| x * x).sum::<f32>().sqrt();
    if n > 0.0 {
        for x in v.iter_mut() {
            *x /= n;
        }
    }
}

type EmbedAll = (Vec<(f32, Vec<f32>)>, Vec<f32>);

/// Slide the 478-frame window over `frames` and embed each position.
///
/// Returns the L2-normalised embeddings for matching, and separately the raw pre-normalisation
/// output, which is what an AC/DC reading has to be taken on.
fn embed_all(
    net: &mut Net,
    frames: &[f32],
    hop_frames: usize,
) -> Result<EmbedAll, String> {
    let bins = nnfp::MEL_BINS as usize;
    let window = nnfp::WINDOW_FRAMES as usize;
    let total = frames.len() / bins;
    let mut out = Vec::new();
    let mut raw = Vec::new();
    let mut start = 0usize;
    while start + window <= total {
        let slice = &frames[start * bins..(start + window) * bins];
        let mut e = net.infer_raw(slice)?.remove(0);
        raw.extend_from_slice(&e);
        l2(&mut e);
        out.push((start as f32 * 0.01, e));
        start += hop_frames;
    }
    Ok((out, raw))
}

fn cosine(a: &[f32], b: &[f32]) -> f32 {
    a.iter().zip(b).map(|(x, y)| x * y).sum()
}

/// The AC/DC ratio task 49 uses: how much of the representation varies with the audio versus
/// sits as a fixed offset. `rows` is time-major, `width` values per row.
///
/// Per dimension, AC is the standard deviation across time and DC the mean across time; the
/// ratio is the mean AC over the mean |DC|. A discriminative embedder should *raise* this from
/// its input to its output — log-mel is all-positive with a large common mode, so a low value
/// going in is expected and correct.
fn ac_dc(rows: &[f32], width: usize) -> f32 {
    let t = rows.len() / width;
    if t < 2 {
        return f32::NAN;
    }
    let (mut ac, mut dc) = (0.0f64, 0.0f64);
    for d in 0..width {
        let mean: f64 = (0..t).map(|i| rows[i * width + d] as f64).sum::<f64>() / t as f64;
        let var: f64 = (0..t)
            .map(|i| {
                let x = rows[i * width + d] as f64 - mean;
                x * x
            })
            .sum::<f64>()
            / t as f64;
        ac += var.sqrt();
        dc += mean.abs();
    }
    (ac / dc) as f32
}

fn main() -> Result<(), String> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() < 3 {
        return Err("usage: spike_nnfp_match <nnfp.maml> <query.wav> <ref.wav>...".into());
    }

    let blob = std::fs::read(&args[0]).map_err(|e| format!("{}: {e}", args[0]))?;
    let weights = Weights::parse(&blob, graph::NNFP)?;
    let plan = nnfp::build(&weights)?;
    // `infer_raw` feeds the arena directly, so the image normalisation is never applied.
    let context = context::shared()?;
    let mut net = Net::new(context, plan, &weights, RESCALE_ONLY)?;
    eprintln!("embedder ready: {} tensors, {} B", weights.len(), blob.len());

    // Reference set.
    let mut refs: Vec<(String, f32, Vec<f32>)> = Vec::new();
    let mut ref_raw: Vec<f32> = Vec::new();
    for path in &args[2..] {
        let mut pcm = read_wav_mono16(Path::new(path))?;
        let rms = rms_normalise(&mut pcm);
        let frames = log_mel(&pcm)?;
        let (embedded, raw) = embed_all(&mut net, &frames, REF_HOP_FRAMES)?;
        ref_raw.extend_from_slice(&raw);
        let name = Path::new(path)
            .file_stem()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_else(|| path.clone());
        eprintln!(
            "  ref {name}: {:.1}s, rms {rms:.0}, {} segments",
            pcm.len() as f32 / 16000.0,
            embedded.len()
        );
        for (at, e) in embedded {
            refs.push((name.clone(), at, e));
        }
    }
    eprintln!("reference set: {} segments", refs.len());

    // Query.
    let mut pcm = read_wav_mono16(Path::new(&args[1]))?;
    let rms = rms_normalise(&mut pcm);
    let frames = log_mel(&pcm)?;
    let (query, raw) = embed_all(&mut net, &frames, QUERY_HOP_FRAMES)?;
    eprintln!(
        "query {}: {:.1}s, rms {rms:.0}, {} windows",
        args[1],
        pcm.len() as f32 / 16000.0,
        query.len()
    );
    println!(
        "\n=== AC/DC (task 49 diagnostic) ===\n  input log-mel          {:.3}\n  after head, 1 track    {:.3}\n  after head, all refs   {:.3}  ({} segments, {} tracks)",
        ac_dc(&frames, nnfp::MEL_BINS as usize),
        ac_dc(&raw, nnfp::EMBEDDING as usize),
        ac_dc(&ref_raw, nnfp::EMBEDDING as usize),
        ref_raw.len() / nnfp::EMBEDDING as usize,
        args.len() - 2
    );

    // Raw pre-L2 reference embeddings, for the effective-rank check that needs numpy.
    if let Ok(path) = std::env::var("SPIKE_DUMP_RAW") {
        let bytes: Vec<u8> = ref_raw.iter().flat_map(|v| v.to_le_bytes()).collect();
        std::fs::write(&path, &bytes).map_err(|e| format!("{path}: {e}"))?;
        eprintln!(
            "wrote {} raw embeddings x {} dims to {path}",
            ref_raw.len() / nnfp::EMBEDDING as usize,
            nnfp::EMBEDDING
        );
    }

    // The query's log-mel frames, to check whether a working front end is itself low-rank.
    if let Ok(path) = std::env::var("SPIKE_DUMP_LOGMEL") {
        let bytes: Vec<u8> = frames.iter().flat_map(|v| v.to_le_bytes()).collect();
        std::fs::write(&path, &bytes).map_err(|e| format!("{path}: {e}"))?;
        eprintln!(
            "wrote {} log-mel frames x {} bins to {path}",
            frames.len() / nnfp::MEL_BINS as usize,
            nnfp::MEL_BINS
        );
    }

    // Best (query window, reference segment) pair per reference track.
    let mut best: BTreeMap<String, (f32, f32, f32)> = BTreeMap::new();
    for (qt, q) in &query {
        for (name, rt, r) in &refs {
            let s = cosine(q, r);
            let slot = best.entry(name.clone()).or_insert((f32::MIN, 0.0, 0.0));
            if s > slot.0 {
                *slot = (s, *qt, *rt);
            }
        }
    }

    let mut ranked: Vec<(&String, &(f32, f32, f32))> = best.iter().collect();
    ranked.sort_by(|a, b| b.1 .0.partial_cmp(&a.1 .0).unwrap());

    println!("\n=== per-track best cosine ===");
    for (name, (s, qt, rt)) in &ranked {
        println!("  {name:<28} {s:.4}   query t={qt:.2}s  ref t={rt:.2}s");
    }
    if ranked.len() >= 2 {
        let margin = ranked[0].1 .0 - ranked[1].1 .0;
        println!(
            "\ntop-1: {}  ({:.4})\nrunner-up: {}  ({:.4})\nmargin: {:.4}",
            ranked[0].0, ranked[0].1 .0, ranked[1].0, ranked[1].1 .0, margin
        );
    }

    // The null distribution: how high a wrong pair gets, so the top-1 has a scale to be read
    // against. Without it a cosine of 0.8 means nothing.
    let mut all: Vec<f32> = Vec::new();
    for (_, q) in query.iter().take(40) {
        for (_, _, r) in refs.iter() {
            all.push(cosine(q, r));
        }
    }
    all.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let pick = |f: f32| all[((all.len() - 1) as f32 * f) as usize];
    println!(
        "\nall-pairs cosine over {} pairs: p50 {:.4}  p95 {:.4}  p99 {:.4}  max {:.4}",
        all.len(),
        pick(0.50),
        pick(0.95),
        pick(0.99),
        all[all.len() - 1]
    );

    Ok(())
}
