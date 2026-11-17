//! A streaming reader for the one shapefile this project reads.
//!
//! # Why write this rather than shell out
//!
//! The OSMCoastline land polygons ship as an ESRI shapefile, and converting one normally means GDAL.
//! This repository has spent its whole life removing that class of dependency — osmium, tippecanoe
//! and Planetiler are all gone — and a polygon shapefile is a genuinely simple binary format: a
//! 100-byte header, then length-prefixed records of `f64` pairs. Reading it directly is about a
//! hundred lines, needs no toolchain, and streams, which matters when the file is 1.26 GB and the
//! whole point of the change it serves is to stop holding geometry in memory.
//!
//! Only what is needed is implemented: shape type 5, `Polygon`. A `PolygonZ` or a `PolyLine` in this
//! file would mean the wrong artefact was handed over, so those are refused rather than coerced.
//!
//! # Ring orientation is the opposite of GeoJSON's
//!
//! The ESRI specification orders an outer ring **clockwise** and a hole counter-clockwise — the
//! reverse of GeoJSON and of this pipeline. That does not matter for winding, because stage C
//! normalises it. It matters for **grouping**: a record holds a flat list of rings, and knowing which
//! are exteriors is the only way to tell one island with a lake from two separate islands.

use std::fs::File;
use std::io::{BufReader, Read};
use std::path::Path;

use osm_ingest::proto::{err, Error, Result};

/// One record's rings as `(ring, is_exterior)` pairs.
pub type PolygonRings = Vec<(Vec<(f64, f64)>, bool)>;

/// `.shp` files start with this, big-endian.
const FILE_CODE: u32 = 9994;
const HEADER_BYTES: usize = 100;
const SHAPE_NULL: u32 = 0;
const SHAPE_POLYGON: u32 = 5;

/// A ring count no real land polygon comes close to, as a guard against a corrupt length driving a
/// huge allocation.
const MAX_PARTS: u32 = 1 << 20;
/// Likewise for points. The largest single land polygon in the *unsplit* product is a few million.
const MAX_POINTS: u32 = 1 << 26;

/// Streams `Polygon` records out of a `.shp`.
pub struct ShapeReader {
    input: BufReader<File>,
    /// Bytes of records left to read, from the header's file length.
    remaining: u64,
    buf: Vec<u8>,
    /// Only records whose own bounding box meets this one are parsed.
    ///
    /// **Not an optimisation — a correctness requirement.** The land polygon product covers the whole
    /// planet: 825 k polygons, of which a California build wants a few hundred. Without this, a
    /// regional build tiles every coastline on Earth, which takes hours and produces an archive whose
    /// bounding box is the globe.
    filter: Option<(f64, f64, f64, f64)>,
    /// How many records were skipped by the filter, for the build report.
    pub skipped: u64,
}

impl ShapeReader {
    pub fn open(path: &Path) -> Result<ShapeReader> {
        let file = File::open(path)
            .map_err(|e| Error(format!("cannot open {}: {e}", path.display())))?;
        let mut input = BufReader::with_capacity(1 << 20, file);
        let mut header = [0u8; HEADER_BYTES];
        input
            .read_exact(&mut header)
            .map_err(|e| Error(format!("{} is too short to be a shapefile: {e}", path.display())))?;

        if be32(&header[0..4]) != FILE_CODE {
            return err(format!("{} is not a shapefile", path.display()));
        }
        // The header states the file length in 16-bit words, including the header itself.
        let words = be32(&header[24..28]) as u64;
        let total = words * 2;
        if total < HEADER_BYTES as u64 {
            return err(format!("{} declares an impossible length", path.display()));
        }
        let shape_type = le32(&header[32..36]);
        if shape_type != SHAPE_POLYGON {
            return err(format!(
                "{} holds shape type {shape_type}, and only 5 (Polygon) is read; this is not the \
                 land polygon product",
                path.display(),
            ));
        }
        Ok(ShapeReader {
            input,
            remaining: total - HEADER_BYTES as u64,
            buf: Vec::new(),
            filter: None,
            skipped: 0,
        })
    }

    /// Keep only records meeting `(min_lon, min_lat, max_lon, max_lat)`, in degrees.
    pub fn clip_to(&mut self, bbox: (f64, f64, f64, f64)) {
        self.filter = Some(bbox);
    }

