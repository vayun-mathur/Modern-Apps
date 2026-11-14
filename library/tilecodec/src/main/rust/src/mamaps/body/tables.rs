use super::consts::{BODY_HEADER_LEN, CARRIAGEWAY_RECORD_LEN, LAYER_INDEX_LEN};
use super::model::{Carriageway, Heightmap, LaneTurns, Layer, MarkingConvention};
use crate::proto::{Result, err};

/// The unaligned end of the last layer payload, as the index declares it.
///
/// Payload offsets and lengths live in the body's layer index (`index_end` is where the index
/// itself - plus extended counts - ends). Payloads are laid out in index order from the aligned
/// `index_end`, so the trailing sections start at the aligned maximum of their ends.
///
/// Floored at `index_end`: an empty body (no layers) is header-only, and a trailing section -
/// when present - starts past the index, never inside it.
pub(crate) fn payloads_end(buf: &[u8], layer_count: usize, index_end: usize) -> Result<usize> {
    let mut end = index_end;
    for i in 0..layer_count {
        let at = BODY_HEADER_LEN + i * LAYER_INDEX_LEN;
        let offset =
            u32::from_le_bytes([buf[at + 4], buf[at + 5], buf[at + 6], buf[at + 7]]) as usize;
        let length =
            u32::from_le_bytes([buf[at + 8], buf[at + 9], buf[at + 10], buf[at + 11]]) as usize;
        let payload_end = offset.checked_add(length).ok_or_else(|| {
            crate::proto::Error("a .mamaps layer's extent overflows".to_string())
        })?;
        end = end.max(payload_end);
    }
    Ok(end)
}

/// Parse a v2 name table: `u16` count then length-prefixed UTF-8 strings. Returns the table and
/// the bytes consumed (including 4-byte alignment padding).
///
/// A 0xFF length byte is a continuation chunk of the *same* name (the writer splits names past
/// 255 bytes); any shorter chunk ends the name. An exact multiple of 255 carries a terminating
/// zero chunk, so the parser cannot mistake the next name's bytes for a continuation.
pub(crate) fn parse_names(buf: &[u8]) -> Result<(Vec<String>, usize)> {
    if buf.len() < 2 {
        return err("a .mamaps name table ends before its count");
    }
    let count = u16::from_le_bytes([buf[0], buf[1]]) as usize;
    let mut at = 2usize;
    let mut names = Vec::with_capacity(count);
    for _ in 0..count {
        let mut bytes = Vec::new();
        loop {
            if at >= buf.len() {
                return err("a .mamaps name table ends inside its names");
            }
            let len = buf[at] as usize;
            at += 1;
            if at + len > buf.len() {
                return err("a .mamaps name runs past the name table");
            }
            bytes.extend_from_slice(&buf[at..at + len]);
            at += len;
            // A full 255-byte chunk continues the same name; anything shorter ends it.
            if len < 255 {
                break;
            }
        }
        let name = std::str::from_utf8(&bytes)
            .map_err(|_| crate::proto::Error("a .mamaps name is not UTF-8".to_string()))?;
        names.push(name.to_string());
    }
    let aligned = align4(at);
    if aligned > buf.len() {
        return err("a .mamaps name table's padding runs past the body");
    }
    Ok((names, aligned))
}

/// Serialise a name table: `u16` count then `u8`-length-prefixed UTF-8, 4-byte aligned.
///
/// Names longer than 255 bytes are split into 255-byte continuation chunks (a 0xFF length byte
/// continues the current name). Byte contents are arbitrary UTF-8, split on byte — never char —
/// boundaries; the parser only needs the byte count to match.
pub fn serialize_names(names: &[String]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&(names.len() as u16).to_le_bytes());
    for name in names {
        let bytes = name.as_bytes();
        let mut at = 0usize;
        // At least one chunk even for the empty string, so empty and missing stay distinct on
        // the wire (both parse back to "").
        loop {
            let take = (bytes.len() - at).min(255);
            out.push(take as u8);
            out.extend_from_slice(&bytes[at..at + take]);
            at += take;
            // A full 255-byte chunk continues; a short one ends the name. A name whose length
            // is an exact multiple of 255 needs a terminating zero chunk.
            if take < 255 {
                break;
            }
            if at >= bytes.len() {
                out.push(0);
                break;
            }
        }
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
    out
}

