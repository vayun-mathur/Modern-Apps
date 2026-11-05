use crate::*;

/// Numeric value of an integer or real object, else `None`.
pub(crate) fn num(obj: &Object) -> Option<f64> {
    match obj {
        Object::Integer(i) => Some(*i as f64),
        Object::Real(r) => Some(*r as f64),
        _ => None,
    }
}

/// Follow a chain of references to the underlying object.
pub(crate) fn deref<'a>(doc: &'a Document, obj: &'a Object) -> Option<&'a Object> {
    match doc.dereference(obj) {
        Ok((_, o)) => Some(o),
        Err(_) => None,
    }
}

/// Decoded stream bytes, falling back to the raw content when the stream has no
/// `/Filter` (lopdf's `decompressed_content` errors instead of returning raw).
/// This version is legacy without Document context (used for ToUnicode cmap and font files).
pub(crate) fn stream_data(s: &lopdf::Stream) -> Vec<u8> {
    // With no /Filter the content is already plaintext and lopdf errors rather
    // than returning it, so the raw fallback is correct here and only here.
    if s.dict.get(b"Filter").is_err() {
        return s.content.clone();
    }
    // A /Filter is present. lopdf's `decompressed_content` can no longer be
    // trusted as the first resort: when the filter list itself cannot be
    // resolved (an indirect reference, a wrong-typed entry) it returns
    // Ok(content) — the still-ENCODED bytes — and for implemented filters it
    // has the predictor-blindness and ASCII85 hazards documented on
    // `decode_stream_content`. Resolve through our own chain instead, which
    // handles every direct filter we implement (including /DecodeParms
    // predictors) and yields nothing for unresolvable or unknown ones.
    //
    // With no Document only DIRECT filter entries resolve. An indirect /Filter
    // leaves `specs` empty, and must yield nothing: returning the raw bytes
    // would feed binary garbage to the cmap/font parser.
    let doc = Document::new();
    let specs = filters::filter_specs_from_dict(&doc, &s.dict);
    if specs.is_empty() {
        return Vec::new();
    }
    if let Some(d) = filters::decode_stream_chain(s.content.clone(), &specs, &doc) {
        return d;
    }
    // Our chain failed. lopdf as a last resort, and only for a filter we do
    // not implement (Unknown): on a filter we DO implement it already judged
    // the bytes corrupt, and it cannot do better than garbage. (Its Ok here is
    // genuine: `specs` is non-empty, so the filter list resolved to direct
    // names lopdf can see; an Unknown name makes it Err, which falls through
    // to nothing below.) Skipped whenever a predictor is involved, exactly as
    // in `decode_stream_content`: lopdf cannot see an indirect or array-form
    // /DecodeParms and would return predictor-garbled bytes as success.
    let has_unknown = specs
        .iter()
        .any(|(k, _)| matches!(k, filters::FilterKind::Unknown(_)));
    if has_unknown && !wants_predictor(&doc, &s.dict) {
        if let Ok(d) = s.decompressed_content() {
            return d;
        }
    }
    // Every decoder failed. Returning the raw bytes here handed the
    // still-ENCODED bytes to the cmap/font parser as if they were plaintext —
    // visible as missing text or missing glyphs. Render nothing instead.
    Vec::new()
}

