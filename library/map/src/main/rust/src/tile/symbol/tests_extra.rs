use super::*;
use crate::camera::Camera;
use crate::tile::geometry::ShapedLabel;
use crate::tile::glyph::Weight;
use crate::tile::sprite::Sprite;

fn camera(pitch_deg: f64) -> Camera {
    Camera {
        center_lon: -122.4194,
        center_lat: 37.7749,
        zoom: 14.0,
        width_dp: 800.0,
        height_dp: 1000.0,
        density: 1.0,
        bearing_deg: 0.0,
        pitch_deg,
        pivot_dp: 0.0,
        time_seconds: 0.0,
        globe: false,
        moon: false,
    }
}

/// The pitch-0 tile matrix's linear 2x2 `[m0, m1, m4, m5]`, as the renderer passes it.
fn ortho2x2(cam: &Camera, z: u8, x: u32, y: u32) -> [f32; 4] {
    let flat = Camera {
        pitch_deg: 0.0,
        pivot_dp: 0.0,
        ..*cam
    }
    .tile_to_clip(z, x, y);
    [flat[0], flat[1], flat[4], flat[5]]
}

#[test]
fn a_curved_glyph_vertex_is_never_billboarded() {
    // A curved label writes each vertex as its own anchor (offset zero), so even with the
    // billboard flag on it projects straight onto the ground — map-aligned, as it must be.
    let cam = camera(45.0);
    let (z, x, y) = (14u8, 2617, 6335);
    let m = cam.tile_to_clip(z, x, y);
    let o = ortho2x2(&cam, z, x, y);
    let v = (0.63f32, 0.47f32);
    let curved = billboard_clip(&m, o, v, v, 0.0, 1.0, true);
    let ground = billboard_clip(&m, o, v, v, 0.0, 1.0, false);
    assert_eq!(curved.map(f32::to_bits), ground.map(f32::to_bits));
}

#[test]
fn a_billboarded_glyph_offset_is_screen_constant_regardless_of_depth() {
    // The whole point of scaling the offset by the anchor's `w`: the same tile-local glyph
    // offset must produce the same *screen* (NDC) offset whether the anchor is near the camera
    // or far up-map toward the horizon — otherwise text would shrink into the distance.
    let cam = camera(55.0);
    let (z, x, y) = (14u8, 2617, 6335);
    let m = cam.tile_to_clip(z, x, y);
    let o = ortho2x2(&cam, z, x, y);
    let off = (0.02f32, -0.015f32);
    let ndc_offset = |anchor: (f32, f32)| {
        let a = billboard_clip(&m, o, anchor, anchor, 0.0, 1.0, true);
        let g = billboard_clip(
            &m,
            o,
            (anchor.0 + off.0, anchor.1 + off.1),
            anchor,
            0.0,
            1.0,
            true,
        );
        ((g[0] / g[3]) - (a[0] / a[3]), (g[1] / g[3]) - (a[1] / a[3]))
    };
    // Two anchors at very different ground depths under the tilt (near vs far up-map). The NDC
    // offset is mathematically `ortho2x2 * off` — independent of the anchor's depth — so the
    // only difference is floating-point noise from the `* w / w` round trip.
    let near = ndc_offset((0.5, 0.72));
    let far = ndc_offset((0.5, 0.30));
    assert!(
        (near.0 - far.0).abs() < 1e-5 && (near.1 - far.1).abs() < 1e-5,
        "screen offset changed with depth: {near:?} vs {far:?}"
    );
}

// --- POI icon billboarding ----------------------------------------------

