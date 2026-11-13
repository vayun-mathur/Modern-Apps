//! Live-traffic JNI: `updateTrafficNative`, `notifyTrafficFetchFinishedNative`,
//! `getTrafficSegmentsNative`, `getTrafficTileNative` and
//! `ensureTrafficLoadedNative`, plus the per-square segment cache they share.
//! Pure move out of `lib.rs`; no logic changes.

use std::collections::BTreeMap;
use std::sync::{Mutex, OnceLock};

use jni::objects::{JByteArray, JLongArray, JObject};
use jni::sys::{jbyteArray, jdouble, jdoubleArray, jint};
use jni::EnvUnowned;

use super::routes::{ensure_traffic_loaded, graph};

/// Per-square traffic segments + requested-square set for the overlay/tiles.
pub(crate) struct TrafficMeta {
    by_square: BTreeMap<i32, Vec<f64>>,
    pub(crate) requested: Vec<u32>,
}

pub(crate) fn traffic_meta() -> &'static Mutex<TrafficMeta> {
    static M: OnceLock<Mutex<TrafficMeta>> = OnceLock::new();
    M.get_or_init(|| {
        Mutex::new(TrafficMeta {
            by_square: BTreeMap::new(),
            requested: Vec::new(),
        })
    })
}

// ---------------------------------------------------------------------------
// JNI: updateTrafficNative
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_updateTrafficNative<'local>(
    mut env: EnvUnowned<'local>,
    _thiz: JObject<'local>,
    edge_ids: JLongArray<'local>,
    speeds: JByteArray<'local>,
    packed_square: jint,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        let g = match graph() {
            Some(g) => g,
            None => return Ok(()),
        };

        let len = edge_ids.len(env).unwrap_or(0);
        if len == 0 {
            // Still record an (empty) square so the overlay clears it.
            let mut meta = traffic_meta().lock().unwrap();
            meta.by_square.entry(packed_square).or_default().clear();
            return Ok(());
        }
        let mut ids = vec![0i64; len];
        if edge_ids.get_region(env, 0, &mut ids).is_err() {
            return Ok(());
        }
        let mut sp = vec![0i8; len];
        if speeds.get_region(env, 0, &mut sp).is_err() {
            return Ok(());
        }

        // The walk runs with no lock held. It recovers each edge's source node (a binary
        // search over the planet's node array) and reads three records out of the mapped
        // graph, which on a phone means cold flash pages and zstd blocks — while
        // `traffic_meta` is the lock the camera-settle prefetch (`ensureTrafficLoadedNative`)
        // takes first. Holding it across the walk let the caller wait out the whole square.
        // Now both locks are held only for the swap-in below.
        let mut updates: Vec<(u64, u8)> = Vec::with_capacity(len);
        let mut fresh: Vec<f64> = Vec::with_capacity(len * 5);
        for i in 0..len {
            let edge_id = ids[i] as u64;
            let speed = sp[i] as u8;
            if edge_id < g.edge_count && speed < 255 {
                updates.push((edge_id, speed));
                // The only entry point with no source node in hand: the id comes
                // from Kotlin. Recovered first, because reading the record needs it.
                let source = g.find_node_idx_for_edge(edge_id);
                let edge = g.edge(source, edge_id);
                let node_u = g.node(source);
                if edge.target < g.node_count {
                    let node_v = g.node(edge.target);
                    let ratio = if edge.speed_limit > 0 {
                        speed as f64 / edge.speed_limit as f64
                    } else {
                        1.0
                    };
                    fresh.push(node_u.lat_e7 as f64 * 1e-7);
                    fresh.push(node_u.lon_e7 as f64 * 1e-7);
                    fresh.push(node_v.lat_e7 as f64 * 1e-7);
                    fresh.push(node_v.lon_e7 as f64 * 1e-7);
                    fresh.push(ratio);
                }
            }
        }
        {
            let mut speeds_w = super::routes::traffic_speeds().write().unwrap();
            speeds_w.extend(updates);
        }
        traffic_meta().lock().unwrap().by_square.insert(packed_square, fresh);
        Ok(())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

// ---------------------------------------------------------------------------
// JNI: notifyTrafficFetchFinishedNative (no-op, kept for ABI compatibility)
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_notifyTrafficFetchFinishedNative<
    'local,
>(
    _env: EnvUnowned<'local>,
    _thiz: JObject<'local>,
    _packed_square: jint,
) {
}

// ---------------------------------------------------------------------------
// JNI: getTrafficSegmentsNative
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_getTrafficSegmentsNative<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _thiz: JObject<'local>,
) -> jdoubleArray {
    env.with_env(|env| -> jni::errors::Result<jdoubleArray> {
        let null = std::ptr::null_mut();
        let meta = traffic_meta().lock().unwrap();

        let mut flattened: Vec<f64> = Vec::new();
        for segments in meta.by_square.values() {
            flattened.extend_from_slice(segments);
            if flattened.len() > 50000 {
                break;
            }
        }
        if flattened.len() > 50000 {
            flattened.truncate(50000);
        }

        match env.new_double_array(flattened.len()) {
            Ok(arr) => {
                if !flattened.is_empty() && arr.set_region(env, 0, &flattened).is_err() {
                    return Ok(null);
                }
                Ok(arr.into_raw())
            }
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

// ---------------------------------------------------------------------------
// JNI: getTrafficTileNative
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_getTrafficTileNative<'local>(
    mut env: EnvUnowned<'local>,
    _thiz: JObject<'local>,
    z: jint,
    x: jint,
    y: jint,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let null = std::ptr::null_mut();
        let tile = {
            let meta = traffic_meta().lock().unwrap();
            crate::mvt::generate_traffic_tile(&meta.by_square, z, x, y)
        };
        let bytes = match tile {
            Some(b) => b,
            None => return Ok(null),
        };

        // set_byte_array_region takes &[i8]; reinterpret the gzip bytes.
        let signed: &[i8] =
            unsafe { std::slice::from_raw_parts(bytes.as_ptr() as *const i8, bytes.len()) };
        match env.new_byte_array(bytes.len()) {
            Ok(arr) => {
                if arr.set_region(env, 0, signed).is_err() {
                    return Ok(null);
                }
                Ok(arr.into_raw())
            }
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

// ---------------------------------------------------------------------------
// JNI: ensureTrafficLoadedNative
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_ensureTrafficLoadedNative<
    'local,
>(
    mut env: EnvUnowned<'local>,
    thiz: JObject<'local>,
    lat: jdouble,
    lon: jdouble,
    force_async: bool,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        if graph().is_none() {
            return Ok(());
        }
        ensure_traffic_loaded(
            &mut *env,
            &thiz,
            (lat * 1e7) as i32,
            (lon * 1e7) as i32,
            force_async,
        );
        Ok(())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
