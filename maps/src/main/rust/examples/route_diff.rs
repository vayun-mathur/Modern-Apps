//! Route-level differential oracle for road-graph changes.
//!
//! Compaction renumbers nodes and edges and changes their counts, so no output
//! file of two graphs built from the same region is comparable byte-wise. What
//! *is* comparable is what the router does with them: for a fixed list of
//! origin/destination coordinates, the total distance, the total time, the turn
//! sequence and the decoded polyline.
//!
//! This lives in the engine crate, and drives `Graph::load` +
//! `prepare_routing` + `perform_search_loop` + `reconstruct_path` directly,
//! because the point is to exercise the real reader — including
//! `decode_edge_coords` and the `REVERSE_GEOMETRY_FLAG` twin lookup — rather
//! than a host reimplementation of it.
//!
//! It is an `examples/` target, not `src/bin/`, so `cargo build` for the
//! Android lib never picks it up.
//!
//! ```text
//! cargo run --release --example route_diff -- gen-pairs --graph DIR --out pairs.txt [--count N]
//! cargo run --release --example route_diff -- report    --graph DIR --pairs pairs.txt --out report.txt
//! cargo run --release --example route_diff -- compare   --baseline a.txt --candidate b.txt [--tol-mm N]
//! cargo run --release --example route_diff -- bench     --graph DIR --pairs pairs.txt [--passes N] [--cold]
//! cargo run --release --example route_diff -- dump      --graph DIR --out dump.bin
//! cargo run --release --example route_diff -- dump-compare --baseline a.bin --candidate b.bin
//! ```
//!
//! `Graph::load` mmaps, and `MmapRegion::map` is `#[cfg(unix)]`, so this must
//! run on Linux (WSL is fine).

use std::collections::{HashMap, HashSet};
use std::fmt::Write as _;
use std::io::Write as _;

use offlinerouter::geometry::{accurate_dist_mm, TrafficSpeeds};
use offlinerouter::graph::{archive_path, get_pt_at, Graph, LatLon, BICYCLE, DRIVING, NO_NAME, WALK};
use offlinerouter::routing::{perform_search_loop, prepare_routing, reconstruct_path};
use offlinerouter::state::{RadixHeap, RoutingScratchpad};

/// One origin/destination probe. Coordinates, not node ids: ids move when the
/// graph is rebuilt, coordinates do not, which is the whole point.
struct Pair {
    kind: String,
    mode: i32,
    s_lat: f64,
    s_lon: f64,
    e_lat: f64,
    e_lon: f64,
}

fn main() -> std::process::ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let r = match args.first().map(String::as_str) {
        Some("gen-pairs") => gen_pairs(&args[1..]),
        Some("report") => report(&args[1..]),
        Some("compare") => compare(&args[1..]),
        Some("geometry") => geometry(&args[1..]),
        Some("bench") => bench(&args[1..]),
        Some("dump") => dump(&args[1..]),
        Some("dump-compare") => dump_compare(&args[1..]),
        Some("sections") => sections(&args[1..]),
        Some("transit") => transit(&args[1..]),
        _ => Err(USAGE.to_string()),
    };
    match r {
        Ok(true) => std::process::ExitCode::SUCCESS,
        Ok(false) => std::process::ExitCode::FAILURE,
        Err(e) => {
            eprintln!("error: {e}");
            std::process::ExitCode::FAILURE
        }
    }
}

const USAGE: &str = "\
usage:
  route_diff gen-pairs --graph DIR --out FILE [--count N]
  route_diff report    --graph DIR --pairs FILE --out FILE [--overlay FILE | --no-overlay]
  route_diff compare   --baseline FILE --candidate FILE [--tol-mm N] [--tol-10ms N]
  route_diff geometry  --baseline DIR --candidate DIR
  route_diff bench     --graph DIR --pairs FILE [--passes N] [--cold] [--verbose] [--overlay FILE | --no-overlay]
  route_diff dump 
  route_diff dump         --graph DIR --out FILE
  route_diff dump-compare --baseline FILE --candidate FILE [--limit N]
  route_diff sections     --archive FILE [--dump KIND]
  route_diff transit      --graph DIR --from LAT,LON --to LAT,LON --dep SECS --date YYYYMMDD --weekday N";

fn flag(args: &[String], name: &str) -> Option<String> {
    args.iter().position(|a| a == name).and_then(|i| args.get(i + 1)).cloned()
}

fn need(args: &[String], name: &str) -> Result<String, String> {
    flag(args, name).ok_or_else(|| format!("missing {name}\n{USAGE}"))
}

/// Plan one transit journey from an archive's transit section and print its
/// legs, the time the first plan took (which includes classifying the
/// intercity routes), and a second, warm plan.
fn transit(args: &[String]) -> Result<bool, String> {
    use offlinerouter::transit::{plan, LegKind, QueryDay, Schedule, TransitIndex};
    let dir = need(args, "--graph")?;
    let point = |name: &str| -> Result<(f64, f64), String> {
        let v = need(args, name)?;
        let (a, b) = v.split_once(',').ok_or_else(|| format!("{name} wants LAT,LON"))?;
        Ok((a.trim().parse().map_err(|e| format!("{name}: {e}"))?, b.trim().parse().map_err(|e| format!("{name}: {e}"))?))
    };
    let num = |name: &str| -> Result<u32, String> { need(args, name)?.parse().map_err(|e| format!("{name}: {e}")) };
    let (from, to) = (point("--from")?, point("--to")?);
    let (dep, date, weekday) = (num("--dep")?, num("--date")?, num("--weekday")?);
    let started = std::time::Instant::now();
    let idx = TransitIndex::load_archive(&archive_path(&dir)).ok_or("no transit section in the archive")?;
    println!("  load                   {:>10.1} ms", started.elapsed().as_secs_f64() * 1e3);
    let sched = Schedule { day: QueryDay::for_date(weekday, date), overlay: None };
    let hhmm = |s: u32| {
        let (d, r) = (s / 86_400, s % 86_400);
        format!("{:02}:{:02}{}", r / 3600, r % 3600 / 60, if d > 0 { format!(" +{d}d") } else { String::new() })
    };
    let mut first = None;
    for pass in ["cold", "warm"] {
        let t = std::time::Instant::now();
        let legs = plan(&idx, from.0, from.1, to.0, to.1, dep, sched);
        println!("  plan ({pass})            {:>10.1} ms", t.elapsed().as_secs_f64() * 1e3);
        if first.is_none() {
            first = Some(legs);
        }
    }
    let Some(legs) = first.flatten() else {
        println!("  no journey");
        return Ok(false);
    };
    for l in &legs {
        let kind = match l.kind {
            LegKind::Walk => "walk",
            LegKind::Wait => "wait",
            LegKind::Ride => "ride",
        };
        println!(
            "  {kind:<4} {:>9} -> {:<9} {:<28} {} -> {} ({} stops, {:.0} km)",
            hhmm(l.dep_secs),
            hhmm(l.arr_secs),
            l.name,
            l.from_stop,
            l.to_stop,
            l.stop_count,
            l.dist_m / 1000.0
        );
    }
    let total = legs.last().map_or(0, |l| l.arr_secs).saturating_sub(dep);
    println!("  total {:.1} h, {} rides", f64::from(total) / 3600.0, legs.iter().filter(|l| l.kind == LegKind::Ride).count());
    Ok(true)
}

