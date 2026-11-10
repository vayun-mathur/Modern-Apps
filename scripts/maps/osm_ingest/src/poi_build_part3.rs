// ---- Region links: boundary assembly and per-POI containment ---------------
//
// Both halves run on the crate pool (`par::install`), and both are
// byte-neutral by construction:
//
// * `assemble_boundaries` maps each boundary independently and collects in the
//   same id order the serial loop walked, so `assembled[i]` — and therefore
//   every grid cell's candidate list — is unchanged.
// * `stamp_regions` decides each POI from that POI and read-only shared state
//   only, so splitting `pois` into chunks cannot change any POI's answer.
//
// The containment test itself is `rings::point_in_ring`'s ray cast, restricted
// to the edges that can straddle the probe's latitude ([`RingIndex`]). The
// crossing parity is an XOR over edges, so visiting a subset in a different
// order gives the same bit as long as the skipped edges could never toggle it —
// which is exactly what the band assignment guarantees.

/// One assembled boundary: its polygons, each exterior ring's absolute area
/// (smallest-wins needs no sign), its bbox, and its tagged link.
struct BoundaryPoly {
    tagged: u64,
    admin_level: u8,
    polys: Vec<rings::Polygon>,
    /// `index[p][r]` accelerates `polys[p][r]`.
    index: Vec<Vec<RingIndex>>,
    areas: Vec<f64>,
    min_lon: f64,
    min_lat: f64,
    max_lon: f64,
    max_lat: f64,
}

/// Latitude-band edge index over one closed ring, so a containment probe walks
/// only the edges whose latitude span covers the probe instead of all of them.
///
/// Admin rings run to 10^5 vertices (a coastline-hugging state or country), and
/// the serial ray cast walked every one of them for every POI in the 1° cell —
/// 80% of a California run.
///
/// Exactness: edge `i` can toggle the ray-cast parity only when
/// `(y1 > y) != (y2 > y)`, i.e. `min(y1, y2) <= y < max(y1, y2)`. `band()` is a
/// monotone non-decreasing function of `y` (an IEEE subtract and a multiply by a
/// positive constant both round monotonically, as do `floor` and the clamp), so
/// for any such `y`, `band(min) <= band(y) <= band(max)` — and the edge is filed
/// under every band in that closed range. No edge that could toggle is ever
/// skipped; the toggle test on the edges visited is the original expression,
/// operand for operand.
struct RingIndex {
    min_y: f64,
    max_y: f64,
    inv: f64,
    bands: usize,
    /// CSR: edges of band `b` are `edges[offs[b]..offs[b + 1]]`.
    offs: Vec<u32>,
    edges: Vec<u32>,
}

/// Edges per band the index aims for. Smaller is faster to probe and bigger to
/// store; four keeps the index well under the ring's own 16 bytes a vertex.
const EDGES_PER_BAND: usize = 4;
/// Upper bound on bands per ring, so a pathological ring cannot blow up memory.
const MAX_BANDS: usize = 1 << 16;

impl RingIndex {
    fn new(ring: &[Coord]) -> RingIndex {
        let n = ring.len();
        let (mut min_y, mut max_y) = (f64::INFINITY, f64::NEG_INFINITY);
        for &(_, y) in ring {
            min_y = min_y.min(y);
            max_y = max_y.max(y);
        }
        let span = max_y - min_y;
        let bands = if n < 3 || !(span > 0.0) {
            1
        } else {
            (n / EDGES_PER_BAND).clamp(1, MAX_BANDS)
        };
        let inv = if span > 0.0 { bands as f64 / span } else { 0.0 };
        let mut idx = RingIndex {
            min_y,
            max_y,
            inv,
            bands,
            offs: Vec::new(),
            edges: Vec::new(),
        };
        if n < 3 {
            idx.offs = vec![0; bands + 1];
            return idx;
        }
        // Two passes (count, then fill) so the CSR is allocated exactly once.
        let mut counts = vec![0u32; bands + 1];
        let span_of = |i: usize| -> Option<(usize, usize)> {
            let (_, y1) = ring[i];
            let (_, y2) = ring[(i + 1) % n];
            // A horizontal edge never satisfies `(y1 > y) != (y2 > y)`.
            if y1 == y2 {
                return None;
            }
            Some((idx.band(y1.min(y2)), idx.band(y1.max(y2))))
        };
        for i in 0..n {
            if let Some((lo, hi)) = span_of(i) {
                for c in &mut counts[lo..=hi] {
                    *c += 1;
                }
            }
        }
        let mut offs = vec![0u32; bands + 1];
        for b in 0..bands {
            offs[b + 1] = offs[b] + counts[b];
        }
        let mut fill = offs.clone();
        let mut edges = vec![0u32; offs[bands] as usize];
        for i in 0..n {
            if let Some((lo, hi)) = span_of(i) {
                for f in &mut fill[lo..=hi] {
                    edges[*f as usize] = i as u32;
                    *f += 1;
                }
            }
        }
        idx.offs = offs;
        idx.edges = edges;
        idx
    }

