//! The race: seats, laps, items, AI, and the chase camera.
//!
//! The Kotlin loop owns the tick cadence (one `tick_and_render` per frame);
//! this module owns everything inside it. AI seats (`ai-N`) mirror the
//! placeholder's shape (`playerIds` humans first, then AI), so standings JSON
//! reads the same whichever engine backs the race.

use crate::items::{AiDriver, HeldItem, ItemBox, Shell, deal_item, tick_shells};
use crate::physics::{DriveInput, KartBody};
use crate::track;

/// Laps per race. Mirrors the placeholder and the ViewModel's `RACE_LAPS`.
pub const RACE_LAPS: u32 = 3;
/// AI seats per race. Mirrors the placeholder's `aiCount = 3`.
pub const AI_COUNT: usize = 3;
/// Maximum seats (humans + AI). Mirrors the controller protocol's 8.
pub const MAX_SEATS: usize = 8;
/// Item boxes around the loop, alternating lanes.
pub const BOX_COUNT: usize = 6;
/// Chase-cam distance behind the kart in metres.
pub const CAM_DIST: f32 = 9.0;
/// Chase-cam height above the track in metres.
pub const CAM_HEIGHT: f32 = 4.2;
/// Chase-cam look-ahead in front of the kart in metres.
pub const CAM_LOOK_AHEAD: f32 = 7.0;

/// One seat: a human's latest input, or an AI driver.
pub struct Seat {
    /// Seat id: a human player id or `ai-N`.
    pub id: String,
    /// The body on track.
    pub kart: KartBody,
    /// Latest human input (ignored for AI seats).
    pub input: DriveInput,
    /// Item button state last tick: the rising-edge latch for firing.
    pub item_held: bool,
    /// What this seat is holding.
    pub held: HeldItem,
    /// The AI brain, present for `ai-N` seats only.
    pub ai: Option<AiDriver>,
}

/// The whole race. Tick with [`Race::tick`]; read with [`Race::standings`].
pub struct Race {
    seats: Vec<Seat>,
    boxes: Vec<ItemBox>,
    shells: Vec<Shell>,
    /// Ticks since race start (at 60 Hz: 3600 is one minute).
    pub ticks: u64,
    /// True once any kart has finished `RACE_LAPS`.
    pub finished: bool,
    /// Seat index the chase camera follows (the local human, else seat 0).
    pub cam_seat: usize,
}

impl Race {
    /// New race: `human_ids` take the front grid slots, AI fills the rest.
    pub fn new(human_ids: &[String]) -> Self {
        let humans = human_ids.len().min(MAX_SEATS);
        // Up to AI_COUNT rivals round out the grid (never past MAX_SEATS).
        let ai = AI_COUNT.min(MAX_SEATS - humans);
        let mut seats = Vec::with_capacity(humans + ai);
        let grid = (humans + ai).max(1);
        for (i, id) in human_ids.iter().take(humans).enumerate() {
            let lane = grid_lane(i, grid);
            // Grid sits just past the start/finish line on the top straight
            // (s = back): the first wrap then completes one real lap. Behind
            // the line would be the end of the right cap, facing a curve.
            let ahead = 8.0 + (i as f32) * 6.0;
            seats.push(Seat {
                id: id.clone(),
                kart: KartBody::spawn(ahead, lane),
                input: DriveInput::default(),
                item_held: false,
                held: HeldItem::None,
                ai: None,
            });
        }
        for n in 0..ai {
            let i = humans + n;
            let lane = grid_lane(i, grid);
            let ahead = 8.0 + (i as f32) * 6.0;
            seats.push(Seat {
                id: format!("ai-{n}"),
                kart: KartBody::spawn(ahead, lane),
                input: DriveInput::default(),
                item_held: false,
                held: HeldItem::None,
                ai: Some(AiDriver::new(n, ai.max(1))),
            });
        }
        let boxes = (0..BOX_COUNT)
            .map(|i| ItemBox {
                track_s: track::TRACK_LENGTH * (i as f32 + 0.5) / BOX_COUNT as f32,
                lane: if i % 2 == 0 { -4.0 } else { 4.0 },
                respawn_left: 0.0,
            })
            .collect();
        Self {
            seats,
            boxes,
            shells: Vec::new(),
            ticks: 0,
            finished: false,
            cam_seat: 0,
        }
    }

    /// Seat count (humans + AI).
    pub fn seat_count(&self) -> usize {
        self.seats.len()
    }

    /// Feed one human sample. Unknown ids are ignored: a phone that joined
    /// mid-race drives from the next race, never this one.
    pub fn set_input(&mut self, seat: usize, input: DriveInput) {
        if let Some(s) = self.seats.get_mut(seat) {
            if s.ai.is_none() {
                s.input = input;
            }
        }
    }

