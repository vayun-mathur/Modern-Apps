/// Consecutive chunks one lane claims at a time.
///
/// **One, because dealing longer runs was tried and measured as nothing.** The reasoning for runs is
/// good and the measurement does not support it: single-chunk round-robin means each of sixteen
/// lanes walks the file in ~9 KB hops sixteen chunks apart, which is the pattern readahead exists to
/// defeat, and the spill was moving at 643 MB/s on hardware that does several gigabytes. Dealing
/// runs of 64 consecutive chunks — ~600 KB, a streaming read — changed north-america tiling from
/// 807.0 s to 813.1 s and the reader from 292.6 s to 298.4 s. Noise, and slightly the wrong way.
///
/// So the reads were never the problem, and the throughput figure was measuring something else: the
/// reader was not waiting on disk, it was busy discarding features (see [`ZoomReader::spawn`]). Kept
/// as a named constant of 1 rather than deleted, because the shape of the partition is worth being
/// explicit about and because the next person to look at a 643 MB/s number will have the same idea.
#[cfg_attr(not(test), allow(dead_code))]
const PREFETCH_RUN: usize = 1;

/// Chunks one lane may run ahead by.
///
/// Bounds the prefetch at `prefetch_lanes() * (PREFETCH_DEPTH + 1) * 64` features — about 34 K at
/// constants above, which is small against the 512 Ki *vertices* the tiler batches immediately
/// downstream. Depth is here to absorb a slow chunk, not to buffer.
#[cfg_attr(not(test), allow(dead_code))]
const PREFETCH_DEPTH: usize = 32;

/// Reads the chunks one zoom needs and seeks past the rest.
#[cfg_attr(not(test), allow(dead_code))]
pub struct ZoomReader {
    /// One receiver per lane. Wanted-chunk `k` is decoded by lane `(k / PREFETCH_RUN) % lanes.len()`,
    /// so walking the lanes a run at a time reproduces file order exactly. Empty when there is
    /// nothing to read.
    lanes: Vec<std::sync::mpsc::Receiver<Decoded>>,
    /// Joined on drop, after the receivers are dropped: a lane blocked on a full channel exits when
    /// its receiver goes, so the order of those two steps is what makes the join finite.
    lanes_running: Vec<std::thread::JoinHandle<()>>,
    /// Chunks taken so far, which is also what decides the lane to take the next one from.
    at: usize,
    total: usize,
    records: Vec<Feature>,
}

impl Drop for ZoomReader {
    fn drop(&mut self) {
        // Receivers first. A lane parked on `send` to a full channel wakes with an error the moment
        // its receiver is gone, and only then is the join below guaranteed to finish -- which
        // matters on the error path, where the tiler abandons a zoom with every lane backed up.
        self.lanes.clear();
        for lane in self.lanes_running.drain(..) {
            let _ = lane.join();
        }
    }
}

