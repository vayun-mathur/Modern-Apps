impl ChainRec {
    /// This chain's polyline, from `chains.pts` read into memory.
    #[cfg(test)]
    pub fn pts<'a>(&self, all: &'a [geom::Pt]) -> &'a [geom::Pt] {
        let s = self.pts_start as usize;
        &all[s..s + self.pts_len as usize]
    }

    fn encode(&self, out: &mut [u8; CHAIN_REC_BYTES as usize]) {
        out.fill(0);
        out[0..8].copy_from_slice(&self.pts_start.to_le_bytes());
        out[8..12].copy_from_slice(&self.pts_len.to_le_bytes());
        out[12..16].copy_from_slice(&self.first.to_le_bytes());
        out[16..20].copy_from_slice(&self.last.to_le_bytes());
        out[20..24].copy_from_slice(&self.dist_mm.to_le_bytes());
        out[24..28].copy_from_slice(&self.name_offset.to_le_bytes());
        out[28..32].copy_from_slice(&self.fwd_lane_off.to_le_bytes());
        out[32..36].copy_from_slice(&self.bwd_lane_off.to_le_bytes());
        out[36..38].copy_from_slice(&self.fwd_lane_count.to_le_bytes());
        out[38..40].copy_from_slice(&self.bwd_lane_count.to_le_bytes());
        out[40] = self.type_;
        out[41] = self.speed_limit;
        out[42] = u8::from(self.oneway);
    }

    fn decode(b: &[u8]) -> ChainRec {
        let u32_at = |o: usize| u32::from_le_bytes(b[o..o + 4].try_into().expect("4 bytes"));
        let u16_at = |o: usize| u16::from_le_bytes(b[o..o + 2].try_into().expect("2 bytes"));
        ChainRec {
            pts_start: u64::from_le_bytes(b[0..8].try_into().expect("8 bytes")),
            pts_len: u32_at(8),
            first: u32_at(12),
            last: u32_at(16),
            dist_mm: u32_at(20),
            name_offset: u32_at(24),
            fwd_lane_off: u32_at(28),
            bwd_lane_off: u32_at(32),
            fwd_lane_count: u16_at(36),
            bwd_lane_count: u16_at(38),
            type_: b[40],
            speed_limit: b[41],
            oneway: b[42] != 0,
        }
    }
}

/// The pair of spill files, in a directory of their own so a build cannot
/// mistake them for pack output.
///
/// The two files can live on different filesystems, because they are read very
/// differently. `chains.hdr` is only ever streamed from the front, once per write
/// round, so a slow-but-roomy mount costs little. `chains.pts` is read *randomly*,
/// once per edge that stores a polyline, and that is the access pattern a
/// translation layer like drvfs punishes by an order of magnitude. At planet scale
/// the header is also the larger of the two — about 24 GB against 18 GB — so
/// separating them puts the bulk where there is room and the seeks where they are
/// cheap.
pub(crate) struct Spill {
    hdr_dir: PathBuf,
    pts_dir: PathBuf,
    hdr: PathBuf,
    pts: PathBuf,
}

impl Spill {
    /// Both files in one directory.
    #[cfg(test)]
    pub fn new(dir: &Path) -> Spill {
        Spill::split(dir, dir)
    }

    /// `chains.hdr` under `hdr_dir`, `chains.pts` under `pts_dir`.
    pub fn split(hdr_dir: &Path, pts_dir: &Path) -> Spill {
        let hdr_dir = hdr_dir.join("chain_spill");
        let pts_dir = pts_dir.join("chain_spill");
        Spill {
            hdr: hdr_dir.join("chains.hdr"),
            pts: pts_dir.join("chains.pts"),
            hdr_dir,
            pts_dir,
        }
    }

    fn make_dirs(&self) -> Result<()> {
        for dir in [&self.hdr_dir, &self.pts_dir] {
            std::fs::create_dir_all(dir)
                .map_err(|e| Error(format!("cannot create {}: {e}", dir.display())))?;
        }
        Ok(())
    }

    /// An incremental writer, for the chain pass to stream into.
    ///
    /// The alternative — accumulate every chain and every point, then write them —
    /// costs 11.7 GB on Europe and about 31 GB on a planet, which is most of the
    /// budget the spill exists to save. The pass sink is already single-threaded
    /// and called in chunk order, so writing from it keeps the files
    /// byte-deterministic.
    pub fn writer(&self) -> Result<SpillWriter> {
        self.make_dirs()?;
        Ok(SpillWriter {
            hdr: BufWriter::with_capacity(1 << 20, create(&self.hdr)?),
            pts: BufWriter::with_capacity(1 << 20, create(&self.pts)?),
            points: 0,
            chains: 0,
        })
    }

