impl Block {
    pub fn from_id(id: Id) -> Self {
        if id <= MAX_BLOCK_ID { unsafe { std::mem::transmute::<Id, Self>(id) } } else { Self::Air }
    }
    pub fn id(self) -> Id { self as Id }
    pub fn is_air(self) -> bool { matches!(self, Self::Air) }
    pub fn is_solid(self) -> bool { !matches!(self, Self::Air | Self::Water | Self::Glass | Self::Lava | Self::NetherPortal | Self::EndPortal) && !self.is_crop() }
    pub fn is_transparent(self) -> bool {
        // Slabs and stairs leave part of their cell empty, so they can never hide a neighbour's
        // face wholesale. `occludes_face` decides the per-direction cases.
        self.shape() != Shape::Cube
            || matches!(self, Self::Air | Self::Glass | Self::Leaves | Self::Water | Self::BirchLeaves | Self::SpruceLeaves | Self::DarkOakLeaves | Self::AzaleaLeaves | Self::Lava | Self::NetherPortal | Self::EndPortal)
            || self.is_crop()
    }
    pub fn is_opaque(self) -> bool { !self.is_transparent() }
    /// Crops grow on farmland and are harvested rather than mined.
    pub fn is_crop(self) -> bool { matches!(self, Self::WheatCrop | Self::CarrotCrop | Self::MelonCrop) }
    /// The seed that plants this crop, and what a ripe one yields.
    pub fn crop_seed(self) -> Id {
        match self { Self::WheatCrop => 1146, Self::CarrotCrop => 1031, Self::MelonCrop => 1032, _ => 0 }
    }
    pub fn crop_yield(self) -> Id {
        match self { Self::WheatCrop => 1147, Self::CarrotCrop => 1031, Self::MelonCrop => 1032, _ => 0 }
    }
    /// The crop a given seed plants, if any.
    pub fn crop_from_seed(seed: Id) -> Option<Self> {
        match seed { 1146 => Some(Self::WheatCrop), 1031 => Some(Self::CarrotCrop), 1032 => Some(Self::MelonCrop), _ => None }
    }

    /// Does this block stop light? Distinct from `is_opaque`, which asks whether a neighbour's face
    /// can be culled. A slab fills only half its cell so it can never hide a face, but it is still
    /// cut from solid stone and must cast shadow — the opposite of glass, which fills the whole cell
    /// and lets light straight through.
    pub fn blocks_light(self) -> bool {
        match self.shape() {
            Shape::Cube => self.is_opaque(),
            _ => self.parent().is_opaque(),
        }
    }

    pub fn shape(self) -> Shape {
        match self {
            Self::StoneSlab | Self::CobbleSlab | Self::PlankSlab | Self::BrickSlab
            | Self::SandstoneSlab | Self::DeepslateBrickSlab | Self::NetherBrickSlab | Self::PurpurSlab => Shape::Slab,
            Self::StoneStairs | Self::CobbleStairs | Self::PlankStairs | Self::BrickStairs
            | Self::SandstoneStairs | Self::DeepslateBrickStairs | Self::NetherBrickStairs | Self::PurpurStairs => Shape::Stairs,
            _ => Shape::Cube,
        }
    }

    /// The full-cube material a slab or stair is cut from; its textures are reused verbatim.
    pub fn parent(self) -> Self {
        match self {
            Self::StoneSlab | Self::StoneStairs => Self::Stone,
            Self::CobbleSlab | Self::CobbleStairs => Self::Cobble,
            Self::PlankSlab | Self::PlankStairs => Self::Planks,
            Self::BrickSlab | Self::BrickStairs => Self::Brick,
            Self::SandstoneSlab | Self::SandstoneStairs => Self::Sandstone,
            Self::DeepslateBrickSlab | Self::DeepslateBrickStairs => Self::DeepslateBricks,
            Self::NetherBrickSlab | Self::NetherBrickStairs => Self::NetherBricks,
            Self::PurpurSlab | Self::PurpurStairs => Self::Purpur,
            other => other,
        }
    }

