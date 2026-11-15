//! The offline router (graph + highway overlay + transit) against a REAL planet archive
//! (`planet4.mamaps`, or the byte-identical local build `scripts/maps/inputs/world.mamaps`).
//!
//! By default these tests run against small SYNTHETIC archives built
//! in-test (see `graph_fixture.rs` and `pack.rs`): the same loader,
//! routing and planning paths, without the 80 GB input. Set the env
//! var to run them against the planet instead:
//!
//! ```text
//! MAMAPS_REAL_ARCHIVE=/path/to/world.mamaps \
//!   cargo test -p offlinerouter --test real_archive -- --nocapture --test-threads=1
//! ```
//!
//! Same calls the JNI layer makes (`Graph::load_archive`, `prepare_routing` +
//! `perform_search_loop` + `reconstruct_path`, `TransitIndex::load_archive`, `plan`,
//! `stop_departures`), minus the JNI marshalling and live traffic.

use offlinerouter::geometry::TrafficSpeeds;
use offlinerouter::graph::{Graph, BICYCLE, DRIVING, WALK};
use offlinerouter::routing::{perform_search_loop, prepare_routing, reconstruct_path, StepData};
use offlinerouter::state::{RadixHeap, RoutingScratchpad};
use offlinerouter::transit::{
    plan, rail_lines, stop_departures, LegKind, QueryDay, Schedule, TransitIndex,
};

#[path = "real_archive/graph_fixture.rs"]
mod graph_fixture;
#[path = "real_archive/pack.rs"]
mod pack;

const ENV: &str = "MAMAPS_REAL_ARCHIVE";

