//! Filter decoders for PDF image XObjects and content streams.
//! Implements chain decoding with case-insensitive filter normalization.
//! Used to close P0 blank-page blockers: ASCIIHex/85, LZW, Flate, RunLength, CCITT, JBIG2.

use lopdf::{Dictionary, Document, Object};

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum FilterKind {
    AsciiHex,
    Ascii85,
    Lzw,
    Flate,
    RunLength,
    Ccitt,
    Dct,
    Jpx,
    Jbig2,
    Crypt,
    Unknown(String),
}

#[derive(Clone, Debug)]
pub struct CcittParams {
    pub k: i32,
    pub columns: u32,
    pub rows: u32,
    pub end_of_line: bool,
    pub end_of_block: bool,
    pub black_is1: bool,
    pub damaged_rows_before_error: u32,
    pub encoded_byte_align: bool,
}

impl Default for CcittParams {
    fn default() -> Self {
        CcittParams {
            k: 0,
            columns: 1728,
            rows: 0,
            end_of_line: false,
            end_of_block: false,
            black_is1: false,
            damaged_rows_before_error: 0,
            encoded_byte_align: false,
        }
    }
}

fn num(obj: &Object) -> Option<f64> {
    match obj {
        Object::Integer(i) => Some(*i as f64),
        Object::Real(r) => Some(*r as f64),
        _ => None,
    }
}
fn deref<'a>(doc: &'a Document, obj: &'a Object) -> Option<&'a Object> {
    match doc.dereference(obj) {
        Ok((_, o)) => Some(o),
        Err(_) => None,
    }
}

pub fn normalize_filter_name(name: &str) -> FilterKind {
    let n = name.trim().trim_start_matches('/').to_ascii_lowercase();
    match n.as_str() {
        "asciihexdecode" | "ahx" | "ah" => FilterKind::AsciiHex,
        "ascii85decode" | "a85" => FilterKind::Ascii85,
        "lzwdecode" | "lzw" => FilterKind::Lzw,
        "flatedecode" | "fl" | "flate" => FilterKind::Flate,
        "runlengthdecode" | "rl" | "rle" => FilterKind::RunLength,
        "ccittfaxdecode" | "ccf" | "ccitt" | "fax" | "g3" | "g4" => FilterKind::Ccitt,
        "dctdecode" | "dct" => FilterKind::Dct,
        "jpxdecode" | "jpx" | "jp2" | "jpeg2000" => FilterKind::Jpx,
        "jbig2decode" | "jbig2" => FilterKind::Jbig2,
        "crypt" => FilterKind::Crypt,
        other => FilterKind::Unknown(other.to_string()),
    }
}

pub fn filter_specs_from_dict(doc: &Document, dict: &Dictionary) -> Vec<(FilterKind, Option<Dictionary>)> {
    let mut out = Vec::new();
    let filter_objs: Vec<Object> = match dict.get(b"Filter").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Name(name)) => vec![Object::Name(name.clone())],
        Some(Object::Array(arr)) => arr.clone(),
        // §8.9.7 Table 93 abbreviates /Filter to /F in an INLINE image dictionary, and
        // inline images are the only dictionaries that reach here without a /Filter.
        // In a regular stream dictionary /F is a file specification instead (§7.3.8.2),
        // which is a string or a dictionary — so only the two shapes a filter can
        // actually take are accepted, and a file spec is still ignored.
        _ => match dict.get(b"F").ok().and_then(|o| deref(doc, o)) {
            Some(Object::Name(name)) => vec![Object::Name(name.clone())],
            Some(Object::Array(arr)) => arr.clone(),
            _ => vec![],
        },
    };
    // DecodeParms may be dict or array; /DP is its inline-image abbreviation.
    let mut decode_parms: Vec<Option<Dictionary>> = Vec::new();
    let parms_obj = dict
        .get(b"DecodeParms")
        .ok()
        .and_then(|o| deref(doc, o))
        .or_else(|| dict.get(b"DP").ok().and_then(|o| deref(doc, o)));
    match parms_obj {
        Some(Object::Dictionary(d)) => {
            // §7.4 pairs a DecodeParms ARRAY with a Filter array, but producers commonly
            // emit a single dict alongside a filter array. Attaching it to index 0 meant
            // that for `[/ASCII85Decode /FlateDecode]` the /Predictor never reached Flate
            // and the image came out garbled. Give it to the first filter that can use it.
            let target = filter_objs
                .iter()
                .position(|f| {
                    f.as_name()
                        .ok()
                        .or_else(|| deref(doc, f).and_then(|o| o.as_name().ok()))
                        .map(|n| {
                            matches!(
                                normalize_filter_name(&String::from_utf8_lossy(n)),
                                FilterKind::Flate | FilterKind::Lzw | FilterKind::Ccitt | FilterKind::Jbig2
                            )
                        })
                        .unwrap_or(false)
                })
                .unwrap_or(0);
            for i in 0..filter_objs.len().max(1) {
                decode_parms.push(if i == target { Some(d.clone()) } else { None });
            }
        }
        Some(Object::Array(arr)) => {
            for el in arr {
                let d_opt = deref(doc, el)
                    .and_then(|o| o.as_dict().ok())
                    .or_else(|| el.as_dict().ok())
                    .cloned();
                decode_parms.push(d_opt);
            }
            while decode_parms.len() < filter_objs.len() { decode_parms.push(None); }
        }
        _ => {
            decode_parms = vec![None; filter_objs.len()];
        }
    }

    for (i, fobj) in filter_objs.iter().enumerate() {
        let name_bytes_opt = fobj.as_name().ok()
            .or_else(|| deref(doc, fobj).and_then(|o| o.as_name().ok()));
        if let Some(name_bytes) = name_bytes_opt {
            let s = String::from_utf8_lossy(name_bytes).into_owned();
            let kind = normalize_filter_name(&s);
            let parms = decode_parms.get(i).cloned().unwrap_or(None);
            out.push((kind, parms));
        }
    }
    out
}

