//! Performance measurement harness.
//!
//! Every test here is `#[ignore]`d so it never runs in the normal suite. Run it
//! explicitly, and only in release:
//!
//! ```text
//! cargo test --release -- --ignored --nocapture
//! ```
//!
//! Debug-mode timings for this crate are misleading by more than an order of
//! magnitude (no inlining, no `lto`, overflow checks on every arithmetic op), and
//! release is what ships, so a debug number is not evidence about anything.
//!
//! There is no `criterion` dependency: `pdf_render` is `crate-type = ["cdylib"]`,
//! so a `benches/` target cannot link against it, and criterion only appears in
//! the workspace lockfile as a dev-dependency of the vendored `third_party`
//! crates (registry source, not vendored by path). This hand-rolls
//! `std::time::Instant` instead and reports a median with spread rather than a
//! single sample.
//!
//! Methodology, applied uniformly by [`bench`]:
//! - discard warmup iterations so the measurement excludes first-touch page
//!   faults and cold instruction cache,
//! - take the *median* of the remaining samples, not the mean, because a single
//!   scheduler preemption skews a mean and cannot skew a median,
//! - report the interquartile spread as a percentage of the median, so a reader
//!   can tell whether a difference between two rows exceeds the noise,
//! - report min, since for a deterministic single-threaded workload the fastest
//!   observed run is the one least perturbed by the rest of the machine.
//!
//! Two differences are only called a win when the gap exceeds the measured noise
//! floor (see [`noise_floor`]); otherwise this reports "within noise".

use crate::*;
use lopdf::{dictionary, Dictionary, Document, Object, ObjectId, Stream};
use std::alloc::{GlobalAlloc, Layout, System};
use std::hint::black_box;
use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering::Relaxed};
use std::time::Instant;

// ---------------------------------------------------------------------------
// Peak-heap instrumentation
// ---------------------------------------------------------------------------

/// Counting shim over the system allocator, used to report peak *heap* bytes.
///
/// Reading RSS portably from inside a test needs a platform crate this crate
/// does not depend on, so instead this measures net heap live-bytes directly,
/// which is the quantity the OOM question actually turns on.
///
/// Accounting is fully disabled unless [`TRACK`] is set, so the normal test
/// suite pays one relaxed bool load per allocation and nothing else. Peak is
/// measured as a high-water mark of net bytes allocated since [`mem_reset`], so
/// frees of blocks that predate the reset can bias it low; that makes the
/// reported figure a lower bound, which is the safe direction for a budget.
struct TrackingAlloc;

static TRACK: AtomicBool = AtomicBool::new(false);
static CUR: AtomicIsize = AtomicIsize::new(0);
static PEAK: AtomicIsize = AtomicIsize::new(0);

unsafe impl GlobalAlloc for TrackingAlloc {
    unsafe fn alloc(&self, l: Layout) -> *mut u8 {
        let p = System.alloc(l);
        if !p.is_null() && TRACK.load(Relaxed) {
            let cur = CUR.fetch_add(l.size() as isize, Relaxed) + l.size() as isize;
            PEAK.fetch_max(cur, Relaxed);
        }
        p
    }
    unsafe fn dealloc(&self, p: *mut u8, l: Layout) {
        if TRACK.load(Relaxed) {
            CUR.fetch_sub(l.size() as isize, Relaxed);
        }
        System.dealloc(p, l)
    }
}

#[global_allocator]
static ALLOC: TrackingAlloc = TrackingAlloc;

fn mem_reset() {
    CUR.store(0, Relaxed);
    PEAK.store(0, Relaxed);
    TRACK.store(true, Relaxed);
}

/// Peak net heap bytes since [`mem_reset`], and stop accounting.
fn mem_peak() -> usize {
    TRACK.store(false, Relaxed);
    PEAK.load(Relaxed).max(0) as usize
}

/// Net heap bytes live *right now*, without stopping accounting. Comparing this
/// against [`mem_peak`] separates what a phase RETAINS from what it transiently
/// allocated while running.
fn mem_current() -> usize {
    CUR.load(Relaxed).max(0) as usize
}

fn mib(bytes: usize) -> f64 {
    bytes as f64 / (1024.0 * 1024.0)
}

// ---------------------------------------------------------------------------
// Timing harness
// ---------------------------------------------------------------------------