#[cfg_attr(not(test), allow(dead_code))]
impl ZoomReader {
    /// Start a lane per [`prefetch_lanes`] over `wanted`, dealt round-robin.
    ///
    /// # Why dedicated threads and not the pool
    ///
    /// This decode is 62.6 s of a 175 s us-west build, all of it on one thread, and
    /// [`NormalizedChunks::read_into`] is an obvious candidate for the pool: it takes `&self`, reads
    /// its own byte range positionally, and is pure. Decoding 512 chunks at a time across the pool
    /// was tried and made the map phase **62% slower** — 102.4 s to 165.5 s — for byte-identical
    /// output.
    ///
    /// The reason is what this reader is: it feeds the clipping workers through a channel. Handing
    /// the refill to the same pool queues it *behind* the clipping tasks it exists to supply, so the
    /// reader blocks waiting on a pool that is busy with work only the reader can extend. The
    /// overlap between reading and clipping — the entire point of the arrangement — disappears, and
    /// both halves get slower.
    ///
    /// So: threads of their own, outside the pool, which is what [`crate::tiler`] does for the
    /// reader itself and for the same reason.
    ///
    /// # Why the lanes drop features rather than hand them over
    ///
    /// A feature below `z`'s own floor is dropped **here**, and that is not tidiness — it is most of
    /// what this reader costs. The chunk index skips a chunk only when *every* feature in it is too
    /// deep, and a chunk is 64 features in file order with no relationship between their
    /// `min_zoom`s, so at a shallow zoom nearly every chunk survives the index while holding almost
    /// nothing that zoom draws. z5 of north-america reads 590,002 chunks — 37.8 M features — to
    /// produce 156 tiles.
    ///
    /// Left to the consumer, as it was, that is roughly **1.06 billion features across the build**
    /// pulled one `next()` at a time and then discarded. No lane count fixes it, because it is not
    /// the lanes' work: raising lanes from 4 to 16 took the reader 496.8 s to 292.6 s and then
    /// stopped, and dealing sequential runs instead of strided chunks changed nothing at all. The
    /// filter is per-feature and order-preserving, so moving it up here removes exactly the features
    /// the tiler removed, in a place where sixteen threads share the cost.
    ///
    /// # Why the order cannot move
    ///
    /// Lane `j` takes wanted-chunks `j`, `j + n`, `j + 2n`, ... and a lane's own channel is FIFO, so
    /// the `k`th chunk out of lane `k % n` is wanted-chunk `k`. Reading the lanes round-robin is
    /// therefore the wanted list in order, which is the file in order, which is what the archive's
    /// feature ordering rests on. Nothing here depends on which lane finishes first; a lane that
    /// races ahead fills its channel and parks.
    fn spawn(inner: NormalizedChunks, wanted: Vec<usize>, z: u8) -> Result<ZoomReader> {
        let total = wanted.len();
        if total == 0 {
            return Ok(ZoomReader {
                lanes: Vec::new(),
                lanes_running: Vec::new(),
                at: 0,
                total: 0,
                records: Vec::new(),
            });
        }
        // One handle shared rather than one file open per lane: `read_into` is positional and
        // documented to serve every thread from one handle.
        let inner = std::sync::Arc::new(inner);
        // No more lanes than there are runs to deal, or the tail lanes are threads started to do
        // nothing.
        let count = prefetch_lanes().min(total.div_ceil(PREFETCH_RUN));
        let mut lanes = Vec::with_capacity(count);
        let mut lanes_running = Vec::with_capacity(count);
        for lane in 0..count {
            let (send, receive) = std::sync::mpsc::sync_channel::<Decoded>(PREFETCH_DEPTH);
            let inner = std::sync::Arc::clone(&inner);
            // This lane's runs, flattened back into the chunks it will read: sequential within a
            // run, which is the whole point of dealing runs rather than chunks.
            let mine: Vec<usize> = wanted
                .chunks(PREFETCH_RUN)
                .skip(lane)
                .step_by(count)
                .flatten()
                .copied()
                .collect();
            let running = std::thread::Builder::new()
                .name(format!("mamaps-spill-{lane}"))
                .spawn(move || {
                    // One byte buffer and one record buffer for the lane, reused across its
                    // chunks. What is sent is a third `Vec`, allocated per chunk -- one allocation
                    // per 64 features, against the per-feature geometry the decode allocates
                    // anyway.
                    let mut scratch = Vec::new();
                    let mut records = Vec::new();
                    for chunk in mine {
                        let decoded = inner
                            .read_into(chunk, &mut scratch, &mut records)
                            .map_err(|e| osm_ingest::proto::Error(e.to_string()))
                            // Reversed here rather than by the consumer, which takes from the
                            // back: same reason as everything else in this closure, it is work and
                            // it does not have to be the reader's.
                            .and_then(|()| {
                                let mut out = Vec::with_capacity(records.len());
                                for record in records.drain(..).rev() {
                                    let feature = feature_of(record)?;
                                    // The zoom window, applied where the work is spread. See this
                                    // function's docs: at a shallow zoom this is nearly the whole
                                    // Floor is the feature's own `min_zoom`; ceiling is the
                                    // layer cap (`MAX_ZOOM_PER_LAYER`) — boundaries stop at
                                    // z13, everything else tiles to the archive max.
                                    if z >= feature.class.min_zoom
                                        && z <= crate::schema::max_zoom_for_layer(feature.class.layer)
                                    {
                                        out.push(feature);
                                    }
                                }
                                Ok(out)
                            });
                        // Stop on the first error: the consumer surfaces it and abandons the zoom,
                        // and carrying on would only queue work behind a build that is already
                        // failing.
                        let stop = decoded.is_err();
                        if send.send(decoded).is_err() || stop {
                            return;
                        }
                    }
                })
                .map_err(|e| {
                    osm_ingest::proto::Error(format!("cannot start spill prefetch lane {lane}: {e}"))
                })?;
            lanes.push(receive);
            lanes_running.push(running);
        }
        Ok(ZoomReader { lanes, lanes_running, at: 0, total, records: Vec::new() })
    }

