//! ES10 local ISD-R command builders and response parsers (SGP.22).
//!
//! The ES10c local ops (EID, info, profiles, enable/disable/delete/nickname)
//! plus the ES10b notification list/remove helpers. The ES10b download flow
//! (challenge → install → metadata → cancel) lives in [`crate::es10b`].
//!
//! Pure protocol logic: each function either builds a request TLV (to be wrapped
//! in STORE DATA APDUs and transmitted by the transport layer) or parses a
//! response TLV. No JNI or transport here, so it is fully host-testable.
//!
//! Reimplemented for this repo, following the open-source OpenEUICC / lpac
//! (GPL-3.0-only).

use crate::asn1;

// --- Tags (SGP.22) ---
const TAG_GET_EUICC_DATA: u32 = 0xBF3E; // ES10c GetEuiccData request/response
const TAG_TAG_LIST: u32 = 0x5C; // [APPLICATION 28] tag list
const TAG_EID: u32 = 0x5A; // [APPLICATION 26] EID value
const TAG_EUICC_INFO1: u32 = 0xBF20; // ES10c GetEUICCInfo1 request/response
const TAG_SVN: u32 = 0x82; // [2] version (3 octets: major.minor.rev)
const TAG_CI_PKID_VERIFICATION: u32 = 0xA9; // [9] SEQUENCE OF SubjectKeyIdentifier
const TAG_CI_PKID_SIGNING: u32 = 0xAA; // [10] SEQUENCE OF SubjectKeyIdentifier

const TAG_PROFILE_INFO_LIST: u32 = 0xBF2D; // ES10c GetProfilesInfo request/response
const TAG_PROFILE_LIST_OK: u32 = 0xA0; // [0] SEQUENCE OF ProfileInfo
const TAG_PROFILE_INFO: u32 = 0xE3; // [PRIVATE 3] ProfileInfo
pub(crate) const TAG_ICCID: u32 = 0x5A; // [APPLICATION 26] ICCID (shared with es10b metadata)
const TAG_ISDP_AID: u32 = 0x4F; // [APPLICATION 15] ISD-P AID
const TAG_PROFILE_STATE: u32 = 0x9F70; // [112] ProfileState
const TAG_NICKNAME: u32 = 0x90; // [16] profileNickname UTF8String
const TAG_SPN: u32 = 0x91; // [17] serviceProviderName UTF8String
const TAG_PROFILE_NAME: u32 = 0x92; // [18] profileName UTF8String
const TAG_PROFILE_CLASS: u32 = 0x95; // [21] ProfileClass

const TAG_ENABLE: u32 = 0xBF31; // ES10c EnableProfile
const TAG_DISABLE: u32 = 0xBF32; // ES10c DisableProfile
const TAG_DELETE: u32 = 0xBF33; // ES10c DeleteProfile
const TAG_SET_NICKNAME: u32 = 0xBF29; // ES10c SetNickname
const TAG_REFRESH_FLAG: u32 = 0x81; // [1] refreshFlag BOOLEAN
const TAG_RESULT: u32 = 0x80; // [0] result INTEGER (enable/disable/delete/setNickname)
/// [0]-constructed profile-identifier choice wrapping ICCID (+refresh) in
/// enable/disable/delete requests.
const TAG_PROFILE_CHOICE: u32 = 0xA0;

// Notification list/remove tags, shared with the es10b retrieve path.
const TAG_LIST_NOTIFICATION: u32 = 0xBF28; // ES10b ListNotification
pub(crate) const TAG_NOTIFICATION_LIST: u32 = 0xA0; // [0] SEQUENCE OF NotificationMetadata
pub(crate) const TAG_NOTIFICATION_METADATA: u32 = 0xBF2F; // [47] NotificationMetadata
pub(crate) const TAG_SEQ_NUMBER: u32 = 0x80; // [0] seqNumber INTEGER
const TAG_NOTIFICATION_EVENT: u32 = 0x81; // [1] profileManagementOperation BIT STRING
// notificationAddress UTF8String. SGP.22 tags it 0x0C (like lpac parses); it is
// NOT context-[2] despite sitting in second position of NotificationMetadata.
pub(crate) const TAG_NOTIFICATION_ADDRESS: u32 = 0x0C;
const TAG_REMOVE_NOTIFICATION: u32 = 0xBF30; // ES10b RemoveNotificationFromList

