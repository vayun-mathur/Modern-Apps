//! 16 kHz PCM to log-mel frames, on the CPU.
//!
//! A port of Google's TFLM "microfrontend" op chain — `Framer`, `Window`, `FftAutoScale`,
//! `Rfft`, `Energy`, `FilterBank`, `FilterBankSquareRoot`, `FilterBankLog` — which is the
//! front end of the Now Playing music models in [`crate::nets::nnfp`]. It is written against
//! a [`Config`] rather than against that one model, because the same chain is what any
//! keyword spotter or pitch tracker here would want.
//!
//! # Why this is not a shader
//!
//! One frame is a 512-point FFT and 119 multiply-accumulates: a few thousand flops every
//! 10 ms. The dispatch and readback of a compute pass would cost more than the arithmetic, and
//! the host is where a sign error in a window or an off-by-one in a filter bank actually gets
//! caught. [`crate::preprocess`] is not a shader for the same reason.
//!
//! # Fixed point, and where this deliberately leaves it
//!
//! The device pipeline is integer end to end. `FftAutoScale` finds the left shift `s` that puts
//! the loudest sample just below i16 full scale, applies it so the i16 FFT keeps its precision,
//! and hands `s` to `FilterBankSquareRoot`, which shifts it back out. Those two cancel exactly:
//! energy is `2^(2s) |X|^2` because the transform is linear and energy quadratic, its square
//! root is `2^s |X|`, and `>> s` recovers `|X|`. So block floating point buys precision for an
//! i16 FFT and changes nothing else. Computing in f32 makes it unnecessary, and this module
//! drops it. Two more integer stages go the same way: the u64 integer square root becomes
//! [`f32::sqrt`], and Google's fixed-point `Log32` polynomial becomes [`f32::ln`].
//!
//! What does **not** cancel is the fixed-point transform's own `1 / fft_size` attenuation, which
//! a float implementation must apply explicitly. See [`Config::fft_gain`].
//!
//! All three departures **reduce** error rather than introduce it, and `output_scale = 1600` —
//! which is what ties this front end to the networks, whose input quantises at exactly
//! `1 / 1600` — is preserved, because these values are in natural-log units either way.
//!
//! Two quantisations are kept, because they are not rounding error but what the signal has
//! actually become by the time a network sees it:
//!
//! * the `>> 12` after windowing, which is real quantisation of the windowed samples;
//! * the 12-bit truncated filter-bank weights, where `weights[j] + unweights[j]` is 4095 and
//!   not 4096.
//!
//! **This is therefore not bit-exact with the DSP, and has never been diffed against it.** It
//! is the same pipeline computed more accurately. The tests below check it against an
//! independently written reference for the pipeline as specified, which is a different and
//! weaker claim.
//!
//! # The tables are computed, not shipped
//!
//! [`mel_breakpoints`] and [`hann_window`] regenerate the six constant tensors that the Now
//! Playing `.tflite` carries as literals. The tests assert they reproduce those tensors **bit
//! for bit**, which is both the correctness check and the reason a caller may ask for different
//! band limits and still get a table the same code path would have produced. The mel arithmetic
//! must be f32 throughout: in f64 several breakpoints land one least-significant bit away,
//! because the truncation to 12 bits happens to sit on a boundary.

