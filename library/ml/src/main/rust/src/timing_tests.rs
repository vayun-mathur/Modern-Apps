//! Timing tests: off by default in a plain test process, and the
//! emit path (a log line) is callable without the switch.

use super::{emit, enabled};

#[test]
fn timing_is_off_unless_asked_for_and_emit_still_works() {
    assert!(!enabled(), "no MODELRUNNER_TIMING in the test process");
    emit("modelrunner timing test line");
}
