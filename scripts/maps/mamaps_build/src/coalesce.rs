//! Merging a layer's line features into one per class, and joining the fragments that continue each
//! other.
//!
//! # Why this exists
//!
//! An OSM way is an **editing** unit. It is split at every junction, bridge, surface change and tag
//! edit, none of which has anything to do with what gets drawn. Carrying one way as one rendering
//! feature therefore pays a feature header and a part entry for each fragment of a road, and on a
//! us-west z7 tile that came to:
//!
//! | | count | width | bytes |
//! |---|---|---|---|
//! | feature records | 57,218 | 16 | 916 KB |
//! | part entries | 57,218 | 12 | 687 KB |
//! | coordinate arena | 189,484 points | ~2-3 | ~0.5 MB |
//!
//! Three quarters of the layer was bookkeeping, 61% of its parts held exactly two points, and the
//! mean was 3.31 points per part. The same tile's `boundaries` layer -- whose relations arrive
//! already merged -- ran 139 features over 667 parts, which is what healthy looks like.
//!
//! It compounds three ways. The headers are the obvious cost. Less obvious is that
//! [`tilecodec::mamaps::body`] restarts its coordinate deltas at every part so a part decodes
//! independently, so at 3.3 points per part most points are the *first* of their part and pay a full
//! absolute coordinate where a continued line would pay one byte. And a `.mamaps` layer indexes its
//! features with a `u16`, so a North America build stopped outright at `layer 4 has 79407 features,
//! past the 65535 a .mamaps body can index`.
//!
//! This is not a container-format problem -- MVT has the same one, which is why `tippecanoe` ships
//! `--coalesce`. It is that nothing in this pipeline ever merged what OSM had split.
//!
//! # What it does
//!
//! Two steps, and the first is the one that matters for the `u16`:
//!
//! 1. **Group by class.** Line features drawn identically -- same `kind`, `kind_detail`, `flags`
//!    and `transit_color` -- become one multi-part feature. Nothing about the geometry changes; a
//!    `.mamaps` line feature has always been able to hold many parts, and `boundaries` already did.
//! 2. **Join what continues.** Within a group, a part whose last point is another's first point is
//!    the same line cut in half, so they are spliced into one part. That removes the part entry and
//!    lets the delta run through the joint instead of restarting.
//!
//! # Why it is safe
//!
//! A `.mamaps` feature carries no identity of its own: [`Feature`] is `kind`, `kind_detail`,
//! `geom_type`, `flags`, `transit_color`, the three transit lane fields and a part range, and
//! nothing else.
//! Two line features that
//! agree on all of those are indistinguishable to a renderer, so merging them cannot change a pixel.
//! There are no names or ids on a feature to lose, and `winding` is meaningless on an open path --
//! which [`crate::rings::normalise`] states outright.
//!
//! `transit_color` is part of the key rather than dropped from it because it is the one per-feature
//! field a renderer reads directly. Two subway lines of different operator colours crossing a tile
//! are the same `kind` and would otherwise collapse into one feature wearing whichever colour came
//! first. The lane fields join it for the same reason one step on: two routes of *one* colour on
//! different corridor ordinals are drawn as two parallel lines, and merging them would put both
//! on whichever ordinal came first.
//!
//! The carriageway joins the key too, and it is the one part of the key that is **not** on the
//! feature: the directional split lives in a side table because it is present on a minority of
//! roads. It has to be in the key all the same, because two four-lane roads that divide 3/1 and
//! 1/3 are drawn as different surfaces with the centre line in different places. A one-way and a
//! two-way are already separated for free, because that one is a flag.
//!
//! Direction is not preserved through a join and does not need to be: nothing in
//! [`tilecodec::mamaps::body`]'s flag set reads a line's direction, so `A -> B` and `B -> A` draw
//! the same. Joins are made head-to-tail only, so a part is never reversed anyway.
//!
//! # Ordering, and why the archive stays reproducible
//!
//! Called on a tile that the merge has already assembled, so it sees a layer's features in the order
//! the store yielded them, and it emits each class at the position of that class's **first** feature.
//! A group's parts stay in feature-then-part order and chains are built by walking them in that
//! order, so nothing here depends on a hash map's iteration order or on how the map phase chunked
//! its input.

