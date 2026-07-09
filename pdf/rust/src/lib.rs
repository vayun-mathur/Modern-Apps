//! Memory-safe PDF renderer for the "Open PDF (safe)" viewer.
//!
//! Parses a PDF entirely in Rust with `lopdf` (no pdfium / no native system
//! PDF stack) and reduces each page to plain drawing primitives — text runs,
//! filled polygons and stroked polylines — positioned in PDF page space. The
//! Kotlin side (`com.vayunmathur.pdf.util.SafePdfParser`) decodes the compact
//! little-endian buffer produced here and draws it on a Compose `Canvas`.
//!
//! Design goals: all untrusted binary parsing happens in memory-safe Rust; the
//! JNI boundary only ever passes a flat byte buffer of geometry + UTF-8 text,
//! so Kotlin never touches the raw PDF bytes.
//!
//! v1 scope: text (with `/ToUnicode` decoding) + vector paths (lines,
//! rectangles, filled/stroked paths, flattened beziers). No embedded raster
//! images (XObjects), no encryption, no shadings/patterns. See the plan and the
//! app README for the deferred list.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use lopdf::content::Content;
use lopdf::{dictionary, Dictionary, Document, Object, ObjectId, Stream};

mod crypto;

// ---------------------------------------------------------------------------
// Document registry
// ---------------------------------------------------------------------------

/// Process-wide registry of parsed documents keyed by an opaque `i64` handle,
/// mirroring the weather crate's `cached_backend`. Keeping the parsed
/// `Document` alive lets Kotlin re-render / re-scroll pages without re-parsing
/// the file.
fn registry() -> &'static Mutex<HashMap<i64, Document>> {
    static REG: OnceLock<Mutex<HashMap<i64, Document>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn next_handle() -> i64 {
    static NEXT: OnceLock<Mutex<i64>> = OnceLock::new();
    let m = NEXT.get_or_init(|| Mutex::new(0));
    let mut guard = m.lock().unwrap();
    *guard += 1;
    *guard
}

/// Parse `bytes` into a document and store it, returning a non-zero handle.
/// Encrypted documents are decrypted in place (with `password`, empty allowed);
/// returns 0 on parse failure, wrong password, or unsupported (AES) encryption.
fn open_document_pw(bytes: &[u8], password: &[u8]) -> i64 {
    let mut doc = match Document::load_mem(bytes) {
        Ok(d) => d,
        Err(_) => return 0,
    };
    if doc.trailer.get(b"Encrypt").is_ok() {
        if decrypt_in_place(&mut doc, password) != DecryptStatus::Ok {
            return 0;
        }
    }
    let handle = next_handle();
    registry().lock().unwrap().insert(handle, doc);
    handle
}

fn open_document(bytes: &[u8]) -> i64 {
    open_document_pw(bytes, b"")
}

/// Whether `bytes` is a standard-encrypted PDF that needs a (non-empty) password
/// the empty password does not satisfy. Returns: 0 no, 1 needs password, 2
/// unsupported encryption (e.g. AES).
fn pdf_password_state(bytes: &[u8]) -> i32 {
    let mut doc = match Document::load_mem(bytes) {
        Ok(d) => d,
        Err(_) => return 0,
    };
    if doc.trailer.get(b"Encrypt").is_err() {
        return 0;
    }
    match decrypt_in_place(&mut doc, b"") {
        DecryptStatus::Ok => 0,
        DecryptStatus::NeedPassword => 1,
        DecryptStatus::Unsupported => 2,
    }
}

#[derive(PartialEq)]
enum DecryptStatus {
    Ok,
    NeedPassword,
    Unsupported,
}

/// Apply a cipher (`apply`) to every string and stream inside `obj`.
fn crypt_object(obj: &mut Object, apply: &dyn Fn(&[u8]) -> Vec<u8>) {
    match obj {
        Object::String(s, _) => *s = apply(s),
        Object::Array(a) => {
            for o in a.iter_mut() {
                crypt_object(o, apply);
            }
        }
        Object::Dictionary(d) => {
            let keys: Vec<Vec<u8>> = d.iter().map(|(k, _)| k.clone()).collect();
            for k in keys {
                if let Ok(v) = d.get_mut(&k) {
                    crypt_object(v, apply);
                }
            }
        }
        Object::Stream(st) => {
            let keys: Vec<Vec<u8>> = st.dict.iter().map(|(k, _)| k.clone()).collect();
            for k in keys {
                if let Ok(v) = st.dict.get_mut(&k) {
                    crypt_object(v, apply);
                }
            }
            st.content = apply(&st.content);
        }
        _ => {}
    }
}

/// First `/ID` element bytes from the trailer, or empty.
fn trailer_id0(doc: &Document) -> Vec<u8> {
    if let Ok(Object::Array(a)) = doc.trailer.get(b"ID") {
        if let Some(Object::String(s, _)) = a.first() {
            return s.clone();
        }
    }
    Vec::new()
}

#[derive(Clone, Copy, PartialEq)]
enum CryptMethod {
    Rc4,
    AesV2,
    AesV3,
}

/// Decrypt a standard-encrypted document (RC4 or AES) in place with `password`.
fn decrypt_in_place(doc: &mut Document, password: &[u8]) -> DecryptStatus {
    let enc_id = match doc.trailer.get(b"Encrypt").and_then(|o| o.as_reference()) {
        Ok(id) => id,
        Err(_) => return DecryptStatus::Unsupported,
    };
    let (o, u, ue, p, r, length, method) = {
        let enc = match doc.get_dictionary(enc_id) {
            Ok(d) => d,
            Err(_) => return DecryptStatus::Unsupported,
        };
        let filter = enc.get(b"Filter").ok().and_then(|o| o.as_name().ok());
        if filter != Some(b"Standard".as_ref()) {
            return DecryptStatus::Unsupported;
        }
        let v = enc.get(b"V").ok().and_then(num).unwrap_or(0.0) as i64;
        let r = enc.get(b"R").ok().and_then(num).unwrap_or(0.0) as i64;
        // Determine the crypt method.
        let method = if v >= 5 {
            CryptMethod::AesV3
        } else if v == 4 {
            // Read /CF /StdCF /CFM.
            let cfm = enc
                .get(b"CF")
                .ok()
                .and_then(|o| o.as_dict().ok())
                .and_then(|cf| cf.get(b"StdCF").ok())
                .and_then(|s| s.as_dict().ok())
                .and_then(|s| s.get(b"CFM").ok())
                .and_then(|o| o.as_name().ok());
            match cfm {
                Some(b) if b == b"AESV3" => CryptMethod::AesV3,
                Some(b) if b == b"AESV2" => CryptMethod::AesV2,
                Some(b) if b == b"V2" => CryptMethod::Rc4,
                _ => return DecryptStatus::Unsupported,
            }
        } else {
            CryptMethod::Rc4
        };
        let o = enc.get(b"O").ok().and_then(|o| o.as_str().ok()).map(|s| s.to_vec()).unwrap_or_default();
        let u = enc.get(b"U").ok().and_then(|o| o.as_str().ok()).map(|s| s.to_vec()).unwrap_or_default();
        let ue = enc.get(b"UE").ok().and_then(|o| o.as_str().ok()).map(|s| s.to_vec()).unwrap_or_default();
        let p = enc.get(b"P").ok().and_then(num).unwrap_or(0.0) as i32;
        let default_len = if method == CryptMethod::AesV2 { 128.0 } else { 40.0 };
        let length = enc.get(b"Length").ok().and_then(num).unwrap_or(default_len) as usize;
        (o, u, ue, p, r, length, method)
    };

    let id0 = trailer_id0(doc);
    let n = if method == CryptMethod::AesV2 { 16 } else { (length / 8).clamp(5, 16) };

    // Derive the file key.
    let key = match method {
        CryptMethod::AesV3 => match crypto::authenticate_v5(password, &u, &ue, r as u8) {
            Some(k) => k,
            None => return DecryptStatus::NeedPassword,
        },
        _ => match crypto::authenticate(password, &o, &u, p, &id0, n, r as u8) {
            Some(k) => k,
            None => return DecryptStatus::NeedPassword,
        },
    };

    let ids: Vec<ObjectId> = doc.objects.keys().copied().collect();
    for id in ids {
        if id == enc_id {
            continue;
        }
        let apply: Box<dyn Fn(&[u8]) -> Vec<u8>> = match method {
            CryptMethod::Rc4 => {
                let okey = crypto::object_key(&key, id.0, id.1, n);
                Box::new(move |d: &[u8]| crypto::rc4(&okey, d))
            }
            CryptMethod::AesV2 => {
                let okey = crypto::object_key_aes(&key, id.0, id.1, n);
                Box::new(move |d: &[u8]| crypto::aes_cbc_decrypt(&okey, d))
            }
            CryptMethod::AesV3 => {
                let k = key.clone();
                Box::new(move |d: &[u8]| crypto::aes_cbc_decrypt(&k, d))
            }
        };
        if let Some(obj) = doc.objects.get_mut(&id) {
            crypt_object(obj, &apply);
        }
    }
    doc.trailer.remove(b"Encrypt");
    DecryptStatus::Ok
}

/// Serialize `handle` encrypted with the given passwords (RC4-128, R3).
fn save_encrypted(handle: i64, user_pw: &[u8], owner_pw: &[u8]) -> Option<Vec<u8>> {
    let bytes = save_document(handle)?;
    let mut doc = Document::load_mem(&bytes).ok()?;
    let n = 16usize;
    let rev = 3u8;
    let p: i32 = -4; // allow all operations
    // Ensure an /ID exists.
    let id0 = {
        let existing = trailer_id0(&doc);
        if existing.is_empty() {
            let h = {
                use md5::{Digest, Md5};
                let mut m = Md5::new();
                m.update(&bytes);
                let d: [u8; 16] = m.finalize().into();
                d.to_vec()
            };
            doc.trailer.set(
                "ID",
                Object::Array(vec![
                    Object::String(h.clone(), lopdf::StringFormat::Hexadecimal),
                    Object::String(h.clone(), lopdf::StringFormat::Hexadecimal),
                ]),
            );
            h
        } else {
            existing
        }
    };
    let owner = if owner_pw.is_empty() { user_pw } else { owner_pw };
    let o = crypto::compute_o(owner, user_pw, n, rev);
    let key = crypto::compute_key(user_pw, &o, p, &id0, n, rev);
    let u = crypto::compute_u(&key, &id0, rev);

    let mut enc = Dictionary::new();
    enc.set("Filter", name_obj("Standard"));
    enc.set("V", Object::Integer(2));
    enc.set("R", Object::Integer(3));
    enc.set("Length", Object::Integer(128));
    enc.set("P", Object::Integer(p as i64));
    enc.set("O", Object::String(o, lopdf::StringFormat::Literal));
    enc.set("U", Object::String(u, lopdf::StringFormat::Literal));
    let enc_id = doc.add_object(enc);

    let ids: Vec<ObjectId> = doc.objects.keys().copied().collect();
    for id in ids {
        if id == enc_id {
            continue;
        }
        let okey = crypto::object_key(&key, id.0, id.1, n);
        let apply = move |d: &[u8]| crypto::rc4(&okey, d);
        if let Some(obj) = doc.objects.get_mut(&id) {
            crypt_object(obj, &apply);
        }
    }
    doc.trailer.set("Encrypt", Object::Reference(enc_id));

    let mut out = Vec::new();
    doc.save_to(&mut out).ok()?;
    Some(out)
}

fn page_count(handle: i64) -> i32 {
    let reg = registry().lock().unwrap();
    match reg.get(&handle) {
        Some(doc) => doc.get_pages().len() as i32,
        None => 0,
    }
}

fn close_document(handle: i64) {
    registry().lock().unwrap().remove(&handle);
    index_cache().lock().unwrap().remove(&handle);
}

// ---------------------------------------------------------------------------
// Compose / merge ("cut and glue")
// ---------------------------------------------------------------------------

/// Create a new empty PDF document and return its handle.
fn create_empty_document() -> i64 {
    let mut doc = Document::with_version("1.7");
    let pages_id = doc.add_object(dictionary! {
        "Type" => "Pages",
        "Kids" => Object::Array(vec![]),
        "Count" => 0,
    });
    let catalog_id = doc.add_object(dictionary! {
        "Type" => "Catalog",
        "Pages" => pages_id,
    });
    doc.trailer.set("Root", catalog_id);
    let handle = next_handle();
    registry().lock().unwrap().insert(handle, doc);
    handle
}

/// The document's `/Pages` root object id.
fn pages_root(doc: &Document) -> Option<ObjectId> {
    let root = doc.trailer.get(b"Root").ok().and_then(|o| o.as_reference().ok())?;
    let cat = doc.get_dictionary(root).ok()?;
    cat.get(b"Pages").ok().and_then(|o| o.as_reference().ok())
}

/// Append a page reference to the `/Pages` tree and refresh `/Count`.
fn append_kid(doc: &mut Document, pages_id: ObjectId, page_id: ObjectId) {
    if let Ok(pages) = doc.get_dictionary_mut(pages_id) {
        let has = matches!(pages.get(b"Kids"), Ok(Object::Array(_)));
        if !has {
            pages.set("Kids", Object::Array(vec![]));
        }
        if let Ok(Object::Array(a)) = pages.get_mut(b"Kids") {
            a.push(Object::Reference(page_id));
        }
        let count = if let Ok(Object::Array(a)) = pages.get(b"Kids") { a.len() as i64 } else { 0 };
        pages.set("Count", count);
    }
}

/// Deep-copy an object, remapping any object references through `map`.
fn remap_object(obj: &Object, map: &HashMap<ObjectId, ObjectId>) -> Object {
    match obj {
        Object::Reference(id) => Object::Reference(*map.get(id).unwrap_or(id)),
        Object::Array(a) => Object::Array(a.iter().map(|o| remap_object(o, map)).collect()),
        Object::Dictionary(d) => {
            let mut nd = Dictionary::new();
            for (k, v) in d.iter() {
                nd.set(k.clone(), remap_object(v, map));
            }
            Object::Dictionary(nd)
        }
        Object::Stream(s) => {
            let mut ns = s.clone();
            let mut nd = Dictionary::new();
            for (k, v) in s.dict.iter() {
                nd.set(k.clone(), remap_object(v, map));
            }
            ns.dict = nd;
            Object::Stream(ns)
        }
        other => other.clone(),
    }
}

/// Append every page of the PDF in `bytes` to the document behind `handle`.
/// Returns the number of pages added (0 on failure/encrypted source).
fn append_pdf(handle: i64, bytes: &[u8]) -> i32 {
    let src = match Document::load_mem(bytes) {
        Ok(d) => d,
        Err(_) => return 0,
    };
    if src.trailer.get(b"Encrypt").is_ok() {
        return 0;
    }
    let mut reg = registry().lock().unwrap();
    let dest = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return 0,
    };
    let pages_id = match pages_root(dest) {
        Some(p) => p,
        None => return 0,
    };
    // Reserve fresh ids for every source object, then copy them in remapped.
    let mut map: HashMap<ObjectId, ObjectId> = HashMap::new();
    for old_id in src.objects.keys() {
        dest.max_id += 1;
        map.insert(*old_id, (dest.max_id, 0));
    }
    for (old_id, obj) in &src.objects {
        let new = remap_object(obj, &map);
        dest.objects.insert(map[old_id], new);
    }
    let mut added = 0;
    for (_num, src_page_id) in src.get_pages() {
        let new_page_id = match map.get(&src_page_id) {
            Some(id) => *id,
            None => continue,
        };
        // Resolve inherited MediaBox/Resources onto the imported page since its
        // parent is now our (attribute-less) Pages root.
        let mb = media_box(&src, src_page_id);
        let res = inherited(&src, src_page_id, b"Resources").map(|o| remap_object(o, &map));
        if let Ok(pd) = dest.get_dictionary_mut(new_page_id) {
            pd.set("Parent", Object::Reference(pages_id));
            if pd.get(b"MediaBox").is_err() {
                pd.set(
                    "MediaBox",
                    Object::Array(vec![mb[0].into(), mb[1].into(), mb[2].into(), mb[3].into()]),
                );
            }
            if pd.get(b"Resources").is_err() {
                if let Some(r) = res {
                    pd.set("Resources", r);
                }
            }
        }
        append_kid(dest, pages_id, new_page_id);
        added += 1;
    }
    added
}

/// Append a JPEG image as a new full-width page. Returns 1 on success.
fn append_image_page(handle: i64, jpeg: &[u8], img_w: u32, img_h: u32) -> i32 {
    if img_w == 0 || img_h == 0 {
        return 0;
    }
    let mut reg = registry().lock().unwrap();
    let dest = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return 0,
    };
    let pages_id = match pages_root(dest) {
        Some(p) => p,
        None => return 0,
    };
    let pw = 595.0_f64; // A4 width in points
    let ph = pw * img_h as f64 / img_w as f64;

    let mut img_dict = Dictionary::new();
    img_dict.set("Type", name_obj("XObject"));
    img_dict.set("Subtype", name_obj("Image"));
    img_dict.set("Width", Object::Integer(img_w as i64));
    img_dict.set("Height", Object::Integer(img_h as i64));
    img_dict.set("BitsPerComponent", Object::Integer(8));
    img_dict.set("ColorSpace", name_obj("DeviceRGB"));
    img_dict.set("Filter", name_obj("DCTDecode"));
    let img_id = dest.add_object(Stream::new(img_dict, jpeg.to_vec()));

    let content = format!("q {pw:.2} 0 0 {ph:.2} 0 0 cm /Im0 Do Q").into_bytes();
    let content_id = dest.add_object(Stream::new(dictionary! {}, content));

    let page = dictionary! {
        "Type" => "Page",
        "Parent" => pages_id,
        "MediaBox" => Object::Array(vec![0.into(), 0.into(), pw.into(), ph.into()]),
        "Contents" => content_id,
        "Resources" => dictionary! {
            "XObject" => dictionary! { "Im0" => img_id },
        },
    };
    let page_id = dest.add_object(page);
    append_kid(dest, pages_id, page_id);
    1
}

/// Move the page at `from` to index `to` in the page order. Returns success.
fn move_page(handle: i64, from: usize, to: usize) -> bool {
    let mut reg = registry().lock().unwrap();
    let dest = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let pages_id = match pages_root(dest) {
        Some(p) => p,
        None => return false,
    };
    if let Ok(pages) = dest.get_dictionary_mut(pages_id) {
        if let Ok(Object::Array(a)) = pages.get_mut(b"Kids") {
            if from < a.len() && to < a.len() {
                let item = a.remove(from);
                a.insert(to, item);
                return true;
            }
        }
    }
    false
}

/// Delete the page at `index` from the page order (keeps orphan objects).
fn remove_page(handle: i64, index: usize) -> bool {
    let mut reg = registry().lock().unwrap();
    let dest = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let pages_id = match pages_root(dest) {
        Some(p) => p,
        None => return false,
    };
    if let Ok(pages) = dest.get_dictionary_mut(pages_id) {
        let removed = if let Ok(Object::Array(a)) = pages.get_mut(b"Kids") {
            if index < a.len() {
                a.remove(index);
                true
            } else {
                false
            }
        } else {
            false
        };
        if removed {
            let count = if let Ok(Object::Array(a)) = pages.get(b"Kids") { a.len() as i64 } else { 0 };
            pages.set("Count", count);
        }
        return removed;
    }
    false
}

/// Rotate the page at `index` by `delta` degrees (adjusts `/Rotate`).
fn rotate_page(handle: i64, index: i32, delta: i32) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let page_id = match nth_page_id(doc, index) {
        Some(p) => p,
        None => return false,
    };
    let cur = page_rotation(doc, page_id) as i32;
    let new = (((cur + delta) % 360) + 360) % 360;
    if let Ok(pd) = doc.get_dictionary_mut(page_id) {
        pd.set("Rotate", Object::Integer(new as i64));
        true
    } else {
        false
    }
}

/// Extract the page at `index` into a standalone one-page PDF, returned as bytes.
fn extract_page(handle: i64, index: i32) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap();
    let src = reg.get(&handle)?;
    let src_page_id = nth_page_id(src, index)?;

    let mut out = Document::with_version("1.7");
    let pages_id = out.add_object(dictionary! {
        "Type" => "Pages",
        "Kids" => Object::Array(vec![]),
        "Count" => 0,
    });
    let catalog_id = out.add_object(dictionary! {
        "Type" => "Catalog",
        "Pages" => pages_id,
    });
    out.trailer.set("Root", catalog_id);

    // Copy the whole source object graph, then attach just the chosen page.
    let mut map: HashMap<ObjectId, ObjectId> = HashMap::new();
    for old_id in src.objects.keys() {
        out.max_id += 1;
        map.insert(*old_id, (out.max_id, 0));
    }
    for (old_id, obj) in &src.objects {
        out.objects.insert(map[old_id], remap_object(obj, &map));
    }
    let new_page_id = *map.get(&src_page_id)?;
    let mb = media_box(src, src_page_id);
    let res = inherited(src, src_page_id, b"Resources").map(|o| remap_object(o, &map));
    let rot = page_rotation(src, src_page_id);
    drop(reg);

    if let Ok(pd) = out.get_dictionary_mut(new_page_id) {
        pd.set("Parent", Object::Reference(pages_id));
        if pd.get(b"MediaBox").is_err() {
            pd.set(
                "MediaBox",
                Object::Array(vec![mb[0].into(), mb[1].into(), mb[2].into(), mb[3].into()]),
            );
        }
        if pd.get(b"Resources").is_err() {
            if let Some(r) = res {
                pd.set("Resources", r);
            }
        }
        if rot != 0 {
            pd.set("Rotate", Object::Integer(rot));
        }
    }
    append_kid(&mut out, pages_id, new_page_id);

    let mut buf = Vec::new();
    out.save_to(&mut buf).ok()?;
    Some(buf)
}

/// Serialize page `index` (0-based) of the document behind `handle` into the
/// wire buffer, or `None` on any error.
fn render_page(handle: i64, index: i32) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap();
    let doc = reg.get(&handle)?;
    let pages = doc.get_pages();
    let page_id = *pages.get(&((index as u32) + 1))?;
    let page = interpret_page(doc, page_id).ok()?;
    Some(wire::serialize(&page))
}

// ---------------------------------------------------------------------------
// Geometry / matrix helpers
// ---------------------------------------------------------------------------

/// A PDF transformation matrix `[a b c d e f]` representing
/// `[[a b 0] [c d 0] [e f 1]]`.
type Mat = [f64; 6];

const IDENTITY: Mat = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0];

/// `m1 * m2` in PDF convention (m1 is applied first).
fn mat_mul(m1: &Mat, m2: &Mat) -> Mat {
    [
        m1[0] * m2[0] + m1[1] * m2[2],
        m1[0] * m2[1] + m1[1] * m2[3],
        m1[2] * m2[0] + m1[3] * m2[2],
        m1[2] * m2[1] + m1[3] * m2[3],
        m1[4] * m2[0] + m1[5] * m2[2] + m2[4],
        m1[4] * m2[1] + m1[5] * m2[3] + m2[5],
    ]
}

/// Transform point `(x, y)` by `m`.
fn transform(m: &Mat, x: f64, y: f64) -> (f64, f64) {
    (m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5])
}

fn translate(tx: f64, ty: f64) -> Mat {
    [1.0, 0.0, 0.0, 1.0, tx, ty]
}

// ---------------------------------------------------------------------------
// Primitives
// ---------------------------------------------------------------------------

/// Drawing primitives in PDF page space (origin bottom-left). Kotlin performs
/// the Y-flip and fit-to-width scale.
enum Prim {
    Text {
        x: f32,
        y: f32,
        size: f32,
        argb: u32,
        text: String,
    },
    Fill {
        argb: u32,
        even_odd: bool,
        pts: Vec<(f32, f32)>,
    },
    Stroke {
        argb: u32,
        width: f32,
        /// Dash segment lengths in device space (empty = solid).
        dash: Vec<f32>,
        dash_phase: f32,
        pts: Vec<(f32, f32)>,
    },
    /// A raster image placed by mapping the unit square through `ctm` (PDF image
    /// space). `format`: 0 = raw RGBA8888 (`w*h*4` bytes), 1 = JPEG bytes.
    Image {
        ctm: Mat,
        w: u32,
        h: u32,
        format: u8,
        data: Vec<u8>,
    },
}

struct PageData {
    width: f32,
    height: f32,
    prims: Vec<Prim>,
}

/// Multiply the alpha channel of a primitive's color by `alpha` (0..1). Used to
/// honor an annotation's constant opacity (`/CA`). Images are left unchanged.
fn scale_prim_alpha(prim: &mut Prim, alpha: f64) {
    let scale = |argb: &mut u32| {
        let a = ((*argb >> 24) & 0xFF) as f64;
        let na = (a * alpha).round().clamp(0.0, 255.0) as u32;
        *argb = (*argb & 0x00FF_FFFF) | (na << 24);
    };
    match prim {
        Prim::Text { argb, .. } => scale(argb),
        Prim::Fill { argb, .. } => scale(argb),
        Prim::Stroke { argb, .. } => scale(argb),
        Prim::Image { .. } => {}
    }
}

