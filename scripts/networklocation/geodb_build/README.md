# geodb_build — offline geocoder database builder

Builds `geocoder.db`, the offline reverse/forward geocoder database that
`:networklocation` serves the system `GeocodeProvider` from.

Replaces the `osmium tags-filter | osmium export | scripts/geocoder_gen.cpp` chain, which
needed osmium, g++, OpenMP, simdjson and POSIX `mmap` — so WSL only — and wrote a **101 GB**
GeoJSONSeq intermediate on the way to a 1.4 GB database. This reads the `.osm.pbf` natively
through `scripts/maps/osm_ingest`, holds no intermediate, and runs on Windows.

## What v3 indexes that v2 did not

v2 held **only** objects carrying both `addr:housenumber` and `addr:street`. Everything below
was simply absent, in both directions:

| Added | Why it mattered |
| --- | --- |
| **Named streets**, sampled every ~250 m along their length (roads only, not footways, service aisles or bus stops) | Reverse geocoding could return a house number or nothing; it could never say "you are on Market Street". |
| **Points of interest** — `amenity`/`shop`/`tourism`/`leisure`/`office`/`healthcare` with a name | A named building or shop was invisible. |
| **Populated places** — `place=city\|town\|village\|hamlet\|suburb\|neighbourhood\|quarter\|borough\|island` | No way to say which neighbourhood or village a point is in. |
| **`addr:place` addresses** | Much of Germany and Austria, and most of Japan and Korea, address buildings against a place rather than a street. Requiring `addr:street` silently discarded all of them. |

On California, v3 held 8.96 M records where the old rules would have given 3.66 M.

## v4: cut down for size

v4 keeps everything v3 indexes but stores it more cheaply. The header version is 4; the
section layout is unchanged.

- **Coordinates are e5** (about 1.1 m). At e7 the two coordinate columns were nearly half the
  file, spent on centimetres no geocoder answer needs.
- **Fewer street samples**: ~250 m apart instead of ~100 m, roads only, and exact duplicate
  rows (adjacent ways meeting at a shared node) written once.
- **The forward index holds only records with a street.** A forward query always names one;
  keying on kind instead would lose POIs and places that carry a full address but no house
  number.
- **The name index holds one entry per street name per grid cell**, not one per sample.

## v5: read from a memory map

v5 changes no content, only the layout, so the app can serve the planet without holding it in
RAM. v4's reader inflated every dictionary on open: about 2 GB of native heap on the phone and
more than a minute before the first answer.

- **Dictionaries are stored in blocks** of 1,024 strings, each its own zstd frame, behind an
  uncompressed table of every block's first string. A lookup binary-searches that table in the
  mapped file and decompresses one block.
- **Column directories store end offsets**, so any block is located straight from the mapping.

On California this costs 1% of size (43.44 MiB vs 43.00 MiB).

## Building and running

```sh
cargo build --release -p geodb_build

# The crate is a member of the repo-root workspace, so the binary lands in the root target/.
# rehearse on a state before the planet
target/release/geodb_build scripts/maps/inputs/california.osm.pbf --out analysis/geodb/ca.db
target/release/geodb_build scripts/maps/inputs/planet.osm.pbf --out analysis/geodb/geocoder.db
```

Run from the repo root.

Options: `--bbox W,S,E,N` to clip, `--threads N` (default: all cores, or `MAPS_THREADS`),
`--no-verify` to skip the read-back.

Detached from the Android Rust workspace like `scripts/maps/osm_ingest`, so the build never
pulls the aarch64 toolchain. `zstd` is the one C dependency: the device reader decompresses
with the pure-Rust `ruzstd`, which has no encoder, and nothing here cross-compiles.

## Publishing

```sh
sha256sum geocoder.db
../../maps/publish_r2.sh geocoder.db
```

Then put the hash into `OfflineDatabases.sha256For` in the app.

## The three orderings that decide whether it works

None of these fails loudly. A database with any of them wrong opens fine and returns
plausible nonsense, which is why `write::verify` re-reads the finished file through an
independent path and checks all three.

1. **Records are ordered by grid cell, then Z-order within the cell.** Reverse lookup walks
   outward from the query's cell, so this ordering *is* the spatial index.
2. **Searchable dictionaries are sorted by UTF-16 code unit**, because the reader binary
   -searches them with a UTF-16 comparator. UTF-8 and UTF-16 disagree above the BMP: `U+10000`
   sorts after `U+FFFF` in UTF-8 but before it in UTF-16, because surrogates start at `0xD800`.
   Sorting the wrong way makes forward lookups start missing entries near the disagreement.
3. **The forward and name indexes point into grid order**, each sorted by its own key. Out of
   step with its dictionary, forward search returns the wrong rows.

The Z-order interleave is the format's other landmine. v2 interleaved 16 bits per axis, which
worked only because an e6 cell is 50 000 units wide. `format::morton_in_cell` interleaves 20,
which covers an e5 cell (5 000 units, 13 bits) with room to spare; too few bits does not fail,
it silently produces a worse ordering, and on overflow a wrong one.

## Determinism

Two runs over the same input produce byte-identical output, verified on full California. This
is not free: interned dictionary ids are assigned in first-seen order, which depends on how the
PBF's blobs were chunked, so `write` re-sorts each tied run once the final content-derived ids
are known (`sort_rows_deterministically`). A `HashMap` iteration or a non-total sort key
silently destroys the property, and only a byte comparison catches it.

## Memory

The two things that decide whether a planet build fits:

- **Rows are interned during extraction, not after.** Seven `String`s per row is about 250 GB
  at planet scale; seven `u32` ids is 40 bytes a row. Every pass uses `run_pass_sink` rather
  than `run_pass` so a chunk's owned strings are interned and dropped as it arrives, instead of
  every chunk's strings being alive at once.
- **`osm_ingest::nodeloc` backs its coordinate array with a memory-mapped file**, so the node
  table costs 8 bytes per needed node in RAM rather than 16.

## Compression

Column blocks and dictionaries are zstd **level 3**. Measured on California's column blocks,
level 3 is 38.85 MiB in 0.8 s against 35.48 MiB in 30 s at level 19: 9.5% larger for a ~37x
faster write. Blocks are compressed in parallel (they are independent by construction), and the
output is byte-identical whatever the thread count.
