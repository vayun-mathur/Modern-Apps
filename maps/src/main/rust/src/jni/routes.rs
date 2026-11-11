//! Driving-route JNI: `init` / `findRouteNative`, the road-graph globals they
//! share (`GRAPH`, live traffic speeds, serialized routing scratch), the
//! traffic-prefetch reverse callback, and the walk-leg road snapper the
//! transit planner borrows. Pure move out of `lib.rs`; no logic changes.

use std::collections::HashSet;
use std::sync::{Arc, Mutex, OnceLock, RwLock};

use jni::objects::{JObject, JString, JValue};
use jni::sys::{jdouble, jint, jobjectArray};
use jni::{Env, EnvUnowned, jni_sig, jni_str};

use crate::geometry::TrafficSpeeds;
use crate::graph::{Graph, WALK};
use crate::routing::{perform_search_loop, prepare_routing, reconstruct_path, route_ascent_descent};
use crate::state::{RadixHeap, RoutingScratchpad};

use super::traffic_jni::traffic_meta;

// ---------------------------------------------------------------------------
// Global state
// ---------------------------------------------------------------------------

/// The immutable whole-world graph, set once by `init`.
static GRAPH: RwLock<Option<Arc<Graph>>> = RwLock::new(None);

pub(crate) fn graph() -> Option<Arc<Graph>> {
    GRAPH.read().ok()?.clone()
}

/// Live traffic speeds (global edge id -> km/h). Read (snapshot) per route,
/// written by `updateTrafficNative`.
pub(crate) fn traffic_speeds() -> &'static RwLock<TrafficSpeeds> {
    static S: OnceLock<RwLock<TrafficSpeeds>> = OnceLock::new();
    S.get_or_init(|| RwLock::new(TrafficSpeeds::default()))
}

/// Reusable A* working sets, serialized so only one route runs at a time.
struct RouteState {
    scratch: RoutingScratchpad,
    heap: RadixHeap,
}

fn route_state() -> &'static Mutex<RouteState> {
    static R: OnceLock<Mutex<RouteState>> = OnceLock::new();
    R.get_or_init(|| {
        Mutex::new(RouteState {
            scratch: RoutingScratchpad::new(),
            heap: RadixHeap::new(),
        })
    })
}

// ---------------------------------------------------------------------------
// Traffic prefetch reverse-callback
// ---------------------------------------------------------------------------

/// The 1° traffic square containing `(lat_e7, lon_e7)`, packed as Kotlin's
/// `packedSquare`.
fn traffic_square(lat_e7: i32, lon_e7: i32) -> (i32, i32, u32) {
    let lat_idx = (lat_e7 as f64 * 1e-7).floor() as i32;
    let lon_idx = (lon_e7 as f64 * 1e-7).floor() as i32;
    let packed = (((lat_idx + 360) as u32) << 16) | (lon_idx + 720) as u32;
    (lat_idx, lon_idx, packed)
}

/// Request the traffic square containing `(lat_e7, lon_e7)` from Kotlin if it
/// hasn't been requested yet. Mirrors the C++ `ensure_traffic_loaded`.
pub(crate) fn ensure_traffic_loaded(
    env: &mut Env,
    thiz: &JObject,
    lat_e7: i32,
    lon_e7: i32,
    force_async: bool,
) {
    let (lat_idx, lon_idx, packed) = traffic_square(lat_e7, lon_e7);

    {
        let mut meta = traffic_meta().lock().unwrap();
        if meta.requested.contains(&packed) {
            return;
        }
        meta.requested.push(packed);
    }

    let _ = env.call_method(
        thiz,
        jni_str!("fetchTrafficData"),
        jni_sig!("(DDDDIZ)V"),
        &[
            JValue::Double(lat_idx as f64),
            JValue::Double(lon_idx as f64),
            JValue::Double(lat_idx as f64 + 1.0),
            JValue::Double(lon_idx as f64 + 1.0),
            JValue::Int(packed as i32),
            JValue::Bool(force_async),
        ],
    );
}

