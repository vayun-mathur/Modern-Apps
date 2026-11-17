//! ES10b download-flow command builders and response parsers (SGP.22).
//!
//! The AuthenticateServer → PrepareDownload → LoadBoundProfilePackage sequence
//! plus profile-metadata preview, notification retrieval, and CancelSession.
//! Split from [`crate::es10`] (which keeps the ES10c local ops) along module
//! lines; shared tags and ASN.1 scalar helpers live there and are imported.
//!
//! Pure protocol logic, fully host-testable. Reimplemented for this repo,
//! following the open-source OpenEUICC / lpac (GPL-3.0-only).

use crate::asn1;
use crate::es10::{
    TAG_ICCID, TAG_NOTIFICATION_ADDRESS, TAG_NOTIFICATION_LIST, TAG_NOTIFICATION_METADATA,
    TAG_SEQ_NUMBER, decode_iccid, encode_int_minimal, first_byte, hex, parse_int, utf8,
};

// --- Tags (SGP.22) ---
const TAG_GET_EUICC_CHALLENGE: u32 = 0xBF2E; // ES10b GetEUICCChallenge
const TAG_EUICC_CHALLENGE: u32 = 0x80; // [0] euiccChallenge Octet16
const TAG_AUTHENTICATE_SERVER: u32 = 0xBF38; // ES10b AuthenticateServer
const TAG_PREPARE_DOWNLOAD: u32 = 0xBF21; // ES10b PrepareDownload
const TAG_HASH_CC: u32 = 0x04; // hashCc Octet32 (confirmation code)
const TAG_BPP: u32 = 0xBF36; // BoundProfilePackage
const TAG_INITIALISE_SECURE_CHANNEL: u32 = 0xBF23; // initialiseSecureChannelRequest
const TAG_BPP_SEQ_87: u32 = 0xA0; // firstSequenceOf87
const TAG_BPP_SEQ_88: u32 = 0xA1; // sequenceOf88
const TAG_BPP_SEQ_87B: u32 = 0xA2; // secondSequenceOf87
const TAG_BPP_SEQ_86: u32 = 0xA3; // sequenceOf86
const TAG_PROFILE_INSTALL_RESULT: u32 = 0xBF37; // ProfileInstallationResult
const TAG_PIR_DATA: u32 = 0xBF27; // profileInstallationResultData
const TAG_FINAL_RESULT: u32 = 0xA2; // [2] finalResult
const TAG_SUCCESS_RESULT: u32 = 0xA0; // [0] successResult
const TAG_ERROR_RESULT: u32 = 0xA1; // [1] errorResult
const TAG_BPP_COMMAND_ID: u32 = 0x80; // [0] bppCommandId INTEGER inside errorResult
const TAG_ERROR_REASON: u32 = 0x81; // [1] errorReason ENUMERATED inside errorResult

// ctxParams1 / DeviceInfo construction tags.
const TAG_CTX_PARAMS_COMMON: u32 = 0xA0; // [0] ctxParamsForCommonAuthentication
const TAG_MATCHING_ID: u32 = 0x80; // [0] matchingId UTF8String
const TAG_DEVICE_INFO: u32 = 0xA1; // [1] deviceInfo (IMPLICIT replaces SEQUENCE tag)
const TAG_TAC: u32 = 0x80; // [0] tac Octet4
const TAG_DEVICE_CAPS: u32 = 0xA1; // [1] deviceCapabilities SEQUENCE
const TAG_IMEI: u32 = 0x82; // imei Octet8, GSM-BCD, inside deviceInfo after capabilities

/// Default Type Allocation Code used when the device IMEI is unknown, matching
/// lpac (`euicc/es10b.c:es10b_authenticate_server_r`).
pub const DEFAULT_TAC: [u8; 4] = [0x35, 0x29, 0x06, 0x11];

