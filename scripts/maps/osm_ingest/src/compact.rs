//! Degree-2 chain collapsing: the step that turns one graph edge per OSM
//! geometry vertex into one graph edge per road between junctions.
//!
//! Before this stage, `graph_build` emitted an edge for every consecutive pair
//! of way nodes, so every geometry vertex was a routing node with two
//! neighbours. California came out at 35.8 M nodes / 74.3 M directed edges — a
//! ratio of 2.07, the signature of a graph where almost every node is a
//! pass-through. Those nodes cost 16 bytes each in `nodes.bin`, 14 bytes per
//! direction in `edges.bin` and 8 bytes per direction in `lanes.bin`, and they
//! buy nothing: no route ever chooses anything at them.
//!
//! Collapsing them moves the vertices into `intermediate.bin` at 4 bytes each
//! (see [`crate::geom`]) and leaves the road network's shape untouched.
//!
//! # What may be collapsed
//!
//! A node is interior to a chain only when removing it cannot change a routing
//! decision or lose data:
//!
//! * exactly two incident segments, leading to two *distinct* neighbours that
//!   are both distinct from the node itself;
//! * both segments agree on road type, speed limit, name and one-way-ness, so
//!   the merged edge can carry a single set of attributes;
//! * their lane masks agree *in each traversed direction* — checked on mask
//!   contents, not on pool offsets, since two ways with identical lanes intern
//!   them at different offsets;
//! * one-way segments must actually flow through the node, one in and one out;
//! * the node carries no transit stop code, because `graph_build`'s isolated
//!   stop reconnection addresses stop nodes directly.
//!
//! # Chains that close on themselves
//!
//! A ring road whose every node is interior has no anchor to start from. The
//! lowest-numbered node on such a cycle is promoted back to a real node so the
//! ring becomes one edge from it to itself. A self-loop is inert for routing —
//! no shortest path traverses one — but it keeps the ring snappable and drawable,
//! which is all its interior nodes were doing anyway.

use crate::geom;

/// Sentinel for "this node was collapsed away".
pub const REMOVED: u32 = u32::MAX;

/// An undirected road segment between two adjacent way nodes: what pass 3
/// produces before it is split into one or two directed edges.
///
/// Road type, speed limit and name come from the way and so are shared by both
/// directions; only lane masks differ, which is why they are stored per
/// direction.
#[derive(Clone, Copy)]
pub struct Seg {
    pub u: u32,
    pub v: u32,
    pub dist_mm: u32,
    pub name_offset: u32,
    pub type_: u8,
    pub speed_limit: u8,
    /// True when the way is only traversable `u` -> `v`.
    pub oneway: bool,
    pub fwd_lane_off: u32,
    pub fwd_lane_count: u16,
    pub bwd_lane_off: u32,
    pub bwd_lane_count: u16,
}

impl Seg {
    /// The endpoint that is not `n`. For a self-loop this is `n` again.
    #[inline]
    fn other(&self, n: u32) -> u32 {
        if self.u == n {
            self.v
        } else {
            self.u
        }
    }

    /// Lane pool range for traversal starting at `from`.
    #[inline]
    fn lanes_from(&self, from: u32) -> (u32, u16) {
        if self.u == from {
            (self.fwd_lane_off, self.fwd_lane_count)
        } else {
            (self.bwd_lane_off, self.bwd_lane_count)
        }
    }
}

/// One collapsed run of segments, to be emitted as one directed edge per
/// permitted direction.
///
/// `pts_start`/`pts_len` index [`Compacted::pts`], which holds *original* node
/// ids: the caller needs their coordinates for the geometry blob, and only its
/// two endpoints survive into `nodes.bin`.
pub struct Chain {
    pub pts_start: u64,
    pub pts_len: u32,
    /// Sum of the merged segments' distances, saturating.
    pub dist_mm: u32,
    pub name_offset: u32,
    pub type_: u8,
    pub speed_limit: u8,
    pub oneway: bool,
    /// Lanes for `pts[0]` -> `pts[last]`.
    pub fwd_lane_off: u32,
    pub fwd_lane_count: u16,
    /// Lanes for `pts[last]` -> `pts[0]`. Meaningless when `oneway`.
    pub bwd_lane_off: u32,
    pub bwd_lane_count: u16,
}

