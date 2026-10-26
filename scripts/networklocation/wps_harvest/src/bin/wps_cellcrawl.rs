//! `wps_cellcrawl --out SHARD --seed MCC,MNC,TAC,CID [--seed ...] [options]`
//!
//! Snowball crawl of LTE cells through gs-loc. A cell request is answered with the asked-for
//! tower plus its surrounding LTE cells (around 120 when the request does not cap them), each
//! with its own position, so a few seeds from a phone's cell list cover a whole neighbourhood.
//! Cells nearest the first resolved seed are queried first, so a bounded run fills in the area
//! around the phone rather than wandering off along a motorway.

use std::cmp::Reverse;
use std::collections::{BinaryHeap, HashSet};
use std::fs::OpenOptions;
use std::io::BufWriter;
use std::path::PathBuf;
use std::process::ExitCode;
use std::time::Duration;

use wps_harvest::gsloc::{CellFix, Client};
use wps_harvest::keys::{self, CellId, RadioType};
use wps_harvest::record::{Record, RecordWriter, Source, ACC_UNKNOWN};

const USAGE: &str = "\
Usage: wps_cellcrawl --out SHARD --seed MCC,MNC,TAC,CID [--seed ...] [options]

  --out            record shard to append to
  --seed           an LTE cell to start from, e.g. from the phone's cell list (repeatable)
  --max-queries N  stop after N requests (default 50)
  --radius-km R    only expand into cells within R km of the first resolved seed (default 15)
  --interval MS    minimum milliseconds between requests (default 1000)";

/// Consecutive failed requests after which the crawl gives up rather than hammering a
/// service that is refusing it.
const MAX_CONSECUTIVE_ERRORS: u32 = 5;

struct Args {
    out: PathBuf,
    seeds: Vec<(u32, u32, u32, u32)>,
    max_queries: u32,
    radius_m: f64,
    interval: Duration,
}

fn parse_seed(s: &str) -> Result<(u32, u32, u32, u32), String> {
    let p: Vec<u32> = s
        .split(',')
        .map(|v| v.trim().parse::<u32>().map_err(|e| format!("bad seed '{s}': {e}")))
        .collect::<Result<_, _>>()?;
    match p[..] {
        [mcc, mnc, tac, cid] => Ok((mcc, mnc, tac, cid)),
        _ => Err(format!("seed '{s}' must be MCC,MNC,TAC,CID")),
    }
}

fn parse_args(argv: &[String]) -> Result<Args, String> {
    let mut out = None;
    let mut seeds = Vec::new();
    let mut max_queries = 50u32;
    let mut radius_km = 15.0f64;
    let mut interval_ms = 1000u64;
    let mut i = 0;
    while i < argv.len() {
        let a = argv[i].as_str();
        let value = || argv.get(i + 1).cloned().ok_or_else(|| format!("{a} needs a value"));
        match a {
            "--out" => out = Some(PathBuf::from(value()?)),
            "--seed" => seeds.push(parse_seed(&value()?)?),
            "--max-queries" => max_queries = value()?.parse().map_err(|e| format!("{e}"))?,
            "--radius-km" => radius_km = value()?.parse().map_err(|e| format!("{e}"))?,
            "--interval" => interval_ms = value()?.parse().map_err(|e| format!("{e}"))?,
            "-h" | "--help" => return Err("help".to_string()),
            other => return Err(format!("unknown argument: {other}")),
        }
        i += 2;
    }
    if seeds.is_empty() {
        return Err("at least one --seed is required".to_string());
    }
    Ok(Args {
        out: out.ok_or("--out is required")?,
        seeds,
        max_queries,
        radius_m: radius_km * 1000.0,
        interval: Duration::from_millis(interval_ms),
    })
}

/// Great-circle distance in metres between two e8 coordinates.
fn distance_m(a: (i64, i64), b: (i64, i64)) -> f64 {
    let (lat1, lon1) = ((a.0 as f64 / 1e8).to_radians(), (a.1 as f64 / 1e8).to_radians());
    let (lat2, lon2) = ((b.0 as f64 / 1e8).to_radians(), (b.1 as f64 / 1e8).to_radians());
    let h = ((lat2 - lat1) / 2.0).sin().powi(2)
        + lat1.cos() * lat2.cos() * ((lon2 - lon1) / 2.0).sin().powi(2);
    2.0 * 6_371_000.0 * h.sqrt().asin()
}

