use super::model::{
    BUILDING_ATTRS_LEN, BuildingAttrs, Carriageway, LaneTurns, Layer, MarkingConvention,
    ROOF_ORIENT_MAX, ROOF_SHAPE_MAX,
};
use super::tables::{LayerTable, align4};
use crate::proto::{Result, err};

/// Parse the S3DB building attribute table: the same framing as [`super::tables::parse_ids`] — a `u32` entry
/// count, then per entry a `u8` layer id, three reserved bytes, a `u32` record count and that many
/// fixed [`BUILDING_ATTRS_LEN`]-byte [`BuildingAttrs`] records. Returns the table and the bytes
/// consumed (including 4-byte alignment padding).
///
/// Validated against `layers` exactly as the id and turn-lane tables are: an entry must name a
/// layer the body carries, entries ascend and are distinct by layer id, and a layer's attr vector
/// is exactly as long as its feature vector — a mismatch would misattribute every building's
/// height after the first. `roof_shape`, `roof_orientation` and the reserved bytes are checked so a
/// corrupt record fails here rather than extruding a wrong shape on device.
pub(crate) fn parse_buildings(buf: &[u8], layers: &[Layer]) -> Result<(LayerTable<BuildingAttrs>, usize)> {
    if buf.len() < 4 {
        return err("a .mamaps building table ends before its count");
    }
    let count = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
    if count > layers.len() {
        return err(format!(
            "a .mamaps building table has {count} entries for a body with {} layer(s)",
            layers.len(),
        ));
    }
    let mut at = 4usize;
    let mut table = Vec::with_capacity(count);
    let mut previous: Option<u8> = None;
    for _ in 0..count {
        if at + 8 > buf.len() {
            return err("a .mamaps building table ends inside an entry header");
        }
        let layer_id = buf[at];
        if buf[at + 1] != 0 || u16::from_le_bytes([buf[at + 2], buf[at + 3]]) != 0 {
            return err("a .mamaps building table entry has non-zero reserved bytes");
        }
        let attrs_len =
            u32::from_le_bytes([buf[at + 4], buf[at + 5], buf[at + 6], buf[at + 7]]) as usize;
        at += 8;
        if previous.is_some_and(|p| layer_id <= p) {
            return err("a .mamaps building table's entries are not ordered by layer id");
        }
        previous = Some(layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == layer_id) else {
            return err(format!(
                "a .mamaps building table names layer {layer_id}, which the body does not carry"
            ));
        };
        if attrs_len != layer.features.len() {
            return err(format!(
                "a .mamaps building table gives layer {layer_id} {attrs_len} record(s) for {} \
                 feature(s)",
                layer.features.len(),
            ));
        }
        // Bounded against the slice before allocating, the same discipline `parse_ids` applies.
        let bytes = attrs_len.checked_mul(BUILDING_ATTRS_LEN).ok_or_else(|| {
            crate::proto::Error("a .mamaps building table's entry overflows".to_string())
        })?;
        if at + bytes > buf.len() {
            return err("a .mamaps building table's records run past the table");
        }
        let mut attrs = Vec::with_capacity(attrs_len);
        for i in 0..attrs_len {
            let o = at + i * BUILDING_ATTRS_LEN;
            let roof_shape = buf[o + 6];
            if roof_shape > ROOF_SHAPE_MAX {
                return err(format!("a .mamaps building record has roof shape {roof_shape}"));
            }
            let roof_orientation = buf[o + 8];
            if roof_orientation > ROOF_ORIENT_MAX {
                return err(format!(
                    "a .mamaps building record has roof orientation {roof_orientation}"
                ));
            }
            if buf[o + 9] != 0 || buf[o + 10] != 0 || buf[o + 11] != 0 {
                return err("a .mamaps building record has non-zero reserved bytes");
            }
            attrs.push(BuildingAttrs {
                height: u16::from_le_bytes([buf[o], buf[o + 1]]),
                min_height: u16::from_le_bytes([buf[o + 2], buf[o + 3]]),
                roof_height: u16::from_le_bytes([buf[o + 4], buf[o + 5]]),
                roof_shape,
                roof_direction: buf[o + 7],
                roof_orientation,
                building_colour: u32::from_le_bytes([
                    buf[o + 12],
                    buf[o + 13],
                    buf[o + 14],
                    buf[o + 15],
                ]),
                roof_colour: u32::from_le_bytes([
                    buf[o + 16],
                    buf[o + 17],
                    buf[o + 18],
                    buf[o + 19],
                ]),
            });
        }
        at += bytes;
        table.push((layer_id, attrs));
    }
    let aligned = align4(at);
    if aligned > buf.len() {
        return err("a .mamaps building table's padding runs past the body");
    }
    Ok((table, aligned))
}

