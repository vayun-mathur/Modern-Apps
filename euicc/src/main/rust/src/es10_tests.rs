use super::*;

#[test]
fn scalar_int_edges() {
    assert_eq!(parse_int(&[0x00, 0x80]), 128);
    assert_eq!(parse_int(&[0xFF, 0x00]), -256);
    assert_eq!(parse_int(&[0x7F]), 127);
    assert_eq!(encode_int_minimal(255), vec![0x00, 0xFF]);
    assert_eq!(encode_int_minimal(256), vec![0x01, 0x00]);
    assert_eq!(encode_int_minimal(0x8000), vec![0x00, 0x80, 0x00]);
    assert_eq!(build_remove_notification(0), vec![0xBF, 0x30, 0x03, 0x80, 0x01, 0x00]);
    assert_eq!(build_remove_notification(128), vec![0xBF, 0x30, 0x04, 0x80, 0x02, 0x00, 0x80]);
}

#[test]
fn hex_codec_edges() {
    assert_eq!(hex(&[]), "");
    assert_eq!(hex(&[0xAB, 0x00, 0xFF]), "ab00ff");
    assert_eq!(hex_decode("AB00ff"), vec![0xAB, 0x00, 0xFF]);
    assert_eq!(hex_decode(""), Vec::<u8>::new());
    // Odd length / non-hex are malformed -> empty, never panic.
    assert_eq!(hex_decode("abc"), Vec::<u8>::new());
    assert_eq!(hex_decode("zz"), Vec::<u8>::new());
    assert_eq!(hex_decode("0g"), Vec::<u8>::new());
}

#[test]
fn iccid_invalid_nibbles_are_dropped() {
    // 0xA nibble is not a BCD digit and not F padding: dropped, digits kept.
    assert_eq!(decode_iccid(&[0x1A]), "1");
    assert_eq!(decode_iccid(&[0xFF]), "");
}

#[test]
fn eid_parse_errors() {
    assert!(parse_eid(&[]).is_err());
    assert!(parse_eid(&[0xBF, 0x3E]).is_err()); // truncated header
    // Wrong EID length (15 bytes) is an error naming the length.
    let mut inner = vec![0x5A, 0x0F];
    inner.extend(0..15u8);
    let resp = asn1::tlv(TAG_GET_EUICC_DATA, &inner);
    assert!(parse_eid(&resp).unwrap_err().contains("15"));
    // BF3E present but no 5A inside.
    let resp = asn1::tlv(TAG_GET_EUICC_DATA, &asn1::tlv(0x04, &[1]));
    assert!(parse_eid(&resp).is_err());
}

#[test]
fn euicc_info1_degenerate_inputs() {
    assert!(parse_euicc_info1(&[0x00]).is_err());
    // SVN of wrong length -> empty svn, lists default empty.
    let body = asn1::tlv(TAG_SVN, &[0x02, 0x02]);
    let resp = asn1::tlv(TAG_EUICC_INFO1, &body);
    let info = parse_euicc_info1(&resp).unwrap();
    assert_eq!(info.svn, "");
    assert!(info.ci_pkid_verification.is_empty());
    // Malformed list body (not TLV children) -> empty list, not an error.
    let bad_list = asn1::tlv(TAG_CI_PKID_VERIFICATION, &[0xFF]);
    let resp = asn1::tlv(TAG_EUICC_INFO1, &bad_list);
    assert!(parse_euicc_info1(&resp).unwrap().ci_pkid_verification.is_empty());
    // Two entries in one list keep order.
    let mut kids = asn1::tlv(0x04, &[0x01]);
    kids.extend(asn1::tlv(0x04, &[0x02]));
    let resp = asn1::tlv(TAG_EUICC_INFO1, &asn1::tlv(TAG_CI_PKID_SIGNING, &kids));
    assert_eq!(parse_euicc_info1(&resp).unwrap().ci_pkid_signing, vec!["01", "02"]);
}

