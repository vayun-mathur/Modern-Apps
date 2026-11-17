impl TerrainGen {
    pub fn new(seed: u32) -> Self { Self::new_dim(seed, 0) }
    pub fn new_dim(seed: u32, dim: u8) -> Self {
        Self {
            perlin_height: Perlin::new(seed),
            perlin_detail: Perlin::new(seed.wrapping_add(101)),
            perlin_cave: Perlin::new(seed.wrapping_add(202)),
            perlin_biome: Perlin::new(seed.wrapping_add(303)),
            perlin_ore: Perlin::new(seed.wrapping_add(404)),
            seed,
            dim,
        }
    }
    // Per-biome grass tint (multiplied over the grayscale grass_top / grass_side textures). Uses the
    // same low-frequency biome noise the terrain height uses, plus a humidity sample, so the ground
    // colour shifts smoothly between cool-lush, warm-lush and dry regions.
    pub fn grass_tint(&self, wx: f64, wz: f64) -> [f32; 3] {
        use Biome::*;
        match self.biome_at(wx, wz, self.height_at(wx, wz)) {
            Savanna => [0.74, 0.68, 0.32],                              // dry gold-green
            Jungle | SparseJungle | MangroveSwamp => [0.30, 0.66, 0.18], // vivid tropical
            Swamp => [0.42, 0.48, 0.27],                                // murky
            DarkForest => [0.26, 0.48, 0.20],                           // deep shade
            Desert | Badlands => [0.66, 0.64, 0.34],
            Taiga | SnowyTaiga | SnowyPlains | SnowySlopes | Grove | FrozenPeaks => [0.50, 0.62, 0.48],
            _ => {
                // Temperate default: smooth temperature x humidity blend.
                let temp = (self.perlin_biome.get([wx * 0.0015, wz * 0.0015]) as f32 * 0.5 + 0.5).clamp(0.0, 1.0);
                let humid = (self.perlin_biome.get([wx * 0.0015 + 137.0, wz * 0.0015 - 91.0]) as f32 * 0.5 + 0.5).clamp(0.0, 1.0);
                let lerp3 = |a: [f32; 3], b: [f32; 3], t: f32| [a[0] + (b[0]-a[0])*t, a[1] + (b[1]-a[1])*t, a[2] + (b[2]-a[2])*t];
                let cold = [0.46, 0.62, 0.42];
                let lush = [0.40, 0.68, 0.28];
                let dry  = [0.74, 0.72, 0.36];
                let warm = lerp3(dry, lush, humid);
                lerp3(cold, warm, temp)
            }
        }
    }
    // Classify a column into a biome from temperature/humidity/variant noise plus elevation. Mountain
    // bands take over at height; lowlands split by a Whittaker-style temp x humidity grid, with a
    // low-frequency "variant" noise selecting rarer sub-biomes (ice spikes, flower forest, etc.).
    pub fn biome_at(&self, wx: f64, wz: f64, h: i32) -> Biome {
        let temp = (self.perlin_biome.get([wx * 0.0015, wz * 0.0015]) as f32 * 0.5 + 0.5).clamp(0.0, 1.0);
        let humid = (self.perlin_biome.get([wx * 0.0015 + 137.0, wz * 0.0015 - 91.0]) as f32 * 0.5 + 0.5).clamp(0.0, 1.0);
        let variant = (self.perlin_detail.get([wx * 0.004 + 53.0, wz * 0.004 - 17.0]) as f32 * 0.5 + 0.5).clamp(0.0, 1.0);
        // High mountains.
        if h > 118 {
            if temp < 0.35 { return Biome::FrozenPeaks; }
            if temp > 0.66 { return Biome::StonyPeaks; }
            return Biome::JaggedPeaks;
        }
        if h > 98 {
            if temp < 0.34 { return Biome::SnowySlopes; }
            if humid > 0.5 { return Biome::Grove; }
            return Biome::WindsweptHills;
        }
        // Lowlands by temperature band.
        if temp < 0.28 {
            if variant > 0.86 { return Biome::IceSpikes; }
            if humid > 0.55 { return Biome::SnowyTaiga; }
            Biome::SnowyPlains
        } else if temp > 0.72 {
            if humid < 0.28 { return if variant > 0.72 { Biome::Badlands } else { Biome::Desert }; }
            if humid > 0.60 { return if variant > 0.5 { Biome::Jungle } else { Biome::SparseJungle }; }
            Biome::Savanna
        } else {
            if humid < 0.30 { return Biome::Savanna; }
            if humid > 0.78 { return if variant > 0.7 { Biome::MushroomFields } else if temp > 0.5 { Biome::MangroveSwamp } else { Biome::Swamp }; }
            if humid > 0.55 { return if variant > 0.78 { Biome::DarkForest } else if variant < 0.25 { Biome::Taiga } else { Biome::Forest }; }
            // Plains band with variants.
            if variant > 0.88 { return Biome::SunflowerPlains; }
            if variant > 0.72 { return Biome::FlowerForest; }
            if variant < 0.14 { return Biome::BirchForest; }
            if variant < 0.30 { return Biome::Meadow; }
            Biome::Plains
        }
    }
    // Surface + subsurface block for a biome at surface height `h` (before the sea/beach override).
    fn surface_blocks(&self, biome: Biome, h: usize, wx: f64, wz: f64) -> (Id, Id) {
        use Biome::*;
        match biome {
            Desert => (6, 38),                       // sand / sandstone
            Badlands => (36, 37),                    // red sand / red sandstone
            SnowyPlains | SnowySlopes | Grove => (11, 2), // snow / dirt
            SnowyTaiga => (11, 2),
            IceSpikes => (11, 42),                   // snow / packed ice
            FrozenPeaks => (42, 42),                 // packed ice
            JaggedPeaks => if h > 124 { (11, 1) } else { (1, 1) }, // snow cap / bare rock
            StonyPeaks => (1, 1),
            WindsweptHills => (3, 1),                // grass over stone
            MushroomFields => (41, 2),               // mycelium / dirt
            Swamp | MangroveSwamp => (45, 45),       // mud
            Taiga => {
                // Patches of podzol/coarse dirt among the grass.
                let n = self.perlin_detail.get([wx * 0.09, wz * 0.09]);
                if n > 0.35 { (39, 2) } else if n < -0.5 { (40, 40) } else { (3, 2) }
            }
            _ => (3, 2),                             // grass / dirt (plains, forests, savanna, jungle)
        }
    }
    // Plant a simple tree: a trunk of `log` topped with a `leaf` canopy. Shared by all wooded biomes.
    fn plant_tree(&self, chunk: &mut Chunk, tree: Tree) {
        let Tree { dx, dz, h, log, leaf, trunk_h } = tree;
        for ty in 1..=trunk_h {
            let y = h + ty;
            if y < CHUNK_HEIGHT { chunk.set_block(dx, y, dz, log); }
        }
        let top_y = h + trunk_h;
        for ly in 0..2 {
            let y = top_y + ly;
            if y >= CHUNK_HEIGHT { continue; }
            for lx in -2i32..=2 { for lz in -2i32..=2 {
                if lx.abs() == 2 && lz.abs() == 2 { continue; }
                let x = dx as i32 + lx;
                let z = dz as i32 + lz;
                if !(0..16).contains(&x) || !(0..16).contains(&z) { continue; }
                if ly == 0 && lx == 0 && lz == 0 { continue; }
                if chunk.get_block(x as usize, y, z as usize) == 0 {
                    chunk.set_block(x as usize, y, z as usize, leaf);
                }
            }}
        }
        let y = top_y + 2;
        if y < CHUNK_HEIGHT && chunk.get_block(dx, y, dz) == 0 { chunk.set_block(dx, y, dz, leaf); }
    }
    pub fn height_at(&self, wx: f64, wz: f64) -> i32 {
        let h1 = self.perlin_height.get([wx * 0.007, wz * 0.007]) * 28.0;
        let h2 = self.perlin_height.get([wx * 0.015, wz * 0.015]) * 10.0;
        let detail = self.perlin_detail.get([wx * 0.05, wz * 0.05]) * 3.0;
        let biome = self.perlin_biome.get([wx * 0.002, wz * 0.002]);
        let hill_factor = (biome * 0.5 + 0.5).powf(1.8) * 34.0;
        let base = 62.0 + h1 + h2 + detail + hill_factor;
        base.clamp(12.0, 128.0) as i32
    }
    pub fn cave_at(&self, x: f64, y: f64, z: f64) -> bool {
        if !(6.0..=112.0).contains(&y) { return false; }
        // Spaghetti tunnels: two independent noise fields both near zero carve winding 1D tubes
        // (Perlin-worm approximation), giving long connected passages instead of isolated blobs.
        let n1 = self.perlin_cave.get([x * 0.021 + 11.0, y * 0.030, z * 0.021]).abs();
        let n2 = self.perlin_cave.get([x * 0.021 - 7.0, y * 0.030 + 50.0, z * 0.021]).abs();
        let width = 0.055 + 0.03 * (self.perlin_detail.get([x * 0.01, z * 0.01]) * 0.5 + 0.5);
        if n1 < width && n2 < width { return true; }
        // Cheese caverns: larger open rooms, concentrated in the lower half of the world.
        let cheese = self.perlin_cave.get([x * 0.045, y * 0.055, z * 0.045]);
        let depth_bias = ((60.0 - y) / 55.0).clamp(0.0, 1.0); // more/bigger caverns deeper
        cheese > (0.70 - 0.16 * depth_bias)
    }
    // The block that fills a solid underground cell: an ore/variant vein if one passes here,
    // otherwise deepslate at depth or plain stone.
    fn stone_at(&self, veins: &[Vein], wx: f64, y: i32, wz: f64, base: Id) -> Id {
        for &(id, off, scale, thresh, y0, y1) in veins {
            if y < y0 || y > y1 { continue; }
            if self.perlin_ore.get([wx * scale + off, y as f64 * scale, wz * scale - off]) > thresh { return id; }
        }
        base
    }
    // Deepslate replaces stone below DEEPSLATE_Y, with a noisy band so the boundary isn't a flat line.
    fn deep_base(&self, wx: f64, y: i32, wz: f64) -> Id {
        let jitter = self.perlin_detail.get([wx * 0.06, wz * 0.06]) * 5.0;
        if (y as f64) < DEEPSLATE_Y as f64 + jitter { 57 } else { 1 }
    }
    // Underground biome for cave decoration, from depth + humidity.
    fn cave_kind(&self, wx: f64, wz: f64, y: i32) -> u8 {
        if y < 20 { return 0; } // deep dark
        let humid = self.perlin_biome.get([wx * 0.0015 + 137.0, wz * 0.0015 - 91.0]);
        if humid > 0.15 { 1 } else { 2 } // 1 = lush, 2 = dripstone
    }
    // Ocean temperature class: 0 frozen, 1 cold, 2 lukewarm, 3 warm.
    fn ocean_kind(&self, wx: f64, wz: f64) -> u8 {
        let t = (self.perlin_biome.get([wx * 0.0015, wz * 0.0015]) as f32 * 0.5 + 0.5).clamp(0.0, 1.0);
        if t < 0.28 { 0 } else if t < 0.5 { 1 } else if t < 0.72 { 2 } else { 3 }
    }
    pub fn fill_chunk(&self, chunk: &mut Chunk) {
        match self.dim {
            1 => self.fill_nether(chunk),
            2 => self.fill_end(chunk),
            _ => self.fill_overworld(chunk),
        }
        chunk.generated = true;
        chunk.mesh_dirty = true;
    }

    // Nether: netherrack shell riddled with large 3D-noise caverns, a lava sea in the low y range,
    // glowstone clusters on ceilings, and magma patches. No sky (solid roof + floor).
    fn fill_nether(&self, chunk: &mut Chunk) {
        let (ox, oz) = chunk.pos.world_origin();
        const ROOF: i32 = 120;
        const LAVA: i32 = 31;
        for dz in 0..CHUNK_SIZE { for dx in 0..CHUNK_SIZE {
            let wx = (ox + dx as i32) as f64;
            let wz = (oz + dz as i32) as f64;
            for y in 0..=ROOF {
                let yy = y as usize;
                // Solid bedrock cap at floor/roof.
                if y <= 2 || y >= ROOF - 2 { chunk.set_block(dx, yy, dz, 13); continue; }
                // Carve big caverns with 3D noise; solid netherrack elsewhere.
                let n = self.perlin_cave.get([wx * 0.035, y as f64 * 0.045, wz * 0.035])
                    + 0.5 * self.perlin_detail.get([wx * 0.07, y as f64 * 0.09, wz * 0.07]);
                if n > 0.15 {
                    if y <= LAVA { chunk.set_block(dx, yy, dz, 84); } // lava sea in the open low area
                } else {
                    // Magma near the lava line, netherrack elsewhere.
                    let id = if y <= LAVA + 1 && n > 0.02 { 76 } else {
                        self.stone_at(&NETHER_VEINS, wx, y, wz, 32)
                    };
                    chunk.set_block(dx, yy, dz, id);
                }
            }
            // Glowstone clusters hanging from the ceiling.
            let g = self.perlin_biome.get([wx * 0.08, wz * 0.08]);
            if g > 0.72 {
                for k in 0..3 { let y = (ROOF - 3 - k) as usize; if chunk.get_block(dx, y, dz) == 32 { chunk.set_block(dx, y, dz, 77); } }
            }
        }}
        // Nether-brick fortress section (~1/60 chunks), sitting on a platform above the lava sea.
        let fh = hash3(chunk.pos.0, chunk.pos.1, self.seed ^ 0x0F02417E);
        if fh % 60 == 0 {
            let base = LAVA as usize + 6 + (fh % 20) as usize;
            build_nether_fortress(chunk, 4, 4, base, fh);
        }
    }

    // End: floating end-stone islands in a void (no floor). A guaranteed central island near origin.
    fn fill_end(&self, chunk: &mut Chunk) {
        let (ox, oz) = chunk.pos.world_origin();
        for dz in 0..CHUNK_SIZE { for dx in 0..CHUNK_SIZE {
            let wx = (ox + dx as i32) as f64;
            let wz = (oz + dz as i32) as f64;
            let dist = (wx * wx + wz * wz).sqrt();
            for y in 40..90 {
                // Island mass: 3D noise thresholded, biased to a slab around y=64; central island guaranteed.
                let slab = 1.0 - ((y as f64 - 64.0).abs() / 22.0);
                let n = self.perlin_cave.get([wx * 0.02, y as f64 * 0.03, wz * 0.02]) + slab * 0.6;
                let central = dist < 34.0 && (y as f64 - 62.0).abs() < 6.0;
                if n > 0.55 || central { chunk.set_block(dx, y as usize, dz, 85); }
            }
        }}
        // Obsidian pillars on the central island.
        if ox.abs() < 16 && oz.abs() < 16 {
            for &(px, pz) in &[(2i32, 2i32), (12, 3), (4, 12), (11, 11)] {
                if (0..16).contains(&px) && (0..16).contains(&pz) {
                    for y in 63..70 { chunk.set_block(px as usize, y, pz as usize, 78); }
                }
            }
        }
        // End City (~1/40 outer chunks): a purpur tower rising from the island top, away from origin
        // so it never clashes with the dragon-fight central island.
        let dist0 = ((ox + 8) as f64).hypot((oz + 8) as f64);
        let ch = hash3(chunk.pos.0, chunk.pos.1, self.seed ^ 0x0E7DC17E);
        if dist0 > 80.0 && ch % 40 == 0 {
            // Find the island surface at local (6,6): topmost end-stone in the column.
            let mut top = None;
            for y in (40..90).rev() { if chunk.get_block(6, y, 6) == 85 { top = Some(y); break; } }
            if let Some(sy) = top { build_end_city(chunk, 6, 6, sy + 1, ch); }
        }
    }
}