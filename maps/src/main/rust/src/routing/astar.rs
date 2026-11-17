//! The A* main loop, including the highway-overlay hops for long routes.
use crate::geometry::*;
use crate::graph::*;
use crate::state::{RadixHeap, RoutingScratchpad};
use super::search::{seed, stub_reverse_allowed, RoutingContext};

/// Within this distance of either end the search expands every road; beyond it
/// a full-graph node only hops onto the overlay.
pub const OVERLAY_LOCAL_MM: u32 = 30_000_000;

/// Whether a point is close enough to either end to expand the full graph.
///
/// Plain A* on a cross-country route expands every residential street inside an
/// ellipse a thousand kilometres wide, and because nodes are stored in spatial
/// order the arterials it follows are scattered across the whole node table, so
/// every step inflates another block. Away from both ends the search runs on the
/// overlay instead: the same arterial edges at the same costs, stored densely.
/// Transitions between a full node and its overlay twin cost nothing and change
/// nothing about the heuristic, so A* stays consistent. Routes are near-optimal
/// rather than provably optimal: a minor-road shortcut far from both ends is not
/// seen.
#[inline]
fn is_local(g: &Graph, lat_e7: i32, lon_e7: i32, ctx: &RoutingContext) -> bool {
    fast_dist_mm(g, lat_e7, lon_e7, ctx.start.proj_lat, ctx.start.proj_lon) < OVERLAY_LOCAL_MM
        || near_end(g, lat_e7, lon_e7, ctx)
}

#[inline]
fn near_end(g: &Graph, lat_e7: i32, lon_e7: i32, ctx: &RoutingContext) -> bool {
    fast_dist_mm(g, lat_e7, lon_e7, ctx.end.proj_lat, ctx.end.proj_lon) < OVERLAY_LOCAL_MM
}


/// A* main loop. Fills `ctx.target_node` on success.
///
/// A hierarchical search that finds nothing (an end whose only way out is a long
/// minor road) is retried over every road, so pruning can cost time but never a
/// route.
pub fn perform_search_loop(
    g: &Graph,
    traffic: &TrafficSpeeds,
    ensure_traffic: &mut dyn FnMut(i32, i32),
    mode: i32,
    ctx: &mut RoutingContext,
    scratch: &mut RoutingScratchpad,
    heap: &mut RadixHeap,
) {
    search(g, traffic, ensure_traffic, mode, ctx, scratch, heap);
    if ctx.hierarchy && ctx.target_node == 0xFFFF_FFFF {
        ctx.hierarchy = false;
        ctx.iterations = 0;
        scratch.reset();
        heap.clear();
        seed(g, traffic, &ctx.start, &ctx.end, mode, scratch, heap);
        search(g, traffic, ensure_traffic, mode, ctx, scratch, heap);
    }
}

