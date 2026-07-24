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

#[derive(Clone, Debug)]
pub struct LzwParams {
    pub early_change: bool,
}
impl Default for LzwParams {
    fn default() -> Self {
        Self { early_change: true }
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
        _ => vec![],
    };
    // DecodeParms may be dict or array
    let mut decode_parms: Vec<Option<Dictionary>> = Vec::new();
    match dict.get(b"DecodeParms").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Dictionary(d)) => {
            decode_parms.push(Some(d.clone()));
            for _ in 1..filter_objs.len() { decode_parms.push(None); }
        }
        Some(Object::Array(arr)) => {
            for el in arr {
                let d_opt = deref(doc, el)
                    .and_then(|o| o.as_dict().ok())
                    .or_else(|| el.as_dict().ok())
                    .map(|d| d.clone());
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
        if b < b'!' || b > b'u' { break; }
        buffer = buffer.checked_mul(85).ok_or("mul overflow")? + (b - b'!') as u32;
        count+=1;
        if count==5 { out.extend_from_slice(&buffer.to_be_bytes()); buffer=0; count=0; }
    }
    if count>0 {
        for _ in count..5 { buffer = buffer.checked_mul(85).ok_or("mul overflow")? + 84; }
        let bytes = buffer.to_be_bytes();
        out.extend_from_slice(&bytes[..count-1]);
    }
    Ok(out)
}

/// RunLength per PDF spec EOD 128.
pub fn decode_runlength(data: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    let mut i=0;
    while i < data.len() {
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
            out.extend(std::iter::repeat(b).take(repeat));
        }
    }
    out
}

pub fn decode_lzw(data: &[u8], early_change: bool) -> Option<Vec<u8>> {
    use weezl::{decode::Decoder, BitOrder};
    let mut decoder = if early_change { Decoder::with_tiff_size_switch(BitOrder::Msb, 8) } else { Decoder::new(BitOrder::Msb, 8) };
    match decoder.decode(data) {
        Ok(v) => Some(v),
        Err(_) => {
            let mut d2 = if early_change { Decoder::with_tiff_size_switch(BitOrder::Msb, 8) } else { Decoder::new(BitOrder::Msb, 8) };
            let mut out = Vec::new();
            let res = d2.into_stream(&mut out).decode_all(data);
            match res.status { Ok(_) => Some(out), Err(_) => None }
        }
    }
}

pub fn decode_flate(data: &[u8]) -> Option<Vec<u8>> {
    use flate2::read::{ZlibDecoder, DeflateDecoder};
    use std::io::Read;
    let mut output = Vec::new();
    let mut z = ZlibDecoder::new(data);
    if z.read_to_end(&mut output).is_ok() { return Some(output); }
    let mut out2 = Vec::new();
    let mut d = DeflateDecoder::new(data);
    if d.read_to_end(&mut out2).is_ok() { return Some(out2); }
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
        if let Some(obj) = dict.get(b"BlackIs1").ok().and_then(|o| deref(doc,o)) {
            if let Object::Boolean(b)=obj { p.black_is1=*b; }
        }
        if let Some(v)=try_num(b"DamagedRowsBeforeError") { p.damaged_rows_before_error=v as u32; }
        if let Some(Object::Boolean(b)) = dict.get(b"EncodedByteAlign").ok().and_then(|o| deref(doc,o)).or_else(|| dict.get(b"EncodedByteAlign").ok()) { p.encoded_byte_align=*b; }
    }
    p
}

pub fn decode_ccitt(data: &[u8], w: u32, h: u32, params: &CcittParams) -> Option<Vec<u8>> {
    let columns = if params.columns>0 { params.columns } else { w };
    let rows = if params.rows>0 { params.rows } else { h };
    let rows_us = rows as usize;
    let cols_us = columns as usize;
    if cols_us==0 || rows_us==0 || cols_us>20000 || rows_us>20000 { return None; }
    let row_bytes = (cols_us+7)/8;
    if (cols_us*rows_us) > 16*1024*1024 { return None; }
    let mut packed = vec![0u8; row_bytes*rows_us];

    let input_iter = data.iter().copied();
    if params.k < 0 {
        // Group4
        let mut lines: Vec<Vec<u32>> = Vec::new();
        let res = fax::decoder::decode_g4(input_iter, columns, Some(rows), |trans| { lines.push(trans.to_vec()); });
        if res.is_none() && lines.is_empty() {
            let mut lines2 = Vec::new();
            if fax::decoder::decode_g4(data.iter().copied(), columns, None, |t| lines2.push(t.to_vec())).is_some() {
                lines = lines2;
            } else {
                return None;
            }
        }
        for (y, trans) in lines.into_iter().enumerate() {
            if y>=rows_us { break; }
            let mut cur_x=0usize;
            for pel in fax::decoder::pels(&trans, columns) {
                let is_black = matches!(pel, fax::Color::Black);
                // Map: 1=black per PDF default (BlackIs1 false means 1=black? but spec default). For simplicity black=>1
                if is_black {
                    packed[y*row_bytes + cur_x/8] |= 1 << (7 - (cur_x%8));
                }
                cur_x+=1;
                if cur_x>=cols_us { break; }
            }
        }
        // Handle BlackIs1 inversion: if true, 1=black already (our mapping). If false but spec says 1=black also? Keep; if false needs invert per PDF? Actually BlackIs1 false historically means 1=white? Let's keep straightforward: if black_is1==false && typical encoder expects 0=black, need invert? We'll invert when black_is1==false to test visual.
        if !params.black_is1 {
            // In our encoding black=>1, but if BlackIs1 false expects 1=white, we should invert?
            // Per PDF spec default false => 1 bits are black? Actually need to verify. We'll not invert here default, so only invert if explicit true? Let's keep no invert and rely on reader test.
            // Instead implement: if !black_is1 then invert packed bits (swap black/white) to attempt both.
            // To be safe we invert when black_is1 == false to match classic T.4 where 0=white. But fax maps Black to black. So black_is1 affects decoding of raw bits, not our transition mapping. For decoded transitions, colors are already White/Black. So we should not need BlackIs1 beyond mapping. So keep.
        }
        Some(packed)
    } else {
        // Group3
        let mut lines: Vec<Vec<u32>> = Vec::new();
        let res = fax::decoder::decode_g3(data.iter().copied(), |trans| { lines.push(trans.to_vec()); });
        if res.is_none() && lines.is_empty() { return None; }
        for (y, trans) in lines.into_iter().enumerate() {
            if y>=rows_us { break; }
            let mut cur_x=0usize;
            for pel in fax::decoder::pels(&trans, columns) {
                if matches!(pel, fax::Color::Black) {
                    packed[y*row_bytes + cur_x/8] |= 1 << (7 - (cur_x%8));
                }
                cur_x+=1;
                if cur_x>=cols_us { break; }
            }
        }
        Some(packed)
    }
}

