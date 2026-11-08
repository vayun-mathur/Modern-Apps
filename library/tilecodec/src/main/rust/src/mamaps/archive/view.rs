use super::consts::*;
use super::entry::ArchiveEntry;
use super::footer::ArchiveFooter;
use super::layout::{align_up, sidecar_start};
use crate::proto::{err, Result};

/// A v7 blocked section must parse and unpack to exactly `want` bytes of
/// `stride`-byte records. Used by the nodes/elevation checks; the edges
/// check inlines its own (records share the section with escapes/names).
fn check_blocked(b: &[u8], want: u64, stride: usize, what: &str) -> Result<()> {
    match crate::blocked::Blocked::parse(b) {
        Ok(v) if v.unpacked_len() == want && v.record_len() == stride => Ok(()),
        _ => err(format!(
            "a single-archive {what} section is not the blocked records it claims"
        )),
    }
}

/// A parsed sidecar: footer + directory, with payload accessors.
///
/// Parse validates everything a later slice would discover the hard way:
/// footer magic, directory fit, per-entry bounds/alignment/overlap, build id
/// equality with the tile header, and per-kind magic/version/count agreement.
/// A corrupt sidecar is refused here rather than mmapped into on device.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArchiveView {
    pub footer: ArchiveFooter,
    pub entries: Vec<ArchiveEntry>,
}

impl ArchiveView {
    /// Parse the sidecar off the end of a whole file already in memory.
    ///
    /// `header` is the tile header parsed from the same bytes (so its
    /// `file_len` and `build_id` are the expectations); `file_len` is the
    /// file's own length, which must equal `header.file_len`.
    pub fn parse(
        bytes: &[u8],
        header: &crate::mamaps::header::Header,
    ) -> Result<ArchiveView> {
        if bytes.len() as u64 != header.file_len {
            return err(format!(
                "a single-archive file declares {} bytes but is {}",
                header.file_len,
                bytes.len(),
            ));
        }
        if (bytes.len() as u64) < sidecar_start(header) + ARCHIVE_FOOTER_LEN as u64 {
            return err("a single-archive file ends inside its tile data");
        }
        let footer_at = bytes.len() - ARCHIVE_FOOTER_LEN;
        let footer = ArchiveFooter::parse(&bytes[footer_at..])?;
        if footer.build_id != header.build_id {
            return err(format!(
                "a single-archive footer carries build {:#018x} but its header carries {:#018x}",
                footer.build_id, header.build_id,
            ));
        }
        let dir_end = footer.dir_offset.checked_add(footer.dir_len).ok_or_else(|| {
            crate::proto::Error("a single-archive directory extent overflows".to_string())
        })?;
        if dir_end > footer_at as u64 {
            return err("a single-archive directory runs into its footer");
        }
        if footer.dir_len < ARCHIVE_DIR_HEADER_LEN as u64 {
            return err("a single-archive directory ends before its count");
        }
        let dir = &bytes[footer.dir_offset as usize..dir_end as usize];
        let count = u32::from_le_bytes([dir[0], dir[1], dir[2], dir[3]]) as usize;
        if count > 32 {
            return err(format!(
                "a single-archive directory names {count} sections, past the 32 a directory may hold"
            ));
        }
        let want = ARCHIVE_DIR_HEADER_LEN + count * ARCHIVE_ENTRY_LEN;
        let aligned = align_up(want as u64) as usize;
        if footer.dir_len as usize != aligned {
            return err(format!(
                "a single-archive directory declares {} bytes but {count} entries need {aligned}",
                footer.dir_len,
            ));
        }
        if dir.len() != aligned {
            return err("a single-archive directory length disagrees with its footer");
        }
        // Padding past the entries must be zeroes.
        if dir[want..].iter().any(|&b| b != 0) {
            return err("a single-archive directory has non-zero padding");
        }
        let mut entries = Vec::with_capacity(count);
        let mut previous_kind: Option<u8> = None;
        for i in 0..count {
            let at = ARCHIVE_DIR_HEADER_LEN + i * ARCHIVE_ENTRY_LEN;
            let e = ArchiveEntry::parse(&dir[at..at + ARCHIVE_ENTRY_LEN])?;
            if previous_kind.is_some_and(|p| e.kind <= p) {
                return err("a single-archive directory is not ordered by section kind");
            }
            previous_kind = Some(e.kind);
            entries.push(e);
        }
        // Bounds, alignment and overlap — same three refusals as the tile header.
        let tile_end = sidecar_start(header);
        for e in &entries {
            if e.offset % ARCHIVE_ALIGN != 0 {
                return err(format!(
                    "single-archive section {} starts at {}, which is not 8-byte aligned",
                    e.kind, e.offset
                ));
            }
            if e.len == 0 {
                return err(format!("single-archive section {} has zero length", e.kind));
            }
            let end = e.offset.checked_add(e.len).ok_or_else(|| {
                crate::proto::Error("a single-archive section extent overflows".to_string())
            })?;
            if e.offset < tile_end {
                return err(format!(
                    "single-archive section {} overlaps the tile data",
                    e.kind
                ));
            }
            if end > footer_at as u64 {
                return err(format!(
                    "single-archive section {} runs past its directory",
                    e.kind
                ));
            }
        }
        for (i, a) in entries.iter().enumerate() {
            for b in &entries[i + 1..] {
                let a_end = a.offset + a.len;
                let b_end = b.offset + b.len;
                if a.offset < b_end && b.offset < a_end {
                    return err(format!(
                        "single-archive sections {} and {} overlap",
                        a.kind, b.kind
                    ));
                }
            }
        }
        let view = ArchiveView { footer, entries };
        view.check_counts(bytes, header)?;
        Ok(view)
    }

