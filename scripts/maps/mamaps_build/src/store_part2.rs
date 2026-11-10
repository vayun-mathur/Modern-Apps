impl Store {
    /// How many features are in the file.
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn len(&self) -> u64 {
        self.count
    }

    /// The bounding box of every feature, in degrees times 1e7, for the archive header.
    pub fn bbox(&self) -> (i32, i32, i32, i32) {
        self.bbox
    }

    /// Read every feature a zoom could want, in the order they were written.
    ///
    /// A chunk whose shallowest `min_zoom` is deeper than `z` holds nothing this zoom draws, so it is
    /// skipped outright — not read, not parsed. Order is unchanged, because the chunks that survive
    /// are still visited in file order, and that is what keeps the archive byte-identical.
#[cfg_attr(not(test), allow(dead_code))]
    pub fn reader_for_zoom(&self, z: u8) -> Result<ZoomReader> {
        let wanted: Vec<usize> = self
            .chunk_mins
            .iter()
            .enumerate()
            .filter(|(_, min)| **min <= z)
            .map(|(i, _)| i)
            .collect();
        self.reader_for_wanted(wanted, z)
    }

    pub fn wanted_chunks_for_zoom(&self, z: u8) -> Vec<usize> {
        self.chunk_mins
            .iter()
            .enumerate()
            .filter(|(_, min)| **min <= z)
            .map(|(i, _)| i)
            .collect()
    }

    pub fn from_parts(path: PathBuf, chunks: Vec<u64>, chunk_mins: Vec<u8>) -> Self {
        Self {
            path,
            anon: None,
            chunks,
            chunk_mins,
            count: 0,
            bbox: (0, 0, 0, 0),
            conventions: crate::schema::boundaries::Conventions::default(),
        }
    }

    /// Open the staged bytes for chunked reading, anonymous or file according to the plan the
    /// sink was created with. One helper so the three reader constructors agree.
    fn open_chunks(&self) -> Result<NormalizedChunks> {
        match &self.anon {
            Some(store) => NormalizedChunks::open_anon(
                self.path.clone(),
                self.chunks.clone(),
                std::sync::Arc::clone(store),
            )
            .map_err(|e| osm_ingest::proto::Error(e.to_string())),
            None => NormalizedChunks::open(self.path.clone(), self.chunks.clone())
                .map_err(|e| osm_ingest::proto::Error(e.to_string())),
        }
    }

    /// Open the staged bytes for sequential reading. See [`open_chunks`](Self::open_chunks).
    fn open_sequential(&self) -> Result<NormalizedReader> {
        match &self.anon {
            Some(store) => NormalizedReader::open_anon(
                self.path.clone(),
                std::sync::Arc::clone(store),
            )
            .map_err(|e| osm_ingest::proto::Error(e.to_string())),
            None => NormalizedReader::open(self.path.clone())
                .map_err(|e| osm_ingest::proto::Error(e.to_string())),
        }
    }

    /// Attach the anonymous store after construction. Used by tests that build
    /// a `Store` from parts and then read it back without a file.
    #[cfg(test)]
    pub fn with_anon(
        mut self,
        store: std::sync::Arc<tile_build::anon::AnonStore>,
    ) -> Self {
        self.anon = Some(store);
        self
    }

    /// Take the marking-convention grid stage A resolved from the country relations.
    pub fn with_conventions(
        mut self,
        conventions: crate::schema::boundaries::Conventions,
    ) -> Self {
        self.conventions = conventions;
        self
    }

    /// Which marking convention applies where, for the tiler to stamp onto each tile.
    pub fn conventions(&self) -> &crate::schema::boundaries::Conventions {
        &self.conventions
    }

    pub fn reader_for_wanted(&self, wanted: Vec<usize>, z: u8) -> Result<ZoomReader> {
        let chunks = self.open_chunks()?;
        ZoomReader::spawn(chunks, wanted, z)
    }

    /// Read every feature, in the order they were written.
    ///
    /// Kept as the reference [`Self::reader_for_zoom`] is checked against: a test that reads a store
    /// both ways and compares is the cheapest possible guard on the chunk index being right.
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn reader(&self) -> Result<Reader> {
        Ok(Reader { inner: self.open_sequential()? })
    }
}

