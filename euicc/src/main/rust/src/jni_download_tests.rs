use super::*;

/// The session store is process-global, so store tests must not run
/// concurrently with each other (cargo test defaults to parallel threads).
static TEST_LOCK: Mutex<()> = Mutex::new(());

fn session(id: &str) -> download::DownloadSession {
    download::DownloadSession::for_test(id)
}

#[test]
fn session_survives_take_on_another_thread() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
    sessions().clear();
    session_put("tx-cross-thread".to_string(), session("tx-cross-thread"));
    // Dispatchers.IO does not pin authenticate/finish to one thread; the
    // take must succeed from a different OS thread than the put.
    let taken = std::thread::spawn(|| session_take("tx-cross-thread"))
        .join()
        .unwrap();
    assert_eq!(taken.unwrap().transaction_id, "tx-cross-thread");
    assert!(session_take("tx-cross-thread").is_none());
}

#[test]
fn parse_tac_and_cancel_reason_and_empty_to_null() {
    assert_eq!(parse_tac("35290611"), Some([0x35, 0x29, 0x06, 0x11]));
    assert_eq!(parse_tac(" 35290611 "), Some(es10b::DEFAULT_TAC));
    // Malformed TAC (odd, non-hex, wrong length) falls back to None.
    assert_eq!(parse_tac("3529061"), None);
    assert_eq!(parse_tac("zz290611"), None);
    assert_eq!(parse_tac("3529061100"), None);
    assert_eq!(parse_tac(""), None);
    assert_eq!(cancel_reason(0), es10b::CancelReason::EndUserRejection);
    assert_eq!(cancel_reason(1), es10b::CancelReason::Postponed);
    assert_eq!(cancel_reason(2), es10b::CancelReason::Timeout);
    assert_eq!(cancel_reason(3), es10b::CancelReason::PprNotAllowed);
    assert_eq!(cancel_reason(4), es10b::CancelReason::MetadataMismatch);
    assert_eq!(cancel_reason(5), es10b::CancelReason::LoadBppExecutionError);
    assert_eq!(cancel_reason(6), es10b::CancelReason::EndUserRejection);
    assert_eq!(cancel_reason(-1), es10b::CancelReason::EndUserRejection);
    assert_eq!(empty_to_null(""), serde_json::Value::Null);
    assert_eq!(empty_to_null("Carrier"), serde_json::Value::String("Carrier".to_string()));
}

#[test]
fn session_put_replaces_same_handle_and_take_missing() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
    sessions().clear();
    assert!(session_take("nope").is_none());
    session_put("tx-dup".to_string(), session("tx-dup"));
    session_put("tx-dup".to_string(), session("tx-dup"));
    // Re-put must not duplicate the handle (eviction counts once).
    assert_eq!(sessions().len(), 1);
    assert!(session_take("tx-dup").is_some());
    assert!(session_take("tx-dup").is_none());
}

#[test]
fn download_helpers_json_and_options() {
    let ok = es10b::InstallResult { success: true, message: "Profile installed".into(), seq_number: 1, installed_iccid: "abcd".into(), bpp_command_id: None, error_reason: None, notification: vec![] };
    let v = download_result_json(Ok(ok.clone()));
    assert_eq!(v["success"], true);
    assert_eq!(v["iccid"], "abcd");
    let v = download_result_json(Err("nope".into()));
    assert_eq!(v["success"], false);
    assert_eq!(v["message"], "nope");
    assert_eq!(v["iccid"], serde_json::Value::Null);
    let s = session("tx-json");
    let v = authenticate_success_json(&s);
    assert_eq!(v["transactionId"], "tx-json");
    assert_eq!(v["error"], serde_json::Value::Null);
    assert_eq!(v["ccRequired"], false);
    let v = authenticate_error_json("bad code");
    assert_eq!(v["error"], "bad code");
    assert_eq!(v["transactionId"], serde_json::Value::Null);
    assert_eq!(finish_json_value(false, "expired")["message"], "expired");
    // Options: blank dropped, bad TAC falls back to default, good TAC kept.
    let o = download_options_from(Some("  ".into()), Some("zz".into()), Some("  ".into()));
    assert_eq!(o.confirmation_code, None);
    assert_eq!(o.imei, None);
    assert_eq!(o.tac, Some(es10b::DEFAULT_TAC));
    let o = download_options_from(Some("1234".into()), Some("35290611".into()), Some("490154203237518".into()));
    assert_eq!(o.confirmation_code.as_deref(), Some("1234"));
    assert_eq!(o.tac, Some([0x35, 0x29, 0x06, 0x11]));
    assert!(o.imei.is_some());
    let o = download_options_from(None, None, None);
    assert_eq!(o.tac, Some(es10b::DEFAULT_TAC));
    assert_eq!(non_empty(Some(String::new())), None);
    assert_eq!(non_empty(Some(" x".into())), Some(" x".to_string()));
    assert_eq!(non_blank(Some("  ".into())), None);
    // Nickname seeding only on success with a decodable ICCID + name.
    assert!(nickname_request(&ok, Some(" Carrier ")).is_some());
    assert!(nickname_request(&ok, None).is_none());
    let failed = es10b::InstallResult { success: false, ..ok.clone() };
    assert!(nickname_request(&failed, Some("x")).is_none());
    let no_iccid = es10b::InstallResult { installed_iccid: String::new(), ..ok.clone() };
    assert!(nickname_request(&no_iccid, Some("x")).is_none());
    assert!(progress_decision(Some(false)) == false);
    assert!(progress_decision(Some(true)));
    assert!(progress_decision(None));
}

