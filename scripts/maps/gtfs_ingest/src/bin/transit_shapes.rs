//! `transit_shapes` — host build tool emitting the basemap's `transit` layer as
//! newline-delimited GeoJSON (geojsonseq), one `LineString` per distinct rail
//! polyline, carrying the agency's own `route_color`.
//!
//! Usage:
//!   transit_shapes <out.geojsonseq> <feed>...
//!   transit_shapes <out.geojsonseq> --manifest FILE
//!
//! `<feed>` is `feed_name=gtfs_dir[=motis_prefix]` or a bare `gtfs_dir`, exactly
//! as `gtfs_ingest` takes them, so the same `feeds.manifest`
//! `build_world_transit.ps1` writes drives every tool in this crate.
//!
//! # Why GTFS rather than OSM route relations
//!
//! The layer used to be derived from `type=route` relations in the `.osm.pbf`,
//! which got both halves wrong. A relation's members include its **platforms**,
//! and the member role is not available where the colour is assigned, so every
//! platform way came out as a wide coloured band beside the track. And the colour
//! itself was the relation's `colour=` tag when it had one and a per-mode guess
//! when it did not. `routes.txt` carries the agency's official `route_color` and
//! `shapes.txt` carries the vehicle's real path with no platforms in it at all.
//!
//! # Reading the `.transit` pack instead (`--pack`)
//!
//! The pack's ride geometry is fitted to a stop pattern and trimmed to
//! `[first stop, last stop]`: right for a journey leg, wrong for a basemap line,
//! which wants the whole alignment. So a v7 pack also carries RAIL_LINES, the
//! exact shapes this tool reads from each feed, and `--pack` derives the same
//! layer from it without the raw feeds.
//!
//! Like the rest of the crate: no serde, the JSON lines are hand-written.

#[path = "transit_shapes_extra2.rs"]
mod extra2;

#[path = "transit_shapes_intercity.rs"]
mod intercity;

#[path = "transit_shapes_pack.rs"]
mod pack;

#[path = "transit_shapes_extra.rs"]
#[cfg(test)]
mod extra;

use gtfs_ingest::bundle;
use gtfs_ingest::gtfs::{self, Csv};
use gtfs_ingest::rail::{fallback_color, mode_of, rail_routes, shape_ids_by_route};
use gtfs_ingest::manifest::{parse_feed_spec, read_manifest, FeedSpec};
use gtfs_ingest::timing::PhaseTimer;

/// How many distinct services may draw over one stretch of track before the rest are dropped.
///
/// Four, because that is what the renderer can show: `Layer::lane_offset_px` clamps the drawn
/// lanes to the style's `lanes` ramp, which tops out at 4 at z13. A fifth service over the same
/// track gets squashed onto a lane it shares with another and adds nothing but over-draw.
///
/// The cap exists because the dedup gates above it are colour-scoped on purpose — two services
/// sharing a track are two real services and both should draw. That reasoning holds for one city
/// and fails for a planet, where one alignment is republished by a city feed, the regional feed
/// containing it and a national feed on top, each under its own `route_color` and often its own
/// `route_type`. Fifteen lines over one railway is not fifteen services; it is one service seen
/// fifteen times.
const MAX_SERVICES_PER_TRACK: usize = 4;

/// How much of a line must already be drawn in its colour for it to count as a duplicate.
///
/// Not 100%, which is what "is this line already drawn" used to mean. A route published twice at
/// slightly different lengths — a short-turn, or one feed running two stops further than another —
/// shares nearly all of its alignment with what is drawn, adds a little new track at one end, and
/// under an all-or-nothing rule is kept whole. That duplicates the shared 95%: the A line came out
/// as two strands a metre apart, each fanned into its own corridor lane and tapered, so one line
/// read as two diverging and reconverging.
///
/// High enough that a genuine branch survives. A route sharing a trunk and then diverging is well
/// under this — the branch is the point of it — while a re-publication with a couple of extra
/// stops is well over.
const MOSTLY_DRAWN: f64 = 0.95;
use std::collections::{BTreeMap, HashSet};
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Mutex;