/// ASN.1 scalar helpers shared with [`crate::es10b`].
pub(crate) fn first_byte(v: &[u8]) -> Option<u8> {
    v.first().copied()
}

pub(crate) fn utf8(v: &[u8]) -> String {
    String::from_utf8_lossy(v).into_owned()
}

/// Parses a (short) two's-complement BER INTEGER into i64.
pub(crate) fn parse_int(v: &[u8]) -> i64 {
    if v.is_empty() {
        return 0;
    }
    let mut acc: i64 = if v[0] & 0x80 != 0 { -1 } else { 0 };
    for &b in v {
        acc = (acc << 8) | b as i64;
    }
    acc
}

/// Minimal unsigned big-endian INTEGER encoding (always at least one byte, with
/// a leading zero when the top bit would otherwise make it negative).
pub(crate) fn encode_int_minimal(mut v: u32) -> Vec<u8> {
    if v == 0 {
        return vec![0];
    }
    let mut bytes = Vec::new();
    while v > 0 {
        bytes.insert(0, (v & 0xFF) as u8);
        v >>= 8;
    }
    if bytes[0] & 0x80 != 0 {
        bytes.insert(0, 0x00);
    }
    bytes
}

/// Decodes an SGP.22 ICCID (BCD, nibble-swapped, F-padded) into decimal digits.
pub(crate) fn decode_iccid(raw: &[u8]) -> String {
    let mut s = String::with_capacity(raw.len() * 2);
    for &b in raw {
        let lo = b & 0x0F;
        let hi = b >> 4;
        for nib in [lo, hi] {
            if nib == 0x0F {
                continue;
            }
            if nib < 10 {
                s.push((b'0' + nib) as char);
            }
        }
    }
    s
}

/// Parsed subset of EUICCInfo1 useful for display.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EuiccInfo1 {
    /// SGP.22 spec version, e.g. "2.2.0".
    pub svn: String,
    /// GSMA CI public-key identifiers the eUICC accepts for verification.
    pub ci_pkid_verification: Vec<String>,
    /// GSMA CI public-key identifiers the eUICC can sign under.
    pub ci_pkid_signing: Vec<String>,
}

/// One installed profile, as reported by GetProfilesInfo.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ProfileInfo {
    /// Raw ICCID octets as hex (nibble-swapped form); used as the command key.
    pub iccid: String,
    /// Human-readable ICCID digits (nibble-swapped and trimmed).
    pub iccid_display: String,
    pub isdp_aid: String,
    /// "enabled", "disabled", or "unknown".
    pub state: String,
    /// "operational", "test", "provisioning", or "unknown".
    pub class: String,
    pub nickname: String,
    pub service_provider: String,
    pub name: String,
}

/// One pending notification, as reported by ListNotification.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct NotificationInfo {
    pub seq_number: i64,
    /// "install", "enable", "disable", "delete", or "unknown".
    pub operation: String,
    pub address: String,
    pub iccid_display: String,
}

// ---------------------------------------------------------------------------
// GetEID
// ---------------------------------------------------------------------------

/// Builds the ES10c GetEID request (GetEuiccData with a tag list of `{EID}`).
pub fn build_get_eid() -> Vec<u8> {
    let tag_list = asn1::tlv(TAG_TAG_LIST, &[TAG_EID as u8]);
    asn1::tlv(TAG_GET_EUICC_DATA, &tag_list)
}

/// Parses a GetEuiccData response and returns the EID as 32 hex digits.
pub fn parse_eid(response: &[u8]) -> Result<String, String> {
    let body = asn1::find(response, TAG_GET_EUICC_DATA).ok_or("GetEuiccData: missing BF3E")?;
    let eid = asn1::find(body, TAG_EID).ok_or("GetEuiccData: missing EID (5A)")?;
    if eid.len() != 16 {
        return Err(format!("EID length {} != 16", eid.len()));
    }
    Ok(hex(eid))
}

// ---------------------------------------------------------------------------
// GetEUICCInfo1
// ---------------------------------------------------------------------------

/// Builds the ES10c GetEUICCInfo1 request (empty BF20).
pub fn build_get_euicc_info1() -> Vec<u8> {
    asn1::tlv(TAG_EUICC_INFO1, &[])
}

