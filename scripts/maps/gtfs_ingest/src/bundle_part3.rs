impl Covered {
    /// Record every metre of `line` as drawn.
    pub fn add(&mut self, line: &[(i32, i32)]) {
        self.add_tagged(line, 0);
    }

    /// Record every metre of `line` as drawn by service `tag`.
    pub fn add_tagged(&mut self, line: &[(i32, i32)], tag: u32) {
        self.add_tagged_walked(&walk(line), tag);
    }

    /// [`add`](Self::add) over samples already walked by [`walk`].
    pub fn add_walked(&mut self, walked: &[Walked]) {
        self.add_tagged_walked(walked, 0);
    }

    /// [`add_tagged`](Self::add_tagged) over samples already walked by [`walk`], so a caller
    /// running several gates over one line walks it once and shares the result.
    pub fn add_tagged_walked(&mut self, walked: &[Walked], tag: u32) {
        let cells: Vec<Cell> = walked.iter().map(sample_cell).collect();
        self.add_tagged_walked_in(walked, &cells, tag);
    }

    /// [`add_tagged_walked`](Self::add_tagged_walked) with each sample's [`sample_cell`]
    /// already computed, parallel to `walked`.
    pub fn add_tagged_walked_in(&mut self, walked: &[Walked], cells: &[Cell], tag: u32) {
        debug_assert_eq!(walked.len(), cells.len());
        self.adds += 1;
        let now = self.adds;
        // A run of samples in one cell — about four per 40 m cell at 10 m spacing — needs one
        // bucket probe, not four: after the first, the cell holds a sample of `tag` whichever
        // way that probe went, so every later one would be dropped anyway. The same holds for
        // any repeat of a cell within one add, which is why each shard may also skip its own
        // consecutive repeats below.
        let mut firsts: Vec<u32> = Vec::with_capacity(walked.len() / 3 + 1);
        let mut prev: Option<Cell> = None;
        for (k, &cell) in cells.iter().enumerate() {
            if prev != Some(cell) {
                firsts.push(k as u32);
                prev = Some(cell);
            }
        }
        if firsts.len() < 2048 {
            for k in firsts {
                let cell = cells[k as usize];
                insert(&mut self.cells[shard_of(cell)], cell, walked[k as usize], tag, now);
            }
            return;
        }
        // Counting sort by shard, stable, so each shard takes its samples in line order.
        let mut starts = [0usize; COVER_SHARDS + 1];
        for &k in &firsts {
            starts[shard_of(cells[k as usize]) + 1] += 1;
        }
        for i in 0..COVER_SHARDS {
            starts[i + 1] += starts[i];
        }
        let mut fill = starts;
        let mut order = vec![0u32; firsts.len()];
        for &k in &firsts {
            let s = shard_of(cells[k as usize]);
            order[fill[s]] = k;
            fill[s] += 1;
        }
        // A few thousand inserts per task: each fork wakes sleeping workers, and on a line of a
        // few thousand cells waking all of them costs more than the inserts.
        let tasks = (firsts.len() / 2048).clamp(1, COVER_SHARDS);
        self.cells.par_iter_mut().enumerate().with_min_len(COVER_SHARDS.div_ceil(tasks)).for_each(|(s, map)| {
            for &k in &order[starts[s]..starts[s + 1]] {
                insert(map, cells[k as usize], walked[k as usize], tag, now);
            }
        });
    }

    /// The most distinct services already drawn over any one metre of `line`.
    ///
    /// The colour-scoped gates above stop a service being drawn twice, but they are deliberately
    /// blind to *other* services: two lines sharing a track are two real services and both should
    /// draw, fanned into lanes. That holds for a city. It does not hold for a planet, where one
    /// physical alignment is republished by a city feed, the regional feed that contains it and a
    /// national feed on top, each under its own `route_color` and often its own `route_type` — all
    /// of which read as distinct services and each claim a lane. That is what turns one railway
    /// into fifteen jagged parallel lines when you zoom in.
    ///
    /// So the rule is a ceiling rather than a ban: a few services over one track is real, fifteen
    /// is a data artefact. Returns the worst point rather than an average, because a line that
    /// joins a crowded trunk for part of its length is exactly the case worth suppressing.
    ///
    /// `limit` stops the walk as soon as any point reaches it. The answer above the ceiling is
    /// never used, only compared against it, and this is called once per surviving line over a
    /// structure holding every sample of every line already drawn.
    pub fn crowd_reaches(&self, line: &[(i32, i32)], limit: usize) -> bool {
        self.crowd_reaches_walked(&walk(line), limit)
    }

    /// [`crowd_reaches`](Self::crowd_reaches) over samples already walked by [`walk`].
    pub fn crowd_reaches_walked(&self, walked: &[Walked], limit: usize) -> bool {
        if limit == 0 {
            return true;
        }
        let mut seen: Vec<u32> = Vec::new();
        for &(point, ux, uy) in walked {
            seen.clear();
            self.tags_over(point, ux, uy, &mut seen, limit);
            if seen.len() >= limit {
                return true;
            }
        }
        false
    }

    /// The most distinct services already drawn over any one metre of `line`.
    ///
    /// Unbounded, for tests and for reporting. Prefer [`crowd_reaches`](Self::crowd_reaches) on
    /// the build path, which stops as soon as the answer can no longer change the decision.
    pub fn crowd(&self, line: &[(i32, i32)]) -> usize {
        let mut worst = 0;
        let mut seen: Vec<u32> = Vec::new();
        for &(point, ux, uy) in &walk(line) {
            seen.clear();
            self.tags_over(point, ux, uy, &mut seen, usize::MAX);
            worst = worst.max(seen.len());
        }
        worst
    }

    /// Every distinct service drawn within [`CORRIDOR_M`] of this point, running parallel to it.
    ///
    /// Stops once `limit` distinct services have been found, since no caller needs more.
    fn tags_over(&self, point: (i32, i32), ux: f64, uy: f64, out: &mut Vec<u32>, limit: usize) {
        self.tags_over_in(cell_of(point), point, ux, uy, out, limit);
    }

    /// [`tags_over`](Self::tags_over) with `cell_of(point)` already known.
    fn tags_over_in(&self, (cx, cy): Cell, point: (i32, i32), ux: f64, uy: f64, out: &mut Vec<u32>, limit: usize) {
        for (dx, dy) in NEIGHBOURHOOD {
            let Some(bucket) = self.bucket((cx + dx, cy + dy)) else { continue };
            for d in bucket.iter() {
                if (ux * d.ux + uy * d.uy).abs() < COS_FOLD {
                    continue;
                }
                if distance_m(point, d.point) <= CORRIDOR_M {
                    let tag = d.tag;
                    if !out.contains(&tag) {
                        out.push(tag);
                        if out.len() >= limit {
                            return;
                        }
                    }
                }
            }
        }
    }

    /// Is every metre of `line` already drawn?
    ///
    /// Walked at [`SAMPLE_M`] rather than at the line's own vertices, whose spacing is a
    /// feed's business and is sometimes hundreds of metres.
    pub fn contains(&self, line: &[(i32, i32)]) -> bool {
        self.covered_fraction(line) >= 1.0
    }

    /// How much of `line` is already drawn, from 0 to 1. A line too short to walk covers nothing,
    /// so it reports 0 and is kept — matching what the all-or-nothing test used to do with it.
    ///
    /// The all-or-nothing [`contains`](Self::contains) is too strict for one real case: a route
    /// published twice at slightly different *lengths*. A short-turn, or one feed's version
    /// running two stops further than another's, shares 95% of its alignment with what is already
    /// drawn, adds a little genuinely new track at one end, and is therefore kept **whole** —
    /// duplicating the 95%. On the ground that draws the A line as two strands about a metre
    /// apart, each fanned into its own corridor lane and tapered, so one line reads as two
    /// diverging and reconverging.
    ///
    /// Subtracting the covered part instead is not the answer and was tried: it cut every route
    /// into fragments and left holes where a piece fell below the length worth emitting. A
    /// fraction keeps the whole-line rule and only moves where the threshold sits.
    pub fn covered_fraction(&self, line: &[(i32, i32)]) -> f64 {
        self.covered_fraction_walked(&walk(line))
    }

    /// [`covered_fraction`](Self::covered_fraction) over samples already walked by [`walk`].
    pub fn covered_fraction_walked(&self, walked: &[Walked]) -> f64 {
        if walked.is_empty() {
            return 0.0;
        }
        let drawn = walked.iter().filter(|&&(point, ux, uy)| self.covers(point, ux, uy)).count();
        drawn as f64 / walked.len() as f64
    }

    /// Is this point already drawn, by track running parallel to it?
    ///
    /// Returns on the first match, so visit order matters — see [`NEIGHBOURHOOD`], which is
    /// ordered for exactly that reason. The buckets are unbounded: [`add`](Self::add) records
    /// every sample of every line it draws, and a trunk alignment republished by a dozen feeds is
    /// a dozen overlapping sample runs in the same cells.
    fn covers(&self, point: (i32, i32), ux: f64, uy: f64) -> bool {
        self.covers_in(cell_of(point), point, ux, uy)
    }

    /// [`covers`](Self::covers) with `cell_of(point)` already known.
    fn covers_in(&self, (cx, cy): Cell, point: (i32, i32), ux: f64, uy: f64) -> bool {
        for (dx, dy) in NEIGHBOURHOOD {
            let Some(bucket) = self.bucket((cx + dx, cy + dy)) else { continue };
            for d in bucket.iter() {
                if (ux * d.ux + uy * d.uy).abs() < COS_FOLD {
                    continue;
                }
                if distance_m(point, d.point) <= CORRIDOR_M {
                    return true;
                }
            }
        }
        false
    }

    /// Is this one walked sample, in `cell` (its [`sample_cell`]), already drawn? The
    /// per-sample half of [`covered_fraction_walked`](Self::covered_fraction_walked), for a
    /// caller that keeps the per-sample answers (see [`sample_cell`] for why that is sound).
    pub fn covers_sample_in(&self, cell: Cell, sample: &Walked) -> bool {
        self.covers_in(cell, sample.0, sample.1, sample.2)
    }

    /// Do at least `limit` distinct services already run over this one sample, in `cell`? The
    /// per-sample half of [`crowd_reaches_walked`](Self::crowd_reaches_walked): that is
    /// exactly "any sample of the line answers true here". `scratch` is reused between calls.
    pub fn crowd_reaches_in(&self, cell: Cell, sample: &Walked, limit: usize, scratch: &mut Vec<u32>) -> bool {
        if limit == 0 {
            return true;
        }
        scratch.clear();
        self.tags_over_in(cell, sample.0, sample.1, sample.2, scratch, limit);
        scratch.len() >= limit
    }

    fn bucket(&self, cell: Cell) -> Option<&Bucket> {
        self.cells[shard_of(cell)].get(&cell)
    }

    /// A mark to pass to [`changed_near`](Self::changed_near) later: how many adds this cover
    /// has taken so far.
    pub fn mark(&self) -> u32 {
        self.adds
    }

    /// Has any sample been added, since `mark` was taken, to a cell a query at `cell` reads?
    ///
    /// Each bucket records the last add that appended to it, so this is the nine bucket probes
    /// a query makes anyway, without the scan. When it is `false`, every
    /// [`covers_sample_in`](Self::covers_sample_in) / [`crowd_reaches_in`](Self::crowd_reaches_in)
    /// answer at `cell` is exactly what it was at `mark`.
    pub fn changed_near(&self, (cx, cy): Cell, mark: u32) -> bool {
        if self.adds == mark {
            return false;
        }
        NEIGHBOURHOOD
            .iter()
            .any(|(dx, dy)| self.bucket((cx + dx, cy + dy)).is_some_and(|b| b.added > mark))
    }
}