/// Serialise an S3DB building attribute table, 4-byte aligned. The inverse of [`parse_buildings`].
pub fn serialize_buildings(buildings: &[(u8, Vec<BuildingAttrs>)], out: &mut Vec<u8>) {
    out.extend_from_slice(&(buildings.len() as u32).to_le_bytes());
    for (layer_id, entries) in buildings {
        out.push(*layer_id);
        out.extend_from_slice(&[0u8; 3]);
        out.extend_from_slice(&(entries.len() as u32).to_le_bytes());
        for a in entries {
            out.extend_from_slice(&a.height.to_le_bytes());
            out.extend_from_slice(&a.min_height.to_le_bytes());
            out.extend_from_slice(&a.roof_height.to_le_bytes());
            out.push(a.roof_shape);
            out.push(a.roof_direction);
            out.push(a.roof_orientation);
            out.extend_from_slice(&[0u8; 3]);
            out.extend_from_slice(&a.building_colour.to_le_bytes());
            out.extend_from_slice(&a.roof_colour.to_le_bytes());
        }
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
}

/// Parse the v10 sparse turn-lane table: like [`parse_sparse_buildings`], a
/// `u32` layer count, then per layer a `u8` id, a `u32` feature count, a
/// bitset of the features carrying lane masks (`(n+7)/8` bytes), and the
/// variable-length mask records (2 count bytes + 2 bytes per mask) for the set
/// features in order. Expands to the dense model, so the reader API is
/// unchanged. Returns the table and the bytes consumed.
pub(crate) fn parse_sparse_lanes(
    buf: &[u8],
    layers: &[Layer],
) -> Result<(LayerTable<LaneTurns>, usize)> {
    if buf.len() < 4 {
        return err("a .mamaps sparse turn-lane table ends before its count");
    }
    let count = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
    if count > layers.len() {
        return err(format!(
            "a .mamaps sparse turn-lane table has {count} entries for a body with {} layer(s)",
            layers.len(),
        ));
    }
    let mut at = 4usize;
    let mut table = Vec::with_capacity(count);
    let mut previous: Option<u8> = None;
    for _ in 0..count {
        if at + 5 > buf.len() {
            return err("a .mamaps sparse turn-lane table ends inside an entry header");
        }
        let layer_id = buf[at];
        let n = u32::from_le_bytes([buf[at + 1], buf[at + 2], buf[at + 3], buf[at + 4]]) as usize;
        at += 5;
        if previous.is_some_and(|p| layer_id <= p) {
            return err("a .mamaps sparse turn-lane table's entries are not ordered by layer id");
        }
        previous = Some(layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == layer_id) else {
            return err(format!(
                "a .mamaps sparse turn-lane table names layer {layer_id}, which the body does not carry"
            ));
        };
        if n != layer.features.len() {
            return err(format!(
                "a .mamaps sparse turn-lane table gives layer {layer_id} {n} bit(s) for {} feature(s)",
                layer.features.len(),
            ));
        }
        let bitlen = n.div_ceil(8);
        if at + bitlen > buf.len() {
            return err("a .mamaps sparse turn-lane table ends inside its bitset");
        }
        let bits = &buf[at..at + bitlen];
        at += bitlen;
        let mut turns = Vec::with_capacity(n);
        for i in 0..n {
            if bits[i / 8] & (1u8 << (i % 8)) == 0 {
                turns.push(LaneTurns::default());
                continue;
            }
            if at + 2 > buf.len() {
                return err("a .mamaps sparse turn-lane record ends inside its counts");
            }
            let (fwd_len, bwd_len) = (buf[at] as usize, buf[at + 1] as usize);
            at += 2;
            let masks = fwd_len + bwd_len;
            let bytes = masks.checked_mul(2).ok_or_else(|| {
                crate::proto::Error("a .mamaps sparse turn-lane record overflows".to_string())
            })?;
            if at + bytes > buf.len() {
                return err("a .mamaps sparse turn-lane record's masks run past the table");
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
        return err("a .mamaps sparse turn-lane table's padding runs past the body");
    }
    Ok((table, aligned))
}

/// Serialise a sparse turn-lane table, 4-byte aligned. Layers with no masked
/// features are omitted entirely; the inverse of [`parse_sparse_lanes`].
pub fn serialize_sparse_lanes(turn_lanes: &[(u8, Vec<LaneTurns>)], out: &mut Vec<u8>) {
    let present: Vec<(&u8, &Vec<LaneTurns>)> = turn_lanes
        .iter()
        .map(|(id, v)| (id, v))
        .filter(|(_, v)| v.iter().any(|t| !t.is_empty()))
        .collect();
    out.extend_from_slice(&(present.len() as u32).to_le_bytes());
    for (layer_id, entries) in present {
        out.push(*layer_id);
        out.extend_from_slice(&(entries.len() as u32).to_le_bytes());
        let mut bits = vec![0u8; entries.len().div_ceil(8)];
        for (i, t) in entries.iter().enumerate() {
            if !t.is_empty() {
                bits[i / 8] |= 1u8 << (i % 8);
            }
        }
        out.extend_from_slice(&bits);
        for t in entries {
            if t.is_empty() {
                continue;
            }
            out.push(t.forward.len() as u8);
            out.push(t.backward.len() as u8);
            for mask in t.forward.iter().chain(&t.backward) {
                out.extend_from_slice(&mask.to_le_bytes());
            }
        }
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
}

/// Parse the v10 sparse carriageway table: the tile convention byte and three
/// reserved bytes, then the sparse framing [`parse_sparse_lanes`] uses — a
/// `u32` entry count, then per entry a `u8` layer id, a `u32` record count, a
/// bitset, and 6-byte records for the set features only. Expands to the dense
/// model. Returns the table, the convention, and the bytes consumed.
pub(crate) fn parse_sparse_carriageways(
    buf: &[u8],
    layers: &[Layer],
) -> Result<(LayerTable<Carriageway>, MarkingConvention, usize)> {
    if buf.len() < 8 {
        return err("a .mamaps sparse carriageway table ends before its header");
    }
    let convention = MarkingConvention::from_byte(buf[0])?;
    if buf[1] != 0 || u16::from_le_bytes([buf[2], buf[3]]) != 0 {
        return err("a .mamaps sparse carriageway table has non-zero reserved bytes");
    }
    let count = u32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]) as usize;
    if count > layers.len() {
        return err(format!(
            "a .mamaps sparse carriageway table has {count} entries for a body with {} layer(s)",
            layers.len(),
        ));
    }
    let mut at = 8usize;
    let mut table = Vec::with_capacity(count);
    let mut previous: Option<u8> = None;
    for _ in 0..count {
        if at + 5 > buf.len() {
            return err("a .mamaps sparse carriageway table ends inside an entry header");
        }
        let layer_id = buf[at];
        let n = u32::from_le_bytes([buf[at + 1], buf[at + 2], buf[at + 3], buf[at + 4]]) as usize;
        at += 5;
        if previous.is_some_and(|p| layer_id <= p) {
            return err("a .mamaps sparse carriageway table's entries are not ordered by layer id");
        }
        previous = Some(layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == layer_id) else {
            return err(format!(
                "a .mamaps sparse carriageway table names layer {layer_id}, which the body does not carry"
            ));
        };
        if n != layer.features.len() {
            return err(format!(
                "a .mamaps sparse carriageway table gives layer {layer_id} {n} bit(s) for {} feature(s)",
                layer.features.len(),
            ));
        }
        let bitlen = n.div_ceil(8);
        if at + bitlen > buf.len() {
            return err("a .mamaps sparse carriageway table ends inside its bitset");
        }
        let bits = &buf[at..at + bitlen];
        at += bitlen;
        let mut rows = Vec::with_capacity(n);
        for i in 0..n {
            if bits[i / 8] & (1u8 << (i % 8)) == 0 {
                rows.push(Carriageway::default());
                continue;
            }
            if at + 6 > buf.len() {
                return err("a .mamaps sparse carriageway record runs past the table");
            }
            rows.push(Carriageway {
                forward: buf[at],
                backward: buf[at + 1],
                solid_dividers: u32::from_le_bytes([buf[at + 2], buf[at + 3], buf[at + 4], buf[at + 5]]),
            });
            at += 6;
        }
        table.push((layer_id, rows));
    }
    let aligned = align4(at);
    if aligned > buf.len() {
        return err("a .mamaps sparse carriageway table's padding runs past the body");
    }
    Ok((table, convention, aligned))
}

/// Serialise a sparse carriageway table, 4-byte aligned. Layers whose splits
/// are all default are omitted; the inverse of [`parse_sparse_carriageways`].
pub fn serialize_sparse_carriageways(
    carriageways: &[(u8, Vec<Carriageway>)],
    convention: MarkingConvention,
    out: &mut Vec<u8>,
) {
    out.push(convention.to_byte());
    out.extend_from_slice(&[0u8; 3]);
    let present: Vec<(&u8, &Vec<Carriageway>)> = carriageways
        .iter()
        .map(|(id, v)| (id, v))
        .filter(|(_, v)| v.iter().any(|c| !c.is_empty()))
        .collect();
    out.extend_from_slice(&(present.len() as u32).to_le_bytes());
    for (layer_id, entries) in present {
        out.push(*layer_id);
        out.extend_from_slice(&(entries.len() as u32).to_le_bytes());
        let mut bits = vec![0u8; entries.len().div_ceil(8)];
        for (i, c) in entries.iter().enumerate() {
            if !c.is_empty() {
                bits[i / 8] |= 1u8 << (i % 8);
            }
        }
        out.extend_from_slice(&bits);
        for c in entries {
            if c.is_empty() {
                continue;
            }
            out.push(c.forward);
            out.push(c.backward);
            out.extend_from_slice(&c.solid_dividers.to_le_bytes());
        }
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
}

/// The v10 narrowed S3DB entry width: heights (6) + packed shape/orient/colour
/// flags (1) + direction (1) + two RGB24 colours (6) = 14, down from 20.
pub const BUILDING_ATTRS_V10_LEN: usize = 14;

/// True when a building carries no S3DB tags at all: the sparse table omits
/// it and the reader restores [`BuildingAttrs::default`].
fn is_default_attrs(a: &BuildingAttrs) -> bool {
    *a == BuildingAttrs::default()
}

/// Pack shape/orientation/colour presence into one byte.
///
/// Bits: 0..3 shape (≤ 5), 3 orientation, 4 has-building-colour, 5
/// has-roof-colour, 6..8 reserved zero. Colours are RGB24 on the wire (alpha
/// is always `FF` or absent); the presence bits keep opaque black distinct
/// from "no colour".
fn pack_attrs(a: &BuildingAttrs) -> u8 {
    let mut packed = a.roof_shape & 0x07;
    if a.roof_orientation == super::model::ROOF_ORIENT_ACROSS {
        packed |= 0x08;
    }
    if a.building_colour != 0 {
        packed |= 0x10;
    }
    if a.roof_colour != 0 {
        packed |= 0x20;
    }
    packed
}

/// Parse the v10 sparse S3DB table: `u32` layer count, then per layer a `u8`
/// id, a `u32` feature count, a bitset of the features carrying tags
/// (`(n+7)/8` bytes), and the packed 14-byte non-default entries in feature
/// order. Expands to the dense model (defaults filled), so the reader API is
/// unchanged. Returns the table and the bytes consumed.
pub(crate) fn parse_sparse_buildings(
    buf: &[u8],
    layers: &[Layer],
) -> Result<(LayerTable<BuildingAttrs>, usize)> {
    use super::model::{ROOF_ORIENT_ACROSS, ROOF_ORIENT_ALONG};
    if buf.len() < 4 {
        return err("a .mamaps sparse building table ends before its count");
    }
    let count = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
    if count > layers.len() {
        return err(format!(
            "a .mamaps sparse building table has {count} entries for a body with {} layer(s)",
            layers.len(),
        ));
    }
    let mut at = 4usize;
    let mut table = Vec::with_capacity(count);
    let mut previous: Option<u8> = None;
    for _ in 0..count {
        if at + 5 > buf.len() {
            return err("a .mamaps sparse building table ends inside an entry header");
        }
        let layer_id = buf[at];
        let n =
            u32::from_le_bytes([buf[at + 1], buf[at + 2], buf[at + 3], buf[at + 4]]) as usize;
        at += 5;
        if previous.is_some_and(|p| layer_id <= p) {
            return err("a .mamaps sparse building table's entries are not ordered by layer id");
        }
        previous = Some(layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == layer_id) else {
            return err(format!(
                "a .mamaps sparse building table names layer {layer_id}, which the body does not carry"
            ));
        };
        if n != layer.features.len() {
            return err(format!(
                "a .mamaps sparse building table gives layer {layer_id} {n} bit(s) for {} feature(s)",
                layer.features.len(),
            ));
        }
        let bitlen = n.div_ceil(8);
        if at + bitlen > buf.len() {
            return err("a .mamaps sparse building table ends inside its bitset");
        }
        let bits = &buf[at..at + bitlen];
        at += bitlen;
        let mut attrs = Vec::with_capacity(n);
        for i in 0..n {
            if bits[i / 8] & (1u8 << (i % 8)) == 0 {
                attrs.push(BuildingAttrs::default());
                continue;
            }
            if at + BUILDING_ATTRS_V10_LEN > buf.len() {
                return err("a .mamaps sparse building record runs past the table");
            }
            let o = at;
            let roof_shape = buf[o + 6] & 0x07;
            if roof_shape > ROOF_SHAPE_MAX {
                return err(format!("a .mamaps sparse building record has roof shape {roof_shape}"));
            }
            let roof_orientation = if buf[o + 6] & 0x08 != 0 {
                ROOF_ORIENT_ACROSS
            } else {
                ROOF_ORIENT_ALONG
            };
            let has_bcol = buf[o + 6] & 0x10 != 0;
            let has_rcol = buf[o + 6] & 0x20 != 0;
            if buf[o + 6] & 0xC0 != 0 {
                return err("a .mamaps sparse building record sets reserved flag bits");
            }
            let bcol = u32::from_le_bytes([buf[o + 8], buf[o + 9], buf[o + 10], 0xFF]);
            let rcol = u32::from_le_bytes([buf[o + 11], buf[o + 12], buf[o + 13], 0xFF]);
            if !has_bcol && (buf[o + 8] != 0 || buf[o + 9] != 0 || buf[o + 10] != 0) {
                return err("a .mamaps sparse building record carries a colour it disclaims");
            }
            if !has_rcol && (buf[o + 11] != 0 || buf[o + 12] != 0 || buf[o + 13] != 0) {
                return err("a .mamaps sparse building record carries a colour it disclaims");
            }
            attrs.push(BuildingAttrs {
                height: u16::from_le_bytes([buf[o], buf[o + 1]]),
                min_height: u16::from_le_bytes([buf[o + 2], buf[o + 3]]),
                roof_height: u16::from_le_bytes([buf[o + 4], buf[o + 5]]),
                roof_shape,
                roof_direction: buf[o + 7],
                roof_orientation,
                building_colour: if has_bcol { bcol } else { 0 },
                roof_colour: if has_rcol { rcol } else { 0 },
            });
            at += BUILDING_ATTRS_V10_LEN;
        }
        table.push((layer_id, attrs));
    }
    let aligned = align4(at);
    if aligned > buf.len() {
        return err("a .mamaps sparse building table's padding runs past the body");
    }
    Ok((table, aligned))
}

/// Serialise a sparse S3DB table, 4-byte aligned. Layers with no tagged
/// buildings are omitted entirely (the reader restores all-default); the
/// inverse of [`parse_sparse_buildings`].
pub fn serialize_sparse_buildings(buildings: &[(u8, Vec<BuildingAttrs>)], out: &mut Vec<u8>) {
    let present: Vec<(&u8, &Vec<BuildingAttrs>)> = buildings
        .iter()
        .map(|(id, v)| (id, v))
        .filter(|(_, v)| v.iter().any(|a| !is_default_attrs(a)))
        .collect();
    out.extend_from_slice(&(present.len() as u32).to_le_bytes());
    for (layer_id, entries) in present {
        out.push(*layer_id);
        out.extend_from_slice(&(entries.len() as u32).to_le_bytes());
        let mut bits = vec![0u8; entries.len().div_ceil(8)];
        for (i, a) in entries.iter().enumerate() {
            if !is_default_attrs(a) {
                bits[i / 8] |= 1u8 << (i % 8);
            }
        }
        out.extend_from_slice(&bits);
        for a in entries {
            if is_default_attrs(a) {
                continue;
            }
            out.extend_from_slice(&a.height.to_le_bytes());
            out.extend_from_slice(&a.min_height.to_le_bytes());
            out.extend_from_slice(&a.roof_height.to_le_bytes());
            out.push(pack_attrs(a));
            out.push(a.roof_direction);
            // RGB24: alpha is always FF-or-absent, presence in the packed flags.
            out.push((a.building_colour & 0xFF) as u8);
            out.push(((a.building_colour >> 8) & 0xFF) as u8);
            out.push(((a.building_colour >> 16) & 0xFF) as u8);
            out.push((a.roof_colour & 0xFF) as u8);
            out.push(((a.roof_colour >> 8) & 0xFF) as u8);
            out.push(((a.roof_colour >> 16) & 0xFF) as u8);
        }
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
}
