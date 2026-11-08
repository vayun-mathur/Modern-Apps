/// Expand every `/Type /ObjStm` into its contained objects.
///
/// lopdf skips object-stream expansion at load time when the trailer has `/Encrypt`
/// (reader.rs: `if stream.dict.has_type(b"ObjStm") && !is_encrypted`) because the bytes
/// are still ciphertext, and it only auto-decrypts for the EMPTY password. So a document
/// with a real password reached this point with every ObjStm-contained object missing —
/// including `/Root`, `/Pages` and the page dictionaries, which is where §7.5.7 puts them
/// for essentially every modern encrypted PDF. The result was a correct password opening
/// a document with zero pages.
fn expand_object_streams(doc: &mut Document) {
    let mut recovered: Vec<(ObjectId, Object)> = Vec::new();
    for (_, object) in doc.objects.iter() {
        let Ok(stream) = object.as_stream() else {
            continue;
        };
        if !stream.dict.has_type(b"ObjStm") {
            continue;
        }
        let mut stream = stream.clone();
        if let Ok(obj_stream) = lopdf::ObjectStream::new(&mut stream) {
            recovered.extend(obj_stream.objects);
        }
    }
    // Only add, never replace: a top-level definition supersedes one inside an ObjStm.
    for (id, obj) in recovered {
        doc.objects.entry(id).or_insert(obj);
    }
}

/// Which standard-security-handler algorithm to write on save.
#[derive(Clone, Copy, PartialEq)]
pub(crate) enum EncryptAlgo {
    /// RC4-128, revision 3 (V2/R3).
    Rc4_128,
    /// AES-128, revision 4 (V4/R4, AESV2).
    Aes128,
    /// AES-256, revision 6 (V5/R6, AESV3).
    Aes256,
}

/// Cryptographically-secure random bytes for salts/IVs, sourced from the OS
/// CSPRNG. Falls back to md5-based mixing (seed + wall clock) only if the OS
/// RNG is unavailable, preserving the no-panic invariant.
fn rand_bytes<const N: usize>(seed: &[u8]) -> [u8; N] {
    use rand_core::TryRng;
    let mut out = [0u8; N];
    if rand::rngs::SysRng.try_fill_bytes(&mut out).is_ok() {
        return out;
    }
    use md5::{Digest, Md5};
    let t = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let mut ctr: u64 = 0;
    let mut filled = 0;
    while filled < N {
        let mut m = Md5::new();
        m.update(seed);
        m.update(t.to_le_bytes());
        m.update(ctr.to_le_bytes());
        let d: [u8; 16] = m.finalize().into();
        let take = d.len().min(N - filled);
        out[filled..filled + take].copy_from_slice(&d[..take]);
        filled += take;
        ctr += 1;
    }
    out
}

/// Serialize `handle` encrypted with the given passwords, defaulting to AES-128
/// (V4/R4) — modern and widely supported.
pub(crate) fn save_encrypted(handle: i64, user_pw: &[u8], owner_pw: &[u8]) -> Option<Vec<u8>> {
    let bytes = save_document(handle)?;
    encrypt_doc_bytes(&bytes, user_pw, owner_pw, EncryptAlgo::Aes128)
}

