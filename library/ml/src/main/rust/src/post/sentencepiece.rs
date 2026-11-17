//! NLLB's SentencePiece tokenizer: text to token ids and back.
//!
//! # What was inside the ncnn AAR
//!
//! There is no app-side tokenizer today — encode and decode both live in the `.so`, so a wrong
//! piece boundary could not be read, let alone tested. This is that code, in the open.
//!
//! # It is BPE, and the "scores" are merge ranks
//!
//! The model file is named `sentencepiece.bpe.model` and its `trainer_spec.model_type` is 2, BPE
//! (verified by model-eng with the existing `read_spm` parser: 256,000 pieces, scores 0..-255996).
//! Its pieces carry **negative merge ranks** rather than log probabilities, so encoding is greedy
//! pairwise merging — repeatedly join the adjacent pair whose concatenation is in the vocabulary
//! with the highest score, leftmost on a tie — and not the Viterbi a Unigram model would need.
//! Reading the scores as log probabilities and running Viterbi produces plausible pieces and the
//! wrong ones.
//!
//! # Normalisation is the caller's job
//!
//! The model's `normalizer_spec` is `nmt_nfkc` with a 237 KB `precompiled_charsmap`. Carrying
//! that in the APK is not worth it when the platform has
//! `java.text.Normalizer.normalize(text, Form.NFKC)`, so [`Table::encode`] takes text that is
//! **already NFKC** and does the rest: collapse whitespace runs, trim, prefix the metaspace and
//! substitute it for every space. Checked against the real `sentencepiece` on 20 samples across
//! Latin, Cyrillic, Arabic, Devanagari, Han, Kana, Hangul, fullwidth forms and emoji — NFKC plus
//! that whitespace handling agrees with `nmt_nfkc` on all of them.
//!
//! The table itself is `scripts/ml/nllb_tokenizer.py`'s output; see it for the format.

use std::collections::HashMap;

/// The magic the converter writes.
const MAGIC: &[u8; 4] = b"SPM1";

/// SentencePiece's word-start marker, `U+2581 LOWER ONE EIGHTH BLOCK`.
pub const METASPACE: char = '\u{2581}';

/// The ids a table reserves and how it normalises text, which differ between the models this
/// reads.
///
/// fairseq puts `<s> <pad> </s> <unk>` at 0..3; Gemma puts `<pad> <eos> <bos> <unk>` there. Both
/// are checked against the table rather than assumed, because a table loaded with the wrong
/// convention decodes every sentence off by one special and looks almost right.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Flavour {
    /// Beginning of sequence.
    pub bos: u32,
    /// Padding.
    pub pad: u32,
    /// End of sequence, which a decode loop stops on.
    pub eos: u32,
    /// The id for text no piece covers, used only when the table has no byte fallback.
    pub unk: u32,
    /// The four pieces, in id order from 0, that [`Table::parse`] asserts.
    pub names: [&'static str; 4],
    /// Whether to collapse whitespace runs, trim, and prefix a metaspace before encoding.
    ///
    /// sentencepiece's `remove_extra_whitespaces` plus `add_dummy_prefix`, which fairseq's models
    /// were trained with. **Gemma was not**: its `tokenizer.json` normaliser is a bare
    /// `Replace(" ", "\u{2581}")` with no trimming and no prefix, so leading and repeated spaces
    /// are significant to it. Collapsing them would silently retokenise indented text, code
    /// blocks and anything else where runs of spaces carry meaning - which for a chat model is
    /// most of what it is asked to write.
    pub tidy_whitespace: bool,
}


/// fairseq's specials, which the table is checked against rather than assumed to hold.
pub const BOS: u32 = 0;
/// The padding id.
pub const PAD: u32 = 1;
/// End of sentence, which the tokenizer appends and the decode loop stops on.
pub const EOS: u32 = 2;
/// The id for a character no piece covers.
pub const UNK: u32 = 3;

/// The longest piece the Viterbi encoder will attempt: longer spans never match.
///
/// SentencePiece caps pieces at 32 bytes of UTF-8 in practice; bounding the inner loop
/// keeps a pathological input linear rather than quadratic. A table holding a longer
/// piece would need this raised — and `parse` does not check, so overlong pieces would
/// silently never match. The MADLAD table's longest is under this; see
/// `tests::the_viterbi_prefers_the_longer_piece`.
pub const MAX_PIECE_LEN: usize = 64;

