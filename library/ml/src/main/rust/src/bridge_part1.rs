/// Recognise every line in `pixels` and return them as tab-separated text.
///
/// One line per region: `text`, then eight quad coordinates in source-bitmap pixels, then
/// the confidence, then `1` or `0` for vertical — ten fields after the text, tab-separated,
/// regions separated by newlines.
///
/// A string rather than a `float[]` plus a `String[]`, because the geometry and the text
/// belong to the same region and two arrays would have to be kept in step across the
/// boundary. It is safe to pack this way rather than lucky: the dictionary is 836 single
/// non-whitespace characters plus a space, so a decoded line can contain neither a tab nor
/// a newline, and `ctc::Dictionary::parse` rejects a file that broke that.
///
/// Returns null on failure or on a `0` handle. An empty string means no text, which is not
/// an error.
///
/// # Safety
///
/// `handle` must be `0` or a value returned by
/// [`Java_com_vayunmathur_library_ml_MlNative_createPpocr`] and not yet destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_recognizeText<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    pixels: JIntArray<'l>,
    width: jint,
    height: jint,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        if handle == 0 || width <= 0 || height <= 0 {
            return Ok(null);
        }
        // SAFETY: as `segment` — the caller guarantees the handle is live and serialises this
        // against `destroyOcr`.
        let state = unsafe { &mut *(handle as *mut OcrHandle) };

        if let Err(e) = read_pixels(env, &pixels, width, height, &mut state.pixels) {
            log(&e);
            return Ok(null);
        }
        let lines = match read_text(state, width as u32, height as u32) {
            Ok(lines) => lines,
            Err(e) => {
                log(&format!("OCR failed: {e}"));
                return Ok(null);
            }
        };
        Ok(match env.new_string(encode(&lines)) {
            Ok(text) => text.into_raw(),
            Err(e) => {
                log(&format!("cannot return the text: {e}"));
                null
            }
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Detect, crop, recognise and order. The Vulkan half of `post::ocr::lines`.
fn read_text(state: &mut OcrHandle, width: u32, height: u32) -> Result<Vec<Line>, String> {
    let fit = Letterbox::square(width, height, ppocr_det::LONG_SIDE)?;
    let maps = state.det.infer_letterboxed(&state.pixels, width, height, &fit)?;
    let probability = match maps.as_slice() {
        [only] => only,
        other => return Err(format!("detection returned {} maps, not one", other.len())),
    };
    let (map_w, map_h) = state.det.output_size()?;
    // Disjoint field borrows: the recogniser is taken mutably while the pixels and the
    // dictionary are read, which is why this is not `state.rec.infer(...)` inline.
    let OcrHandle { rec, rec_batches, dictionary, pixels, .. } = state;
    ocr::lines_batched(
        &ocr::Detection { probability, width: map_w, height: map_h, fit: &fit },
        &ocr::Source { pixels, width, height },
        dictionary,
        |strips| recognise_all(rec, rec_batches, strips),
    )
}

/// Pack the lines into the tab-separated form described on `recognizeText`.
fn encode(lines: &[Line]) -> String {
    let mut out = String::new();
    for line in lines {
        if !out.is_empty() {
            out.push('\n');
        }
        out.push_str(&line.text);
        for (x, y) in &line.corners {
            out.push('\t');
            out.push_str(&format!("{x}"));
            out.push('\t');
            out.push_str(&format!("{y}"));
        }
        out.push('\t');
        out.push_str(&format!("{}", line.confidence));
        out.push('\t');
        out.push(if line.vertical { '1' } else { '0' });
    }
    out
}

/// Free both networks and the dictionary.
///
/// Separate from [`Java_com_vayunmathur_library_ml_MlNative_destroy`] because the handle is
/// a different type; passing one to the other is undefined.
///
/// # Safety
///
/// `handle` must be `0` or a value returned by
/// [`Java_com_vayunmathur_library_ml_MlNative_createPpocr`], and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyOcr<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this came from `createPpocr` and has not been
    // destroyed. Each `Net`'s Drop waits for the device to go idle.
    drop(unsafe { Box::from_raw(handle as *mut OcrHandle) });
}

/// A small deterministic-per-seed generator, seeded from the clock per utterance.
///
/// Speech is *meant* to vary: Supertonic's flow matching starts from a sampled latent, so two
/// readings of the same sentence differ. That is the model's design, not a defect. SplitMix64 is
/// used rather than a cryptographic source because nothing here is a secret and the sequence only
/// has to be well-distributed.
struct SplitMix {
    state: u64,
}

impl SplitMix {
    fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    /// Box-Muller over two uniforms, which is exact rather than an approximation of normal.
    fn normal(&mut self, count: usize) -> Vec<f32> {
        let mut out = Vec::with_capacity(count);
        while out.len() < count {
            // `next_u64 >> 11` gives 53 significant bits, and the `+ 1` keeps the log finite.
            let first = ((self.next_u64() >> 11) as f64 + 1.0) / 9_007_199_254_740_993.0;
            let second = ((self.next_u64() >> 11) as f64) / 9_007_199_254_740_992.0;
            let radius = (-2.0 * first.ln()).sqrt();
            let angle = std::f64::consts::TAU * second;
            out.push((radius * angle.cos()) as f32);
            if out.len() < count {
                out.push((radius * angle.sin()) as f32);
            }
        }
        out
    }
}

/// The single output of a plan that has exactly one.
fn one_output(outputs: Vec<Vec<f32>>) -> Result<Vec<f32>, String> {
    match <[Vec<f32>; 1]>::try_from(outputs) {
        Ok([only]) => Ok(only),
        Err(other) => Err(format!("{} outputs, expected one", other.len())),
    }
}

/// A seed from the clock, so two readings of a sentence differ as the model intends.
fn seed() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0x1234_5678_9ABC_DEF0)
        | 1
}

