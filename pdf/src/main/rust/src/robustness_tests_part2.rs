/// The full deterministic mutation schedule for one seed document.
fn mutants(seed_name: &str, base: &[u8]) -> Vec<(String, Vec<u8>)> {
    let mut out: Vec<(String, Vec<u8>)> = Vec::new();
    let n = base.len();
    let tag = |what: &str| format!("{seed_name}/{what}");

    // (a) Truncate at every 1/16th of the length — the "crasheshalfway" /
    //     partial-download shape.
    for k in 0..16 {
        let cut = n * k / 16;
        out.push((tag(&format!("truncate@{k}/16={cut}")), base[..cut].to_vec()));
    }
    // Off-by-one truncations around the very end, where the trailer lives.
    for back in [1usize, 2, 5, 9, 20] {
        if n > back {
            out.push((tag(&format!("truncate-last-{back}")), base[..n - back].to_vec()));
        }
    }

    // (b) Zero out a 64-byte run at every 1/8th, plus 0xFF runs.
    for k in 0..8 {
        let at = n * k / 8;
        out.push((tag(&format!("zero64@{k}/8")), splat(base, at, 64, 0x00)));
        out.push((tag(&format!("ff64@{k}/8")), splat(base, at, 64, 0xFF)));
    }

    // (c) Single-bit flips at deterministic positions.
    let mut rng = Lcg::new(FUZZ_SEED ^ seed_name.len() as u64);
    for i in 0..180 {
        let pos = rng.below(n.max(1));
        let bit = rng.below(8);
        let mut v = base.to_vec();
        if pos < v.len() {
            v[pos] ^= 1u8 << bit;
        }
        out.push((tag(&format!("bitflip#{i}@{pos}:{bit}")), v));
    }

    // (d) Corrupt the startxref offset — the single most common real-world
    //     damage, and the trigger for the custom rebuild path.
    for sx in find_all(base, b"startxref") {
        for bogus in ["0", "1", "999999999999", "18446744073709551615", "4294967295"] {
            out.push((
                tag(&format!("startxref={bogus}")),
                replace_digits_at(base, sx + b"startxref".len(), bogus),
            ));
        }
        // Point startxref just off the `xref` keyword (the documented
        // real-world case that motivated the rebuild).
        out.push((
            tag("startxref+1"),
            replace_digits_at(base, sx + b"startxref".len(), &format!("{}", n.saturating_sub(1))),
        ));
    }

    // (e) Corrupt xref table entry offsets.
    if let Some(xr) = find_all(base, b"\nxref").first().copied() {
        out.push((tag("xref-entries-zeroed"), splat(base, xr + 1, 256, b'0')));
        out.push((tag("xref-keyword-broken"), splat(base, xr + 1, 4, b'X')));
    }

    // (f) Corrupt every /Length: far too large, zero, and negative.
    for lp in find_all(base, b"/Length") {
        for bogus in ["2147483647", "0", "1", "999999999999"] {
            out.push((
                tag(&format!("Length={bogus}@{lp}")),
                replace_digits_at(base, lp + b"/Length".len(), bogus),
            ));
        }
    }

    // (g) Replace a stream body with garbage, so a declared Flate/DCT filter
    //     gets bytes that cannot possibly decode.
    for (kw, skip) in [(&b"stream\n"[..], 7usize), (&b"stream\r\n"[..], 8)] {
        for sp in find_all(base, kw) {
            let body = sp + skip;
            out.push((tag(&format!("stream-body-garbage@{sp}")), splat(base, body, 128, 0xA5)));
            out.push((tag(&format!("stream-body-zeros@{sp}")), splat(base, body, 128, 0x00)));
        }
    }

    // (h) Remove the trailer entirely.
    if let Some(tp) = find_all(base, b"trailer").last().copied() {
        out.push((tag("trailer-removed"), base[..tp].to_vec()));
        let mut no_kw = base.to_vec();
        no_kw[tp..tp + 7].copy_from_slice(b"XXXXXXX");
        out.push((tag("trailer-keyword-clobbered"), no_kw));
    }
    // Remove /Root from the trailer.
    if let Some(rp) = find_all(base, b"/Root").last().copied() {
        let mut v = base.to_vec();
        v[rp..rp + 5].copy_from_slice(b"/Rxot");
        out.push((tag("Root-key-renamed"), v));
    }

    // (i) Duplicate an object header, so two definitions claim the same id.
    for id in [1u32, 2, 3] {
        let needle = format!("\n{id} 0 obj");
        if let Some(p) = find_all(base, needle.as_bytes()).first().copied() {
            let mut v = Vec::with_capacity(base.len() + needle.len());
            v.extend_from_slice(&base[..p]);
            v.extend_from_slice(needle.as_bytes());
            v.extend_from_slice(b"\n<< >>\nendobj");
            v.extend_from_slice(&base[p..]);
            out.push((tag(&format!("dup-header-{id}")), v));
        }
    }
    // A bogus enormous object number, which is what previously sized the
    // rebuilt xref table at ~20 GB.
    {
        let mut v = base.to_vec();
        v.extend_from_slice(b"\n999999999 0 obj\n<< >>\nendobj\n");
        out.push((tag("bogus-high-object-id"), v));
    }

    // (j) Swap the object-header digits inside the body, so offsets point at the
    //     wrong object entirely.
    for p in find_all(base, b" 0 obj") {
        if p >= 1 && base[p - 1].is_ascii_digit() {
            let mut v = base.to_vec();
            v[p - 1] = b'7';
            out.push((tag(&format!("objnum-repointed@{p}")), v));
        }
    }

    out
}

