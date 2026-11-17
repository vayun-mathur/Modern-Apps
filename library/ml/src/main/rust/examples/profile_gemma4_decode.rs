//! Profile Gemma-4's decode step: op mix, weight traffic, and the two ceilings.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example profile_gemma4_decode
//! ```
//!
//! # Why
//!
//! P8 measures 554 ms/token steady-state (1.80 tok/s) against LiteRT's ~10 tok/s.
//! This attributes that number host-side (no device, no weights — the plan is
//! built against synthetic shapes, exactly as `report_barriers` does): per-Kind
//! op counts, per-Kind weight bytes read per step, total weight traffic per
//! step, and the dispatch-rate ceiling the op count implies. What it cannot do
//! is split a step into dispatch overhead vs kernel execution — that needs
//! on-device timestamps the runtime does not emit yet.
use modelrunner::nets::{gemma4, Kind, Op, WeightSource};

/// A [`WeightSource`] that invents offsets and no data. Same trick as
/// `report_barriers::Shapes`: plan-building never looks at a weight value.
struct Shapes {
    count: usize,
    next: std::cell::Cell<u32>,
}

impl WeightSource for Shapes {
    fn shaped(&self, _index: usize, dims: &[u32]) -> Result<u32, String> {
        let elements: u32 = dims.iter().copied().product::<u32>().max(1);
        let at = self.next.get();
        self.next.set(at + elements.next_multiple_of(8));
        Ok(at)
    }

    fn shaped_words(&self, _index: usize, dims: &[u32]) -> Result<u32, String> {
        let elements: u32 = dims.iter().copied().product::<u32>().max(1);
        let at = self.next.get();
        self.next.set(at + elements.div_ceil(4).next_multiple_of(8));
        Ok(at)
    }

    fn count(&self) -> usize {
        self.count
    }
}

/// Weight bytes one op reads per decode step, from the geometry the builder
/// resolved (`Push` carries in/out channels + positions; scales/biases are
/// small beside the kernels and are folded into the per-kind factor).
fn weight_bytes(kind: &Kind, push: &modelrunner::nets::Push) -> u64 {
    let taps = u64::from(push.in_c) * u64::from(push.kh).max(1) * u64::from(push.kw).max(1);
    let chans = u64::from(push.out_c);
    let positions = u64::from(push.out_w).max(1);
    // Decode: one position. Prefill shapes would multiply by positions —
    // decode only, stated.
    let _ = positions;
    match kind {
        Kind::ConvPointInt4 | Kind::ConvVecInt4 => chans * taps / 2 + chans * taps / 32,
        Kind::ConvInt8 | Kind::ConvPointInt8 | Kind::ConvVecInt8 => {
            chans * taps + chans * 2
        }
        Kind::ConvQ2K | Kind::ConvPointQ2K | Kind::ConvVecQ2K => chans * taps / 4,
        Kind::Conv | Kind::ConvPoint => chans * taps * 2,
        // Norms/gammas: one vector of out_c fp16.
        Kind::RmsNorm | Kind::LayerNorm => chans * 2,
        // Everything else reads no weights (caches, softmaxes, elementwise).
        _ => 0,
    }
}

fn main() {
    let source = Shapes { count: 1 << 20, next: std::cell::Cell::new(0) };
    let plan = match gemma4::build(&source, gemma4::Mode::DecodeStep.at(4096)) {
        Ok(plan) => plan,
        Err(why) => {
            // Same two-attempt dance as report_barriers::synthetic.
            let wanted = why
                .split("never reads tensor ")
                .nth(1)
                .and_then(|rest| rest.split(' ').next())
                .and_then(|n| n.parse::<usize>().ok());
            match wanted {
                Some(n) => {
                    let retry =
                        Shapes { count: n, next: std::cell::Cell::new(0) };
                    match gemma4::build(&retry, gemma4::Mode::DecodeStep.at(4096)) {
                        Ok(plan) => plan,
                        Err(why) => return println!("the decode plan does not build: {why}"),
                    }
                }
                None => return println!("the decode plan does not build: {why}"),
            }
        }
    };
    println!("decode plan: {} ops, {} barriers today", plan.ops.len(), plan.ops.len());
    let mut per_kind: Vec<(Kind, usize, u64)> = Vec::new();
    for op in &plan.ops {
        let Op::Dispatch { kind, push, .. } = op else { continue };
        let bytes = weight_bytes(kind, push);
        match per_kind.iter_mut().find(|(k, _, _)| k == kind) {
            Some((_, n, b)) => {
                *n += 1;
                *b += bytes;
            }
            None => per_kind.push((*kind, 1, bytes)),
        }
    }
    per_kind.sort_by_key(|b| std::cmp::Reverse(b.2));
    println!();
    println!("{:>6} {:>10}  {:?}", "count", "wt-bytes", "kind");
    let mut total_bytes = 0u64;
    for (kind, n, bytes) in &per_kind {
        println!("{n:>6} {bytes:>10}  {kind:?}");
        total_bytes += bytes;
    }
    println!();
    println!("total weight traffic per step: ~{:.1} MB", total_bytes as f64 / 1e6);
    println!("arena: {:.1} MB", f64::from(plan.arena_elems) * 2.0 / 1e6);
    println!();
    println!("ceilings at P8-measured 587 us/dispatch (plan) vs 5 GB/s buffers:");
    println!(
        "  dispatch ceiling: {} ops x 587 us = {:.0} ms",
        plan.ops.len(),
        plan.ops.len() as f64 * 0.587
    );
    println!(
        "  bandwidth ceiling: {:.1} MB / 5 GB/s = {:.0} ms",
        total_bytes as f64 / 1e6,
        total_bytes as f64 / 5e9 * 1e3
    );
    println!("  measured on-device: 554 ms/token steady-state");
}