/// Parses a GetEUICCInfo1 response into the displayable [`EuiccInfo1`] subset.
pub fn parse_euicc_info1(response: &[u8]) -> Result<EuiccInfo1, String> {
    let body = asn1::find(response, TAG_EUICC_INFO1).ok_or("EUICCInfo1: missing BF20")?;

    let svn = match asn1::find(body, TAG_SVN) {
        Some(v) if v.len() == 3 => format!("{}.{}.{}", v[0], v[1], v[2]),
        _ => String::new(),
    };

    let list = |tag: u32| -> Vec<String> {
        asn1::find(body, tag)
            .and_then(asn1::children)
            .map(|kids| kids.iter().map(|k| hex(k.value)).collect())
            .unwrap_or_default()
    };

    Ok(EuiccInfo1 {
        svn,
        ci_pkid_verification: list(TAG_CI_PKID_VERIFICATION),
        ci_pkid_signing: list(TAG_CI_PKID_SIGNING),
    })
}

// ---------------------------------------------------------------------------
// GetProfilesInfo
// ---------------------------------------------------------------------------

/// Builds the ES10c GetProfilesInfo request with a tag list of the fields we
/// display.
pub fn build_get_profiles() -> Vec<u8> {
    // Two-byte tag 9F70 is included as its two octets; the rest are one byte.
    let tags: &[u8] = &[
        TAG_ICCID as u8,
        TAG_ISDP_AID as u8,
        0x9F,
        0x70,
        TAG_NICKNAME as u8,
        TAG_SPN as u8,
        TAG_PROFILE_NAME as u8,
        TAG_PROFILE_CLASS as u8,
    ];
    let tag_list = asn1::tlv(TAG_TAG_LIST, tags);
    asn1::tlv(TAG_PROFILE_INFO_LIST, &tag_list)
}

/// Parses a GetProfilesInfo response into the list of installed profiles.
pub fn parse_profiles(response: &[u8]) -> Result<Vec<ProfileInfo>, String> {
    let body = asn1::find(response, TAG_PROFILE_INFO_LIST).ok_or("ProfileInfoList: missing BF2D")?;
    // profileInfoListOk [0]; absent means an error CHOICE was returned.
    let ok = asn1::find(body, TAG_PROFILE_LIST_OK)
        .ok_or("ProfileInfoList: error result (no A0)")?;

    let mut profiles = Vec::new();
    for child in asn1::children(ok).ok_or("ProfileInfoList: malformed list")? {
        if child.tag != TAG_PROFILE_INFO {
            continue;
        }
        let p = child.value;
        let raw_iccid = asn1::find(p, TAG_ICCID).unwrap_or(&[]);
        profiles.push(ProfileInfo {
            iccid: hex(raw_iccid),
            iccid_display: decode_iccid(raw_iccid),
            isdp_aid: asn1::find(p, TAG_ISDP_AID).map(hex).unwrap_or_default(),
            state: match asn1::find(p, TAG_PROFILE_STATE).and_then(first_byte) {
                Some(0) => "disabled".into(),
                Some(1) => "enabled".into(),
                _ => "unknown".into(),
            },
            class: match asn1::find(p, TAG_PROFILE_CLASS).and_then(first_byte) {
                Some(0) => "test".into(),
                Some(1) => "provisioning".into(),
                Some(2) => "operational".into(),
                _ => "unknown".into(),
            },
            nickname: asn1::find(p, TAG_NICKNAME).map(utf8).unwrap_or_default(),
            service_provider: asn1::find(p, TAG_SPN).map(utf8).unwrap_or_default(),
            name: asn1::find(p, TAG_PROFILE_NAME).map(utf8).unwrap_or_default(),
        });
    }
    Ok(profiles)
}

// ---------------------------------------------------------------------------
// Enable / Disable / Delete / SetNickname
// ---------------------------------------------------------------------------

/// Builds an EnableProfile request keyed by ICCID.
///
/// Per SGP.22 (and lpac's `es10c_enable_disable_delete_profile`), the
/// ICCID + refresh flag ride inside the `[0]`-constructed `A0` choice:
/// `BF31 { A0 { 5A iccid, 81 FF } }`. A bare `BF31 { 5A, 81 }` is rejected
/// by the eUICC (surfaced as result code 127).
pub fn build_enable(iccid: &[u8], refresh: bool) -> Vec<u8> {
    let mut v = asn1::tlv(TAG_ICCID, iccid);
    v.extend(asn1::tlv(TAG_REFRESH_FLAG, &[if refresh { 0xFF } else { 0x00 }]));
    asn1::tlv(TAG_ENABLE, &asn1::tlv(TAG_PROFILE_CHOICE, &v))
}

