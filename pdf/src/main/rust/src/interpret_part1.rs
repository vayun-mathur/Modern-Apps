pub(crate) fn interpret_page(doc: &Document, page_id: ObjectId) -> Result<PageData, String> {
    let (width, height) = page_display_size(doc, page_id);
    let base = page_base_matrix(doc, page_id);

    // `fonts_from_resources` runs on EVERY `interpret_content` call — the page,
    // every form XObject it reaches, every tiling-pattern cell and every
    // annotation appearance stream — and re-parses the whole embedded font
    // program each time. One scope per page collapses that to once per font.
    let _font_cache = crate::FontCacheScope::new();

    // §7.7.3.3: /Contents is optional, and a tokenizer failure must not lose the
    // whole page. `page_operations` returns lopdf's strict parse unchanged when it
    // succeeds and only re-tokenizes leniently when it fails, which is the
    // all-or-nothing inline-image case (§8.9.7) that used to blank a whole page.
    let (ops, recovered) = crate::content::page_operations(doc, page_id);
    if recovered && cfg!(debug_assertions) {
        eprintln!(
            "[pdf_render/interpret] page {page_id:?}: strict content parse failed, \
             recovered {} operations leniently",
            ops.len()
        );
    }
    let res = resources_dict(doc, page_id);

    let mut prims = Vec::new();
    let init = GraphicsState { ctm: base, ..Default::default() };
    // One budget for the whole page, annotations included. Each appearance
    // stream is a separate top-level entry into the interpreter, so without this
    // outer scope a page carrying N annotations would get N+1 full budgets —
    // the branching blow-up [`MAX_FORM_INVOCATIONS`] exists to bound, multiplied
    // by however many annotations the file declares.
    let _form_budget = FormBudgetScope::enter();
    // §8.7.4.1: with no clipping path, `sh` paints across the whole page, so seed
    // the clip extent with the page box. Prims are emitted in page space, so that
    // box is simply [0, 0, width, height].
    interpret_content_seeded(
        doc,
        &ops,
        res.as_ref(),
        init,
        &mut prims,
        0,
        false,
        Some([0.0, 0.0, width as f64, height as f64]),
    );
    render_annotations(doc, page_id, &base, &mut prims);

    Ok(PageData {
        width,
        height,
        prims,
    })
}

/// Interpret a content stream (`ops`) against a `resources` dictionary into
/// drawing primitives, starting from `init` graphics state. Reused for page
/// content, form XObjects (`Do`), and annotation appearance streams. `depth`
/// bounds recursion through nested form XObjects.
pub(crate) fn interpret_content(
    doc: &Document,
    ops: &[lopdf::content::Operation],
    resources: Option<&lopdf::Dictionary>,
    init: GraphicsState,
    prims: &mut Vec<Prim>,
    depth: u32,
    text_only: bool,
) {
    interpret_content_seeded(doc, ops, resources, init, prims, depth, text_only, None);
}

