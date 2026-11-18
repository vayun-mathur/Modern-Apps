//! Host tests for every `extern "C"` function in `c_bridge.rs` (unsafe calls).
use crate::c_bridge::*;
use std::ptr;

unsafe fn take(p: *mut u8, len: usize) -> Vec<u8> {
    if p.is_null() { return Vec::new(); }
    let v = std::slice::from_raw_parts(p, len).to_vec();
    pqc_free(p, len);
    v
}
unsafe fn kem_pair() -> (Vec<u8>, Vec<u8>) {
    let (mut p, mut pl, mut s, mut sl) = (ptr::null_mut(), 0, ptr::null_mut(), 0);
    assert_eq!(pqc_kem_keygen(&mut p, &mut pl, &mut s, &mut sl), 1);
    (take(p, pl), take(s, sl))
}
unsafe fn dsa_pair() -> (Vec<u8>, Vec<u8>) {
    let (mut p, mut pl, mut s, mut sl) = (ptr::null_mut(), 0, ptr::null_mut(), 0);
    assert_eq!(pqc_dsa_keygen(&mut p, &mut pl, &mut s, &mut sl), 1);
    (take(p, pl), take(s, sl))
}
unsafe fn identity() -> (Vec<u8>, Vec<u8>) {
    let (mut p, mut pl, mut s, mut sl) = (ptr::null_mut(), 0, ptr::null_mut(), 0);
    assert_eq!(pqc_identity_keygen(&mut p, &mut pl, &mut s, &mut sl), 1);
    (take(p, pl), take(s, sl))
}
unsafe fn split(bundle: &[u8]) -> (Vec<u8>, Vec<u8>) {
    let (mut a, mut al, mut b, mut bl) = (ptr::null_mut(), 0, ptr::null_mut(), 0);
    assert_eq!(pqc_bundle_split(bundle.as_ptr(), bundle.len(), &mut a, &mut al, &mut b, &mut bl), 1);
    (take(a, al), take(b, bl))
}

#[test]
fn c_kem_keygen_roundtrip_and_nulls() {
    unsafe {
        let (pub_der, priv_der) = kem_pair();
        assert_eq!(pub_der.len(), 1206);
        assert_eq!(priv_der.len(), 2498);
        let (mut ct, mut ctl, mut ss, mut ssl) = (ptr::null_mut(), 0, ptr::null_mut(), 0);
        assert_eq!(pqc_kem_encaps(pub_der.as_ptr(), pub_der.len(), &mut ct, &mut ctl, &mut ss, &mut ssl), 1);
        let (ct_v, ss1) = (take(ct, ctl), take(ss, ssl));
        assert_eq!(ct_v.len(), 1088);
        assert_eq!(ss1.len(), 32);
        let (mut ss2p, mut ss2l) = (ptr::null_mut(), 0);
        assert_eq!(pqc_kem_decaps(priv_der.as_ptr(), priv_der.len(), ct_v.as_ptr(), ct_v.len(), &mut ss2p, &mut ss2l), 1);
        assert_eq!(take(ss2p, ss2l), ss1);
        let (mut p, mut pl, mut s, mut sl) = (ptr::null_mut(), 0, ptr::null_mut(), 0);
        assert_eq!(pqc_kem_keygen(ptr::null_mut(), &mut pl, &mut s, &mut sl), 0);
        assert_eq!(pqc_kem_keygen(&mut p, ptr::null_mut(), &mut s, &mut sl), 0);
        assert_eq!(pqc_kem_keygen(&mut p, &mut pl, ptr::null_mut(), &mut sl), 0);
        assert_eq!(pqc_kem_keygen(&mut p, &mut pl, &mut s, ptr::null_mut()), 0);
    }
}

