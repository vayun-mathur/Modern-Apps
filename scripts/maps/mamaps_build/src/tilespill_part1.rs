impl ChunkReader<'_> {
    /// Skip one entry without decoding it, returning its key and merge cost.
    ///
    /// Byte-for-byte the same cursor movement as [`next`](Self::next) —
    /// header, fixed arenas, name lengths, turn counts, building and
    /// carriageway sections — minus every allocation, UTF-8 check and struct
    /// build. The range planner uses it to walk chunks cheaply (cost histogram
    /// plus boundary offsets); the merge workers then decode for real. A corrupt
    /// entry errors here exactly as `next` would surface it, so planning fails
    /// fast rather than handing workers a desynced stream.
    ///
    /// Never touches the spill's read counters: planning reads are not
    /// decoded entries, and `check_books` must see each entry counted once —
    /// by the worker that decodes it.
    pub fn skip_entry(&mut self) -> Result<Option<((u64, u8), u64)>> {
        if self.left == 0 {
            return Ok(None);
        }
        self.fill(ENTRY_HEADER_BYTES)?;
        let head: &[u8; ENTRY_HEADER_BYTES] = self.buf[self.used..self.used + ENTRY_HEADER_BYTES]
            .try_into()
            .expect("a header's worth of bytes");
        let header = entry_header(head)?;
        let fixed =
            payload_bytes(header.features, header.parts, header.coords, header.ids) as usize;
        self.fill(ENTRY_HEADER_BYTES + fixed)?;
        self.used += ENTRY_HEADER_BYTES + fixed;
        for _ in 0..header.names {
            self.fill(4)?;
            let len = u32::from_le_bytes(
                self.buf[self.used..self.used + 4].try_into().expect("4 bytes"),
            ) as usize;
            if len > 1 << 20 {
                return err("a tile chunk entry names a megabyte-plus string".to_string());
            }
            self.fill(4 + len)?;
            self.used += 4 + len;
        }
        if header.has_turns {
            for _ in 0..header.features {
                self.fill(2)?;
                let (fwd, bwd) =
                    (self.buf[self.used] as usize, self.buf[self.used + 1] as usize);
                let bytes = 2 + 2 * (fwd + bwd);
                self.fill(bytes)?;
                self.used += bytes;
            }
        }
        if header.has_buildings {
            let bytes = BUILDING_BYTES * header.features;
            self.fill(bytes)?;
            self.used += bytes;
        }
        if header.has_carriageways {
            let bytes = CARRIAGEWAY_RECORD_LEN * header.features;
            self.fill(bytes)?;
            self.used += bytes;
        }
        self.left -= 1;
        // Same cost unit the merge batches by (`tile_cost`): features plus
        // coordinates, the proxy the encode pass balances tasks by.
        let cost = header.features as u64 + header.coords as u64;
        Ok(Some(((header.tile, header.layer_id), cost)))
    }

    /// File offset of the next unread byte: what a range worker seeks to.
    ///
    /// `at` is the first byte not yet buffered, so the unread position is
    /// behind it by exactly the buffered-but-unconsumed span.
    pub fn position(&self) -> u64 {
        self.at - (self.buf.len() - self.used) as u64
    }

    /// The next entry's tile id without consuming it.
    ///
    /// A range worker seeks to a bucket edge (coarser than its tile bound),
    /// then advances past entries below the bound with [`skip_entry`](Self::skip_entry)
    /// — counted down from `left` like any consumed entry, uncounted in the
    /// spill books like any skip. `None` past the last entry (no trailing
    /// check here; [`next`](Self::next) owns that).
    pub fn peek_tile(&mut self) -> Result<Option<u64>> {
        if self.left == 0 {
            return Ok(None);
        }
        self.fill(ENTRY_HEADER_BYTES)?;
        let head: &[u8; ENTRY_HEADER_BYTES] = self.buf[self.used..self.used + ENTRY_HEADER_BYTES]
            .try_into()
            .expect("a header's worth of bytes");
        Ok(Some(entry_header(head)?.tile))
    }

    /// Reposition a reader at a planned offset with a planned entry count.
    ///
    /// The buffer is discarded (its bytes belong to another range now and
    /// will be re-read through the window); `end` is unchanged. `exact_end`
    /// says whether this segment runs to the chunk's last byte: only then
    /// does [`next`](Self::next) enforce the no-trailing-bytes check, since
    /// any other segment legitimately ends mid-chunk with the next range's
    /// bytes after it.
    pub fn seek_to(&mut self, offset: u64, remaining: u64, exact_end: bool) {
        self.buf.clear();
        self.used = 0;
        self.at = offset;
        self.left = remaining;
        self.exact_end = exact_end;
    }
    ///
    /// Fallible, and that matters: a truncated scratch file must fail the build rather than silently
    /// shorten the archive.
    #[allow(clippy::should_implement_trait)]
    pub fn next(&mut self) -> Result<Option<((u64, u8), ChunkEntry)>> {
        if self.left == 0 {
            if self.exact_end && (self.used < self.buf.len() || self.at < self.end) {
                return err(format!(
                    "a tile chunk in {} holds bytes past its last entry",
                    self.spill.path.display()
                ));
            }
            return Ok(None);
        }
        self.fill(ENTRY_HEADER_BYTES)?;
        let head: &[u8; ENTRY_HEADER_BYTES] = self.buf[self.used..self.used + ENTRY_HEADER_BYTES]
            .try_into()
            .expect("a header's worth of bytes");
        let header = entry_header(head)?;
        // The fixed arenas first: their length is a pure function of the header, so one fill
        // covers them. Names follow with inline lengths and are pulled one at a time — their
        // byte length is known only once the previous one is read.
        let fixed =
            payload_bytes(header.features, header.parts, header.coords, header.ids) as usize;
        self.fill(ENTRY_HEADER_BYTES + fixed)?;
        let body = self.used + ENTRY_HEADER_BYTES;
        let spare = self.spare.take();
        let mut entry = decode_fixed(&header, &self.buf[body..body + fixed], spare)?;
        self.used = body + fixed;
        // Counted, not differenced: `fill` compacts the buffer when it tops up, so a span across
        // two buffer positions is not the difference of two offsets.
        let mut consumed = ENTRY_HEADER_BYTES + fixed;
        for _ in 0..header.names {
            self.fill(4)?;
            let len = u32::from_le_bytes(
                self.buf[self.used..self.used + 4].try_into().expect("4 bytes"),
            ) as usize;
            if len > 1 << 20 {
                return err("a tile chunk entry names a megabyte-plus string".to_string());
            }
            self.fill(4 + len)?;
            let bytes = &self.buf[self.used + 4..self.used + 4 + len];
            entry.names.push(
                std::str::from_utf8(bytes)
                    .map_err(|_| Error("a tile chunk entry's name is not UTF-8".to_string()))?
                    .to_string(),
            );
            self.used += 4 + len;
            consumed += 4 + len;
        }
        // Every name index must land in the table just read: a dangling index would decode to
        // the wrong label on device with no other symptom.
        for feature in &entry.layer.features {
            if feature.name_idx as usize > entry.names.len() {
                return err("a tile chunk feature names past its entry's table".to_string());
            }
        }
        // The turn-lane section, after the names when the flag is set: one record per feature,
        // each a u8 forward count, u8 backward count, then that many u16 masks each.
        if header.has_turns {
            entry.turn_lanes.reserve(header.features);
            for _ in 0..header.features {
                self.fill(2)?;
                let (fwd, bwd) =
                    (self.buf[self.used] as usize, self.buf[self.used + 1] as usize);
                let bytes = 2 + 2 * (fwd + bwd);
                self.fill(bytes)?;
                let read = |off: usize, n: usize| -> Vec<u16> {
                    (0..n)
                        .map(|k| {
                            let o = self.used + off + k * 2;
                            u16::from_le_bytes([self.buf[o], self.buf[o + 1]])
                        })
                        .collect()
                };
                let forward = read(2, fwd);
                let backward = read(2 + 2 * fwd, bwd);
                entry.turn_lanes.push(tilecodec::mamaps::body::LaneTurns { forward, backward });
                self.used += bytes;
                consumed += bytes;
            }
        }
        // The building section, after the turn-lane section when the flag is set: one
        // BUILDING_BYTES record per feature, dense.
        if header.has_buildings {
            entry.buildings.reserve(header.features);
            for _ in 0..header.features {
                self.fill(BUILDING_BYTES)?;
                let o = self.used;
                let u16_at = |k: usize| u16::from_le_bytes([self.buf[o + k], self.buf[o + k + 1]]);
                let u32_at = |k: usize| {
                    u32::from_le_bytes([
                        self.buf[o + k],
                        self.buf[o + k + 1],
                        self.buf[o + k + 2],
                        self.buf[o + k + 3],
                    ])
                };
                entry.buildings.push(BuildingAttrs {
                    height: u16_at(0),
                    min_height: u16_at(2),
                    roof_height: u16_at(4),
                    roof_shape: self.buf[o + 6],
                    roof_direction: self.buf[o + 7],
                    roof_orientation: self.buf[o + 8],
                    building_colour: u32_at(9),
                    roof_colour: u32_at(13),
                });
                self.used += BUILDING_BYTES;
                consumed += BUILDING_BYTES;
            }
        }
        // The carriageway section, last, when the flag is set: one [`CARRIAGEWAY_RECORD_LEN`]
        // record per feature, dense.
        if header.has_carriageways {
            entry.carriageways.reserve(header.features);
            for _ in 0..header.features {
                self.fill(CARRIAGEWAY_RECORD_LEN)?;
                let o = self.used;
                entry.carriageways.push(Carriageway {
                    forward: self.buf[o],
                    backward: self.buf[o + 1],
                    solid_dividers: u32::from_le_bytes([
                        self.buf[o + 2],
                        self.buf[o + 3],
                        self.buf[o + 4],
                        self.buf[o + 5],
                    ]),
                });
                self.used += CARRIAGEWAY_RECORD_LEN;
                consumed += CARRIAGEWAY_RECORD_LEN;
            }
        }
        self.left -= 1;
        self.spill.read.fetch_add(consumed as u64, Ordering::Relaxed);
        self.spill.read_entries.fetch_add(1, Ordering::Relaxed);
        Ok(Some(((header.tile, header.layer_id), entry)))
    }

    /// Make at least `need` unread bytes available in `buf`.
    fn fill(&mut self, need: usize) -> Result<()> {
        if self.buf.len() - self.used >= need {
            return Ok(());
        }
        if self.used > 0 {
            self.buf.copy_within(self.used.., 0);
            let keep = self.buf.len() - self.used;
            self.buf.truncate(keep);
            self.used = 0;
        }
        // An entry larger than the window is read whole rather than in pieces: the decode wants it
        // contiguous, and one such entry is bounded by `MAX_ENTRY_BYTES`.
        let want = need.max(self.window) - self.buf.len();
        let take = want.min((self.end - self.at) as usize);
        if self.buf.len() + take < need {
            return err(format!(
                "a tile chunk in {} ends {} byte(s) into a {need}-byte read",
                self.spill.path.display(),
                self.buf.len() + take
            ));
        }
        let base = self.buf.len();
        self.buf.resize(base + take, 0);
        match (&self.spill.file, &self.spill.anon) {
            (Some(f), None) => {
                tilecodec::pmtiles::read_exact_at(f, &mut self.buf[base..], self.at)
                    .map_err(|e| Error(format!("reading {}: {e}", self.spill.path.display())))?;
            }
            (None, Some(a)) => {
                a.lock()
                    .expect("the anon tile chunk spill")
                    .read_at(self.at, &mut self.buf[base..])
                    .map_err(|e| Error(format!("reading {}: {e}", self.spill.path.display())))?;
            }
            _ => {
                return err(format!(
                    "a tile chunk spill for {} has no backend",
                    self.spill.path.display()
                ))
            }
        }
        self.at += take as u64;
        Ok(())
    }

    /// Take back an entry whose arenas the merge has drained, to reuse on the next decode.
    ///
    /// The merge hands this back the emptied [`ChunkEntry`] it just concatenated into its
    /// accumulator; [`Self::next`] then fills these buffers instead of allocating fresh ones. Every
    /// arena is cleared (so no stale value can leak into a later entry) but its capacity is kept,
    /// which is the whole point. A single slot is enough: the merge consumes one of a reader's
    /// entries and immediately decodes the next, so the buffers are back in hand exactly when the
    /// next decode wants them.
    pub fn recycle(&mut self, mut entry: ChunkEntry) {
        entry.layer.features.clear();
        entry.layer.parts.clear();
        entry.layer.coords.clear();
        entry.names.clear();
        entry.ids.clear();
        entry.turn_lanes.clear();
        entry.buildings.clear();
        entry.carriageways.clear();
        self.spare = Some(entry);
    }
}

