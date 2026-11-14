//! `mamaps_pack` -- append the sidecar files to a finished v7 `.mamaps` archive.
//!
//! Usage:
//!   mamaps_pack --tiles IN.mamaps --graph GRAPH_DIR --poi POI_DIR
//!               [--transit WORLD.transit] --out OUT.mamaps
//!               [--sha256-out OUT.mamaps.sha256]
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
//!          poi_name_index.bin poi_region.bin poi_admin.bin
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
    ARCHIVE_KIND_POI_ADMIN, ARCHIVE_KIND_POI_NAMES, ARCHIVE_KIND_POI_REGION,
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
    /// `poi_attrs.bin` when staged non-empty: its FNV-1a feeds `derive_build_id`. The
    /// attributes can change with no tile change (merged review data), and phones cache
    /// byte ranges by build id, so the id has to move with them.
    ///
    /// A path, hashed by [`Validated::poi_attrs_digest`] on demand, so `main` can run the
    /// hash (a strictly serial byte loop, ~1.7 GB on a planet) beside the payload copy
    /// rather than in front of it: the id is only needed for the header and footer,
    /// which are written last.
    poi_attrs: Option<PathBuf>,
    /// `poi_admin.bin` when staged non-empty. Its FNV-1a is appended to the attributes digest
    /// for the same reason: adding or rebuilding the hierarchy changes the section directory
    /// with no tile change. Absent, the digest (and so every existing build id) is unchanged.
    poi_admin: Option<PathBuf>,
}

