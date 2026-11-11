//! Overlay discovery: base path to `(base, segments, tombstones)`.
//!
//! The Kotlin worker owns manifest state (fetch, seq compare, segment
//! download, pin). This module reads what it pinned: `<base-dir>/overlays/`
//! holds `manifest.json` plus one `<seq:04d>/` dir of `overlay-SSSS.mamaps`
//! segments per applied overlay. Discovery opens the pinned sequence's
//! segments newest-first, parses the tombstone list, and refuses everything
//! (base alone) unless every pinned segment is present and length-plausible
//! and the manifest's base pin matches the base file's own header.
//!
//! Refusal is silent and total: a partial overlay never becomes visible,
//! and the map keeps reading the base exactly as before. What the Kotlin
//! side reports as state (pending bytes, applied seq) is unaffected — this
//! only decides what the next surface open serves.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use tilecodec::mamaps::read::{LeafCache, MamapsArchive, OverlayArchive, TileSource};

use super::file::FileRangeReader;

/// What discovery found beside the base file.
pub struct Discovered {
    /// Base + segments + tombstones, ready to serve patch-first.
    pub overlay: OverlayArchive<FileRangeReader>,
    /// The applied overlay sequence (for logging only).
    pub seq: u64,
}

/// Discover and open the pinned overlay for the base at `base_path`, or
/// `None` when there is none (no manifest, no segments, any mismatch).
///
/// Every file is opened read-only and never mutated. The base reads through
/// `base_leaves` — the surface's one shared cache, the same one the plain
/// single-archive path uses, so overlay discovery does not give every worker a
/// private copy of the (4 MiB-per-leaf, at planet scale) base index. Each segment
/// gets its own cache (offsets collide across archives). A corrupt segment header
/// refuses the whole overlay rather than serving a mixed map.
pub fn discover(base_path: &Path, base_leaves: Arc<LeafCache>) -> Option<Discovered> {
    let dir = base_path.parent()?;
    let overlays = dir.join("overlays");
    let text = std::fs::read_to_string(overlays.join("manifest.json")).ok()?;
    let manifest: serde_json::Value = serde_json::from_str(&text).ok()?;
    let seq = manifest.get("overlay_seq")?.as_u64()?;
    let base_pin = manifest.get("base")?;
    let base_build = parse_hex(base_pin.get("build_id")?.as_str()?)?;
    let base_len = base_pin.get("len")?.as_u64()?;

    // Base pin must match the file on disk: length first (cheap), then the
    // header build id (one 128-byte read, no mapping).
    let meta = std::fs::metadata(base_path).ok()?;
    if meta.len() != base_len && base_len != 0 {
        return None;
    }
    if read_build_id(base_path)? != base_build {
        return None;
    }

    let seq_dir = overlays.join(format!("{seq:04}"));
    let segments_json = manifest.get("segments")?.as_array()?;
    // Manifest order is ascending seq; serve newest-first.
    let mut names: Vec<(u64, String, u64)> = Vec::with_capacity(segments_json.len());
    for s in segments_json {
        names.push((
            s.get("seq")?.as_u64()?,
            s.get("url")?.as_str()?.to_string(),
            s.get("len")?.as_u64()?,
        ));
    }
    names.sort_by_key(|(seq, _, _)| *seq);
    let _ = names.iter().map(|(_, url, _)| url).collect::<Vec<_>>();

    let base = MamapsArchive::open_with_cache(
        FileRangeReader::open(base_path).ok()?,
        Arc::clone(&base_leaves),
    )
    .ok()?;
    // The overlay must apply to THIS base (defense in depth: Kotlin checks
    // too, but a sideloaded manifest must not mix builds).
    if !tilecodec::mamaps::read::base_matches(&base, base_build) {
        return None;
    }
    let mut segments = Vec::with_capacity(names.len());
    for (seg_seq, _, len) in names.iter().rev() {
        let path = seq_dir.join(format!("overlay-{seg_seq:04}.mamaps"));
        let meta = std::fs::metadata(&path).ok()?;
        if meta.len() != *len {
            return None;
        }
        let view = MamapsArchive::open_with_cache(
            FileRangeReader::open(&path).ok()?,
            Arc::new(LeafCache::new()),
        )
        .ok()?;
        segments.push(view);
    }
    if segments.len() != names.len() {
        return None;
    }
    let tombstones: Vec<u64> = manifest
        .get("tombstones")
        .and_then(|v| v.as_array())
        .map(|ids| ids.iter().filter_map(|id| id.as_u64()).collect())
        .unwrap_or_default();
    let pruned = tilecodec::mamaps::read::prune_tombstones(&base, tombstones);
    Some(Discovered {
        overlay: OverlayArchive::new(base, segments, pruned, base_leaves),
        seq,
    })
}

/// Read the base file's own header `build_id` (bytes 16..24 LE) without mapping it.
fn read_build_id(path: &Path) -> Option<u64> {
    use std::io::{Read as _, Seek as _};
    let mut file = std::fs::File::open(path).ok()?;
    let mut head = [0u8; 128];
    file.read_exact(&mut head).ok()?;
    let header = tilecodec::mamaps::header::Header::parse(&head).ok()?;
    Some(header.build_id)
}

/// A `0x…`-prefixed hex build id from the manifest.
fn parse_hex(raw: &str) -> Option<u64> {
    let digits = raw.strip_prefix("0x").or_else(|| raw.strip_prefix("0X"))?;
    u64::from_str_radix(digits, 16).ok()
}

/// Where discovery looks: `<base-dir>/overlays/manifest.json`. Exposed for
/// the Kotlin side's parity check (it pins the same path).
pub fn manifest_path(base_path: &Path) -> PathBuf {
    base_path
        .parent()
        .map(|d| d.join("overlays").join("manifest.json"))
        .unwrap_or_else(|| PathBuf::from("overlays/manifest.json"))
}
