//! Host timing for `updateTrafficNative`'s per-square walk, against a REAL planet archive.
//!
//! Ignored by default and gated on `MAMAPS_REAL_ARCHIVE` like `real_archive.rs`:
//!
//! ```text
//! MAMAPS_REAL_ARCHIVE=/path/to/world.mamaps \
//!   cargo test -p offlinerouter --release --test traffic_update_bench -- --ignored --nocapture
//! ```
//!
//! `updateTrafficNative` runs on an IO thread after each 1° traffic square lands, and for every
//! edge in it recovers the source node (a binary search over the planet's node array), reads the
//! edge and both endpoints. Until this change it did all of that while holding `traffic_meta`,
//! the same lock the main thread's camera-settle prefetch (`ensureTrafficLoadedNative`) takes
//! first, so the main thread could sit behind the whole walk. This harness times that walk.

use offlinerouter::graph::{Graph, DRIVING};
use offlinerouter::routing::find_nearest_edge;
use std::collections::{HashSet, VecDeque};
use std::time::Instant;

fn archive() -> Option<String> {
    match std::env::var("MAMAPS_REAL_ARCHIVE") {
        Ok(p) if std::path::Path::new(&p).is_file() => Some(p),
        _ => {
            eprintln!("MAMAPS_REAL_ARCHIVE not set: skipped");
            None
        }
    }
}

/// Up to `cap` edge ids whose source node lies in the 1° square at (`lat0`, `lon0`), found by a
/// breadth-first walk from the node nearest the square's centre — a stand-in for the edge ids a
/// traffic fetch for that square carries.
fn square_edges(g: &Graph, lat0: f64, lon0: f64, cap: usize) -> Vec<u64> {
    let start = find_nearest_edge(g, lat0 + 0.5, lon0 + 0.5, DRIVING).node_a;
    let inside = |n: u32| {
        let m = g.node(n);
        let (lat, lon) = (m.lat_e7 as f64 * 1e-7, m.lon_e7 as f64 * 1e-7);
        lat >= lat0 && lat < lat0 + 1.0 && lon >= lon0 && lon < lon0 + 1.0
    };
    let mut seen = HashSet::new();
    let mut queue = VecDeque::from([start]);
    let mut edges = Vec::new();
    while let Some(n) = queue.pop_front() {
        if !seen.insert(n) || !inside(n) {
            continue;
        }
        let (s, e) = g.edge_range(n);
        for idx in s..e {
            edges.push(idx);
            let t = g.edge(n, idx).target;
            if t < g.node_count {
                queue.push_back(t);
            }
        }
        if edges.len() >= cap {
            break;
        }
    }
    edges
}

/// The per-edge work `updateTrafficNative` does, minus the JNI copy.
fn walk(g: &Graph, edges: &[u64]) -> Vec<f64> {
    let mut segments = Vec::with_capacity(edges.len() * 5);
    for &edge_id in edges {
        if edge_id >= g.edge_count {
            continue;
        }
        let source = g.find_node_idx_for_edge(edge_id);
        let edge = g.edge(source, edge_id);
        let node_u = g.node(source);
        if edge.target < g.node_count {
            let node_v = g.node(edge.target);
            segments.extend_from_slice(&[
                node_u.lat_e7 as f64 * 1e-7,
                node_u.lon_e7 as f64 * 1e-7,
                node_v.lat_e7 as f64 * 1e-7,
                node_v.lon_e7 as f64 * 1e-7,
                1.0,
            ]);
        }
    }
    segments
}

#[test]
#[ignore = "needs MAMAPS_REAL_ARCHIVE (80 GB planet archive)"]
fn the_traffic_square_walk() {
    let Some(path) = archive() else { return };
    let g = Graph::load_archive(&path).expect("graph");
    for (name, lat0, lon0) in [("SF", 37.0, -123.0), ("LA", 34.0, -119.0), ("London", 51.0, -1.0)] {
        let edges = square_edges(&g, lat0, lon0, 60_000);
        let t = Instant::now();
        let first = walk(&g, &edges);
        let first_ms = t.elapsed().as_secs_f64() * 1e3;
        let t = Instant::now();
        let again = walk(&g, &edges);
        let again_ms = t.elapsed().as_secs_f64() * 1e3;
        assert_eq!(first.len(), again.len());
        println!(
            "{name}: {} edges, walk {first_ms:.1} ms first pass, {again_ms:.1} ms warm \
             ({:.2} us/edge warm)",
            edges.len(),
            again_ms * 1e3 / edges.len().max(1) as f64
        );
    }
}
