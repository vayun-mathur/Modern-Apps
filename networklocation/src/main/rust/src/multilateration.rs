// Ported from GrapheneOS NetworkLocation
// (github.com/GrapheneOS/platform_packages_apps_NetworkLocation),
// MIT-licensed, Copyright (c) 2024-2025 GrapheneOS. See LICENSE-GrapheneOS.

//! Documentation for multilateration module.

use core::f64;
use std::ops::{Div, Sub};

use crate::{measurement::Measurement, position::Position};
use itertools::Itertools;

/**
 * Multilateration using an algorithm based on Expectation-Maximization.
 */
pub fn multilateration(
    measurements: &mut [Measurement],
    initial_guess: Option<Position>,
    iterations: usize,
) -> Position {
    // Also used for six_sigma_squared estimation iterations.
    let mut estimated_position = initial_guess.unwrap_or_default();
    let mut actual_estimated_position = estimated_position;
    let six_sigma_squared_estimation_iterations_per_point = 6;
    // 2 points for each dimension.
    let mut six_sigma_squared_estimation_points = [Position::default(); 6];
    let six_sigma_squared_estimation_iterations = six_sigma_squared_estimation_points.len()
        * six_sigma_squared_estimation_iterations_per_point;
    let mut previous_estimated_position = estimated_position;

    // At least one iteration per phase: with `iterations < 3` this would be 0
    // and `(iterations_per_phase * 3) - 1` underflows below.
    let iterations_per_phase = (iterations / 3).max(1);
    // Make sure we don't have any extra iterations.
    let iterations = (iterations_per_phase * 3) + six_sigma_squared_estimation_iterations;

    for iter_index in 0..iterations {
        let is_last_phases_iter = iter_index == (iterations_per_phase * 3) - 1;

        let only_use_measurements_with_no_latent_variables = iter_index < iterations_per_phase;
        let only_update_position_with_fully_real_measurements =
            (iterations_per_phase..(iterations_per_phase * 2)).contains(&iter_index);
        let can_use_measurement_for_position_update = |measurement: Measurement| {
            measurement.position.is_all_real() || !only_update_position_with_fully_real_measurements
        };
        let is_in_final_six_sigma_squared_estimation =
            (iterations.sub(six_sigma_squared_estimation_iterations)..iterations)
                .contains(&iter_index);
        let learning_rate = if iter_index < iterations - six_sigma_squared_estimation_iterations {
            iter_index as f64 / iterations_per_phase as f64
        } else {
            1.0
        };
        let learning_rate = 1.0 - learning_rate.fract();
        let mut measurements_to_work_on = None;

        let six_sigma_squared_estimation_iter_index =
            iter_index.wrapping_sub(iterations - six_sigma_squared_estimation_iterations);
        let six_sigma_squared_estimation_point_index = six_sigma_squared_estimation_iter_index
            / six_sigma_squared_estimation_iterations_per_point;
        if is_in_final_six_sigma_squared_estimation
            && six_sigma_squared_estimation_iter_index
                % six_sigma_squared_estimation_iterations_per_point
                == 0
        {
            estimated_position = actual_estimated_position;
            match six_sigma_squared_estimation_point_index {
                0 => estimated_position.x.value -= estimated_position.x.six_sigma_squared.sqrt(),
                1 => estimated_position.x.value += estimated_position.x.six_sigma_squared.sqrt(),
                2 => estimated_position.y.value -= estimated_position.y.six_sigma_squared.sqrt(),
                3 => estimated_position.y.value += estimated_position.y.six_sigma_squared.sqrt(),
                4 => estimated_position.z.value -= estimated_position.z.six_sigma_squared.sqrt(),
                5 => estimated_position.z.value += estimated_position.z.six_sigma_squared.sqrt(),
                6.. => panic!(
                    "There shouldn't be more than 6 six_sigma_squared estimation points (2 for each dimension)!"
                ),
            }
        };

        if only_use_measurements_with_no_latent_variables {
            let measurements_with_no_latent_variables = measurements
                .iter_mut()
                .enumerate()
                .filter(|measurement| {
                    let position = measurement.1.position;
                    position.x.real && position.y.real && position.z.real
                })
                .map(|measurement| measurement.0)
                .collect_vec();

            if !measurements_with_no_latent_variables.is_empty() {
                measurements_to_work_on = Some(measurements_with_no_latent_variables);
            }
        }

        let measurements_to_work_on = match measurements_to_work_on {
            None => measurements
                .iter()
                .enumerate()
                .map(|measurement| measurement.0)
                .collect_vec(),
            Some(measurements) => measurements,
        };

        let mut update = Position::default();

        let mut total_weighted_six_sigma_x = 0.0;
        let mut total_weighted_six_sigma_y = 0.0;
        let mut total_weighted_six_sigma_z = 0.0;

        for measurement_index in &measurements_to_work_on {
            let mut measurement = measurements[*measurement_index];

            let mut x_delta = estimated_position.x.value - measurement.position.x.value;
            let mut y_delta = estimated_position.y.value - measurement.position.y.value;
            let mut z_delta = estimated_position.z.value - measurement.position.z.value;

            if x_delta.abs() <= measurement.position.x.six_sigma_squared.sqrt() {
                x_delta = 0.0
            }
            if y_delta.abs() <= measurement.position.y.six_sigma_squared.sqrt() {
                y_delta = 0.0
            }
            if z_delta.abs() <= measurement.position.z.six_sigma_squared.sqrt() {
                z_delta = 0.0
            }

            let x_delta_squared = x_delta.powi(2);
            let y_delta_squared = y_delta.powi(2);
            let z_delta_squared = z_delta.powi(2);

            let space_diagonal_length_squared = x_delta_squared + y_delta_squared + z_delta_squared;
            let estimated_distance = space_diagonal_length_squared.sqrt();

            let is_x_real = measurement.position.x.real;
            let is_y_real = measurement.position.y.real;
            let is_z_real = measurement.position.z.real;

            let x_six_sigma_squared = measurement.position.x.six_sigma_squared;
            let y_six_sigma_squared = measurement.position.y.six_sigma_squared;
            let z_six_sigma_squared = measurement.position.z.six_sigma_squared;

            let measurement_distance_std_dev = (measurement.position.x.six_sigma_squared
                + measurement.position.y.six_sigma_squared
                + measurement.position.z.six_sigma_squared)
                .sqrt();
            measurement.weight = if (measurement.distance - estimated_distance).abs()
                > measurement_distance_std_dev
                && estimated_distance > 0.0
            {
                (measurement.distance
                    - (measurement_distance_std_dev
                        * (measurement.distance - estimated_distance).signum()))
                    / estimated_distance
                    - 1.0
            } else {
                0.0
            };

            let update_x = ((x_delta_squared * measurement.weight).abs().sqrt()
                * (x_delta * measurement.weight).signum())
                * learning_rate;
            let update_y = ((y_delta_squared * measurement.weight).abs().sqrt()
                * (y_delta * measurement.weight).signum())
                * learning_rate;
            let update_z = ((z_delta_squared * measurement.weight).abs().sqrt()
                * (z_delta * measurement.weight).signum())
                * learning_rate;

            if !is_x_real {
                measurement.position.x.value -= update_x;
            }
            if !is_y_real {
                measurement.position.y.value -= update_y;
            }
            if !is_z_real {
                measurement.position.z.value -= update_z;
            }

            if can_use_measurement_for_position_update(measurement) {
                if is_x_real {
                    update.x.value += update_x;
                }
                if is_y_real {
                    update.y.value += update_y;
                }
                if is_z_real {
                    update.z.value += update_z;
                }
            }

            if is_last_phases_iter && can_use_measurement_for_position_update(measurement) {
                let weight = ((estimated_distance
                    + (measurement_distance_std_dev
                        * (measurement.distance - estimated_distance).signum()))
                    - measurement.distance)
                    / measurement_distance_std_dev;
                let weight = weight.max(1.0);
                if is_x_real {
                    total_weighted_six_sigma_x += x_six_sigma_squared.sqrt() * weight;
                }
                if is_y_real {
                    total_weighted_six_sigma_y += y_six_sigma_squared.sqrt() * weight;
                }
                if is_z_real {
                    total_weighted_six_sigma_z += z_six_sigma_squared.sqrt() * weight;
                }
            }
        }

        let real_measurements_to_work_on_lens =
            measurements_to_work_on
                .iter()
                .fold((0.0, 0.0, 0.0), |acc, measurement_index| {
                    let measurement = measurements[*measurement_index];

                    if can_use_measurement_for_position_update(measurement) {
                        (
                            acc.0
                                + if measurement.position.x.real {
                                    1.0
                                } else {
                                    0.0
                                },
                            acc.1
                                + if measurement.position.y.real {
                                    1.0
                                } else {
                                    0.0
                                },
                            acc.2
                                + if measurement.position.z.real {
                                    1.0
                                } else {
                                    0.0
                                },
                        )
                    } else {
                        acc
                    }
                });
        if real_measurements_to_work_on_lens.0 == 0.0 {
            update.x.value = 0.0
        } else {
            update.x.value /= real_measurements_to_work_on_lens.0
        }
        if real_measurements_to_work_on_lens.1 == 0.0 {
            update.y.value = 0.0
        } else {
            update.y.value /= real_measurements_to_work_on_lens.1
        }
        if real_measurements_to_work_on_lens.2 == 0.0 {
            update.z.value = 0.0
        } else {
            update.z.value /= real_measurements_to_work_on_lens.2
        }

        estimated_position.x.value += update.x.value;
        estimated_position.y.value += update.y.value;
        estimated_position.z.value += update.z.value;

        if is_last_phases_iter {
            estimated_position.x.six_sigma_squared = if real_measurements_to_work_on_lens.0 == 0.0 {
                estimated_position.x.real = false;
                0.0
            } else {
                (total_weighted_six_sigma_x / real_measurements_to_work_on_lens.0).powi(2)
            };
            estimated_position.y.six_sigma_squared = if real_measurements_to_work_on_lens.1 == 0.0 {
                estimated_position.y.real = false;
                0.0
            } else {
                (total_weighted_six_sigma_y / real_measurements_to_work_on_lens.1).powi(2)
            };
            estimated_position.z.six_sigma_squared = if real_measurements_to_work_on_lens.2 == 0.0 {
                estimated_position.z.real = false;
                0.0
            } else {
                (total_weighted_six_sigma_z / real_measurements_to_work_on_lens.2).powi(2)
            };

            actual_estimated_position = estimated_position;
        }

        if is_in_final_six_sigma_squared_estimation {
            let mut six_sigma_squared_estimation_point = estimated_position;
            // If even, we use the lowest and if odd we use the highest since even estimated points had
            // lower initial guesses and odd ones had higher guesses. This way, we are protected against
            // oscillating position estimates since we will always choose the one that yields less
            // reported accuracy.
            let most_general_point = |v1: f64, v2: f64| -> f64 {
                if six_sigma_squared_estimation_point_index % 2 == 0 {
                    v1.min(v2)
                } else {
                    v1.max(v2)
                }
            };
            six_sigma_squared_estimation_point.x.value = most_general_point(
                estimated_position.x.value,
                previous_estimated_position.x.value,
            );
            six_sigma_squared_estimation_point.y.value = most_general_point(
                estimated_position.y.value,
                previous_estimated_position.y.value,
            );
            six_sigma_squared_estimation_point.z.value = most_general_point(
                estimated_position.z.value,
                previous_estimated_position.z.value,
            );

            six_sigma_squared_estimation_points[six_sigma_squared_estimation_point_index] =
                six_sigma_squared_estimation_point;

            if iter_index == iterations - 1 {
                let first_x = six_sigma_squared_estimation_points[0].x;
                let second_x = six_sigma_squared_estimation_points[1].x;
                let first_y = six_sigma_squared_estimation_points[2].y;
                let second_y = six_sigma_squared_estimation_points[3].y;
                let first_z = six_sigma_squared_estimation_points[4].z;
                let second_z = six_sigma_squared_estimation_points[5].z;

                let final_x = (first_x.value + second_x.value) / 2.0;
                let final_x_six_sigma_squared = (first_x.value - second_x.value).div(2.0).powi(2);
                let final_y = (first_y.value + second_y.value) / 2.0;
                let final_y_six_sigma_squared = (first_y.value - second_y.value).div(2.0).powi(2);
                let final_z = (first_z.value + second_z.value) / 2.0;
                let final_z_six_sigma_squared = (first_z.value - second_z.value).div(2.0).powi(2);

                actual_estimated_position.x.value = final_x;
                actual_estimated_position.x.six_sigma_squared = final_x_six_sigma_squared;
                actual_estimated_position.y.value = final_y;
                actual_estimated_position.y.six_sigma_squared = final_y_six_sigma_squared;
                actual_estimated_position.z.value = final_z;
                actual_estimated_position.z.six_sigma_squared = final_z_six_sigma_squared;
            }
        }

        previous_estimated_position = estimated_position;
    }

    actual_estimated_position
}