    /// Index of the seat with this id, if it is racing.
    pub fn seat_of(&self, id: &str) -> Option<usize> {
        self.seats.iter().position(|s| s.id == id)
    }

    /// Advance one tick of `dt` seconds: karts, boxes, shells, laps.
    pub fn tick(&mut self, dt: f32) {
        let dt = dt.clamp(0.0, 0.25);
        self.ticks += 1;
        for b in &mut self.boxes {
            if b.respawn_left > 0.0 {
                b.respawn_left -= dt;
            }
        }
        // Order first: items deal by race position, and AI paces itself off
        // the leader's progress (rubber-banding).
        let mut order: Vec<usize> = (0..self.seats.len()).collect();
        order.sort_by(|&a, &b| {
            progress_key(&self.seats[b].kart)
                .partial_cmp(&progress_key(&self.seats[a].kart))
                .unwrap_or(std::cmp::Ordering::Equal)
        });
        let leader_progress = order
            .first()
            .map(|&si| progress_key(&self.seats[si].kart))
            .unwrap_or(0.0);
        for (pos, &si) in order.iter().enumerate() {
            // Read the seat's state first (immutable borrows end here).
            let seat_count = self.seats.len();
            let (control, should_fire, held_item, kx, kz) = {
                let seat = &mut self.seats[si];
                let mut control = if let Some(ai) = &mut seat.ai {
                    let deficit = leader_progress - progress_key(&seat.kart);
                    let mut c = ai.control(&seat.kart, deficit.max(0.0));
                    // AI fires shells at the kart ahead, mushrooms on straights.
                    if seat.held == HeldItem::Shell && pos > 0 {
                        c.item = true;
                    } else if seat.held == HeldItem::Mushroom {
                        let p = track::point_at(seat.kart.track_s);
                        if track::heading_error(seat.kart.yaw, &p).abs() < 0.1 {
                            c.item = true;
                        }
                    }
                    c
                } else {
                    seat.input
                };
                // Rising edge: holding the button does not fire twice.
                let fire = control.item && !seat.item_held;
                seat.item_held = control.item;
                control.item = false;
                (control, fire, seat.held, seat.kart.x, seat.kart.z)
            };
            // Tick the body (mutable borrow of one seat only).
            {
                let seat = &mut self.seats[si];
                seat.kart.tick(&control, dt);
                if seat.kart.lap >= RACE_LAPS {
                    self.finished = true;
                }
            }
            if should_fire {
                self.fire_item(si);
            }
            // Item boxes: nearest box within radius and off cooldown deals.
            if held_item == HeldItem::None {
                for b in &mut self.boxes {
                    if b.respawn_left > 0.0 {
                        continue;
                    }
                    let p = track::point_at(b.track_s);
                    let bx = p.x + p.nx * b.lane;
                    let bz = p.z + p.nz * b.lane;
                    if (kx - bx).hypot(kz - bz) < crate::items::BOX_RADIUS {
                        self.seats[si].held = deal_item(pos, seat_count);
                        b.respawn_left = crate::items::BOX_RESPAWN;
                        break;
                    }
                }
            }
        }
        let karts: Vec<KartBody> = self.seats.iter().map(|s| s.kart.clone()).collect();
        for hit in tick_shells(&mut self.shells, &karts, dt) {
            if let Some(s) = self.seats.get_mut(hit) {
                s.kart.spin();
            }
        }
    }

    /// Fire seat `si`'s held item (no-op when empty).
    fn fire_item(&mut self, si: usize) {
        let seat = match self.seats.get_mut(si) {
            Some(s) => s,
            None => return,
        };
        match seat.held {
            HeldItem::None => {}
            HeldItem::Mushroom => {
                seat.kart.boost_now();
                seat.held = HeldItem::None;
            }
            HeldItem::Shell => {
                let (x, z, yaw) = (seat.kart.x, seat.kart.z, seat.kart.yaw);
                self.shells.push(Shell {
                    owner: si,
                    x: x + yaw.sin() * 3.0,
                    z: z - yaw.cos() * 3.0,
                    yaw,
                    life: crate::items::SHELL_LIFE,
                    grace: 0.5,
                });
                seat.held = HeldItem::None;
            }
        }
    }