// ---------------------------------------------------------------------------
// LAYER 1 — mutation testing over valid documents
// ---------------------------------------------------------------------------

/// Run a labelled corpus through `exercise` under the guard, and fail once with
/// every finding rather than at the first one.
fn sweep(corpus: Vec<(String, Vec<u8>)>, budget: Duration) {
    let total = corpus.len();
    let mut failures: Vec<String> = Vec::new();
    let mut slowest: Vec<(Duration, String)> = Vec::new();
    let reach = std::sync::Arc::new(Reach::default());

    for (label, bytes) in corpus {
        let l = label.clone();
        let r = std::sync::Arc::clone(&reach);
        match guarded(&label, budget, move || exercise_counting(&bytes, &r)) {
            Verdict::Completed(dt) => slowest.push((dt, l)),
            Verdict::Panicked(msg, dt) => {
                failures.push(format!("  PANIC  [{l}] after {dt:?}: {msg}"))
            }
            Verdict::TimedOut => {
                failures.push(format!("  HANG   [{l}] exceeded {budget:?}"))
            }
        }
    }

    slowest.sort_by_key(|b| std::cmp::Reverse(b.0));
    let worst: Vec<String> = slowest
        .iter()
        .take(5)
        .map(|(d, l)| format!("{l} = {d:?}"))
        .collect();
    eprintln!(
        "[robustness] {total} mutants exercised, seed 0x{FUZZ_SEED:016X}.\n  \
         reached: loaded={} withPages={} interpreted={} emittedPrims={}\n  \
         slowest: {}",
        Reach::get(&reach.loaded),
        Reach::get(&reach.had_pages),
        Reach::get(&reach.interpreted),
        Reach::get(&reach.prims_emitted),
        worst.join(", ")
    );

    assert!(
        failures.is_empty(),
        "ROBUSTNESS FAILURES: {} of {total} mutants did not degrade gracefully \
         (FUZZ_SEED = 0x{FUZZ_SEED:016X}; each label is the exact deterministic \
         recipe, reproduce with `mutants(seed, base)`):\n{}",
        failures.len(),
        failures.join("\n")
    );
}

/// The core invariant: no byte-level corruption of a valid PDF may make any
/// entry point panic, hang, or emit a structurally invalid wire buffer.
#[test]
fn byte_level_corruption_of_valid_pdfs_never_panics_or_hangs() {
    let mut corpus = Vec::new();
    for (name, base) in seeds() {
        corpus.extend(mutants(name, &base));
    }
    assert!(
        corpus.len() > 700,
        "mutation schedule shrank unexpectedly ({} mutants) — a seed document \
         probably failed to build",
        corpus.len()
    );
    sweep(corpus, mutant_budget());
}

/// Every seed must render cleanly BEFORE mutation. Without this the sweep above
/// could pass vacuously by never reaching real code.
#[test]
fn seed_documents_render_successfully_before_mutation() {
    for (name, bytes) in seeds() {
        let doc = load_document_lenient(&bytes)
            .unwrap_or_else(|| panic!("seed {name} must load"));
        let pages = doc.get_pages();
        assert_eq!(pages.len(), 1, "seed {name} must have one page");
        let page_id = *pages.values().next().unwrap();
        let page = interpret_page(&doc, page_id)
            .unwrap_or_else(|e| panic!("seed {name} must interpret: {e}"));
        assert!(
            !page.prims.is_empty(),
            "seed {name} produced no primitives — it is not exercising the \
             renderer, so its mutants would not either"
        );
        assert_eq!(page.width, 612.0);
        let buf = crate::wire::serialize(&page);
        assert!(buf.len() > 20, "seed {name} wire buffer is header-only");
    }
}

