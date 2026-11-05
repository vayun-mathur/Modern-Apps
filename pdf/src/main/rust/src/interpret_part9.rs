fn paint_tiling_pattern(
    doc: &Document,
    obj: &Object,
    dict: &lopdf::Dictionary,
    pmat: &Mat,
    base_argb: u32,
    polys: &[Vec<(f64, f64)>],
    prims: &mut Vec<Prim>,
    depth: u32,
    alpha: f32,
    blend: BlendMode,
) {
    let stream = match obj {
        Object::Stream(s) => s,
        _ => return,
    };
    let paint_type = dict.get(b"PaintType").ok().and_then(num).unwrap_or(1.0) as i64;
    let bbox = dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)).unwrap_or([0.0, 0.0, 1.0, 1.0]);
    let xstep = dict.get(b"XStep").ok().and_then(num).unwrap_or(bbox[2] - bbox[0]);
    let ystep = dict.get(b"YStep").ok().and_then(num).unwrap_or(bbox[3] - bbox[1]);
    // The tile lattice spacing is a magnitude; a negative /XStep or /YStep made
    // i0 > i1 so the loop body never ran and the pattern painted nothing.
    let xstep = xstep.abs();
    let ystep = ystep.abs();
    // §8.7.4.1: `sh` fills the whole current clip, and `rasterize_shading` paints NOTHING
    // for a shading with no `/BBox` of its own when handed no clip extent. §8.7.3.1 clips a
    // cell to the pattern `/BBox`, so that box is the extent for every path below that
    // interprets the cell. Two of them are the malformed-pattern fallbacks, which paint the
    // cell once at `pmat`; the third (the periodic-raster path) works in cell space and
    // needs the box unmapped, computed separately at its own site.
    let cell_bbox_device = quad_device_bbox(&[
        transform(pmat, bbox[0], bbox[1]),
        transform(pmat, bbox[2], bbox[1]),
        transform(pmat, bbox[2], bbox[3]),
        transform(pmat, bbox[0], bbox[3]),
    ]);
    // Zero-step pattern is malformed — show bbox once instead of blanking
    if xstep.abs() < 1e-6 || ystep.abs() < 1e-6 {
        let res = dict
            .get(b"Resources")
            .ok()
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
            .cloned();
        // Same all-or-nothing hazard as a page or a form: one inline image lopdf
        // rejects would otherwise blank the whole cell.
        let cell_ops = crate::content::stream_operations(doc, stream);
        if !cell_ops.is_empty() {
            let mut tile_gs = GraphicsState { ctm: *pmat, alpha_fill: alpha as f64, alpha_stroke: alpha as f64, blend_mode: blend, ..GraphicsState::default() };
            if paint_type == 2 { tile_gs.fill = base_argb; tile_gs.stroke = base_argb; }
            interpret_content_seeded(doc, &cell_ops, res.as_ref(), tile_gs, prims, depth + 1, false, cell_bbox_device);
        }
        return;
    }
    let res = dict
        .get(b"Resources")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .cloned();
    let content_ops = crate::content::stream_operations(doc, stream);
    if content_ops.is_empty() {
        return;
    }

    // Device-space bounding box of the fill region.
    let (mut minx, mut miny, mut maxx, mut maxy) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
    for poly in polys {
        for &(x, y) in poly {
            minx = minx.min(x);
            miny = miny.min(y);
            maxx = maxx.max(x);
            maxy = maxy.max(y);
        }
    }
    if !minx.is_finite() {
        return;
    }

    // Map that box into pattern space to bound the tile index range.
    let inv = mat_inverse(pmat);
    // Singular pattern matrix — degrade to single tile instead of blank.
    if (inv[0]*inv[3] - inv[1]*inv[2]).abs() < 1e-12 {
        let mut tile_gs = GraphicsState { ctm: *pmat, alpha_fill: alpha as f64, alpha_stroke: alpha as f64, blend_mode: blend, ..GraphicsState::default() };
        if paint_type == 2 { tile_gs.fill = base_argb; tile_gs.stroke = base_argb; }
        interpret_content_seeded(doc, &content_ops, res.as_ref(), tile_gs, prims, depth + 1, false, cell_bbox_device);
        return;
    }
    let (mut pminx, mut pminy, mut pmaxx, mut pmaxy) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
    for (x, y) in [(minx, miny), (maxx, miny), (minx, maxy), (maxx, maxy)] {
        let (px, py) = transform(&inv, x, y);
        pminx = pminx.min(px);
        pminy = pminy.min(py);
        pmaxx = pmaxx.max(px);
        pmaxy = pmaxy.max(py);
    }
    let i0 = ((pminx - bbox[2]) / xstep).floor() as i64;
    let i1 = ((pmaxx - bbox[0]) / xstep).ceil() as i64;
    let j0 = ((pminy - bbox[3]) / ystep).floor() as i64;
    let j1 = ((pmaxy - bbox[1]) / ystep).ceil() as i64;
    let total_i = (i1 - i0 + 1).max(0);
    let total_j = (j1 - j0 + 1).max(0);

    // §8.7.3.3 requires the cell replicated across the WHOLE region. Rasterizing
    // the cell ONCE and emitting a periodic bitmap makes the tile count
    // irrelevant, which is the only way to satisfy that: the per-tile path below
    // has to cap the count, and any cap leaves part of a large hatched region
    // blank.
    //
    // Restricted to cells made only of fills and strokes, because
    // `rasterize_prims_to_rgba` has no glyph rasterizer and ignores images, clips
    // and groups — a cell containing any of those would silently lose it, so it
    // keeps the per-tile path. `rasterize_pattern_cell` owns the other gates (the
    // period being the step and not the bbox, and bounding the copies-per-period
    // needed to honour §8.7.3.1 overlap).
    let mut cell_prims: Vec<Prim> = Vec::new();
    let mut cell_gs = GraphicsState {
        ctm: IDENTITY,
        // §11.6.7 treats the pattern as a transparency group: alpha and blend ride
        // on the composited result, not on each element inside the cell.
        alpha_fill: 1.0,
        alpha_stroke: 1.0,
        ..GraphicsState::default()
    };
    if paint_type == 2 { cell_gs.fill = base_argb; cell_gs.stroke = base_argb; }
    // This cell is interpreted at IDENTITY, so its "device" space IS pattern space and the
    // extent is the `/BBox` unmapped. Seeding it is not cosmetic: it decides the GATE below.
    // Unseeded, a cell whose only content is `sh` produced no Image prim, `cell_prims` came
    // out all-Fill/Stroke, the periodic-raster path was taken, and the gradient was silently
    // dropped from every tile. Seeded, the Image appears, the gate correctly rejects it, and
    // the per-tile path (also seeded) paints it.
    let cell_space_bbox = quad_device_bbox(&[
        (bbox[0], bbox[1]),
        (bbox[2], bbox[1]),
        (bbox[2], bbox[3]),
        (bbox[0], bbox[3]),
    ]);
    interpret_content_seeded(doc, &content_ops, res.as_ref(), cell_gs, &mut cell_prims, depth + 1, false, cell_space_bbox);
    if !cell_prims.is_empty()
        && cell_prims.iter().all(|p| matches!(p, Prim::Fill { .. } | Prim::Stroke { .. }))
        && prims.len() < MAX_PRIMITIVES
    {
        // Pattern-space -> device scale, so the cell is rasterized at display
        // resolution instead of an arbitrary fixed size.
        let sx = (pmat[0] * pmat[0] + pmat[1] * pmat[1]).sqrt();
        let sy = (pmat[2] * pmat[2] + pmat[3] * pmat[3]).sqrt();
        if let Some((cw, ch, data)) =
            rasterize_pattern_cell(&cell_prims, bbox, xstep, ystep, sx.max(sy))
        {
            // The unit square maps onto ONE CELL — one period of the lattice —
            // anchored at the bbox origin, matching `rasterize_pattern_cell`'s
            // step rect.
            let cell_mat: Mat = [xstep, 0.0, 0.0, ystep, bbox[0], bbox[1]];
            // For a PERIODIC bitmap the extent is counted in whole periods
            // relative to the region: cell `i` spans pattern x in
            // [bbox[0] + i*xstep, bbox[0] + (i+1)*xstep). That differs from the
            // per-tile loop's `i0`/`i1`, which are in bbox-overlap terms and
            // deliberately start a cell early — here that would report an extent
            // a period wider than the region on every side.
            let ti0 = ((pminx - bbox[0]) / xstep).floor();
            let tj0 = ((pminy - bbox[1]) / ystep).floor();
            let tnx = (((pmaxx - bbox[0]) / xstep).ceil() - ti0).max(1.0);
            let tny = (((pmaxy - bbox[1]) / ystep).ceil() - tj0).max(1.0);
            prims.push(Prim::ImageTiled {
                ctm: mat_mul(&cell_mat, pmat),
                w: cw,
                h: ch,
                data,
                xstep: xstep as f32,
                ystep: ystep as f32,
                i0: ti0.clamp(i32::MIN as f64, i32::MAX as f64) as i32,
                j0: tj0.clamp(i32::MIN as f64, i32::MAX as f64) as i32,
                nx: tnx.clamp(1.0, u32::MAX as f64) as u32,
                ny: tny.clamp(1.0, u32::MAX as f64) as u32,
                alpha,
                blend,
            });
            return;
        }
    }

    // Per-tile fallback. Reached when the cell contains text, an image or its own
    // clipping (see the gate above), and when `rasterize_pattern_cell` declines:
    // a degenerate step, or a `/BBox` so much larger than the step that honouring
    // §8.7.3.1 overlap would need more than its copies-per-period budget. Plain
    // overlap is NOT a fallback case — that path composites the cell at each
    // reaching lattice offset and stays periodic.
    //
    // Each cell is REPLAYED as primitives here, so the count has to be capped.
    const MAX_TILES: i64 = 20_000;
    // §8.7.3.3 requires the cell replicated across the WHOLE region, so when the
    // lattice exceeds the budget, thin it out UNIFORMLY. Taking a dense square
    // patch anchored at one corner instead — which is what this used to do — left
    // the rest of the region empty, and a 2pt lattice over a 400x400 region is
    // 40,401 tiles, so it covered barely half. A lower-density cell over the whole
    // region still reads as the texture that was asked for; a correct patch beside
    // a blank area reads as missing content.
    //
    // f64 for the product: `i1`/`j1` come from a division by a step that may be
    // tiny, so `total_i * total_j` can overflow `i64` and panic in a debug build.
    let need = total_i as f64 * total_j as f64;
    let stride = if need > MAX_TILES as f64 {
        ((need / MAX_TILES as f64).sqrt().ceil() as i64).max(1)
    } else {
        1
    };
    let mut count = 0i64;
    'outer: for j in (j0..=j1).step_by(stride as usize) {
        for i in (i0..=i1).step_by(stride as usize) {
            if count >= MAX_TILES || prims.len() >= MAX_PRIMITIVES {
                break 'outer;
            }
            count += 1;
            let translate: Mat = [1.0, 0.0, 0.0, 1.0, i as f64 * xstep, j as f64 * ystep];
            let tile_ctm = mat_mul(&translate, pmat);
            // Clip each cell to the pattern /BBox (PDF 8.7.3.1) so content that
            // overflows the cell — or a cell smaller than XStep/YStep — cannot
            // bleed into neighboring cells.
            let bc = [
                transform(&tile_ctm, bbox[0], bbox[1]),
                transform(&tile_ctm, bbox[2], bbox[1]),
                transform(&tile_ctm, bbox[2], bbox[3]),
                transform(&tile_ctm, bbox[0], bbox[3]),
            ];
            let cell_pts: Vec<(f32, f32)> = bc.iter().map(|&(x, y)| (x as f32, y as f32)).collect();
            let cell_po = vec![
                PathOp::Move(bc[0].0 as f32, bc[0].1 as f32),
                PathOp::Line(bc[1].0 as f32, bc[1].1 as f32),
                PathOp::Line(bc[2].0 as f32, bc[2].1 as f32),
                PathOp::Line(bc[3].0 as f32, bc[3].1 as f32),
                PathOp::Close,
            ];
            prims.push(Prim::ClipPush { even_odd: false, pts: cell_pts, path_ops: Some(cell_po) });
            let mut tile_gs = GraphicsState { ctm: tile_ctm, alpha_fill: alpha as f64, alpha_stroke: alpha as f64, blend_mode: blend, ..GraphicsState::default() };
            if paint_type == 2 {
                tile_gs.fill = base_argb;
                tile_gs.stroke = base_argb;
            }
            // §8.7.3.1 clips the cell to the pattern `/BBox`, which is therefore the
            // clip extent a `sh` inside the cell must fill (§8.7.4.1). Without it
            // `rasterize_shading` declines and a gradient-filled hatch cell paints
            // nothing — the `bc` corners just used for the ClipPush are that extent.
            let cell_clip = quad_device_bbox(&bc);
            interpret_content_seeded(doc, &content_ops, res.as_ref(), tile_gs, prims, depth + 1, false, cell_clip);
            prims.push(Prim::ClipPop);
        }
    }
}

