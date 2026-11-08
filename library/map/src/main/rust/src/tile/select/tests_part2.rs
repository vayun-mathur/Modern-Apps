use super::*;
use crate::camera::Camera;

fn camera(lon: f64, lat: f64, zoom: f64, w: f32, h: f32) -> Camera {
    Camera {
        center_lon: lon,
        center_lat: lat,
        zoom,
        width_dp: w,
        height_dp: h,
        density: 1.0,
        bearing_deg: 0.0,
        pitch_deg: 0.0,
        pivot_dp: 0.0,
        time_seconds: 0.0,
        globe: false,
        moon: false,
    }
}

#[test]
fn ancestors_are_included_and_ordered_under_their_descendants() {
    // Without ancestors the map goes blank on every zoom step: z changes, every resident
    // tile is dropped, and nothing draws until the new level arrives.
    let c = camera(-122.4194, 37.7749, 14.0, 411.0, 891.0);
    let exact = visible(&c, 0, 16);
    let all = resident_set(&c, 0, 16);
    assert!(all.len() > exact.len(), "ancestors must be added");

    // Every exact tile is still present.
    for tile in &exact {
        assert!(all.contains(tile), "{tile:?} was dropped");
    }
    // Every ancestor really is one: the child's coordinates shifted right.
    for tile in &all {
        if exact.contains(tile) {
            continue;
        }
        let covers = exact.iter().any(|child| {
            child.z > tile.z && {
                let shift = child.z - tile.z;
                child.x >> shift == tile.x && child.y >> shift == tile.y
            }
        });
        assert!(covers, "{tile:?} is not an ancestor of any visible tile");
    }
    // Coarsest first: the renderer draws in this order, so a parent must precede its
    // child or a stale parent lands on top of the sharp child.
    let zooms: Vec<u8> = all.iter().map(|t| t.z).collect();
    let mut sorted = zooms.clone();
    sorted.sort_unstable();
    assert_eq!(zooms, sorted, "ancestors must come before descendants");
}

#[test]
fn ancestors_never_go_below_the_archives_minimum_zoom() {
    let c = camera(0.0, 0.0, 6.0, 411.0, 891.0);
    for tile in resident_set(&c, 5, 16) {
        assert!(tile.z >= 5, "{tile:?} is below the archive's min zoom");
    }
}

#[test]
fn ancestors_are_distinct_and_bounded() {
    let c = camera(-122.4194, 37.7749, 16.0, 411.0, 891.0);
    let all = resident_set(&c, 0, 16);
    let mut keys: Vec<u64> = all.iter().map(|t| t.key()).collect();
    let count = keys.len();
    keys.sort_unstable();
    keys.dedup();
    assert_eq!(count, keys.len(), "a tile is listed twice");
    // Residency is GPU memory, so this must stay proportional to the viewport rather
    // than growing with depth.
    assert!(
        all.len() < bound(&c) * 3,
        "{} tiles is too many to keep resident",
        all.len()
    );
}

#[test]
fn an_unmeasured_viewport_asks_for_nothing_even_with_ancestors() {
    // The viewport is null until Compose measures it. Asking for ancestors of nothing
    // must stay nothing rather than falling back to the whole world.
    let unmeasured = camera(0.0, 0.0, 5.0, 0.0, 0.0);
    assert!(visible(&unmeasured, 0, 16).is_empty());
    assert!(resident_set(&unmeasured, 0, 16).is_empty());
}

#[test]
fn every_tile_is_distinct() {
    let tiles = visible(&camera(2.3522, 48.8566, 12.0, 411.0, 891.0), 0, 16);
    let mut keys: Vec<u64> = tiles.iter().map(|t| t.key()).collect();
    let count = keys.len();
    keys.sort_unstable();
    keys.dedup();
    assert_eq!(count, keys.len(), "no tile is requested twice");
}

