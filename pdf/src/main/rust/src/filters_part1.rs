pub fn decode_ccitt(data: &[u8], w: u32, h: u32, params: &CcittParams) -> Option<Vec<u8>> {
    // P0 fix: honor BlackIs1 (spec §7.4.6: true=>1=black, false default=>1=white inverted), estimate Rows when absent
    let columns = if params.columns > 0 { params.columns } else { w.max(1) };
    // §7.4.6 Table 11: /Rows gives the scan-line count; when it is absent or 0 the
    // height comes from the image dictionary's /Height, which is what `h` is.
    //
    // The data-length estimate below is the LAST resort, used only when neither is
    // available. It is not a spec-sanctioned reading of the row count at all: it
    // equates one row to `columns` BITS of payload, so it only exceeds `h` when the
    // encoded data is LARGER than the raster it encodes. For any normally-compressed
    // fax it comes out well below `h`, which is why deriving from it looks harmless.
    // On a padded, corrupt or mislabelled stream it does not, and past the 20000-row
    // guard below `decode_ccitt` then returns None and the image is dropped ENTIRELY
    // rather than merely mis-sized - total loss where a partial render was available.
    let rows_est = if params.rows > 0 {
        params.rows
    } else if h > 0 {
        h
    } else {
        ((data.len() * 8 / columns.max(1) as usize) as u32).max(1)
    };
    let rows = rows_est;
    let rows_us = rows as usize;
    let cols_us = columns as usize;
    if cols_us == 0
        || rows_us == 0
        || cols_us > crate::MAX_IMAGE_DIM as usize
        || rows_us > crate::MAX_IMAGE_DIM as usize
    {
        return None;
    }
    let row_bytes = cols_us.div_ceil(8);
    // Budget the buffer this function actually allocates, in the unit it actually
    // allocates it in.
    //
    // The test used to be `(cols_us * rows_us) > 16 * 1024 * 1024`, i.e. the value of
    // `MAX_IMAGE_PIXELS` — a budget sized for a FOUR-BYTE-per-pixel RGBA raster —
    // applied to the ONE-BIT raster below. That is 32x too strict for what it guards. A
    // 300 dpi A0 engineering scan is 9933 x 14043 = 139 Mpx, which the cap refuses, but
    // packs to `1242 * 14043` = 17.4 MB, which is entirely holdable. Large architectural
    // and engineering scans are exactly what CCITT carries, and they rendered as nothing
    // at all: `decode_ccitt` returned None and `images.rs` reported a failed decode.
    //
    // `MAX_UNPACKED_SAMPLE_BYTES` is the crate's existing ceiling for a decoded raster
    // buffer at one byte per component. A packed bilevel raster is strictly cheaper per
    // pixel than that, so reusing it keeps one policy number instead of inventing a
    // second. With /Columns and /Rows already bounded to `MAX_IMAGE_DIM` above, the
    // worst case here is 2500 * 20000 = 50 MB and the dimension cap is the binding one.
    //
    // This bounds only THIS buffer. The RGBA that `images.rs` builds from it is 32x
    // larger and is bounded separately, by the decimation at its CCITT branch — the two
    // have to stay paired, because raising this alone would hand that branch a 139 Mpx
    // raster and a 558 MB allocation.
    if row_bytes.saturating_mul(rows_us) > crate::MAX_UNPACKED_SAMPLE_BYTES {
        return None;
    }

    let black_is1 = params.black_is1;
    // Byte value meaning WHITE in the sample polarity this function emits (see
    // `fill_rows`): with the /BlackIs1 default of false a 1 bit is white, with
    // /BlackIs1 true a 0 bit is.
    //
    // Rows the fax decoder never produced — a truncated or corrupt codestream, or a
    // /Rows//Height taller than the data — keep this value. They used to keep 0, which
    // under the DEFAULT parameters is BLACK, so the undecoded tail of a scan rendered
    // as a solid black band across the page (and as alpha 0, i.e. the base image
    // vanishing, when the stream was a soft mask). Neither `decode_ccitt`'s caller nor
    // this function can tell those rows apart from decoded ones after the fact, so the
    // background has to be right here. White is the background of every fax page.
    let white_byte: u8 = if black_is1 { 0x00 } else { 0xFF };
    let mut packed = vec![white_byte; row_bytes * rows_us];
    // §7.4.6 Table 11 /BlackIs1: false (the default) means 0 bits are black, true means
    // 1 bits are black. The filter's job is only to emit samples in that polarity; it is
    // the COLOUR SPACE that then decides what a sample means on the page, so this must
    // not also be folded into the image layer (`images.rs` reads sample 0 as black per
    // §8.9.5.2 and does not re-apply the flag).
    let fill_rows = |lines: Vec<Vec<u32>>, packed: &mut Vec<u8>| {
        for (y, trans) in lines.into_iter().enumerate() {
            if y >= rows_us { break; }
            let row_off = y * row_bytes;
            for (cur_x, pel) in fax::decoder::pels(&trans, columns).enumerate() {
                if cur_x >= cols_us { break; }
                let is_black = matches!(pel, fax::Color::Black);
                // Whether this pel's SAMPLE is a 1 bit, which /BlackIs1 selects.
                let bit_is_one = if black_is1 { is_black } else { !is_black };
                let mask = 1u8 << (7 - (cur_x % 8));
                // Written rather than OR-ed: the row starts at `white_byte`, so a pel
                // the decoder says is white must be able to CLEAR a bit as well as set
                // one. Pels the decoder never reaches keep the white background.
                let b = &mut packed[row_off + cur_x / 8];
                if bit_is_one { *b |= mask; } else { *b &= !mask; }
            }
        }
    };

    if params.k < 0 {
        // Group4
        let mut lines: Vec<Vec<u32>> = Vec::new();
        let res = fax::decoder::decode_g4(data.iter().copied(), columns, Some(rows), |trans| { lines.push(trans.to_vec()); });
        if res.is_none() && lines.is_empty() {
            let mut lines2 = Vec::new();
            if fax::decoder::decode_g4(data.iter().copied(), columns, None, |t| lines2.push(t.to_vec())).is_some() {
                lines = lines2;
            } else {
                return None;
            }
        }
        fill_rows(lines, &mut packed);
        Some(packed)
    } else if params.k > 0 {
        // Mixed 1-D/2-D G3 (K>0): fax crate doesn't expose K switching natively —
        // try G3 decode first (will get 1-D lines for K>0 mixed), then G4 fallback.
        let mut lines: Vec<Vec<u32>> = Vec::new();
        if fax::decoder::decode_g3(data.iter().copied(), |trans| { lines.push(trans.to_vec()); }).is_some() && !lines.is_empty() {
            fill_rows(lines, &mut packed);
            return Some(packed);
        }
        // Fallback to G4 attempt for mixed pages that lean G4
        let mut lines2: Vec<Vec<u32>> = Vec::new();
        if fax::decoder::decode_g4(data.iter().copied(), columns, Some(rows), |t| { lines2.push(t.to_vec()); }).is_some() && !lines2.is_empty() {
            fill_rows(lines2, &mut packed);
            return Some(packed);
        }
        None
    } else {
        // Pure G3
        let mut lines: Vec<Vec<u32>> = Vec::new();
        let res = fax::decoder::decode_g3(data.iter().copied(), |trans| { lines.push(trans.to_vec()); });
        if res.is_none() && lines.is_empty() { return None; }
        fill_rows(lines, &mut packed);
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
                // Handle PNG/TIFF predictors via parms if present
                {
                    let d = decode_flate(&data)?; data = d; }
                data = apply_predictor(data, parms.as_ref(), doc);
            }
            FilterKind::Lzw => {
                let early = parms.as_ref().and_then(|d| {
                    d.get(b"EarlyChange").ok().and_then(num).or_else(|| d.get(b"EarlyChange").ok().and_then(|o| deref(doc,o).and_then(num)))
                }).map(|v| v!=0.0).unwrap_or(true);
                {
                    let d = decode_lzw(&data, early)?; data=d; }
                // LZW supports the same PNG/TIFF predictors as Flate.
                data = apply_predictor(data, parms.as_ref(), doc);
            }
            FilterKind::Ccitt | FilterKind::Dct | FilterKind::Jpx | FilterKind::Jbig2 => {
                // Image codecs are always the LAST filter (§7.4) and are decoded by the
                // image layer, which is the only place that knows the real /Width and
                // /Height. Decoding CCITT here as well made the image layer re-decode the
                // resulting 1-bpc raster as if it were a fresh G3/G4 codestream, and forced
                // this code to guess the row count from `data.len()*8/columns` — which
                // measures COMPRESSED bits and so under-counts rows by the compression
                // ratio. Content streams never use these filters.
            }
            FilterKind::Crypt => {
                // Stream decryption already happens at document load (decrypt.rs),
                // so by here the bytes are plaintext regardless of /Name. A /Crypt
                // filter with /Name /Identity means "not encrypted" — either way a
                // no-op in the decode chain.
            }
            FilterKind::Unknown(_) => { /* return None to avoid silent corruption */ return None; }
        }
    }
    Some(data)
}

