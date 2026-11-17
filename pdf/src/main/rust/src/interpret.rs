use crate::*;

/// Recursion depth allowed for TRANSPARENCY GROUP emission (`GroupPush`) and for
/// opening a soft-mask BRACKET, matching the `depth < 10` form-XObject limit in the
/// `Do` arm so a form that recurses to 10 does not lose its group on the way.
///
/// It is NOT the cap on EXPANDING a soft-mask group. That is
/// [`MAX_PATTERN_RECURSION`] (4), enforced inside `render_soft_mask_group`, and the
/// two are deliberately different: expanding a mask re-interprets a whole content
/// stream per masked operation, and unlike `Do` it is not metered by
/// [`MAX_FORM_INVOCATIONS`], so a self-referential mask branches unbounded. Raising
/// it to 10 to "keep them in step" would reopen that.
///
/// The comment here used to claim they WERE in step, and that a mask over the cap
/// merely "painted the form unmasked". Neither was true: `wrap_with_soft_mask`
/// opened the bracket at this depth while `render_soft_mask_group` refused to fill
/// it past 4, and an empty bracket composites `DST_IN` against an all-zero mask —
/// it ERASED the content. `wrap_with_soft_mask` now unwinds the bracket on a
/// refusal, which is what actually makes the over-cap case paint unmasked.
pub(crate) const MAX_GROUP_DEPTH: u32 = 10;

/// Hard ceiling on saved graphics states. §8.4.2 puts no limit on `q` nesting, and
/// declining to save on overflow let colour/CTM changes leak past the matching
/// `Q`, misrendering everything after it. This is deliberately far above any real
/// document so the lossy path is unreachable in practice.
pub(crate) const MAX_GRAPHICS_STACK_HARD: usize = MAX_GRAPHICS_STACK * 16;

/// Total form-XObject invocations allowed per top-level content stream.
///
/// §8.10.1 imposes no limit, and the `depth < 10` guard in the `Do` arm bounds the
/// DEPTH of `Do` recursion but not its BRANCHING. A ~200-byte form whose own
/// `/Resources` name it six times therefore reaches 6^10 = 60M invocations — each
/// re-decoding the stream and rebuilding six resource maps — which measured 48 s in
/// a release build and grows by an order of magnitude per extra `Do` in the cell.
/// The depth cap cannot express that; only a total budget can. Set far above any
/// real page (a heavily stamped map is a few thousand) so it binds only on an attack.
pub(crate) const MAX_FORM_INVOCATIONS: u32 = 50_000;

thread_local! {
    /// Remaining [`MAX_FORM_INVOCATIONS`] for the render in progress on this
    /// thread. Refilled by the OUTERMOST [`FormBudgetScope`], never by a nested
    /// one, so a whole render tree shares one budget however deep its root sits.
    static FORM_BUDGET: std::cell::Cell<u32> = const { std::cell::Cell::new(MAX_FORM_INVOCATIONS) };
    /// Number of live [`FormBudgetScope`]s, i.e. whether a render is already in
    /// progress on this thread. Zero means the next scope is the outermost one.
    static FORM_BUDGET_SCOPES: std::cell::Cell<u32> = const { std::cell::Cell::new(0) };
}

/// Establishes the form-invocation budget for one top-level render.
///
/// This deliberately does NOT key off the interpreter's `depth` parameter.
/// `depth` counts `Do`/pattern/glyph NESTING for the §8.10.1 recursion cap, and
/// several legitimate top-level entries start it above 0:
/// `annotations::render_annotation` enters at 1 for an appearance stream (as
/// does a form-field appearance through it), and the tiling-pattern, soft-mask
/// group and Type 3 CharProc paths all enter at `depth + 1`. Refilling only at
/// `depth == 0` therefore left every one of those running on whatever the
/// previous render had left over — zero, once anything had exhausted it — so
/// every `Do` inside an annotation or field appearance was silently dropped and
/// the appearance rendered as nothing. A budget that can silently reach zero on
/// a legitimate path is worse than no budget, because it fails closed with no
/// error anywhere; the depth-independent scope is what makes that unreachable.
///
/// RAII rather than a manual decrement so an early return or a panic unwinding
/// out of the interpreter cannot leave the thread permanently "inside" a render.
struct FormBudgetScope;