// Profile metadata (ES8P StoreMetadata, tag BF25) and session tags.
const TAG_PROFILE_METADATA: u32 = 0xBF25; // StoreMetadata inside authenticateClient
const TAG_METADATA_SPN: u32 = 0x91; // serviceProviderName UTF8String
const TAG_METADATA_NAME: u32 = 0x92; // profileName UTF8String
const TAG_METADATA_CLASS: u32 = 0x95; // profileClass INTEGER
const TAG_RETRIEVE_NOTIFICATIONS: u32 = 0xBF2B; // ES10b RetrieveNotificationsList
const TAG_SEARCH_CRITERIA: u32 = 0xA0; // [0] searchCriteria
const TAG_CANCEL_SESSION: u32 = 0xBF41; // ES10b CancelSessionRequest
const TAG_CANCEL_REASON: u32 = 0x81; // [1] reason ENUMERATED
const TAG_TX_ID: u32 = 0x80; // [0] transactionId (inside signed SEQUENCEs)
const TAG_CC_FLAG: u32 = 0x01; // ccRequiredFlag BOOLEAN (inside smdpSigned2)
const TAG_SIGNED_SEQUENCE: u32 = 0x30; // SEQUENCE wrapping serverSigned1/smdpSigned2

// ---------------------------------------------------------------------------
// Download flow (ES10b): challenge, AuthenticateServer, PrepareDownload,
// LoadBoundProfilePackage
// ---------------------------------------------------------------------------

/// Outcome of installing a Bound Profile Package.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstallResult {
    pub success: bool,
    pub message: String,
    /// Sequence number from the result's NotificationMetadata (for retrieve/remove).
    pub seq_number: i64,
    /// Installed profile ICCID as raw hex (for the follow-up SetNickname).
    pub installed_iccid: String,
    /// Failing BPP command id (0-5) on error, `None` on success.
    pub bpp_command_id: Option<i64>,
    /// SGP.22 install error reason on error, `None` on success.
    pub error_reason: Option<i64>,
    /// The raw ProfileInstallationResult (BF37) to deliver via HandleNotification.
    pub notification: Vec<u8>,
}

/// BPP command id names, matching lpac's `euicc_bppcommandid2str`.
pub fn bpp_command_name(id: i64) -> &'static str {
    match id {
        0 => "initialise_secure_channel",
        1 => "configure_isdp",
        2 => "store_metadata",
        3 => "store_metadata2",
        4 => "replace_session_keys",
        5 => "load_profile_elements",
        _ => "unknown",
    }
}

/// SGP.22 install error reason names, matching lpac's `euicc_errorreason2str`.
pub fn error_reason_name(reason: i64) -> &'static str {
    match reason {
        1 => "incorrect_input_values",
        2 => "invalid_signature",
        3 => "invalid_transaction_id",
        4 => "unsupported_crt_values",
        5 => "unsupported_remote_operation_type",
        6 => "unsupported_profile_class",
        7 => "scp03t_structure_error",
        8 => "scp03t_security_error",
        9 => "install_failed_due_to_iccid_already_exists_on_euicc",
        10 => "install_failed_due_to_insufficient_memory_for_profile",
        11 => "install_failed_due_to_interruption",
        12 => "install_failed_due_to_pe_processing_error",
        13 => "install_failed_due_to_data_mismatch",
        14 => "test_profile_install_failed_due_to_invalid_naa_key",
        15 => "ppr_not_allowed",
        127 => "install_failed_due_to_unknown_error",
        _ => "unknown",
    }
}

/// Builds the ES10b GetEUICCChallenge request (empty BF2E).
pub fn build_get_euicc_challenge() -> Vec<u8> {
    asn1::tlv(TAG_GET_EUICC_CHALLENGE, &[])
}

/// Parses a GetEUICCChallenge response into the 16-byte challenge.
pub fn parse_euicc_challenge(response: &[u8]) -> Result<Vec<u8>, String> {
    let body = asn1::find(response, TAG_GET_EUICC_CHALLENGE).ok_or("GetEUICCChallenge: missing BF2E")?;
    let challenge = asn1::find(body, TAG_EUICC_CHALLENGE).ok_or("GetEUICCChallenge: missing challenge")?;
    Ok(challenge.to_vec())
}

