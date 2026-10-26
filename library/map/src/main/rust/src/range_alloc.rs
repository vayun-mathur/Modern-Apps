//! First-fit free list over one fixed-size byte range, for the tile-upload arenas.
//!
//! Host-compiled (unlike `vulkan`) so the bookkeeping the arenas rely on is pinned by tests.
//! Every range handed out and every free span is a multiple of [`ALIGN`], so a released range
//! always merges cleanly with its neighbours.

pub const ALIGN: u64 = 64;

pub fn align_up(v: u64) -> u64 {
    (v + ALIGN - 1) & !(ALIGN - 1)
}

/// Free spans as `(offset, len)`, sorted by offset and never adjacent (adjacent spans merge).
#[derive(Debug, Clone)]
pub struct RangeAlloc {
    capacity: u64,
    free: Vec<(u64, u64)>,
}

impl RangeAlloc {
    pub fn new(capacity: u64) -> RangeAlloc {
        let capacity = capacity & !(ALIGN - 1);
        RangeAlloc {
            capacity,
            free: vec![(0, capacity)],
        }
    }

    /// Reserve `len` bytes (rounded up to [`ALIGN`]); the offset, or `None` when no span fits.
    pub fn alloc(&mut self, len: u64) -> Option<u64> {
        let len = align_up(len.max(1));
        let at = self.free.iter().position(|&(_, span)| span >= len)?;
        let (offset, span) = self.free[at];
        if span == len {
            self.free.remove(at);
        } else {
            self.free[at] = (offset + len, span - len);
        }
        Some(offset)
    }

    /// Whether a span of `len` bytes is free, without reserving it.
    pub fn fits(&self, len: u64) -> bool {
        let len = align_up(len.max(1));
        self.free.iter().any(|&(_, span)| span >= len)
    }

    /// Return a range previously handed out by [`alloc`](Self::alloc) with the same `len`.
    pub fn release(&mut self, offset: u64, len: u64) {
        let len = align_up(len.max(1));
        let at = self.free.partition_point(|&(o, _)| o < offset);
        self.free.insert(at, (offset, len));
        if at + 1 < self.free.len() && offset + len == self.free[at + 1].0 {
            self.free[at].1 += self.free[at + 1].1;
            self.free.remove(at + 1);
        }
        if at > 0 && self.free[at - 1].0 + self.free[at - 1].1 == offset {
            self.free[at - 1].1 += self.free[at].1;
            self.free.remove(at);
        }
    }

    pub fn is_empty(&self) -> bool {
        self.free.len() == 1 && self.free[0] == (0, self.capacity)
    }

    pub fn used(&self) -> u64 {
        self.capacity - self.free.iter().map(|&(_, span)| span).sum::<u64>()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_released_range_is_handed_out_again() {
        let mut a = RangeAlloc::new(1024);
        let first = a.alloc(100).unwrap();
        let second = a.alloc(100).unwrap();
        assert_eq!((first, second), (0, 128));
        a.release(first, 100);
        assert_eq!(a.alloc(64), Some(0));
    }

    #[test]
    fn neighbours_merge_back_into_one_span() {
        let mut a = RangeAlloc::new(1024);
        let r: Vec<u64> = (0..4).map(|_| a.alloc(256).unwrap()).collect();
        assert_eq!(a.alloc(1), None);
        a.release(r[1], 256);
        a.release(r[3], 256);
        a.release(r[2], 256);
        assert_eq!(a.alloc(768), Some(256));
        a.release(256, 768);
        a.release(r[0], 256);
        assert!(a.is_empty());
        assert_eq!(a.used(), 0);
    }

    #[test]
    fn fits_does_not_reserve() {
        let mut a = RangeAlloc::new(256);
        assert!(a.fits(256));
        assert!(a.fits(256));
        assert_eq!(a.alloc(256), Some(0));
        assert!(!a.fits(1));
    }

    #[test]
    fn fragmentation_falls_through_to_a_later_span() {
        let mut a = RangeAlloc::new(1024);
        let r: Vec<u64> = (0..4).map(|_| a.alloc(256).unwrap()).collect();
        a.release(r[0], 256);
        a.release(r[2], 256);
        assert_eq!(a.alloc(512), None);
        assert_eq!(a.alloc(200), Some(0));
        assert_eq!(a.alloc(256), Some(512));
        assert_eq!(a.used(), 1024);
    }
}
