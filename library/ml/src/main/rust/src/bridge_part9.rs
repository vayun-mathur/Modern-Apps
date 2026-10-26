/// Gemma 4's audio tower, which is its own `.maml` and its own graph id.
///
/// Separate from [`Gemma4Handle`] for the reason the vision tower is: optional, separately
/// downloaded, and a failure here leaves the assistant answering without sound rather than not
/// answering.
///
/// # This one owns a front end, which the vision tower did not
///
/// The vision tower takes patches Kotlin already produced. This takes a **waveform**, and the
/// log-mel spectrogram between the two is [`crate::logmel`] - reference-verified, and until now
/// with no caller. It lives in the handle rather than being built per call because it holds the
/// Hann window, the 128-channel filter bank and the transform's twiddle tables, none of which
/// depend on the clip.
struct Gemma4AudioHandle {
    net: Reshaped<gemma4_audio::Mode>,
    /// The log-mel front end. Stateful only in its scratch buffers; one clip at a time.
    mel: crate::logmel::LogMel,
}

fn gemma4_audio_plan(offsets: &Offsets, mode: gemma4_audio::Mode) -> Result<Plan, String> {
    gemma4_audio::build(offsets, mode)
}

/// Bring up the audio tower from its `.maml`. Returns 0 on failure, having logged why.
///
/// # Safety
///
/// Called only by the JVM, with a descriptor the caller detached and nothing else holds.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createGemma4Audio<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    length: jlong,
) -> jlong {
    if fd < 0 {
        log(&format!("the gemma4 audio tower is unavailable: descriptor {fd} is not open"));
        return 0;
    }
    // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes it
    // on drop including on every failure path below.
    let file = unsafe { File::from_raw_fd(fd) };
    match build_gemma4_audio(file, offset, length) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("the gemma4 audio tower is unavailable: {e}"));
            0
        }
    }
}

fn build_gemma4_audio(file: File, offset: jlong, length: jlong) -> Result<Gemma4AudioHandle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::GEMMA4_AUDIO)?;
    if weights.len() != gemma4_audio::TENSORS {
        return Err(format!("a file of {} tensors, not {}", weights.len(), gemma4_audio::TENSORS));
    }
    // Recorded at the cap. `at` re-records per clip length, and unlike the vision tower's grid
    // there is only one axis to vary, so most conversations settle on a handful of lengths.
    // Recording at the longest means the first short clip re-records downward rather than the
    // arena having to grow.
    let longest = crate::logmel::frame_count(gemma4_audio::MAX_SAMPLES) as u32;
    let net = Reshaped::streamed(
        context::shared()?,
        weights.offsets(),
        &weights,
        gemma4_audio::Mode::Clip { frames: longest },
        gemma4_audio_plan,
    )?;
    Ok(Gemma4AudioHandle { net, mel: crate::logmel::LogMel::new() })
}

