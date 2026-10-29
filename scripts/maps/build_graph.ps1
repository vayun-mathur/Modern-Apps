# build_graph.ps1 -- build one .mamaps archive from one extract, end to end.
#
# The single full builder: OSM extract (+ coastline, world-transit feeds, DEM)
# in, final .mamaps out. One command does the whole pipeline:
#
#   [1] check every input exists + the free-space preflight
#   [2] cargo-build the host tools (road_graph, poi_extract, transit_shapes,
#       mamaps_build, mamaps_dump, mamaps_pack)
#   [3] build the road graph into a TEMP dir (never the final output)
#   [4] derive the transit-routes layer from inputs/world.transit itself
#       (transit_shapes --pack; the raw GTFS feeds are not needed)
#   [5] run mamaps_build with pbf + coastline + temp graph + routes + dem into a
#       TEMP tiles-only archive ($tmp/tiles.mamaps) -- NO sidecar footer yet
#   [6] extract the POI sidecars from the pbf into a TEMP dir, then mamaps_pack
#       the graph + POI + transit sidecar sections onto the temp tiles archive
#       to produce the FINAL $Out (this is what adds the MAMA8 sidecar footer
#       the app's router, departure boards and POI tags read)
#   [7] header check via mamaps_dump on the FINAL $Out (rings_validated, 16 KiB
#       prefix budget, build_id printed) + TIME/PER-ZOOM/PEAK-MEMORY summary,
#   then delete every intermediate (the temp dir is always removed on success).
#
# Nothing here is optional content except under -NoTransit: every input is
# mandatory and every build is all 12 layers at z0-14. -Threads/-Verify are
# operational (how), not content options -- there is no flag that skips
# the coastline, the graph, the DEM, a layer, or a zoom. -NoTransit is the
# one content exception: it skips every GTFS feed (empty transit-routes +
# no --transit sidecar, as if 0 feeds).
#
# Spill backends: by default the feature spill, tile chunks and ways spill
# stage as temp files beside the output. `MAPS_ANON_SPILL=1` stages them in
# pagefile-backed anonymous memory instead — same bytes, no `.tmp` files,
# nothing stranded on kill. The pagefile cap is then the bound, not free disk
# (system-managed pagefile is what a world build wants); without it the preflight
# below warns against a larger multiple.
#
# This folds in the old measure_build.ps1 (live progress + cost report) and the
# old harden_mamaps.sh (header checks; its full 1/3/32-thread matrix lives on
# as the -Verify spot-check, off by default because it doubles build time).
#
# Usage:
#   .\build_graph.ps1 [-Region california|na|world|caonly|naonly] [-NoTransit] [-Verify] [-Threads N]
#
# -Region filters roads/POIs/buildings to the region's bbox. Everything else
# (water, earth, boundaries, landuse, transit, traffic, DEM) is always
# included regardless. The input is ALWAYS the planet -- except caonly, which
# takes inputs/california.osm.pbf directly with NO coordinate filtering, and
# naonly, which takes inputs/north-america.osm.pbf directly the same way
# (downstream tools get --region world): a california build is
# a world build with only CA roads/POIs/buildings, so borders, coastlines
# and country names still render everywhere.
#
# Inputs are fixed (no file-location options):
#   inputs/planet.osm.pbf -> inputs/california.mamaps | inputs/na.mamaps | inputs/world.mamaps
#   inputs/california.osm.pbf -> inputs/caonly.mamaps (only with -Region caonly)
#   inputs/north-america.osm.pbf -> inputs/naonly.mamaps (only with -Region naonly)
#   plus inputs/coastline.shp, inputs/world.mdem.
#   inputs/world.transit is required
#   unless -NoTransit is passed, which skips GTFS entirely (empty routes +
#   no --transit sidecar, as if 0 feeds).
#   inputs/reviews_archive.tsv is required: its per-place rating, review count
#   and summary are always merged into the POI attribute sidecar by OSM element
#   (see analysis/osm_reviews/summarize_reviews.py).
#
# Requires: cargo (https://rustup.rs). A state-sized extract needs roughly
# 10 GB of RAM; any build wants ~6x the .pbf in free disk (checked up front),
# or ~2x under MAPS_ANON_SPILL=1 (archive + sidecars only — the spills live
# in the pagefile, so the pagefile cap is the bound instead).
#[CmdletBinding()]
param(
    # Which region to build: california filters roads/POIs/buildings to the
    # California bbox, na to the North America bbox, world builds everything.
    # Everything else (water, earth, boundaries, landuse, transit, traffic,
    # DEM) is always included.
    [ValidateSet("california", "na", "world", "caonly", "naonly")]
    [string] $Region = "world",
    # Skip every GTFS feed: no transit_shapes run, an empty transit-routes
    # file for mamaps_build, and no --transit sidecar on the pack -- the
    # archive builds as if 0 feeds existed.
    [switch] $NoTransit,
    # Worker threads (via MAPS_THREADS). 0 = the tools' default (max).
    [int] $Threads = 0,
    # Determinism spot-check: rebuild at 1 thread into temp and require a
    # byte-identical sha256. Off by default; it doubles the build.
    [switch] $Verify
)