struct Stats {
    median_ms: f64,
    min_ms: f64,
    /// Interquartile spread as a percentage of the median.
    spread_pct: f64,
    iters: usize,
}

impl Stats {
    fn row(&self, label: &str, n: usize, extra: &str) {
        println!(
            "{:<38} n={:<9} median={:>10.3} ms  min={:>10.3} ms  spread={:>5.1}%  iters={:<3} {}",
            label, n, self.median_ms, self.min_ms, self.spread_pct, self.iters, extra
        );
    }
}

/// Time `f` `iters` times after `warmup` discarded runs; median + IQR spread.
fn bench<F: FnMut()>(iters: usize, mut f: F) -> Stats {
    let warmup = 3.min(iters);
    for _ in 0..warmup {
        f();
    }
    let mut samples = Vec::with_capacity(iters);
    for _ in 0..iters {
        let t = Instant::now();
        f();
        samples.push(t.elapsed().as_secs_f64() * 1000.0);
    }
    samples.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let median = samples[samples.len() / 2];
    let q1 = samples[samples.len() / 4];
    let q3 = samples[samples.len() * 3 / 4];
    Stats {
        median_ms: median,
        min_ms: samples[0],
        spread_pct: if median > 0.0 {
            (q3 - q1) / median * 100.0
        } else {
            0.0
        },
        iters,
    }
}

/// Ratio of two medians, plus whether the gap clears the noise floor.
fn verdict(base: &Stats, alt: &Stats, noise_pct: f64) -> String {
    if base.median_ms <= 0.0 {
        return "n/a".into();
    }
    let ratio = alt.median_ms / base.median_ms;
    let delta_pct = (ratio - 1.0).abs() * 100.0;
    // Require the gap to exceed both benchmarks' own spread and the machine
    // noise floor before calling it real.
    let threshold = noise_pct.max(base.spread_pct).max(alt.spread_pct);
    if delta_pct <= threshold {
        format!(
            "WITHIN NOISE (delta {:.1}% <= threshold {:.1}%)",
            delta_pct, threshold
        )
    } else if ratio < 1.0 {
        format!("{:.2}x FASTER (delta {:.1}%)", 1.0 / ratio, delta_pct)
    } else {
        format!("{:.2}x SLOWER (delta {:.1}%)", ratio, delta_pct)
    }
}

/// Per-step growth factor of a series of medians, printed next to the doubling
/// of `n`. Linear scaling shows the same factor as the `n` step; a larger factor
/// is super-linear and a factor near 1.0 means a constant dominates.
fn scaling(ns: &[usize], meds: &[f64]) {
    println!("  scaling:");
    for i in 1..ns.len() {
        let nfac = ns[i] as f64 / ns[i - 1] as f64;
        let tfac = if meds[i - 1] > 0.0 {
            meds[i] / meds[i - 1]
        } else {
            0.0
        };
        let exponent = if nfac > 1.0 {
            tfac.ln() / nfac.ln()
        } else {
            0.0
        };
        let kind = if exponent < 0.4 {
            "constant-dominated"
        } else if exponent < 1.3 {
            "~linear"
        } else if exponent < 1.7 {
            "super-linear"
        } else {
            "~QUADRATIC or worse"
        };
        println!(
            "    n {:>7} -> {:>7} ({:.1}x):  time {:.2}x  => O(n^{:.2})  {}",
            ns[i - 1], ns[i], nfac, tfac, exponent, kind
        );
    }
}

// ---------------------------------------------------------------------------
// Synthetic document builders
// ---------------------------------------------------------------------------

/// Build an n-page document whose pages all share one inline resources dict
/// (so any indirect font object inside it is shared across pages, which is what
/// the font cache keys on).
fn build_doc(contents: &[Vec<u8>], res: Dictionary) -> (Document, Vec<ObjectId>) {
    let mut doc = Document::with_version("1.5");
    let pages_id = doc.new_object_id();
    let mut kids = Vec::new();
    let mut page_ids = Vec::new();
    for c in contents {
        let cid = doc.add_object(Stream::new(Dictionary::new(), c.clone()));
        let pid = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => cid,
            "Resources" => res.clone(),
        });
        kids.push(pid.into());
        page_ids.push(pid);
    }
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages",
            "Kids" => kids,
            "Count" => contents.len() as i64,
        }),
    );
    let cat = doc.add_object(dictionary! {
        "Type" => "Catalog",
        "Pages" => pages_id,
    });
    doc.trailer.set("Root", cat);
    (doc, page_ids)
}