/// Encode one clip into soft tokens: `[n, 1536]` flattened, or null.
///
/// `samples` is **16 kHz mono** in roughly `-1.0..1.0`. The front end has no gain of its own, so
/// the scale it arrives in is the scale the tower sees. The result goes straight to
/// `pushSoftGemma4`, unscaled, for the reason the vision tower's does.
///
/// # What this does to the waveform, and what it deliberately does not
///
/// Truncates to [`gemma4_audio::MAX_SAMPLES`] - thirty seconds, the reference's own cap and what
/// makes the token count bounded. It does **not** pad to a multiple of 128 samples. The reference
/// does, so a batch stacks, and then spends a validity mask through the whole tower undoing it;
/// this runtime records a plan per frame count and passes the clip at its true length. Measured
/// bit-identical against the export over 68 configurations. See `nets::gemma4_audio::prepare`.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4Audio`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_encodeAudioGemma4<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    samples: JFloatArray<'l>,
) -> jfloatArray {
    env.with_env(|env| -> jni::errors::Result<jfloatArray> {
        let null = std::ptr::null_mut();
        if handle == 0 {
            return Ok(null);
        }
        // SAFETY: the caller guarantees the handle came from `createGemma4Audio` and is still live.
        // `&mut` because a new clip length re-records the net, and Kotlin serialises calls.
        let handle = unsafe { &mut *(handle as *mut Gemma4AudioHandle) };
        let encoded = read_float_array(env, &samples)
            .and_then(|waveform| run_gemma4_audio(handle, &waveform));
        Ok(match encoded.and_then(|values| new_float_array(env, &values)) {
            Ok(array) => array,
            Err(e) => {
                log(&format!("the gemma4 audio tower failed: {e}"));
                null
            }
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

fn run_gemma4_audio(handle: &mut Gemma4AudioHandle, waveform: &[f32]) -> Result<Vec<f32>, String> {
    // The cap is the reference's, and it is what bounds the arena at 49.8 MiB. Truncating here
    // rather than refusing is deliberate: a caller who hands over a minute of audio wants the
    // first thirty seconds encoded, not an error.
    let capped = &waveform[..waveform.len().min(gemma4_audio::MAX_SAMPLES)];
    let frames = crate::logmel::frame_count(capped.len());
    let count = u32::try_from(frames).map_err(|_| format!("{frames} mel frames"))?;
    let tokens = gemma4_audio::tokens(count);
    if tokens < gemma4_audio::MIN_TOKENS {
        return Err(format!(
            "{} samples is {frames} mel frames and {tokens} soft tokens, under the {} the \
             attention band needs - about {} ms of audio",
            capped.len(),
            gemma4_audio::MIN_TOKENS,
            capped.len() * 1000 / crate::logmel::SAMPLE_RATE as usize
        ));
    }

    let mut mel = Vec::new();
    let produced = handle.mel.spectrogram(capped, &mut mel);
    if produced != frames {
        return Err(format!("the front end made {produced} frames, not {frames}"));
    }
    let input = gemma4_audio::prepare(&mel, count)?;
    let out = handle
        .net
        .at(gemma4_audio::Mode::Clip { frames: count })?
        .infer_raw_many(&[&input])?;
    let features = one_output(out)?;
    let width = gemma4_audio::OUT_DIM as usize;
    let rows = tokens as usize;
    if features.len() != rows * width {
        return Err(format!("{} values, not {}", features.len(), rows * width));
    }
    // The plan writes `[1536, 1, tokens]`; the decoder reads one soft token at a time, so this
    // hands back `[tokens, 1536]`.
    Ok(transpose(&features, width, rows))
}

/// Release the audio tower.
///
/// # Safety
///
/// Called only by the JVM, once, with a handle from `createGemma4Audio` that nothing else uses.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyGemma4Audio<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this runs once, after every other call on the handle.
    drop(unsafe { Box::from_raw(handle as *mut Gemma4AudioHandle) });
}

/// Feed one token and return its `k` most likely next tokens as `[logit, id, logit, id, ...]`,
/// highest first, ties by lower id - the order a stable descending sort of the full logits gives,
/// so sampling from these candidates draws exactly what sampling the full array would whenever
/// `top_k <= k`. 2k floats cross the boundary instead of 262,144. Null on failure.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_topKGemma4<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    token: jint,
    k: jint,
) -> jfloatArray {
    env.with_env(|env| -> jni::errors::Result<jfloatArray> {
        if handle == 0 {
            return Ok(std::ptr::null_mut());
        }
        // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
        let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
        let (Ok(token), Ok(k)) = (u32::try_from(token), usize::try_from(k)) else {
            log(&format!("gemma4 top-k was given token {token}, k {k}"));
            return Ok(std::ptr::null_mut());
        };
        if token >= gemma4::VOCAB || k == 0 {
            log(&format!("gemma4 top-k was given token {token}, k {k}"));
            return Ok(std::ptr::null_mut());
        }
        let picked = handle.step_top_k(token, k);
        Ok(match picked {
            Ok(pairs) => match new_float_array(env, &pairs) {
                Ok(array) => array,
                Err(e) => {
                    log(&format!("gemma4 cannot return its candidates: {e}"));
                    std::ptr::null_mut()
                }
            },
            Err(e) => {
                log(&format!("gemma4 top-k step failed: {e}"));
                std::ptr::null_mut()
            }
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// The `k` largest of `logits` as `[logit, id, ...]`, descending, ties by lower id. Ids are below
/// 2^24, so they are exact as `f32`.
fn top_k_pairs(logits: &[f32], k: usize) -> Vec<f32> {
    let order = |a: &u32, b: &u32| logits[*b as usize].total_cmp(&logits[*a as usize]).then(a.cmp(b));
    let mut ids: Vec<u32> = (0..logits.len() as u32).collect();
    let k = k.min(ids.len());
    if k < ids.len() {
        ids.select_nth_unstable_by(k - 1, order);
        ids.truncate(k);
    }
    ids.sort_unstable_by(order);
    ids.iter().flat_map(|&id| [logits[id as usize], id as f32]).collect()
}

#[cfg(test)]
mod top_k_tests {
    #[test]
    fn candidates_come_highest_first_with_ties_by_lower_id() {
        let logits = [0.5, 2.0, -1.0, 2.0, 1.5, 0.5];
        assert_eq!(super::top_k_pairs(&logits, 4), vec![2.0, 1.0, 2.0, 3.0, 1.5, 4.0, 0.5, 0.0]);
        assert_eq!(super::top_k_pairs(&logits, 9).len(), 12, "k past the vocabulary keeps all");
    }
}
impl Gemma4Handle {
    /// One decode step whose head returns `[softcapped logit, id] x k`, highest first, ties by
    /// lower id. On the device (`HeadOut::TopK`, 192 values back) when the head file is loaded
    /// and `k` fits its 64; otherwise the full logits, selected on the host.
    fn step_top_k(&mut self, token: u32, k: usize) -> Result<Vec<f32>, String> {
        use crate::nets::gemma4_head::HeadOut;
        let device = k <= crate::nets::gemma4::TOP_K as usize && self.head.is_some();
        if !device {
            let logits = self.step(token, true)?.ok_or("a decode step returned no logits")?;
            return Ok(top_k_pairs(&logits, k));
        }
        let state = match self.fold_pending(token)? {
            Some(state) => state,
            None => {
                let (hidden, per_layer) = self.gather(token)?;
                self.decode_state(&hidden, &per_layer)?
            }
        };
        let Some(head) = self.head.as_mut() else { return Err("the head disappeared".into()) };
        let out = head.at(HeadOut::TopK)?.infer_raw_many(&[&state])?;
        let triples = out.first().ok_or("the top-k head returned nothing")?;
        let mut pairs = Vec::with_capacity(k * 2);
        for triple in triples.chunks_exact(3).take(k) {
            let id = triple[2] as u32 * 2048 + triple[1] as u32;
            if id >= gemma4::VOCAB {
                break;
            }
            pairs.push(gemma4::LOGIT_CAP * (triple[0] / gemma4::LOGIT_CAP).tanh());
            pairs.push(id as f32);
        }
        Ok(pairs)
    }
}

impl Gemma4Handle {
    /// Take pushed tokens into `pending`, refusing what the cache cannot hold. They run with the
    /// next step's token (see [`fold_pending`](Self::fold_pending)), or at the next [`flush`](Self::flush).
    fn push(&mut self, tokens: &[u32]) -> Result<(), String> {
        let total = self.position as usize + self.pending.len() + tokens.len();
        if total > self.context as usize {
            return Err(format!("a prompt of {total} positions past this device's {} cache", self.context));
        }
        if let Some(bad) = tokens.iter().find(|&&t| t >= gemma4::VOCAB) {
            return Err(format!("token {bad} is past the {}-entry vocabulary", gemma4::VOCAB));
        }
        self.pending.extend_from_slice(tokens);
        Ok(())
    }

    /// Positions the cache holds once `pending` is in it: what Kotlin counts.
    fn fed(&self) -> u32 {
        self.position + self.pending.len() as u32
    }

    /// Prefill `pending` now, for any call that needs the cache to hold it.
    fn flush(&mut self) -> Result<(), String> {
        let tokens = std::mem::take(&mut self.pending);
        if !tokens.is_empty() {
            self.prefill(&tokens)?;
        }
        Ok(())
    }

    /// `pending` and `token` as one prefill, returning `token`'s normed state for the head, or
    /// `None` when nothing is pending and the step runs as a decode step.
    fn fold_pending(&mut self, token: u32) -> Result<Option<Vec<f32>>, String> {
        if self.pending.is_empty() {
            return Ok(None);
        }
        let mut tokens = std::mem::take(&mut self.pending);
        tokens.push(token);
        self.prefill(&tokens).map(Some)
    }

    /// Feed many tokens in **one** submit per chunk, filling the caches. Returns the last token's
    /// final-normed state, which the head reads when that token is a step's.
    ///
    /// At one position a pass reads 1.30 GB of weights for a single 1536-wide vector; at T
    /// positions it reads them once and every projection becomes a GEMM over T columns. Chunked
    /// because each chunk's score maps are eight heads of `T x context`. Every chunk attends the
    /// whole cache (`attn_cached_multi`), so any split and any starting position is correct.
    fn prefill(&mut self, tokens: &[u32]) -> Result<Vec<f32>, String> {
        if self.position as usize + tokens.len() > self.context as usize {
            return Err(format!(
                "a prompt of {} positions past this device's {} cache",
                self.position as usize + tokens.len(),
                self.context
            ));
        }
        let mut state = Vec::new();
        for chunk in tokens.chunks(CHUNK as usize) {
            let real = chunk.len() as u32;
            let width = crate::nets::gemma4_gather::padded(real, self.context - self.position);
            // Pad columns feed the pad token; nothing real reads them (see `padded`).
            let mut fed = chunk.to_vec();
            fed.resize(width as usize, 0);
            state = self.prefill_chunk(real, width, |reader| {
                let hidden = crate::nets::gemma4_gather::token_rows(reader, &fed)?;
                Ok((hidden, crate::nets::gemma4_gather::layer_rows(reader, &fed)?))
            })?;
        }
        Ok(state)
    }

    /// [`prefill`](Self::prefill) for **soft tokens** (`D_MODEL` values per row from the vision
    /// or audio encoder). Soft rows take the pad token's per-layer rows: the reference rewrites
    /// the placeholder id to `pad_token_id` before gathering, then overwrites only the hidden.
    fn prefill_soft(&mut self, values: &[f32]) -> Result<(), String> {
        let d = gemma4::D_MODEL as usize;
        if values.is_empty() || !values.len().is_multiple_of(d) {
            return Err(format!("{} values, which is not a whole number of {d}", values.len()));
        }
        if self.position as usize + values.len() / d > self.context as usize {
            return Err(format!(
                "{} soft tokens past this device's {} cache",
                self.position as usize + values.len() / d,
                self.context
            ));
        }
        for chunk in values.chunks(d * CHUNK as usize) {
            let real = (chunk.len() / d) as u32;
            let width = crate::nets::gemma4_gather::padded(real, self.context - self.position);
            self.prefill_chunk(real, width, |reader| {
                let mut hidden = vec![0f32; d * width as usize];
                for (at, row) in chunk.chunks_exact(d).enumerate() {
                    crate::nets::gemma4_gather::place(&mut hidden, row, at, width as usize);
                }
                Ok((hidden, crate::nets::gemma4_gather::layer_rows(reader, &vec![0; width as usize])?))
            })?;
        }
        Ok(())
    }

    /// One chunk of `real` positions run at `width`: `rows` gathers the `[C, 1, width]` hidden
    /// and table blocks while the chunk's plan builds on another thread if it was evicted, then
    /// one combine submit and one Prefill submit. Returns the last real column's normed state.
    fn prefill_chunk(
        &mut self,
        real: u32,
        width: u32,
        rows: impl FnOnce(&crate::weights::Reader<'_>) -> Result<(Vec<f32>, Vec<f32>), String>,
    ) -> Result<Vec<f32>, String> {
        let started = std::time::Instant::now();
        let mode = if self.position == 0 {
            gemma4::Mode::PrefillFresh { tokens: width }
        } else {
            gemma4::Mode::Prefill { tokens: width }
        }
        .at(self.context);
        let mut pending = (!self.net.holds(mode)).then(|| self.net.planner());
        if self.fused() {
            // Nothing left to overlap with, and a thread blocked in `join` lets this core clock
            // down: on a Pixel 8 that made the record ~5x slower (23 -> 120 ms).
            if let Some((plan, offsets)) = pending.take() {
                self.net.adopt(mode, plan(&offsets, mode)?);
            }
        }
        // Owns its inputs, so it keeps building through the rows and the combine below.
        let building = pending.map(|(plan, offsets)| std::thread::spawn(move || plan(&offsets, mode)));
        let (hidden, embedded) = rows(&self.embed.reader())?;
        let gathered = started.elapsed();
        let per_layer = if self.fused() { embedded } else { self.combine_block(&hidden, &embedded, width)? };
        let combined = started.elapsed() - gathered;
        if let Some(thread) = building {
            let plan = thread.join().map_err(|_| "the plan thread panicked".to_string())?;
            self.net.adopt(mode, plan?);
        }
        let wide = width as usize;
        let (l, g) = (gemma4::HEAD_DIM as usize, gemma4::GLOBAL_HEAD_DIM as usize);
        let (mut local, mut global) = (vec![0f32; l * wide], vec![0f32; g * wide]);
        for at in 0..wide {
            let position = self.position as usize + at;
            let local_row = self.local.get(position * l..(position + 1) * l).ok_or("past the rotary table")?;
            let global_row = self.global.get(position * g..(position + 1) * g).ok_or("past the rotary table")?;
            crate::nets::gemma4_gather::place(&mut local, local_row, at, wide);
            crate::nets::gemma4_gather::place(&mut global, global_row, at, wide);
        }
        let submitted = std::time::Instant::now();
        let at = self.net.at(mode)?;
        let recorded = submitted.elapsed();
        at.set_params(StepParams {
            prefix: self.position,
            window_start: self.position.saturating_sub(gemma4::WINDOW - 1),
        })?;
        let ran = std::time::Instant::now();
        let out = at.infer_raw_many(&[&hidden, &per_layer, &local, &global])?;
        log(&format!(
            "gemma4 prefill {real} positions (run at {width}) at {}: {:.0} ms rows, {:.0} ms combine, \
             {:.0} ms record, {:.0} ms gpu",
            self.position,
            gathered.as_secs_f64() * 1000.0,
            combined.as_secs_f64() * 1000.0,
            recorded.as_secs_f64() * 1000.0,
            ran.elapsed().as_secs_f64() * 1000.0,
        ));
        self.position += real;
        let states = out.first().ok_or("a prefill returned nothing")?;
        Ok(crate::nets::gemma4_gather::column(states, real.saturating_sub(1) as usize, wide))
    }
}
