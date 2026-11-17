//! Decode COP90 (Copernicus 90 m DSM) GeoTIFF tiles into a per-map-tile `u16`
//! heightmap grid dataset — the build half of WS-G's 3D terrain relief.
//!
//! Given the COP90 tile directory (`inputs/cop90/COP90_hh/` of
//! `Copernicus_DSM_30_*_DEM.tif` Float32 tiles plus the `COP90_hh.vrt` mosaic)
//! and a bounding box, it produces one small square `u16` grid per output map
//! tile. Samples are metres above sea level **biased by 32768** — the same
//! convention [`tilecodec::mamaps::body::Heightmap`] stores on the wire.
//!
//! Each 1°×1° tile file is found by flooring the sample lon/lat to its
//! south-west corner and sampled nearest-neighbour through its own GeoTIFF tags
//! (pixel scale + tiepoint). Missing files, nodata cells and NaNs read as sea
//! level. Network I/O lives in the build scripts; this crate's only raster
//! dependency is the pure-Rust `tiff` crate — no native GDAL.
//!
//! Output is a single `.mdem` dataset (see `dataset.rs`): a header, then per
//! non-empty tile a pmtiles tile id and its `dim * dim` samples, row-major.
//! Ocean/flat tiles are omitted by default (`--skip-flat` on;
//! `--no-skip-flat` keeps them), matching the wire format's rule that an
//! elevation-free tile carries no section.
//!
//! COP90 source tiles are independent, so sampling runs across a rayon pool:
//! the ascending output-id range is split into contiguous chunks (neighbours
//! share DEM tiles, so each chunk's own cache stays warm), sampled in parallel,
//! then the kept grids are merged back in ascending id order and streamed out —
//! byte-identical to a serial walk.

use std::path::{Path, PathBuf};

use rayon::prelude::*;
use tilecodec::pmtiles::{tile_zxy, zoom_base};

mod geotags;

mod dataset;
use dataset::{finish_dataset, open_dataset, write_tile};

mod progress;
use progress::fmt_hms;

mod sample;
use sample::{sample_tile, DemCache};

mod tilemath;
use tilemath::{lat_to_tile_y, lon_to_tile_x};

/// One kept tile: pmtiles tile id plus its `dim * dim` height samples.
type TileGrid = (u64, Vec<u16>);
/// Per-chunk sample result: kept tiles plus the count with no DEM coverage.
type ChunkResult = (Vec<TileGrid>, u64);

/// Default DEM input, relative to the repo's `scripts/maps/` working dir.
const DEFAULT_INPUT: &str = "inputs/cop90/COP90_hh.vrt";

fn main() {
    if let Err(e) = run() {
        eprintln!("dem_ingest: {e}");
        std::process::exit(1);
    }
}

struct Args {
    dem_dir: PathBuf,
    out: PathBuf,
    out_zoom: u8,
    dim: u16,
    bbox: (f64, f64, f64, f64),
    skip_flat: bool,
}

