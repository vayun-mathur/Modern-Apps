#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rc4_roundtrip() {
        let key = b"SecretKey";
        let data = b"hello world, this is a test";
        let enc = rc4(key, data);
        assert_ne!(enc, data);
        assert_eq!(rc4(key, &enc), data);
    }

    #[test]
    fn user_password_roundtrip() {
        let id0 = b"0123456789abcdef";
        let p: i32 = -44;
        let n = 16;
        let rev = 3u8;
        let o = compute_o(b"owner", b"", n, rev);
        let key = compute_key(b"", &o, p, id0, n, rev, true);
        let u = compute_u(&key, id0, rev);
        let got =
            authenticate(&AuthParams { pw: b"", o: &o, u: &u, p, id0, n, rev, encrypt_metadata: true }).expect("empty user pw authenticates");
        assert_eq!(got, key);
        assert!(authenticate(&AuthParams { pw: b"wrong", o: &o, u: &u, p, id0, n, rev, encrypt_metadata: true }).is_none());
    }

    #[test]
    fn aes_roundtrip() {
        let key = [7u8; 16];
        let iv = [3u8; 16];
        let data = b"AES-CBC round trip test payload!!";
        let enc = aes_cbc_encrypt(&key, &iv, data).expect("valid 16-byte key and IV");
        assert_eq!(aes_cbc_decrypt(&key, &enc).expect("round-trips"), data);
    }

    #[test]
    fn aes_cbc_decrypt_rejects_unaligned_and_short_input() {
        let key = [7u8; 16];
        let iv = [3u8; 16];
        let mut enc = aes_cbc_encrypt(&key, &iv, b"payload").expect("encrypt");
        enc.truncate(enc.len() - 6); // leave a partial trailing block
        assert_eq!(
            aes_cbc_decrypt(&key, &enc),
            Err(CbcError::NotBlockAligned(10))
        );
        assert_eq!(aes_cbc_decrypt(&key, &[0u8; 4]), Err(CbcError::TooShort(4)));
        assert_eq!(aes_cbc_decrypt(&[0u8; 7], &[0u8; 32]), Err(CbcError::KeyLen(7)));
    }

    #[test]
    fn aes_cbc_decrypt_with_wrong_key_is_an_error() {
        let iv = [3u8; 16];
        let enc = aes_cbc_encrypt(&[7u8; 16], &iv, b"secret payload").expect("encrypt");
        assert_eq!(aes_cbc_decrypt(&[8u8; 16], &enc), Err(CbcError::BadPadding));
    }

    /// ISO 32000-2 algorithms 12/13 hash exactly the 48-byte /U string. A longer /U is
    /// untrusted input to algorithm 2.B, which repeats it 64 times per round for at
    /// least 64 rounds - so honouring the file's length turns one password check into
    /// tens of gigabytes of AES.
    #[test]
    fn owner_authentication_uses_only_the_48_byte_u_string() {
        let file_key = [0x5Au8; 32];
        let salts: [[u8; 8]; 4] = [[1; 8], [2; 8], [3; 8], [4; 8]];
        for rev in [5u8, 6u8] {
            let (u, _ue, o, oe) =
                compute_v5(b"user", b"owner", &file_key, &salts, rev).expect("entries");
            assert_eq!(u.len(), 48);
            assert_eq!(
                authenticate_v5_owner(b"owner", &o, &oe, &u, rev),
                Some(file_key.to_vec()),
                "R{rev} owner password must recover the file key"
            );
            // Trailing junk past the 48 bytes must be ignored, not hashed.
            let mut padded = u.clone();
            padded.extend(std::iter::repeat_n(0xAAu8, 4096));
            assert_eq!(
                authenticate_v5_owner(b"owner", &o, &oe, &padded, rev),
                Some(file_key.to_vec()),
                "only /U[0..48] is part of the hash"
            );
            assert!(authenticate_v5_owner(b"wrong", &o, &oe, &u, rev).is_none());
        }
    }
}