/// How PCM becomes log-mel frames.
///
/// [`NOW_PLAYING`] is what the music models were trained with. The fields are public so a
/// different model can ask for a different band, rate or resolution.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Config {
    /// Samples per second of the incoming PCM.
    pub sample_rate: u32,
    /// Samples per analysis window.
    pub window_samples: usize,
    /// Samples between the start of one window and the start of the next.
    pub hop_samples: usize,
    /// FFT length. At least `window_samples`, a power of two; the window is zero padded to it.
    pub fft_size: usize,
    /// Mel channels out. The filter bank uses `channels + 1` bands to make them.
    pub channels: usize,
    /// Low edge of the mel band, in Hz.
    pub lower_band_limit: f32,
    /// High edge of the mel band, in Hz.
    pub upper_band_limit: f32,
    /// Fractional bits in the window table, so a tap of 1.0 is `1 << window_bits`.
    pub window_bits: u32,
    /// Multiplier on the transform output, before energy is taken.
    ///
    /// The device's `Rfft` attenuates by `1 / fft_size`; a plain f32 transform does not. The
    /// factor is not cosmetic. It is a *constant offset* of `ln(fft_size)` — 6.24 nats, 41.6% of
    /// the usable range — added uniformly to every channel, which pins every frame to
    /// [`Config::ceiling`]. Omitting it produces a pipeline that looks like it works until you
    /// notice every output is identical.
    ///
    /// **This is forced by the model, not inferred from the kernel.** `Rfft` is declared
    /// i16 to i16, and `FftAutoScale` has just normalised its input to near full i16 scale, so a
    /// unit-gain DC bin would be `512 * 32767` — a 512x overflow of the declared output type.
    /// The op cannot have unit gain. That the mechanism happens to be a fixed-point kissfft
    /// halving at each radix-2 stage is then a detail; the attenuation is structural.
    ///
    /// Two independent anchors agree. `FftAutoScale` would have nothing to protect if the
    /// transform did not shrink its input. And the classifier's input quantisation is exactly
    /// `15 / 255` at zero point -128, so its design range is precisely `[0, 15]` nats — content
    /// that belongs in 8..15 lands at 14.24..21.24 without this factor, which is the saturation
    /// that first exposed it.
    pub fft_gain: f32,
    /// Left shift applied inside the log, compensating precision lost in the square root.
    pub log_correction_bits: u32,
    /// Upper clamp on an output value, in natural-log units.
    ///
    /// Both networks quantise this frame to int8 at `scale = 0.05882353, zero_point = -128`,
    /// so anything above `255 * 0.05882353` saturates there. Reproducing the clamp keeps a loud
    /// input from behaving differently here than on the device. [`f32::INFINITY`] for none.
    pub ceiling: f32,
}

/// The front end the Now Playing music detector and fingerprinter were trained with.
///
/// Every value is recovered from `music_detector.sound_model`: 25 ms windows at a 10 ms hop,
/// 32 mel channels spanning 60–3800 Hz off a 512-point FFT.
pub const NOW_PLAYING: Config = Config {
    sample_rate: 16_000,
    window_samples: 400,
    hop_samples: 160,
    fft_size: 512,
    channels: 32,
    lower_band_limit: 60.0,
    upper_band_limit: 3800.0,
    window_bits: 12,
    fft_gain: 1.0 / 512.0,
    log_correction_bits: 3,
    ceiling: 15.0,
};

/// `1127 * ln(1 + hz / 700)`, in f32.
///
/// The precision is load bearing rather than incidental: the same formula evaluated in f64 and
/// rounded at the end moves several of the 119 filter-bank weights by one, because the
/// truncation to 12 bits sits on a boundary for them. The tests pin that.
fn mel(hz: f32) -> f32 {
    1127.0f32 * (hz / 700.0f32).ln_1p()
}

/// The triangular mel filter bank, as the integer tables the device holds.
///
/// `channels + 1` bands partition the live FFT bins. Band `b` contributes `weights[j]` to
/// channel `b - 1` and `unweights[j]` to channel `b`, so band 0's weighted half falls off the
/// bottom and is discarded — that is what turns 33 bands into 32 channels.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FilterBank {
    /// First FFT bin of each band, `channels + 1` entries.
    pub freq_starts: Vec<usize>,
    /// Bins in each band, `channels + 1` entries, summing to `weights.len()`.
    pub widths: Vec<usize>,
    /// Rising-edge weight per live bin, truncated to `window_bits` fractional bits.
    pub weights: Vec<u16>,
    /// Falling-edge weight per live bin. `weights[j] + unweights[j]` is one below
    /// `1 << window_bits`, because both halves truncate.
    pub unweights: Vec<u16>,
    /// First FFT bin the bank reads, `(int)(1.5 + lower_band_limit / hz_per_bin)`.
    pub start_index: usize,
    /// One past the last FFT bin the bank reads.
    pub end_index: usize,
}

