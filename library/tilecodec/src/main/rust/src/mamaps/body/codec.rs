use super::consts::{BODY_FLAG_BUILDING_TABLE, BODY_FLAG_EXTENDED_COUNTS, BODY_FLAG_HEIGHTMAP, BODY_FLAG_ID_TABLE, BODY_FLAG_LANE_TABLE, BODY_FLAG_NAME_TABLE, BODY_FLAG_REGION_LINKS, BODY_FLAG_ROAD_LANES, BODY_HEADER_LEN, FEATURE_RECORD_LEN, GEOM_LINE, GEOM_POINT, GEOM_POLYGON, KNOWN_BODY_FLAGS, KNOWN_FEATURE_FLAGS, LAYER_INDEX_LEN, PART_ENTRY_LEN, PART_ENTRY_V10_LEN, WINDING_HOLE, WINDING_OUTER};
use super::model::{Body, BuildingAttrs, Carriageway, Feature, LaneTurns, Layer, NAME_NONE, Part};
use super::tables::{align4, parse_carriageways, parse_heightmap, parse_ids, parse_lanes, parse_names, parse_region_links, payloads_end};
use super::tables_extra::{parse_buildings, parse_sparse_buildings, parse_sparse_carriageways, parse_sparse_lanes};
use crate::proto::{Result, err};

impl Body {
    pub fn new(extent: u16) -> Body {
        Body {
            extent,
            layers: Vec::new(),
            names: Vec::new(),
            ids: Vec::new(),
            turn_lanes: Vec::new(),
            buildings: Vec::new(),
            heightmap: None,
            carriageways: Vec::new(),
            convention: None,
            region_links: Vec::new(),
        }
    }

    pub fn layer(&self, layer_id: u8) -> Option<&Layer> {
        self.layers.iter().find(|l| l.layer_id == layer_id)
    }

    /// This feature's per-lane turn indications, or `None` when its layer carries no turn-lane
    /// table. A feature in a layer that has one but with no `turn:lanes` of its own reads back as
    /// an empty [`LaneTurns`], not `None`.
    pub fn feature_turns(&self, layer_id: u8, index: usize) -> Option<&LaneTurns> {
        self.turn_lanes
            .iter()
            .find(|(id, _)| *id == layer_id)
            .and_then(|(_, turns)| turns.get(index))
    }

    /// The `index`-th feature of `layer_id`'s carriageway shape, or `None` when the tile carries no
    /// carriageway table for that layer.
    ///
    /// A road present in the table but with nothing known holds a default [`Carriageway`], not
    /// `None` — the same convention [`feature_turns`](Self::feature_turns) uses.
    pub fn feature_carriageway(&self, layer_id: u8, index: usize) -> Option<&Carriageway> {
        self.carriageways
            .iter()
            .find(|(id, _)| *id == layer_id)
            .and_then(|(_, rows)| rows.get(index))
    }

    /// The stable feature id for the `index`-th feature of `layer_id`.
    ///
    /// `None` when the layer carries no id table at all; [`ID_NONE`] when it does but the
    /// generator could not attribute this feature to an OSM element.
    pub fn feature_id(&self, layer_id: u8, index: usize) -> Option<u64> {
        self.ids
            .iter()
            .find(|(id, _)| *id == layer_id)
            .and_then(|(_, ids)| ids.get(index).copied())
    }

    /// The tagged OSM relation id of the admin boundary the `index`-th feature of `layer_id` names,
    /// or `None` when the layer carries no region-link table.
    ///
    /// [`REGION_NONE`](super::model::REGION_NONE) when the layer has a table but this label links to
    /// no region. Only the `places` layer ever carries one.
    pub fn region_link(&self, layer_id: u8, index: usize) -> Option<u64> {
        self.region_links
            .iter()
            .find(|(id, _)| *id == layer_id)
            .and_then(|(_, links)| links.get(index).copied())
    }

    /// This feature's S3DB attributes, or `None` when its layer carries no building table. A
    /// feature in a layer that has one but with no S3DB tags of its own reads back as a default
    /// [`BuildingAttrs`], not `None`.
    pub fn building_attrs(&self, layer_id: u8, index: usize) -> Option<BuildingAttrs> {
        self.buildings
            .iter()
            .find(|(id, _)| *id == layer_id)
            .and_then(|(_, attrs)| attrs.get(index).copied())
    }