    /// Write `chains` and their points, resolving dense ids to coordinates.
    ///
    /// `pts_dense` must be laid out so each chain's range follows the one before
    /// it, which is how the chain pass builds it; the points then stream out in
    /// one sequential write and every `pts_start` carries over unchanged.
    pub fn write(
        &self,
        chains: &[Chain],
        pts_dense: &[u32],
        coords: &[geom::Pt],
    ) -> Result<()> {
        self.make_dirs()?;
        let mut hdr = BufWriter::new(create(&self.hdr)?);
        let mut rec = [0u8; CHAIN_REC_BYTES as usize];
        let mut expect_start = 0u64;
        for c in chains {
            let ids = c.pts(pts_dense);
            debug_assert_eq!(c.pts_start, expect_start, "chain points are not contiguous");
            expect_start += u64::from(c.pts_len);
            ChainRec {
                pts_start: c.pts_start,
                pts_len: c.pts_len,
                first: ids[0],
                last: ids[ids.len() - 1],
                dist_mm: c.dist_mm,
                name_offset: c.name_offset,
                fwd_lane_off: c.fwd_lane_off,
                bwd_lane_off: c.bwd_lane_off,
                fwd_lane_count: c.fwd_lane_count,
                bwd_lane_count: c.bwd_lane_count,
                type_: c.type_,
                speed_limit: c.speed_limit,
                oneway: c.oneway,
            }
            .encode(&mut rec);
            hdr.write_all(&rec).map_err(io_err)?;
        }
        hdr.flush().map_err(io_err)?;

        let mut out = BufWriter::new(create(&self.pts)?);
        for id in pts_dense {
            let (lat_e7, lon_e7) = coords[*id as usize];
            out.write_all(&lat_e7.to_le_bytes()).map_err(io_err)?;
            out.write_all(&lon_e7.to_le_bytes()).map_err(io_err)?;
        }
        out.flush().map_err(io_err)?;
        Ok(())
    }

    /// How many chains the spill holds, from the header file's size alone.
    pub fn chain_count(&self) -> Result<u64> {
        let len = std::fs::metadata(&self.hdr)
            .map_err(|e| Error(format!("cannot stat {}: {e}", self.hdr.display())))?
            .len();
        if len % CHAIN_REC_BYTES != 0 {
            return Err(Error(format!(
                "{} is {len} bytes, not a multiple of {CHAIN_REC_BYTES}",
                self.hdr.display()
            )));
        }
        Ok(len / CHAIN_REC_BYTES)
    }

    /// Read the whole spill back. The build itself streams; this is for tests,
    /// which need to see the two files as a unit to check they round-trip.
    #[cfg(test)]
    pub fn read_all(&self) -> Result<(Vec<ChainRec>, Vec<geom::Pt>)> {
        let count = self.chain_count()?;
        let mut hdr = self.headers()?;
        let mut chains = Vec::with_capacity(count as usize);
        while let Some(c) = hdr.next()? {
            chains.push(c);
        }

        let bytes = self.point_bytes()?;
        let mut src = BufReader::new(open(&self.pts)?);
        let mut buf = [0u8; CHAIN_PT_BYTES as usize];
        let mut pts = Vec::with_capacity((bytes / CHAIN_PT_BYTES) as usize);
        for _ in 0..bytes / CHAIN_PT_BYTES {
            src.read_exact(&mut buf).map_err(io_err)?;
            pts.push(decode_pt(&buf));
        }
        Ok((chains, pts))
    }

    /// Sequential reader over `chains.hdr`. Every stage after the chain pass is one
    /// of these: the degree count for the CSR, the target scatter, and the single
    /// round-assignment scan feeding [`Spill::headers_mapped`](Self::headers_mapped).
    pub fn headers(&self) -> Result<HeaderReader> {
        Ok(HeaderReader {
            src: BufReader::with_capacity(1 << 20, open(&self.hdr)?),
            left: self.chain_count()?,
        })
    }

    /// Random-access reader over `chains.hdr`, for the write rounds.
    ///
    /// Records are fixed-stride (`CHAIN_REC_BYTES` each), so chain `i` starts
    /// at byte `i * CHAIN_REC_BYTES` — no index, no scan. The write used to
    /// re-stream the whole file once per round (3 rounds on a continent: 3
    /// full header passes); now one assignment scan records each chain's
    /// round and every round gathers only its chains through this mapping.
    /// Page-cached after the assignment scan, so the gather faults almost
    /// nothing new.
    pub fn headers_mapped(&self) -> Result<HeaderMap> {
        Ok(HeaderMap { map: crate::mem::Mapped::open(&self.hdr).map_err(Error)? })
    }

