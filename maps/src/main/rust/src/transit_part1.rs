impl TransitIndex {
    /// Load `<base_dir>/<feed>.transit`. Returns `None` if absent or malformed.
    pub fn load(base_dir: &str, feed: &str) -> Option<TransitIndex> {
        let mut path = base_dir.to_string();
        if !path.is_empty() && !path.ends_with('/') {
            path.push('/');
        }
        path.push_str(feed);
        path.push_str(".transit");

        TransitIndex::parse(Backing::Mmap(MmapRegion::map(&path)?))
    }

    /// Build an index over an in-memory TRX2 blob, so the planner can be tested
    /// without a pack file (the crate's mmap loader is Unix-only).
    #[cfg(test)]
    pub fn from_bytes(bytes: Vec<u8>) -> Option<TransitIndex> {
        TransitIndex::parse(Backing::Owned(bytes))
    }

    /// Validate the TRX2 header + section directory. A pack whose version this
    /// build does not know is rejected here, which is what lets the caller treat
    /// offline transit as unavailable rather than misread it. Versions
    /// `VERSION_MIN..=VERSION` are all accepted; the directory is sized from the
    /// header's own `section_count`, so an older pack loads with the newer
    /// sections left empty and every read of them gated on a non-zero length.
    fn parse(backing: Backing) -> Option<TransitIndex> {
        if backing.len() < HEADER_LEN {
            return None;
        }
        let base = backing.base();
        let magic: u32 = unsafe { read_at::<u32>(base, 0) };
        let version: u32 = unsafe { read_at::<u32>(base, 1) };
        if magic != MAGIC || !(VERSION_MIN..=VERSION).contains(&version) {
            return None;
        }
        let section_count = unsafe { read_at::<u32>(base, 2) } as usize;
        if section_count < SECTION_COUNT_V3 {
            return None;
        }
        if HEADER_LEN + section_count.saturating_mul(16) > backing.len() {
            return None;
        }
        let stop_count: u32 = unsafe { read_at::<u32>(base, 3) };
        let route_count: u32 = unsafe { read_at::<u32>(base, 4) };
        let service_count: u32 = unsafe { read_at::<u32>(base, 6) };
        let feed_count: u32 = unsafe { read_at::<u32>(base, 8) };
        let grid_cell_count: u32 = unsafe { read_at::<u32>(base, 9) };
        let feed_name_off: u32 = unsafe { read_at::<u32>(base, 10) };
        let min_lat_e7: i32 = unsafe { read_at::<i32>(base, 11) };
        let min_lon_e7: i32 = unsafe { read_at::<i32>(base, 12) };
        let max_lat_e7: i32 = unsafe { read_at::<i32>(base, 13) };
        let max_lon_e7: i32 = unsafe { read_at::<i32>(base, 14) };
        let grid_lat0_e7: i32 = unsafe { read_at::<i32>(base, 15) };
        let grid_lon0_e7: i32 = unsafe { read_at::<i32>(base, 16) };
        let grid_cell_e7: u32 = unsafe { read_at::<u32>(base, 17) };
        let grid_cols: u32 = unsafe { read_at::<u32>(base, 18) };

        // Directory: section_count * (u64 offset, u64 len) starting at HEADER_LEN.
        // A newer pack may carry more sections than this build knows; ignore the
        // tail rather than rejecting it.
        let dir_base = unsafe { base.add(HEADER_LEN) };
        let mut sec = [(0usize, 0usize); SECTION_COUNT];
        let mut zsec = [None; SECTION_COUNT];
        for (i, s) in sec.iter_mut().enumerate().take(section_count.min(SECTION_COUNT)) {
            let off: u64 = unsafe { read_at::<u64>(dir_base, i * 2) };
            let len_word: u64 = unsafe { read_at::<u64>(dir_base, i * 2 + 1) };
            // v8: bit 63 of a directory length marks a ZSEC block section (see
            // TRANSIT_ZSEC_FLAG); the rest is the stored length either way.
            let zflag = version >= tilecodec::mamaps::archive::TRANSIT_VERSION_ZSEC
                && len_word & tilecodec::mamaps::archive::TRANSIT_ZSEC_FLAG != 0;
            let len = len_word & !tilecodec::mamaps::archive::TRANSIT_ZSEC_FLAG;
            if off as usize + len as usize > backing.len() {
                return None;
            }
            *s = (off as usize, len as usize);
            // Validate a ZSEC section's table now and expose its *raw* length, which is what
            // every bound check below compares against.
            if zflag && len > 0 {
                let bytes = unsafe { std::slice::from_raw_parts(base.add(off as usize), len as usize) };
                let z = tilecodec::zsec::ZSec::parse(bytes).ok()?;
                if z.block_size() == 0 || z.block_size() > (1 << 20) {
                    return None;
                }
                zsec[i] = Some((off as usize, len as usize, z.block_size()));
                *s = (off as usize, z.raw_len() as usize);
            }
        }

        Some(TransitIndex {
            _backing: backing,
            base,
            stop_count,
            route_count,
            service_count,
            feed_count,
            feed_name_off,
            min_lat_e7,
            min_lon_e7,
            max_lat_e7,
            max_lon_e7,
            grid_lat0_e7,
            grid_lon0_e7,
            grid_cell_e7,
            grid_cols,
            grid_cell_count,
            sec,
            zsec,
            uid: NEXT_TRANSIT_UID.fetch_add(1, std::sync::atomic::Ordering::Relaxed),
            intercity: std::sync::OnceLock::new(),
        })
    }

    fn sec_ptr(&self, section: usize) -> *const u8 {
        unsafe { self.base.add(self.sec[section].0) }
    }

    /// Read an unsigned LEB128 varint from `section` at byte position `pos`,
    /// advancing it. Mirrors `write_uvarint` in the producer.
    fn uvarint(&self, section: usize, pos: &mut usize) -> u64 {
        let (off, len) = self.sec[section];
        let mut result = 0u64;
        let mut shift = 0u32;
        loop {
            if *pos >= len {
                break;
            }
            let b = if self.zsec[section].is_some() {
                self.byte(section, *pos)
            } else {
                unsafe { *self.base.add(off + *pos) }
            };
            *pos += 1;
            result |= ((b & 0x7f) as u64) << shift;
            if b & 0x80 == 0 {
                break;
            }
            shift += 7;
        }
        result
    }

    fn read_str(&self, off: u32) -> String {
        if off == NONE {
            return String::new();
        }
        let (start, len) = self.sec[SEC_STRINGS];
        if off as usize >= len {
            return String::new();
        }
        if self.zsec[SEC_STRINGS].is_some() {
            let mut bytes = Vec::new();
            let mut at = off as usize;
            while at < len {
                let b = self.byte(SEC_STRINGS, at);
                if b == 0 {
                    break;
                }
                bytes.push(b);
                at += 1;
            }
            return String::from_utf8_lossy(&bytes).into_owned();
        }
        unsafe {
            let p = self.base.add(start + off as usize);
            let mut n = 0usize;
            while off as usize + n < len && *p.add(n) != 0 {
                n += 1;
            }
            String::from_utf8_lossy(std::slice::from_raw_parts(p, n)).into_owned()
        }
    }

    fn stop(&self, i: u32) -> StopRec {
        self.rd::<StopRec>(SEC_STOPS, i as usize)
    }
    fn route(&self, i: u32) -> RouteRec {
        self.rd::<RouteRec>(SEC_ROUTES, i as usize)
    }
    fn route_stop(&self, i: u32) -> u32 {
        self.rd::<u32>(SEC_ROUTE_STOPS, i as usize)
    }
    fn stop_routes_range(&self, stop: u32) -> (u32, u32) {
        let idx = SEC_STOP_ROUTES_IDX;
        let s = self.rd::<u32>(idx, stop as usize);
        let e = self.rd::<u32>(idx, stop as usize + 1);
        (s, e)
    }
    fn stop_route(&self, i: u32) -> u32 {
        self.rd::<u32>(SEC_STOP_ROUTES, i as usize)
    }
    /// Position of the stop within that route's stop pattern, parallel to
    /// [`Self::stop_route`] (first occurrence, as the writer records it).
    fn stop_route_pos(&self, i: u32) -> u32 {
        self.rd::<u32>(SEC_STOP_ROUTE_POS, i as usize)
    }
    fn transfers_range(&self, stop: u32) -> (u32, u32) {
        let idx = SEC_TRANSFERS_IDX;
        let s = self.rd::<u32>(idx, stop as usize);
        let e = self.rd::<u32>(idx, stop as usize + 1);
        (s, e)
    }
    fn transfer(&self, i: u32) -> TransferRec {
        self.rd::<TransferRec>(SEC_TRANSFERS, i as usize)
    }
    fn service(&self, i: u32) -> ServiceRec {
        self.rd::<ServiceRec>(SEC_SERVICES, i as usize)
    }
    fn exception(&self, i: u32) -> ExcRec {
        self.rd::<ExcRec>(SEC_EXCEPTIONS, i as usize)
    }
    /// `[start, end)` range of EXCEPTIONS belonging to `service_idx`, ascending
    /// by date. v3 CSR index; replaces a full scan of every exception per call.
    fn exceptions_range(&self, service_idx: u32) -> (u32, u32) {
        let idx = SEC_EXCEPTIONS_IDX;
        let s = self.rd::<u32>(idx, service_idx as usize);
        let e = self.rd::<u32>(idx, service_idx as usize + 1);
        (s, e)
    }

    /// Byte offset of `route_idx`'s polyline within SHAPE_COORDS, or `None` when
    /// the pack predates v4, or the ingester found no usable `shapes.txt`
    /// geometry for that route. Offsets are not a prefix sum: routes sharing a
    /// `shape_id` share one blob, so several may point at the same offset.
    fn route_shape_off(&self, route_idx: u32) -> Option<usize> {
        if route_idx >= self.route_count {
            return None;
        }
        if self.sec[SEC_ROUTE_SHAPE_IDX].1 < (self.route_count as usize + 1) * 4 {
            return None;
        }
        let off = self.rd::<u32>(SEC_ROUTE_SHAPE_IDX, route_idx as usize);
        // NONE marks "no shape"; an offset with no room for the point count is
        // corrupt and gets the same fallback.
        if (off as usize).saturating_add(4) > self.sec[SEC_SHAPE_COORDS].1 {
            return None;
        }
        Some(off as usize)
    }

    /// Vertex index within its route's shape for ROUTE_STOPS entry `i`, or
    /// [`NONE`] when the pack carries no shape for it.
    fn route_stop_shape(&self, i: u32) -> u32 {
        if (i as usize + 1) * 4 > self.sec[SEC_ROUTE_STOP_SHAPE].1 {
            return NONE;
        }
        self.rd::<u32>(SEC_ROUTE_STOP_SHAPE, i as usize)
    }

    /// Decode vertices `from..=to` of the shape blob at byte offset `off`, as
    /// `(lat, lon)` degrees. Deltas accumulate from the blob's first point, so
    /// decoding always starts there and discards the head.
    fn shape_slice(&self, off: usize, from: u32, to: u32) -> Vec<(f64, f64)> {
        let point_count = self.rd_at::<u32>(SEC_SHAPE_COORDS, off);
        if to < from || to >= point_count {
            return Vec::new();
        }
        let mut pos = off + 4;
        let mut lat_e7 = 0i64;
        let mut lon_e7 = 0i64;
        let mut out = Vec::with_capacity((to - from + 1) as usize);
        for v in 0..=to {
            lat_e7 += zigzag(self.uvarint(SEC_SHAPE_COORDS, &mut pos));
            lon_e7 += zigzag(self.uvarint(SEC_SHAPE_COORDS, &mut pos));
            if v >= from {
                out.push((lat_e7 as f64 * 1e-7, lon_e7 as f64 * 1e-7));
            }
        }
        out
    }

    /// Decode a route's varint trip block into `TripDec`s (start-time order).
    fn route_trips(&self, rec: &RouteRec) -> Vec<TripDec> {
        let n = rec.n_trips;
        let mut pos = rec.trips_off as usize;
        let mut prev: u32 = 0;
        let mut out = Vec::with_capacity(n as usize);
        for _ in 0..n {
            let start = prev.wrapping_add(self.uvarint(SEC_ROUTE_TRIPS, &mut pos) as u32);
            let profile_id = self.uvarint(SEC_ROUTE_TRIPS, &mut pos) as u32;
            let service_idx = self.uvarint(SEC_ROUTE_TRIPS, &mut pos) as u32;
            let headsign_off = self.uvarint(SEC_ROUTE_TRIPS, &mut pos) as u32;
            out.push(TripDec { start_time: start, profile_id, service_idx, headsign_off });
            prev = start;
        }
        out
    }

    /// Whether this pack carries v6's fixed-stride trip table.
    fn has_trip_table(&self) -> bool {
        self.sec[SEC_ROUTE_TRIP_RECS].1 != 0 && self.sec[SEC_ROUTE_TRIP_OFF].1 != 0
    }

    /// Trip record `i` of the strided table, by global trip index.
    fn trip_at(&self, i: u32) -> TripDec {
        let p = SEC_ROUTE_TRIP_RECS;
        let w = i as usize * 4;
        TripDec {
            start_time: self.rd::<u32>(p, w),
            profile_id: self.rd::<u32>(p, w + 1),
            service_idx: self.rd::<u32>(p, w + 2),
            headsign_off: self.rd::<u32>(p, w + 3),
        }
    }

    /// A route's trips, however this pack happens to store them.
    fn trips_for(&self, route_idx: u32, rec: &RouteRec) -> Trips<'_> {
        if !self.has_trip_table() {
            return Trips::Decoded(self.route_trips(rec));
        }
        let off = SEC_ROUTE_TRIP_OFF;
        let base = self.rd::<u32>(off, route_idx as usize);
        let end = self.rd::<u32>(off, route_idx as usize + 1);
        // A directory that disagrees with the route record would index past the table;
        // fall back rather than read out of bounds.
        let len = end.saturating_sub(base);
        if end < base
            || (end as usize) * TRIP_REC_BYTES > self.sec[SEC_ROUTE_TRIP_RECS].1
            || len != rec.n_trips
        {
            return Trips::Decoded(self.route_trips(rec));
        }
        Trips::Strided { idx: self, base, len }
    }

    /// Decode profile `pid` into per-stop offsets relative to `start_time`.
    fn profile(&self, pid: u32) -> ProfileDec {
        let off = self.rd::<u32>(SEC_PROFILES_IDX, pid as usize) as usize;
        let mut pos = off;
        let n = self.uvarint(SEC_PROFILES, &mut pos) as usize;
        let mut arr_rel = vec![0i32; n.max(1)];
        let mut dep_rel = vec![0i32; n.max(1)];
        if n == 0 {
            return ProfileDec { arr_rel, dep_rel };
        }
        let dwell0 = self.uvarint(SEC_PROFILES, &mut pos) as i64;
        arr_rel[0] = -(dwell0 as i32);
        dep_rel[0] = 0;
        let mut prev_dep = 0i64;
        for k in 1..n {
            let hop = self.uvarint(SEC_PROFILES, &mut pos) as i64;
            let dwell = self.uvarint(SEC_PROFILES, &mut pos) as i64;
            let arr = prev_dep + hop;
            let dep = arr + dwell;
            arr_rel[k] = arr as i32;
            dep_rel[k] = dep as i32;
            prev_dep = dep;
        }
        ProfileDec { arr_rel, dep_rel }
    }

    fn stop_ll(&self, i: u32) -> (f64, f64) {
        let s = self.stop(i);
        (s.lat_e7 as f64 * 1e-7, s.lon_e7 as f64 * 1e-7)
    }

    /// Display name of a stop, falling back to its code when the feed's
    /// `stop_name` is blank. The UI presents these as names, not codes.
    fn stop_label(&self, i: u32) -> String {
        let s = self.stop(i);
        let name = self.read_str(s.name_off);
        if name.is_empty() {
            self.read_str(s.code_off)
        } else {
            name
        }
    }

    // --- Spatial grid (sparse CSR) ---

    fn cell_row(&self, lat_e7: i32) -> i64 {
        ((lat_e7 as i64 - self.grid_lat0_e7 as i64) / self.grid_cell_e7 as i64).max(0)
    }
    fn cell_col(&self, lon_e7: i32) -> i64 {
        ((lon_e7 as i64 - self.grid_lon0_e7 as i64) / self.grid_cell_e7 as i64).max(0)
    }

    /// Binary-search a cell id in GRID_CELL_IDS -> its index, if present.
    fn cell_index(&self, cell_id: u32) -> Option<usize> {
        let ids = SEC_GRID_CELL_IDS;
        let (mut lo, mut hi) = (0usize, self.grid_cell_count as usize);
        while lo < hi {
            let mid = (lo + hi) / 2;
            let v = self.rd::<u32>(ids, mid);
            if v == cell_id {
                return Some(mid);
            } else if v < cell_id {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        None
    }

    fn cell_stops(&self, cell_index: usize) -> (u32, u32) {
        let off = SEC_GRID_CELL_OFF;
        let s = self.rd::<u32>(off, cell_index);
        let e = self.rd::<u32>(off, cell_index + 1);
        (s, e)
    }
    fn grid_stop(&self, i: u32) -> u32 {
        self.rd::<u32>(SEC_GRID_STOPS, i as usize)
    }

    /// Candidate stops within `radius_m` of `(lat,lon)` and their distances,
    /// using the grid so the scan is cell-local rather than O(all stops).
    fn stops_in_radius(&self, lat: f64, lon: f64, radius_m: f64) -> Vec<(u32, f64)> {
        let mut out: Vec<(u32, f64)> = Vec::new();
        if self.grid_cell_count == 0 || self.grid_cols == 0 || self.grid_cell_e7 == 0 {
            return out;
        }
        let lat_e7 = (lat * 1e7) as i32;
        let lon_e7 = (lon * 1e7) as i32;
        let row0 = self.cell_row(lat_e7);
        let col0 = self.cell_col(lon_e7);
        let cell_deg = self.grid_cell_e7 as f64 * 1e-7;
        let rad_deg = radius_m / 111_320.0;
        let cos = lat.to_radians().cos().abs().max(1e-6);
        // +1 cell of slack; longitude cells shrink with latitude (cos factor).
        let dr = (rad_deg / cell_deg).ceil() as i64 + 1;
        let dc = ((rad_deg / cos) / cell_deg).ceil() as i64 + 1;
        let cols = self.grid_cols as i64;
        for r in (row0 - dr)..=(row0 + dr) {
            if r < 0 {
                continue;
            }
            for c in (col0 - dc)..=(col0 + dc) {
                if c < 0 || c >= cols {
                    continue;
                }
                let cell_id = (r * cols + c) as u32;
                if let Some(ci) = self.cell_index(cell_id) {
                    let (s, e) = self.cell_stops(ci);
                    for k in s..e {
                        let sid = self.grid_stop(k);
                        let (slat, slon) = self.stop_ll(sid);
                        let d = dist_m(lat, lon, slat, slon);
                        if d <= radius_m {
                            out.push((sid, d));
                        }
                    }
                }
            }
        }
        out
    }

    /// Nearest stop to `(lat,lon)` within `max_m`, via the grid.
    fn nearest_stop(&self, lat: f64, lon: f64, max_m: f64) -> Option<(u32, f64)> {
        let mut best: Option<(u32, f64)> = None;
        for (s, d) in self.stops_in_radius(lat, lon, max_m) {
            if best.map(|(_, bd)| d < bd).unwrap_or(true) {
                best = Some((s, d));
            }
        }
        best
    }

    /// True if `lat`/`lon` (degrees) lie within the pack's bounding box (with a
    /// small margin so points just outside still route via a nearby stop).
    pub fn covers(&self, lat: f64, lon: f64) -> bool {
        let m = 0.05; // ~5.5 km margin
        let lat_e7 = (lat * 1e7) as i32;
        let lon_e7 = (lon * 1e7) as i32;
        lat_e7 >= self.min_lat_e7 - (m * 1e7) as i32
            && lat_e7 <= self.max_lat_e7 + (m * 1e7) as i32
            && lon_e7 >= self.min_lon_e7 - (m * 1e7) as i32
            && lon_e7 <= self.max_lon_e7 + (m * 1e7) as i32
    }

    /// Pack-level name (fallback). Per-route feeds use [`Self::feed_name_of`].
    pub fn feed_name(&self) -> String {
        self.read_str(self.feed_name_off)
    }

    /// Name of feed `feed_idx` from the FEEDS table (per-route provenance).
    fn feed_name_of(&self, feed_idx: u32) -> String {
        if feed_idx >= self.feed_count {
            return self.feed_name();
        }
        let off = self.rd::<u32>(SEC_FEEDS, feed_idx as usize);
        self.read_str(off)
    }
}
