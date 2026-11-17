//! Shim that mimics ring/aws-lc-rs aead + error API using chacha20poly1305 0.11
//! (aead 0.6 `AeadInOut` API with `InOutBuf`).

pub mod error {
    #[derive(Debug, Clone, Copy)]
    pub struct Unspecified;
    impl std::fmt::Display for Unspecified {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { write!(f, "Unspecified") }
    }
    impl std::error::Error for Unspecified {}
}

pub mod aead {
    use super::error;
    use chacha20poly1305::aead::{inout::InOutBuf, AeadInOut, KeyInit};
    use chacha20poly1305::{ChaCha20Poly1305, Key as CKey, Tag};

    #[derive(Clone, Copy, Debug)]
    pub struct Algorithm;
    pub static CHACHA20_POLY1305: Algorithm = Algorithm;

    pub struct UnboundKey { bytes: [u8; 32] }
    impl UnboundKey {
        pub fn new(_alg: &Algorithm, key: &[u8]) -> Result<Self, error::Unspecified> {
            if key.len() != 32 { return Err(error::Unspecified); }
            let mut bytes = [0u8; 32]; bytes.copy_from_slice(key);
            Ok(Self { bytes })
        }
    }

    #[derive(Clone, Copy)]
    pub struct Nonce(pub [u8; 12]);
    impl Nonce { pub fn assume_unique_for_key(n: [u8; 12]) -> Self { Self(n) } }

    pub struct Aad<B>(pub B);
    impl<B: AsRef<[u8]>> Aad<B> {
        pub fn from(b: B) -> Self { Self(b) }
    }
    impl<'a> From<&'a [u8]> for Aad<&'a [u8]> { fn from(b: &'a [u8]) -> Self { Self(b) } }

    pub struct LessSafeKey { inner: ChaCha20Poly1305 }
    impl LessSafeKey {
        pub fn new(unbound: UnboundKey) -> Self {
            Self { inner: ChaCha20Poly1305::new(&CKey::from(unbound.bytes)) }
        }

        pub fn seal_in_place_separate_tag(
            &self, nonce: Nonce, aad: Aad<&[u8]>, in_out: &mut [u8],
        ) -> Result<Tag, error::Unspecified> {
            let nonce = chacha20poly1305::Nonce::from(nonce.0);
            let tag = self
                .inner
                .encrypt_inout_detached(
                    &nonce,
                    aad.0,
                    InOutBuf::from(in_out),
                )
                .map_err(|_| error::Unspecified)?;
            let mut out = Tag::default();
            out.copy_from_slice(&tag);
            Ok(out)
        }

        // Ring's less-safe key mutates buffer that is ct || tag, and returns pt slice.
        pub fn open_in_place<'a>(
            &self, nonce: Nonce, aad: Aad<&[u8]>, in_out: &'a mut [u8],
        ) -> Result<&'a mut [u8], error::Unspecified> {
            let len = in_out.len();
            if len < 16 { return Err(error::Unspecified); }
            let (ct, tag_bytes) = in_out.split_at_mut(len - 16);
            let tag = Tag::try_from(&*tag_bytes).map_err(|_| error::Unspecified)?;
            let ct_len = ct.len();
            self.inner
                .decrypt_inout_detached(
                    &chacha20poly1305::Nonce::from(nonce.0),
                    aad.0,
                    InOutBuf::from(&mut ct[..]),
                    &tag,
                )
                .map_err(|_| error::Unspecified)?;
            let plain_len = len - 16;
            debug_assert_eq!(ct_len, len - 16);
            let ptr = in_out.as_mut_ptr();
            // SAFETY: ptr derived from in_out, length shrinks within allocation.
            let plain = unsafe { std::slice::from_raw_parts_mut(ptr, plain_len) };
            Ok(plain)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::aead::*;
    use super::error::Unspecified;

    fn key(bytes: &[u8]) -> LessSafeKey {
        LessSafeKey::new(UnboundKey::new(&CHACHA20_POLY1305, bytes).unwrap())
    }

    #[test]
    fn unbound_key_rejects_wrong_length() {
        assert!(UnboundKey::new(&CHACHA20_POLY1305, &[0u8; 31]).is_err());
        assert!(UnboundKey::new(&CHACHA20_POLY1305, &[0u8; 33]).is_err());
        assert!(UnboundKey::new(&CHACHA20_POLY1305, &[0u8; 32]).is_ok());
        assert!(UnboundKey::new(&CHACHA20_POLY1305, &[]).is_err());
    }

    #[test]
    fn seal_open_roundtrip() {
        let k = key(&[7u8; 32]);
        let nonce = Nonce::assume_unique_for_key([3u8; 12]);
        let mut buf = b"hello crypto".to_vec();
        let tag = k.seal_in_place_separate_tag(nonce, Aad::from(&b"aad"[..]), &mut buf).unwrap();
        let mut ct_and_tag = buf.clone();
        ct_and_tag.extend_from_slice(tag.as_ref());
        let pt = k.open_in_place(nonce, Aad::from(&b"aad"[..]), &mut ct_and_tag).unwrap();
        assert_eq!(pt, b"hello crypto");
    }

    #[test]
    fn open_rejects_wrong_aad_key_and_short() {
        let k = key(&[7u8; 32]);
        let nonce = Nonce::assume_unique_for_key([3u8; 12]);
        let mut buf = b"data".to_vec();
        let tag = k.seal_in_place_separate_tag(nonce, Aad::from(&b"aad"[..]), &mut buf).unwrap();
        let mut ct = buf.clone();
        ct.extend_from_slice(tag.as_ref());
        // wrong AAD
        let mut c2 = ct.clone();
        assert!(k.open_in_place(nonce, Aad::from(&b"bad"[..]), &mut c2).is_err());
        // wrong key
        let k2 = key(&[8u8; 32]);
        let mut c3 = ct.clone();
        assert!(k2.open_in_place(nonce, Aad::from(&b"aad"[..]), &mut c3).is_err());
        // too short for tag
        let mut short = [0u8; 15];
        assert!(k.open_in_place(nonce, Aad::from(&[][..]), &mut short).is_err());
        let _ = Unspecified;
    }

    #[test]
    fn seal_empty_plaintext() {
        let k = key(&[0u8; 32]);
        let nonce = Nonce::assume_unique_for_key([0u8; 12]);
        let mut buf: Vec<u8> = Vec::new();
        let tag = k.seal_in_place_separate_tag(nonce, Aad::from(&[][..]), &mut buf).unwrap();
        let mut ct = Vec::new();
        ct.extend_from_slice(tag.as_ref());
        let pt = k.open_in_place(nonce, Aad::from(&[][..]), &mut ct).unwrap();
        assert!(pt.is_empty());
    }
}