/// How many bytes one entry encodes to, or an error when an arena is past what a `u32` addresses.
fn entry_bytes(entry: &ChunkEntry) -> Result<u64> {
    let layer = &entry.layer;
    let count = |what: &str, n: usize| -> Result<usize> {
        if n > u32::MAX as usize {
            return err(format!("a tile chunk entry holds {n} {what}, which no header can address"));
        }
        Ok(n)
    };
    let features = count("feature(s)", layer.features.len())?;
    let parts = count("part(s)", layer.parts.len())?;
    let coords = count("coordinate(s)", layer.coords.len())?;
    let ids = count("id(s)", entry.ids.len())?;
    let mut names_bytes = 0u64;
    for name in &entry.names {
        names_bytes += 4 + name.len() as u64;
    }
    let mut turns_bytes = 0u64;
    for turns in &entry.turn_lanes {
        turns_bytes += 2 + 2 * (turns.forward.len() as u64 + turns.backward.len() as u64);
    }
    // The building section: one fixed record per feature when present.
    let buildings_bytes = if entry.buildings.is_empty() {
        0
    } else {
        entry.buildings.len() as u64 * BUILDING_BYTES as u64
    };
    // And the carriageway section, likewise fixed-width and one per feature.
    let carriageways_bytes = if entry.carriageways.is_empty() {
        0
    } else {
        entry.carriageways.len() as u64 * CARRIAGEWAY_RECORD_LEN as u64
    };
    Ok(ENTRY_HEADER_BYTES as u64
        + payload_bytes(features, parts, coords, ids)
        + names_bytes
        + turns_bytes
        + buildings_bytes
        + carriageways_bytes)
}

