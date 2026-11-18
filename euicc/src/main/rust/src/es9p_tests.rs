use super::*;
use serde_json::json;

#[test]
fn base_url_and_host_edges() {
    assert_eq!(base_url("smdp.example.com"), "https://smdp.example.com");
    assert_eq!(base_url("  smdp.example.com///"), "https://smdp.example.com");
    assert_eq!(base_url("http://a.b:8443/"), "http://a.b:8443");
    assert_eq!(host_of("http://a.b/"), "a.b");
    assert_eq!(host_of("a.b:8443"), "a.b:8443");
    assert_eq!(
        endpoint("a.b:8443", "getBoundProfilePackage"),
        "https://a.b:8443/gsma/rsp2/es9plus/getBoundProfilePackage"
    );
}

#[test]
fn error_table_spot_checks_and_unknown() {
    assert_eq!(
        es9p_error_message("8.2.7", "6.4"),
        Some("The maximum number of retries for the Confirmation Code has been exceeded")
    );
    assert_eq!(
        es9p_error_message("8.10.1", "3.9"),
        Some("The RSP session identified by the TransactionID is unknown")
    );
    assert_eq!(
        es9p_error_message("8.9.5", "3.3"),
        Some("The Event Record already exists in the SM-DS (EventID duplicated)")
    );
    assert_eq!(es9p_error_message("8.1", "9.9"), None);
    assert_eq!(es9p_error_message("", ""), None);
}

#[test]
fn status_detail_fallbacks() {
    // Missing statusCodeData entirely: empty codes + unknown wording.
    let bare = json!({"status": "Failed"});
    assert_eq!(
        status_detail(&bare),
        "subjectCode  / subjectIdentifier  / reasonCode : unknown error"
    );
    // Non-string fields are ignored, server message wins when present.
    let mixed = json!({"statusCodeData": {"subjectCode": 8, "message": "m"}});
    assert!(status_detail(&mixed).ends_with(": m"));
    // Known code without message uses the lpac table.
    let known = json!({"statusCodeData": {"subjectCode": "8.8.1", "reasonCode": "3.8"}});
    assert!(status_detail(&known).contains("Invalid SM-DP+ Address"));
}

#[test]
fn function_status_variants() {
    let ok = json!({"header": {"functionExecutionStatus": {"status": "Executed-Success"}}});
    assert!(check_function_execution_status(&ok).is_ok());
    // Case-sensitive: lowercase success is a failure state.
    let lower = json!({"header": {"functionExecutionStatus": {"status": "executed-success"}}});
    assert!(check_function_execution_status(&lower).is_err());
    // Non-string status reads as empty -> error, never panic.
    let numeric = json!({"header": {"functionExecutionStatus": {"status": 0}}});
    assert!(check_function_execution_status(&numeric).is_err());
    let no_header = json!({"header": {}});
    assert!(check_function_execution_status(&no_header).is_err());
}

#[test]
fn missing_field_descriptions_for_scalars() {
    assert!(describe_missing(&Value::Null, "k").contains("empty reply"));
    assert!(describe_missing(&json!("hello"), "k").contains("string[5]"));
    assert!(describe_missing(&json!([1, 2]), "k").contains("array[2]"));
    assert!(describe_missing(&json!(true), "k").contains("bool(true)"));
    assert!(describe_missing(&json!(42), "k").contains("number(42)"));
    // Object keys are sorted in the message.
    let err = describe_missing(&json!({"b": 1, "a": 2}), "k");
    assert!(err.contains("a, b"), "sorted keys: {err}");
}

#[test]
fn get_str_and_b64_edges() {
    assert_eq!(get_str(&json!({"a": "x"}), "a").unwrap(), "x");
    assert!(get_str(&json!({"a": 1}), "a").is_err());
    assert_eq!(get_b64(&json!({"a": ""}), "a").unwrap(), Vec::<u8>::new());
    assert!(get_b64(&json!({"a": "not!b64"}), "a").is_err());
    assert!(get_b64(&json!({"a": 7}), "a").is_err());
}

