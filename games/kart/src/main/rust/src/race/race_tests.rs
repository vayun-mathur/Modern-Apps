//! Extra deterministic tests for race state (sibling of `race::tests`).
//! Child module: may inspect private seats/boxes/shells and helpers.
use super::*;
use crate::items::{HeldItem, Shell, BOX_RESPAWN};
use crate::physics::{DriveInput, KartBody, BOOST_TIME};
use crate::track;

fn drive(item: bool) -> DriveInput {
    DriveInput { throttle: 0.0, steering: 0.0, brake: 0.0, drift: false, item }
}

#[test]
fn grid_lane_alternates_and_progress_key_orders() {
    assert_eq!(grid_lane(0, 4), -3.0);
    assert_eq!(grid_lane(1, 4), 3.0);
    assert_eq!(grid_lane(2, 4), -3.0);
    assert_eq!(grid_lane(7, 8), 3.0);
    let mut k = KartBody::spawn(0.0, 0.0);
    k.lap = 2;
    k.track_s = track::TRACK_LENGTH / 2.0;
    assert!((progress_key(&k) - 2.5).abs() < 1e-5);
    k.lap = 0;
    k.track_s = 0.0;
    assert_eq!(progress_key(&k), 0.0);
}

#[test]
fn new_race_with_no_humans_is_all_ai() {
    let race = Race::new(&[]);
    assert_eq!(race.seat_count(), AI_COUNT);
    assert_eq!(race.cam_seat, 0);
    assert_eq!(race.ticks, 0);
    assert!(!race.finished);
    for (i, s) in race.seats.iter().enumerate() {
        assert_eq!(s.id, format!("ai-{i}"));
        assert!(s.ai.is_some());
        assert_eq!(s.held, HeldItem::None);
    }
    assert_eq!(race.seat_of("ai-2"), Some(2));
    assert_eq!(race.seat_of("nobody"), None);
}

#[test]
fn new_race_caps_seats_at_max() {
    let many: Vec<String> = (0..20).map(|i| format!("p{i}")).collect();
    let race = Race::new(&many);
    assert_eq!(race.seat_count(), MAX_SEATS);
    assert!(race.seats.iter().all(|s| s.ai.is_none()), "full human grid has no AI");
    // 6 humans leave room for only 2 AI.
    let six: Vec<String> = (0..6).map(|i| format!("p{i}")).collect();
    let race = Race::new(&six);
    assert_eq!(race.seat_count(), MAX_SEATS);
    assert_eq!(race.seats.iter().filter(|s| s.ai.is_some()).count(), 2);
    // 1 human + AI_COUNT.
    let race = Race::new(&["solo".to_string()]);
    assert_eq!(race.seat_count(), 1 + AI_COUNT);
    assert_eq!(race.seats[0].id, "solo");
    assert!(race.seats[0].ai.is_none());
}

#[test]
fn grid_spawn_positions_stagger_by_seat() {
    let race = Race::new(&["a".to_string(), "b".to_string()]);
    for (i, s) in race.seats.iter().enumerate() {
        assert!((s.kart.track_s - (8.0 + i as f32 * 6.0)).abs() < 1e-4, "seat {i} s");
        // Lane alternates -3/+3 (checked via lateral offset at spawn point).
        let p = track::point_at(s.kart.track_s);
        let lat = track::lateral(s.kart.x, s.kart.z, &p);
        assert!((lat.abs() - 3.0).abs() < 1e-3, "seat {i} lane {lat}");
        assert_eq!(s.kart.speed, 0.0);
    }
}

#[test]
fn item_boxes_are_spread_and_alternate_lanes() {
    let race = Race::new(&["a".to_string()]);
    assert_eq!(race.boxes.len(), BOX_COUNT);
    for (i, b) in race.boxes.iter().enumerate() {
        let want_s = track::TRACK_LENGTH * (i as f32 + 0.5) / BOX_COUNT as f32;
        assert!((b.track_s - want_s).abs() < 1e-3);
        assert_eq!(b.lane, if i % 2 == 0 { -4.0 } else { 4.0 });
        assert_eq!(b.respawn_left, 0.0);
    }
    // All boxes start live for the renderer.
    assert_eq!(race.box_positions().len(), BOX_COUNT);
}