#[test]
fn run_flows_with_mock_transport() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
    sessions().clear();
    // run_download: bad code, success, failure.
    let mut ok_fn = |_: &mut (), _c: &str| Ok(es10b::InstallResult { success: true, message: "m".into(), seq_number: 0, installed_iccid: "aa".into(), bpp_command_id: None, error_reason: None, notification: vec![] });
    assert_eq!(run_download(&mut (), None, &mut ok_fn)["message"], "Invalid activation code");
    assert_eq!(run_download(&mut (), Some("LPA:1".into()), &mut ok_fn)["iccid"], "aa");
    let mut err_fn = |_: &mut (), _c: &str| Err::<es10b::InstallResult, String>("down".into());
    assert_eq!(run_download(&mut (), Some("LPA:1".into()), &mut err_fn)["message"], "down");
    // run_authenticate success stores the session; failure does not.
    let mut auth_ok = |_: &mut (), _c: &str, _o: &download::DownloadOptions| Ok(session("tx-flow"));
    let v = run_authenticate(&mut (), "code", &download::DownloadOptions::default(), &mut auth_ok);
    assert_eq!(v["transactionId"], "tx-flow");
    assert!(session_take("tx-flow").is_some());
    let mut auth_err = |_: &mut (), _c: &str, _o: &download::DownloadOptions| Err::<download::DownloadSession, String>("bad".into());
    assert_eq!(run_authenticate(&mut (), "code", &download::DownloadOptions::default(), &mut auth_err)["error"], "bad");
    // run_finish: expired, success (with nickname seeding), failure.
    let mut fin_ok = |_: &mut (), _s: &download::DownloadSession, _c: Option<&str>| Ok(es10b::InstallResult { success: true, message: "m".into(), seq_number: 0, installed_iccid: "abcd".into(), bpp_command_id: None, error_reason: None, notification: vec![] });
    let applied = std::cell::RefCell::new(Vec::<Vec<u8>>::new());
    let mut apply = |_: &mut (), req: &[u8]| applied.borrow_mut().push(req.to_vec());
    assert_eq!(run_finish(&mut (), "missing", None, None, &mut fin_ok, &mut apply)["message"], "Download session expired — start again");
    session_put("tx-fin".into(), session("tx-fin"));
    let v = run_finish(&mut (), "tx-fin", Some("1234"), Some("Carrier"), &mut fin_ok, &mut apply);
    assert_eq!(v["success"], true);
    assert_eq!(applied.borrow().len(), 1, "nickname seeded once");
    session_put("tx-fin2".into(), session("tx-fin2"));
    let mut fin_err = |_: &mut (), _s: &download::DownloadSession, _c: Option<&str>| Err::<es10b::InstallResult, String>("install broke".into());
    assert_eq!(run_finish(&mut (), "tx-fin2", None, None, &mut fin_err, &mut apply)["message"], "install broke");
    // run_cancel: bad handle false; present session cancelled + consumed; absent still true.
    let cancelled = std::cell::RefCell::new(Vec::<i32>::new());
    let mut cancel_fn = |_: &mut (), _s: &download::DownloadSession, r: es10b::CancelReason| cancelled.borrow_mut().push(r as i32);
    assert!(!run_cancel(&mut (), None, 0, &mut cancel_fn));
    session_put("tx-cancel".into(), session("tx-cancel"));
    assert!(run_cancel(&mut (), Some("tx-cancel".into()), 2, &mut cancel_fn));
    assert_eq!(*cancelled.borrow(), vec![2]);
    assert!(run_cancel(&mut (), Some("tx-cancel".into()), 0, &mut cancel_fn));
    assert_eq!(cancelled.borrow().len(), 1);
}

#[test]
fn session_eviction_is_oldest_first() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
    sessions().clear();
    for i in 0..=MAX_SESSIONS {
        session_put(format!("tx-{i}"), session(&format!("tx-{i}")));
    }
    assert!(session_take("tx-0").is_none(), "oldest must be evicted");
    for i in 1..=MAX_SESSIONS {
        assert!(session_take(&format!("tx-{i}")).is_some());
    }
}
