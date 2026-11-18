use super::*;

#[test]
fn pkid_value_unwraps_one_tlv_layer_only() {
    // Bare value that is not a valid TLV is returned unchanged.
    assert_eq!(pkid_value(&[0xAA, 0xBB, 0xCC]), &[0xAA, 0xBB, 0xCC]);
    assert_eq!(pkid_value(&[]), &[] as &[u8]);
    // Truncated TLV header falls back to the raw field, never panics.
    assert_eq!(pkid_value(&[0x04]), &[0x04]);
    // Wrapped value unwraps to the inner bytes.
    let wrapped = crate::asn1::tlv(0x04, &[0x01, 0x02]);
    assert_eq!(pkid_value(&wrapped), &[0x01, 0x02]);
}

#[test]
fn ci_key_known_case_and_empty() {
    // eUICC lists come from hex() (lowercase); an uppercase entry must not
    // silently match a different encoding.
    let list = vec!["AABB".to_string()];
    assert!(!ci_key_known(&list, &[0xAA, 0xBB]));
    let list = vec!["aabb".to_string(), "ccdd".to_string()];
    assert!(ci_key_known(&list, &[0xCC, 0xDD]));
    assert!(!ci_key_known(&list, &[]));
}

#[test]
fn notification_seq_bounds() {
    assert_eq!(notification_seq(i64::MIN), None);
    assert_eq!(notification_seq(i64::from(u32::MAX)), Some(u32::MAX));
    assert_eq!(notification_seq(-2), None);
}

#[test]
fn check_ci_key_rules() {
    let info = crate::es10::EuiccInfo1 { svn: "2.2.0".into(), ci_pkid_verification: vec!["aabb".into()], ci_pkid_signing: vec![] };
    assert!(check_ci_key(&info, &[0xAA, 0xBB]).is_ok());
    let err = check_ci_key(&info, &[0xDD]).unwrap_err();
    assert!(err.contains("unknown to this eUICC") && err.contains("aabb"), "{err}");
    let empty = crate::es10::EuiccInfo1 { svn: String::new(), ci_pkid_verification: vec![], ci_pkid_signing: vec![] };
    assert!(check_ci_key(&empty, &[0xDD]).is_ok(), "no list never blocks");
}

#[test]
fn resolve_confirmation_code_rules() {
    assert_eq!(resolve_confirmation_code(false, None, None).unwrap(), None);
    assert_eq!(resolve_confirmation_code(false, Some("1234"), Some("9999")).unwrap(), Some("1234"));
    assert_eq!(resolve_confirmation_code(true, Some(""), Some("9999")).unwrap(), Some("9999"), "empty explicit falls back");
    assert_eq!(resolve_confirmation_code(true, None, Some("9999")).unwrap(), Some("9999"));
    assert!(resolve_confirmation_code(true, Some(""), None).unwrap_err().contains("confirmation code"));
    assert!(resolve_confirmation_code(true, None, None).is_err());
}

#[test]
fn build_session_parses_metadata_and_cc() {
    let ac = ActivationCode { smdp: "smdp.example.com".into(), matching_id: "M1".into(), confirmation_code_required: false };
    let mut meta_body = crate::asn1::tlv(0x91, b"Carrier");
    meta_body.extend(crate::asn1::tlv(0x92, b"Plan"));
    let r1 = crate::es9p::InitiateAuthResult { transaction_id: "tx-9".into(), server_signed1: vec![1], server_signature1: vec![2], euicc_ci_pkid: vec![3], server_certificate: vec![4] };
    // smdpSigned2: SEQUENCE { 80 tx, 01 FF } => cc required.
    let mut signed_inner = crate::asn1::tlv(0x80, &[0x01]);
    signed_inner.extend(crate::asn1::tlv(0x01, &[0xFF]));
    let r2 = crate::es9p::AuthenticateClientResult {
        profile_metadata: crate::asn1::tlv(0xBF25, &meta_body),
        smdp_signed2: crate::asn1::tlv(0x30, &signed_inner),
        smdp_signature2: vec![5], smdp_certificate: vec![6],
    };
    let s = build_session(ac, r1, r2, vec![0x01], &DownloadOptions::default()).unwrap();
    assert_eq!(s.transaction_id, "tx-9");
    assert!(s.cc_required);
    assert_eq!(s.metadata.service_provider, "Carrier");
    assert_eq!(s.metadata.profile_name, "Plan");
    // Bad metadata propagates.
    let r1b = crate::es9p::InitiateAuthResult { transaction_id: "t".into(), server_signed1: vec![], server_signature1: vec![], euicc_ci_pkid: vec![], server_certificate: vec![] };
    let r2b = crate::es9p::AuthenticateClientResult { profile_metadata: vec![], smdp_signed2: vec![], smdp_signature2: vec![], smdp_certificate: vec![] };
    let acb = ActivationCode { smdp: "s".into(), matching_id: "m".into(), confirmation_code_required: true };
    assert!(build_session(acb, r1b, r2b, vec![], &DownloadOptions::default()).is_err());
}

