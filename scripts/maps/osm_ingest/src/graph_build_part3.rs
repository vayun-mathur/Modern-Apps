/// Hook every transit stop that is *not* attached to a real road network onto the
/// nearest node that is. Without this a matched GTFS stop that OSM never attached
/// to a road is unreachable and the transit planner cannot get the user to it.
///
/// # Why component *size* and not the largest component
///
/// The question worth asking is "is this stop attached to a road network?", not
/// "is it attached to the biggest one?". On a region those coincide — the largest
/// component is 98.2% of California — so the pass used to find the largest
/// component and reconnect everything outside it. On a planet they do not:
/// continents are not road-connected, so the largest component is
/// Eurasia-plus-Africa at 57.7% and every stop in the Americas, Australia, Japan,
/// the UK and Indonesia was classified as isolated. The code then tried to bridge
/// an ocean to reach a road in France, failed 1,173,347 times — a quarter of all
/// stops — and paid for a widening search out to a million-node index window
/// before each failure.
///
/// So a stop needs reconnecting only when its own component is smaller than
/// [`MIN_ROUTABLE_COMPONENT`], and the target is the nearest node in *any*
/// component at or above that threshold. A stop alone on an untagged node is a
/// component of size 1 and is still connected; a stop on a small island's road
/// network is left alone, because it is already as reachable as that island gets;
/// a stop in North America connects to North America. That also removes the
/// pathology rather than its symptom: a qualifying node is now metres away, so the
/// widening search almost always succeeds on its first window.
///
/// The threshold is capped at the largest component that exists, because a graph
/// with no component of 1000 nodes still has a road network — it is just a small
/// one — and requiring a size nothing reaches would reconnect nothing at all.
///
/// One caveat on the word "component": the scan follows out-edges only, because
/// that is the index the build has — a reverse CSR would be another 4.3 GB of
/// `targets` at planet scale. So the partition is by out-reachability, and a node
/// fed only by incoming one-ways can be labelled separately from the network that
/// feeds it, undercounting that network's size. The error is in the safe direction:
/// such a node looks *less* connected than it is, so at worst it gains a connector
/// it did not need. The same traversal is what measured California's largest
/// component at 98.24%, which bounds how far off it can be in practice.
///
/// `coords` and `stops` are both in final (Morton) id space.
fn reconnect_isolated_stops(coords: &[geom::Pt], stops: &Bitset, csr: &Csr) -> Reconnected {
    let n = coords.len();
    if n == 0 {
        return Reconnected {
            lcc_size: 0,
            synth: Vec::new(),
            already_connected: 0,
            unreachable: 0,
        };
    }
    println!("Identifying connected components...");

    let mut component = vec![u32::MAX; n];
    // One size per component id. Planet has ~291 K components, so 1.2 MB.
    let mut sizes: Vec<u32> = Vec::new();
    let mut queue: Vec<u32> = Vec::with_capacity(n);
    let mut prog = crate::progress::Progress::new("Connected components", n as u64);
    for start in 0..n {
        prog.inc();
        if component[start] != u32::MAX {
            continue;
        }
        let id = sizes.len() as u32;
        let base = queue.len();
        queue.push(start as u32);
        component[start] = id;
        let mut head = base;
        while head < queue.len() {
            let u = queue[head];
            head += 1;
            for v in csr.out(u) {
                if component[*v as usize] == u32::MAX {
                    component[*v as usize] = id;
                    queue.push(*v);
                }
            }
        }
        sizes.push((queue.len() - base) as u32);
    }
    prog.finish();
    drop(queue);

    let lcc_size = sizes.iter().copied().max().unwrap_or(0);
    let threshold = MIN_ROUTABLE_COMPONENT.min(lcc_size);
    println!(
        "{} component(s), largest {lcc_size} / {n} node(s) ({:.1}%); a component of \
         {threshold}+ node(s) counts as routable",
        sizes.len(),
        f64::from(lcc_size) * 100.0 / n as f64
    );

    let routable = |node: u32| sizes[component[node as usize] as usize] >= threshold;
    let mut stop_count = 0usize;
    let mut isolated: Vec<u32> = Vec::new();
    for i in 0..n as u32 {
        if stops.get(u64::from(i)) {
            stop_count += 1;
            if !routable(i) {
                isolated.push(i);
            }
        }
    }
    let already_connected = stop_count - isolated.len();
    if isolated.is_empty() {
        println!("{stop_count} stop(s), all already on a routable component");
        return Reconnected {
            lcc_size: u64::from(lcc_size),
            synth: Vec::new(),
            already_connected,
            unreachable: 0,
        };
    }

    // The nodes are Morton-sorted, so index distance approximates spatial
    // distance: widen an index window around the stop until it contains a node in
    // a routable component, then take the closest one in that window.
    let found = par::map_chunks(&isolated, 256, |_, chunk| {
        let mut out: Vec<Synth> = Vec::new();
        let mut failed = 0usize;
        for &i in chunk {
            let stop = coords[i as usize];
            let mut best: Option<(u32, u32)> = None;
            let mut radius = 1000usize;
            while radius <= 1_000_000 {
                let lo = (i as usize).saturating_sub(radius);
                let hi = (i as usize + radius).min(n);
                for j in lo..hi {
                    if !routable(j as u32) {
                        continue;
                    }
                    let d = accurate_dist_mm(stop.0, stop.1, coords[j].0, coords[j].1);
                    if best.is_none_or(|(bd, _)| d < bd) {
                        best = Some((d, j as u32));
                    }
                }
                if best.is_some() {
                    break;
                }
                radius *= 10;
            }
            if let Some((dist_mm, target)) = best {
                out.push(Synth {
                    source: i,
                    target,
                    dist_mm,
                });
                out.push(Synth {
                    source: target,
                    target: i,
                    dist_mm,
                });
            } else {
                failed += 1;
            }
        }
        (out, failed)
    });

    let unreachable: usize = found.iter().map(|(_, f)| f).sum();
    let mut synth: Vec<Synth> = found.into_iter().flat_map(|(e, _)| e).collect();
    println!(
        "{stop_count} stop(s): {already_connected} already connected, {} reconnected, \
         {unreachable} found nothing",
        synth.len() / 2
    );
    if unreachable > 0 {
        // The index window caps at 1e6 nodes, so a stop with no routable road
        // within that window stays unreachable. Say so rather than dropping it
        // silently: an unreachable stop is exactly what this pass exists to
        // prevent. A large count here means MIN_ROUTABLE_COMPONENT is too high.
        eprintln!("WARN: {unreachable} isolated stop(s) had no routable road nearby");
    }
    synth.sort_by_key(|s| (s.source, s.target));
    Reconnected {
        lcc_size: u64::from(lcc_size),
        synth,
        already_connected,
        unreachable,
    }
}