#[test]
fn a_label_anchor_carries_its_sampled_ground_height() {
    // Labels must hang off the terrain, not z=0: the emit writes the ground closure's
    // height as the trailing float on every corner.
    let label = icon_label((0.4, 0.6));
    let (mut vertices, mut indices) = (Vec::new(), Vec::new());
    emit_icon(
        &label,
        test_sprite(),
        false,
        1.0,
        512.0,
        (1.0, 0.0),
        &|_, _| 0.25,
        &mut vertices,
        &mut indices,
    );
    assert_eq!(
        vertices.len(),
        4 * ICON_FLOATS_PER_VERTEX,
        "expected one quad"
    );
    for corner in vertices.chunks_exact(ICON_FLOATS_PER_VERTEX) {
        assert_eq!(
            (corner[4], corner[5]),
            label.anchor,
            "corner lost its anchor"
        );
        assert_eq!(corner[6], 0.25, "corner lost its ground height");
    }
}

#[test]
fn an_icon_quad_carries_its_ground_anchor_on_every_corner() {
    // The billboard shader recovers a corner's screen offset as `position - anchor`, so all
    // four corners must name the same anchor or the quad tears apart under tilt.
    let label = icon_label((0.4, 0.6));
    let (mut vertices, mut indices) = (Vec::new(), Vec::new());
    emit_icon(
        &label,
        test_sprite(),
        false,
        1.0,
        512.0,
        (1.0, 0.0),
        &|_, _| 0.0,
        &mut vertices,
        &mut indices,
    );
    assert_eq!(
        vertices.len(),
        4 * ICON_FLOATS_PER_VERTEX,
        "expected one quad"
    );
    for corner in vertices.chunks_exact(ICON_FLOATS_PER_VERTEX) {
        assert_eq!(
            (corner[4], corner[5]),
            label.anchor,
            "corner lost its anchor"
        );
    }
}

#[test]
fn an_icon_corner_offset_is_a_tile_local_offset_from_the_anchor() {
    // Not a resolved screen position: the offset has to survive into the vertex as something
    // the shader can rotate, or billboarding would have to happen on the CPU per frame.
    let label = icon_label((0.4, 0.6));
    let (mut vertices, mut indices) = (Vec::new(), Vec::new());
    let (density, tile_span_px) = (2.0f32, 512.0f32);
    let sprite = test_sprite();
    emit_icon(
        &label,
        sprite,
        false,
        density,
        tile_span_px,
        (1.0, 0.0),
        &|_, _| 0.0,
        &mut vertices,
        &mut indices,
    );
    let half_w = sprite.width_dp * density * 0.5 / tile_span_px;
    let half_h = sprite.height_dp * density * 0.5 / tile_span_px;
    let first = &vertices[..ICON_FLOATS_PER_VERTEX];
    assert!(
        (first[0] - first[4] + half_w).abs() < 1e-6,
        "x offset is not the half-width"
    );
    assert!(
        (first[1] - first[5] + half_h).abs() < 1e-6,
        "y offset is not the half-height"
    );
}

#[test]
fn a_billboarded_icon_stays_the_same_screen_size_at_any_pitch() {
    // The bug being fixed: on the ground plane a tilted camera foreshortens the quad, so its
    // screen height collapses. Billboarded, the icon must measure the same in NDC at pitch 60
    // as it does flat on — that is what "faces the camera" means here.
    let sprite = test_sprite();
    let (z, x, y) = CENTRED_TILE;
    let ndc_height = |pitch: f64, billboard: bool| {
        let cam = camera(pitch);
        let m = cam.tile_to_clip(z, x, y);
        let o = ortho2x2(&cam, z, x, y);
        let label = icon_label(CENTRE_ANCHOR);
        let (mut vertices, mut indices) = (Vec::new(), Vec::new());
        emit_icon(
            &label,
            sprite,
            false,
            1.0,
            cam.tile_span_px(z) as f32,
            (1.0, 0.0),
            &|_, _| 0.0,
            &mut vertices,
            &mut indices,
        );
        let corner = |i: usize| {
            let v = &vertices[i * ICON_FLOATS_PER_VERTEX..];
            let c = billboard_clip(&m, o, (v[0], v[1]), (v[4], v[5]), v[6], 1.0, billboard);
            c[1] / c[3]
        };
        (corner(2) - corner(0)).abs()
    };
    let flat = ndc_height(0.0, false);
    let tilted = ndc_height(60.0, true);
    assert!(
        (tilted - flat).abs() < 1e-5,
        "icon changed screen height under tilt: \
        {flat} flat vs {tilted} tilted"
    );
    // And the bug really was a bug: left on the ground plane, pitch 60 squashes it.
    let squashed = ndc_height(60.0, false);
    assert!(
        squashed < flat * 0.9,
        "expected the on-ground quad to foreshorten, got {squashed} \
        against {flat} flat"
    );
}