#[test]
fn install_segments_streaming_rules() {
    let segs = vec![vec![1u8], vec![2], vec![3]];
    // Happy path: last response returned, progress sees each segment.
    let mut seen: Vec<usize> = Vec::new();
    let mut tx = |_: &mut (), s: &[u8]| Ok::<Vec<u8>, String>(vec![s[0] + 10]);
    let mut prog = |_: &mut (), d: usize, _t: usize| { seen.push(d); true };
    assert_eq!(install_segments(&mut (), &segs, &mut tx, &mut prog).unwrap(), vec![13]);
    assert_eq!(seen, vec![1, 2, 3]);
    // Empty segment list returns empty last response.
    let mut tx2 = |_: &mut (), _s: &[u8]| Ok::<Vec<u8>, String>(vec![]);
    let mut prog2 = |_: &mut (), _d: usize, _t: usize| true;
    assert_eq!(install_segments(&mut (), &[], &mut tx2, &mut prog2).unwrap(), Vec::<u8>::new());
    // Transmit failure names the segment.
    let mut tx3 = |_: &mut (), _s: &[u8]| Err::<Vec<u8>, String>("boom".into());
    assert!(install_segments(&mut (), &segs, &mut tx3, &mut prog2).unwrap_err().contains("segment 1/3: boom"));
    // Progress veto cancels after first segment.
    let mut tx4 = |_: &mut (), s: &[u8]| Ok::<Vec<u8>, String>(vec![s[0]]);
    let mut veto = |_: &mut (), _d: usize, _t: usize| false;
    assert_eq!(install_segments(&mut (), &segs, &mut tx4, &mut veto).unwrap_err(), "Download cancelled");
    // Mid-stream typed BF37 error aborts; same blob as last segment does not.
    let err_blob = crate::asn1::tlv(0xBF37, &crate::asn1::tlv(0xBF27, &crate::asn1::tlv(0xA2, &crate::asn1::tlv(0xA1, &crate::asn1::tlv(0x80, &[0x03])))));
    let mut calls = 0usize;
    let mut tx5 = |_: &mut (), _s: &[u8]| { calls += 1; Ok::<Vec<u8>, String>(err_blob.clone()) };
    let mut prog3 = |_: &mut (), _d: usize, _t: usize| true;
    assert!(install_segments(&mut (), &segs, &mut tx5, &mut prog3).unwrap_err().contains("store_metadata2"));
    assert_eq!(calls, 1, "must abort on first segment");
    let mut tx6 = |_: &mut (), _s: &[u8]| Ok::<Vec<u8>, String>(err_blob.clone());
    assert_eq!(install_segments(&mut (), &segs[..1], &mut tx6, &mut prog3).unwrap(), err_blob);
}

#[test]
fn notification_target_prefers_own_address() {
    assert_eq!(notification_target("rsp.example.com", "smdp.example.com"), "rsp.example.com");
    assert_eq!(notification_target("", "smdp.example.com"), "smdp.example.com");
}

