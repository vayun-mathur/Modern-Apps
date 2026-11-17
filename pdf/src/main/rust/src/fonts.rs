use crate::*;
use std::sync::Arc;

/// Highest CID a composite font may use (PDF 32000-1 9.7.4.3). Ranges read from
/// untrusted `/W`, `/W2` and CMap data are clamped to this so a corrupt or
/// hostile upper bound cannot drive a multi-billion-iteration loop.
pub(crate) const MAX_CID: u32 = 0xFFFF;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub(crate) struct FontStyle {
    pub(crate) bold: bool,
    pub(crate) italic: bool,
}

#[derive(Clone)]
/// `Clone` is the font cache's HIT path (see [`FontCacheScope`]), so every
/// collection here is behind an `Arc`: cloning must not copy the tables, which
/// for a CJK `/W` table of 32768 entries dominated the hit. Adding an owned
/// collection here silently reintroduces that.
pub(crate) struct FontInfo {
    /// Type0 (Identity-H) fonts use 2-byte codes; simple fonts use 1 byte.
    pub(crate) two_byte: bool,
    pub(crate) wmode: u8,
    /// CID -> `(w1_y, v_x)` from `/W2`: the vertical displacement and the x
    /// component of the position vector, in text-space units (glyph units/1000).
    pub(crate) vertical_metrics: Arc<HashMap<u32, (f64, f64)>>,
    /// `(v_y, w1_y)` from `/DW2`, text-space units. Spec default `[880 -1000]`.
    pub(crate) default_vertical: (f64, f64),
    pub(crate) cid_to_gid: Option<Arc<HashMap<u32, u16>>>,
    /// `code -> unicode string` from the font's `/ToUnicode` CMap, if any.
    /// Shared, not owned: see [`FontCacheScope`].
    pub(crate) to_unicode: Option<Arc<HashMap<u32, String>>>,
    /// `code -> unicode char` from the simple-font encoding (base + Differences),
    /// used when `/ToUnicode` is absent or lacks the code.
    pub(crate) encoding: Arc<HashMap<u32, char>>,
    /// `code -> unicode char` recovered from an embedded TrueType `cmap`, for
    /// re-encoded subset fonts without `/ToUnicode`. Preferred over `encoding`.
    pub(crate) cmap_uni: Arc<HashMap<u32, char>>,
    /// Type0 `/Encoding` CMap mapping character codes -> CIDs (non-Identity CJK
    /// encodings). `None` for Identity-H/V (code == CID) and simple fonts.
    pub(crate) cmap: Option<Arc<cmap::EncodingCMap>>,
    /// `code (or CID) -> glyph width` in text-space units (glyph units / 1000).
    pub(crate) widths: Arc<HashMap<u32, f64>>,
    /// Fallback width (glyph units / 1000) for codes absent from `widths`.
    pub(crate) default_width: f64,
    /// Type 3 font data (glyph CharProc content streams), if this is a Type 3 font.
    pub(crate) t3: Option<Type3Font>,
    /// Synthetic font style recovered from BaseFont name + FontDescriptor.
    pub(crate) style: FontStyle,
    /// Generic font family for substitute shaping on the Kotlin side, recovered
    /// from the BaseFont name + FontDescriptor `/Flags`: 0 = sans-serif,
    /// 1 = serif, 2 = monospace.
    pub(crate) family: u8,
    /// Descriptive base font name for fallback shaping (optional).
    /// The raw `/BaseFont` name, kept for diagnostics only — everything downstream
    /// needs from it is already extracted at parse time into `style` and `family`,
    /// which ship packed into the v8 `fontFlags` byte. Read by the local-file debug
    /// test, so do not go looking for a consumer to wire it to.
    #[allow(dead_code)]
    pub(crate) base_font: String,
    /// Embedded font program (TrueType/CFF/Type1) for rendering the PDF's real
    /// glyph outlines. `None` falls back to system-font substitution. Shared
    /// rather than owned because it holds the whole decompressed program: see
    /// [`FontCacheScope`].
    pub(crate) glyph_program: Option<Arc<crate::outlines::GlyphProgram>>,
    /// `code -> glyph name` from the PDF `/Encoding` `/Differences`, used to look
    /// up outlines by name in Type1 / CFF programs.
    pub(crate) glyph_names: Arc<HashMap<u32, String>>,
}

/// Type 3 font: glyphs are content streams drawn in glyph space, mapped to text
/// space by `font_matrix`.
#[derive(Clone)]
pub(crate) struct Type3Font {
    pub(crate) font_matrix: Mat,
    /// Character code -> CharProc stream object id (via `/Encoding` Differences).
    pub(crate) char_procs: HashMap<u32, ObjectId>,
    pub(crate) resources: Option<Dictionary>,
}

