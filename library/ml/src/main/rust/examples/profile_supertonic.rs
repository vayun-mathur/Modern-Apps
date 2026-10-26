//! End-to-end Supertonic utterance profiler: one real sentence exactly as the bridge runs it.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example profile_supertonic -- [asset dir] [voice]
//! adb shell MODELRUNNER_TIMESTAMPS=1 /data/local/tmp/maml/supertonic/profile_supertonic /data/local/tmp/maml/supertonic
//! ```
//!
//! The asset dir holds supertonic_{dp,ttl,ve,voc}.maml, unicode_indexer.bin and style_*.bin
//! (see speech/src/main/assets/supertonic). Voice defaults to F1.
//!
//! The four nets run through [`Stages`] exactly as `bridge_part1.rs`'s `SupertonicNets` does:
//! duration predictor, text encoder, 16 dual-branch sampler steps (`build_dual`, one submit per
//! step), vocoder with the host `unpack_latent`/`interleave`. The only deliberate difference is
//! the noise: the bridge seeds from the clock so two readings differ, while this uses a fixed
//! seed so the audio checksum below proves the output is unchanged against another build.
//!
//! Wall times are with timestamps off unless `MODELRUNNER_TIMESTAMPS=1` is set, so measure the
//! two in separate runs (see examples/profile_net.rs). The pipeline is non-streaming — the
//! waveform exists only after the vocoder — so time-to-first-audio is the total.
use std::collections::BTreeMap;
use std::time::Instant;

use modelrunner::nets::{
    supertonic_duration, supertonic_sampler, supertonic_text, supertonic_vocoder, Plan,
};
use modelrunner::post::supertonic::{self, Voice, STEPS};
use modelrunner::vulkan::context;
use modelrunner::vulkan::reshape::Reshaped;
use modelrunner::weights::{graph, Offsets, Weights};

/// A typical sentence, already NFKD (plain ASCII is stable under NFKD, which is what the
/// Kotlin side guarantees before calling in).
const SENTENCE: &str = "Hello, this voice runs entirely on this device.";
const LANGUAGE: &str = "en";

/// Fixed noise seed, so the checksum below is comparable across builds. The bridge seeds from
/// the clock; everything else here is identical to it.
const NOISE_SEED: u64 = 0x1234_5678_9ABC_DEF1 | 1;

/// The smallest legal shape, for the plan recorded at construction and immediately replaced.
/// Mirrors `bridge_part1.rs`: `Net::rebuild` only ever grows the arena, so starting here
/// wastes nothing.
const SMALLEST: u32 = 1;

fn duration_plan(offsets: &Offsets, chars: u32) -> Result<Plan, String> {
    supertonic_duration::build(offsets, chars)
}

fn text_plan(offsets: &Offsets, chars: u32) -> Result<Plan, String> {
    supertonic_text::build(offsets, chars)
}

fn sampler_plan(offsets: &Offsets, shape: (u32, u32)) -> Result<Plan, String> {
    supertonic_sampler::build_dual_padded(offsets, shape.0, shape.1)
}

fn vocoder_plan(offsets: &Offsets, frames: u32) -> Result<Plan, String> {
    supertonic_vocoder::build(offsets, frames)
}

/// A small deterministic-per-seed generator. Copied from `bridge_part1.rs` (SplitMix64 +
/// Box-Muller) so the latent matches what the bridge would draw from the same seed.
struct SplitMix {
    state: u64,
}

impl SplitMix {
    fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    fn normal(&mut self, count: usize) -> Vec<f32> {
        let mut out = Vec::with_capacity(count);
        while out.len() < count {
            let first = ((self.next_u64() >> 11) as f64 + 1.0) / 9_007_199_254_740_993.0;
            let second = ((self.next_u64() >> 11) as f64) / 9_007_199_254_740_992.0;
            let radius = (-2.0 * first.ln()).sqrt();
            let angle = std::f64::consts::TAU * second;
            out.push((radius * angle.cos()) as f32);
            if out.len() < count {
                out.push((radius * angle.sin()) as f32);
            }
        }
        out
    }
}