impl FormBudgetScope {
    fn enter() -> Self {
        FORM_BUDGET_SCOPES.with(|n| {
            if n.get() == 0 {
                FORM_BUDGET.with(|b| b.set(MAX_FORM_INVOCATIONS));
            }
            n.set(n.get() + 1);
        });
        FormBudgetScope
    }
}

impl Drop for FormBudgetScope {
    fn drop(&mut self) {
        FORM_BUDGET_SCOPES.with(|n| n.set(n.get().saturating_sub(1)));
    }
}

/// Consume one form-XObject invocation, or report the budget exhausted.
fn take_form_budget() -> bool {
    FORM_BUDGET.with(|b| {
        let n = b.get();
        if n == 0 {
            return false;
        }
        b.set(n - 1);
        true
    })
}

/// The render mode to SHOW a run in while it sits inside an OFF optional-content
/// group (§8.11.3.3: such content shall not be drawn).
///
/// Modes 0-2 become 3 — "neither fill nor stroke" (§9.3.6 Table 106) — which
/// suppresses the ink while keeping the run in the primitive stream, so
/// `search::build_index` still sees it. Hidden is not absent.
///
/// Modes 4-7 become **7**, not 3. 4-6 are "paint AND add to clip" and 7 is
/// "clip only, paint nothing", so 7 is exactly what suppressing the paint of a
/// clipping text run leaves behind. Collapsing them to 3 dropped the clip
/// contribution too: `text_clip_used` was already latched from the real mode, so
/// `ET` still emitted `Prim::TextClipApply`, but every glyph went out tagged mode
/// 3 and the renderer accumulates outlines only from `rm in 4..7` — so it opened a
/// canvas level and narrowed NOTHING. Artwork that should have shown only inside
/// the letterforms then painted as a full opaque rectangle over whatever was
/// beneath it. Reported by `r5-kotlin`.
///
/// Keeping the clip is also the reading this file already commits to for `W n`
/// (see `emit_one_clip`): the clipping path is a graphics-state parameter
/// (§8.4.1 Table 52), and §8.11.3.3 suppresses DRAWING, not state.
fn hidden_render_mode(rm: i64) -> i64 {
    if rm >= 4 { 7 } else { 3 }
}

