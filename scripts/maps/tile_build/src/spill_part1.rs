fn decode_props(b: &[u8]) -> Result<Vec<(String, Value)>> {
    let mut c = Cur::new(b);
    let n = c.u32()? as usize;
    let mut out = Vec::with_capacity(n.min(1 << 12));
    for _ in 0..n {
        let key_len = c.u32()? as usize;
        let key = std::str::from_utf8(c.take(key_len)?)
            .map_err(|e| Error(format!("spill property key is not UTF-8: {e}")))?
            .to_string();
        let tag = c.u8()?;
        let value = match tag {
            0 => {
                let len = c.u32()? as usize;
                Value::String(
                    std::str::from_utf8(c.take(len)?)
                        .map_err(|e| Error(format!("spill property value is not UTF-8: {e}")))?
                        .to_string(),
                )
            }
            1 => Value::Float(f32::from_bits(c.u32()?)),
            2 => Value::Double(f64::from_bits(c.u64()?)),
            3 => Value::Int(c.i64()?),
            4 => Value::Uint(c.u64()?),
            5 => Value::SInt(c.i64()?),
            6 => Value::Bool(c.u8()? != 0),
            other => return err(format!("spill property has value type {other}")),
        };
        out.push((key, value));
    }
    if !c.at_end() {
        return err(format!(
            "spill properties decoded {} of {} byte(s)",
            c.consumed(),
            b.len()
        ));
    }
    Ok(out)
}

// --- the spill record --------------------------------------------------------

/// One feature's contribution to one tile, as a bucket stores it.
///
/// Already clipped, moved into the tile and simplified: the bucket pass does that work
/// once and the encode pass does none of it. `extent` is the importance proxy, computed
/// there too, so the encode pass's sort is a comparison of two integers.
#[derive(Debug, Clone, PartialEq)]
pub struct SpillRecord {
    pub tile_id: u64,
    /// The feature's position in the input. What makes the drop policy's order total,
    /// and the key the props-normalizing variant of this format would use.
    pub seq: u64,
    pub extent: i64,
    pub geom: IntGeometry,
    pub props: Vec<(String, Value)>,
}

impl SpillRecord {
    /// Append the wire form of this record to `out`.
    pub fn encode(&self, out: &mut Vec<u8>) -> Result<()> {
        encode_record(
            self.tile_id,
            self.seq,
            self.extent,
            &self.geom,
            &self.props,
            out,
        )
    }

    /// The whole record, ready to hand to [`BucketSet::push`].
    pub fn to_bytes(&self) -> Result<Vec<u8>> {
        let mut out = Vec::new();
        self.encode(&mut out)?;
        Ok(out)
    }
}

/// Append one record's wire form to `out`, without owning it first.
///
/// The bucket pass has a borrowed geometry and a borrowed property list and writes the
/// same feature's properties once per touched tile; building a [`SpillRecord`] to encode
/// it would clone them forty times over for nothing.
///
/// ```text
/// 0..4    u32 rec_len       whole record, this field included
/// 4..12   u64 tile_id
/// 12..20  u64 seq
/// 20..28  i64 extent
/// 28..32  u32 geom_len
/// 32..36  u32 props_len
/// 36..37  u8  geom_kind
/// 37..48  reserved, zero
/// 48..    geometry, then properties
/// ```
pub fn encode_record(
    tile_id: u64,
    seq: u64,
    extent: i64,
    geom: &IntGeometry,
    props: &[(String, Value)],
    out: &mut Vec<u8>,
) -> Result<()> {
    let start = out.len();
    out.resize(start + REC_HEADER_BYTES, 0);
    encode_int_geometry(geom, out)?;
    let geom_len = count(out.len() - start - REC_HEADER_BYTES)?;
    encode_props(props, out)?;
    let props_len = count(out.len() - start - REC_HEADER_BYTES - geom_len as usize)?;
    let rec_len = count(out.len() - start)?;

    let h = &mut out[start..start + REC_HEADER_BYTES];
    h[0..4].copy_from_slice(&rec_len.to_le_bytes());
    h[4..12].copy_from_slice(&tile_id.to_le_bytes());
    h[12..20].copy_from_slice(&seq.to_le_bytes());
    h[20..28].copy_from_slice(&extent.to_le_bytes());
    h[28..32].copy_from_slice(&geom_len.to_le_bytes());
    h[32..36].copy_from_slice(&props_len.to_le_bytes());
    h[36] = GeomKind::of_int(geom).tag();
    Ok(())
}

