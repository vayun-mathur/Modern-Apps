use super::geom::{bounds, box_area, boxes_overlap, point_within_ring, ring_area2, rings_overlap};

/// Regroup rings into polygons earcut can actually express — see the module docs for why.
///
/// Each returned group is `[exterior, hole, hole, ..]` as indices into `rings`.
pub(crate) fn polygons(rings: &[&[(i32, i32)]]) -> Vec<Vec<usize>> {
    let count = rings.len();
    let boxes: Vec<(i32, i32, i32, i32)> = rings.iter().map(|r| bounds(r)).collect();

    // Enclosing rings per ring, each with the vote that decided it. A majority vote rather
    // than one probe vertex, so a ring that poked a few vertices outside its container
    // during clipping is still recognised as being inside it — and the vote doubles as the
    // straddle test below, at no extra cost. The boundary counts as inside: a hole that
    // shares an edge with its exterior is touching it, not straddling it, and reading those
    // vertices as outside discarded real islands.
    let mut enclosing: Vec<Vec<(usize, usize)>> = vec![Vec::new(); count];
    for j in 0..count {
        for i in 0..count {
            // Only a ring with a larger box can enclose a smaller one. That prunes the
            // hole-against-hole half of this cheaply; every hole is still swept against the
            // exterior, which is what the cost actually goes on.
            if i == j
                || !boxes_overlap(boxes[i], boxes[j])
                || box_area(boxes[i]) < box_area(boxes[j])
            {
                continue;
            }
            let votes = rings[j]
                .iter()
                .filter(|&&(x, y)| point_within_ring(x, y, rings[i]))
                .count();
            if votes * 2 > rings[j].len() {
                enclosing[j].push((i, votes));
            }
        }
    }
    let depth: Vec<usize> = enclosing.iter().map(|e| e.len()).collect();

    let mut groups = Vec::new();
    for exterior in 0..count {
        if depth[exterior] % 2 != 0 {
            continue;
        }

        let mut holes: Vec<(usize, usize)> = Vec::new();
        for j in 0..count {
            if depth[j] % 2 == 0 {
                continue;
            }
            // The most deeply nested enclosing ring is the one this is a hole of.
            let mut closest: Option<(usize, usize)> = None;
            for &(ring, votes) in &enclosing[j] {
                if closest.is_none_or(|(held, _)| depth[ring] > depth[held]) {
                    closest = Some((ring, votes));
                }
            }
            if closest.is_some_and(|(ring, _)| ring == exterior) {
                holes.push((j, closest.unwrap().1));
            }
        }
        // Largest first, so where a conflict forces a hole out it is the smaller island
        // that gets filled in. Cached keys: the shoelace walk is O(ring), and coastline
        // holes run to a thousand vertices.
        holes.sort_by_cached_key(|&(j, _)| std::cmp::Reverse(ring_area2(rings[j])));

        let mut group = vec![exterior];
        for (j, votes) in holes {
            // A hole that straddles its exterior, or overlaps a hole already taken, makes
            // the bridged ring self-intersecting, and earcut answers that with slivers
            // spanning the whole polygon. Losing the hole costs far less area.
            //
            // Both tests are vertex votes rather than exact edge-crossing tests, an order of
            // magnitude cheaper. The cost of that is measurable and worth knowing: across the
            // archive's low zooms this discards 773 holes, each one an island or lake filled
            // in solid, and 301 of those straddle by a single vertex out of ten to forty-five.
            //
            // That distribution invites tolerating a one-vertex straddle. It was tried and it
            // is worse: summed surplus over 19372 polygon groups rises from 0.399 to 0.441,
            // because those really do cross and their bridged rings really do self-intersect.
            // Recovering the islands means clipping the offending vertex back onto the
            // exterior, which keeps the hole without the crossing — not relaxing this test.
            //
            // A hole that crosses out and back between two consecutive vertices is missed
            // entirely and simply tolerated.
            let straddles = votes != rings[j].len();
            let overlaps = group[1..]
                .iter()
                .any(|&k| boxes_overlap(boxes[j], boxes[k]) && rings_overlap(rings[j], rings[k]));
            if !straddles && !overlaps {
                group.push(j);
            }
        }
        groups.push(group);
    }
    groups
}
