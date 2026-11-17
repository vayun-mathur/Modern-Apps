impl MaskKey {
    fn of(mask: &SoftMask) -> Self {
        let mut ctm = [0u64; 6];
        for (dst, src) in ctm.iter_mut().zip(mask.ctm.iter()) {
            *dst = src.to_bits();
        }
        MaskKey {
            group_id: mask.group_id,
            mask_type: mask.mask_type,
            ctm,
            backdrop: mask
                .backdrop
                .as_ref()
                .map(|b| b.iter().map(|v| v.to_bits()).collect()),
            tr: mask.tr,
        }
    }
}

/// The soft-mask bracket most recently emitted into `prims`, so a following
/// painting operation under the same mask can extend it.
pub(crate) struct MaskBracket {
    key: MaskKey,
    /// Index of the `SoftMaskPush`.
    push: usize,
    /// Index of the `SoftMaskContent` separator.
    content: usize,
    /// `prims.len()` when the bracket was closed. Coalescing is only sound while
    /// the bracket is still the tail of `prims`.
    end: usize,
}

/// Render an ExtGState soft-mask group into `prims` as the mask content of a
/// SoftMaskPush/Content/Pop bracket. The group is placed at the CTM captured
/// when the mask was set.
///
/// `masked_extent` is the device-space clip extent the masked content is being
/// painted under. §11.6.5.2 composites the group against a FULLY OPAQUE backdrop
/// of `/BC` and converts the result to luminosity, so the mask value at every
/// point the group does not paint — including everywhere outside its `/BBox` —
/// is the luminosity of `/BC`, not zero. Painting the backdrop only over the
/// `/BBox` left the rest of the mask surface at luminosity 0, which for a bright
/// `/BC` HIDES content that the file asked to be revealed. Reported by
/// `a-shading`.
///
/// Returns `false` when the group could NOT be rendered — over the recursion or
/// primitive cap, or `/G` missing or not a stream. That is NOT the same as a group
/// that legitimately paints nothing, and the caller must tell them apart: §11.6.5.2
/// makes the mask value 0 wherever the group paints nothing, and the renderer
/// composites the mask with `DST_IN`, so a bracket left EMPTY deletes every
/// primitive inside it. "Too deeply nested to expand the mask" must degrade to
/// unmasked, not to erased.
pub(crate) fn render_soft_mask_group(
    doc: &Document,
    resources: Option<&lopdf::Dictionary>,
    mask: &SoftMask,
    prims: &mut Vec<Prim>,
    depth: u32,
    masked_extent: Option<[f64; 4]>,
) -> bool {
    if depth >= MAX_PATTERN_RECURSION || prims.len() >= MAX_PRIMITIVES {
        return false;
    }
    let mstream = match doc.get_object(mask.group_id) {
        Ok(Object::Stream(s)) => s.clone(),
        _ => return false,
    };
    let mmatrix = mstream.dict.get(b"Matrix").ok().and_then(|o| read_matrix_obj(deref(doc, o).unwrap_or(o))).unwrap_or(IDENTITY);
    let group_ctm = mat_mul(&mmatrix, &mask.ctm);
    let mres = mstream.dict.get(b"Resources").ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .cloned();
    // §11.6.5.2 backdrop for luminosity masks. `/BC` is OPTIONAL and its default
    // is "the colour representing a zero luminosity in the group's colour space"
    // — BLACK, not "no backdrop at all". Gating the whole fill on `/BC` being
    // PRESENT treated a defaulted value as an absent feature.
    //
    // The clause says to composite the group against a FULLY OPAQUE backdrop and
    // take the result's luminosity, so the fill also supplies the alpha the
    // consumer's luminosity filter cannot: that filter is an affine ColorMatrix
    // whose alpha row has a 0 coefficient in the A column, and A_out = A_in x
    // luma(RGB) is not expressible as one, so this cannot be fixed on that side.
    // Worked by `hunt-wrong2`: a white group rect at `/ca 0.5` over no backdrop
    // reads as luminosity 1.0 under straight alpha — twice as opaque as the 0.5
    // the clause gives — while over an opaque black backdrop it reads 0.5. Under
    // premultiplied alpha the two already agree, so this is a no-op there and a
    // fix under straight alpha; it cannot make either worse.
    if mask.mask_type == 1 {
        let argb = match &mask.backdrop {
            Some(bc) => {
                let cs = mstream.dict.get(b"Group").ok().and_then(|o| deref(doc, o))
                    .and_then(|o| o.as_dict().ok())
                    .and_then(|gd| gd.get(b"CS").ok().and_then(|o| parse_cs_kind(doc, Some(o), &HashMap::new())));
                cs.as_ref()
                    .and_then(|k| eval_cs_to_rgb(doc, k, bc, &HashMap::new()))
                    .unwrap_or_else(|| match bc.len() {
                        1 => gray_to_argb(bc[0]),
                        3 => rgb_to_argb(bc[0], bc[1], bc[2]),
                        4 => cmyk_to_argb(bc[0], bc[1], bc[2], bc[3]),
                        _ => 0xFF00_0000,
                    })
            }
            // Zero luminosity is black in every colour space this renders
            // through, so the default needs no `/CS` round trip.
            None => 0xFF00_0000,
        };
        // The backdrop covers everything the mask is applied to, not just the
        // group's /BBox — see this function's doc comment. The /BBox quad is only
        // the FALLBACK for when there is no extent: that reproduces the OLD,
        // known-wrong behaviour in a case that already had it, whereas an
        // unbounded fill would flood the page with the backdrop colour, which is
        // a worse new failure.
        //
        // So the /BBox is required only on that fallback path. Gating the whole
        // block on it — which is what reading `rect` before this match did —
        // meant a group with no /BBox got no backdrop even when `masked_extent`
        // was Some and the extent needed to paint one was right there. §8.10.2
        // makes /BBox mandatory and a mask group is a form XObject, so that is
        // malformed input, but producers omit it. Residual found by `hunt-wrong2`
        // on the /BC fix below it.
        //
        // Capturing the extent at bracket creation is sound even though
        // `wrap_with_soft_mask` later splices more content into the SAME
        // bracket, because coalescing requires `b.end == start` — the
        // bracket must still be the tail of `prims`. The only operator
        // that can GROW the clip is `Q`, and it pushes a `Prim::ClipPop`
        // per level before restoring the saved bbox, so any growth emits
        // a prim, fails that check and forces a fresh bracket with a
        // freshly sized backdrop. The extent therefore cannot go stale
        // for anything that coalesces in. (Sizing it to the masked
        // CONTENT's extent has no such guarantee and silently
        // under-covers every operation after the first.)
        let poly: Option<Vec<(f64, f64)>> = match masked_extent {
            Some([x0, y0, x1, y1]) => Some(vec![(x0, y0), (x1, y0), (x1, y1), (x0, y1)]),
            None => mstream.dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)).map(|rect| {
                vec![
                    transform(&group_ctm, rect[0], rect[1]),
                    transform(&group_ctm, rect[2], rect[1]),
                    transform(&group_ctm, rect[2], rect[3]),
                    transform(&group_ctm, rect[0], rect[3]),
                ]
            }),
        };
        if let Some(poly) = poly {
            emit_fill(prims, std::slice::from_ref(&poly), argb, false, 1.0, BlendMode::Normal);
        }
    }
    let msub_ops = crate::content::stream_operations(doc, &mstream);
    if !msub_ops.is_empty() {
        let mgs = GraphicsState {
            ctm: group_ctm,
            soft_mask: None,
            blend_mode: BlendMode::Normal,
            alpha_fill: 1.0,
            alpha_stroke: 1.0,
            ..Default::default()
        };
        let mres_ref = mres.as_ref().or(resources);
        // Same §8.7.4.1 hazard as the `Do` arm: a mask group is a form XObject
        // (§11.6.5.2), so its `/BBox` is the clip its content is drawn under, and a
        // `sh` inside it paints nothing at all without that extent. A mask that
        // paints nothing is uniformly black, which for a luminosity mask hides
        // ALL of the masked content rather than merely mis-toning it.
        let bbox_corners = mstream
            .dict
            .get(b"BBox")
            .ok()
            .and_then(|o| read_rect(doc, o))
            .map(|bb| {
                [
                    transform(&group_ctm, bb[0], bb[1]),
                    transform(&group_ctm, bb[2], bb[1]),
                    transform(&group_ctm, bb[2], bb[3]),
                    transform(&group_ctm, bb[0], bb[3]),
                ]
            });
        let mask_clip_bbox = bbox_corners.as_ref().and_then(quad_device_bbox);
        // Seeding the extent only tells a `sh` how big to rasterize; it does not
        // BOUND anything the group paints. §11.6.5.2 makes the group a form
        // XObject, so §8.10.1's rule applies unchanged — content outside the
        // `/BBox` must read as backdrop — and the `Do` arm already emits this
        // clip. Without it a group whose content overruns its box put mask
        // luminosity where the file said there was none, revealing masked content
        // it should have hidden.
        //
        // Deliberately after the `/BC` backdrop fill above, which covers
        // everything the mask is applied to rather than just the `/BBox`.
        let mut bbox_clipped = false;
        if let Some(c) = bbox_corners {
            if prims.len() < MAX_PRIMITIVES {
                let pts: Vec<(f32, f32)> = c.iter().map(|&(x, y)| (x as f32, y as f32)).collect();
                let po = vec![
                    PathOp::Move(c[0].0 as f32, c[0].1 as f32),
                    PathOp::Line(c[1].0 as f32, c[1].1 as f32),
                    PathOp::Line(c[2].0 as f32, c[2].1 as f32),
                    PathOp::Line(c[3].0 as f32, c[3].1 as f32),
                    PathOp::Close,
                ];
                prims.push(Prim::ClipPush { even_odd: false, pts, path_ops: Some(po) });
                bbox_clipped = true;
            }
        }
        interpret_content_seeded(
            doc,
            &msub_ops,
            mres_ref,
            mgs,
            prims,
            depth + 1,
            false,
            mask_clip_bbox,
        );
        if bbox_clipped {
            // Balanced even if the prim cap was hit inside, to keep the clip stack sane.
            prims.push(Prim::ClipPop);
        }
    }
    true
}

