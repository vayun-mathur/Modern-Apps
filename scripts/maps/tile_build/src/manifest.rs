//! `manifest` — the `planet4.manifest.json` schema for incremental updates.
//!
//! The manifest is the only versioned thing the client fetches on a check
//! (bytes, not gigabytes): it pins the base archive, names the overlay
//! segments and sidecar sections, and carries the hashes that make each
//! download verifiable and resumable. See the plan file
//! (`maps_incremental_updates.plan.md`, Phase 1) for the rollout order.
//!
//! Wire form is plain JSON with fixed field names (documented on each
//! struct); the publisher (`upload_planet.py --manifest`) writes it, the
//! client parses it. No serde dependency: the host crate stays cargo-offline
//! buildable, and the schema is small enough to emit by hand.

/// Manifest format version. Bump when a client must understand new fields to
/// apply the overlay safely.
pub const MANIFEST_VERSION: u32 = 1;
/// Oldest manifest version this publisher writes. Clients below
/// `MIN_CLIENT_COMPAT` must full-download instead of applying overlays.
pub const MIN_CLIENT_COMPAT: u32 = 1;
/// Largest overlay segment the publisher emits. 512 MiB keeps peak transient
/// phone disk to one segment (the no-2x guarantee) while keeping the segment
/// count for a monthly overlay in the single digits.
pub const SEGMENT_CAP_BYTES: u64 = 512 * 1024 * 1024;
/// Chunk size for large sidecar sections (graph nodes/edges/intermediate,
/// transit). 32 MiB granularity lets the client skip unchanged chunks.
pub const SECTION_CHUNK_BYTES: u64 = 32 * 1024 * 1024;

/// The base archive this overlay applies to.
pub struct BaseRef {
    /// Download URL of the base `.mamaps` (today `planet4.mamaps`).
    pub url: String,
    /// `build_id` from the base header: the overlay refuses a mismatched base.
    pub build_id: u64,
    /// Base length in bytes (truncation check).
    pub len: u64,
}

/// One overlay segment file: a small self-contained `.mamaps` holding only
/// changed/added tile bodies plus tombstones, with overlay-relative offsets.
pub struct SegmentRef {
    /// Download URL of the segment file.
    pub url: String,
    /// Segment length in bytes.
    pub len: u64,
    /// Lowercase hex SHA-256 of the segment file.
    pub sha256: String,
    /// Sequence index (apply order; segments are independent but ordered).
    pub seq: u32,
}

/// One sidecar section published as an independently chunked file (large
/// kinds: graph nodes/edges/intermediate, transit) or ridden inside overlay
/// segments (small kinds — absent here).
pub struct SectionRef {
    /// Archive kind id (`ARCHIVE_KIND_*`).
    pub kind: u8,
    /// Download URL of the section file.
    pub url: String,
    /// Section length in bytes.
    pub len: u64,
    /// Lowercase hex SHA-256 of the whole section file.
    pub sha256: String,
    /// Chunk size in bytes (always [`SECTION_CHUNK_BYTES`]).
    pub chunk_size: u64,
    /// Lowercase hex SHA-256 per chunk, in order.
    pub chunk_hashes: Vec<String>,
}

/// The manifest: base pin + overlay sequence + sections + compat floor.
pub struct Manifest {
    /// [`MANIFEST_VERSION`].
    pub version: u32,
    /// Base archive reference.
    pub base: BaseRef,
    /// Overlay sequence number (monotonic; client applies when ahead).
    pub overlay_seq: u64,
    /// `overlay_build_id` (derived from base id + new OSM id + section hashes).
    pub overlay_build_id: u64,
    /// Overlay segments, ascending `seq`.
    pub segments: Vec<SegmentRef>,
    /// Independently chunked sidecar sections, ascending `kind`.
    pub sections: Vec<SectionRef>,
    /// Deleted tile ids (answered absent even though the base holds them).
    pub tombstones: Vec<u64>,
    /// [`MIN_CLIENT_COMPAT`].
    pub min_client: u32,
}

/// Render a [`Manifest`] as canonical JSON (fixed field order, no whitespace
/// beyond separators) so publishers and tests hash the same bytes.
pub fn render(manifest: &Manifest) -> String {
    let mut out = String::from("{");
    out.push_str(&format!("\"version\":{},", manifest.version));
    out.push_str("\"base\":{");
    out.push_str(&format!(
        "\"url\":{},\"build_id\":\"{:#018x}\",\"len\":{}",
        json_string(&manifest.base.url),
        manifest.base.build_id,
        manifest.base.len,
    ));
    out.push_str("},");
    out.push_str(&format!(
        "\"overlay_seq\":{},\"overlay_build_id\":\"{:#018x}\",",
        manifest.overlay_seq, manifest.overlay_build_id,
    ));
    out.push_str("\"segments\":[");
    for (n, s) in manifest.segments.iter().enumerate() {
        if n > 0 {
            out.push(',');
        }
        out.push_str(&format!(
            "{{\"url\":{},\"len\":{},\"sha256\":{},\"seq\":{}}}",
            json_string(&s.url),
            s.len,
            json_string(&s.sha256),
            s.seq,
        ));
    }
    out.push_str("],\"sections\":[");
    for (n, s) in manifest.sections.iter().enumerate() {
        if n > 0 {
            out.push(',');
        }
        out.push_str(&format!(
            "{{\"kind\":{},\"url\":{},\"len\":{},\"sha256\":{},\"chunk_size\":{},\"chunk_hashes\":[",
            s.kind,
            json_string(&s.url),
            s.len,
            json_string(&s.sha256),
            s.chunk_size,
        ));
        for (m, h) in s.chunk_hashes.iter().enumerate() {
            if m > 0 {
                out.push(',');
            }
            out.push_str(&json_string(h));
        }
        out.push_str("]}");
    }
    out.push_str("],\"tombstones\":[");
    for (n, id) in manifest.tombstones.iter().enumerate() {
        if n > 0 {
            out.push(',');
        }
        out.push_str(&id.to_string());
    }
    out.push_str(&format!("],\"min_client\":{}}}", manifest.min_client));
    out
}

/// A JSON string literal with the escapes this schema needs (URLs and hex
/// digests carry no control characters, but backslash/quote must survive).
fn json_string(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            _ => out.push(c),
        }
    }
    out.push('"');
    out
}