#[test]
fn a_billboarded_icon_stays_pinned_to_its_ground_point() {
    // Facing the camera must not mean floating free of the feature: the quad's centre has to
    // land exactly where the plain projection puts the POI's ground point.
    let cam = camera(55.0);
    let (z, x, y) = CENTRED_TILE;
    let m = cam.tile_to_clip(z, x, y);
    let o = ortho2x2(&cam, z, x, y);
    let label = icon_label(CENTRE_ANCHOR);
    let (mut vertices, mut indices) = (Vec::new(), Vec::new());
    emit_icon(
        &label,
        test_sprite(),
        false,
        1.0,
        cam.tile_span_px(z) as f32,
        (1.0, 0.0),
        &|_, _| 0.0,
        &mut vertices,
        &mut indices,
    );
    let centre_ndc = |axis: usize| {
        let mut sum = 0.0;
        for v in vertices.chunks_exact(ICON_FLOATS_PER_VERTEX) {
            let c = billboard_clip(&m, o, (v[0], v[1]), (v[4], v[5]), v[6], 1.0, true);
            sum += c[axis] / c[3];
        }
        sum / 4.0
    };
    let ground = billboard_clip(&m, o, CENTRE_ANCHOR, CENTRE_ANCHOR, 0.0, 1.0, false);
    assert!(
        (centre_ndc(0) - ground[0] / ground[3]).abs() < 1e-5,
        "icon drifted in x"
    );
    assert!(
        (centre_ndc(1) - ground[1] / ground[3]).abs() < 1e-5,
        "icon drifted in y"
    );
}

/// The z14 tile holding [`camera`]'s centre, and the tile-local point inside it that the
/// centre falls on. Projecting an icon here keeps it near the middle of the viewport at any
/// pitch — far from the horizon, where a clip `w` near zero would swamp the measurement.
const CENTRED_TILE: (u8, u32, u32) = (14, 2620, 6332);
const CENTRE_ANCHOR: (f32, f32) = (0.6, 0.4);

/// A minimal POI label anchored at `anchor`. No shaped text: `emit_icon` reads only the
/// anchor and the sprite.
fn icon_label(anchor: (f32, f32)) -> ShapedLabel {
    ShapedLabel {
        layer_index: 0,
        anchor,
        name: "Cafe".to_string(),
        lines: Vec::new(),
        total_advance: 0.0,
        weight: Weight::Regular,
        rank: 4,
        pop: 0,
        sprite: None,
        kind: 0,
        feature_id: tilecodec::mamaps::body::ID_NONE,
        region_id: tilecodec::mamaps::body::REGION_NONE,
        centreline: None,
    }
}

/// A 20x24 Dp icon somewhere in the middle of the sheet. Deliberately non-square, so a test
/// that confused the two half-extents would fail.
fn test_sprite() -> Sprite {
    Sprite {
        uv: crate::tile::glyph::UvRect {
            u0: 0.1,
            v0: 0.2,
            u1: 0.15,
            v1: 0.28,
        },
        width_dp: 20.0,
        height_dp: 24.0,
    }
}

// --- the icon push is one derivation, not two checked values -------------

