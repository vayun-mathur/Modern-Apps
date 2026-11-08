use crate::*;

/// Per-object encrypt/decrypt transform.
type CryptFn = Box<dyn Fn(&[u8]) -> Vec<u8>>;
/// Builds the [`CryptFn`] for a given object id.
type CryptFnFactory = Box<dyn Fn(ObjectId) -> CryptFn>;

/// Whether `bytes` is a standard-encrypted PDF that needs a (non-empty) password
/// the empty password does not satisfy. Returns: 0 no, 1 needs password, 2
/// unsupported encryption (e.g. AES).
pub(crate) fn pdf_password_state(bytes: &[u8]) -> i32 {
    // The same size guard `open_document_pw` applies, for the same bytes: this probe
    // runs FIRST in the UI flow, so without it a 1 GB file is fully parsed here and
    // only then refused at open. Reporting "no password needed" matches what open
    // will do with it.
    if bytes.len() > crate::registry::MAX_PDF_BYTES {
        return 0;
    }
    // Must match open_document_pw's loader: using the strict Document::load_mem here made a
    // damaged encrypted file report "no password needed" and then fail to open with no prompt.
    let mut doc = match crate::registry::load_document_lenient(bytes) {
        Some(d) => d,
        None => return 0,
    };
    if doc.trailer.get(b"Encrypt").is_err() {
        return 0;
    }
    // Probe with no password (empty, built at runtime — not a hard-coded credential).
    let no_password: Vec<u8> = Vec::new();
    match decrypt_in_place(&mut doc, &no_password) {
        DecryptStatus::Ok => 0,
        DecryptStatus::NeedPassword => 1,
        DecryptStatus::Unsupported => 2,
    }
}

#[derive(PartialEq, Debug)]
pub(crate) enum DecryptStatus {
    Ok,
    NeedPassword,
    Unsupported,
}

/// Apply a cipher (`apply`) to every string and stream inside `obj`.
pub(crate) fn crypt_object(obj: &mut Object, apply: &dyn Fn(&[u8]) -> Vec<u8>) {
    crypt_object_split(obj, Some(apply), Some(apply));
}

/// Apply `strings` to every string and `streams` to every stream body inside `obj`.
///
/// §7.6.5 gives streams and strings SEPARATE crypt filters (`/StmF`, `/StrF`), and
/// either may be `/Identity`, so the two cannot share one transform. `None` leaves
/// that class of data untouched.
fn crypt_object_split(
    obj: &mut Object,
    strings: Option<&dyn Fn(&[u8]) -> Vec<u8>>,
    streams: Option<&dyn Fn(&[u8]) -> Vec<u8>>,
) {
    match obj {
        Object::String(s, fmt) => {
            if let Some(apply) = strings {
                *s = apply(s);
                // Transformed (encrypted or decrypted) bytes are arbitrary
                // binary: as a literal string they can contain unbalanced
                // parens/backslashes that corrupt the file for every parser
                // (a saved-then-reloaded encrypted doc lost all objects but
                // the /Encrypt dict). Hex strings carry any bytes safely.
                *fmt = lopdf::StringFormat::Hexadecimal;
            }
        }
        Object::Array(a) => {
            for o in a.iter_mut() {
                crypt_object_split(o, strings, streams);
            }
        }
        Object::Dictionary(d) => {
            let keys: Vec<Vec<u8>> = d.iter().map(|(k, _)| k.clone()).collect();
            for k in keys {
                if let Ok(v) = d.get_mut(&k) {
                    crypt_object_split(v, strings, streams);
                }
            }
        }
        Object::Stream(st) => {
            let keys: Vec<Vec<u8>> = st.dict.iter().map(|(k, _)| k.clone()).collect();
            for k in keys {
                if let Ok(v) = st.dict.get_mut(&k) {
                    crypt_object_split(v, strings, streams);
                }
            }
            if let Some(apply) = streams {
                st.content = apply(&st.content);
            }
        }
        _ => {}
    }
}

