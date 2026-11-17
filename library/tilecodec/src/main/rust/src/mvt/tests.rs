use super::*;
use crate::proto::Reader;

/// The real tile lifted out of the published v5-ca.pmtiles. See
/// `tests/fixtures/README.md`.
const REAL_TILE: &[u8] = include_bytes!("../../tests/fixtures/v5ca_z11_tile.mvt");

fn point_feature(x: i32, y: i32, props: Vec<(&str, Value)>) -> Feature {
    Feature {
        id: None,
        geom_type: GeomType::Point,
        geometry: encode_points(&[(x, y)]),
        props: props.into_iter().map(|(k, v)| (k.to_string(), v)).collect(),
    }
}

#[test]
fn command_integers_pack_and_unpack() {
    // The spec's worked example: MoveTo with count 1 is 9.
    assert_eq!(command(CMD_MOVE_TO, 1), 9);
    assert_eq!(command_parts(9), (CMD_MOVE_TO, 1));
    // LineTo with count 3 is 26.
    assert_eq!(command(CMD_LINE_TO, 3), 26);
    assert_eq!(command_parts(26), (CMD_LINE_TO, 3));
    assert_eq!(command_parts(command(CMD_CLOSE_PATH, 1)), (CMD_CLOSE_PATH, 1));
}

#[test]
fn points_round_trip_through_the_geometry_stream() {
    let pts = vec![(0, 0), (25, 17), (4096, 4096), (10, 4000), (-5, -5)];
    let geom = encode_points(&pts);
    // One command integer plus two zigzag deltas per point.
    assert_eq!(geom.len(), 1 + pts.len() * 2);
    assert_eq!(command_parts(geom[0]), (CMD_MOVE_TO, pts.len() as u32));
    assert_eq!(decode_points(&geom).unwrap(), pts);
}

#[test]
fn a_line_geometry_stream_is_not_mistaken_for_points() {
    let geom = vec![command(CMD_MOVE_TO, 1), 0, 0, command(CMD_LINE_TO, 1), 2, 2];
    assert!(decode_points(&geom).is_none(), "a LineTo must not decode as points");
}

#[test]
fn a_tile_round_trips_through_encode_and_decode() {
    let mut layer = Layer::new("transit_stops");
    layer.features.push(point_feature(
        100,
        200,
        vec![
            ("name", Value::String("Embarcadero".into())),
            ("motis_id", Value::String("us-ca-SF-bayarea_901201".into())),
            ("route_type", Value::Uint(1)),
        ],
    ));
    layer.features.push(point_feature(
        300,
        400,
        vec![
            ("name", Value::String("Powell".into())),
            ("route_type", Value::Uint(0)),
        ],
    ));
    let tile = Tile { layers: vec![layer] };

    let back = Tile::decode(&tile.encode()).unwrap();
    assert_eq!(back.layer_names(), vec!["transit_stops"]);
    let l = back.layer("transit_stops").unwrap();
    assert_eq!(l.extent, DEFAULT_EXTENT);
    assert_eq!(l.version, DEFAULT_VERSION);
    assert_eq!(l.features.len(), 2);
    assert_eq!(
        l.features[0].get("motis_id"),
        Some(&Value::String("us-ca-SF-bayarea_901201".into()))
    );
    assert_eq!(l.features[0].get("route_type"), Some(&Value::Uint(1)));
    assert_eq!(decode_points(&l.features[0].geometry).unwrap(), vec![(100, 200)]);
    // The second feature never had a motis_id, and must not inherit one from
    // the shared dictionary.
    assert_eq!(l.features[1].get("motis_id"), None);
    assert_eq!(decode_points(&l.features[1].geometry).unwrap(), vec![(300, 400)]);
}

#[test]
fn every_value_kind_round_trips() {
    let vals = [
        Value::String("s".into()),
        Value::Float(1.5),
        Value::Double(-2.25),
        Value::Int(-42),
        Value::Uint(42),
        Value::SInt(-7),
        Value::Bool(true),
        Value::Bool(false),
    ];
    let mut layer = Layer::new("vals");
    for (i, v) in vals.iter().enumerate() {
        layer.features.push(point_feature(i as i32, 0, vec![("v", v.clone())]));
    }
    let back = Tile::decode(&Tile { layers: vec![layer] }.encode()).unwrap();
    let l = back.layer("vals").unwrap();
    for (i, v) in vals.iter().enumerate() {
        assert_eq!(l.features[i].get("v"), Some(v), "value {i}");
    }
}

#[test]
fn repeated_keys_and_values_are_interned_once() {
    let mut layer = Layer::new("dedup");
    for i in 0..5 {
        layer.features.push(point_feature(
            i,
            0,
            // Same key every time, and only two distinct values.
            vec![("route_type", Value::Uint((i % 2) as u64))],
        ));
    }
    let encoded = Tile { layers: vec![layer] }.encode();
    // Count the layer's key (field 3) and value (field 4) entries directly.
    let mut r = Reader::new(&encoded);
    let (_, _) = r.next_field().unwrap().unwrap();
    let body = r.bytes().unwrap();
    let (mut nkeys, mut nvals) = (0, 0);
    let mut lr = Reader::new(body);
    while let Some((f, w)) = lr.next_field().unwrap() {
        match f {
            3 => {
                nkeys += 1;
                lr.skip(w).unwrap();
            }
            4 => {
                nvals += 1;
                lr.skip(w).unwrap();
            }
            _ => lr.skip(w).unwrap(),
        }
    }
    assert_eq!(nkeys, 1, "one distinct key");
    assert_eq!(nvals, 2, "two distinct values across five features");
}