/// Reverting either push value must fail here, not silently flatten icons onto the ground.
///
/// The audit that motivated this guard: writing `line: [0.0, 0.0, 0.0, 0.0]` (flag cleared)
/// or `morph: [0.0; 4]` (matrix zeroed) in the icon push compiles clean and passes all 439
/// host tests — `vulkan/` never compiles on the host, and no host test reads the draw the
/// renderer fills by hand. On device the icons stay on the ground plane under tilt while
/// their labels stand up, and nothing says so.
///
/// So the renderer builds both symbol pushes from [`icon_push_billboard`], and this pins the
/// contract: the derivation's output equals the values the `record_symbol` icon push carries —
/// a revert in either copy breaks the equality rather than the picture.
#[test]
fn an_icon_push_built_from_parts_matches_icon_push() {
    // The derivation under test.
    let cam = camera(50.0);
    let (z, x, y) = CENTRED_TILE;
    let flat = Camera {
        pitch_deg: 0.0,
        pivot_dp: 0.0,
        ..cam
    }
    .tile_to_clip(z, x, y);
    let (flag, ortho) = icon_push_billboard(&flat, cam.pitch_deg);
    // The audit's revert #1: the flag cleared. At pitch 50 the icon must stand up.
    assert_eq!(
        flag.to_bits(),
        1.0f32.to_bits(),
        "a pitched camera billboards its icons"
    );
    // The audit's revert #2: the matrix zeroed. A zero matrix keeps the anchor's clip
    // offset at zero for every corner — the quad collapses onto the ground point.
    assert!(
        ortho.iter().any(|v| *v != 0.0),
        "the billboard matrix is the pitch-0 linear 2x2, never zeroed",
    );
    assert_eq!(
        ortho,
        [flat[0], flat[1], flat[4], flat[5]],
        "and it is exactly the flat matrix's linear part",
    );
    // At pitch 0 the flag clears and the shader draws straight through `tile_to_clip`,
    // byte-identical to the pre-billboard path — the derivation must say so too.
    let level = camera(0.0);
    let flat_level = Camera {
        pitch_deg: 0.0,
        pivot_dp: 0.0,
        ..level
    }
    .tile_to_clip(z, x, y);
    let (flag_level, _) = icon_push_billboard(&flat_level, level.pitch_deg);
    assert_eq!(
        flag_level.to_bits(),
        0.0f32.to_bits(),
        "a level camera draws flat"
    );
}

/// The flag the renderer fills and the flag the derivation computes are the same value.
///
/// This is the half of the guard the shader actually reads (`push.line.w > 0.5`): whatever
/// `record_symbol` puts in `line.w` for the icon draw must be what `billboard_clip` treats
/// as "on", at every pitch the map supports — otherwise the CPU mirror and the GPU disagree
/// about whether the icon is standing up.
#[test]
fn the_derived_flag_drives_billboard_clip_the_way_the_shader_reads_it() {
    let (z, x, y) = CENTRED_TILE;
    for pitch in [0.0f64, 15.0, 45.0, 60.0] {
        let cam = camera(pitch);
        let m = cam.tile_to_clip(z, x, y);
        let flat = Camera {
            pitch_deg: 0.0,
            pivot_dp: 0.0,
            ..cam
        }
        .tile_to_clip(z, x, y);
        let (flag, ortho) = icon_push_billboard(&flat, cam.pitch_deg);
        let on = flag > 0.5;
        // A glyph offset from its anchor: billboarded it stays screen-constant, flat it
        // foreshortens with the ground. `billboard_clip` must take the branch the push says.
        let anchor = CENTRE_ANCHOR;
        let off = (anchor.0 + 0.02, anchor.1 - 0.015);
        let billed = billboard_clip(&m, ortho, off, anchor, 0.0, 1.0, on);
        let ground = billboard_clip(&m, ortho, off, anchor, 0.0, 1.0, false);
        if on {
            assert_ne!(
                billed.map(f32::to_bits),
                ground.map(f32::to_bits),
                "at pitch {pitch} the flag says billboard but the vertex drew flat",
            );
        } else {
            assert_eq!(
                billed.map(f32::to_bits),
                ground.map(f32::to_bits),
                "at pitch 0 the flag says flat but the vertex stood up",
            );
        }
    }
}
