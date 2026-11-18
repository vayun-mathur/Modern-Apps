#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chacha20_seal_rfc7530_test_vector() {
        let plaintext = b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
        let aad: [u8; 12] = [
            0x50, 0x51, 0x52, 0x53, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7,
        ];
        let key: [u8; 32] = [
            0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x8b, 0x8c, 0x8d,
            0x8e, 0x8f, 0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0x9b,
            0x9c, 0x9d, 0x9e, 0x9f,
        ];
        let nonce: [u8; 12] = [
            0x07, 0x00, 0x00, 0x00, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47,
        ];
        let mut buffer = vec![0; plaintext.len() + 16];

        aead_chacha20_seal_inner(&mut buffer, &key, nonce, plaintext, &aad);

        const EXPECTED_CIPHERTEXT: [u8; 114] = [
            0xd3, 0x1a, 0x8d, 0x34, 0x64, 0x8e, 0x60, 0xdb, 0x7b, 0x86, 0xaf, 0xbc, 0x53, 0xef,
            0x7e, 0xc2, 0xa4, 0xad, 0xed, 0x51, 0x29, 0x6e, 0x08, 0xfe, 0xa9, 0xe2, 0xb5, 0xa7,
            0x36, 0xee, 0x62, 0xd6, 0x3d, 0xbe, 0xa4, 0x5e, 0x8c, 0xa9, 0x67, 0x12, 0x82, 0xfa,
            0xfb, 0x69, 0xda, 0x92, 0x72, 0x8b, 0x1a, 0x71, 0xde, 0x0a, 0x9e, 0x06, 0x0b, 0x29,
            0x05, 0xd6, 0xa5, 0xb6, 0x7e, 0xcd, 0x3b, 0x36, 0x92, 0xdd, 0xbd, 0x7f, 0x2d, 0x77,
            0x8b, 0x8c, 0x98, 0x03, 0xae, 0xe3, 0x28, 0x09, 0x1b, 0x58, 0xfa, 0xb3, 0x24, 0xe4,
            0xfa, 0xd6, 0x75, 0x94, 0x55, 0x85, 0x80, 0x8b, 0x48, 0x31, 0xd7, 0xbc, 0x3f, 0xf4,
            0xde, 0xf0, 0x8e, 0x4b, 0x7a, 0x9d, 0xe5, 0x76, 0xd2, 0x65, 0x86, 0xce, 0xc6, 0x4b,
            0x61, 0x16,
        ];
        const EXPECTED_TAG: [u8; 16] = [
            0x1a, 0xe1, 0x0b, 0x59, 0x4f, 0x09, 0xe2, 0x6a, 0x7e, 0x90, 0x2e, 0xcb, 0xd0, 0x60,
            0x06, 0x91,
        ];

        assert_eq!(buffer[..plaintext.len()], EXPECTED_CIPHERTEXT);
        assert_eq!(buffer[plaintext.len()..], EXPECTED_TAG);
    }

    #[test]
    fn symmetric_chacha20_seal_open() {
        let aad: [u8; 32] = Default::default();
        let key: [u8; 32] = Default::default();
        let counter = 0;

        let mut encrypted_nothing: [u8; 16] = Default::default();

        aead_chacha20_seal(&mut encrypted_nothing, &key, counter, &[], &aad);

        eprintln!("encrypted_nothing: {encrypted_nothing:?}");

        aead_chacha20_open(&mut [], &key, counter, &encrypted_nothing, &aad)
            .expect("Should open what we just sealed");
    }

    #[test]
    fn tai64n_parse_ordering_and_zero() {
        let mut buf = [0u8; 12];
        buf[0..8].copy_from_slice(&100u64.to_be_bytes());
        buf[8..12].copy_from_slice(&5u32.to_be_bytes());
        let a = Tai64N::parse(&buf).unwrap();
        let mut buf2 = buf;
        buf2[8..12].copy_from_slice(&6u32.to_be_bytes());
        let b = Tai64N::parse(&buf2).unwrap();
        assert!(b.after(&a));
        assert!(!a.after(&b));
        assert!(!a.after(&a));
        let zero = Tai64N::zero();
        assert!(a.after(&zero));
        assert_eq!(format!("{:?}", a).contains("secs"), true);
    }

    #[test]
    fn timestamper_stamp_is_12_bytes_and_increasing() {
        let s = TimeStamper::new();
        let t1 = s.stamp();
        let t2 = s.stamp();
        assert_eq!(t1.len(), 12);
        // Big-endian secs+nanos encoding is lexicographically non-decreasing.
        assert!(t2 >= t1);
    }

    #[test]
    fn noise_params_debug_redacts_and_key_rotation() {
        let priv_a = x25519::StaticSecret::from([41u8; 32]);
        let pub_a = x25519::PublicKey::from(&priv_a);
        let priv_b = x25519::StaticSecret::from([42u8; 32]);
        let pub_b = x25519::PublicKey::from(&priv_b);
        let mut params = NoiseParams::new(priv_a, pub_a, pub_b, None);
        let dbg = format!("{:?}", params);
        assert!(dbg.contains("redacted"));
        params.set_preshared_key(Some([9u8; 32]));
        assert_eq!(params.preshared_key, Some([9u8; 32]));
        let new_priv = x25519::StaticSecret::from([43u8; 32]);
        let new_pub = x25519::PublicKey::from(&new_priv);
        params.set_static_private(new_priv, new_pub);
        assert_eq!(params.static_public.as_bytes(), new_pub.as_bytes());
        // InitSent state Debug also redacts the ephemeral secret.
        let dbg2 = format!(
            "{:?}",
            HandshakeState::InitSent(HandshakeInitSentState {
                local_index: IndexTable::from_os_rng().new_index(),
                hash: [0u8; 32],
                chaining_key: [0u8; 32],
                ephemeral_private: x25519::ReusableSecret::random_from_rng(&mut crate::os_rng()),
                time_sent: Instant::now(),
            })
        );
        assert!(dbg2.contains("redacted"));
    }

    #[test]
    fn handshake_cookie_reply_flow_and_state_guards() {
        let a_priv = x25519::StaticSecret::from([51u8; 32]);
        let b_priv = x25519::StaticSecret::from([52u8; 32]);
        let a_pub = x25519::PublicKey::from(&a_priv);
        let b_pub = x25519::PublicKey::from(&b_priv);
        let mut a = Handshake::new(a_priv, a_pub, b_pub, IndexTable::from_os_rng(), None);
        let b = Handshake::new(b_priv, b_pub, a_pub, IndexTable::from_os_rng(), None);
        // No in-flight handshake: response/cookie guards reject.
        assert!(matches!(
            a.receive_handshake_response(&WgHandshakeResp::new(1, 2, [0u8; 32])),
            Err(WireGuardError::UnexpectedPacket)
        ));
        assert!(matches!(
            a.receive_cookie_reply(&WgCookieReply::new()),
            Err(WireGuardError::UnexpectedPacket)
        ));
        // Anonymous parse of A's initiation recovers A's static public key.
        let init_pkt = a.format_handshake_initiation();
        let init_ref = <WgHandshakeInit as zerocopy::FromBytes>::ref_from_bytes(init_pkt.as_bytes()).unwrap();
        let half = parse_handshake_anon(&b.params.static_private, &b.params.static_public, init_ref).unwrap();
        assert_eq!(half.peer_static_public, a_pub.to_bytes());
        assert_eq!(half.peer_index, init_ref.sender_idx.get());
        // Cookie reply with the wrong receiver index is rejected.
        let mac1 = a.cookies.last_mac1.expect("mac1 stored");
        let cookie_key = b2s_hash(LABEL_COOKIE, b_pub.as_bytes());
        let mut reply = WgCookieReply::new();
        reply.receiver_idx.set(0xFFFF_FFFF);
        let cipher = <chacha20poly1305::XChaCha20Poly1305 as aead::KeyInit>::new_from_slice(&cookie_key).unwrap();
        reply.encrypted_cookie.encrypted = [3u8; 16];
        let tag = {
            use aead::AeadInOut;
            use chacha20poly1305::aead::inout::InOutBuf;
            let iv = chacha20poly1305::XNonce::from(reply.nonce);
            cipher
                .encrypt_inout_detached(&iv, &mac1, InOutBuf::from(&mut reply.encrypted_cookie.encrypted[..]))
                .unwrap()
        };
        reply.encrypted_cookie.tag = tag.into();
        assert!(matches!(a.receive_cookie_reply(&reply), Err(WireGuardError::WrongIndex)));
        // Correct index stores the cookie; clear_cookie removes it.
        reply.receiver_idx.set(a.cookies.index);
        // Re-encrypt for the (unchanged) nonce/mac1: cookie plaintext was altered
        // in place above, so rebuild cleanly.
        let mut reply = WgCookieReply::new();
        reply.receiver_idx.set(a.cookies.index);
        reply.encrypted_cookie.encrypted = [3u8; 16];
        let tag = {
            use aead::AeadInOut;
            use chacha20poly1305::aead::inout::InOutBuf;
            let iv = chacha20poly1305::XNonce::from(reply.nonce);
            cipher
                .encrypt_inout_detached(&iv, &mac1, InOutBuf::from(&mut reply.encrypted_cookie.encrypted[..]))
                .unwrap()
        };
        reply.encrypted_cookie.tag = tag.into();
        a.receive_cookie_reply(&reply).unwrap();
        assert!(a.has_cookie());
        a.clear_cookie();
        assert!(!a.has_cookie());
        // Expiry guards.
        assert!(!a.is_expired());
        a.set_expired();
        assert!(a.is_expired());
        assert!(!a.is_in_progress());
        a.reset();
        assert!(!a.is_expired());
    }

    #[test]
    fn aead_open_rejects_wrong_key_and_mac_helpers() {
        let mut buf = [0u8; 16];
        aead_chacha20_seal(&mut buf, &[1u8; 32], 0, &[], &[0u8; 32]);
        assert!(aead_chacha20_open(&mut [], &[2u8; 32], 0, &buf, &[0u8; 32]).is_err());
        assert_eq!(b2s_keyed_mac_16_2(&[1u8; 32], b"a", b"b").len(), 16);
        assert_ne!(
            b2s_keyed_mac_16_2(&[1u8; 32], b"a", b"b"),
            b2s_keyed_mac_16_2(&[1u8; 32], b"b", b"a")
        );
        assert_eq!(b2s_mac_24(&[1u8; 32], b"x").len(), 24);
        assert_ne!(b2s_mac_24(&[1u8; 32], b"x"), b2s_mac_24(&[1u8; 32], b"y"));
    }
}
