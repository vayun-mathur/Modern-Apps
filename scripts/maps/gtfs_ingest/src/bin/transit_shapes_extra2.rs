//! The `run` pipeline for `transit_shapes`, moved out of `transit_shapes.rs`
//! to keep the bin under the file-size limit. Behaviour-identical.

use super::*;
use rayon::prelude::*;
use std::time::Instant;

/// Where the lines come from: raw GTFS feeds, or the `world.transit` pack built from them.
#[derive(Clone, Copy)]
pub(crate) enum Inputs<'a> {
    Feeds(&'a [FeedSpec]),
    Pack(&'a Path),
}

/// Untimed entry, kept for the unit tests in `transit_shapes_extra`.
#[cfg(test)]
pub(crate) fn run(out_path: &Path, specs: &[FeedSpec]) -> Result<(), String> {
    run_with_timer(out_path, Inputs::Feeds(specs), None)
}

/// Timed pipeline entry: ticks `parse-feeds` over the feed loop below, the
/// corridor stages inside [`expand_corridor_spans`], then `write-output` over
/// the sort and file write, and emits the `TIMING` line before the human
/// summary. `None` skips every tick, which is what the tests drive via [`run`].
pub(crate) fn run_with_timer(out_path: &Path, inputs: Inputs<'_>, timer: Option<PhaseTimer>) -> Result<(), String> {
    run_windowed(out_path, inputs, timer, FOLD_WINDOW)
}

/// [`run_with_timer`] with the cover gates' speculative window as a parameter, so a test can
/// pin that the window changes nothing: a window of one line is the sequential fold exactly.
pub(crate) fn run_windowed(
    out_path: &Path,
    inputs: Inputs<'_>,
    mut timer: Option<PhaseTimer>,
    fold_window: usize,
) -> Result<(), String> {
    let fold_window = fold_window.max(1);
    let mut seen: HashSet<(u64, u32)> = HashSet::new();
    let mut lines: Vec<Line> = Vec::new();
    // Track already drawn, per colour and per mode. Two of one colour must never draw as
    // parallel lines — nothing distinguishes them, so the second is pure over-draw — and what
    // stops that is the slot, which is assigned per colour so they land on top of each other.
    // This drops the ones that are wholly redundant as well, so the layer does not carry a
    // route's two directions and every other feed's copy of them.
    //
    // The per-mode cover is for a line wearing its mode's fallback colour, which means the
    // feed gave it none: if that track is already drawn in *someone's* colour, a line with
    // no colour of its own should not be drawn over it in a guessed one.
    let mut by_color: BTreeMap<(&'static str, u32), bundle::Covered> = BTreeMap::new();
    let mut by_mode: BTreeMap<&'static str, bundle::Covered> = BTreeMap::new();
    let (mut kept, mut without_shape, mut deduped, mut fell_back) = (0usize, 0usize, 0usize, 0usize);
    let (mut shapes_read, mut merged) = (0usize, 0usize);
    let mut crowded = 0usize;

    // Feeds are parsed in parallel and folded in sequentially.
    //
    // Parsing is pure per feed and was most of the wall clock: a world-scale set spent ~40 of
    // its 54 minutes here, single-threaded, on machines with dozens of idle cores. The fold
    // below cannot move — `seen`, `by_color` and `by_mode` are order-dependent, and feed order
    // is what decides which duplicate of a shared alignment survives — so only the reads are
    // spread out, and their results are put back into spec order before any of them is folded.
    // The output is therefore bit-identical to the sequential version.
    //
    // Chunked rather than read-all-then-fold: a chunk of twice the pool keeps every core busy
    // while capping how many parsed feeds are alive at once, so peak memory follows the pool
    // size and not the number of feeds.
    //
    // ONE subphase for the whole feed loop below: thousands of feeds must not
    // produce thousands of entries. The sequential fold rides along inside it.
    if let Some(t) = timer.as_mut() {
        t.switch("parse-feeds");
    }
    let parse_start = Instant::now();
    let threads = gtfs_ingest::par::threads();
    let chunk_size = threads.saturating_mul(2).max(1);

    let total = match inputs {
        Inputs::Feeds(specs) => specs.len(),
        Inputs::Pack(_) => 1,
    };
    let chunks: Vec<&[FeedSpec]> = match inputs {
        Inputs::Feeds(specs) => specs.chunks(chunk_size).collect(),
        // One pass over the whole pack: it is already one merged, ordered set.
        Inputs::Pack(_) => vec![&[]],
    };
    let mut processed = 0usize;
    for chunk in chunks {
        let parsed = match inputs {
            Inputs::Pack(path) => vec![(0, super::pack::pack_lines(path))],
            Inputs::Feeds(_) => parse_chunk(chunk, threads),
        };

        // Feeds unwrapped in spec order and their lines gathered, so the dedup key of every
        // line can be computed wide before the sequential fold. Stats accumulate here so an
        // unreadable feed still surfaces its error in feed order, exactly as before.
        let mut chunk_lines: Vec<Line> = Vec::new();
        for (_, feed) in parsed {
            let feed = feed?;
            kept += feed.routes_kept;
            without_shape += feed.routes_without_shape;
            shapes_read += feed.shapes_read;
            chunk_lines.extend(feed.lines);
            processed += 1;
            print_feed_meter(processed, total, &parse_start);
        }

        // The dedup key is a content hash of the geometry, so it is order-independent and every
        // line's can be computed in parallel.
        let keys: Vec<(u64, u32)> = chunk_lines
            .par_iter()
            .map(|line| (polyline_hash(&line.points), line.color))
            .collect();

        // Across feeds as well as within one, so an alignment published by both a city feed and
        // the regional feed that merges it draws once.
        //
        // Keyed on the colour too, which the hash alone was not: two services running the same
        // track are routinely published against one `shape_id`, and dropping one of them because
        // its geometry had been seen is how a corridor loses a line. This is only the fast path
        // for the subtraction below, which would reach the same answer the slow way.
        //
        // Settled for the whole chunk before any cover gate runs: every line reaches
        // `seen.insert` whatever the gates later decide about it, so which lines survive it is a
        // function of the key sequence alone, and decides which duplicate survives, in feed
        // order, exactly as the interleaved loop did.
        let mut survivors: Vec<Line> = Vec::with_capacity(chunk_lines.len());
        for (line, key) in chunk_lines.into_iter().zip(keys) {
            if seen.insert(key) {
                survivors.push(line);
            } else {
                deduped += 1;
            }
        }

        // The cover gates. They are order-dependent — each line is judged against everything
        // accepted before it — and this loop used to be the whole run: 293 of a planet's 380 s,
        // on one core, walking and probing ~52 k lines one after another.
        //
        // Now speculative, and still exact. A window of lines is walked and judged in parallel
        // against the covers as they stand at the window's start, then resolved in order. A
        // speculated per-sample answer can only be stale where a line accepted earlier in the
        // same window appended samples to a cell that answer read (`Covered::changed_near`
        // against the cover's mark at speculation time), and a `true` is never stale, since both
        // gates only grow as samples are appended. So resolution re-asks the live cover only for
        // the samples that answered `false` beside such a cell — usually none, since a colour's
        // cover changes only when a line of that colour was accepted earlier in the window.
        // Every other answer is the one the sequential loop would have computed. Adds stay
        // sequential and in order, so the covers evolve exactly as before, and the window size
        // decides how much is re-asked, never what is decided.
        let mut rest = survivors.into_iter();
        loop {
            let window: Vec<Line> = rest.by_ref().take(fold_window).collect();
            if window.is_empty() {
                break;
            }
            let specs: Vec<Speculated> =
                window.par_iter().map(|line| speculate(line, &by_color, &by_mode)).collect();
            // Freed off this thread: each line's samples were allocated on a worker, and handing
            // a few hundred MB back to other threads' arenas one line at a time was a fifth of
            // this loop.
            let mut spent: Vec<(Vec<bundle::Walked>, Vec<bundle::Cell>, Vec<u32>)> = Vec::with_capacity(window.len());
            for (line, spec) in window.into_iter().zip(specs) {
                let Speculated { walked, cells, drawn, uncovered, redundant, crowd, gate_mark, mode_mark } = spec;
                let spent_len = spent.len();
                spent.push((walked, cells, uncovered));
                let (walked, cells, uncovered) = {
                    let (w, c, u) = &spent[spent_len];
                    (w.as_slice(), c.as_slice(), u.as_slice())
                };
                let gate = if line.fallback { by_mode.get(line.mode) } else { by_color.get(&(line.mode, line.color)) };
                let redundant = redundant
                    || gate.is_some_and(|g| {
                        if g.mark() == gate_mark {
                            return false;
                        }
                        recount_reaches(g, gate_mark, walked, cells, drawn, uncovered)
                    });
                if redundant {
                    merged += 1;
                    continue;
                }
                // The gates above are colour-scoped, deliberately: two services sharing a track
                // are two real services and both should draw. On a planet that stops being true.
                // One alignment is republished by a city feed, the regional feed containing it
                // and a national feed on top, each with its own `route_color` and often its own
                // `route_type`, so each reads as a distinct service and claims its own lane. That
                // is what makes one railway render as fifteen jagged parallel lines.
                //
                // A ceiling rather than a ban, so the two-services-on-one-track case the tests
                // pin still works. Above it the track is already saying everything it can.
                //
                // Intercity lines are exempt: at country zoom they are the only transit drawn, so
                // losing one to a crowded shared corridor (the Northeast Corridor carries a dozen
                // commuter services) would cut a gap in the national network.
                if !line.intercity {
                    // Speculated for every non-intercity line speculation did not find redundant,
                    // which is every line that can reach here.
                    let over = crowd.expect("crowd speculated")
                        || by_mode.get(line.mode).is_some_and(|m| {
                            m.mark() != mode_mark && recrowd_reaches(m, mode_mark, walked, cells)
                        });
                    if over {
                        crowded += 1;
                        continue;
                    }
                }
                let mode_cover = by_mode.entry(line.mode).or_default();
                let color_cover = by_color.entry((line.mode, line.color)).or_default();
                rayon::join(
                    || mode_cover.add_tagged_walked_in(walked, cells, line.color),
                    || color_cover.add_tagged_walked_in(walked, cells, 0),
                );
                if line.fallback {
                    fell_back += 1;
                }
                lines.push(line);
            }
            // On a one-thread pool a spawned job would only run once this thread goes idle -
            // at the end of the run - so every window's samples would pile up until then.
            if rayon::current_num_threads() > 1 {
                rayon::spawn(move || drop(spent));
            } else {
                drop(spent);
            }
        }
    }
    eprintln!();
    drop(by_color);
    drop(by_mode);

    // Which routes share a corridor, which lane each takes in it, and where each line has
    // to be cut for that lane to change. Once, over the whole set: neither the tiler nor
    // the renderer sees more than one tile, so deciding it there is what puts a jog in every
    // route at every tile seam.
    let bundled = expand_corridor_spans(&mut lines, timer.as_mut());
    if let Some(t) = timer.as_mut() {
        t.switch("write-output");
    }

    // Deterministic output, so a rebuild produces a byte-identical layer and the
    // tile diff is empty when nothing changed. Feed order already fixes which
    // duplicate survives; this fixes the order they are written in.
    lines.par_sort_by(|a, b| {
        a.points
            .cmp(&b.points)
            .then_with(|| a.color.cmp(&b.color))
            .then_with(|| a.mode.cmp(b.mode))
            .then_with(|| (a.ordinal, a.lanes, a.taper).cmp(&(b.ordinal, b.lanes, b.taper)))
            .then_with(|| a.route.cmp(&b.route))
    });

    let file = std::fs::File::create(out_path)
        .map_err(|e| format!("cannot write {}: {e}", out_path.display()))?;
    // Large sequential writes: the output disk compresses per extent.
    let mut out = BufWriter::with_capacity(8 << 20, file);
    // Each line's JSON depends on that line alone, so blocks of lines are formatted wide and
    // written in order.
    for block in lines.chunks(4096) {
        let texts: Vec<Result<Vec<u8>, String>> = block.par_iter().map(feature_json).collect();
        for text in texts {
            out.write_all(&text?).map_err(io_err)?;
        }
    }
    out.flush().map_err(io_err)?;

    // TIMING before the human summary: stdout carries facts + TIMING only.
    if let Some(t) = timer {
        t.finish();
    }

    println!(
        "transit_shapes: wrote {} ({} feed(s), {kept} rail route(s) kept, \
         {without_shape} with no usable shape, {shapes_read} shape(s) read, \
         {deduped} republished byte-for-byte, {merged} already drawn in their colour, \
         {crowded} over track already carrying {MAX_SERVICES_PER_TRACK} services, \
         {} line(s), {} intercity, {bundled} in a shared corridor, \
         {fell_back} on a per-mode fallback colour)",
        out_path.display(),
        total,
        lines.len(),
        lines.iter().filter(|l| l.intercity).count(),
    );
    Ok(())
}

/// One output line: a GeoJSON `Feature` and its newline.
fn feature_json(line: &Line) -> Result<Vec<u8>, String> {
    let mut buf: Vec<u8> = Vec::with_capacity(64 + line.points.len() * 26);
    buf.extend_from_slice(b"{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
    for (i, (lat, lon)) in line.points.iter().enumerate() {
        if i > 0 {
            buf.push(b',');
        }
        write!(buf, "[{:.7},{:.7}]", *lon as f64 * 1e-7, *lat as f64 * 1e-7).map_err(io_err)?;
    }
    write!(
        buf,
        "]}},\"properties\":{{\"color\":\"{:06X}\",\"mode\":\"{}\",\"ordinal\":{},\
         \"lanes\":{},\"taper\":{},{}\"route\":\"",
        intercity::drawn_color(line.color, line.intercity, fallback_color(line.mode)),
        line.mode,
        line.ordinal,
        line.lanes,
        line.taper,
        // Only when set, so every other line is written exactly as before.
        if line.intercity { "\"intercity\":1," } else { "" },
    )
    .map_err(io_err)?;
    json_escape(line.route.as_bytes(), &mut buf);
    buf.extend_from_slice(b"\"}}\n");
    Ok(buf)
}

/// Parse one chunk of feeds on `threads` workers, back in spec order, so the fold sees exactly
/// the sequence a sequential read would give it.
fn parse_chunk(chunk: &[FeedSpec], threads: usize) -> Vec<(usize, Result<FeedLines, String>)> {
    let next = AtomicUsize::new(0);
    let parsed: Mutex<Vec<(usize, Result<FeedLines, String>)>> =
        Mutex::new(Vec::with_capacity(chunk.len()));
    std::thread::scope(|scope| {
        for _ in 0..threads.min(chunk.len()) {
            scope.spawn(|| loop {
                let i = next.fetch_add(1, Ordering::Relaxed);
                if i >= chunk.len() {
                    break;
                }
                let read = read_feed(&chunk[i]);
                parsed.lock().expect("feed reader pool").push((i, read));
            });
        }
    });
    let mut parsed = parsed.into_inner().expect("feed reader pool");
    parsed.sort_unstable_by_key(|(i, _)| *i);
    parsed
}

/// One stderr redraw of the feed-parse meter in the standard shape:
/// `\r{label:<28} [{pct:>3}%] {done}/{total}  {elapsed} elapsed, ETA {eta}`.
fn print_feed_meter(done: usize, total: usize, start: &Instant) {
    let elapsed = start.elapsed().as_secs_f64();
    let pct = if total == 0 { 100 } else { (done * 100 / total.max(1)).min(100) };
    let eta = if done == 0 {
        "--".to_string()
    } else {
        fmt_dur(elapsed / done as f64 * (total - done) as f64)
    };
    eprint!(
        "\r{:<28} [{:>3}%] {done}/{total}  {} elapsed, ETA {eta}",
        "Parse feeds",
        pct,
        fmt_dur(elapsed),
    );
    let _ = std::io::stderr().flush();
}

/// Short stable durations for the meter: `12s`, `2m05s`, `1h02m`.
fn fmt_dur(secs: f64) -> String {
    let s = secs.max(0.0).round() as u64;
    if s < 60 {
        format!("{s}s")
    } else if s < 3600 {
        format!("{}m{:02}s", s / 60, s % 60)
    } else {
        format!("{}h{:02}m", s / 3600, (s % 3600) / 60)
    }
}

/// Lines per speculative window of the cover gates. Decides only how much is re-asked at
/// resolution, never what is decided. Measured on the world pack at 192 threads: 16 lines
/// spent 12 s in window barriers, 128 spent 8 s re-asking, 64 balanced the two (~17 s for the
/// whole fold, against 293 s sequential).
const FOLD_WINDOW: usize = 64;

/// The fewest drawn samples out of `len` for which `drawn as f64 / len as f64 >=
/// MOSTLY_DRAWN` — the gate's own arithmetic, solved for the count, so comparing a count
/// against it decides exactly what the division would.
fn drawn_needed(len: usize) -> usize {
    let at = |d: usize| d as f64 / len as f64 >= MOSTLY_DRAWN;
    let mut d = ((MOSTLY_DRAWN * len as f64).floor() as usize).min(len);
    while d > 0 && at(d - 1) {
        d -= 1;
    }
    while !at(d) {
        d += 1;
    }
    d
}

/// Samples below which a per-line pass stays on the calling thread: under it a fork-join
/// costs more than it saves. Changes no answer.
const PAR_SAMPLES: usize = 4096;

/// One line judged against the covers as they stood at its window's start.
struct Speculated {
    walked: Vec<bundle::Walked>,
    /// `bundle::sample_cell` of every walked sample.
    cells: Vec<bundle::Cell>,
    /// Samples already drawn in the gating cover.
    drawn: usize,
    /// The rest, by index into `walked`: the only answers resolution may have to revise.
    uncovered: Vec<u32>,
    redundant: bool,
    /// The crowd gate's answer, for a line that can reach it (not redundant, not intercity).
    crowd: Option<bool>,
    /// `Covered::mark` of the gating cover and of the mode's cover when this ran (0 for a
    /// cover not created yet, which answered as an empty one).
    gate_mark: u32,
    mode_mark: u32,
}

fn speculate(
    line: &Line,
    by_color: &BTreeMap<(&'static str, u32), bundle::Covered>,
    by_mode: &BTreeMap<&'static str, bundle::Covered>,
) -> Speculated {
    let walked = bundle::walk(&line.points);
    let cells: Vec<bundle::Cell> = walked.iter().map(bundle::sample_cell).collect();
    // Exactly the cover the sequential gate reads: the mode's for a line on its mode's
    // fallback colour, the colour's otherwise. A cover not created yet is an empty one.
    let gate = if line.fallback { by_mode.get(line.mode) } else { by_color.get(&(line.mode, line.color)) };
    let mode = by_mode.get(line.mode);
    let uncovered: Vec<u32> = match gate {
        None => (0..walked.len() as u32).collect(),
        Some(g) => {
            let scan = |lo: usize, hi: usize| -> Vec<u32> {
                (lo..hi).filter(|&k| !g.covers_sample_in(cells[k], &walked[k])).map(|k| k as u32).collect()
            };
            if walked.len() < PAR_SAMPLES {
                scan(0, walked.len())
            } else {
                let starts: Vec<usize> = (0..walked.len()).step_by(PAR_SAMPLES).collect();
                let parts: Vec<Vec<u32>> =
                    starts.par_iter().map(|&lo| scan(lo, (lo + PAR_SAMPLES).min(walked.len()))).collect();
                parts.concat()
            }
        }
    };
    let drawn = walked.len() - uncovered.len();
    // `covered_fraction_walked`'s arithmetic, including 0 for a line too short to walk.
    let redundant = !walked.is_empty() && drawn as f64 / walked.len() as f64 >= MOSTLY_DRAWN;
    let crowd = (!redundant && !line.intercity).then(|| {
        mode.is_some_and(|m| {
            let at = |k: usize| m.crowd_reaches_in(cells[k], &walked[k], MAX_SERVICES_PER_TRACK, &mut Vec::new());
            if walked.len() < PAR_SAMPLES {
                (0..walked.len()).any(at)
            } else {
                (0..walked.len()).into_par_iter().with_min_len(PAR_SAMPLES / 4).any(at)
            }
        })
    });
    Speculated {
        walked,
        cells,
        drawn,
        uncovered,
        redundant,
        crowd,
        gate_mark: gate.map_or(0, bundle::Covered::mark),
        mode_mark: mode.map_or(0, bundle::Covered::mark),
    }
}

/// The redundancy gate re-decided against the live cover `g`: the speculated `drawn`, plus
/// every speculated-uncovered sample that is now covered — which only a sample beside a cell
/// `g` has gained since `mark` can be. Whether the count reaches the threshold is all that
/// matters, so a small line stops as soon as that is settled either way.
fn recount_reaches(
    g: &bundle::Covered,
    mark: u32,
    walked: &[bundle::Walked],
    cells: &[bundle::Cell],
    drawn: usize,
    uncovered: &[u32],
) -> bool {
    let need = drawn_needed(walked.len());
    let now_covered = |k: u32| {
        let k = k as usize;
        g.changed_near(cells[k], mark) && g.covers_sample_in(cells[k], &walked[k])
    };
    if uncovered.len() >= PAR_SAMPLES {
        return drawn + uncovered.par_iter().with_min_len(PAR_SAMPLES / 4).filter(|&&k| now_covered(k)).count() >= need;
    }
    let (mut drawn, mut left) = (drawn, uncovered.len());
    for &k in uncovered {
        if drawn >= need || drawn + left < need {
            break;
        }
        left -= 1;
        if now_covered(k) {
            drawn += 1;
        }
    }
    drawn >= need
}

/// The crowd gate re-decided against the live mode cover `m`, where the speculated answer
/// was `false`: only a sample beside a cell `m` has gained since `mark` can have changed.
fn recrowd_reaches(m: &bundle::Covered, mark: u32, walked: &[bundle::Walked], cells: &[bundle::Cell]) -> bool {
    let at = |k: usize| {
        m.changed_near(cells[k], mark)
            && m.crowd_reaches_in(cells[k], &walked[k], MAX_SERVICES_PER_TRACK, &mut Vec::new())
    };
    if walked.len() < PAR_SAMPLES {
        (0..walked.len()).any(at)
    } else {
        (0..walked.len()).into_par_iter().with_min_len(PAR_SAMPLES / 4).any(at)
    }
}
