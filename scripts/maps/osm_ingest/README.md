# osm_ingest

Host-side Rust tools that turn an OpenStreetMap `.osm.pbf` extract into the
Maps app's offline artifacts. Three binaries share one library:

| Binary | Replaces | Emits |
|---|---|---|
| `road_graph` | `scripts/maps/generator.cpp` | `metadata.bin`, `nodes.bin`, `edges.bin`, `lanes.bin`, `intermediate.bin`, `road_names.bin` |
| `poi_extract` | `scripts/maps/poi_extract.cpp` | `<name>.geojsonseq`, `poi_names.bin`, `poi_index.bin`, `poi_attrs.bin`, `poi_spatial.bin`, `poi_name_index.bin`, `poi_region.bin`, `poi_admin.bin` |
| `osm_extract` | `osmium tags-filter \| osmium export \| normalize_*.py` | one `<layer>.geojsonseq` per baked vector layer |

## Why Rust (LANGUAGE RULE)

- **Rust-first:** the same rule that made `gtfs_ingest` Rust rather than Python,
  and the router Rust rather than C++ (`maps/src/main/cpp/` is gone). These two
  files were the last hand-written C++ in the Maps pipeline.
- **No libosmium, so no WSL.** The C++ needed `g++` + `libosmium2-dev` +
  `-lz -lexpat -lbz2`, which do not exist on Windows, so regenerating a pack
  meant dropping into WSL. `.osm.pbf` is read natively here: a hand-rolled
  protobuf decoder (`src/proto.rs`, mirroring the encoder in
  `maps/src/main/rust/src/mvt.rs`) over a pure-Rust inflate.
- **Offline builds:** the crate is detached from the repo-root Cargo workspace
  (the empty `[workspace]` in `Cargo.toml`), like `gtfs_ingest`, so `cargo build`
  never pulls the aarch64-android toolchain or the app crates' lints. The single
  dependency, `miniz_oxide`, is a **decode-only** inflate crate — we never
  compress — following the `ruzstd` precedent in
  `networklocation/src/main/rust/Cargo.toml`. It is already in the local registry
  cache because the app's maps crate pulls it via `flate2`.
- **Deterministic**, which the C++ was not. See below.

## Usage

```sh
# Routing graph -> map_data/ (the directory publish_r2.sh uploads to R2)
cargo run --release --bin road_graph -- california-latest.osm.pbf --out map_data

# POI layer side files + the geojsonseq the tiler consumes
cargo run --release --bin poi_extract -- california-latest.osm.pbf \
    --geojson pois.geojsonseq --names poi_names.bin --index poi_index.bin \
    --attrs poi_attrs.bin \
    --spatial poi_spatial.bin \
    --name-index poi_name_index.bin

# One baked vector layer, with the bbox clip done inline (no osmium extract)
cargo run --release --bin osm_extract -- california-latest.osm.pbf \
    --layer safety --out safety.geojsonseq --bbox -122.6,37.2,-121.7,37.9
```

`road_graph` takes two flags beyond `--out`, both about build-time memory rather
than output:

```sh
# Chains confined to one OSM way: no segment array, no incidence index.
cargo run --release --bin road_graph -- planet.osm.pbf --within-way-chains

# Write the pack in 3 source-partitioned rounds instead of one.
cargo run --release --bin road_graph -- planet.osm.pbf --rounds 3

# Keep the chain spill off the output disk. `chains.pts` is the only file the
# build reads randomly, so it wants cheap seeks; the output only needs sequential
# writes and can go wherever there is room.
cargo run --release --bin road_graph -- planet.osm.pbf --spill-dir /fast/scratch
```

