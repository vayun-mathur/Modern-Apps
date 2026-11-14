// Planet5 graph v9: `intermediate.bin`'s blob and `road_names.bin` are tilecodec ZSEC sections
// (64 KiB zstd blocks). Bytes are read by raw position through a per-thread block cache, so a
// lookup inflates at most the one or two blocks it spans, and a route's or a snap's
// neighbouring edges -- stored next to each other, the nodes being curve-sorted -- hit it.

static NEXT_GRAPH_ZSEC_UID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);

/// Decoded bytes a thread keeps across every graph region (16 MiB).
const GRAPH_ZSEC_CACHE_BYTES: usize = 16 << 20;

/// One ZSEC region inside a mapping the graph owns (raw pointer, like every other table).
pub(crate) struct ZsecRegion {
    base: *const u8,
    len: usize,
    block_size: usize,
    raw_len: usize,
    uid: u64,
}

unsafe impl Send for ZsecRegion {}
unsafe impl Sync for ZsecRegion {}

struct GraphZsecCache {
    map: std::collections::HashMap<(u64, u32), std::rc::Rc<Vec<u8>>>,
    order: std::collections::VecDeque<(u64, u32)>,
    bytes: usize,
    last: Option<((u64, u32), std::rc::Rc<Vec<u8>>)>,
}

thread_local! {
    static GRAPH_ZSEC_BLOCKS: std::cell::RefCell<GraphZsecCache> = std::cell::RefCell::new(GraphZsecCache {
        map: std::collections::HashMap::new(),
        order: std::collections::VecDeque::new(),
        bytes: 0,
        last: None,
    });
}

impl ZsecRegion {
    /// Validate `bytes` as a whole ZSEC section (header, codec, full offset table).
    pub(crate) fn parse(bytes: &[u8]) -> Option<ZsecRegion> {
        let z = tilecodec::zsec::ZSec::parse(bytes).ok()?;
        Some(ZsecRegion {
            base: bytes.as_ptr(),
            len: bytes.len(),
            block_size: z.block_size(),
            raw_len: usize::try_from(z.raw_len()).ok()?,
            uid: NEXT_GRAPH_ZSEC_UID.fetch_add(1, std::sync::atomic::Ordering::Relaxed),
        })
    }

    pub(crate) fn raw_len(&self) -> usize {
        self.raw_len
    }

    fn block(&self, b: u32) -> std::rc::Rc<Vec<u8>> {
        let key = (self.uid, b);
        GRAPH_ZSEC_BLOCKS.with(|cell| {
            let mut c = cell.borrow_mut();
            if let Some((k, v)) = &c.last {
                if *k == key {
                    return v.clone();
                }
            }
            if let Some(v) = c.map.get(&key).cloned() {
                c.last = Some((key, v.clone()));
                return v;
            }
            let bytes = unsafe { std::slice::from_raw_parts(self.base, self.len) };
            // Validated at open; a failure here is corruption the open could not see.
            let v = std::rc::Rc::new(tilecodec::zsec::block_of(bytes, b as usize).unwrap_or_default());
            c.bytes += v.len();
            while c.bytes > GRAPH_ZSEC_CACHE_BYTES {
                let Some(old) = c.order.pop_front() else { break };
                if let Some(o) = c.map.remove(&old) {
                    c.bytes -= o.len();
                }
            }
            c.order.push_back(key);
            c.map.insert(key, v.clone());
            c.last = Some((key, v.clone()));
            v
        })
    }

    /// `len` raw bytes at `pos` appended to `out` (clamped to the raw length).
    pub(crate) fn read_into(&self, pos: usize, len: usize, out: &mut Vec<u8>) {
        let end = (pos + len).min(self.raw_len);
        let mut p = pos;
        while p < end {
            let blk = self.block((p / self.block_size) as u32);
            let off = p % self.block_size;
            let n = (end - p).min(blk.len().saturating_sub(off));
            if n == 0 {
                break;
            }
            out.extend_from_slice(&blk[off..off + n]);
            p += n;
        }
    }

    /// The NUL-terminated string at raw position `pos`.
    pub(crate) fn cstr_at(&self, pos: usize) -> Vec<u8> {
        let mut out = Vec::new();
        let mut p = pos;
        while p < self.raw_len {
            let blk = self.block((p / self.block_size) as u32);
            let off = p % self.block_size;
            if off >= blk.len() {
                break;
            }
            match blk[off..].iter().position(|&b| b == 0) {
                Some(i) => {
                    out.extend_from_slice(&blk[off..off + i]);
                    return out;
                }
                None => {
                    out.extend_from_slice(&blk[off..]);
                    p += blk.len() - off;
                }
            }
        }
        out
    }
}