    /// The payload slice for `kind`, or `None` when the archive omits it.
    ///
    /// Optional sections (names, lanes, elevation, POI sidecars) are absent —
    /// not zero-length — when the build carries none. Callers match on `None`
    /// to degrade (flat profile, topology lanes, scan fallback), never to fail.
    pub fn section<'b>(&self, bytes: &'b [u8], kind: u8) -> Option<&'b [u8]> {
        self.entries.iter().find(|e| e.kind == kind).map(|e| {
            &bytes[e.offset as usize..(e.offset + e.len) as usize]
        })
    }

    /// `(offset, len)` of `kind` for a loader that maps rather than copies.
    pub fn location(&self, kind: u8) -> Option<(u64, u64)> {
        self.entries.iter().find(|e| e.kind == kind).map(|e| (e.offset, e.len))
    }

    /// Per-kind magic/version/count agreement.
    ///
    /// Each check mirrors the multi-file loader's own (`Graph::load`,
    /// `PoiIndex::open*`, `TransitIndex::parse`) so a vintage mismatch the old
    /// layout refused by length is refused here by the same numbers.
    fn check_counts(
        &self,
        bytes: &[u8],
        header: &crate::mamaps::header::Header,
    ) -> Result<()> {
        let slice = |kind: u8| -> Result<Option<&[u8]>> {
            match self.entries.iter().find(|e| e.kind == kind) {
                None => Ok(None),
                Some(e) => Ok(Some(&bytes[e.offset as usize..(e.offset + e.len) as usize])),
            }
        };
        // Graph meta is mandatory when any graph section is present: it carries
        // the counts every other graph check is sized from.
        let graph_present = self.entries.iter().any(|e| {
            matches!(
                e.kind,
                ARCHIVE_KIND_GRAPH_META
                    | ARCHIVE_KIND_GRAPH_NODES
                    | ARCHIVE_KIND_GRAPH_EDGES
                    | ARCHIVE_KIND_GRAPH_INTERMEDIATE
            )
        });
        let meta = slice(ARCHIVE_KIND_GRAPH_META)?;
        let (node_count, edge_count, escape_count, named_edges, blocked_v7) = match meta {
            None => {
                if graph_present {
                    return err("a single-archive carries graph sections but no graph meta");
                }
                (0, 0, 0, 0, false)
            }
            Some(m) => {
                if m.len() as u64 != GRAPH_META_LEN {
                    return err(format!(
                        "a single-archive graph meta is {} bytes, not {GRAPH_META_LEN}",
                        m.len()
                    ));
                }
                let u32_at = |o: usize| {
                    u32::from_le_bytes([m[o], m[o + 1], m[o + 2], m[o + 3]])
                };
                let u64_at = |o: usize| {
                    u64::from_le_bytes([
                        m[o], m[o + 1], m[o + 2], m[o + 3], m[o + 4], m[o + 5], m[o + 6],
                        m[o + 7],
                    ])
                };
                if u32_at(0) != GRAPH_MAGIC {
                    return err("a single-archive graph meta has bad MARG magic");
                }
                let graph_version = u32_at(4);
                if !(GRAPH_VERSION_MIN..=GRAPH_VERSION).contains(&graph_version) {
                    return err(format!(
                        "a single-archive graph meta is version {graph_version}, not {GRAPH_VERSION_MIN}..={GRAPH_VERSION}",
                    ));
                }
                let (n, e, esc, named) = (u64_at(8), u64_at(16), u64_at(24), u64_at(32));
                if u32::try_from(e).is_err() || esc > e || named > e {
                    return err("a single-archive graph meta counts disagree");
                }
                if u32::try_from(n).is_err() {
                    return err("a single-archive graph node count overflows u32");
                }
                let _ = header;
                (n, e, esc, named, graph_version >= GRAPH_VERSION)
            }
        };
        // Nodes: (N+1) 12-byte records (the sentinel). Under v7 a ZBLK
        // section unpacking to exactly that.
        if let Some(b) = slice(ARCHIVE_KIND_GRAPH_NODES)? {
            let want = (node_count + 1) * 12;
            if blocked_v7 {
                check_blocked(b, want, 12, "nodes")?;
            } else if b.len() as u64 != want {
                return err(format!(
                    "a single-archive nodes section is {} bytes for {node_count} nodes, want {want}",
                    b.len()
                ));
            }
        }
        // Edges: records + escape index + escape rows + name bitmap + name offs.
        // Under v7 the records are a ZBLK section; the escapes and names follow
        // it exactly as before.
        if let Some(b) = slice(ARCHIVE_KIND_GRAPH_EDGES)? {
            let escape_blocks = edge_count.div_ceil(1024) + 1;
            let (rec_end, rec_ok) = if blocked_v7 {
                match crate::blocked::Blocked::parse(b) {
                    Ok(v)
                        if v.unpacked_len() == edge_count * 7
                            && v.record_len() == 7 =>
                    {
                        (v.block_offset(v.block_count()).unwrap_or(b.len() as u64), true)
                    }
                    _ => (0, false),
                }
            } else {
                (edge_count * 7, true)
            };
            if !rec_ok {
                return err("a single-archive edges records section is not the blocked records it claims".to_string());
            }
            let first_off = align_up(rec_end);
            let escapes_off = first_off + escape_blocks * 4;
            let names_off = align_up(escapes_off + escape_count * 12);
            // Bitmap bytes: rank + present, same as graph::EdgeBitmap::bytes.
            let rank = (edge_count.div_ceil(512) + 1) * 8;
            let present = edge_count.div_ceil(8);
            let name_off_off = names_off + rank + present;
            let want = name_off_off + named_edges * 4;
            if b.len() as u64 != want {
                return err(format!(
                    "a single-archive edges section is {} bytes, want {want}",
                    b.len()
                ));
            }
        }
        // Intermediate: at least the trailing G, and G <= E.
        if let Some(b) = slice(ARCHIVE_KIND_GRAPH_INTERMEDIATE)? {
            if b.len() < 8 {
                return err("a single-archive intermediate section ends before its G");
            }
            let at = b.len() - 8;
            let g = u64::from_le_bytes([
                b[at], b[at + 1], b[at + 2], b[at + 3], b[at + 4], b[at + 5], b[at + 6],
                b[at + 7],
            ]);
            if g > edge_count {
                return err(format!(
                    "a single-archive intermediate names {g} geometry edges of {edge_count}"
                ));
            }
        }
        // Elevation: i16[N] when present — or a ZBLK section unpacking to
        // exactly that under v7.
        if let Some(b) = slice(ARCHIVE_KIND_GRAPH_ELEVATION)? {
            if blocked_v7 {
                check_blocked(b, node_count * 2, 2, "elevation")?;
            } else if b.len() as u64 != node_count * 2 {
                return err(format!(
                    "a single-archive elevation section is {} bytes for {node_count} nodes",
                    b.len()
                ));
            }
        }
        // POI index: 14-byte records; its count anchors the sidecars.
        let poi_count = match slice(ARCHIVE_KIND_POI_INDEX)? {
            None => None,
            Some(b) => {
                if b.len() as u64 % POI_RECORD_BYTES != 0 {
                    return err("a single-archive POI index is not whole records");
                }
                Some(b.len() as u64 / POI_RECORD_BYTES)
            }
        };
        if slice(ARCHIVE_KIND_POI_NAMES)?.is_none() && poi_count.is_some() {
            return err("a single-archive carries a POI index but no POI names");
        }
        // Attrs / spatial / words each refuse a count mismatch (ordinal join).
        if let Some(b) = slice(ARCHIVE_KIND_POI_ATTRS)? {
            if b.len() < 12 {
                return err("a single-archive POI attrs end before their header");
            }
            if &b[0..4] != b"MAPA" {
                return err("a single-archive POI attrs have bad MAPA magic");
            }
            let n = u32::from_le_bytes([b[8], b[9], b[10], b[11]]) as u64;
            if Some(n) != poi_count {
                return err(format!(
                    "a single-archive POI attrs cover {n} records for {} index records",
                    poi_count.unwrap_or(0)
                ));
            }
        }
        if let Some(b) = slice(ARCHIVE_KIND_POI_SPATIAL)? {
            if b.len() < 32 {
                return err("a single-archive POI grid ends before its header");
            }
            if &b[0..4] != b"PSP1" {
                return err("a single-archive POI grid has bad PSP1 magic");
            }
            let n = u32::from_le_bytes([b[8], b[9], b[10], b[11]]) as u64;
            if Some(n) != poi_count {
                return err("a single-archive POI grid covers a different index");
            }
        }
        if let Some(b) = slice(ARCHIVE_KIND_POI_WORDS)? {
            if b.len() < 16 {
                return err("a single-archive POI word index ends before its header");
            }
            if &b[0..4] != b"PNI1" {
                return err("a single-archive POI word index has bad PNI1 magic");
            }
            let n = u32::from_le_bytes([b[8], b[9], b[10], b[11]]) as u64;
            if Some(n) != poi_count {
                return err("a single-archive POI word index covers a different index");
            }
        }
        // Region: PRG1 header + one u64 tagged id per index ordinal (ordinal join).
        if let Some(b) = slice(ARCHIVE_KIND_POI_REGION)? {
            if b.len() < 12 {
                return err("a single-archive POI region ends before its header");
            }
            if &b[0..4] != b"PRG1" {
                return err("a single-archive POI region has bad PRG1 magic");
            }
            let n = u32::from_le_bytes([b[8], b[9], b[10], b[11]]) as u64;
            if Some(n) != poi_count {
                return err("a single-archive POI region covers a different index");
            }
        }
        // Transit: TRIX v3..=6 with a directory that fits.
        if let Some(b) = slice(ARCHIVE_KIND_TRANSIT)? {
            if b.len() < 80 {
                return err("a single-archive transit pack ends before its header");
            }
            let magic = u32::from_le_bytes([b[0], b[1], b[2], b[3]]);
            let version = u32::from_le_bytes([b[4], b[5], b[6], b[7]]);
            if magic != TRANSIT_MAGIC {
                return err("a single-archive transit pack has bad TRIX magic");
            }
            if !(TRANSIT_VERSION_MIN..=TRANSIT_VERSION).contains(&version) {
                return err(format!(
                    "a single-archive transit pack is version {version}, not {TRANSIT_VERSION_MIN}..={TRANSIT_VERSION}"
                ));
            }
            let section_count = u32::from_le_bytes([b[8], b[9], b[10], b[11]]) as usize;
            if section_count < 20 {
                return err("a single-archive transit pack names too few sections");
            }
            if 80 + section_count * 16 > b.len() {
                return err("a single-archive transit directory runs past its pack");
            }
        }
        Ok(())
    }
}