/// A record header, as read off disk.
struct RecHeader {
    rec_len: u32,
    tile_id: u64,
    seq: u64,
    extent: i64,
    geom_len: u32,
    props_len: u32,
    kind: GeomKind,
}

impl RecHeader {
    fn parse(b: &[u8; REC_HEADER_BYTES]) -> Result<RecHeader> {
        let u32_at = |o: usize| u32::from_le_bytes(b[o..o + 4].try_into().expect("4 bytes"));
        let u64_at = |o: usize| u64::from_le_bytes(b[o..o + 8].try_into().expect("8 bytes"));
        // A newer writer would use these, so a nonzero tail means the reader is the
        // wrong version for the file. Guessing would decode a field that has moved.
        if b[37..REC_HEADER_BYTES].iter().any(|v| *v != 0) {
            return err("spill record has a nonzero reserved tail");
        }
        let rec_len = u32_at(0);
        let geom_len = u32_at(28);
        let props_len = u32_at(32);
        let want = REC_HEADER_BYTES as u64 + geom_len as u64 + props_len as u64;
        if rec_len as u64 != want {
            return err(format!(
                "spill record claims {rec_len} byte(s) but its header adds up to {want}"
            ));
        }
        if want > MAX_RECORD_BYTES {
            return err(format!("spill record is {want} byte(s), which is corruption"));
        }
        Ok(RecHeader {
            rec_len,
            tile_id: u64_at(4),
            seq: u64_at(12),
            extent: i64::from_le_bytes(b[20..28].try_into().expect("8 bytes")),
            geom_len,
            props_len,
            kind: GeomKind::from_tag(b[36])?,
        })
    }
}

// --- buckets -----------------------------------------------------------------

/// `tile_id`-range buckets for one zoom, or for one over-budget bucket's sub-range.
///
/// Bucket `i` holds every `tile_id` in `[lo + i*span, lo + (i+1)*span)`, so the index is
/// monotonic in `tile_id`: draining buckets in ascending index, each sorted, gives
/// globally ascending `tile_id`.
///
/// The bucket count is a power of four and the range is a power of four, so `span` is
/// too and a bucket is exactly one quadtree cell's descendants. That keeps a bucket
/// spatially coherent, which is what makes a re-partition of a dense metro bucket
/// productive rather than splitting the same crowd four ways.
///
/// **The count must stay under the process's file-descriptor limit.** Files are opened
/// lazily, so a shallow zoom with a handful of occupied ranges only creates a handful —
/// but a dense zoom will hold every one of them open at once. 256 is the default and
/// safe everywhere; a few thousand is not, and this deliberately does not paper over it
/// with a handle cache.
pub struct BucketSet {
    dir: PathBuf,
    /// First `tile_id` this set covers.
    lo: u64,
    /// One past the last `tile_id` this set covers.
    hi: u64,
    span: u64,
    paths: Vec<PathBuf>,
    files: Vec<Option<BufWriter<File>>>,
    /// Tallied at push time from each record's own `tile_id`. The reader re-derives both
    /// from the bytes on disk, and [`BucketSet::seal`] asserts they agree with each
    /// other and with the file length.
    records: Vec<u64>,
    bytes: Vec<u64>,
    pushed: u64,
    pushed_bytes: u64,
    sealed: bool,
}

