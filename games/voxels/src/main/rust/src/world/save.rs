use super::chunk::{Chunk, ChunkPos, SECTIONS_PER_CHUNK};
use std::fs;
use std::path::{Path, PathBuf};

// `base` is already the world's own directory (MainActivity passes it in), so everything below sits
// directly inside it.
fn region_dir(base: &str) -> PathBuf {
    Path::new(base).join("regions")
}
fn chunk_file(base: &str, pos: ChunkPos) -> PathBuf {
    region_dir(base).join(format!("c_{}_{}.dat", pos.0, pos.1))
}
pub fn save_chunk(base: &str, chunk: &Chunk) -> std::io::Result<()> {
    let dir = region_dir(base);
    fs::create_dir_all(&dir)?;
    let path = chunk_file(base, chunk.pos);
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, encode_chunk(chunk))?;
    fs::rename(&tmp, &path)?;
    Ok(())
}
// VOX3: "VOX3", section count, then per section: present u8; if 1 { 4096 block ids as u16
// little-endian; has_meta u8; if 1 { 4096 meta bytes } }. Block ids are two bytes wide so ids above
// 255 have somewhere to live; meta stays one byte. The meta flag keeps cube-only sections from
// paying for an array they never filled.
pub fn encode_chunk(chunk: &Chunk) -> Vec<u8> {
    let mut data = Vec::with_capacity(32*1024);
    data.extend_from_slice(b"VOX3");
    data.push(SECTIONS_PER_CHUNK as u8);
    for sec_opt in chunk.sections.iter() {
        let Some(sec) = sec_opt else { data.push(0); continue; };
        data.push(1);
        for &id in sec.blocks.iter() { data.extend_from_slice(&id.to_le_bytes()); }
        match sec.meta.as_ref().filter(|m| m.iter().any(|&v| v != 0)) {
            Some(meta) => { data.push(1); data.extend_from_slice(&meta[..]); }
            None => data.push(0),
        }
    }
    data
}
pub fn load_chunk(base: &str, _pos: ChunkPos, chunk: &mut Chunk) -> std::io::Result<()> {
    let path = chunk_file(base, chunk.pos);
    decode_chunk(&fs::read(&path)?, chunk)
}
// Reads the one format there is. A decode error makes `load_or_gen` treat the chunk as ungenerated
// and regenerate it from the seed, erasing whatever the player built there, so `Err` has to mean the
// bytes genuinely are not a chunk.
pub fn decode_chunk(bytes: &[u8], chunk: &mut Chunk) -> std::io::Result<()> {
    let bad = |m: &'static str| std::io::Error::new(std::io::ErrorKind::InvalidData, m);
    if bytes.len() < 5 { return Err(bad("too small")); }
    if &bytes[0..4] != b"VOX3" { return Err(bad("bad magic")); }
    let sec_count = bytes[4] as usize;
    let mut off = 5usize;
    for sec_idx in 0..sec_count.min(SECTIONS_PER_CHUNK) {
        if off >= bytes.len() { break; }
        let flag = bytes[off]; off+=1;
        if flag != 1 { continue; }
        if off + 8192 > bytes.len() { return Err(bad("section overflow")); }
        let mut blocks = [0 as crate::world::block::Id; 4096];
        for (i, cell) in blocks.iter_mut().enumerate() {
            *cell = u16::from_le_bytes([bytes[off + i*2], bytes[off + i*2 + 1]]);
        }
        off += 8192;
        let mut meta = None;
        if off >= bytes.len() { return Err(bad("missing meta flag")); }
        let has_meta = bytes[off]; off+=1;
        if has_meta == 1 {
            if off + 4096 > bytes.len() { return Err(bad("meta overflow")); }
            let mut m = Box::new([0u8; 4096]);
            m.copy_from_slice(&bytes[off..off+4096]);
            off+=4096;
            meta = Some(m);
        }
        let non_air = blocks.iter().filter(|&&b| b!=0).count();
        if non_air>0 { chunk.sections[sec_idx] = Some(super::chunk::BlockSection{ blocks, meta, non_air }); }
    }
    chunk.generated = true; chunk.dirty = false; chunk.mesh_dirty = true;
    Ok(())
}

