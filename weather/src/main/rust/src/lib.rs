//! Native `.om` decoder for the Weather app map.
//!
//! Decodes Open-Meteo spatial `.om` files from a keyless
//! `map-tiles.open-meteo.com` bucket using **HTTP Range requests** (64 KB blocks, only the
//! chunks a view actually covers) with a process-wide LRU of backends (12 files) and a
//! per-backend block cache, so panning reuses already-fetched bytes.
//!
//! Never fetch the whole file: an earlier `SliceBackend` experiment used
//! `NetworkClient.performRequestBytes` (~148 MB per pan/zoom) and OOM-crashed the map.
//!
//! Ranges are fetched natively — see [`http_range`]. This previously called back into Kotlin
//! (`OmRangeFetcher`) to avoid `ureq`'s ~90-crate default feature set; restricted to
//! `default-features = false` + `rustls` it is 26 crates already in the workspace lockfile, and
//! dropping the callback removes a `JavaVM::attach_current_thread` from every block read.
//!
//! Color mapping stays in Kotlin; this crate returns raw `f32` values (NaN where no data).
//! Grid geometry and interpolation logic ported from open-meteo/weather-map-layer `src/grids/regular.ts`.

use std::collections::HashMap;
use std::ops::Range;
use std::sync::{Arc, Mutex, OnceLock};

mod http_range;
mod om;

use crate::om::{OmBackend, OmReader};

// ---------------------------------------------------------------------------
// Block caching helpers
// ---------------------------------------------------------------------------

/// Size of the HTTP Range block cache, aligned so nearby chunk reads reuse bytes.
/// Mirrors old `BLOCK = 64 * 1024`.
const BLOCK: u64 = 64 * 1024;

// ---------------------------------------------------------------------------
// Backends
// ---------------------------------------------------------------------------

/// JNI callback backend – fetches only needed 64 KB blocks via Kotlin
/// `OmRangeFetcher`. Implements the same block-cache logic as the old
/// `HttpRangeBackend` (which used `ureq`), but without any native HTTP crate.
struct RangeBackend {
    url: String,
    size: usize,
    cache: Mutex<HashMap<u64, Vec<u8>>>,
}

impl RangeBackend {
    fn new(url: String, size: usize) -> Self {
        Self {
            url,
            size,
            cache: Mutex::new(HashMap::new()),
        }
    }

    fn ensure_block(&self, block: u64) -> Result<(), String> {
        if self.cache.lock().unwrap().contains_key(&block) {
            return Ok(());
        }
        let start = block * BLOCK;
        if start >= self.size as u64 {
            return Ok(());
        }
        let len = BLOCK.min(self.size as u64 - start);
        let data = http_range::fetch_range(&self.url, start, len)?;
        self.cache.lock().unwrap().insert(block, data);
        Ok(())
    }
}

impl OmBackend for RangeBackend {
    fn count(&self) -> usize {
        self.size
    }

