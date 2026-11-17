pub(crate) fn add_highlight(handle: i64, page_index: i32, rect: [f64; 4], argb: u32) -> Option<i64> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let (cr, cg, cb) = argb_rgb(argb);
    // Multiply-blended translucent fill so underlying text shows through.
    let content = format!(
        "q /GS1 gs {cr:.3} {cg:.3} {cb:.3} rg 0 0 {w} {h} re f Q"
    )
    .into_bytes();
    let mut gs = Dictionary::new();
    gs.set("Type", name_obj("ExtGState"));
    gs.set("ca", Object::Real(0.4));
    gs.set("BM", name_obj("Multiply"));
    let mut gss = Dictionary::new();
    gss.set("GS1", Object::Dictionary(gs));
    let mut res = Dictionary::new();
    res.set("ExtGState", Object::Dictionary(gss));
    let ap_id = make_appearance(doc, w, h, content, res);

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Highlight"));
    annot.set(
        "QuadPoints",
        Object::Array(vec![
            r[0].into(), r[3].into(), r[2].into(), r[3].into(),
            r[0].into(), r[1].into(), r[2].into(), r[1].into(),
        ]),
    );
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a text-markup annotation over `rect`. kind: 0 Underline, 1 StrikeOut, 2 Squiggly.
pub(crate) fn add_text_markup(handle: i64, page_index: i32, rect: [f64; 4], argb: u32, kind: i32) -> Option<i64> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    // The underline/strikeout position is relative to the text's reading
    // orientation, so it must be laid out in display orientation.
    let (w, h, apm) = page_display_orientation(doc, page_index, r[2] - r[0], r[3] - r[1]);
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = (h * 0.06).clamp(0.8, 3.0);
    let content = match kind {
        1 => {
            let y = h / 2.0;
            format!("q {lw} w {cr:.3} {cg:.3} {cb:.3} RG 0 {y:.2} m {w:.2} {y:.2} l S Q")
        }
        2 => {
            let base = h * 0.12;
            let amp = (h * 0.08).clamp(1.0, 4.0);
            let step = (amp * 2.0).max(3.0);
            let mut c = format!("q {lw} w {cr:.3} {cg:.3} {cb:.3} RG 0 {base:.2} m ");
            let mut x = 0.0;
            let mut up = true;
            while x < w {
                let nx = (x + step).min(w);
                let y = if up { base + amp } else { base };
                c.push_str(&format!("{nx:.2} {y:.2} l "));
                x = nx;
                up = !up;
            }
            c.push_str("S Q");
            c
        }
        _ => {
            let y = h * 0.10;
            format!("q {lw} w {cr:.3} {cg:.3} {cb:.3} RG 0 {y:.2} m {w:.2} {y:.2} l S Q")
        }
    }
    .into_bytes();
    let ap_id = make_appearance_oriented(doc, w, h, content, Dictionary::new(), apm);
    let subtype = match kind {
        1 => "StrikeOut",
        2 => "Squiggly",
        _ => "Underline",
    };
    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj(subtype));
    annot.set(
        "QuadPoints",
        Object::Array(vec![
            r[0].into(), r[3].into(), r[2].into(), r[3].into(),
            r[0].into(), r[1].into(), r[2].into(), r[1].into(),
        ]),
    );
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a sticky-note (Text) annotation at editor point (x,y) with `text`.
pub(crate) fn add_note(handle: i64, page_index: i32, x: f64, y: f64, argb: u32, text: &str) -> Option<i64> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let binv = page_base_inverse(doc, page_index);
    let (px, py) = transform(&binv, x, y);
    let s = 20.0;
    let r = normalize_rect([px, py - s, px + s, py]);
    let (cr, cg, cb) = argb_rgb(argb);
    let content = format!(
        "q {cr:.3} {cg:.3} {cb:.3} rg 1 1 {w:.1} {h:.1} re f 1 1 1 rg 4 5 12 2 re f 4 9 12 2 re f 4 13 8 2 re f Q",
        w = s - 2.0,
        h = s - 2.0,
    )
    .into_bytes();
    // The icon's text bars read left-to-right, so like the other text-bearing
    // appearances it is laid out in display orientation and rotated back by
    // /Matrix; the rect is square, so only the content orientation changes.
    let rot = nth_page_id(doc, page_index)
        .map(|pid| page_rotation(doc, pid))
        .unwrap_or(0);
    let (_, _, apm) = display_orientation(rot, s, s);
    let ap_id = make_appearance_oriented(doc, s, s, content, Dictionary::new(), apm);
    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Text"));
    annot.set("Name", name_obj("Note"));
    annot.set("Contents", Object::string_literal(text));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a FreeText callout: a leader line from anchor (ax,ay) to a text box near
