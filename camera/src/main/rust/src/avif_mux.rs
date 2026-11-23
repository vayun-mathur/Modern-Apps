//! Post-encode EXIF mux for already-encoded AVIF files (see
//! [`mux_avif_exif`]): splices a TIFF Exif item into a bare AVIF, e.g.
//! one produced by a hardware `image/avif` MediaCodec encoder.

use crate::avif::looks_like_exif;

/// One parsed ISOBMFF box: type plus absolute payload span in the file.
struct BoxSpan {
    typ: [u8; 4],
    start: usize,
    payload: std::ops::Range<usize>,
}

fn parse_boxes(bytes: &[u8], span: std::ops::Range<usize>) -> Vec<BoxSpan> {
    let mut out = Vec::new();
    let mut off = span.start;
    while off + 8 <= span.end && off + 8 <= bytes.len() {
        let size32 = u32::from_be_bytes([bytes[off], bytes[off + 1], bytes[off + 2], bytes[off + 3]]);
        let (size, header) = if size32 == 1 && off + 16 <= bytes.len() {
            (
                u64::from_be_bytes(bytes[off + 8..off + 16].try_into().unwrap()) as usize,
                16usize,
            )
        } else if size32 == 0 {
            (span.end.saturating_sub(off), 8usize)
        } else {
            (size32 as usize, 8usize)
        };
        if size < header || off + size > bytes.len() {
            break;
        }
        let mut typ: [u8; 4] = bytes[off + 4..off + 8].try_into().unwrap();
        let payload_start = if typ == *b"uuid" { off + header + 16 } else { off + header };
        if typ == *b"uuid" {
            typ = *b"uuid";
        }
        out.push(BoxSpan { typ, start: off, payload: payload_start..off + size });
        off += size;
    }
    out
}

fn read_uint(bytes: &[u8], pos: &mut usize, size: usize) -> Option<u64> {
    let mut value = 0u64;
    for _ in 0..size {
        value = (value << 8) | *bytes.get(*pos)? as u64;
        *pos += 1;
    }
    Some(value)
}

/// Serialises one ISOBMFF box with a 32-bit size header.
fn box_bytes(typ: &[u8; 4], payload: &[u8]) -> Option<Vec<u8>> {
    let size = u32::try_from(payload.len().checked_add(8)?).ok()?;
    let mut out = Vec::with_capacity(size as usize);
    out.extend_from_slice(&size.to_be_bytes());
    out.extend_from_slice(typ);
    out.extend_from_slice(payload);
    Some(out)
}