    fn get_bytes(&self, offset: u64, count: u64) -> Result<Vec<u8>, String> {
        if count == 0 {
            return Ok(Vec::new());
        }
        let first = offset / BLOCK;
        let last = (offset + count - 1) / BLOCK;
        for b in first..=last {
            self.ensure_block(b)?;
        }
        let mut out = Vec::with_capacity(count as usize);
        let guard = self.cache.lock().unwrap();
        for b in first..=last {
            let block = guard
                .get(&b)
                .ok_or_else(|| "cache miss".to_string())?;
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

// Process-wide cache of open RangeBackends keyed by .om URL, so panning /
// measure / time scrubbing reuse file size + already-fetched blocks. Matches
// old `cached_backend` cap of 12.
fn cached_backend(url: &str) -> Result<Arc<RangeBackend>, String> {
    static CACHE: OnceLock<Mutex<HashMap<String, Arc<RangeBackend>>>> = OnceLock::new();
    let cache = CACHE.get_or_init(|| Mutex::new(HashMap::new()));
    {
        let guard = cache.lock().unwrap();
        if let Some(b) = guard.get(url) {
            return Ok(b.clone());
        }
    }
    let size = http_range::file_size(url)?;
    let backend = Arc::new(RangeBackend::new(url.to_string(), size));
    let mut guard = cache.lock().unwrap();
    if guard.len() >= 12 {
        guard.clear();
    }
    guard.insert(url.to_string(), backend.clone());
    Ok(backend)
}

// ---------------------------------------------------------------------------
// Grid geometry (regular lat/lon grid)
// ---------------------------------------------------------------------------

/// A regular lat/lon grid, mirroring `RegularGridData` from the JS lib.
struct Grid {
    nx: usize,
    ny: usize,
    lon_min: f64,
    lat_min: f64,
    dx: f64,
    dy: f64,
}

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
// Decode helpers
// ---------------------------------------------------------------------------

fn read_subgrid_generic<B: OmBackend>(
    root: &OmReader<B>,
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
        if !child.is_array() {
            return Err(format!("{name} is not an array"));
        }
        child.read_f32(&ranges).map_err(|e| format!("read {name} failed: {e}"))
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

fn wind_speed_components(variable: &str) -> Option<(&'static str, &'static str)> {
    match variable {
        "wind_speed_10m" => Some(("wind_u_component_10m", "wind_v_component_10m")),
        _ => None,
    }
}

fn decode_region_url_inner(
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
    let root = OmReader::new(backend).map_err(|e| format!("open failed: {e}"))?;

    let data = read_subgrid_generic(&root, variable, &win)?;
    if data.len() != sub_nx * sub_ny {
        return Err(format!(
            "unexpected sub-grid size: got {}, expected {}",
            data.len(),
            sub_nx * sub_ny
        ));
    }

    Ok(resample(data, sub_nx, sub_ny, &win, grid, west, south, east, north, out_w, out_h))
}

fn resample(
    data: Vec<f32>,
    sub_nx: usize,
    sub_ny: usize,
    win: &Window,
    grid: &Grid,
    west: f64,
    south: f64,
    east: f64,
    north: f64,
    out_w: usize,
    out_h: usize,
) -> Vec<f32> {
    let mut out = vec![f32::NAN; out_w * out_h];
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
            out[r * out_w + c] = bilinear(&data, sub_nx, sub_ny, win, gy, gx);
        }
    }
    out
}

// ---------------------------------------------------------------------------
// JNI – armv8-only, no ureq, callback via OmRangeFetcher
// ---------------------------------------------------------------------------

mod jni_bindings {
    use super::*;
    use jni::objects::{JClass, JString};
    use jni::sys::{jdouble, jfloatArray, jint};
    use jni::EnvUnowned;

    /// Restored efficient path: fetches only needed `.om` blocks via
    /// `OmRangeFetcher` (HttpURLConnection) instead of full 148 MB file.
    #[no_mangle]
    #[allow(clippy::too_many_arguments)]
    pub extern "system" fn Java_com_vayunmathur_weather_domain_map_OmTilesNative_decodeRegion<
        'local,
    >(
        mut env: EnvUnowned<'local>,
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
        env.with_env(|env| -> jni::errors::Result<jfloatArray> {
            let null = std::ptr::null_mut();

            // Resolves the bridge class while we still hold a JNIEnv; the range fetches below run
            // through it. Harmless to repeat.
            jni_http::init(env);

            let url: String = match om_url.try_to_string(env) {
                Ok(s) => s,
                Err(_) => return Ok(null),
            };
            let var: String = match variable.try_to_string(env) {
                Ok(s) => s,
                Err(_) => return Ok(null),
            };

            let grid = Grid {
                nx: nx.max(0) as usize,
                ny: ny.max(0) as usize,
                lon_min,
                lat_min,
                dx,
                dy,
            };

            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                decode_region_url_inner(
                    &url,
                    &var,
                    &grid,
                    west,
                    south,
                    east,
                    north,
                    out_w.max(0) as usize,
                    out_h.max(0) as usize,
                )
            }))
            .unwrap_or_else(|_| Err("panic in decode_region".to_string()));

            let values = match result {
                Ok(v) => v,
                Err(_) => return Ok(null),
            };

            match env.new_float_array(values.len()) {
                Ok(arr) => {
                    if arr.set_region(env, 0, &values).is_err() {
                        return Ok(null);
                    }
                    Ok(arr.into_raw())
                }
                Err(_) => Ok(null),
            }
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }
}

// ---------------------------------------------------------------------------
// Tests – use in-memory backend now
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

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

    #[test]
    fn grid_covering_window_basic() {
        let grid = dwd_icon();
        let win = grid.covering_window(5.0, 47.0, 15.0, 55.0).expect("window");
        assert!(win.x1 > win.x0);
        assert!(win.y1 > win.y0);
    }

    #[test]
    fn bilinear_finite() {
        let data = vec![1.0f32, 2.0, 3.0, 4.0];
        let win = Window { y0: 0, y1: 2, x0: 0, x1: 2 };
        let v = bilinear(&data, 2, 2, &win, 0.5, 0.5);
        assert!(v > 2.0 && v < 3.0);
    }

    #[test]
    fn resample_produces_finite_values() {
        // Full `.om` decode needs a live file; here we exercise the covering-window +
        // resample path with a synthetic local grid that exactly spans the bbox, so
        // the covering window aligns with the data we hand in (unlike a global grid,
        // where a 2x2 window at the origin would never intersect a mid-latitude bbox).
        let grid = Grid {
            nx: 2,
            ny: 2,
            lon_min: 5.0,
            lat_min: 47.0,
            dx: 10.0,
            dy: 8.0,
        };
        let win = grid.covering_window(5.0, 47.0, 15.0, 55.0).expect("window");
        let sub_nx = win.x1 - win.x0;
        let sub_ny = win.y1 - win.y0;
        let data: Vec<f32> = (0..sub_nx * sub_ny).map(|i| i as f32 * 10.0).collect();
        let out = resample(data, sub_nx, sub_ny, &win, &grid, 5.0, 47.0, 15.0, 55.0, 4, 4);
        assert_eq!(out.len(), 16);
        // Every output pixel falls inside the covered window, so all must be finite.
        assert!(out.iter().all(|v| v.is_finite()));
    }
}