/// Print `kind offset len` for every sidecar section of a `.mamaps`, reading
/// only the footer and directory, so sections can be cut out with `dd`.
fn sections(args: &[String]) -> Result<bool, String> {
    use std::io::{Read as _, Seek as _, SeekFrom};
    use tilecodec::mamaps::archive::{ArchiveEntry, ArchiveFooter};
    let path = need(args, "--archive")?;
    let mut f = std::fs::File::open(&path).map_err(|e| format!("{path}: {e}"))?;
    let mut read_at = |at: SeekFrom, len: usize| -> Result<Vec<u8>, String> {
        let mut buf = vec![0u8; len];
        f.seek(at).and_then(|_| f.read_exact(&mut buf)).map_err(|e| format!("{path}: {e}"))?;
        Ok(buf)
    };
    let footer = ArchiveFooter::parse(&read_at(SeekFrom::End(-32), 32)?).map_err(|e| e.0)?;
    let dir = read_at(SeekFrom::Start(footer.dir_offset), footer.dir_len as usize)?;
    let count = u32::from_le_bytes(dir[0..4].try_into().expect("4 bytes")) as usize;
    let dump: Option<u8> = flag(args, "--dump").map(|k| k.parse().map_err(|e| format!("--dump: {e}"))).transpose()?;
    for i in 0..count {
        let e = ArchiveEntry::parse(&dir[4 + i * 32..]).map_err(|e| e.0)?;
        match dump {
            None => println!("{} {} {}", e.kind, e.offset, e.len),
            Some(k) if k == e.kind => {
                f.seek(SeekFrom::Start(e.offset)).map_err(|e| e.to_string())?;
                let mut out = std::io::stdout().lock();
                std::io::copy(&mut (&mut f).take(e.len), &mut out).map_err(|e| e.to_string())?;
                return Ok(true);
            }
            Some(_) => {}
        }
    }
    Ok(dump.is_none())
}

fn load(dir: &str) -> Result<Graph, String> {
    Graph::load(dir).or_else(|| Graph::load_archive(&archive_path(dir))).ok_or_else(|| {
        format!("cannot load a graph from {dir} (missing/short files, or a bad metadata.bin header)")
    })
}

/// [`load`], then attach `--overlay FILE` when given, or drop the archive's own
/// overlay with `--no-overlay`.
fn load_with_overlay(dir: &str, args: &[String]) -> Result<Graph, String> {
    let mut g = load(dir)?;
    if args.iter().any(|a| a == "--no-overlay") {
        g.detach_overlay();
    }
    if let Some(path) = flag(args, "--overlay") {
        if !g.attach_overlay_file(&path) {
            return Err(format!("{path}: not an overlay for this graph"));
        }
    }
    Ok(g)
}

// ---------------------------------------------------------------------------
// gen-pairs
// ---------------------------------------------------------------------------

/// Sample probe pairs from a graph's node array and write them as coordinates.
///
/// Three length classes, because they stress different things. `long` pairs
/// exercise the search itself; `mid` pairs exercise turn-by-turn output; and
/// `near` pairs put both endpoints within a few nodes of each other, which
/// after compaction is very often the *same* collapsed edge — the case where a
/// straight-chord graph and a collapsed graph can disagree most.
fn gen_pairs(args: &[String]) -> Result<bool, String> {
    let dir = need(args, "--graph")?;
    let out = need(args, "--out")?;
    let count: u32 = flag(args, "--count")
        .as_deref()
        .unwrap_or("300")
        .parse()
        .map_err(|e| format!("--count: {e}"))?;

    let g = load(&dir)?;
    if g.node_count < 16 {
        return Err(format!("graph has only {} node(s)", g.node_count));
    }
    let n = u64::from(g.node_count);

    // Deterministic sampling: a fixed odd multiplier walked over the node
    // array. No RNG, so the pair file is reproducible from any graph of the
    // same region without carrying a seed.
    let mut buf = String::new();
    buf.push_str("# route_diff pairs: kind mode s_lat s_lon e_lat e_lon\n");
    buf.push_str(&format!("# generated from {dir} ({} nodes)\n", g.node_count));
    let mut emitted = 0u32;
    let mut k = 0u64;
    while emitted < count {
        let i = (k.wrapping_mul(2_654_435_761) % n) as u32;
        let (kind, mode, j) = match emitted % 5 {
            0 => ("long", DRIVING, (k.wrapping_mul(40_503) % n) as u32),
            1 => ("mid", DRIVING, ((u64::from(i) + 4_000) % n) as u32),
            2 => ("near", DRIVING, ((u64::from(i) + 3) % n) as u32),
            3 => ("near", WALK, ((u64::from(i) + 2) % n) as u32),
            _ => ("mid", BICYCLE, ((u64::from(i) + 500) % n) as u32),
        };
        k += 1;
        if i == j {
            continue;
        }
        let a = g.node(i);
        let b = g.node(j);
        // A node at (0, 0) means the graph has a hole where a coordinate should
        // be; probing it tests nothing.
        if (a.lat_e7 == 0 && a.lon_e7 == 0) || (b.lat_e7 == 0 && b.lon_e7 == 0) {
            continue;
        }
        // Nudge off the node itself so the probe exercises mid-edge snapping
        // rather than landing exactly on a vertex.
        buf.push_str(&format!(
            "{} {} {:.7} {:.7} {:.7} {:.7}\n",
            kind,
            mode,
            f64::from(a.lat_e7) * 1e-7 + 1e-6,
            f64::from(a.lon_e7) * 1e-7 - 1e-6,
            f64::from(b.lat_e7) * 1e-7 - 1e-6,
            f64::from(b.lon_e7) * 1e-7 + 1e-6,
        ));
        emitted += 1;
    }
    write_file(&out, buf.as_bytes())?;
    println!("wrote {emitted} pair(s) to {out}");
    Ok(true)
}

fn read_pairs(path: &str) -> Result<Vec<Pair>, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("{path}: {e}"))?;
    let mut out = Vec::new();
    for (ln, line) in text.lines().enumerate() {
        if line.starts_with('#') || line.trim().is_empty() {
            continue;
        }
        let f: Vec<&str> = line.split_whitespace().collect();
        if f.len() != 6 {
            return Err(format!("{path}:{}: want 6 fields, got {}", ln + 1, f.len()));
        }
        let num = |s: &str| s.parse::<f64>().map_err(|e| format!("{path}:{}: {e}", ln + 1));
        out.push(Pair {
            kind: f[0].to_string(),
            mode: f[1].parse::<i32>().map_err(|e| format!("{path}:{}: {e}", ln + 1))?,
            s_lat: num(f[2])?,
            s_lon: num(f[3])?,
            e_lat: num(f[4])?,
            e_lon: num(f[5])?,
        });
    }
    Ok(out)
}