/// As [`interpret_content`], but seeds the initial device-space clip extent.
/// Only the page-level caller has a meaningful starting clip region (the page
/// box); nested streams start with none.
#[allow(clippy::too_many_arguments)]
pub(crate) fn interpret_content_seeded(
    doc: &Document,
    ops: &[lopdf::content::Operation],
    resources: Option<&lopdf::Dictionary>,
    init: GraphicsState,
    prims: &mut Vec<Prim>,
    depth: u32,
    text_only: bool,
    init_clip_bbox: Option<[f64; 4]>,
) {
    // `mut` for the ExtGState `/Font` arm (§8.4.5 Table 58), which references a font
    // dictionary directly instead of through a resource name and so has to register it.
    let fonts = resources
        .map(|r| fonts_from_resources(doc, r))
        .unwrap_or_default();
    let xobjects = resources
        .map(|r| xobjects_from_resources(doc, r))
        .unwrap_or_default();
    let extgstates = resources
        .map(|r| extgstates_from_resources(doc, r))
        .unwrap_or_default();
    let colorspaces = resources
        .map(|r| colorspaces_from_resources(doc, r))
        .unwrap_or_default();
    let shadings = resources
        .map(|r| shadings_from_resources(doc, r))
        .unwrap_or_default();
    let patterns = resources
        .map(|r| patterns_from_resources(doc, r))
        .unwrap_or_default();

    let mut st = SeededInterp {
        gs: init.clone(),
        pattern_base_ctm: init.ctm,
        stack: Vec::new(),
        q_overflow: 0,
        text_matrix: IDENTITY,
        line_matrix: IDENTITY,
        subpaths: Vec::new(),
        cur_user: (0.0, 0.0),
        start_user: (0.0, 0.0),
        subpath_closed: false,
        subpath_dropped: false,
        oc_stack: Vec::new(),
        oc_overflow: 0,
        group_depth: 0,
        pending_clip: None,
        mask_bracket: None,
        oc_cache: HashMap::new(),
        oc_config: None,
        current_clip_bbox: init_clip_bbox,
        clip_depth: 0,
        clip_path_ops: Vec::new(),
        text_clip_used: false,
        fonts,
        xobjects,
        extgstates,
        colorspaces,
        shadings,
        patterns,
        type3_shape_only: ops.first().is_some_and(|op| op.operator == "d1"),
    };
    // Establishes the form-invocation budget when this is the outermost
    // interpretation on the thread, at whatever `depth` it was entered with.
    // See [`FormBudgetScope`].
    let _form_budget = FormBudgetScope::enter();
    // §9.6.5: "The glyph description shall begin with either the d0 or the d1
    // operator." A `d1` glyph is SHAPE ONLY — Table 113 requires any colour or
    // colour-related parameters it specifies to be IGNORED and the glyph painted
    // with the current text-state colour. Without this a CharProc that does
    // `1 1 1 rg` after its `d1` paints white on white: invisible, no crash, no log.
    //
    // Keyed on the leading operator rather than a threaded parameter because `d1`
    // is legal nowhere else, so its presence at index 0 IS the signal, and reading
    // it here needs no change at the four call sites. Unreachable until
    // `content::repair_d0_d1` restored `d1` as an operator lopdf can produce.
    for op in ops.iter().take(MAX_CONTENT_OPS) {
        let o = &op.operands;
        if st.type3_shape_only
            && matches!(
                op.operator.as_str(),
                "g" | "rg" | "k" | "cs" | "sc" | "scn" | "G" | "RG" | "K" | "CS" | "SC" | "SCN"
            )
        {
            continue;
        }
        match op.operator.as_str() {
            "q" | "Q" | "cm" | "w" | "J" | "j" | "M" | "i" | "d" | "gs" => st.handle_state_ops(op, o, doc, resources, prims, text_only),
            "W" | "W*" | "m" | "l" | "c" | "v" | "y" | "re" | "h" => st.handle_path_ops(op, o, doc, prims, text_only),
            "S" | "s" | "f" | "F" | "f*" | "B" | "B*" | "b" | "b*" | "n" | "BI" => st.handle_paint_ops(op, o, HandlerCtx { doc, resources, prims, depth, text_only }),
            "Do" => st.handle_do_op(op, o, HandlerCtx { doc, resources, prims, depth, text_only }),
            "rg" | "RG" | "g" | "G" | "k" | "K" | "CS" | "cs" | "SC" | "sc" | "SCN" | "scn" | "sh" | "BMC" | "BDC" | "MP" | "DP" | "EMC" | "d0" | "d1" => st.handle_color_ops(op, o, HandlerCtx { doc, resources, prims, depth, text_only }),
            "BT" | "ET" | "Tf" | "TL" | "Tc" | "Tw" | "Tz" | "Ts" | "Tr" | "Td" | "TD" | "Tm" | "T*" | "Tj" | "'" | "\"" | "TJ" | "ri" | "BX" | "EX" | "EI" => st.handle_text_ops(op, o, HandlerCtx { doc, resources, prims, depth, text_only }),
            _ => {}
        }
    }
    while st.group_depth > 0 { if !text_only { prims.push(Prim::GroupPop); } st.group_depth-=1; }
    while st.clip_depth > 0 {
        if !text_only {
            prims.push(Prim::ClipPop);
        }
        st.clip_depth -= 1;
    }
}

    #[derive(Clone)]
    struct SavedState {
        gs: GraphicsState,
        clip_depth: usize,
        group_depth: usize,
        clip_bbox: Option<[f64; 4]>,
    }

    struct PendingClip {
        even_odd: bool,
        polys: Vec<Vec<(f64,f64)>>,
        path_ops: Vec<PathOp>,
    }

    // single ClipPush per W op preserving holes via full path_ops (fix high #7).
    // Also intersects the new clip's device-space bbox into `clip_bbox` so that
    // later `sh` operators know the current clip region even after `W n` commits.
    //
    // Deliberately NOT gated on optional-content visibility. §8.11.3.3 makes an
    // OFF group's content undrawn, and §8.5.3 Table 60 lists the PAINTING
    // operators; `W`/`W*` are clipping-path operators (§8.5.4) and `n` paints
    // nothing, so a clip is a graphics-state change (§8.4.1 Table 52) and not a
    // mark. A clip set inside BDC/EMC survives the EMC and bounds the VISIBLE
    // content after it — exactly like the `q`, `cm`, `gs` and colour operators in
    // the same hidden run, none of which are suppressed here either. Dropping it
    // let that later content paint unclipped, which is the direction that puts ink
    // where the file said there should be none.
    #[inline]
    fn emit_one_clip(prims: &mut Vec<Prim>, pc: PendingClip, clip_depth: &mut usize, clip_bbox: &mut Option<[f64;4]>, text_only: bool) {
        if text_only { return; }
        if *clip_depth >= MAX_CLIP_DEPTH { return; }
        if pc.polys.is_empty() && pc.path_ops.is_empty() { return; }
        // Intersect the accumulated clip bbox with this clip's device bbox.
        let mut nx0 = f64::INFINITY; let mut ny0 = f64::INFINITY;
        let mut nx1 = f64::NEG_INFINITY; let mut ny1 = f64::NEG_INFINITY;
        for poly in &pc.polys {
            for &(x, y) in poly {
                nx0 = nx0.min(x); ny0 = ny0.min(y);
                nx1 = nx1.max(x); ny1 = ny1.max(y);
            }
        }
        if nx1 > nx0 && ny1 > ny0 {
            *clip_bbox = Some(match *clip_bbox {
                Some(cur) => [cur[0].max(nx0), cur[1].max(ny0), cur[2].min(nx1), cur[3].min(ny1)],
                None => [nx0, ny0, nx1, ny1],
            });
        }
        let pts: Vec<(f32,f32)> = pc.polys.first().map(|p| p.iter().map(|&(x,y)| (x as f32, y as f32)).collect()).unwrap_or_default();
        let po = if pc.path_ops.is_empty() { None } else { Some(pc.path_ops) };
        prims.push(Prim::ClipPush { even_odd: pc.even_odd, pts, path_ops: po });
        *clip_depth += 1;
    }

    // Begin the subpath §8.5.2.1 requires after a close, at the closepoint.
    // Returns false when the cap refuses it.
    fn reopen_subpath(
        subpaths: &mut Vec<Vec<(f64, f64)>>,
        clip_path_ops: &mut Vec<PathOp>,
        at: (f64, f64),
    ) -> bool {
        if subpaths.len() >= MAX_SUBPATHS {
            return false;
        }
        subpaths.push(vec![at]);
        clip_path_ops.push(PathOp::Move(at.0 as f32, at.1 as f32));
        true
    }

    #[derive(PartialEq, Eq, Hash)]
    enum OcKey {
        Named(Vec<u8>),
        Ref(ObjectId),
    }