#[test]
fn c_dsa_keygen_sign_verify_and_nulls() {
    unsafe {
        let (pub_der, priv_der) = dsa_pair();
        assert_eq!(pub_der.len(), 1974);
        assert_eq!(priv_der.len(), 4098);
        let msg = b"sign me via C";
        let (mut sig, mut sigl) = (ptr::null_mut(), 0);
        assert_eq!(pqc_dsa_sign(priv_der.as_ptr(), priv_der.len(), msg.as_ptr(), msg.len(), &mut sig, &mut sigl), 1);
        let sig_v = take(sig, sigl);
        assert_eq!(sig_v.len(), 3309);
        assert_eq!(pqc_dsa_verify(pub_der.as_ptr(), pub_der.len(), msg.as_ptr(), msg.len(), sig_v.as_ptr(), sig_v.len()), 1);
        assert_eq!(pqc_dsa_verify(pub_der.as_ptr(), pub_der.len(), b"other".as_ptr(), 5, sig_v.as_ptr(), sig_v.len()), 0);
        let mut bad = sig_v.clone();
        bad[100] ^= 1;
        assert_eq!(pqc_dsa_verify(pub_der.as_ptr(), pub_der.len(), msg.as_ptr(), msg.len(), bad.as_ptr(), bad.len()), 0);
        let (mut p, mut pl, mut s, mut sl) = (ptr::null_mut(), 0, ptr::null_mut(), 0);
        assert_eq!(pqc_dsa_keygen(ptr::null_mut(), &mut pl, &mut s, &mut sl), 0);
        assert_eq!(pqc_dsa_keygen(&mut p, ptr::null_mut(), &mut s, &mut sl), 0);
        assert_eq!(pqc_dsa_keygen(&mut p, &mut pl, ptr::null_mut(), &mut sl), 0);
        assert_eq!(pqc_dsa_keygen(&mut p, &mut pl, &mut s, ptr::null_mut()), 0);
        let (mut s2, mut s2l) = (ptr::null_mut(), 0);
        let empty: &[u8] = b"";
        assert_eq!(pqc_dsa_sign(priv_der.as_ptr(), priv_der.len(), empty.as_ptr(), 0, &mut s2, &mut s2l), 1);
        let s2v = take(s2, s2l);
        assert_eq!(pqc_dsa_verify(pub_der.as_ptr(), pub_der.len(), empty.as_ptr(), 0, s2v.as_ptr(), s2v.len()), 1);
    }
}

#[test]
fn c_dsa_sign_verify_error_paths() {
    unsafe {
        let (pub_der, priv_der) = dsa_pair();
        let msg = b"m";
        let (mut sig, mut sigl) = (ptr::null_mut(), 0);
        assert_eq!(pqc_dsa_sign(priv_der.as_ptr(), priv_der.len(), msg.as_ptr(), 1, &mut sig, &mut sigl), 1);
        let sig_v = take(sig, sigl);
        let (mut o, mut ol) = (ptr::null_mut(), 0);
        assert_eq!(pqc_dsa_sign(ptr::null(), 1, msg.as_ptr(), 1, &mut o, &mut ol), 0);
        assert_eq!(pqc_dsa_sign(priv_der.as_ptr(), priv_der.len(), ptr::null(), 0, &mut o, &mut ol), 0);
        assert_eq!(pqc_dsa_sign(priv_der.as_ptr(), priv_der.len(), msg.as_ptr(), 1, ptr::null_mut(), &mut ol), 0);
        assert_eq!(pqc_dsa_sign(priv_der.as_ptr(), priv_der.len(), msg.as_ptr(), 1, &mut o, ptr::null_mut()), 0);
        assert_eq!(pqc_dsa_sign(priv_der.as_ptr(), 10, msg.as_ptr(), 1, &mut o, &mut ol), 0);
        let short = [0u8; 100];
        assert_eq!(pqc_dsa_sign(short.as_ptr(), short.len(), msg.as_ptr(), 1, &mut o, &mut ol), 0);
        assert_eq!(pqc_dsa_verify(ptr::null(), 1, msg.as_ptr(), 1, sig_v.as_ptr(), sig_v.len()), -1);
        assert_eq!(pqc_dsa_verify(pub_der.as_ptr(), pub_der.len(), ptr::null(), 0, sig_v.as_ptr(), sig_v.len()), -1);
        assert_eq!(pqc_dsa_verify(pub_der.as_ptr(), pub_der.len(), msg.as_ptr(), 1, ptr::null(), 0), -1);
        assert_eq!(pqc_dsa_verify(pub_der.as_ptr(), 10, msg.as_ptr(), 1, sig_v.as_ptr(), sig_v.len()), -1);
        assert_eq!(pqc_dsa_verify(short.as_ptr(), short.len(), msg.as_ptr(), 1, sig_v.as_ptr(), sig_v.len()), -1);
        assert_eq!(pqc_dsa_verify(pub_der.as_ptr(), pub_der.len(), msg.as_ptr(), 1, sig_v.as_ptr(), 0), -1);
        assert_eq!(pqc_dsa_verify(pub_der.as_ptr(), pub_der.len(), msg.as_ptr(), 1, sig_v.as_ptr(), 3308), -1);
        let mut long_sig = sig_v.clone();
        long_sig.push(0);
        assert_eq!(pqc_dsa_verify(pub_der.as_ptr(), pub_der.len(), msg.as_ptr(), 1, long_sig.as_ptr(), long_sig.len()), -1);
    }
}