#[cfg(test)]
mod tests {
    use crate::{
        coordinate::Coordinate, measurement::Measurement, multilateration::multilateration,
        position::Position,
    };

    #[test]
    fn basic() {
        let mut measurements = vec![];

        measurements.push(Measurement {
            position: Position {
                x: Coordinate::new_real(1.5, 1.0_f64.powi(2)),
                ..Default::default()
            },
            distance: 4.0,
            weight: 0.0,
        });
        measurements.push(Measurement {
            position: Position {
                x: Coordinate::new_real(12.0, 1.5_f64.powi(2)),
                ..Default::default()
            },
            distance: 5.5,
            weight: 0.0,
        });

        let expected = Position {
            // Most accurate result would be value: 5.75, six_sigma_squared: 0.75_f64.powi(2).
            x: Coordinate::new_real(5.868107855175952, 0.3015669990124259_f64.powi(2)),
            ..Default::default()
        };

        let result = multilateration(&mut measurements, None, 50);

        assert_eq!(expected.x.value, result.x.value);
        assert_eq!(expected.x.six_sigma_squared, result.x.six_sigma_squared);

        let mut measurements = vec![];

        measurements.push(Measurement {
            position: Position {
                x: Coordinate::new_real(9.5, 1.0_f64.powi(2)),
                ..Default::default()
            },
            distance: 4.0,
            weight: 0.0,
        });
        measurements.push(Measurement {
            position: Position {
                x: Coordinate::new_real(12.0, 1.5_f64.powi(2)),
                ..Default::default()
            },
            distance: 5.5,
            weight: 0.0,
        });

        let expected = Position {
            // Most accurate result would be value: 5.75, six_sigma_squared: 0.75_f64.powi(2).
            x: Coordinate::new_real(5.756402373747081, 0.6326516952769938_f64.powi(2)),
            ..Default::default()
        };

        let result = multilateration(&mut measurements, None, 50);

        assert_eq!(expected.x.value, result.x.value);
        assert_eq!(expected.x.six_sigma_squared, result.x.six_sigma_squared)
    }
}

