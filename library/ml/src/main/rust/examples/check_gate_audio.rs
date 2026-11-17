//! Measures what the music gate scores on music, speech, noise and silence — offline, no device.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example check_gate_audio
//! cargo run --offline --release -p modelrunner --example check_gate_audio -- song.wav ...
//! ```
//!
//! # What this answers
//!
//! Every other check on `gate.rs` is a *layout* check: 8,200 weights in the order the graph
//! declares them, a forward pass bit-exact against `scripts/ml/gate_reference.py`, a front end
//! bit-exact against numpy. All of that can pass while the detector is useless, because a
//! network that has been transcribed correctly and *scaled* wrongly still produces a number
//! between zero and one for every frame. The unanswered question is empirical: does the score
//! rise on music and stay down on everything else?
//!
//! # Why speech is the control that matters
//!
//! Noise and silence are trivial negatives — anything that responds to spectral structure at all
//! will reject them. Speech is harmonic, band-limited to roughly the same 60–3800 Hz the front
//! end keeps, and temporally structured, so it is the input that separates a music detector from
//! an "is there sound" detector. If speech scores where music scores, the reported separation is
//! not evidence of a working port.
//!
//! # Why every signal is RMS-matched
//!
//! The front end is a log-mel bank with no per-utterance normalisation, so level walks straight
//! into the features. Comparing loud synthetic music against quiet synthetic speech would
//! measure the level difference and report it as discrimination. Everything here is normalised
//! to the same RMS, and `music_chords` is additionally swept across three levels so the level
//! sensitivity is visible rather than hidden.
//!
//! Synthetic audio is a real limitation and is not the same as recordings; pass WAV paths on the
//! command line to add real material. 16-bit PCM only, any rate, mono or stereo.
//!
//! # What it measured, 2026-09-05
//!
//! Run over 12 real recordings (Windows Media ringtones, alarms and chimes), real speech from two
//! Windows SAPI voices, the ASI APK's audio assets, and a synthetic set, all resampled to 16 kHz
//! mono and RMS-matched.
//!
//! **The gate fires on music and rejects speech, decisively.** Median raw score: real ringtones
//! 0.69–0.96, real speech 0.0033–0.0048. Under a debounced latch at threshold 0.90 the real music
//! clips hold the positive state 67–99% of the time and the speech clips hold it 0%. Speech is the
//! hard negative and the separation there is about as clean as it could be.
//!
//! **It does not reject broadband noise.** Pink noise holds 87% duty at threshold 0.58 and still
//! 80% at 0.98, and 47 s of real keyboard clicks holds 89%. Dropping the noise to −50 dBFS barely
//! moves it, so this is not an artefact of testing noise at music's level. No threshold separates
//! music from noise, because the noise score distribution has a long tail into 1.0 rather than a
//! shifted mean.
//!
//! **The shipped latch parameters are unusable as-is**, independent of the threshold.
//! [`Hysteresis::negative_frames_before_event`] is 1, so at 100 Hz the state drops on a single
//! dipping frame and immediately re-arms: every input, including silence-adjacent ones, produces
//! 100–200 positive events per minute. That is why `sweep` reports duty cycle and not event rate.
//!
//! # Two earlier conclusions that were wrong, recorded so they are not re-derived
//!
//! Against synthetic music alone this harness said the gate ranked pink noise *above* music and
//! that the port therefore had a weight-layout or scale bug. Both were artefacts of the
//! synthesiser: `music_chords` scores 0.44 where real ringtones score 0.75–0.96, and
//! `music_harmonics` — a sustained, perfectly stationary chord — scores 0.04, lower than anything
//! but silence. Real music is none of those things. Synthetic positives are not a substitute here
//! and a negative result from them alone should not be trusted.
//!
//! The front end is not implicated either way: `features()` shows a flat bank for noise, a
//! structured one for chords, nothing pinned at the ceiling. And `scripts/ml/gate_reference.py`
//! cannot arbitrate any of this — the forward pass is already bit-exact against it, so both sides
//! would reproduce a shared misreading of the flatbuffer identically.