/// The single output of a plan that has exactly one. As in `bridge_part1.rs`.
fn one_output(outputs: Vec<Vec<f32>>) -> Result<Vec<f32>, String> {
    match <[Vec<f32>; 1]>::try_from(outputs) {
        Ok([only]) => Ok(only),
        Err(other) => Err(format!("{} outputs, expected one", other.len())),
    }
}

/// Positions per id tensor: `nets::embed_lanes` writes two lanes, `lo + 2048 * hi`.
const LANES: usize = 2;

/// `values / stride`, refusing a remainder. As in `bridge_part1.rs`.
fn positions(what: &str, values: usize, stride: usize) -> Result<u32, String> {
    if stride == 0 || values == 0 || !values.is_multiple_of(stride) {
        return Err(format!("{what}: {values} values is not a whole number of {stride}"));
    }
    Ok((values / stride) as u32)
}

/// The bridge's `SupertonicNets`, plus per-stage wall clocks and the sampler's per-step times.
struct Timed {
    duration: Reshaped<u32>,
    text: Reshaped<u32>,
    sampler: Reshaped<(u32, u32)>,
    vocoder: Reshaped<u32>,
    duration_times: Vec<(String, f64, f64)>,
    text_times: Vec<(String, f64, f64)>,
    sampler_times: Vec<(String, f64, f64)>,
    vocoder_times: Vec<(String, f64, f64)>,
    duration_ms: f64,
    text_ms: f64,
    sampler_ms: Vec<f64>,
    vocoder_ms: f64,
    reshape_ms: f64,
    reshape_duration_ms: f64,
    reshape_text_ms: f64,
    reshape_sampler_ms: f64,
    reshape_vocoder_ms: f64,
    unpack_ms: f64,
    interleave_ms: f64,
}

impl supertonic::Stages for Timed {
    fn duration(&mut self, lanes: &[f32], style: &[f32]) -> Result<f32, String> {
        let sequence = positions("duration ids", lanes.len(), LANES)?;
        let chars = sequence.checked_sub(1).ok_or("a duration pass over only a sentence token")?;
        let reshaping = Instant::now();
        let net = self.duration.at(chars)?;
        let each = ms(reshaping);
        self.reshape_ms += each;
        self.reshape_duration_ms += each;
        let running = Instant::now();
        let out = net.infer_raw_many(&[lanes, style])?;
        self.duration_ms += ms(running);
        // Captured here rather than after the utterance: `Reshaped::at` may
        // re-record later and a fresh pool without a fresh submit reads back
        // garbage. Errors (timestamps off) just leave the previous table.
        if let Ok(times) = net.op_times() {
            self.duration_times = times;
        }
        let [_encoded, log_seconds] = <[Vec<f32>; 2]>::try_from(out).map_err(|other| {
            format!("the duration predictor returned {} tensors, not two", other.len())
        })?;
        match log_seconds.as_slice() {
            [only] => Ok(*only),
            other => {
                Err(format!("the duration predictor returned {} values, not one", other.len()))
            }
        }
    }

    fn text(&mut self, lanes: &[f32], style: &[f32]) -> Result<Vec<f32>, String> {
        let chars = positions("text ids", lanes.len(), LANES)?;
        let reshaping = Instant::now();
        let net = self.text.at(chars)?;
        let each = ms(reshaping);
        self.reshape_ms += each;
        self.reshape_text_ms += each;
        let running = Instant::now();
        let out = one_output(net.infer_raw_many(&[lanes, style])?);
        self.text_ms += ms(running);
        if let Ok(times) = net.op_times() {
            self.text_times = times;
        }
        out
    }
    fn text_then(
        &mut self,
        lanes: &[f32],
        style: &[f32],
        sampler_shape: (u32, u32),
    ) -> Result<Vec<f32>, String> {
        if self.sampler.holds(sampler_shape) {
            return self.text(lanes, style);
        }
        // As bridge_part1.rs: the sampler plan builds on another thread while the text encoder runs.
        let (plan, offsets) = self.sampler.planner();
        let (text, built) = std::thread::scope(|scope| {
            let building = scope.spawn(move || plan(&offsets, sampler_shape));
            let text = self.text(lanes, style);
            (text, building.join())
        });
        if let Ok(Ok(built)) = built {
            self.sampler.adopt(sampler_shape, built);
        }
        text
    }