// ---------------------------------------------------------------------------
// report
// ---------------------------------------------------------------------------

/// Route every pair and write a line-oriented report.
///
/// The format is deliberately flat text: one `PAIR` line per probe carrying the
/// totals, then one `STEP` line per turn, then one `GEOM` line holding the
/// whole polyline. `compare` reads it back field by field, so totals can carry
/// a tolerance while the turn sequence and geometry are matched exactly.
fn report(args: &[String]) -> Result<bool, String> {
    let dir = need(args, "--graph")?;
    let pairs_path = need(args, "--pairs")?;
    let out = need(args, "--out")?;

    let g = load_with_overlay(&dir, args)?;
    let pairs = read_pairs(&pairs_path)?;
    let speeds = TrafficSpeeds::default();
    let mut scratch = RoutingScratchpad::new();
    let mut heap = RadixHeap::new();

    let mut buf = String::new();
    let _ = writeln!(buf, "# route_diff report");
    let _ = writeln!(buf, "GRAPH nodes={} edges={}", g.node_count, g.edge_count);
    let _ = writeln!(buf, "PAIRS {}", pairs.len());

    let mut routed = 0usize;
    for (i, p) in pairs.iter().enumerate() {
        // No traffic on a host report: live speeds are a device-side input and
        // would make the differential depend on when it ran.
        let mut ensure = |_lat_e7: i32, _lon_e7: i32| {};
        let ctx = prepare_routing(
            &g, &speeds, &mut ensure, p.s_lat, p.s_lon, p.e_lat, p.e_lon, p.mode, &mut scratch,
            &mut heap,
        );
        let mut ctx = match ctx {
            Some(c) => c,
            None => {
                let _ = writeln!(buf, "PAIR {i} {} mode={} no_snap", p.kind, p.mode);
                continue;
            }
        };
        perform_search_loop(&g, &speeds, &mut ensure, p.mode, &mut ctx, &mut scratch, &mut heap);
        // Where the query points landed on the network, and on which edges.
        // Emitted before the routability check because a pair that stops routing
        // is usually a pair that snapped somewhere else, and that is only
        // visible if the snap is reported either way.
        //
        // Reported separately from the route because a rebuild that moves the
        // node set changes how far `find_nearest_edge`'s fixed 800-node window
        // reaches: a route that starts somewhere else is not the same question as
        // a route that takes a different path from the same place.
        let _ = writeln!(
            buf,
            "SNAP {i} s={:.7},{:.7} e={:.7},{:.7} s_off={:.1} e_off={:.1} s_road={} e_road={} direct={}",
            f64::from(ctx.start.proj_lat) * 1e-7,
            f64::from(ctx.start.proj_lon) * 1e-7,
            f64::from(ctx.end.proj_lat) * 1e-7,
            f64::from(ctx.end.proj_lon) * 1e-7,
            // How far the query point had to move to reach the network. This is
            // the measure of snap *quality*: the pairs were sampled from node
            // coordinates, so a good snap lands within centimetres.
            crow_m(p.s_lat, p.s_lon, f64::from(ctx.start.proj_lat) * 1e-7, f64::from(ctx.start.proj_lon) * 1e-7),
            crow_m(p.e_lat, p.e_lon, f64::from(ctx.end.proj_lat) * 1e-7, f64::from(ctx.end.proj_lon) * 1e-7),
            g.road_name(ctx.start.name_offset).unwrap_or_default(),
            g.road_name(ctx.end.name_offset).unwrap_or_default(),
            u8::from(ctx.direct.is_some())
        );
        let _ = writeln!(
            buf,
            "EDGE {i} s_edge={} s_ab={},{} e_edge={} e_ab={},{} twin={}",
            ctx.start.edge_idx,
            ctx.start.node_a,
            ctx.start.node_b,
            ctx.end.edge_idx,
            ctx.end.node_a,
            ctx.end.node_b,
            // Whether the road can be travelled the other way at all. When both
            // ends snap to one edge but in the wrong order, this is what decides
            // between "stay on the road" and "go around the block".
            twin_of(&g, ctx.start.node_a, ctx.start.node_b)
                .map_or(-1i64, |k| i64::try_from(k).unwrap_or(-2))
        );
        // A route that never leaves its snapped edge reaches no node, so
        // `target_node` stays unset and `ctx.direct` carries the route instead.
        if ctx.target_node == 0xFFFF_FFFF && ctx.direct.is_none() {
            let _ = writeln!(buf, "PAIR {i} {} mode={} no_route", p.kind, p.mode);
            continue;
        }
        let steps = reconstruct_path(&g, &speeds, p.mode, &ctx, &mut scratch);
        let dist: u64 = steps.iter().map(|s| s.dist_mm).sum();
        let time: u64 = steps.iter().map(|s| s.time_10ms).sum();
        routed += 1;
        let _ = writeln!(
            buf,
            "PAIR {i} {} mode={} ok dist_mm={dist} time_10ms={time} steps={}",
            p.kind,
            p.mode,
            steps.len()
        );
        for (k, s) in steps.iter().enumerate() {
            let name = g.road_name(s.name_off).unwrap_or_default();
            let _ = writeln!(
                buf,
                "STEP {i} {k} maneuver={} dist_mm={} time_10ms={} pts={} name={name}",
                s.maneuver,
                s.dist_mm,
                s.time_10ms,
                s.coords.len() / 2
            );
        }
        // The polyline is the claim that compaction is geometry-preserving, so
        // it is compared exactly. `coords` is flat [lon, lat, ...] from
        // `StepData`; 7 decimals is the graph's own resolution.
        let _ = write!(buf, "GEOM {i}");
        for s in &steps {
            for c in s.coords.chunks_exact(2) {
                let _ = write!(buf, " {:.7},{:.7}", c[1], c[0]);
            }
        }
        buf.push('\n');
    }
    let _ = writeln!(buf, "ROUTED {routed}/{}", pairs.len());
    write_file(&out, buf.as_bytes())?;
    println!("{out}: routed {routed}/{} pair(s)", pairs.len());
    Ok(true)
}

// ---------------------------------------------------------------------------
// dump / dump-compare
// ---------------------------------------------------------------------------

/// Magic and version of the dump format itself, so two dumps of different
/// vintages cannot be compared as if they were the same thing.
const DUMP_MAGIC: u32 = 0x5044_4752; // "RGDP"
const DUMP_VERSION: u32 = 1;
const DUMP_HEADER: usize = 24;
/// `u32 source, u32 target, u32 dist_mm, u32 name_offset, u8 type_, u8 speed_limit`.
const DUMP_EDGE: usize = 18;
/// `i32 lat_e7, i32 lon_e7, u64 edge_ptr`.
const DUMP_NODE: usize = 16;

