use super::block::{Aabb, Block, Id, Shape, GRASS_SIDE_TILE};
use super::chunk::{Chunk, SECTION_SIZE, SECTIONS_PER_CHUNK, CHUNK_HEIGHT};

/// A neighbour lookup: block id plus its meta byte, since occlusion now depends on both.
pub type Neighbor<'a> = dyn Fn(i32, i32, i32) -> (Id, u8) + 'a;

// Per-chunk lighting: skylight column tops (highest opaque block per column) and a block-light grid
// (BFS flood from emitters, decreasing by 1 per open cell). Cross-chunk bleed is limited to this
// chunk's own data, which is plenty for caves + torch pools.
fn compute_light(chunk: &Chunk) -> ([[i32; 16]; 16], Vec<u8>) {
    let mut col_top = [[-1i32; 16]; 16];
    for (x, col) in col_top.iter_mut().enumerate() { for (z, top) in col.iter_mut().enumerate() {
        for y in (0..CHUNK_HEIGHT).rev() {
            let id = chunk.get_block(x, y, z);
            if id != 0 && Block::from_id(id).blocks_light() { *top = y as i32; break; }
        }
    }}
    let mut blk = vec![0u8; 16 * CHUNK_HEIGHT * 16];
    let idx = |x: usize, y: usize, z: usize| (y * 16 + z) * 16 + x;
    let mut q: std::collections::VecDeque<(usize, usize, usize, u8)> = std::collections::VecDeque::new();
    for y in 0..CHUNK_HEIGHT { for z in 0..16 { for x in 0..16 {
        let e = Block::from_id(chunk.get_block(x, y, z)).light_emission();
        if e > 0 { let i = idx(x, y, z); if blk[i] < e { blk[i] = e; q.push_back((x, y, z, e)); } }
    }}}
    while let Some((x, y, z, l)) = q.pop_front() {
        if l <= 1 { continue; }
        let nl = l - 1;
        let nbrs = [(x as i32 + 1, y as i32, z as i32), (x as i32 - 1, y as i32, z as i32),
                    (x as i32, y as i32 + 1, z as i32), (x as i32, y as i32 - 1, z as i32),
                    (x as i32, y as i32, z as i32 + 1), (x as i32, y as i32, z as i32 - 1)];
        for (nx, ny, nz) in nbrs {
            if !(0..16).contains(&nx) || !(0..16).contains(&nz) || ny < 0 || ny >= CHUNK_HEIGHT as i32 { continue; }
            let (nx, ny, nz) = (nx as usize, ny as usize, nz as usize);
            let bid = chunk.get_block(nx, ny, nz);
            if bid != 0 && Block::from_id(bid).blocks_light() { continue; }
            let i = idx(nx, ny, nz);
            if blk[i] < nl { blk[i] = nl; q.push_back((nx, ny, nz, nl)); }
        }
    }
    (col_top, blk)
}
// Pack skylight (low nibble) + blocklight (high nibble) into a u8; decode to the shader's f32.
fn light_to_f32(packed: u8) -> f32 { (packed & 0x0F) as f32 + ((packed >> 4) & 0x0F) as f32 * 16.0 }

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct Vertex {
    pub pos: [f32; 3],
    // uv carries per-block tile counts (0..w, 0..h); the shader wraps with fract into the tile so
    // greedy-merged faces tile the texture instead of stretching one tile across the whole quad.
    pub uv: [f32; 2],
    pub color: [f32; 3],
    pub ao: f32,
    pub tile_idx: f32,
    pub normal: [f32; 3],
    // Packed lighting: skylight + blocklight*16 (each 0..15). Read by block.frag; 0 elsewhere.
    pub light: f32,
}

pub struct MeshData {
    pub vertices: Vec<Vertex>,
    pub indices: Vec<u32>,
    // Water surface, rendered separately as a transparent, wavy pass.
    pub water_vertices: Vec<Vertex>,
    pub water_indices: Vec<u32>,
}

