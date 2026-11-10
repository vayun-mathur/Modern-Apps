/// Sequential reader over one bucket.
///
/// Errors if a record's `tile_id` falls outside the bucket's range, and at the end if
/// the record or byte count disagrees with what the writer tallied. The order the
/// encode pass depends on is asserted directly by its own test;
/// [`crate::pmtiles::StreamBuilder::add_tile_raw`] refusing a non-ascending id is the
/// second line of defence, not the first.
pub struct BucketReader {
    src: BufReader<File>,
    path: PathBuf,
    lo: u64,
    hi: u64,
    expect_records: u64,
    expect_bytes: u64,
    seen_records: u64,
    seen_bytes: u64,
    done: bool,
    buf: Vec<u8>,
}

impl BucketReader {
    #[allow(clippy::should_implement_trait)]
    pub fn next(&mut self) -> Result<Option<SpillRecord>> {
        if self.done {
            return Ok(None);
        }
        let mut head = [0u8; REC_HEADER_BYTES];
        let mut read = 0usize;
        while read < REC_HEADER_BYTES {
            let n = self
                .src
                .read(&mut head[read..])
                .map_err(|e| Error(format!("reading {}: {e}", self.path.display())))?;
            if n == 0 {
                break;
            }
            read += n;
        }
        if read == 0 {
            self.done = true;
            self.check_totals()?;
            return Ok(None);
        }
        if read < REC_HEADER_BYTES {
            // A partial header is a truncated file, never a legitimate end.
            return err(format!(
                "{} ends {read} byte(s) into a {REC_HEADER_BYTES}-byte record header",
                self.path.display()
            ));
        }
        let h = RecHeader::parse(&head)?;
        if h.tile_id < self.lo || h.tile_id >= self.hi {
            return err(format!(
                "{} holds tile id {} but covers {}..{}",
                self.path.display(),
                h.tile_id,
                self.lo,
                self.hi
            ));
        }
        let payload = h.geom_len as usize + h.props_len as usize;
        self.buf.clear();
        self.buf.resize(payload, 0);
        self.src
            .read_exact(&mut self.buf)
            .map_err(|e| Error(format!("reading {}'s record payload: {e}", self.path.display())))?;
        let geom = decode_int_geometry(h.kind, &self.buf[..h.geom_len as usize])?;
        let props = decode_props(&self.buf[h.geom_len as usize..])?;

        self.seen_records += 1;
        self.seen_bytes += h.rec_len as u64;
        Ok(Some(SpillRecord {
            tile_id: h.tile_id,
            seq: h.seq,
            extent: h.extent,
            geom,
            props,
        }))
    }

    fn check_totals(&self) -> Result<()> {
        if self.seen_records != self.expect_records || self.seen_bytes != self.expect_bytes {
            return err(format!(
                "{} read back {} record(s)/{} byte(s) but the writer tallied {}/{}",
                self.path.display(),
                self.seen_records,
                self.seen_bytes,
                self.expect_records,
                self.expect_bytes
            ));
        }
        Ok(())
    }
}

// --- the normalized file -----------------------------------------------------

/// What one pass over the geojsonseq learned, beyond the records themselves.
///
/// The streaming producer's header comes from here rather than from a second pass:
/// [`crate::pmtiles::StreamBuilder`]'s bounds fields are serialised last, so they can be
/// a streaming fold assigned any time before `finish`.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct NormalizedSummary {
    pub count: u64,
    pub bounds: Option<Rect>,
    /// The FIRST feature's kind, which is the rule `dominant_geom_type` already applies:
    /// a layer is styled as one thing, so a mixed layer is a mistake upstream and should
    /// show as wrong rendering rather than hide as a silently split layer.
    pub geom_kind: Option<GeomKind>,
    pub skipped: u64,
    /// Byte offset of every `NORM_CHUNK_FEATURES`-th record, plus a final sentinel
    /// holding the file's total length — so chunk `i` spans `chunks[i]..chunks[i + 1]`
    /// and there are `chunks.len() - 1` of them.
    ///
    /// Built by the same single pass that writes the records, because the writer is the
    /// only place that already knows each record's length. Empty for an empty file.
    pub chunks: Vec<u64>,
}

impl NormalizedSummary {
    /// How many chunks the file has, for [`NormalizedChunks::read_into`].
    pub fn chunk_count(&self) -> usize {
        self.chunks.len().saturating_sub(1)
    }
}

