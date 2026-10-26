// Gemma-4 bridge free functions, split out of `bridge_part6.rs` so each
// file stays under the 500-line Rust limit: the host `combine`, the
// head/gather plan constructors, the prefill chunking helpers, and the
// `createGemma4` / `encodeGemma4` JNI entry points with their builder.
//
// Same module scope via `bridge.rs` (`include!`), so item order is
// irrelevant — e.g. `CHUNK` here is read by `prefill` in part6.

/// Host `combine`: projection + grouped-norm + scaled add over rows the
/// caller already gathered.
///
/// A thin wrapper over `gemma4::combine_cached` — the single shared
/// implementation, served from the process-constant combine cache (see
/// `nets::gemma4_part4`). The device combine plan
/// (`gemma4_gather::build_plan`) must agree bit-for-bit — the
/// `--gather-parity` gate in `run_gemma4` enforces it.
fn gather_combine_host(
    embed: &Streamed,
    hidden: &[f32],
    embedded: &[f32],
) -> Result<Vec<f32>, String> {
    crate::nets::gemma4::combine_cached(&embed.reader(), hidden, embedded)
}

/// The device combine plan over an EMBED file, uploading only the ~13 MB it reads
/// (`Reshaped::windowed`), or `None` when the file predates the plan's tensors.
/// Keyed by column count: 1 for a decode step, the chunk width for a prefill.
fn gemma4_gather_plan(embed: &Streamed) -> Option<Reshaped<u32>> {
    Reshaped::windowed(
        context::shared().ok()?,
        embed.offsets(),
        embed,
        &[1],
        |offsets, width| crate::nets::gemma4_gather::build_plan_at(offsets, width),
    )
    .ok()
}

/// The head over the standalone head file (`graph::GEMMA4_HEAD`, the 48
/// int8 triples, ~385 MB): one upload, one recording per `HeadOut` (greedy id,
/// the 16 logit splits, the device top 64). `None` when the file does not match.
fn gemma4_head_file_plan(head: &Streamed) -> Option<Reshaped<crate::nets::gemma4_head::HeadOut>> {
    let offsets = head.offsets();
    if offsets.len() != crate::nets::gemma4_head::HEAD_FILE_TENSORS {
        return None;
    }
    Reshaped::streamed(
        context::shared().ok()?,
        offsets,
        head,
        crate::nets::gemma4_head::HeadOut::Greedy,
        |offsets, out| crate::nets::gemma4_head::build_plan_standalone(offsets, out),
    )
    .ok()
}
/// Positions one prefill submit covers. Every chunk attends the whole cache, so the split only
/// trades GEMM width against the `8 x CHUNK x context` score maps (4 MB each at 256 x 1024).
const CHUNK: u32 = 256;

/// Grow the arena to the widest prefill chunk, then record the decode step. An arena growth drops
/// every kept recording, so without this the first prompt re-records the decode step for the
/// first token. A new prompt width then only records its own plan, built while its rows gather.
fn warm_gemma4(net: &mut Reshaped<gemma4::Pass>, context: u32) -> Result<(), String> {
    let width = CHUNK.min(context);
    net.at(gemma4::Mode::Prefill { tokens: width }.at(context))?;
    net.at(gemma4::Mode::PrefillFresh { tokens: width }.at(context))?;
    net.at(gemma4::Mode::DecodeStep.at(context))?;
    Ok(())
}

/// The most likely token, and nothing else.
///
/// Greedy. litertlm sampled with `top_k` 64 and `top_p` 0.95 and this does not, which is a real
/// behavioural change: replies become deterministic and slightly flatter. Sampling belongs on the
/// Kotlin side of the boundary where a seed can be held and a temperature exposed, and adding it
/// here would put a policy decision in the wrong module.
fn argmax(logits: &[f32]) -> u32 {
    let mut best = (f32::NEG_INFINITY, 0u32);
    for (index, &value) in logits.iter().enumerate() {
        if value > best.0 {
            best = (value, index as u32);
        }
    }
    best.1
}