fn u16b(v: u16) -> [u8; 2] {
    v.to_be_bytes()
}
fn u32b(v: u32) -> [u8; 4] {
    v.to_be_bytes()
}

/// A structurally valid TrueType font with `n_glyphs` glyphs of `pts` points
/// each, so the cost of parsing an embedded font program can be scaled.
///
/// The contours are monotone staircases rather than letter shapes: geometrically
/// meaningless, but the table structure, point counts and parsing work are real,
/// which is what is being measured.
fn synth_ttf(n_glyphs: u16, pts: u16) -> Vec<u8> {
    let ng = n_glyphs.max(1);
    let np = pts.max(2);

    // glyf: one simple glyph per id, single contour of `np` points.
    let mut glyf: Vec<u8> = Vec::new();
    let mut loca: Vec<u32> = vec![0];
    for _ in 0..ng {
        let start = glyf.len();
        glyf.extend_from_slice(&u16b(1)); // numberOfContours
        glyf.extend_from_slice(&u16b(0)); // xMin
        glyf.extend_from_slice(&u16b(0)); // yMin
        glyf.extend_from_slice(&u16b(500)); // xMax
        glyf.extend_from_slice(&u16b(700)); // yMax
        glyf.extend_from_slice(&u16b(np - 1)); // endPtsOfContours[0]
        glyf.extend_from_slice(&u16b(0)); // instructionLength
        // flags: on-curve | x-short | y-short | x-positive | y-positive
        for _ in 0..np {
            glyf.push(0x01 | 0x02 | 0x04 | 0x10 | 0x20);
        }
        glyf.extend(std::iter::repeat_n(3, np as usize)); // dx
        glyf.extend(std::iter::repeat_n(5, np as usize)); // dy
        while glyf.len() % 4 != 0 {
            glyf.push(0);
        }
        debug_assert!(glyf.len() > start);
        loca.push(glyf.len() as u32);
    }
    let mut loca_b = Vec::new();
    for o in &loca {
        loca_b.extend_from_slice(&u32b(*o));
    }

    // head
    let mut head = Vec::new();
    head.extend_from_slice(&u32b(0x0001_0000)); // version
    head.extend_from_slice(&u32b(0x0001_0000)); // fontRevision
    head.extend_from_slice(&u32b(0)); // checkSumAdjustment
    head.extend_from_slice(&u32b(0x5F0F_3CF5)); // magic
    head.extend_from_slice(&u16b(0)); // flags
    head.extend_from_slice(&u16b(1000)); // unitsPerEm
    head.extend_from_slice(&[0u8; 16]); // created + modified
    head.extend_from_slice(&u16b(0)); // xMin
    head.extend_from_slice(&u16b(0)); // yMin
    head.extend_from_slice(&u16b(1000)); // xMax
    head.extend_from_slice(&u16b(1000)); // yMax
    head.extend_from_slice(&u16b(0)); // macStyle
    head.extend_from_slice(&u16b(8)); // lowestRecPPEM
    head.extend_from_slice(&u16b(2)); // fontDirectionHint
    head.extend_from_slice(&u16b(1)); // indexToLocFormat = long
    head.extend_from_slice(&u16b(0)); // glyphDataFormat

    // maxp v1.0
    let mut maxp = Vec::new();
    maxp.extend_from_slice(&u32b(0x0001_0000));
    maxp.extend_from_slice(&u16b(ng));
    maxp.extend_from_slice(&[0u8; 26]);

    // hhea
    let mut hhea = Vec::new();
    hhea.extend_from_slice(&u32b(0x0001_0000));
    hhea.extend_from_slice(&u16b(800)); // ascender
    hhea.extend_from_slice(&u16b(0xFF38)); // descender (-200)
    hhea.extend_from_slice(&u16b(0)); // lineGap
    hhea.extend_from_slice(&u16b(1000)); // advanceWidthMax
    hhea.extend_from_slice(&[0u8; 22]);
    hhea.extend_from_slice(&u16b(ng)); // numberOfHMetrics

    // hmtx
    let mut hmtx = Vec::new();
    for _ in 0..ng {
        hmtx.extend_from_slice(&u16b(500)); // advanceWidth
        hmtx.extend_from_slice(&u16b(0)); // lsb
    }

    // cmap: one format-0 subtable (platform 1, Mac Roman), codes 0..255.
    let mut sub = Vec::new();
    sub.extend_from_slice(&u16b(0)); // format
    sub.extend_from_slice(&u16b(262)); // length
    sub.extend_from_slice(&u16b(0)); // language
    for c in 0..256u16 {
        sub.push((c % ng.min(256)) as u8);
    }
    let mut cmap = Vec::new();
    cmap.extend_from_slice(&u16b(0)); // version
    cmap.extend_from_slice(&u16b(1)); // numTables
    cmap.extend_from_slice(&u16b(1)); // platformID
    cmap.extend_from_slice(&u16b(0)); // encodingID
    cmap.extend_from_slice(&u32b(12)); // offset
    cmap.extend_from_slice(&sub);

    // Assemble the sfnt. Table records must be in ascending tag order.
    let tables: Vec<(&[u8; 4], Vec<u8>)> = vec![
        (b"cmap", cmap),
        (b"glyf", glyf),
        (b"head", head),
        (b"hhea", hhea),
        (b"hmtx", hmtx),
        (b"loca", loca_b),
        (b"maxp", maxp),
    ];
    let num = tables.len() as u16;
    let mut out = Vec::new();
    out.extend_from_slice(&u32b(0x0001_0000)); // sfntVersion
    out.extend_from_slice(&u16b(num));
    let mut sr = 1u16;
    while sr * 2 <= num {
        sr *= 2;
    }
    out.extend_from_slice(&u16b(sr * 16)); // searchRange
    out.extend_from_slice(&u16b(0)); // entrySelector
    out.extend_from_slice(&u16b(num * 16 - sr * 16)); // rangeShift
    let mut offset = 12 + 16 * tables.len();
    let mut records = Vec::new();
    let mut body = Vec::new();
    for (tag, data) in &tables {
        records.extend_from_slice(*tag);
        records.extend_from_slice(&u32b(0)); // checksum (unverified by parsers)
        records.extend_from_slice(&u32b(offset as u32));
        records.extend_from_slice(&u32b(data.len() as u32));
        body.extend_from_slice(data);
        let mut pad = data.len();
        while pad % 4 != 0 {
            body.push(0);
            pad += 1;
        }
        offset += pad;
    }
    out.extend_from_slice(&records);
    out.extend_from_slice(&body);
    out
}