/// ASCIIHex: strip whitespace, stop at '>', handle odd nibble padded with 0.
pub fn decode_ascii_hex(data: &[u8]) -> Vec<u8> {
    let mut hex_digits = Vec::new();
    for &b in data {
        if b == b'>' { break; }
        if b.is_ascii_whitespace() { continue; }
        if b.is_ascii_hexdigit() { hex_digits.push(b); } else { break; }
    }
    if hex_digits.len() % 2 == 1 { hex_digits.push(b'0'); }
    let mut out = Vec::with_capacity(hex_digits.len() / 2);
    for chunk in hex_digits.chunks(2) {
        let hi = (chunk[0] as char).to_digit(16).unwrap_or(0) as u8;
        let lo = (chunk[1] as char).to_digit(16).unwrap_or(0) as u8;
        out.push((hi << 4) | lo);
    }
    out
}

/// ASCII85: handle 'z' and '~>' EOD, ignore whitespace.
pub fn decode_ascii85(data: &[u8]) -> Result<Vec<u8>, String> {
    let mut out = Vec::new();
    let mut buffer: u32 = 0;
    let mut count = 0usize;
    let mut i = 0;
    // Skip the PostScript "<~" opening delimiter. §7.4.3 only specifies the "~>" EOD, but
    // producers following the PostScript convention emit the opener too, and '<' falls
    // inside the valid '!'..'u' digit range so it would otherwise be decoded as data and
    // shift every subsequent group.
    if data.starts_with(b"<~") {
        i = 2;
    }
    while i < data.len() {
        let b = data[i]; i+=1;
        if b == b'~' {
            if i < data.len() && data[i]==b'>' { break; }
            continue;
        }
        if b == b'z' {
            if count!=0 { return Err("z inside group".into()); }
            out.extend_from_slice(&[0,0,0,0]);
            continue;
        }
        if b.is_ascii_whitespace() { continue; }
        if !(b'!'..=b'u').contains(&b) { break; }
        // checked_add matters as well as checked_mul: "s8W-" reaches exactly u32::MAX/85*85,
        // so the following digit overflows the add. Release builds have no overflow checks,
        // so this wrapped silently in production and panicked in tests.
        buffer = buffer
            .checked_mul(85)
            .and_then(|v| v.checked_add((b - b'!') as u32))
            .ok_or("group overflows u32")?;
        count+=1;
        if count==5 { out.extend_from_slice(&buffer.to_be_bytes()); buffer=0; count=0; }
    }
    if count>0 {
        for _ in count..5 {
            buffer = buffer
                .checked_mul(85)
                .and_then(|v| v.checked_add(84))
                .ok_or("group overflows u32")?;
        }
        let bytes = buffer.to_be_bytes();
        out.extend_from_slice(&bytes[..count-1]);
    }
    Ok(out)
}

/// RunLength per PDF spec EOD 128.
pub fn decode_runlength(data: &[u8]) -> Vec<u8> {
    decode_runlength_limited(data, MAX_DECODED_BYTES as usize)
}