impl FontInfo {
    /// Whether showing `bytes` in this font iterates at least one character code.
    ///
    /// A NON-EMPTY string can show ZERO glyphs, so `!bytes.is_empty()` is not a
    /// stand-in for "a glyph was shown". The Identity-H/V arm of `for_each_code`
    /// consumes codes in pairs (`while i + 1 < bytes.len()`), so a lone trailing
    /// byte is dropped and a 1-byte string yields nothing at all. The other two arms
    /// always consume a non-empty slice, which is why the difference is invisible
    /// unless you look at this specific encoding.
    ///
    /// This exists because §9.4.3's text clip must be latched on whether glyphs were
    /// SHOWN, not on whether the operand was non-empty: latching for a run that
    /// showed nothing produces a clip with no outlines behind it, and an empty
    /// accumulation clips the following content to nothing. Deliberately delegates
    /// to `for_each_code` rather than re-deriving the code widths, so it cannot
    /// drift away from what the show path actually iterates.
    pub(crate) fn shows_any_glyph(&self, bytes: &[u8]) -> bool {
        let mut any = false;
        self.for_each_code(bytes, |_, _| any = true);
        any
    }

    /// Invoke `f(code, is_single_byte_space)` for each character code in the
    /// string, honoring this font's code width (1 or 2 bytes).
    pub(crate) fn for_each_code(&self, bytes: &[u8], mut f: impl FnMut(u32, bool)) {
        if self.two_byte {
            match &self.cmap {
                // Non-Identity CMap: segment bytes by the codespace (variable
                // length) and yield the raw character code. Width/glyph lookups
                // map code -> CID via `to_cid`.
                Some(cm) => {
                    let mut i = 0;
                    while i < bytes.len() {
                        let n = cm.code_len_at(&bytes[i..]).clamp(1, 4).min(bytes.len() - i);
                        let mut c = 0u32;
                        for k in 0..n { c = (c << 8) | bytes[i + k] as u32; }
                        // Tw applies only to a single-byte code 32.
                        f(c, n == 1 && c == 32);
                        i += n;
                    }
                }
                // Identity-H/V: fixed 2-byte codes, code == CID.
                None => {
                    let mut i = 0;
                    while i + 1 < bytes.len() {
                        let code = ((bytes[i] as u32) << 8) | bytes[i + 1] as u32;
                        // Word spacing (Tw) never applies to 2-byte codes (PDF 9.3.3).
                        f(code, false);
                        i += 2;
                    }
                }
            }
        } else {
            for &b in bytes {
                let code = b as u32;
                // PDF 9.3.3: Tw applies to the single-byte code 32 and to nothing
                // else. Notably NOT to NBSP, which must not stretch under
                // justification, nor to other codes the encoding maps to a space.
                f(code, code == 32);
            }
        }
    }

    /// Map a raw character code to a CID via the `/Encoding` CMap (identity when
    /// there is no CMap, i.e. Identity-H/V or simple fonts).
    pub(crate) fn to_cid(&self, code: u32) -> u32 {
        match &self.cmap {
            Some(cm) => cm.to_cid(code),
            None => code,
        }
    }

    /// Width of `code` in text-space units (glyph units / 1000). Widths (`/W`)
    /// are keyed by CID, so the code is mapped through the CMap first.
    pub(crate) fn width(&self, code: u32) -> f64 {
        let cid = self.to_cid(code);
        self.widths.get(&cid).copied().unwrap_or(self.default_width)
    }

    pub(crate) fn push_code(&self, code: u32, out: &mut String) {
        if let Some(map) = &self.to_unicode {
            if let Some(s) = map.get(&code) {
                out.push_str(s);
                return;
            }
        }
        // Prefer the declared encoding (WinAnsi / Differences) so standard
        // punctuation is correct; fall back to the embedded cmap for symbolic
        // re-encoded subset fonts whose encoding doesn't cover the code.
        if let Some(c) = self.encoding.get(&code) {
            out.push(*c);
            return;
        }
        if let Some(c) = self.cmap_uni.get(&code) {
            out.push(*c);
            return;
        }
        // Last resort: Latin-1 for single-byte codes; best-effort otherwise.
        if let Some(c) = char::from_u32(code) {
            out.push(c);
        }
    }
}

thread_local! {
    /// Populated only inside a [`FontCacheScope`]. `None` means "no cache", which
    /// is the safe default: an entry cannot go stale if none is stored.
    static FONT_CACHE: std::cell::RefCell<Option<HashMap<ObjectId, (u64, FontInfo)>>> =
        const { std::cell::RefCell::new(None) };
}