Neither changes the on-disk contract, and `--rounds` does not change the output
at all — a test asserts every byte is identical across `--rounds 1`, `4` and
`17`. See [Build-time memory](#build-time-memory).

From Windows, `scripts/maps/build_graph.ps1` builds the full `.mamaps` archive
end to end (road graph, transit routes, tiling, header checks) in one command.

## `osm_extract` and the vector layers

One 3-pass driver ([`src/extract.rs`](src/extract.rs)) for every baked layer,
with each layer's schema in its own module. Supported today: `safety`
([`src/safety.rs`](src/safety.rs)).

Three pieces are shared:

* [`src/geojson.rs`](src/geojson.rs) — the writer. Reproduces
  `json.dumps(..., separators=(",", ":"), ensure_ascii=False)`: no whitespace,
  fixed key order, UTF-8 passthrough.
* [`src/bbox.rs`](src/bbox.rs) — the `osmium extract -b` equivalent, reproducing
  osmium's `complete_ways` strategy (any vertex inside keeps the whole element).
  Containment is compared in 1e-7 **integers**, not degrees, because
  `-1_226_000_000 * 1e-7` lands a hair west of `-122.6` and would drop a node
  sitting exactly on its own box edge.
* [`src/select.rs`](src/select.rs) — the `osmium tags-filter` equivalent, as a
  cheap pre-filter. Tag lookup is a linear scan, so screening on three keys
  before the classifier reads ten is worth doing at planet scale.

### Pass ordering

A PBF stores nodes before the ways that reference them, so any layer needing way
or relation geometry has to traverse **relations, then ways, then nodes** —
relations decide which ways matter, ways decide which node coordinates matter,
nodes supply them. Only the passes a layer needs are run; `safety` is node-based,
so it runs one.

When the way and relation layers land, their node coordinates must be stored the
way `poi_build.rs` does it — a sorted `Vec` of needed ids plus an index-aligned
coordinate array, looked up by `binary_search`, 16 bytes per *needed* node — and
**not** the way `graph_build.rs` does it. That module's bitset is sized from
`BITSET_SIZE = 20e9`, i.e. 2.5 GB keyed by raw node id, and peaks around 10 GB on
California.

### The port rules, pinned by tests in `src/safety.rs`

The Python normalisers (`normalize_*.py`) were the contract of record during the
port and have since been deleted with the legacy `--engine legacy` scripts and
the differential harness. What remains are the two rules easiest to break,
pinned by tests in `src/safety.rs`:

* `enforcement=maxspeed` is checked **before** the `man_made=surveillance`
  branch. A camera tagged both ways is a speed camera, and swapping the order
  silently reclassifies every enforcement camera.
* The ALPR heuristic is **asymmetric**: `surveillance:type` matches by substring
  (real values include `ALPR;camera`), `camera:type` matches by exact equality
  (it is an enumerated field, and a substring rule would corrupt it), and
  `operator`/`manufacturer` match `flock` by substring.

## Output contracts

Every layout below is read by code elsewhere in the repo. Changing one without
changing its reader produces a graph that loads as garbage, which is why
`metadata.bin` carries a magic and version.

| File | Layout | Reader |
|---|---|---|
| `metadata.bin` | `u32 magic "MARG"`, `u32 version 5`, `u64 node_count`, `u64 edge_count`, `u64 escape_count` (32 B) | `graph.rs` `GRAPH_MAGIC`/`GRAPH_VERSION` |
| `nodes.bin` | `NodeRec[node_count + 1]`, **12 bytes**: `i32 lat_e7, i32 lon_e7, u32 edge_ptr`; Morton-sorted, trailing sentinel whose `edge_ptr == edge_count` | `graph.rs` — widened into `NodeMaster` by `node()` |
| `edges.bin` | `EdgeRec[edge_count]`, **11 bytes**: `i16 target_delta, u24 dist_mm, u8 type, u8 speed_limit, u32 name_offset`; then padding to an 8-byte boundary, `u32 escape_first[E.div_ceil(1024) + 1]`, and `(u32 edge_idx, u32 target, u32 dist_mm) × escape_count` ascending by `edge_idx` | `graph.rs` — widened into `Edge` by `edge(source, idx)`; `name_offset` reached only through `edge_name_offset()` |
| `lanes.bin` | `u32 n`, then `(u32 edge_idx, u32 blob_byte_off) × (n + 1)` ascending by `edge_idx`, then the packed `u16` per-lane turn masks. **Sparse**: only lane-bearing edges appear | `graph.rs` `edge_lane_masks` |
| `intermediate.bin` | the delta-encoded polyline blob at **offset 0** — per storing edge, `i16 dlat, i16 dlon` per *interior* point — then a trailer: `u64 rank[E.div_ceil(512) + 1]`, `u8 present[E.div_ceil(8)]`, `u64 coarse[G.div_ceil(32) + 1]`, `u16 within[G + 1]`, `u64 G`. `off(g) = coarse[g / 32] + within[g]` where `g = rank(k)` | `graph.rs` `intermediate_range` / `decode_edge_coords` — **mandatory**, see below |
| `road_names.bin` | deduped NUL-terminated UTF-8 pool; a name offset is a byte offset | `graph.rs` |
| `poi_index.bin` | 14-byte records: `i32 lat_e7, i32 lon_e7, u32 name_off, u16 type`, Morton-sorted | `PoiIndex.kt` |
| `poi_names.bin` | same pool convention as `road_names.bin` | `PoiIndex.kt` |
| `poi_attrs.bin` | attribute sidecar keyed by `poi_index.bin` record ordinal; layout in `src/poi_attrs.rs` | `PoiIndex.kt` |
| `poi_spatial.bin` | sparse CSR lat/lon grid over record ordinals, so a bbox query is cell-local; layout in `src/poi_side.rs` | `PoiIndex.kt` |
| `poi_name_index.bin` | one `(record, word)` entry per word of every name, sorted by word; layout in `src/poi_side.rs` | `PoiIndex.kt` |
| `poi_region.bin` | one tagged OSM boundary id per record ordinal (label link, else smallest containing admin); layout in `src/poi_side.rs` | `PoiIndexRegion.kt` |
| `poi_admin.bin` | the hierarchy behind those ids: per linked boundary and ancestor, `name`, `name:en`, `admin_level` and parent, sorted by tagged id; written beside `poi_region.bin`, layout in `src/poi_admin.rs` | `PoiIndexAdmin.kt` (archive kind 16) |

`GRAPH_VERSION` is **5**. Version 1 stored a 16-byte node record and a dense
`u64` offset per edge in both blob files; version 2 fixed that, and version 3 gave
`intermediate.bin` a presence bitmap and dropped both endpoints of every stored
polyline. See [Getting a planet under 40 GB](#getting-a-planet-under-40-gb) and
[Getting it under 30](#getting-it-under-30) for what each cost and what replaced
it. There is no dual-path reader — an older pack is rejected — so the app and the
pack have to be updated together, as they already did across the v0→v1 boundary.

Versions 4 and 5 are the next step down, to ~25.8 GB; see
[Getting it under 25](#getting-it-under-25-what-the-census-says). Version 4 changes
**no layout at all**. It adds `edge_count` to `metadata.bin`, so that the reader
stops deriving it from `edges.bin`'s file size, and with it
[exact length validation](#every-files-length-is-checked) of every other file.
That has to land before any record narrows: the moment the stride changes, a
derived `edge_count` changes silently — and it is what sizes `intermediate.bin`'s
trailer, so the mis-parse would be of a *different file*.

Version 5 is the narrowing itself.

### Every file's length is checked

Every mandatory file's length is now an exact function of the two counts in
`metadata.bin`, and `Graph::load` checks each one:

| File | Expected length |
|---|---|
| `metadata.bin` | ≥ 32 B, with the right magic and version |
| `nodes.bin` | exactly `(node_count + 1) × 12` |
| `edges.bin` | exactly `align_up(edge_count × 11, 8) + (E.div_ceil(1024) + 1) × 4 + escape_count × 12`, with the block index's last entry equal to `escape_count` and every row's `edge_idx < edge_count` |
| `lanes.bin` | exactly `4 + (n + 1) × 8 + blob`, from its own header, plus every `edge_idx < edge_count` |
| `intermediate.bin` | trailer sized from `edge_count` and `G`, with `coarse[G/32] + within[G] == ` the blob length |

Exactly, not "at least": a file that is too long is as much a vintage mismatch as
one that is too short, and one comparison catches both. `nodes.bin`'s length used
not to be checked at all — `node()` documents that callers guarantee
`id <= node_count` and does no bounds test — so a stale or truncated file read
straight past the mapping. `road_names.bin` is the one exception: it is a byte pool
with no count of its own, and `road_name()` bounds each read against its size
instead.

Together these catch what a matching version field cannot. The version lives in
`metadata.bin` while the layouts live in `nodes.bin` and `edges.bin`, so a pack
directory assembled from two builds of the *same* version is invisible to it and
obvious to the lengths.

The POI Morton sort key is computed from the *stored* `lat_e7`/`lon_e7`, not from
the pre-rounding `f64` centroid the C++ used, so the order matches the key a
reader recomputes from the file. On California that removed 9 ordering
inversions the old tool produced.

The POI type numbers live in `src/tags.rs` and are also tabulated in
`scripts/maps/README.md`. **Never renumber an existing type — only append.**

## Compaction and `intermediate.bin`

`road_graph` collapses degree-2 chains: a run of nodes that each have exactly two
neighbours becomes a single edge whose polyline lives in `intermediate.bin`. This
is in `src/compact.rs`, and the encoder is `src/geom.rs`.

Before compaction there was one edge per consecutive pair of way nodes, so every
geometry vertex was a routing node. Measured on California:

| | uncompacted | compacted |
|---|---|---|
| nodes | 35,857,253 | 6,416,947 (5.59x fewer) |
| directed edges | 74,340,356 | 16,781,727 (4.43x fewer) |
| `nodes.bin` | 573.7 MB | 102.7 MB |
| `edges.bin` | 1040.8 MB | 234.9 MB |
| `lanes.bin` | 597.1 MB | 135.5 MB |
| `intermediate.bin` | — | 314.1 MB |
| **total** | **2.22 GB** | **0.79 GB (2.8x smaller)** |

Both columns are **v1** layouts. See
[Getting a planet under 40 GB](#getting-a-planet-under-40-gb) and
[Getting it under 30](#getting-it-under-30) for what v2 and v3 do to the
`nodes.bin`, `lanes.bin` and `intermediate.bin` rows.

Those nodes bought nothing: no route ever chose anything at them. What they did
buy was the road's shape, which is why removing them requires storing geometry.

### The file is mandatory

`Graph::load` refuses a pack directory without it. Once an edge is a whole road
between junctions, its polyline is the only record of that road's shape;
`find_nearest_edge` would otherwise project a query onto a straight chord
spanning an entire street, so snapping *and* drawn geometry would be wrong rather
than merely coarse. It is also in `MainActivity.kt`'s download list, and adding
it there makes `InitialDownloader`'s `allFilesPresent` false for any install that
predates it, which re-triggers the download.

### Three encoding rules, all enforced by assertion

All three come from `decode_edge_coords`:

1. **At most 256 points per edge**, endpoints included. The decode loop stops
   there and its callers allocate exactly `[LatLon; 256]`, so a longer polyline
   would be silently truncated. `compact.rs` splits a chain that would exceed the
   budget, promoting the cut node back to a real node (4,983 splits on
   California). The largest legal blob is therefore `4 × 254` = 1016 bytes.
2. **Deltas are `i16`.** Consecutive points must be within 32,767 units
   (~364 m of latitude) on each axis, and the reader uses `wrapping_add`, so an
   overflow would corrupt silently rather than fail. Where a real gap is larger,
   `geom.rs` inserts **collinear** points on the straight line between the pair:
   the geometry is unchanged and each costs 4 bytes.
3. **A chain must begin and end on its own nodes' coordinates.** Only the
   interior is stored, so the reader rebuilds both ends out of `nodes.bin`. This
   was always true by construction — both come from the same coordinate array —
   but the reader now depends on it, so `assert_endpoints` checks it per stored
   edge rather than letting a wrong polyline reach a phone.

An edge whose polyline is just the chord between its own endpoints stores nothing
and is simply absent from the presence bitmap, which is also the escape hatch for
a single hop too long to delta-encode at all.

### `REVERSE_GEOMETRY_FLAG`

Only one edge of a bidirectional pair stores the polyline. The other sets bit
`0x40` in its `type_` byte, and the reader finds the twin by scanning the
*target's* edge range for the first edge pointing back at the source. That halves
the blob, and imposes two obligations on the generator:

* **Set it only when the twin is unambiguous.** With two parallel roads between
  the same pair of nodes, the reader's "first edge back" could resolve to the
  wrong one, so `twin_is_unique` checks there is exactly one, and both polylines
  are stored when there is not.
* **`type_` is a bitfield, not an enum.** Bits 0-5 are the road class, bit 6 is
  this flag. `geometry.rs::ROAD_TYPE_MASK` is the reader's half of that contract;
  it used to be `0x7F`, which let the flag through into `is_mode_allowed`'s range
  checks and so rejected every flagged edge for every travel mode — silently
  deleting half the road network.

### What compaction may not collapse

`compact.rs::classify` requires all of: exactly two incident segments to two
distinct neighbours; agreement on road type, speed limit, name and
one-way-ness; lane masks equal **per traversed direction**, compared by content
rather than by pool offset since two ways intern identical masks at different
offsets; one-way segments actually flowing through the node rather than into it;
and no transit stop code, because isolated-stop reconnection addresses stop nodes
directly.

A ring road whose every node qualifies has no anchor to start from. Its
lowest-numbered node is kept, so the ring becomes one edge from that node to
itself.

A one-way chain is emitted along its traffic flow, not along whichever end the
walk happened to start from.

## Getting a planet under 40 GB

The output format the planet build inherited projected to **50.8 GB**, against a
40 GB target, and two thirds of that was offset tables describing edges that had
nothing to describe. Version 2 is the clean break that fixes it — no dual-path
reader, no downloader change, since nothing had shipped from a v1 pack.

| file | v1 | v2 | basis |
|---|---|---|---|
| `metadata.bin` | 16 B | 16 B | unchanged |
| `nodes.bin` | 6.90 GB | **5.18 GB** | 12 B × (N+1) |
| `edges.bin` | 15.04 GB | 15.04 GB | unchanged |
| `lanes.bin` | 8.60 GB | **0.04 GB** | sparse index |
| `intermediate.bin` | 20.02 GB | **13.86 GB** | two-level offsets |
| `road_names.bin` | 0.25 GB | 0.25 GB | unchanged |
| **total** | **50.8 GB** | **~34.4 GB** | 5.6 GB under target |

Projected from the measured planet counts: 431,474,933 surviving nodes,
1,074,282,569 directed edges, 552,689,139 chains. **Measured on California the same
change is −32.1%** (see [below](#measured-on-california-v1-vs-v2)), against the
−32.3% this table projects, which is the closest thing to a check on it short of
running the planet again.

**`lanes.bin`: a sparse index.** `turn:lanes` is rare — about 2.9 M of a planet's
1.07 G directed edges carry any — so a dense `u64` per edge meant 99.8% of an
8.60 GB file described edges with no lanes at all. Only the lane-bearing edges are
listed now, as `(u32 edge_idx, u32 blob_byte_off)` pairs ascending by edge index,
and `edge_lane_masks` binary-searches instead of indexing. It is called once per
maneuver rather than once per edge, so ~22 probes is free. "This edge has no
lanes" is now spelled by absence from the index rather than by an empty range, and
it resolves to the same `None` the topology-inference fallback in `routing.rs`
already handles.

**`intermediate.bin`: two-level offsets.** A per-edge blob was at most 1028 bytes —
8 for the absolute first point plus 4 per delta, capped at 256 points by the
reader — so six of every eight offset bytes were always zero. v2 replaced the dense
`u64` with a `u64` per 32 edges plus a `u16` within the block, `off(k) =
coarse[k / 32] + within_block[k]`. The block size of 32 is what makes the narrow
half safe: a block spanned at most 32 × 1028 = 32,896 bytes, comfortably inside a
`u16`. That bound is `geom::MAX_POINTS` / `geom::fits` and the chain splitting in
the collapse pass; it is the invariant the whole scheme rests on, so the writer
asserts it and a test pins it at a chain of exactly 256 points. v3 keeps the two
levels but keys them on geometry edges and drops both endpoints, which tightens the
per-blob bound to 1016 bytes — see [Getting it under 30](#getting-it-under-30).

**`nodes.bin`: `edge_ptr` as a `u32`.** 1.07 G edges leave 4× of headroom, so the
record is 12 bytes instead of 16. It is an on-disk `NodeRec` that `node()` widens
into the existing `NodeMaster`, whose `edge_ptr` stays a `u64`, so nothing outside
`graph.rs` sees the change. The generator gained a `cap_u32` guard on the edge
count for it — the same idiom already used for node, survivor and chain counts —
because this is the change that would let a wrapped edge count silently address
another node's edge range.

`edges.bin` is left alone deliberately. Its 14 bytes are `u32 target`,
`u32 dist_mm`, `u32 name_offset`, `u8 type_`, `u8 speed_limit`: `dist_mm` holds a
whole collapsed road so it needs the range, `name_offset` addresses a 250 MB pool,
and `type_` is a bitfield with exactly one spare bit and a history of a masking
error deleting half the road network.

### Stop reconnection is about component *size*, not the largest component

The other thing only a planet could show. `reconnect_isolated_stops` used to find
the largest connected component and hook every stop outside it to its nearest node
inside it. On a region that is right — the largest component is 98.2% of
California, 93.8% of Europe. On a planet it is Eurasia-plus-Africa at **57.7%**,
because continents are not road-connected, so every stop in the Americas,
Australia, Japan, the UK and Indonesia was classified as isolated and the code
tried to bridge an ocean to reach a road in France. It failed **1,173,347** times,
a quarter of all stops, paying for a widening search out to a million-node index
window before each failure.

The question worth asking is *"is this stop attached to a real road network?"*,
not *"is it attached to the biggest one?"*. So the BFS keeps a size per component
instead of discarding all but the largest, a stop needs reconnecting only when its
own component is below `MIN_ROUTABLE_COMPONENT` (1000 nodes), and the target is the
nearest node in **any** component at or above that threshold. A stop alone on an
untagged node is a component of size 1 and is still connected; a stop on a small
island's road network is left alone, because it is already as reachable as that
island gets; a stop in North America connects to North America.

That removes the pathology rather than its symptom: a qualifying node is now
metres away instead of an ocean away, so the widening search almost always
succeeds on its first 1000-node window.

The threshold is capped at the largest component that actually exists, because a
graph with no component of 1000 nodes still has a road network — it is just a
small one — and requiring a size nothing reaches would reconnect nothing. That is
also what keeps the fixtures in `graph_build.rs`, whose whole graph is four nodes,
reconnecting against their own three-node road.

`MIN_ROUTABLE_COMPONENT` wants a measured value rather than a guess, so every
build reports the three counts that make the choice auditable: stops left alone as
already-connected, stops reconnected, and stops that still found nothing. The last
one is the number that decides whether the threshold is right — on a planet it
should collapse from 1,173,347 toward zero.

### Measured on California, v1 vs v2

`california-latest.osm.pbf`, 1.26 GB, both collapse paths, same machine and same
input as the v1 numbers above.

| | reference v1 | reference v2 | within-way v1 | within-way v2 |
|---|---|---|---|---|
| `metadata.bin` | 16 | 16 | 16 | 16 |
| `nodes.bin` | 102,693,792 | **77,020,344** | 113,583,040 | **85,187,280** |
| `edges.bin` | 234,999,884 | 234,999,828 | 252,469,686 | 252,469,630 |
| `lanes.bin` | 135,575,036 | **3,013,792** | 145,815,866 | **3,563,182** |
| `intermediate.bin` | 314,211,280 | **217,693,470** | 318,945,504 | **215,252,596** |
| `road_names.bin` | 5,481,848 | 5,481,848 | 5,481,848 | 5,481,848 |
| **total** | **792,961,856** | **538,209,298** | **836,295,960** | **561,954,552** |
| | | **−32.1%** | | **−32.8%** |

Every byte of that is accounted for, which is what makes it a check rather than a
result:

* `nodes.bin` is exactly `(6,418,361 + 1) × 12` against `× 16`.
* `lanes.bin`'s mask blob is **byte-identical** at 1,289,380 bytes; what went was
  the 134,285,656-byte dense table, replaced by a 1,724,408-byte index over the
  215,550 edges (**1.28%**) that actually carry `turn:lanes`.
* `intermediate.bin` fell by 96,517,810 bytes, which is precisely the dense
  `(E+1) × 8` table minus the new `coarse` + `within_block` pair. The polyline blob
  is untouched.
* `edges.bin` lost 56 bytes = 4 edges × 14, and those four edges are the stop
  change below, not a format change.

**Stop reconnection, both paths identically:** 81,553 stops → **20,836 already on
a routable component, 60,717 reconnected, 0 found nothing**. The v1 rule
reconnected 60,719, so the reconnected count moved by two pairs — which is the
whole of the `edges.bin` delta. California's largest component is 98.24% of
6,418,361 nodes across 76,081 components, so almost nothing sits in a mid-sized
non-largest component and the two rules were always going to agree here. That is
exactly why the rule had to be fixed against a planet instead.

`route_diff` reference-vs-within reports **34,900** baseline vertices missing from
the candidate and a worst-case route delta of **−1.49 km** — both unchanged from
the v1-era measurements below, which is the evidence that the format change is
semantically inert. A true v1-vs-v2 `route_diff` is not possible by construction:
the reader accepts one version, which is the price of the clean break.

**`--rounds 1` vs `3` vs `17` is byte-identical on all six files, on both collapse
paths.** That is the strongest check either new offset scheme gets: round
boundaries move which edges each pass collects, which coarse block it is filling,
which within-block offsets it appends, where `lanes.bin`'s sparse index resumes and
where `nodes.bin`'s running edge counter picks up — and none of it reaches the file.

## Getting it under 30

v2 left the pack at ~34.4 GB against a 40 GB target, and the next question was
whether 25 GB is reachable. Five candidate changes were priced against measured
Europe and planet counts; the three cheapest and least risky are all in
`intermediate.bin`, and version 3 is those three:

| projected saving | change |
|---|---|
| −2.54 GB | drop the absolute first point — it is always the source node |
| −1.55 GB | a presence bitmap instead of a per-edge offset |
| −1.27 GB | drop the last point — it is always the target node |

That takes `intermediate.bin` from 13.86 GB to **~8.50 GB** and the pack to
**~29.0 GB**:

| file | v1 | v2 | v3 | basis |
|---|---|---|---|---|
| `metadata.bin` | 16 B | 16 B | 16 B | unchanged |
| `nodes.bin` | 6.90 GB | 5.18 GB | 5.18 GB | unchanged since v2 |
| `edges.bin` | 15.04 GB | 15.04 GB | 15.04 GB | unchanged |
| `lanes.bin` | 8.60 GB | 0.04 GB | 0.04 GB | unchanged since v2 |
| `intermediate.bin` | 20.02 GB | 13.86 GB | **8.50 GB** | bitmap + dropped endpoints |
| `road_names.bin` | 0.25 GB | 0.25 GB | 0.25 GB | unchanged |
| **total** | **50.8 GB** | **~34.4 GB** | **~29.0 GB** | **−15.7%** on v2, −42.9% on v1 |

Projected from the same measured planet counts as the v2 table — 431,474,933
surviving nodes, 1,074,282,569 directed edges — with `G`, the number of edges that
store a polyline, projected from Europe's measured 110,807,665 of 374,856,290
(29.56%) at **~317.6 M**. Every row of the v3 column is arithmetic on those two
numbers: the blob loses `12 × G` = 3.81 GB, and the tables go from
`coarse` 0.27 GB + `within` 2.15 GB = **2.42 GB** to `rank` 17 MB + `present`
134 MB + `coarse` 79 MB + `within` 635 MB = **0.87 GB**.

The two dropped endpoints are pure redundancy removal: the test
`intermediate_bin_reproduces_the_collapsed_geometry` already asserted that a stored
polyline starts at its edge's source and ends at its target, so both were already
known to be recoverable from `nodes.bin`. The bitmap follows from the 70.4% of
edges that store no polyline at all, each of which still owned a `u16` and a
repeated coarse `u64` under v2.

Two further changes were priced and **rejected for now**, and they are what the
remaining 3.5 GB to a 25.5 GB pack would have to come from: `dist_mm → u24`
centimetres (−1.07 GB) needs an escape hatch for chains over 167 km and moves every
route cost, and delta-coded `nodes.bin` coordinates (−2.4 GB) put a decode step on
the router's hottest read and must handle Morton quadrant boundaries. Neither is
worth it for a pack already 11 GB under the original target.

**The California measurement checks the arithmetic, not the ratio.** Feeding
California's own counts — E = 16,785,702, G = 4,494,428 — through the same formula
predicts a 79,227,999-byte fall, and the file fell by exactly that (see
[below](#measured-on-california-v2-vs-v3)). The *percentage* differs from the
planet's projected −38.7%, at −36.4%, because California stores a polyline on only
26.8% of its edges against Europe's 29.6% and its mean polyline is shorter: a lower
`G / E` means less endpoint saving per byte of blob. That is a property of the
region, not a disagreement with the model.

**The layout, and why the tables come last.**

```text
[ blob ]
[ u64 rank[E.div_ceil(512) + 1] ]   set bits before each 64-byte block, total appended
[ u8  present[E.div_ceil(8)] ]      one bit per directed edge
[ u64 coarse[G.div_ceil(32) + 1] ]  G = geometry edges = rank[last]
[ u16 within[G + 1] ]
[ u64 G ]                           fixed-size trailer
```

`G` is not known until the final round has run, so a reserved prefix cannot be
sized — the same problem `lanes.bin` has. `LaneFile` solves it by streaming its
blob to scratch and copying it back, which is free for 13 MB but would be ~23 GB
of extra I/O for this blob. A trailer instead makes the whole file **append-only
with no scratch file at all**, which is strictly less I/O than v2, where
`BlobFile` copied a 2.4 GB prefix back. The four tables are held in memory while
the blob streams: 0.87 GB at planet scale, against v2's 2.42 GB *on disk*.

The reader recovers everything from the last 8 bytes plus `edges.bin`'s file size,
and validates more than v2 did: the file must hold all four tables plus the
trailer, the `coarse`/`within` sentinel must equal the remaining blob region
exactly, and `rank`'s total must equal `G`.

**The offsets are keyed on geometry edges, not edges.** `off(g) = coarse[g / 32] +
within[g]` where `g = rank(k)`, so an edge's polyline is found by testing its
presence bit and then ranking below it — one `u64` load plus at most 64 bytes of
`popcount`. This *tightens* the `u16` bound rather than loosening it: a per-edge
blob is now at most `4 × (256 − 2)` = 1016 bytes, a block of 32 spans at most
32,512, and every entry in a block is a real blob where before most were
zero-length.

**The encoding.** A stored polyline is `Q0 .. Q(m-1)` with `Q0` the source node's
coordinates and `Q(m-1)` the target's. Only the interior deltas are stored: `m − 2`
pairs of `i16`, omitting both the 8-byte absolute `Q0` and the final delta that
lands on `Q(m-1)`. So bytes = `4 × (m − 2)`, the saving is exactly 12 per stored
edge, and `m = byte_len / 4 + 2`. An empty blob decodes to `[source, target]` — the
chord — which is why "no interior" and "the chord" are now the same statement, and
why presence needs a bit rather than a length. Interpolation is unaffected:
`geom::interp_steps` still decides how many steps a hop takes and only the *final*
step is the one omitted.

**The reader API.** `decode_edge_coords` now needs the edge's endpoints, so
`Graph::get_edge_coordinates_from(source, edge_idx, out)` is the real entry point
and `get_edge_coordinates(edge_idx, out)` is a wrapper that recovers the source via
`find_node_idx_for_edge`. All six `routing.rs` call sites pass the source they
already hold; that is not polish, because otherwise the wrapper's ~29-probe binary
search lands inside `find_nearest_edge`'s candidate loop, which runs over a
1601-node window twice per route and up to 16 times per transit plan. As a bonus it
*removes* the `find_node_idx_for_edge` call the reverse-geometry path used to make
per reversed edge, purely to recover a value the caller already had.

### Measured on California, v2 vs v3

`california-latest.osm.pbf`, 1.26 GB, both collapse paths, same machine and input
as the tables above. Only `intermediate.bin` moves.

| | reference v2 | reference v3 | within-way v2 | within-way v3 |
|---|---|---|---|---|
| `metadata.bin` | 16 | 16 | 16 | 16 |
| `nodes.bin` | 77,020,344 | 77,020,344 | 85,187,280 | 85,187,280 |
| `edges.bin` | 234,999,828 | 234,999,828 | 252,469,630 | 252,469,630 |
| `lanes.bin` | 3,013,792 | 3,013,792 | 3,563,182 | 3,563,182 |
| `intermediate.bin` | 217,693,470 | **138,465,471** | 215,252,596 | **134,529,908** |
| `road_names.bin` | 5,481,848 | 5,481,848 | 5,481,848 | 5,481,848 |
| **total** | **538,209,298** | **458,981,299** | **561,954,552** | **481,231,864** |
| | | **−14.7%** | | **−14.4%** |

Against v1 that is **−42.1%** and **−42.5%**.

Every byte of the `intermediate.bin` delta is accounted for, which is what makes it
a check rather than a result. The reference path has E = 16,785,702 directed edges
and G = 4,494,428 stored polylines:

* the v2 file was a 179,925,624-byte blob behind 37,767,846 bytes of tables
  (`coarse` 4,196,440 + `within` 33,571,406);
* dropping both endpoints takes 12 bytes off each of the 4,494,428 stored
  polylines, i.e. **−53,933,136**, leaving a 125,992,488-byte blob;
* the trailer is 12,472,983 bytes — `rank` 262,288 + `present` 2,098,213 +
  `coarse` 1,123,616 + `within` 8,988,858 + 8 — so the tables cost **−25,294,863**;
* 125,992,488 + 12,472,983 = **138,465,471**, exactly the file on disk.

So the presence bitmap plus the narrower offset tables are 3.0× smaller than v2's
two-level pair, and the blob is 30% smaller for holding nothing but interiors.

**`route_diff` is unchanged from the v2 baseline, to the digit.** `compare` reports
the same **999** same-snap-same-length, **966** alternative optimum, **8** snapped
elsewhere, and the same worst deltas of **−1,493,486 mm** and **−20,893** 10 ms
units. `geometry` reports the same **34,900** baseline vertices missing forward and
**29,703** reversed, and the same ±98,897,640 mm length delta, so the vertex
difference is still symmetric. That is the point of keeping `count == 0` for an
absent polyline in this change: every one of those numbers describes the *reference
vs within-way* difference, and none of them may move for a format change. They did
not.

`geometry / reversed` also matches: 4,494,428 / 3,669,235 on the reference path and
4,377,764 / 3,595,577 on within-way, and 8,163,663 of 16,785,702 edges resolve a
polyline either way.

**`--rounds 1` vs `3` vs `17` is byte-identical on all six files, on both collapse
paths.** That is the strongest check the presence bitmap gets, because round
boundaries move which edges each pass collects, which presence bits each pass sets,
which coarse block it is filling and which within-block offsets it appends — and
none of it reaches the file.

Europe and planet runs are out of scope for this change; the ~29 GB figure above
stays a projection.

**A note the earlier revision of this file got wrong.** The p99 snap offset of
2.2 km recorded below was once attributed to `find_nearest_edge` skipping edges
with no resolvable geometry. It does not skip them: it falls through to
"No geometry: snap to the straight node-to-node segment" and projects onto the
chord itself, as do the other six `get_edge_coordinates` call sites. So chord
recovery was never the missing piece, and nothing here changes snapping coverage.

## Getting it under 25: what the census says

v3 projects a planet pack at ~29.0 GB. The next question was whether 25 GB is
reachable, and the answer had to come from measurement rather than from guessing
which field looked wasteful — so `road_graph` gained a `--stats` mode that reports
what a narrower record would cost, computed by **the encoder's own escape
predicate** rather than by an analysis script that could disagree with it by one.

```
road_graph california-latest.osm.pbf --out DIR --stats
```

Where it ended up, on the planet basis of E = 1,074,282,569 directed edges and
N = 431,474,933 nodes:

| version | change | `edges.bin` | `nodes.bin` | pack |
|---|---|---|---|---|
| 3 | where this started | 15.04 GB | 5.18 GB | 29.0 GB |
| 4 | `edge_count` in metadata, length validation, no layout change | 15.04 GB | 5.18 GB | 29.0 GB |
| 5 | `i16` target delta + `u24 dist_mm`, one merged escape table | **11.86 GB** | 5.18 GB | **25.8 GB** |
| 6 | `name_offset` sparse, 7-byte record | **9.33 GB** | 5.18 GB | **23.3 GB** |
| ~~7~~ | ~~fused node block header~~ — correct, 10% slower, reverted | 9.33 GB | ~~3.99 GB~~ | ~~22.1 GB~~ |

`intermediate.bin` (8.50 GB), `lanes.bin` (0.04 GB) and `road_names.bin` (0.25 GB) are
untouched throughout. Every step is **exact** — no quantisation anywhere — so the pass
condition at each one is byte-identical routing output, not "within tolerance".

Measured on California, reference path: 458,981,307 bytes → **369,839,208**, a 19.4%
reduction with the per-element dump identical at every step.

### The measurement spike came first

Nothing shipped until `--stats` had run, because the whole design rested on numbers
nobody had. Measured on California, reference path (E = 16,785,702, N = 6,418,361):

| | |
|---|---|
| `edges.bin` share of the pack | **52%** |
| `\|target − source\|` fits an `i16` | 99.699% (**50,591** escapes) |
| `dist_mm` at or above `0xFFFFFF` (16.78 km) | **166** edges, 0.001% |
| named edges | 37.72% — so 62.28% of `name_offset` is a sentinel |
| `speed_limit` zero | 91.76% |
| max out-degree | **24** |
| degree-0 nodes | **1,480** (0.023%) |
| name pool | 5,481,848 B in 323,443 names |

Three of `edges.bin`'s five fields are therefore mostly redundant, and `dist_mm`
should be a `u24` in **millimetres** rather than centimetres: only 0.001% of edges
exceed the ceiling, so the escape table is ~85 KB planet-wide and the field stays
*exact* rather than quantising 1.07 G edges to save a table.

### `c` is the number that transfers, not the escape rate

An escape rate measured on one extract says nothing about a planet 22× larger. What
should transfer is the constant in

```
P(|target − source| > n) ≈ c · n^(−1/2)
```

because a level-*k* quadrant boundary produces a delta of order `N / 4^k` and is
crossed with probability proportional to `2^k`; substituting `n = N / 4^k` gives
`P = (L·√N / W) / √n`, and `L·√N / W` is the ratio of edge length to node spacing —
a local, physical quantity that does not depend on `N`. `--stats` prints `c` at every
power of two so the shape of the curve is visible, not just one point on it.

**Measured on both California and Europe, and it holds.** Europe is 20.9× the edge
count and 21.6× the node count:

| | California | Europe | ratio |
|---|---|---|---|
| directed edges | 16,785,702 | 350,290,432 | 20.9× |
| nodes | 6,418,361 | 138,635,441 | 21.6× |
| `i16` escape rate | 0.3014% | 0.3132% | **1.04×** |
| `c` at n = 32,767 | 0.5456 | 0.5670 | **1.04×** |
| `u24 dist_mm` escape rate | 0.000989% | 0.000892% | 0.90× |
| named edges | 37.72% | 31.15% | 0.83× |
| max out-degree | 24 | **77** | 3.2× |
| degree-0 nodes | 0.0231% | 0.0270% | 1.17× |
| max `dist_mm` | 61.5 km | **2,979 km** | 48× |

`c` moves by 4% across a 21× change in scale, well inside the 1.5× the design needed,
so the planet escape rate is predictable and the escape table is ~40 MB rather than
anything that matters. Two other columns are worth more than they look:

* **The named fraction *falls* with scale** (37.7% → 31.1%), so making `name_offset`
  sparse saves more on a planet than California suggests, not less.
* **Max out-degree triples** (24 → 77). Any future per-node `u8 degree` field would
  still fit, but not by the margin California implied — which is why one is not
  worth adding without a writer pre-pass that widens the field if it overflows.

### The single most useful thing the census found

Europe has an edge **2,979 km long**. California's longest is 61.5 km.

That number kills the option v3 considered and this work rejected: `dist_mm` as a
`u24` counting **centimetres**, which needs no escape table because 2^24 cm is
167.77 km. It would have been silently, catastrophically wrong on Europe — the value
wraps — and it quantises all 1.07 G edges to buy that. Millimetres plus a shared
escape table keeps every distance *exact* and costs 3,126 rows on Europe: 37 KB.

It also vindicates merging the two escape tables. The `dist_mm` escape rate is the
least transferable number in the whole census, and merging makes it irrelevant to
the design: a hundredfold miss rides along in the table `target` already needed.

### Hilbert was tried, and loses

Z-order's known flaw is the discontinuity at every quadrant boundary, which is
exactly the mechanism above, so Hilbert — which has no long-range jumps at all —
looked like a free win. It is not. Both curves, same extract, same predicate:

| `\|target − source\|` > | Morton | Hilbert |
|---|---|---|
| 1,024 | 2.2254% | 2.1553% |
| 16,384 | 0.4711% | 0.4648% |
| **32,767** | **0.30139%** | **0.30756%** |
| 262,144 | 0.0713% | 0.0892% |

Hilbert wins below ~16 k and loses above it: it *concentrates* the distribution
rather than shortening it, trading mid-range deltas for far ones, and the crossover
falls almost exactly on the `i16` boundary. So the quadrant discontinuities are not
what generates the 0.3% tail — mapping a 2-D neighbourhood onto a 1-D index costs
about that much however it is done — and the escape table is unavoidable either way.
Morton stays, and `spatial.rs` now pins the discontinuity in a test so the next
person reads the measurement instead of repeating it.

### Delta-coded coordinates stay rejected

The other candidate v3 named. Longitude deltas along the Morton order fit an `i16`
only 91.4% of the time, and `find_nearest_edge` binary-searches the coordinate array
by spatial key, so it needs O(1) random access — which delta coding destroys.

### Version 4 is the de-risking step

No byte of any data file moves. What lands is the machinery every later phase needs:
`edge_count` in `metadata.bin`, [exact length validation](#every-files-length-is-checked)
of every file, compile-time record-size assertions, `Graph::edge_range` as the only
way to ask for a node's edge range, the `EdgeRec`/`Edge` widening that keeps a
layout change inside `graph.rs`, and `route_diff dump` — a per-element equivalence
dump over **all** E edges and N+1 node records, which is what later phases are
verified against instead of the ~35 K vertices 999 sampled routes happen to touch.

`route_diff` also gained a `bench` mode, so the phases that are gated on latency
rather than correctness have a baseline to be gated against. It reports routes/sec,
per-route p50/p90/p99 and peak RSS, separating the first pass from the warm ones, and
`--cold` evicts the pack from the page cache first with `posix_fadvise` — which,
unlike `/proc/sys/vm/drop_caches`, needs no root. Every pass also reports a checksum
over what it routed, so a format change that broke routing shows up as a broken
checksum rather than as a speedup.

Version 4's own gate: every data file byte-identical to the v3 pack on both collapse
paths, `metadata.bin` 16 B → 24 B, and `route_diff report` byte-identical to what
the *v3 binary* produced from the *v3 pack* — a direct reader-equivalence check
across the change rather than a comparison against itself.

### Version 5: the 11-byte record

```text
[ EdgeRec[E] ]                     11 B: i16 target_delta, u24 dist_mm,
                                         u8 type_, u8 speed_limit, u32 name_offset
[ pad to an 8-byte boundary ]
[ u32 escape_first[E.div_ceil(1024) + 1] ]
[ { u32 edge_idx, u32 target, u32 dist_mm } × escape_count ]   ascending by edge_idx
```

`target` becomes a signed delta from the edge's own source and `dist_mm` a `u24`,
so 14 bytes become 11 — **3.22 GB** of a planet, less the escape table. Every value
stays exact.

**One escape table, not two.** `target_delta == i16::MIN` *or* `dist_mm == 0xFFFFFF`
means both fields come from the escape row. Two sentinels with two tables would cost
two branches in the A* relaxation, two metadata fields and two sets of off-by-one
risk, to keep a 39 MB table apart from an 85 KB one. Merging costs ~13 MB and
removes half the code — and it makes the `dist_mm` escape rate, the least
transferable number in the census, irrelevant to the design: collapsed chains in
Siberia will be far longer than California's, and a hundredfold miss just rides
along in the same table.

**A block index, not a binary search.** The inline sentinel already *is* the
presence bit — it is in a register that was just loaded — so a v3-style presence
bitmap would spend 134 MB duplicating it. All that is missing is edge index → row,
and `u32 escape_first[E/1024 + 1]` is 4.2 MB: one load from a largely
cache-resident array, then a scan of the ~3 rows a block holds, usually one cache
line. A binary search over 3.24 M rows would be 12–15 misses. `lanes.bin` does
binary-search its sparse index and its doc comment justifies that by being "called
once per maneuver rather than per edge"; `target` is read on every relaxation, so
that precedent does not transfer.

**Field order is load-bearing.** The four fields the A* relaxation reads sit in
bytes 0..7, so one 8-byte load covers all of them, and `name_offset` is last. The
`u24` is assembled from three bytes rather than loaded as a masked `u32`, so no read
can run past the array — and the array is padded to 8 bytes anyway, which keeps the
block index naturally aligned whatever `E` is.

Shrinking the record is a locality *win*, not a cost: adjacency records per 64-byte
line goes 4.6 → 5.8, so the relaxation should get slightly faster rather than
slower.

**Measured on California:** `edges.bin` 234,999,828 → 185,316,836 bytes on the
reference path (−21.1%), with 50,711 escape rows (0.302% of 16,785,702 edges); the
whole pack 458,981,307 → 409,298,323 (−10.8%). The per-element dump is identical —
**0 of 16,785,702 edges and 0 of 6,418,362 node records differ**, and the same on the
within-way path's 18,033,545 edges — `report` is still byte-identical to the v3
binary's, and `compare` and `geometry` do not move a digit.

| file | v3/v4 | v5 | basis |
|---|---|---|---|
| `metadata.bin` | 24 B | 32 B | `escape_count` |
| `edges.bin` | 15.04 GB | **11.86 GB** | 11 B × E + 4.2 MB index + 40 MB rows |
| everything else | | unchanged | |
| **planet total** | **~29.0 GB** | **~25.8 GB** | |

**Seven synthetic tests cover what California cannot contain**: deltas at 0, ±1,
±32766, ±32767, ±32768, −32769 and +40000; `dist_mm` at 0, 1, `0xFFFFFE`,
`0xFFFFFF`, `0x1000000` and `u32::MAX`; an edge that fails both tests at once
(one row, not two); escapes at edge 0 and edge E−1; a pack with no escapes at all,
where the padding is the only thing keeping a wide load in bounds; and escape rows
on both sides of every block boundary at 1023/1024/1025/2047/2048/2049. The
encoder and the decoder are exercised as a pair, directly, because putting two nodes
exactly 32,768 apart in Morton order would mean choosing coordinates for the 32,767
nodes in between.

**`--rounds 1` vs `3` vs `17` stays byte-identical on all six files, on both collapse
paths.** That is the strongest check the escape table gets: round boundaries move
which edges each pass collects, which of them escape, and where the block index's
prefix counts land — and none of it reaches the file.

### Version 6: `name_offset` becomes sparse

```text
[ EdgeRec[E] ]                     7 B: i16 target_delta, u24 dist_mm,
                                        u8 type_, u8 speed_limit
[ pad ][ u32 escape_first[..] ][ EscapeRow[escape_count] ][ pad ]
[ u64 name rank[E.div_ceil(512) + 1] ][ u8 name present[E.div_ceil(8)] ]
[ u32 name_off[named_edges] ]
```

Two thirds of edges have no name — **62.28%** of California's, **68.85%** of
Europe's — so a dense `u32` per edge spent four bytes on a sentinel more often than
on an offset. A presence bitmap plus a rank index makes absence free and costs the
named third four bytes each: **−2.53 GB** of a planet.

**It is a presence bitmap over the existing byte offset, not a dense name id.**
Adding an id space would get a further 0.41 GB, but it means changing
`NamePool::intern`'s return type, and `poi_build.rs` is a second consumer that has to
keep byte offsets for `PoiIndex.kt`. Recoverable later without touching
`poi_build.rs` if that 0.41 GB is ever wanted.

**The rank/select pair is now one type, used twice.** `intermediate.bin` has had a
presence bitmap and rank index since v3, and this is the same question asked of a
different field, so `EdgeBitmap` in the reader and `PresenceBitmap` in the writer
replace what would have been two transcriptions of the same eight lines. The block
size stays 64 bytes — 512 edges, one cache line — so a rank is one `u64` load plus at
most eight `popcount`s.

**Why this and not sparse `speed_limit`.** `speed_limit` is zero on 91.76% of edges
and making it sparse would save 0.84 GB, but it is read in the A* relaxation, up to
25 M expansions × degree per route, from a record already in L1. A rank probe there
is not worth 0.84 GB. Names are read when an *instruction* is emitted — once per
maneuver, not once per edge — which is what makes three loads instead of one
affordable.

**The prerequisite was deleting a dead store.** The A* relaxation used to copy
`name_offset` into the scratchpad on every improving edge, into an `Entry` field
nothing ever read; the same was true of `last_type`. Until those went, `name_offset`
was on the hot path and no sparse form was affordable. Removing them also took
`Entry` from 24 bytes to 20, in the structure the router touches most.

**Measured on California:** `edges.bin` 185,316,836 → 145,857,713 bytes on the
reference path, so the whole pack is 458,981,307 → 369,839,208 — **−19.4% against
v3**. 6,330,795 of 16,785,702 edges are named (37.72%). The per-element dump is
identical again — **0 of 16,785,702 edges and 0 of 6,418,362 node records differ,
including `name_offset` for every edge**, which is the phase where that dump earns
its keep.

The 145,857,713 breaks down exactly as the reader computes it: 117,499,920 of padded
records, 65,576 of escape block index, 608,532 of escape rows, 4 of padding,
2,360,501 of name bitmap and rank, and 25,323,180 of name offsets.

| file | v3 | v5 | v6 | basis |
|---|---|---|---|---|
| `metadata.bin` | 16 B | 32 B | 40 B | `named_edges` |
| `edges.bin` | 15.04 GB | 11.86 GB | **9.33 GB** | 7 B × E + 0.15 GB bitmap + 1.62 GB offsets |
| everything else | | | unchanged | |
| **planet total** | **~29.0 GB** | **~25.8 GB** | **~23.3 GB** | |

That planet column uses California's 37.72% named fraction. At Europe's 31.15% the
offsets are 1.34 GB rather than 1.62 GB, so the real figure should come in nearer
**23.0 GB**.

**Four synthetic tests cover the sparse table's boundaries**: a name at edge 0 and at
edge E−1; names either side of the 512-edge rank-block boundary and either side of
1024, with a distinct offset per named edge so a rank off by one reads a visibly
wrong value; a pack where no edge is named; and one where every edge is.

### Version 7 was built, measured and abandoned

The remaining idea was to split `nodes.bin` into coordinates plus a fused per-block
edge-extent header:

```text
[ { i32 lat_e7, i32 lon_e7 } x (N + 1) ]                          8 B
[ { u32 edge_base, u8 degree[16] } x (N + 1).div_ceil(16) ]      20 B per 16 nodes
```

9.25 bytes a node instead of 12, so **−1.19 GB** and a planet at ~22.1 GB. Because
`degree[v] == edge_ptr(v + 1) − edge_ptr(v)`, `edge_range(v)` needs only `v`'s own
block: no `v + 1` lookup, no block-boundary case, and one cache miss where the dense
layout needed two.

It was implemented in full — degree-width pre-pass, `u8`/`u16` widths, sentinel
integrity check, all of it — and it is **correct**: the per-element dump is identical
across all 16,785,702 edges and 6,418,362 node records, `report` stays byte-identical
to the v3 binary's, and `nodes.bin` came out at 59,369,856 bytes against v6's
77,020,344 (−22.9%), the whole pack at 352,188,721 (−23.3% against v3).

It is also **slower**, and the phase was gated on that:

| | v6 | v7 | | gate |
|---|---|---|---|---|
| warm routes/s | 20.2 | 18.1 | −10.4% | ≥97% ✗ |
| cold routes/s | 19.2 | 17.7 | −7.8% | ≥97% ✗ |
| warm p50 | 0.953 ms | 1.043 ms | +9.4% | ✗ |
| cold p99 | 651.2 ms | 710.8 ms | +9.2% | ≥97% ✗ |
| peak RSS | 484 MB | 466 MB | −3.7% | ✓ |

Three times over the 3% tolerance, so it is reverted.

**The interesting part is why, because the design's cost model was wrong.** The
reasoning was about *cache misses*: one fused header is one miss where a separate
base array plus a degree array would be two, and the dense layout read two records.
That reasoning is sound and the miss count really did improve. What it missed is that
a within-block prefix sum is a **serial dependency chain of up to fifteen adds**, and
`edge_range` is the router's hottest read — once per A* pop, and 1,600 times per
`find_nearest_edge` window scan. On a pack that fits in RAM, where nothing misses,
that arithmetic is the whole cost and there is no miss saved to pay for it.

Measured on California, which is the *favourable* case: the pack is 370 MB on a 94 GB
box, so even a `posix_fadvise`-evicted run is reading from local disk in seconds. The
case v7 was designed for — a planet-sized pack where each node read is a major fault —
would have to overcome a 10% arithmetic penalty before it broke even, and nothing here
suggests it would.

A variant storing `u16 prefix[16]` instead of degrees would make `edge_range` two byte
loads and no loop, at 10.25 bytes a node rather than 9.25 — about **−0.76 GB** instead
of −1.19 GB, and it reintroduces the block-boundary case for one node in sixteen. That
is the shape worth trying if the last gigabyte is ever wanted. It was not tried here,
because the target was already met at v6 and the measurement above is the answer to
the question that was actually asked.

## Build-time memory

The output has fitted a planet for a while; the build did not. California peaked
at **4.97 GB of RSS** for a 1.26 GB PBF, which scales to roughly 310 GB on an
80 GB planet file — and the structural breakdown agreed, putting the pass-3
working set at about 217 GB. The gap was 2.3x on a 94 GB machine, so this was a
restructure rather than a diet, following the same route `gtfs_ingest` took: not
spill machinery, but a streaming, incrementally-finalised build.

Four changes do the work.

**Rank/select over the bitset that already existed.** `Bitset` marks exactly the
graph's nodes, keyed by raw OSM id. A cumulative popcount per 512-bit block —
sized to the largest id actually seen, not to `BITSET_SIZE` — makes
`dense(osm_id)` an O(1) rank. The node array is then *implicitly ordered by OSM
id*, which deletes the sorted `(osm_id, local_id)` table the edge pass used to
binary-search, deletes the per-node `osm_id` because it is the index, and deletes
the per-node spatial key because only the survivors ever need a Morton sort.
What is left is `coords`, at 8 bytes a node.

This is also where dangling references become dangerous rather than merely
untidy. Pass 1 marks every ref of every routable way, including refs to nodes no
blob in the file defines — an extract clipped at a boundary is full of them — and
under rank addressing each of those owns a slot. A slot that never receives
coordinates reads as `(0, 0)`, which corrupts distances, spatial keys and
polylines while leaving every count plausible. Hence the `present` bitset, set
during the coordinate scatter, and hence the pass order: **coordinates before
anything that reads the node array**.

**Never cache ways; re-read the PBF.** `blob_kinds` already lets a later pass
skip whole blobs. Way blobs are a small minority of a planet file, so re-reading
them is far cheaper than the ~32 GB way-and-ref cache it replaces. The build logs
what fraction of blobs the ways pass actually read, because that number is what
justifies the strategy.

**Never materialise segments** (`--within-way-chains`). Restricting chains to a
single OSM way means every segment in a chain shares its way's type, speed, name,
one-way-ness and lanes *by construction*, so the attribute-agreement half of
`compact.rs::classify` becomes free. All that remains is a global per-node
degree, which is one byte a node. That deletes the segment array **and** the
incidence CSR over it — 114 GB of the 217.

**Spill the chains and write in source-partitioned rounds.** Chains go to
`chains.hdr` (fixed-size records, so the count is the file size over the stride
and any stage can seek to chain *k*) and `chains.pts` (coordinates, not node ids,
so `coords` is freed the moment the spill is written). The edge index, the
component scan and every write round are then sequential scans over those files.

### What within-way chains cost

An accepted quality regression, and a real one: a node where two different ways
meet end-to-end with matching tags is collapsed by `compact.rs` and kept here.
OSM splits long roads constantly, at every tag change and at the 2000-node way
ceiling, so this is not a rare case. `graph_build.rs`'s
`within_way_chains_keep_the_node_where_two_ways_meet` states it as a test rather
than an estimate.

Two things soften it. The 256-point split budget already forces cuts on long
dense roads anyway, and more surviving nodes strictly *improves* snapping — the
p99 snap offset fell from 3.7 km to 2.2 km when compaction landed, for the same
reason in reverse. Nothing about the drawn shape of a road changes: the extra node
was already a vertex in `intermediate.bin`.

Two correctness simplifications come free with it, both of which were bugs that
had to be fixed once already. The walk advances through a finite ref list, so it
cannot loop: there is no per-segment "used" flag and no anchorless-cycle phase, and
a ring way closes on its own first node. And within one way every segment runs
along ref order, so a one-way chain is already oriented along its traffic and
there is no reorientation step to hand the forward and backward lane masks to the
wrong end.

If the regression turns out to be unacceptable at scale, the recovery path needs
no new memory: run `compact.rs`'s logic over the **chain** stream instead of the
segment stream. Chains are a few hundred million records rather than billions of
segments, they already carry every attribute `classify` compares, and a chain is
structurally a `Seg` with a point range.

### Why the writer uses rounds and not computed offsets

Computing each edge's index and writing it into place is sound for `edges.bin` —
fixed 14-byte records, pre-sized file — and wrong for the two blob files. The
reader derives a polyline's length from `off(k + 1) - off(k)` and a lane run's
length from the next index entry, so `intermediate.bin` and `lanes.bin` are forced
into edge-index order with monotonic offsets, and writing a blob at a computed
offset would mean knowing every earlier blob's length first: a per-edge length
array, a prefix sum and a second encode pass.

Partitioning by source instead makes every write an append. Each round takes a
contiguous range of final node ids, scans the spill for the edges whose *source*
falls in that range, sorts that buffer by `(source, target, chain)`, and writes
both blobs, both of `intermediate.bin`'s offset halves, `edges.bin` and `nodes.bin`
forwards. Offsets come out monotonic by construction, the offset halves become
append-only scratch files copied into their reserved prefixes at the end,
`nodes.bin`'s running edge counter works because the rounds ascend, and the
`(source, target)` grouping the reader and `twin_is_unique` depend on is restored
within each round.

`lanes.bin` is the one file that cannot reserve a prefix, because its index is
sparse and `n` is not known until the last round has run. Its blob streams to a
scratch file while the index accumulates in memory — 23 MB at planet scale — and
the header plus index are written in front of the blob at the end.

Each round's edge count is checked against `edge_ptr` *before* anything is
written, which catches both an edge landing in the wrong round and an edge whose
source is not a surviving node.

`--rounds` defaults to 1, so small inputs and the test suite are untouched.

`--spill-dir` puts the spill somewhere other than the output directory. Worth
separating, because `chains.pts` is the only file the build reads *randomly* —
once per edge that stores a polyline — while the output only ever needs
sequential writes. On the first California run the spill sat on a slow mount and
that single random read per edge cost more wall clock than every other stage put
together: 47 minutes at 21% CPU, with 17.8 M voluntary context switches against
16.8 M edges. Two reorderings removed most of those reads outright — a chain of
two points cannot have stored geometry, and an edge that defers to its twin never
needs the polyline — and the flag moves the rest onto a disk whose seeks are
cheap.

### Measured on California

`california-latest.osm.pbf`, 1.26 GB, 23010 data blobs, 35,864,766 marked nodes.
Both paths built from the same input on the same machine, spill on a local disk:

| | reference (`compact.rs`) | `--within-way-chains` |
|---|---|---|
| nodes kept | 6,418,361 | 7,098,939 (+10.6%) |
| directed edges | 16,785,706 | 18,033,549 (+7.4%) |
| chains | 8,687,974 | 9,368,552 (+7.8%) |
| split for the 256-point limit | 4,985 | 4,480 |
| largest component | 98.24% | 98.32% |
| `intermediate.bin` | 314,211,280 B | 318,945,504 B (+1.5%) |
| total road length | 1,950,166 km | −0.005% |
| **peak RSS** | **4.49 GB** | **2.79 GB (−38%)** |
| wall clock | 1:33 | 1:26 |

So the cross-way regression the plan could only guess at is **about a tenth of the
node count**, bought for 38% less peak memory and the deletion of the segment
array and its incidence index. The ways pass read **2145 of 23010 blobs (9.3%)**,
which is the measurement that justifies re-reading them rather than caching.

Both `intermediate.bin` figures are **v1** sizes, measured before the two-level
offset table. The node and edge counts, the split count and the peak RSS are
unaffected by the format change.

Note that ~2.5 GB of either figure is the `BITSET_SIZE` node mask, which is a
fixed cost independent of region size, so neither number scales linearly.

`route_diff geometry` reports a vertex-set difference of ~0.1% in **both**
directions — each build appears to be "missing" vertices the other has, 34,900 one
way and 29,703 the other, with the total length delta exactly ±98,897,640 mm. That
symmetry is the tell: within-way chains keep strictly more nodes, so they cannot
lose a real vertex. What moves is the *interpolated* filler `geom::encode` inserts
when a gap exceeds ~364 m — chopping a polyline at a different node slides that
filler along the same straight line. `route_diff` counts those points as vertices,
so its vertex check has a false-failure mode whenever the chopping changes; the
length tolerance, which is the check that would catch real loss, passes both ways.

Both vertex counts were **re-measured unchanged under v2**, which is the evidence
that the offset-format change is semantically inert: a different way of addressing
the same polyline blob cannot move a vertex, and it did not.

`route_diff compare`, re-run under v2 over the same 2000 pairs: 999 identical,
**966 taking an alternative optimum of a different length**, 8 snapping more than a
metre elsewhere, none gained or lost routability, worst case **−1.49 km** on a long
route. **This is still not explained.** The plausible cause is that more surviving
nodes mean more turn penalties, which moves the optimum. The figure reproduces the
v1-era measurement to the metre, but `routing.rs` is *still* being modified
concurrently, so this is a second measurement against a tree that is still not
quiet rather than the clean re-run that would settle it.

### Measured on Europe

`europe-latest.osm.pbf`, 33.2 GB, 534,883 blobs, 793,460,857 marked nodes. The
continent gate matters because it is the first run where `coords`, `degree`,
`edge_ptr` and `targets` all pass a gigabyte, and where the `u32` ceilings come
within about 2x.

| | reference | `--within-way-chains` | + streamed spill, `--rounds 8` |
|---|---|---|---|
| nodes kept | 138,635,441 | 151,447,545 (+9.2%) | same |
| directed edges | 350,312,326 | 374,856,290 (+7.0%) | same |
| chains | 178,171,772 | 190,983,876 | same |
| `intermediate.bin` | 6.85 GB | 6.99 GB | same |
| **peak RSS** | **61.85 GiB** | **31.81 GiB** | **14.30 GiB** |
| wall clock | 39:11 | 35:17 | 41:15 |

Three things to take from that.

**The reference path is out at planet scale, measured rather than argued.** 61.85 GiB
for a continent, on a 94 GB machine, with a planet 2.65x larger.

**The cross-way regression is stable across two scales**: +10.6% nodes on
California, +9.2% on Europe. It does not grow with the input.

**The last two thirds of the memory win came from two changes the fixtures could
never have shown.** At `--rounds 1` the write buffer held every edge —
374,856,290 x 48 B, 18 GB — and the chain pass accumulated the whole chain set
before writing the spill, another 11.7 GB. Neither is visible on a 9-node fixture.
The rounds writer already existed for the first; the second needed the spill write
moved into the pass sink, which is where it should have been.

All six output files are **byte-identical** between the `--rounds 1` accumulated
build and the `--rounds 8` streamed one, on a 375 M-edge graph. That is the
strongest check the rounds writer gets: round boundaries move which edges each pass
collects, which offsets it appends and where `nodes.bin`'s running counter resumes,
and none of it reaches the file.

The cost of eight rounds is eight sequential passes over a 16 GB spill, which is
the 17% of extra wall clock.

A planet is run at **`--rounds 3`**, not 8. The write buffer is
`edges / rounds × 48 B`, so three rounds move it from 6.4 GB to 17.2 GB and peak
RSS from ~24 GB to roughly 30-35 GB — still comfortable in 94 GB — in exchange for
three passes over the 24 GB header spill instead of eight. The header spill is on
the slower mount, so that should be the larger effect.

### Measuring it

`road_graph` prints its own peak RSS from `/proc/self/status` at the end of every
run, so every build is a measurement rather than something to be caught with
`top`. `VmHWM` is a high-water mark, so it survives the frees that a sample
between two phases would miss; it does not include page cache or dirty pages,
which is a further reason the rounds writer is preferable to computed-offset
writes. Other platforms report nothing rather than a guess.

Also worth watching, in roughly this order: the cross-way regression on a real
extract; the dangling-ref ratio, which the build logs as marked ids with no
coordinates; the fraction of blobs the ways pass skips; and `unique_names` /
`name_bytes`, which decides whether `names.rs` needs `gtfs_ingest`'s `StringPool`
treatment.

Two rules hold everywhere. Every large array must be allocated at its final size:
one `push`-driven doubling on a 12 GB array transiently costs 36 GB. And `u32`
ceilings are only ~2.5x away at planet scale for node, survivor and chain counts,
so each is checked rather than left to truncate silently into a valid-looking
wrong record — as is `NamePool`'s cursor, since a `name_offset` is a `u32` on
disk and a wrapped pool means permanently wrong street names.

## Determinism

Every output is a pure function of the input file. This is a real change: the C++
generator filled its node array with `idx.fetch_add(1)` from concurrent workers
and interned way names in thread-scheduling order, so two runs of the C++ on the
same extract produced different node ordering, different `name_offset` values and
therefore different `road_names.bin`, `edges.bin` and `nodes.bin`.

The mechanism is in `src/par.rs` and `pbf::run_pass`: work is split into fixed
contiguous chunks (of blobs, ways or nodes), chunks are claimed dynamically so a
heavy chunk cannot stall a core, but each chunk's results are stored at its own
index and merged **in index order**. Name interning then happens single-threaded
during that merge. The chunk size is a constant, not a function of the core
count, so the output does not vary between machines either.

Verified on the full California extract: two runs produce byte-identical
`metadata.bin`, `nodes.bin`, `edges.bin`, `lanes.bin`, `intermediate.bin`,
`road_names.bin`, `poi_index.bin`, `poi_names.bin` and geojsonseq.

## How the reader works

A `.osm.pbf` is a flat sequence of `[u32 BE header_len][BlobHeader][Blob]`.
`pbf::scan_blobs` walks that framing once reading only headers, recording each
`OSMData` blob's `(offset, len)`. Threads then each open their own `File`, seek,
read and inflate their own blobs — no channels, no shared queue, no throttling
condvar, which is how the C++ `ThreadPool`/`MoveTask`/`wait_finished` machinery
(and the data race its comments describe) disappears.

Every blob is self-checked: `miniz_oxide` validates the zlib header and the
Adler-32 trailer, and the inflated length is compared against the blob's declared
`raw_size`. Across California's 23,007 blobs that is strong evidence the reader
is correct. Compressions that would need a C library (lzma, bzip2, lz4, zstd)
are reported as a clear error rather than silently skipped, because a skipped
blob would yield a quietly incomplete graph.

Because a PBF stores all nodes, then all ways, then all relations, the first pass
records which entity kinds each blob held; later passes skip blobs outright.
`road_graph` needs two passes (ways must be known before we know which nodes
matter) and `poi_extract` needs three (relations -> ways -> nodes).

## Testing

`cargo test` runs 206 in-crate tests, following the `gtfs_ingest` pattern. The
fixture in `src/testpbf.rs` hand-encodes a complete `.osm.pbf` — including real
zlib framing built from stored DEFLATE blocks and a real Adler-32 — so the
decoder is tested against bytes produced independently of it.

- **protobuf**: uvarint at every 7-bit boundary, 10-byte negative `int64`,
  zigzag against the spec's vectors, packed deltas, skipping unknown fields of
  every wire type, and truncated input rejected.
- **`latlng_to_spatial`** compared against a transcription of
  `graph.rs::latlng_to_spatial` for a table of coordinates. If these drift the
  device's spatial binary search silently returns the wrong nodes.
- **`accurate_dist_mm`** against known city-pair great-circle distances, and
  across the antimeridian and pole to pole (where a naive `i32` coordinate
  subtraction overflows).
- **tags**: `get_hw_id`, `parse_maxspeed` (`mph` ×1.60934, `knots` ×1.852,
  non-numeric -> 0), `turn:lanes` (`|` lanes, `;` indications, unknown ->
  `LANE_NONE`, count pad/truncate), and POI `classify` precedence including the
  rule that bare `railway`/`public_transport` values are *not* POIs.
- **whole pipeline**: the fixture through `road_graph`, asserting the exact
  `metadata.bin` bytes, the `nodes.bin` sentinel, `edges.bin % 14 == 0`, Morton
  ordering, the full decoded edge set, the exact `road_names.bin`, and
  `lanes.bin`'s sparse index resolved by the same binary search the device uses —
  plus the same for `poi_extract`, and a two-runs-are-identical check for both.
- **the v3 layouts at their boundaries**: a chain of exactly 256 points, which is
  the largest legal per-edge blob (1016 bytes) and so the worst case for the
  within-block `u16`; a graph whose *geometry* edges span three coarse blocks, where
  `g % 32 == 0` and `31` both fall inside it rather than under the sentinel; a graph
  where every edge stores a polyline and one where none does; on-disk rank/select
  over the presence bitmap, exercised standalone at byte and 512-bit block
  boundaries against a brute-force popcount, and past a single rank block; the
  writer's endpoint assertions firing on a mismatched chain; a graph with no
  `turn:lanes` at all, whose lane index is nothing but its own sentinel; and
  `cap_u32` refusing an edge count past the `u32` ceiling `nodes.bin` depends on.
- **`--rounds` changes no byte**: the strongest check on the presence bitmap and
  both offset levels, run across `1`, `4` and `17` on two fixtures and both collapse
  paths. If any table were round-dependent it would say so.
- **ring centroid**: that the repeated ring vertex is the one with the smallest
  `(lon, lat)`, so the centroid does not move when the ring's start rotates.
  Getting this wrong put small buildings metres off the old tool's output.

## Cross-checked against the C++ it replaces

The C++ cannot be a byte-for-byte oracle (it was nondeterministic), so the port
was validated *structurally*. Both toolchains were run over the same San Luis
Obispo extract — the C++ built in WSL against libosmium 2.20 — and compared on
invariants that do not depend on its thread-scheduling-dependent ordering:

| Invariant | Result |
|---|---|
| `metadata.bin` magic / version / `node_count` | identical (88,666 nodes) |
| `edge_count` | identical (181,481) |
| `lanes.bin` length | identical (1,453,984 B) |
| `road_names.bin` string set | identical (1,125 names) |
| Node coordinates | every one matches; 7,164 differ by exactly one 1e-7 unit (1.1 cm) — see below |
| Edge `(type, speed_limit, name)` groups + `dist_mm` | identical groups, every distance within 12 mm |
| Out-degree sequence | identical |
| POIs the C++ found | all 1,413 present, identical `name` + `type` |
| Node/closed-way POI coordinates | all within one 1e-7 unit |
| Relation POI centroids | 4 of 15 over 1 m, worst 3.1 m |

Two deliberate differences:

- **Coordinates are 1.1 cm more accurate.** The C++ recovered `lat_e7` as
  `(int32_t)(location.lat() * 1e7)` — truncating a double that libosmium had
  already divided by 1e7 — so ~8% of coordinates came out one unit low. This
  keeps the PBF's exact integer, which also shifts `dist_mm` by a few mm.
- **Three extra POIs.** libosmium's assembler cannot close a ring whose member
  ways were cut off by the bbox, so it silently dropped three boundary relations
  (Los Padres National Forest, Santa Lucia Wilderness, Cuesta Ridge). The centroid
  approximation here still places them. Gaining a named POI is the better failure
  mode, and on an unclipped extract the question does not arise.

## Known limitations

- **Relation POI centroids are approximated.** libosmium assembled true polygon
  rings (`MultipolygonManager` + `Assembler`); porting a ring assembler is a
  large piece of work on its own. Here a relation's outer ring is approximated by
  the deduplicated node ids of its `outer`-role member ways. That is exact when
  the ring is a single closed way; when it is split across several, the measured
  error was under 3.1 m across the sample above. POIs render as points at z12–z16,
  so this is not visible, but `poi_index.bin` is not byte-identical to the C++ for
  relation-derived POIs. Node- and closed-way-derived POIs match exactly.
- **Closed-way areas** use `first ref == last ref` where libosmium compared end
  *locations*. Equivalent for real OSM data and it avoids needing a location
  index during the way pass.
- **Memory.** `road_graph` holds a 2.5 GB node-id bitset (sized from the C++'s
  `BITSET_SIZE = 20e9`, so node ids at or above it are skipped exactly as
  before) plus the node and edge arrays: roughly 10 GB peak on California. Same
  ballpark as the C++, and it will not run on a small machine.
