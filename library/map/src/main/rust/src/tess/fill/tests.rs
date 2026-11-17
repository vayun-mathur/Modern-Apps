use super::*;

/// Total unsigned area of the emitted triangles, in tile-normalised units.
fn covered_area(vertices: &[f32], indices: &[u32]) -> f64 {
    let mut total = 0.0;
    for t in indices.chunks_exact(3) {
        let p = |i: u32| -> (f64, f64) {
            let at = i as usize * FLOATS_PER_VERTEX;
            (vertices[at] as f64, vertices[at + 1] as f64)
        };
        let (ax, ay) = p(t[0]);
        let (bx, by) = p(t[1]);
        let (cx, cy) = p(t[2]);
        total += ((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)).abs() / 2.0;
    }
    total
}

fn square(size: i32) -> Vec<(i32, i32)> {
    // Closed, as decode_polygons returns rings.
    vec![(0, 0), (size, 0), (size, size), (0, size), (0, 0)]
}

#[test]
fn a_closed_ring_loses_its_repeated_vertex() {
    let mut v = Vec::new();
    let mut idx = Vec::new();
    tessellate(&[square(4096)], 4096, false, &mut v, &mut idx);
    assert_eq!(
        v.len() / FLOATS_PER_VERTEX,
        4,
        "the closing vertex is not a vertex"
    );
    assert_eq!(idx.len(), 6);
    // The whole tile, so area 1 in tile-normalised units.
    assert!((covered_area(&v, &idx) - 1.0).abs() < 1e-6);
}

#[test]
fn positions_are_tile_normalised() {
    let mut v = Vec::new();
    tessellate(&[square(4096)], 4096, false, &mut v, &mut Vec::new());
    for f in &v {
        assert!(*f >= 0.0 && *f <= 1.0, "{f} is not tile-normalised");
    }
}

/// The area a correct tessellation must cover: the even-odd region of every ring.
///
/// Not `exterior - sum(holes)`. The published archive contains holes that overlap each
/// other and lakes nested inside continent holes, so subtracting each ring's area
/// independently double-counts both — on the z0 ocean that reads 0.7096 against a true
/// 0.7135. Even-odd and non-zero winding agree on this figure, and it is what a
/// rasteriser would paint, so integrate it directly instead of approximating it.
fn expected_area(rings: &[Vec<(i32, i32)>], extent: u32) -> f64 {
    let mut edges: Vec<((i32, i32), (i32, i32))> = Vec::new();
    for ring in rings {
        let n = open_length(ring);
        if n < 3 {
            continue;
        }
        for i in 0..n {
            let (a, b) = (ring[i], ring[(i + 1) % n]);
            // Horizontal edges cross no scanline.
            if a.1 != b.1 {
                edges.push((a, b));
            }
        }
    }
    if edges.is_empty() {
        return 0.0;
    }
    let lo = edges.iter().map(|e| e.0 .1.min(e.1 .1)).min().unwrap();
    let hi = edges.iter().map(|e| e.0 .1.max(e.1 .1)).max().unwrap();

    // Coordinates are integers, so which edges cross a scanline can only change at an
    // integer y. Sampling each unit band at its midpoint therefore integrates that band
    // exactly, bar the fractional-y crossings the overlapping rings introduce; those
    // move the total by under 1e-7 of the tile, checked against an 8x finer sweep.
    let mut crossings: Vec<f64> = Vec::new();
    let mut total = 0.0f64;
    for row in lo..hi {
        let y = row as f64 + 0.5;
        crossings.clear();
        for &((x1, y1), (x2, y2)) in &edges {
            if (y1 as f64 > y) != (y2 as f64 > y) {
                let t = (y - y1 as f64) / (y2 as f64 - y1 as f64);
                crossings.push(x1 as f64 + t * (x2 as f64 - x1 as f64));
            }
        }
        crossings.sort_by(|a, b| a.partial_cmp(b).unwrap());
        let mut i = 0;
        while i + 1 < crossings.len() {
            total += crossings[i + 1] - crossings[i];
            i += 2;
        }
    }
    let scale = 1.0 / extent as f64;
    total * scale * scale
}