#[cfg(test)]
mod extra_tests {
    use crate::{
        coordinate::Coordinate, measurement::Measurement, multilateration::multilateration,
        position::Position,
    };

    fn at(x: f64, distance: f64) -> Measurement {
        Measurement {
            position: Position {
                x: Coordinate::new_real(x, 1.0),
                ..Default::default()
            },
            distance,
            weight: 0.0,
        }
    }

    #[test]
    fn empty_measurements_return_fake_position_without_panicking() {
        let result = multilateration(&mut [], None, 50);
        assert_eq!(result.number_of_real(), 0);
        assert!(result.x.value.is_finite());
    }

    #[test]
    fn zero_iterations_still_returns_finite_position() {
        let mut m = vec![at(0.0, 1.0), at(10.0, 1.0)];
        let result = multilateration(&mut m, None, 0);
        assert!(result.x.value.is_finite());
        assert!(result.x.six_sigma_squared.is_finite());
    }

    #[test]
    fn explicit_initial_guess_is_respected_when_consistent() {
        // Beacon at x=10 reporting distance 3: positions 7 and 13 both fit;
        // starting from 7 must not be dragged across the beacon.
        let mut m = vec![at(10.0, 3.0)];
        let guess = Position {
            x: Coordinate::new_real(7.0, 1.0),
            ..Default::default()
        };
        let result = multilateration(&mut m, Some(guess), 50);
        assert!((result.x.value - 7.0).abs() < 2.0, "x {}", result.x.value);
    }
}
