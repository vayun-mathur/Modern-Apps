/// The budget assertion is only meaningful if a normal page is far inside it.
/// This measures real seed renders against the calibrated mutant budget and
/// prints both, so the headroom is visible in the log rather than asserted on
/// faith. It also reports each seed as a multiple of the reference page, which is
/// the unit the budgets are actually expressed in.
#[test]
fn a_normal_page_renders_far_inside_the_budget() {
    let reference = reference_render();
    let seeds = seeds();
    let mut worst = Duration::ZERO;
    for (name, bytes) in &seeds {
        let doc = load_document_lenient(bytes).expect("seed must load");
        let page_id = *doc.get_pages().values().next().unwrap();
        // Warm the font cache the way a real session would, then measure.
        let _ = interpret_page(&doc, page_id);
        let t0 = Instant::now();
        let page = interpret_page(&doc, page_id).expect("seed must interpret");
        let _ = crate::wire::serialize(&page);
        let dt = t0.elapsed();
        eprintln!(
            "[robustness] seed {name}: one page render = {dt:?} ({:.2}x reference)",
            dt.as_secs_f64() / reference.as_secs_f64().max(f64::MIN_POSITIVE)
        );
        worst = worst.max(dt);
    }
    let b = mutant_budget();
    let headroom = b / 10;
    eprintln!(
        "[robustness] reference = {reference:?}, mutant budget = {b:?} \
         ({MUTANT_BUDGET_X}x reference, floor {MUTANT_BUDGET_FLOOR:?})"
    );
    assert!(
        worst < headroom,
        "a normal page took {worst:?}, which is not comfortably inside \
         mutant_budget()/10 = {headroom:?}. Either the renderer regressed badly \
         or MUTANT_BUDGET_X needs raising — do NOT raise it without saying why."
    );
}

/// REGRESSION TEST — page RENDERING must not inherit the caller's stack.
///
/// The open path was pinned to an explicit worker stack for finding A; rendering
/// was not, even though `interpret_page` recurses through form XObjects, tiling
/// and shading patterns, soft-mask groups and Type 3 glyphs. Those are each
/// depth-capped (`MAX_GROUP_DEPTH` 10, `MAX_PATTERN_RECURSION` 4), so the depth is
/// a small constant — but the frames are large, and the headroom was still a
/// property of whichever thread called in. On Android that is a JNI thread with a
/// fraction of a desktop main thread's stack, which is the asymmetry that made the
/// same file open on a workstation and kill the app on a phone.
///
/// FIXED: `docedit::render_page` runs `interpret_page` on a worker with
/// `RENDER_STACK_BYTES`, so the caller's stack no longer bounds the render.
///
/// This drives the real entry point from a deliberately SMALL (256 KiB) thread,
/// nesting forms, a pattern and a soft mask together. Verified non-vacuous: with
/// the worker removed, this same case exits the test binary with
/// `STATUS_STACK_OVERFLOW` (0xc00000fd) rather than failing an assertion — the
/// process death is inherent to the hazard (see `WORKER_STACK`), which is why the
/// assertion here is "rendered non-empty", not "did not overflow".
#[test]
fn rendering_does_not_depend_on_the_calling_threads_stack() {
    let pdf = nested_forms_pattern_and_soft_mask_pdf();

    // 256 KiB: far below anything the interpreter could recurse through on its own,
    // and far below an Android JNI thread. If the render inherited it, this would
    // not return.
    let v = guarded_on_stack(
        "render from a small stack",
        construct_budget(),
        256 * 1024,
        move || {
            let handle = open_document(&pdf);
            assert_ne!(handle, 0, "the constructed document must open");
            let rendered = render_page(handle, 0);
            close_document(handle);
            assert!(
                rendered.is_some_and(|b| !b.is_empty()),
                "nested forms / pattern / soft mask must still render"
            );
        },
    );
    assert!(!v.is_failure(), "{v:?}");
}

