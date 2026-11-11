// --- pool-built records ------------------------------------------------------------------------
//
// The materialise drains used to build every spill record on the one serial thread: the props
// list (a `String` per key), `encode_props`, the record header, and -- per 64 features -- a DEFLATE
// frame. On California that was 19.0 s of a 26.6 s `materialise-ways`; on the planet, ~1.1 B ways,
// it was the bulk of a 30-40 minute single-core phase. Everything up to the record bytes is a pure
// function of one feature, so it now runs on the pool ([`PreRecord`]) and the serial thread only
// appends ([`Sink::push_pre`]); the frames compress in batches on the pool inside the writer.
//
// The props builders below are the bodies `push_named` / `push_road` / `push_building` always had,
// moved out so both paths run the very same code. Same props, same `encode_props`, same header:
// the same bytes.

/// `push_named`'s props: the packed class, then the name and the tagged id when present.
fn fill_named(
    props: &mut Vec<(String, Value)>,
    class: &Class,
    name: Option<&str>,
    id: u64,
) -> Result<()> {
    props[0].1 = Value::Uint(pack(class)?);
    props.truncate(1);
    if let Some(name) = name.filter(|n| !n.is_empty()) {
        props.push((NAME_KEY.to_string(), Value::String(name.to_string())));
    }
    if id != tilecodec::mamaps::body::ID_NONE {
        if !crate::extract::tracks_ids(class) {
            return err(format!(
                "layer {} carries a feature id, which only places, poi and a boundaries region shape may",
                class.layer,
            ));
        }
        props.push((ID_KEY.to_string(), Value::Uint(id)));
    }
    Ok(())
}

/// `push_road`'s props, including its refusal of a road with nothing to carry.
fn fill_road(
    props: &mut Vec<(String, Value)>,
    class: &Class,
    name: Option<&str>,
    lane_count: u8,
    turn_fwd: &[u16],
    turn_bwd: &[u16],
    carriageway: Carriageway,
) -> Result<()> {
    if name.is_none()
        && lane_count == 0
        && turn_fwd.is_empty()
        && turn_bwd.is_empty()
        && carriageway.is_empty()
    {
        return err("a road pushed with no lane data".to_string());
    }
    props[0].1 = Value::Uint(pack(class)?);
    props.truncate(1);
    if let Some(name) = name.filter(|n| !n.is_empty()) {
        props.push((NAME_KEY.to_string(), Value::String(name.to_string())));
    }
    if lane_count > 0 {
        props.push((LANE_COUNT_KEY.to_string(), Value::Uint(lane_count as u64)));
    }
    if !turn_fwd.is_empty() {
        props.push((TURN_FWD_KEY.to_string(), Value::String(osm_ingest::roads::pack_lanes(turn_fwd))));
    }
    if !turn_bwd.is_empty() {
        props.push((TURN_BWD_KEY.to_string(), Value::String(osm_ingest::roads::pack_lanes(turn_bwd))));
    }
    if !carriageway.is_empty() {
        props.push((CARRIAGEWAY_KEY.to_string(), Value::Uint(pack_carriageway(carriageway))));
    }
    Ok(())
}

/// `push_building`'s props: class, optional name, then the three packed S3DB words.
fn fill_building(
    props: &mut Vec<(String, Value)>,
    class: &Class,
    name: Option<&str>,
    attrs: BuildingAttrs,
) -> Result<()> {
    props[0].1 = Value::Uint(pack(class)?);
    props.truncate(1);
    if let Some(name) = name.filter(|n| !n.is_empty()) {
        props.push((NAME_KEY.to_string(), Value::String(name.to_string())));
    }
    let (a, b, c) = pack_building(&attrs);
    props.push((BLD_A_KEY.to_string(), Value::Uint(a)));
    props.push((BLD_B_KEY.to_string(), Value::Uint(b)));
    props.push((BLD_C_KEY.to_string(), Value::Uint(c)));
    Ok(())
}

/// One feature's spill record, built off the serial thread.
///
/// Carries what [`Sink::write_record`] needs beyond the bytes -- the geometry kind and bounds for
/// the writer's summary, the e7 box for the sink's bbox, the class's `min_zoom` for the chunk
/// index -- so [`Sink::push_pre`] repeats `write_record`'s bookkeeping exactly.
pub struct PreRecord {
    rec: Vec<u8>,
    kind: GeomKind,
    bounds: Option<Rect>,
    ebbx: Option<(i32, i32, i32, i32)>,
    min_zoom: u8,
}