    #[inline]
    fn band(&self, y: f64) -> usize {
        let b = ((y - self.min_y) * self.inv).floor();
        // `as usize` saturates a negative to 0; clamp the top edge.
        (b as usize).min(self.bands - 1)
    }

    /// `rings::point_in_ring((x, y), ring)`, bit for bit.
    #[inline]
    fn contains(&self, (x, y): Coord, ring: &[Coord]) -> bool {
        let n = ring.len();
        if n < 3 {
            return false;
        }
        // No edge has `min <= y < max` outside this range, so no toggle.
        if !(y >= self.min_y && y < self.max_y) {
            return false;
        }
        let b = self.band(y);
        let mut inside = false;
        for &i in &self.edges[self.offs[b] as usize..self.offs[b + 1] as usize] {
            let i = i as usize;
            let (x1, y1) = ring[i];
            let (x2, y2) = ring[(i + 1) % n];
            if (y1 > y) != (y2 > y) {
                let t = (y - y1) / (y2 - y1);
                if x < x1 + t * (x2 - x1) {
                    inside = !inside;
                }
            }
        }
        inside
    }
}

/// Assemble every boundary's polygons (in boundary-id order) and index their
/// rings for [`stamp_regions`]. Boundaries that close nothing are dropped.
fn assemble_boundaries<L>(
    boundaries: &[BoundaryRel],
    outer_refs: &HashMap<i64, Vec<i64>>,
    location: &L,
) -> Vec<BoundaryPoly>
where
    L: Fn(i64) -> Option<(i32, i32)> + Sync,
{
    use rayon::prelude::*;
    let mut ordered: Vec<&BoundaryRel> = boundaries.iter().collect();
    ordered.sort_by_key(|b| b.id);
    let locate = |id: i64| -> Option<Coord> {
        location(id).map(|(lat_e7, lon_e7)| (lon_e7 as f64 * 1e-7, lat_e7 as f64 * 1e-7))
    };
    let one = |b: &BoundaryRel| -> Option<BoundaryPoly> {
        let members: Vec<MemberWay> = b
            .member_ways
            .iter()
            .map(|(w, outer)| MemberWay {
                refs: outer_refs.get(w).cloned().unwrap_or_default(),
                outer: *outer,
            })
            .collect();
        // The stats are a per-call report nobody reads on this path.
        let polys = rings::assemble(&members, &locate, &mut RingStats::default());
        if polys.is_empty() {
            return None;
        }
        let (mut min_lon, mut min_lat) = (f64::INFINITY, f64::INFINITY);
        let (mut max_lon, mut max_lat) = (f64::NEG_INFINITY, f64::NEG_INFINITY);
        let mut areas = Vec::with_capacity(polys.len());
        for poly in &polys {
            areas.push(rings::ring_area(&poly[0]).abs());
            for ring in poly {
                for &(lon, lat) in ring {
                    min_lon = min_lon.min(lon);
                    min_lat = min_lat.min(lat);
                    max_lon = max_lon.max(lon);
                    max_lat = max_lat.max(lat);
                }
            }
        }
        let index = polys
            .iter()
            .map(|poly| poly.iter().map(|r| RingIndex::new(r)).collect())
            .collect();
        Some(BoundaryPoly {
            tagged: poi_side::tag_relation(b.id),
            admin_level: b.admin_level,
            polys,
            index,
            areas,
            min_lon,
            min_lat,
            max_lon,
            max_lat,
        })
    };
    // `with_max_len(1)`: a handful of country-sized boundaries dominate, so let
    // each be stolen on its own. `collect` keeps the input (id) order.
    let out: Vec<Option<BoundaryPoly>> = crate::par::install(|| {
        ordered.par_iter().with_max_len(1).map(|b| one(b)).collect()
    });
    out.into_iter().flatten().collect()
}

/// POIs per containment work unit.
const STAMP_CHUNK: usize = 1024;