    /// The slab shape for a material, and the stair shape. Used by the stonecutter and by the
    /// "two slabs make a cube" merge at placement.
    pub fn slab_of(self) -> Option<Self> {
        Some(match self {
            Self::Stone => Self::StoneSlab,
            Self::Cobble => Self::CobbleSlab,
            Self::Planks => Self::PlankSlab,
            Self::Brick => Self::BrickSlab,
            Self::Sandstone => Self::SandstoneSlab,
            Self::DeepslateBricks => Self::DeepslateBrickSlab,
            Self::NetherBricks => Self::NetherBrickSlab,
            Self::Purpur => Self::PurpurSlab,
            _ => return None,
        })
    }
    pub fn stairs_of(self) -> Option<Self> {
        Some(match self {
            Self::Stone => Self::StoneStairs,
            Self::Cobble => Self::CobbleStairs,
            Self::Planks => Self::PlankStairs,
            Self::Brick => Self::BrickStairs,
            Self::Sandstone => Self::SandstoneStairs,
            Self::DeepslateBricks => Self::DeepslateBrickStairs,
            Self::NetherBricks => Self::NetherBrickStairs,
            Self::Purpur => Self::PurpurStairs,
            _ => return None,
        })
    }

    /// The boxes this block fills, in cell-local 0..1 coordinates.
    pub fn collision_boxes(self, meta: u8) -> Boxes {
        if !self.is_solid() { return Boxes::none(); }
        let top = meta & META_TOP != 0;
        match self.shape() {
            Shape::Cube => Boxes::one(FULL_CUBE),
            Shape::Slab => Boxes::one(if top {
                Aabb::new([0.0, 0.5, 0.0], [1.0, 1.0, 1.0])
            } else {
                Aabb::new([0.0, 0.0, 0.0], [1.0, 0.5, 1.0])
            }),
            Shape::Stairs => {
                // A stair is a half-height slab plus a quarter block on the side opposite `facing`,
                // so you climb it walking against the way it faces.
                let (base, step_y) = if top {
                    (Aabb::new([0.0, 0.5, 0.0], [1.0, 1.0, 1.0]), [0.0, 0.5])
                } else {
                    (Aabb::new([0.0, 0.0, 0.0], [1.0, 0.5, 1.0]), [0.5, 1.0])
                };
                let step = match meta & META_FACING {
                    FACE_NORTH => Aabb::new([0.0, step_y[0], 0.5], [1.0, step_y[1], 1.0]),
                    FACE_EAST => Aabb::new([0.0, step_y[0], 0.0], [0.5, step_y[1], 1.0]),
                    FACE_SOUTH => Aabb::new([0.0, step_y[0], 0.0], [1.0, step_y[1], 0.5]),
                    _ => Aabb::new([0.5, step_y[0], 0.0], [1.0, step_y[1], 1.0]),
                };
                Boxes::two(base, step)
            }
        }
    }

    /// Whether this block completely hides a neighbour's face in direction (dx, dy, dz), pointing
    /// out of this block. Only a fully covered face may be culled; a half-covered one would leave
    /// a hole in the world.
    pub fn occludes_face(self, meta: u8, dx: i32, dy: i32, dz: i32) -> bool {
        let top = meta & META_TOP != 0;
        match self.shape() {
            Shape::Cube => self.is_opaque(),
            // A slab seals only the face its solid half rests against.
            Shape::Slab => if top { dy == 1 } else { dy == -1 },
            Shape::Stairs => {
                if if top { dy == 1 } else { dy == -1 } { return true; }
                // The tall side is opposite `facing`, and there the block spans the full face.
                match meta & META_FACING {
                    FACE_NORTH => dz == 1,
                    FACE_EAST => dx == -1,
                    FACE_SOUTH => dz == -1,
                    _ => dx == 1,
                }
            }
        }
    }

    // Interactive block menu: 0 = none, 1 = crafting, 2 = furnace, 3 = jukebox, 4 = blast furnace,
    // 5 = stonecutter.
    pub fn menu(self) -> i32 {
        match self { Self::CraftingTable => 1, Self::Furnace => 2, Self::Jukebox => 3, Self::BlastFurnace => 4, Self::Stonecutter => 5, _ => 0 }
    }

