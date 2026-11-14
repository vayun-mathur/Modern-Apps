
impl TransitIndex {
    /// IANA timezone of feed `feed_idx` from FEED_TZ, or empty when the feed had
    /// no `agency.txt`.
    fn feed_tz_of(&self, feed_idx: u32) -> String {
        if feed_idx >= self.feed_count {
            return String::new();
        }
        let off = self.rd::<u32>(SEC_FEED_TZ, feed_idx as usize);
        self.read_str(off)
    }

    /// Transitous id prefix of feed `feed_idx` (`us-ca-SF-bayarea`), or `None` on
    /// a pre-v5 pack or a feed whose prefix the build did not know.
    fn feed_motis_prefix_of(&self, feed_idx: u32) -> Option<String> {
        if feed_idx >= self.feed_count {
            return None;
        }
        // v3/v4 packs carry no such section; its length is the version gate.
        if self.sec[SEC_FEED_MOTIS_PREFIX].1 < (self.feed_count as usize) * 4 {
            return None;
        }
        let off =
            self.rd::<u32>(SEC_FEED_MOTIS_PREFIX, feed_idx as usize);
        if off == NONE {
            return None;
        }
        let prefix = self.read_str(off);
        if prefix.is_empty() {
            None
        } else {
            Some(prefix)
        }
    }

    /// MOTIS/Transitous stop id for `stop_idx`, composed as
    /// `<feed prefix>_<gtfs stop_id>` (e.g. `us-ca-SF-bayarea_901201`). This is
    /// what the realtime overlay passes to `/stoptimes`, so it replaces the
    /// coordinate-to-id round trip through `/map/stops`.
    ///
    /// `None` on a pre-v5 pack, or when the feed's prefix was unknown at build
    /// time. `StopRec` carries no `feed_idx` — only `RouteRec` does — so the feed
    /// is resolved through a route serving the stop, as [`Self::timezone_at`] does.
    pub fn motis_stop_id(&self, stop_idx: u32) -> Option<String> {
        if stop_idx >= self.stop_count {
            return None;
        }
        if self.sec[SEC_STOP_GTFS_ID].1 < (self.stop_count as usize) * 4 {
            return None;
        }
        let id_off =
            self.rd::<u32>(SEC_STOP_GTFS_ID, stop_idx as usize);
        if id_off == NONE {
            return None;
        }
        let gtfs_id = self.read_str(id_off);
        if gtfs_id.is_empty() {
            return None;
        }
        let (rs, re) = self.stop_routes_range(stop_idx);
        for i in rs..re {
            let feed_idx = self.route(self.stop_route(i)).feed_idx;
            if let Some(prefix) = self.feed_motis_prefix_of(feed_idx) {
                return Some(format!("{prefix}_{gtfs_id}"));
            }
        }
        None
    }

    /// MOTIS id of the stop nearest `(lat, lon)`, for a caller that must name a
    /// stop before it knows which one it wants — the departure board fetches its
    /// realtime overlay before running the board query. Local lookup, no network.
    pub fn nearest_stop_motis_id(&self, lat: f64, lon: f64) -> Option<String> {
        const NEAREST_MAX_M: f64 = 400.0;
        let (stop, _) = self.nearest_stop(lat, lon, NEAREST_MAX_M)?;
        self.motis_stop_id(stop)
    }

    /// IANA timezone of the feed covering `(lat, lon)`, resolved via the nearest
    /// stop and one of the routes serving it. Stops carry no `feed_idx` — only
    /// `RouteRec` does — so the route hop is required. Empty when nothing is
    /// near enough or the feed has no timezone.
    pub fn timezone_at(&self, lat: f64, lon: f64) -> String {
        const NEAREST_MAX_M: f64 = 5000.0;
        let (stop, _) = match self.nearest_stop(lat, lon, NEAREST_MAX_M) {
            Some(v) => v,
            None => return String::new(),
        };
        let (rs, re) = self.stop_routes_range(stop);
        for i in rs..re {
            let tz = self.feed_tz_of(self.route(self.stop_route(i)).feed_idx);
            if !tz.is_empty() {
                return tz;
            }
        }
        String::new()
    }

