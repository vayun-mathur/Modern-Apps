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

/// A freshly-resident tile ramps its opacity 0→1 across `LOD_FADE_SECONDS`, so a finer LOD
/// fades in rather than popping. Clamped at both ends.
#[test]
fn a_fresh_tile_ramps_zero_to_one_over_the_duration() {
    let uploaded_at = 10.0;
    let d = LOD_FADE_SECONDS;
    assert_eq!(
        lod_fade_alpha(uploaded_at, uploaded_at, d),
        0.0,
        "0 at upload"
    );
    assert!(
        (lod_fade_alpha(uploaded_at + d * 0.5, uploaded_at, d) - 0.5).abs() < 1e-4,
        "halfway through the fade",
    );
    assert_eq!(
        lod_fade_alpha(uploaded_at + d, uploaded_at, d),
        1.0,
        "opaque at the end"
    );
    // Before upload (clock races the stamp) reads as long-resident: the elapsed time is
    // modulo the hourly wrap, so a `now` behind the stamp is indistinguishable from a stamp
    // nearly an hour old. Opaque is the safe direction — a race must never make a tile
    // transparent — and it agrees with `fade_in_progress`, which already wraps.
    assert_eq!(lod_fade_alpha(uploaded_at - 1.0, uploaded_at, d), 1.0);
    assert_eq!(lod_fade_alpha(uploaded_at + 10.0, uploaded_at, d), 1.0);
}

/// A tile resident long enough for the fade to complete is fully opaque.
#[test]
fn a_long_resident_tile_is_fully_opaque() {
    let now = 5000.0;
    let uploaded_at = now - 100.0;
    assert_eq!(lod_fade_alpha(now, uploaded_at, LOD_FADE_SECONDS), 1.0);
}

/// Across the hourly clock wrap a young tile stays young: a tile stamped at 3599.9 against a
/// clock just wrapped to 0.1 is 0.2 s old, ramping — never stuck transparent for the hour.
/// The plain subtraction read −3599.8 s, clamped to 0, and pinned the tile invisible while a
/// coarse ancestor was resident. `fade_in_progress` already wrapped; this makes the alpha
/// agree with it.
#[test]
fn the_fade_survives_the_hourly_clock_wrap() {
    use crate::camera::CLOCK_WRAP_SECONDS;
    let d = LOD_FADE_SECONDS;
    let uploaded_at = CLOCK_WRAP_SECONDS - 0.1;
    let now = 0.1;
    let alpha = lod_fade_alpha(now, uploaded_at, d);
    assert!(
        (alpha - 0.2 / d).abs() < 1e-3,
        "0.2 s into the fade, not stuck at 0: got {alpha}",
    );
    // And the progress gate agrees it is mid-fade, so the frame loop keeps running.
    assert!(fade_in_progress(now, uploaded_at, d));
}

/// The coarse ancestor stays fully opaque underneath a fading finer child: the child ramps
/// (an ancestor is resident to cover the gap) while the ancestor, having nothing resident
/// above it, draws at full opacity.
#[test]
fn coarse_ancestor_is_opaque_under_a_fading_child() {
    use std::collections::HashSet;
    let coarse = TileId { z: 10, x: 5, y: 5 };
    let fine = TileId {
        z: 11,
        x: 10,
        y: 10,
    };
    assert_eq!(fine.ancestor(1), Some(coarse), "fine descends from coarse");

    let resident: HashSet<u64> = [coarse.key(), fine.key()].into_iter().collect();
    let now = 20.0;
    let uploaded_at = now; // both just landed this frame

    // The child fades because its ancestor is resident underneath.
    let child_alpha = tile_lod_alpha(fine.key(), uploaded_at, now, LOD_FADE_SECONDS, &resident);
    assert_eq!(
        child_alpha, 0.0,
        "the child starts transparent and ramps in"
    );

    // The ancestor has no coarser tile resident above it, so it never fades — it is the
    // opaque stand-in the child fades over.
    let ancestor_alpha =
        tile_lod_alpha(coarse.key(), uploaded_at, now, LOD_FADE_SECONDS, &resident);
    assert_eq!(
        ancestor_alpha, 1.0,
        "the coarse ancestor stays fully opaque"
    );

    // Partway through, the child is partly there and the ancestor is still solid.
    let mid = now + LOD_FADE_SECONDS * 0.5;
    assert!(
        (tile_lod_alpha(fine.key(), uploaded_at, mid, LOD_FADE_SECONDS, &resident) - 0.5).abs()
            < 1e-4,
    );
    assert_eq!(
        tile_lod_alpha(coarse.key(), uploaded_at, mid, LOD_FADE_SECONDS, &resident),
        1.0,
    );
}

