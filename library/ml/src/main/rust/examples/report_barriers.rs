//! How many of a plan's barriers are real, and which fusions would pay.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example report_barriers
//! ```
//!
//! # Why
//!
//! `vulkan::run::Net::record` emits a `vkCmdPipelineBarrier` after **every** op, which is
//! expensive — but how expensive is **unmeasured**. The figure this header used to quote (74% of a
//! Supertonic utterance, 5,091 ms falling to 1,194 ms) came from a no-barrier control that
//! delivered 43,008 frames against a correct run's 150,528, so it compared a full utterance
//! against a truncated one and measured output length as much as barrier cost.
//!
//! The one valid same-work comparison available is `narrow` at 2,179 ms against `none` at
//! 1,160 ms, both at 43,008 frames: a 47% delta, at a truncated workload and with a cheaper
//! barrier than the default. Treat that as a lower bound and not as the headline. A real ceiling
//! needs the frame count pinned so both paths do identical work — that is task 11. See
//! `analysis/maml_vs_litert.md` section 6.
//!
//! There are two ways to spend less on that, and this decides between them before either is
//! built:
//!
//! * **Emit fewer barriers for the same ops.** [`nets::schedule`] computes how many a
//!   dependency-correct recorder would need. If that is close to the op count the plan is serial
//!   and there is nothing to win, which is the likely answer for a ConvNeXt stack and the reason
//!   to measure before implementing.
//! * **Have fewer ops.** Every fused node removes a dispatch *and* a barrier. The adjacency table
//!   below ranks the candidates by how often each producer-consumer pair actually occurs, across
//!   every net rather than the one currently under the microscope.
//!
//! # No device, no downloads
//!
//! Runs on the host: it reads the `.maml` files committed to the repo and only builds plans, which
//! needs no Vulkan. Nets whose weights are a runtime download (Gemma 4, NLLB) are skipped with a
//! line saying so rather than failing the run.
use std::path::{Path, PathBuf};

use modelrunner::nets::schedule::{self, Schedule};
use modelrunner::nets::{
    gemma4, maia, mobilefacenet, nllb, ppocr_det, ppocr_rec, scrfd, selfie, supertonic_duration,
    supertonic_sampler, supertonic_text, supertonic_vocoder, tinyclip, whisper, Kind, Op, Plan,
    WeightSource,
};
use modelrunner::weights::{graph, Weights};

/// Frames and characters to build Supertonic at.
///
/// The measured utterance in `analysis/maml_vs_litert.md`: "Hello, this voice runs entirely on
/// this device." is 49 latent frames and 55 characters once the language tag is added. Plans are
/// shaped by the utterance, so a report at a made-up length would describe a net nobody runs.
const FRAMES: u32 = 49;
const CHARS: u32 = 55;

