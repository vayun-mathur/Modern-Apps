use crate::*;
use indexmap::IndexMap;

// Document registry - bounded TRUE LRU to avoid long-running leak.
// Uses IndexMap to preserve insertion/access order for true LRU semantics.
//
// Lock ordering policy (documented to avoid deadlock):
//   - Always acquire `registry()` lock BEFORE `index_cache()` lock when both are needed.
//   - `next_handle` only locks NEXT static — independent.
//   - `open_document_pw`, `close_document`, `page_count` follow registry -> index_cache order.
//   - `search.rs::ensure_index` must also respect registry -> index_cache (audit fix):
//       it checks index_cache first (read-only), then takes registry, then index_cache again for insert.
//       This is safe because the first check is optimistic and the second insert is after dropping registry
//       OR it must be documented that registry is taken first in the critical section.
//   Documented order: registry → index_cache.

const MAX_REG_DOCS: usize = 8;
/// Max PDF size accepted to prevent zip-bomb / OOM DoS (200 MB).
pub(crate) const MAX_PDF_BYTES: usize = 200 * 1024 * 1024;

/// Nesting-depth ceiling for the raw pre-scan (see [`nesting_exceeds`]).
///
/// Well above anything a real producer emits - nesting past a couple of dozen
/// levels does not occur outside deliberately hostile files - and well below the
/// ~256 levels measured to survive an 8 MiB stack inside lopdf's object parser.
const MAX_RAW_NESTING: u32 = 200;

/// Stack for the document-parse worker (see [`load_mem_on_big_stack`]).
const OPEN_STACK_BYTES: usize = 32 * 1024 * 1024;

pub(crate) fn registry() -> &'static Mutex<IndexMap<i64, Document>> {
    static REG: OnceLock<Mutex<IndexMap<i64, Document>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(IndexMap::new()))
}

pub(crate) fn next_handle() -> i64 {
    static NEXT: OnceLock<Mutex<i64>> = OnceLock::new();
    let m = NEXT.get_or_init(|| Mutex::new(0));
    let mut guard = m.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    *guard += 1;
    *guard
}

/// Parse `bytes` into a document and store it, returning a non-zero handle.
/// Encrypted documents are decrypted in place (with `password`, empty allowed);
/// supports RC4 and AES (V4/V5) standard security handlers. Returns 0 on parse
/// failure, wrong password, or unsupported encryption.
///
/// Size guard: rejects inputs larger than 200 MB to prevent OOM DoS.
/// Load `bytes` and, if encrypted, decrypt with `password`; None on any
/// failure (bad password, unparseable, unsupported).
///
/// Why the password goes to the loader: lopdf 0.45's `load_mem` authenticates
/// with the (empty) load password and, when that fails, returns a document
/// whose objects were never loaded — only the /Encrypt dictionary survives.
/// `decrypt_in_place` on such a husk reports Ok after "decrypting" nothing,
/// so a user-password-protected PDF used to open as a successful empty
/// document. Loading with the password lets lopdf decrypt during the parse
/// (objects present, plaintext, /Encrypt cleared); the in-place path remains
/// as the fallback for passwords lopdf's &str API cannot carry and for
/// encryption variants its handler refuses.
pub(crate) fn open_decrypted(bytes: &[u8], password: &[u8]) -> Option<Document> {
    let mut doc = load_document_lenient(bytes)?;
    if doc.trailer.get(b"Encrypt").is_err() {
        return Some(doc);
    }
    if let Ok(pw) = std::str::from_utf8(password) {
        if let Ok(d2) = Document::load_mem_with_options(
            bytes,
            lopdf::LoadOptions::with_password(pw),
        ) {
            // was_encrypted + !is_encrypted means lopdf authenticated and
            // decrypted in-place; an auth failure comes back as a husk
            // (still "encrypted", no objects), which must not win.
            if d2.was_encrypted() && !d2.is_encrypted() && !d2.objects.is_empty() {
                return Some(d2);
            }
        }
    }
    if decrypt_in_place(&mut doc, password) != DecryptStatus::Ok {
        return None;
    }
    Some(doc)
}

