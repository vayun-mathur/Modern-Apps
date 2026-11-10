//! Deterministic parallel map over contiguous chunks of a slice.
//!
//! Chunks are claimed dynamically (so a heavy chunk doesn't stall a core) but
//! each chunk's result is stored at its own index, so merging the results in
//! index order gives output that does not depend on thread scheduling. Every
//! parallel stage in this crate is built on that property — it is what makes the
//! tool reproducible, which the C++ generator it replaces was not.
//!
//! The pool size is capped by [`set_threads`] (`--threads`) or `MAPS_THREADS`,
//! because the build scripts run several of these tools concurrently and an
//! uncapped pool oversubscribes the box once they do.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

/// 0 means "not set"; any other value is an explicit cap from `--threads`.
static OVERRIDE: AtomicUsize = AtomicUsize::new(0);

/// Cap the pool for the rest of the process. Called once from `main` after
/// argument parsing.
///
/// This exists because the build scripts fan out several of these tools at once.
/// `available_parallelism()` reports the whole box to every one of them, so six
/// concurrent layer builds would each claim 32 threads on a 32-core machine and
/// finish slower than running in sequence. The cap has to be settable from
/// outside the process, hence this and `MAPS_THREADS`.
pub fn set_threads(n: usize) {
    OVERRIDE.store(n.max(1), Ordering::Relaxed);
}

/// Drop the override again, so a test can leave the process as it found it.
#[cfg(test)]
pub fn clear_threads() {
    OVERRIDE.store(0, Ordering::Relaxed);
}

/// Threads to use for one parallel stage.
///
/// `--threads` beats `MAPS_THREADS` beats the box. A single-process run sets
/// neither and is therefore unchanged.
pub fn threads() -> usize {
    resolve(
        OVERRIDE.load(Ordering::Relaxed),
        std::env::var("MAPS_THREADS").ok().as_deref(),
    )
}

/// The precedence rule, split out so it can be tested without mutating the
/// process-wide override or the environment.
fn resolve(override_n: usize, env: Option<&str>) -> usize {
    if override_n > 0 {
        return override_n;
    }
    if let Some(n) = env_threads(env) {
        return n;
    }
    std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4)
}

/// A malformed or non-positive `MAPS_THREADS` is ignored rather than fatal: it
/// is usually exported once for a whole script run, and killing an eight-hour
/// planet build over a typo in an environment variable serves nobody. Warned
/// about once, because `threads()` is called once per parallel stage.
fn env_threads(raw: Option<&str>) -> Option<usize> {
    static WARNED: std::sync::Once = std::sync::Once::new();
    let trimmed = raw?.trim();
    match trimmed.parse::<usize>() {
        Ok(n) if n > 0 => Some(n),
        _ => {
            WARNED.call_once(|| {
                eprintln!("WARN: ignoring MAPS_THREADS='{trimmed}' (wants a positive count)");
            });
            None
        }
    }
}

/// Parse a `--threads N` value. Shared by all three binaries so they reject the
/// same things with the same message.
pub fn parse_threads(value: &str) -> std::result::Result<usize, String> {
    value
        .parse::<usize>()
        .ok()
        .filter(|n| *n > 0)
        .ok_or_else(|| format!("--threads wants a positive count, not {value}"))
}

/// Our own rayon pool rather than the global one, so the cap above governs it.
///
/// Cached, and rebuilt only when the requested count changes. In production that
/// happens once, because `--threads` is read before any work starts; the reason it
/// is not a `OnceLock` is that the byte-identity tests build the same graph at
/// several thread counts in one process, and a pool fixed on first use would make
/// those tests assert nothing.
///
/// `map_chunks` above does not use this — it owns its own scoped threads and its
/// own ordered merge. This is for the phases that are a plain sort or map, where
/// rayon's work stealing is worth more than a hand-rolled split.
fn pool() -> Arc<rayon::ThreadPool> {
    static POOL: Mutex<Option<(usize, Arc<rayon::ThreadPool>)>> = Mutex::new(None);
    let want = threads();
    let mut cached = POOL.lock().expect("thread pool mutex");
    if let Some((have, p)) = cached.as_ref() {
        if *have == want {
            return Arc::clone(p);
        }
    }
    let built = Arc::new(build_pool(want).unwrap_or_else(|e| {
        eprintln!("WARN: cannot start a {want}-thread pool ({e}); continuing on one thread");
        build_pool(1).expect("a one-thread pool")
    }));
    *cached = Some((want, Arc::clone(&built)));
    built
}

fn build_pool(n: usize) -> std::result::Result<rayon::ThreadPool, rayon::ThreadPoolBuildError> {
    rayon::ThreadPoolBuilder::new()
        .num_threads(n)
        // As big as the main thread's, because that is where this work used to run.
        // rayon workers otherwise get std's 2 MiB default, so parallelising a deep
        // call path silently shrinks its stack by 4x.
        .stack_size(8 * 1024 * 1024)
        .thread_name(|i| format!("osm_ingest-{i}"))
        .build()
}