/// A multiply-xorshift hasher for the cover's cell maps, probed nine times per query.
///
/// SipHash's flood resistance buys nothing on our own grid coordinates, and nothing iterates
/// either map — every answer is a boolean or a count about one point — so the hasher cannot
/// leak an order into the output.
#[derive(Default, Clone, Copy)]
pub struct CellHasher(u64);

impl std::hash::Hasher for CellHasher {
    fn write(&mut self, bytes: &[u8]) {
        for &b in bytes {
            self.write_u64(u64::from(b));
        }
    }
    fn write_i32(&mut self, v: i32) {
        self.write_u64(u64::from(v as u32));
    }
    fn write_u32(&mut self, v: u32) {
        self.write_u64(u64::from(v));
    }
    fn write_u64(&mut self, v: u64) {
        self.0 = (self.0.rotate_left(26) ^ v).wrapping_mul(0x9E37_79B9_7F4A_7C15);
    }
    fn finish(&self) -> u64 {
        self.0 ^ (self.0 >> 29)
    }
}

/// `BuildHasher` for [`CellHasher`].
pub type CellHash = std::hash::BuildHasherDefault<CellHasher>;

/// Add one sample of `tag` to `cell`'s bucket in `map`, unless the cell already holds one.
fn insert(map: &mut std::collections::HashMap<Cell, Bucket, CellHash>, cell: Cell, (point, ux, uy): Walked, tag: u32, now: u32) {
    let drawn = Drawn { point, ux, uy, tag };
    match map.entry(cell) {
        std::collections::hash_map::Entry::Occupied(mut e) => {
            let bucket = e.get_mut();
            // One representative per cell per service: a second sample of the same service in
            // the same cell cannot change any `covers`/`tags_over` answer, and dropping it is
            // what stops a heavily-republished trunk's buckets growing with the feed count.
            if bucket.iter().any(|d| d.tag == tag) {
                return;
            }
            bucket.rest.push(drawn);
            bucket.added = now;
        }
        std::collections::hash_map::Entry::Vacant(e) => {
            e.insert(Bucket { added: now, first: drawn, rest: Vec::new() });
        }
    }
}