use modelrunner::gate::{probability, Gate, Hysteresis, Latch, CHANNELS, HOP_SAMPLES};
use modelrunner::microfrontend::{Frontend, NOW_PLAYING};

const RATE: usize = 16_000;
const SECONDS: usize = 12;
const SAMPLES: usize = RATE * SECONDS;

/// Full-scale fraction every generated signal is normalised to before quantisation.
const TARGET_RMS: f32 = 0.1;

fn main() {
    let mut signals: Vec<(String, Vec<i16>)> = Vec::new();

    for (name, samples) in [
        ("silence", silence()),
        ("white_noise", white_noise()),
        ("pink_noise", pink_noise()),
        ("noise_low_band", band_noise(60.0, 500.0)),
        ("noise_high_band", band_noise(2000.0, 3800.0)),
        ("tone_440", tone()),
        ("music_harmonics", harmonic_stack()),
        ("music_chords", chords()),
        ("music_melody", melody()),
        ("music_full_mix", full_mix()),
        ("speech_male", speech(112.0, true)),
        ("speech_female", speech(196.0, true)),
        ("speech_continuous", speech(120.0, false)),
        ("babble_two_voices", babble()),
        ("am_noise", am_noise()),
    ] {
        signals.push((name.to_string(), quantize(&samples, TARGET_RMS)));
    }

    // The same music at three levels, to show whether the score is tracking loudness.
    for level in [0.02f32, 0.05, 0.30] {
        signals.push((format!("music_chords@rms{level:.2}"), quantize(&chords(), level)));
    }

    // Pink noise at four levels. Everything above is matched at -20 dBFS, which is loud for room
    // noise and quiet for music, so a noise false-accept measured there may not be one in a real
    // room. This is the control that says whether the noise problem survives realistic levels.
    for level in [0.003f32, 0.01, 0.03, 0.10] {
        signals.push((format!("noise_pink@rms{level:.3}"), quantize(&pink_noise(), level)));
    }

    for path in std::env::args().skip(1) {
        // The label drives the grouping in `summarise`, so real files are named with the same
        // `music_` / `speech_` / `noise_` prefixes the synthetic ones use.
        let label = std::path::Path::new(&path)
            .file_stem()
            .map(|stem| stem.to_string_lossy().into_owned())
            .unwrap_or_else(|| path.clone());
        match read_wav(&path) {
            Ok(samples) => signals.push((label, samples)),
            Err(error) => println!("skipping {path}: {error}"),
        }
    }

    println!(
        "{:<26} {:>6} {:>7} {:>7} {:>7} {:>7} {:>7} {:>7} {:>7} {:>6} {:>7}",
        "input", "hops", "min", "mean", "p50", "p90", "max", "sd", "d/hop", "rail%", "events"
    );
    println!("{}", "-".repeat(110));

    let mut rows = Vec::new();
    for (name, pcm) in &signals {
        let Some(row) = run(pcm) else {
            println!("{name:<26} too short to warm the gate up");
            continue;
        };
        println!(
            "{:<26} {:>6} {:>7.4} {:>7.4} {:>7.4} {:>7.4} {:>7.4} {:>7.4} {:>7.4} {:>5.1}% {:>7}",
            name,
            row.hops,
            row.min,
            row.mean,
            row.p50,
            row.p90,
            row.max,
            row.sd,
            row.step,
            row.rail * 100.0,
            row.events
        );
        rows.push((name.clone(), row));
    }

    println!();
    summarise(&rows);
    println!();
    sweep(&rows);
    println!();
    features(&signals);
    println!();
    trajectory(&signals);
}

