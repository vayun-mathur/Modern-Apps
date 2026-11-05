fn clip_contours(r: &Rast, pts: &[(f32, f32)], path_ops: &Option<Vec<PathOp>>) -> Vec<Vec<(f64, f64)>> {
    match path_ops {
        Some(ops) => {
            let mut out: Vec<Vec<(f64, f64)>> = Vec::new();
            let mut cur: Vec<(f64, f64)> = Vec::new();
            let mut last = (0f64, 0f64);
            for op in ops {
                match op {
                    PathOp::Move(x, y) => {
                        if cur.len() >= 3 {
                            out.push(std::mem::take(&mut cur));
                        } else {
                            cur.clear();
                        }
                        last = (*x as f64, *y as f64);
                        cur.push(r.dev(last.0, last.1));
                    }
                    PathOp::Line(x, y) => {
                        last = (*x as f64, *y as f64);
                        cur.push(r.dev(last.0, last.1));
                    }
                    PathOp::Cubic(x1, y1, x2, y2, x3, y3) => {
                        let p0 = last;
                        let (c1, c2, c3) = (
                            r.dev(*x1 as f64, *y1 as f64),
                            r.dev(*x2 as f64, *y2 as f64),
                            r.dev(*x3 as f64, *y3 as f64),
                        );
                        let d0 = r.dev(p0.0, p0.1);
                        // Scale-aware, to match what the CONSUMER does: a clip
                        // curve crosses the wire as a real `PathOp::Cubic`
                        // (`interpret.rs:1035` is the only producer) and Skia
                        // re-flattens it at device resolution every frame, so a
                        // clip boundary does NOT facet at zoom the way a
                        // pre-flattened `Prim::Fill` contour does. Flattening at
                        // a fixed step count here would import a faceting error
                        // production does not have, and at high `SCALE` that
                        // would read as a renderer disagreement.
                        let hull = (c1.0 - d0.0).hypot(c1.1 - d0.1)
                            + (c2.0 - c1.0).hypot(c2.1 - c1.1)
                            + (c3.0 - c2.0).hypot(c3.1 - c2.1);
                        let steps = ((hull / 3.0).ceil() as usize).clamp(8, 256);
                        for k in 1..=steps {
                            let t = k as f64 / steps as f64;
                            let mt = 1.0 - t;
                            let x = mt * mt * mt * p0.0
                                + 3.0 * mt * mt * t * *x1 as f64
                                + 3.0 * mt * t * t * *x2 as f64
                                + t * t * t * *x3 as f64;
                            let y = mt * mt * mt * p0.1
                                + 3.0 * mt * mt * t * *y1 as f64
                                + 3.0 * mt * t * t * *y2 as f64
                                + t * t * t * *y3 as f64;
                            cur.push(r.dev(x, y));
                        }
                        last = (*x3 as f64, *y3 as f64);
                    }
                    PathOp::Close => {
                        if cur.len() >= 3 {
                            out.push(std::mem::take(&mut cur));
                        } else {
                            cur.clear();
                        }
                    }
                }
            }
            if cur.len() >= 3 {
                out.push(cur);
            }
            out
        }
        None => vec![pts.iter().map(|&(x, y)| r.dev(x as f64, y as f64)).collect()],
    }
}

// ---------------------------------------------------------------------------
// The reference side
// ---------------------------------------------------------------------------

fn hayro_rgb8(bytes: &[u8], scale: f32) -> (usize, usize, Vec<[u8; 3]>) {
    use hayro::{render, PixmapSettings, RenderCache, RenderSettings};
    use hayro::hayro_interpret::InterpreterSettings;
    use hayro::hayro_syntax::Pdf;
    use hayro::vello_cpu::color::palette::css::WHITE;

    let pdf = Pdf::new(bytes.to_vec()).expect("hayro must parse the fixture");
    let pages = pdf.pages();
    assert!(!pages.is_empty(), "hayro found no pages");
    let cache = RenderCache::new();
    let settings = InterpreterSettings::default();
    let pixmap = render(
        &pages[0],
        &cache,
        &settings,
        &RenderSettings::default(),
        &PixmapSettings { x_scale: scale, y_scale: scale, bg_color: WHITE, ..Default::default() },
    );
    let (w, h) = (pixmap.width() as usize, pixmap.height() as usize);
    let rgb = pixmap
        .data()
        .iter()
        .map(|p| {
            // Premultiplied over an opaque white background: with bg_color WHITE
            // every pixel has a == 255, so the channels are already straight.
            let a = p.a as u32;
            let un = |c: u8| {
                if a == 0 {
                    255u8
                } else {
                    ((c as u32 * 255 + a / 2) / a).min(255) as u8
                }
            };
            [un(p.r), un(p.g), un(p.b)]
        })
        .collect();
    (w, h, rgb)
}

// ---------------------------------------------------------------------------
// The metric
// ---------------------------------------------------------------------------

#[derive(Debug)]
struct DiffReport {
    w: usize,
    h: usize,
    flagged: usize,
    fraction: f64,
    worst: Option<(usize, usize, [u8; 3], [u8; 3])>,
}