/// What `write_graph` produced, for [`Stats`].
struct Written {
    edge_count: u64,
    geometry_edges: u64,
    reversed_edges: u64,
    intermediate_bytes: u64,
    edges_bytes: u64,
    escape_count: u64,
    named_edges: u64,
    census: Census,
}

/// Write the pack in `rounds` source-partitioned rounds.
///
/// # Why rounds, and not computed offsets
///
/// The obvious alternative is to compute each edge's index and `pwrite` it into
/// place. That is sound for `edges.bin` — fixed 14-byte records in a pre-sized
/// file — but wrong for the two blob files. The reader derives a polyline's length
/// from the next offset entry and a lane run's from the next index entry, so
/// `intermediate.bin` and `lanes.bin` are forced into edge-index order with
/// monotonic offsets, and writing a blob at a computed offset would mean knowing
/// every earlier blob's length first: a per-edge length array, a prefix sum and a
/// second encode pass.
///
/// Partitioning by source instead makes every write an append. Each round takes a
/// contiguous range of final node ids, scans the chain spill for the edges whose
/// *source* falls in that range, sorts that buffer, and writes both blobs, the
/// presence bitmap, the offset tables, `edges.bin` and `nodes.bin` sequentially.
/// Offsets come out monotonic by construction, `nodes.bin`'s running edge counter
/// works because the rounds ascend, and the `(source, target)` grouping the reader
/// and [`twin_is_unique`] depend on is restored within each round.
///
/// The cost is `rounds` sequential reads of the spill instead of one.
#[allow(clippy::too_many_arguments)]
fn write_graph(
    out_dir: &Path,
    node_coords: &[geom::Pt],
    spill: &chains::Spill,
    lane_pool: &[u16],
    csr: &Csr,
    synth: &[Synth],
    ids: &FinalIds,
    rounds: u32,
    dem: Option<&crate::dem::Dem>,
    timer: &mut crate::timing::PhaseTimer,
) -> Result<Written> {
    let kept = node_coords.len() as u32;
    let edge_count = csr.edge_count() + synth.len() as u64;
    // `nodes.bin`'s `edge_ptr` is a `u32`, so an edge count past that ceiling would
    // wrap and point a node at another node's edge range.
    cap_u32("directed edge", edge_count)?;
    println!("Writing {edge_count} edge(s) in {rounds} round(s)...");
    let chain_total = spill.chain_count()?;

    let mut nodes_blocked = ParBlocked::create(12, &out_dir.join("nodes.blocked.tmp"))
        .map_err(|e| Error(format!("cannot stage nodes.bin: {e}")))?;
    let mut edges_out = EdgeFile::create(out_dir, "edges.bin", edge_count)?;

    // `intermediate.bin` puts its blob at offset 0 and everything that indexes it
    // in a trailer, so it is written strictly forwards with no scratch file at all
    // (see [`GeomFile`]). `lanes.bin` is sparse and so cannot size its index up
    // front; its blob streams to scratch instead and the index is written in front
    // of it.
    let mut inter = GeomFile::create(out_dir, "intermediate.bin", edge_count)?;
    let mut lanes = LaneFile::create(out_dir, "lanes.bin")?;

    let mut buffer: Vec<TmpEdge> = Vec::new();
    let points = spill.points()?;
    let pmap = chains::points_map(&points);
    let mut edge_ptr: u64 = 0;
    let mut geometry_edges: u64 = 0;
    let mut reversed_edges: u64 = 0;
    let mut census = Census::default();
    // One assignment scan instead of one scan per round: every chain's round
    // is a pure function of its endpoints' final ids, so a single pass over
    // the mapped headers records each chain's index per round and every round
    // gathers only its chains. Chain order within a round is ascending index —
    // the order the old per-round scan visited — so the buffer, the sort, and
    // everything downstream are identical.
    //
    // Parallel: fixed contiguous chunks of chain indices, each producing its
    // own per-round lists, concatenated in chunk order — so every list is still
    // ascending and identical to the serial scan's.
    timer.switch("write-scan");
    let headers = spill.headers_mapped()?;
    let chain_total_now = headers.len()?;
    if chain_total_now != chain_total {
        return Err(Error(format!(
            "chains.hdr holds {chain_total_now} chain(s), expected {chain_total}"
        )));
    }
    let round_lo = |round: u32| (u64::from(kept) * u64::from(round) / u64::from(rounds)) as u32;
    let by_round: Vec<Vec<u32>> = {
        let n_chunks = (chain_total as usize).div_ceil(SCAN_CHUNK);
        let chunk_ids: Vec<usize> = (0..n_chunks).collect();
        let parts: Vec<Result<Vec<Vec<u32>>>> = par::map_chunks(&chunk_ids, 1, |_, ch| {
            let c0 = (ch[0] * SCAN_CHUNK) as u64;
            let c1 = (c0 + SCAN_CHUNK as u64).min(chain_total);
            let mut mine: Vec<Vec<u32>> = vec![Vec::new(); rounds as usize];
            for ci in c0..c1 {
                let c = headers.get(ci)?;
                let (a, b) = (ids.get(c.first), ids.get(c.last));
                // A chain enters a round when the old scan would have buffered an
                // edge for it there: source endpoint in range, or (two-way) target
                // endpoint in range. One entry per qualifying round — a chain whose
                // endpoints straddle a boundary is gathered twice, once per round,
                // exactly as the old scan buffered it twice.
                for round in 0..rounds {
                    let (lo, hi) = (round_lo(round), round_lo(round + 1));
                    if (lo..hi).contains(&a) || (!c.oneway && (lo..hi).contains(&b)) {
                        mine[round as usize].push(ci as u32);
                    }
                }
            }
            Ok(mine)
        });
        let mut by_round: Vec<Vec<u32>> = vec![Vec::new(); rounds as usize];
        let mut sizes = vec![0usize; rounds as usize];
        for p in &parts {
            if let Ok(p) = p {
                for (r, v) in p.iter().enumerate() {
                    sizes[r] += v.len();
                }
            }
        }
        for (r, v) in by_round.iter_mut().enumerate() {
            v.reserve_exact(sizes[r]);
        }
        for p in parts {
            for (r, v) in p?.into_iter().enumerate() {
                by_round[r].extend_from_slice(&v);
            }
        }
        by_round
    };

    for round in 0..rounds {
        // Split by node count. Contiguous and ascending, so the four output streams
        // stay append-only across the whole run.
        let lo = round_lo(round);
        let hi = round_lo(round + 1);
        timer.switch("write-scan");

        // Gather: this round's chains in ascending index order — the visit
        // order of the old per-round scan. Parallel over fixed chunks of the
        // round's list, concatenated in chunk order, so the buffer is the
        // serial one element for element.
        buffer.clear();
        let gathered: Vec<Result<Vec<TmpEdge>>> =
            par::map_chunks(&by_round[round as usize], SCAN_CHUNK, |_, chunk| {
                let mut out = Vec::with_capacity(chunk.len() + chunk.len() / 2);
                for &ci in chunk {
                    let c = headers.get(u64::from(ci))?;
                    let (a, b) = (ids.get(c.first), ids.get(c.last));
                    if (lo..hi).contains(&a) {
                        out.push(chain_edge(&c, ci, a, b, false));
                    }
                    if !c.oneway && (lo..hi).contains(&b) {
                        out.push(chain_edge(&c, ci, b, a, true));
                    }
                }
                Ok(out)
            });
        let synth_lo = synth.partition_point(|s| s.source < lo);
        let synth_hi = synth.partition_point(|s| s.source < hi);
        let gathered_len: usize = gathered.iter().map(|g| g.as_ref().map_or(0, Vec::len)).sum();
        buffer.reserve_exact(gathered_len + (synth_hi - synth_lo));
        for g in gathered {
            buffer.extend_from_slice(&g?);
        }
        for s in &synth[synth_lo..synth_hi] {
            buffer.push(TmpEdge {
                source: s.source,
                target: s.target,
                dist_mm: s.dist_mm,
                name_offset: NO_NAME,
                type_: RECONNECT_TYPE,
                speed_limit: RECONNECT_SPEED,
                lane_off: NO_LANES,
                lane_count: 0,
                chain: NO_CHAIN,
                chain_rev: false,
                pts_start: 0,
                pts_len: 0,
            });
        }

        // The CSR already knows how many edges this range owns. Checking against it
        // catches both an edge landing in the wrong round and an edge whose source
        // is not a surviving node — which is what the old
        // `assert_eq!(cursor, edges.len())` was for, now checked per round and
        // before anything is written rather than after everything is.
        let expected = csr.edge_ptr[hi as usize] - csr.edge_ptr[lo as usize]
            + (synth_hi - synth_lo) as u64;
        if buffer.len() as u64 != expected {
            return Err(Error(format!(
                "round {round} covering node(s) {lo}..{hi} collected {} edge(s), \
                 but the edge index says {expected}",
                buffer.len()
            )));
        }

        // Grouped by source for the reader, then by target so a node's parallel
        // edges are adjacent, then by chain so the order is total.
        //
        // This was a STABLE sort on `(source, target, chain)` over a buffer built
        // in chain order. The only elements that key leaves tied and that still
        // differ are the two directions of a two-way self-loop chain — same
        // source, target and chain — which the gather emits forward
        // (`chain_rev == false`) first. Appending `chain_rev` to the key breaks
        // exactly that tie the same way, and anything still tied is a
        // byte-identical duplicate (a repeated connector), whose relative order
        // cannot show. So the order is total over distinguishable elements and an
        // UNSTABLE parallel in-place sort yields the stable sort's sequence — with
        // no merge buffer, which at planet scale would be another 17 GB.
        par::install(|| {
            buffer.par_sort_unstable_by_key(|e| (e.source, e.target, e.chain, e.chain_rev))
        });

        // --- write-write: parallel bake, in-order drain ------------------------
        timer.switch("write-write");
        write_round(
            &buffer,
            lo,
            hi,
            edge_ptr,
            node_coords,
            lane_pool,
            csr,
            synth,
            pmap,
            &mut WriteSinks {
                nodes: &mut nodes_blocked,
                edges: &mut edges_out,
                inter: &mut inter,
                lanes: &mut lanes,
                census: &mut census,
                geometry_edges: &mut geometry_edges,
                reversed_edges: &mut reversed_edges,
            },
            round,
            rounds,
        )?;
        edge_ptr += buffer.len() as u64;
    }
    drop(buffer);

    if edge_ptr != edge_count {
        return Err(Error(format!(
            "wrote {edge_ptr} edge(s) into a file sized for {edge_count}"
        )));
    }
    finish_pack(
        out_dir,
        kept,
        edge_count,
        edge_ptr,
        geometry_edges,
        reversed_edges,
        nodes_blocked,
        edges_out,
        inter,
        lanes,
        census,
        node_coords,
        dem,
    )
}