#[test]
fn request_builders_embed_inputs() {
    let r1 = crate::es9p::InitiateAuthResult { transaction_id: "tx".into(), server_signed1: vec![0x30, 0x00], server_signature1: vec![0x02], euicc_ci_pkid: vec![0x03], server_certificate: vec![0x04] };
    let req = authenticate_server_request(&r1, "MATCH-1", &DownloadOptions::default());
    assert!(!req.is_empty());
    let with_imei = DownloadOptions { confirmation_code: None, tac: Some([1, 2, 3, 4]), imei: Some("490154203237518".into()) };
    assert!(!authenticate_server_request(&r1, "MATCH-1", &with_imei).is_empty());
    // PrepareDownload: no code => request builds; bad signed2 => tx error.
    let mut s = DownloadSession::for_test("tx-prep");
    let mut signed_inner = crate::asn1::tlv(0x80, &[0xDE, 0xAD]);
    signed_inner.extend(crate::asn1::tlv(0x01, &[0x00]));
    s.server_signed2 = crate::asn1::tlv(0x30, &signed_inner);
    assert!(!prepare_download_request(&s, None).unwrap().is_empty());
    assert!(!prepare_download_request(&s, Some("123456")).unwrap().is_empty());
    s.server_signed2 = vec![0x30, 0x00];
    assert!(prepare_download_request(&s, None).is_err());
}

#[test]
fn deliver_notification_with_branches() {
    use std::cell::RefCell;
    // No seq: fallback post to SM-DP+, no retrieve/remove.
    let calls = RefCell::new(Vec::<String>::new());
    let mut retrieve = |_: &mut (), _seq: u32| -> Result<(String, Vec<u8>), String> { calls.borrow_mut().push("retrieve".into()); Err("x".into()) };
    let mut fallback = |addr: &str, _b: &[u8]| -> Result<(), String> { calls.borrow_mut().push(format!("fallback:{addr}")); Ok(()) };
    let mut post_at = |addr: &str, _b: &[u8]| -> Result<(), String> { calls.borrow_mut().push(format!("post:{addr}")); Ok(()) };
    let mut remove = |_: &mut (), seq: u32| { calls.borrow_mut().push(format!("remove:{seq}")); };
    deliver_notification_with(&mut (), "smdp.example.com", -1, &[9], &mut retrieve, &mut fallback, &mut post_at, &mut remove);
    assert_eq!(*calls.borrow(), vec!["fallback:smdp.example.com".to_string()]);
    // Retrieve failure: fallback post, no remove.
    calls.borrow_mut().clear();
    deliver_notification_with(&mut (), "smdp.example.com", 7, &[9], &mut retrieve, &mut fallback, &mut post_at, &mut remove);
    assert_eq!(*calls.borrow(), vec!["retrieve".to_string(), "fallback:smdp.example.com".to_string()]);
    // Success with own address: post there, then remove; empty address uses SM-DP+.
    let removed = RefCell::new(Vec::<u32>::new());
    let posted = RefCell::new(Vec::<String>::new());
    let mut retrieve_ok = |_: &mut (), _seq: u32| -> Result<(String, Vec<u8>), String> { Ok(("rsp.example.com".into(), vec![1, 2])) };
    let mut fb2 = |_addr: &str, _b: &[u8]| -> Result<(), String> { Ok(()) };
    let mut post2 = |addr: &str, _b: &[u8]| -> Result<(), String> { posted.borrow_mut().push(addr.to_string()); Ok(()) };
    let mut rem2 = |_: &mut (), seq: u32| { removed.borrow_mut().push(seq); };
    deliver_notification_with(&mut (), "smdp.example.com", 7, &[9], &mut retrieve_ok, &mut fb2, &mut post2, &mut rem2);
    assert_eq!(*posted.borrow(), vec!["rsp.example.com".to_string()]);
    assert_eq!(*removed.borrow(), vec![7]);
    // Post failure skips remove; empty address targets the SM-DP+.
    let mut retrieve_empty = |_: &mut (), _seq: u32| -> Result<(String, Vec<u8>), String> { Ok((String::new(), vec![3])) };
    let mut post_fail = |addr: &str, _b: &[u8]| -> Result<(), String> { posted.borrow_mut().push(addr.to_string()); Err("down".into()) };
    posted.borrow_mut().clear(); removed.borrow_mut().clear();
    deliver_notification_with(&mut (), "smdp.example.com", 8, &[9], &mut retrieve_empty, &mut fb2, &mut post_fail, &mut rem2);
    assert_eq!(*posted.borrow(), vec!["smdp.example.com".to_string()]);
    assert!(removed.borrow().is_empty());
}