    /// How many spill chunks this zoom will read, and how many it has.
    ///
    /// Exposed so the tiler can show progress: the map phase is the longest in the build and was
    /// silent for all of it, which on a continent is forty minutes of no output at all.
    #[allow(dead_code)]
    pub fn chunks(&self) -> (usize, usize) {
        (self.at.min(self.total), self.total)
    }

    /// The next feature, or `None` once this zoom's chunks are exhausted.
    pub fn next(&mut self) -> Result<Option<Feature>> {
        loop {
            // From the back, which is why the lane reversed the chunk before sending it.
            if let Some(feature) = self.records.pop() {
                return Ok(Some(feature));
            }
            if self.at >= self.total {
                return Ok(None);
            }
            let lane = self.at % self.lanes.len();
            self.at += 1;
            // A lane that hung up before delivering all of its chunks panicked: it returns only
            // after sending every one of them or after sending an error, and an error arrives as a
            // value rather than a hangup.
            let decoded = self.lanes[lane].recv().map_err(|_| {
                osm_ingest::proto::Error(format!("spill prefetch lane {lane} stopped early"))
            })?;
            self.records = decoded?;
        }
    }
}

#[cfg_attr(not(test), allow(dead_code))]
pub struct Reader {
    inner: NormalizedReader,
}

impl Reader {
    /// The next feature, or `None` at the end of the file.
    ///
    /// A record whose class property is missing or is not an integer is a corrupt file rather than a
    /// feature to skip: everything in here was written by [`Sink::push`] one run ago, so anything
    /// else means the file is not the one we wrote.
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn next(&mut self) -> Result<Option<Feature>> {
        let Some(record) =
            self.inner.next().map_err(|e| osm_ingest::proto::Error(e.to_string()))?
        else {
            return Ok(None);
        };
        Ok(Some(feature_of(record)?))
    }
}