impl BucketSet {
    /// `name` distinguishes one set's files from another's in the same directory, so a
    /// zoom and its re-partitions do not collide.
    ///
    /// `hi - lo` must be positive; it is a power of four in every use here.
    pub fn new(
        dir: impl Into<PathBuf>,
        name: &str,
        lo: u64,
        hi: u64,
        want_buckets: usize,
    ) -> Result<BucketSet> {
        if hi <= lo {
            return err(format!("bucket set range {lo}..{hi} is empty"));
        }
        if want_buckets == 0 {
            return err("bucket set needs at least one bucket");
        }
        if !want_buckets.is_power_of_two() || want_buckets.trailing_zeros() % 2 != 0 {
            return err(format!(
                "bucket count must be a power of four so a bucket is one quadtree cell, got \
                 {want_buckets}"
            ));
        }
        let range = hi - lo;
        // A shallow zoom has fewer tiles than the requested bucket count. Dividing by
        // four keeps the quadtree property that clamping to `range` would break.
        let mut n = want_buckets as u64;
        while n > 1 && n > range {
            n /= 4;
        }
        let span = range.div_ceil(n);
        let n = range.div_ceil(span) as usize;

        let dir = dir.into();
        std::fs::create_dir_all(&dir)
            .map_err(|e| Error(format!("cannot create {}: {e}", dir.display())))?;
        let paths: Vec<PathBuf> = (0..n)
            .map(|i| dir.join(format!("{name}.{i:05}.bucket")))
            .collect();
        // A bucket that receives no records is never opened, so `File::create`'s truncate
        // would not clear a file a SIGKILLed run left at the same path -- and `seal`
        // would then report bytes on disk that nobody pushed. With an operator-supplied
        // --spill-dir that turns a crash into a baffling failure on the next attempt.
        for p in &paths {
            let _ = std::fs::remove_file(p);
        }
        Ok(BucketSet {
            dir,
            lo,
            hi,
            span,
            paths,
            files: (0..n).map(|_| None).collect(),
            records: vec![0; n],
            bytes: vec![0; n],
            pushed: 0,
            pushed_bytes: 0,
            sealed: false,
        })
    }

    pub fn len(&self) -> usize {
        self.paths.len()
    }

    pub fn is_empty(&self) -> bool {
        self.paths.is_empty()
    }

    pub fn span(&self) -> u64 {
        self.span
    }

    /// `[lo, hi)` of bucket `i`, clamped to the set's own range.
    pub fn range_of(&self, i: usize) -> (u64, u64) {
        let lo = self.lo + i as u64 * self.span;
        (lo, (lo + self.span).min(self.hi))
    }

    pub fn records_in(&self, i: usize) -> u64 {
        self.records[i]
    }

    pub fn bytes_in(&self, i: usize) -> u64 {
        self.bytes[i]
    }

    pub fn total_records(&self) -> u64 {
        self.pushed
    }

    fn index_of(&self, tile_id: u64) -> Result<usize> {
        if tile_id < self.lo || tile_id >= self.hi {
            return err(format!(
                "tile id {tile_id} is outside the bucket set's range {}..{}",
                self.lo, self.hi
            ));
        }
        Ok(((tile_id - self.lo) / self.span) as usize)
    }

    /// Append one already-encoded record to whichever bucket its `tile_id` names.
    pub fn push(&mut self, tile_id: u64, record: &[u8]) -> Result<()> {
        if self.sealed {
            return err("a sealed bucket set cannot be pushed to");
        }
        let i = self.index_of(tile_id)?;
        if self.files[i].is_none() {
            let f = File::create(&self.paths[i])
                .map_err(|e| Error(format!("cannot create {}: {e}", self.paths[i].display())))?;
            self.files[i] = Some(BufWriter::with_capacity(1 << 18, f));
        }
        let w = self.files[i].as_mut().expect("just opened");
        w.write_all(record)
            .map_err(|e| Error(format!("writing {}: {e}", self.paths[i].display())))?;
        self.records[i] += 1;
        self.bytes[i] += record.len() as u64;
        self.pushed += 1;
        self.pushed_bytes += record.len() as u64;
        Ok(())
    }