/// Fraction of time the latch holds the positive state, at a range of positive thresholds.
///
/// This is the decision-relevant view, and it is deliberately *not* an event count. The shipped
/// [`Hysteresis::negative_frames_before_event`] is 1, so at 100 Hz the state drops on a single
/// dipping frame and re-arms immediately; event rates then land at 100–200 per minute for almost
/// every input and measure the latch's chatter rather than the threshold. Duty cycle — how much
/// of the clip the app would believe music is playing — is monotone in the threshold and is what
/// a caller actually consumes.
///
/// `DEBOUNCED` is the same sweep with a hold time, to separate "the threshold is wrong" from
/// "the latch parameters are wrong".
fn sweep(rows: &[(String, Row)]) {
    for (label, hold, band) in [("SHIPPED latch", 1u32, 0.01f32), ("DEBOUNCED latch", 50, 0.05)] {
        println!("active duty cycle %, {label} (hold {hold} frames, band {band:.2})");
        let thresholds = [0.50f32, 0.58, 0.70, 0.80, 0.90, 0.95, 0.98];
        print!("{:<26}", "");
        for threshold in thresholds {
            print!("{threshold:>8.2}");
        }
        println!();
        println!("{}", "-".repeat(26 + 8 * thresholds.len()));

        let mut totals = vec![(0.0f32, 0.0f32); thresholds.len()];
        for (name, row) in rows {
            print!("{name:<26}");
            for (column, threshold) in thresholds.iter().enumerate() {
                let duty = replay(&row.scores, *threshold, hold, band);
                print!("{:>8.1}", duty * 100.0);
                // Everything not named `music_` counts against the false-accept budget.
                let slot = &mut totals[column];
                if name.starts_with("music_") {
                    slot.0 += duty;
                } else {
                    slot.1 += duty;
                }
            }
            println!();
        }

        let music = rows.iter().filter(|(n, _)| n.starts_with("music_")).count() as f32;
        let other = rows.len() as f32 - music;
        for (heading, pick) in [
            ("MEAN music (want high)", 0usize),
            ("MEAN non-music (want low)", 1),
        ] {
            print!("{heading:<26}");
            for total in &totals {
                let (sum, count) = if pick == 0 { (total.0, music) } else { (total.1, other) };
                print!("{:>8.1}", 100.0 * sum / count);
            }
            println!();
        }
        println!();
    }
}

/// Re-run the latch over an already-computed score stream, returning the active duty cycle.
fn replay(scores: &[i16], positive_threshold: f32, hold: u32, band: f32) -> f32 {
    let mut latch = Latch::new(Hysteresis {
        positive_threshold,
        negative_threshold: positive_threshold - band,
        negative_frames_before_event: hold,
        ..Hysteresis::SHIPPED
    });
    let active = scores.iter().filter(|&&score| latch.push(score).active).count();
    active as f32 / scores.len() as f32
}

/// The first 40 reported hops of a few inputs, because a distribution hides whether the score is
/// tracking anything or just rattling. At 100 Hz these forty numbers are 400 ms.
fn trajectory(signals: &[(String, Vec<i16>)]) {
    println!("first 40 reported hops (400 ms)");
    for (name, pcm) in signals {
        if !matches!(name.as_str(), "white_noise" | "music_chords" | "music_harmonics") {
            continue;
        }
        let mut frontend = Frontend::new(NOW_PLAYING).expect("the front end builds");
        let mut gate = Gate::new();
        let mut frames = Vec::new();
        frontend.process(pcm, &mut frames);
        let scores: Vec<String> = (0..frames.len() / CHANNELS)
            .filter_map(|frame| gate.push(&frames[frame * CHANNELS..(frame + 1) * CHANNELS]))
            .take(40)
            .map(|score| format!("{:.2}", probability(score)))
            .collect();
        println!("  {name:<18} {}", scores.join(" "));
    }
}

