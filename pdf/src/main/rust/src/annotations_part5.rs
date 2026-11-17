/// `points`: flat page-space x,y pairs of a single ink stroke.
pub(crate) fn add_ink(
    handle: i64,
    page_index: i32,
    argb: u32,
    line_width: f64,
    points: &[f32],
) -> Option<i64> {
    if points.len() < 4 {
        return None;
    }
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let converted = page_points(doc, page_index, points);
    let points = converted.as_slice();
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = line_width.max(0.5);

    let mut minx = f64::INFINITY;
    let mut miny = f64::INFINITY;
    let mut maxx = f64::NEG_INFINITY;
    let mut maxy = f64::NEG_INFINITY;
    let mut i = 0;
    while i + 1 < points.len() {
        let (x, y) = (points[i] as f64, points[i + 1] as f64);
        minx = minx.min(x);
        miny = miny.min(y);
        maxx = maxx.max(x);
        maxy = maxy.max(y);
        i += 2;
    }
    let pad = lw + 2.0;
    let rect = [minx - pad, miny - pad, maxx + pad, maxy + pad];
    let (w, h) = (rect[2] - rect[0], rect[3] - rect[1]);

    // Appearance content in BBox space (origin at rect min).
    let mut c = format!("q {lw} w {cr:.3} {cg:.3} {cb:.3} RG ");
    let mut ink = Vec::new();
    let mut j = 0;
    let mut first = true;
    while j + 1 < points.len() {
        let px = points[j] as f64;
        let py = points[j + 1] as f64;
        ink.push(px.into());
        ink.push(py.into());
        let (lx, ly) = (px - rect[0], py - rect[1]);
        if first {
            c.push_str(&format!("{lx:.2} {ly:.2} m "));
            first = false;
        } else {
            c.push_str(&format!("{lx:.2} {ly:.2} l "));
        }
        j += 2;
    }
    c.push_str("S Q");
    let ap_id = make_appearance(doc, w, h, c.into_bytes(), Dictionary::new());

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Ink"));
    annot.set("InkList", Object::Array(vec![Object::Array(ink)]));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    let mut bs = Dictionary::new();
    bs.set("W", Object::Real(lw as f32));
    annot.set("BS", Object::Dictionary(bs));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, rect, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// `jpeg`: raw JPEG bytes for a Stamp annotation image.
pub(crate) fn add_stamp(
    handle: i64,
    page_index: i32,
    rect: [f64; 4],
    img_w: u32,
    img_h: u32,
    jpeg: &[u8],
) -> Option<i64> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    // A stamp image must appear upright to the reader, not rotated with the page.
    let (w, h, apm) = page_display_orientation(doc, page_index, r[2] - r[0], r[3] - r[1]);

    let mut img_dict = Dictionary::new();
    img_dict.set("Type", name_obj("XObject"));
    img_dict.set("Subtype", name_obj("Image"));
    img_dict.set("Width", Object::Integer(img_w as i64));
    img_dict.set("Height", Object::Integer(img_h as i64));
    img_dict.set("BitsPerComponent", Object::Integer(8));
    img_dict.set("ColorSpace", name_obj("DeviceRGB"));
    img_dict.set("Filter", name_obj("DCTDecode"));
    let img_id = doc.add_object(Stream::new(img_dict, jpeg.to_vec()));

    let mut xobj = Dictionary::new();
    xobj.set("Im0", Object::Reference(img_id));
    let mut res = Dictionary::new();
    res.set("XObject", Object::Dictionary(xobj));
    let content = format!("q {w} 0 0 {h} 0 0 cm /Im0 Do Q").into_bytes();
    let ap_id = make_appearance_oriented(doc, w, h, content, res, apm);

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Stamp"));
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

pub(crate) fn update_annotation_rect(handle: i64, page_index: i32, annot_id: i64, rect: [f64; 4]) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let pr = page_rect(doc, page_index, rect);
    let id = decode_id(annot_id);
    if let Ok(dict) = doc.get_dictionary_mut(id) {
        dict.set("Rect", rect_obj(pr));
        true
    } else {
        false
    }
}

