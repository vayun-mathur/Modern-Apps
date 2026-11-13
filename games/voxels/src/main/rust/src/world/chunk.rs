use super::block::Id;

pub const CHUNK_SIZE: usize = 16;
pub const SECTION_SIZE: usize = 16;
pub const SECTIONS_PER_CHUNK: usize = 16;
pub const CHUNK_HEIGHT: usize = SECTIONS_PER_CHUNK * SECTION_SIZE;
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct ChunkPos(pub i32, pub i32);
impl ChunkPos {
    pub fn from_world(x: i32, z: i32) -> Self { Self(x.div_euclid(CHUNK_SIZE as i32), z.div_euclid(CHUNK_SIZE as i32)) }
    pub fn world_origin(self) -> (i32, i32) { (self.0 * CHUNK_SIZE as i32, self.1 * CHUNK_SIZE as i32) }
}
// `meta` carries the per-voxel state that doesn't fit in the block id (slab half, stair facing). It
// stays one byte wide while ids are two. It is allocated only once a section actually stores a
// non-zero value: generated terrain is all plain cubes, so the vast majority of resident sections
// stay at 8 bytes instead of 4 KB.
#[derive(Clone)]
pub struct BlockSection { pub blocks: [Id; 4096], pub meta: Option<Box<[u8; 4096]>>, pub non_air: usize, }
impl BlockSection {
    pub fn empty() -> Self { Self { blocks: [0;4096], meta: None, non_air:0 } }
    fn idx(x: usize, y: usize, z: usize) -> usize { y*256+z*16+x }
    pub fn get(&self, x: usize, y: usize, z: usize) -> Id { self.blocks[Self::idx(x,y,z)] }
    pub fn set(&mut self, x: usize, y: usize, z: usize, id: Id) {
        let i=Self::idx(x,y,z); let prev=self.blocks[i];
        if prev!=0 && id==0 { self.non_air=self.non_air.saturating_sub(1); } else if prev==0 && id!=0 { self.non_air+=1; }
        self.blocks[i]=id;
        if let Some(m) = self.meta.as_mut() { m[i] = 0; }
    }
    pub fn get_meta(&self, x: usize, y: usize, z: usize) -> u8 {
        match &self.meta { Some(m) => m[Self::idx(x,y,z)], None => 0 }
    }
    pub fn set_meta(&mut self, x: usize, y: usize, z: usize, meta: u8) {
        if meta == 0 && self.meta.is_none() { return; }
        let m = self.meta.get_or_insert_with(|| Box::new([0u8; 4096]));
        m[Self::idx(x,y,z)] = meta;
    }
    pub fn is_empty(&self) -> bool { self.non_air==0 }
}
pub struct Chunk {
    pub pos: ChunkPos,
    pub sections: [Option<BlockSection>; SECTIONS_PER_CHUNK],
    pub generated: bool, pub dirty: bool, pub mesh_dirty: bool,
    /// Changed by play (a block placed or broken) since it was last saved, as opposed to merely
    /// generated. Only edited chunks have to be saved together with the player; see `ChunkMap`.
    pub edited: bool,
}
impl Chunk {
    pub fn new(pos: ChunkPos) -> Self { Self { pos, sections: std::array::from_fn(|_| None), generated:false, dirty:false, mesh_dirty:true, edited:false } }
    pub fn get_block(&self, x: usize, y: usize, z: usize) -> Id {
        if y>=CHUNK_HEIGHT { return 0; } let sec=y/SECTION_SIZE; let ly=y%SECTION_SIZE;
        if let Some(s)=&self.sections[sec] { s.get(x,ly,z) } else { 0 }
    }
    pub fn get_meta(&self, x: usize, y: usize, z: usize) -> u8 {
        if y>=CHUNK_HEIGHT { return 0; } let sec=y/SECTION_SIZE; let ly=y%SECTION_SIZE;
        if let Some(s)=&self.sections[sec] { s.get_meta(x,ly,z) } else { 0 }
    }
    pub fn set_block(&mut self, x: usize, y: usize, z: usize, id: Id) { self.set_block_meta(x, y, z, id, 0); }
    pub fn set_block_meta(&mut self, x: usize, y: usize, z: usize, id: Id, meta: u8) {
        if y>=CHUNK_HEIGHT { return; } let sec=y/SECTION_SIZE; let ly=y%SECTION_SIZE;
        if self.sections[sec].is_none() { if id==0 { return; } self.sections[sec]=Some(BlockSection::empty()); }
        if let Some(s)=self.sections[sec].as_mut() {
            s.set(x,ly,z,id);
            s.set_meta(x,ly,z,meta);
            if s.is_empty() { self.sections[sec]=None; }
        }
        self.dirty=true; self.mesh_dirty=true;
    }
}