/// Every polygon of every layer of the published tile, as the renderer sees them:
/// `(layer name, feature id, polygons as rings of tile-local points)`.
type RealPolygons = Vec<(String, u32, Vec<Vec<(i32, i32)>>)>;
fn real_polygons() -> RealPolygons {
    const REAL_TILE: &[u8] = include_bytes!("../../../tests/fixtures/v5ca_z11_tile.mvt");
    let tile = tilecodec::mvt::Tile::decode(REAL_TILE).expect("the published tile decodes");
    let mut out = Vec::new();
    for layer in &tile.layers {
        for feature in &layer.features {
            if feature.geom_type != tilecodec::mvt::GeomType::Polygon {
                continue;
            }
            let Some(polygons) = tilecodec::mvt::decode_polygons(&feature.geometry) else {
                continue;
            };
            for rings in polygons {
                out.push((layer.name.clone(), layer.extent, rings));
            }
        }
    }
    out
}

/// The published **z0** tile, whose `water` layer holds the ocean as one polygon with 105
/// holes — one per continent and major island.
///
/// This is here because synthetic cases are not hard enough. A grid of a hundred square
/// holes tessellates exactly; this does not, and it is what the app actually draws. Real
/// coastlines are clipped at the tile edge, contain long collinear runs, and have holes
/// that touch the exterior ring.
const REAL_Z0_TILE: &[u8] = include_bytes!("../../../tests/fixtures/v4_z0_tile.mvt");

/// The z0 ocean, which is the hardest polygon in the archive and the one that exposed
/// how invalid the published geometry is.
///
/// Synthetic cases are not hard enough: a grid of a hundred square holes tessellates
/// exactly, and this does not. Real coastlines are clipped at the tile edge, contain
/// long collinear runs, and have holes that touch the exterior.
///
/// This once covered 0.8004 of the tile against a true 0.7135, and on device showed a
/// fan of long thin slivers radiating from the Arctic corner. Two things were ruled out
/// the expensive way and are worth recording, because both look like the obvious
/// culprit and neither is:
///
/// * **The earcut port matches upstream.** A literal transliteration of upstream v2.2.4,
///   built independently over object references rather than an index arena, produces
///   byte-identical output on this polygon — the same 5532 triangles in the same order.
///   Upstream earcut is equally wrong here. That agreement holds only with the undoubled
///   `dy` in earcut's `middle_inside`; without it the port emits 5540 triangles for
///   0.8060, so it did once diverge and this comparison is evidence for that fix rather
///   than grounds to undo it.
/// * **A vetted crate would not have helped.** Upstream's own later rework of the
///   fallback paths, `compareXYSlope` hole ordering included, still gives 0.7694 on
///   this input. `compareXYSlope` in isolation changes nothing at all, because it only
///   engages when two holes share a leftmost *point* and none here do.
///
/// What was actually wrong was the input, and [`super::grouping::polygons`] is the fix. See the
/// module docs.
#[test]
fn the_published_ocean_polygon_conserves_area() {
    // The ocean is drawn over `earth`, so any area it covers beyond its true extent is a
    // continent painted blue. At its worst this covered 0.981 of the tile and the map had
    // no coastlines at all.
    let tile = tilecodec::mvt::Tile::decode(REAL_Z0_TILE).expect("the published z0 tile");
    let water = tile.layer("water").expect("a water layer");

    let mut checked = 0;
    for feature in &water.features {
        if feature.geom_type != tilecodec::mvt::GeomType::Polygon {
            continue;
        }
        let Some(polygons) = tilecodec::mvt::decode_polygons(&feature.geometry) else {
            continue;
        };
        for rings in &polygons {
            if rings.len() < 50 {
                // Only the ocean group is interesting; the lakes are single rings and were
                // never wrong.
                continue;
            }
            checked += 1;
            let mut v = Vec::new();
            let mut idx = Vec::new();
            tessellate(rings, water.extent, false, &mut v, &mut idx);
            let want = expected_area(rings, water.extent);
            let got = covered_area(&v, &idx);

            assert!(
                (got - want).abs() <= want * 0.02,
                "the ocean covers {got:.4} of the tile but is only {want:.4} \
                 ({} rings) — that surplus is land painted over. {} triangles emitted",
                rings.len(),
                idx.len() / 3,
            );
        }
    }
    assert_eq!(
        checked, 1,
        "the z0 ocean polygon must be present and checked"
    );
}