// ---------------------------------------------------------------------------
// Object helpers
// ---------------------------------------------------------------------------

/// Numeric value of an integer or real object, else `None`.
fn num(obj: &Object) -> Option<f64> {
    match obj {
        Object::Integer(i) => Some(*i as f64),
        Object::Real(r) => Some(*r as f64),
        _ => None,
    }
}

/// Follow a chain of references to the underlying object.
fn deref<'a>(doc: &'a Document, obj: &'a Object) -> Option<&'a Object> {
    match doc.dereference(obj) {
        Ok((_, o)) => Some(o),
        Err(_) => None,
    }
}

/// Decoded stream bytes, falling back to the raw content when the stream has no
/// `/Filter` (lopdf's `decompressed_content` errors instead of returning raw).
fn stream_data(s: &lopdf::Stream) -> Vec<u8> {
    s.decompressed_content().unwrap_or_else(|_| s.content.clone())
}

// ---------------------------------------------------------------------------
// Fonts + ToUnicode
// ---------------------------------------------------------------------------

struct FontInfo {
    /// Type0 (Identity-H) fonts use 2-byte codes; simple fonts use 1 byte.
    two_byte: bool,
    /// `code -> unicode string` from the font's `/ToUnicode` CMap, if any.
    to_unicode: Option<HashMap<u32, String>>,
    /// `code -> unicode char` from the simple-font encoding (base + Differences),
    /// used when `/ToUnicode` is absent or lacks the code.
    encoding: HashMap<u32, char>,
    /// `code -> unicode char` recovered from an embedded TrueType `cmap`, for
    /// re-encoded subset fonts without `/ToUnicode`. Preferred over `encoding`.
    cmap_uni: HashMap<u32, char>,
    /// `code (or CID) -> glyph width` in text-space units (glyph units / 1000).
    widths: HashMap<u32, f64>,
    /// Fallback width (glyph units / 1000) for codes absent from `widths`.
    default_width: f64,
}

impl FontInfo {
    /// Invoke `f(code, is_single_byte_space)` for each character code in the
    /// string, honoring this font's code width (1 or 2 bytes).
    fn for_each_code(&self, bytes: &[u8], mut f: impl FnMut(u32, bool)) {
        if self.two_byte {
            let mut i = 0;
            while i + 1 < bytes.len() {
                let code = ((bytes[i] as u32) << 8) | bytes[i + 1] as u32;
                f(code, false);
                i += 2;
            }
        } else {
            for &b in bytes {
                f(b as u32, b == 32);
            }
        }
    }

    /// Width of `code` in text-space units (glyph units / 1000).
    fn width(&self, code: u32) -> f64 {
        self.widths.get(&code).copied().unwrap_or(self.default_width)
    }

    fn push_code(&self, code: u32, out: &mut String) {
        if let Some(map) = &self.to_unicode {
            if let Some(s) = map.get(&code) {
                out.push_str(s);
                return;
            }
        }
        // Prefer the declared encoding (WinAnsi / Differences) so standard
        // punctuation is correct; fall back to the embedded cmap for symbolic
        // re-encoded subset fonts whose encoding doesn't cover the code.
        if let Some(c) = self.encoding.get(&code) {
            out.push(*c);
            return;
        }
        if let Some(c) = self.cmap_uni.get(&code) {
            out.push(*c);
            return;
        }
        // Last resort: Latin-1 for single-byte codes; best-effort otherwise.
        if let Some(c) = char::from_u32(code) {
            out.push(c);
        }
    }
}

/// Build a `font resource name -> FontInfo` map from a resources dictionary.
fn fonts_from_resources(doc: &Document, res_dict: &lopdf::Dictionary) -> HashMap<Vec<u8>, FontInfo> {
    let mut fonts = HashMap::new();
    let font_dict = match res_dict.get(b"Font").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Dictionary(d)) => d,
        _ => return fonts,
    };
    for (name, font_ref) in font_dict.iter() {
        if let Some(Object::Dictionary(fd)) = deref(doc, font_ref) {
            fonts.insert(name.clone(), font_info(doc, fd));
        }
    }
    fonts
}

fn font_info(doc: &Document, font: &lopdf::Dictionary) -> FontInfo {
    let two_byte = matches!(
        font.get(b"Subtype").ok().and_then(|o| o.as_name().ok()),
        Some(b"Type0")
    );
    let to_unicode = font
        .get(b"ToUnicode")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| match o {
            Object::Stream(s) => Some(stream_data(s)),
            _ => None,
        })
        .map(|data| cmap::parse(&data));

    let (widths, default_width) = if two_byte {
        cid_widths(doc, font)
    } else {
        simple_widths(doc, font)
    };

    let encoding = if two_byte {
        HashMap::new()
    } else {
        encoding::build(doc, font)
    };
    let cmap_uni = if two_byte {
        HashMap::new()
    } else {
        ttf_code_map(doc, font)
    };

    FontInfo {
        two_byte,
        to_unicode,
        encoding,
        cmap_uni,
        widths,
        default_width,
    }
}

/// Widths for a simple (1-byte) font from `/Widths` + `/FirstChar`, with the
/// `/FontDescriptor /MissingWidth` fallback. Values are glyph units / 1000.
fn simple_widths(doc: &Document, font: &lopdf::Dictionary) -> (HashMap<u32, f64>, f64) {
    let mut widths = HashMap::new();
    let first_char = font
        .get(b"FirstChar")
        .ok()
        .and_then(num)
        .unwrap_or(0.0) as u32;
    if let Some(Object::Array(arr)) = font.get(b"Widths").ok().and_then(|o| deref(doc, o)) {
        for (i, w) in arr.iter().enumerate() {
            if let Some(w) = deref(doc, w).and_then(num) {
                widths.insert(first_char + i as u32, w / 1000.0);
            }
        }
    }
    let missing = font
        .get(b"FontDescriptor")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|d| d.get(b"MissingWidth").ok())
        .and_then(num)
        .unwrap_or(0.0)
        / 1000.0;
    // Simple fonts without a /Widths array (e.g. the standard 14) get a
    // reasonable default so advances are non-degenerate.
    let default_width = if widths.is_empty() { 0.5 } else { missing };
    (widths, default_width)
}

/// Widths for a Type0/CID font from the descendant font's `/W` array + `/DW`.
/// The map is keyed by CID (== 2-byte code for Identity-H). Units glyph/1000.
fn cid_widths(doc: &Document, font: &lopdf::Dictionary) -> (HashMap<u32, f64>, f64) {
    let mut widths = HashMap::new();
    let mut default_width = 1.0; // /DW default is 1000 glyph units.

    let descendant = font
        .get(b"DescendantFonts")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| match o {
            Object::Array(a) => a.first(),
            _ => None,
        })
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok());

    let df = match descendant {
        Some(d) => d,
        None => return (widths, default_width),
    };

    if let Some(dw) = df.get(b"DW").ok().and_then(num) {
        default_width = dw / 1000.0;
    }

    // /W: [ c [w1 w2 ...]  cFirst cLast w  ... ]
    if let Some(Object::Array(w)) = df.get(b"W").ok().and_then(|o| deref(doc, o)) {
        let mut i = 0;
        while i < w.len() {
            let c = match deref(doc, &w[i]).and_then(num) {
                Some(v) => v as u32,
                None => break,
            };
            match w.get(i + 1).and_then(|o| deref(doc, o)) {
                Some(Object::Array(list)) => {
                    for (j, item) in list.iter().enumerate() {
                        if let Some(v) = deref(doc, item).and_then(num) {
                            widths.insert(c + j as u32, v / 1000.0);
                        }
                    }
                    i += 2;
                }
                _ => {
                    let c_last = w.get(i + 1).and_then(|o| deref(doc, o)).and_then(num);
                    let width = w.get(i + 2).and_then(|o| deref(doc, o)).and_then(num);
                    if let (Some(c_last), Some(width)) = (c_last, width) {
                        for cid in c..=(c_last as u32) {
                            widths.insert(cid, width / 1000.0);
                        }
                    }
                    i += 3;
                }
            }
        }
    }
    (widths, default_width)
}

/// Look up `key` on the page dict, walking up `/Parent` for inherited
/// attributes (`MediaBox`, `Resources`).
fn inherited<'a>(doc: &'a Document, page_id: ObjectId, key: &[u8]) -> Option<&'a Object> {
    let mut current = page_id;
    for _ in 0..32 {
        let dict = doc.get_dictionary(current).ok()?;
        if let Ok(obj) = dict.get(key) {
            return Some(obj);
        }
        match dict.get(b"Parent").ok().and_then(|o| o.as_reference().ok()) {
            Some(parent) => current = parent,
            None => return None,
        }
    }
    None
}

/// Page MediaBox as `[x0, y0, x1, y1]`, defaulting to US Letter.
fn media_box(doc: &Document, page_id: ObjectId) -> [f64; 4] {
    let default = [0.0, 0.0, 612.0, 792.0];
    let obj = match inherited(doc, page_id, b"MediaBox").and_then(|o| deref(doc, o)) {
        Some(o) => o,
        None => return default,
    };
    let arr = match obj.as_array() {
        Ok(a) => a,
        Err(_) => return default,
    };
    if arr.len() != 4 {
        return default;
    }
    let mut out = [0.0; 4];
    for (i, v) in arr.iter().enumerate() {
        out[i] = match deref(doc, v).and_then(num) {
            Some(n) => n,
            None => return default,
        };
    }
    out
}

/// Normalized page rotation in {0,90,180,270}, inherited via `/Parent`.
fn page_rotation(doc: &Document, page_id: ObjectId) -> i64 {
    let r = inherited(doc, page_id, b"Rotate")
        .and_then(|o| deref(doc, o))
        .and_then(num)
        .unwrap_or(0.0) as i64;
    (((r % 360) + 360) % 360 / 90) * 90
}

/// Matrix mapping raw page space (MediaBox origin, before rotation) into
/// displayed space: origin bottom-left, with dimensions swapped for 90/270.
fn page_base_matrix(doc: &Document, page_id: ObjectId) -> Mat {
    let mb = media_box(doc, page_id);
    let w = (mb[2] - mb[0]).abs();
    let h = (mb[3] - mb[1]).abs();
    let t = translate(-mb[0].min(mb[2]), -mb[1].min(mb[3]));
    let r: Mat = match page_rotation(doc, page_id) {
        90 => [0.0, 1.0, -1.0, 0.0, h, 0.0],
        180 => [-1.0, 0.0, 0.0, -1.0, w, h],
        270 => [0.0, -1.0, 1.0, 0.0, 0.0, w],
        _ => IDENTITY,
    };
    mat_mul(&t, &r)
}

/// Page dimensions as displayed (after `/Rotate`).
fn page_display_size(doc: &Document, page_id: ObjectId) -> (f32, f32) {
    let mb = media_box(doc, page_id);
    let w = (mb[2] - mb[0]).abs() as f32;
    let h = (mb[3] - mb[1]).abs() as f32;
    match page_rotation(doc, page_id) {
        90 | 270 => (h, w),
        _ => (w, h),
    }
}

/// Inverse of an affine matrix `[a b c d e f]` (identity if singular).
fn mat_inverse(m: &Mat) -> Mat {
    let det = m[0] * m[3] - m[1] * m[2];
    if det.abs() < 1e-12 {
        return IDENTITY;
    }
    let inv = 1.0 / det;
    let a = m[3] * inv;
    let b = -m[1] * inv;
    let c = -m[2] * inv;
    let d = m[0] * inv;
    let e = -(m[4] * a + m[5] * c);
    let f = -(m[4] * b + m[5] * d);
    [a, b, c, d, e, f]
}

/// Inverse base matrix for a page index, mapping displayed (editor) coordinates
/// back into raw page space so stored annotations remain valid PDF.
fn page_base_inverse(doc: &Document, page_index: i32) -> Mat {
    match nth_page_id(doc, page_index) {
        Some(pid) => mat_inverse(&page_base_matrix(doc, pid)),
        None => IDENTITY,
    }
}

/// Convert an editor-space rect into a normalized raw-page-space rect.
fn page_rect(doc: &Document, page_index: i32, rect: [f64; 4]) -> [f64; 4] {
    let binv = page_base_inverse(doc, page_index);
    let (x0, y0) = transform(&binv, rect[0], rect[1]);
    let (x1, y1) = transform(&binv, rect[2], rect[3]);
    normalize_rect([x0, y0, x1, y1])
}

/// Convert editor-space flat x,y points into raw-page-space.
fn page_points(doc: &Document, page_index: i32, points: &[f32]) -> Vec<f32> {
    let binv = page_base_inverse(doc, page_index);
    let mut out = Vec::with_capacity(points.len());
    let mut i = 0;
    while i + 1 < points.len() {
        let (x, y) = transform(&binv, points[i] as f64, points[i + 1] as f64);
        out.push(x as f32);
        out.push(y as f32);
        i += 2;
    }
    out
}

fn rgb_to_argb(r: f64, g: f64, b: f64) -> u32 {
    let c = |v: f64| (v.clamp(0.0, 1.0) * 255.0).round() as u32;
    0xFF00_0000 | (c(r) << 16) | (c(g) << 8) | c(b)
}

fn gray_to_argb(v: f64) -> u32 {
    rgb_to_argb(v, v, v)
}

fn cmyk_to_argb(c: f64, m: f64, y: f64, k: f64) -> u32 {
    let r = (1.0 - c) * (1.0 - k);
    let g = (1.0 - m) * (1.0 - k);
    let b = (1.0 - y) * (1.0 - k);
    rgb_to_argb(r, g, b)
}

// ---------------------------------------------------------------------------
// Content-stream interpreter
// ---------------------------------------------------------------------------

#[derive(Clone)]
struct GraphicsState {
    ctm: Mat,
    fill: u32,
    stroke: u32,
    line_width: f64,
    font_key: Vec<u8>,
    font_size: f64,
    /// Character spacing (Tc), user-space units.
    char_spacing: f64,
    /// Word spacing (Tw), user-space units (applies to single-byte code 32).
    word_spacing: f64,
    /// Horizontal scaling (Tz) as a fraction (100% = 1.0).
    h_scale: f64,
    /// Text rise (Ts), user-space units.
    rise: f64,
    /// Text rendering mode (Tr). 3 = invisible, 7 = clip-only (not drawn).
    render_mode: i64,
    /// Dash pattern (user-space segment lengths) and phase; empty = solid.
    dash: Vec<f64>,
    dash_phase: f64,
}

impl Default for GraphicsState {
    fn default() -> Self {
        GraphicsState {
            ctm: IDENTITY,
            fill: 0xFF00_0000,
            stroke: 0xFF00_0000,
            line_width: 1.0,
            font_key: Vec::new(),
            font_size: 0.0,
            char_spacing: 0.0,
            word_spacing: 0.0,
            h_scale: 1.0,
            rise: 0.0,
            render_mode: 0,
            dash: Vec::new(),
            dash_phase: 0.0,
        }
    }
}

/// Number of line segments a bezier curve is flattened into.
const BEZIER_STEPS: usize = 16;

fn interpret_page(doc: &Document, page_id: ObjectId) -> Result<PageData, String> {
    let (width, height) = page_display_size(doc, page_id);
    let base = page_base_matrix(doc, page_id);

    let content = doc
        .get_and_decode_page_content(page_id)
        .map_err(|e| format!("decode content failed: {e:?}"))?;
    let res = resources_dict(doc, page_id);

    let mut prims = Vec::new();
    let mut init = GraphicsState::default();
    init.ctm = base;
    interpret_content(
        doc,
        &content.operations,
        res.as_ref(),
        init,
        &mut prims,
        0,
        false,
    );
    render_annotations(doc, page_id, &base, &mut prims);

    Ok(PageData {
        width,
        height,
        prims,
    })
}

/// Interpret a content stream (`ops`) against a `resources` dictionary into
/// drawing primitives, starting from `init` graphics state. Reused for page
/// content, form XObjects (`Do`), and annotation appearance streams. `depth`
/// bounds recursion through nested form XObjects.
fn interpret_content(
    doc: &Document,
    ops: &[lopdf::content::Operation],
    resources: Option<&lopdf::Dictionary>,
    init: GraphicsState,
    prims: &mut Vec<Prim>,
    depth: u32,
    text_only: bool,
) {
    let fonts = resources
        .map(|r| fonts_from_resources(doc, r))
        .unwrap_or_default();
    let xobjects = resources
        .map(|r| xobjects_from_resources(doc, r))
        .unwrap_or_default();

    let mut gs = init;
    let mut stack: Vec<GraphicsState> = Vec::new();

    // Text state.
    let mut text_matrix = IDENTITY;
    let mut line_matrix = IDENTITY;
    let mut leading = 0.0_f64;

    // Path state: a list of subpaths, each a list of device-space points, plus
    // the current point in *user* space for bezier control-point ops.
    let mut subpaths: Vec<Vec<(f64, f64)>> = Vec::new();
    let mut cur_user: (f64, f64) = (0.0, 0.0);
    let mut start_user: (f64, f64) = (0.0, 0.0);

    let dev = |gs: &GraphicsState, x: f64, y: f64| transform(&gs.ctm, x, y);

    for op in ops {
        let o = &op.operands;
        match op.operator.as_str() {
            // Graphics state stack.
            "q" => stack.push(gs.clone()),
            "Q" => {
                if let Some(s) = stack.pop() {
                    gs = s;
                }
            }
            "cm" => {
                if let Some(m) = read_matrix(o) {
                    gs.ctm = mat_mul(&m, &gs.ctm);
                }
            }
            "w" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.line_width = v;
                }
            }
            "d" => {
                if let Some(Object::Array(arr)) = o.first() {
                    gs.dash = arr.iter().filter_map(num).collect();
                }
                gs.dash_phase = o.get(1).and_then(num).unwrap_or(0.0);
            }

            // Path construction.
            "m" => {
                if let (Some(x), Some(y)) = (o.first().and_then(num), o.get(1).and_then(num)) {
                    cur_user = (x, y);
                    start_user = (x, y);
                    subpaths.push(vec![dev(&gs, x, y)]);
                }
            }
            "l" => {
                if let (Some(x), Some(y)) = (o.first().and_then(num), o.get(1).and_then(num)) {
                    cur_user = (x, y);
                    if let Some(sp) = subpaths.last_mut() {
                        sp.push(dev(&gs, x, y));
                    } else {
                        subpaths.push(vec![dev(&gs, x, y)]);
                    }
                }
            }
            "c" | "v" | "y" => {
                let nums: Vec<f64> = o.iter().filter_map(num).collect();
                let (p1, p2, p3) = match op.operator.as_str() {
                    "c" if nums.len() == 6 => (
                        (nums[0], nums[1]),
                        (nums[2], nums[3]),
                        (nums[4], nums[5]),
                    ),
                    "v" if nums.len() == 4 => {
                        (cur_user, (nums[0], nums[1]), (nums[2], nums[3]))
                    }
                    "y" if nums.len() == 4 => {
                        ((nums[0], nums[1]), (nums[2], nums[3]), (nums[2], nums[3]))
                    }
                    _ => continue,
                };
                let p0 = cur_user;
                for step in 1..=BEZIER_STEPS {
                    let t = step as f64 / BEZIER_STEPS as f64;
                    let (bx, by) = cubic_bezier(p0, p1, p2, p3, t);
                    if let Some(sp) = subpaths.last_mut() {
                        sp.push(dev(&gs, bx, by));
                    }
                }
                cur_user = p3;
            }
            "re" => {
                let nums: Vec<f64> = o.iter().filter_map(num).collect();
                if nums.len() == 4 {
                    let (x, y, w, h) = (nums[0], nums[1], nums[2], nums[3]);
                    let rect = vec![
                        dev(&gs, x, y),
                        dev(&gs, x + w, y),
                        dev(&gs, x + w, y + h),
                        dev(&gs, x, y + h),
                        dev(&gs, x, y),
                    ];
                    subpaths.push(rect);
                    cur_user = (x, y);
                    start_user = (x, y);
                }
            }
            "h" => {
                if let Some(sp) = subpaths.last_mut() {
                    sp.push(dev(&gs, start_user.0, start_user.1));
                }
                cur_user = start_user;
            }

            // Path painting.
            "S" | "s" => {
                if op.operator == "s" {
                    if let Some(sp) = subpaths.last_mut() {
                        sp.push(dev(&gs, start_user.0, start_user.1));
                    }
                }
                emit_stroke(prims, &subpaths, &gs);
                subpaths.clear();
            }
            "f" | "F" | "f*" => {
                emit_fill(prims, &subpaths, gs.fill, op.operator == "f*");
                subpaths.clear();
            }
            "B" | "B*" | "b" | "b*" => {
                if op.operator.starts_with('b') {
                    if let Some(sp) = subpaths.last_mut() {
                        sp.push(dev(&gs, start_user.0, start_user.1));
                    }
                }
                emit_fill(prims, &subpaths, gs.fill, op.operator.ends_with('*'));
                emit_stroke(prims, &subpaths, &gs);
                subpaths.clear();
            }
            "n" => subpaths.clear(),

            // XObjects: image XObjects are rasterized; form XObjects are inlined
            // (interpreted with their own resources + Matrix), depth-limited.
            "Do" => {
                if let Some(Object::Name(name)) = o.first() {
                    if let Some(&id) = xobjects.get(name) {
                        if let Ok(Object::Stream(stream)) = doc.get_object(id) {
                            let subtype = stream
                                .dict
                                .get(b"Subtype")
                                .ok()
                                .and_then(|o| o.as_name().ok());
                            if subtype == Some(b"Image") {
                                if !text_only {
                                    if let Some(img) = extract_image(doc, stream, gs.fill) {
                                        prims.push(Prim::Image {
                                            ctm: gs.ctm,
                                            w: img.w,
                                            h: img.h,
                                            format: img.format,
                                            data: img.data,
                                        });
                                    }
                                }
                            } else if subtype == Some(b"Form") && depth < 10 {
                                let form_matrix = stream
                                    .dict
                                    .get(b"Matrix")
                                    .ok()
                                    .and_then(read_matrix_obj)
                                    .unwrap_or(IDENTITY);
                                let form_res = stream
                                    .dict
                                    .get(b"Resources")
                                    .ok()
                                    .and_then(|o| deref(doc, o))
                                    .and_then(|o| o.as_dict().ok())
                                    .cloned();
                                if let Ok(sub) = Content::decode(&stream_data(stream)) {
                                        let mut sub_gs = gs.clone();
                                        sub_gs.ctm = mat_mul(&form_matrix, &gs.ctm);
                                        let res_ref = form_res.as_ref().or(resources);
                                        interpret_content(
                                            doc,
                                            &sub.operations,
                                            res_ref,
                                            sub_gs,
                                            prims,
                                            depth + 1,
                                            text_only,
                                        );
                                }
                            }
                        }
                    }
                }
            }

            // Color.
            "rg" => {
                let n: Vec<f64> = o.iter().filter_map(num).collect();
                if n.len() == 3 {
                    gs.fill = rgb_to_argb(n[0], n[1], n[2]);
                }
            }
            "RG" => {
                let n: Vec<f64> = o.iter().filter_map(num).collect();
                if n.len() == 3 {
                    gs.stroke = rgb_to_argb(n[0], n[1], n[2]);
                }
            }
            "g" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.fill = gray_to_argb(v);
                }
            }
            "G" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.stroke = gray_to_argb(v);
                }
            }
            "k" => {
                let n: Vec<f64> = o.iter().filter_map(num).collect();
                if n.len() == 4 {
                    gs.fill = cmyk_to_argb(n[0], n[1], n[2], n[3]);
                }
            }
            "K" => {
                let n: Vec<f64> = o.iter().filter_map(num).collect();
                if n.len() == 4 {
                    gs.stroke = cmyk_to_argb(n[0], n[1], n[2], n[3]);
                }
            }

            // Text objects.
            "BT" => {
                text_matrix = IDENTITY;
                line_matrix = IDENTITY;
            }
            "ET" => {}
            "Tf" => {
                if let Some(Object::Name(name)) = o.first() {
                    gs.font_key = name.clone();
                }
                if let Some(sz) = o.get(1).and_then(num) {
                    gs.font_size = sz;
                }
            }
            "TL" => {
                if let Some(v) = o.first().and_then(num) {
                    leading = v;
                }
            }
            "Tc" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.char_spacing = v;
                }
            }
            "Tw" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.word_spacing = v;
                }
            }
            "Tz" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.h_scale = v / 100.0;
                }
            }
            "Ts" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.rise = v;
                }
            }
            "Tr" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.render_mode = v as i64;
                }
            }
            "Td" => {
                if let (Some(tx), Some(ty)) = (o.first().and_then(num), o.get(1).and_then(num)) {
                    line_matrix = mat_mul(&translate(tx, ty), &line_matrix);
                    text_matrix = line_matrix;
                }
            }
            "TD" => {
                if let (Some(tx), Some(ty)) = (o.first().and_then(num), o.get(1).and_then(num)) {
                    leading = -ty;
                    line_matrix = mat_mul(&translate(tx, ty), &line_matrix);
                    text_matrix = line_matrix;
                }
            }
            "Tm" => {
                if let Some(m) = read_matrix(o) {
                    line_matrix = m;
                    text_matrix = m;
                }
            }
            "T*" => {
                line_matrix = mat_mul(&translate(0.0, -leading), &line_matrix);
                text_matrix = line_matrix;
            }
            "Tj" => {
                if let Some(Object::String(bytes, _)) = o.first() {
                    let adv = show_string(prims, &gs, &fonts, &text_matrix, bytes);
                    text_matrix = mat_mul(&translate(adv, 0.0), &text_matrix);
                }
            }
            "'" => {
                line_matrix = mat_mul(&translate(0.0, -leading), &line_matrix);
                text_matrix = line_matrix;
                if let Some(Object::String(bytes, _)) = o.first() {
                    let adv = show_string(prims, &gs, &fonts, &text_matrix, bytes);
                    text_matrix = mat_mul(&translate(adv, 0.0), &text_matrix);
                }
            }
            "\"" => {
                if let Some(aw) = o.first().and_then(num) {
                    gs.word_spacing = aw;
                }
                if let Some(ac) = o.get(1).and_then(num) {
                    gs.char_spacing = ac;
                }
                line_matrix = mat_mul(&translate(0.0, -leading), &line_matrix);
                text_matrix = line_matrix;
                if let Some(Object::String(bytes, _)) = o.get(2) {
                    let adv = show_string(prims, &gs, &fonts, &text_matrix, bytes);
                    text_matrix = mat_mul(&translate(adv, 0.0), &text_matrix);
                }
            }
            "TJ" => {
                if let Some(Object::Array(arr)) = o.first() {
                    for el in arr {
                        match el {
                            Object::String(bytes, _) => {
                                let adv =
                                    show_string(prims, &gs, &fonts, &text_matrix, bytes);
                                text_matrix = mat_mul(&translate(adv, 0.0), &text_matrix);
                            }
                            Object::Integer(_) | Object::Real(_) => {
                                // Kerning adjustment: tx = -n/1000 * Tfs * Th.
                                let n = num(el).unwrap_or(0.0);
                                let tx = -n / 1000.0 * gs.font_size * gs.h_scale;
                                text_matrix = mat_mul(&translate(tx, 0.0), &text_matrix);
                            }
                            _ => {}
                        }
                    }
                }
            }

            _ => {}
        }
    }
}