/// A page that drives every recursive interpreter path at once: a form-XObject
/// chain deeper than the interpreter's own cap (so the cap, not the stack, is
/// what stops it), a tiling pattern, and a luminosity soft mask whose group
/// re-enters the chain. Shared by the three small-stack regression tests, which
/// differ only in which entry point they drive.
fn nested_forms_pattern_and_soft_mask_pdf() -> Vec<u8> {
    let mut objects: Vec<(u32, Vec<u8>)> = Vec::new();
    const CHAIN: u32 = 24;
    for k in 0..CHAIN {
        let id = 10 + k;
        let body = format!("q /GS0 gs /Pt0 scn 0 0 8 8 re f /Fm{} Do Q", k + 1);
        objects.push((
            id,
            raw_stream(
                &format!(
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] \
                     /Resources << /XObject << /Fm{} {} 0 R >> \
                     /Pattern << /Pt0 60 0 R >> /ExtGState << /GS0 61 0 R >> >> \
                     /Length {} >>",
                    k + 1,
                    id + 1,
                    body.len()
                ),
                body.as_bytes(),
            ),
        ));
    }
    // The tail of the chain terminates.
    objects.push((
        10 + CHAIN,
        raw_stream(
            "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] /Length 15 >>",
            b"0 0 4 4 re f  ",
        ),
    ));
    objects.push((
        60,
        raw_stream(
            "<< /Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 \
             /BBox [0 0 8 8] /XStep 8 /YStep 8 /Length 15 >>",
            b"0 0 8 8 re f  ",
        ),
    ));
    objects.push((
        61,
        b"<< /Type /ExtGState /SMask << /S /Luminosity /G 62 0 R >> >>".to_vec(),
    ));
    objects.push((
        62,
        raw_stream(
            "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] \
             /Group << /S /Transparency /CS /DeviceGray >> \
             /Resources << /XObject << /Fm0 10 0 R >> >> /Length 9 >>",
            b"/Fm0 Do ",
        ),
    ));
    raw_one_page(
        "/Resources << /XObject << /Fm0 10 0 R >> /Pattern << /Pt0 60 0 R >> \
         /ExtGState << /GS0 61 0 R >> >>",
        b"/Pattern cs /Pt0 scn /GS0 gs /Fm0 Do",
        objects,
    )
}

/// REGRESSION TEST — TEXT EXTRACTION must not inherit the caller's stack.
///
/// `forms::document_text` is the second JNI-reachable path into the interpreter
/// (`PdfNative.extractText`), and it recursed on the caller's stack exactly as
/// `render_page` did before it was pinned: the same form-XObject, tiling-pattern,
/// soft-mask and Type 3 recursion, with the same depth-capped-but-large frames.
///
/// FIXED: `document_text` runs the per-page loop on a worker with
/// `RENDER_STACK_BYTES`.
///
/// Verified non-vacuous the same way as `rendering_does_not_depend_on_the_calling_threads_stack`:
/// with the worker removed, this case exits the test binary with
/// `STATUS_STACK_OVERFLOW` (0xc00000fd) instead of failing an assertion, which is
/// why the assertion is "extraction returned", not "did not overflow".
#[test]
fn text_extraction_does_not_depend_on_the_calling_threads_stack() {
    let pdf = nested_forms_pattern_and_soft_mask_pdf();
    let v = guarded_on_stack(
        "extractText from a small stack",
        construct_budget(),
        256 * 1024,
        move || {
            let handle = open_document(&pdf);
            assert_ne!(handle, 0, "the constructed document must open");
            let text = document_text(handle);
            close_document(handle);
            // The page paints only rectangles, so the text is legitimately empty —
            // what matters is that the call returned at all rather than taking the
            // process down.
            assert!(
                text.is_some(),
                "text extraction over nested forms / pattern / soft mask must return"
            );
        },
    );
    assert!(!v.is_failure(), "{v:?}");
}

/// REGRESSION TEST — SEARCH INDEXING must not inherit the caller's stack.
///
/// `search::ensure_index` is the third JNI-reachable path into the interpreter
/// (`PdfNative.searchDocument` / `buildSearchIndex`): `build_index` calls
/// `content::page_operations` and `interpret_content` per page, so it carries the
/// same recursion — through the same form XObjects, patterns, soft masks and
/// Type 3 glyphs — on whatever stack called in.
///
/// FIXED: `ensure_index` builds on a worker with `RENDER_STACK_BYTES`.
///
/// Verified non-vacuous the same way: with the worker removed this case exits with
/// `STATUS_STACK_OVERFLOW` (0xc00000fd).
#[test]
fn search_indexing_does_not_depend_on_the_calling_threads_stack() {
    let pdf = nested_forms_pattern_and_soft_mask_pdf();
    let v = guarded_on_stack(
        "searchDocument from a small stack",
        construct_budget(),
        256 * 1024,
        move || {
            let handle = open_document(&pdf);
            assert_ne!(handle, 0, "the constructed document must open");
            let hits = search_document(handle, "anything");
            close_document(handle);
            assert!(
                hits.is_some(),
                "search over nested forms / pattern / soft mask must return"
            );
        },
    );
    assert!(!v.is_failure(), "{v:?}");
}