/// Supertonic's four nets, the conditioning read once, and the voice bundle.
///
/// Its own handle type for the same reason [`OcrHandle`] is: it owns a different set of things, and
/// one `destroy` guessing between them would be type confusion waiting for a caller to mix them up.
///
/// Each net is a [`Reshaped`] rather than a [`Net`], because every Supertonic plan is
/// utterance-shaped and there is no width to compile once and pad to.
struct SupertonicHandle {
    duration: Reshaped<u32>,
    text: Reshaped<u32>,
    /// Frames and characters, which vary independently.
    sampler: Reshaped<(u32, u32)>,
    vocoder: Reshaped<u32>,
    /// What the sampler needs from its weights file that no shader sees, walked once per handle.
    conditioning: supertonic::Conditioning,
    /// The 65,536-entry codepoint table, read as bytes with no parsing.
    indexer: Vec<u8>,
    voice: supertonic::Voice,
    rng: SplitMix,
}

/// The smallest legal shape, for the plan recorded at construction and immediately replaced.
///
/// [`Net::new`] needs a plan, and the real one is not known until an utterance arrives. Recording
/// the smallest is cheapest, and [`Net::rebuild`] only ever grows the arena, so nothing is wasted
/// by starting here.
const SMALLEST: u32 = 1;

fn duration_plan(offsets: &Offsets, chars: u32) -> Result<Plan, String> {
    supertonic_duration::build(offsets, chars)
}

fn text_plan(offsets: &Offsets, chars: u32) -> Result<Plan, String> {
    supertonic_text::build(offsets, chars)
}

fn sampler_plan(offsets: &Offsets, shape: (u32, u32)) -> Result<Plan, String> {
    supertonic_sampler::build_dual_padded(offsets, shape.0, shape.1)
}

fn vocoder_plan(offsets: &Offsets, frames: u32) -> Result<Plan, String> {
    supertonic_vocoder::build(offsets, frames)
}

/// The four nets, as `post::supertonic` wants them.
struct SupertonicNets<'a> {
    duration: &'a mut Reshaped<u32>,
    text: &'a mut Reshaped<u32>,
    sampler: &'a mut Reshaped<(u32, u32)>,
    vocoder: &'a mut Reshaped<u32>,
    timing: Timing,
}

/// Where an utterance's time goes, filled in as the stages run.
///
/// Reported through [`crate::timing`], so it costs a branch per stage unless
/// `MODELRUNNER_TIMING` or `debug.modelrunner.timing` is set. It is kept rather than deleted
/// because the stage split is the whole diagnosis — `analysis/maml_vs_litert.md` turns on the
/// sampler being 92% of an utterance and the barriers being 61% of the sampler, neither of which
/// is visible from a total.
#[derive(Default)]
struct Timing {
    reshape: f64,
    duration: f64,
    text: f64,
    sampler: f64,
    vocoder: f64,
    sampler_calls: u32,
}

