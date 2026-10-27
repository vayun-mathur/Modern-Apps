/// Load a baked prefix cache, so the fixed prompt is never prefilled on device. Returns the
/// positions loaded, or -1.
///
/// # What this saves
///
/// The system block is ~334 positions and the tool declarations take it past a thousand. On a
/// Tensor G4 that is 14 seconds of prefill, paid on every cold start, to compute numbers that
/// are the same on every device - the tokens do not change, so neither do the keys and values.
/// `examples/bake_gemma4_prefix.rs` computes them once and this loads the result.
///
/// The caller must have the matching tokens and set its own reuse record to them, or the next
/// turn will re-feed the prefix and undo the point. It must also be the *same* prefix: native
/// checks only the size, because it has no way to know what tokens produced these numbers.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_loadPrefixGemma4<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    positions: jint,
    cache: JByteArray<'l>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
        if handle == 0 {
            return Ok(-1);
        }
        // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
        let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
        let Ok(positions) = u32::try_from(positions) else {
            return Ok(-1);
        };
        let loaded = env
            .convert_byte_array(&cache)
            .map_err(|e| format!("cannot read the cache: {e}"))
            .and_then(|bytes| {
                if positions > handle.context {
                    // A device on a small tier cannot hold the whole prefix. Refusing leaves it to
                    // prefill normally, which is slow and right, rather than loading a truncated
                    // cache and attending over keys that stop mid-prompt.
                    return Err(format!(
                        "a {positions}-position prefix into a {} cache",
                        handle.context
                    ));
                }
                let at = handle.net.at(gemma4::Mode::DecodeStep.at(handle.context))?;
                at.import_pinned(gemma4::CACHE_TENSORS, positions, &bytes)?;
                handle.position = positions;
                handle.pending.clear();
                Ok(positions)
            });
        Ok(match loaded {
            Ok(positions) => {
                log(&format!("gemma4 loaded a {positions}-position prefix cache"));
                jint::try_from(positions).unwrap_or(-1)
            }
            Err(e) => {
                log(&format!("gemma4 cannot load the prefix cache: {e}"));
                -1
            }
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Positions the cache currently holds, or -1.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_capacityGemma4<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return -1;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &*(handle as *const Gemma4Handle) };
    jint::try_from(handle.context).unwrap_or(-1)
}

/// Grow the cache so `needed` positions fit. Returns the new capacity, or -1.
///
/// **The cache is emptied**: a bigger arena is a different allocation and nothing is copied
/// across, so the caller must feed its whole prompt again afterwards. Returning the capacity
/// rather than a boolean is deliberate - the caller needs the number to decide whether the
/// prompt fits at all.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_growGemma4<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    needed: jint,
) -> jint {
    if handle == 0 {
        return -1;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
    let Ok(needed) = u32::try_from(needed) else {
        return -1;
    };
    match handle.grow(needed) {
        Ok(capacity) => jint::try_from(capacity).unwrap_or(-1),
        Err(e) => {
            log(&format!("gemma4 cannot grow: {e}"));
            -1
        }
    }
}

/// Rewind the cache to `position`, keeping everything before it. Returns the new position, or -1.
///
/// # The point of this
///
/// A turn's prompt is almost entirely the previous turn's prompt: the same system block, the same
/// tool declarations, the same history. Re-feeding all of it is how this started - `generate`
/// called `reset` and pushed the lot - and with 24 tools that is some 1,600 positions of prefill
/// before the model has seen a single new word, on every message.
///
/// The KV cache for that prefix is still sitting in the arena, still correct, because the tokens
/// that produced it have not changed. Seeking to the length of the unchanged prefix and pushing
/// only the new suffix turns a 1,600-position prefill into a 15-position one.
///
/// Rewinding is safe for exactly the reason [`Java_com_vayunmathur_library_ml_MlNative_resetGemma4`]
/// is: attention reads `[window_start, prefix]` and never past it, so the rows above `position`
/// are unreachable until something overwrites them. It is the caller's job to be sure the tokens
/// below `position` really are unchanged - native cannot check that, and a wrong seek is a model
/// answering a conversation that never happened.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_seekGemma4<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    position: jint,
) -> jint {
    if handle == 0 {
        return -1;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
    match u32::try_from(position) {
        Ok(position) if position <= handle.position => {
            handle.position = position;
            handle.pending.clear();
            jint::try_from(position).unwrap_or(-1)
        }
        // Inside what was pushed but not yet run: keep that much of it.
        Ok(position) if position <= handle.fed() => {
            handle.pending.truncate((position - handle.position) as usize);
            jint::try_from(position).unwrap_or(-1)
        }
        // Forward is refused: those rows were never written, so attending over them would read
        // whatever the arena happened to hold.
        _ => {
            log(&format!("gemma4 cannot seek to {position} from {}", handle.position));
            -1
        }
    }
}

/// Run the pushed tokens now instead of with the next step. Returns the position, or -1.
///
/// For warming a prompt prefix ahead of the first turn: without this the prefill would wait
/// for the step that ends the user's turn.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_flushGemma4<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return -1;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
    match handle.flush() {
        Ok(()) => jint::try_from(handle.position).unwrap_or(-1),
        Err(e) => {
            log(&format!("gemma4 cannot flush: {e}"));
            -1
        }
    }
}