impl Chain {
    /// Original node ids from tail to head.
    pub fn pts<'a>(&self, all: &'a [u32]) -> &'a [u32] {
        let s = self.pts_start as usize;
        &all[s..s + self.pts_len as usize]
    }
}

pub struct Compacted {
    /// Original node id -> surviving node id, or [`REMOVED`].
    pub new_id: Vec<u32>,
    /// How many nodes survived.
    pub kept: u32,
    pub chains: Vec<Chain>,
    pub pts: Vec<u32>,
    /// Chains that had to be cut because their geometry would not fit one edge.
    pub splits: usize,
}

/// Collapse every eligible degree-2 chain in `segs`.
///
/// `point` supplies a node's coordinates (needed to budget the geometry blob),
/// `is_stop` reports whether it carries a transit stop code, and `lane_pool` is
/// the interned lane-mask pool the segments' offsets point into.
pub fn compact(
    node_count: u32,
    segs: &[Seg],
    lane_pool: &[u16],
    point: impl Fn(u32) -> geom::Pt,
    is_stop: impl Fn(u32) -> bool,
) -> Compacted {
    let n = node_count as usize;
    let inc = Incidence::build(n, segs);
    let mut interior = classify(n, segs, lane_pool, &inc, &is_stop);

    let mut out = Compacted {
        new_id: Vec::new(),
        kept: 0,
        chains: Vec::new(),
        pts: Vec::new(),
        splits: 0,
    };
    let mut seg_used = vec![false; segs.len()];

    // Walk every chain that has at least one real endpoint.
    for a in 0..n as u32 {
        if interior[a as usize] {
            continue;
        }
        for si in inc.at(a) {
            walk_chain(a, si, segs, &inc, &mut interior, &mut seg_used, &point, &mut out);
        }
    }

    // Anything still unused is a cycle of interior nodes with no anchor. Break
    // it at its lowest-numbered node and walk again. Each pass consumes at
    // least one cycle, so this terminates.
    for si in 0..segs.len() {
        if seg_used[si] {
            continue;
        }
        let anchor = cycle_anchor(si, segs, &inc);
        interior[anchor as usize] = false;
        for sj in inc.at(anchor) {
            walk_chain(anchor, sj, segs, &inc, &mut interior, &mut seg_used, &point, &mut out);
        }
    }

    // Renumber. Survivors keep their relative order, so the Morton sort the
    // device binary-searches is preserved for free.
    out.new_id = vec![REMOVED; n];
    let mut next = 0u32;
    for (i, &is_interior) in interior.iter().enumerate().take(n) {
        if !is_interior {
            out.new_id[i] = next;
            next += 1;
        }
    }
    out.kept = next;
    out
}

/// Segment ids incident to each node, as a CSR.
struct Incidence {
    start: Vec<u64>,
    seg: Vec<u32>,
}

impl Incidence {
    fn build(n: usize, segs: &[Seg]) -> Incidence {
        let mut start = vec![0u64; n + 2];
        for s in segs {
            start[s.u as usize + 1] += 1;
            start[s.v as usize + 1] += 1;
        }
        for i in 0..=n {
            start[i + 1] += start[i];
        }
        let mut seg = vec![0u32; start[n] as usize];
        let mut cursor: Vec<u64> = start[..=n].to_vec();
        for (i, s) in segs.iter().enumerate() {
            for endpoint in [s.u, s.v] {
                let c = &mut cursor[endpoint as usize];
                seg[*c as usize] = i as u32;
                *c += 1;
            }
        }
        start.truncate(n + 1);
        Incidence { start, seg }
    }