/// With no coarse ancestor resident there is nothing to show through a gap, so a freshly
/// fetched tile draws fully opaque immediately instead of fading up from the background.
#[test]
fn a_lone_fresh_tile_does_not_fade() {
    use std::collections::HashSet;
    let lone = TileId {
        z: 11,
        x: 10,
        y: 10,
    };
    let resident: HashSet<u64> = [lone.key()].into_iter().collect();
    let now = 3000.0;
    assert_eq!(
        tile_lod_alpha(lone.key(), now, now, LOD_FADE_SECONDS, &resident),
        1.0,
        "no ancestor underneath — draw opaque, never fade over the background",
    );
}

/// The zoom-out upload burst: the first frame and any frame whose zoom decreased drain
/// finished tiles faster, so a zoom-out that invalidates the whole viewport refills in a
/// frame or two instead of trickling at the steady rate while the east half of the map
/// waits lineless.
#[test]
fn the_first_frame_and_any_zoom_out_take_the_burst_path() {
    assert!(zoom_out_burst(None, 10.0), "the first frame bursts");
    assert!(zoom_out_burst(Some(10.0), 4.0), "a zoom-out bursts");
    assert!(
        zoom_out_burst(Some(10.0), 9.99),
        "even a fractional step out bursts",
    );
    assert!(!zoom_out_burst(Some(4.0), 10.0), "a zoom-in keeps the steady rate");
    assert!(!zoom_out_burst(Some(10.0), 10.0), "a pan at the same zoom keeps it");
}

/// The fade window is open for exactly as long as the fade runs, so the on-demand frame
/// loop keeps drawing across it and stops once the tile has settled. A tile frozen at
/// half opacity is what this prevents.
#[test]
fn the_fade_window_is_open_for_exactly_the_fade() {
    let uploaded_at = 100.0;
    let d = LOD_FADE_SECONDS;
    assert!(
        fade_in_progress(uploaded_at, uploaded_at, d),
        "open at upload"
    );
    assert!(
        fade_in_progress(uploaded_at + d * 0.5, uploaded_at, d),
        "open halfway"
    );
    // The instant the ramp reaches 1.0 there is nothing left to animate.
    assert!(
        !fade_in_progress(uploaded_at + d, uploaded_at, d),
        "shut when opaque"
    );
    assert!(
        !fade_in_progress(uploaded_at + d * 10.0, uploaded_at, d),
        "shut long after"
    );
}

/// The window is open wherever the opacity ramp is still moving. Checked against
/// [`lod_fade_alpha`] rather than restated, because the two drifting apart is exactly how
/// the loop would idle mid-fade.
#[test]
fn the_fade_window_covers_every_frame_the_opacity_is_still_ramping() {
    let uploaded_at = 42.0;
    let d = LOD_FADE_SECONDS;
    for step in 0..40 {
        let now = uploaded_at + d * step as f32 / 20.0;
        if lod_fade_alpha(now, uploaded_at, d) < 1.0 {
            assert!(
                fade_in_progress(now, uploaded_at, d),
                "opacity is {} at {now} but the window says settled",
                lod_fade_alpha(now, uploaded_at, d),
            );
        }
    }
}