/// Start a new conversation on the same handle.
///
/// Only the position is reset. Attention reads `[window_start, prefix]`, so cache rows past the
/// new prefix are never read again and overwriting them lazily costs nothing - clearing them
/// would be a gigabyte of pointless writes between every turn.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_resetGemma4<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
    handle.position = 0;
    handle.pending.clear();
}

/// Release the handle. Idempotent from Kotlin's side, which zeroes its field first.
///
/// # Safety
///
/// Called only by the JVM, once, with a handle from `createGemma4` that nothing else is using.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyGemma4<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this runs once, after every other call on the handle.
    drop(unsafe { Box::from_raw(handle as *mut Gemma4Handle) });
}

/// Feed soft tokens - an encoder's output - into the decoder's cache.
///
/// `embeddings` is `n * 1536` floats, one row per soft token, and it advances the position by `n`.
/// Returns the number fed, or -1. The logits are discarded: a soft token is never the last thing
/// in a prompt, because the template closes the image with `<eoi>` and opens a model turn.
///
/// This is the seam the vision tower attaches to. The decoder is text-only and gathers a row of
/// the embedding table per token; an image has no token to gather, so its rows arrive here
/// instead. See `Gemma4Handle::gather_soft` for why the per-layer half still comes from the table.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_pushSoftGemma4<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    embeddings: JFloatArray<'l>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
        if handle == 0 {
            return Ok(-1);
        }
        // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live, and
        // that no other call on it overlaps this one.
        let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
        let fed = read_float_array(env, &embeddings).and_then(|values| {
            // Soft tokens follow whatever was pushed before them, so that goes in first.
            handle.flush()?;
            handle.prefill_soft(&values)?;
            Ok((values.len() / gemma4::D_MODEL as usize) as jint)
        });
        Ok(match fed {
            Ok(count) => count,
            Err(e) => {
                log(&format!("gemma4 could not take soft tokens: {e}"));
                -1
            }
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Gemma 4's vision tower, which is its own `.maml` and its own graph id.
///
/// Separate from [`Gemma4Handle`] because it is optional: a device that never sends an image never
/// downloads it, and a handle that failed to build must not take the assistant down with it.
struct Gemma4VisionHandle {
    net: Reshaped<gemma4_vision::Mode>,
    /// Retained for the two position tables, which are gathered on the host.
    weights: Streamed,
}

fn gemma4_vision_plan(offsets: &Offsets, mode: gemma4_vision::Mode) -> Result<Plan, String> {
    gemma4_vision::build(offsets, mode)
}

/// Bring up the vision tower from its `.maml`. Returns 0 on failure, having logged why.
///
/// # Safety
///
/// Called only by the JVM, with a descriptor the caller detached and nothing else holds.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createGemma4Vision<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    length: jlong,
) -> jlong {
    if fd < 0 {
        log(&format!("the gemma4 vision tower is unavailable: descriptor {fd} is not open"));
        return 0;
    }
    // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes it
    // on drop including on every failure path below.
    let file = unsafe { File::from_raw_fd(fd) };
    match build_gemma4_vision(file, offset, length) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("the gemma4 vision tower is unavailable: {e}"));
            0
        }
    }
}

fn build_gemma4_vision(file: File, offset: jlong, length: jlong) -> Result<Gemma4VisionHandle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::GEMMA4_VISION)?;
    if weights.len() != gemma4_vision::TENSORS {
        return Err(format!("a file of {} tensors, not {}", weights.len(), gemma4_vision::TENSORS));
    }
    // Recorded at the grid a square image resolves to. `at` re-records when a later image has a
    // different aspect ratio, which against sixteen layers over a couple of thousand patches
    // costs nothing worth avoiding.
    let start = gemma4_vision::Grid::for_image(1, 1, gemma4_vision::DEFAULT_SOFT_TOKENS)?;
    let net = Reshaped::streamed(
        context::shared()?,
        weights.offsets(),
        &weights,
        gemma4_vision::Mode::Image(start),
        gemma4_vision_plan,
    )?;
    Ok(Gemma4VisionHandle { net, weights })
}

