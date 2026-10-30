//! External sort over record shards.
//!
//! The record sets are far larger than memory — a planet WiFi store is order a billion
//! observations — so records are buffered into fixed-size runs, each sorted in memory and
//! spilled, then merged with a heap. `wps_build` consumes the merged stream directly, so the
//! sorted whole is never materialized.
//!
//! Sorting is by key first and then by a total order over the rest of the record, so equal
//! keys arrive adjacent (which is what lets the merge see every observation of a beacon at
//! once) and the output is deterministic regardless of input order or run boundaries.

use std::cmp::Ordering;
use std::collections::BinaryHeap;
use std::fs::File;
use std::io::{self, BufReader, BufWriter};
use std::path::{Path, PathBuf};

use crate::record::{Record, RecordReader, RecordWriter};

/// Total order: key, then accuracy (best first), then the rest, for determinism.
pub fn record_order(a: &Record, b: &Record) -> Ordering {
    a.key
        .cmp(&b.key)
        .then(a.accuracy_m.cmp(&b.accuracy_m))
        .then(a.lat_e8.cmp(&b.lat_e8))
        .then(a.lon_e8.cmp(&b.lon_e8))
        .then(a.source.cmp(&b.source))
}

/// Buffers records into sorted runs on disk, then merges them.
pub struct ExternalSort {
    dir: PathBuf,
    run_capacity: usize,
    buf: Vec<Record>,
    runs: Vec<PathBuf>,
    total: u64,
}

impl ExternalSort {
    /// `run_capacity` records are held in memory at a time; each full buffer becomes one run
    /// file in `dir`. At the default 4 M that is about 160 MB of buffer.
    pub fn new(dir: &Path, run_capacity: usize) -> io::Result<ExternalSort> {
        std::fs::create_dir_all(dir)?;
        Ok(ExternalSort {
            dir: dir.to_path_buf(),
            run_capacity: run_capacity.max(1),
            buf: Vec::with_capacity(run_capacity.min(1 << 20)),
            runs: Vec::new(),
            total: 0,
        })
    }

    /// Add one record.
    pub fn push(&mut self, r: Record) -> io::Result<()> {
        self.buf.push(r);
        self.total += 1;
        if self.buf.len() >= self.run_capacity {
            self.spill()?;
        }
        Ok(())
    }

    /// Records accepted so far.
    pub fn len(&self) -> u64 {
        self.total
    }

    /// Whether nothing has been pushed.
    pub fn is_empty(&self) -> bool {
        self.total == 0
    }

    fn spill(&mut self) -> io::Result<()> {
        if self.buf.is_empty() {
            return Ok(());
        }
        self.buf.sort_unstable_by(record_order);
        let path = self.dir.join(format!("run-{:05}.shard", self.runs.len()));
        let mut w = RecordWriter::new(BufWriter::new(File::create(&path)?));
        for r in &self.buf {
            w.push(r)?;
        }
        let _ = w.finish()?;
        self.buf.clear();
        self.runs.push(path);
        Ok(())
    }

    /// Spill the tail and return a merged, globally sorted stream.
    pub fn finish(mut self) -> io::Result<Merge> {
        self.spill()?;
        Merge::open(self.runs, self.total)
    }
}

struct Head {
    record: Record,
    which: usize,
}

impl PartialEq for Head {
    fn eq(&self, other: &Self) -> bool {
        record_order(&self.record, &other.record) == Ordering::Equal && self.which == other.which
    }
}
impl Eq for Head {}
impl Ord for Head {
    fn cmp(&self, other: &Self) -> Ordering {
        // BinaryHeap is a max-heap; invert so the smallest record comes out first. `which`
        // breaks ties so the merge is stable across runs.
        record_order(&other.record, &self.record).then(other.which.cmp(&self.which))
    }
}
impl PartialOrd for Head {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

/// A k-way merge over sorted run files. Deletes the runs when dropped.
pub struct Merge {
    readers: Vec<RecordReader<BufReader<File>>>,
    heap: BinaryHeap<Head>,
    paths: Vec<PathBuf>,
    remaining: u64,
}

impl Merge {
    fn open(paths: Vec<PathBuf>, total: u64) -> io::Result<Merge> {
        let mut readers = Vec::with_capacity(paths.len());
        let mut heap = BinaryHeap::with_capacity(paths.len());
        for (which, p) in paths.iter().enumerate() {
            let mut rd = RecordReader::new(BufReader::new(File::open(p)?));
            if let Some(record) = rd.next()? {
                heap.push(Head { record, which });
            }
            readers.push(rd);
        }
        Ok(Merge { readers, heap, paths, remaining: total })
    }

