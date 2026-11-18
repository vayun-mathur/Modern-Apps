//! 16 kHz PCM to 128-bin HTK log-mel frames, for Gemma 4's audio encoder.
//!
//! A port of `Gemma4AudioFeatureExtractor._extract_spectrogram` from transformers, which is the
//! Universal Speech Model front end. The checkpoint ships no feature-extractor settings —
//! `preprocessor_config.json` carries only `processor_class` — so every constant below is the
//! reference default rather than a choice made here.
//!
//! # This is not [`crate::microfrontend`]
//!
//! Both turn 16 kHz PCM into log-mel frames off a 512-point transform at a 160-sample hop, and
//! there the resemblance stops. That module is Google's TFLM integer op chain: breakpoint
//! filters over a bin range, *energy* rather than magnitude, a square root, and a scaled
//! fixed-point log. This one is HTK triangles over all 257 bins, magnitude, and a plain
//! `ln(x + 1e-3)`. The two produce different numbers from the same audio and neither is a
//! parameterisation of the other, so they stay apart. What they do share is the transform, and
//! `microfrontend::fft` is `pub(crate)` for exactly that.
//!
//! # The chain, and the two places it is easy to get wrong
//!
//! Prepend `FRAME_SAMPLES / 2` zeros so frame 0 is *centred* on sample 0 — `sl.STFT`'s
//! `semicausal` padding — then frame, window, transform, project, log.
//!
//! The first trap is the frame count. The reference unfolds at `FRAME_SAMPLES + 1` and then
//! throws the extra sample away, because that slot is where HTK-flavour preemphasis would have
//! read the previous sample from. Preemphasis is off here, so the sample is unused — but the
//! *count* still comes from the longer window, and framing at 320 gives one frame too many on
//! most lengths. See [`frame_count`].
//!
//! The second is the window. HF's `window_function` builds `np.hanning(n + 1)` and drops the
//! last tap, which is the *periodic* Hann; the symmetric one differs on every tap but the
//! middle and would show up as a small broadband error that looks like rounding.
//!
//! # The bottom filter is empty, and stays empty
//!
//! 128 mel filters from 0 Hz puts the first triangle at 0 -> 13.81 -> 27.89 Hz, entirely below
//! bin 1 at 31.25 Hz. The one bin it does contain is bin 0, where its rising edge is exactly
//! zero — so channel **0** is all zeros, and is `ln(1e-3)` for every input and every frame. The
//! reference warns about this and keeps it; the encoder was trained on 128 channels one of which
//! is constant, so dropping it here would be a silent off-by-one in everything downstream. It is
//! the bottom of the bank rather than the top: the topmost triangle spans 7505..8000 Hz and is
//! the widest column there is.
//!
//! # Where this stops
//!
//! [`LogMel::spectrogram`] is `_extract_spectrogram` and nothing else. The reference's `__call__`
//! also truncates the waveform at 480,000 samples (30 s), zero-pads it to a multiple of 128
//! samples, and zeroes the frames whose analysis window ran off the end of the real audio. That
//! belongs with the encoder's batching rather than here, and is left to the caller.

use crate::microfrontend::{fft, fft_tables};

/// Samples per second the front end expects.
pub const SAMPLE_RATE: u32 = 16_000;
/// Mel channels per frame.
pub const MELS: usize = 128;
/// Analysis window, from `frame_length_ms = 20.0`.
pub const FRAME_SAMPLES: usize = 320;
/// Samples between frames, from `hop_length_ms = 10.0`.
pub const HOP_SAMPLES: usize = 160;
/// `2^ceil(log2(FRAME_SAMPLES))`, with `fft_overdrive` off.
pub const FFT_SIZE: usize = 512;
/// Bins in the one-sided transform.
pub const BINS: usize = FFT_SIZE / 2 + 1;
/// Low edge of the mel band, in Hz.
pub const MIN_HZ: f64 = 0.0;
/// High edge of the mel band, in Hz. Nyquist, so the bank spans the whole spectrum.
pub const MAX_HZ: f64 = 8000.0;
/// Added inside the log, so silence is `ln(1e-3)` rather than negative infinity.
pub const MEL_FLOOR: f32 = 1e-3;

/// `2595 log10(1 + hz / 700)`, the HTK mel scale.
fn hertz_to_mel(hz: f64) -> f64 {
    2595.0 * (1.0 + hz / 700.0).log10()
}

/// The inverse of [`hertz_to_mel`].
fn mel_to_hertz(mel: f64) -> f64 {
    700.0 * (10.0f64.powf(mel / 2595.0) - 1.0)
}

/// How many frames `samples` of audio produce.
///
/// `(samples + 160 - 321) / 160 + 1`, and zero below that. The 321 is the reference's unfold
/// size, `FRAME_SAMPLES + 1`: it reads one sample more than it uses so that preemphasis has a
/// predecessor for the first tap, and the frame count inherits the longer window whether
/// preemphasis is on or off. Using 320 here would claim one extra frame for every length that is
/// not exactly a hop boundary.
pub fn frame_count(samples: usize) -> usize {
    let padded = samples + FRAME_SAMPLES / 2;
    match padded.checked_sub(FRAME_SAMPLES + 1) {
        Some(rest) => rest / HOP_SAMPLES + 1,
        None => 0,
    }
}

/// The periodic Hann window, `0.5 - 0.5 cos(2 pi n / 320)` for `n` in `0..320`.
///
/// Computed in f64 and rounded once, because the reference builds it in f64 and calls
/// `.astype(np.float32)`.
pub fn hann_window() -> Vec<f32> {
    (0..FRAME_SAMPLES)
        .map(|n| {
            let phase = std::f64::consts::TAU * n as f64 / FRAME_SAMPLES as f64;
            (0.5 - 0.5 * phase.cos()) as f32
        })
        .collect()
}