fn main() -> ExitCode {
    let mut args: Vec<String> = std::env::args().collect();
    // `--threads N` anywhere, taken out before the positional parse. It and `MAPS_THREADS`
    // size every pool this tool runs; neither changes a byte of the output.
    if let Some(at) = args.iter().position(|a| a == "--threads") {
        let Some(value) = args.get(at + 1).cloned() else {
            eprintln!("ERROR: transit_shapes: --threads requires a count");
            return ExitCode::from(2);
        };
        match gtfs_ingest::par::parse_threads(&value) {
            Ok(n) => gtfs_ingest::par::set_threads(n),
            Err(e) => {
                eprintln!("ERROR: transit_shapes: {e}");
                return ExitCode::from(2);
            }
        }
        args.drain(at..at + 2);
    }
    // One pool for every rayon call in the run, sized by the thread budget rather than by
    // the whole box, so `--threads` / `MAPS_THREADS` govern the parallel sorts and maps too.
    let pool = match rayon::ThreadPoolBuilder::new()
        .num_threads(gtfs_ingest::par::threads())
        .thread_name(|i| format!("transit_shapes-{i}"))
        .build()
    {
        Ok(p) => p,
        Err(e) => {
            eprintln!("ERROR: transit_shapes: cannot start the thread pool: {e}");
            return ExitCode::FAILURE;
        }
    };
    pool.install(|| run_main(args))
}

fn run_main(args: Vec<String>) -> ExitCode {
    if args.len() >= 2 && (args[1] == "-h" || args[1] == "--help") {
        usage();
        return ExitCode::SUCCESS;
    }
    if args.len() < 3 {
        usage();
        return ExitCode::from(2);
    }
    let out = PathBuf::from(&args[1]);

    let mut timer = PhaseTimer::start("transit");
    timer.switch("manifest-resolve");

    // A built pack stands in for the raw feeds it was made from.
    if args[2] == "--pack" {
        let Some(file) = args.get(3) else {
            eprintln!("ERROR: transit_shapes: --pack requires a world.transit path");
            return ExitCode::from(2);
        };
        return match extra2::run_with_timer(&out, extra2::Inputs::Pack(Path::new(file)), Some(timer)) {
            Ok(()) => ExitCode::SUCCESS,
            Err(e) => {
                eprintln!("ERROR: transit_shapes: {e}");
                ExitCode::FAILURE
            }
        };
    }

    let specs: Vec<FeedSpec> = if args[2] == "--manifest" {
        let Some(file) = args.get(3) else {
            eprintln!("ERROR: transit_shapes: --manifest requires a file path");
            return ExitCode::from(2);
        };
        match read_manifest(Path::new(file)) {
            Ok(v) => v,
            Err(e) => {
                eprintln!("ERROR: transit_shapes: {e}");
                return ExitCode::FAILURE;
            }
        }
    } else {
        args[2..].iter().map(|s| parse_feed_spec(s)).collect()
    };

    if specs.is_empty() {
        eprintln!("ERROR: transit_shapes: no feeds given");
        usage();
        return ExitCode::from(2);
    }

    match extra2::run_with_timer(&out, extra2::Inputs::Feeds(&specs), Some(timer)) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("ERROR: transit_shapes: {e}");
            ExitCode::FAILURE
        }
    }
}

fn usage() {
    eprintln!("usage: transit_shapes <out.geojsonseq> <feed>...");
    eprintln!("       transit_shapes <out.geojsonseq> --manifest FILE");
    eprintln!("       transit_shapes <out.geojsonseq> --pack world.transit");
    eprintln!("  <feed> = feed_name=gtfs_dir[=motis_prefix]  |  gtfs_dir");
}

