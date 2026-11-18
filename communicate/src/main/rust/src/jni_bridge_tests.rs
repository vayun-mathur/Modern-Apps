//! Host JVM tests for the JNI bridge inners.
//!
//! The `*_inner` functions are the whole marshalling layer: byte-array
//! conversion, length validation, exception mapping and delegation to the
//! pure session/group/signal logic. They need a real `Env`, so these tests
//! boot one host JVM per test binary and drive full protocol flows through
//! the same entry points Kotlin calls.

use super::*;
use jni::objects::JObjectArray;
use jni::JavaVM;
use std::sync::OnceLock;

static JVM: OnceLock<JavaVM> = OnceLock::new();

fn jvm() -> &'static JavaVM {
    JVM.get_or_init(|| {
        let args = jni::InitArgsBuilder::new().build().expect("JNI init args");
        JavaVM::new(args).expect("host JVM must start for JNI bridge tests")
    })
}

fn with_env<R>(f: impl FnOnce(&mut Env) -> R) -> R {
    let mut out: Option<R> = None;
    jvm()
        .attach_current_thread(|env| {
            out = Some(f(env));
            Ok::<(), jni::errors::Error>(())
        })
        .expect("attach_current_thread");
    out.expect("closure ran")
}

fn ba<'a>(env: &mut Env<'a>, bytes: &[u8]) -> JByteArray<'a> {
    env.byte_array_from_slice(bytes).expect("byte array")
}

fn read(env: &mut Env, raw: jbyteArray) -> Vec<u8> {
    assert!(!raw.is_null(), "expected non-null byte array");
    let arr = unsafe { JByteArray::from_raw(env, raw) };
    env.convert_byte_array(&arr).expect("convert back")
}

fn read_elems(env: &mut Env, raw: jobjectArray, want: usize) -> Vec<Vec<u8>> {
    assert!(!raw.is_null(), "expected non-null object array");
    let arr = unsafe { JObjectArray::<JByteArray>::from_raw(env, raw) };
    assert_eq!(arr.len(env).expect("len"), want);
    (0..want)
        .map(|i| {
            let e = arr.get_element(env, i).expect("element");
            env.convert_byte_array(&e).expect("element bytes")
        })
        .collect()
}

/// Error paths throw `RuntimeException`; assert one is pending, then clear
/// it so the thread detaches cleanly.
fn assert_threw(env: &Env) {
    assert!(env.exception_check(), "expected a thrown RuntimeException");
    env.exception_clear();
}

fn kp() -> ([u8; 32], [u8; 32]) {
    crypto::generate_key_pair(&mut OsRng::default())
}

#[test]
fn parse_helpers_validate_lengths() {
    assert_eq!(parse_32("k", &[7u8; 32]).expect("ok"), [7u8; 32]);
    assert!(parse_32("k", &[7u8; 31]).is_err());
    assert!(parse_32("k", &[]).is_err());
    assert_eq!(parse_64("s", &[9u8; 64]).expect("ok"), [9u8; 64]);
    assert!(parse_64("s", &[9u8; 63]).is_err());
    assert!(parse_64("s", &[9u8; 65]).is_err());
}

#[test]
fn keypair_agreement_sign_verify_via_bridge() {
    with_env(|env| {
        let raw = generate_keypair_inner(&mut *env);
        let pair = read(&mut *env, raw);
        assert_eq!(pair.len(), 64, "keypair is private||public");
        let (priv_a, pub_a) = (pair[..32].to_vec(), pair[32..].to_vec());

        let a = ba(&mut *env, &priv_a);
        let raw = public_from_private_inner(&mut *env, a);
        assert_eq!(read(&mut *env, raw), pub_a);

        let (priv_b, pub_b) = kp();
        let (a1, b1) = (ba(&mut *env, &priv_a), ba(&mut *env, &pub_b));
        let raw = x25519_agreement_inner(&mut *env, a1, b1);
        let s1 = read(&mut *env, raw);
        let (a2, b2) = (ba(&mut *env, &priv_b), ba(&mut *env, &pub_a));
        let raw = x25519_agreement_inner(&mut *env, a2, b2);
        assert_eq!(s1, read(&mut *env, raw));
        assert_ne!(s1, vec![0u8; 32]);

        let (a3, m1) = (ba(&mut *env, &priv_a), ba(&mut *env, b"hello"));
        let raw = sign_inner(&mut *env, a3, m1);
        let sig = read(&mut *env, raw);
        assert_eq!(sig.len(), 64);
        let (p1, m2, s1a) = (ba(&mut *env, &pub_a), ba(&mut *env, b"hello"), ba(&mut *env, &sig));
        assert!(verify_inner(&mut *env, p1, m2, s1a));
        let (p2, m3, s2) = (ba(&mut *env, &pub_a), ba(&mut *env, b"HELLO"), ba(&mut *env, &sig));
        assert!(!verify_inner(&mut *env, p2, m3, s2));
        let (p3, m4, s3) = (ba(&mut *env, &pub_a), ba(&mut *env, b"hello"), ba(&mut *env, &sig[..63]));
        assert!(!verify_inner(&mut *env, p3, m4, s3));
        let (p4, m5, s4) = (ba(&mut *env, &pub_a[..31]), ba(&mut *env, b"hello"), ba(&mut *env, &sig));
        assert!(!verify_inner(&mut *env, p4, m5, s4));
    });
}