    /// Whether `service_idx` runs on the query weekday/date.
    fn service_runs(&self, service_idx: u32, weekday: u32, date: u32) -> bool {
        if service_idx >= self.service_count {
            return false;
        }
        let s = self.service(service_idx);
        let start = s.start_date;
        let end = s.end_date;
        let mask = s.weekday_mask;
        let runs = date >= start && date <= end && (mask & (1 << (weekday & 7))) != 0;
        // A `calendar_dates` row for this exact date overrides the weekly mask.
        // EXCEPTIONS is date-sorted within the service's CSR range (v3), so this
        // is a binary search rather than a scan of the whole pack.
        let (mut lo, mut hi) = self.exceptions_range(service_idx);
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            let e = self.exception(mid);
            if e.date == date {
                return e.added == 1;
            } else if e.date < date {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        runs
    }
}

/// Approximate ground distance in metres (equirectangular).
fn dist_m(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let dlat = (lat2 - lat1) * 111_320.0;
    let mean = ((lat1 + lat2) * 0.5).to_radians();
    let dlon = (lon2 - lon1) * 111_320.0 * mean.cos();
    (dlat * dlat + dlon * dlon).sqrt()
}

/// Undo the producer's zigzag encoding (`(n << 1) ^ (n >> 63)`).
fn zigzag(u: u64) -> i64 {
    ((u >> 1) as i64) ^ -((u & 1) as i64)
}

/// Absolute time in the **query day's** frame: trip `start_time` plus a profile
/// offset. GTFS stores a trip running past midnight on its own service day
/// (`24:30:00`), so a trip inherited from the previous service day is shifted
/// back one full day.
fn abs_time_on(start_time: u32, rel: i32, prev_day: bool) -> u32 {
    abs_time_day(start_time, rel, if prev_day { -1 } else { 0 })
}

/// [`abs_time_on`] for a trip on the service day `day` days from the query day
/// (`-1` is the previous day, `1` tomorrow).
fn abs_time_day(start_time: u32, rel: i32, day: i32) -> u32 {
    (start_time as i64 + rel as i64 + day as i64 * SECS_PER_DAY as i64).max(0) as u32
}

/// The two service days a query may draw trips from: the query day itself, and
/// the day before it, whose `>24:00:00` trips run into the query day. Weekdays
/// are 0=Mon..6=Sun and dates are `yyyymmdd`, both in the **feed's** timezone.
#[derive(Clone, Copy)]
pub struct QueryDay {
    pub weekday: u32,
    pub date: u32,
    pub prev_weekday: u32,
    pub prev_date: u32,
}

/// The query-specific view of the timetable: which service days are eligible,
/// and what realtime knows about them. Threaded through the RAPTOR rounds.
#[derive(Clone, Copy)]
pub struct Schedule<'a> {
    pub day: QueryDay,
    pub overlay: Option<&'a DelayOverlay>,
}

impl Schedule<'_> {
    /// Whether `service_idx` runs on the query day (`prev = false`) or on the
    /// preceding service day (`prev = true`).
    fn runs(&self, idx: &TransitIndex, service_idx: u32, prev: bool) -> bool {
        self.runs_on(idx, service_idx, if prev { -1 } else { 0 })
    }

    /// Whether `service_idx` runs on the service day `day` days from the query
    /// day (see [`QueryDay::on`]).
    fn runs_on(&self, idx: &TransitIndex, service_idx: u32, day: i32) -> bool {
        let (weekday, date) = self.day.on(day);
        idx.service_runs(service_idx, weekday, date)
    }

    fn adjustment(&self, route: u32, pos: u32, sched: u32) -> Option<Adjustment> {
        self.overlay.and_then(|o| o.get(route, pos, sched))
    }

    /// How much earlier than its timetable slot any trip could depart.
    fn max_early_secs(&self) -> u32 {
        self.overlay.map_or(0, |o| o.max_early_secs)
    }
}

/// Realtime delays and cancellations lifted from a MOTIS departure board, so
/// RAPTOR plans against live times instead of the bare timetable.
///
/// The join is a **fingerprint**, not a trip id: TRX2 stores no `trip_id` (the
/// ingest tool discards it) and MOTIS's `tripId` is an opaque internally-encoded
/// handle, not a bare GTFS id. Worse, a v2 *profile* is deliberately shared
/// across many trips, so a delay can never hang off one. Entries are therefore
/// keyed on `(route_idx, stop_pos, scheduled_departure)` and resolved **once**,
/// at construction — never inside the RAPTOR hot loop.
#[derive(Default)]
pub struct DelayOverlay {
    entries: HashMap<(u32, u32, u32), Adjustment>,
    /// Largest amount by which any entry pulls a departure **earlier** (seconds,
    /// ≥ 0). `earliest_trip`'s early break needs this: a vehicle running early
    /// makes `dep < start_time`, so the break bound has to be widened by it or
    /// the scan can stop before a trip whose live departure is earlier.
    max_early_secs: u32,
}

#[derive(Clone, Copy)]
struct Adjustment {
    delay_secs: i32,
    cancelled: bool,
}

/// One realtime board entry as it crosses the JNI boundary.
pub struct DelayEntry {
    /// Board-stop coordinates; matched to a baked stop via the spatial grid,
    /// because MOTIS stop ids share no namespace with the pack.
    pub lat: f64,
    pub lon: f64,
    /// MOTIS `routeShortName`, matched against the baked route name.
    pub route_name: String,
    /// Scheduled departure, seconds since feed-local midnight.
    pub sched_secs: u32,
    pub delay_secs: i32,
    pub cancelled: bool,
}