/// Bisection probe for the nesting stack-overflow finding. Ignored because a
/// stack overflow is a guard-page fault that kills the process, so it cannot be
/// a normal test. Driven by env vars and run one depth per process:
///
///   FUZZ_NEST_DEPTH=<n> FUZZ_NEST_TARGET=strict|lenient|page|objgraph \
///     cargo test -- --ignored bisect_nesting_depth
///
/// The process exit code is the result: 0 = survived, 0xc00000fd = overflow.
#[test]
#[ignore = "diagnostic probe; a stack overflow kills the process"]
fn bisect_nesting_depth() {
    let depth: usize = std::env::var("FUZZ_NEST_DEPTH")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(500);
    let target = std::env::var("FUZZ_NEST_TARGET").unwrap_or_else(|_| "page".to_string());
    eprintln!("[bisect] target={target} depth={depth}");

    let mut nested = Vec::new();
    nested.extend(std::iter::repeat_n(b'[', depth));
    nested.extend_from_slice(b"(x)");
    nested.extend(std::iter::repeat_n(b']', depth));

    let work: Box<dyn FnOnce() + Send> = match target.as_str() {
        // lopdf's strict content parser, called DIRECTLY.
        //
        // NOTE: this target deliberately bypasses the fix. The guard added for
        // finding B is a pre-check in `content::strict_operations`, so
        // `Content::decode` itself is still unbounded — measured here as
        // STACK_OVERFLOW at depth 48 on a 1 MiB stack even after the fix. That is
        // EXPECTED and is NOT a live app bug: nothing reachable from the app calls
        // `Content::decode` without the pre-check. Use the "page" target to test
        // what the app actually does. This target is kept only to demonstrate that
        // the underlying dependency behaviour is unchanged, i.e. that the fix is a
        // call-site guard rather than an upstream repair.
        "strict" => Box::new(move || {
            let mut ops = Vec::from(&b"BT "[..]);
            ops.extend_from_slice(&nested);
            ops.extend_from_slice(b" TJ ET");
            let r = lopdf::content::Content::decode(&ops);
            eprintln!("[bisect] strict decode ok={}", r.is_ok());
        }),
        // Our own lenient re-tokenizer.
        "lenient" => Box::new(move || {
            let mut ops = Vec::from(&b"BT "[..]);
            ops.extend_from_slice(&nested);
            ops.extend_from_slice(b" TJ ET");
            let v = crate::content::parse_operations_lenient(&ops);
            eprintln!("[bisect] lenient ops={}", v.len());
        }),
        // Nesting in the object graph rather than a content stream.
        "objgraph" | "load" => {
            let load_only = target == "load";
            Box::new(move || {
                let mut dict = Vec::new();
                for _ in 0..depth {
                    dict.extend_from_slice(b"<< /K ");
                }
                dict.extend_from_slice(b"0");
                for _ in 0..depth {
                    dict.extend_from_slice(b" >>");
                }
                let pdf = raw_one_page("/DeepNest 5 0 R", b"0 0 10 10 re f", vec![(5, dict)]);
                if load_only {
                    let loaded = load_document_lenient(&pdf).is_some();
                    eprintln!("[bisect] load-only survived, loaded={loaded}");
                } else {
                    exercise(&pdf);
                    eprintln!("[bisect] objgraph survived");
                }
            })
        }
        // The full page path, as the real app hits it.
        _ => Box::new(move || {
            let mut ops = Vec::from(&b"BT "[..]);
            ops.extend_from_slice(&nested);
            ops.extend_from_slice(b" TJ ET");
            let pdf = raw_one_page("", &ops, vec![]);
            exercise(&pdf);
            eprintln!("[bisect] page path survived");
        }),
    };
    // Same stack size as the harness unless overridden, so the bisect transfers.
    let stack: usize = std::env::var("FUZZ_NEST_STACK")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(WORKER_STACK);
    let v = guarded_on_stack("bisect", Duration::from_secs(60), stack, work);
    eprintln!("[bisect] stack={stack} verdict={v:?}");
}

/// The guard itself must work, or every test above passes vacuously. Verifies
/// that a hang is detected as a hang and a panic as a panic.
#[test]
fn the_guard_detects_hangs_and_panics() {
    let v = guarded("self-test-hang", Duration::from_millis(150), || {
        // Deliberate spin, deliberately leaked. Bounded so the leaked thread
        // cannot outlive the test binary in a way that burns CPU forever.
        let t0 = Instant::now();
        while t0.elapsed() < Duration::from_secs(20) {
            std::hint::spin_loop();
        }
    });
    assert!(
        matches!(v, Verdict::TimedOut),
        "the timeout guard failed to detect a hang: {v:?}"
    );

    let v = guarded("self-test-panic", construct_budget(), || {
        panic!("deliberate");
    });
    match v {
        Verdict::Panicked(msg, _) => assert!(msg.contains("deliberate")),
        other => panic!("the panic guard failed to capture a panic: {other:?}"),
    }

    let v = guarded("self-test-ok", construct_budget(), || {});
    assert!(matches!(v, Verdict::Completed(_)), "{v:?}");
}