/// Mean log-mel per channel, so a nonsense score can be attributed to the trunk rather than to
/// the front end feeding it. A bank pinned at the ceiling or flat across every channel would mean
/// the harness is wrong, not the gate.
fn features(signals: &[(String, Vec<i16>)]) {
    println!("mean log-mel by channel (front-end sanity; ceiling is 15.0)");
    for (name, pcm) in signals {
        if !matches!(name.as_str(), "white_noise" | "music_chords" | "music_harmonics" | "speech_male")
        {
            continue;
        }
        let mut frontend = Frontend::new(NOW_PLAYING).expect("the front end builds");
        let mut frames = Vec::new();
        frontend.process(pcm, &mut frames);
        let count = frames.len() / CHANNELS;
        let mut mean = [0.0f32; CHANNELS];
        for frame in 0..count {
            for (channel, slot) in mean.iter_mut().enumerate() {
                *slot += frames[frame * CHANNELS + channel];
            }
        }
        let line: Vec<String> = mean
            .iter()
            .step_by(4)
            .map(|total| format!("{:>5.1}", total / count as f32))
            .collect();
        println!("  {name:<18} {}", line.join(" "));
    }
}

struct Row {
    hops: usize,
    min: f32,
    mean: f32,
    p50: f32,
    p90: f32,
    max: f32,
    /// Fraction of hops whose raw probability is at or above the shipped positive threshold.
    above: f32,
    /// Standard deviation of the raw probability.
    sd: f32,
    /// Mean absolute change in probability between consecutive 10 ms hops.
    step: f32,
    /// Fraction of hops where the int8 logit hit a rail, i.e. the classifier saturated.
    rail: f32,
    /// Positive events the shipped `SmoothedLatchingClassifier` fired.
    events: usize,
    /// Every reported int16 score, kept so `sweep` can re-latch without re-running the gate.
    scores: Vec<i16>,
}

/// PCM in, one raw probability per hop out, plus what the shipped latch did with the scores.
///
/// `None` when the clip is shorter than the gate's 230 ms receptive field, which real recordings
/// of UI blips easily are.
fn run(pcm: &[i16]) -> Option<Row> {
    let mut frontend = Frontend::new(NOW_PLAYING).expect("the front end builds");
    let mut gate = Gate::new();
    let mut latch = Latch::new(Hysteresis::SHIPPED);

    let mut probabilities = Vec::new();
    let mut scores = Vec::new();
    let mut events = 0usize;
    let mut frames = Vec::new();

    for chunk in pcm.chunks(HOP_SAMPLES) {
        frames.clear();
        let produced = frontend.process(chunk, &mut frames);
        for frame in 0..produced {
            let logmel = &frames[frame * CHANNELS..(frame + 1) * CHANNELS];
            if let Some(score) = gate.push(logmel) {
                probabilities.push(probability(score));
                scores.push(score);
                if latch.push(score).positive_event {
                    events += 1;
                }
            }
        }
    }

    let mut sorted = probabilities.clone();
    sorted.sort_by(|a, b| a.partial_cmp(b).expect("scores are finite"));
    let hops = sorted.len();
    if hops < 2 {
        return None;
    }
    let threshold = Hysteresis::SHIPPED.positive_threshold;
    let mean = probabilities.iter().sum::<f32>() / hops as f32;
    let variance = probabilities.iter().map(|p| (p - mean) * (p - mean)).sum::<f32>() / hops as f32;
    let step = probabilities.windows(2).map(|w| (w[1] - w[0]).abs()).sum::<f32>()
        / (hops - 1).max(1) as f32;
    // The int8 logit spans +-10 through a scale of 0.0784, so a probability outside this band can
    // only come from the quantized logit clamping at 127 or -128.
    let rail = probabilities.iter().filter(|&&p| !(0.001..=0.999).contains(&p)).count() as f32
        / hops as f32;
    Some(Row {
        hops,
        min: sorted[0],
        mean,
        p50: sorted[hops / 2],
        p90: sorted[hops * 9 / 10],
        max: sorted[hops - 1],
        above: probabilities.iter().filter(|&&p| p >= threshold).count() as f32 / hops as f32,
        sd: variance.sqrt(),
        step,
        rail,
        events,
        scores,
    })
}

