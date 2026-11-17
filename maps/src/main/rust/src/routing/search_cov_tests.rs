//! End-to-end routing tests over the synthetic square graph from
//! `graph::graph_cov_tests`: snapping, the A* loop, the on-edge direct path
//! and step reconstruction, all with hand-computable distances (0.01° at the
//! equator is ~1113.9 m per side).
use crate::geometry::TrafficSpeeds;
use crate::graph::graph_cov_tests::{assemble, Spec};
use crate::graph::{BICYCLE, DRIVING, PUBLIC_TRANSIT, WALK};
use crate::routing::{ascent_descent, find_nearest_edge, perform_search_loop, prepare_routing, reconstruct_path, route_ascent_descent};
use crate::state::{RadixHeap, RoutingScratchpad};
use std::collections::BTreeMap;

fn square(one_way: bool, elevation: Option<Vec<i16>>) -> Spec {
    let mut edges: Vec<(u32, u32, u8, u8, u32)> = Vec::new();
    for (s, t) in [(0, 1), (1, 2), (2, 3), (3, 0)] {
        edges.push((s, t, 5, 50, 1_113_900));
    }
    if !one_way {
        for (s, t) in [(1, 0), (2, 1), (3, 2), (0, 3)] {
            edges.push((s, t, 5, 50, 1_113_900));
        }
    }
    Spec {
        nodes: vec![(0, 0), (0, 100_000), (100_000, 100_000), (100_000, 0)],
        edges,
        escaped: vec![],
        names: BTreeMap::new(),
        pool: vec![],
        geom: BTreeMap::new(),
        lanes: None,
        elevation,
    }
}

fn plan(spec: &Spec, s: (f64, f64), e: (f64, f64), mode: i32) -> Option<(Vec<crate::routing::StepData>, bool)> {
    let g = assemble(spec);
    let speeds = TrafficSpeeds::default();
    let (mut scratch, mut heap) = (RoutingScratchpad::new(), RadixHeap::new());
    let mut ensure = |_: i32, _: i32| {};
    let mut ctx = prepare_routing(&g, &speeds, &mut ensure, s.0, s.1, e.0, e.1, mode, &mut scratch, &mut heap)?;
    let has_direct = ctx.direct.is_some();
    perform_search_loop(&g, &speeds, &mut ensure, mode, &mut ctx, &mut scratch, &mut heap);
    Some((reconstruct_path(&g, &speeds, mode, &ctx, &mut scratch), has_direct))
}

fn total(steps: &[crate::routing::StepData]) -> (u64, u64) {
    (steps.iter().map(|s| s.dist_mm).sum(), steps.iter().map(|s| s.time_10ms).sum())
}

#[test]
fn snapping_finds_the_nearest_edge_and_projection() {
    let g = assemble(&square(false, None));
    // 55 m north of the 0->1 edge's midpoint.
    let s = find_nearest_edge(&g, 0.0005, 0.005, DRIVING);
    assert_eq!((s.node_a, s.node_b), (0, 1));
    assert!((500_000..580_000).contains(&s.dist_a_mm), "dist_a {}", s.dist_a_mm);
    assert!((500_000..580_000).contains(&s.dist_b_mm), "dist_b {}", s.dist_b_mm);
    assert_eq!((s.type_, s.speed_limit), (5, 50));
    assert_ne!(s.edge_idx, crate::graph::INVALID_EDGE);
    // An empty graph snaps to the sentinel, never to node 0.
    let mut empty = square(false, None);
    empty.nodes.clear();
    empty.edges.clear();
    let g0 = assemble(&empty);
    assert_eq!(find_nearest_edge(&g0, 0.0, 0.0, WALK).node_a, 0xFFFF_FFFF);
}

#[test]
fn a_two_leg_route_reconstructs_with_the_right_distance_and_time() {
    let (steps, direct) = plan(&square(false, None), (0.0001, 0.0001), (0.0099, 0.0099), DRIVING).unwrap();
    assert!(!direct, "different edges: the search, not the direct path");
    assert!(!steps.is_empty());
    let (dist, time) = total(&steps);
    // Two sides of the square (~1114 m each) less the corner stubs.
    assert!((2_050_000..2_300_000).contains(&dist), "dist {dist} mm");
    assert!(time > 0 && time < 100_000, "time {time}");
    let first = &steps[0].coords;
    assert!((first[0] - 0.0001).abs() < 0.001 && (first[1] - 0.0001).abs() < 0.001, "starts at the start");
    let last_step = steps.last().unwrap();
    let last = &last_step.coords;
    let (lo, la) = (last[last.len() - 2], last[last.len() - 1]);
    assert!((lo - 0.0099).abs() < 0.001 && (la - 0.0099).abs() < 0.001, "ends at the end, got {lo},{la}");
    // The same corners walk and cycle too (slower over the fixed speeds).
    for mode in [WALK, BICYCLE] {
        let (wsteps, _) = plan(&square(false, None), (0.0001, 0.0001), (0.0099, 0.0099), mode).unwrap();
        let (wd, wt) = total(&wsteps);
        assert!((2_050_000..2_300_000).contains(&wd), "mode {mode} dist {wd}");
        assert!(wt > time, "mode {mode} is slower than driving");
    }
}

