/// MADLAD400-3B-MT, as `:translate` holds it. Handed to Kotlin as an opaque `jlong`.
///
/// Its own handle type for the same reason the translation handle is: it owns a different set of
/// things, and one `destroy` guessing between them would be type confusion waiting to happen.
///
/// # One net, two passes
///
/// [`Reshaped`] keyed by [`madlad::Mode`]. The encoder and the decode step (which computes the
/// untied logits head itself) are two passes over **one** ~1 GiB file, so two `Net`s would
/// upload it twice. Both are keyed on the source length, so a translation pays two re-records -
/// into the encoder and back into the decode step - and the decode steps none: the step number
/// reaches the plan through [`StepParams`].
///
/// # The weights file stays open
///
/// Unlike Supertonic, whose host-side tensors are read once into [`supertonic::Conditioning`],
/// MADLAD's host-side tensors are the ~250 MiB input table (gathered a 1 KB row per token) and
/// the two 1 KB bias tables (read once per shape). So the [`Streamed`] is retained and
/// [`madlad_part1::embed_positions`] / [`madlad_part1::relative_bias`] read from it per call.
///
/// # The bias is computed per call, on the host
///
/// [`madlad_part1::relative_bias`] gathers the `[32, 16]` block-0 tables into the `[16, queries, keys]`
/// matrix one shape needs. The encoder computes one matrix per sentence; each decode step
/// computes one row. Both arrive as plan inputs beside the token — never as weights, which
/// would need a table per shape.
///
/// The tensor order is the contract in `maml_convert.collect_madlad` — see `nets::madlad`.
struct MadladHandle {
    net: Reshaped<madlad::Mode>,
    weights: Streamed,
    /// `scripts/ml/fetch_madlad400.py`'s Unigram table, parsed per translation.
    tokenizer: Vec<u8>,
}

fn madlad_plan(offsets: &Offsets, mode: madlad::Mode) -> Result<Plan, String> {
    madlad::build(offsets, mode)
}

/// The two GPU passes, as [`madlad_post::Nets`] wants them.
///
/// Only the token and its bias row cross to the device per step, and only the next token comes
/// back: the self-attention KV cache and the cross-attention K and V the encoder pass computed all
/// live in the arena. A sentence cannot leak into the next one because the encoder pass rewrites
/// the cross K and V, and the first step writes self-attention row zero before reading anything.
struct MadladNets<'a> {
    net: &'a mut Reshaped<madlad::Mode>,
    weights: &'a Streamed,
    /// Source positions, so a decode step records at the length the encoder ran at.
    src_len: u32,
}

impl MadladNets<'_> {
    fn new<'a>(handle: &'a mut MadladHandle) -> MadladNets<'a> {
        MadladNets { net: &mut handle.net, weights: &handle.weights, src_len: 0 }
    }
}

impl madlad_post::Nets for MadladNets<'_> {
    fn encode(&mut self, source: &[u32]) -> Result<(), String> {
        let len = u32::try_from(source.len()).map_err(|_| "a source longer than u32")?;
        let reader = self.weights.reader();
        // The embedding with no scale and no positions — T5 has neither — all on the host.
        // See `nets::madlad` for why none of that is a shader.
        let embedded = madlad_part1::embed_positions(reader, source, 0)?;
        // The encoder bias: bidirectional over 0..len, gathered from the encoder block-0 table.
        let queries: Vec<u32> = (0..len).collect();
        let bias = madlad_part1::relative_bias(reader, madlad::TABLE_ENC, &queries, &queries, true)?;
        let net = self.net.at(madlad::Mode::Encode { len })?;
        // The output is the encoder state, which the decode steps never need: the pass has
        // already left their cross-attention K and V in the arena.
        net.infer_raw_many(&[&embedded, &bias])?;
        self.src_len = len;
        Ok(())
    }

    fn decode_step(&mut self, token: u32, step: usize) -> Result<u32, String> {
        let cache_len = u32::try_from(step).map_err(|_| "a step past u32")?;
        if cache_len >= madlad::MAX_DECODE_POSITIONS {
            return Err(format!(
                "step {step} is past the {} the KV cache holds",
                madlad::MAX_DECODE_POSITIONS
            ));
        }
        if self.src_len == 0 {
            return Err("a decode step before the encoder ran".into());
        }
        let reader = self.weights.reader();
        let embedded = madlad_part1::embed_positions(reader, &[token], cache_len)?;

        // The step's bias row: query `step` (absolute) against keys `0..=step`, causal, from
        // the decoder block-0 table. Padded with zeros to the plan width; `softmax_prefix`
        // only normalises the leading `prefix + 1` entries, so the tail never matters.
        let queries = [cache_len];
        let keys: Vec<u32> = (0..=cache_len).collect();
        let row = madlad_part1::relative_bias(reader, madlad::TABLE_DEC, &queries, &keys, false)?;
        let mut bias = vec![0.0f32; madlad::HEADS as usize * madlad::MAX_DECODE_POSITIONS as usize];
        for head in 0..madlad::HEADS as usize {
            let from = head * keys.len();
            let to = head * madlad::MAX_DECODE_POSITIONS as usize;
            bias[to..to + keys.len()].copy_from_slice(&row[from..from + keys.len()]);
        }

        // Keyed on the source length only, so this re-records once per translation.
        let net = self.net.at(madlad::Mode::DecodeStep { src_len: self.src_len })?;
        net.set_params(StepParams { prefix: cache_len, window_start: 0 })?;
        // `Builder::argmax`'s pair: `lo = id % 2048`, `hi = id / 2048`.
        let pair = one_output(net.infer_raw_many(&[&embedded, &bias])?)?;
        let [lo, hi] = pair[..] else {
            return Err(format!("the greedy id holds {} values, not 2", pair.len()));
        };
        let id = hi as u32 * 2048 + lo as u32;
        if id >= madlad::VOCAB {
            return Err(format!("the greedy id {id} is past the {}-entry vocabulary", madlad::VOCAB));
        }
        Ok(id)
    }
}

