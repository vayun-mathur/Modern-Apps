//! Entity helpers: kind tables, mesh building, particles and projectiles.

use super::*;
use glam::Vec3;

#[test]
fn kind_tables_are_coherent() {
    assert_eq!(MobKind::ALL.len(), 13);
    for kind in MobKind::ALL {
        assert_eq!(MobKind::from_cell(kind.cell()), kind);
        assert_eq!(kind.cell() < 13, true);
        // Loot ids are real items/blocks or empty for villagers.
        for &id in kind.loot() { assert!(id != 0); }
        let v = roll_variation(kind, 12345);
        if kind.is_boss() {
            assert_eq!(v.health, kind.max_health());
            assert!(!v.elite);
        }
    }
    assert!(MobKind::Dragon.flies() && MobKind::Ghast.flies() && !MobKind::Pig.flies());
    assert!(MobKind::Dragon.is_boss() && MobKind::Wither.is_boss() && !MobKind::Zombie.is_boss());
    assert!(MobKind::Zombie.hostile() && !MobKind::Cow.hostile());
    assert!(MobKind::Blaze.uniform_skin() && !MobKind::Pig.uniform_skin());
    assert_eq!(MobKind::Shulker.max_health(), 30.0);
    assert_eq!(MobKind::Chicken.max_health(), 4.0);
}

#[test]
fn xorshift_is_deterministic_and_bounded() {
    let mut a = 42u32;
    let mut b = 42u32;
    for _ in 0..100 {
        let (x, y) = (xorshift(&mut a), xorshift(&mut b));
        assert_eq!(x, y);
        assert!((0.0..1.0).contains(&x));
    }
}

#[test]
fn entity_mesh_and_billboards_have_valid_indices() {
    let mobs: Vec<Mob> = MobKind::ALL.iter().map(|&k| Mob::new(k, Vec3::new(0.0, 64.0, 0.0), 9)).collect();
    let (verts, indices) = build_entity_mesh(&mobs);
    assert!(!verts.is_empty() && !indices.is_empty());
    assert_eq!(indices.len() % 3, 0);
    assert!(indices.iter().all(|&i| (i as usize) < verts.len()));
    // 6 faces * 4 verts per part; every kind has at least 3 parts.
    assert!(verts.len() >= mobs.len() * 3 * 24);
    for v in &verts {
        assert!(v.pos.iter().all(|c| c.is_finite()));
        assert!(v.uv.iter().all(|c| (0.0..=1.0).contains(c)));
    }
    // Remote-player avatars reuse the biped mesh.
    let (mut rv, mut ri) = (Vec::new(), Vec::new());
    append_remote_players(&mut rv, &mut ri, &[(Vec3::new(1.0, 64.0, 1.0), 0.5), (Vec3::new(3.0, 64.0, 3.0), -1.0)]);
    assert_eq!(rv.len(), 2 * 6 * 24, "two bipeds, 6 parts each");
    assert!(ri.iter().all(|&i| (i as usize) < rv.len()));
    // Projectiles and particles are one quad each.
    let projs = [ProjKind::Fireball, ProjKind::ShulkerBullet, ProjKind::Firework, ProjKind::Snowball, ProjKind::EnderPearl]
        .iter().map(|&kind| Projectile { pos: Vec3::ONE, vel: Vec3::ZERO, life: 1.0, kind, from_player: false, damage: 1.0, explosive: false }).collect::<Vec<_>>();
    for p in &projs { assert!(p.size() > 0.0); assert!(p.color().iter().all(|c| (0.0..=1.0).contains(c))); }
    assert_eq!(projs[3].gravity(), 14.0);
    assert_eq!(projs[0].gravity(), 0.0);
    let (mut pv, mut pi) = (Vec::new(), Vec::new());
    append_projectiles(&mut pv, &mut pi, &projs, Vec3::X, Vec3::Y);
    assert_eq!(pv.len(), projs.len() * 4);
    let mut parts = vec![Particle { pos: Vec3::ZERO, vel: Vec3::new(0.0, 5.0, 0.0), life: 0.5, max_life: 1.0, size: 0.2, color: [1.0, 0.5, 0.0], gravity: BURST_GRAVITY }];
    append_particles(&mut pv, &mut pi, &parts, Vec3::X, Vec3::Y);
    tick_particles(&mut parts, 0.1);
    assert_eq!(parts.len(), 1);
    assert!(parts[0].pos.y > 0.0 && parts[0].life < 0.5);
    tick_particles(&mut parts, 1.0);
    assert!(parts.is_empty(), "dead particles are retained out");
}

