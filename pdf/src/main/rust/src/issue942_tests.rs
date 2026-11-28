//! Regression harness for GitHub issue #942 ("crashes when opening" a
//! 940-page pdfTeX book, app v2.6.11b).
//!
//! What the reported document is (verified by host-side reproduction with
//! the file itself, which is copyrighted book text and is NOT committed):
//! a 940-page LaTeX-produced PDF 1.5, 202 compressed object streams, 59
//! subset Type1 fonts, ~8.8k link annotations, and an embedded text layer
//! on every content page. Diving it on the host target shows the current
//! parser/renderer handles it at *every* entry point the viewer calls at
//! open — `open_document`, `render_page` for all 940 pages, `ensure_index`
//! (2,324,745 glyph spans, ~74 MB of retained span tuples), `list_outline`,
//! `document_text`, per-page listings and search (240 s full pass in a
//! debug build, 238 MB peak RSS) — and the Kotlin wire parser decodes its
//! largest pages in ~25 ms. So on HEAD there is no malformed object in it
//! that panics this pipeline; the shipped-build crash is the *open-time
//! work* it triggers (the viewer eagerly built that whole-book index
//! before the first page was useful, with the release profile's
//! `panic = "abort"` turning any failure in the eager path into a process
//! kill instead of a failed page).
//!
//! The fix gated the eager prewarm by page count on the Kotlin side
//! (`SafePdfDocument.MAX_PREWARM_PAGES`); this module is the native half
//! of the regression net. It must never be able to turn into the kind of
//! silent skip `issue321_tests` warns about, so:
//!
//!  * a synthetic many-page book (built here, no fixture needed) is driven
//!    through open + render-every-page + index on every run; and
//!  * the real reported file can additionally be driven by pointing
//!    `ISSUE942_PDF` at it, with `ISSUE942_REQUIRE_PDF=1` making its
//!    absence a hard failure instead of a skip.

#[cfg(test)]
mod issue942 {
    use crate::*;

    /// Assemble a classic-xref PDF from `(object number, body)` pairs.
    fn raw_pdf(objs: &[(u32, Vec<u8>)], trailer_extra: &str) -> Vec<u8> {
        let mut out = b"%PDF-1.5\n".to_vec();
        let mut offsets = Vec::new();
        for (num, body) in objs {
            offsets.push((*num, out.len()));
            out.extend_from_slice(format!("{num} 0 obj\n").as_bytes());
            out.extend_from_slice(body);
            out.extend_from_slice(b"\nendobj\n");
        }
        let xref_at = out.len();
        let max = objs.iter().map(|(n, _)| *n).max().unwrap_or(0);
        out.extend_from_slice(format!("xref\n0 {}\n", max + 1).as_bytes());
        out.extend_from_slice(b"0000000000 65535 f \n");
        for n in 1..=max {
            let off = offsets.iter().find(|(num, _)| *num == n).map(|(_, o)| *o).unwrap_or(0);
            out.extend_from_slice(format!("{off:010} 00000 n \n").as_bytes());
        }
        out.extend_from_slice(
            format!("trailer\n<< /Size {} {trailer_extra} >>\nstartxref\n{xref_at}\n%%EOF\n", max + 1).as_bytes(),
        );
        out
    }

    fn raw_stream(dict: &str, content: &[u8]) -> Vec<u8> {
        let mut v = format!("{dict}\nstream\n").into_bytes();
        v.extend_from_slice(content);
        v.extend_from_slice(b"\nendstream");
        v
    }

    /// The synthetic trigger: many ordinary text pages behind one /Pages
    /// tree — the shape whose only unusual property is scale.
    fn many_page_book(pages: u32) -> Vec<u8> {
        let mut objs: Vec<(u32, Vec<u8>)> = vec![
            (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
            (5, b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_vec()),
        ];
        let mut kids = String::new();
        for i in 0..pages {
            let page_obj = 10 + i * 2;
            let content_obj = page_obj + 1;
            kids.push_str(&format!("{page_obj} 0 R "));
            objs.push((
                page_obj,
                format!(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] \
                     /Contents {content_obj} 0 R \
                     /Resources << /Font << /F1 5 0 R >> >> >>"
                )
                .into_bytes(),
            ));
            let mut content = b"BT /F1 11 Tf ".to_vec();
            for line in 0..30 {
                content.extend_from_slice(
                    format!("1 0 0 1 40 {} Tm (page {} line {} of a long book) Tj ", 740 - line * 12, i, line)
                        .as_bytes(),
                );
            }
            content.extend_from_slice(b"ET");
            objs.push((
                content_obj,
                raw_stream(&format!("<< /Length {} >>", content.len()), &content),
            ));
        }
        objs.push((
            2,
            format!("<< /Type /Pages /Kids [{kids}] /Count {pages} >>").into_bytes(),
        ));
        raw_pdf(&objs, "/Root 1 0 R")
    }

    /// Drive one document (bytes) through the viewer's open-time sequence:
    /// open, render every page, build the whole search index. Returns the
    /// page count, or panics with the failing step named.
    fn open_render_and_index_all(bytes: &[u8]) -> usize {
        let handle = open_document(bytes);
        assert!(handle != 0, "open_document refused the document");
        let pages = {
            let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
            reg.get(&handle).map(|d| d.get_pages().len()).unwrap_or(0)
        };
        assert!(pages > 0, "document reports no pages");
        for i in 0..pages {
            let wire = render_page(handle, i as i32);
            assert!(wire.is_some(), "page {i} of {pages} failed to render");
            let wire = wire.unwrap();
            assert!(wire.len() >= 20, "page {i} wire is truncated ({} bytes)", wire.len());
        }
        let index = ensure_index(handle).expect("search index refused the document");
        assert_eq!(index.len(), pages, "index must cover every page");
        close_document(handle);
        pages
    }

    #[test]
    fn a_many_page_book_opens_renders_and_indexes_in_full() {
        // 150 pages keeps the debug-build runtime in single-digit seconds
        // while still being far past anything the prewarm gate allows and
        // past every per-page cache in the viewer. The reported book is
        // 940 pages of this shape.
        let bytes = many_page_book(150);
        let pages = open_render_and_index_all(&bytes);
        assert_eq!(pages, 150);
    }

    #[test]
    fn the_reported_book_pdf_when_provided() {
        let Some(path) = std::env::var_os("ISSUE942_PDF") else {
            if std::env::var_os("ISSUE942_REQUIRE_PDF").is_some() {
                panic!("ISSUE942_PDF is not set but ISSUE942_REQUIRE_PDF=1: refusing to skip");
            }
            eprintln!(
                "[issue942] SKIPPING the real-book drive: set ISSUE942_PDF to the \
                 reported file (940-page pdfTeX book) to run it. The synthetic \
                 many-page test above is NOT a substitute for it."
            );
            return;
        };
        let bytes = std::fs::read(&path).expect("ISSUE942_PDF must be readable");
        let pages = open_render_and_index_all(&bytes);
        eprintln!("[issue942] reported book: {pages} pages opened, rendered and indexed");
    }
}
