//! Hand-rolled Mapbox Vector Tile encoder for the live-traffic overlay, plus
//! gzip. Port of the `--- MVT ENCODING UTILS ---` helpers and
//! `getTrafficTileNative` body from `native-lib.cpp`.

use std::collections::BTreeMap;
use std::io::Write;

use flate2::write::GzEncoder;
use flate2::Compression;

fn write_varint(buf: &mut Vec<u8>, mut value: u64) {
    while value >= 0x80 {
        buf.push((value as u8) | 0x80);
        value >>= 7;
    }
    buf.push(value as u8);
}

fn write_tag(buf: &mut Vec<u8>, field: u32, wire_type: u8) {
    write_varint(buf, ((field << 3) | wire_type as u32) as u64);
}

fn write_string(buf: &mut Vec<u8>, field: u32, s: &str) {
    write_tag(buf, field, 2);
    write_varint(buf, s.len() as u64);
    buf.extend_from_slice(s.as_bytes());
}

fn zigzag(n: i32) -> i32 {
    (n << 1) ^ (n >> 31)
}

fn mvt_command(cmd: u32, count: u32) -> u32 {
    (cmd & 0x7) | (count << 3)
}

struct TileProj {
    n: f64,
    x: i32,
    y: i32,
}

impl TileProj {
    fn wgs84_to_tile_px(&self, lat: f64, lon: f64) -> (i32, i32) {
        let lat_rad = lat * std::f64::consts::PI / 180.0;
        let tx = (lon + 180.0) / 360.0 * self.n;
        let ty =
            (1.0 - (lat_rad.tan() + (1.0 / lat_rad.cos())).ln() / std::f64::consts::PI) / 2.0 * self.n;
        let px = ((tx - self.x as f64) * 4096.0) as i32;
        let py = ((ty - self.y as f64) * 4096.0) as i32;
        (px, py)
    }
}

fn compress_gzip(input: &[u8]) -> Vec<u8> {
    if input.is_empty() {
        return Vec::new();
    }
    let mut enc = GzEncoder::new(Vec::new(), Compression::default());
    if enc.write_all(input).is_err() {
        return Vec::new();
    }
    enc.finish().unwrap_or_default()
}

