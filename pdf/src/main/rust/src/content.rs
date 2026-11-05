//! Lenient fallback tokenizer for PDF content streams (§7.2, §7.3, §8.9.7).
//!
//! `lopdf`'s content parser wraps inline-image parsing in nom's `cut(...)`
//! (parser/mod.rs:555), so a failure inside it becomes a `Failure` rather than an
//! `Error`. `many0` propagates a `Failure`, which means ONE malformed or merely
//! unsupported inline image makes the ENTIRE content stream fail to decode and the
//! page render completely blank. lopdf hard-errors on:
//!
//! * any inline image with `/F` or `/Filter` (`Error::Unimplemented`),
//! * `/CS /G` — the §8.9.7 Table 93 abbreviation for `DeviceGray`, which is absent
//!   from lopdf's accepted name list,
//! * a missing `/BPC` or `/CS`, which is every `/IM true` stencil, since a stencil
//!   mask has no colour space and an implicit depth of 1.
//!
//! So this module re-tokenizes the stream by hand, accepting everything above and
//! recovering from outright garbage instead of aborting. It is used ONLY when
//! lopdf's strict parse fails (or yields nothing), so it cannot regress a file that
//! already renders.
//!
//! # The `BI`/`ID`/`EI` hazard
//!
//! Per §8.9.7 the `ID` operator is followed by exactly ONE whitespace byte and then
//! RAW BINARY image data. Scanning forward for the next `EI` is therefore wrong: the
//! byte pair `EI` occurs in binary pixel data constantly, and accepting a false match
//! resynchronizes the tokenizer into the middle of an image, turning the rest of the
//! stream into garbage operators — strictly worse than the blank page being fixed.
//!
//! Instead the expected data length is COMPUTED from `/W`, `/H`, `/BPC` and `/CS`
//! (or taken from `/L` / `/Length`, which PDF 2.0 added for exactly this reason),
//! exactly that many bytes are skipped, and the terminating `EI` is then verified.
//! Scanning is a last resort, reached only when the length genuinely cannot be
//! computed (a filtered image with no `/L`), and even then a candidate must be
//! whitespace-delimited AND followed by something that actually looks like a content
//! stream before it is accepted.

use crate::*;
use lopdf::content::Operation;
use lopdf::StringFormat;

// --- Bounds. Nothing here may allocate based on a length read from the file. ---

/// Cap on recovered operations. Bounds worst-case memory on a hostile stream.
///
/// Tied to the interpreter's own ceiling: `interpret_content` stops reading at
/// `MAX_CONTENT_OPS`, so tokenizing beyond that allocates operations nobody will
/// ever look at — on precisely the hostile input the cap exists to contain. Keeping
/// the two equal also stops them drifting into one being dead code.
const MAX_OPERATIONS: usize = MAX_CONTENT_OPS;
/// Cap on the pending operand stack. Operators consume a handful; the rest is junk.
const MAX_OPERANDS: usize = 4096;
/// Array/dictionary nesting cap (recursion bound).
const MAX_DEPTH: u32 = 32;
/// Cap on elements in one array and entries in one dictionary.
const MAX_ARRAY_ITEMS: usize = 200_000;
const MAX_DICT_ENTRIES: usize = 4096;
/// Cap on a single string or name token.
const MAX_STRING: usize = 16 * 1024 * 1024;
const MAX_NAME: usize = 4096;
/// How far past a candidate `EI` to look for proof that operators resume there.
const EI_LOOKAHEAD: usize = 64;

/// §7.2.3 Table 1: PDF white-space characters.
#[inline]
fn is_ws(b: u8) -> bool {
    matches!(b, b'\0' | b'\t' | b'\n' | b'\x0c' | b'\r' | b' ')
}

/// §7.2.3 Table 2: PDF delimiter characters.
#[inline]
fn is_delim(b: u8) -> bool {
    matches!(b, b'(' | b')' | b'<' | b'>' | b'[' | b']' | b'{' | b'}' | b'/' | b'%')
}