$ErrorActionPreference = "Stop"

# --- cohesive timing ----------------------------------------------------
# Two nested gapless chains plus machine lines from the tools.
#
# Outer chain (this script): $TotalSw runs from here to the final report;
# $PhaseSw is restarted at each phase boundary and its elapsed is booked to
# the phase just finished, so TOTAL WALL always equals the sum of the phase
# rows (within ms). No step owns its own Stopwatch anymore.
#
# Inner chain (PS1-owned subphases): $SubSw mirrors the same stop==start
# contract one level down. Switch-Phase first closes the open subphase into
# the old phase, so a subphase never spans a phase boundary. Used for phases
# with no Rust timer (tools' per-crate cargo builds, verify's steps) and for
# PS1-side work inside a Rust-timed phase (transit's manifest resolution).
#
# Rust-timed phases (graph, transit, tiles, poi, pack) report their own
# subphases as `TIMING {"phase":...,"wall_s":...,"subphases":[...]}` lines on
# stdout (see timing.rs, one copy per crate). Each binary's stdout is Tee'd
# to a per-phase log; the report parses those lines back out. Per-zoom
# `TIMING {"zoom":...}` machine rows ride the same channel.
#
# Closure rule, applied uniformly per phase at report time:
#   other = phase_wall - sum(named subphases), clamped at zero.
# PS1 phase walls are ground truth; Rust subphases are the measured detail;
# `other` absorbs spawn/pipe overhead (and the microseconds of boundary skew
# between the two clocks), so subphases always sum to the phase wall.
#
# To time a new step: one Switch-Phase/Switch-Subphase call at its boundary
# (Rust side: one timer.switch), and it appears in TIME + timing.json.
$script:PhaseTimes = [ordered]@{}
$script:SubTimes = [ordered]@{}   # "phase/sub" -> seconds, PS1-owned subphases only
$script:TotalSw = [System.Diagnostics.Stopwatch]::StartNew()
$script:PhaseSw = [System.Diagnostics.Stopwatch]::StartNew()
$script:SubSw = [System.Diagnostics.Stopwatch]::StartNew()
$script:PhaseCurrent = "preflight"
$script:SubCurrent = "other"
function Switch-Subphase([string] $Next) {
    # Read first, restart second: the boundary instant belongs to neither
    # subphase twice and to no gap. Bookkeeping happens after the restart so
    # it never leaks into either subphase.
    $elapsed = $script:SubSw.Elapsed.TotalSeconds
    $script:SubSw.Restart()
    $key = "$($script:PhaseCurrent)/$($script:SubCurrent)"
    if ($script:SubTimes.Contains($key)) { $script:SubTimes[$key] += $elapsed } else { $script:SubTimes[$key] = $elapsed }
    $script:SubCurrent = $Next
}
function Switch-Phase([string] $Next) {
    # Close the open subphase into the OLD phase first, so no subphase ever
    # spans a phase boundary; then the phase switch itself, as before.
    Switch-Subphase "other" | Out-Null
    $elapsed = $script:PhaseSw.Elapsed.TotalSeconds
    $script:PhaseSw.Restart()
    if ($script:PhaseTimes.Contains($script:PhaseCurrent)) { $script:PhaseTimes[$script:PhaseCurrent] += $elapsed } else { $script:PhaseTimes[$script:PhaseCurrent] = $elapsed }
    $script:PhaseCurrent = $Next
}
function Stop-Phases() {
    Switch-Subphase "other" | Out-Null
    $elapsed = $script:PhaseSw.Elapsed.TotalSeconds
    $script:PhaseSw.Stop()
    $script:TotalSw.Stop()
    $script:SubSw.Stop()
    if ($script:PhaseTimes.Contains($script:PhaseCurrent)) { $script:PhaseTimes[$script:PhaseCurrent] += $elapsed } else { $script:PhaseTimes[$script:PhaseCurrent] = $elapsed }
}
# Parse one phase log's `TIMING {...}` machine lines. Returns
# @{ wall = <phase wall_s>; subs = [ordered]@{ name = seconds } } for the
# last "phase" line found ($null when the tool never emitted one, e.g. a
# skipped -NoTransit step), plus any per-zoom rows in @{ zooms = @() }.
function Read-TimingLog([string] $Path) {
    $phase = $null
    $zooms = @()
    if (-not (Test-Path $Path)) { return @{ phase = $phase; zooms = $zooms } }
    foreach ($line in (Get-Content $Path)) {
        if ($line -notmatch '^TIMING (.*)$') { continue }
        try { $o = $Matches[1] | ConvertFrom-Json } catch { continue }
        $names = @($o.PSObject.Properties.Name)
        if ($names -contains 'phase') {
            $subs = [ordered]@{}
            foreach ($s in $o.subphases) {
                if ($subs.Contains($s.name)) { $subs[$s.name] += [double]$s.s } else { $subs[$s.name] = [double]$s.s }
            }
            $phase = @{ wall = [double]$o.wall_s; subs = $subs }
        } elseif ($names -contains 'zoom') {
            $zooms += [pscustomobject]@{
                zoom = [int]$o.zoom; tiles = [long]$o.tiles; features = [long]$o.features
                points = [long]$o.points; dropped = [long]$o.dropped; bytes = [long]$o.bytes
                map_ms = [long]$o.map_ms; merge_ms = [long]$o.merge_ms
                encode_ms = [long]$o.encode_ms; append_ms = [long]$o.append_ms
            }
        }
    }
    return @{ phase = $phase; zooms = $zooms }
}

