//! Native `.om` decoder for the Weather app map.
//!
//! Reads Open-Meteo spatial `.om` files directly from the keyless
//! `map-tiles.open-meteo.com` bucket over HTTP range requests (so only the
//! index + the covering chunks for the visible region are transferred),
//! decodes a single variable for a lat/lon bounding box, and bilinearly
//! resamples it into a fixed-size raster for display as a MapLibre
//! `ImageSource`. Exposed to Kotlin via JNI.
//!
//! Grid geometry and the covering-range / interpolation logic are ported from
//! open-meteo/weather-map-layer (`src/grids/regular.ts`). Color mapping stays
//! in Kotlin; this crate returns raw `f32` values (NaN where there is no data).

use std::collections::HashMap;
use std::io::Read;
use std::ops::Range;
use std::sync::{Arc, Mutex, OnceLock};

use omfiles::reader::OmFileReader;
use omfiles::traits::{OmFileReadable, OmFileReaderBackend};
use omfiles::OmFilesError;

/// Block size for the range cache. Range reads are aligned to this so nearby
/// chunk reads reuse already-fetched bytes.
const BLOCK: u64 = 64 * 1024;

/// Shared HTTP agent so range requests to the same host reuse the TLS
/// connection (keep-alive) instead of a fresh handshake per request.
fn http_agent() -> &'static ureq::Agent {
    static AGENT: OnceLock<ureq::Agent> = OnceLock::new();
    AGENT.get_or_init(|| {
        ureq::AgentBuilder::new()
            .max_idle_connections_per_host(8)
            .build()
    })
}

/// Process-wide cache of open backends keyed by `.om` URL, so repeated decodes
/// of the same file (panning, changing measure, scrubbing back to a step)
/// reuse the file size + already-fetched blocks instead of refetching. Capped
/// to bound memory across many time steps.
fn cached_backend(url: &str) -> Result<Arc<HttpRangeBackend>, String> {
    static CACHE: OnceLock<Mutex<HashMap<String, Arc<HttpRangeBackend>>>> = OnceLock::new();
    let cache = CACHE.get_or_init(|| Mutex::new(HashMap::new()));
    {
        let guard = cache.lock().unwrap();
        if let Some(b) = guard.get(url) {
            return Ok(b.clone());
        }
    }
    let backend = Arc::new(HttpRangeBackend::open(url)?);
    let mut guard = cache.lock().unwrap();
    // Simple cap: drop everything if we're holding too many files' blocks.
    if guard.len() >= 12 {
        guard.clear();
    }
    guard.insert(url.to_string(), backend.clone());
    Ok(backend)
}

// ---------------------------------------------------------------------------
// HTTP range backend
// ---------------------------------------------------------------------------

/// An [`OmFileReaderBackend`] that serves bytes from a remote `.om` file using
/// HTTP `Range` requests, with a simple aligned in-memory block cache.
struct HttpRangeBackend {
    url: String,
    size: usize,
    cache: Mutex<HashMap<u64, Vec<u8>>>,
}

impl HttpRangeBackend {
    fn open(url: &str) -> Result<Self, String> {
        // A 1-byte range read returns `Content-Range: bytes 0-0/<total>` which
        // gives us the file size without a separate HEAD (some S3 fronts don't
        // answer HEAD consistently).
        let resp = http_agent()
            .get(url)
            .set("Range", "bytes=0-0")
            .call()
            .map_err(|e| format!("size probe failed: {e}"))?;

        let size = resp
            .header("Content-Range")
            .and_then(|cr| cr.rsplit('/').next())
            .and_then(|total| total.trim().parse::<usize>().ok())
            .or_else(|| {
                resp.header("Content-Length")
                    .and_then(|c| c.parse::<usize>().ok())
            })
            .ok_or_else(|| "could not determine file size".to_string())?;

        Ok(Self {
            url: url.to_string(),
            size,
            cache: Mutex::new(HashMap::new()),
        })
    }