/// A "regular" character: anything that is neither white space nor a delimiter.
/// Regular characters are what make up numbers, names, keywords and operators.
#[inline]
fn is_regular(b: u8) -> bool {
    !is_ws(b) && !is_delim(b)
}

/// Every content-stream operator in §A.1. Used to validate that a candidate `EI`
/// is followed by real operators rather than more pixel data.
const OPERATORS: &[&[u8]] = &[
    b"b", b"B", b"b*", b"B*", b"BDC", b"BI", b"BMC", b"BT", b"BX", b"c", b"cm", b"CS", b"cs", b"d",
    b"d0", b"d1", b"Do", b"DP", b"EI", b"EMC", b"ET", b"EX", b"f", b"F", b"f*", b"G", b"g", b"gs",
    b"h", b"i", b"ID", b"j", b"J", b"K", b"k", b"l", b"m", b"M", b"MP", b"n", b"q", b"Q", b"re",
    b"RG", b"rg", b"ri", b"s", b"S", b"SC", b"sc", b"SCN", b"scn", b"sh", b"T*", b"Tc", b"Td",
    b"TD", b"Tf", b"Tj", b"TJ", b"TL", b"Tm", b"Tr", b"Ts", b"Tw", b"Tz", b"v", b"w", b"W", b"W*",
    b"y", b"'", b"\"",
];

fn is_known_operator(tok: &[u8]) -> bool {
    OPERATORS.contains(&tok)
}

// ---------------------------------------------------------------------------
// Public entry points
// ---------------------------------------------------------------------------

/// Tokenize a content stream leniently. Never fails and never panics; returns
/// whatever could be recovered, which may be empty.
pub(crate) fn parse_operations_lenient(data: &[u8]) -> Vec<Operation> {
    Lexer { d: data, p: 0 }.run()
}

/// Whether array/dictionary nesting in `data` ever exceeds [`MAX_DEPTH`].
///
/// `lopdf::content::Content::decode` recurses per nesting level with no bound, so
/// `BT [[[[…(x)…]]]] TJ ET` overflows the stack — measured at N≈360 on an 8 MiB stack and
/// N≈48 on the 1 MiB an Android render thread gets. That is a guard-page fault, not an
/// unwind, so the `catch_unwind` at the JNI boundary cannot catch it and the whole process
/// dies. The lenient tokenizer already bounds itself at [`MAX_DEPTH`] and survives, but it
/// only runs when the strict parser *returns*.
///
/// So this is a pre-check, not a parser: it decides only whether the strict parser is safe
/// to call. It deliberately errs toward "too deep" — a false positive costs one pass of the
/// lenient tokenizer, which handles valid content identically, while a false negative is a
/// process kill. §7.3.4.2 literal strings (balanced unescaped parens, backslash escapes),
/// §7.3.4.3 hex strings and §7.2.4 comments are skipped so a `[` inside them cannot count.
fn nesting_is_too_deep(data: &[u8], max: u32) -> bool {
    let mut depth: u32 = 0;
    let mut i = 0usize;
    while i < data.len() {
        match data[i] {
            b'%' => {
                while i < data.len() && data[i] != b'\n' && data[i] != b'\r' {
                    i += 1;
                }
            }
            b'(' => {
                let mut nest = 1usize;
                i += 1;
                while i < data.len() && nest > 0 {
                    match data[i] {
                        b'\\' => i += 1,
                        b'(' => nest += 1,
                        b')' => nest -= 1,
                        _ => {}
                    }
                    i += 1;
                }
            }
            b'<' if data.get(i + 1) == Some(&b'<') => {
                depth += 1;
                if depth > max {
                    return true;
                }
                i += 2;
            }
            b'<' => {
                i += 1;
                while i < data.len() && data[i] != b'>' {
                    i += 1;
                }
                i += 1;
            }
            b'>' if data.get(i + 1) == Some(&b'>') => {
                depth = depth.saturating_sub(1);
                i += 2;
            }
            b'[' => {
                depth += 1;
                if depth > max {
                    return true;
                }
                i += 1;
            }
            b']' => {
                depth = depth.saturating_sub(1);
                i += 1;
            }
            _ => i += 1,
        }
    }
    false
}

