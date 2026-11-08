//! `mamaps_sizes` — byte breakdown of a `.mamaps` archive without inflating bodies.
//!
//! Usage: mamaps_sizes IN.mamaps [--sample-every N]
//!
//! Sections:
//!   A. File envelope: header/dict/root/leaves/data/sidecars/footer + alignment.
//!   B. Sidecar directory: per-section kind/offset/len.
//!   C. Tile overhead: index bytes per tile + empty-body cost (the zero-feature
//!      baseline: 16B body header + per-layer index entries with zero-length payloads).
//!   D. Per-layer stored bytes, attributed by raw-share: each body's stored
//!      (compressed) length is split across its layers by raw payload share.
//!      Bodies are compressed as a unit, so this is an estimate — exact for the
//!      total, approximate per layer (layers with similar entropy split evenly).
//!   E. Wire anatomy: feature records (24B), part entries (12B), point counts,
//!      trailing-table presence from body flags.
//!
//! Reads only structural metadata: header, dict, root, leaves, and each body's
//! 16B frame prefix + layer index. Never inflates, never reads coordinates.
//! Bodies are walked through a single sequential pass over the leaf table with
//! the body-prefix reads batched per leaf, so a 66GB file scans in minutes.
use std::collections::BTreeMap;
use std::process::ExitCode;

use tile_build::mamaps::body::{
    BODY_FLAG_BUILDING_TABLE, BODY_FLAG_EXTENDED_COUNTS, BODY_FLAG_HEIGHTMAP,
    BODY_FLAG_ID_TABLE, BODY_FLAG_LANE_TABLE, BODY_FLAG_NAME_TABLE, BODY_FLAG_ROAD_LANES,
    BODY_HEADER_LEN, FEATURE_RECORD_LEN, LAYER_INDEX_LEN,
};
use tile_build::mamaps::dict::Dictionary;
use tile_build::mamaps::header::Header;
use tile_build::mamaps::index::{decompress_leaf, parse_leaf, parse_root, LEAF_ENTRY_LEN, ROOT_ENTRY_LEN};

const KIND_NAMES: [(u8, &str); 14] = [
    (1, "graph_meta"), (2, "graph_nodes"), (3, "graph_edges"), (4, "graph_intermediate"),
    (5, "graph_names"), (6, "graph_lanes"), (7, "graph_elevation"), (8, "poi_index"),
    (9, "poi_names"), (10, "poi_attrs"), (11, "poi_spatial"), (12, "poi_words"),
    (13, "transit"), (15, "poi_region"),
];

fn gb(n: u64) -> f64 {
    n as f64 / 1e9
}

fn th(n: u64) -> String {
    let s = n.to_string();
    let mut out = String::with_capacity(s.len() + s.len() / 3);
    for (i, c) in s.chars().rev().enumerate() {
        if i > 0 && i % 3 == 0 {
            out.push(',');
        }
        out.push(c);
    }
    out.chars().rev().collect()
}

fn kind_name(kind: u8) -> String {
    KIND_NAMES.iter().find(|(k, _)| *k == kind).map(|(_, n)| n.to_string()).unwrap_or_else(|| format!("kind{kind}"))
}

/// One leaf's worth of the D/E scan, computed on a worker thread with its own
/// file handle. Aggregated by the caller; never shared mid-scan.
#[derive(Default)]
struct Partial {
    bodies_seen: u64,
    total_stored: u64,
    total_raw: u64,
    empty_bodies: u64,
    empty_stored: u64,
    extended_count: u64,
    layer_stored: BTreeMap<u8, u64>,
    layer_raw: BTreeMap<u8, u64>,
    layer_bodies: BTreeMap<u8, u64>,
    layer_features: BTreeMap<u8, u64>,
    flags_hist: BTreeMap<u8, u64>,
    // Stored bytes by (zoom, layer): exact per-zoom totals need no sampling —
    // the zoom comes from the tile id, the layer split from the sample below.
    // Keyed (zoom, layer) -> sampled stored bytes; scaled like layer_stored.
    zoom_layer_stored: BTreeMap<(u8, u8), u64>,
    // Trailing-table bytes by section, measured on the RAW (decompressed) body:
    // names, ids, turn-lanes, buildings, heightmap, carriageways. Compressed
    // share is attributed by raw-share like the layers.
    table_raw: BTreeMap<&'static str, u64>,
    table_stored: BTreeMap<&'static str, u64>,
}