/// Finish the four output streams and the metadata: the tail of `write_graph`
/// after the last round drains. Unchanged from the serial loop — the drain
/// above stages identical bytes, so everything downstream (sentinel, sanity
/// parses, metadata, elevation) is untouched.
#[allow(clippy::too_many_arguments)]
fn finish_pack(
    out_dir: &Path,
    kept: u32,
    edge_count: u64,
    edge_ptr: u64,
    geometry_edges: u64,
    reversed_edges: u64,
    nodes_blocked: ParBlocked,
    edges_out: EdgeFile,
    inter: GeomFile,
    lanes: LaneFile,
    census: Census,
    node_coords: &[geom::Pt],
    dem: Option<&crate::dem::Dem>,
) -> Result<Written> {
    let mut nodes_blocked = nodes_blocked;
    let mut edges_out = edges_out;
    let mut inter = inter;
    let mut lanes = lanes;
    // Trailing sentinel so `graph.rs` can read node(v + 1).edge_ptr as the end of
    // node v's edge range.
    let mut sentinel = [0u8; 12];
    sentinel[8..12].copy_from_slice(&(edge_ptr as u32).to_le_bytes());
    nodes_blocked
        .push(&sentinel)
        .map_err(|e| Error(format!("cannot stage node sentinel: {e}")))?;
    // Streamed straight into the file (no in-memory copy of the section),
    // then checked against the mapped result.
    {
        let mut out = BufWriter::with_capacity(WRITE_BUF_BYTES, create(&out_dir.join("nodes.bin"))?);
        nodes_blocked
            .finish_into(&mut out)
            .map_err(|e| Error(format!("cannot finish nodes.bin: {e}")))?;
        out.flush().map_err(io_err)?;
    }
    let nodes_map = crate::mem::Mapped::open(&out_dir.join("nodes.bin")).map_err(Error)?;
    // Sanity: the blocked section must unpack to exactly the pushed records.
    let parsed = tilecodec::blocked::Blocked::parse(&nodes_map).map_err(|e| {
        Error(format!(
            "freshly blocked nodes.bin does not parse: {e}"
        ))
    })?;
    // Sanity: the blocked section must unpack to exactly the pushed records.
    if parsed.unpacked_len() != (u64::from(kept) + 1) * 12 {
        return Err(Error(format!(
            "nodes.bin unpacks to {} byte(s) ({} 12-byte record(s)), not the {} record(s) \
             pushed ({} byte(s))",
            parsed.unpacked_len(),
            parsed.unpacked_len() / 12,
            u64::from(kept) + 1,
            (u64::from(kept) + 1) * 12
        )));
    }
    drop(nodes_map);
    let escape_count = edges_out.escapes.len() as u64;
    let named_edges = edges_out.name_offsets.len() as u64;
    let edges_bytes = edges_out.finish(edge_count)?;
    let intermediate_bytes = inter.finish(edge_count)?;
    lanes.finish()?;
    finish_pack_tail(
        out_dir,
        kept,
        edge_count,
        escape_count,
        named_edges,
        node_coords,
        dem,
    )?;
    Ok(Written {
        edge_count,
        geometry_edges,
        reversed_edges,
        intermediate_bytes,
        edges_bytes,
        escape_count,
        named_edges,
        census,
    })
}

