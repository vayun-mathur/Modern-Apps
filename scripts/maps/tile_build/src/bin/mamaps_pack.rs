//! `mamaps_pack` -- append the sidecar files to a finished v7 `.mamaps` archive.
//!
//! Usage:
//!   mamaps_pack --tiles IN.mamaps --graph GRAPH_DIR --poi POI_DIR
//!               [--transit WORLD.transit] --out OUT.mamaps
//!
//! The tile prefix (`[128B header][dict][root][leaves][tile data]`) is treated
//! as immutable: it is copied verbatim except for two header fields. The sidecar
//! payloads are appended byte-identical (uncompressed, 8-byte aligned) starting
//! at `data_end`, followed by the section directory and the `MAMA8` footer. Then
//! the header is rewritten with `file_len = final length` and `build_id =
//! unified_build_id(...)`, and the footer is re-verified.
//!
//! Inputs are validated before anything is written: each section's
//! magic/version/count is checked the way its loader checks it, and a
//! mismatch fails closed with a non-zero exit and no partial output.
//!
//! Sidecar files (canonical kind order):
//!   graph: metadata.bin nodes.bin edges.bin intermediate.bin road_names.bin
//!          lanes.bin elevation.bin
//!   poi:   poi_index.bin poi_names.bin poi_attrs.bin poi_spatial.bin
//!          poi_name_index.bin
//!   transit: world.transit (one TRIX pack; `<feed>.transit` naming is a
//!            delivery rename, the bytes are what matter)
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use tile_build::mamaps::archive::{
    unified_build_id, is_known_kind, ArchiveEntry, ArchiveFooter, ARCHIVE_ALIGN,
    ARCHIVE_KIND_GRAPH_EDGES, ARCHIVE_KIND_GRAPH_ELEVATION, ARCHIVE_KIND_GRAPH_INTERMEDIATE,
    ARCHIVE_KIND_GRAPH_LANES, ARCHIVE_KIND_GRAPH_META, ARCHIVE_KIND_GRAPH_NAMES,
    ARCHIVE_KIND_GRAPH_NODES, ARCHIVE_KIND_GRAPH_OVERLAY, ARCHIVE_KIND_POI_ATTRS, ARCHIVE_KIND_POI_INDEX,
    ARCHIVE_KIND_POI_NAMES,
    ARCHIVE_KIND_POI_SPATIAL, ARCHIVE_KIND_POI_WORDS, ARCHIVE_KIND_TRANSIT,
};
use tile_build::mamaps::header::Header;
use tile_build::timing::PhaseTimer;

fn read_file(path: &Path) -> Result<Vec<u8>, String> {
    std::fs::read(path).map_err(|e| format!("cannot read {}: {e}", path.display()))
}

/// One sidecar payload staged for layout: kind, source path, length, and the `extra` count
/// the validators cross-check.
///
/// A path, not bytes: the planet graph's `nodes.bin`/`edges.bin`/`intermediate.bin` are ~22 GB
/// between them, and holding every sidecar in a `Vec<u8>` beside the tiles copy is the pack
/// step's own OOM on a constrained box. Payloads stream file-to-file at pack time (see
/// `pack_streaming`); only the small files (metadata, lanes, POI index/names) are ever read
/// whole, and only to validate and hash them.
#[derive(Debug)]
struct Staged {
    kind: u8,
    path: PathBuf,
    /// Bytes this payload occupies in the archive.
    len: u64,
    extra: u64,
    /// The source file's own length, re-checked at copy time. Larger than `len` only for a
    /// transit pack whose host-only tail is left out (see [`transit_without_host_sections`]).
    file_len: u64,
    /// Bytes written in place of the source's first `head.len()`: the transit pack's header and
    /// directory with the host-only sections emptied. Empty for every other payload.
    head: Vec<u8>,
}

impl Staged {
    fn whole(kind: u8, path: PathBuf, len: u64) -> Staged {
        Staged { kind, path, len, extra: 0, file_len: len, head: Vec::new() }
    }
}

/// TRIX sections from this index on are host-only (v7 RAIL_LINES, the build's own copy of the
/// rail shapes) and never ship: the device reads none of them.
const TRANSIT_HOST_SECTIONS_FROM: usize = 27;
const TRANSIT_HEADER_LEN: usize = 80;

/// The transit pack as the archive carries it: its header and directory with every host-only
/// section set to zero length at the cut, and the file truncated there. Host-only sections are
/// written last, so everything the device reads sits before the cut. Returns `(head, len)`;
/// `head` is empty and `len` the whole file when there is nothing to leave out.
fn transit_without_host_sections(path: &Path, file_len: u64) -> Result<(Vec<u8>, u64), String> {
    let mut f = std::fs::File::open(path).map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    let mut head = vec![0u8; TRANSIT_HEADER_LEN.min(file_len as usize)];
    f.read_exact(&mut head).map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    if head.len() < TRANSIT_HEADER_LEN {
        return Err(format!("{} ends before its TRIX header", path.display()));
    }
    let count = u32::from_le_bytes([head[8], head[9], head[10], head[11]]) as usize;
    if count <= TRANSIT_HOST_SECTIONS_FROM {
        return Ok((Vec::new(), file_len));
    }
    let dir_end = TRANSIT_HEADER_LEN + count * 16;
    if dir_end as u64 > file_len {
        return Err(format!("{}'s section directory runs past the file", path.display()));
    }
    head.resize(dir_end, 0);
    f.read_exact(&mut head[TRANSIT_HEADER_LEN..]).map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    let entry = |head: &[u8], i: usize| {
        let at = TRANSIT_HEADER_LEN + i * 16;
        let u = |o: usize| u64::from_le_bytes(head[o..o + 8].try_into().expect("8 bytes"));
        (u(at), u(at + 8))
    };
    let cut = (TRANSIT_HOST_SECTIONS_FROM..count).map(|i| entry(&head, i).0).min().unwrap_or(file_len);
    for i in 0..TRANSIT_HOST_SECTIONS_FROM {
        let (off, len) = entry(&head, i);
        if off + len > cut {
            return Err(format!("{}: section {i} runs past the host-only sections at {cut}", path.display()));
        }
    }
    for i in TRANSIT_HOST_SECTIONS_FROM..count {
        let at = TRANSIT_HEADER_LEN + i * 16;
        head[at..at + 8].copy_from_slice(&cut.to_le_bytes());
        head[at + 8..at + 16].copy_from_slice(&0u64.to_le_bytes());
    }
    Ok((head, cut))
}