/// Extended decoder that uses Document context to handle filter chains case-insensitively,
/// including ASCIIHex, ASCII85, RunLength, LZW (EarlyChange), Flate with PNG predictors,
/// CCITT and JBIG2 (passthrough).
pub(crate) fn decode_stream_content(doc: &Document, dict: &Dictionary, raw: &[u8]) -> Vec<u8> {
    let specs = filters::filter_specs_from_dict(doc, dict);
    if specs.is_empty() {
        // No /Filter: the stream is already plaintext.
        return raw.to_vec();
    }
    // Image codecs (DCT/JPX/JBIG2/CCITT) are decoded by the image layer, which is the only
    // place with the real /Width and /Height, so it needs the still-encoded bytes.
    let image_codec_only = specs.iter().all(|(k, _)| {
        matches!(
            k,
            filters::FilterKind::Dct
                | filters::FilterKind::Jpx
                | filters::FilterKind::Jbig2
                | filters::FilterKind::Ccitt
                | filters::FilterKind::Crypt
        )
    });
    if image_codec_only {
        return raw.to_vec();
    }
    if let Some(decoded) = filters::decode_stream_chain(raw.to_vec(), &specs, doc) {
        return decoded;
    }
    // Fall back to lopdf's native chain ONLY for a filter name we do not implement.
    //
    // `decode_stream_chain` implements every `FilterKind` except `Unknown`, so when it
    // fails on a filter we DO implement it has already judged the bytes corrupt — and
    // lopdf cannot do better than garbage. It can do worse: its ASCII85 decoder adds a
    // base-85 5-tuple into a `u32` without checking (`lopdf/src/object.rs:777`), so
    // `uuuuu` panics with "attempt to add with overflow" in debug and wraps silently in
    // release. A panic here is fatal at the JNI boundary, which kills the whole document
    // for one malformed stream.
    //
    // It is also skipped whenever a predictor is involved: lopdf reads the parameters as
    // a single DIRECT dictionary (`get(b"DecodeParms").and_then(Object::as_dict)`), so an
    // indirect reference, or the array form used alongside a filter chain, silently
    // resolves to `None`. It then inflates happily and returns Ok with the /Predictor
    // NEVER UNDONE — success carrying garbage, which no error check can catch.
    let has_unknown_filter = specs
        .iter()
        .any(|(k, _)| matches!(k, filters::FilterKind::Unknown(_)));
    if has_unknown_filter && !wants_predictor(doc, dict) {
        let temp = Stream::new(dict.clone(), raw.to_vec());
        if let Ok(d) = temp.decompressed_content() {
            return d;
        }
    }
    // Every decoder failed. Returning `raw` here handed the still-ENCODED bytes to the
    // content-stream interpreter to be parsed as operators, or to the image layer as raw
    // samples — visible as garbage on the page. Render nothing instead.
    Vec::new()
}

/// Whether any `/DecodeParms` entry asks for a predictor (§7.4.4.4).
///
/// Used to decide whether lopdf's decoder can be trusted as a fallback: it cannot see
/// an indirect or array-form `/DecodeParms`, and silently returns undecoded-predictor
/// data as success. `/DP` is the inline-image abbreviation for the same key.
fn wants_predictor(doc: &Document, dict: &Dictionary) -> bool {
    let Some(parms) = dict
        .get(b"DecodeParms")
        .or_else(|_| dict.get(b"DP"))
        .ok()
        .and_then(|o| deref(doc, o))
    else {
        return false;
    };
    let has_predictor = |d: &Dictionary| {
        d.get(b"Predictor")
            .ok()
            .and_then(|o| deref(doc, o).and_then(num).or_else(|| num(o)))
            .is_some_and(|p| p > 1.0)
    };
    match parms {
        Object::Dictionary(d) => has_predictor(d),
        Object::Array(a) => a.iter().any(|el| {
            deref(doc, el)
                .and_then(|o| o.as_dict().ok())
                .is_some_and(has_predictor)
        }),
        _ => false,
    }
}

pub(crate) fn stream_data_with_doc(doc: &Document, s: &Stream) -> Vec<u8> {
    decode_stream_content(doc, &s.dict, &s.content)
}

