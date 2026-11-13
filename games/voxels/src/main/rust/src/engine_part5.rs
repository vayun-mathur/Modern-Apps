#[cfg(test)]
mod tests {
    use super::*;
    use crate::world::block::{FACE_EAST, FACE_NORTH, FACE_SOUTH, FACE_WEST};

    // Yaw 0 looks north, and yaw grows counter-clockwise (see Player::forward). A stair's low side
    // must end up facing the player so that walking forward climbs it.
    #[test]
    fn stairs_face_back_toward_the_player() {
        use std::f32::consts::FRAC_PI_2;
        assert_eq!(stair_facing(0.0), FACE_SOUTH, "looking north, approach from the south");
        assert_eq!(stair_facing(FRAC_PI_2), FACE_EAST, "looking west, approach from the east");
        assert_eq!(stair_facing(FRAC_PI_2 * 2.0), FACE_NORTH);
        assert_eq!(stair_facing(FRAC_PI_2 * 3.0), FACE_WEST);
        // Wraps cleanly, and snaps from in-between angles.
        assert_eq!(stair_facing(FRAC_PI_2 * 4.0), FACE_SOUTH);
        assert_eq!(stair_facing(-FRAC_PI_2), FACE_WEST);
        assert_eq!(stair_facing(0.3), FACE_SOUTH, "a small tilt still reads as north");
    }

    // A fresh world must open in daylight, and the night has to be long enough to matter but short
    // enough to wait out on a phone.
    #[test]
    fn the_day_starts_at_noon_and_night_is_half_the_cycle() {
        assert!((day_t_at(0.0) - 0.5).abs() < 1e-4, "a fresh world opens at midday");
        assert!(!is_night_at(day_t_at(0.0)));
        // Quarter-cycle steps walk noon -> dusk -> midnight -> dawn -> noon.
        let q = DAY_CYCLE * 0.25;
        assert!(is_night_at(day_t_at(q * 1.5)), "dusk has fallen a cycle-eighth after sunset");
        assert!(is_night_at(day_t_at(q * 2.0)), "midnight");
        assert!(!is_night_at(day_t_at(q * 4.0)), "back to noon a full day later");

        let steps = 2000;
        let nights = (0..steps).filter(|i| is_night_at(day_t_at(DAY_CYCLE * *i as f32 / steps as f32))).count();
        let fraction = nights as f32 / steps as f32;
        assert!((fraction - 0.5).abs() < 0.01, "sun-below-horizon is half the cycle, got {fraction}");
        let night_secs = DAY_CYCLE * fraction;
        assert!((240.0..600.0).contains(&night_secs), "night lasts {night_secs}s, outside the playable range");
    }

    // world_secs is saved as-is and wrapped on load and as it advances, so a save taken at some time
    // of day reopens at that same time of day. The wrap must be a whole number of days or it would
    // silently shift the clock.
    #[test]
    fn a_saved_clock_resumes_at_the_same_time_of_day() {
        for elapsed in [0.0f32, 37.5, DAY_CYCLE * 0.3, DAY_CYCLE * 1.7, WORLD_SECS_WRAP - 1.0, WORLD_SECS_WRAP + 123.0] {
            let resumed = day_t_at(wrap_world_secs(elapsed));
            let d = (day_t_at(elapsed) - resumed).abs();
            assert!(!(1e-3..=0.999).contains(&d), "{elapsed}s round-tripped to a different phase");
        }
        assert!((WORLD_SECS_WRAP / DAY_CYCLE).fract() < 1e-6, "the world_secs wrap must be a whole number of days");
        assert_eq!(wrap_world_secs(f32::NAN), 0.0);
        assert_eq!(wrap_world_secs(-5.0), WORLD_SECS_WRAP - 5.0);
    }

