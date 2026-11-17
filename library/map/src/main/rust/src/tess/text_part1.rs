/// One glyph positioned along a curved baseline: the source glyph, where its pen origin sits
/// in tile-local 0..1, and the unit tangent of the baseline there.
///
/// The single source of truth shared by [`emit_curved`] (which builds the quads) and the
/// placer (which builds the oriented collision boxes) so the box a label occupies is the box
/// the GPU draws — the curved analogue of what [`crate::tile::placement::anchored_rect`] does
/// for a point label.
#[derive(Clone, Copy, Debug)]
pub struct CurvedGlyph {
    /// The source glyph: char, atlas metrics and advance, all in font units.
    pub glyph: ShapedGlyph,
    /// Pen origin on the baseline, tile-local 0..1.
    pub pen: (f32, f32),
    /// Unit tangent `(cos, sin)` of the baseline at the pen, tile-local. The glyph is rotated
    /// to this, so the run curves with the road/river.
    pub tangent: (f32, f32),
}

/// The arc length of a tile-local polyline.
fn polyline_length(pts: &[(f32, f32)]) -> f32 {
    let mut sum = 0.0;
    for w in pts.windows(2) {
        let dx = w[1].0 - w[0].0;
        let dy = w[1].1 - w[0].1;
        sum += (dx * dx + dy * dy).sqrt();
    }
    sum
}

/// The sharpest turn a run may be laid across, at a single vertex.
///
/// MapLibre's `symbol-max-angle` default. A corner sharper than this cannot carry legible
/// glyphs, and — more importantly here — it is what a *discontinuity* looks like: a line
/// feature's parts are joined end to end before they reach this module, so a multi-part road
/// arrives as one polyline with a phantom connector segment bridging the gap. That connector
/// inflates the arc length (so an over-long name passes the fit test) and points nowhere near
/// the road (so the glyphs that land on it fly off at their own angle). Both of the reported
/// symptoms — a name that stops mid-word, and letters kinked away from the line — are that.
const MAX_TURN_RAD: f32 = std::f32::consts::FRAC_PI_4;

/// The longest stretch of `pts` containing no turn sharper than [`MAX_TURN_RAD`].
///
/// A label is laid along this rather than the whole polyline, which is the cheap version of
/// "put the run where it fits": no placement search, just the single best-behaved stretch.
///
/// Unchanged from the original rescan-per-corner algorithm: the candidate lengths
/// feed `>` comparisons that pick the winning run, and prefix-sum subtraction
/// rounds differently from a fresh rescan — a 1-ulp difference flips which run
/// wins on ties (the equivalence harness caught exactly this on a zigzag). The
/// O(n)-per-corner rescans only bite on paths with many sharp turns; the real
/// per-frame cost is the per-glyph sampling, linearised in `layout_along_line`.
fn longest_smooth_run(pts: &[(f32, f32)]) -> &[(f32, f32)] {
    if pts.len() < 3 {
        return pts;
    }
    let cos_max = MAX_TURN_RAD.cos();
    let direction = |a: (f32, f32), b: (f32, f32)| {
        let (dx, dy) = (b.0 - a.0, b.1 - a.1);
        let len = (dx * dx + dy * dy).sqrt();
        (len > 0.0).then_some((dx / len, dy / len))
    };
    let (mut best_start, mut best_end) = (0usize, pts.len());
    let mut best_len = -1.0f32;
    let mut start = 0usize;
    let mut previous: Option<(f32, f32)> = None;
    for i in 0..pts.len() - 1 {
        // A repeated vertex has no direction of its own; it bridges its neighbours rather than
        // ending the run, exactly as the sampler skips it.
        let Some(d) = direction(pts[i], pts[i + 1]) else { continue };
        if let Some(p) = previous {
            if p.0 * d.0 + p.1 * d.1 < cos_max {
                let len = polyline_length(&pts[start..=i]);
                if len > best_len {
                    best_len = len;
                    (best_start, best_end) = (start, i + 1);
                }
                // The corner vertex belongs to both runs: it is the end of one and the start
                // of the next, so no arc length is lost between them.
                start = i;
            }
        }
        previous = Some(d);
    }
    if polyline_length(&pts[start..]) > best_len {
        (best_start, best_end) = (start, pts.len());
    }
    &pts[best_start..best_end]
}