fn dev(gs: &GraphicsState, x: f64, y: f64) -> (f64, f64) {
    transform(&gs.ctm, x, y)
}

/// Mutable walk state for [`interpret_content_seeded`], bundled so the
/// per-operator match arms can live in helper methods (each under the
/// 500-line split limit) instead of one 1500-line function body.
struct SeededInterp {
    gs: GraphicsState,
    pattern_base_ctm: Mat,
    stack: Vec<SavedState>,
    q_overflow: usize,
    text_matrix: Mat,
    line_matrix: Mat,
    subpaths: Vec<Vec<(f64, f64)>>,
    cur_user: (f64, f64),
    start_user: (f64, f64),
    subpath_closed: bool,
    subpath_dropped: bool,
    oc_stack: Vec<bool>,
    oc_overflow: usize,
    group_depth: usize,
    pending_clip: Option<PendingClip>,
    mask_bracket: Option<MaskBracket>,
    oc_cache: HashMap<OcKey, bool>,
    oc_config: Option<OcConfig>,
    current_clip_bbox: Option<[f64; 4]>,
    clip_depth: usize,
    clip_path_ops: Vec<PathOp>,
    text_clip_used: bool,
    fonts: HashMap<Vec<u8>, FontInfo>,
    xobjects: HashMap<Vec<u8>, ObjectId>,
    extgstates: HashMap<Vec<u8>, ObjectId>,
    colorspaces: HashMap<Vec<u8>, ObjectId>,
    shadings: HashMap<Vec<u8>, ObjectId>,
    patterns: HashMap<Vec<u8>, ObjectId>,
    type3_shape_only: bool,
}