    fn sampler(
        &mut self,
        _latent: &[f32],
        _text: &[f32],
        _keys: &[f32],
        _style: &[f32],
        _shifts: &[f32],
        _query_angles: &[f32],
        _key_angles: &[f32],
    ) -> Result<Vec<f32>, String> {
        // Dead: `synthesise` takes the dual path. The bridge spells out the single-branch
        // submit; this profiler only runs the path the bridge actually takes.
        Err("the profiler runs the dual sampler path only".into())
    }

    #[allow(clippy::too_many_arguments)]
    fn sampler_both(
        &mut self,
        latent: &[f32],
        conditional_text: &[f32],
        conditional_keys: &[f32],
        conditional_style: &[f32],
        unconditional_text: &[f32],
        unconditional_keys: &[f32],
        unconditional_style: &[f32],
        shifts: &[f32],
        query_angles: &[f32],
        key_angles: &[f32],
    ) -> Result<[Vec<f32>; 2], String> {
        let frames = positions("a latent", latent.len(), supertonic_sampler::LATENT as usize)?;
        let chars = positions(
            "a conditioning",
            conditional_text.len(),
            supertonic_sampler::TEXT as usize,
        )?;
        let reshaping = Instant::now();
        let net = self.sampler.at((frames, chars))?;
        let each = ms(reshaping);
        self.reshape_ms += each;
        self.reshape_sampler_ms += each;
        let running = Instant::now();
        let latent = supertonic_sampler::pad_frames(latent, frames)?;
        let query_angles = supertonic_sampler::pad_frames(query_angles, frames)?;
        let (latent, query_angles) = (latent.as_slice(), query_angles.as_slice());
        let out = net.infer_raw_many(&[
            latent,
            conditional_text,
            conditional_keys,
            conditional_style,
            shifts,
            query_angles,
            key_angles,
            latent,
            unconditional_text,
            unconditional_keys,
            unconditional_style,
            shifts,
            query_angles,
            key_angles,
        ])?;
        self.sampler_ms.push(ms(running));
        // One step's table stands for all sixteen: same plan, same shape.
        if let Ok(times) = net.op_times() {
            self.sampler_times = times;
        }
        let [conditional, unconditional] =
            <[Vec<f32>; 2]>::try_from(out).map_err(|other| {
                format!("the dual sampler returned {} tensors, not two", other.len())
            })?;
        Ok([
            supertonic_sampler::unpad_frames(&conditional, frames)?,
            supertonic_sampler::unpad_frames(&unconditional, frames)?,
        ])
    }

    fn vocoder(&mut self, latent: &[f32], frames: u32) -> Result<Vec<f32>, String> {
        let reshaping = Instant::now();
        let net = self.vocoder.at(supertonic_vocoder::padded_frames(frames))?;
        let each = ms(reshaping);
        self.reshape_ms += each;
        self.reshape_vocoder_ms += each;
        let unpacking = Instant::now();
        let unpacked = supertonic_vocoder::unpack_latent_padded(latent, frames as usize)?;
        self.unpack_ms += ms(unpacking);
        let running = Instant::now();
        let channelled = one_output(net.infer_raw(&unpacked)?)?;
        self.vocoder_ms += ms(running);
        if let Ok(times) = net.op_times() {
            self.vocoder_times = times;
        }
        let interleaving = Instant::now();
        let out = supertonic_vocoder::interleave_padded(&channelled, frames as usize)?;
        self.interleave_ms += ms(interleaving);
        Ok(out)
    }
}

