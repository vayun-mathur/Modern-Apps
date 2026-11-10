/// The planet path: a byte of degree per node and a positional walk over each
/// way's refs. See [`crate::chains`] for what it costs and what it buys.
#[allow(clippy::too_many_arguments)]
fn collapse_within_ways<W: std::io::Write + Send>(
    input: &Path,
    spill_dir: &Path,
    spill_pts_dir: &Path,
    blobs: &[pbf::BlobLoc],
    blob_kinds: &[u8],
    index: &NodeIndex,
    coords: &[geom::Pt],
    stop: &Bitset,
    slots: u32,
    filter: RegionFilter,
    pool: &mut NamePool<W>,
    timer: &mut crate::timing::PhaseTimer,
) -> Result<Collapsed> {
    timer.switch("collapse-degrees");
    let degree = chains::count_degrees(input, blobs, blob_kinds, index, coords, slots, filter)?;
    let spill = chains::Spill::split(spill_dir, spill_pts_dir);
    timer.switch("collapse-chains");
    let built = chains::build(
        input, blobs, blob_kinds, index, coords, &degree, stop, slots, filter, pool, &spill,
    )?;
    report_way_blobs(blobs.len(), &built.kinds);

    // One byte of `kept` (eight nodes) per item, on the pool: each byte is a
    // pure function of its own nodes, so the split cannot show.
    let mut kept = Bitset::new(u64::from(slots));
    {
        let n = u64::from(slots);
        let present = &index.present;
        let ends = &built.endpoints;
        let degree = &degree;
        par::install(|| {
            kept.bits.par_chunks_mut(1 << 16).enumerate().for_each(|(ci, chunk)| {
                for (j, byte) in chunk.iter_mut().enumerate() {
                    let base = (((ci << 16) + j) as u64) * 8;
                    let mut v = 0u8;
                    for k in 0..8u64 {
                        let i = base + k;
                        if i < n && present.get(i) && chains::survives(ends, degree, stop, i as u32)
                        {
                            v |= 1 << k;
                        }
                    }
                    *byte = v;
                }
            });
        });
    }
    drop(degree);

    let count = spill.chain_count()?;
    if count != built.chain_count {
        return Err(Error(format!(
            "the chain pass streamed {} chain(s) but chains.hdr holds {count}",
            built.chain_count
        )));
    }

    Ok(Collapsed {
        spill,
        kept,
        lane_pool: built.lanes,
        chain_count: count,
        splits: built.splits,
        raw_edge_count: built.raw_edge_count,
    })
}

/// The load-bearing measurement for re-reading the way blobs rather than caching
/// them: if a ways-only pass had to touch most blobs, the cache would have been
/// the cheaper of the two.
fn report_way_blobs(total: usize, kinds: &[u8]) {
    let read = kinds.iter().filter(|k| *k & KIND_WAYS != 0).count();
    println!(
        "The ways pass read {read} of {total} blob(s) ({:.1}%)",
        read as f64 * 100.0 / total.max(1) as f64
    );
}

/// Refuse a lane pool whose offsets would no longer fit a `u32`.
///
/// Same failure mode as the counts above and one step worse: a rebased offset that
/// wrapped onto [`NO_LANES`] reads back as "this edge has no lane data", so real
/// turn lanes would disappear from a build that reported success.
pub(crate) fn cap_lane_pool(len: usize) -> Result<()> {
    if len as u64 >= u64::from(NO_LANES) {
        return Err(Error(format!(
            "the lane pool reached {len} mask(s), past the {NO_LANES} an offset can address"
        )));
    }
    Ok(())
}

/// Refuse a count that no longer fits the `u32` ids the build and the on-disk
/// format are built on.
///
/// Every one of these is a silent truncation rather than a crash: a `u32` node id
/// or chain id that wrapped would address a real but wrong record, so the build
/// would succeed and the graph would be quietly wrong. The ceilings are ~2.5x
/// away at planet scale, which is close enough to be worth naming.
fn cap_u32(what: &str, n: u64) -> Result<u32> {
    u32::try_from(n).map_err(|_| {
        Error(format!(
            "{n} {what}(s) is past the {} this format can address",
            u32::MAX
        ))
    })
}

