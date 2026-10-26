//! A read-only memory mapping of one region of a file, shared by the store and geocoder
//! readers. Mapped pages are file-backed, so the kernel can drop them under memory pressure and
//! re-read them on demand; that is what lets both readers hold multi-gigabyte files without
//! putting them on the heap.

use std::fs::File;

#[cfg(unix)]
pub(crate) struct Map {
    /// Page-aligned mapping base, as returned by `mmap`.
    addr: *mut libc::c_void,
    /// Length of the mapping, including the alignment slack before `offset`.
    len: usize,
    /// Where the requested region starts within the mapping.
    slack: usize,
}

/// Host fallback: plain heap buffer. A multi-hundred-MB host test store is not
/// the deployment target (the service is Android-only), so resident memory is
/// acceptable here; on-device this code never runs.
#[cfg(not(unix))]
pub(crate) struct Map {
    buf: Vec<u8>,
}

// The mapping is read-only and never mutated after construction.
unsafe impl Send for Map {}
unsafe impl Sync for Map {}

#[cfg(unix)]
impl Map {
    pub(crate) fn new(file: &File, offset: u64, len: usize) -> Option<Map> {
        if len == 0 {
            return None;
        }
        let page = unsafe { libc::sysconf(libc::_SC_PAGESIZE) };
        if page <= 0 {
            return None;
        }
        let page = page as u64;
        let aligned = offset - (offset % page);
        let slack = (offset - aligned) as usize;
        let maplen = slack.checked_add(len)?;
        let addr = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                maplen,
                libc::PROT_READ,
                libc::MAP_PRIVATE,
                {
                    use std::os::unix::io::AsRawFd;
                    file.as_raw_fd()
                },
                aligned as libc::off_t,
            )
        };
        if addr == libc::MAP_FAILED {
            return None;
        }
        // Probes are scattered single-bit tests, so readahead is wasted IO.
        unsafe { libc::madvise(addr, maplen, libc::MADV_RANDOM) };
        Some(Map { addr, len: maplen, slack })
    }

    #[inline]
    pub(crate) fn as_slice(&self) -> &[u8] {
        unsafe {
            std::slice::from_raw_parts(
                (self.addr as *const u8).add(self.slack),
                self.len - self.slack,
            )
        }
    }
}

#[cfg(not(unix))]
impl Map {
    pub(crate) fn new(file: &File, offset: u64, len: usize) -> Option<Map> {
        if len == 0 {
            return None;
        }
        let mut f = file.try_clone().ok()?;
        use std::io::{Read, Seek, SeekFrom};
        f.seek(SeekFrom::Start(offset)).ok()?;
        let mut buf = vec![0u8; len];
        f.read_exact(&mut buf).ok()?;
        Some(Map { buf })
    }

    #[inline]
    pub(crate) fn as_slice(&self) -> &[u8] {
        &self.buf
    }
}

#[cfg(unix)]
impl Drop for Map {
    fn drop(&mut self) {
        unsafe { libc::munmap(self.addr, self.len) };
    }
}
