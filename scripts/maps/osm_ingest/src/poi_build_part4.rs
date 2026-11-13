// ---- poi_admin.bin: names, levels and parents of the linked boundaries -------
//
// `stamp_regions` gives each POI one boundary; this walks those boundaries up
// to the top so the app can say "City, State, Country" without a polygon. The
// table carries only boundaries some POI links to plus their ancestors, not
// every admin relation on earth: that is everything a reader can reach from a
// POI, at a fraction of the size.

/// Degrees in one cell of the boundary-candidate grid (as in `stamp_regions`).
const ADMIN_GRID_DEG: f64 = 1.0;

/// The boundary table for `poi_admin.bin`: one entry per assembled boundary that
/// a POI in `pois` links to, and per ancestor of those.
///
/// The parent of boundary B is the boundary of a LOWER `admin_level` that
/// contains a point strictly inside B ([`interior_point`]) — the deepest such
/// level, smallest area on a tie. Vertices are useless as probes: a city's
/// border runs along its county's, so a vertex is on the parent's edge, where
/// the ray cast may answer either way.
fn admin_table(
    boundaries: &[BoundaryRel],
    assembled: &[BoundaryPoly],
    pois: &[Poi],
) -> Vec<crate::poi_admin::AdminEntry> {
    use rayon::prelude::*;
    let by_tag: HashMap<u64, usize> = assembled.iter().enumerate().map(|(i, b)| (b.tagged, i)).collect();
    let names: HashMap<u64, &BoundaryRel> =
        boundaries.iter().map(|b| (poi_side::tag_relation(b.id), b)).collect();
    let grid = boundary_grid(assembled);

    // Parent of every assembled boundary, computed only for the ones reached.
    let parent_of = |i: usize| -> u64 {
        let b = &assembled[i];
        let Some(probe) = b.polys.iter().zip(&b.areas).max_by(|x, y| x.1.total_cmp(y.1)).and_then(|(p, _)| interior_point(p))
        else {
            return poi_side::REGION_NONE;
        };
        let cell = ((probe.0 / ADMIN_GRID_DEG).floor() as i32, (probe.1 / ADMIN_GRID_DEG).floor() as i32);
        let mut best: Option<(u8, f64, u64)> = None;
        for &ci in grid.get(&cell).map(Vec::as_slice).unwrap_or(&[]) {
            let c = &assembled[ci];
            if c.admin_level >= b.admin_level {
                continue;
            }
            for ((poly, idx), area) in c.polys.iter().zip(&c.index).zip(&c.areas) {
                let better = best.is_none_or(|(lvl, a, _)| c.admin_level > lvl || (c.admin_level == lvl && *area < a));
                if better && polygon_contains(poly, idx, probe) {
                    best = Some((c.admin_level, *area, c.tagged));
                }
            }
        }
        best.map_or(poi_side::REGION_NONE, |(_, _, t)| t)
    };

    // Breadth-first from the linked boundaries; each round resolves one level of parents.
    let mut parents: HashMap<u64, u64> = HashMap::new();
    let mut frontier: Vec<u64> = pois.iter().map(|p| p.region).filter(|t| by_tag.contains_key(t)).collect();
    frontier.sort_unstable();
    frontier.dedup();
    while !frontier.is_empty() {
        let resolved: Vec<(u64, u64)> = crate::par::install(|| {
            frontier.par_iter().with_max_len(1).map(|t| (*t, parent_of(by_tag[t]))).collect()
        });
        let mut next = Vec::new();
        for (t, p) in resolved {
            parents.insert(t, p);
            if p != poi_side::REGION_NONE && !parents.contains_key(&p) && by_tag.contains_key(&p) {
                next.push(p);
            }
        }
        next.sort_unstable();
        next.dedup();
        frontier = next;
    }

    let mut out: Vec<crate::poi_admin::AdminEntry> = parents
        .iter()
        .map(|(t, p)| {
            let rel = names.get(t);
            crate::poi_admin::AdminEntry {
                tagged: *t,
                parent: *p,
                admin_level: assembled[by_tag[t]].admin_level,
                name: rel.map(|r| r.name.clone()).unwrap_or_default(),
                name_en: rel.map(|r| r.name_en.clone()).unwrap_or_default(),
            }
        })
        .collect();
    out.sort_by_key(|e| e.tagged);
    out
}

