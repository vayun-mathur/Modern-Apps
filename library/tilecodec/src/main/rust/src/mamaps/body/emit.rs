use super::consts::{BODY_FLAG_BUILDING_TABLE, BODY_FLAG_EXTENDED_COUNTS, BODY_FLAG_HEIGHTMAP, BODY_FLAG_ID_TABLE, BODY_FLAG_LANE_TABLE, BODY_FLAG_NAME_TABLE, BODY_FLAG_REGION_LINKS, BODY_FLAG_ROAD_LANES, BODY_HEADER_LEN, LAYER_INDEX_LEN};
use super::model::{
    Body, Layer, NAME_NONE, ROOF_ORIENT_MAX, ROOF_SHAPE_MAX,
};
use super::emit_extra::write_full_layer_payload;
use super::emit_extra::write_v10_layer_payload;
use super::tables::{serialize_carriageways, serialize_heightmap, serialize_ids, serialize_lanes, serialize_names, serialize_region_links, align4};
use super::tables_extra::serialize_buildings;
use super::tables_extra::{serialize_sparse_buildings, serialize_sparse_carriageways, serialize_sparse_lanes};
use crate::proto::{Result, err};

/// Serialise a body.
///
/// Deliberately available without the `write` feature: a synthetic body is how the reader's own
/// tests get something to read, and `mamaps_dump` round-trips one to check a body is sane.
/// Reusable buffers for [`serialize_into`].
///
/// One per worker, not one per tile. Serialising allocated a payload `Vec` per layer plus the
/// output `Vec` per tile, and on a us-west z14 build across 64 threads that allocation traffic --
/// not the encoding -- was the cost: measured with the encode pass split three ways, serialisation
/// took 20.9 s of CPU at 16 threads and 1120 s at 64, while DEFLATE over the same work merely
/// doubled. Encode wall time was *lower* at 16 threads than at 64. Buffers that outlive a tile take
/// the allocator out of the loop.
#[derive(Default)]
pub struct Scratch {
    payloads: Vec<Vec<u8>>,
    meta: Vec<(u8, u32)>,
    out: Vec<u8>,
}

/// Serialise a body into `scratch`, returning the bytes it holds.
///
/// Byte for byte what [`serialize`] returns; that function is this one with a fresh [`Scratch`].
pub fn serialize_into<'s>(body: &Body, scratch: &'s mut Scratch) -> Result<&'s [u8]> {
    serialize_into_version(body, scratch, crate::mamaps::header::FORMAT_VERSION)
}

/// Serialise a body with v10 narrowed records into `scratch`.
///
/// Same validation as [`serialize_into`]; layer payloads use the per-layer
/// record classes and 4-byte parts, and the version byte stamps v10. Side
/// tables are unchanged in this path (see the sparse-table serializers for
/// the full v10 body).
pub fn serialize_into_v10<'s>(body: &Body, scratch: &'s mut Scratch) -> Result<&'s [u8]> {
    serialize_into_version(body, scratch, crate::mamaps::header::FORMAT_VERSION_V10)
}

/// Serialise a planet5 v11 body: v10 records and side tables, with the heightmap stored as MED
/// residuals (see [`crate::mamaps::header::FORMAT_VERSION_V11`]). Lossless; same model back.
pub fn serialize_into_v11<'s>(body: &Body, scratch: &'s mut Scratch) -> Result<&'s [u8]> {
    serialize_into_version(body, scratch, crate::mamaps::header::FORMAT_VERSION_V11)
}

