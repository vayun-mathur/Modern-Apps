//! whisper's decode loop: a log-mel window to a list of token ids, one token at a time.
//!
//! # Why this is here rather than in Kotlin
//!
//! It was in Kotlin, inside `WhisperOnnxEngine`, threading nineteen ONNX tensors per step. The two
//! GPU stages arrive here as a **trait** instead so the sequencing is host-testable against a
//! scripted stub — the pattern [`super::translate`], [`super::ocr`] and [`super::supertonic`] all
//! use — because the order of the special tokens is where the mistakes are and none of them needs a
//! device to catch.
//!
//! # The prompt is four tokens, and `<|notimestamps|>` is one of them
//!
//! ```text
//! <|startoftranscript|>  <|xx|>  <|transcribe|>  <|notimestamps|>
//!        50258            lang       50359            50363
//! ```
//!
//! Dropping the last one does not fail: it changes the output from plain text to `<|0.00|>`-style
//! timestamped text, because the model was trained to predict a timestamp first unless told not to.
//! That is the same shape of hazard as SMaLL-100's "the target language goes on the source side",
//! and `tests::the_prompt_is_four_tokens_ending_in_no_timestamps` is what pins it.
//!
//! The reference feeds all four at once with `use_cache_branch=false`. This feeds them one at a
//! time, because [`crate::nets::whisper::Mode::DecodeStep`] is one query per step — and for a
//! causal decoder those are the same thing: token `t`'s hidden state depends only on tokens
//! `0..=t`, whichever order they were presented in. It costs three extra steps out of ~30.
//!
//! # Language detection is the first step's logits, not an extra pass
//!
//! With only `<|startoftranscript|>` fed, the highest-scoring **language token** is the detected
//! language — that is how whisper's own detection works. So step 0 is run whether or not the caller
//! named a language, and its logits are simply ignored when they did. No extra forward pass.
//!
//! The code-to-token table stays with the caller, as [`super::translate`]'s does: Kotlin already
//! reads `lang_to_id` out of `generation_config.json`, and duplicating 99 entries here would be two
//! places to get wrong.
//!
//! # Greedy, and the ids come back raw
//!
//! No beam search, matching the engine this replaces. And [`transcribe`] returns **token ids**, not
//! text: `WhisperTokenizer` is Kotlin, it is what skips the ids at or above 50,257 that have no text
//! form, and it stays untouched by this port.

/// Tokens the loop will emit before giving up.
///
/// One 30-second window cannot say more than this. It is the guard against a greedy decode that
/// never emits `<|endoftext|>`, which it can do by repeating itself, and it is the same 224 the
/// engine this replaces used.
pub const MAX_NEW_TOKENS: usize = 224;

/// Tokens the prompt occupies before the first generated one. See the module docs.
pub const PROMPT_TOKENS: usize = 4;

/// The special ids and suppression lists a decode needs, all read from `generation_config.json`.
///
/// Carried as a struct rather than as eight arguments because the caller builds it once, at
/// construction, and a decode is then one call. Every field is a `u32` id in the model's 51,865-entry
/// vocabulary; [`Decoding::check`] refuses anything outside it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Decoding {
    /// `<|startoftranscript|>`, 50258. `decoder_start_token_id`.
    pub start_of_transcript: u32,
    /// `<|endoftext|>`, 50257. `eos_token_id`, and what ends the loop.
    pub end_of_text: u32,
    /// `<|transcribe|>`, 50359. **Not** `<|translate|>`, which is the id one lower and would
    /// produce English for every language.
    pub transcribe: u32,
    /// `<|notimestamps|>`, 50363. See the module docs for what dropping it does.
    pub no_timestamps: u32,
    /// `max_length`, 448, which is also the decoder's position table.
    pub max_length: usize,
    /// The 99 `<|xx|>` language ids, in any order. Scored against each other when the caller did
    /// not name a language.
    pub languages: Vec<u32>,
    /// Never-emit ids — music notes and formatting artefacts — that upstream masks out.
    pub suppress: Vec<u32>,
    /// Additionally suppressed at the **first generated position only**: a leading space and
    /// `<|endoftext|>`, so a window with speech in it cannot return nothing.
    pub suppress_at_begin: Vec<u32>,
}