#[test]
fn c_identity_and_bundle_build_split_inspect() {
    unsafe {
        let (pub_b, priv_b) = identity();
        assert_eq!(pqc_bundle_is_valid(pub_b.as_ptr(), pub_b.len()), 1);
        assert_eq!(pqc_bundle_is_valid(priv_b.as_ptr(), priv_b.len()), 1);
        let (mut kl, mut dl) = (0usize, 0usize);
        assert_eq!(pqc_bundle_inspect(pub_b.as_ptr(), pub_b.len(), &mut kl, &mut dl), 1);
        assert_eq!((kl, dl), (1206, 1974));
        assert_eq!(pqc_bundle_inspect(priv_b.as_ptr(), priv_b.len(), &mut kl, &mut dl), 1);
        assert_eq!((kl, dl), (2498, 4098));
        let (kem_pub, dsa_pub) = split(&pub_b);
        assert_eq!(kem_pub.len(), 1206);
        assert_eq!(dsa_pub.len(), 1974);
        let (mut out, mut outl) = (ptr::null_mut(), 0);
        assert_eq!(pqc_bundle_build(kem_pub.as_ptr(), kem_pub.len(), dsa_pub.as_ptr(), dsa_pub.len(), &mut out, &mut outl), 1);
        assert_eq!(take(out, outl), pub_b);
        let (kem_priv, dsa_priv) = split(&priv_b);
        assert_eq!(kem_priv.len(), 2498);
        assert_eq!(dsa_priv.len(), 4098);
        let (mut p, mut pl, mut s, mut sl) = (ptr::null_mut(), 0, ptr::null_mut(), 0);
        assert_eq!(pqc_identity_keygen(ptr::null_mut(), &mut pl, &mut s, &mut sl), 0);
        assert_eq!(pqc_identity_keygen(&mut p, ptr::null_mut(), &mut s, &mut sl), 0);
        assert_eq!(pqc_identity_keygen(&mut p, &mut pl, ptr::null_mut(), &mut sl), 0);
        assert_eq!(pqc_identity_keygen(&mut p, &mut pl, &mut s, ptr::null_mut()), 0);
    }
}