fn scan_leaf(
    path: &str,
    header: &Header,
    re: &tile_build::mamaps::index::RootEntry,
    sample_every: u64,
    body_counter: &std::sync::atomic::AtomicU64,
) -> Result<Partial, String> {
    use std::fs::File;
    use std::io::{Read, Seek, SeekFrom};
    let mut f = File::open(path).map_err(|e| format!("cannot open: {e}"))?;
    let mut p = Partial::default();
    f.seek(SeekFrom::Start(header.leaf_offset + re.leaf_offset))
        .map_err(|e| format!("cannot seek to leaf: {e}"))?;
    // v9 leaf framing: `stored_len == 0` is a raw legacy leaf, anything else is
    // one raw-DEFLATE frame of that length (see `index::decompress_leaf`).
    let raw_len = re.leaf_entry_count as usize * LEAF_ENTRY_LEN;
    let stored_len = if re.stored_len == 0 { raw_len } else { re.stored_len as usize };
    let mut stored_buf = vec![0u8; stored_len];
    f.read_exact(&mut stored_buf).map_err(|e| format!("cannot read leaf: {e}"))?;
    let leaf_buf = if re.stored_len == 0 {
        stored_buf
    } else {
        decompress_leaf(&stored_buf, raw_len).map_err(|e| format!("bad leaf frame: {e:?}"))?
    };
    let leaf = parse_leaf(&leaf_buf).map_err(|e| format!("bad leaf: {e:?}"))?;
    // Dedup: a run of consecutive tiles shares one body — count each stored body
    // once (keyed by data offset), while crediting addressed tiles separately.
    // The zoom for the (zoom, layer) table comes from each body's own tile id.
    let mut seen_offsets = std::collections::HashSet::new();
    for le in &leaf {
        let start = header.data_offset + re.base_data_offset + le.offset_delta as u64;
        if !seen_offsets.insert(start) {
            continue;
        }
        let tile_id = re.base_tile_id + le.tile_id_lo as u64;
        let zoom = tile_build::pmtiles::tile_zxy(tile_id).0;
        // Exact totals need every body; layer attribution needs only a sample.
        // Inflate every `sample_every`-th body (global counter): the index parse
        // below runs on DECOMPRESSED bytes, which the old code got wrong by
        // reading the compressed frame.
        let n = body_counter.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let sampled = sample_every == 0 || n % sample_every == 0;
        // Stored length + raw length come from the leaf + frame prefix (cheap,
        // exact, every body).
        f.seek(SeekFrom::Start(start)).map_err(|e| format!("cannot seek to body: {e}"))?;
        let mut prefix = [0u8; BODY_HEADER_LEN];
        f.read_exact(&mut prefix).map_err(|e| format!("cannot read body prefix: {e}"))?;
        if &prefix[0..3] != b"MBD" {
            continue;
        }
        let raw_len = u32::from_le_bytes(prefix[4..8].try_into().unwrap()) as u64;
        let bflags = prefix[11];
        *p.flags_hist.entry(bflags).or_default() += 1;
        p.total_stored += le.length as u64;
        p.total_raw += raw_len;
        p.bodies_seen += 1;
        if !sampled {
            continue;
        }
        // Sampled: read the whole stored frame, inflate, parse the layer index.
        let mut stored = vec![0u8; le.length as usize];
        f.seek(SeekFrom::Start(start)).map_err(|e| format!("cannot seek to body: {e}"))?;
        f.read_exact(&mut stored).map_err(|e| format!("cannot read body: {e}"))?;
        let raw = tile_build::mamaps::read::helpers::decompress(header.compression, &stored)
            .map_err(|e| format!("cannot inflate body: {e:?}"))?;
        let nlayers = raw[10] as usize;
        let bflags = raw[11];
        if bflags & BODY_FLAG_EXTENDED_COUNTS != 0 {
            p.extended_count += 1;
        }
        let mut payloads: Vec<(u8, u64, u64)> = Vec::with_capacity(nlayers);
        let mut raw_sum = 0u64;
        for i in 0..nlayers {
            let at = BODY_HEADER_LEN + i * LAYER_INDEX_LEN;
            let lid = raw[at];
            let fcount = u16::from_le_bytes([raw[at + 2], raw[at + 3]]) as u64;
            let plen = u32::from_le_bytes(raw[at + 8..at + 12].try_into().unwrap()) as u64;
            payloads.push((lid, fcount, plen));
            raw_sum += plen;
        }
        if payloads.iter().all(|(_, fc, _)| *fc == 0) {
            p.empty_bodies += 1;
            p.empty_stored += le.length as u64;
        }
        let denom = raw_sum.max(1);
        for (lid, fcount, plen) in payloads {
            let share_num = plen as f64 / denom as f64;
            let attrib = (le.length as f64 * share_num) as u64;
            *p.layer_stored.entry(lid).or_default() += attrib;
            *p.zoom_layer_stored.entry((zoom, lid)).or_default() += attrib;
            *p.layer_raw.entry(lid).or_default() += plen;
            if fcount > 0 {
                *p.layer_bodies.entry(lid).or_default() += 1;
                *p.layer_features.entry(lid).or_default() += fcount;
            }
        }
        // Trailing tables: parse the inflated body for real and re-measure
        // each section with the same serializers the writer uses. Exact raw
        // bytes; stored share by raw-share like the layers.
        {
            use tile_build::mamaps::body::Body;
            use tile_build::mamaps::body::{tables, tables_extra};
            if let Ok(body) = Body::parse(&raw) {
                let mut sections: Vec<(&'static str, usize)> = Vec::new();
                // header + layer index (incl. extended counts), 4-aligned
                let mut idx = BODY_HEADER_LEN + nlayers * LAYER_INDEX_LEN;
                if bflags & BODY_FLAG_EXTENDED_COUNTS != 0 {
                    idx += nlayers * 4;
                }
                sections.push(("header+index", (idx + 3) & !3));
                let mut scratch = Vec::new();
                if !body.names.is_empty() {
                    scratch = tables::serialize_names(&body.names);
                    sections.push(("names", scratch.len()));
                }
                if !body.ids.is_empty() {
                    scratch.clear();
                    tables::serialize_ids(&body.ids, &mut scratch);
                    sections.push(("ids", scratch.len()));
                }
                if !body.turn_lanes.is_empty() {
                    scratch.clear();
                    tables::serialize_lanes(&body.turn_lanes, &mut scratch);
                    sections.push(("turn_lanes", scratch.len()));
                }
                if !body.buildings.is_empty() {
                    scratch.clear();
                    tables_extra::serialize_buildings(&body.buildings, &mut scratch);
                    sections.push(("buildings_table", scratch.len()));
                }
                if let Some(grid) = &body.heightmap {
                    scratch.clear();
                    tables::serialize_heightmap(grid, &mut scratch);
                    sections.push(("heightmap", scratch.len()));
                }
                if !body.carriageways.is_empty() {
                    scratch.clear();
                    tables::serialize_carriageways(
                        &body.carriageways,
                        body.convention.unwrap_or_default(),
                        &mut scratch,
                    );
                    sections.push(("carriageways", scratch.len()));
                }
                for (name, len) in sections {
                    let len = len as u64;
                    *p.table_raw.entry(name).or_default() += len;
                    *p.table_stored.entry(name).or_default() +=
                        ((le.length as f64) * (len as f64) / (raw_len.max(1) as f64)) as u64;
                }
            }
        }
    }
    Ok(p)
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let mut input = None;
    let mut sample_every: u64 = 0;
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--sample-every" => {
                sample_every = args.get(i + 1).and_then(|s| s.parse().ok()).unwrap_or(0);
                i += 2;
            }
            other => {
                if input.is_some() {
                    eprintln!("mamaps_sizes: more than one input");
                    return ExitCode::from(2);
                }
                input = Some(other.to_string());
                i += 1;
            }
        }
    }
    let Some(input) = input else {
        eprintln!("usage: mamaps_sizes IN.mamaps [--sample-every N]");
        return ExitCode::from(2);
    };
    match run(&input, sample_every) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("mamaps_sizes: {input}: {e}");
            ExitCode::from(1)
        }
    }
}

