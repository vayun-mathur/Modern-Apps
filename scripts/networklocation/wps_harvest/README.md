# wps_harvest — offline beacon store builder

Builds `wifi-v3.wpsdb` and `cells-v3.wpsdb`, the offline WiFi and cell beacon databases that
`:networklocation` resolves scan results against.

Replaces `wtfps-experiment/store.py`, which lived outside the repo and no longer exists.
`SUPPLY_CHAIN_RISKS.md` recorded that as the app's one unresolved-provenance dependency: the
shipped stores were built by code nobody could read. This is that code, in the tree.

## Pipeline

```
v1 wifi.wpsdb ── wps_extract ──► seed-bssids.txt ─┐
                 (BSSIDs only)                    │
                                                  ├─ wps_crawl ──► *.shard ─┐
(or a local scan, or random probing) ─────────────┘  (gs-loc snowball)      │
                                                                            ├─ wps_build ──► wifi-v3.wpsdb
OpenCelliD ────── wps_seed ──────────────────────────────► *.shard ─────────┘                cells-v3.wpsdb
```

Shards are the interchange format: fixed-width, unsorted, append-only. The stages are separate
processes because they have nothing in common operationally — a seed ingest is minutes, a
planet crawl is weeks, a build is hours of IO — and because passing shards means a crawl can be
interrupted, resumed, extended, or merged with a newer dump without any stage knowing about the
others.

## What "accuracy first" actually means here

The store this writes keeps, per beacon:

- **coordinates as integer degrees x 1e8** — bit-exact against what the source reported, since
  gs-loc returns e8 integers and the CSV dumps are parsed as exact decimals rather than through
  `f64`;
- **its own horizontal accuracy**, in metres.

The format it replaces quantized to a **20 m grid** and stored **no accuracy at all**, so the
device substituted one constant for every beacon. That mattered more than it sounds: the
solver's uncertainty weighting is fully implemented — `jni.rs` turns accuracy into each
measurement's `six_sigma_squared`, `lib.rs` sorts and seeds RANSAC by it and uses it as the
inlier threshold, `multilateration.rs` uses it as a per-axis dead-band and weight — and with
every beacon reporting the same number it all reduced to an unweighted centroid.

Coverage comes from the crawl. What makes a fix *good* is `quality.rs`, which drops:

- **movers** — a BSSID seen in two places kilometres apart is a phone hotspot, a travel router,
  or an AP on a bus. Recording either position is worse than recording none, because the device
  gets confidently placed somewhere it is not. This is also the standard privacy mitigation for
  a dataset of this shape, since a moving AP's history is a person's history;
- **randomized and multicast MACs**, which are not stable identifiers for a place;
- **identities that do not fit the key layout**, rather than truncating them into a collision.

`NearbyWifi.kt` mirrors the randomized-MAC filter so the device does not spend a store probe on
a key the builder deliberately excluded.

## The gs-loc protocol