pub(crate) fn update_free_text(handle: i64, annot_id: i64, text: &str) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(annot_id);
    // Read existing rect / color / size.
    let (rect, argb, size) = {
        let dict = match doc.get_dictionary(id) {
            Ok(d) => d,
            Err(_) => return false,
        };
        let rect = dict
            .get(b"Rect")
            .ok()
            .and_then(|o| read_rect(doc, o))
            .map(normalize_rect)
            .unwrap_or([0.0, 0.0, 100.0, 20.0]);
        let argb = dict
            .get(b"C")
            .ok()
            .and_then(|o| o.as_array().ok())
            .filter(|a| a.len() == 3)
            .map(|a| {
                let r = a[0].as_float().unwrap_or(0.0);
                let g = a[1].as_float().unwrap_or(0.0);
                let b = a[2].as_float().unwrap_or(0.0);
                rgb_to_argb(r as f64, g as f64, b as f64)
            })
            .unwrap_or(0xFF00_0000);
        let size = dict
            .get(b"DA")
            .ok()
            .and_then(|o| o.as_str().ok())
            .and_then(parse_da_size)
            .unwrap_or(12.0);
        (rect, argb, size)
    };
    // The regenerated appearance must keep the orientation the annotation was
    // authored with. On a /Rotate 90/180/270 page `add_free_text` lays the text
    // out in DISPLAY orientation and carries it back into raw page space with
    // the form's `/Matrix` (§12.5.5, see `display_orientation`). Re-authoring
    // with an identity `/Matrix` and the raw `/Rect` extents put the edited text
    // sideways relative to the reader, and squashed it as well, because
    // §12.5.5 fits the /Matrix-transformed /BBox onto /Rect and a BBox whose
    // axes no longer line up with /Rect gets scaled to fit. Reusing the existing
    // stream's /BBox extents and /Matrix reproduces exactly what created it, and
    // is a strict no-op at /Rotate 0.
    let (w, h, apm) = {
        let existing = doc
            .get_dictionary(id)
            .ok()
            .and_then(|d| d.get(b"AP").ok())
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
            .and_then(|ap| ap.get(b"N").ok())
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_stream().ok());
        let fallback = (rect[2] - rect[0], rect[3] - rect[1]);
        match existing {
            Some(s) => {
                let m = s.dict.get(b"Matrix").ok().and_then(read_matrix_obj).unwrap_or(IDENTITY);
                let (bw, bh) = s
                    .dict
                    .get(b"BBox")
                    .ok()
                    .and_then(|o| read_rect(doc, o))
                    .map(normalize_rect)
                    .filter(|b| b[2] - b[0] > 0.0 && b[3] - b[1] > 0.0)
                    .map(|b| (b[2] - b[0], b[3] - b[1]))
                    .unwrap_or(fallback);
                (bw, bh, m)
            }
            None => (fallback.0, fallback.1, IDENTITY),
        }
    };
    let content = free_text_content(w, h, text, argb, size);
    let ap_id = make_appearance_oriented(doc, w, h, content, helvetica_resources(), apm);
    if let Ok(dict) = doc.get_dictionary_mut(id) {
        dict.set("Contents", Object::string_literal(text));
        let mut ap = Dictionary::new();
        ap.set("N", Object::Reference(ap_id));
        dict.set("AP", Object::Dictionary(ap));
        true
    } else {
        false
    }
}

/// Extract the font size preceding `Tf` in a `/DA` string.
pub(crate) fn parse_da_size(da: &[u8]) -> Option<f64> {
    let s = String::from_utf8_lossy(da);
    let toks: Vec<&str> = s.split_whitespace().collect();
    let tf = toks.iter().position(|t| *t == "Tf")?;
    if tf == 0 {
        return None;
    }
    toks[tf - 1].parse::<f64>().ok()
}

/// Remove an annotation reference from a page's `/Annots` (inline or indirect).
/// Returns whether a reference was actually removed. Does NOT delete the object.
pub(crate) fn remove_annot_ref(doc: &mut Document, page_id: ObjectId, id: ObjectId) -> bool {
    let indirect = match doc.get_dictionary(page_id).ok().and_then(|d| d.get(b"Annots").ok()) {
        Some(Object::Reference(aid)) => Some(*aid),
        _ => None,
    };
    if let Some(arr_id) = indirect {
        if let Ok(Object::Array(a)) = doc.get_object_mut(arr_id) {
            let before = a.len();
            a.retain(|o| o.as_reference().ok() != Some(id));
            return before != a.len();
        }
        return false;
    }
    if let Ok(page) = doc.get_dictionary_mut(page_id) {
        if let Ok(Object::Array(a)) = page.get_mut(b"Annots") {
            let before = a.len();
            a.retain(|o| o.as_reference().ok() != Some(id));
            return before != a.len();
        }
    }
    false
}

pub(crate) fn delete_annotation(handle: i64, page_index: i32, annot_id: i64) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(annot_id);
    let page_id = match nth_page_id(doc, page_index) {
        Some(p) => p,
        None => return false,
    };
    let removed = remove_annot_ref(doc, page_id, id);
    doc.objects.remove(&id);
    removed
}