/// Apply a PNG (Predictor >= 10) or TIFF (Predictor == 2) predictor to `data`
/// using the `/DecodeParms` values. Returns `data` unchanged if no predictor.
fn apply_predictor(data: Vec<u8>, parms: Option<&Dictionary>, doc: &Document) -> Vec<u8> {
    let dict = match parms {
        Some(d) => d,
        None => return data,
    };
    let pred = dict
        .get(b"Predictor")
        .ok()
        .and_then(num)
        .or_else(|| dict.get(b"Predictor").ok().and_then(|o| deref(doc, o).and_then(num)))
        .unwrap_or(1.0);
    if pred <= 1.0 {
        return data;
    }
    let get = |key: &[u8], default: f64| -> f64 {
        dict.get(key)
            .ok()
            .and_then(num)
            .or_else(|| dict.get(key).ok().and_then(|o| deref(doc, o).and_then(num)))
            .unwrap_or(default)
    };
    // Clamp before any multiplication: `as usize` saturates a huge or negative float to
    // usize::MAX / 0, and `colors * bpc` / `bpp * cols` would then overflow (silently in
    // release, since overflow-checks are off).
    let cols = (get(b"Columns", 1.0).max(0.0) as usize).clamp(1, 1 << 24);
    let colors = (get(b"Colors", 1.0).max(0.0) as usize).clamp(1, 32);
    let bpc = match get(b"BitsPerComponent", 8.0) as i64 {
        1 => 1,
        2 => 2,
        4 => 4,
        16 => 16,
        _ => 8,
    };
    if (10.0..=15.0).contains(&pred) {
        apply_png_predictor(data, cols, colors, bpc)
    } else if pred == 2.0 {
        apply_tiff_predictor2(data, cols, colors, bpc)
    } else {
        data
    }
}

