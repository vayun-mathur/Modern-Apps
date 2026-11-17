use serde::{Deserialize, Serialize};

/// One 16-bit id space for blocks and items, split at `ITEM_BASE`: below it is a block, at or above
/// it is an item. See `item.rs`.
pub type Id = u16;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[repr(u16)]
pub enum Block {
    Air = 0,
    Stone = 1,
    Dirt = 2,
    Grass = 3,
    Wood = 4,
    Leaves = 5,
    Sand = 6,
    Glass = 7,
    Cobble = 8,
    Brick = 9,
    Planks = 10,
    Snow = 11,
    Water = 12,
    Bedrock = 13,
    Gravel = 14,
    MossyCobble = 15,
    Diorite = 16,
    PolishedDiorite = 17,
    CoalOre = 18,
    IronOre = 19,
    DiamondOre = 20,
    RedstoneOre = 21,
    EmeraldOre = 22,
    Iron = 23,
    Diamond = 24,
    Emerald = 25,
    BirchLog = 26,
    BirchPlanks = 27,
    BirchLeaves = 28,
    SpruceLog = 29,
    SprucePlanks = 30,
    SpruceLeaves = 31,
    Netherrack = 32,
    Bookshelf = 33,
    CraftingTable = 34,
    Furnace = 35,
    RedSand = 36,
    RedSandstone = 37,
    Sandstone = 38,
    Podzol = 39,
    CoarseDirt = 40,
    Mycelium = 41,
    PackedIce = 42,
    Ice = 43,
    BlueIce = 44,
    Mud = 45,
    RootedDirt = 46,
    DarkOakLog = 47,
    DarkOakLeaves = 48,
    DarkOakPlanks = 49,
    AcaciaLog = 50,
    JungleLog = 51,
    JunglePlanks = 52,
    GraniteBricks = 53,
    DeepslateBricks = 54,
    NetherBricks = 55,
    EndStoneBricks = 56,
    CobbledDeepslate = 57,
    Hay = 58,
    Farmland = 59,
    PackedDirt = 60,
    TubeCoral = 61,
    BrainCoral = 62,
    BubbleCoral = 63,
    FireCoral = 64,
    HornCoral = 65,
    Kelp = 66,
    SeaLantern = 67,
    Prismarine = 68,
    DarkPrismarine = 69,
    Dripstone = 70,
    Moss = 71,
    Sculk = 72,
    Amethyst = 73,
    Calcite = 74,
    Tuff = 75,
    Magma = 76,
    Glowstone = 77,
    Obsidian = 78,
    Clay = 79,
    AzaleaLeaves = 80,
    WardingStone = 81,
    Jukebox = 82,
    Chest = 83,
    Lava = 84,
    EndStone = 85,
    NetherPortal = 86,
    EndPortal = 87,
    Beacon = 88,
    Purpur = 89,
    // Matcha alloy tier: silver in the overworld, sulfur and cinnabar in the Nether, feeding the
    // steel -> adamant smithing line.
    SilverOre = 90,
    SulfurOre = 91,
    CinnabarOre = 92,
    Silver = 93,
    Steel = 94,
    Adamant = 95,
    BlastFurnace = 96,
    // Copper and gold round out Matcha's metals, feeding the bronze alloy.
    CopperOre = 97,
    GoldOre = 98,
    Copper = 99,
    Gold = 100,
    Bronze = 101,
    // Matcha's building set. Each slab/stair pair borrows its parent material's atlas tiles; the
    // top/bottom half and the stair's facing live in the per-voxel meta byte, not in the id.
    StoneSlab = 102,
    StoneStairs = 103,
    CobbleSlab = 104,
    CobbleStairs = 105,
    PlankSlab = 106,
    PlankStairs = 107,
    BrickSlab = 108,
    BrickStairs = 109,
    SandstoneSlab = 110,
    SandstoneStairs = 111,
    DeepslateBrickSlab = 112,
    DeepslateBrickStairs = 113,
    NetherBrickSlab = 114,
    NetherBrickStairs = 115,
    PurpurSlab = 116,
    PurpurStairs = 117,
    Stonecutter = 118,
    /// Sheared from sheep; the game's first block that can't be mined out of the ground.
    Wool = 119,
    /// Buried finds. Brushing it yields loot and leaves plain sand behind; mining it just gives sand.
    SuspiciousSand = 120,
    // Crops. Growth stage lives in the free meta bits, so each crop costs one block id rather than
    // one per stage; see CROP_STAGE_SHIFT below.
    WheatCrop = 121,
    CarrotCrop = 122,
    MelonCrop = 123,
}