/// Generate a gzip-compressed traffic MVT tile for `(z, x, y)`, or `None` if
/// there is no traffic to render / gzip fails. `by_square` maps packed square
/// ids to flat `[lat1, lon1, lat2, lon2, speed_ratio, ...]` segment lists.
pub fn generate_traffic_tile(
    by_square: &BTreeMap<i32, Vec<f64>>,
    z: i32,
    x: i32,
    y: i32,
) -> Option<Vec<u8>> {
    let n = 2f64.powi(z);
    let lon_min = x as f64 / n * 360.0 - 180.0;
    let lon_max = (x + 1) as f64 / n * 360.0 - 180.0;
    let lat_max = (std::f64::consts::PI * (1.0 - 2.0 * y as f64 / n)).sinh().atan() * 180.0
        / std::f64::consts::PI;
    let lat_min = (std::f64::consts::PI * (1.0 - 2.0 * (y + 1) as f64 / n)).sinh().atan() * 180.0
        / std::f64::consts::PI;

    let proj = TileProj { n, x, y };
    let mut layer_buf: Vec<u8> = Vec::new();
    write_string(&mut layer_buf, 1, "traffic");
    write_tag(&mut layer_buf, 15, 0);
    write_varint(&mut layer_buf, 2);
    write_tag(&mut layer_buf, 5, 0);
    write_varint(&mut layer_buf, 4096);

    let min_lat_idx = lat_min.floor() as i32;
    let max_lat_idx = lat_max.floor() as i32;
    let min_lon_idx = lon_min.floor() as i32;
    let max_lon_idx = lon_max.floor() as i32;

    // "color" is the only key; values are interned in first-seen order.
    let mut values: Vec<String> = Vec::new();
    let mut val_map: BTreeMap<String, u32> = BTreeMap::new();
    let mut get_val_idx = |v: &str| -> u32 {
        if let Some(&idx) = val_map.get(v) {
            idx
        } else {
            let idx = values.len() as u32;
            val_map.insert(v.to_string(), idx);
            values.push(v.to_string());
            idx
        }
    };

    for lat_i in min_lat_idx..=max_lat_idx {
        for lon_i in min_lon_idx..=max_lon_idx {
            let square_id = (((lat_i + 360) as u32) << 16) | (lon_i + 720) as u32;
            let data = match by_square.get(&(square_id as i32)) {
                Some(d) => d,
                None => continue,
            };

            let mut i = 0usize;
            while i + 4 < data.len() {
                let lat1 = data[i];
                let lon1 = data[i + 1];
                let lat2 = data[i + 2];
                let lon2 = data[i + 3];
                let speed_ratio = data[i + 4];
                i += 5;
                if speed_ratio <= 0.0 {
                    continue;
                }
                let (px1, py1) = proj.wgs84_to_tile_px(lat1, lon1);
                let (px2, py2) = proj.wgs84_to_tile_px(lat2, lon2);
                if px1.max(px2) < -512
                    || px1.min(px2) > 4608
                    || py1.max(py2) < -512
                    || py1.min(py2) > 4608
                {
                    continue;
                }

                let mut feat_buf: Vec<u8> = Vec::new();
                write_varint(&mut feat_buf, (3 << 3) as u64); // field 3 (id), wiretype 0
                write_varint(&mut feat_buf, 2);

                let mut geom_buf: Vec<u8> = Vec::new();
                write_varint(&mut geom_buf, mvt_command(1, 1) as u64); // MoveTo
                write_varint(&mut geom_buf, zigzag(px1) as u64);
                write_varint(&mut geom_buf, zigzag(py1) as u64);
                write_varint(&mut geom_buf, mvt_command(2, 1) as u64); // LineTo
                write_varint(&mut geom_buf, zigzag(px2 - px1) as u64);
                write_varint(&mut geom_buf, zigzag(py2 - py1) as u64);

                write_tag(&mut feat_buf, 4, 2);
                write_varint(&mut feat_buf, geom_buf.len() as u64);
                feat_buf.extend_from_slice(&geom_buf);

                let color = if speed_ratio < 0.5 {
                    "#B71C1C"
                } else if speed_ratio < 0.9 {
                    "#E65100"
                } else {
                    "#1B5E20"
                };
                write_tag(&mut feat_buf, 2, 2);
                let mut tag_buf: Vec<u8> = Vec::new();
                write_varint(&mut tag_buf, 0); // key index (color)
                write_varint(&mut tag_buf, get_val_idx(color) as u64);
                write_varint(&mut feat_buf, tag_buf.len() as u64);
                feat_buf.extend_from_slice(&tag_buf);

                write_tag(&mut layer_buf, 2, 2); // feature
                write_varint(&mut layer_buf, feat_buf.len() as u64);
                layer_buf.extend_from_slice(&feat_buf);
            }
        }
    }

    // keys
    write_string(&mut layer_buf, 3, "color");
    // values
    for v in &values {
        write_tag(&mut layer_buf, 4, 2);
        let mut v_buf: Vec<u8> = Vec::new();
        write_string(&mut v_buf, 1, v);
        write_varint(&mut layer_buf, v_buf.len() as u64);
        layer_buf.extend_from_slice(&v_buf);
    }

    let mut tile_buf: Vec<u8> = Vec::new();
    write_tag(&mut tile_buf, 3, 2);
    write_varint(&mut tile_buf, layer_buf.len() as u64);
    tile_buf.extend_from_slice(&layer_buf);

    let compressed = compress_gzip(&tile_buf);
    if compressed.is_empty() {
        // Must not return raw protobuf: the Kotlin HTTP wrapper always tags the
        // body `Content-Encoding: gzip`, so a non-gzip body breaks MapLibre.
        return None;
    }
    Some(compressed)
}

#[cfg(test)]
#[path = "mvt_tests.rs"]
mod tests;