use std::collections::HashMap;

use crate::coalesce_extra::Run;
use tilecodec::mamaps::body::{
    BuildingAttrs, Carriageway, LaneTurns, Layer, Part, GEOM_LINE, WINDING_OUTER,
};

/// What coalescing a build saved, for the report.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Stats {
    pub features_before: u64,
    pub features_after: u64,
    pub parts_before: u64,
    pub parts_after: u64,
}

impl Stats {
    pub fn add(&mut self, other: Stats) {
        self.features_before += other.features_before;
        self.features_after += other.features_after;
        self.parts_before += other.parts_before;
        self.parts_after += other.parts_after;
    }
}

/// What makes two line features interchangeable to a renderer. The trailing `name_idx` keeps two
/// differently-named roads or rivers of the same class apart, so a curved label is never attached
/// to a line that merged with a neighbour of another name; same-named fragments still merge.
///
/// The last three come from the carriageway side table rather than from the feature, and they are
/// in the key for the same reason `transit_color` is: two four-lane roads of one class that divide
/// 3/1 and 1/3 are drawn as different surfaces, and merging them would put both on whichever split
/// came first. `flags` already separates a one-way from a two-way, because that is a flag.
type Class = (u16, u16, u8, u32, u8, u8, u8, u8, u16, u8, u8, u32);

/// One line part, as `(coord_start, point_count)` into a layer's existing arena.
///
/// Indices rather than copied points: a group can hold tens of thousands of parts, and a `Vec` of
/// points each would be the allocation this module exists to remove.
pub(crate) type Span = (u32, u32);

/// Merge a layer's line features by class and splice the parts that continue each other.
///
/// Polygon features are left exactly as they were, in place; only the parts table and the arena are
/// rebuilt around them, which [`crate::rings::normalise`] does immediately afterwards anyway.
#[cfg(test)]
pub fn coalesce_lines(layer: &mut Layer) -> Stats {
    coalesce_lines_with_ids(layer, None, None, None, None)
}