/// Dense id -> final (Morton) id, for the nodes that survived.
///
/// Indexed by rank over the surviving-node bitset rather than by dense id, so it
/// costs four bytes per *survivor* instead of four per marked id — a difference of
/// tens of gigabytes at planet scale, since the marked set includes every node any
/// routable way ever mentions.
struct FinalIds<'a> {
    kept: &'a Bitset,
    of_kept: &'a [u32],
}

impl FinalIds<'_> {
    #[inline]
    fn get(&self, dense: u32) -> u32 {
        self.of_kept[self.kept.dense(u64::from(dense)) as usize]
    }
}

/// Out-edges per surviving node, as a CSR in final id space.
///
/// Built by two sequential scans of the chain spill, and it pays for itself three
/// times over: [`twin_is_unique`] becomes a binary search in one node's group
/// rather than in the whole edge array, the component scan walks it instead of a
/// materialised edge array — which is what makes that scan affordable at all — and
/// each write round takes its expected edge count straight out of `edge_ptr`.
struct Csr {
    /// Directed edges before node `v`'s group. Length is `kept_count + 1`.
    edge_ptr: Vec<u64>,
    /// Each group's targets, ascending.
    targets: Vec<u32>,
}

impl Csr {
    #[inline]
    fn out(&self, v: u32) -> &[u32] {
        let (a, b) = (self.edge_ptr[v as usize], self.edge_ptr[v as usize + 1]);
        &self.targets[a as usize..b as usize]
    }

    fn edge_count(&self) -> u64 {
        *self.edge_ptr.last().expect("edge_ptr has a sentinel")
    }
}

/// One synthetic connector reattaching an isolated transit stop.
///
/// Kept apart from the CSR rather than merged into it: there are a handful of
/// these against billions of real edges, and inserting them would mean rebuilding
/// `targets` to make room.
#[derive(Clone, Copy)]
struct Synth {
    source: u32,
    target: u32,
    dist_mm: u32,
}

