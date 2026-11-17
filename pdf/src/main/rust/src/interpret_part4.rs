impl SeededInterp {
    fn handle_do_op(&mut self, op: &lopdf::content::Operation, o: &[Object], ctx: HandlerCtx<'_>) {
        let HandlerCtx { doc, resources, prims, depth, text_only } = ctx;
        if op.operator.as_str() == "Do" {
            if let Some(pc) = self.pending_clip.take() {
                emit_one_clip(prims, pc, &mut self.clip_depth, &mut self.current_clip_bbox, text_only);
            }
            if let Some(Object::Name(name)) = o.first() {
                if let Some(&id) = self.xobjects.get(name) {
                    if let Ok(Object::Stream(stream)) = doc.get_object(id) {
                        // Skip the whole XObject if its optional-content group is OFF.
                        if stream.dict.get(b"OC").ok().map(|oc| { let cfg = self.oc_config.get_or_insert_with(|| OcConfig::from_doc(doc)); cfg.object_hidden(doc, oc) }).unwrap_or(false) {
                            return;
                        }
                        let subtype = stream
                            .dict
                            .get(b"Subtype")
                            .ok()
                            .and_then(|o| o.as_name().ok());
                        if subtype == Some(b"Image") {
                            if !text_only && !self.oc_stack.last().copied().unwrap_or(false) {
                                if let Some(img) = extract_image(doc, stream, self.gs.fill, &self.colorspaces) {
                                    let sm_start = prims.len();
                                    if prims.len() < MAX_PRIMITIVES { prims.push(Prim::Image { ctm: self.gs.ctm, w: img.w, h: img.h, format: img.format, data: img.data, alpha: self.gs.alpha_fill as f32, blend: self.gs.blend_mode }); }
                                    if let Some(m) = self.gs.soft_mask.clone() { wrap_with_soft_mask(SoftMaskWrap { prims: &mut *prims, start: sm_start, doc, resources, mask: &m, depth, bracket: &mut self.mask_bracket, masked_extent: self.current_clip_bbox }); }
                                }
                            }
                        } else if subtype == Some(b"Form")
                            && depth < 10
                            // §8.11.2: content in a disabled optional-content group
                            // shall not be drawn. The Image branch above is gated on
                            // this; the form recursion was NOT, and `oc_stack` is a
                            // local that no nested stream inherits, so
                            // `/OC1 BDC /Fm0 Do EMC` with OC1 OFF painted the form's
                            // ENTIRE contents. Worse, it painted them UNCLIPPED,
                            // because the `/BBox` ClipPush and the `GroupPush` below
                            // are both gated on this same flag while the recursion
                            // that needed them was not.
                            //
                            // `text_only` deliberately still descends. That caller is
                            // `search::build_index`, and the policy set in the `Tj`
                            // arm is that an OC-hidden layer is hidden, not absent:
                            // its text has to stay searchable. Suppressing ink and
                            // keeping the index is exactly what mode 3 achieves there.
                            && !(self.oc_stack.last().copied().unwrap_or(false) && !text_only)
                        {
                            // Bounds BRANCHING, which `depth < 10` above does
                            // not: see [`MAX_FORM_INVOCATIONS`]. Checked before
                            // anything is emitted, so exhausting it drops the
                            // whole `Do` cleanly rather than leaving a clip or
                            // group bracket half-open.
                            if !take_form_budget() {
                                return;
                            }
                            let form_matrix = stream
                                .dict
                                .get(b"Matrix")
                                .ok()
                                .and_then(|o| read_matrix_obj(deref(doc, o).unwrap_or(o)))
                                .unwrap_or(IDENTITY);
                            let form_res = stream
                                .dict
                                .get(b"Resources")
                                .ok()
                                .and_then(|o| deref(doc, o))
                                .and_then(|o| o.as_dict().ok())
                                .cloned();
                            // Transparency group detection per Phase 4: /Group << /S /Transparency /I bool /K bool >>
                            let (is_transparency_group, isolated, knockout) = {
                                if let Some(Object::Dictionary(gdict)) = stream.dict.get(b"Group").ok().and_then(|o| deref(doc,o).or(Some(o))).cloned() {
                                    let s = gdict.get(b"S").ok().and_then(|o| o.as_name().ok());
                                    if s == Some(b"Transparency") {
                                        let i = gdict.get(b"I").ok().and_then(|o| match o { Object::Boolean(b) => Some(*b), _=> None }).unwrap_or(false);
                                        let k = gdict.get(b"K").ok().and_then(|o| match o { Object::Boolean(b) => Some(*b), _=> None }).unwrap_or(false);
                                        (true, i, k)
                                    } else { (false,false,false) }
                                } else { (false,false,false) }
                            };
                            // ExtGState soft mask active at this Do: bracket
                            // the form as the masked content and emit the /G
                            // group as the mask.
                            let active_smask = self.gs.soft_mask.clone();
                            let use_smask = active_smask.is_some()
                                && !text_only
                                && !self.oc_stack.last().copied().unwrap_or(false)
                                && prims.len() < crate::MAX_PRIMITIVES
                                && depth < MAX_GROUP_DEPTH;
                            let sm_start = prims.len();
                            // The group push is NOT mutually exclusive with the
                            // soft-mask bracket. Making it so silently disabled the
                            // §11.6.6 alpha reset below, which is gated on
                            // `pushed_group`: a form carrying BOTH `/ca` < 1 and an
                            // `/SMask` then applied `ca` to every element inside
                            // instead of once to the composited group, over-darkening
                            // wherever that content overlaps itself.
                            //
                            // Both fit because `sm_start` is captured ABOVE the push,
                            // so `wrap_with_soft_mask` inserts `SoftMaskPush` outside
                            // the group: the group composites (applying `ca` once)
                            // inside the masked layer, then the mask applies to that
                            // result, which is the §11.6.5.1 order.
                            let should_emit_group = is_transparency_group && !text_only && !self.oc_stack.last().copied().unwrap_or(false) && depth < MAX_GROUP_DEPTH;
                            let pushed_group = should_emit_group
                                && prims.len() < crate::MAX_PRIMITIVES
                                && self.group_depth < 32;
                            if pushed_group {
                                // The nonstroking constant alpha (ca) applies to the
                                // group as a whole when it is painted; NOT ca*CA.
                                prims.push(Prim::GroupPush { isolated, knockout, alpha: self.gs.alpha_fill as f32, blend: self.gs.blend_mode });
                                self.group_depth+=1;
                            }
                            // Form content shall be clipped to /BBox (transformed by
                            // /Matrix), per PDF 8.10.1, so it can't bleed past its box.
                            let form_ctm = mat_mul(&form_matrix, &self.gs.ctm);
                            // §8.7.4.1 requires `sh` to cover the ENTIRE current
                            // clipping region, so `rasterize_shading` refuses to guess:
                            // handed no clip extent, a shading with no `/BBox` of its own
                            // paints NOTHING at all. Only the page-level caller seeded
                            // that extent, and every nested stream reaches the
                            // interpreter through `interpret_content`, which passes
                            // `None` — so `/Sh sh` inside a form XObject silently
                            // vanished, which is one of the few ways a fully-implemented
                            // operator can still render nothing.
                            //
                            // §8.10.1 clips a form's content to `/BBox` transformed by
                            // `/Matrix`, so that box — intersected with the clip already
                            // in force — IS the region a nested `sh` has to fill. It is
                            // the same quantity the `ClipPush` below is built from.
                            let form_clip_bbox = {
                                let own = stream
                                    .dict
                                    .get(b"BBox")
                                    .ok()
                                    .and_then(|o| read_rect(doc, o))
                                    .and_then(|bb| {
                                        quad_device_bbox(&[
                                            transform(&form_ctm, bb[0], bb[1]),
                                            transform(&form_ctm, bb[2], bb[1]),
                                            transform(&form_ctm, bb[2], bb[3]),
                                            transform(&form_ctm, bb[0], bb[3]),
                                        ])
                                    });
                                // No `/BBox` (malformed, §8.10.2 makes it required) means
                                // no extra bound, not an empty one: inherit the caller's.
                                match (own, self.current_clip_bbox) {
                                    (Some(b), Some(cur)) => Some([
                                        b[0].max(cur[0]),
                                        b[1].max(cur[1]),
                                        b[2].min(cur[2]),
                                        b[3].min(cur[3]),
                                    ]),
                                    (Some(b), None) => Some(b),
                                    (None, cur) => cur,
                                }
                                // The intersection itself can come out empty.
                                .filter(|b| b[2] > b[0] && b[3] > b[1])
                            };
                            let mut bbox_clipped = false;
                            if !text_only && !self.oc_stack.last().copied().unwrap_or(false) {
                                // §8.10.2 makes `/BBox` REQUIRED on a form
                                // XObject, so a form without one is malformed
                                // and there is no box to clip to. Deliberately
                                // left unclipped rather than falling back to
                                // the page box: that matches pdf.js, and the
                                // form's `/Matrix` may legitimately place its
                                // content outside the page, so a page-box
                                // fallback would erase content in files that
                                // render today. It does resolve malformed
                                // input in the ink-ADDING direction, which is
                                // the trade being made knowingly. Raised by
                                // `hunt-extra`; a decision, not an oversight.
                                if let Some(bb) = stream.dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)) {
                                    if prims.len() < MAX_PRIMITIVES {
                                        let c = [
                                            transform(&form_ctm, bb[0], bb[1]),
                                            transform(&form_ctm, bb[2], bb[1]),
                                            transform(&form_ctm, bb[2], bb[3]),
                                            transform(&form_ctm, bb[0], bb[3]),
                                        ];
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
                            }
                            // §8.9.7: lopdf wraps inline-image parsing in nom
                            // `cut(...)`, so ONE inline image it cannot handle
                            // failed this whole form and blanked it, exactly as it
                            // used to blank a whole page. `stream_operations`
                            // returns lopdf's result untouched when it succeeds, so
                            // a form that renders today is unaffected.
                            let sub_ops = crate::content::stream_operations(doc, stream);
                            if !sub_ops.is_empty() {
                                    let mut sub_gs = self.gs.clone();
                                    sub_gs.ctm = form_ctm;
                                    // A soft mask applies once, to this form as a
                                    // whole. Only clear it when the wrap actually
                                    // happened; otherwise the mask must stay in the
                                    // state and be applied per element inside, since
                                    // §11.6.5.1 makes it inherited state that cannot
                                    // silently vanish.
                                    if use_smask { sub_gs.soft_mask = None; }
                                    // Per PDF 11.6.6: on entering a transparency group the
                                    // alpha constants reset to 1.0 and blend to Normal — they
                                    // are applied when the group's result is composited (via
                                    // GroupPush), not again to each element inside. Without
                                    // this, ca is double-applied and low-alpha groups vanish.
                                    //
                                    // Deliberately still gated on `pushed_group`, so when
                                    // `MAX_PRIMITIVES` or the group-depth cap demotes the push
                                    // the alpha keeps being applied per element. There is no
                                    // composite to apply it to once in that case, and resetting
                                    // anyway would drop `ca` entirely and paint the form fully
                                    // opaque — wrong for all content, where per-element is
                                    // wrong only where content overlaps itself.
                                    if pushed_group {
                                        sub_gs.alpha_fill = 1.0;
                                        sub_gs.alpha_stroke = 1.0;
                                        sub_gs.blend_mode = BlendMode::Normal;
                                    }
                                    let res_ref = form_res.as_ref().or(resources);
                                    interpret_content_seeded(
                                        doc,
                                        &sub_ops,
                                        res_ref,
                                        sub_gs,
                                        prims,
                                        depth + 1,
                                        text_only,
                                        form_clip_bbox,
                                    );
                            }
                            if bbox_clipped {
                                // Always balance the ClipPush, even if the prim cap
                                // was hit inside the form, to keep the clip stack sane.
                                prims.push(Prim::ClipPop);
                            }
                            // §8.10.1: `Do` on a form behaves as `q … Q`, so the
                            // group must be composited here rather than being left
                            // open until the next `Q` (or end of stream), which let
                            // its alpha and blend mode leak onto later content.
                            if pushed_group {
                                prims.push(Prim::GroupPop);
                                self.group_depth -= 1;
                            }
                            // Bracket the whole form as the masked content, then
                            // append the mask group (rendered at the mask's set-time CTM).
                            if use_smask {
                                if let Some(mask) = active_smask {
                                    wrap_with_soft_mask(SoftMaskWrap { prims: &mut *prims, start: sm_start, doc, resources, mask: &mask, depth, bracket: &mut self.mask_bracket, masked_extent: self.current_clip_bbox });
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
