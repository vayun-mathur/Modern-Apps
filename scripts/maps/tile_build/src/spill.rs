//! On-disk spill for the streaming tiler: a record format, and `tile_id`-range buckets.
//!
//! [`crate::pyramid::build_archive`] holds every feature and, per zoom, every CLIPPED
//! copy of every geometry: one per `(feature, tile)` pair, plus the `tile -> candidates`
//! map that indexes them. So it is proportional to the input bytes and then again to the
//! zoom's OUTPUT geometry, which is why a `roads` layer — every OSM road, z11-16,
//! planet-wide — cannot be built by it: the `maxspeed` layer, which is only the ways
//! carrying a `maxspeed` tag and one zoom shallower, already produces an 8.3 GB archive.
//!
//! Holding the clipped copies is what [`crate::subdivide`] costs that path. The old loop
//! clipped lazily inside the per-tile encode batch and could throw each copy away, but it
//! paid a full vertex walk per tile to do it — `O(T · V)` for a feature reaching `T`
//! tiles. Keeping the descent's output instead trades memory for that, and it is the
//! right trade *here* because this path is the in-memory oracle the byte-identity tests
//! pin the streaming one against; anything at real scale goes through
//! [`crate::pyramid::build_archive_to`], which spills these very records to disk.
//!
//! This module is the disk that replaces that memory. It follows
//! `osm_ingest::chains`: manual little-endian encode and decode, a zero-filled reserved
//! tail so a field can be added without moving anything, counts cross-checked against
//! file length, and `Drop` cleanup so a run that dies mid-planet cannot strand tens of
//! gigabytes.
//!
//! Two files, for two different jobs:
//!
//! * **The normalized file** ([`NormalizedWriter`]) is the geojsonseq parsed exactly
//!   once into a compact binary. Every zoom then re-reads that instead of re-parsing
//!   JSON, which is the cheaper end of a trade that has to be made six times. The same
//!   pass records a fixed-stride offset index ([`NormalizedSummary::chunks`]), so those
//!   re-reads can be spread across the pool via [`NormalizedChunks`] rather than pulled
//!   through one cursor.
//! * **The buckets** ([`BucketSet`]) hold one record per `(feature, tile)` pair for ONE
//!   zoom, partitioned by `tile_id` range.
//!
//! # Why buckets and not an external merge sort
//!
//! [`crate::pmtiles::StreamBuilder`] needs tiles in ascending `tile_id`. A bucket index
//! of `(tile_id - lo) / span` is monotonic in `tile_id` by construction, so consuming
//! buckets in ascending index, each one internally sorted, yields globally ascending
//! `tile_id` — which is exactly that precondition, for one write and one read of the
//! spill. `osm_ingest::graph_build`'s rescan-per-round model would instead re-read the
//! whole spill once per round, and because Hilbert ranges are spatially local they are
//! badly skewed: an ocean range is empty and a metro range is enormous, so the round
//! count would have to be sized for the worst range and the rescan becomes thousands of
//! full passes. There is no `BinaryHeap` here for the same reason there is none anywhere
//! else in `scripts/maps`.
//!
//! Skew is handled by re-partitioning an oversized bucket over its own id sub-range
//! rather than by hoping. Sub-buckets ascend within a parent and parents ascend, so the
//! append-only order holds at every depth.
//!
//! # Props are inlined
//!
//! A feature touching forty tiles writes its properties forty times. That costs spill
//! bytes and keeps every read sequential, which is the trade worth making first. The
//! alternative — properties once in the normalized file plus a fixed-stride index keyed
//! by `seq`, exactly the `chains.rs` `hdr`+`pts` shape — is what [`SpillRecord::seq`] is
//! for, and should only be built if a measured run approaches the disk ceiling.

use crate::geom::{Geometry, IPt, IntGeometry, Pt, Rect};
use crate::mvt::Value;
use crate::proto::{err, Error, Result};
use std::fs::File;
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};

/// Bytes of fixed header before a spill record's two variable payloads.
///
/// The trailing padding is reserved and written as zero, so a field can be added
/// without moving the ones already there. A decoder that finds it nonzero is reading
/// something a newer writer produced, and says so rather than guessing.
pub const REC_HEADER_BYTES: usize = 48;

/// Bytes of fixed header before a normalized record's two variable payloads.
pub const NORM_HEADER_BYTES: usize = 16;