/// Detach an annotation (remove its page reference) but keep the object, so it
/// can be re-attached for undo/redo.
pub(crate) fn detach_annotation(handle: i64, page_index: i32, annot_id: i64) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(annot_id);
    let page_id = match nth_page_id(doc, page_index) {
        Some(p) => p,
        None => return false,
    };
    remove_annot_ref(doc, page_id, id)
}

/// Re-attach a previously detached annotation to its page.
pub(crate) fn reattach_annotation(handle: i64, page_index: i32, annot_id: i64) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(annot_id);
    if !doc.objects.contains_key(&id) {
        return false;
    }
    let page_id = match nth_page_id(doc, page_index) {
        Some(p) => p,
        None => return false,
    };
    append_annot(doc, page_id, id);
    true
}

/// Offset alternating x,y numbers of a flat array in place by (dx, dy).
pub(crate) fn offset_flat(arr: &mut [Object], dx: f64, dy: f64) {
    for (i, o) in arr.iter_mut().enumerate() {
        if let Some(n) = num(o) {
            let d = if i % 2 == 0 { dx } else { dy };
            *o = Object::Real((n + d) as f32);
        }
    }
}

/// Duplicate an annotation, shifting its geometry by (dx, dy) page-space units.
/// The copy shares the (immutable) appearance stream. Returns the new id, or 0.
pub(crate) fn duplicate_annotation(handle: i64, page_index: i32, annot_id: i64, dx: f64, dy: f64) -> i64 {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return 0,
    };
    let id = decode_id(annot_id);
    let mut dict = match doc.get_dictionary(id) {
        Ok(d) => d.clone(),
        Err(_) => return 0,
    };
    for key in [b"Rect".as_ref(), b"Vertices", b"QuadPoints", b"L"] {
        if let Ok(Object::Array(a)) = dict.get(key) {
            let mut a2 = a.clone();
            offset_flat(&mut a2, dx, dy);
            dict.set(key.to_vec(), Object::Array(a2));
        }
    }
    if let Ok(Object::Array(lists)) = dict.get(b"InkList") {
        let mut out = Vec::with_capacity(lists.len());
        for l in lists {
            if let Object::Array(pts) = l {
                let mut p2 = pts.clone();
                offset_flat(&mut p2, dx, dy);
                out.push(Object::Array(p2));
            } else {
                out.push(l.clone());
            }
        }
        dict.set("InkList", Object::Array(out));
    }
    let new_id = doc.add_object(dict);
    let page_id = match nth_page_id(doc, page_index) {
        Some(p) => p,
        None => return 0,
    };
    append_annot(doc, page_id, new_id);
    encode_id(new_id)
}

// --- Serialized listing for the UI ---------------------------------------

pub(crate) fn subtype_code(subtype: &[u8]) -> u8 {
    match subtype {
        b"FreeText" => 1,
        b"Highlight" => 2,
        b"Square" => 3,
        b"Ink" => 4,
        b"Stamp" => 5,
        b"Widget" => 6,
        b"Text" => 7,
        b"Line" => 8,
        b"Circle" => 9,
        b"Polygon" => 10,
        b"PolyLine" => 11,
        b"Underline" => 12,
        b"StrikeOut" => 13,
        b"Squiggly" => 14,
        b"Link" => 15,
        b"Popup" => 16,
        b"FileAttachment" => 17,
        b"Sound" => 18,
        b"Movie" => 19,
        b"Screen" => 20,
        b"Caret" => 21,
        b"Redact" => 22,
        b"Watermark" => 23,
        b"PrinterMark" => 24,
        b"TrapNet" => 25,
        b"3D" => 26,
        _ => 0,
    }
}

pub(crate) fn annot_color(doc: &Document, dict: &Dictionary) -> u32 {
    // §12.5.2 Table 166: /C holds 0 (transparent), 1 (gray), 3 (RGB) or
    // 4 (CMYK) numbers. Reading only the 3-number form made every gray or
    // CMYK annotation report opaque black in the editor listing.
    let arr = dict
        .get(b"C")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_array().ok());
    let Some(a) = arr else { return 0xFF00_0000 };
    let comp = |i: usize| -> f64 {
        a.get(i)
            .and_then(|o| deref(doc, o))
            .and_then(num)
            .filter(|v| v.is_finite())
            .unwrap_or(0.0)
    };
    match a.len() {
        0 => 0x0000_0000,
        1 => gray_to_argb(comp(0)),
        3 => rgb_to_argb(comp(0), comp(1), comp(2)),
        4 => cmyk_to_argb(comp(0), comp(1), comp(2), comp(3)),
        _ => 0xFF00_0000,
    }
}
