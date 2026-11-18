use glam::Vec3;
use crate::world::{ChunkMap, block::{Block, Shape}};

#[derive(Debug, Clone, Copy)]
pub struct HitResult { pub pos: (i32,i32,i32), pub prev: (i32,i32,i32), pub normal: (i32,i32,i32), pub dist: f32 }

/// Slab in `t` for a ray against one box, in world space. Returns the entry distance and the axis
/// that was crossed on entry, or None if the ray misses.
fn ray_box(origin: Vec3, dir: Vec3, min: [f32; 3], max: [f32; 3]) -> Option<(f32, usize, bool)> {
    let o = [origin.x, origin.y, origin.z];
    let d = [dir.x, dir.y, dir.z];
    let (mut t_near, mut t_far) = (f32::NEG_INFINITY, f32::INFINITY);
    let (mut axis, mut positive) = (0usize, false);
    for i in 0..3 {
        if d[i].abs() < 1e-8 {
            // Parallel to this slab: a miss unless the origin already lies inside it.
            if o[i] < min[i] || o[i] > max[i] { return None; }
            continue;
        }
        let inv = 1.0 / d[i];
        let mut t0 = (min[i] - o[i]) * inv;
        let mut t1 = (max[i] - o[i]) * inv;
        let mut enter_positive = false; // the ray enters through this axis' max face
        if t0 > t1 { std::mem::swap(&mut t0, &mut t1); enter_positive = true; }
        if t0 > t_near { t_near = t0; axis = i; positive = enter_positive; }
        if t1 < t_far { t_far = t1; }
        if t_near > t_far { return None; }
    }
    if t_far < 0.0 { return None; }
    Some((t_near.max(0.0), axis, positive))
}

/// Can a pick ray stop on this block? Collision solidity (`is_solid`) is the wrong predicate
/// here: crops and glass have no collision box (you walk through a crop field, and glass is
/// excluded from `is_solid` for light/face rules) but both fill their cell for interaction —
/// a crop that no ray can hit can never be harvested, and glass could never be broken or
/// built against. Fluids and portals stay untargetable so casts and travel pass through them.
fn targetable(block: Block) -> bool {
    block.is_solid() || block.is_crop() || block == Block::Glass
}

/// The nearest surface of a block within its cell, and the outward normal of the face hit. Cubes
/// short-circuit; slabs and stairs are tested box by box so you can look through their empty half.
fn hit_in_cell(chunks: &ChunkMap, cell: (i32, i32, i32), origin: Vec3, dir: Vec3) -> Option<(f32, (i32, i32, i32))> {
    let (x, y, z) = cell;
    let id = chunks.get_block_world(x, y, z);
    if id == 0 { return None; }
    let block = Block::from_id(id);
    if !targetable(block) { return None; }
    let meta = chunks.get_meta_world(x, y, z);
    let base = [x as f32, y as f32, z as f32];
    // Crops and glass have no collision boxes, but for picking they occupy the whole cell.
    let owned = block.collision_boxes(meta);
    let cube = [crate::world::block::FULL_CUBE];
    let boxes: &[crate::world::block::Aabb] = if block.is_crop() || block == Block::Glass { &cube } else { owned.as_slice() };
    let mut best: Option<(f32, (i32, i32, i32))> = None;
    for b in boxes {
        let min = [base[0] + b.min[0], base[1] + b.min[1], base[2] + b.min[2]];
        let max = [base[0] + b.max[0], base[1] + b.max[1], base[2] + b.max[2]];
        let Some((t, axis, positive)) = ray_box(origin, dir, min, max) else { continue; };
        if best.is_some_and(|(bt, _)| bt <= t) { continue; }
        let mut n = [0i32; 3];
        n[axis] = if positive { 1 } else { -1 };
        best = Some((t, (n[0], n[1], n[2])));
    }
    best
}

/// The cell a face normal points into — where a block placed against this hit would go.
fn step_out(cell: (i32, i32, i32), normal: (i32, i32, i32)) -> (i32, i32, i32) {
    (cell.0 + normal.0, cell.1 + normal.1, cell.2 + normal.2)
}