/// First `/ID` element bytes from the trailer, or empty if absent.
/// For decryption, an empty ID is tolerated (some generators omit it). The
/// alternate hash fallback was breaking RC4/AES-128 round-trips because
/// `encrypt_doc_bytes` must persist a concrete ID; `trailer_id0` must NOT
/// synthesize a different value on each load. Missing ID returns empty.
pub(crate) fn trailer_id0(doc: &Document) -> Vec<u8> {
    if let Ok(Object::Array(a)) = doc.trailer.get(b"ID") {
        if let Some(Object::String(s, _)) = a.first() {
            return s.clone();
        }
    }
    Vec::new()
}

/// Ensure the document trailer has an `/ID` array (two entries), generating
/// a random one if missing. Returns the first ID bytes for key derivation.
pub(crate) fn ensure_trailer_id(doc: &mut Document, seed: &[u8]) -> Vec<u8> {
    if let Ok(Object::Array(a)) = doc.trailer.get(b"ID") {
        if let Some(Object::String(s, _)) = a.first() {
            return s.clone();
        }
    }
    let h = rand_bytes::<16>(seed).to_vec();
    doc.trailer.set(
        "ID",
        Object::Array(vec![
            Object::String(h.clone(), lopdf::StringFormat::Hexadecimal),
            Object::String(h.clone(), lopdf::StringFormat::Hexadecimal),
        ]),
    );
    h
}

#[derive(Clone, Copy, PartialEq)]
pub(crate) enum CryptMethod {
    Rc4,
    AesV2,
    AesV3,
}

/// §7.6.5: resolve a `/StmF` or `/StrF` crypt-filter NAME to the method it selects.
///
/// `None` means the data shall NOT be decrypted. `/Identity` is the reserved filter
/// name for exactly that (Table 20, and it is the DEFAULT for both keys), and
/// `/CFM /None` says the same at the filter itself (Table 25). Running the file's
/// cipher over data a crypt filter declared unencrypted turns plaintext into noise
/// for the whole document, which is why the two cases are worth separating.
///
/// A name that resolves to no readable `/CFM` falls back to `default`, so a file that
/// never mentions `/Identity` takes exactly the path it took before.
fn crypt_filter_method(
    cf: Option<&Dictionary>,
    name: &[u8],
    default: CryptMethod,
) -> Option<CryptMethod> {
    if name == b"Identity" {
        return None;
    }
    let cfm = cf
        .and_then(|d| d.get(name).ok())
        .and_then(|o| o.as_dict().ok())
        .and_then(|d| d.get(b"CFM").ok())
        .and_then(|o| o.as_name().ok());
    match cfm {
        Some(m) if m == b"None" => None,
        Some(m) if m == b"AESV3" => Some(CryptMethod::AesV3),
        Some(m) if m == b"AESV2" => Some(CryptMethod::AesV2),
        Some(m) if m == b"V2" => Some(CryptMethod::Rc4),
        _ => Some(default),
    }
}

/// The `/P` permission flags as the 32-BIT quantity §7.6.3.3 Algorithm 2 step (d)
/// feeds to the key hash.
///
/// Producers write the same bit pattern either signed (`-3904`) or unsigned
/// (`4294963392`); both are the same four bytes and Acrobat reads them identically.
/// Going through `f64 as i32` SATURATES the unsigned form to `2147483647`, which
/// hashes four wrong bytes and rejects a correct password on a file that opens
/// everywhere else. Truncating to 32 bits reads both forms the same.
fn permissions_p(enc: &Dictionary) -> i32 {
    enc.get(b"P")
        .ok()
        .and_then(|o| o.as_i64().ok().or_else(|| num(o).map(|v| v as i64)))
        .unwrap_or(0) as u32 as i32
}

/// The per-object transform for one object under `method`.
fn object_cipher(method: CryptMethod, key: &[u8], id: ObjectId, n: usize) -> CryptFn {
    match method {
        CryptMethod::Rc4 => {
            let okey = crypto::object_key(key, id.0, id.1, n);
            Box::new(move |d: &[u8]| crypto::rc4(&okey, d))
        }
        CryptMethod::AesV2 => {
            let okey = crypto::object_key_aes(key, id.0, id.1, n);
            Box::new(move |d: &[u8]| crypto::aes_cbc_decrypt(&okey, d).unwrap_or_default())
        }
        // §7.6.5.3: AESV3 uses the file encryption key directly, with no per-object key.
        CryptMethod::AesV3 => {
            let k = key.to_vec();
            Box::new(move |d: &[u8]| crypto::aes_cbc_decrypt(&k, d).unwrap_or_default())
        }
    }
}

