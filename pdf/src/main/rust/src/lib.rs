//! Memory-safe PDF renderer for the "Open PDF (safe)" viewer.
//!
//! Parses a PDF entirely in Rust with `lopdf` (no pdfium / no native system
//! PDF stack) and reduces each page to plain drawing primitives — text runs,
//! filled polygons and stroked polylines — positioned in PDF page space. The
//! Kotlin side (`com.vayunmathur.pdf.util.SafePdfParser`) decodes the compact
//! little-endian buffer produced here and draws it on a Compose `Canvas`.
//!
//! Design goals: all untrusted binary parsing happens in memory-safe Rust; the
//! JNI boundary only ever passes a flat byte buffer of geometry + UTF-8 text,
//! so Kotlin never touches the raw PDF bytes.
//!
//! Scope: text (with `/ToUnicode`, embedded-font encoding and CFF/Type1 charset
//! recovery), vector paths (lines, rectangles, filled/stroked paths, beziers),
//! raster image XObjects (with full colorspace conversion incl.
//! Separation/DeviceN/Lab/ICC/Indexed), all four PDF function types
//! ([`crate::functions`]), axial/radial and Type 1/4-7 mesh shadings, tiling and
//! shading patterns, Type 3 fonts, filters (Flate/LZW with predictors, ASCII85,
//! ASCIIHex, RunLength, CCITT, JBIG2, DCT/JPX passthrough), standard-security
//! decryption/encryption (RC4 + AES-128/256), annotations, AcroForm fields, and
//! text search. Genuinely unsupported items (public-key encryption; the compiled
//! code->CID tables of the predefined CJK CMaps) are documented in `README.md`.
//! Note that TrueType/CFF/Type1 outlines ARE rendered as real vector contours
//! (see [`crate::outlines`]); substitute-typeface text runs are the fallback for
//! fonts with no usable embedded program, not the primary path.

pub(crate) use std::collections::HashMap;
pub(crate) use std::io::Cursor;
pub(crate) use std::sync::{Mutex, OnceLock};

pub(crate) use lopdf::content::Content;
pub(crate) use lopdf::{dictionary, Dictionary, Document, Object, ObjectId, Stream};

mod pdf_cbc;
mod crypto;
mod filters;
mod functions;
pub(crate) use functions::*;
mod cff;
mod afm;
mod type1;
mod outlines;
mod glyphlist;
mod jbig2;
mod shading;
mod type3;

mod registry;
pub(crate) use registry::*;
mod content;
mod decrypt;
pub(crate) use decrypt::*;
mod docedit;
pub(crate) use docedit::*;
mod geometry;
pub(crate) use geometry::*;
mod model;
pub(crate) use model::*;
mod objects;
pub(crate) use objects::*;
mod fonts;
pub(crate) use fonts::*;
mod color;
pub(crate) use color::*;
mod graphics_state;
pub(crate) use graphics_state::*;
mod interpret;
pub(crate) use interpret::*;
mod annotations;
pub(crate) use annotations::*;
mod forms;
pub(crate) use forms::*;
mod search;
pub(crate) use search::*;
mod draw;
pub(crate) use draw::*;
mod images;
pub(crate) use images::*;

mod wire;
mod jni_bindings;
#[cfg(test)]
mod tests;
#[cfg(test)]
mod golden_tests;
#[cfg(test)]
mod debug_ishi_test;
#[cfg(test)]
mod issue321_tests;
#[cfg(test)]
mod differential_tests;
#[cfg(test)]
mod reference_diff_tests;
#[cfg(test)]
mod perf_tests;
#[cfg(test)]
mod robustness_tests;
#[cfg(test)] mod issue942_tests;
#[cfg(test)] mod cov_geometry_tests;
#[cfg(test)] mod cov_outlines_tests;
#[cfg(test)] mod cov_interpret_tests;
#[cfg(test)] mod cov_cff_tests;
#[cfg(test)] mod cov_docedit_tests;
#[cfg(test)] mod cov_decrypt_tests;
#[cfg(test)] mod cov_annotations_tests;
#[cfg(test)] mod cov_forms_tests;
#[cfg(test)] mod cov_more_tests;
#[cfg(test)] mod cov_fonts_tests;