/// Neighbourhood-tolerant perceptual difference. A pixel of `a` is flagged only
/// when it differs from every pixel of `b` in its 3x3 neighbourhood by more than
/// [`CHANNEL_TOLERANCE`]. Antialiasing and sub-pixel placement therefore never
/// flag; wrong colour, missing ink and >1px displacement do.
fn fuzzy_diff(a: &[[u8; 3]], b: &[[u8; 3]], w: usize, h: usize) -> DiffReport {
    let mut flagged = 0usize;
    let mut worst: Option<(usize, usize, [u8; 3], [u8; 3], i32)> = None;
    let at = |v: &[[u8; 3]], x: usize, y: usize| v[y * w + x];
    for y in 0..h {
        for x in 0..w {
            let pa = at(a, x, y);
            let mut best = i32::MAX;
            let mut best_px = pa;
            for dy in -1i32..=1 {
                for dx in -1i32..=1 {
                    let nx = x as i32 + dx;
                    let ny = y as i32 + dy;
                    if nx < 0 || ny < 0 || nx >= w as i32 || ny >= h as i32 {
                        continue;
                    }
                    let pb = at(b, nx as usize, ny as usize);
                    let d = (0..3)
                        .map(|c| (pa[c] as i32 - pb[c] as i32).abs())
                        .max()
                        .unwrap_or(0);
                    if d < best {
                        best = d;
                        best_px = pb;
                    }
                }
            }
            if best > CHANNEL_TOLERANCE {
                flagged += 1;
                if worst.map_or(true, |wv| best > wv.4) {
                    worst = Some((x, y, pa, best_px, best));
                }
            }
        }
    }
    DiffReport {
        w,
        h,
        flagged,
        fraction: flagged as f64 / (w * h).max(1) as f64,
        worst: worst.map(|(x, y, p, q, _)| (x, y, p, q)),
    }
}

/// Mean straight-RGB colour of an axis-aligned rectangle given in PDF page
/// coordinates (y up). Sampling well inside a shape makes the result completely
/// independent of every antialiasing approximation in this file.
fn interior_mean(rgb: &[[u8; 3]], w: usize, h: usize, page_h: f64, rect: [f64; 4]) -> [f64; 3] {
    let x0 = (rect[0] * SCALE as f64).round().max(0.0) as usize;
    let x1 = (rect[2] * SCALE as f64).round().min(w as f64) as usize;
    let y0 = ((page_h - rect[3]) * SCALE as f64).round().max(0.0) as usize;
    let y1 = ((page_h - rect[1]) * SCALE as f64).round().min(h as f64) as usize;
    let mut acc = [0f64; 3];
    let mut n = 0f64;
    for y in y0..y1 {
        for x in x0..x1 {
            let p = rgb[y * w + x];
            acc[0] += p[0] as f64;
            acc[1] += p[1] as f64;
            acc[2] += p[2] as f64;
            n += 1.0;
        }
    }
    assert!(n > 0.0, "empty sample rect {rect:?}");
    [acc[0] / n, acc[1] / n, acc[2] / n]
}

/// Coarse ASCII ink map, for reading a disagreement off the test log without a
/// PNG viewer. `#` = dark, `+` = mid, `.` = light, ` ` = white.
fn ascii_ink(rgb: &[[u8; 3]], w: usize, h: usize, cols: usize) -> String {
    let rows = (cols * h / w.max(1)).max(1);
    let mut s = String::new();
    for ry in 0..rows {
        for rx in 0..cols {
            let x0 = rx * w / cols;
            let x1 = ((rx + 1) * w / cols).max(x0 + 1).min(w);
            let y0 = ry * h / rows;
            let y1 = ((ry + 1) * h / rows).max(y0 + 1).min(h);
            let mut acc = 0u32;
            let mut n = 0u32;
            for y in y0..y1 {
                for x in x0..x1 {
                    let p = rgb[y * w + x];
                    acc += (p[0] as u32 + p[1] as u32 + p[2] as u32) / 3;
                    n += 1;
                }
            }
            let v = if n == 0 { 255 } else { acc / n };
            s.push(match v {
                0..=63 => '#',
                64..=159 => '+',
                160..=239 => '.',
                _ => ' ',
            });
        }
        s.push('\n');
    }
    s
}

/// Everything a single fixture produced.
struct Rendered {
    w: usize,
    h: usize,
    page_h: f64,
    ours: Vec<[u8; 3]>,
    theirs: Vec<[u8; 3]>,
    report: DiffReport,
}