#[test]
fn tile_keys_do_not_collide_across_the_archives_zoom_range() {
    let mut seen = std::collections::HashSet::new();
    for z in 0..=16u8 {
        let n = 1u32 << z;
        let mut coords = vec![0, n / 2, n - 1];
        coords.dedup();
        for &x in &coords {
            for &y in &coords {
                assert!(
                    seen.insert(TileId { z, x, y }.key()),
                    "z{z}/{x}/{y} collided"
                );
            }
        }
    }
}

#[test]
fn a_key_round_trips() {
    // `from_key` is what lets the renderer recover a tile's position from its residency map
    // key alone, so the two must agree at the edges of the bit packing as well as the middle.
    for z in 0..=22u8 {
        let n = 1u32 << z;
        for &(x, y) in &[(0, 0), (n - 1, n - 1), (n / 2, n / 3)] {
            let tile = TileId { z, x, y };
            assert_eq!(TileId::from_key(tile.key()), tile);
        }
    }
}

#[test]
fn a_tilted_viewport_pulls_in_the_trapezoid_toward_the_horizon() {
    // Under tilt the top of the screen recedes toward the horizon, so the covered ground is a
    // trapezoid larger than the flat viewport box. A pitched camera must ask for strictly more
    // tiles than the same level camera, and every level-camera tile must still be present.
    let level = camera(-122.4194, 37.7749, 14.0, 411.0, 891.0);
    let tilted = Camera {
        pitch_deg: 55.0,
        pivot_dp: 0.0,
        ..level
    };
    let flat = visible(&level, 0, 16);
    let pitched = visible(&tilted, 0, 16);
    assert!(
        pitched.len() > flat.len(),
        "a tilted camera covers the receding trapezoid: {} vs {}",
        pitched.len(),
        flat.len(),
    );
    for tile in &flat {
        assert!(pitched.contains(tile), "{tile:?} was dropped by the tilt");
    }
    assert!(
        pitched.len() <= bound(&tilted),
        "the bound must grow with the tilt too"
    );
}

#[test]
fn every_on_screen_ground_point_of_a_tilted_view_lands_in_a_selected_tile() {
    // The property that matters: whatever the tilt (and bearing), the ground under each screen
    // corner belongs to a tile that was asked for. Uses the same tilt-aware unproject the
    // renderer draws with, so selection and drawing agree.
    let c = Camera {
        pitch_deg: 50.0,
        pivot_dp: 0.0,
        bearing_deg: 30.0,
        ..camera(2.3522, 48.8566, 13.0, 411.0, 891.0)
    };
    let tiles = visible(&c, 0, 16);
    let span = c.tile_span_dp(13);
    for &(sx, sy) in &[
        (0.0, 0.0),
        (411.0, 0.0),
        (411.0, 891.0),
        (0.0, 891.0),
        (205.0, 20.0),
    ] {
        let ground = c
            .screen_to_world(sx, sy)
            .expect("below the horizon under the cap");
        let tx = (ground.x / span).floor() as i64;
        let ty = (ground.y / span).floor() as i64;
        assert!(
            tiles.iter().any(|t| t.x as i64 == tx && t.y as i64 == ty),
            "nothing covers the on-screen point at ({sx},{sy}) -> tile 13/{tx}/{ty}",
        );
    }
}

#[test]
fn a_zero_pitch_selection_is_unchanged() {
    // The regression guard: adding tilt coverage must not perturb the flat/phone path. At pitch
    // 0 the coverage box is exactly the viewport bounds, so the selection is what it always was.
    let c = camera(-122.4194, 37.7749, 14.0, 411.0, 891.0);
    let turned = Camera {
        bearing_deg: 37.0,
        ..c
    };
    assert_eq!(
        visible(&c, 0, 16),
        visible(
            &Camera {
                pitch_deg: 0.0,
                pivot_dp: 0.0,
                ..c
            },
            0,
            16
        )
    );
    assert_eq!(
        visible(&turned, 0, 16),
        visible(
            &Camera {
                pitch_deg: 0.0,
                pivot_dp: 0.0,
                ..turned
            },
            0,
            16
        )
    );
}