/// Everything needed to wrap already-emitted primitives in a soft mask.
pub(crate) struct SoftMaskWrap<'a> {
    pub prims: &'a mut Vec<Prim>,
    pub start: usize,
    pub doc: &'a Document,
    pub resources: Option<&'a lopdf::Dictionary>,
    pub mask: &'a SoftMask,
    pub depth: u32,
    pub bracket: &'a mut Option<MaskBracket>,
    pub masked_extent: Option<[f64; 4]>,
}

/// Everything needed to fill a region with a pattern.
pub(crate) struct PatternFill<'a> {
    pub doc: &'a Document,
    pub pattern_id: ObjectId,
    pub polys: &'a [Vec<(f64, f64)>],
    pub even_odd: bool,
    pub pattern_base_ctm: &'a Mat,
    pub base_argb: u32,
    pub alpha_fill: f32,
    pub blend: BlendMode,
    // The invoking stream's `/Resources /ColorSpace` map: a shading whose
    // `/ColorSpace` is a NAME resolves against it (see PatternStroke).
    pub cs_resources: &'a HashMap<Vec<u8>, ObjectId>,
    pub prims: &'a mut Vec<Prim>,
    pub depth: u32,
    pub clip_depth: usize,
}

/// Everything needed to stroke subpaths with a pattern.
pub(crate) struct PatternStroke<'a> {
    pub doc: &'a Document,
    pub pattern_id: ObjectId,
    pub subpaths: &'a [Vec<(f64, f64)>],
    pub gs: &'a GraphicsState,
    pub pattern_base_ctm: &'a Mat,
    // The invoking stream's `/Resources /ColorSpace` map. §8.6.1 lets any
    // non-device colour space be written as a NAME, and Table 78 puts no
    // restriction on the form a shading's `/ColorSpace` takes — a PatternType 2
    // dictionary has no `/Resources` of its own (only tiling patterns do), so a
    // name in its shading resolves against the stream that invoked the pattern.
    // Passing an empty map made every such lookup miss and silently substitute
    // DeviceRGB. Found by `r5-color`.
    pub cs_resources: &'a HashMap<Vec<u8>, ObjectId>,
    pub prims: &'a mut Vec<Prim>,
    pub depth: u32,
    pub clip_depth: usize,
}