fn main() {
    if let Err(why) = run() {
        println!("failed: {why}");
    }
}

fn run() -> Result<(), String> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let dir = args
        .first()
        .map(String::as_str)
        .or_else(|| option_env!("MODELRUNNER_ASSETS"))
        .map(str::to_owned)
        .or_else(|| std::env::var("MODELRUNNER_ASSETS").ok())
        .ok_or("pass the supertonic asset dir or set MODELRUNNER_ASSETS")?;
    let voice = args.get(1).map(String::as_str).unwrap_or("F1");
    let file = |name: &str| {
        let path = format!("{dir}/{name}");
        std::fs::read(&path).map_err(|e| format!("cannot read {path}: {e}"))
    };

    let dp = Weights::parse(&file("supertonic_dp.maml")?, graph::SUPERTONIC_DP)?;
    let ttl = Weights::parse(&file("supertonic_ttl.maml")?, graph::SUPERTONIC_TTL)?;
    let ve = Weights::parse(&file("supertonic_ve.maml")?, graph::SUPERTONIC_VE)?;
    let voc = Weights::parse(&file("supertonic_voc.maml")?, graph::SUPERTONIC_VOC)?;
    let indexer = file("unicode_indexer.bin")?;
    let voice = Voice::read(&file(&format!("style_{voice}.bin"))?)?;
    let conditioning = supertonic::Conditioning::read(ve.reader())?;

    let context = context::shared()?;
    let started = Instant::now();
    let mut stages = Timed {
        duration: Reshaped::new(context.clone(), &dp, SMALLEST, duration_plan)?,
        text: Reshaped::new(context.clone(), &ttl, SMALLEST, text_plan)?,
        sampler: Reshaped::new(context.clone(), &ve, (SMALLEST, SMALLEST), sampler_plan)?,
        vocoder: Reshaped::new(context, &voc, SMALLEST, vocoder_plan)?,
        duration_times: Vec::new(),
        text_times: Vec::new(),
        sampler_times: Vec::new(),
        vocoder_times: Vec::new(),
        duration_ms: 0.0,
        text_ms: 0.0,
        sampler_ms: Vec::new(),
        vocoder_ms: 0.0,
        reshape_ms: 0.0,
        reshape_duration_ms: 0.0,
        reshape_text_ms: 0.0,
        reshape_sampler_ms: 0.0,
        reshape_vocoder_ms: 0.0,
        unpack_ms: 0.0,
        interleave_ms: 0.0,
    };
    let load_ms = ms(started);
    println!("load      {load_ms:>8.1} ms (upload + first record)");

    let noise = std::cell::RefCell::new(SplitMix { state: NOISE_SEED });
    let whole = Instant::now();
    let samples = supertonic::synthesise(
        &mut stages,
        &conditioning,
        &indexer,
        &voice,
        SENTENCE,
        LANGUAGE,
        &|count| noise.borrow_mut().normal(count),
    )?;
    let total_ms = ms(whole);

    let ids = supertonic::to_ids(&indexer, SENTENCE, LANGUAGE)?;
    let chars = ids.len() as u32;
    let frames = samples.len() as u32 / supertonic_vocoder::SAMPLES_PER_FRAME;
    println!("sentence  {SENTENCE:?} -> {chars} ids -> {frames} frames -> {} samples", samples.len());
    println!("duration  {:>8.1} ms", stages.duration_ms);
    println!("text      {:>8.1} ms", stages.text_ms);
    println!("sampler   {:>8.1} ms over {} steps (dual submit each)", sum(&stages.sampler_ms), stages.sampler_ms.len());
    for (step, each) in stages.sampler_ms.iter().enumerate() {
        println!("  step {step:>2}  {each:>8.1} ms");
    }
    println!("vocoder   {:>8.1} ms (unpack {:.1}, infer {:.1}, interleave {:.1})",
        stages.unpack_ms + stages.vocoder_ms + stages.interleave_ms,
        stages.unpack_ms,
        stages.vocoder_ms,
        stages.interleave_ms);
    println!("reshape   {:>8.1} ms (dp {:.1}, ttl {:.1}, ve {:.1}, voc {:.1}; plan build + re-record)",
        stages.reshape_ms,
        stages.reshape_duration_ms,
        stages.reshape_text_ms,
        stages.reshape_sampler_ms,
        stages.reshape_vocoder_ms);
    println!("total     {total_ms:>8.1} ms");
    println!("first audio {total_ms:>8.1} ms (non-streaming: the waveform exists only after the vocoder)");

    let (sum, sum_sq, peak) = checksum(&samples);
    println!("checksum  len {} sum {sum:.6} sum_sq {sum_sq:.6} peak {peak:.6}", samples.len());

    // Per-kind GPU tables, captured after each stage's own submit above — only with
    // MODELRUNNER_TIMESTAMPS=1, and in a separate run from the wall times.
    let _ = (chars, frames);
    print_kinds("duration", &stages.duration_times);
    print_kinds("text", &stages.text_times);
    print_kinds("sampler dual step", &stages.sampler_times);
    print_kinds("vocoder", &stages.vocoder_times);

    assert_eq!(stages.sampler_ms.len(), STEPS as usize, "one dual submit per step");

    // Further, differently sized sentences in the same process (`MODELRUNNER_SECOND`, split on
    // `|`): what each new sentence length costs once everything is warm.
    let more = std::env::var("MODELRUNNER_SECOND").unwrap_or_default();
    for second in more.split('|').filter(|s| !s.is_empty()) {
        let reshaped = stages.reshape_ms;
        let (text, sampler, vocoder) =
            (stages.reshape_text_ms, stages.reshape_sampler_ms, stages.reshape_vocoder_ms);
        let whole = Instant::now();
        let samples = supertonic::synthesise(
            &mut stages,
            &conditioning,
            &indexer,
            &voice,
            second,
            LANGUAGE,
            &|count| noise.borrow_mut().normal(count),
        )?;
        println!(
            "second    {:>8.1} ms, {} samples, reshape {:.1} ms (ttl {:.1}, ve {:.1}, voc {:.1})",
            ms(whole),
            samples.len(),
            stages.reshape_ms - reshaped,
            stages.reshape_text_ms - text,
            stages.reshape_sampler_ms - sampler,
            stages.reshape_vocoder_ms - vocoder
        );
    }
    Ok(())
}