impl Store {
    /// Spill features from memory into a temporary store.
    ///
    /// For tests only. The generator never has a `Vec<Feature>` to hand -- that is the entire point
    /// of this module -- but a test that had to write a file to state its case would be a worse test.
    #[cfg(test)]
    pub fn of(features: &[Feature]) -> Result<Store> {
        use std::sync::atomic::{AtomicU64, Ordering};
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "mamaps_test_{}_{}.features",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed),
        ));
        let mut sink = Sink::create(&path)?;
        for feature in features {
            // Test-only path: encode inline (the pool-built path is exercised
            // by the materialise drains, not here).
            let encoded = EncodedGeom::live(&feature.geometry)?;
            if feature.transit_color != 0 {
                sink.push_transit(
                    &feature.class,
                    &encoded,
                    feature.transit_color,
                    feature.transit_ordinal,
                    feature.transit_lanes,
                    feature.transit_taper,
                )?;
            } else if let Some(attrs) = feature.building {
                sink.push_building(
                    &feature.class,
                    &encoded,
                    feature.name.as_deref(),
                    attrs,
                )?;
            } else if feature.lane_count != 0
                || !feature.turn_fwd.is_empty()
                || !feature.turn_bwd.is_empty()
                || !feature.carriageway.is_empty()
            {
                sink.push_road(
                    &feature.class,
                    &encoded,
                    feature.name.as_deref(),
                    feature.lane_count,
                    &feature.turn_fwd,
                    &feature.turn_bwd,
                    feature.carriageway,
                )?;
            } else {
                sink.push_named(&feature.class, &encoded, feature.name.as_deref(), feature.id)?;
            }
        }
        sink.finish(&path)
    }
}

/// A spilled record as a [`Feature`].
///
/// A record whose class property is missing or is not an integer is a corrupt file rather than a
/// feature to skip: everything in here was written by [`Sink::push`] one run ago, so anything else
/// means the file is not the one we wrote. A `n` string property rides along as the display
/// name, a `t` integer as the transit colour, an `s` integer as its packed lane inputs and an `i`
/// integer as the tagged OSM id; anything else in there is corruption for the same reason.
fn feature_of(record: tile_build::spill::NormalizedFeature) -> Result<Feature> {
    let bits = match record.props.iter().find(|(key, _)| key == CLASS_KEY) {
        Some((_, Value::Uint(bits))) => *bits,
        _ => return err("a spilled feature carries no packed class".to_string()),
    };
    let mut name: Option<String> = None;
    let mut id: u64 = tilecodec::mamaps::body::ID_NONE;
    let mut transit_color: u32 = 0;
    let mut transit_ordinal: u8 = 0;
    let mut transit_lanes: u8 = 0;
    let mut transit_taper: u8 = 0;
    let mut lane_count: u8 = 0;
    let mut turn_fwd: Vec<u16> = Vec::new();
    let mut turn_bwd: Vec<u16> = Vec::new();
    let mut carriageway = Carriageway::default();
    let mut bld_a: Option<u64> = None;
    let mut bld_b: Option<u64> = None;
    let mut bld_c: Option<u64> = None;
    for (key, value) in &record.props {
        if key == CLASS_KEY {
            continue;
        }
        match (key.as_str(), value) {
            (NAME_KEY, Value::String(text)) if !text.is_empty() => {
                if name.replace(text.clone()).is_some() {
                    return err("a spilled feature carries two names".to_string());
                }
            }
            (COLOR_KEY, Value::Uint(color)) => {
                transit_color = u32::try_from(*color)
                    .map_err(|_| Error("a spilled transit colour does not fit u32".to_string()))?;
            }
            (LANE_KEY, Value::Uint(lane_bits)) => {
                if *lane_bits > 0xff_ff_ff {
                    return err("a spilled transit lane triple does not fit three bytes".to_string());
                }
                transit_ordinal = (lane_bits >> 16) as u8;
                transit_lanes = (lane_bits >> 8) as u8;
                transit_taper = *lane_bits as u8;
            }
            (LANE_COUNT_KEY, Value::Uint(count)) => {
                lane_count = u8::try_from(*count)
                    .map_err(|_| Error("a spilled road lane count does not fit a byte".to_string()))?;
            }
            (TURN_FWD_KEY, Value::String(packed)) => {
                turn_fwd = unpack_lanes(packed)?;
            }
            (TURN_BWD_KEY, Value::String(packed)) => {
                turn_bwd = unpack_lanes(packed)?;
            }
            (CARRIAGEWAY_KEY, Value::Uint(bits)) => {
                carriageway = unpack_carriageway(*bits);
            }
            (BLD_A_KEY, Value::Uint(v)) => bld_a = Some(*v),
            (BLD_B_KEY, Value::Uint(v)) => bld_b = Some(*v),
            (BLD_C_KEY, Value::Uint(v)) => bld_c = Some(*v),
            (ID_KEY, Value::Uint(tagged)) => {
                if id != tilecodec::mamaps::body::ID_NONE {
                    return err("a spilled feature carries two ids".to_string());
                }
                id = *tagged;
            }
            _ => {
                return err(format!("a spilled feature carries an unknown property `{key}`"));
            }
        }
    }
    Ok(Feature {
        class: unpack(bits),
        geometry: record.geometry,
        name,
        id,
        transit_color,
        transit_ordinal,
        transit_lanes,
        transit_taper,
        lane_count,
        turn_fwd,
        turn_bwd,
        carriageway,
        building: match (bld_a, bld_b, bld_c) {
            (Some(a), Some(b), Some(c)) => Some(unpack_building(a, b, c)),
            (None, None, None) => None,
            _ => return err("a spilled building is missing part of its attributes".to_string()),
        },
    })
}