/// Undo a PNG predictor (§7.4.4.4). Each row is prefixed with a filter-type byte.
///
/// `lopdf::filters::png::decode_frame` cannot be used: it derives the row length as
/// `bytes_per_pixel * pixels_per_row`, but the spec requires
/// `ceil(Columns * Colors * BitsPerComponent / 8)`. Those agree only when
/// `BitsPerComponent` is 8 or 16, so every bilevel and 2/4-bit image got an over-long
/// row, `read_exact` failed, and the caller returned the data with the per-row filter
/// bytes still embedded. It also aborted the whole frame on a short final row.
///
/// The per-row arithmetic itself (Sub/Up/Avg/Paeth in wrapping u8 math) is correct in
/// lopdf, so `decode_row` is reused.
fn apply_png_predictor(data: Vec<u8>, cols: usize, colors: usize, bpc: usize) -> Vec<u8> {
    use lopdf::filters::png::{decode_row, FilterType};
    // Byte offset of the "left" sample. PNG defines this as ceil(bits per pixel / 8),
    // minimum 1, so sub-byte pixel depths filter on adjacent bytes.
    let bpp = (colors * bpc).div_ceil(8).max(1);
    // The FACTORS are clamped by the caller, but their PRODUCT is not bounded by
    // anything the file has to back up: at the clamp ceilings,
    // (1<<24 * 32 * 16).div_ceil(8) is a 1 GiB row, and the two row buffers below
    // plus `out` then allocate ~3 GiB from a 512-byte stream. `MAX_DECODED_BYTES`
    // cannot help - this happens after decompression.
    //
    // 7.4.4.4 gives the row length as ceil(Columns x Colors x BitsPerComponent / 8),
    // so the honest bound is the decoded stream itself: a row that does not fit in
    // the data cannot be a row the data describes. Truncated final rows are still
    // zero-filled below, so this only bites when not even one row is present - a
    // case whose output is unusable regardless.
    let row_bytes = cols
        .saturating_mul(colors)
        .saturating_mul(bpc)
        .div_ceil(8)
        .min(data.len());
    if row_bytes == 0 {
        return data;
    }
    let mut out = Vec::with_capacity(data.len());
    // §7.4.4.4: the row above the first is treated as all zeros.
    let mut prev = vec![0u8; row_bytes];
    let mut row = vec![0u8; row_bytes];
    let mut pos = 0usize;
    while pos < data.len() {
        let Ok(filter) = FilterType::try_from(data[pos]) else {
            // Not a predictor byte: stop and keep the rows decoded so far.
            break;
        };
        pos += 1;
        let end = (pos + row_bytes).min(data.len());
        let got = end - pos;
        row[..got].copy_from_slice(&data[pos..end]);
        // Zero-fill a truncated final row instead of discarding the entire frame.
        row[got..].fill(0);
        pos = end;
        decode_row(filter, bpp, &prev, &mut row);
        out.extend_from_slice(&row);
        std::mem::swap(&mut prev, &mut row);
    }
    out
}

