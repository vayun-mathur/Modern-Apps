use super::*;

#[test]
fn empty_command_sends_one_empty_last_block() {
    assert_eq!(
        build_store_data_apdus(&[], STORE_DATA_CHUNK),
        vec![vec![0x80, 0xE2, 0x91, 0x00, 0x00]]
    );
}

#[test]
fn single_block_is_last_with_p1_91() {
    assert_eq!(
        build_store_data_apdus(&[0xBF, 0x2E, 0x00], STORE_DATA_CHUNK),
        vec![vec![0x80, 0xE2, 0x91, 0x00, 0x03, 0xBF, 0x2E, 0x00]]
    );
}

#[test]
fn multi_block_chunking_marks_only_last() {
    let cmd: Vec<u8> = (0..130u8).collect();
    let apdus = build_store_data_apdus(&cmd, 63);
    assert_eq!(apdus.len(), 3);
    assert_eq!(&apdus[0][..5], &[0x80, 0xE2, 0x11, 0x00, 63]);
    assert_eq!(&apdus[1][..5], &[0x80, 0xE2, 0x11, 0x01, 63]);
    assert_eq!(&apdus[2][..5], &[0x80, 0xE2, 0x91, 0x02, 4]);
    // Payloads reassemble to the original command.
    let joined: Vec<u8> = apdus.iter().flat_map(|a| a[5..].iter().copied()).collect();
    assert_eq!(joined, cmd);
}

#[test]
fn exact_chunk_multiple_has_no_trailing_empty_block() {
    let apdus = build_store_data_apdus(&[0xAA; 126], 63);
    assert_eq!(apdus.len(), 2);
    assert_eq!(apdus[1][2], 0x91);
    assert_eq!(apdus[1][4], 63);
}

#[test]
fn chunk_is_clamped_to_apdu_limits() {
    // 0 clamps to 1: one APDU per byte.
    let apdus = build_store_data_apdus(&[1, 2, 3], 0);
    assert_eq!(apdus.len(), 3);
    assert!(apdus.iter().all(|a| a[4] == 1));
    // >255 clamps to 255.
    let big: Vec<u8> = vec![0x55; 300];
    let apdus = build_store_data_apdus(&big, 1000);
    assert_eq!(apdus.len(), 2);
    assert_eq!(apdus[0][4], 255);
    assert_eq!(apdus[1][4], 45);
}

#[test]
fn store_data_with_reassembles_blocks() {
    // Two chunks; intermediate reply carries data, final carries data + SW.
    let cmd: Vec<u8> = (0..70u8).collect();
    let mut replies: Vec<Vec<u8>> = vec![vec![0xAA, 0x90, 0x00], vec![0xBB, 0xCC, 0x90, 0x00]];
    let mut tx = |_: &mut (), _apdu: &[u8]| Ok::<Vec<u8>, String>(replies.remove(0));
    assert_eq!(store_data_with(&mut (), &cmd, 63, &mut tx).unwrap(), vec![0xAA, 0xBB, 0xCC]);
    // Empty command: one empty block, empty data back.
    let mut tx = |_: &mut (), _apdu: &[u8]| Ok::<Vec<u8>, String>(vec![0x90, 0x00]);
    assert_eq!(store_data_with(&mut (), &[], 63, &mut tx).unwrap(), Vec::<u8>::new());
}

#[test]
fn store_data_with_error_rules() {
    let mut short = |_: &mut (), _apdu: &[u8]| Ok::<Vec<u8>, String>(vec![0x90]);
    assert_eq!(store_data_with(&mut (), &[1], 63, &mut short).unwrap_err(), "APDU response too short");
    let mut bad_sw = |_: &mut (), _apdu: &[u8]| Ok::<Vec<u8>, String>(vec![0x6A, 0x88]);
    assert_eq!(store_data_with(&mut (), &[1], 63, &mut bad_sw).unwrap_err(), "eUICC returned SW=6A88");
    // Intermediate non-success SW fails fast even if a later block would succeed.
    let mut replies: Vec<Vec<u8>> = vec![vec![0x63, 0xC1], vec![0x90, 0x00]];
    let mut tx = |_: &mut (), _apdu: &[u8]| Ok::<Vec<u8>, String>(replies.remove(0));
    assert_eq!(store_data_with(&mut (), &[0u8; 70], 63, &mut tx).unwrap_err(), "eUICC returned SW=63C1");
    let mut boom = |_: &mut (), _apdu: &[u8]| Err::<Vec<u8>, String>("transport down".into());
    assert_eq!(store_data_with(&mut (), &[1], 63, &mut boom).unwrap_err(), "transport down");
}

#[test]
fn json_converters_and_result_code() {
    let p = crate::es10::ProfileInfo { iccid: "89".into(), iccid_display: "8901".into(), isdp_aid: "A0".into(), state: "enabled".into(), class: "operational".into(), nickname: "nick".into(), service_provider: "Carrier".into(), name: "Plan".into() };
    let v: serde_json::Value = serde_json::from_str(&profiles_json(&[p])).unwrap();
    assert_eq!(v[0]["iccidDisplay"], "8901");
    assert_eq!(v[0]["serviceProvider"], "Carrier");
    assert_eq!(profiles_json(&[]), "[]");
    let n = crate::es10::NotificationInfo { seq_number: 7, operation: "install".into(), address: "a.b".into(), iccid_display: "8901".into() };
    let v: serde_json::Value = serde_json::from_str(&notifications_json(&[n])).unwrap();
    assert_eq!(v[0]["seqNumber"], 7);
    assert_eq!(v[0]["operation"], "install");
    let info = crate::es10::EuiccInfo1 { svn: "2.2.0".into(), ci_pkid_verification: vec!["aa".into()], ci_pkid_signing: vec!["bb".into()] };
    let v: serde_json::Value = serde_json::from_str(&euicc_info_json(&info)).unwrap();
    assert_eq!(v["svn"], "2.2.0");
    assert_eq!(v["ciPkIdListForVerification"][0], "aa");
    // result_code: build a parseable Enable result (BF31 { 80 00 }) and failures.
    let ok_resp = crate::es10::build_enable(&[0x01], true);
    let _ = ok_resp;
    let resp = crate::asn1::tlv(crate::es10::RESULT_TAG_ENABLE, &crate::asn1::tlv(0x80, &[0x00]));
    assert_eq!(result_code(Ok(resp), crate::es10::RESULT_TAG_ENABLE), 0);
    let resp = crate::asn1::tlv(crate::es10::RESULT_TAG_ENABLE, &crate::asn1::tlv(0x80, &[0x02]));
    assert_eq!(result_code(Ok(resp), crate::es10::RESULT_TAG_ENABLE), 2);
    assert_eq!(result_code(Err("x".into()), crate::es10::RESULT_TAG_ENABLE), -1);
    assert_eq!(result_code(Ok(vec![]), crate::es10::RESULT_TAG_ENABLE), -1);
    assert_eq!(seq_arg(-1), None);
    assert_eq!(seq_arg(0), Some(0));
    assert_eq!(seq_arg(42), Some(42));
}

#[test]
fn remove_code_mapping() {
    assert_eq!(remove_code(Err("x".into())), -1);
    assert_eq!(remove_code(Ok(vec![])), -1);
}