/// Chunks of features as the lanes hand them over, or the error that stopped one.
///
/// [`Feature`] rather than the decoder's own record, because turning one into the other is where
/// the reader thread's remaining time went. It looks like a field lookup and a move, and the field
/// lookup and the move are free; what is not is dropping the `props` the decoder built — a `Vec` and
/// an owned key `String` per feature, freed one at a time on the one thread the whole map phase
/// waits on. Converting in the lane puts that on the lane.
#[cfg_attr(not(test), allow(dead_code))]
type Decoded = Result<Vec<Feature>>;

/// Threads decoding spill chunks ahead of the tiler.
///
/// Not the pool: see [`ZoomReader::spawn`]. **Four is the safe default, not the right answer**, and
/// there is no right answer available as a constant. The two extracts want opposite things:
///
/// | lanes / tiling workers | us-west | north-america |
/// |---|---|---|
/// | 4 / 64 | **91.9 s** | 945.7 s |
/// | 16 / 64 | 151.9 s | **766.9 s** |
/// | 16 / 48 | 94.4 s | 900.4 s |
///
/// Sixteen lanes is a 65% regression on us-west and a 19% improvement on north-america, and the
/// mechanism is the same in both: a lane competes for a CPU only when it is running. On
/// north-america the reader cannot fill the channel fast enough, so the clipping workers are blocked
/// and the lanes are free; on us-west the workers saturate the machine and the lanes take CPU from
/// the single reader thread they exist to feed. Which regime a build lands in depends on the spill
/// against the page cache, not on anything this constant can see — the middle row of that table is
/// the attempt to correct for it by taking the lanes out of the worker budget, and it makes each
/// case worse than that case's own best.
///
/// So it is a knob, defaulting to the value that cannot hurt. `MAPS_PREFETCH_LANES=16` is what a
/// continent wants. Removing the need for the knob means removing the re-decode it is compensating
/// for: the spill is read once per zoom, which is ~1.06 billion feature decodes to deliver
/// 199.7 M distinct features on north-america, and no lane count makes redundant work cheap.
#[cfg_attr(not(test), allow(dead_code))]
pub fn prefetch_lanes() -> usize {
    static LANES: std::sync::OnceLock<usize> = std::sync::OnceLock::new();
    *LANES.get_or_init(|| {
        std::env::var("MAPS_PREFETCH_LANES")
            .ok()
            .and_then(|raw| raw.trim().parse::<usize>().ok())
            .filter(|n| *n > 0)
            .unwrap_or(4)
    })
}
