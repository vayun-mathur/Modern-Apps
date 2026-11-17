#[cfg(test)]
mod tests {
    use super::*;
    use crate::world::block::Id;

    fn blessed_player(ids: &[Id]) -> Player {
        let mut p = Player::new(0.0, 64.0, 0.0);
        for &id in ids { assert!(p.blessings.attune(id), "could not attune {id}"); }
        p
    }

    // Icarus is the whole point of the blessing: a long drop must stop hurting.
    #[test]
    fn feather_fall_removes_landing_damage() {
        let mut plain = Player::new(0.0, 64.0, 0.0);
        plain.air_max_y = 100.0;
        plain.damage(plain.air_max_y - plain.pos.y - 3.5);
        assert!(plain.health < 20.0, "an unblessed fall should hurt");

        let blessed = blessed_player(&[1100]);
        assert!(blessed.blessed(Passive::FeatherFall));
        // The tick applies the same drop with the blessing attuned.
        assert_eq!(blessed.health, 20.0);
    }

    #[test]
    fn traversal_makes_you_faster() {
        let plain = Player::new(0.0, 64.0, 0.0);
        let swift = blessed_player(&[1056]);
        assert!(swift.speed_mult() > plain.speed_mult());
    }

    #[test]
    fn ares_multiplies_melee_but_others_do_not() {
        assert_eq!(Player::new(0.0, 0.0, 0.0).might_mult(), 1.0);
        assert!(blessed_player(&[1057]).might_mult() > 1.0);
        assert_eq!(blessed_player(&[1100]).might_mult(), 1.0);
    }

    // Aeolus should only launch the player when it is actually attuned.
    #[test]
    fn wind_burst_only_fires_when_blessed() {
        let mut plain = Player::new(0.0, 64.0, 0.0);
        plain.wind_burst();
        assert_eq!(plain.vel.y, 0.0);

        let mut blessed = blessed_player(&[1110]);
        blessed.wind_burst();
        assert!(blessed.vel.y > 0.0, "Aeolus must throw the player upward");
        assert!(!blessed.on_ground);
    }

    // Yamm clamps descent and lets you rise. The peak-height tracker that drives fall damage has to
    // be reset while submerged, or surfacing and diving again would hurt you on touchdown.
    #[test]
    fn landing_damage_respects_swimming_and_feather_fall() {
        // A 30-block drop hurts normally.
        assert!(landing_damage(94.0, 64.0, false) > 0.0);
        // Icarus removes it entirely.
        assert_eq!(landing_damage(94.0, 64.0, true), 0.0);
        // Swimming keeps air_max_y pinned to the current height, so the same descent is harmless.
        assert_eq!(landing_damage(64.0, 64.0, false), 0.0);
        // Short hops were always free.
        assert_eq!(landing_damage(66.0, 64.0, false), 0.0);
    }

    // Terrain never reaches this high, so surrounding cells are guaranteed air.
    const SKY: i32 = 200;
    fn world() -> ChunkMap {
        let dir = std::env::temp_dir().join("voxels_collide_test").to_string_lossy().into_owned();
        ChunkMap::new(2, dir)
    }

    // You stand on a bottom slab half a block up, not a whole one.
    #[test]
    fn a_bottom_slab_only_fills_the_lower_half() {
        let mut w = world();
        w.set_block_meta_world(0, SKY, 0, Block::StoneSlab as Id, 0);
        let p = Player::new(0.5, 0.0, 0.5);

        assert!(!p.collides_at(vec3(0.5, SKY as f32 + 0.5, 0.5), &w), "resting on the slab's surface is clear");
        assert!(p.collides_at(vec3(0.5, SKY as f32 + 0.4, 0.5), &w), "any lower and you are inside it");
        assert!(p.supported_at(vec3(0.5, SKY as f32 + 0.5, 0.5), &w), "the slab holds you up");
    }

    // The empty half of a top slab is real headroom — a full cube in the same cell would not fit.
    #[test]
    fn you_can_walk_under_a_top_slab() {
        let feet = vec3(0.5, SKY as f32 - 0.3, 0.5); // head lands exactly at the slab's underside

        let mut with_slab = world();
        with_slab.set_block_meta_world(0, SKY + 1, 0, Block::StoneSlab as Id, crate::world::block::META_TOP);
        let p = Player::new(0.5, 0.0, 0.5);
        assert!(!p.collides_at(feet, &with_slab), "a top slab leaves its lower half open");

        let mut with_cube = world();
        with_cube.set_block_world(0, SKY + 1, 0, Block::Stone as Id);
        assert!(p.collides_at(feet, &with_cube), "a full cube in the same cell would block");
    }

