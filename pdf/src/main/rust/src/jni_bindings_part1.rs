fn add_stamp_inner<'local>(
    env: &mut Env<'local>,
    handle: jlong,
    page: jint,
    // (x0, y0, x1, y1) stamp rect and (width, height) of the image
    rect: [jfloat; 4],
    img_size: (jint, jint),
    jpeg: JByteArray<'local>,
) -> jlong {
    if jpeg.is_null() {
        throw_iae(env, "jpeg is null");
        return 0;
    }
    let len = jpeg.len(env).unwrap_or(0);
    if len > MAX_JAVA_BYTE_ARRAY_BYTES {
        throw_oom(env, "Image stamp too large (>200MB)");
        return 0;
    }
    let bytes = match env.convert_byte_array(&jpeg) {
        Ok(b) => b,
        Err(_) => {
            env.exception_clear();
            return 0;
        }
    };
    add_stamp(
        handle,
        page,
        rect.map(|v| v as f64),
        img_size.0 as u32,
        img_size.1 as u32,
        &bytes,
    )
    .unwrap_or(0)
}

fn update_text_annotation_inner<'local>(
    env: &mut Env<'local>,
    handle: jlong,
    annot_id: jlong,
    text: JString<'local>,
) -> jboolean {
    let t = match jstr_safe(env, &text) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return false;
        }
    };
    update_free_text(handle, annot_id, &t)
}

fn set_text_field_inner<'local>(
    env: &mut Env<'local>,
    handle: jlong,
    widget_id: jlong,
    value: JString<'local>,
) -> jboolean {
    let v = match jstr_safe(env, &value) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return false;
        }
    };
    set_text_field(handle, widget_id, &v)
}

fn set_choice_field_inner<'local>(
    env: &mut Env<'local>,
    handle: jlong,
    widget_id: jlong,
    value: JString<'local>,
) -> jboolean {
    let v = match jstr_safe(env, &value) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return false;
        }
    };
    set_choice_field(handle, widget_id, &v)
}

fn save_document_inner<'local>(env: &mut Env<'local>, handle: jlong) -> jbyteArray {
    if env.ensure_local_capacity(4).is_err() {
        env.exception_clear();
        return std::ptr::null_mut();
    }
    bytes_or_null_mut(env, save_document(handle))
}

fn save_compressed_inner<'local>(env: &mut Env<'local>, handle: jlong) -> jbyteArray {
    if env.ensure_local_capacity(4).is_err() {
        env.exception_clear();
        return std::ptr::null_mut();
    }
    bytes_or_null_mut(env, save_compressed(handle))
}

fn extract_text_inner<'local>(env: &mut Env<'local>, handle: jlong) -> jstring {
    if env.ensure_local_capacity(4).is_err() {
        env.exception_clear();
        return std::ptr::null_mut();
    }
    let text_opt = document_text(handle);
    let s_opt = match text_opt {
        Some(t) => match env.new_string(t) {
            Ok(js) => Some(js),
            Err(_) => {
                env.exception_clear();
                None
            }
        },
        None => None,
    };
    match s_opt {
        Some(s) => {
            // SAFETY: into_raw transfers ownership of local ref to JVM. JVM GC manages lifetime.
            s.into_raw()
        }
        None => std::ptr::null_mut(),
    }
}

fn search_document_inner_fn<'local>(
    env: &mut Env<'local>,
    handle: jlong,
    query: JString<'local>,
) -> jbyteArray {
    let q = match jstr_safe(env, &query) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return std::ptr::null_mut();
        }
    };
    if env.ensure_local_capacity(4).is_err() {
        env.exception_clear();
        return std::ptr::null_mut();
    }
    bytes_or_null_mut(env, search_document(handle, &q))
}

fn search_document_cs_inner<'local>(
    env: &mut Env<'local>,
    handle: jlong,
    query: JString<'local>,
) -> jbyteArray {
    let q = match jstr_safe(env, &query) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return std::ptr::null_mut();
        }
    };
    if env.ensure_local_capacity(4).is_err() {
        env.exception_clear();
        return std::ptr::null_mut();
    }
    bytes_or_null_mut(env, search_document_case_sensitive(handle, &q))
}

