fn encode_gemma4<'l>(
    env: &mut Env,
    handle: &Gemma4Handle,
    text: &str,
    specials: &JObjectArray<'l>,
) -> Result<Vec<i32>, String> {
    let table = Table::parse_with(&handle.tokenizer, GEMMA)?;
    let count = specials.len(env).map_err(|e| format!("{e}"))?;
    let mut owned = Vec::with_capacity(count);
    for index in 0..count {
        let item = specials.get_element(env, index).map_err(|e| format!("{e}"))?;
        // Convert to an owned String within this statement so the borrowing
        // `JavaStr` is dropped before the next `get_element` call.
        let item: JString = env.cast_local::<JString>(item).map_err(|e| format!("{e}"))?;
        let text = item.try_to_string(env).map_err(|e| format!("{e}"))?;
        owned.push(text);
    }
    let borrowed: Vec<&str> = owned.iter().map(String::as_str).collect();
    // The JVM's arrays are signed, and the vocabulary fits in a positive `i32` twice over, so the
    // cast is total rather than merely usually right.
    Ok(table.encode_with_specials(text, &borrowed).into_iter().map(|id| id as i32).collect())
}

/// Text for a run of token ids, fusing byte pieces back into characters.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_decodeGemma4<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    tokens: JIntArray<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        if handle == 0 {
            return Ok(std::ptr::null_mut());
        }
        // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
        let handle = unsafe { &*(handle as *const Gemma4Handle) };
        let decoded = read_int_array(env, &tokens).and_then(|ids| {
            let table = Table::parse_with(&handle.tokenizer, GEMMA)?;
            // Negative ids cannot name a piece; dropping them keeps `decode`'s "one bad token loses a
            // word, not the reply" behaviour rather than failing the whole call.
            let ids: Vec<u32> = ids.into_iter().filter_map(|id| u32::try_from(id).ok()).collect();
            Ok(table.decode(&ids))
        });
        Ok(match decoded {
            Ok(text) => match env.new_string(&text) {
                Ok(string) => string.into_raw(),
                Err(e) => {
                    log(&format!("gemma4 cannot return its text: {e}"));
                    std::ptr::null_mut()
                }
            },
            Err(e) => {
                log(&format!("gemma4 cannot decode: {e}"));
                std::ptr::null_mut()
            }
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Feed tokens into the cache without generating. Returns the new position, or -1.
///
/// The prompt path: every token but the last only fills the KV cache, so its logits are computed
/// and thrown away. Doing that here rather than one JNI call at a time keeps a 500-token prompt
/// to one crossing instead of 500.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_pushGemma4<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    tokens: JIntArray<'l>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
        if handle == 0 {
            return Ok(-1);
        }
        // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live. It is
        // `&mut` because a step advances the cache, and Kotlin serialises calls on one handle.
        let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
        let pushed = read_int_array(env, &tokens).and_then(|ids| {
            let mut owned = Vec::with_capacity(ids.len());
            for token in ids {
                owned.push(
                    u32::try_from(token)
                        .map_err(|_| format!("token {token} is not in the vocabulary"))?,
                );
            }
            handle.push(&owned)?;
            Ok(handle.fed())
        });
        Ok(match pushed {
            Ok(position) => jint::try_from(position).unwrap_or(-1),
            Err(e) => {
                log(&format!("gemma4 cannot take the prompt: {e}"));
                -1
            }
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Feed one token and return the next, greedily. -1 on failure.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_stepGemma4<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    token: jint,
) -> jint {
    if handle == 0 {
        return -1;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
    let token = match u32::try_from(token) {
        Ok(token) if token < gemma4::VOCAB => token,
        _ => {
            log(&format!("gemma4 was given token {token}, which is not in the vocabulary"));
            return -1;
        }
    };
    match handle.step_greedy(token) {
        Ok(id) => jint::try_from(id).unwrap_or(-1),
        Err(e) => {
            log(&format!("gemma4 greedy step failed: {e}"));
            -1
        }
    }
}

/// Feed one token and return its 262,144 logits for Kotlin-side sampling. Null on failure.
///
/// The same step [`Java_com_vayunmathur_library_ml_MlNative_stepGemma4`] runs - one token through
/// all thirty-five layers plus the tied head on the host - but the logits cross the boundary
/// unreduced so the caller can sample (top_k, top_p, temperature) with a seed it holds. Greedy
/// decoding keeps calling `stepGemma4`: a 1 MB float array per token is the price of the
/// flexibility, paid only when sampling is wanted.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_logitsGemma4<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    token: jint,
) -> jfloatArray {
    env.with_env(|env| -> jni::errors::Result<jfloatArray> {
        if handle == 0 {
            return Ok(std::ptr::null_mut());
        }
        // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
        let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
        let token = match u32::try_from(token) {
            Ok(token) if token < gemma4::VOCAB => token,
            _ => {
                log(&format!("gemma4 was given token {token}, which is not in the vocabulary"));
                return Ok(std::ptr::null_mut());
            }
        };
        Ok(match handle.step(token, true).and_then(|logits| {
            logits.ok_or_else(|| "a decode step returned no logits".to_string())
        }) {
            Ok(logits) => match new_float_array(env, &logits) {
                Ok(array) => array,
                Err(e) => {
                    log(&format!("gemma4 cannot return its logits: {e}"));
                    std::ptr::null_mut()
                }
            },
            Err(e) => {
                log(&format!("gemma4 logits step failed: {e}"));
                std::ptr::null_mut()
            }
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Positions currently in the KV cache.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_positionGemma4<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return -1;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &*(handle as *const Gemma4Handle) };
    jint::try_from(handle.fed()).unwrap_or(-1)
}

/// A weight source for the scaling probe: every tensor at offset zero.
///
/// They alias, which would be nonsense for inference and is right here - the probe asks how fast
/// a given volume of weights can be pulled through, not what the numbers mean.
struct ProbeSource;

impl crate::nets::WeightSource for ProbeSource {
    fn shaped(&self, _index: usize, _dims: &[u32]) -> Result<u32, String> {
        Ok(0)
    }
    fn shaped_words(&self, _index: usize, _dims: &[u32]) -> Result<u32, String> {
        Ok(0)
    }
    fn count(&self) -> usize {
        3
    }
}

/// A blob of identical bytes with a table whose three tensors all span it.
struct ProbeBlob {
    bytes: Vec<u8>,
    table: Vec<crate::weights::Tensor>,
}

impl crate::weights::Blob for ProbeBlob {
    fn data_len(&self) -> u64 {
        self.bytes.len() as u64
    }
    fn tensors(&self) -> &[crate::weights::Tensor] {
        &self.table
    }
    fn read_at(&self, offset: u64, into: &mut [u8]) -> Result<(), String> {
        let from = offset as usize;
        let span = self.bytes.get(from..from + into.len()).ok_or_else(|| {
            format!("a read of {} at {offset} past {}", into.len(), self.bytes.len())
        })?;
        into.copy_from_slice(span);
        Ok(())
    }
}

/// Compare a storage-buffer read against a texel-buffer read of the same bytes. Returns -1.
///
/// The standing hypothesis for the remaining 3x, and the last one left: every buffer path on this
/// device sits near 5 GB/s, LiteRT needs about 15 to reach its published 89 ms a token, and its
/// Android default is `SetPreferTextureWeights(true)`. If the texture unit is faster here, moving
/// the weights is the whole job. If it is not, a 1.3 GB model simply costs what it costs.
///
/// # Safety
///
/// Called only by the JVM.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_imageProbeGemma4<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jint {
    let Ok(context) = context::shared() else {
        return -1;
    };
    match crate::vulkan::imageprobe::compare(&context) {
        Ok((buffer, texel)) => log(&format!(
            "gemma4 read path: storage buffer {:.2} GB/s, texel buffer {:.2} GB/s ({:.2}x)",
            buffer / 1e9,
            texel / 1e9,
            texel / buffer.max(1.0)
        )),
        Err(why) => log(&format!("gemma4 read path probe failed: {why}")),
    }
    -1
}

/// Log how a gemv's achieved bandwidth varies with the size of the dispatch. Returns -1 always.
///
/// # The question this answers
///
/// On desktop the same shader reaches 12.3 GB/s on a 1.2 MB projection and 33.3 on a 50 MB one.
/// If that curve holds here, the per-layer projections are slow because they are *small*, and
/// merging the ones that share an input - q, k and v all read the same normed vector - is worth
/// a converter change. If the curve is flat, size is not the story and merging buys nothing.
///
/// # Safety
///
/// Called only by the JVM.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_scalingGemma4<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jint {
    let Ok(context) = context::shared() else {
        return -1;
    };
    for out in [1536u32, 2560, 6144, 12288, 24576, 65536] {
        match probe_gemv(&context, out, 1536) {
            Ok((bytes, rate)) => log(&format!(
                "gemma4 scaling {out:>6} x 1536: {:>6.1} MB, {:>6.2} GB/s",
                bytes as f64 / 1e6,
                rate / 1e9
            )),
            Err(why) => log(&format!("gemma4 scaling {out} failed: {why}")),
        }
    }
    -1
}

/// Time one int4 gemv, returning its weight bytes and the rate reached.
fn probe_gemv(
    context: &std::sync::Arc<context::Context>,
    out: u32,
    inputs: u32,
) -> Result<(u64, f64), String> {
    use crate::nets::{Act, Builder, Shape};
    let source = ProbeSource;
    let mut builder = Builder::new(&source);
    let input = builder.input(Shape::new(inputs, 1, 1));
    let projected = builder.conv_int4(input, 0, out, Act::None);
    let plan = builder.finish(&[projected])?;
    let blocks = inputs.div_ceil(32);
    let weight_bytes = (u64::from(out) * u64::from(inputs)) / 2;
    let total = weight_bytes + u64::from(out) * u64::from(blocks) * 2 + u64::from(out) * 2 + 4096;
    let table = vec![
        crate::weights::Tensor {
            rank: 1,
            dims: [(total / 2) as u32, 0, 0, 0],
            offset: 0,
            len: (total / 2) as u32,
            dtype: crate::weights::Dtype::F16,
        };
        3
    ];
    let data = ProbeBlob { bytes: vec![0x11u8; total as usize], table };
    let mut net = Net::new(
        std::sync::Arc::clone(context),
        plan,
        &data,
        crate::preprocess::RESCALE_ONLY,
    )?;
    let feed = vec![0.5f32; inputs as usize];
    net.infer_raw(&feed)?;
    let mut best = f64::MAX;
    for _ in 0..5 {
        let started = std::time::Instant::now();
        net.infer_raw(&feed)?;
        best = best.min(started.elapsed().as_secs_f64());
    }
    Ok((weight_bytes, weight_bytes as f64 / best))
}

/// Log what this GPU can do that the shaders are not yet using. Returns -1 always.
///
/// # Why bother
///
/// Strings in `liblitertlm_jni.so` name `cl_arm_integer_dot_product_accumulate_int8` and
/// `VK_KHR_shader_integer_dot_product` - so LiteRT reaches for a hardware int8 dot product on
/// Mali, and for subgroup reductions, where this runtime uses plain fp32 multiply-accumulate and
/// a shared-memory reduction across 64 lanes.
///
/// Whether either is worth adopting depends on what the device in hand actually reports, and
/// guessing that from a vendor name has already cost this project several wrong turns.
///
/// # Safety
///
/// Called only by the JVM.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_capabilitiesGemma4<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jint {
    let Ok(context) = context::shared() else {
        return -1;
    };
    let wanted = [
        "VK_KHR_shader_integer_dot_product",
        "VK_KHR_cooperative_matrix",
        "VK_KHR_16bit_storage",
        "VK_KHR_shader_float16_int8",
        "VK_KHR_zero_initialize_workgroup_memory",
        "VK_KHR_shader_subgroup_clustered",
    ];
    // SAFETY: the physical device outlives the context, which is shared and alive here.
    let found = unsafe {
        context
            .instance
            .enumerate_device_extension_properties(context.physical_device)
    };
    let Ok(found) = found else {
        return -1;
    };
    let names: Vec<String> = found
        .iter()
        .filter_map(|e| e.extension_name_as_c_str().ok())
        .map(|c| c.to_string_lossy().into_owned())
        .collect();
    for want in wanted {
        log(&format!(
            "gemma4 capability {want}: {}",
            if names.iter().any(|n| n == want) { "yes" } else { "no" }
        ));
    }
    // Subgroup width decides whether a shared-memory reduction over 64 lanes can become a
    // `subgroupAdd`, which is the cheapest of the wins the strings point at.
    let mut subgroup = ash::vk::PhysicalDeviceSubgroupProperties::default();
    let mut props = ash::vk::PhysicalDeviceProperties2::default().push_next(&mut subgroup);
    // SAFETY: as above.
    unsafe {
        context
            .instance
            .get_physical_device_properties2(context.physical_device, &mut props)
    };
    log(&format!(
        "gemma4 subgroup size {}, arithmetic {}",
        subgroup.subgroup_size,
        subgroup
            .supported_operations
            .contains(ash::vk::SubgroupFeatureFlags::ARITHMETIC)
    ));
    -1
}

/// Time one decode pass and one head-free pass, and log both. Returns -1 always.
///
/// # What this settles
///
/// A decode step costs 676 ms on a Tensor G4 against 41 ms on a desktop, and the question is
/// whether that is the **weights** or the **dispatches**. The two answers need completely
/// different work - better kernels versus fusing ops - so guessing is expensive.
///
/// The head is the discriminator. It is four of the pass's 1,094 dispatches, and 402 MB of its
/// 1.30 GB of weights. So running with it and without it:
///
/// * bandwidth-bound -> the head-free pass is about **31% faster**
/// * dispatch-bound  -> it is about **0.4% faster**, which is nothing
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_benchmarkGemma4<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return -1;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
    let Ok((hidden, per_layer)) = gemma4::gather(&handle.embed.reader(), 2) else {
        return -1;
    };
    let local = handle.local[..gemma4::HEAD_DIM as usize].to_vec();
    let global = handle.global[..gemma4::GLOBAL_HEAD_DIM as usize].to_vec();
    // The memory ceiling first, so the two numbers below can be read against it.
    if let Ok(at) = handle.net.at(gemma4::Mode::DecodeStep.at(handle.context)) {
        match at.copy_bandwidth() {
            Ok(rate) => log(&format!(
                "gemma4 benchmark plain copy: {:.1} GB/s, the ceiling for every kernel",
                rate / 1e9
            )),
            Err(why) => log(&format!("gemma4 benchmark copy failed: {why}")),
        }
    }
    for (label, mode) in [
        ("with head   ", gemma4::Mode::DecodeStep),
        ("without head", gemma4::Mode::Prefill { tokens: 1 }),
    ] {
        let mut best = f64::MAX;
        for _ in 0..3 {
            handle.position = 0;
            let Ok(at) = handle.net.at(mode.at(handle.context)) else { continue };
            let _ = at.set_params(StepParams { prefix: 0, window_start: 0 });
            let started = std::time::Instant::now();
            if at.infer_raw_many(&[&hidden, &per_layer, &local, &global]).is_err() {
                continue;
            }
            best = best.min(started.elapsed().as_secs_f64() * 1000.0);
        }
        log(&format!("gemma4 benchmark {label}: {best:.0} ms"));
    }
    handle.position = 0;
    handle.pending.clear();
    -1
}