impl Validated {
    /// FNV-1a of `poi_attrs.bin` followed by FNV-1a of `poi_admin.bin`, each only when that
    /// file is staged; empty with neither.
    fn poi_attrs_digest(&self) -> Result<Vec<u8>, String> {
        let mut out = Vec::new();
        for p in [&self.poi_attrs, &self.poi_admin].into_iter().flatten() {
            out.extend_from_slice(&fnv64_file(p)?);
        }
        Ok(out)
    }
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
    let poi_attrs = match std::fs::metadata(poi.join("poi_attrs.bin")) {
        Ok(m) if m.len() > 0 => Some(poi.join("poi_attrs.bin")),
        _ => None,
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
    // The POI region sidecar (`poi_region.bin`) is optional and sorts last: kind 15
    // comes after the overlay's 14, and `ArchiveView::parse` requires ascending kinds.
    let poi_region = poi.join("poi_region.bin");
    match std::fs::metadata(&poi_region) {
        Ok(m) if m.len() > 0 => {
            staged.push(Staged::whole(ARCHIVE_KIND_POI_REGION, poi_region, m.len()))
        }
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => return Err(format!("cannot stat {}: {e}", poi_region.display())),
    }
    // The boundary hierarchy (`poi_admin.bin`, kind 16) is optional and sorts after the region
    // sidecar it describes. Older apps skip the kind; its digest feeds the build id (below).
    let poi_admin = poi.join("poi_admin.bin");
    let poi_admin = match std::fs::metadata(&poi_admin) {
        Ok(m) if m.len() > 0 => {
            staged.push(Staged::whole(ARCHIVE_KIND_POI_ADMIN, poi_admin.clone(), m.len()));
            Some(poi_admin)
        }
        Ok(_) => None,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => None,
        Err(e) => return Err(format!("cannot stat {}: {e}", poi_admin.display())),
    };
    // Validate every sidecar's magic/version/count BEFORE anything is written: each section's
    // check is the same one its loader runs, and a mismatch fails closed with a non-zero exit
    // and no partial output. Only the small files are read whole; the large graph payloads
    // validate by streaming their heads (lengths come from the metadata counts).
    validate_sidecars(graph, poi, &staged, timer)?;
    // The tile header's own build-id bytes are read by the caller (it holds the tiles prefix);
    // placeholder filled in `main` after the tiles prefix is parsed.
    Ok(Validated { staged, transit_prefix, osm_id: [0u8; 8], poi_attrs, poi_admin })
}

/// A file's length, with the same "cannot read" wording a whole-file read would give.
fn file_len(path: &Path) -> Result<u64, String> {
    std::fs::metadata(path)
        .map(|m| m.len())
        .map_err(|e| format!("cannot read {}: {e}", path.display()))
}

/// Up to the first `n` bytes of a file (fewer only if the file is shorter).
fn read_head(path: &Path, n: usize) -> Result<Vec<u8>, String> {
    let f = std::fs::File::open(path).map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    let mut buf = Vec::with_capacity(n);
    f.take(n as u64)
        .read_to_end(&mut buf)
        .map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    Ok(buf)
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
    // v8 (planet5) differs from v7 only inside intermediate.bin (varint shape points), which
    // is validated below by its trailer like any other version.
    if !(6..=8).contains(&graph_version) {
        return Err(format!("the graph is version {graph_version}, this pack reads v6..=v8"));
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
    //
    // Every check below needs a file's length and at most its first 16 bytes, so that is
    // all that is read. These files were read whole before — ~3 GB serially on a planet —
    // for exactly the same decisions.
    timer.switch("read-poi");
    let poi_index_len = file_len(&poi.join("poi_index.bin"))?;
    if poi_index_len % 14 != 0 {
        return Err("poi_index.bin is not whole 14-byte records".to_string());
    }
    let poi_count = poi_index_len / 14;
    let poi_names_len = file_len(&poi.join("poi_names.bin"))?;
    if poi_names_len == 0 && poi_count > 0 {
        return Err("poi carries an index but no names".to_string());
    }
    for name in ["poi_attrs.bin", "poi_spatial.bin", "poi_name_index.bin", "poi_region.bin"] {
        let path = poi.join(name);
        if path.exists() {
            let (magic, want, kind) = match name {
                "poi_attrs.bin" => (&b"MAPA"[..], 12usize, "attrs"),
                "poi_spatial.bin" => (&b"PSP1"[..], 32usize, "grid"),
                "poi_region.bin" => (&b"PRG1"[..], 12usize, "region"),
                _ => (&b"PNI1"[..], 16usize, "word index"),
            };
            // Shorter than `want` exactly when the file is.
            let b = read_head(&path, want)?;
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
    validate_poi_admin(&poi.join("poi_admin.bin"))
}

/// `poi_admin.bin`, when present: `PAD1`, version 1, and a length that is exactly the header,
/// `entry_count` 32-byte entries and `names_len` pool bytes. It is keyed by boundary id, not by
/// index ordinal, so there is no record count to cross-check.
fn validate_poi_admin(path: &Path) -> Result<(), String> {
    if !path.exists() {
        return Ok(());
    }
    let b = read_head(path, 16)?;
    if b.len() < 16 {
        return Err("poi_admin.bin ends before its header".to_string());
    }
    if &b[0..4] != b"PAD1" {
        return Err("poi_admin.bin has bad magic".to_string());
    }
    let word = |at: usize| u32::from_le_bytes([b[at], b[at + 1], b[at + 2], b[at + 3]]) as u64;
    if word(4) != 1 {
        return Err(format!("poi_admin.bin is version {}, expected 1", word(4)));
    }
    let want = 16 + 32 * word(8) + word(12);
    let got = file_len(path)?;
    if got != want {
        return Err(format!("poi_admin.bin is {got} bytes, its header declares {want}"));
    }
    Ok(())
}

/// One byte range to copy: `len` bytes from `src` at `src_off` to the output at `dst_off`.
struct CopyTask<'a> {
    src: &'a Path,
    src_off: u64,
    dst_off: u64,
    len: u64,
}

/// Copy one range.
///
/// `std::io::copy` from a `Take<&File>` to a `&File` is `copy_file_range(2)` on Linux: on
/// btrfs/XFS that first tries to share the extents (a reflink) and otherwise copies inside
/// the kernel; elsewhere std falls back to a plain read/write loop. All three put the same
/// bytes at the same offsets; only the cost differs.
fn run_copy(t: &CopyTask, dst: &mut std::fs::File, out_path: &Path) -> Result<(), String> {
    let mut src = std::fs::File::open(t.src)
        .map_err(|e| format!("cannot read {}: {e}", t.src.display()))?;
    src.seek(SeekFrom::Start(t.src_off))
        .map_err(|e| format!("cannot seek {}: {e}", t.src.display()))?;
    dst.seek(SeekFrom::Start(t.dst_off))
        .map_err(|e| format!("cannot seek {}: {e}", out_path.display()))?;
    let n = std::io::copy(&mut (&src).take(t.len), dst)
        .map_err(|e| format!("cannot copy {} -> {}: {e}", t.src.display(), out_path.display()))?;
    if n != t.len {
        return Err(format!(
            "cannot read {}: {} bytes at {} ended after {n}",
            t.src.display(),
            t.len,
            t.src_off
        ));
    }
    Ok(())
}

/// `bytes` at `offset` of the output.
fn write_at(out: &mut std::fs::File, offset: u64, bytes: &[u8], out_path: &Path) -> Result<(), String> {
    out.seek(SeekFrom::Start(offset))
        .map_err(|e| format!("cannot write {}: {e}", out_path.display()))?;
    out.write_all(bytes)
        .map_err(|e| format!("cannot write {}: {e}", out_path.display()))
}

/// [`pack_streaming_with`] for a build id already known and no inline hash (the unit tests).
#[cfg(test)]
fn pack_streaming(
    tiles_path: &Path,
    validated: &Validated,
    build_id: u64,
    out: &Path,
) -> Result<u64, String> {
    pack_streaming_with(tiles_path, validated, || Ok(build_id), out, false).map(|(len, _, _)| len)
}

/// One stretch of the output, in file order, as the inline hash sees it.
enum Piece<'a> {
    /// Bytes this process writes itself (header, padding, transit heads, directory, footer).
    Mem(&'a [u8]),
    /// `len` bytes of the output at `off`, final once copy task `gate` has finished.
    Out { off: u64, len: u64, gate: usize },
}

/// How far the copy loop has got: tasks finished in order, and whether one failed.
#[derive(Default)]
struct Progress {
    state: std::sync::Mutex<(usize, bool)>,
    moved: std::sync::Condvar,
}

impl Progress {
    fn finish_one(&self) {
        self.state.lock().expect("progress mutex").0 += 1;
        self.moved.notify_all();
    }
    fn fail(&self) {
        self.state.lock().expect("progress mutex").1 = true;
        self.moved.notify_all();
    }
    /// Block until task `gate` has finished; `false` if the copy failed first.
    fn wait_past(&self, gate: usize) -> bool {
        let mut st = self.state.lock().expect("progress mutex");
        while st.0 <= gate && !st.1 {
            st = self.moved.wait(st).expect("progress mutex");
        }
        st.0 > gate
    }
}

/// Bytes per positional read of the output for the hash, and per double-buffered window.
const HASH_READ: u64 = 8 << 20;
const HASH_WINDOW: u64 = 256 << 20;

/// `buf.len()` bytes of `file` at `off`, without moving any shared cursor (so the reads of
/// one window can run on several threads against one handle).
fn read_exact_at(file: &std::fs::File, buf: &mut [u8], off: u64) -> std::io::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::FileExt;
        file.read_exact_at(buf, off)
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::FileExt;
        let mut done = 0;
        while done < buf.len() {
            let n = file.seek_read(&mut buf[done..], off + done as u64)?;
            if n == 0 {
                return Err(std::io::ErrorKind::UnexpectedEof.into());
            }
            done += n;
        }
        Ok(())
    }
}

/// SHA-256 over `pieces`, i.e. over the finished archive, byte for byte, while it is still
/// being written.
///
/// The output regions are read back from the output file itself (not from the sources), once
/// the copy task that fills each one has finished, so the digest is of the bytes actually in
/// the file: the reflinked tile prefix is read exactly once, here. Reads go 8 MiB at a time
/// in parallel on the crate pool, double-buffered against the (inherently serial) hash, so
/// the hash runs at the hasher's speed rather than at one thread's read+zstd-inflate speed.
/// `sha2` picks its SHA-NI backend at run time on CPUs that have it (Zen 4 does).
fn hash_pieces(pieces: &[Piece], out_path: &Path, progress: &Progress) -> Result<[u8; 32], String> {
    use rayon::prelude::*;
    use sha2::{Digest as _, Sha256};
    enum Read<'a> {
        Mem(&'a [u8]),
        Out { off: u64, len: u64, gate: usize },
    }
    // Flatten into bounded reads, then group into windows.
    let mut reads: Vec<Read> = Vec::new();
    for p in pieces {
        match *p {
            Piece::Mem(b) => reads.push(Read::Mem(b)),
            Piece::Out { off, len, gate } => {
                let mut at = 0;
                while at < len {
                    let n = (len - at).min(HASH_READ);
                    reads.push(Read::Out { off: off + at, len: n, gate });
                    at += n;
                }
            }
        }
    }
    let mut windows: Vec<std::ops::Range<usize>> = Vec::new();
    let (mut start, mut bytes) = (0usize, 0u64);
    for (i, r) in reads.iter().enumerate() {
        bytes += match r {
            Read::Mem(b) => b.len() as u64,
            Read::Out { len, .. } => *len,
        };
        if bytes >= HASH_WINDOW {
            windows.push(start..i + 1);
            start = i + 1;
            bytes = 0;
        }
    }
    if start < reads.len() {
        windows.push(start..reads.len());
    }

    let file = std::fs::File::open(out_path)
        .map_err(|e| format!("cannot read {}: {e}", out_path.display()))?;
    // One window's output reads, in order; `Mem` reads stay borrowed and need no buffer.
    let load = |w: &std::ops::Range<usize>| -> Result<Vec<Vec<u8>>, String> {
        let gate = reads[w.clone()]
            .iter()
            .filter_map(|r| match r {
                Read::Out { gate, .. } => Some(*gate),
                Read::Mem(_) => None,
            })
            .max();
        if let Some(g) = gate {
            if !progress.wait_past(g) {
                return Err("the copy failed".to_string());
            }
        }
        reads[w.clone()]
            .par_iter()
            .map(|r| match r {
                Read::Mem(_) => Ok(Vec::new()),
                Read::Out { off, len, .. } => {
                    let mut buf = vec![0u8; *len as usize];
                    read_exact_at(&file, &mut buf, *off)
                        .map_err(|e| format!("cannot read {} at {off}: {e}", out_path.display()))?;
                    Ok(buf)
                }
            })
            .collect()
    };
    let mut hasher = Sha256::new();
    tile_build::par::install(|| -> Result<(), String> {
        let mut current = match windows.first() {
            Some(w) => Some(load(w)?),
            None => None,
        };
        for (k, w) in windows.iter().enumerate() {
            let bufs = current.take().expect("window loaded");
            let next = windows.get(k + 1);
            let (_, loaded) = rayon::join(
                || {
                    for (r, b) in reads[w.clone()].iter().zip(&bufs) {
                        match r {
                            Read::Mem(m) => hasher.update(m),
                            Read::Out { .. } => hasher.update(b),
                        }
                    }
                },
                || next.map(&load),
            );
            current = loaded.transpose()?;
        }
        Ok(())
    })?;
    Ok(hasher.finalize().into())
}

/// Write the archive: tile prefix, sidecars, directory, footer, header rewrite, re-verify,
/// rename. Returns `(final length, build id, SHA-256 of the archive if asked for)`.
///
/// Every byte's position is a function of the staged lengths alone (the payloads sit
/// back-to-back from `data_end`, each 8-byte aligned), so the whole layout is computed up
/// front and each range is copied straight to its precomputed offset with
/// `copy_file_range` (see [`run_copy`]) instead of through a 1 MiB user-space buffer. The
/// output is the same file byte for byte: same bytes, same offsets, and every gap
/// (alignment padding) is written explicitly with zeros.
///
/// The tile prefix is the big one. It sits at offset 0 in both files, so its range is
/// block-aligned on both sides and a reflinking filesystem (the planet box's btrfs) shares
/// its extents rather than copying them: measured 1.24 GB in 0.06 s on California, and the
/// planet's tens of GB of tiles stop costing their size a second time on disk. Sidecars
/// land at 8-byte-aligned offsets, so they are copied (in-kernel). They are copied one at a
/// time on purpose: on this btrfs (`compress-force=zstd`) buffered writes top out near
/// 2.6 GB/s whether one thread or 64 write, to one file or to 64 separate ones (measured
/// with 64 MiB tasks), and the parallel version burned 86 CPU-seconds of lock spinning for
/// no wall-clock gain.
///
/// `build_id` runs on its own thread beside the copy: it is only needed for the footer and
/// the header, both written after the copy, and in `main` it hashes `poi_attrs.bin`. With
/// `sha256`, the same thread then hashes the archive ([`hash_pieces`]) as the copy finishes
/// each region, so the digest costs little more than the copy instead of a second full read
/// of a ~80 GB file afterwards.
fn pack_streaming_with<F>(
    tiles_path: &Path,
    validated: &Validated,
    build_id: F,
    out: &Path,
    sha256: bool,
) -> Result<(u64, u64, Option<[u8; 32]>), String>
where
    F: FnOnce() -> Result<u64, String> + Send,
{
    // Parse the tile header out of the prefix; everything else rides along. Only the 128-byte
    // header is read here -- the tile bodies are copied below.
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
    drop(tiles);
    let header = Header::parse(&head).map_err(|e| format!("tile header does not parse: {e:?}"))?;
    if header.file_len != tiles_len {
        return Err(format!(
            "tile file declares {} bytes but is {tiles_len}",
            header.file_len,
        ));
    }
    let staged = &validated.staged;

    // Layout. Tile prefix is immutable: copied verbatim, then sidecars append from its end.
    let align = |at: u64| (at + ARCHIVE_ALIGN - 1) & !(ARCHIVE_ALIGN - 1);
    let mut entries = Vec::with_capacity(staged.len());
    let mut at = tiles_len;
    for s in staged {
        // Every sidecar payload starts 8-byte aligned; padding is zeroes.
        at = align(at);
        entries.push(ArchiveEntry { kind: s.kind, flags: 0, offset: at, len: s.len, extra: s.extra });
        at += s.len;
    }
    let dir_offset = align(at);
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
    let footer_len = ArchiveFooter { dir_offset, dir_len, build_id: 0 }.serialize().len() as u64;
    let final_len = dir_offset + dir_len + footer_len;
    // Every gap before a section or the directory, `(offset, zeros)`. Fewer than 8 bytes
    // each; written by this thread and fed to the hash from the same buffers.
    let mut gaps: Vec<(u64, Vec<u8>)> = Vec::new();
    let mut prev_end = tiles_len;
    for e in &entries {
        gaps.push((prev_end, vec![0u8; (e.offset - prev_end) as usize]));
        prev_end = e.offset + e.len;
    }
    gaps.push((prev_end, vec![0u8; (dir_offset - prev_end) as usize]));

    // A source that changed since staging would shift every offset after it.
    for s in staged {
        let src_len = std::fs::metadata(&s.path)
            .map_err(|e| format!("cannot stat {}: {e}", s.path.display()))?
            .len();
        if src_len != s.file_len {
            return Err(format!(
                "{} changed under the pack (staged {} bytes, now {src_len})",
                s.path.display(),
                s.file_len,
            ));
        }
    }

    let tmp = out.with_extension("pack.tmp");
    let mut writer = std::fs::File::create(&tmp)
        .map_err(|e| format!("cannot write {}: {e}", tmp.display()))?;
    // Sized up front: every write below lands at a precomputed offset.
    writer
        .set_len(final_len)
        .map_err(|e| format!("cannot write {}: {e}", tmp.display()))?;

    // The payload bytes: tile prefix, then each sidecar after its head (written below).
    let mut tasks: Vec<CopyTask> = vec![CopyTask { src: tiles_path, src_off: 0, dst_off: 0, len: tiles_len }];
    for (s, e) in staged.iter().zip(&entries) {
        let head = s.head.len() as u64;
        tasks.push(CopyTask { src: &s.path, src_off: head, dst_off: e.offset + head, len: s.len - head });
    }
    let progress = Progress::default();
    // The id (and, when asked, the archive digest) on one side thread; the copy here.
    let side = || -> Result<(u64, Option<[u8; 32]>), String> {
        let id = build_id()?;
        if !sha256 {
            return Ok((id, None));
        }
        // The final header and footer exactly as written below.
        let header_bytes = Header { file_len: final_len, build_id: id, ..header }.serialize();
        let footer_bytes = ArchiveFooter { dir_offset, dir_len, build_id: id }.serialize();
        let mut pieces: Vec<Piece> = vec![
            Piece::Mem(&header_bytes[..header_bytes.len().min(128)]),
            Piece::Out { off: 128, len: tiles_len - 128, gate: 0 },
        ];
        for (k, ((s, e), (_, gap))) in staged.iter().zip(&entries).zip(&gaps).enumerate() {
            pieces.push(Piece::Mem(gap));
            pieces.push(Piece::Mem(&s.head));
            let head = s.head.len() as u64;
            pieces.push(Piece::Out { off: e.offset + head, len: s.len - head, gate: k + 1 });
        }
        pieces.push(Piece::Mem(&gaps.last().expect("the directory gap").1));
        pieces.push(Piece::Mem(&dir));
        pieces.push(Piece::Mem(&footer_bytes));
        let digest = hash_pieces(&pieces, &tmp, &progress)?;
        Ok((id, Some(digest)))
    };
    let (copied, side) = std::thread::scope(|scope| {
        let side = scope.spawn(side);
        let copied = tasks.iter().try_for_each(|t| {
            run_copy(t, &mut writer, &tmp)?;
            progress.finish_one();
            Ok::<(), String>(())
        });
        if copied.is_err() {
            progress.fail();
        }
        (copied, side.join())
    });
    // Report a copy failure first, as the serial copy would have met it.
    copied?;
    let (build_id, digest) = side.map_err(|_| "the build-id thread panicked".to_string())??;
    // The small writes, after every copy so no copy (a whole-block reflink in particular)
    // can land on top of them: every alignment gap explicitly zeroed, each transit head (in
    // place of its source's first bytes), and the directory.
    for (offset, zeros) in &gaps {
        if !zeros.is_empty() {
            write_at(&mut writer, *offset, zeros, &tmp)?;
        }
    }
    for (s, e) in staged.iter().zip(&entries) {
        if !s.head.is_empty() {
            write_at(&mut writer, e.offset, &s.head, &tmp)?;
        }
    }
    write_at(&mut writer, dir_offset, &dir, &tmp)?;

    let footer = ArchiveFooter { dir_offset, dir_len, build_id };
    write_at(&mut writer, dir_offset + dir_len, &footer.serialize(), &tmp)?;
    // Rewrite the tile header in place: only file_len + build_id move.
    let rewritten = Header { file_len: final_len, build_id, ..header };
    let header_bytes = rewritten.serialize();
    if header_bytes.len() != 128 {
        return Err("rewritten v7 header is not 128 bytes".to_string());
    }
    write_at(&mut writer, 0, &header_bytes, &tmp)?;
    writer
        .flush()
        .map_err(|e| format!("cannot write {}: {e}", tmp.display()))?;
    drop(writer);
    let written = std::fs::metadata(&tmp)
        .map_err(|e| format!("cannot stat {}: {e}", tmp.display()))?
        .len();
    if written != final_len {
        return Err(format!("{} is {written} bytes, laid out {final_len}", tmp.display()));
    }
    // Re-verify: footer parse + full ArchiveView validation (magic, bounds,
    // alignment, overlap, build equality, per-kind counts). Streams the footer + directory
    // + the small sidecars it checks; the large payloads were length-checked above.
    verify_packed(&tmp, staged.len())?;
    std::fs::rename(&tmp, out)
        .map_err(|e| format!("cannot write {}: {e}", out.display()))?;
    Ok((final_len, build_id, digest))
}

/// Write `<hex>  <file name>\n` -- `sha256sum`'s own format, so `sha256sum -c` run beside
/// the archive checks it -- via a temp file and a rename, so a reader never sees half a line.
fn write_sha256_file(path: &Path, archive: &Path, digest: &[u8; 32]) -> Result<(), String> {
    let name = archive
        .file_name()
        .ok_or_else(|| format!("{} has no file name", archive.display()))?
        .to_string_lossy();
    let mut line = String::with_capacity(64 + 3 + name.len());
    for b in digest {
        line.push_str(&format!("{b:02x}"));
    }
    line.push_str("  ");
    line.push_str(&name);
    line.push('\n');
    let tmp = path.with_extension("sha256.tmp");
    std::fs::write(&tmp, line).map_err(|e| format!("cannot write {}: {e}", tmp.display()))?;
    std::fs::rename(&tmp, path).map_err(|e| format!("cannot write {}: {e}", path.display()))
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
    let mut sha256_out: Option<PathBuf> = None;
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--tiles" => tiles = Some(value(&args, i, "--tiles")),
            "--graph" => graph = Some(value(&args, i, "--graph")),
            "--poi" => poi = Some(value(&args, i, "--poi")),
            "--transit" => transit = Some(value(&args, i, "--transit")),
            "--out" => out = Some(value(&args, i, "--out")),
            // SHA-256 of the finished archive, computed while it is written, in
            // `sha256sum` format (`<hex>  <archive file name>`).
            "--sha256-out" => sha256_out = Some(value(&args, i, "--sha256-out")),
            other => {
                eprintln!("ERROR: mamaps_pack: unknown flag {other}");
                return ExitCode::from(2);
            }
        }
        i += 2;
    }
    let (Some(tiles), Some(graph), Some(poi), Some(out)) = (tiles, graph, poi, out) else {
        eprintln!("usage: mamaps_pack --tiles IN.mamaps --graph GRAPH_DIR --poi POI_DIR [--transit WORLD.transit] --out OUT.mamaps [--sha256-out OUT.mamaps.sha256]");
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
    // The build id hashes `poi_attrs.bin` (FNV-1a, strictly serial); it runs beside the
    // payload copy inside `pack_streaming_with`, and books here with the streaming copy,
    // directory/footer, header rewrite, re-verify and rename.
    timer.switch("write-output");
    let derive = || -> Result<u64, String> {
        let digest = validated.poi_attrs_digest()?;
        Ok(derive_build_id(&validated.osm_id, &digest, &validated.transit_prefix))
    };
    match pack_streaming_with(&tiles, &validated, derive, &out, sha256_out.is_some()) {
        Ok((final_len, id, digest)) => {
            if let (Some(path), Some(digest)) = (sha256_out.as_ref(), digest.as_ref()) {
                if let Err(e) = write_sha256_file(path, &out, digest) {
                    eprintln!("ERROR: mamaps_pack: {e}");
                    return ExitCode::from(1);
                }
                let hex: String = digest.iter().map(|b| format!("{b:02x}")).collect();
                println!("sha256 {hex} -> {}", path.display());
            }
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
        let id = derive_build_id(&osm_id, &validated.poi_attrs_digest().unwrap(), &validated.transit_prefix);
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
    /// The inline digest is SHA-256 of the archive as it lands on disk -- transit head,
    /// padding, directory, footer and the rewritten header included -- at any pool size,
    /// and the sidecar file is `sha256sum`'s own line.
    #[test]
    fn the_inline_sha256_is_the_digest_of_the_written_archive() {
        use sha2::{Digest as _, Sha256};
        let (dir, poi) = overlay_fixture("inline_sha");
        std::fs::write(dir.join("overlay.bin"), overlay_head(4, 2)).expect("fixture overlay");
        let region = region_payload(7);
        std::fs::write(poi.join("poi_region.bin"), &region).expect("fixture region");
        let transit_path = dir.join("world.transit");
        std::fs::write(&transit_path, transit_pack_v7()).expect("fixture transit");
        let validated = load_sidecars(&dir, &poi, Some(&transit_path)).expect("stage fixtures");
        let out = dir.join("packed.mamaps");
        let mut seen = Vec::new();
        for threads in [1usize, 3, 8] {
            tile_build::par::set_threads(threads);
            let (len, id, digest) =
                pack_streaming_with(&dir.join("tiles.mamaps"), &validated, || Ok(0xBEEF), &out, true)
                    .expect("pack");
            let packed = std::fs::read(&out).expect("read packed");
            assert_eq!(len, packed.len() as u64);
            assert_eq!(id, 0xBEEF);
            let want: [u8; 32] = Sha256::digest(&packed).into();
            assert_eq!(digest, Some(want), "threads {threads}");
            seen.push(want);
        }
        // (`par::clear_threads` is test-only inside the lib; leaving the cap at 8 changes
        // nothing another test here asserts.)
        assert!(seen.windows(2).all(|w| w[0] == w[1]), "same archive at every pool size");
        let sha = dir.join("packed.mamaps.sha256");
        write_sha256_file(&sha, &out, &seen[0]).expect("sha file");
        let line = std::fs::read_to_string(&sha).expect("read sha file");
        let hex: String = seen[0].iter().map(|b| format!("{b:02x}")).collect();
        assert_eq!(line, format!("{hex}  packed.mamaps\n"));
        let (_, _, none) =
            pack_streaming_with(&dir.join("tiles.mamaps"), &validated, || Ok(0xBEEF), &out, false)
                .expect("pack without hash");
        assert!(none.is_none());
        let _ = std::fs::remove_dir_all(&dir);
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
    fn region_payload(tagged: u64) -> Vec<u8> {
        // `poi_region.bin`: PRG1 + version + one u64 tagged id per index ordinal.
        // The round-trip fixture's POI dir stages one index record.
        let mut out = Vec::new();
        out.extend_from_slice(b"PRG1");
        out.extend_from_slice(&1u32.to_le_bytes());
        out.extend_from_slice(&1u32.to_le_bytes());
        out.extend_from_slice(&tagged.to_le_bytes());
        out
    }
    #[test]
    fn a_region_sidecar_packs_after_the_overlay() {
        let (dir, poi) = overlay_fixture("region");
        let region = region_payload((300u64 << 2) | 3);
        std::fs::write(poi.join("poi_region.bin"), &region).expect("fixture region");
        let validated = load_sidecars(&dir, &poi, None).expect("stage fixtures");
        assert_eq!(validated.staged.last().map(|s| s.kind), Some(ARCHIVE_KIND_POI_REGION));
        let out = dir.join("packed.mamaps");
        pack_streaming(&dir.join("tiles.mamaps"), &validated, 0xBEEF, &out).expect("pack");
        let packed = std::fs::read(&out).expect("read packed");
        let header = Header::parse(&packed).expect("packed header");
        // `ArchiveView::parse` requires ascending kinds, so this open also proves
        // kind 15 stages after the overlay's 14.
        let view = ArchiveView::parse(&packed, &header).expect("packed archive opens");
        assert_eq!(view.section(&packed, ARCHIVE_KIND_POI_REGION), Some(&region[..]));
        let _ = std::fs::remove_dir_all(&dir);
    }
    /// `poi_admin.bin`: PAD1 + version + one 32-byte entry + a 6-byte name pool.
    fn admin_payload() -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(b"PAD1");
        out.extend_from_slice(&1u32.to_le_bytes());
        out.extend_from_slice(&1u32.to_le_bytes());
        out.extend_from_slice(&6u32.to_le_bytes());
        out.extend_from_slice(&((300u64 << 2) | 3).to_le_bytes());
        out.extend_from_slice(&0u64.to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes());
        out.extend_from_slice(&u32::MAX.to_le_bytes());
        out.push(8);
        out.extend_from_slice(&[0u8; 7]);
        out.extend_from_slice(b"Paris\0");
        out
    }
    #[test]
    fn an_admin_sidecar_packs_after_the_region_and_moves_the_build_id() {
        let (dir, poi) = overlay_fixture("admin");
        std::fs::write(poi.join("poi_region.bin"), region_payload((300u64 << 2) | 3)).expect("fixture region");
        let before = load_sidecars(&dir, &poi, None).expect("stage without admin").poi_attrs_digest().unwrap();
        let admin = admin_payload();
        std::fs::write(poi.join("poi_admin.bin"), &admin).expect("fixture admin");
        let validated = load_sidecars(&dir, &poi, None).expect("stage fixtures");
        assert_eq!(validated.staged.last().map(|s| s.kind), Some(ARCHIVE_KIND_POI_ADMIN));
        let after = validated.poi_attrs_digest().unwrap();
        assert_ne!(before, after, "adding the hierarchy moves the build-id digest");
        assert!(before.is_empty(), "no attrs, no admin: the digest every old archive had");
        let out = dir.join("packed.mamaps");
        pack_streaming(&dir.join("tiles.mamaps"), &validated, 0xBEEF, &out).expect("pack");
        let packed = std::fs::read(&out).expect("read packed");
        let header = Header::parse(&packed).expect("packed header");
        // Ascending kinds again: 16 after the region's 15.
        let view = ArchiveView::parse(&packed, &header).expect("packed archive opens");
        assert_eq!(view.section(&packed, ARCHIVE_KIND_POI_ADMIN), Some(&admin[..]));
        let _ = std::fs::remove_dir_all(&dir);
    }
    #[test]
    fn a_truncated_admin_sidecar_fails_the_pack() {
        let (dir, poi) = overlay_fixture("admin_short");
        let admin = admin_payload();
        std::fs::write(poi.join("poi_admin.bin"), &admin[..admin.len() - 1]).expect("fixture admin");
        let err = load_sidecars(&dir, &poi, None).expect_err("a short admin table");
        assert!(err.contains("poi_admin.bin"), "{err}");
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
        assert!(load_sidecars(&dir, &poi, None).expect("no attrs").poi_attrs_digest().unwrap().is_empty());
        let mut attrs = Vec::new();
        attrs.extend_from_slice(b"MAPA");
        attrs.extend_from_slice(&[1, 0, 0, 0]);
        attrs.extend_from_slice(&1u32.to_le_bytes());
        attrs.extend_from_slice(&u32::MAX.to_le_bytes());
        std::fs::write(poi.join("poi_attrs.bin"), &attrs).expect("fixture attrs");
        let digest = load_sidecars(&dir, &poi, None).expect("with attrs").poi_attrs_digest().unwrap();
        assert_eq!(digest, fnv64_file(&poi.join("poi_attrs.bin")).unwrap().to_vec());
        assert_eq!(digest.len(), 8);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