/// The separation statement, stated in terms of the measured distributions rather than a verdict.
fn summarise(rows: &[(String, Row)]) {
    let pick = |prefix: &str| -> Vec<&(String, Row)> {
        rows.iter().filter(|(name, _)| name.starts_with(prefix)).collect()
    };
    let music = pick("music_");
    let speech: Vec<_> = pick("speech_").into_iter().chain(pick("babble_")).collect();
    let noise: Vec<_> = pick("white_")
        .into_iter()
        .chain(pick("pink_"))
        .chain(pick("noise_"))
        .chain(pick("silence"))
        .collect();

    let span = |group: &[&(String, Row)]| -> (f32, f32) {
        let low = group.iter().map(|(_, r)| r.p50).fold(f32::MAX, f32::min);
        let high = group.iter().map(|(_, r)| r.p50).fold(f32::MIN, f32::max);
        (low, high)
    };

    let (music_low, music_high) = span(&music);
    let (speech_low, speech_high) = span(&speech);
    let (noise_low, noise_high) = span(&noise);

    println!("median raw score by group");
    println!("  music  {music_low:.4} .. {music_high:.4}");
    println!("  speech {speech_low:.4} .. {speech_high:.4}");
    println!("  noise  {noise_low:.4} .. {noise_high:.4}");
    println!();
    println!(
        "music vs speech gap: {:+.4}   music vs noise gap: {:+.4}",
        music_low - speech_high,
        music_low - noise_high
    );
    println!(
        "shipped positive threshold {:.2}; total positive events across all inputs: {}",
        Hysteresis::SHIPPED.positive_threshold,
        rows.iter().map(|(_, r)| r.events).sum::<usize>()
    );
    println!(
        "fraction of hops at or above threshold: music {:.1}%, speech {:.1}%, noise {:.1}%",
        100.0 * music.iter().map(|(_, r)| r.above).sum::<f32>() / music.len() as f32,
        100.0 * speech.iter().map(|(_, r)| r.above).sum::<f32>() / speech.len() as f32,
        100.0 * noise.iter().map(|(_, r)| r.above).sum::<f32>() / noise.len() as f32,
    );
}

// --- signal generation -------------------------------------------------------------------
//
// Deterministic, so a run is reproducible and a regression is attributable.

struct Random(u32);

impl Random {
    fn new() -> Random {
        Random(0x2545_F491)
    }

    /// Uniform in `[-1, 1)`.
    fn signed(&mut self) -> f32 {
        self.0 = self.0.wrapping_mul(1103515245).wrapping_add(12345) & 0x7FFF_FFFF;
        (self.0 >> 11) as f32 / (1u32 << 19) as f32 - 1.0
    }
}

/// Scale to `rms` of full scale and round to i16. Silence is passed through untouched.
fn quantize(samples: &[f32], rms: f32) -> Vec<i16> {
    let energy = samples.iter().map(|s| s * s).sum::<f32>() / samples.len() as f32;
    if energy <= 0.0 {
        return vec![0i16; samples.len()];
    }
    let gain = rms / energy.sqrt();
    samples
        .iter()
        .map(|s| (s * gain * 32767.0).clamp(-32768.0, 32767.0) as i16)
        .collect()
}

fn silence() -> Vec<f32> {
    vec![0.0; SAMPLES]
}

fn white_noise() -> Vec<f32> {
    let mut random = Random::new();
    (0..SAMPLES).map(|_| random.signed()).collect()
}

/// Voss-McCartney: octave-spaced random holds summed, giving a 1/f slope.
fn pink_noise() -> Vec<f32> {
    let mut random = Random::new();
    let mut rows = [0.0f32; 12];
    (0..SAMPLES)
        .map(|n| {
            for (octave, row) in rows.iter_mut().enumerate() {
                if n % (1 << octave) == 0 {
                    *row = random.signed();
                }
            }
            rows.iter().sum::<f32>() / rows.len() as f32
        })
        .collect()
}

/// White noise through a band-pass, to see which part of the 60–3800 Hz band drives the score.
fn band_noise(low: f32, high: f32) -> Vec<f32> {
    let mut out = white_noise();
    let centre = (low * high).sqrt();
    resonate(&mut out, centre, high - low);
    out
}