#[test]
fn profiles_error_and_degenerate_shapes() {
    // No A0 -> error CHOICE.
    let resp = asn1::tlv(TAG_PROFILE_INFO_LIST, &asn1::tlv(0xA1, &[]));
    assert!(parse_profiles(&resp).is_err());
    // Empty A0 -> zero profiles.
    let resp = asn1::tlv(TAG_PROFILE_INFO_LIST, &asn1::tlv(TAG_PROFILE_LIST_OK, &[]));
    assert_eq!(parse_profiles(&resp).unwrap().len(), 0);
    // Non-E3 children skipped; unknown state/class -> "unknown"; missing
    // fields default empty.
    let mut p = Vec::new();
    p.extend(asn1::tlv(TAG_PROFILE_STATE, &[0x07]));
    p.extend(asn1::tlv(TAG_PROFILE_CLASS, &[]));
    let mut list_body = asn1::tlv(0x04, &[0x00]); // skipped child
    list_body.extend(asn1::tlv(TAG_PROFILE_INFO, &p));
    let resp = asn1::tlv(TAG_PROFILE_INFO_LIST, &asn1::tlv(TAG_PROFILE_LIST_OK, &list_body));
    let profiles = parse_profiles(&resp).unwrap();
    assert_eq!(profiles.len(), 1);
    assert_eq!(profiles[0].state, "unknown");
    assert_eq!(profiles[0].class, "unknown");
    assert_eq!(profiles[0].iccid, "");
    // Malformed list body errors, never panics.
    let resp = asn1::tlv(TAG_PROFILE_INFO_LIST, &asn1::tlv(TAG_PROFILE_LIST_OK, &[0xE3, 0x05, 0x01]));
    assert!(parse_profiles(&resp).is_err());
    // State/class name table.
    for (byte, name) in [(0u8, "disabled"), (1, "enabled")] {
        let mut p = Vec::new();
        p.extend(asn1::tlv(TAG_PROFILE_STATE, &[byte]));
        let resp = asn1::tlv(
            TAG_PROFILE_INFO_LIST,
            &asn1::tlv(TAG_PROFILE_LIST_OK, &asn1::tlv(TAG_PROFILE_INFO, &p)),
        );
        assert_eq!(parse_profiles(&resp).unwrap()[0].state, name);
    }
    for (byte, name) in [(0u8, "test"), (1, "provisioning"), (2, "operational")] {
        let mut p = Vec::new();
        p.extend(asn1::tlv(TAG_PROFILE_CLASS, &[byte]));
        let resp = asn1::tlv(
            TAG_PROFILE_INFO_LIST,
            &asn1::tlv(TAG_PROFILE_LIST_OK, &asn1::tlv(TAG_PROFILE_INFO, &p)),
        );
        assert_eq!(parse_profiles(&resp).unwrap()[0].class, name);
    }
}

#[test]
fn notifications_event_table_and_defaults() {
    let one = |event: Option<Vec<u8>>, seq: Option<Vec<u8>>| {
        let mut m = Vec::new();
        if let Some(s) = seq { m.extend(asn1::tlv(TAG_SEQ_NUMBER, &s)); }
        if let Some(e) = event { m.extend(asn1::tlv(TAG_NOTIFICATION_EVENT, &e)); }
        let resp = asn1::tlv(
            TAG_LIST_NOTIFICATION,
            &asn1::tlv(TAG_NOTIFICATION_LIST, &asn1::tlv(TAG_NOTIFICATION_METADATA, &m)),
        );
        parse_notifications(&resp).unwrap().remove(0)
    };
    assert_eq!(one(Some(vec![0x00, 0x80]), Some(vec![0x01])).operation, "install");
    assert_eq!(one(Some(vec![0x00, 0x20]), None).operation, "disable");
    assert_eq!(one(Some(vec![0x00, 0x10]), None).operation, "delete");
    assert_eq!(one(Some(vec![0x00, 0x00]), None).operation, "unknown");
    // Empty BIT STRING value and missing event both read "unknown"/-1.
    let n = one(Some(vec![]), None);
    assert_eq!(n.operation, "unknown");
    assert_eq!(n.seq_number, -1);
    assert_eq!(one(None, None).operation, "unknown");
    assert!(parse_notifications(&asn1::tlv(TAG_LIST_NOTIFICATION, &[])).is_err());
    assert_eq!(
        build_list_notifications(),
        vec![0xBF, 0x28, 0x00]
    );
    // Remove-result parse: success and missing-tag errors.
    let ok = asn1::tlv(TAG_REMOVE_NOTIFICATION, &asn1::tlv(TAG_SEQ_NUMBER, &[0x00]));
    assert_eq!(parse_remove_result(&ok).unwrap(), 0);
    assert!(parse_remove_result(&[]).is_err());
    assert!(parse_remove_result(&asn1::tlv(TAG_REMOVE_NOTIFICATION, &[])).is_err());
}

#[test]
fn get_profiles_request_and_non_metadata_notifications() {
    // build_get_profiles was only exercised via JNI; pin its exact bytes shape.
    let req = build_get_profiles();
    assert!(!req.is_empty());
    let parsed = crate::asn1::parse(&req).unwrap().0;
    assert_eq!(parsed.tag, TAG_PROFILE_INFO_LIST);
    // A notification list containing a non-metadata child skips that child.
    let meta = asn1::tlv(TAG_NOTIFICATION_METADATA, &asn1::tlv(TAG_SEQ_NUMBER, &[0x05]));
    let mut list_body = asn1::tlv(0x99, &[0x01]);
    list_body.extend(meta);
    let resp = asn1::tlv(TAG_LIST_NOTIFICATION, &asn1::tlv(TAG_NOTIFICATION_LIST, &list_body));
    let notes = parse_notifications(&resp).unwrap();
    assert_eq!(notes.len(), 1);
    assert_eq!(notes[0].seq_number, 5);
}