/// Writes the geojsonseq once into a compact binary every zoom can re-read.
///
/// ```text
/// 0..4    u32 rec_len       whole record, this field included
/// 4..8    u32 geom_len
/// 8..12   u32 props_len
/// 12..13  u8  geom_kind
/// 13..16  reserved, zero
/// 16..    geometry (lon/lat e7 `i32` pairs), then properties
/// ```
///
/// Eight bytes a vertex rather than sixteen: see [`encode_geometry`] for why that is
/// lossless for OSM coordinates and what it costs a coastline.
///
/// # Compressed frames (planet disk peak)
///
/// Records are grouped into frames of [`NORM_CHUNK_FEATURES`] features and each frame is
/// stored as raw DEFLATE. The file layout is:
///
/// ```text
/// 0..4    magic `NZC1`
/// 4..5    version 1
/// 5..8    reserved, zero
/// 8..    frames, back to back:
///           u32 compressed_len (this field excluded)
///           raw DEFLATE stream of one chunk's records
/// ```
///
/// Why this shape: the 259 GB planet feature spill is what fills the disk beside the z14
/// tile chunks (~101 GB) and the bodies scratch, and coordinates compress ~3x (sorted e7
/// varints, delta runs). Per-chunk frames keep the [`NormalizedSummary::chunks`] index valid
/// verbatim -- chunk `i` still spans `chunks[i]..chunks[i+1]`, now over compressed bytes --
/// so positional reads, the `chunk_mins` zoom filter, the prefetch lanes and the anon backend
/// all work unchanged, and inflate-then-decode yields byte-identical records. A whole-file
/// stream would kill positional reads; per-record frames would pay inflate setup 1.9 B x 15
/// times for worse ratio.
///
/// Spill files are build-temp (removed on success, never shipped), so there is no cross-version
/// compat to keep: a reader that does not know `NZC1` refuses the magic rather than decoding
/// frames as records.
///
/// Backed by a file ([`create`](Self::create)) or by anonymous pagefile memory
/// ([`create_anon`](Self::create_anon)),
/// which stages the same bytes with no directory entry. Same records, same
/// offsets, same output bytes — the -Verify hash check proves it.
/// Where a [`NormalizedWriter`] stages its bytes: a file, or anonymous memory.
enum WriterSink {
    File(BufWriter<File>),
    Anon(crate::anon::AnonStore),
}

/// File magic for the compressed normalized spill. A reader that does not know it refuses
/// the file rather than decoding frames as records.
const NORM_MAGIC: &[u8; 4] = b"NZC1";
/// Frame format version. Bumped if the framing ever changes; spill files are build-temp so no
/// migration path is needed, only a refusal.
const NORM_VERSION: u8 = 1;
/// Bytes of file header before the first frame. The chunk index's first offset equals this.
const NORM_FILE_HEADER_BYTES: usize = 8;
/// Largest single frame accepted, compressed or not. A 64-feature chunk of plausible records is
/// kilobytes; a gigabyte frame is corruption, not a dense chunk.
const MAX_FRAME_BYTES: u64 = 1 << 30;

pub struct NormalizedWriter {
    out: WriterSink,
    path: PathBuf,
    summary: NormalizedSummary,
    rec: Vec<u8>,
    /// The current chunk's raw records, accumulating until [`NORM_CHUNK_FEATURES`] are held.
    /// Buffered rather than written per record because a frame is compressed as a unit: flushing
    /// per record would be per-record frames (worse ratio, 1.9 B x 15 inflates). One chunk is
    /// ~9 KB raw, noise next to every other peak in the build.
    pending: Vec<u8>,
    /// Records in `pending`. Counted rather than derived because records are variable-length.
    pending_count: u64,
    /// Compressed scratch, reused across frames so a chunk seal allocates nothing.
    sealed: Vec<u8>,
    /// Sealed-but-not-yet-compressed frames (raw records), oldest first. Compressed on the pool
    /// [`SEAL_BATCH`] at a time by [`drain_frames`](Self::drain_frames) and staged in order, so
    /// DEFLATE leaves the serial push path while the bytes, offsets and chunk index stay exactly
    /// what sealing one frame at a time produced.
    queued: Vec<Vec<u8>>,
    /// Bytes written so far, which is the next frame's offset. Tracks *compressed* bytes: the
    /// chunk index addresses frames, not records.
    at: u64,
}

/// DEFLATE level for spill frames. See [`NormalizedWriter::seal_frame`].
const SPILL_DEFLATE_LEVEL: u8 = 1;

/// Frames compressed per batch by [`NormalizedWriter::drain_frames`]. ~9 KB raw each on
/// California, so a batch is tens of MB in flight and one multi-MB write; large enough that
/// the pool hand-off is noise next to the DEFLATE it parallelises.
const SEAL_BATCH: usize = 4096;

impl NormalizedWriter {
    pub fn create(path: impl Into<PathBuf>) -> Result<NormalizedWriter> {
        let path = path.into();
        if let Some(dir) = path.parent() {
            if !dir.as_os_str().is_empty() {
                std::fs::create_dir_all(dir)
                    .map_err(|e| Error(format!("cannot create {}: {e}", dir.display())))?;
            }
        }
        let f = File::create(&path)
            .map_err(|e| Error(format!("cannot create {}: {e}", path.display())))?;
        let mut w = NormalizedWriter {
            out: WriterSink::File(BufWriter::with_capacity(1 << 20, f)),
            path,
            summary: NormalizedSummary::default(),
            rec: Vec::new(),
            pending: Vec::new(),
            pending_count: 0,
            sealed: Vec::new(),
            queued: Vec::new(),
            // The first frame starts past the file header; the chunk index's first offset
            // records exactly this, so readers never special-case it.
            at: NORM_FILE_HEADER_BYTES as u64,
        };
        w.write_file_header()?;
        Ok(w)
    }

    /// Anonymous twin of [`create`](Self::create): same records and offsets,
    /// staged in pagefile-backed memory with no file. `path` names nothing on
    /// disk — it only rides along for error messages. Pair with
    /// [`NormalizedChunks::open_anon`] on the way back.
    pub fn create_anon(path: impl Into<PathBuf>) -> Result<NormalizedWriter> {
        let mut w = NormalizedWriter {
            out: WriterSink::Anon(crate::anon::AnonStore::new()),
            path: path.into(),
            summary: NormalizedSummary::default(),
            rec: Vec::new(),
            pending: Vec::new(),
            pending_count: 0,
            sealed: Vec::new(),
            queued: Vec::new(),
            at: NORM_FILE_HEADER_BYTES as u64,
        };
        w.write_file_header()?;
        Ok(w)
    }