#[test]
fn bridge_rejects_null_and_short_inputs() {
    with_env(|env| {
        assert!(public_from_private_inner(&mut *env, JByteArray::null()).is_null());
        assert_threw(&*env);
        let short = ba(&mut *env, &[1u8; 31]);
        assert!(public_from_private_inner(&mut *env, short).is_null());
        assert_threw(&*env);

        let (priv_a, pub_a) = kp();
        let b1 = ba(&mut *env, &pub_a);
        assert!(x25519_agreement_inner(&mut *env, JByteArray::null(), b1).is_null());
        assert_threw(&*env);
        let (a2, b2) = (ba(&mut *env, &priv_a), ba(&mut *env, &[2u8; 3]));
        assert!(x25519_agreement_inner(&mut *env, a2, b2).is_null());
        assert_threw(&*env);
        let m1 = ba(&mut *env, b"m");
        assert!(sign_inner(&mut *env, JByteArray::null(), m1).is_null());
        assert_threw(&*env);
        let (a3, m2) = (ba(&mut *env, &[1u8; 8]), ba(&mut *env, b"m"));
        assert!(sign_inner(&mut *env, a3, m2).is_null());
        assert_threw(&*env);

        // verify never throws on bad input; it just returns false.
        let (m3, s1) = (ba(&mut *env, b"m"), ba(&mut *env, &[0u8; 64]));
        assert!(!verify_inner(&mut *env, JByteArray::null(), m3, s1));
        assert!(!env.exception_check());
    });
}

/// Full X3DH + Double Ratchet conversation driven only through `*_inner`.
#[test]
fn session_flow_via_bridge() {
    with_env(|env| {
        let (alice_priv, alice_pub) = kp();
        let (bob_priv, bob_pub) = kp();
        let (spk_priv, spk_pub) = kp();
        let (opk_priv, opk_pub) = kp();
        // The signed pre-key signature is made with Bob's identity key.
        let sig = crypto::sign(&bob_priv, &crypto::serialize_public(&spk_pub), &[0x44u8; 64]);

        let (a1, a2, a3, a4, a5, a6) = (
            ba(&mut *env, &alice_priv),
            ba(&mut *env, &alice_pub),
            ba(&mut *env, &opk_pub),
            ba(&mut *env, &spk_pub),
            ba(&mut *env, &sig),
            ba(&mut *env, &bob_pub),
        );
        let raw = process_prekey_bundle_inner(&mut *env, a1, a2, 9, 7, 11, a3, 3, a4, a5, a6);
        let alice_sess = read(&mut *env, raw);
        assert!(!alice_sess.is_empty());

        let (s1, p1) = (ba(&mut *env, &alice_sess), ba(&mut *env, b"hello bob"));
        let raw = encrypt_inner(&mut *env, s1, p1);
        let parts = read_elems(&mut *env, raw, 3);
        assert_eq!(parts[0], vec![1u8], "first message must be pre-key");
        let (pk_body, alice_sess) = (parts[1].clone(), parts[2].clone());

        let (b1, b2, b3, b4, b5) = (
            ba(&mut *env, &bob_priv),
            ba(&mut *env, &bob_pub),
            ba(&mut *env, &spk_priv),
            ba(&mut *env, &opk_priv),
            ba(&mut *env, &pk_body),
        );
        let raw = decrypt_prekey_inner(&mut *env, b1, b2, b3, b4, b5);
        let parts = read_elems(&mut *env, raw, 2);
        assert_eq!(parts[0], b"hello bob");
        let bob_sess = parts[1].clone();

        let (s2, p2) = (ba(&mut *env, &bob_sess), ba(&mut *env, b"hello alice"));
        let raw = encrypt_inner(&mut *env, s2, p2);
        let parts = read_elems(&mut *env, raw, 3);
        assert_eq!(parts[0], vec![0u8]);
        let (s3, c1) = (ba(&mut *env, &alice_sess), ba(&mut *env, &parts[1]));
        let raw = decrypt_message_inner(&mut *env, s3, c1);
        let parts = read_elems(&mut *env, raw, 2);
        assert_eq!(parts[0], b"hello alice");
        assert!(!parts[1].is_empty(), "session advances");

        // Garbage inputs throw instead of returning data.
        let (g1, g2) = (ba(&mut *env, b"junk"), ba(&mut *env, b"x"));
        assert!(encrypt_inner(&mut *env, g1, g2).is_null());
        assert_threw(&*env);
        let (s4, g3) = (ba(&mut *env, &parts[1]), ba(&mut *env, b"junk"));
        assert!(decrypt_message_inner(&mut *env, s4, g3).is_null());
        assert_threw(&*env);
        let (b6, b7, b8, b9) = (
            ba(&mut *env, &bob_priv),
            ba(&mut *env, &bob_pub),
            ba(&mut *env, &spk_priv),
            ba(&mut *env, b"junk"),
        );
        assert!(decrypt_prekey_inner(&mut *env, b6, b7, b8, JByteArray::null(), b9).is_null());
        assert_threw(&*env);
    });
}

