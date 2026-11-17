// Ported from GrapheneOS NetworkLocation
// (github.com/GrapheneOS/platform_packages_apps_NetworkLocation),
// MIT-licensed, Copyright (c) 2024-2025 GrapheneOS. See LICENSE-GrapheneOS.

//! Documentation for position module.

use crate::coordinate::Coordinate;

/// Position.
#[derive(Clone, Copy, Debug)]
pub struct Position {
    /// x coordinate
    pub x: Coordinate,
    /// y coordinate
    pub y: Coordinate,
    /// z coordinate
    pub z: Coordinate,
}

impl Default for Position {
    fn default() -> Self {
        Self {
            x: Coordinate::new_fake(),
            y: Coordinate::new_fake(),
            z: Coordinate::new_fake(),
        }
    }
}

impl Position {
    /// Gets the average position.
    pub fn average(positions: &[Position]) -> Self {
        if positions.is_empty() {
            return Self::default();
        }
        let positions_len = positions.len() as f64;

        Position {
            x: Coordinate::new_real(
                positions.iter().map(|p| p.x.value).sum::<f64>() / positions_len,
                positions.iter().map(|p| p.x.six_sigma_squared).sum::<f64>() / positions_len,
            ),
            y: Coordinate::new_real(
                positions.iter().map(|p| p.y.value).sum::<f64>() / positions_len,
                positions.iter().map(|p| p.y.six_sigma_squared).sum::<f64>() / positions_len,
            ),
            z: Coordinate::new_real(
                positions.iter().map(|p| p.z.value).sum::<f64>() / positions_len,
                positions.iter().map(|p| p.z.six_sigma_squared).sum::<f64>() / positions_len,
            ),
        }
    }

    /// Whether all coordinates are real.
    pub fn is_all_real(self) -> bool {
        self.x.real && self.y.real && self.z.real
    }

    /// Returns the number of coordinates which are real.
    pub fn number_of_real(self) -> u8 {
        u8::from(self.x.real) + u8::from(self.y.real) + u8::from(self.z.real)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn real(x: f64, y: f64, z: f64, s: f64) -> Position {
        Position {
            x: Coordinate::new_real(x, s),
            y: Coordinate::new_real(y, s),
            z: Coordinate::new_real(z, s),
        }
    }

    #[test]
    fn default_is_all_fake() {
        let p = Position::default();
        assert!(!p.is_all_real());
        assert_eq!(p.number_of_real(), 0);
        assert_eq!((p.x.value, p.y.value, p.z.value), (0.0, 0.0, 0.0));
    }

    #[test]
    fn real_counts() {
        assert_eq!(real(1.0, 2.0, 3.0, 1.0).number_of_real(), 3);
        assert!(real(1.0, 2.0, 3.0, 1.0).is_all_real());
        let mut p = real(1.0, 2.0, 3.0, 1.0);
        p.z = Coordinate::new_fake();
        assert_eq!(p.number_of_real(), 2);
        assert!(!p.is_all_real());
        p.x = Coordinate::new_fake();
        assert_eq!(p.number_of_real(), 1);
    }

    #[test]
    fn average_of_two_positions() {
        let avg = Position::average(&[real(0.0, 10.0, -4.0, 4.0), real(10.0, 20.0, 4.0, 8.0)]);
        assert_eq!((avg.x.value, avg.y.value, avg.z.value), (5.0, 15.0, 0.0));
        assert_eq!(avg.x.six_sigma_squared, 6.0);
        assert!(avg.is_all_real());
    }

    #[test]
    fn average_of_single_is_identity() {
        let p = real(3.0, -7.0, 11.0, 25.0);
        let avg = Position::average(&[p]);
        assert_eq!((avg.x.value, avg.y.value, avg.z.value), (3.0, -7.0, 11.0));
        assert_eq!(avg.y.six_sigma_squared, 25.0);
    }

    #[test]
    fn average_of_empty_is_fake_not_nan() {
        // The mean of no positions must not poison downstream math with NaN;
        // the honest answer is the all-fake default.
        let avg = Position::average(&[]);
        assert!(!avg.is_all_real());
        assert_eq!(avg.number_of_real(), 0);
        assert!(avg.x.value.is_finite() && avg.y.value.is_finite() && avg.z.value.is_finite());
        assert_eq!((avg.x.value, avg.y.value, avg.z.value), (0.0, 0.0, 0.0));
    }
}