    /// The 8-byte file header: magic, version, reserved zero. Written once, before the first
    /// frame, on both backends -- so anon and file bytes are identical and every reader parses
    /// one layout.
    fn write_file_header(&mut self) -> Result<()> {
        let mut head = [0u8; NORM_FILE_HEADER_BYTES];
        head[0..4].copy_from_slice(NORM_MAGIC);
        head[4] = NORM_VERSION;
        match &mut self.out {
            WriterSink::File(f) => f
                .write_all(&head)
                .map_err(|e| Error(format!("writing {}: {e}", self.path.display())))?,
            WriterSink::Anon(a) => {
                a.push(&head)?;
            }
        }
        Ok(())
    }

    /// A line the caller could not use. Counted here so the summary is the one place
    /// the whole pass is described.
    pub fn skip(&mut self) {
        self.summary.skipped += 1;
    }

    pub fn push(&mut self, geometry: &Geometry, props: &[(String, Value)]) -> Result<()> {
        self.rec.clear();
        self.rec.resize(NORM_HEADER_BYTES, 0);
        encode_geometry(geometry, &mut self.rec)?;
        let geom_len = count(self.rec.len() - NORM_HEADER_BYTES)?;
        encode_props(props, &mut self.rec)?;
        let props_len = count(self.rec.len() - NORM_HEADER_BYTES - geom_len as usize)?;
        let rec_len = count(self.rec.len())?;
        self.rec[0..4].copy_from_slice(&rec_len.to_le_bytes());
        self.rec[4..8].copy_from_slice(&geom_len.to_le_bytes());
        self.rec[8..12].copy_from_slice(&props_len.to_le_bytes());
        self.rec[12] = GeomKind::of(geometry).tag();
        // Buffered, not written: a frame is one chunk's records compressed as a unit. The index
        // entry below still fires on the same 64-feature boundary, so entry `i` still describes
        // chunk `i` -- only the bytes behind the offset are frames now.
        self.pending.extend_from_slice(&self.rec);
        self.pending_count += 1;
        // The chunk index entry for this frame is recorded when the frame is staged
        // (`drain_frames`), which is when its offset is known: frames are compressed in
        // batches, so `at` here still lags the frames queued ahead of this one.
        if self.pending_count >= NORM_CHUNK_FEATURES {
            self.seal_frame()?;
        }

        if self.summary.geom_kind.is_none() {
            self.summary.geom_kind = Some(GeomKind::of(geometry));
        }
        self.summary.bounds = crate::pyramid::fold_bounds(self.summary.bounds, geometry);
        self.summary.count += 1;
        Ok(())
    }

    /// [`push`](Self::push) for a geometry encoded off-thread: the same record
    /// layout with the geometry bytes supplied instead of encoded here.
    ///
    /// The materialise drains resolve + encode + region-test each batch on the
    /// pool and append the records in order here, so the serial thread does no
    /// vertex walk. Byte-identical to `push`: `geom_bytes` must be what
    /// [`encode_geometry`](crate::spill::encode_geometry) produced for the same
    /// geometry, `kind` its [`GeomKind`], `bounds` its
    /// [`bounds`](crate::geom::bounds) — all pure functions, so computing them
    /// on the pool changes no byte. Order, chunk index, sealing and the bounds
    /// fold are untouched: they happen here, serially, exactly as in `push`.
    pub fn push_preencoded(
        &mut self,
        kind: GeomKind,
        geom_bytes: &[u8],
        bounds: Option<crate::geom::Rect>,
        props: &[(String, Value)],
    ) -> Result<()> {
        let mut rec = std::mem::take(&mut self.rec);
        let built = encode_normalized_record(kind, geom_bytes, props, &mut rec);
        let pushed = built.and_then(|()| self.push_record(kind, &rec, bounds));
        self.rec = rec;
        pushed
    }

    /// Append one record [`encode_normalized_record`] already built (on the pool): the serial half of
    /// [`push_preencoded`](Self::push_preencoded), byte-for-byte. `kind` and `bounds` are the
    /// record's own, as `push_preencoded` takes them.
    pub fn push_record(
        &mut self,
        kind: GeomKind,
        rec: &[u8],
        bounds: Option<crate::geom::Rect>,
    ) -> Result<()> {
        self.pending.extend_from_slice(rec);
        self.pending_count += 1;
        // The chunk index entry for this frame is recorded when the frame is staged
        // (`drain_frames`), which is when its offset is known.
        if self.pending_count >= NORM_CHUNK_FEATURES {
            self.seal_frame()?;
        }

        if self.summary.geom_kind.is_none() {
            self.summary.geom_kind = Some(kind);
        }
        self.summary.bounds = match (self.summary.bounds, bounds) {
            (None, b) => b,
            (a, None) => a,
            (Some(a), Some(b)) => Some(crate::geom::Rect {
                min_x: a.min_x.min(b.min_x),
                min_y: a.min_y.min(b.min_y),
                max_x: a.max_x.max(b.max_x),
                max_y: a.max_y.max(b.max_y),
            }),
        };
        self.summary.count += 1;
        Ok(())
    }

