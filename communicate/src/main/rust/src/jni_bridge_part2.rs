#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_createSenderKey<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        match catch_unwind(AssertUnwindSafe(|| create_sender_key_inner(&mut *env))) {
            Ok(v) => Ok(v),
            Err(_) => {
                env.exception_clear();
                let _ = env.throw_new(
                    jni_str!("java/lang/RuntimeException"),
                    jni_str!("Native panic in createSenderKey"),
                );
                Ok(std::ptr::null_mut())
            }
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_processSenderKey<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    skdm: JByteArray<'local>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        match catch_unwind(AssertUnwindSafe(|| process_sender_key_inner(&mut *env, skdm))) {
            Ok(v) => Ok(v),
            Err(_) => {
                env.exception_clear();
                let _ = env.throw_new(
                    jni_str!("java/lang/RuntimeException"),
                    jni_str!("Native panic in processSenderKey"),
                );
                Ok(std::ptr::null_mut())
            }
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_encryptGroup<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    state: JByteArray<'local>,
    plaintext: JByteArray<'local>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        match catch_unwind(AssertUnwindSafe(|| encrypt_group_inner(&mut *env, state, plaintext))) {
            Ok(v) => Ok(v),
            Err(_) => {
                env.exception_clear();
                let _ = env.throw_new(
                    jni_str!("java/lang/RuntimeException"),
                    jni_str!("Native panic in encryptGroup"),
                );
                Ok(std::ptr::null_mut())
            }
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_decryptGroup<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    state: JByteArray<'local>,
    ciphertext: JByteArray<'local>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        match catch_unwind(AssertUnwindSafe(|| decrypt_group_inner(&mut *env, state, ciphertext))) {
            Ok(v) => Ok(v),
            Err(_) => {
                env.exception_clear();
                let _ = env.throw_new(
                    jni_str!("java/lang/RuntimeException"),
                    jni_str!("Native panic in decryptGroup"),
                );
                Ok(std::ptr::null_mut())
            }
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

// -- Signal sealed sender (same Rust primitives, Signal JNI class) --

fn sealed_sender_encrypt_inner<'a>(
    env: &mut Env,
    plaintext: JByteArray<'a>,
    recipient_aci: jni::objects::JString<'a>,
) -> jbyteArray {
    let pt = match bytes_in(&mut *env, &plaintext) {
        Some(b) => b,
        None => { throw_runtime(&mut *env, "plaintext null"); return std::ptr::null_mut(); }
    };
    let aci: String = match recipient_aci.try_to_string(env) {
        Ok(s) => s,
        Err(_) => { throw_runtime(&mut *env, "recipientAci null"); return std::ptr::null_mut(); }
    };
    let sealed = signal::sealed_sender_encrypt(&pt, &aci);
    bytes_out(&mut *env, &sealed)
}

fn sealed_sender_decrypt_inner<'a>(
    env: &mut Env,
    ciphertext: JByteArray<'a>,
) -> jbyteArray {
    let ct = match bytes_in(&mut *env, &ciphertext) {
        Some(b) => b,
        None => { throw_runtime(&mut *env, "ciphertext null"); return std::ptr::null_mut(); }
    };
    // Try empty-key path (Kotlin supplies ACI out-of-band; Rust stub uses empty for round-trip tests)
    let pt = match signal::sealed_sender_decrypt_any(&ct) {
        Ok(p) => p,
        Err(CryptoError(e)) => { throw_runtime(&mut *env, e); return std::ptr::null_mut(); }
    };
    bytes_out(&mut *env, &pt)
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_signal_e2e_RustSignalCrypto_sealedSenderEncrypt<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    plaintext: JByteArray<'local>,
    recipient_aci: jni::objects::JString<'local>,
    _recipient_device_id: jint,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        match catch_unwind(AssertUnwindSafe(|| sealed_sender_encrypt_inner(&mut *env, plaintext, recipient_aci))) {
            Ok(v) => Ok(v),
            Err(_) => { env.exception_clear(); let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), jni_str!("Native panic in sealedSenderEncrypt")); Ok(std::ptr::null_mut()) }
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_signal_e2e_RustSignalCrypto_sealedSenderDecrypt<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    ciphertext: JByteArray<'local>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        match catch_unwind(AssertUnwindSafe(|| sealed_sender_decrypt_inner(&mut *env, ciphertext))) {
            Ok(v) => Ok(v),
            Err(_) => { env.exception_clear(); let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), jni_str!("Native panic in sealedSenderDecrypt")); Ok(std::ptr::null_mut()) }
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

// Also expose the same symbols under RustWhatsAppCrypto so a single .so serves both clients
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_sealedSenderEncrypt<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    plaintext: JByteArray<'local>,
    recipient_aci: jni::objects::JString<'local>,
    _recipient_device_id: jint,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        match catch_unwind(AssertUnwindSafe(|| sealed_sender_encrypt_inner(&mut *env, plaintext, recipient_aci))) {
            Ok(v) => Ok(v),
            Err(_) => { env.exception_clear(); let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), jni_str!("Native panic in sealedSenderEncrypt")); Ok(std::ptr::null_mut()) }
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_sealedSenderDecrypt<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    ciphertext: JByteArray<'local>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        match catch_unwind(AssertUnwindSafe(|| sealed_sender_decrypt_inner(&mut *env, ciphertext))) {
            Ok(v) => Ok(v),
            Err(_) => { env.exception_clear(); let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), jni_str!("Native panic in sealedSenderDecrypt")); Ok(std::ptr::null_mut()) }
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

// Signal PQXDH Kyber bridge — real PQXDH goes via libsignal Java SessionBuilder; Rust stub keeps build green
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_vayunmathur_communicate_data_signal_e2e_RustSignalCrypto_processPreKeyBundle<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    local_private: JByteArray<'local>,
    local_public: JByteArray<'local>,
    local_reg_id: jint,
    reg_id: jint,
    pre_key_id: jint,
    pre_key_public: JByteArray<'local>,
    signed_pre_key_id: jint,
    signed_pre_key_public: JByteArray<'local>,
    signed_pre_key_sig: JByteArray<'local>,
    identity_key: JByteArray<'local>,
    _kyber_pre_key_id: jint,
    _kyber_pre_key_public: JByteArray<'local>,
    _kyber_pre_key_signature: JByteArray<'local>,
    _kyber_ciphertext: JByteArray<'local>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        match catch_unwind(AssertUnwindSafe(|| {
            process_prekey_bundle_inner(&mut *env, local_private, local_public, local_reg_id, reg_id, pre_key_id, pre_key_public, signed_pre_key_id, signed_pre_key_public, signed_pre_key_sig, identity_key)
        })) {
            Ok(v) => Ok(v),
            Err(_) => { env.exception_clear(); let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), jni_str!("Native panic in RustSignalCrypto.processPreKeyBundle")); Ok(std::ptr::null_mut()) }
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_signal_e2e_RustSignalCrypto_markKyberPreKeyUsed<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    kyber_id: jint,
    signed_ec_id: jint,
    base_key: JByteArray<'local>,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
        match catch_unwind(AssertUnwindSafe(|| {
            let b = match bytes_in(&mut *env, &base_key) { Some(x) => x, None => { let _ = env.throw_new(jni_str!("java/lang/RuntimeException"),jni_str!("baseKey null")); return false; } };
            crate::signal::mark_kyber_pre_key_used(kyber_id, signed_ec_id, &b)
        })) {
            Ok(v) => Ok(v),
            Err(_) => { env.exception_clear(); Ok(false) }
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