fn build_csr(
    spill: &chains::Spill,
    kept_count: u32,
    ids: &FinalIds,
    timer: &mut crate::timing::PhaseTimer,
) -> Result<Csr> {
    println!("Building the edge index over {kept_count} node(s)...");
    let chain_total = spill.chain_count()?;
    // Both scans run on the pool over the mapped header file.
    //
    // Counting is integer addition into per-node counters, so atomic
    // increments give the same counts in any order. The scatter claims each
    // slot with an atomic cursor, so WHERE in its group a target lands depends
    // on scheduling — but every group is sorted right after (below), and a
    // sorted group of `u32`s has one arrangement, so `targets` comes out
    // identical to the serial scan's.
    let headers = spill.headers_mapped()?;
    if headers.len()? != chain_total {
        return Err(Error("chains.hdr changed size under the edge index".into()));
    }
    let mut edge_ptr = vec![0u64; kept_count as usize + 2];
    {
        let counts = atomic_u64s(&mut edge_ptr);
        let errs: Vec<Result<()>> = par::map_ranges(chain_total as usize, 1 << 16, |r| {
            for ci in r {
                let c = headers.get(ci as u64)?;
                counts[ids.get(c.first) as usize + 1].fetch_add(1, Ordering::Relaxed);
                if !c.oneway {
                    counts[ids.get(c.last) as usize + 1].fetch_add(1, Ordering::Relaxed);
                }
            }
            Ok(())
        });
        errs.into_iter().collect::<Result<Vec<()>>>()?;
    }
    for i in 0..=kept_count as usize {
        edge_ptr[i + 1] += edge_ptr[i];
    }

    let total = edge_ptr[kept_count as usize];
    timer.switch("edge-index-scatter");
    let mut targets = vec![0u32; total as usize];
    {
        let mut cursor: Vec<u64> = edge_ptr[..=kept_count as usize].to_vec();
        let cursor = atomic_u64s(&mut cursor);
        let out = par::SyncPtr(targets.as_mut_ptr());
        let errs: Vec<Result<()>> = par::map_ranges(chain_total as usize, 1 << 16, |r| {
            for ci in r {
                let c = headers.get(ci as u64)?;
                let (a, b) = (ids.get(c.first), ids.get(c.last));
                let slot = cursor[a as usize].fetch_add(1, Ordering::Relaxed);
                // SAFETY: `fetch_add` hands every slot out exactly once, and the
                // counts above bound each group's slots to its own range.
                unsafe { out.write(slot as usize, b) };
                if !c.oneway {
                    let slot = cursor[b as usize].fetch_add(1, Ordering::Relaxed);
                    // SAFETY: as above.
                    unsafe { out.write(slot as usize, a) };
                }
            }
            Ok(())
        });
        errs.into_iter().collect::<Result<Vec<()>>>()?;
    }
    edge_ptr.truncate(kept_count as usize + 1);

    // Per group, so `twin_is_unique` can binary-search it. Sorting the whole array
    // would be one comparison sort over a billion elements; this is millions of
    // sorts over a handful each.
    //
    // Parallelised by carving `targets` into one disjoint slice per WORKER, each
    // covering a contiguous span of nodes, rather than one slice per group: a
    // `&mut [u32]` is 16 bytes, and a planet graph has around a billion groups, so
    // collecting them all would cost more memory than the graph. Sorting `u32`s has
    // one answer, so nothing here depends on the order the work happens in.
    let n = kept_count as usize;
    let workers = par::threads().min(n).max(1);
    let mut pieces: Vec<(usize, usize, &mut [u32])> = Vec::with_capacity(workers);
    let mut rest: &mut [u32] = &mut targets;
    let mut consumed = 0usize;
    for w in 0..workers {
        let lo = n * w / workers;
        let hi = n * (w + 1) / workers;
        let end = edge_ptr[hi] as usize;
        let (mine, tail) = rest.split_at_mut(end - consumed);
        pieces.push((lo, hi, mine));
        rest = tail;
        consumed = end;
    }
    let ptr = &edge_ptr;
    timer.switch("edge-index-sort");
    eprintln!("Sorting edge groups across {workers} worker(s)...");
    par::install(|| {
        pieces.par_iter_mut().for_each(|(lo, hi, buf)| {
            let base = ptr[*lo] as usize;
            for v in *lo..*hi {
                let (a, b) = (ptr[v] as usize - base, ptr[v + 1] as usize - base);
                buf[a..b].sort_unstable();
            }
        });
    });
    println!("{total} directed edge(s) from chains");
    Ok(Csr { edge_ptr, targets })
}

/// A `Vec<u64>` as shared atomic counters for the length of the borrow.
fn atomic_u64s(v: &mut [u64]) -> &[std::sync::atomic::AtomicU64] {
    // SAFETY: `AtomicU64` has the size and bit validity of `u64` and, on every
    // target this tool builds for, its alignment too (asserted below); the
    // exclusive borrow is held for the view's lifetime.
    const _: () = assert!(
        std::mem::align_of::<std::sync::atomic::AtomicU64>() == std::mem::align_of::<u64>()
    );
    unsafe { &*(v as *mut [u64] as *const [std::sync::atomic::AtomicU64]) }
}

/// True when `target`'s edge range holds exactly one edge pointing back at
/// `source`.
///
/// `graph.rs` resolves a reversed edge by scanning the target's range for the
/// first edge whose own target is the source, so with two parallel roads between
/// the same pair of nodes it could pick the wrong twin and draw the wrong
/// polyline.
///
/// The synthetic connectors have to be counted too, even though they are not in
/// the CSR, because the reader does not know they are synthetic. In practice they
/// never collide — a connector runs from a component too small to be routable into
/// one that is, so no chain can join the same pair — but counting them makes that a
/// measured fact rather than an assumption, and it is a binary search in a list of
/// a few thousand.
fn twin_is_unique(csr: &Csr, synth: &[Synth], source: u32, target: u32) -> bool {
    let group = csr.out(target);
    let lo = group.partition_point(|t| *t < source);
    let hi = group.partition_point(|t| *t <= source);
    let from_synth = {
        let key = (target, source);
        let a = synth.partition_point(|s| (s.source, s.target) < key);
        let b = synth.partition_point(|s| (s.source, s.target) <= key);
        b - a
    };
    hi - lo + from_synth == 1
}

