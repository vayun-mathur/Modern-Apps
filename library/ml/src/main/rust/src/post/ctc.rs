//! CTC greedy decoding, and the character table it decodes against.
//!
//! # The label space
//!
//! PP-OCRv5's recogniser emits [`LOGITS`] = 838 values per timestep. PaddleOCR's
//! `CTCLabelDecode` builds that list as
//!
//! ```text
//!     index 0        the CTC blank
//!     index 1..=836  the 836 entries of the character dictionary, in file order
//!     index 837      the space, which `use_space_char` appends
//! ```
//!
//! so a label's character is `dictionary[index - 1]` — the off-by-one the plan warned
//! about, and the reason [`Dictionary::label`] exists rather than callers indexing.
//!
//! The dictionary ships as `ppocr_keys.txt`, one character per line, extracted from the
//! export's own `inference.yml` (`PostProcess.character_dict`). **Its order is
//! load-bearing**: the model's final layer emits logits positionally, so a reordered file
//! decodes to fluent-looking nonsense rather than failing. The space is *not* in the file
//! — no entry in it is whitespace, checked at load — so it is appended here, which keeps
//! the asset free of trailing-whitespace hazards that a text editor or a line-ending
//! conversion would silently eat.
//!
//! # Greedy, not beam search
//!
//! `ppocrv5.cpp` takes the argmax per timestep and collapses runs; there is no language
//! model and no beam. This reproduces that.

/// Logits the recogniser emits per timestep: blank + 836 characters + space.
pub const LOGITS: usize = 838;
/// Entries the dictionary file must hold.
pub const DICTIONARY_ENTRIES: usize = 836;

/// The character table, in label order.
#[derive(Debug)]
pub struct Dictionary {
    /// One entry per label from 1 upwards; index 0 (the blank) has no character.
    characters: Vec<String>,
}

impl Dictionary {
    /// Parse `ppocr_keys.txt`: one character per line, and the space appended.
    ///
    /// Rejects a file of the wrong length rather than decoding against a truncated table,
    /// which would shift every character above the missing one.
    pub fn parse(text: &str) -> Result<Dictionary, String> {
        // `lines` handles both endings, which matters because this asset is text in a
        // repo with mixed CRLF and LF.
        let mut characters: Vec<String> = text
            .lines()
            .map(|line| line.trim_end_matches('\r').to_string())
            .filter(|line| !line.is_empty())
            .collect();
        if characters.len() != DICTIONARY_ENTRIES {
            return Err(format!(
                "{} dictionary entries, expected {DICTIONARY_ENTRIES}",
                characters.len()
            ));
        }
        if let Some(bad) = characters.iter().position(|c| c.chars().count() != 1) {
            return Err(format!("dictionary entry {bad} is not a single character"));
        }
        // `use_space_char`, which is what makes the label space 838 rather than 837.
        characters.push(" ".to_string());
        Ok(Dictionary { characters })
    }

    /// The character for `label`, or `None` for the blank and for anything out of range.
    pub fn label(&self, label: usize) -> Option<&str> {
        if label == 0 {
            return None;
        }
        self.characters.get(label - 1).map(String::as_str)
    }

    /// How many labels there are, including the blank. Should equal [`LOGITS`].
    pub fn labels(&self) -> usize {
        self.characters.len() + 1
    }
}

/// One decoded line: its text and the mean confidence of the labels that produced it.
#[derive(Clone, Debug, PartialEq)]
pub struct Decoded {
    /// The text, with repeats collapsed and blanks removed.
    pub text: String,
    /// Mean probability of the kept timesteps, or 0 when nothing was kept.
    pub confidence: f32,
}