/// Pure byte soup: no PDF structure at all. Cheap, and it covers the shapes a
/// structured mutator never produces (e.g. a file that is only a header, or
/// only binary).
#[test]
fn arbitrary_byte_strings_never_panic() {
    let mut rng = Lcg::new(FUZZ_SEED);
    let mut corpus = vec![
        ("empty".to_string(), Vec::new()),
        ("header-only".to_string(), b"%PDF-1.7\n".to_vec()),
        ("nul".to_string(), vec![0u8; 64]),
        ("eof-only".to_string(), b"%%EOF".to_vec()),
        (
            "keywords-only".to_string(),
            b"%PDF-1.7 obj endobj stream endstream trailer xref startxref R".to_vec(),
        ),
    ];
    for i in 0..120 {
        let len = rng.below(400) + 1;
        let bytes: Vec<u8> = (0..len).map(|_| (rng.next_u64() & 0xFF) as u8).collect();
        corpus.push((format!("random#{i}/{len}b"), bytes));
    }
    // Random bytes with a plausible PDF skeleton grafted on, so the parser gets
    // far enough in to matter.
    for i in 0..120 {
        let len = rng.below(400) + 1;
        let mut bytes = b"%PDF-1.7\n1 0 obj\n".to_vec();
        bytes.extend((0..len).map(|_| (rng.next_u64() & 0xFF) as u8));
        bytes.extend_from_slice(b"\nendobj\ntrailer\n<< /Root 1 0 R >>\nstartxref\n9\n%%EOF\n");
        corpus.push((format!("random-in-skeleton#{i}/{len}b"), bytes));
    }
    sweep(corpus, mutant_budget());
}

/// The registry path (`open_document` -> `page_count` -> `render_page` ->
/// `close_document`) is what JNI actually calls, so it gets its own pass.
///
/// Deliberately only a handful of mutants. The registry is a process-global
/// 8-entry LRU (MAX_REG_DOCS, registry.rs:17) that evicts on insert, so a bulk
/// sweep through it would evict documents belonging to tests running in parallel
/// and make the whole suite order-dependent. Each document is closed immediately
/// after use to keep at most one of ours resident.
///
/// Asserts only "no panic, no hang", never success: a concurrent test can
/// legitimately evict our handle, and `None` is a correct outcome anyway.
#[test]
fn registry_open_render_close_survives_mutants() {
    let base = seed_rich();
    let all = mutants("rich", &base);
    // A fixed, reproducible slice: every 150th mutant.
    let picked: Vec<(String, Vec<u8>)> =
        all.into_iter().enumerate().filter(|(i, _)| i % 150 == 0).map(|(_, m)| m).collect();

    let mut failures = Vec::new();
    for (label, bytes) in picked {
        let l = label.clone();
        let v = guarded(&label, mutant_budget(), move || {
            let handle = open_document(&bytes);
            if handle == 0 {
                return; // clean rejection
            }
            let n = page_count(handle);
            for i in 0..n.min(4) {
                let _ = render_page(handle, i);
            }
            // Out-of-range indices must be rejected, not indexed. Negative
            // indices are deliberately NOT probed here — they hit a separate,
            // already-identified defect (see
            // `negative_page_indices_do_not_overflow_the_page_lookup`) which
            // would otherwise mask any mutant-specific finding in this sweep.
            let _ = render_page(handle, i32::MAX);
            let _ = render_page(handle, n);
            let _ = document_text(handle);
            let _ = list_annotations(handle, 0);
            let _ = list_form_fields(handle, 0);
            let _ = list_links(handle, 0);
            let _ = list_outline(handle);
            let _ = search_document(handle, "a");
            close_document(handle);
        });
        if v.is_failure() {
            failures.push(format!("  [{l}] {v:?}"));
        }
    }
    assert!(
        failures.is_empty(),
        "registry round-trip failed on {} mutants (seed 0x{FUZZ_SEED:016X}):\n{}",
        failures.len(),
        failures.join("\n")
    );
}