    /// Compress the buffered chunk into one frame and stage it.
    ///
    /// Level 1, not 6: the write happens once (stage A, serial sink) while the reads happen
    /// fifteen times (parallel prefetch lanes), which is what argued for 6 — ratio over deflate
    /// speed, with level-independent inflate. Measured on California, that argument was wrong:
    /// level 6 spends ~27 s of a 61 s `materialise-ways` serial drain in DEFLATE while level 1
    /// spends ~0 s for ~5% more scratch bytes (scratch, deleted on success; the budget gate
    /// sizes commit with headroom). The drain is serial and order-critical, so its wall is
    /// Amdahl time; the spill size is not.
    ///
    /// The level is a constant, not a knob: the frame header carries no level mark because
    /// inflate is level-independent, so the choice changes only scratch bytes, never the
    /// archive — and an operator knob here would trade build minutes for scratch bytes with
    /// no content effect either way. Re-measure (drain wall + spill GB) before touching it.
    fn seal_frame(&mut self) -> Result<()> {
        if self.pending.is_empty() {
            return Ok(());
        }
        // Queued raw, compressed later in a batch: see `drain_frames`. `take` leaves `pending`
        // empty for the next chunk; the frame owns its bytes until it is staged.
        self.queued.push(std::mem::take(&mut self.pending));
        self.pending_count = 0;
        if self.queued.len() >= SEAL_BATCH {
            self.drain_frames()?;
        }
        Ok(())
    }

    /// Compress every queued frame (on the pool when there is more than one) and stage them in
    /// queue order, recording each frame's chunk-index offset as it lands.
    ///
    /// Byte-identical to sealing frames one at a time: DEFLATE is a pure function of a frame's
    /// bytes and the level, the frames are staged in the order they were sealed, and each index
    /// entry is the offset the frame is written at -- exactly the `at` the per-frame path
    /// recorded at the frame's first record, since every frame starts on a
    /// `NORM_CHUNK_FEATURES` boundary and the frames before it were staged first.
    ///
    /// The batch also turns the writes into one large sequential write per batch (megabytes)
    /// rather than one per 64-feature frame.
    fn drain_frames(&mut self) -> Result<()> {
        if self.queued.is_empty() {
            return Ok(());
        }
        let frames = std::mem::take(&mut self.queued);
        let compressed: Vec<Vec<u8>> = if frames.len() == 1 {
            vec![miniz_oxide::deflate::compress_to_vec(&frames[0], SPILL_DEFLATE_LEVEL)]
        } else {
            use rayon::prelude::*;
            crate::par::install(|| {
                frames
                    .par_iter()
                    .map(|raw| miniz_oxide::deflate::compress_to_vec(raw, SPILL_DEFLATE_LEVEL))
                    .collect()
            })
        };
        drop(frames);
        let total: usize = compressed.iter().map(|c| 4 + c.len()).sum();
        self.sealed.clear();
        self.sealed.reserve(total);
        for frame in &compressed {
            let frame_len = u32::try_from(frame.len())
                .map_err(|_| Error("a spill frame is larger than 4 GiB".to_string()))?;
            self.summary.chunks.push(self.at + self.sealed.len() as u64);
            self.sealed.extend_from_slice(&frame_len.to_le_bytes());
            self.sealed.extend_from_slice(frame);
        }
        match &mut self.out {
            WriterSink::File(f) => f
                .write_all(&self.sealed)
                .map_err(|e| Error(format!("writing {}: {e}", self.path.display())))?,
            WriterSink::Anon(a) => {
                a.push(&self.sealed)?;
            }
        }
        self.at += self.sealed.len() as u64;
        self.sealed.clear();
        Ok(())
    }

    /// Compressed bytes staged so far (frames plus file header). For spill-size
    /// measurement: the budget calibrator (`SPILL_BYTES_PER_FEATURE`) is tuned
    /// per deflate level, so level experiments report this alongside the wall.
    /// Lags by the frames still queued for batch compression until `finish`.
    pub fn staged_bytes(&self) -> u64 {
        self.at
    }

    /// Flush, and hand back what the pass learned.
    pub fn finish(mut self) -> Result<NormalizedSummary> {
        // The last chunk is usually partial and still needs its frame -- without this its
        // records would be buffered and never staged.
        self.seal_frame()?;
        self.drain_frames()?;
        self.flush_out()?;
        // Close the last chunk. Without this the final partial chunk has no end, and
        // `chunk_count` would also over-report by one.
        if !self.summary.chunks.is_empty() {
            self.summary.chunks.push(self.at);
        }
        Ok(self.summary)
    }

    /// Flush, and hand back what the pass learned plus whatever backend holds the bytes.
    ///
    /// Unifies [`finish`](Self::finish) and [`finish_anon`](Self::finish_anon): callers that pick
    /// the backend by budget ([`osm_ingest::mem::SpillPlan`]) seal without knowing which one they
    /// chose. A file backend yields no store -- its bytes live at the path it was created with,
    /// and readers open that path.
    pub fn finish_either(mut self) -> Result<(NormalizedSummary, Option<crate::anon::AnonStore>)> {
        self.seal_frame()?;
        self.drain_frames()?;
        self.flush_out()?;
        if !self.summary.chunks.is_empty() {
            self.summary.chunks.push(self.at);
        }
        match self.out {
            WriterSink::Anon(a) => Ok((self.summary, Some(a))),
            WriterSink::File(_) => Ok((self.summary, None)),
        }
    }

