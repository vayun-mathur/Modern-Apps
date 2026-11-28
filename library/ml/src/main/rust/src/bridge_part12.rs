/// Recognise every line in an RGBA camera plane read in place, and return
/// them in the same tab-separated form as `recognizeText`.
///
/// The plane arrives as a **direct** `ByteBuffer` (CameraX `ImageProxy`
/// planes are direct), so no pixel copy crosses JNI at all: this reads the
/// buffer at its native address, and [`crate::frame::rgba_to_argb_upright`]
/// does the viewport crop and quarter-turn rotation into the handle's
/// reused pixel `Vec` — the detector letterbox, rotated line crops and
/// recognition then run exactly as the Bitmap path's do, over identical
/// pixels. Coordinates in the result are in upright cropped-frame pixels.
///
/// Returns null on failure, on a `0` handle, on a non-direct buffer, and
/// on any plane this path does not convert (non-RGBA stride, non-quarter
/// rotation, undersized buffer) — every one of those is the caller's cue
/// to fall back to the Bitmap path, never a wrong answer. An empty string
/// means no text, as in `recognizeText`.
///
/// # Safety
///
/// `handle` must be `0` or a value returned by
/// [`Java_com_vayunmathur_library_ml_MlNative_createPpocr`] and not yet
/// destroyed, serialised against `destroyOcr` by the caller. The caller
/// must keep the image the buffer belongs to open for the whole call
/// (closing the `ImageProxy` first would let the camera reuse the plane).
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_recognizeTextBuffer<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    buffer: JByteBuffer<'l>,
    width: jint,
    height: jint,
    row_stride: jint,
    pixel_stride: jint,
    crop_left: jint,
    crop_top: jint,
    crop_right: jint,
    crop_bottom: jint,
    rotation_degrees: jint,
) -> jstring {
    // Held for the whole pass, as in `recognizeText` (issue #944).
    let _lifetime = ocr_use_guard();
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        if handle == 0 || width <= 0 || height <= 0 || row_stride <= 0 || pixel_stride <= 0 {
            return Ok(null);
        }
        // SAFETY: the caller guarantees the handle came from `createPpocr`;
        // `OCR_LIFETIME` (held above) guarantees it is not destroyed concurrently.
        let state = unsafe { &mut *(handle as *mut OcrHandle) };
        let address = match env.get_direct_buffer_address(&buffer) {
            Ok(a) if !a.is_null() => a,
            _ => return Ok(null),
        };
        let capacity = match env.get_direct_buffer_capacity(&buffer) {
            Ok(c) => c,
            Err(_) => return Ok(null),
        };
        // SAFETY: the buffer is a live local reference for the whole call
        // and the caller keeps its ImageProxy open, so the address stays
        // valid while the slice is read; nothing is retained past return.
        let bytes = unsafe { std::slice::from_raw_parts(address, capacity) };
        let crop = crate::frame::Crop {
            left: crop_left,
            top: crop_top,
            right: crop_right,
            bottom: crop_bottom,
        };
        let (upright_w, upright_h) = match crate::frame::rgba_to_argb_upright(
            bytes,
            width as u32,
            height as u32,
            row_stride as u32,
            pixel_stride as u32,
            crop,
            rotation_degrees,
            &mut state.pixels,
        ) {
            Ok(size) => size,
            Err(e) => {
                log(&format!("buffer OCR frame refused: {e}"));
                return Ok(null);
            }
        };
        let lines = match read_text(state, upright_w, upright_h) {
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