fn tone() -> Vec<f32> {
    (0..SAMPLES)
        .map(|n| (2.0 * std::f32::consts::PI * 440.0 * n as f32 / RATE as f32).sin())
        .collect()
}

/// One sustained chord: three notes, eight harmonics each, slight detune, slow tremolo.
fn harmonic_stack() -> Vec<f32> {
    let roots = [220.0f32, 277.18, 329.63];
    (0..SAMPLES)
        .map(|n| {
            let t = n as f32 / RATE as f32;
            let mut sum = 0.0;
            for (voice, root) in roots.iter().enumerate() {
                let detune = 1.0 + 0.0015 * voice as f32;
                for harmonic in 1..=8 {
                    let f = root * detune * harmonic as f32;
                    if f < 7800.0 {
                        sum += (2.0 * std::f32::consts::PI * f * t).sin() / harmonic as f32;
                    }
                }
            }
            sum * (1.0 + 0.08 * (2.0 * std::f32::consts::PI * 4.5 * t).sin())
        })
        .collect()
}

/// A note with a plucked envelope and a 1/k harmonic series.
fn note(out: &mut [f32], start: usize, length: usize, hz: f32, gain: f32) {
    for n in 0..length.min(out.len().saturating_sub(start)) {
        let t = n as f32 / RATE as f32;
        let envelope = (1.0 - (-t * 60.0).exp()) * (-t * 1.6).exp();
        let mut sum = 0.0;
        for harmonic in 1..=10 {
            let f = hz * harmonic as f32;
            if f < 7800.0 {
                sum += (2.0 * std::f32::consts::PI * f * t).sin() / harmonic as f32;
            }
        }
        out[start + n] += sum * envelope * gain;
    }
}

/// I–V–vi–IV in C, three times, each chord struck twice.
fn chords() -> Vec<f32> {
    let progression: [[f32; 3]; 4] = [
        [261.63, 329.63, 392.00],
        [392.00, 493.88, 587.33],
        [220.00, 261.63, 329.63],
        [174.61, 220.00, 261.63],
    ];
    let mut out = vec![0.0f32; SAMPLES];
    let beat = RATE / 2;
    for bar in 0..(SAMPLES / (beat * 2)) {
        let chord = progression[bar % progression.len()];
        for pitch in chord {
            note(&mut out, bar * beat * 2, beat * 2, pitch, 1.0);
            note(&mut out, bar * beat * 2 + beat, beat, pitch, 0.6);
        }
        note(&mut out, bar * beat * 2, beat * 2, chord[0] / 2.0, 1.2);
    }
    out
}

/// A monophonic line with vibrato over a held bass note.
fn melody() -> Vec<f32> {
    let scale = [261.63f32, 293.66, 329.63, 349.23, 392.00, 440.00, 493.88, 523.25];
    let order = [0usize, 2, 4, 7, 5, 4, 2, 0, 3, 5, 7, 5, 4, 2, 1, 0];
    let mut out = vec![0.0f32; SAMPLES];
    let step = RATE / 4;
    for index in 0..(SAMPLES / step) {
        let hz = scale[order[index % order.len()]];
        let start = index * step;
        for n in 0..step.min(SAMPLES - start) {
            let t = n as f32 / RATE as f32;
            let vibrato = 1.0 + 0.006 * (2.0 * std::f32::consts::PI * 5.5 * t).sin();
            let envelope = (1.0 - (-t * 40.0).exp()) * (-t * 1.2).exp();
            let mut sum = 0.0;
            for harmonic in 1..=9 {
                let f = hz * vibrato * harmonic as f32;
                if f < 7800.0 {
                    sum += (2.0 * std::f32::consts::PI * f * t).sin() / harmonic as f32;
                }
            }
            out[start + n] += sum * envelope;
        }
        note(&mut out, start, step, scale[0] / 2.0, 0.8);
    }
    out
}