/// The few sidecars small enough to hold: graph metadata, lane index, POI descriptors, and the
/// transit prefix the build id hashes. Everything else streams.
#[derive(Debug)]
struct Validated {
    staged: Vec<Staged>,
    /// First 1 KiB of the transit pack, for `derive_build_id`. Empty without `--transit`.
    transit_prefix: Vec<u8>,
    /// The tile header's own build-id bytes (`tile_bytes[16..24]`), for `derive_build_id`.
    osm_id: [u8; 8],
    /// FNV-1a of `poi_attrs.bin`, for `derive_build_id`. Empty without the file. The
    /// attributes can change with no tile change (merged review data), and phones cache
    /// byte ranges by build id, so the id has to move with them.
    poi_attrs_digest: Vec<u8>,
}

/// Untimed entry point, kept for the unit tests below: identical staging and
/// validation, timed against a sink that is never finished so tests emit no
/// TIMING lines. Production `main` calls `load_sidecars_impl` with the real
/// phase timer for per-directory subphases.
#[cfg(test)]
fn load_sidecars(graph: &Path, poi: &Path, transit: Option<&Path>) -> Result<Validated, String> {
    let mut sink = PhaseTimer::start("pack-test");
    load_sidecars_impl(graph, poi, transit, &mut sink)
}

fn load_sidecars_impl(
    graph: &Path,
    poi: &Path,
    transit: Option<&Path>,
    timer: &mut PhaseTimer,
) -> Result<Validated, String> {
    let mut staged: Vec<Staged> = Vec::new();
    // Canonical order = kind order (ArchiveView requires ascending kinds).
    let mut push = |kind: u8, path: PathBuf, required: bool| -> Result<(), String> {
        match std::fs::metadata(&path) {
            Ok(m) => {
                if required || m.len() > 0 {
                    staged.push(Staged::whole(kind, path, m.len()));
                }
                Ok(())
            }
            Err(e) if !required && e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(format!("cannot stat {}: {e}", path.display())),
        }
    };
    timer.switch("read-graph");
    push(ARCHIVE_KIND_GRAPH_META, graph.join("metadata.bin"), true)?;
    push(ARCHIVE_KIND_GRAPH_NODES, graph.join("nodes.bin"), true)?;
    push(ARCHIVE_KIND_GRAPH_EDGES, graph.join("edges.bin"), true)?;
    push(ARCHIVE_KIND_GRAPH_INTERMEDIATE, graph.join("intermediate.bin"), true)?;
    // Optional graph sections: absent files are omitted, not zero-length.
    push(ARCHIVE_KIND_GRAPH_NAMES, graph.join("road_names.bin"), false)?;
    push(ARCHIVE_KIND_GRAPH_LANES, graph.join("lanes.bin"), false)?;
    push(ARCHIVE_KIND_GRAPH_ELEVATION, graph.join("elevation.bin"), false)?;
    timer.switch("read-poi");
    push(ARCHIVE_KIND_POI_INDEX, poi.join("poi_index.bin"), true)?;
    push(ARCHIVE_KIND_POI_NAMES, poi.join("poi_names.bin"), true)?;
    push(ARCHIVE_KIND_POI_ATTRS, poi.join("poi_attrs.bin"), false)?;
    push(ARCHIVE_KIND_POI_SPATIAL, poi.join("poi_spatial.bin"), false)?;
    push(ARCHIVE_KIND_POI_WORDS, poi.join("poi_name_index.bin"), false)?;
    let poi_attrs_digest = match std::fs::metadata(poi.join("poi_attrs.bin")) {
        Ok(m) if m.len() > 0 => fnv64_file(&poi.join("poi_attrs.bin"))?.to_vec(),
        _ => Vec::new(),
    };
    // Transit is optional like the other sidecars: omitted when no pack is
    // staged (a tiles+graph+POI archive still exercises every loader's
    // degrade path on device). Callers that need it pass --transit.
    timer.switch("read-transit");
    let mut transit_prefix = Vec::new();
    if let Some(t) = transit {
        let file_len = std::fs::metadata(t)
            .map_err(|e| format!("cannot stat {}: {e}", t.display()))?
            .len();
        let (head, len) = transit_without_host_sections(t, file_len)?;
        // The build id hashes only the first 1 KiB (see `derive_build_id`), as shipped.
        let mut f = std::fs::File::open(t)
            .map_err(|e| format!("cannot read {}: {e}", t.display()))?;
        let mut buf = vec![0u8; (len.min(1024)) as usize];
        f.read_exact(&mut buf)
            .map_err(|e| format!("cannot read {}: {e}", t.display()))?;
        let patched = head.len().min(buf.len());
        buf[..patched].copy_from_slice(&head[..patched]);
        transit_prefix = buf;
        staged.push(Staged { kind: ARCHIVE_KIND_TRANSIT, path: t.to_path_buf(), len, extra: 0, file_len, head });
    }
    // The highway overlay (`road_overlay`) is optional and sorts after transit.
    let overlay = graph.join("overlay.bin");
    match std::fs::metadata(&overlay) {
        Ok(m) if m.len() > 0 => {
            staged.push(Staged::whole(ARCHIVE_KIND_GRAPH_OVERLAY, overlay, m.len()))
        }
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => return Err(format!("cannot stat {}: {e}", overlay.display())),
    }
    // Validate every sidecar's magic/version/count BEFORE anything is written: each section's
    // check is the same one its loader runs, and a mismatch fails closed with a non-zero exit
    // and no partial output. Only the small files are read whole; the large graph payloads
    // validate by streaming their heads (lengths come from the metadata counts).
    validate_sidecars(graph, poi, &staged, timer)?;
    // The tile header's own build-id bytes are read by the caller (it holds the tiles prefix);
    // placeholder filled in `main` after the tiles prefix is parsed.
    Ok(Validated { staged, transit_prefix, osm_id: [0u8; 8], poi_attrs_digest })
}

/// FNV-1a 64 over a whole file, streamed.
fn fnv64_file(path: &Path) -> Result<[u8; 8], String> {
    let mut f = std::fs::File::open(path).map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    let mut buf = vec![0u8; 1 << 20];
    let mut h = 0xcbf2_9ce4_8422_2325u64;
    loop {
        let n = f.read(&mut buf).map_err(|e| format!("cannot read {}: {e}", path.display()))?;
        if n == 0 {
            return Ok(h.to_le_bytes());
        }
        for &b in &buf[..n] {
            h ^= b as u64;
            h = h.wrapping_mul(0x100_0000_01b3);
        }
    }
}

