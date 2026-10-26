//! Shared by the Gemma 4 tower parity examples: `--time N` builds the net once, runs it `N`
//! times and prints wall time per run plus, with `MODELRUNNER_TIMESTAMPS=1`, a per-kind table.

use modelrunner::vulkan::reshape::Reshaped;
use std::time::Instant;

/// Build-free timing of an already-built net: one warm-up run, then `repeats` timed ones.
pub fn time_runs<S: Copy + PartialEq>(
    net: &mut Reshaped<S>,
    mode: S,
    inputs: &[&[f32]],
    repeats: usize,
) -> Result<(), String> {
    let mut walls = Vec::with_capacity(repeats);
    for pass in 0..=repeats {
        let tick = Instant::now();
        net.at(mode)?.infer_raw_many(inputs)?;
        let ms = tick.elapsed().as_secs_f64() * 1000.0;
        println!("run {pass}: {ms:.1} ms{}", if pass == 0 { " (first)" } else { "" });
        if pass > 0 {
            walls.push(ms);
        }
    }
    walls.sort_by(f64::total_cmp);
    if let Some(median) = walls.get(walls.len() / 2) {
        println!("median of {} warm runs: {median:.1} ms", walls.len());
    }
    if modelrunner::knobs::is_set("timestamps") {
        print_kinds(&net.at(mode)?.op_times()?);
    }
    Ok(())
}

/// Per-kind GPU table for one submit, plus its ten hottest dispatches.
fn print_kinds(report: &[(String, f64, f64)]) {
    let mut per_kind: Vec<(String, usize, f64)> = Vec::new();
    let (mut total, mut gaps) = (0.0, 0.0);
    for (label, us, gap) in report {
        total += us;
        gaps += gap;
        let kind = label.split(':').nth(1).unwrap_or("?").trim().to_string();
        match per_kind.iter_mut().find(|(k, _, _)| *k == kind) {
            Some((_, n, t)) => {
                *n += 1;
                *t += us;
            }
            None => per_kind.push((kind, 1, *us)),
        }
    }
    per_kind.sort_by(|a, b| b.2.total_cmp(&a.2));
    println!("{} dispatches, {:.1} ms timestamped, {:.1} ms gaps", report.len(), total / 1000.0, gaps / 1000.0);
    println!("{:>6} {:>10} {:>10}  kind", "count", "total-ms", "mean-us");
    for (kind, n, us) in &per_kind {
        println!("{n:>6} {:>10.1} {:>10.1}  {kind}", us / 1000.0, us / *n as f64);
    }
    let mut hottest: Vec<&(String, f64, f64)> = report.iter().collect();
    hottest.sort_by(|a, b| b.1.total_cmp(&a.1));
    for (label, us, _) in hottest.into_iter().take(10) {
        println!("  {us:>9.1} us  {label}");
    }
}
