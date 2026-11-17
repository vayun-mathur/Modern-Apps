use super::*;

fn tlv(tag: u32, v: &[u8]) -> Vec<u8> { asn1::tlv(tag, v) }

#[test]
fn name_tables() {
    assert_eq!(bpp_command_name(0), "initialise_secure_channel");
    assert_eq!(bpp_command_name(5), "load_profile_elements");
    assert_eq!(bpp_command_name(6), "unknown");
    assert_eq!(bpp_command_name(-1), "unknown");
    assert_eq!(error_reason_name(1), "incorrect_input_values");
    assert_eq!(error_reason_name(15), "ppr_not_allowed");
    assert_eq!(error_reason_name(127), "install_failed_due_to_unknown_error");
    assert_eq!(error_reason_name(16), "unknown");
}

#[test]
fn challenge_and_request_builders() {
    assert_eq!(build_get_euicc_challenge(), vec![0xBF, 0x2E, 0x00]);
    assert!(parse_euicc_challenge(&[]).is_err());
    assert!(parse_euicc_challenge(&[0xBF, 0x2E]).is_err());
    let body = tlv(TAG_EUICC_CHALLENGE, &[0xAB; 16]);
    let resp = tlv(TAG_GET_EUICC_CHALLENGE, &body);
    assert_eq!(parse_euicc_challenge(&resp).unwrap(), vec![0xAB; 16]);
    // BF2E without inner 80 errors.
    assert!(parse_euicc_challenge(&tlv(TAG_GET_EUICC_CHALLENGE, &tlv(0x04, &[1]))).is_err());

    // AuthenticateServer concatenates blobs verbatim inside BF38.
    let req = build_authenticate_server(&[0x30, 0x00], &[0x03, 0x00], &[0x04, 0x00], &[0x30, 0x00], &[0xA0, 0x00]);
    let body = asn1::find(&req, TAG_AUTHENTICATE_SERVER).unwrap();
    assert_eq!(body, &[0x30, 0x00, 0x03, 0x00, 0x04, 0x00, 0x30, 0x00, 0xA0, 0x00]);
    // PrepareDownload omits hashCc when None, includes tag 04 when Some.
    let no_cc = build_prepare_download(&[0x30, 0x00], &[0x03, 0x00], None, &[0x30, 0x00]);
    assert!(asn1::find(asn1::find(&no_cc, TAG_PREPARE_DOWNLOAD).unwrap(), TAG_HASH_CC).is_none());
    let with_cc = build_prepare_download(&[0x30, 0x00], &[0x03, 0x00], Some(&[0xCC; 32]), &[0x30, 0x00]);
    assert_eq!(
        asn1::find(asn1::find(&with_cc, TAG_PREPARE_DOWNLOAD).unwrap(), TAG_HASH_CC).unwrap(),
        &[0xCC; 32]
    );
}

#[test]
fn imei_gsm_bcd_edges() {
    // 15 digits -> 8 octets, pair-swapped with F pad on the odd digit.
    assert_eq!(
        encode_imei_gsm_bcd("490154203237518").unwrap(),
        vec![0x94, 0x10, 0x45, 0x02, 0x23, 0x73, 0x15, 0xF8]
    );
    assert!(encode_imei_gsm_bcd(" 490154203237518 ").is_some()); // trimmed
    assert_eq!(encode_imei_gsm_bcd("12345678901234").unwrap().len(), 7); // 14 digits
    assert!(encode_imei_gsm_bcd("123").is_none());
    assert!(encode_imei_gsm_bcd("49015420323751a").is_none());
    assert!(encode_imei_gsm_bcd("").is_none());
    assert!(encode_imei_gsm_bcd("12345678901234567").is_none()); // 17 digits
}

#[test]
fn ctx_params1_with_and_without_imei() {
    let plain = build_ctx_params1("M1", &DEFAULT_TAC, None);
    let body = asn1::find(&plain, TAG_CTX_PARAMS_COMMON).unwrap();
    assert_eq!(asn1::find(body, TAG_MATCHING_ID).unwrap(), b"M1");
    // Invalid IMEI is dropped, valid IMEI appears as tag 82 in deviceInfo.
    let dropped = build_ctx_params1("M1", &DEFAULT_TAC, Some("nope"));
    assert_eq!(dropped, plain);
    let with = build_ctx_params1("M1", &DEFAULT_TAC, Some("490154203237518"));
    let di = asn1::find(body_of(&with), TAG_DEVICE_INFO).unwrap();
    assert!(asn1::find(di, TAG_IMEI).is_some());
}