/// A clock that reads before the stamp is the hourly wrap of the shared clock, and the
/// elapsed time has to be taken modulo the period. Getting this wrong does not cost a few
/// frames — it holds the frame loop open for the rest of the hour, which is the whole
/// defect on-demand rendering exists to fix.
#[test]
fn a_wrapped_clock_measures_the_real_elapsed_time() {
    let d = LOD_FADE_SECONDS;
    // Uploaded 0.5s ago in real time, across a wrap: the fade has finished.
    assert!(
        !fade_in_progress(0.0, 3599.5, d),
        "0.5s elapsed across the wrap is past a 0.3s fade — the loop must be allowed to idle",
    );
    // Uploaded 0.1s ago in real time, across a wrap: still fading.
    assert!(
        fade_in_progress(0.0, 3599.9, d),
        "0.1s elapsed across the wrap is mid-fade"
    );
    // The far side of the wrap must not read as an hour of pending work.
    assert!(
        !fade_in_progress(1.0, 2000.0, d),
        "a stamp far behind the clock must not pin the loop awake",
    );
}

/// A disabled fade animates nothing, so it must never hold the frame loop open — that
/// would be a permanent 60fps with no visible change.
#[test]
fn a_disabled_fade_never_holds_the_loop_open() {
    assert!(!fade_in_progress(10.0, 10.0, 0.0));
    assert!(!fade_in_progress(10.0, 10.0, -1.0));
}

/// The wrap period the fade window measures against has to be the one the bridge actually
/// reduces the clock by, or every elapsed time across a wrap is wrong by the difference.
#[test]
fn the_clock_period_matches_the_bridges_reduction() {
    assert_eq!(
        crate::camera::CLOCK_WRAP_SECONDS,
        crate::camera::CLOCK_WRAP_NANOS as f32 / 1_000_000_000.0,
    );
}

#[test]
fn the_viewport_is_covered_at_an_exact_zoom() {
    // z2 centred on null island: the world is 1024 Dp across (256 grid),
    // so a 256 Dp viewport straddles the four tiles around the centre.
    let tiles = visible(&camera(0.0, 0.0, 2.0, 256.0, 256.0), 0, 16);
    assert_eq!(
        tiles.len(),
        4,
        "the centre of the world is a four-tile corner"
    );
    assert!(tiles.iter().all(|t| t.z == 2));
    let mut coords: Vec<(u32, u32)> = tiles.iter().map(|t| (t.x, t.y)).collect();
    coords.sort_unstable();
    assert_eq!(coords, vec![(1, 1), (1, 2), (2, 1), (2, 2)]);
}

#[test]
fn a_fractional_zoom_uses_the_floor() {
    let tiles = visible(&camera(0.0, 0.0, 2.5, 512.0, 512.0), 0, 16);
    assert!(
        tiles.iter().all(|t| t.z == 2),
        "z2.5 draws z2 tiles, larger"
    );
}

#[test]
fn past_the_archives_max_zoom_the_same_tiles_are_drawn_larger() {
    // The archive stops at z16 and users keep zooming. Without overzoom the map goes
    // blank at z17.
    let tiles = visible(&camera(-122.4194, 37.7749, 19.0, 411.0, 891.0), 0, 16);
    assert!(!tiles.is_empty(), "z19 must still be covered");
    assert!(
        tiles.iter().all(|t| t.z == 16),
        "clamped to the archive's max zoom"
    );
    // A z16 tile at z19 is 8x its normal size, so a phone viewport needs very few.
    assert!(
        tiles.len() <= 4,
        "only a handful of overzoomed tiles: {}",
        tiles.len()
    );
}

#[test]
fn below_the_archives_min_zoom_the_lowest_available_tiles_are_used() {
    let tiles = visible(&camera(0.0, 0.0, 1.0, 400.0, 400.0), 5, 16);
    assert!(tiles.iter().all(|t| t.z == 5));
}

#[test]
fn tiles_off_the_edge_of_the_world_are_not_requested() {
    // At the antimeridian the viewport runs past x = 2^z, and there is no wrap.
    let tiles = visible(&camera(179.99, 0.0, 3.0, 800.0, 400.0), 0, 16);
    let n = 1u32 << 3;
    assert!(!tiles.is_empty());
    for t in &tiles {
        assert!(t.x < n, "x {} outside the grid", t.x);
        assert!(t.y < n, "y {} outside the grid", t.y);
    }
}