// ---------------------------------------------------------------------------
// JNI: init
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_init<'local>(
    mut env: EnvUnowned<'local>,
    _thiz: JObject<'local>,
    base_path: JString<'local>,
) -> bool {
    env.with_env(|env| -> jni::errors::Result<bool> {
        let base: String = match base_path.try_to_string(env) {
            Ok(s) => s,
            Err(_) => return Ok(false),
        };

        // Single-archive first: one download carries tiles, graph, POI and
        // transit, and the archive's own checks refuse a corrupt container before
        // any section loads. Falls back to the multi-file layout.
        let graph = Graph::load_archive(&crate::graph::archive_path(&base))
            .or_else(|| Graph::load(&base));
        match graph {
            Some(g) => {
                if let Ok(mut w) = GRAPH.write() {
                    *w = Some(Arc::new(g));
                    Ok(true)
                } else {
                    Ok(false)
                }
            }
            None => Ok(false),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

// ---------------------------------------------------------------------------
// JNI: findRouteNative
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_findRouteNative<'local>(
    mut env: EnvUnowned<'local>,
    thiz: JObject<'local>,
    s_lat: jdouble,
    s_lon: jdouble,
    e_lat: jdouble,
    e_lon: jdouble,
    mode: jint,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let null = std::ptr::null_mut();
        let g = match graph() {
            Some(g) => g,
            None => return Ok(null),
        };

        // Snapshot the current traffic speeds so the hot loop needs no locking and
        // concurrent updateTrafficNative calls don't block the whole route.
        let speeds: TrafficSpeeds = traffic_speeds().read().map(|m| m.clone()).unwrap_or_default();

        let state_mutex = route_state();
        let mut state = state_mutex.lock().unwrap();
        let RouteState { scratch, heap } = &mut *state;

        // prepare + search need the JNI env for the driving traffic prefetch.
        // The search asks on every expansion, but a square once requested stays
        // requested, so only the first ask per square needs the global lock.
        let ctx = {
            let mut last_square = u32::MAX;
            let mut seen_squares: HashSet<u32> = HashSet::new();
            let mut ensure = |lat_e7: i32, lon_e7: i32| {
                let (_, _, square) = traffic_square(lat_e7, lon_e7);
                if square == last_square {
                    return;
                }
                last_square = square;
                if seen_squares.insert(square) {
                    ensure_traffic_loaded(&mut *env, &thiz, lat_e7, lon_e7, false);
                }
            };

            let mut ctx = match prepare_routing(
                &g, &speeds, &mut ensure, s_lat, s_lon, e_lat, e_lon, mode, scratch, heap,
            ) {
                Some(c) => c,
                None => return Ok(null),
            };
            perform_search_loop(&g, &speeds, &mut ensure, mode, &mut ctx, scratch, heap);
            ctx
        };

        // A route that never leaves the edge it snapped to reaches no node, so
        // `target_node` stays unset; `ctx.direct` is the route in that case.
        if ctx.target_node == 0xFFFF_FFFF && ctx.direct.is_none() {
            return Ok(null);
        }

        let mut steps = reconstruct_path(&g, &speeds, mode, &ctx, scratch);
        // No elevation section (planet4 ships none): every sample is a fabricated 0 m, which
        // Kotlin would draw as a flat chart with 0 m ascent. Empty arrays read as "no profile".
        if !g.has_elevation() {
            for step in &mut steps {
                step.elevations.clear();
            }
        }
        // Whole-route elevation totals (WS-G): cumulative ascent/descent in metres, computed once and
        // carried on every RawStep so Kotlin can read them off any step.
        let (ascent_m, descent_m) = route_ascent_descent(&steps);

        // --- Marshal steps into OfflineRouter.RawStep[] ---
        let class = match env.find_class(jni_str!("com/vayunmathur/maps/util/OfflineRouter$RawStep")) {
            Ok(c) => c,
            Err(_) => return Ok(null),
        };
        // MUST match the descriptor at the transit call site below: one ctor, two
        // callers. The trailing two Strings are the ride's MOTIS board/alight ids;
        // the trailing `[DDD` is WS-G's per-coordinate elevation array (parallel to
        // the geometry) plus the route's cumulative ascent/descent in metres.
        let ctor = jni_sig!("(ILjava/lang/String;JJ[DDZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;I[ILjava/lang/String;IIILjava/lang/String;Ljava/lang/String;[DDD)V");

        let array = match env.new_object_array(steps.len() as i32, &class, JObject::null()) {
            Ok(a) => a,
            Err(_) => return Ok(null),
        };

        for (i, step) in steps.iter().enumerate() {
            let name = g.road_name(step.name_off).unwrap_or_else(|| "Unknown Road".to_string());
            let jname: JObject = match env.new_string(&name) {
                Ok(s) => s.into(),
                Err(_) => return Ok(null),
            };
            let jgeom = match env.new_double_array(step.coords.len()) {
                Ok(a) => a,
                Err(_) => return Ok(null),
            };
            if jgeom.set_region(env, 0, &step.coords).is_err() {
                return Ok(null);
            }
            let jgeom_obj: JObject = jgeom.into();

            // Packed turn-lane guidance: one int per lane (dir * 2 + valid).
            let jlanes = match env.new_int_array(step.lanes.len()) {
                Ok(a) => a,
                Err(_) => return Ok(null),
            };
            if !step.lanes.is_empty()
                && jlanes.set_region(env, 0, &step.lanes).is_err()
            {
                return Ok(null);
            }
            let jlanes_obj: JObject = jlanes.into();

            // Per-coordinate elevation (metres), parallel to the geometry double[].
            let jelev = match env.new_double_array(step.elevations.len()) {
                Ok(a) => a,
                Err(_) => return Ok(null),
            };
            if !step.elevations.is_empty()
                && jelev.set_region(env, 0, &step.elevations).is_err()
            {
                return Ok(null);
            }
            let jelev_obj: JObject = jelev.into();

            let obj = match env.new_object(
                &class,
                &ctor,
                &[
                    JValue::Int(step.maneuver),
                    JValue::Object(&jname),
                    JValue::Long(step.dist_mm as i64),
                    JValue::Long(step.time_10ms as i64),
                    JValue::Object(&jgeom_obj),
                    JValue::Double(step.speed_ratio),
                    // Transit-only tail. The ctor descriptor is shared with the
                    // RAPTOR path; the road graph has no timetable, so it fills the
                    // transit fields with nulls/zeros.
                    JValue::Bool(false),
                    JValue::Object(&JObject::null()),
                    JValue::Object(&JObject::null()),
                    JValue::Object(&JObject::null()),
                    JValue::Int(0),
                    JValue::Object(&jlanes_obj),
                    JValue::Object(&JObject::null()),
                    JValue::Int(0),
                    JValue::Int(0),
                    JValue::Int(0),
                    // No MOTIS stop ids on a road-graph step.
                    JValue::Object(&JObject::null()),
                    JValue::Object(&JObject::null()),
                    // WS-G elevation profile: per-coordinate elevations + route totals.
                    JValue::Object(&jelev_obj),
                    JValue::Double(ascent_m),
                    JValue::Double(descent_m),
                ],
            ) {
                Ok(o) => o,
                Err(_) => return Ok(null),
            };
            if array.set_element(env, i, &obj).is_err() {
                return Ok(null);
            }
        }

        Ok(array.into_raw())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

// ---------------------------------------------------------------------------
// Walk-leg road snapper (shared with the transit planner)
// ---------------------------------------------------------------------------

/// How far apart a walk leg's endpoints may be before we stop trying to route it
/// on the road graph. RAPTOR caps access/egress at 1 km and transfers at 400 m,
/// so this only excludes pathological legs.
pub(crate) const WALK_SNAP_MAX_M: f64 = 2_000.0;
/// Per-request budget of road searches. A plan can hold several itineraries with
/// two to four walk legs each, and every search serialises behind the same
/// `route_state()` mutex, on top of RAPTOR.
const WALK_SNAP_MAX_SEARCHES: usize = 8;
/// A road path this many times longer than the straight line is a bad snap (the
/// far side of a motorway, a ferry terminal). Keep the straight line rather than
/// pair a wildly longer polyline with RAPTOR's original duration.
const WALK_SNAP_MAX_RATIO: f64 = 2.5;

/// Approximate ground distance in metres (equirectangular).
fn crow_m(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let dlat = (lat2 - lat1) * 111_320.0;
    let dlon = (lon2 - lon1) * 111_320.0 * ((lat1 + lat2) * 0.5).to_radians().cos();
    (dlat * dlat + dlon * dlon).sqrt()
}

/// The straight line a walk leg was drawn as, when it is worth a road search.
/// A `TransitLeg` carries no endpoints of its own, only that line — which works
/// uniformly for access (user → stop), egress (stop → user) and transfer
/// (stop → stop) legs. Returns `(s_lat, s_lon, e_lat, e_lon)`.
pub(crate) fn walk_snap_endpoints(leg: &crate::transit::TransitLeg) -> Option<(f64, f64, f64, f64)> {
    if leg.kind != crate::transit::LegKind::Walk || leg.dist_m > WALK_SNAP_MAX_M {
        return None;
    }
    let n = leg.coords.len();
    if n < 4 {
        return None;
    }
    Some((leg.coords[1], leg.coords[0], leg.coords[n - 1], leg.coords[n - 2]))
}

/// Whether a `road_m` road path is a plausible redraw of a `crow`-metre straight
/// line. Rejecting the implausible ones keeps the straight line, which reads far
/// better than a wildly longer polyline paired with RAPTOR's original duration.
pub(crate) fn walk_snap_plausible(crow: f64, road_m: f64) -> bool {
    crow <= 1.0 || road_m <= crow * WALK_SNAP_MAX_RATIO
}

/// Redraw each walk leg along the road graph, in place.
///
/// Only `coords` and `dist_m` change. `dep_secs`/`arr_secs` are what RAPTOR
/// planned the journey around, and re-timing them would desynchronise it from
/// the departure it was built for — so a road-routed walk, being longer than the
/// crow-flies estimate RAPTOR used, leaves a shown ETA slightly optimistic. That
/// is the same heuristic the pack's TRANSFERS table is already built on, and the
/// ratio guard bounds how wrong the drawn line can get.
pub(crate) fn snap_walk_legs(legs: &mut [crate::transit::TransitLeg]) {
    let g = match graph() {
        Some(g) => g,
        None => return,
    };
    let speeds: TrafficSpeeds = traffic_speeds().read().map(|m| m.clone()).unwrap_or_default();
    let mut state = match route_state().lock() {
        Ok(s) => s,
        Err(_) => return,
    };
    let RouteState { scratch, heap } = &mut *state;
    // WALK, not PUBLIC_TRANSIT: `is_mode_allowed` grants them the same road types
    // today, but WALK states the intent and cannot drift.
    let mut no_traffic = |_: i32, _: i32| {};
    let mut budget = WALK_SNAP_MAX_SEARCHES;

    for leg in legs.iter_mut() {
        if budget == 0 {
            break;
        }
        let (s_lat, s_lon, e_lat, e_lon) = match walk_snap_endpoints(leg) {
            Some(p) => p,
            None => continue,
        };
        budget -= 1;

        let mut ctx = match prepare_routing(
            &g, &speeds, &mut no_traffic, s_lat, s_lon, e_lat, e_lon, WALK, scratch, heap,
        ) {
            Some(c) => c,
            None => continue,
        };
        perform_search_loop(&g, &speeds, &mut no_traffic, WALK, &mut ctx, scratch, heap);
        if ctx.target_node == 0xFFFF_FFFF && ctx.direct.is_none() {
            continue;
        }

        // Consecutive steps repeat their joint vertex, so drop each step's first
        // point after the first step.
        let steps = reconstruct_path(&g, &speeds, WALK, &ctx, scratch);
        let mut coords: Vec<f64> = Vec::new();
        let mut dist_mm = 0u64;
        for step in &steps {
            dist_mm += step.dist_mm;
            let skip = if coords.is_empty() { 0 } else { 2 };
            if step.coords.len() > skip {
                coords.extend_from_slice(&step.coords[skip..]);
            }
        }
        if coords.len() < 4 {
            continue;
        }
        // The search starts and ends at a projection onto the nearest road, not
        // at the stop itself. Stitch the true endpoints back on so the drawn walk
        // meets its stop marker, and count those stubs.
        let mut road_m = dist_mm as f64 / 1000.0;
        road_m += crow_m(s_lat, s_lon, coords[1], coords[0]);
        road_m += crow_m(coords[coords.len() - 1], coords[coords.len() - 2], e_lat, e_lon);
        if !walk_snap_plausible(leg.dist_m, road_m) {
            continue;
        }
        let mut stitched = Vec::with_capacity(coords.len() + 4);
        stitched.push(s_lon);
        stitched.push(s_lat);
        stitched.extend_from_slice(&coords);
        stitched.push(e_lon);
        stitched.push(e_lat);
        leg.coords = stitched;
        leg.dist_m = road_m;
    }
}