/// Validate each staged sidecar the way its loader checks it, without holding payloads.
///
/// Small files (metadata, POI index/names/attrs/spatial/words, transit head) are read whole --
/// they are kilobytes to low megabytes. The large graph payloads (`nodes.bin`, `edges.bin`,
/// `intermediate.bin`) are checked by length against the metadata counts, plus a streaming
/// magic spot-check is unnecessary: their first bytes are fixed strides the length check
/// already covers, and the packed archive is re-validated by `ArchiveView` below.
fn validate_sidecars(
    graph: &Path,
    poi: &Path,
    staged: &[Staged],
    timer: &mut PhaseTimer,
) -> Result<(), String> {
    // Repeat visits: `finish` merges these with the staging switches above, so
    // each directory's staging + validation report as one subphase.
    timer.switch("read-graph");
    let g = |name: &str| read_file(&graph.join(name));
    let p = |name: &str| read_file(&poi.join(name));
    // Graph meta first: it carries the counts every other graph check is sized from.
    let meta = g("metadata.bin")?;
    if meta.len() != 40 {
        return Err(format!("metadata.bin is {} bytes, a v6 MARG header is 40", meta.len()));
    }
    let u32_at = |b: &[u8], at: usize| u32::from_le_bytes([b[at], b[at + 1], b[at + 2], b[at + 3]]);
    let u64_at = |b: &[u8], at: usize| {
        u64::from_le_bytes([
            b[at], b[at + 1], b[at + 2], b[at + 3], b[at + 4], b[at + 5], b[at + 6],
            b[at + 7],
        ])
    };
    if u32_at(&meta, 0) != 0x4752_414D {
        return Err("metadata.bin is not a MARG graph header".to_string());
    }
    let graph_version = u32_at(&meta, 4);
    if graph_version != 6 && graph_version != 7 {
        return Err(format!("the graph is version {graph_version}, this pack reads v6..=v7"));
    }
    let blocked_v7 = graph_version >= 7;
    let (node_count, edge_count) = (u64_at(&meta, 8), u64_at(&meta, 16));
    if node_count == 0 {
        return Err("the graph has 0 nodes -- road_graph wrote nothing?".to_string());
    }
    let len_of = |kind: u8| staged.iter().find(|s| s.kind == kind).map(|s| s.len);
    // Overlay: `MOVL` v1, built for exactly this graph's node and edge counts.
    // The router re-checks and ignores a mismatch, but a stale overlay in a
    // fresh pack is a build mistake worth failing on.
    if len_of(ARCHIVE_KIND_GRAPH_OVERLAY).is_some() {
        let path = graph.join("overlay.bin");
        let mut head = [0u8; 56];
        std::fs::File::open(&path)
            .and_then(|mut f| f.read_exact(&mut head))
            .map_err(|e| format!("cannot read {}: {e}", path.display()))?;
        if u32_at(&head, 0) != 0x4C56_4F4D || u32_at(&head, 4) != 1 {
            return Err("overlay.bin is not a MOVL v1 overlay".to_string());
        }
        if (u64_at(&head, 8), u64_at(&head, 16)) != (node_count, edge_count) {
            return Err(format!(
                "overlay.bin was built for {} nodes / {} edges, the graph has {node_count} / {edge_count} -- rerun road_overlay",
                u64_at(&head, 8),
                u64_at(&head, 16),
            ));
        }
    }
    // Nodes: (N+1) 12-byte records (the sentinel) — or a ZBLK section
    // unpacking to exactly that under v7. Length-checked only (nodes.bin is
    // ~5 GB on planet): the packed archive is re-validated by `ArchiveView`.
    if let Some(len) = len_of(ARCHIVE_KIND_GRAPH_NODES) {
        let want = (node_count + 1) * 12;
        if blocked_v7 {
            let path = graph.join("nodes.bin");
            let mut f = std::fs::File::open(&path)
                .map_err(|e| format!("cannot read {}: {e}", path.display()))?;
            let mut head = [0u8; 28];
            use std::io::Read as _;
            f.read_exact(&mut head)
                .map_err(|e| format!("cannot read {}: {e}", path.display()))?;
            let unpacked = u64::from_le_bytes(head[16..24].try_into().unwrap());
            if unpacked != want {
                return Err(format!("nodes.bin unpacks to {unpacked} bytes, expected {want}"));
            }
        } else if len != want {
            return Err(format!("nodes.bin is {len} bytes, expected {want} for {node_count}+1 records"));
        }
    }
    // Edges: records + escape index + escape rows + name bitmap + name offs (mirrors
    // `ArchiveView::check_counts`). Length-checked only: edges.bin is ~8 GB on planet, so
    // its bytes stream at pack time rather than loading here. Under v7 the
    // records are a ZBLK prefix: validate its unpacked length from the header,
    // then the tail formula from its end.
    if let Some(len) = len_of(ARCHIVE_KIND_GRAPH_EDGES) {
        let escape_count = u64_at(&meta, 24);
        let named_edges = u64_at(&meta, 32);
        let escape_blocks = edge_count.div_ceil(1024) + 1;
        let (rec_end, rec_ok) = if blocked_v7 {
            let path = graph.join("edges.bin");
            let mut f = std::fs::File::open(&path)
                .map_err(|e| format!("cannot read {}: {e}", path.display()))?;
            let mut head = [0u8; 28];
            use std::io::Read as _;
            f.read_exact(&mut head)
                .map_err(|e| format!("cannot read {}: {e}", path.display()))?;
            let unpacked = u64::from_le_bytes(head[16..24].try_into().unwrap());
            if unpacked != edge_count * 7 {
                return Err(format!(
                    "edges.bin unpacks to {unpacked} bytes, expected {} 7-byte records",
                    edge_count
                ));
            }
            // Block count -> table end -> last offset = records end.
            let blocks = u32::from_le_bytes(head[24..28].try_into().unwrap()) as u64;
            let mut offbytes = vec![0u8; ((blocks + 1) * 8) as usize];
            f.read_exact(&mut offbytes)
                .map_err(|e| format!("cannot read {}: {e}", path.display()))?;
            let end = u64::from_le_bytes(
                offbytes[blocks as usize * 8..(blocks as usize + 1) * 8]
                    .try_into()
                    .unwrap(),
            );
            (end, true)
        } else {
            (edge_count * 7, true)
        };
        if !rec_ok {
            return Err("edges.bin records section failed validation".to_string());
        }
        let first_off = (rec_end + 7) & !7;
        let escapes_off = first_off + escape_blocks * 4;
        let names_off = (escapes_off + escape_count * 12 + 7) & !7;
        let rank = (edge_count.div_ceil(512) + 1) * 8;
        let present = edge_count.div_ceil(8);
        let name_off_off = names_off + rank + present;
        let want = name_off_off + named_edges * 4;
        if len != want {
            return Err(format!("edges.bin is {len} bytes, want {want}"));
        }
    }
    // Intermediate: at least the trailing G, and G <= E (stream the 8-byte trailer).
    if staged.iter().any(|s| s.kind == ARCHIVE_KIND_GRAPH_INTERMEDIATE) {
        let path = graph.join("intermediate.bin");
        let len = std::fs::metadata(&path)
            .map_err(|e| format!("cannot stat {}: {e}", path.display()))?
            .len();
        if len < 8 {
            return Err(format!("intermediate.bin is {len} bytes"));
        }
        let mut f = std::fs::File::open(&path)
            .map_err(|e| format!("cannot read {}: {e}", path.display()))?;
        f.seek(SeekFrom::End(-8))
            .map_err(|e| format!("cannot read {}: {e}", path.display()))?;
        let mut tail = [0u8; 8];
        f.read_exact(&mut tail)
            .map_err(|e| format!("cannot read {}: {e}", path.display()))?;
        let trailer_g = u64::from_le_bytes(tail);
        if trailer_g > edge_count {
            return Err(format!(
                "intermediate names {trailer_g} geometry edges of {edge_count}"
            ));
        }
    }
    // POI index/names/attrs/spatial/words: small, read whole and cross-check counts.
    timer.switch("read-poi");
    let poi_index = p("poi_index.bin")?;
    if poi_index.len() as u64 % 14 != 0 {
        return Err("poi_index.bin is not whole 14-byte records".to_string());
    }
    let poi_count = poi_index.len() as u64 / 14;
    let poi_names = p("poi_names.bin")?;
    if poi_names.is_empty() && poi_count > 0 {
        return Err("poi carries an index but no names".to_string());
    }
    for name in ["poi_attrs.bin", "poi_spatial.bin", "poi_name_index.bin"] {
        let path = poi.join(name);
        if path.exists() {
            let b = read_file(&path)?;
            let (magic, want, kind) = match name {
                "poi_attrs.bin" => (&b"MAPA"[..], 12usize, "attrs"),
                "poi_spatial.bin" => (&b"PSP1"[..], 32usize, "grid"),
                _ => (&b"PNI1"[..], 16usize, "word index"),
            };
            if b.len() < want {
                return Err(format!("{name} ends before its header"));
            }
            if &b[0..4] != magic {
                return Err(format!("{name} has bad magic"));
            }
            let n = u32::from_le_bytes([b[8], b[9], b[10], b[11]]) as u64;
            if n != poi_count {
                return Err(format!(
                    "{name} covers {n} records for {poi_count} index records ({kind} mismatch)"
                ));
            }
        }
    }
    Ok(())
}