#[test]
fn bundle_without_one_time_prekey_via_bridge() {
    with_env(|env| {
        let (alice_priv, alice_pub) = kp();
        let (bob_priv, bob_pub) = kp();
        let (_spk_priv, spk_pub) = kp();
        let sig = crypto::sign(&bob_priv, &crypto::serialize_public(&spk_pub), &[0x55u8; 64]);

        let bundle = |env: &mut Env, sig: &[u8], local_priv_null: bool| {
            let a1 = if local_priv_null { JByteArray::null() } else { ba(env, &alice_priv) };
            let (a2, a4, a5, a6) = (ba(env, &alice_pub), ba(env, &spk_pub), ba(env, sig), ba(env, &bob_pub));
            process_prekey_bundle_inner(env, a1, a2, 9, 7, -1, JByteArray::null(), 3, a4, a5, a6)
        };
        let raw = bundle(&mut *env, &sig, false);
        assert!(!raw.is_null(), "3-DH bundle (no one-time key) establishes");
        // A bad identity signature is rejected with an exception.
        let raw = bundle(&mut *env, &[0u8; 64], false);
        assert!(raw.is_null());
        assert_threw(&*env);
        // Null mandatory field throws too.
        let raw = bundle(&mut *env, &sig, true);
        assert!(raw.is_null());
        assert_threw(&*env);
    });
}

#[test]
fn group_flow_via_bridge() {
    with_env(|env| {
        let raw = create_sender_key_inner(&mut *env);
        let parts = read_elems(&mut *env, raw, 2);
        let (state_a, skdm) = (parts[0].clone(), parts[1].clone());
        assert!(!state_a.is_empty() && !skdm.is_empty());

        let d1 = ba(&mut *env, &skdm);
        let raw = process_sender_key_inner(&mut *env, d1);
        let state_b = read(&mut *env, raw);

        let (s1, p1) = (ba(&mut *env, &state_a), ba(&mut *env, b"group hi"));
        let raw = encrypt_group_inner(&mut *env, s1, p1);
        let parts = read_elems(&mut *env, raw, 2);
        let (s2, c1) = (ba(&mut *env, &state_b), ba(&mut *env, &parts[0]));
        let raw = decrypt_group_inner(&mut *env, s2, c1);
        let parts = read_elems(&mut *env, raw, 2);
        assert_eq!(parts[0], b"group hi");

        // Malformed inputs throw.
        let g1 = ba(&mut *env, b"junk");
        assert!(process_sender_key_inner(&mut *env, g1).is_null());
        assert_threw(&*env);
        assert!(process_sender_key_inner(&mut *env, JByteArray::null()).is_null());
        assert_threw(&*env);
        let (g2, g3) = (ba(&mut *env, b"junk"), ba(&mut *env, b"x"));
        assert!(encrypt_group_inner(&mut *env, g2, g3).is_null());
        assert_threw(&*env);
        let (s3, g4) = (ba(&mut *env, &state_b), ba(&mut *env, b"junk"));
        assert!(decrypt_group_inner(&mut *env, s3, g4).is_null());
        assert_threw(&*env);
    });
}