#[test]
fn error_table_all_entries() {
    let cases: &[(&str, &str, &str)] = &[
        ("8.1", "4.8", "eUICC does not have sufficient space for this Profile"),
        ("8.1", "6.1", "eUICC signature is invalid or serverChallenge is invalid"),
        ("8.1.1", "2.2", "EID is missing (SM-DS address provided or MatchingID empty)"),
        ("8.1.1", "3.1", "a different EID is already associated with this ICCID"),
        ("8.1.1", "3.8", "EID doesn't match the expected value"),
        ("8.1.2", "6.1", "EUM Certificate is invalid"),
        ("8.1.2", "6.3", "EUM Certificate has expired"),
        ("8.1.3", "6.1", "eUICC Certificate is invalid"),
        ("8.1.3", "6.3", "eUICC Certificate has expired"),
        ("8.2", "1.2", "Profile has not yet been released"),
        ("8.2", "3.7", "BPP is not available for a new binding"),
        ("8.2.1", "1.2", "caller is not allowed to perform this function on the target Profile"),
        ("8.2.1", "3.1", "a different EID is associated with this ICCID"),
        ("8.2.1", "3.3", "the Profile identified by the provided ICCID is not available"),
        ("8.2.1", "3.5", "the target Profile cannot be released"),
        ("8.2.1", "3.9", "the Profile Type is unknown to the SM-DP+"),
        ("8.2.5", "1.2", "caller is not allowed to perform this function on the Profile Type"),
        ("8.2.5", "3.7", "No more Profile available for the requested Profile Type"),
        ("8.2.5", "3.8", "Profile Type is not aligned with the Profile identified by the ICCID"),
        ("8.2.5", "3.9", "the Profile Type is unknown to the SM-DP+"),
        ("8.2.5", "4.3", "No eligible Profile for this eUICC/Device"),
        ("8.2.6", "3.1", "a different MatchingID is associated with this ICCID"),
        ("8.2.6", "3.3", "Conflicting MatchingID value"),
        ("8.2.6", "3.8", "MatchingID (AC_Token or EventID) is refused"),
        ("8.2.7", "2.2", "Confirmation Code is missing"),
        ("8.2.7", "3.8", "Confirmation Code is refused"),
        ("8.2.7", "6.4", "The maximum number of retries for the Confirmation Code has been exceeded"),
        ("8.8", "3.1", "The provided SM-DP+ OID is invalid"),
        ("8.8.1", "3.8", "Invalid SM-DP+ Address"),
        ("8.8.2", "3.1", "None of the proposed Public Key Identifiers is supported by the SM-DP+"),
        ("8.8.3", "3.1", "The Specification Version Number indicated by the eUICC is not supported by the SM-DP+"),
        ("8.8.4", "3.7", "The SM-DP+ has no CERT.DPauth.ECDSA signed by one of the CI Public Key supported by the eUICC"),
        ("8.8.5", "4.1", "The Download order has expired"),
        ("8.8.5", "6.4", "The maximum number of retries for the Profile download order has been exceeded"),
        ("8.9", "4.2", "Root SM-DS has raised an error"),
        ("8.9", "5.1", "Root SM-DS was unavailable"),
        ("8.9.1", "3.8", "Invalid SM-DS Address"),
        ("8.9.2", "3.1", "None of the proposed Public Key Identifiers is supported by the SM-DS"),
        ("8.9.3", "3.1", "The Specification Version Number indicated by the eUICC is not supported by the SM-DS"),
        ("8.9.4", "3.7", "The SM-DS has no CERT.DS.ECDSA signed by one of the GSMA CI Public Key supported by the eUICC"),
        ("8.9.5", "3.3", "The Event Record already exists in the SM-DS (EventID duplicated)"),
        ("8.9.5", "3.9", "No Event identified by the Event ID for the EID exists"),
        ("8.10.1", "3.9", "The RSP session identified by the TransactionID is unknown"),
        ("8.11.1", "3.9", "Unknown CI Public Key. The CI used by the EUM Certificate is not a trusted root."),
    ];
    for (s, r, want) in cases {
        assert_eq!(es9p_error_message(s, r), Some(*want), "{s}/{r}");
    }
}