/// Builds ctxParams1 for common authentication: matchingId + a minimal DeviceInfo.
///
/// `tac` is the 4-byte Type Allocation Code; deviceCapabilities is sent empty.
/// `imei` is the optional 15-digit IMEI string, GSM-BCD encoded as tag `0x82`
/// after the capabilities, matching lpac
/// (`euicc/es10b.c:es10b_authenticate_server_r`). An invalid IMEI is dropped
/// rather than failing the download.
pub fn build_ctx_params1(matching_id: &str, tac: &[u8; 4], imei: Option<&str>) -> Vec<u8> {
    let mut device_info_val = asn1::tlv(TAG_TAC, tac);
    device_info_val.extend(asn1::tlv(TAG_DEVICE_CAPS, &[])); // empty DeviceCapabilities
    if let Some(digits) = imei.and_then(encode_imei_gsm_bcd) {
        device_info_val.extend(asn1::tlv(TAG_IMEI, &digits));
    }
    let device_info = asn1::tlv(TAG_DEVICE_INFO, &device_info_val);

    let mut common = asn1::tlv(TAG_MATCHING_ID, matching_id.as_bytes());
    common.extend(device_info);
    asn1::tlv(TAG_CTX_PARAMS_COMMON, &common)
}

/// GSM-BCD-encodes a 15-digit IMEI string into 8 octets (pair-swapped nibbles,
/// `F`-padded when odd), matching lpac's `euicc_hexutil_gsmbcd2bin`. Returns
/// `None` when the input is not 14-16 ASCII digits.
pub fn encode_imei_gsm_bcd(imei: &str) -> Option<Vec<u8>> {
    let digits = imei.trim();
    if !(14..=16).contains(&digits.len()) || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let mut nibbles: Vec<u8> = digits.bytes().map(|b| b - b'0').collect();
    if !nibbles.len().is_multiple_of(2) {
        nibbles.push(0x0F);
    }
    let mut out = Vec::with_capacity(nibbles.len() / 2);
    let mut i = 0;
    while i < nibbles.len() {
        out.push((nibbles[i + 1] << 4) | nibbles[i]);
        i += 2;
    }
    Some(out)
}

/// Builds the ES10b AuthenticateServer request by concatenating the server blobs
/// (each already a complete DER TLV) with ctxParams1, wrapped in BF38.
pub fn build_authenticate_server(
    server_signed1: &[u8],
    server_signature1: &[u8],
    euicc_ci_pkid: &[u8],
    server_certificate: &[u8],
    ctx_params1: &[u8],
) -> Vec<u8> {
    let mut v = Vec::new();
    v.extend_from_slice(server_signed1);
    v.extend_from_slice(server_signature1);
    v.extend_from_slice(euicc_ci_pkid);
    v.extend_from_slice(server_certificate);
    v.extend_from_slice(ctx_params1);
    asn1::tlv(TAG_AUTHENTICATE_SERVER, &v)
}

/// Builds the ES10b PrepareDownload request from the SM-DP+ blobs (each a
/// complete DER TLV), wrapped in BF21.
pub fn build_prepare_download(
    smdp_signed2: &[u8],
    smdp_signature2: &[u8],
    hash_cc: Option<&[u8]>,
    smdp_certificate: &[u8],
) -> Vec<u8> {
    let mut v = Vec::new();
    v.extend_from_slice(smdp_signed2);
    v.extend_from_slice(smdp_signature2);
    if let Some(cc) = hash_cc {
        v.extend(asn1::tlv(TAG_HASH_CC, cc));
    }
    v.extend_from_slice(smdp_certificate);
    asn1::tlv(TAG_PREPARE_DOWNLOAD, &v)
}