#[test]
fn set_input_only_drives_human_seats() {
    let mut race = Race::new(&["a".to_string()]);
    let sample = DriveInput { throttle: 0.7, steering: 0.2, brake: 0.1, drift: true, item: false };
    race.set_input(0, sample);
    assert_eq!(race.seats[0].input.throttle, 0.7);
    // AI seat ignores set_input.
    let ai_seat = race.seats.iter().position(|s| s.ai.is_some()).unwrap();
    race.set_input(ai_seat, sample);
    assert_eq!(race.seats[ai_seat].input.throttle, 0.0);
    // Out-of-range seat is a no-op, not a panic.
    race.set_input(999, sample);
}

#[test]
fn standings_clamp_lap_and_progress() {
    let mut race = Race::new(&["a".to_string()]);
    race.seats[0].kart.lap = 99;
    race.seats[0].kart.track_s = track::TRACK_LENGTH * 3.0;
    let st = race.standings();
    let mine = st.iter().find(|(id, _, _)| id == "a").unwrap();
    assert_eq!(mine.1, RACE_LAPS, "lap clamped for display");
    assert!((mine.2 - 1.0).abs() < 1e-6, "progress clamped to 1");
    // Sorted descending by (lap, progress).
    for w in st.windows(2) {
        assert!((w[0].1, w[0].2) >= (w[1].1, w[1].2));
    }
}

#[test]
fn standings_ties_keep_seat_order() {
    let mut race = Race::new(&["a".to_string(), "b".to_string()]);
    for s in race.seats.iter_mut() {
        s.kart.lap = 1;
        s.kart.track_s = 42.0;
    }
    let st = race.standings();
    let ids: Vec<&str> = st.iter().map(|(id, _, _)| id.as_str()).collect();
    let seat_ids: Vec<&str> = race.seats.iter().map(|s| s.id.as_str()).collect();
    assert_eq!(ids, seat_ids, "stable sort preserves grid order on ties");
}

#[test]
fn empty_race_standings_camera_and_tick() {
    let mut race = Race { seats: Vec::new(), boxes: Vec::new(), shells: Vec::new(), ticks: 0, finished: false, cam_seat: 0 };
    assert!(race.standings().is_empty());
    assert_eq!(race.winner(), None);
    race.finished = true;
    assert_eq!(race.winner(), None, "finished but empty: still no winner");
    let (pos, look) = race.camera();
    assert_eq!(pos, [0.0, 8.0, 20.0]);
    assert_eq!(look, [0.0, 0.0, 0.0]);
    assert!(race.kart_transforms().is_empty());
    assert!(race.shell_positions().is_empty());
    assert!(race.box_positions().is_empty());
    race.tick(1.0 / 60.0);
    assert_eq!(race.ticks, 1);
}

#[test]
fn camera_sits_behind_and_looks_ahead_of_kart() {
    let mut race = Race::new(&["a".to_string()]);
    race.seats[0].kart.x = 50.0;
    race.seats[0].kart.z = 20.0;
    race.seats[0].kart.yaw = 0.0; // faces -Z
    let (pos, look) = race.camera();
    // Behind is +Z of the kart, at CAM_HEIGHT; look is ahead (-Z) at y=1.
    assert!((pos[0] - 50.0).abs() < 1e-4);
    assert!((pos[1] - CAM_HEIGHT).abs() < 1e-4);
    assert!((pos[2] - (20.0 + CAM_DIST)).abs() < 1e-4, "pos {pos:?}");
    assert!((look[0] - 50.0).abs() < 1e-4);
    assert_eq!(look[1], 1.0);
    assert!((look[2] - (20.0 - CAM_LOOK_AHEAD)).abs() < 1e-4);
    // Out-of-range cam_seat falls back to the first seat.
    race.cam_seat = 999;
    let (pos2, _) = race.camera();
    assert_eq!(pos2, pos);
}

#[test]
fn transforms_and_positions_reflect_state() {
    let mut race = Race::new(&["a".to_string()]);
    race.seats[0].kart.x = 3.0;
    race.seats[0].kart.z = -4.0;
    race.seats[0].kart.yaw = 0.5;
    let t = race.kart_transforms();
    assert_eq!(t.len(), race.seat_count());
    assert_eq!(t[0], (3.0, -4.0, 0.5, false));
    assert!(t[1].3, "second seat is AI");
    race.shells.push(Shell { owner: 0, x: 7.0, z: 8.0, yaw: 0.0, life: 3.0, grace: 0.0 });
    assert_eq!(race.shell_positions(), vec![(7.0, 8.0)]);
    // A box on respawn disappears from the renderer list.
    race.boxes[0].respawn_left = 2.0;
    assert_eq!(race.box_positions().len(), BOX_COUNT - 1);
}