fn read_matrix(operands: &[Object]) -> Option<Mat> {
    let n: Vec<f64> = operands.iter().filter_map(num).collect();
    if n.len() == 6 {
        Some([n[0], n[1], n[2], n[3], n[4], n[5]])
    } else {
        None
    }
}

/// Read a 6-element matrix from an array object.
fn read_matrix_obj(obj: &Object) -> Option<Mat> {
    match obj {
        Object::Array(a) => read_matrix(a),
        _ => None,
    }
}

/// Read a 4-number array (e.g. `/Rect`, `/BBox`) resolving references.
fn read_rect(doc: &Document, obj: &Object) -> Option<[f64; 4]> {
    let arr = deref(doc, obj)?.as_array().ok()?;
    if arr.len() != 4 {
        return None;
    }
    let mut out = [0.0; 4];
    for (i, v) in arr.iter().enumerate() {
        out[i] = deref(doc, v).and_then(num)?;
    }
    Some(out)
}

/// The matrix mapping an appearance stream's form space to page space, fitting
/// the (Matrix-transformed) `/BBox` into the annotation `/Rect` (PDF 12.5.5).
fn appearance_matrix(rect: [f64; 4], bbox: [f64; 4], matrix: Mat) -> Mat {
    let corners = [
        (bbox[0], bbox[1]),
        (bbox[2], bbox[1]),
        (bbox[2], bbox[3]),
        (bbox[0], bbox[3]),
    ];
    let mut tx0 = f64::INFINITY;
    let mut ty0 = f64::INFINITY;
    let mut tx1 = f64::NEG_INFINITY;
    let mut ty1 = f64::NEG_INFINITY;
    for (x, y) in corners {
        let (px, py) = transform(&matrix, x, y);
        tx0 = tx0.min(px);
        ty0 = ty0.min(py);
        tx1 = tx1.max(px);
        ty1 = ty1.max(py);
    }
    let rx0 = rect[0].min(rect[2]);
    let ry0 = rect[1].min(rect[3]);
    let rx1 = rect[0].max(rect[2]);
    let ry1 = rect[1].max(rect[3]);
    let bw = tx1 - tx0;
    let bh = ty1 - ty0;
    let sx = if bw.abs() > 1e-6 { (rx1 - rx0) / bw } else { 1.0 };
    let sy = if bh.abs() > 1e-6 { (ry1 - ry0) / bh } else { 1.0 };
    let fit = [sx, 0.0, 0.0, sy, rx0 - sx * tx0, ry0 - sy * ty0];
    mat_mul(&matrix, &fit)
}

/// Render each visible page annotation's normal appearance (`/AP /N`) into
/// primitives, mapping the appearance BBox into the annotation Rect, then
/// through `base` (page rotation / origin) into displayed space.
fn render_annotations(doc: &Document, page_id: ObjectId, base: &Mat, prims: &mut Vec<Prim>) {
    let annots = match doc
        .get_dictionary(page_id)
        .ok()
        .and_then(|d| d.get(b"Annots").ok())
        .and_then(|o| deref(doc, o))
    {
        Some(Object::Array(a)) => a.clone(),
        _ => return,
    };

    for a in &annots {
        let dict = match deref(doc, a).and_then(|o| o.as_dict().ok()) {
            Some(d) => d,
            None => continue,
        };
        // Skip Hidden (bit 2) and NoView (bit 6) annotations.
        let flags = dict.get(b"F").ok().and_then(num).unwrap_or(0.0) as i64;
        if flags & 0b10 != 0 || flags & 0b10_0000 != 0 {
            continue;
        }
        render_annotation(doc, dict, base, prims);
    }
}

fn render_annotation(doc: &Document, dict: &lopdf::Dictionary, base: &Mat, prims: &mut Vec<Prim>) {
    let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
        Some(r) => r,
        None => return,
    };

    // Resolve the normal appearance: /AP /N is either a stream or a subdictionary
    // of appearance states selected by /AS.
    let ap = match dict.get(b"AP").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Dictionary(d)) => d,
        _ => return,
    };
    let normal = match ap.get(b"N").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Stream(s)) => s,
        Some(Object::Dictionary(states)) => {
            let as_name = dict.get(b"AS").ok().and_then(|o| o.as_name().ok());
            let picked = as_name
                .and_then(|n| states.get(n).ok())
                .or_else(|| states.iter().next().map(|(_, v)| v));
            match picked.and_then(|o| deref(doc, o)) {
                Some(Object::Stream(s)) => s,
                _ => return,
            }
        }
        _ => return,
    };

    let bbox = normal
        .dict
        .get(b"BBox")
        .ok()
        .and_then(|o| read_rect(doc, o))
        .unwrap_or([0.0, 0.0, 1.0, 1.0]);
    let matrix = normal
        .dict
        .get(b"Matrix")
        .ok()
        .and_then(read_matrix_obj)
        .unwrap_or(IDENTITY);
    let res = normal
        .dict
        .get(b"Resources")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .cloned();

    let bytes = stream_data(normal);
    let ops = match Content::decode(&bytes) {
        Ok(c) => c.operations,
        Err(_) => return,
    };

    let mut gs = GraphicsState::default();
    gs.ctm = mat_mul(&appearance_matrix(rect, bbox, matrix), base);
    let start = prims.len();
    interpret_content(doc, &ops, res.as_ref(), gs, prims, 1, false);

    // Honor the annotation's constant opacity (/CA) over its rendered prims.
    let ca = dict.get(b"CA").ok().and_then(num).unwrap_or(1.0);
    if ca < 1.0 {
        for p in prims[start..].iter_mut() {
            scale_prim_alpha(p, ca);
        }
    }
}

// ---------------------------------------------------------------------------
// Editing: annotations, form filling, and save (lopdf write-back)
// ---------------------------------------------------------------------------
//
// The "safe" viewer edits via an overlay model written back through lopdf: new
// content is added as annotations (with generated appearance streams so other
// viewers render them) or as AcroForm field values. Existing body-text glyph
// runs are not editable in this architecture.

/// Encode a lopdf `ObjectId` (num, gen) into a single `i64` handle for Kotlin.
fn encode_id(id: ObjectId) -> i64 {
    ((id.0 as i64) << 16) | (id.1 as i64)
}

fn decode_id(v: i64) -> ObjectId {
    (((v >> 16) & 0xFFFF_FFFF) as u32, (v & 0xFFFF) as u16)
}

fn nth_page_id(doc: &Document, index: i32) -> Option<ObjectId> {
    doc.get_pages().get(&((index as u32) + 1)).copied()
}

fn name_obj(s: &str) -> Object {
    Object::Name(s.as_bytes().to_vec())
}

fn rect_obj(r: [f64; 4]) -> Object {
    Object::Array(vec![r[0].into(), r[1].into(), r[2].into(), r[3].into()])
}

fn argb_rgb(argb: u32) -> (f64, f64, f64) {
    (
        ((argb >> 16) & 0xFF) as f64 / 255.0,
        ((argb >> 8) & 0xFF) as f64 / 255.0,
        (argb & 0xFF) as f64 / 255.0,
    )
}

fn normalize_rect(r: [f64; 4]) -> [f64; 4] {
    [
        r[0].min(r[2]),
        r[1].min(r[3]),
        r[0].max(r[2]),
        r[1].max(r[3]),
    ]
}

/// Escape a string for use inside a PDF content-stream literal `(...)`.
fn escape_pdf_literal(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '(' => out.push_str("\\("),
            ')' => out.push_str("\\)"),
            '\\' => out.push_str("\\\\"),
            _ => out.push(c),
        }
    }
    out
}

/// Decode a PDF text string (`/Contents`, `/V`): UTF-16BE if it has a BOM, else
/// treated as Latin-1 (a superset-safe approximation of PDFDocEncoding).
fn decode_pdf_text(bytes: &[u8]) -> String {
    if bytes.len() >= 2 && bytes[0] == 0xFE && bytes[1] == 0xFF {
        let units: Vec<u16> = bytes[2..]
            .chunks(2)
            .map(|c| ((c[0] as u16) << 8) | *c.get(1).unwrap_or(&0) as u16)
            .collect();
        String::from_utf16_lossy(&units)
    } else {
        bytes.iter().map(|&b| b as char).collect()
    }
}

/// Resources dictionary with a single Helvetica font under `/F1`.
fn helvetica_resources() -> Dictionary {
    let mut font = Dictionary::new();
    font.set("Type", name_obj("Font"));
    font.set("Subtype", name_obj("Type1"));
    font.set("BaseFont", name_obj("Helvetica"));
    let mut fonts = Dictionary::new();
    fonts.set("F1", Object::Dictionary(font));
    let mut res = Dictionary::new();
    res.set("Font", Object::Dictionary(fonts));
    res
}

/// Build a Form XObject appearance stream with the given BBox size, content and
/// resources, returning its object id.
fn make_appearance(doc: &mut Document, w: f64, h: f64, content: Vec<u8>, res: Dictionary) -> ObjectId {
    let mut d = Dictionary::new();
    d.set("Type", name_obj("XObject"));
    d.set("Subtype", name_obj("Form"));
    d.set("FormType", 1);
    d.set(
        "BBox",
        Object::Array(vec![0.into(), 0.into(), w.into(), h.into()]),
    );
    d.set("Resources", Object::Dictionary(res));
    doc.add_object(Stream::new(d, content))
}

/// Append an annotation reference to a page's `/Annots` array (creating it if
/// needed), handling both inline and indirect arrays.
fn append_annot(doc: &mut Document, page_id: ObjectId, annot_id: ObjectId) {
    let indirect = match doc.get_dictionary(page_id).ok().and_then(|d| d.get(b"Annots").ok()) {
        Some(Object::Reference(id)) => Some(*id),
        _ => None,
    };
    if let Some(arr_id) = indirect {
        if let Ok(Object::Array(a)) = doc.get_object_mut(arr_id) {
            a.push(Object::Reference(annot_id));
        }
        return;
    }
    if let Ok(page) = doc.get_dictionary_mut(page_id) {
        match page.get_mut(b"Annots") {
            Ok(Object::Array(a)) => a.push(Object::Reference(annot_id)),
            _ => page.set("Annots", Object::Array(vec![Object::Reference(annot_id)])),
        }
    }
}

/// Attach `Rect` + `AP /N` to an annotation dict.
fn set_appearance(annot: &mut Dictionary, rect: [f64; 4], ap_id: ObjectId) {
    annot.set("Rect", rect_obj(rect));
    let mut ap = Dictionary::new();
    ap.set("N", Object::Reference(ap_id));
    annot.set("AP", Object::Dictionary(ap));
}

fn add_annotation_object(doc: &mut Document, page_index: i32, annot: Dictionary) -> Option<i64> {
    let page_id = nth_page_id(doc, page_index)?;
    let annot_id = doc.add_object(annot);
    append_annot(doc, page_id, annot_id);
    Some(encode_id(annot_id))
}

/// Content stream drawing a (possibly multi-line) text block in a `w`×`h` box.
fn free_text_content(w: f64, h: f64, text: &str, argb: u32, size: f64) -> Vec<u8> {
    let (r, g, b) = argb_rgb(argb);
    let leading = size * 1.2;
    let mut c = format!(
        "q {r:.3} {g:.3} {b:.3} rg BT /F1 {size} Tf {leading} TL {x} {y} Td",
        x = 2.0,
        y = h - size,
    );
    let _ = w;
    for line in text.split('\n') {
        c.push_str(&format!(" ({}) Tj T*", escape_pdf_literal(line)));
    }
    c.push_str(" ET Q");
    c.into_bytes()
}

fn add_free_text(
    handle: i64,
    page_index: i32,
    rect: [f64; 4],
    argb: u32,
    size: f64,
    text: &str,
) -> Option<i64> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let content = free_text_content(w, h, text, argb, size);
    let ap_id = make_appearance(doc, w, h, content, helvetica_resources());
    let (cr, cg, cb) = argb_rgb(argb);

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("FreeText"));
    annot.set("Contents", Object::string_literal(text));
    annot.set(
        "DA",
        Object::string_literal(format!("{cr:.3} {cg:.3} {cb:.3} rg /F1 {size} Tf")),
    );
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

fn add_highlight(handle: i64, page_index: i32, rect: [f64; 4], argb: u32) -> Option<i64> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let (cr, cg, cb) = argb_rgb(argb);
    // Multiply-blended translucent fill so underlying text shows through.
    let content = format!(
        "q /GS1 gs {cr:.3} {cg:.3} {cb:.3} rg 0 0 {w} {h} re f Q"
    )
    .into_bytes();
    let mut gs = Dictionary::new();
    gs.set("Type", name_obj("ExtGState"));
    gs.set("ca", Object::Real(0.4));
    gs.set("BM", name_obj("Multiply"));
    let mut gss = Dictionary::new();
    gss.set("GS1", Object::Dictionary(gs));
    let mut res = Dictionary::new();
    res.set("ExtGState", Object::Dictionary(gss));
    let ap_id = make_appearance(doc, w, h, content, res);

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Highlight"));
    annot.set(
        "QuadPoints",
        Object::Array(vec![
            r[0].into(), r[3].into(), r[2].into(), r[3].into(),
            r[0].into(), r[1].into(), r[2].into(), r[1].into(),
        ]),
    );
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a text-markup annotation over `rect`. kind: 0 Underline, 1 StrikeOut, 2 Squiggly.
fn add_text_markup(handle: i64, page_index: i32, rect: [f64; 4], argb: u32, kind: i32) -> Option<i64> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = (h * 0.06).clamp(0.8, 3.0);
    let content = match kind {
        1 => {
            let y = h / 2.0;
            format!("q {lw} w {cr:.3} {cg:.3} {cb:.3} RG 0 {y:.2} m {w:.2} {y:.2} l S Q")
        }
        2 => {
            let base = h * 0.12;
            let amp = (h * 0.08).clamp(1.0, 4.0);
            let step = (amp * 2.0).max(3.0);
            let mut c = format!("q {lw} w {cr:.3} {cg:.3} {cb:.3} RG 0 {base:.2} m ");
            let mut x = 0.0;
            let mut up = true;
            while x < w {
                let nx = (x + step).min(w);
                let y = if up { base + amp } else { base };
                c.push_str(&format!("{nx:.2} {y:.2} l "));
                x = nx;
                up = !up;
            }
            c.push_str("S Q");
            c
        }
        _ => {
            let y = h * 0.10;
            format!("q {lw} w {cr:.3} {cg:.3} {cb:.3} RG 0 {y:.2} m {w:.2} {y:.2} l S Q")
        }
    }
    .into_bytes();
    let ap_id = make_appearance(doc, w, h, content, Dictionary::new());
    let subtype = match kind {
        1 => "StrikeOut",
        2 => "Squiggly",
        _ => "Underline",
    };
    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj(subtype));
    annot.set(
        "QuadPoints",
        Object::Array(vec![
            r[0].into(), r[3].into(), r[2].into(), r[3].into(),
            r[0].into(), r[1].into(), r[2].into(), r[1].into(),
        ]),
    );
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a sticky-note (Text) annotation at editor point (x,y) with `text`.
fn add_note(handle: i64, page_index: i32, x: f64, y: f64, argb: u32, text: &str) -> Option<i64> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let binv = page_base_inverse(doc, page_index);
    let (px, py) = transform(&binv, x, y);
    let s = 20.0;
    let r = normalize_rect([px, py - s, px + s, py]);
    let (cr, cg, cb) = argb_rgb(argb);
    let content = format!(
        "q {cr:.3} {cg:.3} {cb:.3} rg 1 1 {w:.1} {h:.1} re f 1 1 1 rg 4 5 12 2 re f 4 9 12 2 re f 4 13 8 2 re f Q",
        w = s - 2.0,
        h = s - 2.0,
    )
    .into_bytes();
    let ap_id = make_appearance(doc, s, s, content, Dictionary::new());
    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Text"));
    annot.set("Name", name_obj("Note"));
    annot.set("Contents", Object::string_literal(text));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a FreeText callout: a leader line from anchor (ax,ay) to a text box near
/// (bx,by), all in editor coordinates.
fn add_callout(
    handle: i64,
    page_index: i32,
    ax: f64,
    ay: f64,
    bx: f64,
    by: f64,
    argb: u32,
    size: f64,
    text: &str,
) -> Option<i64> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let binv = page_base_inverse(doc, page_index);
    let (pax, pay) = transform(&binv, ax, ay);
    let (pbx, pby) = transform(&binv, bx, by);
    let bw = 160.0;
    let bh = (size * 1.6).max(24.0);
    let (box_x0, box_y1) = (pbx, pby);
    let box_y0 = pby - bh;
    let box_x1 = pbx + bw;
    let minx = pax.min(box_x0);
    let miny = pay.min(box_y0);
    let maxx = pax.max(box_x1);
    let maxy = pay.max(box_y1);
    let r = [minx, miny, maxx, maxy];
    let (w, h) = (maxx - minx, maxy - miny);
    let (cr, cg, cb) = argb_rgb(argb);
    let lax = pax - minx;
    let lay = pay - miny;
    let lx0 = box_x0 - minx;
    let ly0 = box_y0 - miny;
    let lx1 = box_x1 - minx;
    let ly1 = box_y1 - miny;
    let knee_y = (ly0 + ly1) / 2.0;
    let mut c = format!(
        "q 1 w {cr:.3} {cg:.3} {cb:.3} RG {lax:.2} {lay:.2} m {lx0:.2} {knee_y:.2} l S "
    );
    c.push_str(&format!(
        "{lx0:.2} {ly0:.2} {bw2:.2} {bh2:.2} re S ",
        bw2 = lx1 - lx0,
        bh2 = ly1 - ly0,
    ));
    c.push_str(&format!(
        "{cr:.3} {cg:.3} {cb:.3} rg BT /F1 {size} Tf {tx:.2} {ty:.2} Td ({t}) Tj ET Q",
        tx = lx0 + 4.0,
        ty = ly1 - size - 2.0,
        t = escape_pdf_literal(text),
    ));
    let ap_id = make_appearance(doc, w, h, c.into_bytes(), helvetica_resources());
    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("FreeText"));
    annot.set("IT", name_obj("FreeTextCallout"));
    annot.set("Contents", Object::string_literal(text));
    annot.set(
        "DA",
        Object::string_literal(format!("{cr:.3} {cg:.3} {cb:.3} rg /F1 {size} Tf")),
    );
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a redaction annotation: an opaque black filled rectangle marked so that
/// `apply_redactions` can permanently remove the content beneath it.
fn add_redaction(handle: i64, page_index: i32, rect: [f64; 4]) -> Option<i64> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let content = format!("q 0 0 0 rg 0 0 {w} {h} re f Q").into_bytes();
    let ap_id = make_appearance(doc, w, h, content, Dictionary::new());
    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Square"));
    annot.set("IC", Object::Array(vec![0.into(), 0.into(), 0.into()]));
    annot.set("PdfRedact", Object::Boolean(true));
    let mut bs = Dictionary::new();
    bs.set("W", Object::Real(0.0));
    annot.set("BS", Object::Dictionary(bs));
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

fn add_square(
    handle: i64,
    page_index: i32,
    rect: [f64; 4],
    argb: u32,
    line_width: f64,
    fill: bool,
) -> Option<i64> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = line_width.max(0.5);
    let content = if fill {
        format!("q {cr:.3} {cg:.3} {cb:.3} rg 0 0 {w} {h} re f Q")
    } else {
        format!(
            "q {lw} w {cr:.3} {cg:.3} {cb:.3} RG {x} {y} {rw} {rh} re S Q",
            x = lw / 2.0,
            y = lw / 2.0,
            rw = w - lw,
            rh = h - lw,
        )
    }
    .into_bytes();
    let ap_id = make_appearance(doc, w, h, content, Dictionary::new());

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Square"));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_shape_border(&mut annot, argb, lw, fill);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a Circle (ellipse) annotation inscribed in [rect], stroked or filled.
fn add_circle(
    handle: i64,
    page_index: i32,
    rect: [f64; 4],
    argb: u32,
    line_width: f64,
    fill: bool,
) -> Option<i64> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = line_width.max(0.5);

    // Ellipse inscribed in the BBox
    // approximated by four cubic Bézier arcs.
    let inset = if fill { 0.0 } else { lw / 2.0 };
    let cx = w / 2.0;
    let cy = h / 2.0;
    let rx = (w / 2.0 - inset).max(0.0);
    let ry = (h / 2.0 - inset).max(0.0);
    let k = 0.552_284_75_f64; // 4/3 * (sqrt(2) - 1)
    let ox = rx * k;
    let oy = ry * k;

    let mut c = String::from("q ");
    if fill {
        c.push_str(&format!("{cr:.3} {cg:.3} {cb:.3} rg "));
    } else {
        c.push_str(&format!("{lw} w {cr:.3} {cg:.3} {cb:.3} RG "));
    }
    c.push_str(&format!("{:.2} {:.2} m ", cx + rx, cy));
    c.push_str(&format!(
        "{:.2} {:.2} {:.2} {:.2} {:.2} {:.2} c ",
        cx + rx, cy + oy, cx + ox, cy + ry, cx, cy + ry,
    ));
    c.push_str(&format!(
        "{:.2} {:.2} {:.2} {:.2} {:.2} {:.2} c ",
        cx - ox, cy + ry, cx - rx, cy + oy, cx - rx, cy,
    ));
    c.push_str(&format!(
        "{:.2} {:.2} {:.2} {:.2} {:.2} {:.2} c ",
        cx - rx, cy - oy, cx - ox, cy - ry, cx, cy - ry,
    ));
    c.push_str(&format!(
        "{:.2} {:.2} {:.2} {:.2} {:.2} {:.2} c ",
        cx + ox, cy - ry, cx + rx, cy - oy, cx + rx, cy,
    ));
    c.push_str(if fill { "f Q" } else { "S Q" });
    let ap_id = make_appearance(doc, w, h, c.into_bytes(), Dictionary::new());

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Circle"));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_shape_border(&mut annot, argb, lw, fill);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Set annotation constant opacity (`/CA`, `/ca`) from the alpha byte of `argb`.
fn set_alpha(annot: &mut Dictionary, argb: u32) {
    let a = ((argb >> 24) & 0xFF) as f64 / 255.0;
    if a < 1.0 {
        annot.set("CA", Object::Real(a as f32));
        annot.set("ca", Object::Real(a as f32));
    }
}