fn run() -> Result<(), String> {
    let args = parse_args()?;
    if args.dim < 2 {
        return Err("--dim must be at least 2".to_string());
    }
    let dem_dir = resolve_dem_dir(&args.dem_dir)?;
    let n = 1u64 << args.out_zoom;
    // The output tile range the bbox covers, clamped to the world.
    let (min_lon, min_lat, max_lon, max_lat) = args.bbox;
    let x0 = lon_to_tile_x(min_lon, args.out_zoom).floor().max(0.0) as u64;
    let x1 = lon_to_tile_x(max_lon, args.out_zoom).ceil().min(n as f64) as u64;
    // Latitude runs the other way: max_lat is the smaller tile y.
    let y0 = lat_to_tile_y(max_lat, args.out_zoom).floor().max(0.0) as u64;
    let y1 = lat_to_tile_y(min_lat, args.out_zoom).ceil().min(n as f64) as u64;
    if x1 <= x0 || y1 <= y0 {
        return Err(format!(
            "the bbox {min_lon},{min_lat},{max_lon},{max_lat} covers no z{} tiles",
            args.out_zoom
        ));
    }

    // Output tile ids the bbox covers, in ascending id order (Hilbert ids aren't
    // row-major, so the id range is walked and mapped back to x/y). This is the
    // exact order the .mdem body must end up in.
    let (base, end) = (zoom_base(args.out_zoom), zoom_base(args.out_zoom + 1));
    let mut ids = Vec::new();
    for id in base..end {
        let (_, x, y) = tile_zxy(id);
        if x < x0 || x >= x1 || y < y0 || y >= y1 {
            continue;
        }
        ids.push(id);
    }
    let total = ids.len() as u64;

    // Sample in parallel. Each contiguous chunk gets its own DemCache and returns
    // the tiles it kept plus how many had no DEM coverage; chunks come back in id
    // order, so the flattened result is already ascending.
    let dim = args.dim as usize;
    let skip_flat = args.skip_flat;
    let threads = rayon::current_num_threads().max(1);
    let chunk_size = (ids.len() / (threads * 4)).max(1);
    let done = std::sync::atomic::AtomicU64::new(0);
    let start = std::time::Instant::now();

    let chunks: Vec<ChunkResult> = ids
        .par_chunks(chunk_size)
        .map(|chunk| -> Result<ChunkResult, String> {
            let mut cache = DemCache::new();
            let mut kept: Vec<TileGrid> = Vec::new();
            let mut missing = 0u64;
            for &id in chunk {
                let (_, x, y) = tile_zxy(id);
                match sample_tile(&dem_dir, x, y, args.out_zoom, dim, &mut cache)? {
                    Some(grid) if !(skip_flat && is_flat(&grid)) => kept.push((id, grid)),
                    Some(_) => {}
                    None => missing += 1,
                }
                let d = done.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1;
                if d % 10000 == 0 || d == total {
                    tick(d, total, start);
                }
            }
            Ok((kept, missing))
        })
        .collect::<Result<Vec<_>, String>>()?;
    eprintln!();

    // Merge in chunk (ascending id) order; the sort keeps the body byte-identical
    // to the serial walk regardless of how chunks were scheduled.
    let mut tiles: Vec<TileGrid> = Vec::new();
    let mut missing = 0u64;
    for (kept, chunk_missing) in chunks {
        tiles.extend(kept);
        missing += chunk_missing;
    }
    tiles.sort_by_key(|(id, _)| *id);

    // Stream to disk: header with a zero count, one record per tile, then the
    // real count patched into the header (see dataset.rs).
    let mut out = open_dataset(&args.out, args.out_zoom, args.dim)?;
    let mut written = 0u32;
    for (id, grid) in &tiles {
        write_tile(&mut out, *id, grid)?;
        written += 1;
    }
    finish_dataset(out, written)?;
    println!(
        "dem_ingest: wrote {} tile(s) at z{} ({}x{}) to {} ({} output tile(s) had no DEM coverage)",
        written,
        args.out_zoom,
        args.dim,
        args.dim,
        args.out.display(),
        missing,
    );
    Ok(())
}

/// Redraw the stderr progress line for `done`/`total` tiles.
fn tick(done: u64, total: u64, start: std::time::Instant) {
    let el = start.elapsed().as_secs_f64();
    let rate = done as f64 / el.max(1e-6);
    eprint!(
        "\rdem_ingest: {done}/{total} ({:.1}%) elapsed {} eta {} {:.1} tiles/s",
        done as f64 / total as f64 * 100.0,
        fmt_hms(el),
        fmt_hms((total - done) as f64 / rate.max(1e-6)),
        rate
    );
    let _ = std::io::Write::flush(&mut std::io::stderr());
}

/// A grid is "flat" when every sample is within ±2 m of one level; omitted as relief-free.
fn is_flat(grid: &[u16]) -> bool {
    let (mut lo, mut hi) = (u16::MAX, u16::MIN);
    for &s in grid {
        lo = lo.min(s);
        hi = hi.max(s);
    }
    hi.saturating_sub(lo) <= 2
}

/// Accept either the tile directory or the `.vrt` mosaic path: a `.vrt` file
/// resolves to the `COP90_hh` sibling its sources live in.
fn resolve_dem_dir(input: &Path) -> Result<PathBuf, String> {
    if input.is_dir() {
        return Ok(input.to_path_buf());
    }
    if input.is_file() {
        if let Some(parent) = input.parent() {
            let sibling = parent.join("COP90_hh");
            if sibling.is_dir() {
                return Ok(sibling);
            }
            return Ok(parent.to_path_buf());
        }
    }
    Err(format!(
        "no COP90 tiles at {} (want the COP90_hh directory or the COP90_hh.vrt mosaic)",
        input.display()
    ))
}

