//! MADLAD's decode loop: source text to translated text, one token at a time.
//!
//! # What was inside the GGUF runner
//!
//! The loop, like the tokenizer, lived outside the repo — in candle's `quantized-t5`
//! example. The two nets arrive here as a **trait** so the sequencing is host-testable
//! against stubs — the pattern `post::translate` uses — because the order of the special
//! tokens is where the mistakes are and none of them needs a device to catch.
//!
//! # The TARGET tag goes on the SOURCE side; the decoder starts from `<unk>`
//!
//! This is the one thing about MADLAD that is easy to get backwards, and getting it
//! backwards produces fluent output in the wrong language rather than an error:
//!
//! ```text
//! source  = [<2tgt>] ++ tokenizer(text) ++ [</s>]
//! decoder input starts from `<unk>` (id 0, `decoder_start_token_id`); decoding proceeds
//! autoregressively from there with NO forced-BOS override, stopping at `</s>` (id 2)
//! ```
//!
//! The `<2xx>` tag is an ordinary Unigram piece (e.g. `<2en>` is id 38): it tokenises
//! like any other text and needs no special id table. What it does need is to be the
//! FIRST piece — T5 was trained with the tag as a source prefix, so the host prepends
//! the tag string to the text before encoding rather than splicing an id in after.
//!
//! # Greedy, not beam
//!
//! As NLLB's: the reference decodes greedily (`temperature 0`, one beam), and so does
//! this. Beam search would mean several decoder states and several times the work per
//! token for a phrase-sized translation.

use super::sentencepiece::{Table, EOS};

/// Decode-loop timing, via logcat. Temporary profiling for the Q2_K bring-up: answers
/// whether a slow translation is slow steps (shader throughput) or many steps (EOS never
/// fires and the loop runs all 128). Remove once the step budget is known.
fn profile(message: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        let tag = CString::new("ModelRunner").unwrap_or_default();
        let text = CString::new(message).unwrap_or_default();
        unsafe {
            ndk_sys_log(tag.as_ptr(), text.as_ptr());
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = message;
    }
}

/// Minimal `__android_log_print` binding, so the post module need not depend on the bridge.
#[cfg(target_os = "android")]
unsafe fn ndk_sys_log(tag: *const std::os::raw::c_char, text: *const std::os::raw::c_char) {
    unsafe extern "C" {
        fn __android_log_print(
            prio: std::os::raw::c_int,
            tag: *const std::os::raw::c_char,
            fmt: *const std::os::raw::c_char,
            ...
        ) -> std::os::raw::c_int;
    }
    // 3 = ANDROID_LOG_DEBUG. The format string is the message itself; it carries no `%`.
    unsafe { __android_log_print(3, tag, text) };
}

/// Decoder start token: `<unk>` (id 0), which is also `decoder_start_token_id`.
///
/// T5 has no beginning-of-sequence marker of its own, so the start id doubles as the
/// unknown-word id. The first step's input is this token; from there the loop is pure
/// autoregression — unlike NLLB there is no forced target token to feed back.
pub const DECODER_START: u32 = 0;

/// Tokens the loop will emit before giving up.
///
/// The same 128 the decoder's KV cache is built for, so the loop cannot outrun it. A translation
/// of a sentence is tens of tokens; this is the guard against a model that never emits `</s>`,
/// which greedy decoding can do by repeating itself.
pub const MAX_TOKENS: usize = 128;

/// The two GPU stages, as a trait, so the loop below is host-testable.
pub trait Nets {
    /// The encoder over the whole source sequence.
    ///
    /// Run once. It leaves every decoder layer's cross-attention K and V on the device, so
    /// nothing comes back to the host.
    fn encode(&mut self, source: &[u32]) -> Result<(), String>;

    /// One decoder step: the previous token in, the greedy next token out.
    ///
    /// `step` is the position, so the implementation knows where in its KV cache to write and
    /// which bias row to upload.
    fn decode_step(&mut self, token: u32, step: usize) -> Result<u32, String>;
}