    fn fetch_range(&self, offset: u64, len: u64) -> Result<Vec<u8>, String> {
        if len == 0 {
            return Ok(Vec::new());
        }
        let end = offset + len - 1;
        let resp = http_agent()
            .get(&self.url)
            .set("Range", &format!("bytes={offset}-{end}"))
            .call()
            .map_err(|e| format!("range read failed: {e}"))?;
        let mut buf = Vec::with_capacity(len as usize);
        resp.into_reader()
            .read_to_end(&mut buf)
            .map_err(|e| format!("range body read failed: {e}"))?;
        Ok(buf)
    }

    fn ensure_block(&self, block: u64) -> Result<(), String> {
        if self.cache.lock().unwrap().contains_key(&block) {
            return Ok(());
        }
        let start = block * BLOCK;
        let len = BLOCK.min(self.size as u64 - start);
        let data = self.fetch_range(start, len)?;
        self.cache.lock().unwrap().insert(block, data);
        Ok(())
    }
}

impl OmFileReaderBackend for HttpRangeBackend {
    type Bytes<'a>
        = Vec<u8>
    where
        Self: 'a;

    fn count(&self) -> usize {
        self.size
    }

    fn prefetch_data(&self, _offset: usize, _count: usize) {
        // No-op: the block cache already amortizes repeated reads.
    }

    fn get_bytes(&self, offset: u64, count: u64) -> Result<Self::Bytes<'_>, OmFilesError> {
        if count == 0 {
            return Ok(Vec::new());
        }
        let first = offset / BLOCK;
        let last = (offset + count - 1) / BLOCK;
        for b in first..=last {
            self.ensure_block(b)
                .map_err(|e| OmFilesError::GenericError(e))?;
        }

        let mut out = Vec::with_capacity(count as usize);
        let guard = self.cache.lock().unwrap();
        for b in first..=last {
            let block = guard
                .get(&b)
                .ok_or_else(|| OmFilesError::GenericError("cache miss".to_string()))?;
            let block_start = b * BLOCK;
            let from = offset.saturating_sub(block_start).min(block.len() as u64) as usize;
            let block_end = block_start + block.len() as u64;
            let to = (offset + count).min(block_end).saturating_sub(block_start) as usize;
            if from < to {
                out.extend_from_slice(&block[from..to]);
            }
        }
        Ok(out)
    }
}

// ---------------------------------------------------------------------------
// Grid geometry (regular lat/lon grid)
// ---------------------------------------------------------------------------

/// A regular lat/lon grid, mirroring `RegularGridData` from the JS lib.
/// Grid node `(j, i)` sits at `lat = lat_min + dy*j`, `lon = lon_min + dx*i`.
/// The `.om` array is stored `[ny, nx]` (dim 0 = latitude, dim 1 = longitude).
struct Grid {
    nx: usize,
    ny: usize,
    lon_min: f64,
    lat_min: f64,
    dx: f64,
    dy: f64,
}

/// Inclusive-exclusive index window into the grid for a bbox, padded by one
/// cell so bilinear sampling has neighbours (see `getCoveringRanges`).
struct Window {
    y0: usize,
    y1: usize,
    x0: usize,
    x1: usize,
}

impl Grid {
    fn covering_window(&self, west: f64, south: f64, east: f64, north: f64) -> Option<Window> {
        let y0 = ((south - self.lat_min) / self.dy).floor() as i64 - 1;
        let y1 = ((north - self.lat_min) / self.dy).ceil() as i64 + 1;
        let x0 = ((west - self.lon_min) / self.dx).floor() as i64 - 1;
        let x1 = ((east - self.lon_min) / self.dx).ceil() as i64 + 1;

        let y0 = y0.clamp(0, self.ny as i64) as usize;
        let y1 = y1.clamp(0, self.ny as i64) as usize;
        let x0 = x0.clamp(0, self.nx as i64) as usize;
        let x1 = x1.clamp(0, self.nx as i64) as usize;

        if y1 <= y0 || x1 <= x0 {
            return None;
        }
        Some(Window { y0, y1, x0, x1 })
    }
}