#[test]
fn many_holes_are_all_cut_out() {
    // The bug that made the whole basemap wrong. The published ocean polygon has 105
    // holes — one per continent and island — and earcut found and grouped every one of
    // them correctly, then tessellated straight over them: expected 0.711 of the tile,
    // produced 0.981. Some tiles were 81% over. So the ocean painted across the land, and
    // the only layer that looked right was the one that is drawn as lines.
    //
    // Two holes pass, which is why the existing tests missed it. This uses a hundred.
    const SIZE: i32 = 4096;
    const GRID: i32 = 10;
    const CELL: i32 = SIZE / GRID;
    const HOLE: i32 = CELL / 2;

    let mut rings = vec![vec![(0, 0), (SIZE, 0), (SIZE, SIZE), (0, SIZE), (0, 0)]];
    for row in 0..GRID {
        for column in 0..GRID {
            let x = column * CELL + CELL / 4;
            let y = row * CELL + CELL / 4;
            // Wound opposite to the exterior, as a hole must be.
            rings.push(vec![
                (x, y),
                (x, y + HOLE),
                (x + HOLE, y + HOLE),
                (x + HOLE, y),
                (x, y),
            ]);
        }
    }
    assert_eq!(
        rings.len(),
        1 + (GRID * GRID) as usize,
        "one exterior and a hundred holes"
    );

    let mut v = Vec::new();
    let mut idx = Vec::new();
    tessellate(&rings, SIZE as u32, false, &mut v, &mut idx);

    let want = expected_area(&rings, SIZE as u32);
    let got = covered_area(&v, &idx);
    assert!(
        (got - want).abs() <= want * 0.02 + 1e-6,
        "covered {got:.4} but the polygon is only {want:.4} — {} holes were not cut out",
        rings.len() - 1,
    );
}

#[test]
fn a_lake_inside_a_continent_hole_is_filled_again() {
    // Nesting depth, not ring order, decides what is a hole. The z0 ocean carries 13 of
    // these: ocean, a continent cut out of it, and a lake cut back into the continent.
    // Earcut cannot express a hole inside a hole, so the lake has to become its own
    // exterior — otherwise it is left dry.
    let ocean = vec![(0, 0), (1000, 0), (1000, 1000), (0, 1000), (0, 0)];
    let continent = vec![(200, 200), (200, 800), (800, 800), (800, 200), (200, 200)];
    let lake = vec![(400, 400), (400, 600), (600, 600), (600, 400), (400, 400)];
    let rings = vec![ocean, continent, lake];

    let mut v = Vec::new();
    let mut idx = Vec::new();
    tessellate(&rings, 1000, false, &mut v, &mut idx);

    // The tile minus the continent, plus the lake back: 1 - 0.36 + 0.04.
    let want = expected_area(&rings, 1000);
    assert!((want - 0.68).abs() < 1e-6, "the even-odd region is {want}");
    assert!(
        (covered_area(&v, &idx) - want).abs() <= want * 0.02,
        "covered {} against {want}",
        covered_area(&v, &idx),
    );
}

