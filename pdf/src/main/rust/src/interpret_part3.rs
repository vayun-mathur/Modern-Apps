impl SeededInterp {
    fn handle_path_ops(&mut self, op: &lopdf::content::Operation, o: &[Object], doc: &Document, prims: &mut Vec<Prim>, text_only: bool) {
        // Non-finite coordinate guard (same rationale as the `finite2` closure
        // formerly defined in `handle_state_ops`): a single non-finite point
        // makes the whole path undrawable.
        let finite2 = |a: (f64, f64)| a.0.is_finite() && a.1.is_finite();
        match op.operator.as_str() {
                "W" => {
                    // P0 fix: emit as single ClipPush preserving holes via path_ops (was per-poly loop)
                    if let Some(pc) = self.pending_clip.take() {
                        emit_one_clip(prims, pc, &mut self.clip_depth, &mut self.current_clip_bbox, text_only);
                    }
                    self.pending_clip = Some(PendingClip { even_odd: false, polys: self.subpaths.clone(), path_ops: self.clip_path_ops.clone() });
                }
                "W*" => {
                    if let Some(pc) = self.pending_clip.take() {
                        emit_one_clip(prims, pc, &mut self.clip_depth, &mut self.current_clip_bbox, text_only);
                    }
                    self.pending_clip = Some(PendingClip { even_odd: true, polys: self.subpaths.clone(), path_ops: self.clip_path_ops.clone() });
                }
                "m" => {
                    let xn = o.first().and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x)));
                    let yn = o.get(1).and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x)));
                    if let (Some(x), Some(y)) = (xn, yn) {
                        let (dx, dy) = dev(&self.gs, x, y);
                        // A non-finite moveto starts no subpath: see `finite2`.
                        if !finite2((dx, dy)) { return; }
                        self.cur_user = (x, y);
                        self.start_user = (x, y);
                        self.subpath_closed = false;
                        self.subpath_dropped = !reopen_subpath(&mut self.subpaths, &mut self.clip_path_ops, (dx, dy));
                    }
                }
                "l" => {
                    let xn = o.first().and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x)));
                    let yn = o.get(1).and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x)));
                    if let (Some(x), Some(y)) = (xn, yn) {
                        // §8.5.2.1: `l` with no current point is an error. Fabricating a
                        // subpath here desynchronised `subpaths` from `clip_path_ops`,
                        // and an unmatched Line makes Android's Path start at (0,0).
                        if self.subpaths.is_empty() || self.subpath_dropped { return; }
                        let (dx, dy) = dev(&self.gs, x, y);
                        // A non-finite point would make the whole contour undrawable,
                        // not just this vertex: see `finite2`.
                        if !finite2((dx, dy)) { return; }
                        // §8.5.2.1: a segment after a close starts a new subpath.
                        if self.subpath_closed {
                            let at = dev(&self.gs, self.start_user.0, self.start_user.1);
                            if !finite2(at) { return; }
                            if !reopen_subpath(&mut self.subpaths, &mut self.clip_path_ops, at) {
                                self.subpath_dropped = true;
                                return;
                            }
                            self.subpath_closed = false;
                        }
                        self.cur_user = (x, y);
                        if let Some(sp) = self.subpaths.last_mut() {
                            sp.push((dx, dy));
                        }
                        self.clip_path_ops.push(PathOp::Line(dx as f32, dy as f32));
                    }
                }
                "c" | "v" | "y" => {
                    let nums: Vec<f64> = o.iter().filter_map(|x| deref(doc, x).and_then(num).or_else(|| num(x))).collect();
                    let (p1, p2, p3) = match op.operator.as_str() {
                        "c" if nums.len() == 6 => (
                            (nums[0], nums[1]),
                            (nums[2], nums[3]),
                            (nums[4], nums[5]),
                        ),
                        "v" if nums.len() == 4 => {
                            (self.cur_user, (nums[0], nums[1]), (nums[2], nums[3]))
                        }
                        "y" if nums.len() == 4 => {
                            ((nums[0], nums[1]), (nums[2], nums[3]), (nums[2], nums[3]))
                        }
                        _ => return,
                    };
                    let p0 = self.cur_user;
                    // Same rule as `l`: a curve with no current point is a no-op.
                    if self.subpaths.is_empty() || self.subpath_dropped { return; }
                    let (d0x, d0y) = dev(&self.gs, p0.0, p0.1);
                    let (c1x, c1y) = dev(&self.gs, p1.0, p1.1);
                    let (c2x, c2y) = dev(&self.gs, p2.0, p2.1);
                    let (c3x, c3y) = dev(&self.gs, p3.0, p3.1);
                    // One non-finite control point makes every flattened vertex
                    // non-finite: see `finite2`.
                    if !(finite2((d0x, d0y)) && finite2((c1x, c1y))
                        && finite2((c2x, c2y)) && finite2((c3x, c3y)))
                    {
                        return;
                    }
                    // §8.5.2.1: a segment after a close starts a new subpath, anchored
                    // at the closepoint the curve itself starts from.
                    if self.subpath_closed {
                        if !reopen_subpath(&mut self.subpaths, &mut self.clip_path_ops, (d0x, d0y)) {
                            self.subpath_dropped = true;
                            return;
                        }
                        self.subpath_closed = false;
                    }
                    let bez_steps = bezier_steps_for_flatness(
                        [(d0x, d0y), (c1x, c1y), (c2x, c2y), (c3x, c3y)],
                        self.gs.flatness,
                    );
                    for step in 1..=bez_steps {
                        let t = step as f64 / bez_steps as f64;
                        let (bx, by) = cubic_bezier(p0, p1, p2, p3, t);
                        if let Some(sp) = self.subpaths.last_mut() {
                            sp.push(dev(&self.gs, bx, by));
                        }
                    }
                    self.cur_user = p3;
                    // Record the exact cubic (device space) for bezier-retentive clips.
                    self.clip_path_ops.push(PathOp::Cubic(c1x as f32, c1y as f32, c2x as f32, c2y as f32, c3x as f32, c3y as f32));
                }
                "re" => {
                    let nums: Vec<f64> = o.iter().filter_map(|x| deref(doc, x).and_then(num).or_else(|| num(x))).collect();
                    if nums.len() == 4 {
                        let (x, y, w, h) = (nums[0], nums[1], nums[2], nums[3]);
                        let (mx, my) = dev(&self.gs, x, y);
                        let (x1, y1d) = dev(&self.gs, x + w, y);
                        let (x2, y2d) = dev(&self.gs, x + w, y + h);
                        let (x3, y3d) = dev(&self.gs, x, y + h);
                        // See `finite2`: a non-finite corner makes the whole rect
                        // undrawable, and `re` is the usual shape of a `W n` clip.
                        if !(finite2((mx, my)) && finite2((x1, y1d))
                            && finite2((x2, y2d)) && finite2((x3, y3d)))
                        {
                            return;
                        }
                        let rect = vec![
                            (mx, my),
                            (x1, y1d),
                            (x2, y2d),
                            (x3, y3d),
                            (mx, my),
                        ];
                        if self.subpaths.len() >= MAX_SUBPATHS { self.subpath_dropped = true; return; }
                        self.subpaths.push(rect);
                        self.clip_path_ops.push(PathOp::Move(mx as f32, my as f32));
                        self.clip_path_ops.push(PathOp::Line(x1 as f32, y1d as f32));
                        self.clip_path_ops.push(PathOp::Line(x2 as f32, y2d as f32));
                        self.clip_path_ops.push(PathOp::Line(x3 as f32, y3d as f32));
                        self.clip_path_ops.push(PathOp::Close);
                        self.cur_user = (x, y);
                        self.start_user = (x, y);
                        // §8.5.2.1 Table 59 defines `re` as `… l h`, so the subpath is
                        // closed and terminated: a following segment starts a new one.
                        self.subpath_closed = true;
                        self.subpath_dropped = false;
                    }
                }
                "h" => {
                    // §8.5.2.1: `h` closes the CURRENT subpath; with no current point
                    // there is nothing to close. Emitting a bare `Close` desynchronised
                    // `clip_path_ops` from `subpaths` and moved the current point to a
                    // stale `start_user`.
                    if self.subpaths.is_empty() || self.subpath_dropped { return; }
                    // §8.5.2.1: "If the current subpath is already closed, h shall do
                    // nothing." A second Close would also duplicate the path op.
                    if self.subpath_closed { return; }
                    // P0 fix: avoid duplicate close point causing zero-length segment
                    if let Some(sp) = self.subpaths.last_mut() {
                        let (sx, sy) = dev(&self.gs, self.start_user.0, self.start_user.1);
                        let skip_dup = sp.last().map(|&(px, py)| (px-sx).abs() < 1e-6 && (py-sy).abs() < 1e-6).unwrap_or(false);
                        if !skip_dup { sp.push((sx, sy)); }
                    }
                    self.clip_path_ops.push(PathOp::Close);
                    self.cur_user = self.start_user;
                    self.subpath_closed = true;
                }
                _ => {}
        }
    }
}

