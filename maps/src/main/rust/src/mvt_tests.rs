//! Unit tests for the traffic MVT encoder (sibling of `mvt.rs`, wired via
//! `#[path]`). Tiles are verified by gunzipping and walking the protobuf
//! wire format by hand, so the encoder is checked against the MVT spec's
//! field numbers rather than against itself.
use super::*;
use std::io::Read;

// --- minimal protobuf reader ---------------------------------------------
fn varint(b: &[u8], at: &mut usize) -> u64 {
    let (mut r, mut s) = (0u64, 0u32);
    loop {
        let x = b[*at];
        *at += 1;
        r |= u64::from(x & 0x7f) << s;
        if x & 0x80 == 0 {
            return r;
        }
        s += 7;
    }
}
fn fields(b: &[u8]) -> Vec<(u32, u64, Vec<u8>)> {
    let (mut at, mut out) = (0usize, Vec::new());
    while at < b.len() {
        let tag = varint(b, &mut at);
        let (f, w) = ((tag >> 3) as u32, (tag & 7) as u8);
        if w == 0 {
            out.push((f, varint(b, &mut at), Vec::new()));
        } else {
            let n = varint(b, &mut at) as usize;
            out.push((f, 0, b[at..at + n].to_vec()));
            at += n;
        }
    }
    out
}
fn gunzip(b: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    flate2::read::GzDecoder::new(b).read_to_end(&mut out).unwrap();
    out
}
struct Layer {
    name: String,
    version: u64,
    extent: u64,
    keys: Vec<String>,
    values: Vec<String>,
    feats: Vec<(u64, Vec<u64>, Vec<u64>)>, // (id, tags, geometry commands raw)
}
fn parse_tile(gz: &[u8]) -> Layer {
    let tile = gunzip(gz);
    let tf = fields(&tile);
    assert_eq!(tf.len(), 1, "tile has exactly the layer field");
    assert_eq!(tf[0].0, 3, "tile field 3 is layers");
    let mut l = Layer { name: String::new(), version: 0, extent: 0, keys: vec![], values: vec![], feats: vec![] };
    for (f, v, bytes) in fields(&tf[0].2) {
        match f {
            1 => l.name = String::from_utf8(bytes).unwrap(),
            15 => l.version = v,
            5 => l.extent = v,
            3 => l.keys.push(String::from_utf8(bytes).unwrap()),
            4 => {
                let vf = fields(&bytes);
                l.values.push(String::from_utf8(vf[0].2.clone()).unwrap());
            }
            2 => {
                let (mut id, mut tags, mut geom) = (0u64, Vec::new(), Vec::new());
                for (ff, vv, bb) in fields(&bytes) {
                    match ff {
                        3 => id = vv,
                        2 => { let mut a = 0; while a < bb.len() { tags.push(varint(&bb, &mut a)); } }
                        4 => { let mut a = 0; while a < bb.len() { geom.push(varint(&bb, &mut a)); } }
                        _ => panic!("unexpected feature field {ff}"),
                    }
                }
                l.feats.push((id, tags, geom));
            }
            _ => panic!("unexpected layer field {f}"),
        }
    }
    l
}
fn unzig(v: u64) -> i64 {
    ((v >> 1) as i64) ^ -((v & 1) as i64)
}
fn square(lat: i32, lon: i32) -> i32 {
    ((((lat + 360) as u32) << 16) | (lon + 720) as u32) as i32
}

#[test]
fn varint_and_tag_encodings_match_the_wire_format() {
    for (v, want) in [
        (0u64, vec![0u8]), (1, vec![1]), (127, vec![127]), (128, vec![0x80, 1]),
        (300, vec![0xAC, 2]), (16_384, vec![0x80, 0x80, 1]),
        (u64::MAX, vec![0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 1]),
    ] {
        let mut b = Vec::new();
        write_varint(&mut b, v);
        assert_eq!(b, want, "varint {v}");
        let mut at = 0;
        assert_eq!(varint(&b, &mut at), v, "round trip {v}");
    }
    let mut b = Vec::new();
    write_string(&mut b, 1, "traffic");
    assert_eq!(b, [0x0A, 7, b't', b'r', b'a', b'f', b'f', b'i', b'c']);
    assert_eq!(mvt_command(1, 1), 9, "MoveTo(1)");
    assert_eq!(mvt_command(2, 1), 10, "LineTo(1)");
    assert_eq!(mvt_command(7, 3), 31, "ClosePath(3)");
}

#[test]
fn zigzag_maps_signed_deltas_to_unsigned() {
    assert_eq!(zigzag(0), 0);
    assert_eq!(zigzag(-1), 1);
    assert_eq!(zigzag(1), 2);
    assert_eq!(zigzag(-2), 3);
    assert_eq!(zigzag(4096), 8192);
    assert_eq!(zigzag(-4096), 8191);
    for n in [-4608, -512, -1, 0, 1, 512, 4608, 1_000_000] {
        assert_eq!(unzig(zigzag(n) as u64), i64::from(n), "zigzag round trip {n}");
    }
}

