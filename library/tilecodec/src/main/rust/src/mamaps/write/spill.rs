use crate::proto::Result;
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

const BUFFER_BYTES: usize = 1 << 20;

/// The data section of an archive being written: a scratch file with its tail held in memory.
///
/// Two callers want different things from it. Appending wants a buffer, so that a body is not a
/// syscall. Confirming a content-dedup candidate wants random access to any *earlier* body — a seek
/// and a read for anything already on disk, but a slice compare for anything still in the buffer,
/// and the bodies most recently written are the ones a repeat is likeliest to match.
pub(crate) struct Spill {
    /// The name was removed right after creation (Unix): the handle is the file, and `Drop`
    /// must not delete whatever may have reused the path since.
    unlinked: bool,
    pub(crate) path: PathBuf,
    /// Opened for reading as well as writing, because a content-dedup compare reads back a body
    /// this same handle wrote.
    pub(crate) file: File,
    /// The tail of the section, not yet in `file`.
    pub(crate) buffer: Vec<u8>,
    /// Bytes that *are* in `file`, which is also the offset `buffer` begins at.
    pub(crate) flushed: u64,
    /// Reused by [`Self::matches_at`], so a compare against the file allocates nothing.
    pub(crate) scratch: Vec<u8>,
    // TEMPORARY instrumentation, to measure how often a content-dedup confirmation has to read.
    pub(crate) confirms: u64,
    pub(crate) confirms_from_file: u64,
    pub(crate) bytes_from_file: u64,
    /// The background writer, once [`Self::append_shared`] has started one. See [`BodyWriter`].
    bg: Option<BodyWriter>,
}

/// Bytes of bodies batched per hand-off to the background writer.
const BG_BATCH_BYTES: usize = 8 << 20;

/// A thread that writes appended bodies into the scratch file, in append order, at the offsets
/// the appender already gave them; the appender keeps every body not yet known written in
/// memory (`inflight`) so a dedup compare against it never touches the file.
///
/// What it removes from the serial append path is the copy of every stored body into the
/// buffer and the write itself -- ~34 GB of bodies on a planet build, all on the one thread
/// that also does the order-dependent dedup bookkeeping. Offsets are a running sum of body
/// lengths, known at append time, so the data section's bytes and every index entry are
/// exactly what the buffered path produced.
struct BodyWriter {
    tx: Option<std::sync::mpsc::SyncSender<Vec<std::sync::Arc<Vec<u8>>>>>,
    handle: Option<std::thread::JoinHandle<()>>,
    progress: std::sync::Arc<(std::sync::Mutex<BgProgress>, std::sync::Condvar)>,
    /// Logical length: every body appended, written or not.
    logical: u64,
    outgoing: Vec<std::sync::Arc<Vec<u8>>>,
    outgoing_bytes: usize,
    /// `(offset, body)` for every body not yet known written, ascending.
    inflight: std::collections::VecDeque<(u64, std::sync::Arc<Vec<u8>>)>,
}

struct BgProgress {
    /// Bytes known written: a body ending at or before this is in the file.
    written: u64,
    failed: Option<String>,
}