fn body_of(req: &[u8]) -> &[u8] { asn1::find(req, TAG_CTX_PARAMS_COMMON).unwrap() }

#[test]
fn segment_bpp_full_shape_and_errors() {
    let isc = tlv(TAG_INITIALISE_SECURE_CHANNEL, &[0x01, 0x02]);
    let a0 = tlv(TAG_BPP_SEQ_87, &[0x09]);
    let mut a1v = tlv(0x88, &[0x01]);
    a1v.extend(tlv(0x88, &[0x02, 0x03]));
    let a1 = tlv(TAG_BPP_SEQ_88, &a1v);
    let a2 = tlv(TAG_BPP_SEQ_87B, &[0x07]);
    let a3 = tlv(TAG_BPP_SEQ_86, &tlv(0x86, &[0x05]));
    let mut body = Vec::new();
    for part in [&isc, &a0, &a1, &a2, &a3] { body.extend_from_slice(part); }
    let bpp = tlv(TAG_BPP, &body);
    let segs = segment_bpp(&bpp).unwrap();
    // [hdr+BF23][A0][A1 hdr][88][88][A2][A3 hdr][86]
    assert_eq!(segs.len(), 8);
    assert!(segs[0].starts_with(&bpp[..bpp.len() - body.len()])); // BF36 header verbatim
    assert!(segs[0].ends_with(&isc));
    assert_eq!(segs[1], a0);
    assert_eq!(segs[2], asn1::header(TAG_BPP_SEQ_88, a1v.len() as usize));
    assert_eq!(segs[3], tlv(0x88, &[0x01]));
    assert_eq!(segs[5], a2);
    // Without optional A2 there are 7 segments.
    let mut body2 = Vec::new();
    for part in [&isc, &a0, &a1, &a3] { body2.extend_from_slice(part); }
    assert_eq!(segment_bpp(&tlv(TAG_BPP, &body2)).unwrap().len(), 7);
    // Missing pieces error, never panic.
    assert!(segment_bpp(&[]).is_err());
    assert!(segment_bpp(&tlv(TAG_BPP, &a0)).is_err()); // no BF23
    let mut no_a1 = Vec::new();
    for part in [&isc, &a0, &a3] { no_a1.extend_from_slice(part); }
    assert!(segment_bpp(&tlv(TAG_BPP, &no_a1)).is_err());
    let mut bad_a1 = Vec::new();
    for part in [&isc, &a0, &tlv(TAG_BPP_SEQ_88, &[0x88, 0x05, 0x01]), &a3] { bad_a1.extend_from_slice(part); }
    assert!(segment_bpp(&tlv(TAG_BPP, &bad_a1)).is_err()); // truncated 88 child
}

#[test]
fn install_result_error_detail_combinations() {
    let mk = |final_body: Vec<u8>| {
        let data = tlv(TAG_PIR_DATA, &tlv(TAG_FINAL_RESULT, &final_body));
        tlv(TAG_PROFILE_INSTALL_RESULT, &data)
    };
    // Only bppCommandId.
    let err = tlv(TAG_ERROR_RESULT, &tlv(TAG_BPP_COMMAND_ID, &[0x03]));
    let r = parse_install_result(&mk(err)).unwrap();
    assert!(!r.success && r.bpp_command_id == Some(3) && r.error_reason.is_none());
    assert!(r.message.contains("store_metadata2"));
    // Only errorReason.
    let err = tlv(TAG_ERROR_RESULT, &tlv(TAG_ERROR_REASON, &[0x0A]));
    let r = parse_install_result(&mk(err)).unwrap();
    assert!(r.message.contains("insufficient_memory"));
    // Neither field.
    let r = parse_install_result(&mk(tlv(TAG_ERROR_RESULT, &[]))).unwrap();
    assert!(r.message.contains("unknown"));
    // Neither success nor error CHOICE.
    let r = parse_install_result(&mk(tlv(0xA3, &[]))).unwrap();
    assert!(!r.success && r.message.contains("unknown result"));
    assert_eq!(r.seq_number, -1); // no NotificationMetadata
    // Missing wrappers error, never panic.
    assert!(parse_install_result(&[]).is_err());
    assert!(parse_install_result(&tlv(TAG_PROFILE_INSTALL_RESULT, &[])).is_err());
    assert!(parse_install_result(&tlv(TAG_PROFILE_INSTALL_RESULT, &tlv(TAG_PIR_DATA, &[]))).is_err());
    assert!(find_child_offset(&[0x30, 0x00], 0x80).is_none());
    assert!(find_child_offset(&[0xFF], 0x80).is_none());
}