#[test]
fn a_tile_descends_from_its_ancestors_and_from_itself() {
    let tile = TileId {
        z: 12,
        x: 2048,
        y: 1362,
    };
    assert!(tile.descends_from(&tile, 2), "a tile stands in for itself");
    assert!(tile.descends_from(
        &TileId {
            z: 11,
            x: 1024,
            y: 681
        },
        2
    ));
    assert!(tile.descends_from(
        &TileId {
            z: 10,
            x: 512,
            y: 340
        },
        2
    ));
}

#[test]
fn descent_stops_at_the_depth_and_never_goes_upwards() {
    let tile = TileId {
        z: 12,
        x: 2048,
        y: 1362,
    };
    // Three levels up is a real ancestor, but past the depth we are willing to hold.
    assert!(!tile.descends_from(
        &TileId {
            z: 9,
            x: 256,
            y: 170
        },
        2
    ));
    // An ancestor does not descend from its own descendant.
    assert!(!TileId {
        z: 10,
        x: 512,
        y: 340
    }
    .descends_from(&tile, 2));
    // A neighbour at the same zoom shares no ground.
    assert!(!tile.descends_from(
        &TileId {
            z: 11,
            x: 1025,
            y: 681
        },
        2
    ));
}

#[test]
fn a_resident_descendant_stands_in_for_a_visible_tile() {
    // The zoom-out case: the camera has pulled back to z10 and those tiles are still in
    // flight, but the z12 tiles from a moment ago are resident and cover the same ground.
    let visible = vec![TileId {
        z: 10,
        x: 512,
        y: 340,
    }];
    assert!(stands_in_for_visible(
        TileId {
            z: 12,
            x: 2048,
            y: 1362
        }
        .key(),
        &visible,
        2
    ));
    assert!(stands_in_for_visible(
        TileId {
            z: 11,
            x: 1024,
            y: 681
        }
        .key(),
        &visible,
        2
    ));
    // Deeper than we hold, and elsewhere in the world.
    assert!(!stands_in_for_visible(
        TileId {
            z: 13,
            x: 4096,
            y: 2724
        }
        .key(),
        &visible,
        2
    ));
    assert!(!stands_in_for_visible(
        TileId { z: 12, x: 8, y: 8 }.key(),
        &visible,
        2
    ));
}

#[test]
fn the_keep_list_stays_free_of_descendants() {
    // Descendants are recognised against what is resident, never enumerated into the keep
    // list: naming them would be 4^DESCENDANT_DEPTH keys per visible tile, almost all of
    // which were never fetched. This is the invariant that lets the bound above hold.
    let c = camera(-122.4194, 37.7749, 14.0, 411.0, 891.0);
    let exact = visible(&c, 0, 16);
    let deepest = exact.iter().map(|t| t.z).max().expect("a tile");
    for tile in resident_set(&c, 0, 16) {
        assert!(
            tile.z <= deepest,
            "{tile:?} is deeper than any visible tile"
        );
    }
}