// --- Against genuine tippecanoe output ---------------------------------

#[test]
fn the_real_tile_decodes_to_what_tippecanoe_wrote() {
    let tile = Tile::decode(REAL_TILE).expect("the published tile decodes");
    let mut names = tile.layer_names();
    names.sort_unstable();
    assert_eq!(names, vec!["earth", "roads", "water"]);
    for l in &tile.layers {
        assert_eq!(l.extent, 4096, "{} extent", l.name);
        assert_eq!(l.version, 2, "{} version", l.name);
    }
    assert_eq!(tile.layer("earth").unwrap().features.len(), 1);
    assert_eq!(tile.layer("roads").unwrap().features.len(), 1);
    assert_eq!(tile.layer("water").unwrap().features.len(), 2);
    // Lines and polygons, which is the pass-through case tile-join needs.
    assert_eq!(tile.layer("roads").unwrap().features[0].geom_type, GeomType::LineString);
    assert_eq!(tile.layer("earth").unwrap().features[0].geom_type, GeomType::Polygon);
}

#[test]
fn the_real_tile_survives_a_re_encode() {
    // The round-trip proof the composite step rests on: re-encoding a
    // tippecanoe tile must preserve its layers, geometry and properties.
    // Bytes are NOT expected to match -- dictionary order depends on first use.
    let before = Tile::decode(REAL_TILE).unwrap();
    let after = Tile::decode(&before.encode()).unwrap();

    assert_eq!(before.layer_names(), after.layer_names());
    for (b, a) in before.layers.iter().zip(after.layers.iter()) {
        assert_eq!(b.name, a.name);
        assert_eq!(b.extent, a.extent);
        assert_eq!(b.version, a.version);
        assert_eq!(b.features.len(), a.features.len(), "{} feature count", b.name);
        for (bf, af) in b.features.iter().zip(a.features.iter()) {
            assert_eq!(bf.id, af.id);
            assert_eq!(bf.geom_type, af.geom_type);
            // Geometry is re-emitted verbatim, so this one IS byte-exact.
            assert_eq!(bf.geometry, af.geometry, "{} geometry", b.name);
            let mut bp = bf.props.clone();
            let mut ap = af.props.clone();
            bp.sort_by(|x, y| x.0.cmp(&y.0));
            ap.sort_by(|x, y| x.0.cmp(&y.0));
            assert_eq!(bp, ap, "{} properties", b.name);
        }
    }
}

#[test]
fn a_re_encode_of_the_real_tile_is_stable() {
    // Second pass must be byte-identical to the first: dictionary order is a
    // function of the decoded model, so once through our writer it is fixed.
    let once = Tile::decode(REAL_TILE).unwrap().encode();
    let twice = Tile::decode(&once).unwrap().encode();
    assert_eq!(once, twice, "re-encoding must be idempotent");
}

#[test]
fn a_truncated_real_tile_errors_rather_than_panicking() {
    // Range requests and partial writes both produce these.
    assert!(Tile::decode(&REAL_TILE[..REAL_TILE.len() / 2]).is_err());
}

// --- signed area -------------------------------------------------------

#[test]
fn signed_area_is_positive_for_the_specs_exterior_winding() {
    // MVT states the rule as a sign, not a direction: exterior rings are
    // positive under the surveyor's formula. In tile space, where y grows
    // downward, that is clockwise on screen -- top-right, bottom-right,
    // bottom-left, top-left. Quoting the direction instead of the sign is a
    // reliable way to get this backwards, which is why the encoder tests below
    // assert on the sign.
    let clockwise_on_screen = [(10, 0), (10, 10), (0, 10), (0, 0)];
    assert_eq!(signed_area(&clockwise_on_screen), 200);
    let mut other = clockwise_on_screen.to_vec();
    other.reverse();
    assert_eq!(signed_area(&other), -200);
}

#[test]
fn signed_area_ignores_an_explicit_closing_vertex() {
    let open = [(10, 0), (10, 10), (0, 10), (0, 0)];
    let mut closed = open.to_vec();
    closed.push(open[0]);
    assert_eq!(signed_area(&open), signed_area(&closed));
    // Twice the area of a 10x10 square.
    assert_eq!(signed_area(&open).abs(), 200);
}

#[test]
fn signed_area_is_zero_for_anything_that_encloses_nothing() {
    assert_eq!(signed_area(&[]), 0);
    assert_eq!(signed_area(&[(1, 1)]), 0);
    assert_eq!(signed_area(&[(0, 0), (5, 5)]), 0);
    // Collinear.
    assert_eq!(signed_area(&[(0, 0), (5, 0), (10, 0)]), 0);
    // A degenerate out-and-back.
    assert_eq!(signed_area(&[(0, 0), (10, 0), (0, 0)]), 0);
}

#[test]
fn signed_area_does_not_overflow_at_the_coordinate_extremes() {
    // Two i32 spans multiply to 62 bits, so the accumulator must be i64.
    let big = i32::MAX;
    let a = signed_area(&[(0, 0), (big, 0), (big, big), (0, big)]);
    assert!(a != 0 && a.abs() > i32::MAX as i64, "{a}");
}