/// Metadata + elevation tail of `write_graph`, after every stream finishes.
/// Split out so the round loop above reads as plan/bake/drain without the
/// file-assembly tail inline. Byte-identical: moved whole, order untouched.
#[allow(clippy::too_many_arguments)]
fn finish_pack_tail(
    out_dir: &Path,
    kept: u32,
    edge_count: u64,
    escape_count: u64,
    named_edges: u64,
    node_coords: &[geom::Pt],
    dem: Option<&crate::dem::Dem>,
) -> Result<()> {
    // metadata.bin's magic + version let the device refuse a pack directory
    // holding files from two different vintages instead of misreading it, and its
    // counts are what every other file's length is validated against.
    let mut meta = create(&out_dir.join("metadata.bin"))?;
    meta.write_all(&GRAPH_MAGIC.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&GRAPH_VERSION.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&u64::from(kept).to_le_bytes()).map_err(io_err)?;
    meta.write_all(&edge_count.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&escape_count.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&named_edges.to_le_bytes()).map_err(io_err)?;
    meta.flush().map_err(io_err)?;
    // elevation.bin: `i16[node_count]` metres, one per node in final-id order — an OPTIONAL sidecar
    // like road_names.bin/lanes.bin, so a build without a DEM simply omits it and the device reads
    // every node's elevation as 0. `node_coords` is already indexed by final node id, so a single
    // forward pass matches the order `nodes.bin` was written in. Blocked like
    // nodes/edges under the v7 layout (magic-dispatched on read, so absence
    // still reads as all-zero).
    if let Some(dem) = dem {
        let mut elev_blocked = tilecodec::blocked::BlockedStreamWriter::create(
            2,
            &out_dir.join("elevation.blocked.tmp"),
        )
        .map_err(|e| Error(format!("cannot stage elevation.bin: {e}")))?;
        for &(lat_e7, lon_e7) in node_coords {
            let lat = f64::from(lat_e7) * 1e-7;
            let lon = f64::from(lon_e7) * 1e-7;
            let metres = dem.sample_metres(lon, lat).unwrap_or(0);
            elev_blocked
                .push(&metres.to_le_bytes())
                .map_err(|e| Error(format!("cannot stage elevation record: {e}")))?;
        }
        let elev_bytes = elev_blocked
            .finish()
            .map_err(|e| Error(format!("cannot finish elevation.bin: {e}")))?;
        std::fs::write(out_dir.join("elevation.bin"), &elev_bytes).map_err(io_err)?;
    }
    Ok(())
}