/// Boundary indices per [`ADMIN_GRID_DEG`] cell of their bbox.
fn boundary_grid(assembled: &[BoundaryPoly]) -> HashMap<(i32, i32), Vec<usize>> {
    let mut grid: HashMap<(i32, i32), Vec<usize>> = HashMap::new();
    for (i, b) in assembled.iter().enumerate() {
        for cx in b.min_lon.floor() as i32..=b.max_lon.floor() as i32 {
            for cy in b.min_lat.floor() as i32..=b.max_lat.floor() as i32 {
                grid.entry((cx, cy)).or_default().push(i);
            }
        }
    }
    grid
}

/// Whether `p` is inside the polygon's exterior ring and outside all its holes.
fn polygon_contains(poly: &rings::Polygon, idx: &[RingIndex], p: Coord) -> bool {
    idx[0].contains(p, &poly[0]) && !poly[1..].iter().zip(&idx[1..]).any(|(h, hi)| hi.contains(p, h))
}

/// A point strictly inside `poly` (exterior minus holes), or `None` for a
/// degenerate polygon.
///
/// Scanline: a horizontal line between two distinct vertex latitudes near the
/// middle of the ring (so it passes through no vertex) crosses the rings an
/// even number of times; by the even-odd rule the stretches between crossings
/// 1-2, 3-4, ... are inside, holes included. The midpoint of the widest one is
/// as far from the border as that line allows.
fn interior_point(poly: &rings::Polygon) -> Option<Coord> {
    let outer = poly.first()?;
    let mut ys: Vec<f64> = outer.iter().map(|c| c.1).collect();
    ys.sort_by(f64::total_cmp);
    ys.dedup();
    if ys.len() < 2 {
        return None;
    }
    let k = (ys.len() - 1) / 2;
    let y = (ys[k] + ys[k + 1]) / 2.0;
    let mut xs: Vec<f64> = Vec::new();
    for ring in poly {
        let n = ring.len();
        for i in 0..n {
            let (x1, y1) = ring[i];
            let (x2, y2) = ring[(i + 1) % n];
            if (y1 > y) != (y2 > y) {
                xs.push(x1 + (y - y1) / (y2 - y1) * (x2 - x1));
            }
        }
    }
    xs.sort_by(f64::total_cmp);
    xs.chunks_exact(2)
        .max_by(|a, b| (a[1] - a[0]).total_cmp(&(b[1] - b[0])))
        .filter(|w| w[1] > w[0])
        .map(|w| ((w[0] + w[1]) / 2.0, y))
}

#[cfg(test)]
mod admin_tests {
    use super::*;
    use crate::poi_admin::{ADMIN_ENTRY_BYTES, ADMIN_HEADER_BYTES, ADMIN_MAGIC, ADMIN_NO_NAME};
    use crate::testpbf;

    struct Admin {
        parent: u64,
        level: u8,
        name: String,
        name_en: Option<String>,
    }

