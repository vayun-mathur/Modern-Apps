/// `PdfNative.extractPage(long, int) -> byte[]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_extractPage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    index: jint,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
    match catch_unwind(AssertUnwindSafe(|| extract_page_inner(&mut *env, handle, index))) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), jni_str!("Native panic in extractPage"));
            Ok(std::ptr::null_mut())
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `PdfNative.listAnnotations(long, int) -> byte[]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_listAnnotations<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
    match catch_unwind(AssertUnwindSafe(|| {
        list_data_inner(&mut *env, || list_annotations(handle, page))
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

/// `PdfNative.listFormFields(long, int) -> byte[]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_listFormFields<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
    match catch_unwind(AssertUnwindSafe(|| {
        list_data_inner(&mut *env, || list_form_fields(handle, page))
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

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_listLinks<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
    match catch_unwind(AssertUnwindSafe(|| {
        list_data_inner(&mut *env, || list_links(handle, page))
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

#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addTextAnnotation<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
    argb: jint,
    size: jfloat,
    text: JString<'local>,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_free_text_inner(&mut *env, handle, page, [x0, y0, x1, y1], argb, size, text)
    })) {
        Ok(v) => Ok(v),
        Err(_) => {
            env.exception_clear();
            let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), jni_str!("Native panic in addTextAnnotation"));
            Ok(0)
        }
    }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addHighlight<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
    argb: jint,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_highlight(
            handle,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
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

#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addTextMarkup<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
    argb: jint,
    kind: jint,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_text_markup(
            handle,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
            kind,
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

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addNote<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    x: jfloat,
    y: jfloat,
    argb: jint,
    text: JString<'local>,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_note_inner(&mut *env, handle, page, x, y, argb, text)
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

#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addCallout<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    ax: jfloat,
    ay: jfloat,
    bx: jfloat,
    by: jfloat,
    argb: jint,
    size: jfloat,
    text: JString<'local>,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_callout_inner(&mut *env, handle, page, [ax, ay, bx, by], argb, size, text)
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

#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addRectAnnotation<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
    argb: jint,
    line_width: jfloat,
    fill: jboolean,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_square(
            handle,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
            line_width as f64,
            fill,
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

#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addCircleAnnotation<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
    argb: jint,
    line_width: jfloat,
    fill: jboolean,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_circle(
            handle,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
            line_width as f64,
            fill,
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

/// `PdfNative.addPolyAnnotation(long, int, int argb, float width, bool fill, bool closed, float[] pts)`.
#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addPolyAnnotation<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    argb: jint,
    line_width: jfloat,
    fill: jboolean,
    closed: jboolean,
    pts: JFloatArray<'local>,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_poly_inner(&mut *env, handle, page, argb, line_width, (fill, closed), pts)
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

/// `PdfNative.addInkAnnotation(long, int, int argb, float width, float[] pts)`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addInkAnnotation<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    argb: jint,
    line_width: jfloat,
    pts: JFloatArray<'local>,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_ink_inner(&mut *env, handle, page, argb, line_width, pts)
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

/// `PdfNative.addImageStamp(long, int, rect, imgW, imgH, byte[] jpeg)`.
#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addImageStamp<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
    img_w: jint,
    img_h: jint,
    jpeg: JByteArray<'local>,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_stamp_inner(&mut *env, handle, page, [x0, y0, x1, y1], (img_w, img_h), jpeg)
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

#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_updateAnnotationRect<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    annot_id: jlong,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        update_annotation_rect(
            handle,
            page,
            annot_id,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
        )
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
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_updateTextAnnotation<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    annot_id: jlong,
    text: JString<'local>,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        update_text_annotation_inner(&mut *env, handle, annot_id, text)
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
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_deleteAnnotation<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    annot_id: jlong,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        delete_annotation(handle, page, annot_id)
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