    /// How many records the header declares, for progress sizing: the file
    /// length in words minus the header, over the 8-byte record header plus
    /// the smallest a `Polygon` body can be. An underestimate when records
    /// run large, which a progress bar tolerates (it clamps at 100%) — and
    /// exact for sizing, never for logic.
    pub fn records(&self) -> usize {
        const MIN_RECORD_BYTES: u64 = 8 + 44;
        (self.remaining / MIN_RECORD_BYTES) as usize
    }

    /// The next record's rings, or `None` at the end of the file.
    ///
    /// Rings come back as `(ring, is_exterior)` in file order, so the caller can group them without
    /// re-deriving orientation.
    pub fn next(&mut self) -> Result<Option<PolygonRings>> {
        // A record header is 8 bytes; anything less than that left is the end.
        if self.remaining < 8 {
            return Ok(None);
        }
        let mut record_header = [0u8; 8];
        self.input
            .read_exact(&mut record_header)
            .map_err(|e| Error(format!("reading a record header: {e}")))?;
        // Content length is in 16-bit words, as everywhere else in this format.
        let content_len = be32(&record_header[4..8]) as u64 * 2;
        self.remaining -= 8;
        if content_len > self.remaining {
            return err("a record claims more content than the file has left".to_string());
        }
        self.remaining -= content_len;

        self.buf.clear();
        self.buf.resize(content_len as usize, 0);
        self.input
            .read_exact(&mut self.buf)
            .map_err(|e| Error(format!("reading a record body: {e}")))?;

        // Every Polygon record carries its own bounding box at bytes 4..36, so a record outside the
        // build's area is rejected for the cost of four `f64` comparisons rather than by decoding
        // however many thousand points it holds.
        if let Some(want) = self.filter {
            if self.buf.len() >= 36 && le32(&self.buf[0..4]) == SHAPE_POLYGON {
                let (min_x, min_y) = (le64(&self.buf[4..12]), le64(&self.buf[12..20]));
                let (max_x, max_y) = (le64(&self.buf[20..28]), le64(&self.buf[28..36]));
                // Intersection, not containment: a polygon straddling the extract's edge is land
                // inside it and has to be kept.
                let misses = max_x < want.0 || min_x > want.2 || max_y < want.1 || min_y > want.3;
                if misses {
                    self.skipped += 1;
                    return Ok(Some(Vec::new()));
                }
            }
        }
        parse_polygon(&self.buf)
    }
}

/// One record's rings.
fn parse_polygon(body: &[u8]) -> Result<Option<PolygonRings>> {
    if body.len() < 4 {
        return err("a record is too short to name its shape type".to_string());
    }
    match le32(&body[0..4]) {
        // A null shape is legal and means "no geometry here". Skipped, not an error.
        SHAPE_NULL => return Ok(Some(Vec::new())),
        SHAPE_POLYGON => {}
        other => return err(format!("record shape type {other} is not a Polygon")),
    }
    // 4..36 is the record's own bounding box, which is redundant with the points.
    if body.len() < 44 {
        return err("a Polygon record is too short to hold its counts".to_string());
    }
    let part_count = le32(&body[36..40]);
    let point_count = le32(&body[40..44]);
    if part_count > MAX_PARTS || point_count > MAX_POINTS {
        return err(format!("a record claims {part_count} part(s) and {point_count} point(s)"));
    }
    let parts_at = 44usize;
    let points_at = parts_at + part_count as usize * 4;
    let needed = points_at + point_count as usize * 16;
    if body.len() < needed {
        return err(format!("a Polygon record is {} bytes short", needed - body.len()));
    }

    // Each part is the index of its first point; the last runs to the end.
    let mut starts: Vec<usize> = Vec::with_capacity(part_count as usize);
    for i in 0..part_count as usize {
        starts.push(le32(&body[parts_at + i * 4..parts_at + i * 4 + 4]) as usize);
    }
    let point = |i: usize| -> (f64, f64) {
        let at = points_at + i * 16;
        (le64(&body[at..at + 8]), le64(&body[at + 8..at + 16]))
    };

    let mut rings = Vec::with_capacity(starts.len());
    for (i, &start) in starts.iter().enumerate() {
        let end = starts.get(i + 1).copied().unwrap_or(point_count as usize);
        if start >= end || end > point_count as usize {
            return err("a part's range is not inside the record's points".to_string());
        }
        let ring: Vec<(f64, f64)> = (start..end).map(point).collect();
        // Clockwise is an exterior in this format, which is a negative shoelace area with y up.
        let exterior = signed_area(&ring) < 0.0;
        rings.push((ring, exterior));
    }
    Ok(Some(rings))
}