/// Bring up Gemma 4 from its two `.maml`s and its tokenizer table. Returns 0 on failure.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env`, arrays it owns, and descriptors nothing else holds.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createGemma4<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    text_fd: jint,
    text_offset: jlong,
    text_length: jlong,
    embed_fd: jint,
    embed_offset: jlong,
    embed_length: jlong,
    head_fd: jint,
    head_offset: jlong,
    head_length: jlong,
    tokenizer: JByteArray<'l>,
    // Bytes of KV cache this device will spend. See `gemma4::tier_for`.
    budget: jlong,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
        // Both descriptors are adopted before anything may fail. The caller detached them, so a path
        // that returns without wrapping one leaks it for the life of the process - and there are
        // three here (the head is optional), so the usual single-`fd` shape is not enough.
        if text_fd < 0 || embed_fd < 0 {
            log(&format!("gemma4 is unavailable: descriptors {text_fd} and {embed_fd}"));
            return Ok(0);
        }
        // SAFETY: the caller detached both, so nothing else owns them, and `File` closes them on drop
        // including on every failure path below.
        let text = unsafe { File::from_raw_fd(text_fd) };
        let embed = unsafe { File::from_raw_fd(embed_fd) };
        // The head file is optional: -1 means absent (old downloads), and native
        // falls back to the host head. A non-negative fd is adopted like the rest.
        let head: Option<File> = if head_fd >= 0 {
            // SAFETY: same contract as text/embed above.
            Some(unsafe { File::from_raw_fd(head_fd) })
        } else {
            None
        };
        let spans = (
            u64::try_from(text_offset),
            u64::try_from(text_length),
            u64::try_from(embed_offset),
            u64::try_from(embed_length),
            u64::try_from(head_offset),
            u64::try_from(head_length),
        );
        let built = match spans {
            (Ok(ta), Ok(tl), Ok(ea), Ok(el), Ok(ha), Ok(hl)) => {
                build_gemma4(env, text, ta, tl, embed, ea, el, head, ha, hl, &tokenizer, budget.max(0) as u64)
            }
            _ => Err("a graph span that is not a positive offset and length".to_string()),
        };
        Ok(match built {
            Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
            Err(e) => {
                log(&format!("gemma4 is unavailable: {e}"));
                0
            }
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[allow(clippy::too_many_arguments)]
fn build_gemma4<'l>(
    env: &Env,
    text: File,
    text_at: u64,
    text_len: u64,
    embed: File,
    embed_at: u64,
    embed_len: u64,
    head: Option<File>,
    head_at: u64,
    head_len: u64,
    tokenizer: &JByteArray<'l>,
    budget: u64,
) -> Result<Gemma4Handle, String> {
    let weights = Streamed::open(text, text_at, text_len, graph::GEMMA4_TEXT)?;
    let embed = Streamed::open(embed, embed_at, embed_len, graph::GEMMA4_EMBED)?;
    // The optional head file: opened (header-checked against GEMMA4_HEAD)
    // only when the caller passed a live descriptor. `None` keeps the old
    // two-file behavior exactly — host head, no third upload.
    let head_file: Option<Streamed> = match head {
        Some(file) => Some(Streamed::open(file, head_at, head_len, graph::GEMMA4_HEAD)?),
        None => None,
    };
    let tokenizer = env
        .convert_byte_array(tokenizer)
        .map_err(|e| format!("cannot read the tokenizer table: {e}"))?;
    // Parsed once here to refuse a bad table at construction rather than at the first turn, when
    // the UI has already committed to having a working assistant.
    let parsed = Table::parse_with(&tokenizer, GEMMA)?;
    if parsed.len() != gemma4::VOCAB as usize {
        return Err(format!("a tokenizer of {} pieces, not {}", parsed.len(), gemma4::VOCAB));
    }
    if !parsed.has_byte_fallback() {
        return Err("a tokenizer without byte fallback, which cannot spell every reply".into());
    }
    let reader = weights.reader();
    let local = reader.fp16(gemma4::ROTARY_LOCAL, &[gemma4::MAX_CONTEXT, gemma4::HEAD_DIM])?;
    let global =
        reader.fp16(gemma4::ROTARY_GLOBAL, &[gemma4::MAX_CONTEXT, gemma4::GLOBAL_HEAD_DIM])?;
    drop(reader);
    // The largest cache this device's memory budget affords. Kotlin measures the device;
    // native turns that into positions, because only native knows a position costs 18 KB.
    let cache = gemma4::tier_for(budget);
    log(&format!(
        "gemma4 cache {cache} positions, {} MB, from a {} MB budget",
        (u64::from(cache) * u64::from(gemma4::BYTES_PER_POSITION)) / 1_000_000,
        budget / 1_000_000,
    ));
    let mut net = Reshaped::streamed(
        context::shared()?,
        weights.offsets(),
        &weights,
        gemma4::Mode::DecodeStep.at(gemma4::CONTEXT_TIERS[0]),
        gemma4_plan,
    )?;
    warm_gemma4(&mut net, gemma4::CONTEXT_TIERS[0])?;
    // Never `Reshaped::streamed` over EMBED: it uploads the whole 3.6 GB file, which on top of
    // TEXT OOM-kills a Pixel 8. The gather net is windowed to the tensors it reads.
    let mut head = head_file.as_ref().and_then(gemma4_head_file_plan);
    if let Some(head) = head.as_mut() {
        // Each mode's first recording costs ~70 ms on a Pixel 8; a sampled first token would pay
        // it. Logits first: it has the largest readback, so the staging grows once.
        use crate::nets::gemma4_head::HeadOut;
        for out in [HeadOut::Logits, HeadOut::TopK, HeadOut::Greedy] {
            head.at(out)?;
        }
    }
    let mut gather_net = gemma4_gather_plan(&embed);
    if let Some(gather) = gather_net.as_mut() {
        gather.at(CHUNK)?;
        gather.at(1)?;
    }
    Ok(Gemma4Handle {
        net,
        head,
        gather_net,
        weights,
        embed,
        head_file,
        tokenizer,
        local,
        global,
        ceiling: cache,
        // Start at the smallest tier whatever the device affords. A conversation that stays
        // short never pays for a cache it does not use, and 19 MB against 301 MB is the
        // difference between the assistant being a background cost and being the reason
        // something else was killed.
        context: gemma4::CONTEXT_TIERS[0],
        position: 0,
        pending: Vec::new(),
    })
}

/// Encode text to token ids, matching HuggingFace's `tokenizers` for this vocabulary.
///
/// `specials` are matched literally and never merged into - the chat markers a template inserts.
/// Passing them from Kotlin rather than hardcoding them here is deliberate: which markers a
/// prompt may contain is a policy question, and a model that let a user's text spell a turn
/// boundary would let them forge one.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_encodeGemma4<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    text: JString<'l>,
    specials: JObjectArray<'l>,
) -> jintArray {
    env.with_env(|env| -> jni::errors::Result<jintArray> {
        if handle == 0 {
            return Ok(std::ptr::null_mut());
        }
        // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
        let handle = unsafe { &*(handle as *const Gemma4Handle) };
        let encoded = match text.try_to_string(env) {
            Ok(text) => encode_gemma4(env, handle, &text, &specials),
            Err(e) => Err(format!("cannot read the prompt: {e}")),
        };
        Ok(match encoded {
            Ok(ids) => match new_int_array(env, &ids) {
                Ok(array) => array,
                Err(e) => {
                    log(&format!("gemma4 cannot return its token ids: {e}"));
                    std::ptr::null_mut()
                }
            },
            Err(e) => {
                log(&format!("gemma4 cannot encode: {e}"));
                std::ptr::null_mut()
            }
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
