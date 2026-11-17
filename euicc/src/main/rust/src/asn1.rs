//! Minimal BER-TLV (SGP.22 uses DER) encode/decode.
//!
//! SGP.22 ASN.1 is small and regular: context/application tags of one or two
//! bytes, definite lengths (short and long form), constructed and primitive
//! values. This module implements just that surface — enough to build the ES10
//! request TLVs and walk the responses — without pulling a full ASN.1 stack.
//!
//! It is pure and host-testable; no JNI, no protocol logic.

/// A parsed TLV: its tag, the raw value bytes, and the number of header bytes
/// (tag + length octets) that preceded the value.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Tlv<'a> {
    pub tag: u32,
    pub value: &'a [u8],
}

/// Reads the tag at the start of `data`, returning `(tag, bytes_consumed)`.
///
/// Handles single-byte tags and the multi-byte form (low 5 bits of the first
/// byte all set), which SGP.22 uses for two-byte context tags such as `BF3E`.
fn read_tag(data: &[u8]) -> Option<(u32, usize)> {
    let first = *data.first()?;
    // Low 5 bits set => tag number continues in following bytes.
    if first & 0x1F != 0x1F {
        return Some((first as u32, 1));
    }
    let mut tag = first as u32;
    let mut i = 1;
    loop {
        let b = *data.get(i)?;
        tag = (tag << 8) | b as u32;
        i += 1;
        // High bit clear terminates the tag.
        if b & 0x80 == 0 {
            break;
        }
        // SGP.22 never exceeds two-byte tags; guard runaway input.
        if i > 4 {
            return None;
        }
    }
    Some((tag, i))
}

/// Reads a definite BER length at the start of `data`, returning
/// `(length, bytes_consumed)`.
fn read_len(data: &[u8]) -> Option<(usize, usize)> {
    let first = *data.first()?;
    if first & 0x80 == 0 {
        return Some((first as usize, 1));
    }
    let n = (first & 0x7F) as usize;
    // Indefinite (n == 0) is not valid DER; refuse it. Cap at 4 length octets.
    if n == 0 || n > 4 {
        return None;
    }
    let mut len = 0usize;
    for i in 0..n {
        len = (len << 8) | *data.get(1 + i)? as usize;
    }
    Some((len, 1 + n))
}

/// Parses the first TLV in `data`, returning it together with the remaining
/// bytes after its value.
pub fn parse(data: &[u8]) -> Option<(Tlv<'_>, &[u8])> {
    let (tag, tag_len) = read_tag(data)?;
    let (len, len_len) = read_len(&data[tag_len..])?;
    let header = tag_len + len_len;
    let end = header.checked_add(len)?;
    let value = data.get(header..end)?;
    Some((Tlv { tag, value }, &data[end..]))
}

/// Returns the value of the first child TLV with `tag`, searching only the top
/// level of `data`.
pub fn find(data: &[u8], tag: u32) -> Option<&[u8]> {
    let mut rest = data;
    while !rest.is_empty() {
        let (tlv, next) = parse(rest)?;
        if tlv.tag == tag {
            return Some(tlv.value);
        }
        rest = next;
    }
    None
}

/// Returns every top-level child TLV of `data`.
pub fn children(data: &[u8]) -> Option<Vec<Tlv<'_>>> {
    let mut out = Vec::new();
    let mut rest = data;
    while !rest.is_empty() {
        let (tlv, next) = parse(rest)?;
        out.push(tlv);
        rest = next;
    }
    Some(out)
}

/// Encodes a definite length in BER short/long form.
pub fn encode_len(len: usize) -> Vec<u8> {
    if len < 0x80 {
        return vec![len as u8];
    }
    let mut bytes = Vec::new();
    let mut v = len;
    while v > 0 {
        bytes.insert(0, (v & 0xFF) as u8);
        v >>= 8;
    }
    let mut out = vec![0x80 | bytes.len() as u8];
    out.extend_from_slice(&bytes);
    out
}

/// Encodes the big-endian bytes of `tag` (1 or 2 bytes) as a tag prefix.
fn encode_tag(tag: u32) -> Vec<u8> {
    if tag <= 0xFF {
        vec![tag as u8]
    } else {
        vec![(tag >> 8) as u8, (tag & 0xFF) as u8]
    }
}

/// Builds a TLV: `tag || len(value) || value`.
pub fn tlv(tag: u32, value: &[u8]) -> Vec<u8> {
    let mut out = encode_tag(tag);
    out.extend_from_slice(&encode_len(value.len()));
    out.extend_from_slice(value);
    out
}

/// Builds a bare TLV header: `tag || len(value_len)` with no value bytes.
///
/// Used when streaming constructed sequences (the BPP `88…`/`86…` element
/// lists) whose header the eUICC treats as a length prefix for the elements
/// that follow as separate STORE DATA payloads — mirroring lpac's split of
/// the A1/A3 header from its children.
pub fn header(tag: u32, value_len: usize) -> Vec<u8> {
    let mut out = encode_tag(tag);
    out.extend_from_slice(&encode_len(value_len));
    out
}