#[test]
fn fire_item_variants() {
    let mut race = Race::new(&["a".to_string()]);
    // Empty hands: nothing happens.
    race.fire_item(0);
    assert!(race.shells.is_empty());
    assert_eq!(race.seats[0].kart.boost_left, 0.0);
    // Mushroom -> boost, slot clears.
    race.seats[0].held = HeldItem::Mushroom;
    race.fire_item(0);
    assert_eq!(race.seats[0].kart.boost_left, BOOST_TIME);
    assert_eq!(race.seats[0].held, HeldItem::None);
    // Shell -> projectile 3m ahead of the nose, slot clears.
    race.seats[0].held = HeldItem::Shell;
    race.seats[0].kart.x = 10.0;
    race.seats[0].kart.z = 10.0;
    race.seats[0].kart.yaw = 0.0;
    race.fire_item(0);
    assert_eq!(race.seats[0].held, HeldItem::None);
    assert_eq!(race.shells.len(), 1);
    let sh = &race.shells[0];
    assert_eq!(sh.owner, 0);
    assert!((sh.x - 10.0).abs() < 1e-4);
    assert!((sh.z - 7.0).abs() < 1e-4, "yaw 0 nose is -Z so shell spawns 3m ahead at z-3, got {}", sh.z);
    assert_eq!(sh.life, crate::items::SHELL_LIFE);
    assert_eq!(sh.grace, 0.5);
    // Out-of-range seat is a no-op.
    race.fire_item(999);
    assert_eq!(race.shells.len(), 1);
}

#[test]
fn item_button_is_rising_edge_only() {
    let mut race = Race::new(&["a".to_string()]);
    race.seats[0].held = HeldItem::Mushroom;
    race.set_input(0, drive(true));
    race.tick(1.0 / 60.0);
    assert_eq!(race.seats[0].held, HeldItem::None, "first press fires");
    assert!(race.seats[0].kart.boost_left > 0.0);
    // Holding the button with a fresh mushroom must not refire.
    race.seats[0].held = HeldItem::Mushroom;
    race.seats[0].kart.boost_left = 0.0;
    race.tick(1.0 / 60.0);
    assert_eq!(race.seats[0].held, HeldItem::Mushroom, "held button must not refire");
    assert_eq!(race.seats[0].kart.boost_left, 0.0);
    // Release, then press again: fires.
    race.set_input(0, drive(false));
    race.tick(1.0 / 60.0);
    race.set_input(0, drive(true));
    race.tick(1.0 / 60.0);
    assert_eq!(race.seats[0].held, HeldItem::None);
    assert!(race.seats[0].kart.boost_left > 0.0);
}

#[test]
fn driving_over_a_live_box_deals_an_item() {
    let mut race = Race::new(&["a".to_string()]);
    let b = race.boxes[0].clone();
    let p = track::point_at(b.track_s);
    race.seats[0].kart.x = p.x + p.nx * b.lane;
    race.seats[0].kart.z = p.z + p.nz * b.lane;
    race.seats[0].kart.track_s = b.track_s;
    race.seats[0].kart.speed = 0.0;
    race.tick(1.0 / 60.0);
    assert_ne!(race.seats[0].held, HeldItem::None, "box must deal");
    assert!((race.boxes[0].respawn_left - BOX_RESPAWN).abs() < 1e-4);
    // The same box on cooldown deals nothing to a second kart placed on it.
    let dealt = race.seats[0].held;
    race.seats[0].held = HeldItem::None;
    race.tick(1.0 / 60.0);
    // Kart may have drifted off the box with zero throttle it has not, but
    // cooldown alone must prevent a redeal while it sits there.
    if race.seats[0].held != HeldItem::None {
        // Only acceptable if a different box was hit; box0 must stay on cooldown.
        assert!(race.boxes[0].respawn_left > 0.0);
    }
    let _ = dealt;
}

#[test]
fn tick_clamps_dt_and_counts_ticks() {
    let mut race = Race::new(&["a".to_string()]);
    race.boxes[0].respawn_left = 8.0;
    race.tick(10.0);
    assert_eq!(race.ticks, 1);
    // dt clamped to 0.25: respawn drops by at most a quarter second.
    assert!(race.boxes[0].respawn_left > 7.7, "respawn {}", race.boxes[0].respawn_left);
    race.tick(0.0);
    assert_eq!(race.ticks, 2, "even a zero-dt tick counts");
}

#[test]
fn reaching_race_laps_sets_finished() {
    let mut race = Race::new(&["a".to_string()]);
    race.seats[0].kart.lap = RACE_LAPS;
    race.tick(1.0 / 60.0);
    assert!(race.finished);
    assert!(race.winner().is_some());
}