/// The filter bank `config` describes, or why it cannot be built.
pub fn mel_breakpoints(config: &Config) -> Result<FilterBank, String> {
    if config.fft_size == 0 || config.channels == 0 {
        return Err("a filter bank needs a non-zero FFT size and channel count".to_string());
    }
    let hz_per_bin = config.sample_rate as f32 / config.fft_size as f32;
    let low = mel(config.lower_band_limit);
    let high = mel(config.upper_band_limit);
    if high <= low || !high.is_finite() {
        return Err(format!(
            "the band {}..{} Hz is empty in mel",
            config.lower_band_limit, config.upper_band_limit
        ));
    }
    let spacing = (high - low) / (config.channels as f32 + 1.0);
    // Centres for bands 0..=channels. The last lands exactly on `high` by construction.
    let centres: Vec<f32> = (0..=config.channels).map(|c| low + spacing * (c + 1) as f32).collect();

    let start_index = (1.5 + config.lower_band_limit / hz_per_bin) as usize;
    let bins = config.fft_size / 2 + 1;
    let mut end_index = start_index;
    while end_index < bins && mel(end_index as f32 * hz_per_bin) <= high {
        end_index += 1;
    }
    if end_index <= start_index {
        return Err(format!("no FFT bin falls in {}..{} Hz", config.lower_band_limit, high));
    }

    let one = (1u32 << config.window_bits) as f32;
    let mut widths = vec![0usize; config.channels + 1];
    let mut weights = Vec::with_capacity(end_index - start_index);
    let mut unweights = Vec::with_capacity(end_index - start_index);
    let mut band = 0usize;
    for bin in start_index..end_index {
        let here = mel(bin as f32 * hz_per_bin);
        while band < config.channels && here > centres.get(band).copied().unwrap_or(high) {
            band += 1;
        }
        let centre = centres.get(band).copied().unwrap_or(high);
        let rising = (centre - here) / spacing;
        weights.push((rising * one) as u16);
        unweights.push(((1.0 - rising) * one) as u16);
        if let Some(width) = widths.get_mut(band) {
            *width += 1;
        }
    }
    // Bands are contiguous from `start_index`, so a start is the running total of the widths
    // before it. Writing it that way rather than recording the first bin of each band keeps an
    // empty band's start meaningful instead of leaving it at zero.
    let mut next = start_index;
    let freq_starts = widths
        .iter()
        .map(|width| {
            let first = next;
            next += width;
            first
        })
        .collect();
    Ok(FilterBank { freq_starts, widths, weights, unweights, start_index, end_index })
}

/// The analysis window: a *periodic* Hann offset by half a sample, in `window_bits` fixed point.
///
/// `w[i] = floor((0.5 - 0.5 cos(2 pi (i + 0.5) / n)) * 2^bits + 0.5)`. The half-sample offset
/// and the round — rather than a truncate — are both load bearing: the tests pin the table
/// against the one the model ships, which none of the obvious near misses reproduce.
pub fn hann_window(config: &Config) -> Vec<i32> {
    let n = config.window_samples;
    let one = (1u32 << config.window_bits) as f64;
    (0..n)
        .map(|i| {
            let phase = std::f64::consts::TAU * (i as f64 + 0.5) / n as f64;
            ((0.5 - 0.5 * phase.cos()) * one + 0.5).floor() as i32
        })
        .collect()
}

/// A streaming log-mel front end: push PCM, get frames.
///
/// Holds the framer's partial window between calls, so a caller can hand it whatever the audio
/// source produced without aligning to a hop boundary. [`Frontend::reset`] clears that state.
/// Nothing allocates after construction.
pub struct Frontend {
    config: Config,
    window: Vec<i32>,
    bank: FilterBank,
    /// Samples not yet consumed by a frame.
    pending: Vec<i16>,
    spectrum: Vec<f32>,
    scratch: Vec<[f32; 2]>,
    twiddles: Vec<[f32; 2]>,
    reversed: Vec<u32>,
    rising: Vec<f32>,
    falling: Vec<f32>,
    frame: Vec<f32>,
}