/// Everything needed to paint one tiling-pattern cell lattice.
pub(crate) struct TilingPattern<'a> {
    pub doc: &'a Document,
    pub obj: &'a Object,
    pub dict: &'a lopdf::Dictionary,
    pub pmat: &'a Mat,
    pub base_argb: u32,
    pub polys: &'a [Vec<(f64, f64)>],
    pub prims: &'a mut Vec<Prim>,
    pub depth: u32,
    pub alpha: f32,
    pub blend: BlendMode,
}

/// Bracket the primitives appended since `start` with the given soft mask so
/// they are drawn only where the mask is opaque/luminous. No-op if nothing was
/// emitted. Reuses the SoftMaskPush/Content/Pop wire prims.
///
/// §11.6.5.1 makes the soft mask a graphics-state PARAMETER: one mask covers
/// every operation painted while it is set. So when the bracket in `bracket` is
/// still the tail of `prims` and carries the same mask, this operation's
/// primitives are moved inside it instead of opening a second bracket. Opening
/// one per operation re-interprets the mask's whole content stream every time,
/// which on a page with one gradient mask over hundreds of shapes expands the
/// mask hundreds of times and pushes the page past [`MAX_PRIMITIVES`], dropping
/// real content.
///
/// The merge composites the coalesced operations against each other inside the
/// masked layer before the mask is applied, rather than masking each one
/// separately against the backdrop. Those agree exactly for a fully opaque or
/// fully transparent mask and for non-overlapping content — which is what a run
/// of shapes under one mask is in practice — and differ only in the alpha
/// arithmetic where partially-masked content overlaps itself.
pub(crate) fn wrap_with_soft_mask(wrap: SoftMaskWrap<'_>) {
    let SoftMaskWrap { prims, start, doc, resources, mask, depth, bracket, masked_extent } = wrap;
    if start >= prims.len() || prims.len() >= MAX_PRIMITIVES {
        return;
    }
    let key = MaskKey::of(mask);
    // Structural re-validation rather than invalidating on every other push: the
    // recorded indices must still name the bracket's own prims, and the bracket
    // must end exactly where this operation began.
    if let Some(b) = bracket.as_mut() {
        if b.key == key
            && b.end == start
            && b.push < b.content
            && b.content < b.end
            && matches!(prims.get(b.push), Some(Prim::SoftMaskPush { .. }))
            && matches!(prims.get(b.content), Some(Prim::SoftMaskContent))
            && matches!(prims.get(b.end - 1), Some(Prim::SoftMaskPop))
        {
            let added: Vec<Prim> = prims.drain(start..).collect();
            let n = added.len();
            prims.splice(b.content..b.content, added);
            b.content += n;
            b.end += n;
            return;
        }
    }
    prims.insert(start, Prim::SoftMaskPush { mask_type: mask.mask_type });
    // §11.6.5.2: the mask value is passed through `/TR` before use. `model.rs`
    // specifies this immediately after the push, and it is `None` for `/Identity`.
    let tr_inserted = mask.tr.is_some();
    if let Some(lut) = mask.tr {
        prims.insert(start + 1, Prim::SoftMaskTransfer(Box::new(lut)));
    }
    let content = prims.len();
    prims.push(Prim::SoftMaskContent);
    if !render_soft_mask_group(doc, resources, mask, prims, depth, masked_extent) {
        // The group could not be expanded (recursion cap, primitive cap, or a `/G`
        // that is missing or not a stream). Leaving the bracket in place would ship
        // an EMPTY mask, and an empty mask is not "no mask": the renderer's mask
        // layer composites with `DST_IN`, so mask alpha 0 everywhere DELETES every
        // primitive between the push and the separator. Unwind instead and paint the
        // content unmasked, which is the §11.6.5.1 no-mask default and the direction
        // that loses an effect rather than the artwork.
        //
        // A group that legitimately paints nothing is NOT this case — it returns
        // true, keeps its bracket, and correctly hides the content.
        prims.truncate(content);
        prims.remove(start);
        if tr_inserted {
            prims.remove(start);
        }
        *bracket = None;
        return;
    }
    prims.push(Prim::SoftMaskPop);
    *bracket = Some(MaskBracket { key, push: start, content, end: prims.len() });
}

