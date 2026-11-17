#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_detachAnnotation<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    annot_id: jlong,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        detach_annotation(handle, page, annot_id)
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

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_reattachAnnotation<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    annot_id: jlong,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        reattach_annotation(handle, page, annot_id)
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

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_duplicateAnnotation<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    annot_id: jlong,
    dx: jfloat,
    dy: jfloat,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        duplicate_annotation(handle, page, annot_id, dx as f64, dy as f64)
    })) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            Ok(0)
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_setTextField<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    widget_id: jlong,
    value: JString<'local>,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        set_text_field_inner(&mut *env, handle, widget_id, value)
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

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_setCheckbox<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    widget_id: jlong,
    on: jboolean,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        set_checkbox(handle, widget_id, on)
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

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_setChoiceField<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    widget_id: jlong,
    value: JString<'local>,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        set_choice_field_inner(&mut *env, handle, widget_id, value)
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

/// `PdfNative.saveDocument(long) -> byte[]`. Serialized modified PDF.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_saveDocument<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
    match catch_unwind(AssertUnwindSafe(|| save_document_inner(&mut *env, handle))) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), jni_str!("Native panic in saveDocument"));
            Ok(std::ptr::null_mut())
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.saveCompressed(long) -> byte[]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_saveCompressed<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
    match catch_unwind(AssertUnwindSafe(|| save_compressed_inner(&mut *env, handle))) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            Ok(std::ptr::null_mut())
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.flattenDocument(long) -> boolean`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_flattenDocument<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| flatten_document(handle))) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            Ok(false)
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.applyRedactions(long) -> boolean`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_applyRedactions<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| apply_redactions(handle))) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            Ok(false)
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.hasRedactions(long) -> boolean`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_hasRedactions<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
    match catch_unwind(AssertUnwindSafe(|| has_redactions(handle))) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            Ok(false)
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.addRedaction(long, int, f,f,f,f) -> long`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addRedaction<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_redaction(
            handle,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
        )
        .unwrap_or(0)
    })) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            Ok(0)
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.extractText(long) -> String` (null on failure).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_extractText<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
    match catch_unwind(AssertUnwindSafe(|| extract_text_inner(&mut *env, handle))) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            Ok(std::ptr::null_mut())
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.listOutline(long) -> byte[]`. Serialized document outline.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_listOutline<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
    match catch_unwind(AssertUnwindSafe(|| {
        list_data_inner(&mut *env, || list_outline(handle))
    })) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            Ok(std::ptr::null_mut())
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.searchDocument(long, String) -> byte[]`. Serialized matches.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_searchDocument<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    query: JString<'local>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
    match catch_unwind(AssertUnwindSafe(|| {
        search_document_inner_fn(&mut *env, handle, query)
    })) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            Ok(std::ptr::null_mut())
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.searchDocumentCaseSensitive(long, String) -> byte[]`. Phase 7 toggle.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_searchDocumentCaseSensitive<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    query: JString<'local>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
    match catch_unwind(AssertUnwindSafe(|| {
        search_document_cs_inner(&mut *env, handle, query)
    })) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            Ok(std::ptr::null_mut())
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.buildSearchIndex(long)`. Prebuilds the text index so the first
/// search is instant; safe to call on a background thread.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_buildSearchIndex<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let _ = ensure_index(handle);
    }));
    if res.is_err() {
        env.exception_clear();
    }
    Ok(())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