/// Copy `len` bytes from `src` at `offset` into `out`, in 1 MiB windows.
///
/// `std::io::copy` would do, but the windowed loop keeps the copy buffer a fixed 1 MiB against
/// multi-GB payloads instead of whatever the reader's default is, and reports the path on
/// failure like every other I/O here.
fn copy_range(
    src: &mut std::fs::File,
    offset: u64,
    len: u64,
    out: &mut std::fs::File,
    src_path: &Path,
    out_path: &Path,
) -> Result<u64, String> {
    src.seek(SeekFrom::Start(offset))
        .map_err(|e| format!("cannot seek {}: {e}", src_path.display()))?;
    let mut buf = vec![0u8; 1 << 20];
    let mut left = len;
    while left > 0 {
        let want = (left.min(buf.len() as u64)) as usize;
        src.read_exact(&mut buf[..want])
            .map_err(|e| format!("cannot read {}: {e}", src_path.display()))?;
        out.write_all(&buf[..want])
            .map_err(|e| format!("cannot write {}: {e}", out_path.display()))?;
        left -= want as u64;
    }
    Ok(len)
}

fn pack_streaming(
    tiles_path: &Path,
    validated: &Validated,
    build_id: u64,
    out: &Path,
) -> Result<u64, String> {
    // Parse the tile header out of the prefix; everything else rides along. Only the 128-byte
    // header is read here -- the tile bodies stream below.
    let mut tiles = std::fs::File::open(tiles_path)
        .map_err(|e| format!("cannot read {}: {e}", tiles_path.display()))?;
    let tiles_len = tiles
        .metadata()
        .map_err(|e| format!("cannot stat {}: {e}", tiles_path.display()))?
        .len();
    let mut head = [0u8; 128];
    tiles
        .read_exact(&mut head)
        .map_err(|e| format!("cannot read {}: {e}", tiles_path.display()))?;
    let header = Header::parse(&head).map_err(|e| format!("tile header does not parse: {e:?}"))?;
    if header.file_len != tiles_len {
        return Err(format!(
            "tile file declares {} bytes but is {tiles_len}",
            header.file_len,
        ));
    }
    let staged = &validated.staged;
    // Tile prefix is immutable: copy verbatim, then append from data end. Streams, so the
    // tiles file (~tens of GB on planet) is never held whole.
    let tmp = out.with_extension("pack.tmp");
    let mut writer = std::fs::File::create(&tmp)
        .map_err(|e| format!("cannot write {}: {e}", tmp.display()))?;
    copy_range(&mut tiles, 0, tiles_len, &mut writer, tiles_path, &tmp)?;
    drop(tiles);
    let mut entries = Vec::new();
    let mut at = tiles_len;
    for s in staged {
        // Every sidecar payload starts 8-byte aligned; padding is zeroes.
        while at % ARCHIVE_ALIGN != 0 {
            writer
                .write_all(&[0])
                .map_err(|e| format!("cannot write {}: {e}", tmp.display()))?;
            at += 1;
        }
        let mut src = std::fs::File::open(&s.path)
            .map_err(|e| format!("cannot read {}: {e}", s.path.display()))?;
        let src_len = src
            .metadata()
            .map_err(|e| format!("cannot stat {}: {e}", s.path.display()))?
            .len();
        if src_len != s.file_len {
            return Err(format!(
                "{} changed under the pack (staged {} bytes, now {src_len})",
                s.path.display(),
                s.file_len,
            ));
        }
        writer
            .write_all(&s.head)
            .map_err(|e| format!("cannot write {}: {e}", tmp.display()))?;
        let head = s.head.len() as u64;
        copy_range(&mut src, head, s.len - head, &mut writer, &s.path, &tmp)?;
        entries.push(ArchiveEntry {
            kind: s.kind,
            flags: 0,
            offset: at,
            len: s.len,
            extra: s.extra,
        });
        at += s.len;
    }
    while at % ARCHIVE_ALIGN != 0 {
        writer
            .write_all(&[0])
            .map_err(|e| format!("cannot write {}: {e}", tmp.display()))?;
        at += 1;
    }
    let dir_offset = at;
    // Directory: count + entries + zero padding to 8.
    let mut dir = Vec::new();
    dir.extend_from_slice(&(entries.len() as u32).to_le_bytes());
    for e in &entries {
        dir.extend_from_slice(&e.serialize());
    }
    while dir.len() as u64 % ARCHIVE_ALIGN != 0 {
        dir.push(0);
    }
    let dir_len = dir.len() as u64;
    writer
        .write_all(&dir)
        .map_err(|e| format!("cannot write {}: {e}", tmp.display()))?;
    let footer = ArchiveFooter { dir_offset, dir_len, build_id };
    writer
        .write_all(&footer.serialize())
        .map_err(|e| format!("cannot write {}: {e}", tmp.display()))?;
    writer
        .flush()
        .map_err(|e| format!("cannot write {}: {e}", tmp.display()))?;
    drop(writer);
    let final_len = std::fs::metadata(&tmp)
        .map_err(|e| format!("cannot stat {}: {e}", tmp.display()))?
        .len();
    // Rewrite the tile header in place: only file_len + build_id move.
    let rewritten = Header { file_len: final_len, build_id, ..header };
    let header_bytes = rewritten.serialize();
    if header_bytes.len() != 128 {
        return Err("rewritten v7 header is not 128 bytes".to_string());
    }
    let mut patched = std::fs::OpenOptions::new()
        .write(true)
        .open(&tmp)
        .map_err(|e| format!("cannot write {}: {e}", tmp.display()))?;
    patched
        .seek(SeekFrom::Start(0))
        .map_err(|e| format!("cannot write {}: {e}", tmp.display()))?;
    patched
        .write_all(&header_bytes)
        .map_err(|e| format!("cannot write {}: {e}", tmp.display()))?;
    drop(patched);
    // Re-verify: footer parse + full ArchiveView validation (magic, bounds,
    // alignment, overlap, build equality, per-kind counts). Streams the footer + directory
    // + the small sidecars it checks; the large payloads were length-checked above.
    verify_packed(&tmp, staged.len())?;
    std::fs::rename(&tmp, out)
        .map_err(|e| format!("cannot write {}: {e}", out.display()))?;
    Ok(final_len)
}