pub(crate) fn open_document_pw(bytes: &[u8], password: &[u8]) -> i64 {
    // Zip-bomb / OOM guard: reject absurdly large PDF before parsing.
    if bytes.len() > MAX_PDF_BYTES {
        return 0;
    }
    let doc = match open_decrypted(bytes, password) {
        Some(d) => d,
        None => return 0,
    };
    let handle = next_handle();
    // Lock ordering: registry first, then index_cache if eviction needed.
    let mut map = registry()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    // True LRU: evict index 0 (least recently used). Page accesses bump to end via move_index.
    if map.len() >= MAX_REG_DOCS {
        if let Some((oldest_key, _)) = map.shift_remove_index(0) {
            // Still holding registry lock, now acquire index_cache (registry -> index_cache order)
            let mut ic = index_cache()
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            ic.remove(&oldest_key);
        }
    }
    map.insert(handle, doc);
    drop(map);
    handle
}

pub(crate) fn open_document(bytes: &[u8]) -> i64 {
    // No password (empty, built at runtime — not a hard-coded credential).
    open_document_pw(bytes, &Vec::<u8>::new())
}

/// Load a document, falling back to cross-reference reconstruction when lopdf's
/// strict parser rejects an otherwise-recoverable file.
///
/// The common real-world failure (seen with viewers that tolerate it, e.g.
/// Chrome/Acrobat) is a `startxref` offset that points a byte or two off the
/// `xref` keyword, or a damaged/incremental xref chain. lopdf's `xref` parser
/// requires the keyword at the exact offset, so such files yield
/// `InvalidFileTrailer`. On any load error we rebuild a fresh classic xref by
/// scanning the byte stream for indirect objects and append it as an
/// incremental section, then reload. This never runs for files that already
/// parse, so it cannot regress the happy path.
///
/// # Defending the lopdf boundary
///
/// Two of lopdf's failure modes on hostile input are not survivable from our side
/// once it has been entered: unbounded recursion in the object parser is a
/// guard-page fault (`STATUS_STACK_OVERFLOW`), which is not an unwind and so is
/// invisible to the `catch_unwind` in `jni_bindings.rs` - it kills the process -
/// and a degenerate cross-reference stream is a multi-billion-iteration loop, not
/// an error return. Both are decidable from the raw bytes, so they are decided
/// here, before the parser is entered, per 7.5.1's requirement that a damaged file
/// be handled rather than crash the reader. The parse then runs on a worker thread
/// with a large stack as defence in depth, for the recursion the pre-scan cannot
/// see (nesting that only exists after an object stream is decompressed).
pub(crate) fn load_document_lenient(bytes: &[u8]) -> Option<Document> {
    // Unbounded recursion: nothing downstream can recover from it, so this is a
    // hard rejection rather than a fall-through to recovery - the rebuilt file below
    // would contain the same nesting and overflow the same way.
    if nesting_exceeds(bytes, MAX_RAW_NESTING) {
        return None;
    }
    // A degenerate xref stream, by contrast, is survivable: skip only lopdf's
    // strict load, and let recovery below produce a classic table and a fresh
    // trailer that never reference the bad stream. The document still opens.
    if !xref_stream_is_degenerate(bytes) {
        if let Ok(d) = load_mem_on_big_stack(bytes) {
            return Some(d);
        }
    }
    let rebuilt = rebuild_with_scanned_xref(bytes)?;
    load_mem_on_big_stack(&rebuilt).ok()
}

/// `Document::load_mem` on a worker thread with [`OPEN_STACK_BYTES`] of stack.
///
/// lopdf's object parser recurses once per nesting level, so the depth it survives
/// is a property of whichever thread happened to call in. On Android that is a JNI
/// thread whose stack is a fraction of a desktop main thread's, which is why the
/// same file can open on a workstation and kill the app on a phone. Pinning the
/// stack here makes the headroom explicit rather than accidental.
///
/// The input is borrowed, not copied - a PDF may be up to [`MAX_PDF_BYTES`]. A
/// panic is re-raised with its original payload, so the JNI `catch_unwind` and the
/// robustness harness both still observe it; a spawn failure falls back to the
/// calling thread, which is exactly the behaviour before this existed.
fn load_mem_on_big_stack(bytes: &[u8]) -> lopdf::Result<Document> {
    std::thread::scope(|s| {
        match std::thread::Builder::new()
            .name("pdf-open".to_owned())
            .stack_size(OPEN_STACK_BYTES)
            .spawn_scoped(s, || Document::load_mem(bytes))
        {
            Ok(h) => match h.join() {
                Ok(r) => r,
                Err(payload) => std::panic::resume_unwind(payload),
            },
            Err(_) => Document::load_mem(bytes),
        }
    })
}

