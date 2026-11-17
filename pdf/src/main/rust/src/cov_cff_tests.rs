//! Coverage tests for the CFF parser's public surface with synthetic fonts.
//! Malformed input must yield empty/None, never panic.
use crate::cff::{builtin_encoding, cid_to_gid_map};

fn idx1(data: &[u8]) -> Vec<u8> {
    // INDEX with one entry, 1-byte offsets.
    let mut v = vec![0u8, 1, 1, 1, (1 + data.len()) as u8];
    v.extend_from_slice(data);
    v
}
fn empty_idx() -> Vec<u8> { vec![0u8, 0] }
fn int16_op(v: i16, op: u8) -> [u8; 4] { [28, (v >> 8) as u8, v as u8, op] }

/// Minimal name-keyed CFF: StandardEncoding (default), 1 glyph.
fn minimal_cff(top_extra: &[u8]) -> Vec<u8> {
    let mut out = vec![1u8, 0, 4, 1];
    out.extend(idx1(b"F")); // Name INDEX
    // CharStrings INDEX will go after header+name+top+string; compute its offset.
    // Top DICT: CharStrings offset (op 17) + caller extras.
    let name_len = 2 + 1 + 2 + 1; // count(2)+offsize(1)+2 offsets+data(1) = 6
    let top_dict_guess_len = 4 + top_extra.len();
    let top_idx_len = 2 + 1 + 2 + top_dict_guess_len;
    let cs_off = (4 + name_len + top_idx_len + 2) as i16;
    let mut dict = Vec::new();
    dict.extend(int16_op(cs_off, 17));
    dict.extend_from_slice(top_extra);
    out.extend(idx1(&dict)); // Top DICT INDEX
    out.extend(empty_idx()); // String INDEX
    debug_assert_eq!(out.len(), cs_off as usize);
    out.extend(idx1(&[14])); // CharStrings: one glyph (endchar)
    out
}

#[test]
fn malformed_cff_never_panics_and_yields_nothing() {
    for bytes in [&[][..], &[1], &[1, 0, 4], &[2, 0, 4, 1, 0, 0], &[9, 9, 9, 9, 9],
                  &[1, 0, 255, 1, 0, 0], &[1, 0, 2, 1], &[1, 0, 4, 9, 0xff, 0xff]] {
        assert!(builtin_encoding(bytes).is_empty(), "bytes {bytes:?}");
        assert!(cid_to_gid_map(bytes).is_none(), "bytes {bytes:?}");
    }
    // Every truncation of a valid font must also be safe.
    let valid = minimal_cff(&[]);
    for n in 0..valid.len() {
        let _ = builtin_encoding(&valid[..n]);
        let _ = cid_to_gid_map(&valid[..n]);
    }
    // Every single-byte mutation must be safe.
    for i in 0..valid.len() {
        for v in [0u8, 0xff, 0x80] {
            let mut m = valid.clone(); m[i] = v;
            let _ = builtin_encoding(&m);
            let _ = cid_to_gid_map(&m);
        }
    }
}

#[test]
fn minimal_cff_standard_encoding_maps_codes() {
    let m = builtin_encoding(&minimal_cff(&[]));
    assert_eq!(m.get(&65), Some(&'A'));
    assert_eq!(m.get(&97), Some(&'a'));
    assert_eq!(m.get(&32), Some(&' '));
    assert_eq!(m.get(&48), Some(&'0'));
    assert!(cid_to_gid_map(&minimal_cff(&[])).is_none(), "name-keyed font has no CID map");
}

#[test]
fn minimal_cff_expert_encoding() {
    // Encoding operator 16 with operand 1 (139+1=140 -> byte 140 encodes int 1? use int16).
    let mut extra = Vec::new();
    extra.extend(int16_op(1, 16));
    let m = builtin_encoding(&minimal_cff(&extra));
    assert!(!m.is_empty());
    assert_eq!(m.get(&32), Some(&' '), "ExpertEncoding code 32 is space");
}