impl Frontend {
    /// A front end for `config`, or why that configuration cannot be built.
    pub fn new(config: Config) -> Result<Frontend, String> {
        if config.hop_samples == 0 || config.window_samples == 0 {
            return Err("a front end needs a non-zero window and hop".to_string());
        }
        if config.fft_size < config.window_samples || !config.fft_size.is_power_of_two() {
            return Err(format!(
                "an FFT of {} cannot hold a {}-sample window, or is not a power of two",
                config.fft_size, config.window_samples
            ));
        }
        let bank = mel_breakpoints(&config)?;
        let bands = bank.widths.len();
        let window = hann_window(&config);
        let (twiddles, reversed) = fft_tables(config.fft_size);
        Ok(Frontend {
            spectrum: vec![0.0; config.fft_size / 2 + 1],
            scratch: vec![[0.0, 0.0]; config.fft_size],
            rising: vec![0.0; bands],
            falling: vec![0.0; bands],
            frame: vec![0.0; config.channels],
            pending: Vec::with_capacity(config.window_samples + config.hop_samples),
            window,
            bank,
            twiddles,
            reversed,
            config,
        })
    }

    /// Values in one frame, which is [`Config::channels`].
    pub fn channels(&self) -> usize {
        self.config.channels
    }

    /// Forget the partial window, so the next frame starts from silence.
    pub fn reset(&mut self) {
        self.pending.clear();
    }

    /// The power spectrum of the most recent frame, `fft_size / 2 + 1` bins.
    ///
    /// For a caller that wants the spectrum rather than the mel projection — pitch detection,
    /// say. Only the bins the filter bank reads are computed; the rest stay zero, because
    /// nothing in this pipeline looks at them.
    pub fn spectrum(&self) -> &[f32] {
        &self.spectrum
    }

    /// Append `samples`, emitting every frame that completes into `out`, in order.
    ///
    /// A frame is [`Config::channels`] values of `ln(magnitude)`, floored at zero and clamped to
    /// [`Config::ceiling`]. Returns how many frames were appended.
    pub fn process(&mut self, samples: &[i16], out: &mut Vec<f32>) -> usize {
        self.pending.extend_from_slice(samples);
        let mut produced = 0;
        while self.pending.len() >= self.config.window_samples {
            self.analyse();
            out.extend_from_slice(&self.frame);
            produced += 1;
            let hop = self.config.hop_samples.min(self.pending.len());
            self.pending.copy_within(hop.., 0);
            let keep = self.pending.len() - hop;
            self.pending.truncate(keep);
        }
        produced
    }