/// Decrypt a standard-encrypted document (RC4 or AES) in place with `password`.
///
/// Tries lopdf's own standard-security-handler first. That matters for correctness, not
/// just economy: lopdf honours `/StrF` and the `/Identity` crypt filter, applies
/// Algorithm 2 step (f) for `/EncryptMetadata false`, and skips `/Type /XRef` streams.
/// Our implementation is retained for the files lopdf cannot authenticate.
pub(crate) fn decrypt_in_place(doc: &mut Document, password: &[u8]) -> DecryptStatus {
    // Authenticate WITHOUT mutating first. lopdf's decrypt_raw applies `?` to each object
    // inside its mutation loop (document.rs:486-493), so a mid-loop failure leaves the
    // document HALF-decrypted. Running our fallback over that would decrypt the already
    // plaintext prefix a second time — and RC4 is symmetric, so it would re-encrypt it
    // into noise. Deciding up front which implementation owns the document avoids that.
    if doc.authenticate_raw_password(password).is_ok() {
        return match doc.decrypt_raw(password) {
            // decrypt_raw also re-expands object streams and clears /Encrypt.
            Ok(()) => DecryptStatus::Ok,
            Err(_) => {
                // Partially decrypted. Keep what succeeded rather than corrupting it, on
                // the same "partial data beats no data" principle used for Flate/LZW.
                //
                // decrypt_raw bails out of its per-object loop (document.rs:492) BEFORE
                // it reaches its own object-stream expansion (document.rs:496-517), so
                // without this the ObjStm-contained objects — /Root, /Pages and the page
                // dictionaries for essentially every modern encrypted PDF — stay missing
                // and the document opens with zero pages. Only ever adds objects.
                expand_object_streams(doc);
                doc.trailer.remove(b"Encrypt");
                DecryptStatus::Ok
            }
        };
    }
    // lopdf could not authenticate, so it has not touched the document: our own handler
    // gets a pristine copy.
    decrypt_in_place_fallback(doc, password)
}