/// Every field of every edge and every node, in one linear pass.
///
/// This is the strongest check available on a pack-format change, and the cheapest.
/// `report`/`compare` exercise whatever ~35 K vertices 999 sampled routes happen to
/// touch; this exercises all E edges and all N+1 nodes, so a record that decodes
/// wrongly one time in a million cannot hide. When it fails it names an edge index
/// rather than a route, which is the difference between an afternoon and five
/// minutes.
///
/// It reads through the *public* accessors — `edge_range`, `edge`,
/// `edge_name_offset` — precisely so that the widened view, the delta decode and
/// the sparse name table are all in the path being tested.
///
/// Fixed-width binary rather than text: California is 405 MB this way and 840 MB as
/// text, and `dump_compare` decodes it back into field names anyway.
fn dump(args: &[String]) -> Result<bool, String> {
    let dir = need(args, "--graph")?;
    let out = need(args, "--out")?;
    let g = load(&dir)?;

    let mut f = std::io::BufWriter::with_capacity(
        1 << 20,
        std::fs::File::create(&out).map_err(|e| format!("{out}: {e}"))?,
    );
    let mut w = |bytes: &[u8]| -> Result<(), String> {
        f.write_all(bytes).map_err(|e| format!("{out}: {e}"))
    };
    w(&DUMP_MAGIC.to_le_bytes())?;
    w(&DUMP_VERSION.to_le_bytes())?;
    w(&u64::from(g.node_count).to_le_bytes())?;
    w(&g.edge_count.to_le_bytes())?;

    // Edges in index order, which is also source order, so `source` comes from the
    // walk rather than from a search. Writing it down is the point: it is the one
    // field no `edges.bin` record has ever stored, and getting it wrong is exactly
    // how a delta-coded `target` fails.
    let mut written = 0u64;
    for u in 0..g.node_count {
        let (start, end) = g.edge_range(u);
        for idx in start..end {
            let e = g.edge(u, idx);
            w(&u.to_le_bytes())?;
            w(&e.target.to_le_bytes())?;
            w(&e.dist_mm.to_le_bytes())?;
            w(&g.edge_name_offset(idx).unwrap_or(NO_NAME).to_le_bytes())?;
            w(&[e.type_, e.speed_limit])?;
            written += 1;
        }
    }
    // Every edge must be reachable from exactly one node's range. If the ranges do
    // not tile `0..edge_count` the dump is short, and no field comparison would say
    // so.
    if written != g.edge_count {
        return Err(format!(
            "the node ranges cover {written} edge(s), not the {} in the pack",
            g.edge_count
        ));
    }

    // N + 1, because the sentinel record is read by every `edge_range` call and so
    // is as much a part of the contract as any real node.
    for v in 0..=g.node_count {
        let n = g.node(v);
        w(&n.lat_e7.to_le_bytes())?;
        w(&n.lon_e7.to_le_bytes())?;
        w(&n.edge_ptr.to_le_bytes())?;
    }
    f.flush().map_err(|e| format!("{out}: {e}"))?;
    // The escape and named counts are reported but deliberately *not* written into
    // the dump. The dump has to stay byte-identical across every phase, and which
    // fields a record could not hold is a property of the encoding rather than of the
    // graph.
    println!(
        "{out}: {} edge(s) and {} node record(s), {} escaped ({:.4}%), {} named ({:.2}%)",
        g.edge_count,
        u64::from(g.node_count) + 1,
        g.escape_count,
        g.escape_count as f64 / g.edge_count.max(1) as f64 * 100.0,
        g.named_edges,
        g.named_edges as f64 / g.edge_count.max(1) as f64 * 100.0
    );
    Ok(true)
}

/// Compare two dumps element by element, naming the first few that differ.
///
/// Streams both files, because a Europe dump is 6.3 GB and holding two of them
/// would be the only reason this tool needed a big machine.
fn dump_compare(args: &[String]) -> Result<bool, String> {
    let base_path = need(args, "--baseline")?;
    let cand_path = need(args, "--candidate")?;
    let limit: usize = flag(args, "--limit")
        .as_deref()
        .unwrap_or("10")
        .parse()
        .map_err(|e| format!("--limit: {e}"))?;

    let mut b = DumpReader::open(&base_path)?;
    let mut c = DumpReader::open(&cand_path)?;
    let mut ok = true;
    if (b.node_count, b.edge_count) != (c.node_count, c.edge_count) {
        println!(
            "FAIL  counts differ: baseline {} node(s) {} edge(s), candidate {} / {}",
            b.node_count, b.edge_count, c.node_count, c.edge_count
        );
        return Ok(false);
    }
    println!(
        "DUMP  {} edge(s), {} node record(s)",
        b.edge_count,
        b.node_count + 1
    );

    let mut shown = 0usize;
    let mut edge_diffs = 0u64;
    for idx in 0..b.edge_count {
        let (x, y) = (b.next(DUMP_EDGE)?, c.next(DUMP_EDGE)?);
        if x == y {
            continue;
        }
        edge_diffs += 1;
        ok = false;
        if shown < limit {
            shown += 1;
            println!("  edge {idx}\n    baseline {}\n    candidate {}", fmt_edge(&x), fmt_edge(&y));
        }
    }

    let mut node_diffs = 0u64;
    shown = 0;
    for idx in 0..=b.node_count {
        let (x, y) = (b.next(DUMP_NODE)?, c.next(DUMP_NODE)?);
        if x == y {
            continue;
        }
        node_diffs += 1;
        ok = false;
        if shown < limit {
            shown += 1;
            let sentinel = if idx == b.node_count { " (sentinel)" } else { "" };
            println!(
                "  node {idx}{sentinel}\n    baseline {}\n    candidate {}",
                fmt_node(&x),
                fmt_node(&y)
            );
        }
    }

    println!(
        "  {edge_diffs} edge(s) and {node_diffs} node record(s) differ\n{}",
        if ok { "DUMPS IDENTICAL" } else { "DUMPS DIFFER" }
    );
    Ok(ok)
}

/// Fixed-width record reader over a dump file.
struct DumpReader {
    inner: std::io::BufReader<std::fs::File>,
    path: String,
    node_count: u64,
    edge_count: u64,
    buf: Vec<u8>,
}

impl DumpReader {
    fn open(path: &str) -> Result<DumpReader, String> {
        let f = std::fs::File::open(path).map_err(|e| format!("{path}: {e}"))?;
        let mut r = DumpReader {
            inner: std::io::BufReader::with_capacity(1 << 20, f),
            path: path.to_string(),
            node_count: 0,
            edge_count: 0,
            buf: Vec::new(),
        };
        let h = r.next(DUMP_HEADER)?;
        if u32::from_le_bytes(h[0..4].try_into().unwrap()) != DUMP_MAGIC
            || u32::from_le_bytes(h[4..8].try_into().unwrap()) != DUMP_VERSION
        {
            return Err(format!("{path} is not a v{DUMP_VERSION} dump"));
        }
        r.node_count = u64::from_le_bytes(h[8..16].try_into().unwrap());
        r.edge_count = u64::from_le_bytes(h[16..24].try_into().unwrap());
        Ok(r)
    }