/// Bring up MADLAD from its one `.maml` and its Unigram tokenizer table. Returns 0 on failure.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env`, arrays it owns, and a descriptor nothing else holds.
///
/// `createMadlad` mirrors `createNllb`, and `translateMadlad` takes a **target tag**
/// (`<2en>`) rather than two flores ids — the protocol difference vs NLLB. Agreed with
/// app-eng (team `nllb-translate`).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createMadlad<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    length: jlong,
    tokenizer: JByteArray<'l>,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
        // The descriptor first, and adopted into an owning `File` before anything else may fail: the
        // caller detached it, so a path that returns without wrapping it leaks it for the life of the
        // process. Every check below the adoption is therefore free to fail; nothing above it is.
        if fd < 0 {
            log(&format!("madlad is unavailable: descriptor {fd} is not open"));
            return Ok(0);
        }
        // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes it on
        // drop — including on every failure path below.
        let file = unsafe { File::from_raw_fd(fd) };
        Ok(match build_madlad(env, file, offset, length, &tokenizer) {
            Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
            Err(e) => {
                log(&format!("madlad is unavailable: {e}"));
                0
            }
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

fn build_madlad<'l>(
    env: &Env,
    file: File,
    offset: jlong,
    length: jlong,
    tokenizer: &JByteArray<'l>,
) -> Result<MadladHandle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::MADLAD)?;
    if weights.len() != madlad::TENSORS {
        return Err(format!("a file of {} tensors, not {}", weights.len(), madlad::TENSORS));
    }

    let tokenizer = env
        .convert_byte_array(tokenizer)
        .map_err(|e| format!("cannot read the tokenizer table: {e}"))?;
    // Parsed once here purely to refuse a bad table at construction rather than at the first
    // translation, when the UI has already committed to having a working engine.
    let parsed = Table::parse_with(&tokenizer, T5)?;
    if parsed.len() != madlad::VOCAB as usize {
        return Err(format!("a tokenizer of {} pieces, not {}", parsed.len(), madlad::VOCAB));
    }

    // Decode step first, then the smallest encoder, for the reason `build_nllb` gives.
    let mut net = Reshaped::streamed(
        context::shared()?,
        weights.offsets(),
        &weights,
        madlad::Mode::DecodeStep { src_len: WARM_SRC_LEN },
        madlad_plan,
    )?;
    net.at(madlad::Mode::Encode { len: SMALLEST })?;
    Ok(MadladHandle { net, weights, tokenizer })
}