# Fixed inputs -- no file-location options. ALWAYS the planet: a california
# build keeps only CA roads/POIs/buildings but renders borders, coastlines
# and country names everywhere, so it needs the whole world as input.
# `na` is the same with the North America bbox. `caonly`/`naonly` are the
# exception: their extracts ARE the region, so they build unfiltered.
$Pbf = Join-Path $PSScriptRoot "inputs/planet.osm.pbf"
if ($Region -eq "california") {
    $Out = Join-Path $PSScriptRoot "inputs/california.mamaps"
} elseif ($Region -eq "na") {
    $Out = Join-Path $PSScriptRoot "inputs/na.mamaps"
} elseif ($Region -eq "caonly") {
    # No coordinate filtering: the extract IS California, so downstream tools
    # build it as world.
    $Pbf = Join-Path $PSScriptRoot "inputs/california.osm.pbf"
    $Out = Join-Path $PSScriptRoot "inputs/caonly.mamaps"
} elseif ($Region -eq "naonly") {
    # No coordinate filtering: the extract IS North America, so downstream
    # tools build it as world. Distinct from na.mamaps, which filters the
    # planet to the NA bbox.
    $Pbf = Join-Path $PSScriptRoot "inputs/north-america.osm.pbf"
    $Out = Join-Path $PSScriptRoot "inputs/naonly.mamaps"
} else {
    $Out = Join-Path $PSScriptRoot "inputs/world.mamaps"
}
# What --region the tools see. caonly/naonly build unfiltered (world) from
# their extracts; every other region passes through.
$BuildRegion = if ($Region -eq "caonly" -or $Region -eq "naonly") { "world" } else { $Region }
$Coastline    = Join-Path $PSScriptRoot "inputs/coastline.shp"
$WorldTransit = Join-Path $PSScriptRoot "inputs/world.transit"
$Dem          = Join-Path $PSScriptRoot "inputs/world.mdem"
$Reviews      = Join-Path $PSScriptRoot "inputs/reviews_archive.tsv"
function Find-Built([string] $crate, [string] $name) {
    # The tool crates are root-workspace members (no [workspace] table), so
    # `cargo build` links into the WORKSPACE target dir (repo root
    # target/release), not the per-crate one. The per-crate target/ dirs are
    # fossils from when the crates were detached: cargo never refreshes them,
    # so resolving there silently runs an ancient binary (this exact failure
    # shipped a pre-fix tiler into a full archive build on 2026-10-19).
    # Prefer the workspace binary; fall back to the per-crate path only when
    # the workspace one is absent.
    $wsBuilt = Join-Path $PSScriptRoot "../../target/release/$name.exe"
    if (Test-Path $wsBuilt) {
        $wsItem = Get-Item $wsBuilt
        $crateItem = $null
        $cratePath = Join-Path $PSScriptRoot "$crate\target\release\$name.exe"
        if (Test-Path $cratePath) { $crateItem = Get-Item $cratePath }
        if ($null -eq $crateItem -or $wsItem.LastWriteTime -ge $crateItem.LastWriteTime) {
            return $wsItem.FullName
        }
        Write-Warning "$name.exe: per-crate copy ($($crateItem.LastWriteTime)) is newer than workspace copy ($($wsItem.LastWriteTime)); using per-crate copy"
    }
    $built = Join-Path $PSScriptRoot "$crate\target\release\$name.exe"
    if (-not (Test-Path $built)) {
        throw "no $name.exe after building $crate -- this is a builder bug, not your input"
    }
    return (Resolve-Path $built).Path
}

# Adaptive, because these span a 128-byte header and a 30 GB spill; a fixed
# unit makes one end of that unreadable.
function Size([double] $b) {
    if ($b -ge 1GB) { return "{0,9:N2} GB" -f ($b / 1GB) }
    if ($b -ge 1MB) { return "{0,9:N1} MB" -f ($b / 1MB) }
    if ($b -ge 1KB) { return "{0,9:N1} KB" -f ($b / 1KB) }
    return "{0,9:N0} B " -f $b
}

