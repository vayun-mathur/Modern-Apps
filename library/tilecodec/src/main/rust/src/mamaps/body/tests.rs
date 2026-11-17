use super::*;
use crate::mamaps::dict;

type BodyMutator = fn(&mut Vec<u8>);

/// A tile with one polygon carrying a hole and one road, which between them exercise every
/// field the format has.
fn sample() -> Body {
    let mut water = Layer::new(dict::LAYER_LANDTYPE);
    water.features.push(Feature {
        kind: 4,
        kind_detail: dict::NONE,
        geom_type: GEOM_POLYGON,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 2,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    water.parts.push(Part { coord_start: 0, point_count: 4, winding: WINDING_OUTER });
    water.parts.push(Part { coord_start: 4, point_count: 4, winding: WINDING_HOLE });
    water.coords = vec![
        (0, 0),
        (4096, 0),
        (4096, 4096),
        (0, 4096),
        (1000, 1000),
        (1000, 2000),
        (2000, 2000),
        (2000, 1000),
    ];

    let mut roads = Layer::new(dict::LAYER_ROADS);
    roads.features.push(Feature {
        kind: 45,
        kind_detail: 4,
        geom_type: GEOM_LINE,
        flags: FLAG_IS_BRIDGE | FLAG_IS_LINK,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    roads.parts.push(Part { coord_start: 0, point_count: 3, winding: WINDING_OUTER });
    roads.coords = vec![(-64, 10), (2048, 2048), (4160, 4000)];

    Body { extent: DEFAULT_EXTENT, layers: vec![roads, water], names: Vec::new(), ids: Vec::new() , turn_lanes: Vec::new(), buildings: Vec::new(), heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new() }
}

#[test]
fn a_body_round_trips_with_its_layers_in_id_order() {
    let bytes = serialize(&sample()).expect("should serialize");
    let parsed = Body::parse(&bytes).expect("should parse");
    assert_eq!(parsed.extent, DEFAULT_EXTENT);
    // Sorted on the way out, whatever order the caller assembled them in.
    assert_eq!(
        parsed.layers.iter().map(|l| l.layer_id).collect::<Vec<_>>(),
        vec![dict::LAYER_LANDTYPE, dict::LAYER_ROADS],
    );
    let expected = {
        let mut body = sample();
        body.layers.sort_by_key(|l| l.layer_id);
        body
    };
    assert_eq!(parsed, expected);
}

#[test]
fn every_fixed_record_is_the_width_the_format_declares() {
    // A stride that drifted would misread every record after the first.
    let bytes = serialize(&sample()).expect("serialize");
    assert_eq!(&bytes[0..3], b"MBD");
    assert_eq!(Body::raw_len(&bytes).expect("raw_len"), bytes.len() as u32);
    // Two layers: header, two index entries, then payloads.
    assert_eq!(BODY_HEADER_LEN + 2 * LAYER_INDEX_LEN, 40);
    assert_eq!(FEATURE_RECORD_LEN, 24);
    assert_eq!(PART_ENTRY_LEN, 12);
}

/// `raw_len` is read from the body header, which sits outside the compressed frame, so a
/// decompressing reader knows the output size before it starts and allocates once.
#[test]
fn raw_len_is_readable_from_the_first_eight_bytes_alone() {
    let bytes = serialize(&sample()).expect("serialize");
    assert_eq!(Body::raw_len(&bytes[..8]).expect("raw_len"), bytes.len() as u32);
    assert!(Body::raw_len(&bytes[..7]).is_err(), "too short to declare a length");
}

#[test]
fn every_layer_payload_starts_four_byte_aligned() {
    // What lets a reader take zero-copy slices of the fixed records.
    let bytes = serialize(&sample()).expect("serialize");
    let layer_count = bytes[10] as usize;
    for i in 0..layer_count {
        let at = BODY_HEADER_LEN + i * LAYER_INDEX_LEN;
        let offset =
            u32::from_le_bytes([bytes[at + 4], bytes[at + 5], bytes[at + 6], bytes[at + 7]]);
        assert_eq!(offset % 4, 0, "layer {i} starts at {offset}");
    }
}

/// The measured reason the arena is varints: a delta of 1 to 2 bytes against a fixed 4, on
/// geometry that is 87% of a body. Asserted on a path whose steps are small, which is what real
/// simplified geometry looks like.
#[test]
fn the_arena_costs_far_less_than_a_fixed_four_bytes_a_point() {
    let mut roads = Layer::new(dict::LAYER_ROADS);
    roads.features.push(Feature {
        kind: 45,
        kind_detail: dict::NONE,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    let points: Vec<(i16, i16)> = (0..1000).map(|i| (i * 3, i * 2)).collect();
    roads.parts.push(Part { coord_start: 0, point_count: 1000, winding: WINDING_OUTER });
    roads.coords = points.clone();
    let body = Body { extent: DEFAULT_EXTENT, layers: vec![roads], names: Vec::new(), ids: Vec::new() , turn_lanes: Vec::new(), buildings: Vec::new(), heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new() };
    let bytes = serialize(&body).expect("serialize");
    // Two bytes a point rather than four: each delta is (3, 2), a single varint byte each.
    assert!(
        bytes.len() < 1000 * 3,
        "{} bytes for 1000 points, against {} flat",
        bytes.len(),
        1000 * 4,
    );
    assert_eq!(Body::parse(&bytes).expect("parse").layer(dict::LAYER_ROADS).unwrap().coords, points);
}

/// Deltas restart at each part, so a part decodes without walking the one before it and a long
/// line never accumulates a large running value.
#[test]
fn each_part_restarts_its_deltas_from_the_origin() {
    let mut layer = Layer::new(dict::LAYER_ROADS);
    layer.features.push(Feature {
        kind: 45,
        kind_detail: dict::NONE,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 2,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    layer.parts.push(Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER });
    layer.parts.push(Part { coord_start: 2, point_count: 2, winding: WINDING_OUTER });
    // The second part starts far from where the first ended; if deltas carried over, the
    // round trip would place it somewhere else entirely.
    layer.coords = vec![(0, 0), (10, 10), (3000, 3000), (3010, 3010)];
    let body = Body { extent: DEFAULT_EXTENT, layers: vec![layer], names: Vec::new(), ids: Vec::new() , turn_lanes: Vec::new(), buildings: Vec::new(), heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new() };
    let parsed = Body::parse(&serialize(&body).expect("serialize")).expect("parse");
    assert_eq!(parsed, body);
}

/// The arena has no offsets of its own, so a part must start exactly where the previous one
/// ended. A caller that got that wrong would otherwise write a body that round-trips to
/// different geometry.
#[test]
fn parts_that_do_not_tile_the_arena_are_refused() {
    let mut layer = Layer::new(dict::LAYER_ROADS);
    layer.features.push(Feature {
        kind: 45,
        kind_detail: dict::NONE,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    layer.parts.push(Part { coord_start: 1, point_count: 2, winding: WINDING_OUTER });
    layer.coords = vec![(0, 0), (1, 1), (2, 2)];
    let gapped = Body { extent: DEFAULT_EXTENT, layers: vec![layer.clone()], names: Vec::new(), ids: Vec::new() , turn_lanes: Vec::new(), buildings: Vec::new(), heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new() };
    assert!(serialize(&gapped).is_err(), "a part starting past the front");

    layer.parts[0].coord_start = 0;
    let over = Body { extent: DEFAULT_EXTENT, layers: vec![layer.clone()], names: Vec::new(), ids: Vec::new() , turn_lanes: Vec::new(), buildings: Vec::new(), heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new() };
    assert!(serialize(&over).is_err(), "an arena longer than its parts cover");

    layer.coords.pop();
    assert!(serialize(&Body { extent: DEFAULT_EXTENT, layers: vec![layer], names: Vec::new(), ids: Vec::new(), turn_lanes: Vec::new(), buildings: Vec::new(), heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new() }).is_ok());
}

#[test]
fn a_feature_indexing_parts_it_does_not_have_is_refused_by_the_encoder() {
    let mut layer = Layer::new(dict::LAYER_ROADS);
    layer.features.push(Feature {
        kind: 45,
        kind_detail: dict::NONE,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 3,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    layer.parts.push(Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER });
    layer.coords = vec![(0, 0), (1, 1)];
    assert!(serialize(&Body { extent: DEFAULT_EXTENT, layers: vec![layer], names: Vec::new(), ids: Vec::new(), turn_lanes: Vec::new(), buildings: Vec::new(), heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new() }).is_err());
}

#[test]
fn a_features_geometry_reads_back_through_the_part_table() {
    let bytes = serialize(&sample()).expect("serialize");
    let body = Body::parse(&bytes).expect("parse");
    let water = body.layer(dict::LAYER_LANDTYPE).expect("water");
    let feature = &water.features[0];
    let parts = water.parts_of(feature);
    assert_eq!(parts.len(), 2);
    assert!(!parts[0].is_hole(), "the exterior is outer");
    assert!(parts[1].is_hole());
    assert_eq!(water.points(&parts[0]).len(), 4);
    assert_eq!(water.points(&parts[1])[0], (1000, 1000));

    let roads = body.layer(dict::LAYER_ROADS).expect("roads");
    let road = &roads.features[0];
    assert!(road.is_bridge() && road.is_link() && !road.is_tunnel());
    assert_eq!(road.detail_number(), None, "an interned detail, not a number");
    // Coordinates may leave the tile: the clip keeps a buffer either side.
    assert_eq!(roads.points(&roads.parts_of(road)[0])[0], (-64, 10));
}

/// A boundary's admin level shares the field a road's `service` uses, discriminated by a flag,
/// because the style compares an admin level with `<=` rather than matching a name.
#[test]
fn a_numeric_detail_is_a_number_and_an_interned_one_is_not() {
    let mut boundaries = Layer::new(dict::LAYER_BOUNDARIES);
    boundaries.features.push(Feature {
        kind: dict::NONE,
        kind_detail: 2,
        geom_type: GEOM_LINE,
        flags: FLAG_DETAIL_NUMERIC,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    boundaries.parts.push(Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER });
    boundaries.coords = vec![(0, 0), (100, 100)];
    let bytes = serialize(&Body { extent: DEFAULT_EXTENT, layers: vec![boundaries], names: Vec::new(), ids: Vec::new(), turn_lanes: Vec::new(), buildings: Vec::new(), heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new() })
        .expect("serialize");
    let body = Body::parse(&bytes).expect("parse");
    let feature = &body.layer(dict::LAYER_BOUNDARIES).expect("boundaries").features[0];
    assert_eq!(feature.detail_number(), Some(2), "admin level 2, a country border");
}

#[test]
fn an_empty_body_is_valid_and_is_what_an_ocean_tile_costs() {
    let bytes = serialize(&Body::new(DEFAULT_EXTENT)).expect("serialize");
    assert_eq!(bytes.len(), BODY_HEADER_LEN, "nothing but the header");
    let body = Body::parse(&bytes).expect("parse");
    assert!(body.layers.is_empty());
    assert_eq!(body.extent, DEFAULT_EXTENT);
}

#[test]
fn a_layer_with_no_features_still_round_trips() {
    let body = Body { extent: DEFAULT_EXTENT, layers: vec![Layer::new(dict::LAYER_LANDTYPE)], names: Vec::new(), ids: Vec::new() , turn_lanes: Vec::new(), buildings: Vec::new(), heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new() };
    let parsed = Body::parse(&serialize(&body).expect("serialize")).expect("parse");
    assert_eq!(parsed, body);
}

#[test]
fn a_body_carrying_two_layers_with_one_id_is_refused() {
    let body = Body {
        extent: DEFAULT_EXTENT,
        layers: vec![Layer::new(dict::LAYER_LANDTYPE), Layer::new(dict::LAYER_LANDTYPE)],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new(),
    };
    assert!(serialize(&body).is_err());
}

/// A corrupt body must fail here, not by indexing past a slice or allocating on a count it
/// read out of the same corrupt bytes.
#[test]
fn a_corrupt_body_is_refused_rather_than_decoded() {
    let good = serialize(&sample()).expect("serialize");
    assert!(Body::parse(&good[..BODY_HEADER_LEN - 1]).is_err(), "shorter than the header");
    assert!(Body::parse(&good[..good.len() - 4]).is_err(), "declares more than it is");

    let cases: &[(&str, BodyMutator)] = &[
        ("bad magic", |b| b[0] = b'X'),
        ("a newer body version", |b| b[3] = 99),
        ("a zero extent", |b| b[8..10].copy_from_slice(&0u16.to_le_bytes())),
        ("a dirty reserved word", |b| b[12] = 1),
        ("a layer count past the index", |b| b[10] = 200),
        ("a layer payload outside the body", |b| {
            b[20..24].copy_from_slice(&999_999u32.to_le_bytes())
        }),
        ("a layer payload starting inside the index", |b| {
            b[20..24].copy_from_slice(&0u32.to_le_bytes())
        }),
    ];
    for (what, break_it) in cases {
        let mut bytes = good.clone();
        break_it(&mut bytes);
        assert!(Body::parse(&bytes).is_err(), "{what} should be refused");
    }
}

/// Geometry type is validated on parse, and points genuinely round-trip (`places` and `poi`
/// labels are the only features that carry them).
#[test]
fn an_unknown_geometry_type_is_refused_but_points_round_trip() {
    let mut bytes = serialize(&sample()).expect("serialize");
    // The first layer's first feature record: water, at the aligned payload start.
    let at = align4(BODY_HEADER_LEN + 2 * LAYER_INDEX_LEN);
    bytes[at + 4] = 0;
    assert!(Body::parse(&bytes).is_err(), "geometry type 0");
    bytes[at + 4] = 7;
    assert!(Body::parse(&bytes).is_err(), "an unknown geometry type");
    bytes[at + 4] = GEOM_POLYGON;
    assert!(Body::parse(&bytes).is_ok(), "and the sample is otherwise fine");

    // A point genuinely round-trips: one feature, one single-point part, and a name.
    let mut poi = Layer::new(dict::LAYER_POI);
    poi.features.push(Feature {
        kind: 1,
        kind_detail: dict::NONE,
        geom_type: GEOM_POINT,
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
    poi.parts.push(Part { coord_start: 0, point_count: 1, winding: WINDING_OUTER });
    poi.coords = vec![(100, 200)];
    let body =
        Body { extent: DEFAULT_EXTENT, layers: vec![poi], names: vec!["Cafe".to_string()], ids: Vec::new() , turn_lanes: Vec::new(), buildings: Vec::new(), heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new() };
    let parsed = Body::parse(&serialize(&body).expect("serialize")).expect("parse");
    assert_eq!(parsed, body);
    let feature = &parsed.layer(dict::LAYER_POI).expect("poi").features[0];
    assert_eq!(feature.name(&parsed), Some("Cafe"));
}

/// v1 is gone. A body carrying that version byte is a body from an archive this reader no
/// longer speaks, and reading it with 24-byte records would silently misparse every field.
#[test]
fn a_v1_body_is_refused_outright() {
    let mut bytes = serialize(&sample()).expect("serialize");
    bytes[3] = 1;
    assert!(Body::parse(&bytes).is_err(), "a v1 body version");
    bytes[3] = 2;
    assert!(Body::parse(&bytes).is_err(), "a v2 body version");
    bytes[3] = crate::mamaps::header::FORMAT_VERSION;
    assert!(Body::parse(&bytes).is_ok(), "and v3 is otherwise fine");
}

/// The id table is parallel to a layer's features and survives a round trip alongside the
/// name table, which is the case that matters: `poi` carries both.
#[test]
fn the_id_table_round_trips_beside_the_name_table() {
    let mut poi = Layer::new(dict::LAYER_POI);
    for name_idx in 1..=2u16 {
        poi.features.push(Feature {
            kind: 1,
            kind_detail: dict::NONE,
            geom_type: GEOM_POINT,
            flags: 0,
            name_idx,
            parts_offset: u32::from(name_idx) - 1,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        });
        poi.parts.push(Part {
            coord_start: u32::from(name_idx) - 1,
            point_count: 1,
            winding: WINDING_OUTER,
        });
    }
    poi.coords = vec![(10, 20), (30, 40)];
    let body = Body {
        extent: DEFAULT_EXTENT,
        layers: vec![poi],
        names: vec!["Cafe".to_string(), "Bar".to_string()],
        // A real OSM id and ID_NONE for a feature the generator could not attribute.
        ids: vec![(dict::LAYER_POI, vec![12_345_678_901, ID_NONE])],
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new(),
    };
    let bytes = serialize(&body).expect("serialize");
    let parsed = Body::parse(&bytes).expect("parse");
    assert_eq!(parsed, body);
    assert_eq!(parsed.feature_id(dict::LAYER_POI, 0), Some(12_345_678_901));
    assert_eq!(parsed.feature_id(dict::LAYER_POI, 1), Some(ID_NONE));
    assert_eq!(parsed.feature_id(dict::LAYER_POI, 2), None, "past the table");
    assert_eq!(parsed.feature_id(dict::LAYER_ROADS, 0), None, "a layer with no ids");
}