    // Atlas tile indices (8x8 atlas). See the atlas generator's TILES order.
    pub fn tile_top(self) -> u32 {
        match self {
            Self::Air => 0,
            Self::Stone => 0,
            Self::Dirt => 1,
            Self::Grass => 2,
            Self::Wood => 5,
            Self::Leaves => 6,
            Self::Sand => 7,
            Self::Glass => 12,
            Self::Cobble => 8,
            Self::Brick => 9,
            Self::Planks => 10,
            Self::Snow => 11,
            Self::Water => 13,
            Self::Bedrock => 14,
            Self::Gravel => 17,
            Self::MossyCobble => 18,
            Self::Diorite => 19,
            Self::PolishedDiorite => 20,
            Self::CoalOre => 21,
            Self::IronOre => 22,
            Self::DiamondOre => 23,
            Self::RedstoneOre => 24,
            Self::EmeraldOre => 25,
            Self::Iron => 26,
            Self::Diamond => 27,
            Self::Emerald => 28,
            Self::BirchLog => 30,
            Self::BirchPlanks => 31,
            Self::BirchLeaves => 32,
            Self::SpruceLog => 34,
            Self::SprucePlanks => 35,
            Self::SpruceLeaves => 36,
            Self::Netherrack => 37,
            Self::Bookshelf => 10,
            Self::CraftingTable => 39,
            Self::Furnace => 42,
            Self::RedSand => 64,
            Self::RedSandstone => 66,
            Self::Sandstone => 68,
            Self::Podzol => 70,
            Self::CoarseDirt => 72,
            Self::Mycelium => 73,
            Self::PackedIce => 74,
            Self::Ice => 75,
            Self::BlueIce => 76,
            Self::Mud => 77,
            Self::RootedDirt => 78,
            Self::DarkOakLog => 80,
            Self::DarkOakLeaves => 81,
            Self::DarkOakPlanks => 82,
            Self::AcaciaLog => 84,
            Self::JungleLog => 86,
            Self::JunglePlanks => 87,
            Self::GraniteBricks => 88,
            Self::DeepslateBricks => 89,
            Self::NetherBricks => 90,
            Self::EndStoneBricks => 91,
            Self::CobbledDeepslate => 92,
            Self::Hay => 94,
            Self::Farmland => 95,
            Self::PackedDirt => 96,
            Self::TubeCoral => 97,
            Self::BrainCoral => 98,
            Self::BubbleCoral => 99,
            Self::FireCoral => 100,
            Self::HornCoral => 101,
            Self::Kelp => 102,
            Self::SeaLantern => 103,
            Self::Prismarine => 104,
            Self::DarkPrismarine => 105,
            Self::Dripstone => 106,
            Self::Moss => 107,
            Self::Sculk => 108,
            Self::Amethyst => 109,
            Self::Calcite => 110,
            Self::Tuff => 111,
            Self::Magma => 112,
            Self::Glowstone => 113,
            Self::Obsidian => 114,
            Self::Clay => 115,
            Self::AzaleaLeaves => 116,
            Self::WardingStone => 117,
            Self::Jukebox => 118,
            Self::Chest => 119,
            Self::Lava => 120,
            Self::EndStone => 121,
            Self::NetherPortal => 122,
            Self::EndPortal => 123,
            Self::Beacon => 124,
            Self::Purpur => 125,
            Self::SilverOre => 128,
            Self::SulfurOre => 129,
            Self::CinnabarOre => 130,
            Self::Silver => 131,
            Self::Steel => 132,
            Self::Adamant => 133,
            Self::BlastFurnace => 134,
            Self::CopperOre => 136,
            Self::GoldOre => 137,
            Self::Copper => 138,
            Self::Gold => 139,
            Self::Bronze => 140,
            Self::Stonecutter => 141,
            Self::Wool => 143,
            Self::SuspiciousSand => 144,
            Self::WheatCrop => 145,
            Self::CarrotCrop => 146,
            Self::MelonCrop => 147,
            // Slabs and stairs are textured entirely from their parent material.
            Self::StoneSlab | Self::StoneStairs => Self::Stone.tile_top(),
            Self::CobbleSlab | Self::CobbleStairs => Self::Cobble.tile_top(),
            Self::PlankSlab | Self::PlankStairs => Self::Planks.tile_top(),
            Self::BrickSlab | Self::BrickStairs => Self::Brick.tile_top(),
            Self::SandstoneSlab | Self::SandstoneStairs => Self::Sandstone.tile_top(),
            Self::DeepslateBrickSlab | Self::DeepslateBrickStairs => Self::DeepslateBricks.tile_top(),
            Self::NetherBrickSlab | Self::NetherBrickStairs => Self::NetherBricks.tile_top(),
            Self::PurpurSlab | Self::PurpurStairs => Self::Purpur.tile_top(),
        }
    }
    pub fn tile_bottom(self) -> u32 {
        match self {
            Self::Grass => 1,
            Self::Wood => 5,
            Self::BirchLog => 30,
            Self::SpruceLog => 34,
            Self::Bookshelf => 10,
            Self::CraftingTable => 10,
            Self::Furnace => 43,
            Self::BlastFurnace => 135,
            Self::Sandstone => 69,
            Self::Podzol => 1,
            Self::Mycelium => 1,
            Self::Farmland => 1,
            _ => self.tile_top(),
        }
    }
    pub fn tile_side(self) -> u32 {
        match self {
            Self::Grass => 3,
            Self::Wood => 4,
            Self::BirchLog => 29,
            Self::SpruceLog => 33,
            Self::Bookshelf => 38,
            Self::CraftingTable => 40,
            Self::Furnace => 43,
            Self::RedSandstone => 65,
            Self::Sandstone => 67,
            Self::Podzol => 71,
            Self::DarkOakLog => 79,
            Self::AcaciaLog => 83,
            Self::JungleLog => 85,
            Self::Hay => 93,
            Self::Farmland => 1,
            Self::BlastFurnace => 135,
            Self::Stonecutter => 142,
            _ => self.tile_top(),
        }
    }
    // Slabs and stairs draw with their parent's faces; the geometry, not the texture, is what makes
    // them a different block.
    pub fn tile_for_dir(self, _dx: i32, dy: i32, _dz: i32) -> u32 {
        let m = self.parent();
        if dy == 1 { m.tile_top() } else if dy == -1 { m.tile_bottom() } else { m.tile_side() }
    }
    pub fn color(self) -> [f32; 3] {
        match self {
            Self::Leaves => [0.42, 0.72, 0.33],
            Self::BirchLeaves => [0.55, 0.72, 0.35],
            Self::SpruceLeaves => [0.32, 0.55, 0.34],
            Self::DarkOakLeaves => [0.27, 0.45, 0.20],
            Self::Water => [0.6, 0.8, 1.0],
            _ => [1.0, 1.0, 1.0],
        }
    }
    // Stone/mineral blocks that only drop when mined with a pickaxe.
    pub fn needs_pickaxe(self) -> bool {
        matches!(self,
            Self::Stone | Self::Cobble | Self::Brick | Self::MossyCobble | Self::Diorite | Self::PolishedDiorite
            | Self::CoalOre | Self::IronOre | Self::DiamondOre | Self::RedstoneOre | Self::EmeraldOre
            | Self::Iron | Self::Diamond | Self::Emerald | Self::Netherrack | Self::Furnace
            | Self::RedSandstone | Self::Sandstone | Self::GraniteBricks | Self::DeepslateBricks | Self::NetherBricks
            | Self::EndStoneBricks | Self::CobbledDeepslate | Self::Prismarine | Self::DarkPrismarine | Self::Dripstone
            | Self::Amethyst | Self::Calcite | Self::Tuff | Self::Magma | Self::Obsidian | Self::PackedIce | Self::BlueIce | Self::Purpur
            | Self::SilverOre | Self::SulfurOre | Self::CinnabarOre | Self::Silver | Self::Steel | Self::Adamant | Self::BlastFurnace
            | Self::CopperOre | Self::GoldOre | Self::Copper | Self::Gold | Self::Bronze
            | Self::Stonecutter
            | Self::StoneSlab | Self::StoneStairs | Self::CobbleSlab | Self::CobbleStairs
            | Self::BrickSlab | Self::BrickStairs | Self::SandstoneSlab | Self::SandstoneStairs
            | Self::DeepslateBrickSlab | Self::DeepslateBrickStairs
            | Self::NetherBrickSlab | Self::NetherBrickStairs | Self::PurpurSlab | Self::PurpurStairs)
    }
    // Block light emitted (0..15) for dynamic lighting.
    pub fn light_emission(self) -> u8 {
        match self {
            Self::Glowstone | Self::SeaLantern | Self::Lava | Self::Beacon => 15,
            Self::WardingStone => 13,
            Self::NetherPortal | Self::EndPortal => 12,
            Self::Magma => 11,
            _ => 0,
        }
    }
}

// Atlas is 16 tiles wide x 16 tall (256x256). Tiles 0..15 = base blocks, 16 = grass side overlay,
// 17..63 = the original extra blocks, 64.. = the biome/expansion blocks. GRASS_SIDE_TILE is a shader
// sentinel (dirt + tinted overlay composite).
pub const ATLAS_COLS: f32 = 16.0;
pub const ATLAS_ROWS: f32 = 16.0;
pub const GRASS_SIDE_OVERLAY: u32 = 16;
// Shader sentinel (dirt + tinted overlay composite). Kept far above any real tile index so the
// atlas can use tiles up to ~999.
pub const GRASS_SIDE_TILE: u32 = 1000;
pub fn tile_uv(tile_index: u32) -> (f32,f32,f32,f32) {
    let tx = (tile_index % 16) as f32;
    let ty = (tile_index / 16) as f32;
    let sx = 1.0 / ATLAS_COLS;
    let sy = 1.0 / ATLAS_ROWS;
    (tx*sx, ty*sy, (tx+1.0)*sx, (ty+1.0)*sy)
}