/// Read a 6-element matrix from an array object.
pub(crate) fn read_matrix_obj(obj: &Object) -> Option<Mat> {
    match obj {
        Object::Array(a) => read_matrix(a),
        _ => None,
    }
}

/// Read a 4-number array (e.g. `/Rect`, `/BBox`) resolving references.
pub(crate) fn read_rect(doc: &Document, obj: &Object) -> Option<[f64; 4]> {
    let arr = deref(doc, obj)?.as_array().ok()?;
    if arr.len() != 4 {
        return None;
    }
    let mut out = [0.0; 4];
    for (i, v) in arr.iter().enumerate() {
        out[i] = deref(doc, v).and_then(num)?;
    }
    // §7.9.5 defines a rectangle as four NUMBERS; NaN and the infinities are not
    // numbers a rectangle can be made of. Rejecting the whole rect rather than
    // patching a component matches the `cm` arm's treatment of a non-finite CTM,
    // and for the same reason: one poisoned coordinate propagates through every
    // transform derived from it, and the rasterizer drops a path containing a
    // NaN point silently, so a region of the page vanishes with no error
    // anywhere. Every caller already handles `None` — as an absent /BBox, an
    // unclipped form, or a skipped annotation — which are all visibly wrong in
    // the way a malformed file should be, instead of invisibly wrong.
    if out.iter().all(|v| v.is_finite()) {
        Some(out)
    } else {
        None
    }
}