#[test]
fn flying_and_ground_mobs_tick_without_panic() {
    // Flat empty terrain: nothing solid, no surface.
    let terrain = Terrain { solid: &|_, _, _| false, surface: &|_, _, _| None };
    for kind in MobKind::ALL {
        let mut m = Mob::new(kind, Vec3::new(0.0, 90.0, 0.0), 5);
        for _ in 0..120 { m.tick(1.0 / 60.0, Vec3::new(0.0, 90.0, 5.0), &terrain); }
        assert!(m.pos.x.is_finite() && m.pos.y.is_finite() && m.pos.z.is_finite(), "{kind:?} produced non-finite pos");
    }
    // A solid floor through the surface callback only.
    let terrain = Terrain { solid: &|_, y, _| y < 64.0, surface: &|_, _, _| Some(64.0) };
    let mut pig = Mob::new(MobKind::Pig, Vec3::new(0.0, 70.0, 0.0), 5);
    for _ in 0..180 { pig.tick(1.0 / 60.0, Vec3::new(100.0, 64.0, 100.0), &terrain); }
    assert!((pig.pos.y - 64.0).abs() < 1e-3, "pig should rest on the surface, got {}", pig.pos.y);
    assert!(pig.on_ground);
}

#[test]
fn mob_tint_rules() {
    let mut elite = Mob::new(MobKind::Zombie, Vec3::ZERO, 7);
    elite.elite = true;
    assert_eq!(elite.tint(), [1.35, 0.62, 0.55]);
    let plain = Mob::new(MobKind::Pig, Vec3::ZERO, 7);
    assert_eq!(plain.tint(), [1.0, 1.0, 1.0]);
}

#[test]
fn step_target_rules_and_x_axis_step() {
    // Directly: no step while airborne, none onto a full-block rise, none into a blocked head.
    let flat = Terrain { solid: &|_, _, _| false, surface: &|_, _, _| Some(64.5) };
    let mut m = Mob::new(MobKind::Pig, Vec3::new(0.0, 64.0, 0.0), 5);
    assert_eq!(m.step_target(&flat, 1.0, 0.0), None, "airborne mobs do not step");
    m.on_ground = true;
    assert_eq!(m.step_target(&flat, 1.0, 0.0), Some(64.5), "a half rise is steppable");
    let high = Terrain { solid: &|_, _, _| false, surface: &|_, _, _| Some(66.0) };
    assert_eq!(m.step_target(&high, 1.0, 0.0), None, "a two-block rise is not");
    let blocked = Terrain { solid: &|_, y, _| y > 64.6, surface: &|_, _, _| Some(64.5) };
    assert_eq!(m.step_target(&blocked, 1.0, 0.0), None, "no room to stand means no step");
    let none = Terrain { solid: &|_, _, _| false, surface: &|_, _, _| None };
    assert_eq!(m.step_target(&none, 1.0, 0.0), None);
    // And in motion along +X: a zombie chasing east steps onto a slab ledge.
    let mut w = crate::world::ChunkMap::new(3, std::env::temp_dir().join("voxels_mob_x").to_string_lossy().into_owned());
    for x in -3..=20 { for z in -3..=3 { w.set_block_world(x, 200, z, 1); } }
    for z in -3..=3 { w.set_block_meta_world(6, 201, z, crate::world::block::Block::StoneSlab as u16, 0); }
    let terrain = Terrain { solid: &|x, y, z| w.solid_at(x, y, z), surface: &|x, z, c| w.surface_below(x, z, c, 2) };
    let mut z = Mob::new(MobKind::Zombie, Vec3::new(0.5, 201.0, 0.5), 11);
    let player = Vec3::new(15.5, 201.5, 0.5);
    for _ in 0..240 { z.tick(1.0 / 60.0, player, &terrain); }
    assert!(z.pos.x > 6.0, "the zombie must cross the slab ledge eastward, x={}", z.pos.x);
}

#[test]
fn part_constructor_at_runtime() {
    let part = p([8.0, 8.0, 8.0], [0.0, 0.7, -0.4], [28.0, 8.0], 0.5, -1.0);
    assert_eq!(part.size, [8.0, 8.0, 8.0]);
    assert_eq!(part.pos, [0.0, 0.7, -0.4]);
    assert_eq!(part.uv, [28.0, 8.0]);
    assert_eq!(part.x_rot, 0.5);
    assert_eq!(part.leg, -1.0);
    // Every model part across all kinds is a positive box inside the skin cell.
    for kind in MobKind::ALL {
        for part in kind.parts() {
            assert!(part.size.iter().all(|v| *v > 0.0), "{kind:?} has a degenerate part");
        }
        assert!(!kind.parts().is_empty());
    }
}