/// The payload width implied by an entry's four counts. A pure function of the header, which is
/// what lets a reader validate before it allocates. Names ride after the fixed arenas and are
/// counted in the header's own `names` field.
fn payload_bytes(features: usize, parts: usize, coords: usize, ids: usize) -> u64 {
    features as u64 * FEATURE_BYTES as u64
        + parts as u64 * PART_BYTES as u64
        + coords as u64 * COORD_BYTES as u64
        + ids as u64 * ID_BYTES as u64
}

fn encode_entry(tile: u64, layer_id: u8, entry: &ChunkEntry, out: &mut Vec<u8>) {
    let layer = &entry.layer;
    let base = out.len();
    out.resize(base + ENTRY_HEADER_BYTES, 0);
    out[base..base + 8].copy_from_slice(&tile.to_le_bytes());
    out[base + 8..base + 12].copy_from_slice(&(layer.features.len() as u32).to_le_bytes());
    out[base + 12..base + 16].copy_from_slice(&(layer.parts.len() as u32).to_le_bytes());
    out[base + 16..base + 20].copy_from_slice(&(layer.coords.len() as u32).to_le_bytes());
    out[base + 20] = layer_id;
    // Byte 21 flags a turn-lane section after the names; byte 22 a building section after that;
    // byte 23 a carriageway section after that. The building section is `features` records (one per
    // feature, dense), each a BUILDING_BYTES packed [`BuildingAttrs`]. Present only for a
    // `buildings` tile that had S3DB attributes in it.
    //
    // The carriageway section is the same shape for the `roads` layer. It has to be here rather
    // than reconstructed later: a side table that never reaches the spill is dropped on every
    // build that spills a zoom, and the archive comes back with no lane markings and no error.
    let has_turns = !entry.turn_lanes.is_empty();
    let has_buildings = !entry.buildings.is_empty();
    let has_carriageways = !entry.carriageways.is_empty();
    out[base + 21] = has_turns as u8;
    out[base + 22] = has_buildings as u8;
    out[base + 23] = has_carriageways as u8;
    out[base + 24..base + 28].copy_from_slice(&(entry.names.len() as u32).to_le_bytes());
    out[base + 28..base + 32].copy_from_slice(&(entry.ids.len() as u32).to_le_bytes());
    for feature in &layer.features {
        // The spill carries the v2 index, not the v1 wire: name_idx, transit_color and the three
        // transit lane bytes ride the entry and are re-encoded by the body serializer, so the
        // scratch format never lags the codec by a version.
        out.extend_from_slice(&feature.kind.to_le_bytes());
        out.extend_from_slice(&feature.kind_detail.to_le_bytes());
        out.push(feature.geom_type);
        out.push(feature.flags);
        out.extend_from_slice(&feature.name_idx.to_le_bytes());
        out.extend_from_slice(&feature.parts_offset.to_le_bytes());
        out.extend_from_slice(&feature.part_count.to_le_bytes());
        out.extend_from_slice(&feature.transit_color.to_le_bytes());
        out.push(feature.transit_ordinal);
        out.push(feature.transit_lanes);
        out.push(feature.transit_taper);
        out.push(feature.lane_count);
    }
    for part in &layer.parts {
        out.extend_from_slice(&part.coord_start.to_le_bytes());
        out.extend_from_slice(&part.point_count.to_le_bytes());
        out.extend_from_slice(&part.winding.to_le_bytes());
    }
    for (x, y) in &layer.coords {
        out.extend_from_slice(&x.to_le_bytes());
        out.extend_from_slice(&y.to_le_bytes());
    }
    for id in &entry.ids {
        out.extend_from_slice(&id.to_le_bytes());
    }
    for name in &entry.names {
        out.extend_from_slice(&(name.len() as u32).to_le_bytes());
        out.extend_from_slice(name.as_bytes());
    }
    // The turn-lane section, after the names, when present. One record per feature, dense.
    if has_turns {
        for turns in &entry.turn_lanes {
            out.push(turns.forward.len() as u8);
            out.push(turns.backward.len() as u8);
            for m in turns.forward.iter().chain(&turns.backward) {
                out.extend_from_slice(&m.to_le_bytes());
            }
        }
    }
    // The building section, after the turn-lane section, when present. One record per feature.
    if has_buildings {
        for a in &entry.buildings {
            out.extend_from_slice(&a.height.to_le_bytes());
            out.extend_from_slice(&a.min_height.to_le_bytes());
            out.extend_from_slice(&a.roof_height.to_le_bytes());
            out.push(a.roof_shape);
            out.push(a.roof_direction);
            out.push(a.roof_orientation);
            out.extend_from_slice(&a.building_colour.to_le_bytes());
            out.extend_from_slice(&a.roof_colour.to_le_bytes());
        }
    }
    // The carriageway section, last, when present. One record per feature, in the same field order
    // the body's own [`CARRIAGEWAY_RECORD_LEN`] record uses.
    if has_carriageways {
        for c in &entry.carriageways {
            out.push(c.forward);
            out.push(c.backward);
            out.extend_from_slice(&c.solid_dividers.to_le_bytes());
        }
    }
}