// --- CLI ---

fn parse_args() -> Result<Args, String> {
    let mut tiles_dir: Option<PathBuf> = None;
    let mut out: Option<PathBuf> = None;
    let mut out_zoom = 12u8;
    let mut dim = 9u16;
    let mut bbox: Option<(f64, f64, f64, f64)> = None;
    let mut skip_flat = true;
    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        match a.as_str() {
            // The COP90 tile directory, or the COP90_hh.vrt mosaic (the default);
            // kept under the old flag name so existing invocations still parse.
            "--tiles-dir" => tiles_dir = Some(PathBuf::from(next(&mut it, "--tiles-dir")?)),
            "--out" => out = Some(PathBuf::from(next(&mut it, "--out")?)),
            "--out-zoom" => out_zoom = next(&mut it, "--out-zoom")?.parse().map_err(|_| "--out-zoom must be a number")?,
            // Accepted for compatibility; COP90 samples at its native ~90 m everywhere.
            "--dem-zoom" => {
                next(&mut it, "--dem-zoom")?;
            }
            "--dim" => dim = next(&mut it, "--dim")?.parse().map_err(|_| "--dim must be a number")?,
            "--bbox" => bbox = Some(parse_bbox(&next(&mut it, "--bbox")?)?),
            "--skip-flat" => skip_flat = true,
            "--no-skip-flat" => skip_flat = false,
            "-h" | "--help" => {
                print_help();
                std::process::exit(0);
            }
            other => return Err(format!("unknown argument {other} (try --help)")),
        }
    }
    Ok(Args {
        dem_dir: tiles_dir.unwrap_or_else(|| PathBuf::from(DEFAULT_INPUT)),
        out: out.ok_or("--out is required")?,
        out_zoom,
        dim,
        bbox: bbox.ok_or("--bbox is required")?,
        skip_flat,
    })
}

fn next(it: &mut impl Iterator<Item = String>, flag: &str) -> Result<String, String> {
    it.next().ok_or_else(|| format!("{flag} needs a value"))
}

fn parse_bbox(s: &str) -> Result<(f64, f64, f64, f64), String> {
    let parts: Vec<f64> = s
        .split(',')
        .map(|p| p.trim().parse::<f64>().map_err(|_| format!("bad bbox component `{p}`")))
        .collect::<Result<_, _>>()?;
    match parts.as_slice() {
        [min_lon, min_lat, max_lon, max_lat] => Ok((*min_lon, *min_lat, *max_lon, *max_lat)),
        _ => Err("--bbox is minlon,minlat,maxlon,maxlat".to_string()),
    }
}

fn print_help() {
    println!(
        "dem_ingest --bbox minlon,minlat,maxlon,maxlat --out FILE [--tiles-dir DIR]\n\
         \n\
         Decode COP90 GeoTIFF tiles (COP90_hh/Copernicus_DSM_30_*_DEM.tif, Float32) into a\n\
         per-map-tile u16 heightmap grid dataset (metres + 32768 bias).\n\
         \n\
         Options:\n\
         \x20 --tiles-dir DIR   COP90_hh tile directory or the COP90_hh.vrt mosaic\n\
         \x20                   (default {DEFAULT_INPUT})\n\
         \x20 --bbox BOX        minlon,minlat,maxlon,maxlat to cover (required)\n\
         \x20 --out FILE        the .mdem dataset to write (required)\n\
         \x20 --out-zoom Z      map tile zoom to sample a grid per (default 12)\n\
         \x20 --dem-zoom Z      accepted for compatibility; COP90 is ~90 m everywhere\n\
         \x20 --dim N           grid side length, N*N samples per tile (default 9)\n\
         \x20 --skip-flat       omit tiles with no relief (ocean/flat), matching the wire format\n\
         \x20                   (default on)\n\
         \x20 --no-skip-flat    keep flat tiles too: a world run without it needs tens of GB\n"
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn is_flat_omits_a_level_grid_but_keeps_relief() {
        assert!(is_flat(&vec![32768; 289]), "an all-sea-level grid is flat");
        let mut relief = vec![32768u16; 289];
        relief[100] = 32768 + 50;
        assert!(!is_flat(&relief), "50 m of relief is not flat");
    }
}
