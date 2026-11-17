//! `wps_build --kind wifi|cell --out STORE --scratch DIR SHARD...`
//!
//! Merge every record shard into one `WPSDB2` store: sort by key, reduce each beacon's
//! observations to at most one record, then write and verify.
//!
//! Two passes over the sorted data, because the Elias-Fano index needs the record count
//! before it can choose its parameters. The first pass writes the reduced records to a scratch
//! shard and counts them; the second streams that shard into the writer. Nothing proportional
//! to the record count is ever held in memory.

use std::fs::File;
use std::io::{BufReader, BufWriter};
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use wps_harvest::keys;
use wps_harvest::quality::{self, Policy, Rejects};
use wps_harvest::record::{Record, RecordReader, RecordWriter};
use wps_harvest::sort::ExternalSort;
use wps_harvest::store::{self, KeyKind, DEFAULT_SAMPLE_LOG2};

const USAGE: &str = "\
Usage: wps_build --kind wifi|cell --out STORE --scratch DIR [options] SHARD...

  --kind             which store to build
  --out              .wpsdb path to write
  --scratch          directory for sort runs and intermediates (needs ~2x the store size)
  --mover-threshold  metres; a beacon seen further apart than this is dropped as mobile
                     (default 1000 for wifi, 20000 for cell)
  --max-accuracy     metres; drop observations less precise than this (default: keep all)
  --run-records      records held in memory per sort run (default 4000000, ~160 MiB)
  --no-verify        skip the read-back check (not recommended)";

struct Args {
    kind: KeyKind,
    out: PathBuf,
    scratch: PathBuf,
    policy: Policy,
    run_records: usize,
    verify: bool,
    shards: Vec<PathBuf>,
}

fn parse_args(argv: &[String]) -> Result<Args, String> {
    let mut kind = None;
    let mut out = None;
    let mut scratch = None;
    let mut mover = None;
    let mut max_accuracy = None;
    let mut run_records = 4_000_000usize;
    let mut verify = true;
    let mut shards = Vec::new();

    let mut i = 0;
    while i < argv.len() {
        let a = argv[i].as_str();
        let value = || {
            argv.get(i + 1).cloned().ok_or_else(|| format!("{a} needs a value"))
        };
        match a {
            "--kind" => {
                kind = Some(match value()?.as_str() {
                    "wifi" => KeyKind::Wifi,
                    "cell" => KeyKind::Cell,
                    other => return Err(format!("unknown --kind '{other}'")),
                });
                i += 2;
            }
            "--out" => {
                out = Some(PathBuf::from(value()?));
                i += 2;
            }
            "--scratch" => {
                scratch = Some(PathBuf::from(value()?));
                i += 2;
            }
            "--mover-threshold" => {
                mover = Some(value()?.parse::<u32>().map_err(|e| e.to_string())?);
                i += 2;
            }
            "--max-accuracy" => {
                max_accuracy = Some(value()?.parse::<u16>().map_err(|e| e.to_string())?);
                i += 2;
            }
            "--run-records" => {
                run_records = value()?.parse::<usize>().map_err(|e| e.to_string())?;
                i += 2;
            }
            "--no-verify" => {
                verify = false;
                i += 1;
            }
            "-h" | "--help" => return Err("help".to_string()),
            other if other.starts_with("--") => return Err(format!("unknown option: {other}")),
            other => {
                shards.push(PathBuf::from(other));
                i += 1;
            }
        }
    }

    let kind = kind.ok_or("--kind is required")?;
    let mut policy = match kind {
        KeyKind::Wifi => Policy::wifi(),
        KeyKind::Cell => Policy::cell(),
    };
    if let Some(m) = mover {
        policy.mover_threshold_m = m;
    }
    policy.max_accuracy_m = max_accuracy;

    if shards.is_empty() {
        return Err("at least one input shard is required".to_string());
    }
    Ok(Args {
        kind,
        out: out.ok_or("--out is required")?,
        scratch: scratch.ok_or("--scratch is required")?,
        policy,
        run_records,
        verify,
        shards,
    })
}