    /// Total records the merge will yield.
    pub fn len(&self) -> u64 {
        self.remaining
    }

    /// Whether the merge is empty.
    pub fn is_empty(&self) -> bool {
        self.remaining == 0
    }

    /// Next record in sorted order.
    pub fn next(&mut self) -> io::Result<Option<Record>> {
        let Some(Head { record, which }) = self.heap.pop() else {
            return Ok(None);
        };
        let reader = self
            .readers
            .get_mut(which)
            .ok_or_else(|| io::Error::new(io::ErrorKind::Other, "merge run index out of range"))?;
        if let Some(next) = reader.next()? {
            self.heap.push(Head { record: next, which });
        }
        self.remaining = self.remaining.saturating_sub(1);
        Ok(Some(record))
    }
}

impl Drop for Merge {
    fn drop(&mut self) {
        for p in &self.paths {
            let _ = std::fs::remove_file(p);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::record::{ALT_UNKNOWN, Source};

    struct TempDir(PathBuf);
    impl TempDir {
        fn new(tag: &str) -> TempDir {
            let p = std::env::temp_dir().join(format!("wpssort-{tag}-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&p);
            std::fs::create_dir_all(&p).unwrap();
            TempDir(p)
        }
    }
    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn rec(key: u128, acc: u16) -> Record {
        Record {
            key,
            lat_e8: 37_00000000,
            lon_e8: -122_00000000,
            accuracy_m: acc,
            altitude_m: ALT_UNKNOWN,
            source: Source::Gsloc,
        }
    }

    fn drain(mut m: Merge) -> Vec<Record> {
        let mut out = Vec::new();
        while let Some(r) = m.next().unwrap() {
            out.push(r);
        }
        out
    }

    #[test]
    fn sorts_across_many_runs() {
        let d = TempDir::new("runs");
        // A tiny run capacity forces plenty of spills, which is the case worth testing.
        let mut s = ExternalSort::new(&d.0, 7).unwrap();
        let mut state = 99u64;
        let mut expect = Vec::new();
        for _ in 0..500 {
            state = state.wrapping_mul(6364136223846793005).wrapping_add(1);
            let r = rec((state >> 20) as u128, (state & 0xFF) as u16);
            expect.push(r);
            s.push(r).unwrap();
        }
        assert_eq!(s.len(), 500);
        let got = drain(s.finish().unwrap());
        expect.sort_unstable_by(record_order);
        assert_eq!(got, expect);
    }

    #[test]
    fn equal_keys_come_out_adjacent_and_best_accuracy_first() {
        let d = TempDir::new("dupes");
        let mut s = ExternalSort::new(&d.0, 3).unwrap();
        for acc in [400u16, 12, 90, 12] {
            s.push(rec(42, acc)).unwrap();
        }
        s.push(rec(7, 5)).unwrap();
        s.push(rec(99, 5)).unwrap();
        let got = drain(s.finish().unwrap());
        assert_eq!(got.iter().map(|r| r.key).collect::<Vec<_>>(), vec![7, 42, 42, 42, 42, 99]);
        assert_eq!(
            got[1..5].iter().map(|r| r.accuracy_m).collect::<Vec<_>>(),
            vec![12, 12, 90, 400]
        );
    }

    #[test]
    fn run_files_are_cleaned_up() {
        let d = TempDir::new("cleanup");
        let mut s = ExternalSort::new(&d.0, 2).unwrap();
        for i in 0..10 {
            s.push(rec(i, 1)).unwrap();
        }
        let m = s.finish().unwrap();
        drop(drain(m));
        let leftover: Vec<_> = std::fs::read_dir(&d.0)
            .unwrap()
            .filter_map(|e| e.ok())
            .filter(|e| e.file_name().to_string_lossy().starts_with("run-"))
            .collect();
        assert!(leftover.is_empty(), "run files were left behind: {leftover:?}");
    }

    #[test]
    fn an_empty_sort_merges_to_nothing() {
        let d = TempDir::new("empty");
        let s = ExternalSort::new(&d.0, 16).unwrap();
        assert!(s.is_empty());
        assert!(drain(s.finish().unwrap()).is_empty());
    }
}
