//! `road_overlay --graph DIR [--out FILE] [--threads N]` - build the highway overlay
//! (`overlay.bin`, default `DIR/overlay.bin`) from a finished v7 graph dir.
//!
//! See `osm_ingest::overlay` for the layout.
use std::path::PathBuf;
use std::process::ExitCode;

const USAGE: &str =
    "Usage: road_overlay --graph DIR [--out FILE] [--threads N] (default --out DIR/overlay.bin; --threads beats MAPS_THREADS beats the box)";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let flag = |name: &str| args.iter().position(|a| a == name).and_then(|i| args.get(i + 1)).cloned();
    let Some(dir) = flag("--graph").map(PathBuf::from) else {
        eprintln!("{USAGE}");
        return ExitCode::FAILURE;
    };
    if let Some(v) = flag("--threads") {
        match osm_ingest::par::parse_threads(&v) {
            Ok(n) => osm_ingest::par::set_threads(n),
            Err(e) => {
                eprintln!("road_overlay: {e}");
                return ExitCode::FAILURE;
            }
        }
    }
    let out = flag("--out").map(PathBuf::from).unwrap_or_else(|| dir.join("overlay.bin"));
    let started = std::time::Instant::now();
    match osm_ingest::overlay::build(&dir, &out) {
        Ok(s) => {
            println!(
                "overlay: {} of {} nodes, {} of {} edges, {} bytes -> {} ({:.1} s)",
                s.nodes,
                s.full_nodes,
                s.edges,
                s.full_edges,
                s.bytes,
                out.display(),
                started.elapsed().as_secs_f64(),
            );
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("road_overlay: {e}");
            ExitCode::FAILURE
        }
    }
}
