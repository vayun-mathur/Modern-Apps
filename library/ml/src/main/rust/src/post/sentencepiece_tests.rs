//! Host tests for the SentencePiece table (`sentencepiece.rs` +
//! `sentencepiece_unigram.rs` + `sentencepiece_extra.rs`).
//!
//! Every table here is built in memory in the converter's `SPM1` layout, so
//! the parser, both encoders and the decoder are exercised end to end with
//! hand-computed expectations: BPE merge ranking, byte fallback, special
//! tokens, Viterbi segmentation and the malformed-table errors.

use super::*;
use crate::post::sentencepiece_flavours::{FAIRSEQ, GEMMA};

/// One `SPM1` blob for `pieces` as `(text, score)` in id order.
fn blob(pieces: &[(&str, i32)]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"SPM1");
    out.extend_from_slice(&(pieces.len() as u32).to_le_bytes());
    for (text, score) in pieces {
        out.extend_from_slice(&score.to_le_bytes());
        out.extend_from_slice(&(text.len() as u16).to_le_bytes());
        out.extend_from_slice(text.as_bytes());
    }
    out
}

/// The fairseq specials plus a small Latin vocabulary.
///
/// Ids: 0 `<s>`, 1 `<pad>`, 2 `</s>`, 3 `<unk>`, 4 `▁`, 5 `a`, 6 `b`,
/// 7 `▁a`, 8 `▁b`, 9 `ab`. Merge scores rise with merge priority.
fn latin() -> Vec<u8> {
    blob(&[
        ("<s>", 0),
        ("<pad>", 0),
        ("</s>", 0),
        ("<unk>", 0),
        ("\u{2581}", -40),
        ("a", -41),
        ("b", -42),
        ("\u{2581}a", -1),
        ("\u{2581}b", -2),
        ("ab", -3),
    ])
}

/// The latin table plus all 256 byte-fallback pieces (ids 10..266).
fn latin_with_bytes() -> Vec<u8> {
    let mut pieces: Vec<(String, i32)> = vec![
        ("<s>".into(), 0),
        ("<pad>".into(), 0),
        ("</s>".into(), 0),
        ("<unk>".into(), 0),
        ("\u{2581}".into(), -40),
        ("a".into(), -41),
        ("b".into(), -42),
        ("\u{2581}a".into(), -1),
        ("\u{2581}b".into(), -2),
        ("ab".into(), -3),
    ];
    for byte in 0..=255u32 {
        pieces.push((format!("<0x{byte:02X}>"), -100));
    }
    let refs: Vec<(&str, i32)> = pieces.iter().map(|(t, s)| (t.as_str(), *s)).collect();
    blob(&refs)
}

#[test]
fn a_table_parses_and_answers_lookups() {
    let bytes = latin();
    let table = Table::parse(&bytes).expect("the table parses");
    assert_eq!(table.len(), 10);
    assert!(!table.is_empty());
    assert_eq!(table.specials(), FAIRSEQ);
    assert!(!table.has_byte_fallback(), "no byte pieces in this table");
    assert_eq!(table.piece(7), Some("\u{2581}a".as_bytes()));
    assert_eq!(table.piece(10), None, "one past the end");
    assert_eq!(table.id_of("ab"), Some(9));
    assert_eq!(table.id_of("zz"), None);
}

#[test]
fn malformed_tables_are_refused_with_the_reason() {
    assert!(Table::parse(b"SPM").is_err(), "shorter than the header");
    assert!(Table::parse(b"XXXX........").is_err(), "bad magic");
    // Truncated inside the second piece's header.
    let bytes = latin();
    assert!(Table::parse(&bytes[..12]).is_err(), "ends inside a piece");
    // One trailing byte the piece count does not account for.
    let mut padded = latin();
    padded.push(0);
    assert!(Table::parse(&padded).is_err(), "trailing bytes");
    // The reserved ids must spell what the flavour expects.
    let wrong = blob(&[("<pad>", 0), ("<s>", 0), ("</s>", 0), ("<unk>", 0)]);
    assert!(Table::parse(&wrong).is_err(), "id 0 is not <s>");
}

#[test]
fn bpe_merges_the_highest_ranked_pair_first_leftmost_on_a_tie() {
    let bytes = latin();
    let table = Table::parse(&bytes).expect("the table parses");
    // "a b" prepares to "▁a▁b"; both word pieces outrank their parts.
    assert_eq!(table.encode("a b"), vec![7, 8]);
    // Whitespace runs collapse and the ends trim (fairseq tidying).
    assert_eq!(table.encode("  a   b  "), vec![7, 8]);
    assert_eq!(table.encode(""), Vec::<u32>::new());
    assert_eq!(table.encode("   "), Vec::<u32>::new());
    // Inside one word, "ab" (rank -3) merges after neither ▁ piece applies.
    assert_eq!(table.encode("ab"), vec![7, 6], "▁a then a lone b: no ▁ab piece");
}