    // Resting always moves the clock forward to the same point in the morning, never backwards and
    // never by more than a day.
    #[test]
    fn resting_always_skips_forward_to_dawn() {
        for t in [0.0f32, 0.1, 0.24, 0.26, 0.28, 0.5, 0.76, 0.99] {
            let skip = secs_until_dawn(t);
            assert!(skip >= 0.0, "the clock went backwards from {t}");
            assert!(skip <= DAY_CYCLE + 1e-3, "skipping {skip}s from {t} is more than a day");
            let landed = (t + skip / DAY_CYCLE) % 1.0;
            assert!((landed - DAWN).abs() < 1e-3 || (landed - DAWN).abs() > 0.999, "{t} landed at {landed}");
            assert!(!is_night_at(DAWN), "dawn must not itself count as night");
        }
        // Resting at dawn costs a whole day rather than doing nothing surprising.
        assert!((secs_until_dawn(DAWN) - 0.0).abs() < 1e-3 || secs_until_dawn(DAWN) >= DAY_CYCLE - 1e-3);
    }

    // A dig site has to be worth digging: every roll gives a real item, most of them modest.
    #[test]
    fn every_buried_find_is_a_real_item() {
        let mut diamonds = 0;
        let n = 10_000;
        for i in 0..n {
            let id = buried_find(i as f32 / n as f32);
            assert!(id != 0, "an empty dig site");
            assert!(id <= crate::world::block::MAX_BLOCK_ID || crate::item::is_item(id), "{id} is not an id");
            if id == 1051 { diamonds += 1; }
        }
        let rate = diamonds as f32 / n as f32;
        assert!(rate > 0.0 && rate < 0.06, "diamonds turn up {rate} of the time, which is not a treasure");
    }

    // The weather is a random walk, so what matters is that it can't wander somewhere invalid and that
    // clear skies stay the common case rather than the game raining most of the time.
    #[test]
    fn weather_stays_mostly_clear_and_never_leaves_its_three_states() {
        let mut w = WEATHER_CLEAR;
        let mut counts = [0usize; 3];
        let mut storm_from = [0usize; 3];
        let steps = 20_000;
        let mut s = 0x1234_5678u32;
        for _ in 0..steps {
            s ^= s << 13; s ^= s >> 17; s ^= s << 5;
            let r = (s >> 8) as f32 / 16_777_216.0;
            let next = next_weather(w, r);
            assert!(next <= WEATHER_STORM, "weather wandered to {next}");
            if next == WEATHER_STORM { storm_from[w as usize] += 1; }
            w = next;
            counts[w as usize] += 1;
        }
        let clear = counts[WEATHER_CLEAR as usize] as f32 / steps as f32;
        assert!(clear > 0.5, "it rains too much: clear only {clear} of the time");
        assert!(counts[WEATHER_RAIN as usize] > 0 && counts[WEATHER_STORM as usize] > 0, "some weather never happens");
        assert_eq!(storm_from[WEATHER_CLEAR as usize], 0, "a storm must build through rain, not out of sunshine");
    }

    #[test]
    fn rain_intensity_lines_up_with_the_spawn_rule() {
        assert_eq!(rain_target(WEATHER_CLEAR), 0.0);
        assert!(rain_target(WEATHER_RAIN) < rain_target(WEATHER_STORM));
        assert!(rain_target(WEATHER_STORM) <= 1.0);
        // Rain is heavy enough to bring hostiles out; a clear sky never is.
        assert!(rain_target(WEATHER_RAIN) >= RAIN_SPAWN_THRESHOLD);
        assert!(rain_target(WEATHER_CLEAR) < RAIN_SPAWN_THRESHOLD);
        // A corrupt saved value reads as clear rather than as a permanent storm.
        assert_eq!(rain_target(200), 0.0);
        for r in [0.0f32, 0.5, 1.0, -3.0, 7.0] {
            let d = weather_duration(r);
            assert!((WEATHER_MIN_SECS..=WEATHER_MAX_SECS).contains(&d), "duration {d} out of range");
        }
    }
}