/// Whether a show operator that just ran should latch the text clip.
///
/// §9.4.3 builds the clip from the outlines of the glyphs SHOWN, and the
/// consumer resets its accumulated path ONLY when it sees the `TextClipApply`
/// that `ET` emits. The gate is deliberately HERE and not in the consumer: the
/// consumer applies the marker unconditionally, so an empty accumulation clips
/// to EMPTY. That is the right reading for a run that shows only whitespace — a
/// space has no contours, so it contributes no outline — and it is safe only
/// because this side does not send the marker for a run that showed nothing.
///
/// "Shown" is an ITERATION property and is measured with the same decoder the
/// show path uses. Two cheaper proxies are both wrong, and each was tried:
///
/// * `!bytes.is_empty()` is an OPERAND property. `FontInfo::for_each_code`'s
///   Identity-H/V branch consumes codes in PAIRS (`while i + 1 < bytes.len()`),
///   so a ONE-byte string in a two-byte font is non-empty and yields ZERO codes.
///   Latching there sent the marker over an empty accumulation and blanked every
///   mark to the matching `ClipPop`. Caught by `fix-text`, `hunt-missing` and
///   `differential`.
/// * "a prim was emitted" is discontinuous. In a ten-glyph `Tr 7` run over a
///   full-page gradient, one glyph resolving gives a one-letterform aperture and
///   leaks ~0.3% of the page; zero resolving sends no marker, no clip, and the
///   gradient covers 100% of it. The failure inverts on a single `/ToUnicode`
///   entry. `differential` measured the two outcomes at MAE 15.15 (blanked, all
///   surviving content intact) against 155.70 (unclipped, none of it intact).
///
/// So a run whose glyphs were all dropped still latches and clips to EMPTY. That
/// is deliberate: the true aperture is a handful of letterforms, so empty is a
/// SUBSET of it and the error is bounded by the text's own area, whereas not
/// clipping is a superset of everything and the error is the whole page.
///
/// An earlier version rested this on the soft-mask precedent — an unexpandable
/// mask paints unmasked, losing the EFFECT not the artwork. `hunt-missing`
/// showed the disanalogy that sinks it: a soft mask MODULATES content that has
/// its own geometry, so dropping it leaks only within that path, while a text
/// clip DEFINES the extent, so dropping it leaks without bound.
///
/// `shown_mode` is the mode BEFORE [`hidden_render_mode`] may have rewritten it:
/// an optional-content group that is off still contributes its clip (see that
/// function).
fn latches_text_clip(
    shown_mode: i64,
    fonts: &HashMap<Vec<u8>, FontInfo>,
    font_key: &[u8],
    bytes: &[u8],
) -> bool {
    // Checked first so the extra decode only happens for clipping modes, which
    // are rare — this must not put a second pass over every string on the hot
    // text path.
    if shown_mode < 4 {
        return false;
    }
    match fonts.get(font_key) {
        // `FontInfo::shows_any_glyph`, not a second copy of its body: that
        // method is what the Identity-H matrix in `fonts.rs` pins, and a
        // duplicate here would leave the only coverage of the case that blanked
        // pages exercising a function the renderer never calls. Flagged by
        // `hunt-wrong2` and `hunt-missing`.
        Some(fi) => fi.shows_any_glyph(bytes),
        // No font resource: `draw.rs` emits a single record for the whole run,
        // gated only on the string being non-empty. Collapsing this match to
        // `.map(..).unwrap_or(false)` would drop that record's clip on the floor
        // and contaminate the next text object with it.
        None => !bytes.is_empty(),
    }
}

pub(crate) fn bezier_steps_for_flatness(hull: [(f64, f64); 4], flatness: f64) -> usize {
    // §10.6.2 defines flatness as a tolerance in DEVICE space, so the segment
    // count has to scale with the curve's device-space size. A fixed count made
    // large curves visibly faceted, and `i` could collapse a curve to one line.
    let mut len = 0.0;
    for w in hull.windows(2) {
        len += (w[1].0 - w[0].0).hypot(w[1].1 - w[0].1);
    }
    if !len.is_finite() || len <= 0.0 {
        return 1;
    }
    let tol = if flatness > 0.0 { flatness.min(3.0) } else { 0.25 };
    ((len / tol).sqrt().ceil() as usize).clamp(4, 64)
}

pub(crate) fn shoelace_area(pts: &[(f64,f64)]) -> f64 {
    if pts.len() < 3 { return 0.0; }
    let mut area = 0.0;
    for i in 0..pts.len() {
        let j = (i+1) % pts.len();
        area += pts[i].0 * pts[j].1 - pts[j].0 * pts[i].1;
    }
    area * 0.5
}