/// The `[BINS, MELS]` triangular filter bank, row major: bin `b`, channel `c` at `b * MELS + c`.
///
/// `MELS + 2` breakpoints spaced evenly in mel between [`MIN_HZ`] and [`MAX_HZ`] and converted
/// back to hertz; the triangle for channel `c` rises from breakpoint `c` to `c + 1` and falls to
/// `c + 2`. Bin centres are `linspace(0, SAMPLE_RATE / 2, BINS)`. Unnormalised — `norm=None` in
/// the reference — so a channel's weights peak at one and do not sum to one.
pub fn mel_filters() -> Vec<f32> {
    let mel_max = hertz_to_mel(MAX_HZ);
    let mel_min = hertz_to_mel(MIN_HZ);
    let step = (mel_max - mel_min) / (MELS + 1) as f64;
    let mut edges: Vec<f64> =
        (0..MELS + 2).map(|i| mel_to_hertz(mel_min + step * i as f64)).collect();
    // `linspace` lands its last point on the endpoint exactly rather than at `start + n * step`.
    if let Some(last) = edges.last_mut() {
        *last = mel_to_hertz(mel_max);
    }

    let bin_width = (SAMPLE_RATE / 2) as f64 / (BINS - 1) as f64;
    let mut bank = vec![0.0f32; BINS * MELS];
    for bin in 0..BINS {
        let hz = bin as f64 * bin_width;
        for channel in 0..MELS {
            let (Some(&left), Some(&centre), Some(&right)) =
                (edges.get(channel), edges.get(channel + 1), edges.get(channel + 2))
            else {
                continue;
            };
            let rising = (hz - left) / (centre - left);
            let falling = (right - hz) / (right - centre);
            if let Some(slot) = bank.get_mut(bin * MELS + channel) {
                *slot = rising.min(falling).max(0.0) as f32;
            }
        }
    }
    bank
}

/// Waveform in, log-mel frames out. Holds the window, the bank and the transform's tables.
///
/// Nothing allocates per frame, but unlike [`crate::microfrontend::Frontend`] this is not a
/// streaming front end: the semicausal padding and the frame count are both properties of the
/// whole utterance, and the encoder consumes the whole utterance at once anyway.
pub struct LogMel {
    window: Vec<f32>,
    filters: Vec<f32>,
    twiddles: Vec<[f32; 2]>,
    reversed: Vec<u32>,
    scratch: Vec<[f32; 2]>,
    magnitude: Vec<f32>,
}

impl Default for LogMel {
    fn default() -> LogMel {
        LogMel::new()
    }
}

impl LogMel {
    /// Build the window, the filter bank and the transform's tables.
    pub fn new() -> LogMel {
        let (twiddles, reversed) = fft_tables(FFT_SIZE);
        LogMel {
            window: hann_window(),
            filters: mel_filters(),
            twiddles,
            reversed,
            scratch: vec![[0.0, 0.0]; FFT_SIZE],
            magnitude: vec![0.0; BINS],
        }
    }

    /// The magnitude spectrum of the most recent frame, [`BINS`] wide.
    ///
    /// Here to bisect a disagreement with the reference: a wrong window and a wrong filter bank
    /// both move the log-mel output and only one of them moves this.
    pub fn magnitude(&self) -> &[f32] {
        &self.magnitude
    }

    /// Append [`frame_count`] frames of [`MELS`] values each to `out`, and return how many.
    ///
    /// `waveform` is the whole utterance at [`SAMPLE_RATE`], nominally in `-1.0..1.0`; the front
    /// end has no gain of its own, so the scale it arrives in is the scale the encoder sees.
    pub fn spectrogram(&mut self, waveform: &[f32], out: &mut Vec<f32>) -> usize {
        let frames = frame_count(waveform.len());
        let pad = FRAME_SAMPLES / 2;
        out.reserve(frames * MELS);
        for frame in 0..frames {
            let start = frame * HOP_SAMPLES;
            for (n, slot) in self.scratch.iter_mut().enumerate().take(FRAME_SAMPLES) {
                // The left pad is not materialised: index into it and read zero.
                let sample = match (start + n).checked_sub(pad) {
                    Some(at) => waveform.get(at).copied().unwrap_or(0.0),
                    None => 0.0,
                };
                *slot = [sample * self.window.get(n).copied().unwrap_or(0.0), 0.0];
            }
            for slot in self.scratch.iter_mut().skip(FRAME_SAMPLES) {
                *slot = [0.0, 0.0];
            }
            fft(&mut self.scratch, &self.twiddles, &self.reversed);
            for (bin, slot) in self.magnitude.iter_mut().enumerate() {
                let [re, im] = self.scratch.get(bin).copied().unwrap_or([0.0, 0.0]);
                *slot = (re * re + im * im).sqrt();
            }

            let base = out.len();
            out.resize(base + MELS, 0.0);
            for (bin, level) in self.magnitude.iter().enumerate() {
                if *level == 0.0 {
                    continue;
                }
                let row = self.filters.get(bin * MELS..bin * MELS + MELS).unwrap_or(&[]);
                for (weight, slot) in row.iter().zip(out.iter_mut().skip(base)) {
                    *slot += weight * level;
                }
            }
            for slot in out.iter_mut().skip(base) {
                *slot = (*slot + MEL_FLOOR).ln();
            }
        }
        frames
    }
}


#[cfg(test)]
#[path = "logmel_tests.rs"]
mod logmel_tests;
