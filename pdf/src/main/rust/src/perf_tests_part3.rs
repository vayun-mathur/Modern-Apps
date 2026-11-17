#[test]
#[ignore]
fn perf_peak_memory_by_stressor() {
    println!("\n=== PEAK HEAP by stressor (counting allocator, net live bytes) ===");
    let mut rows: Vec<(String, usize, usize, usize)> = Vec::new();

    {
        let mut d0 = Document::with_version("1.5");
        let res = standard_font_res(&mut d0);
        let (doc, pages) = build_doc(&[rect_content(200_000)], res);
        mem_reset();
        let p = interpret_page(&doc, pages[0]).unwrap();
        let (a, b) = (p.prims.len(), raster_bytes(&p));
        drop(p);
        rows.push(("200k rects".into(), mem_peak(), a, b));
    }
    {
        let (doc, pid) = shading_doc(160, (600.0, 600.0));
        mem_reset();
        let p = interpret_page(&doc, pid).unwrap();
        let (a, b) = (p.prims.len(), raster_bytes(&p));
        drop(p);
        rows.push(("160 square shadings".into(), mem_peak(), a, b));
    }
    {
        let (doc, pid) = shading_doc(160, (600.0, 2.0));
        mem_reset();
        let p = interpret_page(&doc, pid).unwrap();
        let (a, b) = (p.prims.len(), raster_bytes(&p));
        drop(p);
        rows.push(("160 long-thin shadings".into(), mem_peak(), a, b));
    }
    {
        let (doc, pid) = tiling_doc(2.0);
        mem_reset();
        let p = interpret_page(&doc, pid).unwrap();
        let (a, b) = (p.prims.len(), raster_bytes(&p));
        drop(p);
        rows.push(("fine hatch tiling (pitch 2)".into(), mem_peak(), a, b));
    }
    {
        let (doc, pid) = smask_doc(800, false);
        mem_reset();
        let p = interpret_page(&doc, pid).unwrap();
        let (a, b) = (p.prims.len(), raster_bytes(&p));
        drop(p);
        rows.push(("800 shapes under one SMask".into(), mem_peak(), a, b));
    }
    {
        let mut doc = Document::with_version("1.5");
        let res = embedded_font_res(&mut doc, 1600, 64);
        let pages_id = doc.new_object_id();
        let cid = doc.add_object(Stream::new(Dictionary::new(), text_content(2000)));
        let pid = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => cid, "Resources" => res,
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages", "Kids" => vec![pid.into()], "Count" => 1,
            }),
        );
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", cat);
        mem_reset();
        let p = interpret_page(&doc, pid).unwrap();
        let (a, b) = (p.prims.len(), raster_bytes(&p));
        drop(p);
        rows.push(("2000 glyph runs, 1600-glyph font".into(), mem_peak(), a, b));
    }

    println!(
        "{:<34} {:>14} {:>10} {:>14}",
        "stressor", "peak heap MiB", "prims", "raster MiB"
    );
    for (name, peak, prims, rb) in &rows {
        println!(
            "{:<34} {:>14.2} {:>10} {:>14.2}",
            name,
            mib(*peak),
            prims,
            mib(*rb)
        );
    }
    println!("  Highest peak here is the best available proxy for the OOM risk that");
    println!("  the 'crasheshalfway' symptom would come from.");
}

// ---------------------------------------------------------------------------
// 9. Font cache, sharpened
// ---------------------------------------------------------------------------

/// Build an n-page doc sharing one embedded font, with `runs` text runs per page.
fn font_doc(n_pages: usize, glyphs: u16, pts: u16, runs: usize) -> (Document, Vec<ObjectId>) {
    let mut doc = Document::with_version("1.5");
    let res = embedded_font_res(&mut doc, glyphs, pts);
    let pages_id = doc.new_object_id();
    let mut kids = Vec::new();
    let mut page_ids = Vec::new();
    for _ in 0..n_pages {
        let cid = doc.add_object(Stream::new(Dictionary::new(), text_content(runs)));
        let pid = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => cid, "Resources" => res.clone(),
        });
        kids.push(pid.into());
        page_ids.push(pid);
    }
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => kids, "Count" => n_pages as i64,
        }),
    );
    let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", cat);
    (doc, page_ids)
}

