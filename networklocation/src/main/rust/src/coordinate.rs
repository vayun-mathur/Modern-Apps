// Ported from GrapheneOS NetworkLocation
// (github.com/GrapheneOS/platform_packages_apps_NetworkLocation),
// MIT-licensed, Copyright (c) 2024-2025 GrapheneOS. See LICENSE-GrapheneOS.

//! Documentation for coordinate module.

/// Coordinate.
#[derive(Clone, Copy, Debug)]
pub struct Coordinate {
    /// whether the value is real (not estimated based on other data)
    pub real: bool,
    /// coordinate value
    pub value: f64,
    /// 6-sigma²
    pub six_sigma_squared: f64,
}

impl Coordinate {
    /// new real coordinate
    pub fn new_real(value: f64, six_sigma_squared: f64) -> Self {
        Coordinate {
            real: true,
            value,
            six_sigma_squared,
        }
    }

    /// new fake coordinate
    pub fn new_fake() -> Self {
        Coordinate {
            real: false,
            value: 0.0,
            six_sigma_squared: 0.0,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_real_keeps_value_and_variance() {
        let c = Coordinate::new_real(-12.5, 36.0);
        assert!(c.real);
        assert_eq!(c.value, -12.5);
        assert_eq!(c.six_sigma_squared, 36.0);
    }

    #[test]
    fn new_fake_is_zeroed_and_not_real() {
        let c = Coordinate::new_fake();
        assert!(!c.real);
        assert_eq!(c.value, 0.0);
        assert_eq!(c.six_sigma_squared, 0.0);
    }

    #[test]
    fn coordinate_is_copy() {
        let c = Coordinate::new_real(1.0, 2.0);
        let copy = c;
        assert_eq!(copy.value, c.value);
        assert!(copy.real);
    }
}