/// Segments a Bound Profile Package (BF36) into the ordered list of TLVs the LPA
/// must transmit to the eUICC via STORE DATA, mirroring lpac
/// (`euicc/es10b.c:es10b_load_bound_profile_package_r`):
/// `[BF36hdr+BF23 verbatim][A0 whole][A1 header + 88 elements...][A2 whole if
/// present][A3 header + 86 elements...]`.
///
/// The first segment is the BPP bytes from the outer BF36 tag through the end
/// of BF23, verbatim: the outer header keeps its FULL body length and the
/// eUICC reads the total BPP length from it — rewriting the length breaks the
/// install with SW 6985. The remaining wrappers are sent whole (tag + length +
/// value); the `88…`/`86…` element sequences are sent header-first (tag +
/// length, without the value) then element by element, because the eUICC uses
/// the header as a length prefix for the stream that follows.
pub fn segment_bpp(bpp: &[u8]) -> Result<Vec<Vec<u8>>, String> {
    let body = asn1::find(bpp, TAG_BPP).ok_or("BoundProfilePackage: missing BF36")?;
    let mut segments = Vec::new();

    // First segment: the BPP bytes from the outer BF36 tag through the end of
    // BF23, verbatim — including the outer header with its FULL body length
    // (lpac sends `reqbuf_len = tmpnode.self.ptr -
    // n_BoundProfilePackage.self.ptr + tmpnode.self.length`, i.e. the
    // original header untouched). The eUICC reads the total BPP length from
    // it; rewriting the length breaks the install with SW 6985.
    let isc_value = asn1::find(body, TAG_INITIALISE_SECURE_CHANNEL)
        .ok_or("BoundProfilePackage: missing BF23")?;
    let isc_start = find_child_offset(body, TAG_INITIALISE_SECURE_CHANNEL)
        .ok_or("BoundProfilePackage: missing BF23")?;
    let isc_tlv = asn1::tlv(TAG_INITIALISE_SECURE_CHANNEL, isc_value);
    let prefix_len = bpp.len() - body.len();
    segments.push(bpp[..prefix_len + isc_start + isc_tlv.len()].to_vec());

    // firstSequenceOf87 (A0): sent whole.
    let seq87 = asn1::find(body, TAG_BPP_SEQ_87).ok_or("BoundProfilePackage: missing A0")?;
    segments.push(asn1::tlv(TAG_BPP_SEQ_87, seq87));

    // sequenceOf88 (A1): header first, then each 88 element.
    let seq88 = asn1::find(body, TAG_BPP_SEQ_88).ok_or("BoundProfilePackage: missing A1")?;
    segments.push(asn1::header(TAG_BPP_SEQ_88, seq88.len()));
    for element in asn1::children(seq88).ok_or("BPP: malformed A1 sequence")? {
        segments.push(asn1::tlv(element.tag, element.value));
    }

    // secondSequenceOf87 (A2): optional, sent whole when present.
    if let Some(seq87b) = asn1::find(body, TAG_BPP_SEQ_87B) {
        segments.push(asn1::tlv(TAG_BPP_SEQ_87B, seq87b));
    }

    // sequenceOf86 (A3): header first, then each 86 element.
    let seq86 = asn1::find(body, TAG_BPP_SEQ_86).ok_or("BoundProfilePackage: missing A3")?;
    segments.push(asn1::header(TAG_BPP_SEQ_86, seq86.len()));
    for element in asn1::children(seq86).ok_or("BPP: malformed A3 sequence")? {
        segments.push(asn1::tlv(element.tag, element.value));
    }

    Ok(segments)
}

/// Byte offset of the first child with `tag` inside a constructed value body.
fn find_child_offset(body: &[u8], tag: u32) -> Option<usize> {
    let mut offset = 0;
    let mut rest = body;
    while !rest.is_empty() {
        let (tlv, next) = asn1::parse(rest)?;
        if tlv.tag == tag {
            return Some(offset);
        }
        let consumed = rest.len() - next.len();
        offset += consumed;
        rest = next;
    }
    None
}