impl DelayOverlay {
    /// Resolve realtime board entries against the index. Entries whose stop or
    /// route can't be matched are dropped — a miss must leave the schedule
    /// untouched, never guess.
    pub fn build(idx: &TransitIndex, entries: &[DelayEntry]) -> DelayOverlay {
        /// A MOTIS stop and its baked counterpart are the same platform, so keep
        /// this tight enough that adjacent platforms don't collide.
        const MATCH_RADIUS_M: f64 = 60.0;

        let mut out = DelayOverlay::default();
        for e in entries {
            if e.delay_secs == 0 && !e.cancelled {
                continue;
            }
            let stop = match idx.nearest_stop(e.lat, e.lon, MATCH_RADIUS_M) {
                Some((s, _)) => s,
                None => continue,
            };
            let (rs, re) = idx.stop_routes_range(stop);
            for i in rs..re {
                let r = idx.stop_route(i);
                if idx.read_str(idx.route(r).name_off) != e.route_name {
                    continue;
                }
                out.max_early_secs = out.max_early_secs.max((-e.delay_secs).max(0) as u32);
                out.entries.insert(
                    (r, idx.stop_route_pos(i), e.sched_secs),
                    Adjustment { delay_secs: e.delay_secs, cancelled: e.cancelled },
                );
            }
        }
        out
    }

    fn get(&self, route: u32, pos: u32, sched: u32) -> Option<Adjustment> {
        self.entries.get(&(route, pos, sched)).copied()
    }
}

/// Apply an overlay adjustment to a scheduled time, clamped ≥ 0.
fn delayed(sched: u32, delay_secs: i32) -> u32 {
    (sched as i64 + delay_secs as i64).max(0) as u32
}

/// The trip being ridden while scanning a route, plus the realtime shift that
/// applies to the rest of its journey.
#[derive(Clone, Copy)]
struct Boarding {
    /// Local index into the route's decoded trip list.
    trip: usize,
    /// The trip's service day relative to the query day: `-1` for a previous-day
    /// `>24:00:00` trip, `0` today, `1..=PLAN_DAYS_AHEAD` for a later day.
    day: i32,
    delay_secs: i32,
}

/// Fetch (and cache) a decoded profile.
fn get_profile<'a>(
    cache: &'a mut HashMap<u32, ProfileDec>,
    idx: &TransitIndex,
    pid: u32,
) -> &'a ProfileDec {
    cache.entry(pid).or_insert_with(|| idx.profile(pid))
}

/// How a stop was first reached in a RAPTOR round, for journey reconstruction.
#[derive(Clone, Copy)]
enum Reached {
    Origin,
    /// Boarded `route` at `board_stop` (see [`Boarding`]), alighting here.
    Transit { route: u32, board_stop: u32, boarding: Boarding },
    /// Walked from `from_stop` (footpath transfer or egress precursor).
    Walk { from_stop: u32 },
}

/// Earliest known arrival per stop, plus how it was reached and the destination
/// bound that prunes the search.
///
/// Sparse because the flat `vec![_; stop_count]` pair this replaced wrote ~175 MB
/// per call at planetary stop counts, twice per query, to serve a touched set that
/// target pruning keeps proportional to the query. Probes now pay a hash instead,
/// which the round loop can afford.
struct Arrivals {
    best: HashMap<u32, u32>,
    label: HashMap<u32, Reached>,
    /// Walk seconds from each stop within egress range of the destination.
    egress: HashMap<u32, u32>,
    /// Best egress-adjusted arrival at the destination so far, or `u32::MAX`.
    ///
    /// Every onward move costs non-negative time, so a journey through a stop
    /// reached at or after this can never beat it. That makes it a sound cutoff
    /// rather than a heuristic — without one, six rounds diffuse across the whole
    /// planetary component.
    target: u32,
}

impl Arrivals {
    fn new(egress: &[(u32, u32)]) -> Arrivals {
        let mut by_stop: HashMap<u32, u32> = HashMap::new();
        for &(s, w) in egress {
            by_stop.entry(s).and_modify(|e| *e = (*e).min(w)).or_insert(w);
        }
        Arrivals {
            best: HashMap::new(),
            label: HashMap::new(),
            egress: by_stop,
            target: u32::MAX,
        }
    }

    /// Earliest known arrival at `stop`, or `u32::MAX` if unreached.
    fn at(&self, stop: u32) -> u32 {
        self.best.get(&stop).copied().unwrap_or(u32::MAX)
    }

    /// Whether reaching a stop at `t` could still improve the destination.
    fn useful(&self, t: u32) -> bool {
        t < self.target
    }

    /// Record reaching `stop` at `t` via `how`, if that beats both the stop's own
    /// best and [`Self::target`]. Reports whether it did, and tightens the target
    /// when `stop` can walk to the destination.
    fn improve(&mut self, stop: u32, t: u32, how: Reached) -> bool {
        if !self.useful(t) || t >= self.at(stop) {
            return false;
        }
        self.best.insert(stop, t);
        self.label.insert(stop, how);
        if let Some(&w) = self.egress.get(&stop) {
            self.target = self.target.min(t.saturating_add(w));
        }
        true
    }

    /// How `stop` was reached. Unreached stops read as [`Reached::Origin`], which
    /// terminates reconstruction rather than looping.
    fn how(&self, stop: u32) -> Reached {
        self.label.get(&stop).copied().unwrap_or(Reached::Origin)
    }
}
