//! Load the `.mdem` heightmap dataset `dem_ingest` produced and hand the tiler one grid per output
//! tile — the second half of WS-G's build side, closing the gap the wire format left open.
//!
//! `dem_ingest` fetches AWS terrarium PNG tiles and downsamples them into one small square `u16`
//! grid per map tile at a single zoom (`--out-zoom`, default 14; `--dim`, default 9), each sample
//! `metres + 32768` row-major from the tile's top-left. That is exactly the layout
//! [`tilecodec::mamaps::body::Heightmap`] carries on the wire, so a tile at the dataset's own zoom
//! is a **verbatim copy** of its grid — no resample, byte for byte what the ingest computed,
//! unless the tile is flat enough to coarsen (see `FLAT_RELIEF_M`).
//!
//! A tile at any other zoom is **resampled**: `dim * dim` points are sampled across the tile's own
//! extent (the same slippy walk `dem_ingest` does over the terrarium grid), each bilinearly
//! interpolated from the covering dataset grid. A tile with no data under it at all — off the fetch
//! coverage, or open ocean the ingest dropped with `--skip-flat` — yields `None`, so that tile omits
//! the section and stays 16-byte, matching the format's rule. A tile whose grid is entirely sea
//! level yields `None` the same way, whatever the zoom: it carries no relief, and a flat sea-level
//! grid would make the renderer draw earth-coloured terrain over open water (see [`Dem::heightmap_for`]).

use std::f64::consts::PI;
use std::path::Path;

use rayon::prelude::*;
use tile_build::par;
use tilecodec::mamaps::body::Heightmap;
use tilecodec::pmtiles::tile_id;
use tilecodec::proto::{err, Error, Result};

/// The `.mdem` dataset's magic and version, as `dem_ingest` writes them.
const MAGIC: &[u8; 4] = b"MDEM";
const VERSION: u8 = 1;

/// The biased `u16` for zero metres: sea level, and what a sample with no DEM under it reads as.
/// The same bias [`Heightmap`] and the terrarium source use.
const SEA_LEVEL: u16 = 32768;

/// Whether every sample in a grid is sea level, i.e. the tile is all ocean / flat coast at 0 m and
/// carries no relief worth a terrain grid. See [`Dem::heightmap_for`] for why such tiles drop it.
fn all_sea_level(samples: &[u16]) -> bool {
    samples.iter().all(|&s| s == SEA_LEVEL)
}

/// A tile flatter than this (max sample minus min sample, in metres) carries
/// a coarse grid: at 5x5 the elevation error is bounded by the relief the
/// grid cannot see, and below a 10-storey building over a tile (~1 km at
/// z14) the mesh is indistinguishable from full resolution.
pub const FLAT_RELIEF_M: u16 = 32;

/// The coarse grid side length for flat tiles. 5x5x2+2 = 52 bytes against
/// 9x9x2+2 = 164 — a 68% saving where it applies, with zero reader changes
/// (the wire carries a per-tile `dim`, and sampling/tessellation derive
/// from it).
pub const FLAT_DIM: u16 = 5;

/// The relief (max minus min sample) of a grid, in sample units (metres).
fn relief(samples: &[u16]) -> u16 {
    let (mut lo, mut hi) = (u16::MAX, u16::MIN);
    for &s in samples {
        lo = lo.min(s);
        hi = hi.max(s);
    }
    hi - lo
}

/// Downsample a `dim x dim` grid to `FLAT_DIM x FLAT_DIM` by picking the
/// evenly-spaced rows/columns (corners included, so the tile edges agree
/// with a full-resolution neighbour's skirt). Only called when
/// `dim >= FLAT_DIM` and the grid is flat — the values barely vary, so
/// nearest-pick and averaging differ by less than the relief itself.
fn coarsen(samples: &[u16], dim: usize) -> Vec<u16> {
    let flat = FLAT_DIM as usize;
    let mut out = Vec::with_capacity(flat * flat);
    for row in 0..flat {
        let src_row = row * (dim - 1) / (flat - 1);
        for col in 0..flat {
            let src_col = col * (dim - 1) / (flat - 1);
            out.push(samples[src_row * dim + src_col]);
        }
    }
    out
}