struct EntryHeader {
    tile: u64,
    layer_id: u8,
    features: usize,
    parts: usize,
    coords: usize,
    names: usize,
    ids: usize,
    has_turns: bool,
    has_buildings: bool,
    has_carriageways: bool,
}

fn entry_header(head: &[u8; ENTRY_HEADER_BYTES]) -> Result<EntryHeader> {
    // Bytes 21 (turn-lane flag), 22 (building flag) and 23 (carriageway flag) are 0 or 1; anything
    // else means the reader is the wrong version for the file, because a newer writer would be
    // using the spare values. Guessing would decode a field that moved.
    if head[21] > 1 {
        return err("a tile chunk entry has an unknown turn-lane flag");
    }
    if head[22] > 1 {
        return err("a tile chunk entry has an unknown building flag");
    }
    if head[23] > 1 {
        return err("a tile chunk entry has an unknown carriageway flag");
    }
    let u32_at =
        |o: usize| u32::from_le_bytes(head[o..o + 4].try_into().expect("4 bytes")) as usize;
    let header = EntryHeader {
        tile: u64::from_le_bytes(head[0..8].try_into().expect("8 bytes")),
        features: u32_at(8),
        parts: u32_at(12),
        coords: u32_at(16),
        layer_id: head[20],
        names: u32_at(24),
        ids: u32_at(28),
        has_turns: head[21] == 1,
        has_buildings: head[22] == 1,
        has_carriageways: head[23] == 1,
    };
    // A layer either has an id per feature or none at all. Checked before the length so a garbled
    // count is refused as the desync it is rather than as a size that happens not to fit.
    if header.ids != 0 && header.ids != header.features {
        return err(format!(
            "a tile chunk entry has {} id(s) for {} feature(s)",
            header.ids, header.features,
        ));
    }
    let len = ENTRY_HEADER_BYTES as u64
        + payload_bytes(header.features, header.parts, header.coords, header.ids);
    if len > MAX_ENTRY_BYTES {
        return err(format!("a tile chunk entry is {len} byte(s), which is corruption"));
    }
    Ok(header)
}

