//! On-device transit index loader + RAPTOR journey planner (P11b / world pack).
//!
//! Consumes the compact `.transit` index produced by the host tool
//! `scripts/maps/gtfs_ingest` (P11a). The on-disk layout (format v3..=v7 "TRIX") is
//! documented at the top of that tool's `src/index.rs` and MUST stay in sync
//! with the section constants and record accessors here.
//!
//! v6 adds two sections (25-26): a fixed-stride trip table and its CSR directory,
//! so a route's trips are addressable by index instead of only decodable from the
//! route's first trip. See [`Trips`] for what that buys and what it does not.
//! Packs without them fall back to the varint stream, unchanged.
//!
//! v5 adds two sections (23-24) holding a per-feed Transitous id prefix and each
//! stop's raw GTFS `stop_id`, which compose into a MOTIS stop id
//! (`us-ca-SF-bayarea_901201`). That is what lets the realtime `/stoptimes`
//! overlay name a stop without the `/map/stops` coordinate lookup. v3 and v4
//! packs are still accepted and simply report no MOTIS ids.
//!
//! v4 adds three sections (20-22) carrying GTFS `shapes.txt` geometry, so a ride
//! leg draws the vehicle's real path instead of a line through its stops. v3
//! packs are still accepted and fall back to stop-to-stop geometry.
//!
//! v2 exists to make a single global (world) pack feasible. Instead of an 8 B
//! `(arr,dep)` stop-time per stop *per trip* (which blew a world pack past the
//! u32 stop-time ceiling and ~10–20 GB), a trip is just `{ start_time,
//! profile_id }`, where a **profile** is the varint-delta run-time *shape*
//! (per-stop hop + dwell), deduplicated across every trip with that shape. Trips
//! are varint-packed per route; a **spatial grid** (sparse CSR) makes
//! nearest-stop / access / egress cell-local instead of O(all stops); and a
//! **FEEDS** table + per-route `feed_idx` let many agencies merge into one pack.
//!
//! The planner is transfer-aware RAPTOR (round-based, earliest-arrival). Each
//! round only processes freshly-marked stops (a marked-stop queue) rather than
//! scanning all stops, and access/egress use the grid — both required so a
//! world-sized stop set stays fast. Access/egress walk straight-line to nearby
//! stops rather than the road graph, keeping transit decoupled from the
//! road-graph merge gap; `lib.rs` re-draws the resulting walk legs along the
//! road graph afterwards, without re-timing them.

use crate::graph::{read_at, MmapRegion};
use std::collections::HashMap;

// --- Format constants: the shared tilecodec values the writer (gtfs_ingest) and the
// archive validator also check, so the three ranges cannot drift apart. ---
// `"TRIX"`, little-endian.
use tilecodec::mamaps::archive::TRANSIT_MAGIC as MAGIC;
// Newest format this reader understands (7). v7 only adds host-only RAIL_LINES after
// the sections read here, which the directory loop below ignores (and `mamaps_pack`
// strips to length 0).
use tilecodec::mamaps::archive::TRANSIT_VERSION as VERSION;
// Oldest format still accepted (3). Reading both means an app update and a pack
// rebuild can land in either order without offline transit silently degrading
// to the online planner in between.
use tilecodec::mamaps::archive::TRANSIT_VERSION_MIN as VERSION_MIN;
pub const NONE: u32 = 0xFFFF_FFFF;
const HEADER_LEN: usize = 80;
/// Section count of the newest format. The section directory is sized to this
/// and an older pack simply leaves the trailing entries empty.
const SECTION_COUNT: usize = 27;
const SECTION_COUNT_V3: usize = 20;

const SEC_STRINGS: usize = 0;
const SEC_STOPS: usize = 1;
const SEC_ROUTES: usize = 2;
const SEC_ROUTE_STOPS: usize = 3;
const SEC_ROUTE_TRIPS: usize = 4;
const SEC_PROFILES: usize = 5;
const SEC_PROFILES_IDX: usize = 6;
const SEC_STOP_ROUTES: usize = 7;
const SEC_STOP_ROUTES_IDX: usize = 8;
const SEC_TRANSFERS: usize = 9;
const SEC_TRANSFERS_IDX: usize = 10;
const SEC_SERVICES: usize = 11;
const SEC_EXCEPTIONS: usize = 12;
const SEC_GRID_CELL_IDS: usize = 13;
const SEC_GRID_CELL_OFF: usize = 14;
const SEC_GRID_STOPS: usize = 15;
const SEC_FEEDS: usize = 16;
const SEC_FEED_TZ: usize = 17;
const SEC_EXCEPTIONS_IDX: usize = 18;
const SEC_STOP_ROUTE_POS: usize = 19;
const SEC_SHAPE_COORDS: usize = 20;
const SEC_ROUTE_SHAPE_IDX: usize = 21;
const SEC_ROUTE_STOP_SHAPE: usize = 22;
const SEC_FEED_MOTIS_PREFIX: usize = 23;
const SEC_STOP_GTFS_ID: usize = 24;
/// v6: fixed-stride trip records, so a trip is addressable by index.
const SEC_ROUTE_TRIP_RECS: usize = 25;
/// v6: `u32[route_count + 1]` CSR prefix giving each route's first trip index.
const SEC_ROUTE_TRIP_OFF: usize = 26;

