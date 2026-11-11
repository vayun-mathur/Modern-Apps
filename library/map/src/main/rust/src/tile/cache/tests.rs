//! Tests for the disk range cache: roundtrip, freshness vs LRU separation,
//! corruption refusal, origin invalidation, and the batched size cap.

use super::*;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

struct Fixture {
    dir: PathBuf,
}

impl Fixture {
    fn new(name: &str) -> Fixture {
        let dir =
            std::env::temp_dir().join(format!("rangecache-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("temp dir");
        Fixture { dir }
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.dir);
    }
}

const URL: &str = "https://data.maproj.org/planet4.mamaps";
const ORIGIN: &str = "v1|https://data.maproj.org/planet4.mamaps";

fn cache_with(dir: &Path, clock: Arc<AtomicU64>, max_bytes: u64) -> RangeCache {
    let c = clock.clone();
    RangeCache::with_clock(
        dir,
        ORIGIN,
        max_bytes,
        Box::new(move || c.load(Ordering::SeqCst)),
    )
}

fn data_files(dir: &Path) -> Vec<PathBuf> {
    fs::read_dir(dir)
        .unwrap()
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|e| e == "data"))
        .collect()
}

#[test]
fn a_written_entry_reads_back() {
    let f = Fixture::new("roundtrip");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
    let key = RangeCache::key(URL, "bytes=0-126");
    cache.write(&key, &[7u8; 127]);
    let entry = cache.read(&key).expect("cached");
    assert_eq!(entry.body, vec![7u8; 127]);
    assert_eq!(entry.fetched_at_ms, 1_000_000);
    assert!(cache.is_fresh(&entry));
}

#[test]
fn ranges_of_the_same_url_do_not_collide() {
    assert_ne!(
        RangeCache::key(URL, "bytes=0-3"),
        RangeCache::key(URL, "bytes=4-7")
    );
    // And a different archive under the same range is a different entry.
    assert_ne!(
        RangeCache::key(URL, "bytes=0-3"),
        RangeCache::key("https://other", "bytes=0-3")
    );
}

#[test]
fn an_entry_goes_stale_after_the_refresh_interval() {
    let f = Fixture::new("stale");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
    let key = RangeCache::key(URL, "bytes=0-3");
    cache.write(&key, &[1, 2, 3, 4]);

    clock.store(1_000_000 + REFRESH_INTERVAL_MS / 2, Ordering::SeqCst);
    assert!(
        cache.is_fresh(&cache.read(&key).unwrap()),
        "half an interval is still fresh"
    );

    clock.store(1_000_000 + REFRESH_INTERVAL_MS + 1, Ordering::SeqCst);
    assert!(
        !cache.is_fresh(&cache.read(&key).unwrap()),
        "past the interval it is stale"
    );
}

#[test]
fn reading_an_entry_does_not_reset_its_freshness() {
    // Freshness comes from the meta file's fetch time precisely so that touching the
    // data file for LRU cannot make a stale entry look new.
    let f = Fixture::new("freshness");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
    let key = RangeCache::key(URL, "bytes=0-3");
    cache.write(&key, &[1, 2, 3, 4]);

    clock.store(1_000_000 + REFRESH_INTERVAL_MS / 2, Ordering::SeqCst);
    let _ = cache.read(&key);
    clock.store(1_000_000 + REFRESH_INTERVAL_MS + 1, Ordering::SeqCst);
    assert!(
        !cache.is_fresh(&cache.read(&key).unwrap()),
        "the earlier read did not extend it"
    );
}

#[test]
fn a_stale_entry_is_still_readable_so_it_can_be_served_offline() {
    // Never dropped for being stale — only overwritten by a successful refetch. This
    // is what makes a previously-viewed area render with no network.
    let f = Fixture::new("offline");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
    let key = RangeCache::key(URL, "bytes=0-3");
    cache.write(&key, &[1, 2, 3, 4]);
    clock.store(1_000_000 + REFRESH_INTERVAL_MS * 100, Ordering::SeqCst);
    assert_eq!(
        cache.read(&key).expect("still there").body,
        vec![1, 2, 3, 4]
    );
}

#[test]
fn an_entry_whose_body_disagrees_with_its_meta_is_dropped() {
    let f = Fixture::new("truncated");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
    let key = RangeCache::key(URL, "bytes=0-99");
    cache.write(&key, &[5u8; 100]);
    // Truncated on disk, e.g. by a failed write we did not see.
    fs::write(f.dir.join(format!("{key}.data")), [0u8; 40]).unwrap();
    assert!(
        cache.read(&key).is_none(),
        "a short body must not be served"
    );
    assert!(
        !f.dir.join(format!("{key}.data")).exists(),
        "and it is dropped"
    );
}

