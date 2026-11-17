//! The host half of Supertonic 3's sampler: the timestep embedding, the rotary angles, and the
//! guidance combination that turns two forward passes into one Euler step.
//!
//! # Why any of this is on the host
//!
//! [`crate::nets::supertonic_sampler`] is a plan of convolutions, attentions and layer norms.
//! Four things in the export are none of those, and all four are cheap:
//!
//! * The **timestep conditioning** is a sinusoidal embedding of `current_step / total_step`, a
//!   two-layer MLP with a Mish between, and four `Linear`s to 512 numbers. 2,048 values from two
//!   scalars, against a net that spends 64M multiply-adds per latent frame — so `Sin`, `Cos`,
//!   `Softplus` and `Tanh` shaders would exist for nothing.
//! * The **rotary angles** are `(position / length) * theta`. Nothing learned, and they change
//!   with the sequence lengths rather than with the weights.
//! * The **unconditional branch's inputs** are two learned tokens, one broadcast over the text
//!   and one standing in for the voice.
//! * The **guidance combination and the Euler step**, four multiply-adds per latent value.
//!
//! Each of those tensors is declared to [`crate::nets::Builder::host_tensor`] so a genuinely
//! unread weight is still an error.
//!
//! # The step is two passes
//!
//! The export tiles its batch to two and runs the whole network twice, once conditioned on the
//! real text and voice and once on the unconditional tokens, then takes
//! `4 * conditional - 3 * unconditional`. This runtime has no batch axis, so [`step`] takes the
//! two velocities the caller already ran. That is a genuine doubling of the sampler's cost.

use crate::nets::embed_lanes;
use crate::nets::supertonic_duration as duration_net;
use crate::nets::supertonic_sampler as net;
use crate::preprocess::f16_to_f32;
use crate::weights::Reader;

/// The frequency multiplier the export applies before the sinusoids: `t * 1000 * frequency`.
const TIME_SCALE: f32 = 1000.0;

/// `out[o] = sum_i weight[o][i] * x[i] + bias[o]`, over a row-major `[out, in]` weight.
fn linear(weight: &[f32], bias: &[f32], x: &[f32]) -> Vec<f32> {
    let inputs = x.len();
    bias.iter()
        .enumerate()
        .map(|(o, &b)| {
            let row = &weight[o * inputs..(o + 1) * inputs];
            row.iter().zip(x).map(|(&w, &v)| w * v).sum::<f32>() + b
        })
        .collect()
}

/// `x * tanh(softplus(x))`, the export's `mlp.1`.
///
/// `softplus` is written as `ln(1 + e^x)` only for negative `x`: for large positive `x` the
/// exponential overflows while the function is within an fp32 epsilon of `x` itself.
fn mish(x: f32) -> f32 {
    let softplus = if x > 20.0 { x } else { x.exp().ln_1p() };
    x * softplus.tanh()
}

/// The four per-block timestep shifts, `[4 * 512]`, for step `current` of `total`.
///
/// The plan reads them as one `[2048, 1, 1]` input and [`crate::nets::Builder::slice_channels`]
/// hands each main block its own 512.
pub fn time_shifts(weights: Reader, current: u32, total: u32) -> Result<Vec<f32>, String> {
    if total == 0 {
        return Err("a sampler step out of no steps".into());
    }
    let frequencies = weights.fp16(net::HOST_FREQUENCIES, &[net::FREQUENCIES])?;
    let progress = current as f32 / total as f32;

    // Sines then cosines, which is the order of the export's `Concat`.
    let angles: Vec<f32> = frequencies.iter().map(|&f| progress * TIME_SCALE * f).collect();
    let embedding: Vec<f32> = angles
        .iter()
        .map(|a| a.sin())
        .chain(angles.iter().map(|a| a.cos()))
        .collect();

    let in_weight = weights.fp16(net::HOST_MLP_IN, &[net::TIME_INNER, net::TIME])?;
    let in_bias = weights.fp16(net::HOST_MLP_IN + 1, &[net::TIME_INNER])?;
    let hidden: Vec<f32> = linear(&in_weight, &in_bias, &embedding).into_iter().map(mish).collect();

    let out_weight = weights.fp16(net::HOST_MLP_OUT, &[net::TIME, net::TIME_INNER])?;
    let out_bias = weights.fp16(net::HOST_MLP_OUT + 1, &[net::TIME])?;
    let time = linear(&out_weight, &out_bias, &hidden);

    let mut shifts = Vec::with_capacity(net::MAIN_BLOCKS * net::CHANNELS as usize);
    for block in 0..net::MAIN_BLOCKS {
        let index = net::HOST_TIME_LINEARS + block * 2;
        let weight = weights.fp16(index, &[net::CHANNELS, net::TIME])?;
        let bias = weights.fp16(index + 1, &[net::CHANNELS])?;
        shifts.extend(linear(&weight, &bias, &time));
    }
    Ok(shifts)
}