impl Decoding {
    /// Refuse a configuration that cannot have come from this model's `generation_config.json`.
    ///
    /// Checked at construction rather than per decode, and loudly: every one of these is an id the
    /// loop feeds or compares against, so a wrong one produces confident wrong text.
    pub fn check(&self, vocabulary: u32) -> Result<(), String> {
        let named = [
            ("start_of_transcript", self.start_of_transcript),
            ("end_of_text", self.end_of_text),
            ("transcribe", self.transcribe),
            ("no_timestamps", self.no_timestamps),
        ];
        for (what, id) in named {
            if id >= vocabulary {
                return Err(format!("{what} is {id}, past the {vocabulary}-entry vocabulary"));
            }
        }
        if self.languages.is_empty() {
            return Err("no language tokens, so a language can be neither named nor detected".into());
        }
        if let Some(&id) = self.languages.iter().find(|&&id| id >= vocabulary) {
            return Err(format!("language token {id} is past the vocabulary"));
        }
        if self.max_length <= PROMPT_TOKENS {
            return Err(format!(
                "max_length {} leaves nothing after the {PROMPT_TOKENS}-token prompt",
                self.max_length
            ));
        }
        Ok(())
    }

    /// Tokens the loop may generate: whatever the position table has left, capped at
    /// [`MAX_NEW_TOKENS`].
    fn budget(&self) -> usize {
        self.max_length.saturating_sub(PROMPT_TOKENS).min(MAX_NEW_TOKENS)
    }
}

/// The two GPU stages, as a trait, so the loop below is host-testable.
pub trait Nets {
    /// The encoder over one 30-second log-mel window, `[80 * 3000]` row-major.
    ///
    /// Run once. It also computes every decoder layer's cross-attention keys and values, which the
    /// implementation retains — so unlike [`super::translate::Nets`] nothing is handed back here.
    fn encode(&mut self, mel: &[f32]) -> Result<(), String>;

    /// One decoder step: the token in, the logits over the whole vocabulary out.
    ///
    /// `step` is the position, so the implementation knows how long its self-attention cache should
    /// be and where to append this token's key and value.
    fn decode_step(&mut self, token: u32, step: usize) -> Result<Vec<f32>, String>;
}

/// Transcribe one log-mel window into token ids, for the caller's tokenizer to decode.
///
/// `language` is a `<|xx|>` token the caller resolved from a code, or `None` to detect. An unknown
/// code should arrive as `None` rather than as a guess: detection is what whisper does by default
/// and is better than the wrong language.
///
/// An empty result means the window held nothing the model would transcribe, which is not a failure.
pub fn transcribe(
    nets: &mut dyn Nets,
    config: &Decoding,
    mel: &[f32],
    language: Option<u32>,
) -> Result<Vec<u32>, String> {
    if let Some(id) = language {
        if !config.languages.contains(&id) {
            return Err(format!("{id} is not one of the {} language tokens", config.languages.len()));
        }
    }
    nets.encode(mel)?;

    // Step 0 is `<|startoftranscript|>` alone, and its logits are the language distribution — which
    // is run whether or not the caller named a language, because the token has to be fed either way.
    let mut logits = nets.decode_step(config.start_of_transcript, 0)?;
    let language = match language {
        Some(id) => id,
        None => best_of(&logits, &config.languages)
            .ok_or("the decoder scored no language token at step 0")?,
    };
    // The rest of the prompt. Each of these three positions' logits is discarded: the model is
    // predicting the next *prompt* token, which is already decided.
    for (offset, token) in [language, config.transcribe, config.no_timestamps].into_iter().enumerate()
    {
        logits = nets.decode_step(token, 1 + offset)?;
    }

    // `logits` now predicts the first token of the transcript.
    let mut produced: Vec<u32> = Vec::new();
    let budget = config.budget();
    for index in 0..budget {
        let begin: &[u32] = if index == 0 { &config.suppress_at_begin } else { &[] };
        let next = argmax(&logits, &config.suppress, begin).ok_or_else(|| {
            format!("the decoder returned {} usable logits at token {index}", logits.len())
        })?;
        if next == config.end_of_text {
            break;
        }
        produced.push(next);
        if produced.len() == budget {
            // The budget is spent, so there is no next token to predict and no step to run for it.
            break;
        }
        logits = nets.decode_step(next, PROMPT_TOKENS + index)?;
    }
    Ok(produced)
}