/// Parses a ProfileInstallationResult (BF37) into an [`InstallResult`].
///
/// Matches fields by tag — `bppCommandId 0x80`, `errorReason 0x81` — like lpac's
/// `es10b_load_bound_profile_package_tx`, because extra result-data fields shift
/// any positional read. Also extracts the NotificationMetadata `seqNumber` so the
/// caller can retrieve/remove the notification by sequence.
pub fn parse_install_result(response: &[u8]) -> Result<InstallResult, String> {
    let body = asn1::find(response, TAG_PROFILE_INSTALL_RESULT)
        .ok_or("ProfileInstallationResult: missing BF37")?;
    let data = asn1::find(body, TAG_PIR_DATA).ok_or("ProfileInstallationResult: missing BF27")?;
    let final_result = asn1::find(data, TAG_FINAL_RESULT)
        .ok_or("ProfileInstallationResult: missing finalResult")?;

    let seq_number = asn1::find(data, TAG_NOTIFICATION_METADATA)
        .and_then(|m| asn1::find(m, TAG_SEQ_NUMBER))
        .map(parse_int)
        .unwrap_or(-1);

    // Installed profile ICCID (BCD in NotificationMetadata): needed to address
    // the follow-up SetNickname, since the BPP result carries no ISD-P AID.
    let installed_iccid = asn1::find(data, TAG_NOTIFICATION_METADATA)
        .and_then(|m| asn1::find(m, TAG_ICCID))
        .map(hex)
        .unwrap_or_default();

    let base = InstallResult {
        success: false,
        message: String::new(),
        seq_number,
        installed_iccid,
        bpp_command_id: None,
        error_reason: None,
        notification: response.to_vec(),
    };

    if asn1::find(final_result, TAG_SUCCESS_RESULT).is_some() {
        return Ok(InstallResult {
            success: true,
            message: "Profile installed".to_string(),
            ..base
        });
    }
    if let Some(err) = asn1::find(final_result, TAG_ERROR_RESULT) {
        // errorResult ::= SEQUENCE { bppCommandId INTEGER, errorReason ENUMERATED, ... }
        let kids = asn1::children(err).unwrap_or_default();
        let find_int = |tag: u32| {
            kids.iter()
                .find(|k| k.tag == tag)
                .map(|k| parse_int(k.value))
        };
        let cmd = find_int(TAG_BPP_COMMAND_ID);
        let reason = find_int(TAG_ERROR_REASON);
        let detail = match (cmd, reason) {
            (Some(c), Some(r)) => format!("{} ({})", bpp_command_name(c), error_reason_name(r)),
            (None, Some(r)) => error_reason_name(r).to_string(),
            (Some(c), None) => bpp_command_name(c).to_string(),
            (None, None) => "unknown".to_string(),
        };
        return Ok(InstallResult {
            message: format!("Install failed ({detail})"),
            bpp_command_id: cmd,
            error_reason: reason,
            ..base
        });
    }
    Ok(InstallResult {
        message: "Install failed (unknown result)".to_string(),
        ..base
    })
}

// ---------------------------------------------------------------------------
// Metadata, transaction extraction, retrieve-notifications, cancel-session
// ---------------------------------------------------------------------------

/// Parsed carrier preview from the `profileMetadata` (StoreMetadata BF25) the
/// SM-DP+ returns inside AuthenticateClient, mirroring lpac's
/// `es8p_metadata_parse` display subset.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ProfileMetadata {
    /// BCD-decoded ICCID digits.
    pub iccid_display: String,
    /// Carrier name (`serviceProviderName`, tag `0x91`).
    pub service_provider: String,
    /// Profile display name (tag `0x92`).
    pub profile_name: String,
    /// "test" / "provisioning" / "operational" / "unknown".
    pub class: String,
}

/// Parses the `profileMetadata` StoreMetadata (BF25) blob into its display
/// subset. Unknown tags are skipped, matching lpac.
pub fn parse_profile_metadata(metadata: &[u8]) -> Result<ProfileMetadata, String> {
    let body = asn1::find(metadata, TAG_PROFILE_METADATA)
        .ok_or("ProfileMetadata: missing BF25")?;
    let kids = asn1::children(body).ok_or("ProfileMetadata: malformed")?;
    let mut out = ProfileMetadata::default();
    for k in &kids {
        match k.tag {
            TAG_ICCID => out.iccid_display = decode_iccid(k.value),
            TAG_METADATA_SPN => out.service_provider = utf8(k.value),
            TAG_METADATA_NAME => out.profile_name = utf8(k.value),
            TAG_METADATA_CLASS => {
                out.class = match first_byte(k.value) {
                    Some(0) => "test",
                    Some(1) => "provisioning",
                    Some(2) => "operational",
                    _ => "unknown",
                }
                .into();
            }
            _ => {}
        }
    }
    Ok(out)
}

/// Extracts the binary `transactionId` (tag `0x80` inside the signed SEQUENCE)
/// from `serverSigned1` (AuthenticateServer path) or `smdpSigned2`
/// (PrepareDownload path). Needed for `hashCc` and CancelSession; lpac keeps
/// the same bytes (`es10b.c` unpacks `0x80` out of the `0x30` SEQUENCE).
pub fn extract_transaction_id(signed: &[u8]) -> Result<Vec<u8>, String> {
    let seq = asn1::find(signed, TAG_SIGNED_SEQUENCE).ok_or("signed blob: missing SEQUENCE")?;
    asn1::find(seq, TAG_TX_ID)
        .map(<[u8]>::to_vec)
        .ok_or_else(|| "signed blob: missing transactionId (80)".to_string())
}