/// The rotary angle table for a sequence of `positions`, as the `[64, 1, positions]` the plan
/// wants: 32 channels of cosine then 32 of sine.
///
/// The angle is `(position / positions) * theta` — normalised by the sequence's **own** length,
/// which is what lets a latent frame and a text character at the same fraction of the way through
/// meet at the same angle. It also means the query table and the key table are different tensors
/// even though they share `theta`.
pub fn rotary_angles(theta: &[f32], positions: u32) -> Result<Vec<f32>, String> {
    if positions == 0 {
        return Err("a rotary table over no positions".into());
    }
    if theta.len() != net::FREQUENCIES as usize {
        return Err(format!("{} rotary frequencies, not {}", theta.len(), net::FREQUENCIES));
    }
    let width = positions as usize;
    let mut table = vec![0.0f32; 2 * net::FREQUENCIES as usize * width];
    for (frequency, &turn) in theta.iter().enumerate() {
        for position in 0..width {
            let angle = position as f32 / positions as f32 * turn;
            table[frequency * width + position] = angle.cos();
            table[(net::FREQUENCIES as usize + frequency) * width + position] = angle.sin();
        }
    }
    Ok(table)
}

/// The unconditional branch's text input: `text_special_token` at every one of `chars` positions.
pub fn unconditional_text(token: &[f32], chars: u32) -> Result<Vec<f32>, String> {
    if chars == 0 {
        return Err("an unconditional text of no characters".into());
    }
    if token.len() != net::TEXT as usize {
        return Err(format!("an unconditional token of {}, not {}", token.len(), net::TEXT));
    }
    let mut out = Vec::with_capacity(token.len() * chars as usize);
    for &value in token {
        out.extend(std::iter::repeat_n(value, chars as usize));
    }
    Ok(out)
}

/// The unconditional branch's style values, `[256, 1, 50]` and already transposed in the file.
pub fn unconditional_style(weights: Reader) -> Result<Vec<f32>, String> {
    weights.fp16(net::HOST_STYLE_TOKEN, &[net::STYLE, net::STYLE_TOKENS])
}

/// The folded style keys for one guidance branch, `[4 * 256, 1, 50]`.
///
/// `tanh(W_key . style_key + b_key)`, all constant, so the converter evaluates it. The four style
/// attentions share one `style_key` but each has its own `W_key`, so this is four 256-channel
/// blocks stacked; the plan slices its own out. The two branches have different `style_key`s,
/// which is the only structural difference between them — everything else is an input, so one
/// plan serves both.
pub fn style_keys(weights: Reader, conditional: bool) -> Result<Vec<f32>, String> {
    let index = if conditional {
        net::HOST_KEYS_CONDITIONAL
    } else {
        net::HOST_KEYS_UNCONDITIONAL
    };
    weights.fp16(index, &[net::STYLE * net::MAIN_BLOCKS as u32, net::STYLE_TOKENS])
}

/// Entries in the codepoint table: every code unit of the Basic Multilingual Plane.
pub const INDEXER_ENTRIES: usize = 65_536;

/// The characters the model treats as ending a sentence, so [`to_ids`] adds no period after one.
///
/// Exactly `UnicodeProcessor`'s `_ENDING_PUNCTUATION_PATTERN`, including the CJK and guillemet
/// closers — this is the 31-language model, and a Japanese sentence ends in `。` not `.`.
const TERMINAL: [char; 20] = [
    '.', '!', '?', ';', ':', ',', '\'', '"', ')', ']', '}', '…', '。', '」', '』', '】', '〉',
    '》', '›', '»',
];

