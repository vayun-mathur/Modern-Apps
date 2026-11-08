use super::*;
use crate::style::Layer;
use crate::tile::geometry::ShapedLabel;
use crate::tile::glyph::fonts_staged;
use tilecodec::mamaps::body::{Body, Feature};

/// The size arms are the style's job now, so there is no multiplier here to pin.
/// `paint::city_labels_track_the_big_city_arm_at_compared_zooms` covers the arms.
#[test]
fn rank_for_layer_orders_country_before_subplace() {
    assert!(rank_for_layer("places-country") < rank_for_layer("places-region"));
    assert!(rank_for_layer("places-region") < rank_for_layer("places-locality"));
    assert!(rank_for_layer("places-locality") < rank_for_layer("places-subplace"));
    assert_eq!(
        rank_for_layer("something-else"),
        u8::MAX,
        "unknown ids sink"
    );
}

/// Line labels rank below every point label but above the unknown sink, and major roads
/// outrank minor roads outrank rivers so the more important line wins a crossing collision.
#[test]
fn line_labels_rank_below_points_roads_above_rivers() {
    assert!(rank_for_layer("roads-label-major") > rank_for_layer("places-subplace"));
    assert!(rank_for_layer("roads-label-major") > rank_for_layer("poi-food"));
    assert!(rank_for_layer("roads-label-major") < rank_for_layer("roads-label-minor"));
    assert!(rank_for_layer("roads-label-minor") < rank_for_layer("waterway-label"));
    assert!(
        rank_for_layer("waterway-label") < u8::MAX,
        "line labels must not sink"
    );
}

/// Every POI layer sits at one rank, below every place label.
///
/// One rank for all six because the split exists to give each colour group its own
/// `text-color` and nothing else — if they ranked in file order, a cafe would beat a
/// park at a collision for no reason anyone authored. And they must be *known* ranks:
/// falling through to `u8::MAX` would put them below an unrecognised layer.
#[test]
fn every_poi_layer_shares_one_rank_below_the_places() {
    let poi = [
        "poi-outdoor",
        "poi-transport",
        "poi-civic",
        "poi-shop",
        "poi-food",
        "poi-culture",
    ];
    for id in poi {
        assert_eq!(rank_for_layer(id), 4, "{id}");
        assert!(
            rank_for_layer(id) > rank_for_layer("places-subplace"),
            "{id}"
        );
        assert!(
            rank_for_layer(id) < u8::MAX,
            "{id} fell through to the sink rank"
        );
    }
    // And the style really does call them that — a renamed layer would silently sink.
    for layer in crate::style::layers() {
        if layer.toggle == Some(crate::style::Toggle::Poi) {
            assert!(
                poi.contains(&layer.id.as_str()),
                "`{}` is not ranked",
                layer.id
            );
        }
    }
}

/// The reference's whole `icon-image` expression: `station` renames, everything else
/// is the kind itself, and a kind with no picture draws label-only.
#[test]
fn a_kind_resolves_to_its_own_sprite_except_the_one_the_reference_renames() {
    let id = crate::style::kind_id_for_test;
    let park = sprite_for(id("park")).expect("park has a sprite");
    assert_eq!(
        park.uv.u0.to_bits(),
        crate::tile::sprite::atlas()
            .get("park")
            .expect("park")
            .uv
            .u0
            .to_bits(),
    );
    // `station` is the rename; it must NOT resolve to a sprite called `station`.
    let station = sprite_for(id("station")).expect("station resolves to train_station");
    assert_eq!(
        station.uv.u0.to_bits(),
        crate::tile::sprite::atlas()
            .get("train_station")
            .expect("train_station")
            .uv
            .u0
            .to_bits(),
    );
    assert!(
        crate::tile::sprite::atlas().get("station").is_none(),
        "or this proves nothing"
    );
    // The one POI kind with no picture: label-only, as MapLibre draws it.
    assert!(sprite_for(id("townhall")).is_none());
    // A kind that is not a POI at all, and the `dict::NONE` id.
    assert!(sprite_for(id("highway")).is_none());
    assert!(
        sprite_for(0).is_none(),
        "the no-kind id must not index the table"
    );
}