/// Bilinear sample of a `[sub_ny, sub_nx]` row-major sub-grid at global
/// fractional grid coordinates `(gy, gx)`. Returns NaN outside the sub-grid or
/// when any of the four corners is missing.
fn bilinear(
    data: &[f32],
    sub_nx: usize,
    sub_ny: usize,
    win: &Window,
    gy: f64,
    gx: f64,
) -> f32 {
    let ly = gy - win.y0 as f64;
    let lx = gx - win.x0 as f64;
    if ly < 0.0 || lx < 0.0 {
        return f32::NAN;
    }
    let y0 = ly.floor() as usize;
    let x0 = lx.floor() as usize;
    if y0 + 1 >= sub_ny || x0 + 1 >= sub_nx {
        return f32::NAN;
    }
    let fy = (ly - y0 as f64) as f32;
    let fx = (lx - x0 as f64) as f32;

    let p00 = data[y0 * sub_nx + x0];
    let p01 = data[y0 * sub_nx + x0 + 1];
    let p10 = data[(y0 + 1) * sub_nx + x0];
    let p11 = data[(y0 + 1) * sub_nx + x0 + 1];

    if p00.is_finite() && p01.is_finite() && p10.is_finite() && p11.is_finite() {
        let w00 = (1.0 - fx) * (1.0 - fy);
        let w01 = fx * (1.0 - fy);
        let w10 = (1.0 - fx) * fy;
        let w11 = fx * fy;
        return p00 * w00 + p01 * w01 + p10 * w10 + p11 * w11;
    }
    // Graceful fallback: nearest finite corner (handles coastal masks).
    let nearest = if fy < 0.5 {
        if fx < 0.5 { p00 } else { p01 }
    } else if fx < 0.5 {
        p10
    } else {
        p11
    };
    if nearest.is_finite() {
        nearest
    } else {
        [p00, p01, p10, p11]
            .into_iter()
            .find(|v| v.is_finite())
            .unwrap_or(f32::NAN)
    }
}

// ---------------------------------------------------------------------------
// Decode
// ---------------------------------------------------------------------------

/// Read a single variable's covering sub-grid as a flat `[sub_ny, sub_nx]`
/// row-major `Vec<f32>`. Handles the derived `wind_speed_10m` measure by
/// combining the u/v components into wind magnitude.
fn read_subgrid(
    root: &OmFileReader<HttpRangeBackend>,
    variable: &str,
    win: &Window,
) -> Result<Vec<f32>, String> {
    let ranges: [Range<u64>; 2] = [
        (win.y0 as u64)..(win.y1 as u64),
        (win.x0 as u64)..(win.x1 as u64),
    ];

    let read_one = |name: &str| -> Result<Vec<f32>, String> {
        let child = root
            .get_child_by_name(name)
            .ok_or_else(|| format!("variable {name} not found"))?;
        let array = child
            .expect_array()
            .map_err(|e| format!("{name} is not an array: {e:?}"))?;
        let decoded = array
            .read::<f32>(&ranges)
            .map_err(|e| format!("read {name} failed: {e:?}"))?;
        Ok(decoded.iter().copied().collect())
    };

    if root.get_child_by_name(variable).is_none() {
        if let Some((u_name, v_name)) = wind_speed_components(variable) {
            let u = read_one(u_name)?;
            let v = read_one(v_name)?;
            let mag = u
                .iter()
                .zip(v.iter())
                .map(|(a, b)| (a * a + b * b).sqrt())
                .collect();
            return Ok(mag);
        }
    }
    read_one(variable)
}

/// For a derived wind-speed variable, the underlying u/v component names.
fn wind_speed_components(variable: &str) -> Option<(&'static str, &'static str)> {
    match variable {
        "wind_speed_10m" => Some(("wind_u_component_10m", "wind_v_component_10m")),
        _ => None,
    }
}