    /// The display name for a `name_idx`, or `None` for [`NAME_NONE`] and anything past the table.
    pub fn name(&self, idx: u16) -> Option<&str> {
        (idx != NAME_NONE)
            .then(|| self.names.get(idx as usize - 1).map(String::as_str))
            .flatten()
    }

    /// The decompressed size a body's bytes will occupy, read without decoding anything else.
    ///
    /// Outside the compressed frame on purpose: it is what lets a reader allocate exactly once.
    /// Needs only the first eight bytes, so it can be answered from a frame's uncompressed prefix.
    pub fn raw_len(bytes: &[u8]) -> Result<u32> {
        if bytes.len() < 8 {
            return err("a .mamaps body is too short to declare its own length");
        }
        Ok(u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]))
    }

    /// Body header: `0..4` magic-and-version, `4..8` raw_len, `8..10` extent, `10` layer_count,
    /// `11` flags, `12..16` reserved. Flag `0x01` means extended feature counts follow the layer
    /// index; `0x02` an id table and `0x04` a name table trail the payloads, in that order.
    pub fn parse(buf: &[u8]) -> Result<Body> {
        if buf.len() < BODY_HEADER_LEN {
            return err("a .mamaps body is shorter than its own header");
        }
        if buf[0..3] != *b"MBD" {
            return err("not a .mamaps tile body (bad magic)");
        }
        let version = buf[3];
        if version != crate::mamaps::header::FORMAT_VERSION
            && version != crate::mamaps::header::FORMAT_VERSION_V9
            && version != crate::mamaps::header::FORMAT_VERSION_V10
        {
            return err(format!("unsupported .mamaps body version {version}"));
        }
        let v10 = version == crate::mamaps::header::FORMAT_VERSION_V10;
        let raw_len = u32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]) as usize;
        if raw_len != buf.len() {
            return err(format!(
                "a .mamaps body declares {raw_len} bytes but is {}",
                buf.len(),
            ));
        }
        let extent = u16::from_le_bytes([buf[8], buf[9]]);
        if extent == 0 {
            return err("a .mamaps body has a zero extent");
        }
        let layer_count = buf[10] as usize;
        let body_flags = buf[11];
        // All eight bits are defined today, so `!KNOWN_BODY_FLAGS` is 0 and clippy calls this
        // a no-op; it stays so a ninth flag (or a narrowed set) is refused, not misread.
        #[allow(clippy::bad_bit_mask)]
        let unknown = body_flags & !KNOWN_BODY_FLAGS;
        if unknown != 0 {
            return err(format!("unknown body flags {:#04x}", body_flags));
        }
        // Reserved word must be zero unless extended flag is set; extended uses it as spill for counts.
        if body_flags & BODY_FLAG_EXTENDED_COUNTS == 0
            && u32::from_le_bytes([buf[12], buf[13], buf[14], buf[15]]) != 0
        {
            return err("a .mamaps body has a non-zero reserved word");
        }

        let has_extended = (body_flags & BODY_FLAG_EXTENDED_COUNTS) != 0;
        let mut index_end = BODY_HEADER_LEN + layer_count * LAYER_INDEX_LEN;
        if has_extended {
            index_end = index_end.checked_add(layer_count * 4).ok_or_else(|| {
                crate::proto::Error("extended counts overflow".to_string())
            })?;
        }
        if index_end > buf.len() {
            return err("a .mamaps body's layer index runs past its end");
        }
        // Extended counts live right after the layer index, one u32 LE per layer in order.
        let ext_at = BODY_HEADER_LEN + layer_count * LAYER_INDEX_LEN;
        let mut layers = Vec::with_capacity(layer_count);
        let mut previous: Option<u8> = None;
        for i in 0..layer_count {
            let at = BODY_HEADER_LEN + i * LAYER_INDEX_LEN;
            let u32_at = |o: usize| {
                u32::from_le_bytes([buf[at + o], buf[at + o + 1], buf[at + o + 2], buf[at + o + 3]])
            };
            let layer_id = buf[at];
            // Ascending and distinct, so `Body::layer` is unambiguous and a corrupt body cannot
            // present two layers with one id.
            if previous.is_some_and(|p| layer_id <= p) {
                return err("a .mamaps body's layers are not ordered by id");
            }
            previous = Some(layer_id);
            let feature_count = if has_extended {
                u32::from_le_bytes([
                    buf[ext_at + i * 4],
                    buf[ext_at + i * 4 + 1],
                    buf[ext_at + i * 4 + 2],
                    buf[ext_at + i * 4 + 3],
                ]) as usize
            } else {
                u16::from_le_bytes([buf[at + 2], buf[at + 3]]) as usize
            };
            let (offset, length) = (u32_at(4) as usize, u32_at(8) as usize);
            let end = offset.checked_add(length).ok_or_else(|| {
                crate::proto::Error("a .mamaps layer's extent overflows".to_string())
            })?;
            if offset < index_end || end > buf.len() {
                return err("a .mamaps layer's payload is outside the body");
            }
            layers.push(if v10 {
                parse_layer_v10(layer_id, feature_count, &buf[offset..end])?
            } else {
                parse_layer(layer_id, feature_count, &buf[offset..end])?
            });
        }
        // The optional trailing sections, in order: the name table, then the id table, then the
        // turn-lane table, then the building table, then the heightmap, then the carriageway
        // table, each at the aligned end of the one before it. Each is announced by a flag rather
        // than inferred from leftover bytes, because with six of them "there are bytes left" no
        // longer says which one they are. Order is positional: append, never insert.
        let mut names = Vec::new();
        let mut ids = Vec::new();
        let mut turn_lanes = Vec::new();
        let mut buildings = Vec::new();
        let mut heightmap = None;
        let mut carriageways = Vec::new();
        let mut convention = None;
        let mut region_links = Vec::new();
        let payloads_end = payloads_end(buf, layer_count, index_end)?;
        let has_names = body_flags & BODY_FLAG_NAME_TABLE != 0;
        let has_ids = body_flags & BODY_FLAG_ID_TABLE != 0;
        let has_lanes = body_flags & BODY_FLAG_LANE_TABLE != 0;
        let has_buildings = body_flags & BODY_FLAG_BUILDING_TABLE != 0;
        let has_heightmap = body_flags & BODY_FLAG_HEIGHTMAP != 0;
        let has_carriageways = body_flags & BODY_FLAG_ROAD_LANES != 0;
        let has_region_links = body_flags & BODY_FLAG_REGION_LINKS != 0;
        if !has_names
            && !has_ids
            && !has_lanes
            && !has_buildings
            && !has_heightmap
            && !has_carriageways
            && !has_region_links
        {
            // The body is exactly its payloads, unpadded. Anything else is trailing garbage.
            if payloads_end != buf.len() {
                return err(format!(
                    "a .mamaps body has {} trailing byte(s) past its payloads",
                    buf.len().saturating_sub(payloads_end),
                ));
            }
        } else {
            let mut at = align4(payloads_end);
            if at > buf.len() {
                return err("a .mamaps body's trailing sections start past its end");
            }
            if has_names {
                let (table, used) = parse_names(&buf[at..])?;
                at += used;
                names = table;
            }
            if has_ids {
                let (table, used) = parse_ids(&buf[at..], &layers)?;
                at += used;
                ids = table;
            }
            if has_lanes {
                // v10 lanes are sparse (bitset + non-empty masks only); v8 is
                // dense. Both expand to the same dense model.
                let (table, used) = if v10 {
                    parse_sparse_lanes(&buf[at..], &layers)?
                } else {
                    parse_lanes(&buf[at..], &layers)?
                };
                at += used;
                turn_lanes = table;
            }
            if has_buildings {
                let (table, used) = if v10 {
                    parse_sparse_buildings(&buf[at..], &layers)?
                } else {
                    parse_buildings(&buf[at..], &layers)?
                };
                // v10 sparse expands to the dense model, so the shapes agree.
                at += used;
                buildings = table;
            }
            if has_heightmap {
                let (grid, used) = parse_heightmap(&buf[at..])?;
                at += used;
                heightmap = Some(grid);
            }
            if has_carriageways {
                // v10 carriageways are sparse like v10 lanes; v8 is dense.
                let (table, marking, used) = if v10 {
                    parse_sparse_carriageways(&buf[at..], &layers)?
                } else {
                    parse_carriageways(&buf[at..], &layers)?
                };
                at += used;
                carriageways = table;
                convention = Some(marking);
            }
            if has_region_links {
                let (table, used) = parse_region_links(&buf[at..], &layers)?;
                at += used;
                region_links = table;
            }
            if at != buf.len() {
                return err(format!(
                    "a .mamaps body has {} trailing byte(s) past its trailing sections",
                    buf.len() - at,
                ));
            }
        }
        Ok(Body {
            extent,
            layers,
            names,
            ids,
            turn_lanes,
            buildings,
            heightmap,
            carriageways,
            convention,
            region_links,
        })
    }
}
/// One layer's payload: features, then parts, then the coordinate arena.
///
/// Every count is bounded by the slice it is read from before anything is allocated, so a corrupt
/// body cannot ask for a gigabyte of `Vec`.
pub(crate) fn parse_layer(layer_id: u8, feature_count: usize, buf: &[u8]) -> Result<Layer> {
    parse_layer_v8(layer_id, feature_count, buf)
}