/// Axis-aligned device-space bbox of a transformed `/BBox` quad, or `None` when the
/// quad is degenerate or non-finite.
///
/// §8.7.4.1 makes `sh` fill the whole current clip, and `rasterize_shading` paints
/// nothing rather than guess an extent, so every nested content stream has to hand
/// down the box its content is clipped to. Shared by the three that have one: a form
/// XObject's `/BBox` (§8.10.1), a soft-mask group's (§11.6.5.2), a tiling-pattern
/// cell's (§8.7.3.1) and an annotation appearance stream's (§12.5.5).
pub(crate) fn quad_device_bbox(c: &[(f64, f64); 4]) -> Option<[f64; 4]> {
    let xs = c.iter().map(|p| p.0);
    let ys = c.iter().map(|p| p.1);
    let b = [
        xs.clone().fold(f64::INFINITY, f64::min),
        ys.clone().fold(f64::INFINITY, f64::min),
        xs.fold(f64::NEG_INFINITY, f64::max),
        ys.fold(f64::NEG_INFINITY, f64::max),
    ];
    if b.iter().all(|v| v.is_finite()) && b[2] > b[0] && b[3] > b[1] {
        Some(b)
    } else {
        None
    }
}

/// Bounding box (device space) of a set of polygons, or `None` if empty.
fn polys_device_bbox(polys: &[Vec<(f64, f64)>]) -> Option<[f64;4]> {
    let mut x0 = f64::INFINITY; let mut y0 = f64::INFINITY;
    let mut x1 = f64::NEG_INFINITY; let mut y1 = f64::NEG_INFINITY;
    for poly in polys {
        for &(x,y) in poly.iter() {
            x0 = x0.min(x); y0 = y0.min(y); x1 = x1.max(x); y1 = y1.max(y);
        }
    }
    if x1 > x0 && y1 > y0 { Some([x0, y0, x1, y1]) } else { None }
}