/// Classified ways on disk: the second row of the table above, and the second thing stage A no
/// longer holds.
///
/// # Why a file rather than the `HashMap<i64, Way>` this replaces
///
/// The map cost ~2.7 GB on California, nearly all of it node refs, and every read of it was
/// sequential in ascending id order — [`crate::extract`] sorted the keys precisely so the archive
/// would not depend on hash order. It was a map only because a relation reaches its member ways by
/// id, in whatever order it lists them, and that one random access is served far more cheaply by
/// keeping *only* the relation members resident: California has 63 156 relations against several
/// million classified ways.
///
/// # Why nothing has to be sorted on the way back
///
/// A `.osm.pbf` stores ways sorted by id, and [`osm_ingest::pbf::run_pass_sink`] hands finished
/// chunks to its sink in file order. Appending as pass 1 goes therefore produces a file already in
/// the order materialisation wants, and the sort disappears along with the map.
///
/// [`push`] **enforces** that ordering rather than trusting it. An id that does not advance would
/// silently reorder the archive, and a byte-identical rebuild is the only evidence this pipeline has
/// that a change to stage A preserved its meaning — so a file that is not sorted is an error here,
/// where it is one line of output, rather than a diff in a 655 MB archive.
///
/// # The encoding
///
/// One record per way, every field a varint, nothing aligned:
///
/// | field | encoding |
/// |---|---|
/// | id | zigzag varint of the gap from the previous record's id |
/// | class | unsigned varint of [`pack`]'s integer |
/// | ref count | unsigned varint |
/// | refs | zigzag varint of each ref's gap from the one before it, the first from zero |
/// | name | unsigned varint byte length, then that many UTF-8 bytes (zero when nameless) |
/// | lane count | unsigned varint |
/// | turn masks | a count then each `u16`, forward then backward |
/// | carriageway | unsigned varint of the packed forward/backward/divider word |
/// | building | a presence byte, then three packed varints when set |
///
/// Delta coding earns its keep rather than being a flourish. The refs are ~150 M ids on California
/// and the file is read twice — once to collect the node ids pass 3 must resolve, once to build
/// geometry — so a flat 8 bytes each would be 1.2 GB read twice. Consecutive nodes of a way were
/// almost always created in one editing session and so have near-consecutive ids, which is exactly
/// what a delta collapses to one or two bytes.
///
/// [`push`]: WaySink::push
pub struct WaySink {
    out: WayOut,
    /// One record's bytes, reused. Several million records, so a `Vec` per record would be several
    /// million allocations for a buffer that is dead a line later.
    record: Vec<u8>,
    last_id: i64,
    count: u64,
    refs: u64,
    max_ref: i64,
    /// Bytes staged so far: the next record's offset, for the block index.
    at: u64,
    /// The block index over the stream. See [`WayBlockLoc`].
    blocks: Vec<WayBlockLoc>,
}

/// Where a [`WaySink`] stages its bytes: a file, or anonymous memory.
enum WayOut {
    File(BufWriter<File>),
    Anon(tile_build::anon::AnonStore),
}

impl WaySink {
    pub fn create(path: &Path) -> Result<WaySink> {
        let file = File::create(path)
            .map_err(|e| Error(format!("cannot create {}: {e}", path.display())))?;
        Ok(WaySink {
            // A megabyte, because the writes are a few dozen bytes each and there are millions.
            out: WayOut::File(BufWriter::with_capacity(1 << 20, file)),
            record: Vec::new(),
            last_id: 0,
            count: 0,
            refs: 0,
            max_ref: 0,
            at: 0,
            blocks: Vec::new(),
        })
    }

    /// Anonymous twin of [`create`](Self::create): same records in pagefile-backed
    /// memory, no file. `path` names nothing — it only rides
    /// along for error messages. Pair with [`WaySink::finish_anon`].
    pub fn create_anon(path: &Path) -> Result<WaySink> {
        Self::create_planned(path, osm_ingest::mem::SpillPlan::Anon)
    }

    /// Create with an explicit staging plan. See [`Sink::create_planned`]: the budget gate picks
    /// `File` when anon commit does not fit, and `File` stages through the `.tmp` at `path`,
    /// removed after materialise like every other scratch file. Pair with
    /// [`finish_either`](Self::finish_either).
    pub fn create_planned(path: &Path, plan: osm_ingest::mem::SpillPlan) -> Result<WaySink> {
        let out = match plan {
            osm_ingest::mem::SpillPlan::Anon => {
                WayOut::Anon(tile_build::anon::AnonStore::new())
            }
            osm_ingest::mem::SpillPlan::File => {
                eprintln!("  [stage A] ways spill -> {} (file backend: commit budget)", path.display());
                let file = File::create(path)
                    .map_err(|e| Error(format!("cannot create {}: {e}", path.display())))?;
                // A megabyte, because the writes are a few dozen bytes each and there are millions.
                WayOut::File(BufWriter::with_capacity(1 << 20, file))
            }
        };
        Ok(WaySink {
            out,
            record: Vec::new(),
            last_id: 0,
            count: 0,
            refs: 0,
            max_ref: 0,
            at: 0,
            blocks: Vec::new(),
        })
    }