/// v8 payload: 24-byte records, 12-byte parts.
fn parse_layer_v8(layer_id: u8, feature_count: usize, buf: &[u8]) -> Result<Layer> {
    let features_len = feature_count * FEATURE_RECORD_LEN;
    if features_len > buf.len() {
        return err("a .mamaps layer's features run past its payload");
    }
    let mut features = Vec::with_capacity(feature_count);
    let mut parts_needed = 0usize;
    for i in 0..feature_count {
        let at = i * FEATURE_RECORD_LEN;
        let u16_at = |o: usize| u16::from_le_bytes([buf[at + o], buf[at + o + 1]]);
        let u32_at = |o: usize| {
            u32::from_le_bytes([buf[at + o], buf[at + o + 1], buf[at + o + 2], buf[at + o + 3]])
        };
        let geom_type = buf[at + 4];
        if !matches!(geom_type, GEOM_LINE | GEOM_POLYGON | GEOM_POINT) {
            return err(format!("a .mamaps feature has geometry type {geom_type}, which this format does not carry"));
        }
        let flags = buf[at + 5];
        if flags & !KNOWN_FEATURE_FLAGS != 0 {
            return err("a .mamaps feature sets unknown flags");
        }
        let feature = Feature {
            kind: u16_at(0),
            kind_detail: u16_at(2),
            geom_type,
            flags,
            name_idx: u16_at(6),
            parts_offset: u32_at(8),
            part_count: u32_at(12),
            transit_color: u32_at(16),
            transit_ordinal: buf[at + 20],
            transit_lanes: buf[at + 21],
            transit_taper: buf[at + 22],
            lane_count: buf[at + 23],
        };
        if feature.part_count == 0 {
            return err("a .mamaps feature has no geometry");
        }
        let end = (feature.parts_offset as usize)
            .checked_add(feature.part_count as usize)
            .ok_or_else(|| crate::proto::Error("a .mamaps feature's parts overflow".to_string()))?;
        parts_needed = parts_needed.max(end);
        features.push(feature);
    }

    let parts_at = features_len;
    let parts_len = parts_needed * PART_ENTRY_LEN;
    if parts_at + parts_len > buf.len() {
        return err("a .mamaps layer's parts run past its payload");
    }
    let mut parts = Vec::with_capacity(parts_needed);
    let mut coords_needed = 0usize;
    for i in 0..parts_needed {
        let at = parts_at + i * PART_ENTRY_LEN;
        let u32_at = |o: usize| {
            u32::from_le_bytes([buf[at + o], buf[at + o + 1], buf[at + o + 2], buf[at + o + 3]])
        };
        let winding = u16::from_le_bytes([buf[at + 8], buf[at + 9]]);
        if !matches!(winding, WINDING_OUTER | WINDING_HOLE) {
            return err(format!("a .mamaps part has winding {winding}, which is neither outer nor hole"));
        }
        if u16::from_le_bytes([buf[at + 10], buf[at + 11]]) != 0 {
            return err("a .mamaps part has a non-zero reserved half-word");
        }
        let part = Part { coord_start: u32_at(0), point_count: u32_at(4), winding };
        let end = (part.coord_start as usize)
            .checked_add(part.point_count as usize)
            .ok_or_else(|| crate::proto::Error("a .mamaps part's coordinates overflow".to_string()))?;
        coords_needed = coords_needed.max(end);
        parts.push(part);
    }

    let coords_at = align4(parts_at + parts_len);
    if coords_at > buf.len() {
        return err("a .mamaps layer's coordinate arena starts past its payload");
    }
    // Walked in parts-table order, because a varint arena has no random access: each part's points
    // are deltas from the one before it, restarting at every part.
    let mut coords = Vec::with_capacity(coords_needed);
    let mut reader = crate::proto::Reader::new(&buf[coords_at..]);
    for part in &parts {
        if part.coord_start as usize != coords.len() {
            return err(format!(
                "a .mamaps part claims to start at point {} but the arena is {} points in",
                part.coord_start,
                coords.len(),
            ));
        }
        let (mut x, mut y) = (0i32, 0i32);
        for _ in 0..part.point_count {
            x += zigzag(reader.uvarint()?);
            y += zigzag(reader.uvarint()?);
            let (Ok(x), Ok(y)) = (i16::try_from(x), i16::try_from(y)) else {
                return err("a .mamaps coordinate delta walks outside the extent an i16 holds");
            };
            coords.push((x, y));
        }
    }
    if coords.len() != coords_needed {
        return err(format!(
            "a .mamaps layer decoded {} points where its parts want {coords_needed}",
            coords.len(),
        ));
    }
    Ok(Layer { layer_id, features, parts, coords })
}

