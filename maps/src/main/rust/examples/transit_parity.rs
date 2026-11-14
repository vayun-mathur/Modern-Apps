//! `transit_parity` — planet5 transit check: the same plans and departure boards from a TRIX v7
//! pack and its v8 (ZSEC, thinned shapes, no ROUTE_TRIPS) rewrite.
//!
//! ```text
//! cargo run --release --example transit_parity -- V7_DIR V8_DIR FEED [N]
//! ```
//!
//! Loads `<dir>/<FEED>.transit` from both directories and runs N origin/destination plans around
//! 24 transit-heavy cities at three departure times, plus a departure board at each origin.
//! Times, stops, routes, headsigns and stop counts must match exactly; ride distances and
//! polylines may differ only by the shape thinning (reported). Also times both, since v8 reads
//! go through the block cache.
//!
//! Experiment tooling (examples/): never in the cdylib.

use offlinerouter::transit::{plan, stop_departures, LegKind, QueryDay, Schedule, TransitIndex};
use std::time::Instant;

const CITIES: [(&str, f64, f64); 24] = [
    ("SF", 37.7749, -122.4194), ("LA", 34.0522, -118.2437), ("Seattle", 47.6062, -122.3321),
    ("NYC", 40.7580, -73.9855), ("Chicago", 41.8781, -87.6298), ("Toronto", 43.6532, -79.3832),
    ("Mexico City", 19.4326, -99.1332), ("Sao Paulo", -23.5505, -46.6333), ("London", 51.5074, -0.1278),
    ("Paris", 48.8566, 2.3522), ("Berlin", 52.52, 13.405), ("Madrid", 40.4168, -3.7038),
    ("Rome", 41.9028, 12.4964), ("Amsterdam", 52.3676, 4.9041), ("Zurich", 47.3769, 8.5417),
    ("Vienna", 48.2082, 16.3738), ("Warsaw", 52.2297, 21.0122), ("Stockholm", 59.3293, 18.0686),
    ("Tokyo", 35.6812, 139.7671), ("Seoul", 37.5665, 126.978), ("Sydney", -33.8688, 151.2093),
    ("Melbourne", -37.8136, 144.9631), ("Auckland", -36.8485, 174.7633), ("Singapore", 1.3521, 103.8198),
];

fn main() -> std::process::ExitCode {
    let a: Vec<String> = std::env::args().skip(1).collect();
    if a.len() < 3 {
        eprintln!("usage: transit_parity V7_DIR V8_DIR FEED [N]");
        return std::process::ExitCode::from(2);
    }
    let n: usize = a.get(3).and_then(|v| v.parse().ok()).unwrap_or(480);
    let t = Instant::now();
    let v7 = TransitIndex::load(&a[0], &a[2]).expect("v7 pack loads");
    let v8 = TransitIndex::load(&a[1], &a[2]).expect("v8 pack loads");
    println!("loaded both in {:.2}s", t.elapsed().as_secs_f64());
    // Tuesday 2026-11-17 (weekday 1 = Tue), previous day Monday.
    let day = QueryDay { weekday: 1, date: 20261117, prev_weekday: 0, prev_date: 20261116 };
    let sched = Schedule { day, overlay: None };
    let mut seed = 0x9E37_79B9_7F4A_7C15u64;
    let mut rnd = || {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        (seed >> 11) as f64 / (1u64 << 53) as f64
    };
    let (mut same, mut diff, mut none_both, mut routed) = (0usize, 0usize, 0usize, 0usize);
    let (mut boards_same, mut boards) = (0usize, 0usize);
    let (mut t7, mut t8) = (0.0f64, 0.0f64);
    let mut max_dist_diff = 0.0f64;
    let mut coords7 = 0usize;
    let mut coords8 = 0usize;
    for q in 0..n {
        let (_, la, lo) = CITIES[q % CITIES.len()];
        let off = |r: f64| (r - 0.5) * 0.12;
        let (fla, flo) = (la + off(rnd()), lo + off(rnd()));
        let (tla, tlo) = (la + off(rnd()), lo + off(rnd()));
        let dep = [7 * 3600 + 30 * 60, 12 * 3600, 18 * 3600 + 15 * 60][q % 3];
        let s = Instant::now();
        let p7 = plan(&v7, fla, flo, tla, tlo, dep, sched);
        t7 += s.elapsed().as_secs_f64();
        let s = Instant::now();
        let p8 = plan(&v8, fla, flo, tla, tlo, dep, sched);
        t8 += s.elapsed().as_secs_f64();
        match (&p7, &p8) {
            (None, None) => none_both += 1,
            (Some(x), Some(y)) => {
                routed += 1;
                let key = |l: &Vec<offlinerouter::transit::TransitLeg>| {
                    l.iter()
                        .map(|g| {
                            (
                                g.kind == LegKind::Ride,
                                g.name.clone(),
                                g.from_stop.clone(),
                                g.to_stop.clone(),
                                g.headsign.clone(),
                                g.dep_secs,
                                g.arr_secs,
                                g.stop_count,
                                g.board_stop_motis_id.clone(),
                                g.alight_stop_motis_id.clone(),
                            )
                        })
                        .collect::<Vec<_>>()
                };
                if key(x) == key(y) {
                    same += 1;
                    for (g7, g8) in x.iter().zip(y.iter()) {
                        max_dist_diff = max_dist_diff.max((g7.dist_m - g8.dist_m).abs());
                        coords7 += g7.coords.len() / 2;
                        coords8 += g8.coords.len() / 2;
                    }
                } else {
                    diff += 1;
                    if diff <= 5 {
                        println!("query {q}: plans differ");
                    }
                }
            }
            _ => {
                diff += 1;
                println!("query {q}: routable in one pack only");
            }
        }
        let b7 = stop_departures(&v7, fla, flo, dep, sched, 20);
        let b8 = stop_departures(&v8, fla, flo, dep, sched, 20);
        let bk = |b: &Vec<offlinerouter::transit::StopDeparture>| {
            b.iter().map(|d| (d.route_name.clone(), d.headsign.clone(), d.stop_code.clone(), d.dep_secs)).collect::<Vec<_>>()
        };
        boards += 1;
        if bk(&b7) == bk(&b8) {
            boards_same += 1;
        }
    }
    println!(
        "plans: {routed} routed, {none_both} unroutable in both; identical legs {same}, different {diff}\n\
         departure boards identical {boards_same}/{boards}\n\
         ride polylines: {coords7} -> {coords8} points; max leg distance change {max_dist_diff:.2} m\n\
         plan time: v7 {:.1} ms/query, v8 {:.1} ms/query",
        t7 * 1e3 / n as f64,
        t8 * 1e3 / n as f64
    );
    if diff == 0 && boards_same == boards {
        println!("TRANSIT PARITY OK");
        std::process::ExitCode::SUCCESS
    } else {
        println!("TRANSIT PARITY FAILED");
        std::process::ExitCode::FAILURE
    }
}