/// As [`coalesce_lines`], but also rewrites an id side table, a turn-lane side table and a
/// carriageway side table so they stay parallel to the features.
///
/// The tables are indexed by feature position, so merging lines without rewriting them leaves more
/// entries than features and the encoder rejects the tile. A merged line takes the id of the first
/// feature of its class, which is arbitrary — that is why only layers whose id-carrying features
/// are *not* lines populate the id table at all (see `extract::tracks_ids`). Turn masks are the
/// opposite: they belong to a *road* line, so a line that carries any is held out of merging
/// entirely (each becomes its own survivor) rather than folded into a neighbour and losing them.
/// A carriageway takes the third route: it is part of the merge key, so a survivor's split is by
/// construction the split every line it absorbed had.
pub fn coalesce_lines_with_ids(
    layer: &mut Layer,
    ids: Option<&mut Vec<u64>>,
    turns: Option<&mut Vec<LaneTurns>>,
    buildings: Option<&mut Vec<BuildingAttrs>>,
    carriageways: Option<&mut Vec<Carriageway>>,
) -> Stats {
    let mut stats = Stats {
        features_before: layer.features.len() as u64,
        parts_before: layer.parts.len() as u64,
        ..Stats::default()
    };
    let lines = layer.features.iter().filter(|f| f.geom_type == GEOM_LINE).count();
    // One line feature cannot be merged with anything, and a layer of pure polygons is the common
    // case: both would only pay for a rebuild that changes nothing.
    //
    // Boundaries are never merged at all: their relations arrive one feature per member way,
    // and merging them by class would chain the US-Canada border to the US-Mexico border
    // across the continent into one part — destroying the per-way tile locality that keeps
    // border tiles small and complete. The u16 feature cap this exists to relieve does not
    // bind here (relations are already merged); roads are the layer that needs it.
    if lines < 2 || layer.layer_id == tilecodec::mamaps::dict::LAYER_BOUNDARIES {
        stats.features_after = stats.features_before;
        stats.parts_after = stats.parts_before;
        return stats;
    }

    // A line that carries turn masks is never merged — its arrows belong to that exact segment —
    // so it is copied through at its position like a non-line. `false` everywhere when the layer
    // has no turn table, which is every layer but a `roads` tile that had `turn:lanes` in it.
    let unmergeable: Vec<bool> = match turns.as_deref() {
        Some(t) => layer.features.iter().enumerate().map(|(i, _)| {
            t.get(i).is_some_and(|lt| !lt.is_empty())
        }).collect(),
        None => vec![false; layer.features.len()],
    };

    // The split a feature's key carries, read out before the tables are borrowed mutably below.
    // Default when the layer has no carriageway table, which is every layer but `roads`.
    let split = |at: usize| -> Carriageway {
        carriageways.as_deref().and_then(|c| c.get(at).copied()).unwrap_or_default()
    };

    // Every line part, grouped by class, in feature-then-part order.
    let mut order: Vec<Class> = Vec::new();
    let mut group_of: HashMap<Class, usize> = HashMap::new();
    let mut groups: Vec<Vec<Span>> = Vec::new();
    for (index, feature) in layer.features.iter().enumerate() {
        if feature.geom_type != GEOM_LINE || unmergeable[index] {
            continue;
        }
        let class = class_of(feature, split(index));
        let at = *group_of.entry(class).or_insert_with(|| {
            order.push(class);
            groups.push(Vec::new());
            groups.len() - 1
        });
        let from = feature.parts_offset as usize;
        for part in &layer.parts[from..from + feature.part_count as usize] {
            groups[at].push((part.coord_start, part.point_count));
        }
    }

    // Chains first, so the arena can be laid down in one pass below.
    let chains: Vec<Vec<Run>> =
        groups.iter().map(|parts| chain(parts, &layer.coords)).collect();

    let mut features = Vec::with_capacity(layer.features.len() - lines + order.len());
    // The original position of each surviving feature, for remapping the side tables below.
    let mut kept: Vec<usize> = Vec::with_capacity(features.capacity());
    let mut parts: Vec<Part> = Vec::with_capacity(layer.parts.len());
    let mut coords: Vec<(i16, i16)> = Vec::with_capacity(layer.coords.len());
    let mut emitted: Vec<bool> = vec![false; order.len()];

    for (index, feature) in layer.features.iter().enumerate() {
        if feature.geom_type != GEOM_LINE || unmergeable[index] {
            // Copied through at its original position, so a mixed layer keeps its feature order and
            // a turn-masked road keeps its own geometry and its arrows.
            let from = feature.parts_offset as usize;
            let start = parts.len() as u32;
            for part in &layer.parts[from..from + feature.part_count as usize] {
                let points = points_of(&layer.coords, *part);
                parts.push(Part {
                    coord_start: coords.len() as u32,
                    point_count: points.len() as u32,
                    winding: part.winding,
                });
                coords.extend_from_slice(points);
            }
            let mut copy = *feature;
            copy.parts_offset = start;
            features.push(copy);
            kept.push(index);
            continue;
        }
        let class = class_of(feature, split(index));
        let at = group_of[&class];
        if emitted[at] {
            continue;
        }
        emitted[at] = true;
        let start = parts.len() as u32;
        for run in &chains[at] {
            let first = coords.len() as u32;
            for (i, &(coord_start, point_count)) in run.iter().enumerate() {
                let points = points_of(
                    &layer.coords,
                    Part { coord_start, point_count, winding: WINDING_OUTER },
                );
                // The joint is one point in two parts; the second copy would be a zero-length
                // segment and `geom::quantize` is no longer around to drop it.
                let points = if i == 0 { points } else { &points[1..] };
                coords.extend_from_slice(points);
            }
            parts.push(Part {
                coord_start: first,
                point_count: coords.len() as u32 - first,
                winding: WINDING_OUTER,
            });
        }
        let mut merged = *feature;
        merged.parts_offset = start;
        merged.part_count = parts.len() as u32 - start;
        features.push(merged);
        kept.push(index);
    }

    if let Some(ids) = ids {
        if !ids.is_empty() {
            *ids = kept.iter().map(|&at| ids[at]).collect();
        }
    }
    if let Some(turns) = turns {
        if !turns.is_empty() {
            *turns = kept.iter().map(|&at| turns[at].clone()).collect();
        }
    }
    if let Some(buildings) = buildings {
        if !buildings.is_empty() {
            *buildings = kept.iter().map(|&at| buildings[at]).collect();
        }
    }
    if let Some(carriageways) = carriageways {
        if !carriageways.is_empty() {
            *carriageways = kept.iter().map(|&at| carriageways[at]).collect();
        }
    }

    layer.features = features;
    layer.parts = parts;
    layer.coords = coords;
    stats.features_after = layer.features.len() as u64;
    stats.parts_after = layer.parts.len() as u64;
    stats
}