/// A loaded `.mdem` dataset: one `u16` grid per output tile, keyed by pmtiles tile id at the
/// dataset's own zoom.
pub struct Dem {
    /// The zoom the grids were sampled at — the one a tile can copy verbatim rather than resample.
    out_zoom: u8,
    /// The grid side length, so every grid is `dim * dim` samples. Shared by every tile.
    dim: u16,
    /// `tile_id(out_zoom, x, y)` -> the tile's `dim * dim` samples, row-major from its top-left.
    grids: Grids,
}

/// The dataset's grids as one sorted id column and one contiguous sample column.
///
/// Was a `HashMap<u64, Vec<u16>>`: on the 13 GB world dataset that is ~76 M allocations and a
/// single-threaded fold of ~76 M inserts on every build regardless of region (the bulk of a
/// 12-24 s `dem-load` at ~5 cores), plus the map's own slack. Here both columns are built on the
/// pool and a lookup is a binary search. Same answers as the map for every id, including a file
/// that repeats one: the map kept the **last** insert, and so does [`Grids::build`].
struct Grids {
    /// Tile ids, strictly ascending.
    ids: Vec<u64>,
    /// `ids.len() * cells` samples; tile `i`'s grid is `samples[i * cells..][..cells]`.
    samples: Vec<u16>,
    cells: usize,
}

impl Grids {
    /// From `(id, file index)` for every record and a way to fill a grid from a record index.
    /// Ids are sorted (already ascending in a `dem_ingest` file, which is checked rather than
    /// assumed) and a repeated id keeps its last record, as `HashMap::insert` in file order did.
    fn build<F>(mut keys: Vec<(u64, usize)>, cells: usize, fill: F) -> Grids
    where
        F: Fn(usize, &mut [u16]) + Sync,
    {
        let ascending = keys.par_windows(2).all(|w| w[0].0 < w[1].0);
        if !ascending {
            // (id, index) is a total order with unique keys, so the sort is deterministic; the
            // last of each id's run is its last record in the file.
            keys.par_sort_unstable();
            let mut kept: Vec<(u64, usize)> = Vec::with_capacity(keys.len());
            for k in keys {
                match kept.last_mut() {
                    Some(last) if last.0 == k.0 => *last = k,
                    _ => kept.push(k),
                }
            }
            keys = kept;
        }
        let mut samples = vec![0u16; keys.len() * cells];
        if cells > 0 {
            samples
                .par_chunks_mut(cells)
                .zip(keys.par_iter())
                .for_each(|(grid, &(_, index))| fill(index, grid));
        }
        Grids { ids: keys.into_iter().map(|(id, _)| id).collect(), samples, cells }
    }

    fn get(&self, id: &u64) -> Option<&[u16]> {
        let i = self.ids.binary_search(id).ok()?;
        Some(&self.samples[i * self.cells..(i + 1) * self.cells])
    }
}

impl Dem {
    /// Read and parse a `.mdem` dataset from disk.
    ///
    /// Mapped, not read: the 13 GB world file would otherwise cost a
    /// single-threaded `fs::read` plus a full copy before parsing starts.
    /// The mapping faults pages in on the pool's 64 threads during the
    /// parallel parse below, and the bytes are never copied — each tile's
    /// samples land directly in its grid `Vec`. The mapping drops on return;
    /// the `Dem` owns its grids.
    pub fn load(path: &Path) -> Result<Dem> {
        let mapped = osm_ingest::mem::Mapped::open(path).map_err(Error)?;
        Dem::parse(&mapped)
    }