/// Whether `codepoint` is in one of the emoji blocks the SDK strips.
fn is_emoji(codepoint: char) -> bool {
    matches!(codepoint as u32,
        0x1F600..=0x1F64F      // emoticons
        | 0x1F300..=0x1F5FF    // symbols and pictographs
        | 0x1F680..=0x1F6FF    // transport and map
        | 0x1F700..=0x1F8FF
        | 0x1F900..=0x1F9FF
        | 0x1FA00..=0x1FAFF
        | 0x2600..=0x26FF
        | 0x2700..=0x27BF
        | 0x1F1E6..=0x1F1FF)   // regional indicators
}

/// The SDK's single-character substitutions, or `None` to leave the character as it is.
///
/// `U+2011` is in the SDK's table and never fires: NFKD, which runs ahead of this, has already
/// turned it into `U+2010`. Kept anyway, so this reads as the port of that table it is.
fn substitute(codepoint: char) -> Option<char> {
    Some(match codepoint {
        '\u{2013}' | '\u{2011}' | '\u{2014}' => '-',
        '\u{00AF}' | '_' | '[' | ']' | '|' | '/' | '#' | '→' | '←' => ' ',
        '\u{201C}' | '\u{201D}' => '"',
        '\u{2018}' | '\u{2019}' | '\u{00B4}' | '`' => '\'',
        _ => return None,
    })
}