    /// Decode `poi_admin.bin` the way [`crate::poi_admin`] documents it.
    fn read_admin(dir: &Path) -> HashMap<u64, Admin> {
        let b = std::fs::read(dir.join("poi_admin.bin")).unwrap();
        assert_eq!(&b[0..4], ADMIN_MAGIC);
        let u32_at = |at: usize| u32::from_le_bytes(b[at..at + 4].try_into().unwrap());
        let u64_at = |at: usize| u64::from_le_bytes(b[at..at + 8].try_into().unwrap());
        let count = u32_at(8) as usize;
        let pool = &b[ADMIN_HEADER_BYTES + count * ADMIN_ENTRY_BYTES..];
        let cstr = |off: u32| {
            let s = &pool[off as usize..];
            String::from_utf8(s[..s.iter().position(|c| *c == 0).unwrap()].to_vec()).unwrap()
        };
        (0..count)
            .map(|i| {
                let at = ADMIN_HEADER_BYTES + i * ADMIN_ENTRY_BYTES;
                let en = u32_at(at + 20);
                (
                    u64_at(at),
                    Admin {
                        parent: u64_at(at + 8),
                        level: b[at + 24],
                        name: cstr(u32_at(at + 16)),
                        name_en: (en != ADMIN_NO_NAME).then(|| cstr(en)),
                    },
                )
            })
            .collect()
    }

    fn tagged(id: i64) -> u64 {
        poi_side::tag_relation(id)
    }

    /// Country > state > city on a synthetic PBF: each POI links to its smallest
    /// boundary, the table walks every linked boundary to the top, and a
    /// boundary no POI reaches is left out.
    #[test]
    fn the_admin_table_walks_linked_boundaries_to_the_top() {
        let (pbf_path, dir) = testpbf::write_admin_sample("poi_admin");
        let stats = build(
            &pbf_path,
            &dir.join("pois.geojsonseq"),
            &dir.join("poi_names.bin"),
            &dir.join("poi_index.bin"),
            &dir.join("poi_attrs.bin"),
            &dir.join("poi_spatial.bin"),
            &dir.join("poi_name_index.bin"),
            &dir.join("poi_region.bin"),
        )
        .unwrap();
        let admin = read_admin(&dir);
        assert_eq!(stats.admin_entries, 3);
        assert_eq!(admin.len(), 3, "country, state, city; the unreached town is not written");

        let city = &admin[&tagged(testpbf::ADMIN_CITY_ID)];
        assert_eq!((city.level, city.name.as_str()), (8, "Testcity"));
        assert_eq!(city.parent, tagged(testpbf::ADMIN_STATE_ID));
        assert_eq!(city.name_en, None);

        let state = &admin[&tagged(testpbf::ADMIN_STATE_ID)];
        assert_eq!((state.level, state.name.as_str()), (4, "Teststate"));
        assert_eq!(state.parent, tagged(testpbf::ADMIN_COUNTRY_ID));

        let country = &admin[&tagged(testpbf::ADMIN_COUNTRY_ID)];
        assert_eq!((country.level, country.name.as_str()), (2, "Testlandia"));
        assert_eq!(country.name_en.as_deref(), Some("Testland"));
        assert_eq!(country.parent, 0, "the country is the top");
        assert!(!admin.contains_key(&tagged(testpbf::ADMIN_GHOST_ID)));
    }

    #[test]
    fn the_interior_point_avoids_holes_and_borders() {
        // A 4x4 square with a 2x2 hole in its middle: the probe must land in the
        // ring around the hole, never in the hole or on an edge.
        let outer = vec![(0.0, 0.0), (4.0, 0.0), (4.0, 4.0), (0.0, 4.0), (0.0, 0.0)];
        let hole = vec![(1.0, 1.0), (3.0, 1.0), (3.0, 3.0), (1.0, 3.0), (1.0, 1.0)];
        let poly: rings::Polygon = vec![outer.clone(), hole.clone()];
        let (x, y) = interior_point(&poly).unwrap();
        assert!(rings::point_in_ring((x, y), &outer));
        assert!(!rings::point_in_ring((x, y), &hole));
        assert!(x > 0.0 && x < 4.0 && y > 0.0 && y < 4.0);
        assert!(interior_point(&vec![vec![(0.0, 0.0), (1.0, 0.0), (0.0, 0.0)]]).is_none());
    }
}
