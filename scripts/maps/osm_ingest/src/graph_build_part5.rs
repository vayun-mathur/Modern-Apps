/// `lanes.bin`, as a sparse index: `[ u32 n ][ (u32 edge_idx, u32 off) x (n + 1) ]`
/// then the `u16` mask blob.
///
/// `turn:lanes` is rare — 2.9 M of a planet's 1.07 G directed edges carry any — so
/// a dense `u64` per edge made 99.8% of an 8.60 GB file describe edges with no
/// lanes. Listing only the edges that have them is ~23 MB of index plus a 13 MB
/// blob. Entry *i*'s masks run from its offset to entry *i+1*'s, so the trailing
/// sentinel gives the last edge its length and the blob its total.
///
/// The round-partitioned writer cannot know `n` up front, so the blob streams to a
/// scratch file while the index accumulates in memory (23 MB at planet scale) and
/// the header plus index are written in front of it at the end.
struct LaneFile {
    path: PathBuf,
    blob_path: PathBuf,
    blob: BufWriter<File>,
    index: Vec<(u32, u32)>,
    blob_len: u64,
}

impl LaneFile {
    fn create(dir: &Path, name: &str) -> Result<LaneFile> {
        let blob_path = dir.join(format!("{name}.blob"));
        Ok(LaneFile {
            path: dir.join(name),
            blob: BufWriter::new(create(&blob_path)?),
            blob_path,
            index: Vec::new(),
            blob_len: 0,
        })
    }

    /// Record `bytes` as edge `idx`'s masks. Only called for edges that have any,
    /// and always with an ascending `idx`, because the rounds ascend and each round
    /// writes its edges in index order.
    fn push(&mut self, idx: u64, bytes: &[u8]) -> Result<()> {
        debug_assert!(!bytes.is_empty(), "an empty lane blob would read back as absent");
        debug_assert!(
            self.index.last().is_none_or(|(last, _)| *last < idx as u32),
            "lane index entries must ascend by edge index"
        );
        let off = self.blob_off()?;
        self.index.push((idx as u32, off));
        self.blob.write_all(bytes).map_err(io_err)?;
        self.blob_len += bytes.len() as u64;
        Ok(())
    }

    /// The current blob length as the `u32` an index entry stores.
    fn blob_off(&self) -> Result<u32> {
        u32::try_from(self.blob_len).map_err(|_| {
            Error(format!(
                "the lane blob reached {} bytes, past the {} a u32 offset can address",
                self.blob_len,
                u32::MAX
            ))
        })
    }

    /// Write the header and index, then the blob behind it. Returns the finished
    /// file's size.
    fn finish(mut self) -> Result<u64> {
        self.blob.flush().map_err(io_err)?;
        let n = u32::try_from(self.index.len()).map_err(|_| {
            Error(format!(
                "{} lane-bearing edge(s) is past the {} the index header can count",
                self.index.len(),
                u32::MAX
            ))
        })?;
        let mut out = BufWriter::new(create(&self.path)?);
        out.write_all(&n.to_le_bytes()).map_err(io_err)?;
        for (idx, off) in &self.index {
            out.write_all(&idx.to_le_bytes()).map_err(io_err)?;
            out.write_all(&off.to_le_bytes()).map_err(io_err)?;
        }
        // The sentinel's edge index is `u32::MAX` rather than a real one: it exists
        // only to give the last entry a length, and the reader's binary search
        // covers the first `n` entries, so no valid index may collide with it. Its
        // offset is the blob length, which is also how the reader length-validates
        // the blob.
        let blob_end = self.blob_off()?;
        out.write_all(&u32::MAX.to_le_bytes()).map_err(io_err)?;
        out.write_all(&blob_end.to_le_bytes()).map_err(io_err)?;

        let mut src = BufReader::with_capacity(1 << 20, open(&self.blob_path)?);
        std::io::copy(&mut src, &mut out).map_err(io_err)?;
        out.flush().map_err(io_err)?;
        Ok(4 + u64::from(n + 1) * 8 + self.blob_len)
    }
}

impl Drop for LaneFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.blob_path);
    }
}

fn create(path: &Path) -> Result<File> {
    File::create(path).map_err(|e| Error(format!("cannot write {}: {e}", path.display())))
}

fn open(path: &Path) -> Result<File> {
    File::open(path).map_err(|e| Error(format!("cannot read {}: {e}", path.display())))
}

fn io_err(e: std::io::Error) -> Error {
    Error(e.to_string())
}

/// Parse the tool's command line:
/// `road_graph IN.osm.pbf [--out DIR] [--reference-collapse] [--rounds N]
/// [--spill-dir DIR] [--spill-pts-dir DIR] [--dem DATASET.mdem] [--region california|world] [--stats]`.
pub fn parse_args(
    args: &[String],
) -> std::result::Result<(PathBuf, PathBuf, Options), String> {
    let mut input: Option<PathBuf> = None;
    let mut out = PathBuf::from("map_data");
    // The CLI defaults to the streaming within-way collapse: it is the only path
    // whose working set fits a planet build on a commodity box. The reference
    // whole-graph collapse stays reachable for cross-checking via
    // --reference-collapse. The library default (`Options::default`) is unchanged.
    let mut opts = Options { within_way_chains: true, ..Default::default() };
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--out" | "-o" => {
                i += 1;
                let dir = args.get(i).ok_or_else(|| "--out needs a directory".to_string())?;
                out = PathBuf::from(dir);
            }
            "--reference-collapse" => opts.within_way_chains = false,
            "--stats" => opts.stats = true,
            "--threads" => {
                i += 1;
                let n = args
                    .get(i)
                    .ok_or_else(|| "--threads needs a count".to_string())?;
                opts.threads = Some(crate::par::parse_threads(n)?);
            }
            "--spill-dir" => {
                i += 1;
                let dir = args
                    .get(i)
                    .ok_or_else(|| "--spill-dir needs a directory".to_string())?;
                opts.spill_dir = Some(PathBuf::from(dir));
            }
            "--spill-pts-dir" => {
                i += 1;
                let dir = args
                    .get(i)
                    .ok_or_else(|| "--spill-pts-dir needs a directory".to_string())?;
                opts.spill_pts_dir = Some(PathBuf::from(dir));
            }
            "--rounds" => {
                i += 1;
                let n = args.get(i).ok_or_else(|| "--rounds needs a count".to_string())?;
                opts.rounds = n
                    .parse::<u32>()
                    .ok()
                    .filter(|n| *n > 0)
                    .ok_or_else(|| format!("--rounds wants a positive count, not {n}"))?;
            }
            "--dem" => {
                i += 1;
                let path = args
                    .get(i)
                    .ok_or_else(|| "--dem needs a .mdem dataset path".to_string())?;
                opts.dem = Some(PathBuf::from(path));
            }
            "--region" => {
                i += 1;
                let value = args
                    .get(i)
                    .ok_or_else(|| "--region needs `california`, `na` or `world`".to_string())?;
                let region = crate::region::Region::parse(value).map_err(|e| e.0)?;
                opts.bbox = region.bbox();
            }
            a if a.starts_with('-') => return Err(format!("unknown option: {a}")),
            a => {
                if input.is_some() {
                    return Err(format!("unexpected extra argument: {a}"));
                }
                input = Some(PathBuf::from(a));
            }
        }
        i += 1;
    }
    Ok((
        input.ok_or_else(|| "missing IN.osm.pbf".to_string())?,
        out,
        opts,
    ))
}