/// Collapse `logits` into text: argmax per timestep, drop blanks, drop repeats.
///
/// # Layout: class-major, and raw
///
/// `logits` is [`LOGITS`] * `timesteps` values in **class-major** order — every timestep's
/// value for label 0, then every one for label 1, and so on. That is `[LOGITS, 1, T]`,
/// exactly what [`crate::nets::ppocr_rec`] writes, so nothing transposes anything.
///
/// `used` is how many leading timesteps to read, which is not always all of them.
/// Recognition runs at one fixed width so that a single compiled plan serves every line, so
/// a short crop sits in the left of the input and mid-grey padding fills the rest. Upstream
/// PaddleOCR avoids the question by batching lines of similar aspect ratio, which makes its
/// padding narrow; here it can be most of the strip, and decoding it invites a hallucinated
/// character on the end of every short word.
///
/// The values are **raw logits**, not probabilities. The export ends in a `Softmax` over
/// the 838 classes and this runtime deliberately does not run it, because the decode does
/// not need it:
///
/// * The argmax is unchanged by a softmax, which is monotonic.
/// * The winner's probability is `1 / sum(exp(x - peak))` — subtracting the peak makes
///   its own term `exp(0)`, so the numerator is 1 and the denominator is at least 1. That
///   is computed here, for the timesteps that are actually kept, which is a few hundred
///   exponentials per line rather than a whole pass over the logit map.
///
/// So the softmax is not moved to the host, it is deleted.
///
/// The classic CTC collapse: a label repeated across consecutive timesteps is one
/// character, and a blank between two identical labels is what separates them into two.
pub fn decode(logits: &[f32], used: usize, dictionary: &Dictionary) -> Result<Decoded, String> {
    let labels = dictionary.labels();
    if labels != LOGITS {
        return Err(format!("a dictionary of {labels} labels, expected {LOGITS}"));
    }
    if logits.is_empty() {
        return Err("no logits at all, so the recogniser produced no timesteps".into());
    }
    if logits.len() % (labels) != 0 {
        return Err(format!(
            "{} logits is not a whole number of {labels}-wide timesteps",
            logits.len()
        ));
    }
    let timesteps = logits.len() / labels;
    if used > timesteps {
        return Err(format!("{used} of {timesteps} timesteps requested"));
    }
    let at = |label: usize, step: usize| -> Result<f32, String> {
        logits
            .get(label * timesteps + step)
            .copied()
            .ok_or_else(|| format!("label {label} of timestep {step} is outside the map"))
    };

    let mut text = String::new();
    let mut total = 0.0f64;
    let mut kept = 0u32;
    let mut previous = usize::MAX;

    for step in 0..used {
        let mut best = 0;
        let mut peak = at(0, step)?;
        for label in 1..labels {
            let value = at(label, step)?;
            if value > peak {
                peak = value;
                best = label;
            }
        }
        // A repeat of the immediately preceding label is the same character held across
        // two frames. A blank resets that, which is how "aa" is spelled at all.
        if best != previous {
            if let Some(character) = dictionary.label(best) {
                // f64, like the mean below: summing 837 small terms into a running total
                // that already holds `exp(0) = 1` loses several f32 digits, and this
                // number is compared against a threshold by callers.
                let mut denominator = 0.0f64;
                for label in 0..labels {
                    denominator += ((at(label, step)? - peak) as f64).exp();
                }
                text.push_str(character);
                total += 1.0 / denominator;
                kept += 1;
            }
        }
        previous = best;
    }

    let confidence = if kept > 0 { (total / kept as f64) as f32 } else { 0.0 };
    Ok(Decoded { text, confidence })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A dictionary of `a`, `b`, `c`, ... padded to the real length so `decode` accepts
    /// it, since a short one is refused on purpose.
    fn dictionary() -> Dictionary {
        let mut text = String::new();
        for index in 0..DICTIONARY_ENTRIES {
            // Printable ASCII cycling, so the first few entries are predictable.
            let code = b'a' + (index % 26) as u8;
            text.push(code as char);
            text.push('\n');
        }
        Dictionary::parse(&text).expect("the fixture dictionary parses")
    }

    /// One timestep's winning label and its logit. Every other label sits at zero, which
    /// makes the expected confidence [`probability`].
    ///
    /// Laid out **class-major**, the way `nets::ppocr_rec` writes it: label `l` of
    /// timestep `t` is at `l * steps + t`. A single-timestep fixture is the same bytes
    /// either way, which is why the argmax test below can stay a flat array.
    fn logits(steps: &[(usize, f32)]) -> Vec<f32> {
        let mut map = vec![0.0; LOGITS * steps.len()];
        for (step, &(label, logit)) in steps.iter().enumerate() {
            if let Some(slot) = map.get_mut(label * steps.len() + step) {
                *slot = logit;
            }
        }
        map
    }

    /// [`decode`] over every timestep in the map, which is what most fixtures want.
    fn all(logits: &[f32], dictionary: &Dictionary) -> Result<Decoded, String> {
        decode(logits, logits.len() / LOGITS, dictionary)
    }

    /// The softmax probability of a winner at `logit` when every other label is zero:
    /// `1 / (1 + 837 * exp(-logit))`.
    ///
    /// Computed here from the definition rather than from `decode`'s arithmetic, so the
    /// fixtures below pin the confidence rather than restate it. f64 for the same reason
    /// `decode` uses it — in f32 the two disagree in the fifth digit, which would make the
    /// tolerance hide a real error.
    fn probability(logit: f32) -> f64 {
        1.0 / (1.0 + (LOGITS - 1) as f64 * (-logit as f64).exp())
    }

    #[test]
    fn the_label_space_is_blank_then_the_dictionary_then_a_space() {
        let d = dictionary();
        assert_eq!(d.labels(), LOGITS);
        // 0 is the blank and decodes to nothing.
        assert_eq!(d.label(0), None);
        // 1 is the *first* dictionary entry: the `index - 1` the plan flagged.
        assert_eq!(d.label(1), Some("a"));
        assert_eq!(d.label(2), Some("b"));
        // The last label is the appended space, not a dictionary entry.
        assert_eq!(d.label(LOGITS - 1), Some(" "));
        assert_eq!(d.label(LOGITS), None);
    }

    #[test]
    fn a_run_of_one_label_collapses_to_one_character() {
        let d = dictionary();
        // Different logits across the run, so "only the first is scored" is observable.
        let got = all(&logits(&[(1, 10.0), (1, 8.0), (1, 12.0)]), &d).expect("decodes");
        assert_eq!(got.text, "a");
        let want = probability(10.0) as f32;
        assert!((got.confidence - want).abs() < 1e-6, "{} vs {want}", got.confidence);
    }

    #[test]
    fn the_confidence_is_the_winners_softmax_probability_of_the_raw_logits() {
        // The graph's final softmax is deliberately not run, so this is where the
        // probability comes from. A decoder that returned the logit itself would report
        // 10.0, and one that forgot the 836 other zero-logit classes would report 1/2.
        let d = dictionary();
        let got = all(&logits(&[(3, 10.0)]), &d).expect("decodes");
        let want = (1.0 / (1.0 + 837.0 * (-10.0f64).exp())) as f32;
        assert!((got.confidence - want).abs() < 1e-6, "{} vs {want}", got.confidence);
        // Sanity: 837 competitors at logit zero against one at 10 is confident but not
        // certain, so this is well inside the unit interval rather than clamped to it.
        assert!((0.9..0.99).contains(&got.confidence), "{}", got.confidence);
    }

    #[test]
    fn a_flat_timestep_is_reported_at_chance() {
        // Every label equally likely is 1/838, and it is the blank that wins a tie at
        // label 0 — so nothing is emitted and the confidence falls back to zero.
        let d = dictionary();
        let got = all(&vec![0.5; LOGITS], &d).expect("decodes");
        assert_eq!(got.text, "");
        assert_eq!(got.confidence, 0.0);
        // With a non-blank winner the same flat field gives chance plus a little.
        let mut map = vec![0.0; LOGITS];
        if let Some(slot) = map.get_mut(2) {
            *slot = f32::MIN_POSITIVE;
        }
        let got = all(&map, &d).expect("decodes");
        assert_eq!(got.text, "b");
        let chance = 1.0 / LOGITS as f32;
        assert!((got.confidence - chance).abs() < 1e-6, "{} vs {chance}", got.confidence);
    }

    #[test]
    fn a_blank_between_two_identical_labels_keeps_both() {
        // The whole reason CTC has a blank: without it "aa" is indistinguishable from a
        // held "a".
        let d = dictionary();
        let got = all(&logits(&[(1, 10.0), (0, 8.0), (1, 9.0)]), &d).expect("decodes");
        assert_eq!(got.text, "aa");
    }

    #[test]
    fn blanks_contribute_no_characters_and_no_confidence() {
        let d = dictionary();
        let got = all(&logits(&[(0, 12.0), (0, 12.0)]), &d).expect("decodes");
        assert_eq!(got.text, "");
        assert_eq!(got.confidence, 0.0);
    }

    #[test]
    fn distinct_adjacent_labels_both_survive_without_a_blank() {
        let d = dictionary();
        let got = all(&logits(&[(1, 10.0), (2, 8.0), (3, 12.0)]), &d).expect("decodes");
        assert_eq!(got.text, "abc");
        // The mean of all three, since none is a repeat.
        let want =
            ((probability(10.0) + probability(8.0) + probability(12.0)) / 3.0) as f32;
        assert!((got.confidence - want).abs() < 1e-6, "{} vs {want}", got.confidence);
    }

    #[test]
    fn the_space_label_decodes_to_a_space() {
        let d = dictionary();
        let got = all(&logits(&[(1, 10.0), (LOGITS - 1, 9.0), (2, 8.0)]), &d)
            .expect("decodes");
        assert_eq!(got.text, "a b");
    }

    #[test]
    fn a_timestep_is_a_column_not_a_row() {
        // The map is class-major, so with three timesteps the first three values are
        // label 0 across all of them, not timestep 0 across three labels. Reading it
        // row-major here would decode label 0 (the blank), then 1, then 2 — "ab" — from
        // a map that actually says "a" once.
        let d = dictionary();
        let (label, steps) = (1usize, 3usize);
        let mut map = vec![0.0; LOGITS * steps];
        for step in 0..steps {
            if let Some(slot) = map.get_mut(label * steps + step) {
                *slot = 10.0;
            }
        }
        let got = all(&map, &d).expect("decodes");
        assert_eq!(got.text, "a");
    }

    #[test]
    fn the_argmax_is_taken_across_every_label_of_the_timestep() {
        // One timestep, so class-major and row-major coincide. The winner is not the only
        // non-zero, so a decoder that took the first positive value would differ.
        let d = dictionary();
        let mut column = vec![0.0; LOGITS];
        for (label, value) in [(1, 0.2), (5, 0.7), (9, 0.1)] {
            if let Some(slot) = column.get_mut(label) {
                *slot = value;
            }
        }
        let got = all(&column, &d).expect("decodes");
        assert_eq!(got.text, "e");
    }

    #[test]
    fn only_the_used_timesteps_are_decoded() {
        // Recognition pads a short crop out to a fixed width, and the padded strip is not
        // image. Reading it invents a character on the end of every short word, which is
        // exactly the failure a fixed-width plan introduces and upstream's aspect-ratio
        // batching avoids.
        let d = dictionary();
        let map = logits(&[(1, 10.0), (2, 10.0), (3, 10.0), (4, 10.0)]);
        assert_eq!(all(&map, &d).expect("decodes").text, "abcd");
        assert_eq!(decode(&map, 2, &d).expect("decodes").text, "ab");
        // Zero used timesteps is a crop with no content, not an error.
        let empty = decode(&map, 0, &d).expect("decodes");
        assert_eq!(empty.text, "");
        assert_eq!(empty.confidence, 0.0);
    }

    #[test]
    fn asking_for_more_timesteps_than_the_map_holds_is_refused() {
        let d = dictionary();
        let map = logits(&[(1, 10.0), (2, 10.0)]);
        let error = decode(&map, 3, &d).expect_err("three of two");
        assert!(error.contains("3 of 2 timesteps"), "{error}");
    }

    #[test]
    fn an_empty_logit_map_is_refused_rather_than_decoding_to_nothing() {
        // A zero-timestep map means the recogniser produced nothing, which is a failure
        // upstream. Returning empty text would make it indistinguishable from a crop that
        // genuinely holds no characters.
        let d = dictionary();
        let error = decode(&[], 0, &d).expect_err("an empty map");
        assert!(error.contains("no logits"), "{error}");
    }

    #[test]
    fn a_dictionary_of_the_wrong_length_is_refused() {
        let error = Dictionary::parse("a\nb\nc\n").expect_err("too short");
        assert!(error.contains("3 dictionary entries"), "{error}");
    }

    #[test]
    fn a_multi_character_dictionary_entry_is_refused() {
        let mut text = String::new();
        for index in 0..DICTIONARY_ENTRIES {
            text.push_str(if index == 4 { "ab" } else { "a" });
            text.push('\n');
        }
        let error = Dictionary::parse(&text).expect_err("a two-character entry");
        assert!(error.contains("entry 4"), "{error}");
    }

    #[test]
    fn carriage_returns_are_tolerated() {
        // The repo has mixed line endings and this asset is text, so a checkout that
        // converted it must still load.
        let mut text = String::new();
        for _ in 0..DICTIONARY_ENTRIES {
            text.push_str("a\r\n");
        }
        let d = Dictionary::parse(&text).expect("parses with CRLF");
        assert_eq!(d.label(1), Some("a"));
    }

    #[test]
    fn a_ragged_logit_array_is_refused() {
        let d = dictionary();
        let error = decode(&[0.0; LOGITS + 3], 1, &d).expect_err("ragged");
        assert!(error.contains("not a whole number"), "{error}");
    }
}