    /// Random-access reader over `chains.pts`.
    ///
    /// The write rounds visit chains in edge order, not chain order, so this is the
    /// one place the spill is not read sequentially. The file is mapped once and
    /// read as slices: one seek per edge into a file the page cache has largely
    /// seen already became zero syscalls per edge.
    pub fn points(&self) -> Result<PointReader> {
        Ok(PointReader { map: crate::mem::Mapped::open(&self.pts).map_err(Error)? })
    }

    #[cfg(test)]
    fn point_bytes(&self) -> Result<u64> {
        let bytes = std::fs::metadata(&self.pts)
            .map_err(|e| Error(format!("cannot stat {}: {e}", self.pts.display())))?
            .len();
        if bytes % CHAIN_PT_BYTES != 0 {
            return Err(Error(format!(
                "{} is {bytes} bytes, not a multiple of {CHAIN_PT_BYTES}",
                self.pts.display()
            )));
        }
        Ok(bytes)
    }

    /// Best-effort cleanup, also run on drop. A leftover spill is tens of
    /// gigabytes, but failing the build over an undeletable temporary would be
    /// worse than leaving it.
    pub fn remove(&self) {
        let _ = std::fs::remove_file(&self.hdr);
        let _ = std::fs::remove_file(&self.pts);
        // Only removes them if empty, so a shared parent survives.
        let _ = std::fs::remove_dir(&self.hdr_dir);
        let _ = std::fs::remove_dir(&self.pts_dir);
    }
}

impl Drop for Spill {
    /// The spill is a temporary, so its lifetime is the handle's. Without this, any
    /// error between writing it and the end of the build would strand it: the one
    /// path where a leftover is most likely is also the one nobody is watching.
    fn drop(&mut self) {
        self.remove();
    }
}

fn create(path: &Path) -> Result<File> {
    File::create(path).map_err(|e| Error(format!("cannot write {}: {e}", path.display())))
}

fn open(path: &Path) -> Result<File> {
    File::open(path).map_err(|e| Error(format!("cannot read {}: {e}", path.display())))
}

fn decode_pt(b: &[u8]) -> geom::Pt {
    (
        i32::from_le_bytes(b[0..4].try_into().expect("4 bytes")),
        i32::from_le_bytes(b[4..8].try_into().expect("4 bytes")),
    )
}

/// Streams `chains.hdr` and `chains.pts` from the front.
pub(crate) struct SpillWriter {
    hdr: BufWriter<File>,
    pts: BufWriter<File>,
    /// Points written so far, which is the next chain's `pts_start`.
    pub(crate) points: u64,
    chains: u64,
}

impl SpillWriter {
    /// Append one chain and its polyline.
    ///
    /// `ids` are the chain's dense node ids in order; their coordinates go to
    /// `chains.pts` and only the two endpoint *ids* are kept, in the header, since
    /// coordinates cannot be mapped back to a node.
    pub fn push(&mut self, c: &Chain, ids: &[u32], coords: &[geom::Pt]) -> Result<()> {
        debug_assert_eq!(ids.len(), c.pts_len as usize);
        let mut rec = [0u8; CHAIN_REC_BYTES as usize];
        ChainRec {
            pts_start: self.points,
            pts_len: c.pts_len,
            first: ids[0],
            last: ids[ids.len() - 1],
            dist_mm: c.dist_mm,
            name_offset: c.name_offset,
            fwd_lane_off: c.fwd_lane_off,
            bwd_lane_off: c.bwd_lane_off,
            fwd_lane_count: c.fwd_lane_count,
            bwd_lane_count: c.bwd_lane_count,
            type_: c.type_,
            speed_limit: c.speed_limit,
            oneway: c.oneway,
        }
        .encode(&mut rec);
        self.hdr.write_all(&rec).map_err(io_err)?;
        for id in ids {
            let (lat_e7, lon_e7) = coords[*id as usize];
            self.pts.write_all(&lat_e7.to_le_bytes()).map_err(io_err)?;
            self.pts.write_all(&lon_e7.to_le_bytes()).map_err(io_err)?;
        }
        self.points += u64::from(c.pts_len);
        self.chains += 1;
        Ok(())
    }

    /// Append chains already encoded by [`build`]'s workers: `hdr` holds whole
    /// header records whose `pts_start` the caller has rebased onto
    /// [`SpillWriter::points`], and `pts` the `points` coordinates they cover.
    /// Same bytes as [`SpillWriter::push`] per chain.
    pub fn push_encoded(&mut self, hdr: &[u8], pts: &[u8], points: u64) -> Result<()> {
        debug_assert_eq!(hdr.len() as u64 % CHAIN_REC_BYTES, 0);
        debug_assert_eq!(pts.len() as u64, points * CHAIN_PT_BYTES);
        self.hdr.write_all(hdr).map_err(io_err)?;
        self.pts.write_all(pts).map_err(io_err)?;
        self.points += points;
        self.chains += hdr.len() as u64 / CHAIN_REC_BYTES;
        Ok(())
    }

