use super::fetch::RangeFetcher;
use crate::tile::cache::RangeCache;
use tilecodec::proto::{err, Result};
use tilecodec::stream::RangeReader;

pub struct CachingRangeReader<F: RangeFetcher> {
    url: String,
    cache: RangeCache,
    // `pub(crate)` so the policy tests beside this module can count requests,
    // as they did when they were an inline `mod tests`. No logic change.
    pub(crate) fetcher: F,
    /// Set from Kotlin, which owns the `ConnectivityManager`. When offline the network is
    /// not attempted at all and a stale entry is served instead.
    online: std::sync::atomic::AtomicBool,
    /// False until the first read of the session has gone to the network.
    ///
    /// That read is the archive header, and it is the one entry the cache must not answer from
    /// itself — see the note in [`RangeReader::read`]. Shared across a surface's workers (see
    /// [`new_shared`](Self::new_shared)), so one cold start pays one revalidation, not one per
    /// worker: each reader used to carry its own flag, and four workers meant four forced
    /// network reads of the same prefix before any tile was fetched.
    prefix_checked: std::sync::Arc<std::sync::atomic::AtomicBool>,
}

impl<F: RangeFetcher> CachingRangeReader<F> {
    pub fn new(url: impl Into<String>, cache: RangeCache, fetcher: F) -> Self {
        CachingRangeReader {
            url: url.into(),
            cache,
            fetcher,
            online: std::sync::atomic::AtomicBool::new(true),
            prefix_checked: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
        }
    }

    /// [`new`](Self::new) with a session-wide revalidation gate shared by every worker of one
    /// surface. The first read across *all* readers sharing the gate goes to the network; the
    /// rest serve from cache per the usual freshness policy.
    pub fn new_shared(
        url: impl Into<String>,
        cache: RangeCache,
        fetcher: F,
        prefix_checked: std::sync::Arc<std::sync::atomic::AtomicBool>,
    ) -> Self {
        CachingRangeReader {
            url: url.into(),
            cache,
            fetcher,
            online: std::sync::atomic::AtomicBool::new(true),
            prefix_checked,
        }
    }

    pub fn set_online(&self, online: bool) {
        self.online
            .store(online, std::sync::atomic::Ordering::Relaxed);
    }

    fn is_online(&self) -> bool {
        self.online.load(std::sync::atomic::Ordering::Relaxed)
    }

    /// One range through the full policy: revalidation gate, disk cache, single fetch.
    ///
    /// The batch path inlines its own cache pass (so a wave costs one JNI crossing), and this
    /// is what serves sub-two-range batches plus the archive open prefix — same policy, one
    /// range at a time.
    fn read_single(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        if length == 0 {
            return Ok(Vec::new());
        }
        let range = format!("bytes={}-{}", offset, offset + length as u64 - 1);
        let key = RangeCache::key(&self.url, &range);

        // The archive header decides whether every other entry is still valid, so it cannot be
        // served from the cache it is meant to validate. Once per session the first read — which
        // is always the header, because nothing can be located without it — goes to the network.
        //
        // Without this the staleness is self-consistent: a cached prefix reports the *previous*
        // build's id, `basemap_origin` therefore matches, the cache is kept, and every leaf index
        // in it keeps addressing the previous build's byte offsets. The archive is republished
        // under a stable URL by design, so nothing else ever catches it, and the entry only
        // expires on the 24-hour refresh interval. Republishing twice in an afternoon meant a map
        // built from two different archives at once: bodies that would not inflate, and a
        // coastline drawn from offsets that had moved.
        //
        // One small request per app start, and only when online.
        let first = !self
            .prefix_checked
            .swap(true, std::sync::atomic::Ordering::Relaxed);
        let revalidate = first && self.is_online();

        let cached = self.cache.read(&key);
        if let Some(entry) = &cached {
            if !revalidate && (self.cache.is_fresh(entry) || !self.is_online()) {
                return Ok(entry.body.clone());
            }
        }

        let response = self.fetcher.fetch(&self.url, &range)?;

        if !(200..300).contains(&response.status) {
            return err(format!(
                "range request for {range} of {} failed with HTTP {}",
                self.url, response.status
            ));
        }
        // Only a body we trust: a 206 partial whose length matches the request.
        if response.status == 206 && response.body.len() == length as usize {
            self.cache.write(&key, &response.body);
        }
        Ok(response.body)
    }
}

/// Read a batch of ranges, returning each range's bytes in order.
///
/// Thin forwarder to [`RangeReader::read_batch`]: one disk-cache pass, then one network
/// wave for every miss. Failures are per-range — a failed range is an error for that slot,
/// never a poisoned batch — and caching applies per range (fresh entries are served
/// without touching the network, exactly as in a single read).
pub fn read_batch<F: RangeFetcher>(
    reader: &CachingRangeReader<F>,
    requests: &[(u64, u32)],
) -> Vec<Result<Vec<u8>>> {
    reader.read_batch(requests)
}