#[test]
fn two_overlapping_holes_do_not_leave_a_surplus() {
    // 40 of the z0 ocean's holes overlap another one. Bridging both into the exterior
    // makes the ring self-intersecting, and earcut answers that by fanning slivers
    // across the whole polygon — far more area than dropping one hole costs. So exactly
    // one of an overlapping pair survives, and the fill is generous by the other.
    let outer = vec![(0, 0), (1000, 0), (1000, 1000), (0, 1000), (0, 0)];
    let a = vec![(200, 200), (200, 600), (600, 600), (600, 200), (200, 200)];
    let b = vec![(400, 400), (400, 800), (800, 800), (800, 400), (400, 400)];

    let mut v = Vec::new();
    let mut idx = Vec::new();
    tessellate(&[outer, a, b], 1000, false, &mut v, &mut idx);

    // One 400x400 hole of a 1000x1000 tile cut out, and only one.
    assert!(
        (covered_area(&v, &idx) - 0.84).abs() < 1e-6,
        "expected exactly one of the two holes to survive, covered {}",
        covered_area(&v, &idx),
    );
    // The dropped hole's vertices are not emitted, so they cannot sit unreferenced in
    // the buffer: the outer ring plus one hole, four vertices each.
    assert_eq!(v.len() / FLOATS_PER_VERTEX, 8);
}

#[test]
fn a_hole_touching_its_exterior_is_still_cut_out() {
    // Real coastline holes share an edge with their exterior. `point_in_ring` is an
    // even-odd crossing test and answers inconsistently for a point lying exactly on the
    // boundary, so without treating the boundary as inside these read as straddling and
    // the whole hole was discarded — an island filled in solid for no reason. Eleven of
    // them across the archive's low zooms.
    let outer = vec![(0, 0), (1000, 0), (1000, 1000), (0, 1000), (0, 0)];
    // Left and right edges of the hole sit exactly on the exterior's left and right edges.
    let spanning = vec![(0, 400), (1000, 400), (1000, 600), (0, 600), (0, 400)];
    let rings = vec![outer, spanning];

    let mut v = Vec::new();
    let mut idx = Vec::new();
    tessellate(&rings, 1000, false, &mut v, &mut idx);

    let want = expected_area(&rings, 1000);
    assert!((want - 0.8).abs() < 1e-6, "the even-odd region is {want}");
    assert!(
        (covered_area(&v, &idx) - want).abs() < 1e-6,
        "the hole must still be cut out: covered {} against {want}",
        covered_area(&v, &idx),
    );
}

#[test]
fn a_hole_crossing_its_exterior_by_one_vertex_is_still_dropped() {
    // Guards the STRICTNESS of the straddle test, which nothing else does: relaxing it to
    // tolerate one outside vertex leaves every other test in this module green.
    //
    // One vertex here sits genuinely outside the exterior — outside, not on the edge, so
    // `on_boundary` cannot rescue it. Four of five are inside, so it is still classified as
    // this exterior's hole; only the strictness decides its fate.
    //
    // Dropping it fills in an island, which reads as the wrong trade until measured:
    // tolerating single-vertex straddles raises summed surplus over 19372 polygon groups
    // from 0.399 to 0.441, because these really cross and their bridged rings really
    // self-intersect. The strict form is deliberate, and this pins it.
    let outer = vec![(0, 0), (1000, 0), (1000, 1000), (0, 1000), (0, 0)];
    let crossing = vec![
        (400, 300),
        (400, 700),
        (700, 700),
        (1010, 500),
        (700, 300),
        (400, 300),
    ];

    let mut v = Vec::new();
    let mut idx = Vec::new();
    tessellate(&[outer, crossing], 1000, false, &mut v, &mut idx);

    // Dropped, so the fill is exactly the exterior. Keeping it covers 0.8965 instead.
    assert!(
        (covered_area(&v, &idx) - 1.0).abs() < 1e-6,
        "the crossing hole must be dropped, not bridged: covered {}",
        covered_area(&v, &idx),
    );
}

