//! The Unigram encoder for [`super::sentencepiece::Table`]: text to token ids by Viterbi.
//!
//! Split from `sentencepiece.rs` along the encoder line: that module keeps the BPE merge
//! loop, the table parser and decode; this one holds the Viterbi path T5/Unigram models
//! need. Both encode through the same [`MAX_PIECE_LEN`] bound and the same byte-fallback
//! table.

use super::sentencepiece::{Table, MAX_PIECE_LEN};

impl<'a> Table<'a> {
    /// Token ids for text that is **already normalised**, by Viterbi over log-probabilities.
    ///
    /// The Unigram encoder: every segmentation of the prepared text into table pieces scores
    /// the sum of its pieces' log-probabilities, and the best one wins. Unknown characters
    /// fall back to byte pieces (or `unk` without them) — a character with no covering piece
    /// is one byte-piece per UTF-8 byte, each scoring like any other piece.
    ///
    /// `prepare` is the same dummy-prefix + metaspace spelling [`Table::encode`] uses, but
    /// WITHOUT whitespace collapsing or trimming: Unigram was trained with runs of spaces
    /// significant (its normaliser is the identity plus HF's collapse-double-spaces, which
    /// the caller applies). Does not append [`EOS`] or prepend anything: the decode loop
    /// owns the sequence.
    ///
    /// [`EOS`]: super::sentencepiece::EOS
    pub fn encode_unigram(&self, text: &str) -> Vec<u32> {
        // Empty text encodes to no tokens: the dummy prefix `prepare_unigram`
        // adds is a word-start marker for real text, not a token of its own,
        // so it must not survive on its own (the `prepared.is_empty()` check
        // below can never fire, since the prefix alone is non-empty).
        if text.is_empty() {
            return Vec::new();
        }
        let prepared = self.prepare_unigram(text);
        if prepared.is_empty() {
            return Vec::new();
        }
        let bytes = prepared.as_bytes();
        // Best log-probability of segmenting the first `i` bytes, and the split that won it.
        let mut best = vec![f32::NEG_INFINITY; bytes.len() + 1];
        let mut split = vec![0u32; bytes.len() + 1];
        let mut piece_of = vec![0u32; bytes.len() + 1];
        best[0] = 0.0;
        for end in 1..=bytes.len() {
            // Candidate pieces ending at `end`: every start behind it that the table holds.
            // Bounded by the longest piece, so this is linear in practice, not quadratic.
            let from = end.saturating_sub(MAX_PIECE_LEN);
            for start in from..end {
                let piece = &bytes[start..end];
                let &(id, score) = match self.by_piece.get(piece) {
                    Some(found) => found,
                    None => continue,
                };
                // Scores are stored quantised (x1e6); the ordering is what Viterbi needs.
                let candidate = best[start] + score as f32;
                if candidate > best[end] {
                    best[end] = candidate;
                    split[end] = start as u32;
                    piece_of[end] = id;
                }
            }
            // No piece covers `end`: back off to byte fallback (one byte-piece per byte
            // of the char ending here), or to `unk` without it. Every byte position is
            // reachable through SOME path — single bytes always match a byte piece — so
            // the backtrack below always terminates with a finite score.
            if best[end] == f32::NEG_INFINITY && best[end - 1].is_finite() {
                let byte = bytes[end - 1];
                let (id, score) = match &self.byte_fallback {
                    Some(table) => {
                        let id = table[byte as usize];
                        let score = self
                            .by_piece
                            .get(self.piece(id).unwrap_or(&[]))
                            .map(|&(_, s)| s)
                            .unwrap_or(0);
                        (id, score)
                    }
                    // No byte fallback: one `unk` per byte, scored at 0 so it never beats a
                    // real piece but always terminates the path.
                    None => (self.specials.unk, 0),
                };
                best[end] = best[end - 1] + score as f32;
                split[end] = (end - 1) as u32;
                piece_of[end] = id;
            }
        }
        // Walk back the winning splits. Every position is reachable (single bytes always
        // match), so the score is finite and the walk terminates at 0.
        let mut ids = Vec::new();
        let mut at = bytes.len();
        while at > 0 {
            let from = split[at] as usize;
            if from >= at {
                // Unreachable (no finite path): emit nothing rather than half a segmentation.
                return Vec::new();
            }
            ids.push(piece_of[at]);
            at = from;
        }
        ids.reverse();
        ids
    }
}