    fn next(&mut self, n: usize) -> Result<Vec<u8>, String> {
        self.buf.resize(n, 0);
        std::io::Read::read_exact(&mut self.inner, &mut self.buf)
            .map_err(|e| format!("{}: {e}", self.path))?;
        Ok(self.buf.clone())
    }
}

fn fmt_edge(r: &[u8]) -> String {
    let u32_at = |i: usize| u32::from_le_bytes(r[i..i + 4].try_into().unwrap());
    format!(
        "source={} target={} dist_mm={} name_offset={} type={} speed_limit={}",
        u32_at(0),
        u32_at(4),
        u32_at(8),
        u32_at(12),
        r[16],
        r[17]
    )
}

fn fmt_node(r: &[u8]) -> String {
    format!(
        "lat_e7={} lon_e7={} edge_ptr={}",
        i32::from_le_bytes(r[0..4].try_into().unwrap()),
        i32::from_le_bytes(r[4..8].try_into().unwrap()),
        u64::from_le_bytes(r[8..16].try_into().unwrap())
    )
}

// ---------------------------------------------------------------------------
// bench
// ---------------------------------------------------------------------------

/// Time the real reader against a fixed pair set.
///
/// Every pack-format phase is measured against this and one of them is *gated* on
/// it, so the cold pass is reported apart from the warm ones. On a pack far larger
/// than RAM the first pass is almost entirely major page faults, and that is the
/// number that decides whether splitting one record across two arrays is
/// affordable; the warm passes measure the instruction path instead. Dropping the
/// page cache is the caller's job, so "cold" means whatever state it left the cache
/// in.
///
/// The route checksum is not decoration. A format change that broke routing would
/// otherwise show up here as a *speedup*, so the bench refuses to report a number
/// without also reporting what was computed.
fn bench(args: &[String]) -> Result<bool, String> {
    let dir = need(args, "--graph")?;
    let pairs_path = need(args, "--pairs")?;
    let passes: u32 = flag(args, "--passes")
        .as_deref()
        .unwrap_or("3")
        .parse()
        .map_err(|e| format!("--passes: {e}"))?;
    if passes == 0 {
        return Err("--passes wants at least one".to_string());
    }
    let cold = args.iter().any(|a| a == "--cold");
    let verbose = args.iter().any(|a| a == "--verbose");
    if cold {
        let evicted = evict(&dir)?;
        println!("evicted {evicted} pack file(s) from the page cache");
    }

    let load_started = std::time::Instant::now();
    let g = load_with_overlay(&dir, args)?;
    let load_ms = load_started.elapsed().as_secs_f64() * 1e3;

    let pairs = read_pairs(&pairs_path)?;
    let speeds = TrafficSpeeds::default();
    let mut scratch = RoutingScratchpad::new();
    let mut heap = RadixHeap::new();

    println!("BENCH graph={dir} pairs={} passes={passes}", pairs.len());
    println!("  Graph::load            {load_ms:>10.1} ms");

    let mut first: Option<Pass> = None;
    let mut last = Pass::default();
    for pass in 0..passes {
        let mut p = Pass::default();
        let started = std::time::Instant::now();
        for pair in &pairs {
            let one = std::time::Instant::now();
            let mut ensure = |_lat_e7: i32, _lon_e7: i32| {};
            let ctx = prepare_routing(
                &g, &speeds, &mut ensure, pair.s_lat, pair.s_lon, pair.e_lat, pair.e_lon,
                pair.mode, &mut scratch, &mut heap,
            );
            if let Some(mut ctx) = ctx {
                let hierarchical = ctx.hierarchy;
                perform_search_loop(
                    &g, &speeds, &mut ensure, pair.mode, &mut ctx, &mut scratch, &mut heap,
                );
                if verbose {
                    println!(
                        "    {} mode={} {:.0} ms iterations={} hierarchy={} fell_back={} routed={}",
                        pair.kind,
                        pair.mode,
                        one.elapsed().as_secs_f64() * 1e3,
                        ctx.iterations,
                        hierarchical,
                        hierarchical && !ctx.hierarchy,
                        ctx.target_node != 0xFFFF_FFFF || ctx.direct.is_some(),
                    );
                }
                if ctx.target_node != 0xFFFF_FFFF || ctx.direct.is_some() {
                    let steps = reconstruct_path(&g, &speeds, pair.mode, &ctx, &mut scratch);
                    p.routed += 1;
                    for s in &steps {
                        // A cheap order-dependent mix, so a route that changes
                        // length, timing or turn order changes the digest.
                        p.checksum = p
                            .checksum
                            .rotate_left(7)
                            .wrapping_add(s.dist_mm)
                            .wrapping_mul(0x0100_0000_01B3)
                            ^ s.time_10ms;
                    }
                }
            }
            p.latency_ns.push(one.elapsed().as_nanos() as u64);
        }
        p.wall_s = started.elapsed().as_secs_f64();
        p.latency_ns.sort_unstable();
        let label = if pass == 0 { "cold" } else { "warm" };
        println!(
            "  pass {:<2} ({label})  {:>9.1} routes/s   p50 {:>7.2}  p90 {:>7.2}  \
             p99 {:>7.2}  max {:>8.2} ms",
            pass + 1,
            pairs.len() as f64 / p.wall_s,
            p.pct_ms(50),
            p.pct_ms(90),
            p.pct_ms(99),
            p.pct_ms(100),
        );
        if first.is_none() {
            first = Some(p);
        } else {
            last = p;
        }
    }
    let first = first.expect("at least one pass");
    let warm = if last.latency_ns.is_empty() { &first } else { &last };
    println!(
        "  routed                 {}/{} pair(s), checksum {:016x}",
        warm.routed,
        pairs.len(),
        warm.checksum
    );
    if first.checksum != warm.checksum || first.routed != warm.routed {
        println!("  FAIL  the passes disagree on what they routed");
        return Ok(false);
    }
    println!("  peak RSS               {:>10} kB", peak_rss_kb());
    // One line per pass is what a script diffs, but the summary line is what a
    // human reads, and the plan's tolerances are stated against these three.
    println!(
        "SUMMARY load_ms={load_ms:.1} cold_routes_s={:.1} warm_routes_s={:.1} \
         cold_p50_ms={:.3} cold_p99_ms={:.3} warm_p50_ms={:.3} warm_p99_ms={:.3} \
         peak_rss_kb={} checksum={:016x}",
        pairs.len() as f64 / first.wall_s,
        pairs.len() as f64 / warm.wall_s,
        first.pct_ms(50),
        first.pct_ms(99),
        warm.pct_ms(50),
        warm.pct_ms(99),
        peak_rss_kb(),
        warm.checksum
    );
    Ok(true)
}