#[derive(serde::Serialize, serde::Deserialize, Default, Debug, Clone)]
pub struct PlayerSave {
    pub x: f32, pub y: f32, pub z: f32, pub yaw: f32, pub pitch: f32,
    pub inventory: InventorySave, pub stats: StatsSave,
    #[serde(default)] pub progress: ProgressSave,
}
// Progression that survives a quit: equipped armor, earned max health, which dimension
// the player is standing in (and where they were in the others), and one-time boss kills.
#[derive(serde::Serialize, serde::Deserialize, Debug, Clone)]
pub struct ProgressSave {
    #[serde(default)] pub armor: Vec<InvSlotSave>,
    #[serde(default = "default_max_health")] pub max_health: f32,
    #[serde(default)] pub dim: u8,
    #[serde(default)] pub dim_pos: Vec<[f32; 3]>,
    #[serde(default)] pub dim_visited: Vec<bool>,
    #[serde(default)] pub respawn: Option<[f32; 3]>,
    #[serde(default)] pub end_dragon_dead: bool,
    #[serde(default)] pub nether_wither_dead: bool,
    #[serde(default)] pub world_secs: f32,
    #[serde(default)] pub best_beacon: i32,
    #[serde(default = "default_deepest")] pub deepest_y: i32,
    #[serde(default)] pub blessings: crate::blessing::Attunement,
    // Completed trades per profession, indexed by `villager::ALL` order. Villager mobs aren't saved,
    // so levels are tracked against the profession rather than the individual.
    #[serde(default)] pub trades_done: Vec<u32>,
    // Weather state and the seconds left on it, so a save doesn't reopen to a clear sky mid-storm.
    #[serde(default)] pub weather: u8,
    #[serde(default)] pub weather_cd: f32,
    /// Bitmask over RECIPES of recipes crafted at least once — the crafting tech tree's state.
    #[serde(default)] pub crafted_recipes: Vec<u8>,
}
fn default_max_health() -> f32 { 20.0 }
fn default_deepest() -> i32 { 128 }
impl Default for ProgressSave {
    fn default() -> Self {
        Self {
            armor: Vec::new(), max_health: default_max_health(), dim: 0,
            dim_pos: Vec::new(), dim_visited: Vec::new(), respawn: None,
            end_dragon_dead: false, nether_wither_dead: false, world_secs: 0.0,
            best_beacon: 0, deepest_y: default_deepest(),
            blessings: crate::blessing::Attunement::default(),
            trades_done: Vec::new(),
            weather: 0, weather_cd: 0.0, crafted_recipes: Vec::new(),
        }
    }
}
#[derive(serde::Serialize, serde::Deserialize, Default, Debug, Clone)]
pub struct InventorySave { pub selected: usize, pub slots: Vec<InvSlotSave> }
#[derive(serde::Serialize, serde::Deserialize, Default, Debug, Clone)]
pub struct InvSlotSave { pub id: crate::world::block::Id, pub count: i32 }
#[derive(serde::Serialize, serde::Deserialize, Default, Debug, Clone)]
pub struct StatsSave { pub placed: i32, pub broken: i32, pub walked: i32, pub night_seen: bool }