/// Run `f` on the pool, so it sees our thread cap rather than rayon's global one.
pub fn install<R: Send>(f: impl FnOnce() -> R + Send) -> R {
    pool().install(f)
}

/// Apply `f(chunk_start, chunk)` to every chunk of `chunk_len` items, returning
/// the results in chunk order.
pub fn map_chunks<T, R, F>(items: &[T], chunk_len: usize, f: F) -> Vec<R>
where
    T: Sync,
    R: Send,
    F: Fn(usize, &[T]) -> R + Sync,
{
    let chunk_len = chunk_len.max(1);
    let n_chunks = items.len().div_ceil(chunk_len);
    if n_chunks == 0 {
        return Vec::new();
    }
    let slots: Mutex<Vec<Option<R>>> = Mutex::new((0..n_chunks).map(|_| None).collect());
    let next = AtomicUsize::new(0);
    let n_threads = threads().min(n_chunks);

    std::thread::scope(|scope| {
        for _ in 0..n_threads {
            scope.spawn(|| loop {
                let chunk = next.fetch_add(1, Ordering::Relaxed);
                if chunk >= n_chunks {
                    break;
                }
                let start = chunk * chunk_len;
                let end = ((chunk + 1) * chunk_len).min(items.len());
                let result = f(start, &items[start..end]);
                slots.lock().expect("chunk slot mutex")[chunk] = Some(result);
            });
        }
    });

    slots
        .into_inner()
        .expect("chunk slot mutex")
        .into_iter()
        .flatten()
        .collect()
}

/// Apply `f(lo..hi)` to every fixed `chunk_len`-sized range of `0..n`,
/// returning the results in range order. [`map_chunks`] over indices without
/// materialising them: the deterministic shape for loops over billions of ids.
pub fn map_ranges<R, F>(n: usize, chunk_len: usize, f: F) -> Vec<R>
where
    R: Send,
    F: Fn(std::ops::Range<usize>) -> R + Sync,
{
    let chunk_len = chunk_len.max(1);
    let starts: Vec<usize> = (0..n.div_ceil(chunk_len)).map(|c| c * chunk_len).collect();
    map_chunks(&starts, 1, |_, s| f(s[0]..(s[0] + chunk_len).min(n)))
}

/// A raw pointer that may be shared across the pool, for scatters whose
/// targets are provably distinct (a permutation, or slots claimed by an atomic
/// cursor). Every use states why its writes cannot collide.
#[derive(Clone, Copy)]
pub struct SyncPtr<T>(pub *mut T);
// SAFETY: only ever dereferenced at indices the caller has proven disjoint.
unsafe impl<T: Send> Send for SyncPtr<T> {}
unsafe impl<T: Send> Sync for SyncPtr<T> {}

impl<T> SyncPtr<T> {
    /// Write `v` at `i`.
    ///
    /// # Safety
    /// `i` is in bounds of the allocation and no other thread reads or writes
    /// index `i` concurrently.
    #[inline]
    pub unsafe fn write(self, i: usize, v: T) {
        unsafe { self.0.add(i).write(v) }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn results_come_back_in_chunk_order() {
        let items: Vec<usize> = (0..1000).collect();
        let out = map_chunks(&items, 7, |start, chunk| (start, chunk.iter().sum::<usize>()));
        assert_eq!(out.len(), 1000usize.div_ceil(7));
        assert!(out.windows(2).all(|w| w[0].0 < w[1].0));
        assert_eq!(out.iter().map(|c| c.1).sum::<usize>(), items.iter().sum());
    }

    #[test]
    fn parse_threads_rejects_zero_and_junk() {
        assert_eq!(parse_threads("8"), Ok(8));
        assert!(parse_threads("0").is_err());
        assert!(parse_threads("-1").is_err());
        assert!(parse_threads("many").is_err());
        assert!(parse_threads("").is_err());
    }

    #[test]
    fn the_flag_beats_the_env_beats_the_box() {
        let box_threads = resolve(0, None);
        assert_eq!(resolve(6, Some("2")), 6, "--threads wins");
        assert_eq!(resolve(0, Some("2")), 2, "MAPS_THREADS wins over the box");
        assert_eq!(resolve(6, None), 6);
        // Junk in the environment falls back to the box rather than failing, so a
        // typo cannot silently serialise a long build.
        for junk in ["0", "many", "", "-4"] {
            assert_eq!(resolve(0, Some(junk)), box_threads, "MAPS_THREADS={junk}");
        }
        assert_eq!(resolve(0, Some(" 3 ")), 3, "surrounding space is tolerated");
    }

    #[test]
    fn ranges_cover_everything_once_in_order() {
        let out = map_ranges(1001, 64, |r| (r.start, r.end));
        assert_eq!(out.first(), Some(&(0, 64)));
        assert_eq!(out.last(), Some(&(960, 1001)));
        assert!(out.windows(2).all(|w| w[0].1 == w[1].0));
        assert!(map_ranges(0, 8, |r| r).is_empty());
    }

    #[test]
    fn empty_input_produces_no_chunks() {
        let out = map_chunks::<usize, usize, _>(&[], 8, |_, _| unreachable!());
        assert!(out.is_empty());
    }
}