/// Translate [`text`] into the language `tag` names (e.g. `"<2en>"`), or null on failure.
///
/// `text` must already have double spaces collapsed (HF's T5 normaliser; the Unigram model
/// itself is the identity). The tag is validated against the table — an unknown tag is refused
/// rather than mistranslated.
///
/// Unlike NLLB's `translateNllb`, ONE tag is required rather than two language tokens: the tag
/// leads the encoder source and the decoder starts unforced from `<unk>`. Backwards it produces
/// fluent output in the wrong language rather than an error.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createMadlad` that has not been destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_translateMadlad<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    text: JString<'l>,
    tag: JString<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        if handle == 0 {
            return Ok(std::ptr::null_mut());
        }
        // SAFETY: the caller guarantees the handle came from `createMadlad` and is still live. It is
        // `&mut` because a decode step re-records the net, and Kotlin serialises calls on one handle.
        let handle = unsafe { &mut *(handle as *mut MadladHandle) };
        let translated = match (text.try_to_string(env), tag.try_to_string(env)) {
            (Ok(text), Ok(tag)) => run_madlad(handle, &text, &tag),
            _ => Err("cannot read the source text or the target tag".into()),
        };
        Ok(match translated {
            Ok(out) => match env.new_string(&out) {
                Ok(string) => string.into_raw(),
                Err(e) => {
                    log(&format!("madlad cannot return its translation: {e}"));
                    std::ptr::null_mut()
                }
            },
            Err(e) => {
                log(&format!("madlad failed: {e}"));
                std::ptr::null_mut()
            }
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

fn run_madlad(handle: &mut MadladHandle, text: &str, tag: &str) -> Result<String, String> {
    // Cloned so the table can borrow it while the nets borrow the handle mutably.
    let tokenizer = handle.tokenizer.clone();
    let table = Table::parse_with(&tokenizer, T5)?;
    let mut nets = MadladNets::new(handle);
    madlad_post::translate(&mut nets, &table, tag, text)
}

/// Free MADLAD's net, its open weights file and its tokenizer table.
///
/// Exactly once per non-zero handle from `createMadlad`. When it is the last user of the shared
/// `VkDevice`, the device goes away with it.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createMadlad`, and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyMadlad<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this handle came from `createMadlad` and has not been
    // destroyed. `Net`'s Drop waits for the device to go idle before freeing.
    drop(unsafe { Box::from_raw(handle as *mut MadladHandle) });
}

/// Recogniser batch sizes, largest first. A request's strips go through the largest that fits,
/// then smaller ones, then the single-line net, so a 10-line page is two submits rather than ten
/// (each submit costs ~4 ms on a Pixel 8 beyond its GPU time).
const REC_BATCHES: [u32; 3] = [8, 4, 2];

/// The batched recognisers for [`REC_BATCHES`], independent copies of the pass in one plan each.
/// Built on the first request with more than one line: the three cost 48-78 ms on a Pixel 8,
/// which a single-word request should not pay at load.
struct RecBatches {
    shared: std::sync::Arc<crate::vulkan::context::Context>,
    weights: Weights,
    nets: Vec<(u32, Net)>,
}

impl RecBatches {
    fn nets(&mut self, lines: usize) -> Result<&mut [(u32, Net)], String> {
        if self.nets.is_empty() && lines > 1 {
            for batch in REC_BATCHES {
                let plan = ppocr_rec::build_batch(&self.weights, ocr::REC_WIDTH, batch)?;
                self.nets.push((batch, Net::new(self.shared.clone(), plan, &self.weights, PPOCR_REC)?));
            }
        }
        Ok(&mut self.nets)
    }
}

/// Recognise every strip, one result per strip in order. Each line's logits are identical to a
/// single-line run: the batch plans are independent copies, and the strip is preprocessed by the
/// same `to_planar_f16` and rounded to the same fp16 on the way in.
fn recognise_all(rec: &mut Net, batches: &mut RecBatches, strips: &[Vec<i32>]) -> Vec<Result<Vec<f32>, String>> {
    let batches = match batches.nets(strips.len()) {
        Ok(nets) => nets,
        Err(e) => {
            log(&format!("batched recognisers unavailable, one line at a time: {e}"));
            &mut []
        }
    };
    let mut out = Vec::with_capacity(strips.len());
    let mut rest = strips;
    while let Some((first, tail)) = rest.split_first() {
        let fit = batches.iter_mut().find(|(lines, _)| *lines as usize <= rest.len());
        if let Some((lines, net)) = fit {
            let (now, later) = rest.split_at(*lines as usize);
            match infer_batch(net, now) {
                Ok(logits) => out.extend(logits.into_iter().map(Ok)),
                Err(e) => out.extend(now.iter().map(|_| Err(e.clone()))),
            }
            rest = later;
        } else {
            out.push(rec.infer(first, ocr::REC_WIDTH, ppocr_rec::HEIGHT));
            rest = tail;
        }
    }
    out
}

fn infer_batch(net: &mut Net, strips: &[Vec<i32>]) -> Result<Vec<Vec<f32>>, String> {
    let shape = crate::nets::Shape::new(3, ppocr_rec::HEIGHT, ocr::REC_WIDTH);
    let mut half = vec![0u16; shape.len() as usize];
    let mut inputs = Vec::with_capacity(strips.len());
    for strip in strips {
        crate::preprocess::to_planar_f16(strip, ocr::REC_WIDTH, ppocr_rec::HEIGHT, shape, &PPOCR_REC, &mut half)?;
        inputs.push(half.iter().map(|&h| crate::preprocess::f16_to_f32(h)).collect::<Vec<f32>>());
    }
    let refs: Vec<&[f32]> = inputs.iter().map(Vec::as_slice).collect();
    net.infer_raw_many(&refs)
}