/// Enables the [`FontInfo`] cache for the lifetime of the guard.
///
/// [`font_info`] decompresses and parses the entire embedded font program, and
/// [`fonts_from_resources`] runs on *every* `interpret_content` call — so without
/// a cache a font shared by N pages is parsed N times to render and another N
/// times to build the search index.
///
/// The key is the font dictionary's object id, and a hit additionally requires the stored
/// [`font_identity`] — a hash of the font dictionary with every indirect reference
/// RESOLVED — to still match. Hashing is far cheaper than `font_info`, which parses the
/// whole embedded font program into glyph outlines.
///
/// It is not, however, free, and it is what a hit now costs: every [`FontInfo`] collection
/// is behind an `Arc`, so the clone is O(1), but `font_identity` walks the resolved dict on
/// EVERY lookup — including a CID font's `/W` array element by element and the embedded
/// program's bytes. Measured by `perf_font_cache_hit_vs_parse`: 0.80 ms per hit at
/// `/W` = 32768 against 12.1 ms per uncached render, so the cache is still strongly
/// worth having, but the per-hit cost scales with the font rather than being constant.
/// Making it constant means a key that needs no content check — the registry handle, which
/// is a stable identity — and that is a change to `FontCacheScope::new`'s signature and to
/// its callers in `interpret.rs` and `search.rs`. Do NOT instead weaken the hash to a
/// bounded sample of the font: that trades the identity guarantee below for speed, which is
/// the exact trade that put the address key here in the first place.
///
/// The id alone is not an identity, and neither of the two cheaper things this tried
/// first is either:
///   * the id paired with the `Document`'s ADDRESS — overwriting a `Box<Document>` in
///     place puts the replacement at the same address, so a scope spanning both served
///     the first document's fonts for the second (a Courier document rendered with
///     Helvetica's metrics). A raw pointer is an identity no test can enforce and no
///     compiler error can catch.
///   * the id paired with the font dictionary itself — shallow equality misses two
///     documents whose font dicts are byte-identical but whose indirect TARGETS differ,
///     the same `/Widths 7 0 R` naming different arrays.
///
/// Resolving through the references closes both.
///
/// It is still a *complete* key only while the document is not mutated. That is precisely
/// why the cache is scoped to one top-level operation and dropped at the end instead of
/// living in a global: a `docedit` mutation between operations cannot be served a stale
/// entry, because between operations there is no cache at all. Fonts written as direct
/// (non-indirect) dictionaries have no object id and are never cached.
///
/// Nesting is safe — only the outermost guard installs and tears down the cache —
/// so callers may create one without knowing whether an outer one exists.
pub(crate) struct FontCacheScope {
    outermost: bool,
}

impl FontCacheScope {
    pub(crate) fn new() -> Self {
        let outermost = FONT_CACHE.with(|c| {
            let mut c = c.borrow_mut();
            if c.is_none() {
                *c = Some(HashMap::new());
                true
            } else {
                false
            }
        });
        FontCacheScope { outermost }
    }
}

impl Drop for FontCacheScope {
    fn drop(&mut self) {
        if self.outermost {
            FONT_CACHE.with(|c| *c.borrow_mut() = None);
        }
    }
}

/// Build a `font resource name -> FontInfo` map from a resources dictionary.
pub(crate) fn fonts_from_resources(doc: &Document, res_dict: &lopdf::Dictionary) -> HashMap<Vec<u8>, FontInfo> {
    let mut fonts = HashMap::new();
    let font_dict = match res_dict.get(b"Font").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Dictionary(d)) => d,
        _ => return fonts,
    };
    for (name, font_ref) in font_dict.iter() {
        // Only an indirect font can be cached: a direct dictionary has no identity to
        // key on. A hit must also match the resolved content the id names now, so a
        // colliding object id from another document cannot be served.
        let id = match font_ref {
            Object::Reference(id) => Some(*id),
            _ => None,
        };
        let Some(Object::Dictionary(fd)) = deref(doc, font_ref) else {
            continue;
        };
        let identity = id.map(|_| font_identity(doc, fd));
        if let (Some(id), Some(identity)) = (id, identity) {
            if let Some(hit) = cached_font(id, identity) {
                fonts.insert(name.clone(), hit);
                continue;
            }
        }
        let fi = font_info(doc, fd);
        if let (Some(id), Some(identity)) = (id, identity) {
            cache_font(id, identity, &fi);
        }
        fonts.insert(name.clone(), fi);
    }
    fonts
}

fn cached_font(id: ObjectId, identity: u64) -> Option<FontInfo> {
    FONT_CACHE.with(|c| {
        let (stored, fi) = c.borrow().as_ref()?.get(&id)?.clone();
        (stored == identity).then_some(fi)
    })
}