    /// Append one classified way. `id` must be greater than the previous call's.
    ///
    /// `name` is the display label for `places`/`poi` ways, `None` for every other layer. Written
    /// as a length-prefixed string per record — zero bytes for the nameless, which is nearly all
    /// of them. `lane_count`, `turn_fwd`, `turn_bwd` and `carriageway` are a road's lane count,
    /// per-lane turn masks and directional split, empty for every other layer and for a road with
    /// no lane tags; a handful of trailing varints per record, which the scratch file (removed
    /// after materialise) affords.
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
        self.record.clear();
        put_svarint(&mut self.record, id.wrapping_sub(self.last_id));
        let max_ref = encode_way_tail(
            &mut self.record,
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
        // Moved out rather than cloned: `out_write` takes `&mut self`, so `&self.record`
        // cannot borrow across the call, and a per-way clone is a memcpy per record over
        // millions of records. The buffer is restored below with its capacity intact, so the
        // `clear` at the top of the next `push` still reuses the allocation.
        let record = std::mem::take(&mut self.record);
        let result = self.out_write(&record).map_err(|e| {
            Error(format!("cannot write the ways spill: {e}"))
        });
        let len = record.len() as u64;
        self.record = record;
        result?;
        self.note_block(len, 1);
        self.last_id = id;
        self.count += 1;
        self.refs += refs.len() as u64;
        Ok(())
    }

    /// Append a run of ways a worker encoded ([`WayBlock`]). Writes exactly what `push` would
    /// have written for those ways one at a time: the block's first id varint is re-encoded
    /// against the last id this sink saw, the rest is copied verbatim. The ordering rule is
    /// enforced across the block boundary just as `push` enforces it between records.
    pub fn push_block(&mut self, block: &WayBlock) -> Result<()> {
        if block.count == 0 {
            return Ok(());
        }
        if self.count > 0 && block.first_id <= self.last_id {
            return err(unsorted_ways(block.first_id, self.last_id));
        }
        self.record.clear();
        put_svarint(&mut self.record, block.first_id.wrapping_sub(self.last_id));
        let head = std::mem::take(&mut self.record);
        let wrote = self
            .out_write(&head)
            .and_then(|()| self.out_write(&block.bytes[block.head_len..]));
        let len = (head.len() + block.bytes.len() - block.head_len) as u64;
        self.record = head;
        wrote?;
        self.note_block(len, block.count);
        self.last_id = block.last_id;
        self.count += block.count;
        self.refs += block.refs;
        self.max_ref = self.max_ref.max(block.max_ref);
        Ok(())
    }

    /// Index `len` bytes / `ways` records just written at `self.at`, whose first delta is taken
    /// from `self.last_id` (still the pre-write value). Coalesced into the previous entry while
    /// that stays within [`WAY_BLOCK_WAYS`]: a run of consecutive records decodes from the first
    /// one's base however it was written, so merging entries changes nothing but the index size.
    fn note_block(&mut self, len: u64, ways: u64) {
        match self.blocks.last_mut() {
            Some(last) if last.offset + last.len == self.at && last.ways + ways <= WAY_BLOCK_WAYS => {
                last.len += len;
                last.ways += ways;
            }
            _ => self.blocks.push(WayBlockLoc {
                offset: self.at,
                len,
                start_last_id: self.last_id,
                ways,
            }),
        }
        self.at += len;
    }

    fn out_write(&mut self, bytes: &[u8]) -> Result<()> {
        match &mut self.out {
            WayOut::File(f) => f.write_all(bytes).map_err(|e| {
                Error(format!("cannot write the ways spill: {e}"))
            }),
            WayOut::Anon(a) => a.push(bytes).map(|_| ()).map_err(|e| {
                Error(format!("cannot stage the ways spill: {e}"))
            }),
        }
    }