/// Set `/BS` (border) and, for filled shapes, `/IC` (interior color) on a
/// Square/Circle annotation. Filled shapes carry a zero-width border.
fn set_shape_border(annot: &mut Dictionary, argb: u32, line_width: f64, fill: bool) {
    let (cr, cg, cb) = argb_rgb(argb);
    let mut bs = Dictionary::new();
    if fill {
        annot.set("IC", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
        bs.set("W", Object::Real(0.0));
    } else {
        bs.set("W", Object::Real(line_width as f32));
    }
    annot.set("BS", Object::Dictionary(bs));
    set_alpha(annot, argb);
}

/// Add a Polygon (when `closed`) or PolyLine (open) annotation from flat
/// page-space x,y `points`. Closed polygons may be filled; open polylines are
/// always stroked. Used for triangles, stars, arrows, lines, polylines and
/// flattened Bézier curves.
fn add_poly(
    handle: i64,
    page_index: i32,
    points: &[f32],
    argb: u32,
    line_width: f64,
    fill: bool,
    closed: bool,
) -> Option<i64> {
    if points.len() < 4 {
        return None;
    }
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let converted = page_points(doc, page_index, points);
    let points = converted.as_slice();
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = line_width.max(0.5);
    let do_fill = fill && closed;

    let mut minx = f64::INFINITY;
    let mut miny = f64::INFINITY;
    let mut maxx = f64::NEG_INFINITY;
    let mut maxy = f64::NEG_INFINITY;
    let mut i = 0;
    while i + 1 < points.len() {
        let (x, y) = (points[i] as f64, points[i + 1] as f64);
        minx = minx.min(x);
        miny = miny.min(y);
        maxx = maxx.max(x);
        maxy = maxy.max(y);
        i += 2;
    }
    let pad = lw + 2.0;
    let rect = [minx - pad, miny - pad, maxx + pad, maxy + pad];
    let (w, h) = (rect[2] - rect[0], rect[3] - rect[1]);

    let mut c = String::from("q ");
    if do_fill {
        c.push_str(&format!("{cr:.3} {cg:.3} {cb:.3} rg "));
    } else {
        c.push_str(&format!("{lw} w {cr:.3} {cg:.3} {cb:.3} RG "));
    }
    let mut verts = Vec::new();
    let mut j = 0;
    let mut first = true;
    while j + 1 < points.len() {
        let px = points[j] as f64;
        let py = points[j + 1] as f64;
        verts.push(px.into());
        verts.push(py.into());
        let (lx, ly) = (px - rect[0], py - rect[1]);
        if first {
            c.push_str(&format!("{lx:.2} {ly:.2} m "));
            first = false;
        } else {
            c.push_str(&format!("{lx:.2} {ly:.2} l "));
        }
        j += 2;
    }
    if closed {
        c.push_str(if do_fill { "h f Q" } else { "h S Q" });
    } else {
        c.push_str("S Q");
    }
    let ap_id = make_appearance(doc, w, h, c.into_bytes(), Dictionary::new());

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj(if closed { "Polygon" } else { "PolyLine" }));
    annot.set("Vertices", Object::Array(verts));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    if do_fill {
        annot.set("IC", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    }
    let mut bs = Dictionary::new();
    bs.set("W", Object::Real(if do_fill { 0.0 } else { lw as f32 }));
    annot.set("BS", Object::Dictionary(bs));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, normalize_rect(rect), ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// `points`: flat page-space x,y pairs of a single ink stroke.
fn add_ink(
    handle: i64,
    page_index: i32,
    argb: u32,
    line_width: f64,
    points: &[f32],
) -> Option<i64> {
    if points.len() < 4 {
        return None;
    }
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let converted = page_points(doc, page_index, points);
    let points = converted.as_slice();
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = line_width.max(0.5);

    let mut minx = f64::INFINITY;
    let mut miny = f64::INFINITY;
    let mut maxx = f64::NEG_INFINITY;
    let mut maxy = f64::NEG_INFINITY;
    let mut i = 0;
    while i + 1 < points.len() {
        let (x, y) = (points[i] as f64, points[i + 1] as f64);
        minx = minx.min(x);
        miny = miny.min(y);
        maxx = maxx.max(x);
        maxy = maxy.max(y);
        i += 2;
    }
    let pad = lw + 2.0;
    let rect = [minx - pad, miny - pad, maxx + pad, maxy + pad];
    let (w, h) = (rect[2] - rect[0], rect[3] - rect[1]);

    // Appearance content in BBox space (origin at rect min).
    let mut c = format!("q {lw} w {cr:.3} {cg:.3} {cb:.3} RG ");
    let mut ink = Vec::new();
    let mut j = 0;
    let mut first = true;
    while j + 1 < points.len() {
        let px = points[j] as f64;
        let py = points[j + 1] as f64;
        ink.push(px.into());
        ink.push(py.into());
        let (lx, ly) = (px - rect[0], py - rect[1]);
        if first {
            c.push_str(&format!("{lx:.2} {ly:.2} m "));
            first = false;
        } else {
            c.push_str(&format!("{lx:.2} {ly:.2} l "));
        }
        j += 2;
    }
    c.push_str("S Q");
    let ap_id = make_appearance(doc, w, h, c.into_bytes(), Dictionary::new());

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Ink"));
    annot.set("InkList", Object::Array(vec![Object::Array(ink)]));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    let mut bs = Dictionary::new();
    bs.set("W", Object::Real(lw as f32));
    annot.set("BS", Object::Dictionary(bs));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, rect, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// `jpeg`: raw JPEG bytes for a Stamp annotation image.
fn add_stamp(
    handle: i64,
    page_index: i32,
    rect: [f64; 4],
    img_w: u32,
    img_h: u32,
    jpeg: &[u8],
) -> Option<i64> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);

    let mut img_dict = Dictionary::new();
    img_dict.set("Type", name_obj("XObject"));
    img_dict.set("Subtype", name_obj("Image"));
    img_dict.set("Width", Object::Integer(img_w as i64));
    img_dict.set("Height", Object::Integer(img_h as i64));
    img_dict.set("BitsPerComponent", Object::Integer(8));
    img_dict.set("ColorSpace", name_obj("DeviceRGB"));
    img_dict.set("Filter", name_obj("DCTDecode"));
    let img_id = doc.add_object(Stream::new(img_dict, jpeg.to_vec()));

    let mut xobj = Dictionary::new();
    xobj.set("Im0", Object::Reference(img_id));
    let mut res = Dictionary::new();
    res.set("XObject", Object::Dictionary(xobj));
    let content = format!("q {w} 0 0 {h} 0 0 cm /Im0 Do Q").into_bytes();
    let ap_id = make_appearance(doc, w, h, content, res);

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Stamp"));
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

fn update_annotation_rect(handle: i64, page_index: i32, annot_id: i64, rect: [f64; 4]) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let pr = page_rect(doc, page_index, rect);
    let id = decode_id(annot_id);
    if let Ok(dict) = doc.get_dictionary_mut(id) {
        dict.set("Rect", rect_obj(pr));
        true
    } else {
        false
    }
}

fn update_free_text(handle: i64, annot_id: i64, text: &str) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(annot_id);
    // Read existing rect / color / size.
    let (rect, argb, size) = {
        let dict = match doc.get_dictionary(id) {
            Ok(d) => d,
            Err(_) => return false,
        };
        let rect = dict
            .get(b"Rect")
            .ok()
            .and_then(|o| read_rect(doc, o))
            .map(normalize_rect)
            .unwrap_or([0.0, 0.0, 100.0, 20.0]);
        let argb = dict
            .get(b"C")
            .ok()
            .and_then(|o| o.as_array().ok())
            .filter(|a| a.len() == 3)
            .map(|a| {
                let r = a[0].as_float().unwrap_or(0.0);
                let g = a[1].as_float().unwrap_or(0.0);
                let b = a[2].as_float().unwrap_or(0.0);
                rgb_to_argb(r as f64, g as f64, b as f64)
            })
            .unwrap_or(0xFF00_0000);
        let size = dict
            .get(b"DA")
            .ok()
            .and_then(|o| o.as_str().ok())
            .and_then(|s| parse_da_size(s))
            .unwrap_or(12.0);
        (rect, argb, size)
    };
    let (w, h) = (rect[2] - rect[0], rect[3] - rect[1]);
    let content = free_text_content(w, h, text, argb, size);
    let ap_id = make_appearance(doc, w, h, content, helvetica_resources());
    if let Ok(dict) = doc.get_dictionary_mut(id) {
        dict.set("Contents", Object::string_literal(text));
        let mut ap = Dictionary::new();
        ap.set("N", Object::Reference(ap_id));
        dict.set("AP", Object::Dictionary(ap));
        true
    } else {
        false
    }
}

/// Extract the font size preceding `Tf` in a `/DA` string.
fn parse_da_size(da: &[u8]) -> Option<f64> {
    let s = String::from_utf8_lossy(da);
    let toks: Vec<&str> = s.split_whitespace().collect();
    let tf = toks.iter().position(|t| *t == "Tf")?;
    if tf == 0 {
        return None;
    }
    toks[tf - 1].parse::<f64>().ok()
}

/// Remove an annotation reference from a page's `/Annots` (inline or indirect).
/// Returns whether a reference was actually removed. Does NOT delete the object.
fn remove_annot_ref(doc: &mut Document, page_id: ObjectId, id: ObjectId) -> bool {
    let indirect = match doc.get_dictionary(page_id).ok().and_then(|d| d.get(b"Annots").ok()) {
        Some(Object::Reference(aid)) => Some(*aid),
        _ => None,
    };
    if let Some(arr_id) = indirect {
        if let Ok(Object::Array(a)) = doc.get_object_mut(arr_id) {
            let before = a.len();
            a.retain(|o| o.as_reference().ok() != Some(id));
            return before != a.len();
        }
        return false;
    }
    if let Ok(page) = doc.get_dictionary_mut(page_id) {
        if let Ok(Object::Array(a)) = page.get_mut(b"Annots") {
            let before = a.len();
            a.retain(|o| o.as_reference().ok() != Some(id));
            return before != a.len();
        }
    }
    false
}

fn delete_annotation(handle: i64, page_index: i32, annot_id: i64) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(annot_id);
    let page_id = match nth_page_id(doc, page_index) {
        Some(p) => p,
        None => return false,
    };
    let removed = remove_annot_ref(doc, page_id, id);
    doc.objects.remove(&id);
    removed
}

/// Detach an annotation (remove its page reference) but keep the object, so it
/// can be re-attached for undo/redo.
fn detach_annotation(handle: i64, page_index: i32, annot_id: i64) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(annot_id);
    let page_id = match nth_page_id(doc, page_index) {
        Some(p) => p,
        None => return false,
    };
    remove_annot_ref(doc, page_id, id)
}

/// Re-attach a previously detached annotation to its page.
fn reattach_annotation(handle: i64, page_index: i32, annot_id: i64) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(annot_id);
    if !doc.objects.contains_key(&id) {
        return false;
    }
    let page_id = match nth_page_id(doc, page_index) {
        Some(p) => p,
        None => return false,
    };
    append_annot(doc, page_id, id);
    true
}

/// Offset alternating x,y numbers of a flat array in place by (dx, dy).
fn offset_flat(arr: &mut [Object], dx: f64, dy: f64) {
    for (i, o) in arr.iter_mut().enumerate() {
        if let Some(n) = num(o) {
            let d = if i % 2 == 0 { dx } else { dy };
            *o = Object::Real((n + d) as f32);
        }
    }
}

/// Duplicate an annotation, shifting its geometry by (dx, dy) page-space units.
/// The copy shares the (immutable) appearance stream. Returns the new id, or 0.
fn duplicate_annotation(handle: i64, page_index: i32, annot_id: i64, dx: f64, dy: f64) -> i64 {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return 0,
    };
    let id = decode_id(annot_id);
    let mut dict = match doc.get_dictionary(id) {
        Ok(d) => d.clone(),
        Err(_) => return 0,
    };
    for key in [b"Rect".as_ref(), b"Vertices", b"QuadPoints", b"L"] {
        if let Ok(Object::Array(a)) = dict.get(key) {
            let mut a2 = a.clone();
            offset_flat(&mut a2, dx, dy);
            dict.set(key.to_vec(), Object::Array(a2));
        }
    }
    if let Ok(Object::Array(lists)) = dict.get(b"InkList") {
        let mut out = Vec::with_capacity(lists.len());
        for l in lists {
            if let Object::Array(pts) = l {
                let mut p2 = pts.clone();
                offset_flat(&mut p2, dx, dy);
                out.push(Object::Array(p2));
            } else {
                out.push(l.clone());
            }
        }
        dict.set("InkList", Object::Array(out));
    }
    let new_id = doc.add_object(dict);
    let page_id = match nth_page_id(doc, page_index) {
        Some(p) => p,
        None => return 0,
    };
    append_annot(doc, page_id, new_id);
    encode_id(new_id)
}

// --- Serialized listing for the UI ---------------------------------------

fn subtype_code(subtype: &[u8]) -> u8 {
    match subtype {
        b"FreeText" => 1,
        b"Highlight" => 2,
        b"Square" => 3,
        b"Ink" => 4,
        b"Stamp" => 5,
        b"Widget" => 6,
        b"Text" => 7,
        b"Line" => 8,
        b"Circle" => 9,
        b"Polygon" => 10,
        b"PolyLine" => 11,
        _ => 0,
    }
}

fn annot_color(doc: &Document, dict: &Dictionary) -> u32 {
    dict.get(b"C")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_array().ok())
        .filter(|a| a.len() == 3)
        .map(|a| {
            rgb_to_argb(
                a[0].as_float().unwrap_or(0.0) as f64,
                a[1].as_float().unwrap_or(0.0) as f64,
                a[2].as_float().unwrap_or(0.0) as f64,
            )
        })
        .unwrap_or(0xFF00_0000)
}

fn list_annotations(handle: i64, page_index: i32) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap();
    let doc = reg.get(&handle)?;
    let page_id = nth_page_id(doc, page_index)?;
    let base = page_base_matrix(doc, page_id);

    let mut records: Vec<(i64, u8, [f64; 4], u32, String)> = Vec::new();
    if let Some(Object::Array(annots)) = doc
        .get_dictionary(page_id)
        .ok()
        .and_then(|d| d.get(b"Annots").ok())
        .and_then(|o| deref(doc, o))
    {
        for a in annots {
            let id = match a.as_reference() {
                Ok(id) => id,
                Err(_) => continue,
            };
            let dict = match doc.get_dictionary(id) {
                Ok(d) => d,
                Err(_) => continue,
            };
            let subtype = dict.get(b"Subtype").ok().and_then(|o| o.as_name().ok());
            let code = subtype.map(subtype_code).unwrap_or(0);
            // Report rects in displayed space so the editor's hit-testing and
            // selection boxes line up with the (rotation-baked) render.
            let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
                Some(r) => {
                    let n = normalize_rect(r);
                    let (dx0, dy0) = transform(&base, n[0], n[1]);
                    let (dx1, dy1) = transform(&base, n[2], n[3]);
                    normalize_rect([dx0, dy0, dx1, dy1])
                }
                None => continue,
            };
            let color = annot_color(doc, dict);
            let contents = dict
                .get(b"Contents")
                .ok()
                .and_then(|o| o.as_str().ok())
                .map(decode_pdf_text)
                .unwrap_or_default();
            records.push((encode_id(id), code, rect, color, contents));
        }
    }

    let mut buf = Vec::new();
    buf.extend_from_slice(&(records.len() as u32).to_le_bytes());
    for (id, code, rect, color, contents) in records {
        buf.extend_from_slice(&id.to_le_bytes());
        buf.push(code);
        for v in rect {
            buf.extend_from_slice(&(v as f32).to_le_bytes());
        }
        buf.extend_from_slice(&color.to_le_bytes());
        let b = contents.as_bytes();
        let len = b.len().min(u16::MAX as usize);
        buf.extend_from_slice(&(len as u16).to_le_bytes());
        buf.extend_from_slice(&b[..len]);
    }
    Some(buf)
}

/// Field type + name inherited through the widget's `/Parent` chain.
fn field_attr<'a>(doc: &'a Document, mut id: ObjectId, key: &[u8]) -> Option<&'a Object> {
    for _ in 0..16 {
        let dict = doc.get_dictionary(id).ok()?;
        if let Ok(v) = dict.get(key) {
            return Some(v);
        }
        id = dict.get(b"Parent").ok().and_then(|o| o.as_reference().ok())?;
    }
    None
}

/// Resolve a GoTo destination to a 0-based page index, or -1.
fn resolve_dest_page(doc: &Document, d: &Object, page_of: &HashMap<ObjectId, i32>) -> i32 {
    let d = deref(doc, d).unwrap_or(d);
    if let Object::Array(a) = d {
        if let Some(first) = a.first() {
            if let Ok(id) = first.as_reference() {
                return *page_of.get(&id).unwrap_or(&-1);
            }
        }
    }
    -1
}

/// Serialize link annotations for a page: rect (displayed space), destination
/// page (-1 if none), and URI (empty if none).
fn list_links(handle: i64, page_index: i32) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap();
    let doc = reg.get(&handle)?;
    let page_id = nth_page_id(doc, page_index)?;
    let base = page_base_matrix(doc, page_id);
    let mut page_of: HashMap<ObjectId, i32> = HashMap::new();
    for (n, id) in doc.get_pages() {
        page_of.insert(id, (n as i32) - 1);
    }
    let mut records: Vec<([f64; 4], i32, String)> = Vec::new();
    if let Some(Object::Array(annots)) = doc
        .get_dictionary(page_id)
        .ok()
        .and_then(|d| d.get(b"Annots").ok())
        .and_then(|o| deref(doc, o))
    {
        for a in annots {
            let dict = match a.as_reference().ok().and_then(|id| doc.get_dictionary(id).ok()) {
                Some(d) => d,
                None => continue,
            };
            if dict.get(b"Subtype").ok().and_then(|o| o.as_name().ok()) != Some(b"Link".as_ref()) {
                continue;
            }
            let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
                Some(r) => {
                    let n = normalize_rect(r);
                    let (x0, y0) = transform(&base, n[0], n[1]);
                    let (x1, y1) = transform(&base, n[2], n[3]);
                    normalize_rect([x0, y0, x1, y1])
                }
                None => continue,
            };
            let mut dest_page = -1i32;
            let mut uri = String::new();
            if let Some(action) = dict.get(b"A").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok()) {
                let s = action.get(b"S").ok().and_then(|o| o.as_name().ok());
                if s == Some(b"URI".as_ref()) {
                    if let Ok(u) = action.get(b"URI").and_then(|o| o.as_str()) {
                        uri = String::from_utf8_lossy(u).into_owned();
                    }
                } else if s == Some(b"GoTo".as_ref()) {
                    if let Ok(d) = action.get(b"D") {
                        dest_page = resolve_dest_page(doc, d, &page_of);
                    }
                }
            } else if let Ok(d) = dict.get(b"Dest") {
                dest_page = resolve_dest_page(doc, d, &page_of);
            }
            if dest_page >= 0 || !uri.is_empty() {
                records.push((rect, dest_page, uri));
            }
        }
    }
    let mut buf = Vec::new();
    buf.extend_from_slice(&(records.len() as u32).to_le_bytes());
    for (rect, dest, uri) in records {
        for v in rect {
            buf.extend_from_slice(&(v as f32).to_le_bytes());
        }
        buf.extend_from_slice(&dest.to_le_bytes());
        let b = uri.as_bytes();
        let len = b.len().min(u16::MAX as usize);
        buf.extend_from_slice(&(len as u16).to_le_bytes());
        buf.extend_from_slice(&b[..len]);
    }
    Some(buf)
}

fn list_form_fields(handle: i64, page_index: i32) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap();
    let doc = reg.get(&handle)?;
    let page_id = nth_page_id(doc, page_index)?;
    let base = page_base_matrix(doc, page_id);

    // (widgetId, typeCode, rect, name, value, checked)
    let mut fields: Vec<(i64, u8, [f64; 4], String, String, u8)> = Vec::new();
    if let Some(Object::Array(annots)) = doc
        .get_dictionary(page_id)
        .ok()
        .and_then(|d| d.get(b"Annots").ok())
        .and_then(|o| deref(doc, o))
    {
        for a in annots {
            let id = match a.as_reference() {
                Ok(id) => id,
                Err(_) => continue,
            };
            let dict = match doc.get_dictionary(id) {
                Ok(d) => d,
                Err(_) => continue,
            };
            let is_widget = dict.get(b"Subtype").ok().and_then(|o| o.as_name().ok())
                == Some(b"Widget".as_ref());
            let ft = field_attr(doc, id, b"FT").and_then(|o| o.as_name().ok());
            if !is_widget || ft.is_none() {
                continue;
            }
            let ft = ft.unwrap();
            let type_code = match ft {
                b"Tx" => 0u8,
                b"Btn" => 1u8,
                b"Ch" => 2u8,
                _ => 3u8,
            };
            let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
                Some(r) => {
                    let n = normalize_rect(r);
                    let (dx0, dy0) = transform(&base, n[0], n[1]);
                    let (dx1, dy1) = transform(&base, n[2], n[3]);
                    normalize_rect([dx0, dy0, dx1, dy1])
                }
                None => continue,
            };
            let name = field_attr(doc, id, b"T")
                .and_then(|o| o.as_str().ok())
                .map(decode_pdf_text)
                .unwrap_or_default();
            let value = field_attr(doc, id, b"V")
                .map(|o| match o {
                    Object::String(s, _) => decode_pdf_text(s),
                    Object::Name(n) => String::from_utf8_lossy(n).into_owned(),
                    _ => String::new(),
                })
                .unwrap_or_default();
            let checked = if type_code == 1 {
                let as_state = dict.get(b"AS").ok().and_then(|o| o.as_name().ok());
                let on = as_state.map(|s| s != b"Off").unwrap_or(false)
                    || (!value.is_empty() && value != "Off");
                on as u8
            } else {
                0
            };
            fields.push((encode_id(id), type_code, rect, name, value, checked));
        }
    }

    let mut buf = Vec::new();
    buf.extend_from_slice(&(fields.len() as u32).to_le_bytes());
    for (id, tc, rect, name, value, checked) in fields {
        buf.extend_from_slice(&id.to_le_bytes());
        buf.push(tc);
        for v in rect {
            buf.extend_from_slice(&(v as f32).to_le_bytes());
        }
        for s in [&name, &value] {
            let b = s.as_bytes();
            let len = b.len().min(u16::MAX as usize);
            buf.extend_from_slice(&(len as u16).to_le_bytes());
            buf.extend_from_slice(&b[..len]);
        }
        buf.push(checked);
    }
    Some(buf)
}

/// Set the AcroForm `/NeedAppearances` flag so conformant viewers regenerate
/// field appearances after a value change.
fn set_need_appearances(doc: &mut Document) {
    let acro_id = doc
        .catalog()
        .ok()
        .and_then(|c| c.get(b"AcroForm").ok())
        .and_then(|o| o.as_reference().ok());
    if let Some(id) = acro_id {
        if let Ok(af) = doc.get_dictionary_mut(id) {
            af.set("NeedAppearances", Object::Boolean(true));
        }
    }
}

fn set_text_field(handle: i64, widget_id: i64, value: &str) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(widget_id);
    let rect = doc
        .get_dictionary(id)
        .ok()
        .and_then(|d| d.get(b"Rect").ok())
        .and_then(|o| read_rect(doc, o))
        .map(normalize_rect);
    // Regenerate a simple left-aligned, vertically-centered appearance.
    let ap_id = rect.map(|r| {
        let (w, h) = (r[2] - r[0], r[3] - r[1]);
        let size = (h - 4.0).clamp(6.0, 14.0);
        let content = format!(
            "q 0 0 0 rg BT /F1 {size} Tf 2 {y:.2} Td ({}) Tj ET Q",
            escape_pdf_literal(value),
            y = (h - size) / 2.0,
        )
        .into_bytes();
        make_appearance(doc, w, h, content, helvetica_resources())
    });

    if let Ok(dict) = doc.get_dictionary_mut(id) {
        dict.set("V", Object::string_literal(value));
        if let Some(ap_id) = ap_id {
            let mut ap = Dictionary::new();
            ap.set("N", Object::Reference(ap_id));
            dict.set("AP", Object::Dictionary(ap));
        }
    } else {
        return false;
    }
    set_need_appearances(doc);
    true
}