/// One emitted polyline.
#[derive(Clone)]
struct Line {
    /// The shape's points in `shape_pt_sequence` order, as `(lat_e7, lon_e7)`.
    points: Vec<(i32, i32)>,
    /// `0xRRGGBB`, never zero — zero means "no colour" on the wire and
    /// `Sink::push_transit` refuses it.
    color: u32,
    mode: &'static str,
    /// True when `color` is this mode's fallback rather than the agency's.
    fallback: bool,
    /// `route_short_name` or, failing that, `route_long_name`. The order within a
    /// bundle is the colour and then this, and the tiler reads neither.
    route: String,
    /// Feed name and `route_id`, which is what makes a route one route across the
    /// branches and short-turns it publishes. Never emitted.
    route_key: String,
    /// This colour's index among its corridor's distinct colours. See [`bundle`].
    ordinal: u8,
    /// How many distinct colours that corridor carries. One outside a corridor.
    lanes: u8,
    /// How far into its lane this piece sits, over 255. See [`bundle`].
    taper: u8,
    /// Long-distance rail, carried from country zoom. See [`intercity`].
    intercity: bool,
}

/// What one feed contributes, before the cross-feed passes.
#[derive(Default)]
struct FeedLines {
    lines: Vec<Line>,
    routes_kept: usize,
    routes_without_shape: usize,
    /// Distinct usable `shape_id`s seen.
    shapes_read: usize,
}

/// A content hash of one polyline, for dedup.
///
/// Rounded to e6 — about 0.1 m — so that two feeds publishing the same alignment
/// with the last digit differing still collapse to one line. FNV-1a over the
/// rounded pairs rather than the coordinates themselves: a world feed set is
/// millions of polylines and holding every one of them as a key would cost more
/// than the layer does. Sixty-four bits over that many lines is a collision
/// chance around one in ten million, against heavy visible over-draw if the
/// dedup is skipped.
///
/// Exact after rounding, and so direction-sensitive. The near-match that
/// collapses a route's two directions is [`bundle::same_line`]; this is the
/// cheap cross-feed pass that runs over every line in the set.
fn polyline_hash(points: &[(i32, i32)]) -> u64 {
    let mut h = 0xcbf2_9ce4_8422_2325u64;
    let mut eat = |v: i32| {
        for b in v.to_le_bytes() {
            h ^= b as u64;
            h = h.wrapping_mul(0x100_0000_01b3);
        }
    };
    for (lat, lon) in points {
        eat(lat.div_euclid(10));
        eat(lon.div_euclid(10));
    }
    h
}

/// Read one feed and reduce it to candidate lines.
fn read_feed(spec: &FeedSpec) -> Result<FeedLines, String> {
    let (name, dir, _) = spec;
    // Shapes are the only thing this layer draws, so a feed without a usable shapes.txt
    // contributes nothing — decided first, before the (often multi-GB) routes.txt and
    // trips.txt are touched. A national feed with no shapes.txt still carries a huge
    // routes/trips/stop_times, and reading those only to return empty is what stalled the
    // chunk barrier on mega-feeds like gb_great_britain while the other cores idled.
    let Some(shapes) = gtfs::read_shapes(dir) else {
        // Optional in GTFS, and a feed without it can still route — it just draws
        // nothing. Not an error, or one bus-only agency would fail a whole region.
        eprintln!(
            "WARN: transit_shapes: feed '{name}' has no usable shapes.txt, so it draws no lines"
        );
        return Ok(FeedLines::default());
    };
    let require = |file: &str| -> Result<Csv, String> {
        gtfs::read_table(dir, file).ok_or_else(|| {
            format!("feed '{name}' ({}) missing required GTFS file: {file}", dir.display())
        })
    };
    let routes_csv = require("routes.txt")?;
    let trips_csv = require("trips.txt")?;

    let rail = rail_routes(&routes_csv);
    let by_route = shape_ids_by_route(&trips_csv, &rail);

    let mut out = FeedLines { routes_kept: rail.len(), ..FeedLines::default() };
    for (route_id, shape_ids) in &by_route {
        let (mode, color, fallback, route, route_type) = &rail[route_id.as_str()];
        let before = out.lines.len();
        // Every distinct shape, whole. What a route publishes is one alignment per
        // direction plus a short-turn or a branch variant or two, all of them overlapping,
        // and sorting that out is not a per-route job: the same track is published again by
        // every other feed that covers the city. `run` subtracts them all at once.
        for shape_id in shape_ids {
            let Some(shape) = shapes.get(shape_id.as_str()) else { continue };
            // Two points at minimum, or it is not a line.
            if shape.lat_e7.len() < 2 {
                continue;
            }
            out.shapes_read += 1;
            let points: Vec<(i32, i32)> =
                shape.lat_e7.iter().zip(&shape.lon_e7).map(|(&lat, &lon)| (lat, lon)).collect();
            out.lines.push(Line {
                intercity: intercity::is_intercity(intercity::is_long_distance_type(*route_type), &points),
                points,
                color: *color,
                mode,
                fallback: *fallback,
                route: route.clone(),
                route_key: format!("{name}\u{0}{route_id}"),
                ordinal: 0,
                lanes: 1,
                taper: 255,
            });
        }
        if out.lines.len() == before {
            out.routes_without_shape += 1;
        }
    }
    // A rail route no trip references at all never reaches the loop above.
    out.routes_without_shape += rail.len() - by_route.len();
    Ok(out)
}

