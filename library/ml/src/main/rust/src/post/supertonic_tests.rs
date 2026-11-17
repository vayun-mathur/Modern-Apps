//! Host tests for Supertonic's text front end (`post/supertonic.rs`):
//! `normalise`, `to_ids` and the small host tensors. All expectations are
//! hand-derived from the SDK behaviour the module documents.

use super::*;
use crate::nets::supertonic_sampler as sampler;

/// An indexer with only `<`, `>`, `/`, `e`, `n`, `a`, `.` and space mapped.
fn indexer() -> Vec<u8> {
    let mut bytes = vec![0xffu8; INDEXER_ENTRIES * 2];
    let mut set = |c: char, token: i16| {
        let at = c as usize * 2;
        bytes[at..at + 2].copy_from_slice(&token.to_le_bytes());
    };
    set('<', 0);
    set('>', 1);
    set('e', 2);
    set('n', 3);
    set('/', 4);
    set('a', 5);
    set('.', 6);
    set(' ', 7);
    bytes
}

#[test]
fn normalise_strips_emoji_and_unifies_punctuation() {
    assert_eq!(normalise("hi \u{1F600} there"), "hi there");
    assert_eq!(normalise("\u{201C}hi\u{201D}"), "\"hi\"");
    assert_eq!(normalise("a\u{2014}b"), "a-b");
    assert_eq!(normalise("one @ two"), "one at two");
    assert_eq!(normalise("x, e.g., y"), "x, for example, y");
    assert_eq!(normalise("a  ,  b"), "a, b", "space before punctuation goes");
    assert_eq!(normalise("say \"\"hi\"\""), "say \"hi\"", "quote runs collapse");
    assert_eq!(normalise("  spaced   out  "), "spaced out");
    // No trailing period here: `to_ids` owns that decision.
    assert_eq!(normalise("no end mark"), "no end mark");
}

#[test]
fn to_ids_wraps_the_text_in_its_language_tag_and_ends_the_sentence() {
    let table = indexer();
    // "<en>" = 0,2,3,1; "a" = 5; added "." = 6; "</en>" = 0,4,2,3,1.
    assert_eq!(
        to_ids(&table, "a", "en").expect("ids"),
        vec![0, 2, 3, 1, 5, 6, 0, 4, 2, 3, 1]
    );
    // A sentence already ending in terminal punctuation gets no extra period.
    assert_eq!(
        to_ids(&table, "a.", "en").expect("ids"),
        vec![0, 2, 3, 1, 5, 6, 0, 4, 2, 3, 1]
    );
    // Unmapped characters are dropped, not substituted.
    assert_eq!(
        to_ids(&table, "aza", "en").expect("ids"),
        vec![0, 2, 3, 1, 5, 5, 6, 0, 4, 2, 3, 1]
    );
}

#[test]
fn to_ids_refuses_unreadable_text_and_bad_arguments() {
    let table = indexer();
    assert!(to_ids(&table, "\u{1F600}", "en").is_err(), "nothing maps");
    assert!(to_ids(&table[..100], "a", "en").is_err(), "short indexer");
    assert!(to_ids(&table, "a", "EN").is_err(), "uppercase language");
    assert!(to_ids(&table, "a", "e").is_err(), "one-letter language");
    assert!(to_ids(&table, "a", "e1").is_err(), "non-letter language");
}

#[test]
fn rotary_angles_are_normalised_by_the_sequence_length() {
    let theta = vec![0.0f32; sampler::FREQUENCIES as usize];
    let table = rotary_angles(&theta, 4).expect("a table");
    assert_eq!(table.len(), 2 * sampler::FREQUENCIES as usize * 4);
    assert!(table.iter().take(32 * 4).all(|&v| v == 1.0), "cos(0) everywhere");
    assert!(table.iter().skip(32 * 4).all(|&v| v == 0.0), "sin(0) everywhere");

    let mut theta = vec![0.0f32; sampler::FREQUENCIES as usize];
    theta[0] = std::f32::consts::PI;
    let table = rotary_angles(&theta, 4).expect("a table");
    // Frequency 0, position 2 of 4: angle = (2/4) * PI = PI/2.
    assert!((table[2] - 0.0).abs() < 1e-6, "cos(pi/2), got {}", table[2]);
    assert!((table[32 * 4 + 2] - 1.0).abs() < 1e-6, "sin(pi/2)");

    assert!(rotary_angles(&theta, 0).is_err(), "no positions");
    assert!(rotary_angles(&theta[..4], 4).is_err(), "wrong frequency count");
}

#[test]
fn unconditional_text_broadcasts_the_token_and_rejects_bad_shapes() {
    let token: Vec<f32> = (0..sampler::TEXT).map(|i| i as f32).collect();
    let out = unconditional_text(&token, 3).expect("broadcast");
    assert_eq!(out.len(), sampler::TEXT as usize * 3);
    // Channel-major: channel c repeats at every position before c + 1 starts.
    assert_eq!(&out[..3], &[0.0, 0.0, 0.0]);
    assert_eq!(&out[3..6], &[1.0, 1.0, 1.0]);
    assert!(unconditional_text(&token, 0).is_err(), "no characters");
    assert!(unconditional_text(&token[..4], 3).is_err(), "short token");
}