#[test]
fn a_trip_along_one_road_takes_the_direct_path_both_ways() {
    // Both ends on edge 0->1, 0.006 degrees apart (~668 m): no node detour.
    let (steps, direct) = plan(&square(false, None), (0.0, 0.002), (0.0, 0.008), DRIVING).unwrap();
    assert!(direct, "same directed edge");
    let (dist, _) = total(&steps);
    assert!((620_000..720_000).contains(&dist), "direct dist {dist} mm, not a node round trip");
    let (rsteps, rdirect) = plan(&square(false, None), (0.0, 0.008), (0.0, 0.002), DRIVING).unwrap();
    assert!(rdirect, "the twin makes the return direct too");
    let (rdist, _) = total(&rsteps);
    assert!((620_000..720_000).contains(&rdist), "return dist {rdist} mm");
}

#[test]
fn a_one_way_road_forbids_the_backward_direct_path() {
    let (steps, direct) = plan(&square(true, None), (0.0, 0.008), (0.0, 0.002), DRIVING).unwrap();
    assert!(!direct, "no twin, no backward direct path");
    let (dist, _) = total(&steps);
    // Around the other three sides (~3.3 km) instead of 0.67 km back.
    assert!(dist > 2_500_000, "one-way detour dist {dist} mm");
    // Forward along the one-way street is still direct.
    let (_, fwd_direct) = plan(&square(true, None), (0.0, 0.002), (0.0, 0.008), DRIVING).unwrap();
    assert!(fwd_direct);
}

#[test]
fn transit_walk_legs_refuse_implausible_lengths_and_unsearched_routes_are_empty() {
    // A 10-degree transit walk leg is refused before snapping.
    assert!(plan(&square(false, None), (0.0, 0.0), (10.0, 10.0), PUBLIC_TRANSIT).is_none());
    // Reconstructing before any search (target unset, no direct) is empty.
    let g = assemble(&square(false, None));
    let speeds = TrafficSpeeds::default();
    let (mut scratch, mut heap) = (RoutingScratchpad::new(), RadixHeap::new());
    let mut ensure = |_: i32, _: i32| {};
    let ctx = prepare_routing(&g, &speeds, &mut ensure, 0.0001, 0.0001, 0.0099, 0.0099, DRIVING, &mut scratch, &mut heap).unwrap();
    assert!(reconstruct_path(&g, &speeds, DRIVING, &ctx, &mut scratch).is_empty());
}

#[test]
fn elevations_ride_the_route_and_sum_to_the_terrain() {
    let spec = square(false, Some(vec![0, 100, 0, 0]));
    let (steps, _) = plan(&spec, (0.0001, 0.0001), (0.0099, 0.0099), DRIVING).unwrap();
    let (asc, desc) = route_ascent_descent(&steps);
    assert!((asc - 100.0).abs() < 3.0, "climbs node 1's 100 m: {asc}");
    assert!((desc - 100.0).abs() < 3.0, "and descends again: {desc}");
    assert_eq!(ascent_descent(&[]), (0.0, 0.0));
    assert_eq!(ascent_descent(&[5.0]), (0.0, 0.0));
}

#[test]
fn a_route_over_curved_edges_reconstructs_through_their_geometry() {
    // Bottom edge bulges north, right edge bulges west: the route must follow
    // the stored polylines (start stub, main leg and end stub all take the
    // geometry branches of reconstruct_path), not the straight chords.
    let mut spec = square(false, None);
    let mut sorted = spec.edges.clone();
    sorted.sort_by_key(|e| e.0);
    let bottom = sorted.iter().position(|e| (e.0, e.1) == (0, 1)).unwrap() as u64;
    let right = sorted.iter().position(|e| (e.0, e.1) == (1, 2)).unwrap() as u64;
    spec.geom = BTreeMap::from([
        (bottom, vec![(30_000i16, 30_000i16)]),
        (right, vec![(30_000i16, -30_000i16)]),
    ]);
    let (steps, _) = plan(&spec, (0.0001, 0.0002), (0.009, 0.0099), DRIVING).unwrap();
    assert!(!steps.is_empty());
    let (dist, _) = total(&steps);
    // Longer than the two straight chords (~2.13 km) because of the bulges,
    // shorter than any three-side alternative.
    assert!((1_900_000..2_900_000).contains(&dist), "curved dist {dist} mm");
    let all: Vec<f64> = steps.iter().flat_map(|s| s.coords.clone()).collect();
    let has_bulge = all.windows(2).any(|w| (w[0] - 0.003).abs() < 0.001 && (w[1] - 0.003).abs() < 0.001);
    assert!(has_bulge, "an interior bulge vertex (lon 0.003, lat 0.003) is in the route: {all:?}");
}