impl PreRecord {
    /// What [`Sink::push_named`] would stage.
    pub fn named(
        class: &Class,
        geometry: &EncodedGeom,
        name: Option<&str>,
        id: u64,
    ) -> Result<PreRecord> {
        let mut props = vec![(CLASS_KEY.to_string(), Value::Uint(0))];
        fill_named(&mut props, class, name, id)?;
        Self::build(class, geometry, &props)
    }

    /// What [`Sink::push_road`] would stage.
    #[allow(clippy::too_many_arguments)]
    pub fn road(
        class: &Class,
        geometry: &EncodedGeom,
        name: Option<&str>,
        lane_count: u8,
        turn_fwd: &[u16],
        turn_bwd: &[u16],
        carriageway: Carriageway,
    ) -> Result<PreRecord> {
        let mut props = vec![(CLASS_KEY.to_string(), Value::Uint(0))];
        fill_road(&mut props, class, name, lane_count, turn_fwd, turn_bwd, carriageway)?;
        Self::build(class, geometry, &props)
    }

    /// What [`Sink::push_building`] would stage.
    pub fn building(
        class: &Class,
        geometry: &EncodedGeom,
        name: Option<&str>,
        attrs: BuildingAttrs,
    ) -> Result<PreRecord> {
        let mut props = vec![(CLASS_KEY.to_string(), Value::Uint(0))];
        fill_building(&mut props, class, name, attrs)?;
        Self::build(class, geometry, &props)
    }

    fn build(class: &Class, geometry: &EncodedGeom, props: &[(String, Value)]) -> Result<PreRecord> {
        let mut rec = Vec::with_capacity(64 + geometry.bytes.len());
        tile_build::spill::encode_normalized_record(geometry.kind, &geometry.bytes, props, &mut rec)
            .map_err(|e| osm_ingest::proto::Error(e.to_string()))?;
        Ok(PreRecord {
            rec,
            kind: geometry.kind,
            bounds: geometry.bounds,
            ebbx: geometry.ebbx,
            min_zoom: class.min_zoom,
        })
    }
}

/// Many pool-built records in one buffer, for [`Sink::push_run`]: what the materialise drains
/// used to push one `PreRecord` at a time. Built on the pool (one per ways block, per junction
/// group), so the serial thread does one call per run instead of per-record bookkeeping -- on the
/// planet ~1.1 B records, which was the floor under `materialise-ways` (195 s, all of it drain).
#[derive(Default)]
pub struct RecordRun {
    bytes: Vec<u8>,
    ends: Vec<usize>,
    min_zoom: Vec<u8>,
    first_kind: Option<GeomKind>,
    bounds: Option<Rect>,
    ebbx: Option<(i32, i32, i32, i32)>,
}

impl RecordRun {
    /// Append one record, folding its facts exactly as `Sink::push_pre` would one at a time.
    pub fn add(&mut self, record: PreRecord) {
        if self.bytes.is_empty() && self.ends.is_empty() {
            self.bytes = record.rec;
        } else {
            self.bytes.extend_from_slice(&record.rec);
        }
        self.ends.push(self.bytes.len());
        self.min_zoom.push(record.min_zoom);
        if self.first_kind.is_none() {
            self.first_kind = Some(record.kind);
        }
        self.bounds = match (self.bounds, record.bounds) {
            (None, b) => b,
            (a, None) => a,
            (Some(a), Some(b)) => Some(Rect {
                min_x: a.min_x.min(b.min_x),
                min_y: a.min_y.min(b.min_y),
                max_x: a.max_x.max(b.max_x),
                max_y: a.max_y.max(b.max_y),
            }),
        };
        if let Some((x0, y0, x1, y1)) = record.ebbx {
            self.ebbx = Some(match self.ebbx {
                None => (x0, y0, x1, y1),
                Some(e) => (e.0.min(x0), e.1.min(y0), e.2.max(x1), e.3.max(y1)),
            });
        }
    }

