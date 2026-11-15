//! A minimal TRIX pack writer for the synthetic-archive tests.
//!
//! Mirrors the test writer in `src/transit_part6.rs` (which cannot be
//! linked from an integration test): if the two drift, these tests
//! fail, which is the point. Builds the same three-stop pack the unit
//! tests plan over, at raw v7 (planet4's layout).


use std::collections::HashMap;

const NONE: u32 = 0xFFFF_FFFF;
const HEADER_LEN: usize = 80;
const MAGIC: u32 = tilecodec::mamaps::archive::TRANSIT_MAGIC;
const GRID_CELL_E7: u32 = 200_000;
const MAX_TRANSFER_M: f64 = 400.0;
const WALK_SPEED_M_S: f64 = 4.5 / 3.6;

fn dist_m(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let dlat = (lat2 - lat1) * 111_320.0;
    let mean = ((lat1 + lat2) * 0.5).to_radians();
    let dlon = (lon2 - lon1) * 111_320.0 * mean.cos();
    (dlat * dlat + dlon * dlon).sqrt()
}

struct Stop {
    lat: f64,
    lon: f64,
    name: &'static str,
    code: &'static str,
    gtfs_id: &'static str,
}

struct Trip {
    start: u32,
    stoptimes: Vec<(u32, u32)>,
    service: u32,
    headsign: &'static str,
}

struct Shape {
    points: Vec<(f64, f64)>,
    stop_vertices: Vec<u32>,
}

struct Route {
    name: &'static str,
    color: u32,
    route_type: u32,
    feed: u32,
    pattern: Vec<u32>,
    trips: Vec<Trip>,
    shape: Option<Shape>,
}

struct Service {
    mask: u8,
    start: u32,
    end: u32,
}