    /// Anonymous twin of [`finish`](Self::finish): flush, hand back the summary
    /// AND the staged store for [`NormalizedChunks::open_anon`]. Errors when
    /// this writer is file-backed (a programming bug, not a runtime one).
    pub fn finish_anon(mut self) -> Result<(NormalizedSummary, crate::anon::AnonStore)> {
        self.seal_frame()?;
        self.drain_frames()?;
        self.flush_out()?;
        if !self.summary.chunks.is_empty() {
            self.summary.chunks.push(self.at);
        }
        match self.out {
            WriterSink::Anon(a) => Ok((self.summary, a)),
            WriterSink::File(_) => err("finish_anon on a file-backed writer".to_string()),
        }
    }

    fn flush_out(&mut self) -> Result<()> {
        match &mut self.out {
            WriterSink::File(f) => f
                .flush()
                .map_err(|e| Error(format!("flushing {}: {e}", self.path.display())))?,
            WriterSink::Anon(a) => a.finish(),
        }
        Ok(())
    }
}

/// One spill record -- header, pre-encoded geometry, properties -- written into `out`
/// (cleared first). Pure, so the materialise drains build records on the pool and hand them to
/// [`NormalizedWriter::push_record`] in order; `push_preencoded` is exactly this plus that.
pub fn encode_normalized_record(
    kind: GeomKind,
    geom_bytes: &[u8],
    props: &[(String, Value)],
    out: &mut Vec<u8>,
) -> Result<()> {
    out.clear();
    out.resize(NORM_HEADER_BYTES, 0);
    out.extend_from_slice(geom_bytes);
    let geom_len = count(geom_bytes.len())?;
    encode_props(props, out)?;
    let props_len = count(out.len() - NORM_HEADER_BYTES - geom_len as usize)?;
    let rec_len = count(out.len())?;
    out[0..4].copy_from_slice(&rec_len.to_le_bytes());
    out[4..8].copy_from_slice(&geom_len.to_le_bytes());
    out[8..12].copy_from_slice(&props_len.to_le_bytes());
    out[12] = kind.tag();
    Ok(())
}

/// One normalized feature.
#[derive(Debug, Clone, PartialEq)]
pub struct NormalizedFeature {
    pub geometry: Geometry,
    pub props: Vec<(String, Value)>,
}

/// Validate a normalized record's fixed header, returning its payload shape.
///
/// Shared by the sequential reader and the chunked one, so the two cannot diverge on
/// what they accept: a record one of them rejects must not be one the other decodes.
fn norm_header(head: &[u8; NORM_HEADER_BYTES]) -> Result<(usize, usize, GeomKind)> {
    if head[13..NORM_HEADER_BYTES].iter().any(|v| *v != 0) {
        return err("normalized record has a nonzero reserved tail");
    }
    let u32_at = |o: usize| u32::from_le_bytes(head[o..o + 4].try_into().expect("4 bytes"));
    let rec_len = u32_at(0) as u64;
    let geom_len = u32_at(4) as usize;
    let props_len = u32_at(8) as usize;
    let want = NORM_HEADER_BYTES as u64 + geom_len as u64 + props_len as u64;
    if rec_len != want {
        return err(format!(
            "normalized record claims {rec_len} byte(s) but its header adds up to {want}"
        ));
    }
    if want > MAX_RECORD_BYTES {
        return err(format!(
            "normalized record is {want} byte(s), which is corruption"
        ));
    }
    Ok((geom_len, props_len, GeomKind::from_tag(head[12])?))
}

/// Decode a validated record's payload, `geom_len` bytes of geometry then properties.
fn norm_payload(kind: GeomKind, geom_len: usize, payload: &[u8]) -> Result<NormalizedFeature> {
    Ok(NormalizedFeature {
        geometry: decode_geometry(kind, &payload[..geom_len])?,
        props: decode_props(&payload[geom_len..])?,
    })
}

/// Validate a spill file header, returning nothing: the magic, version and reserved tail are
/// either exactly what the writer emits or the file is not a spill this reader knows.
fn norm_file_header(head: &[u8; NORM_FILE_HEADER_BYTES]) -> Result<()> {
    if &head[0..4] != NORM_MAGIC {
        return err("not a normalized spill file (bad NZC1 magic)".to_string());
    }
    if head[4] != NORM_VERSION {
        return err(format!(
            "unsupported normalized spill version {} (this reader speaks v{NORM_VERSION})",
            head[4],
        ));
    }
    if head[5..NORM_FILE_HEADER_BYTES].iter().any(|v| *v != 0) {
        return err("a normalized spill file has a nonzero reserved tail".to_string());
    }
    Ok(())
}