/// the segments (matching how a real stroke covers the path).
pub(crate) fn paint_pattern_stroke(stroke: PatternStroke<'_>) {
    let PatternStroke { doc, pattern_id, subpaths, gs, pattern_base_ctm, cs_resources, prims, depth, clip_depth } = stroke;
    // These clips are pushed and popped inside this function, so they never
    // unbalance the stream — but they DO consume renderer clip levels, so they
    // have to respect the same ceiling as `emit_one_clip`.
    if clip_depth >= MAX_CLIP_DEPTH {
        return;
    }
    if depth >= MAX_PATTERN_RECURSION || prims.len() >= MAX_PRIMITIVES {
        return;
    }
    let obj = match doc.get_object(pattern_id) {
        Ok(o) => o,
        Err(_) => return,
    };
    let dict = match obj {
        Object::Dictionary(d) => d,
        Object::Stream(s) => &s.dict,
        _ => return,
    };
    let ptype = dict.get(b"PatternType").ok().and_then(num).unwrap_or(0.0) as i64;
    let matrix = dict.get(b"Matrix").ok().and_then(|o| read_matrix_obj(deref(doc, o).unwrap_or(o))).unwrap_or(IDENTITY);
    let pmat = mat_mul(&matrix, pattern_base_ctm);

    // Half stroke width in device space (CTM average axis scale).
    let ctm = &gs.ctm;
    let sx = (ctm[0]*ctm[0] + ctm[1]*ctm[1]).sqrt();
    let sy = (ctm[2]*ctm[2] + ctm[3]*ctm[3]).sqrt();
    let scale = (sx + sy) / 2.0;
    // P0 fix medium #23: don't enlarge hairlines via min 0.35 – keep true width, Kotlin handles 1 device px hairline
    let hw = (gs.line_width * scale) / 2.0;

    // Build ONE clip covering every stroke quad, then paint the pattern ONCE.
    // Rasterizing the shading per quad allocated a full bbox-sized image for each
    // of the ~2N quads of an N-point path (up to ~4 MB each), i.e. multi-GB on any
    // gradient-stroked curve.
    const MAX_STROKE_QUADS: usize = 4096;
    let mut quads = stroke_outline_quads(subpaths, hw);
    if quads.len() > MAX_STROKE_QUADS {
        quads.truncate(MAX_STROKE_QUADS);
    }
    let stroke_bbox = polys_device_bbox(subpaths);
    let mut path_ops: Vec<PathOp> = Vec::new();
    for quad in &quads {
        if quad.len() < 3 || shoelace_area(quad).abs() < 1e-3 { continue; }
        // The quads deliberately overlap (segment bodies plus vertex squares).
        // Normalize each to positive winding so they UNION under the nonzero rule
        // rather than cancelling each other into holes.
        let mut q: Vec<(f64, f64)> = quad.clone();
        if shoelace_area(&q) < 0.0 {
            q.reverse();
        }
        path_ops.push(PathOp::Move(q[0].0 as f32, q[0].1 as f32));
        for &(x, y) in &q[1..] {
            path_ops.push(PathOp::Line(x as f32, y as f32));
        }
        path_ops.push(PathOp::Close);
    }
    if path_ops.is_empty() || prims.len() >= MAX_PRIMITIVES {
        return;
    }
    let pts: Vec<(f32, f32)> = quads
        .first()
        .map(|q| q.iter().map(|&(x, y)| (x as f32, y as f32)).collect())
        .unwrap_or_default();
    prims.push(Prim::ClipPush { even_odd: false, pts, path_ops: Some(path_ops) });
    if ptype == 2 {
        if let Some(shobj) = dict.get(b"Shading").ok().and_then(|o| deref(doc, o)) {
            // §8.7.4.3 Table 78: `/Background` fills the parts of the painted area
            // outside the shading's own extent, and applies ONLY when the shading is
            // painted as a shading pattern — it "shall be ignored by the `sh`
            // operator". This is PatternType 2, so it opts in; the `sh` arm keeps the
            // plain entry point.
            if let Some((ctm, w, h, data)) = rasterize_shading_as_pattern(doc, shobj, &pmat, cs_resources, 0, stroke_bbox) {
                if prims.len() < MAX_PRIMITIVES {
                    prims.push(Prim::Image { ctm, w, h, format: 0, data, alpha: gs.alpha_stroke as f32, blend: gs.blend_mode });
                }
            }
        }
    } else if ptype == 1 {
        paint_tiling_pattern(TilingPattern { doc, obj, dict, pmat: &pmat, base_argb: gs.stroke, polys: &quads, prims, depth, alpha: gs.alpha_stroke as f32, blend: gs.blend_mode });
    }
    prims.push(Prim::ClipPop);
}