/// Lay a single shaped line's glyphs along a tile-local `centreline`, centred on the line's
/// length.
///
/// `px_per_font_unit` converts a font-unit advance into the tile-local unit the centreline is
/// measured in (`text_px / UP_EM / tile_span_px`), so the along-line spacing tracks the frame's
/// text size exactly as [`emit`]'s does. Returns one [`CurvedGlyph`] per glyph, or an empty vec
/// when the run is longer than the centreline — it does not fit and the label should not be
/// placed at all, which is what stops a long name spilling off a short road. A half-drawn street
/// name is worse than none: the reader cannot tell it is incomplete.
///
/// The run is measured and laid along the [longest smooth stretch](longest_smooth_run) of the
/// centreline, not the whole of it, so a corner or a join between two parts of the feature
/// neither lends its length to the fit test nor carries any glyph.
pub fn layout_along_line(
    line: &ShapedLine,
    centreline: &[(f32, f32)],
    px_per_font_unit: f32,
) -> Vec<CurvedGlyph> {
    if centreline.len() < 2 || line.glyphs.is_empty() || px_per_font_unit <= 0.0 {
        return Vec::new();
    }
    let smooth = longest_smooth_run(centreline);
    if smooth.len() < 2 {
        return Vec::new();
    }
    let total_len = polyline_length(smooth);
    let run_len = line.advance * px_per_font_unit;
    if run_len <= 0.0 || run_len > total_len {
        return Vec::new();
    }
    // Read the line in whichever direction keeps it upright: a polyline whose net heading points
    // leftwards would otherwise draw every glyph upside down. Reverse the *walk*, not the glyph
    // order, so the run still spells left-to-right on screen.
    //
    // Direction matters for sampling, not just tangents: the old code sampled a
    // REVERSED POINT COPY, so its arc positions, segment boundaries and therefore
    // its pens/chords all live in the reversed frame. The new code replays that
    // frame exactly (virtual copy, same accumulation — see below) instead of
    // mirroring arcs analytically, which is what made analytic mirroring drift by
    // reassociation on multi-segment paths.
    let net_dx = smooth[smooth.len() - 1].0 - smooth[0].0;
    let reversed_walk = net_dx < 0.0;
    // `start` centres the run on the walk: the old code centred on its (possibly
    // reversed) copy with `(total - run)/2`, and both frames share the same total
    // length — so one formula serves both; only the walk DIRECTION differs.
    let start = (total_len - run_len) * 0.5;
    // One em either side: the window each glyph's heading is averaged over. Wide enough that a
    // vertex is inside it for a few consecutive glyphs (so a bend is shared between them rather
    // than taken in one step) and that the direction noise of extent-quantised coordinates
    // averages out, narrow enough that the run still tracks a real curve.
    let half_window = UP_EM as f32 * px_per_font_unit;
    // Forward segment table: lengths and start-arcs in path order. The sampling
    // below is the old `sample_polyline` rescan with a caller-held cursor: glyph
    // pen distances are monotonic in glyph order (pen_x advances left to right),
    // so one walk serves all three samples per glyph instead of three O(n)
    // rescans each — O(segments + glyphs) total. Comparisons, operand order and
    // endpoint arithmetic are the old code's verbatim (bit-identical); only the
    // segment FIND is faster.
    let mut seg_len: Vec<f32> = Vec::with_capacity(smooth.len());
    let mut seg_cum: Vec<f32> = Vec::with_capacity(smooth.len());
    let mut acc = 0.0f32;
    for w in smooth.windows(2) {
        let dx = w[1].0 - w[0].0;
        let dy = w[1].1 - w[0].1;
        let seg = (dx * dx + dy * dy).sqrt();
        seg_len.push(seg);
        seg_cum.push(acc);
        acc += seg;
    }
    // Sampling direction: forward runs walk the table above front-to-back;
    // leftward runs walk the virtual reversed copy back-to-front (below) instead
    // of allocating one — same directed edges, same prefix-sum accumulation order
    // as the old rescan over its copy, so bit-identical there too.
    //
    // Bit-identity contract (pinned by the equivalence harness): every sample
    // below replays the old rescan's arithmetic on the same operands in the same
    // order. The ONLY structural change is the segment find (cursor vs rescan)
    // and the copy's removal (reverse index vs `reversed.collect()`).
    let m = seg_len.len();
    // (`reversed_walk` is defined with `net_dx` above.)
    // The point and unit tangent at forward-arc `d`: the old `sample_polyline`
    // computation verbatim, with a caller-held cursor that makes monotonic `d`
    // amortised O(1) per sample (the behind/ahead window rewinds at most a few
    // segments). Serves forward runs directly; reversed runs use the virtual-copy
    // sampler further below.
    let sample_forward_at = |d: f32, cursor: &mut usize| -> ((f32, f32), (f32, f32)) {
        let d = d.max(0.0);
        // Advance while `d` lies past this segment's end (strict `<`: an exact hit
        // `end == d` stops here with t == 1, exactly the old `>=` return).
        while *cursor + 1 < m && seg_cum[*cursor + 1] + seg_len[*cursor + 1] < d {
            *cursor += 1;
        }
        // Rewind while `d` precedes this segment's start (strict `>`: the scan
        // below skips zero-length segments itself, exactly like the rescan's
        // `continue` — an exact vertex hit shared by degenerate segments simply
        // restarts the scan a few segments back and lands on the FIRST segment
        // ending there, as the rescan would).
        while *cursor > 0 && seg_cum[*cursor] > d {
            *cursor -= 1;
        }
        let mut i = *cursor;
        // Scan forward for the first non-degenerate segment ending at or past `d`.
        loop {
            if seg_len[i] > 0.0 && seg_cum[i] + seg_len[i] >= d {
                // Found: interpolate — the old interior return, same operands.
                let dx = smooth[i + 1].0 - smooth[i].0;
                let dy = smooth[i + 1].1 - smooth[i].1;
                let seg = seg_len[i];
                let t = ((d - seg_cum[i]) / seg).clamp(0.0, 1.0);
                *cursor = i;
                return (
                    (smooth[i].0 + dx * t, smooth[i].1 + dy * t),
                    (dx / seg, dy / seg),
                );
            }
            if i + 1 < m {
                i += 1;
                continue;
            }
            // Past the end (or an all-degenerate line): the last vertex, with the
            // last real tangent — the old fallback's reverse scan, verbatim.
            let last = smooth[m];
            for k in (0..m).rev() {
                if seg_len[k] > 0.0 {
                    let dx = smooth[k + 1].0 - smooth[k].0;
                    let dy = smooth[k + 1].1 - smooth[k].1;
                    let seg = seg_len[k];
                    *cursor = k;
                    return (last, (dx / seg, dy / seg));
                }
            }
            *cursor = 0;
            return (last, (1.0, 0.0));
        }
    };
    // Walk-order views for the virtual reversed copy (derived, never copied).
    // `smooth` vertex `k` of the old copy IS forward vertex `m-k`; its segment `i`
    // runs from forward vertex `m-i` down to `m-i-1` — the same directed edge, so
    // every subtraction below repeats the old copy's verbatim. Its prefix sums
    // accumulate back-to-front over the same lengths in the same order.
    // `rev_cum`, built ONLY on reversed runs (forward runs never read it):
    // `rev_cum[i]` is the old copy's start-arc of its segment `i` — accumulated
    // back-to-front over the same lengths in the same order the old rescan ran.
    //
    // Allocation note: `seg_len`/`seg_cum`/`rev_cum` are three small tables per
    // `layout_along_line` call (one Vec each, m+1 floats at most) — the old code
    // allocated a full reversed point copy PLUS re-walked O(n) per sample, so this
    // is strictly less allocation and asymptotically less arithmetic.
    //
    // `rev_cum` stores the RUNNING back-to-front accumulation — `rev_cum[0] =
    // 0`, `rev_cum[k+1] = rev_cum[k] + seg_len[m-1-k]` — EXACTLY the sequence the
    // old rescan's `acc` took over its copy (same lengths, same order, same
    // per-add rounding). Index m holds the grand total. This is what makes the
    // walk bit-identical rather than merely close: every boundary below equals
    // the old running total bit-for-bit, including its rounding.
    let mut rev_cum: Vec<f32> = Vec::new();
    if reversed_walk {
        rev_cum.push(0.0);
        for k in 0..m {
            let l = seg_len[m - 1 - k];
            // `x + 0.0 == x` bit-exact: degenerate segments leave the running
            // total untouched, exactly like the old rescan's `continue`.
            rev_cum.push(rev_cum[k] + l);
        }
    }
    let sample_reversed_at = |s: f32, cursor: &mut usize| -> ((f32, f32), (f32, f32)) {
        // `s` is ALREADY a copy-frame arc: the caller centres with the shared
        // `(total - run)/2` formula and the virtual copy shares the total length,
        // so no mapping — the sampler reads `s` exactly like the old rescan read
        // its `d`. (Mapping here rather than in the caller keeps every cursor
        // comparison inside one consistent frame.)
        //
        // The old copy's rescan sampled ITS OWN points (reversed) at ITS OWN arc
        // `d`: point = copy_v[i] + (copy_v[i+1] - copy_v[i]) * t with
        // t = (d - acc)/seg. The interpolation below repeats that arithmetic
        // with copy vertices read by reverse index — same points, same tangent.
        // Pens match by shared coordinates; tangents match because the directed
        // edge IS the old edge.
        //
        // NO mirror: `s` is already a copy-frame arc. The caller passes the same
        // `start`-relative distances the old code passed over its copy (`start`
        // is copy-frame: `(total - run)/2` over the shared total length), so the
        // sampler reads them directly — exactly like the old rescan read its `d`.
        // (An earlier revision mirrored here AND centred from the walk's reading
        // end; that double-mapped arcs and the harness caught it.)
        //
        // Clamp order matches the old rescan exactly: `d.max(0.0)` on the
        // copy-frame arc, as the old code clamped its `d`.
        let d = s.max(0.0);
        // Advance while `d` lies past this segment's end (strict `<`, as above).
        // NOTE: the end recomputes from the table — `rev_cum[next] + len(next)`
        // — the same two-operand add the old rescan ran as `acc + seg`, rather
        // than trusting a stored end. (Storing ends directly would accumulate in
        // a different order and could round the boundary by a ulp — which is
        // exactly what flipped segment choice on exact vertex hits.)
        while *cursor + 1 < m {
            let l = seg_len[m - 1 - (*cursor + 1)];
            let end = rev_cum[*cursor + 1] + l;
            if end >= d {
                break;
            }
            *cursor += 1;
        }
        // Rewind while `d` precedes this segment's start (strict `>`, as above).
        // NOTE: a chained cursor can sit several segments past a much smaller `d`
        // (the behind-window sample after the pen, or the next glyph's pen after
        // an ahead sample); the loop rewinds all the way — O(segments) worst case
        // for that sample, still amortised O(1) across the monotonic glyph order.
        // The scan below then converges to the same segment the rescan would
        // return regardless of where the cursor started.
        while *cursor > 0 && rev_cum[*cursor] > d {
            *cursor -= 1;
        }
        let mut i = *cursor;
        loop {
            let j = m - 1 - i;
            let l = seg_len[j];
            // NOTE: `rev_cum[i] + l` recomputes the segment END in closed form —
            // the same two-operand add the old rescan ran as `acc + seg` — rather
            // than trusting a stored end (see above).
            if l > 0.0 && rev_cum[i] + l >= d {
                // The old copy's segment i ran from its vertex i to i+1 — forward
                // vertices (m-i) down to (m-i-1). Copy-frame arithmetic, verbatim:
                // point = copy_v[i] + (copy_v[i+1] - copy_v[i]) * t, tangent =
                // (copy_v[i+1] - copy_v[i]) / l.
                //
                // The pen matches the old code by shared coordinates. The tangent
                // is NEGATED into the forward frame here: the copy's edge runs
                // backwards along the geometry, but the chord above — `ahead -
                // behind` of copy-frame points — already runs forward, and the
                // curved-label tests read forward tangents. (The chord needs no
                // mapping: it is computed from points, not edges.)
                let dx = smooth[m - i - 1].0 - smooth[m - i].0;
                let dy = smooth[m - i - 1].1 - smooth[m - i].1;
                let t = ((d - rev_cum[i]) / l).clamp(0.0, 1.0);
                *cursor = i;
                return (
                    (smooth[m - i].0 + dx * t, smooth[m - i].1 + dy * t),
                    (-dx / l, -dy / l),
                );
            }
            if i + 1 < m {
                i += 1;
                continue;
            }
            // Past the end: the copy's last vertex (forward `smooth[0]`) with the
            // last real reversed tangent — the old fallback's reverse scan over
            // the copy, negated into the forward frame like the interpolation.
            let last = smooth[0];
            for k in 0..m {
                let j2 = m - 1 - k;
                if seg_len[j2] > 0.0 {
                    let dx = smooth[m - k - 1].0 - smooth[m - k].0;
                    let dy = smooth[m - k - 1].1 - smooth[m - k].1;
                    let seg = seg_len[j2];
                    *cursor = k;
                    return (last, (-dx / seg, -dy / seg));
                }
            }
            *cursor = 0;
            return (last, (1.0, 0.0));
        }
    };
    let mut cursor = 0usize;
    let mut out = Vec::with_capacity(line.glyphs.len());
    // `d - half_window` can precede `d` by up to 2 ems, so sample the window first
    // (rewinding), then the centre, then ahead: each call moves the cursor
    // monotonically except for the small rewinds, keeping the walk amortised-linear.
    //
    // Forward runs sample forward arcs through `sample_forward_at`; reversed runs
    // sample the mirrored walk through `sample_reversed_at` — each in the frame
    // whose prefix sums its cursor compares against, so chained advance/rewind
    // stays consistent. The chord keeps the old `ahead - behind` formula verbatim.
    for g in &line.glyphs {
        let d = start + g.pen_x * px_per_font_unit;
        if !reversed_walk {
            let (behind, _) = sample_forward_at(d - half_window, &mut cursor);
            let (pen, segment_tangent) = sample_forward_at(d, &mut cursor);
            let (ahead, _) = sample_forward_at(d + half_window, &mut cursor);
            // The chord across that window, not the tangent of the segment the pen happens to sit
            // on. The segment tangent is a step function of `d`: every glyph on one segment shares
            // an angle, the angle jumps at each vertex, and on a finely-digitised road it jitters
            // with the coordinate quantisation — which is the faceted, individually-rotated look.
            // A chord between two points of a continuous polyline turns continuously.
            let (dx, dy) = (ahead.0 - behind.0, ahead.1 - behind.1);
            let chord = (dx * dx + dy * dy).sqrt();
            let tangent = if chord > 1e-7 { (dx / chord, dy / chord) } else { segment_tangent };
            out.push(CurvedGlyph { glyph: *g, pen, tangent });
            continue;
        }
        // Reversed: `d` is a FORWARD arc (same `start` formula both directions);
        // the sampler maps it into the virtual copy's frame itself, so the
        // cursor's chained advance/rewind compares walk arcs against walk prefix
        // sums in one consistent frame. Sample order is pen, behind, ahead — the
        // old code's own order (each sample converges its cursor independently,
        // so order is perf-only; matching keeps the cursor path closest).
        let (pen, segment_tangent) = sample_reversed_at(d, &mut cursor);
        let (behind, _) = sample_reversed_at(d - half_window, &mut cursor);
        let (ahead, _) = sample_reversed_at(d + half_window, &mut cursor);
        // The chord across that window: `ahead - behind`, the old formula
        // verbatim, in the frame the sampler provides.
        //
        // Frame accounting: the sampler's points are shared tile-local coordinates
        // — the same points the old code sampled from its reversed copy — but the
        // old code's chord `ahead - behind` ran in the COPY's frame, i.e. against
        // the walk direction: on a backwards walk `ahead` (larger walk-arc) sits
        // BEHIND `behind` along the geometry, so `ahead - behind` points along
        // the geometry's FORWARD direction. The new sampler's `ahead`/`behind`
        // are the same two points, so the same subtraction gives the same forward
        // chord. No extra negation: the points already carry the frame.
        let (dx, dy) = (ahead.0 - behind.0, ahead.1 - behind.1);
        let chord = (dx * dx + dy * dy).sqrt();
        let tangent = if chord > 1e-7 { (dx / chord, dy / chord) } else { segment_tangent };
        out.push(CurvedGlyph { glyph: *g, pen, tangent });
    }
    out
}