/// Reads the `ccRequiredFlag` (tag `0x01` inside `smdpSigned2`). Any non-zero
/// first byte means the profile needs a confirmation code, like lpac's
/// `convert_bin2long` truthiness check.
pub fn cc_required(smdp_signed2: &[u8]) -> bool {
    asn1::find(smdp_signed2, TAG_SIGNED_SEQUENCE)
        .and_then(|seq| asn1::find(seq, TAG_CC_FLAG))
        .is_some_and(|v| v.first().copied().unwrap_or(0) != 0)
}

/// Builds the ES10b RetrieveNotificationsList request (BF2B) for one sequence
/// number, mirroring lpac's `es10b_retrieve_notifications_list`.
pub fn build_retrieve_notification(seq: u32) -> Vec<u8> {
    let seq_tlv = asn1::tlv(TAG_SEQ_NUMBER, &encode_int_minimal(seq));
    let criteria = asn1::tlv(TAG_SEARCH_CRITERIA, &seq_tlv);
    asn1::tlv(TAG_RETRIEVE_NOTIFICATIONS, &criteria)
}

/// Parses a RetrieveNotificationsList response into the server address (tag
/// `0x0C` inside NotificationMetadata) and the raw pending-notification TLV
/// (BF37 or `0x30` otherSignedNotification, sent whole).
pub fn parse_retrieved_notification(response: &[u8]) -> Result<(String, Vec<u8>), String> {
    let body = asn1::find(response, TAG_RETRIEVE_NOTIFICATIONS)
        .ok_or("RetrieveNotifications: missing BF2B")?;
    let list = asn1::find(body, TAG_NOTIFICATION_LIST)
        .ok_or("RetrieveNotifications: error result (no A0)")?;
    let notif = asn1::children(list)
        .ok_or("RetrieveNotifications: malformed list")?
        .into_iter()
        .find(|k| k.tag == TAG_PROFILE_INSTALL_RESULT || k.tag == TAG_SIGNED_SEQUENCE)
        .ok_or("RetrieveNotifications: no pending notification")?;
    let notif_raw = asn1::wrap(notif.tag, notif.value);
    let meta_body = if notif.tag == TAG_PROFILE_INSTALL_RESULT {
        let data = asn1::find(notif.value, TAG_PIR_DATA)
            .ok_or("RetrieveNotifications: missing BF27")?;
        asn1::find(data, TAG_NOTIFICATION_METADATA)
            .ok_or("RetrieveNotifications: missing BF2F")?
            .to_vec()
    } else {
        asn1::find(notif.value, TAG_NOTIFICATION_METADATA)
            .ok_or("RetrieveNotifications: missing BF2F")?
            .to_vec()
    };
    let address = asn1::find(&meta_body, TAG_NOTIFICATION_ADDRESS)
        .map(utf8)
        .unwrap_or_default();
    Ok((address, notif_raw))
}

/// Cancel-session reasons, matching lpac's `es10b_cancel_session_reason`
/// (`euicc/es10b.h`). The LPA reports the end-user-visible cause.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum CancelReason {
    #[default]
    EndUserRejection = 0,
    Postponed = 1,
    Timeout = 2,
    PprNotAllowed = 3,
    MetadataMismatch = 4,
    LoadBppExecutionError = 5,
}

/// Builds the ES10b CancelSession request (BF41): the binary transaction id
/// plus a reason, mirroring lpac's `es10b_cancel_session_r`.
pub fn build_cancel_session(transaction_id: &[u8], reason: CancelReason) -> Vec<u8> {
    let mut v = asn1::tlv(TAG_TX_ID, transaction_id);
    v.extend(asn1::tlv(TAG_CANCEL_REASON, &[reason as u8]));
    asn1::tlv(TAG_CANCEL_SESSION, &v)
}

include!("es10b_part1.rs");

#[cfg(test)]
#[path = "es10b_tests.rs"]
mod es10b_tests;