/// Blocks occupy the low 10 bits of the id space; items start where they end. Both sides have room
/// to grow, and there is exactly one boundary to remember.
pub const MAX_BLOCK_ID: Id = 123;

/// Growth stage occupies meta bits 3-4 (bits 0-2 are facing and top-half, used by slabs and stairs).
pub const CROP_STAGE_SHIFT: u8 = 3;
pub const CROP_STAGE_MASK: u8 = 0b1_1000;
pub const CROP_RIPE: u8 = 3;
pub fn crop_stage(meta: u8) -> u8 { (meta & CROP_STAGE_MASK) >> CROP_STAGE_SHIFT }
pub fn crop_meta(stage: u8) -> u8 { (stage.min(CROP_RIPE) << CROP_STAGE_SHIFT) & CROP_STAGE_MASK }

/// Does an id name something a player can actually hold — a real block, or an id in the item
/// window? The data tables are full of bare numbers, and a typo there yields Air in silence.
#[cfg(test)]
pub fn is_real_id(id: Id) -> bool {
    id != 0 && (Block::from_id(id) != Block::Air || crate::item::is_item(id))
}

/// The geometry a block occupies within its cell.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Shape { Cube, Slab, Stairs }

/// Meta bit layout for non-cube blocks: bits 0-1 facing, bit 2 half. Four bits are spare for
/// future shapes (corner stairs).
pub const META_FACING: u8 = 0b11;
pub const META_TOP: u8 = 0b100;
/// Facing values, named for the direction the stair's *low* side looks toward.
pub const FACE_NORTH: u8 = 0; // -Z
pub const FACE_EAST: u8 = 1;  // +X
pub const FACE_SOUTH: u8 = 2; // +Z
pub const FACE_WEST: u8 = 3;  // -X

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Aabb { pub min: [f32; 3], pub max: [f32; 3] }

impl Aabb {
    pub const fn new(min: [f32; 3], max: [f32; 3]) -> Self { Self { min, max } }
    /// Does this box, placed in the cell at `cell`, overlap the world-space box `min`..`max`?
    pub fn overlaps_at(&self, cell: [f32; 3], min: [f32; 3], max: [f32; 3]) -> bool {
        (0..3).all(|i| cell[i] + self.max[i] > min[i] && cell[i] + self.min[i] < max[i])
    }
}

pub const FULL_CUBE: Aabb = Aabb::new([0.0, 0.0, 0.0], [1.0, 1.0, 1.0]);

/// The one or two boxes a block occupies. Returned by value to keep collision allocation-free.
#[derive(Clone, Copy, Debug)]
pub struct Boxes { len: usize, boxes: [Aabb; 2] }
impl Boxes {
    const fn one(a: Aabb) -> Self { Self { len: 1, boxes: [a, a] } }
    const fn two(a: Aabb, b: Aabb) -> Self { Self { len: 2, boxes: [a, b] } }
    const fn none() -> Self { Self { len: 0, boxes: [FULL_CUBE, FULL_CUBE] } }
    pub fn as_slice(&self) -> &[Aabb] { &self.boxes[..self.len] }
}

include!("block_part1.rs");
include!("block_part2.rs");
#[cfg(test)]
#[path = "block_tests.rs"]
mod block_tests;
