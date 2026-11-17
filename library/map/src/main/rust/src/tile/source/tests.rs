use super::*;
use crate::tile::cache::RangeCache;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use tilecodec::proto::{err, Result};
use tilecodec::stream::RangeReader;

/// The first failure waits, rather than re-requesting on the very next frame. Without this
/// a failing tile is asked for sixty times a second and the on-demand frame loop can never
/// idle, because the tile re-enters the in-flight set as fast as it leaves.
#[test]
fn the_first_failure_backs_off_rather_than_retrying_at_once() {
    assert_eq!(retry_delay_ms(1), RETRY_BASE_MS);
    // A delay under one frame would not be a backoff at all.
    const { assert!(RETRY_BASE_MS > 16) };
}

/// Consecutive failures double, so an unreachable archive settles to a poll.
#[test]
fn consecutive_failures_double_the_wait() {
    assert_eq!(retry_delay_ms(2), RETRY_BASE_MS * 2);
    assert_eq!(retry_delay_ms(3), RETRY_BASE_MS * 4);
    assert_eq!(retry_delay_ms(4), RETRY_BASE_MS * 8);
}

/// The wait saturates rather than growing without bound: a tile that has failed all
/// session must still recover promptly once the network returns.
#[test]
fn the_backoff_saturates_at_the_ceiling() {
    assert_eq!(retry_delay_ms(1000), RETRY_MAX_MS);
    assert_eq!(
        retry_delay_ms(u32::MAX),
        RETRY_MAX_MS,
        "no overflow on a huge attempt count"
    );
    for attempts in 1..200u32 {
        assert!(
            retry_delay_ms(attempts) <= RETRY_MAX_MS,
            "{attempts} exceeded the ceiling"
        );
    }
}

/// Monotonic, and a zero attempt count is treated as the first failure rather than
/// shifting by -1 or returning no delay at all.
#[test]
fn the_backoff_is_monotonic_and_defends_a_zero_count() {
    assert_eq!(retry_delay_ms(0), RETRY_BASE_MS);
    for attempts in 1..200u32 {
        assert!(retry_delay_ms(attempts) <= retry_delay_ms(attempts + 1));
    }
}

struct Fixture {
    dir: PathBuf,
}