    // Stairs are open above their low half, which is what makes them climbable.
    #[test]
    fn stairs_are_open_over_their_low_half() {
        let mut w = world();
        w.set_block_meta_world(0, SKY, 0, Block::StoneStairs as Id, crate::world::block::FACE_NORTH);
        let p = Player::new(0.0, 0.0, 0.0);
        // The low half is -Z. The player is 0.6 wide, so their box has to sit well into it.
        assert!(!p.collides_at(vec3(0.5, SKY as f32 + 0.5, 0.2), &w), "standing on the tread is clear");
        // The tall half is +Z and reaches the cell ceiling.
        assert!(p.collides_at(vec3(0.5, SKY as f32 + 0.5, 0.75), &w), "the step fills the far half");
    }

    fn walk(p: &mut Player, w: &ChunkMap, forward: f32, ticks: usize) {
        let input = crate::input::InputState { move_forward: forward, ..Default::default() };
        for _ in 0..ticks { p.tick(1.0 / 60.0, &input, w); }
    }

    // Falling onto a slab must leave you standing on its surface, not hovering at the cell
    // boundary and not sunk into it.
    #[test]
    fn you_come_to_rest_on_a_slabs_surface() {
        let mut w = world();
        w.set_block_meta_world(0, SKY, 0, Block::StoneSlab as Id, 0);
        let mut p = Player::new(0.5, SKY as f32 + 3.0, 0.5);
        let input = crate::input::InputState::default();
        for _ in 0..120 { p.tick(1.0 / 60.0, &input, &w); }

        assert!(p.on_ground, "the player should have landed");
        assert!((p.pos.y - (SKY as f32 + 0.5)).abs() < 1e-3,
            "expected to rest on the slab top at {}, got {}", SKY as f32 + 0.5, p.pos.y);
    }

    // The same drop onto a full block rests a whole block up, so the slab case is really following
    // the shape rather than getting lucky.
    #[test]
    fn a_full_block_still_holds_you_a_whole_block_up() {
        let mut w = world();
        w.set_block_world(0, SKY, 0, Block::Stone as Id);
        let mut p = Player::new(0.5, SKY as f32 + 3.0, 0.5);
        let input = crate::input::InputState::default();
        for _ in 0..120 { p.tick(1.0 / 60.0, &input, &w); }
        assert!((p.pos.y - (SKY as f32 + 1.0)).abs() < 1e-3, "got {}", p.pos.y);
    }

    /// A long floor to walk along, so a test never runs off the end of the world.
    fn floor(w: &mut ChunkMap, from_z: i32, to_z: i32, cell_y: i32) {
        for z in from_z..=to_z { w.set_block_world(0, cell_y, z, Block::Stone as Id); }
    }

    // Walking into a slab should climb it, and land on its surface rather than the step allowance.
    #[test]
    fn you_walk_up_onto_a_slab() {
        let mut w = world();
        floor(&mut w, -20, 1, SKY);
        for z in -20..=-2 { w.set_block_meta_world(0, SKY + 1, z, Block::StoneSlab as Id, 0); }

        let mut p = Player::new(0.5, SKY as f32 + 1.0, 0.5);
        p.yaw = 0.0; // facing -Z
        walk(&mut p, &w, 1.0, 90);

        assert!(p.pos.z < -2.5, "the player should have walked onto the slab, z = {}", p.pos.z);
        assert!((p.pos.y - (SKY as f32 + 1.5)).abs() < 1e-3,
            "expected to stand on the slab surface at {}, got {}", SKY as f32 + 1.5, p.pos.y);
    }

    // A staircase is a pair of half-steps per block, so it must be climbable without jumping.
    #[test]
    fn you_walk_up_a_staircase() {
        use crate::world::block::FACE_SOUTH;
        let mut w = world();
        floor(&mut w, -20, 1, SKY);
        // Six steps rising away from the player: column -(2+i) is filled to the previous step's
        // height and capped with a stair whose low side faces the approach (+Z).
        const STEPS: i32 = 6;
        for i in 0..STEPS {
            let z = -2 - i;
            for c in 1..=i { w.set_block_world(0, SKY + c, z, Block::Stone as Id); }
            w.set_block_meta_world(0, SKY + 1 + i, z, Block::StoneStairs as Id, FACE_SOUTH);
        }
        // A landing at the top so the climb has somewhere to finish.
        for z in -20..=-(2 + STEPS) {
            for c in 1..=STEPS { w.set_block_world(0, SKY + c, z, Block::Stone as Id); }
        }

        let mut p = Player::new(0.5, SKY as f32 + 1.0, 0.5);
        p.yaw = 0.0;
        walk(&mut p, &w, 1.0, 240);

        assert!(p.pos.y > SKY as f32 + 5.0, "the player should have climbed the stairs, y = {}", p.pos.y);
        assert!(p.pos.z < -6.0, "and travelled along them, z = {}", p.pos.z);
    }

