//! `mamaps_build` — one `.osm.pbf` in, one `.mamaps` archive out.
//!
//! The generator this project exists to build. It produces all 9 layers (`landtype`, `roads`,
//! `boundaries`, `buildings`, `places`, `poi`, `transit`, `traffic`, `junction`), and the shape
//! does not change when a rule does because every layer is one module under [`schema`].
//!
//! ```text
//! mamaps_build --input california.osm.pbf --out california.mamaps
//!              --coastline LAND.shp --graph GRAPH_DIR
//!              --transit-routes ROUTES.geojsonseq --dem HEIGHTMAPS.mdem
//!              [--region california|world]
//!              [--build-graph-to DIR] [--build-poi-to DIR]
//!
//! All six flags are required. Every build carries all 9 layers at z0-14 as
//! FORMAT_VERSION 8: there is no layer selection, no zoom selection and no
//! store reuse, so a build is a pure function of its six inputs.
//!
//! `--region` filters the built layers (`roads`, `poi`, `buildings`) to the
//! region's bbox. Everything else is always included regardless. `world` (the
//! default) disables the filter.
//!
//! `--graph-ready-file PATH` lets the build start while `road_graph` is still writing
//! `--graph`: every stage that does not read the graph runs first, and the build waits for PATH
//! to exist (polling once a second, logging "waiting for graph") before the first one that does.
//! See [`GraphGate`] for exactly which stages those are. Without the flag nothing changes.
//!
//! `--build-graph-to` / `--build-poi-to` fold the routing graph and POI
//! sidecars into this process instead of running `road_graph` / `poi_extract`
//! as separate binaries first. Same thread pool, one shared blob scan, and the
//! graph coverage is validated against the region before stage A runs — which
//! is what stops a 43-minute tile build dying on a missing `metadata.bin`.
//! ```
//!
//! # Why not through the existing tiler
//!
//! `tile_build` already turns GeoJSON-seq into PMTiles, and routing through it would have been less
//! new code. But it would also mean encoding to MVT and decoding again, which loses exactly the
//! attributes this format was built to carry: a road's `is_bridge`, a boundary's numeric admin
//! level, a ring's stated winding. So the geometry is reused — projection, significance,
//! simplification, tile bisection, clipping, all of `tile_build` — and only the last step, turning a
//! clipped shape into bytes, is this crate's.

use std::path::PathBuf;
use std::process::ExitCode;

mod coalesce;
mod coalesce_extra;
mod corridor;
mod dem;
mod extract;
mod extract_fallback;
mod lanefill;
mod rings;
mod shapefile;
mod schema;
mod store;
mod tiler;
mod tilespill;
mod timing;

/// The allocator, replaced because the default one was three quarters of the build.
///
/// A build tool has no business caring which allocator it gets, and this one did not until the
/// encode pass was measured against the machine it runs on: **1358 s of worker CPU against 180 s of
/// wall on a 32-core, 64-thread box** — a speed-up of 7.5 where the pool can give 64. Nothing in
/// that pass is shared. [`rings::normalise`], `body::serialize_into` and DEFLATE each read one tile
/// and write one tile, there is no lock and no atomic in the path, and the
/// memory traffic works out at a few hundred MB/s against eight channels of DDR5. The only thing
/// all sixty-four threads still contend on is the heap.
///
/// And they hit it hard. `normalise` allocates a `Vec` per polygon group, another for its exterior
/// and one per hole it keeps, on the way to rebuilding a layer's arenas; z14 of a us-west build is
/// 44.4 M features across 943,401 tiles, which is tens of millions of allocate/free pairs on
/// Windows' process heap in about two and a half minutes.
///
/// Swapping it out took the tiling stage from **351 s to 110 s** on the same spill, for a
/// byte-identical archive:
///
/// | phase | default heap | mimalloc |
/// |---|---|---|
/// | map | 125.1 s | 72.9 s |
/// | merge | 33.2 s | 9.6 s |
/// | encode | 180.3 s | 21.6 s |
/// | append | 12.4 s | 3.3 s |
/// | *encode CPU: stage C* | *452.6 s* | *54.4 s* |
/// | *encode CPU: serialise* | *355.9 s* | *5.4 s* |
/// | *encode CPU: deflate* | *550.0 s* | *181.1 s* |
///
/// Serialisation is the one that says what this really was. It is a memcpy into a reused
/// [`tilecodec::mamaps::body::Scratch`] and it cost 356 s of CPU; it now costs 5.4 s. That was never
/// encoding, it was the handful of buffer growths per tile going to `HeapAlloc` under sixty-four
/// threads of contention.
///
/// Two things this is not. It is not a substitute for allocating less — `normalise` should be
/// threading scratch buffers through rather than building a `Vec` per ring, and a cheaper allocator
/// only makes that less urgent. And it is not free: mimalloc holds freed pages back rather than
/// returning them promptly, which took peak RSS from 6.86 GB to 10.14 GB on the same build. That
/// trade is worth revisiting if memory becomes the binding constraint again.
///
/// No `mi_collect` purge is wired after stage A's phase drops for the same reason: `mi_collect`
/// lives behind the `extended` feature (plus its `cty` dependency), which is not in the offline
/// registry cache, and the drops that matter for the planet peak are not heap frees at all --
/// unmapping an anon segment releases pagefile commit immediately, and a file mapping never
/// charged it. A purge would only return heap slack (relations, members, rayon buffers), which
/// is measured in hundreds of MB against the multi-GB anon/mmap peaks above.
///
/// It cannot change a byte of the output — an allocator decides *where*, never *what* — so it needed
/// no argument about ordering, only a measurement. The us-west archive hashes the same either way.
#[global_allocator]
static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;