    #[inline]
    fn at(&self, n: u32) -> impl Iterator<Item = u32> + '_ {
        let (a, b) = (self.start[n as usize] as usize, self.start[n as usize + 1] as usize);
        self.seg[a..b].iter().copied()
    }

    #[inline]
    fn degree(&self, n: u32) -> u64 {
        self.start[n as usize + 1] - self.start[n as usize]
    }
}

/// Decide, for every node, whether it is a pass-through vertex that can be
/// folded into an edge's geometry.
fn classify(
    n: usize,
    segs: &[Seg],
    lane_pool: &[u16],
    inc: &Incidence,
    is_stop: &impl Fn(u32) -> bool,
) -> Vec<bool> {
    let lanes = |off: u32, count: u16| -> &[u16] {
        if count == 0 || off == u32::MAX {
            return &[];
        }
        let s = off as usize;
        &lane_pool[s..s + count as usize]
    };

    let mut interior = vec![false; n];
    for w in 0..n as u32 {
        if is_stop(w) {
            continue;
        }
        // Degree 0 is dropped rather than kept: a node with no incident segment
        // is a way the region filter removed, dead weight in `nodes.bin` with no
        // edge ever addressing it. Marked interior so the renumber below hands
        // it REMOVED; no walk can reach a node with no segments, so the mark is
        // unreachable by construction.
        if inc.degree(w) == 0 {
            interior[w as usize] = true;
            continue;
        }
        if inc.degree(w) != 2 {
            continue;
        }
        let mut it = inc.at(w);
        let (i1, i2) = match (it.next(), it.next()) {
            (Some(a), Some(b)) => (a, b),
            _ => continue,
        };
        let (s1, s2) = (&segs[i1 as usize], &segs[i2 as usize]);
        let (a, b) = (s1.other(w), s2.other(w));
        // Distinct neighbours, neither of them w. `a == b` means two parallel
        // segments between the same pair of nodes; collapsing that would fuse
        // two genuinely different roads into one polyline.
        if a == w || b == w || a == b {
            continue;
        }
        if s1.type_ != s2.type_
            || s1.speed_limit != s2.speed_limit
            || s1.name_offset != s2.name_offset
            || s1.oneway != s2.oneway
        {
            continue;
        }
        if s1.oneway {
            // Traffic has to pass through: one segment must end at w and the
            // other start there. Two one-ways both pointing at w make it a
            // sink, which is a real feature of the network, not a pass-through.
            let flows = (s1.v == w && s2.u == w) || (s2.v == w && s1.u == w);
            if !flows {
                continue;
            }
        }
        // Lane masks must agree along each direction actually traversed.
        let (o1, c1) = s1.lanes_from(a);
        let (o2, c2) = s2.lanes_from(w);
        if lanes(o1, c1) != lanes(o2, c2) {
            continue;
        }
        if !s1.oneway {
            let (o3, c3) = s2.lanes_from(b);
            let (o4, c4) = s1.lanes_from(w);
            if lanes(o3, c3) != lanes(o4, c4) {
                continue;
            }
        }
        interior[w as usize] = true;
    }
    interior
}