#[test]
fn tessellation_conserves_area_on_the_published_tile() {
    // The invariant that catches a bad triangulation. A fill whose triangles do not sum
    // to the polygon's own area is drawing the wrong shape — which on screen looks like
    // land that does not line up with the coastline and borders drawn over it, with
    // straight-edged wedges where the ear clipping went wrong.
    let polygons = real_polygons();
    assert!(!polygons.is_empty(), "the fixture must contain polygons");
    for (name, extent, rings) in &polygons {
        let mut v = Vec::new();
        let mut idx = Vec::new();
        tessellate(rings, *extent, false, &mut v, &mut idx);
        let want = expected_area(rings, *extent);
        let got = covered_area(&v, &idx);
        // Generous: exact only up to f32 vertex precision.
        assert!(
            (got - want).abs() <= want * 0.02 + 1e-6,
            "{name}: covered {got:.6} but the polygon is {want:.6} \
             ({} rings, {} triangles)",
            rings.len(),
            idx.len() / 3,
        );
    }
}

#[test]
fn tessellation_conserves_area_on_a_concave_polygon_with_holes() {
    // A comb-shaped exterior with two holes: the case simple squares never exercise and
    // real coastlines are full of.
    let mut outer = vec![(0, 0)];
    for i in 0..8 {
        let x = i * 500;
        outer.push((x, 3000));
        outer.push((x + 250, 3000));
        outer.push((x + 250, 500));
        outer.push((x + 500, 500));
    }
    outer.push((4000, 0));
    outer.push((0, 0));
    let hole_a = vec![(100, 100), (100, 300), (300, 300), (300, 100), (100, 100)];
    let hole_b = vec![
        (3600, 100),
        (3600, 400),
        (3900, 400),
        (3900, 100),
        (3600, 100),
    ];
    let rings = vec![outer, hole_a, hole_b];

    let mut v = Vec::new();
    let mut idx = Vec::new();
    tessellate(&rings, 4096, false, &mut v, &mut idx);
    let want = expected_area(&rings, 4096);
    let got = covered_area(&v, &idx);
    assert!(
        (got - want).abs() <= want * 0.02 + 1e-6,
        "covered {got:.6} but the polygon is {want:.6}",
    );
}

#[test]
fn a_hole_is_excluded() {
    let outer = square(100);
    let hole = vec![(40, 40), (40, 60), (60, 60), (60, 40), (40, 40)];
    let mut v = Vec::new();
    let mut idx = Vec::new();
    tessellate(&[outer, hole], 100, false, &mut v, &mut idx);
    // 100x100 minus 20x20, over an extent of 100. Vertices are `f32`, so 0.4 and
    // 0.6 are not exact and the tolerance is set by that rather than by the maths.
    assert!(
        (covered_area(&v, &idx) - (1.0 - 0.04)).abs() < 1e-6,
        "covered {}",
        covered_area(&v, &idx),
    );
}

#[test]
fn a_degenerate_exterior_drops_the_whole_polygon() {
    // A hole with nothing around it renders as solid fill in the layer's colour,
    // which is worse than the feature being absent.
    let mut v = Vec::new();
    let mut idx = Vec::new();
    tessellate(
        &[
            vec![(0, 0), (5, 0), (0, 0)],
            vec![(40, 40), (40, 60), (60, 60), (60, 40), (40, 40)],
        ],
        100,
        false,
        &mut v,
        &mut idx,
    );
    assert!(v.is_empty());
    assert!(idx.is_empty());
}

#[test]
fn a_degenerate_hole_leaves_the_exterior() {
    let mut v = Vec::new();
    let mut idx = Vec::new();
    tessellate(
        &[square(100), vec![(5, 5), (6, 5)]],
        100,
        false,
        &mut v,
        &mut idx,
    );
    assert!(
        (covered_area(&v, &idx) - 1.0).abs() < 1e-6,
        "the exterior still fills"
    );
}