    /// Window, transform and project the leading `window_samples` of `pending` into `frame`.
    fn analyse(&mut self) {
        let shift = self.config.window_bits;
        for (slot, (sample, tap)) in
            self.scratch.iter_mut().zip(self.pending.iter().zip(self.window.iter()))
        {
            // The device's `Window` op verbatim: an arithmetic shift, so negatives floor.
            *slot = [((*sample as i32 * *tap) >> shift) as f32, 0.0];
        }
        for slot in self.scratch.iter_mut().skip(self.config.window_samples) {
            *slot = [0.0, 0.0];
        }
        fft(&mut self.scratch, &self.twiddles, &self.reversed);

        let (first_bin, last_bin) = (self.bank.start_index, self.bank.end_index);
        // Energy is quadratic, so the transform's gain is squared here rather than applied to
        // the coefficients — one multiply per bin instead of two.
        let gain = self.config.fft_gain * self.config.fft_gain;
        for (bin, slot) in self.spectrum.iter_mut().enumerate() {
            *slot = match self.scratch.get(bin) {
                Some([re, im]) if bin >= first_bin && bin < last_bin => (re * re + im * im) * gain,
                _ => 0.0,
            };
        }

        let mut at = 0usize;
        for (band, width) in self.bank.widths.iter().enumerate() {
            let first = self.bank.freq_starts.get(band).copied().unwrap_or(0);
            let mut up = 0.0f32;
            let mut down = 0.0f32;
            for step in 0..*width {
                let power = self.spectrum.get(first + step).copied().unwrap_or(0.0);
                up += self.bank.weights.get(at + step).copied().unwrap_or(0) as f32 * power;
                down += self.bank.unweights.get(at + step).copied().unwrap_or(0) as f32 * power;
            }
            if let Some(slot) = self.rising.get_mut(band) {
                *slot = up;
            }
            if let Some(slot) = self.falling.get_mut(band) {
                *slot = down;
            }
            at += width;
        }

        let correction = (1u32 << self.config.log_correction_bits) as f32;
        let ceiling = self.config.ceiling;
        for (channel, slot) in self.frame.iter_mut().enumerate() {
            // Channel `c` is band `c + 1`'s rising edge plus band `c`'s falling edge. That
            // discards band 0's rising half, which is the triangle below the first centre, and
            // is what makes `channels + 1` bands into `channels` outputs.
            let energy = self.rising.get(channel + 1).copied().unwrap_or(0.0)
                + self.falling.get(channel).copied().unwrap_or(0.0);
            // `FilterBankLog` floors at an argument of one, which in log units is zero — the
            // same place `ln` turns negative, so one clamp covers both ends.
            *slot = (correction * energy.sqrt()).ln().clamp(0.0, ceiling);
        }
    }
}

/// Twiddle factors `exp(-2 pi i k / n)` for `k < n / 2`, and the bit-reversal permutation.
///
/// `pub(crate)` because [`crate::logmel`] needs the same 512-point transform under a different
/// mel chain, and two Cooley-Tukeys in one crate is one too many.
pub(crate) fn fft_tables(n: usize) -> (Vec<[f32; 2]>, Vec<u32>) {
    let twiddles = (0..n / 2)
        .map(|k| {
            let angle = -std::f64::consts::TAU * k as f64 / n as f64;
            [angle.cos() as f32, angle.sin() as f32]
        })
        .collect();
    let bits = n.trailing_zeros();
    let reversed = (0..n).map(|i| (i as u32).reverse_bits() >> (32 - bits)).collect();
    (twiddles, reversed)
}

/// In-place iterative radix-2 Cooley-Tukey, decimation in time.
///
/// Nothing here is worth specialising: 512 points is 2,304 butterflies once per 10 ms of audio.
/// A real-input transform would halve that and is not worth the packing.
///
/// `pub(crate)` for [`crate::logmel`]. See [`fft_tables`].
pub(crate) fn fft(data: &mut [[f32; 2]], twiddles: &[[f32; 2]], reversed: &[u32]) {
    let n = data.len();
    for i in 0..n {
        let j = reversed.get(i).copied().unwrap_or(0) as usize;
        if j > i {
            data.swap(i, j);
        }
    }
    let mut span = 2usize;
    while span <= n {
        let half = span / 2;
        let stride = n / span;
        let mut base = 0usize;
        while base < n {
            for k in 0..half {
                let Some(&[wr, wi]) = twiddles.get(k * stride) else { continue };
                let Some(&[br, bi]) = data.get(base + k + half) else { continue };
                let Some(&[ar, ai]) = data.get(base + k) else { continue };
                let vr = br * wr - bi * wi;
                let vi = br * wi + bi * wr;
                if let Some(slot) = data.get_mut(base + k) {
                    *slot = [ar + vr, ai + vi];
                }
                if let Some(slot) = data.get_mut(base + k + half) {
                    *slot = [ar - vr, ai - vi];
                }
            }
            base += span;
        }
        span *= 2;
    }
}

include!("microfrontend_part1.rs");
#[cfg(test)]
#[path = "microfrontend_tests.rs"]
mod microfrontend_tests;
