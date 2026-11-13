//! `mamaps_probe` — read a handful of tiles out of a `.mamaps` archive by range.
//!
//! `mamaps_dump` reads the whole file into RAM, which dies on a 60 GB archive.
//! This opens the archive over a file-backed [`RangeReader`] and prints one line
//! per requested tile: which `landtype` kinds it carries and how much ground
//! each covers. Built for the Santa Cruz water/land question — the ocean below
//! Santa Cruz rendered as land past ~z7 with only rivers showing as water.
//!
//! Usage:
//!   mamaps_probe IN.mamaps Z/X/Y [Z/X/Y ...]
//!   mamaps_probe IN.mamaps --breakdown [SAMPLES_PER_ZOOM]
//!
//! `--breakdown` walks the whole leaf index (cheap: index only, no bodies) and
//! prints per-zoom: addressed tiles, stored bodies, stored (DEFLATE) bytes, plus
//! — from up to SAMPLES_PER_ZOOM sampled bodies per zoom (default 200) — the
//! share of bodies carrying a heightmap, the mean raw body size, and the mean
//! raw heightmap section size. That answers "how much space does height take".

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::process::ExitCode;

use tile_build::mamaps::body::{Body, GEOM_POLYGON};
use tile_build::mamaps::dict::Dictionary;
use tile_build::mamaps::read::MamapsArchive;
use tile_build::proto::{err, Result};
use tilecodec::stream::RangeReader;

struct FileRanges {
    file: std::sync::Mutex<File>,
}

impl RangeReader for FileRanges {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        let mut file = self.file.lock().map_err(|_| tile_build::proto::Error("lock".into()))?;
        file.seek(SeekFrom::Start(offset))
            .map_err(|e| tile_build::proto::Error(format!("seek {offset}: {e}")))?;
        let mut buf = vec![0u8; length as usize];
        let mut got = 0usize;
        while got < buf.len() {
            match file.read(&mut buf[got..]) {
                Ok(0) => break,
                Ok(n) => got += n,
                Err(e) => return err(format!("read at {offset}: {e}")),
            }
        }
        buf.truncate(got);
        Ok(buf)
    }
}

fn kind_name(dict: &Dictionary, kind: u16) -> &str {
    if kind == 0 {
        return "(land-base)";
    }
    dict.kind_name(kind).unwrap_or("?")
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 3 {
        eprintln!("usage: mamaps_probe IN.mamaps Z/X/Y [Z/X/Y ...]");
        eprintln!("   or: mamaps_probe IN.mamaps --breakdown [SAMPLES_PER_ZOOM]");
        return ExitCode::from(2);
    }
    let file = match File::open(&args[1]) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("mamaps_probe: cannot open {}: {e}", args[1]);
            return ExitCode::from(1);
        }
    };
    if args[2] == "--breakdown" {
        let samples: usize = args.get(3).and_then(|s| s.parse().ok()).unwrap_or(200);
        return breakdown(&args[1], samples);
    }
    if args[2] == "--layers" {
        let samples: usize = args.get(3).and_then(|s| s.parse().ok()).unwrap_or(200);
        return layer_breakdown(&args[1], samples);
    }
    if args[2] == "--sidecars" {
        return sidecars(&args[1]);
    }
    let archive = match MamapsArchive::open(FileRanges { file: std::sync::Mutex::new(file) }) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("mamaps_probe: open: {e}");
            return ExitCode::from(1);
        }
    };
    println!(
        "archive z{}-{} build_id {:#018x} validated={}",
        archive.header.min_zoom,
        archive.header.max_zoom,
        archive.header.build_id,
        archive.header.rings_validated(),
    );
    let mut code = ExitCode::SUCCESS;
    for spec in &args[2..] {
        let mut parts = spec.split('/');
        let tile = match (parts.next(), parts.next(), parts.next(), parts.next()) {
            (Some(z), Some(x), Some(y), None) => {
                match (z.parse::<u8>(), x.parse::<u32>(), y.parse::<u32>()) {
                    (Ok(z), Ok(x), Ok(y)) => (z, x, y),
                    _ => {
                        eprintln!("mamaps_probe: bad tile '{spec}', want Z/X/Y");
                        code = ExitCode::from(2);
                        continue;
                    }
                }
            }
            _ => {
                eprintln!("mamaps_probe: bad tile '{spec}', want Z/X/Y");
                code = ExitCode::from(2);
                continue;
            }
        };
        let (z, x, y) = tile;
        match archive.tile(z, x, y) {
            Err(e) => {
                println!("{z}/{x}/{y}\tERROR {e}");
                code = ExitCode::from(1);
            }
            Ok(None) => println!("{z}/{x}/{y}\tABSENT"),
            Ok(Some(body)) => print_tile(&archive.dictionary, z, x, y, &body),
        }
    }
    code
}