#[test]
fn c_bundle_edge_cases() {
    unsafe {
        let kem: &[u8] = b"kem-bytes";
        let dsa: &[u8] = b"dsa!";
        let (mut out, mut outl) = (ptr::null_mut(), 0);
        assert_eq!(pqc_bundle_build(ptr::null(), 0, dsa.as_ptr(), dsa.len(), &mut out, &mut outl), 0);
        assert_eq!(pqc_bundle_build(kem.as_ptr(), kem.len(), ptr::null(), 0, &mut out, &mut outl), 0);
        assert_eq!(pqc_bundle_build(kem.as_ptr(), kem.len(), dsa.as_ptr(), dsa.len(), ptr::null_mut(), &mut outl), 0);
        assert_eq!(pqc_bundle_build(kem.as_ptr(), kem.len(), dsa.as_ptr(), dsa.len(), &mut out, ptr::null_mut()), 0);
        assert_eq!(pqc_bundle_build(kem.as_ptr(), kem.len(), dsa.as_ptr(), dsa.len(), &mut out, &mut outl), 1);
        let bundle = take(out, outl);
        assert_eq!(&bundle[..4], &9u32.to_be_bytes());
        assert_eq!(split(&bundle), (kem.to_vec(), dsa.to_vec()));
        let (mut a, mut al, mut b, mut bl) = (ptr::null_mut(), 0, ptr::null_mut(), 0);
        assert_eq!(pqc_bundle_split(ptr::null(), bundle.len(), &mut a, &mut al, &mut b, &mut bl), 0);
        assert_eq!(pqc_bundle_split(bundle.as_ptr(), bundle.len(), ptr::null_mut(), &mut al, &mut b, &mut bl), 0);
        assert_eq!(pqc_bundle_split(bundle.as_ptr(), bundle.len(), &mut a, ptr::null_mut(), &mut b, &mut bl), 0);
        assert_eq!(pqc_bundle_split(bundle.as_ptr(), bundle.len(), &mut a, &mut al, ptr::null_mut(), &mut bl), 0);
        assert_eq!(pqc_bundle_split(bundle.as_ptr(), bundle.len(), &mut a, &mut al, &mut b, ptr::null_mut()), 0);
        for bad in [&b""[..], &b"\x00\x00\x00"[..], &b"\x00\x00\x00\x09ab"[..], &b"\xff\xff\xff\xffabcd"[..]] {
            assert_eq!(pqc_bundle_split(bad.as_ptr(), bad.len(), &mut a, &mut al, &mut b, &mut bl), 0);
            assert_eq!(pqc_bundle_is_valid(bad.as_ptr(), bad.len()), 0);
            let (mut x, mut y) = (0usize, 0usize);
            assert_eq!(pqc_bundle_inspect(bad.as_ptr(), bad.len(), &mut x, &mut y), 0);
        }
        let empty_bundle: &[u8] = &[0, 0, 0, 0];
        assert_eq!(pqc_bundle_is_valid(empty_bundle.as_ptr(), empty_bundle.len()), 1);
        let (mut x, mut y) = (99usize, 99usize);
        assert_eq!(pqc_bundle_inspect(empty_bundle.as_ptr(), empty_bundle.len(), &mut x, &mut y), 1);
        assert_eq!((x, y), (0, 0));
        assert_eq!(split(empty_bundle), (Vec::new(), Vec::new()));
        assert_eq!(pqc_bundle_is_valid(ptr::null(), 10), 0);
        assert_eq!(pqc_bundle_is_valid(bundle.as_ptr(), 3), 0);
        assert_eq!(pqc_bundle_inspect(ptr::null(), bundle.len(), &mut x, &mut y), 0);
        assert_eq!(pqc_bundle_inspect(bundle.as_ptr(), 3, &mut x, &mut y), 0);
        assert_eq!(pqc_bundle_inspect(bundle.as_ptr(), bundle.len(), ptr::null_mut(), &mut y), 0);
        assert_eq!(pqc_bundle_inspect(bundle.as_ptr(), bundle.len(), &mut x, ptr::null_mut()), 0);
        let e: &[u8] = b"";
        assert_eq!(pqc_bundle_build(e.as_ptr(), 0, e.as_ptr(), 0, &mut out, &mut outl), 1);
        assert_eq!(take(out, outl), vec![0, 0, 0, 0]);
    }
}