    /// Parse a `.mdem` dataset: a 12-byte header (`MDEM`, version, out_zoom, `dim` u16, tile count
    /// u32), then per tile a `u64` tile id and its `dim * dim` `u16` samples, little-endian.
    ///
    /// Tiles parse on the pool: records are fixed-stride (`8 + cells * 2` bytes), so tile `i`
    /// starts at `12 + i * stride` with no dependency on any other tile. Each worker decodes a
    /// contiguous run into owned `(id, samples)` pairs; the single-threaded fold inserts them in
    /// file order. Same pairs as the serial loop — the map holds the same contents, and nothing
    /// ever iterates it (only `heightmap_for`'s `.get`), so no byte can move. The 13 GB world
    /// dataset's per-sample `u16::from_le_bytes` + per-tile alloc + hash insert runs ~50 s
    /// serially on every build regardless of region; fanning out the decode leaves only the
    /// `fs::read` and the inserts.
    fn parse(bytes: &[u8]) -> Result<Dem> {
        if bytes.len() < 12 {
            return err("a .mdem dataset is shorter than its 12-byte header");
        }
        if &bytes[0..4] != MAGIC {
            return err("not a .mdem dataset (bad magic)");
        }
        if bytes[4] != VERSION {
            return err(format!("unsupported .mdem version {} (this build reads {VERSION})", bytes[4]));
        }
        let out_zoom = bytes[5];
        let dim = u16::from_le_bytes([bytes[6], bytes[7]]);
        if dim < 2 {
            return err("a .mdem grid dimension is less than 2");
        }
        let count = u32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]) as usize;
        let cells = dim as usize * dim as usize;
        // One tile: an 8-byte id then `cells` little-endian u16 samples.
        let stride = 8 + cells * 2;
        if bytes.len() < 12 + count.saturating_mul(stride) {
            return err("a .mdem dataset ends before its tile count");
        }
        // Both columns on the pool (see [`Grids`]): the ids straight off their fixed-stride
        // offsets, then each grid decoded into its slot of one contiguous sample array.
        let keys: Vec<(u64, usize)> = par::install(|| {
            (0..count)
                .into_par_iter()
                .map(|i| {
                    let at = 12 + i * stride;
                    (u64::from_le_bytes(bytes[at..at + 8].try_into().expect("8 bytes")), i)
                })
                .collect()
        });
        let grids = par::install(|| {
            Grids::build(keys, cells, |i, grid| {
                let at = 12 + i * stride + 8;
                for (c, out) in grid.iter_mut().enumerate() {
                    let o = at + c * 2;
                    *out = u16::from_le_bytes([bytes[o], bytes[o + 1]]);
                }
            })
        });
        Ok(Dem { out_zoom, dim, grids })
    }

    /// The heightmap for one output tile, or `None` when no DEM covers it (so the tile omits the
    /// section and stays 16-byte).
    ///
    /// A tile at the dataset's own zoom is a verbatim copy of its grid — byte for byte what the
    /// ingest sampled, independent of neighbour coverage. Any other zoom is resampled onto this
    /// tile's grid.
    ///
    /// A grid that is **entirely sea level** yields `None` whatever the zoom: an all-ocean tile
    /// carries no relief, and emitting a flat sea-level heightmap makes the renderer draw its
    /// earth-coloured terrain grid over what should be open water (the renderer's contract is
    /// "no heightmap over ocean" — see `library/map`'s `terrain.rs`). Dropping it here is the
    /// backstop for a `.mdem` built without `dem_ingest --skip-flat`. A flat *land* tile at exactly
    /// sea level loses nothing visible: its flat `earth` fill draws the same ground the flat grid
    /// would have, and its features sit at 0 m either way.
    pub fn heightmap_for(&self, z: u8, x: u64, y: u64) -> Option<Heightmap> {
        if z == self.out_zoom {
            let samples = self.grids.get(&tile_id(z, x, y))?;
            if all_sea_level(samples) {
                return None;
            }
            // A flat tile's grid is mostly repetition: coarsen it. Steep
            // tiles keep the full grid.
            if relief(samples) <= FLAT_RELIEF_M && self.dim >= FLAT_DIM {
                return Some(Heightmap { dim: FLAT_DIM, samples: coarsen(samples, self.dim as usize) });
            }
            return Some(Heightmap { dim: self.dim, samples: samples.to_vec() });
        }
        self.resample(z, x, y)
    }

    /// Resample the dataset's grids onto one tile's `dim * dim` grid, sampling `dim * dim` points
    /// across the tile's own extent from its top-left — the same slippy walk `dem_ingest` runs over
    /// the terrarium grid, sourced from the loaded grids instead of PNGs.
    fn resample(&self, z: u8, x: u64, y: u64) -> Option<Heightmap> {
        let dim = self.dim as usize;
        let mut samples = Vec::with_capacity(dim * dim);
        let mut any = false;
        for row in 0..dim {
            let fy = y as f64 + row as f64 / (self.dim as f64 - 1.0);
            let lat = tile_y_to_lat(fy, z);
            for col in 0..dim {
                let fx = x as f64 + col as f64 / (self.dim as f64 - 1.0);
                let lon = tile_x_to_lon(fx, z);
                match self.sample_at(lon, lat) {
                    Some(v) => {
                        any = true;
                        samples.push(v);
                    }
                    // No DEM here: sea level, as `dem_ingest` fills a missing terrarium pixel. A
                    // tile entirely over missing DEM is dropped below.
                    None => samples.push(SEA_LEVEL),
                }
            }
        }
        // A tile whose every sample is sea level carries no relief: drop it so the renderer paints
        // open water rather than a flat earth-coloured terrain grid (see `heightmap_for`).
        if !any || all_sea_level(&samples) {
            return None;
        }
        // Same flat-tile coarsening as the verbatim path: the resampled grid
        // is full resolution, so relief is measured on it directly.
        if relief(&samples) <= FLAT_RELIEF_M && self.dim >= FLAT_DIM {
            return Some(Heightmap { dim: FLAT_DIM, samples: coarsen(&samples, dim) });
        }
        Some(Heightmap { dim: self.dim, samples })
    }

    /// Build a dataset directly from grids, for tests that inject a synthetic DEM into the tiler
    /// rather than round-tripping bytes through disk.
    #[cfg(test)]
    pub(crate) fn from_grids(out_zoom: u8, dim: u16, grids: Vec<(u64, Vec<u16>)>) -> Dem {
        let cells = dim as usize * dim as usize;
        let keys: Vec<(u64, usize)> = grids.iter().enumerate().map(|(i, (id, _))| (*id, i)).collect();
        let grids = Grids::build(keys, cells, |i, grid| grid.copy_from_slice(&grids[i].1));
        Dem { out_zoom, dim, grids }
    }

    /// The elevation at one lon/lat, bilinearly interpolated within the dataset grid that covers it,
    /// or `None` when that grid was not fetched.
    fn sample_at(&self, lon: f64, lat: f64) -> Option<u16> {
        let n = (1u64 << self.out_zoom) as f64;
        // The fractional tile position at the dataset zoom. Clamped so a point exactly on the
        // world's right/bottom edge lands in the last tile rather than one past it.
        let tx = lon_to_tile_x(lon, self.out_zoom).clamp(0.0, n - f64::EPSILON);
        let ty = lat_to_tile_y(lat, self.out_zoom).clamp(0.0, n - f64::EPSILON);
        let ix = tx.floor() as u64;
        let iy = ty.floor() as u64;
        let grid = self.grids.get(&tile_id(self.out_zoom, ix, iy))?;
        let last = self.dim as usize - 1;
        // The point's position within the tile's grid: the fraction across the tile scaled onto the
        // `dim - 1` cells between the grid's edge samples.
        let gx = (tx - ix as f64) * last as f64;
        let gy = (ty - iy as f64) * last as f64;
        let x0 = gx.floor() as usize;
        let y0 = gy.floor() as usize;
        let x1 = (x0 + 1).min(last);
        let y1 = (y0 + 1).min(last);
        let fx = gx - x0 as f64;
        let fy = gy - y0 as f64;
        let d = self.dim as usize;
        let at = |cx: usize, cy: usize| grid[cy * d + cx] as f64;
        let top = at(x0, y0) * (1.0 - fx) + at(x1, y0) * fx;
        let bot = at(x0, y1) * (1.0 - fx) + at(x1, y1) * fx;
        let v = top * (1.0 - fy) + bot * fy;
        Some(v.round().clamp(0.0, u16::MAX as f64) as u16)
    }
}