The envelope is byte-for-byte from [`joelkoen/wtfps`](https://codeberg.org/joelkoen/wtfps),
which is a working client. The version recovered from this app's own deleted Kotlin
implementation (`reference/`) was **wrong in three ways** and produced an empty response every
time:

| | Recovered Kotlin | Actual |
| --- | --- | --- |
| Trailer | `00 00 00 01` + `00 00 00 00` (8 bytes) | `00 00 00 01 00 00 00` (**7**) |
| Body length prefix | big-endian `u16` | protobuf **varint** |
| Body | sent once | sent **twice** |
| OS string | `8.4.1.12H321` | `17.5.21F79` |

The first two together put two spurious zero bytes in front of every request. The duplicated
body looks like a mistake in wtfps and may well be one, but it is what the working client does.

The schema also differs from the recovered `.proto`: there is one `AppleWLoc` message, not
separate WiFi and cell ones. WiFi devices are field 2, cell responses field 22, and a cell
*request* is a single tower in field 25 — so unlike WiFi, cells are one tower per request.
"Not found" is `-18000000000` (-180 degrees) in both coordinates, **not** a negative accuracy;
treating a missing accuracy as "not found", as the old client did, discarded real fixes.

## Building

```sh
cd scripts/networklocation/wps_harvest
cargo build --release
```

Detached from the repo-root workspace, like `scripts/maps/osm_ingest`: these are host binaries,
so the build never pulls the aarch64-android toolchain or the app crates' lints. `ureq` is the
one dependency that breaks the other host tools' "pure Rust, no C toolchain" rule, via rustls'
`ring` backend; it is allowed because nothing here cross-compiles and the alternative is
shelling out to curl once per request.

## Running

### 1. Seed the crawl

**There is no open bulk WiFi dump to seed from.** beaconDB's homepage and README both say
dumps "will be" published — they are announced, not released — and Mozilla Location Services
was never able to publish its WiFi data at all, for legal and privacy reasons. WiGLE has the
data but its terms forbid redistributing it in bulk.

The best seed is the previous-generation store, which is still published and holds hundreds of
millions of real BSSIDs:

```sh
curl -O https://data.maproj.org/wps/wifi.wpsdb
./target/release/wps_extract wifi.wpsdb --out seed-bssids.txt
```

**Identities only — v1 positions are never imported.** v1 quantized to a 20 m grid and stored
no accuracy, so bringing a position forward would mean inventing one. It would also be worse
than useless: `quality::reduce` keeps whichever observation claims the better accuracy, and
Apple typically reports 20-100 m for WiFi, so a v1 position labelled 20 m would **beat most
real crawled measurements** and pin the beacon to its old grid cell. Every coordinate in the
new store comes from a source that also said how accurate it was.

Extracting the real store yields ~189 M BSSIDs — and drops ~98 M (34%) randomized or
locally-administered addresses that v1 never filtered.

Failing that, a local WiFi scan gives a seed for one metro:

```powershell
netsh wlan show networks mode=bssid | Select-String 'BSSID' > seed-bssids.txt
```

```sh
nmcli -f BSSID device wifi list > seed-bssids.txt          # Linux
```

### 2. Crawl

```sh
./target/release/wps_crawl \
  --state ./crawl-state \
  --out wifi-gsloc.shard \
  --seed seed-bssids.txt \
  --interval 1000 \
  --expected 1000000000
```

Kill it whenever. Rerun the same command to resume — the frontier, its cursor and the seen-set
all live in `--state`, and the shard is appended to. `--max-queries` bounds a trial run.

**One BSSID per request.** Apple answers HTTP 400 to a request carrying more than one WiFi
device, verified live: a single BSSID returns ~108 neighbours, eight returns 400 every time.
`--batch` therefore defaults to 1 and exists only so the limit can be re-tested if the service
changes. The surplus results are not asked for — there is no `num_results` field, Apple simply
volunteers the neighbours of anything it recognises, and that is the whole basis of the crawl.

#### Cold start with no seed at all

When the frontier is empty, `wps_crawl` guesses addresses until one lands (`--probe`). This
works because only ~37 000 of the 16.7 million possible OUIs are assigned to anyone, so
drawing from assigned OUI space is roughly 450x better than drawing uniformly. It learns:
every BSSID in any response contributes its OUI to a frequency-weighted reservoir, so the hit
rate climbs as it runs. See `probe.rs` for the arithmetic.

Be realistic about the cold start, though — at one guess per request and one request per
second:

| Starting from | Rough hit rate | Expected time to first hit |
| --- | --- | --- |
| Nothing at all | ~1 in 140 000 | ~39 hours |
| An OUI list (`--oui-file`) | ~1 in 300 | ~5 minutes |
| One real BSSID (`--seed`) | — | immediate |

So `--probe` is a genuine fallback, not the intended path. A single BSSID from a local WiFi
scan is worth more than a day of guessing:

```sh
./target/release/wps_crawl --state ./crawl-state --out wifi-gsloc.shard \
  --oui-file oui.csv        # the IEEE registry, or any WiFi scan output
```

### 3. Seed the cell store from the open dumps

Cells are unaffected by any of the above — OpenCelliD publishes a full CSV, and this step works
today. The parser is driven by the header row rather than fixed column positions, so a renamed
column fails loudly instead of quietly reading longitude out of the accuracy field. Compressed
dumps are piped in rather than decompressed in process.

```sh
gunzip -c cell_towers.csv.gz | \
  ./target/release/wps_seed --kind cell --source opencellid --out cells-ocid.shard -
```

**This talks to somebody else's service, at length.** Requests are serialized through one
client with a minimum interval, and failures back off geometrically to a five-minute ceiling.
`analysis/server-inventory-raw.md` already flags the endpoint as a terms-of-service concern;
running the crawl faster is both rude and self-defeating.

### Crawl cells from gs-loc

OpenCelliD misses cells, and a phone whose serving cell is missing gets no fix at all: LTE
neighbour reports carry only a PCI, so the serving cell is usually the only one with a full
identity. `wps_cellcrawl` fills the gap from gs-loc. An LTE cell request returns the asked-for
cell plus about 120 surrounding LTE cells, so seeding it with a phone's cells covers the area
around it:

```sh
./target/release/wps_cellcrawl --out cells-gsloc.shard \
  --seed 310,260,14570,21206278 --max-queries 30 --radius-km 15
```

Seeds are `MCC,MNC,TAC,CID`. Cells nearest the first resolved seed are queried first. LTE only:
gs-loc's cell request is the LTE tower field.

### 3. Build

```sh
./target/release/wps_build --kind wifi --out wifi-v3.wpsdb \
  --scratch /var/tmp/wps  wifi-bdb.shard wifi-gsloc.shard

./target/release/wps_build --kind cell --out cells-v3.wpsdb \
  --scratch /var/tmp/wps  cells-ocid.shard cells-bdb.shard
```

`--scratch` needs roughly twice the finished store's size. Nothing proportional to the record
count is held in memory at any stage: the sort spills fixed-size runs, the merge streams, and
the writer streams its three sections to scratch files before concatenating them.

The build prints what it discarded and why, then reads the store back and checks every record.
The read-back walks the Elias-Fano bitvector directly rather than using `select0`, so a shared
misunderstanding between two `index()` implementations cannot hide behind it. `--no-verify`
exists but there is no good reason to use it.

### 4. Publish

```sh
sha256sum wifi-v3.wpsdb cells-v3.wpsdb
../../maps/publish_r2.sh wifi-v3.wpsdb cells-v3.wpsdb
```

Put the hashes into `OfflineDatabases.sha256For` in the app. They are load-bearing: a store is
several gigabytes fetched in resumable chunks, and a truncated file is non-empty, so without a
checksum it reads as installed and then fails its magic check on every open, forever.

## Cell key layout

```
mcc(10) | mnc(10) | radio(4) | area(24) | cid(36)      = 84 bits
```

The previous layout was `mcc(10) | mnc(10) | tac(16) | cid(28)`, exactly 64 bits with nothing
spare, which cost two things. 5G's NCI is 36 bits and was **truncated to 28**, aliasing distinct
gNB cells. And there was **no radio-type discriminator**, so an LTE ECI and a GSM CID — the same
number in the same network and area code — mapped to one key. Fixing either requires more than
64 bits, which is why the store's keys are 128-bit and the JNI boundary passes a `(hi, lo)` pair.

## Keeping the two sides in step

`keys.rs` and `BeaconKeys.kt` must agree exactly. A mismatch does not throw or log — it makes
every lookup miss, which is indistinguishable from a database that does not know the beacon.
The test vectors in `keys.rs` are transcribed from `BeaconKeysTest.kt`; change them together.

Likewise `store.rs` writes the format `networklocation/src/main/rust/src/wpsdb.rs` reads, and
that file's fixture tests build a store with a transcription of this writer, so a divergence
fails on the reader side too.

## Tests

```sh
cargo test
```

Everything is covered without a real dump or a network: exact decimal parsing against the cases
where `f64` disagrees, header resolution for both dumps' spellings, mover and randomized-MAC
rejection, external sort across many runs, frontier resume semantics, store round-trip at both
key widths, and gs-loc request framing against the recovered Kotlin client byte for byte.