/// Our own standard-security-handler implementation, used when lopdf declines the file.
fn decrypt_in_place_fallback(doc: &mut Document, password: &[u8]) -> DecryptStatus {
    let enc_id = match doc.trailer.get(b"Encrypt").and_then(|o| o.as_reference()) {
        Ok(id) => id,
        Err(_) => return DecryptStatus::Unsupported,
    };
    let (o, u, ue, oe, p, r, length, method, encrypt_metadata, stm_method, str_method) = {
        let enc = match doc.get_dictionary(enc_id) {
            Ok(d) => d,
            Err(_) => return DecryptStatus::Unsupported,
        };
        let filter = enc.get(b"Filter").ok().and_then(|o| o.as_name().ok());
        if filter != Some(b"Standard".as_ref()) {
            // Only the Standard security handler is supported. Public-key /
            // certificate handlers (e.g. /Filter /Adobe.PubSec) are infeasible
            // here: decryption requires the recipient's private key, which the
            // viewer does not possess. Report as Unsupported rather than failing
            // silently or corrupting bytes.
            return DecryptStatus::Unsupported;
        }
        let v = enc.get(b"V").ok().and_then(num).unwrap_or(0.0) as i64;
        let r = enc.get(b"R").ok().and_then(num).unwrap_or(0.0) as i64;
        // P0 fix: properly resolve /CF dict + /StmF /StrF names vs. only StdCF
        // Per PDF 1.7 §7.6.2, /CF may have multiple named crypt filters.
        // /StmF and /StrF name which CF to use for streams and strings.
        // Previously only StdCF checked, breaking crypt-filters docs.
        let cf_dict_opt = enc.get(b"CF").ok().and_then(|o| o.as_dict().ok()).cloned();
        // For V=4, if CF absent spec says fall back to V2 Rc4 — fix P0 issue #22
        let method = if v >= 5 {
            CryptMethod::AesV3
        } else if v == 4 {
            if let Some(cf_dict) = &cf_dict_opt {
                // Try StdCF first, then StmF-named filter, then any CF entry
                let stm_f_name = enc.get(b"StmF").ok().and_then(|o| o.as_name().ok()).unwrap_or(b"StdCF");
                let cfm = cf_dict
                    .get(stm_f_name)
                    .or_else(|_| cf_dict.get(b"StdCF"))
                    .ok()
                    .and_then(|s| s.as_dict().ok())
                    .and_then(|s| s.get(b"CFM").ok())
                    .and_then(|o| o.as_name().ok());
                match cfm {
                    Some(b) if b == b"AESV3" => CryptMethod::AesV3,
                    Some(b) if b == b"AESV2" => CryptMethod::AesV2,
                    Some(b) if b == b"V2" => CryptMethod::Rc4,
                    // If no CFM found but CF present, could be custom — treat as Unsupported
                    // unless CF missing entirely then fall back to Rc4 below
                    Some(_) => return DecryptStatus::Unsupported,
                    None => {
                        // CF exists but no CFM? Check if CF dict non-empty maybe encryption present but missing method
                        // Per plan, if CF dict absent entirely we fall through to Rc4 fallback
                        // If CF present but unreadable, Unsupported
                        if cf_dict.is_empty() {
                            CryptMethod::Rc4
                        } else {
                            // Try any CF entry's CFM
                            let mut found = None;
                            for (_, cf_entry) in cf_dict.iter() {
                                if let Ok(d) = cf_entry.as_dict() {
                                    if let Ok(cfm_obj) = d.get(b"CFM") {
                                        if let Ok(cfm_name) = cfm_obj.as_name() {
                                            if cfm_name == b"AESV3" { found = Some(CryptMethod::AesV3); break; }
                                            if cfm_name == b"AESV2" { found = Some(CryptMethod::AesV2); break; }
                                            if cfm_name == b"V2" { found = Some(CryptMethod::Rc4); break; }
                                        }
                                    }
                                }
                            }
                            found.unwrap_or(CryptMethod::Rc4)
                        }
                    }
                }
            } else {
                // V=4 with no CF dict — spec says default to V2 Rc4 fallback
                CryptMethod::Rc4
            }
        } else {
            CryptMethod::Rc4
        };
        let o = enc.get(b"O").ok().and_then(|o| o.as_str().ok()).map(|s| s.to_vec()).unwrap_or_default();
        let u = enc.get(b"U").ok().and_then(|o| o.as_str().ok()).map(|s| s.to_vec()).unwrap_or_default();
        let ue = enc.get(b"UE").ok().and_then(|o| o.as_str().ok()).map(|s| s.to_vec()).unwrap_or_default();
        let oe = enc.get(b"OE").ok().and_then(|o| o.as_str().ok()).map(|s| s.to_vec()).unwrap_or_default();
        // Preserve CF dict for later StmF/StrF handling? We already parsed method but for auth need OE for owner
        // §7.6.3.3 Algorithm 2 step (d) hashes /P as a 32-BIT quantity.
        let p = permissions_p(enc);
        let default_len = if method == CryptMethod::AesV2 { 128.0 } else { 40.0 };
        let length = enc.get(b"Length").ok().and_then(num).unwrap_or(default_len) as usize;
        // §7.6.3.3 Algorithm 2 step (f): with /EncryptMetadata false and R >= 4 the key
        // derivation takes four extra 0xFF bytes. Default true.
        let encrypt_metadata = !matches!(enc.get(b"EncryptMetadata"), Ok(Object::Boolean(false)));
        // §7.6.5 Table 20: /StmF and /StrF name the crypt filters for STREAMS and
        // STRINGS separately, and either may be /Identity. `method` above still drives
        // key derivation and authentication - only what gets transformed changes.
        let (stm_method, str_method) = if v >= 4 {
            let cf = cf_dict_opt.as_ref();
            let stm_name = enc
                .get(b"StmF")
                .ok()
                .and_then(|o| o.as_name().ok())
                .unwrap_or(b"StdCF");
            let str_name = enc
                .get(b"StrF")
                .ok()
                .and_then(|o| o.as_name().ok())
                .unwrap_or(b"StdCF");
            (
                crypt_filter_method(cf, stm_name, method),
                crypt_filter_method(cf, str_name, method),
            )
        } else {
            (Some(method), Some(method))
        };
        (o, u, ue, oe, p, r, length, method, encrypt_metadata, stm_method, str_method)
    };

    let id0 = trailer_id0(doc);
    let n = if method == CryptMethod::AesV2 { 16 } else { (length / 8).clamp(5, 16) };

    // Derive the file key: try user pw first, then owner pw for both V<5 and V>=5 (P0 fix #14 + #21)
    let key = match method {
        CryptMethod::AesV3 => {
            // V5/R5/R6: try user auth then owner auth
            if let Some(k) = crypto::authenticate_v5_user(password, &u, &ue, r as u8) {
                k
            } else if let Some(k) = crypto::authenticate_v5_owner(password, &o, &oe, &u, r as u8) {
                k
            } else {
                return DecryptStatus::NeedPassword;
            }
        }
        _ => {
            // V<5: spec says owner pw derives via O then user check, but many viewers try both
            // Also support owner password path: authenticate returns key if U matches; try both user and owner variants?
            // Owner auth in RC4: you can recover user pw from O using owner pw. To avoid full impl, we reuse authenticate
            // which already checks U against key derived from pw (user). Owner-only docs use empty user pw that still validates,
            // but some require owner.
            // Attempt direct authenticate with given password (user path)
            if let Some(k) = crypto::authenticate(password, &o, &u, p, &id0, n, r as u8, encrypt_metadata) {
                k
            } else {
                // Owner path: if owner pw supplied, O entry contains user pw encrypted; try to brute cheap?
                // Implement algorithm 7 (recover user key from O using owner pw) per spec.
                // Simplified: compute key from owner pw directly, then compute U and compare via O decryption.
                // For minimal fix, attempt authenticate_owner which we emulate below.
                let mut found: Option<Vec<u8>> = None;
                // Try derive candidate owner key and then decrypt O to get user pw, then authenticate that user pw
                // Algorithm 3 reverse: owner pw -> okey -> user_pad = rc4 decypt O etc.
                // We'll delegate to helper.
                if let Some(k) = crypto::authenticate_owner_fallback(password, &o, &u, p, &id0, n, r as u8, encrypt_metadata) {
                    found = Some(k);
                }
                if let Some(k) = found {
                    k
                } else {
                    return DecryptStatus::NeedPassword;
                }
            }
        }
    };

    let ids: Vec<ObjectId> = doc.objects.keys().copied().collect();
    for id in ids {
        if id == enc_id {
            continue;
        }
        // §7.5.8.2: a cross-reference stream shall not be encrypted, and neither shall
        // the strings in its dictionary.
        let is_xref_stream = doc
            .objects
            .get(&id)
            .and_then(|o| o.as_stream().ok())
            .map(|s| s.dict.has_type(b"XRef"))
            .unwrap_or(false);
        if is_xref_stream {
            continue;
        }
        let str_apply = str_method.map(|m| object_cipher(m, &key, id, n));
        let stm_apply = stm_method.map(|m| object_cipher(m, &key, id, n));
        if let Some(obj) = doc.objects.get_mut(&id) {
            crypt_object_split(
                obj,
                str_apply.as_ref().map(|f| f.as_ref() as &dyn Fn(&[u8]) -> Vec<u8>),
                stm_apply.as_ref().map(|f| f.as_ref() as &dyn Fn(&[u8]) -> Vec<u8>),
            );
        }
    }
    expand_object_streams(doc);
    doc.trailer.remove(b"Encrypt");
    DecryptStatus::Ok
}

include!("decrypt_part1.rs");