impl MeshData { pub fn is_empty(&self) -> bool { self.vertices.is_empty() && self.water_vertices.is_empty() } }

// Classic voxel corner AO: 0 (darkest) .. 3 (fully open) from the two edge neighbors + the diagonal.
fn vao(s1: bool, s2: bool, c: bool) -> u8 { if s1 && s2 { 0 } else { 3 - (s1 as u8 + s2 as u8 + c as u8) } }
fn aof(level: u8) -> f32 { level as f32 / 3.0 }

const FACE_DIRS: [(i32, i32, i32); 6] = [(1,0,0), (-1,0,0), (0,1,0), (0,-1,0), (0,0,1), (0,0,-1)];

/// Is this face of `b` sealed against another box of the same block? Emitting both sides of a shared
/// plane would z-fight, so the fully covered one is dropped.
fn face_covered_by_sibling(b: &Aabb, siblings: &[Aabb], axis: usize, positive: bool) -> bool {
    let plane = if positive { b.max[axis] } else { b.min[axis] };
    let (u, v) = ((axis + 1) % 3, (axis + 2) % 3);
    siblings.iter().any(|s| {
        if std::ptr::eq(s, b) { return false; }
        // The sibling must start exactly where this face ends, on the far side.
        let touches = if positive { s.min[axis] == plane } else { s.max[axis] == plane };
        touches && s.min[u] <= b.min[u] && s.max[u] >= b.max[u]
               && s.min[v] <= b.min[v] && s.max[v] >= b.max[v]
    })
}

/// Emit the six faces of one sub-block box. `cell` is the box's cell in section-local coordinates and
/// `origin` the section's world corner. Faces flush with the cell boundary are culled against the
/// neighbouring block; faces sealed against a sibling box are dropped.
#[allow(clippy::too_many_arguments)]
fn append_box(
    verts: &mut Vec<Vertex>, indices: &mut Vec<u32>,
    block: Block, b: Aabb, siblings: &[Aabb],
    cell: [f32; 3], origin: [f32; 3],
    hidden: &dyn Fn(i32, i32, i32) -> bool,
    light: &dyn Fn(i32, i32, i32) -> u8,
) {
    let color = block.color();
    for (dx, dy, dz) in FACE_DIRS {
        let axis = if dx != 0 { 0 } else if dy != 0 { 1 } else { 2 };
        let positive = dx + dy + dz > 0;
        let plane = if positive { b.max[axis] } else { b.min[axis] };
        // Only a face sitting exactly on the cell wall can be hidden by the neighbouring block.
        let flush = if positive { plane >= 1.0 } else { plane <= 0.0 };
        if flush && hidden(dx, dy, dz) { continue; }
        if face_covered_by_sibling(&b, siblings, axis, positive) { continue; }

        let (x0, y0, z0) = (b.min[0], b.min[1], b.min[2]);
        let (x1, y1, z1) = (b.max[0], b.max[1], b.max[2]);
        // Texture coordinates follow the block's own 0..1 cell, so a half-height side samples the
        // matching half of the tile rather than squashing the whole thing into it. v grows downward.
        let corners: [([f32; 3], [f32; 2]); 4] = match (dx, dy, dz) {
            (1, 0, 0) => [([x1,y0,z0],[z0,1.0-y0]), ([x1,y1,z0],[z0,1.0-y1]), ([x1,y1,z1],[z1,1.0-y1]), ([x1,y0,z1],[z1,1.0-y0])],
            (-1, 0, 0) => [([x0,y0,z1],[1.0-z1,1.0-y0]), ([x0,y1,z1],[1.0-z1,1.0-y1]), ([x0,y1,z0],[1.0-z0,1.0-y1]), ([x0,y0,z0],[1.0-z0,1.0-y0])],
            (0, 1, 0) => [([x0,y1,z0],[x0,z0]), ([x1,y1,z0],[x1,z0]), ([x1,y1,z1],[x1,z1]), ([x0,y1,z1],[x0,z1])],
            (0, -1, 0) => [([x0,y0,z1],[x0,1.0-z1]), ([x1,y0,z1],[x1,1.0-z1]), ([x1,y0,z0],[x1,1.0-z0]), ([x0,y0,z0],[x0,1.0-z0])],
            (0, 0, 1) => [([x0,y0,z1],[x0,1.0-y0]), ([x1,y0,z1],[x1,1.0-y0]), ([x1,y1,z1],[x1,1.0-y1]), ([x0,y1,z1],[x0,1.0-y1])],
            _ => [([x1,y0,z0],[1.0-x1,1.0-y0]), ([x0,y0,z0],[1.0-x0,1.0-y0]), ([x0,y1,z0],[1.0-x0,1.0-y1]), ([x1,y1,z0],[1.0-x1,1.0-y1])],
        };
        let tile = block.tile_for_dir(dx, dy, dz) as f32;
        let lpk = light(dx, dy, dz);
        let start = verts.len() as u32;
        for (pos, uv) in corners {
            verts.push(Vertex {
                pos: [origin[0] + cell[0] + pos[0], origin[1] + cell[1] + pos[1], origin[2] + cell[2] + pos[2]],
                uv,
                color,
                // Partial faces get flat lighting: the corner AO sampler only understands whole cells.
                ao: 1.0,
                tile_idx: tile,
                normal: [dx as f32, dy as f32, dz as f32],
                light: light_to_f32(lpk),
            });
        }
        indices.extend_from_slice(&[start, start+1, start+2, start, start+2, start+3]);
    }
}