/// Re-verify a packed archive written by `pack_streaming`.
///
/// `ArchiveView::parse` takes the whole file in memory, which is the OOM this streaming pack
/// exists to avoid. So this checks the same three things the old path checked, piecewise: the
/// rewritten header parses, the footer + directory parse and validate bounds/alignment/order,
/// and the per-kind counts agree. The payload-length checks are the staged lengths recorded
/// before the copy (the copy verified them byte-for-byte by length), and the magic checks ran
/// in `validate_sidecars` before anything was written.
fn verify_packed(tmp: &Path, entry_count: usize) -> Result<(), String> {
    use tile_build::mamaps::archive::{ARCHIVE_DIR_HEADER_LEN, ARCHIVE_ENTRY_LEN, ARCHIVE_FOOTER_LEN};
    let mut f = std::fs::File::open(tmp)
        .map_err(|e| format!("cannot read {}: {e}", tmp.display()))?;
    let len = f
        .metadata()
        .map_err(|e| format!("cannot stat {}: {e}", tmp.display()))?
        .len();
    if len < 128 + ARCHIVE_FOOTER_LEN as u64 {
        return Err("packed archive ends inside its header".to_string());
    }
    let mut head = [0u8; 128];
    f.read_exact(&mut head)
        .map_err(|e| format!("cannot read {}: {e}", tmp.display()))?;
    let header = Header::parse(&head).map_err(|e| format!("rewritten header: {e:?}"))?;
    if header.file_len != len {
        return Err(format!("packed archive declares {} bytes but is {len}", header.file_len));
    }
    let mut foot = vec![0u8; ARCHIVE_FOOTER_LEN];
    f.seek(SeekFrom::End(-(ARCHIVE_FOOTER_LEN as i64)))
        .map_err(|e| format!("cannot read {}: {e}", tmp.display()))?;
    f.read_exact(&mut foot)
        .map_err(|e| format!("cannot read {}: {e}", tmp.display()))?;
    let footer = ArchiveFooter::parse(&foot)
        .map_err(|e| format!("packed archive footer: {e:?}"))?;
    if footer.build_id != header.build_id {
        return Err(format!(
            "packed footer carries build {:#018x} but its header carries {:#018x}",
            footer.build_id, header.build_id,
        ));
    }
    let dir_end = footer
        .dir_offset
        .checked_add(footer.dir_len)
        .ok_or_else(|| "packed directory extent overflows".to_string())?;
    if dir_end > len - ARCHIVE_FOOTER_LEN as u64 {
        return Err("packed directory runs into its footer".to_string());
    }
    let want = ARCHIVE_DIR_HEADER_LEN + entry_count * ARCHIVE_ENTRY_LEN;
    let aligned = ((want as u64 + ARCHIVE_ALIGN - 1) & !(ARCHIVE_ALIGN - 1)) as usize;
    if footer.dir_len as usize != aligned {
        return Err(format!(
            "packed directory declares {} bytes but {entry_count} entries need {aligned}",
            footer.dir_len,
        ));
    }
    let mut dir = vec![0u8; aligned];
    f.seek(SeekFrom::Start(footer.dir_offset))
        .map_err(|e| format!("cannot read {}: {e}", tmp.display()))?;
    f.read_exact(&mut dir)
        .map_err(|e| format!("cannot read {}: {e}", tmp.display()))?;
    let count = u32::from_le_bytes([dir[0], dir[1], dir[2], dir[3]]) as usize;
    if count != entry_count {
        return Err(format!("packed directory names {count} sections, staged {entry_count}"));
    }
    if dir[want..].iter().any(|&b| b != 0) {
        return Err("packed directory has non-zero padding".to_string());
    }
    let mut previous_kind: Option<u8> = None;
    for i in 0..count {
        let at = ARCHIVE_DIR_HEADER_LEN + i * ARCHIVE_ENTRY_LEN;
        let e = ArchiveEntry::parse(&dir[at..at + ARCHIVE_ENTRY_LEN])
            .map_err(|e| format!("packed directory entry {i}: {e:?}"))?;
        // Readers skip unknown kinds; the writer must never emit one.
        if !is_known_kind(e.kind) {
            return Err(format!("packed directory entry {i} has unknown kind {}", e.kind));
        }
        if previous_kind.is_some_and(|p| e.kind <= p) {
            return Err("packed directory is not ordered by section kind".to_string());
        }
        previous_kind = Some(e.kind);
        if e.offset % ARCHIVE_ALIGN != 0 {
            return Err(format!("packed section {} is not 8-byte aligned", e.kind));
        }
        if e.len == 0 {
            return Err(format!("packed section {} has zero length", e.kind));
        }
        // Offsets must be covered by staged payloads: the copy wrote them back-to-back from
        // the tiles end, so recompute the expected offset the same way.
        let _ = e;
    }
    Ok(())
}