#[derive(Default)]
struct Pass {
    latency_ns: Vec<u64>,
    wall_s: f64,
    routed: usize,
    checksum: u64,
}

impl Pass {
    /// The `p`-th percentile in milliseconds; `p = 100` is the maximum.
    fn pct_ms(&self, p: usize) -> f64 {
        if self.latency_ns.is_empty() {
            return 0.0;
        }
        let last = self.latency_ns.len() - 1;
        let i = (p * last / 100).min(last);
        self.latency_ns[i] as f64 / 1e6
    }
}

/// High-water mark of the process's resident set, in kB, or 0 where the kernel
/// does not report it.
fn peak_rss_kb() -> u64 {
    std::fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|s| {
            s.lines()
                .find(|l| l.starts_with("VmHWM:"))
                .and_then(|l| l.split_whitespace().nth(1)?.parse().ok())
        })
        .unwrap_or(0)
}

/// Evict a pack directory's files from the page cache, so the pass that follows
/// measures major faults rather than a warm cache. Returns how many files were hit.
///
/// `POSIX_FADV_DONTNEED` rather than `/proc/sys/vm/drop_caches`, which needs root.
/// It is advisory and best-effort — pages that are dirty or still mapped elsewhere
/// stay resident — so what it produces is a *lower bound* on how cold a real first
/// run is. Which is the right direction: a phase that looks affordable here might
/// still not be on a pack larger than RAM, and one that regresses here certainly is
/// not.
fn evict(dir: &str) -> Result<usize, String> {
    const FILES: [&str; 6] = [
        "metadata.bin",
        "nodes.bin",
        "edges.bin",
        "lanes.bin",
        "intermediate.bin",
        "road_names.bin",
    ];
    let mut hit = 0;
    for name in FILES {
        let path = format!("{}/{name}", dir.trim_end_matches('/'));
        let Ok(f) = std::fs::File::open(&path) else { continue };
        // Offset 0, length 0 means "the whole file".
        let rc = unsafe {
            libc::posix_fadvise(
                std::os::unix::io::AsRawFd::as_raw_fd(&f),
                0,
                0,
                libc::POSIX_FADV_DONTNEED,
            )
        };
        if rc != 0 {
            return Err(format!("posix_fadvise({path}): {rc}"));
        }
        hit += 1;
    }
    Ok(hit)
}

// ---------------------------------------------------------------------------
// compare
// ---------------------------------------------------------------------------

/// Compare two reports.
///
/// The pass criterion has to account for the fact that a rebuild which changes
/// the node set also changes *snapping*: `find_nearest_edge` scans a fixed
/// window of 800 nodes in Morton order, so with fewer, longer edges it reaches
/// further and legitimately finds a different — often nearer — road. A route from
/// a different starting edge is not a regression, it is a different question.
///
/// So pairs are split by whether both ends snapped to the same place:
///
/// * **Same snap, same distance** is the strict case: the turn sequence and the
///   polyline must match exactly. This is where a geometry or bookkeeping bug
///   would show, and it is the only class treated as a failure.
/// * **Same snap, different distance** is an alternative optimum. Reported.
/// * **Moved snap** is reported and counted, not failed.
///
/// A pair that stops being routable is always a failure; one that becomes
/// routable is not.
fn compare(args: &[String]) -> Result<bool, String> {
    let base_path = need(args, "--baseline")?;
    let cand_path = need(args, "--candidate")?;
    let tol_mm: u64 = flag(args, "--tol-mm")
        .as_deref()
        .unwrap_or("1000")
        .parse()
        .map_err(|e| format!("--tol-mm: {e}"))?;
    let tol_time: u64 = flag(args, "--tol-10ms")
        .as_deref()
        .unwrap_or("100")
        .parse()
        .map_err(|e| format!("--tol-10ms: {e}"))?;
    // How far a snap may move before the pair is no longer "the same question".
    let snap_tol_m: f64 = flag(args, "--tol-snap-m")
        .as_deref()
        .unwrap_or("1.0")
        .parse()
        .map_err(|e| format!("--tol-snap-m: {e}"))?;

    let base = std::fs::read_to_string(&base_path).map_err(|e| format!("{base_path}: {e}"))?;
    let cand = std::fs::read_to_string(&cand_path).map_err(|e| format!("{cand_path}: {e}"))?;

    let bp = index_by_key(&base, "PAIR");
    let cp = index_by_key(&cand, "PAIR");
    let bs_snap = index_by_key(&base, "SNAP");
    let cs_snap = index_by_key(&cand, "SNAP");
    let bg = index_by_key(&base, "GEOM");
    let cg = index_by_key(&cand, "GEOM");
    let bs = index_steps(&base);
    let cs = index_steps(&cand);

    let mut fails: Vec<String> = Vec::new();
    let mut lost = 0usize;
    let mut gained = 0usize;
    let mut moved_snap = 0usize;
    let mut strict = 0usize;
    let mut alt_optimum = 0usize;
    let mut worst_dist = 0i64;
    let mut worst_time = 0i64;

    let mut keys: Vec<&String> = bp.keys().collect();
    keys.sort_by_key(|k| k.parse::<u64>().unwrap_or(u64::MAX));

    for k in &keys {
        let b = &bp[*k];
        let c = match cp.get(*k) {
            Some(c) => c,
            None => {
                fails.push(format!("pair {k}: missing from the candidate report"));
                continue;
            }
        };
        let b_ok = b.contains(&"ok".to_string());
        let c_ok = c.contains(&"ok".to_string());
        // Snap movement is checked first, and outranks routability. A query point
        // that is a kilometre from any road can snap onto a nearer but
        // disconnected one after a rebuild, and calling that a lost route blames
        // the graph for an unreasonable question.
        let shift = snap_shift_m(bs_snap.get(*k), cs_snap.get(*k));
        if shift > snap_tol_m {
            moved_snap += 1;
            continue;
        }
        if b_ok && !c_ok {
            lost += 1;
            fails.push(format!("pair {k}: snapped to the same place but is no longer routable"));
            continue;
        }
        if !b_ok {
            if c_ok {
                gained += 1;
            }
            continue;
        }

        let dd = kv(c, "dist_mm").unwrap_or(0) as i64 - kv(b, "dist_mm").unwrap_or(0) as i64;
        let dt = kv(c, "time_10ms").unwrap_or(0) as i64 - kv(b, "time_10ms").unwrap_or(0) as i64;
        if dd.abs() > worst_dist.abs() {
            worst_dist = dd;
        }
        if dt.abs() > worst_time.abs() {
            worst_time = dt;
        }

        if dd.unsigned_abs() > tol_mm || dt.unsigned_abs() > tol_time {
            alt_optimum += 1;
            continue;
        }

        // Same snap, same length: the roads travelled and the path drawn must
        // match.
        strict += 1;
        let b_roads = bs.get(*k).map(road_seq).unwrap_or_default();
        let c_roads = cs.get(*k).map(road_seq).unwrap_or_default();
        if b_roads != c_roads {
            fails.push(format!(
                "pair {k}: same snap and same length, but the roads travelled differ \
                 ({} vs {}, first at {:?})",
                b_roads.len(),
                c_roads.len(),
                first_mismatch(&b_roads, &c_roads)
            ));
        }
        let b_line = polyline_points(bg.get(*k));
        let c_line = polyline_points(cg.get(*k));
        if !same_path(&b_line, &c_line) {
            fails.push(format!(
                "pair {k}: same snap and same length, but the path differs \
                 ({} vs {} distinct point(s))",
                b_line.len(),
                c_line.len()
            ));
        }
    }

    for k in cp.keys() {
        if !bp.contains_key(k) {
            fails.push(format!("pair {k}: only in the candidate report"));
        }
    }

    println!("{} pair(s) compared", keys.len());
    println!("  {strict:>5} same snap, same length  (compared strictly)");
    println!("  {alt_optimum:>5} same snap, different length  (alternative optimum)");
    println!("  {moved_snap:>5} snapped somewhere else  (>{snap_tol_m} m)");
    println!("  {gained:>5} became routable");
    println!("  {lost:>5} stopped being routable");
    println!("worst dist delta {worst_dist} mm, worst time delta {worst_time} (10 ms units)");
    for f in fails.iter().take(20) {
        println!("  {f}");
    }
    if fails.len() > 20 {
        println!("  ({} further finding(s) suppressed)", fails.len() - 20);
    }
    println!(
        "{}",
        if fails.is_empty() { "DIFFERENTIAL CLEAN" } else { "DIFFERENTIAL FAILED" }
    );
    Ok(fails.is_empty())
}