/// Translate `text` into the language `tag` names (e.g. `"<2en>"`).
///
/// `text` must already have double spaces collapsed (HF's T5 normaliser; the Unigram model
/// itself is the identity). The tag is prepended to the text before encoding, so it
/// tokenises as the leading piece — the training-time source prefix — rather than being
/// spliced in as an id the tokenizer might have split differently.
///
/// The decoder starts from `<unk>` ([`DECODER_START`]) and proceeds greedily, stopping at
/// the first `</s>`. No forced-BOS: every emitted token, including the first, is the
/// model's own argmax.
///
/// An empty result means the text had nothing to translate, which is not a failure.
pub fn translate(
    nets: &mut dyn Nets,
    table: &Table,
    tag: &str,
    text: &str,
) -> Result<String, String> {
    if table.id_of(tag).is_none() {
        return Err(format!("{tag} is not a piece in this table"));
    }
    // Nothing to translate is not a failure — and with the tag prepended the
    // encoded body is never empty, so this must be decided on the text itself
    // or a whitespace-only input would run the encoder and decode its noise.
    if text.trim().is_empty() {
        return Ok(String::new());
    }
    // The tag leads, exactly as trained: `<2en> text`, one string, one Viterbi run. The
    // space separates the tag from the body so the dummy prefix applies once, to the tag.
    let body = table.encode_unigram(&format!("{tag} {text}"));
    if body.is_empty() {
        return Ok(String::new());
    }

    // The tokenizer's own `</s>` closes the source.
    let mut source = Vec::with_capacity(body.len() + 1);
    source.extend(body);
    source.push(EOS);

    nets.encode(&source)?;
    profile(&format!("madlad: encoded {} tokens", source.len()));

    let mut produced: Vec<u32> = Vec::new();
    let mut token = DECODER_START;
    let t0 = std::time::Instant::now();
    for step in 0..MAX_TOKENS {
        let s0 = std::time::Instant::now();
        let next = nets.decode_step(token, step)?;
        let dt = s0.elapsed();
        if step < 5 || step % 20 == 0 {
            profile(&format!("madlad: step {step} took {}ms", dt.as_millis()));
        }
        if next == EOS {
            break;
        }
        produced.push(next);
        token = next;
    }
    profile(&format!(
        "madlad: decoded {} tokens in {}ms",
        produced.len(),
        t0.elapsed().as_millis()
    ));
    Ok(table.decode(&produced))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Records what the loop asked for, and answers with a scripted sequence of tokens.
    struct Scripted {
        /// One token per step, the last of which is usually `EOS`.
        script: Vec<u32>,
        /// The source the encoder was given, for the assertions about special tokens.
        source: Vec<u32>,
        /// Every `(token, step)` the decoder saw.
        steps: Vec<(u32, usize)>,
    }

    impl Nets for Scripted {
        fn encode(&mut self, source: &[u32]) -> Result<(), String> {
            self.source = source.to_vec();
            Ok(())
        }

        fn decode_step(&mut self, token: u32, step: usize) -> Result<u32, String> {
            assert!(!self.source.is_empty(), "a step before the encoder ran");
            self.steps.push((token, step));
            // Running off the end of the script asks for `EOS`.
            Ok(self.script.get(step).copied().unwrap_or(EOS))
        }
    }

    fn scripted(script: &[u32]) -> Scripted {
        Scripted { script: script.to_vec(), source: Vec::new(), steps: Vec::new() }
    }

    /// A table whose ids 10.. are the ASCII letters, so a script maps to readable text —
    /// plus a `<2en>` tag piece for the prefix tests.
    fn table_bytes() -> Vec<u8> {
        let mut out = b"SPM1".to_vec();
        // T5's specials (sentencepiece_flavours::T5): <unk>=0 doubles as the
        // decoder start, <s>=1 is pad, </s>=2 is EOS, and id 3 is the real
        // newline piece — not a fourth special.
        let pieces: Vec<String> = ["<unk>", "<s>", "</s>", "\n"]
            .iter()
            .map(|s| s.to_string())
            .chain(["\u{2581}<2en>".to_string(), "<2en>".to_string()])
            .chain((0..6).map(|i| format!("\u{2581}w{i}")))
            .chain((0..26).map(|i| ((b'a' + i) as char).to_string()))
            .collect();
        out.extend((pieces.len() as u32).to_le_bytes());
        for (index, piece) in pieces.iter().enumerate() {
            // Earlier pieces score higher (less negative), so the multi-character ones win
            // the Viterbi over their spellings.
            out.extend((-(index as i32)).to_le_bytes());
            out.extend((piece.len() as u16).to_le_bytes());
            out.extend(piece.as_bytes());
        }
        out
    }

    #[test]
    fn the_tag_leads_the_source_and_unk_starts_the_decoder() {
        // The trap this module exists to document: the TARGET tag leads the source, and the
        // decoder starts from `<unk>` with no forcing. All three are asserted, because
        // swapping the tag side produces fluent text in the wrong language.
        let bytes = table_bytes();
        let table = Table::parse_with(&bytes, super::super::sentencepiece_flavours::T5).expect("parses");
        let mut nets = scripted(&[EOS]);
        translate(&mut nets, &table, "<2en>", "a b").expect("translates");
        // The tag tokenised first: id 4 is `\u{2581}<2en>`, the tag carrying
        // the dummy prefix, exactly as sentencepiece emits it.
        assert_eq!(nets.source.first(), Some(&4u32));
        assert_eq!(nets.source.last(), Some(&EOS));
        // The decoder was primed with `<unk>`, not with the tag and not with `</s>`.
        assert_eq!(nets.steps.first(), Some(&(DECODER_START, 0)));
        assert_eq!(DECODER_START, 0);
    }

    #[test]
    fn an_unknown_tag_is_refused() {
        // A tag the table never saw would mistranslate silently if spliced as an id.
        let bytes = table_bytes();
        let table = Table::parse_with(&bytes, super::super::sentencepiece_flavours::T5).expect("parses");
        let mut nets = scripted(&[EOS]);
        let error = translate(&mut nets, &table, "<2xx>", "a").expect_err("unknown tag");
        assert!(error.contains("not a piece"), "{error}");
    }

    #[test]
    fn eos_ends_the_loop_and_is_not_emitted() {
        let bytes = table_bytes();
        let table = Table::parse_with(&bytes, super::super::sentencepiece_flavours::T5).expect("parses");
        let mut nets = scripted(&[14, 15, EOS, 16]);
        let got = translate(&mut nets, &table, "<2en>", "a").expect("translates");
        // The script's winners decode through the letter pieces; 16 is past the `</s>`.
        assert_eq!(nets.steps.len(), 3);
        assert!(!got.contains("</s>"));
    }

    #[test]
    fn a_decoder_that_never_stops_is_capped() {
        // Greedy decoding can loop forever by repeating itself, and the KV cache is only 128
        // deep, so the loop stops rather than running past it.
        let bytes = table_bytes();
        let table = Table::parse_with(&bytes, super::super::sentencepiece_flavours::T5).expect("parses");
        // A script of one repeated non-EOS token, longer than the cap.
        let script = vec![20u32; MAX_TOKENS + 10];
        let mut nets = scripted(&script);
        let got = translate(&mut nets, &table, "<2en>", "a").expect("translates");
        assert_eq!(nets.steps.len(), MAX_TOKENS);
    }

    #[test]
    fn text_with_nothing_to_translate_returns_nothing_and_runs_no_net() {
        let bytes = table_bytes();
        let table = Table::parse_with(&bytes, super::super::sentencepiece_flavours::T5).expect("parses");
        let mut nets = scripted(&[EOS]);
        let got = translate(&mut nets, &table, "<2en>", "   ").expect("translates");
        assert_eq!(got, "");
        assert!(nets.source.is_empty(), "the encoder ran on nothing");
        assert!(nets.steps.is_empty(), "the decoder ran on nothing");
    }
}