/// **Phase B: a missing tile falls back to its parent, which falls back to the
/// background.** A pruned (or never-stored) water tile resolves to no fetch of its own;
/// the renderer keeps the visible tile's ancestors resident (`resident_set`), draws the
/// coarsest resident one under it, and the style background — the water colour — shows
/// through wherever no ancestor carries land. This pins the chain tile by tile:
///
/// 1. the missing tile's ancestors are exactly the keep list ahead of it;
/// 2. each ancestor step halves the coordinates (the overzoom the reader takes);
/// 3. the background the chain bottoms out at is the water colour, not land.
#[test]
fn a_missing_tile_falls_back_through_its_parent_to_the_water_background() {
    // A mid-Pacific viewport at z12: the visible tiles are deep-ocean tiles the archive
    // does not store (Phase B prunes them), so every one of them must have its parent
    // chain in the keep list.
    let c = camera(-150.0, 0.0, 12.0, 411.0, 891.0);
    let exact = visible(&c, 0, 14);
    assert!(!exact.is_empty(), "the viewport must cover tiles");
    assert!(exact.iter().all(|t| t.z == 12));
    let keep = resident_set(&c, 0, 14);
    for tile in &exact {
        // 1. The parent is kept (and therefore drawable as the overzoom stand-in).
        let parent = tile.ancestor(1).expect("z12 has a parent");
        assert!(
            keep.contains(&parent),
            "{tile:?} is missing with no parent {parent:?} kept to overzoom",
        );
        // 2. Each ancestor step halves the coordinates — the overzoom mapping.
        let grandparent = tile.ancestor(2).expect("z12 has a grandparent");
        assert_eq!(parent.x, tile.x >> 1, "overzoom halves x");
        assert_eq!(parent.y, tile.y >> 1, "overzoom halves y");
        assert_eq!(grandparent.x, tile.x >> 2, "two levels halve twice");
        assert_eq!(grandparent.y, tile.y >> 2);
        assert!(
            keep.contains(&grandparent),
            "{tile:?} is missing with no grandparent {grandparent:?} kept",
        );
    }
    // 3. The chain bottoms out at the water background: the style backdrop behind every
    // tile is the sea colour, so an ocean viewport with no resident ancestor still reads
    // as ocean rather than land or void.
    use crate::style::{background, Variant};
    let water_light = 0xFF80_DEEA;
    let water_dark = 0xFF0D_1B2A;
    assert_eq!(
        background(Variant::Light),
        water_light,
        "the light backdrop must stay the water colour Phase B relies on",
    );
    assert_eq!(
        background(Variant::Dark),
        water_dark,
        "the dark backdrop must stay the water colour Phase B relies on",
    );
    // And the `water` fill arm agrees with it: a stored water polygon and the bare
    // background paint the same sea, which is what makes dropping the tile invisible.
    let water = crate::style::layers()
        .iter()
        .find(|l| l.id == "water")
        .expect("the water fill arm");
    assert_eq!(water.light, water_light, "water fill matches the light backdrop");
    assert_eq!(water.dark, water_dark, "water fill matches the dark backdrop");
}

/// Ancestor levels halve the coordinates each step, level zero is the tile itself,
/// and anything past the root is `None` rather than a wrapped tile.
///
/// The keep list and the overzoom reader both rely on this: a wrong shift fetches
/// the neighbour's parent, and a wrapping root ancestor would pin the whole world
/// resident under every viewport.
#[test]
fn tile_ancestor_chain_halves_each_level_and_stops_at_root() {
    let tile = TileId { z: 12, x: 2048, y: 1362 };
    assert_eq!(tile.ancestor(0), Some(tile), "level zero is the tile itself");
    let parent = tile.ancestor(1).expect("z12 has a parent");
    assert_eq!((parent.z, parent.x, parent.y), (11, 1024, 681));
    let grandparent = tile.ancestor(2).expect("z12 has a grandparent");
    assert_eq!((grandparent.z, grandparent.x, grandparent.y), (10, 512, 340));
    assert_eq!(tile.ancestor(12), Some(TileId { z: 0, x: 0, y: 0 }), "the root");
    assert!(tile.ancestor(13).is_none(), "past the root is nothing, not a wrap");
    assert!(TileId { z: 0, x: 0, y: 0 }.ancestor(1).is_none(), "the root has no parent");
    // And the depth gate agrees: the tile stands in for itself and its parent chain only.
    let root = TileId { z: 0, x: 0, y: 0 };
    assert!(tile.descends_from(&tile, 0), "depth zero still covers self");
    assert!(!tile.descends_from(&parent, 0), "depth zero stops past self");
    assert!(tile.descends_from(&grandparent, 2), "depth two covers two levels");
    assert!(!root.descends_from(&tile, 4), "an ancestor never descends from its child");
}