/// A borrowed view of the vocabulary: piece bytes, log-probabilities or merge ranks, and ids.
///
/// Which of the two the scores are is a property of how the model was trained: BPE pieces
/// carry negative merge ranks and encode by greedy pairwise merging ([`Table::encode`]),
/// Unigram pieces carry log-probabilities and encode by Viterbi ([`Table::encode_unigram`]).
/// Reading one as the other produces plausible pieces and the wrong ones — which is why
/// [`Table::parse`] records the convention from the caller-chosen [`Flavour`].
pub struct Table<'a> {
    /// One `(offset, length, score)` per id, so an id indexes directly.
    pieces: Vec<(u32, u16, i32)>,
    blob: &'a [u8],
    /// Piece bytes to `(id, score)`. Empty pieces are absent, and the lowest id wins a duplicate.
    pub(crate) by_piece: HashMap<&'a [u8], (u32, i32)>,
    /// Which ids are reserved, and how [`Table::encode`] spells an unrepresentable byte.
    pub(crate) specials: Flavour,
    /// `<0x00>`..`<0xFF>` as ids, when the table carries byte fallback.
    ///
    /// `None` for a table without it, where an uncoverable piece becomes [`Flavour::unk`] and
    /// the text is simply lost. With it, any byte round-trips - which is what lets Gemma emit
    /// text its vocabulary never saw.
    pub(crate) byte_fallback: Option<Box<[u32; 256]>>,
}

impl<'a> Table<'a> {
    /// Parse the converter's output. Borrows `bytes`, so nothing is copied.
    ///
    /// [`FAIRSEQ`] specials, for the tables that predate a second convention.
    ///
    /// [`FAIRSEQ`]: super::sentencepiece_flavours::FAIRSEQ
    pub fn parse(bytes: &'a [u8]) -> Result<Table<'a>, String> {
        Table::parse_with(bytes, super::sentencepiece_flavours::FAIRSEQ)
    }

    /// [`Table::parse`] for a table whose reserved ids are not fairseq's.
    pub fn parse_with(bytes: &'a [u8], specials: Flavour) -> Result<Table<'a>, String> {
        if bytes.len() < 8 || &bytes[0..4] != MAGIC {
            return Err("not a SPM1 tokenizer table".into());
        }
        let count = u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]) as usize;
        let mut pieces = Vec::with_capacity(count);
        let mut by_piece = HashMap::with_capacity(count);
        let mut at = 8usize;
        for id in 0..count {
            let head = bytes
                .get(at..at + 6)
                .ok_or_else(|| format!("the table ends inside piece {id}"))?;
            let score = i32::from_le_bytes([head[0], head[1], head[2], head[3]]);
            let length = u16::from_le_bytes([head[4], head[5]]);
            at += 6;
            let piece = bytes
                .get(at..at + length as usize)
                .ok_or_else(|| format!("piece {id} runs past the table"))?;
            pieces.push((at as u32, length, score));
            at += length as usize;
            // Eight of SMaLL-100's entries are empty padding, and would otherwise match every
            // lookup of the empty string.
            if !piece.is_empty() {
                by_piece.entry(piece).or_insert((id as u32, score));
            }
        }
        if at != bytes.len() {
            return Err(format!("{} bytes left after {count} pieces", bytes.len() - at));
        }
        let table = Table { pieces, blob: bytes, by_piece, specials, byte_fallback: None };
        for (id, name) in specials.names.iter().enumerate() {
            match table.piece(id as u32) {
                Some(found) if found == name.as_bytes() => {}
                other => {
                    return Err(format!(
                        "id {id} is {:?}, not {name}",
                        other.map(String::from_utf8_lossy)
                    ))
                }
            }
        }
        // Byte fallback, if the table has all 256 `<0xNN>` pieces. All or nothing: a partial set
        // would cover some bytes and silently drop the rest, which is worse than covering none.
        let mut bytes_ids = [0u32; 256];
        let mut complete = true;
        for (byte, slot) in bytes_ids.iter_mut().enumerate() {
            let name = format!("<0x{byte:02X}>");
            match table.by_piece.get(name.as_bytes()) {
                Some(&(id, _)) => *slot = id,
                None => {
                    complete = false;
                    break;
                }
            }
        }
        let byte_fallback = complete.then(|| Box::new(bytes_ids));
        Ok(Table { byte_fallback, ..table })
    }

