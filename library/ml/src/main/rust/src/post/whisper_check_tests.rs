//! Whisper `Decoding::check` tests: a valid generation config and
//! each of its refusals.

use super::{Decoding, PROMPT_TOKENS};

fn valid() -> Decoding {
    Decoding {
        start_of_transcript: 50258,
        end_of_text: 50257,
        transcribe: 50359,
        no_timestamps: 50363,
        max_length: 448,
        languages: vec![50259, 50260],
        suppress: vec![220],
        suppress_at_begin: vec![220],
    }
}

#[test]
fn a_valid_config_is_accepted() {
    assert!(valid().check(51_865).is_ok());
}

#[test]
fn config_refusals_name_the_fault() {
    let mut config = valid();
    config.end_of_text = 51_865;
    assert!(config.check(51_865).expect_err("named id").contains("past the"));

    let mut config = valid();
    config.languages = Vec::new();
    assert!(config.check(51_865).expect_err("no languages").contains("no language tokens"));

    let mut config = valid();
    config.languages = vec![50259, 99_999];
    let err = config.check(51_865).expect_err("language id");
    assert!(err.contains("language token 99999 is past the vocabulary"), "{err}");

    let mut config = valid();
    config.max_length = PROMPT_TOKENS;
    assert!(config.check(51_865).expect_err("no room").contains("leaves nothing"));
}