/// Whether `<<`/`[` nesting anywhere in `bytes` exceeds `max` levels.
///
/// This is a pre-check, not a parser: it decides only whether the bytes are safe to
/// hand to lopdf's recursive object parser. It is therefore deliberately biased -
/// every ambiguity below resolves toward under-counting, because a false positive
/// refuses a document (bad) while a false negative is a process kill (worse), and
/// the depth limit is generous enough that under-counting still leaves the limit
/// far below the stack's real capacity.
///
/// Context that has to be respected or the count is meaningless:
///   * 7.3.4.2 literal strings - parens nest, and `\` escapes the next byte, so a
///     `>`, `[` or `<<` inside `(...)` must not move the count. Getting this wrong
///     would reject legitimate documents, which is the one outcome worse than the
///     bug being defended against.
///   * 7.3.4.3 hex strings - `<AB CD>` opens with the same byte as a dictionary.
///   * 7.2.4 comments - `%` to end of line.
///   * 7.3.8 stream bodies - compressed and image data is dense in `[` and `<<`
///     bytes, is not parsed as objects at this level, and would otherwise drift the
///     count upward on every large legitimate file. Skipped wholesale.
/// The count also resets at each `endobj`, so one unbalanced object cannot leak its
/// residual depth into the rest of the file. The one direction that can over-count
/// is a stream body containing the literal bytes `endstream`, which would resume the
/// scan inside binary data; that is why the reset exists and why the limit is 200
/// rather than the couple of dozen levels real files actually use.
fn nesting_exceeds(bytes: &[u8], max: u32) -> bool {
    let n = bytes.len();
    let mut depth: u32 = 0;
    let mut i = 0usize;
    while i < n {
        match bytes[i] {
            b'%' => {
                while i < n && bytes[i] != b'\n' && bytes[i] != b'\r' {
                    i += 1;
                }
            }
            b'(' => {
                let mut nest = 1usize;
                i += 1;
                while i < n && nest > 0 {
                    match bytes[i] {
                        b'\\' => i += 1,
                        b'(' => nest += 1,
                        b')' => nest -= 1,
                        _ => {}
                    }
                    i += 1;
                }
            }
            b'<' if bytes.get(i + 1) == Some(&b'<') => {
                depth += 1;
                if depth > max {
                    return true;
                }
                i += 2;
            }
            b'<' => {
                i += 1;
                while i < n && bytes[i] != b'>' {
                    i += 1;
                }
                i += 1;
            }
            b'>' if bytes.get(i + 1) == Some(&b'>') => {
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
            // 7.3.8: the `stream` keyword follows the dictionary's `>>`. Requiring
            // that boundary keeps a name like `/Substream` from being mistaken for
            // the start of a body. The byte AFTER it is deliberately not checked:
            // the spec says CRLF or LF, but files that omit it exist, and treating
            // one as a non-stream would leave its binary body to be counted.
            b's' if bytes[i..].starts_with(b"stream")
                && i > 0
                && (is_pdf_ws(bytes[i - 1]) || bytes[i - 1] == b'>') =>
            {
                match find_subsequence(bytes, b"endstream", i + 6) {
                    Some(end) => i = end + 9,
                    // Unterminated stream: nothing after it can be located reliably.
                    None => return false,
                }
            }
            b'e' if bytes[i..].starts_with(b"endobj") => {
                depth = 0;
                i += 6;
            }
            _ => i += 1,
        }
    }
    false
}

/// Whether a cross-reference STREAM in `bytes` declares an entry layout that makes
/// lopdf's reader loop without consuming input (7.5.8).
///
/// `/W` gives the byte width of each of the three entry fields.
/// `decode_xref_stream` (lopdf parser_aux.rs:291) iterates the count declared by
/// each `/Index` pair and reads exactly `W[0] + W[1] + W[2]` bytes per entry, so
/// when that total is zero it consumes nothing, `read_exact` never fails, and the
/// declared count is the *only* bound: `/W [0 0 0] /Index [0 2147483647]` on an
/// 8-byte stream is 2.1 billion iterations, each inserting an xref entry. Measured
/// at 243 s without completing, against ~10 ms for the same document with a sane
/// `/W`. lopdf already rejects a negative or short `/W`, so a zero total is the
/// whole of the remaining hole.
///
/// The `/Index` check is the same argument made against the file rather than the
/// stream: a file cannot describe more cross-reference entries than it has bytes,
/// since every object it lists needs at least one byte somewhere.
fn xref_stream_is_degenerate(bytes: &[u8]) -> bool {
    let mut from = 0usize;
    // Bound on candidate dictionaries examined. A real file has one `/XRef` per
    // incremental update; scanning is windowed to a constant per candidate so that
    // this whole check stays O(n) even on a file that repeats the token to make it
    // quadratic. Stopping at the cap only returns that file to the behaviour it had
    // before this check existed.
    let mut candidates = 0u32;
    while let Some(hit) = find_subsequence(bytes, b"/XRef", from) {
        from = hit + 5;
        candidates += 1;
        if candidates > 64 {
            return false;
        }
        // The dictionary holding it: back to the start of the line, forward to the
        // stream body. The dictionary is plain text even when the body is not.
        let back = hit.saturating_sub(4096);
        let start = bytes[back..hit]
            .iter()
            .rposition(|&b| b == b'\n' || b == b'\r')
            .map(|p| back + p + 1)
            .unwrap_or(back);
        let win_end = (hit + 8192).min(bytes.len());
        let end = find_subsequence(&bytes[..win_end], b"stream", hit).unwrap_or(win_end);
        let dict = &bytes[start..end];
        if let Some(w) = int_array_after_key(dict, b"/W") {
            if w.len() >= 3 && w.iter().take(3).all(|&x| x == 0) {
                return true;
            }
        }
        if let Some(index) = int_array_after_key(dict, b"/Index") {
            // Saturating, not `sum()`: every count here came out of the file, and
            // `i64 + i64` panics on overflow in debug and WRAPS in release - where a
            // wrap to a negative total would make the very file this check exists to
            // catch (`/Index [0 <i64::MAX> 0 <i64::MAX>]`) read as harmless.
            let declared = index
                .iter()
                .skip(1)
                .step_by(2)
                .fold(0i64, |acc, &n| acc.saturating_add(n.max(0)));
            if declared > bytes.len() as i64 {
                return true;
            }
        }
    }
    false
}

/// Integers of the array that follows `key` in a raw dictionary slice, e.g. the
/// `[1 2 1]` of `/W [1 2 1]`. `None` unless `key` appears as a complete name
/// followed by an array of plain integers.
fn int_array_after_key(dict: &[u8], key: &[u8]) -> Option<Vec<i64>> {
    let mut p = 0usize;
    'candidate: while p < dict.len() {
        let rel = dict[p..].windows(key.len()).position(|w| w == key)?;
        let mut i = p + rel + key.len();
        p = p + rel + 1;
        // `/W` must not match the `/W` of `/Widths`.
        match dict.get(i) {
            Some(&b) if !is_pdf_ws(b) && b != b'[' => continue 'candidate,
            None => return None,
            _ => {}
        }
        while i < dict.len() && is_pdf_ws(dict[i]) {
            i += 1;
        }
        if dict.get(i) != Some(&b'[') {
            continue 'candidate;
        }
        i += 1;
        let mut out = Vec::new();
        while i < dict.len() && dict[i] != b']' {
            if is_pdf_ws(dict[i]) {
                i += 1;
            } else if dict[i] == b'-' || dict[i].is_ascii_digit() {
                let s = i;
                i += 1;
                while i < dict.len() && dict[i].is_ascii_digit() {
                    i += 1;
                }
                match std::str::from_utf8(&dict[s..i]).ok().and_then(|t| t.parse::<i64>().ok()) {
                    Some(v) if out.len() < 64 => out.push(v),
                    // Not a plain integer array, or longer than any real /W or
                    // /Index: not something to draw conclusions from.
                    _ => continue 'candidate,
                }
            } else {
                continue 'candidate;
            }
        }
        return Some(out);
    }
    None
}

/// True for PDF whitespace / delimiter bytes (used for token-boundary checks).
fn is_pdf_ws(b: u8) -> bool {
    matches!(b, b' ' | b'\t' | b'\r' | b'\n' | b'\x0c' | b'\0')
}

/// Byte offset of the first occurrence of `needle` at or after `from`.
fn find_subsequence(haystack: &[u8], needle: &[u8], from: usize) -> Option<usize> {
    if from >= haystack.len() || needle.is_empty() {
        return None;
    }
    haystack[from..]
        .windows(needle.len())
        .position(|w| w == needle)
        .map(|p| from + p)
}

include!("registry_part1.rs");
include!("registry_part2.rs");