    pub fn len(&self) -> usize {
        self.ends.len()
    }
}

impl Sink {
    /// Append a whole [`RecordRun`]: the same writer bytes, chunk minimums, count and bounding box
    /// as `push_pre` on each of its records in order. The chunk minimums close on the same
    /// 64-record boundaries; the box and the summary folds are min/max, so folding a run first
    /// changes nothing.
    pub fn push_run(&mut self, run: RecordRun) -> Result<()> {
        let RecordRun { bytes, ends, min_zoom, first_kind, bounds, ebbx } = run;
        if ends.is_empty() {
            return Ok(());
        }
        self.writer
            .push_run(bytes, &ends, first_kind, bounds)
            .map_err(|e| osm_ingest::proto::Error(e.to_string()))?;
        let per = NORM_CHUNK_FEATURES as usize;
        let mut rest = &min_zoom[..];
        while !rest.is_empty() {
            let room = per - (self.count % NORM_CHUNK_FEATURES) as usize;
            let take = room.min(rest.len());
            let m = rest[..take].iter().copied().min().expect("non-empty");
            self.filling = self.filling.min(m);
            self.count += take as u64;
            if self.count % NORM_CHUNK_FEATURES == 0 {
                self.chunk_mins.push(self.filling);
                self.filling = u8::MAX;
            }
            rest = &rest[take..];
        }
        self.grow_bounds(ebbx);
        Ok(())
    }

    /// Append a pool-built record: [`write_record`](Self::write_record) with the encoding already
    /// done. Same writer call, same chunk-min, count and bbox bookkeeping, in the same order.
    pub fn push_pre(&mut self, record: &PreRecord) -> Result<()> {
        self.writer
            .push_record(record.kind, &record.rec, record.bounds)
            .map_err(|e| osm_ingest::proto::Error(e.to_string()))?;
        self.filling = self.filling.min(record.min_zoom);
        self.count += 1;
        if self.count % NORM_CHUNK_FEATURES == 0 {
            self.chunk_mins.push(self.filling);
            self.filling = u8::MAX;
        }
        self.grow_bounds(record.ebbx);
        Ok(())
    }
}

// --- the ways spill, in blocks ---------------------------------------------------------------------
//
// The ways spill is one varint stream whose ids are delta-coded from record to record, so it could
// only ever be read by one cursor -- and it is read twice (lane inheritance, materialise), 1.1 B
// records on the planet, with every way decoded on one thread: 6 min and 30-40 min phases running
// at 4-6 of 192 cores. And it was *written* by pass 1's serial drain, record by record, which is
// what held pass 1 to ~4 cores for 4m40s.
//
// The stream is unchanged, byte for byte. What is new is an index over it: every
// [`WAY_BLOCK_WAYS`]-ish run of records is a [`WayBlockLoc`] -- its byte range plus the id the
// first record's delta is taken from -- so any run decodes on its own. Pass 1's workers encode
// their chunk's ways into [`WayBlock`]s (the record bytes with the first id left open), the drain
// only patches that one varint and appends, and the two readers decode blocks across the pool
// ([`WayBlocks`]) and consume them in block order, which is spill order.

/// Ways per index entry, at most. Small enough that a planet pass decodes ~280 k blocks across the
/// pool with even load; large enough that the index is a few MB.
pub const WAY_BLOCK_WAYS: u64 = 4096;

/// One run of the ways spill that decodes on its own.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WayBlockLoc {
    /// Byte offset of the run's first record in the spill.
    offset: u64,
    /// Bytes in the run.
    len: u64,
    /// The id the first record's delta is taken from: the previous record's id, or zero.
    start_last_id: i64,
    /// Records in the run.
    ways: u64,
}

impl WayBlockLoc {
    pub fn ways(&self) -> u64 {
        self.ways
    }
}

/// A run of way records encoded off the serial thread, for [`WaySink::push_block`].
///
/// Exactly the bytes [`WaySink::push`] would write for the same ways, except that the first
/// record's id delta is left open (it is relative to whatever the sink saw last, which a worker
/// cannot know): the block stores that varint as the absolute id and remembers its length, and the
/// sink re-encodes just that varint.
pub struct WayBlock {
    bytes: Vec<u8>,
    /// Length of the first record's id varint at the head of `bytes`.
    head_len: usize,
    first_id: i64,
    last_id: i64,
    count: u64,
    refs: u64,
    max_ref: i64,
}