fn to_record(f: &CellFix) -> Option<Record> {
    let id = CellId {
        mcc: u16::try_from(f.mcc).ok()?,
        mnc: u16::try_from(f.mnc).ok()?,
        radio: RadioType::Lte,
        cell_id: u64::try_from(f.cell_id).ok()?,
        area: u32::try_from(f.area).ok()?,
    };
    if !keys::cell_fits(&id) {
        return None;
    }
    let accuracy_m = if f.accuracy_m < 0 {
        ACC_UNKNOWN
    } else {
        f.accuracy_m.min(i32::from(ACC_UNKNOWN - 1)) as u16
    };
    let r = Record { key: keys::cell_key(&id), lat_e8: f.lat_e8, lon_e8: f.lon_e8, accuracy_m, source: Source::Gsloc };
    r.in_range().then_some(r)
}

fn run(args: &Args) -> std::io::Result<()> {
    let file = OpenOptions::new().create(true).append(true).open(&args.out)?;
    let mut writer = RecordWriter::new(BufWriter::new(file));
    let mut client = Client::new(args.interval);

    // Seeds go first regardless of distance, in the order given; everything learned later is
    // ordered by distance from the first seed that resolved.
    let mut seeds: Vec<(u32, u32, u32, u32)> = args.seeds.clone();
    seeds.reverse();
    let mut frontier: BinaryHeap<(Reverse<u64>, (u32, u32, u32, u32))> = BinaryHeap::new();
    let mut queried: HashSet<(u32, u32, u32, u32)> = HashSet::new();
    let mut written: HashSet<u128> = HashSet::new();
    let mut center: Option<(i64, i64)> = None;
    let (mut queries, mut errors) = (0u32, 0u32);

    while queries < args.max_queries {
        let Some(next) = seeds.pop().or_else(|| frontier.pop().map(|(_, c)| c)) else { break };
        if !queried.insert(next) {
            continue;
        }
        let (mcc, mnc, tac, cid) = next;
        queries += 1;
        let fixes = match client.query_cell(mcc, mnc, cid, tac) {
            Ok(f) => {
                errors = 0;
                f
            }
            Err(e) => {
                errors += 1;
                eprintln!("query {queries}: {mcc}/{mnc} tac {tac} cid {cid}: {e}");
                if errors >= MAX_CONSECUTIVE_ERRORS {
                    eprintln!("giving up after {errors} consecutive errors");
                    break;
                }
                continue;
            }
        };

        let asked = fixes.iter().find(|f| f.cell_id == i64::from(cid) && f.area == tac as i32);
        if center.is_none() {
            center = asked.map(|f| (f.lat_e8, f.lon_e8));
        }
        let mut fresh = 0;
        for f in &fixes {
            let Some(r) = to_record(f) else { continue };
            if written.insert(r.key) {
                writer.push(&r)?;
                fresh += 1;
            }
            if let (Some(c), Ok(m), Ok(n), Ok(t), Ok(i)) =
                (center, u32::try_from(f.mcc), u32::try_from(f.mnc), u32::try_from(f.area), u32::try_from(f.cell_id))
            {
                let d = distance_m(c, (f.lat_e8, f.lon_e8));
                if d <= args.radius_m && !queried.contains(&(m, n, t, i)) {
                    frontier.push((Reverse(d as u64), (m, n, t, i)));
                }
            }
        }
        println!(
            "query {queries}: {mcc}/{mnc} tac {tac} cid {cid} -> {} cells ({fresh} new){}; {} total, {} queued",
            fixes.len(),
            if asked.is_some() { "" } else { ", asked-for cell unknown" },
            written.len(),
            frontier.len(),
        );
    }
    let n = writer.finish()?;
    println!("Done: {queries} request(s), {n} cell record(s) appended to {}", args.out.display());
    Ok(())
}

fn main() -> ExitCode {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    let args = match parse_args(&argv) {
        Ok(a) => a,
        Err(e) => {
            if e != "help" {
                eprintln!("error: {e}");
            }
            eprintln!("{USAGE}");
            return if e == "help" { ExitCode::SUCCESS } else { ExitCode::FAILURE };
        }
    };
    match run(&args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}
