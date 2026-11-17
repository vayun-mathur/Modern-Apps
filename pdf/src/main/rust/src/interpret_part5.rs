impl SeededInterp {
    fn handle_color_ops(&mut self, op: &lopdf::content::Operation, o: &[Object], ctx: HandlerCtx<'_>) {
        let HandlerCtx { doc, resources, prims, depth, text_only } = ctx;
        match op.operator.as_str() {
                "rg" => {
                    let n: Vec<f64> = o.iter().filter_map(num).collect();
                    if n.len() == 3 {
                        self.gs.fill = rgb_to_argb(n[0], n[1], n[2]);
                        self.gs.non_stroke_cs = CsKind::DeviceRGB;
                        self.gs.fill_pattern = None;
                    }
                }
                "RG" => {
                    let n: Vec<f64> = o.iter().filter_map(num).collect();
                    if n.len() == 3 {
                        self.gs.stroke = rgb_to_argb(n[0], n[1], n[2]);
                        self.gs.stroke_cs = CsKind::DeviceRGB;
                        self.gs.stroke_pattern = None;
                    }
                }
                "g" => {
                    if let Some(v) = o.first().and_then(num) {
                        self.gs.fill = gray_to_argb(v);
                        self.gs.non_stroke_cs = CsKind::DeviceGray;
                        self.gs.fill_pattern = None;
                    }
                }
                "G" => {
                    if let Some(v) = o.first().and_then(num) {
                        self.gs.stroke = gray_to_argb(v);
                        self.gs.stroke_cs = CsKind::DeviceGray;
                        self.gs.stroke_pattern = None;
                    }
                }
                "k" => {
                    let n: Vec<f64> = o.iter().filter_map(num).collect();
                    if n.len() == 4 {
                        self.gs.fill = cmyk_to_argb(n[0], n[1], n[2], n[3]);
                        self.gs.non_stroke_cs = CsKind::DeviceCMYK;
                        self.gs.fill_pattern = None;
                    }
                }
                "K" => {
                    let n: Vec<f64> = o.iter().filter_map(num).collect();
                    if n.len() == 4 {
                        self.gs.stroke = cmyk_to_argb(n[0], n[1], n[2], n[3]);
                        self.gs.stroke_cs = CsKind::DeviceCMYK;
                        self.gs.stroke_pattern = None;
                    }
                }
                "CS" => {
                    if let Some(cs_name) = o.first() {
                        if let Some(kind) = parse_named_cs(doc, cs_name, resources, &self.colorspaces) {
                            // Selecting a color space resets the current color to its
                            // initial value (PDF 8.6.8).
                            if let Some(c) = cs_initial_color(doc, &kind, &self.colorspaces) { self.gs.stroke = c; }
                            self.gs.stroke_cs = kind;
                        }
                        self.gs.stroke_pattern = None;
                    }
                }
                "cs" => {
                    if let Some(cs_name) = o.first() {
                        if let Some(kind) = parse_named_cs(doc, cs_name, resources, &self.colorspaces) {
                            if let Some(c) = cs_initial_color(doc, &kind, &self.colorspaces) { self.gs.fill = c; }
                            self.gs.non_stroke_cs = kind;
                        }
                        self.gs.fill_pattern = None;
                    }
                }
                "SC" => {
                    let comps: Vec<f64> = o.iter().filter_map(num).collect();
                    if let Some(rgb) = eval_cs_to_rgb(doc, &self.gs.stroke_cs, &comps, &self.colorspaces) {
                        self.gs.stroke = rgb;
                    }
                }
                "sc" => {
                    let comps: Vec<f64> = o.iter().filter_map(num).collect();
                    if let Some(rgb) = eval_cs_to_rgb(doc, &self.gs.non_stroke_cs, &comps, &self.colorspaces) {
                        self.gs.fill = rgb;
                    }
                }
                "SCN" => {
                    let comps: Vec<f64> = o.iter().filter_map(num).collect();
                    if matches!(self.gs.stroke_cs, CsKind::Pattern { .. }) {
                        self.gs.stroke_pattern = o.last().and_then(|obj| obj.as_name().ok()).and_then(|pn| self.patterns.get(pn).copied());
                        if !comps.is_empty() {
                            self.gs.stroke = uncolored_pattern_argb(doc, &self.gs.stroke_cs, &comps, &self.colorspaces);
                        }
                    } else if !comps.is_empty() {
                        if let Some(rgb) = eval_cs_to_rgb(doc, &self.gs.stroke_cs, &comps, &self.colorspaces) {
                            self.gs.stroke = rgb;
                        }
                    }
                }
                "scn" => {
                    let comps: Vec<f64> = o.iter().filter_map(num).collect();
                    if matches!(self.gs.non_stroke_cs, CsKind::Pattern { .. }) {
                        self.gs.fill_pattern = o.last().and_then(|obj| obj.as_name().ok()).and_then(|pn| self.patterns.get(pn).copied());
                        if !comps.is_empty() {
                            self.gs.fill = uncolored_pattern_argb(doc, &self.gs.non_stroke_cs, &comps, &self.colorspaces);
                        }
                    } else if !comps.is_empty() {
                        if let Some(rgb) = eval_cs_to_rgb(doc, &self.gs.non_stroke_cs, &comps, &self.colorspaces) {
                            self.gs.fill = rgb;
                        }
                    }
                }
                "sh" => {
                    // Capture the clip extent (device space) that bounds this shading
                    // so it can be rasterized at device resolution and, when the
                    // shading has no /BBox, cover the whole clip.
                    let clip_bbox_device: Option<[f64;4]> = self.pending_clip.as_ref().map(|pc| {
                        let mut x0 = f64::INFINITY; let mut y0 = f64::INFINITY;
                        let mut x1 = f64::NEG_INFINITY; let mut y1 = f64::NEG_INFINITY;
                        for poly in pc.polys.iter() {
                            for &(x,y) in poly.iter() {
                                x0 = x0.min(x); y0 = y0.min(y); x1 = x1.max(x); y1 = y1.max(y);
                            }
                        }
                        [x0, y0, x1, y1]
                    }).filter(|b| b[2] > b[0] && b[3] > b[1])
                    // Fall back to the already-committed clip region (the common
                    // `re W n /Sh sh` case, where pending_clip is None by now).
                    .or(self.current_clip_bbox);
                    if let Some(pc) = self.pending_clip.take() {
                        emit_one_clip(prims, pc, &mut self.clip_depth, &mut self.current_clip_bbox, text_only);
                    }
                    if !text_only {
                        if let Some(Object::Name(name)) = o.first() {
                            // §8.7.4.2: a /Shading resource is "a dictionary or a
                            // stream", and §7.3.8.1 requires only the stream form to
                            // be indirect — so ShadingTypes 1-3 are legally written
                            // DIRECTLY in the resource dictionary and never reach the
                            // reference-only `shadings` map.
                            let shading = self.shadings
                                .get(name)
                                .and_then(|&id| doc.get_object(id).ok())
                                .or_else(|| resolve_named_resource(doc, resources, b"Shading", name));
                            if let Some(obj) = shading {
                                if let Some((ctm,w,h,data)) = rasterize_shading(doc, obj, &self.gs.ctm, &self.colorspaces, 0, clip_bbox_device) {
                                    if prims.len() < MAX_PRIMITIVES && !self.oc_stack.last().copied().unwrap_or(false) {
                                        let sm_start = prims.len();
                                        prims.push(Prim::Image { ctm, w, h, format: 0, data, alpha: self.gs.alpha_fill as f32, blend: self.gs.blend_mode });
                                        if let Some(m) = self.gs.soft_mask.clone() { wrap_with_soft_mask(SoftMaskWrap { prims: &mut *prims, start: sm_start, doc, resources, mask: &m, depth, bracket: &mut self.mask_bracket, masked_extent: self.current_clip_bbox }); }
                                    }
                                }
                            }
                        }
                    }
                }
                "BMC" => {
                    let hidden = self.oc_stack.last().copied().unwrap_or(false);
                    if self.oc_stack.len() < MAX_OC_STACK { self.oc_stack.push(hidden); } else { self.oc_overflow += 1; }
                }
                "BDC" => {
                    // Optional content: `/OC <props> BDC`, where <props> is the OCG/OCMD
                    // itself (an inline dict or a name resolved via /Properties). The
                    // property list IS the group — there is no nested /OC key.
                    let mut should_hide = false;
                    let tag = o.first().and_then(|t| t.as_name().ok());
                    if tag == Some(b"OC") {
                        if let Some(prop_obj) = o.get(1) {
                            match prop_obj {
                                Object::Name(n) => {
                                    // Resolve via the /Properties resource, keeping the
                                    // indirect reference so ON/OFF lists can match it.
                                    if let Some(&cached) = self.oc_cache.get(&OcKey::Named(n.clone())) {
                                        should_hide = cached;
                                    } else if let Some(res_dict) = resources {
                                        if let Some(prop_dict) = res_dict.get(b"Properties").ok().and_then(|ob| deref(doc, ob)).and_then(|ob| ob.as_dict().ok()) {
                                            if let Ok(oc_ref) = prop_dict.get(n) {
                                                let cfg = self.oc_config.get_or_insert_with(|| OcConfig::from_doc(doc));
                                                should_hide = cfg.object_hidden(doc, oc_ref);
                                                self.oc_cache.insert(OcKey::Named(n.clone()), should_hide);
                                            }
                                        }
                                    }
                                }
                                Object::Reference(id) => {
                                    should_hide = match self.oc_cache.get(&OcKey::Ref(*id)) {
                                        Some(&cached) => cached,
                                        None => {
                                            let cfg = self.oc_config.get_or_insert_with(|| OcConfig::from_doc(doc));
                                            let v = cfg.object_hidden(doc, prop_obj);
                                            self.oc_cache.insert(OcKey::Ref(*id), v);
                                            v
                                        }
                                    };
                                }
                                other => {
                                    let cfg = self.oc_config.get_or_insert_with(|| OcConfig::from_doc(doc));
                                    should_hide = cfg.object_hidden(doc, other);
                                }
                            }
                        }
                    }
                    // Hiding is inherited: a visible OCG nested inside a hidden
                    // region stays hidden (§8.11.4.5).
                    let hidden = self.oc_stack.last().copied().unwrap_or(false) || should_hide;
                    if self.oc_stack.len() < MAX_OC_STACK { self.oc_stack.push(hidden); } else { self.oc_overflow += 1; }
                }
                "MP" | "DP" => {
                    // Marked-content point operators: no matching EMC, so they must not
                    // affect the marked-content / optional-content stack.
                }
                "EMC" => {
                    // Exactly one frame per EMC (§14.6). Unmatched EMCs are ignored.
                    if self.oc_overflow > 0 { self.oc_overflow -= 1; } else { self.oc_stack.pop(); }
                }
                "d0" | "d1" => {
                    // §9.6.5 Table 113: `wx wy d0` and `wx wy llx lly urx ury d1` declare
                    // the glyph's advance (and, for `d1`, its bbox). The advance comes
                    // from the font's /Widths array, which §9.6.5 requires to agree, so
                    // there is nothing to apply here — but `d1` additionally makes the
                    // glyph SHAPE ONLY, which `type3_shape_only` above acts on.
                    //
                    // Reachable only since `content::repair_d0_d1`: lopdf 0.36 ends an
                    // operator token at the first digit, so this arm was dead code and
                    // the `"d"` arm ran instead, clearing the dash pattern the glyph
                    // inherits.
                }
                _ => {}
        }
    }
}