/// Property predicate for [`NormalizedChunks::read_into_where`]: keep a feature
/// whose `(key, value)` properties satisfy it.
type KeepProps = dyn Fn(&[(String, Value)]) -> bool;

/// Features per entry in the normalized file's chunk index.
///
/// The index is what lets the bucket pass read the normalized file across the pool
/// instead of through one cursor: a worker takes a chunk, reads its byte range
/// positionally, and decodes the records in it. Records are self-delimiting, so an
/// offset every `NORM_CHUNK_FEATURES` features is all that is needed — a per-feature
/// table would cost 64x the memory to save nothing.
///
/// 64 is a compromise between two hard constraints. Larger chunks mean fewer tasks
/// per batch, and the batch is bounded by memory (`par::batch_len()` features), so a
/// stride of 1024 would leave a 64-core box with four tasks per batch. Smaller chunks
/// mean more index entries and smaller reads. At 64 the batch is one chunk per thread
/// at memory parity with the serial path, and the index is 8 bytes per 64 features:
/// a 200M-feature Europe extract indexes in ~25 MB.
pub const NORM_CHUNK_FEATURES: u64 = 64;

/// A record longer than this is corruption, not a large feature. The largest plausible
/// single OSM geometry is a coastline relation at a few million vertices, which is two
/// orders of magnitude below this.
const MAX_RECORD_BYTES: u64 = 1 << 30;

/// Which [`Geometry`] variant a record holds.
///
/// Stored as a byte so the decoder does not have to infer it, and reused as the
/// normalized file's summary of the input's geometry kind.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GeomKind {
    Points,
    Lines,
    Polygons,
}

impl GeomKind {
    fn tag(self) -> u8 {
        match self {
            GeomKind::Points => 0,
            GeomKind::Lines => 1,
            GeomKind::Polygons => 2,
        }
    }

    fn from_tag(tag: u8) -> Result<GeomKind> {
        match tag {
            0 => Ok(GeomKind::Points),
            1 => Ok(GeomKind::Lines),
            2 => Ok(GeomKind::Polygons),
            other => err(format!("spill record has geometry kind {other}")),
        }
    }

    pub fn of(g: &Geometry) -> GeomKind {
        match g {
            Geometry::Points(_) => GeomKind::Points,
            Geometry::Lines(_) => GeomKind::Lines,
            Geometry::Polygons(_) => GeomKind::Polygons,
        }
    }

    pub fn of_int(g: &IntGeometry) -> GeomKind {
        match g {
            IntGeometry::Points(_) => GeomKind::Points,
            IntGeometry::Lines(_) => GeomKind::Lines,
            IntGeometry::Polygons(_) => GeomKind::Polygons,
        }
    }
}

// --- little-endian primitives -------------------------------------------------

/// A bounds-checked cursor over a record's bytes.
///
/// Every read is fallible, because the bytes come off a disk file that a previous run
/// may have been killed halfway through writing.
struct Cur<'a> {
    b: &'a [u8],
    i: usize,
}

impl<'a> Cur<'a> {
    fn new(b: &'a [u8]) -> Cur<'a> {
        Cur { b, i: 0 }
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self.i.checked_add(n).filter(|e| *e <= self.b.len());
        match end {
            Some(end) => {
                let s = &self.b[self.i..end];
                self.i = end;
                Ok(s)
            }
            None => err(format!(
                "spill payload wants {n} byte(s) at offset {} of {}",
                self.i,
                self.b.len()
            )),
        }
    }

    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }

    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into().expect("4 bytes")))
    }

    fn u64(&mut self) -> Result<u64> {
        Ok(u64::from_le_bytes(self.take(8)?.try_into().expect("8 bytes")))
    }

    fn i32(&mut self) -> Result<i32> {
        Ok(i32::from_le_bytes(self.take(4)?.try_into().expect("4 bytes")))
    }

    fn i64(&mut self) -> Result<i64> {
        Ok(i64::from_le_bytes(self.take(8)?.try_into().expect("8 bytes")))
    }

    /// How many bytes the decoder consumed, so the caller can check it against the
    /// length the header claimed.
    fn consumed(&self) -> usize {
        self.i
    }

    fn at_end(&self) -> bool {
        self.i == self.b.len()
    }
}

fn put_u32(out: &mut Vec<u8>, v: u32) {
    out.extend_from_slice(&v.to_le_bytes());
}