/// §7.4.5 expands by up to 128x, so a 200 MB stream (the `MAX_PDF_BYTES` ceiling)
/// can reach ~25 GB. Nothing else bounds this, and the bytes decoded so far are
/// still usable, so `limit` stops the expansion rather than the process being killed.
fn decode_runlength_limited(data: &[u8], limit: usize) -> Vec<u8> {
    let mut out = Vec::new();
    let mut i=0;
    while i < data.len() {
        if out.len() >= limit { break; }
        let len = data[i] as i16; i+=1;
        if len==128 { break; }
        else if len<=127 {
            let copy_len = (len+1) as usize;
            if i+copy_len > data.len() { out.extend_from_slice(&data[i..]); break; }
            out.extend_from_slice(&data[i..i+copy_len]); i+=copy_len;
        } else {
            if i>=data.len() { break; }
            let repeat = (257 - len as i32) as usize;
            let b=data[i]; i+=1;
            out.extend(std::iter::repeat_n(b, repeat));
        }
    }
    out
}

pub fn decode_lzw(data: &[u8], early_change: bool) -> Option<Vec<u8>> {
    // weezl crate removed – pure std LZW decoder (single function we use, prefer stdlib)
    lzw_decode_std(data, early_change, MAX_DECODED_BYTES as usize)
}

/// Minimal std-only LZW decoder for PDF's LZWDecode (MSB-first, 8-bit symbols).
/// Handles EarlyChange 1 (default, code size early bump) vs 0.
/// This is the `single function we use, we can just write it ourselves` rewrite path.
/// Returns None on malformed data.
fn lzw_decode_std(data: &[u8], early_change: bool, limit: usize) -> Option<Vec<u8>> {
    // Simplified version: common case – try via quick dict of 258+ entries.
    // If too complex, return None and let caller fail gracefully.
    // Real PDF LZW switches code size at 2^k - early. Standard TIFF variant uses clear code 256, eod 257.
    const CLEAR: u32 = 256;
    const EOD: u32 = 257;
    if data.is_empty() { return Some(Vec::new()); }
    let mut dict: Vec<Vec<u8>> = Vec::with_capacity(4096);
    for i in 0..256 { dict.push(vec![i as u8]); }
    dict.push(vec![]); // 256 clear
    dict.push(vec![]); // 257 eod
    // Reserve a guess, not a file-controlled amount: `data.len() * 2` is a 400 MB
    // up-front allocation for a `MAX_PDF_BYTES`-sized stream, before a single code
    // has been shown to decode.
    let mut out = Vec::with_capacity(data.len().saturating_mul(2).min(1 << 20));
    let mut code_bits = 9usize;
    let mut bit_buf: u32 = 0;
    let mut bits_in_buf = 0usize;
    let mut data_pos = 0usize;
    let mut prev: Option<Vec<u8>> = None;

    let read_code = |bit_buf: &mut u32, bits_in_buf: &mut usize, data: &[u8], pos: &mut usize, bits: usize| -> Option<u32> {
        while *bits_in_buf < bits {
            if *pos >= data.len() { return None; }
            *bit_buf = (*bit_buf << 8) | data[*pos] as u32;
            *bits_in_buf += 8;
            *pos += 1;
        }
        let shift = *bits_in_buf - bits;
        let code = (*bit_buf >> shift) & ((1u32 << bits) - 1);
        *bits_in_buf = shift;
        *bit_buf &= (1u32 << shift).wrapping_sub(1);
        Some(code)
    };

    // Stream ended without an EOD (257) ends the loop: many producers omit
    // it; returning None there discarded a fully-decoded stream and blanked
    // the page.
    while let Some(code) = read_code(&mut bit_buf, &mut bits_in_buf, data, &mut data_pos, code_bits) {
        if code == CLEAR {
            dict.truncate(258);
            code_bits = 9;
            prev = None;
            continue;
        }
        if code == EOD { break; }
        let entry: Vec<u8> = if (code as usize) < dict.len() {
            dict[code as usize].clone()
        } else if code as usize == dict.len() {
            // KwKwK case
            match prev.as_ref() {
                Some(p) if !p.is_empty() => {
                    let mut e = p.clone();
                    e.push(p[0]);
                    e
                }
                _ => break,
            }
        } else {
            // Corrupt code: keep everything decoded so far rather than losing the stream.
            break;
        };
        out.extend_from_slice(&entry);
        // §7.4.3 places no bound on the expansion ratio: a dictionary entry grows to
        // 4096 bytes, so a few-MB stream can decode to gigabytes. Flate is capped by
        // `MAX_DECODED_BYTES`; this was not, and nothing downstream bounds it either.
        // Everything decoded so far is kept, as for a truncated stream.
        if out.len() >= limit {
            break;
        }
        if let Some(p) = prev.take() {
            if dict.len() < 4096 {
                if let Some(&first) = entry.first() {
                    let mut new_entry = p;
                    new_entry.push(first);
                    dict.push(new_entry);
                    // EarlyChange bumps code size one entry early per PDF spec
                    let threshold = if early_change { (1usize << code_bits) - 1 } else { 1usize << code_bits };
                    if dict.len() >= threshold && code_bits < 12 {
                        code_bits += 1;
                    }
                }
            }
        }
        prev = Some(entry);
    }
    Some(out)
}