/// A ring's signed area in degrees squared. Only its sign is used.
fn signed_area(ring: &[(f64, f64)]) -> f64 {
    let mut twice = 0.0;
    for pair in ring.windows(2) {
        twice += pair[0].0 * pair[1].1 - pair[1].0 * pair[0].1;
    }
    if let (Some(first), Some(last)) = (ring.first(), ring.last()) {
        if first != last {
            twice += last.0 * first.1 - first.0 * last.1;
        }
    }
    twice / 2.0
}

/// Group a record's rings into polygons: each exterior starts one, and the holes that follow are
/// its.
///
/// A file whose first ring is a hole is malformed; those holes have nothing to belong to and are
/// dropped rather than promoted, because promoting one would paint a lake as land.
pub fn group(rings: Vec<(Vec<(f64, f64)>, bool)>) -> Vec<Vec<Vec<(f64, f64)>>> {
    let mut out: Vec<Vec<Vec<(f64, f64)>>> = Vec::new();
    for (ring, exterior) in rings {
        // Three distinct points plus a closing one; less bounds no area.
        if ring.len() < 4 {
            continue;
        }
        if exterior || out.is_empty() {
            out.push(vec![ring]);
        } else {
            out.last_mut().expect("non-empty").push(ring);
        }
    }
    out
}

fn be32(bytes: &[u8]) -> u32 {
    u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]])
}

fn le32(bytes: &[u8]) -> u32 {
    u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]])
}