/// Chords plus a kick on every beat and a hat on every off-beat: the closest thing here to a mix.
fn full_mix() -> Vec<f32> {
    let mut out = chords();
    let mut random = Random::new();
    let beat = RATE / 2;
    for hit in 0..(SAMPLES / beat) {
        let start = hit * beat;
        for n in 0..(RATE / 8).min(SAMPLES - start) {
            let t = n as f32 / RATE as f32;
            let sweep = 120.0 * (-t * 30.0).exp() + 45.0;
            out[start + n] += (2.0 * std::f32::consts::PI * sweep * t).sin() * (-t * 22.0).exp() * 2.0;
        }
        let hat = start + beat / 2;
        for n in 0..(RATE / 40).min(SAMPLES.saturating_sub(hat)) {
            let t = n as f32 / RATE as f32;
            out[hat + n] += random.signed() * (-t * 180.0).exp() * 0.5;
        }
    }
    out
}

/// A two-pole resonator, applied in place. `bandwidth` is in Hz.
fn resonate(buffer: &mut [f32], hz: f32, bandwidth: f32) {
    let r = (-std::f32::consts::PI * bandwidth / RATE as f32).exp();
    let theta = 2.0 * std::f32::consts::PI * hz / RATE as f32;
    let (a1, a2) = (2.0 * r * theta.cos(), -r * r);
    let (mut y1, mut y2) = (0.0f32, 0.0f32);
    for sample in buffer.iter_mut() {
        let y = *sample * (1.0 - r) + a1 * y1 + a2 * y2;
        y2 = y1;
        y1 = y;
        *sample = y;
    }
}

/// Source-filter speech: a jittered glottal pulse train through three moving formants, with
/// syllable-rate amplitude modulation, fricative bursts and, when `pauses`, inter-phrase silence.
///
/// Not a recording, and does not claim to be one. What it does carry is the properties that make
/// speech the hard negative: a harmonic source, formant structure inside the gate's band, and a
/// 4–5 Hz envelope — everything music has except a stable pitch and a metrical grid.
///
/// `pauses` exists because RMS is measured over the whole signal, so a speech clip full of gaps is
/// louder than music during its voiced parts and quieter on average. The pause-free variant
/// removes that confound.
fn speech(f0: f32, pauses: bool) -> Vec<f32> {
    // Five vowels, as (F1, F2, F3) in Hz.
    let vowels = [
        (700.0f32, 1220.0f32, 2600.0f32),
        (400.0, 2000.0, 2550.0),
        (300.0, 2300.0, 3000.0),
        (600.0, 900.0, 2400.0),
        (500.0, 1500.0, 2500.0),
    ];
    let mut random = Random::new();
    let mut out = vec![0.0f32; SAMPLES];
    let phoneme = RATE * 13 / 100;
    let mut index = 0usize;
    let mut position = 0usize;
    let mut phase = 0.0f32;

    while position < SAMPLES {
        let length = phoneme.min(SAMPLES - position);
        // Every seventh segment is a pause; every third is a fricative rather than a vowel.
        let kind = index % 7;
        let mut segment = vec![0.0f32; length];
        if kind == 6 && pauses {
            // Silence between phrases.
        } else if kind == 2 || kind == 5 {
            for sample in segment.iter_mut() {
                *sample = random.signed() * 0.35;
            }
            resonate(&mut segment, 4200.0, 1400.0);
        } else {
            // Declining pitch contour across the phrase, plus per-period jitter.
            let base = f0 * (1.0 + 0.18 * (index as f32 * 0.9).sin() - 0.02 * (index % 7) as f32);
            for sample in segment.iter_mut() {
                phase += base * (1.0 + 0.01 * random.signed()) / RATE as f32;
                // A glottal pulse: one impulse per period, not a sine.
                *sample = if phase >= 1.0 {
                    phase -= 1.0;
                    1.0
                } else {
                    0.0
                };
            }
            let (f1, f2, f3) = vowels[index % vowels.len()];
            resonate(&mut segment, f1, 80.0);
            resonate(&mut segment, f2, 110.0);
            resonate(&mut segment, f3, 170.0);
        }
        // Syllable-rate envelope with onset and offset ramps.
        for (n, sample) in segment.iter_mut().enumerate() {
            let t = n as f32 / length as f32;
            *sample *= (std::f32::consts::PI * t).sin().powf(0.6);
        }
        out[position..position + length].copy_from_slice(&segment);
        position += length;
        index += 1;
    }
    out
}