impl Default for WayBlock {
    fn default() -> Self {
        Self::new()
    }
}

impl WayBlock {
    pub fn new() -> WayBlock {
        WayBlock { bytes: Vec::new(), head_len: 0, first_id: 0, last_id: 0, count: 0, refs: 0, max_ref: 0 }
    }

    /// Ways encoded so far.
    pub fn len(&self) -> u64 {
        self.count
    }

    #[allow(dead_code)]
    pub fn is_empty(&self) -> bool {
        self.count == 0
    }

    /// Encode one way. Same arguments, same ordering rule and same record bytes as
    /// [`WaySink::push`].
    #[allow(clippy::too_many_arguments)]
    pub fn push(
        &mut self,
        id: i64,
        class: &Class,
        refs: &[i64],
        name: Option<&str>,
        lane_count: u8,
        turn_fwd: &[u16],
        turn_bwd: &[u16],
        carriageway: Carriageway,
        building: Option<BuildingAttrs>,
    ) -> Result<()> {
        if self.count > 0 && id <= self.last_id {
            return err(unsorted_ways(id, self.last_id));
        }
        if self.count == 0 {
            put_svarint(&mut self.bytes, id);
            self.head_len = self.bytes.len();
            self.first_id = id;
        } else {
            put_svarint(&mut self.bytes, id.wrapping_sub(self.last_id));
        }
        let max_ref = encode_way_tail(
            &mut self.bytes,
            class,
            refs,
            name,
            lane_count,
            turn_fwd,
            turn_bwd,
            carriageway,
            building,
        )?;
        self.max_ref = self.max_ref.max(max_ref);
        self.last_id = id;
        self.count += 1;
        self.refs += refs.len() as u64;
        Ok(())
    }
}

/// The error a non-advancing way id gets, from the sink and from a block alike.
fn unsorted_ways(id: i64, last_id: i64) -> String {
    format!(
        "way {id} arrived after way {last_id}, so this PBF is not sorted by way id and the \
         sequential ways spill cannot be used for it",
    )
}

/// Everything in a way record after its id varint (see [`WaySink`]'s table), appended to `out`.
/// Returns the largest ref, or zero for none -- the fold `WaySink` keeps.
#[allow(clippy::too_many_arguments)]
fn encode_way_tail(
    out: &mut Vec<u8>,
    class: &Class,
    refs: &[i64],
    name: Option<&str>,
    lane_count: u8,
    turn_fwd: &[u16],
    turn_bwd: &[u16],
    carriageway: Carriageway,
    building: Option<BuildingAttrs>,
) -> Result<i64> {
    put_uvarint(out, pack(class)?);
    put_uvarint(out, refs.len() as u64);
    let mut previous: i64 = 0;
    let mut max_ref: i64 = 0;
    for &node in refs {
        put_svarint(out, node.wrapping_sub(previous));
        previous = node;
        max_ref = max_ref.max(node);
    }
    match name.filter(|n| !n.is_empty()) {
        Some(name) => {
            put_uvarint(out, name.len() as u64);
            out.extend_from_slice(name.as_bytes());
        }
        None => put_uvarint(out, 0),
    }
    put_uvarint(out, lane_count as u64);
    // The turn masks: a count then each u16, forward then backward. Zero counts for the
    // overwhelming majority, which cost one byte each.
    for masks in [turn_fwd, turn_bwd] {
        put_uvarint(out, masks.len() as u64);
        for &m in masks {
            put_uvarint(out, m as u64);
        }
    }
    // The directional split, as one packed varint. Zero -- one byte -- when never surveyed.
    put_uvarint(out, pack_carriageway(carriageway));
    // The building attributes: a presence byte, then the three packed words when present. A
    // non-building writes one zero byte; a building -- even one with no S3DB tags -- writes its
    // (usually all-zero) words so the tiler's side table stays dense-parallel to the layer.
    match building {
        Some(attrs) => {
            put_uvarint(out, 1);
            let (a, b, c) = pack_building(&attrs);
            put_uvarint(out, a);
            put_uvarint(out, b);
            put_uvarint(out, c);
        }
        None => put_uvarint(out, 0),
    }
    Ok(max_ref)
}