#[test]
fn an_unnamed_feature_shapes_nothing() {
    // A feature the name table cannot resolve shapes to None: the renderer
    // skips it silently — no panic, no partial geometry.
    let body = Body::new(4096);
    let layer = Layer {
        id: "places-country".to_string(),
        source_layer: "places".to_string(),
        source_layer_id: tilecodec::mamaps::dict::LAYER_PLACES,
        kind: crate::style::LayerKind::Symbol,
        kinds: vec!["country".to_string()],
        kind_ids: vec![crate::style::kind_id_for_test("country")],
        require_flags: 0,
        forbid_flags: 0,
        detail_ids: Vec::new(),
        forbid_details: Vec::new(),
        forbid_kind_ids: Vec::new(),
        light: 0xFFA3A3A3,
        dark: 0xFFA3A3A3,
        opacity: crate::style::paint::Ramp::constant(1.0),
        width: crate::style::paint::Ramp::constant(0.0),
        gap_width: crate::style::paint::Ramp::constant(0.0),
        spread: crate::style::paint::Ramp::constant(0.0),
        lanes: crate::style::paint::Ramp::constant(1.0),
        carriageway: false,
        dash: (0.0, 0.0),
        text_size: crate::style::paint::Ramp::constant(12.0),
        text_size_large: None,
        rank_threshold: None,
        uppercase: true,
        medium: true,
        toggle: None,
        icon: false,
        text_offset: (0.0, 0.0),
        text_max_width: 0.0,
        variable_anchor: Vec::new(),
        halo_light: 0xFFE2DFDA,
        halo_dark: 0xFFE2DFDA,
        halo_width: 1.0,
        min_zoom: 0,
        browse_min_zoom: 0,
        max_zoom: 22,
        authored: "places_country".to_string(),
    };
    let feature = Feature {
        kind: 1,
        kind_detail: 0,
        geom_type: tilecodec::mamaps::body::GEOM_POINT,
        flags: 0,
        name_idx: tilecodec::mamaps::body::NAME_NONE,
        parts_offset: 0,
        part_count: 0,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    };
    let label = shape_label(&layer, &body, &feature, "Test", 4096, 7, 0);
    assert!(label.is_none(), "no point, no anchor, no label");
}