/// Two speakers at once — no pauses, denser harmonic content, closer to a room with people in it.
fn babble() -> Vec<f32> {
    let a = speech(104.0, true);
    let b = speech(188.0, true);
    let offset = RATE * 3 / 10;
    (0..SAMPLES)
        .map(|n| a[n] + b[(n + offset) % SAMPLES] * 0.8)
        .collect()
}

/// White noise wearing speech's envelope: structureless, but modulated. Separates a detector that
/// keys on spectral structure from one that keys on the shape of the level over time.
fn am_noise() -> Vec<f32> {
    let envelope = speech(112.0, true);
    let mut random = Random::new();
    let window = 160usize;
    let mut out = vec![0.0f32; SAMPLES];
    for start in (0..SAMPLES).step_by(window) {
        let end = (start + window).min(SAMPLES);
        let level = envelope[start..end].iter().map(|s| s.abs()).sum::<f32>() / (end - start) as f32;
        for sample in out[start..end].iter_mut() {
            *sample = random.signed() * level;
        }
    }
    out
}

// --- WAV ---------------------------------------------------------------------------------

/// Minimal 16-bit PCM WAV reader: downmixes to mono and resamples to 16 kHz linearly.
fn read_wav(path: &str) -> Result<Vec<i16>, String> {
    let bytes = std::fs::read(path).map_err(|e| e.to_string())?;
    if bytes.len() < 44 || &bytes[0..4] != b"RIFF" || &bytes[8..12] != b"WAVE" {
        return Err("not a RIFF/WAVE file".into());
    }
    let word = |at: usize| u16::from_le_bytes([bytes[at], bytes[at + 1]]) as usize;
    let long =
        |at: usize| u32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]]) as usize;

    let (mut channels, mut rate, mut bits) = (0usize, 0usize, 0usize);
    let mut data: Option<(usize, usize)> = None;
    let mut at = 12usize;
    while at + 8 <= bytes.len() {
        let id = &bytes[at..at + 4];
        let size = long(at + 4);
        let body = at + 8;
        if id == b"fmt " && body + 16 <= bytes.len() {
            channels = word(body + 2);
            rate = long(body + 4);
            bits = word(body + 14);
        } else if id == b"data" {
            data = Some((body, size.min(bytes.len() - body)));
        }
        at = body + size + (size & 1);
    }

    let (body, size) = data.ok_or("no data chunk")?;
    if bits != 16 || channels == 0 || rate == 0 {
        return Err(format!("need 16-bit PCM; got {bits}-bit, {channels}ch, {rate}Hz"));
    }

    let mono: Vec<f32> = bytes[body..body + size]
        .chunks_exact(2)
        .map(|c| i16::from_le_bytes([c[0], c[1]]) as f32)
        .collect::<Vec<_>>()
        .chunks(channels)
        .map(|frame| frame.iter().sum::<f32>() / channels as f32)
        .collect();

    let length = mono.len() * RATE / rate;
    let resampled: Vec<f32> = (0..length)
        .map(|n| {
            let source = n as f32 * rate as f32 / RATE as f32;
            let index = source as usize;
            let frac = source - index as f32;
            let a = mono[index.min(mono.len() - 1)];
            let b = mono[(index + 1).min(mono.len() - 1)];
            a + (b - a) * frac
        })
        .collect();

    // Same RMS normalisation as the synthetic signals, so the table stays comparable.
    Ok(quantize(&resampled, TARGET_RMS))
}