fn main() {
    let root = repo_root();
    println!("reading .maml from {}", root.display());
    println!();

    let mut plans: Vec<(String, Plan)> = Vec::new();
    let mut add = |name: &str, plan: Result<Plan, String>| match plan {
        Ok(plan) => plans.push((name.to_string(), plan)),
        Err(why) => println!("  {name:<24} does not build: {why}"),
    };

    // (relative path, graph id, how to build every plan that file backs)
    if let Some(w) = open(&root, "camera/src/main/assets/selfie_segmentation.maml", graph::SELFIE) {
        add("selfie", selfie::build(&w.offsets()));
    }
    if let Some(w) = open(&root, "photos/src/main/assets/u2netp.maml", graph::U2NETP) {
        add("u2netp", modelrunner::nets::u2netp::build(&w.offsets()));
    }
    if let Some(w) = open(&root, "photos/src/main/assets/scrfd_500m.maml", graph::SCRFD) {
        add("scrfd 640x640", scrfd::build(&w.offsets(), 640, 640));
    }
    if let Some(w) = open(&root, "photos/src/main/assets/w600k_mbf.maml", graph::MOBILEFACENET) {
        add("mobilefacenet", mobilefacenet::build(&w.offsets()));
    }
    if let Some(w) = open(&root, "library/ocr/src/main/assets/ppocr_det.maml", graph::PPOCR_DET) {
        add("ppocr_det 960x960", ppocr_det::build(&w.offsets(), 960, 960));
    }
    if let Some(w) = open(&root, "library/ocr/src/main/assets/ppocr_rec.maml", graph::PPOCR_REC) {
        add("ppocr_rec w=320", ppocr_rec::build(&w.offsets(), 320));
    }
    if let Some(w) = open(&root, "photos/src/main/assets/clip/tinyclip.maml", graph::TINYCLIP) {
        add("tinyclip image", tinyclip::build(&w.offsets(), tinyclip::Mode::Image));
        add("tinyclip text", tinyclip::build(&w.offsets(), tinyclip::Mode::Text { len: 16 }));
    }
    if let Some(w) = open(&root, "games/chess/src/main/assets/maia3-5m.maml", graph::MAIA) {
        add("maia", maia::build(&w.offsets()));
    }

    let supertonic = "speech/src/main/assets/supertonic";
    if let Some(w) = open(&root, &format!("{supertonic}/supertonic_dp.maml"), graph::SUPERTONIC_DP)
    {
        add("supertonic duration", supertonic_duration::build(&w.offsets(), CHARS));
    }
    if let Some(w) =
        open(&root, &format!("{supertonic}/supertonic_ttl.maml"), graph::SUPERTONIC_TTL)
    {
        add("supertonic text", supertonic_text::build(&w.offsets(), CHARS));
    }
    if let Some(w) = open(&root, &format!("{supertonic}/supertonic_ve.maml"), graph::SUPERTONIC_VE)
    {
        add("supertonic sampler", supertonic_sampler::build(&w.offsets(), FRAMES, CHARS));
    }
    if let Some(w) =
        open(&root, &format!("{supertonic}/supertonic_voc.maml"), graph::SUPERTONIC_VOC)
    {
        add("supertonic vocoder", supertonic_vocoder::build(&w.offsets(), FRAMES));
    }
    if let Some(w) =
        open(&root, "speech/src/main/assets/whisper-base/whisper_base.maml", graph::WHISPER)
    {
        add("whisper encoder", whisper::build(&w.offsets(), whisper::Mode::Encode));
    }

    // The nets whose weights are a runtime download. Built against `Shapes`, which gives the
    // same plan as the real file for a fraction of the gigabytes. Gemma 4's decode step is the
    // largest plan in the runtime and the one `run.rs:695` measured at 676 ms, so it is the most
    // interesting row here even though it is the only one nobody can load from the repo.
    add(
        "gemma4 decode 4096",
        synthetic(|w| gemma4::build(w, gemma4::Mode::DecodeStep.at(4096))),
    );
    add(
        "gemma4 prefill 256",
        synthetic(|w| gemma4::build(w, gemma4::Mode::Prefill { tokens: 256 }.at(4096))),
    );
    add("nllb encode 32", synthetic(|w| nllb::build(w, nllb::Mode::Encode { len: 32 })));
    add(
        "nllb decode step",
        synthetic(|w| nllb::build(w, nllb::Mode::DecodeStep { src_len: 32 })),
    );

    if plans.is_empty() {
        println!("no .maml files found. Run this from inside the repo.");
        return;
    }

    barrier_table(&plans);
    println!();
    adjacency_table(&plans);
}

/// One row per plan: how many barriers today, how many a correct recorder needs, and why.
fn barrier_table(plans: &[(String, Plan)]) {
    println!("Barriers a dependency-correct recorder would emit, against one per op.");
    println!();
    println!(
        "{:<22} {:>6} {:>8} {:>8} {:>7}   of the needed ones",
        "plan", "ops", "today", "needed", "saved"
    );
    for (name, plan) in plans {
        let s = schedule::schedule(plan);
        if let Err(why) = schedule::is_sound(plan, &s) {
            println!("  {name}: SCHEDULE IS UNSOUND: {why}");
            continue;
        }
        let ops = plan.ops.len();
        let needed = s.barriers();
        let saved = if ops == 0 { 0.0 } else { 100.0 * (ops - needed) as f64 / ops as f64 };
        println!(
            "{:<22} {:>6} {:>8} {:>8} {:>6.0}%   {}",
            name,
            ops,
            ops,
            needed,
            saved,
            why(&s),
        );
    }
    println!();
    println!("`today` is one per op, which is what `Net::record` does.");
    println!("A plan whose `saved` is near zero is serial: fusing ops is the only lever on it.");
}

fn why(s: &Schedule) -> String {
    let c = s.causes;
    let mut parts = Vec::new();
    if c.hazard > 0 {
        parts.push(format!("{} a real hazard", c.hazard));
    }
    if c.unknown > 0 {
        parts.push(format!("{} an unaudited read", c.unknown));
    }
    if c.unbounded > 0 {
        parts.push(format!("{} an unbounded write", c.unbounded));
    }
    if parts.is_empty() {
        "none".to_string()
    } else {
        parts.join(", ")
    }
}