/// Decode an entry's fixed arenas. `payload` is exactly the fixed bytes the header accounted
/// for; names are pulled separately by the reader, one inline length at a time.
///
/// `spare` is a previously-yielded entry the merge handed back, its arenas emptied but their
/// capacity kept. Reusing it turns four `Vec` allocations per entry into four `clear`/`reserve`
/// pairs on the serial merge thread. The decoded values are identical either way: every arena is
/// cleared before it is refilled, so nothing of the old entry survives.
fn decode_fixed(
    header: &EntryHeader,
    payload: &[u8],
    spare: Option<ChunkEntry>,
) -> Result<ChunkEntry> {
    let u16_at = |b: &[u8], o: usize| u16::from_le_bytes(b[o..o + 2].try_into().expect("2 bytes"));
    let u32_at = |b: &[u8], o: usize| u32::from_le_bytes(b[o..o + 4].try_into().expect("4 bytes"));
    let i16_at = |b: &[u8], o: usize| i16::from_le_bytes(b[o..o + 2].try_into().expect("2 bytes"));

    let mut entry = spare.unwrap_or_else(|| ChunkEntry::new(header.layer_id));
    entry.layer.layer_id = header.layer_id;
    entry.layer.features.clear();
    entry.layer.features.reserve(header.features);
    entry.layer.parts.clear();
    entry.layer.parts.reserve(header.parts);
    entry.layer.coords.clear();
    entry.layer.coords.reserve(header.coords);
    entry.ids.clear();
    entry.ids.reserve(header.ids);
    // The reader fills these; clear so a reused entry starts empty. Names are reserved here (the
    // count is in the header); the section arenas are reserved by the reader when their flag is set.
    entry.names.clear();
    entry.names.reserve(header.names);
    entry.turn_lanes.clear();
    entry.buildings.clear();
    entry.carriageways.clear();

    let mut at = 0usize;
    for _ in 0..header.features {
        let b = payload
            .get(at..at + FEATURE_BYTES)
            .ok_or_else(|| Error("a tile chunk entry's features run past its payload".to_string()))?;
        entry.layer.features.push(BodyFeature {
            kind: u16_at(b, 0),
            kind_detail: u16_at(b, 2),
            geom_type: b[4],
            flags: b[5],
            name_idx: u16_at(b, 6),
            parts_offset: u32_at(b, 8),
            part_count: u32_at(b, 12),
            transit_color: u32_at(b, 16),
            transit_ordinal: b[20],
            transit_lanes: b[21],
            transit_taper: b[22],
            lane_count: b[23],
        });
        at += FEATURE_BYTES;
    }
    for _ in 0..header.parts {
        let b = payload
            .get(at..at + PART_BYTES)
            .ok_or_else(|| Error("a tile chunk entry's parts run past its payload".to_string()))?;
        entry.layer.parts.push(Part {
            coord_start: u32_at(b, 0),
            point_count: u32_at(b, 4),
            winding: u16_at(b, 8),
        });
        at += PART_BYTES;
    }
    for _ in 0..header.coords {
        let b = payload
            .get(at..at + COORD_BYTES)
            .ok_or_else(|| Error("a tile chunk entry's coords run past its payload".to_string()))?;
        entry.layer.coords.push((i16_at(b, 0), i16_at(b, 2)));
        at += COORD_BYTES;
    }
    for _ in 0..header.ids {
        let b = payload
            .get(at..at + ID_BYTES)
            .ok_or_else(|| Error("a tile chunk entry's ids run past its payload".to_string()))?;
        entry.ids.push(u64::from_le_bytes(b.try_into().expect("8 bytes")));
        at += ID_BYTES;
    }
    debug_assert_eq!(at, payload.len(), "the fixed arenas must consume their payload exactly");
    Ok(entry)
}