    /// Whether this table can spell any byte, rather than losing it to `unk`.
    pub fn has_byte_fallback(&self) -> bool {
        self.byte_fallback.is_some()
    }

    /// The reserved ids this table was parsed with.
    pub fn specials(&self) -> Flavour {
        self.specials
    }

    /// Entries the table holds, which is also one past the highest id.
    pub fn len(&self) -> usize {
        self.pieces.len()
    }

    /// Whether the table is empty, which a parsed one never is.
    pub fn is_empty(&self) -> bool {
        self.pieces.is_empty()
    }

    /// One id's piece as UTF-8 bytes.
    pub fn piece(&self, id: u32) -> Option<&'a [u8]> {
        let &(offset, length, _) = self.pieces.get(id as usize)?;
        self.blob.get(offset as usize..offset as usize + length as usize)
    }

    /// Token ids for text that is **already NFKC-normalised**.
    ///
    /// Does not append [`EOS`] or prepend a language token: the decode loop owns the
    /// sequence, because SMaLL-100 puts the *target* language token on the **source** side and
    /// that is easy to get backwards.
    pub fn encode(&self, normalised: &str) -> Vec<u32> {
        let prepared = self.prepare(normalised);
        if prepared.is_empty() {
            return Vec::new();
        }
        // Symbols as byte ranges into `prepared`, so a merge is a range join rather than a
        // string concatenation. A sentence of 200 characters does 200 merges of 200 lookups.
        let mut symbols: Vec<(usize, usize)> = prepared
            .char_indices()
            .map(|(at, c)| (at, at + c.len_utf8()))
            .collect();
        loop {
            let mut best: Option<(i32, usize)> = None;
            for index in 0..symbols.len().saturating_sub(1) {
                let joined = &prepared[symbols[index].0..symbols[index + 1].1];
                let Some(&(_, score)) = self.by_piece.get(joined.as_bytes()) else {
                    continue;
                };
                // Highest score wins, and the leftmost of equal scores, which is what
                // sentencepiece does. `>` rather than `>=` keeps the leftmost.
                if best.is_none_or(|(top, _)| score > top) {
                    best = Some((score, index));
                }
            }
            let Some((_, index)) = best else { break };
            symbols[index].1 = symbols[index + 1].1;
            symbols.remove(index + 1);
        }
        let mut out = Vec::with_capacity(symbols.len());
        for &(from, to) in &symbols {
            let piece = &prepared.as_bytes()[from..to];
            match self.by_piece.get(piece) {
                Some(&(id, _)) => out.push(id),
                // No piece covers this symbol. With byte fallback it becomes one token per
                // **byte**, so the text survives a round trip; without, it is one `unk` and the
                // text is lost.
                None => match &self.byte_fallback {
                    Some(table) => out.extend(piece.iter().map(|&b| table[b as usize])),
                    None => out.push(self.specials.unk),
                },
            }
        }
        out
    }

    /// The id whose piece is exactly `piece`, if the table holds it.
    ///
    /// For looking up the markers a chat template inserts - `<bos>`, `<start_of_turn>`, the tool
    /// tags - so a caller can name them rather than hardcode ids that differ between models.
    pub fn id_of(&self, piece: &str) -> Option<u32> {
        self.by_piece.get(piece.as_bytes()).map(|&(id, _)| id)
    }

    /// [`Table::encode`], with `specials` matched literally and never merged into.
    ///
    /// # Why this is separate from `encode`
    ///
    /// HuggingFace matches its `added_tokens` against the raw text *before* the BPE loop runs, so
    /// the literal text `<bos>` becomes id 2 rather than the four or five pieces that spell it.
    /// A chat template is exactly that: markers interleaved with user text, all in one string. An
    /// encoder that merged them would feed the model a description of a turn boundary instead of
    /// a turn boundary.
    ///
    /// It is not folded into [`Table::encode`] because the set is a property of the *caller* -
    /// which markers a prompt is allowed to contain is a policy question, and a model that let a
    /// user's text spell `<start_of_turn>` would let them forge a turn.
    ///
    /// Longest match wins, so `<tool_call|>` is preferred over any shorter marker that prefixes
    /// it. Unknown entries in `specials` are skipped rather than failing the encode.
    pub fn encode_with_specials(&self, normalised: &str, specials: &[&str]) -> Vec<u32> {
        let mut markers: Vec<(&str, u32)> =
            specials.iter().filter_map(|s| self.id_of(s).map(|id| (*s, id))).collect();
        markers.sort_by_key(|(text, _)| std::cmp::Reverse(text.len()));

        let mut out = Vec::new();
        let mut rest = normalised;
        'outer: while !rest.is_empty() {
            for (marker, id) in &markers {
                if let Some(at) = rest.find(marker) {
                    if at == 0 {
                        out.push(*id);
                        rest = &rest[marker.len()..];
                        continue 'outer;
                    }
                }
            }
            // No marker starts here. Take everything up to the earliest one, or the rest.
            let next = markers
                .iter()
                .filter_map(|(marker, _)| rest.find(marker))
                .filter(|&at| at > 0)
                .min()
                .unwrap_or(rest.len());
            out.extend(self.encode(&rest[..next]));
            rest = &rest[next..];
        }
        out
    }

    /// NFKC-normalised text as sentencepiece feeds it to the merge loop.
    ///
    /// Whitespace runs collapse to one space and the ends are trimmed
    /// (`remove_extra_whitespaces`), then every space becomes the metaspace and one is prefixed
    /// (`add_dummy_prefix`) so a word at the start of a sentence tokenises like the same word in
    /// the middle of one.
    fn prepare(&self, normalised: &str) -> String {
        if !self.specials.tidy_whitespace {
            // Gemma: every space becomes the metaspace and nothing else changes. Runs of spaces
            // survive as runs of metaspaces, which is what it was trained on.
            return normalised.replace(' ', &METASPACE.to_string());
        }
        let mut out = String::with_capacity(normalised.len() + METASPACE.len_utf8());
        for word in normalised.split_whitespace() {
            out.push(METASPACE);
            out.push_str(word);
        }
        out
    }

    /// Unigram input spelling: dummy prefix + metaspace, WITHOUT whitespace tidying.
    ///
    /// T5's normaliser is the identity (plus HF's collapse of double spaces, which the caller
    /// applies): runs of spaces are significant, the ends are not trimmed, and every space —
    /// leading included — becomes the metaspace, with one prefixed (`add_dummy_prefix`) so a
    /// word at the start of a sentence tokenises like the same word mid-sentence.
    ///
    /// `pub(crate)` for the Viterbi encoder in `sentencepiece_unigram`, which owns the only
    /// other call.
    pub(crate) fn prepare_unigram(&self, text: &str) -> String {
        let mut out = String::with_capacity(text.len() + METASPACE.len_utf8());
        out.push(METASPACE);
        for c in text.chars() {
            if c == ' ' {
                out.push(METASPACE);
            } else {
                out.push(c);
            }
        }
        out
    }

    /// Text for a run of ids, with the metaspace turned back into spaces.
    ///
    /// Ids past the table are skipped rather than replacing the whole string: a decode loop that
    /// produced one bad token should lose a word, not the translation.
    ///
    /// # Byte pieces are fused before they are decoded
    ///
    /// A `<0xNN>` piece is one byte of a character, not a character. Decoding each on its own
    /// would turn every multi-byte sequence into a run of replacement characters, so consecutive
    /// byte pieces are collected and converted as one string - `ByteFallback` then `Fuse` in
    /// HuggingFace's decoder, and the same thing here.
    pub fn decode(&self, ids: &[u32]) -> String {
        let mut out: Vec<u8> = Vec::new();
        for &id in ids {
            let Some(piece) = self.piece(id) else { continue };
            match super::sentencepiece_extra::byte_piece(piece) {
                Some(byte) => out.push(byte),
                None => out.extend_from_slice(piece),
            }
        }
        String::from_utf8_lossy(&out).replace(METASPACE, " ").trim().to_string()
    }
}

#[cfg(test)]
#[path = "sentencepiece_tests.rs"]
mod sentencepiece_tests;