/// Sort every shard, reduce each key's observations, and write the survivors to `reduced`.
/// Returns the record count and what was rejected.
fn sort_and_reduce(
    shards: &[PathBuf],
    scratch: &Path,
    policy: &Policy,
    run_records: usize,
    reduced: &Path,
) -> std::io::Result<(u64, u64, Rejects)> {
    let mut sorter = ExternalSort::new(scratch, run_records)?;
    for path in shards {
        let mut rd = RecordReader::new(BufReader::with_capacity(1 << 20, File::open(path)?));
        while let Some(r) = rd.next().transpose()? {
            sorter.push(r)?;
        }
        println!("  read {} ({} observations so far)", path.display(), sorter.len());
    }
    let observations = sorter.len();

    let mut merge = sorter.finish()?;
    let mut writer = RecordWriter::new(BufWriter::with_capacity(1 << 20, File::create(reduced)?));
    let mut rejects = Rejects::default();

    // Equal keys arrive adjacent, so one group is one beacon and the whole group is visible at
    // once - which is what makes the mover test possible at all.
    let mut group: Vec<Record> = Vec::new();
    let flush = |group: &mut Vec<Record>,
                     writer: &mut RecordWriter<BufWriter<File>>,
                     rejects: &mut Rejects|
     -> std::io::Result<()> {
        if let Some(r) = quality::reduce(group, policy, rejects) {
            writer.push(&r)?;
        }
        group.clear();
        Ok(())
    };

    while let Some(r) = merge.next().transpose()? {
        if group.first().is_some_and(|g| g.key != r.key) {
            flush(&mut group, &mut writer, &mut rejects)?;
        }
        group.push(r);
    }
    flush(&mut group, &mut writer, &mut rejects)?;

    let kept = writer.finish()?;
    Ok((observations, kept, rejects))
}

fn run(args: &Args) -> std::io::Result<()> {
    std::fs::create_dir_all(&args.scratch)?;
    let reduced = args.scratch.join("reduced.shard");

    let started = std::time::Instant::now();
    let (observations, kept, rejects) = sort_and_reduce(
        &args.shards,
        &args.scratch,
        &args.policy,
        args.run_records,
        &reduced,
    )?;

    println!(
        "{observations} observation(s) -> {kept} beacon(s) in {:.1}s",
        started.elapsed().as_secs_f64()
    );
    println!(
        "  rejected {} total: {} randomized MAC, {} mobile, {} out of range, {} too coarse",
        rejects.total(),
        rejects.randomized_mac,
        rejects.mover,
        rejects.out_of_range,
        rejects.too_coarse
    );

    let universe_bits = match args.kind {
        KeyKind::Wifi => keys::WIFI_UNIVERSE_BITS,
        KeyKind::Cell => keys::CELL_UNIVERSE_BITS,
    };

    // Reduced records are already sorted and unique, which is what the writer requires. They
    // are streamed back off disk rather than held: at planet scale this list is tens of GB.
    let size = store::write_store(
        &args.out,
        &args.scratch,
        args.kind,
        universe_bits,
        kept,
        DEFAULT_SAMPLE_LOG2,
        stream_shard(&reduced)?,
    )?;
    println!("Wrote {} ({})", args.out.display(), wps_harvest::human_bytes(size));
    if kept > 0 {
        println!("  {:.1} bits per beacon", (size as f64 * 8.0) / kept as f64);
    }

    if args.verify {
        let t = std::time::Instant::now();
        let n = store::verify_store(&args.out, stream_shard(&reduced)?)?;
        println!("Verified {n} record(s) in {:.1}s", t.elapsed().as_secs_f64());
    } else {
        eprintln!("WARNING: --no-verify; the store was not read back");
    }

    let _ = std::fs::remove_file(&reduced);
    Ok(())
}

/// Stream a shard as the `io::Result<Record>` sequence the store writer and verifier take.
fn stream_shard(path: &Path) -> std::io::Result<impl Iterator<Item = std::io::Result<Record>>> {
    let rd = RecordReader::new(BufReader::with_capacity(1 << 20, File::open(path)?));
    Ok(rd)
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