/// The grid cell a walked sample falls in.
///
/// What makes per-sample answers reusable across [`Covered::add`]s: both
/// [`Covered::covers_sample`] and [`Covered::crowd_reaches_at`] read only the 3 x 3 block of
/// cells around this one, and adding a line only ever *appends* samples to the cells its own
/// samples fall in. So an answer computed before some adds is still exact after them unless
/// one of those adds touched that 3 x 3 block — and a `true` stays `true` regardless, since
/// both answers can only grow as samples are appended.
pub fn sample_cell(sample: &Walked) -> Cell {
    cell_of(sample.0)
}

/// Every candidate's samples, flat, with the range each candidate's own block occupies.
///
/// Each candidate is resampled independently, so the walks go wide and are concatenated back
/// in candidate order — the blocks, and every sample within them, come out exactly as the
/// sequential walk left them, so nothing downstream can tell the difference.
fn sample_all(candidates: &[Candidate]) -> (Vec<Sample>, Vec<Range<usize>>) {
    let per: Vec<Vec<Sample>> = candidates
        .par_iter()
        .map(|candidate| {
            walk(candidate.points)
                .into_iter()
                .map(|(point, ux, uy)| Sample { route: candidate.route, point, ux, uy })
                .collect()
        })
        .collect();
    let mut blocks = Vec::with_capacity(candidates.len());
    let mut start = 0usize;
    for samples in &per {
        blocks.push(start..start + samples.len());
        start += samples.len();
    }
    // Concatenated wide too — ~9 GB of samples on a world set. `collect` keeps order.
    let out: Vec<Sample> = per.into_par_iter().flatten_iter().collect();
    (out, blocks)
}