    /// Flush every bucket and check the books.
    ///
    /// The push-time tallies, the file lengths and the set's totals must all agree.
    /// This is `graph_build.rs`'s expected-count gate adapted to a partition: a bucket
    /// that quietly lost records would produce an archive with holes in it and nothing
    /// downstream could tell.
    pub fn seal(&mut self) -> Result<()> {
        for (i, slot) in self.files.iter_mut().enumerate() {
            if let Some(w) = slot {
                w.flush()
                    .map_err(|e| Error(format!("flushing {}: {e}", self.paths[i].display())))?;
            }
            *slot = None;
        }
        let mut records = 0u64;
        let mut bytes = 0u64;
        for i in 0..self.paths.len() {
            records += self.records[i];
            bytes += self.bytes[i];
            let on_disk = match std::fs::metadata(&self.paths[i]) {
                Ok(m) => m.len(),
                Err(_) if self.records[i] == 0 => 0,
                Err(e) => {
                    return err(format!("cannot stat {}: {e}", self.paths[i].display()));
                }
            };
            if on_disk != self.bytes[i] {
                return err(format!(
                    "{} is {on_disk} byte(s) on disk but {} were pushed to it",
                    self.paths[i].display(),
                    self.bytes[i]
                ));
            }
        }
        if records != self.pushed || bytes != self.pushed_bytes {
            return err(format!(
                "bucket totals are {records} record(s)/{bytes} byte(s) but {} record(s)/{} \
                 byte(s) were pushed",
                self.pushed, self.pushed_bytes
            ));
        }
        self.sealed = true;
        Ok(())
    }

    /// A reader over bucket `i`, or `None` when it holds nothing.
    ///
    /// Only valid after [`BucketSet::seal`]: reading a bucket whose writer has not been
    /// flushed would miss its tail.
    pub fn reader(&self, i: usize) -> Result<Option<BucketReader>> {
        if !self.sealed {
            return err("a bucket set must be sealed before it is read");
        }
        if self.records[i] == 0 {
            return Ok(None);
        }
        let (lo, hi) = self.range_of(i);
        let f = File::open(&self.paths[i])
            .map_err(|e| Error(format!("cannot read {}: {e}", self.paths[i].display())))?;
        Ok(Some(BucketReader {
            src: BufReader::with_capacity(1 << 18, f),
            path: self.paths[i].clone(),
            lo,
            hi,
            expect_records: self.records[i],
            expect_bytes: self.bytes[i],
            seen_records: 0,
            seen_bytes: 0,
            done: false,
            buf: Vec::new(),
        }))
    }

    /// Every record in bucket `i`, checked against the push-time tallies.
    pub fn load(&self, i: usize) -> Result<Vec<SpillRecord>> {
        let Some(mut r) = self.reader(i)? else { return Ok(Vec::new()) };
        let mut out = Vec::with_capacity(self.records[i].min(1 << 22) as usize);
        while let Some(rec) = r.next()? {
            out.push(rec);
        }
        Ok(out)
    }

    /// Best-effort cleanup, also run on drop.
    pub fn remove(&mut self) {
        for slot in self.files.iter_mut() {
            *slot = None;
        }
        for p in &self.paths {
            let _ = std::fs::remove_file(p);
        }
        // Only if empty, so a shared spill directory survives.
        let _ = std::fs::remove_dir(&self.dir);
    }
}

impl Drop for BucketSet {
    /// A zoom's buckets are a temporary, so their lifetime is the set's. Per-zoom spill
    /// is the reason peak disk is the largest single zoom rather than the sum of them,
    /// and that only holds if a dropped set really does take its files with it.
    fn drop(&mut self) {
        self.remove();
    }
}