/// Inserts [exif] (a TIFF payload) into an already-encoded AVIF file as
/// its Exif item, returning the rewritten file. This is the post-mux a
/// hardware `image/avif` MediaCodec encoder needs: such encoders emit a
/// bare AVIF with no way to attach EXIF at configure time, so the item
/// (`iinf`/`iloc`/`iref` entries plus the framed item data in a trailing
/// `mdat`) is spliced in here. Returns `None` for anything this splicer
/// does not understand — a file that already has an Exif item, no
/// primary item (`pitm`), an `iloc` using idat construction, oversized
/// boxes — so the caller falls back to the software encoder rather than
/// shipping a malformed or EXIF-less file.
pub fn mux_avif_exif(avif: &[u8], exif: &[u8]) -> Option<Vec<u8>> {
    if avif.len() < 12 || &avif[4..8] != b"ftyp" || exif.is_empty() || !looks_like_exif(exif) {
        return None;
    }
    let top = parse_boxes(avif, 0..avif.len());
    let meta = top.iter().find(|b| &b.typ == b"meta")?;
    let meta_children_span = (meta.payload.start + 4)..meta.payload.end;
    let children = parse_boxes(avif, meta_children_span.clone());
    let raw_child = |b: &BoxSpan| avif[b.start..b.payload.end].to_vec();

    // Primary item id (for the cdsc reference) from `pitm`.
    let pitm = children.iter().find(|b| &b.typ == b"pitm")?;
    let pitm_version = *avif.get(pitm.payload.start)?;
    let primary_id: u32 = if pitm_version == 1 {
        u32::from_be_bytes(avif.get(pitm.payload.start + 4..pitm.payload.start + 8)?.try_into().ok()?)
    } else {
        u16::from_be_bytes(avif.get(pitm.payload.start + 4..pitm.payload.start + 6)?.try_into().ok()?) as u32
    };

    // `iinf`: bump the entry count and append an Exif `infe` (v2).
    let iinf = children.iter().find(|b| &b.typ == b"iinf")?;
    let iinf_version = *avif.get(iinf.payload.start)?;
    let (count_len, entry_count) = if iinf_version == 1 { (4usize, {
        u32::from_be_bytes(avif.get(iinf.payload.start + 4..iinf.payload.start + 8)?.try_into().ok()?) as usize
    }) } else { (2usize, {
        u16::from_be_bytes(avif.get(iinf.payload.start + 4..iinf.payload.start + 6)?.try_into().ok()?) as usize
    }) };
    let infes = parse_boxes(avif, (iinf.payload.start + 4 + count_len)..iinf.payload.end);
    let mut max_id: u32 = primary_id;
    for infe in &infes {
        let p = infe.payload.start + 4;
        let id = u16::from_be_bytes(avif.get(p..p + 2)?.try_into().ok()?) as u32;
        max_id = max_id.max(id);
        if avif.get(p + 4..p + 8)? == b"Exif" {
            return None; // already carries an Exif item
        }
    }
    let _ = entry_count;
    let new_id = max_id.checked_add(1)?;
    if new_id > u16::MAX as u32 {
        return None;
    }
    let mut infe_payload = Vec::new();
    infe_payload.extend_from_slice(&2u32.to_be_bytes()); // version 2, flags 0
    infe_payload.extend_from_slice(&(new_id as u16).to_be_bytes());
    infe_payload.extend_from_slice(&0u16.to_be_bytes()); // protection index
    infe_payload.extend_from_slice(b"Exif");
    infe_payload.push(0); // empty item name
    let infe_box = box_bytes(b"infe", &infe_payload)?;
    let mut iinf_payload = Vec::new();
    iinf_payload.extend_from_slice(avif.get(iinf.payload.start..iinf.payload.start + 4)?);
    if iinf_version == 1 {
        iinf_payload.extend_from_slice(&((entry_count + 1) as u32).to_be_bytes());
    } else {
        iinf_payload.extend_from_slice(&((entry_count + 1) as u16).to_be_bytes());
    }
    for infe in &infes {
        iinf_payload.extend_from_slice(&raw_child(infe));
    }
    iinf_payload.extend_from_slice(&infe_box);
    let iinf_box = box_bytes(b"iinf", &iinf_payload)?;

    // `iref`: add (or extend) with a `cdsc` reference Exif -> primary.
    let make_ref_box = |id_bytes: usize| -> Option<Vec<u8>> {
        let mut payload = Vec::new();
        payload.extend_from_slice(&0u32.to_be_bytes()); // version/flags
        if id_bytes == 4 {
            payload.extend_from_slice(&new_id.to_be_bytes());
            payload.extend_from_slice(&1u16.to_be_bytes());
            payload.extend_from_slice(&primary_id.to_be_bytes());
        } else {
            payload.extend_from_slice(&(new_id as u16).to_be_bytes());
            payload.extend_from_slice(&1u16.to_be_bytes());
            payload.extend_from_slice(&(primary_id as u16).to_be_bytes());
        }
        box_bytes(b"cdsc", &payload)
    };
    let (iref_box, iref_is_new) = match children.iter().find(|b| &b.typ == b"iref") {
        Some(iref) => {
            let version = *avif.get(iref.payload.start)?;
            let id_bytes = if version == 1 { 4 } else { 2 };
            let mut payload = avif.get(iref.payload.start..iref.payload.end)?.to_vec();
            payload.extend_from_slice(&make_ref_box(id_bytes)?);
            (box_bytes(b"iref", &payload)?, false)
        }
        None => {
            let mut payload = Vec::new();
            payload.extend_from_slice(&0u32.to_be_bytes()); // iref version 0
            payload.extend_from_slice(&make_ref_box(2)?);
            (box_bytes(b"iref", &payload)?, true)
        }
    };

    // Rebuilt meta size decides how far every post-meta extent shifts.
    // `iloc` is rebuilt below at a fixed layout; compute its size first.
    let iloc_old = children.iter().find(|b| &b.typ == b"iloc")?;
    let iloc_items = parse_iloc(avif, iloc_old)?;
    // New iloc: version 1, offset/length 4 bytes, no base offset/index.
    let iloc_payload_len = 8 + (iloc_items.len() + 1) * (2 + 2 + 2 + 2 + 2 * 4);
    let iloc_box_len = iloc_payload_len + 8;

    let mut meta_len = 4usize; // version/flags
    for child in &children {
        meta_len += match &child.typ {
            b"iinf" => iinf_box.len(),
            b"iloc" => iloc_box_len,
            b"iref" => iref_box.len(),
            _ => child.payload.end - child.start,
        };
    }
    if iref_is_new {
        meta_len += iref_box.len();
    }
    let new_meta_total = meta_len + 8;
    let old_meta_total = meta.payload.end - meta.start;
    let delta = new_meta_total as i64 - old_meta_total as i64;

    // Exif item data lives in a trailing mdat: [u32 offset = 0][TIFF].
    let framed_len = 4 + exif.len();
    let mdat_start = (avif.len() as i64 + delta) as usize;
    let exif_extent_offset = mdat_start + 8;

    // Rebuild `iloc` with shifted extents plus the new Exif item.
    let mut iloc_payload = Vec::new();
    iloc_payload.extend_from_slice(&[1u8, 0, 0, 0]); // version 1, flags 0
    iloc_payload.push((4 << 4) | 4); // offset_size=4, length_size=4
    iloc_payload.push(0); // base_offset_size=0, index_size=0
    iloc_payload.extend_from_slice(&((iloc_items.len() + 1) as u16).to_be_bytes());
    let shift = |offset: u64| -> Option<u32> {
        let adjusted = if offset as usize >= meta.payload.end {
            offset as i64 + delta
        } else {
            offset as i64
        };
        u32::try_from(adjusted).ok()
    };
    for item in &iloc_items {
        if item.construction_method != 0 {
            return None; // idat-constructed items are not spliced
        }
        iloc_payload.extend_from_slice(&(item.item_id as u16).to_be_bytes());
        iloc_payload.extend_from_slice(&0u16.to_be_bytes()); // construction: file
        iloc_payload.extend_from_slice(&0u16.to_be_bytes()); // data reference
        iloc_payload.extend_from_slice(&(item.extents.len() as u16).to_be_bytes());
        for &(offset, length) in &item.extents {
            iloc_payload.extend_from_slice(&shift(offset)?.to_be_bytes());
            iloc_payload.extend_from_slice(&(length as u32).to_be_bytes());
        }
    }
    iloc_payload.extend_from_slice(&(new_id as u16).to_be_bytes());
    iloc_payload.extend_from_slice(&0u16.to_be_bytes());
    iloc_payload.extend_from_slice(&0u16.to_be_bytes());
    iloc_payload.extend_from_slice(&1u16.to_be_bytes()); // one extent
    iloc_payload.extend_from_slice(&(exif_extent_offset as u32).to_be_bytes());
    iloc_payload.extend_from_slice(&(framed_len as u32).to_be_bytes());
    let iloc_box = box_bytes(b"iloc", &iloc_payload)?;
    debug_assert_eq!(iloc_box.len(), iloc_box_len);

    // Reassemble: prefix, rebuilt meta, suffix, trailing mdat.
    let mut out = Vec::with_capacity(mdat_start + 8 + framed_len);
    out.extend_from_slice(&avif[..meta.start]);
    let meta_box = {
        let mut payload = Vec::with_capacity(meta_len);
        payload.extend_from_slice(avif.get(meta.payload.start..meta.payload.start + 4)?);
        for child in &children {
            match &child.typ {
                b"iinf" => payload.extend_from_slice(&iinf_box),
                b"iloc" => payload.extend_from_slice(&iloc_box),
                b"iref" => payload.extend_from_slice(&iref_box),
                _ => payload.extend_from_slice(&raw_child(child)),
            }
        }
        if iref_is_new {
            payload.extend_from_slice(&iref_box);
        }
        box_bytes(b"meta", &payload)?
    };
    out.extend_from_slice(&meta_box);
    out.extend_from_slice(&avif[meta.payload.end..]);
    debug_assert_eq!(out.len(), mdat_start);
    let mut mdat_payload = Vec::with_capacity(framed_len);
    mdat_payload.extend_from_slice(&0u32.to_be_bytes());
    mdat_payload.extend_from_slice(exif);
    out.extend_from_slice(&box_bytes(b"mdat", &mdat_payload)?);
    Some(out)
}