pub fn player_file(base: &str) -> PathBuf {
    Path::new(base).join("player.json")
}
pub fn save_player(base: &str, save: &PlayerSave) -> std::io::Result<()> {
    let path = player_file(base);
    if let Some(p) = path.parent() { fs::create_dir_all(p)?; }
    let json = serde_json::to_string(save).unwrap_or_default();
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, json.as_bytes())?;
    fs::rename(tmp, path)?;
    Ok(())
}
pub fn load_player(base: &str) -> Option<PlayerSave> {
    let path = player_file(base);
    let s = fs::read_to_string(&path).ok()?;
    serde_json::from_str(&s).ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use super::super::chunk::{BlockSection, SECTIONS_PER_CHUNK};

    #[test]
    fn vox3_round_trips_blocks_and_meta() {
        let mut original = Chunk::new(ChunkPos(7, 7));
        original.set_block(0, 0, 0, 1);
        original.set_block(15, 255, 15, 123);
        original.set_block_meta(8, 64, 8, 102, 0b100);
        original.set_block(0, 255, 0, 123);
        // An id past what a byte can hold, which is the whole reason the format is 16-bit.
        original.set_block(1, 255, 0, 900);

        let encoded = encode_chunk(&original);
        assert_eq!(&encoded[0..4], b"VOX3");

        let mut loaded = Chunk::new(ChunkPos(7, 7));
        decode_chunk(&encoded, &mut loaded).expect("VOX3 must decode");
        assert_eq!(loaded.get_block(1, 255, 0), 900, "a wide id must survive the round trip intact");
        for (a, b) in original.sections.iter().zip(loaded.sections.iter()) {
            match (a, b) {
                (Some(a), Some(b)) => {
                    assert_eq!(a.blocks, b.blocks, "every block id must survive");
                    assert_eq!(a.meta.is_some(), b.meta.is_some());
                    if let (Some(am), Some(bm)) = (&a.meta, &b.meta) { assert_eq!(&am[..], &bm[..]); }
                }
                (None, None) => {}
                _ => panic!("section presence changed across a VOX3 round trip"),
            }
        }
    }

    // A decode error makes ChunkMap regenerate the chunk from the seed, so `Err` has to mean "this
    // really is not a chunk file" and nothing weaker.
    #[test]
    fn decode_errors_only_on_genuinely_corrupt_input() {
        let mut chunk = Chunk::new(ChunkPos(0, 0));
        chunk.set_block(1, 1, 1, 5);
        let good = encode_chunk(&chunk);

        let mut out = Chunk::new(ChunkPos(0, 0));
        assert!(decode_chunk(&good, &mut out).is_ok());
        assert!(decode_chunk(&[], &mut out).is_err(), "an empty file is not a chunk");
        assert!(decode_chunk(b"NOPE\x10", &mut out).is_err(), "a foreign magic is not a chunk");
        assert!(decode_chunk(&good[..good.len()/2], &mut out).is_err(), "a truncated section is corrupt");

        // A file whose header promises more sections than it carries stops early rather than
        // failing: the sections it did carry are real data worth keeping.
        let mut partial = Chunk::new(ChunkPos(0, 0));
        assert!(decode_chunk(&good[..6 + 8192 + 1], &mut partial).is_ok());
        assert_eq!(partial.get_block(1, 1, 1), 5);
    }

    // `ChunkMap::load_or_gen` treats a decode failure as "never generated" and rebuilds the chunk
    // from the seed, so a file it can't read silently erases whatever the player built. This drives
    // the real load path against a real file on disk rather than just the codec.
    #[test]
    fn a_saved_world_reloads_instead_of_regenerating() {
        use super::super::ChunkMap;
        let dir = std::env::temp_dir().join("voxels_reload_test");
        let _ = fs::remove_dir_all(&dir);
        let base = dir.to_string_lossy().into_owned();

        // Build a chunk well above sea level so the water pass can't touch it, and save it exactly
        // the way the engine does.
        let pos = ChunkPos(5, -3);
        let mut original = Chunk::new(pos);
        original.set_block(2, 200, 3, 42);
        original.set_block_meta(4, 200, 3, 103, 0b110);
        save_chunk(&base, &original).unwrap();

        let mut map = ChunkMap::new(0xB10CCA, base.clone());
        map.load_or_gen(pos);
        assert_eq!(map.get_block_world(5 * 16 + 2, 200, -3 * 16 + 3), 42, "the build was regenerated away");
        assert_eq!(map.get_block_world(5 * 16 + 4, 200, -3 * 16 + 3), 103);
        assert_eq!(map.get_meta_world(5 * 16 + 4, 200, -3 * 16 + 3), 0b110, "stair facing was lost");

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn slabs_and_stairs_survive_a_round_trip() {
        let mut original = Chunk::new(ChunkPos(0, 0));
        // A slab, and stairs facing each of the four compass directions.
        original.set_block_meta(0, 64, 0, 102, 0b100);       // top-half slab
        original.set_block_meta(1, 64, 0, 103, 0);           // stairs facing north, bottom
        original.set_block_meta(2, 64, 0, 103, 1);           // east
        original.set_block_meta(3, 64, 0, 103, 0b110);       // south, top-half
        original.set_block_meta(4, 64, 0, 103, 3);           // west
        original.set_block(5, 64, 0, 1);                     // a plain cube alongside

        let mut loaded = Chunk::new(ChunkPos(0, 0));
        decode_chunk(&encode_chunk(&original), &mut loaded).expect("the chunk must decode");

        assert_eq!(loaded.get_meta(0, 64, 0), 0b100);
        assert_eq!(loaded.get_meta(1, 64, 0), 0);
        assert_eq!(loaded.get_meta(2, 64, 0), 1);
        assert_eq!(loaded.get_meta(3, 64, 0), 0b110);
        assert_eq!(loaded.get_meta(4, 64, 0), 3);
        assert_eq!(loaded.get_block(3, 64, 0), 103);
        assert_eq!(loaded.get_block(5, 64, 0), 1);
        assert_eq!(loaded.get_meta(5, 64, 0), 0);
    }

    // Terrain is all cubes, so a chunk that never stored a meta value must not pay for one — on
    // disk or in memory.
    #[test]
    fn cube_only_chunks_carry_no_meta() {
        let mut chunk = Chunk::new(ChunkPos(0, 0));
        for x in 0..16 { for z in 0..16 { chunk.set_block(x, 10, z, 1); } }
        let sec = chunk.sections[0].as_ref().expect("section 0 holds the stone");
        assert!(sec.meta.is_none(), "a cube-only section must not allocate a meta array");

        let encoded = encode_chunk(&chunk);
        let one_section = 1 + 8192 + 1; // present flag, blocks as u16, has-meta flag
        assert_eq!(encoded.len(), 5 + one_section + (SECTIONS_PER_CHUNK - 1), "no meta bytes should be written");
    }

    // Clearing a block must clear its meta too, or a later slab placed in the same cell would
    // inherit a stale facing.
    #[test]
    fn replacing_a_block_clears_its_meta() {
        let mut s = BlockSection::empty();
        s.set(1, 2, 3, 103);
        s.set_meta(1, 2, 3, 0b110);
        assert_eq!(s.get_meta(1, 2, 3), 0b110);
        s.set(1, 2, 3, 1);
        assert_eq!(s.get_meta(1, 2, 3), 0, "a fresh block starts with default meta");
    }

    #[test]
    fn progress_round_trips() {
        let mut ps = PlayerSave::default();
        ps.progress.max_health = 34.0;
        ps.progress.dim = 1;
        ps.progress.armor = vec![InvSlotSave { id: 175, count: 400 }];
        ps.progress.dim_pos = vec![[1.0, 2.0, 3.0], [4.0, 5.0, 6.0], [0.0; 3]];
        ps.progress.dim_visited = vec![true, true, false];
        ps.progress.respawn = Some([8.0, 70.0, 9.0]);
        ps.progress.nether_wither_dead = true;
        ps.progress.world_secs = 372.5;
        ps.progress.trades_done = vec![0, 7, 0, 0, 0, 0, 14, 0, 0, 0, 0];
        ps.progress.weather = 2;
        ps.progress.weather_cd = 88.0;
        ps.progress.crafted_recipes = vec![0b0000_0101, 0b1000_0000];
        let back: PlayerSave = serde_json::from_str(&serde_json::to_string(&ps).unwrap()).unwrap();
        assert_eq!(back.progress.max_health, 34.0);
        assert_eq!(back.progress.dim, 1);
        assert_eq!(back.progress.armor[0].id, 175);
        assert_eq!(back.progress.dim_visited, vec![true, true, false]);
        assert_eq!(back.progress.respawn, Some([8.0, 70.0, 9.0]));
        assert!(back.progress.nether_wither_dead);
        assert_eq!(back.progress.world_secs, 372.5);
        assert_eq!(back.progress.trades_done[1], 7, "villager levels have to survive a quit");
        assert_eq!(back.progress.trades_done[6], 14);
        assert_eq!(back.progress.weather, 2, "a storm has to still be raging after a reload");
        assert_eq!(back.progress.weather_cd, 88.0);
        assert_eq!(back.progress.crafted_recipes, vec![0b0000_0101, 0b1000_0000], "crafted recipes must persist");
    }
}