/// Which producer-consumer pairs occur often enough to be worth a fused shader.
///
/// Counted over consecutive ops that are genuinely chained — the consumer reads exactly what the
/// producer wrote — because that is the pair a fusion has to replace. Summed across every plan, so
/// the ranking reflects the runtime rather than whichever net is currently slow.
///
/// **Both operands**, and reported separately. A residual `Add(x, f(x))` takes the convolution's
/// output as its *second* operand, so a table keyed on `in0` alone would score the single most
/// interesting fusion in this runtime at zero.
fn adjacency_table(plans: &[(String, Plan)]) {
    let mut pairs: Vec<((Kind, Kind, u8), usize)> = Vec::new();
    let mut total = 0usize;
    for (_, plan) in plans {
        for window in plan.ops.windows(2) {
            let (
                Op::Dispatch { kind: from, push: wrote, .. },
                Op::Dispatch { kind: to, push: reads, .. },
            ) = (&window[0], &window[1])
            else {
                continue;
            };
            // Which operand the producer's output arrives as. `in1` is only meaningful for the
            // binary kinds, so it is only counted where `arena_reads` says the kind reads it.
            let binary = matches!(
                to,
                Kind::Add
                    | Kind::Mul
                    | Kind::MulBroadcast
                    | Kind::AddBroadcast
                    | Kind::Rotary
                    | Kind::AttnScores
                    | Kind::AttnScoresRelative
                    | Kind::AttnScoresBanded
                    | Kind::AttnApply
                    | Kind::AttnApplyRelative
                    | Kind::AttnApplyBanded
                    | Kind::AttnScoresCached
                    | Kind::AttnApplyCached
            );
            let operand = if reads.in0 == wrote.out {
                0
            } else if binary && reads.in1 == wrote.out {
                1
            } else {
                continue;
            };
            total += 1;
            match pairs.iter_mut().find(|((f, t, o), _)| f == from && t == to && *o == operand) {
                Some((_, n)) => *n += 1,
                None => pairs.push(((*from, *to, operand), 1)),
            }
        }
    }
    pairs.sort_by_key(|b| std::cmp::Reverse(b.1));

    println!("Chained producer -> consumer pairs, every plan above summed. {total} in total.");
    println!("Each fused pair removes one dispatch and one barrier everywhere it occurs.");
    println!();
    println!("{:>6} {:>6}  {:<44} arrives as", "count", "share", "pair");
    for ((from, to, operand), n) in pairs.iter().take(24) {
        let share = 100.0 * *n as f64 / total.max(1) as f64;
        let pair = format!("{from:?} -> {to:?}");
        println!("{:>6} {:>5.1}%  {:<44} in{}", n, share, pair, operand);
    }
}

/// Read one `.maml`, or say why not and carry on.
fn open(root: &Path, relative: &str, id: u32) -> Option<Weights> {
    let path = root.join(relative);
    let bytes = match std::fs::read(&path) {
        Ok(bytes) => bytes,
        Err(why) => {
            println!("  {relative}: {why}");
            return None;
        }
    };
    match Weights::parse(&bytes, id) {
        Ok(weights) => Some(weights),
        Err(why) => {
            println!("  {relative}: {why}");
            None
        }
    }
}

/// A [`WeightSource`] that invents offsets and no data.
///
/// Building a [`Plan`] never looks at a weight's *value* — [`Builder`] asks only for an offset and
/// checks the shape it was given — so a plan built against this is byte-identical to one built
/// against the real file. That is what lets this report cover Gemma 4 and NLLB, whose weights are
/// a multi-gigabyte runtime download nobody wants a barrier report to depend on.
///
/// Distinct from [`reference::Invented`], which materialises fp32 data and refuses `shaped_words`
/// outright — no use for the int4 and int8 nets, which are exactly the ones missing from disk.
struct Shapes {
    count: usize,
    next: std::cell::Cell<u32>,
}

impl Shapes {
    fn new(count: usize) -> Shapes {
        Shapes { count, next: std::cell::Cell::new(0) }
    }

    /// Hand out a 16-byte-aligned offset covering `dims`, as the real table's would be.
    fn take(&self, dims: &[u32], per: u32) -> u32 {
        let elements: u32 = dims.iter().copied().product::<u32>().max(1);
        let at = self.next.get();
        self.next.set(at + elements.div_ceil(per).next_multiple_of(8));
        at
    }
}

impl WeightSource for Shapes {
    fn shaped(&self, _index: usize, dims: &[u32]) -> Result<u32, String> {
        Ok(self.take(dims, 1))
    }

    fn shaped_words(&self, _index: usize, dims: &[u32]) -> Result<u32, String> {
        // Four int8 codes to a 32-bit word, which is the only place the unit matters here.
        Ok(self.take(dims, 4))
    }

    fn count(&self) -> usize {
        self.count
    }
}

/// Build a plan with no weights, discovering how many tensors the pass wants.
///
/// [`Builder::finish`] refuses a pass that leaves a tensor unread — a real guard against a
/// misindexed net — and a synthetic source has no idea how many there are. So: ask for far too
/// many, read the count back out of the complaint, and build again. Two attempts, always.
fn synthetic(build: impl Fn(&dyn WeightSource) -> Result<Plan, String>) -> Result<Plan, String> {
    match build(&Shapes::new(1 << 20)) {
        Ok(plan) => Ok(plan),
        Err(why) => {
            let wanted = why
                .split("never reads tensor ")
                .nth(1)
                .and_then(|rest| rest.split(' ').next())
                .and_then(|n| n.parse::<usize>().ok())
                .ok_or(why)?;
            build(&Shapes::new(wanted))
        }
    }
}

/// The repo root, from this crate's manifest directory.
///
/// `library/ml/src/main/rust` is five levels down, so the example works from wherever `cargo run`
/// is invoked rather than only from the directory it happens to be in.
fn repo_root() -> PathBuf {
    let mut at = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    for _ in 0..5 {
        at.pop();
    }
    at
}