/// **The bug this field exists to fix.** A shaped label reports the feature's own kind, not
/// its layer's first whitelist entry — the old pick path read `layer.kinds.first()`, so every
/// hit on `poi-food`'s four kinds came back as `restaurant`.
///
/// Also pins the id: the index passed in is the position in the *body* layer, which is what
/// the archive's id table is parallel to.
#[test]
fn a_label_carries_its_own_kind_and_id_not_its_layers() {
    let cafe = crate::style::kind_id_for_test("cafe");
    let mut poi = tilecodec::mamaps::body::Layer::new(tilecodec::mamaps::dict::LAYER_POI);
    poi.features.push(Feature {
        kind: cafe,
        kind_detail: 0,
        geom_type: tilecodec::mamaps::body::GEOM_POINT,
        flags: 0,
        name_idx: 1,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    poi.parts.push(tilecodec::mamaps::body::Part {
        coord_start: 0,
        point_count: 1,
        winding: tilecodec::mamaps::body::WINDING_OUTER,
    });
    poi.coords = vec![(2048, 2048)];
    let body = Body {
        extent: 4096,
        layers: vec![poi],
        names: vec!["Blue Bottle".to_string()],
        ids: vec![(tilecodec::mamaps::dict::LAYER_POI, vec![987_654_321])],
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None,
        carriageways: Vec::new(),
        convention: None,
        region_links: Vec::new(),
    };
    // A layer whose whitelist lists `restaurant` first, exactly as `poi-food` does.
    let layer = food_layer();
    // A reference *into* the body, not a copy: `tile_point` recovers the feature's layer by
    // scanning for the pointer inside each layer's feature slice, so a copy anchors nowhere.
    let feature = &body.layers[0].features[0];
    // The staged TTFs are real, so shaping must succeed; a `None` here means the fixture
    // broke, not that the test does not apply. Skipping silently would make the two
    // assertions below vacuous.
    assert!(fonts_staged(), "this test needs the staged Noto Sans");
    let label =
        shape_label(&layer, &body, feature, "Blue Bottle", 4096, 3, 0).expect("a shaped poi");
    assert_eq!(
        label.kind, cafe,
        "the feature's kind, not the layer's first"
    );
    assert_ne!(label.kind, layer.kind_ids[0], "or this proves nothing");
    assert_eq!(label.feature_id, 987_654_321);
}

fn food_layer() -> Layer {
    let kinds = ["restaurant", "fast_food", "cafe", "bar"];
    Layer {
        id: "poi-food".to_string(),
        source_layer: "poi".to_string(),
        source_layer_id: tilecodec::mamaps::dict::LAYER_POI,
        kind: crate::style::LayerKind::Symbol,
        kinds: kinds.iter().map(|k| (*k).to_string()).collect(),
        kind_ids: kinds
            .iter()
            .map(|k| crate::style::kind_id_for_test(k))
            .collect(),
        require_flags: 0,
        forbid_flags: 0,
        detail_ids: Vec::new(),
        forbid_details: Vec::new(),
        forbid_kind_ids: Vec::new(),
        light: 0xFFCB6704,
        dark: 0xFFCB6704,
        opacity: crate::style::paint::Ramp::constant(1.0),
        width: crate::style::paint::Ramp::constant(0.0),
        gap_width: crate::style::paint::Ramp::constant(0.0),
        spread: crate::style::paint::Ramp::constant(0.0),
        lanes: crate::style::paint::Ramp::constant(1.0),
        carriageway: false,
        dash: (0.0, 0.0),
        text_size: crate::style::paint::Ramp::constant(12.0),
        text_size_large: None,
        rank_threshold: None,
        uppercase: false,
        medium: false,
        toggle: Some(crate::style::Toggle::Poi),
        icon: true,
        text_offset: (1.1, 0.0),
        text_max_width: 8.0,
        variable_anchor: Vec::new(),
        halo_light: 0xFFE2DFDA,
        halo_dark: 0xFF0D1B2A,
        halo_width: 1.0,
        min_zoom: 0,
        browse_min_zoom: 0,
        max_zoom: 22,
        authored: "pois".to_string(),
    }
}

// --- point-label billboarding under tilt --------------------------------

use crate::camera::Camera;

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
fn billboard_off_is_the_plain_projection_byte_for_byte() {
    // At pitch 0 the renderer passes `billboard = false`, and the glyph must be drawn straight
    // through `tile_to_clip` — bit-identical to the pre-billboard path, whatever the anchor is.
    let cam = camera(0.0);
    let (z, x, y) = (14u8, 2617, 6335);
    let m = cam.tile_to_clip(z, x, y);
    let o = ortho2x2(&cam, z, x, y);
    let pos = (0.62f32, 0.48f32);
    let got = billboard_clip(&m, o, pos, (0.5, 0.5), 0.0, 1.0, false);
    let want = [
        m[0] * pos.0 + m[4] * pos.1 + m[12],
        m[1] * pos.0 + m[5] * pos.1 + m[13],
        m[14],
        m[15],
    ];
    assert_eq!(
        got.map(f32::to_bits),
        want.map(f32::to_bits),
        "billboard-off moved a vertex"
    );
}

#[test]
fn a_pitched_anchor_projects_to_the_same_ground_clip_as_unbillboarded() {
    // The anchor vertex (offset zero) must land exactly where the plain projection would put
    // the ground point, so a billboarded label stays glued to its feature under tilt.
    let cam = camera(50.0);
    let (z, x, y) = (14u8, 2617, 6335);
    let m = cam.tile_to_clip(z, x, y);
    let o = ortho2x2(&cam, z, x, y);
    let anchor = (0.4f32, 0.55f32);
    let billed = billboard_clip(&m, o, anchor, anchor, 0.0, 1.0, true);
    let plain = billboard_clip(&m, o, anchor, anchor, 0.0, 1.0, false);
    for (a, b) in billed.iter().zip(plain.iter()) {
        assert!(
            (a - b).abs() < 1e-6,
            "anchor drifted off the ground: {a} vs {b}"
        );
    }
}