/// Strict parse first, lenient tokenizer only if that fails, yields nothing, or is
/// unsafe to attempt at all.
///
/// A strict parse that kept a `BI` without its image stream also counts as a
/// failure: lopdf 0.45's `inline_image_impl` skips any inline image it cannot
/// fully decode — an abbreviated `/CS /G`, any `/F` filter, a missing `/BPC` —
/// and still reports success with a bare operand-less `BI`. The interpreter
/// renders a `BI` only from an attached stream operand, so that "success" is a
/// silently dropped image, while the lenient tokenizer decodes all of those
/// shapes. Falling back keeps the image; both callers (`page_operations` and
/// `operations_from_bytes`) already treat `None` as "run lenient".
pub(crate) fn strict_operations(bytes: &[u8]) -> Option<Vec<Operation>> {
    if nesting_is_too_deep(bytes, MAX_DEPTH) {
        return None;
    }
    match Content::decode(bytes) {
        Ok(content) if !content.operations.is_empty() => {
            let ops = repair_d0_d1(content.operations);
            // lopdf attaches the decoded image as a Stream operand; without one
            // the image was skipped and the lenient path must run instead.
            let dropped_image = ops.iter().any(|o| {
                o.operator == "BI"
                    && !o
                        .operands
                        .iter()
                        .any(|x| matches!(x, Object::Stream(_)))
            });
            if dropped_image {
                return None;
            }
            Some(ops)
        }
        _ => None,
    }
}

/// Undo lopdf 0.36's mis-tokenisation of `d0`/`d1` (§9.6.5, Table 113).
///
/// lopdf ends an operator token at the first digit, so `700 0 d0` decodes as the
/// DASH operator `d` with operands `[700, 0]` and the orphaned `0` becomes the
/// FIRST OPERAND OF THE NEXT OPERATION. §9.6.5 requires every Type 3 glyph
/// description to begin with `d0` or `d1`, so untreated this corrupts the first
/// painting operator of every conforming Type 3 glyph — `re` arrives with five
/// operands and is dropped, `m` starts the subpath at the wrong point.
///
/// A conforming `d` takes an ARRAY then a number (§8.4.3.6, Table 52), so a `d`
/// whose operands are ALL NUMERIC cannot be a dash and is unambiguously the
/// mangled form: two operands mean `d0` (`wx wy`), six mean `d1`
/// (`wx wy llx lly urx ury`).
///
/// The rename happens only AFTER the leaked digit is confirmed to be sitting
/// there as the next operation's first operand, so a genuinely malformed `d` in
/// a real dash context is left alone. A `d0`/`d1` that is the final operator of
/// a stream — the shape of every blank glyph, `wx 0 d0` and nothing else —
/// leaves no next operation and so no digit to confirm, and is renamed on the
/// strength of the operand signature alone.
///
/// The lenient tokenizer needs no equivalent: its [`OPERATORS`] table lists
/// `d0`/`d1` and matches the whole token.
fn repair_d0_d1(mut ops: Vec<Operation>) -> Vec<Operation> {
    for i in 0..ops.len() {
        let mangled = ops[i].operator == "d"
            && matches!(ops[i].operands.len(), 2 | 6)
            && ops[i]
                .operands
                .iter()
                .all(|o| matches!(o, Object::Integer(_) | Object::Real(_)));
        if !mangled {
            continue;
        }
        let is_d1 = ops[i].operands.len() == 6;
        if let Some(next) = ops.get_mut(i + 1) {
            let leaked = match next.operands.first() {
                Some(Object::Integer(v)) => *v == i64::from(is_d1),
                Some(Object::Real(v)) => *v == f32::from(u8::from(is_d1)),
                _ => false,
            };
            if !leaked {
                continue;
            }
            let _ = next.operands.remove(0);
        }
        ops[i].operator = if is_d1 { "d1" } else { "d0" }.to_string();
    }
    ops
}