/// Decode `variable` from the `.om` file at `url` over the bbox
/// `[west, south, east, north]`, resampling into an `out_w * out_h` raster in
/// row-major order with **row 0 = north** (top), suitable for a bitmap.
/// Missing/out-of-coverage pixels are `NaN`.
#[allow(clippy::too_many_arguments)]
fn decode_region(
    url: &str,
    variable: &str,
    grid: &Grid,
    west: f64,
    south: f64,
    east: f64,
    north: f64,
    out_w: usize,
    out_h: usize,
) -> Result<Vec<f32>, String> {
    if out_w == 0 || out_h == 0 {
        return Err("empty output size".to_string());
    }
    let win = grid
        .covering_window(west, south, east, north)
        .ok_or_else(|| "bbox does not intersect grid".to_string())?;
    let sub_nx = win.x1 - win.x0;
    let sub_ny = win.y1 - win.y0;

    let backend = cached_backend(url)?;
    let root = OmFileReader::new(backend).map_err(|e| format!("open failed: {e:?}"))?;

    let data = read_subgrid(&root, variable, &win)?;
    if data.len() != sub_nx * sub_ny {
        return Err(format!(
            "unexpected sub-grid size: got {}, expected {}",
            data.len(),
            sub_nx * sub_ny
        ));
    }

    let mut out = vec![f32::NAN; out_w * out_h];
    // MapLibre places the ImageSource linearly in Web Mercator Y between the
    // north/south edges, so sample rows at the Mercator-interpolated latitude
    // (not linearly in latitude) or the field slides off the coastline.
    // Longitude is linear in Mercator X, so columns stay linear in lon.
    let merc_y = |lat_deg: f64| {
        let lat = lat_deg.to_radians();
        (std::f64::consts::FRAC_PI_4 + lat / 2.0).tan().ln()
    };
    let inv_merc_y = |y: f64| (2.0 * y.exp().atan() - std::f64::consts::FRAC_PI_2).to_degrees();
    let y_north = merc_y(north);
    let y_south = merc_y(south);
    for r in 0..out_h {
        let t = (r as f64 + 0.5) / out_h as f64;
        let lat = inv_merc_y(y_north + t * (y_south - y_north));
        let gy = (lat - grid.lat_min) / grid.dy;
        for c in 0..out_w {
            let lon = west + (c as f64 + 0.5) * (east - west) / out_w as f64;
            let gx = (lon - grid.lon_min) / grid.dx;
            out[r * out_w + c] = bilinear(&data, sub_nx, sub_ny, &win, gy, gx);
        }
    }
    Ok(out)
}

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------

#[cfg(not(test))]
mod jni_bindings {
    use super::*;
    use jni::objects::{JClass, JString};
    use jni::sys::{jdouble, jfloatArray, jint};
    use jni::JNIEnv;