fn run(path: &str, sample_every_arg: u64) -> Result<(), String> {
    use std::fs::File;
    use std::io::{Read, Seek, SeekFrom};
    let mut f = File::open(path).map_err(|e| format!("cannot open: {e}"))?;
    let size = f.metadata().map_err(|e| format!("cannot stat: {e}"))?.len();

    let mut hbuf = [0u8; 128];
    f.read_exact(&mut hbuf).map_err(|e| format!("cannot read header: {e}"))?;
    let header = Header::parse(&hbuf).map_err(|e| format!("bad header: {e:?}"))?;

    // Layer names come from the file's own dict, not a hardcoded schema table:
    // v8 merged the four v7 wash layers (earth/water/landcover/landuse) into
    // landtype, so any fixed table rots on the next schema change.
    f.seek(SeekFrom::Start(header.dict_offset)).map_err(|e| format!("cannot seek to dict: {e}"))?;
    let mut dbuf = vec![0u8; header.dict_len as usize];
    f.read_exact(&mut dbuf).map_err(|e| format!("cannot read dict: {e}"))?;
    let dictionary = Dictionary::parse(&dbuf).map_err(|e| format!("bad dict: {e:?}"))?;
    let layer_names: BTreeMap<u8, String> = dictionary
        .layers
        .iter()
        .enumerate()
        .map(|(id, nm)| (id as u8, nm.clone()))
        .collect();

    println!("== {path}  ({:.2} GB on disk, header declares {:.2} GB)", gb(size), gb(header.file_len));
    println!(
        "   compression={} layers={} zoom={}..{} leaf_cap={}",
        header.compression, header.layer_count, header.min_zoom, header.max_zoom, header.leaf_entry_capacity
    );
    println!(
        "   tiles_addressed={} bodies_written={} dedup={:.3}",
        header.tiles_addressed,
        header.bodies_written,
        header.bodies_written as f64 / header.tiles_addressed.max(1) as f64
    );
    println!();
    println!("== A. envelope");
    println!("   {:20} {:>14}  ({:.4} GB)", "header", th(128), gb(128));
    println!("   {:20} {:>14}  ({:.4} GB)", "dict", th(header.dict_len as u64), gb(header.dict_len as u64));
    println!(
        "   {:20} {:>14}  ({} entries x 32B)",
        "root",
        th(header.root_len as u64),
        header.root_len / ROOT_ENTRY_LEN as u32
    );
    println!(
        "   {:20} {:>14}  ({} entries x 16B)",
        "leaves",
        th(header.leaf_len),
        header.leaf_len / LEAF_ENTRY_LEN as u64
    );
    println!(
        "   leaf-bytes per stored body: {:.1} B",
        header.leaf_len as f64 / header.bodies_written.max(1) as f64
    );
    println!(
        "   leaf-bytes per addressed tile: {:.1} B",
        header.leaf_len as f64 / header.tiles_addressed.max(1) as f64
    );
    println!(
        "   {:20} {:>14}  ({:.2} GB)",
        "tile data section",
        th(header.data_len),
        gb(header.data_len)
    );

    // B. sidecars via footer
    f.seek(SeekFrom::End(-32)).map_err(|e| format!("cannot seek to footer: {e}"))?;
    let mut footer = [0u8; 32];
    f.read_exact(&mut footer).map_err(|e| format!("cannot read footer: {e}"))?;
    println!();
    if &footer[0..8] == b"MAMA8\0\0\0" {
        let dir_offset = u64::from_le_bytes(footer[8..16].try_into().unwrap());
        let dir_len = u64::from_le_bytes(footer[16..24].try_into().unwrap());
        let footer_build = u64::from_le_bytes(footer[24..32].try_into().unwrap());
        println!("== B. sidecars (dir@{dir_offset}+{dir_len} build={footer_build:#018x})");
        f.seek(SeekFrom::Start(dir_offset)).map_err(|e| format!("cannot seek to dir: {e}"))?;
        let mut cbuf = [0u8; 4];
        f.read_exact(&mut cbuf).map_err(|e| format!("cannot read dir count: {e}"))?;
        let count = u32::from_le_bytes(cbuf) as usize;
        let mut sidecar_total = 0u64;
        let mut prev_end = header.data_offset + header.data_len;
        for _ in 0..count {
            let mut ebuf = [0u8; 32];
            f.read_exact(&mut ebuf).map_err(|e| format!("cannot read dir entry: {e}"))?;
            let kind = ebuf[0];
            let offset = u64::from_le_bytes(ebuf[8..16].try_into().unwrap());
            let length = u64::from_le_bytes(ebuf[16..24].try_into().unwrap());
            let extra = u64::from_le_bytes(ebuf[24..32].try_into().unwrap());
            let pad_before = offset.saturating_sub(prev_end);
            sidecar_total += length;
            println!(
                "   {:22} {:>14}  ({:.2} GB)  pad_before={} extra={}",
                kind_name(kind),
                th(length),
                gb(length),
                th(pad_before),
                extra
            );
            prev_end = offset + length;
        }
        let dir_pad = (dir_offset + dir_len).saturating_sub(prev_end);
        println!(
            "   {:22} {:>14}  pad_before_dir={}",
            "[dir+footer]",
            th(dir_len + 32),
            th(dir_pad)
        );
        println!("   sidecar payloads total: {} ({:.2} GB)", th(sidecar_total), gb(sidecar_total));
    } else {
        println!("== B. sidecars: (no MAMA8 footer — tiles-only archive)");
    }

    println!();
    println!("== C. tile overhead (the zero-feature baseline)");
    let empty_raw = (BODY_HEADER_LEN + header.layer_count as usize * LAYER_INDEX_LEN + 3) & !3;
    println!("   empty-body raw: {empty_raw} B (16B header + {}x12B layer index)", header.layer_count);
    println!("   (stored empty-body cost follows from the D/E scan: bodies with no layers.)");
    println!(
        "   index overhead: root {} + leaves {} = {} ({:.4} GB)",
        header.root_len,
        header.leaf_len,
        header.root_len as u64 + header.leaf_len,
        gb(header.root_len as u64 + header.leaf_len)
    );
    println!(
        "   per addressed tile: {:.1} B index",
        (header.root_len as u64 + header.leaf_len) as f64 / header.tiles_addressed.max(1) as f64
    );

    // D/E. walk leaves -> bodies, read frame prefix + layer index only.
    println!();
    println!("== D/E. per-layer stored bytes (raw-share of each compressed body) + wire anatomy");
    println!("   scanning body headers + layer indexes (no inflate, no coords)...");
    f.seek(SeekFrom::Start(header.root_offset)).map_err(|e| format!("cannot seek to root: {e}"))?;
    let mut root_buf = vec![0u8; header.root_len as usize];
    f.read_exact(&mut root_buf).map_err(|e| format!("cannot read root: {e}"))?;
    let root = parse_root(&root_buf).map_err(|e| format!("bad root: {e:?}"))?;

    let mut layer_stored: BTreeMap<u8, u64> = BTreeMap::new();
    let mut layer_raw: BTreeMap<u8, u64> = BTreeMap::new();
    let mut layer_bodies: BTreeMap<u8, u64> = BTreeMap::new();
    let mut layer_features: BTreeMap<u8, u64> = BTreeMap::new();
    let mut flags_hist: BTreeMap<u8, u64> = BTreeMap::new();
    let mut extended_count = 0u64;
    let mut total_stored = 0u64;
    let mut total_raw = 0u64;
    let mut empty_bodies = 0u64;
    let mut empty_stored = 0u64;
    let mut bodies_seen = 0u64;

    // `layer_names` was built from the file's dict above; the dead first-pass
    // accumulators used to live here but were shadowed by the aggregation block
    // below, so they are gone and only the dict-derived names survive.

    let t0 = std::time::Instant::now();
    // Parallel scan: one task per root entry (313 leaves for this archive), each
    // with its own file handle (pread-style seeks are thread-safe on separate
    // handles). Totals are exact (every body's leaf length); layer attribution
    // inflates every `sample_every`-th body (default 64, ~2M samples, <1% error).
    let sample_every = if sample_every_arg == 0 { 64 } else { sample_every_arg };
    let body_counter = std::sync::atomic::AtomicU64::new(0);
    let partials: Vec<Partial> = tile_build::par::install(|| {
        use rayon::prelude::*;
        root.par_iter()
            .map(|re| scan_leaf(path, &header, re, sample_every, &body_counter).unwrap_or_else(|e| {
                eprintln!("warning: skipping leaf @{}: {e}", re.base_tile_id);
                Partial::default()
            }))
            .collect()
    });
    let mut layer_stored: BTreeMap<u8, u64> = BTreeMap::new();
    let mut layer_raw: BTreeMap<u8, u64> = BTreeMap::new();
    let mut layer_bodies: BTreeMap<u8, u64> = BTreeMap::new();
    let mut layer_features: BTreeMap<u8, u64> = BTreeMap::new();
    let mut flags_hist: BTreeMap<u8, u64> = BTreeMap::new();
    let mut zoom_layer_stored: BTreeMap<(u8, u8), u64> = BTreeMap::new();
    let mut table_raw: BTreeMap<&'static str, u64> = BTreeMap::new();
    let mut table_stored: BTreeMap<&'static str, u64> = BTreeMap::new();
    let mut extended_count = 0u64;
    let mut total_stored = 0u64;
    let mut total_raw = 0u64;
    let mut empty_bodies = 0u64;
    let mut empty_stored = 0u64;
    let mut bodies_seen = 0u64;
    for p in &partials {
        bodies_seen += p.bodies_seen;
        total_stored += p.total_stored;
        total_raw += p.total_raw;
        empty_bodies += p.empty_bodies;
        empty_stored += p.empty_stored;
        extended_count += p.extended_count;
        for (k, v) in &p.layer_stored {
            *layer_stored.entry(*k).or_default() += v;
        }
        for (k, v) in &p.zoom_layer_stored {
            *zoom_layer_stored.entry(*k).or_default() += v;
        }
        for (k, v) in &p.table_raw {
            *table_raw.entry(k).or_default() += v;
        }
        for (k, v) in &p.table_stored {
            *table_stored.entry(k).or_default() += v;
        }
        for (k, v) in &p.layer_raw {
            *layer_raw.entry(*k).or_default() += v;
        }
        for (k, v) in &p.layer_bodies {
            *layer_bodies.entry(*k).or_default() += v;
        }
        for (k, v) in &p.layer_features {
            *layer_features.entry(*k).or_default() += v;
        }
        for (k, v) in &p.flags_hist {
            *flags_hist.entry(*k).or_default() += v;
        }
    }
    println!("   bodies scanned: {bodies_seen} in {:.1}s", t0.elapsed().as_secs_f64());
    println!("   (layer attribution from every {sample_every}-th body, scaled to exact totals)");
    let mismatch = (total_stored as i64 - header.data_len as i64).abs();
    println!(
        "   stored-bytes check {} vs data_len {} ({})",
        total_stored,
        header.data_len,
        if mismatch < (header.data_len as i64 / 100) { "OK" } else { "MISMATCH" }
    );
    println!(
        "   raw bytes (declared): {} ({:.2} GB)  compression ratio: {:.2}x",
        total_raw,
        gb(total_raw),
        total_raw as f64 / total_stored.max(1) as f64
    );
    println!("   bodies with extended counts: {extended_count}");
    println!("   body-flag histogram: {:?}", flags_hist);
    println!(
        "   empty bodies (zero features everywhere): {empty_bodies} holding {empty_stored} stored bytes ({:.2} GB)",
        gb(empty_stored)
    );
    println!();
    println!(
        "   {:14} {:>10} {:>10} {:>10} {:>12} {:>10} {:>8}",
        "layer", "stored(GB)", "raw(GB)", "bodies", "features", "B/feat(st)", "feat/B"
    );
    for (lid, st) in &layer_stored {
        let rw = layer_raw.get(lid).copied().unwrap_or(0);
        let nb = layer_bodies.get(lid).copied().unwrap_or(0);
        let nf = layer_features.get(lid).copied().unwrap_or(0);
        let nm = layer_names.get(lid).cloned().unwrap_or_else(|| format!("layer{lid}"));
        // Scale the sampled attribution to the exact stored total: the sample
        // ratio is uniform, so each layer's share of the sample scales linearly.
        // Raw scales by the same factor (both accumulated on the sample only).
        let scale = total_stored as f64 / layer_stored.values().sum::<u64>().max(1) as f64;
        let st_scaled = (*st as f64 * scale) as u64;
        let rw_scaled = (rw as f64 * scale) as u64;
        // Bodies/features counts are sample-only: scale to the full body count.
        let count_scale = bodies_seen as f64 / layer_bodies.values().sum::<u64>().max(1) as f64;
        let nb_scaled = (nb as f64 * count_scale) as u64;
        let nf_scaled = (nf as f64 * count_scale) as u64;
        println!(
            "   {:14} {:>10.2} {:>10.2} {:>10} {:>12} {:>10.1} {:>8.1}",
            nm,
            gb(st_scaled),
            gb(rw_scaled),
            nb_scaled,
            nf_scaled,
            st_scaled as f64 / nf_scaled.max(1) as f64,
            nf_scaled as f64 / nb_scaled.max(1) as f64
        );
    }
    println!();
    println!("   wire anatomy per feature (fixed widths): 24B feature record + 12B/part +");
    println!("   varint arena (zigzag deltas, ~2-4B/point typical) + trailing tables.");
    println!("   trailing-table flags: names=0x04 ids=0x02 lanes=0x08 buildings=0x10");
    println!("   heightmap=0x20 roadlanes=0x40 (see histogram above).");
    println!("   feature-record fields: kind u16, detail u16, geom u8, flags u8, name u16,");
    println!("   parts_off u32, part_count u32, transit_color u32, ordinal/lanes/taper/lanecount u8x4.");
    println!();
    println!("== F. stored GB by (zoom, layer) — sampled attribution scaled to exact totals");
    println!("   rows: zoom 0..14 (+ `all`); columns: layers in schema order. `..` = <0.005GB.");
    let zscale = total_stored as f64 / zoom_layer_stored.values().sum::<u64>().max(1) as f64;
    let nlayers = header.layer_count;
    print!("   {:>4}", "z");
    for lid in 0..nlayers {
        let nm = layer_names.get(&lid).cloned().unwrap_or_else(|| format!("layer{lid}"));
        print!(" {:>10}", nm.chars().take(10).collect::<String>());
    }
    println!(" {:>10}", "z-total");
    let mut col_totals = vec![0u64; nlayers as usize];
    for z in 0..=14u8 {
        let mut row_total = 0u64;
        print!("   {:>4}", z);
        for lid in 0..nlayers {
            let v = ((zoom_layer_stored.get(&(z, lid)).copied().unwrap_or(0) as f64) * zscale) as u64;
            row_total += v;
            col_totals[lid as usize] += v;
            if v < 5_000_000 {
                print!(" {:>10}", "..");
            } else {
                print!(" {:>10.2}", gb(v));
            }
        }
        println!(" {:>10.2}", gb(row_total));
    }
    print!("   {:>4}", "all");
    let mut grand = 0u64;
    for lid in 0..nlayers {
        let v = col_totals[lid as usize];
        grand += v;
        print!(" {:>10.2}", gb(v));
    }
    println!(" {:>10.2}", gb(grand));
    println!();
    println!("== G. trailing-table + framing bytes (sampled, scaled to exact totals)");
    println!("   raw = re-serialized section bytes; stored = raw-share of compressed bodies.");
    // Per-body attribution already splits each sampled body's stored bytes
    // across its sections by raw-share. Sampling is uniform (every 64th body),
    // so scale to full by the measured ratio of exact total stored bytes to
    // sampled attributed stored bytes (the layer table's own scale factor).
    let sampled_stored: u64 = layer_stored.values().sum();
    let to_full = total_stored as f64 / sampled_stored.max(1) as f64;
    let mut t_total_st = 0u64;
    for name in ["header+index", "names", "ids", "turn_lanes", "buildings_table", "heightmap", "carriageways"] {
        let st = ((table_stored.get(name).copied().unwrap_or(0) as f64) * to_full) as u64;
        let rw = table_raw.get(name).copied().unwrap_or(0);
        t_total_st += st;
        println!("   {:16} stored={:>10.2} GB  raw(sample)={:>12} B", name, gb(st), th(rw));
    }
    println!("   {:16} stored={:>10.2} GB", "tables-total", gb(t_total_st));
    println!("   (payload arenas = the rest: total stored {:.2} GB - tables {:.2} GB = {:.2} GB geometry)",
        gb(total_stored), gb(t_total_st), gb(total_stored.saturating_sub(t_total_st)));
    Ok(())
}