pub fn mesh_chunk(chunk: &Chunk, get_neighbor: &Neighbor, grass_tint: &dyn Fn(i32,i32)->[f32;3]) -> Vec<Option<MeshData>> {
    // AO only samples whole cells, so a partial block never darkens its neighbours.
    let occ = |ax: i32, ay: i32, az: i32| -> bool {
        let (id, _) = get_neighbor(ax, ay, az);
        id != 0 && Block::from_id(id).is_opaque()
    };
    // Whether the neighbour in direction (dx,dy,dz) fully covers the face we are about to emit.
    let hidden = |wx: i32, wy: i32, wz: i32, dx: i32, dy: i32, dz: i32| -> bool {
        let (nid, nmeta) = get_neighbor(wx + dx, wy + dy, wz + dz);
        if nid == 0 { return false; }
        // The neighbour's own face points back at us.
        Block::from_id(nid).occludes_face(nmeta, -dx, -dy, -dz)
    };
    let mut result: Vec<Option<MeshData>> = (0..SECTIONS_PER_CHUNK).map(|_| None).collect();
    let ox = chunk.pos.world_origin().0;
    let oz = chunk.pos.world_origin().1;
    // Lighting for the whole chunk column, sampled per exposed face (at the neighbouring air cell).
    let (col_top, blk) = compute_light(chunk);
    let face_light = |wx: i32, wy: i32, wz: i32| -> u8 {
        let lx = (wx - ox).clamp(0, 15) as usize;
        let lz = (wz - oz).clamp(0, 15) as usize;
        let top = col_top[lx][lz];
        let sky = if wy > top { 15 } else { (15 - (top - wy) * 3).max(0) } as u8;
        let by = wy.clamp(0, CHUNK_HEIGHT as i32 - 1) as usize;
        let b = blk[(by * 16 + lz) * 16 + lx];
        sky | (b << 4)
    };
    for (sec_idx, slot) in result.iter_mut().enumerate() {
        let section = match &chunk.sections[sec_idx] {
            Some(s) if !s.is_empty() => s,
            _ => continue,
        };
        let base_y = (sec_idx * SECTION_SIZE) as i32;
        let mut verts = Vec::with_capacity(1024);
        let mut indices = Vec::with_capacity(1536);
        let dirs = [(1,0,0,0), (-1,0,0,0), (0,1,0,1), (0,-1,0,1), (0,0,1,2), (0,0,-1,2)];
        for &(dx,dy,dz, axis) in &dirs {
            let nrm = [dx as f32, dy as f32, dz as f32];
            // Mask entry: (block id, tile, per-corner AO in this direction's vertex order).
            match axis {
                0 => {
                    for x in 0..SECTION_SIZE {
                        let mut mask = [[None::<(Id,u32,[u8;4],u8)>; 16]; 16];
                        for (y, row) in mask.iter_mut().enumerate() { for (z, cell) in row.iter_mut().enumerate() {
                            let id = section.get(x,y,z);
                            if id == 0 || id == Block::Water as Id { continue; }
                            if Block::from_id(id).shape() != Shape::Cube { continue; } // emitted as boxes below
                            let wx = ox + x as i32; let wy = base_y + y as i32; let wz = oz + z as i32;
                            if !hidden(wx, wy, wz, dx, dy, dz) {
                                let tile = Block::from_id(id).tile_for_dir(dx,dy,dz);
                                let nb = wx + dx;
                                let ao = |ys: i32, zs: i32| vao(occ(nb, wy+ys, wz), occ(nb, wy, wz+zs), occ(nb, wy+ys, wz+zs));
                                let ao4 = if dx==1 { [ao(-1,-1), ao(1,-1), ao(1,1), ao(-1,1)] }
                                          else       { [ao(-1,1),  ao(1,1),  ao(1,-1), ao(-1,-1)] };
                                *cell = Some((id, tile, ao4, face_light(wx+dx, wy+dy, wz+dz)));
                            }
                        }}
                        let mut visited = [[false; 16]; 16];
                        for (y, row) in mask.iter().enumerate() { for (z, cell) in row.iter().enumerate() {
                            if visited[y][z] { continue; }
                            let Some((bid, tile_idx, ao4, lpk)) = *cell else { continue; };
                            let mut w = 1;
                            while z + w < SECTION_SIZE && !visited[y][z+w] && mask[y][z+w] == Some((bid, tile_idx, ao4, lpk)) { w+=1; }
                            let mut h = 1;
                            'outer: while y + h < SECTION_SIZE {
                                for k in 0..w { if visited[y+h][z+k] || mask[y+h][z+k] != Some((bid, tile_idx, ao4, lpk)) { break 'outer; } }
                                h+=1;
                            }
                            for row in visited.iter_mut().skip(y).take(h) { for cell in row.iter_mut().skip(z).take(w) { *cell = true; } }
                            let x0 = x as f32 + if dx==1 { 1.0 } else { 0.0 };
                            let y0 = y as f32; let z0 = z as f32; let ww = w as f32; let hh = h as f32;
                            let (u0,v0,u1,v1) = (0.0f32, 0.0f32, ww, hh);
                            // Grass side: sentinel tile so the shader composites dirt + tinted overlay in one quad.
                            let is_grass = bid == Block::Grass as Id;
                            let color = if is_grass { grass_tint(ox + x as i32, oz + z as i32) } else { Block::from_id(bid).color() };
                            let etile = if is_grass { GRASS_SIDE_TILE as f32 } else { tile_idx as f32 };
                            let quad = if dx==1 {
                                [([x0, y0, z0], [u0, v1]), ([x0, y0+hh, z0], [u0, v0]), ([x0, y0+hh, z0+ww], [u1, v0]), ([x0, y0, z0+ww], [u1, v1])]
                            } else {
                                [([x0, y0, z0+ww], [u0, v1]), ([x0, y0+hh, z0+ww], [u0, v0]), ([x0, y0+hh, z0], [u1, v0]), ([x0, y0, z0], [u1, v1])]
                            };
                            let wx0 = ox as f32; let wz0 = oz as f32; let by = base_y as f32;
                            let start = verts.len() as u32;
                            for (i,(pos, uv)) in quad.iter().enumerate() {
                                verts.push(Vertex{ pos: [wx0 + pos[0], by + pos[1], wz0 + pos[2]], uv: *uv, color, ao: aof(ao4[i]), tile_idx: etile, normal: nrm, light: light_to_f32(lpk) });
                            }
                            indices.extend_from_slice(&[start, start+1, start+2, start, start+2, start+3]);
                        }}
                    }
                }
                1 => {
                    for y in 0..SECTION_SIZE {
                        let mut mask = [[None::<(Id,u32,[u8;4],u8)>; 16]; 16];
                        for (x, row) in mask.iter_mut().enumerate() { for (z, cell) in row.iter_mut().enumerate() {
                            let id = section.get(x,y,z);
                            if id==0 || id==Block::Water as Id { continue; }
                            if Block::from_id(id).shape() != Shape::Cube { continue; }
                            let wx = ox + x as i32; let wy = base_y + y as i32; let wz = oz + z as i32;
                            if !hidden(wx, wy, wz, dx, dy, dz) {
                                {
                                    let tile = Block::from_id(id).tile_for_dir(dx,dy,dz);
                                    let nb = wy + dy;
                                    let ao = |xs: i32, zs: i32| vao(occ(wx+xs, nb, wz), occ(wx, nb, wz+zs), occ(wx+xs, nb, wz+zs));
                                    let ao4 = if dy==1 { [ao(-1,-1), ao(1,-1), ao(1,1), ao(-1,1)] }
                                              else       { [ao(-1,1),  ao(1,1),  ao(1,-1), ao(-1,-1)] };
                                    *cell=Some((id, tile, ao4, face_light(wx+dx, wy+dy, wz+dz)));
                                }
                            }
                        }}
                        let mut visited = [[false; 16]; 16];
                        for (x, row) in mask.iter().enumerate() { for (z, cell) in row.iter().enumerate() {
                            if visited[x][z] { continue; }
                            let Some((bid, tile_idx, ao4, lpk)) = *cell else { continue; };
                            let mut w = 1;
                            while z+w < SECTION_SIZE && !visited[x][z+w] && mask[x][z+w]==Some((bid, tile_idx, ao4, lpk)) { w+=1; }
                            let mut h = 1;
                            'outer2: while x+h < SECTION_SIZE {
                                for k in 0..w { if visited[x+h][z+k] || mask[x+h][z+k]!=Some((bid, tile_idx, ao4, lpk)) { break 'outer2; } }
                                h+=1;
                            }
                            for row in visited.iter_mut().skip(x).take(h) { for cell in row.iter_mut().skip(z).take(w) { *cell = true; } }
                            let y0 = y as f32 + if dy==1 { 1.0 } else { 0.0 };
                            let x0 = x as f32; let z0 = z as f32; let ww = w as f32; let hh = h as f32;
                            let (u0,v0,u1,v1) = (0.0f32, 0.0f32, hh, ww);
                            let color = if bid == Block::Grass as Id && dy == 1 {
                                grass_tint(ox + x as i32, oz + z as i32)
                            } else { Block::from_id(bid).color() };
                            let wx0 = ox as f32; let wz0 = oz as f32; let by = base_y as f32;
                            let quad = if dy==1 {
                                [([x0, y0, z0], [u0, v0]), ([x0+hh, y0, z0], [u1, v0]), ([x0+hh, y0, z0+ww], [u1, v1]), ([x0, y0, z0+ww], [u0, v1])]
                            } else {
                                [([x0, y0, z0+ww], [u0, v0]), ([x0+hh, y0, z0+ww], [u1, v0]), ([x0+hh, y0, z0], [u1, v1]), ([x0, y0, z0], [u0, v1])]
                            };
                            let start = verts.len() as u32;
                            for (i,(lp, uv)) in quad.iter().enumerate() {
                                verts.push(Vertex{ pos: [wx0 + lp[0], by + lp[1], wz0 + lp[2]], uv: *uv, color, ao: aof(ao4[i]), tile_idx: tile_idx as f32, normal: nrm, light: light_to_f32(lpk) });
                            }
                            indices.extend_from_slice(&[start, start+1, start+2, start, start+2, start+3]);
                        }}
                    }
                }
                2 => {
                    for z in 0..SECTION_SIZE {
                        let mut mask = [[None::<(Id,u32,[u8;4],u8)>; 16]; 16];
                        for (x, row) in mask.iter_mut().enumerate() { for (y, cell) in row.iter_mut().enumerate() {
                            let id = section.get(x,y,z);
                            if id==0 || id==Block::Water as Id { continue; }
                            if Block::from_id(id).shape() != Shape::Cube { continue; }
                            let wx = ox + x as i32; let wy = base_y + y as i32; let wz = oz + z as i32;
                            if !hidden(wx, wy, wz, dx, dy, dz) {
                                let tile = Block::from_id(id).tile_for_dir(dx,dy,dz);
                                let nb = wz + dz;
                                let ao = |xs: i32, ys: i32| vao(occ(wx+xs, wy, nb), occ(wx, wy+ys, nb), occ(wx+xs, wy+ys, nb));
                                let ao4 = if dz==1 { [ao(-1,-1), ao(1,-1), ao(1,1), ao(-1,1)] }
                                          else       { [ao(1,-1),  ao(-1,-1), ao(-1,1), ao(1,1)] };
                                *cell=Some((id, tile, ao4, face_light(wx+dx, wy+dy, wz+dz)));
                            }
                        }}
                        let mut visited = [[false; 16]; 16];
                        for (x, row) in mask.iter().enumerate() { for (y, cell) in row.iter().enumerate() {
                            if visited[x][y] { continue; }
                            let Some((bid, tile_idx, ao4, lpk)) = *cell else { continue; };
                            let mut w = 1;
                            while y+w < SECTION_SIZE && !visited[x][y+w] && mask[x][y+w]==Some((bid, tile_idx, ao4, lpk)) { w+=1; }
                            let mut h = 1;
                            'outer3: while x+h < SECTION_SIZE {
                                for k in 0..w { if visited[x+h][y+k] || mask[x+h][y+k]!=Some((bid, tile_idx, ao4, lpk)) { break 'outer3; } }
                                h+=1;
                            }
                            for row in visited.iter_mut().skip(x).take(h) { for cell in row.iter_mut().skip(y).take(w) { *cell = true; } }
                            let z0 = z as f32 + if dz==1 { 1.0 } else { 0.0 };
                            let x0 = x as f32; let y0 = y as f32; let ww = w as f32; let hh = h as f32;
                            let (u0,v0,u1,v1) = (0.0f32, 0.0f32, hh, ww);
                            let is_grass = bid == Block::Grass as Id;
                            let color = if is_grass { grass_tint(ox + x as i32, oz + z as i32) } else { Block::from_id(bid).color() };
                            let etile = if is_grass { GRASS_SIDE_TILE as f32 } else { tile_idx as f32 };
                            let wx0 = ox as f32; let wz0 = oz as f32; let by = base_y as f32;
                            let quad = if dz==1 {
                                [([x0, y0, z0], [u0, v1]), ([x0+hh, y0, z0], [u1, v1]), ([x0+hh, y0+ww, z0], [u1, v0]), ([x0, y0+ww, z0], [u0, v0])]
                            } else {
                                [([x0+hh, y0, z0], [u0, v1]), ([x0, y0, z0], [u1, v1]), ([x0, y0+ww, z0], [u1, v0]), ([x0+hh, y0+ww, z0], [u0, v0])]
                            };
                            let start = verts.len() as u32;
                            for (i,(lp, uv)) in quad.iter().enumerate() {
                                verts.push(Vertex{ pos: [wx0 + lp[0], by + lp[1], wz0 + lp[2]], uv: *uv, color, ao: aof(ao4[i]), tile_idx: etile, normal: nrm, light: light_to_f32(lpk) });
                            }
                            indices.extend_from_slice(&[start, start+1, start+2, start, start+2, start+3]);
                        }}
                    }
                }
                _ => {}
            }
        }
        // --- Water surface pass: top faces of water columns exposed to air, greedy-merged. ---
        let mut wverts: Vec<Vertex> = Vec::new();
        let mut windices: Vec<u32> = Vec::new();
        for y in 0..SECTION_SIZE {
            let mut wmask = [[false; 16]; 16];
            for (x, row) in wmask.iter_mut().enumerate() { for (z, cell) in row.iter_mut().enumerate() {
                if section.get(x,y,z) != Block::Water as Id { continue; }
                let wx = ox + x as i32; let wy = base_y + y as i32; let wz = oz + z as i32;
                let above = get_neighbor(wx, wy+1, wz).0;
                if above != Block::Water as Id && !(above != 0 && Block::from_id(above).is_opaque()) {
                    *cell = true;
                }
            }}
            let mut visited = [[false; 16]; 16];
            for (x, row) in wmask.iter().enumerate() { for (z, &cell) in row.iter().enumerate() {
                if visited[x][z] || !cell { continue; }
                let mut w = 1; while z+w < SECTION_SIZE && !visited[x][z+w] && wmask[x][z+w] { w+=1; }
                let mut h = 1; 'ow: while x+h < SECTION_SIZE { for k in 0..w { if visited[x+h][z+k] || !wmask[x+h][z+k] { break 'ow; } } h+=1; }
                for row in visited.iter_mut().skip(x).take(h) { for cell in row.iter_mut().skip(z).take(w) { *cell = true; } }
                let y0 = (y+1) as f32;
                let x0 = x as f32; let z0 = z as f32; let ww = w as f32; let hh = h as f32;
                let (u0,v0,u1,v1) = (0.0f32, 0.0f32, hh, ww);
                let wx0 = ox as f32; let wz0 = oz as f32; let by = base_y as f32;
                let quad = [([x0,y0,z0],[u0,v0]), ([x0+hh,y0,z0],[u1,v0]), ([x0+hh,y0,z0+ww],[u1,v1]), ([x0,y0,z0+ww],[u0,v1])];
                let start = wverts.len() as u32;
                for (pos,uv) in quad.iter() {
                    wverts.push(Vertex{ pos: [wx0+pos[0], by+pos[1], wz0+pos[2]], uv: *uv, color: [1.0,1.0,1.0], ao: 1.0, tile_idx: 13.0, normal: [0.0,1.0,0.0], light: 15.0 });
                }
                windices.extend_from_slice(&[start, start+1, start+2, start, start+2, start+3]);
            }}
        }
        // --- Slabs and stairs: emitted box by box, since they never merge with anything. ---
        for y in 0..SECTION_SIZE { for z in 0..SECTION_SIZE { for x in 0..SECTION_SIZE {
            let id = section.get(x, y, z);
            if id == 0 { continue; }
            let block = Block::from_id(id);
            if block.shape() == Shape::Cube { continue; }
            let meta = section.get_meta(x, y, z);
            let (wx, wy, wz) = (ox + x as i32, base_y + y as i32, oz + z as i32);
            let cell = [x as f32, y as f32, z as f32];
            let origin = [ox as f32, base_y as f32, oz as f32];
            let boxes = block.collision_boxes(meta);
            for bx in boxes.as_slice() {
                append_box(&mut verts, &mut indices, block, *bx, boxes.as_slice(), cell, origin,
                           &|dx, dy, dz| hidden(wx, wy, wz, dx, dy, dz),
                           &|dx, dy, dz| face_light(wx + dx, wy + dy, wz + dz));
            }
        }}}
        if !verts.is_empty() || !wverts.is_empty() { *slot = Some(MeshData{ vertices: verts, indices, water_vertices: wverts, water_indices: windices }); }
    }
    result
}

include!("mesher_part1.rs");
#[cfg(test)]
#[path = "mesher_coverage_tests.rs"]
mod mesher_coverage_tests;