/// The deepest zoom an archive is built to. Fixed: every build is z0-14.
///
/// Named rather than inlined because layer code is bounded by it: a layer's tiler-side `min_zoom`
/// past this lands its features in no tile at all, so [`schema::junction`] pins itself against
/// this constant and lowering it fails that test instead of silently emptying a layer.
pub const DEFAULT_MAX_ZOOM: u8 = 14;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let mut input: Option<PathBuf> = None;
    let mut out: Option<PathBuf> = None;
    let mut coastline: Option<PathBuf> = None;
    let mut transit_routes: Option<PathBuf> = None;
    let mut graph: Option<PathBuf> = None;
    let mut dem: Option<PathBuf> = None;
    let mut region: Option<String> = None;
    let mut build_graph_to: Option<PathBuf> = None;
    let mut build_poi_to: Option<PathBuf> = None;
    let mut graph_ready_file: Option<PathBuf> = None;
    let mut v8_bodies = false;

    let mut i = 1;
    while i < args.len() {
        let value = |name: &str| -> Result<String, String> {
            args.get(i + 1).cloned().ok_or_else(|| format!("{name} needs a value"))
        };
        let taken = match args[i].as_str() {
            "--input" => value("--input").map(|v| {
                input = Some(PathBuf::from(v));
                2
            }),
            "--out" => value("--out").map(|v| {
                out = Some(PathBuf::from(v));
                2
            }),
            "--coastline" => value("--coastline").map(|v| {
                coastline = Some(PathBuf::from(v));
                2
            }),
            "--transit-routes" => value("--transit-routes").map(|v| {
                transit_routes = Some(PathBuf::from(v));
                2
            }),
            "--graph" => value("--graph").map(|v| {
                graph = Some(PathBuf::from(v));
                2
            }),
            "--dem" => value("--dem").map(|v| {
                dem = Some(PathBuf::from(v));
                2
            }),
            "--region" => value("--region").map(|v| {
                region = Some(v);
                2
            }),
            "--build-graph-to" => value("--build-graph-to").map(|v| {
                build_graph_to = Some(PathBuf::from(v));
                2
            }),
            "--build-poi-to" => value("--build-poi-to").map(|v| {
                build_poi_to = Some(PathBuf::from(v));
                2
            }),
            "--graph-ready-file" => value("--graph-ready-file").map(|v| {
                graph_ready_file = Some(PathBuf::from(v));
                2
            }),
            // Dense v8 bodies instead of the v10 default (was the `MAMAPS_V8` environment
            // variable; a flag, so no environment value can change an output byte).
            "--v8-bodies" => {
                v8_bodies = true;
                Ok(1)
            }
            "-h" | "--help" => {
                usage();
                return ExitCode::SUCCESS;
            }
            other => Err(format!("unexpected argument '{other}'")),
        };
        match taken {
            Ok(step) => i += step,
            Err(e) => {
                eprintln!("mamaps_build: {e}");
                usage();
                return ExitCode::from(2);
            }
        }
    }

    let (Some(input), Some(out), Some(coastline), Some(transit_routes), Some(graph), Some(dem)) =
        (input, out, coastline, transit_routes, graph, dem)
    else {
        usage();
        return ExitCode::from(2);
    };

    // `--region` defaults to world (no filtering). Parsed here so a bad value
    // fails in seconds, not after stage A.
    let region = match region.as_deref().unwrap_or("world") {
        s => match osm_ingest::region::Region::parse(s) {
            Ok(r) => r,
            Err(e) => {
                eprintln!("mamaps_build: {}", e.0);
                usage();
                return ExitCode::from(2);
            }
        },
    };

    let settings = RunSettings {
        coastline,
        transit_routes,
        graph,
        dem,
        region,
        build_graph_to,
        build_poi_to,
        graph_ready_file,
        v8_bodies,
    };
    match run(&input, &out, &settings) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("mamaps_build: {e}");
            ExitCode::FAILURE
        }
    }
}

/// Everything a run needs beyond its input and output paths.
///
/// All four side inputs are required: every build carries all 9 layers.
struct RunSettings {
    /// A prepared land polygon for `landtype`'s mainland — see [`check_required`].
    coastline: PathBuf,
    /// A prepared GTFS export for `transit`'s coloured rail lines.
    transit_routes: PathBuf,
    /// The v6 routing graph directory (`nodes.bin`/`edges.bin`/`intermediate.bin`/`metadata.bin`)
    /// for `traffic`'s per-component lines and `junction`'s lane connectors.
    graph: PathBuf,
    /// The `.mdem` heightmap dataset `dem_ingest` produced.
    /// Every tile carries the DEM grid sampled to its own z/x/y.
    dem: PathBuf,
    /// Which region's bbox filters roads/pois/buildings. `world` = no filter.
    region: osm_ingest::region::Region,
    /// When set, build the routing graph in-process into this dir (shared pool,
    /// shared blob scan) instead of reading `--graph`.
    build_graph_to: Option<PathBuf>,
    /// When set, build the POI sidecars in-process into this dir instead of
    /// expecting them from a prior `poi_extract` run.
    build_poi_to: Option<PathBuf>,
    /// `--graph-ready-file`: start before the routing graph exists and wait for this file to
    /// appear before the first stage that reads `--graph`. See [`GraphGate`].
    graph_ready_file: Option<PathBuf>,
    /// `--v8-bodies`: encode dense v8 tile bodies instead of the v10 default. In the build id.
    v8_bodies: bool,
}

include!("main_part1.rs");
include!("main_part2.rs");