/// Bytes per v6 trip record: `u32 start_time, profile_id, service_idx, headsign_off`.
const TRIP_REC_BYTES: usize = 16;

const WALK_SPEED_M_S: f64 = 1.33;
/// Access/egress radius: how far we will walk to the first / from the last stop.
const ACCESS_RADIUS_M: f64 = 1000.0;
const MAX_ROUNDS: usize = 6;
const SECS_PER_DAY: u32 = 24 * 3600;
/// Service days after the query day a journey may draw trips from, so a
/// multi-day long-distance trip, or a connection that only runs tomorrow, is
/// still found.
const PLAN_DAYS_AHEAD: i32 = 3;

// --- On-disk fixed records (`#[repr(C, packed)]`, read via `read_at`). ---
// Variable-length sections (ROUTE_TRIPS, PROFILES) are decoded with `uvarint`.

#[repr(C, packed)]
#[derive(Clone, Copy)]
struct StopRec {
    lat_e7: i32,
    lon_e7: i32,
    name_off: u32,
    code_off: u32,
}

#[repr(C, packed)]
#[derive(Clone, Copy)]
struct RouteRec {
    name_off: u32,
    color: u32,
    route_type: u32,
    feed_idx: u32,
    n_stops: u32,
    first_route_stop: u32,
    n_trips: u32,
    trips_off: u32,
}

#[repr(C, packed)]
#[derive(Clone, Copy)]
struct TransferRec {
    to_stop: u32,
    secs: u32,
}

#[repr(C, packed)]
#[derive(Clone, Copy)]
struct ServiceRec {
    weekday_mask: u8,
    _pad: [u8; 3],
    start_date: u32,
    end_date: u32,
}

#[repr(C, packed)]
#[derive(Clone, Copy)]
struct ExcRec {
    service_idx: u32,
    date: u32,
    added: u32,
}

/// A trip decoded from a route's varint block.
#[derive(Clone, Copy)]
struct TripDec {
    start_time: u32,
    profile_id: u32,
    service_idx: u32,
    headsign_off: u32,
}

/// A route's trips, in start-time order, however the pack stores them.
///
/// v6 keeps them in a fixed-stride table, so [`Trips::get`] is an indexed read of 16
/// bytes. That is the point of the format change: the trip scan in [`earliest_trip`]
/// breaks out as soon as a trip's `start_time` passes the bound, but until now
/// [`TransitIndex::route_trips`] had already varint-decoded and heap-allocated *every*
/// trip of the route before the scan began — per route per RAPTOR round, again per leg,
/// and again per departure board. The break could not save the work it was written to
/// save.
///
/// Note what the sorted order does **not** buy: a lower bound. A trip's departure from
/// stop position `p` is `start_time + dep_rel[p]`, and `dep_rel` grows along the route,
/// so a trip starting long before `ready` can still depart after it. Only the upper
/// bound is sound, which is why this exposes indexed access and not a binary search.
///
/// [`Trips::Decoded`] is the pre-v6 path, kept because [`VERSION_MIN`] still accepts
/// those packs; it behaves exactly as before, decode cost included.
enum Trips<'a> {
    Strided { idx: &'a TransitIndex, base: u32, len: u32 },
    Decoded(Vec<TripDec>),
}

impl Trips<'_> {
    fn len(&self) -> usize {
        match self {
            Trips::Strided { len, .. } => *len as usize,
            Trips::Decoded(v) => v.len(),
        }
    }

    fn is_empty(&self) -> bool {
        self.len() == 0
    }

    fn get(&self, i: usize) -> Option<TripDec> {
        if i >= self.len() {
            return None;
        }
        match self {
            Trips::Strided { idx, base, .. } => Some(idx.trip_at(*base + i as u32)),
            Trips::Decoded(v) => Some(v[i]),
        }
    }
}

/// A decoded run-time profile: per-stop arrival/departure offsets relative to
/// the trip's `start_time` (`dep_rel[0] == 0`, `arr_rel[0] <= 0`).
struct ProfileDec {
    arr_rel: Vec<i32>,
    dep_rel: Vec<i32>,
}