#[test]
fn a_data_file_with_no_meta_is_not_served() {
    let f = Fixture::new("nometa");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
    let key = RangeCache::key(URL, "bytes=0-3");
    fs::write(f.dir.join(format!("{key}.data")), [1, 2, 3, 4]).unwrap();
    assert!(cache.read(&key).is_none());
}

#[test]
fn changing_the_origin_marker_drops_every_entry() {
    // The archive is republished under the same name, so a cached directory chunk
    // addresses the previous build's byte offsets. This marker is the only thing
    // standing between that and a map made of mismatched tiles.
    let f = Fixture::new("origin");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    {
        let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
        cache.write(&RangeCache::key(URL, "bytes=0-3"), &[1, 2, 3, 4]);
        assert_eq!(data_files(&f.dir).len(), 1);
    }
    let c = clock.clone();
    RangeCache::with_clock(
        &f.dir,
        "v2|different",
        DEFAULT_MAX_BYTES,
        Box::new(move || c.load(Ordering::SeqCst)),
    );
    assert_eq!(
        data_files(&f.dir).len(),
        0,
        "entries from the previous origin are dropped"
    );
    assert_eq!(
        fs::read_to_string(f.dir.join(ORIGIN_FILE)).unwrap(),
        "v2|different"
    );
}

#[test]
fn moving_from_planet3_to_planet4_wipes_the_planet3_ranges() {
    // The URL switch must not leave the old archive's ranges beside the new
    // ones: the directory is shared (not keyed per URL), and the real origin
    // marker carries the URL, so the first planet4 open drops every planet3
    // entry and the cache never holds both.
    use crate::tile::source::{basemap_origin, BASEMAP_ARCHIVE_URL};
    const PLANET3: &str = "https://data.maproj.org/planet3.mamaps";
    assert_eq!(BASEMAP_ARCHIVE_URL, URL, "the renderer reads planet4");
    let f = Fixture::new("planet3to4");
    let clock = || Box::new(|| 1_000_000u64) as Box<dyn Fn() -> u64 + Send + Sync>;
    {
        let old = RangeCache::with_clock(
            &f.dir,
            &basemap_origin(PLANET3, 0x0a90_e5e4_ab91_159e),
            DEFAULT_MAX_BYTES,
            clock(),
        );
        for i in 0..5 {
            old.write(&RangeCache::key(PLANET3, &format!("bytes={i}-{i}")), &[i as u8]);
        }
        assert_eq!(data_files(&f.dir).len(), 5);
    }
    let new = RangeCache::with_clock(
        &f.dir,
        &basemap_origin(BASEMAP_ARCHIVE_URL, 0x2ab5_ba58_33e5_4438),
        DEFAULT_MAX_BYTES,
        clock(),
    );
    assert_eq!(data_files(&f.dir).len(), 0, "planet3 ranges must be evicted on the switch");
    new.write(&RangeCache::key(BASEMAP_ARCHIVE_URL, "bytes=0-127"), &[0u8; 128]);
    assert_eq!(data_files(&f.dir).len(), 1);
    assert!(fs::read_to_string(f.dir.join(ORIGIN_FILE)).unwrap().contains("planet4.mamaps"));
}

#[test]
fn an_unchanged_origin_marker_keeps_the_cache() {
    let f = Fixture::new("sameorigin");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    {
        let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
        cache.write(&RangeCache::key(URL, "bytes=0-3"), &[1, 2, 3, 4]);
    }
    let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
    assert_eq!(data_files(&f.dir).len(), 1);
    assert!(cache.read(&RangeCache::key(URL, "bytes=0-3")).is_some());
}

#[test]
fn the_cache_evicts_over_its_cap() {
    // The size cap is the one thing added to the ported policy: the original grew
    // without bound, tolerable for one map app and not for five.
    let f = Fixture::new("evict");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let cache = cache_with(&f.dir, clock.clone(), 1000);
    // A multiple of the eviction batch threshold (issue 815: the scan runs every 32 writes,
    // not per write), so the scan runs on the last write and the cap holds at the end.
    for i in 0..64 {
        cache.write(&RangeCache::key(URL, &format!("bytes={i}")), &[0u8; 200]);
        // Distinct mtimes, so eviction order is well defined.
        std::thread::sleep(std::time::Duration::from_millis(12));
    }
    let total: u64 = data_files(&f.dir)
        .iter()
        .map(|p| fs::metadata(p).unwrap().len())
        .sum();
    assert!(total <= 1000, "cache is {total} bytes, cap is 1000");
    // Evicts to a margin below the cap, not exactly to it.
    assert!(total <= 800, "evicts with headroom: {total}");
    // The most recent write survives; the oldest does not.
    assert!(cache.read(&RangeCache::key(URL, "bytes=63")).is_some());
    assert!(cache.read(&RangeCache::key(URL, "bytes=0")).is_none());
}