    /// Standings: (seat id, lap, 0..1 lap progress), leaders first.
    pub fn standings(&self) -> Vec<(String, u32, f32)> {
        let mut v: Vec<(String, u32, f32)> = self
            .seats
            .iter()
            .map(|s| {
                let lap = s.kart.lap.min(RACE_LAPS);
                let frac = (s.kart.track_s / track::TRACK_LENGTH).clamp(0.0, 1.0);
                (s.id.clone(), lap, frac)
            })
            .collect();
        v.sort_by(|a, b| {
            (b.1, b.2)
                .partial_cmp(&(a.1, a.2))
                .unwrap_or(std::cmp::Ordering::Equal)
        });
        v
    }

    /// Winner once finished: the first across the line.
    pub fn winner(&self) -> Option<String> {
        if !self.finished {
            return None;
        }
        self.standings().first().map(|(id, _, _)| id.clone())
    }

    /// Chase camera: position and look-at for the followed kart.
    pub fn camera(&self) -> ([f32; 3], [f32; 3]) {
        let seat = self.seats.get(self.cam_seat).or(self.seats.first());
        match seat {
            None => ([0.0, 8.0, 20.0], [0.0, 0.0, 0.0]),
            Some(s) => {
                let (fx, fz) = (s.kart.yaw.sin(), -s.kart.yaw.cos());
                let pos = [
                    s.kart.x - fx * CAM_DIST,
                    CAM_HEIGHT,
                    s.kart.z - fz * CAM_DIST,
                ];
                let look = [
                    s.kart.x + fx * CAM_LOOK_AHEAD,
                    1.0,
                    s.kart.z + fz * CAM_LOOK_AHEAD,
                ];
                (pos, look)
            }
        }
    }

    /// Kart world transforms for the renderer: (x, z, yaw, is_ai).
    pub fn kart_transforms(&self) -> Vec<(f32, f32, f32, bool)> {
        self.seats
            .iter()
            .map(|s| (s.kart.x, s.kart.z, s.kart.yaw, s.ai.is_some()))
            .collect()
    }

    /// Live shell positions for the renderer: (x, z).
    pub fn shell_positions(&self) -> Vec<(f32, f32)> {
        self.shells.iter().map(|s| (s.x, s.z)).collect()
    }

    /// Item-box world positions (only the live ones) for the renderer.
    pub fn box_positions(&self) -> Vec<(f32, f32)> {
        self.boxes
            .iter()
            .filter(|b| b.respawn_left <= 0.0)
            .map(|b| {
                let p = track::point_at(b.track_s);
                (p.x + p.nx * b.lane, p.z + p.nz * b.lane)
            })
            .collect()
    }
}

/// Total progress in laps (fractional) for ordering.
fn progress_key(k: &KartBody) -> f32 {
    k.lap as f32 + k.track_s / track::TRACK_LENGTH
}

/// Grid lane stagger: alternate ±3 m so karts don't spawn stacked.
fn grid_lane(i: usize, _grid: usize) -> f32 {
    if i % 2 == 0 {
        -3.0
    } else {
        3.0
    }
}

#[cfg(test)]
mod race_tests;
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn grid_then_race_then_winner() {
        let ids = vec!["p1".to_string()];
        let mut race = Race::new(&ids);
        assert!(race.seat_count() > ids.len(), "AI should fill the grid");
        let drive = DriveInput {
            throttle: 1.0,
            ..Default::default()
        };
        for _ in 0..60 * 120 {
            race.set_input(0, drive);
            race.tick(1.0 / 60.0);
            if race.finished {
                break;
            }
        }
        assert!(race.finished, "nobody finished 3 laps in 2 minutes");
        assert!(race.winner().is_some());
    }

    #[test]
    fn camera_follows_seat_zero() {
        let race = Race::new(&["p1".to_string()]);
        let (pos, look) = race.camera();
        assert!(pos[1] > 0.0, "camera under the track");
        assert!(
            (pos[0] - look[0]).hypot(pos[2] - look[2]) > CAM_DIST * 0.5,
            "camera on top of the kart"
        );
    }

    #[test]
    fn standings_orders_by_lap_then_progress() {
        let mut race = Race::new(&["p1".to_string()]);
        assert!(race.seat_count() >= 2, "needs a rival to order");
        race.seats[0].kart.lap = 1;
        race.seats[0].kart.track_s = 10.0;
        race.seats[1].kart.lap = 1;
        race.seats[1].kart.track_s = 100.0;
        let order = race.standings();
        assert_eq!(order[0].0, race.seats[1].id);
        race.seats[0].kart.lap = 2;
        let order = race.standings();
        assert_eq!(order[0].0, race.seats[0].id);
    }

    #[test]
    fn winner_needs_finish_flag() {
        let mut race = Race::new(&["p1".to_string()]);
        assert_eq!(race.winner(), None, "no winner before the line");
        race.finished = true;
        assert!(race.winner().is_some(), "leader wins once flagged");
    }
}