fn render_both(name: &str, bytes: &[u8]) -> Rendered {
    let doc = load_document_lenient(bytes).expect("our loader must open the fixture");
    let page_id = nth_page_id(&doc, 0).expect("page 0");
    let page = interpret_page(&doc, page_id).expect("interpret");
    let (canvas, skipped) = rasterize(&page, SCALE);
    assert_eq!(
        skipped,
        Skipped::default(),
        "[{name}] this fixture uses constructs the differential rasteriser cannot draw; \
         the comparison would be meaningless. See the module header."
    );
    let ours = canvas.to_rgb8();
    let (hw, hh, theirs) = hayro_rgb8(bytes, SCALE);
    assert_eq!(
        (canvas.w, canvas.h),
        (hw, hh),
        "[{name}] page dimensions disagree before any pixel is compared"
    );
    let report = fuzzy_diff(&ours, &theirs, hw, hh);
    println!(
        "[{name}] {}x{} flagged={} ({:.4}%) worst={:?}",
        report.w,
        report.h,
        report.flagged,
        report.fraction * 100.0,
        report.worst
    );
    Rendered { w: hw, h: hh, page_h: page.height as f64, ours, theirs, report }
}

fn compare_page(name: &str, bytes: &[u8]) -> Rendered {
    let r = render_both(name, bytes);
    if r.report.fraction > DIFF_BUDGET {
        println!("--- [{name}] OURS ---\n{}", ascii_ink(&r.ours, r.w, r.h, 96));
        println!("--- [{name}] HAYRO ---\n{}", ascii_ink(&r.theirs, r.w, r.h, 96));
    }
    assert!(
        r.report.fraction <= DIFF_BUDGET,
        "[{name}] renderers disagree on {:.3}% of pixels (budget {:.3}%). worst pixel {:?} \
         (ours, theirs)",
        r.report.fraction * 100.0,
        DIFF_BUDGET * 100.0,
        r.report.worst
    );
    r
}

// ---------------------------------------------------------------------------
// Fixture builders (mirrors of the private `golden_tests.rs` helpers)
// ---------------------------------------------------------------------------

fn assemble(
    doc: &mut Document,
    content: Vec<u8>,
    resources: Dictionary,
    mut page: Dictionary,
) -> ObjectId {
    let content_id = doc.add_object(Stream::new(dictionary! {}, content));
    let pages_id = doc.new_object_id();
    page.set("Type", Object::Name(b"Page".to_vec()));
    page.set("Parent", pages_id);
    page.set("Resources", resources);
    page.set("Contents", Object::Reference(content_id));
    if page.get(b"MediaBox").is_err() {
        page.set("MediaBox", vec![0.into(), 0.into(), 200.into(), 200.into()]);
    }
    let page_id = doc.add_object(page.clone());
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
        }),
    );
    let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", catalog_id);
    page_id
}

/// Build a one-page 200x200 document from an operator list and serialise it.
fn pdf_bytes(ops: Vec<Operation>, resources: Dictionary) -> Vec<u8> {
    pdf_bytes_page(ops, resources, dictionary! {})
}

fn pdf_bytes_page(ops: Vec<Operation>, resources: Dictionary, page: Dictionary) -> Vec<u8> {
    let mut doc = Document::with_version("1.7");
    let content = Content { operations: ops }.encode().expect("encode content");
    let _ = assemble(&mut doc, content, resources, page);
    let mut out = Vec::new();
    doc.save_to(&mut out).expect("save");
    out
}

fn op(o: &str, args: Vec<Object>) -> Operation {
    Operation::new(o, args)
}

fn n(v: f64) -> Object {
    Object::Real(v as f32)
}

// ---------------------------------------------------------------------------
// Corpus
//
// Chosen for where a SELF-CONSISTENT misreading is most plausible, and phrased
// so this file's rasteriser approximations cannot explain a failure: flat
// colours, axis-aligned or generously-sized shapes, no blend modes, no
// substitute-font text.
// ---------------------------------------------------------------------------

/// Baseline: if two independent renderers cannot agree on a filled rectangle,
/// nothing further in this file means anything. This also calibrates
/// [`DIFF_BUDGET`] — the flagged fraction printed here is the metric's noise
/// floor for a page that is by construction identical.
#[test]
#[ignore]
fn refdiff_solid_fills_calibrate_the_metric() {
    let ops = vec![
        op("rg", vec![n(0.9), n(0.1), n(0.1)]),
        op("re", vec![n(20.0), n(20.0), n(70.0), n(70.0)]),
        op("f", vec![]),
        op("g", vec![n(0.25)]),
        op("re", vec![n(110.0), n(20.0), n(70.0), n(70.0)]),
        op("f", vec![]),
        op("rg", vec![n(0.0), n(0.35), n(0.7)]),
        op("re", vec![n(20.0), n(110.0), n(70.0), n(70.0)]),
        op("f", vec![]),
    ];
    let r = compare_page("solid_fills", &pdf_bytes(ops, dictionary! {}));
    // Interior means are antialiasing-free, so they can be graded tightly.
    for (label, rect) in [
        ("devicergb_warm", [30.0, 30.0, 80.0, 80.0]),
        ("devicegray", [120.0, 30.0, 170.0, 80.0]),
        ("devicergb_cool", [30.0, 120.0, 80.0, 170.0]),
    ] {
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        println!("  {label}: ours={a:?} theirs={b:?}");
        for c in 0..3 {
            assert!(
                (a[c] - b[c]).abs() <= 3.0,
                "{label} channel {c}: ours {} vs hayro {}",
                a[c],
                b[c]
            );
        }
    }
}