fn print_tile(dict: &Dictionary, z: u8, x: u32, y: u32, body: &Body) {
    let extent = body.extent.max(1) as f64;
    let full = extent * extent;
    let Some(layer) = body.layer(tile_build::mamaps::dict::LAYER_LANDTYPE) else {
        println!("{z}/{x}/{y}\tno landtype layer");
        return;
    };
    // Per-kind ground: sum of absolute ring areas, so holes add rather than cancel.
    let mut kinds: std::collections::BTreeMap<u16, (usize, f64)> =
        std::collections::BTreeMap::new();
    let mut feats = 0usize;
    for feature in &layer.features {
        if feature.geom_type != GEOM_POLYGON {
            continue;
        }
        feats += 1;
        let mut area = 0.0f64;
        for part in layer.parts_of(feature) {
            area += ring_area(layer.points(part)).abs();
        }
        let e = kinds.entry(feature.kind).or_insert((0, 0.0));
        e.0 += 1;
        e.1 += area;
    }
    if kinds.is_empty() {
        println!("{z}/{x}/{y}\tlandtype: no polygons ({feats} polygon features)");
        return;
    }
    let mut bits: Vec<String> = Vec::new();
    for (kind, (count, area)) in &kinds {
        bits.push(format!(
            "{}x{}={:.1}%",
            kind_name(dict, *kind),
            count,
            100.0 * area / full
        ));
    }
    let dem = if body.heightmap.is_some() { " dem" } else { "" };
    println!("{z}/{x}/{y}\tlandtype: {}{}", bits.join(" "), dem);
}

fn ring_area(pts: &[(i16, i16)]) -> f64 {
    let n = pts.len();
    if n < 3 {
        return 0.0;
    }
    let mut twice: i64 = 0;
    for i in 0..n {
        let (x1, y1) = pts[i];
        let (x2, y2) = pts[(i + 1) % n];
        twice += x1 as i64 * y2 as i64 - x2 as i64 * y1 as i64;
    }
    twice as f64 / 2.0
}

