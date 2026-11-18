//! Tier arithmetic tests: which KV-cache context tier a byte budget
//! buys, and the ladder between tiers. Expectations restate
//! `CONTEXT_TIERS` against `BYTES_PER_POSITION` directly.

use super::{next_tier, tier_for, BYTES_PER_POSITION, CONTEXT_TIERS};

fn cost(tier: u32) -> u64 {
    tier as u64 * BYTES_PER_POSITION as u64
}

#[test]
fn a_budget_buys_the_largest_tier_it_fully_fits() {
    // Nothing fits: the smallest tier is the floor, not zero.
    assert_eq!(tier_for(0), CONTEXT_TIERS[0]);
    assert_eq!(tier_for(cost(CONTEXT_TIERS[0]) - 1), CONTEXT_TIERS[0]);
    // An exact fit buys that tier; one byte short falls back.
    assert_eq!(tier_for(cost(2048)), 2048);
    assert_eq!(tier_for(cost(4096) - 1), 2048);
    assert_eq!(tier_for(cost(4096)), 4096);
    // Past the top, the top is what you get.
    assert_eq!(tier_for(u64::MAX), *CONTEXT_TIERS.last().unwrap());
}

#[test]
fn the_next_tier_is_the_first_above_wherever_you_stand() {
    assert_eq!(next_tier(0), Some(CONTEXT_TIERS[0]));
    assert_eq!(next_tier(1024), Some(2048));
    assert_eq!(next_tier(1025), Some(2048));
    assert_eq!(next_tier(8192), Some(16_384));
    assert_eq!(next_tier(16_384), None);
    assert_eq!(next_tier(u32::MAX), None);
}
