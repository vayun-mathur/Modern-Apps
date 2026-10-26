# networklocation — offline database build scripts

The three databases `:networklocation` reads are built here. None of them ships in the APK:
at 3.5 GB the old bundle pushed the MAOS factory zip past fastboot's 4 GiB limit, so they are
published to `data.maproj.org` and downloaded to device-protected storage on request.

| Database | Built by | Holds |
| --- | --- | --- |
| `geocoder.db` | [`geodb_build/`](geodb_build/) | Addresses, named streets, POIs and populated places, from an OSM planet dump |
| `wifi-v3.wpsdb` | [`wps_harvest/`](wps_harvest/) | WiFi BSSID → coordinates + accuracy |
| `cells-v3.wpsdb` | [`wps_harvest/`](wps_harvest/) | Cell tower → coordinates + accuracy |

Both are self-contained Rust crates with their own READMEs; start there.

## Rehearse before the planet

Both builders scale from a metro extract to the planet, and both have surprises at scale that
a small extract will not show you. Work up the ladder, recording time and peak memory at each
rung, exactly as `scripts/maps/README.md` prescribes for the map layers:

```
SanFrancisco.osm.pbf   31 MB     seconds
california-latest      1.3 GB    ~80 s,  9.0 M records,  70 MiB
planet-latest          90 GB     hours
```

## What replaced what

The geocoder used to be built by `osmium tags-filter | osmium export` into a **101 GB**
`addr.geojsonseq`, then packed by `scripts/geocoder_gen.cpp`. That needed osmium, g++, OpenMP,
simdjson and POSIX `mmap` — WSL only — plus ~190 GB of free disk and a large-RAM box, and it
indexed address points and nothing else. `geodb_build` reads the PBF natively through
`scripts/maps/osm_ingest`, holds no intermediate, runs on Windows, and also carries streets,
POIs and places.

`scripts/geocoder_gen.cpp` is kept as the reference implementation of the v2 format. It is not
part of any current pipeline.

The beacon stores used to be built by `wtfps-experiment/store.py`, which lived outside the repo
and no longer exists anywhere. `SUPPLY_CHAIN_RISKS.md` recorded that as the app's one
unresolved-provenance dependency: the shipped stores were built by code nobody could read.
`wps_harvest` is that code, in the tree.

## Removed

`gen.sh`, `run-extract.sh`, `poll.sh`, `gpoll.sh`, `reencode.sh` and `memest.py` drove the old
JVM and C++ geocoder builds. They referenced `GeocoderGenerator` (a unit test that no longer
exists) and `networklocation/tools/extract-osm-addresses.sh` (a file that no longer exists), so
they could not run; and they hardcoded one machine's WSL paths. Recover them from git history
if the v2 format ever needs rebuilding.