fn set_checkbox(handle: i64, widget_id: i64, on: bool) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(widget_id);
    // Determine the "on" state name from the widget's /AP /N sub-dictionary.
    let on_state = doc
        .get_dictionary(id)
        .ok()
        .and_then(|d| d.get(b"AP").ok())
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|ap| ap.get(b"N").ok())
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|states| {
            states
                .iter()
                .map(|(k, _)| k.clone())
                .find(|k| k.as_slice() != b"Off")
        })
        .unwrap_or_else(|| b"Yes".to_vec());

    let state = if on { on_state } else { b"Off".to_vec() };
    if let Ok(dict) = doc.get_dictionary_mut(id) {
        dict.set("AS", Object::Name(state.clone()));
        dict.set("V", Object::Name(state));
        true
    } else {
        false
    }
}

/// Serialize `handle` with streams deflate-compressed and unused objects pruned.
fn save_compressed(handle: i64) -> Option<Vec<u8>> {
    let bytes = save_document(handle)?;
    let mut doc = Document::load_mem(&bytes).ok()?;
    doc.compress();
    doc.prune_objects();
    let mut out = Vec::new();
    doc.save_to(&mut out).ok()?;
    Some(out)
}

/// Ensure page `page_id` has an inline `/Resources /XObject` mapping `name` -> `xid`.
fn add_page_xobject(doc: &mut Document, page_id: ObjectId, name: &str, xid: ObjectId) {
    // Resolve to an inline Resources dict on the page (copying a referenced one).
    let res_inline = matches!(
        doc.get_dictionary(page_id).ok().and_then(|d| d.get(b"Resources").ok()),
        Some(Object::Dictionary(_))
    );
    if !res_inline {
        let copied = doc
            .get_dictionary(page_id)
            .ok()
            .and_then(|d| d.get(b"Resources").ok())
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
            .cloned()
            .unwrap_or_else(Dictionary::new);
        if let Ok(p) = doc.get_dictionary_mut(page_id) {
            p.set("Resources", Object::Dictionary(copied));
        }
    }
    if let Ok(p) = doc.get_dictionary_mut(page_id) {
        if let Ok(Object::Dictionary(res)) = p.get_mut(b"Resources") {
            let has_xo = matches!(res.get(b"XObject"), Ok(Object::Dictionary(_)));
            if !has_xo {
                res.set("XObject", Object::Dictionary(Dictionary::new()));
            }
            if let Ok(Object::Dictionary(xo)) = res.get_mut(b"XObject") {
                xo.set(name, Object::Reference(xid));
            }
        }
    }
}

/// Append `content_id` (a content stream) to page `page_id`'s `/Contents`.
fn append_content(doc: &mut Document, page_id: ObjectId, content_id: ObjectId) {
    let current = doc.get_dictionary(page_id).ok().and_then(|d| d.get(b"Contents").ok()).cloned();
    let new_contents = match current {
        Some(Object::Reference(r)) => Object::Array(vec![Object::Reference(r), Object::Reference(content_id)]),
        Some(Object::Array(mut a)) => {
            a.push(Object::Reference(content_id));
            Object::Array(a)
        }
        _ => Object::Array(vec![Object::Reference(content_id)]),
    };
    if let Ok(p) = doc.get_dictionary_mut(page_id) {
        p.set("Contents", new_contents);
    }
}

/// Flatten every annotation's appearance into its page content stream, then drop
/// the annotations. Makes overlays (incl. redaction boxes) permanent.
fn flatten_document(handle: i64) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let page_ids: Vec<ObjectId> = doc.get_pages().values().copied().collect();
    for page_id in page_ids {
        // Collect (xobject name, appearance id, placement matrix) for each annot.
        let annot_ids: Vec<ObjectId> = match doc
            .get_dictionary(page_id)
            .ok()
            .and_then(|d| d.get(b"Annots").ok())
            .and_then(|o| deref(doc, o))
        {
            Some(Object::Array(a)) => a.iter().filter_map(|o| o.as_reference().ok()).collect(),
            _ => continue,
        };
        if annot_ids.is_empty() {
            continue;
        }
        let mut placements: Vec<(String, ObjectId, Mat)> = Vec::new();
        for (i, aid) in annot_ids.iter().enumerate() {
            let dict = match doc.get_dictionary(*aid) {
                Ok(d) => d,
                Err(_) => continue,
            };
            let flags = dict.get(b"F").ok().and_then(num).unwrap_or(0.0) as i64;
            if flags & 0b10 != 0 {
                continue;
            }
            let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
                Some(r) => r,
                None => continue,
            };
            let ap_id = match dict.get(b"AP").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok())
                .and_then(|ap| ap.get(b"N").ok())
                .and_then(|n| n.as_reference().ok())
            {
                Some(id) => id,
                None => continue,
            };
            let (bbox, matrix) = match doc.get_object(ap_id).ok().and_then(|o| o.as_stream().ok()) {
                Some(s) => {
                    let bbox = s.dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)).unwrap_or([0.0, 0.0, 1.0, 1.0]);
                    let matrix = s.dict.get(b"Matrix").ok().and_then(read_matrix_obj).unwrap_or(IDENTITY);
                    (bbox, matrix)
                }
                None => continue,
            };
            let m = appearance_matrix(rect, bbox, matrix);
            placements.push((format!("Fl{}_{}", page_id.0, i), ap_id, m));
        }
        if placements.is_empty() {
            continue;
        }
        let mut content = String::new();
        for (name, _, m) in &placements {
            content.push_str(&format!(
                "q {:.4} {:.4} {:.4} {:.4} {:.4} {:.4} cm /{} Do Q ",
                m[0], m[1], m[2], m[3], m[4], m[5], name
            ));
        }
        let cid = doc.add_object(Stream::new(dictionary! {}, content.into_bytes()));
        append_content(doc, page_id, cid);
        for (name, ap_id, _) in &placements {
            add_page_xobject(doc, page_id, name, *ap_id);
        }
        if let Ok(p) = doc.get_dictionary_mut(page_id) {
            p.remove(b"Annots");
        }
    }
    true
}

/// Approximate per-string text length for advance estimation (byte count).
fn approx_text_len(op: &lopdf::content::Operation) -> f64 {
    if op.operator == "TJ" {
        if let Some(Object::Array(a)) = op.operands.first() {
            return a
                .iter()
                .map(|o| if let Object::String(s, _) = o { s.len() as f64 } else { 0.0 })
                .sum();
        }
        return 0.0;
    }
    op.operands
        .iter()
        .rev()
        .find_map(|o| if let Object::String(s, _) = o { Some(s.len() as f64) } else { None })
        .unwrap_or(0.0)
}

/// Rewrite a page's operator list, dropping text-show operators whose origin
/// falls within any redaction `rects` (page space). Heuristic advance tracking.
fn redact_operations(
    ops: Vec<lopdf::content::Operation>,
    rects: &[[f64; 4]],
) -> Vec<lopdf::content::Operation> {
    let mut out: Vec<lopdf::content::Operation> = Vec::with_capacity(ops.len());
    let mut ctm_stack: Vec<Mat> = Vec::new();
    let mut ctm = IDENTITY;
    let mut tm = IDENTITY;
    let mut lm = IDENTITY;
    let mut font_size = 0.0f64;
    let mut leading = 0.0f64;
    let mut char_spacing = 0.0f64;
    let mut h_scale = 1.0f64;
    let n = |o: Option<&Object>| o.and_then(num).unwrap_or(0.0);
    for op in ops {
        let operands = &op.operands;
        match op.operator.as_str() {
            "q" => ctm_stack.push(ctm),
            "Q" => {
                if let Some(m) = ctm_stack.pop() {
                    ctm = m;
                }
            }
            "cm" if operands.len() >= 6 => {
                let m = [
                    n(operands.first()), n(operands.get(1)), n(operands.get(2)),
                    n(operands.get(3)), n(operands.get(4)), n(operands.get(5)),
                ];
                ctm = mat_mul(&m, &ctm);
            }
            "BT" => {
                tm = IDENTITY;
                lm = IDENTITY;
            }
            "Tf" if operands.len() >= 2 => font_size = n(operands.get(1)),
            "TL" => leading = n(operands.first()),
            "Tc" => char_spacing = n(operands.first()),
            "Tz" => h_scale = n(operands.first()) / 100.0,
            "Tm" if operands.len() >= 6 => {
                let m = [
                    n(operands.first()), n(operands.get(1)), n(operands.get(2)),
                    n(operands.get(3)), n(operands.get(4)), n(operands.get(5)),
                ];
                tm = m;
                lm = m;
            }
            "Td" if operands.len() >= 2 => {
                lm = mat_mul(&translate(n(operands.first()), n(operands.get(1))), &lm);
                tm = lm;
            }
            "TD" if operands.len() >= 2 => {
                leading = -n(operands.get(1));
                lm = mat_mul(&translate(n(operands.first()), n(operands.get(1))), &lm);
                tm = lm;
            }
            "T*" => {
                lm = mat_mul(&translate(0.0, -leading), &lm);
                tm = lm;
            }
            "Tj" | "'" | "\"" | "TJ" => {
                if op.operator == "'" || op.operator == "\"" {
                    lm = mat_mul(&translate(0.0, -leading), &lm);
                    tm = lm;
                }
                let trm = mat_mul(&tm, &ctm);
                let (x, y) = (trm[4], trm[5]);
                let hit = rects.iter().any(|r| {
                    x >= r[0] - 1.0 && x <= r[2] + 1.0 && y >= r[1] - 2.0 && y <= r[3] + font_size + 2.0
                });
                let len = approx_text_len(&op);
                let adv = len * font_size * 0.5 * h_scale + len * char_spacing;
                if !hit {
                    out.push(op);
                }
                tm = mat_mul(&translate(adv, 0.0), &tm);
                continue;
            }
            _ => {}
        }
        out.push(op);
    }
    out
}

/// Permanently remove content under redaction annotations, cover with black, and
/// delete the annotations. Returns whether any redaction was applied.
/// Whether the document has any redaction annotations pending.
fn has_redactions(handle: i64) -> bool {
    let reg = registry().lock().unwrap();
    let doc = match reg.get(&handle) {
        Some(d) => d,
        None => return false,
    };
    for page_id in doc.get_pages().values().copied() {
        if let Some(Object::Array(annots)) = doc
            .get_dictionary(page_id)
            .ok()
            .and_then(|d| d.get(b"Annots").ok())
            .and_then(|o| deref(doc, o))
        {
            for a in annots {
                if let Some(dict) = a.as_reference().ok().and_then(|id| doc.get_dictionary(id).ok()) {
                    if matches!(dict.get(b"PdfRedact"), Ok(Object::Boolean(true))) {
                        return true;
                    }
                }
            }
        }
    }
    false
}

fn apply_redactions(handle: i64) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let page_ids: Vec<ObjectId> = doc.get_pages().values().copied().collect();
    let mut applied = false;
    for page_id in page_ids {
        let annot_ids: Vec<ObjectId> = match doc
            .get_dictionary(page_id)
            .ok()
            .and_then(|d| d.get(b"Annots").ok())
            .and_then(|o| deref(doc, o))
        {
            Some(Object::Array(a)) => a.iter().filter_map(|o| o.as_reference().ok()).collect(),
            _ => continue,
        };
        let mut rects: Vec<[f64; 4]> = Vec::new();
        let mut redact_ids: Vec<ObjectId> = Vec::new();
        for aid in &annot_ids {
            if let Ok(dict) = doc.get_dictionary(*aid) {
                if matches!(dict.get(b"PdfRedact"), Ok(Object::Boolean(true))) {
                    if let Some(r) = dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
                        rects.push(normalize_rect(r));
                        redact_ids.push(*aid);
                    }
                }
            }
        }
        if rects.is_empty() {
            continue;
        }
        let content = match doc.get_and_decode_page_content(page_id) {
            Ok(c) => c,
            Err(_) => continue,
        };
        let new_ops = redact_operations(content.operations, &rects);
        let mut bytes = lopdf::content::Content { operations: new_ops }.encode().unwrap_or_default();
        let mut cover = String::new();
        for r in &rects {
            cover.push_str(&format!(
                " q 0 0 0 rg {:.2} {:.2} {:.2} {:.2} re f Q",
                r[0], r[1], r[2] - r[0], r[3] - r[1]
            ));
        }
        bytes.extend_from_slice(cover.as_bytes());
        let cid = doc.add_object(Stream::new(dictionary! {}, bytes));
        if let Ok(p) = doc.get_dictionary_mut(page_id) {
            p.set("Contents", Object::Reference(cid));
        }
        for rid in redact_ids {
            remove_annot_ref(doc, page_id, rid);
            doc.objects.remove(&rid);
        }
        applied = true;
    }
    applied
}

fn save_document(handle: i64) -> Option<Vec<u8>> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let mut buf = Vec::new();
    doc.save_to(&mut buf).ok()?;
    Some(buf)
}

/// Build a signature-ready PDF: an invisible signature field + `/Sig` dict with a
/// fixed-width `/ByteRange` placeholder and a `contents_bytes`-long zero
/// `/Contents`. Kotlin patches the ByteRange and fills the detached PKCS#7
/// signature. Returns the serialized bytes.
fn prepare_signature(handle: i64, name: &str, contents_bytes: usize) -> Option<Vec<u8>> {
    let bytes = save_document(handle)?;
    let mut doc = Document::load_mem(&bytes).ok()?;
    let page0 = doc.get_pages().values().next().copied()?;
    let catalog_id = doc.trailer.get(b"Root").ok().and_then(|o| o.as_reference().ok())?;

    let mut sig = Dictionary::new();
    sig.set("Type", name_obj("Sig"));
    sig.set("Filter", name_obj("Adobe.PPKLite"));
    sig.set("SubFilter", name_obj("adbe.pkcs7.detached"));
    sig.set(
        "ByteRange",
        Object::Array(vec![
            Object::Integer(0),
            Object::Integer(2_000_000_000),
            Object::Integer(2_000_000_000),
            Object::Integer(2_000_000_000),
        ]),
    );
    sig.set(
        "Contents",
        Object::String(vec![0u8; contents_bytes], lopdf::StringFormat::Hexadecimal),
    );
    sig.set("Name", Object::string_literal(name));
    let sig_id = doc.add_object(sig);

    let mut widget = Dictionary::new();
    widget.set("Type", name_obj("Annot"));
    widget.set("Subtype", name_obj("Widget"));
    widget.set("FT", name_obj("Sig"));
    widget.set("T", Object::string_literal("Signature1"));
    widget.set("V", Object::Reference(sig_id));
    widget.set("P", Object::Reference(page0));
    widget.set("Rect", Object::Array(vec![0.into(), 0.into(), 0.into(), 0.into()]));
    widget.set("F", Object::Integer(132));
    let widget_id = doc.add_object(widget);
    append_annot(&mut doc, page0, widget_id);

    // Attach the field to the AcroForm (reuse/extend existing, else create one).
    let af_ref = doc
        .get_dictionary(catalog_id)
        .ok()
        .and_then(|c| c.get(b"AcroForm").ok())
        .and_then(|o| o.as_reference().ok());
    if let Some(af_id) = af_ref {
        if let Ok(af) = doc.get_dictionary_mut(af_id) {
            let has_fields = matches!(af.get(b"Fields"), Ok(Object::Array(_)));
            if !has_fields {
                af.set("Fields", Object::Array(vec![]));
            }
            if let Ok(Object::Array(a)) = af.get_mut(b"Fields") {
                a.push(Object::Reference(widget_id));
            }
            af.set("SigFlags", Object::Integer(3));
        }
    } else {
        let mut af = Dictionary::new();
        af.set("Fields", Object::Array(vec![Object::Reference(widget_id)]));
        af.set("SigFlags", Object::Integer(3));
        let af_id = doc.add_object(af);
        if let Ok(cat) = doc.get_dictionary_mut(catalog_id) {
            cat.set("AcroForm", Object::Reference(af_id));
        }
    }

    let mut out = Vec::new();
    doc.save_to(&mut out).ok()?;
    Some(out)
}

/// Extract the document's visible text (from rendered text primitives), one
/// blank line between pages.
fn document_text(handle: i64) -> Option<String> {
    let reg = registry().lock().unwrap();
    let doc = reg.get(&handle)?;
    let mut out = String::new();
    for (_num, page_id) in doc.get_pages() {
        if let Ok(pd) = interpret_page(doc, page_id) {
            let mut last_y = f32::NAN;
            for p in &pd.prims {
                if let Prim::Text { text, y, .. } = p {
                    if !last_y.is_nan() && (last_y - *y).abs() > 2.0 {
                        out.push('\n');
                    }
                    out.push_str(text);
                    last_y = *y;
                }
            }
        }
        out.push_str("\n\n");
    }
    Some(out)
}

// ---------------------------------------------------------------------------
// Document outline (bookmarks)
// ---------------------------------------------------------------------------

/// Resolve a destination (array, or named) to a 0-based page index, or -1.
fn resolve_dest(doc: &Document, dest: &Object, page_index: &HashMap<ObjectId, i32>) -> i32 {
    let arr = match dest {
        Object::Array(a) => Some(a.clone()),
        Object::Name(n) => named_dest(doc, n),
        Object::String(s, _) => named_dest(doc, s),
        _ => None,
    };
    if let Some(a) = arr {
        if let Some(first) = a.first() {
            if let Ok(id) = first.as_reference() {
                return page_index.get(&id).copied().unwrap_or(-1);
            }
        }
    }
    -1
}

/// Look up a named destination's explicit dest array via `/Dests` and the
/// `/Names` name tree.
fn named_dest(doc: &Document, name: &[u8]) -> Option<Vec<Object>> {
    let catalog = doc.catalog().ok()?;
    // Old-style /Dests dictionary.
    if let Some(Object::Dictionary(dests)) = catalog.get(b"Dests").ok().and_then(|o| deref(doc, o)) {
        if let Ok(v) = dests.get(name) {
            return dest_array(doc, v);
        }
    }
    // /Names /Dests name tree.
    let root = catalog
        .get(b"Names")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|d| d.get(b"Dests").ok())
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())?;
    let mut visited = std::collections::HashSet::new();
    search_name_tree(doc, root, name, &mut visited)
}

fn dest_array(doc: &Document, obj: &Object) -> Option<Vec<Object>> {
    match deref(doc, obj)? {
        Object::Array(a) => Some(a.clone()),
        Object::Dictionary(d) => d.get(b"D").ok().and_then(|o| dest_array(doc, o)),
        _ => None,
    }
}

fn search_name_tree(
    doc: &Document,
    node: &lopdf::Dictionary,
    name: &[u8],
    visited: &mut std::collections::HashSet<ObjectId>,
) -> Option<Vec<Object>> {
    if let Some(Object::Array(names)) = node.get(b"Names").ok().and_then(|o| deref(doc, o)) {
        let mut i = 0;
        while i + 1 < names.len() {
            if names[i].as_str().ok() == Some(name) {
                return dest_array(doc, &names[i + 1]);
            }
            i += 2;
        }
    }
    if let Some(Object::Array(kids)) = node.get(b"Kids").ok().and_then(|o| deref(doc, o)) {
        for kid in kids {
            if let Ok(id) = kid.as_reference() {
                if !visited.insert(id) {
                    continue;
                }
                if let Ok(child) = doc.get_dictionary(id) {
                    if let Some(r) = search_name_tree(doc, child, name, visited) {
                        return Some(r);
                    }
                }
            }
        }
    }
    None
}

/// Walk the outline linked-list/tree collecting `(level, pageIndex, title)`.
fn walk_outline(
    doc: &Document,
    start: Option<ObjectId>,
    level: u16,
    page_index: &HashMap<ObjectId, i32>,
    visited: &mut std::collections::HashSet<ObjectId>,
    out: &mut Vec<(u16, i32, String)>,
) {
    let mut cur = start;
    while let Some(id) = cur {
        if !visited.insert(id) || out.len() > 5000 {
            break;
        }
        let dict = match doc.get_dictionary(id) {
            Ok(d) => d,
            Err(_) => break,
        };
        let title = dict
            .get(b"Title")
            .ok()
            .and_then(|o| o.as_str().ok())
            .map(decode_pdf_text)
            .unwrap_or_default();
        let page = dict
            .get(b"Dest")
            .ok()
            .and_then(|o| deref(doc, o))
            .map(|d| resolve_dest(doc, d, page_index))
            .or_else(|| {
                dict.get(b"A")
                    .ok()
                    .and_then(|o| deref(doc, o))
                    .and_then(|o| o.as_dict().ok())
                    .and_then(|a| a.get(b"D").ok())
                    .and_then(|o| deref(doc, o))
                    .map(|d| resolve_dest(doc, d, page_index))
            })
            .unwrap_or(-1);
        out.push((level, page, title));

        if let Some(first) = dict.get(b"First").ok().and_then(|o| o.as_reference().ok()) {
            walk_outline(doc, Some(first), level + 1, page_index, visited, out);
        }
        cur = dict.get(b"Next").ok().and_then(|o| o.as_reference().ok());
    }
}

/// Serialized document outline: u32 count, then per entry
/// `u16 level, i32 pageIndex, u16 titleLen, [utf8]`.
fn list_outline(handle: i64) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap();
    let doc = reg.get(&handle)?;
    let outlines_id = doc
        .catalog()
        .ok()
        .and_then(|c| c.get(b"Outlines").ok())
        .and_then(|o| o.as_reference().ok())?;
    let pages = doc.get_pages();
    let mut page_index = HashMap::new();
    for (i, (_, id)) in pages.iter().enumerate() {
        page_index.insert(*id, i as i32);
    }
    let first = doc
        .get_dictionary(outlines_id)
        .ok()
        .and_then(|d| d.get(b"First").ok())
        .and_then(|o| o.as_reference().ok());
    let mut items = Vec::new();
    let mut visited = std::collections::HashSet::new();
    walk_outline(doc, first, 0, &page_index, &mut visited, &mut items);

    let mut buf = Vec::new();
    buf.extend_from_slice(&(items.len() as u32).to_le_bytes());
    for (level, page, title) in items {
        buf.extend_from_slice(&level.to_le_bytes());
        buf.extend_from_slice(&page.to_le_bytes());
        let b = title.as_bytes();
        let len = b.len().min(u16::MAX as usize);
        buf.extend_from_slice(&(len as u16).to_le_bytes());
        buf.extend_from_slice(&b[..len]);
    }
    Some(buf)
}

// ---------------------------------------------------------------------------
// Full-text search
// ---------------------------------------------------------------------------

/// Cached searchable text for one page: the concatenated (lowercased) glyph
/// text plus a span per text primitive mapping byte ranges back to positions.
struct PageIndex {
    text: String,
    /// (start_byte, end_byte, x, y, size, char_count)
    spans: Vec<(usize, usize, f32, f32, f32, usize)>,
}

/// Process-wide cache of built text indices, keyed by document handle, so a
/// document's pages are interpreted for text only once.
fn index_cache() -> &'static Mutex<HashMap<i64, std::sync::Arc<Vec<PageIndex>>>> {
    static CACHE: OnceLock<Mutex<HashMap<i64, std::sync::Arc<Vec<PageIndex>>>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Build the text index for every page (text-only interpretation, no images).
fn build_index(doc: &Document) -> Vec<PageIndex> {
    let mut out = Vec::new();
    for (_, page_id) in doc.get_pages() {
        let content = match doc.get_and_decode_page_content(page_id) {
            Ok(c) => c,
            Err(_) => {
                out.push(PageIndex { text: String::new(), spans: Vec::new() });
                continue;
            }
        };
        let res = resources_dict(doc, page_id);
        let mut prims = Vec::new();
        interpret_content(
            doc,
            &content.operations,
            res.as_ref(),
            GraphicsState::default(),
            &mut prims,
            0,
            true,
        );
        let mut text = String::new();
        let mut spans = Vec::new();
        for p in &prims {
            if let Prim::Text { x, y, size, text: t, .. } = p {
                let start = text.len();
                text.push_str(&t.to_lowercase());
                spans.push((start, text.len(), *x, *y, *size, t.chars().count()));
            }
        }
        out.push(PageIndex { text, spans });
    }
    out
}

/// Return the cached text index for `handle`, building it on first use.
fn ensure_index(handle: i64) -> Option<std::sync::Arc<Vec<PageIndex>>> {
    if let Some(idx) = index_cache().lock().unwrap().get(&handle) {
        return Some(idx.clone());
    }
    let built = {
        let reg = registry().lock().unwrap();
        let doc = reg.get(&handle)?;
        std::sync::Arc::new(build_index(doc))
    };
    index_cache().lock().unwrap().insert(handle, built.clone());
    Some(built)
}

