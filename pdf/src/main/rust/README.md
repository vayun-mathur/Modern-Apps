# pdf_render — memory-safe PDF renderer (Rust + JNI)

Parses PDFs entirely in Rust with [`lopdf`] (no pdfium / no system PDF stack)
and reduces each page to plain drawing primitives — text runs (with accurate advance),
filled/stroked polygons, raster images (JPEG, JPEG2000 via openjp2, JBIG2 via hayro-jbig2,
CCITTFax G3/G4 via fax crate with K/Columns/BlackIs1), clipping via ClipPush/Pop with
bezier retention, shading raster fallback Type2-7, transparency groups — for the
"Open PDF (safe)" viewer. Built into `libpdf_render.so` and called from Kotlin via `PdfNative`.

The point of "safe": all untrusted binary parsing happens in memory-safe Rust,
and the JNI boundary only ever passes a flat little-endian buffer of geometry + UTF-8 text,
so Kotlin never touches the raw PDF bytes.

## Scope (v3 — implemented)

- Text with `/ToUnicode` CMap decoding (1-byte simple fonts and 2-byte Identity-H / Type0),
  Latin-1 fallback, plus TrueType cmap recovery. Text rendering modes 0-7:
  0 fill (default), 1 stroke (via Prim::Text stroke_argb), 2 fill+stroke, 3 invisible,
  4 fill+clip, 5 stroke+clip, 6 fill+stroke+clip, 7 clip. Stroke text drawn in Kotlin via
  Paint.Style.STROKE then FILL. Accurate glyph advance via `/Widths/FirstChar/MissingWidth`
  and CID `/W/DW` plus `Tc+Tw+Th` — emitted in `Prim::Text.advance` extra f32 so Kotlin
  search rect can use accurate advance vs `size*0.5f` approximation. TextSelectionLayer
  uses `Paint.measureText` + Tz (h_scale) instead of cw approximation, SelGlyph stores
  String for ligatures `fi` U+FB01.
- Vector paths: lines, rectangles, filled/stroked paths, flattened beziers for drawing but
  clip path retains beziers via `PathOp` (Move/Line/Cubic/Close) for fidelity (Phase 6).
  Line width `w`, cap `J`, join `j`, miter `M`, flatness `i`, dash `d` with corrected
  `ExtGState D [[2 1] 0.5]` dash-phase fix via `parse_dash_d` helper. Kotlin dash double-scale
  bug fixed: Rust already scales dash by CTM avg, Kotlin uses dash directly.
- Clipping path: `W`/`W*` plus painting ops: degenerate clip guards `pts>=3 && shoelace>1e-3`,
  clip depth cap 64 Rust / 32 Kotlin saveCount, SavedState stack 128 cap.
  Rust `to_path` for ClipPush now preserves beziers via path_ops, Kotlin uses `cubicTo` path
  when available, falling back to lineTo for flattened v2.
- ExtGState `gs`: `/ExtGState` resources parsed, honoring `/CA`/`ca` alpha, `/LW`/`LC`/`LJ`/`ML`/`D`,
  `/BM` blend parsed enum (Normal,Multiply,Screen...) but only Multiply + Normal first handled
  in Kotlin via `Paint.blendMode` (API 29+), others fallback Normal.
- Images: XObject `Do` + inline `BI`/`ID`/`EI` (with chain decode `AHx/A85/LZW/Fl/CCF/DCT/JPX/JBIG2`
  case-insensitive, multiple filters). New decoders in `filters.rs`: `decode_ascii_hex`
  (strip whitespace,`>` EOD odd padded 0), `decode_ascii85` (z and `~>`), `decode_runlength`
  (EOD 128), `decode_lzw` via weezl with EarlyChange, `decode_ccitt` via fax crate
  `CcittParams{K,Columns,Rows,EndOfLine,BlackIs1}`, `decode_flate` zlib + raw deflate fallback
  with PNG predictor (10-15) via `lopdf::filters::png::decode_frame`. Chain decode
  `decode_stream_chain` iterates filter specs.
  JBIG2: `jbig2::decode_jbig2` returning RGBA via hayro-jbig2 `Image::new` / `new_embedded`
  with optional `JBIG2Globals` obj if present. Guard returns placeholder gray/red-border
  bitmap if decode fails instead of blank per Phase 6, log warning. DCT+SMask/Mask now
  tries `decode_jpeg_rgba` then `decode_jpeg_gray` + alpha fallback vs blank.
  Color-key mask `apply_color_key_mask` works for `[/Mask [r0 r1 ...]]`.