/// The first font-cache benchmark buried the parse cost under 200 text runs of
/// rendering per page. This isolates it: a *large* embedded font and almost no
/// drawing, so per-page font parsing is the dominant term. If the cache is worth
/// anything, it has to show up here.
#[test]
#[ignore]
fn perf_font_cache_isolated() {
    println!("\n=== CLAIM: font cache, ISOLATED (big font, minimal drawing) ===");
    let floor = noise_floor();
    println!();
    for &(glyphs, runs) in &[(1600u16, 2usize), (1600, 40), (400, 2)] {
        for &n_pages in &[1usize, 20] {
            let (doc, pages) = font_doc(n_pages, glyphs, 64, runs);
            let without = bench(9, || {
                for &pid in &pages {
                    black_box(interpret_page(&doc, pid).unwrap());
                }
            });
            let with = bench(9, || {
                let _scope = FontCacheScope::new();
                for &pid in &pages {
                    black_box(interpret_page(&doc, pid).unwrap());
                }
            });
            let label = format!("{}-glyph font, {} runs/page", glyphs, runs);
            without.row(&format!("{} [pre-fix]", label), n_pages, "");
            with.row(&format!("{} [post-fix]", label), n_pages, "");
            println!(
                "  {} pages, {}-glyph font, {} runs/page: {}",
                n_pages,
                glyphs,
                runs,
                verdict(&without, &with, floor)
            );
            if n_pages > 1 {
                let saved = without.median_ms - with.median_ms;
                println!(
                    "     saved {:.3} ms over {} pages => {:.3} ms per avoided font parse",
                    saved,
                    n_pages,
                    saved / (n_pages as f64 - 1.0)
                );
            }
            println!();
        }
    }
}