fn search(
    g: &Graph,
    traffic: &TrafficSpeeds,
    ensure_traffic: &mut dyn FnMut(i32, i32),
    mode: i32,
    ctx: &mut RoutingContext,
    scratch: &mut RoutingScratchpad,
    heap: &mut RadixHeap,
) {
    // Cap is a safety net only. The 36M-node CA graph needs ~6.4M expansions for
    // the longest routes (SF->LA), so the old 1M cap aborted them ("no route").
    // Reaching one end of the destination's edge is not the same as arriving.
    // The search can only stop at nodes, so it stops at `end.node_a` or
    // `end.node_b` and `reconstruct_path` then walks the leftover stub to the
    // projection. Stopping at whichever anchor is reached *first* ignores how
    // long that stub is, and after compaction the two anchors are a whole road
    // apart rather than a few metres, so the wrong choice costs the length of
    // the street. Score both completions and keep the better one.
    let mut best_total = u32::MAX;
    // Completing via `end.node_b` means travelling the end stub backwards
    // along the end edge; on a one-way street (driving, no reverse edge)
    // that anchor cannot complete a route. See `stub_reverse_allowed`.
    let end_reverse_ok = stub_reverse_allowed(g, &ctx.end, mode);
    let n_full = g.node_count;
    let overlay = if ctx.hierarchy { g.overlay() } else { None };
    let (end_lat, end_lon) = (ctx.end.proj_lat, ctx.end.proj_lon);
    while !heap.empty() && ctx.iterations < 25_000_000 {
        ctx.iterations += 1;
        let (score, u) = heap.pop();

        let u_cost = scratch.get_entry(u).g_fwd;
        let (lat_u, lon_u) = if u < n_full {
            let n = g.node(u);
            (n.lat_e7, n.lon_e7)
        } else {
            match overlay {
                Some(ov) if u - n_full < ov.node_count => {
                    let n = ov.node(u - n_full);
                    (n.lat_e7, n.lon_e7)
                }
                _ => continue,
            }
        };
        let h_u = heuristic_time_10ms(g, lat_u, lon_u, end_lat, end_lon, mode);
        // A node is re-pushed every time its cost improves, so older entries for it
        // linger in the heap. The fresh entry pops first and already expanded the
        // node at this same cost, so expanding a stale one again finds nothing new.
        if score != u_cost.wrapping_add(h_u) {
            continue;
        }

        // `f` is a lower bound on any route through `u`, and the heap pops in
        // non-decreasing `f` order, so once it cannot beat a completed route
        // nothing left can.
        let f_u = u_cost.saturating_add(h_u);
        if f_u >= best_total {
            break;
        }

        if u == ctx.end.node_a || (u == ctx.end.node_b && end_reverse_ok) {
            let stub = if u == ctx.end.node_a {
                ctx.end.dist_a_mm
            } else {
                ctx.end.dist_b_mm
            };
            let total = u_cost.saturating_add(get_edge_time_10ms(
                g, traffic, INVALID_EDGE, stub, ctx.end.type_, ctx.end.speed_limit, mode,
            ));
            if total < best_total {
                best_total = total;
                ctx.target_node = u;
            }
            // Keep expanding: this anchor may also be on the way to the other.
        }

        // The search runs on the traffic snapshot taken when it started, so a
        // prefetch only helps the next route. Across a continent that was ~550
        // one-degree squares per route, each a request plus a native traffic merge
        // and a renderer re-push, which stalled the UI for seconds; keep it to the
        // roads near either end, which are the ones a reroute will need.
        if mode == DRIVING && (overlay.is_none() || is_local(g, lat_u, lon_u, ctx)) {
            ensure_traffic(lat_u, lon_u);
        }

        let mut improve = |v: u32, new_g: u32| -> bool {
            let entry_v = scratch.get_entry(v);
            let better = new_g < entry_v.g_fwd;
            if better {
                entry_v.g_fwd = new_g;
                entry_v.p_fwd = u;
            }
            better
        };
        let mut push = |v: u32, new_g: u32, lat_v: i32, lon_v: i32| {
            heap.push(new_g.wrapping_add(heuristic_time_10ms(g, lat_v, lon_v, end_lat, end_lon, mode)), v);
        };

        if u >= n_full {
            let Some(ov) = overlay else { continue };
            let o = u - n_full;
            let (s, e_ptr) = ov.edge_range(o);
            for i in s..e_ptr {
                let edge = ov.edge(i);
                if edge.target >= ov.node_count || !is_mode_allowed(edge.type_, mode) {
                    continue;
                }
                let travel_time = get_edge_time_10ms(
                    g, traffic, edge.full_edge, edge.dist_mm, edge.type_, edge.speed_limit, mode,
                );
                let (v, new_g) = (n_full + edge.target, u_cost.wrapping_add(travel_time));
                if improve(v, new_g) {
                    let t = ov.node(edge.target);
                    push(v, new_g, t.lat_e7, t.lon_e7);
                }
            }
            // Back onto the full graph only where the destination's local roads are.
            if near_end(g, lat_u, lon_u, ctx) {
                let full = ov.node(o).full_node;
                if improve(full, u_cost) {
                    push(full, u_cost, lat_u, lon_u);
                }
            }
            continue;
        }

        if overlay.is_none() || is_local(g, lat_u, lon_u, ctx) {
            let (s, e_ptr) = g.edge_range(u);
            for i in s..e_ptr {
                let edge = g.edge(u, i);
                if !is_mode_allowed(edge.type_, mode) {
                    continue;
                }
                let travel_time =
                    get_edge_time_10ms(g, traffic, i, edge.dist_mm, edge.type_, edge.speed_limit, mode);
                let new_g = u_cost.wrapping_add(travel_time);
                if improve(edge.target, new_g) {
                    let n_v = g.get_node(edge.target);
                    push(edge.target, new_g, n_v.lat_e7, n_v.lon_e7);
                }
            }
        }
        if let Some(o) = overlay.and_then(|ov| ov.of_full(u)) {
            if improve(n_full + o, u_cost) {
                push(n_full + o, u_cost, lat_u, lon_u);
            }
        }
    }
}