    /// Flush both files and report how many chains were written.
    pub fn finish(mut self) -> Result<u64> {
        self.hdr.flush().map_err(io_err)?;
        self.pts.flush().map_err(io_err)?;
        Ok(self.chains)
    }
}

/// Streams `chains.hdr` from the front.
pub(crate) struct HeaderReader {
    src: BufReader<File>,
    left: u64,
}

impl HeaderReader {
    #[allow(clippy::should_implement_trait)]
    pub fn next(&mut self) -> Result<Option<ChainRec>> {
        if self.left == 0 {
            return Ok(None);
        }
        let mut rec = [0u8; CHAIN_REC_BYTES as usize];
        self.src.read_exact(&mut rec).map_err(io_err)?;
        self.left -= 1;
        Ok(Some(ChainRec::decode(&rec)))
    }
}

/// Random-access view over `chains.hdr`. See [`Spill::headers_mapped`].
pub(crate) struct HeaderMap {
    map: crate::mem::Mapped,
}

impl HeaderMap {
    /// How many records the mapping holds, from its length alone — the same
    /// width check [`Spill::chain_count`] applies to the file.
    pub fn len(&self) -> Result<u64> {
        let len = self.map.len() as u64;
        if len % CHAIN_REC_BYTES != 0 {
            return Err(Error(format!(
                "chains.hdr is {len} bytes, not a multiple of {CHAIN_REC_BYTES}"
            )));
        }
        Ok(len / CHAIN_REC_BYTES)
    }

    /// Decode chain `i`. Bounds-checked: a short mapping fails rather than
    /// slicing past the end.
    pub fn get(&self, i: u64) -> Result<ChainRec> {
        let at = i.checked_mul(CHAIN_REC_BYTES).expect("chain index * stride fits u64");
        let end = at + CHAIN_REC_BYTES;
        if end > self.map.len() as u64 {
            return Err(Error(format!("chains.hdr ends before chain {i}")));
        }
        let (at, end) = (at as usize, end as usize);
        let mut rec = [0u8; CHAIN_REC_BYTES as usize];
        rec.copy_from_slice(&self.map[at..end]);
        Ok(ChainRec::decode(&rec))
    }
}

/// Reads one chain's polyline out of `chains.pts`.
pub(crate) struct PointReader {
    map: crate::mem::Mapped,
}

/// The shared mapping behind a [`PointReader`]: pool workers read the same
/// bytes through [`PointReader::read_shared`] with no lock and no cursor.
pub(crate) fn points_map(reader: &PointReader) -> &crate::mem::Mapped {
    &reader.map
}

impl PointReader {
    /// Replace `out` with the `len` points starting at point index `start`.
    ///
    /// A slice of one persistent mapping, not a positional read: the write
    /// rounds visit chains in edge order, not chain order, so a `File` paid
    /// one syscall per edge (~300 M at planet scale) for a few dozen bytes
    /// each. The mapping faults the same pages with zero syscalls, no
    /// per-edge buffer zeroing, and no cursor to keep — which is also what a
    /// parallel writer would need first.
    ///
    /// Short reads fail exactly as the positional path did (`UnexpectedEof`
    /// naming the file), so a truncated spill surfaces here rather than as a
    /// wrong polyline downstream.
    pub fn read(&mut self, start: u64, len: u32, out: &mut Vec<geom::Pt>) -> Result<()> {
        Self::read_from(&self.map, start, len, out)
    }

    /// [`read`](Self::read) over an explicit mapping: the shared-read form.
    ///
    /// `read` takes `&mut self` for the historical reason that the reader
    /// used to hold a cursor; the mapping itself is never mutated, so pool
    /// workers share one `&Mapped` and read through this instead. Same
    /// slices, same errors, no lock.
    pub fn read_shared(map: &crate::mem::Mapped, start: u64, len: u32, out: &mut Vec<geom::Pt>) -> Result<()> {
        Self::read_from(map, start, len, out)
    }

    fn read_from(map: &crate::mem::Mapped, start: u64, len: u32, out: &mut Vec<geom::Pt>) -> Result<()> {
        out.clear();
        if len == 0 {
            return Ok(());
        }
        let at = start as u128 * CHAIN_PT_BYTES as u128;
        let bytes = len as u128 * CHAIN_PT_BYTES as u128;
        if at + bytes > map.len() as u128 {
            return Err(Error(format!("chains.pts ended mid-chain")));
        }
        let (at, bytes) = (at as usize, bytes as usize);
        out.extend(map[at..at + bytes].chunks_exact(CHAIN_PT_BYTES as usize).map(decode_pt));
        Ok(())
    }
}

fn io_err(e: std::io::Error) -> Error {
    Error(e.to_string())
}