/// HAZARD: a negative page index arriving from the JNI boundary must be
/// rejected, not converted to `u32` and incremented.
///
/// Two sites computed `(index as u32) + 1`, which for `index == -1` is
/// `0xFFFF_FFFFu32 + 1` — a panic in a debug build, a silent wrap to 0 in
/// release. `renderPage(long, int)` and the annotation / form / link listing
/// entry points all take an unvalidated `jint` from Kotlin, so -1 is reachable
/// input rather than a synthetic case.
///
///   annotations.rs `nth_page_id`  - FIXED; now
///                                   `u32::try_from(index).ok()?.checked_add(1)?`
///   docedit.rs `render_page`      - FIXED the same way
/// `jni_bindings.rs` also stopped coercing a negative index with `index.max(0) as
/// usize` in `removePage` / `movePage`, which edited page 0 instead of refusing.
///
/// The `render_page` half used to panic *while holding the process-global registry
/// mutex* (docedit.rs:326), which poisons it. Production code is poison-tolerant
/// (`.unwrap_or_else(|p| p.into_inner())` everywhere) so the app survived, but
/// `tests::edit_render_tests::radio_group_clears_siblings` locks the registry with a
/// bare `.unwrap()` (tests.rs:743/747/756) and failed as collateral. This test
/// therefore still clears the poison explicitly after catching a panic, so a
/// regression stays visible without breaking an unrelated test.
#[test]
fn negative_page_indices_do_not_overflow_the_page_lookup() {
    let bytes = seed_text_and_paths();
    let v = guarded("negative page index", construct_budget(), move || {
        let doc = load_document_lenient(&bytes).expect("the seed must load");
        let mut panics: Vec<String> = Vec::new();

        // Lock-free site: safe to probe directly.
        for idx in [-1i32, -2, -1000, i32::MIN, i32::MIN + 1] {
            match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| nth_page_id(&doc, idx))) {
                Ok(got) => assert!(
                    got.is_none(),
                    "nth_page_id({idx}) must not resolve to a page, got {got:?}"
                ),
                Err(e) => panics.push(format!("nth_page_id(index={idx}): {}", panic_text(e))),
            }
        }

        // Registry-backed sites. Each panic poisons the registry mutex, so clear
        // it before continuing; leaving it poisoned would fail unrelated tests
        // that lock it with a bare `.unwrap()`.
        let handle = open_document(&bytes);
        assert_ne!(handle, 0, "the seed must open");
        for idx in [-1i32, -2, i32::MIN] {
            for (name, call) in [
                ("render_page", 0u8),
                ("list_annotations", 1),
                ("list_form_fields", 2),
                ("list_links", 3),
            ] {
                let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| match call {
                    0 => render_page(handle, idx).map(|_| ()),
                    1 => list_annotations(handle, idx).map(|_| ()),
                    2 => list_form_fields(handle, idx).map(|_| ()),
                    _ => list_links(handle, idx).map(|_| ()),
                }));
                if let Err(e) = r {
                    panics.push(format!("{name}(index={idx}): {}", panic_text(e)));
                    registry().clear_poison();
                    index_cache().clear_poison();
                }
            }
        }
        close_document(handle);

        assert!(
            panics.is_empty(),
            "ROBUSTNESS FAILURE — a negative page index must be rejected before \
             the `as u32` cast; `(index as u32) + 1` overflows for index == -1 \
             (docedit.rs:331). {} panicking call(s):\n  {}",
            panics.len(),
            panics.join("\n  ")
        );
    });
    assert!(!v.is_failure(), "{v:?}");
}

/// A handle that was never opened must be inert on every entry point rather than
/// indexing a stale slot.
///
/// The probe values are constrained to handles the registry can never hand out:
/// `next_handle()` (registry.rs:26) counts UP from 1, so every live handle is a
/// small positive integer, and search.rs:277 reserves `i64::MAX - 777`. Probing
/// `1` or `i64::MAX - 777` here would `close_document` a document another test is
/// actively using — which is exactly what this test did in its first version, and
/// it broke `tests::edit_render_tests::radio_group_clears_siblings`.
#[test]
fn unknown_and_closed_handles_are_inert() {
    let v = guarded("stale-handles", construct_budget(), || {
        for h in [0i64, -1, -999_999, i64::MIN, i64::MIN + 1, i64::MAX] {
            assert_eq!(page_count(h), 0, "unknown handle {h} must report 0 pages");
            assert!(render_page(h, 0).is_none());
            assert!(render_page(h, i32::MAX).is_none());
            assert!(document_text(h).is_none());
            assert!(search_document(h, "x").is_none());
            close_document(h); // must be idempotent
            close_document(h);
        }
    });
    assert!(!v.is_failure(), "stale-handle probe failed: {v:?}");
}

// ---------------------------------------------------------------------------
// LAYER 2 — adversarial constructs, one named hazard each
// ---------------------------------------------------------------------------

/// Compressed data that expands to `total` zero bytes, built without ever
/// holding `total` bytes in memory.
fn zero_bomb(total: usize) -> Vec<u8> {
    use flate2::write::ZlibEncoder;
    use std::io::Write;
    let mut e = ZlibEncoder::new(Vec::new(), flate2::Compression::fast());
    let chunk = vec![0u8; 1 << 20];
    let mut written = 0usize;
    while written < total {
        let take = chunk.len().min(total - written);
        e.write_all(&chunk[..take]).unwrap();
        written += take;
    }
    e.finish().unwrap()
}