/// The sealed ways spill as independently decodable blocks. Shared read-only across the pool:
/// every [`reader`](Self::reader) call is a positional read of one block.
pub struct WayBlocks {
    file: Option<File>,
    anon: Option<std::sync::Arc<tile_build::anon::AnonStore>>,
    locs: Vec<WayBlockLoc>,
}

impl WayBlocks {
    /// Open whichever backend the sink sealed -- the shared anon store when present, else the
    /// file at `path` -- with the index [`WaySink`] returned in its [`WayCounts`].
    pub fn open(
        path: &Path,
        anon: &Option<std::sync::Arc<tile_build::anon::AnonStore>>,
        locs: Vec<WayBlockLoc>,
    ) -> Result<WayBlocks> {
        let file = match anon {
            Some(_) => None,
            None => Some(
                File::open(path).map_err(|e| Error(format!("cannot open {}: {e}", path.display())))?,
            ),
        };
        Ok(WayBlocks { file, anon: anon.clone(), locs })
    }

    pub fn locs(&self) -> &[WayBlockLoc] {
        &self.locs
    }

    /// A cursor over block `i` alone, positioned on its first record with the id its first delta
    /// is taken from. Yields exactly the records [`WayReader::open`] yields over that byte range.
    pub fn reader(&self, i: usize) -> Result<WayReader> {
        let loc = self.locs[i];
        let len = usize::try_from(loc.len)
            .map_err(|_| Error("a ways spill block does not fit in memory".to_string()))?;
        let mut buf = vec![0u8; len];
        match (&self.anon, &self.file) {
            (Some(anon), _) => anon
                .read_at(loc.offset, &mut buf)
                .map_err(|e| Error(format!("cannot read the anon ways spill: {e}")))?,
            (None, Some(file)) => read_exact_at(file, &mut buf, loc.offset)
                .map_err(|e| Error(format!("cannot read the ways spill: {e}")))?,
            (None, None) => return err("a ways spill with no backend".to_string()),
        }
        Ok(WayReader {
            inner: WayIn::Slice { buf, used: 0 },
            last_id: loc.start_last_id,
            seen: 0,
        })
    }
}

/// Positional read of exactly `buf.len()` bytes, safe to call from many threads on one `File`.
fn read_exact_at(file: &File, mut buf: &mut [u8], mut offset: u64) -> std::io::Result<()> {
    while !buf.is_empty() {
        #[cfg(windows)]
        let n = std::os::windows::fs::FileExt::seek_read(file, buf, offset)?;
        #[cfg(unix)]
        let n = std::os::unix::fs::FileExt::read_at(file, buf, offset)?;
        if n == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "the ways spill ended inside a block",
            ));
        }
        buf = &mut buf[n..];
        offset += n as u64;
    }
    Ok(())
}

#[cfg(test)]
mod way_block_tests {
    use super::*;

    fn class(layer: u8, kind: u16) -> Class {
        Class { layer, kind, kind_detail: 0, flags: 0, area: false, min_zoom: 12, min_area_px: 0.0 }
    }