/// (bx,by), all in editor coordinates.
///
/// The whole callout — leader, box and text — is laid out in DISPLAY space and
/// carried back into raw page space by the appearance `/Matrix`, the same
/// mechanism `add_free_text` uses (see `display_orientation`). Laying it out in
/// raw page space instead, as this did, put the text and the box sideways
/// relative to the visible content on a `/Rotate 90` or `270` page, and mirrored
/// the leader's knee on `180`.
pub(crate) fn add_callout(
    handle: i64,
    page_index: i32,
    // Leader anchor and text-box corner, in editor coordinates.
    anchor: (f64, f64),
    box_pt: (f64, f64),
    argb: u32,
    size: f64,
    text: &str,
) -> Option<i64> {
    let (ax, ay) = anchor;
    let (bx, by) = box_pt;
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let bw = 160.0;
    let bh = (size * 1.6).max(24.0);
    // Editor coordinates are already display space, so the box is built there.
    let (box_x0, box_y1) = (bx, by);
    let box_y0 = by - bh;
    let box_x1 = bx + bw;
    let minx = ax.min(box_x0);
    let miny = ay.min(box_y0);
    let maxx = ax.max(box_x1);
    let maxy = ay.max(box_y1);
    let (w, h) = (maxx - minx, maxy - miny);
    // /Rect is in raw page space; the display-space bounding box maps to it under
    // the page base matrix, which for every /Rotate is axis-aligned.
    let r = page_rect(doc, page_index, [minx, miny, maxx, maxy]);
    let rot = nth_page_id(doc, page_index)
        .map(|pid| page_rotation(doc, pid))
        .unwrap_or(0);
    let (_, _, apm) = display_orientation(rot, w, h);
    let (cr, cg, cb) = argb_rgb(argb);
    let lax = ax - minx;
    let lay = ay - miny;
    let lx0 = box_x0 - minx;
    let ly0 = box_y0 - miny;
    let lx1 = box_x1 - minx;
    let ly1 = box_y1 - miny;
    let knee_y = (ly0 + ly1) / 2.0;
    let mut c = format!(
        "q 1 w {cr:.3} {cg:.3} {cb:.3} RG {lax:.2} {lay:.2} m {lx0:.2} {knee_y:.2} l S "
    );
    c.push_str(&format!(
        "{lx0:.2} {ly0:.2} {bw2:.2} {bh2:.2} re S ",
        bw2 = lx1 - lx0,
        bh2 = ly1 - ly0,
    ));
    c.push_str(&format!(
        "{cr:.3} {cg:.3} {cb:.3} rg BT /F1 {size} Tf {tx:.2} {ty:.2} Td ({t}) Tj ET Q",
        tx = lx0 + 4.0,
        ty = ly1 - size - 2.0,
        t = escape_pdf_literal(text),
    ));
    let ap_id = make_appearance_oriented(doc, w, h, c.into_bytes(), helvetica_resources(), apm);
    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("FreeText"));
    annot.set("IT", name_obj("FreeTextCallout"));
    annot.set("Contents", Object::string_literal(text));
    annot.set(
        "DA",
        Object::string_literal(format!("{cr:.3} {cg:.3} {cb:.3} rg /F1 {size} Tf")),
    );
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a redaction annotation: an opaque black filled rectangle marked so that
/// `apply_redactions` can permanently remove the content beneath it.
pub(crate) fn add_redaction(handle: i64, page_index: i32, rect: [f64; 4]) -> Option<i64> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let content = format!("q 0 0 0 rg 0 0 {w} {h} re f Q").into_bytes();
    let ap_id = make_appearance(doc, w, h, content, Dictionary::new());
    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Square"));
    annot.set("IC", Object::Array(vec![0.into(), 0.into(), 0.into()]));
    annot.set("PdfRedact", Object::Boolean(true));
    let mut bs = Dictionary::new();
    bs.set("W", Object::Real(0.0));
    annot.set("BS", Object::Dictionary(bs));
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