#[test]
fn metadata_cc_and_transaction_edges() {
    // Metadata: unknown tags skipped, class table, malformed body errors.
    let mut body = tlv(0x99, &[0x01]);
    body.extend(tlv(TAG_METADATA_CLASS, &[0x01]));
    body.extend(tlv(TAG_ICCID, &[0x98, 0x10]));
    let meta = parse_profile_metadata(&tlv(TAG_PROFILE_METADATA, &body)).unwrap();
    assert_eq!(meta.class, "provisioning");
    assert_eq!(meta.iccid_display, "8901");
    assert!(parse_profile_metadata(&tlv(TAG_PROFILE_METADATA, &[0x5A, 0x05, 0x01])).is_err());
    assert!(parse_profile_metadata(&[]).is_err());
    for (b, name) in [(0u8, "test"), (2, "operational"), (9, "unknown")] {
        let m = parse_profile_metadata(&tlv(TAG_PROFILE_METADATA, &tlv(TAG_METADATA_CLASS, &[b]))).unwrap();
        assert_eq!(m.class, name);
    }
    // ccRequiredFlag: absent / zero / non-zero / empty value.
    let signed = |flag: Option<&[u8]>| {
        let mut s = tlv(TAG_TX_ID, &[0x01]);
        if let Some(f) = flag { s.extend(tlv(TAG_CC_FLAG, f)); }
        tlv(TAG_SIGNED_SEQUENCE, &s)
    };
    assert!(!cc_required(&signed(None)));
    assert!(!cc_required(&signed(Some(&[0x00]))));
    assert!(cc_required(&signed(Some(&[0xFF]))));
    assert!(!cc_required(&signed(Some(&[]))));
    assert!(!cc_required(&[0x30, 0x00]));
    // Transaction id extraction errors.
    assert!(extract_transaction_id(&tlv(TAG_SIGNED_SEQUENCE, &tlv(0x04, &[1]))).is_err());
    assert!(extract_transaction_id(&[0x80, 0x01, 0x00]).is_err());
}

#[test]
fn retrieve_and_cancel_shapes() {
    assert_eq!(
        build_retrieve_notification(5),
        vec![0xBF, 0x2B, 0x05, 0xA0, 0x03, 0x80, 0x01, 0x05]
    );
    let cancel = build_cancel_session(&[0xDE, 0xAD], CancelReason::Timeout);
    let body = asn1::find(&cancel, TAG_CANCEL_SESSION).unwrap();
    assert_eq!(asn1::find(body, TAG_TX_ID).unwrap(), &[0xDE, 0xAD]);
    assert_eq!(asn1::find(body, TAG_CANCEL_REASON).unwrap(), &[0x02]);
    assert_eq!(CancelReason::default(), CancelReason::EndUserRejection);

    // Retrieved notification via the 0x30 otherSignedNotification path.
    let meta = tlv(TAG_NOTIFICATION_METADATA, &tlv(TAG_NOTIFICATION_ADDRESS, b"ds.example"));
    let other = tlv(TAG_SIGNED_SEQUENCE, &meta);
    let list = tlv(TAG_NOTIFICATION_LIST, &other);
    let resp = tlv(TAG_RETRIEVE_NOTIFICATIONS, &list);
    let (addr, raw) = parse_retrieved_notification(&resp).unwrap();
    assert_eq!(addr, "ds.example");
    assert_eq!(raw, other);
    // Errors: no A0, empty list, BF37 without BF27.
    assert!(parse_retrieved_notification(&tlv(TAG_RETRIEVE_NOTIFICATIONS, &[])).is_err());
    let empty = tlv(TAG_RETRIEVE_NOTIFICATIONS, &tlv(TAG_NOTIFICATION_LIST, &[]));
    assert!(parse_retrieved_notification(&empty).is_err());
    let bad37 = tlv(
        TAG_RETRIEVE_NOTIFICATIONS,
        &tlv(TAG_NOTIFICATION_LIST, &tlv(TAG_PROFILE_INSTALL_RESULT, &[])),
    );
    assert!(parse_retrieved_notification(&bad37).is_err());
}
