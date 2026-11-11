impl Graph {
    // --- Raw accessors (all unaligned-safe) ---

    /// Whether this graph reads record tables through v7 blocked sections.
    /// Test hook: the loaders set the views, the accessors branch on them.
    pub(crate) fn uses_blocked_sections(&self) -> bool {
        self.nodes_view.is_some() || self.edges_view.is_some()
    }

    #[inline]
    pub fn node(&self, id: u32) -> NodeMaster {
        // Callers guarantee id <= node_count (sentinel index is valid).
        if let Some(view) = &self.nodes_view {
            return self.blocked_node(view, id);
        }
        let r = unsafe { read_at::<NodeRec>(self.nodes, id as usize) };
        NodeMaster {
            lat_e7: r.lat_e7,
            lon_e7: r.lon_e7,
            edge_ptr: u64::from(r.edge_ptr),
        }
    }

    /// A node record out of a v7 blocked section; inflates its block on first
    /// read. Not `#[cold]`: the shipped archive is v7, so this is the A* hot path.
    #[inline]
    fn blocked_node(&self, view: &BlockedView, id: u32) -> NodeMaster {
        let Some(r) = view.record(u64::from(id)).filter(|r| r.len() >= 12) else {
            return NodeMaster {
                lat_e7: 0,
                lon_e7: 0,
                edge_ptr: 0,
            };
        };
        NodeMaster {
            lat_e7: i32::from_le_bytes([r[0], r[1], r[2], r[3]]),
            lon_e7: i32::from_le_bytes([r[4], r[5], r[6], r[7]]),
            edge_ptr: u64::from(u32::from_le_bytes([r[8], r[9], r[10], r[11]])),
        }
    }

    /// Safe node fetch: returns a zeroed node for out-of-range ids, mirroring
    /// the C++ `get_node` fallback.
    ///
    /// Deliberately asymmetric with [`Graph::node`], which is unchecked: the
    /// sentinel index `node_count` is a *valid* read that this method rejects, and
    /// [`Graph::edge_range`] depends on being able to make it.
    ///
    /// Note: under v7 the raw table pointer is still the section base (never
    /// null), so this null check means "graph without a node table", not
    /// "blocked layout" (see `load`/`assemble`).
    #[inline]
    pub fn get_node(&self, id: u32) -> NodeMaster {
        if self.nodes.is_null() || id >= self.node_count {
            return NodeMaster {
                lat_e7: 0,
                lon_e7: 0,
                edge_ptr: 0,
            };
        }
        self.node(id)
    }

    /// Whether the graph carries node elevations at all (`elevation.bin` / archive kind 7, raw
    /// v6 or blocked v7). The planet archives are built without it (`road_graph` ran without
    /// `--dem`), and then every [`node_elevation`](Self::node_elevation) is 0: callers use this
    /// to report "no profile" instead of a flat 0 m one.
    pub fn has_elevation(&self) -> bool {
        self.elevation_view.is_some() || !self.elevation.is_null()
    }

    /// Ground elevation of node `id` in metres, or 0 when the graph carries no `elevation.bin` or
    /// `id` is out of range. Baked from the DEM at build time (WS-G); a build without a DEM, or a
    /// node off the DEM's coverage, reads as 0 so the route profile is simply flat there.
    #[inline]
    pub fn node_elevation(&self, id: u32) -> i16 {
        if id >= self.node_count {
            return 0;
        }
        if let Some(view) = &self.elevation_view {
            return self.blocked_elevation(view, id);
        }
        if self.elevation.is_null() {
            return 0;
        }
        unsafe { read_at::<i16>(self.elevation, id as usize) }
    }

    /// An elevation sample out of a v7 blocked section; inflates its block on
    /// first read.
    #[cold]
    fn blocked_elevation(&self, view: &BlockedView, id: u32) -> i16 {
        match view.record(u64::from(id)) {
            Some(&[a, b, ..]) => i16::from_le_bytes([a, b]),
            _ => 0,
        }
    }

    /// `[start, end)` of node `id`'s outgoing edges in `edges.bin`. `id` must be a
    /// real node, i.e. `id < node_count`.
    ///
    /// The only way to ask that question. Every caller that reads an `edge_ptr`
    /// wants a range, not an offset, so the pairing is expressed once here rather
    /// than transcribed at eight sites — which is also what stops a future
    /// `nodes.bin` layout from leaking out of this file.
    ///
    /// # What the sentinel record is for
    ///
    /// `nodes.bin` carries one record past the real nodes whose `edge_ptr` is
    /// `edge_count`. That is what makes this well-defined for the *last* real node:
    /// `id + 1` is read for every node, so without it the last one would read past
    /// the mapping and every other node's range would be one short of correct.
    /// `edge_range(node_count - 1).1 == edge_count` is the property, and it is
    /// load-bearing at eight sites.
    ///
    /// The sentinel index itself is the highest `id` this may be *passed plus one* —
    /// `edge_range(node_count)` would read `node_count + 1`, which does not exist.
    /// Every caller either iterates `0..node_count` or rejects `id >= node_count`
    /// first.
    ///
    /// Note that this goes through [`Graph::node`], which is unchecked, *not*
    /// [`Graph::get_node`], which would reject the sentinel index and silently
    /// return `edge_ptr = 0` — turning the last node's range into an empty one.
    #[inline]
    pub fn edge_range(&self, id: u32) -> (u64, u64) {
        debug_assert!(id < self.node_count, "node {id} is not a real node");
        (self.node(id).edge_ptr, self.node(id + 1).edge_ptr)
    }

    /// Edge `idx`, which must be one of node `source`'s outgoing edges.
    ///
    /// `source` is what makes the record readable at all: it stores `target` as a
    /// signed delta from it. Passing the wrong one produces a plausible node id
    /// rather than a crash, which is why the debug assertion below exists — it is the
    /// cheapest available guard against the one bug class delta coding introduces.
    #[inline]
    pub fn edge(&self, source: u32, idx: u64) -> Edge {
        debug_assert!(
            {
                let (s, e) = self.edge_range(source);
                (s..e).contains(&idx)
            },
            "edge {idx} is not in node {source}'s range {:?}",
            self.edge_range(source)
        );
        let r = self.edge_rec(source, idx);
        let (target, dist_mm) = if r.escaped() {
            self.escaped_fields(idx)
        } else {
            (
                source.wrapping_add_signed(i32::from(r.target_delta)),
                r.dist_mm(),
            )
        };
        Edge {
            target,
            dist_mm,
            type_: r.type_,
            speed_limit: r.speed_limit,
        }
    }

    /// One edge record, by value. v6 reads the mapped record directly; v7
    /// reads it out of its lazily inflated block.
    #[inline]
    fn edge_rec(&self, _source: u32, idx: u64) -> EdgeRec {
        if let Some(view) = &self.edges_view {
            return self.blocked_edge(view, idx);
        }
        unsafe { read_at::<EdgeRec>(self.edges, idx as usize) }
    }

    /// An edge record out of a v7 blocked section. Hot path, like
    /// [`Graph::blocked_node`].
    #[inline]
    fn blocked_edge(&self, view: &BlockedView, idx: u64) -> EdgeRec {
        match view.record(idx) {
            Some(&[d0, d1, x0, x1, x2, type_, speed_limit, ..]) => EdgeRec {
                target_delta: i16::from_le_bytes([d0, d1]),
                dist: [x0, x1, x2],
                type_,
                speed_limit,
            },
            _ => EdgeRec {
                target_delta: 0,
                dist: [0, 0, 0],
                type_: 0,
                speed_limit: 0,
            },
        }
    }

    /// `(target, dist_mm)` from the escape table, for an edge whose record carries a
    /// sentinel.
    ///
    /// One load from the block index, then a scan of that block's rows — ~3 of them
    /// at a 0.3% escape rate, usually within one cache line. See [`ESCAPE_BLOCK`].
    ///
    /// A record with a sentinel and no matching row is a corrupt pack that the load-
    /// time checks cannot catch without an O(E) scan. It returns `u32::MAX` as the
    /// target, which every caller already rejects as out of range, so the edge is
    /// skipped rather than followed somewhere arbitrary.
    #[cold]
    fn escaped_fields(&self, idx: u64) -> (u32, u32) {
        let block = (idx / ESCAPE_BLOCK) as usize;
        let lo = unsafe { read_at::<u32>(self.escape_first, block) };
        let hi = unsafe { read_at::<u32>(self.escape_first, block + 1) };
        for r in lo..hi {
            let row = unsafe { read_at::<EscapeRow>(self.escapes, r as usize) };
            if u64::from(row.edge_idx) == idx {
                return (row.target, row.dist_mm);
            }
        }
        debug_assert!(false, "edge {idx} is escaped but has no escape row");
        (u32::MAX, DIST_MM_ESCAPE)
    }

    /// Whether edge `idx` of node `source` points at `want`.
    ///
    /// Four call sites scan a node's edge range looking for the one edge that reaches
    /// a given node and never use the target as a value. Delegating to [`Graph::edge`]
    /// rather than comparing in delta space keeps the escape handling in one place:
    /// the delta form would have to handle both an escaped record that could still
    /// match and a `want − source` outside `i16` range, and the saving over one
    /// sign-extend and one add is nothing.
    #[inline]
    pub fn edge_targets(&self, idx: u64, source: u32, want: u32) -> bool {
        self.edge(source, idx).target == want
    }
}