/// Re-wraps an already-parsed value in its tag+length header (`tag || len ||
/// value`). The encoder is canonical DER (minimal length form), so re-wrapping
/// a value taken from canonical eUICC output reproduces the original bytes —
/// what lpac's `pack_alloc` round trip also yields.
pub fn wrap(tag: u32, value: &[u8]) -> Vec<u8> {
    tlv(tag, value)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn single_byte_tag_and_len() {
        let (tlv, rest) = parse(&[0x5A, 0x02, 0xAB, 0xCD]).unwrap();
        assert_eq!(tlv.tag, 0x5A);
        assert_eq!(tlv.value, &[0xAB, 0xCD]);
        assert!(rest.is_empty());
    }

    #[test]
    fn two_byte_tag() {
        let (tlv, _) = parse(&[0xBF, 0x3E, 0x01, 0x00]).unwrap();
        assert_eq!(tlv.tag, 0xBF3E);
        assert_eq!(tlv.value, &[0x00]);
    }

    #[test]
    fn long_form_length() {
        let mut data = vec![0x04, 0x81, 0x80];
        data.extend(std::iter::repeat(0x11).take(0x80));
        let (tlv, rest) = parse(&data).unwrap();
        assert_eq!(tlv.tag, 0x04);
        assert_eq!(tlv.value.len(), 0x80);
        assert!(rest.is_empty());
    }

    #[test]
    fn find_nested() {
        // BF3E 12 5A 10 <16 bytes>
        let mut inner = vec![0x5A, 0x10];
        inner.extend(0..16u8);
        let outer = tlv(0xBF3E, &inner);
        let body = find(&outer, 0xBF3E).unwrap();
        let eid = find(body, 0x5A).unwrap();
        assert_eq!(eid.len(), 16);
    }

    #[test]
    fn roundtrip_encode() {
        assert_eq!(tlv(0x5C, &[0x5A]), vec![0x5C, 0x01, 0x5A]);
        assert_eq!(encode_len(0x80), vec![0x81, 0x80]);
        assert_eq!(encode_len(0x7F), vec![0x7F]);
    }

    #[test]
    fn encode_len_boundary_round_trip() {
        for len in [0x00usize, 0x7F, 0x80, 0xFF, 0x100, 0x1234, 0xFFFF] {
            let payload = vec![0x55u8; len];
            let encoded = tlv(0x04, &payload);
            let (parsed, rest) = parse(&encoded).unwrap();
            assert_eq!(parsed.tag, 0x04);
            assert_eq!(parsed.value, payload.as_slice());
            assert!(rest.is_empty());
        }
        assert_eq!(encode_len(0x7F), vec![0x7F]);
        assert_eq!(encode_len(0x80), vec![0x81, 0x80]);
        assert_eq!(encode_len(0x100), vec![0x82, 0x01, 0x00]);
        assert_eq!(header(0xA1, 3), vec![0xA1, 0x03]);
        assert_eq!(wrap(0x5C, &[0x5A]), tlv(0x5C, &[0x5A]));
    }

    #[test]
    fn asn1_more_malformed_edges() {
        // Runaway multi-byte tag (continuation bit never clears) is refused.
        assert!(parse(&[0x9F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00]).is_none());
        // Three-byte tag within the guard parses.
        let (tlv, _) = parse(&[0x9F, 0x81, 0x70, 0x01, 0x00]).unwrap();
        assert_eq!(tlv.tag, 0x9F8170);
        // Trailing garbage after a valid TLV makes find/children fail clean.
        assert!(find(&[0x5A, 0x01, 0x00, 0xFF], 0x04).is_none());
        assert!(children(&[0x5A, 0x01, 0x00, 0xFF]).is_none());
        // Long-form length with leading zero byte still decodes (BER).
        let (tlv, _) = parse(&[0x04, 0x82, 0x00, 0x01, 0xAA]).unwrap();
        assert_eq!(tlv.value, &[0xAA]);
    }

    #[test]
    fn rejects_malformed_tlv_vectors() {
        assert!(parse(&[]).is_none());
        assert!(parse(&[0x5A]).is_none());
        assert!(parse(&[0x5A, 0x02, 0xAB]).is_none());
        assert!(parse(&[0x04, 0x80, 0x00]).is_none());
        assert!(parse(&[0x04, 0x85, 0x01, 0x02, 0x03, 0x04, 0x05]).is_none());
        assert!(parse(&[0xBF, 0x3E, 0x81, 0x80]).is_none());
        assert!(find(&[0x5A, 0x02, 0xAB], 0x5A).is_none());
        assert!(children(&[0xFF]).is_none());
        assert!(find(&[], 0x5A).is_none());
        assert!(children(&[]).unwrap().is_empty());
    }
}