impl SeededInterp {
    fn handle_paint_ops(&mut self, op: &lopdf::content::Operation, o: &[Object], ctx: HandlerCtx<'_>) {
        let HandlerCtx { doc, resources, prims, depth, text_only } = ctx;
        match op.operator.as_str() {
                "S" | "s" => {
                    // `s` closes first (§8.5.3.1). A subpath `re` or `h` already closed
                    // must not get a second closing point.
                    if op.operator == "s" && !self.subpath_closed {
                        if let Some(sp) = self.subpaths.last_mut() {
                            sp.push(dev(&self.gs, self.start_user.0, self.start_user.1));
                        }
                    }
                    if !text_only && !self.oc_stack.last().copied().unwrap_or(false) {
                        let sm_start = prims.len();
                        if let Some(pid) = self.gs.stroke_pattern {
                            paint_pattern_stroke(PatternStroke { doc, pattern_id: pid, subpaths: &self.subpaths, gs: &self.gs, pattern_base_ctm: &self.pattern_base_ctm, cs_resources: &self.colorspaces, prims: &mut *prims, depth, clip_depth: self.clip_depth });
                        } else if prims.len() < MAX_PRIMITIVES {
                            emit_stroke(prims, &self.subpaths, &self.gs);
                        }
                        if let Some(m) = self.gs.soft_mask.clone() {
                            wrap_with_soft_mask(SoftMaskWrap { prims: &mut *prims, start: sm_start, doc, resources, mask: &m, depth, bracket: &mut self.mask_bracket, masked_extent: self.current_clip_bbox });
                        }
                    }
                    // §8.5.4: a pending `W` takes effect only AFTER the painting
                    // operator that ends the path object — "the painting operation
                    // shall be unaffected by the new clipping path". Committing it
                    // first clipped a stroke to its own centreline, so `W S` came out
                    // at half width.
                    if let Some(pc) = self.pending_clip.take() {
                        emit_one_clip(prims, pc, &mut self.clip_depth, &mut self.current_clip_bbox, text_only);
                    }
                    self.subpaths.clear(); self.clip_path_ops.clear();
                }
                "f" | "F" | "f*" => {
                    if !text_only && !self.oc_stack.last().copied().unwrap_or(false) {
                        let sm_start = prims.len();
                        if let Some(pid) = self.gs.fill_pattern {
                            paint_pattern_fill(PatternFill { doc, pattern_id: pid, polys: &self.subpaths, even_odd: op.operator == "f*", pattern_base_ctm: &self.pattern_base_ctm, base_argb: self.gs.fill, alpha_fill: self.gs.alpha_fill as f32, blend: self.gs.blend_mode, cs_resources: &self.colorspaces, prims: &mut *prims, depth, clip_depth: self.clip_depth });
                        } else if prims.len() < MAX_PRIMITIVES {
                            emit_fill(prims, &self.subpaths, self.gs.fill, op.operator == "f*", self.gs.alpha_fill, self.gs.blend_mode);
                        }
                        if let Some(m) = self.gs.soft_mask.clone() {
                            wrap_with_soft_mask(SoftMaskWrap { prims: &mut *prims, start: sm_start, doc, resources, mask: &m, depth, bracket: &mut self.mask_bracket, masked_extent: self.current_clip_bbox });
                        }
                    }
                    // §8.5.4: see the `S` arm — the clip lands after the paint.
                    if let Some(pc) = self.pending_clip.take() {
                        emit_one_clip(prims, pc, &mut self.clip_depth, &mut self.current_clip_bbox, text_only);
                    }
                    self.subpaths.clear(); self.clip_path_ops.clear();
                }
                "B" | "B*" | "b" | "b*" => {
                    if op.operator.starts_with('b') && !self.subpath_closed {
                        if let Some(sp) = self.subpaths.last_mut() {
                            sp.push(dev(&self.gs, self.start_user.0, self.start_user.1));
                        }
                    }
                    if !text_only && !self.oc_stack.last().copied().unwrap_or(false) {
                        let sm_start = prims.len();
                        if let Some(pid) = self.gs.fill_pattern {
                            paint_pattern_fill(PatternFill { doc, pattern_id: pid, polys: &self.subpaths, even_odd: op.operator.ends_with('*'), pattern_base_ctm: &self.pattern_base_ctm, base_argb: self.gs.fill, alpha_fill: self.gs.alpha_fill as f32, blend: self.gs.blend_mode, cs_resources: &self.colorspaces, prims: &mut *prims, depth, clip_depth: self.clip_depth });
                        } else if prims.len() < MAX_PRIMITIVES {
                            emit_fill(prims, &self.subpaths, self.gs.fill, op.operator.ends_with('*'), self.gs.alpha_fill, self.gs.blend_mode);
                        }
                        if let Some(pid) = self.gs.stroke_pattern {
                            paint_pattern_stroke(PatternStroke { doc, pattern_id: pid, subpaths: &self.subpaths, gs: &self.gs, pattern_base_ctm: &self.pattern_base_ctm, cs_resources: &self.colorspaces, prims: &mut *prims, depth, clip_depth: self.clip_depth });
                        } else if prims.len() < MAX_PRIMITIVES {
                            emit_stroke(prims, &self.subpaths, &self.gs);
                        }
                        if let Some(m) = self.gs.soft_mask.clone() {
                            wrap_with_soft_mask(SoftMaskWrap { prims: &mut *prims, start: sm_start, doc, resources, mask: &m, depth, bracket: &mut self.mask_bracket, masked_extent: self.current_clip_bbox });
                        }
                    }
                    // §8.5.4: see the `S` arm — the clip lands after the paint.
                    if let Some(pc) = self.pending_clip.take() {
                        emit_one_clip(prims, pc, &mut self.clip_depth, &mut self.current_clip_bbox, text_only);
                    }
                    self.subpaths.clear(); self.clip_path_ops.clear();
                }
                "n" => {
                    if let Some(pc) = self.pending_clip.take() {
                        emit_one_clip(prims, pc, &mut self.clip_depth, &mut self.current_clip_bbox, text_only);
                    }
                    self.subpaths.clear(); self.clip_path_ops.clear();
                }
                "BI" => {
                    if let Some(pc) = self.pending_clip.take() {
                        emit_one_clip(prims, pc, &mut self.clip_depth, &mut self.current_clip_bbox, text_only);
                    }
                    if !text_only && !self.oc_stack.last().copied().unwrap_or(false) {
                        if let Some(Object::Stream(stream)) = o.first() {
                            if let Some(img) = extract_inline_image(doc, stream, self.gs.fill, &self.colorspaces) {
                                let sm_start = prims.len();
                                if prims.len() < MAX_PRIMITIVES { prims.push(Prim::Image { ctm: self.gs.ctm, w: img.w, h: img.h, format: img.format, data: img.data, alpha: self.gs.alpha_fill as f32, blend: self.gs.blend_mode }); }
                                if let Some(m) = self.gs.soft_mask.clone() { wrap_with_soft_mask(SoftMaskWrap { prims: &mut *prims, start: sm_start, doc, resources, mask: &m, depth, bracket: &mut self.mask_bracket, masked_extent: self.current_clip_bbox }); }
                            }
                        }
                    }
                }
                _ => {}
        }
    }
}