/// Separate the one-time font *parse* from the per-cache-hit `FontInfo::clone`.
/// Rendering the same page k times inside one scope costs `parse + k*clone`, so
/// the slope in k is the clone cost and the intercept is the parse cost.
#[test]
#[ignore]
fn perf_font_cache_hit_vs_parse() {
    println!("\n=== separating font PARSE cost from per-HIT clone cost ===");
    println!("  (one scope, page rendered k times: cost = parse + k*(clone+render))");
    for &nw in &[256usize, 32768] {
        let mut doc = Document::with_version("1.5");
        let prog = synth_ttf(400, 64);
        let len = prog.len() as i64;
        let ff = doc.add_object(Stream::new(dictionary! { "Length1" => len }, prog));
        let fd = doc.add_object(dictionary! {
            "Type" => "FontDescriptor", "FontName" => "BenchFont", "Flags" => 4,
            "ItalicAngle" => 0, "Ascent" => 800, "Descent" => -200,
            "CapHeight" => 700, "StemV" => 80,
            "FontBBox" => vec![0.into(), (-200).into(), 1000.into(), 1000.into()],
            "FontFile2" => ff,
        });
        let mut w: Vec<Object> = Vec::new();
        for cid in 0..nw {
            w.push(Object::Integer(cid as i64));
            w.push(Object::Array(vec![Object::Integer(500)]));
        }
        let desc = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "CIDFontType2", "BaseFont" => "BenchFont",
            "CIDSystemInfo" => dictionary! {
                "Registry" => Object::string_literal("Adobe"),
                "Ordering" => Object::string_literal("Identity"),
                "Supplement" => 0,
            },
            "FontDescriptor" => fd, "DW" => 1000, "W" => w,
        });
        let font = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type0", "BaseFont" => "BenchFont",
            "Encoding" => "Identity-H", "DescendantFonts" => vec![desc.into()],
        });
        let res = dictionary! { "Font" => dictionary! { "F1" => font } };
        let pages_id = doc.new_object_id();
        let cstream = doc.add_object(Stream::new(
            Dictionary::new(),
            b"BT\n/F1 12 Tf\n1 0 0 1 20 700 Tm\n<00410042> Tj\nET\n".to_vec(),
        ));
        let pid = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => cstream, "Resources" => res,
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages", "Kids" => vec![pid.into()], "Count" => 1,
            }),
        );
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", cat);

        let mut meds = Vec::new();
        let ks = [1usize, 41];
        for &k in &ks {
            let st = bench(9, || {
                let _scope = FontCacheScope::new();
                for _ in 0..k {
                    black_box(interpret_page(&doc, pid).unwrap());
                }
            });
            st.row(&format!("/W={} entries, k renders", nw), k, "");
            meds.push(st.median_ms);
        }
        // Also the fully-cold cost: a fresh scope per render, so every render parses.
        let cold = bench(9, || {
            for _ in 0..ks[1] {
                black_box(interpret_page(&doc, pid).unwrap());
            }
        });
        cold.row(&format!("/W={} entries, k COLD renders", nw), ks[1], "");

        let slope = (meds[1] - meds[0]) / (ks[1] - ks[0]) as f64;
        let parse_plus = meds[0];
        let cold_each = cold.median_ms / ks[1] as f64;
        println!(
            "  /W={:>6}: per-HIT (clone+render) {:.4} ms | first render (parse+render) {:.4} ms | per-COLD-render {:.4} ms",
            nw, slope, parse_plus, cold_each
        );
        println!(
            "             => cache saves ~{:.4} ms per repeat; a HIT still costs ~{:.4} ms",
            cold_each - slope, slope
        );
        println!();
    }
    println!("  A hit is not free, but the cost is no longer the clone: every FontInfo");
    println!("  collection is Arc-shared. It is `font_identity`, the cache-key check,");
    println!("  which re-hashes the resolved font dict (/W and the embedded program)");
    println!("  on every lookup.");
}

// ---------------------------------------------------------------------------
// 10. Where does the memory actually go? (the OOM mechanism)
// ---------------------------------------------------------------------------

/// `perf_primitive_cap_and_memory` shows peak heap growing with the number of
/// rects *requested* even after `MAX_PRIMITIVES` clamps the number of primitives
/// retained. That means the memory is not in the output. This splits the two
/// phases to find out where it is: parsing the content stream into a
/// `Vec<Operation>`, versus interpreting that into `Vec<Prim>`.
#[test]
#[ignore]
fn perf_memory_ops_vs_prims() {
    println!("\n=== WHERE THE MEMORY GOES: content ops vs primitives ===");
    println!("  MAX_CONTENT_OPS = {}, MAX_PRIMITIVES = {}", MAX_CONTENT_OPS, MAX_PRIMITIVES);
    println!(
        "  size_of::<Prim>() = {} B, size_of::<lopdf Operation>() = {} B",
        std::mem::size_of::<Prim>(),
        std::mem::size_of::<lopdf::content::Operation>()
    );
    println!();
    println!(
        "{:<12} {:>10} {:>12} {:>12} {:>14} {:>10} {:>10}",
        "rects", "ops", "ops ret MiB", "ops pk MiB", "full peak MiB", "prims", "prim MiB"
    );
    for &n in &[50_000usize, 200_000, 400_000] {
        let mut d0 = Document::with_version("1.5");
        let res = standard_font_res(&mut d0);
        let (doc, pages) = build_doc(&[rect_content(n)], res);

        // Phase 1 only: parse the content stream.
        mem_reset();
        let (ops, _rec) = crate::content::page_operations(&doc, pages[0]);
        let n_ops = ops.len();
        let ops_retained = mem_current();
        let ops_peak = mem_peak();
        drop(ops);

        // Both phases.
        mem_reset();
        let p = interpret_page(&doc, pages[0]).unwrap();
        let prims = p.prims.len();
        drop(p);
        let full_peak = mem_peak();

        println!(
            "{:<12} {:>10} {:>12.2} {:>12.2} {:>14.2} {:>10} {:>10.2}",
            n,
            n_ops,
            mib(ops_retained),
            mib(ops_peak),
            mib(full_peak),
            prims,
            mib(prims * std::mem::size_of::<Prim>())
        );
    }
    println!();
    println!("  If 'ops peak' dominates, the OOM happens while PARSING, before the");
    println!("  primitive cap can bound anything - which is what a document that");
    println!("  'crashes halfway' would look like.");
    println!(
        "  Worst case admitted by MAX_CONTENT_OPS alone: {} ops.",
        MAX_CONTENT_OPS
    );
}