pub(crate) fn add_square(
    handle: i64,
    page_index: i32,
    rect: [f64; 4],
    argb: u32,
    line_width: f64,
    fill: bool,
) -> Option<i64> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = line_width.max(0.5);
    let content = if fill {
        format!("q {cr:.3} {cg:.3} {cb:.3} rg 0 0 {w} {h} re f Q")
    } else {
        format!(
            "q {lw} w {cr:.3} {cg:.3} {cb:.3} RG {x} {y} {rw} {rh} re S Q",
            x = lw / 2.0,
            y = lw / 2.0,
            rw = w - lw,
            rh = h - lw,
        )
    }
    .into_bytes();
    let ap_id = make_appearance(doc, w, h, content, Dictionary::new());

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Square"));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_shape_border(&mut annot, argb, lw, fill);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a Circle (ellipse) annotation inscribed in [rect], stroked or filled.
pub(crate) fn add_circle(
    handle: i64,
    page_index: i32,
    rect: [f64; 4],
    argb: u32,
    line_width: f64,
    fill: bool,
) -> Option<i64> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = line_width.max(0.5);

    // Ellipse inscribed in the BBox
    // approximated by four cubic Bézier arcs.
    let inset = if fill { 0.0 } else { lw / 2.0 };
    let cx = w / 2.0;
    let cy = h / 2.0;
    let rx = (w / 2.0 - inset).max(0.0);
    let ry = (h / 2.0 - inset).max(0.0);
    let k = 0.552_284_75_f64; // 4/3 * (sqrt(2) - 1)
    let ox = rx * k;
    let oy = ry * k;

    let mut c = String::from("q ");
    if fill {
        c.push_str(&format!("{cr:.3} {cg:.3} {cb:.3} rg "));
    } else {
        c.push_str(&format!("{lw} w {cr:.3} {cg:.3} {cb:.3} RG "));
    }
    c.push_str(&format!("{:.2} {:.2} m ", cx + rx, cy));
    c.push_str(&format!(
        "{:.2} {:.2} {:.2} {:.2} {:.2} {:.2} c ",
        cx + rx, cy + oy, cx + ox, cy + ry, cx, cy + ry,
    ));
    c.push_str(&format!(
        "{:.2} {:.2} {:.2} {:.2} {:.2} {:.2} c ",
        cx - ox, cy + ry, cx - rx, cy + oy, cx - rx, cy,
    ));
    c.push_str(&format!(
        "{:.2} {:.2} {:.2} {:.2} {:.2} {:.2} c ",
        cx - rx, cy - oy, cx - ox, cy - ry, cx, cy - ry,
    ));
    c.push_str(&format!(
        "{:.2} {:.2} {:.2} {:.2} {:.2} {:.2} c ",
        cx + ox, cy - ry, cx + rx, cy - oy, cx + rx, cy,
    ));
    c.push_str(if fill { "f Q" } else { "S Q" });
    let ap_id = make_appearance(doc, w, h, c.into_bytes(), Dictionary::new());

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Circle"));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_shape_border(&mut annot, argb, lw, fill);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Set annotation constant opacity (`/CA`, `/ca`) from the alpha byte of `argb`.
pub(crate) fn set_alpha(annot: &mut Dictionary, argb: u32) {
    let a = ((argb >> 24) & 0xFF) as f64 / 255.0;
    if a < 1.0 {
        annot.set("CA", Object::Real(a as f32));
        annot.set("ca", Object::Real(a as f32));
    }
}

/// Set `/BS` (border) and, for filled shapes, `/IC` (interior color) on a
/// Square/Circle annotation. Filled shapes carry a zero-width border.
pub(crate) fn set_shape_border(annot: &mut Dictionary, argb: u32, line_width: f64, fill: bool) {
    let (cr, cg, cb) = argb_rgb(argb);
    let mut bs = Dictionary::new();
    if fill {
        annot.set("IC", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
        bs.set("W", Object::Real(0.0));
    } else {
        bs.set("W", Object::Real(line_width as f32));
    }
    annot.set("BS", Object::Dictionary(bs));
    set_alpha(annot, argb);
}

/// Add a Polygon (when `closed`) or PolyLine (open) annotation from flat
/// page-space x,y `points`. Closed polygons may be filled; open polylines are
/// always stroked. Used for triangles, stars, arrows, lines, polylines and
/// flattened Bézier curves.
pub(crate) fn add_poly(
    handle: i64,
    page_index: i32,
    points: &[f32],
    argb: u32,
    line_width: f64,
    fill: bool,
    closed: bool,
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
    let do_fill = fill && closed;

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

    let mut c = String::from("q ");
    if do_fill {
        c.push_str(&format!("{cr:.3} {cg:.3} {cb:.3} rg "));
    } else {
        c.push_str(&format!("{lw} w {cr:.3} {cg:.3} {cb:.3} RG "));
    }
    let mut verts = Vec::new();
    let mut j = 0;
    let mut first = true;
    while j + 1 < points.len() {
        let px = points[j] as f64;
        let py = points[j + 1] as f64;
        verts.push(px.into());
        verts.push(py.into());
        let (lx, ly) = (px - rect[0], py - rect[1]);
        if first {
            c.push_str(&format!("{lx:.2} {ly:.2} m "));
            first = false;
        } else {
            c.push_str(&format!("{lx:.2} {ly:.2} l "));
        }
        j += 2;
    }
    if closed {
        c.push_str(if do_fill { "h f Q" } else { "h S Q" });
    } else {
        c.push_str("S Q");
    }
    let ap_id = make_appearance(doc, w, h, c.into_bytes(), Dictionary::new());

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj(if closed { "Polygon" } else { "PolyLine" }));
    annot.set("Vertices", Object::Array(verts));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    if do_fill {
        annot.set("IC", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    }
    let mut bs = Dictionary::new();
    bs.set("W", Object::Real(if do_fill { 0.0 } else { lw as f32 }));
    annot.set("BS", Object::Dictionary(bs));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, normalize_rect(rect), ap_id);
    add_annotation_object(doc, page_index, annot)
}