/// Follow the chain that leaves anchor `a` through segment `si`, emitting one or
/// more [`Chain`]s. Does nothing if the segment was already consumed from the
/// other end.
#[allow(clippy::too_many_arguments)]
fn walk_chain(
    a: u32,
    si: u32,
    segs: &[Seg],
    inc: &Incidence,
    interior: &mut [bool],
    seg_used: &mut [bool],
    point: &impl Fn(u32) -> geom::Pt,
    out: &mut Compacted,
) {
    if seg_used[si as usize] {
        return;
    }
    let mut path = vec![a];
    // One entry per hop in `path`, so a split run can take its own distances
    // rather than look them up again. The lookup would have to match a segment by
    // its endpoints, which is ambiguous for a two-node run between two junctions
    // joined by more than one way.
    let mut hops: Vec<u32> = Vec::new();
    let mut prev_seg = si;
    let mut cur = segs[si as usize].other(a);
    seg_used[si as usize] = true;
    hops.push(segs[si as usize].dist_mm);
    path.push(cur);

    while interior[cur as usize] {
        // Interior nodes have exactly two incident segments by construction, so
        // "the one we did not arrive on" is well defined.
        let next_seg = match inc.at(cur).find(|s| *s != prev_seg) {
            Some(s) => s,
            None => break,
        };
        if seg_used[next_seg as usize] {
            break;
        }
        seg_used[next_seg as usize] = true;
        hops.push(segs[next_seg as usize].dist_mm);
        cur = segs[next_seg as usize].other(cur);
        path.push(cur);
        prev_seg = next_seg;
    }

    // A one-way chain must be emitted along its traffic flow, not along whichever
    // end the walk happened to start from. `classify` guarantees every segment in
    // it is consistently oriented, so the first segment decides: if the walk came
    // in against the flow, turn the whole path around.
    let mut first_seg = si;
    let mut last_seg = prev_seg;
    if segs[si as usize].oneway && segs[si as usize].u != path[0] {
        path.reverse();
        hops.reverse();
        std::mem::swap(&mut first_seg, &mut last_seg);
    }

    // The chain's attributes are those of its first segment; every segment in it
    // was required to agree.
    let head = &segs[first_seg as usize];
    let (fwd_lane_off, fwd_lane_count) = head.lanes_from(path[0]);
    let last = path[path.len() - 1];
    let (bwd_lane_off, bwd_lane_count) = segs[last_seg as usize].lanes_from(last);

    let runs = split_runs(&path, point);
    if runs.len() > 1 {
        out.splits += runs.len() - 1;
    }
    for (lo, hi) in runs {
        // A cut point becomes a real node again.
        if lo != 0 {
            interior[path[lo] as usize] = false;
        }
        let pts_start = out.pts.len() as u64;
        out.pts.extend_from_slice(&path[lo..=hi]);
        out.chains.push(Chain {
            pts_start,
            pts_len: (hi - lo + 1) as u32,
            dist_mm: hops[lo..hi].iter().fold(0u32, |a, d| a.saturating_add(*d)),
            name_offset: head.name_offset,
            type_: head.type_,
            speed_limit: head.speed_limit,
            oneway: head.oneway,
            fwd_lane_off,
            fwd_lane_count,
            bwd_lane_off,
            bwd_lane_count,
        });
    }
}

/// Cut `path` into runs that each fit one edge's geometry.
///
/// Returns inclusive index ranges. A run of two nodes is always allowed even if
/// its single segment is too long to delta-encode: the caller stores no geometry
/// for it, and the reader's straight-chord fallback is then exactly right,
/// because a two-node run *is* a straight chord.
fn split_runs(path: &[u32], point: &impl Fn(u32) -> geom::Pt) -> Vec<(usize, usize)> {
    let mut runs = Vec::new();
    let mut start = 0usize;
    let mut cost = 1u64;
    let mut i = 1usize;
    while i < path.len() {
        let step = geom::segment_points(point(path[i - 1]), point(path[i]));
        if cost + step <= u64::from(geom::MAX_POINTS) {
            cost += step;
            i += 1;
            continue;
        }
        if i - 1 > start {
            // Close the run before this segment and retry it with a fresh
            // budget starting at the shared node.
            runs.push((start, i - 1));
            start = i - 1;
            cost = 1;
        } else {
            // One segment that cannot be encoded at all, even alone.
            runs.push((start, i));
            start = i;
            cost = 1;
            i += 1;
        }
    }
    if start < path.len() - 1 {
        runs.push((start, path.len() - 1));
    }
    runs
}

include!("compact_part1.rs");