/// Live heap bytes per parsed `Operation`, for several content-stream shapes.
///
/// A byte-based bound on the operator vector needs this constant, and it is not
/// a single number: `size_of::<Operation>()` is only 48 bytes, but each one owns
/// an operator `String` and a `Vec<Object>` of operands, so the true cost
/// depends on how many operands the operators carry and whether any are strings.
#[test]
#[ignore]
fn perf_bytes_per_content_op() {
    println!("\n=== live heap bytes per parsed Operation, by stream shape ===");
    println!(
        "  size_of::<Operation>() = {} B (the heap behind it is the real cost)",
        std::mem::size_of::<lopdf::content::Operation>()
    );
    println!();

    // (label, content bytes, note)
    let mut cases: Vec<(String, Vec<u8>)> = Vec::new();
    cases.push(("rects: 're' (4 numeric operands) + 'f'".into(), rect_content(200_000)));
    cases.push((
        "text: 'Tm' (6 operands) + 'Tj' (1 string)".into(),
        text_content(100_000),
    ));
    {
        // Zero-operand operators only: the cheapest possible op.
        let mut s = String::new();
        for _ in 0..200_000 {
            s.push_str("q\nQ\n");
        }
        cases.push(("bare 'q'/'Q' (no operands)".into(), s.into_bytes()));
    }
    {
        // Many operands per operator: a long polyline of 'l' ops.
        let mut s = String::from("0 0 m\n");
        for i in 0..200_000 {
            s.push_str(&format!("{} {} l\n", i % 600, (i * 7) % 780));
        }
        s.push_str("S\n");
        cases.push(("'l' lineto (2 numeric operands)".into(), s.into_bytes()));
    }

    println!(
        "{:<44} {:>10} {:>12} {:>12} {:>10} {:>10}",
        "stream shape", "ops", "retain MiB", "peak MiB", "B/op ret", "B/op pk"
    );
    for (label, content) in &cases {
        let mut d0 = Document::with_version("1.5");
        let res = standard_font_res(&mut d0);
        let (doc, pages) = build_doc(std::slice::from_ref(content), res);
        mem_reset();
        let (ops, _rec) = crate::content::page_operations(&doc, pages[0]);
        let n_ops = ops.len();
        // Live bytes with `ops` still held = what the phase RETAINS.
        let retained = mem_current();
        let peak = mem_peak();
        drop(ops);
        println!(
            "{:<44} {:>10} {:>12.2} {:>12.2} {:>10.0} {:>10.0}",
            label,
            n_ops,
            mib(retained),
            mib(peak),
            if n_ops > 0 { retained as f64 / n_ops as f64 } else { 0.0 },
            if n_ops > 0 { peak as f64 / n_ops as f64 } else { 0.0 }
        );
    }
    println!();
    println!("  'retain' is the operator vector itself; 'peak' includes whatever the");
    println!("  parser transiently allocated. peak >> retain means a streaming parse");
    println!("  would help even more than shrinking the vector would.");
    println!("  To bound the operator vector by BYTES, use the worst shape here, not");
    println!("  an average: a hostile stream picks the most expensive operator.");
}

// ---------------------------------------------------------------------------
// 11. Prim width and wire-buffer cost, measured on the current tree
// ---------------------------------------------------------------------------