/// Find `needle` (case-insensitive) across all pages, returning serialized
/// matches: u32 count, then per match `i32 pageIndex, f32 x0,y0,x1,y1` (page
/// space). Uses a cached per-page text index so repeated searches are instant.
fn search_document(handle: i64, needle: &str) -> Option<Vec<u8>> {
    let index = ensure_index(handle)?;
    let needle = needle.to_lowercase();

    let mut matches: Vec<(i32, f32, f32, f32, f32)> = Vec::new();
    if !needle.is_empty() {
        'pages: for (pi, page) in index.iter().enumerate() {
            let mut from = 0;
            while let Some(rel) = page.text[from..].find(&needle) {
                let ms = from + rel;
                let me = ms + needle.len();
                let mut minx = f32::MAX;
                let mut miny = f32::MAX;
                let mut maxx = f32::MIN;
                let mut maxy = f32::MIN;
                let mut any = false;
                for (s, e, x, y, size, clen) in &page.spans {
                    if *s < me && *e > ms {
                        any = true;
                        minx = minx.min(*x);
                        miny = miny.min(*y);
                        maxx = maxx.max(*x + *size * 0.5 * (*clen).max(1) as f32);
                        maxy = maxy.max(*y + *size);
                    }
                }
                if any {
                    matches.push((pi as i32, minx, miny, maxx, maxy));
                }
                from = me;
                if matches.len() > 2000 {
                    break 'pages;
                }
            }
        }
    }

    let mut buf = Vec::new();
    buf.extend_from_slice(&(matches.len() as u32).to_le_bytes());
    for (page, x0, y0, x1, y1) in matches {
        buf.extend_from_slice(&page.to_le_bytes());
        for v in [x0, y0, x1, y1] {
            buf.extend_from_slice(&v.to_le_bytes());
        }
    }
    Some(buf)
}

fn cubic_bezier(
    p0: (f64, f64),
    p1: (f64, f64),
    p2: (f64, f64),
    p3: (f64, f64),
    t: f64,
) -> (f64, f64) {
    let u = 1.0 - t;
    let w0 = u * u * u;
    let w1 = 3.0 * u * u * t;
    let w2 = 3.0 * u * t * t;
    let w3 = t * t * t;
    (
        w0 * p0.0 + w1 * p1.0 + w2 * p2.0 + w3 * p3.0,
        w0 * p0.1 + w1 * p1.1 + w2 * p2.1 + w3 * p3.1,
    )
}

fn emit_fill(prims: &mut Vec<Prim>, subpaths: &[Vec<(f64, f64)>], argb: u32, even_odd: bool) {
    for sp in subpaths {
        if sp.len() >= 3 {
            prims.push(Prim::Fill {
                argb,
                even_odd,
                pts: sp.iter().map(|&(x, y)| (x as f32, y as f32)).collect(),
            });
        }
    }
}

fn emit_stroke(prims: &mut Vec<Prim>, subpaths: &[Vec<(f64, f64)>], gs: &GraphicsState) {
    // Approximate device-space scale via the CTM's average axis length.
    let ctm = &gs.ctm;
    let sx = (ctm[0] * ctm[0] + ctm[1] * ctm[1]).sqrt();
    let sy = (ctm[2] * ctm[2] + ctm[3] * ctm[3]).sqrt();
    let scale = (sx + sy) / 2.0;
    let width = (gs.line_width * scale) as f32;
    let dash: Vec<f32> = gs
        .dash
        .iter()
        .map(|d| (d * scale) as f32)
        .filter(|d| *d >= 0.0)
        .collect();
    // A valid dash needs at least two positive entries with a non-zero sum.
    let dash = if dash.len() >= 2 && dash.iter().sum::<f32>() > 0.0 {
        dash
    } else {
        Vec::new()
    };
    let dash_phase = (gs.dash_phase * scale) as f32;
    for sp in subpaths {
        if sp.len() >= 2 {
            prims.push(Prim::Stroke {
                argb: gs.stroke,
                width: width.max(0.1),
                dash: dash.clone(),
                dash_phase,
                pts: sp.iter().map(|&(x, y)| (x as f32, y as f32)).collect(),
            });
        }
    }
}

/// Emit a text primitive for `bytes` at the current text matrix (unless the
/// render mode is invisible/clip-only) and return the horizontal advance in
/// user-space units so the caller can step the text matrix.
/// Emit one text primitive per glyph, each positioned at its exact device-space
/// origin computed from the PDF glyph widths + text state. Drawing glyph-by-glyph
/// (rather than one run) keeps kerned/justified text aligned even though a
/// substitute system font renders the glyph shapes. Returns the total advance in
/// text space so the caller can step the text matrix.
fn show_string(
    prims: &mut Vec<Prim>,
    gs: &GraphicsState,
    fonts: &HashMap<Vec<u8>, FontInfo>,
    text_matrix: &Mat,
    bytes: &[u8],
) -> f64 {
    let tfs = gs.font_size;
    let th = gs.h_scale;
    let trm = mat_mul(text_matrix, &gs.ctm);
    let y_scale = (trm[2] * trm[2] + trm[3] * trm[3]).sqrt();
    let size = (tfs * y_scale) as f32;
    // Modes 3 (invisible) and 7 (clip only) advance the pen but paint nothing.
    let drawable = gs.render_mode != 3 && gs.render_mode != 7;

    let fi = match fonts.get(&gs.font_key) {
        Some(fi) => fi,
        None => {
            // No font metrics: emit the run at the origin and estimate advance.
            if drawable && !bytes.is_empty() {
                let (x, y) = transform(&trm, 0.0, gs.rise);
                let text: String =
                    bytes.iter().filter_map(|&b| char::from_u32(b as u32)).collect();
                if !text.is_empty() {
                    prims.push(Prim::Text {
                        x: x as f32,
                        y: y as f32,
                        size,
                        argb: gs.fill,
                        text,
                    });
                }
            }
            return bytes.len() as f64 * 0.5 * tfs * th;
        }
    };

    let mut pen = 0.0_f64;
    fi.for_each_code(bytes, |code, is_space| {
        if drawable {
            let (x, y) = transform(&trm, pen, gs.rise);
            let mut s = String::new();
            fi.push_code(code, &mut s);
            if !s.is_empty() {
                prims.push(Prim::Text {
                    x: x as f32,
                    y: y as f32,
                    size,
                    argb: gs.fill,
                    text: s,
                });
            }
        }
        let mut tx = fi.width(code) * tfs + gs.char_spacing;
        if is_space {
            tx += gs.word_spacing;
        }
        pen += tx * th;
    });
    pen
}

// ---------------------------------------------------------------------------
// Image XObjects
// ---------------------------------------------------------------------------

/// JPEG2000 (`JPXDecode`) decoding via the pure-Rust `openjp2` port of OpenJPEG.
mod jp2 {
    use openjp2::openjpeg::*;
    use std::ffi::c_void;

    struct Slice<'a> {
        off: usize,
        buf: &'a [u8],
    }
    impl<'a> Slice<'a> {
        fn seek(&mut self, n: usize) -> usize {
            self.off = self.buf.len().min(n);
            self.off
        }
        fn consume(&mut self, n: usize) -> usize {
            self.off = self.buf.len().min(self.off.saturating_add(n));
            self.off
        }
    }
    extern "C" fn free_fn(p: *mut c_void) {
        drop(unsafe { Box::from_raw(p as *mut Slice) })
    }
    extern "C" fn read_fn(pb: *mut c_void, nb: usize, p: *mut c_void) -> usize {
        if pb.is_null() || nb == 0 {
            return usize::MAX;
        }
        let s = unsafe { &mut *(p as *mut Slice) };
        let remaining = s.buf.len() - s.off;
        if remaining == 0 {
            return usize::MAX;
        }
        let n = remaining.min(nb);
        let out = unsafe { std::slice::from_raw_parts_mut(pb as *mut u8, n) };
        out.copy_from_slice(&s.buf[s.off..s.off + n]);
        s.off += n;
        n
    }
    extern "C" fn skip_fn(nb: i64, p: *mut c_void) -> i64 {
        let s = unsafe { &mut *(p as *mut Slice) };
        s.consume(nb.max(0) as usize) as i64
    }
    extern "C" fn seek_fn(nb: i64, p: *mut c_void) -> i32 {
        let s = unsafe { &mut *(p as *mut Slice) };
        let want = nb.max(0) as usize;
        if s.seek(want) == want {
            1
        } else {
            0
        }
    }

    /// Decode JP2/J2K bytes to `(width, height, RGBA8888)`, or `None`.
    pub fn decode(bytes: &[u8]) -> Option<(u32, u32, Vec<u8>)> {
        // JP2 signature box vs raw codestream.
        let fmt = if bytes.len() > 4 && &bytes[4..8] == b"jP  " {
            OPJ_CODEC_JP2
        } else {
            OPJ_CODEC_J2K
        };
        unsafe { decode_with(bytes, fmt).or_else(|| decode_with(bytes, OPJ_CODEC_JP2)) }
    }

    unsafe fn decode_with(bytes: &[u8], fmt: OPJ_CODEC_FORMAT) -> Option<(u32, u32, Vec<u8>)> {
        let data = Box::new(Slice { off: 0, buf: bytes });
        let stream = opj_stream_default_create(1);
        if stream.is_null() {
            return None;
        }
        let p = Box::into_raw(data) as *mut c_void;
        opj_stream_set_read_function(stream, Some(read_fn));
        opj_stream_set_skip_function(stream, Some(skip_fn));
        opj_stream_set_seek_function(stream, Some(seek_fn));
        opj_stream_set_user_data_length(stream, bytes.len() as u64);
        opj_stream_set_user_data(stream, p, Some(free_fn));

        let codec = opj_create_decompress(fmt);
        if codec.is_null() {
            opj_stream_destroy(stream);
            return None;
        }
        let mut params = opj_dparameters_t::default();
        opj_set_default_decoder_parameters(&mut params);
        let mut out = None;
        if opj_setup_decoder(codec, &mut params) != 0 {
            let mut image = std::ptr::null_mut() as *mut opj_image_t;
            if opj_read_header(stream, codec, &mut image) != 0
                && opj_decode(codec, stream, image) != 0
                && opj_end_decompress(codec, stream) != 0
                && !image.is_null()
            {
                out = image_to_rgba(&*image);
            }
            if !image.is_null() {
                opj_image_destroy(image);
            }
        }
        opj_destroy_codec(codec);
        opj_stream_destroy(stream);
        out
    }

    unsafe fn image_to_rgba(img: &opj_image_t) -> Option<(u32, u32, Vec<u8>)> {
        let w = (img.x1 - img.x0) as usize;
        let h = (img.y1 - img.y0) as usize;
        if w == 0 || h == 0 || w > 20000 || h > 20000 || img.numcomps == 0 {
            return None;
        }
        let comps = std::slice::from_raw_parts(img.comps, img.numcomps as usize);
        let n = img.numcomps as usize;
        // Sample a component's value at (x,y) scaled to 8-bit.
        let sample = |c: &opj_image_comp_t, x: usize, y: usize| -> u8 {
            let cw = c.w as usize;
            let ch = c.h as usize;
            if cw == 0 || ch == 0 || c.data.is_null() {
                return 0;
            }
            let sx = (x * cw / w).min(cw - 1);
            let sy = (y * ch / h).min(ch - 1);
            let mut v = *c.data.add(sy * cw + sx);
            if c.sgnd != 0 {
                v += 1 << (c.prec - 1);
            }
            let prec = c.prec as i32;
            let v = if prec > 8 {
                v >> (prec - 8)
            } else if prec < 8 {
                v << (8 - prec)
            } else {
                v
            };
            v.clamp(0, 255) as u8
        };

        let mut rgba = vec![0u8; w * h * 4];
        for y in 0..h {
            for x in 0..w {
                let idx = (y * w + x) * 4;
                let (r, g, b) = if n >= 3 {
                    (
                        sample(&comps[0], x, y),
                        sample(&comps[1], x, y),
                        sample(&comps[2], x, y),
                    )
                } else {
                    let v = sample(&comps[0], x, y);
                    (v, v, v)
                };
                rgba[idx] = r;
                rgba[idx + 1] = g;
                rgba[idx + 2] = b;
                rgba[idx + 3] = 255;
            }
        }
        Some((w as u32, h as u32, rgba))
    }
}

struct ImageData {
    w: u32,
    h: u32,
    /// 0 = raw RGBA8888, 1 = JPEG bytes.
    format: u8,
    data: Vec<u8>,
}

/// Map of XObject resource name -> object id from a resources dictionary.
fn xobjects_from_resources(doc: &Document, res_dict: &lopdf::Dictionary) -> HashMap<Vec<u8>, ObjectId> {
    let mut out = HashMap::new();
    if let Some(Object::Dictionary(xo)) = res_dict.get(b"XObject").ok().and_then(|o| deref(doc, o)) {
        for (name, v) in xo.iter() {
            if let Ok(id) = v.as_reference() {
                out.insert(name.clone(), id);
            }
        }
    }
    out
}

/// Resolve the page's (inherited) `/Resources` as an owned dictionary.
fn resources_dict(doc: &Document, page_id: ObjectId) -> Option<lopdf::Dictionary> {
    inherited(doc, page_id, b"Resources")
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .cloned()
}

/// Collect the `/Filter` names of a stream (single name or array).
fn filter_names(doc: &Document, dict: &lopdf::Dictionary) -> Vec<String> {
    match dict.get(b"Filter").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Name(n)) => vec![String::from_utf8_lossy(n).into_owned()],
        Some(Object::Array(a)) => a
            .iter()
            .filter_map(|o| o.as_name().ok())
            .map(|n| String::from_utf8_lossy(n).into_owned())
            .collect(),
        _ => Vec::new(),
    }
}

/// Number of color components for a colorspace object, plus an optional Indexed
/// palette `(base_components, lookup_bytes)`.
fn colorspace_info(
    doc: &Document,
    cs: Option<&Object>,
) -> (u8, Option<(u8, Vec<u8>)>) {
    let cs = match cs.and_then(|o| deref(doc, o)) {
        Some(o) => o,
        None => return (1, None),
    };
    match cs {
        Object::Name(n) => match n.as_slice() {
            b"DeviceRGB" | b"RGB" | b"CalRGB" => (3, None),
            b"DeviceCMYK" | b"CMYK" => (4, None),
            _ => (1, None), // DeviceGray / CalGray / fallback
        },
        Object::Array(a) => {
            let head = a.first().and_then(|o| o.as_name().ok()).unwrap_or(b"");
            match head {
                b"ICCBased" => {
                    let n = a
                        .get(1)
                        .and_then(|o| deref(doc, o))
                        .and_then(|o| match o {
                            Object::Stream(s) => s.dict.get(b"N").ok().and_then(num),
                            _ => None,
                        })
                        .unwrap_or(1.0) as u8;
                    (n.max(1), None)
                }
                b"Indexed" | b"I" => {
                    let (base_n, _) = colorspace_info(doc, a.get(1));
                    let lookup = match a.get(3).and_then(|o| deref(doc, o)) {
                        Some(Object::String(s, _)) => s.clone(),
                        Some(Object::Stream(s)) => {
                            s.decompressed_content().unwrap_or_else(|_| s.content.clone())
                        }
                        _ => Vec::new(),
                    };
                    (1, Some((base_n, lookup)))
                }
                b"CalRGB" => (3, None),
                b"CalGray" => (1, None),
                b"DeviceN" => {
                    let n = a
                        .get(1)
                        .and_then(|o| deref(doc, o))
                        .and_then(|o| o.as_array().ok())
                        .map(|arr| arr.len() as u8)
                        .unwrap_or(1);
                    (n.max(1), None)
                }
                b"Separation" => (1, None),
                _ => (1, None),
            }
        }
        _ => (1, None),
    }
}

fn comps_to_rgb(comps: &[u8], n: u8) -> (u8, u8, u8) {
    match n {
        3 => (comps[0], comps[1], comps[2]),
        4 => {
            let c = comps[0] as f64 / 255.0;
            let m = comps[1] as f64 / 255.0;
            let y = comps[2] as f64 / 255.0;
            let k = comps[3] as f64 / 255.0;
            let r = (1.0 - c) * (1.0 - k);
            let g = (1.0 - m) * (1.0 - k);
            let b = (1.0 - y) * (1.0 - k);
            ((r * 255.0) as u8, (g * 255.0) as u8, (b * 255.0) as u8)
        }
        _ => (comps[0], comps[0], comps[0]),
    }
}

/// Extract a drawable image from an image XObject stream, or `None` if the
/// format is unsupported (e.g. JPEG2000, exotic color spaces).
fn extract_image(doc: &Document, stream: &lopdf::Stream, fill_argb: u32) -> Option<ImageData> {
    let dict = &stream.dict;
    let w = dict.get(b"Width").ok().and_then(num)? as u32;
    let h = dict.get(b"Height").ok().and_then(num)? as u32;
    if w == 0 || h == 0 || w > 20000 || h > 20000 {
        return None;
    }
    let filters = filter_names(doc, dict);
    let is_dct = filters.iter().any(|f| f == "DCTDecode" || f == "DCT");
    let is_jpx = filters.iter().any(|f| f == "JPXDecode");
    if is_jpx {
        // JPEG2000: decode with openjp2 to RGBA, then apply any soft mask.
        if let Some((jw, jh, mut rgba)) = jp2::decode(&stream.content) {
            let smask = read_smask(doc, dict, jw, jh);
            apply_smask(&mut rgba, &smask);
            return Some(ImageData {
                w: jw,
                h: jh,
                format: 0,
                data: rgba,
            });
        }
        return None;
    }
    if is_dct {
        // Hand the raw JPEG bytes to Android's BitmapFactory.
        return Some(ImageData {
            w,
            h,
            format: 1,
            data: stream.content.clone(),
        });
    }

    let image_mask = matches!(dict.get(b"ImageMask").ok(), Some(Object::Boolean(true)));
    let bpc = if image_mask {
        1
    } else {
        dict.get(b"BitsPerComponent").ok().and_then(num).unwrap_or(8.0) as u32
    };
    let samples = stream_data(stream);
    let mut rgba = vec![0u8; (w * h * 4) as usize];
    let smask = read_smask(doc, dict, w, h);

    if image_mask {
        // 1-bit stencil: paint fill color where the (Decode-adjusted) sample is
        // 0; elsewhere transparent.
        let invert = matches!(
            dict.get(b"Decode").ok().and_then(|o| deref(doc, o)),
            Some(Object::Array(a)) if a.first().and_then(num) == Some(1.0)
        );
        let fr = ((fill_argb >> 16) & 0xFF) as u8;
        let fg = ((fill_argb >> 8) & 0xFF) as u8;
        let fb = (fill_argb & 0xFF) as u8;
        let row_bytes = ((w + 7) / 8) as usize;
        for y in 0..h as usize {
            for x in 0..w as usize {
                let byte = samples.get(y * row_bytes + x / 8).copied().unwrap_or(0);
                let mut bit = (byte >> (7 - (x % 8))) & 1;
                if invert {
                    bit ^= 1;
                }
                let idx = (y * w as usize + x) * 4;
                if bit == 0 {
                    rgba[idx] = fr;
                    rgba[idx + 1] = fg;
                    rgba[idx + 2] = fb;
                    rgba[idx + 3] = 255;
                }
            }
        }
        return Some(ImageData { w, h, format: 0, data: rgba });
    }

    let (ncomp, indexed) = colorspace_info(doc, dict.get(b"ColorSpace").ok());

    if bpc == 8 {
        let row_bytes = (w as usize) * (ncomp as usize);
        for y in 0..h as usize {
            for x in 0..w as usize {
                let base = y * row_bytes + x * ncomp as usize;
                let idx = (y * w as usize + x) * 4;
                let (r, g, b) = if let Some((base_n, lookup)) = &indexed {
                    let index = samples.get(base).copied().unwrap_or(0) as usize;
                    let off = index * *base_n as usize;
                    if off + *base_n as usize <= lookup.len() {
                        comps_to_rgb(&lookup[off..off + *base_n as usize], *base_n)
                    } else {
                        (0, 0, 0)
                    }
                } else if base + ncomp as usize <= samples.len() {
                    comps_to_rgb(&samples[base..base + ncomp as usize], ncomp)
                } else {
                    (0, 0, 0)
                };
                rgba[idx] = r;
                rgba[idx + 1] = g;
                rgba[idx + 2] = b;
                rgba[idx + 3] = 255;
            }
        }
        apply_smask(&mut rgba, &smask);
        return Some(ImageData { w, h, format: 0, data: rgba });
    }

    if bpc == 1 {
        // 1-bit grayscale (0 = black, 1 = white), or indexed 1-bit palette.
        let row_bytes = ((w + 7) / 8) as usize;
        for y in 0..h as usize {
            for x in 0..w as usize {
                let byte = samples.get(y * row_bytes + x / 8).copied().unwrap_or(0);
                let bit = (byte >> (7 - (x % 8))) & 1;
                let idx = (y * w as usize + x) * 4;
                let (r, g, b) = if let Some((base_n, lookup)) = &indexed {
                    let off = bit as usize * *base_n as usize;
                    if off + *base_n as usize <= lookup.len() {
                        comps_to_rgb(&lookup[off..off + *base_n as usize], *base_n)
                    } else {
                        (0, 0, 0)
                    }
                } else if bit == 1 {
                    (255, 255, 255)
                } else {
                    (0, 0, 0)
                };
                rgba[idx] = r;
                rgba[idx + 1] = g;
                rgba[idx + 2] = b;
                rgba[idx + 3] = 255;
            }
        }
        apply_smask(&mut rgba, &smask);
        return Some(ImageData { w, h, format: 0, data: rgba });
    }

    None
}

/// Apply a per-pixel soft-mask alpha (length `w*h`) to an RGBA buffer.
fn apply_smask(rgba: &mut [u8], smask: &Option<Vec<u8>>) {
    if let Some(alpha) = smask {
        let n = (rgba.len() / 4).min(alpha.len());
        for i in 0..n {
            rgba[i * 4 + 3] = alpha[i];
        }
    }
}

/// Decode an image's `/SMask` (soft mask) into a `w*h` 8-bit alpha buffer,
/// nearest-resampling if its dimensions differ. Returns `None` for absent or
/// unsupported (e.g. DCT/JPX, non-8-bit) masks.
fn read_smask(doc: &Document, dict: &lopdf::Dictionary, w: u32, h: u32) -> Option<Vec<u8>> {
    let sm = dict.get(b"SMask").ok().and_then(|o| deref(doc, o))?;
    let s = match sm {
        Object::Stream(s) => s,
        _ => return None,
    };
    let filters = filter_names(doc, &s.dict);
    if filters.iter().any(|f| f == "DCTDecode" || f == "JPXDecode") {
        return None;
    }
    let sbpc = s.dict.get(b"BitsPerComponent").ok().and_then(num).unwrap_or(8.0) as u32;
    if sbpc != 8 {
        return None;
    }
    let sw = s.dict.get(b"Width").ok().and_then(num)? as usize;
    let sh = s.dict.get(b"Height").ok().and_then(num)? as usize;
    if sw == 0 || sh == 0 {
        return None;
    }
    let data = stream_data(s);
    let (w, h) = (w as usize, h as usize);
    let mut alpha = vec![255u8; w * h];
    for y in 0..h {
        for x in 0..w {
            let sx = x * sw / w;
            let sy = y * sh / h;
            alpha[y * w + x] = data.get(sy * sw + sx).copied().unwrap_or(255);
        }
    }
    Some(alpha)
}

/// Build a `code -> unicode char` map from an embedded simple TrueType font's
/// `/FontFile2` cmap, used to recover text from re-encoded subset fonts that
/// lack a `/ToUnicode` map. Empty if unavailable.
fn ttf_code_map(doc: &Document, font: &lopdf::Dictionary) -> HashMap<u32, char> {
    let ff = font
        .get(b"FontDescriptor")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|d| d.get(b"FontFile2").ok())
        .and_then(|o| deref(doc, o));
    match ff {
        Some(Object::Stream(s)) => ttf::code_to_unicode(&stream_data(s)),
        _ => HashMap::new(),
    }
}

/// Minimal TrueType `cmap` parser: recovers a character-code → Unicode map by
/// composing a code→glyph subtable (Mac 1,0 or Symbol 3,0) with the reverse of
/// a Unicode subtable (3,1 / 0,3 / 3,10). All reads are bounds-checked so
/// malformed font data can never panic.
mod ttf {
    use std::collections::HashMap;

    fn u16b(b: &[u8], o: usize) -> u16 {
        ((*b.get(o).unwrap_or(&0) as u16) << 8) | *b.get(o + 1).unwrap_or(&0) as u16
    }
    fn u32b(b: &[u8], o: usize) -> u32 {
        ((u16b(b, o) as u32) << 16) | u16b(b, o + 2) as u32
    }