/// Inflate one frame: `u32 compressed_len` + raw DEFLATE of one chunk's records.
///
/// The length is checked against the chunk span first (a frame longer than its span is
/// corruption, not a large chunk), then the inflate itself is the check: DEFLATE framing errors
/// on truncated or bit-rotted input rather than yielding partial records. Record validation
/// (`norm_header` per record) still runs on the inflated bytes, so a corrupt frame that
/// happens to inflate is refused as the desync it is.
fn inflate_frame(chunk: usize, path: &std::path::Path, frame: &[u8]) -> Result<Vec<u8>> {
    if frame.len() < 4 {
        return err(format!(
            "normalized chunk {chunk} of {} ends {} byte(s) into its 4-byte frame header",
            path.display(),
            frame.len(),
        ));
    }
    let want = u32::from_le_bytes(frame[0..4].try_into().expect("4 bytes")) as u64;
    if want > MAX_FRAME_BYTES {
        return err(format!(
            "normalized chunk {chunk} of {} claims a {want}-byte frame, which is corruption",
            path.display(),
        ));
    }
    if want as usize != frame.len() - 4 {
        return err(format!(
            "normalized chunk {chunk} of {} holds {} frame byte(s) but claims {want}",
            path.display(),
            frame.len() - 4,
        ));
    }
    miniz_oxide::inflate::decompress_to_vec(&frame[4..]).map_err(|e| {
        Error(format!(
            "normalized chunk {chunk} of {} does not inflate: {e:?}",
            path.display(),
        ))
    })
}

/// Decode every record in one inflated chunk into `out`, which is cleared first.
///
/// Split from [`NormalizedChunks::read_into`] so the sequential reader shares the exact same
/// validation: a record one of them rejects must not be one the other decodes.
fn decode_chunk_records(
    chunk: usize,
    path: &std::path::Path,
    raw: &[u8],
    out: &mut Vec<NormalizedFeature>,
) -> Result<()> {
    out.clear();
    let mut at = 0usize;
    while at < raw.len() {
        if raw.len() - at < NORM_HEADER_BYTES {
            return err(format!(
                "normalized chunk {chunk} of {} ends {} byte(s) into a \
                 {NORM_HEADER_BYTES}-byte record header",
                path.display(),
                raw.len() - at
            ));
        }
        let head: &[u8; NORM_HEADER_BYTES] = raw[at..at + NORM_HEADER_BYTES]
            .try_into()
            .expect("a header's worth of bytes");
        let (geom_len, props_len, kind) = norm_header(head)?;
        let body = at + NORM_HEADER_BYTES;
        let rec_end = body + geom_len + props_len;
        if rec_end > raw.len() {
            return err(format!(
                "normalized chunk {chunk} of {} holds a record running {} byte(s) past its end",
                path.display(),
                rec_end - raw.len()
            ));
        }
        out.push(norm_payload(kind, geom_len, &raw[body..rec_end])?);
        at = rec_end;
    }
    Ok(())
}

/// Sequential reader over the normalized spill, rewindable because every zoom
/// re-reads it from the front. File-backed ([`open`](Self::open)) or anonymous
/// ([`open_anon`](Self::open_anon)): the record format is a sequential stream
/// either way, so the reader is a cursor over bytes — a file cursor or a store
/// offset.
pub struct NormalizedReader {
    src: ReaderIn,
    path: PathBuf,
    /// The current frame's inflated records. Refilled from the next frame when exhausted; the
    /// reader yields records across the refill boundary without the caller seeing frames at all.
    frame: Vec<u8>,
    /// Bytes of `frame` already yielded.
    frame_used: usize,
    /// Frames exhausted so far, for error messages that name the chunk.
    frames_seen: usize,
    /// Scratch for one compressed frame, reused so a refill allocates nothing but the inflate.
    raw_frame: Vec<u8>,
}

/// Where a [`NormalizedReader`] reads from.
///
/// Both variants are frame-aware now: the file cursor sits at the next unread frame (past the
/// 8-byte file header), and the anon offset likewise. `rewind` returns both to the first frame.
enum ReaderIn {
    File(BufReader<File>),
    Anon {
        store: std::sync::Arc<crate::anon::AnonStore>,
        at: u64,
    },
}

impl NormalizedReader {
    pub fn open(path: impl Into<PathBuf>) -> Result<NormalizedReader> {
        let path = path.into();
        let f = File::open(&path)
            .map_err(|e| Error(format!("cannot read {}: {e}", path.display())))?;
        let mut reader = NormalizedReader {
            src: ReaderIn::File(BufReader::with_capacity(1 << 20, f)),
            path,
            frame: Vec::new(),
            frame_used: 0,
            frames_seen: 0,
            raw_frame: Vec::new(),
        };
        reader.read_file_header()?;
        Ok(reader)
    }

    /// Anonymous twin of [`open`](Self::open): reads the sealed store from
    /// [`NormalizedWriter::finish_anon`], shared by refcount.
    pub fn open_anon(
        path: impl Into<PathBuf>,
        store: std::sync::Arc<crate::anon::AnonStore>,
    ) -> Result<NormalizedReader> {
        let mut reader = NormalizedReader {
            src: ReaderIn::Anon { store, at: NORM_FILE_HEADER_BYTES as u64 },
            path: path.into(),
            frame: Vec::new(),
            frame_used: 0,
            frames_seen: 0,
            raw_frame: Vec::new(),
        };
        reader.read_file_header()?;
        Ok(reader)
    }