/// Cut every line at its corridor boundaries and give each piece its lane, so one line in
/// becomes several out — the stretch before a corridor on its own geometry, the stretch
/// inside it on the corridor's reference geometry at its lane, and a taper between. Returns
/// how many pieces came out offset into a corridor.
fn expand_corridor_spans(
    lines: &mut Vec<Line>,
    timer: Option<&mut PhaseTimer>,
) -> usize {
    // A route is a route across every branch and short-turn it publishes, so all of
    // them take one lane. `BTreeMap` and first-seen ids, because a `HashMap`'s order
    // would decide the lanes and the output has to be byte-identical between runs.
    let mut ids: BTreeMap<&str, u32> = BTreeMap::new();
    let mut routes: Vec<u32> = Vec::with_capacity(lines.len());
    for line in lines.iter() {
        let next = ids.len() as u32;
        routes.push(*ids.entry(line.route_key.as_str()).or_insert(next));
    }
    let spans = {
        let candidates: Vec<bundle::Candidate> = lines
            .iter()
            .zip(&routes)
            .map(|(line, route)| bundle::Candidate {
                points: &line.points,
                route: *route,
                color: line.color,
                name: &line.route,
            })
            .collect();
        bundle::assign_with_timer(&candidates, timer)
    };
    let mut bundled = 0usize;
    let mut out: Vec<Line> = Vec::with_capacity(lines.len());
    for (line, spans) in lines.drain(..).zip(spans) {
        for span in spans {
            // A cut can leave nothing between its two ends, and two points at minimum is
            // what makes a line.
            if span.points.len() < 2 {
                continue;
            }
            if span.lanes > 1 {
                bundled += 1;
            }
            out.push(Line {
                points: span.points,
                ordinal: span.ordinal,
                lanes: span.lanes,
                taper: span.taper,
                ..line.clone()
            });
        }
    }
    *lines = out;
    bundled
}

fn io_err(e: impl std::fmt::Display) -> String {
    format!("write failed: {e}")
}

/// Escape a name for a JSON string. UTF-8 bytes pass through verbatim; only the
/// JSON-mandatory escapes and C0 controls are rewritten. Mirrors
/// `transit_stops`' escaper so both layers quote identically.
fn json_escape(s: &[u8], out: &mut Vec<u8>) {
    for &b in s {
        match b {
            b'"' => out.extend_from_slice(b"\\\""),
            b'\\' => out.extend_from_slice(b"\\\\"),
            b'\n' => out.extend_from_slice(b"\\n"),
            b'\r' => out.extend_from_slice(b"\\r"),
            b'\t' => out.extend_from_slice(b"\\t"),
            0x00..=0x1f => {
                out.extend_from_slice(format!("\\u{:04x}", b).as_bytes());
            }
            _ => out.push(b),
        }
    }
}