// --- slippy / web-mercator tile math, matching `dem_ingest` so a resample lands where the ingest
// would have sampled ---

fn lon_to_tile_x(lon: f64, z: u8) -> f64 {
    (lon + 180.0) / 360.0 * (1u64 << z) as f64
}

fn lat_to_tile_y(lat: f64, z: u8) -> f64 {
    let r = lat.clamp(-85.05112878, 85.05112878).to_radians();
    (1.0 - (r.tan() + 1.0 / r.cos()).ln() / PI) / 2.0 * (1u64 << z) as f64
}

fn tile_x_to_lon(x: f64, z: u8) -> f64 {
    x / (1u64 << z) as f64 * 360.0 - 180.0
}

fn tile_y_to_lat(y: f64, z: u8) -> f64 {
    let n = PI * (1.0 - 2.0 * y / (1u64 << z) as f64);
    n.sinh().atan().to_degrees()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The sorted-column grids answer exactly what the old `HashMap` built by inserting in file
    /// order answered -- for ascending files, unsorted ones, and ones repeating an id (last wins).
    #[test]
    fn grids_answer_what_a_file_order_hash_map_did() {
        let dim = 3u16;
        let cells = 9usize;
        let grid = |v: u16| vec![v; cells];
        let cases: Vec<Vec<(u64, Vec<u16>)>> = vec![
            vec![(1, grid(1)), (5, grid(5)), (9, grid(9))],
            vec![(9, grid(1)), (1, grid(2)), (5, grid(3)), (1, grid(4)), (7, grid(5)), (9, grid(6))],
            vec![],
        ];
        for tiles in cases {
            let dem = Dem::parse(&dataset(10, dim, &tiles)).expect("parse");
            let mut map = std::collections::HashMap::new();
            for (id, g) in &tiles {
                map.insert(*id, g.clone());
            }
            for id in 0..12u64 {
                assert_eq!(dem.grids.get(&id).map(<[u16]>::to_vec), map.get(&id).cloned(), "id {id}");
            }
        }
    }

    /// Serialise a synthetic `.mdem` dataset the way `dem_ingest` does, for the parser to read back.
    fn dataset(out_zoom: u8, dim: u16, tiles: &[(u64, Vec<u16>)]) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(MAGIC);
        out.push(VERSION);
        out.push(out_zoom);
        out.extend_from_slice(&dim.to_le_bytes());
        out.extend_from_slice(&(tiles.len() as u32).to_le_bytes());
        for (id, grid) in tiles {
            out.extend_from_slice(&id.to_le_bytes());
            for &s in grid {
                out.extend_from_slice(&s.to_le_bytes());
            }
        }
        out
    }

    /// A ramp grid whose sample at `(col, row)` is `32768 + row * 10 + col`, so a read-back can name
    /// which cell it got.
    fn ramp(dim: u16) -> Vec<u16> {
        let d = dim as usize;
        let mut g = Vec::with_capacity(d * d);
        for row in 0..d {
            for col in 0..d {
                g.push(SEA_LEVEL + (row * 10 + col) as u16);
            }
        }
        g
    }

    #[test]
    fn a_tile_at_the_datasets_zoom_is_a_verbatim_copy() {
        let (z, dim) = (14u8, 17u16);
        let (x, y) = (2730u64, 6335u64);
        let grid = ramp(dim);
        let dem = Dem::parse(&dataset(z, dim, &[(tile_id(z, x, y), grid.clone())])).expect("parse");
        let hm = dem.heightmap_for(z, x, y).expect("a heightmap for the covered tile");
        assert_eq!(hm.dim, dim);
        assert_eq!(hm.samples, grid, "the grid is copied byte for byte at the dataset's own zoom");
        // And it reads back through the wire type's own accessor.
        assert_eq!(hm.sample(0, 0), Some(SEA_LEVEL));
        assert_eq!(hm.sample(3, 2), Some(SEA_LEVEL + 23));
        assert_eq!(Heightmap::metres(hm.sample(3, 2).unwrap()), 23);
        assert_eq!(hm.sample(dim, 0), None, "out of range");
    }

    #[test]
    fn a_tile_off_coverage_has_no_heightmap() {
        let (z, dim) = (14u8, 17u16);
        let dem = Dem::parse(&dataset(z, dim, &[(tile_id(z, 10, 10), ramp(dim))])).expect("parse");
        assert!(dem.heightmap_for(z, 999, 999).is_none(), "an uncovered tile omits the section");
    }

    #[test]
    fn an_all_sea_level_tile_has_no_heightmap() {
        let (z, dim) = (14u8, 17u16);
        let (x, y) = (2730u64, 6335u64);
        let sea = vec![SEA_LEVEL; dim as usize * dim as usize];
        let dem = Dem::parse(&dataset(z, dim, &[(tile_id(z, x, y), sea)])).expect("parse");
        assert!(
            dem.heightmap_for(z, x, y).is_none(),
            "an all-ocean tile drops its heightmap so the renderer paints open water",
        );
    }

    #[test]
    fn a_resampled_all_sea_level_tile_has_no_heightmap() {
        let (oz, dim) = (14u8, 17u16);
        // Cover the whole z13 block so every resample point hits coverage, yet all at sea level.
        let sea: Vec<u16> = vec![SEA_LEVEL; dim as usize * dim as usize];
        let mut tiles = Vec::new();
        for cx in 2730..=2732u64 {
            for cy in 6334..=6336u64 {
                tiles.push((tile_id(oz, cx, cy), sea.clone()));
            }
        }
        let dem = Dem::parse(&dataset(oz, dim, &tiles)).expect("parse");
        assert!(
            dem.heightmap_for(13, 1365, 3167).is_none(),
            "a resampled all-sea-level tile drops its heightmap too",
        );
    }

    /// A coarser tile resamples from the covering grids. Sampled over a tile whose whole extent is
    /// covered, a flat field must come back flat and non-empty.
    #[test]
    fn a_coarser_tile_resamples_within_range() {
        let (oz, dim) = (14u8, 17u16);
        // A z13 tile's dim*dim points span its three edge columns/rows of z14 tiles: x in
        // 2730..=2732 and y in 6334..=6336 for z13 (1365, 3167). Cover that whole 3x3 block so no
        // sample point falls off coverage (which would read as sea level).
        let flat: Vec<u16> = vec![SEA_LEVEL + 100; dim as usize * dim as usize];
        let mut tiles = Vec::new();
        for cx in 2730..=2732u64 {
            for cy in 6334..=6336u64 {
                tiles.push((tile_id(oz, cx, cy), flat.clone()));
            }
        }
        let dem = Dem::parse(&dataset(oz, dim, &tiles)).expect("parse");
        let hm = dem.heightmap_for(13, 1365, 3167).expect("a resampled heightmap");
        // A perfectly flat field coarsens: 5x5 of 100 m everywhere.
        assert_eq!(hm.dim, FLAT_DIM, "a flat resampled tile coarsens");
        assert_eq!(hm.samples.len(), FLAT_DIM as usize * FLAT_DIM as usize);
        assert!(
            hm.samples.iter().all(|&s| s == SEA_LEVEL + 100),
            "resampling a flat 100 m field is still 100 m everywhere",
        );
    }

    #[test]
    fn a_short_dataset_is_refused() {
        assert!(Dem::parse(b"MDE").is_err(), "shorter than the header");
        assert!(Dem::parse(b"XXXX\x01\x0e\x11\x00\x00\x00\x00\x00").is_err(), "bad magic");
        // A header claiming one tile with no tile bytes behind it.
        let mut short = dataset(14, 17, &[]);
        short[8] = 1; // claim one tile
        assert!(Dem::parse(&short).is_err(), "a claimed tile past the end is refused");
    }

    /// A flat tile (relief under `FLAT_RELIEF_M`) coarsens to `FLAT_DIM`:
    /// corners survive so tile edges agree with full-resolution neighbours.
    #[test]
    fn a_flat_tile_coarsens_and_a_steep_tile_does_not() {
        let (z, dim) = (14u8, 9u16);
        let (x, y) = (2730u64, 6335u64);
        // Relief 8 m: flat farmland. Corners pinned to distinct values so the
        // test can tell nearest-pick from averaging.
        let mut flat = vec![SEA_LEVEL + 100; dim as usize * dim as usize];
        flat[0] = SEA_LEVEL + 96;
        flat[8] = SEA_LEVEL + 104;
        flat[72] = SEA_LEVEL + 97;
        flat[80] = SEA_LEVEL + 103;
        let dem = Dem::parse(&dataset(z, dim, &[(tile_id(z, x, y), flat)])).expect("parse");
        let hm = dem.heightmap_for(z, x, y).expect("a heightmap");
        assert_eq!(hm.dim, FLAT_DIM, "a flat tile coarsens");
        assert_eq!(hm.samples.len(), FLAT_DIM as usize * FLAT_DIM as usize);
        assert_eq!(hm.samples[0], SEA_LEVEL + 96, "top-left corner survives");
        assert_eq!(hm.samples[4], SEA_LEVEL + 104, "top-right corner survives");
        assert_eq!(hm.samples[20], SEA_LEVEL + 97, "bottom-left corner survives");
        assert_eq!(hm.samples[24], SEA_LEVEL + 103, "bottom-right corner survives");
        // A steep tile (relief over the threshold) keeps the full grid.
        let steep = ramp(dim);
        assert!(relief(&steep) > FLAT_RELIEF_M, "the ramp is steep: {}", relief(&steep));
        let dem = Dem::parse(&dataset(z, dim, &[(tile_id(z, x, y), steep.clone())])).expect("parse");
        let hm = dem.heightmap_for(z, x, y).expect("a heightmap");
        assert_eq!(hm.dim, dim, "a steep tile keeps full resolution");
        assert_eq!(hm.samples, steep);
    }
}