/// The `Java_*` wrappers themselves: same flows, through the exported
/// symbols Kotlin actually binds (including both sealed-sender class names
/// and the Signal PQXDH bundle entry, which delegates to the same inner).
#[test]
fn extern_wrappers_via_jni() {
    use jni::objects::JClass;
    type Un<'a> = EnvUnowned<'a>;
    fn un<'a>(env: &Env<'a>) -> Un<'a> {
        unsafe { EnvUnowned::from_raw(env.get_raw()) }
    }
    with_env(|env| {
        let cls = JClass::null();
        let raw = Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_generateKeyPair(un(&*env), cls);
        let pair = read(&mut *env, raw);
        let (alice_priv, alice_pub) = (pair[..32].to_vec(), pair[32..].to_vec());
        let (bob_priv, bob_pub) = kp();
        let (spk_priv, spk_pub) = kp();
        let (opk_priv, opk_pub) = kp();
        let sig = crypto::sign(&bob_priv, &crypto::serialize_public(&spk_pub), &[0x66u8; 64]);

        let a = ba(&mut *env, &alice_priv);
        let raw = Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_publicFromPrivate(un(&*env), JClass::null(), a);
        assert_eq!(read(&mut *env, raw), alice_pub);
        let (a1, b1) = (ba(&mut *env, &alice_priv), ba(&mut *env, &bob_pub));
        let raw = Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_x25519Agreement(un(&*env), JClass::null(), a1, b1);
        assert_eq!(read(&mut *env, raw).len(), 32);
        let (s1, m1) = (ba(&mut *env, &alice_priv), ba(&mut *env, b"hi"));
        let raw = Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_sign(un(&*env), JClass::null(), s1, m1);
        let sig_bytes = read(&mut *env, raw);
        let (p1, m2, sg) = (ba(&mut *env, &alice_pub), ba(&mut *env, b"hi"), ba(&mut *env, &sig_bytes));
        assert!(Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_verify(un(&*env), JClass::null(), p1, m2, sg));
        let (p2, m3, sg2) = (ba(&mut *env, &alice_pub), ba(&mut *env, b"bye"), ba(&mut *env, &sig_bytes));
        assert!(!Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_verify(un(&*env), JClass::null(), p2, m3, sg2));

        // Session establishment + conversation via the WhatsApp symbols.
        let (w1, w2, w3, w4, w5, w6) = (
            ba(&mut *env, &alice_priv), ba(&mut *env, &alice_pub), ba(&mut *env, &opk_pub),
            ba(&mut *env, &spk_pub), ba(&mut *env, &sig), ba(&mut *env, &bob_pub),
        );
        let raw = Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_processPreKeyBundle(
            un(&*env), JClass::null(), w1, w2, 9, 7, 11, w3, 3, w4, w5, w6,
        );
        let alice_sess = read(&mut *env, raw);
        let (e1, e2) = (ba(&mut *env, &alice_sess), ba(&mut *env, b"via wrappers"));
        let raw = Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_encrypt(un(&*env), JClass::null(), e1, e2);
        let parts = read_elems(&mut *env, raw, 3);
        let alice_sess2 = parts[2].clone();
        let (d1, d2, d3, d4, d5) = (
            ba(&mut *env, &bob_priv), ba(&mut *env, &bob_pub), ba(&mut *env, &spk_priv),
            ba(&mut *env, &opk_priv), ba(&mut *env, &parts[1]),
        );
        let raw = Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_decryptPreKeyMessage(un(&*env), JClass::null(), d1, d2, d3, d4, d5);
        let bob_parts = read_elems(&mut *env, raw, 2);
        assert_eq!(bob_parts[0], b"via wrappers");
        let (e3, e4) = (ba(&mut *env, &bob_parts[1]), ba(&mut *env, b"reply"));
        let raw = Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_encrypt(un(&*env), JClass::null(), e3, e4);
        let parts = read_elems(&mut *env, raw, 3);
        let (d6, d7) = (ba(&mut *env, &alice_sess2), ba(&mut *env, &parts[1]));
        let raw = Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_decryptMessage(un(&*env), JClass::null(), d6, d7);
        assert_eq!(read_elems(&mut *env, raw, 2)[0], b"reply");

        // Group symbols.
        let raw = Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_createSenderKey(un(&*env), JClass::null());
        let gp = read_elems(&mut *env, raw, 2);
        let k1 = ba(&mut *env, &gp[1]);
        let raw = Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_processSenderKey(un(&*env), JClass::null(), k1);
        let state_b = read(&mut *env, raw);
        let (g1, g2) = (ba(&mut *env, &gp[0]), ba(&mut *env, b"grp"));
        let raw = Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_encryptGroup(un(&*env), JClass::null(), g1, g2);
        let ct = read_elems(&mut *env, raw, 2);
        let (g3, g4) = (ba(&mut *env, &state_b), ba(&mut *env, &ct[0]));
        let raw = Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_decryptGroup(un(&*env), JClass::null(), g3, g4);
        assert_eq!(read_elems(&mut *env, raw, 2)[0], b"grp");

        // Sealed sender under both class names (empty ACI round-trips).
        for wa in [true, false] {
            let aci = env.new_string("").expect("string");
            let p = ba(&mut *env, b"sealed2");
            let raw = if wa {
                Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_sealedSenderEncrypt(un(&*env), JClass::null(), p, aci, 1)
            } else {
                Java_com_vayunmathur_communicate_data_signal_e2e_RustSignalCrypto_sealedSenderEncrypt(un(&*env), JClass::null(), p, aci, 1)
            };
            let sealed = read(&mut *env, raw);
            let c = ba(&mut *env, &sealed);
            let raw = if wa {
                Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_sealedSenderDecrypt(un(&*env), JClass::null(), c)
            } else {
                Java_com_vayunmathur_communicate_data_signal_e2e_RustSignalCrypto_sealedSenderDecrypt(un(&*env), JClass::null(), c)
            };
            assert_eq!(read(&mut *env, raw), b"sealed2");
        }

        // Signal bundle entry (Kyber args ignored by the stub) + replay guard.
        let (q1, q2, q3, q4, q5, q6) = (
            ba(&mut *env, &alice_priv), ba(&mut *env, &alice_pub), ba(&mut *env, &opk_pub),
            ba(&mut *env, &spk_pub), ba(&mut *env, &sig), ba(&mut *env, &bob_pub),
        );
        let (q7, q8, q9) = (ba(&mut *env, &[]), ba(&mut *env, &[]), ba(&mut *env, &[]));
        let raw = Java_com_vayunmathur_communicate_data_signal_e2e_RustSignalCrypto_processPreKeyBundle(
            un(&*env), JClass::null(), q1, q2, 9, 7, 11, q3, 3, q4, q5, q6, 5, q7, q8, q9,
        );
        assert!(!raw.is_null());
        let bk = ba(&mut *env, b"base-key-wrapper-test");
        assert!(Java_com_vayunmathur_communicate_data_signal_e2e_RustSignalCrypto_markKyberPreKeyUsed(un(&*env), JClass::null(), 4242, 777, bk));
        let bk2 = ba(&mut *env, b"base-key-wrapper-test");
        assert!(!Java_com_vayunmathur_communicate_data_signal_e2e_RustSignalCrypto_markKyberPreKeyUsed(un(&*env), JClass::null(), 4242, 777, bk2));
        env.exception_clear();
    });
}