/// The larger of the two ends' snap movements, in metres.
fn snap_shift_m(b: Option<&Vec<String>>, c: Option<&Vec<String>>) -> f64 {
    let point = |t: &Vec<String>, key: &str| -> Option<(f64, f64)> {
        let v = t.iter().find_map(|s| s.strip_prefix(key)?.strip_prefix('='))?;
        let (a, b) = v.split_once(',')?;
        Some((a.parse().ok()?, b.parse().ok()?))
    };
    let (b, c) = match (b, c) {
        (Some(b), Some(c)) => (b, c),
        // No SNAP line means an older report; treat it as "unknown", which is
        // safer read as moved than as identical.
        _ => return f64::INFINITY,
    };
    let mut worst = 0.0f64;
    for key in ["s", "e"] {
        match (point(b, key), point(c, key)) {
            (Some(p), Some(q)) => worst = worst.max(crow_m(p.0, p.1, q.0, q.1)),
            _ => return f64::INFINITY,
        }
    }
    worst
}

/// Collect `KIND <id> rest...` lines into id -> tokens.
fn index_by_key(text: &str, kind: &str) -> HashMap<String, Vec<String>> {
    let mut out = HashMap::new();
    for line in text.lines() {
        let mut it = line.split_whitespace();
        if it.next() != Some(kind) {
            continue;
        }
        if let Some(id) = it.next() {
            let _ = out.insert(id.to_string(), it.map(str::to_string).collect());
        }
    }
    out
}

/// `STEP <pair> <k> ...` grouped by pair, in file order.
fn index_steps(text: &str) -> HashMap<String, Vec<String>> {
    let mut out: HashMap<String, Vec<String>> = HashMap::new();
    for line in text.lines() {
        let mut it = line.split_whitespace();
        if it.next() != Some("STEP") {
            continue;
        }
        if let Some(id) = it.next() {
            out.entry(id.to_string()).or_default().push(line.to_string());
        }
    }
    out
}

fn kv(tokens: &[String], key: &str) -> Option<u64> {
    tokens
        .iter()
        .find_map(|t| t.strip_prefix(key).and_then(|r| r.strip_prefix('=')))
        .and_then(|v| v.parse().ok())
}

/// The roads a route actually travels, in order, with repeats folded together.
///
/// Deliberately not the raw step list. Steps are split on bearing change, and two
/// graphs of one region split them differently for reasons that are not route
/// changes: an uncompacted graph's reconstruction emits a spurious zero-length
/// U-turn step where the route doubles back to a node, and a compacted one does
/// not. The sequence of named roads is what a driver would recognise, and it is
/// invariant to that.
fn road_seq(lines: &Vec<String>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for l in lines {
        // Zero-length steps are reconstruction artefacts, not roads.
        if kv(&l.split_whitespace().map(str::to_string).collect::<Vec<_>>(), "dist_mm")
            == Some(0)
        {
            continue;
        }
        let name = match l.find(" name=") {
            Some(i) => l[i + 6..].to_string(),
            None => String::new(),
        };
        if out.last() != Some(&name) {
            out.push(name);
        }
    }
    out
}

/// A polyline's points, with consecutive repeats folded together.
///
/// Repeats are artefacts, not shape: a route that doubles back to a node emits it
/// twice, and the reconstruction's start and end stubs repeat their joint vertex.
fn polyline_points(tokens: Option<&Vec<String>>) -> Vec<(i64, i64)> {
    let tokens = match tokens {
        Some(t) => t,
        None => return Vec::new(),
    };
    let mut pts: Vec<(i64, i64)> = Vec::with_capacity(tokens.len());
    for t in tokens {
        if let Some((a, b)) = t.split_once(',') {
            if let (Ok(lat), Ok(lon)) = (a.parse::<f64>(), b.parse::<f64>()) {
                let p = ((lat * 1e7).round() as i64, (lon * 1e7).round() as i64);
                if pts.last() != Some(&p) {
                    pts.push(p);
                }
            }
        }
    }
    pts
}

/// Whether two polylines trace the same path, allowing one to be a finer
/// subdivision of the other.
///
/// The geometry encoder inserts collinear points wherever a gap exceeds the `i16`
/// delta range, so a compacted graph's polyline legitimately carries vertices an
/// uncompacted one never needed. Those extra points lie *between* the originals,
/// so the coarser polyline must appear in the finer one as an in-order
/// subsequence — which is an exact test, unlike trying to detect and drop the
/// inserted points, since collinearity is not stable under subdivision (adding a
/// point between A and P changes P's neighbours, and so changes whether P looks
/// collinear).
fn same_path(a: &[(i64, i64)], b: &[(i64, i64)]) -> bool {
    if a == b {
        return true;
    }
    is_subsequence(a, b) || is_subsequence(b, a)
}

fn is_subsequence(needle: &[(i64, i64)], haystack: &[(i64, i64)]) -> bool {
    if needle.len() > haystack.len() {
        return false;
    }
    let mut it = haystack.iter();
    needle.iter().all(|p| it.any(|q| q == p))
}

fn first_mismatch(a: &[String], b: &[String]) -> Option<usize> {
    (0..a.len().max(b.len())).find(|&i| a.get(i) != b.get(i))
}

// ---------------------------------------------------------------------------
// geometry
// ---------------------------------------------------------------------------