#[test]
fn c_link_keygen_and_from_seed() {
    unsafe {
        let (mut seed, mut seedl, mut bundle, mut bundlel) = (ptr::null_mut(), 0, ptr::null_mut(), 0);
        assert_eq!(pqc_link_keygen(&mut seed, &mut seedl, &mut bundle, &mut bundlel), 1);
        let (seed_v, bundle_v) = (take(seed, seedl), take(bundle, bundlel));
        assert_eq!(seed_v.len(), 32);
        let (kem, dsa) = split(&bundle_v);
        assert_eq!(kem.len(), 1206);
        assert!(dsa.is_empty());
        let (mut b2, mut b2l) = (ptr::null_mut(), 0);
        assert_eq!(pqc_link_pub_from_seed(seed_v.as_ptr(), seed_v.len(), &mut b2, &mut b2l), 1);
        assert_eq!(take(b2, b2l), bundle_v);
        for n in [0usize, 1, 31, 33, 64] {
            let s = vec![7u8; n];
            assert_eq!(pqc_link_pub_from_seed(s.as_ptr(), n, &mut b2, &mut b2l), 0, "len {n}");
        }
        assert_eq!(pqc_link_pub_from_seed(ptr::null(), 32, &mut b2, &mut b2l), 0);
        assert_eq!(pqc_link_pub_from_seed(seed_v.as_ptr(), 32, ptr::null_mut(), &mut b2l), 0);
        assert_eq!(pqc_link_pub_from_seed(seed_v.as_ptr(), 32, &mut b2, ptr::null_mut()), 0);
        let (mut s2, mut s2l, mut bb, mut bbl) = (ptr::null_mut(), 0, ptr::null_mut(), 0);
        assert_eq!(pqc_link_keygen(ptr::null_mut(), &mut s2l, &mut bb, &mut bbl), 0);
        assert_eq!(pqc_link_keygen(&mut s2, ptr::null_mut(), &mut bb, &mut bbl), 0);
        assert_eq!(pqc_link_keygen(&mut s2, &mut s2l, ptr::null_mut(), &mut bbl), 0);
        assert_eq!(pqc_link_keygen(&mut s2, &mut s2l, &mut bb, ptr::null_mut()), 0);
    }
}

#[test]
fn c_kem_encaps_decaps_error_paths() {
    unsafe {
        let (pub_der, priv_der) = kem_pair();
        let (mut ct, mut ctl, mut ss, mut ssl) = (ptr::null_mut(), 0, ptr::null_mut(), 0);
        assert_eq!(pqc_kem_encaps(ptr::null(), 1, &mut ct, &mut ctl, &mut ss, &mut ssl), 0);
        assert_eq!(pqc_kem_encaps(pub_der.as_ptr(), pub_der.len(), ptr::null_mut(), &mut ctl, &mut ss, &mut ssl), 0);
        assert_eq!(pqc_kem_encaps(pub_der.as_ptr(), pub_der.len(), &mut ct, ptr::null_mut(), &mut ss, &mut ssl), 0);
        assert_eq!(pqc_kem_encaps(pub_der.as_ptr(), pub_der.len(), &mut ct, &mut ctl, ptr::null_mut(), &mut ssl), 0);
        assert_eq!(pqc_kem_encaps(pub_der.as_ptr(), pub_der.len(), &mut ct, &mut ctl, &mut ss, ptr::null_mut()), 0);
        assert_eq!(pqc_kem_encaps(pub_der.as_ptr(), 0, &mut ct, &mut ctl, &mut ss, &mut ssl), 0);
        assert_eq!(pqc_kem_encaps(pub_der.as_ptr(), 100, &mut ct, &mut ctl, &mut ss, &mut ssl), 0);
        assert_eq!(pqc_kem_encaps(pub_der.as_ptr(), pub_der.len(), &mut ct, &mut ctl, &mut ss, &mut ssl), 1);
        let (ct_v, _ss) = (take(ct, ctl), take(ss, ssl));
        let (mut o, mut ol) = (ptr::null_mut(), 0);
        assert_eq!(pqc_kem_decaps(ptr::null(), 1, ct_v.as_ptr(), ct_v.len(), &mut o, &mut ol), 0);
        assert_eq!(pqc_kem_decaps(priv_der.as_ptr(), priv_der.len(), ptr::null(), 1, &mut o, &mut ol), 0);
        assert_eq!(pqc_kem_decaps(priv_der.as_ptr(), priv_der.len(), ct_v.as_ptr(), ct_v.len(), ptr::null_mut(), &mut ol), 0);
        assert_eq!(pqc_kem_decaps(priv_der.as_ptr(), priv_der.len(), ct_v.as_ptr(), ct_v.len(), &mut o, ptr::null_mut()), 0);
        assert_eq!(pqc_kem_decaps(priv_der.as_ptr(), 100, ct_v.as_ptr(), ct_v.len(), &mut o, &mut ol), 0);
        assert_eq!(pqc_kem_decaps(priv_der.as_ptr(), priv_der.len(), ct_v.as_ptr(), 0, &mut o, &mut ol), 0);
        assert_eq!(pqc_kem_decaps(priv_der.as_ptr(), priv_der.len(), ct_v.as_ptr(), 1087, &mut o, &mut ol), 0);
        let mut long_ct = ct_v.clone();
        long_ct.push(0);
        assert_eq!(pqc_kem_decaps(priv_der.as_ptr(), priv_der.len(), long_ct.as_ptr(), long_ct.len(), &mut o, &mut ol), 0);
    }
}

