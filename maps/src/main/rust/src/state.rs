//! Search-time working sets: the monotonic radix heap (A* open set) and the
//! two-level page-table scratchpad of per-node A* state.
//!
//! Port of `radix_heap.h` and the `RoutingScratchpad` class in `scratchpad.h`.
//! Both are large, pre-allocated structures reused across routes; `lib.rs`
//! keeps a single instance of each behind a mutex so routes are serialized.

// --- Radix heap ---

#[derive(Clone, Copy)]
struct HeapNode {
    score: u32,
    id: u32,
}

const NUM_BUCKETS: usize = 33;

/// Monotonic radix heap with growable per-bucket vectors. Buckets grow on demand
/// so a large search (e.g. a cross-state route over the 36M-node graph) can never
/// overflow a fixed bucket and silently corrupt the open set (which produced
/// garbled / wrong-direction paths). Still O(1) amortized push and near-O(1) pop.
pub struct RadixHeap {
    buckets: Vec<Vec<HeapNode>>,
    last_pop_value: u32,
    count: u32,
}

impl Default for RadixHeap {
    fn default() -> Self {
        Self::new()
    }
}

impl RadixHeap {
    pub fn new() -> RadixHeap {
        RadixHeap {
            buckets: (0..NUM_BUCKETS).map(|_| Vec::new()).collect(),
            last_pop_value: 0,
            count: 0,
        }
    }

    #[inline]
    fn get_bucket_idx(&self, score: u32) -> u32 {
        let x = score ^ self.last_pop_value;
        if x == 0 {
            0
        } else {
            32 - x.leading_zeros()
        }
    }

    #[inline]
    pub fn push(&mut self, score: u32, node_id: u32) {
        let i = self.get_bucket_idx(score) as usize;
        self.buckets[i].push(HeapNode { score, id: node_id });
        self.count += 1;
    }

    /// Pops the minimum entry as `(score, node_id)`.
    #[inline]
    pub fn pop(&mut self) -> (u32, u32) {
        if self.buckets[0].is_empty() {
            // Lowest non-empty bucket above 0 holds the current minimum; drain it
            // down against the new last_pop_value (standard radix-heap advance).
            let i = (1..NUM_BUCKETS)
                .find(|&b| !self.buckets[b].is_empty())
                .expect("pop from non-empty heap");
            let min_score = self.buckets[i].iter().map(|n| n.score).min().unwrap();
            self.last_pop_value = min_score;

            let moved = std::mem::take(&mut self.buckets[i]);
            for node in moved {
                let idx = self.get_bucket_idx(node.score) as usize;
                self.buckets[idx].push(node);
            }
        }

        let node = self.buckets[0].pop().expect("bucket 0 non-empty after refill");
        self.count -= 1;
        (node.score, node.id)
    }

    pub fn clear(&mut self) {
        for b in self.buckets.iter_mut() {
            b.clear();
        }
        self.last_pop_value = 0;
        self.count = 0;
    }

    #[inline]
    pub fn empty(&self) -> bool {
        self.count == 0
    }
}

// --- Routing scratchpad ---

/// Kept to 8 bytes so a long search touches as few cache lines as possible.
#[derive(Clone, Copy)]
pub struct Entry {
    pub g_fwd: u32,
    pub p_fwd: u32,
}

impl Entry {
    const FRESH: Entry = Entry {
        g_fwd: 0xFFFF_FFFF,
        p_fwd: 0xFFFF_FFFF,
    };
}

const PAGE_BITS: u32 = 14;
const ROUTING_PAGE_SIZE: usize = 1 << PAGE_BITS;
const ROUTING_PAGE_MASK: u32 = (ROUTING_PAGE_SIZE as u32) - 1;
const DIR_SIZE: usize = (1usize << 32) >> PAGE_BITS;

/// Two-level page table indexed by node id, sparsely allocated.
pub struct RoutingScratchpad {
    directory: Vec<Option<Box<[Entry]>>>,
    active_pages: Vec<u32>,
}

impl Default for RoutingScratchpad {
    fn default() -> Self {
        Self::new()
    }
}

impl RoutingScratchpad {
    pub fn new() -> RoutingScratchpad {
        let mut directory = Vec::with_capacity(DIR_SIZE);
        directory.resize_with(DIR_SIZE, || None);
        RoutingScratchpad {
            directory,
            active_pages: Vec::with_capacity(1024),
        }
    }

    pub fn reset(&mut self) {
        for &page_idx in &self.active_pages {
            self.directory[page_idx as usize] = None;
        }
        self.active_pages.clear();
    }

    #[inline]
    pub fn get_entry(&mut self, node_id: u32) -> &mut Entry {
        let dir_idx = (node_id >> PAGE_BITS) as usize;
        let page_offset = (node_id & ROUTING_PAGE_MASK) as usize;

        if self.directory[dir_idx].is_none() {
            let page = vec![Entry::FRESH; ROUTING_PAGE_SIZE].into_boxed_slice();
            self.directory[dir_idx] = Some(page);
            self.active_pages.push(dir_idx as u32);
        }

        let page = self.directory[dir_idx].as_mut().unwrap();
        &mut page[page_offset]
    }
}