impl Fixture {
    fn new(name: &str) -> Fixture {
        let dir = std::env::temp_dir().join(format!("rangesource-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).expect("temp dir");
        Fixture { dir }
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

/// Records every range asked for, and answers with a canned status and body.
struct Fake {
    status: u16,
    body: Vec<u8>,
    fail: bool,
    ranges: std::sync::Mutex<Vec<String>>,
}

impl Fake {
    fn ok(body: Vec<u8>) -> Fake {
        Fake {
            status: 206,
            body,
            fail: false,
            ranges: std::sync::Mutex::new(Vec::new()),
        }
    }

    fn logged(&self) -> Vec<String> {
        self.ranges.lock().unwrap().clone()
    }
}

impl RangeFetcher for Fake {
    fn fetch(&self, _url: &str, range: &str) -> Result<RangeResponse> {
        self.ranges.lock().unwrap().push(range.to_string());
        if self.fail {
            return err("no route to host");
        }
        Ok(RangeResponse {
            status: self.status,
            body: self.body.clone(),
        })
    }
}

const URL: &str = BASEMAP_ARCHIVE_URL;

fn reader(dir: &std::path::Path, clock: Arc<AtomicU64>, fetcher: Fake) -> CachingRangeReader<Fake> {
    let c = clock.clone();
    let cache = RangeCache::with_clock(
        dir,
        "v1|test",
        crate::tile::cache::DEFAULT_MAX_BYTES,
        Box::new(move || c.load(Ordering::SeqCst)),
    );
    CachingRangeReader::new(URL, cache, fetcher)
}

#[test]
fn the_range_header_names_the_exact_byte_range() {
    let f = Fixture::new("header");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let r = reader(&f.dir, clock, Fake::ok(vec![0u8; 127]));
    r.read(0, 127).expect("read");
    assert_eq!(r.fetcher.logged()[0], "bytes=0-126");
    r.read(2_409_278, 4).ok();
    assert_eq!(r.fetcher.logged()[1], "bytes=2409278-2409281");
}

#[test]
fn a_fresh_entry_is_served_without_touching_the_network() {
    let f = Fixture::new("fresh");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    {
        let r = reader(&f.dir, clock.clone(), Fake::ok(vec![7u8; 16]));
        assert_eq!(r.read(0, 16).unwrap(), vec![7u8; 16]);
        assert_eq!(r.fetcher.logged().len(), 1);
    }
    // A second reader over the same directory: the entry is on disk and fresh.
    let r = reader(&f.dir, clock, Fake::ok(vec![9u8; 16]));
    // The first read of a session always revalidates — it is the archive header, and a stale
    // one would validate the very cache it is meant to invalidate.
    assert_eq!(
        r.read(0, 16).unwrap(),
        vec![9u8; 16],
        "the header is refetched"
    );
    assert_eq!(r.fetcher.logged().len(), 1);
    // Everything after it is served from the cache.
    assert_eq!(r.read(0, 16).unwrap(), vec![9u8; 16], "the cached bytes");
    assert_eq!(r.fetcher.logged().len(), 1, "no second request");
}

/// The archive header cannot be answered from the cache it decides the fate of.
///
/// `basemap_origin` keys the cache on the archive's `build_id`, which is read from the header.
/// If the header itself comes from the cache it reports the *previous* build's id, the marker
/// matches, and every leaf index in the cache goes on addressing byte offsets from an archive
/// that no longer exists. Nothing else catches it, because the archive is deliberately
/// republished under the same URL, so the entry survives until the 24-hour refresh — which in
/// practice meant a map drawn from two different archives at once.
#[test]
fn the_first_read_of_a_session_bypasses_a_fresh_cache() {
    let f = Fixture::new("prefix");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    {
        let r = reader(&f.dir, clock.clone(), Fake::ok(vec![1u8; 16]));
        assert_eq!(r.read(0, 16).unwrap(), vec![1u8; 16]);
    }
    // Same directory, same clock, so the entry is unambiguously fresh — and the archive has
    // been republished behind it.
    let r = reader(&f.dir, clock, Fake::ok(vec![2u8; 16]));
    assert_eq!(
        r.read(0, 16).unwrap(),
        vec![2u8; 16],
        "a fresh cache must not hide a republished archive from the header read",
    );
}

/// Offline, the rule reverses: a stale header beats no map at all.
#[test]
fn the_first_read_still_uses_the_cache_when_offline() {
    let f = Fixture::new("prefix_offline");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    {
        let r = reader(&f.dir, clock.clone(), Fake::ok(vec![3u8; 16]));
        assert_eq!(r.read(0, 16).unwrap(), vec![3u8; 16]);
    }
    let r = reader(&f.dir, clock, Fake::ok(vec![4u8; 16]));
    r.set_online(false);
    assert_eq!(r.read(0, 16).unwrap(), vec![3u8; 16], "the cached header");
    assert!(
        r.fetcher.logged().is_empty(),
        "and no request attempted"
    );
}

#[test]
fn a_stale_entry_is_refetched_when_online() {
    let f = Fixture::new("refetch");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    {
        let r = reader(&f.dir, clock.clone(), Fake::ok(vec![1u8; 4]));
        r.read(0, 4).unwrap();
    }
    clock.fetch_add(
        crate::tile::cache::REFRESH_INTERVAL_MS + 1,
        Ordering::SeqCst,
    );
    let r = reader(&f.dir, clock, Fake::ok(vec![2u8; 4]));
    assert_eq!(r.read(0, 4).unwrap(), vec![2u8; 4], "the refreshed bytes");
    assert_eq!(r.fetcher.logged().len(), 1);
}

#[test]
fn a_stale_entry_is_served_offline_rather_than_failing() {
    // The whole point of the disk cache.
    let f = Fixture::new("offline");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    {
        let r = reader(&f.dir, clock.clone(), Fake::ok(vec![1u8; 4]));
        r.read(0, 4).unwrap();
    }
    clock.fetch_add(
        crate::tile::cache::REFRESH_INTERVAL_MS * 10,
        Ordering::SeqCst,
    );
    let r = reader(&f.dir, clock, Fake::ok(vec![2u8; 4]));
    r.set_online(false);
    assert_eq!(r.read(0, 4).unwrap(), vec![1u8; 4], "the stale bytes");
    assert!(
        r.fetcher.logged().is_empty(),
        "offline must not attempt a request"
    );
}

/// A failed refetch is an error: the reader never serves a stale entry for a
/// range whose refetch failed.
#[test]
fn a_failed_fetch_is_an_error_not_a_stale_fallback() {
    let f = Fixture::new("failover");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    {
        let r = reader(&f.dir, clock.clone(), Fake::ok(vec![1u8; 4]));
        r.read(0, 4).unwrap();
    }
    clock.fetch_add(
        crate::tile::cache::REFRESH_INTERVAL_MS + 1,
        Ordering::SeqCst,
    );
    let failing = Fake {
        status: 206,
        body: Vec::new(),
        fail: true,
        ranges: std::sync::Mutex::new(Vec::new()),
    };
    let r = reader(&f.dir, clock, failing);
    assert!(
        r.read(0, 4).is_err(),
        "a failed refetch must not serve the stale entry"
    );
}

#[test]
fn a_failed_fetch_with_nothing_cached_propagates() {
    let f = Fixture::new("nofallback");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let failing = Fake {
        status: 206,
        body: Vec::new(),
        fail: true,
        ranges: std::sync::Mutex::new(Vec::new()),
    };
    let r = reader(&f.dir, clock, failing);
    assert!(r.read(0, 4).is_err());
}

/// A non-2xx is an error too, cached entry or not.
#[test]
fn a_server_error_is_an_error_not_a_cache_fallback() {
    let f = Fixture::new("servererror");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    {
        let r = reader(&f.dir, clock.clone(), Fake::ok(vec![1u8; 4]));
        r.read(0, 4).unwrap();
    }
    clock.fetch_add(
        crate::tile::cache::REFRESH_INTERVAL_MS + 1,
        Ordering::SeqCst,
    );
    let erroring = Fake {
        status: 503,
        body: vec![9u8; 4],
        fail: false,
        ranges: std::sync::Mutex::new(Vec::new()),
    };
    let r = reader(&f.dir, clock, erroring);
    assert!(
        r.read(0, 4).is_err(),
        "a 503 must not serve the stale entry"
    );
    // With nothing cached for a different range, it is an error.
    assert!(r.read(64, 4).is_err());
}

#[test]
fn a_whole_file_200_reply_to_a_range_request_is_never_cached() {
    // This is the failure the original records: a 200 whole-file reply stored as a
    // range is what produced the "Prefix string too short" pmtiles header errors.
    let f = Fixture::new("wholefile");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let whole = Fake {
        status: 200,
        body: vec![3u8; 4096],
        fail: false,
        ranges: std::sync::Mutex::new(Vec::new()),
    };
    let r = reader(&f.dir, clock, whole);
    assert_eq!(
        r.read(0, 16).unwrap().len(),
        4096,
        "returned to the caller, which rejects it"
    );
    let cached = std::fs::read_dir(&f.dir)
        .unwrap()
        .flatten()
        .filter(|e| e.path().extension().is_some_and(|x| x == "data"))
        .count();
    assert_eq!(cached, 0, "nothing may reach the disk");
}

#[test]
fn a_206_whose_body_is_the_wrong_length_is_never_cached() {
    let f = Fixture::new("shortpartial");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let r = reader(&f.dir, clock, Fake::ok(vec![3u8; 8]));
    r.read(0, 16).unwrap();
    let cached = std::fs::read_dir(&f.dir)
        .unwrap()
        .flatten()
        .filter(|e| e.path().extension().is_some_and(|x| x == "data"))
        .count();
    assert_eq!(cached, 0, "a short partial may not be cached");
}
/// **The whole point of carrying a `build_id`.** The archive is republished under the *same* URL
/// every build, so the URL alone cannot invalidate anything: a cached leaf index keeps addressing
/// byte offsets from the build it came from, and a user sits on a stale map with no way to
/// notice. The id is what changes.
///
/// The origin marker is computed from a header prefix fetch *before* the cache opens, and the
/// cache opens once with the full marker — so a republish wipes on open, not on a later reset.
#[test]
fn republishing_under_the_same_url_wipes_the_cache_when_the_build_id_changes() {
    let dir = std::env::temp_dir().join(format!("mamaps_origin_{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let url = "https://example.invalid/basemap.mamaps";

    // First run against a brand-new cache: entries written under build 1 survive.
    let cache = RangeCache::open(&dir, &basemap_origin(url, 1), 1 << 20);
    cache.write(&RangeCache::key(url, "bytes=0-15"), b"the first build");
    cache.write(&RangeCache::key(url, "bytes=64-79"), b"a leaf of one  ");

    // Second run, same build: the marker matches, so everything survives —
    // including across restarts. A marker scheme that wiped here would clear
    // the whole cache on every single start.
    let cache = RangeCache::open(&dir, &basemap_origin(url, 1), 1 << 20);
    assert!(
        cache.read(&RangeCache::key(url, "bytes=0-15")).is_some(),
        "restarting against the same build wiped the cache",
    );
    assert!(cache.read(&RangeCache::key(url, "bytes=64-79")).is_some());

    // A republish, same URL. Every entry goes, because every offset in it belongs to build 1.
    let cache = RangeCache::open(&dir, &basemap_origin(url, 2), 1 << 20);
    for range in ["bytes=0-15", "bytes=64-79"] {
        assert!(
            cache.read(&RangeCache::key(url, range)).is_none(),
            "a republish under the same name left build 1's {range} in the cache",
        );
    }
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn an_origin_marker_names_the_layout_the_url_and_the_build() {
    let url = "https://example.invalid/basemap.mamaps";
    assert!(basemap_origin(url, 1).starts_with(&format!("{CACHE_FORMAT}|{url}|")));
    assert_eq!(basemap_origin(url, 1), basemap_origin(url, 1), "stable");
    assert_ne!(
        basemap_origin(url, 1),
        basemap_origin(url, 2),
        "a republish"
    );
    // A different archive at a different URL is a different origin even at the same id, which is
    // what keeps a debug build pointed at a local file from poisoning the real cache.
    assert_ne!(
        basemap_origin(url, 1),
        basemap_origin("https://other.invalid/x", 1)
    );
}

/// Batch reads overlap latency: two ranges cost each its own request, in order, with one
/// request per range — no merging, no reordering.
///
/// The `Fake` fetcher has no `fetch_batch` override, so the wave serializes through the
/// default — which is exactly what this pins: the batch structure (one slot per range, in
/// order) holds regardless of whether the transport multiplexes the wave.
#[test]
fn a_batch_read_issues_one_request_per_range_in_order() {
    let f = Fixture::new("batch");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let r = reader(&f.dir, clock, Fake::ok(vec![7u8; 64]));
    let out = read_batch(&r, &[(0, 8), (100, 8), (200, 8)]);
    assert_eq!(out.len(), 3);
    for slot in &out {
        assert_eq!(slot.as_ref().unwrap().len(), 64);
    }
    // Result slots follow request order (handles join in order); the issue order across
    // the scoped threads is nondeterministic by design, so the log compares as a set:
    // one request per range, no merging, no duplication.
    let mut logged = r.fetcher.logged();
    logged.sort();
    assert_eq!(
        logged,
        vec!["bytes=0-7", "bytes=100-107", "bytes=200-207"],
    );
}

/// A failed range fails its own slot, not the batch: the other ranges still land.
#[test]
fn a_batch_read_isolates_a_failed_range() {
    let f = Fixture::new("batch_fail");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let failing = Fake {
        status: 503,
        body: Vec::new(),
        fail: false,
        ranges: std::sync::Mutex::new(Vec::new()),
    };
    let r = reader(&f.dir, clock, failing);
    let out = read_batch(&r, &[(0, 4), (64, 4)]);
    assert_eq!(out.len(), 2);
    assert!(out[0].is_err() && out[1].is_err());
    assert_eq!(r.fetcher.logged().len(), 2);
}

/// A single-range batch takes the serial path with identical results.
#[test]
fn a_single_range_batch_reads_like_a_plain_read() {
    let f = Fixture::new("batch_single");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let r = reader(&f.dir, clock, Fake::ok(vec![5u8; 16]));
    let out = read_batch(&r, &[(0, 16)]);
    assert_eq!(out.len(), 1);
    assert_eq!(out[0].as_ref().unwrap(), &vec![5u8; 16]);
    assert_eq!(r.fetcher.logged().len(), 1);
}
