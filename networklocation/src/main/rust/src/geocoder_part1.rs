impl Reader {
    #[cfg(unix)]
    fn open(fd: i32, offset: i64) -> Option<Reader> {
        let dupfd = unsafe { libc::dup(fd) };
        if dupfd < 0 {
            return None;
        }
        // The mapping outlives the descriptor, so the dup'd fd is closed as soon as it is made.
        let file = unsafe { File::from_raw_fd(dupfd) };
        Reader::map_file(&file, offset.max(0) as u64)
    }

    /// Host fallback: `fd` is an Android descriptor, meaningless off-device.
    #[cfg(not(unix))]
    fn open(_fd: i32, _offset: i64) -> Option<Reader> {
        None
    }

    /// Open a database straight from a filesystem path. Test-only: the production path
    /// receives a descriptor via `open`.
    #[cfg(test)]
    fn open_path<P: AsRef<std::path::Path>>(path: P) -> Option<Reader> {
        Reader::map_file(&File::open(path).ok()?, 0)
    }

    fn map_file(file: &File, offset: u64) -> Option<Reader> {
        let len = file.metadata().ok()?.len().checked_sub(offset)?;
        let map = Map::new(file, offset, usize::try_from(len).ok()?)?;
        Reader::from_map(map)
    }

    /// Walk the section headers and remember where each section starts. Nothing is decoded and
    /// nothing proportional to the database is allocated.
    fn from_map(map: Map) -> Option<Reader> {
        let d = map.as_slice();
        if be32(d, 0)? != MAGIC || be32(d, 4)? != VERSION {
            return None;
        }
        let n = be32(d, 8)? as i32;

        // (body offset, body end) of every section.
        let mut bodies = Vec::with_capacity(SECTIONS);
        let mut cursor = 12usize;
        for _ in 0..SECTIONS {
            let size = be32(d, cursor)? as usize;
            let body = cursor + 4;
            let end = body.checked_add(size)?;
            if end > d.len() {
                return None;
            }
            bodies.push((body, end));
            cursor = end;
        }

        let mut dicts = Vec::with_capacity(DICTS);
        for &(body, end) in &bodies[..DICTS] {
            dicts.push(Dict::new(d, body, end)?);
        }
        let mut cols = Vec::with_capacity(COLUMNS);
        for (c, &(body, _)) in bodies[DICTS..DICTS + COLUMNS].iter().enumerate() {
            cols.push(Column::new(d, body, DELTA[c])?);
        }
        let (grid_body, grid_end) = bodies[DICTS + COLUMNS];
        let grid_count = be32(d, grid_body)? as usize;
        let grid = grid_body + 4;
        if grid.checked_add(grid_count.checked_mul(GRID_ENTRY)?)? > grid_end {
            return None;
        }
        let fwd = Column::new(d, bodies[DICTS + COLUMNS + 1].0, true)?;
        let nm_name = Column::new(d, bodies[DICTS + COLUMNS + 2].0, true)?;
        let nm_rec = Column::new(d, bodies[DICTS + COLUMNS + 3].0, false)?;

        Some(Reader { map, n, dicts, grid, grid_count, cols, fwd, nm_name, nm_rec })
    }

    fn grid_cell(&self, gi: usize) -> i64 {
        be64(self.map.as_slice(), self.grid + gi * GRID_ENTRY).unwrap_or(i64::MAX)
    }

    fn grid_start(&self, gi: usize) -> i32 {
        be32(self.map.as_slice(), self.grid + gi * GRID_ENTRY + 8).map_or(self.n, |v| v as i32)
    }

    fn grid_index(&self, cell: i64) -> Option<usize> {
        let (mut lo, mut hi) = (0usize, self.grid_count);
        while lo < hi {
            let mid = (lo + hi) / 2;
            match self.grid_cell(mid).cmp(&cell) {
                Ordering::Less => lo = mid + 1,
                Ordering::Greater => hi = mid,
                Ordering::Equal => return Some(mid),
            }
        }
        None
    }

    fn col(&mut self, c: usize, i: i32) -> i32 {
        self.cols[c].get(self.map.as_slice(), i)
    }

    /// Nearest record to `(lat, lon)`, or `None` if nothing is within [`MAX_RADIUS`] cells.
    ///
    /// Expands ring by ring from the query's cell. v2 stopped at the first radius that found
    /// anything, which does **not** give the nearest record: a point just over a cell boundary
    /// is found on ring 1, while something genuinely closer sits on ring 2. This keeps going
    /// until the next ring cannot possibly hold anything nearer than the best already seen.
    fn reverse(&mut self, lat: f64, lon: f64) -> Option<i32> {
        if self.n == 0 {
            return None;
        }
        let q_lat = to_e5(lat);
        let q_lon = to_e5(lon);
        let row = (q_lat as i64 - MIN_LAT as i64) / CELL;
        let col = (q_lon as i64 - MIN_LON as i64) / CELL;
        let lon_scale = lat.to_radians().cos();

        let mut best_rec = -1i32;
        let mut best_dist = f64::MAX;
        let mut radius = 1i64;
        while radius <= MAX_RADIUS {
            for r in (row - radius)..=(row + radius) {
                if r < 0 {
                    continue;
                }
                for c in (col - radius)..=(col + radius) {
                    let on_edge = !(radius > 1
                        && r > row - radius
                        && r < row + radius
                        && c > col - radius
                        && c < col + radius);
                    if !on_edge {
                        continue;
                    }
                    let cc = ((c % COLS) + COLS) % COLS;
                    let Some(gi) = self.grid_index(r * COLS + cc) else { continue };
                    let start = self.grid_start(gi);
                    let end = if gi + 1 < self.grid_count { self.grid_start(gi + 1) } else { self.n };
                    for i in start..end {
                        let d_lat = (self.col(C_LAT, i) - q_lat) as f64;
                        let d_lon = (self.col(C_LON, i) - q_lon) as f64 * lon_scale;
                        let d = d_lat * d_lat + d_lon * d_lon;
                        if d < best_dist {
                            best_dist = d;
                            best_rec = i;
                        }
                    }
                }
            }
            // Anything on the next ring is at least `radius` whole cells away along one axis,
            // because the query sits somewhere inside ring 0's cell. Once that floor exceeds
            // the best distance found, no further ring can improve on it.
            if best_rec >= 0 {
                let floor = radius as f64 * CELL as f64 * lon_scale.abs().min(1.0);
                if floor * floor > best_dist {
                    break;
                }
            }
            radius += 1;
        }
        (best_rec >= 0).then_some(best_rec)
    }

    fn compare_key(&mut self, rec: i32, target: [i32; 4]) -> i32 {
        for (c, t) in [(C_COUNTRY, target[0]), (C_STATE, target[1]), (C_CITY, target[2])] {
            let v = self.col(c, rec) - t;
            if v != 0 {
                return v;
            }
        }
        self.col(C_STREET, rec) - target[3]
    }

    fn dict_index(&mut self, field: usize, key: &str) -> Option<i32> {
        self.dicts[field].index_of(self.map.as_slice(), key).map(|i| i as i32)
    }

    fn forward(&mut self, country: &str, state: &str, city: &str, street: &str, limit: i32) -> Vec<i32> {
        let keys = (
            self.dict_index(dict_of(C_COUNTRY), country),
            self.dict_index(dict_of(C_STATE), state),
            self.dict_index(dict_of(C_CITY), city),
            self.dict_index(dict_of(C_STREET), street),
        );
        let (Some(k_country), Some(k_state), Some(k_city), Some(k_street)) = keys else {
            return Vec::new();
        };
        let target = [k_country, k_state, k_city, k_street];
        let total = self.fwd.n;
        let (mut lo, mut hi) = (0i32, total);
        while lo < hi {
            let mid = (lo + hi) >> 1;
            let rec = self.fwd.get(self.map.as_slice(), mid);
            if self.compare_key(rec, target) < 0 {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        let mut out = Vec::new();
        let mut k = lo;
        while k < total && (out.len() as i32) < limit {
            let rec = self.fwd.get(self.map.as_slice(), k);
            if self.compare_key(rec, target) != 0 {
                break;
            }
            out.push(rec);
            k += 1;
        }
        out
    }

    /// Dictionary id range `[start, end)` of names beginning with `prefix`.
    ///
    /// The name dictionary is UTF-16 sorted, so every name with a given prefix forms one
    /// contiguous run and both ends are a binary search. Matching is case-sensitive: the
    /// dictionary is ordered by the names as they appear in OpenStreetMap, and a case-folded
    /// run would not be contiguous in that ordering.
    fn name_prefix_range(&mut self, prefix: &str) -> (i32, i32) {
        let d = self.map.as_slice();
        let dict = &mut self.dicts[dict_of(C_NAME)];
        let start = dict.partition_point(d, |s| cmp_utf16(s, prefix) == Ordering::Less);
        let end = dict.partition_point(d, |s| {
            cmp_utf16(s, prefix) == Ordering::Less || s.starts_with(prefix)
        });
        (start as i32, end as i32)
    }

    /// Records whose name begins with `prefix`, up to `limit`.
    fn search_name(&mut self, prefix: &str, limit: i32) -> Vec<i32> {
        if prefix.is_empty() {
            return Vec::new();
        }
        let (id_lo, id_hi) = self.name_prefix_range(prefix);
        if id_lo >= id_hi {
            return Vec::new();
        }
        let d = self.map.as_slice();
        let total = self.nm_name.n;
        let (mut lo, mut hi) = (0i32, total);
        while lo < hi {
            let mid = (lo + hi) >> 1;
            if self.nm_name.get(d, mid) < id_lo {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        let mut out = Vec::new();
        let mut k = lo;
        while k < total && (out.len() as i32) < limit {
            if self.nm_name.get(d, k) >= id_hi {
                break;
            }
            out.push(self.nm_rec.get(d, k));
            k += 1;
        }
        out
    }

    /// `[lat, lon, name, house, street, city, state, country, postcode, kind]` for a
    /// grid-ordered record.
    fn resolve(&mut self, rec: i32) -> [String; FIELDS] {
        let lat = self.col(C_LAT, rec) as f64 / 1e5;
        let lon = self.col(C_LON, rec) as f64 / 1e5;
        let kind = self.col(C_KIND, rec);
        let mut text = |c: usize| -> String {
            let id = self.cols[c].get(self.map.as_slice(), rec);
            if id < 0 {
                return String::new();
            }
            self.dicts[dict_of(c)].get(self.map.as_slice(), id as usize)
        };
        [
            format!("{lat:.5}"),
            format!("{lon:.5}"),
            text(C_NAME),
            text(C_HOUSE),
            text(C_STREET),
            text(C_CITY),
            text(C_STATE),
            text(C_COUNTRY),
            text(C_POSTCODE),
            kind.to_string(),
        ]
    }
}

/// Degrees to e5 (about 1.1 m), the precision this database stores.
fn to_e5(deg: f64) -> i32 {
    (deg * 100_000.0 + 0.5).floor() as i32
}

// --------------------------------------------------------------------------- JNI
type Handle = Mutex<Reader>;

fn build_string_array(env: &mut Env, reader: &mut Reader, recs: &[i32]) -> jobjectArray {
    let arr: JObjectArray<JString> =
        match env.new_object_type_array::<JString>((recs.len() * FIELDS) as usize, JString::null()) {
            Ok(a) => a,
            Err(_) => return std::ptr::null_mut(),
        };
    for (idx, &rec) in recs.iter().enumerate() {
        let fields = reader.resolve(rec);
        for (j, val) in fields.iter().enumerate() {
            let s = match env.new_string(val) {
                Ok(s) => s,
                Err(_) => return std::ptr::null_mut(),
            };
            if arr.set_element(env, idx * FIELDS + j, &s).is_err() {
                return std::ptr::null_mut();
            }
        }
    }
    arr.into_raw()
}

/// `open(fd, offset, length) -> handle` (0 on failure). `length` is unused: the reader maps
/// the file from `offset` to its end and the section framing bounds every read.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_open<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    _length: jlong,
) -> jlong {
    match Reader::open(fd, offset) {
        Some(r) => Box::into_raw(Box::new(Mutex::new(r))) as jlong,
        None => 0,
    }
}

/// `reverse(handle, lat, lon) -> String[10]` (nearest record) or null.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_reverse<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    lat: jdouble,
    lon: jdouble,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        if handle == 0 {
            return Ok(std::ptr::null_mut());
        }
        let m = unsafe { &*(handle as *const Handle) };
        let mut reader = match m.lock() {
            Ok(g) => g,
            Err(_) => return Ok(std::ptr::null_mut()),
        };
        Ok(match reader.reverse(lat, lon) {
            Some(rec) => build_string_array(env, &mut reader, &[rec]),
            None => std::ptr::null_mut(),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `forward(handle, country, state, city, street, limit) -> String[10*k]` (may be empty).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_forward<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    country: JString<'l>,
    state: JString<'l>,
    city: JString<'l>,
    street: JString<'l>,
    limit: jint,
) -> jobjectArray {
    let get = |env: &Env, s: &JString| -> String {
        s.try_to_string(env).unwrap_or_default()
    };
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        if handle == 0 {
            return Ok(std::ptr::null_mut());
        }
        let m = unsafe { &*(handle as *const Handle) };
        let mut reader = match m.lock() {
            Ok(g) => g,
            Err(_) => return Ok(std::ptr::null_mut()),
        };
        let country = get(env, &country);
        let state = get(env, &state);
        let city = get(env, &city);
        let street = get(env, &street);
        let limit = limit.clamp(1, 50);
        let recs = reader.forward(&country, &state, &city, &street, limit);
        Ok(build_string_array(env, &mut reader, &recs))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `searchName(handle, prefix, limit) -> String[10*k]` (may be empty).
///
/// Finds named features — points of interest, places and streets — by name prefix. v2 had no
/// name column at all, so the only way in was a fully structured address.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_searchName<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    prefix: JString<'l>,
    limit: jint,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        if handle == 0 {
            return Ok(std::ptr::null_mut());
        }
        let m = unsafe { &*(handle as *const Handle) };
        let mut reader = match m.lock() {
            Ok(g) => g,
            Err(_) => return Ok(std::ptr::null_mut()),
        };
        let prefix: String = match prefix.try_to_string(env) {
            Ok(s) => s,
            Err(_) => return Ok(std::ptr::null_mut()),
        };
        let recs = reader.search_name(prefix.trim(), limit.clamp(1, 50));
        Ok(build_string_array(env, &mut reader, &recs))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
