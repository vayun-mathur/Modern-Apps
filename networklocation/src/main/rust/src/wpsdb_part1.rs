/// `lookup(handle, keyHi, keyLo) -> double[4]` (`[lat, lon, accuracyMeters, altitudeMeters]`),
/// or null for an unknown key. The key is 128 bits split across two longs: a 48-bit WiFi MAC
/// leaves `keyHi` zero, an 84-bit cell key does not. A negative accuracy means the store had
/// none; a NaN altitude means the same (all kind-1 stores, and cell records generally).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_WpsStoreNative_lookup<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    key_hi: jlong,
    key_lo: jlong,
) -> jdoubleArray {
    env.with_env(|env| -> jni::errors::Result<jdoubleArray> {
        if handle == 0 {
            return Ok(std::ptr::null_mut());
        }
        let reader = unsafe { &*(handle as *const Handle) };
        let key = ((key_hi as u64 as u128) << 64) | (key_lo as u64 as u128);
        match reader.lookup(key) {
            Some((lat, lon, acc, alt)) => {
                let arr = match env.new_double_array(4) {
                    Ok(a) => a,
                    Err(_) => return Ok(std::ptr::null_mut()),
                };
                if arr.set_region(env, 0, &[lat, lon, acc, alt]).is_err() {
                    return Ok(std::ptr::null_mut());
                }
                Ok(arr.into_raw())
            }
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `close(handle)` — frees the reader, its mapping and its dup'd fd. Safe to call with 0.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_WpsStoreNative_close<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut Handle));
        }
    }
}