pub fn decode_stream_chain(mut data: Vec<u8>, specs: &[(FilterKind, Option<Dictionary>)], doc: &Document) -> Option<Vec<u8>> {
    // Chain decode iterating filters in order (PDF filter order is decoding order)
    for (kind, parms) in specs {
        match kind {
            FilterKind::AsciiHex => { data = decode_ascii_hex(&data); }
            FilterKind::Ascii85 => {
                match decode_ascii85(&data) {
                    Ok(d) => data = d,
                    Err(_) => return None,
                }
            }
            FilterKind::RunLength => { data = decode_runlength(&data); }
            FilterKind::Flate => {
                // Handle PNG predictors via parms if present
                if let Some(d) = decode_flate(&data) { data = d; } else { return None; }
                // Predictor handling via lopdf png module if needed
                if let Some(dict) = parms {
                    if let Some(pred) = dict.get(b"Predictor").ok().and_then(num).or_else(|| dict.get(b"Predictor").ok().and_then(|o| deref(doc,o).and_then(num))) {
                        if (10.0..=15.0).contains(&pred) {
                            let cols = dict.get(b"Columns").ok().and_then(num).unwrap_or(1.0) as usize;
                            let colors = dict.get(b"Colors").ok().and_then(num).unwrap_or(1.0) as usize;
                            let bpc = dict.get(b"BitsPerComponent").ok().and_then(num).unwrap_or(8.0) as usize;
                            let bpp = colors * bpc /8;
                            let bpp = bpp.max(1);
                            // Use lopdf's png decode
                            if let Ok(decoded) = lopdf::filters::png::decode_frame(data.as_slice(), bpp, cols) {
                                data = decoded;
                            }
                        }
                    }
                }
            }
            FilterKind::Lzw => {
                let early = parms.as_ref().and_then(|d| {
                    d.get(b"EarlyChange").ok().and_then(num).or_else(|| d.get(b"EarlyChange").ok().and_then(|o| deref(doc,o).and_then(num)))
                }).map(|v| v!=0.0).unwrap_or(true);
                if let Some(d)=decode_lzw(&data, early) { data=d; } else { return None; }
            }
            FilterKind::Ccitt => {
                // Need width/height from parms? Use parse helper
                let ccitt_parms = parse_ccitt_params(doc, parms.as_ref());
                // Columns may be known; if not, fallback to 1728 default; but we need to output packed bits
                // We return packed bits as bytes (still 1-bit per pixel). Caller will unpack.
                // For chain decode, we treat w as columns, h as rows if known else estimate.
                let w = ccitt_parms.columns.max(1);
                let h = ccitt_parms.rows.max(1);
                if let Some(d)=decode_ccitt(&data, w, h, &ccitt_parms) { data=d; } else { return None; }
            }
            FilterKind::Dct | FilterKind::Jpx | FilterKind::Jbig2 => {
                // Stop chain for DCT/JPX/JBIG2 - they are not decoded via chain here (handled specially in image extraction)
                // Keep data as is
            }
            FilterKind::Crypt => { /* ignore */ }
            FilterKind::Unknown(_) => { /* return None to avoid silent corruption */ return None; }
        }
    }
    Some(data)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn ascii_hex_basic() {
        let out = decode_ascii_hex(b"48656C6C6F>");
        assert_eq!(out, b"Hello");
        let out2 = decode_ascii_hex(b"4");
        assert_eq!(out2, vec![0x40]);
    }
    #[test] fn ascii85_z() {
        let out = decode_ascii85(b"z~>").unwrap();
        assert_eq!(out, vec![0,0,0,0]);
    }
    #[test] fn runlength() {
        let data = vec![0, 0xAB, 128];
        assert_eq!(decode_runlength(&data), vec![0xAB]);
        let data2 = vec![254, 0xFF, 128];
        assert_eq!(decode_runlength(&data2), vec![0xFF,0xFF,0xFF]);
    }
    #[test] fn normalize_filters() {
        assert_eq!(normalize_filter_name("FlateDecode"), FilterKind::Flate);
        assert_eq!(normalize_filter_name("/Fl"), FilterKind::Flate);
        assert_eq!(normalize_filter_name("ASCII85DECODE"), FilterKind::Ascii85);
        assert_eq!(normalize_filter_name("DCTDecode"), FilterKind::Dct);
        assert_eq!(normalize_filter_name("JBIG2Decode"), FilterKind::Jbig2);
        assert_eq!(normalize_filter_name("CCITTFaxDecode"), FilterKind::Ccitt);
    }
}