/// Walk the whole archive and report per-zoom storage: addressed tiles (with
/// run-length expansion), stored bodies, stored (DEFLATE) bytes, plus sampled
/// body stats — the heightmap share and the raw (pre-DEFLATE) body/heightmap
/// sizes that say how much of each zoom is elevation.
///
/// The index walk reads only the root (already in the open prefix) plus one
/// read per leaf — kilobytes against a 60 GB file. Bodies are fetched only for
/// the sample: evenly spaced entries per zoom, so one sample per ~N bodies,
/// each a single range read plus inflate.
fn breakdown(path: &str, samples_per_zoom: usize) -> ExitCode {
    use tile_build::mamaps::{header, index};
    use tilecodec::pmtiles::tile_zxy;

    let file = match File::open(path) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("mamaps_probe: cannot open {path}: {e}");
            return ExitCode::from(1);
        }
    };
    let ranges = FileRanges { file: std::sync::Mutex::new(file) };
    // Header + dictionary + root straight out of the open prefix.
    let prefix = match ranges.read(0, tilecodec::stream::OPEN_PREFIX_BYTES) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("mamaps_probe: open: {e}");
            return ExitCode::from(1);
        }
    };
    let hdr = match header::Header::parse(&prefix) {
        Ok(h) => h,
        Err(e) => {
            eprintln!("mamaps_probe: header: {e}");
            return ExitCode::from(1);
        }
    };
    let root = match index::parse_root(
        &prefix[hdr.root_offset as usize..(hdr.root_offset + hdr.root_len as u64) as usize],
    ) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("mamaps_probe: root: {e}");
            return ExitCode::from(1);
        }
    };
    let file_len = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
    println!(
        "archive z{}-{} build_id {:#018x} validated={} compression={} file={:.2}GB data={:.2}GB bodies_written={} tiles_addressed={}",
        hdr.min_zoom,
        hdr.max_zoom,
        hdr.build_id,
        hdr.rings_validated(),
        hdr.compression,
        file_len as f64 / 1e9,
        hdr.data_len as f64 / 1e9,
        hdr.bodies_written,
        hdr.tiles_addressed,
    );
    // Per-zoom aggregates from the index alone.
    struct Zoom {
        addressed: u64,
        bodies: u64,
        stored: u64,
        entries: Vec<(u64, u32)>,
    }
    let mut zooms: std::collections::BTreeMap<u8, Zoom> = std::collections::BTreeMap::new();
    for root_entry in &root {
        let raw_len = root_entry.leaf_entry_count as u64 * index::LEAF_ENTRY_LEN as u64;
        let stored_len = if root_entry.stored_len == 0 { raw_len } else { root_entry.stored_len as u64 };
        let stored = match ranges.read(
            (hdr.leaf_offset + root_entry.leaf_offset) as u64,
            stored_len as u32,
        ) {
            Ok(r) => r,
            Err(e) => {
                eprintln!("mamaps_probe: leaf: {e}");
                return ExitCode::from(1);
            }
        };
        let raw = if root_entry.stored_len == 0 {
            stored
        } else {
            match index::decompress_leaf(&stored, raw_len as usize) {
                Ok(r) => r,
                Err(e) => {
                    eprintln!("mamaps_probe: leaf inflate: {e}");
                    return ExitCode::from(1);
                }
            }
        };
        let leaf = match index::parse_leaf(&raw) {
            Ok(l) => l,
            Err(e) => {
                eprintln!("mamaps_probe: leaf parse: {e}");
                return ExitCode::from(1);
            }
        };
        for e in &leaf {
            let (z, _, _) = tile_zxy(root_entry.base_tile_id + e.tile_id_lo as u64);
            let at = hdr.data_offset + root_entry.base_data_offset + e.offset_delta as u64;
            let zoom = zooms.entry(z).or_insert(Zoom {
                addressed: 0,
                bodies: 0,
                stored: 0,
                entries: Vec::new(),
            });
            zoom.addressed += e.run_length as u64;
            zoom.bodies += 1;
            zoom.stored += e.length as u64;
            zoom.entries.push((at, e.length));
        }
    }
    println!("zoom  addressed   bodies  stored_GB  avg_stored");
    let mut total_stored = 0u64;
    for (z, zoom) in &zooms {
        total_stored += zoom.stored;
        println!(
            "z{z:<3} {:>9} {:>8} {:>10.3} {:>8}",
            zoom.addressed,
            zoom.bodies,
            zoom.stored as f64 / 1e9,
            fmt_bytes(zoom.stored / zoom.bodies.max(1)),
        );
    }
    println!("total stored bodies: {:.3} GB", total_stored as f64 / 1e9);
    // Sampled bodies per zoom: heightmap share + raw sizes.
    let archive = match MamapsArchive::open(FileRanges {
        file: std::sync::Mutex::new(File::open(path).expect("open")),
    }) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("mamaps_probe: reopen: {e}");
            return ExitCode::from(1);
        }
    };
    println!("zoom  sampled  with_dem  dem_share  avg_raw_body  avg_raw_dem  dem_of_raw  stored_dem  dem_of_stored");
    for (z, zoom) in &zooms {
        if zoom.entries.is_empty() {
            continue;
        }
        let step = (zoom.entries.len() / samples_per_zoom.max(1)).max(1);
        let mut sampled = 0u64;
        let mut with_dem = 0u64;
        let mut raw_body = 0u64;
        let mut raw_dem = 0u64;
        let mut stored_dem = 0u64;
        let mut stored_nodem = 0u64;
        let mut idx = 0usize;
        while idx < zoom.entries.len() {
            let (at, len) = zoom.entries[idx];
            let stored = match archive.reader().read(at, len) {
                Ok(s) => s,
                Err(_) => {
                    idx += step;
                    continue;
                }
            };
            let raw = match tile_build::mamaps::read::decompress(hdr.compression, &stored) {
                Ok(r) => r,
                Err(_) => {
                    idx += step;
                    continue;
                }
            };
            let body = match Body::parse(&raw) {
                Ok(b) => b,
                Err(_) => {
                    idx += step;
                    continue;
                }
            };
            sampled += 1;
            raw_body += raw.len() as u64;
            if let Some(hm) = &body.heightmap {
                with_dem += 1;
                raw_dem += (2 + hm.samples.len() * 2) as u64;
                stored_dem += len as u64;
            } else {
                stored_nodem += len as u64;
            }
            idx += step;
        }
        if sampled == 0 {
            continue;
        }
        // Stored-bytes split: mean stored size of dem vs no-dem bodies, scaled to
        // the zoom's body count. DEFLATE-compressibility differs between the two
        // populations, so this is measured, not modelled.
        let dem_bodies = zoom.bodies as f64 * with_dem as f64 / sampled as f64;
        let nodem_bodies = zoom.bodies as f64 - dem_bodies;
        let avg_stored_dem = if with_dem > 0 { stored_dem / with_dem } else { 0 };
        let avg_stored_nodem =
            if sampled > with_dem { stored_nodem / (sampled - with_dem) } else { 0 };
        let stored_dem_total = dem_bodies * avg_stored_dem as f64;
        println!(
            "z{z:<3} {:>7} {:>8} {:>8.1}% {:>11} {:>10} {:>8.1}% {:>10} {:>6.1}%",
            sampled,
            with_dem,
            100.0 * with_dem as f64 / sampled as f64,
            fmt_bytes(raw_body / sampled),
            if with_dem > 0 {
                fmt_bytes(raw_dem / with_dem).to_string()
            } else {
                "-".to_string()
            },
            100.0 * raw_dem as f64 / raw_body.max(1) as f64,
            fmt_bytes(stored_dem_total as u64),
            100.0 * stored_dem_total / zoom.stored.max(1) as f64,
        );
    }
    ExitCode::SUCCESS
}