fn checksum(samples: &[f32]) -> (f64, f64, f32) {
    let sum = samples.iter().map(|&v| f64::from(v)).sum::<f64>();
    let sum_sq = samples.iter().map(|&v| { let d = f64::from(v); d * d }).sum::<f64>();
    let peak = samples.iter().fold(0.0f32, |top, &v| top.max(v.abs()));
    (sum, sum_sq, peak)
}

fn print_kinds(label: &str, times: &[(String, f64, f64)]) {
    if times.is_empty() {
        println!("{label}: no timestamps recorded: set MODELRUNNER_TIMESTAMPS=1");
        return;
    }
    let mut kinds: BTreeMap<String, (usize, f64)> = BTreeMap::new();
    let (mut total, mut gaps) = (0.0, 0.0);
    for (name, us, gap) in times {
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
    let mut by_time: Vec<_> = times.iter().collect();
    by_time.sort_by(|a, b| b.1.total_cmp(&a.1));
    println!("  top dispatches:");
    for (name, us, _) in by_time.iter().take(12) {
        println!("    {name:<28} {us:>10.1} us");
    }
}

fn ms(since: Instant) -> f64 {
    since.elapsed().as_secs_f64() * 1e3
}

fn sum(values: &[f64]) -> f64 {
    values.iter().sum()
}