/// Round up to the next 4-byte boundary, so every section starts where a reader can slice it.
pub(crate) fn align4(at: usize) -> usize {
    (at + 3) & !3
}

/// Parse the feature id table: `u32` entry count, then per entry a `u8` layer id, three reserved
/// bytes, a `u32` id count and that many `u64` ids. Returns the table and the bytes consumed
/// (including 4-byte alignment padding).
///
/// Ids are fixed-width rather than varint because they are unsorted OSM element ids: within one
/// tile there is no ordering to delta against, and a varint over a ~35-bit tagged id averages
/// five bytes to save three off eight.
///
/// Validated against `layers`, which is already parsed by the time this runs: an entry must name
/// a layer the body actually carries, entries must be ascending and distinct by layer id, and a
/// layer's id vector must be exactly as long as its feature vector. A short or long vector would
/// silently misattribute every id after the first mismatch.
pub(crate) fn parse_ids(buf: &[u8], layers: &[Layer]) -> Result<(Vec<(u8, Vec<u64>)>, usize)> {
    if buf.len() < 4 {
        return err("a .mamaps id table ends before its count");
    }
    let count = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
    if count > layers.len() {
        return err(format!(
            "a .mamaps id table has {count} entries for a body with {} layer(s)",
            layers.len(),
        ));
    }
    let mut at = 4usize;
    let mut table = Vec::with_capacity(count);
    let mut previous: Option<u8> = None;
    for _ in 0..count {
        if at + 8 > buf.len() {
            return err("a .mamaps id table ends inside an entry header");
        }
        let layer_id = buf[at];
        if buf[at + 1] != 0 || u16::from_le_bytes([buf[at + 2], buf[at + 3]]) != 0 {
            return err("a .mamaps id table entry has non-zero reserved bytes");
        }
        let ids_len =
            u32::from_le_bytes([buf[at + 4], buf[at + 5], buf[at + 6], buf[at + 7]]) as usize;
        at += 8;
        if previous.is_some_and(|p| layer_id <= p) {
            return err("a .mamaps id table's entries are not ordered by layer id");
        }
        previous = Some(layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == layer_id) else {
            return err(format!("a .mamaps id table names layer {layer_id}, which the body does not carry"));
        };
        if ids_len != layer.features.len() {
            return err(format!(
                "a .mamaps id table gives layer {layer_id} {ids_len} id(s) for {} feature(s)",
                layer.features.len(),
            ));
        }
        // Bounded against the slice before allocating, so a corrupt count cannot ask for a
        // gigabyte of `Vec` — the same discipline `parse_layer` applies to its counts.
        let bytes = ids_len.checked_mul(8).ok_or_else(|| {
            crate::proto::Error("a .mamaps id table's entry overflows".to_string())
        })?;
        if at + bytes > buf.len() {
            return err("a .mamaps id table's ids run past the table");
        }
        let mut ids = Vec::with_capacity(ids_len);
        for i in 0..ids_len {
            let o = at + i * 8;
            ids.push(u64::from_le_bytes([
                buf[o],
                buf[o + 1],
                buf[o + 2],
                buf[o + 3],
                buf[o + 4],
                buf[o + 5],
                buf[o + 6],
                buf[o + 7],
            ]));
        }
        at += bytes;
        table.push((layer_id, ids));
    }
    let aligned = align4(at);
    if aligned > buf.len() {
        return err("a .mamaps id table's padding runs past the body");
    }
    Ok((table, aligned))
}