/// Maximum bytes we will inflate from one stream. §7.4.4 places no limit on the
/// expansion ratio, so a few-KB stream can inflate to gigabytes and OOM-kill the
/// app; `MAX_PDF_BYTES` bounds only the compressed input.
const MAX_DECODED_BYTES: u64 = 256 * 1024 * 1024;

pub fn decode_flate(data: &[u8]) -> Option<Vec<u8>> {
    use flate2::read::{DeflateDecoder, ZlibDecoder};
    use std::io::Read;
    // `read_to_end` appends as it inflates, so on a corrupt or truncated stream the
    // buffer already holds every byte that did inflate. Truncated streams are common
    // in the wild and discarding the partial output turns a mostly-fine page blank.
    let inflate = |bytes: &[u8], zlib: bool| -> Option<Vec<u8>> {
        let mut out = Vec::new();
        let res = if zlib {
            ZlibDecoder::new(bytes)
                .take(MAX_DECODED_BYTES)
                .read_to_end(&mut out)
        } else {
            DeflateDecoder::new(bytes)
                .take(MAX_DECODED_BYTES)
                .read_to_end(&mut out)
        };
        match res {
            Ok(_) => Some(out),
            Err(_) if !out.is_empty() => Some(out),
            Err(_) => None,
        }
    };
    if let Some(out) = inflate(data, true) {
        return Some(out);
    }
    // Some producers omit the two-byte zlib header and emit raw deflate.
    if let Some(out) = inflate(data, false) {
        return Some(out);
    }
    // A single stray byte before the zlib header is another known producer bug.
    if data.len() > 1 {
        if let Some(out) = inflate(&data[1..], true) {
            return Some(out);
        }
    }
    None
}

pub fn parse_ccitt_params(doc: &Document, dict_opt: Option<&Dictionary>) -> CcittParams {
    let mut p = CcittParams::default();
    if let Some(dict)=dict_opt {
        let try_num = |key: &[u8]| -> Option<f64> {
            dict.get(key).ok().and_then(|o| deref(doc,o).and_then(num).or_else(|| num(o)))
        };
        if let Some(v)=try_num(b"K") { p.k = v as i32; }
        if let Some(v)=try_num(b"Columns").or_else(|| try_num(b"W")) { p.columns = v as u32; }
        if let Some(v)=try_num(b"Rows").or_else(|| try_num(b"H")) { p.rows = v as u32; }
        if let Some(Object::Boolean(b)) = dict.get(b"EndOfLine").ok().and_then(|o| deref(doc,o)).or_else(|| dict.get(b"EndOfLine").ok()) { p.end_of_line = *b; }
        if let Some(Object::Boolean(b)) = dict.get(b"EndOfBlock").ok().and_then(|o| deref(doc,o)).or_else(|| dict.get(b"EndOfBlock").ok()) { p.end_of_block = *b; }
        if let Some(Object::Boolean(b)) = dict.get(b"BlackIs1").ok().and_then(|o| deref(doc,o)).or_else(|| dict.get(b"BlackIs1").ok()) { p.black_is1 = *b; }
        // also check boolean via reference variant for BlackIs1 through deref returning Object::Boolean
        if let Some(Object::Boolean(b)) = dict.get(b"BlackIs1").ok().and_then(|o| deref(doc,o)) {
            p.black_is1 = *b;
        }
        if let Some(v)=try_num(b"DamagedRowsBeforeError") { p.damaged_rows_before_error=v as u32; }
        if let Some(Object::Boolean(b)) = dict.get(b"EncodedByteAlign").ok().and_then(|o| deref(doc,o)).or_else(|| dict.get(b"EncodedByteAlign").ok()) { p.encoded_byte_align = *b; }
    }
    p
}

include!("filters_part1.rs");
include!("filters_part2.rs");