/// Builds a DisableProfile request keyed by ICCID (same `A0` choice shape).
pub fn build_disable(iccid: &[u8], refresh: bool) -> Vec<u8> {
    let mut v = asn1::tlv(TAG_ICCID, iccid);
    v.extend(asn1::tlv(TAG_REFRESH_FLAG, &[if refresh { 0xFF } else { 0x00 }]));
    asn1::tlv(TAG_DISABLE, &asn1::tlv(TAG_PROFILE_CHOICE, &v))
}

/// Builds a DeleteProfile request keyed by ICCID (same `A0` choice shape).
pub fn build_delete(iccid: &[u8]) -> Vec<u8> {
    let inner = asn1::tlv(TAG_ICCID, iccid);
    asn1::tlv(TAG_DELETE, &asn1::tlv(TAG_PROFILE_CHOICE, &inner))
}

/// Builds a SetNickname request for a profile keyed by ICCID.
pub fn build_set_nickname(iccid: &[u8], nickname: &str) -> Vec<u8> {
    let mut v = asn1::tlv(TAG_ICCID, iccid);
    v.extend(asn1::tlv(TAG_NICKNAME, nickname.as_bytes()));
    asn1::tlv(TAG_SET_NICKNAME, &v)
}

/// Parses the INTEGER result of Enable/Disable/Delete/SetNickname. 0 = success.
pub fn parse_result(response: &[u8], tag: u32) -> Result<i64, String> {
    let body = asn1::find(response, tag).ok_or("result: missing response TLV")?;
    let code = asn1::find(body, TAG_RESULT).ok_or("result: missing code (80)")?;
    Ok(parse_int(code))
}

/// Result tag for an EnableProfile response.
pub const RESULT_TAG_ENABLE: u32 = TAG_ENABLE;
pub const RESULT_TAG_DISABLE: u32 = TAG_DISABLE;
pub const RESULT_TAG_DELETE: u32 = TAG_DELETE;
pub const RESULT_TAG_SET_NICKNAME: u32 = TAG_SET_NICKNAME;

// ---------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------

/// Builds the ES10b ListNotification request (all events).
pub fn build_list_notifications() -> Vec<u8> {
    asn1::tlv(TAG_LIST_NOTIFICATION, &[])
}

/// Parses a ListNotification response into pending notifications.
pub fn parse_notifications(response: &[u8]) -> Result<Vec<NotificationInfo>, String> {
    let body = asn1::find(response, TAG_LIST_NOTIFICATION).ok_or("ListNotification: missing BF28")?;
    let list = asn1::find(body, TAG_NOTIFICATION_LIST)
        .ok_or("ListNotification: error result (no A0)")?;

    let mut out = Vec::new();
    for child in asn1::children(list).ok_or("ListNotification: malformed list")? {
        if child.tag != TAG_NOTIFICATION_METADATA {
            continue;
        }
        let m = child.value;
        out.push(NotificationInfo {
            seq_number: asn1::find(m, TAG_SEQ_NUMBER).map(parse_int).unwrap_or(-1),
            operation: asn1::find(m, TAG_NOTIFICATION_EVENT)
                .map(decode_event)
                .unwrap_or_else(|| "unknown".into()),
            address: asn1::find(m, TAG_NOTIFICATION_ADDRESS).map(utf8).unwrap_or_default(),
            iccid_display: asn1::find(m, TAG_ICCID).map(decode_iccid).unwrap_or_default(),
        });
    }
    Ok(out)
}

/// Builds the ES10b RemoveNotificationFromList request for a sequence number.
pub fn build_remove_notification(seq: u32) -> Vec<u8> {
    let inner = asn1::tlv(TAG_SEQ_NUMBER, &encode_int_minimal(seq));
    asn1::tlv(TAG_REMOVE_NOTIFICATION, &inner)
}

/// Parses the RemoveNotificationFromList result. 0 = success.
pub fn parse_remove_result(response: &[u8]) -> Result<i64, String> {
    let body = asn1::find(response, TAG_REMOVE_NOTIFICATION)
        .ok_or("RemoveNotification: missing BF30")?;
    let code = asn1::find(body, TAG_SEQ_NUMBER).ok_or("RemoveNotification: missing code")?;
    Ok(parse_int(code))
}


include!("es10_part1.rs");

#[cfg(test)]
#[path = "es10_tests.rs"]
mod es10_tests;