    /// Pushing ways one at a time and pushing the same ways as worker-built blocks must write the
    /// same bytes, report the same counts, and read back the same records both through the
    /// sequential cursor and block by block -- on both backends.
    #[test]
    fn blocks_write_the_same_stream_and_read_back_block_by_block() {
        let dir = std::env::temp_dir().join(format!("mamaps_wayblock_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let mut ways: Vec<(i64, Vec<i64>, Option<String>, u8, Vec<u16>)> = Vec::new();
        let mut id = 7i64;
        for i in 0..10_000i64 {
            id += 1 + (i % 5) * 1000;
            let refs: Vec<i64> = (0..(2 + i % 9)).map(|k| 1_000_000 + i * 3 + k * 17).collect();
            let name = (i % 7 == 0).then(|| format!("Road {i}"));
            ways.push((id, refs, name, (i % 4) as u8, if i % 11 == 0 { vec![1, 4] } else { vec![] }));
        }
        let c = class(tilecodec::mamaps::dict::LAYER_ROADS, 3);
        for anon in [false, true] {
            let one_path = dir.join(format!("one{anon}.tmp"));
            let blk_path = dir.join(format!("blk{anon}.tmp"));
            let plan = if anon { osm_ingest::mem::SpillPlan::Anon } else { osm_ingest::mem::SpillPlan::File };
            let mut one = WaySink::create_planned(&one_path, plan).unwrap();
            let mut blk = WaySink::create_planned(&blk_path, plan).unwrap();
            let mut block = WayBlock::new();
            for (n, (id, refs, name, lanes, turns)) in ways.iter().enumerate() {
                one.push(*id, &c, refs, name.as_deref(), *lanes, turns, &[], Carriageway::default(), None)
                    .unwrap();
                block
                    .push(*id, &c, refs, name.as_deref(), *lanes, turns, &[], Carriageway::default(), None)
                    .unwrap();
                // Uneven block sizes, including one-way blocks, to exercise coalescing.
                if n % 1237 == 0 || block.len() >= 3000 {
                    blk.push_block(&std::mem::take(&mut block)).unwrap();
                }
            }
            blk.push_block(&block).unwrap();
            let (c1, s1) = one.finish_either().unwrap();
            let (c2, s2) = blk.finish_either().unwrap();
            assert_eq!((c1.ways, c1.refs, c1.max_ref), (c2.ways, c2.refs, c2.max_ref));
            let bytes = |path: &Path, s: Option<&tile_build::anon::AnonStore>| -> Vec<u8> {
                match s {
                    Some(a) => {
                        let mut v = vec![0u8; a.len() as usize];
                        a.read_at(0, &mut v).unwrap();
                        v
                    }
                    None => std::fs::read(path).unwrap(),
                }
            };
            let s2 = s2.map(std::sync::Arc::new);
            let b1 = bytes(&one_path, s1.as_ref());
            let b2 = bytes(&blk_path, s2.as_deref());
            assert_eq!(b1, b2, "the block path must write the per-way stream byte for byte");
            for counts in [&c1, &c2] {
                assert!(counts.blocks.iter().all(|l| l.ways <= WAY_BLOCK_WAYS));
                assert_eq!(counts.blocks.iter().map(|l| l.ways).sum::<u64>(), ways.len() as u64);
            }
            // Block-by-block decode equals the sequential cursor, record for record.
            let blocks = WayBlocks::open(&blk_path, &s2, c2.blocks.clone()).unwrap();
            let mut seq = WayReader::open_either(&blk_path, &s2).unwrap();
            let (mut r1, mut r2) = (Vec::new(), Vec::new());
            for i in 0..blocks.locs().len() {
                let mut reader = blocks.reader(i).unwrap();
                let mut n = 0u64;
                while let Some(a) = reader.next(&mut r1).unwrap() {
                    let b = seq.next(&mut r2).unwrap().expect("sequential has as many records");
                    assert_eq!(a.0, b.0);
                    assert_eq!(a.2, b.2);
                    assert_eq!(a.3, b.3);
                    assert_eq!(a.4, b.4);
                    assert_eq!(r1, r2);
                    n += 1;
                }
                assert_eq!(n, blocks.locs()[i].ways);
            }
            assert!(seq.next(&mut r2).unwrap().is_none());
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The ordering rule holds across a block boundary, not only inside one.
    #[test]
    fn a_block_that_does_not_advance_is_refused() {
        let path = std::env::temp_dir().join(format!("mamaps_wayblock_order_{}.tmp", std::process::id()));
        let c = class(tilecodec::mamaps::dict::LAYER_ROADS, 3);
        let mut sink = WaySink::create_planned(&path, osm_ingest::mem::SpillPlan::Anon).unwrap();
        let mut a = WayBlock::new();
        a.push(10, &c, &[1, 2], None, 0, &[], &[], Carriageway::default(), None).unwrap();
        sink.push_block(&a).unwrap();
        let mut b = WayBlock::new();
        b.push(10, &c, &[1, 2], None, 0, &[], &[], Carriageway::default(), None).unwrap();
        assert!(sink.push_block(&b).is_err());
        assert!(a.push(9, &c, &[1, 2], None, 0, &[], &[], Carriageway::default(), None).is_err());
    }
}

// --- zoom decode for the parallel map reader --------------------------------------------------------

/// The staged features opened for positional, many-thread decoding: one handle shared by every
/// decode thread of [`crate::tiler`]'s map reader.
pub struct ZoomChunks {
    inner: NormalizedChunks,
}

impl Store {
    /// Open the staged bytes for [`ZoomChunks::decode`]. Same backend choice as every other reader.
    pub fn zoom_chunks(&self) -> Result<ZoomChunks> {
        Ok(ZoomChunks { inner: self.open_chunks()? })
    }
}

impl ZoomChunks {
    /// Spill chunk `chunk`'s features that zoom `z` draws, in file order, plus each one's vertex
    /// count. Exactly what a [`ZoomReader`] lane yields for that chunk -- the same `read_into`, the
    /// same `feature_of`, the same zoom window -- so a reader that concatenates these in wanted
    /// order sees the stream `reader_for_zoom` delivers.
    pub fn decode(
        &self,
        chunk: usize,
        z: u8,
        scratch: &mut Vec<u8>,
        records: &mut Vec<tile_build::spill::NormalizedFeature>,
    ) -> Result<(Vec<Feature>, Vec<usize>)> {
        self.inner
            .read_into(chunk, scratch, records)
            .map_err(|e| osm_ingest::proto::Error(e.to_string()))?;
        let mut features = Vec::with_capacity(records.len());
        let mut counts = Vec::with_capacity(records.len());
        for record in records.drain(..) {
            let feature = feature_of(record)?;
            // The zoom window: floor is the feature's own `min_zoom`, ceiling the layer cap
            // (`MAX_ZOOM_PER_LAYER`). The same test `ZoomReader`'s lanes apply.
            if z >= feature.class.min_zoom
                && z <= crate::schema::max_zoom_for_layer(feature.class.layer)
            {
                counts.push(geometry_vertices(&feature.geometry));
                features.push(feature);
            }
        }
        Ok((features, counts))
    }
}

/// Every vertex in a geometry, all parts and rings: the measure the map reader cuts chunks by.
pub fn geometry_vertices(geometry: &Geometry) -> usize {
    match geometry {
        Geometry::Points(points) => points.len(),
        Geometry::Lines(lines) => lines.iter().map(Vec::len).sum(),
        Geometry::Polygons(polygons) => polygons.iter().flatten().map(Vec::len).sum(),
    }
}

impl Store {
    /// Every feature in the store, in file order, passed through `keep` on the pool; the kept
    /// values come back in file order.
    ///
    /// The same features [`reader`](Self::reader)'s one sequential cursor yields -- spill chunk by
    /// spill chunk, each read with the same `read_into` and decoded with the same `feature_of` --
    /// but decoded across the pool. For whole-store scans that keep a sliver (the region
    /// fallback's boundary shapes and place labels): on the planet the one-cursor version decoded
    /// 1.35 B features on one thread for 8+ minutes.
    pub fn par_scan<T, F>(&self, keep: F) -> Result<Vec<T>>
    where
        T: Send,
        F: Fn(Feature) -> Option<T> + Sync,
    {
        use rayon::prelude::*;
        let chunks = self.open_chunks()?;
        let n = chunks.chunk_count();
        // Groups bound what is in flight (one `Vec` header per chunk) on a 21 M-chunk planet.
        const GROUP: usize = 1 << 16;
        let mut out = Vec::new();
        let mut start = 0usize;
        while start < n {
            let end = (start + GROUP).min(n);
            let results: Vec<Result<Vec<T>>> = tile_build::par::install(|| {
                (start..end)
                    .into_par_iter()
                    .map_init(
                        || (Vec::new(), Vec::new()),
                        |(scratch, records), i| -> Result<Vec<T>> {
                            chunks
                                .read_into(i, scratch, records)
                                .map_err(|e| osm_ingest::proto::Error(e.to_string()))?;
                            let mut kept = Vec::new();
                            for record in records.drain(..) {
                                if let Some(v) = keep(feature_of(record)?) {
                                    kept.push(v);
                                }
                            }
                            Ok(kept)
                        },
                    )
                    .collect()
            });
            for r in results {
                out.extend(r?);
            }
            start = end;
        }
        Ok(out)
    }
}