/// v10 payload: narrowed per-layer records, 4-byte parts, shared arena.
///
/// The class is a pure function of the layer id (see
/// `emit_extra::record_class`), so the reader derives the same layout the
/// writer used without a wire bit. Dropped fields read back as their defaults
/// (zero names/details/transit/lanes), and `coord_start` is reconstructed by
/// the arena walk — v10 parts are validated contiguous exactly as v8 requires.
pub(crate) fn parse_layer_v10(layer_id: u8, feature_count: usize, buf: &[u8]) -> Result<Layer> {
    use super::consts::{FEATURE_RECORD_MIN_LEN, FEATURE_RECORD_ROAD_LEN, PART_ENTRY_V10_LEN};
    use super::emit_extra::{RecordClass, is_mixed_layer, record_class};
    let class = record_class(layer_id);
    let mixed = is_mixed_layer(layer_id);
    // Uniform layers stride fixed-width records; mixed layers walk a tag
    // byte per record (0 = Min, 1 = Road) so named lines keep their names.
    let mut features = Vec::with_capacity(feature_count);
    let mut parts_needed = 0usize;
    let mut at = 0usize;
    for _ in 0..feature_count {
        let feature_class = if mixed {
            if at + 1 > buf.len() {
                return err("a .mamaps mixed-layer record ends inside its class tag");
            }
            let wide = match buf[at] {
                0 => false,
                1 => true,
                t => {
                    return err(format!(
                        "a .mamaps mixed-layer record has class tag {t}, which this format does not carry"
                    ));
                }
            };
            at += 1;
            if wide {
                RecordClass::Road
            } else {
                RecordClass::Min
            }
        } else {
            class
        };
        let reclen = match feature_class {
            RecordClass::Min => FEATURE_RECORD_MIN_LEN,
            RecordClass::Road => FEATURE_RECORD_ROAD_LEN,
            RecordClass::Full => FEATURE_RECORD_LEN,
        };
        if at + reclen > buf.len() {
            return err("a .mamaps layer's features run past its payload");
        }
        let u16_at = |o: usize| u16::from_le_bytes([buf[at + o], buf[at + o + 1]]);
        let u32_at = |o: usize| {
            u32::from_le_bytes([buf[at + o], buf[at + o + 1], buf[at + o + 2], buf[at + o + 3]])
        };
        // Field offsets within the class. Min: kind(0) geom(2) flags(3)
        // off(4) cnt(8). Road adds detail(2 in v8 order...) — laid out in
        // emit order: kind, [detail,] geom, flags, [name,] off, cnt, [lane].
        let (kind_detail, name_idx, lane_count, transit);
        let (geom_type, flags, parts_offset, part_count);
        match feature_class {
            RecordClass::Min => {
                kind_detail = 0;
                name_idx = NAME_NONE;
                lane_count = 0;
                transit = (0u32, 0u8, 0u8, 0u8);
                geom_type = buf[at + 2];
                flags = buf[at + 3];
                parts_offset = u32_at(4);
                part_count = u32_at(8);
            }
            RecordClass::Road => {
                kind_detail = u16_at(2);
                name_idx = u16_at(6);
                lane_count = buf[at + 16];
                transit = (0u32, 0u8, 0u8, 0u8);
                geom_type = buf[at + 4];
                flags = buf[at + 5];
                parts_offset = u32_at(8);
                part_count = u32_at(12);
            }
            RecordClass::Full => {
                kind_detail = u16_at(2);
                name_idx = u16_at(6);
                lane_count = buf[at + 23];
                transit = (u32_at(16), buf[at + 20], buf[at + 21], buf[at + 22]);
                geom_type = buf[at + 4];
                flags = buf[at + 5];
                parts_offset = u32_at(8);
                part_count = u32_at(12);
            }
        }
        if !matches!(geom_type, GEOM_LINE | GEOM_POLYGON | GEOM_POINT) {
            return err(format!("a .mamaps feature has geometry type {geom_type}, which this format does not carry"));
        }
        if flags & !KNOWN_FEATURE_FLAGS != 0 {
            return err("a .mamaps feature sets unknown flags");
        }
        let feature = Feature {
            kind: u16_at(0),
            kind_detail,
            geom_type,
            flags,
            name_idx,
            parts_offset,
            part_count,
            transit_color: transit.0,
            transit_ordinal: transit.1,
            transit_lanes: transit.2,
            transit_taper: transit.3,
            lane_count,
        };
        if feature.part_count == 0 {
            return err("a .mamaps feature has no geometry");
        }
        let end = (feature.parts_offset as usize)
            .checked_add(feature.part_count as usize)
            .ok_or_else(|| crate::proto::Error("a .mamaps feature's parts overflow".to_string()))?;
        parts_needed = parts_needed.max(end);
        features.push(feature);
        at += reclen;
    }

    let parts_at = at;
    let parts_len = parts_needed * PART_ENTRY_V10_LEN;
    if parts_at + parts_len > buf.len() {
        return err("a .mamaps layer's parts run past its payload");
    }
    let mut parts = Vec::with_capacity(parts_needed);
    let mut coords_needed = 0usize;
    for i in 0..parts_needed {
        let at = parts_at + i * PART_ENTRY_V10_LEN;
        let point_count = u32::from(u16::from_le_bytes([buf[at], buf[at + 1]]));
        let pflags = buf[at + 2];
        if pflags & !0x01 != 0 {
            return err(format!("a .mamaps part sets unknown flags {pflags:#04x}"));
        }
        if buf[at + 3] != 0 {
            return err("a .mamaps part has a non-zero reserved byte");
        }
        let winding = if pflags & 0x01 != 0 {
            WINDING_HOLE
        } else {
            WINDING_OUTER
        };
        // coord_start is reconstructed: v10 parts are contiguous by
        // construction (the writer validates), so the Nth part starts where
        // the arena walk ends.
        let part = Part { coord_start: coords_needed as u32, point_count, winding };
        let end = (part.coord_start as usize)
            .checked_add(part.point_count as usize)
            .ok_or_else(|| crate::proto::Error("a .mamaps part's coordinates overflow".to_string()))?;
        coords_needed = coords_needed.max(end);
        parts.push(part);
    }

    let coords_at = align4(parts_at + parts_len);
    if coords_at > buf.len() {
        return err("a .mamaps layer's coordinate arena starts past its payload");
    }
    // Identical arena walk to v8: varint deltas restarting at each part.
    let mut coords = Vec::with_capacity(coords_needed);
    let mut reader = crate::proto::Reader::new(&buf[coords_at..]);
    for part in &parts {
        if part.coord_start as usize != coords.len() {
            return err(format!(
                "a .mamaps part claims to start at point {} but the arena is {} points in",
                part.coord_start,
                coords.len(),
            ));
        }
        let (mut x, mut y) = (0i32, 0i32);
        for _ in 0..part.point_count {
            x += zigzag(reader.uvarint()?);
            y += zigzag(reader.uvarint()?);
            let (Ok(x), Ok(y)) = (i16::try_from(x), i16::try_from(y)) else {
                return err("a .mamaps coordinate delta walks outside the extent an i16 holds");
            };
            coords.push((x, y));
        }
    }
    if coords.len() != coords_needed {
        return err(format!(
            "a .mamaps layer decoded {} points where its parts want {coords_needed}",
            coords.len(),
        ));
    }
    Ok(Layer { layer_id, features, parts, coords })
}

fn zigzag(v: u64) -> i32 {
    crate::proto::zigzag_decode(v) as i32
}