fn fmt_bytes(n: u64) -> String {
    if n >= 1_000_000 {
        format!("{:.1}M", n as f64 / 1e6)
    } else if n >= 1_000 {
        format!("{:.1}K", n as f64 / 1e3)
    } else {
        format!("{n}B")
    }
}

/// List the packed sidecar sections: kind, byte offset, length. Reads only the
/// 32-byte `MAMA8` footer off the file's tail plus the section directory it
/// points at — kilobytes against a 60 GB file.
///
/// Sidecars are appended **uncompressed** after the tile data (see
/// `mamaps_pack`), so these lengths are the bytes on disk, not estimates.
fn sidecars(path: &str) -> ExitCode {
    use tile_build::mamaps::archive::{ARCHIVE_DIR_HEADER_LEN, ARCHIVE_ENTRY_LEN, ARCHIVE_FOOTER_LEN};

    fn kind_name(kind: u8) -> &'static str {
        match kind {
            1 => "graph_meta (metadata.bin: MARG header, node/edge/escape/name counts)",
            2 => "graph_nodes (nodes.bin: per-node records + sentinel)",
            3 => "graph_edges (edges.bin: edge records + escape index/rows + name bitmap/offs)",
            4 => "graph_intermediate (intermediate.bin: geometry blob + trailer)",
            5 => "graph_names (road_names.bin: NUL-terminated string pool)",
            6 => "graph_lanes (lanes.bin: sparse lane index + blob)",
            7 => "graph_elevation (elevation.bin: per-node i16 elevations)",
            8 => "poi_index (poi_index.bin: 14 B records, one per POI)",
            9 => "poi_names (poi_names.bin: NUL-terminated name pool)",
            10 => "poi_attrs (poi_attrs.bin: MAPA attribute sidecar)",
            11 => "poi_spatial (poi_spatial.bin: PSP1 spatial grid)",
            12 => "poi_words (poi_name_index.bin: PNI1 full-text word index)",
            13 => "transit (world.transit: TRIX pack of routes/shapes)",
            15 => "poi_region (poi_region.bin: PRG1 region-id sidecar)",
            16 => "poi_admin (poi_admin.bin: PAD1 boundary names/levels/parents)",
            _ => "unknown",
        }
    }

    let file = match File::open(path) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("mamaps_probe: cannot open {path}: {e}");
            return ExitCode::from(1);
        }
    };
    let ranges = FileRanges { file: std::sync::Mutex::new(file) };
    let file_len = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
    if file_len < ARCHIVE_FOOTER_LEN as u64 {
        eprintln!("mamaps_probe: file shorter than a footer");
        return ExitCode::from(1);
    }
    let foot = match ranges.read(file_len - ARCHIVE_FOOTER_LEN as u64, ARCHIVE_FOOTER_LEN as u32)
    {
        Ok(f) => f,
        Err(e) => {
            eprintln!("mamaps_probe: footer read: {e}");
            return ExitCode::from(1);
        }
    };
    if &foot[0..8] != b"MAMA8\0\0\0" {
        println!("no MAMA8 footer: tiles-only archive, no sidecars packed");
        return ExitCode::SUCCESS;
    }
    let u64_at = |o: usize| {
        u64::from_le_bytes([
            foot[o], foot[o + 1], foot[o + 2], foot[o + 3], foot[o + 4], foot[o + 5], foot[o + 6],
            foot[o + 7],
        ])
    };
    let (dir_offset, dir_len, build_id) = (u64_at(8), u64_at(16), u64_at(24));
    let dir = match ranges.read(dir_offset, dir_len as u32) {
        Ok(d) => d,
        Err(e) => {
            eprintln!("mamaps_probe: directory read: {e}");
            return ExitCode::from(1);
        }
    };
    let count = u32::from_le_bytes([dir[0], dir[1], dir[2], dir[3]]) as usize;
    println!("MAMA8 footer: build_id {build_id:#018x} dir at {dir_offset} ({dir_len} B) file {file_len} B");
    println!("kind  section (+ what the payload holds)");
    println!("      offset            length");
    let mut total = 0u64;
    for i in 0..count {
        let at = ARCHIVE_DIR_HEADER_LEN + i * ARCHIVE_ENTRY_LEN;
        let e = &dir[at..at + ARCHIVE_ENTRY_LEN];
        let u32_at = |o: usize| u32::from_le_bytes([e[o], e[o + 1], e[o + 2], e[o + 3]]);
        let u64_at = |o: usize| {
            u64::from_le_bytes([
                e[o], e[o + 1], e[o + 2], e[o + 3], e[o + 4], e[o + 5], e[o + 6], e[o + 7],
            ])
        };
        // Entry layout: kind u8 @0, flags u32 @4, offset u64 @8, len u64 @16, extra u64 @24.
        let (kind, offset, len) = (e[0], u64_at(8), u64_at(16));
        let _ = u32_at(0);
        total += len;
        println!("{kind:<5} {}", kind_name(kind));
        println!("      {offset:<17} {}", fmt_bytes(len));
    }
    println!("sidecar total: {} ({:.1}% of file)", fmt_bytes(total), 100.0 * total as f64 / file_len as f64);
    ExitCode::SUCCESS
}