    /// Parse and validate the 8-byte file header, positioning the cursor at the first frame.
    ///
    /// Read through the cursor itself (not a separate open) so the file and anon paths share
    /// the framing: on the file path this consumes the header bytes; on anon it validates the
    /// store's own first 8 bytes while `at` already skips past them.
    fn read_file_header(&mut self) -> Result<()> {
        match &mut self.src {
            ReaderIn::File(f) => {
                let mut head = [0u8; NORM_FILE_HEADER_BYTES];
                use std::io::Read;
                f.read_exact(&mut head).map_err(|e| {
                    Error(format!("reading {}'s spill header: {e}", self.path.display()))
                })?;
                norm_file_header(&head)?;
            }
            ReaderIn::Anon { store, .. } => {
                let mut head = [0u8; NORM_FILE_HEADER_BYTES];
                store.read_at(0, &mut head).map_err(|e| {
                    Error(format!("reading {}'s spill header: {e}", self.path.display()))
                })?;
                norm_file_header(&head)?;
            }
        }
        Ok(())
    }

    pub fn rewind(&mut self) -> Result<()> {
        match &mut self.src {
            ReaderIn::File(f) => {
                use std::io::Seek;
                // Past the file header: frame 0 starts at byte 8, not byte 0.
                f.seek(std::io::SeekFrom::Start(NORM_FILE_HEADER_BYTES as u64))
                    .map_err(|e| Error(format!("rewinding {}: {e}", self.path.display())))?;
            }
            ReaderIn::Anon { at, .. } => *at = NORM_FILE_HEADER_BYTES as u64,
        }
        self.frame.clear();
        self.frame_used = 0;
        self.frames_seen = 0;
        Ok(())
    }

    /// Fill `buf` with exactly `len` bytes from the cursor, or `Ok(false)` at a
    /// clean frame boundary (start of read, or exactly past the last frame's bytes).
    ///
    /// A short read anywhere else is corruption, and the callers treat it as such: frames state
    /// their own length, so a file that ends mid-frame is truncated, not finished.
    fn read_full(&mut self, buf: &mut [u8]) -> Result<bool> {
        match &mut self.src {
            ReaderIn::File(f) => {
                let mut read = 0usize;
                while read < buf.len() {
                    let n = f
                        .read(&mut buf[read..])
                        .map_err(|e| Error(format!("reading {}: {e}", self.path.display())))?;
                    if n == 0 {
                        break;
                    }
                    read += n;
                }
                // `Ok(false)` only at a frame boundary: mid-frame EOF is reported by the frame
                // length check in `refill`, not here.
                Ok(read > 0)
            }
            ReaderIn::Anon { store, at } => {
                if *at >= store.len() {
                    return Ok(false);
                }
                let remaining = store.len() - *at;
                let take = (buf.len() as u64).min(remaining) as usize;
                store.read_at(*at, &mut buf[..take]).map_err(|e| {
                    Error(format!("reading {}: {e}", self.path.display()))
                })?;
                *at += take as u64;
                Ok(true)
            }
        }
    }

    /// The next frame's inflated records, replacing `frame`.
    ///
    /// Reads the frame's `u32` length, then exactly that many bytes -- a short read there is a
    /// truncated file, never a legitimate end -- inflates, and validates the inflate before any
    /// record is yielded. `Ok(false)` only when the cursor sits exactly at end of input.
    fn refill(&mut self) -> Result<bool> {
        let mut len_buf = [0u8; 4];
        if !self.read_full(&mut len_buf)? {
            return Ok(false);
        }
        let want = u32::from_le_bytes(len_buf) as usize;
        if want as u64 > MAX_FRAME_BYTES {
            return err(format!(
                "normalized frame {} of {} claims {} byte(s), which is corruption",
                self.frames_seen,
                self.path.display(),
                want,
            ));
        }
        self.raw_frame.clear();
        self.raw_frame.resize(want, 0);
        // Exact-length by construction (the length was just read), so a short payload is
        // corruption, not EOF. `read_full` may return short mid-frame on the file path;
        // loop until the frame is whole or the file ends.
        let mut read = 0usize;
        while read < want {
            let mut chunk = vec![0u8; want - read];
            if !self.read_full(&mut chunk)? {
                return err(format!(
                    "{} ends {} byte(s) into frame {}",
                    self.path.display(),
                    read,
                    self.frames_seen,
                ));
            }
            // `read_full` on the file path may return short; on anon it fills exactly.
            let n = chunk.len();
            self.raw_frame[read..read + n].copy_from_slice(&chunk);
            read += n;
            if n == 0 {
                break;
            }
        }
        let mut framed = Vec::with_capacity(4 + want);
        framed.extend_from_slice(&len_buf);
        framed.extend_from_slice(&self.raw_frame);
        self.frame = inflate_frame(self.frames_seen, &self.path, &framed)?;
        self.frame_used = 0;
        self.frames_seen += 1;
        Ok(true)
    }

    #[allow(clippy::should_implement_trait)]
    pub fn next(&mut self) -> Result<Option<NormalizedFeature>> {
        loop {
            if self.frame_used < self.frame.len() {
                let head: &[u8; NORM_HEADER_BYTES] = self.frame
                    [self.frame_used..self.frame_used + NORM_HEADER_BYTES]
                    .try_into()
                    .map_err(|_| {
                        Error(format!(
                            "{} ends {} byte(s) into a {NORM_HEADER_BYTES}-byte record header",
                            self.path.display(),
                            self.frame.len() - self.frame_used,
                        ))
                    })?;
                let (geom_len, props_len, kind) = norm_header(head)?;
                let body = self.frame_used + NORM_HEADER_BYTES;
                let rec_end = body + geom_len + props_len;
                if rec_end > self.frame.len() {
                    return err(format!(
                        "{} holds a record running {} byte(s) past its frame",
                        self.path.display(),
                        rec_end - self.frame.len(),
                    ));
                }
                let feature = norm_payload(kind, geom_len, &self.frame[body..rec_end])?;
                self.frame_used = rec_end;
                return Ok(Some(feature));
            }
            if !self.refill()? {
                return Ok(None);
            }
        }
    }
}