impl Spill {
    pub(crate) fn create(dir: Option<&Path>, hook: Option<fn(&File)>) -> Result<Spill> {
        /// Two writers in one process — which the test suite has, and a planet build may — must not
        /// share a scratch file.
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let dir = dir.map(Path::to_path_buf).unwrap_or_else(std::env::temp_dir);
        let path = dir.join(format!(
            "mamaps_bodies_{}_{}.tmp",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed),
        ));
        // Truncating rather than `create_new`: a run that died left its scratch behind, and once a
        // pid is recycled, refusing to build over an abandoned file would be a worse failure than
        // overwriting it.
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(&path)
            .map_err(|e| scratch_error(&path, "create", e))?;
        if let Some(hook) = hook {
            hook(&file);
        }
        // Everything after this goes through the one handle (appends, dedup reads, the copy into
        // the archive), so on Unix the name can go now: a build killed mid-way then leaves no
        // body scratch behind (tens of GB on a planet). Elsewhere `Drop` deletes it as before.
        #[cfg(unix)]
        let unlinked = std::fs::remove_file(&path).is_ok();
        #[cfg(not(unix))]
        let unlinked = false;
        Ok(Spill {
            unlinked,
            path,
            file,
            buffer: Vec::with_capacity(BUFFER_BYTES),
            flushed: 0,
            scratch: Vec::new(),
            confirms: 0,
            confirms_from_file: 0,
            bytes_from_file: 0,
            bg: None,
        })
    }

    /// Bytes appended so far, buffered or not — which is the offset the next body will land at.
    pub(crate) fn len(&self) -> u64 {
        match &self.bg {
            Some(bg) => bg.logical,
            None => self.flushed + self.buffer.len() as u64,
        }
    }

    /// Append a body the caller hands over whole, through the background writer (started on
    /// first use). Same offset, same bytes as [`Self::append`]; the body is written later, in
    /// order, and kept in memory until it is.
    pub(crate) fn append_shared(&mut self, body: std::sync::Arc<Vec<u8>>) -> Result<()> {
        if self.bg.is_none() {
            self.flush()?;
            self.start_bg()?;
        }
        let bg = self.bg.as_mut().expect("started above");
        let at = bg.logical;
        bg.logical += body.len() as u64;
        bg.outgoing_bytes += body.len();
        bg.inflight.push_back((at, std::sync::Arc::clone(&body)));
        bg.outgoing.push(body);
        if bg.outgoing_bytes >= BG_BATCH_BYTES {
            self.send_outgoing()?;
            // Drop the in-memory copies of everything the writer has finished: without this the
            // bodies would stay resident until a dedup compare happened to prune them -- the whole
            // data section (~53 GB on a planet) in the worst case.
            self.bg_written()?;
        }
        Ok(())
    }

    fn start_bg(&mut self) -> Result<()> {
        let file = self.file.try_clone().map_err(|e| scratch_error(&self.path, "reopen", e))?;
        let path = self.path.clone();
        let progress = std::sync::Arc::new((
            std::sync::Mutex::new(BgProgress { written: self.flushed, failed: None }),
            std::sync::Condvar::new(),
        ));
        let (tx, rx) = std::sync::mpsc::sync_channel::<Vec<std::sync::Arc<Vec<u8>>>>(4);
        let shared = std::sync::Arc::clone(&progress);
        let mut cursor = self.flushed;
        let handle = std::thread::Builder::new()
            .name("mamaps-bodies".to_string())
            .spawn(move || {
                let mut joined: Vec<u8> = Vec::with_capacity(BG_BATCH_BYTES * 2);
                for batch in rx {
                    joined.clear();
                    for body in &batch {
                        joined.extend_from_slice(body);
                    }
                    let wrote = pwrite_all(&file, cursor, &joined);
                    let (lock, cv) = &*shared;
                    let mut p = lock.lock().expect("the body writer's progress");
                    match wrote {
                        Ok(()) => {
                            cursor += joined.len() as u64;
                            p.written = cursor;
                        }
                        Err(e) => {
                            p.failed = Some(scratch_error(&path, "write to", e).0);
                            cv.notify_all();
                            return;
                        }
                    }
                    cv.notify_all();
                }
            })
            .map_err(|e| crate::proto::Error(format!("cannot start the body writer: {e}")))?;
        self.bg = Some(BodyWriter {
            tx: Some(tx),
            handle: Some(handle),
            progress,
            logical: self.flushed,
            outgoing: Vec::new(),
            outgoing_bytes: 0,
            inflight: std::collections::VecDeque::new(),
        });
        Ok(())
    }

    fn send_outgoing(&mut self) -> Result<()> {
        let bg = self.bg.as_mut().expect("only with a writer");
        if bg.outgoing.is_empty() {
            return Ok(());
        }
        let batch = std::mem::take(&mut bg.outgoing);
        bg.outgoing_bytes = 0;
        if bg.tx.as_ref().expect("open until finished").send(batch).is_err() {
            return Err(self.bg_failure());
        }
        Ok(())
    }

    fn bg_failure(&self) -> crate::proto::Error {
        let bg = self.bg.as_ref().expect("only with a writer");
        let p = bg.progress.0.lock().expect("the body writer's progress");
        crate::proto::Error(
            p.failed.clone().unwrap_or_else(|| "the body writer stopped early".to_string()),
        )
    }

    /// Bytes known written, after dropping the in-memory copies of everything below it.
    fn bg_written(&mut self) -> Result<u64> {
        let bg = self.bg.as_mut().expect("only with a writer");
        let written = {
            let p = bg.progress.0.lock().expect("the body writer's progress");
            if let Some(e) = &p.failed {
                return Err(crate::proto::Error(e.clone()));
            }
            p.written
        };
        while let Some((at, body)) = bg.inflight.front() {
            if at + body.len() as u64 > written {
                break;
            }
            bg.inflight.pop_front();
        }
        Ok(written)
    }

    /// Send everything and wait until it is all in the file, then fold the writer back into the
    /// buffered state (`flushed` = the whole section) and stop it.
    fn finish_bg(&mut self) -> Result<()> {
        if self.bg.is_none() {
            return Ok(());
        }
        self.send_outgoing()?;
        let mut bg = self.bg.take().expect("checked above");
        drop(bg.tx.take());
        if let Some(h) = bg.handle.take() {
            h.join().map_err(|_| crate::proto::Error("the body writer panicked".to_string()))?;
        }
        let p = bg.progress.0.lock().expect("the body writer's progress");
        if let Some(e) = &p.failed {
            return Err(crate::proto::Error(e.clone()));
        }
        if p.written != bg.logical {
            return Err(crate::proto::Error(format!(
                "the body writer stopped at {} of {} bytes",
                p.written, bg.logical,
            )));
        }
        self.flushed = bg.logical;
        Ok(())
    }

    pub(crate) fn append(&mut self, bytes: &[u8]) -> Result<()> {
        if self.bg.is_some() {
            return self.append_shared(std::sync::Arc::new(bytes.to_vec()));
        }
        if self.buffer.len() + bytes.len() > BUFFER_BYTES {
            self.flush()?;
        }
        if bytes.len() >= BUFFER_BYTES {
            // A body bigger than the buffer goes straight through, because buffering it would only
            // copy it twice on the way to the same place.
            let Spill { file, path, flushed, .. } = self;
            write_all_at(file, path, *flushed, bytes)?;
            *flushed += bytes.len() as u64;
        } else {
            self.buffer.extend_from_slice(bytes);
        }
        Ok(())
    }

    /// Whether the `candidate.len()` bytes at `offset` are exactly `candidate`.
    ///
    /// The compare that makes a content-dedup hit a fact rather than a probability. Answered out of
    /// the buffer when the body is still in it, which costs nothing at all.
    pub(crate) fn matches_at(&mut self, offset: u64, candidate: &[u8]) -> Result<bool> {
        let start = offset as u64;
        let end = start + candidate.len() as u64;
        debug_assert!(end <= self.len(), "a dedup candidate past the end of the data section");
        self.confirms += 1;
        if self.bg.is_some() {
            let written = self.bg_written()?;
            if end > written {
                // Not known written: in memory. Dedup candidates are whole earlier bodies, so the
                // offset names one exactly; anything else waits for the writer and reads.
                let bg = self.bg.as_ref().expect("checked above");
                let i = bg.inflight.partition_point(|(at, _)| *at < start);
                if let Some((at, body)) = bg.inflight.get(i) {
                    if *at == start && body.len() == candidate.len() {
                        return Ok(body[..] == *candidate);
                    }
                }
                self.finish_bg()?;
            }
            self.confirms_from_file += 1;
            self.bytes_from_file += candidate.len() as u64;
            let Spill { file, path, scratch, .. } = self;
            scratch.resize(candidate.len(), 0);
            pread_exact(file, start, scratch).map_err(|e| scratch_error(path, "read back from", e))?;
            return Ok(scratch[..] == *candidate);
        }
        if start >= self.flushed {
            let at = (start - self.flushed) as usize;
            return Ok(self.buffer[at..at + candidate.len()] == *candidate);
        }
        self.confirms_from_file += 1;
        // A body straddling the flush boundary lives half in the file, half in the buffer.
        // Compare the two halves in place instead of flushing: a flush here would push a whole
        // megabyte to disk just to answer one compare, and can only happen once per body.
        // Byte-identical to the flush-then-compare it replaces: same bytes, same answer.
        if end > self.flushed {
            let file_len = (self.flushed - start) as usize;
            self.bytes_from_file += file_len as u64;
            let Spill { file, path, scratch, buffer, .. } = self;
            scratch.resize(file_len, 0);
            read_exact_at(file, path, start, scratch)?;
            if scratch[..] != candidate[..file_len] {
                return Ok(false);
            }
            return Ok(buffer[..candidate.len() - file_len] == candidate[file_len..]);
        }
        self.bytes_from_file += candidate.len() as u64;
        let Spill { file, path, scratch, .. } = self;
        scratch.resize(candidate.len(), 0);
        read_exact_at(file, path, start, scratch)?;
        Ok(scratch[..] == *candidate)
    }

    pub(crate) fn flush(&mut self) -> Result<()> {
        if self.bg.is_some() {
            return self.finish_bg();
        }
        if self.buffer.is_empty() {
            return Ok(());
        }
        let Spill { file, path, buffer, flushed, .. } = self;
        write_all_at(file, path, *flushed, buffer)?;
        *flushed += buffer.len() as u64;
        buffer.clear();
        Ok(())
    }

    /// Copy the whole section onto the end of the archive being written, and say how much.
    ///
    /// Through a buffer of its own rather than `std::io::copy`, whose eight kilobytes would make a
    /// California data section eighty thousand round trips in each direction.
    pub(crate) fn copy_to(&mut self, out: &mut File, out_path: &Path) -> Result<u64> {
        self.flush()?;
        let Spill { file, path, .. } = self;
        file.rewind().map_err(|e| scratch_error(path, "seek in", e))?;
        let mut chunk = vec![0u8; BUFFER_BYTES];
        let mut copied = 0u64;
        loop {
            let n = file.read(&mut chunk).map_err(|e| scratch_error(path, "read back from", e))?;
            if n == 0 {
                return Ok(copied);
            }
            out.write_all(&chunk[..n]).map_err(|e| {
                crate::proto::Error(format!(
                    "cannot copy the .mamaps data section into {}: {e}",
                    out_path.display(),
                ))
            })?;
            copied += n as u64;
        }
    }

    /// The same, onto the end of an archive being assembled in memory.
    ///
    /// Into a resized tail rather than through `read_to_end`, whose growth heuristics will happily
    /// double a 655 MB buffer and hold both halves while it copies — which is most of the peak this
    /// whole arrangement exists to remove. The length is known exactly, so nothing needs guessing.
    pub(crate) fn copy_to_vec(&mut self, out: &mut Vec<u8>) -> Result<u64> {
        self.flush()?;
        let at = out.len();
        let len = self.flushed;
        out.resize(at + len as usize, 0);
        let Spill { file, path, .. } = self;
        file.rewind().map_err(|e| scratch_error(path, "seek in", e))?;
        file.read_exact(&mut out[at..]).map_err(|e| scratch_error(path, "read back from", e))?;
        Ok(len)
    }
}