/// Positions per id tensor: `crate::nets::embed_lanes` writes two lanes, `lo + 2048 * hi`.
const LANES: usize = 2;

/// `values / stride`, refusing a remainder.
///
/// Every shape below is recovered from an input's length rather than passed alongside it, so the
/// net is always recorded at what the data actually is and cannot drift from what `synthesise`
/// computed. A remainder means the caller and the forward pass disagree about a channel count,
/// which is worth an error rather than a truncating division.
fn positions(what: &str, values: usize, stride: usize) -> Result<u32, String> {
    if stride == 0 || values == 0 || !values.is_multiple_of(stride) {
        return Err(format!("{what}: {values} values is not a whole number of {stride}"));
    }
    Ok((values / stride) as u32)
}

impl supertonic::Stages for SupertonicNets<'_> {
    fn duration(&mut self, lanes: &[f32], style: &[f32]) -> Result<f32, String> {
        // This sequence leads with the sentence token, which `build` adds itself, so it is given
        // the character count without it.
        let sequence = positions("duration ids", lanes.len(), LANES)?;
        let chars = sequence.checked_sub(1).ok_or("a duration pass over only a sentence token")?;
        let reshaping = std::time::Instant::now();
        let net = self.duration.at(chars)?;
        self.timing.reshape += reshaping.elapsed().as_secs_f64() * 1000.0;
        let running = std::time::Instant::now();
        // Two outputs, in `nets::supertonic_duration`'s declaration order: the sentence encoder's
        // hidden states, then the one value `seconds` exponentiates. Only the second is wanted here.
        // The first exists because it is what `scripts/ml/onnx_parity.py` probes for this graph — the
        // net's own output is a single scalar, and a correlation over one value is not a number.
        //
        // Reading it with `one_output` was this engine's original bug: the duration predictor has
        // always returned two tensors, so every synthesis failed at the first stage with
        // "2 outputs, expected one" and no audio was ever produced.
        let out = net.infer_raw_many(&[lanes, style])?;
        self.timing.duration += running.elapsed().as_secs_f64() * 1000.0;
        let [_encoded, log_seconds] = <[Vec<f32>; 2]>::try_from(out).map_err(|other| {
            format!("the duration predictor returned {} tensors, not two", other.len())
        })?;
        match log_seconds.as_slice() {
            [only] => Ok(*only),
            other => {
                Err(format!("the duration predictor returned {} values, not one", other.len()))
            }
        }
    }

    fn text(&mut self, lanes: &[f32], style: &[f32]) -> Result<Vec<f32>, String> {
        let chars = positions("text ids", lanes.len(), LANES)?;
        let reshaping = std::time::Instant::now();
        let net = self.text.at(chars)?;
        self.timing.reshape += reshaping.elapsed().as_secs_f64() * 1000.0;
        let running = std::time::Instant::now();
        let out = one_output(net.infer_raw_many(&[lanes, style])?);
        self.timing.text += running.elapsed().as_secs_f64() * 1000.0;
        out
    }

    fn text_then(
        &mut self,
        lanes: &[f32],
        style: &[f32],
        sampler_shape: (u32, u32),
    ) -> Result<Vec<f32>, String> {
        if self.sampler.holds(sampler_shape) {
            return self.text(lanes, style);
        }
        // The sampler's plan for a new sentence is tens of ms of host work; build it on another
        // thread while the text encoder runs on the GPU. Same pure function, so same plan.
        let (plan, offsets) = self.sampler.planner();
        let (text, built) = std::thread::scope(|scope| {
            let building = scope.spawn(move || plan(&offsets, sampler_shape));
            let text = self.text(lanes, style);
            (text, building.join())
        });
        if let Ok(Ok(built)) = built {
            self.sampler.adopt(sampler_shape, built);
        }
        text
    }

    fn sampler(
        &mut self,
        latent: &[f32],
        text: &[f32],
        keys: &[f32],
        style: &[f32],
        shifts: &[f32],
        query_angles: &[f32],
        key_angles: &[f32],
    ) -> Result<Vec<f32>, String> {
        let frames = positions("a latent", latent.len(), supertonic_sampler::LATENT as usize)?;
        let chars = positions("a conditioning", text.len(), supertonic_sampler::TEXT as usize)?;
        let reshaping = std::time::Instant::now();
        let net = self.sampler.at((frames, chars))?;
        self.timing.reshape += reshaping.elapsed().as_secs_f64() * 1000.0;
        let running = std::time::Instant::now();
        self.timing.sampler_calls += 1;
        // Declaration order, which `infer_raw_many` checks each of against its own binding — the
        // seven are all fp16 planes and a swapped pair would be the right size.
        let out = one_output(net.infer_raw_many(&[
            latent,
            text,
            keys,
            style,
            shifts,
            query_angles,
            key_angles,
        ])?);
        self.timing.sampler += running.elapsed().as_secs_f64() * 1000.0;
        out
    }

    /// Both branches in one submit, through the dual plan.
    ///
    /// `Reshaped::at` re-records at the shape only when it changes, which for a sentence
    /// is never mid-utterance — so the sixteen steps share one recording and each pays a
    /// single submit for both branches. Falls back to two submits only when the dual plan
    /// itself fails to build, which keeps a builder regression from silencing synthesis.
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
    ) -> Result<[Vec<f32>; 2], String> {
        let frames = positions("a latent", latent.len(), supertonic_sampler::LATENT as usize)?;
        let chars = positions(
            "a conditioning",
            conditional_text.len(),
            supertonic_sampler::TEXT as usize,
        )?;
        let reshaping = std::time::Instant::now();
        let net = self.sampler.at((frames, chars))?;
        self.timing.reshape += reshaping.elapsed().as_secs_f64() * 1000.0;
        let running = std::time::Instant::now();
        self.timing.sampler_calls += 1;
        // The plan runs at `padded_frames` (see `build_dual_padded`), so the latent and the
        // query angles go in widened and the velocities come back cut to `frames`.
        let latent = supertonic_sampler::pad_frames(latent, frames)?;
        let query_angles = supertonic_sampler::pad_frames(query_angles, frames)?;
        let (latent, query_angles) = (latent.as_slice(), query_angles.as_slice());
        // Fourteen inputs: the conditional seven, then the unconditional seven — the
        // order `build_dual` declares them in. Two outputs, the two velocities.
        let out = net.infer_raw_many(&[
            latent,
            conditional_text,
            conditional_keys,
            conditional_style,
            shifts,
            query_angles,
            key_angles,
            latent,
            unconditional_text,
            unconditional_keys,
            unconditional_style,
            shifts,
            query_angles,
            key_angles,
        ])?;
        self.timing.sampler += running.elapsed().as_secs_f64() * 1000.0;
        let [conditional, unconditional] =
            <[Vec<f32>; 2]>::try_from(out).map_err(|other| {
                format!("the dual sampler returned {} tensors, not two", other.len())
            })?;
        Ok([
            supertonic_sampler::unpad_frames(&conditional, frames)?,
            supertonic_sampler::unpad_frames(&unconditional, frames)?,
        ])
    }

    fn vocoder(&mut self, latent: &[f32], frames: u32) -> Result<Vec<f32>, String> {
        let reshaping = std::time::Instant::now();
        let net = self.vocoder.at(supertonic_vocoder::padded_frames(frames))?;
        self.timing.reshape += reshaping.elapsed().as_secs_f64() * 1000.0;
        let running = std::time::Instant::now();
        // Two marshalling steps, both the host's job — `nets::supertonic_vocoder::build`'s own doc
        // says the input is "reinterpreted" and the output "read transposed", and the parity path in
        // `nets::reference` does both. Production did neither, which is what made the engine whine:
        //
        //  - the latent arrives `[144, frames]` and the plan reads `[24, 6 * frames]`, and that is
        //    *not* a flat reinterpretation. Assuming one correlates with the reference at 0.009.
        //  - the plan emits `[512, 1, T]` channel-major while a waveform is time-major. Handing that
        //    to AudioTrack unchanged plays each channel plane as if it were consecutive samples, so
        //    every 512th value is a real neighbour and the rest is a periodic artefact — a loud tone
        //    at the frame rate rather than speech.
        let unpacked = supertonic_vocoder::unpack_latent_padded(latent, frames as usize)?;
        let channelled = one_output(net.infer_raw(&unpacked)?)?;
        let out = supertonic_vocoder::interleave_padded(&channelled, frames as usize)?;
        self.timing.vocoder += running.elapsed().as_secs_f64() * 1000.0;
        Ok(out)
    }
}
