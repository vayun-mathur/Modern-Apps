// A read-only memory mapping of a `.maml` data section, for files the host reads a row at a time.
//
// Gemma's 3.6 GB EMBED file is read two small ranges per table row, ~70 rows a token: as `pread`
// calls that is thousands of syscalls for a prompt. Mapped, a row is a `memcpy` out of the page
// cache. Pages stay file-backed, so the kernel drops them under pressure like any cached file.

/// The mapped data section of a [`Streamed`]. See [`Streamed::map`].
#[cfg(unix)]
struct Mapping {
    /// Page-aligned base `mmap` returned.
    addr: *mut libc::c_void,
    /// Bytes mapped from `addr`, including `slack`.
    len: usize,
    /// Bytes from `addr` to the data section's first byte.
    slack: usize,
}

// SAFETY: the mapping is PROT_READ and never written through, and it lives until `Drop`, so
// shared references from any thread read immutable memory.
#[cfg(unix)]
unsafe impl Send for Mapping {}
// SAFETY: as for `Send`: nothing mutates the mapped bytes.
#[cfg(unix)]
unsafe impl Sync for Mapping {}

#[cfg(unix)]
impl Mapping {
    /// Map `len` bytes of `file` from `offset`, or `None` when the kernel refuses (e.g. a 32-bit
    /// address space too small for the file), in which case the caller keeps reading with pread.
    fn new(file: &File, offset: u64, len: u64) -> Option<Mapping> {
        use std::os::unix::io::AsRawFd;
        // SAFETY: sysconf has no preconditions.
        let page = u64::try_from(unsafe { libc::sysconf(libc::_SC_PAGESIZE) }).ok()?;
        if page == 0 || len == 0 {
            return None;
        }
        let aligned = offset - offset % page;
        let slack = usize::try_from(offset - aligned).ok()?;
        let len = slack.checked_add(usize::try_from(len).ok()?)?;
        let at = libc::off_t::try_from(aligned).ok()?;
        // SAFETY: a fresh read-only private mapping of a descriptor this process owns; the kernel
        // picks the address, so nothing existing is replaced. Checked against MAP_FAILED below.
        let addr = unsafe {
            libc::mmap(std::ptr::null_mut(), len, libc::PROT_READ, libc::MAP_PRIVATE, file.as_raw_fd(), at)
        };
        if addr == libc::MAP_FAILED {
            return None;
        }
        // SAFETY: `addr..addr + len` is the mapping just created; advice cannot fault.
        unsafe { libc::madvise(addr, len, libc::MADV_RANDOM) };
        Some(Mapping { addr, len, slack })
    }

    /// Copy `into.len()` bytes from data-section offset `offset`. The caller has bounds-checked
    /// the range against the data section, which this mapping covers exactly.
    fn read_at(&self, offset: u64, into: &mut [u8]) -> Option<()> {
        let from = self.slack.checked_add(usize::try_from(offset).ok()?)?;
        if from.checked_add(into.len())? > self.len {
            return None;
        }
        // SAFETY: `from..from + into.len()` lies inside the live read-only mapping (checked
        // above), and `into` is a distinct heap buffer, so the ranges cannot overlap.
        unsafe {
            std::ptr::copy_nonoverlapping((self.addr as *const u8).add(from), into.as_mut_ptr(), into.len());
        }
        Some(())
    }
}

#[cfg(unix)]
impl Drop for Mapping {
    fn drop(&mut self) {
        // SAFETY: `addr`/`len` are exactly what `mmap` returned, and no reference into the
        // mapping outlives `self`: `read_at` copies out rather than lending slices.
        unsafe { libc::munmap(self.addr, self.len) };
    }
}

/// Non-unix hosts read with positional reads only.
#[cfg(not(unix))]
struct Mapping;

#[cfg(not(unix))]
impl Mapping {
    fn new(_file: &File, _offset: u64, _len: u64) -> Option<Mapping> {
        None
    }

    fn read_at(&self, _offset: u64, _into: &mut [u8]) -> Option<()> {
        None
    }
}

impl Streamed {
    /// Serve later host reads from a memory mapping of the data section. Returns whether it
    /// mapped; on failure (or off unix) reads stay positional, so this never fails the caller.
    ///
    /// For files read a row at a time (Gemma's EMBED). Not for files a net uploads whole, which
    /// gain nothing from it.
    pub fn map(&mut self) -> bool {
        self.mapping = Mapping::new(&self.file, self.data_at, self.data_len);
        self.mapping.is_some()
    }
}

#[cfg(test)]
mod mapping_tests {
    use super::*;

    /// A mapped read returns exactly the bytes a positional read does, for a file that starts at
    /// an unaligned offset inside a larger one (an APK asset), and `map` never breaks reads.
    #[test]
    fn mapped_rows_equal_positional_rows() {
        let blob = write_mixed(
            graph::GEMMA4_EMBED,
            &[
                Fixture::I4(vec![64, 32], (0..64 * 32).map(|i| (i % 15) as i8 - 7).collect()),
                Fixture::F16(vec![64, 1], (0..64).map(|i| 0.01 * i as f32).collect()),
            ],
        );
        let lead = 4099usize;
        let mut bytes = vec![0xA5u8; lead];
        bytes.extend_from_slice(&blob);
        let path = std::env::temp_dir().join(format!("mapping_test_{}.bin", std::process::id()));
        std::fs::write(&path, &bytes).expect("the fixture writes");
        let open = || {
            let file = File::open(&path).expect("the fixture opens");
            Streamed::open(file, lead as u64, blob.len() as u64, graph::GEMMA4_EMBED).expect("it parses")
        };
        let plain = open();
        let mut mapped = open();
        let did_map = mapped.map();
        assert_eq!(did_map, cfg!(unix), "map() maps exactly on unix");
        for row in [0u32, 1, 31, 63] {
            let a = plain.reader().int4_row(0, 1, &[64, 32], row).expect("pread row");
            let b = mapped.reader().int4_row(0, 1, &[64, 32], row).expect("mapped row");
            assert_eq!(a, b, "row {row}");
        }
        let mut past = [0u8; 4];
        assert!(mapped.read_at(mapped.data_len() - 2, &mut past).is_err(), "reads past the end still fail");
        drop(mapped);
        let _ = std::fs::remove_file(&path);
    }
}