# --- inputs -------------------------------------------------------------
# -NoTransit drops the GTFS inputs entirely (no world.transit, no manifest).
$pairs = @(@($Pbf, "pbf"), @($Coastline, "coastline"), @($Dem, "dem"), @($Reviews, "reviews"))
if (-not $NoTransit) { $pairs += ,@($WorldTransit, "world.transit") }
foreach ($pair in $pairs) {
    if (-not (Test-Path $pair[0])) { throw "$($pair[1]) not found: $($pair[0])" }
}
$Pbf        = (Resolve-Path $Pbf).Path
$Coastline  = (Resolve-Path $Coastline).Path
$Dem        = (Resolve-Path $Dem).Path
$Out        = [System.IO.Path]::GetFullPath($Out)
$Reviews    = (Resolve-Path $Reviews).Path
if (-not $NoTransit) {
    $WorldTransit = (Resolve-Path $WorldTransit).Path
} else {
    $WorldTransit = $null
}

# Two things are on disk at once at the deepest zoom: one zoom's tile chunks
# (file backend only) and the archive being assembled — plus the feature spill
# on the file path. On north-america that was about 29 + 23 + 17 GB against a
# 14 GB .pbf, so six times the source is the rule of thumb. Under
# MAPS_ANON_SPILL=1 the spills live in the pagefile, leaving the archive plus
# sidecars (~2x), and running out an hour into z14, after stage A has already
# been paid for, is the failure this warning exists to flag.
#
# It is a warning, not a hard stop: the multiples are north-america-derived
# upper estimates, and a build may well fit in less. Proceeding on a smaller disk
# is allowed -- you accept the risk of a late z14 out-of-space.
$pbfBytes = (Get-Item $Pbf).Length
$anon = ($env:MAPS_ANON_SPILL -eq "1") -or ($env:MAPS_ANON_SPILL -ieq "true") -or ($env:MAPS_ANON_SPILL -ieq "yes")
$wanted = if ($anon) { $pbfBytes * 2 } else { $pbfBytes * 6 }
$root = [System.IO.Path]::GetPathRoot($Out)
$free = (New-Object System.IO.DriveInfo $root).AvailableFreeSpace
if ($free -lt $wanted) {
    $what = if ($anon) { "(archive + sidecars; spills are in the pagefile - check its cap too)" } else { "(feature spill + one zoom's tile chunks + the archive)" }
    Write-Warning (("{0} has {1:N1} GB free; this build may want up to about {2:N1} GB " -f `
            $root, ($free / 1GB), ($wanted / 1GB)) +
          "$what. Proceeding anyway -- " +
          "a large planet run could still hit an out-of-space at z14.")
}

$tmp = "$Out.buildtmp"
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$graphDir = Join-Path $tmp "graph"
$routes   = Join-Path $tmp "transit-routes.geojsonseq"
$log      = Join-Path $tmp "build.log"
# mamaps_build now emits a TILES-ONLY archive here; mamaps_pack folds the
# graph/POI/transit sidecars onto it to produce the final $Out.
$tilesTmp = Join-Path $tmp "tiles.mamaps"
$poiDir   = Join-Path $tmp "poi"
if (Test-Path $Out) { Remove-Item $Out -Force }

if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw "cargo not found. Install Rust from https://rustup.rs"
}

# Canonical thread behaviour: no inherited pool settings. MAPS_THREADS is the
# one knob both crates honour (see tile_build's par docs); RAYON_NUM_THREADS
# is adopted only when it is unset, so it is cleared rather than left to
# silently shrink a build someone asked for at full width.
if ($Threads -gt 0) { $env:MAPS_THREADS = "$Threads" } else { Remove-Item Env:MAPS_THREADS -ErrorAction SilentlyContinue }
Remove-Item Env:RAYON_NUM_THREADS -ErrorAction SilentlyContinue
Remove-Item Env:MAPS_PREFETCH_LANES -ErrorAction SilentlyContinue

# --- [1/7] build the host tools ------------------------------------------
# One crate builds all three PBF consumers now: road_graph, poi_extract and
# mamaps_build share osm_ingest, one pool, and one blob scan via the graph's
# sidecar. transit_shapes stays separate (it reads GTFS, not the PBF).
$null = Switch-Phase "tools"
Write-Host "[1/7] Building the host tools ($Region)"
# PS1-owned subphases: no Rust timer covers cargo itself, so each crate build
# is one subphase named for the binary it produces.
$crates = @(@("osm_ingest", "road_graph"), @("osm_ingest", "road_overlay"), @("osm_ingest", "poi_extract"), @("mamaps_build", "mamaps_build"))
if (-not $NoTransit) { $crates += ,@("gtfs_ingest", "transit_shapes") }
foreach ($crate in $crates) {
    Switch-Subphase $crate[1] | Out-Null
    cargo build --release --manifest-path (Join-Path $PSScriptRoot "$($crate[0])\Cargo.toml") --bin $($crate[1])
    if ($LASTEXITCODE -ne 0) { throw "cargo build $($crate[0])/$($crate[1]) failed with exit code $LASTEXITCODE" }
}
foreach ($bin in @("mamaps_dump", "mamaps_pack")) {
    Switch-Subphase $bin | Out-Null
    cargo build --release --manifest-path (Join-Path $PSScriptRoot "tile_build\Cargo.toml") --bin $bin
    if ($LASTEXITCODE -ne 0) { throw "cargo build tile_build/$bin failed with exit code $LASTEXITCODE" }
}
$roadGraph  = Find-Built "osm_ingest" "road_graph"
$roadOverlay = Find-Built "osm_ingest" "road_overlay"
$poiExtract = Find-Built "osm_ingest" "poi_extract"
if (-not $NoTransit) { $shapes = Find-Built "gtfs_ingest" "transit_shapes" } else { $shapes = $null }
$build      = Find-Built "mamaps_build" "mamaps_build"
$dump       = Find-Built "tile_build" "mamaps_dump"
$pack       = Find-Built "tile_build" "mamaps_pack"

# --- [2/7] road graph into temp ------------------------------------------
# The region filter lives here too: a california graph keeps only ways
# touching the state bbox, so the traffic/junction layers and the pack agree
# with the tiles. mamaps_build re-validates the dir before stage A — an empty
# or stale graph used to cost 43 minutes of stage A before failing.
$null = Switch-Phase "graph"
Write-Host "[2/7] Building the routing graph ($Region) -> $graphDir"
# caonly/naonly pass world: the extract IS the region, so no bbox filtering.
# Stdout is Tee'd to the phase log so the report can parse the TIMING line
# back out; bars stay live because Tee-Object does not redirect.
$graphLog = Join-Path $tmp "graph.log"
# Planet-scale inputs OOM the single-round write buffer (~48 B/edge x 1.08 B
# edges); --rounds 3 partitions the write into source-id ranges so peak RSS
# stays in the ~30 GB band (see osm_ingest README "Build-time memory"). Gated
# on input size so state extracts keep single-round behavior.
$graphRounds = @()
if ($pbfBytes -gt 10GB) { $graphRounds = @("--rounds", "3") }
& $roadGraph $Pbf --out $graphDir --region $BuildRegion @graphRounds | Tee-Object -FilePath $graphLog
if ($LASTEXITCODE -ne 0) { throw "road_graph failed with exit code $LASTEXITCODE" }
# Fail HERE, not 43 minutes into stage A: the world build died on a missing
# metadata.bin after paying the whole of stage A first.
if (-not (Test-Path (Join-Path $graphDir "metadata.bin"))) {
    throw "graph build produced no metadata.bin at $graphDir -- refusing to run stage A against an empty graph"
}
# Highway overlay: the arterial subgraph long routes search on device.
# mamaps_pack stages graphDir/overlay.bin as an optional section.
& $roadOverlay --graph $graphDir | Tee-Object -FilePath $graphLog -Append
if ($LASTEXITCODE -ne 0) { throw "road_overlay failed with exit code $LASTEXITCODE" }

# --- [3/7] transit routes from the world transit pack ---------------------
# -NoTransit skips every feed: an empty routes file, as if 0 feeds existed.
# The lines come out of inputs/world.transit itself (every route's fitted shape,
# colour and type), so the ~80 GB of raw feeds it was built from are not needed.
$null = Switch-Phase "transit"
$transitLog = Join-Path $tmp "transit.log"
if ($NoTransit) {
    Write-Host "[3/7] NoTransit: skipping all GTFS feeds (empty transit-routes)"
    [System.IO.File]::WriteAllText($routes, "")
} else {
Write-Host "[3/7] Deriving transit routes from $WorldTransit"
& $shapes $routes --pack $WorldTransit | Tee-Object -FilePath $transitLog
if ($LASTEXITCODE -ne 0) { throw "transit_shapes failed with exit code $LASTEXITCODE" }
}

# --- [4/7] the tiles-only archive (temp) ----------------------------------
# Started before the build, so the first spike cannot be missed. Under
# MAPS_ANON_SPILL the feature spill has no file: the sampler then tracks only
# the process high-water (commit charge is visible in the OS, not in a path).
$null = Switch-Phase "tiles"
$spill = [System.IO.Path]::ChangeExtension($tilesTmp, "features.tmp")
$sampler = Start-Job -ArgumentList "mamaps_build", $spill, $anon -ScriptBlock {
    param($name, $spill, $anon)
    $peakWs = 0L; $peakSpill = 0L
    while ($true) {
        $p = Get-Process -Name $name -ErrorAction SilentlyContinue
        if ($p) {
            $peakWs = [Math]::Max($peakWs, ($p | Measure-Object PeakWorkingSet64 -Maximum).Maximum)
        }
        if (-not $anon -and (Test-Path $spill)) {
            $len = (Get-Item $spill -ErrorAction SilentlyContinue).Length
            if ($len -gt $peakSpill) { $peakSpill = $len }
        }
        [pscustomobject]@{ Ws = $peakWs; Spill = $peakSpill }
        Start-Sleep -Milliseconds 250
    }
}

Write-Host "[4/7] Building $(Split-Path $tilesTmp -Leaf) (tiles only)   z0..z14$(if ($Threads -gt 0) { ", $Threads thread(s)" })"
Write-Host ""
# Tee-Object keeps the bars live on the console AND captures stdout to the log,
# which the summary below parses. No redirect: the bars need the console to redraw on.
# No Stopwatch here: the phase chain above already covers this step, from this
# boundary to the next Switch-Phase. (The old $sw made TOTAL WALL tiles-only.)
& $build --input $Pbf --out $tilesTmp `
    --coastline $Coastline `
    --graph $graphDir `
    --transit-routes $routes `
    --dem $Dem `
    --region $BuildRegion | Tee-Object -FilePath $log
$exit = $LASTEXITCODE

Start-Sleep -Milliseconds 400
$s = Receive-Job $sampler
Stop-Job $sampler; Remove-Job $sampler -Force
$peakWs    = ($s | Measure-Object Ws    -Maximum).Maximum
$peakSpill = ($s | Measure-Object Spill -Maximum).Maximum

# Sampler teardown (job stop + 400 ms settle above) is booked to tiles so the
# POI clock starts exactly when [5/7] prints.
$null = Switch-Phase "poi"
Write-Host ""
if ($exit -ne 0) {
    Stop-Phases
    $failedSecs = $script:TotalSw.Elapsed.TotalSeconds
    Write-Host ("FAILED, exit {0}, after {1:N1} s (temp kept at {2})" -f $exit, $failedSecs, $tmp)
    exit $exit
}

# --- [5/7] POI sidecars ---------------------------------------------------
# The offline POI / restaurant tags come from here: poi_extract bakes the five
# poi_*.bin side files the packer folds in. --index fixes the dir; --attrs,
# --spatial and --name-index default to their poi_*.bin names beside it, which
# is exactly the set mamaps_pack --poi expects. The --geojson output is the
# baked-vector feed and is not consumed by the pack, but poi_extract requires it.
Write-Host "[5/7] Extracting POI sidecars ($Region) -> $poiDir"
New-Item -ItemType Directory -Force -Path $poiDir | Out-Null
$poiLog = Join-Path $tmp "poi.log"
$poiArgs = @(
    $Pbf,
    "--geojson", (Join-Path $poiDir "poi.geojsonseq"),
    "--names", (Join-Path $poiDir "poi_names.bin"),
    "--index", (Join-Path $poiDir "poi_index.bin"),
    "--region", $BuildRegion,
    "--reviews", $Reviews
)
Write-Host "      merging review data from $Reviews"
& $poiExtract @poiArgs | Tee-Object -FilePath $poiLog
if ($LASTEXITCODE -ne 0) { throw "poi_extract failed with exit code $LASTEXITCODE" }

# --- [6/7] pack the sidecars onto the tiles archive -> FINAL $Out ----------
# mamaps_build emits tiles only (no MAMA8 footer); the graph / transit / POI
# sections the app's router, departure boards and POI tags need are appended
# here. This is the step that turns a tiles-only archive into a complete pack.
$null = Switch-Phase "pack"
Write-Host "[6/7] Packing graph + POI + transit sidecars -> $(Split-Path $Out -Leaf)"
$packLog = Join-Path $tmp "pack.log"
if ($NoTransit) {
    & $pack --tiles $tilesTmp --graph $graphDir --poi $poiDir --out $Out | Tee-Object -FilePath $packLog
} else {
    & $pack --tiles $tilesTmp --graph $graphDir --poi $poiDir --transit $WorldTransit --out $Out | Tee-Object -FilePath $packLog
}
if ($LASTEXITCODE -ne 0) { throw "mamaps_pack failed with exit code $LASTEXITCODE" }

# --- [7/7] header check + report ------------------------------------------
# The binary prints its own per-zoom table (`z0 ...`, `z1 ...`) plus the
# `classified ...` and `wrote ... build_id ...` lines. Parsed here rather than
# re-derived.
$null = Switch-Phase "verify"
# PS1-owned subphases: header dump, hash, and (under -Verify) the rebuild.
Switch-Subphase "header" | Out-Null
$logLines = Get-Content $log
$zooms = @()
foreach ($line in $logLines) {
    # Per-zoom row: `z` + zoom, tiles, features, points, dropped, bytes, map/merge/enc/app seconds.
    if ($line -match '^z(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)') {
        $zooms += [pscustomobject]@{
            zoom = [int]$Matches[1]; tiles = [long]$Matches[2]; features = [long]$Matches[3]
            points = [long]$Matches[4]; dropped = [long]$Matches[5]; bytes = [long]$Matches[6]
            map_ms = [long]([double]$Matches[7] * 1000); merge_ms = [long]([double]$Matches[8] * 1000)
            encode_ms = [long]([double]$Matches[9] * 1000); append_ms = [long]([double]$Matches[10] * 1000)
        }
    }
}
if ($zooms.Count -eq 0) { throw "no per-zoom rows parsed from $log -- the build printed no zN table" }

$h = @{}
foreach ($line in (& $dump $Out --mode header)) {
    $f = $line -split "`t"
    if ($f.Count -ge 2) { $h[$f[0]] = $f[1] }
}
# The renderer skips its repair pass on the strength of this claim.
if ($h["rings_validated"] -ne "true") { throw "rings_validated is '$($h["rings_validated"])' -- the archive is unshippable" }
# Header plus dictionary plus root index inside 16 KiB, so a cold open is one
# range request and not two.
$prefix = [long]$h["prefix_bytes"]
if ($prefix -gt 16384) { throw "prefix is $prefix bytes of 16384 -- a cold open costs two requests" }
Write-Host "[7/7] Header ok: rings_validated true, prefix $prefix/16384 B, build_id $($h["build_id"])"

Switch-Subphase "hash" | Out-Null
$sha = (Get-FileHash $Out -Algorithm SHA256).Hash
if ($Verify) {
    # harden_mamaps.sh's full 1/3/32-thread matrix, folded in cheaply: one
    # single-threaded rebuild must be byte-identical. A differing hash means an
    # emit path whose order comes from a pool (e.g. a HashMap iterated on emit).
    Write-Host "Verifying determinism: rebuilding at 1 thread + repacking"
    Switch-Subphase "rebuild" | Out-Null
    $verifyTiles = Join-Path $tmp "verify-tiles.mamaps"
    $verifyOut = Join-Path $tmp "verify.mamaps"
    $env:RAYON_NUM_THREADS = "1"
    Remove-Item Env:MAPS_THREADS -ErrorAction SilentlyContinue
    & $build --input $Pbf --out $verifyTiles `
        --coastline $Coastline `
        --graph $graphDir `
        --transit-routes $routes `
        --dem $Dem `
        --region $BuildRegion | Tee-Object -FilePath (Join-Path $tmp "verify.log") | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "verify rebuild failed with exit code $LASTEXITCODE" }
    if ($NoTransit) {
        & $pack --tiles $verifyTiles --graph $graphDir --poi $poiDir --out $verifyOut
    } else {
        & $pack --tiles $verifyTiles --graph $graphDir --poi $poiDir --transit $WorldTransit --out $verifyOut
    }
    if ($LASTEXITCODE -ne 0) { throw "verify pack failed with exit code $LASTEXITCODE" }
    $vsha = (Get-FileHash $verifyOut -Algorithm SHA256).Hash
    if ($vsha -ne $sha) { throw "determinism FAIL: 1-thread rebuild differs ($vsha vs $sha)" }
    Write-Host "  ok: 1-thread rebuild is byte-identical"
    if ($Threads -gt 0) { $env:MAPS_THREADS = "$Threads" }
    Remove-Item Env:RAYON_NUM_THREADS -ErrorAction SilentlyContinue
}

$archive = (Get-Item $Out).Length
# Stop the chain: this instant ends "verify" and freezes TOTAL WALL. From
# here on the report only reads $script:PhaseTimes -- no more timing.
Stop-Phases
$total = $script:TotalSw.Elapsed.TotalSeconds

# --- merge phase walls with Rust subphases --------------------------------
# $rustSubs["phase"] = [ordered]@{ sub = seconds } from the last TIMING line
# in each phase log. The `other` row per phase is PS1 wall minus the named
# subphases (clamped at zero): it absorbs spawn/pipe overhead and the
# microseconds of skew between the two clocks, so rows always sum to the wall.
$rustSubs = @{}
foreach ($pair in @(@("graph", $graphLog), @("transit", $transitLog), @("tiles", $log), @("poi", $poiLog), @("pack", $packLog))) {
    $parsed = Read-TimingLog $pair[1]
    if ($null -ne $parsed.phase) { $rustSubs[$pair[0]] = $parsed.phase.subs }
    if ($pair[0] -eq "tiles" -and $parsed.zooms.Count -gt 0) { $zooms = $parsed.zooms }
}
function Get-PhaseSubs([string] $phase) {
    # Merged view: Rust subphases in emission order, PS1-owned subphases
    # (tools builds, verify steps) in capture order,
    # then the closing `other` bucket. Every row sums to the phase wall.
    $merged = [ordered]@{}
    if ($rustSubs.Contains($phase)) {
        foreach ($k in $rustSubs[$phase].Keys) { $merged[$k] = $rustSubs[$phase][$k] }
    }
    foreach ($k in $script:SubTimes.Keys) {
        if ($k.StartsWith("$phase/")) {
            $sub = $k.Substring($phase.Length + 1)
            if ($merged.Contains($sub)) { $merged[$sub] += $script:SubTimes[$k] } else { $merged[$sub] = $script:SubTimes[$k] }
        }
    }
    $wall = $script:PhaseTimes[$phase]
    if ($null -eq $wall) { $wall = 0.0 }
    $named = 0.0
    foreach ($v in $merged.Values) { $named += $v }
    $merged["other"] = [Math]::Max(0.0, $wall - $named)
    return @{ wall = $wall; subs = $merged }
}
$phaseOrder = @("preflight", "tools", "graph", "transit", "tiles", "poi", "pack", "verify")
$phaseViews = [ordered]@{}
foreach ($name in $phaseOrder) { $phaseViews[$name] = Get-PhaseSubs $name }

Write-Host ""
Write-Host "======================== RESULT ========================"
Write-Host ""
Write-Host "TIME"
foreach ($name in $phaseOrder) {
    $view = $phaseViews[$name]
    Write-Host ("  {0,-24} {1,8:N1} s  {2,5:N1}%" -f $name, $view.wall, (100.0 * $view.wall / $total))
    foreach ($k in $view.subs.Keys) {
        $t = $view.subs[$k]
        if ($t -lt 0.05 -and $k -ne "other") { continue }
        Write-Host ("  {0,-24} {1,8:N1} s  {2,5:N1}%" -f "  $k", $t, (100.0 * $t / $total))
    }
}
Write-Host ("  {0,-24} {1,8:N1} s" -f "TOTAL WALL", $total)
Write-Host ""
Write-Host "PER ZOOM"
Write-Host ("  {0,-5}{1,12}{2,14}{3,16}{4,10}{5,9}{6,9}{7,9}" -f `
    "zoom", "tiles", "features", "body bytes", "map_s", "merge_s", "enc_s", "app_s")
foreach ($z in $zooms) {
    Write-Host ("  z{0,-4}{1,12:N0}{2,14:N0}{3,16:N0}{4,10:N1}{5,9:N1}{6,9:N1}{7,9:N1}" -f `
        $z.zoom, $z.tiles, $z.features, $z.bytes,
        ($z.map_ms / 1000.0), ($z.merge_ms / 1000.0), ($z.encode_ms / 1000.0), ($z.append_ms / 1000.0))
}
Write-Host ""
Write-Host "PEAK MEMORY"
Write-Host ("  {0,-24} {1}   kernel high-water, cannot be missed" -f "resident", (Size $peakWs))
if ($anon) {
    Write-Host ("  {0,-24} {1}   anon spill: no file; commit charge is in the pagefile" -f "feature spill, peak", "n/a")
} else {
    Write-Host ("  {0,-24} {1}   scratch, deleted" -f "feature spill, peak", (Size $peakSpill))
}
Write-Host ""
Write-Host ("  archive           {0}   {1}" -f (Size $archive), $Out)
Write-Host ("  build id          {0,14}" -f $h["build_id"])
Write-Host ("  sha256            {0}" -f $sha)

# timing.json beside the archive, before $tmp (and the phase logs) are
# deleted: the machine-readable twin of the TIME table above. Schema:
# { build_id, sha256, archive_bytes, total_wall_s, peak_rss_bytes,
#   peak_spill_bytes, phases: [{ name, wall_s, subphases: [{name, s}] }],
#   per_zoom: [{ zoom, tiles, features, points, dropped, bytes,
#                map_s, merge_s, encode_s, append_s }] }.
$timingJson = [ordered]@{
    build_id = $h["build_id"]; sha256 = $sha; archive_bytes = $archive
    total_wall_s = [Math]::Round($total, 3)
    peak_rss_bytes = $peakWs; peak_spill_bytes = $peakSpill
    region = $Region
    phases = @()
    per_zoom = @()
}
foreach ($name in $phaseOrder) {
    $view = $phaseViews[$name]
    $subs = @()
    foreach ($k in $view.subs.Keys) {
        $subs += [ordered]@{ name = $k; s = [Math]::Round($view.subs[$k], 3) }
    }
    $timingJson.phases += [ordered]@{ name = $name; wall_s = [Math]::Round($view.wall, 3); subphases = $subs }
}
foreach ($z in ($zooms | Sort-Object zoom)) {
    $timingJson.per_zoom += [ordered]@{
        zoom = $z.zoom; tiles = $z.tiles; features = $z.features
        points = $z.points; dropped = $z.dropped; bytes = $z.bytes
        map_s = [Math]::Round($z.map_ms / 1000.0, 1); merge_s = [Math]::Round($z.merge_ms / 1000.0, 1)
        encode_s = [Math]::Round($z.encode_ms / 1000.0, 1); append_s = [Math]::Round($z.append_ms / 1000.0, 1)
    }
}
$timingPath = [System.IO.Path]::ChangeExtension($Out, "timing.json")
($timingJson | ConvertTo-Json -Depth 6).Replace("`r`n", "`n") | Set-Content -Path $timingPath -NoNewline
Write-Host ("  timing            {0}" -f $timingPath)

# Temp is always removed on success now (no -Keep): the graph/POI/routes/tiles
# intermediates are reproducible from the inputs, and anon spills vanish with
# the process. A failed build keeps $tmp as evidence (see the FAILED branch).
Remove-Item $tmp -Recurse -Force
Write-Host ""