/// Encrypt a serialized PDF (`bytes`) with `algo` and the given passwords,
/// returning the re-serialized encrypted document. Testable without the registry.
pub(crate) fn encrypt_doc_bytes(
    bytes: &[u8],
    user_pw: &[u8],
    owner_pw: &[u8],
    algo: EncryptAlgo,
) -> Option<Vec<u8>> {
    let mut doc = Document::load_mem(bytes).ok()?;
    // Ensure an /ID exists (used by RC4/AES-128 key derivation).
    let id0 = ensure_trailer_id(&mut doc, bytes);
    let owner = if owner_pw.is_empty() { user_pw } else { owner_pw };
    let p: i32 = -4; // allow all operations

    // Build the /Encrypt dict and per-object cipher factory.
    let (enc, make_apply): (Dictionary, CryptFnFactory) =
        match algo {
            EncryptAlgo::Rc4_128 => {
                let (n, rev) = (16usize, 3u8);
                let o = crypto::compute_o(owner, user_pw, n, rev);
                let key = crypto::compute_key(user_pw, &o, p, &id0, n, rev, true);
                let u = crypto::compute_u(&key, &id0, rev);
                let mut enc = Dictionary::new();
                enc.set("Filter", name_obj("Standard"));
                enc.set("V", Object::Integer(2));
                enc.set("R", Object::Integer(3));
                enc.set("Length", Object::Integer(128));
                enc.set("P", Object::Integer(p as i64));
                enc.set("O", Object::String(o, lopdf::StringFormat::Literal));
                enc.set("U", Object::String(u, lopdf::StringFormat::Literal));
                let key2 = key.clone();
                let make = move |id: ObjectId| -> CryptFn {
                    let okey = crypto::object_key(&key2, id.0, id.1, n);
                    Box::new(move |d: &[u8]| crypto::rc4(&okey, d))
                };
                (enc, Box::new(make))
            }
            EncryptAlgo::Aes128 => {
                let (n, rev) = (16usize, 4u8);
                let o = crypto::compute_o(owner, user_pw, n, rev);
                let key = crypto::compute_key(user_pw, &o, p, &id0, n, rev, true);
                let u = crypto::compute_u(&key, &id0, rev);
                let mut cf = Dictionary::new();
                let mut stdcf = Dictionary::new();
                stdcf.set("CFM", name_obj("AESV2"));
                stdcf.set("Length", Object::Integer(16));
                cf.set("StdCF", Object::Dictionary(stdcf));
                let mut enc = Dictionary::new();
                enc.set("Filter", name_obj("Standard"));
                enc.set("V", Object::Integer(4));
                enc.set("R", Object::Integer(4));
                enc.set("Length", Object::Integer(128));
                enc.set("P", Object::Integer(p as i64));
                enc.set("CF", Object::Dictionary(cf));
                enc.set("StmF", name_obj("StdCF"));
                enc.set("StrF", name_obj("StdCF"));
                enc.set("O", Object::String(o, lopdf::StringFormat::Literal));
                enc.set("U", Object::String(u, lopdf::StringFormat::Literal));
                let key2 = key.clone();
                let seed = id0.clone();
                let make = move |id: ObjectId| -> CryptFn {
                    let okey = crypto::object_key_aes(&key2, id.0, id.1, n);
                    let seed = seed.clone();
                    Box::new(move |d: &[u8]| {
                        let iv = rand_bytes::<16>(&[&seed[..], d.get(..8).unwrap_or(d)].concat());
                        crypto::aes_cbc_encrypt(&okey, &iv, d).unwrap_or_default()
                    })
                };
                (enc, Box::new(make))
            }
            EncryptAlgo::Aes256 => {
                let rev = 6u8;
                let file_key = rand_bytes::<32>(&id0);
                let salt_bytes = rand_bytes::<32>(&[&id0[..], b"salts"].concat());
                // Split the random salt bytes into four 8-byte salts (derived from
                // salt_bytes, so no hard-coded array flows into the KDF).
                let salts: [[u8; 8]; 4] = std::array::from_fn(|i| {
                    let mut s = [0u8; 8];
                    s.copy_from_slice(&salt_bytes[i * 8..i * 8 + 8]);
                    s
                });
                let (u, ue, o, oe) = crypto::compute_v5(user_pw, owner, &file_key, &salts, rev)?;
                let perms = crypto::compute_perms_v5(&file_key, p)?;
                let mut cf = Dictionary::new();
                let mut stdcf = Dictionary::new();
                stdcf.set("CFM", name_obj("AESV3"));
                stdcf.set("Length", Object::Integer(32));
                cf.set("StdCF", Object::Dictionary(stdcf));
                let mut enc = Dictionary::new();
                enc.set("Filter", name_obj("Standard"));
                enc.set("V", Object::Integer(5));
                enc.set("R", Object::Integer(6));
                enc.set("Length", Object::Integer(256));
                enc.set("P", Object::Integer(p as i64));
                enc.set("CF", Object::Dictionary(cf));
                enc.set("StmF", name_obj("StdCF"));
                enc.set("StrF", name_obj("StdCF"));
                enc.set("O", Object::String(o, lopdf::StringFormat::Literal));
                enc.set("U", Object::String(u, lopdf::StringFormat::Literal));
                enc.set("OE", Object::String(oe, lopdf::StringFormat::Literal));
                enc.set("UE", Object::String(ue, lopdf::StringFormat::Literal));
                enc.set("Perms", Object::String(perms, lopdf::StringFormat::Literal));
                let fk = file_key;
                let seed = id0.clone();
                let make = move |_id: ObjectId| -> CryptFn {
                    // AESV3 uses the file key directly (no per-object key).
                    let seed = seed.clone();
                    Box::new(move |d: &[u8]| {
                        let iv = rand_bytes::<16>(&[&seed[..], d.get(..8).unwrap_or(d)].concat());
                        crypto::aes_cbc_encrypt(&fk, &iv, d).unwrap_or_default()
                    })
                };
                (enc, Box::new(make))
            }
        };

    let enc_id = doc.add_object(enc);
    let ids: Vec<ObjectId> = doc.objects.keys().copied().collect();
    for id in ids {
        if id == enc_id {
            continue;
        }
        let apply = make_apply(id);
        if let Some(obj) = doc.objects.get_mut(&id) {
            crypt_object(obj, &apply);
        }
    }
    doc.trailer.set("Encrypt", Object::Reference(enc_id));

    let mut out = Vec::new();
    doc.save_to(&mut out).ok()?;
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// §7.6.3.3 Algorithm 2 step (d): /P is hashed as 32 bits, so the signed and
    /// unsigned spellings of the same flags must derive the same file key.
    #[test]
    fn permissions_p_reads_both_spellings_of_the_same_flags() {
        let signed = dictionary! { "P" => Object::Integer(-3904) };
        let unsigned = dictionary! { "P" => Object::Integer(4_294_963_392) };
        assert_eq!(permissions_p(&signed), -3904);
        assert_eq!(
            permissions_p(&unsigned),
            -3904,
            "the unsigned spelling is the same 32 bits, not i32::MAX"
        );
        // The four bytes Algorithm 2 actually hashes.
        assert_eq!(
            (permissions_p(&unsigned) as u32).to_le_bytes(),
            (permissions_p(&signed) as u32).to_le_bytes()
        );
        // A missing /P still reads as zero rather than panicking.
        assert_eq!(permissions_p(&Dictionary::new()), 0);
    }

    /// §7.6.5 Table 20: /Identity is the reserved crypt-filter name for data that is
    /// NOT encrypted, and Table 25 gives /CFM /None the same meaning. Decrypting
    /// either turns plaintext into noise for the whole document.
    #[test]
    fn identity_crypt_filters_select_no_cipher() {
        let mut cf = Dictionary::new();
        cf.set("StdCF", dictionary! { "CFM" => name_obj("AESV2") });
        cf.set("NoneCF", dictionary! { "CFM" => name_obj("None") });
        cf.set("Rc4CF", dictionary! { "CFM" => name_obj("V2") });
        let cf = Some(&cf);

        assert!(crypt_filter_method(cf, b"Identity", CryptMethod::AesV2).is_none());
        assert!(crypt_filter_method(cf, b"NoneCF", CryptMethod::AesV2).is_none());
        assert!(matches!(
            crypt_filter_method(cf, b"StdCF", CryptMethod::Rc4),
            Some(CryptMethod::AesV2)
        ));
        // A crypt filter may differ from the default: /StmF and /StrF are independent.
        assert!(matches!(
            crypt_filter_method(cf, b"Rc4CF", CryptMethod::AesV2),
            Some(CryptMethod::Rc4)
        ));
        // An unresolvable name keeps the previous behaviour: fall back to the default,
        // so no file that never mentions /Identity changes path.
        assert!(matches!(
            crypt_filter_method(cf, b"MissingCF", CryptMethod::AesV3),
            Some(CryptMethod::AesV3)
        ));
        assert!(matches!(
            crypt_filter_method(None, b"StdCF", CryptMethod::Rc4),
            Some(CryptMethod::Rc4)
        ));
    }

    /// §7.6.5: streams and strings have SEPARATE crypt filters, so one of them being
    /// /Identity must not stop the other being decrypted - and must not decrypt it.
    #[test]
    fn a_stream_identity_filter_leaves_the_body_but_not_the_strings() {
        let flip = |d: &[u8]| d.iter().map(|b| b ^ 0xFF).collect::<Vec<u8>>();
        let mut obj = Object::Stream(Stream::new(
            dictionary! { "Author" => Object::String(b"abc".to_vec(), lopdf::StringFormat::Literal) },
            b"body".to_vec(),
        ));
        crypt_object_split(&mut obj, Some(&flip), None);
        let Object::Stream(s) = &obj else { panic!("expected a stream") };
        assert_eq!(s.content, b"body", "an /Identity /StmF must not touch the body");
        let Ok(Object::String(a, _)) = s.dict.get(b"Author") else {
            panic!("expected a string")
        };
        assert_eq!(a, &flip(b"abc"), "/StrF still applies to the dictionary strings");

        // And the mirror case: /StrF /Identity with a real /StmF.
        let mut obj = Object::Stream(Stream::new(
            dictionary! { "Author" => Object::String(b"abc".to_vec(), lopdf::StringFormat::Literal) },
            b"body".to_vec(),
        ));
        crypt_object_split(&mut obj, None, Some(&flip));
        let Object::Stream(s) = &obj else { panic!("expected a stream") };
        assert_eq!(s.content, flip(b"body"));
        let Ok(Object::String(a, _)) = s.dict.get(b"Author") else {
            panic!("expected a string")
        };
        assert_eq!(a, b"abc", "an /Identity /StrF must not touch the strings");
    }

    /// `crypt_object` (the encrypt-on-save path) must keep applying one transform to
    /// both, unchanged by the split above.
    #[test]
    fn crypt_object_still_applies_to_strings_and_streams_alike() {
        let flip = |d: &[u8]| d.iter().map(|b| b ^ 0xFF).collect::<Vec<u8>>();
        let mut obj = Object::Array(vec![
            Object::String(b"s".to_vec(), lopdf::StringFormat::Literal),
            Object::Stream(Stream::new(Dictionary::new(), b"c".to_vec())),
        ]);
        crypt_object(&mut obj, &flip);
        let Object::Array(a) = &obj else { panic!() };
        // Transformed strings are re-tagged hexadecimal (binary-safe); the
        // payload is the flipped bytes either way.
        assert_eq!(a[0], Object::String(flip(b"s"), lopdf::StringFormat::Hexadecimal));
        let Object::Stream(s) = &a[1] else { panic!() };
        assert_eq!(s.content, flip(b"c"));
    }
}