/// The highest-scoring id among `candidates`, or `None` if none of them is in range.
///
/// Used for language detection, which scores the 99 language tokens against each other and ignores
/// every other logit — including the far larger text ones, which is why this is not an `argmax` with
/// a mask.
fn best_of(logits: &[f32], candidates: &[u32]) -> Option<u32> {
    let mut best: Option<(u32, f32)> = None;
    for &id in candidates {
        let Some(&value) = logits.get(id as usize) else {
            continue;
        };
        if value.is_nan() {
            continue;
        }
        if best.is_none_or(|(_, top)| value > top) {
            best = Some((id, value));
        }
    }
    best.map(|(id, _)| id)
}

/// The index of the largest value, skipping every id in `suppress` or `also`.
///
/// Ties take the lowest index, which is what `argmax` does everywhere else here and what makes a
/// greedy decode reproducible. NaN never wins, so a net that produced one degrades to picking some
/// other token rather than to whichever comparison happened first.
fn argmax(logits: &[f32], suppress: &[u32], also: &[u32]) -> Option<u32> {
    let mut best: Option<(usize, f32)> = None;
    for (index, &value) in logits.iter().enumerate() {
        if value.is_nan() {
            continue;
        }
        let id = index as u32;
        if suppress.contains(&id) || also.contains(&id) {
            continue;
        }
        if best.is_none_or(|(_, top)| value > top) {
            best = Some((index, value));
        }
    }
    best.map(|(index, _)| index as u32)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The real ids, from `generation_config.json`, so the fixtures below are the shipping prompt.
    const SOT: u32 = 50_258;
    const EOT: u32 = 50_257;
    const TRANSCRIBE: u32 = 50_359;
    const NO_TIMESTAMPS: u32 = 50_363;
    const EN: u32 = 50_259;
    const FR: u32 = 50_265;

    fn config() -> Decoding {
        Decoding {
            start_of_transcript: SOT,
            end_of_text: EOT,
            transcribe: TRANSCRIBE,
            no_timestamps: NO_TIMESTAMPS,
            max_length: 448,
            languages: vec![EN, FR],
            // 220 is the leading space and 50257 the end of text, which is `begin_suppress_tokens`.
            suppress: vec![1, 2, 7],
            suppress_at_begin: vec![220, EOT],
        }
    }

    /// Records what the loop asked for, and answers with a scripted sequence of tokens.
    struct Scripted {
        /// One winning token per *generated* position; the prompt's three discarded steps are not
        /// scripted, because their logits are never read.
        script: Vec<u32>,
        /// Every `(token, step)` the decoder saw, in order.
        steps: Vec<(u32, usize)>,
        /// The mel the encoder was given, so a test can assert it ran exactly once.
        encoded: usize,
        /// Scores for the language tokens at step 0, so detection can be steered.
        language_scores: Vec<(u32, f32)>,
        vocabulary: usize,
    }

    impl Nets for Scripted {
        fn encode(&mut self, mel: &[f32]) -> Result<(), String> {
            assert_eq!(mel.len(), 80 * 3000, "the mel window is a fixed size");
            self.encoded += 1;
            Ok(())
        }

        fn decode_step(&mut self, token: u32, step: usize) -> Result<Vec<f32>, String> {
            self.steps.push((token, step));
            let mut logits = vec![0.0f32; self.vocabulary];
            if step == 0 {
                for &(id, score) in &self.language_scores {
                    logits[id as usize] = score;
                }
                return Ok(logits);
            }
            // Positions 1 and 2 are prompt steps whose logits are discarded; position 3 predicts
            // the first transcript token, and position `3 + n` the token after the nth.
            let wanted = step.saturating_sub(PROMPT_TOKENS - 1);
            let token = self.script.get(wanted).copied().unwrap_or(EOT);
            logits[token as usize] = 1.0;
            Ok(logits)
        }
    }

    fn scripted(script: &[u32]) -> Scripted {
        Scripted {
            script: script.to_vec(),
            steps: Vec::new(),
            encoded: 0,
            // English wins unless a test says otherwise.
            language_scores: vec![(EN, 2.0), (FR, 1.0)],
            vocabulary: 51_865,
        }
    }

    fn mel() -> Vec<f32> {
        vec![0.25; 80 * 3000]
    }

    #[test]
    fn the_prompt_is_four_tokens_ending_in_no_timestamps() {
        // The trap this module exists to document. `<|notimestamps|>` last, at position 3, is what
        // keeps the output plain text; dropping it produces `<|0.00|>`-style output that still
        // decodes and still reads like a transcript.
        let mut nets = scripted(&[100, EOT]);
        let out = transcribe(&mut nets, &config(), &mel(), None).expect("transcribes");
        assert_eq!(out, vec![100]);
        let prompt: Vec<(u32, usize)> = nets.steps.iter().take(PROMPT_TOKENS).copied().collect();
        assert_eq!(
            prompt,
            vec![(SOT, 0), (EN, 1), (TRANSCRIBE, 2), (NO_TIMESTAMPS, 3)],
            "{:?}",
            nets.steps
        );
    }

    #[test]
    fn the_language_is_detected_from_step_zero_and_needs_no_extra_pass() {
        // With only `<|startoftranscript|>` fed, the highest-scoring language token is the answer.
        // Step 0 runs either way, so detection is free.
        let mut nets = scripted(&[EOT]);
        nets.language_scores = vec![(EN, 0.5), (FR, 3.0)];
        transcribe(&mut nets, &config(), &mel(), None).expect("transcribes");
        assert_eq!(nets.steps[1], (FR, 1));
        // One encoder run, and the language was fed exactly once: detection cost no extra pass.
        assert_eq!(nets.encoded, 1);
        let languages = nets
            .steps
            .iter()
            .filter(|(token, _)| config().languages.contains(token))
            .count();
        assert_eq!(languages, 1, "{:?}", nets.steps);
    }

    #[test]
    fn a_named_language_wins_over_the_scores() {
        // An explicit code beats detection, and the detection logits are simply ignored — which is
        // why step 0 still runs.
        let mut nets = scripted(&[EOT]);
        nets.language_scores = vec![(EN, 9.0), (FR, 0.0)];
        transcribe(&mut nets, &config(), &mel(), Some(FR)).expect("transcribes");
        assert_eq!(nets.steps[1], (FR, 1));
    }

    #[test]
    fn a_language_token_the_model_does_not_have_is_refused() {
        // The caller resolves a code to a token, so a wrong table there would otherwise feed a text
        // id into the prompt and produce confident nonsense.
        let mut nets = scripted(&[EOT]);
        let error = transcribe(&mut nets, &config(), &mel(), Some(42)).expect_err("a text id");
        assert!(error.contains("language tokens"), "{error}");
        assert_eq!(nets.encoded, 0, "the encoder ran before the check");
    }

    #[test]
    fn each_step_feeds_the_previous_token_back_after_the_prompt() {
        // Greedy decoding is a chain, and the step numbering continues from the prompt: the first
        // generated token is fed at position 4, not at 0.
        let mut nets = scripted(&[100, 101, 102, EOT]);
        let out = transcribe(&mut nets, &config(), &mel(), Some(EN)).expect("transcribes");
        assert_eq!(out, vec![100, 101, 102]);
        assert_eq!(
            nets.steps,
            vec![
                (SOT, 0),
                (EN, 1),
                (TRANSCRIBE, 2),
                (NO_TIMESTAMPS, 3),
                (100, 4),
                (101, 5),
                (102, 6),
            ]
        );
    }

    #[test]
    fn end_of_text_ends_the_loop_and_is_not_emitted() {
        let mut nets = scripted(&[100, 101, EOT, 102]);
        let out = transcribe(&mut nets, &config(), &mel(), Some(EN)).expect("transcribes");
        assert_eq!(out, vec![100, 101]);
        // Four prompt steps plus two generated ones: the step that would have fed `EOT` never ran.
        assert_eq!(nets.steps.len(), PROMPT_TOKENS + 2);
    }

    #[test]
    fn end_of_text_at_the_first_position_is_suppressed() {
        // `begin_suppress_tokens` is `[220, 50257]`, so a window with speech in it cannot come back
        // empty because the model was unsure. Without it this returns nothing.
        let mut nets = scripted(&[EOT]);
        // Make `EOT` the strongest and 100 the runner-up at the first position only.
        nets.script = vec![EOT];
        let mut config = config();
        config.suppress_at_begin = vec![220, EOT];
        let out = transcribe(&mut nets, &config, &mel(), Some(EN)).expect("transcribes");
        // Suppressed, so the loop picks *something* rather than stopping immediately.
        assert!(!out.is_empty(), "the first position returned nothing");
        assert_ne!(out[0], EOT);
        // And a leading space is suppressed there too.
        assert_ne!(out[0], 220);
    }

    #[test]
    fn a_suppressed_id_is_never_emitted_at_any_position() {
        let mut config = config();
        config.suppress = vec![100];
        let mut nets = scripted(&[100, 100, EOT]);
        let out = transcribe(&mut nets, &config, &mel(), Some(EN)).expect("transcribes");
        assert!(!out.contains(&100), "{out:?}");
    }

    #[test]
    fn a_decoder_that_never_stops_is_capped() {
        // Greedy decoding can loop forever by repeating itself, and the position table is only 448
        // deep, so the loop stops rather than running past it.
        let mut nets = scripted(&vec![100u32; MAX_NEW_TOKENS + 50]);
        let out = transcribe(&mut nets, &config(), &mel(), Some(EN)).expect("transcribes");
        assert_eq!(out.len(), MAX_NEW_TOKENS);
        // And it did not run a step for a token it had no room to predict from.
        assert_eq!(nets.steps.len(), PROMPT_TOKENS + MAX_NEW_TOKENS - 1);
        // The last step is at position `3 + 223`, comfortably inside the 448-entry table.
        assert!(nets.steps.last().expect("a step").1 < 448);
    }

    #[test]
    fn a_short_max_length_caps_the_loop_before_the_token_budget_does() {
        // `max_length` is the position table, and the prompt spends four of it. A configuration that
        // shrank it must shorten the transcript rather than overrun the table.
        let mut config = config();
        config.max_length = PROMPT_TOKENS + 3;
        assert_eq!(config.budget(), 3);
        let mut nets = scripted(&[100u32; 50]);
        let out = transcribe(&mut nets, &config, &mel(), Some(EN)).expect("transcribes");
        assert_eq!(out.len(), 3);
    }

    #[test]
    fn a_configuration_outside_the_vocabulary_is_refused() {
        let vocabulary = 51_865;
        config().check(vocabulary).expect("the real ids are in range");

        let mut wrong = config();
        wrong.no_timestamps = vocabulary;
        let error = wrong.check(vocabulary).expect_err("past the vocabulary");
        assert!(error.contains("no_timestamps"), "{error}");

        let mut wrong = config();
        wrong.languages.clear();
        let error = wrong.check(vocabulary).expect_err("no languages");
        assert!(error.contains("language tokens"), "{error}");

        let mut wrong = config();
        wrong.max_length = PROMPT_TOKENS;
        let error = wrong.check(vocabulary).expect_err("no room");
        assert!(error.contains("max_length"), "{error}");
    }

    #[test]
    fn argmax_takes_the_lowest_index_of_a_tie_and_skips_nan() {
        assert_eq!(argmax(&[1.0, 3.0, 3.0, 2.0], &[], &[]), Some(1));
        assert_eq!(argmax(&[f32::NAN, 1.0], &[], &[]), Some(1));
        assert_eq!(argmax(&[f32::NAN], &[], &[]), None);
        assert_eq!(argmax(&[], &[], &[]), None);
        // Negative logits are normal — a softmax was never taken.
        assert_eq!(argmax(&[-5.0, -1.0, -9.0], &[], &[]), Some(1));
        // Suppression applies before the comparison, not after.
        assert_eq!(argmax(&[1.0, 9.0, 2.0], &[1], &[]), Some(2));
        assert_eq!(argmax(&[1.0, 9.0, 2.0], &[], &[1, 2]), Some(0));
    }

    #[test]
    fn language_detection_ignores_every_non_language_logit() {
        // A text token will always outscore a language token on a real window, so scoring the
        // language ids against *each other* is the whole of the method.
        let mut logits = vec![0.0f32; 51_865];
        logits[100] = 99.0;
        logits[EN as usize] = 1.0;
        logits[FR as usize] = 2.0;
        assert_eq!(best_of(&logits, &[EN, FR]), Some(FR));
        // An id past the end is skipped rather than panicking.
        assert_eq!(best_of(&logits, &[EN, 99_999]), Some(EN));
        assert_eq!(best_of(&logits, &[]), None);
    }
}
