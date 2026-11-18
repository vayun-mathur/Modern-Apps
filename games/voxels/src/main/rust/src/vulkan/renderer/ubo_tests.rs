//! smoothstep curve used for sun elevation.

use super::*;

#[test]
fn smoothstep_clamps_and_eases() {
    assert_eq!(smoothstep(0.0, 1.0, -1.0), 0.0);
    assert_eq!(smoothstep(0.0, 1.0, 2.0), 1.0);
    assert_eq!(smoothstep(0.0, 1.0, 0.0), 0.0);
    assert_eq!(smoothstep(0.0, 1.0, 1.0), 1.0);
    assert!((smoothstep(0.0, 1.0, 0.5) - 0.5).abs() < 1e-6);
    // The S-curve is slower than linear at the edges.
    assert!(smoothstep(0.0, 1.0, 0.25) < 0.25);
    assert!(smoothstep(0.0, 1.0, 0.75) > 0.75);
    assert!((smoothstep(-0.10, 0.22, 0.06) - 0.5).abs() < 0.05, "mid sun elevation is mid blend");
}