/// Chunked reader over the normalized file, for reading it across a thread pool.
///
/// The sequential [`NormalizedReader`] is one cursor, so the decode of every feature —
/// which allocates a `Geometry` and a `Vec` of properties, six times over a z11..z16
/// build — happens on whichever single thread owns it, while the pool waits. This reads
/// a whole chunk's byte range positionally instead, so the decode happens on the worker
/// that will bucket those features.
///
/// Takes `&self` throughout: see [`crate::pmtiles::read_exact_at`]. One handle serves
/// every thread.
///
/// Anonymous twin: [`open_anon`](Self::open_anon) serves the same chunks from an
/// [`crate::anon::AnonStore`] instead of a file. Same offsets, same bytes.
pub struct NormalizedChunks {
    file: Option<File>,
    anon: Option<std::sync::Arc<crate::anon::AnonStore>>,
    path: PathBuf,
    /// Chunk boundaries, `chunk_count() + 1` of them. See [`NormalizedSummary::chunks`].
    chunks: Vec<u64>,
}

impl NormalizedChunks {
    /// `chunks` is [`NormalizedSummary::chunks`] for this file.
    pub fn open(path: impl Into<PathBuf>, chunks: Vec<u64>) -> Result<NormalizedChunks> {
        let path = path.into();
        let file = File::open(&path)
            .map_err(|e| Error(format!("cannot read {}: {e}", path.display())))?;
        Ok(NormalizedChunks { file: Some(file), anon: None, path, chunks })
    }

    /// Anonymous twin of [`open`](Self::open): `store` is the sealed store from
    /// [`NormalizedWriter::finish_anon`], shared by refcount — the store
    /// outlives every reader, so this is a refcount bump, never a copy.
    /// `path` names nothing — it only rides along for error messages.
    pub fn open_anon(
        path: impl Into<PathBuf>,
        chunks: Vec<u64>,
        store: std::sync::Arc<crate::anon::AnonStore>,
    ) -> Result<NormalizedChunks> {
        Ok(NormalizedChunks {
            file: None,
            anon: Some(store),
            path: path.into(),
            chunks,
        })
    }

    pub fn chunk_count(&self) -> usize {
        self.chunks.len().saturating_sub(1)
    }

    /// Whether this reader serves from anonymous memory rather than a file.
    pub fn is_anon(&self) -> bool {
        self.anon.is_some()
    }

    /// Decode chunk `i` into `out`, which is cleared first.
    ///
    /// `scratch` is the caller's per-worker byte buffer, reused across chunks so a pass
    /// allocates once per thread rather than once per chunk.
    ///
    /// The first feature of chunk `i` is input feature `i * NORM_CHUNK_FEATURES`, by
    /// construction of the index — which is what lets the caller reproduce the exact
    /// `seq` the sequential path assigns.
    ///
    /// The chunk span covers one compressed frame: it is read whole, inflated, and decoded
    /// with the same [`decode_chunk_records`] the sequential reader uses, so the two paths
    /// cannot diverge on what they accept.
    pub fn read_into(
        &self,
        i: usize,
        scratch: &mut Vec<u8>,
        out: &mut Vec<NormalizedFeature>,
    ) -> Result<()> {
        out.clear();
        let (Some(&start), Some(&end)) = (self.chunks.get(i), self.chunks.get(i + 1)) else {
            return err(format!(
                "normalized chunk {i} is past the {} in {}",
                self.chunk_count(),
                self.path.display()
            ));
        };
        if end < start {
            return err(format!(
                "normalized chunk {i} of {} ends before it starts",
                self.path.display()
            ));
        }
        let len = (end - start) as usize;
        scratch.clear();
        scratch.resize(len, 0);
        match (&self.file, &self.anon) {
            (Some(f), None) => {
                crate::pmtiles::read_exact_at(f, scratch, start).map_err(|e| {
                    Error(format!(
                        "reading normalized chunk {i} of {}: {e}",
                        self.path.display()
                    ))
                })?;
            }
            (None, Some(a)) => a.read_at(start, scratch).map_err(|e| {
                Error(format!(
                    "reading normalized chunk {i} of {}: {e}",
                    self.path.display()
                ))
            })?,
            _ => return err("a normalized chunk reader has no backend".to_string()),
        }
        let raw = inflate_frame(i, &self.path, scratch)?;
        decode_chunk_records(i, &self.path, &raw, out)
    }
}

/// The normalized file's lifetime, so a run that dies mid-planet cannot strand it.
///
/// A guard rather than `impl Drop` on the reader, because the reader is handed around
/// and the file has to outlive every one of them.
pub struct NormalizedFile(PathBuf);

impl NormalizedFile {
    pub fn new(path: impl Into<PathBuf>) -> NormalizedFile {
        NormalizedFile(path.into())
    }

    pub fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for NormalizedFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}