    /// Flush, and report how many ways were written, how many node refs they hold between them, and
    /// the largest of those refs.
    ///
    /// None of the three is a statistic. [`crate::extract`] reads this file back to collect the node
    /// ids pass 3 must resolve, and it picks between two ways of doing that on the ref count: below a
    /// budget it sizes one exact vector (grown by extension a 200 M-id vector doubles into 2.1 GB of
    /// capacity, and the realloc that gets it there holds the old and new allocations at once), and
    /// above it sizes a bitset from `max_ref`. Both numbers are free here, where every ref is already
    /// being walked to encode it, and neither is recoverable later without a second pass.
    pub fn finish(mut self) -> Result<WayCounts> {
        match &mut self.out {
            WayOut::File(f) => f.flush().map_err(|e| Error(format!("cannot flush the ways spill: {e}")))?,
            WayOut::Anon(a) => a.finish(),
        }
        Ok(WayCounts {
            ways: self.count,
            refs: self.refs,
            max_ref: self.max_ref,
            blocks: std::mem::take(&mut self.blocks),
        })
    }

    /// Anonymous twin of [`finish`](Self::finish): seals the pagefile-backed
    /// spill and hands back the counts plus the staged store. Errors on a
    /// file-backed sink.
    pub fn finish_anon(mut self) -> Result<(WayCounts, tile_build::anon::AnonStore)> {
        match &mut self.out {
            WayOut::File(_) => {
                return err("finish_anon on a file-backed ways sink".to_string())
            }
            WayOut::Anon(a) => a.finish(),
        }
        let counts = WayCounts {
            ways: self.count,
            refs: self.refs,
            max_ref: self.max_ref,
            blocks: std::mem::take(&mut self.blocks),
        };
        match self.out {
            WayOut::Anon(a) => Ok((counts, a)),
            WayOut::File(_) => unreachable!("checked above"),
        }
    }

    /// Seal, and hand back the counts plus the staged bytes on the anon plan or `None` on the
    /// file plan (whose bytes live at the path the sink was created with). Unifies
    /// [`finish`](Self::finish) and [`finish_anon`](Self::finish_anon) so callers that pick the
    /// backend by budget seal without knowing which one they chose.
    pub fn finish_either(mut self) -> Result<(WayCounts, Option<tile_build::anon::AnonStore>)> {
        match &mut self.out {
            WayOut::File(f) => f.flush().map_err(|e| Error(format!("cannot flush the ways spill: {e}")))?,
            WayOut::Anon(a) => a.finish(),
        }
        let counts = WayCounts {
            ways: self.count,
            refs: self.refs,
            max_ref: self.max_ref,
            blocks: std::mem::take(&mut self.blocks),
        };
        match self.out {
            WayOut::Anon(a) => Ok((counts, Some(a))),
            WayOut::File(_) => Ok((counts, None)),
        }
    }
}

/// What a finished [`WaySink`] holds.
pub struct WayCounts {
    pub ways: u64,
    /// Node refs summed over every way, duplicates included — a capacity, not a cardinality.
    pub refs: u64,
    /// The largest node ref written, or zero if none were. What a bitset over the id space is sized
    /// from.
    pub max_ref: i64,
    /// The block index over the stream, in stream order: what [`WayBlocks`] decodes from.
    pub blocks: Vec<WayBlockLoc>,
}

/// Reads back what a [`WaySink`] wrote, in the order it was written.
///
/// File-backed ([`open`](Self::open)) or anonymous ([`open_anon`](Self::open_anon)):
/// the record format is a sequential varint stream either way, so the reader is
/// a cursor over bytes — a file cursor or a store offset. Same records, same order.
pub struct WayReader {
    inner: WayIn,
    last_id: i64,
    seen: u64,
}

/// Where a [`WayReader`] reads from.
enum WayIn {
#[cfg_attr(not(test), allow(dead_code))]
    File(BufReader<File>),
    Anon {
        store: std::sync::Arc<tile_build::anon::AnonStore>,
        at: u64,
        buf: Vec<u8>,
        used: usize,
    },
    /// One block of the stream, read whole ([`WayBlocks::reader`]).
    Slice { buf: Vec<u8>, used: usize },
}