/// Stamp every POI's [`Poi::region`]: self-links are already set, a node carried
/// as a boundary's `label`/`admin_centre` member takes that boundary's tagged id,
/// otherwise the smallest-area containing admin at or above the POI's
/// place-implied cap wins. Returns the stamped count.
///
/// `pois[..from_nodes]` are nodes, which is the only slice the member-link map
/// can hit — node ids and way/relation ids share a namespace per element type,
/// and a way id equal to a label node id is a different object.
///
/// Containment runs through a coarse 1°-cell grid over boundary bboxes, so a
/// planet build tests each POI only against the boundaries whose bbox covers it
/// instead of every admin relation on earth.
fn stamp_regions(
    pois: &mut [Poi],
    from_nodes: usize,
    boundaries: &[BoundaryRel],
    assembled: &[BoundaryPoly],
) -> usize {
    use rayon::prelude::*;
    // Deterministic on double-labelling: the lowest boundary id wins.
    let mut ordered: Vec<&BoundaryRel> = boundaries.iter().collect();
    ordered.sort_by_key(|b| b.id);
    let mut labels: HashMap<i64, u64> = HashMap::new();
    for b in &ordered {
        let tagged = poi_side::tag_relation(b.id);
        for n in &b.label_nodes {
            labels.entry(*n).or_insert(tagged);
        }
    }

    let mut grid: HashMap<(i32, i32), Vec<usize>> = HashMap::new();
    for (i, b) in assembled.iter().enumerate() {
        for cx in b.min_lon.floor() as i32..=b.max_lon.floor() as i32 {
            for cy in b.min_lat.floor() as i32..=b.max_lat.floor() as i32 {
                grid.entry((cx, cy)).or_default().push(i);
            }
        }
    }

    // One POI's answer, from that POI and read-only state alone.
    let stamp = |i: usize, p: &mut Poi| -> bool {
        if p.region != poi_side::REGION_NONE {
            return true;
        }
        // Member links win over containment.
        if i < from_nodes {
            if let Some(tagged) = labels.get(&p.osm_id) {
                p.region = *tagged;
                return true;
            }
        }
        let lon = p.lon_e7 as f64 * 1e-7;
        let lat = p.lat_e7 as f64 * 1e-7;
        let Some(cands) = grid.get(&(lon.floor() as i32, lat.floor() as i32)) else {
            return false;
        };
        let mut best: Option<(f64, u64)> = None;
        for bi in cands {
            let b = &assembled[*bi];
            if b.admin_level > p.place_cap {
                continue;
            }
            for ((poly, idx), area) in b.polys.iter().zip(&b.index).zip(&b.areas) {
                // The serial loop's three conjuncts, cheapest first. All three are
                // pure, so their order cannot change whether `best` moves.
                if best.is_none_or(|(a, _)| *area < a)
                    && idx[0].contains((lon, lat), &poly[0])
                    // Holes excluded: inside an inner ring is not inside the admin.
                    && !poly[1..]
                        .iter()
                        .zip(&idx[1..])
                        .any(|(h, hi)| hi.contains((lon, lat), h))
                {
                    best = Some((*area, b.tagged));
                }
            }
        }
        match best {
            Some((_, tagged)) => {
                p.region = tagged;
                true
            }
            None => false,
        }
    };

    crate::par::install(|| {
        pois.par_chunks_mut(STAMP_CHUNK)
            .enumerate()
            .map(|(c, chunk)| {
                let base = c * STAMP_CHUNK;
                chunk
                    .iter_mut()
                    .enumerate()
                    .map(|(j, p)| stamp(base + j, p))
                    .filter(|hit| *hit)
                    .count()
            })
            .sum()
    })
}

#[cfg(test)]
mod region_index_tests {
    use super::*;

    /// A deterministic xorshift, so the property test needs no crate.
    fn rng(seed: &mut u64) -> f64 {
        *seed ^= *seed << 13;
        *seed ^= *seed >> 7;
        *seed ^= *seed << 17;
        (*seed >> 11) as f64 / (1u64 << 53) as f64
    }

    /// The banded probe must agree with `rings::point_in_ring` on every point,
    /// including probes exactly on vertex latitudes and outside the bbox.
    #[test]
    fn the_banded_probe_matches_the_full_ray_cast() {
        let mut seed = 0x9E37_79B9_7F4A_7C15u64;
        for n in [3usize, 4, 5, 17, 64, 1000, 5000] {
            // A star-shaped (very concave) closed ring around (10, 50).
            let mut ring: Vec<Coord> = (0..n)
                .map(|k| {
                    let a = k as f64 / n as f64 * std::f64::consts::TAU;
                    let r = 0.2 + rng(&mut seed);
                    (10.0 + r * a.cos(), 50.0 + r * a.sin())
                })
                .collect();
            ring.push(ring[0]);
            let idx = RingIndex::new(&ring);
            let mut probes: Vec<Coord> = (0..4000)
                .map(|_| (8.5 + 3.0 * rng(&mut seed), 48.5 + 3.0 * rng(&mut seed)))
                .collect();
            // Exactly on vertex latitudes and on vertices themselves.
            for &(vx, vy) in ring.iter().take(200) {
                probes.push((vx, vy));
                probes.push((vx - 0.01, vy));
                probes.push((10.0, vy));
            }
            for p in probes {
                assert_eq!(
                    idx.contains(p, &ring),
                    rings::point_in_ring(p, &ring),
                    "n={n} p={p:?}"
                );
            }
        }
    }

    #[test]
    fn degenerate_rings_contain_nothing() {
        let flat = vec![(0.0, 1.0), (1.0, 1.0), (2.0, 1.0), (0.0, 1.0)];
        let idx = RingIndex::new(&flat);
        assert!(!idx.contains((1.0, 1.0), &flat));
        assert_eq!(rings::point_in_ring((1.0, 1.0), &flat), false);
        let tiny = vec![(0.0, 0.0), (1.0, 1.0)];
        assert!(!RingIndex::new(&tiny).contains((0.5, 0.5), &tiny));
    }
}