// ---------------------------------------------------------------------------
// JNI entry points — all wrapped in catch_unwind (critical #1)
// ---------------------------------------------------------------------------

/// `PdfNative.openDocument(byte[]) -> long`. Returns a non-zero handle, or
/// 0 on parse failure / encrypted document.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_openDocument<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    data: JByteArray<'local>,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
    match catch_unwind(AssertUnwindSafe(|| open_document_inner(&mut *env, data))) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), jni_str!("Native panic in openDocument"));
            Ok(0)
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.openDocumentWithPassword(byte[], String) -> long`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_openDocumentWithPassword<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    data: JByteArray<'local>,
    password: JString<'local>,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
    match catch_unwind(AssertUnwindSafe(|| {
        open_document_pw_inner(&mut *env, data, password)
    })) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), jni_str!("Native panic in openDocumentWithPassword"));
            Ok(0)
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.pdfPasswordState(byte[]) -> int` (0 none, 1 needs pw, 2 unsupported).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_pdfPasswordState<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    data: JByteArray<'local>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
    match catch_unwind(AssertUnwindSafe(|| pdf_password_state_inner(&mut *env, data))) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), jni_str!("Native panic in pdfPasswordState"));
            Ok(0)
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.saveEncrypted(long, String, String) -> byte[]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_saveEncrypted<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    user_pw: JString<'local>,
    owner_pw: JString<'local>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
    match catch_unwind(AssertUnwindSafe(|| {
        save_encrypted_inner(&mut *env, handle, user_pw, owner_pw)
    })) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), jni_str!("Native panic in saveEncrypted"));
            Ok(std::ptr::null_mut())
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.getPageCount(long) -> int`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_getPageCount<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
    match catch_unwind(AssertUnwindSafe(|| page_count(handle))) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), jni_str!("Native panic in getPageCount"));
            Ok(0)
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.renderPage(long, int) -> byte[]`. Serialized primitives, or
/// `null` on error.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_renderPage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    index: jint,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
    match catch_unwind(AssertUnwindSafe(|| render_page_inner(&mut *env, handle, index))) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), jni_str!("Native panic in renderPage"));
            Ok(std::ptr::null_mut())
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.closeDocument(long)`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_closeDocument<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
    let res = catch_unwind(AssertUnwindSafe(|| {
        close_document(handle);
    }));
    if res.is_err() {
        env.exception_clear();
    }
    Ok(())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.createEmptyDocument() -> long`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_createEmptyDocument<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
    match catch_unwind(AssertUnwindSafe(create_empty_document)) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), jni_str!("Native panic in createEmptyDocument"));
            Ok(0)
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.appendPdf(long, byte[]) -> int` (pages added).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_appendPdf<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    data: JByteArray<'local>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| append_pdf_inner(&mut *env, handle, data))) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), jni_str!("Native panic in appendPdf"));
            Ok(0)
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.appendImagePage(long, byte[], int, int) -> int`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_appendImagePage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    jpeg: JByteArray<'local>,
    w: jint,
    h: jint,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        append_image_page_inner(&mut *env, handle, jpeg, w, h)
    })) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), jni_str!("Native panic in appendImagePage"));
            Ok(0)
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.movePage(long, int, int) -> boolean`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_movePage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    from: jint,
    to: jint,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        // A negative jint is reachable input. `from.max(0) as usize` silently moved
        // page 0 instead, i.e. edited the wrong page rather than refusing.
        match (usize::try_from(from), usize::try_from(to)) {
            (Ok(from), Ok(to)) => move_page(handle, from, to),
            _ => false,
        }
    })) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            Ok(false)
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.removePage(long, int) -> boolean`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_removePage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    index: jint,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        // As in `movePage`: refuse a negative index rather than deleting page 0.
        match usize::try_from(index) {
            Ok(index) => remove_page(handle, index),
            Err(_) => false,
        }
    })) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            Ok(false)
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.rotatePage(long, int, int) -> boolean`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_rotatePage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    index: jint,
    delta: jint,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| rotate_page(handle, index, delta))) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            Ok(false)
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