fn cache_font(id: ObjectId, identity: u64, fi: &FontInfo) {
    FONT_CACHE.with(|c| {
        if let Some(map) = c.borrow_mut().as_mut() {
            map.insert(id, (identity, fi.clone()));
        }
    });
}

/// Hash `obj` with every indirect reference RESOLVED, so the result depends on the
/// content the font is actually built from rather than on object numbers.
///
/// Depth-bounded because a hostile file can make the object graph cyclic; a font dict
/// nests only a few levels (descendant font -> descriptor -> font file).
fn hash_resolved(doc: &Document, obj: &Object, h: &mut impl std::hash::Hasher, depth: u32) {
    use std::hash::Hash;
    if depth > 8 {
        return;
    }
    // Discriminants are hashed so `/X 1` and `/X (1)` cannot collide.
    match obj {
        Object::Null => 0u8.hash(h),
        Object::Boolean(b) => {
            1u8.hash(h);
            b.hash(h);
        }
        Object::Integer(i) => {
            2u8.hash(h);
            i.hash(h);
        }
        Object::Real(r) => {
            3u8.hash(h);
            r.to_bits().hash(h);
        }
        Object::Name(n) => {
            4u8.hash(h);
            n.hash(h);
        }
        Object::String(s, f) => {
            5u8.hash(h);
            s.hash(h);
            (*f as u8).hash(h);
        }
        Object::Array(a) => {
            6u8.hash(h);
            a.len().hash(h);
            for v in a {
                hash_resolved(doc, v, h, depth + 1);
            }
        }
        Object::Dictionary(d) => {
            7u8.hash(h);
            d.len().hash(h);
            for (k, v) in d.iter() {
                k.hash(h);
                hash_resolved(doc, v, h, depth + 1);
            }
        }
        Object::Stream(s) => {
            8u8.hash(h);
            for (k, v) in s.dict.iter() {
                k.hash(h);
                hash_resolved(doc, v, h, depth + 1);
            }
            // The still-encoded bytes: cheaper than decoding, and equal encoded bytes
            // under an equal dictionary decode to equal samples.
            s.content.hash(h);
        }
        // The whole point: hash what the reference POINTS AT, not its object number.
        Object::Reference(id) => match doc.get_object(*id) {
            Ok(o) => hash_resolved(doc, o, h, depth + 1),
            Err(_) => 9u8.hash(h),
        },
    }
}

/// Content identity of a font dictionary: everything [`font_info`] reads, resolved.
fn font_identity(doc: &Document, dict: &Dictionary) -> u64 {
    use std::hash::Hasher;
    let mut h = std::collections::hash_map::DefaultHasher::new();
    for (k, v) in dict.iter() {
        std::hash::Hash::hash(k, &mut h);
        hash_resolved(doc, v, &mut h, 0);
    }
    h.finish()
}

/// `code -> glyph name` from a simple font's NAMED base encoding, empty when the
/// font names none.
///
/// PDF 32000-1 9.6.6.2 orders a simple font's encoding: `/Differences` first,
/// then the base encoding named by `/Encoding` or `/Encoding /BaseEncoding`,
/// then the font program's own built-in encoding, then StandardEncoding. Only
/// the middle step needs a table here — an absent name is precisely the case
/// where the built-in encoding wins, and returning empty lets the caller fall
/// through to it.
///
/// MacRomanEncoding is deliberately not tabulated. PDF's MacRomanEncoding is not
/// Mac OS Roman (it leaves several codes undefined and differs at 0xDB), so a
/// half-remembered table would substitute one wrong glyph for another; leaving
/// it out keeps such fonts on the existing built-in-encoding path, which is the
/// spec's own next step.
fn named_base_encoding_names(doc: &Document, font: &lopdf::Dictionary) -> HashMap<u32, String> {
    let mut names = HashMap::new();
    let base = match font.get(b"Encoding").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Name(n)) => Some(String::from_utf8_lossy(n).into_owned()),
        Some(Object::Dictionary(d)) => d
            .get(b"BaseEncoding")
            .ok()
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_name().ok())
            .map(|n| String::from_utf8_lossy(n).into_owned()),
        _ => None,
    };
    let table: &[(u8, &str)] = match base.as_deref() {
        Some("WinAnsiEncoding") => encoding::WIN_ANSI_NAMES,
        Some("StandardEncoding") => crate::type1::STANDARD_ENCODING,
        _ => return names,
    };
    for (code, name) in table {
        names.insert(*code as u32, (*name).to_string());
    }
    names
}

include!("fonts_part1.rs");
include!("fonts_part2.rs");
include!("fonts_part3.rs");
include!("fonts_part4.rs");
include!("fonts_part5.rs");
include!("fonts_part6.rs");
include!("fonts_part7.rs");
include!("fonts_part8.rs");