fn le64(bytes: &[u8]) -> f64 {
    f64::from_le_bytes([
        bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
    ])
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Build a one-record shapefile in memory, so the reader is tested against bytes rather than
    /// against a 1.26 GB download.
    fn shapefile(rings: &[Vec<(f64, f64)>]) -> Vec<u8> {
        let point_count: usize = rings.iter().map(|r| r.len()).sum();
        let mut content = Vec::new();
        content.extend_from_slice(&SHAPE_POLYGON.to_le_bytes());
        for _ in 0..4 {
            content.extend_from_slice(&0f64.to_le_bytes());
        }
        content.extend_from_slice(&(rings.len() as u32).to_le_bytes());
        content.extend_from_slice(&(point_count as u32).to_le_bytes());
        let mut at = 0u32;
        for ring in rings {
            content.extend_from_slice(&at.to_le_bytes());
            at += ring.len() as u32;
        }
        for ring in rings {
            for &(x, y) in ring {
                content.extend_from_slice(&x.to_le_bytes());
                content.extend_from_slice(&y.to_le_bytes());
            }
        }

        let mut out = vec![0u8; HEADER_BYTES];
        out[0..4].copy_from_slice(&FILE_CODE.to_be_bytes());
        out[32..36].copy_from_slice(&SHAPE_POLYGON.to_le_bytes());
        out.extend_from_slice(&1u32.to_be_bytes());
        out.extend_from_slice(&((content.len() / 2) as u32).to_be_bytes());
        out.extend_from_slice(&content);
        let words = (out.len() / 2) as u32;
        out[24..28].copy_from_slice(&words.to_be_bytes());
        out
    }

    fn write_temp(name: &str, bytes: &[u8]) -> std::path::PathBuf {
        let path = std::env::temp_dir()
            .join(format!("mamaps_shp_{}_{name}.shp", std::process::id()));
        std::fs::write(&path, bytes).expect("write");
        path
    }

    /// Clockwise in a y-up system, which the ESRI spec calls an exterior.
    fn clockwise() -> Vec<(f64, f64)> {
        vec![(0.0, 0.0), (0.0, 1.0), (1.0, 1.0), (1.0, 0.0), (0.0, 0.0)]
    }

    fn counter_clockwise() -> Vec<(f64, f64)> {
        let mut ring = clockwise();
        ring.reverse();
        ring
    }

    #[test]
    fn a_single_polygon_reads_back_with_its_points() {
        let path = write_temp("single", &shapefile(&[clockwise()]));
        let mut reader = ShapeReader::open(&path).expect("open");
        let rings = reader.next().expect("read").expect("a record");
        assert_eq!(rings.len(), 1);
        assert!(rings[0].1, "a clockwise ring is an exterior in this format");
        assert_eq!(rings[0].0, clockwise());
        assert!(reader.next().expect("read").is_none(), "and then the end");
        let _ = std::fs::remove_file(&path);
    }

    /// **The reason orientation is read at all.** One island with a lake and two separate islands
    /// have the same ring count and the same points; only the winding tells them apart.
    #[test]
    fn orientation_groups_rings_into_the_right_polygons() {
        // Exterior, hole, exterior -> two polygons, the first with a hole.
        let rings = vec![clockwise(), counter_clockwise(), clockwise()];
        let path = write_temp("grouped", &shapefile(&rings));
        let mut reader = ShapeReader::open(&path).expect("open");
        let polygons = group(reader.next().expect("read").expect("a record"));
        assert_eq!(polygons.len(), 2, "two islands");
        assert_eq!(polygons[0].len(), 2, "the first has a lake");
        assert_eq!(polygons[1].len(), 1, "the second does not");
        let _ = std::fs::remove_file(&path);

        // Two exteriors and no holes -> two separate polygons, not one with a hole.
        let path = write_temp("two", &shapefile(&[clockwise(), clockwise()]));
        let mut reader = ShapeReader::open(&path).expect("open");
        let polygons = group(reader.next().expect("read").expect("a record"));
        assert_eq!(polygons.len(), 2);
        assert!(polygons.iter().all(|p| p.len() == 1));
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn a_degenerate_ring_is_dropped_and_a_leading_hole_does_not_become_land() {
        // A two-point ring bounds nothing.
        let rings = vec![(vec![(0.0, 0.0), (1.0, 1.0)], true)];
        assert!(group(rings).is_empty(), "a degenerate ring");

        // A record whose first ring is a hole is malformed. It must not be promoted to land, which
        // would paint a lake solid.
        let rings = vec![(counter_clockwise(), false), (clockwise(), true)];
        let polygons = group(rings);
        assert_eq!(polygons.len(), 2, "the stray hole stands alone rather than swallowing the land");
    }

    #[test]
    fn the_wrong_file_is_refused_rather_than_misread() {
        // Not a shapefile at all.
        let path = write_temp("garbage", b"this is not a shapefile, it is a sentence");
        assert!(ShapeReader::open(&path).is_err());
        let _ = std::fs::remove_file(&path);

        // A shapefile of the wrong shape type, which means the wrong artefact was downloaded.
        let mut bytes = shapefile(&[clockwise()]);
        bytes[32..36].copy_from_slice(&3u32.to_le_bytes()); // PolyLine
        let path = write_temp("polyline", &bytes);
        let failure = match ShapeReader::open(&path) {
            Ok(_) => panic!("a PolyLine shapefile should be refused"),
            Err(e) => e,
        };
        assert!(failure.0.contains("land polygon product"), "{}", failure.0);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn a_truncated_record_is_an_error_rather_than_a_silent_stop() {
        let mut bytes = shapefile(&[clockwise()]);
        bytes.truncate(bytes.len() - 40);
        let path = write_temp("truncated", &bytes);
        let mut reader = ShapeReader::open(&path).expect("open");
        assert!(reader.next().is_err(), "a half-read polygon must not look like the end of the file");
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn a_null_shape_is_skipped_rather_than_failing_the_build() {
        let mut content = Vec::new();
        content.extend_from_slice(&SHAPE_NULL.to_le_bytes());
        let mut bytes = vec![0u8; HEADER_BYTES];
        bytes[0..4].copy_from_slice(&FILE_CODE.to_be_bytes());
        bytes[32..36].copy_from_slice(&SHAPE_POLYGON.to_le_bytes());
        bytes.extend_from_slice(&1u32.to_be_bytes());
        bytes.extend_from_slice(&((content.len() / 2) as u32).to_be_bytes());
        bytes.extend_from_slice(&content);
        let words = (bytes.len() / 2) as u32;
        bytes[24..28].copy_from_slice(&words.to_be_bytes());

        let path = write_temp("null", &bytes);
        let mut reader = ShapeReader::open(&path).expect("open");
        assert_eq!(reader.next().expect("read").expect("a record").len(), 0);
        let _ = std::fs::remove_file(&path);
    }
}