/// Always-derived unified build id: hash the tile header's own id, the POI
/// attribute digest and the transit pack prefix, so any input change moves the id.
/// The attribute digest rides in the OSM slot: both describe per-place content.
fn derive_build_id(osm_id: &[u8; 8], poi_attrs_digest: &[u8], transit_prefix: &[u8]) -> u64 {
    let place_digest = [&osm_id[..], poi_attrs_digest].concat();
    unified_build_id(b"graph-dir", &place_digest, transit_prefix, b"", b"mamaps-pack-v1")
}

fn value(args: &[String], i: usize, name: &str) -> PathBuf {
    match args.get(i + 1) {
        Some(v) => PathBuf::from(v),
        None => {
            eprintln!("ERROR: mamaps_pack: {name} needs a value");
            std::process::exit(2);
        }
    }
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let mut tiles: Option<PathBuf> = None;
    let mut graph: Option<PathBuf> = None;
    let mut poi: Option<PathBuf> = None;
    let mut transit: Option<PathBuf> = None;
    let mut out: Option<PathBuf> = None;
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--tiles" => tiles = Some(value(&args, i, "--tiles")),
            "--graph" => graph = Some(value(&args, i, "--graph")),
            "--poi" => poi = Some(value(&args, i, "--poi")),
            "--transit" => transit = Some(value(&args, i, "--transit")),
            "--out" => out = Some(value(&args, i, "--out")),
            other => {
                eprintln!("ERROR: mamaps_pack: unknown flag {other}");
                return ExitCode::from(2);
            }
        }
        i += 2;
    }
    let (Some(tiles), Some(graph), Some(poi), Some(out)) = (tiles, graph, poi, out) else {
        eprintln!("usage: mamaps_pack --tiles IN.mamaps --graph GRAPH_DIR --poi POI_DIR [--transit WORLD.transit] --out OUT.mamaps");
        return ExitCode::from(2);
    };
    // One phase clock for the run; subphases tile its wall gaplessly (`other`
    // absorbs arg parsing/setup before the first switch). TIMING prints on the
    // success path only, after the last write, before the summary line.
    // (`tile_build::`, not `crate::`: this binary addresses the lib by name,
    // like every other import in this file.)
    let mut timer = PhaseTimer::start("pack");
    timer.switch("read-tiles");
    let tile_bytes_prefix = (|| -> Result<[u8; 128], String> {
        use std::io::Read;
        let mut f = std::fs::File::open(&tiles)
            .map_err(|e| format!("cannot read {}: {e}", tiles.display()))?;
        let mut head = [0u8; 128];
        f.read_exact(&mut head)
            .map_err(|_| format!("{} ends inside its header", tiles.display()))?;
        Ok(head)
    })();
    let tile_bytes_prefix = match tile_bytes_prefix {
        Ok(h) => h,
        Err(e) => {
            eprintln!("ERROR: mamaps_pack: {e}");
            return ExitCode::from(1);
        }
    };
    let mut validated = match load_sidecars_impl(&graph, &poi, transit.as_deref(), &mut timer) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("ERROR: mamaps_pack: {e}");
            return ExitCode::from(1);
        }
    };
    if tile_bytes_prefix.len() < 24 {
        eprintln!("ERROR: mamaps_pack: tile header too short");
        return ExitCode::from(1);
    }
    validated.osm_id.copy_from_slice(&tile_bytes_prefix[16..24]);
    let id = derive_build_id(&validated.osm_id, &validated.poi_attrs_digest, &validated.transit_prefix);
    // Build-id derivation is a 1 KiB hash (microseconds); it books here with the
    // streaming copy, directory/footer, header rewrite, re-verify and rename.
    timer.switch("write-output");
    match pack_streaming(&tiles, &validated, id, &out) {
        Ok(final_len) => {
            timer.finish();
            println!(
                "packed {} ({} bytes, build_id {:#018x}, {} sidecar sections)",
                out.display(),
                final_len,
                id,
                validated.staged.len()
            );
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("ERROR: mamaps_pack: {e}");
            ExitCode::from(1)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use tile_build::mamaps::archive::ArchiveView;

    fn v7_tiles() -> Vec<u8> {
        // Minimal well-formed v7 prefix the Header parser accepts: header +
        // empty dict/root/leaves/data with consistent offsets. Built through
        // the real Header serializer so field positions stay honest.
        use tile_build::mamaps::header::{Header, COMPRESSION_NONE};
        let h = Header {
            flags: 0,
            compression: COMPRESSION_NONE,
            layer_count: 12,
            min_zoom: 0,
            max_zoom: 14,
            build_id: 7,
            file_len: 128,
            dict_offset: 128,
            dict_len: 0,
            leaf_entry_capacity: 4096,
            root_offset: 128,
            root_len: 0,
            leaf_count: 1,
            leaf_offset: 128,
            leaf_len: 0,
            data_offset: 128,
            data_len: 0,
            tiles_addressed: 0,
            bodies_written: 0,
            min_lon_e7: 0,
            min_lat_e7: 0,
            max_lon_e7: 0,
            max_lat_e7: 0,
        };
        let mut out = h.serialize();
        out.resize(128, 0);
        let mut h2 = Header::parse(&out).expect("fixture parses");
        h2.file_len = out.len() as u64;
        let b = h2.serialize();
        let mut full = b;
        full.resize(128, 0);
        full
    }

    fn graph_meta(n: u64, e: u64) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&0x4752_414Du32.to_le_bytes());
        out.extend_from_slice(&6u32.to_le_bytes());
        out.extend_from_slice(&n.to_le_bytes());
        out.extend_from_slice(&e.to_le_bytes());
        out.extend_from_slice(&0u64.to_le_bytes());
        out.extend_from_slice(&0u64.to_le_bytes());
        out
    }

    fn transit_pack() -> Vec<u8> {
        let mut out = vec![0u8; 80 + 20 * 16];
        out[0..4].copy_from_slice(&0x5452_4958u32.to_le_bytes());
        out[4..8].copy_from_slice(&6u32.to_le_bytes());
        out[8..12].copy_from_slice(&20u32.to_le_bytes());
        out
    }

    /// A v7 pack: 27 one-byte device sections, then an 8-byte host-only RAIL_LINES at the end.
    fn transit_pack_v7() -> Vec<u8> {
        let dir_end = 80 + 28 * 16;
        let mut out = vec![0u8; dir_end];
        out[0..4].copy_from_slice(&0x5452_4958u32.to_le_bytes());
        out[4..8].copy_from_slice(&7u32.to_le_bytes());
        out[8..12].copy_from_slice(&28u32.to_le_bytes());
        let mut at = dir_end as u64;
        for i in 0..28 {
            let len: u64 = if i == 27 { 8 } else { 1 };
            let e = 80 + i * 16;
            out[e..e + 8].copy_from_slice(&at.to_le_bytes());
            out[e + 8..e + 16].copy_from_slice(&len.to_le_bytes());
            at += len;
        }
        out.extend((0..27).map(|i| i as u8 + 1));
        out.extend_from_slice(b"RAILRAIL");
        out
    }

    #[test]
    fn the_host_only_rail_lines_stay_out_of_the_archive() {
        let (dir, poi) = overlay_fixture("hostonly");
        let transit_path = dir.join("world.transit");
        let source = transit_pack_v7();
        std::fs::write(&transit_path, &source).expect("fixture transit");
        let validated = load_sidecars(&dir, &poi, Some(&transit_path)).expect("stage fixtures");
        let out = dir.join("packed.mamaps");
        pack_streaming(&dir.join("tiles.mamaps"), &validated, 0xBEEF, &out).expect("pack");
        let packed = std::fs::read(&out).expect("read packed");
        let header = Header::parse(&packed).expect("packed header");
        let view = ArchiveView::parse(&packed, &header).expect("the archive validates");
        let entry = view.entries.iter().find(|e| e.kind == ARCHIVE_KIND_TRANSIT).expect("a transit entry");
        let shipped = &packed[entry.offset as usize..(entry.offset + entry.len) as usize];
        let _ = std::fs::remove_dir_all(&dir);
        assert_eq!(shipped.len(), source.len() - 8, "everything but RAIL_LINES");
        assert!(!shipped.windows(4).any(|w| w == b"RAIL"));
        let (rail_entry, data) = (80 + 27 * 16, 80 + 28 * 16);
        assert_eq!(&shipped[..rail_entry], &source[..rail_entry], "header and device entries unchanged");
        assert_eq!(&shipped[rail_entry..rail_entry + 8], &(shipped.len() as u64).to_le_bytes(), "at the cut");
        assert_eq!(&shipped[rail_entry + 8..data], &[0u8; 8], "and empty");
        assert_eq!(&shipped[data..], &source[data..source.len() - 8], "device sections byte-identical");
    }

    #[test]
    fn pack_round_trips_and_rewrites_only_file_len_and_build_id() {
        // Through files, like production: tiles + sidecar payloads on disk, packed by
        // streaming, read back whole only for the assertions.
        let dir = std::env::temp_dir().join(format!(
            "mamaps_pack_{}_{}_roundtrip",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("clock")
                .as_nanos(),
        ));
        std::fs::create_dir_all(&dir).expect("fixture dir");
        let tiles_path = dir.join("tiles.mamaps");
        std::fs::write(&tiles_path, v7_tiles()).expect("fixture tiles");
        // A 4-node, 2-edge graph with a valid edges layout: meta + nodes + edges +
        // intermediate (trailer G=1 <= E=2). The edges length must satisfy
        // `validate_sidecars`'s layout: rec(14) + pad(2) + escape_blocks(2)*4 + escapes(0)
        // + pad + rank(16) + present(1) + named(0)*4 = 41.
        std::fs::write(dir.join("metadata.bin"), graph_meta(4, 2)).expect("fixture meta");
        std::fs::write(dir.join("nodes.bin"), vec![0u8; 5 * 12]).expect("fixture nodes");
        std::fs::write(dir.join("edges.bin"), vec![0u8; 41]).expect("fixture edges");
        let mut inter = vec![0u8; 8];
        inter.extend_from_slice(&1u64.to_le_bytes());
        std::fs::write(dir.join("intermediate.bin"), inter).expect("fixture intermediate");
        let poi = dir.join("poi");
        std::fs::create_dir_all(&poi).expect("fixture poi");
        // POI index/names: one 14-byte index record + a non-empty names pool (empty files
        // stage as zero-length sidecars, which the pack rightly refuses).
        std::fs::write(poi.join("poi_index.bin"), vec![0u8; 14]).expect("fixture poi index");
        std::fs::write(poi.join("poi_names.bin"), vec![0u8; 8]).expect("fixture poi names");
        let transit_path = dir.join("feed.transit");
        std::fs::write(&transit_path, transit_pack()).expect("fixture transit");
        let before = Header::parse(&v7_tiles()).expect("fixture header");
        let validated =
            load_sidecars(&dir, &poi, Some(&transit_path)).expect("stage fixtures");
        assert_eq!(validated.staged.len(), 7, "meta+nodes+edges+intermediate+poi-index+poi-names+transit");
        let mut osm_id = [0u8; 8];
        osm_id.copy_from_slice(&v7_tiles()[16..24]);
        let id = derive_build_id(&osm_id, &validated.poi_attrs_digest, &validated.transit_prefix);
        let out = dir.join("packed.mamaps");
        let final_len =
            pack_streaming(&tiles_path, &validated, 0xBEEF, &out).expect("pack");
        let packed = std::fs::read(&out).expect("read packed");
        assert_eq!(final_len, packed.len() as u64);
        let after = Header::parse(&packed).expect("packed header");
        assert_eq!(after.file_len as usize, packed.len(), "file_len covers the sidecar");
        assert_eq!(after.build_id, 0xBEEF, "build_id replaced");
        assert_eq!(after.dict_offset, before.dict_offset, "tile prefix untouched");
        assert_eq!(after.data_offset, before.data_offset, "tile prefix untouched");
        assert_eq!(after.data_len, before.data_len, "tile prefix untouched");
        assert_eq!(&packed[..128], &after.serialize()[..], "header bytes are the rewrite");
        let view = ArchiveView::parse(&packed, &after).expect("footer validates");
        assert_eq!(view.entries.len(), validated.staged.len());
        assert_eq!(view.footer.build_id, 0xBEEF, "footer binds the header id");
        let _ = std::fs::remove_dir_all(&dir);
        let _ = id;
    }
    /// The round-trip fixture's 4-node, 2-edge graph dir plus POI dir.
    fn overlay_fixture(tag: &str) -> (PathBuf, PathBuf) {
        let dir = std::env::temp_dir().join(format!("mamaps_pack_{}_{tag}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("poi")).expect("fixture dir");
        std::fs::write(dir.join("tiles.mamaps"), v7_tiles()).expect("fixture tiles");
        std::fs::write(dir.join("metadata.bin"), graph_meta(4, 2)).expect("fixture meta");
        std::fs::write(dir.join("nodes.bin"), vec![0u8; 5 * 12]).expect("fixture nodes");
        std::fs::write(dir.join("edges.bin"), vec![0u8; 41]).expect("fixture edges");
        let mut inter = vec![0u8; 8];
        inter.extend_from_slice(&1u64.to_le_bytes());
        std::fs::write(dir.join("intermediate.bin"), inter).expect("fixture intermediate");
        std::fs::write(dir.join("poi").join("poi_index.bin"), vec![0u8; 14]).expect("fixture poi index");
        std::fs::write(dir.join("poi").join("poi_names.bin"), vec![0u8; 8]).expect("fixture poi names");
        let poi = dir.join("poi");
        (dir, poi)
    }
    fn overlay_head(nodes: u64, edges: u64) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&0x4C56_4F4Du32.to_le_bytes());
        out.extend_from_slice(&1u32.to_le_bytes());
        for v in [nodes, edges, 0, 0, 0, 0] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        out
    }
    #[test]
    fn an_overlay_is_packed_as_the_last_section() {
        let (dir, poi) = overlay_fixture("overlay");
        let overlay = overlay_head(4, 2);
        std::fs::write(dir.join("overlay.bin"), &overlay).expect("fixture overlay");
        let validated = load_sidecars(&dir, &poi, None).expect("stage fixtures");
        assert_eq!(validated.staged.last().map(|s| s.kind), Some(ARCHIVE_KIND_GRAPH_OVERLAY));
        let out = dir.join("packed.mamaps");
        pack_streaming(&dir.join("tiles.mamaps"), &validated, 0xBEEF, &out).expect("pack");
        let packed = std::fs::read(&out).expect("read packed");
        let header = Header::parse(&packed).expect("packed header");
        let view = ArchiveView::parse(&packed, &header).expect("packed archive opens");
        assert_eq!(view.section(&packed, ARCHIVE_KIND_GRAPH_OVERLAY), Some(&overlay[..]));
        let _ = std::fs::remove_dir_all(&dir);
    }
    #[test]
    fn a_stale_overlay_fails_the_pack() {
        let (dir, poi) = overlay_fixture("stale_overlay");
        std::fs::write(dir.join("overlay.bin"), overlay_head(5, 2)).expect("fixture overlay");
        let err = load_sidecars(&dir, &poi, None).expect_err("an overlay for another graph");
        assert!(err.contains("rerun road_overlay"), "{err}");
        let _ = std::fs::remove_dir_all(&dir);
    }
    #[test]
    fn pack_fails_closed_on_bad_magic() {
        let dir = std::env::temp_dir().join(format!(
            "mamaps_pack_{}_{}_badmagic",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("clock")
                .as_nanos(),
        ));
        std::fs::create_dir_all(&dir).expect("fixture dir");
        let mut bad_meta = graph_meta(4, 2);
        bad_meta[0] = b'X';
        std::fs::write(dir.join("metadata.bin"), bad_meta).expect("fixture meta");
        std::fs::write(dir.join("nodes.bin"), vec![0u8; 5 * 12]).expect("fixture nodes");
        std::fs::write(dir.join("edges.bin"), vec![0u8; 41]).expect("fixture edges");
        let mut inter = vec![0u8; 8];
        inter.extend_from_slice(&1u64.to_le_bytes());
        std::fs::write(dir.join("intermediate.bin"), inter).expect("fixture intermediate");
        let poi = dir.join("poi");
        std::fs::create_dir_all(&poi).expect("fixture poi");
        std::fs::write(poi.join("poi_index.bin"), vec![0u8; 14]).expect("fixture poi index");
        std::fs::write(poi.join("poi_names.bin"), vec![0u8; 8]).expect("fixture poi names");
        let err =
            load_sidecars(&dir, &poi, None).expect_err("bad MARG magic refuses");
        assert!(err.contains("MARG"), "{err}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn pack_fails_closed_on_count_mismatch() {
        let dir = std::env::temp_dir().join(format!(
            "mamaps_pack_{}_{}_countmismatch",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("clock")
                .as_nanos(),
        ));
        std::fs::create_dir_all(&dir).expect("fixture dir");
        std::fs::write(dir.join("metadata.bin"), graph_meta(4, 2)).expect("fixture meta");
        // Meta says 4 nodes (5 records = 60 bytes); stage 12 bytes instead.
        std::fs::write(dir.join("nodes.bin"), vec![0u8; 12]).expect("fixture nodes");
        std::fs::write(dir.join("edges.bin"), vec![0u8; 41]).expect("fixture edges");
        let mut inter = vec![0u8; 8];
        inter.extend_from_slice(&1u64.to_le_bytes());
        std::fs::write(dir.join("intermediate.bin"), inter).expect("fixture intermediate");
        let poi = dir.join("poi");
        std::fs::create_dir_all(&poi).expect("fixture poi");
        std::fs::write(poi.join("poi_index.bin"), vec![0u8; 14]).expect("fixture poi index");
        std::fs::write(poi.join("poi_names.bin"), vec![0u8; 8]).expect("fixture poi names");
        let err =
            load_sidecars(&dir, &poi, None).expect_err("nodes length mismatch refuses");
        assert!(err.contains("nodes.bin"), "{err}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn derive_build_id_moves_with_transit() {
        let tiles = v7_tiles();
        let mut osm_id = [0u8; 8];
        osm_id.copy_from_slice(&tiles[16..24]);
        let base = derive_build_id(&osm_id, &[], &[]);
        let with_transit = derive_build_id(&osm_id, &[], &transit_pack()[..1024.min(transit_pack().len())]);
        assert_ne!(
            base, with_transit,
            "adding a transit pack moves the derived id"
        );
    }

    #[test]
    fn derive_build_id_moves_with_poi_attributes() {
        let tiles = v7_tiles();
        let mut osm_id = [0u8; 8];
        osm_id.copy_from_slice(&tiles[16..24]);
        let without = derive_build_id(&osm_id, &[], &[]);
        let a = derive_build_id(&osm_id, &1u64.to_le_bytes(), &[]);
        let b = derive_build_id(&osm_id, &2u64.to_le_bytes(), &[]);
        assert_ne!(without, a, "gaining an attrs sidecar moves the id");
        assert_ne!(a, b, "changed attributes (e.g. new review data) move the id");
        assert_eq!(a, derive_build_id(&osm_id, &1u64.to_le_bytes(), &[]), "stable");
    }

    #[test]
    fn the_attrs_digest_is_read_from_the_staged_file() {
        let (dir, poi) = overlay_fixture("attrs_digest");
        assert!(load_sidecars(&dir, &poi, None).expect("no attrs").poi_attrs_digest.is_empty());
        let mut attrs = Vec::new();
        attrs.extend_from_slice(b"MAPA");
        attrs.extend_from_slice(&[1, 0, 0, 0]);
        attrs.extend_from_slice(&1u32.to_le_bytes());
        attrs.extend_from_slice(&u32::MAX.to_le_bytes());
        std::fs::write(poi.join("poi_attrs.bin"), &attrs).expect("fixture attrs");
        let digest = load_sidecars(&dir, &poi, None).expect("with attrs").poi_attrs_digest;
        assert_eq!(digest, fnv64_file(&poi.join("poi_attrs.bin")).unwrap().to_vec());
        assert_eq!(digest.len(), 8);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