/// A grid cell, `(x, y)` in [`CELL_M`] units.
pub type Cell = (i32, i32);

/// The cell a point falls in. Square in metres rather than in degrees, so a cell near the
/// pole is not a sliver: longitude is scaled by the cosine of its own latitude.
fn cell_of(point: (i32, i32)) -> Cell {
    let lat = point.0 as f64 * 1e-7;
    let cos_lat = lat.to_radians().cos().max(1e-6);
    let y = lat * 111_320.0 / CELL_M;
    let x = point.1 as f64 * 1e-7 * 111_320.0 * cos_lat / CELL_M;
    (x.floor() as i32, y.floor() as i32)
}

/// A sorted flat `(cell, sample)` vector, as [`crate::index`]'s transfer grid is.
struct Grid {
    entries: Vec<(Cell, u32)>,
}

impl Grid {
    fn build(samples: &[Sample]) -> Grid {
        let mut entries: Vec<(Cell, u32)> =
            samples.par_iter().enumerate().map(|(i, s)| (cell_of(s.point), i as u32)).collect();
        entries.par_sort_unstable();
        Grid { entries }
    }

    /// The routes with a sample within [`CORRIDOR_M`] of `at` running parallel to it,
    /// ascending and deduplicated. Always contains `at`'s own route.
    ///
    /// `seen` is a caller-owned scratch array of one slot per route, holding the `stamp` of
    /// the sample a route was last accepted for. It replaces an `out.contains()` linear scan
    /// that ran once per neighbouring sample: a 40 m cell holds ~4 samples per line at
    /// [`SAMPLE_M`], so the nine-cell neighbourhood holds ~36 per route in the corridor, and
    /// scanning `out` for each made the probe quadratic in *local route density*. On a
    /// world-scale set that is the whole cost — dense metros carry the same trunk track
    /// republished by every feed covering the city, so density there is far higher than the
    /// feed count suggests, and the run time grows much faster than the input does.
    ///
    /// A route is stamped only when it is **accepted**, never when the angle or distance test
    /// rejects it. That is what the `contains` check did — it tested membership of `out`, not
    /// of everything examined — and a route rejected against one sample must stay eligible
    /// against a nearer one in the same neighbourhood.
    fn routes_near(
        &self,
        samples: &[Sample],
        at: &Sample,
        out: &mut Vec<u32>,
        seen: &mut [u32],
        stamp: u32,
    ) {
        out.clear();
        out.push(at.route);
        seen[at.route as usize] = stamp;
        let (cx, cy) = cell_of(at.point);
        for dx in -1..=1 {
            // The grid is sorted by `(x, y)`, so the three cells `(x, cy - 1..=cy + 1)` are one
            // contiguous run of it, in exactly the order the three per-cell buckets were
            // visited: two binary searches over the whole grid instead of six.
            let x = cx + dx;
            let lo = self.entries.partition_point(|(k, _)| *k < (x, cy - 1));
            let hi = lo + self.entries[lo..].partition_point(|(k, _)| *k <= (x, cy + 1));
            for &(_, i) in &self.entries[lo..hi] {
                let other = &samples[i as usize];
                if seen[other.route as usize] == stamp {
                    continue;
                }
                if (at.ux * other.ux + at.uy * other.uy).abs() < COS_FOLD {
                    continue;
                }
                if distance_m(at.point, other.point) <= CORRIDOR_M {
                    seen[other.route as usize] = stamp;
                    out.push(other.route);
                }
            }
        }
        out.sort_unstable();
    }
}