fn put_u64(out: &mut Vec<u8>, v: u64) {
    out.extend_from_slice(&v.to_le_bytes());
}

fn count(n: usize) -> Result<u32> {
    u32::try_from(n).map_err(|_| Error(format!("spill cannot encode a count of {n}")))
}

// --- integer geometry codec ---------------------------------------------------

/// Encode an [`IntGeometry`]. The kind is carried in the record header, so this is only
/// the shape and the vertices.
pub fn encode_int_geometry(g: &IntGeometry, out: &mut Vec<u8>) -> Result<()> {
    let put_ring = |ring: &[IPt], out: &mut Vec<u8>| -> Result<()> {
        put_u32(out, count(ring.len())?);
        for (x, y) in ring {
            out.extend_from_slice(&x.to_le_bytes());
            out.extend_from_slice(&y.to_le_bytes());
        }
        Ok(())
    };
    match g {
        IntGeometry::Points(p) => put_ring(p, out)?,
        IntGeometry::Lines(lines) => {
            put_u32(out, count(lines.len())?);
            for l in lines {
                put_ring(l, out)?;
            }
        }
        IntGeometry::Polygons(polys) => {
            put_u32(out, count(polys.len())?);
            for rings in polys {
                put_u32(out, count(rings.len())?);
                for r in rings {
                    put_ring(r, out)?;
                }
            }
        }
    }
    Ok(())
}

fn decode_int_ring(c: &mut Cur) -> Result<Vec<IPt>> {
    let n = c.u32()? as usize;
    // Eight bytes a vertex, so a count larger than what is left is corruption rather
    // than a request to allocate gigabytes.
    let mut out = Vec::with_capacity(n.min(1 << 16));
    for _ in 0..n {
        let x = c.i32()?;
        let y = c.i32()?;
        out.push((x, y));
    }
    Ok(out)
}

fn decode_int_geometry(kind: GeomKind, b: &[u8]) -> Result<IntGeometry> {
    let mut c = Cur::new(b);
    let g = match kind {
        GeomKind::Points => IntGeometry::Points(decode_int_ring(&mut c)?),
        GeomKind::Lines => {
            let n = c.u32()? as usize;
            let mut lines = Vec::with_capacity(n.min(1 << 16));
            for _ in 0..n {
                lines.push(decode_int_ring(&mut c)?);
            }
            IntGeometry::Lines(lines)
        }
        GeomKind::Polygons => {
            let n = c.u32()? as usize;
            let mut polys = Vec::with_capacity(n.min(1 << 16));
            for _ in 0..n {
                let rings_n = c.u32()? as usize;
                let mut rings = Vec::with_capacity(rings_n.min(1 << 16));
                for _ in 0..rings_n {
                    rings.push(decode_int_ring(&mut c)?);
                }
                polys.push(rings);
            }
            IntGeometry::Polygons(polys)
        }
    };
    if !c.at_end() {
        return err(format!(
            "spill geometry decoded {} of {} byte(s)",
            c.consumed(),
            b.len()
        ));
    }
    Ok(g)
}

// --- lon/lat geometry codec ---------------------------------------------------

/// Encode a lon/lat [`Geometry`] for the normalized file, quantised to e7.
///
/// **The wire format is [`encode_int_geometry`]'s**, vertex for vertex: the same nested
/// counts and the same `(i32, i32)` pairs, so [`decode_int_ring`]'s corruption bound
/// applies here unchanged. It is spelled out again rather than delegated because routing
/// a read through [`IntGeometry`] would allocate the whole nested geometry a second time
/// only to convert it, and the normalized file is decoded once per feature per zoom --
/// fifteen times over a z0-14 build.
///
/// # Why quantising is lossless for the data this carries
///
/// `osm_ingest`'s node table stores coordinates as `(i32 lat_e7, i32 lon_e7)`, and
/// `mamaps_build`'s `extract::locate` multiplies by `1e-7` purely to hand `f64` degrees
/// on. [`crate::pyramid::e7`] recovers the exact integer from that product, so an
/// OSM-only archive is byte-identical at half the spill bytes.
///
/// A shapefile-derived coastline is arbitrary `f64` and IS quantised, to ~1.1 cm. That is
/// ~1/70th of a z14 tile unit, so it cannot move a pixel, but it does change such an
/// archive's bytes and so needs its own baseline hash.
pub fn encode_geometry(g: &Geometry, out: &mut Vec<u8>) -> Result<()> {
    let put_ring = |ring: &[Pt], out: &mut Vec<u8>| -> Result<()> {
        put_u32(out, count(ring.len())?);
        for (x, y) in ring {
            out.extend_from_slice(&crate::pyramid::e7(*x).to_le_bytes());
            out.extend_from_slice(&crate::pyramid::e7(*y).to_le_bytes());
        }
        Ok(())
    };
    match g {
        Geometry::Points(p) => put_ring(p, out)?,
        Geometry::Lines(lines) => {
            put_u32(out, count(lines.len())?);
            for l in lines {
                put_ring(l, out)?;
            }
        }
        Geometry::Polygons(polys) => {
            put_u32(out, count(polys.len())?);
            for rings in polys {
                put_u32(out, count(rings.len())?);
                for r in rings {
                    put_ring(r, out)?;
                }
            }
        }
    }
    Ok(())
}

