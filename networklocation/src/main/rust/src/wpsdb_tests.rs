//! Bit-level tests for the WPSDB payload codec. The store round-trip tests in
//! `wpsdb_part2.rs` cover the reader end to end; these pin `extract_bits`
//! directly, including unaligned and 64-bit-wide reads that a corrupt store
//! could otherwise turn into a panic or a silent mis-decode.

use super::*;

#[test]
fn extract_bits_single_byte_fields() {
    let buf = [0b1011_0011u8];
    assert_eq!(extract_bits(&buf, 0, 1), 1);
    assert_eq!(extract_bits(&buf, 1, 1), 1);
    assert_eq!(extract_bits(&buf, 2, 1), 0);
    assert_eq!(extract_bits(&buf, 0, 4), 0b0011);
    assert_eq!(extract_bits(&buf, 4, 4), 0b1011);
    assert_eq!(extract_bits(&buf, 0, 8), 0b1011_0011);
    assert_eq!(extract_bits(&buf, 0, 0), 0);
}

#[test]
fn extract_bits_crosses_byte_boundaries_unaligned() {
    // Value 0b10101011_1100 written LSB-first starting at bit offset 3.
    let mut bits = [0u8; 4];
    let value: u64 = 0b1010_1011_1100;
    for k in 0..12u32 {
        if (value >> k) & 1 == 1 {
            let p = 3 + k as usize;
            bits[p >> 3] |= 1 << (p & 7);
        }
    }
    assert_eq!(extract_bits(&bits, 3, 12), value);
    assert_eq!(extract_bits(&bits, 3, 1), value & 1);
    assert_eq!(extract_bits(&bits, 14, 1), (value >> 11) & 1);
}

#[test]
fn extract_bits_full_width_and_known_pattern() {
    let buf = [0xFFu8, 0x00, 0xA5, 0x5A, 0x12, 0x34, 0x56, 0x78, 0x9A];
    assert_eq!(extract_bits(&buf, 0, 64), 0x7856_3412_5AA5_00FF);
    assert_eq!(extract_bits(&buf, 4, 64), 0xA785_6341_25AA_500F);
    assert_eq!(extract_bits(&buf, 0, 35) & 1, 1);
    // Every all-ones field reads back as all ones.
    assert_eq!(extract_bits(&[0xFFu8; 9], 5, 36), (1u64 << 36) - 1);
    assert_eq!(extract_bits(&[0x00u8; 9], 5, 36), 0);
}

#[test]
fn payload_field_layout_matches_record_codec() {
    // Hand-pack one kind-2 record: lat|lon|acc|alt at 35/36/16/16 bits.
    let lat_u: u64 = (37.77f64 * COORD_SCALE) as u64 + LAT_BIAS as u64;
    let lon_u: u64 = (-122.41f64 * COORD_SCALE) as u64 + LON_BIAS as u64;
    let fields = [
        (lat_u, LAT_BITS),
        (lon_u, LON_BITS),
        (250u64, ACC_BITS),
        ((8848i64 + ALT_BIAS) as u64, ALT_BITS),
    ];
    let total: u32 = fields.iter().map(|f| f.1).sum();
    assert_eq!(total, 103);
    let mut buf = vec![0u8; 13];
    let mut pos = 0u32;
    for &(v, n) in &fields {
        for k in 0..n {
            if (v >> k) & 1 == 1 {
                let p = (pos + k) as usize;
                buf[p >> 3] |= 1 << (p & 7);
            }
        }
        pos += n;
    }
    assert_eq!(extract_bits(&buf, 0, LAT_BITS), lat_u);
    assert_eq!(extract_bits(&buf, LAT_BITS, LON_BITS), lon_u);
    assert_eq!(extract_bits(&buf, LAT_BITS + LON_BITS, ACC_BITS), 250);
    assert_eq!(
        extract_bits(&buf, LAT_BITS + LON_BITS + ACC_BITS, ALT_BITS),
        (8848i64 + ALT_BIAS) as u64
    );
}