    fn table_offset(b: &[u8], tag: &[u8; 4]) -> Option<usize> {
        let num = u16b(b, 4) as usize;
        for i in 0..num {
            let rec = 12 + i * 16;
            if b.get(rec..rec + 4)? == tag {
                return Some(u32b(b, rec + 8) as usize);
            }
        }
        None
    }

    /// Parse a subtable at `off` into (code, glyphId) pairs.
    fn parse_subtable(b: &[u8], off: usize) -> Vec<(u32, u16)> {
        let mut out = Vec::new();
        match u16b(b, off) {
            0 => {
                // Byte encoding: 256 single-byte glyph ids.
                for c in 0..256u32 {
                    let g = *b.get(off + 6 + c as usize).unwrap_or(&0) as u16;
                    if g != 0 {
                        out.push((c, g));
                    }
                }
            }
            6 => {
                let first = u16b(b, off + 6) as u32;
                let count = u16b(b, off + 8) as usize;
                for i in 0..count {
                    let g = u16b(b, off + 10 + i * 2);
                    if g != 0 {
                        out.push((first + i as u32, g));
                    }
                }
            }
            4 => {
                let segx2 = u16b(b, off + 6) as usize;
                let seg = segx2 / 2;
                let end_o = off + 14;
                let start_o = end_o + segx2 + 2;
                let delta_o = start_o + segx2;
                let range_o = delta_o + segx2;
                for i in 0..seg {
                    let end = u16b(b, end_o + i * 2);
                    let start = u16b(b, start_o + i * 2);
                    let delta = u16b(b, delta_o + i * 2);
                    let range = u16b(b, range_o + i * 2);
                    if start > end {
                        continue;
                    }
                    for c in start..=end {
                        if c == 0xFFFF {
                            break;
                        }
                        let gid = if range == 0 {
                            (c.wrapping_add(delta)) & 0xFFFF
                        } else {
                            let addr = range_o + i * 2 + range as usize + 2 * (c - start) as usize;
                            let g = u16b(b, addr);
                            if g == 0 {
                                0
                            } else {
                                (g.wrapping_add(delta)) & 0xFFFF
                            }
                        };
                        if gid != 0 {
                            out.push((c as u32, gid));
                        }
                    }
                }
            }
            12 => {
                let ngroups = u32b(b, off + 12) as usize;
                for i in 0..ngroups {
                    let g = off + 16 + i * 12;
                    let sc = u32b(b, g);
                    let ec = u32b(b, g + 4);
                    let sg = u32b(b, g + 8);
                    if sc > ec || ec - sc > 65535 {
                        continue;
                    }
                    for c in sc..=ec {
                        out.push((c, (sg + (c - sc)) as u16));
                    }
                }
            }
            _ => {}
        }
        out
    }

    pub fn code_to_unicode(b: &[u8]) -> HashMap<u32, char> {
        let mut result = HashMap::new();
        let cmap = match table_offset(b, b"cmap") {
            Some(o) => o,
            None => return result,
        };
        let n = u16b(b, cmap + 2) as usize;

        let mut uni_sub: Option<usize> = None;
        let mut mac_sub: Option<usize> = None;
        let mut sym_sub: Option<usize> = None;
        for i in 0..n {
            let r = cmap + 4 + i * 8;
            let pid = u16b(b, r);
            let eid = u16b(b, r + 2);
            let so = cmap + u32b(b, r + 4) as usize;
            match (pid, eid) {
                (3, 1) | (0, 3) | (3, 10) | (0, 4) => uni_sub = Some(so),
                (1, 0) => mac_sub = Some(so),
                (3, 0) => sym_sub = Some(so),
                _ => {}
            }
        }

        // glyph -> unicode (from the Unicode subtable).
        let gid_to_uni: HashMap<u16, u32> = match uni_sub {
            Some(o) => {
                let mut m = HashMap::new();
                for (uni, gid) in parse_subtable(b, o) {
                    m.entry(gid).or_insert(uni);
                }
                m
            }
            None => return result,
        };

        // code -> glyph (from Mac and/or Symbol subtables), then -> unicode.
        for sub in [mac_sub, sym_sub].into_iter().flatten() {
            for (code, gid) in parse_subtable(b, sub) {
                if let Some(&uni) = gid_to_uni.get(&gid) {
                    if let Some(c) = char::from_u32(uni) {
                        result.entry(code).or_insert(c);
                        // Symbol (3,0) codes are often mapped at 0xF000+code.
                        if code >= 0xF000 {
                            result.entry(code - 0xF000).or_insert(c);
                        }
                    }
                }
            }
        }
        result
    }
}

// ---------------------------------------------------------------------------
// Simple-font encodings (base encoding + /Differences)
// ---------------------------------------------------------------------------

mod encoding {
    use super::{deref, num, Object};
    use lopdf::Document;
    use std::collections::HashMap;

    /// Build a `code -> unicode char` map for a simple font: start from the base
    /// encoding (WinAnsi / MacRoman / Standard, or Symbol / ZapfDingbats for
    /// those base fonts), then apply any `/Encoding /Differences`.
    pub fn build(doc: &Document, font: &lopdf::Dictionary) -> HashMap<u32, char> {
        let base_font = font
            .get(b"BaseFont")
            .ok()
            .and_then(|o| o.as_name().ok())
            .map(|n| String::from_utf8_lossy(n).into_owned())
            .unwrap_or_default();

        let enc_obj = font.get(b"Encoding").ok().and_then(|o| deref(doc, o));
        let base_name = match &enc_obj {
            Some(Object::Name(n)) => Some(String::from_utf8_lossy(n).into_owned()),
            Some(Object::Dictionary(d)) => d
                .get(b"BaseEncoding")
                .ok()
                .and_then(|o| o.as_name().ok())
                .map(|n| String::from_utf8_lossy(n).into_owned()),
            _ => None,
        };

        let mut map = if base_font.contains("Symbol") {
            symbol_table()
        } else if base_font.contains("ZapfDingbats") || base_font.contains("Dingbats") {
            zapf_table()
        } else {
            match base_name.as_deref() {
                Some("WinAnsiEncoding") => win_ansi(),
                Some("MacRomanEncoding") => win_ansi(), // close enough for Latin text
                Some("StandardEncoding") => standard(),
                // Default base encoding for most simple fonts is Standard, but
                // WinAnsi is the safest superset for modern PDFs.
                _ => win_ansi(),
            }
        };

        // Apply /Differences: [ code /name /name code /name ... ].
        if let Some(Object::Dictionary(d)) = &enc_obj {
            if let Some(Object::Array(diffs)) = d.get(b"Differences").ok().and_then(|o| deref(doc, o))
            {
                let mut code = 0u32;
                for item in diffs {
                    match item {
                        Object::Integer(_) | Object::Real(_) => {
                            code = num(item).unwrap_or(0.0) as u32;
                        }
                        Object::Name(name) => {
                            if let Some(c) = glyph_to_char(&String::from_utf8_lossy(name)) {
                                map.insert(code, c);
                            }
                            code += 1;
                        }
                        _ => {}
                    }
                }
            }
        }
        map
    }

    /// Resolve an Adobe glyph name to a Unicode scalar. Handles `uniXXXX`,
    /// single-character names, digit/letter names and a curated common subset.
    pub fn glyph_to_char(name: &str) -> Option<char> {
        if let Some(hex) = name.strip_prefix("uni") {
            if hex.len() >= 4 {
                if let Ok(cp) = u32::from_str_radix(&hex[..4], 16) {
                    return char::from_u32(cp);
                }
            }
        }
        if name.starts_with('u') && name.len() >= 5 {
            if let Ok(cp) = u32::from_str_radix(&name[1..], 16) {
                if let Some(c) = char::from_u32(cp) {
                    return Some(c);
                }
            }
        }
        if let Some(c) = curated(name) {
            return Some(c);
        }
        // Single-character glyph name (e.g. "A", "a", "1").
        let mut chars = name.chars();
        if let (Some(c), None) = (chars.next(), chars.clone().next()) {
            return Some(c);
        }
        None
    }

    fn curated(name: &str) -> Option<char> {
        let c = match name {
            "space" | "nbspace" => ' ',
            "bullet" => '\u{2022}',
            "periodcentered" => '\u{00B7}',
            "endash" => '\u{2013}',
            "emdash" => '\u{2014}',
            "hyphen" | "sfthyphen" => '-',
            "quoteleft" => '\u{2018}',
            "quoteright" => '\u{2019}',
            "quotedblleft" => '\u{201C}',
            "quotedblright" => '\u{201D}',
            "quotesingle" => '\'',
            "quotedbl" => '"',
            "comma" => ',',
            "period" => '.',
            "colon" => ':',
            "semicolon" => ';',
            "slash" => '/',
            "backslash" => '\\',
            "asterisk" => '*',
            "ampersand" => '&',
            "at" => '@',
            "numbersign" => '#',
            "percent" => '%',
            "dollar" => '$',
            "cent" => '\u{00A2}',
            "sterling" => '\u{00A3}',
            "euro" => '\u{20AC}',
            "yen" => '\u{00A5}',
            "trademark" => '\u{2122}',
            "registered" => '\u{00AE}',
            "copyright" => '\u{00A9}',
            "degree" => '\u{00B0}',
            "plusminus" => '\u{00B1}',
            "multiply" => '\u{00D7}',
            "divide" => '\u{00F7}',
            "ellipsis" => '\u{2026}',
            "dagger" => '\u{2020}',
            "daggerdbl" => '\u{2021}',
            "paragraph" => '\u{00B6}',
            "section" => '\u{00A7}',
            "fi" => '\u{FB01}',
            "fl" => '\u{FB02}',
            "exclam" => '!',
            "question" => '?',
            "parenleft" => '(',
            "parenright" => ')',
            "bracketleft" => '[',
            "bracketright" => ']',
            "braceleft" => '{',
            "braceright" => '}',
            "less" => '<',
            "greater" => '>',
            "equal" => '=',
            "plus" => '+',
            "minus" => '\u{2212}',
            "underscore" => '_',
            "hyphenminus" => '-',
            "arrowright" => '\u{2192}',
            "arrowleft" => '\u{2190}',
            "arrowup" => '\u{2191}',
            "arrowdown" => '\u{2193}',
            "zero" => '0',
            "one" => '1',
            "two" => '2',
            "three" => '3',
            "four" => '4',
            "five" => '5',
            "six" => '6',
            "seven" => '7',
            "eight" => '8',
            "nine" => '9',
            _ => return None,
        };
        Some(c)
    }

    /// WinAnsiEncoding (CP1252): Latin-1 with the 0x80–0x9F range remapped.
    pub fn win_ansi() -> HashMap<u32, char> {
        let mut m = latin1();
        let overrides: [(u32, u32); 27] = [
            (0x80, 0x20AC),
            (0x82, 0x201A),
            (0x83, 0x0192),
            (0x84, 0x201E),
            (0x85, 0x2026),
            (0x86, 0x2020),
            (0x87, 0x2021),
            (0x88, 0x02C6),
            (0x89, 0x2030),
            (0x8A, 0x0160),
            (0x8B, 0x2039),
            (0x8C, 0x0152),
            (0x8E, 0x017D),
            (0x91, 0x2018),
            (0x92, 0x2019),
            (0x93, 0x201C),
            (0x94, 0x201D),
            (0x95, 0x2022),
            (0x96, 0x2013),
            (0x97, 0x2014),
            (0x98, 0x02DC),
            (0x99, 0x2122),
            (0x9A, 0x0161),
            (0x9B, 0x203A),
            (0x9C, 0x0153),
            (0x9E, 0x017E),
            (0x9F, 0x0178),
        ];
        for (code, cp) in overrides {
            if let Some(c) = char::from_u32(cp) {
                m.insert(code, c);
            }
        }
        m
    }

    /// StandardEncoding: for the ASCII range it matches Latin-1; good enough as
    /// a base to which /Differences are applied.
    fn standard() -> HashMap<u32, char> {
        latin1()
    }

    /// Codes 0x20–0xFF mapped as Latin-1 (identity to Unicode).
    fn latin1() -> HashMap<u32, char> {
        let mut m = HashMap::new();
        for code in 0x20u32..=0xFF {
            if let Some(c) = char::from_u32(code) {
                m.insert(code, c);
            }
        }
        m
    }

    /// A minimal Symbol-font subset (common math/greek glyphs seen in PDFs).
    fn symbol_table() -> HashMap<u32, char> {
        let pairs: [(u32, u32); 8] = [
            (0x61, 0x03B1), // alpha
            (0x62, 0x03B2), // beta
            (0x64, 0x03B4), // delta
            (0x70, 0x03C0), // pi
            (0xB7, 0x2022), // bullet
            (0xE0, 0x2666), // diamond
            (0xAE, 0x2192), // arrowright
            (0xAC, 0x2190), // arrowleft
        ];
        pairs
            .iter()
            .filter_map(|&(code, cp)| char::from_u32(cp).map(|c| (code, c)))
            .collect()
    }

    /// A minimal ZapfDingbats subset (bullets/arrows commonly used as markers).
    fn zapf_table() -> HashMap<u32, char> {
        let pairs: [(u32, u32); 6] = [
            (0x6C, 0x25CF), // filled circle bullet
            (0x6E, 0x25A0), // filled square
            (0xA8, 0x2022), // bullet
            (0xE0, 0x2192), // arrow-like
            (0x4C, 0x2764), // heart-ish
            (0x48, 0x2666), // diamond
        ];
        pairs
            .iter()
            .filter_map(|&(code, cp)| char::from_u32(cp).map(|c| (code, c)))
            .collect()
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn winansi_maps_bullet_and_dashes() {
            let m = win_ansi();
            assert_eq!(m.get(&0x95), Some(&'\u{2022}'));
            assert_eq!(m.get(&0x96), Some(&'\u{2013}'));
            assert_eq!(m.get(&0x97), Some(&'\u{2014}'));
            assert_eq!(m.get(&0x41), Some(&'A'));
        }

        #[test]
        fn glyph_names_resolve() {
            assert_eq!(glyph_to_char("bullet"), Some('\u{2022}'));
            assert_eq!(glyph_to_char("uni20AC"), Some('\u{20AC}'));
            assert_eq!(glyph_to_char("A"), Some('A'));
            assert_eq!(glyph_to_char("emdash"), Some('\u{2014}'));
        }
    }
}

// ---------------------------------------------------------------------------
// ToUnicode CMap parsing
// ---------------------------------------------------------------------------

mod cmap {
    use std::collections::HashMap;

    enum Token {
        Hex(Vec<u8>),
        ArrayOpen,
        ArrayClose,
        Keyword(String),
    }

    /// Parse a `/ToUnicode` CMap stream into a `code -> string` map, handling
    /// `beginbfchar`/`endbfchar` and `beginbfrange`/`endbfrange`.
    pub fn parse(data: &[u8]) -> HashMap<u32, String> {
        let tokens = tokenize(data);
        let mut map = HashMap::new();
        let mut i = 0;
        while i < tokens.len() {
            match &tokens[i] {
                Token::Keyword(k) if k == "beginbfchar" => {
                    i += 1;
                    while i < tokens.len() {
                        if let Token::Keyword(e) = &tokens[i] {
                            if e == "endbfchar" {
                                break;
                            }
                        }
                        if let (Token::Hex(src), Some(Token::Hex(dst))) =
                            (&tokens[i], tokens.get(i + 1))
                        {
                            map.insert(code(src), utf16be(dst));
                            i += 2;
                        } else {
                            i += 1;
                        }
                    }
                    i += 1; // skip endbfchar
                }
                Token::Keyword(k) if k == "beginbfrange" => {
                    i += 1;
                    while i < tokens.len() {
                        if let Token::Keyword(e) = &tokens[i] {
                            if e == "endbfrange" {
                                break;
                            }
                        }
                        match (tokens.get(i), tokens.get(i + 1), tokens.get(i + 2)) {
                            (Some(Token::Hex(lo)), Some(Token::Hex(hi)), Some(Token::Hex(dst))) => {
                                let (lo, hi) = (code(lo), code(hi));
                                let base = utf16be_units(dst);
                                for (n, c) in (lo..=hi).enumerate() {
                                    map.insert(c, units_to_string_incremented(&base, n as u32));
                                }
                                i += 3;
                            }
                            (Some(Token::Hex(lo)), Some(Token::Hex(_hi)), Some(Token::ArrayOpen)) => {
                                let lo = code(lo);
                                i += 3; // skip lo, hi, '['
                                let mut n = 0u32;
                                while i < tokens.len() {
                                    match &tokens[i] {
                                        Token::ArrayClose => {
                                            i += 1;
                                            break;
                                        }
                                        Token::Hex(dst) => {
                                            map.insert(lo + n, utf16be(dst));
                                            n += 1;
                                            i += 1;
                                        }
                                        _ => i += 1,
                                    }
                                }
                            }
                            _ => i += 1,
                        }
                    }
                    i += 1; // skip endbfrange
                }
                _ => i += 1,
            }
        }
        map
    }

    fn tokenize(data: &[u8]) -> Vec<Token> {
        let mut tokens = Vec::new();
        let mut i = 0;
        while i < data.len() {
            let b = data[i];
            match b {
                b'<' => {
                    let mut hex = String::new();
                    i += 1;
                    while i < data.len() && data[i] != b'>' {
                        if !data[i].is_ascii_whitespace() {
                            hex.push(data[i] as char);
                        }
                        i += 1;
                    }
                    i += 1; // consume '>'
                    tokens.push(Token::Hex(hex_to_bytes(&hex)));
                }
                b'[' => {
                    tokens.push(Token::ArrayOpen);
                    i += 1;
                }
                b']' => {
                    tokens.push(Token::ArrayClose);
                    i += 1;
                }
                _ if b.is_ascii_alphabetic() => {
                    let mut kw = String::new();
                    while i < data.len()
                        && (data[i].is_ascii_alphanumeric() || data[i] == b'*')
                    {
                        kw.push(data[i] as char);
                        i += 1;
                    }
                    tokens.push(Token::Keyword(kw));
                }
                _ => i += 1,
            }
        }
        tokens
    }

    fn hex_to_bytes(hex: &str) -> Vec<u8> {
        let mut h = hex.to_string();
        if h.len() % 2 == 1 {
            h.push('0');
        }
        (0..h.len())
            .step_by(2)
            .filter_map(|i| u8::from_str_radix(&h[i..i + 2], 16).ok())
            .collect()
    }

    fn code(bytes: &[u8]) -> u32 {
        let mut c = 0u32;
        for &b in bytes {
            c = (c << 8) | b as u32;
        }
        c
    }

    fn utf16be_units(bytes: &[u8]) -> Vec<u16> {
        bytes
            .chunks(2)
            .map(|c| {
                let hi = c[0] as u16;
                let lo = *c.get(1).unwrap_or(&0) as u16;
                (hi << 8) | lo
            })
            .collect()
    }

    fn utf16be(bytes: &[u8]) -> String {
        String::from_utf16_lossy(&utf16be_units(bytes))
    }

    /// Increment the last UTF-16 code unit by `n` (per PDF bfrange semantics)
    /// and decode the result.
    fn units_to_string_incremented(units: &[u16], n: u32) -> String {
        let mut u = units.to_vec();
        if let Some(last) = u.last_mut() {
            *last = last.wrapping_add(n as u16);
        }
        String::from_utf16_lossy(&u)
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn parses_bfchar_single_byte() {
            let cmap = b"2 beginbfchar\n<41> <0041>\n<42> <0042>\nendbfchar";
            let map = parse(cmap);
            assert_eq!(map.get(&0x41).map(String::as_str), Some("A"));
            assert_eq!(map.get(&0x42).map(String::as_str), Some("B"));
        }

        #[test]
        fn parses_bfchar_two_byte() {
            let cmap = b"1 beginbfchar\n<0003> <0048>\nendbfchar";
            let map = parse(cmap);
            assert_eq!(map.get(&0x0003).map(String::as_str), Some("H"));
        }

        #[test]
        fn parses_bfrange_incrementing() {
            let cmap = b"1 beginbfrange\n<0041> <0043> <0061>\nendbfrange";
            let map = parse(cmap);
            assert_eq!(map.get(&0x41).map(String::as_str), Some("a"));
            assert_eq!(map.get(&0x42).map(String::as_str), Some("b"));
            assert_eq!(map.get(&0x43).map(String::as_str), Some("c"));
        }

        #[test]
        fn parses_bfrange_after_preamble() {
            // A realistic ToUnicode with a dict/codespace preamble before the
            // bfrange block (regression for a token double-increment bug).
            let cmap = b"/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n<< /Registry (TTX+0) /Ordering (T1) /Supplement 0 >> def\n1 begincodespacerange\n<0000><FFFF>\nendcodespacerange\n2 beginbfrange\n<0033><0033><0050>\n<0055><0055><0072>\nendbfrange\nendcmap";
            let map = parse(cmap);
            assert_eq!(map.get(&0x33).map(String::as_str), Some("P"));
            assert_eq!(map.get(&0x55).map(String::as_str), Some("r"));
        }
    }
}

// ---------------------------------------------------------------------------
// Wire serialization
// ---------------------------------------------------------------------------

mod wire {
    use super::{PageData, Prim};

    const TAG_TEXT: u8 = 1;
    const TAG_FILL: u8 = 2;
    const TAG_STROKE: u8 = 3;
    const TAG_IMAGE: u8 = 4;

    /// Serialize a page into a compact little-endian buffer:
    ///
    /// ```text
    /// header: f32 pageWidth, f32 pageHeight, u32 primitiveCount
    /// per primitive: u8 tag, then payload
    ///   1 Text:   f32 x, f32 y, f32 size, u32 argb, u16 len, [utf8 bytes]
    ///   2 Fill:   u32 argb, u8 evenOdd, u16 nPts, [f32 x, f32 y]...
    ///   3 Stroke: u32 argb, f32 width, u8 nDash, [f32 dash]..., f32 phase,
    ///            u16 nPts, [f32 x, f32 y]...
    ///   4 Image:  6×f32 ctm, u32 w, u32 h, u8 format, u32 len, [bytes]
    ///            (format 0 = RGBA8888, 1 = JPEG)
    /// ```
    pub fn serialize(page: &PageData) -> Vec<u8> {
        let mut buf = Vec::new();
        buf.extend_from_slice(&page.width.to_le_bytes());
        buf.extend_from_slice(&page.height.to_le_bytes());
        buf.extend_from_slice(&(page.prims.len() as u32).to_le_bytes());
        for prim in &page.prims {
            match prim {
                Prim::Text {
                    x,
                    y,
                    size,
                    argb,
                    text,
                } => {
                    buf.push(TAG_TEXT);
                    buf.extend_from_slice(&x.to_le_bytes());
                    buf.extend_from_slice(&y.to_le_bytes());
                    buf.extend_from_slice(&size.to_le_bytes());
                    buf.extend_from_slice(&argb.to_le_bytes());
                    let bytes = text.as_bytes();
                    let len = bytes.len().min(u16::MAX as usize);
                    buf.extend_from_slice(&(len as u16).to_le_bytes());
                    buf.extend_from_slice(&bytes[..len]);
                }
                Prim::Fill { argb, even_odd, pts } => {
                    buf.push(TAG_FILL);
                    buf.extend_from_slice(&argb.to_le_bytes());
                    buf.push(if *even_odd { 1 } else { 0 });
                    write_points(&mut buf, pts);
                }
                Prim::Stroke {
                    argb,
                    width,
                    dash,
                    dash_phase,
                    pts,
                } => {
                    buf.push(TAG_STROKE);
                    buf.extend_from_slice(&argb.to_le_bytes());
                    buf.extend_from_slice(&width.to_le_bytes());
                    let n = dash.len().min(u8::MAX as usize);
                    buf.push(n as u8);
                    for d in &dash[..n] {
                        buf.extend_from_slice(&d.to_le_bytes());
                    }
                    buf.extend_from_slice(&dash_phase.to_le_bytes());
                    write_points(&mut buf, pts);
                }
                Prim::Image {
                    ctm,
                    w,
                    h,
                    format,
                    data,
                } => {
                    buf.push(TAG_IMAGE);
                    for v in ctm {
                        buf.extend_from_slice(&(*v as f32).to_le_bytes());
                    }
                    buf.extend_from_slice(&w.to_le_bytes());
                    buf.extend_from_slice(&h.to_le_bytes());
                    buf.push(*format);
                    buf.extend_from_slice(&(data.len() as u32).to_le_bytes());
                    buf.extend_from_slice(data);
                }
            }
        }
        buf
    }