/// Resources with an embedded-TrueType simple font under `/F1`.
fn embedded_font_res(doc: &mut Document, n_glyphs: u16, pts: u16) -> Dictionary {
    let prog = synth_ttf(n_glyphs, pts);
    let len = prog.len() as i64;
    let ff = doc.add_object(Stream::new(
        dictionary! { "Length1" => len },
        prog,
    ));
    let fd = doc.add_object(dictionary! {
        "Type" => "FontDescriptor",
        "FontName" => "BenchFont",
        "Flags" => 4,
        "ItalicAngle" => 0,
        "Ascent" => 800,
        "Descent" => -200,
        "CapHeight" => 700,
        "StemV" => 80,
        "FontBBox" => vec![0.into(), (-200).into(), 1000.into(), 1000.into()],
        "FontFile2" => ff,
    });
    let widths: Vec<Object> = (0..256).map(|_| Object::Integer(500)).collect();
    let font = doc.add_object(dictionary! {
        "Type" => "Font",
        "Subtype" => "TrueType",
        "BaseFont" => "BenchFont",
        "FirstChar" => 0,
        "LastChar" => 255,
        "Widths" => widths,
        "FontDescriptor" => fd,
    });
    dictionary! { "Font" => dictionary! { "F1" => font } }
}

/// Resources with a non-embedded standard font (AFM metrics path).
fn standard_font_res(doc: &mut Document) -> Dictionary {
    let font = doc.add_object(dictionary! {
        "Type" => "Font",
        "Subtype" => "Type1",
        "BaseFont" => "Helvetica",
    });
    dictionary! { "Font" => dictionary! { "F1" => font } }
}

include!("perf_tests_part1.rs");
include!("perf_tests_part2.rs");
include!("perf_tests_part3.rs");
include!("perf_tests_part4.rs");