#[cfg(test)]
mod refdiff_followup_tests {
    use crate::*;
    use lopdf::content::Operation;
    use lopdf::{dictionary, Stream};

    fn op(name: &str, operands: Vec<Object>) -> Operation {
        Operation::new(name, operands)
    }

    fn clip_applies(prims: &[Prim]) -> usize {
        prims.iter().filter(|p| matches!(p, Prim::TextClipApply)).count()
    }

    /// §9.4.3: the text clip accumulates the outlines of the glyphs SHOWN. The
    /// `TJ` arm latched `text_clip_used` OUTSIDE its array destructure, so
    /// `7 Tr [] TJ` — and any `TJ` whose operand is not an array at all —
    /// claimed a clip built from no glyphs, which `ET` then applied. The sibling
    /// `Tj`/`'`/`"` arms all latch inside a successful string destructure.
    ///
    /// This matters far more since the consumer stopped ignoring an empty text
    /// clip and started clipping to NOTHING, which is what §9.4.3 requires: a
    /// spurious latch now blanks the page rather than being quietly absorbed.
    #[test]
    fn tj_does_not_latch_a_text_clip_when_it_shows_no_glyphs() {
        let mut doc = Document::with_version("1.7");
        let font = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
        });
        let res = dictionary! { "Font" => dictionary! { "F1" => Object::Reference(font) } };
        let run = |body: Vec<Operation>| {
            let mut ops = vec![
                op("BT", vec![]),
                op("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
                op("Tr", vec![7.into()]),
            ];
            ops.extend(body);
            ops.push(op("ET", vec![]));
            let mut prims = Vec::new();
            interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
            prims
        };

        for (what, body) in [
            ("an empty array", vec![op("TJ", vec![Object::Array(vec![])])]),
            (
                "adjustments only",
                vec![op("TJ", vec![Object::Array(vec![Object::Integer(-500)])])],
            ),
            ("a non-array operand", vec![op("TJ", vec![Object::Integer(0)])]),
            ("no operand at all", vec![op("TJ", vec![])]),
        ] {
            assert_eq!(clip_applies(&run(body)), 0, "TJ with {what} showed no glyphs, so no clip");
        }

        // The converse: a TJ that DOES show a glyph must still latch, or the fix
        // would have deleted the feature instead of bounding it.
        let prims = run(vec![op(
            "TJ",
            vec![Object::Array(vec![
                Object::string_literal("A"),
                Object::Integer(-200),
                Object::string_literal("B"),
            ])],
        )]);
        assert_eq!(clip_applies(&prims), 1, "a TJ that shows glyphs must still clip");

        // A WHITESPACE-ONLY run must also latch. It delivers a record, so the
        // consumer accumulates — and a space has no contours, so it accumulates an
        // EMPTY path and clips to nothing. That is §9.4.3, and it is the case the
        // consumer's unconditional clipPath was changed to serve; latching on
        // delivery rather than on outline area is what keeps it reachable.
        assert_eq!(
            clip_applies(&run(vec![op("Tj", vec![Object::string_literal("   ")])])),
            1,
            "a whitespace-only Tr 7 run must still emit the marker"
        );
    }

    /// §9.6.5 Table 113: after `d1` a glyph description "shall not specify any
    /// colour or other colour-related parameters"; if it does, they SHALL BE
    /// IGNORED and the glyph painted with the current text-state colour. A
    /// CharProc doing `1 1 1 rg` after its `d1` otherwise paints white on white.
    ///
    /// Unreachable until `content::repair_d0_d1` made `d1` an operator lopdf can
    /// actually produce, so this is also the regression test for that arm being
    /// live rather than dead code.
    #[test]
    fn colour_operators_after_d1_are_ignored() {
        let doc = Document::with_version("1.7");
        let red = rgb_to_argb(1.0, 0.0, 0.0);
        let glyph = vec![
            op("d1", vec![0.into(), 0.into(), 0.into(), 0.into(), 750.into(), 750.into()]),
            op("rg", vec![1.into(), 1.into(), 1.into()]),
            op("re", vec![0.into(), 0.into(), 100.into(), 100.into()]),
            op("f", vec![]),
        ];
        let mut gs = GraphicsState::default();
        gs.fill = red;
        let mut prims = Vec::new();
        interpret_content(&doc, &glyph, None, gs.clone(), &mut prims, 0, false);
        let fills: Vec<u32> = prims
            .iter()
            .filter_map(|p| match p {
                Prim::Fill { argb, .. } => Some(*argb),
                _ => None,
            })
            .collect();
        assert_eq!(fills, vec![red], "the `1 1 1 rg` inside a d1 glyph must be ignored");

        // `d0` carries no such rule, and neither does a page stream: the
        // suppression must not leak outside a d1 glyph description.
        let mut d0_glyph = glyph.clone();
        d0_glyph[0] = op("d0", vec![0.into(), 0.into()]);
        let mut prims = Vec::new();
        interpret_content(&doc, &d0_glyph, None, gs.clone(), &mut prims, 0, false);
        assert!(
            prims.iter().any(|p| matches!(p, Prim::Fill { argb, .. } if *argb == rgb_to_argb(1.0, 1.0, 1.0))),
            "d0 imposes no colour rule, so `1 1 1 rg` must take effect"
        );
    }
}