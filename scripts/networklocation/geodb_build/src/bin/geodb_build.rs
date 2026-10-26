//! `geodb_build IN.osm.pbf --out geocoder.db [--bbox BOX] [--threads N]`

use std::path::PathBuf;
use std::process::ExitCode;

use geodb_build::{commas, extract, human_bytes, write};
use osm_ingest::bbox::BBox;

/// An allocator decides where, never what, so this cannot change a byte of the output.
#[global_allocator]
static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;

const USAGE: &str = "\
Usage: geodb_build IN.osm.pbf --out FILE [--bbox W,S,E,N] [--threads N] [--no-verify]

  --out        geocoder.db to write
  --bbox       clip to a box, for a metro or region rehearsal
  --threads    worker threads (default: all cores, or MAPS_THREADS)
  --no-verify  skip the read-back check (not recommended)";

struct Args {
    input: PathBuf,
    out: PathBuf,
    bbox: Option<BBox>,
    threads: Option<usize>,
    verify: bool,
}

fn parse_args(argv: &[String]) -> Result<Args, String> {
    let mut input = None;
    let mut out = None;
    let mut bbox = None;
    let mut threads = None;
    let mut verify = true;

    let mut i = 0;
    while i < argv.len() {
        let a = argv[i].as_str();
        let value = || argv.get(i + 1).cloned().ok_or_else(|| format!("{a} needs a value"));
        match a {
            "--out" => {
                out = Some(PathBuf::from(value()?));
                i += 2;
            }
            "--bbox" => {
                bbox = Some(BBox::parse(&value()?).map_err(|e| e.0)?);
                i += 2;
            }
            "--threads" => {
                threads = Some(osm_ingest::par::parse_threads(&value()?)?);
                i += 2;
            }
            "--no-verify" => {
                verify = false;
                i += 1;
            }
            "-h" | "--help" => return Err("help".to_string()),
            other if other.starts_with('-') => return Err(format!("unknown option: {other}")),
            other => {
                if input.replace(PathBuf::from(other)).is_some() {
                    return Err("more than one input given".to_string());
                }
                i += 1;
            }
        }
    }
    Ok(Args {
        input: input.ok_or("an input .osm.pbf is required")?,
        out: out.ok_or("--out is required")?,
        bbox,
        threads,
        verify,
    })
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
    if let Some(n) = args.threads {
        osm_ingest::par::set_threads(n);
    }
    let _ = rayon::ThreadPoolBuilder::new()
        .num_threads(osm_ingest::par::threads())
        .build_global();

    let started = std::time::Instant::now();
    let (mut rows, mut strings, stats) = match extract::extract(&args.input, args.bbox.as_ref()) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("error: {e}");
            return ExitCode::FAILURE;
        }
    };
    println!(
        "Extracted {} row(s) in {:.1}s",
        commas(stats.rows()),
        started.elapsed().as_secs_f64()
    );
    println!(
        "  {} address, {} street sample, {} POI, {} place",
        commas(stats.addresses),
        commas(stats.streets),
        commas(stats.pois),
        commas(stats.places)
    );
    println!(
        "  from {} node(s), {} way(s), {} relation(s); {} incomplete, {} outside bbox",
        commas(stats.from_nodes),
        commas(stats.from_ways),
        commas(stats.from_relations),
        commas(stats.incomplete),
        commas(stats.outside_bbox)
    );

    let t = std::time::Instant::now();
    let report = match write::write(&args.out, &mut rows, &mut strings) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("error: writing {}: {e}", args.out.display());
            return ExitCode::FAILURE;
        }
    };
    println!("Wrote {} in {:.1}s", args.out.display(), t.elapsed().as_secs_f64());
    println!(
        "  {} ({} records, {:.1} bits/record)",
        human_bytes(report.total),
        commas(report.n),
        report.bits_per_record()
    );
    for (name, bytes) in report.dicts.iter().chain(report.columns.iter()) {
        println!("    {name:<9} {:>12}", human_bytes(*bytes));
    }
    println!("    {:<9} {:>12}", "grid", human_bytes(report.grid));
    println!("    {:<9} {:>12}", "fwd", human_bytes(report.fwd));
    println!("    {:<9} {:>12}", "nameidx", human_bytes(report.name_index));
    for (name, count) in &report.dict_counts {
        println!("    dict {name:<9} {:>12} distinct", commas(*count));
    }

    if args.verify {
        let t = std::time::Instant::now();
        if let Err(e) = write::verify(&args.out, &rows) {
            eprintln!("error: verification failed: {e}");
            return ExitCode::FAILURE;
        }
        println!("Verified {} record(s) in {:.1}s", commas(report.n), t.elapsed().as_secs_f64());
    } else {
        eprintln!("WARNING: --no-verify; the database was not read back");
    }

    println!("Peak RSS: {}", osm_ingest::mem::peak_rss_report());
    println!("Total {:.1}s", started.elapsed().as_secs_f64());
    ExitCode::SUCCESS
}