/// The scale a curved label is emitted at, plus the ground it drapes onto.
///
/// Bundled because the three values are always derived together by the
/// caller (frame text size, tile span, DEM sampler) and never varied
/// independently across the glyphs of one run.
pub struct CurvedScale<'a> {
    /// Text size in device px.
    pub text_px: f32,
    /// The tile's span in device px.
    pub tile_span_px: f32,
    /// Ground height under a tile-local `(x, y)`.
    pub ground: &'a dyn Fn(f32, f32) -> f32,
}

/// Emit two triangles per glyph of a single shaped line laid along a tile-local `centreline`.
///
/// The baseline follows the polyline and every glyph is rotated to its local tangent, so a
/// street or river name curves with its line. This is the counterpart of [`emit`] for a
/// **line** label, and it differs in two deliberate ways:
///
/// * There is no anchor, offset or justification — the run is centred on the polyline's length
///   by [`layout_along_line`].
/// * The caller must **not** pass the result through [`upright`]. A curved label is map-aligned
///   (MapLibre's `text-rotation-alignment: map`): its orientation is the geometry's tangent, not
///   the camera's bearing, so counter-rotating it would fight the curve it is meant to follow.
///
/// Vertical placement mirrors [`emit`]: the baseline sits half a cap-height below the centreline
/// (screen-down), so the cap box straddles the line. Each quad still covers the glyph ink plus
/// the SDF spread and addresses the same atlas cell, so the fragment shader is unchanged.
pub fn emit_curved(
    atlas: &GlyphAtlas,
    weight: Weight,
    line: &ShapedLine,
    centreline: &[(f32, f32)],
    scale: &CurvedScale<'_>,
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
) {
    if scale.tile_span_px <= 0.0 {
        return;
    }
    let px_per_font_unit = scale.text_px / UP_EM as f32 / scale.tile_span_px;
    let placed = layout_along_line(line, centreline, px_per_font_unit);
    // The baseline is half a cap-height below the centreline, so the cap box centres on the line.
    let cap_half = 0.5 * CAP_HEIGHT_EM * UP_EM as f32 * px_per_font_unit;
    for cg in &placed {
        let Some(uv) = atlas.uv(weight, cg.glyph.ch) else { continue };
        let (cos, sin) = cg.tangent;
        // "Up" (toward glyph tops) in y-down tile space is the tangent rotated by -90 degrees.
        let up = (sin, -cos);
        let g = cg.glyph;
        // Local coordinates from the pen origin: `a` along the baseline, `u` up (font units → tile
        // local via px_per_font_unit).
        let a0 = g.bearing_x * px_per_font_unit;
        let a1 = a0 + g.w * px_per_font_unit;
        let u_top = -cap_half + g.top * px_per_font_unit;
        let u_bot = u_top - g.h * px_per_font_unit;
        let corner = |a: f32, u: f32| {
            (cg.pen.0 + cos * a + up.0 * u, cg.pen.1 + sin * a + up.1 * u)
        };
        let (x0, y0) = corner(a0, u_top);
        let (x1, y1) = corner(a1, u_top);
        let (x2, y2) = corner(a1, u_bot);
        let (x3, y3) = corner(a0, u_bot);
        let base = (vertices.len() / FLOATS_PER_VERTEX) as u32;
        // A curved label is map-aligned: each glyph vertex is its own anchor, so the billboard
        // shader collapses to a plain on-ground projection (offset zero) and the run foreshortens
        // with the ground under tilt instead of standing up. The anchor height is sampled at the
        // pen per glyph — the four corners sit within pixels of it — so street names ride the
        // same relief their road drapes onto rather than sinking into hillsides.
        let pen_h = (scale.ground)(cg.pen.0, cg.pen.1);
        vertices.extend_from_slice(&[x0, y0, uv.u0, uv.v0, x0, y0, pen_h]);
        vertices.extend_from_slice(&[x1, y1, uv.u1, uv.v0, x1, y1, pen_h]);
        vertices.extend_from_slice(&[x2, y2, uv.u1, uv.v1, x2, y2, pen_h]);
        vertices.extend_from_slice(&[x3, y3, uv.u0, uv.v1, x3, y3, pen_h]);
        indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
    }
}
