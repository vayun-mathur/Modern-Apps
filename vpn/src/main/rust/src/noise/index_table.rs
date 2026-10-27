use std::collections::HashSet;
use std::sync::{Arc, Mutex};
use rand::rngs::StdRng;
use rand::SeedableRng;

pub struct IndexTable<Rng = StdRng>(Arc<Mutex<(Rng, HashSet<u32>)>>);

impl<Rng> Clone for IndexTable<Rng> {
    fn clone(&self) -> Self {
        Self(Arc::clone(&self.0))
    }
}

pub struct Index<Rng = StdRng> {
    value: u32,
    table: IndexTable<Rng>,
}

impl<Rng: rand::Rng> IndexTable<Rng> {
    pub fn new_index(&self) -> Index<Rng> {
        let mut g = self.0.lock().unwrap();
        loop {
            let idx = Self::next_id(&mut g.0);
            if g.1.insert(idx) {
                return Index { value: idx, table: Self(Arc::clone(&self.0)) };
            }
        }
    }
    pub(crate) fn next_id(rng: &mut Rng) -> u32 { rng.next_u32() }
    pub fn from_rng(rng: Rng) -> Self { IndexTable(Arc::new(Mutex::new((rng, HashSet::new())))) }
    pub fn in_use(&self, value: u32) -> bool { self.0.lock().unwrap().1.contains(&value) }
}

impl<Rng: SeedableRng + rand::Rng> IndexTable<Rng> {
    pub fn from_os_rng() -> Self {
        Self::from_rng(Rng::try_from_rng(&mut rand::rngs::SysRng).expect("OS RNG unavailable"))
    }
}

impl<Rng> IndexTable<Rng> {
    fn free_index(&self, index: u32) { self.0.lock().unwrap().1.remove(&index); }
}

impl Index {
    pub fn value(&self) -> u32 { self.value }
}
impl<Rng> Drop for Index<Rng> {
    fn drop(&mut self) { self.table.free_index(self.value); }
}
impl std::fmt::Debug for Index {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { self.value.fmt(f) }
}
impl std::fmt::Display for Index {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { self.value.fmt(f) }
}