fn pass1_blob(
    state: &mut Pass1,
    block: &pbf::PrimitiveBlock,
    filter: RegionFilter,
    mask: &AtomicBits,
) -> Result<u8> {
    let mut kinds = 0u8;
    let mut mark = |id: i64| {
        if id >= 0 && (id as u64) < BITSET_SIZE {
            if mask.set(id as u64) {
                state.marked += 1;
            }
            // The rank index is sized from the largest id actually marked,
            // not from BITSET_SIZE: indexing all 20 G nominal ids would cost
            // 313 MB to describe blocks no node reaches.
            state.max_id = state.max_id.max(id as u64);
        }
    };
    visit_block(
        block,
        KIND_NODES | KIND_WAYS,
        &mut kinds,
        &mut |el: Element| {
            match el {
                Element::Node(n) => {
                    if tags::is_stop_node(
                        n.tags.get_str("highway"),
                        n.tags.get_str("railway"),
                        n.tags.get_str("public_transport"),
                    ) {
                        // A stop outside the region never enters the graph. The
                        // node carries its own coordinates, so this is the one
                        // place the region filter can apply without a location
                        // table.
                        if crate::bbox::keep_e7(filter.as_ref(), n.lat_e7, n.lon_e7) {
                            mark(n.id);
                        }
                    }
                }
                Element::Way(w) => {
                    if tags::get_hw_id(w.tags.get_str("highway")) != 0 {
                        for r in w.refs {
                            mark(*r);
                        }
                    }
                }
                Element::Relation(_) => {}
            }
            Ok(())
        },
    )?;
    Ok(kinds)
}

/// Everything the graph takes from one routable way's tags.
pub(crate) struct WayAttrs {
    /// Chunk-local name id, or `u32::MAX` for an unnamed way.
    pub name: u32,
    pub type_: u8,
    pub speed_limit: u8,
    pub oneway: bool,
    pub fwd_lane_off: u32,
    pub fwd_lane_count: u16,
    pub bwd_lane_off: u32,
    pub bwd_lane_count: u16,
}

fn way_blob(
    state: &mut WayPass,
    block: &pbf::PrimitiveBlock,
    index: &NodeIndex,
    coords: &[geom::Pt],
    filter: RegionFilter,
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_WAYS, &mut kinds, &mut |el: Element| {
        let Element::Way(w) = el else {
            return Ok(());
        };
        let type_ = tags::get_hw_id(w.tags.get_str("highway"));
        if type_ == 0 {
            return Ok(());
        }
        // Region filter: keep the way when ANY node touches the box (the
        // `complete_ways` rule — truncating at the boundary would invent a
        // vertex). Coordinates are resolved by now, so this is a direct test.
        if let Some(b) = filter.as_ref() {
            let mut touches = false;
            for r in w.refs {
                if let Some(d) = index.dense(*r) {
                    let (lat_e7, lon_e7) = coords[d as usize];
                    if b.contains_e7(lat_e7, lon_e7) {
                        touches = true;
                        break;
                    }
                }
            }
            if !touches {
                return Ok(());
            }
        }
        let attrs = way_attrs(&w, type_, &mut state.lanes, &mut state.names);
        for pair in w.refs.windows(2) {
            // The same predicate the within-way path uses, so the two collapse
            // paths agree on what a segment is. Asking a different question here
            // would make the reference path emit a zero-length self-loop for a
            // repeated ref, and the byte-identity comparison between the two would
            // stop meaning anything.
            let Some((u, v)) = chains::segment(index, pair[0], pair[1]) else {
                continue;
            };
            let (a, b) = (coords[u as usize], coords[v as usize]);
            state.segs.push(Seg {
                u,
                v,
                dist_mm: accurate_dist_mm(a.0, a.1, b.0, b.1),
                name_offset: attrs.name,
                type_: attrs.type_,
                speed_limit: attrs.speed_limit,
                oneway: attrs.oneway,
                fwd_lane_off: attrs.fwd_lane_off,
                fwd_lane_count: attrs.fwd_lane_count,
                bwd_lane_off: attrs.bwd_lane_off,
                bwd_lane_count: attrs.bwd_lane_count,
            });
        }
        Ok(())
    })?;
    Ok(kinds)
}