/// What a journey leg is; maps onto `RouteService.API.Maneuver` at the JNI
/// boundary (`Walk` → UNSPECIFIED, `Wait` → WAIT, `Ride` → RIDE).
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum LegKind {
    Walk,
    /// Waiting at a stop for the next departure.
    Wait,
    /// Riding a transit vehicle.
    Ride,
}

impl LegKind {
    pub fn is_transit(self) -> bool {
        self == LegKind::Ride
    }
}

/// A single leg of a planned journey, with owned strings ready for JNI.
pub struct TransitLeg {
    pub kind: LegKind,
    /// Route short/long name for a ride (or the awaited route for a wait).
    pub name: String,
    pub feed: String,
    /// Stop name (falling back to `stop_code` when the feed has no name).
    pub from_stop: String,
    pub to_stop: String,
    /// GTFS `trip_headsign`.
    pub headsign: String,
    /// GTFS `route_color` as packed 0xRRGGBB, or 0 when the feed omits it.
    pub route_color: u32,
    pub dep_secs: u32,
    pub arr_secs: u32,
    pub stop_count: i32,
    pub dist_m: f64,
    /// Flat `[lon, lat, lon, lat, ...]` polyline through the leg's stops.
    pub coords: Vec<f64>,
    /// MOTIS/Transitous ids of the ride's board and alight stops (v5 packs only;
    /// empty otherwise, and empty for non-ride legs). The realtime overlay asks
    /// `/stoptimes` about these directly, which is what removed the old
    /// coordinate-to-id round trip through `/map/stops`.
    pub board_stop_motis_id: String,
    pub alight_stop_motis_id: String,
}

/// What keeps an index's bytes alive: an mmap of the pack file in production, or
/// an owned buffer in tests (a `Vec`'s heap allocation doesn't move when the
/// enum does, so `base` stays valid).
enum Backing {
    Mmap(MmapRegion),
    // A transit pack section inside a single-archive mapping: `region` is the
    // whole file, `offset`/`len` the pack. The index reads the pack in place,
    // exactly as from its own file — no copy, no second download.
    Archive {
        region: MmapRegion,
        offset: usize,
        len: usize,
    },
    #[cfg(test)]
    Owned(Vec<u8>),
}

impl Backing {
    fn base(&self) -> *const u8 {
        match self {
            Backing::Mmap(r) => r.base(),
            Backing::Archive { region, offset, .. } => unsafe { region.base().add(*offset) },
            #[cfg(test)]
            Backing::Owned(v) => v.as_ptr(),
        }
    }
    fn len(&self) -> usize {
        match self {
            Backing::Mmap(r) => r.len,
            Backing::Archive { len, .. } => *len,
            #[cfg(test)]
            Backing::Owned(v) => v.len(),
        }
    }
}

/// Read-only transit index for one pack (may hold many merged feeds).
pub struct TransitIndex {
    _backing: Backing,
    base: *const u8,
    stop_count: u32,
    route_count: u32,
    service_count: u32,
    feed_count: u32,
    feed_name_off: u32,
    min_lat_e7: i32,
    min_lon_e7: i32,
    max_lat_e7: i32,
    max_lon_e7: i32,
    // Grid params.
    grid_lat0_e7: i32,
    grid_lon0_e7: i32,
    grid_cell_e7: u32,
    grid_cols: u32,
    grid_cell_count: u32,
    // Section (offset, len) directory.
    sec: [(usize, usize); SECTION_COUNT],
    /// Planet5 (TRIX v8): per section, its ZSEC `(offset, stored_len, block_size)` when the
    /// section is block-compressed; `sec[i]` then holds `(offset, raw_len)` and every read goes
    /// through [`TransitIndex::rd`] / [`TransitIndex::byte`] and the per-thread block cache.
    zsec: [Option<(usize, usize, usize)>; SECTION_COUNT],
    /// Distinguishes this index's blocks in the per-thread cache.
    uid: u64,
    /// Long-distance route classification, built on first use.
    intercity: std::sync::OnceLock<Intercity>,
}

// Read-only after load; the raw pointer is sound to share.
unsafe impl Send for TransitIndex {}
unsafe impl Sync for TransitIndex {}

include!("transit_part1.rs");
include!("transit_zsec.rs");
include!("transit_part2.rs");
include!("transit_part3.rs");
include!("transit_part4.rs");
include!("transit_part5.rs");
include!("transit_part10.rs");
include!("transit_part11.rs");
include!("transit_part13.rs");
#[cfg(test)]
mod tests {
    include!("transit_part6.rs");
    include!("transit_part7.rs");
    include!("transit_part8.rs");
    include!("transit_part9.rs");
    include!("transit_part12.rs");
    include!("transit_part14.rs");
}