struct Pack {
    stops: Vec<Stop>,
    routes: Vec<Route>,
    services: Vec<Service>,
    exceptions: Vec<(u32, u32, u32)>,
    feeds: Vec<(&'static str, &'static str, &'static str)>,
}

#[derive(Default)]
struct Pool {
    bytes: Vec<u8>,
    map: HashMap<String, u32>,
}

impl Pool {
    fn new() -> Pool {
        Pool { bytes: vec![0], map: HashMap::new() }
    }
    fn intern(&mut self, s: &str) -> u32 {
        if s.is_empty() {
            return NONE;
        }
        if let Some(&o) = self.map.get(s) {
            return o;
        }
        let off = self.bytes.len() as u32;
        self.bytes.extend_from_slice(s.as_bytes());
        self.bytes.push(0);
        self.map.insert(s.to_string(), off);
        off
    }
}

fn u32b(v: &mut Vec<u8>, x: u32) {
    v.extend_from_slice(&x.to_le_bytes());
}
fn i32b(v: &mut Vec<u8>, x: i32) {
    v.extend_from_slice(&x.to_le_bytes());
}
fn uvarint(v: &mut Vec<u8>, mut x: u64) {
    loop {
        let b = (x & 0x7f) as u8;
        x >>= 7;
        if x != 0 {
            v.push(b | 0x80);
        } else {
            v.push(b);
            break;
        }
    }
}
fn zigzag_varint(v: &mut Vec<u8>, x: i64) {
    uvarint(v, ((x << 1) ^ (x >> 63)) as u64);
}

impl Pack {
    fn build_with_version(&self, version: u32) -> Vec<u8> {
        let mut pool = Pool::new();
        let pack_name_off = pool.intern("testpack");

        let n_stops = self.stops.len();
        let mut sec_stops = Vec::new();
        let mut sec_stop_gtfs_id = Vec::new();
        let mut sec_route_trip_recs = Vec::new();
        let mut sec_route_trip_off = Vec::new();
        let (mut min_lat, mut min_lon) = (i32::MAX, i32::MAX);
        let (mut max_lat, mut max_lon) = (i32::MIN, i32::MIN);
        let lat_e7: Vec<i32> = self.stops.iter().map(|s| (s.lat * 1e7) as i32).collect();
        let lon_e7: Vec<i32> = self.stops.iter().map(|s| (s.lon * 1e7) as i32).collect();
        for i in 0..n_stops {
            min_lat = min_lat.min(lat_e7[i]);
            min_lon = min_lon.min(lon_e7[i]);
            max_lat = max_lat.max(lat_e7[i]);
            max_lon = max_lon.max(lon_e7[i]);
        }
        for (i, s) in self.stops.iter().enumerate() {
            i32b(&mut sec_stops, lat_e7[i]);
            i32b(&mut sec_stops, lon_e7[i]);
            let n = pool.intern(s.name);
            let c = pool.intern(s.code);
            u32b(&mut sec_stops, n);
            u32b(&mut sec_stops, c);
            let g = pool.intern(s.gtfs_id);
            u32b(&mut sec_stop_gtfs_id, g);
        }

        let mut profile_bytes: Vec<u8> = Vec::new();
        let mut profile_offsets: Vec<u32> = Vec::new();
        let mut profile_ids: HashMap<Vec<u8>, u32> = HashMap::new();
        let mut intern_profile = |sts: &[(u32, u32)]| -> u32 {
            let mut body = Vec::new();
            uvarint(&mut body, sts.len() as u64);
            let (arr0, dep0) = sts[0];
            uvarint(&mut body, dep0.saturating_sub(arr0) as u64);
            let mut prev_dep = dep0;
            for &(arr, dep) in &sts[1..] {
                uvarint(&mut body, arr.saturating_sub(prev_dep) as u64);
                uvarint(&mut body, dep.saturating_sub(arr) as u64);
                prev_dep = dep;
            }
            if let Some(&id) = profile_ids.get(&body) {
                return id;
            }
            let id = profile_offsets.len() as u32;
            profile_offsets.push(profile_bytes.len() as u32);
            profile_bytes.extend_from_slice(&body);
            profile_ids.insert(body, id);
            id
        };

        let mut sec_routes = Vec::new();
        let mut sec_route_stops = Vec::new();
        let mut sec_route_trips = Vec::new();
        let mut sec_route_stop_shape = Vec::new();
        let mut sec_route_shape_idx = Vec::new();
        let mut shape_bytes: Vec<u8> = Vec::new();
        let mut shape_offsets: HashMap<Vec<u8>, u32> = HashMap::new();
        let mut stop_routes: Vec<Vec<(u32, u32)>> = vec![Vec::new(); n_stops];
        let mut trip_total = 0usize;

        for (ridx, r) in self.routes.iter().enumerate() {
            let first_route_stop = (sec_route_stops.len() / 4) as u32;
            for (pos, &s) in r.pattern.iter().enumerate() {
                u32b(&mut sec_route_stops, s);
                let vertex = r
                    .shape
                    .as_ref()
                    .and_then(|sh| sh.stop_vertices.get(pos).copied())
                    .unwrap_or(NONE);
                u32b(&mut sec_route_stop_shape, vertex);
                let sr = &mut stop_routes[s as usize];
                if !sr.iter().any(|&(rr, _)| rr == ridx as u32) {
                    sr.push((ridx as u32, pos as u32));
                }
            }
            let shape_off = match &r.shape {
                None => NONE,
                Some(sh) => {
                    let mut body = Vec::new();
                    u32b(&mut body, sh.points.len() as u32);
                    let (mut plat, mut plon) = (0i64, 0i64);
                    for &(lat, lon) in &sh.points {
                        let lat_e7 = (lat * 1e7).round() as i64;
                        let lon_e7 = (lon * 1e7).round() as i64;
                        zigzag_varint(&mut body, lat_e7 - plat);
                        zigzag_varint(&mut body, lon_e7 - plon);
                        plat = lat_e7;
                        plon = lon_e7;
                    }
                    match shape_offsets.get(&body) {
                        Some(&off) => off,
                        None => {
                            let off = shape_bytes.len() as u32;
                            shape_bytes.extend_from_slice(&body);
                            shape_offsets.insert(body, off);
                            off
                        }
                    }
                }
            };
            u32b(&mut sec_route_shape_idx, shape_off);
            let trips_off = sec_route_trips.len() as u32;
            let mut ordered: Vec<&Trip> = r.trips.iter().collect();
            ordered.sort_by_key(|t| t.start);
            u32b(&mut sec_route_trip_off, trip_total as u32);
            let mut prev_start = 0u32;
            for t in &ordered {
                let pid = intern_profile(&t.stoptimes);
                uvarint(&mut sec_route_trips, t.start.saturating_sub(prev_start) as u64);
                uvarint(&mut sec_route_trips, pid as u64);
                uvarint(&mut sec_route_trips, t.service as u64);
                let h = pool.intern(t.headsign);
                uvarint(&mut sec_route_trips, h as u64);
                u32b(&mut sec_route_trip_recs, t.start);
                u32b(&mut sec_route_trip_recs, pid);
                u32b(&mut sec_route_trip_recs, t.service);
                u32b(&mut sec_route_trip_recs, h);
                prev_start = t.start;
                trip_total += 1;
            }
            let name_off = pool.intern(r.name);
            u32b(&mut sec_routes, name_off);
            u32b(&mut sec_routes, r.color);
            u32b(&mut sec_routes, r.route_type);
            u32b(&mut sec_routes, r.feed);
            u32b(&mut sec_routes, r.pattern.len() as u32);
            u32b(&mut sec_routes, first_route_stop);
            u32b(&mut sec_routes, ordered.len() as u32);
            u32b(&mut sec_routes, trips_off);
        }

        let mut sec_profiles_idx = Vec::new();
        for &o in &profile_offsets {
            u32b(&mut sec_profiles_idx, o);
        }
        u32b(&mut sec_profiles_idx, profile_bytes.len() as u32);

        let mut sec_stop_routes = Vec::new();
        let mut sec_stop_routes_idx = Vec::new();
        let mut sec_stop_route_pos = Vec::new();
        let mut acc = 0u32;
        for sr in &stop_routes {
            u32b(&mut sec_stop_routes_idx, acc);
            for &(r, pos) in sr {
                u32b(&mut sec_stop_routes, r);
                u32b(&mut sec_stop_route_pos, pos);
            }
            acc += sr.len() as u32;
        }
        u32b(&mut sec_stop_routes_idx, acc);

        let mut sec_transfers = Vec::new();
        let mut sec_transfers_idx = Vec::new();
        let mut tacc = 0u32;
        for i in 0..n_stops {
            u32b(&mut sec_transfers_idx, tacc);
            for j in 0..n_stops {
                if i == j {
                    continue;
                }
                let d = dist_m(
                    self.stops[i].lat,
                    self.stops[i].lon,
                    self.stops[j].lat,
                    self.stops[j].lon,
                );
                if d <= MAX_TRANSFER_M {
                    u32b(&mut sec_transfers, j as u32);
                    u32b(&mut sec_transfers, (d / WALK_SPEED_M_S).ceil() as u32);
                    tacc += 1;
                }
            }
        }
        u32b(&mut sec_transfers_idx, tacc);

        let mut sec_services = Vec::new();
        for s in &self.services {
            sec_services.push(s.mask);
            sec_services.extend_from_slice(&[0u8, 0, 0]);
            u32b(&mut sec_services, s.start);
            u32b(&mut sec_services, s.end);
        }

        let mut exc = self.exceptions.clone();
        exc.sort_by_key(|&(s, d, _)| (s, d));
        let mut sec_exceptions = Vec::new();
        let mut sec_exceptions_idx = Vec::new();
        let mut eacc = 0u32;
        let mut ei = 0usize;
        for s in 0..self.services.len() as u32 {
            u32b(&mut sec_exceptions_idx, eacc);
            while exc.get(ei).is_some_and(|&(sidx, _, _)| sidx == s) {
                let (sidx, date, added) = exc[ei];
                u32b(&mut sec_exceptions, sidx);
                u32b(&mut sec_exceptions, date);
                u32b(&mut sec_exceptions, added);
                eacc += 1;
                ei += 1;
            }
        }
        u32b(&mut sec_exceptions_idx, eacc);

        let cell_col = |lon: i32| ((lon as i64 - min_lon as i64) / GRID_CELL_E7 as i64).max(0);
        let cell_row = |lat: i32| ((lat as i64 - min_lat as i64) / GRID_CELL_E7 as i64).max(0);
        let grid_cols = (cell_col(max_lon) + 1) as u32;
        let grid_rows = (cell_row(max_lat) + 1) as u32;
        let mut grid: HashMap<u32, Vec<u32>> = HashMap::new();
        for i in 0..n_stops {
            let cid = cell_row(lat_e7[i]) as u32 * grid_cols + cell_col(lon_e7[i]) as u32;
            grid.entry(cid).or_default().push(i as u32);
        }
        let mut cell_ids: Vec<u32> = grid.keys().copied().collect();
        cell_ids.sort_unstable();
        let mut sec_grid_cell_ids = Vec::new();
        let mut sec_grid_cell_off = Vec::new();
        let mut sec_grid_stops = Vec::new();
        let mut gacc = 0u32;
        for &cid in &cell_ids {
            u32b(&mut sec_grid_cell_ids, cid);
            u32b(&mut sec_grid_cell_off, gacc);
            for &s in &grid[&cid] {
                u32b(&mut sec_grid_stops, s);
            }
            gacc += grid[&cid].len() as u32;
        }
        u32b(&mut sec_grid_cell_off, gacc);

        let mut sec_feeds = Vec::new();
        let mut sec_feed_tz = Vec::new();
        let mut sec_feed_motis_prefix = Vec::new();
        for &(name, tz, motis) in &self.feeds {
            let n = pool.intern(name);
            let t = pool.intern(tz);
            let m = pool.intern(motis);
            u32b(&mut sec_feeds, n);
            u32b(&mut sec_feed_tz, t);
            u32b(&mut sec_feed_motis_prefix, m);
        }

        let mut sections: Vec<&[u8]> = vec![
            &pool.bytes,
            &sec_stops,
            &sec_routes,
            &sec_route_stops,
            &sec_route_trips,
            &profile_bytes,
            &sec_profiles_idx,
            &sec_stop_routes,
            &sec_stop_routes_idx,
            &sec_transfers,
            &sec_transfers_idx,
            &sec_services,
            &sec_exceptions,
            &sec_grid_cell_ids,
            &sec_grid_cell_off,
            &sec_grid_stops,
            &sec_feeds,
            &sec_feed_tz,
            &sec_exceptions_idx,
            &sec_stop_route_pos,
        ];
        if version >= 4 {
            u32b(&mut sec_route_shape_idx, shape_bytes.len() as u32);
            sections.push(&shape_bytes);
            sections.push(&sec_route_shape_idx);
            sections.push(&sec_route_stop_shape);
        }
        if version >= 5 {
            sections.push(&sec_feed_motis_prefix);
            sections.push(&sec_stop_gtfs_id);
        }
        if version >= 6 {
            u32b(&mut sec_route_trip_off, trip_total as u32);
            sections.push(&sec_route_trip_recs);
            sections.push(&sec_route_trip_off);
        }
        let section_count = sections.len();

        let align = |o: usize| (o + 7) & !7;
        let mut data_off = HEADER_LEN + section_count * 16;
        let mut dir: Vec<(u64, u64)> = Vec::new();
        for s in sections.iter() {
            data_off = align(data_off);
            dir.push((data_off as u64, s.len() as u64));
            data_off += s.len();
        }

        let mut out = Vec::with_capacity(data_off);
        u32b(&mut out, MAGIC);
        u32b(&mut out, version);
        u32b(&mut out, section_count as u32);
        u32b(&mut out, n_stops as u32);
        u32b(&mut out, self.routes.len() as u32);
        u32b(&mut out, trip_total as u32);
        u32b(&mut out, self.services.len() as u32);
        u32b(&mut out, profile_offsets.len() as u32);
        u32b(&mut out, self.feeds.len() as u32);
        u32b(&mut out, cell_ids.len() as u32);
        u32b(&mut out, pack_name_off);
        i32b(&mut out, min_lat);
        i32b(&mut out, min_lon);
        i32b(&mut out, max_lat);
        i32b(&mut out, max_lon);
        i32b(&mut out, min_lat);
        i32b(&mut out, min_lon);
        u32b(&mut out, GRID_CELL_E7);
        u32b(&mut out, grid_cols);
        u32b(&mut out, grid_rows);
        assert_eq!(out.len(), HEADER_LEN);
        for (off, len) in &dir {
            out.extend_from_slice(&off.to_le_bytes());
            out.extend_from_slice(&len.to_le_bytes());
        }
        for s in sections.iter() {
            out.resize(align(out.len()), 0);
            out.extend_from_slice(s);
        }
        out
    }
}

pub fn synthetic_pack() -> Vec<u8> {
    one_route_pack().build_with_version(7)
}

fn weekdays() -> Service {
    Service { mask: 0b0011_1111 & 0b0001_1111, start: 20_240_101, end: 20_241_231 }
}

fn one_route_pack() -> Pack {
    Pack {
        stops: vec![
            Stop { lat: 37.700, lon: -122.400, name: "Alpha", code: "A1", gtfs_id: "901201" },
            Stop { lat: 37.710, lon: -122.400, name: "Beta", code: "B2", gtfs_id: "901202" },
            Stop { lat: 37.720, lon: -122.400, name: "Gamma", code: "C3", gtfs_id: "901203" },
        ],
        routes: vec![Route {
            name: "N",
            color: 0x0000FF,
            route_type: 0,
            feed: 0,
            pattern: vec![0, 1, 2],
            trips: vec![
                Trip {
                    start: 28_800,
                    stoptimes: vec![(28_800, 28_800), (29_100, 29_160), (29_400, 29_400)],
                    service: 0,
                    headsign: "Downtown",
                },
                Trip {
                    start: 32_400,
                    stoptimes: vec![(32_400, 32_400), (32_700, 32_760), (33_000, 33_000)],
                    service: 0,
                    headsign: "Downtown",
                },
            ],
            shape: None,
        }],
        services: vec![weekdays()],
        exceptions: Vec::new(),
        feeds: vec![("sfmuni", "America/Los_Angeles", "us-ca-SFMTA")],
    }
}
