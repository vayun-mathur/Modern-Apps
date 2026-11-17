/// Simulate the position of every in-service transit vehicle within the bbox at
/// `now` (seconds since the query day's local midnight; see [`QueryDay`]).
///
/// For each trip that runs on the query service day (or the previous one, for a
/// `>24:00:00` overnight trip) and is currently between its first departure and
/// last arrival, this finds the two stops bracketing `now`, applies the realtime
/// [`DelayOverlay`] (skipping cancelled trips), lerps along the polyline between
/// them, and takes the bearing from the direction of travel. Reuses the same
/// read-only shape/profile helpers as the planner; it changes no on-disk format.
///
/// Enumeration is bounded to the bbox: a route none of whose stops fall in the
/// bbox (plus a small margin) is skipped before its trips are scanned.
pub fn active_vehicles(
    idx: &TransitIndex,
    now: u32,
    schedule: Schedule,
    min_lat: f64,
    min_lon: f64,
    max_lat: f64,
    max_lon: f64,
) -> Vec<Vehicle> {
    let mut out: Vec<Vehicle> = Vec::new();
    if idx.stop_count == 0 {
        return out;
    }
    let m = VEHICLE_BBOX_MARGIN_DEG;
    let in_bbox = |lat: f64, lon: f64| {
        lat >= min_lat && lat <= max_lat && lon >= min_lon && lon <= max_lon
    };
    let near_bbox = |lat: f64, lon: f64| {
        lat >= min_lat - m && lat <= max_lat + m && lon >= min_lon - m && lon <= max_lon + m
    };

    let mut prof_cache: HashMap<u32, ProfileDec> = HashMap::new();
    for r in 0..idx.route_count {
        let route = idx.route(r);
        if route.n_stops < 2 {
            continue;
        }
        // Bbox cull: skip the whole route (and its trip scan) unless one of its
        // stops falls in (or just outside) the visible bbox.
        let mut serves_bbox = false;
        for pos in 0..route.n_stops {
            let (lat, lon) = idx.stop_ll(idx.route_stop(route.first_route_stop + pos));
            if near_bbox(lat, lon) {
                serves_bbox = true;
                break;
            }
        }
        if !serves_bbox {
            continue;
        }

        let trips = idx.trips_for(r, &route);
        for ti in 0..trips.len() {
            let Some(td) = trips.get(ti) else { break };
            // The previous service day contributes its `>24:00:00` trips to the
            // query day; try it first (earlier times), then today.
            for prev_day in [true, false] {
                if !schedule.runs(idx, td.service_idx, prev_day) {
                    continue;
                }
                let (arr_rel, dep_rel) = {
                    let p = get_profile(&mut prof_cache, idx, td.profile_id);
                    (p.arr_rel.clone(), p.dep_rel.clone())
                };
                let n = (route.n_stops as usize).min(arr_rel.len()).min(dep_rel.len());
                if n < 2 {
                    continue;
                }
                let day_shift = if prev_day { SECS_PER_DAY as i64 } else { 0 };
                // A previous-day trip only counts if it actually runs into the
                // query day (its last arrival is at or past midnight).
                let last_arr_frame = td.start_time as i64 + arr_rel[n - 1] as i64 - day_shift;
                if last_arr_frame < 0 {
                    continue;
                }

                // Resolve the trip-wide realtime shift and cancellation from the
                // overlay, keyed on each stop's scheduled departure (query-day
                // frame). A delay is trip-wide, so the first match applies to the
                // whole run; any cancellation suppresses the vehicle.
                let mut cancelled = false;
                let mut delay_secs = 0i32;
                let mut found_delay = false;
                for (p, dep) in dep_rel.iter().enumerate().take(n) {
                    let sched_dep = td.start_time as i64 + *dep as i64 - day_shift;
                    if sched_dep < 0 {
                        continue;
                    }
                    if let Some(adj) = schedule.adjustment(r, p as u32, sched_dep as u32) {
                        if adj.cancelled {
                            cancelled = true;
                        }
                        if !found_delay {
                            delay_secs = adj.delay_secs;
                            found_delay = true;
                        }
                    }
                }
                if cancelled {
                    continue;
                }

                let arr_t = |p: usize| {
                    delayed(abs_time_on(td.start_time, arr_rel[p], prev_day), delay_secs)
                };
                let dep_t = |p: usize| {
                    delayed(abs_time_on(td.start_time, dep_rel[p], prev_day), delay_secs)
                };
                // In service only between the first departure and last arrival.
                if now < dep_t(0) || now > arr_t(n - 1) {
                    continue;
                }

                // Locate `now`: dwelling at a stop, or travelling between two.
                let mut placement: Option<(usize, usize, f64)> = None;
                for p in 0..n {
                    if now >= arr_t(p) && now <= dep_t(p) {
                        placement = Some((p, p, 0.0));
                        break;
                    }
                    if p + 1 < n && now > dep_t(p) && now < arr_t(p + 1) {
                        let denom = arr_t(p + 1).saturating_sub(dep_t(p)) as f64;
                        let frac = if denom <= 0.0 {
                            0.0
                        } else {
                            (now - dep_t(p)) as f64 / denom
                        };
                        placement = Some((p, p + 1, frac));
                        break;
                    }
                }
                let Some((from_pos, to_pos, frac)) = placement else {
                    continue;
                };

                let (lat, lon, bearing) = if from_pos == to_pos {
                    // Sitting at a stop: the exact stop coordinate, headed toward
                    // the next stop (or, at the terminus, along the last segment).
                    let here = idx.stop_ll(idx.route_stop(route.first_route_stop + from_pos as u32));
                    let bearing = if from_pos + 1 < n {
                        let nxt = idx
                            .stop_ll(idx.route_stop(route.first_route_stop + from_pos as u32 + 1));
                        bearing_deg(here, nxt)
                    } else if from_pos > 0 {
                        let prv = idx
                            .stop_ll(idx.route_stop(route.first_route_stop + from_pos as u32 - 1));
                        bearing_deg(prv, here)
                    } else {
                        0.0
                    };
                    (here.0, here.1, bearing)
                } else {
                    let pts = segment_points(idx, r, &route, from_pos as u32, to_pos as u32);
                    interp_along(&pts, frac)
                };

                if !in_bbox(lat, lon) {
                    continue;
                }

                out.push(Vehicle {
                    lon,
                    lat,
                    bearing,
                    route_color: route.color,
                    route_type: route.route_type,
                    id: ((r as i64) << 32) | ((ti as i64) << 1) | prev_day as i64,
                });
                if out.len() >= MAX_VEHICLES {
                    return out;
                }
                // A trip is placed at most once (today wins over the previous-day
                // sweep once both could match), so stop after the first hit.
                break;
            }
        }
    }
    out
}

const _: () = {
    // Compile-time assertions that fixed on-disk record sizes match the writer.
    assert!(std::mem::size_of::<StopRec>() == 16);
    assert!(std::mem::size_of::<RouteRec>() == 32);
    assert!(std::mem::size_of::<TransferRec>() == 8);
    assert!(std::mem::size_of::<ServiceRec>() == 12);
    assert!(std::mem::size_of::<ExcRec>() == 12);
};
