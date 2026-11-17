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