/// One `iloc` item before rewriting: id, construction method, extents.
struct IlocItem {
    item_id: u32,
    construction_method: u16,
    extents: Vec<(u64, u64)>,
}

/// Parses the items of an `iloc` box (versions 0-2) into absolute
/// (offset, length) extents. `None` on any layout surprise.
fn parse_iloc(bytes: &[u8], iloc: &BoxSpan) -> Option<Vec<IlocItem>> {
    let mut pos = iloc.payload.start + 4; // version/flags
    let version = *bytes.get(iloc.payload.start)?;
    let sizes = *bytes.get(pos)?;
    pos += 1;
    let offset_size = (sizes >> 4) as usize;
    let length_size = (sizes & 0x0F) as usize;
    let base_sizes = *bytes.get(pos)?;
    pos += 1;
    let base_offset_size = (base_sizes >> 4) as usize;
    let index_size = (base_sizes & 0x0F) as usize;
    let item_count = if version == 2 {
        read_uint(bytes, &mut pos, 4)? as usize
    } else {
        read_uint(bytes, &mut pos, 2)? as usize
    };
    let mut items = Vec::with_capacity(item_count);
    for _ in 0..item_count {
        let item_id = read_uint(bytes, &mut pos, 2)? as u32;
        let construction = if version == 1 || version == 2 {
            read_uint(bytes, &mut pos, 2)? as u16
        } else {
            0
        };
        read_uint(bytes, &mut pos, 2)?; // data_reference_index
        let base_offset = read_uint(bytes, &mut pos, base_offset_size)?;
        let extent_count = read_uint(bytes, &mut pos, 2)? as usize;
        let mut extents = Vec::with_capacity(extent_count);
        for _ in 0..extent_count {
            if (version == 1 || version == 2) && index_size > 0 {
                read_uint(bytes, &mut pos, index_size)?;
            }
            let offset = read_uint(bytes, &mut pos, offset_size)?;
            let length = read_uint(bytes, &mut pos, length_size)?;
            extents.push((base_offset + offset, length));
        }
        items.push(IlocItem { item_id, construction_method: construction, extents });
    }
    Some(items)
}

/// JNI entry for `StitchNative.muxAvifExif`: splices a TIFF Exif item
/// into an already-encoded (e.g. hardware-encoded) AVIF file. Null on
/// any failure so Kotlin falls back to the software encoder.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_camera_platform_StitchNative_muxAvifExif<'l>(
    mut env: jni::EnvUnowned<'l>,
    _class: jni::objects::JClass<'l>,
    avif: jni::objects::JByteArray<'l>,
    exif: jni::objects::JByteArray<'l>,
) -> jni::sys::jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jni::sys::jbyteArray> {
        let null = std::ptr::null_mut();
        let bytes = match env.convert_byte_array(&avif) {
            Ok(b) => b,
            Err(_) => return Ok(null),
        };
        let exif_bytes = match env.convert_byte_array(&exif) {
            Ok(b) => b,
            Err(_) => return Ok(null),
        };
        let muxed = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            mux_avif_exif(&bytes, &exif_bytes)
        }))
        .unwrap_or(None);
        match muxed {
            Some(b) => match env.byte_array_from_slice(&b) {
                Ok(arr) => Ok(arr.into_raw()),
                Err(_) => Ok(null),
            },
            None => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