    /// JNI entry point backing `OmTilesNative.decodeRegion`. Returns a
    /// `float[]` of length `out_w * out_h` (row-major, row 0 = north; NaN where
    /// there is no data), or `null` on any error so Kotlin can degrade
    /// gracefully.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_weather_map_OmTilesNative_decodeRegion<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        om_url: JString<'local>,
        variable: JString<'local>,
        nx: jint,
        ny: jint,
        lon_min: jdouble,
        lat_min: jdouble,
        dx: jdouble,
        dy: jdouble,
        west: jdouble,
        south: jdouble,
        east: jdouble,
        north: jdouble,
        out_w: jint,
        out_h: jint,
    ) -> jfloatArray {
        let null = std::ptr::null_mut();

        let url: String = match env.get_string(&om_url) {
            Ok(s) => s.into(),
            Err(_) => return null,
        };
        let var: String = match env.get_string(&variable) {
            Ok(s) => s.into(),
            Err(_) => return null,
        };

        let grid = Grid {
            nx: nx.max(0) as usize,
            ny: ny.max(0) as usize,
            lon_min,
            lat_min,
            dx,
            dy,
        };

        let result = decode_region(
            &url,
            &var,
            &grid,
            west,
            south,
            east,
            north,
            out_w.max(0) as usize,
            out_h.max(0) as usize,
        );

        let values = match result {
            Ok(v) => v,
            Err(_) => return null,
        };

        match env.new_float_array(values.len() as jint) {
            Ok(arr) => {
                if env.set_float_array_region(&arr, 0, &values).is_err() {
                    return null;
                }
                arr.into_raw()
            }
            Err(_) => null,
        }
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    /// dwd_icon global regular grid, per weather-map-layer/src/domains.ts.
    fn dwd_icon() -> Grid {
        Grid {
            nx: 2879,
            ny: 1441,
            lon_min: -180.0,
            lat_min: -90.0,
            dx: 0.125,
            dy: 0.125,
        }
    }

    /// Build the current `.om` URL from the live `latest.json` metadata.
    fn latest_om_url(variable: &str) -> (String, String) {
        let meta_url = format!(
            "https://map-tiles.open-meteo.com/data_spatial/dwd_icon/latest.json?variable={variable}"
        );
        let body = ureq::get(&meta_url).call().unwrap().into_string().unwrap();

        let reference_time = json_string(&body, "reference_time");
        let valid_time = first_valid_time(&body);

        // reference_time "2026-07-01T18:00:00Z" -> "2026/07/01/1800Z"
        let ref_date = &reference_time[0..10]; // 2026-07-01
        let (y, m, d) = (&ref_date[0..4], &ref_date[5..7], &ref_date[8..10]);
        let ref_hhmm = format!("{}{}", &reference_time[11..13], &reference_time[14..16]);
        // valid_time "2026-07-01T18:00Z" -> filename "2026-07-01T1800"
        let file = valid_time.replace(':', "").trim_end_matches('Z').to_string();

        let url = format!(
            "https://map-tiles.open-meteo.com/data_spatial/dwd_icon/{y}/{m}/{d}/{ref_hhmm}Z/{file}.om"
        );
        (url, valid_time)
    }

    fn json_string(body: &str, key: &str) -> String {
        let needle = format!("\"{key}\"");
        let start = body.find(&needle).unwrap() + needle.len();
        let colon = body[start..].find(':').unwrap() + start + 1;
        let q1 = body[colon..].find('"').unwrap() + colon + 1;
        let q2 = body[q1..].find('"').unwrap() + q1;
        body[q1..q2].to_string()
    }

    fn first_valid_time(body: &str) -> String {
        let key = "\"valid_times\"";
        let start = body.find(key).unwrap() + key.len();
        let br = body[start..].find('[').unwrap() + start + 1;
        let q1 = body[br..].find('"').unwrap() + br + 1;
        let q2 = body[q1..].find('"').unwrap() + q1;
        body[q1..q2].to_string()
    }

    #[test]
    #[ignore = "requires network; run with `cargo test -- --ignored`"]
    fn decodes_temperature_over_germany() {
        let (url, _) = latest_om_url("temperature_2m");
        // Roughly Germany.
        let out_w = 64;
        let out_h = 64;
        let grid = dwd_icon();
        let values =
            decode_region(&url, "temperature_2m", &grid, 5.0, 47.0, 15.0, 55.0, out_w, out_h)
                .expect("decode should succeed");

        assert_eq!(values.len(), out_w * out_h);
        let finite: Vec<f32> = values.into_iter().filter(|v| v.is_finite()).collect();
        assert!(!finite.is_empty(), "expected some finite values");
        let mean = finite.iter().sum::<f32>() / finite.len() as f32;
        // Sanity: land-surface temperature in a plausible °C range.
        assert!(mean > -60.0 && mean < 60.0, "implausible mean temp: {mean}");
        println!("mean temperature over Germany: {mean:.2} °C ({} px)", finite.len());
    }

    #[test]
    #[ignore = "requires network; run with `cargo test -- --ignored`"]
    fn derives_wind_speed_from_components() {
        let (url, _) = latest_om_url("wind_u_component_10m");
        let grid = dwd_icon();
        let values =
            decode_region(&url, "wind_speed_10m", &grid, 5.0, 47.0, 15.0, 55.0, 32, 32).unwrap();
        let finite: Vec<f32> = values.into_iter().filter(|v| v.is_finite()).collect();
        assert!(!finite.is_empty());
        assert!(finite.iter().all(|&v| v >= 0.0), "wind speed must be >= 0");
    }
}