/// Serialise a feature id table, 4-byte aligned. The inverse of [`parse_ids`].
pub fn serialize_ids(ids: &[(u8, Vec<u64>)], out: &mut Vec<u8>) {
    out.extend_from_slice(&(ids.len() as u32).to_le_bytes());
    for (layer_id, entries) in ids {
        out.push(*layer_id);
        out.extend_from_slice(&[0u8; 3]);
        out.extend_from_slice(&(entries.len() as u32).to_le_bytes());
        for id in entries {
            out.extend_from_slice(&id.to_le_bytes());
        }
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
}

/// Parse the region-link table. Byte-identical in shape to the id table (see [`parse_ids`]): a
/// `u32` entry count, then per entry a `u8` layer id, three reserved bytes, a `u32` link count and
/// that many `u64` tagged relation ids. Kept a separate section (its own body flag) so the two are
/// independent — a `places` layer carries both an id table (node ids) and a region-link table
/// (boundary relation ids), and neither implies the other. Same validation as [`parse_ids`].
pub(crate) fn parse_region_links(
    buf: &[u8],
    layers: &[Layer],
) -> Result<(Vec<(u8, Vec<u64>)>, usize)> {
    if buf.len() < 4 {
        return err("a .mamaps region-link table ends before its count");
    }
    let count = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
    if count > layers.len() {
        return err(format!(
            "a .mamaps region-link table has {count} entries for a body with {} layer(s)",
            layers.len(),
        ));
    }
    let mut at = 4usize;
    let mut table = Vec::with_capacity(count);
    let mut previous: Option<u8> = None;
    for _ in 0..count {
        if at + 8 > buf.len() {
            return err("a .mamaps region-link table ends inside an entry header");
        }
        let layer_id = buf[at];
        if buf[at + 1] != 0 || u16::from_le_bytes([buf[at + 2], buf[at + 3]]) != 0 {
            return err("a .mamaps region-link table entry has non-zero reserved bytes");
        }
        let links_len =
            u32::from_le_bytes([buf[at + 4], buf[at + 5], buf[at + 6], buf[at + 7]]) as usize;
        at += 8;
        if previous.is_some_and(|p| layer_id <= p) {
            return err("a .mamaps region-link table's entries are not ordered by layer id");
        }
        previous = Some(layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == layer_id) else {
            return err(format!(
                "a .mamaps region-link table names layer {layer_id}, which the body does not carry"
            ));
        };
        if links_len != layer.features.len() {
            return err(format!(
                "a .mamaps region-link table gives layer {layer_id} {links_len} link(s) for {} feature(s)",
                layer.features.len(),
            ));
        }
        let bytes = links_len.checked_mul(8).ok_or_else(|| {
            crate::proto::Error("a .mamaps region-link table's entry overflows".to_string())
        })?;
        if at + bytes > buf.len() {
            return err("a .mamaps region-link table's links run past the table");
        }
        let mut links = Vec::with_capacity(links_len);
        for i in 0..links_len {
            let o = at + i * 8;
            links.push(u64::from_le_bytes([
                buf[o],
                buf[o + 1],
                buf[o + 2],
                buf[o + 3],
                buf[o + 4],
                buf[o + 5],
                buf[o + 6],
                buf[o + 7],
            ]));
        }
        at += bytes;
        table.push((layer_id, links));
    }
    let aligned = align4(at);
    if aligned > buf.len() {
        return err("a .mamaps region-link table's padding runs past the body");
    }
    Ok((table, aligned))
}

/// Serialise a region-link table, 4-byte aligned. The inverse of [`parse_region_links`].
pub fn serialize_region_links(links: &[(u8, Vec<u64>)], out: &mut Vec<u8>) {
    out.extend_from_slice(&(links.len() as u32).to_le_bytes());
    for (layer_id, entries) in links {
        out.push(*layer_id);
        out.extend_from_slice(&[0u8; 3]);
        out.extend_from_slice(&(entries.len() as u32).to_le_bytes());
        for id in entries {
            out.extend_from_slice(&id.to_le_bytes());
        }
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
}

/// Parse the turn-lane table: `u32` entry count, then per entry a `u8` layer id, three reserved
/// bytes, a `u32` feature count and that many per-feature records. A per-feature record is a `u8`
/// forward lane count, a `u8` backward lane count, then that many `u16` masks each (forward then
/// backward), little-endian. Returns the table and the bytes consumed (including 4-byte padding).
///
/// The same shape and validation as [`parse_ids`]: an entry must name a layer the body carries,
/// entries ascend and are distinct by layer id, and a layer's turn vector is exactly as long as
/// its feature vector — a mismatch would misattribute every feature's lanes after the first.
pub(crate) fn parse_lanes(buf: &[u8], layers: &[Layer]) -> Result<(Vec<(u8, Vec<LaneTurns>)>, usize)> {
    if buf.len() < 4 {
        return err("a .mamaps turn-lane table ends before its count");
    }
    let count = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
    if count > layers.len() {
        return err(format!(
            "a .mamaps turn-lane table has {count} entries for a body with {} layer(s)",
            layers.len(),
        ));
    }
    let mut at = 4usize;
    let mut table = Vec::with_capacity(count);
    let mut previous: Option<u8> = None;
    for _ in 0..count {
        if at + 8 > buf.len() {
            return err("a .mamaps turn-lane table ends inside an entry header");
        }
        let layer_id = buf[at];
        if buf[at + 1] != 0 || u16::from_le_bytes([buf[at + 2], buf[at + 3]]) != 0 {
            return err("a .mamaps turn-lane table entry has non-zero reserved bytes");
        }
        let feat_len =
            u32::from_le_bytes([buf[at + 4], buf[at + 5], buf[at + 6], buf[at + 7]]) as usize;
        at += 8;
        if previous.is_some_and(|p| layer_id <= p) {
            return err("a .mamaps turn-lane table's entries are not ordered by layer id");
        }
        previous = Some(layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == layer_id) else {
            return err(format!(
                "a .mamaps turn-lane table names layer {layer_id}, which the body does not carry"
            ));
        };
        if feat_len != layer.features.len() {
            return err(format!(
                "a .mamaps turn-lane table gives layer {layer_id} {feat_len} record(s) for {} \
                 feature(s)",
                layer.features.len(),
            ));
        }
        let mut turns = Vec::with_capacity(feat_len);
        for _ in 0..feat_len {
            if at + 2 > buf.len() {
                return err("a .mamaps turn-lane record ends inside its counts");
            }
            let (fwd_len, bwd_len) = (buf[at] as usize, buf[at + 1] as usize);
            at += 2;
            // Bounded against the slice before allocating, so a corrupt count cannot ask for a
            // gigabyte of `Vec` — the same discipline `parse_ids` and `parse_layer` apply.
            let masks = fwd_len + bwd_len;
            let bytes = masks.checked_mul(2).ok_or_else(|| {
                crate::proto::Error("a .mamaps turn-lane record overflows".to_string())
            })?;
            if at + bytes > buf.len() {
                return err("a .mamaps turn-lane record's masks run past the table");
            }
            let read = |n: usize, at: &mut usize| -> Vec<u16> {
                let mut v = Vec::with_capacity(n);
                for _ in 0..n {
                    v.push(u16::from_le_bytes([buf[*at], buf[*at + 1]]));
                    *at += 2;
                }
                v
            };
            let forward = read(fwd_len, &mut at);
            let backward = read(bwd_len, &mut at);
            turns.push(LaneTurns { forward, backward });
        }
        table.push((layer_id, turns));
    }
    let aligned = align4(at);
    if aligned > buf.len() {
        return err("a .mamaps turn-lane table's padding runs past the body");
    }
    Ok((table, aligned))
}

/// Serialise a turn-lane table, 4-byte aligned. The inverse of [`parse_lanes`].
pub fn serialize_lanes(turn_lanes: &[(u8, Vec<LaneTurns>)], out: &mut Vec<u8>) {
    out.extend_from_slice(&(turn_lanes.len() as u32).to_le_bytes());
    for (layer_id, entries) in turn_lanes {
        out.push(*layer_id);
        out.extend_from_slice(&[0u8; 3]);
        out.extend_from_slice(&(entries.len() as u32).to_le_bytes());
        for turns in entries {
            out.push(turns.forward.len() as u8);
            out.push(turns.backward.len() as u8);
            for mask in turns.forward.iter().chain(&turns.backward) {
                out.extend_from_slice(&mask.to_le_bytes());
            }
        }
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
}

/// Parse the carriageway table: a `u8` [`MarkingConvention`] byte for the whole tile, three
/// reserved bytes, then the same framing [`parse_lanes`] uses — a `u32` entry count, then per entry
/// a `u8` layer id, three reserved bytes, a `u32` record count and that many fixed 6-byte records
/// (`u8` forward, `u8` backward, `u32` solid divider bits, little-endian).
///
/// The convention leads rather than trailing so it is readable without walking the entries, and it
/// is per tile rather than per record because a tile never meaningfully spans two conventions.
///
/// Validation matches [`parse_lanes`] exactly: an entry must name a layer the body carries, entries
/// ascend and are distinct by layer id, and a layer's record vector is exactly as long as its
/// feature vector — a mismatch would misattribute every road's carriageway after the first.
pub(crate) fn parse_carriageways(
    buf: &[u8],
    layers: &[Layer],
) -> Result<(Vec<(u8, Vec<Carriageway>)>, MarkingConvention, usize)> {
    if buf.len() < 8 {
        return err("a .mamaps carriageway table ends before its header");
    }
    let convention = MarkingConvention::from_byte(buf[0])?;
    if buf[1] != 0 || u16::from_le_bytes([buf[2], buf[3]]) != 0 {
        return err("a .mamaps carriageway table has non-zero reserved bytes");
    }
    let count = u32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]) as usize;
    if count > layers.len() {
        return err(format!(
            "a .mamaps carriageway table has {count} entries for a body with {} layer(s)",
            layers.len(),
        ));
    }
    let mut at = 8usize;
    let mut table = Vec::with_capacity(count);
    let mut previous: Option<u8> = None;
    for _ in 0..count {
        if at + 8 > buf.len() {
            return err("a .mamaps carriageway table ends inside an entry header");
        }
        let layer_id = buf[at];
        if buf[at + 1] != 0 || u16::from_le_bytes([buf[at + 2], buf[at + 3]]) != 0 {
            return err("a .mamaps carriageway table entry has non-zero reserved bytes");
        }
        let feat_len =
            u32::from_le_bytes([buf[at + 4], buf[at + 5], buf[at + 6], buf[at + 7]]) as usize;
        at += 8;
        if previous.is_some_and(|p| layer_id <= p) {
            return err("a .mamaps carriageway table's entries are not ordered by layer id");
        }
        previous = Some(layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == layer_id) else {
            return err(format!(
                "a .mamaps carriageway table names layer {layer_id}, which the body does not carry"
            ));
        };
        if feat_len != layer.features.len() {
            return err(format!(
                "a .mamaps carriageway table gives layer {layer_id} {feat_len} record(s) for {} \
                 feature(s)",
                layer.features.len(),
            ));
        }
        // Bounded against the slice before allocating, so a corrupt count cannot ask for a
        // gigabyte of `Vec` — the same discipline `parse_lanes` and `parse_layer` apply.
        let bytes = feat_len.checked_mul(CARRIAGEWAY_RECORD_LEN).ok_or_else(|| {
            crate::proto::Error("a .mamaps carriageway table overflows".to_string())
        })?;
        if at + bytes > buf.len() {
            return err("a .mamaps carriageway table's records run past the table");
        }
        let mut rows = Vec::with_capacity(feat_len);
        for _ in 0..feat_len {
            rows.push(Carriageway {
                forward: buf[at],
                backward: buf[at + 1],
                solid_dividers: u32::from_le_bytes([
                    buf[at + 2],
                    buf[at + 3],
                    buf[at + 4],
                    buf[at + 5],
                ]),
            });
            at += CARRIAGEWAY_RECORD_LEN;
        }
        table.push((layer_id, rows));
    }
    let aligned = align4(at);
    if aligned > buf.len() {
        return err("a .mamaps carriageway table's padding runs past the body");
    }
    Ok((table, convention, aligned))
}

/// Serialise a carriageway table, 4-byte aligned. The inverse of [`parse_carriageways`].
pub fn serialize_carriageways(
    carriageways: &[(u8, Vec<Carriageway>)],
    convention: MarkingConvention,
    out: &mut Vec<u8>,
) {
    out.push(convention.to_byte());
    out.extend_from_slice(&[0u8; 3]);
    out.extend_from_slice(&(carriageways.len() as u32).to_le_bytes());
    for (layer_id, entries) in carriageways {
        out.push(*layer_id);
        out.extend_from_slice(&[0u8; 3]);
        out.extend_from_slice(&(entries.len() as u32).to_le_bytes());
        for row in entries {
            out.push(row.forward);
            out.push(row.backward);
            out.extend_from_slice(&row.solid_dividers.to_le_bytes());
        }
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
}

/// Parse the per-tile heightmap: a `u16` grid side length `dim`, then `dim * dim` `u16` samples,
/// row-major. Returns the grid and the bytes consumed (including 4-byte alignment padding).
///
/// A zero `dim` is refused rather than read as an empty grid: an elevation-free tile omits the
/// section (the flag is clear) instead of writing a degenerate one, so a zero here is corruption.
pub(crate) fn parse_heightmap(buf: &[u8]) -> Result<(Heightmap, usize)> {
    if buf.len() < 2 {
        return err("a .mamaps heightmap ends before its dimension");
    }
    let dim = u16::from_le_bytes([buf[0], buf[1]]);
    if dim == 0 {
        return err("a .mamaps heightmap has a zero dimension");
    }
    let cells = dim as usize * dim as usize;
    let bytes = cells
        .checked_mul(2)
        .ok_or_else(|| crate::proto::Error("a .mamaps heightmap's grid overflows".to_string()))?;
    let mut at = 2usize;
    if at + bytes > buf.len() {
        return err("a .mamaps heightmap's samples run past the body");
    }
    let mut samples = Vec::with_capacity(cells);
    for i in 0..cells {
        let o = at + i * 2;
        samples.push(u16::from_le_bytes([buf[o], buf[o + 1]]));
    }
    at += bytes;
    let aligned = align4(at);
    if aligned > buf.len() {
        return err("a .mamaps heightmap's padding runs past the body");
    }
    Ok((Heightmap { dim, samples }, aligned))
}

/// Serialise a per-tile heightmap, 4-byte aligned. The inverse of [`parse_heightmap`].
pub fn serialize_heightmap(grid: &Heightmap, out: &mut Vec<u8>) {
    out.extend_from_slice(&grid.dim.to_le_bytes());
    for &s in &grid.samples {
        out.extend_from_slice(&s.to_le_bytes());
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
}

/// The MED (LOCO-I) prediction for sample `(r, c)` of a `d x d` grid from already-known
/// neighbours: left, up and up-left. First row predicts from the left, first column from above,
/// the first sample from sea level (32768). Shared by the encoder and the decoder so they agree.
#[inline]
fn med_predict(s: &[u16], d: usize, r: usize, c: usize) -> u16 {
    if r == 0 && c == 0 {
        return 32768;
    }
    if r == 0 {
        return s[c - 1];
    }
    if c == 0 {
        return s[(r - 1) * d];
    }
    let (l, u, ul) = (s[r * d + c - 1] as i32, s[(r - 1) * d + c] as i32, s[(r - 1) * d + c - 1] as i32);
    let (lo, hi) = (l.min(u), l.max(u));
    let p = if ul >= hi {
        lo
    } else if ul <= lo {
        hi
    } else {
        l + u - ul
    };
    p as u16
}

/// Serialise a heightmap as v11 MED residuals: the same `dim` + `dim*dim` u16 layout as
/// [`serialize_heightmap`], each sample replaced by `zigzag((sample - prediction) mod 2^16)`.
/// Flat or smoothly sloping ground becomes runs of tiny values, which is what zstd needs.
pub fn serialize_heightmap_residual(grid: &Heightmap, out: &mut Vec<u8>) {
    let d = grid.dim as usize;
    out.extend_from_slice(&grid.dim.to_le_bytes());
    for r in 0..d {
        for c in 0..d {
            let v = grid.samples[r * d + c];
            let e = v.wrapping_sub(med_predict(&grid.samples, d, r, c)) as i16;
            let zz = ((e << 1) ^ (e >> 15)) as u16;
            out.extend_from_slice(&zz.to_le_bytes());
        }
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
}

/// Undo [`serialize_heightmap_residual`] in place, in raster order so every prediction reads
/// samples already reconstructed.
pub(crate) fn unresidual_heightmap(grid: &mut Heightmap) {
    let d = grid.dim as usize;
    for r in 0..d {
        for c in 0..d {
            let zz = grid.samples[r * d + c];
            let e = ((zz >> 1) as i16) ^ -((zz & 1) as i16);
            let p = med_predict(&grid.samples, d, r, c);
            grid.samples[r * d + c] = p.wrapping_add(e as u16);
        }
    }
}
