// Ported from GrapheneOS NetworkLocation
// (github.com/GrapheneOS/platform_packages_apps_NetworkLocation),
// MIT-licensed, Copyright (c) 2024-2025 GrapheneOS. See LICENSE-GrapheneOS.
//
// Adaptation note: the upstream Android build depends on AOSP's `liblogger`
// crate for on-device logging. That crate is not available on crates.io, so the
// `logger::init(...)` calls are dropped; the algorithm is otherwise unchanged.

//! Position estimation using robust algorithms.

use std::ops::{Add, Div};

use crate::multilateration::multilateration;
use itertools::Itertools;
use measurement::Measurement;
use position::Position;

pub mod coordinate;
pub mod geocoder;
mod jni;
pub mod measurement;
mod mmap;
pub mod multilateration;
pub mod position;
pub mod wpsdb;

/// Estimated position.
pub struct EstimatedPosition {
    /// estimated position
    position: Position,
    /// inliers size
    inliers_size: usize,
}

struct Candidate {
    inliers_indices: Vec<usize>,
    position: Position,
}

/**
 * Estimate a position using robust methods.
 */
pub fn estimate_position(measurements: &[Measurement]) -> Option<EstimatedPosition> {
    if measurements.is_empty() {
        return None;
    }

    // only applies to measurements used for combos, all measurements
    // are still used when checking for inliers
    let max_measurements_for_combos = measurements.len().min(10);

    let sample_size = measurements.len().min(4);
    let min_inliers = measurements.len().min(4);
    let mut candidate_inliers_indices = Vec::with_capacity(measurements.len());

    let mut candidates: Vec<Candidate> = vec![];

    let mut measurements_indices: Vec<usize> = (0..measurements.len()).collect();
    measurements_indices = measurements_indices
        .iter()
        .sorted_by(|m1, m2| {
            let m1 = measurements[**m1];
            let m1_standard_deviation = (m1.position.x.six_sigma_squared
                + m1.position.y.six_sigma_squared
                + m1.position.z.six_sigma_squared)
                .sqrt();
            let m2 = measurements[**m2];
            let m2_standard_deviation = (m2.position.x.six_sigma_squared
                + m2.position.y.six_sigma_squared
                + m2.position.z.six_sigma_squared)
                .sqrt();

            m1_standard_deviation.total_cmp(&m2_standard_deviation)
        })
        .take(max_measurements_for_combos)
        .cloned()
        .collect();
    let top_measurements_indices = &measurements_indices[0..max_measurements_for_combos];

    for combo in top_measurements_indices.iter().combinations(sample_size) {
        let mut sample: Vec<Measurement> =
            combo.iter().map(|index| measurements[**index]).collect();

        let initial_guess = Position::average(&sample.iter().map(|m| m.position).collect_vec());

        let candidate_position = multilateration(&mut  sample, Some(initial_guess), 50);

        candidate_inliers_indices.clear();
        for (index, measurement) in measurements.iter().enumerate() {
            let dx = candidate_position.x.value - measurement.position.x.value;
            let dy = candidate_position.y.value - measurement.position.y.value;
            let dz = candidate_position.z.value - measurement.position.z.value;

            let estimated_distance = (dx.powi(2) + dy.powi(2) + dz.powi(2)).sqrt();
            let residual = (estimated_distance - measurement.distance).abs();
            let standard_deviation = (measurement.position.x.six_sigma_squared
                + measurement.position.y.six_sigma_squared
                + measurement.position.z.six_sigma_squared)
                .sqrt()
                // Prevent division by zero.
                .max(f64::MIN_POSITIVE);
            let standardized_residual = residual / standard_deviation;
            // within 2 standard deviations
            let threshold = 2.0;

            if standardized_residual <= threshold {
                candidate_inliers_indices.push(index);
            }
        }

        candidates.push(Candidate {
            inliers_indices: candidate_inliers_indices.clone(),
            position: candidate_position,
        });
    }

    let target_inlier_count = measurements_indices.len().div(2).add(1).max(min_inliers);
    let best_candidate = candidates.iter().reduce(|best, candidate| {
        let compare_inlier_count = !(candidate.inliers_indices.len() >= target_inlier_count
            && best.inliers_indices.len() >= target_inlier_count);
        if compare_inlier_count && candidate.inliers_indices.len() > best.inliers_indices.len() {
            candidate
        } else if compare_inlier_count
            && candidate.inliers_indices.len() < best.inliers_indices.len()
        {
            best
        } else if candidate.position.number_of_real() > best.position.number_of_real()
            || (((candidate.position.is_all_real() && best.position.is_all_real())
                || candidate.position.number_of_real() == best.position.number_of_real())
                && candidate.position.x.six_sigma_squared
                    + candidate.position.y.six_sigma_squared
                    + candidate.position.z.six_sigma_squared
                    < best.position.x.six_sigma_squared
                        + best.position.y.six_sigma_squared
                        + best.position.z.six_sigma_squared)
        {
            candidate
        } else {
            best
        }
    });

    if let Some(best_candidate) = best_candidate {
        if best_candidate.inliers_indices.len() >= min_inliers {
            let final_estimated_position = multilateration(
                &mut best_candidate
                    .inliers_indices
                    .iter()
                    .map(|index| measurements[*index])
                    .collect_vec(),
                Some(best_candidate.position),
                1000.div(best_candidate.inliers_indices.len()).max(3),
            );

            Some(EstimatedPosition {
                position: final_estimated_position,
                inliers_size: best_candidate.inliers_indices.len(),
            })
        } else {
            None
        }
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use log::info;
    use rand::rngs::SmallRng;
    use rand::{Rng, SeedableRng};
    use std::ops::Range;

    use crate::coordinate::Coordinate;

    use super::*;

    fn generate_random_measurement(
        x_range: Range<f64>,
        y_range: Range<f64>,
        z_range: Range<f64>,
        real_position: Position,
        random: &mut SmallRng,
    ) -> Measurement {
        let x = random.gen_range(x_range);
        let y = random.gen_range(y_range);
        let z = random.gen_range(z_range);
        let x_six_sigma_squared: f64 = random.gen_range(3000.0..5000.0);
        let y_six_sigma_squared: f64 = random.gen_range(3000.0..5000.0);
        let z_six_sigma_squared: f64 = random.gen_range(50.0..100.0);
        let distance = ((x - real_position.x.value).powi(2)
            + (y - real_position.y.value).powi(2)
            + (z - real_position.z.value).powi(2))
        .sqrt()
            + random.gen_range(10.0..100.0);

        Measurement {
            position: Position {
                x: Coordinate::new_real(x, x_six_sigma_squared),
                y: Coordinate::new_real(y, y_six_sigma_squared),
                z: Coordinate::new_real(z, z_six_sigma_squared),
            },
            distance,
            weight: 0.0,
        }
    }

    /// checks that position estimation fails due to not having enough inliers
    #[test]
    fn not_enough_inliers() {
        let mut results = vec![];
        let iterations = 100;

        for s in 0..iterations {
            let mut random = SmallRng::seed_from_u64(s);

            let real_position = Position {
                x: Coordinate::new_real(random.gen_range(-10000.0..10000.0), 0.0),
                y: Coordinate::new_real(random.gen_range(-10000.0..10000.0), 0.0),
                z: Coordinate::new_real(random.gen_range(-10000.0..10000.0), 0.0),
            };

            let mut measurements = vec![];

            for i in 0..9 {
                let mut measurement = generate_random_measurement(
                    real_position.x.value - random.gen_range(0.0..1000.0)
                        ..real_position.x.value + random.gen_range(0.0..1000.0),
                    real_position.y.value - random.gen_range(0.0..1000.0)
                        ..real_position.y.value + random.gen_range(0.0..1000.0),
                    real_position.z.value - random.gen_range(0.0..1000.0)
                        ..real_position.z.value + random.gen_range(0.0..1000.0),
                    real_position,
                    &mut random,
                );

                // make 6 of them outliers, meaning we only have 3 inliers which is not
                // enough (minimum inliers is currently 4)
                if i < 6 {
                    measurement.position.x.value *= 100.0;
                    measurement.position.y.value *= 100.0;
                    measurement.position.z.value *= 100.0;
                }

                measurements.push(measurement);
            }

            let estimated_position = estimate_position(&measurements);

            results.push((real_position, estimated_position));
        }

        let position_deltas = results.iter().map(|p| {
            p.1.as_ref().map(|result| {
                let result = result.position;
                Position {
                    x: Coordinate::new_real(
                        (p.0.x.value - result.x.value).abs(),
                        result.x.six_sigma_squared,
                    ),
                    y: Coordinate::new_real(
                        (p.0.y.value - result.y.value).abs(),
                        result.y.six_sigma_squared,
                    ),
                    z: Coordinate::new_real(
                        (p.0.z.value - result.z.value).abs(),
                        result.z.six_sigma_squared,
                    ),
                }
            })
        });

        let successful_results_deltas = position_deltas.flatten().collect::<Vec<Position>>();
        let success_results_deltas_count = successful_results_deltas.len();

        let average_delta = Position::average(&successful_results_deltas);

        info!(
            "\nreal position: {:#?},\naverage delta: {:#?},\nratio of success: {}/{iterations}",
            Position::average(&results.iter().map(|p| p.0).collect_vec()),
            average_delta,
            success_results_deltas_count,
        );

        assert_eq!(success_results_deltas_count, 0);
    }

    /// checks that we can estimate a position successfully despite convincing outliers,
    /// as long as the majority of measurements are inliers
    #[test]
    fn majority_wins() {
        let mut results = vec![];
        let iterations = 100;

        for s in 0..iterations {
            let mut random = SmallRng::seed_from_u64(s);

            let real_position = Position {
                x: Coordinate::new_real(random.gen_range(0.0..10000.0), 0.0),
                y: Coordinate::new_real(random.gen_range(0.0..10000.0), 0.0),
                z: Coordinate::new_real(random.gen_range(0.0..10000.0), 0.0),
            };

            let mut measurements = vec![];

            for i in 0..9 {
                let mut measurement = generate_random_measurement(
                    real_position.x.value - random.gen_range(0.0..1000.0)
                        ..real_position.x.value + random.gen_range(0.0..1000.0),
                    real_position.y.value - random.gen_range(0.0..1000.0)
                        ..real_position.y.value + random.gen_range(0.0..1000.0),
                    real_position.z.value - random.gen_range(0.0..1000.0)
                        ..real_position.z.value + random.gen_range(0.0..1000.0),
                    real_position,
                    &mut random,
                );

                // make 4 of them convincing outliers by just inversing their coordinates,
                // meaning they still seem valid
                if i < 4 {
                    measurement.position.x.value *= -1.0;
                    measurement.position.y.value *= -1.0;
                    measurement.position.z.value *= -1.0;
                }

                measurements.push(measurement);
            }

            let estimated_position = estimate_position(&measurements);

            results.push((real_position, estimated_position));
        }

        let position_deltas = results.iter().map(|p| {
            p.1.as_ref().map(|result| {
                let result = result.position;
                Position {
                    x: Coordinate::new_real(
                        (p.0.x.value - result.x.value).abs(),
                        result.x.six_sigma_squared,
                    ),
                    y: Coordinate::new_real(
                        (p.0.y.value - result.y.value).abs(),
                        result.y.six_sigma_squared,
                    ),
                    z: Coordinate::new_real(
                        (p.0.z.value - result.z.value).abs(),
                        result.z.six_sigma_squared,
                    ),
                }
            })
        });

        let successful_results_deltas = position_deltas.flatten().collect::<Vec<Position>>();
        let success_results_deltas_count = successful_results_deltas.len();

        let average_delta = Position::average(&successful_results_deltas);

        info!(
            "\nreal position: {:#?},\naverage delta: {:#?},\nratio of success: {}/{iterations}",
            Position::average(&results.iter().map(|p| p.0).collect_vec()),
            average_delta,
            success_results_deltas_count,
        );

        assert!(average_delta.x.value < 101.0);
        assert!(average_delta.y.value < 100.0);
        assert!(average_delta.z.value < 100.0);

        assert!(average_delta.x.six_sigma_squared < 5000.0);
        assert!(average_delta.y.six_sigma_squared < 5000.0);
        assert!(average_delta.z.six_sigma_squared < 100.0);
    }
}