impl Drop for BodyWriter {
    fn drop(&mut self) {
        drop(self.tx.take());
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

impl Drop for Spill {
    /// So a build that dies part way through cannot strand a copy of its data section — 652 MB for
    /// California and tens of gigabytes for a planet, which is why `tile_build::spill` does the
    /// same.
    ///
    /// The error is dropped on purpose. Scratch that outlives the process is untidy; panicking in a
    /// `Drop` while a real failure is unwinding would replace its diagnostic with this one.
    fn drop(&mut self) {
        if !self.unlinked {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}

/// Seeking before every access rather than tracking the cursor.
///
/// A dedup compare moves the file cursor, so a write that assumed it was still at the end would put
/// a body somewhere else in the data section — and the index would still say it was at the end.
/// These take the offset they want and are therefore immune to whatever ran last.
pub(crate) fn write_all_at(file: &mut File, path: &Path, offset: u64, bytes: &[u8]) -> Result<()> {
    file.seek(SeekFrom::Start(offset)).map_err(|e| scratch_error(path, "seek in", e))?;
    file.write_all(bytes).map_err(|e| scratch_error(path, "write to", e))
}

pub(crate) fn read_exact_at(file: &mut File, path: &Path, offset: u64, into: &mut [u8]) -> Result<()> {
    file.seek(SeekFrom::Start(offset)).map_err(|e| scratch_error(path, "seek in", e))?;
    file.read_exact(into).map_err(|e| scratch_error(path, "read back from", e))
}

/// A positional write that never moves the shared file cursor (the appender's reads and the
/// writer's writes share one file description).
fn pwrite_all(file: &File, mut offset: u64, mut bytes: &[u8]) -> std::io::Result<()> {
    while !bytes.is_empty() {
        #[cfg(unix)]
        let n = std::os::unix::fs::FileExt::write_at(file, bytes, offset)?;
        #[cfg(windows)]
        let n = std::os::windows::fs::FileExt::seek_write(file, bytes, offset)?;
        if n == 0 {
            return Err(std::io::Error::new(std::io::ErrorKind::WriteZero, "the scratch took nothing"));
        }
        bytes = &bytes[n..];
        offset += n as u64;
    }
    Ok(())
}

/// The read twin of [`pwrite_all`].
fn pread_exact(file: &File, mut offset: u64, mut into: &mut [u8]) -> std::io::Result<()> {
    while !into.is_empty() {
        #[cfg(unix)]
        let n = std::os::unix::fs::FileExt::read_at(file, into, offset)?;
        #[cfg(windows)]
        let n = std::os::windows::fs::FileExt::seek_read(file, into, offset)?;
        if n == 0 {
            return Err(std::io::Error::new(std::io::ErrorKind::UnexpectedEof, "the scratch ended early"));
        }
        into = &mut into[n..];
        offset += n as u64;
    }
    Ok(())
}

pub(crate) fn scratch_error(path: &Path, doing: &str, e: std::io::Error) -> crate::proto::Error {
    crate::proto::Error(format!(
        "cannot {doing} the .mamaps body scratch file {}: {e}",
        path.display(),
    ))
}

#[cfg(test)]
mod bg_tests {
    use super::*;

    /// Bodies appended through the background writer land at the offsets `len()` promised, in
    /// order, byte for byte; dedup compares answer the same whether the body is still in memory
    /// or already in the file; and the buffered path can be mixed in.
    #[test]
    fn background_appends_write_the_same_section() {
        let mut spill = Spill::create(None, None).unwrap();
        let mut want: Vec<u8> = Vec::new();
        let mut offsets = Vec::new();
        spill.append(b"buffered-prefix").unwrap();
        want.extend_from_slice(b"buffered-prefix");
        let mut state = 0x9E37_79B9_7F4A_7C15u64;
        for i in 0..3000usize {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            let len = (state % 20_000) as usize + 1;
            let body: Vec<u8> = (0..len).map(|k| (k as u64 ^ state ^ i as u64) as u8).collect();
            assert_eq!(spill.len(), want.len() as u64);
            offsets.push((want.len() as u64, body.clone()));
            want.extend_from_slice(&body);
            spill.append_shared(std::sync::Arc::new(body)).unwrap();
            if i % 97 == 0 {
                // A compare against an early body (on disk by now, or still in flight) and the
                // latest one (certainly in flight); a wrong candidate must not match.
                let (at, b) = &offsets[i / 3];
                assert!(spill.matches_at(*at, b).unwrap());
                let (at, b) = offsets.last().unwrap();
                assert!(spill.matches_at(*at, b).unwrap());
                let mut wrong = b.clone();
                wrong[0] ^= 1;
                assert!(!spill.matches_at(*at, &wrong).unwrap());
            }
            if i == 1500 {
                spill.append(b"mixed").unwrap();
                want.extend_from_slice(b"mixed");
            }
        }
        // In-flight copies are released as the writer catches up, not held to the end.
        let held: usize = spill.bg.as_ref().map_or(0, |bg| bg.inflight.iter().map(|(_, b)| b.len()).sum());
        assert!(held < 64 << 20, "{held} bytes still held in memory");
        let mut out = Vec::new();
        spill.copy_to_vec(&mut out).unwrap();
        assert_eq!(out, want);
    }

    /// The body scratch is unlinked at creation on Unix: its directory is empty while the spill
    /// is live and written to, and reading it back still works.
    #[test]
    fn the_body_scratch_leaves_no_name_behind() {
        let dir = std::env::temp_dir().join(format!("mamaps_bodies_unlink_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let mut spill = Spill::create(Some(&dir), None).unwrap();
        spill.append(b"abc").unwrap();
        spill.append_shared(std::sync::Arc::new(vec![7u8; 100_000])).unwrap();
        #[cfg(unix)]
        assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 0, "a body scratch name was left");
        assert!(spill.matches_at(0, b"abc").unwrap());
        let mut out = Vec::new();
        spill.copy_to_vec(&mut out).unwrap();
        assert_eq!(out.len(), 100_003);
        drop(spill);
        assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 0);
        std::fs::remove_dir(&dir).unwrap();
    }
}