    fn write_points(buf: &mut Vec<u8>, pts: &[(f32, f32)]) {
        let n = pts.len().min(u16::MAX as usize);
        buf.extend_from_slice(&(n as u16).to_le_bytes());
        for &(x, y) in &pts[..n] {
            buf.extend_from_slice(&x.to_le_bytes());
            buf.extend_from_slice(&y.to_le_bytes());
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        /// Minimal Kotlin-equivalent decoder used to round-trip the wire format.
        struct Reader<'a> {
            buf: &'a [u8],
            pos: usize,
        }
        impl<'a> Reader<'a> {
            fn u8(&mut self) -> u8 {
                let v = self.buf[self.pos];
                self.pos += 1;
                v
            }
            fn u16(&mut self) -> u16 {
                let v = u16::from_le_bytes([self.buf[self.pos], self.buf[self.pos + 1]]);
                self.pos += 2;
                v
            }
            fn u32(&mut self) -> u32 {
                let v = u32::from_le_bytes(self.buf[self.pos..self.pos + 4].try_into().unwrap());
                self.pos += 4;
                v
            }
            fn f32(&mut self) -> f32 {
                let v = f32::from_le_bytes(self.buf[self.pos..self.pos + 4].try_into().unwrap());
                self.pos += 4;
                v
            }
        }

        #[test]
        fn round_trips_all_primitives() {
            let page = PageData {
                width: 612.0,
                height: 792.0,
                prims: vec![
                    Prim::Text {
                        x: 10.0,
                        y: 20.0,
                        size: 12.0,
                        argb: 0xFF112233,
                        text: "Hé".to_string(),
                    },
                    Prim::Fill {
                        argb: 0xFFAABBCC,
                        even_odd: true,
                        pts: vec![(0.0, 0.0), (1.0, 0.0), (1.0, 1.0)],
                    },
                    Prim::Stroke {
                        argb: 0xFF010203,
                        width: 2.5,
                        dash: vec![3.0, 2.0],
                        dash_phase: 1.0,
                        pts: vec![(3.0, 4.0), (5.0, 6.0)],
                    },
                ],
            };
            let buf = serialize(&page);
            let mut r = Reader { buf: &buf, pos: 0 };
            assert_eq!(r.f32(), 612.0);
            assert_eq!(r.f32(), 792.0);
            assert_eq!(r.u32(), 3);

            assert_eq!(r.u8(), TAG_TEXT);
            assert_eq!(r.f32(), 10.0);
            assert_eq!(r.f32(), 20.0);
            assert_eq!(r.f32(), 12.0);
            assert_eq!(r.u32(), 0xFF112233);
            let len = r.u16() as usize;
            let s = std::str::from_utf8(&buf[r.pos..r.pos + len]).unwrap();
            assert_eq!(s, "Hé");
            r.pos += len;

            assert_eq!(r.u8(), TAG_FILL);
            assert_eq!(r.u32(), 0xFFAABBCC);
            assert_eq!(r.u8(), 1); // even-odd
            assert_eq!(r.u16(), 3);
            r.pos += 3 * 8;

            assert_eq!(r.u8(), TAG_STROKE);
            assert_eq!(r.u32(), 0xFF010203);
            assert_eq!(r.f32(), 2.5);
            assert_eq!(r.u8(), 2); // dash count
            assert_eq!(r.f32(), 3.0);
            assert_eq!(r.f32(), 2.0);
            assert_eq!(r.f32(), 1.0); // phase
            assert_eq!(r.u16(), 2);
        }
    }
}

// ---------------------------------------------------------------------------
// JNI bindings
// ---------------------------------------------------------------------------

#[cfg(not(test))]
mod jni_bindings {
    use super::*;
    use jni::objects::{JByteArray, JClass, JFloatArray, JString};
    use jni::sys::{jbyteArray, jboolean, jfloat, jint, jlong, jstring};
    use jni::JNIEnv;

    /// `PdfNative.openDocument(byte[]) -> long`. Returns a non-zero handle, or
    /// 0 on parse failure / encrypted document.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_openDocument<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        data: JByteArray<'local>,
    ) -> jlong {
        let bytes = match env.convert_byte_array(&data) {
            Ok(b) => b,
            Err(_) => return 0,
        };
        open_document(&bytes) as jlong
    }

    /// `PdfNative.openDocumentWithPassword(byte[], String) -> long`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_openDocumentWithPassword<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        data: JByteArray<'local>,
        password: JString<'local>,
    ) -> jlong {
        let bytes = match env.convert_byte_array(&data) {
            Ok(b) => b,
            Err(_) => return 0,
        };
        let pw = jstr(&mut env, &password);
        open_document_pw(&bytes, pw.as_bytes()) as jlong
    }

    /// `PdfNative.pdfPasswordState(byte[]) -> int` (0 none, 1 needs pw, 2 unsupported).
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_pdfPasswordState<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        data: JByteArray<'local>,
    ) -> jint {
        let bytes = match env.convert_byte_array(&data) {
            Ok(b) => b,
            Err(_) => return 0,
        };
        pdf_password_state(&bytes)
    }

    /// `PdfNative.saveEncrypted(long, String, String) -> byte[]`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_saveEncrypted<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        user_pw: JString<'local>,
        owner_pw: JString<'local>,
    ) -> jbyteArray {
        let u = jstr(&mut env, &user_pw);
        let o = jstr(&mut env, &owner_pw);
        bytes_or_null(&env, save_encrypted(handle as i64, u.as_bytes(), o.as_bytes()))
    }

    /// `PdfNative.getPageCount(long) -> int`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_getPageCount<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) -> jint {
        page_count(handle as i64)
    }

    /// `PdfNative.renderPage(long, int) -> byte[]`. Serialized primitives, or
    /// `null` on error.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_renderPage<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        index: jint,
    ) -> jbyteArray {
        let null = std::ptr::null_mut();
        let buf = match render_page(handle as i64, index) {
            Some(b) => b,
            None => return null,
        };
        match env.byte_array_from_slice(&buf) {
            Ok(arr) => arr.into_raw(),
            Err(_) => null,
        }
    }

    /// `PdfNative.closeDocument(long)`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_closeDocument<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) {
        close_document(handle as i64);
    }

    /// `PdfNative.createEmptyDocument() -> long`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_createEmptyDocument<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
    ) -> jlong {
        create_empty_document()
    }

    /// `PdfNative.appendPdf(long, byte[]) -> int` (pages added).
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_appendPdf<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        data: JByteArray<'local>,
    ) -> jint {
        let bytes = match env.convert_byte_array(&data) {
            Ok(b) => b,
            Err(_) => return 0,
        };
        append_pdf(handle as i64, &bytes)
    }

    /// `PdfNative.appendImagePage(long, byte[], int, int) -> int`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_appendImagePage<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        jpeg: JByteArray<'local>,
        w: jint,
        h: jint,
    ) -> jint {
        let bytes = match env.convert_byte_array(&jpeg) {
            Ok(b) => b,
            Err(_) => return 0,
        };
        append_image_page(handle as i64, &bytes, w as u32, h as u32)
    }

    /// `PdfNative.movePage(long, int, int) -> boolean`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_movePage<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        from: jint,
        to: jint,
    ) -> jboolean {
        move_page(handle as i64, from.max(0) as usize, to.max(0) as usize) as jboolean
    }

    /// `PdfNative.removePage(long, int) -> boolean`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_removePage<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        index: jint,
    ) -> jboolean {
        remove_page(handle as i64, index.max(0) as usize) as jboolean
    }

    /// `PdfNative.rotatePage(long, int, int) -> boolean`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_rotatePage<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        index: jint,
        delta: jint,
    ) -> jboolean {
        rotate_page(handle as i64, index, delta) as jboolean
    }

    /// `PdfNative.extractPage(long, int) -> byte[]`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_extractPage<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        index: jint,
    ) -> jbyteArray {
        bytes_or_null(&env, extract_page(handle as i64, index))
    }

    fn bytes_or_null<'local>(env: &JNIEnv<'local>, data: Option<Vec<u8>>) -> jbyteArray {
        let null = std::ptr::null_mut();
        match data {
            Some(b) => match env.byte_array_from_slice(&b) {
                Ok(arr) => arr.into_raw(),
                Err(_) => null,
            },
            None => null,
        }
    }

    fn jstr(env: &mut JNIEnv, s: &JString) -> String {
        env.get_string(s).map(|s| s.into()).unwrap_or_default()
    }

    /// `PdfNative.listAnnotations(long, int) -> byte[]`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_listAnnotations<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
    ) -> jbyteArray {
        bytes_or_null(&env, list_annotations(handle as i64, page))
    }

    /// `PdfNative.listFormFields(long, int) -> byte[]`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_listFormFields<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
    ) -> jbyteArray {
        bytes_or_null(&env, list_form_fields(handle as i64, page))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_listLinks<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
    ) -> jbyteArray {
        bytes_or_null(&env, list_links(handle as i64, page))
    }

    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addTextAnnotation<'local>(
        mut env: JNIEnv<'local>,
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
        let t = jstr(&mut env, &text);
        add_free_text(
            handle as i64,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
            size as f64,
            &t,
        )
        .unwrap_or(0)
    }

    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addHighlight<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        x0: jfloat,
        y0: jfloat,
        x1: jfloat,
        y1: jfloat,
        argb: jint,
    ) -> jlong {
        add_highlight(
            handle as i64,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
        )
        .unwrap_or(0)
    }

    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addTextMarkup<'local>(
        _env: JNIEnv<'local>,
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
        add_text_markup(
            handle as i64,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
            kind,
        )
        .unwrap_or(0)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addNote<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        x: jfloat,
        y: jfloat,
        argb: jint,
        text: JString<'local>,
    ) -> jlong {
        let t = jstr(&mut env, &text);
        add_note(handle as i64, page, x as f64, y as f64, argb as u32, &t).unwrap_or(0)
    }

    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addCallout<'local>(
        mut env: JNIEnv<'local>,
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
        let t = jstr(&mut env, &text);
        add_callout(
            handle as i64,
            page,
            ax as f64,
            ay as f64,
            bx as f64,
            by as f64,
            argb as u32,
            size as f64,
            &t,
        )
        .unwrap_or(0)
    }

    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addRectAnnotation<'local>(
        _env: JNIEnv<'local>,
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
        add_square(
            handle as i64,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
            line_width as f64,
            fill != 0,
        )
        .unwrap_or(0)
    }

    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addCircleAnnotation<'local>(
        _env: JNIEnv<'local>,
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
        add_circle(
            handle as i64,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
            line_width as f64,
            fill != 0,
        )
        .unwrap_or(0)
    }

    /// `PdfNative.addPolyAnnotation(long, int, int argb, float width, bool fill, bool closed, float[] pts)`.
    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addPolyAnnotation<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        argb: jint,
        line_width: jfloat,
        fill: jboolean,
        closed: jboolean,
        pts: JFloatArray<'local>,
    ) -> jlong {
        let len = env.get_array_length(&pts).unwrap_or(0) as usize;
        let mut buf = vec![0f32; len];
        if env.get_float_array_region(&pts, 0, &mut buf).is_err() {
            return 0;
        }
        add_poly(
            handle as i64,
            page,
            &buf,
            argb as u32,
            line_width as f64,
            fill != 0,
            closed != 0,
        )
        .unwrap_or(0)
    }

    /// `PdfNative.addInkAnnotation(long, int, int argb, float width, float[] pts)`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addInkAnnotation<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        argb: jint,
        line_width: jfloat,
        pts: JFloatArray<'local>,
    ) -> jlong {
        let len = env.get_array_length(&pts).unwrap_or(0) as usize;
        let mut buf = vec![0f32; len];
        if env.get_float_array_region(&pts, 0, &mut buf).is_err() {
            return 0;
        }
        add_ink(handle as i64, page, argb as u32, line_width as f64, &buf).unwrap_or(0)
    }

    /// `PdfNative.addImageStamp(long, int, rect, imgW, imgH, byte[] jpeg)`.
    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addImageStamp<'local>(
        env: JNIEnv<'local>,
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
        let bytes = match env.convert_byte_array(&jpeg) {
            Ok(b) => b,
            Err(_) => return 0,
        };
        add_stamp(
            handle as i64,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            img_w as u32,
            img_h as u32,
            &bytes,
        )
        .unwrap_or(0)
    }

    #[allow(clippy::too_many_arguments)]
    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_updateAnnotationRect<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        annot_id: jlong,
        x0: jfloat,
        y0: jfloat,
        x1: jfloat,
        y1: jfloat,
    ) -> jboolean {
        update_annotation_rect(
            handle as i64,
            page,
            annot_id,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
        ) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_updateTextAnnotation<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        annot_id: jlong,
        text: JString<'local>,
    ) -> jboolean {
        let t = jstr(&mut env, &text);
        update_free_text(handle as i64, annot_id, &t) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_deleteAnnotation<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        annot_id: jlong,
    ) -> jboolean {
        delete_annotation(handle as i64, page, annot_id) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_detachAnnotation<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        annot_id: jlong,
    ) -> jboolean {
        detach_annotation(handle as i64, page, annot_id) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_reattachAnnotation<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        annot_id: jlong,
    ) -> jboolean {
        reattach_annotation(handle as i64, page, annot_id) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_duplicateAnnotation<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        annot_id: jlong,
        dx: jfloat,
        dy: jfloat,
    ) -> jlong {
        duplicate_annotation(handle as i64, page, annot_id, dx as f64, dy as f64)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_setTextField<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        widget_id: jlong,
        value: JString<'local>,
    ) -> jboolean {
        let v = jstr(&mut env, &value);
        set_text_field(handle as i64, widget_id, &v) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_setCheckbox<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        widget_id: jlong,
        on: jboolean,
    ) -> jboolean {
        set_checkbox(handle as i64, widget_id, on != 0) as jboolean
    }

    /// `PdfNative.saveDocument(long) -> byte[]`. Serialized modified PDF.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_saveDocument<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) -> jbyteArray {
        bytes_or_null(&env, save_document(handle as i64))
    }

    /// `PdfNative.prepareSignature(long, String, int) -> byte[]`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_prepareSignature<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        name: JString<'local>,
        contents_bytes: jint,
    ) -> jbyteArray {
        let n = jstr(&mut env, &name);
        bytes_or_null(&env, prepare_signature(handle as i64, &n, contents_bytes.max(0) as usize))
    }

    /// `PdfNative.saveCompressed(long) -> byte[]`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_saveCompressed<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) -> jbyteArray {
        bytes_or_null(&env, save_compressed(handle as i64))
    }

    /// `PdfNative.flattenDocument(long) -> boolean`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_flattenDocument<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) -> jboolean {
        flatten_document(handle as i64) as jboolean
    }

    /// `PdfNative.applyRedactions(long) -> boolean`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_applyRedactions<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) -> jboolean {
        apply_redactions(handle as i64) as jboolean
    }

    /// `PdfNative.hasRedactions(long) -> boolean`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_hasRedactions<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) -> jboolean {
        has_redactions(handle as i64) as jboolean
    }

    /// `PdfNative.addRedaction(long, int, f,f,f,f) -> long`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addRedaction<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        x0: jfloat,
        y0: jfloat,
        x1: jfloat,
        y1: jfloat,
    ) -> jlong {
        add_redaction(handle as i64, page, [x0 as f64, y0 as f64, x1 as f64, y1 as f64]).unwrap_or(0)
    }

    /// `PdfNative.extractText(long) -> String` (null on failure).
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_extractText<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) -> jstring {
        match document_text(handle as i64).and_then(|s| env.new_string(s).ok()) {
            Some(s) => s.into_raw(),
            None => std::ptr::null_mut(),
        }
    }

    /// `PdfNative.listOutline(long) -> byte[]`. Serialized document outline.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_listOutline<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) -> jbyteArray {
        bytes_or_null(&env, list_outline(handle as i64))
    }

    /// `PdfNative.searchDocument(long, String) -> byte[]`. Serialized matches.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_searchDocument<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        query: JString<'local>,
    ) -> jbyteArray {
        let q = jstr(&mut env, &query);
        bytes_or_null(&env, search_document(handle as i64, &q))
    }

    /// `PdfNative.buildSearchIndex(long)`. Prebuilds the text index so the first
    /// search is instant; safe to call on a background thread.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_buildSearchIndex<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) {
        let _ = ensure_index(handle as i64);
    }
}

// ---------------------------------------------------------------------------
// Interpreter tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use lopdf::content::{Content, Operation};
    use lopdf::{dictionary, Object, Stream};

    /// Build a one-page PDF in memory with a filled rectangle and one text run,
    /// then check the interpreted page size and primitives.
    #[test]
    fn interprets_rect_and_text() {
        let mut doc = Document::with_version("1.5");

        let content = Content {
            operations: vec![
                Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
                Operation::new("re", vec![100.into(), 100.into(), 50.into(), 40.into()]),
                Operation::new("f", vec![]),
                Operation::new("BT", vec![]),
                Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
                Operation::new("Td", vec![72.into(), 700.into()]),
                Operation::new("Tj", vec![Object::string_literal("Hi")]),
                Operation::new("ET", vec![]),
            ],
        };
        let content_data = content.encode().unwrap();
        let content_id = doc.add_object(Stream::new(dictionary! {}, content_data));

        let font_id = doc.add_object(dictionary! {
            "Type" => "Font",
            "Subtype" => "Type1",
            "BaseFont" => "Helvetica",
        });
        let resources = dictionary! {
            "Font" => dictionary! { "F1" => font_id },
        };

        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => content_id,
            "Resources" => resources,
        });
        let pages = dictionary! {
            "Type" => "Pages",
            "Kids" => vec![page_id.into()],
            "Count" => 1,
        };
        doc.objects.insert(pages_id, Object::Dictionary(pages));
        let catalog_id = doc.add_object(dictionary! {
            "Type" => "Catalog",
            "Pages" => pages_id,
        });
        doc.trailer.set("Root", catalog_id);

        let page = interpret_page(&doc, page_id).expect("interpret should succeed");
        assert_eq!(page.width, 612.0);
        assert_eq!(page.height, 792.0);

        let fills: Vec<&Prim> = page
            .prims
            .iter()
            .filter(|p| matches!(p, Prim::Fill { .. }))
            .collect();
        assert_eq!(fills.len(), 1, "expected one filled rectangle");
        if let Prim::Fill { argb, pts, .. } = fills[0] {
            assert_eq!(*argb, 0xFFFF0000, "fill should be red");
            assert!(pts.len() >= 4, "rectangle should have >=4 points");
            assert_eq!(pts[0], (100.0, 100.0));
        }

        let texts: Vec<&Prim> = page
            .prims
            .iter()
            .filter(|p| matches!(p, Prim::Text { .. }))
            .collect();
        // Per-glyph emission: "Hi" -> two glyph primitives.
        assert_eq!(texts.len(), 2, "expected two glyph runs for \"Hi\"");
        if let Prim::Text { x, y, size, text, .. } = texts[0] {
            assert_eq!(text, "H");
            assert_eq!(*x, 72.0);
            assert_eq!(*y, 700.0);
            assert_eq!(*size, 12.0);
        }
        if let Prim::Text { text, .. } = texts[1] {
            assert_eq!(text, "i");
        }
    }

    /// Two consecutive `Tj` runs on one line must not stack at the same x: the
    /// second run is offset by the first run's glyph-width advance.
    #[test]
    fn text_advances_by_glyph_widths() {
        let fi = FontInfo {
            two_byte: false,
            to_unicode: None,
            encoding: HashMap::new(),
            cmap_uni: HashMap::new(),
            // 'A' (0x41) and 'B' (0x42) each 500 glyph units => 0.5.
            widths: HashMap::from([(0x41, 0.5), (0x42, 0.5)]),
            default_width: 0.5,
        };
        let mut fonts = HashMap::new();
        fonts.insert(b"F1".to_vec(), fi);

        let mut gs = GraphicsState::default();
        gs.font_key = b"F1".to_vec();
        gs.font_size = 10.0;

        let mut prims = Vec::new();
        let mut tm = translate(0.0, 100.0);

        let adv1 = show_string(&mut prims, &gs, &fonts, &tm, b"AB");
        tm = mat_mul(&translate(adv1, 0.0), &tm);
        let _adv2 = show_string(&mut prims, &gs, &fonts, &tm, b"AB");

        // Per-glyph emission: run "AB" -> 2 prims; advance = 2*0.5*10 = 10.
        assert!((adv1 - 10.0).abs() < 1e-6, "advance was {adv1}");
        let xs: Vec<f32> = prims
            .iter()
            .filter_map(|p| match p {
                Prim::Text { x, .. } => Some(*x),
                _ => None,
            })
            .collect();
        assert_eq!(xs.len(), 4, "expected 4 glyphs across 2 runs");
        assert_eq!(xs[0], 0.0); // first 'A'
        assert_eq!(xs[1], 5.0); // 'B' advanced by 0.5*10
        assert!((xs[2] - 10.0).abs() < 1e-4, "second run 'A' x was {}", xs[2]);
    }

    /// Invisible text (render mode 3) advances the cursor but emits no glyphs.
    #[test]
    fn invisible_text_not_emitted() {
        let fi = FontInfo {
            two_byte: false,
            to_unicode: None,
            encoding: HashMap::new(),
            cmap_uni: HashMap::new(),
            widths: HashMap::new(),
            default_width: 0.5,
        };
        let mut fonts = HashMap::new();
        fonts.insert(b"F1".to_vec(), fi);
        let mut gs = GraphicsState::default();
        gs.font_key = b"F1".to_vec();
        gs.font_size = 10.0;
        gs.render_mode = 3;
        let mut prims = Vec::new();
        let adv = show_string(&mut prims, &gs, &fonts, &IDENTITY, b"hidden");
        assert!(adv > 0.0);
        assert!(prims.is_empty(), "mode-3 text should not be drawn");
    }

    /// Round-trip a full open -> count -> render -> close cycle via the byte API.
    #[test]
    fn open_render_close_roundtrip() {
        let mut doc = Document::with_version("1.5");
        let content = Content {
            operations: vec![
                Operation::new("re", vec![0.into(), 0.into(), 10.into(), 10.into()]),
                Operation::new("f", vec![]),
            ],
        };
        let content_data = content.encode().unwrap();
        let content_id = doc.add_object(Stream::new(dictionary! {}, content_data));
        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 200.into(), 300.into()],
            "Contents" => content_id,
            "Resources" => dictionary! {},
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages",
                "Kids" => vec![page_id.into()],
                "Count" => 1,
            }),
        );
        let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", catalog_id);

        let mut bytes = Vec::new();
        doc.save_to(&mut bytes).unwrap();

        let handle = open_document(&bytes);
        assert_ne!(handle, 0);
        assert_eq!(page_count(handle), 1);
        let buf = render_page(handle, 0).expect("render should succeed");
        // Header: width, height, count.
        let width = f32::from_le_bytes(buf[0..4].try_into().unwrap());
        let height = f32::from_le_bytes(buf[4..8].try_into().unwrap());
        assert_eq!(width, 200.0);
        assert_eq!(height, 300.0);
        close_document(handle);
        assert_eq!(page_count(handle), 0);
    }
}

#[cfg(test)]
mod edit_render_tests {
    use super::*;
    use lopdf::{dictionary, Stream};

    fn one_page_pdf() -> Vec<u8> {
        let mut doc = Document::with_version("1.5");
        let content = lopdf::content::Content {
            operations: vec![lopdf::content::Operation::new("re", vec![0.into(), 0.into(), 10.into(), 10.into()])],
        };
        let cid = doc.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));
        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => cid, "Resources" => dictionary! {},
        });
        doc.objects.insert(pages_id, Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
        }));
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", cat);
        let mut bytes = Vec::new();
        doc.save_to(&mut bytes).unwrap();
        bytes
    }

    #[test]
    fn added_rect_annotation_renders() {
        let bytes = one_page_pdf();
        let handle = open_document(&bytes);
        assert_ne!(handle, 0);
        let id = add_square(handle, 0, [100.0, 100.0, 300.0, 250.0], 0xFFFF0000, 2.0, false);
        assert!(id.is_some() && id != Some(0), "add_square failed: {id:?}");

        let buf = render_page(handle, 0).expect("render");
        // Count stroke primitives (tag 3).
        let count = u32::from_le_bytes(buf[8..12].try_into().unwrap());
        let mut strokes = 0;
        let mut pos = 12;
        for _ in 0..count {
            let tag = buf[pos]; pos += 1;
            match tag {
                1 => { pos += 14; let l = u16::from_le_bytes(buf[pos-2..pos].try_into().unwrap()) as usize; pos += l; }
                2 => { pos += 5; let n = u16::from_le_bytes(buf[pos-2..pos].try_into().unwrap()) as usize; pos += n*8; }
                3 => { strokes += 1; pos += 9; let n = u16::from_le_bytes(buf[pos-2..pos].try_into().unwrap()) as usize; pos += n*8; }
                4 => { pos += 24; let a=u32::from_le_bytes(buf[pos-4..pos].try_into().unwrap()) as usize; pos += a; }
                _ => panic!("bad tag {tag}"),
            }
        }
        println!("prims={count} strokes={strokes}");
        assert!(strokes >= 1, "expected the annotation stroke to render, got {strokes}");
        close_document(handle);
    }
}