/// TIFF Predictor 2: horizontal differencing. Reverses the left-difference for
/// 1/2/4/8/16-bit samples. Rows are byte-aligned; sub-byte samples are packed
/// big-endian within each byte and 16-bit samples are big-endian.
fn apply_tiff_predictor2(mut data: Vec<u8>, cols: usize, colors: usize, bpc: usize) -> Vec<u8> {
    if cols == 0 || colors == 0 {
        return data;
    }
    // Bounded by the decoded stream for the same reason as the PNG path above: the
    // declared factors are attacker-controlled and their product is not. `rows`
    // already came out as 0 for an over-long row on a 64-bit target, but the
    // multiplications themselves wrap (unchecked in release), and the per-row
    // `samples` buffer in the 1/2/4-bit arm below is sized from `samples_per_row`.
    let row_bytes = cols
        .saturating_mul(colors)
        .saturating_mul(bpc)
        .div_ceil(8)
        .min(data.len());
    if row_bytes == 0 {
        return data;
    }
    // A row cannot hold more samples than it has bits for; equal to
    // `cols * colors` whenever the two agree, and smaller only when the clamp above
    // took effect.
    let samples_per_row = cols.saturating_mul(colors).min(row_bytes * 8 / bpc);
    let rows = data.len() / row_bytes;
    match bpc {
        8 => {
            for r in 0..rows {
                let base = r * row_bytes;
                for i in colors..samples_per_row {
                    let prev = data[base + i - colors];
                    data[base + i] = data[base + i].wrapping_add(prev);
                }
            }
        }
        16 => {
            for r in 0..rows {
                let base = r * row_bytes;
                for i in colors..samples_per_row {
                    let p = base + (i - colors) * 2;
                    let c = base + i * 2;
                    let prev = ((data[p] as u16) << 8) | data[p + 1] as u16;
                    let cur = ((data[c] as u16) << 8) | data[c + 1] as u16;
                    let val = cur.wrapping_add(prev);
                    data[c] = (val >> 8) as u8;
                    data[c + 1] = (val & 0xFF) as u8;
                }
            }
        }
        1 | 2 | 4 => {
            let mask = ((1u32 << bpc) - 1) as u16;
            for r in 0..rows {
                let base = r * row_bytes;
                // Unpack samples (MSB-first) for this row.
                let mut samples = vec![0u16; samples_per_row];
                for (s, slot) in samples.iter_mut().enumerate() {
                    let bit = s * bpc;
                    let byte = base + bit / 8;
                    let shift = 8 - bpc - (bit % 8);
                    *slot = ((data[byte] as u16) >> shift) & mask;
                }
                for i in colors..samples_per_row {
                    samples[i] = samples[i].wrapping_add(samples[i - colors]) & mask;
                }
                for (s, val) in samples.iter().enumerate() {
                    let bit = s * bpc;
                    let byte = base + bit / 8;
                    let shift = 8 - bpc - (bit % 8);
                    data[byte] = (data[byte] & !((mask as u8) << shift)) | ((*val as u8) << shift);
                }
            }
        }
        _ => {}
    }
    data
}