/// The text clean-up `supertonic`'s Python SDK does before indexing, minus the NFKD it opens
/// with — see [`to_ids`] for why that half stays on the Kotlin side.
///
/// Ported from `UnicodeProcessor._preprocess_text`, in its order, because the order is load
/// bearing: `/` becomes a space here, so this has to run *before* `to_ids` wraps the text in
/// `</xx>` or the closing tag would be shredded.
///
/// None of it is cosmetic. The model has no token for `♥` or an emoji and would drop them
/// mid-word; `"` and `“` are different codepoints and only one is in the table; and a sentence
/// with no final punctuation is out of distribution for a model trained on sentences, which
/// reads as a clipped or run-on ending rather than as an error.
///
/// The trailing period is *not* added here — [`to_ids`] adds it after deciding whether anything
/// in the text mapped, so that unreadable text is refused rather than synthesised as one dot.
pub fn normalise(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for codepoint in text.chars() {
        if is_emoji(codepoint) || matches!(codepoint, '♥' | '☆' | '♡' | '©' | '\\') {
            continue;
        }
        out.push(substitute(codepoint).unwrap_or(codepoint));
    }

    // Expansions, then the spacing fixes that a stray space before punctuation would leave.
    // Repeated until none remains: a run of spaces before a mark would otherwise
    // lose only its last space and keep the rest.
    out = out.replace('@', " at ").replace("e.g.,", "for example, ").replace("i.e.,", "that is, ");
    for mark in [',', '.', '!', '?', ';', ':', '\''] {
        while out.contains(&format!(" {mark}")) {
            out = out.replace(&format!(" {mark}"), &mark.to_string());
        }
    }

    // Runs of one quote character collapse to a single one; `` ` `` is already an apostrophe.
    let mut collapsed = String::with_capacity(out.len());
    for codepoint in out.chars() {
        let repeated = matches!(codepoint, '"' | '\'') && collapsed.ends_with(codepoint);
        if !repeated {
            collapsed.push(codepoint);
        }
    }

    collapsed.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Character ids for text that is **already NFKD-decomposed**, wrapped in its language tag and
/// dropping what the model has no token for.
///
/// `indexer` is the voice bundle's `unicode_indexer.bin`: [`INDEXER_ENTRIES`] little-endian
/// `int16`, one per BMP codepoint, `-1` where there is no token. 8,321 of them are mapped.
///
/// # The language tag is not optional, and getting it wrong is silent
///
/// Supertonic 3 is the 31-language model, and it was trained with every utterance wrapped as
/// `<en>text</en>`. `language` is the ISO-639-1 code, or `na` for one the model does not list.
///
/// The tag is not a special token with a row of its own — it is literally the characters `<`,
/// `e`, `n` and `>` through this same table, which is why it needs no re-export. That is also
/// what makes omitting it so expensive to find: the ids stay in range, every net still matches
/// onnxruntime to five decimal places, and the model reads the sentence in confident,
/// correctly-timed gibberish. The symptom is fluent-sounding speech that is not words. Untagged,
/// two noise draws of one sentence agreed spectrally at 0.36; tagged, 0.70 — a conditioned flow
/// says the same thing whatever the noise, and that ratio is the cheapest check that this
/// argument is still being threaded through.
///
/// # NFKD is the caller's job, and it is not optional either
///
/// The model has no precomposed accents: `U+00E9` is unmapped while `e` and `U+0301` are both
/// first-class tokens, and Hangul syllables map only through the Jamo block. So `café`, `über`,
/// `niño`, `안녕` and `привет` index completely under NFKD and partially or not at all otherwise.
/// The Kotlin side calls `java.text.Normalizer.normalize(text, Form.NFKD)`, which is a platform
/// API and free; doing it here would mean carrying Unicode decomposition tables in the APK.
///
/// Everything the SDK does *after* NFKD is [`normalise`], which runs here rather than on the
/// Kotlin side so the host tests and the reference harness see the same front end the app does.
///
/// # Unmapped codepoints are dropped, not substituted
///
/// There is no unknown token to substitute. Dropping loses a character; mapping to something
/// else would mispronounce it, and mapping past the table would read the sentence token — see
/// [`crate::nets::supertonic_duration`]. Text of which *nothing* maps is refused rather than
/// synthesised as an empty tag pair.
pub fn to_ids(indexer: &[u8], text: &str, language: &str) -> Result<Vec<u32>, String> {
    if indexer.len() != INDEXER_ENTRIES * 2 {
        return Err(format!(
            "a codepoint table of {} bytes, not {}",
            indexer.len(),
            INDEXER_ENTRIES * 2
        ));
    }
    // Every code the model knows is two ASCII lowercase letters, `na` included. Checked because
    // a malformed tag indexes cleanly and then mispronounces the whole utterance.
    if language.len() != 2 || !language.bytes().all(|b| b.is_ascii_lowercase()) {
        return Err(format!("a language code of {language:?}, not two lowercase letters"));
    }
    let index = |text: &str| {
        text.chars()
            .filter_map(|codepoint| {
                let entry = codepoint as usize;
                // Astral-plane characters — emoji, most CJK extensions — are outside the table
                // entirely rather than mapped to -1.
                if entry >= INDEXER_ENTRIES {
                    return None;
                }
                let at = entry * 2;
                let token = i16::from_le_bytes([indexer[at], indexer[at + 1]]);
                (token >= 0).then_some(token as u32)
            })
            .collect::<Vec<u32>>()
    };

    // The tag is indexed apart from the text so that text which maps to nothing is still refused.
    // Tagged, the ids would never be empty, and the model would read out an empty utterance.
    let cleaned = normalise(text);
    let mut body = index(&cleaned);
    if body.is_empty() {
        return Err("nothing in this text is in the model's vocabulary".into());
    }
    // A sentence the model can read, so now it is worth giving it an ending. Added after the
    // emptiness check so unreadable text is refused rather than spoken as a lone full stop.
    if !cleaned.ends_with(TERMINAL) {
        body.extend(index("."));
    }
    let mut ids = index(&format!("<{language}>"));
    ids.extend(body);
    ids.extend(index(&format!("</{language}>")));
    Ok(ids)
}

/// The speed the SDK reads at by default, which the predicted duration is divided by.
///
/// `supertonic`'s Python SDK defaults `synthesize(speed=1.05)` and calls values near it "more
/// natural speech"; the duration predictor is trained against un-sped reference audio, so
/// reading its answer literally is a 5% drawl. It divides the seconds, so a *larger* speed is a
/// *shorter* utterance.
pub const SPEED: f32 = 1.05;

/// Sampler steps per utterance.
///
/// Measured, not chosen: at four or fewer the waveform's peak goes above 1.0, sixteen is the floor
/// for a stable level, and audio at 16 correlates with audio at 32 at only 0.883. There is no
/// cheap-steps escape hatch here, which with two guidance branches per step means 32 passes of
/// [`crate::nets::supertonic_sampler`] for one sentence.
///
/// The 0.883 is not a quality argument either way. It was once read as one, and 32 was tried
/// against speech that turned out to be garbled for an unrelated reason — see [`to_ids`].
pub const STEPS: u32 = 16;

/// The GPU stages, as a trait, so the sequencing below is host-testable against stubs.
///
/// The same shape `post::ocr` uses, and for the same reason: the order of the
/// four nets, the length arithmetic between them and the sampler loop are where the mistakes are,
/// and none of them needs a device to check.
pub trait Stages {
    /// The duration predictor over `chars + 1` id lanes and a `[128]` style, returning the one
    /// value [`crate::nets::supertonic_duration::seconds`] exponentiates.
    fn duration(&mut self, lanes: &[f32], style: &[f32]) -> Result<f32, String>;

    /// The text encoder, returning `[256, chars]`.
    fn text(&mut self, lanes: &[f32], style: &[f32]) -> Result<Vec<f32>, String>;

    /// [`Stages::text`], told the `(frames, chars)` the sampler will run at next, so a stage can
    /// prepare the sampler's plan while the text encoder runs. Provided: plain [`Stages::text`].
    fn text_then(
        &mut self,
        lanes: &[f32],
        style: &[f32],
        sampler_shape: (u32, u32),
    ) -> Result<Vec<f32>, String> {
        let _ = sampler_shape;
        self.text(lanes, style)
    }

    /// One guidance branch of the sampler, returning `[144, frames]`.
    #[allow(clippy::too_many_arguments)]
    fn sampler(
        &mut self,
        latent: &[f32],
        text: &[f32],
        keys: &[f32],
        style: &[f32],
        shifts: &[f32],
        query_angles: &[f32],
        key_angles: &[f32],
    ) -> Result<Vec<f32>, String>;

    /// Both guidance branches in one call, returning `[conditional, unconditional]`.
    ///
    /// Provided, so a stage that can run them together overrides it to do one submit
    /// for both — see `bridge.rs`, where the dual plan halves the sampler's submits.
    /// There is no default body on purpose: the two-call spelling is three lines, and
    /// a default would hide whether a stage actually fused them. Every implementor
    /// spells out which path it takes.
    #[allow(clippy::too_many_arguments)]
    fn sampler_both(
        &mut self,
        latent: &[f32],
        conditional_text: &[f32],
        conditional_keys: &[f32],
        conditional_style: &[f32],
        unconditional_text: &[f32],
        unconditional_keys: &[f32],
        unconditional_style: &[f32],
        shifts: &[f32],
        query_angles: &[f32],
        key_angles: &[f32],
    ) -> Result<[Vec<f32>; 2], String>;

    /// The vocoder over a `[144, frames]` latent, returning `frames * SAMPLES_PER_FRAME` samples.
    fn vocoder(&mut self, latent: &[f32], frames: u32) -> Result<Vec<f32>, String>;
}

/// Everything the sampler needs from the weights file that a shader never sees, read once.
///
/// [`synthesise`] takes this rather than a [`Reader`] so the sequencing can be tested against
/// stubs, and so the file is walked once per voice instead of once per step.
pub struct Conditioning {
    /// The rotary `theta`, `[32]`.
    pub theta: Vec<f32>,
    /// The per-block timestep shifts, one `[4 * 512]` per step.
    pub shifts: Vec<Vec<f32>>,
    /// The folded conditional style keys, `[4 * 256, 50]`.
    pub conditional_keys: Vec<f32>,
    /// The folded unconditional style keys, `[4 * 256, 50]`.
    pub unconditional_keys: Vec<f32>,
    /// `text_special_token`, `[256]`, to broadcast over the text positions.
    pub text_token: Vec<f32>,
    /// `style_value_special_token`, `[256, 50]`.
    pub unconditional_style: Vec<f32>,
}

impl Conditioning {
    /// Read all of it, for a sampler run of [`STEPS`] steps.
    pub fn read(weights: Reader) -> Result<Conditioning, String> {
        Ok(Conditioning {
            theta: weights.fp16(net::HOST_THETA, &[net::FREQUENCIES])?,
            shifts: (0..STEPS)
                .map(|step| time_shifts(weights, step, STEPS))
                .collect::<Result<_, _>>()?,
            conditional_keys: style_keys(weights, true)?,
            unconditional_keys: style_keys(weights, false)?,
            text_token: weights.fp16(net::HOST_TEXT_TOKEN, &[net::TEXT])?,
            unconditional_style: unconditional_style(weights)?,
        })
    }
}

/// A voice: the two style tensors the nets take as inputs, already in this runtime's layout.
pub struct Voice {
    /// `style_dp` flattened to 128, for the duration predictor.
    pub duration: Vec<f32>,
    /// `style_ttl` transposed to `[256, 50]`, for the text encoder and the sampler.
    pub text: Vec<f32>,
}

include!("supertonic_part1.rs");
include!("supertonic_part2.rs");
#[cfg(test)]
#[path = "supertonic_tests.rs"]
mod supertonic_tests;