impl<F: RangeFetcher> RangeReader for CachingRangeReader<F> {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        let out = self.read_batch(&[(offset, length)]);
        debug_assert_eq!(out.len(), 1);
        out.into_iter().next().unwrap_or_else(|| err("a range read left its slot unresolved"))
    }

    /// Order recently written ranges on disk, one pass per wave.
    ///
    /// Workers call this after each fetch wave: it drains the keys written since the last
    /// call and durability-flushes them together, instead of the fsync per range the old
    /// write path paid. Best-effort — a missed flush only risks a re-fetch.
    fn flush_pending(&self) {
        for key in self.cache.drain() {
            self.cache.flush_entry(&key);
        }
    }

    /// The batch policy: one cache pass, then one network wave for every miss.
    ///
    /// This is what makes the archive's two-wave batch (`tiles`) cost two network round
    /// trips instead of 2N: each wave arrives here as one `read_batch`, the fresh/miss split
    /// is decided per range from the disk cache (no I/O beyond the cache reads themselves),
    /// and every miss goes out in a single [`fetch_batch`](RangeFetcher::fetch_batch) — one
    /// JNI crossing and one OkHttp dispatcher wave, not one crossing per range.
    ///
    /// The first-read revalidation gate (see `read_single`) applies per wave rather than
    /// per range: the first wave of a session revalidates its misses, exactly as the first
    /// read used to.
    fn read_batch(&self, requests: &[(u64, u32)]) -> Vec<Result<Vec<u8>>> {
        if requests.len() < 2 {
            return requests.iter().map(|&(o, l)| self.read_single(o, l)).collect();
        }
        // Pass 1: every range against the disk cache. Fresh hits (or offline hits) answer
        // immediately; everything else queues for the network wave. No I/O here beyond the
        // cache reads themselves.
        let first = !self.prefix_checked.swap(true, std::sync::atomic::Ordering::Relaxed);
        let revalidate = first && self.is_online();
        let mut out: Vec<Option<Result<Vec<u8>>>> = Vec::with_capacity(requests.len());
        let mut miss_slots: Vec<usize> = Vec::new();
        let mut miss_ranges: Vec<String> = Vec::new();
        let mut miss_keys: Vec<String> = Vec::new();
        let mut miss_lengths: Vec<u32> = Vec::new();
        for _ in requests {
            out.push(None);
        }
        for (slot, &(offset, length)) in requests.iter().enumerate() {
            if length == 0 {
                out[slot] = Some(Ok(Vec::new()));
                continue;
            }
            let range = format!("bytes={}-{}", offset, offset + length as u64 - 1);
            let key = RangeCache::key(&self.url, &range);
            match self.cache.read(&key) {
                Some(entry)
                    if !revalidate && (self.cache.is_fresh(&entry) || !self.is_online()) =>
                {
                    out[slot] = Some(Ok(entry.body.clone()));
                }
                _ => {
                    miss_slots.push(slot);
                    miss_ranges.push(range);
                    miss_keys.push(key);
                    miss_lengths.push(length);
                }
            }
        }
        // Wave: every miss in one `fetch_batch` — one JNI crossing, one dispatcher wave.
        if !miss_slots.is_empty() {
            let fetched = self.fetcher.fetch_batch(&self.url, &miss_ranges);
            debug_assert_eq!(fetched.len(), miss_slots.len());
            for (i, slot) in miss_slots.iter().enumerate() {
                let range = &miss_ranges[i];
                let key = &miss_keys[i];
                let length = miss_lengths[i];
                match fetched.get(i) {
                    Some(Ok(response)) => {
                        if !(200..300).contains(&response.status) {
                            out[*slot] = Some(err(format!(
                                "range request for {range} of {} failed with HTTP {}",
                                self.url, response.status
                            )));
                            continue;
                        }
                        // Only a body we trust: a 206 partial whose length matches the request.
                        if response.status == 206 && response.body.len() == length as usize {
                            self.cache.write(key, &response.body);
                        }
                        out[*slot] = Some(Ok(response.body.clone()));
                    }
                    Some(Err(e)) => out[*slot] = Some(Err(tilecodec::proto::Error(e.0.clone()))),
                    None => {
                        out[*slot] = Some(err(format!(
                            "range request for {range} of {} left its slot unresolved",
                            self.url
                        )));
                    }
                }
            }
        }
        out.into_iter()
            .map(|slot| slot.unwrap_or_else(|| err("a range batch left a slot unresolved")))
            .collect()
    }
}