#[test]
fn tile_projection_puts_the_origin_at_the_tile_centre() {
    let p = TileProj { n: 1.0, x: 0, y: 0 }; // the z0 world tile
    assert_eq!(p.wgs84_to_tile_px(0.0, 0.0), (2048, 2048));
    assert_eq!(p.wgs84_to_tile_px(0.0, -180.0), (0, 2048));
    assert_eq!(p.wgs84_to_tile_px(0.0, 180.0), (4096, 2048));
    let (px, py) = p.wgs84_to_tile_px(85.051_128_78, 0.0); // Web-Mercator limit
    assert_eq!(px, 2048);
    assert!(py.abs() <= 1, "north limit at the top edge, got {py}");
    let p2 = TileProj { n: 2.0, x: 1, y: 1 };
    assert_eq!(p2.wgs84_to_tile_px(0.0, 0.0), (0, 0), "tile (1,1) starts at the origin");
}

#[test]
fn gzip_round_trips_and_empty_input_stays_empty() {
    assert!(compress_gzip(&[]).is_empty(), "callers rely on empty == failure/none");
    let gz = compress_gzip(b"traffic-tile");
    assert_eq!(&gz[..3], &[0x1F, 0x8B, 8], "gzip magic + deflate");
    assert_eq!(gunzip(&gz), b"traffic-tile");
}

#[test]
fn an_empty_tile_still_has_a_valid_traffic_layer() {
    let gz = generate_traffic_tile(&BTreeMap::new(), 3, 2, 1).expect("tile");
    let l = parse_tile(&gz);
    assert_eq!(l.name, "traffic");
    assert_eq!(l.version, 2);
    assert_eq!(l.extent, 4096);
    assert_eq!(l.keys, ["color"]);
    assert!(l.values.is_empty() && l.feats.is_empty());
}

#[test]
fn a_segment_is_encoded_with_its_colour_and_geometry() {
    // z1 tile (0,0): lon -180..0, lat 0..85.05. Segment inside square (10,-10).
    let mut m = BTreeMap::new();
    m.insert(square(10, -10), vec![10.0, -10.0, 11.0, -9.0, 0.3]);
    let l = parse_tile(&generate_traffic_tile(&m, 1, 0, 0).unwrap());
    assert_eq!(l.values, ["#B71C1C"], "ratio < 0.5 is red");
    assert_eq!(l.feats.len(), 1);
    let (id, tags, geom) = &l.feats[0];
    assert_eq!(*id, 2);
    assert_eq!(tags, &[0, 0], "key 0 (color), value 0");
    let proj = TileProj { n: 2.0, x: 0, y: 0 };
    let (x1, y1) = proj.wgs84_to_tile_px(10.0, -10.0);
    let (x2, y2) = proj.wgs84_to_tile_px(11.0, -9.0);
    assert_eq!(geom.len(), 6);
    assert_eq!(geom[0], mvt_command(1, 1) as u64);
    assert_eq!((unzig(geom[1]), unzig(geom[2])), (i64::from(x1), i64::from(y1)), "MoveTo");
    assert_eq!(geom[3], mvt_command(2, 1) as u64);
    assert_eq!((unzig(geom[4]), unzig(geom[5])), (i64::from(x2 - x1), i64::from(y2 - y1)), "LineTo delta");
}

#[test]
fn colour_thresholds_and_value_interning() {
    let mut m = BTreeMap::new();
    // Four segments in one square: boundary ratios 0.5 and 0.9, plus a repeat.
    m.insert(square(10, -10), vec![
        10.0, -10.0, 10.5, -9.5, 0.49,
        11.0, -10.0, 11.5, -9.5, 0.5,
        12.0, -10.0, 12.5, -9.5, 0.9,
        13.0, -10.0, 13.5, -9.5, 0.2,
    ]);
    let l = parse_tile(&generate_traffic_tile(&m, 1, 0, 0).unwrap());
    assert_eq!(l.values, ["#B71C1C", "#E65100", "#1B5E20"], "first-seen intern order");
    let got: Vec<u64> = l.feats.iter().map(|f| f.1[1]).collect();
    assert_eq!(got, [0, 1, 2, 0], "0.5 is orange, 0.9 is green, repeats reuse the index");
}

#[test]
fn dead_truncated_and_off_tile_segments_are_skipped() {
    let mut m = BTreeMap::new();
    m.insert(square(10, -10), vec![
        10.0, -10.0, 10.5, -9.5, 0.0,   // no live speed
        11.0, -10.0, 11.5, -9.5, -1.0,  // negative ratio
        -80.0, -10.0, -81.0, -9.0, 1.0, // projects far below the tile: culled
        12.0, -10.0, 12.5,               // truncated tail group: ignored
    ]);
    let l = parse_tile(&generate_traffic_tile(&m, 1, 0, 0).unwrap());
    assert!(l.feats.is_empty(), "no feature survives: {:?}", l.feats);
    // A square outside the tile's floor range is never even visited.
    let mut m2 = BTreeMap::new();
    m2.insert(square(-40, 100), vec![-40.0, 100.0, -39.0, 101.0, 1.0]);
    let l2 = parse_tile(&generate_traffic_tile(&m2, 1, 0, 0).unwrap());
    assert!(l2.feats.is_empty());
    // Segments straddling the 512px gutter are kept (partial overlap).
    let mut m3 = BTreeMap::new();
    m3.insert(square(0, -1), vec![0.0, -1.0, 0.0, 0.5, 1.0]);
    let l3 = parse_tile(&generate_traffic_tile(&m3, 1, 0, 0).unwrap());
    assert_eq!(l3.feats.len(), 1, "crossing the east edge is drawn, not culled");
}