// ---------------------------------------------------------------------------
// Fonts + ToUnicode
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    /// lopdf 0.45 implements ASCIIHexDecode and RunLengthDecode itself, so these
    /// filters no longer reach the fallback through lopdf's failure — but
    /// `stream_data` decodes direct filters through our own chain first, which
    /// is what this exercises. (An ASCIIHex- or RunLength-encoded ToUnicode
    /// CMap or embedded font file that decoded to nothing was silently no
    /// text, or no glyphs.)
    #[test]
    fn stream_data_decodes_filters_lopdf_does_not_implement() {
        let s = Stream::new(
            dictionary! { "Filter" => "ASCIIHexDecode" },
            b"48656C6C6F>".to_vec(),
        );
        assert_eq!(stream_data(&s), b"Hello".to_vec());

        // RunLength: literal run of 1 byte (0x00 => copy 1), then EOD (128).
        let s = Stream::new(
            dictionary! { "Filter" => "RunLengthDecode" },
            vec![0x00, 0xAB, 128],
        );
        assert_eq!(stream_data(&s), vec![0xAB]);
    }

    #[test]
    fn stream_data_never_returns_still_encoded_bytes() {
        // An indirect /Filter cannot be resolved without the owning document, so the
        // chain is unknown. Yielding the encoded bytes would feed binary garbage to
        // the cmap/font parser, which is the bug this guards.
        let raw = vec![0x9C, 0x78, 0x01, 0x02];
        let s = Stream::new(
            dictionary! { "Filter" => Object::Reference((99, 0)) },
            raw.clone(),
        );
        let out = stream_data(&s);
        assert!(out.is_empty(), "expected nothing, got {out:?}");
        assert_ne!(out, raw);

        // A genuinely unknown filter name likewise decodes to nothing, not to itself.
        let s = Stream::new(dictionary! { "Filter" => "MadeUpDecode" }, raw.clone());
        assert_ne!(stream_data(&s), raw);
    }

    #[test]
    fn stream_data_returns_raw_when_there_is_no_filter() {
        let raw = b"plain cmap bytes".to_vec();
        let s = Stream::new(Dictionary::new(), raw.clone());
        assert_eq!(stream_data(&s), raw);
    }

    /// lopdf reads /DecodeParms as a single DIRECT dictionary, so an indirect
    /// reference or the array form silently loses the /Predictor and it returns
    /// SUCCESS carrying predictor-encoded bytes. That cannot be caught by an error
    /// check, so the fallback must not be consulted at all in those cases.
    #[test]
    fn predictor_params_are_detected_in_every_form() {
        let mut doc = Document::new();

        // Direct dictionary.
        let d = dictionary! {
            "Filter" => "FlateDecode",
            "DecodeParms" => dictionary! { "Predictor" => 12, "Columns" => 4 },
        };
        assert!(wants_predictor(&doc, &d));

        // Predictor 1 means "no predictor" (§7.4.4.4), so the fallback stays usable.
        let d = dictionary! {
            "Filter" => "FlateDecode",
            "DecodeParms" => dictionary! { "Predictor" => 1 },
        };
        assert!(!wants_predictor(&doc, &d));

        // No /DecodeParms at all.
        let d = dictionary! { "Filter" => "FlateDecode" };
        assert!(!wants_predictor(&doc, &d));

        // INDIRECT /DecodeParms — the case lopdf cannot see.
        let parms_id = doc.add_object(dictionary! { "Predictor" => 15, "Columns" => 8 });
        let d = dictionary! {
            "Filter" => "FlateDecode",
            "DecodeParms" => Object::Reference(parms_id),
        };
        assert!(
            wants_predictor(&doc, &d),
            "an indirect /DecodeParms still asks for a predictor"
        );

        // ARRAY form, as used with a filter chain — also invisible to lopdf.
        let d = dictionary! {
            "Filter" => vec![Object::Name(b"ASCII85Decode".to_vec()), Object::Name(b"FlateDecode".to_vec())],
            "DecodeParms" => vec![
                Object::Null,
                Object::Dictionary(dictionary! { "Predictor" => 12, "Columns" => 4 }),
            ],
        };
        assert!(
            wants_predictor(&doc, &d),
            "the array form pairs params with the filter chain by index"
        );

        // /DP is the inline-image abbreviation for the same key.
        let d = dictionary! {
            "F" => "Fl",
            "DP" => dictionary! { "Predictor" => 12 },
        };
        assert!(wants_predictor(&doc, &d));
    }

    #[test]
    fn a_predictor_stream_our_chain_cannot_decode_yields_nothing_not_garbage() {
        // Corrupt Flate payload plus a predictor. Our chain fails, and lopdf must not
        // be consulted, so the result must be empty rather than predictor-encoded.
        let doc = Document::new();
        let dict = dictionary! {
            "Filter" => "FlateDecode",
            "DecodeParms" => dictionary! { "Predictor" => 12, "Columns" => 4 },
        };
        let out = decode_stream_content(&doc, &dict, &[0xFF, 0xFE, 0xFD, 0xFC]);
        assert!(out.is_empty(), "expected nothing, got {out:?}");
    }
}


// Fonts + ToUnicode
// ---------------------------------------------------------------------------
