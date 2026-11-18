//! Knob lookup tests: the environment source, on a name no other
//! test in the process consults.

use super::{get, is_set};

#[test]
fn a_knob_reads_its_environment_variable_and_reports_absence() {
    std::env::remove_var("MODELRUNNER_COVERAGEPROBE");
    assert_eq!(get("coverageprobe"), None);
    assert!(!is_set("coverageprobe"));
    std::env::set_var("MODELRUNNER_COVERAGEPROBE", "yes");
    assert_eq!(get("coverageprobe"), Some("yes".to_string()));
    assert!(is_set("coverageprobe"));
    std::env::remove_var("MODELRUNNER_COVERAGEPROBE");
    assert_eq!(get("coverageprobe"), None);
}
