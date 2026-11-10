impl Sink {
    /// The feature spill stages in anonymous pagefile-backed memory (no `.tmp`
    /// file, nothing stranded on kill). `path` names nothing — it only rides
    /// along for error messages.
    pub fn create(path: impl AsRef<Path>) -> Result<Sink> {
        Self::create_planned(path, osm_ingest::mem::SpillPlan::Anon)
    }

    /// Create with an explicit staging plan. See [`osm_ingest::mem::SpillPlan::decide`]: the
    /// budget gate picks `File` when anon commit does not fit, and `File` stages the same
    /// records through a `.tmp` beside `path`, removed by `finish`'s caller on success like every
    /// other scratch file. Same records, same offsets, same bytes -- the backend never changes a
    /// hash.
    pub fn create_planned(path: impl AsRef<Path>, plan: osm_ingest::mem::SpillPlan) -> Result<Sink> {
        let path = path.as_ref();
        let writer = match plan {
            osm_ingest::mem::SpillPlan::Anon => {
                NormalizedWriter::create_anon(std::path::PathBuf::from("anon-spill"))
                    .map_err(|e| osm_ingest::proto::Error(e.to_string()))?
            }
            osm_ingest::mem::SpillPlan::File => {
                eprintln!("  [stage A] feature spill -> {} (file backend: commit budget)", path.display());
                // Created empty and marked uncompressed first (`create` below truncates the same
                // inode, keeping the flag): the frames are already DEFLATE, so `compress-force`
                // spends a zstd attempt per 128 KiB extent at writeback on bytes that will not
                // shrink -- 289 GB of them on the planet. Best effort; the bytes are the same.
                if let Ok(file) = std::fs::File::create(path) {
                    osm_ingest::mem::mark_scratch_uncompressed(&file);
                }
                NormalizedWriter::create(path)
                    .map_err(|e| osm_ingest::proto::Error(e.to_string()))?
            }
        };
        Ok(Sink {
            writer,
            props: vec![(CLASS_KEY.to_string(), Value::Uint(0))],
            chunk_mins: Vec::new(),
            filling: u8::MAX,
            count: 0,
            bbox: None,
        })
    }

    pub fn push(&mut self, class: &Class, geometry: &EncodedGeom) -> Result<()> {
        self.push_named(class, geometry, None, tilecodec::mamaps::body::ID_NONE)
    }

    /// TEMP-PROFILE: compressed spill bytes staged so far. Removed with the probes.
    pub fn staged_bytes(&self) -> u64 {
        self.writer.staged_bytes()
    }

    /// Push a transit line: like [`Sink::push`], plus the route colour that becomes the body's
    /// `transit_color` and the lane inputs that become its `transit_ordinal`, `transit_lanes` and
    /// `transit_taper`. A zero colour is refused, not written as absent — zero means "no colour"
    /// on the wire, and a transit line without one is a caller bug, not a pale line.
    pub fn push_transit(
        &mut self,
        class: &Class,
        geometry: &EncodedGeom,
        color: u32,
        ordinal: u8,
        lanes: u8,
        taper: u8,
    ) -> Result<()> {
        if color == 0 {
            return err("a transit line with no colour".to_string());
        }
        self.props[0].1 = Value::Uint(pack(class)?);
        self.props.truncate(1);
        self.props.push((COLOR_KEY.to_string(), Value::Uint(color as u64)));
        let lane_bits = ((ordinal as u64) << 16) | ((lanes as u64) << 8) | (taper as u64);
        self.props.push((LANE_KEY.to_string(), Value::Uint(lane_bits)));
        self.write_record(class, geometry)
    }

    /// Push a feature with an optional display name (labels: `places` and `poi`) and, for those
    /// same two layers, the tagged OSM id it came from.
    ///
    /// One prop on the wire for the nameless, idless 190 M and up to three for a label: the spill
    /// encodes a per-record prop count, so variable shapes cost nothing. `feature_of` reads them
    /// all back.
    ///
    /// An id on any layer but `places` or `poi` is refused rather than dropped. Those are the only
    /// two the archive's id table carries, and a caller passing one elsewhere has misunderstood
    /// which features have a stable identity — `coalesce` merges lines and areas, so theirs would
    /// be whichever input happened to survive.
    pub fn push_named(
        &mut self,
        class: &Class,
        geometry: &EncodedGeom,
        name: Option<&str>,
        id: u64,
    ) -> Result<()> {
        fill_named(&mut self.props, class, name, id)?;
        self.write_record(class, geometry)
    }

    /// Push a road with its carriageway lane count, per-lane turn masks and directional split:
    /// like [`Sink::push`], plus the optional display name (so a road's label survives even when it
    /// carries lane data), the lane count (body `lane_count`), the forward/backward `turn:lanes`
    /// masks (body turn-lane table) and the [`Carriageway`] (body carriageway table). Refused with
    /// no name and all lane data empty — a plain, nameless road goes through [`Sink::push`] and
    /// carries no extra property at all, so the spill pays nothing for it.
    pub fn push_road(
        &mut self,
        class: &Class,
        geometry: &EncodedGeom,
        name: Option<&str>,
        lane_count: u8,
        turn_fwd: &[u16],
        turn_bwd: &[u16],
        carriageway: Carriageway,
    ) -> Result<()> {
        fill_road(&mut self.props, class, name, lane_count, turn_fwd, turn_bwd, carriageway)?;
        self.write_record(class, geometry)
    }

    /// Push a building with its S3DB attributes and optional display name. The attributes ride as
    /// three packed `Uint`s ([`BLD_A_KEY`]/[`BLD_B_KEY`]/[`BLD_C_KEY`]), always written — even for
    /// an all-default building — so the tiler can build a side table dense-parallel to the
    /// `buildings` layer. A name is written only when present (a building is rarely named; its
    /// label is usually a `poi`).
    pub fn push_building(
        &mut self,
        class: &Class,
        geometry: &EncodedGeom,
        name: Option<&str>,
        attrs: BuildingAttrs,
    ) -> Result<()> {
        fill_building(&mut self.props, class, name, attrs)?;
        self.write_record(class, geometry)
    }

    fn write_record(&mut self, class: &Class, geometry: &EncodedGeom) -> Result<()> {
        self.writer
            .push_preencoded(geometry.kind, &geometry.bytes, geometry.bounds, &self.props)
            .map_err(|e| osm_ingest::proto::Error(e.to_string()))?;
        self.filling = self.filling.min(class.min_zoom);
        self.count += 1;
        // Closed on the same boundary `NormalizedWriter` closes its own chunks on, so entry `i` here
        // describes chunk `i` there. Off by one and the tiler skips the wrong features.
        if self.count % NORM_CHUNK_FEATURES == 0 {
            self.chunk_mins.push(self.filling);
            self.filling = u8::MAX;
        }
        self.grow_bounds(geometry.ebbx);
        Ok(())
    }

    /// Extend the bounding box by one geometry's precomputed e7 box.
    ///
    /// [`Sink::grow`] walked vertices; the encoder already did on the pool (see
    /// [`ebbx_of`](crate::store::ebbx_of)). `None` (no vertices at all)
    /// contributes nothing — identical to the walk visiting nothing.
    fn grow_bounds(&mut self, ebbx: Option<(i32, i32, i32, i32)>) {
        let Some((x0, y0, x1, y1)) = ebbx else {
            return;
        };
        match &mut self.bbox {
            None => self.bbox = Some((x0, y0, x1, y1)),
            Some(s) => {
                s.0 = s.0.min(x0);
                s.1 = s.1.min(y0);
                s.2 = s.2.max(x1);
                s.3 = s.3.max(y1);
            }
        }
    }

    /// Extend the bounding box by a geometry, in degrees times 1e7.
    fn grow(&mut self, geometry: &Geometry) {
        let mut visit = |&(lon, lat): &(f64, f64)| {
            let (x, y) = ((lon * 1e7) as i32, (lat * 1e7) as i32);
            match &mut self.bbox {
                None => self.bbox = Some((x, y, x, y)),
                Some(b) => {
                    b.0 = b.0.min(x);
                    b.1 = b.1.min(y);
                    b.2 = b.2.max(x);
                    b.3 = b.3.max(y);
                }
            }
        };
        match geometry {
            Geometry::Points(points) => points.iter().for_each(&mut visit),
            Geometry::Lines(lines) => lines.iter().flatten().for_each(&mut visit),
            Geometry::Polygons(polygons) => {
                polygons.iter().flatten().flatten().for_each(&mut visit)
            }
        }
    }

    pub fn finish(mut self, path: impl Into<PathBuf>) -> Result<Store> {
        let (count, bbox) = (self.count, self.bbox);
        // The last chunk is usually partial and still needs an entry.
        if self.filling != u8::MAX {
            self.chunk_mins.push(self.filling);
        }
        let chunk_mins = std::mem::take(&mut self.chunk_mins);
        // Anonymous or file-backed depending on the plan `create_planned` took: seal the store and
        // carry the anon bytes on the `Store`, or `None` when the bytes live in the file at `path`.
        let (summary, anon) = self
            .writer
            .finish_either()
            .map_err(|e| osm_ingest::proto::Error(e.to_string()))?;
        let chunks = summary.chunks;
        // The two indexes must describe the same chunks, or skipping silently drops real features.
        // Cheap to assert and near-impossible to diagnose from the symptom, which would be missing
        // geometry in a handful of tiles at one zoom.
        let expected = chunks.len().saturating_sub(1);
        if chunk_mins.len() != expected {
            return err(format!(
                "the spill has {expected} chunk(s) but {} zoom entr(ies)",
                chunk_mins.len(),
            ));
        }
        Ok(Store {
            path: path.into(),
            anon: anon.map(std::sync::Arc::new),
            count,
            bbox: bbox.unwrap_or((0, 0, 0, 0)),
            chunks,
            chunk_mins,
            conventions: crate::schema::boundaries::Conventions::default(),
        })
    }

    /// The bounding box of everything pushed so far, in degrees.
    ///
    /// `None` until something has been pushed. Used to clip a planet-wide coastline product down to
    /// the area a build actually covers, which is why it is readable mid-stream.
    pub fn bbox_degrees(&self) -> Option<(f64, f64, f64, f64)> {
        let (min_x, min_y, max_x, max_y) = self.bbox?;
        Some((
            min_x as f64 * 1e-7,
            min_y as f64 * 1e-7,
            max_x as f64 * 1e-7,
            max_y as f64 * 1e-7,
        ))
    }
}

/// Features staged in anonymous memory or a scratch file, re-readable in order as many times as
/// the tiler needs. `path` names the file on the `File` plan and nothing on the `Anon` one (it
/// only rides along for error messages there). `anon` carries the pagefile-backed store when
/// present; everything else (indexes, counts, conventions) is as before.
pub struct Store {
    path: PathBuf,
    anon: Option<std::sync::Arc<tile_build::anon::AnonStore>>,
    /// Byte offset of every 64th record, plus a sentinel holding the file length, so chunk `i` spans
    /// `chunks[i]..chunks[i + 1]` and reads with no knowledge of any other chunk.
    chunks: Vec<u64>,
    /// The shallowest `min_zoom` in each chunk.
    chunk_mins: Vec<u8>,
    count: u64,
    bbox: (i32, i32, i32, i32),
    /// Which marking convention applies where, resolved once from the extract's country relations.
    ///
    /// Not a property of the features and so not in the spill: it is one small grid for the whole
    /// build, read by the tiler once per tile. It rides on the `Store` because that is the one
    /// thing stage A hands the tiler.
    conventions: crate::schema::boundaries::Conventions,
}