fn class_of(feature: &tilecodec::mamaps::body::Feature, carriageway: Carriageway) -> Class {
    (
        feature.kind,
        feature.kind_detail,
        feature.flags,
        feature.transit_color,
        feature.transit_ordinal,
        feature.transit_lanes,
        feature.transit_taper,
        feature.lane_count,
        feature.name_idx,
        carriageway.forward,
        carriageway.backward,
        carriageway.solid_dividers,
    )
}

pub(crate) fn points_of(coords: &[(i16, i16)], part: Part) -> &[(i16, i16)] {
    let at = part.coord_start as usize;
    &coords[at..at + part.point_count as usize]
}

/// Group `parts` into runs that each form one continuous polyline.
///
/// Head-to-tail only: a part joins the chain when its first point is the chain's last. Parts are
/// never reversed, so the result is a subset of the joins a reversing walk would find -- and it is
/// the subset that needs no argument about direction.
///
/// Chains start at parts nothing runs into, so a road is grown from its end rather than from its
/// middle; only then are the leftovers taken, which is what closes a ring road onto itself. Both
/// loops walk `parts` in order, so the output does not depend on the hash map.
fn chain(parts: &[Span], coords: &[(i16, i16)]) -> Vec<Run> {
    let ends: Vec<((i16, i16), (i16, i16))> = parts
        .iter()
        .map(|&(coord_start, point_count)| {
            let points = points_of(coords, Part { coord_start, point_count, winding: WINDING_OUTER });
            // A part with nothing in it cannot join anything; a sentinel keeps the index aligned.
            match (points.first(), points.last()) {
                (Some(a), Some(b)) => (*a, *b),
                _ => ((i16::MIN, i16::MIN), (i16::MAX, i16::MAX)),
            }
        })
        .collect();

    let mut by_start: HashMap<(i16, i16), Vec<usize>> = HashMap::new();
    for (i, (first, _)) in ends.iter().enumerate() {
        by_start.entry(*first).or_default().push(i);
    }
    // Whether anything ends where this part starts. A part that nothing runs into is a chain head.
    let mut continued = vec![false; parts.len()];
    for (_, last) in &ends {
        if let Some(hits) = by_start.get(last) {
            for &i in hits {
                continued[i] = true;
            }
        }
    }

    let mut used = vec![false; parts.len()];
    let mut out: Vec<Run> = Vec::new();
    let grow = |from: usize, used: &mut Vec<bool>, out: &mut Vec<Run>| {
        let mut run: Run = vec![parts[from]];
        used[from] = true;
        let mut tail = ends[from].1;
        while let Some(next) =
            by_start.get(&tail).and_then(|hits| hits.iter().copied().find(|&j| !used[j]))
        {
            used[next] = true;
            run.push(parts[next]);
            tail = ends[next].1;
        }
        out.push(run);
    };
    for i in 0..parts.len() {
        if !used[i] && !continued[i] {
            grow(i, &mut used, &mut out);
        }
    }
    // Whatever is left is a cycle: every part in it is continued by another, so none is a head.
    for i in 0..parts.len() {
        if !used[i] {
            grow(i, &mut used, &mut out);
        }
    }
    out
}

