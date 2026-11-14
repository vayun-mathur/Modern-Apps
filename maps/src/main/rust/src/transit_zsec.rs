// Planet5 (TRIX v8) block-compressed sections: typed reads through a per-thread block cache.
//
// A v8 section flagged ZSEC in its directory length is a `tilecodec::zsec` section. A read of
// `size_of::<T>()` bytes at a byte position decodes the one (or, straddling a boundary, two)
// 64 KiB blocks it touches and keeps them in a small per-thread cache, with a per-section
// last-block fast path for sequential walks (varint streams, polylines). The writer flags only
// the sections a plan reads to describe its result; the RAPTOR tables stay raw, because their
// random access across a world pack ran ~35x slower through the cache. Raw sections, and every
// v3..v7 section, take the old mmap path untouched.

static NEXT_TRANSIT_UID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);

/// Decoded blocks a thread keeps across all indexes (64 KiB each: 16 MiB per thread).
const TRANSIT_BLOCK_CACHE: usize = 256;

struct TransitBlockCache {
    map: std::collections::HashMap<(u64, u8, u32), std::rc::Rc<Vec<u8>>>,
    order: std::collections::VecDeque<(u64, u8, u32)>,
    /// The last block each section touched: sequential walks (a varint stream, a polyline)
    /// stay in one block for thousands of reads, interleaved across sections.
    last: Vec<Option<((u64, u8, u32), std::rc::Rc<Vec<u8>>)>>,
}

thread_local! {
    static TRANSIT_BLOCKS: std::cell::RefCell<TransitBlockCache> = std::cell::RefCell::new(TransitBlockCache {
        map: std::collections::HashMap::new(),
        order: std::collections::VecDeque::new(),
        last: vec![None; 32],
    });
}

impl TransitIndex {
    /// Decoded block `b` of ZSEC section `section`, from the per-thread cache.
    fn zblock(&self, section: usize, b: u32) -> std::rc::Rc<Vec<u8>> {
        let key = (self.uid, section as u8, b);
        TRANSIT_BLOCKS.with(|cell| {
            let mut c = cell.borrow_mut();
            if let Some(Some((k, v))) = c.last.get(section) {
                if *k == key {
                    return v.clone();
                }
            }
            if let Some(v) = c.map.get(&key).cloned() {
                c.last[section % 32] = Some((key, v.clone()));
                return v;
            }
            let (off, len, _) = self.zsec[section].expect("a ZSEC section");
            let bytes = unsafe { std::slice::from_raw_parts(self.base.add(off), len) };
            // Validated at open; a decode failure here is corruption the open could not see.
            let block = tilecodec::zsec::ZSec::parse(bytes)
                .and_then(|z| z.block(b as usize))
                .unwrap_or_default();
            let v = std::rc::Rc::new(block);
            if c.order.len() >= TRANSIT_BLOCK_CACHE {
                if let Some(old) = c.order.pop_front() {
                    c.map.remove(&old);
                }
            }
            c.order.push_back(key);
            c.map.insert(key, v.clone());
            c.last[section % 32] = Some((key, v.clone()));
            v
        })
    }

    /// One byte at raw position `pos` of a ZSEC section (0 past the end, as a raw read past a
    /// section's length would never be asked for: callers bound-check against `sec[i].1`).
    fn byte(&self, section: usize, pos: usize) -> u8 {
        let bs = self.zsec[section].expect("a ZSEC section").2;
        let blk = self.zblock(section, (pos / bs) as u32);
        blk.get(pos % bs).copied().unwrap_or(0)
    }

    /// A `T` at raw byte position `pos` of `section`, either layout.
    fn rd_at<T: Copy>(&self, section: usize, pos: usize) -> T {
        let n = std::mem::size_of::<T>();
        if self.zsec[section].is_none() {
            return unsafe { (self.base.add(self.sec[section].0 + pos) as *const T).read_unaligned() };
        }
        let bs = self.zsec[section].expect("a ZSEC section").2;
        let mut buf = [0u8; 64];
        assert!(n <= buf.len(), "a transit record wider than 64 bytes");
        let mut got = 0usize;
        while got < n {
            let p = pos + got;
            let blk = self.zblock(section, (p / bs) as u32);
            let at = p % bs;
            let take = (n - got).min(blk.len().saturating_sub(at));
            if take == 0 {
                break;
            }
            buf[got..got + take].copy_from_slice(&blk[at..at + take]);
            got += take;
        }
        unsafe { (buf.as_ptr() as *const T).read_unaligned() }
    }

    /// The `idx`-th `T` of `section` (the typed record reads every accessor goes through).
    #[inline]
    fn rd<T: Copy>(&self, section: usize, idx: usize) -> T {
        if self.zsec[section].is_none() {
            return unsafe { read_at::<T>(self.sec_ptr(section), idx) };
        }
        self.rd_at::<T>(section, idx * std::mem::size_of::<T>())
    }
}