#[test]
fn c_security_code_format_and_properties() {
    unsafe {
        let a: &[u8] = b"alice-public-bundle-bytes";
        let b: &[u8] = b"bob-public-bundle-bytes!!";
        let mut out = [0u8; 64];
        assert_eq!(pqc_security_code(a.as_ptr(), a.len(), b.as_ptr(), b.len(), out.as_mut_ptr(), out.len()), 1);
        assert_eq!(out[35], 0);
        let code = std::str::from_utf8(&out[..35]).unwrap().to_string();
        let groups: Vec<&str> = code.split(' ').collect();
        assert_eq!(groups.len(), 6);
        for g in &groups {
            assert_eq!(g.len(), 5);
            assert!(g.bytes().all(|c| c.is_ascii_digit()), "group {g}");
        }
        let mut out2 = [0u8; 64];
        assert_eq!(pqc_security_code(b.as_ptr(), b.len(), a.as_ptr(), a.len(), out2.as_mut_ptr(), out2.len()), 1);
        assert_eq!(&out2[..36], &out[..36]);
        let mut out3 = [0u8; 36];
        assert_eq!(pqc_security_code(a.as_ptr(), a.len(), b.as_ptr(), b.len(), out3.as_mut_ptr(), out3.len()), 1);
        assert_eq!(&out3[..], &out[..36]);
        let c: &[u8] = b"carol-public-bundle-bytes";
        let mut out4 = [0u8; 64];
        assert_eq!(pqc_security_code(a.as_ptr(), a.len(), c.as_ptr(), c.len(), out4.as_mut_ptr(), out4.len()), 1);
        assert_ne!(&out4[..35], &out[..35]);
        assert_eq!(pqc_security_code(a.as_ptr(), a.len(), b.as_ptr(), b.len(), out.as_mut_ptr(), 35), 0);
        assert_eq!(pqc_security_code(a.as_ptr(), a.len(), b.as_ptr(), b.len(), out.as_mut_ptr(), 0), 0);
        assert_eq!(pqc_security_code(ptr::null(), a.len(), b.as_ptr(), b.len(), out.as_mut_ptr(), 64), 0);
        assert_eq!(pqc_security_code(a.as_ptr(), a.len(), ptr::null(), b.len(), out.as_mut_ptr(), 64), 0);
        assert_eq!(pqc_security_code(a.as_ptr(), a.len(), b.as_ptr(), b.len(), ptr::null_mut(), 64), 0);
    }
}

#[test]
fn c_free_and_secure_zero() {
    unsafe {
        pqc_free(ptr::null_mut(), 0);
        pqc_free(ptr::null_mut(), 123);
        pqc_secure_zero(ptr::null_mut(), 10);
        let mut buf = [0xABu8; 8];
        pqc_secure_zero(buf.as_mut_ptr(), 0);
        assert_eq!(buf, [0xAB; 8]);
        pqc_secure_zero(buf.as_mut_ptr(), 3);
        assert_eq!(&buf[..3], &[0, 0, 0]);
        assert_eq!(&buf[3..], &[0xAB; 5]);
        pqc_secure_zero(buf.as_mut_ptr(), buf.len());
        assert_eq!(buf, [0; 8]);
        let (pub_der, _) = kem_pair();
        assert_eq!(pub_der.len(), 1206);
    }
}