fn serialize_into_version<'s>(
    body: &Body,
    scratch: &'s mut Scratch,
    version: u8,
) -> Result<&'s [u8]> {
    // Borrowed and sorted by reference, never cloned. Cloning to sort copied every layer's whole
    // coordinate arena per tile, which is 2.5 GB of memcpy on a us-west z14 build. Nothing here
    // mutates a layer, so the sort only ever needed the order.
    let mut layers: Vec<&Layer> = body.layers.iter().collect();
    // Ascending by id, which the parser requires and `Body::layer` assumes. Sorted rather than
    // rejected, because a caller assembling a tile per style layer has no reason to care.
    layers.sort_by_key(|l| l.layer_id);
    if layers.windows(2).any(|pair| pair[0].layer_id == pair[1].layer_id) {
        return err("a .mamaps body cannot carry two layers with the same id");
    }
    let needs_extended = layers.iter().any(|l| l.features.len() > u16::MAX as usize);
    for layer in &layers {
        if !needs_extended && layer.features.len() > u16::MAX as usize {
            return err(format!(
                "layer {} has {} features, past the {} a .mamaps body can index",
                layer.layer_id,
                layer.features.len(),
                u16::MAX,
            ));
        }
        if layer.features.len() > u32::MAX as usize {
            return err(format!(
                "layer {} has {} features, past u32",
                layer.layer_id,
                layer.features.len()
            ));
        }
        // The arena is written in parts-table order with no offsets of its own, so a part has to
        // start exactly where the one before it ended. Checked here rather than trusted, because a
        // caller that got it wrong would produce a body that round-trips to different geometry.
        let mut at = 0u32;
        for part in &layer.parts {
            if part.coord_start != at {
                return err(format!(
                    "layer {}'s parts are not contiguous: one starts at point {} where {at} was \
                     expected",
                    layer.layer_id, part.coord_start,
                ));
            }
            at = at
                .checked_add(part.point_count)
                .ok_or_else(|| crate::proto::Error("a .mamaps layer's parts overflow".into()))?;
        }
        if at as usize != layer.coords.len() {
            return err(format!(
                "layer {}'s parts cover {at} points but its arena holds {}",
                layer.layer_id,
                layer.coords.len(),
            ));
        }
        for feature in &layer.features {
            let end = feature.parts_offset as usize + feature.part_count as usize;
            if feature.part_count == 0 || end > layer.parts.len() {
                return err(format!(
                    "layer {}'s feature indexes parts {}..{end} of {}",
                    layer.layer_id,
                    feature.parts_offset,
                    layer.parts.len(),
                ));
            }
            // A name index must name something: index 0 is NAME_NONE and anything past the
            // table would decode to a different string (or nothing) on read.
            if feature.name_idx != NAME_NONE
                && (feature.name_idx as usize) > body.names.len()
            {
                return err(format!(
                    "layer {}'s feature names index {} of {} name(s)",
                    layer.layer_id,
                    feature.name_idx,
                    body.names.len(),
                ));
            }
        }
    }

    // The id table is keyed by layer id and parallel to that layer's features, so a caller that
    // built it against a different layer set would produce a body that reads back with every id
    // attributed to the wrong feature. Checked here rather than trusted, for the same reason the
    // parts contiguity above is.
    let mut previous: Option<u8> = None;
    for (layer_id, entries) in &body.ids {
        if previous.is_some_and(|p| *layer_id <= p) {
            return err("a .mamaps body's id table is not ordered by layer id");
        }
        previous = Some(*layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == *layer_id) else {
            return err(format!(
                "a .mamaps id table names layer {layer_id}, which the body does not carry"
            ));
        };
        if entries.len() != layer.features.len() {
            return err(format!(
                "a .mamaps id table gives layer {layer_id} {} id(s) for {} feature(s)",
                entries.len(),
                layer.features.len(),
            ));
        }
    }

    // The turn-lane table is validated exactly as the id table is: keyed and ascending by layer id
    // and dense-parallel to that layer's features, so a caller that built it against a different
    // layer set is refused here rather than producing a body that misattributes every lane.
    let mut previous: Option<u8> = None;
    for (layer_id, entries) in &body.turn_lanes {
        if previous.is_some_and(|p| *layer_id <= p) {
            return err("a .mamaps body's turn-lane table is not ordered by layer id");
        }
        previous = Some(*layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == *layer_id) else {
            return err(format!(
                "a .mamaps turn-lane table names layer {layer_id}, which the body does not carry"
            ));
        };
        if entries.len() != layer.features.len() {
            return err(format!(
                "a .mamaps turn-lane table gives layer {layer_id} {} record(s) for {} feature(s)",
                entries.len(),
                layer.features.len(),
            ));
        }
        for turns in entries {
            if turns.forward.len() > u8::MAX as usize || turns.backward.len() > u8::MAX as usize {
                return err(format!(
                    "a .mamaps turn-lane record on layer {layer_id} has more than {} lanes",
                    u8::MAX,
                ));
            }
        }
    }

    // The carriageway table, validated exactly as the turn-lane table above. The convention is
    // only written alongside a table, so a body that sets one with no rows would drop it silently
    // on the wire and come back `None` — refused here rather than round-tripping to something the
    // caller did not ask for.
    if body.convention.is_some() && body.carriageways.is_empty() {
        return err("a .mamaps body sets a marking convention but carries no carriageway table");
    }
    let mut previous: Option<u8> = None;
    for (layer_id, entries) in &body.carriageways {
        if previous.is_some_and(|p| *layer_id <= p) {
            return err("a .mamaps body's carriageway table is not ordered by layer id");
        }
        previous = Some(*layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == *layer_id) else {
            return err(format!(
                "a .mamaps carriageway table names layer {layer_id}, which the body does not carry"
            ));
        };
        if entries.len() != layer.features.len() {
            return err(format!(
                "a .mamaps carriageway table gives layer {layer_id} {} record(s) for {} feature(s)",
                entries.len(),
                layer.features.len(),
            ));
        }
    }

    // The building table is validated exactly as the id and turn-lane tables are: keyed and
    // ascending by layer id and dense-parallel to that layer's features, so a caller that built it
    // against a different layer set is refused rather than misattributing every building's height.
    let mut previous: Option<u8> = None;
    for (layer_id, entries) in &body.buildings {
        if previous.is_some_and(|p| *layer_id <= p) {
            return err("a .mamaps body's building table is not ordered by layer id");
        }
        previous = Some(*layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == *layer_id) else {
            return err(format!(
                "a .mamaps building table names layer {layer_id}, which the body does not carry"
            ));
        };
        if entries.len() != layer.features.len() {
            return err(format!(
                "a .mamaps building table gives layer {layer_id} {} record(s) for {} feature(s)",
                entries.len(),
                layer.features.len(),
            ));
        }
        for a in entries {
            if a.roof_shape > ROOF_SHAPE_MAX {
                return err(format!(
                    "a .mamaps building record on layer {layer_id} has roof shape {}",
                    a.roof_shape,
                ));
            }
            if a.roof_orientation > ROOF_ORIENT_MAX {
                return err(format!(
                    "a .mamaps building record on layer {layer_id} has roof orientation {}",
                    a.roof_orientation,
                ));
            }
        }
    }

    // The region-link table is validated exactly as the id table is: keyed and ascending by layer
    // id and dense-parallel to that layer's features, so a caller that built it against a different
    // layer set is refused rather than linking every label to the wrong region.
    let mut previous: Option<u8> = None;
    for (layer_id, entries) in &body.region_links {
        if previous.is_some_and(|p| *layer_id <= p) {
            return err("a .mamaps body's region-link table is not ordered by layer id");
        }
        previous = Some(*layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == *layer_id) else {
            return err(format!(
                "a .mamaps region-link table names layer {layer_id}, which the body does not carry"
            ));
        };
        if entries.len() != layer.features.len() {
            return err(format!(
                "a .mamaps region-link table gives layer {layer_id} {} link(s) for {} feature(s)",
                entries.len(),
                layer.features.len(),
            ));
        }
    }

    let mut index_end = BODY_HEADER_LEN + layers.len() * LAYER_INDEX_LEN;
    if needs_extended {
        index_end += layers.len() * 4;
    }
    // Both fields at once, which needs the struct broken apart: the payloads are built first and
    // then copied into the output, and each wants its own mutable borrow.
    let Scratch { payloads, meta, out: assembled } = scratch;
    meta.clear();
    for (i, layer) in layers.iter().enumerate() {
        if i >= payloads.len() {
            payloads.push(Vec::new());
        }
        let out = &mut payloads[i];
        out.clear();
        if version == crate::mamaps::header::FORMAT_VERSION_V10
            || version == crate::mamaps::header::FORMAT_VERSION_V11
        {
            write_v10_layer_payload(layer, out)?;
        } else {
            write_full_layer_payload(layer, out);
        }
        meta.push((layer.layer_id, layer.features.len() as u32));
    }

    let mut offset = align4(index_end);
    assembled.clear();
    assembled.reserve(index_end + payloads[..layers.len()].iter().map(Vec::len).sum::<usize>() + 4);
    assembled.extend_from_slice(b"MBD");
    assembled.push(version);
    // Patched below, once the length is known.
    assembled.extend_from_slice(&0u32.to_le_bytes());
    assembled.extend_from_slice(&body.extent.to_le_bytes());
    assembled.push(layers.len() as u8);
    let mut body_flags = if needs_extended { BODY_FLAG_EXTENDED_COUNTS } else { 0 };
    if !body.names.is_empty() {
        body_flags |= BODY_FLAG_NAME_TABLE;
    }
    if !body.ids.is_empty() {
        body_flags |= BODY_FLAG_ID_TABLE;
    }
    if !body.turn_lanes.is_empty() {
        body_flags |= BODY_FLAG_LANE_TABLE;
    }
    if !body.buildings.is_empty() {
        body_flags |= BODY_FLAG_BUILDING_TABLE;
    }
    let v10 = version == crate::mamaps::header::FORMAT_VERSION_V10
        || version == crate::mamaps::header::FORMAT_VERSION_V11;
    let v11 = version == crate::mamaps::header::FORMAT_VERSION_V11;
    if body.heightmap.is_some() {
        body_flags |= BODY_FLAG_HEIGHTMAP;
    }
    if !body.carriageways.is_empty() {
        body_flags |= BODY_FLAG_ROAD_LANES;
    }
    if !body.region_links.is_empty() {
        body_flags |= BODY_FLAG_REGION_LINKS;
    }
    assembled.push(body_flags);
    assembled.extend_from_slice(&0u32.to_le_bytes());
    for (i, (layer_id, feature_count)) in meta.iter().enumerate() {
        assembled.push(*layer_id);
        assembled.push(0);
        // For extended, write sentinel-limited low 16 (old readers see at most 65534
        // and reject on payload bound); real count is the u32 after the index.
        let fc: u16 = if needs_extended {
            (*feature_count as usize).min(0xFFFE) as u16
        } else {
            // Non-extended path is byte-identical to the old format: u16 carry
            // validated above to be ≤65535.
            u16::try_from(*feature_count).expect("feature count fits u16 in non-extended body")
        };
        assembled.extend_from_slice(&fc.to_le_bytes());
        assembled.extend_from_slice(&(offset as u32).to_le_bytes());
        assembled.extend_from_slice(&(payloads[i].len() as u32).to_le_bytes());
        offset += payloads[i].len();
    }
    if needs_extended {
        for layer in &layers {
            assembled.extend_from_slice(&(layer.features.len() as u32).to_le_bytes());
        }
    }
    while assembled.len() % 4 != 0 {
        assembled.push(0);
    }
    for payload in &payloads[..layers.len()] {
        assembled.extend_from_slice(payload);
    }
    // The trailing sections, each omitted when empty so a tile with none is payloads-then-end
    // exactly as the parser expects. Each is announced in `body_flags` above.
    if !body.names.is_empty()
        || !body.ids.is_empty()
        || !body.turn_lanes.is_empty()
        || !body.buildings.is_empty()
        || body.heightmap.is_some()
        || !body.carriageways.is_empty()
        || !body.region_links.is_empty()
    {
        while assembled.len() % 4 != 0 {
            assembled.push(0);
        }
    }
    if !body.names.is_empty() {
        assembled.extend_from_slice(&serialize_names(&body.names));
    }
    if !body.ids.is_empty() {
        serialize_ids(&body.ids, assembled);
    }
    if !body.turn_lanes.is_empty() {
        if v10 {
            serialize_sparse_lanes(&body.turn_lanes, assembled);
        } else {
            serialize_lanes(&body.turn_lanes, assembled);
        }
    }
    if !body.buildings.is_empty() {
        if v10 {
            serialize_sparse_buildings(&body.buildings, assembled);
        } else {
            serialize_buildings(&body.buildings, assembled);
        }
    }
    if let Some(grid) = &body.heightmap {
        if v11 {
            super::tables::serialize_heightmap_residual(grid, assembled);
        } else {
            serialize_heightmap(grid, assembled);
        }
    }
    if !body.carriageways.is_empty() {
        // The convention only reaches the wire alongside a table, so a body that sets one without
        // any carriageway rows would silently drop it. Validation above rejects that.
        if v10 {
            serialize_sparse_carriageways(
                &body.carriageways,
                body.convention.unwrap_or_default(),
                assembled,
            );
        } else {
            serialize_carriageways(
                &body.carriageways,
                body.convention.unwrap_or_default(),
                assembled,
            );
        }
    }
    if !body.region_links.is_empty() {
        serialize_region_links(&body.region_links, assembled);
    }
    let raw_len = assembled.len() as u32;
    assembled[4..8].copy_from_slice(&raw_len.to_le_bytes());
    Ok(assembled)
}

/// Serialise a body to a fresh buffer. [`serialize_into`] is the same thing with the buffer reused.
pub fn serialize(body: &Body) -> Result<Vec<u8>> {
    let mut scratch = Scratch::default();
    serialize_into(body, &mut scratch).map(<[u8]>::to_vec)
}