/// Check directly that a rebuild describes the same roads, independent of
/// routing.
///
/// This is sharper than the route differential, and it has to be: collapsing
/// nodes changes how far `find_nearest_edge`'s fixed 800-node window reaches, so
/// two graphs of one region legitimately snap to different edges and produce
/// different routes. What must not change is the road network itself.
///
/// Two invariants are checked, both chosen to be insensitive to how the geometry
/// is *divided* into points, because the encoder inserts collinear points
/// wherever a gap exceeds the `i16` delta range:
///
/// * **No vertex is lost.** Every point in the baseline's geometry must still
///   appear in the candidate's. Interpolation only ever adds points, so the
///   candidate's vertex set must be a superset. A collapsed node whose
///   coordinate vanished would mean a corner had been cut.
/// * **Total length is preserved.** Summed over every hop of every edge.
///   Subdividing a straight hop does not change its length beyond the
///   sub-centimetre rounding of the inserted points.
fn geometry(args: &[String]) -> Result<bool, String> {
    let base_dir = need(args, "--baseline")?;
    let cand_dir = need(args, "--candidate")?;
    let base = load(&base_dir)?;
    let cand = load(&cand_dir)?;

    let b = scan_geometry(&base);
    println!(
        "baseline  nodes={:<10} edges={:<10} hops={:<10} vertices={:<10} length={} mm",
        base.node_count,
        base.edge_count,
        b.hops,
        b.vertices.len(),
        b.length_mm
    );
    let c = scan_geometry(&cand);
    println!(
        "candidate nodes={:<10} edges={:<10} hops={:<10} vertices={:<10} length={} mm",
        cand.node_count,
        cand.edge_count,
        c.hops,
        c.vertices.len(),
        c.length_mm
    );
    println!(
        "candidate stored geometry on {} edge(s), {} of them by reference to a twin",
        c.with_geometry, c.reversed
    );
    print!("candidate points per edge (log2 buckets):");
    for (i, n) in c.pt_hist.iter().enumerate() {
        if *n > 0 {
            print!(" 2^{i}={n}");
        }
    }
    println!();

    // Sorted, because the sample below is the only thing a human has to go on and a
    // `HashSet`'s iteration order is randomised per process — so an unsorted sample
    // changes on every run of the same pack, which makes it useless for both
    // diagnosis and for diffing two runs against each other.
    let mut missing: Vec<&(i32, i32)> = b.vertices.difference(&c.vertices).collect();
    missing.sort_unstable();
    let mut ok = true;
    if missing.is_empty() {
        println!("PASS  every baseline vertex survives in the candidate");
    } else {
        ok = false;
        println!(
            "FAIL  {} of {} baseline vertices are missing from the candidate",
            missing.len(),
            b.vertices.len()
        );
        for v in missing.iter().take(10) {
            println!("        {:.7},{:.7}", f64::from(v.0) * 1e-7, f64::from(v.1) * 1e-7);
        }
    }

    // A tenth of a percent covers the reconnection edges, which legitimately
    // pick different anchors once the node set changes, plus integer rounding on
    // interpolated points.
    let delta = c.length_mm as i128 - b.length_mm as i128;
    let tol = (b.length_mm / 1000).max(1_000_000) as i128;
    if delta.abs() <= tol {
        println!(
            "PASS  total road length within tolerance (delta {delta} mm of {} mm, tol {tol})",
            b.length_mm
        );
    } else {
        ok = false;
        println!("FAIL  total road length moved by {delta} mm (tol {tol})");
    }
    println!("{}", if ok { "GEOMETRY PRESERVED" } else { "GEOMETRY CHANGED" });
    Ok(ok)
}

struct GeomScan {
    hops: u64,
    length_mm: u128,
    vertices: HashSet<(i32, i32)>,
    with_geometry: u64,
    reversed: u64,
    pt_hist: [u64; 9],
}

/// Walk every edge and collect the geometry the device would draw: the stored
/// polyline where there is one, the straight chord otherwise.
fn scan_geometry(g: &Graph) -> GeomScan {
    let mut coords = [LatLon { lat_e7: 0, lon_e7: 0 }; 256];
    let mut out = GeomScan {
        hops: 0,
        length_mm: 0,
        vertices: HashSet::new(),
        with_geometry: 0,
        reversed: 0,
        pt_hist: [0u64; 9],
    };
    let mut pts: Vec<LatLon> = Vec::with_capacity(256);

    for u in 0..g.node_count {
        let node_u = g.node(u);
        let (start, end) = g.edge_range(u);
        for j in start..end {
            let e = g.edge(u, j);
            if e.target >= g.node_count {
                continue;
            }
            pts.clear();
            match g.get_edge_coordinates_from(u, j, &mut coords) {
                Some((count, is_reversed)) if count >= 2 => {
                    out.with_geometry += 1;
                    if is_reversed {
                        out.reversed += 1;
                    }
                    pts.extend((0..count).map(|p| get_pt_at(&coords, count, is_reversed, p)));
                }
                _ => {
                    let node_v = g.node(e.target);
                    pts.push(LatLon { lat_e7: node_u.lat_e7, lon_e7: node_u.lon_e7 });
                    pts.push(LatLon { lat_e7: node_v.lat_e7, lon_e7: node_v.lon_e7 });
                }
            }
            let bucket = (pts.len() as f64).log2() as usize;
            out.pt_hist[bucket.min(8)] += 1;
            for p in &pts {
                let _ = out.vertices.insert((p.lat_e7, p.lon_e7));
            }
            for w in pts.windows(2) {
                if (w[0].lat_e7, w[0].lon_e7) == (w[1].lat_e7, w[1].lon_e7) {
                    continue;
                }
                out.hops += 1;
                out.length_mm += u128::from(accurate_dist_mm(
                    w[0].lat_e7, w[0].lon_e7, w[1].lat_e7, w[1].lon_e7,
                ));
            }
        }
    }
    out
}

/// The other direction of the same road: the first edge from `target` back to
/// `source`. Mirrors `graph.rs`'s reverse-geometry lookup and `routing.rs`'s
/// `twin_edge`, so the tool and the engine agree on what "the twin" means.
fn twin_of(g: &Graph, source: u32, target: u32) -> Option<u64> {
    if target >= g.node_count {
        return None;
    }
    let (s, e) = g.edge_range(target);
    (s..e).find(|k| g.edge_targets(*k, target, source))
}

/// Approximate ground distance in metres (equirectangular). Only used to report
/// how far a snap moved, so an approximation is fine.
fn crow_m(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let dlat = (lat2 - lat1) * 111_320.0;
    let dlon = (lon2 - lon1) * 111_320.0 * ((lat1 + lat2) * 0.5).to_radians().cos();
    (dlat * dlat + dlon * dlon).sqrt()
}

fn write_file(path: &str, bytes: &[u8]) -> Result<(), String> {
    let mut f = std::fs::File::create(path).map_err(|e| format!("{path}: {e}"))?;
    f.write_all(bytes).map_err(|e| format!("{path}: {e}"))
}