/// Per-layer, per-zoom raw payload sizes from sampled bodies: for each zoom,
/// the mean raw (pre-DEFLATE) bytes each layer's features+parts+coords occupy,
/// scaled to the zoom's body count. Plus the side tables (names, ids, lanes,
/// buildings, heightmap, carriageways, region links) the same way.
///
/// Raw, not stored: the body DEFLATEs as one blob, so per-section stored bytes
/// are unmeasurable — but raw sizes are exact per body, and the ratio between
/// sections is what says where the bytes go. The heightmap column is the DEM
/// answer: `2 + dim*dim*2` bytes wherever the section is present.
fn layer_breakdown(path: &str, samples_per_zoom: usize) -> ExitCode {
    use tile_build::mamaps::{header, index};
    use tilecodec::pmtiles::tile_zxy;

    let file = match File::open(path) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("mamaps_probe: cannot open {path}: {e}");
            return ExitCode::from(1);
        }
    };
    let ranges = FileRanges { file: std::sync::Mutex::new(file) };
    let prefix = match ranges.read(0, tilecodec::stream::OPEN_PREFIX_BYTES) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("mamaps_probe: open: {e}");
            return ExitCode::from(1);
        }
    };
    let hdr = match header::Header::parse(&prefix) {
        Ok(h) => h,
        Err(e) => {
            eprintln!("mamaps_probe: header: {e}");
            return ExitCode::from(1);
        }
    };
    let dictionary = match tile_build::mamaps::dict::Dictionary::parse(
        &prefix[hdr.dict_offset as usize..(hdr.dict_offset + hdr.dict_len as u64) as usize],
    ) {
        Ok(d) => d,
        Err(e) => {
            eprintln!("mamaps_probe: dict: {e}");
            return ExitCode::from(1);
        }
    };
    let root = match index::parse_root(
        &prefix[hdr.root_offset as usize..(hdr.root_offset + hdr.root_len as u64) as usize],
    ) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("mamaps_probe: root: {e}");
            return ExitCode::from(1);
        }
    };
    // Leaf entries per zoom (positions only — bodies fetched in the sample pass).
    let mut zoom_entries: std::collections::BTreeMap<u8, Vec<(u64, u32)>> =
        std::collections::BTreeMap::new();
    let mut zoom_bodies: std::collections::BTreeMap<u8, u64> =
        std::collections::BTreeMap::new();
    for root_entry in &root {
        let raw_len = root_entry.leaf_entry_count as u64 * index::LEAF_ENTRY_LEN as u64;
        let stored_len = if root_entry.stored_len == 0 { raw_len } else { root_entry.stored_len as u64 };
        let stored = match ranges.read((hdr.leaf_offset + root_entry.leaf_offset) as u64, stored_len as u32)
        {
            Ok(r) => r,
            Err(e) => {
                eprintln!("mamaps_probe: leaf: {e}");
                return ExitCode::from(1);
            }
        };
        let raw = if root_entry.stored_len == 0 {
            stored
        } else {
            match index::decompress_leaf(&stored, raw_len as usize) {
                Ok(r) => r,
                Err(e) => {
                    eprintln!("mamaps_probe: leaf inflate: {e}");
                    return ExitCode::from(1);
                }
            }
        };
        let leaf = match index::parse_leaf(&raw) {
            Ok(l) => l,
            Err(e) => {
                eprintln!("mamaps_probe: leaf parse: {e}");
                return ExitCode::from(1);
            }
        };
        for e in &leaf {
            let (z, _, _) = tile_zxy(root_entry.base_tile_id + e.tile_id_lo as u64);
            let at = hdr.data_offset + root_entry.base_data_offset + e.offset_delta as u64;
            zoom_entries.entry(z).or_default().push((at, e.length));
            *zoom_bodies.entry(z).or_default() += 1;
        }
    }
    let layer_name = |id: u8| -> String {
        dictionary.layer_name(id).unwrap_or("?").to_string()
    };
    let archive = match MamapsArchive::open(FileRanges {
        file: std::sync::Mutex::new(File::open(path).expect("open")),
    }) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("mamaps_probe: reopen: {e}");
            return ExitCode::from(1);
        }
    };
    // Fixed layer order for the table: the schema's own layer list, then the
    // side tables in wire order.
    let mut layer_order: Vec<u8> = (0..dictionary.layers.len() as u8).collect();
    layer_order.sort_by_key(|id| layer_name(*id));
    println!("per-zoom mean RAW body bytes by section (sampled bodies, scaled to zoom body count):");
    for (z, entries) in &zoom_entries {
        let bodies = zoom_bodies[z] as f64;
        let step = (entries.len() / samples_per_zoom.max(1)).max(1);
        let mut sampled = 0u64;
        let mut layer_raw: std::collections::BTreeMap<u8, u64> =
            std::collections::BTreeMap::new();
        let mut names = 0u64;
        let mut ids = 0u64;
        let mut lanes = 0u64;
        let mut buildings = 0u64;
        let mut dem = 0u64;
        let mut dem_tiles = 0u64;
        let mut carriage = 0u64;
        let mut regions = 0u64;
        let mut idx = 0usize;
        while idx < entries.len() {
            let (at, len) = entries[idx];
            let stored = match archive.reader().read(at, len) {
                Ok(s) => s,
                Err(_) => {
                    idx += step;
                    continue;
                }
            };
            let raw = match tile_build::mamaps::read::decompress(hdr.compression, &stored) {
                Ok(r) => r,
                Err(_) => {
                    idx += step;
                    continue;
                }
            };
            let body = match Body::parse(&raw) {
                Ok(b) => b,
                Err(_) => {
                    idx += step;
                    continue;
                }
            };
            sampled += 1;
            for layer in &body.layers {
                // Raw payload: 24 B per feature record, 12 B per part entry
                // (10 + align4), ~2-3 B per coordinate (zigzag varint mean).
                let payload = layer.features.len() as u64 * 24
                    + layer.parts.len() as u64 * 12
                    + layer.coords.len() as u64 * 3;
                *layer_raw.entry(layer.layer_id).or_default() += payload;
            }
            for s in &body.names {
                names += s.len() as u64 + 1;
            }
            for (_, v) in &body.ids {
                ids += v.len() as u64 * 8;
            }
            for (_, v) in &body.turn_lanes {
                for t in v {
                    lanes += 2 + t.forward.len() as u64 * 2 + t.backward.len() as u64 * 2;
                }
            }
            for (_, v) in &body.buildings {
                buildings += v.len() as u64 * 20;
            }
            if let Some(hm) = &body.heightmap {
                dem_tiles += 1;
                dem += (2 + hm.samples.len() * 2) as u64;
            }
            for (_, v) in &body.carriageways {
                carriage += v.len() as u64 * 6;
            }
            for (_, v) in &body.region_links {
                regions += v.len() as u64 * 8;
            }
            idx += step;
        }
        if sampled == 0 {
            continue;
        }
        // Scale the per-body mean to the zoom's full body count. (An earlier
        // version multiplied by bodies/sampled — dividing by the sample twice
        // and understating every absolute ~400x. Ratios were unaffected.)
        let scale = bodies;
        let mut cells: Vec<String> = Vec::new();
        let mut total = 0u64;
        for id in &layer_order {
            let mean = layer_raw.get(id).copied().unwrap_or(0) as f64 / sampled as f64;
            let zoom_total = (mean * scale) as u64;
            total += zoom_total;
            if zoom_total > 0 {
                cells.push(format!("{}={}", layer_name(*id), fmt_bytes(zoom_total)));
            }
        }
        let mut side = |n: u64, label: &str| -> Option<String> {
            let t = (n as f64 / sampled as f64 * scale) as u64;
            total += t;
            (t > 0).then(|| format!("{label}={}", fmt_bytes(t)))
        };
        for s in [
            side(names, "names"),
            side(ids, "ids"),
            side(lanes, "lanes"),
            side(buildings, "bldg"),
            side(dem, "DEM"),
            side(carriage, "carr"),
            side(regions, "rgn"),
        ]
        .into_iter()
        .flatten()
        {
            cells.push(s);
        }
        let dem_share = dem as f64 / sampled as f64 * scale / total.max(1) as f64 * 100.0;
        println!(
            "z{z:<3} bodies={:<9} raw_total={:<8} dem_tiles={:.0}% dem_share={:.0}%  {}",
            zoom_bodies[z],
            fmt_bytes(total),
            100.0 * dem_tiles as f64 / sampled as f64,
            dem_share,
            cells.join(" "),
        );
    }
    ExitCode::SUCCESS
}