pub fn raycast(chunks: &ChunkMap, origin: Vec3, dir: Vec3, max_dist: f32) -> Option<HitResult> {
    let dir = dir.normalize_or_zero();
    if dir.length_squared() < 1e-6 { return None; }
    let mut x = origin.x.floor() as i32;
    let mut y = origin.y.floor() as i32;
    let mut z = origin.z.floor() as i32;
    let step_x = if dir.x > 0.0 { 1 } else if dir.x < 0.0 { -1 } else { 0 };
    let step_y = if dir.y > 0.0 { 1 } else if dir.y < 0.0 { -1 } else { 0 };
    let step_z = if dir.z > 0.0 { 1 } else if dir.z < 0.0 { -1 } else { 0 };
    let t_delta_x = if dir.x != 0.0 { 1.0 / dir.x.abs() } else { f32::INFINITY };
    let t_delta_y = if dir.y != 0.0 { 1.0 / dir.y.abs() } else { f32::INFINITY };
    let t_delta_z = if dir.z != 0.0 { 1.0 / dir.z.abs() } else { f32::INFINITY };
    let mut t_max_x = if step_x != 0 {
        let border = if step_x > 0 { x as f32 + 1.0 } else { x as f32 };
        (border - origin.x) / dir.x
    } else { f32::INFINITY };
    let mut t_max_y = if step_y != 0 {
        let border = if step_y > 0 { y as f32 + 1.0 } else { y as f32 };
        (border - origin.y) / dir.y
    } else { f32::INFINITY };
    let mut t_max_z = if step_z != 0 {
        let border = if step_z > 0 { z as f32 + 1.0 } else { z as f32 };
        (border - origin.z) / dir.z
    } else { f32::INFINITY };
    let mut prev;
    let mut dist = 0.0;
    // Which axis the DDA last crossed, so a cube hit reports the face it entered through without
    // relying on float equality between accumulated t values.
    let mut step_axis;
    // Starting inside a block still counts, but only if the ray actually meets its geometry — the
    // eye can sit in the empty half of a slab's cell.
    if let Some((t, normal)) = hit_in_cell(chunks, (x,y,z), origin, dir) {
        return Some(HitResult{ pos: (x,y,z), prev: step_out((x,y,z), normal), normal, dist: t });
    }
    loop {
        if dist > max_dist { return None; }
        if t_max_x < t_max_y && t_max_x < t_max_z {
            prev = (x,y,z); x += step_x; dist = t_max_x; t_max_x += t_delta_x; step_axis = 0;
        } else if t_max_y < t_max_z {
            prev = (x,y,z); y += step_y; dist = t_max_y; t_max_y += t_delta_y; step_axis = 1;
        } else {
            prev = (x,y,z); z += step_z; dist = t_max_z; t_max_z += t_delta_z; step_axis = 2;
        }
        if dist > max_dist { return None; }
        let id = chunks.get_block_world(x, y, z);
        if id != 0 && targetable(Block::from_id(id)) {
            if Block::from_id(id).shape() == Shape::Cube {
                // A full cube is entered exactly at the DDA boundary we just crossed.
                let normal = match step_axis {
                    0 => (-step_x, 0, 0),
                    1 => (0, -step_y, 0),
                    _ => (0, 0, -step_z),
                };
                return Some(HitResult{ pos: (x,y,z), prev, normal, dist });
            }
            // Partial block: keep going unless the ray really meets one of its boxes.
            if let Some((t, normal)) = hit_in_cell(chunks, (x,y,z), origin, dir) {
                if t <= max_dist {
                    return Some(HitResult{ pos: (x,y,z), prev: step_out((x,y,z), normal), normal, dist: t });
                }
                return None;
            }
        }
        if x.abs() > 10000 || y.abs() > 10000 || z.abs() > 10000 { return None; }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::world::block::Id;
    use crate::world::block::{Block, FACE_NORTH, META_TOP};
    use glam::vec3;

    // Terrain never reaches this high, so the test cells around it are guaranteed empty air.
    const SKY: i32 = 200;

    fn world() -> ChunkMap {
        let dir = std::env::temp_dir().join("voxels_raycast_test").to_string_lossy().into_owned();
        ChunkMap::new(1, dir)
    }

    // A bottom slab leaves the top half of its cell empty; a ray through that half must pass by.
    #[test]
    fn a_ray_passes_through_a_slabs_empty_half() {
        let mut w = world();
        w.set_block_meta_world(4, SKY, 0, Block::StoneSlab as Id, 0);

        // Aim along +X at 0.75 up the cell, above the slab's solid half.
        let high = raycast(&w, vec3(0.0, SKY as f32 + 0.75, 0.5), vec3(1.0, 0.0, 0.0), 20.0);
        assert!(high.is_none(), "the upper half of a bottom slab's cell is empty");

        // The same ray at 0.25 up must strike it.
        let low = raycast(&w, vec3(0.0, SKY as f32 + 0.25, 0.5), vec3(1.0, 0.0, 0.0), 20.0).expect("must hit the slab");
        assert_eq!(low.pos, (4, SKY, 0));
        assert_eq!(low.normal, (-1, 0, 0), "entered through the slab's west face");
        assert_eq!(low.prev, (3, SKY, 0), "a block placed here goes in front of the slab");
    }

    // Looking straight down onto a bottom slab must report the top surface, not the cell's ceiling.
    #[test]
    fn looking_down_hits_the_slabs_top_surface() {
        let mut w = world();
        w.set_block_meta_world(0, SKY, 0, Block::StoneSlab as Id, 0);
        let hit = raycast(&w, vec3(0.5, SKY as f32 + 5.0, 0.5), vec3(0.0, -1.0, 0.0), 20.0).expect("must hit");
        assert_eq!(hit.pos, (0, SKY, 0));
        assert_eq!(hit.normal, (0, 1, 0));
        assert!((hit.dist - 4.5).abs() < 1e-3, "the surface is half a block up, got dist {}", hit.dist);
        assert_eq!(hit.prev, (0, SKY + 1, 0), "placing against the top goes in the cell above");
    }

    #[test]
    fn a_top_slab_is_hit_from_above_at_the_cell_ceiling() {
        let mut w = world();
        w.set_block_meta_world(0, SKY, 0, Block::StoneSlab as Id, META_TOP);
        let hit = raycast(&w, vec3(0.5, SKY as f32 + 5.0, 0.5), vec3(0.0, -1.0, 0.0), 20.0).expect("must hit");
        assert!((hit.dist - 4.0).abs() < 1e-3, "a top slab's surface is the cell ceiling");
    }

    // Cubes must behave exactly as before the sub-voxel test was added.
    #[test]
    fn cubes_still_hit_at_the_cell_boundary() {
        let mut w = world();
        w.set_block_world(4, SKY, 0, Block::Stone as Id);
        let hit = raycast(&w, vec3(0.0, SKY as f32 + 0.5, 0.5), vec3(1.0, 0.0, 0.0), 20.0).expect("must hit");
        assert_eq!(hit.pos, (4, SKY, 0));
        assert_eq!(hit.normal, (-1, 0, 0));
        assert_eq!(hit.prev, (3, SKY, 0));
        assert!((hit.dist - 4.0).abs() < 1e-3);
    }

    // Stairs facing north keep their tall half on the +Z side; a ray into the low half at head
    // height should sail over the tread.
    #[test]
    fn stairs_are_open_above_their_low_half() {
        let mut w = world();
        w.set_block_meta_world(4, SKY, 0, Block::StoneStairs as Id, FACE_NORTH);
        // z = 0.25 is the low (north) half, 0.75 up the cell is above the tread.
        assert!(raycast(&w, vec3(0.0, SKY as f32 + 0.75, 0.25), vec3(1.0, 0.0, 0.0), 20.0).is_none());
        // z = 0.75 is under the tall step, so the same height is solid.
        let hit = raycast(&w, vec3(0.0, SKY as f32 + 0.75, 0.75), vec3(1.0, 0.0, 0.0), 20.0);
        assert!(hit.is_some(), "the stair's tall half must block the ray");
    }
}

#[cfg(test)]
#[path = "raycast_coverage_tests.rs"]
mod raycast_coverage_tests;