/// Parse ExtGState dash `D`: Spec §8.4.3.6 canonical is [[dashArray] phase] nested. Flat [a b c] lenient where last=phase only for len>=3 (critical fix: pure [3 3] must NOT become [3] phase 3).
///
/// Every number read here is filtered for finiteness as well as sign: §7.3.3 bounds a
/// real to the implementation limit and lopdf's `Object::Real` is an f32, so a long
/// literal yields INFINITY — which `>= 0.0` admits. An infinite dash segment reaches
/// `Prim::Stroke.dash`; a NaN phase reaches `dash_phase`, and NaN survives every clamp
/// on the way to the renderer because comparisons against it are false.
pub(crate) fn parse_dash_d_array(doc: &Document, arr: &[Object]) -> (Vec<f64>, f64) {
    // Derived from the shared cap, not a local literal: Kotlin's wire decoder
    // rejects a page outright when the dash array exceeds its own bound, so the
    // parser must not allow a longer one.
    const MAX_DASH: usize = MAX_DASH_LEN;
    let ok = |v: &f64| v.is_finite() && *v >= 0.0;
    if arr.is_empty() {
        return (Vec::new(), 0.0);
    }
    // Canonical nested [[dashArray] phase]
    if arr.len() == 2 {
        let first_is_arr = matches!(arr[0], Object::Array(_)) || matches!(deref(doc, &arr[0]), Some(Object::Array(_)));
        if first_is_arr {
            let inner = match &arr[0] {
                Object::Array(a) => a.clone(),
                _ => deref(doc, &arr[0]).and_then(|o| o.as_array().ok()).cloned().unwrap_or_default(),
            };
            let phase = deref(doc, &arr[1]).and_then(num).or_else(|| num(&arr[1])).filter(|v| v.is_finite()).unwrap_or(0.0);
            let dashes: Vec<f64> = inner.iter().filter_map(|o| deref(doc, o).and_then(num).or_else(|| num(o))).filter(ok).take(MAX_DASH).collect();
            return (dashes, phase);
        }
    }
    // Check for any nested arrays
    let has_nested = arr.iter().any(|o| matches!(o, Object::Array(_)) || matches!(deref(doc, o), Some(Object::Array(_))));
    let nums: Vec<f64> = arr.iter().filter_map(|o| deref(doc, o).and_then(num).or_else(|| num(o))).collect();
    if has_nested {
        let mut dashes = Vec::new();
        for o in arr {
            match o {
                Object::Array(inner) => {
                    dashes.extend(inner.iter().filter_map(|x| deref(doc, x).and_then(num).or_else(|| num(x))).filter(ok));
                }
                _ => {
                    if let Some(Object::Array(inner)) = deref(doc, o) {
                        dashes.extend(inner.iter().filter_map(|x| deref(doc, x).and_then(num).or_else(|| num(x))).filter(ok));
                    }
                }
            }
        }
        if dashes.is_empty() {
            dashes = nums;
        }
        return (dashes.into_iter().filter(|v| ok(v)).take(MAX_DASH).collect(), 0.0);
    }
    if nums.is_empty() {
        return (Vec::new(), 0.0);
    }
    // Lenient flat [dashes..., phase] only for len>=3 to avoid [3 3] bug
    if nums.len() >= 3 {
        let phase = nums.last().copied().filter(|v| v.is_finite()).unwrap_or(0.0);
        let dashes: Vec<f64> = nums[..nums.len()-1].iter().copied().filter(|v| ok(v)).take(MAX_DASH).collect();
        (dashes, phase)
    } else {
        (nums.into_iter().filter(|v| ok(v)).take(MAX_DASH).collect(), 0.0)
    }
}


pub(crate) fn parse_dash_extgstate(doc: &Document, obj: &Object) -> (Vec<f64>, f64) {
    match deref(doc, obj).unwrap_or(obj) {
        Object::Array(arr) => parse_dash_d_array(doc, arr),
        _ => (Vec::new(), 0.0),
    }
}

/// The shared context threaded through the per-operator handlers: the
/// document and resources being interpreted, the primitive sink, the
/// form/pattern recursion depth and whether only text is wanted (search
/// indexing rather than rendering).
pub(crate) struct HandlerCtx<'a> {
    pub doc: &'a Document,
    pub resources: Option<&'a lopdf::Dictionary>,
    pub prims: &'a mut Vec<Prim>,
    pub depth: u32,
    pub text_only: bool,
}

include!("interpret_part1.rs");
include!("interpret_part2.rs");
include!("interpret_part3.rs");
include!("interpret_part4.rs");
include!("interpret_part5.rs");
include!("interpret_part6.rs");
include!("interpret_part7.rs");
include!("interpret_part8.rs");
include!("interpret_part9.rs");
include!("interpret_part10.rs");
include!("interpret_part11.rs");
include!("interpret_part12.rs");
include!("interpret_part13.rs");
include!("interpret_part14.rs");