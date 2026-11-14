    use super::*;

    // --- A minimal TRX2 writer (v3 or v4), so the planner can be exercised
    // without a pack file. It is deliberately independent of
    // `scripts/maps/gtfs_ingest`: if the two drift, these tests fail, which is
    // the point (the real writer lives in another crate and cannot be linked).

    const GRID_CELL_E7: u32 = 200_000; // 0.02 deg, as the ingest tool uses
    const MAX_TRANSFER_M: f64 = 400.0;

    struct Stop {
        lat: f64,
        lon: f64,
        name: &'static str,
        code: &'static str,
        /// Raw GTFS `stop_id`, baked into v5's STOP_GTFS_ID section.
        gtfs_id: &'static str,
    }

    struct Trip {
        start: u32,
        /// `(arr, dep)` per pattern position; `arr[0]` may equal `start`.
        stoptimes: Vec<(u32, u32)>,
        service: u32,
        headsign: &'static str,
    }

    /// A GTFS `shapes.txt` polyline plus, parallel to the route's pattern, each
    /// stop's vertex index within it.
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
        /// v4 ride geometry. `None` reproduces a route the ingester could not
        /// attach a shape to, and is what every route in a v3 blob looks like.
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
        /// `(service_idx, yyyymmdd, added)`, in any order.
        exceptions: Vec<(u32, u32, u32)>,
        /// `(feed name, IANA timezone, Transitous MOTIS prefix)`. An empty prefix
        /// reproduces a feed whose id space the build could not derive.
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
        /// Serialize to a TRX2 blob at `version`. Overridable so both a
        /// stale-pack rejection and the older-format guards can be tested; below
        /// v4 the shape sections are omitted, below v5 the MOTIS id sections are.
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

            // Profiles, deduplicated by encoded body exactly as the writer does.
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
            // Deduplicated by encoded body, standing in for the real writer's
            // dedup by `shape_id`.
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
                // The v6 CSR prefix: this route's first trip in the strided table.
                u32b(&mut sec_route_trip_off, trip_total as u32);
                let mut prev_start = 0u32;
                for t in &ordered {
                    let pid = intern_profile(&t.stoptimes);
                    uvarint(&mut sec_route_trips, t.start.saturating_sub(prev_start) as u64);
                    uvarint(&mut sec_route_trips, pid as u64);
                    uvarint(&mut sec_route_trips, t.service as u64);
                    let h = pool.intern(t.headsign);
                    uvarint(&mut sec_route_trips, h as u64);
                    // Both forms are written, so a v6 blob and the v3-v5 blob it would
                    // have been carry the same trips and the two reader paths can be
                    // compared directly.
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

            // Footpath transfers: every pair within MAX_TRANSFER_M.
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

            // Sorted by (service, date) with a CSR index, as v3 requires.
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

            // Sparse grid, keyed by cell id, ascending.
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
            // The shape sections exist only from v4, so a v3 blob is byte-identical
            // to what the previous format produced.
            if version >= 4 {
                u32b(&mut sec_route_shape_idx, shape_bytes.len() as u32);
                sections.push(&shape_bytes);
                sections.push(&sec_route_shape_idx);
                sections.push(&sec_route_stop_shape);
            }
            // Likewise the MOTIS id sections exist only from v5.
            if version >= 5 {
                sections.push(&sec_feed_motis_prefix);
                sections.push(&sec_stop_gtfs_id);
            }
            // v6 adds the fixed-stride trip table and its CSR directory. The prefix
            // needs its final total, which is only known once every route is written.
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

        /// The planet5 (TRIX v8) pack every test reads: the v7 layout with every non-empty
        /// section wrapped in a ZSEC block section — so the whole suite runs through the
        /// block-cache read path. Small blocks (64 bytes) so records straddle block edges.
        fn index(&self) -> TransitIndex {
            TransitIndex::from_bytes(zsec_pack(&self.build_with_version(7), 64)).expect("index loads")
        }

        /// The same pack as raw v7 sections (planet4's layout).
        fn index_v7(&self) -> TransitIndex {
            TransitIndex::from_bytes(self.build_with_version(7)).expect("index loads")
        }
    }

    /// Rewrite a raw TRIX pack as v8: each non-empty section a ZSEC section of `block`-byte
    /// blocks (the real packs use 64 KiB; tests use tiny blocks to force straddling reads).
    fn zsec_pack(v7: &[u8], block: usize) -> Vec<u8> {
        let count = u32::from_le_bytes(v7[8..12].try_into().unwrap()) as usize;
        let mut out = v7[..HEADER_LEN].to_vec();
        out[4..8].copy_from_slice(&tilecodec::mamaps::archive::TRANSIT_VERSION_ZSEC.to_le_bytes());
        let dir_at = out.len();
        out.resize(dir_at + count * 16, 0);
        for i in 0..count {
            let at = HEADER_LEN + i * 16;
            let off = u64::from_le_bytes(v7[at..at + 8].try_into().unwrap()) as usize;
            let len = u64::from_le_bytes(v7[at + 8..at + 16].try_into().unwrap()) as usize;
            let z = if len == 0 { Vec::new() } else { tilecodec::zsec::write_zsec(&v7[off..off + len], block, 3) };
            while out.len() % 8 != 0 {
                out.push(0);
            }
            let o = out.len() as u64;
            out.extend_from_slice(&z);
            let d = dir_at + i * 16;
            out[d..d + 8].copy_from_slice(&o.to_le_bytes());
            out[d + 8..d + 16].copy_from_slice(&(z.len() as u64).to_le_bytes());
        }
        out
    }