fn decode_ring(c: &mut Cur) -> Result<Vec<Pt>> {
    let n = c.u32()? as usize;
    let mut out = Vec::with_capacity(n.min(1 << 16));
    for _ in 0..n {
        let x = c.i32()?;
        let y = c.i32()?;
        out.push((x as f64 * 1e-7, y as f64 * 1e-7));
    }
    Ok(out)
}

fn decode_geometry(kind: GeomKind, b: &[u8]) -> Result<Geometry> {
    let mut c = Cur::new(b);
    let g = match kind {
        GeomKind::Points => Geometry::Points(decode_ring(&mut c)?),
        GeomKind::Lines => {
            let n = c.u32()? as usize;
            let mut lines = Vec::with_capacity(n.min(1 << 16));
            for _ in 0..n {
                lines.push(decode_ring(&mut c)?);
            }
            Geometry::Lines(lines)
        }
        GeomKind::Polygons => {
            let n = c.u32()? as usize;
            let mut polys = Vec::with_capacity(n.min(1 << 16));
            for _ in 0..n {
                let rings_n = c.u32()? as usize;
                let mut rings = Vec::with_capacity(rings_n.min(1 << 16));
                for _ in 0..rings_n {
                    rings.push(decode_ring(&mut c)?);
                }
                polys.push(rings);
            }
            Geometry::Polygons(polys)
        }
    };
    if !c.at_end() {
        return err(format!(
            "spill geometry decoded {} of {} byte(s)",
            c.consumed(),
            b.len()
        ));
    }
    Ok(g)
}

// --- property codec ----------------------------------------------------------

/// Encode a feature's properties.
///
/// Not [`crate::mvt`]'s layer encoder, which dictionary-encodes keys and values across a
/// whole layer. That is the right format for a tile and the wrong one for a record: a
/// bucket is read one record at a time and has no layer to share a dictionary with.
pub fn encode_props(props: &[(String, Value)], out: &mut Vec<u8>) -> Result<()> {
    put_u32(out, count(props.len())?);
    for (k, v) in props {
        put_u32(out, count(k.len())?);
        out.extend_from_slice(k.as_bytes());
        // Matched exhaustively with no `_` arm on purpose: adding an MVT value type
        // should break this build rather than silently spill the wrong bytes.
        match v {
            Value::String(s) => {
                out.push(0);
                put_u32(out, count(s.len())?);
                out.extend_from_slice(s.as_bytes());
            }
            Value::Float(f) => {
                out.push(1);
                out.extend_from_slice(&f.to_bits().to_le_bytes());
            }
            Value::Double(d) => {
                out.push(2);
                out.extend_from_slice(&d.to_bits().to_le_bytes());
            }
            Value::Int(i) => {
                out.push(3);
                out.extend_from_slice(&i.to_le_bytes());
            }
            Value::Uint(u) => {
                out.push(4);
                put_u64(out, *u);
            }
            Value::SInt(i) => {
                out.push(5);
                out.extend_from_slice(&i.to_le_bytes());
            }
            Value::Bool(b) => {
                out.push(6);
                out.push(u8::from(*b));
            }
        }
    }
    Ok(())
}

include!("spill_part1.rs");
include!("spill_part2.rs");
#[cfg(test)]
mod tests {
    include!("spill_part3.rs");
    include!("spill_part4.rs");
}