/// Operations for a page's content, preferring lopdf's strict parser and falling
/// back to [`parse_operations_lenient`] when it fails or recovers nothing.
///
/// The `bool` is true when the fallback produced the result (for logging/tests).
pub(crate) fn page_operations(doc: &Document, page_id: ObjectId) -> (Vec<Operation>, bool) {
    // Decode with OUR filter chain, then parse strictly first so files that render
    // today are unaffected by the parse, falling back to the lenient tokenizer.
    //
    // NOT `get_and_decode_page_content`: that decodes through lopdf, whose ASCII85
    // decoder adds a base-85 5-tuple into a `u32` unchecked (lopdf/src/object.rs:777),
    // so a stream containing `uuuuu` panics with "attempt to add with overflow" in
    // debug and wraps silently in release. A panic here is fatal at the JNI boundary:
    // one malformed content stream kills the whole document. `page_content_bytes` is
    // also the more correct decode — it joins every /Contents stream with intervening
    // white space per §7.8.2, and yields nothing rather than ciphertext when a decoder
    // fails.
    let bytes = page_content_bytes(doc, page_id);
    if bytes.is_empty() {
        return (Vec::new(), false);
    }
    if let Some(ops) = strict_operations(&bytes) {
        return (ops, false);
    }
    // Either the parse failed (the inline-image `cut` case), it recovered nothing, or the
    // nesting was too deep to hand to lopdf at all.
    // All three render a blank page today, so the lenient path can only improve matters.
    (parse_operations_lenient(&bytes), true)
}

/// Operations for a NESTED content stream: a form XObject (`Do`), a soft-mask
/// group, a tiling-pattern cell or an annotation appearance stream.
///
/// Those streams reach `Content::decode` directly, and on failure the caller drops
/// the entire stream — so a single inline image inside a form XObject blanks that
/// whole form exactly as it blanks a whole page. Returns an empty vector only when
/// nothing at all could be recovered, so callers can use the result unconditionally.
pub(crate) fn stream_operations(doc: &Document, stream: &Stream) -> Vec<Operation> {
    operations_from_bytes(&stream_data_with_doc(doc, stream))
}

/// Strict parse first, lenient tokenizer only if that fails or yields nothing.
fn operations_from_bytes(bytes: &[u8]) -> Vec<Operation> {
    if let Some(ops) = strict_operations(bytes) {
        return ops;
    }
    parse_operations_lenient(bytes)
}

/// Concatenated, filter-decoded bytes of a page's `/Contents`.
///
/// Deliberately not `Document::get_page_content`: that writes the still-ENCODED
/// bytes when `decompressed_content` fails (lopdf document.rs:617), which would
/// hand compressed data to the tokenizer to be parsed as operators. Our
/// [`stream_data_with_doc`] handles the filter chain case-insensitively and yields
/// nothing rather than ciphertext when every decoder fails.
pub(crate) fn page_content_bytes(doc: &Document, page_id: ObjectId) -> Vec<u8> {
    let mut out = Vec::new();
    for id in doc.get_page_contents(page_id) {
        if let Ok(Object::Stream(s)) = doc.get_object(id) {
            out.extend_from_slice(&stream_data_with_doc(doc, s));
            // §7.8.2: the streams are concatenated with intervening white space so a
            // lexical token cannot span the boundary between two of them.
            out.push(b'\n');
        }
    }
    out
}

// ---------------------------------------------------------------------------
// Lexer
// ---------------------------------------------------------------------------

struct Lexer<'a> {
    d: &'a [u8],
    p: usize,
}

include!("content_part1.rs");
include!("content_part2.rs");
include!("content_part3.rs");
include!("content_part4.rs");