#[test]
fn sealed_sender_via_bridge() {
    with_env(|env| {
        // The stub binds the key to the ACI; `decrypt_any` is the empty-ACI
        // path, so the JNI round trip uses an empty ACI (see signal.rs).
        let aci = env.new_string("").expect("string");
        let p1 = ba(&mut *env, b"sealed hello");
        let raw = sealed_sender_encrypt_inner(&mut *env, p1, aci);
        let ct = read(&mut *env, raw);
        assert_ne!(ct, b"sealed hello");
        let c1 = ba(&mut *env, &ct);
        let raw = sealed_sender_decrypt_inner(&mut *env, c1);
        assert_eq!(read(&mut *env, raw), b"sealed hello");

        // An ACI-bound ciphertext must not open under the empty key.
        let aci2 = env.new_string("alice").expect("string");
        let p2 = ba(&mut *env, b"for alice only");
        let raw = sealed_sender_encrypt_inner(&mut *env, p2, aci2);
        let ct2 = read(&mut *env, raw);
        let c2 = ba(&mut *env, &ct2);
        assert!(sealed_sender_decrypt_inner(&mut *env, c2).is_null());
        assert_threw(&*env);

        let aci2 = env.new_string("x").expect("string");
        assert!(sealed_sender_encrypt_inner(&mut *env, JByteArray::null(), aci2).is_null());
        assert_threw(&*env);
        let g1 = ba(&mut *env, b"junk");
        assert!(sealed_sender_decrypt_inner(&mut *env, g1).is_null());
        assert_threw(&*env);
    });
}