#[test]
fn an_uncovered_character_is_unk_without_byte_fallback() {
    let bytes = latin();
    let table = Table::parse(&bytes).expect("the table parses");
    // 'z' has no piece: one unk for the whole symbol, the rest still encodes.
    assert_eq!(table.encode("az"), vec![7, FAIRSEQ.unk]);
}

#[test]
fn byte_fallback_spells_every_byte_and_decode_fuses_them_back() {
    let bytes = latin_with_bytes();
    let table = Table::parse(&bytes).expect("the table parses");
    assert!(table.has_byte_fallback());
    // 'é' is U+00E9, UTF-8 C3 A9: two byte pieces, ids 10 + byte.
    let ids = table.encode("a\u{e9}");
    assert_eq!(ids, vec![7, 10 + 0xC3, 10 + 0xA9]);
    // Decoding fuses the byte run into the character again, and the leading
    // metaspace becomes a space that the trim then removes.
    assert_eq!(table.decode(&ids), "a\u{e9}");
    assert_eq!(table.decode(&[7, 8]), "a b");
    // Ids past the table are skipped, not fatal.
    assert_eq!(table.decode(&[7, 9999, 8]), "a b");
}

#[test]
fn gemma_flavour_keeps_space_runs_significant() {
    let bytes = blob(&[
        ("<pad>", 0),
        ("<eos>", 0),
        ("<bos>", 0),
        ("<unk>", 0),
        ("\u{2581}", -40),
        ("a", -41),
        ("\u{2581}a", -1),
        ("\u{2581}\u{2581}a", -2),
    ]);
    let table = Table::parse_with(&bytes, GEMMA).expect("the table parses");
    assert_eq!(table.specials().bos, 2);
    // No tidying: two spaces stay two metaspaces and merge as one piece.
    assert_eq!(table.encode("a  a"), vec![5, 7]);
}

#[test]
fn specials_are_matched_literally_and_never_merged_into() {
    let bytes = latin();
    let table = Table::parse(&bytes).expect("the table parses");
    // "<s>" as text would BPE into pieces; as a special it is id 0.
    assert_eq!(table.encode_with_specials("<s>a", &["<s>"]), vec![0, 7]);
    // Unknown specials are skipped, and ordinary text still encodes.
    assert_eq!(table.encode_with_specials("a", &["<nope>"]), vec![7]);
    assert_eq!(table.encode_with_specials("", &["<s>"]), Vec::<u32>::new());
}

#[test]
fn the_unigram_viterbi_prefers_the_better_scoring_segmentation() {
    // Scores are log-probability-like: -1 beats -2 + -2, so the long piece wins.
    let bytes = blob(&[
        ("<s>", 0),
        ("<pad>", 0),
        ("</s>", 0),
        ("<unk>", 0),
        ("\u{2581}", -3_000_000),
        ("a", -3_000_000),
        ("b", -3_000_000),
        ("\u{2581}a", -2_000_000),
        ("ab", -1_000_000),
        ("\u{2581}ab", -1_000_000),
    ]);
    let table = Table::parse(&bytes).expect("the table parses");
    assert_eq!(table.encode_unigram("ab"), vec![9], "one long piece");
    // With the long piece dearer than its parts, Viterbi splits it.
    let bytes = blob(&[
        ("<s>", 0),
        ("<pad>", 0),
        ("</s>", 0),
        ("<unk>", 0),
        ("\u{2581}", -3_000_000),
        ("a", -1_000_000),
        ("b", -1_000_000),
        ("\u{2581}a", -1_000_000),
        ("\u{2581}ab", -9_000_000),
    ]);
    let table = Table::parse(&bytes).expect("the table parses");
    assert_eq!(table.encode_unigram("ab"), vec![7, 6]);
    assert_eq!(table.encode_unigram(""), Vec::<u32>::new());
}

#[test]
fn byte_piece_spelling_is_strict() {
    use crate::post::sentencepiece_extra::byte_piece;
    assert_eq!(byte_piece(b"<0x00>"), Some(0));
    assert_eq!(byte_piece(b"<0xFF>"), Some(255));
    assert_eq!(byte_piece(b"<0xff>"), Some(255), "lowercase hex is accepted");
    assert_eq!(byte_piece(b"<0x0G>"), None);
    assert_eq!(byte_piece(b"<0x0>"), None, "too short");
    assert_eq!(byte_piece(b"a"), None);
    assert_eq!(byte_piece(b""), None);
}