/// The pixel size an image of `width x height` must be resized to, as `[width, height]`.
///
/// Kotlin does the resize - it is bitmap work the platform does better, as it is for TinyCLIP -
/// but it cannot choose the size, because the target is the reference preprocessor's
/// aspect-ratio-preserving fit to a patch budget and getting it wrong by one block changes the
/// number of soft tokens. So the runtime decides and Kotlin obeys.
///
/// Returns null if the budget or the image is one no grid exists for.
///
/// # Safety
///
/// Called only by the JVM.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_gemma4VisionSize<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    width: jint,
    height: jint,
    soft_tokens: jint,
) -> jintArray {
    env.with_env(|env| -> jni::errors::Result<jintArray> {
        let null = std::ptr::null_mut();
        let sized = u32::try_from(width)
            .ok()
            .zip(u32::try_from(height).ok())
            .zip(u32::try_from(soft_tokens).ok());
        let Some(((width, height), soft_tokens)) = sized else {
            return Ok(null);
        };
        Ok(match gemma4_vision::Grid::for_image(width, height, soft_tokens) {
            Ok(grid) => {
                let (w, h) = grid.pixels();
                match new_int_array(env, &[w as i32, h as i32]) {
                    Ok(array) => array,
                    Err(e) => {
                        log(&format!("cannot return a vision size: {e}"));
                        null
                    }
                }
            }
            Err(e) => {
                log(&format!("no vision grid for {width}x{height}: {e}"));
                null
            }
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Encode one image into soft tokens: `[n, 1536]` flattened, or null.
///
/// `pixels` is ARGB_8888 at exactly the size [`Java_com_vayunmathur_library_ml_MlNative_gemma4VisionSize`]
/// asked for. The result goes straight to `pushSoftGemma4`, unscaled - the reference scatters the
/// tower's output into the decoder's embeddings as it is, while text embeddings carry a
/// `sqrt(hidden_size)` the converter folded into the table. Scaling these to match would be
/// wrong in a way nothing downstream would flag.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4Vision`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_encodeImageGemma4<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    pixels: JIntArray<'l>,
    width: jint,
    height: jint,
) -> jfloatArray {
    env.with_env(|env| -> jni::errors::Result<jfloatArray> {
        let null = std::ptr::null_mut();
        if handle == 0 {
            return Ok(null);
        }
        // SAFETY: the caller guarantees the handle came from `createGemma4Vision` and is still live.
        // It is `&mut` because a new aspect ratio re-records the net, and Kotlin serialises calls.
        let handle = unsafe { &mut *(handle as *mut Gemma4VisionHandle) };
        let encoded = read_int_array(env, &pixels)
            .and_then(|values| run_gemma4_vision(handle, &values, width, height));
        Ok(match encoded.and_then(|values| new_float_array(env, &values)) {
            Ok(array) => array,
            Err(e) => {
                log(&format!("the gemma4 vision tower failed: {e}"));
                null
            }
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

fn run_gemma4_vision(
    handle: &mut Gemma4VisionHandle,
    pixels: &[i32],
    width: jint,
    height: jint,
) -> Result<Vec<f32>, String> {
    let (Ok(width), Ok(height)) = (u32::try_from(width), u32::try_from(height)) else {
        return Err(format!("an image of {width}x{height}"));
    };
    if !width.is_multiple_of(gemma4_vision::PATCH) || !height.is_multiple_of(gemma4_vision::PATCH) {
        return Err(format!("{width}x{height} is not a whole number of patches"));
    }
    let grid = gemma4_vision::Grid::new(height / gemma4_vision::PATCH, width / gemma4_vision::PATCH)?;
    let inputs = gemma4_vision::prepare(&handle.weights.reader(), grid, pixels)?;
    let mode = gemma4_vision::Mode::Image(grid);
    let out = handle
        .net
        .at(mode)?
        .infer_raw_many(&[&inputs[0], &inputs[1], &inputs[2]])?;
    let features = one_output(out)?;
    let tokens = grid.soft_tokens() as usize;
    let width = gemma4_vision::OUT_DIM as usize;
    if features.len() != tokens * width {
        return Err(format!("{} values, not {}", features.len(), tokens * width));
    }
    // The plan writes `[1536, 1, tokens]`; the decoder reads one soft token at a time, so this
    // hands back `[tokens, 1536]`.
    Ok(transpose(&features, width, tokens))
}

/// Release the vision tower.
///
/// # Safety
///
/// Called only by the JVM, once, with a handle from `createGemma4Vision` that nothing else uses.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyGemma4Vision<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this runs once, after every other call on the handle.
    drop(unsafe { Box::from_raw(handle as *mut Gemma4VisionHandle) });
}