#[test]
fn request_bodies_encode_fields() {
    let b = initiate_body("https://smdp.example.com/", &[1, 2], &[3]);
    assert_eq!(b["smdpAddress"], "smdp.example.com");
    assert_eq!(b["euiccChallenge"], crate::base64::encode(&[1, 2]));
    assert_eq!(b["euiccInfo1"], crate::base64::encode(&[3]));
    let b = authenticate_client_body("tx-1", &[9]);
    assert_eq!(b["transactionId"], "tx-1");
    assert_eq!(b["authenticateServerResponse"], crate::base64::encode(&[9]));
    let b = get_bpp_body("tx-2", &[7, 8]);
    assert_eq!(b["transactionId"], "tx-2");
    assert_eq!(b["prepareDownloadResponse"], crate::base64::encode(&[7, 8]));
    let b = cancel_session_body("tx-3", &[5]);
    assert_eq!(b["cancelSessionResponse"], crate::base64::encode(&[5]));
    let b = handle_notification_body(&[4, 4]);
    assert_eq!(b["pendingNotification"], crate::base64::encode(&[4, 4]));
}

#[test]
fn parse_response_helpers() {
    let enc = crate::base64::encode;
    let v = json!({"transactionId": "tx", "serverSigned1": enc(&[1]), "serverSignature1": enc(&[2]),
        "euiccCiPKIdToBeUsed": enc(&[3]), "serverCertificate": enc(&[4])});
    let r = parse_initiate_response(&v).unwrap();
    assert_eq!(r.transaction_id, "tx");
    assert_eq!(r.server_signed1, vec![1]);
    assert_eq!(r.server_signature1, vec![2]);
    assert_eq!(r.euicc_ci_pkid, vec![3]);
    assert_eq!(r.server_certificate, vec![4]);
    assert!(parse_initiate_response(&json!({"transactionId": "tx"})).is_err());
    let v = json!({"profileMetadata": enc(&[1]), "smdpSigned2": enc(&[2]),
        "smdpSignature2": enc(&[3]), "smdpCertificate": enc(&[4])});
    let r = parse_authenticate_client_response(&v).unwrap();
    assert_eq!(r.profile_metadata, vec![1]);
    assert_eq!(r.smdp_signed2, vec![2]);
    assert_eq!(r.smdp_signature2, vec![3]);
    assert_eq!(r.smdp_certificate, vec![4]);
    assert!(parse_authenticate_client_response(&json!({})).is_err());
}

#[test]
fn handle_response_status_and_body_rules() {
    assert!(handle_response("f", "https://x/y", 500, b"{}", true).unwrap_err().contains("HTTP 500"));
    assert!(handle_response("f", "https://x/y", 199, b"{}", true).is_err());
    assert!(handle_response("f", "https://x/y", 300, b"{}", true).is_err());
    assert_eq!(handle_response("handleNotification", "https://x/y", 204, b"", false).unwrap(), Value::Null);
    assert!(handle_response("initiateAuthentication", "https://x/y", 200, b"", true).unwrap_err().contains("empty body"));
    assert!(handle_response("f", "https://x/y", 200, b"not json", true).unwrap_err().contains("parse response"));
    let ok = br#"{"header":{"functionExecutionStatus":{"status":"Executed-Success"}},"k":1}"#;
    assert_eq!(handle_response("f", "https://x/y", 200, ok, true).unwrap()["k"], 1);
    let failed = br#"{"header":{"functionExecutionStatus":{"status":"Failed","statusCodeData":{"subjectCode":"8.1","reasonCode":"4.8"}}}}"#;
    assert!(handle_response("f", "https://x/y", 200, failed, true).unwrap_err().contains("sufficient space"));
    assert_eq!(summarize_scalar(&Value::Null), "null");
}

#[test]
fn public_calls_fail_without_http_bridge() {
    // No JVM/bridge in host tests: every ES9+ call must surface the bridge
    // error instead of panicking, and must not fabricate a success result.
    assert!(initiate_authentication("smdp.example.com", &[1], &[2]).is_err());
    assert!(authenticate_client("smdp.example.com", "tx", &[1]).is_err());
    assert!(get_bound_profile_package("smdp.example.com", "tx", &[1]).is_err());
    assert!(cancel_session("smdp.example.com", "tx", &[1]).is_err());
    assert!(handle_notification_at("smdp.example.com", &[1]).is_err());
    assert!(handle_notification("smdp.example.com", &[1]).is_err());
}