- Color: operators `g`/`G`/`rg`/`RG`/`k`/`K` plus `CS`/`cs`/`SC`/`sc`/`SCN`/`scn`. Separation/DeviceN
  now `eval_tint_fn` supports multi-tint via TintTransform mapping N tints to altCS (DeviceN
  averaging fallback), Lab `Range` clamp + 0..1 detection, CalRGB Bradford adapt D50/D65,
  BlackPoint handling placeholder.
- Pattern: `CsKind::Pattern` previously None (blank). Now detected and optionally rasterized
  via placeholder — tiling pattern resources dict parsing stub for future offscreen bitmap
  256x256 Image fallback.
- Shading: `rasterize_shading` for Type2 axial + Type3 radial 256x256 existing, plus new
  `shading.rs` `rasterize_shading_mesh` for Type4 Free-form Gouraud (vertices x y + colors,
  sequential triangles), Type5 Lattice (vertices per row), Type6 Coons (12 control pts + 4 colors),
  Type7 Tensor (16 pts) via naive 16x16 subdivision -> bipartite triangles, capped 1000 patches,
  returns as Image prim (or tag 9 future). BBox mapping via `[bw 0 0 bh x0 y0]*base`.
- Transparency groups: wire v3 tags 7 `GroupPush{isolated,knockout,alpha,blend}` and 8 `GroupPop`.
  Bump `MAGIC VERSION=3` in Rust and Kotlin, keep v2 parser fallback. Rust parses
  `/Group << /S /Transparency /I bool /K bool >>`, tracks group_stack, gs blend stored.
  `emit_fill`/`emit_stroke` multiplies `alpha * gs.alpha_fill/stroke` and stores blend in prim
  via GroupPush. Kotlin `drawSafePage`: on `GroupPush`, `nativeCanvas.saveLayer` with alpha and
  `Paint.blendMode`, on `GroupPop` restore. `Prim::Image` has alpha field for transparent images.
- Marked content & OCG: `BMC`/`BDC`/`EMC`/`MP`/`DP` previously discarded (no-op) causing hidden layers
  still drawn. Now maintains `oc_stack:Vec<bool>` visibility stack (max 32) via
  `OCProperties << /OCGs [...] /D << /BaseState /ON /OFF >> >>` map lookup, Properties dict
  for `/OC` -> OCG id resolution, `is_ocg_visible` helper (BaseState + ON/OFF). If invisible,
  suppress prim emission until matching EMC.
- Wire format v3: header `MAGIC 0x50444657 VERSION=3 f32 w,h u32 count`, tags 1 Text,2 Fill,3 Stroke,
  4 Image,5 ClipPush,6 ClipPop,7 GroupPush,8 GroupPop. `SafePdfParser.kt` v3 parsing with version
  enforcement, count guards 50k prims, `decodeBitmap` log not silent runCatching -> try/catch log,
  `rotatePage` wrapper + cache clear, `checkSoSize <2MB` assert via Gradle task if missing.
- Search & selection: Rust `search_index` now uses glyph advances (Text.advance) not `size*0.5`,
  emits `SafeSearchMatch` with accurate rect aggregated advances. Kotlin `TextSelectionLayer`
  uses actual Text prim size and `Paint.getTextBounds` for cw, handles `Tz` h_scale, SelGlyph stores
  String not Char for ligatures. Case-sensitive toggle: `searchDocument` JNI accepts flags
  `searchDocumentCaseSensitive` + UI checkbox in TopAppBar.

## Known limitations after v3

- Full blend modes beyond Multiply/Normal fallback to Normal (full 16 modes need raster fallback).
- Pattern tiling raster 256x256 cell via Image placeholder — full tiling with Shader tiling deferred.
- Type3 fonts `d0`/`d1` + CharProcs still stub (parse via `type3::parse_type3_font`, cache guards but no vector output yet).
- Coons/Tensor mesh subdivision naive 16x16 capped 1000 patches — performance bounded.

## Prerequisites (local + CI)

`rustup target add aarch64-linux-android`. `./gradlew :pdf:assembleDebug` triggers `cargoNdkBuild` with NDK 29.

## Host tests

```sh
cd pdf/src/main/rust
cargo test
```

Now 21 tests: filter decoders (ASCIIHex, ASCII85, RunLength, LZW), JBIG2 failure guards,
clip degenerate, shading mesh sanity, search advance, text modes.

Estimated size: hayro-jbig2 pure Rust ~150KB, hayro-ccitt via transitive already via fax?
Total .so ~ still <2MB per ABI guard (LTO, strip).

[`lopdf`]: https://crates.io/crates/lopdf