/// (label, from (lat, lon), to (lat, lon), travel mode).
type RouteCase = (&'static str, (f64, f64), (f64, f64), i32);

fn archive() -> Option<String> {
    match std::env::var(ENV) {
        Ok(p) if !p.trim().is_empty() && std::path::Path::new(&p).is_file() => Some(p),
        _ => None,
    }
}

fn crow_km(a: (f64, f64), b: (f64, f64)) -> f64 {
    let (la1, lo1, la2, lo2) = (a.0.to_radians(), a.1.to_radians(), b.0.to_radians(), b.1.to_radians());
    let h = ((la2 - la1) / 2.0).sin().powi(2) + la1.cos() * la2.cos() * ((lo2 - lo1) / 2.0).sin().powi(2);
    2.0 * 6371.0 * h.sqrt().asin()
}

/// One route through the app's routing core, or `None` when it does not snap/route.
fn route(g: &Graph, from: (f64, f64), to: (f64, f64), mode: i32) -> Option<Vec<StepData>> {
    let speeds = TrafficSpeeds::default();
    let mut scratch = RoutingScratchpad::new();
    let mut heap = RadixHeap::new();
    let mut ensure = |_lat_e7: i32, _lon_e7: i32| {};
    let mut ctx =
        prepare_routing(g, &speeds, &mut ensure, from.0, from.1, to.0, to.1, mode, &mut scratch, &mut heap)?;
    perform_search_loop(g, &speeds, &mut ensure, mode, &mut ctx, &mut scratch, &mut heap);
    if ctx.target_node == 0xFFFF_FFFF && ctx.direct.is_none() {
        return None;
    }
    Some(reconstruct_path(g, &speeds, mode, &ctx, &mut scratch))
}

#[test]
fn graph_sections_load_and_routes_resolve() {
    let (path, planet) = match archive() {
        Some(p) => (p, true),
        None => (
            graph_fixture::write_temp("graph", &graph_fixture::graph_archive())
                .to_string_lossy()
                .into_owned(),
            false,
        ),
    };
    let t0 = std::time::Instant::now();
    let g = Graph::load_archive(&path).expect("Graph::load_archive refused the archive");
    println!(
        "graph: {} nodes, {} edges, overlay {}, elevation {}, loaded in {:?}",
        g.node_count,
        g.edge_count,
        g.overlay().is_some(),
        g.has_elevation(),
        t0.elapsed()
    );
    if planet {
        assert!(g.node_count > 100_000_000, "not a planet graph");
    } else {
        assert_eq!(g.node_count, 9, "the synthetic grid's node count");
        assert_eq!(g.edge_count, 24, "the synthetic grid's edge count");
        assert!(g.edge_lane_masks(0).is_some(), "lanes section carries edge 0");
    }
    assert!(g.overlay().is_some(), "highway overlay (kind 14) not attached");

    let planet_cases: &[RouteCase] = &[
        ("SF drive Ferry Bldg -> Golden Gate Park", (37.7955, -122.3937), (37.7694, -122.4862), DRIVING),
        ("SF walk Union Sq -> Ferry Bldg", (37.7880, -122.4075), (37.7955, -122.3937), WALK),
        ("SF bike Mission -> Marina", (37.7599, -122.4148), (37.8037, -122.4368), BICYCLE),
        ("NYC drive Midtown -> JFK", (40.7580, -73.9855), (40.6413, -73.7781), DRIVING),
        ("London drive Westminster -> Camden", (51.4995, -0.1248), (51.5390, -0.1426), DRIVING),
        ("Tokyo walk Shibuya -> Harajuku", (35.6595, 139.7005), (35.6702, 139.7027), WALK),
        ("Long drive SF -> LA (overlay)", (37.7749, -122.4194), (34.0522, -118.2437), DRIVING),
    ];
    let synthetic_cases: &[RouteCase] = &[
        ("grid drive NE -> SW", graph_fixture::node_lat_lon(8), graph_fixture::node_lat_lon(0), DRIVING),
        ("grid walk N edge", graph_fixture::node_lat_lon(6), graph_fixture::node_lat_lon(8), WALK),
        ("grid bike SW -> NE", graph_fixture::node_lat_lon(0), graph_fixture::node_lat_lon(8), BICYCLE),
    ];
    let cases = if planet { planet_cases } else { synthetic_cases };
    for &(name, from, to, mode) in cases {
        let t = std::time::Instant::now();
        let steps = route(&g, from, to, mode).unwrap_or_else(|| panic!("{name}: no route"));
        let km = steps.iter().map(|s| s.dist_mm).sum::<u64>() as f64 / 1e6;
        let mins = steps.iter().map(|s| s.time_10ms).sum::<u64>() as f64 / 6000.0;
        let named = steps.iter().filter(|s| g.road_name(s.name_off).is_some()).count();
        let lanes = steps.iter().filter(|s| !s.lanes.is_empty()).count();
        let crow = crow_km(from, to);
        println!(
            "{name}: {km:.1} km (crow {crow:.1}), {mins:.0} min, {} steps ({named} named, {lanes} with lanes) in {:?}",
            steps.len(),
            t.elapsed()
        );
        assert!(!steps.is_empty(), "{name}: empty route");
        assert!(km >= crow * 0.95 && km <= crow * 3.0 + 2.0, "{name}: implausible length {km} km for {crow} km crow");
        assert!(named > 0, "{name}: no step resolved a road name (road_names section)");
        for s in &steps {
            assert_eq!(s.coords.len() % 2, 0);
            assert!(s.elevations.is_empty() || s.elevations.len() == s.coords.len() / 2);
        }
    }
    if planet {
        // Lane guidance (lanes section) shows up on a long drive through big junctions.
        let la = route(&g, (37.7749, -122.4194), (34.0522, -118.2437), DRIVING).unwrap();
        assert!(la.iter().any(|s| !s.lanes.is_empty()), "no lane guidance on SF -> LA");
    }
    if !planet {
        std::fs::remove_file(&path).ok();
    }
}

#[test]
fn transit_section_loads_plans_and_lists_departures() {
    let (path, planet) = match archive() {
        Some(p) => (p, true),
        None => (
            graph_fixture::write_temp("transit", &graph_fixture::transit_archive(&pack::synthetic_pack()))
                .to_string_lossy()
                .into_owned(),
            false,
        ),
    };
    assert!(TransitIndex::has_archive_transit(&path), "no transit section (kind 13)");
    let t0 = std::time::Instant::now();
    let idx = TransitIndex::load_archive(&path).expect("TransitIndex::load_archive refused the archive");
    println!("transit: feed {:?}, loaded in {:?}", idx.feed_name(), t0.elapsed());

    if planet {
        assert!(idx.covers(37.7749, -122.4194), "transit pack does not cover SF");

        // A weekday mid-morning (Wednesday 2026-11-11), the day this test was written.
        let sched = Schedule { day: QueryDay::for_date(2, 20261111), overlay: None };
        let nine = 9 * 3600;

        let board = stop_departures(&idx, 37.7764, -122.3943, nine, sched, 10); // SF Caltrain 4th & King
        println!("departures at 4th & King: {:?}",
            board.iter().map(|d| format!("{} -> {} @{}", d.route_name, d.headsign, d.dep_secs)).collect::<Vec<_>>());
        assert!(!board.is_empty(), "no offline departures at SF 4th & King");
        assert!(board.windows(2).all(|w| w[0].dep_secs <= w[1].dep_secs));

        let tz = idx.timezone_at(37.7749, -122.4194);
        let motis = idx.nearest_stop_motis_id(37.7764, -122.3943);
        println!("timezone {tz}, MOTIS stop id {motis:?}");
        assert_eq!(tz, "America/Los_Angeles");

        let lines = rail_lines(&idx, 37.70, -122.50, 37.81, -122.38);
        println!("rail lines in SF bbox: {}", lines.len());
        assert!(!lines.is_empty());

        for (name, from, to) in [
            ("SF Mission -> Downtown", (37.7599, -122.4148), (37.7880, -122.4075)),
            ("NYC Brooklyn -> Midtown", (40.6782, -73.9442), (40.7580, -73.9855)),
            ("London Camden -> Westminster", (51.5390, -0.1426), (51.4995, -0.1248)),
            ("Tokyo Shinjuku -> Tokyo Stn", (35.6896, 139.7006), (35.6812, 139.7671)),
        ] {
            let legs = plan(&idx, from.0, from.1, to.0, to.1, nine, sched);
            match &legs {
                Some(legs) => println!(
                    "plan {name}: {}",
                    legs.iter()
                        .map(|l| {
                            let kind = match l.kind {
                                LegKind::Walk => "walk",
                                LegKind::Wait => "wait",
                                LegKind::Ride => "ride",
                            };
                            format!("{kind}:{}({}->{})", l.name, l.from_stop, l.to_stop)
                        })
                        .collect::<Vec<_>>()
                        .join(" | ")
                ),
                None => println!("plan {name}: no journey"),
            }
            let legs = legs.unwrap_or_else(|| panic!("{name}: no transit journey"));
            assert!(legs.iter().any(|l| l.kind == LegKind::Ride), "{name}: journey has no ride");
        }
    } else {
        // The synthetic pack: Alpha (37.700) -> Beta -> Gamma on route N,
        // 08:00 and 09:00 trips, Wednesday 2024-01-03.
        assert_eq!(idx.feed_name(), "testpack");
        assert!(idx.covers(37.710, -122.400), "transit pack does not cover Beta");
        let sched = Schedule { day: QueryDay::for_date(2, 20240103), overlay: None };

        let board = stop_departures(&idx, 37.700, -122.400, 28_000, sched, 10); // Alpha
        println!("departures at Alpha: {:?}",
            board.iter().map(|d| format!("{} -> {} @{}", d.route_name, d.headsign, d.dep_secs)).collect::<Vec<_>>());
        assert!(!board.is_empty(), "no offline departures at Alpha");
        assert!(board.windows(2).all(|w| w[0].dep_secs <= w[1].dep_secs));
        assert_eq!(board[0].dep_secs, 28_800, "first departure is the 08:00 trip");

        let tz = idx.timezone_at(37.700, -122.400);
        let motis = idx.nearest_stop_motis_id(37.700, -122.400);
        println!("timezone {tz}, MOTIS stop id {motis:?}");
        assert_eq!(tz, "America/Los_Angeles");
        assert!(motis.as_deref().is_some_and(|m| m.contains("901201")), "Alpha's MOTIS id: {motis:?}");

        let lines = rail_lines(&idx, 37.69, -122.41, 37.73, -122.39);
        println!("rail lines in synthetic bbox: {}", lines.len());
        assert!(!lines.is_empty());

        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched)
            .expect("a journey exists");
        println!(
            "plan Alpha -> Gamma: {}",
            legs.iter()
                .map(|l| {
                    let kind = match l.kind {
                        LegKind::Walk => "walk",
                        LegKind::Wait => "wait",
                        LegKind::Ride => "ride",
                    };
                    format!("{kind}:{}({}->{})", l.name, l.from_stop, l.to_stop)
                })
                .collect::<Vec<_>>()
                .join(" | ")
        );
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("journey has no ride");
        assert_eq!(ride.name, "N");
        assert_eq!(ride.from_stop, "Alpha");
        assert_eq!(ride.to_stop, "Gamma");
        assert_eq!((ride.dep_secs, ride.arr_secs), (28_800, 29_400));
        std::fs::remove_file(&path).ok();
    }
}