/// Lane masks are pushed into `lanes` and the name interned into `names`, both
/// chunk-local: the sink rebases the offsets when it folds the chunk in.
pub(crate) fn way_attrs(
    w: &crate::osm::WayView,
    type_: u8,
    lanes: &mut Vec<u16>,
    names: &mut LocalNames,
) -> WayAttrs {
    let name = match w.tags.get("name") {
        Some(n) => names.id(n),
        None => u32::MAX,
    };
    let speed_limit = tags::parse_maxspeed(w.tags.get_str("maxspeed"));
    let oneway = w.tags.get_str("oneway") == Some("yes");

    // Forward lanes come from turn:lanes:forward, or from plain turn:lanes on a
    // oneway; backward from turn:lanes:backward. Plain `lanes*` only refine the
    // count. No turn:lanes at all means no lane data, and the router infers lanes
    // from junction topology instead.
    let tl = w.tags.get_str("turn:lanes");
    let tlf = w.tags.get_str("turn:lanes:forward");
    let tlb = w.tags.get_str("turn:lanes:backward");
    let count_all = tags::parse_int_tag(w.tags.get_str("lanes"));
    let count_fwd = tags::parse_int_tag(w.tags.get_str("lanes:forward"));
    let count_bwd = tags::parse_int_tag(w.tags.get_str("lanes:backward"));

    let fwd_spec = tlf.or(if oneway { tl } else { None });
    let fwd_hint = if count_fwd > 0 {
        count_fwd
    } else if oneway {
        count_all
    } else {
        0
    };
    let bwd_spec = if oneway { None } else { tlb };
    let fwd = tags::build_dir_lanes(fwd_spec, fwd_hint);
    let bwd = tags::build_dir_lanes(bwd_spec, count_bwd);

    let mut push_lanes = |masks: &[u16]| -> (u32, u16) {
        if masks.is_empty() {
            return (NO_LANES, 0);
        }
        let off = lanes.len() as u32;
        lanes.extend_from_slice(masks);
        (off, masks.len() as u16)
    };
    let (fwd_lane_off, fwd_lane_count) = push_lanes(&fwd);
    let (bwd_lane_off, bwd_lane_count) = push_lanes(&bwd);

    WayAttrs {
        name,
        type_,
        speed_limit,
        oneway,
        fwd_lane_off,
        fwd_lane_count,
        bwd_lane_off,
        bwd_lane_count,
    }
}

fn pass2_blob(state: &mut Pass2, block: &pbf::PrimitiveBlock, mask: &Bitset) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_NODES, &mut kinds, &mut |el: Element| {
        if let Element::Node(n) = el {
            if n.id < 0 || n.id as u64 >= BITSET_SIZE || !mask.get(n.id as u64) {
                return Ok(());
            }
            let dense = mask.dense(n.id as u64);
            // A `gtfs:stop_code:<feed>` tag means this node is a matched GTFS
            // stop; its `name` (or the code itself) becomes the stop label the
            // transit planner shows. Otherwise fall back to the plain OSM stop
            // tags.
            let gtfs_code = n
                .tags
                .iter()
                .find(|(k, _)| k.starts_with(b"gtfs:stop_code:"))
                .map(|(_, v)| n.tags.get("name").unwrap_or(v));
            let stop_code = match gtfs_code {
                Some(code) => Some(code),
                None => tags::is_stop_node(
                    n.tags.get_str("highway"),
                    n.tags.get_str("railway"),
                    n.tags.get_str("public_transport"),
                )
                .then(|| n.tags.get("name").unwrap_or(UNNAMED_STOP)),
            };
            state.locs.push((dense, n.lat_e7, n.lon_e7));
            if let Some(code) = stop_code {
                state.stops.push((dense, code.to_vec()));
            }
        }
        Ok(())
    })?;
    Ok(kinds)
}

/// What one stop-reconnection pass did, for [`Stats`] and for the log line that
/// makes [`MIN_ROUTABLE_COMPONENT`] auditable.
struct Reconnected {
    lcc_size: u64,
    /// Bidirectional connectors, sorted by `(source, target)`.
    synth: Vec<Synth>,
    already_connected: usize,
    unreachable: usize,
}