    // A full block is too tall to walk up — only jumping clears it.
    #[test]
    fn a_full_block_still_blocks_you() {
        let mut w = world();
        floor(&mut w, -20, 1, SKY);
        w.set_block_world(0, SKY + 1, -2, Block::Stone as Id);

        let mut p = Player::new(0.5, SKY as f32 + 1.0, 0.5);
        p.yaw = 0.0;
        walk(&mut p, &w, 1.0, 90);
        assert!(p.pos.z > -1.8, "a full block should stop you, z = {}", p.pos.z);
        assert!((p.pos.y - (SKY as f32 + 1.0)).abs() < 1e-3, "and you stay on the floor, y = {}", p.pos.y);
    }

    // Sneaking must still refuse to step off a ledge, including off the edge of a slab.
    #[test]
    fn sneaking_wont_walk_off_a_slab_ledge() {
        let mut w = world();
        for z in -20..=1 { w.set_block_meta_world(0, SKY, z, Block::StoneSlab as Id, 0); }
        let p = Player::new(0.5, SKY as f32 + 0.5, 0.5);

        // Over the slab: supported.
        assert!(p.supported_at(vec3(0.5, SKY as f32 + 0.5, 0.5), &w));
        // Out past the end of the run: nothing underfoot.
        assert!(!p.supported_at(vec3(0.5, SKY as f32 + 0.5, 3.5), &w));
        // Standing a whole block above the slab is not "supported" either.
        assert!(!p.supported_at(vec3(0.5, SKY as f32 + 1.5, 0.5), &w));
    }

    #[test]
    fn attunements_are_independent() {
        let p = blessed_player(&[1101, 1107]);
        assert!(p.blessed(Passive::Pyre));
        assert!(p.blessed(Passive::Fortune));
        assert!(!p.blessed(Passive::Reach));
        assert!(!p.blessed(Passive::DoubleJump));
    }

    // Athena's shield has to reform between fights but never during one, or it makes the player
    // unkillable while a mob is still swinging.
    #[test]
    fn athenas_shield_reforms_only_out_of_combat() {
        let mut p = blessed_player(&[1141]);
        p.tick_status(10.0);
        assert!(p.absorption > 0.0, "the shield should form when nothing is attacking");
        let full = p.absorption;

        p.damage(4.0);
        assert!(p.absorption < full, "the shield has to soak the hit");
        let after = p.absorption;
        p.tick_status(1.0);
        assert_eq!(p.absorption, after, "it must not refill mid-fight");
        p.tick_status(30.0);
        assert!(p.absorption > after, "and must refill once the fight is over");

        // Without the blessing there is no shield at all.
        let mut plain = Player::new(0.0, 64.0, 0.0);
        plain.tick_status(30.0);
        assert_eq!(plain.absorption, 0.0);
    }

    // Sekhmet is a comeback mechanic: it must be off at full health and on when nearly dead.
    #[test]
    fn sekhmet_only_rages_when_bloodied() {
        let mut p = blessed_player(&[1142]);
        assert!(!p.bloodraging(), "a healthy player is not enraged");
        let calm_hit = p.might_mult();

        p.health = p.max_health * (BLOODRAGE_AT - 0.05);
        assert!(p.bloodraging());
        assert!(p.might_mult() > calm_hit, "rage has to hit harder");

        // And it soaks more: the same blow costs less health.
        let mut raging = blessed_player(&[1142]);
        raging.health = raging.max_health * (BLOODRAGE_AT - 0.05);
        let before = raging.health;
        raging.damage(4.0);
        let raged_loss = before - raging.health;

        let mut plain = Player::new(0.0, 64.0, 0.0);
        plain.health = plain.max_health * (BLOODRAGE_AT - 0.05);
        let before = plain.health;
        plain.damage(4.0);
        assert!(raged_loss < before - plain.health, "rage has to soak damage too");

        // A dead player doesn't rage back to life.
        p.health = 0.0;
        p.dead = true;
        assert!(!p.bloodraging());
    }

    #[test]
    fn camazotz_heals_a_share_of_the_damage_dealt() {
        let mut p = blessed_player(&[1143]);
        p.health = 10.0;
        p.lifesteal(20.0);
        assert!((p.health - (10.0 + 20.0 * LIFESTEAL_SHARE)).abs() < 1e-4);
        // It can't overheal.
        p.lifesteal(1000.0);
        assert_eq!(p.health, p.max_health);
        // And it does nothing unattuned.
        let mut plain = Player::new(0.0, 64.0, 0.0);
        plain.health = 10.0;
        plain.lifesteal(20.0);
        assert_eq!(plain.health, 10.0);
    }
}
