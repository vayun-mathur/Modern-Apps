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