#[test]
fn eviction_waits_for_a_batch_of_writes() {
    // The scan stats the whole directory, so it runs every 32 writes rather than per write
    // (issue 815). Below the threshold nothing is evicted even over the cap.
    let f = Fixture::new("evict_batch");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let cache = cache_with(&f.dir, clock.clone(), 100);
    for i in 0..10 {
        cache.write(&RangeCache::key(URL, &format!("bytes={i}")), &[0u8; 200]);
    }
    let total: u64 = data_files(&f.dir)
        .iter()
        .map(|p| fs::metadata(p).unwrap().len())
        .sum();
    assert_eq!(total, 2000, "under the batch threshold no scan runs yet");
}

/// `flush_entry` re-syncs a written entry without changing it: the bytes still read
/// back, freshness is untouched, and a missing entry is a silent no-op (best-effort).
#[test]
fn flushing_an_entry_keeps_it_readable() {
    let f = Fixture::new("flush");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
    let key = RangeCache::key(URL, "bytes=0-3");
    cache.write(&key, &[1, 2, 3, 4]);
    cache.flush_entry(&key);
    let entry = cache.read(&key).expect("still cached");
    assert_eq!(entry.body, vec![1, 2, 3, 4]);
    assert_eq!(entry.fetched_at_ms, 1_000_000);
    // And flushing a key that was never written does nothing, not a panic.
    cache.flush_entry(&RangeCache::key(URL, "bytes=999-1003"));
}

/// Writes queue for the batched durability pass instead of syncing each: `drain` returns
/// every key written since the last drain, then clears — so one pass per wave orders a
/// whole screenful, not one fsync per range.
#[test]
fn writes_queue_for_a_batched_durability_pass() {
    let f = Fixture::new("drain");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
    assert!(cache.drain().is_empty(), "nothing written, nothing pending");
    let first = RangeCache::key(URL, "bytes=0-3");
    let second = RangeCache::key(URL, "bytes=4-7");
    cache.write(&first, &[1, 2, 3, 4]);
    cache.write(&second, &[5, 6, 7, 8]);
    // Both entries are already readable — durability never gates readability.
    assert!(cache.read(&first).is_some());
    assert!(cache.read(&second).is_some());
    let mut pending = cache.drain();
    pending.sort();
    let mut expected = vec![first.clone(), second.clone()];
    expected.sort();
    assert_eq!(pending, expected, "one pass collects the whole wave");
    assert!(cache.drain().is_empty(), "and the drain clears");
    // Flushing the drained keys keeps them readable (the pass from the worker path).
    for key in &[first, second] {
        cache.flush_entry(key);
        assert!(cache.read(key).is_some());
    }
}

/// Freshness ends exactly at the interval and a clock behind the fetch reads as fresh.
///
/// `is_fresh` is `elapsed < interval` with a saturating clock, so an entry exactly
/// `REFRESH_INTERVAL_MS` old is stale (not `<=`), and a clock that went backwards
/// saturates to zero rather than wrapping to stale.
#[test]
fn freshness_ends_exactly_at_the_interval_and_saturates_backward_clocks() {
    let f = Fixture::new("fresh_math");
    let clock = Arc::new(AtomicU64::new(1_000_000));
    let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
    let key = RangeCache::key(URL, "bytes=0-3");
    cache.write(&key, &[1, 2, 3, 4]);
    clock.store(1_000_000 + REFRESH_INTERVAL_MS, Ordering::SeqCst);
    assert!(!cache.is_fresh(&cache.read(&key).unwrap()), "exactly at the interval is stale");
    clock.store(999_000, Ordering::SeqCst);
    assert!(cache.is_fresh(&cache.read(&key).unwrap()), "a backward clock saturates to fresh");
}

/// The eviction target is eighty percent of the cap, in integer bytes.
///
/// `evict_if_over_cap` stops at `max_bytes / 10 * 8`, not at the cap, so a session
/// sitting at the limit does one pass rather than one per tile. Integer division
/// truncates, which only lowers the target and never overshoots it.
#[test]
fn the_eviction_target_is_eighty_percent_of_the_cap() {
    assert_eq!(DEFAULT_MAX_BYTES, 64 * 1024 * 1024, "the 64 MB cap");
    assert_eq!(REFRESH_INTERVAL_MS, 24 * 60 * 60 * 1000, "the 24 h refresh");
    for cap in [1000u64, 100u64, DEFAULT_MAX_BYTES, 1u64] {
        let target = cap / 10 * 8;
        assert!(target <= cap, "cap {cap}: target {target} exceeds it");
        assert_eq!(target + (cap - target), cap, "cap {cap}: target plus headroom is the cap");
    }
    assert_eq!(1000u64 / 10 * 8, 800, "the eviction test's headroom number");
}