#[test]
fn the_poles_clamp_in_y() {
    let tiles = visible(&camera(0.0, 84.9, 4.0, 400.0, 900.0), 0, 16);
    let n = 1u32 << 4;
    assert!(tiles.iter().all(|t| t.y < n));
    assert!(tiles.iter().any(|t| t.y == 0), "the top row is included");
}

#[test]
fn an_unmeasured_viewport_asks_for_nothing() {
    assert!(visible(&camera(0.0, 0.0, 5.0, 0.0, 0.0), 0, 16).is_empty());
}

#[test]
fn the_tile_count_stays_within_the_area_bound() {
    // A sanity bound on GPU residency: a phone at z14 should be tens of tiles, not
    // hundreds. Each resident tile costs vertex and index buffers per layer.
    // (512px tiles cover 4x the area of 256px ones, so the same viewport
    // needs roughly a quarter the tiles.)
    let c = camera(-122.4194, 37.7749, 14.0, 411.0, 891.0);
    let tiles = visible(&c, 0, 16);
    assert!(
        tiles.len() <= bound(&c),
        "{} exceeds the bound {}",
        tiles.len(),
        bound(&c)
    );
    assert!(!tiles.is_empty(), "a phone viewport at z14 covers tiles");
}

#[test]
fn a_rotated_viewport_pulls_in_the_tiles_its_corners_reach() {
    // The heading-up failure this guards: a rotated screen's corners stick out past
    // the unrotated box, and a selection that ignores that leaves them blank. z12 with
    // a phone-shaped viewport at 45 degrees must ask for strictly more than north-up
    // does, and every north-up tile must still be in the set.
    let north_up = camera(-122.4194, 37.7749, 12.0, 411.0, 891.0);
    let turned = Camera {
        bearing_deg: 45.0,
        ..north_up
    };
    let straight = visible(&north_up, 0, 16);
    let rotated = visible(&turned, 0, 16);
    assert!(
        rotated.len() > straight.len(),
        "a 45-degree camera covers more ground: {} vs {}",
        rotated.len(),
        straight.len(),
    );
    for tile in &straight {
        assert!(
            rotated.contains(tile),
            "{tile:?} was dropped by the rotation"
        );
    }
}

#[test]
fn every_corner_of_a_rotated_viewport_lands_in_a_selected_tile() {
    // The property that actually matters, checked against the same rotation the clip
    // matrix applies rather than against a tile count: whatever the bearing, the
    // ground under each corner of the screen belongs to a tile that was asked for.
    for bearing in [0.0, 30.0, 45.0, 90.0, 137.0, 250.0, -80.0] {
        let c = Camera {
            bearing_deg: bearing,
            ..camera(2.3522, 48.8566, 13.0, 411.0, 891.0)
        };
        let tiles = visible(&c, 0, 16);
        let span = c.tile_span_dp(13);
        let centre = crate::camera::project(c.center_lon, c.center_lat, c.zoom);
        let radians = bearing.to_radians();
        let (half_w, half_h) = (c.width_dp as f64 / 2.0, c.height_dp as f64 / 2.0);
        for (sx, sy) in [
            (-half_w, -half_h),
            (half_w, -half_h),
            (half_w, half_h),
            (-half_w, half_h),
        ] {
            let wx = centre.x + radians.cos() * sx - radians.sin() * sy;
            let wy = centre.y + radians.sin() * sx + radians.cos() * sy;
            let tx = (wx / span).floor() as i64;
            let ty = (wy / span).floor() as i64;
            assert!(
                tiles.iter().any(|t| t.x as i64 == tx && t.y as i64 == ty),
                "bearing {bearing}: nothing covers the corner in tile 13/{tx}/{ty}",
            );
        }
    }
}

#[test]
fn the_rotated_tile_count_stays_within_the_area_bound() {
    // The bound is what sizes the residency hint, so it has to grow with the rotation
    // rather than being quietly exceeded by every turned frame.
    let c = Camera {
        bearing_deg: 45.0,
        ..camera(-122.4194, 37.7749, 14.0, 411.0, 891.0)
    };
    let tiles = visible(&c, 0, 16);
    assert!(
        tiles.len() <= bound(&c),
        "{} exceeds the bound {}",
        tiles.len(),
        bound(&c)
    );
}
